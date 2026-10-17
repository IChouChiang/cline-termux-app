package dev.cline.termux.data.agents

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from apps/examples/desktop-app/webview/lib/session-agents.test.ts (every case). */
class SessionAgentsTest {
    private var counter = 0

    /** [input] and [result] are JSON texts (null = JSON null), as the desktop helper serializes them. */
    private fun toolMessage(
        toolName: String,
        hookEventName: String? = null,
        input: String? = null,
        result: String? = null,
        isError: Boolean = false,
        id: String? = null,
    ): ChatMessage {
        counter += 1
        return ChatMessage(
            id = id ?: "tool_$counter",
            sessionId = "session_1",
            role = Role.Tool,
            content = """{"toolName":"$toolName","input":${input ?: "null"},"result":${result ?: "null"},"isError":$isError}""",
            createdAt = counter.toLong(),
            meta = Meta(toolName = toolName, hookEventName = hookEventName),
        )
    }

    private fun textMessage(role: Role, content: String): ChatMessage {
        counter += 1
        return ChatMessage(id = "msg_$counter", sessionId = "session_1", role = role, content = content, createdAt = counter.toLong())
    }

    private fun entry(agentId: String, status: String = "completed") = SessionAgentEntry(
        sessionId = "root__$agentId",
        agentId = agentId,
        kind = SessionAgentEntry.Kind.Subagent,
        status = status,
        startedAt = "2026-07-27T00:00:00.000Z",
        hasMessages = true,
    )

    @Test fun `recognizes subagent and teammate run tools`() {
        assertTrue(isAgentRunToolName("spawn_agent"))
        assertTrue(isAgentRunToolName("spawn-agent"))
        assertTrue(isAgentRunToolName("Spawn_Agent"))
        assertTrue(isAgentRunToolName("subagent_code_reviewer"))
        assertTrue(isAgentRunToolName("team_run_task"))
    }

    @Test fun `ignores tools that do not start an agent`() {
        assertFalse(isAgentRunToolName("read_files"))
        assertFalse(isAgentRunToolName("team_status"))
        assertFalse(isAgentRunToolName("team_spawn_teammate"))
        assertFalse(isAgentRunToolName(null))
    }

    @Test fun `returns empty activity when nothing spawned an agent`() {
        val activity = buildSessionAgentActivity(
            listOf(
                textMessage(Role.User, "hello"),
                textMessage(Role.Assistant, "hi"),
                toolMessage("read_files", "tool_call_end", result = "\"ok\""),
            ),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity.EMPTY, activity)
    }

    @Test fun `counts an in-flight subagent as running`() {
        val activity = buildSessionAgentActivity(listOf(toolMessage("spawn_agent", "tool_call_start")), sessionActive = true)
        assertEquals(SessionAgentActivity(total = 1, running = 1), activity)
    }

