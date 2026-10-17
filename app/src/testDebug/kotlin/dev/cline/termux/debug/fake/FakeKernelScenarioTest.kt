package dev.cline.termux.debug.fake

import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.InMemoryKernelPreferences
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubClientIdentity
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.DetachedHandle
import dev.cline.termux.core.kernel.termux.ProviderPresence
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.readProviderPresence
import dev.cline.termux.fakes.FakeTokenCache
import dev.cline.termux.fakes.InMemoryCursorStore
import dev.cline.termux.fakes.TestKernelClock
import dev.cline.termux.ui.kernel.HubMismatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The real coordinator and Hub client over one [ScriptedKernel]: what the debug app runs. */
@OptIn(ExperimentalCoroutinesApi::class)
private class Rig(scope: TestScope, scenario: FakeKernelScenario) {
    val kernel = ScriptedKernel(scenario, scope, wallClockMs = { 1_760_000_000_000L + scope.testScheduler.currentTime }, random = Random(3))
    val prefs = InMemoryKernelPreferences()
    lateinit var coordinator: KernelCoordinator
    val client = HubClient(
        transport = kernel.transport,
        identity = HubClientIdentity("android-fake", appVersion = "0.1"),
        scope = scope,
        cursors = InMemoryCursorStore(),
        credentials = { coordinator.currentCredentials() },
        random = Random(7),
    )

    init {
        coordinator = KernelCoordinator(
            commands = kernel.commands, prober = kernel.prober, tokens = FakeTokenCache(), prefs = prefs,
            hubClient = client, scope = scope, clock = TestKernelClock(scope.testScheduler),
        )
    }

    val state: KernelState get() = coordinator.state.value
    val gate: KernelGate get() = coordinator.gate.value
}

@OptIn(ExperimentalCoroutinesApi::class)
class FakeKernelScenarioTest {
    private fun TestScope.finish() = coroutineContext.job.cancelChildren()

    private fun TestScope.connected(scenario: FakeKernelScenario): Rig {
        val rig = Rig(this, scenario)
        launch { rig.coordinator.ensureConnected(startIfDown = true) }
        advanceTimeBy(10_000)
        runCurrent()
        return rig
    }

    @Test
    fun `every spec scenario id resolves`() {
        val ids = listOf(
            "termux_missing", "termux_disabled", "termux_old", "permission_missing", "external_apps_blocked", "termux_not_set_up",
            "port_missing", "port_old", "hub_start_slow", "hub_start_fail", "hub_start_hang", "hub_start_port_old", "stopped_in_termux",
            "hub_comes_back", "restart_busy_other_client", "restart_slow", "hub_crash_loop", "asleep_after_ready", "protocol_unsupported",
            "auth_failed", "draining", "providers_empty", "providers_cline", "providers_anthropic", "providers_ollama", "auth_ok",
            "auth_fail_invalid_provider", "signin_success_after_20s", "signin_never",
        )
        assertEquals(ids, FakeKernelScenario.entries.map { it.id })
        ids.forEach { assertEquals(it, FakeKernelScenario.fromId(it)!!.id) }
        assertNull(FakeKernelScenario.fromId(FakeKernelScenario.OFF))
        assertNull(FakeKernelScenario.fromId("nope"))
    }

