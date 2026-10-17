package dev.cline.termux.ui.queue

import app.cash.turbine.test
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.data.chat.SlashRefusal
import dev.cline.termux.data.chat.SlashResolution
import dev.cline.termux.data.session.QueuedPrompt
import dev.cline.termux.data.session.SessionState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptQueueControllerTest {

    private val sessionId = "sess_1"

    private class FakeQueueApi : PromptQueueApi {
        val calls = mutableListOf<String>()
        var onLoad: suspend () -> JsonObject = { reply() }
        var onSteerFirst: suspend () -> JsonObject = { reply() }
        var onUpdate: suspend (String, String?, String?) -> JsonObject = { _, _, _ -> reply() }
        var onRemove: suspend (String) -> JsonObject = { reply() }

        override suspend fun pendingPrompts(sessionId: String): JsonObject {
            calls += "load"
            return onLoad()
        }

        override suspend fun steerFirst(sessionId: String): JsonObject {
            calls += "steerFirst"
            return onSteerFirst()
        }

        override suspend fun update(sessionId: String, promptId: String, prompt: String?, delivery: String?): JsonObject {
            calls += "update $promptId ${prompt ?: "-"} ${delivery ?: "-"}"
            return onUpdate(promptId, prompt, delivery)
        }

        override suspend fun remove(sessionId: String, promptId: String): JsonObject {
            calls += "remove $promptId"
            return onRemove(promptId)
        }
    }

    private class Harness(
        val api: FakeQueueApi,
        val session: MutableStateFlow<SessionState>,
        val kernel: MutableStateFlow<KernelGate>,
        val controller: PromptQueueController,
    )

    private fun TestScope.harness(
        api: FakeQueueApi = FakeQueueApi(),
        resolve: suspend (String) -> SlashResolution = { SlashResolution.Send(it) },
    ): Harness {
        val session = MutableStateFlow(SessionState(sessionId))
        val kernel = MutableStateFlow<KernelGate>(KernelGate.Ready)
        val controller = PromptQueueController.Factory(api, FakeSource(session), kernel).create(sessionId, backgroundScope, resolve)
        return Harness(api, session, kernel, controller)
    }

    private class FakeSource(private val flow: MutableStateFlow<SessionState>) : dev.cline.termux.data.tools.SessionStateSource {
        override fun state(sessionId: String) = flow
        override suspend fun seed(sessionId: String) = Unit
    }

    private fun Harness.emitEvent(vararg prompts: QueuedPrompt) {
        session.value = session.value.copy(facts = session.value.facts.copy(pendingPrompts = ArrayList(prompts.toList()), lastSequence = session.value.facts.lastSequence + 1))
    }

    @Test fun `nothing shows until the first reply, then the list loads`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeQueueApi().apply { onLoad = { gate.await(); reply(p("a", "first")) } }
        val h = harness(api)
        runCurrent()
        assertFalse(h.controller.state.value.loaded)
        assertFalse(h.controller.state.value.stripVisible)
        gate.complete(Unit)
        runCurrent()
        val state = h.controller.state.value
        assertTrue(state.stripVisible)
        assertEquals(listOf("a"), state.items.map { it.id })
    }

    @Test fun `deep link openSheet before the first reply opens the sheet once a non-empty list loads`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeQueueApi().apply { onLoad = { gate.await(); reply(p("a", "first")) } }
        val h = harness(api)
        runCurrent()
        h.controller.openSheet()
        assertFalse(h.controller.state.value.sheetOpen)
        gate.complete(Unit)
        runCurrent()
        assertTrue(h.controller.state.value.sheetOpen)
        assertEquals(listOf("a"), h.controller.state.value.items.map { it.id })
    }

    @Test fun `deep link openSheet before an empty reply is dropped`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeQueueApi().apply { onLoad = { gate.await(); reply() } }
        val h = harness(api)
        runCurrent()
        h.controller.openSheet()
        gate.complete(Unit)
        runCurrent()
        assertTrue(h.controller.state.value.loaded)
        assertFalse(h.controller.state.value.sheetOpen)
        // A later prompt fills the strip without popping the sheet open.
        h.emitEvent(QueuedPrompt("b", "later", "queue", 0))
        runCurrent()
        assertTrue(h.controller.state.value.stripVisible)
        assertFalse(h.controller.state.value.sheetOpen)
    }

    @Test fun `cancelling an edit keeps the sheet open`() = runTest {
        val h = harness(FakeQueueApi().apply { onLoad = { reply(p("a", "one")) } })
        runCurrent()
        h.controller.openSheet()
        h.controller.startEdit("a")
        h.controller.cancelEdit()
        assertNull(h.controller.state.value.editingId)
        assertTrue(h.controller.state.value.sheetOpen)
    }

    @Test fun `display prompt folds the team envelope back to the slash form`() = runTest {
        val envelope = "<user_command slash=\"team\">spawn a team of agents for the following task: build a hello world script</user_command>"
        val api = FakeQueueApi().apply { onLoad = { reply(p("t", envelope, attachments = 1)) } }
        val h = harness(api)
        runCurrent()
        val item = h.controller.state.value.items.single()
        assertEquals("/team build a hello world script", item.displayPrompt)
        assertEquals(1, item.attachmentCount)
    }

    @Test fun `events replace the list, and a stale reply does not overwrite them`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeQueueApi().apply {
            onLoad = { reply(p("a", "one"), p("b", "two")) }
            onRemove = { gate.await(); reply(p("b", "two")) }
        }
        val h = harness(api)
        runCurrent()
        h.controller.openSheet()
        h.controller.remove("a")
        runCurrent()
        assertEquals("a", h.controller.state.value.pendingId)
        // While the remove is in flight an event arrives with a newer list.
        h.emitEvent(QueuedPrompt("c", "three", "queue", 0))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("c"), h.controller.state.value.items.map { it.id })
        assertNull(h.controller.state.value.pendingId)
    }

    @Test fun `mutation reply replaces the list when nothing newer arrived`() = runTest {
        val api = FakeQueueApi().apply {
            onLoad = { reply(p("a", "one"), p("b", "two")) }
            onUpdate = { id, _, delivery -> if (delivery == "steer") reply(p(id, "two", "steer"), p("a", "one")) else reply() }
        }
        val h = harness(api)
        runCurrent()
        h.controller.steer("b")
        runCurrent()
        assertEquals("update b - steer", h.api.calls.last())
        val items = h.controller.state.value.items
        assertEquals(listOf("b", "a"), items.map { it.id })
        assertTrue(items.first().steer)
        assertFalse(h.controller.state.value.canSteerFirst)
    }

    @Test fun `one action at a time`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val api = FakeQueueApi().apply {
            onLoad = { reply(p("a", "one"), p("b", "two")) }
            onRemove = { gate.await(); reply(p("b", "two")) }
        }
        val h = harness(api)
        runCurrent()
        h.controller.remove("a")
        runCurrent()
        assertFalse(h.controller.state.value.actionsEnabled)
        h.controller.remove("b")
        h.controller.steer("b")
        runCurrent()
        assertEquals(listOf("load", "remove a"), h.api.calls)
        gate.complete(Unit)
        runCurrent()
        assertTrue(h.controller.state.value.actionsEnabled)
    }

    @Test fun `row errors use kernel text and clear when the item leaves`() = runTest {
        val api = FakeQueueApi().apply {
            onLoad = { reply(p("a", "one")) }
            onRemove = { throw HubCommandException("session.remove_pending_prompt", "command_failed", "prompt is running") }
        }
        val h = harness(api)
        runCurrent()
        h.controller.remove("a")
        runCurrent()
        val error = h.controller.state.value.error!!
        assertEquals(QueueError("a", QueueAction.Remove, QueueFailure.Kernel("prompt is running")), error)
        h.emitEvent()
        runCurrent()
        assertNull(h.controller.state.value.error)
    }

    @Test fun `strip steer failure is a snackbar event`() = runTest {
        val api = FakeQueueApi().apply {
            onLoad = { reply(p("a", "one")) }
            onSteerFirst = { throw HubCommandException("session.steer_first_pending_prompt", HubErrorCodes.HUB_CONNECTION_CLOSED, "closed") }
        }
        val h = harness(api)
        runCurrent()
        h.controller.events.test {
            h.controller.steerFirst()
            runCurrent()
            assertEquals(QueueEvent.StripSteerFailed(QueueFailure.NotConnected), awaitItem())
        }
        assertNull(h.controller.state.value.error)
    }

    @Test fun `enter steer waits at most one second for queued sends`() = runTest {
        val api = FakeQueueApi().apply { onLoad = { reply(p("a", "one")) } }
        val h = harness(api)
        runCurrent()
        val never = CompletableDeferred<Unit>()
        h.controller.steerFirst { never.await() }
        advanceTimeBy(999)
        runCurrent()
        assertFalse("steerFirst" in h.api.calls)
        advanceTimeBy(2)
        runCurrent()
        assertTrue("steerFirst" in h.api.calls)
    }

    @Test fun `enter steer goes out as soon as queued sends are acknowledged`() = runTest {
        val api = FakeQueueApi().apply { onLoad = { reply(p("a", "one")) } }
        val h = harness(api)
        runCurrent()
        val ack = CompletableDeferred<Unit>()
        h.controller.steerFirst { ack.await() }
        runCurrent()
        ack.complete(Unit)
        runCurrent()
        assertTrue("steerFirst" in h.api.calls)
    }

    @Test fun `edit saves the re-resolved prompt and leaves edit mode`() = runTest {
        val api = FakeQueueApi().apply {
            onLoad = { reply(p("a", "one")) }
            onUpdate = { id, prompt, _ -> reply(p(id, prompt!!)) }
        }
        val h = harness(api, resolve = { SlashResolution.Send("resolved:$it") })
        runCurrent()
        h.controller.startEdit("a")
        assertEquals("one", h.controller.state.value.editingText)
        h.controller.onEditTextChange("  /team go  ")
        h.controller.saveEdit()
        runCurrent()
        assertEquals("update a resolved:/team go -", h.api.calls.last())
        assertNull(h.controller.state.value.editingId)
        assertEquals("resolved:/team go", h.controller.state.value.items.single().displayPrompt)
    }

    @Test fun `blank edits are not saved and refusals show under the row`() = runTest {
        val api = FakeQueueApi().apply { onLoad = { reply(p("a", "one")) } }
        val h = harness(api, resolve = { SlashResolution.Refused(SlashRefusal.TeamUsage) })
        runCurrent()
        h.controller.startEdit("a")
        h.controller.onEditTextChange("   ")
        h.controller.saveEdit()
        runCurrent()
        assertEquals(listOf("load"), h.api.calls)
        h.controller.onEditTextChange("/team")
        h.controller.saveEdit()
        runCurrent()
        assertEquals(QueueFailure.Refused(SlashRefusal.TeamUsage), h.controller.state.value.error!!.failure)
        assertEquals("a", h.controller.state.value.editingId)
    }

    @Test fun `an edit whose item was submitted is cancelled and an empty queue closes the sheet`() = runTest {
        val api = FakeQueueApi().apply { onLoad = { reply(p("a", "one")) } }
        val h = harness(api)
        runCurrent()
        h.controller.openSheet()
        h.controller.startEdit("a")
        h.emitEvent()
        runCurrent()
        val state = h.controller.state.value
        assertNull(state.editingId)
        assertFalse(state.sheetOpen)
        assertFalse(state.stripVisible)
    }

    @Test fun `queue unavailable hides the strip for the connection`() = runTest {
        val api = FakeQueueApi().apply {
            onLoad = { throw HubCommandException("session.pending_prompts", HubErrorCodes.PENDING_PROMPTS_UNAVAILABLE, "no queue") }
        }
        val h = harness(api)
        runCurrent()
        assertTrue(h.controller.state.value.unavailable)
        assertFalse(h.controller.state.value.stripVisible)
        // A new connection retries.
        api.onLoad = { reply(p("a", "one")) }
        h.kernel.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        runCurrent()
        h.kernel.value = KernelGate.Ready
        runCurrent()
        assertFalse(h.controller.state.value.unavailable)
        assertTrue(h.controller.state.value.stripVisible)
    }

    @Test fun `store flag marks the queue unavailable`() = runTest {
        val h = harness(FakeQueueApi().apply { onLoad = { reply(p("a", "one")) } })
        runCurrent()
        h.session.value = h.session.value.copy(facts = h.session.value.facts.copy(queueUnavailable = true))
        runCurrent()
        assertTrue(h.controller.state.value.unavailable)
    }

    @Test fun `reconnecting disables actions, down hides the strip and closes the sheet, reconnect reloads`() = runTest {
        val api = FakeQueueApi().apply { onLoad = { reply(p("a", "one")) } }
        val h = harness(api)
        runCurrent()
        h.controller.openSheet()
        h.kernel.value = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)
        runCurrent()
        assertEquals(QueueLink.Reconnecting, h.controller.state.value.link)
        assertFalse(h.controller.state.value.actionsEnabled)
        assertTrue(h.controller.state.value.sheetOpen)
        h.kernel.value = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
        runCurrent()
        assertFalse(h.controller.state.value.sheetOpen)
        assertFalse(h.controller.state.value.stripVisible)
        h.kernel.value = KernelGate.Ready
        settle()
        assertEquals(listOf("load", "load"), h.api.calls)
        assertTrue(h.controller.state.value.stripVisible)
    }

    @Test fun `reply parsing reads id, prompt, delivery and attachmentCount`() {
        val parsed = PromptQueueController.parsePrompts(JsonArray(listOf(p("x", "hi", "steer", 2), buildJsonObject { put("prompt", "no id") })))
        assertEquals(listOf(QueuedPrompt("x", "hi", "steer", 2)), parsed)
    }

    private companion object {
        fun p(id: String, prompt: String, delivery: String = "queue", attachments: Int = 0): JsonObject = buildJsonObject {
            put("id", id)
            put("prompt", prompt)
            put("delivery", delivery)
            put("attachmentCount", attachments)
        }

        fun reply(vararg prompts: JsonObject): JsonObject = buildJsonObject { put("prompts", JsonArray(prompts.toList())) }
    }

    /** Background-scope work is not counted by `advanceUntilIdle`; advance virtual time instead. */
    private fun TestScope.settle() {
        advanceTimeBy(5_000)
        runCurrent()
    }
}
