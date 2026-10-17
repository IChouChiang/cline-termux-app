package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.buildUserRunCountMap
import dev.cline.termux.data.chat.isSystemSteeringMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Brief §8.4: the act-mode continuation prompt is a synthetic user message (CLI
 * `isSyntheticUserText`): no live bubble, its history row hidden like a steering note and
 * counted as no run, while it still carries the turn's `act` mode for the composer.
 */
class ActModeContinuationTest {
    private val sessionId = "s1"
    private var sequence = 0L

    private fun event(name: String, payload: String) = HubEventEnvelope(
        event = name,
        sessionId = sessionId,
        sequence = ++sequence,
        timestamp = 1_000L + sequence,
        payload = HubJson.parseToJsonElement(payload) as JsonObject,
    )

    private fun project(state: LiveProjection, vararg events: HubEventEnvelope): LiveProjection =
        events.fold(state) { s, e -> ChatProjector.project(s, e, ProjectContext(sessionId = sessionId, now = 5_000L)) }

    @Test
    fun `the projector adds no bubble for the continuation prompt`() {
        val start = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req1", "make a plan", emptyList(), 900L)
        val after = ChatProjector.appendLocalUser(start, sessionId, "req2", ActModeSwitch.CONTINUATION_PROMPT, emptyList(), 950L)
        assertSame(start, after)
        assertEquals(listOf("make a plan"), after.messages.filter { it.role == Role.User }.map { it.content })
        // The same words typed by the user with an image are the user's own message.
        val image = ChatImage(id = "i1", mediaType = "image/png", data = "AAAA")
        val withImage = ChatProjector.appendLocalUser(start, sessionId, "req3", ActModeSwitch.CONTINUATION_PROMPT, listOf(image), 960L)
        assertEquals(2, withImage.messages.count { it.role == Role.User })
    }

    @Test
    fun `the continuation turn streams its answer without a user row`() {
        var s = ChatProjector.appendLocalUser(LiveProjection(), sessionId, "req1", "make a plan", emptyList(), 900L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req1","clientId":"android-test"}"""),
            event(HubEvents.ASSISTANT_DELTA, """{"text":"Plan ready."}"""),
            event(HubEvents.ASSISTANT_FINISHED, """{"text":"Plan ready."}"""),
            event(HubEvents.RUN_COMPLETED, """{"requestId":"req1","reason":"completed"}"""),
        )
        s = ChatProjector.appendLocalUser(s, sessionId, "req2", ActModeSwitch.CONTINUATION_PROMPT, emptyList(), 1_100L)
        s = project(
            s,
            event(HubEvents.RUN_STARTED, """{"requestId":"req2","clientId":"android-test"}"""),
            event(HubEvents.ASSISTANT_DELTA, """{"text":"Plan executed."}"""),
            event(HubEvents.ASSISTANT_FINISHED, """{"text":"Plan executed."}"""),
            event(HubEvents.RUN_COMPLETED, """{"requestId":"req2","reason":"completed"}"""),
        )
        assertEquals(listOf(Role.User, Role.Assistant, Role.Assistant), s.messages.map { it.role })
        assertEquals(listOf("make a plan", "Plan ready.", "Plan executed."), s.messages.map { it.content })
    }

    private val history = HubJson.parseToJsonElement(
        """
        [
          {"id":"u1","role":"user","ts":900,"content":[{"type":"text","text":"<user_input mode=\"plan\">make a plan</user_input>"}]},
          {"id":"m1","role":"assistant","ts":1003,"content":[{"type":"text","text":"Plan ready."},
            {"type":"tool_use","id":"call1","name":"switch_to_act_mode","input":{}}]},
          {"id":"u2","role":"user","ts":1009,"content":[{"type":"tool_result","tool_use_id":"call1","content":"You successfully switched to act mode."}]},
          {"id":"u3","role":"user","ts":1100,"content":[{"type":"text","text":"<user_input mode=\"act\">The user approved switching to act mode. Continue with the approved plan now.</user_input>"}]},
          {"id":"m2","role":"assistant","ts":1200,"content":[{"type":"text","text":"Plan executed."}]}
        ]
        """.trimIndent(),
    ) as JsonArray

    @Test
    fun `history hides the continuation row like a steering note and counts no run for it`() {
        val rows = HistoryMapper.map(sessionId, history).messages
        val continuation = rows.single { it.id == "u3" }
        assertEquals(0, continuation.meta.userRunSpan)
        assertEquals(ActModeSwitch.MESSAGE_KIND, continuation.meta.messageKind)
        assertTrue(isSystemSteeringMessage(continuation))
        val prompt = rows.single { it.id == "u1" }
        assertFalse(isSystemSteeringMessage(prompt))
        // Core's getUserRunSpan counts the continuation as no run: the plan prompt stays run 1.
        assertEquals(mapOf("u1" to 1), buildUserRunCountMap(rows).entries.associate { it.key.id to it.value })
        // The row keeps its `act` mode, so a reopened session's composer shows Act.
        assertEquals("act", UserInputFormat.parseUserInputMode(rows.last { it.role == Role.User && it.content.isNotEmpty() }.content))
    }

    @Test
    fun `a genuine user message is never mistaken for the continuation`() {
        val own = HubJson.parseToJsonElement(
            """[{"id":"u1","role":"user","ts":900,"content":[{"type":"text","text":"<user_input mode=\"act\">Continue with the approved plan now.</user_input>"}]}]""",
        ) as JsonArray
        val row = HistoryMapper.map(sessionId, own).messages.single()
        assertEquals(null, row.meta.messageKind)
        assertFalse(isSystemSteeringMessage(row))
    }
}