    @Test
    fun `prerequisite scenarios land on their blocked states`() = runTest {
        val expected = mapOf(
            FakeKernelScenario.TermuxMissing to KernelState.TermuxMissing(disabled = false),
            FakeKernelScenario.TermuxDisabled to KernelState.TermuxMissing(disabled = true),
            FakeKernelScenario.TermuxOld to KernelState.TermuxTooOld("0.101.0"),
            FakeKernelScenario.PermissionMissing to KernelState.PermissionMissing,
            FakeKernelScenario.TermuxNotSetUp to KernelState.TermuxNotSetUp,
            FakeKernelScenario.PortMissing to KernelState.PortMissing,
            FakeKernelScenario.PortOld to KernelState.PortTooOld("3.0.68-termux.1"),
        )
        for ((scenario, state) in expected) {
            val rig = Rig(this, scenario)
            rig.coordinator.ensureConnected(startIfDown = true)
            advanceUntilIdle()
            assertEquals(scenario.id, state, rig.state)
            assertTrue(scenario.id, rig.gate is KernelGate.Blocked)
            assertFalse("no hub start for ${scenario.id}", rig.kernel.trace.contains(ClineCommands.Labels.HUB_START))
        }
        // KS A.7: a VERSION without `release=` is not judged at the probe; the start runs and the
        // daemon-log check finds the `entry.js` failure when the budget runs out.
        val portOld = Rig(this, FakeKernelScenario.HubStartPortOld)
        portOld.coordinator.ensureConnected(startIfDown = true)
        advanceUntilIdle()
        assertEquals(KernelState.PortTooOld(null), portOld.state)
        assertTrue(portOld.gate is KernelGate.Blocked)
        assertTrue("hub start before the daemon-log check", portOld.kernel.trace.contains(ClineCommands.Labels.HUB_START))
        val blocked = Rig(this, FakeKernelScenario.ExternalAppsBlocked)
        blocked.coordinator.ensureConnected(startIfDown = true)
        advanceUntilIdle()
        assertTrue(blocked.state is KernelState.ExternalAppsBlocked)
        assertTrue((blocked.state as KernelState.ExternalAppsBlocked).errmsg.contains("allow-external-apps"))
        finish()
    }

    @Test
    fun `the base kernel starts its Hub and connects`() = runTest {
        val rig = Rig(this, FakeKernelScenario.ProvidersAnthropic)
        rig.coordinator.ensureConnected(startIfDown = true)
        advanceUntilIdle()
        val ready = rig.state as KernelState.Ready
        assertEquals(ScriptedKernel.FIRST_PID, ready.info.pid)
        assertEquals("3.0.68-termux.2", ready.info.release)
        assertEquals(KernelGate.Ready, rig.gate)
        assertEquals(
            listOf(
                ClineCommands.Labels.PROBE_VERSION, ClineCommands.Labels.HUB_START,
                ClineCommands.Labels.WAKE_LOCK, ClineCommands.Labels.READ_HUB_DISCOVERY,
                ClineCommands.Labels.READ_HUB_CMDLINE,
            ),
            rig.kernel.trace,
        )
        finish()
    }

    @Test
    fun `hub_start_slow is still starting at 20 s and Ready once health answers at 25 s`() = runTest {
        val rig = Rig(this, FakeKernelScenario.HubStartSlow)
        launch { rig.coordinator.ensureConnected(startIfDown = true) }
        advanceTimeBy(21_000)
        runCurrent()
        assertEquals(StartStep.StartingHub, (rig.state as KernelState.Starting).step)
        assertTrue("slow after 20 s", (rig.gate as KernelGate.Busy).slow)
        advanceTimeBy(6_000)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue("got ${rig.state}", rig.state is KernelState.Ready)
        finish()
    }

    @Test
    fun `hub_start_fail ends StartFailed with the CLI text after the 60 s budget`() = runTest {
        val rig = Rig(this, FakeKernelScenario.HubStartFail)
        rig.coordinator.ensureConnected(startIfDown = true)
        advanceUntilIdle()
        val down = rig.state as KernelState.HubDown
        assertEquals(DownReason.StartFailed, down.reason)
        assertTrue(down.lastError!!.contains("waiting for detached hub startup"))
        assertTrue("budget used", currentTime >= 60_000)
        assertTrue(rig.kernel.trace.contains(ClineCommands.Labels.TAIL_DAEMON_LOG))
        finish()
    }

