package dev.cline.termux.data.customize

import dev.cline.termux.core.kernel.dashboard.DashboardTimeouts
import dev.cline.termux.core.kernel.dashboard.DesktopCommandException
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.HelperUnavailable
import dev.cline.termux.core.kernel.dashboard.SettingsHelper
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.isDraining
import dev.cline.termux.core.kernel.hub.withDrainRetry
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.diagnostics.Redactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Shared plumbing of the tier-4 repositories (docs/07 §4.6; customize-and-rest.md §0.3, §0.5):
 * the helper seam, the typed failure every repository returns, the Hub read/write policies and
 * CLI output shaping. Kernel and CLI text stays data (DS §8.2 item 8); the UI maps the typed
 * cases to its own copy.
 */

/**
 * The settings helper as the repositories see it: its state (route selection, CR §0.3) and
 * `desktopCommand`. [SettingsHelper.asChannel] adapts the real lease; tests wrap a
 * `FakeDashboardClient`. Screens still `acquire`/`release` the lease themselves (SP §0.3).
 */
interface HelperChannel {
    val state: StateFlow<HelperState>

    /** @throws HelperUnavailable, DesktopCommandException */
    suspend fun desktopCommand(
        command: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DashboardTimeouts.forCommand(command),
    ): JsonElement

    /** The helper route is used only when the helper is already `Ready` (CR §0.3). */
    val isReady: Boolean get() = state.value is HelperState.Ready
}

fun SettingsHelper.asChannel(): HelperChannel {
    val helper = this
    return object : HelperChannel {
        override val state: StateFlow<HelperState> get() = helper.state
        override suspend fun desktopCommand(command: String, args: JsonObject, timeoutMs: Long): JsonElement =
            helper.desktopCommand(command, args, timeoutMs)
    }
}

/** Why a Termux call did not run (the `KernelBlockedCard` focus, SP §0.5, CR §1.9). */
enum class TermuxProblem { NotInstalled, PermissionMissing, ExternalAppsBlocked, TimedOut, StartFailed }

/** One typed failure for every tier-4 repository; [RepoFailure.message] is kernel/CLI text when it has any. */
sealed interface RepoFailure {
    /** `ok:false` from the Hub; [message] verbatim (`settings_list_failed`, `schedule_command_failed`, …). */
    data class Hub(val code: String, val message: String) : RepoFailure

    /** The socket was closed or not open (CR §0.5 "Cline Hub disconnected. Try again when it reconnects."). */
    data object HubDisconnected : RepoFailure

    /** No reply in time (CR §0.5 "The Cline Hub did not answer in time."); for writes the outcome is unknown. */
    data object HubTimeout : RepoFailure

    /** Still draining after the 30 s wait (CR §0.5 "Cline Hub is restarting. Try again in a moment."). */
    data object HubDraining : RepoFailure

    /** RUN_COMMAND could not run or answer. */
    data class Termux(val problem: TermuxProblem) : RepoFailure

    /** The CLI ran and exited non-zero; [message] is the redacted output (or the shaped desktop message). */
    data class Command(val exitCode: Int, val message: String) : RepoFailure

    /** The settings helper is not usable ([reason] null = no lease / idle). */
    data class HelperDown(val reason: HelperFailure?) : RepoFailure

    /** `desktopCommandResult {ok:false}`; [message] verbatim. */
    data class Helper(val message: String) : RepoFailure

    /** The helper socket dropped mid-call: unknown outcome, callers re-read (CR §2.9, §3.9). */
    data object HelperLost : RepoFailure

    /** The helper did not answer within its timer. */
    data object HelperTimeout : RepoFailure

    /** A reply or file that could not be parsed; [message] is diagnostic text. */
    data class Malformed(val message: String) : RepoFailure

    /** The best human-readable detail the failure carries, or null when the UI must supply copy. */
    val detail: String?
        get() = when (this) {
            is Hub -> message
            is Command -> message
            is Helper -> message
            is Malformed -> message
            else -> null
        }
}

/** A repository result: data or a typed failure. */
sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Failed(val failure: RepoFailure) : Outcome<Nothing>

    fun valueOrNull(): T? = (this as? Ok<T>)?.value
    fun failureOrNull(): RepoFailure? = (this as? Failed)?.failure
}

