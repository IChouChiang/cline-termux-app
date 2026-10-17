package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable

/**
 * [KernelCommands] over the real Termux: every call is a `ClineCommands` catalog row
 * (docs/07 §3.4: `probeVersion`, `hubStart`, `hubStatus`, `hubStop`, `hubUpgrade`,
 * `wakeLock`/`wakeUnlock`, `readHubDiscovery`, `tailDaemonLog`, `doctorJson`, `doctorFix`)
 * run through the [TermuxExecutor], with its exceptions folded into [KernelCommandResult].
 * Command lines and timeouts live in the catalog only.
 */
class TermuxKernelCommands(private val termux: TermuxExecutor) : KernelCommands {

    override fun termuxPresence(): TermuxPresence = termux.isTermuxInstalled()

    override fun hasRunCommandPermission(): Boolean = termux.hasPermission()

    override suspend fun readPortVersion() = run(ClineCommands.probeVersion())

    override suspend fun hubStart(timeoutMs: Long) = run(ClineCommands.hubStart(), timeoutMs)

    override suspend fun hubStatus() = run(ClineCommands.hubStatus())

    override suspend fun hubStop() = run(ClineCommands.hubStop())

    override suspend fun hubUpgrade() = run(ClineCommands.hubUpgrade())

    override suspend fun readDiscovery() = run(ClineCommands.readHubDiscovery())

    override suspend fun readHubCmdline(pid: Int) = run(ClineCommands.readHubCmdline(pid))

    override suspend fun tailDaemonLog() = run(ClineCommands.tailDaemonLog())

    override suspend fun doctorJson() = run(ClineCommands.doctorJson())

    override suspend fun doctorFix() = run(ClineCommands.doctorFix())

    override suspend fun wakeLock() = run(ClineCommands.wakeLock())

    override suspend fun wakeUnlock() = run(ClineCommands.wakeUnlock())

    private suspend fun run(command: TermuxCommand, timeoutMs: Long = command.timeoutMs): KernelCommandResult = try {
        termux.run(command, timeoutMs).toKernelResult()
    } catch (e: TermuxUnavailable.TimedOut) {
        KernelCommandResult.TimedOut(e.afterMillis)
    } catch (_: TermuxUnavailable.NotInstalled) {
        KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.NotInstalled, null)
    } catch (_: TermuxUnavailable.PermissionMissing) {
        KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.PermissionMissing, null)
    } catch (e: TermuxUnavailable.StartFailed) {
        KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.StartFailed, e.message)
    } catch (e: TermuxUnavailable.ExternalAppsBlocked) {
        // An internal error carrying Termux's errmsg: the coordinator's classifyInternalError
        // turns the allow-external-apps text into KernelState.ExternalAppsBlocked.
        KernelCommandResult.Completed("", "", -1, err = EXTERNAL_APPS_ERR, errmsg = e.errmsg)
    }

    private companion object {
        /** Any value other than -1 marks the result as not delivered; the exception drops Termux's own code. */
        const val EXTERNAL_APPS_ERR = 2
    }

    private fun TermuxResult.toKernelResult() = KernelCommandResult.Completed(
        stdout = stdout,
        stderr = stderr,
        exitCode = exitCode,
        err = err,
        errmsg = errmsg?.takeIf { it.isNotBlank() },
    )
}
