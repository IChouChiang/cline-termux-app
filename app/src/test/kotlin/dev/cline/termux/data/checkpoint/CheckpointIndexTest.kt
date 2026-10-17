package dev.cline.termux.data.checkpoint

import dev.cline.termux.data.chat.CheckpointRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the desktop/kernel checkpoint-run tests, plus the index built on them. */
class CheckpointIndexTest {
    private fun j(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun user(id: String, text: String, metadata: String? = null) = RunMessage.userText(id, text, metadata?.let(::j))
    private fun assistant(id: String) = RunMessage(id, "assistant", null, null)
    private fun ref(run: Int, at: Long = run * 10L) = CheckpointRef("r$run", at, run)

    @Test fun `counts spans for prompts, compactions and synthetic messages`() {
        assertEquals(1, getUserRunSpan(user("a", "hello")))
        assertEquals(0, getUserRunSpan(assistant("b")))
        assertEquals(0, getUserRunSpan(user("c", "   ")))
        assertEquals(3, getUserRunSpan(user("d", "summary", """{"kind":"compaction","userRunSpan":3}""")))
        assertEquals(1, getUserRunSpan(user("e", "summary", """{"kind":"compaction_summary"}""")))
        assertEquals(0, getUserRunSpan(user("f", "notice", """{"kind":"recovery_notice"}""")))
        assertEquals(0, getUserRunSpan(user("g", "[TASK RESUMPTION] continue")))
        assertEquals(0, getUserRunSpan(user("h", "The user approved switching to act mode. Continue with the approved plan now.")))
        val image = RunMessage.fromJson(j("""{"id":"i","role":"user","content":[{"type":"image","data":"x"}]}"""))
        assertEquals(1, getUserRunSpan(image))
        val toolResult = RunMessage.fromJson(j("""{"id":"t","role":"user","content":[{"type":"tool_result"},{"type":"image"}]}"""))
        assertEquals(0, getUserRunSpan(toolResult))
    }

    @Test fun `reads history and finds the checkpoint at or before a run`() {
        val history = readSessionCheckpointHistory(
            j("""{"checkpoint":{"history":[{"ref":" r1 ","createdAt":10,"runCount":1,"kind":"stash"},{"ref":"","runCount":2},{"ref":"r3","createdAt":30,"runCount":3},{"ref":"r4","runCount":1.5}]}}"""),
        )
        assertEquals(listOf(CheckpointRef("r1", 10, 1, "stash"), CheckpointRef("r3", 30, 3)), history)
        // `Number(entry.createdAt ?? 0)`: numeric strings count, a missing or blank one is 0, text is not.
        val loose = readSessionCheckpointHistory(
            j("""{"checkpoint":{"history":[{"ref":"s1","createdAt":" 15 ","runCount":1},{"ref":"s2","runCount":2},{"ref":"s3","createdAt":"","runCount":3},{"ref":"s4","createdAt":"soon","runCount":4}]}}"""),
        )
        assertEquals(listOf(CheckpointRef("s1", 15, 1), CheckpointRef("s2", 0, 2), CheckpointRef("s3", 0, 3)), loose)
        assertEquals("r1", findCheckpointForRun(history, 2)?.ref)
        assertEquals("r3", findCheckpointForRun(history, 5)?.ref)
        assertNull(findCheckpointForRun(history, 0))
        val restored = createRestoredCheckpointMetadata(history, 2)!!
        assertEquals("r1", restored.latest.ref)
        assertEquals(1, restored.history.size)
        assertNull(createRestoredCheckpointMetadata(history, 0))
    }

    @Test fun `trims to and before runs with the kernel's errors`() {
        val messages = listOf(
            user("u1", "first"), assistant("a1"),
            user("c", "summary", """{"kind":"compaction","userRunSpan":2}"""), assistant("a2"),
            user("u4", "fourth"), assistant("a3"),
        )
        assertEquals(listOf("u1"), trimMessagesToCheckpoint(messages, 1).map { it.id })
        assertEquals(listOf("u1", "a1", "c", "a2", "u4"), trimMessagesToCheckpoint(messages, 4).map { it.id })
        assertEquals(listOf("u1", "a1", "c", "a2"), trimMessagesBeforeUserRun(messages, 4).map { it.id })
        assertEquals(
            "Run 2 is folded into a compacted message spanning runs 2-3",
            assertThrows(CheckpointRunException::class.java) { trimMessagesToCheckpoint(messages, 2) }.message,
        )
        assertEquals(
            "Cannot fork before run 3 because it is folded into a compacted message",
            assertThrows(CheckpointRunException::class.java) { trimMessagesBeforeUserRun(messages, 3) }.message,
        )
        assertEquals(
            "Could not find user message for run 9",
            assertThrows(CheckpointRunException::class.java) { trimMessagesToCheckpoint(messages, 9) }.message,
        )
        assertEquals(4, countUserRunMessages(messages))
    }

    @Test fun `index exposes exact checkpoints, edits and restorable rows`() {
        val messages = listOf(
            user("u1", "first  prompt"), assistant("a1"),
            user("c", "summary", """{"kind":"compaction","userRunSpan":2}"""),
            user("u4", "fourth"),
        )
        val index = CheckpointIndex.build(listOf(ref(4), ref(1)), messages)
        assertEquals(listOf(1, 3, 4), index.runs.map { it.runCount })
        val first = index.entryForMessage("u1")!!
        assertEquals("first prompt", first.preview)
        assertTrue(index.hasExactCheckpoint(first))
        assertTrue(index.canEdit(first))
        val folded = index.entryAt(2)!!
        assertFalse(index.hasExactCheckpoint(folded))
        assertFalse(index.canEdit(folded))
        assertNull(index.exactCheckpoint(folded))
        assertEquals(listOf("u1" to "r1", "u4" to "r4"), index.restorableMessages().map { it.entry.messageId to it.checkpoint.ref })
        assertEquals(40L, index.restorableMessages().last().time)
    }

    @Test fun `cache rebuilds only when the key changes`() {
        val cache = CheckpointIndexCache()
        var builds = 0
        val build = { builds++; CheckpointIndex(emptyList(), emptyList()) }
        val first = cache.get(5, 2, build)
        assertSame(first, cache.get(5, 2, build))
        cache.get(6, 2, build)
        assertEquals(2, builds)
    }

    @Test fun `previews never split a grapheme`() {
        assertEquals("short", truncatePreview("short"))
        val long = "a".repeat(70)
        assertEquals("${"a".repeat(59)}…", truncatePreview(long))
        assertEquals(60, truncatePreview(long).length)
        val emoji = "a".repeat(58) + "👍🏽" + "tail"
        assertEquals("${"a".repeat(58)}…", truncatePreview(emoji))
    }

    @Test fun `parses session message items`() {
        val message = RunMessage.fromJson(
            Json.parseToJsonElement("""[{"id":"m","role":"user","content":[{"type":"text","text":"hi"},{"type":"text","text":"there"}]}]""").jsonArray[0].jsonObject,
        )
        assertEquals("hi\nthere", displayText(message))
    }
}
