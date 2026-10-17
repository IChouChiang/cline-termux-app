package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.termux.TermuxPresence

/** What one RUN_COMMAND round trip came to, as the coordinator classifies it (KS §0.4). */
sealed interface KernelCommandResult {
    /** Termux ran (or tried to run) the command and posted a result. */
    data class Completed(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        /** Termux-internal error code; -1 means the command itself ran. */
        val err: Int = -1,
        val errmsg: String? = null,
    ) : KernelCommandResult {
        val delivered: Boolean get() = err == -1
        val ok: Boolean get() = delivered && exitCode == 0
        val lastStdoutLine: String? get() = stdout.lineSequence().lastOrNull { it.isNotBlank() }?.trim()
        val lastStderrLine: String? get() = stderr.lineSequence().lastOrNull { it.isNotBlank() }?.trim()

        /** Termux's own message, else stderr, else stdout: the text used for classification. */
        val diagnosticText: String get() = errmsg?.takeIf { it.isNotBlank() } ?: stderr.ifBlank { stdout }
    }

    /** No result within the command's budget; Termux may still be running it. */
    data class TimedOut(val afterMs: Long) : KernelCommandResult

    /** The intent could not be handed to Termux at all. */
    data class Unavailable(val reason: Reason, val detail: String?) : KernelCommandResult {
        enum class Reason { NotInstalled, PermissionMissing, StartFailed }
    }
}

/**
 * The RUN_COMMAND surface the kernel coordinator uses, by purpose rather than by command
 * line (KS §0.4, §0.5, E.7). [TermuxKernelCommands] implements it for the real Termux by
 * running the `ClineCommands` catalog through the `TermuxExecutor`; tests use
 * `FakeKernelCommands`. Every call returns rather than throws: an unavailable Termux is a
 * [KernelCommandResult.Unavailable], a silent one a [KernelCommandResult.TimedOut].
 */
interface KernelCommands {
    /** P1: `PackageManager.getPackageInfo("com.termux")` + enabled flag + version. */
    fun termuxPresence(): TermuxPresence

    /** P2: `checkSelfPermission(com.termux.permission.RUN_COMMAND)`. */
    fun hasRunCommandPermission(): Boolean

    /** `cat $PREFIX/opt/cline-termux/current/VERSION` (10 s). */
    suspend fun readPortVersion(): KernelCommandResult

    /** `cline hub start` (H2; the whole budget is [HUB_START_BUDGET_MS]). */
    suspend fun hubStart(timeoutMs: Long = HUB_START_BUDGET_MS): KernelCommandResult

    /** `cline hub status` (15 s); always prints one JSON line. */
    suspend fun hubStatus(): KernelCommandResult

    /** `cline hub stop` (30 s); stdout `{"stopped":true|false}`. */
    suspend fun hubStop(): KernelCommandResult

    /** `cline hub upgrade --wait 30` (75 s). */
    suspend fun hubUpgrade(): KernelCommandResult

    /** `cat ~/.cline/data/locks/hub/production.json` (20 s): the only way to the token. */
    suspend fun readDiscovery(): KernelCommandResult

    /** `tail -c 4000 ~/.cline/data/logs/hub-daemon.log` (10 s): matched for `entry.js`, then discarded. */
    suspend fun tailDaemonLog(): KernelCommandResult

    /** `cline doctor --json` (30 s). */
    suspend fun doctorJson(): KernelCommandResult

    /** `cline doctor fix --json` (60 s; `fix` is a subcommand, `--fix` is not an option). */
    suspend fun doctorFix(): KernelCommandResult

    /** `termux-wake-lock` (10 s). */
    suspend fun wakeLock(): KernelCommandResult

    /** `termux-wake-unlock` (10 s). */
    suspend fun wakeUnlock(): KernelCommandResult

    companion object {
        const val READ_PORT_VERSION_TIMEOUT_MS = 10_000L
        const val HUB_START_BUDGET_MS = 60_000L
        const val HUB_STATUS_TIMEOUT_MS = 15_000L
        const val HUB_STOP_TIMEOUT_MS = 30_000L

        /** `--wait 30` plus the time the CLI needs around it (KS §0.5: change both together). */
        const val HUB_UPGRADE_WAIT_S = 30
        const val HUB_UPGRADE_TIMEOUT_MS = (HUB_UPGRADE_WAIT_S + 45) * 1_000L
        const val READ_DISCOVERY_TIMEOUT_MS = 20_000L
        const val TAIL_DAEMON_LOG_TIMEOUT_MS = 10_000L
        const val DOCTOR_TIMEOUT_MS = 30_000L
        const val DOCTOR_FIX_TIMEOUT_MS = 60_000L
        const val WAKE_LOCK_TIMEOUT_MS = 10_000L
        const val DAEMON_LOG_TAIL_BYTES = 4_000
    }
}
