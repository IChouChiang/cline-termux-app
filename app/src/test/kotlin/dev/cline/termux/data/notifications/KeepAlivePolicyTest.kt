package dev.cline.termux.data.notifications

import app.cash.turbine.test
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.notifications.NotificationStates.T0
import dev.cline.termux.data.notifications.NotificationStates.ended
import dev.cline.termux.data.notifications.NotificationStates.idle
import dev.cline.termux.data.notifications.NotificationStates.running
import dev.cline.termux.data.notifications.NotificationStates.withApproval
import dev.cline.termux.data.notifications.NotificationStates.withPhase
import dev.cline.termux.data.notifications.NotificationStates.withQuestion
import dev.cline.termux.data.notifications.NotificationStates.factsOf
import dev.cline.termux.data.notifications.NotificationStates.notify
import dev.cline.termux.data.session.NotifyFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CR §12.2 hold rule and §12.3 variants, with the 10 s release debounce (docs/07 §4.7). */
@OptIn(ExperimentalCoroutinesApi::class)
class KeepAlivePolicyTest {
    private val states = MutableStateFlow<Map<String, NotifyFacts>>(emptyMap())
    private val keepConnected = MutableStateFlow(false)
    private val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
    private val foreground = MutableStateFlow(true)

    private fun TestScope.policy() = KeepAlivePolicy(states, keepConnected, gate, foreground, backgroundScope)

    private fun put(vararg s: SessionState) {
        states.value = factsOf(*s)
    }

