package dev.cline.termux.core.kernel.termux

/**
 * The RUN_COMMAND bridge seam (docs/07 §3.3). [TermuxRunner] is the real one; tests use
 * `FakeTermuxExecutor`. Nothing in `ui/` talks to it directly (§0 rule 8).
 *
 * Rules every implementation keeps: stdout and stderr are ANSI-stripped before they are
 * returned; success of a CLI call is `exitCode == 0`, never a stdout match; only the
 * command label and timing are ever logged.
 */
interface TermuxExecutor {
    /** KS P1: resolvable through `<queries>`, enabled, with its version. */
    fun isTermuxInstalled(): TermuxPresence

    /** KS P2: `com.termux.permission.RUN_COMMAND` granted. */
    fun hasPermission(): Boolean

    /**
     * Hands [command] to Termux and suspends until its result intent arrives.
     *
     * @param timeoutMs completion timeout; defaults to the catalog value carried by the
     *   command (60 s for a plain command, docs/07 §3.3).
     * @throws TermuxUnavailable.NotInstalled, [TermuxUnavailable.PermissionMissing],
     *   [TermuxUnavailable.StartFailed] when the intent cannot be delivered;
     *   [TermuxUnavailable.TimedOut] after [timeoutMs]; [TermuxUnavailable.ExternalAppsBlocked]
     *   when Termux refused it because `allow-external-apps` is off.
     */
    suspend fun run(command: TermuxCommand, timeoutMs: Long = command.timeoutMs): TermuxResult

    /**
     * Starts [command] and returns at once. No completion timeout: the handle's
     * `earlyExit` only tells the caller that the command ended (SP §0.5).
     *
     * @throws TermuxUnavailable as [run], except [TermuxUnavailable.TimedOut].
     */
    fun runDetached(command: TermuxCommand): DetachedHandle
}
