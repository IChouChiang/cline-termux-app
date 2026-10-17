package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.REASON_CREDENTIALS
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.prefs.MessageDetails
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A11.9 / F6: the projector keeps every `session.notice`; [ChatProjector.forDetails] leaves the
 * recoverable-error notices out in Brief and shows them in Verbose. `api_error` stays an error
 * row with its credential fix in both; status, stop and compaction notices never change.
 *
 * The recovery frames here are synthetic: no kernel up to cli-v3.0.69 emits a `session.notice`
 * with `noticeType "recovery"` (agent notices are always "status", runtime-event-adapter.ts
 * 247-257; recoverable agent errors are dropped by the Hub, session-event-projector.ts 376-396).
 * The tests pin the filter for the protocol's declared shape until a Hub sends it.
 */
class MessageDetailsProjectionTest {
    private val sid = "s1"
    private var sequence = 0L

    private fun notice(noticeType: String, reason: String?, message: String): HubEventEnvelope = HubEventEnvelope(
        event = HubEvents.SESSION_NOTICE,
        sequence = ++sequence,
        sessionId = sid,
        timestamp = 1_000L + sequence,
        payload = buildJsonObject {
            put("sessionId", JsonPrimitive(sid))
            put("message", JsonPrimitive(message))
            put("noticeType", JsonPrimitive(noticeType))
            reason?.let { put("reason", JsonPrimitive(it)) }
        },
    )

    private fun project(vararg events: HubEventEnvelope): List<ChatMessage> {
        val ctx = ProjectContext(sessionId = sid, now = 5_000L, sessionProviderId = "deepseek")
        return events.fold(LiveProjection()) { state, event -> ChatProjector.project(state, event, ctx) }.messages
    }

    private val recoverableReasons = listOf("invalid_tool_call", "tool_execution_failed", "completion_without_submit", "mistake_limit")

    @Test
    fun `the four recoverable reasons are exactly the hidden set`() {
        assertEquals(recoverableReasons.toSet(), ChatProjector.RECOVERABLE_ERROR_REASONS)
    }

    @Test
    fun `each recoverable reason is projected as a recovery notice row`() {
        for (reason in recoverableReasons) {
            val rows = project(notice("recovery", reason, "Recovered from $reason"))
            val row = rows.single()
            assertEquals(Role.Status, row.role)
            assertEquals("recovery", row.meta.noticeType)
            assertEquals(reason, row.meta.reason)
            assertTrue(reason, ChatProjector.isRecoverableErrorNotice(row))
        }
    }

    @Test
    fun `brief hides each recoverable reason and verbose shows it`() {
        for (reason in recoverableReasons) {
            val rows = project(notice("recovery", reason, "Recovered from $reason"))
            assertTrue(reason, ChatProjector.forDetails(rows, MessageDetails.Brief).isEmpty())
            assertEquals(reason, rows, ChatProjector.forDetails(rows, MessageDetails.Verbose))
        }
    }

    @Test
    fun `api_error is an error row with the credential fix in both modes`() {
        val rows = project(notice("recovery", "api_error", "401 Unauthorized: invalid api key"))
        val row = rows.single()
        assertEquals(Role.Error, row.role)
        assertTrue(row.id.startsWith("x:"))
        assertEquals(REASON_CREDENTIALS, row.meta.reason)
        assertEquals(rows, ChatProjector.forDetails(rows, MessageDetails.Brief))
        assertEquals(rows, ChatProjector.forDetails(rows, MessageDetails.Verbose))
    }

    @Test
    fun `status, stop and compaction notices are unchanged in both modes`() {
        val rows = project(
            notice("status", null, "Session resumed from history"),
            notice("stop", "user", "Stopped by the user"),
            notice("recovery", "auto_compaction", "Compacted the conversation"),
            notice("status", "mistake_limit", "A status notice that only shares the reason"),
        )
        assertEquals(4, rows.size)
        assertTrue(rows.none(ChatProjector::isRecoverableErrorNotice))
        assertSame(rows, ChatProjector.forDetails(rows, MessageDetails.Brief))
        assertSame(rows, ChatProjector.forDetails(rows, MessageDetails.Verbose))
    }

    @Test
    fun `brief keeps the order of everything else around a hidden notice`() {
        val rows = project(
            notice("status", null, "first"),
            notice("recovery", "tool_execution_failed", "hidden"),
            notice("stop", null, "last"),
        )
        val brief = ChatProjector.forDetails(rows, MessageDetails.Brief)
        assertEquals(listOf("first", "last"), brief.map { it.content })
        assertFalse(brief.any { it.content == "hidden" })
        // Switching back re-projects the rows already received: nothing was dropped at projection time.
        assertEquals(listOf("first", "hidden", "last"), ChatProjector.forDetails(rows, MessageDetails.Verbose).map { it.content })
    }
}