    @Test
    fun releasedWhileNothingRuns() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        put(idle("s1"))
        runCurrent()
        assertEquals(HoldState.Released, policy.state.value)
        assertFalse(policy.wouldHoldInBackground())
    }

    @Test
    fun aRunningSessionHoldsWithItsTitleAndStart() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        policy.state.test {
            assertEquals(HoldState.Released, awaitItem())
            put(running("s1", startedAt = T0))
            assertEquals(
                HoldState(hold = true, variant = KeepAliveVariant.Working("s1", "Fix the login redirect", T0), reconnecting = false, stopAvailable = true),
                awaitItem(),
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun startingAndStoppingPhasesHold() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        put(idle("s1").withPhase(RunPhase.Starting))
        runCurrent()
        assertTrue(policy.state.value.hold)
        put(running("s1").withPhase(RunPhase.Stopping))
        runCurrent()
        assertEquals(KeepAliveVariant.Stopping("s1", "Fix the login redirect"), policy.state.value.variant)
    }

    @Test
    fun aFirstSendHoldsWhileItsSessionIsCreated() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        policy.setFirstSend("local:1", PendingFirstSend("slow story please", T0))
        runCurrent()
        assertEquals(KeepAliveVariant.Sending("slow story please", T0), policy.state.value.variant)
        // Home pressed during session.create: ON_PAUSE must find a hold to start the service for.
        assertTrue(policy.wouldHoldInBackground())
        // The created session runs; the send hands over without a gap.
        put(running("s1", startedAt = T0 + 5_000))
        policy.setFirstSend("local:1", null)
        runCurrent()
        assertEquals(KeepAliveVariant.Working("s1", "Fix the login redirect", T0 + 5_000), policy.state.value.variant)
    }

    @Test
    fun aFirstSendBesideARunCountsAsSeveral() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        put(running("s1", startedAt = T0))
        policy.setFirstSend("local:1", PendingFirstSend(null, T0 + 1))
        runCurrent()
        assertEquals(KeepAliveVariant.Several(listOf("Fix the login redirect", null)), policy.state.value.variant)
    }

    @Test
    fun aFailedFirstSendReleases() = runTest {
        val policy = policy()
        policy.setFirstSend("local:1", PendingFirstSend("x", T0))
        runCurrent()
        assertTrue(policy.state.value.hold)
        policy.setFirstSend("local:1", null)
        runCurrent()
        assertFalse(policy.holdsNow())
        assertTrue(policy.state.value.hold)
        advanceTimeBy(KeepAlivePolicy.RELEASE_DEBOUNCE_MS + 1)
        runCurrent()
        assertEquals(HoldState.Released, policy.state.value)
    }

    @Test
    fun approvalsAndQuestionsPickTheirVariant() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        put(running("s1").withApproval("a1", toolName = "run_commands"))
        runCurrent()
        assertEquals(KeepAliveVariant.WaitingApproval("s1", "run_commands"), policy.state.value.variant)
        put(running("s1").withQuestion("q1", "Which branch?"))
        runCurrent()
        assertEquals(KeepAliveVariant.WaitingAnswer("s1", "Fix the login redirect"), policy.state.value.variant)
    }

    @Test
    fun severalSessionsListTheirTitlesInStartOrder() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        put(running("s2", startedAt = T0 + 10, title = "Second"), running("s1", startedAt = T0, title = "First"), idle("s3"))
        runCurrent()
        val variant = policy.state.value.variant as KeepAliveVariant.Several
        assertEquals(listOf("First", "Second"), variant.titles)
        assertEquals(2, variant.count)
    }

    @Test
    fun keepConnectedHoldsOnlyInTheBackgroundWhileReady() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        keepConnected.value = true
        put(idle("s1"))
        runCurrent()
        assertFalse(policy.state.value.hold)
        assertTrue(policy.wouldHoldInBackground())
        foreground.value = false
        runCurrent()
        assertEquals(KeepAliveVariant.ConnectedIdle, policy.state.value.variant)
    }

    @Test
    fun releaseIsDebouncedTenSeconds() = runTest {
        val policy = policy()
        put(running("s1"))
        runCurrent()
        assertTrue(policy.state.value.hold)
        put(running("s1").ended(SessionStatus.Completed, at = T0 + 1))
        runCurrent()
        advanceTimeBy(KeepAlivePolicy.RELEASE_DEBOUNCE_MS - 1)
        runCurrent()
        assertTrue("still held inside the debounce", policy.state.value.hold)
        advanceTimeBy(2)
        runCurrent()
        assertFalse(policy.state.value.hold)
    }

    @Test
    fun aNewHoldInsideTheDebounceCancelsTheRelease() = runTest {
        val policy = policy()
        put(running("s1"))
        runCurrent()
        put(running("s1").ended(SessionStatus.Completed, at = T0 + 1))
        runCurrent()
        advanceTimeBy(5_000)
        put(running("s1", runId = "run-2", startedAt = T0 + 6_000, seq = 10))
        runCurrent()
        advanceTimeBy(KeepAlivePolicy.RELEASE_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(KeepAliveVariant.Working("s1", "Fix the login redirect", T0 + 6_000), policy.state.value.variant)
    }

    @Test
    fun reconnectingOmitsStopAndDownReleases() = runTest {
        val policy = policy()
        put(running("s1"))
        gate.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        runCurrent()
        assertTrue(policy.state.value.reconnecting)
        assertFalse(policy.state.value.stopAvailable)
        gate.value = KernelGate.Down(KernelGate.DownVariant.Asleep, null)
        runCurrent()
        advanceTimeBy(KeepAlivePolicy.RELEASE_DEBOUNCE_MS + 1)
        runCurrent()
        assertFalse(policy.state.value.hold)
    }

    @Test
    fun aConfirmedStopShowsStoppingUntilTheRunEnds() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        put(running("s1"))
        runCurrent()
        policy.onStopConfirmed("s1")
        runCurrent()
        assertEquals(KeepAliveVariant.Stopping("s1", "Fix the login redirect"), policy.state.value.variant)
        put(running("s1").ended(SessionStatus.Cancelled, at = T0 + 1))
        runCurrent()
        // A new run in the same session is "working" again, not stopping.
        put(running("s1", runId = "run-2", seq = 9))
        runCurrent()
        assertTrue(policy.state.value.variant is KeepAliveVariant.Working)
    }

    @Test
    fun onlyKnownSessionsCount() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val known = MutableStateFlow(setOf("mine"))
        val policy = KeepAlivePolicy(knownSessionStates(source, known), keepConnected, gate, foreground, backgroundScope)
        source.put(running("tui"))
        runCurrent()
        assertFalse("a TUI session the app never opened does not hold", policy.state.value.hold)
        source.put(running("mine"))
        runCurrent()
        assertEquals("mine", (policy.state.value.variant as KeepAliveVariant.Working).sessionId)
        known.value = setOf("mine", "tui")
        runCurrent()
        assertTrue(policy.state.value.variant is KeepAliveVariant.Several)
    }

    @Test
    fun serviceStatusReports() = runTest(UnconfinedTestDispatcher()) {
        val policy = policy()
        assertEquals(KeepAlive.Inactive, policy.status.value)
        policy.reportRunning()
        assertEquals(KeepAlive.Running, policy.status.value)
        policy.reportStopped()
        assertEquals(KeepAlive.Inactive, policy.status.value)
        policy.reportDenied()
        assertEquals(KeepAlive.Denied, policy.status.value)
    }

    @Test
    fun knownFactsEmitOnlyWhenAKnownSessionsFactsChange() = runTest(UnconfinedTestDispatcher()) {
        val source = FakeSource()
        val known = MutableStateFlow(setOf("mine"))
        knownSessionStates(source, known).test {
            assertEquals(emptyMap<String, NotifyFacts>(), awaitItem())
            source.put(running("tui"))
            expectNoEvents()
            source.put(running("mine"))
            assertEquals(factsOf(running("mine")), awaitItem())
            // Streamed deltas move the sequence and the transcript, not the facts.
            source.put(running("mine", seq = 40))
            expectNoEvents()
            source.put(running("mine").withApproval("a1"))
            assertEquals(setOf("a1"), awaitItem().getValue("mine").approvals.keys)
            known.value = setOf("mine", "tui")
            assertEquals(setOf("mine", "tui"), awaitItem().keys)
            // The store evicted the session: it leaves the map instead of being pinned in memory.
            source.evict("tui")
            assertEquals(setOf("mine"), awaitItem().keys)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** The store seam: the [NotifyFacts] of the sessions in memory. */
    private class FakeSource : LoadedSessionSource {
        override val facts = MutableStateFlow<Map<String, NotifyFacts>>(emptyMap())

        fun put(state: SessionState) {
            facts.value = facts.value + (state.sessionId to state.notify())
        }

        fun evict(sessionId: String) {
            facts.value = facts.value - sessionId
        }
    }
}
