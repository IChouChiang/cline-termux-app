package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubClientIdentity
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubUpgradeFailed
import dev.cline.termux.core.kernel.hub.StoredHubToken
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.fakes.FAKE_HUB_TOKEN
import dev.cline.termux.fakes.FakeHubHost
import dev.cline.termux.fakes.FakeHubProber
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeKernelCommands
import dev.cline.termux.fakes.FakeTokenCache
import dev.cline.termux.fakes.InMemoryCursorStore
import dev.cline.termux.fakes.TestKernelClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

private class RecordingReattach(var open: Set<String> = emptySet()) : SessionReattachHook {
    val attached = mutableListOf<String>()
    val gone = mutableListOf<String>()
    override suspend fun openSessions(): Set<String> = open
    override suspend fun onAttached(sessionId: String) { attached += sessionId }
    override suspend fun onSessionGone(sessionId: String) { gone += sessionId }
}

/** The coordinator with every seam faked (docs/07 §10.1); the Hub socket is the scripted transport. */
@OptIn(ExperimentalCoroutinesApi::class)
private class Harness(scope: TestScope, configure: Harness.() -> Unit = {}) {
    val host = FakeHubHost()
    val commands = FakeKernelCommands(host)
    val prober = FakeHubProber(host)
    val tokens = FakeTokenCache()
    val prefs = InMemoryKernelPreferences()
    val hub = FakeHubTransport()
    val reattach = RecordingReattach()
    val clock = TestKernelClock(scope.testScheduler)
    var override: String? = null

    /** `KeepAlivePolicy.status == Running`: this app's foreground service is up. */
    var serviceHeld = false
    val client: HubClient
    val coordinator: KernelCoordinator

    init {
        hub.openGate = { if (!host.running) HubUpgradeFailed(null, "websocket upgrade failed: connection refused") else null }
        hub.acceptToken = { it != null && (it == host.token || it == override) }
        configure()
        client = HubClient(
            transport = hub,
            identity = HubClientIdentity("android-t", appVersion = "0.1"),
            scope = scope,
            cursors = InMemoryCursorStore(),
            credentials = { coordinator.currentCredentials() },
            random = Random(7),
        )
        coordinator = KernelCoordinator(
            commands = commands, prober = prober, tokens = tokens, prefs = prefs, hubClient = client,
            scope = scope, clock = clock, tokenOverride = { override }, reattach = reattach,
            serviceHeld = { serviceHeld },
        )
    }

    val state: KernelState get() = coordinator.state.value
    val runCommands: List<String> get() = commands.calls.filter { it != "termuxPresence" && it != "hasRunCommandPermission" }
    fun assertReady(): KernelState.Ready {
        val s = state
        assertTrue("expected Ready, got $s (client=${client.state.value}, calls=${commands.calls}, tokens=${hub.openedTokens})", s is KernelState.Ready)
        return s as KernelState.Ready
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KernelCoordinatorTest {
    private fun TestScope.finish() = coroutineContext.job.cancelChildren()

    // ---- P1–P3 -----------------------------------------------------------------------------

    @Test
    fun `missing, disabled or old Termux and a missing permission are reported before any RUN_COMMAND`() = runTest {
        val h = Harness(this) { commands.presence = TermuxPresence.Missing }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.TermuxMissing(disabled = false), h.state)
        assertEquals(KernelGate.Blocked(CheckId.Termux), h.coordinator.gate.value)

        h.commands.presence = TermuxPresence.Disabled
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.TermuxMissing(disabled = true), h.state)

        h.commands.presence = TermuxPresence.Installed(101, "0.101.0")
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.TermuxTooOld("0.101.0"), h.state)

