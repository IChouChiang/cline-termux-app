package dev.cline.termux.ui.chat.rows

import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.checkpoint.CheckpointIndex
import dev.cline.termux.data.checkpoint.RunMessage
import dev.cline.termux.data.session.HistoryMapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user row's Edit (and its checkpoint) on histories the kernel really stores after
 * compaction, end to end: `session.messages` → [HistoryMapper] → [userRunCountsById], checked
 * against the kernel's own run index ([CheckpointIndex], `getUserRunSpan`). Shapes: core
 * `buildSummaryMessage` (compaction-shared.ts, a user message with `kind: "compaction_summary"`,
 * `displayRole: "system"`, `userRunSpan`), basic compaction's head (basic-compaction.ts,
 * `kind: "compaction"`, `displayRole: "system"`) and its merged user turns (`userRunSpan` > 1,
 * no kind), and runtime reminders (`userRunSpan: 0`).
 */
class UserRunCountCompactionTest {
    private fun history(json: String): JsonArray = HubJson.parseToJsonElement(json) as JsonArray

    /** The user rows' run counts (Edit, checkpoints) equal the kernel's for the same messages. */
    private fun assertEditCountsMatchKernel(raw: JsonArray, runCounts: Map<String, Int>) {
        val kernel = CheckpointIndex.build(emptyList(), raw.map { RunMessage.fromJson(it as JsonObject) }).runs
            .filter { it.span == 1 }
            .associate { it.messageId!! to it.runCount }
        assertEquals(kernel.filterKeys { it in runCounts }, runCounts)
    }

    @Test
    fun `a compaction summary spanning three runs numbers the next prompt four`() {
        val raw = history(
            """[
            {"id":"c","role":"user","content":[{"type":"text","text":"Context summary:\n\nEarlier work."}],
             "metadata":{"kind":"compaction_summary","displayRole":"system","userRunSpan":3,"summary":"Earlier work."}},
            {"id":"a","role":"assistant","content":"continuing"},
            {"id":"u4","role":"user","content":"fourth"},{"id":"a4","role":"assistant","content":"four"},
            {"id":"u5","role":"user","content":"fifth"},{"id":"a5","role":"assistant","content":"five"},
            {"id":"u6","role":"user","content":"sixth"},{"id":"a6","role":"assistant","content":"six"},
            {"id":"u7","role":"user","content":"seventh"},{"id":"a7","role":"assistant","content":"seven"}
            ]""",
        )
        val rows = HistoryMapper.map("s1", raw).messages
        val summary = rows.first { it.id == "c" }
        assertEquals(Role.System, summary.role)
        assertEquals(3, summary.meta.userRunSpan)
        assertEquals(3, summary.meta.runCount)

        val runCounts = userRunCountsById(rows)
        assertEquals(mapOf("u4" to 4, "u5" to 5, "u6" to 6, "u7" to 7), runCounts)
        assertEditCountsMatchKernel(raw, runCounts)

        val model = buildTranscriptModel(rows, ChatStatus.Completed)
        assertEquals("Edit numbers the prompt as the kernel does", 4, model.factsOf("u4").runCount)
    }

    @Test
    fun `basic compaction's system head and merged turns count their spans`() {
        val raw = history(
            """[
            {"id":"h","role":"user","content":"first and second",
             "metadata":{"kind":"compaction","reason":"auto_compaction","displayRole":"system","messagesRemoved":6,"userRunSpan":2}},
            {"id":"ah","role":"assistant","content":"two"},
            {"id":"m","role":"user","content":"third and fourth","metadata":{"userRunSpan":2}},
            {"id":"am","role":"assistant","content":"four"},
            {"id":"u5","role":"user","content":"fifth"},{"id":"a5","role":"assistant","content":"five"},
            {"id":"u6","role":"user","content":"sixth"},{"id":"a6","role":"assistant","content":"six"}
            ]""",
        )
        val rows = HistoryMapper.map("s1", raw).messages
        assertEquals(Role.System, rows.first { it.id == "h" }.role)
        val runCounts = userRunCountsById(rows)
        assertEquals("merged turns have no single run to edit", mapOf("u5" to 5, "u6" to 6), runCounts)
        assertEditCountsMatchKernel(raw, runCounts)
    }

    @Test
    fun `a single-run system head is run one, so the next prompt is run two`() {
        val raw = history(
            """[
            {"id":"h","role":"user","content":"first","metadata":{"kind":"compaction","displayRole":"system","userRunSpan":1}},
            {"id":"ah","role":"assistant","content":"one"},
            {"id":"u2","role":"user","content":"second"},{"id":"a2","role":"assistant","content":"two"}
            ]""",
        )
        val rows = HistoryMapper.map("s1", raw).messages
        val runCounts = userRunCountsById(rows)
        assertEquals(mapOf("u2" to 2), runCounts)
        assertEditCountsMatchKernel(raw, runCounts)
    }

    @Test
    fun `reminders and continuation prompts are no runs, as the kernel counts them`() {
        val raw = history(
            """[
            {"id":"u1","role":"user","content":"first"},{"id":"a1","role":"assistant","content":"one"},
            {"id":"r","role":"user","content":"[SYSTEM] This run is not complete until you call one of these terminal completion tools: submit.",
             "metadata":{"kind":"completion_reminder","userRunSpan":0}},
            {"id":"ar","role":"assistant","content":"still one"},
            {"id":"t","role":"user","content":"[TASK RESUMPTION] Pick up where you left off."},
            {"id":"at","role":"assistant","content":"resumed"},
            {"id":"u2","role":"user","content":"second"},{"id":"a2","role":"assistant","content":"two"}
            ]""",
        )
        val rows = HistoryMapper.map("s1", raw).messages
        assertEquals(0, rows.first { it.id == "t" }.meta.userRunSpan)
        val runCounts = userRunCountsById(rows)
        assertEquals(mapOf("u1" to 1, "u2" to 2), runCounts)
        assertEditCountsMatchKernel(raw, runCounts)
    }

    @Test
    fun `a live turn after history continues from the kernel's count`() {
        val raw = history(
            """[
            {"id":"c","role":"user","content":"Context summary:\n\nx","metadata":{"kind":"compaction_summary","displayRole":"system","userRunSpan":3}},
            {"id":"a","role":"assistant","content":"ok"},
            {"id":"u4","role":"user","content":"fourth"},{"id":"a4","role":"assistant","content":"four"}
            ]""",
        )
        val history = HistoryMapper.map("s1", raw).messages
        val live = listOf(
            ChatMessage("live-u", "s1", Role.User, "fifth", createdAt = 10, meta = Meta(userRunSpan = 1)),
            ChatMessage("live-a", "s1", Role.Assistant, "five", createdAt = 11),
        )
        assertEquals(5, userRunCountsById(history + live)["live-u"])
    }
}
