package dev.cline.termux.data.marketplace

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.data.customize.CliOutput
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.HelperChannel
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.customize.idempotentRead
import dev.cline.termux.data.customize.isUnknownWriteOutcome
import dev.cline.termux.data.customize.outcomeOf
import dev.cline.termux.data.customize.toRepoFailure
import java.io.File
import java.io.IOException
import java.time.Clock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Marketplace (customize-and-rest.md §3.8, §3.9; docs/07 §4.6): the catalog straight from
 * `https://cline.github.io/marketplace/catalog.json` (browsing never needs the helper) with a
 * 10 min in-memory TTL over a 4 MB OkHttp disk cache, falling back to the cached copy (≤ 30
 * days, marked stale) when the network fails; installed keys from the helper or, without it,
 * approximated from `settings.list`; install and uninstall helper-first, then the dashboard's
 * own CLI commands. Every change emits [inventoryChanged], which Customize listens to.
 */
class MarketplaceRepository(
    private val http: OkHttpClient,
    private val hub: HubClient,
    private val termux: TermuxExecutor,
    private val helper: HelperChannel,
    private val clock: Clock,
    private val catalogUrl: String = CATALOG_URL,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The marketplace ↔ customize channel (docs/07 §4.6); MCP saves emit it too. */
    val inventoryChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** CR §3.8 `CatalogLoad`. */
    sealed interface CatalogLoad {
        data class Loaded(val catalog: MarketplaceCatalog, val stale: Boolean) : CatalogLoad

        /** [httpStatus] set for a non-2xx answer (UI: "Failed to fetch marketplace: <status>"); null = network. */
        data class Error(val httpStatus: Int?, val message: String) : CatalogLoad
    }

    private class Memo(val catalog: MarketplaceCatalog, val stale: Boolean, val atMs: Long)

    private val mutex = Mutex()
    private var memo: Memo? = null

    /** The last catalog held in memory, without a request (Customize's badge matching, CR §1.8). */
    val cachedCatalog: MarketplaceCatalog? get() = memo?.catalog

    suspend fun catalog(force: Boolean = false): CatalogLoad = mutex.withLock {
        val now = clock.millis()
        memo?.takeIf { !force && !it.stale && now - it.atMs < MEMORY_TTL_MS }?.let { return@withLock CatalogLoad.Loaded(it.catalog, stale = false) }
        val network = fetch(if (force) CacheControl.FORCE_NETWORK else null)
        val result = if (network is CatalogLoad.Loaded) network else (fetch(STALE_CACHE) as? CatalogLoad.Loaded)?.copy(stale = true) ?: network
        if (result is CatalogLoad.Loaded) memo = Memo(result.catalog, result.stale, now)
        result
    }

    private suspend fun fetch(cacheControl: CacheControl?): CatalogLoad = withContext(io) {
        val request = Request.Builder()
            .url(catalogUrl)
            .header("Accept", "application/json")
            .apply { cacheControl?.let { cacheControl(it) } }
            .build()
        try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CatalogLoad.Error(response.code, "Failed to fetch marketplace: ${response.code}")
                val body = response.body.string()
                CatalogLoad.Loaded(MarketplaceCatalog.parse(CustomizeJson.parseToJsonElement(body)), stale = false)
            }
        } catch (e: IOException) {
            CatalogLoad.Error(null, e.message ?: "Failed to fetch marketplace")
        } catch (e: IllegalArgumentException) {
            CatalogLoad.Error(null, e.message ?: "Failed to fetch marketplace")
        }
    }

    // ---- installed keys --------------------------------------------------------------------------

    /** [approximate] when computed from `settings.list` instead of the helper (CR §3.8). */
    data class Installed(val keys: Set<String>, val approximate: Boolean)

    suspend fun installedKeys(catalog: MarketplaceCatalog, workspaceRoot: String): Outcome<Installed> {
        if (helper.isReady) {
            val viaHelper = outcomeOf {
                val reply = helper.desktopCommand(
                    LIST_INSTALLED,
                    buildJsonObject { put("entries", JsonArray(catalog.entries.map { it.toJson() })) },
                ) as? JsonObject
                val keys = (reply?.get("installedKeys") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
                Installed(keys, approximate = false)
            }
            if (viaHelper !is Outcome.Failed || viaHelper.failure !is RepoFailure.HelperDown) return viaHelper
        }
        return when (val snapshot = settingsSnapshot(workspaceRoot)) {
            is Outcome.Ok -> Outcome.Ok(Installed(approximateInstalledKeys(catalog, snapshot.value), approximate = true))
            is Outcome.Failed -> snapshot
        }
    }

    private suspend fun settingsSnapshot(workspaceRoot: String): Outcome<SettingsSnapshot> = outcomeOf {
        val reply = hub.idempotentRead { hub.command(HubCommands.SETTINGS_LIST, HubPayloads.settingsList(workspaceRoot, workspaceRoot, includePluginTools = false)) }
        SettingsSnapshot.fromReply(reply) ?: SettingsSnapshot.EMPTY
    }

    // ---- install / uninstall -------------------------------------------------------------------------

    /** Typed so ui/marketplace words every result from resources (DS §8.2.1, §8.2.3). */
    sealed interface ActionResult {
        data class Done(val done: MarketplaceDone) : ActionResult

        data class Failed(val failure: MarketplaceFailure) : ActionResult {
            constructor(failure: RepoFailure) : this(MarketplaceFailure.Repo(failure))
        }

        /** The helper socket dropped or timed out mid-install: re-read installed keys (CR §3.9). */
        data object Unknown : ActionResult
    }

    suspend fun install(entry: MarketplaceEntry, workspaceRoot: String): ActionResult =
        act(entry, workspaceRoot, INSTALL) { installViaCli(entry) }

    suspend fun uninstall(entry: MarketplaceEntry, workspaceRoot: String): ActionResult =
        act(entry, workspaceRoot, UNINSTALL) { uninstallViaCli(entry, workspaceRoot) }

    private suspend fun act(entry: MarketplaceEntry, workspaceRoot: String, command: String, fallback: suspend () -> ActionResult): ActionResult {
        val result = if (helper.isReady) {
            val out = outcomeOf { helper.desktopCommand(command, buildJsonObject { put("entry", entry.toJson()) }) }
            when {
                out is Outcome.Ok -> ActionResult.Done(
                    ((out.value as? JsonObject)?.get("message") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { MarketplaceDone.Message(it) }
                        ?: MarketplaceDone.of(entry, command == INSTALL),
                )
                out is Outcome.Failed && out.failure is RepoFailure.HelperDown -> fallback()
                out is Outcome.Failed && (isUnknownWriteOutcome(out.failure) || out.failure == RepoFailure.HelperTimeout) -> ActionResult.Unknown
                else -> ActionResult.Failed((out as Outcome.Failed).failure)
            }
        } else {
            fallback()
        }
        if (result !is ActionResult.Failed) inventoryChanged.tryEmit(Unit)
        return result
    }

    /** The dashboard's own CLI commands (CR §3.8), 300 s. */
    private suspend fun installViaCli(entry: MarketplaceEntry): ActionResult {
        val args = entry.install.args
        val command = when (entry.type) {
            MarketplaceType.Mcp -> ClineCommands.mcpInstallMarketplace(args)
            MarketplaceType.Plugin -> {
                if (args.size != 1) return ActionResult.Failed(MarketplaceFailure.PluginArguments)
                ClineCommands.pluginInstall(args.single())
            }
            MarketplaceType.Skill -> ClineCommands.skillAdd(args)
        }
        return runInstallCommand(command, CliStep.of(entry.type, install = true), MarketplaceDone.of(entry, install = true))
    }

    private suspend fun uninstallViaCli(entry: MarketplaceEntry, workspaceRoot: String): ActionResult {
        val first = entry.install.args.firstOrNull() ?: return ActionResult.Done(MarketplaceDone.NotInstalled(entry.name))
        val command = when (entry.type) {
            MarketplaceType.Mcp -> ClineCommands.mcpUninstall(first)
            MarketplaceType.Plugin -> ClineCommands.pluginUninstall(first)
            MarketplaceType.Skill -> {
                val snapshot = when (val s = settingsSnapshot(workspaceRoot)) {
                    is Outcome.Ok -> s.value
                    is Outcome.Failed -> return ActionResult.Failed(s.failure)
                }
                val skill = matchingSkill(entry, snapshot.skills) ?: return ActionResult.Done(MarketplaceDone.NotInstalled(entry.name))
                ClineCommands.skillRemove(ClineCommands.installedSkillDirectory(skill.path))
            }
        }
        return runInstallCommand(command, CliStep.of(entry.type, install = false), MarketplaceDone.of(entry, install = false))
    }

    /** [success] on exit 0; otherwise the typed "<step> failed with exit code <n>" with the CLI's own output. */
    private suspend fun runInstallCommand(command: TermuxCommand, step: CliStep, success: MarketplaceDone): ActionResult {
        val result = try {
            termux.run(command)
        } catch (e: Throwable) {
            return ActionResult.Failed(e.toRepoFailure())
        }
        if (result.ok) return ActionResult.Done(success)
        if (!result.delivered) return ActionResult.Failed(RepoFailure.Command(result.exitCode, CliOutput.errorLine(result)))
        return ActionResult.Failed(MarketplaceFailure.Cli(step, result.exitCode, CliOutput.of(result)))
    }

    companion object {
        /** `apps/cline-hub/src/server/marketplace.ts` `MARKETPLACE_CATALOG_URL`. */
        const val CATALOG_URL = "https://cline.github.io/marketplace/catalog.json"
        const val MEMORY_TTL_MS = 10 * 60_000L
        const val DISK_CACHE_BYTES = 4L * 1024 * 1024
        const val MAX_STALE_SECONDS = 2_592_000
        const val CACHE_DIR = "marketplace"
        const val LIST_INSTALLED = "list_marketplace_installed_entries"
        const val INSTALL = "install_marketplace_entry"
        const val UNINSTALL = "uninstall_marketplace_entry"

        /** `only-if-cached, max-stale=2592000` (30 days). */
        val STALE_CACHE: CacheControl = CacheControl.Builder().onlyIfCached().maxStale(MAX_STALE_SECONDS, TimeUnit.SECONDS).build()

        /** The marketplace's own client: 4 MB disk cache in `cacheDir/marketplace`, no logging interceptor. */
        fun httpClient(cacheDir: File): OkHttpClient = OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, CACHE_DIR), DISK_CACHE_BYTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Installed state without the helper (CR §3.8): mcp ⇔ an MCP item named `install.args[0]`;
         * skill ⇔ a skill named `entry.id` or `entry.name` (case-insensitive); plugin ⇔ a plugin
         * named `entry.id` or whose path contains `/<entry.id>`.
         */
        fun approximateInstalledKeys(catalog: MarketplaceCatalog, snapshot: SettingsSnapshot): Set<String> =
            catalog.entries.filter { entry ->
                when (entry.type) {
                    MarketplaceType.Mcp -> entry.install.args.firstOrNull()?.let { arg -> snapshot.mcp.any { it.name == arg } } == true
                    MarketplaceType.Skill -> matchingSkill(entry, snapshot.skills) != null
                    MarketplaceType.Plugin -> snapshot.plugins.any { it.name == entry.id || it.path.contains("/${entry.id}") }
                }
            }.map { it.key }.toSet()

        fun matchingSkill(entry: MarketplaceEntry, skills: List<SettingsItem>): SettingsItem? =
            skills.firstOrNull { it.name.equals(entry.id, ignoreCase = true) || it.name.equals(entry.name, ignoreCase = true) }
    }
}

