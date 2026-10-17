package dev.cline.termux.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of `webview/components/views/chat/messages/group-messages.test.ts` (every desktop case). */
class ChatGroupingTest {

    private fun makeMessage(
        id: String,
        role: Role = Role.Assistant,
        content: String = "",
        createdAt: Long = 1,
        reasoning: String? = null,
        reasoningRedacted: Boolean = false,
        images: List<ChatImage> = emptyList(),
        meta: Meta = Meta(),
    ) = ChatMessage(
        id = id,
        sessionId = "session-1",
        role = role,
        content = content,
        reasoning = reasoning,
        reasoningRedacted = reasoningRedacted,
        images = images,
        createdAt = createdAt,
        meta = meta,
    )

    private fun makeTool(id: String, createdAt: Long) =
        makeMessage(id, role = Role.Tool, content = """{"toolName":"search","input":{},"result":{}}""", createdAt = createdAt)

    private fun makeSubmitTool(id: String, createdAt: Long) = makeMessage(
        id,
        role = Role.Tool,
        content = """{"toolName":"submit_and_exit","input":{"summary":"Report ready."},"result":"Report ready."}""",
        createdAt = createdAt,
    )

    private fun collapse(messages: List<ChatMessage>, collapseTrailingRun: Boolean) =
        collapseCompletedWork(groupChatMessages(messages), collapseTrailingRun)

    private fun user(id: String, content: String, createdAt: Long) = makeMessage(id, role = Role.User, content = content, createdAt = createdAt)

    // ---- groupChatMessages ----

    @Test
    fun `folds a reasoning-only assistant message into the next assistant message`() {
        val reasoningOnly = makeMessage("reasoning", reasoning = "thinking hard")
        val answer = makeMessage("answer", content = "Here you go.")
        val items = groupChatMessages(listOf(reasoningOnly, answer))
        assertEquals(1, items.size)
        assertEquals(ChatRenderItem.Message(AgentMessageRole.Assistant, answer, listOf(reasoningOnly)), items[0])
    }

    @Test
    fun `keeps reasoning attached to an assistant message that carries its own reasoning`() {
        val reasoningOnly = makeMessage("r1", reasoning = "step one")
        val answer = makeMessage("answer", content = "Done.", reasoning = "step two")
        val items = groupChatMessages(listOf(reasoningOnly, answer))
        assertEquals(1, items.size)
        assertEquals(listOf(reasoningOnly, answer), (items[0] as ChatRenderItem.Message).reasoningMessages)
    }

    @Test
    fun `flushes trailing reasoning-only messages as a standalone assistant item`() {
        val user = makeMessage("user", role = Role.User, content = "Hi")
        val reasoningOnly = makeMessage("reasoning", reasoning = "pondering")
        val items = groupChatMessages(listOf(user, reasoningOnly))
        assertEquals(2, items.size)
        assertEquals(ChatRenderItem.Message(AgentMessageRole.Assistant, reasoningOnly, listOf(reasoningOnly)), items[1])
    }

    @Test
    fun `treats a redacted-reasoning assistant message without content as reasoning-only`() {
        val redacted = makeMessage("redacted", reasoningRedacted = true)
        val answer = makeMessage("answer", content = "Answer.")
        val items = groupChatMessages(listOf(redacted, answer))
        assertEquals(1, items.size)
        assertEquals(listOf(redacted), (items[0] as ChatRenderItem.Message).reasoningMessages)
    }

    @Test
    fun `collapses consecutive tool messages into one tools item`() {
        val toolA = makeMessage("tool-a", role = Role.Tool, content = "{}")
        val toolB = makeMessage("tool-b", role = Role.Tool, content = "{}")
        val answer = makeMessage("answer", content = "Done.")
        val toolC = makeMessage("tool-c", role = Role.Tool, content = "{}")
        val items = groupChatMessages(listOf(toolA, toolB, answer, toolC))
        assertEquals(3, items.size)
        assertEquals(ChatRenderItem.Tools(listOf(toolA, toolB)), items[0])
        assertEquals(ChatRenderItem.Tools(listOf(toolC)), items[2])
    }

