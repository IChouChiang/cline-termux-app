package dev.cline.termux.ui.question

import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCapabilities
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.session.MistakeLimitDecision
import dev.cline.termux.data.session.MistakeLimitPrompt
import dev.cline.termux.data.session.QuestionKind
import dev.cline.termux.data.session.SessionFactsReducer
import dev.cline.termux.fakes.FakePendingInputHost
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.HubFixtures.event
import dev.cline.termux.ui.approval.CardError
import dev.cline.termux.ui.approval.SheetVisibility
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * composer-approvals.md C4 controller rules. The fixtures hold no `capability.requested`
 * (docs/08 "Not covered"), so the request is synthesized in the documented shape
 * (`hub-client-contributions.ts`: positional `args = [question, options]`) and replayed into
 * fixture 03's session, whose recorded `run.abort` tail covers the Stop paths.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskQuestionControllerTest {
    private val clientId = "android-0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    private val f03 by lazy { HubFixtures.load("03-reject-and-abort.jsonl") }
    private val session2 = "1791064691937_rkppp"

    private class Harness(val host: FakePendingInputHost, val controller: AskQuestionController, val effects: MutableList<QuestionEffect>)

    private fun TestScope.harness(
        sessionId: String,
        visible: Boolean = true,
        host: FakePendingInputHost = FakePendingInputHost(clientId),
        multiple: Boolean = false,
    ): Harness {
        val controller = AskQuestionController(sessionId, host, backgroundScope, initiallyVisible = visible, isMultiple = { multiple })
        val effects = mutableListOf<QuestionEffect>()
        backgroundScope.launch { controller.effects.collect { effects += it } }
        runCurrent()
        return Harness(host, controller, effects)
    }

    private fun capabilityRequested(
        sessionId: String,
        requestId: String,
        /** Null splices the request between two recorded events whose sequences are adjacent. */
        seq: Long?,
        target: String = clientId,
        capability: String = HubCapabilities.ASK_QUESTION,
        question: String = "Which colour do you prefer?",
        options: List<String> = listOf("Red", "Green", "Blue"),
    ) = HubEventEnvelope(
        event = HubEvents.CAPABILITY_REQUESTED, sessionId = sessionId, sequence = seq, timestamp = 1_791_064_700_000 + (seq ?: 0),
        payload = buildJsonObject {
            put("requestId", requestId); put("sessionId", sessionId); put("targetClientId", target); put("capabilityName", capability)
            putJsonObject("payload") {
                put("executor", "askQuestion")
                putJsonArray("args") {
                    add(question)
                    add(kotlinx.serialization.json.buildJsonArray { options.forEach { add(it) } })
                }
                putJsonObject("context") { put("agentId", "agent_1"); put("iteration", 1) }
            }
        },
    )

    /** G2: `mistake_limit.decide` with its `payload.context` (core `ConsecutiveMistakeLimitContext`). */
    private fun mistakeRequested(sessionId: String, requestId: String, seq: Long, details: String) = HubEventEnvelope(
        event = HubEvents.CAPABILITY_REQUESTED, sessionId = sessionId, sequence = seq, timestamp = 1_791_064_700_000 + seq,
        payload = buildJsonObject {
            put("requestId", requestId); put("sessionId", sessionId); put("targetClientId", clientId)
            put("capabilityName", HubCapabilities.MISTAKE_LIMIT)
            putJsonObject("payload") {
                putJsonObject("context") {
                    put("iteration", 7); put("consecutiveMistakes", 6); put("maxConsecutiveMistakes", 6)
                    put("reason", "tool_error"); put("details", details)
                }
            }
        },
    )

    private fun capabilityResolved(sessionId: String, requestId: String, seq: Long, cancelled: Boolean) = HubEventEnvelope(
        event = HubEvents.CAPABILITY_RESOLVED, sessionId = sessionId, sequence = seq,
        payload = buildJsonObject { put("requestId", requestId); put("ok", !cancelled); if (cancelled) put("cancelled", true) },
    )

    private fun TestScope.ask(h: Harness, id: String = "q1", seq: Long = 1, options: List<String> = listOf("Red", "Green", "Blue")) {
        h.host.dispatch(capabilityRequested("s", id, seq, options = options))
        runCurrent()
    }

    @Test
    fun `optionLabel - desktop base 26 letters`() {
        assertEquals("A", optionLabel(0))
        assertEquals("B", optionLabel(1))
        assertEquals("Z", optionLabel(25))
        assertEquals("AA", optionLabel(26))
        assertEquals("AB", optionLabel(27))
        assertEquals("AZ", optionLabel(51))
        assertEquals("BA", optionLabel(52))
        assertEquals("ZZ", optionLabel(701))
        assertEquals("AAA", optionLabel(702))
    }

    @Test
    fun `joinAnswers joins with comma and space`() {
        assertEquals("Red, Blue", joinAnswers(linkedSetOf("Red", "Blue")))
        assertEquals("", joinAnswers(emptyList()))
    }

    @Test
    fun `a question for this client opens the sheet with deduped options`() = runTest {
        val h = harness("s")
        ask(h, options = listOf("Red", "Green", "Red", "Blue"))
        val s = h.controller.state.value
        val item = s.items.single()
        assertEquals("q1", item.requestId)
        assertEquals("Which colour do you prefer?", item.question)
        assertEquals(listOf("Red", "Green", "Blue"), item.options)
        assertFalse(item.multiple)
        assertEquals(SheetVisibility.Expanded, s.sheet)
        assertTrue(h.controller.hasPending.value)
        assertEquals(listOf<QuestionEffect>(QuestionEffect.Opened), h.effects)
    }

    @Test
    fun `questions for another client or another capability are ignored`() = runTest {
        val h = harness("s")
        h.host.dispatch(capabilityRequested("s", "q1", 1, target = "cli-tui"))
        h.host.dispatch(capabilityRequested("s", "q2", 2, capability = "checkpoint.create"))
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertFalse(h.controller.hasPending.value)
    }

    @Test
    fun `a mistake limit opens the sheet with the desktop's two options and the cut detail`() = runTest {
        val h = harness("s")
        h.host.dispatch(mistakeRequested("s", "m1", 1, details = "  " + "e".repeat(700)))
        runCurrent()
        val item = h.controller.state.value.items.single()
        assertEquals(QuestionKind.MistakeLimit, item.kind)
        assertEquals(listOf("Try a different approach", "Stop this run"), item.options)
        assertFalse(item.multiple)
        assertEquals("e".repeat(600) + "…", item.mistakeDetail)
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
        assertTrue(h.controller.hasPending.value)
    }

    @Test
    fun `a mistake limit answer is sent as the decision, never as a plain answer`() = runTest {
        val h = harness("s")
        h.host.dispatch(mistakeRequested("s", "m1", 1, details = "bad call"))
        runCurrent()
        h.controller.selectOption("m1", MistakeLimitPrompt.STOP_OPTION)
        runCurrent()
        assertTrue(h.host.answers.isEmpty())
        val stop = h.host.mistakeAnswers.single()
        assertEquals(FakePendingInputHost.MistakeAnswer("s", "m1", MistakeLimitDecision.Stop("stopped after mistake_limit_reached prompt")), stop)
        assertTrue(h.controller.state.value.items.isEmpty())

        h.host.dispatch(mistakeRequested("s", "m2", 2, details = "bad call"))
        runCurrent()
        h.controller.setCustomAnswer("m2", " try grep first ")
        h.controller.submit("m2")
        runCurrent()
        val custom = h.host.mistakeAnswers.last().decision as MistakeLimitDecision.Continue
        assertTrue(custom.guidance.startsWith("The run reached the limit for repeated mistakes or tool calls. Latest: bad call "))
        assertTrue(custom.guidance.endsWith(" User guidance: try grep first"))
    }

    @Test
    fun `single choice answers on tap with the option text, then closes`() = runTest {
        val h = harness("s")
        ask(h)
        val reply = CompletableDeferred<Boolean>()
        h.host.onAnswer = { reply.await() }
        h.controller.selectOption("q1", "Green")
        runCurrent()
        assertEquals("Green", h.controller.state.value.items.single().pendingAnswer)
        assertTrue(h.controller.state.value.items.single().sending)
        // Everything is disabled while sending.
        h.controller.selectOption("q1", "Blue")
        h.controller.setCustomAnswer("q1", "x")
        h.controller.submit("q1")
        runCurrent()
        assertEquals(listOf(FakePendingInputHost.Answer("q1", "Green")), h.host.answers)
        assertEquals("", h.controller.state.value.items.single().customAnswer)
        // The payload the host sends is the documented capability.respond.
        assertEquals(
            buildJsonObject { put("requestId", "q1"); put("ok", true); putJsonObject("payload") { put("result", "Green") } },
            HubPayloads.capabilityRespond("q1", ok = true, result = JsonPrimitive("Green")),
        )
        reply.complete(false)
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
        // The Hub's capability.resolved after our answer is silent.
        h.host.dispatch(capabilityResolved("s", "q1", 2, cancelled = false))
        runCurrent()
        assertFalse(QuestionEffect.Cancelled in h.effects)
        assertFalse(h.controller.hasPending.value)
    }

    @Test
    fun `the resolution arriving before the reply is not a cancellation`() = runTest {
        val h = harness("s")
        ask(h)
        val reply = CompletableDeferred<Boolean>()
        h.host.onAnswer = { reply.await() }
        h.controller.selectOption("q1", "Red")
        runCurrent()
        h.host.dispatch(capabilityResolved("s", "q1", 2, cancelled = false))
        runCurrent()
        reply.complete(false)
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertFalse(QuestionEffect.Cancelled in h.effects)
    }

    @Test
    fun `ignored true closes silently`() = runTest {
        val h = harness("s")
        ask(h)
        h.host.onAnswer = { true }
        h.controller.selectOption("q1", "Blue")
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertEquals(listOf<QuestionEffect>(QuestionEffect.Opened), h.effects)
    }

    @Test
    fun `custom answer - blank never submits, text is trimmed`() = runTest {
        val h = harness("s")
        ask(h)
        h.controller.setCustomAnswer("q1", "   ")
        assertFalse(h.controller.state.value.items.single().canSubmit)
        h.controller.submit("q1")
        runCurrent()
        assertTrue(h.host.answers.isEmpty())
        h.controller.setCustomAnswer("q1", "  Purple ")
        assertTrue(h.controller.state.value.items.single().canSubmit)
        h.controller.submit("q1")
        runCurrent()
        assertEquals(listOf(FakePendingInputHost.Answer("q1", "Purple")), h.host.answers)
    }

    @Test
    fun `multiple choice toggles in pick order and submits the joined labels`() = runTest {
        val h = harness("s", multiple = true)
        ask(h)
        assertTrue(h.controller.state.value.items.single().multiple)
        assertFalse(h.controller.state.value.items.single().canSubmit)
        h.controller.selectOption("q1", "Blue")
        h.controller.selectOption("q1", "Red")
        h.controller.selectOption("q1", "Green")
        h.controller.selectOption("q1", "Green")
        assertEquals(listOf("Blue", "Red"), h.controller.state.value.items.single().selected.toList())
        assertTrue(h.host.answers.isEmpty())
        h.controller.submit("q1")
        runCurrent()
        assertEquals(listOf(FakePendingInputHost.Answer("q1", "Blue, Red")), h.host.answers)
    }

    @Test
    fun `an error re-enables the controls with the reply message or the fallback`() = runTest {
        val h = harness("s")
        ask(h)
        h.host.onAnswer = { throw HubCommandException(HubCommands.CAPABILITY_RESPOND, HubErrorCodes.CAPABILITY_WRONG_CLIENT, "Capability belongs to another client") }
        h.controller.selectOption("q1", "Red")
        runCurrent()
        var item = h.controller.state.value.items.single()
        assertNull(item.pendingAnswer)
        assertEquals(CardError("Capability belongs to another client"), item.error)

        h.host.onAnswer = { throw HubConnectionClosedException("closed") }
        h.controller.selectOption("q1", "Red")
        runCurrent()
        item = h.controller.state.value.items.single()
        assertEquals(CardError(null), item.error)
        assertEquals(
            listOf(
                FakePendingInputHost.Failure("capability.respond", "q1", HubErrorCodes.CAPABILITY_WRONG_CLIENT),
                FakePendingInputHost.Failure("capability.respond", "q1", HubErrorCodes.HUB_CONNECTION_CLOSED),
            ),
            h.host.failures,
        )
    }

    @Test
    fun `a cancelled question closes with the cancelled snackbar`() = runTest {
        val h = harness("s")
        ask(h)
        h.host.dispatch(capabilityResolved("s", "q1", 2, cancelled = true))
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
        assertEquals(QuestionEffect.Cancelled, h.effects.last())
    }

    @Test
    fun `fixture 03 - the user's own Stop ends the run without the cancelled snackbar`() = runTest {
        val h = harness(session2)
        // Session 2 up to its third send, then a question, then the recorded abort tail.
        val abortNote = f03.indexOfFirst { it.raw["note"]?.toString()?.contains("calling run.abort") == true }
        f03.take(abortNote).mapNotNull { it.event }.forEach { h.host.dispatch(it) }
        runCurrent()
        h.host.dispatch(capabilityRequested(session2, "q1", null))
        runCurrent()
        assertEquals(1, h.controller.state.value.items.size)
        // The composer's Stop: SessionStore.abort sets Stopping before run.abort goes out.
        h.host.updateFacts(session2, SessionFactsReducer::onAbortRequested)
        runCurrent()
        f03.drop(abortNote).mapNotNull { it.event }.forEach { h.host.dispatch(it) }
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertFalse(QuestionEffect.Cancelled in h.effects)
        assertFalse(h.controller.hasPending.value)
    }

    @Test
    fun `fixture 03 - an abort from another client is reported as cancelled`() = runTest {
        val h = harness(session2)
        val abortNote = f03.indexOfFirst { it.raw["note"]?.toString()?.contains("calling run.abort") == true }
        f03.take(abortNote).mapNotNull { it.event }.forEach { h.host.dispatch(it) }
        h.host.dispatch(capabilityRequested(session2, "q1", null))
        runCurrent()
        f03.drop(abortNote).mapNotNull { it.event }.forEach { h.host.dispatch(it) }
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertEquals(QuestionEffect.Cancelled, h.effects.last())
    }

    @Test
    fun `reconnecting disables the controls, the replayed cancellation closes the sheet`() = runTest {
        val h = harness("s")
        ask(h)
        h.host.gate.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        runCurrent()
        assertTrue(h.controller.state.value.reconnecting)
        h.controller.selectOption("q1", "Red")
        runCurrent()
        assertTrue(h.host.answers.isEmpty())
        h.host.gate.value = KernelGate.Ready
        h.host.dispatch(capabilityResolved("s", "q1", 5, cancelled = true))
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertEquals(QuestionEffect.Cancelled, h.effects.last())
    }

    @Test
    fun `kernel down closes the sheet with the cancelled snackbar`() = runTest {
        val h = harness("s")
        ask(h)
        h.host.gate.value = KernelGate.Down(KernelGate.DownVariant.Asleep, null)
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertEquals(SheetVisibility.Hidden, h.controller.state.value.sheet)
        assertEquals(1, h.effects.count { it == QuestionEffect.Cancelled })
        h.host.gate.value = KernelGate.Ready
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
    }

    @Test
    fun `a new daemon cancels the question`() = runTest {
        val h = harness("s")
        ask(h)
        h.host.hubLifetime.value = "9999:2026-10-03T02:00:00.000Z"
        runCurrent()
        assertTrue(h.controller.state.value.items.isEmpty())
        assertEquals(QuestionEffect.Cancelled, h.effects.last())
    }

    @Test
    fun `collapse keeps the state and Answer reopens it`() = runTest {
        val h = harness("s")
        ask(h)
        h.controller.setCustomAnswer("q1", "cyan")
        h.controller.collapse()
        assertTrue(h.controller.state.value.bannerVisible)
        assertTrue(h.host.answers.isEmpty())
        h.controller.reopen()
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
        assertEquals("cyan", h.controller.state.value.items.single().customAnswer)
    }

    @Test
    fun `not visible - banner and snackbar, opens on resume`() = runTest {
        val h = harness("s", visible = false)
        ask(h)
        assertEquals(SheetVisibility.Collapsed, h.controller.state.value.sheet)
        assertEquals(listOf<QuestionEffect>(QuestionEffect.ArrivedWhileAway), h.effects)
        h.controller.setVisible(true)
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)
    }

    @Test
    fun `deep link waits for the question, else says it was already answered`() = runTest {
        val h = harness("s", visible = false)
        h.controller.openFromDeepLink()
        ask(h)
        assertEquals(SheetVisibility.Expanded, h.controller.state.value.sheet)

        val h2 = harness("t", visible = false)
        h2.controller.openFromDeepLink()
        advanceTimeBy(AskQuestionController.DEEP_LINK_GRACE_MS + 1)
        runCurrent()
        assertEquals(listOf<QuestionEffect>(QuestionEffect.AlreadyAnswered), h2.effects)
    }
}
