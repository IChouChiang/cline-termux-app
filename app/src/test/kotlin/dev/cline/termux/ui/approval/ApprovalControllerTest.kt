package dev.cline.termux.ui.approval

import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.session.RunRef
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionFactsReducer
import dev.cline.termux.fakes.FakePendingInputHost
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.HubFixtures.command
import dev.cline.termux.fakes.HubFixtures.event
import dev.cline.termux.ui.kernel.KernelAction
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** composer-approvals.md C3 controller rules, with the recorded fixtures 02 and 03 replayed through the real reducer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalControllerTest {
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val f02 by lazy { HubFixtures.load("02-run-with-tool-approval.jsonl") }
    private val f03 by lazy { HubFixtures.load("03-reject-and-abort.jsonl") }
    private val session1 = "1791064689782_kck3p"
    private val session2 = "1791064691937_rkppp"
    private val approval02 = "approval_1791064690209_v5gc4"
    private val approval03 = "approval_1791064692310_10bwx"

    private class Harness(val host: FakePendingInputHost, val controller: ApprovalController, val effects: MutableList<ApprovalEffect>)

    private fun TestScope.harness(sessionId: String, visible: Boolean = true, host: FakePendingInputHost = FakePendingInputHost(clientId)): Harness {
        val controller = ApprovalController(sessionId, host, backgroundScope, initiallyVisible = visible)
        val effects = mutableListOf<ApprovalEffect>()
        backgroundScope.launch { controller.effects.collect { effects += it } }
        runCurrent()
        return Harness(host, controller, effects)
    }

    /** Replays fixture events into the fake host until [stop] matches (inclusive); returns the index after it. */
    private fun TestScope.replay(h: Harness, lines: List<HubFixtures.Line>, from: Int = 0, stop: (HubEventEnvelope) -> Boolean = { false }): Int {
        var i = from
        while (i < lines.size) {
            val e = lines[i].event
            i++
            if (e != null) {
                h.host.dispatch(e)
                runCurrent()
                if (stop(e)) return i
            }
        }
        return i
    }

    private fun approvalRequested(sessionId: String, id: String, seq: Long, tool: String = "read_files", input: String = """{"files":[{"path":"/w/a.ts"}]}""") =
        HubEventEnvelope(
            event = HubEvents.APPROVAL_REQUESTED, sessionId = sessionId, sequence = seq, timestamp = 1_791_064_700_000 + seq,
            payload = buildJsonObject {
                put("approvalId", id); put("sessionId", sessionId); put("toolCallId", "call_$id"); put("toolName", tool)
                put("inputJson", input); put("iteration", 2)
            },
        )

    private fun approvalResolved(sessionId: String, id: String, seq: Long, cancelled: Boolean = false, approved: Boolean = false) =
        HubEventEnvelope(
            event = HubEvents.APPROVAL_RESOLVED, sessionId = sessionId, sequence = seq,
            payload = buildJsonObject { put("approvalId", id); put("approved", approved); if (cancelled) put("cancelled", true) },
        )

    // ---- fixture replay -----------------------------------------------------------------------

    @Test
    fun `fixture 02 - approval opens expanded, Approve sends the recorded respond and the card leaves`() = runTest {
        val h = harness(session1)
        val next = replay(h, f02) { it.event == HubEvents.APPROVAL_REQUESTED }
        val s = h.controller.state.value
        assertEquals(1, s.cards.size)
        val card = s.cards.single()
        assertEquals(approval02, card.approvalId)
        assertEquals("run_commands", card.toolName)
        assertEquals("call_mock_run_1", card.toolCallId)
        assertEquals(1, card.iteration)
        assertEquals(1_791_064_690_209, card.requestedAtMs)
        assertEquals(ApprovalPreview.Lines(listOf(PreviewLine.Command("echo hello-from-mock"))), card.preview)
        assertEquals("{\n  \"commands\": [\n    \"echo hello-from-mock\"\n  ]\n}", card.rawInput)
        assertEquals(SheetVisibility.Expanded, s.sheet)
        assertTrue(h.controller.hasPending.value)
        assertEquals(listOf<ApprovalEffect>(ApprovalEffect.Opened), h.effects)

        val reply = CompletableDeferred<Unit>()
        h.host.onRespond = { reply.await() }
        h.controller.approve(approval02)
        runCurrent()
        assertEquals(ApprovalAction.Approving, h.controller.state.value.cards.single().action)
        // Both buttons are disabled while one decision is in flight.
        h.controller.reject(approval02)
        runCurrent()
        assertEquals(1, h.host.decisions.size)

        // The payload equals the recorded approval.respond.
        val recorded = f02.mapNotNull { it.command }.first { it.command == HubCommands.APPROVAL_RESPOND }.payload
        val d = h.host.decisions.single()
        assertEquals(recorded, HubPayloads.approvalRespond(d.approvalId, d.approved, d.reason))

        reply.complete(Unit)
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
        assertTrue(ApprovalEffect.Decided(true) in h.effects)

        // The re-issued copy (same sequence) and the rest of the run change nothing.
        replay(h, f02, next)
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertFalse(h.controller.hasPending.value)
        assertTrue(h.host.failures.isEmpty())
    }

    @Test
    fun `fixture 03 - Reject sends the FM reason and the turn continues`() = runTest {
        val h = harness(session2)
        val next = replay(h, f03) { it.event == HubEvents.APPROVAL_REQUESTED }
        assertEquals(approval03, h.controller.state.value.cards.single().approvalId)
        h.controller.reject(approval03)
        runCurrent()
        val recorded = f03.mapNotNull { it.command }.first { it.command == HubCommands.APPROVAL_RESPOND }.payload
        val d = h.host.decisions.single()
        assertEquals(ApprovalController.REJECT_REASON, d.reason)
        assertEquals(recorded, HubPayloads.approvalRespond(d.approvalId, d.approved, d.reason))
        assertTrue(ApprovalEffect.Decided(false) in h.effects)
        assertTrue(h.controller.state.value.cards.isEmpty())
        // No run.abort: rejecting never stops the turn (C3.7 rule 2).
        replay(h, f03, next)
        assertTrue(h.host.aborts.isEmpty())
        assertTrue(h.controller.state.value.cards.isEmpty())
    }

    @Test
    fun `fixture 03 - Stop agent aborts and the cancelled resolution closes the sheet`() = runTest {
        val h = harness(session2)
        replay(h, f03) { it.event == HubEvents.APPROVAL_REQUESTED }
        h.controller.stop()
        runCurrent()
        assertEquals(listOf(session2), h.host.aborts)
        assertTrue(h.controller.state.value.stopping)
        assertEquals(1, h.controller.state.value.cards.size)
        h.controller.stop()
        runCurrent()
        assertEquals(1, h.host.aborts.size)
        h.host.dispatch(approvalResolved(session2, approval03, seq = 66, cancelled = true))
        runCurrent()
        val s = h.controller.state.value
        assertTrue(s.cards.isEmpty())
        assertEquals(SheetVisibility.Hidden, s.sheet)
        assertFalse(s.stopping)
        assertFalse(ApprovalEffect.AlreadyAnswered in h.effects)
        assertFalse(ApprovalEffect.StopFailed in h.effects)
        advanceTimeBy(ApprovalController.STOP_TIMEOUT_MS + 1)
        runCurrent()
        assertFalse(ApprovalEffect.StopFailed in h.effects)
    }

    @Test
    fun `fixture 03 - the run_aborted tail clears a pending card`() = runTest {
        val h = harness(session2)
        replay(h, f03) { it.event == HubEvents.SESSION_CREATED }
        h.host.dispatch(approvalRequested(session2, "approval_x", seq = 85))
        runCurrent()
        assertEquals(1, h.controller.state.value.cards.size)
        val abortedIndex = f03.indexOfFirst { it.event?.event == HubEvents.RUN_ABORTED }
        h.host.dispatch(f03[abortedIndex].event!!)
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertFalse(h.controller.hasPending.value)
    }

    // ---- rules ---------------------------------------------------------------------------------

    @Test
    fun `collapse never rejects and Review reopens`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.controller.collapse()
        assertEquals(SheetVisibility.Collapsed, h.controller.state.value.sheet)
        assertTrue(h.controller.state.value.bannerVisible)
        assertTrue(h.host.decisions.isEmpty())
        h.controller.reopen()
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
    }

    @Test
    fun `two requests stack in arrival order, open half expanded, each independent`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        h.host.dispatch(approvalRequested("s", "a2", 2, tool = "fetch_web_content", input = """{"requests":[{"url":"https://x.dev","prompt":"go"}]}"""))
        runCurrent()
        // Both arrived before the controller saw either: one wave of two.
        assertEquals(listOf("a1", "a2"), h.controller.state.value.cards.map { it.approvalId })
        assertEquals(SheetVisibility.PartiallyExpanded, h.controller.state.value.sheet)
        h.controller.approve("a2")
        runCurrent()
        assertEquals(listOf("a1"), h.controller.state.value.cards.map { it.approvalId })
        assertEquals(SheetVisibility.PartiallyExpanded, h.controller.state.value.sheet)
    }

    @Test
    fun `a single first request opens expanded and a second one keeps the sheet`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
        h.controller.collapse()
        h.host.dispatch(approvalRequested("s", "a2", 2))
        runCurrent()
        // The banner count updates; the sheet re-opens only when the previous cards were answered.
        assertEquals(SheetVisibility.Collapsed, h.controller.state.value.sheet)
        assertEquals(2, h.controller.state.value.cards.size)
    }

    @Test
    fun `re-issued approvals are deduped by approvalId`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        h.host.dispatch(approvalRequested("s", "a1", 5))
        runCurrent()
        assertEquals(1, h.controller.state.value.cards.size)
    }

    @Test
    fun `approval_not_found removes the card with the already answered snackbar`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.onRespond = { throw HubCommandException(HubCommands.APPROVAL_RESPOND, HubErrorCodes.APPROVAL_NOT_FOUND, "Unknown approval") }
        h.controller.approve("a1")
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertTrue(ApprovalEffect.AlreadyAnswered in h.effects)
        assertEquals(listOf(FakePendingInputHost.Failure("approval.respond", "a1", HubErrorCodes.APPROVAL_NOT_FOUND)), h.host.failures)
    }

    @Test
    fun `a respond error keeps the card actionable with the reply message or the fallback`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.onRespond = { throw HubCommandException(HubCommands.APPROVAL_RESPOND, HubErrorCodes.COMMAND_FAILED, "Approval store is busy") }
        h.controller.approve("a1")
        runCurrent()
        var card = h.controller.state.value.cards.single()
        assertNull(card.action)
        assertEquals(CardError("Approval store is busy"), card.error)

        h.host.onRespond = { throw HubConnectionClosedException("socket closed") }
        h.controller.reject("a1")
        runCurrent()
        card = h.controller.state.value.cards.single()
        assertEquals(CardError(null), card.error)

        h.host.onRespond = { throw HubCommandException(HubCommands.APPROVAL_RESPOND, HubErrorCodes.HUB_CONNECTION_CLOSED, "connection closed") }
        h.controller.approve("a1")
        runCurrent()
        assertEquals(CardError(null), h.controller.state.value.cards.single().error)
        // Only ids and codes are recorded (CA §0.8).
        assertTrue(h.host.failures.all { it.id == "a1" })
    }

    @Test
    fun `resolved by another client removes the card silently`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.dispatch(approvalResolved("s", "a1", 2, approved = true))
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
        assertFalse(ApprovalEffect.AlreadyAnswered in h.effects)
    }

    @Test
    fun `toggleRaw flips only cards with a readable preview`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        h.host.dispatch(approvalRequested("s", "m1", 2, tool = "mcp_github", input = """{"x":1}"""))
        runCurrent()
        h.controller.toggleRaw("a1")
        h.controller.toggleRaw("m1")
        val cards = h.controller.state.value.cards.associateBy { it.approvalId }
        assertTrue(cards.getValue("a1").showRaw)
        assertFalse(cards.getValue("m1").showRaw)
        assertFalse(cards.getValue("m1").hasPreview)
        h.controller.toggleRaw("a1")
        assertFalse(h.controller.state.value.cards.first().showRaw)
    }

    @Test
    fun `reconnecting keeps the cards read-only, kernel down too`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.gate.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        runCurrent()
        assertTrue(h.controller.state.value.reconnecting)
        h.controller.approve("a1")
        h.controller.stop()
        runCurrent()
        assertTrue(h.host.decisions.isEmpty())
        assertTrue(h.host.aborts.isEmpty())
        assertEquals(1, h.controller.state.value.cards.size)

        h.host.gate.value = KernelGate.Down(KernelGate.DownVariant.Asleep, null)
        runCurrent()
        assertTrue(h.controller.state.value.kernelDown)
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)

        h.host.gate.value = KernelGate.Busy(KernelGate.BusyKind.Draining, null, slow = false)
        runCurrent()
        assertFalse(h.controller.state.value.readOnly)
    }

    @Test
    fun `same daemon after a reconnect keeps the cards, a new daemon drops them`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.gate.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        h.host.hubLifetime.value = null
        runCurrent()
        h.host.hubLifetime.value = "4242:2026-10-03T00:00:00.000Z"
        h.host.gate.value = KernelGate.Ready
        // The Hub re-issues the pending approval on subscribe.
        h.host.dispatch(approvalRequested("s", "a1", 9))
        runCurrent()
        assertEquals(1, h.controller.state.value.cards.size)

        h.host.hubLifetime.value = "5151:2026-10-03T01:00:00.000Z"
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
        assertFalse(h.controller.hasPending.value)

        // A request from the new daemon shows again.
        h.host.dispatch(approvalRequested("s", "a2", 10))
        runCurrent()
        assertEquals(listOf("a2"), h.controller.state.value.cards.map { it.approvalId })
    }

    @Test
    fun `a derived interruption clears the approvals through the reducer`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.updateFacts("s", SessionFactsReducer::markInterrupted)
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
    }

    @Test
    fun `not visible - banner plus snackbar, then the sheet opens when the chat is resumed`() = runTest {
        val h = harness("s", visible = false)
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertEquals(SheetVisibility.Collapsed, h.controller.state.value.sheet)
        assertEquals(listOf<ApprovalEffect>(ApprovalEffect.ArrivedWhileAway), h.effects)
        h.controller.setVisible(true)
        runCurrent()
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
        assertEquals(ApprovalEffect.Opened, h.effects.last())
    }

    @Test
    fun `a request already pending when the chat opens shows no snackbar and opens on resume`() = runTest {
        val host = FakePendingInputHost(clientId)
        host.dispatch(approvalRequested("s", "a1", 1))
        val h = harness("s", visible = false, host = host)
        assertEquals(SheetVisibility.Collapsed, h.controller.state.value.sheet)
        assertTrue(h.effects.isEmpty())
        h.controller.setVisible(true)
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
    }

    @Test
    fun `a dismissed banner is not reopened by resuming`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.controller.collapse()
        h.controller.setVisible(false)
        h.controller.setVisible(true)
        assertEquals(SheetVisibility.Collapsed, h.controller.state.value.sheet)
    }

    @Test
    fun `deep link opens the sheet when the request arrives within the grace window`() = runTest {
        val h = harness("s", visible = false)
        h.controller.openFromDeepLink()
        advanceTimeBy(1_000)
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
        advanceTimeBy(ApprovalController.DEEP_LINK_GRACE_MS)
        runCurrent()
        assertFalse(ApprovalEffect.AlreadyAnswered in h.effects)
        assertFalse(ApprovalEffect.ArrivedWhileAway in h.effects)
    }

    @Test
    fun `deep link to a request that is gone says it was already answered`() = runTest {
        val h = harness("s", visible = false)
        h.controller.openFromDeepLink()
        advanceTimeBy(ApprovalController.DEEP_LINK_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf<ApprovalEffect>(ApprovalEffect.AlreadyAnswered), h.effects)
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
    }

    @Test
    fun `deep link with the card present opens at once`() = runTest {
        val h = harness("s", visible = false)
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.controller.openFromDeepLink()
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
    }

    @Test
    fun `a failed abort and a stop without a terminal event both report StopFailed`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.onAbort = { Result.failure(HubCommandException(HubCommands.RUN_ABORT, HubErrorCodes.COMMAND_FAILED, "no run")) }
        h.controller.stop()
        runCurrent()
        assertFalse(h.controller.state.value.stopping)
        assertEquals(ApprovalEffect.StopFailed, h.effects.last())
        assertEquals(FakePendingInputHost.Failure("run.abort", "s", HubErrorCodes.COMMAND_FAILED), h.host.failures.last())

        h.effects.clear()
        h.host.onAbort = { Result.success(Unit) }
        h.controller.stop()
        runCurrent()
        assertTrue(h.controller.state.value.stopping)
        advanceTimeBy(ApprovalController.STOP_TIMEOUT_MS + 1)
        runCurrent()
        assertFalse(h.controller.state.value.stopping)
        assertEquals(listOf<ApprovalEffect>(ApprovalEffect.StopFailed), h.effects)
    }

    @Test
    fun `Stop waits for the Hub command timeout and its failure offers Restart kernel`() = runTest {
        // A4: the protocol's 30 s (HUB_DEFAULT_COMMAND_TIMEOUT_MS), not the app's former 10 s.
        assertEquals(30_000L, ApprovalController.STOP_TIMEOUT_MS)
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.effects.clear() // the sheet's Opened
        h.controller.stop()
        runCurrent()
        advanceTimeBy(29_000)
        runCurrent()
        assertTrue("no failure at 29 s", h.controller.state.value.stopping)
        assertFalse(ApprovalEffect.StopFailed in h.effects)
        advanceTimeBy(1_001)
        runCurrent()
        assertFalse(h.controller.state.value.stopping)
        assertEquals(listOf<ApprovalEffect>(ApprovalEffect.StopFailed), h.effects)
        assertEquals(KernelAction.RestartKernel, ApprovalEffect.StopFailed.action)
    }

    // ---- trial row 11: "The agent did not stop" ends with its run ------------------------------------

    private fun runAborted(sessionId: String, seq: Long) =
        HubEventEnvelope(event = HubEvents.RUN_ABORTED, sessionId = sessionId, sequence = seq, payload = buildJsonObject { put("reason", "aborted") })

    private val abortFailed: Result<Unit> = Result.failure(HubCommandException(HubCommands.RUN_ABORT, HubErrorCodes.COMMAND_FAILED, "no run"))

    @Test
    fun `a Stop failure stands while its run goes on and ends with the run`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.onAbort = { abortFailed }
        h.controller.stop()
        runCurrent()
        assertTrue(h.controller.state.value.stopFailed)
        val ended = backgroundScope.async { h.controller.awaitStopFailureEnd() }
        runCurrent()
        assertFalse(ended.isCompleted)
        // The same run asks again: it has not stopped.
        h.host.dispatch(approvalRequested("s", "a2", 2))
        runCurrent()
        assertTrue(h.controller.state.value.stopFailed)

        h.host.dispatch(runAborted("s", 3))
        runCurrent()
        assertFalse(h.controller.state.value.stopFailed)
        assertTrue("the snackbar goes with the run", ended.isCompleted)
    }

    @Test
    fun `a later Stop the Hub accepts ends the failure`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.onAbort = { abortFailed }
        h.controller.stop()
        runCurrent()
        assertTrue(h.controller.state.value.stopFailed)

        h.host.onAbort = { Result.success(Unit) }
        h.controller.stop()
        runCurrent()
        assertTrue(h.controller.state.value.stopping)
        assertFalse("a new attempt is under way", h.controller.state.value.stopFailed)
        advanceTimeBy(ApprovalController.STOP_TIMEOUT_MS + 1)
        runCurrent()
        assertTrue("that attempt failed too", h.controller.state.value.stopFailed)
    }

    @Test
    fun `a deleted session ends the Stop failure`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.onAbort = { abortFailed }
        h.controller.stop()
        runCurrent()
        assertTrue(h.controller.state.value.stopFailed)
        h.host.flow("s").value = h.host.flow("s").value.copy(seed = SeedState.Missing)
        runCurrent()
        assertFalse(h.controller.state.value.stopFailed)
    }

    @Test
    fun `only this session's approvals are shown`() = runTest {
        val h = harness("s")
        h.host.dispatch(approvalRequested("other", "a1", 1))
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertTrue(h.effects.isEmpty())
    }

    @Test
    fun `close stops following the session`() = runTest {
        val h = harness("s")
        h.controller.close()
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())
    }

    // ---- Auto-approve all (decided A1) ---------------------------------------------------------

    private fun FakePendingInputHost.runOf(sessionId: String, ours: Boolean) =
        updateFacts(sessionId) { it.copy(activeRun = RunRef(requestId = "r1", runId = "run1", startedAt = 0, clientId = if (ours) clientId else "tui", ours = ours)) }

    @Test
    fun `Auto-approve all answers a new request of our run without showing it`() = runTest {
        val h = harness("s")
        h.host.autoApproveAll.value = true
        h.host.runOf("s", ours = true)
        runCurrent()
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertEquals(listOf(FakePendingInputHost.Decision("a1", true, null)), h.host.decisions)
        assertTrue(h.controller.state.value.cards.isEmpty())
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
        assertTrue(h.effects.none { it == ApprovalEffect.Opened })
    }

    @Test
    fun `a request already on screen when Auto-approve all turns on stays for the user`() = runTest {
        val h = harness("s")
        h.host.runOf("s", ours = true)
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertEquals(1, h.controller.state.value.cards.size)

        h.host.autoApproveAll.value = true
        runCurrent()
        assertTrue(h.host.decisions.isEmpty())
        assertEquals(1, h.controller.state.value.cards.size)
    }

    @Test
    fun `a request pending when the chat opens is never auto-answered`() = runTest {
        val host = FakePendingInputHost(clientId)
        host.autoApproveAll.value = true
        host.runOf("s", ours = true)
        host.dispatch(approvalRequested("s", "a1", 1))
        val h = harness("s", host = host)
        assertTrue(h.host.decisions.isEmpty())
        assertEquals(1, h.controller.state.value.cards.size)
    }

    @Test
    fun `another client's run keeps its requests for the user`() = runTest {
        val h = harness("s")
        h.host.autoApproveAll.value = true
        h.host.runOf("s", ours = false)
        runCurrent()
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertTrue(h.host.decisions.isEmpty())
        assertEquals(1, h.controller.state.value.cards.size)
    }

    @Test
    fun `a failed auto answer shows the request after all, unless it is gone`() = runTest {
        val h = harness("s")
        h.host.autoApproveAll.value = true
        h.host.runOf("s", ours = true)
        runCurrent()
        h.host.onRespond = { throw HubCommandException("approval.respond", HubErrorCodes.APPROVAL_NOT_FOUND, "gone") }
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        assertTrue(h.controller.state.value.cards.isEmpty())

        h.host.onRespond = { throw HubConnectionClosedException("closed") }
        h.host.dispatch(approvalRequested("s", "a2", 2))
        runCurrent()
        assertEquals(listOf("a2"), h.controller.state.value.cards.map { it.approvalId })
    }

    @Test
    fun `Approve and auto-approve approves first, then turns the setting on`() = runTest {
        val h = harness("s")
        h.host.runOf("s", ours = true)
        h.host.dispatch(approvalRequested("s", "a1", 1))
        h.host.dispatch(approvalRequested("s", "a2", 2))
        runCurrent()
        val order = mutableListOf<String>()
        h.host.onRespond = { order += "respond" }
        h.host.onTurnOnAutoApprove = {
            order += "write"
            h.host.autoApproveAll.value = true
            dev.cline.termux.data.approvals.ApprovalWriteResult.Written
        }
        h.controller.approveAndAutoApprove("a1")
        runCurrent()
        assertEquals(listOf("respond", "write"), order)
        assertEquals(listOf(FakePendingInputHost.Decision("a1", true, null)), h.host.decisions)
        // The other card was already on screen: it stays.
        assertEquals(listOf("a2"), h.controller.state.value.cards.map { it.approvalId })
        assertTrue(h.effects.none { it is ApprovalEffect.AutoApproveFailed })
    }

    @Test
    fun `a failed switch after Approve and auto-approve keeps the approval and says why`() = runTest {
        val h = harness("s")
        h.host.runOf("s", ours = true)
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        val failure = dev.cline.termux.data.approvals.ApprovalWriteFailure.Unreadable
        h.host.onTurnOnAutoApprove = { dev.cline.termux.data.approvals.ApprovalWriteResult.Failed(failure) }
        h.controller.approveAndAutoApprove("a1")
        runCurrent()
        assertEquals(1, h.host.decisions.size)
        assertEquals(1, h.host.autoApproveWrites)
        assertTrue(ApprovalEffect.AutoApproveFailed(failure) in h.effects)
    }

    @Test
    fun `a rejected Approve and auto-approve never writes the setting`() = runTest {
        val h = harness("s")
        h.host.runOf("s", ours = true)
        h.host.dispatch(approvalRequested("s", "a1", 1))
        runCurrent()
        h.host.onRespond = { throw HubCommandException("approval.respond", "internal_error", "boom") }
        h.controller.approveAndAutoApprove("a1")
        runCurrent()
        assertEquals(0, h.host.autoApproveWrites)
    }
}
