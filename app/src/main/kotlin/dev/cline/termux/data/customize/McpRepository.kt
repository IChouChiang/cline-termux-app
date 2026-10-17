package dev.cline.termux.data.customize

import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.McpTransport
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxResult
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP servers (customize-and-rest.md §1.8 MCP rows, §2.8, §2.9): the helper route
 * (`list_mcp_servers` / `upsert_mcp_server` / `delete_mcp_server`) when the settings helper is
 * `Ready`, otherwise the settings file over RUN_COMMAND and `cline mcp install|uninstall`, with
 * `settings.toggle {type:"mcp"}` over the Hub for the enabled state (which needs no helper).
 * Every change emits [inventoryChanged] (the marketplace ↔ customize channel, docs/07 §4.6).
 */
class McpRepository(
    private val hub: HubClient,
    private val termux: TermuxExecutor,
    private val helper: HelperChannel,
    private val inventoryChanged: MutableSharedFlow<Unit>,
) {
    /** Which route a write takes right now (CR §2.8 `McpRoute`). */
    enum class Route { Helper, Fallback }

    val route: Route get() = if (helper.isReady) Route.Helper else Route.Fallback

    /** Helper list when `Ready`, else the settings file (missing file = `hasSettingsFile:false`). */
    suspend fun list(): Outcome<McpServersResponse> =
        if (helper.isReady) {
            val viaHelper = outcomeOf { McpServersResponse.fromHelper(helper.desktopCommand(LIST)) }
            // A helper that died between the state check and the call: the file still answers.
            if (viaHelper is Outcome.Failed && viaHelper.failure is RepoFailure.HelperDown) readFile() else viaHelper
        } else {
            readFile()
        }

    /** RUN_COMMAND `cat cline_mcp_settings.json`, parsed with the dashboard's rules. */
    suspend fun readFile(): Outcome<McpServersResponse> {
        val result = when (val ran = outcomeOf { termux.run(ClineCommands.readMcpSettings()) }) {
            is Outcome.Ok -> ran.value
            is Outcome.Failed -> return ran
        }
        return when {
            result.ok -> outcomeOf { McpServersResponse.fromSettingsFile(result.stdout, TermuxPaths.MCP_SETTINGS_JSON) }
            result.noSuchFile -> Outcome.Ok(McpServersResponse.fromSettingsFile(null, TermuxPaths.MCP_SETTINGS_JSON))
            else -> Outcome.Failed(RepoFailure.Command(result.exitCode, CliOutput.errorLine(result)))
        }
    }

    /** `settings.toggle {type:"mcp", name, enabled}` (works without the helper). */
    suspend fun setEnabled(name: String, enabled: Boolean): Outcome<SettingsSnapshot?> = outcomeOf {
        val reply = hub.write { hub.command(HubCommands.SETTINGS_TOGGLE, HubPayloads.settingsToggle(SettingsType.Mcp.wire, name = name, enabled = enabled)) }
        SettingsSnapshot.fromReply(reply)
    }.also { if (it is Outcome.Ok) inventoryChanged.tryEmit(Unit) }

    /** The result of a save or delete. */
    sealed interface WriteResult {
        /** Done; [servers] is the fresh list when the route returned or re-read one. */
        data class Done(val servers: McpServersResponse?) : WriteResult

        /** The helper dropped mid-call and the re-read does not show the change (CR §2.9). */
        data object Unconfirmed : WriteResult

        data class Failed(val failure: RepoFailure) : WriteResult

        /** Fallback route refused: the stored server has env or cwd the CLI cannot keep (CR §2.8). */
        data object BlockedInFallback : WriteResult
    }

    /**
     * Create or edit. [previous] is the server as listed before the edit (null for a create);
     * the fallback route uses it to roll back a rename and to refuse env/cwd servers.
     */
    suspend fun save(input: McpServerInput, previous: McpServer?): WriteResult {
        val result = if (helper.isReady) saveViaHelper(input) else saveViaCli(input, previous)
        if (result is WriteResult.Done) inventoryChanged.tryEmit(Unit)
        return result
    }

    suspend fun delete(name: String): WriteResult {
        val result = if (helper.isReady) {
            when (val out = outcomeOf { McpServersResponse.fromHelper(helper.desktopCommand(DELETE, buildJsonObject { put("name", name) })) }) {
                is Outcome.Ok -> WriteResult.Done(out.value)
                is Outcome.Failed -> if (out.failure == RepoFailure.HelperLost) confirmByReread { it.find(name) == null } else WriteResult.Failed(out.failure)
            }
        } else {
            when (val out = runCli { ClineCommands.mcpUninstall(name) }) {
                is Outcome.Ok -> WriteResult.Done(readFile().valueOrNull())
                is Outcome.Failed -> WriteResult.Failed(out.failure)
            }
        }
        if (result is WriteResult.Done) inventoryChanged.tryEmit(Unit)
        return result
    }

    private suspend fun saveViaHelper(input: McpServerInput): WriteResult =
        when (val out = outcomeOf { McpServersResponse.fromHelper(helper.desktopCommand(UPSERT, buildJsonObject { put("input", input.toJson()) })) }) {
            is Outcome.Ok -> WriteResult.Done(out.value)
            is Outcome.Failed -> if (out.failure == RepoFailure.HelperLost) {
                confirmByReread { list -> list.find(input.name)?.let(input::matches) == true }
            } else {
                WriteResult.Failed(out.failure)
            }
        }

    /**
     * CR §2.8 fallback: (1) uninstall the old name on a rename, (2) `cline mcp install`, (3)
     * re-apply `disabled` over the Hub; if (2) fails after (1), reinstall [previous].
     */
    private suspend fun saveViaCli(input: McpServerInput, previous: McpServer?): WriteResult {
        if (previous != null && previous.hasEnvOrCwd) return WriteResult.BlockedInFallback
        if (input.isRename) {
            val removed = runCli { ClineCommands.mcpUninstall(input.previousName!!) }
            if (removed is Outcome.Failed) return WriteResult.Failed(removed.failure)
        }
        val installed = runCli { installCommand(input.name, input.transportType, input.command, input.args, input.url, input.headers) }
        if (installed is Outcome.Failed) {
            if (input.isRename && previous != null) {
                runCli { installCommand(previous.name, previous.transportType, previous.command, previous.args, previous.url, previous.headers) }
            }
            return WriteResult.Failed(installed.failure)
        }
        if (input.disabled) {
            val toggled = outcomeOf {
                hub.write { hub.command(HubCommands.SETTINGS_TOGGLE, HubPayloads.settingsToggle(SettingsType.Mcp.wire, name = input.name, enabled = false)) }
            }
            if (toggled is Outcome.Failed) return WriteResult.Failed(toggled.failure)
        }
        return WriteResult.Done(readFile().valueOrNull())
    }

    private suspend fun confirmByReread(check: (McpServersResponse) -> Boolean): WriteResult {
        val fresh = list().valueOrNull() ?: return WriteResult.Unconfirmed
        return if (check(fresh)) WriteResult.Done(fresh) else WriteResult.Unconfirmed
    }

    private suspend fun runCli(command: () -> dev.cline.termux.core.kernel.termux.TermuxCommand): Outcome<TermuxResult> =
        termux.runChecked(command())

    companion object {
        const val LIST = "list_mcp_servers"
        const val UPSERT = "upsert_mcp_server"
        const val DELETE = "delete_mcp_server"

        /** `cline mcp install` in the stdio or remote form (CR §2.8). */
        fun installCommand(
            name: String,
            type: McpTransportType,
            command: String?,
            args: List<String>?,
            url: String?,
            headers: Map<String, String>?,
        ) = if (type == McpTransportType.Stdio) {
            ClineCommands.mcpInstallStdio(name, command.orEmpty(), args.orEmpty())
        } else {
            ClineCommands.mcpInstallRemote(
                name,
                url.orEmpty(),
                if (type == McpTransportType.Sse) McpTransport.Sse else McpTransport.StreamableHttp,
                headers.orEmpty(),
            )
        }
    }
}
