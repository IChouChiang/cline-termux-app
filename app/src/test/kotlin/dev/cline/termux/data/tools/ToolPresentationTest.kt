package dev.cline.termux.data.tools

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.icons.ToolSymbol
import kotlinx.serialization.json.Json
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ported from apps/examples/desktop-app/webview/components/views/chat/messages/
 * tool-summaries.test.ts (every case), plus the `ToolCallRow` rules of tool-message-block.tsx
 * and lib/command-output.ts. Labels resolve through the English resources ([EnglishToolLabels]).
 * Not ported: the auto-open rule (A23, every row starts folded; DisclosureRowsTest). The
 * `$ <command>` lines print the call input's command (trial row 18).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolPresentationTest {
    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun toolMessage(content: String, id: String = "tool-1", meta: Meta = Meta()) =
        ChatMessage(id = id, sessionId = "session-1", role = Role.Tool, content = content, createdAt = 1, meta = meta)

    // parseToolPayload

    @Test fun `parses JSON payloads and rejects non-JSON content`() {
        assertEquals(ToolPayload(toolName = "read_files"), parseToolPayload("""{"toolName":"read_files"}"""))
        assertNull(parseToolPayload("not-json"))
    }

    // formatToolValue

    @Test fun `passes plain strings through`() {
        assertEquals("hello", formatToolValue(JsonPrimitive("hello")))
    }

    @Test fun `re-parses JSON strings and surfaces error fields`() {
        assertEquals("boom", formatToolValue(JsonPrimitive("""{"error":"boom"}""")))
        assertEquals("broken", formatToolValue(j("""{"error":"broken"}""")))
    }

    @Test fun `pretty-prints structured values and hides null and undefined`() {
        assertEquals("{\n  \"a\": 1\n}", formatToolValue(j("""{"a":1}""")))
        assertEquals("", formatToolValue(j("null")))
        assertEquals("", formatToolValue(null))
    }

    // extractSubmitSummaryText

    @Test fun `reads the summary from structured or JSON-string input`() {
        assertEquals("All done.", extractSubmitSummaryText(ToolPayload("submit_and_exit", j("""{"summary":"All done.","verified":true}"""))))
        assertEquals("From string input.", extractSubmitSummaryText(ToolPayload("submit_and_exit", JsonPrimitive("""{"summary":"From string input."}"""))))
    }

    @Test fun `returns empty for missing payloads or summaries`() {
        assertEquals("", extractSubmitSummaryText(null))
        assertEquals("", extractSubmitSummaryText(ToolPayload("submit_and_exit", j("{}"))))
    }

    // buildToolPresentation

    @Test fun `marks a payload without a result as in progress`() {
        val presentation = buildToolPresentation(toolMessage("""{"toolName":"read_files","input":{},"result":null}"""))
        assertTrue(presentation.inProgress)
        assertEquals("read_files", presentation.toolName)
    }

    @Test fun `prefers the meta tool name and falls back to kind labels for unparseable payloads`() {
        val presentation = buildToolPresentation(toolMessage("not-json", meta = Meta(toolName = "search")))
        assertNull(presentation.payload)
        assertEquals(ToolKind.Search, presentation.summary.kind)
        assertEquals("Searched", presentation.summary.english)
    }

    @Test fun `treats tool_call_start hook events as in progress`() {
        val presentation = buildToolPresentation(
            toolMessage(
                """{"toolName":"run_commands","input":{},"result":{"ok":true}}""",
                meta = Meta(toolName = "run_commands", hookEventName = "tool_call_start"),
            ),
        )
        assertTrue(presentation.inProgress)
    }

    @Test fun `flags errored payloads and extracts error text`() {
        val presentation = buildToolPresentation(
            toolMessage("""{"toolName":"run_commands","input":{"commands":["bun run deploy"]},"isError":true,"result":{"error":"exit code 1"}}"""),
        )
        assertFalse(presentation.inProgress)
        assertEquals("exit code 1", presentation.summary.errorText)
    }

    // buildGroupedToolLabel over presentations

    private fun presentationFor(toolName: String, input: String) =
        buildToolPresentation(toolMessage("""{"result":{},"toolName":"$toolName","input":$input}""", id = toolName))

    private fun ToolPresentation.group() = GroupedToolLabelInput(summary.label, summary.aggregate, inProgress)

    private fun grouped(vararg inputs: GroupedToolLabelInput) = EnglishToolLabels.group(buildGroupedToolLabel(inputs.toList()))

    @Test fun `returns the single summary label untouched`() {
        assertEquals("Read file a.ts", grouped(presentationFor("read_files", """{"paths":["a.ts"]}""").group()))
    }

    @Test fun `merges consecutive aggregates that share a key`() {
        val first = presentationFor("read_files", """{"paths":["a.ts","b.ts"]}""")
        val second = presentationFor("read_files", """{"paths":["c.ts"]}""")
        assertEquals("Read 3 files", grouped(first.group(), second.group()))
    }

    @Test fun `joins non-mergeable segments with separators and keeps progress verbs`() {
        val reads = presentationFor("read_files", """{"paths":["a.ts","b.ts"]}""")
        val running = buildToolPresentation(toolMessage("""{"toolName":"run_commands","input":{"commands":["bun test"]},"result":null}""", id = "cmd"))
        assertEquals("Read 2 files · Running 1 command", grouped(reads.group(), running.group()))
    }

    // extractRunCommandOutput

    @Test fun `extracts run command output from one or several records`() {
        assertEquals("plain", extractRunCommandOutput(JsonPrimitive("plain")))
        assertEquals("hello", extractRunCommandOutput(j("""[{"query":"echo hello","result":"hello","success":true}]""")))
        assertEquals(
            "$ ls\na.txt\n\n$ false\nexit 1",
            extractRunCommandOutput(j("""[{"query":"ls","result":"a.txt"},{"query":"false","error":"exit 1"},{"query":"true","result":""}]""")),
        )
        assertEquals("", extractRunCommandOutput(null))
    }

    /** A 300-character command and the kernel's capped echo of it (trial row 18). */
    private val longCommand = "echo " + "x".repeat(295)
    private val longEcho = longCommand.take(200) + " ... [command truncated: 100 more chars; full command is in the tool call input]"
    private val twoCommands = buildJsonObject { putJsonArray("commands") { add("ls"); add(longCommand) } }
    private val twoResults = buildJsonArray {
        addJsonObject { put("query", "ls"); put("result", "a.txt") }
        addJsonObject { put("query", longEcho); put("result", "done") }
    }

    private fun runCommandsMessage(input: JsonElement?, result: JsonElement) = toolMessage(
        buildJsonObject {
            put("toolName", "run_commands")
            input?.let { put("input", it) }
            put("result", result)
        }.toString(),
    )

    @Test fun `several records print the input's full command, never the kernel's capped echo`() {
        val output = extractRunCommandOutput(withExecutedCommands(twoResults, twoCommands))
        assertEquals("$ ls\na.txt\n\n$ $longCommand\ndone", output)
        assertFalse(output.contains("command truncated"))
    }

    @Test fun `without the call input the echo stands in, without its note`() {
        assertEquals("$ ls\na.txt\n\n$ ${longCommand.take(200)}…\ndone", extractRunCommandOutput(withExecutedCommands(twoResults, null)))
    }

    @Test fun `record i prints command i of the input, heredocs joined as the kernel ran them`() {
        val input = j("""{"commands":["true","cat <<'EOF'","body","EOF","pwd"]}""")
        val result = j("""[{"query":"true","result":""},{"query":"cat <<'EOF'\nbody\nEOF","result":"body"},{"query":"pwd","result":"/root"}]""")
        assertEquals("$ cat <<'EOF'\nbody\nEOF\nbody\n\n$ pwd\n/root", extractRunCommandOutput(withExecutedCommands(result, input)))
    }

    @Test fun `a history row's output prints the input's full command`() {
        val model = buildToolRowModel(runCommandsMessage(twoCommands, twoResults), isRunActive = false)
        assertEquals("$ ls\na.txt\n\n$ $longCommand\ndone", model.commandOutput)
        assertEquals(listOf("ls", longCommand), model.details)
    }

    @Test fun `a call that printed nothing falls back to its result JSON, with the input's command`() {
        val silent = buildJsonArray { addJsonObject { put("query", longEcho); put("result", ""); put("success", true) } }
        val input = buildJsonObject { putJsonArray("commands") { add(longCommand) } }
        val output = buildToolRowModel(runCommandsMessage(input, silent), isRunActive = false).commandOutput
        assertTrue(output.contains(longCommand))
        assertFalse(output.contains("command truncated"))
        // Without the input: the echo's kept part, never its note.
        val bare = buildToolRowModel(runCommandsMessage(null, silent), isRunActive = false).commandOutput
        assertTrue(bare.contains(longCommand.take(200) + "…"))
        assertFalse(bare.contains("command truncated"))
    }

    // lib/command-output.ts

    @Test fun `caps command output to the tail with one marker`() {
        val small = appendCappedCommandOutput("ab", "cd")
        assertEquals(CappedOutput("abcd", false), small)
        val big = appendCappedCommandOutput("", "x".repeat(MAX_LIVE_COMMAND_OUTPUT_CHARS + 10))
        assertTrue(big.truncated)
        assertEquals(MAX_LIVE_COMMAND_OUTPUT_CHARS, big.output.length)
        assertTrue(big.output.startsWith(COMMAND_OUTPUT_TRUNCATION_MARKER))
        assertTrue(appendCappedCommandOutput(big.output.substring(0, 100), "y").truncated)
    }

    // ToolCallRow rules

    @Test fun `running status needs an active run, errors win`() {
        val inFlight = toolMessage("""{"toolName":"read_files","input":{"paths":["a.ts"]}}""")
        assertEquals(ToolActivityStatus.Running, buildToolRowModel(inFlight, isRunActive = true).status)
        // A missing result can outlive its run: A21 draws it Interrupted (the desktop: in progress).
        assertEquals(ToolActivityStatus.Interrupted, buildToolRowModel(inFlight, isRunActive = false).status)
        val failed = toolMessage("""{"toolName":"read_files","input":{"paths":["a.ts"]},"isError":true,"result":"denied"}""")
        val model = buildToolRowModel(failed, isRunActive = true)
        assertEquals(ToolActivityStatus.Error, model.status)
        assertEquals("denied", model.errorText)
        assertTrue(model.expandable)
    }

    @Test fun `a call without a result is Interrupted once its turn has finished (A21)`() {
        val blocked = toolMessage(
            """{"toolName":"run_commands","input":{"commands":["echo hook-test"]}}""",
            meta = Meta(toolName = "run_commands", hookEventName = "history_tool_use", toolCallId = "call_1"),
        )
        val live = buildToolRowModel(blocked, isRunActive = true)
        assertEquals(ToolActivityStatus.Running, live.status)
        assertTrue(live.isRunning)
        assertFalse(live.isInterrupted)
        assertEquals("Running command echo hook-test", EnglishToolLabels.of(live.label))

        val finished = buildToolRowModel(blocked, isRunActive = false)
        assertEquals(ToolActivityStatus.Interrupted, finished.status)
        assertFalse(finished.status.inFlight)
        assertFalse(finished.isRunning)
        assertTrue(finished.isInterrupted)
        assertFalse(finished.canProceed)
        assertEquals(ToolLabel.Interrupted(ToolLabel.Phrase(ToolPhrase.RunningCommandNamed, "echo hook-test")), finished.label)
        assertEquals("Interrupted: echo hook-test", EnglishToolLabels.of(finished.label))

        // A result, an error or a finished submit is never Interrupted.
        val done = toolMessage("""{"toolName":"run_commands","input":{"commands":["ls"]},"result":[{"query":"ls","result":"a"}]}""")
        assertEquals(ToolActivityStatus.Success, buildToolRowModel(done, isRunActive = false).status)
        assertFalse(buildToolRowModel(done, isRunActive = false).isInterrupted)
        val failed = toolMessage(
            """{"toolName":"run_commands","input":{"commands":["ls"]},"isError":true}""",
            meta = Meta(hookEventName = "tool_call_start"),
        )
        assertEquals(ToolActivityStatus.Error, buildToolRowModel(failed, isRunActive = false).status)
        assertFalse(buildToolRowModel(failed, isRunActive = false).isInterrupted)
        val submit = toolMessage("""{"toolName":"submit_and_exit","input":{"summary":"Done."}}""")
        assertEquals("Interrupted", EnglishToolLabels.of(buildToolRowModel(submit, isRunActive = false).label))
    }

    @Test fun `streamed command output wins while running`() {
        val message = toolMessage(
            """{"toolName":"run_commands","input":{"commands":["echo hi"]}}""",
            meta = Meta(toolName = "run_commands", toolOutput = "hi\n", toolDetachable = true, toolCallId = "call_1"),
        )
        val model = buildToolRowModel(message, isRunActive = true)
        assertEquals("hi\n", model.commandOutput)
        assertTrue(model.canProceed)
        assertTrue(model.expandable)
        assertEquals(ToolSymbol.Terminal, model.icon)
        assertFalse(buildToolRowModel(message, isRunActive = true, proceedAvailable = false).canProceed)
    }

    @Test fun `history command output comes from the result and is dropped for errors`() {
        val finished = toolMessage("""{"toolName":"run_commands","input":{"commands":["ls"]},"result":[{"query":"ls","result":"a.txt"}]}""")
        val model = buildToolRowModel(finished, isRunActive = false)
        assertEquals("a.txt", model.commandOutput)
        assertTrue(model.expandable)
        val errored = toolMessage("""{"toolName":"run_commands","input":{"commands":["ls"]},"isError":true,"result":[{"query":"ls","result":"partial"}]}""")
        assertEquals("", buildToolRowModel(errored, isRunActive = false).commandOutput)
    }

    @Test fun `submit_and_exit names the milestone and carries its summary`() {
        val running = toolMessage("""{"toolName":"submit_and_exit","input":{"summary":"Done."}}""")
        val model = buildToolRowModel(running, isRunActive = true)
        assertEquals(ToolLabel.Phrase(ToolPhrase.CompletingScheduledTask), model.label)
        assertEquals("Completing scheduled task", EnglishToolLabels.of(model.label))
        assertEquals("Done.", model.submitText)
        assertTrue(model.expandable)
        assertEquals(ToolSymbol.ExitToApp, model.icon)
        val done = toolMessage("""{"toolName":"submit_and_exit","input":{"summary":"Done."},"result":"ok"}""")
        assertEquals("Scheduled task completed", EnglishToolLabels.of(buildToolRowModel(done, false).label))
        val failed = toolMessage("""{"toolName":"submit_and_exit","input":{"summary":"Done."},"result":"x","isError":true}""")
        assertEquals("Scheduled task failed", EnglishToolLabels.of(buildToolRowModel(failed, false).label))
    }

    @Test fun `file diffs build per hunk and badges hide zero counts`() {
        val edit = toolMessage("""{"toolName":"editor","input":{"path":"a.ts","old_text":"x","new_text":"y"},"result":"ok"}""")
        val model = buildToolRowModel(edit, isRunActive = false)
        assertEquals(1, model.fileDiffs.size)
        assertTrue(model.fileDiffs[0].isRich)
        assertEquals("tool-1_diff_0_0", model.fileDiffs[0].key)
        assertTrue(model.expandable)
        assertEquals(1, model.additions)
        assertEquals(1, model.deletions)
        val create = toolMessage("""{"toolName":"editor","input":{"path":"b.ts","new_text":"one"},"result":"ok"}""")
        val created = buildToolRowModel(create, isRunActive = false)
        assertEquals(1, created.additions)
        assertNull(created.deletions)
        assertNull(created.fileDiffs[0].hunk!!.oldText)
    }

    @Test fun `a row without any body is not expandable`() {
        val model = buildToolRowModel(toolMessage("""{"toolName":"custom_tool","input":{}}"""), isRunActive = false)
        assertFalse(model.expandable)
    }

    @Test fun `output images become chat images and other media stays separate`() {
        val content = """{"toolName":"computer","input":{},"result":{"content":[{"type":"image","data":"aGVsbG8=","mimeType":"image/png"},{"type":"audio","data":"d29ybGQ=","mime_type":"audio/mpeg"}]}}"""
        val model = buildToolRowModel(toolMessage(content), isRunActive = false)
        assertEquals("tool-1_tool_image_0", model.outputImages.single().id)
        assertEquals(1, model.otherOutputMedia.size)
        assertTrue(model.expandable)
    }

    @Test fun `debug input preview is opt-in`() {
        val message = toolMessage("""{"toolName":"custom_tool","input":{"a":1}}""")
        assertEquals("", buildToolRowModel(message, false).inputPreview)
        val model = buildToolRowModel(message, false, showInputPreview = true)
        assertEquals("{\n  \"a\": 1\n}", model.inputPreview)
        assertTrue(model.expandable)
    }
}
