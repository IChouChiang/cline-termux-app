package dev.cline.termux.data.tools

import dev.cline.termux.data.chat.MediaModality
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.icons.classifyTool
import dev.cline.termux.ui.icons.normalizeToolName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Ported from sdk/packages/ui/tests/tool-summary.test.ts (every case). Labels are typed; the
 * assertions resolve them through the English resources ([EnglishToolLabels]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolSummaryTest {
    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun s(text: String): JsonElement = JsonPrimitive(text)
    private fun summary(
        toolName: String,
        input: JsonElement? = null,
        result: JsonElement? = null,
        isError: Boolean = false,
        inProgress: Boolean = false,
        options: ToolSummaryOptions = ToolSummaryOptions(),
    ) = buildToolSummary(ToolSummaryPayload(toolName, input, result, isError, inProgress), options)

    // normalizeToolName / classifyTool

    @Test fun `applies aliases from other runtimes`() {
        assertEquals("run_commands", normalizeToolName("bash"))
        assertEquals("editor", normalizeToolName("Edit_File"))
        assertEquals("fetch_web_content", normalizeToolName("web-fetch"))
    }

    @Test fun `classifies sdk tool names`() {
        assertEquals(ToolKind.Read, classifyTool("read_files"))
        assertEquals(ToolKind.Edit, classifyTool("editor"))
        assertEquals(ToolKind.Edit, classifyTool("apply_patch"))
        assertEquals(ToolKind.Command, classifyTool("run_commands"))
        assertEquals(ToolKind.Search, classifyTool("search_codebase"))
        assertEquals(ToolKind.Web, classifyTool("fetch_web_content"))
        assertEquals(ToolKind.Spawn, classifyTool("spawn_agent"))
        assertEquals(ToolKind.Spawn, classifyTool("subagent_review"))
        assertEquals(ToolKind.Team, classifyTool("team_status"))
        assertEquals(ToolKind.Skill, classifyTool("skills"))
        assertEquals(ToolKind.Question, classifyTool("ask_question"))
        assertEquals(ToolKind.Mcp, classifyTool("mcp_linear_create_issue"))
    }

    @Test fun `falls back to fuzzy classification for unknown names`() {
        assertEquals(ToolKind.Command, classifyTool("terminal_run"))
        assertEquals(ToolKind.Search, classifyTool("grep_repo"))
        assertEquals(ToolKind.Web, classifyTool("browser_navigate"))
        assertEquals(ToolKind.Other, classifyTool("some_custom_tool"))
    }

    // read_files

    @Test fun `labels a single file with its line range inline`() {
        val result = summary("read_files", j("""{"files":[{"path":"src/app.tsx","start_line":10,"end_line":80}]}"""))
        assertEquals("Read file app.tsx (10–80)", result.english)
        assertEquals(ToolLabel.Phrase(ToolPhrase.ReadFileNamed, "app.tsx (10–80)"), result.label)
        assertEquals(ToolKind.Read, result.kind)
        assertEquals(listOf("src/app.tsx:10-80"), result.details)
        assertEquals(
            listOf(ToolSummaryItem.File(path = "src/app.tsx", displayPath = "src/app.tsx", startLine = 10, endLine = 80, language = "tsx")),
            result.items,
        )
    }

    @Test fun `marks open-ended reads with a + suffix`() {
        val result = summary("read_files", j("""{"files":[{"path":"a.ts","start_line":100}]}"""))
        assertEquals("Read file a.ts (100+)", result.english)
        assertEquals(listOf("a.ts:100+"), result.details)
    }

    @Test fun `aggregates multiple files with per-file detail lines`() {
        val result = summary("read_files", j("""{"file_paths":["src/a.ts","src/b.ts","src/c.ts"]}"""), inProgress = true)
        assertEquals("Reading 3 files", result.english)
        assertEquals(listOf("src/a.ts", "src/b.ts", "src/c.ts"), result.details)
        assertEquals(3, result.aggregate?.count)
    }

    @Test fun `accepts the paths, bare string and JSON-string shapes`() {
        assertEquals("Read file x.go", summary("read_files", j("""{"paths":["x.go"]}""")).english)
        assertEquals("Read file y.rs", summary("read_files", s("""{"file_paths":["y.rs"]}""")).english)
    }

    @Test fun `shortens long paths in details but keeps basenames in labels`() {
        val longPath = "apps/examples/desktop-app/webview/components/views/chat/chat-messages.tsx"
        val result = summary("read_files", j("""{"file_paths":["$longPath"]}"""))
        assertEquals("Read file chat-messages.tsx", result.english)
        assertEquals(shortenPath(longPath), result.details[0])
        assertTrue(result.details[0].startsWith(".../"))
    }

    // run_commands

    @Test fun `leads a single command with the action, command in mono`() {
        val result = summary("run_commands", j("""{"commands":["bun run test"]}"""))
        assertEquals("Ran command bun run test", result.english)
        assertEquals(ToolLabel.Phrase(ToolPhrase.RanCommandNamed, "bun run test"), result.label)
        assertEquals(listOf(ToolSummaryItem.Command("bun run test")), result.items)
        assertEquals(listOf("bun run test"), result.details)
    }

    @Test fun `never truncates labels by default, layout owns overflow`() {
        val command = "echo ${"x".repeat(400)}"
        assertEquals("Ran command $command", summary("run_commands", j("""{"commands":["$command"]}""")).english)
    }

    @Test fun `applies maxInlineChars only when a consumer opts in`() {
        val command = "echo ${"x".repeat(400)}"
        val result = summary("run_commands", j("""{"commands":["$command"]}"""), options = ToolSummaryOptions(maxInlineChars = 60))
        assertTrue(result.english.length <= "Ran command ".length + 60)
        assertTrue(result.english.endsWith("…"))
        assertEquals(command, result.details[0])
    }

    @Test fun `maxInlineChars cuts model input on a grapheme boundary`() {
        // Budget 10 → 9 units + "…". The emoji / 𠮷 occupies units 8..9 and must not be split.
        val emoji = "abcdefgh\uD83D\uDE00xyz"
        assertEquals("Asked \"abcdefgh…\"", summary("ask_question", j("""{"question":"$emoji"}"""), options = ToolSummaryOptions(maxInlineChars = 10)).english)
        val cjk = "abcdefgh\uD842\uDFB7xyz"
        assertEquals("Ran command abcdefgh…", summary("run_commands", j("""{"commands":["$cjk"]}"""), options = ToolSummaryOptions(maxInlineChars = 10)).english)
        val fits = "abcdefg\uD842\uDFB7xyz"
        assertEquals("Ran command abcdefg\uD842\uDFB7…", summary("run_commands", j("""{"commands":["$fits"]}"""), options = ToolSummaryOptions(maxInlineChars = 10)).english)
    }

    @Test fun `accepts every run_commands union shape`() {
        val cases = listOf(
            j("""{"commands":"ls -la"}""") to listOf("ls -la"),
            j("""{"commands":{"command":"git","args":["status"]}}""") to listOf("git status"),
            j("""[{"command":"bun","args":["test"]}]""") to listOf("bun test"),
            j("""{"command":"bun","args":["run","lint"]}""") to listOf("bun run lint"),
            j("""{"cmd":"pwd"}""") to listOf("pwd"),
            j("""["ls","pwd"]""") to listOf("ls", "pwd"),
            s("whoami") to listOf("whoami"),
        )
        for ((input, expected) in cases) {
            assertEquals(expected.map { ToolSummaryItem.Command(it) }, summary("run_commands", input).items)
        }
    }

    @Test fun `aggregates multiple commands and joins structured entries`() {
        val result = summary("run_commands", j("""{"commands":["ls -la",{"command":"git","args":["status","-sb"]}]}"""), inProgress = true)
        assertEquals("Running 2 commands", result.english)
        assertEquals(listOf("ls -la", "git status -sb"), result.details)
    }

    @Test fun `captures command output text`() {
        assertEquals("a.txt\nb.txt", summary("run_commands", j("""{"commands":["ls"]}"""), s("a.txt\nb.txt")).outputText)
    }

    // search / web

    @Test fun `labels a single search query inline`() {
        assertEquals("Searched ToolActivityTrigger", summary("search_codebase", j("""{"queries":["ToolActivityTrigger"]}""")).english)
    }

    @Test fun `aggregates multiple queries`() {
        val result = summary("search_codebase", j("""{"queries":["a","b"]}"""))
        assertEquals("Explored 2 searches", result.english)
        assertEquals(listOf("a", "b"), result.details)
    }

    @Test fun `labels single and multiple url fetches`() {
        assertEquals("Fetched https://cline.bot", summary("fetch_web_content", j("""{"requests":[{"url":"https://cline.bot"}]}""")).english)
        assertEquals("Explored 2 links", summary("web_fetch", j("""{"requests":[{"url":"https://a.dev"},{"url":"https://b.dev"}]}""")).english)
    }

    // editor

    @Test fun `derives diff counts and a unified diff from old and new text`() {
        val result = summary("editor", j("""{"path":"src/util.ts","old_text":"const a = 1;\nconst b = 2;","new_text":"const a = 1;\nconst b = 3;\nconst c = 4;"}"""))
        assertEquals("Edited file util.ts", result.english)
        assertEquals(listOf("src/util.ts"), result.details)
        assertEquals(DiffCounts(2, 1), result.diff)
        val item = result.items[0] as ToolSummaryItem.File
        assertTrue(item.diff!!.contains("--- a/src/util.ts"))
        assertTrue(item.diff!!.contains("+const c = 4;"))
        assertEquals("typescript", item.language)
        assertTrue(item.diff!!.contains("@@ … @@"))
        assertFalse(Regex("@@ -\\d").containsMatchIn(item.diff!!))
        assertEquals("const a = 1;\nconst b = 2;", item.oldText)
        assertEquals("const a = 1;\nconst b = 3;\nconst c = 4;", item.newText)
        assertEquals(true, item.fragment)
    }

    @Test fun `keeps real hunk positions when creating a whole file`() {
        val item = summary("editor", j("""{"path":"new.ts","new_text":"line 1\nline 2"}""")).items[0] as ToolSummaryItem.File
        assertTrue(Regex("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@").containsMatchIn(item.diff!!))
    }

    @Test fun `treats a create as all additions`() {
        val result = summary("editor", j("""{"path":"new.py","new_text":"print(1)\nprint(2)"}"""))
        assertEquals("Created file new.py", result.english)
        assertEquals(DiffCounts(2, 0), result.diff)
    }

    @Test fun `does not report phantom lines for empty texts`() {
        assertEquals(DiffCounts(0, 1), summary("editor", j("""{"path":"a.ts","old_text":"line","new_text":""}""")).diff)
        assertEquals(DiffCounts(0, 0), summary("editor", j("""{"path":"b.ts","new_text":""}""")).diff)
    }

    @Test fun `uses in-progress verbs`() {
        assertEquals("Editing file a.ts", summary("editor", j("""{"path":"a.ts","old_text":"x","new_text":"y"}"""), inProgress = true).english)
    }

    @Test fun `falls back to +N and -N counts from the result`() {
        val result = summary("editor", j("""{"path":"a.ts"}"""), j("""{"result":"+12: added line\n-13: removed line\n+14: another"}"""))
        assertEquals("Edited file a.ts", result.english)
        assertEquals(DiffCounts(2, 1), result.diff)
    }

    // apply_patch

    private val patch = listOf(
        "*** Begin Patch",
        "*** Update File: src/one.ts",
        "-old line",
        "+new line",
        "+extra line",
        "*** Add File: src/two.ts",
        "+created",
        "*** End Patch",
    ).joinToString("\n")

    @Test fun `parses per-file additions and deletions`() {
        val info = parseApplyPatchInput(s(patch))!!
        assertEquals(listOf("src/one.ts", "src/two.ts"), info.files.map { it.path })
        assertEquals(2 to 1, info.files[0].additions to info.files[0].deletions)
        assertEquals(1 to 0, info.files[1].additions to info.files[1].deletions)
        assertEquals(3, info.additions)
        assertEquals(1, info.deletions)
        assertTrue(info.files[0].diff.contains("--- a/src/one.ts"))
        assertTrue(info.files[0].diff.contains("+new line"))
        assertTrue(info.files[0].diff.contains("@@ … @@"))
        assertFalse(Regex("@@ -\\d").containsMatchIn(info.files[0].diff))
        assertTrue(Regex("@@ -\\d+,\\d+ \\+\\d+,\\d+ @@").containsMatchIn(info.files[1].diff))
        assertEquals(PatchAction.Update, info.files[0].action)
        assertEquals(listOf(DiffHunk("old line", "new line\nextra line")), info.files[0].hunks)
        assertEquals(PatchAction.Add, info.files[1].action)
        assertEquals(listOf(DiffHunk("", "created")), info.files[1].hunks)
    }

    @Test fun `preserves hunk boundaries instead of re-diffing across them`() {
        val multiHunk = listOf("*** Begin Patch", "*** Update File: src/multi.ts", "@@ first", "-a", "+b", "@@ second", "-b", "+c", "*** End Patch").joinToString("\n")
        val info = parseApplyPatchInput(s(multiHunk))!!
        assertEquals(listOf(DiffHunk("a", "b"), DiffHunk("b", "c")), info.files[0].hunks)
        assertEquals(2 to 2, info.files[0].additions to info.files[0].deletions)
        val item = summary("apply_patch", s(multiHunk)).items[0] as ToolSummaryItem.File
        assertEquals(2, item.hunks?.size)
        assertNull(item.oldText)
    }

    @Test fun `labels deleted and renamed files from their section metadata`() {
        val deleted = summary("apply_patch", s(listOf("*** Begin Patch", "*** Delete File: src/gone.ts", "*** End Patch").joinToString("\n")))
        assertEquals("Deleted file gone.ts", deleted.english)
        assertEquals(listOf("Deleted src/gone.ts"), deleted.details)
        val item = deleted.items[0] as ToolSummaryItem.File
        assertNull(item.newText)
        assertNull(item.hunks)
        val rename = listOf("*** Begin Patch", "*** Update File: src/old-name.ts", "*** Move to: src/new-name.ts", "-a", "+b", "*** End Patch").joinToString("\n")
        val renamed = summary("apply_patch", s(rename))
        assertEquals("Edited file old-name.ts → new-name.ts", renamed.english)
        assertEquals(listOf("src/old-name.ts → src/new-name.ts +1 -1"), renamed.details)
    }

    @Test fun `labels multi-file patches with counts and per-file details`() {
        val result = summary("apply_patch", s(patch))
        assertEquals("Edited 2 files", result.english)
        assertEquals(DiffCounts(3, 1), result.diff)
        assertEquals(listOf("src/one.ts +2 -1", "src/two.ts +1 -0"), result.details)
    }

    @Test fun `labels single-file patches with the file name`() {
        val single = listOf("*** Begin Patch", "*** Update File: src/only.ts", "+added", "*** End Patch").joinToString("\n")
        assertEquals("Edited file only.ts", summary("apply_patch", s(single)).english)
    }

    @Test fun `degrades to a generic label when the envelope is unparsable`() {
        assertEquals("Applied patch", summary("apply_patch", j("42")).english)
    }

    // spawn / skill / question

    @Test fun `puts the truncated task inline for spawn_agent`() {
        val result = summary("spawn_agent", j("""{"task":"Fix the login bug in auth flow"}"""))
        assertEquals("Spawned agent: Fix the login bug in auth flow", result.english)
        assertEquals(listOf("Fix the login bug in auth flow"), result.details)
    }

    @Test fun `labels skills with the skill name`() {
        val result = summary("skills", j("""{"skill":"deploy","args":"--prod"}"""))
        assertEquals("Used skill deploy", result.english)
        assertEquals(listOf("deploy --prod"), result.details)
    }

    @Test fun `labels questions with the question text and captures the answer`() {
        val result = summary("ask_question", j("""{"question":"Which env?","options":["dev","prod"]}"""), s("prod"))
        assertEquals("Asked \"Which env?\"", result.english)
        assertEquals(listOf("dev", "prod"), result.details)
        assertEquals("prod", result.outputText)
    }

    // team

    @Test fun `labels team_spawn_teammate with the agent id detail`() {
        val result = summary("team_spawn_teammate", result = j("""{"agentId":"researcher-1"}"""))
        assertEquals("Spawned 1 teammate", result.english)
        assertEquals(listOf("researcher-1"), result.details)
        assertEquals(ToolKind.Team, result.kind)
    }

    @Test fun `uses failure labels for errored team tools`() {
        assertEquals("Failed to broadcast message to teammates", summary("team_broadcast", isError = true).english)
    }

    // fallbacks

    @Test fun `labels input-less events by kind`() {
        assertEquals("Read file", summary("read_files").english)
        assertEquals("Running command", summary("run_commands", inProgress = true).english)
        assertEquals("Edited file", summary("editor").english)
        assertEquals("Searched", summary("search_codebase").english)
    }

    @Test fun `humanizes unknown tool names`() {
        assertEquals("Custom tool", summary("custom-tool").english)
        assertEquals("Running Custom tool", summary("custom_tool", inProgress = true).english)
    }

    /** A11 row 4: the app's own client tool reads as its action and, like the CLI's tool-output.tsx, shows no output. */
    @Test fun `switch_to_act_mode reads as an action and keeps its success text out of the row`() {
        val success = s("You successfully switched to act mode, proceed with the plan.")
        for (inProgress in listOf(false, true)) {
            val result = summary("switch_to_act_mode", j("{}"), if (inProgress) null else success, inProgress = inProgress)
            assertEquals(ToolLabel.Phrase(ToolPhrase.SwitchToActMode), result.label)
            assertEquals("Switch to Act mode", result.english)
            assertNull(result.outputText)
        }
        // The refusal in an act turn is an error, and errors still show (the CLI prints them too).
        val refused = summary("switch_to_act_mode", j("{}"), j("""{"error":"Already in act mode."}"""), isError = true)
        assertEquals("Already in act mode.", refused.errorText)
        assertNull(refused.outputText)
    }

    @Test fun `captures error text from record results`() {
        val result = summary("run_commands", j("""{"commands":["false"]}"""), j("""{"error":"exit code 1"}"""), isError = true)
        assertEquals("exit code 1", result.errorText)
        assertNull(result.outputText)
    }

    @Test fun `never throws on garbage payloads`() {
        for (input in listOf(j("null"), j("42"), s("not json {"), j("[]"), j("""{"files":7}"""))) {
            summary("read_files", input)
        }
    }

    @Test fun `caps oversized output text`() {
        val result = summary("run_commands", j("""{"commands":["cat big"]}"""), s("x".repeat(200)), options = ToolSummaryOptions(maxOutputChars = 50))
        assertTrue(result.outputText!!.endsWith("[output truncated]"))
    }

    // buildGroupedToolLabel

    private fun ToolSummary.groupInput(inProgress: Boolean = false) = GroupedToolLabelInput(label, aggregate, inProgress)

    private fun grouped(inputs: List<GroupedToolLabelInput>) = EnglishToolLabels.group(buildGroupedToolLabel(inputs))

    @Test fun `merges adjacent same-kind aggregates`() {
        val summaries = listOf(
            summary("read_files", j("""{"file_paths":["a.ts"]}""")),
            summary("read_files", j("""{"file_paths":["b.ts","c.ts"]}""")),
            summary("run_commands", j("""{"commands":["bun test"]}""")),
        )
        assertEquals("Read 3 files · Ran 1 command", grouped(summaries.map { it.groupInput() }))
    }

    @Test fun `keeps single-call labels verbatim`() {
        val result = summary("read_files", j("""{"files":[{"path":"a.ts","start_line":1,"end_line":5}]}"""))
        assertEquals("Read file a.ts (1–5)", grouped(listOf(result.groupInput())))
    }

    @Test fun `merges input-less fallback summaries into grouped counts`() {
        val spawns = List(3) { summary("subagent_subagent", j("""{"prompt":"Investigate"}"""), j("""{"text":"Done"}""")) }
        assertEquals("Spawned 3 agents", grouped(spawns.map { it.groupInput() }))
    }

    @Test fun `uses progress verbs when any merged call is in progress`() {
        val done = summary("read_files", j("""{"file_paths":["a.ts"]}"""))
        val running = summary("read_files", j("""{"file_paths":["b.ts"]}"""))
        assertEquals("Reading 2 files", grouped(listOf(done.groupInput(), running.groupInput(inProgress = true))))
    }

    // buildReadFilesKeys (ported from CLI)

    @Test fun `produces unique keys when the same path is read twice`() {
        val keys = buildReadFilesKeys(parseReadFilesInput(j("""{"files":[{"path":"/a/SKILL.md"},{"path":"/a/SKILL.md"}]}"""))!!.files)
        assertEquals(2, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test fun `produces unique keys for duplicate paths from the file_paths shape`() {
        val keys = buildReadFilesKeys(parseReadFilesInput(j("""{"file_paths":["/a/SKILL.md","/a/SKILL.md","/b/SKILL.md"]}"""))!!.files)
        assertEquals(3, keys.size)
        assertEquals(keys.size, keys.toSet().size)
    }

    // tool output content

    @Test fun `extracts text with real newlines from the MCP CallToolResult shape`() {
        assertEquals(
            "# Memory\n\nline one\nline two",
            extractOutputText(j("""{"content":[{"type":"text","text":"# Memory\n\nline one"},{"type":"text","text":"line two"}]}""")),
        )
    }

    @Test fun `keeps binary payloads out of mixed MCP output text`() {
        val raw = j(
            """{"content":[{"type":"text","text":"before"},{"type":"image","data":"aGVsbG8=","mimeType":"image/png"},
            {"type":"resource","resource":{"uri":"file:///a.md","blob":"d29ybGQ="}},{"type":"resource_link","uri":"file:///b.md","name":"b.md"},
            {"type":"text","text":"after"}]}""",
        )
        assertEquals("before\n[image: image/png]\n[resource: file:///a.md]\nd29ybGQ=\n[resource_link: file:///b.md]\nafter", extractOutputText(raw))
    }

    @Test fun `extracts direct content-block arrays without serializing image data`() {
        val raw = j("""[{"type":"text","text":"Screenshot captured"},{"type":"image","data":"aGVsbG8=","mediaType":"image/png"}]""")
        assertEquals("Screenshot captured\n[image: image/png]", extractOutputText(raw))
        assertFalse(extractOutputText(raw)!!.contains("aGVsbG8="))
    }

    @Test fun `extracts, validates, and deduplicates inline image results`() {
        val raw = j(
            """{"content":[{"type":"image","data":"aGVsbG8=","mimeType":"image/png"},{"type":"image","data":"aGVsbG8=","mediaType":"image/png"},
            {"type":"image","data":"not-base64","mimeType":"image/png"}]}""",
        )
        assertEquals(listOf(ToolOutputMedia(MediaModality.Image, "image/png", "aGVsbG8=")), extractOutputMedia(raw))
        assertEquals(1, summary("computer", result = raw).outputMedia.size)
    }

    @Test fun `extracts every distinct image from a multi-screenshot tool result`() {
        val raw = j(
            """{"content":[{"type":"image","data":"aGVsbG8=","mimeType":"image/png"},{"type":"image","data":"d29ybGQ=","mimeType":"image/png"},
            {"type":"image","data":"Zmlyc3Q=","mimeType":"image/jpeg"}]}""",
        )
        assertEquals(
            listOf(
                ToolOutputMedia(MediaModality.Image, "image/png", "aGVsbG8="),
                ToolOutputMedia(MediaModality.Image, "image/png", "d29ybGQ="),
                ToolOutputMedia(MediaModality.Image, "image/jpeg", "Zmlyc3Q="),
            ),
            extractOutputMedia(raw),
        )
        assertEquals(3, summary("computer", result = raw).outputMedia.size)
    }

    @Test fun `extracts inline audio using the mime_type field`() {
        assertEquals(
            listOf(ToolOutputMedia(MediaModality.Audio, "audio/mpeg", "d29ybGQ=")),
            extractOutputMedia(j("""{"content":[{"type":"audio","data":"d29ybGQ=","mime_type":"audio/mpeg"}]}""")),
        )
    }

    @Test fun `extracts canonical media blocks for video and file modalities`() {
        val raw = j(
            """{"content":[{"type":"media","media":{"modality":"video","mediaType":"video/mp4","name":"clip.mp4","source":{"type":"base64","data":"d29ybGQ="}}},
            {"type":"media","media":{"modality":"file","mediaType":"application/pdf","name":"report.pdf","source":{"type":"base64","data":"Zmlyc3Q="}}}]}""",
        )
        assertEquals(
            listOf(
                ToolOutputMedia(MediaModality.Video, "video/mp4", "d29ybGQ=", "clip.mp4"),
                ToolOutputMedia(MediaModality.File, "application/pdf", "Zmlyc3Q=", "report.pdf"),
            ),
            extractOutputMedia(raw),
        )
    }

    @Test fun `rejects a canonical image media block with invalid base64`() {
        val raw = j("""{"content":[{"type":"media","media":{"modality":"image","mediaType":"image/png","source":{"type":"base64","data":"not-base64"}}}]}""")
        assertEquals(emptyList<ToolOutputMedia>(), extractOutputMedia(raw))
    }

    @Test fun `extracts embedded resource text from MCP content`() {
        assertEquals(
            "resource body\nline 2",
            extractOutputText(j("""{"content":[{"type":"resource","resource":{"uri":"file:///memory.md","text":"resource body\nline 2"}}]}""")),
        )
    }

    @Test fun `falls back to pretty JSON for objects without text content`() {
        assertEquals("{\n  \"structuredContent\": {\n    \"ok\": true\n  }\n}", extractOutputText(j("""{"structuredContent":{"ok":true}}""")))
    }

    @Test fun `extracts text from result arrays`() {
        assertEquals("line", extractOutputText(j("""[{"result":"line"}]""")))
    }

    @Test fun `keeps short paths and shortens long ones from the tail`() {
        assertEquals("src/a.ts", shortenPath("src/a.ts"))
        val short = shortenPath("one/two/three/four/five/six/seven/eight/nine/file-name.ts")
        assertTrue(short.length <= 50)
        assertTrue(short.startsWith(".../"))
        assertTrue(short.endsWith("file-name.ts"))
    }

    // Android additions: data URLs and language detection

    @Test fun `accepts a matching data URL and rejects a mismatched one`() {
        assertEquals(ValidImage("image/png", "aGVsbG8="), validateImageMedia(null, "data:image/png;base64,aGVsbG8="))
        assertNull(validateImageMedia("image/jpeg", "data:image/png;base64,aGVsbG8="))
        assertNull(validateImageMedia("image/bmp", "aGVsbG8="))
    }

    @Test fun `detects languages from extensions and well-known names`() {
        assertEquals("kotlin", detectLanguage("app/Main.kt"))
        assertEquals("dockerfile", detectLanguage("ops/Dockerfile"))
        assertEquals("cmake", detectLanguage("CMakeLists.txt"))
        assertNull(detectLanguage("LICENSE"))
    }
}