/** Maps a caught exception onto [RepoFailure]; rethrows cancellation. */
fun Throwable.toRepoFailure(): RepoFailure = when (this) {
    is CancellationException -> throw this
    is HubCommandException -> when {
        code == HubErrorCodes.HUB_CONNECTION_CLOSED -> RepoFailure.HubDisconnected
        code == HubErrorCodes.CLIENT_TIMEOUT || code == HubErrorCodes.HUB_COMMAND_TIMEOUT -> RepoFailure.HubTimeout
        isDraining() -> RepoFailure.HubDraining
        else -> RepoFailure.Hub(code, hubMessage)
    }
    is HubConnectionClosedException -> RepoFailure.HubDisconnected
    is TermuxUnavailable -> RepoFailure.Termux(toProblem())
    is HelperUnavailable.NotRunning -> RepoFailure.HelperDown(reason)
    is HelperUnavailable.TransportClosed -> RepoFailure.HelperLost
    is HelperUnavailable.Timeout -> RepoFailure.HelperTimeout
    is DesktopCommandException -> RepoFailure.Helper(error)
    else -> RepoFailure.Malformed(message ?: javaClass.simpleName)
}

fun TermuxUnavailable.toProblem(): TermuxProblem = when (this) {
    is TermuxUnavailable.NotInstalled -> TermuxProblem.NotInstalled
    is TermuxUnavailable.PermissionMissing -> TermuxProblem.PermissionMissing
    is TermuxUnavailable.ExternalAppsBlocked -> TermuxProblem.ExternalAppsBlocked
    is TermuxUnavailable.TimedOut -> TermuxProblem.TimedOut
    is TermuxUnavailable.StartFailed -> TermuxProblem.StartFailed
}

/** Runs [block] and wraps its value or failure. */
suspend inline fun <T> outcomeOf(block: () -> T): Outcome<T> = try {
    Outcome.Ok(block())
} catch (e: Throwable) {
    Outcome.Failed(e.toRepoFailure())
}

/**
 * Runs a CLI command; a non-zero exit becomes [RepoFailure.Command] with the redacted output
 * (or the CLI's error line when it printed nothing else).
 */
suspend fun TermuxExecutor.runChecked(command: TermuxCommand): Outcome<TermuxResult> {
    val result = when (val ran = outcomeOf { run(command) }) {
        is Outcome.Ok -> ran.value
        is Outcome.Failed -> return ran
    }
    if (result.ok) return Outcome.Ok(result)
    return Outcome.Failed(RepoFailure.Command(result.exitCode, CliOutput.of(result).ifEmpty { CliOutput.errorLine(result) }))
}

/** True when [failure] means the Hub may have applied a write we never heard back about. */
fun isUnknownWriteOutcome(failure: RepoFailure): Boolean =
    failure == RepoFailure.HubDisconnected || failure == RepoFailure.HubTimeout || failure == RepoFailure.HelperLost

/** How long an idempotent read waits for the socket to come back before its one retry (CR §0.5). */
const val READ_RETRY_WAIT_MS = 30_000L

/**
 * CR §0.5 idempotent read: on `hub_connection_closed` (or a socket that is not open) wait for
 * the next `Connected` and send once more; a drain refusal waits and resends once too.
 */
suspend fun <T> HubClient.idempotentRead(waitMs: Long = READ_RETRY_WAIT_MS, block: suspend () -> T): T {
    return try {
        withDrainRetry { block() }
    } catch (e: Throwable) {
        val retryable = (e is HubCommandException && e.code == HubErrorCodes.HUB_CONNECTION_CLOSED) || e is HubConnectionClosedException
        if (!retryable) throw e
        withTimeoutOrNull(waitMs) { state.first { it is HubConnectionState.Connected } } ?: throw e
        withDrainRetry { block() }
    }
}

/** CR §0.5 write: a drain refusal waits for `draining:false` (30 s) and is sent once more; nothing else is retried. */
suspend fun <T> HubClient.write(block: suspend () -> T): T = withDrainRetry { block() }

/** Emits once each time the Hub connection returns to `Connected` after the first observation (CR §1.9, §4.9). */
fun HubClient.reconnects(): Flow<Unit> =
    state.map { it is HubConnectionState.Connected }
        .distinctUntilChanged()
        .drop(1)
        .filter { it }
        .map { }

/** Output of a CLI call shaped for a message line (CR §3.5, §3.9): redacted, capped, last 12 lines when cut. */
object CliOutput {
    const val MAX_OUTPUT_CHARS = 12_000
    const val TRUNCATED_TAIL_LINES = 12

    fun of(result: TermuxResult): String {
        val combined = listOf(result.stdout.trim(), result.stderr.trim()).filter { it.isNotEmpty() }.joinToString("\n")
        val redacted = Redactor.redact(combined)
        val shaped = if (result.truncated) redacted.lines().takeLast(TRUNCATED_TAIL_LINES).joinToString("\n") else redacted
        return if (shaped.length > MAX_OUTPUT_CHARS) shaped.takeLast(MAX_OUTPUT_CHARS) else shaped
    }

    /** The CLI's own error line for a short message (`lastStderrLine`, else the last stdout line), redacted. */
    fun errorLine(result: TermuxResult): String =
        Redactor.redact(result.lastStderrLine ?: result.lastStdoutLine ?: result.errmsg ?: "exit code ${result.exitCode}")
}
