package dev.cline.termux.ui.tool

import dev.cline.termux.data.session.ToolRecord
import dev.cline.termux.data.session.ToolStatus
import dev.cline.termux.data.tools.ExecutionView
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalSnapshot
import dev.cline.termux.data.tools.ToolCallEntry
import dev.cline.termux.data.tools.ToolCallView
import dev.cline.termux.data.tools.ToolLabel
import dev.cline.termux.data.tools.ToolPhrase
import dev.cline.termux.data.tools.ToolOrigin
import dev.cline.termux.ui.icons.ToolKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailModelTest {
    private val sessionId = "s1"

    private val runInput = buildJsonObject { putJsonArray("commands") { add("bun test") } }

    private fun record(
        status: ToolStatus,
        name: String = "run_commands",
        input: JsonElement? = runInput,
        output: JsonElement? = null,
        error: String? = null,
        startedAt: Long = 1_000,
        endedAt: Long? = null,
        durationMs: Long? = null,
    ) = ToolRecord(
        toolCallId = "call_1",
        toolName = name,
        input = input,
        status = status,
        iteration = 1,
        requestId = "r1",
        startedAt = startedAt,
        endedAt = endedAt,
        output = output,
        error = error,
        durationMs = durationMs,
    )

    private fun exec(
        out: String = "",
        err: String = "",
        index: Int = 0,
        query: String = "bun test",
        running: Boolean = true,
        detachable: Boolean = false,
        success: Boolean? = null,
        result: String? = null,
    ): ExecutionView {
        val all = TerminalBuffer.parse(out + err)
        return ExecutionView(
            executionId = "e$index",
            commandIndex = index,
            query = query,
            output = all,
            stdout = TerminalBuffer.parse(out),
            stderr = TerminalBuffer.parse(err),
            hasStderr = err.isNotEmpty(),
            inlineOutput = out + err,
            inlineTruncated = false,
            streamed = out.isNotEmpty() || err.isNotEmpty(),
            gapped = false,
            fromResult = false,
            truncated = false,
            kernelDroppedChars = 0,
            detachable = detachable,
            success = success,
            result = result,
            running = running,
        )
    }

    private fun view(
        record: ToolRecord,
        executions: List<ExecutionView> = emptyList(),
        runActive: Boolean = true,
        live: Boolean = true,
        origin: ToolOrigin = ToolOrigin.Live,
    ) = ToolCallView(ToolCallEntry(record, origin), executions, runActive, live)

    private fun build(view: ToolCallView) = ToolDetailModel.build(sessionId, view)

    @Test
    fun `running command without output is waiting, with output running`() {
        val waiting = build(view(record(ToolStatus.Running), listOf(exec())))
        assertEquals(ToolDetailStatus.WaitingForOutput, waiting.status)
        assertEquals(ToolLayout.Command, waiting.layout)
        assertEquals(1_000L, waiting.runningSince)
        assertNull(waiting.durationMs)

        val running = build(view(record(ToolStatus.Running), listOf(exec(out = "line 1\n"))))
        assertEquals(ToolDetailStatus.Running, running.status)
        assertEquals(listOf("line 1"), running.commands.single().output.lines.map { it.text })
    }

    @Test
    fun `canProceed needs running, a detachable execution and a live session (desktop rule)`() {
        val record = record(ToolStatus.Running)
        assertFalse(build(view(record, listOf(exec(out = "x")))).proceedOffered)
        assertTrue(build(view(record, listOf(exec(out = "x", detachable = true)))).proceedOffered)
        assertFalse(build(view(record, listOf(exec(out = "x", detachable = true)), live = false)).proceedOffered)
        assertFalse(build(view(record, listOf(exec(out = "x", detachable = true)), runActive = false)).proceedOffered)
    }

    @Test
    fun `stop is offered while a live run is in flight for the tool`() {
        assertTrue(build(view(record(ToolStatus.Running), listOf(exec(out = "x")))).abortOffered)
        assertTrue(build(view(record(ToolStatus.AwaitingApproval))).abortOffered)
        assertFalse(build(view(record(ToolStatus.Running), listOf(exec(out = "x")), live = false)).abortOffered)
        assertFalse(build(view(record(ToolStatus.Finished(null)), listOf(exec(out = "x", running = false)))).abortOffered)
    }

    @Test
    fun `in progress after the run ended is No result, cancelled too`() {
        assertEquals(ToolDetailStatus.NoResult, build(view(record(ToolStatus.Running), runActive = false)).status)
        assertEquals(ToolDetailStatus.NoResult, build(view(record(ToolStatus.Cancelled), runActive = false)).status)
        assertEquals(ToolDetailStatus.Interrupted, build(view(record(ToolStatus.Interrupted), runActive = false)).status)
        assertEquals(ToolDetailStatus.AwaitingApproval, build(view(record(ToolStatus.AwaitingApproval))).status)
    }

    @Test
    fun `completed keeps the run duration, rejected shows the kernel text`() {
        val done = build(view(record(ToolStatus.Finished(null), endedAt = 4_000, durationMs = 3_000), listOf(exec(out = "ok\n", running = false, success = true))))
        assertEquals(ToolDetailStatus.Completed, done.status)
        assertEquals(3_000L, done.durationMs)
        assertNull(done.runningSince)

        val rejected = build(view(record(ToolStatus.Rejected, error = "This tool call was rejected by the user and not executed."), runActive = false))
        assertEquals(ToolDetailStatus.Rejected, rejected.status)
        assertEquals("This tool call was rejected by the user and not executed.", rejected.errorText)
    }

    @Test
    fun `failed with streamed output keeps the output, without it only the error`() {
        val streamed = build(view(record(ToolStatus.Finished("exit 3"), error = "exit 3"), listOf(exec(out = "1\n2\n", running = false, success = false))))
        assertEquals(ToolDetailStatus.Failed, streamed.status)
        assertTrue(streamed.showTerminal)
        assertEquals("exit 3", streamed.errorText)

        val bare = build(view(record(ToolStatus.Finished("boom"), error = "boom", output = JsonPrimitive("boom")), runActive = false))
        assertFalse(bare.showTerminal)
        assertTrue(bare.commands.single().output.isEmpty)
    }

    @Test
    fun `detach notice in the result makes it still running in the background with the log path`() {
        val path = "/data/data/com.termux/files/home/.cline/data/cmd-1.log"
        val notice = "[Command is still running. Output will continue in $path]"
        val content = build(
            view(
                record(ToolStatus.Finished(null)),
                listOf(exec(out = "line 1\n$notice\n", running = false, success = true, result = "line 1\n$notice")),
            ),
        )
        assertEquals(ToolDetailStatus.Detached, content.status)
        assertEquals(path, content.detachedLogPath)
    }

    @Test
    fun `leftover children notice keeps Completed`() {
        val notice = "[Command completed with background processes still running; their output is no longer captured]"
        val content = build(view(record(ToolStatus.Finished(null)), listOf(exec(out = "done\n$notice\n", running = false, success = true))))
        assertEquals(ToolDetailStatus.Completed, content.status)
        assertNull(content.detachedLogPath)
        assertEquals("done", content.commands.single().output.copyText())
    }

    @Test
    fun `history without executions falls back to the run result text`() {
        val result = buildJsonArray { addJsonObject { put("query", "echo a"); put("result", "a\n"); put("success", true) } }
        val content = build(view(record(ToolStatus.Finished(null), output = result), origin = ToolOrigin.History, live = false, runActive = false))
        assertEquals(ToolDetailStatus.Completed, content.status)
        assertEquals("a", content.commands.single().output.copyText())
        assertFalse(content.commands.single().streamed)
        assertNull(content.durationMs)
    }

    @Test
    fun `edit calls use the edit layout grouped by file`() {
        val input = buildJsonObject {
            put("command", "str_replace")
            put("path", "/ws/Main.kt")
            put("old_text", "val x = 1")
            put("new_text", "val x = 2")
        }
        val content = build(view(record(ToolStatus.Finished(null), name = "editor", input = input, output = JsonPrimitive("Edited /ws/Main.kt")), runActive = false))
        assertEquals(ToolKind.Edit, content.kind)
        assertEquals(ToolLayout.Edit, content.layout)
        assertEquals(1, content.fileItems.size)
        assertEquals("/ws/Main.kt", content.fileItems.single().path)
        assertTrue(content.fileItems.single().diffs.isNotEmpty())
    }

    @Test
    fun `submit_and_exit renders its summary markdown with the desktop label`() {
        val input = buildJsonObject { put("summary", "## Done\nAll good") }
        val content = build(view(record(ToolStatus.Finished(null), name = "submit_and_exit", input = input, output = JsonPrimitive("ok")), runActive = false))
        assertEquals(ToolLayout.Generic, content.layout)
        assertEquals("## Done\nAll good", content.submitMarkdown)
        assertEquals(ToolLabel.Phrase(ToolPhrase.ScheduledTaskCompleted), content.label)
        assertNull(content.outputText)
    }

    @Test
    fun `unknown tools carry the result text and the pretty input`() {
        val input = buildJsonObject { put("url", "https://example.com") }
        val content = build(view(record(ToolStatus.Finished(null), name = "mcp_fetch_page", input = input, output = JsonPrimitive("hello")), runActive = false))
        assertEquals(ToolLayout.Generic, content.layout)
        assertEquals("hello", content.outputText)
        assertTrue(content.inputJson.contains("\"url\""))
    }

    @Test
    fun `detach notice is found in terminal lines when the result lacks it`() {
        val notice = "[Command is still running. Output will continue in /tmp/x.log]"
        val command = CommandView(0, "q", TerminalBuffer.parse("a\n$notice"), TerminalSnapshot.EMPTY, TerminalSnapshot.EMPTY, false, true, true, false, false)
        assertEquals("/tmp/x.log", ToolDetailModel.detachedLogPath(emptyList(), null, listOf(command)))
    }
}
