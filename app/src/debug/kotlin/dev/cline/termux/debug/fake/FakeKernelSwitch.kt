package dev.cline.termux.debug.fake

import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.KernelCommands
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.hub.HubSocket
import dev.cline.termux.core.kernel.hub.HubTransport
import dev.cline.termux.core.kernel.hub.HubVersion
import dev.cline.termux.core.kernel.termux.DetachedHandle
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import kotlinx.coroutines.CoroutineScope

/**
 * Holds the active [ScriptedKernel] (null = the real kernel) and hands out seams that pick
 * the fake or the real implementation on every call. The app graph builds its executor,
 * coordinator commands, prober and Hub transport once per process, so the switch has to be
 * per call: a scenario applied from a launch intent takes effect without rebuilding them.
 */
class FakeKernelSwitch(
    private val scope: CoroutineScope,
    private val onTrace: (String) -> Unit = {},
) {
    @Volatile var current: ScriptedKernel? = null
        private set

    val scenario: FakeKernelScenario? get() = current?.scenario

    /** Replaces the active kernel; a fresh scenario restarts its timeline. Null returns to the real kernel. */
    @Synchronized
    fun select(scenario: FakeKernelScenario?) {
        if (current?.scenario == scenario) return
        current?.close()
        current = scenario?.let { ScriptedKernel(it, scope, onTrace = onTrace) }
    }

    fun executor(real: TermuxExecutor): TermuxExecutor = object : TermuxExecutor {
        override fun isTermuxInstalled(): TermuxPresence = (current?.executor ?: real).isTermuxInstalled()
        override fun hasPermission(): Boolean = (current?.executor ?: real).hasPermission()
        override suspend fun run(command: TermuxCommand, timeoutMs: Long): TermuxResult = (current?.executor ?: real).run(command, timeoutMs)
        override fun runDetached(command: TermuxCommand): DetachedHandle = (current?.executor ?: real).runDetached(command)
    }

    fun commands(real: KernelCommands): KernelCommands = object : KernelCommands {
        private val target: KernelCommands get() = current?.commands ?: real
        override fun termuxPresence() = target.termuxPresence()
        override fun hasRunCommandPermission() = target.hasRunCommandPermission()
        override suspend fun readPortVersion() = target.readPortVersion()
        override suspend fun hubStart(timeoutMs: Long) = target.hubStart(timeoutMs)
        override suspend fun hubStatus() = target.hubStatus()
        override suspend fun hubStop() = target.hubStop()
        override suspend fun hubUpgrade() = target.hubUpgrade()
        override suspend fun readDiscovery() = target.readDiscovery()
        override suspend fun readHubCmdline(pid: Int) = target.readHubCmdline(pid)
        override suspend fun tailDaemonLog() = target.tailDaemonLog()
        override suspend fun doctorJson() = target.doctorJson()
        override suspend fun doctorFix() = target.doctorFix()
        override suspend fun wakeLock() = target.wakeLock()
        override suspend fun wakeUnlock() = target.wakeUnlock()
    }

    fun prober(real: HubProber): HubProber = object : HubProber {
        override suspend fun health(port: Int): HubHealth? = (current?.prober ?: real).health(port)
        override suspend fun version(port: Int): HubVersion? = (current?.prober ?: real).version(port)
    }

    fun transport(real: HubTransport): HubTransport = object : HubTransport {
        override suspend fun open(url: String, token: String?): HubSocket = (current?.transport ?: real).open(url, token)
    }
}
