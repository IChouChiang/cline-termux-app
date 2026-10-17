package dev.cline.termux.data.approvals

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.KernelGlobalSettings
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsCommands
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsPatch
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The kernel's global tool approval mode (decided A1; docs/research/auto-approve-control.md §1):
 * `toolAutoApprove` in `global-settings.json`, where a missing key means Auto-approve all, as in
 * the terminal (`apps/cli/src/main.ts`). [Unknown] until the first successful read.
 */
enum class ApprovalMode {
    Unknown,
    Auto,
    Ask,
    ;

    companion object {
        fun of(autoApproveAll: Boolean): ApprovalMode = if (autoApproveAll) Auto else Ask
    }
}

/** Why the app re-reads the setting (auto-approve-control.md §6); only for tests and traces. */
enum class ApprovalRefresh {
    Foreground,
    SessionOpen,
    RunEnd,
    MenuOpen,
    SettingsOpen,
    HubReconnect,
    Resume,
    BeforeSend,
    NewSession,
}

/** Why a write did not take; the caller shows "Couldn't turn on/off Auto-approve all: <reason>". */
sealed interface ApprovalWriteFailure {
    /** The script found a `global-settings.json` it cannot parse and left it alone (exit 3). */
    data object Unreadable : ApprovalWriteFailure

    /** Termux refused or never answered; [detail] is the bridge's own text. */
    data class Unavailable(val detail: String) : ApprovalWriteFailure

    /** Any other exit: the last stderr line, or the exit code when stderr was empty. */
    data class Failed(val exitCode: Int, val detail: String?) : ApprovalWriteFailure
}

sealed interface ApprovalWriteResult {
    data object Written : ApprovalWriteResult
    data class Failed(val failure: ApprovalWriteFailure) : ApprovalWriteResult
}

/**
 * Reads and writes the kernel's global `toolAutoApprove` (batch 1 brief §7.1).
 *
 * - [refresh] reads the file through the existing `cat` ([ClineCommands.readGlobalSettings])
 *   and [KernelGlobalSettings.readOrNull]. An unreadable result keeps the last known mode; with
 *   none, [mode] stays [ApprovalMode.Unknown] and new sessions get Ask (the safe value).
 * - [set] writes [KernelGlobalSettingsPatch] through [KernelGlobalSettingsCommands.write]:
 *   optimistic, rolled back on failure. The write runs in [scope] so a closed screen cannot
 *   leave it half done.
 * - [externalChanges] emits the new value once when a read finds a value different from the
 *   last one the app read or wrote (the terminal flipped it; snackbar "turned on/off outside
 *   the app").
 *
 * Nothing here logs the file's content; command labels only. [clock] is monotonic milliseconds
 * (the age of the last read, for [refresh]'s `maxAgeMs`).
 */
class ApprovalModeRepository(
    private val run: suspend (TermuxCommand) -> TermuxResult,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val _mode = MutableStateFlow(ApprovalMode.Unknown)

    /** The global mode as last read or written. */
    val mode: StateFlow<ApprovalMode> = _mode.asStateFlow()

    private val _writing = MutableStateFlow(false)

    /** A write is in flight (Settings shows its spinner, the menu ignores another pick). */
    val writing: StateFlow<Boolean> = _writing.asStateFlow()

    private val _externalChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 8)

    /** `true` = turned on outside the app, `false` = turned off. */
    val externalChanges: SharedFlow<Boolean> = _externalChanges.asSharedFlow()

    private val lock = Mutex()

    /** The last value the app read or wrote; null before the first one. */
    private var known: Boolean? = null
    private var inFlight: Deferred<ApprovalMode>? = null

    /** [clock] time of the last successful read or write; null before the first one. */
    @Volatile private var settledAt: Long? = null

    /**
     * Re-reads the file; concurrent callers share one read. Returns the mode afterwards. With
     * [maxAgeMs] > 0, a read or write at most that old is current enough and nothing is run.
     */
    suspend fun refresh(reason: ApprovalRefresh, maxAgeMs: Long = 0): ApprovalMode {
        if (maxAgeMs > 0) {
            val at = settledAt
            if (at != null && clock() - at <= maxAgeMs) return _mode.value
        }
        val read = synchronized(this) {
            inFlight?.takeIf { it.isActive } ?: scope.async { lock.withLock { readLocked() } }.also { inFlight = it }
        }
        return read.await()
    }

    /**
     * The `toolPolicies` choice for a session the app creates or resumes now: Auto only when the
     * global mode is known to be Auto. An unknown mode is read once first.
     */
    suspend fun autoApproveForNewSession(): Boolean {
        if (_mode.value == ApprovalMode.Unknown) refresh(ApprovalRefresh.NewSession)
        return _mode.value == ApprovalMode.Auto
    }

    /** [refresh] with [reason], then the same answer as [autoApproveForNewSession]. */
    suspend fun autoApproveAfterRefresh(reason: ApprovalRefresh): Boolean = refresh(reason) == ApprovalMode.Auto

    /** Turns Auto-approve all on ([auto]) or off; the menu, the sheet's caret and Settings call it. */
    suspend fun set(auto: Boolean): ApprovalWriteResult = scope.async { lock.withLock { writeLocked(auto) } }.await()

    private suspend fun readLocked(): ApprovalMode {
        val result = try {
            run(ClineCommands.readGlobalSettings())
        } catch (e: CancellationException) {
            throw e
        } catch (_: TermuxUnavailable) {
            return _mode.value
        }
        val settings = KernelGlobalSettings.readOrNull(result) ?: return _mode.value
        adopt(settings.autoApproveAll, external = true)
        return _mode.value
    }

    private suspend fun writeLocked(auto: Boolean): ApprovalWriteResult {
        val before = _mode.value
        _mode.value = ApprovalMode.of(auto)
        _writing.value = true
        try {
            val result = try {
                run(KernelGlobalSettingsCommands.write(KernelGlobalSettingsPatch(toolAutoApprove = auto)))
            } catch (e: TermuxUnavailable) {
                _mode.value = before
                return ApprovalWriteResult.Failed(ApprovalWriteFailure.Unavailable(e.message.orEmpty()))
            }
            if (!result.ok) {
                _mode.value = before
                val failure = if (result.delivered && result.exitCode == KernelGlobalSettingsCommands.EXIT_UNREADABLE) {
                    ApprovalWriteFailure.Unreadable
                } else {
                    ApprovalWriteFailure.Failed(result.exitCode, result.lastStderrLine)
                }
                return ApprovalWriteResult.Failed(failure)
            }
            // The script prints the merged file: adopt what it says (it is the file now).
            val written = KernelGlobalSettings.parse(result.stdout)?.autoApproveAll ?: auto
            adopt(written, external = false)
            return ApprovalWriteResult.Written
        } catch (e: CancellationException) {
            _mode.value = before
            throw e
        } finally {
            _writing.value = false
        }
    }

    private fun adopt(autoApproveAll: Boolean, external: Boolean) {
        val previous = known
        known = autoApproveAll
        settledAt = clock()
        _mode.value = ApprovalMode.of(autoApproveAll)
        if (external && previous != null && previous != autoApproveAll) _externalChanges.tryEmit(autoApproveAll)
    }
}
