package dev.cline.termux.core.kernel.dashboard

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.ClineCommands.Labels
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.expectThrows
import dev.cline.termux.fakes.FakeDashboardTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SP §0.3 lease, probe matrix and budgets (docs/07 §10.1 "Settings helper client"). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsHelperTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    private class FakeKernel : HelperKernel {
        var userStopped = false
        var hubHealthy = true
        var startBringsHubUp = true
        var starts = 0
        var adopts = 0
        override suspend fun isUserStopped() = userStopped
        override suspend fun isHubHealthy() = hubHealthy
        override suspend fun startHub() { starts++; if (startBringsHubUp) hubHealthy = true }
        override suspend fun adoptHub() { adopts++ }
    }

    private class FakeUnsupported(var value: Boolean = false) : HelperUnsupportedStore {
        var writes = mutableListOf<Boolean>()
        override suspend fun read() = value
        override suspend fun write(unsupported: Boolean) { value = unsupported; writes += unsupported }
    }

    private inner class Harness(scope: TestScope) {
        val termux = FakeTermuxExecutor()
        val transport = FakeDashboardTransport()
        val kernel = FakeKernel()
        val store = FakeUnsupported()

        /** The dashboard this process would start: `/health` answers once it is "up". */
        var dashboardUp = false
        var killed = false

        val helper = SettingsHelper(
            termux = termux,
            transport = transport,
            kernel = kernel,
            unsupportedStore = store,
            scope = scope,
            elapsedMs = { scope.testScheduler.currentTime },
            secretSource = { secret },
        )

        init {
            transport.probeResponder = { s -> if (dashboardUp && s == secret) HealthProbe.Ok else HealthProbe.Refused }
            termux.fallback = { TermuxResult.success("") }
        }

        fun dashboardStartsOnLaunch() {
            transport.probeResponder = { s -> if (termux.detached.isNotEmpty() && s == secret) HealthProbe.Ok else HealthProbe.Refused }
        }

        val state get() = helper.state.value
        fun stops() = termux.commandsLabelled(Labels.DASHBOARD_STOP)
    }

    private fun TestScope.harness() = Harness(this)

    @Test
    fun `the first holder starts the sequence and the helper becomes Ready`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.helper.acquire("providers")
        assertEquals(HelperState.Idle, h.state) // nothing ran yet
        advanceUntilIdle()

        assertEquals(HelperState.Ready, h.state)
        assertEquals(1, h.termux.detached.size)
        val launch = h.termux.detached.single().command
        assertEquals(Labels.DASHBOARD_START, launch.label)
        assertEquals(ClineCommands.dashboardStart(secret).args, launch.args)
        assertEquals(secret, h.transport.lastConnectSecret)
        assertEquals(1, h.transport.connectAttempts)
        assertEquals(1, h.kernel.adopts)
        assertEquals(0, h.kernel.starts)
        assertEquals(1, h.helper.holderCount)
        assertTrue(h.stops().isEmpty())
    }

    @Test
    fun `kernel first - a user-stopped kernel is never restarted`() = runTest {
        val h = harness()
        h.kernel.userStopped = true
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.KernelStopped, null), h.state)
        assertTrue(h.termux.commands.isEmpty())
        assertTrue(h.transport.probes.isEmpty())
        assertEquals(0, h.kernel.starts)
    }

    @Test
    fun `a remembered unsupported build short-circuits until cleared`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.store.value = true
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.BuildUnsupported, null), h.state)
        assertTrue(h.termux.commands.isEmpty())

        h.helper.clearUnsupported()
        h.helper.retry()
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
    }

    @Test
    fun `Termux missing and permission missing are reported before any probe`() = runTest {
        val h = harness()
        h.termux.presence = TermuxPresence.Missing
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.TermuxMissing, null), h.state)

        h.termux.presence = TermuxPresence.Installed(1, "x")
        h.termux.permitted = false
        h.helper.retry()
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.PermissionMissing, null), h.state)
        assertTrue(h.transport.probes.isEmpty())
        assertTrue(h.termux.commands.isEmpty())
    }

    @Test
    fun `a Hub that is down is started by the coordinator before the dashboard launches`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.kernel.hubHealthy = false
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(1, h.kernel.starts)
        assertEquals(HelperState.Ready, h.state)
    }

    @Test
    fun `the start budget is 20 s with the Hub up and 45 s without`() = runTest {
        val h = harness()
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.StartTimeout, null), h.state)
        assertTrue("took ${currentTime} ms", currentTime in 20_000..20_600)
        assertTrue(h.transport.probes.size > 30)

        val cold = harness()
        cold.kernel.hubHealthy = false
        cold.kernel.startBringsHubUp = false
        val before = currentTime
        cold.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperFailure.StartTimeout, (cold.state as HelperState.Unavailable).reason)
        assertTrue("took ${currentTime - before} ms", (currentTime - before) in 45_000..45_600)
    }

    @Test
    fun `an early exit before health answers surfaces the stripped stderr tail`() = runTest {
        val h = harness()
        h.helper.acquire("a")
        runCurrent()
        assertTrue(h.state is HelperState.Starting)
        h.termux.detached.single().exitWith(TermuxResult.failure(1, "warming up\nError: listen EADDRINUSE: address already in use 127.0.0.1:8787\n"))
        advanceUntilIdle()
        assertEquals(
            HelperState.Unavailable(HelperFailure.StartTimeout, "warming up\nError: listen EADDRINUSE: address already in use 127.0.0.1:8787"),
            h.state,
        )
        assertTrue(currentTime < 5_000)

        val blocked = harness()
        blocked.helper.acquire("a")
        runCurrent()
        blocked.termux.detached.single().failWith(TermuxUnavailable.ExternalAppsBlocked("allow-external-apps is false"))
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.ExternalAppsBlocked, "allow-external-apps is false"), blocked.state)
    }

    @Test
    fun `a retry after StartTimeout polls the launch that is still booting instead of launching a second one`() = runTest {
        val h = harness()
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.StartTimeout, null), h.state)
        assertEquals(1, h.termux.detached.size)

        // The slow cold start is still booting with our secret; "Retry" adopts it once it binds.
        h.helper.retry()
        runCurrent()
        assertTrue(h.state is HelperState.Starting)
        advanceTimeBy(3_000)
        h.dashboardUp = true
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        assertEquals("no second cline dashboard races the first for the port", 1, h.termux.detached.size)
        assertTrue(h.stops().isEmpty())
        assertFalse(h.termux.detached.single().cancelled)
    }

    @Test
    fun `a retry after the earlier launch exited stops what is left of it and launches again`() = runTest {
        val h = harness()
        h.helper.acquire("a")
        runCurrent()
        h.termux.detached.single().exitWith(TermuxResult.failure(1, "boom\n"))
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.StartTimeout, "boom"), h.state)

        h.transport.probeResponder = { s -> if (h.termux.detached.size >= 2 && s == secret) HealthProbe.Ok else HealthProbe.Refused }
        h.helper.retry()
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        assertEquals(2, h.termux.detached.size)
        assertEquals(listOf(Labels.DASHBOARD_START, Labels.DASHBOARD_STOP, Labels.DASHBOARD_START), h.termux.labels)
        assertEquals(ClineCommands.dashboardStopPattern(secret), h.stops().single().args.last())
    }

    @Test
    fun `a secret-less dashboard on the port is never reused`() = runTest {
        val h = harness()
        h.transport.probeResponder = { HealthProbe.Ok }
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.PortInUse, null), h.state)
        assertTrue(h.termux.commands.isEmpty())
        assertEquals(listOf(secret, null), h.transport.probes.map { it.roomSecret })
    }

    @Test
    fun `our own dashboard from this process is adopted without a new launch`() = runTest {
        val h = harness()
        h.transport.probeResponder = { s -> if (s == secret) HealthProbe.Ok else HealthProbe.Forbidden }
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        assertTrue(h.termux.detached.isEmpty())
        assertEquals(0, h.kernel.adopts)
        h.helper.release("a")
        advanceUntilIdle()
        assertEquals(HelperState.Idle, h.state)
        assertEquals(1, h.stops().size)
    }

    @Test
    fun `a dashboard from an earlier app process is killed by the generic pattern and replaced`() = runTest {
        val h = harness()
        h.transport.probeResponder = { s ->
            when {
                h.termux.detached.isNotEmpty() && s == secret -> HealthProbe.Ok
                h.killed -> HealthProbe.Refused
                else -> HealthProbe.Forbidden
            }
        }
        h.termux.on(Labels.DASHBOARD_STOP) { h.killed = true; TermuxResult.success("") }
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        val kill = h.stops().single()
        assertEquals(ClineCommands.dashboardStopPattern(null), kill.args.last())
        assertFalse(kill.args.last().contains(secret))
        assertEquals(1, h.termux.detached.size)
    }

    @Test
    fun `a foreign dashboard that survives the kill is PortInUse`() = runTest {
        val h = harness()
        h.transport.probeResponder = { HealthProbe.Forbidden }
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.PortInUse, null), h.state)
        assertEquals(1, h.stops().size)
        assertTrue(h.termux.detached.isEmpty())
        assertTrue("took $currentTime ms", currentTime in 5_000..5_600)
    }

    @Test
    fun `a non-101 upgrade from a healthy dashboard latches BuildUnsupported`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.transport.connectFailure = DashboardUpgradeFailed(426, "dashboard websocket upgrade failed: ProtocolException")
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.BuildUnsupported, "HTTP 426"), h.state)
        assertEquals(listOf(true), h.store.writes)

        h.helper.retry()
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.BuildUnsupported, null), h.state)
        assertEquals(1, h.transport.connectAttempts)
    }

    @Test
    fun `401, 403 and plain socket errors are SocketFailed and not remembered`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.transport.connectFailure = DashboardUpgradeFailed(403, "forbidden")
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperFailure.SocketFailed, (h.state as HelperState.Unavailable).reason)
        assertTrue(h.store.writes.isEmpty())

        h.transport.connectFailure = IOException("reset")
        h.helper.retry()
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.SocketFailed, "reset"), h.state)
        assertTrue(h.store.writes.isEmpty())
    }

    @Test
    fun `desktopCommand waits for a running start and then forwards to the socket`() = runTest {
        val h = harness()
        h.transport.onConnect = { client -> client.respond("list_providers", JsonPrimitive("ok")) }
        h.helper.acquire("a")
        runCurrent()
        assertTrue(h.state is HelperState.Starting)
        val pending = async { h.helper.desktopCommand("list_providers", buildJsonObject { put("q", 1) }) }
        runCurrent()
        assertFalse(pending.isCompleted)
        h.dashboardUp = true
        advanceUntilIdle()
        assertEquals(JsonPrimitive("ok"), pending.await())
        val call = h.transport.lastClient!!.calls.single()
        assertEquals("list_providers", call.command)
        assertEquals(1, call.args["q"]!!.let { (it as JsonPrimitive).content.toInt() })
        assertEquals(30_000L, call.timeoutMs)
    }

    @Test
    fun `desktopCommand without a Ready helper is NotRunning with the settled reason`() = runTest {
        val h = harness()
        assertNull(expectThrows<HelperUnavailable.NotRunning> { h.helper.desktopCommand("x") }.reason)
        h.kernel.userStopped = true
        h.helper.acquire("a")
        advanceUntilIdle()
        assertEquals(HelperFailure.KernelStopped, expectThrows<HelperUnavailable.NotRunning> { h.helper.desktopCommand("x") }.reason)
    }

    @Test
    fun `a dropped socket while Ready becomes SocketFailed and retry reconnects`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.helper.acquire("a")
        advanceUntilIdle()
        h.transport.lastClient!!.dropConnection()
        advanceUntilIdle()
        assertEquals(HelperState.Unavailable(HelperFailure.SocketFailed, null), h.state)
        expectThrows<HelperUnavailable.NotRunning> { h.helper.desktopCommand("x") }

        h.helper.retry()
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        assertEquals(2, h.transport.connectAttempts)
    }

    @Test
    fun `retry while Ready is a no-op`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.helper.acquire("a")
        advanceUntilIdle()
        h.helper.retry()
        advanceUntilIdle()
        assertEquals(1, h.transport.connectAttempts)
        assertEquals(1, h.termux.detached.size)
    }

    @Test
    fun `the last release stops the dashboard after the grace, a re-acquire inside it cancels the stop`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.helper.acquire("providers")
        h.helper.acquire("customize")
        advanceUntilIdle()
        assertEquals(2, h.helper.holderCount)

        h.helper.release("providers")
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        assertTrue(h.stops().isEmpty())

        h.helper.release("customize")
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(HelperState.Ready, h.state)
        h.helper.acquire("providers")
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        assertTrue(h.stops().isEmpty())
        assertEquals(1, h.transport.connectAttempts)

        h.helper.release("providers")
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(HelperState.Idle, h.state)
        val stop = h.stops().single()
        assertEquals(ClineCommands.dashboardStopPattern(secret), stop.args.last())
        assertTrue(h.transport.lastClient!!.closed)
        assertTrue(h.termux.detached.single().cancelled)
        assertEquals(0, h.helper.holderCount)
    }

    @Test
    fun `a release during the start never kills mid-start`() = runTest {
        val h = harness()
        h.helper.acquire("a")
        runCurrent()
        assertTrue(h.state is HelperState.Starting)
        h.helper.release("a")
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue(h.state is HelperState.Starting)
        assertTrue(h.stops().isEmpty())

        advanceUntilIdle()
        assertEquals(HelperState.Idle, h.state)
        assertEquals(listOf(Labels.DASHBOARD_START, Labels.DASHBOARD_STOP), h.termux.labels)
    }

    @Test
    fun `nothing is killed when this process never started a dashboard`() = runTest {
        val h = harness()
        h.kernel.userStopped = true
        h.helper.acquire("a")
        advanceUntilIdle()
        h.helper.release("a")
        advanceUntilIdle()
        assertEquals(HelperState.Idle, h.state)
        assertTrue(h.termux.commands.isEmpty())
    }

    @Test
    fun `releasing an unknown holder or a non-last holder does nothing`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.helper.acquire("a")
        advanceUntilIdle()
        h.helper.release("ghost")
        advanceUntilIdle()
        assertEquals(HelperState.Ready, h.state)
        assertEquals(1, h.helper.holderCount)
    }

    @Test
    fun `the secret never appears in state or labels`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.helper.acquire("a")
        advanceUntilIdle()
        h.helper.release("a")
        advanceUntilIdle()
        assertFalse(h.state.toString().contains(secret))
        h.termux.commands.forEach { command ->
            assertFalse(command.toString().contains(secret))
            assertFalse(command.label.contains(secret))
        }
        assertTrue(h.termux.commands.any { it.args.contains(secret) })
    }

    @Test
    fun `randomRoomSecret is 32 lowercase hex characters and differs per call`() {
        val a = SettingsHelper.randomRoomSecret()
        val b = SettingsHelper.randomRoomSecret()
        assertTrue(a, Regex("[0-9a-f]{32}").matches(a))
        assertNotEquals(a, b)
    }

    @Test
    fun `awaitSettled returns the state the start settled in`() = runTest {
        val h = harness().apply { dashboardStartsOnLaunch() }
        h.helper.acquire("a")
        val settled = async { h.helper.awaitSettled() }
        advanceUntilIdle()
        assertEquals(HelperState.Ready, settled.await())
    }
}
