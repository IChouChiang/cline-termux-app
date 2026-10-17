package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.termux.DetachedHandle
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every [KernelCommands] call runs the KS §0.5 catalog row with its timeout (docs/07 §3.4). */
class TermuxKernelCommandsTest {
    private class RecordingExecutor : TermuxExecutor {
        val runs = mutableListOf<Pair<TermuxCommand, Long>>()
        var next: () -> TermuxResult = { TermuxResult.success("", elapsedMillis = 5) }
        var presence: TermuxPresence = TermuxPresence.Installed(118, "0.118.3")
        override fun isTermuxInstalled() = presence
        override fun hasPermission() = true
        override fun runDetached(command: TermuxCommand): DetachedHandle = throw UnsupportedOperationException()
        override suspend fun run(command: TermuxCommand, timeoutMs: Long): TermuxResult {
            runs += command to timeoutMs
            return next()
        }
    }

    private val prefix = "/data/data/com.termux/files/usr"
    private val home = "/data/data/com.termux/files/home"

    @Test
    fun `command lines and timeouts match the catalog`() = runTest {
        val exec = RecordingExecutor()
        val commands = TermuxKernelCommands(exec)
        commands.readPortVersion()
        commands.hubStart(45_000)
        commands.hubStatus()
        commands.hubStop()
        commands.hubUpgrade()
        commands.readDiscovery()
        commands.tailDaemonLog()
        commands.doctorJson()
        commands.doctorFix()
        commands.wakeLock()
        commands.wakeUnlock()
        val lines = exec.runs.map { (cmd, t) -> listOf(cmd.path, *cmd.args.toTypedArray()).joinToString(" ") + " @" + t / 1000 }
        assertEquals(
            listOf(
                "$prefix/bin/cat $prefix/opt/cline-termux/current/VERSION @10",
                "$prefix/bin/cline hub start @45",
                // `hub status` has no --json option; it always prints one JSON line.
                "$prefix/bin/cline hub status @15",
                "$prefix/bin/cline hub stop @30",
                "$prefix/bin/cline hub upgrade --wait 30 @75",
                "$prefix/bin/cat $home/.cline/data/locks/hub/production.json @20",
                "$prefix/bin/tail -c 4000 $home/.cline/data/logs/hub-daemon.log @10",
                "$prefix/bin/cline doctor --json @30",
                "$prefix/bin/cline doctor fix --json @60",
                "$prefix/bin/termux-wake-lock @10",
                "$prefix/bin/termux-wake-unlock @10",
            ),
            lines,
        )
    }

    @Test
    fun `results and failures map onto KernelCommandResult without throwing`() = runTest {
        val exec = RecordingExecutor()
        val commands = TermuxKernelCommands(exec)
        exec.next = { TermuxResult.undelivered(150, "Failed to execute: not found", elapsedMillis = 5) }
        val internal = commands.readPortVersion() as KernelCommandResult.Completed
        assertTrue(!internal.delivered && internal.errmsg == "Failed to execute: not found")

        exec.next = { throw TermuxUnavailable.PermissionMissing() }
        assertEquals(KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.PermissionMissing, null), commands.hubStatus())

        exec.next = { throw TermuxUnavailable.TimedOut("cline hub stop", 30_000) }
        assertEquals(KernelCommandResult.TimedOut(30_000), commands.hubStop())

        exec.next = { throw TermuxUnavailable.ExternalAppsBlocked("allow-external-apps is false") }
        // An internal error, not Unavailable(StartFailed): the coordinator classifies the errmsg
        // as KernelState.ExternalAppsBlocked (KernelCoordinator.classifyInternalError).
        val blocked = commands.wakeLock() as KernelCommandResult.Completed
        assertTrue(!blocked.delivered && blocked.errmsg == "allow-external-apps is false")

        exec.presence = TermuxPresence.Disabled
        assertEquals(TermuxPresence.Disabled, commands.termuxPresence())
    }
}
