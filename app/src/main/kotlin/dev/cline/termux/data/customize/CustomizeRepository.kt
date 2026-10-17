package dev.cline.termux.data.customize

import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.GlobalSettingsView
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxResult
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Customize lists (customize-and-rest.md §1.8, §1.9; docs/07 §4.6): skills, workflows, rules,
 * plugins (+ contributions), plugin tools and MCP names from Hub `settings.list`; built-in tools
 * from `cline config tools --json` plus `global-settings.json` for the appended `tasks` card;
 * hook files from `cline config hooks --json`. A 60 s in-memory cache keyed by workspace (desktop
 * `EXTENSION_LISTS_CACHE_TTL_MS`); nothing is written to disk.
 *
 * [invalidations] fires (and the cache is dropped) on `settings.changed`, on the marketplace's
 * `inventoryChanged`, and when the Hub connection comes back (the daemon may have reloaded
 * plugins); the screen re-runs [load] with its own workspace. The event's snapshot is never
 * applied directly: it may have been computed for another client's workspace.
 */
class CustomizeRepository(
    private val hub: HubClient,
    private val termux: TermuxExecutor,
    private val helper: HelperChannel,
    inventoryChanged: Flow<Unit>,
    scope: CoroutineScope,
    private val clock: Clock,
    private val ttlMs: Long = CACHE_TTL_MS,
) {
    /** A secondary source of the lists (CR §1.4 "Partial results": `<source>: <message>`). */
    enum class Source(val label: String) { Tools("tools"), Hooks("hooks"), Plugins("plugins") }

    data class SourceWarning(val source: Source, val failure: RepoFailure)

    data class Lists(
        val workspaceRoot: String,
        /** Null when `settings.list` failed ([loadError]). */
        val snapshot: SettingsSnapshot?,
        val builtinTools: List<BuiltinTool>,
        val hooks: List<HookFile>,
        val loadError: RepoFailure?,
        val warnings: List<SourceWarning>,
        /** Set when RUN_COMMAND could not run for that tab (the `KernelBlockedCard`, CR §1.9). */
        val toolsBlocked: TermuxProblem?,
        val hooksBlocked: TermuxProblem?,
        /** A workdir RUN_COMMAND failed with "No such file or directory" (CR §1.9). */
        val workspaceMissing: Boolean,
        val loadedAtMs: Long,
    ) {
        val root: String get() = ScopeRules.normalizeRoot(workspaceRoot)
        val counts: CustomizeCounts get() = customizeCounts(snapshot, builtinTools, hooks)
    }

    private val mutex = Mutex()
    private val cache = HashMap<String, Lists>()
    private val _invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** The lists may be stale; re-run [load] with `force = true` for the visible workspace. */
    val invalidations: SharedFlow<Unit> = _invalidations.asSharedFlow()

    init {
        scope.launch {
            merge(
                hub.sideEvents.filter { it.event == HubEvents.SETTINGS_CHANGED }.map { },
                inventoryChanged,
                hub.reconnects(),
            ).collect { invalidate() }
        }
    }

    /** Drops every cached workspace and tells the screens. */
    suspend fun invalidate() {
        mutex.withLock { cache.clear() }
        _invalidations.tryEmit(Unit)
    }

    /** The cached lists for [workspaceRoot], fresh or not (the "Loading with cache" state). */
    suspend fun cached(workspaceRoot: String): Lists? = mutex.withLock { cache[workspaceRoot] }

    suspend fun isFresh(workspaceRoot: String): Boolean = mutex.withLock {
        cache[workspaceRoot]?.let { clock.millis() - it.loadedAtMs < ttlMs } == true
    }

    /** The lists for [workspaceRoot]; served from the cache when younger than 60 s unless [force]. */
    suspend fun load(workspaceRoot: String, force: Boolean = false): Lists {
        if (!force) mutex.withLock { cache[workspaceRoot]?.takeIf { clock.millis() - it.loadedAtMs < ttlMs } }?.let { return it }
        val lists = fetch(workspaceRoot)
        mutex.withLock {
            // A failed reload keeps what was shown (cached cards stay below the error, CR §1.4).
            val previous = cache[workspaceRoot]
            cache[workspaceRoot] = if (lists.snapshot == null && previous?.snapshot != null) {
                lists.copy(snapshot = previous.snapshot, builtinTools = lists.builtinTools.ifEmpty { previous.builtinTools }, hooks = lists.hooks.ifEmpty { previous.hooks })
            } else {
                lists
            }
        }
        return mutex.withLock { cache.getValue(workspaceRoot) }
    }

    private suspend fun fetch(ws: String): Lists = coroutineScope {
        val settings = async { outcomeOf { hub.idempotentRead { hub.command(HubCommands.SETTINGS_LIST, HubPayloads.settingsList(ws, ws, includePluginTools = true)) } } }
        val tools = async { fetchTools(ws) }
        val hooks = async { fetchHooks(ws) }
        val settingsOut = settings.await()
        val toolsOut = tools.await()
        val hooksOut = hooks.await()

        val snapshot = (settingsOut as? Outcome.Ok)?.value?.let(SettingsSnapshot::fromReply)
        val loadError = when {
            settingsOut is Outcome.Failed -> settingsOut.failure
            snapshot == null -> RepoFailure.Malformed("settings.list reply has no snapshot")
            else -> null
        }
        val warnings = buildList {
            toolsOut.failure?.let { add(SourceWarning(Source.Tools, it)) }
            hooksOut.failure?.let { add(SourceWarning(Source.Hooks, it)) }
            snapshot?.plugins?.filter { it.contributions?.inspectionStatus == "failed" || it.loadError != null }?.forEach { plugin ->
                add(SourceWarning(Source.Plugins, RepoFailure.Hub(PLUGIN_INSPECTION_FAILED, listOfNotNull(plugin.name, plugin.loadError).joinToString(": "))))
            }
        }
        Lists(
            workspaceRoot = ws,
            snapshot = snapshot,
            builtinTools = toolsOut.value.orEmpty(),
            hooks = hooksOut.value.orEmpty(),
            loadError = loadError,
            warnings = warnings,
            toolsBlocked = (toolsOut.failure as? RepoFailure.Termux)?.problem,
            hooksBlocked = (hooksOut.failure as? RepoFailure.Termux)?.problem,
            workspaceMissing = toolsOut.workspaceMissing || hooksOut.workspaceMissing,
            loadedAtMs = clock.millis(),
        )
    }

    private class SourceResult<T>(val value: T?, val failure: RepoFailure?, val workspaceMissing: Boolean = false)

    private suspend fun fetchTools(ws: String): SourceResult<List<BuiltinTool>> {
        val result = when (val ran = outcomeOf { termux.run(ClineCommands.configToolsJson(ws)) }) {
            is Outcome.Ok -> ran.value
            is Outcome.Failed -> return SourceResult(null, ran.failure)
        }
        if (!result.ok) return SourceResult(null, RepoFailure.Command(result.exitCode, CliOutput.errorLine(result)), result.missingWorkdir())
        val disabled = when (val settings = outcomeOf { termux.run(ClineCommands.readGlobalSettings()) }) {
            is Outcome.Ok -> GlobalSettingsView.from(settings.value)
            is Outcome.Failed -> GlobalSettingsView.DEFAULT
        }
        return when (val parsed = outcomeOf { CliCatalogs.parseBuiltinTools(result.stdout, disabled.isToolEnabled(CliCatalogs.TASKS_TOOL_ID)) }) {
            is Outcome.Ok -> SourceResult(parsed.value, null)
            is Outcome.Failed -> SourceResult(null, parsed.failure)
        }
    }

    private suspend fun fetchHooks(ws: String): SourceResult<List<HookFile>> {
        val result = when (val ran = outcomeOf { termux.run(ClineCommands.configHooksJson(ws)) }) {
            is Outcome.Ok -> ran.value
            is Outcome.Failed -> return SourceResult(null, ran.failure)
        }
        if (!result.ok) return SourceResult(null, RepoFailure.Command(result.exitCode, CliOutput.errorLine(result)), result.missingWorkdir())
        return when (val parsed = outcomeOf { CliCatalogs.parseHooks(result.stdout) }) {
            is Outcome.Ok -> SourceResult(parsed.value, null)
            is Outcome.Failed -> SourceResult(null, parsed.failure)
        }
    }

    /**
     * The shell's "No such file or directory", or Termux refusing the RUN_COMMAND_WORKDIR itself
     * before running anything (`err` 150, FileUtils "The working directory not found at path").
     */
    private fun TermuxResult.missingWorkdir(): Boolean =
        (errmsg.orEmpty() + "\n" + stderr).let { it.contains(NO_SUCH_FILE) || it.contains(WORKDIR_NOT_FOUND, ignoreCase = true) }

    // ---- toggles (CR §1.8) ---------------------------------------------------------------------

    /**
     * Skill, workflow, plugin (incl. Agent Plugins with `toggleable`) by path. The reply snapshot
     * was computed for [workspaceRoot], so it replaces the cached one.
     */
    suspend fun toggleItem(workspaceRoot: String, item: SettingsItem, enabled: Boolean): Outcome<Lists> {
        val out = outcomeOf {
            hub.write {
                hub.command(
                    HubCommands.SETTINGS_TOGGLE,
                    buildJsonObject {
                        put("type", item.kind.type.wire)
                        put("path", item.path)
                        put("enabled", enabled)
                        put("workspaceRoot", workspaceRoot)
                        put("cwd", workspaceRoot)
                    },
                )
            }
        }
        return when (out) {
            is Outcome.Failed -> out
            is Outcome.Ok -> Outcome.Ok(applySnapshot(workspaceRoot, SettingsSnapshot.fromReply(out.value)))
        }
    }

    /**
     * A tool switch: `settings.toggle {type:"tools", name, enabled}` once per name, sequentially.
     * A built-in tool is updated in place (the CLI catalog is not re-run); plugin tools come from
     * the last reply's snapshot.
     */
    suspend fun toggleTool(workspaceRoot: String, names: List<String>, builtinId: String?, enabled: Boolean): Outcome<Lists> {
        var lastSnapshot: SettingsSnapshot? = null
        for (name in names.distinct()) {
            val out = outcomeOf {
                hub.write { hub.command(HubCommands.SETTINGS_TOGGLE, HubPayloads.settingsToggle(SettingsType.Tools.wire, name = name, enabled = enabled)) }
            }
            when (out) {
                is Outcome.Failed -> return out
                is Outcome.Ok -> lastSnapshot = SettingsSnapshot.fromReply(out.value) ?: lastSnapshot
            }
        }
        val updated = mutex.withLock {
            val current = cache[workspaceRoot] ?: return@withLock null
            val tools = if (builtinId == null) current.builtinTools else current.builtinTools.map { if (it.id == builtinId) it.copy(enabled = enabled) else it }
            val pluginTools = lastSnapshot?.tools
            val snapshot = if (pluginTools != null && current.snapshot != null) current.snapshot.copy(tools = pluginTools) else current.snapshot
            current.copy(builtinTools = tools, snapshot = snapshot).also { cache[workspaceRoot] = it }
        }
        return Outcome.Ok(updated ?: load(workspaceRoot, force = true))
    }

    /** "Enable all" / "Disable all" for a tools section: one switch at a time, stops at the first failure. */
    suspend fun toggleTools(workspaceRoot: String, tools: List<Pair<List<String>, String?>>, enabled: Boolean): Outcome<Lists> {
        var last: Outcome<Lists>? = null
        for ((names, builtinId) in tools) {
            last = toggleTool(workspaceRoot, names, builtinId, enabled)
            if (last is Outcome.Failed) return last
        }
        return last ?: Outcome.Ok(load(workspaceRoot))
    }

    private suspend fun applySnapshot(workspaceRoot: String, snapshot: SettingsSnapshot?): Lists = mutex.withLock {
        val current = cache[workspaceRoot]
        if (current == null || snapshot == null) return@withLock null
        current.copy(snapshot = snapshot, loadError = null).also { cache[workspaceRoot] = it }
    } ?: load(workspaceRoot, force = true)

    // ---- uninstall (CR §1.8) -----------------------------------------------------------------------

    sealed interface UninstallResult {
        /** [message] is the helper's own line; null = the UI says "Uninstalled <name>.". */
        data class Done(val message: String?) : UninstallResult
        data class Failed(val failure: RepoFailure) : UninstallResult

        /** Workflows have no CLI uninstall; the menu offers only "Copy path". */
        data object NotSupported : UninstallResult
    }

    /** Whether Uninstall is offered for [item] right now. */
    fun canUninstall(item: SettingsItem): Boolean = when (item.kind) {
        SettingsKind.Skill, SettingsKind.Plugin -> true
        SettingsKind.Workflow -> helper.isReady
        else -> false
    }

    suspend fun uninstall(workspaceRoot: String, item: SettingsItem): UninstallResult {
        val result = if (helper.isReady) uninstallViaHelper(item) else uninstallViaCli(item)
        if (result is UninstallResult.Done) {
            invalidate()
            load(workspaceRoot, force = true)
        }
        return result
    }

    private suspend fun uninstallViaHelper(item: SettingsItem): UninstallResult {
        val type = when (item.kind) {
            SettingsKind.Skill -> "skill"
            SettingsKind.Workflow -> "workflow"
            SettingsKind.Plugin -> "plugin"
            else -> return UninstallResult.NotSupported
        }
        val args = buildJsonObject {
            put("type", type)
            put("id", item.id)
            put("name", item.name)
            put("path", item.path)
        }
        return when (val out = outcomeOf { helper.desktopCommand(UNINSTALL_LOCAL_PRIMITIVE, args) }) {
            is Outcome.Ok -> UninstallResult.Done((out.value as? kotlinx.serialization.json.JsonObject)?.get("message")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content })
            is Outcome.Failed -> if (out.failure is RepoFailure.HelperDown) uninstallViaCli(item) else UninstallResult.Failed(out.failure)
        }
    }

    private suspend fun uninstallViaCli(item: SettingsItem): UninstallResult {
        val command = when (item.kind) {
            SettingsKind.Plugin -> ClineCommands.pluginUninstall(item.path)
            SettingsKind.Skill -> ClineCommands.skillRemove(ClineCommands.installedSkillDirectory(item.path))
            else -> return UninstallResult.NotSupported
        }
        return when (val out = termux.runChecked(command)) {
            is Outcome.Ok -> UninstallResult.Done(null)
            is Outcome.Failed -> UninstallResult.Failed(out.failure)
        }
    }

    companion object {
        /** Desktop `EXTENSION_LISTS_CACHE_TTL_MS`. */
        const val CACHE_TTL_MS = 60_000L

        /** CR §1.9: a stale "Uninstalling..." older than this is cleared on resume. */
        const val UNINSTALL_STALE_MS = 120_000L
        const val UNINSTALL_LOCAL_PRIMITIVE = "uninstall_local_primitive"
        const val PLUGIN_INSPECTION_FAILED = "plugin_inspection_failed"
        private const val NO_SUCH_FILE = "No such file or directory"
        private const val WORKDIR_NOT_FOUND = "working directory not found"
    }
}