    @Test fun `counts a finished subagent as completed`() {
        val activity = buildSessionAgentActivity(
            listOf(toolMessage("spawn_agent", "tool_call_end", result = """{"text":"done","iterations":3,"finishReason":"completed"}""")),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 1, completed = 1), activity)
    }

    @Test fun `counts an errored subagent as failed`() {
        val activity = buildSessionAgentActivity(
            listOf(toolMessage("spawn_agent", "tool_call_end", result = "\"sub-agent exploded\"", isError = true)),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 1, failed = 1), activity)
    }

    @Test fun `treats an aborted subagent run as cancelled`() {
        val activity = buildSessionAgentActivity(
            listOf(toolMessage("spawn_agent", "tool_call_end", result = """{"text":"","iterations":1,"finishReason":"aborted"}""")),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 1, cancelled = 1), activity)
    }

    @Test fun `mixes buckets across several subagents`() {
        val activity = buildSessionAgentActivity(
            listOf(
                toolMessage("spawn_agent", "tool_call_end", result = """{"finishReason":"completed"}"""),
                toolMessage("subagent_reviewer", "tool_call_end", result = """{"finishReason":"completed"}"""),
                toolMessage("spawn_agent", "tool_call_end", result = "\"nope\"", isError = true),
                toolMessage("spawn_agent", "tool_call_start"),
            ),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 4, running = 1, completed = 2, failed = 1), activity)
    }

    @Test fun `reports unfinished runs as unresolved once the session is idle`() {
        val messages = listOf(toolMessage("spawn_agent", "history_tool_use"))
        assertEquals(SessionAgentActivity(total = 1, running = 1), buildSessionAgentActivity(messages, sessionActive = true))
        assertEquals(SessionAgentActivity(total = 1, unresolved = 1), buildSessionAgentActivity(messages, sessionActive = false))
    }

    @Test fun `reads a hydrated result that arrives as a JSON string`() {
        val activity = buildSessionAgentActivity(
            listOf(toolMessage("spawn_agent", "history_tool_result", result = "\"{\\\"text\\\":\\\"ok\\\",\\\"finishReason\\\":\\\"aborted\\\"}\"")),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 1, cancelled = 1), activity)
    }

    @Test fun `recovers the tool name from the payload when meta omits it`() {
        val base = toolMessage("spawn_agent", "tool_call_end", result = """{"finishReason":"completed"}""")
        val activity = buildSessionAgentActivity(listOf(base.copy(meta = Meta(hookEventName = "tool_call_end"))), sessionActive = true)
        assertEquals(SessionAgentActivity(total = 1, completed = 1), activity)
    }

    @Test fun `counts a sync teammate run as completed when its call returns`() {
        val activity = buildSessionAgentActivity(
            listOf(
                toolMessage(
                    "team_run_task",
                    "tool_call_end",
                    input = """{"agentId":"reviewer","runMode":"sync"}""",
                    result = """{"agentId":"reviewer","mode":"sync","status":"running","dispatched":true,"text":"reviewed"}""",
                ),
            ),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 1, completed = 1), activity)
    }

    @Test fun `keeps an async teammate run running until a later report resolves it`() {
        val dispatch = toolMessage(
            "team_run_task",
            "tool_call_end",
            input = """{"agentId":"reviewer","runMode":"async"}""",
            result = """{"agentId":"reviewer","mode":"async","status":"queued","dispatched":true,"runId":"run_1"}""",
        )
        assertEquals(SessionAgentActivity(total = 1, running = 1), buildSessionAgentActivity(listOf(dispatch), sessionActive = true))
        val awaited = toolMessage("team_await_runs", "tool_call_end", result = """[{"id":"run_1","agentId":"reviewer","status":"completed"}]""")
        assertEquals(SessionAgentActivity(total = 1, completed = 1), buildSessionAgentActivity(listOf(dispatch, awaited), sessionActive = true))
    }

    @Test fun `resolves an async run reported as failed by team_list_runs`() {
        val activity = buildSessionAgentActivity(
            listOf(
                toolMessage("team_run_task", "tool_call_end", input = """{"runMode":"async"}""", result = """{"mode":"async","status":"queued","runId":"run_9"}"""),
                toolMessage("team_list_runs", "tool_call_end", result = """[{"id":"run_9","status":"failed"},{"id":"run_other","status":"completed"}]"""),
            ),
            sessionActive = true,
        )
        // run_other was never dispatched in this session, so it is not counted.
        assertEquals(SessionAgentActivity(total = 1, failed = 1), activity)
    }

    @Test fun `treats a cancelled async run as cancelled`() {
        val activity = buildSessionAgentActivity(
            listOf(
                toolMessage("team_run_task", "tool_call_end", input = """{"runMode":"async"}""", result = """{"mode":"async","status":"queued","runId":"run_2"}"""),
                toolMessage("team_cancel_run", "tool_call_end", result = """{"runId":"run_2","status":"cancelled"}"""),
            ),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 1, cancelled = 1), activity)
    }

    @Test fun `counts one agent per async dispatch, not two`() {
        val activity = buildSessionAgentActivity(
            listOf(toolMessage("team_run_task", "tool_call_end", input = """{"runMode":"async"}""", result = """{"mode":"async","status":"running","runId":"run_3"}""")),
            sessionActive = true,
        )
        assertEquals(1, activity.total)
    }

    @Test fun `keeps repeated status reports idempotent`() {
        val dispatch = toolMessage("team_run_task", "tool_call_end", input = """{"runMode":"async"}""", result = """{"mode":"async","status":"queued","runId":"run_4"}""")
        fun report() = toolMessage("team_list_runs", "tool_call_end", result = """[{"id":"run_4","status":"completed"}]""")
        val activity = buildSessionAgentActivity(listOf(dispatch, report(), report()), sessionActive = true)
        assertEquals(SessionAgentActivity(total = 1, completed = 1), activity)
    }

    @Test fun `counts a failed async dispatch call itself as failed`() {
        val activity = buildSessionAgentActivity(
            listOf(toolMessage("team_run_task", "tool_call_end", input = """{"runMode":"async"}""", result = "\"unknown teammate\"", isError = true)),
            sessionActive = true,
        )
        assertEquals(SessionAgentActivity(total = 1, failed = 1), activity)
    }

    @Test fun `maps persisted session statuses onto run buckets`() {
        assertEquals(SessionAgentRunState.Running, agentEntryState("running"))
        assertEquals(SessionAgentRunState.Running, agentEntryState("pending"))
        assertEquals(SessionAgentRunState.Completed, agentEntryState("Completed"))
        assertEquals(SessionAgentRunState.Failed, agentEntryState("failed"))
        assertEquals(SessionAgentRunState.Cancelled, agentEntryState("cancelled"))
        assertEquals(SessionAgentRunState.Unresolved, agentEntryState("idle"))
        assertEquals(SessionAgentRunState.Unresolved, agentEntryState("something-new"))
    }

    @Test fun `buckets a roster by status`() {
        assertEquals(
            SessionAgentActivity(total = 4, running = 1, completed = 2, failed = 1),
            summarizeAgentEntries(listOf(entry("a", "running"), entry("b"), entry("c"), entry("d", "failed"))),
        )
    }

    @Test fun `returns an empty tally for an empty roster`() {
        assertEquals(SessionAgentActivity.EMPTY, summarizeAgentEntries(emptyList()))
    }

    @Test fun `falls back to the derived tally when the roster is empty`() {
        val derived = SessionAgentActivity(total = 2, running = 2)
        assertSame(derived, mergeAgentActivity(emptyList(), derived, sessionActive = true))
    }

    @Test fun `prefers the roster once it has caught up`() {
        val merged = mergeAgentActivity(listOf(entry("a"), entry("b", "failed")), SessionAgentActivity(total = 2, completed = 2), sessionActive = true)
        assertEquals(SessionAgentActivity(total = 2, completed = 1, failed = 1), merged)
    }

    @Test fun `counts spawns the roster has not recorded yet as running`() {
        val merged = mergeAgentActivity(listOf(entry("a", "running")), SessionAgentActivity(total = 3, running = 3), sessionActive = true)
        assertEquals(3, merged.total)
        assertEquals(3, merged.running)
    }

    @Test fun `does not report unrecorded spawns as running in an idle session`() {
        val merged = mergeAgentActivity(listOf(entry("a")), SessionAgentActivity(total = 2, unresolved = 2), sessionActive = false)
        assertEquals(SessionAgentActivity(total = 2, completed = 1, unresolved = 1), merged)
    }

    @Test fun `never lets the roster shrink the total below what was observed`() {
        assertEquals(4, mergeAgentActivity(listOf(entry("a")), SessionAgentActivity(total = 4, completed = 4), sessionActive = true).total)
    }
}