    @Test
    fun `hub_start_hang times out and reports doctor`() = runTest {
        val rig = Rig(this, FakeKernelScenario.HubStartHang)
        rig.coordinator.ensureConnected(startIfDown = true)
        advanceUntilIdle()
        val down = rig.state as KernelState.HubDown
        assertEquals(DownReason.StartTimedOut, down.reason)
        assertNotNull(down.doctor)
        assertFalse(down.doctor!!.hubHealthy)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.StartFailed, null), rig.gate)
        finish()
    }

    @Test
    fun `asleep_after_ready drops 10 s after Ready and classifies as Asleep`() = runTest {
        val rig = connected(FakeKernelScenario.AsleepAfterReady)
        assertTrue(rig.state is KernelState.Ready)
        assertFalse("the Hub was already running", rig.kernel.trace.contains(ClineCommands.Labels.HUB_START))
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(DownReason.Asleep, (rig.state as KernelState.HubDown).reason)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Asleep, null), rig.gate)
        finish()
    }

    @Test
    fun `stopped_in_termux removes production json and reads as StoppedInTermux`() = runTest {
        val rig = connected(FakeKernelScenario.StoppedInTermux)
        // The discovery check is a RUN_COMMAND, issued only while the app is in the foreground (KS §0.4).
        rig.coordinator.onForeground()
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(DownReason.StoppedInTermux, (rig.state as KernelState.HubDown).reason)
        assertFalse(rig.prefs.userStopped)
        finish()
    }

    @Test
    fun `hub_comes_back is picked up by the passive probe without a hub start`() = runTest {
        val rig = Rig(this, FakeKernelScenario.HubComesBack)
        rig.coordinator.onForeground()
        advanceTimeBy(5_000)
        runCurrent()
        val firstPid = (rig.state as KernelState.Ready).info.pid
        advanceTimeBy(12_000)
        runCurrent()
        assertEquals(DownReason.Asleep, (rig.state as KernelState.HubDown).reason)
        advanceTimeBy(45_000)
        runCurrent()
        val back = rig.state as KernelState.Ready
        assertNotEquals(firstPid, back.info.pid)
        assertFalse(rig.kernel.trace.contains(ClineCommands.Labels.HUB_START))
        finish()
    }

    @Test
    fun `hub_crash_loop suspends auto-start after the second early death`() = runTest {
        val rig = Rig(this, FakeKernelScenario.HubCrashLoop)
        rig.coordinator.onForeground()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("first auto-start: ${rig.state}", rig.state is KernelState.Ready)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(DownReason.Asleep, (rig.state as KernelState.HubDown).reason)
        rig.coordinator.onBackground()
        advanceTimeBy(6_000)
        rig.coordinator.onForeground()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue("second auto-start: ${rig.state}", rig.state is KernelState.Ready)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(DownReason.CrashLoop, (rig.state as KernelState.HubDown).reason)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.CrashLoop, null), rig.gate)
        val starts = rig.kernel.trace.count { it == ClineCommands.Labels.HUB_START }
        rig.coordinator.onBackground()
        advanceTimeBy(6_000)
        rig.coordinator.onForeground()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("no further auto-start", starts, rig.kernel.trace.count { it == ClineCommands.Labels.HUB_START })
        finish()
    }

    @Test
    fun `restart_busy_other_client sees the foreign session and the forced restart replaces the Hub`() = runTest {
        val rig = connected(FakeKernelScenario.RestartBusyOtherClient)
        val pid = (rig.state as KernelState.Ready).info.pid
        val precheck = rig.coordinator.beginRestart()
        assertEquals(1, precheck.others.activeSessionCount)
        assertEquals(1, precheck.others.participantClientCount)
        assertTrue(precheck.needsConfirm)

        val refused = async { rig.coordinator.restart(forced = false) }
        advanceUntilIdle()
        assertTrue("hub upgrade refuses while the TUI holds a session", refused.await() is KernelCoordinator.RestartOutcome.StillServing)
        assertEquals(pid, (rig.state as KernelState.Ready).info.pid)

        val forced = async { rig.coordinator.restart(forced = true) }
        advanceUntilIdle()
        assertEquals(KernelCoordinator.RestartOutcome.Restarted, forced.await())
        assertNotEquals(pid, (rig.state as KernelState.Ready).info.pid)
        assertEquals(0, rig.coordinator.beginRestart().others.activeSessionCount)
        rig.coordinator.cancelRestart()
        finish()
    }

    @Test
    fun `restart_slow times out at 75 s and the re-probe sees the new pid`() = runTest {
        val rig = connected(FakeKernelScenario.RestartSlow)
        val pid = (rig.state as KernelState.Ready).info.pid
        val started = currentTime
        val outcome = async { rig.coordinator.restart(forced = false) }
        advanceUntilIdle()
        assertEquals(KernelCoordinator.RestartOutcome.Restarted, outcome.await())
        assertTrue(currentTime - started >= 75_000)
        assertNotEquals(pid, (rig.state as KernelState.Ready).info.pid)
        finish()
    }

    @Test
    fun `protocol_unsupported is a Hub newer than the app and survives a restart`() = runTest {
        val rig = connected(FakeKernelScenario.ProtocolUnsupported)
        val state = rig.state as KernelState.ProtocolUnsupported
        assertEquals(HubMismatch.HubNewer, HubMismatch.of(state.info))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Protocol, null), rig.gate)
        val outcome = async { rig.coordinator.restart(forced = false) }
        advanceUntilIdle()
        val down = outcome.await() as KernelCoordinator.RestartOutcome.Down
        assertTrue(down.state is KernelState.ProtocolUnsupported)
        finish()
    }

    @Test
    fun `auth_failed and draining`() = runTest {
        val auth = connected(FakeKernelScenario.AuthFailed)
        assertTrue("got ${auth.state}", auth.state is KernelState.AuthFailed)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Auth, null), auth.gate)
        finish()

        val draining = connected(FakeKernelScenario.Draining)
        assertTrue("got ${draining.state}", draining.state is KernelState.Draining)
        assertEquals(KernelGate.BusyKind.Draining, (draining.gate as KernelGate.Busy).kind)
        finish()
    }

    @Test
    fun `stop kernel stops the scripted Hub`() = runTest {
        val rig = connected(FakeKernelScenario.ProvidersCline)
        assertEquals(KernelCoordinator.StopOutcome.Stopped, rig.coordinator.stopKernel())
        assertFalse(rig.kernel.hubRunning)
        assertEquals(DownReason.Stopped, (rig.state as KernelState.HubDown).reason)
        finish()
    }

    @Test
    fun `providers scenarios project like the real file`() = runTest {
        suspend fun presence(scenario: FakeKernelScenario): ProviderPresence =
            ScriptedKernel(scenario, backgroundScope, random = Random(1)).executor.readProviderPresence()
        assertEquals(ProviderPresence.Missing, presence(FakeKernelScenario.ProvidersEmpty))
        val cline = presence(FakeKernelScenario.ProvidersCline).providersOrEmpty
        assertTrue(cline.isConnected("cline"))
        assertTrue(cline.providers.getValue("cline").hasOAuth)
        val anthropic = presence(FakeKernelScenario.ProvidersAnthropic).providersOrEmpty
        assertTrue(anthropic.isConnected("anthropic"))
        assertEquals("anthropic", anthropic.defaultProviderId)
        val ollama = presence(FakeKernelScenario.ProvidersOllama).providersOrEmpty
        assertFalse(ollama.providers.getValue("ollama").configured)
        assertTrue("keyless provider with a model counts as connected", ollama.isConnected("ollama"))
    }

    @Test
    fun `auth_ok writes the provider, auth_fail_invalid_provider prints the CLI error`() = runTest {
        val ok = ScriptedKernel(FakeKernelScenario.AuthOk, backgroundScope, random = Random(1))
        assertEquals(ProviderPresence.Missing, ok.executor.readProviderPresence())
        val result = ok.executor.run(ClineCommands.authProvider("openrouter", "sk-secret-value", "openai/gpt-5"), 30_000)
        assertTrue(result.ok)
        assertTrue(ok.executor.readProviderPresence().providersOrEmpty.isConnected("openrouter"))
        assertTrue(ok.executor.run(ClineCommands.statProviders(), 10_000).ok)
        assertTrue("labels only", ok.trace.none { it.contains("sk-secret-value") })
        assertTrue(ok.trace.contains("cline auth openrouter"))

        val bad = ScriptedKernel(FakeKernelScenario.AuthFailInvalidProvider, backgroundScope, random = Random(1))
        val failed = bad.executor.run(ClineCommands.authProvider("nope", "k", "m"), 30_000)
        assertEquals(1, failed.exitCode)
        assertEquals("invalid provider \"nope\"", failed.lastStderrLine)
    }

    @Test
    fun `sign-in completes after 20 s, or never until cancelled`() = runTest {
        val success = ScriptedKernel(FakeKernelScenario.SignInSuccessAfter20s, backgroundScope, random = Random(1))
        val handle: DetachedHandle = success.executor.runDetached(ClineCommands.authInteractive())
        advanceTimeBy(19_000)
        assertFalse(handle.earlyExit.isCompleted)
        advanceTimeBy(2_000)
        assertTrue(handle.earlyExit.isCompleted)
        assertEquals(0, handle.earlyExit.await().exitCode)
        assertTrue(success.executor.readProviderPresence().providersOrEmpty.isConnected("cline"))

        val never = ScriptedKernel(FakeKernelScenario.SignInNever, backgroundScope, random = Random(1))
        val pending = never.executor.runDetached(ClineCommands.authInteractive())
        advanceTimeBy(15 * 60_000L)
        assertFalse(pending.earlyExit.isCompleted)
        assertTrue(never.executor.run(ClineCommands.cancelAuth(), 10_000).ok)
        assertEquals(ScriptedKernel.SIGTERM_EXIT, pending.earlyExit.await().exitCode)
        assertEquals(ProviderPresence.Missing, never.executor.readProviderPresence())
    }

    @Test
    fun `unscripted commands fail clearly instead of reaching the real Termux`() = runTest {
        val kernel = ScriptedKernel(FakeKernelScenario.ProvidersAnthropic, backgroundScope, random = Random(1))
        val result = kernel.executor.run(ClineCommands.gitBranch("/data/data/com.termux/files/home/app"), 10_000)
        assertEquals(ScriptedKernel.UNSCRIPTED_EXIT, result.exitCode)
        assertTrue(result.stderr.contains("providers_anthropic"))
        val detached = kernel.executor.runDetached(ClineCommands.setupStorage())
        assertEquals(ScriptedKernel.UNSCRIPTED_EXIT, detached.earlyExit.await().exitCode)
    }

    @Test
    fun `the switch delegates to the real seams when off and restarts timelines on change`() = runTest {
        val real = object : TermuxExecutor {
            override fun isTermuxInstalled(): TermuxPresence = TermuxPresence.Installed(118, "real")
            override fun hasPermission(): Boolean = true
            override suspend fun run(command: TermuxCommand, timeoutMs: Long): TermuxResult = TermuxResult.success("real")
            override fun runDetached(command: TermuxCommand): DetachedHandle = error("unused")
        }
        val switch = FakeKernelSwitch(backgroundScope)
        val executor = switch.executor(real)
        assertEquals("real", (executor.isTermuxInstalled() as TermuxPresence.Installed).versionName)
        switch.select(FakeKernelScenario.TermuxMissing)
        assertEquals(TermuxPresence.Missing, executor.isTermuxInstalled())
        val first = switch.current
        switch.select(FakeKernelScenario.TermuxMissing)
        assertTrue("same scenario keeps its timeline", first === switch.current)
        switch.select(FakeKernelScenario.PermissionMissing)
        assertFalse(executor.hasPermission())
        switch.select(null)
        assertEquals("real", executor.run(ClineCommands.cliVersion(), 1_000).stdout)
        assertNull(switch.scenario)
    }
}