        h.commands.presence = TermuxPresence.Installed(118, "0.118.3")
        h.commands.permitted = false
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.PermissionMissing, h.state)
        assertEquals(KernelGate.Blocked(CheckId.Permission), h.coordinator.gate.value)
        assertTrue(h.runCommands.isEmpty())
        finish()
    }

    @Test
    fun `the VERSION probe classifies the port and Termux-internal errors`() = runTest {
        val h = Harness(this)
        h.commands.portVersionResult = FakeKernelCommands.fail(1, "cat: VERSION: No such file or directory")
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.PortMissing, h.state)
        assertEquals(KernelGate.Blocked(CheckId.Kernel), h.coordinator.gate.value)

        h.commands.portVersionResult = FakeKernelCommands.ok("release=3.0.67-termux.3\ntag=v3.0.67-termux.3\ncline=3.0.67\n")
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.PortTooOld("3.0.67-termux.3"), h.state)
        assertEquals("3.0.67-termux.3", h.coordinator.portVersion.value!!.release)

        h.commands.portVersionResult = FakeKernelCommands.internal("RUN_COMMAND Intent not allowed: allow-external-apps is false in ~/.termux/termux.properties")
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertTrue(h.state is KernelState.ExternalAppsBlocked)
        assertEquals(KernelGate.Blocked(CheckId.ExternalApps), h.coordinator.gate.value)

        h.commands.portVersionResult = FakeKernelCommands.internal("Failed to execute: /data/data/com.termux/files/usr/bin/cat: not found")
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.TermuxNotSetUp, h.state)
        assertTrue("no hub start before the kernel check passes", h.commands.calls.none { it == "hubStart" })
        finish()
    }

    // ---- H1–H5 -----------------------------------------------------------------------------

    @Test
    fun `cold start - starts the daemon, takes the wake lock, reads the token and connects`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()

        val ready = h.assertReady()
        assertEquals(listOf("readPortVersion", "hubStart", "wakeLock", "readDiscovery", "readHubCmdline"), h.runCommands)
        assertEquals(listOf(FAKE_HUB_TOKEN), h.hub.openedTokens)
        assertEquals(1, h.tokens.writes)
        assertEquals(4242, ready.info.pid)
        assertEquals("hub_1", ready.info.hubId)
        assertEquals("3.0.68-termux.2", ready.info.release)
        assertEquals("3.0.68", ready.info.cliVersion)
        assertEquals("ws://127.0.0.1:25463/hub", ready.info.url)
        assertNotNull(ready.hub)
        assertTrue(h.prefs.wakeLockRequested)
        assertEquals("3.0.68-termux.2", h.prefs.hubLifetimeRelease)
        assertEquals(KernelGate.Ready, h.coordinator.gate.value)
        // StateFlow conflates, so the step order is read from the transition log (every emit is recorded).
        val visited = h.coordinator.transitions.snapshot().map { it.to }
        assertEquals(
            listOf("Starting(CheckingTermux)", "Starting(CheckingKernel)", "Starting(StartingHub)", "Starting(KeepingAwake)", "Starting(ReadingCredentials)", "Starting(Connecting)", "Connecting", "Ready"),
            visited,
        )
        finish()
    }

    @Test
    fun `warm start reuses the cached token for the same daemon lifetime and skips the start`() = runTest {
        val h = Harness(this) {
            host.running = true
            tokens.stored = StoredHubToken(FAKE_HUB_TOKEN, "hub_1:${host.pid}:${host.startedAt}", 25463)
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        val ready = h.assertReady()
        assertEquals(0, h.tokens.writes)
        assertEquals(listOf("readPortVersion", "wakeLock", "readHubCmdline"), h.runCommands)
        assertEquals("hub_1", ready.info.hubId)
        finish()
    }

    @Test
    fun `the lifetime release survives a relaunch after a port upgrade and follows a new daemon`() = runTest {
        // The Hub was started from .2; the port was then upgraded to .3 and the app relaunched.
        val h = Harness(this) {
            host.running = true
            tokens.stored = StoredHubToken(FAKE_HUB_TOKEN, "hub_1:${host.pid}:${host.startedAt}", 25463)
            prefs.hubLifetimeRelease = "3.0.68-termux.2"
            commands.portVersionResult = FakeKernelCommands.ok("release=3.0.68-termux.3\ncline=3.0.68\n")
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals("same daemon lifetime: E.5 can offer the restart", "3.0.68-termux.2", h.prefs.hubLifetimeRelease)

        // A new daemon lifetime started outside the app after the upgrade runs the installed tree.
        h.coordinator.disconnect(); advanceUntilIdle()
        h.host.restart()
        h.commands.daemonRelease = "3.0.68-termux.3"
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals("3.0.68-termux.3", h.prefs.hubLifetimeRelease)

        // Stopping ends the lifetime; nothing stale is kept for the next one.
        h.coordinator.stopKernel(); advanceUntilIdle()
        assertNull(h.prefs.hubLifetimeRelease)
        finish()
    }

    @Test
    fun `a daemon started outside the app before a port upgrade is recorded with the tree it runs`() = runTest {
        // Trial row 24 (2026-10-09): the Hub ran 3.0.69-termux.1 while VERSION said 3.0.70-termux.1, and the app
        // had no token for that lifetime, so the first-seen rule took the installed release and hid the restart.
        val h = Harness(this) {
            host.running = true
            commands.daemonRelease = "3.0.69-termux.1"
            commands.portVersionResult = FakeKernelCommands.ok("release=3.0.70-termux.1\ncline=3.0.70\n")
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals("3.0.69-termux.1", h.prefs.hubLifetimeRelease)
        finish()
    }

    @Test
    fun `an argv that names no release keeps the first-seen rule`() = runTest {
        val h = Harness(this) {
            host.running = true
            commands.daemonRelease = null
            commands.portVersionResult = FakeKernelCommands.ok("release=3.0.70-termux.1\ncline=3.0.70\n")
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals("`current/entry.js`: unknown", "3.0.70-termux.1", h.prefs.hubLifetimeRelease)

        h.coordinator.disconnect(); advanceUntilIdle()
        h.host.restart()
        h.commands.portVersionResult = FakeKernelCommands.ok("release=3.0.70-termux.2\ncline=3.0.70\n")
        h.commands.hubCmdlineResult = FakeKernelCommands.fail(1, "tr: /proc/4243/cmdline: No such file or directory")
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals("an unreadable argv", "3.0.70-termux.2", h.prefs.hubLifetimeRelease)
        finish()
    }

    @Test
    fun `refreshPortVersion re-reads VERSION while connected, without a reconnect`() = runTest {
        // Trial row 24: the fast path of a re-check never reads VERSION, so an upgrade made while connected went unseen.
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        val sockets = h.hub.openedTokens.size
        h.commands.portVersionResult = FakeKernelCommands.ok("release=3.0.68-termux.3\ncline=3.0.68\n")
        h.coordinator.refreshPortVersion()
        assertEquals("3.0.68-termux.3", h.coordinator.portVersion.value!!.release)
        assertEquals("the daemon still runs the old tree", "3.0.68-termux.2", h.prefs.hubLifetimeRelease)
        assertEquals(sockets, h.hub.openedTokens.size)
        h.assertReady()

        h.commands.portVersionResult = FakeKernelCommands.fail(1, "cat: VERSION: No such file or directory")
        h.coordinator.refreshPortVersion()
        assertEquals("a failed read keeps the last one", "3.0.68-termux.3", h.coordinator.portVersion.value!!.release)
        finish()
    }

    @Test
    fun `a restarted daemon invalidates the cached token`() = runTest {
        val h = Harness(this) {
            host.running = true
            tokens.stored = StoredHubToken("stale", "hub_0:1:2026-01-01T00:00:00Z", 25463)
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals(listOf(FAKE_HUB_TOKEN), h.hub.openedTokens)
        assertEquals(1, h.tokens.writes)
        finish()
    }

    @Test
    fun `a 401 re-reads the token once, a second 401 is AuthFailed`() = runTest {
        val h = Harness(this) {
            host.running = true
            tokens.stored = StoredHubToken("stale-but-same-lifetime", "hub_1:${host.pid}:${host.startedAt}", 25463)
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals(listOf("stale-but-same-lifetime", FAKE_HUB_TOKEN), h.hub.attemptedTokens)
        assertEquals(listOf(FAKE_HUB_TOKEN), h.hub.openedTokens)
        assertEquals(1, h.tokens.writes)
        h.coordinator.disconnect(); advanceUntilIdle()

        val wrong = h.host.discoveryJson().replace(h.host.token, "not-the-token")
        h.commands.discoveryResult = FakeKernelCommands.ok(wrong)
        h.tokens.stored = null
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertTrue("${h.state}", h.state is KernelState.AuthFailed)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Auth, null), h.coordinator.gate.value)
        finish()
    }

    @Test
    fun `an old port is diagnosed from hub start output or from the daemon log after the budget`() = runTest {
        val h = Harness(this) { commands.hubStartResult = FakeKernelCommands.fail(1, "error: Module not found \"/opt/cline-termux/current/entry.js\"") }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.PortTooOld("3.0.68-termux.2"), h.state)

        val quiet = Harness(this) {
            commands.hubStartResult = FakeKernelCommands.fail(1, "hub start failed")
            commands.daemonLog = "error: Module not found \"/data/data/com.termux/files/usr/opt/cline-termux/current/entry.js\"\n"
        }
        val before = quiet.clock.elapsedMs()
        quiet.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.PortTooOld("3.0.68-termux.2"), quiet.state)
        assertTrue("polled /health until the budget ran out", quiet.clock.elapsedMs() - before >= KernelCommands.HUB_START_BUDGET_MS)
        assertTrue(quiet.commands.calls.contains("tailDaemonLog"))
        finish()
    }

    @Test
    fun `a VERSION without release is judged by the daemon log, not blocked at the probe`() = runTest {
        val h = Harness(this) {
            commands.portVersionResult = FakeKernelCommands.ok("cline=3.0.68\n")
            commands.hubStartResult = FakeKernelCommands.fail(1, "Timed out after 15000ms waiting for detached hub startup.")
            commands.daemonLog = "error: Module not found \"/data/data/com.termux/files/usr/opt/cline-termux/3.0.68-termux.1/entry.js\"\n"
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertTrue("the start is attempted", h.commands.calls.contains("hubStart"))
        assertEquals(KernelState.PortTooOld(null), h.state)
        finish()
    }

    @Test
    fun `a start that never answers is StartFailed with the stderr tail, a silent intent is StartTimedOut with doctor`() = runTest {
        val h = Harness(this) { commands.hubStartResult = FakeKernelCommands.fail(1, "error: listen EADDRINUSE 127.0.0.1:25463") }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.HubDown(DownReason.StartFailed, "error: listen EADDRINUSE 127.0.0.1:25463", null), h.state)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.StartFailed, "error: listen EADDRINUSE 127.0.0.1:25463"), h.coordinator.gate.value)

        val silent = Harness(this) { commands.hubStartResult = KernelCommandResult.TimedOut(60_000) }
        silent.coordinator.ensureConnected(); advanceUntilIdle()
        val down = silent.state as KernelState.HubDown
        assertEquals(DownReason.StartTimedOut, down.reason)
        assertNull(down.lastError)
        assertEquals(4242, down.doctor!!.hubPid)
        finish()
    }

    @Test
    fun `the CLI giving up at 15 s is not a failure while the daemon still boots`() = runTest {
        val h = Harness(this) { commands.hubStartDetached = FakeKernelCommands.fail(1, "Timed out after 15000ms waiting for the hub to become healthy") }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertTrue(h.commands.calls.none { it == "tailDaemonLog" || it == "doctorJson" })
        finish()
    }

    @Test
    fun `an incompatible protocol never opens the socket`() = runTest {
        val h = Harness(this) { host.running = true; host.minClientProtocolVersion = "v2"; host.maxClientProtocolVersion = "v3" }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertTrue(h.state is KernelState.ProtocolUnsupported)
        assertTrue(h.hub.sockets.isEmpty())
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Protocol, null), h.coordinator.gate.value)
        finish()
    }

    @Test
    fun `a draining Hub is Draining until it un-drains`() = runTest {
        val h = Harness(this) { host.running = true; host.draining = true; hub.draining = true }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertTrue("${h.state}", h.state is KernelState.Draining)
        assertEquals(KernelGate.Busy(KernelGate.BusyKind.Draining, null, slow = false), h.coordinator.gate.value)
        h.hub.current.event(HubEvents.HUB_DRAIN_CHANGED, sequence = 1, payload = buildJsonObject { put("draining", false) })
        advanceUntilIdle()
        h.assertReady()
        h.hub.current.event(HubEvents.HUB_DRAIN_CHANGED, sequence = 2, payload = buildJsonObject { put("draining", true) })
        advanceUntilIdle()
        assertTrue(h.state is KernelState.Draining)
        finish()
    }

    // ---- G5: Draining follows the current connection -------------------------------------------

    @Test
    fun `a drained Hub replaced behind a dropped socket is Ready when the new connection's status says so`() = runTest {
        val h = Harness(this) { host.running = true }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        h.hub.current.event(HubEvents.HUB_DRAIN_CHANGED, sequence = 1, payload = buildJsonObject { put("draining", true) })
        advanceUntilIdle()
        assertTrue("${h.state}", h.state is KernelState.Draining)
        // "retired by newer install": the socket drops and the Hub that answers next is not
        // draining (the shared durable log keeps the old Hub's sequences).
        h.hub.draining = false
        h.hub.hubId = "hub_new"
        h.hub.lastSequence = 1
        h.hub.current.drop(); advanceUntilIdle()
        h.assertReady()
        assertFalse(h.client.draining.value)
        assertEquals(2, h.hub.sockets.size)
        finish()
    }

    @Test
    fun `a new Hub that is still draining is Draining after the reconnect`() = runTest {
        val h = Harness(this) { host.running = true }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        h.hub.draining = true
        h.hub.current.drop(); advanceUntilIdle()
        assertTrue("${h.state}", h.state is KernelState.Draining)
        assertTrue(h.client.draining.value)
        finish()
    }

    @Test
    fun `a drain lifted while a sequence runs is picked up when the sequence ends`() = runTest {
        val h = Harness(this) { host.running = true; host.draining = true; hub.draining = true }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertTrue("${h.state}", h.state is KernelState.Draining)
        h.prober.healthLatencyMs = 1_000
        launch { h.coordinator.recheck() } // the fast path's /health holds the sequence open
        runCurrent()
        h.host.draining = false
        h.hub.current.event(HubEvents.HUB_DRAIN_CHANGED, sequence = 1, payload = buildJsonObject { put("draining", false) })
        runCurrent()
        assertTrue("the sequence owns the state while it runs, got ${h.state}", h.state is KernelState.Draining)
        advanceUntilIdle()
        h.assertReady()
        finish()
    }

    @Test
    fun `a drain lifted while the restart confirm is open is picked up on Cancel`() = runTest {
        val h = Harness(this) { host.running = true; host.draining = true; hub.draining = true }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertTrue("${h.state}", h.state is KernelState.Draining)
        h.coordinator.beginRestart()
        h.hub.current.event(HubEvents.HUB_DRAIN_CHANGED, sequence = 1, payload = buildJsonObject { put("draining", false) })
        advanceUntilIdle()
        assertTrue("${h.state}", h.state is KernelState.Restarting)
        h.coordinator.cancelRestart(); advanceUntilIdle()
        h.assertReady()
        finish()
    }

    @Test
    fun `a Draining read from the health of the Hub being replaced gives way to the connection's status`() = runTest {
        // The old Hub still answers /health "draining" while the socket reaches its successor.
        val h = Harness(this) { host.running = true; host.draining = true; hub.draining = false }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals(listOf("Draining", "Ready"), h.coordinator.transitions.snapshot().map { it.to }.takeLast(2))
        finish()
    }

    @Test
    fun `open sessions are re-attached after a connect and gone ones reported`() = runTest {
        val h = Harness(this) { host.running = true; reattach.open = setOf("s1", "s2") }
        h.hub.onCommand = { env ->
            if (env.command == HubCommands.SESSION_ATTACH && env.sessionId == "s2") FakeHubTransport.errorReply(env, "session_not_found", "gone")
            else h.hub.defaultReply(env)
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals(listOf("s1"), h.reattach.attached)
        assertEquals(listOf("s2"), h.reattach.gone)
        finish()
    }

    // ---- degraded mode and the developer override -------------------------------------------

    @Test
    fun `a revoked permission with a live Hub and a valid token is degraded mode, not a block`() = runTest {
        val h = Harness(this) {
            host.running = true
            commands.permitted = false
            tokens.stored = StoredHubToken(FAKE_HUB_TOKEN, "hub_1:${host.pid}:${host.startedAt}", 25463)
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertFalse(h.coordinator.commandsAvailable.value)
        assertEquals(KernelGate.DegradedNoCommands, h.coordinator.gate.value)
        assertTrue("no RUN_COMMAND in degraded mode", h.runCommands.isEmpty())
        finish()
    }

    @Test
    fun `recheck on the same Hub lifetime lifts degraded mode once the permission is re-granted`() = runTest {
        val h = Harness(this) {
            host.running = true
            commands.permitted = false
            tokens.stored = StoredHubToken(FAKE_HUB_TOKEN, "hub_1:${host.pid}:${host.startedAt}", 25463)
        }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelGate.DegradedNoCommands, h.coordinator.gate.value)
        val lifetime = h.coordinator.state.value.hubInfo?.lifetimePair

        h.commands.permitted = true
        h.coordinator.recheck(); advanceUntilIdle()
        h.assertReady()
        assertEquals("same daemon lifetime, no reconnect", lifetime, h.coordinator.state.value.hubInfo?.lifetimePair)
        assertTrue(h.coordinator.commandsAvailable.value)
        assertEquals(KernelGate.Ready, h.coordinator.gate.value)
        finish()
    }

    @Test
    fun `the developer override token replaces the discovery read and needs no Termux`() = runTest {
        val h = Harness(this) { host.running = true; commands.presence = TermuxPresence.Missing; override = "override-token" }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals(listOf("override-token"), h.hub.openedTokens)
        assertTrue(h.runCommands.isEmpty())
        assertEquals(KernelGate.DegradedNoCommands, h.coordinator.gate.value)
        assertEquals(0, h.tokens.writes)
        finish()
    }

    // ---- stop / disconnect / auto-start policy ------------------------------------------------

    @Test
    fun `stopKernel stops the daemon, releases the lock, forgets the token and blocks auto-start`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()

        assertEquals(KernelCoordinator.StopOutcome.Stopped, h.coordinator.stopKernel())
        advanceUntilIdle()
        assertEquals(KernelState.HubDown(DownReason.Stopped, null, null), h.state)
        assertTrue(h.client.state.value is HubConnectionState.Disconnected)
        assertNull(h.tokens.stored)
        assertTrue(h.prefs.userStopped)
        assertFalse(h.prefs.wakeLockRequested)
        assertTrue(h.commands.calls.indexOf("hubStop") < h.commands.calls.indexOf("wakeUnlock"))
        assertEquals(KernelGate.Down(KernelGate.DownVariant.Stopped, null), h.coordinator.gate.value)

        val startsBefore = h.commands.calls.count { it == "hubStart" }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.HubDown(DownReason.Stopped, null, null), h.state)
        assertEquals("no auto-start after a user stop", startsBefore, h.commands.calls.count { it == "hubStart" })

        h.coordinator.ensureConnected(userInitiated = true); advanceUntilIdle()
        h.assertReady()
        assertFalse(h.prefs.userStopped)
        assertEquals(startsBefore + 1, h.commands.calls.count { it == "hubStart" })
        finish()
    }

    @Test
    fun `stopKernel reports a daemon that would not stop`() = runTest {
        val h = Harness(this) { commands.hubStopResult = FakeKernelCommands.ok("""{"stopped":false}""") }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        val outcome = h.coordinator.stopKernel(); advanceUntilIdle()
        assertTrue(outcome is KernelCoordinator.StopOutcome.NotStopped)
        assertEquals(KernelState.HubDown(DownReason.Disconnected, null, null), h.state)
        finish()
    }

    @Test
    fun `disconnect keeps the daemon running and resume does not reconnect on its own`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.coordinator.disconnect(); advanceUntilIdle()
        assertEquals(KernelState.HubDown(DownReason.Disconnected, null, null), h.state)
        assertTrue(h.host.running)
        assertTrue(h.commands.calls.none { it == "hubStop" })
        assertTrue(h.commands.calls.contains("wakeUnlock"))
        assertTrue(h.client.state.value is HubConnectionState.Disconnected)

        h.coordinator.onBackground()
        advanceTimeBy(10_000)
        h.coordinator.onForeground(); runCurrent()
        assertEquals(KernelState.HubDown(DownReason.Disconnected, null, null), h.state)
        h.coordinator.ensureConnected(userInitiated = true); advanceUntilIdle()
        h.assertReady()
        finish()
    }

    @Test
    fun `resume re-checks only after more than five seconds away and the fast path issues no RUN_COMMAND`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        val commandsAfterStart = h.commands.calls.size
        val probesAfterStart = h.prober.healthProbes

        h.coordinator.onBackground()
        advanceTimeBy(2_000)
        h.coordinator.onForeground(); advanceUntilIdle()
        assertEquals(probesAfterStart, h.prober.healthProbes)

        h.coordinator.onBackground()
        advanceTimeBy(6_000)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        assertTrue(h.prober.healthProbes > probesAfterStart)
        assertEquals("same daemon lifetime: no RUN_COMMAND, no new socket", commandsAfterStart, h.commands.calls.size)
        assertEquals(1, h.hub.sockets.size)
        finish()
    }

    // ---- outages --------------------------------------------------------------------------

    @Test
    fun `a dropped socket with a live daemon is Reconnecting then Ready again`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        val states = mutableListOf<KernelState>()
        launch { h.coordinator.state.collect { states += it } }
        h.hub.current.drop(); advanceUntilIdle()
        h.assertReady()
        assertTrue(states.any { it is KernelState.Reconnecting && it.attempt == 1 })
        assertEquals(2, h.hub.sockets.size)
        finish()
    }

    @Test
    fun `a daemon that died is StoppedInTermux without its discovery record and Asleep with it`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.host.running = false
        // In the foreground the passive probe keeps HubDown busy, so time is advanced, never "until idle".
        h.hub.current.drop(); advanceTimeBy(5_000); runCurrent()
        assertEquals(KernelState.HubDown(DownReason.StoppedInTermux, null, null), h.state)
        assertEquals(KernelGate.Down(KernelGate.DownVariant.StoppedInTermux, null), h.coordinator.gate.value)
        h.coordinator.onBackground() // stops its passive probe before the second harness runs until idle

        val asleep = Harness(this)
        asleep.coordinator.onForeground(); advanceUntilIdle()
        asleep.assertReady()
        asleep.host.running = false
        asleep.host.discoveryPresent = true
        asleep.hub.current.drop(); advanceTimeBy(5_000); runCurrent()
        assertEquals(KernelState.HubDown(DownReason.Asleep, null, null), asleep.state)
        finish()
    }

    @Test
    fun `a stopped Hub reads as HubDown within 5 s even when every reconnect handshake hangs`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.host.running = false
        // Each reconnect handshake waits out the 10 s open deadline (a half-dead loopback or adb
        // reverse), and the discovery RUN_COMMAND takes as long as it does on the S25U.
        h.hub.openGate = { null }
        h.hub.hangOpen = true
        h.commands.discoveryLatencyMs = 1_500
        val before = h.runCommands.size
        h.hub.current.drop(); advanceTimeBy(2_000); runCurrent()
        assertEquals(KernelState.HubDown(DownReason.StoppedInTermux, null, null), h.state)
        assertEquals("the reconnect loop is stopped", HubConnectionState.Disconnected, h.client.state.value)
        assertEquals("one discovery check per drop", listOf("readDiscovery"), h.runCommands.drop(before))
        h.coordinator.onBackground()
        finish()
    }

    @Test
    fun `a stopping Hub that still answers health for a moment after the close is HubDown soon after it goes silent`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.hub.openGate = { null }
        h.hub.hangOpen = true
        h.commands.discoveryLatencyMs = 1_500
        h.hub.current.drop(); advanceTimeBy(600); runCurrent()
        assertTrue("still answering /health: Reconnecting, got ${h.state}", h.state is KernelState.Reconnecting)
        h.host.running = false // the listener closes after the sockets
        advanceTimeBy(2_500); runCurrent()
        assertEquals(KernelState.HubDown(DownReason.StoppedInTermux, null, null), h.state)
        h.coordinator.onBackground()
        finish()
    }

    @Test
    fun `a Hub that answers again during the drop check stays on the reconnect path`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        val states = mutableListOf<KernelState>()
        launch { h.coordinator.state.collect { states += it } }
        h.host.running = false
        h.commands.discoveryLatencyMs = 1_500
        h.hub.current.drop(); advanceTimeBy(200); runCurrent()
        assertTrue("the discovery check is in flight", h.runCommands.contains("readDiscovery"))
        h.host.running = true // a quick restart: the next reconnect attempt is answered
        advanceTimeBy(3_000); runCurrent()
        h.assertReady()
        assertTrue(states.none { it is KernelState.HubDown })
        h.coordinator.onBackground()
        finish()
    }

    @Test
    fun `a foreground drop with a live daemon runs no discovery check`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        val before = h.runCommands.size
        h.hub.current.drop(); advanceTimeBy(3_000); runCurrent()
        h.assertReady()
        assertEquals(before, h.runCommands.size)
        h.coordinator.onBackground()
        finish()
    }

    @Test
    fun `only cat exiting 1 with No such file is StoppedInTermux - a Termux internal error or another exit is Asleep`() = runTest {
        val cases = listOf(
            // RUN_COMMAND internal error (err != -1) whose text happens to mention a missing file.
            FakeKernelCommands.internal("Failed to execute \"/bin/cat\": No such file or directory", err = 2),
            // The plugin refused external apps mid-run.
            FakeKernelCommands.internal("RUN_COMMAND Intent not allowed: allow-external-apps is not set", err = 2),
            // Delivered, but not the "absent" answer.
            FakeKernelCommands.fail(2, "cat: production.json: Permission denied"),
            FakeKernelCommands.fail(1, "cat: production.json: Input/output error"),
        )
        for (result in cases) {
            val h = Harness(this)
            h.coordinator.onForeground(); advanceUntilIdle()
            h.assertReady()
            h.host.running = false
            h.commands.discoveryResult = result
            h.hub.current.drop(); advanceTimeBy(5_000); runCurrent()
            assertEquals("$result", KernelState.HubDown(DownReason.Asleep, null, null), h.state)
            assertTrue(h.runCommands.contains("readDiscovery"))
            h.coordinator.onBackground()
            advanceUntilIdle()
        }
        finish()
    }

    @Test
    fun `a drop while backgrounded issues no RUN_COMMAND and is classified on the next foreground`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.coordinator.onBackground(); runCurrent()
        val before = h.runCommands.size
        h.host.running = false
        h.hub.current.drop(); advanceUntilIdle()
        assertEquals("no discovery check from the background", KernelState.HubDown(DownReason.Asleep, null, null), h.state)
        assertEquals("no KernelCommands call while backgrounded", before, h.runCommands.size)

        // Back within the resume threshold: no auto-start, but the drop is classified now.
        h.coordinator.onForeground(); advanceTimeBy(5_000); runCurrent()
        assertEquals(KernelState.HubDown(DownReason.StoppedInTermux, null, null), h.state)
        assertEquals("P3, then the discovery check; no hub start", listOf("readPortVersion", "readDiscovery"), h.runCommands.drop(before))
        finish()
    }

    @Test
    fun `a Hub that answers after a background drop is reconnected with a re-read token on the next foreground`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.coordinator.onBackground(); runCurrent()
        val before = h.runCommands.size
        // The daemon restarted under us: the old token is refused on every reconnect.
        h.host.restart()
        h.hub.current.drop(); advanceUntilIdle()
        // Only a token re-read can reconnect, and none may run now: Down, so nothing holds the
        // foreground service on a socket that cannot come back (CR §12.4).
        assertEquals(KernelState.HubDown(DownReason.Asleep, null, null), h.state)
        assertTrue(h.coordinator.gate.value is KernelGate.Down)
        assertEquals("no token re-read while backgrounded", before, h.runCommands.size)

        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        assertTrue(h.runCommands.drop(before).contains("readDiscovery"))
        assertTrue(h.runCommands.drop(before).none { it == "hubStart" })
        finish()
    }

    @Test
    fun `a background drop with the foreground service held re-reads the token at once`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.coordinator.onBackground(); runCurrent()
        h.serviceHeld = true // a turn is running, KernelForegroundService holds
        val before = h.runCommands.size
        h.host.restart() // `cline hub upgrade` from the TUI: new lifetime, rotated token
        h.hub.current.drop(); advanceUntilIdle()
        h.assertReady()
        assertTrue("KS §0.4: RUN_COMMAND from the app's own foreground service", h.runCommands.drop(before).contains("readDiscovery"))
        assertEquals(h.host.token, h.hub.openedTokens.last())
        assertTrue(h.runCommands.drop(before).none { it == "hubStart" })
        finish()
    }

    @Test
    fun `a background drop of the same daemon lifetime reconnects socket-only with backoff`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.coordinator.onBackground(); runCurrent()
        val before = h.runCommands.size
        // Three quick refusals (the Hub's loop was busy) and the client gives up.
        var refuse = true
        h.hub.openGate = { if (refuse) HubUpgradeFailed(null, "connection refused") else null }
        h.hub.current.drop(); advanceTimeBy(3_000); runCurrent()
        assertTrue("expected Reconnecting, got ${h.state}", h.state is KernelState.Reconnecting)

        refuse = false
        advanceTimeBy(6_000); runCurrent()
        h.assertReady()
        assertEquals("the cached token, no RUN_COMMAND", before, h.runCommands.size)
        assertTrue(h.client.state.value is HubConnectionState.Connected)
        finish()
    }

    @Test
    fun `background reconnects that keep failing end in HubDown so the service is released`() = runTest {
        val h = Harness(this)
        h.coordinator.onForeground(); advanceUntilIdle()
        h.assertReady()
        h.coordinator.onBackground(); runCurrent()
        val before = h.runCommands.size
        h.hub.openGate = { HubUpgradeFailed(null, "connection refused") }
        h.hub.current.drop(); advanceUntilIdle()
        assertEquals(KernelState.HubDown(DownReason.Asleep, null, null), h.state)
        assertTrue(h.coordinator.gate.value is KernelGate.Down)
        assertEquals("no RUN_COMMAND from the background", before, h.runCommands.size)

        // The next foreground re-checks it (the Hub answers again by then).
        h.hub.openGate = { null }
        h.coordinator.onForeground(); advanceTimeBy(5_000); runCurrent()
        h.assertReady()
        finish()
    }

    @Test
    fun `every client reconnect re-attaches the open sessions once and the sequence's own connect is not counted twice`() = runTest {
        val h = Harness(this)
        h.reattach.open = setOf("s1")
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        assertEquals("the sequence's connect re-attached; its late Connected is no reconnect", listOf("s1"), h.reattach.attached)
        val generation = (h.client.state.value as HubConnectionState.Connected).generation

        h.hub.current.drop(); advanceUntilIdle()
        h.assertReady()
        assertTrue((h.client.state.value as HubConnectionState.Connected).generation > generation)
        assertEquals(listOf("s1", "s1"), h.reattach.attached)
        finish()
    }

    @Test
    fun `two early deaths after auto-starts suspend auto-start until the user starts the kernel`() = runTest {
        val h = Harness(this)
        repeat(2) {
            h.coordinator.ensureConnected(); advanceUntilIdle()
            h.assertReady()
            h.host.running = false
            h.hub.current.drop(); advanceUntilIdle()
        }
        assertEquals(KernelState.HubDown(DownReason.CrashLoop, null, null), h.state)
        val starts = h.commands.calls.count { it == "hubStart" }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        assertEquals(KernelState.HubDown(DownReason.CrashLoop, null, null), h.state)
        assertEquals(starts, h.commands.calls.count { it == "hubStart" })
        h.coordinator.ensureConnected(userInitiated = true); advanceUntilIdle()
        h.assertReady()
        finish()
    }

    @Test
    fun `the passive probe notices a Hub started from Termux while the app is in the foreground`() = runTest {
        val h = Harness(this) { prefs.userStopped = true }
        runCurrent()
        h.coordinator.onForeground(); runCurrent()
        assertEquals(KernelState.HubDown(DownReason.Stopped, null, null), h.state)
        val probesBefore = h.prober.healthProbes
        advanceTimeBy(KernelCoordinator.Timing().passiveProbeMs + 1); runCurrent()
        assertTrue(h.prober.healthProbes > probesBefore)
        assertEquals(KernelState.HubDown(DownReason.Stopped, null, null), h.state)

        h.host.running = true
        advanceTimeBy(KernelCoordinator.Timing().passiveProbeMs + 1); runCurrent()
        advanceUntilIdle()
        h.assertReady()
        assertTrue("the probe connects, it never starts", h.commands.calls.none { it == "hubStart" })
        finish()
    }

    // ---- restart (E.7) ----------------------------------------------------------------------

    @Test
    fun `restart upgrades the daemon, rotates the token and reconnects`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        val before = h.assertReady()
        val transitionsBefore = h.coordinator.transitions.snapshot().size

        val precheck = h.coordinator.beginRestart()
        assertFalse(precheck.needsConfirm)
        assertEquals(RestartPhase.CheckingSessions, (h.state as KernelState.Restarting).phase)
        val outcome = h.coordinator.restart(forced = false); advanceUntilIdle()

        assertEquals(KernelCoordinator.RestartOutcome.Restarted, outcome)
        val after = h.assertReady()
        assertTrue(after.info.pid != before.info.pid)
        val phases = h.coordinator.transitions.snapshot().drop(transitionsBefore).map { it.to }.filter { it.startsWith("Restarting(") }
        assertEquals(listOf("Restarting(CheckingSessions)", "Restarting(Disconnecting)", "Restarting(Replacing)", "Restarting(Reconnecting)"), phases)
        assertEquals("Ready", h.coordinator.transitions.snapshot().last().to)
        assertEquals(h.host.token, h.hub.openedTokens.last())
        assertEquals(2, h.tokens.writes)
        assertTrue(h.commands.calls.contains("hubUpgrade"))
        assertTrue(h.commands.calls.none { it == "hubStop" })
        finish()
    }

    @Test
    fun `a forced restart stops and starts instead of upgrading`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        h.coordinator.beginRestart()
        assertEquals(KernelCoordinator.RestartOutcome.Restarted, h.coordinator.restart(forced = true))
        advanceUntilIdle()
        h.assertReady()
        val calls = h.commands.calls
        assertTrue(calls.indexOf("hubStop") < calls.lastIndexOf("hubStart"))
        assertTrue(calls.none { it == "hubUpgrade" })
        finish()
    }

    @Test
    fun `the pre-check counts other clients' sessions and own running work`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        h.hub.onCommand = { env ->
            when (env.command) {
                HubCommands.HUB_STATUS -> dev.cline.termux.core.kernel.hub.HubReplyEnvelope(requestId = env.requestId, ok = true, payload = h.hub.statusPayload(activeRpcTurns = 1))
                HubCommands.SESSION_LIST -> dev.cline.termux.core.kernel.hub.HubReplyEnvelope(
                    requestId = env.requestId, ok = true,
                    payload = dev.cline.termux.core.kernel.hub.HubJson.parseToJsonElement(
                        """{"sessions":[{"id":"a","participants":[{"clientId":"android-t"},{"clientId":"cli-1"}]},{"id":"b","participants":[{"clientId":"android-t"}]}]}""",
                    ) as kotlinx.serialization.json.JsonObject,
                )
                else -> h.hub.defaultReply(env)
            }
        }
        val precheck = h.coordinator.beginRestart()
        assertTrue(precheck.needsConfirm)
        assertTrue(precheck.ownBusy)
        assertEquals(HubSessionActivity(1, 1), precheck.others)
        h.coordinator.cancelRestart()
        h.assertReady()
        finish()
    }

    @Test
    fun `a Hub still serving sessions is reconnected to and reported verbatim`() = runTest {
        val h = Harness(this) { commands.hubUpgradeResult = FakeKernelCommands.fail(1, "Hub is still serving sessions after the wait window; not replacing it") }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        val before = h.assertReady()
        h.coordinator.beginRestart()
        val outcome = h.coordinator.restart(forced = false); advanceUntilIdle()
        assertEquals(KernelCoordinator.RestartOutcome.StillServing("Hub is still serving sessions after the wait window; not replacing it"), outcome)
        assertEquals(before.info.pid, h.assertReady().info.pid)
        finish()
    }

    @Test
    fun `an upgrade whose RUN_COMMAND timed out is judged by the daemon lifetime`() = runTest {
        val h = Harness(this) { commands.hubUpgradeResult = KernelCommandResult.TimedOut(75_000) }
        h.coordinator.ensureConnected(); advanceUntilIdle()
        val before = h.assertReady()
        h.coordinator.beginRestart()
        val same = h.coordinator.restart(forced = false); advanceUntilIdle()
        assertEquals(KernelCoordinator.RestartOutcome.Failed(null), same)
        assertEquals(before.info.pid, h.assertReady().info.pid)

        h.commands.hubUpgradeResult = KernelCommandResult.TimedOut(75_000)
        h.coordinator.beginRestart()
        h.host.restart()
        val replaced = h.coordinator.restart(forced = false); advanceUntilIdle()
        assertEquals(KernelCoordinator.RestartOutcome.Restarted, replaced)
        assertTrue(h.assertReady().info.pid != before.info.pid)
        finish()
    }

    // ---- sheet actions ------------------------------------------------------------------------

    @Test
    fun `refreshHubStatus re-reads the live status and the keep-awake switch drives the lock`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        h.hub.lastSequence = 77
        val status = h.coordinator.refreshHubStatus()
        assertEquals(77L, status!!.lastSequence)
        assertEquals(77L, (h.state as KernelState.Ready).hub!!.lastSequence)

        assertEquals(KernelCoordinator.WakeLockOutcome.Applied, h.coordinator.setKeepAwake(false))
        assertFalse(h.prefs.keepAwake)
        assertFalse(h.prefs.wakeLockRequested)
        assertEquals("wakeUnlock", h.commands.calls.last())
        h.commands.wakeLockResult = FakeKernelCommands.fail(1, "termux-wake-lock: not available")
        assertEquals(KernelCoordinator.WakeLockOutcome.Failed("termux-wake-lock: not available"), h.coordinator.setKeepAwake(true))
        assertEquals("termux-wake-lock: not available", h.coordinator.wakeLockError.value)
        finish()
    }

    @Test
    fun `fixProcesses runs doctor fix and reports the daemon gone`() = runTest {
        val h = Harness(this)
        h.coordinator.ensureConnected(); advanceUntilIdle()
        h.assertReady()
        h.commands.doctorJsonResult = FakeKernelCommands.ok("""fixing...${'\n'}{"cliVersion":"3.0.68","hubHealthy":false,"staleHubPids":[9],"listeningPids":[],"hubStartupLocks":[{"pid":1}],"staleCliPids":[]}""")
        val doctor = h.coordinator.fixProcesses(); advanceUntilIdle()
        assertFalse(doctor!!.hubHealthy)
        assertEquals(listOf(9), doctor.staleHubPids)
        assertEquals(1, doctor.hubStartupLocks)
        val down = h.state as KernelState.HubDown
        assertEquals(DownReason.StoppedInTermux, down.reason)
        assertEquals(doctor, down.doctor)
        assertEquals(listOf("doctorFix", "doctorJson"), h.commands.calls.takeLast(2))
        finish()
    }
}