    @Test
    fun `does not merge tool runs across an interleaved message`() {
        val toolA = makeMessage("tool-a", role = Role.Tool, content = "{}")
        val status = makeMessage("status", role = Role.Status, content = "working")
        val toolB = makeMessage("tool-b", role = Role.Tool, content = "{}")
        val items = groupChatMessages(listOf(toolA, status, toolB))
        assertEquals(3, items.size)
        assertEquals(ChatRenderItem.Tools(listOf(toolA)), items[0])
        assertEquals(ChatRenderItem.Tools(listOf(toolB)), items[2])
    }

    // ---- collapseCompletedWork ----

    @Test
    fun `collapses a finished run's tool calls behind its answer`() {
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), makeTool("t2", 3_000), makeMessage("a1", content = "Done.", createdAt = 5_000)), true)
        assertEquals(listOf("message", "work", "message"), items.map { it.type })
        val work = items[1] as ChatRenderItem.Work
        assertEquals(2, work.toolCallCount)
        assertEquals(4_000L, work.durationMilliseconds)
        assertEquals("t1", work.id)
        assertEquals(listOf("tools"), work.items.map { it.type })
    }

    @Test
    fun `groups a live run's working rows into a tight run item`() {
        val items = collapse(
            listOf(
                user("u1", "go", 1_000),
                makeMessage("r1", reasoning = "planning", createdAt = 2_000),
                makeTool("t1", 3_000),
                makeTool("t2", 4_000),
                makeMessage("a1", content = "Done.", createdAt = 5_000),
            ),
            false,
        )
        assertEquals(listOf("message", "run", "message"), items.map { it.type })
        val run = items[1] as ChatRenderItem.Run
        assertEquals("r1", run.id)
        assertEquals(listOf("message", "tools"), run.items.map { it.type })
    }

    @Test
    fun `keeps the live trailing run expanded`() {
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), makeMessage("a1", content = "Done.", createdAt = 3_000)), false)
        assertEquals(listOf("message", "tools", "message"), items.map { it.type })
    }

    @Test
    fun `keeps a trailing run without an answer expanded even when idle`() {
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 2_000)), true)
        assertEquals(listOf("message", "tools"), items.map { it.type })
    }

    @Test
    fun `collapses answerless runs once a later user message exists`() {
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), user("u2", "next", 9_000)), false)
        assertEquals(listOf("message", "work", "message"), items.map { it.type })
    }

    @Test
    fun `leaves runs without tool calls untouched`() {
        val items = collapse(
            listOf(user("u1", "hi", 1_000), makeMessage("r1", reasoning = "hmm", createdAt = 2_000), makeMessage("a1", content = "Hello!", createdAt = 3_000)),
            true,
        )
        assertEquals(listOf("message", "message"), items.map { it.type })
    }

    @Test
    fun `folds thinking traces and narration into the work item`() {
        val items = collapse(
            listOf(
                user("u1", "go", 1_000),
                makeMessage("r1", reasoning = "planning", createdAt = 2_000),
                makeTool("t1", 3_000),
                makeMessage("n1", content = "Halfway there.", createdAt = 4_000),
                makeTool("t2", 5_000),
                makeMessage("a1", content = "Done.", createdAt = 6_000),
            ),
            true,
        )
        assertEquals(listOf("message", "work", "message"), items.map { it.type })
        val work = items[1] as ChatRenderItem.Work
        assertEquals(2, work.toolCallCount)
        assertEquals(listOf("message", "tools", "message", "tools"), work.items.map { it.type })
    }

    @Test
    fun `keeps assistant messages with attachments out of the collapse`() {
        val items = collapse(
            listOf(
                user("u1", "go", 1_000),
                makeTool("t1", 2_000),
                makeMessage("img", images = listOf(ChatImage("i1", "image/png", "aGk=")), createdAt = 3_000),
                makeTool("t2", 4_000),
                makeMessage("a1", content = "Done.", createdAt = 5_000),
                user("u2", "next", 9_000),
            ),
            false,
        )
        assertEquals(listOf("message", "work", "message", "work", "message", "message"), items.map { it.type })
    }

    @Test
    fun `counts tool execution time when pre-tool thinking attaches to the answer`() {
        val items = collapse(
            listOf(
                user("u1", "run it", 1_000),
                makeTool("t1", 6_000),
                makeMessage("r1", reasoning = "planning the command", createdAt = 6_001),
                makeMessage("a1", content = "Done.", createdAt = 14_500),
            ),
            true,
        )
        val work = items.first { it is ChatRenderItem.Work } as ChatRenderItem.Work
        assertEquals(1, work.toolCallCount)
        assertEquals(13_500L, work.durationMilliseconds)
    }

    @Test
    fun `clamps the duration to the collapsed rows when the answer's timestamp is earlier`() {
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 5_000), makeMessage("a1", content = "Done.", createdAt = 1_001)), true)
        val work = items.first { it is ChatRenderItem.Work } as ChatRenderItem.Work
        assertEquals(4_000L, work.durationMilliseconds)
    }

    @Test
    fun `keeps a trailing submit_and_exit row visible as the collapsed run's answer`() {
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), makeSubmitTool("submit", 5_000)), true)
        assertEquals(listOf("message", "work", "tools"), items.map { it.type })
        val work = items[1] as ChatRenderItem.Work
        assertEquals(1, work.toolCallCount)
        assertEquals(4_000L, work.durationMilliseconds)
        assertEquals(listOf("submit"), (items[2] as ChatRenderItem.Tools).messages.map { it.id })
    }

    @Test
    fun `keeps the submit_and_exit row visible once a later user message exists`() {
        val items = collapse(
            listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), makeSubmitTool("submit", 5_000), user("u2", "thanks, one more thing", 9_000)),
            false,
        )
        assertEquals(listOf("message", "work", "tools", "message"), items.map { it.type })
        assertEquals(listOf("submit"), (items[2] as ChatRenderItem.Tools).messages.map { it.id })
    }

    @Test
    fun `keeps a live run's trailing submit_and_exit with its working rows`() {
        val items = collapse(
            listOf(user("u1", "go", 1_000), makeMessage("r1", reasoning = "wrapping up", createdAt = 1_500), makeTool("t1", 2_000), makeSubmitTool("submit", 3_000)),
            false,
        )
        assertEquals(listOf("message", "run"), items.map { it.type })
        val tools = (items[1] as ChatRenderItem.Run).items.last() as ChatRenderItem.Tools
        assertEquals(listOf("t1", "submit"), tools.messages.map { it.id })
    }

    @Test
    fun `treats a mid-run submit_and_exit as ordinary work`() {
        val items = collapse(
            listOf(user("u1", "go", 1_000), makeSubmitTool("submit", 2_000), makeTool("t1", 3_000), makeMessage("a1", content = "Done.", createdAt = 5_000)),
            true,
        )
        assertEquals(listOf("message", "work", "message"), items.map { it.type })
        assertEquals(2, (items[1] as ChatRenderItem.Work).toolCallCount)
    }

    @Test
    fun `prefers trailing assistant text over an earlier submit as the answer`() {
        val items = collapse(
            listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), makeSubmitTool("submit", 3_000), makeMessage("a1", content = "All wrapped up.", createdAt = 4_000)),
            true,
        )
        assertEquals(listOf("message", "work", "message"), items.map { it.type })
        assertEquals(2, (items[1] as ChatRenderItem.Work).toolCallCount)
        assertEquals("a1", (items[2] as ChatRenderItem.Message).message.id)
    }

    @Test
    fun `detects submit_and_exit from message meta when the content is not JSON`() {
        val items = collapse(
            listOf(
                user("u1", "go", 1_000),
                makeTool("t1", 2_000),
                makeMessage("submit-meta", role = Role.Tool, content = "not-json", meta = Meta(toolName = "submit_and_exit"), createdAt = 3_000),
            ),
            true,
        )
        assertEquals(listOf("message", "work", "tools"), items.map { it.type })
    }

    @Test
    fun `does not treat other trailing tool calls as the run's answer`() {
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), makeTool("t2", 3_000)), true)
        assertEquals(listOf("message", "tools"), items.map { it.type })
    }

    @Test
    fun `measures duration from the first working row when no user message precedes it`() {
        val items = collapse(listOf(makeTool("t1", 2_000), makeMessage("a1", content = "Done.", createdAt = 9_000)), true)
        assertEquals(7_000L, (items[0] as ChatRenderItem.Work).durationMilliseconds)
    }

    // ---- buildPreviousTimestampMap ----

    @Test
    fun `maps each message to the timestamp of the previous datable message`() {
        val first = makeMessage("first", createdAt = 100)
        val undated = makeMessage("undated", createdAt = UNDATED)
        val last = makeMessage("last", createdAt = 300)
        val map = buildPreviousTimestampMap(listOf(first, undated, last))
        assertNull(map[first])
        assertEquals(100L, map[undated])
        // The undated message must not overwrite the running timestamp.
        assertEquals(100L, map[last])
    }

    // ---- buildUserRunCountMap ----

    @Test
    fun `increments the run count for each user message`() {
        val userA = makeMessage("user-a", role = Role.User, content = "one")
        val reply = makeMessage("reply", content = "ok")
        val userB = makeMessage("user-b", role = Role.User, content = "two")
        val map = buildUserRunCountMap(listOf(userA, reply, userB))
        assertEquals(1, map[userA])
        assertEquals(2, map[userB])
    }

    @Test
    fun `prefers stored run counts from message meta`() {
        val restored = makeMessage("restored", role = Role.User, content = "restored", meta = Meta(runCount = 5))
        val next = makeMessage("next", role = Role.User, content = "next")
        val map = buildUserRunCountMap(listOf(restored, next))
        assertEquals(5, map[restored])
        assertEquals(6, map[next])
    }

    @Test
    fun `skips user messages whose runSpan is zero and reads checkpoint run counts`() {
        val spanless = makeMessage("spanless", role = Role.User, content = "queued", meta = Meta(userRunSpan = 0))
        val checkpointed = makeMessage(
            "checkpointed",
            role = Role.User,
            content = "checkpointed",
            meta = Meta(checkpoint = CheckpointRef(ref = "abc", createdAt = 1, runCount = 3, kind = "auto")),
        )
        val map = buildUserRunCountMap(listOf(spanless, checkpointed))
        assertFalse(map.containsKey(spanless))
        assertEquals(3, map[checkpointed])
    }

    // ---- getThoughtDurationMilliseconds ----

    @Test
    fun `returns the delta between the previous message and the thinking message`() {
        assertEquals(3_000L, getThoughtDurationMilliseconds(1_000, 4_000))
    }

    @Test
    fun `returns null for missing, undated, or out-of-order timestamps`() {
        assertNull(getThoughtDurationMilliseconds(null, 4_000))
        assertNull(getThoughtDurationMilliseconds(UNDATED, 4_000))
        assertNull(getThoughtDurationMilliseconds(1_000, UNDATED))
        assertNull(getThoughtDurationMilliseconds(4_000, 1_000))
    }

    // ---- isSystemSteeringMessage (Android: history marks unwrapped [SYSTEM] notes with userRunSpan 0) ----

    @Test
    fun `a steering note needs userRunSpan 0, a genuine prompt starting with SYSTEM stays a user turn`() {
        val steering = makeMessage("s", role = Role.User, content = "[SYSTEM] call attempt_completion", meta = Meta(userRunSpan = 0))
        val genuine = makeMessage("g", role = Role.User, content = "[SYSTEM] is my project name")
        assertTrue(isSystemSteeringMessage(steering))
        assertFalse(isSystemSteeringMessage(genuine))
        val items = collapse(listOf(user("u1", "go", 1_000), makeTool("t1", 2_000), steering.copy(createdAt = 2_500), makeMessage("a1", content = "Done.", createdAt = 3_000)), true)
        assertEquals(listOf("message", "work", "message"), items.map { it.type })
    }

    @Test
    fun `work and run ids follow the first row's renderKey`() {
        val tool = makeTool("t:1", 2_000).copy(renderKey = "live-key")
        val items = collapse(listOf(user("u1", "go", 1_000), tool, makeMessage("a1", content = "Done.", createdAt = 3_000)), true)
        assertEquals("live-key", (items[1] as ChatRenderItem.Work).id)
    }
}