/**
 * A finished install or uninstall (CR §3.5). Only the settings helper's own [Message] is kernel
 * text; the CLI route's dashboard wording is app copy that ui/marketplace takes from
 * strings_marketplace.xml.
 */
@Immutable
sealed interface MarketplaceDone {
    /** The settings helper's `message`, shown verbatim (DS §8.2 item 8). */
    data class Message(val text: String) : MarketplaceDone

    /** "Installed <name>." or, for a skill ([global]), "Installed <name> globally for Cline.". */
    data class Installed(val name: String, val global: Boolean) : MarketplaceDone

    /** "Uninstalled <name>.". */
    data class Uninstalled(val name: String) : MarketplaceDone

    /** "<name> is not installed." (the CLI route found nothing to remove). */
    data class NotInstalled(val name: String) : MarketplaceDone

    companion object {
        /** The dashboard's success result (`sdk/packages/core/src/services/marketplace.ts`). */
        fun of(entry: MarketplaceEntry, install: Boolean): MarketplaceDone =
            if (install) Installed(entry.name, global = entry.type == MarketplaceType.Skill) else Uninstalled(entry.name)
    }
}

/** The CLI-route command that ran; each has its own "failed with exit code" sentence. */
enum class CliStep {
    McpInstall, PluginInstall, SkillInstall, McpUninstall, PluginUninstall, SkillUninstall;

    companion object {
        fun of(type: MarketplaceType, install: Boolean): CliStep = when (type) {
            MarketplaceType.Mcp -> if (install) McpInstall else McpUninstall
            MarketplaceType.Plugin -> if (install) PluginInstall else PluginUninstall
            MarketplaceType.Skill -> if (install) SkillInstall else SkillUninstall
        }
    }
}

/** A failed install or uninstall. */
@Immutable
sealed interface MarketplaceFailure {
    /** A shared Hub, helper or Termux failure (CR §0.5). */
    data class Repo(val failure: RepoFailure) : MarketplaceFailure

    /** The CLI exited non-zero: "<step> failed with exit code <n>", then its [output] (redacted, capped; may be empty). */
    data class Cli(val step: CliStep, val exitCode: Int, val output: String) : MarketplaceFailure

    /** A plugin entry whose install args are not exactly one source. */
    data object PluginArguments : MarketplaceFailure
}
