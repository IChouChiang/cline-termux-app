package dev.cline.termux.ui.approval

import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** composer-approvals.md C3.5 previews and the desktop `tool-approval-panel.tsx` formatting functions. */
class ApprovalPreviewTest {
    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun lines(tool: String, input: String): List<PreviewLine> =
        (ApprovalPreviews.build(tool, json(input)) as ApprovalPreview.Lines).lines

    private fun texts(tool: String, input: String): List<String> =
        lines(tool, input).map { it.plainText("Create file", { n -> "Insert at line $n" }, "…") }

    private fun isRaw(tool: String, input: JsonElement?) = ApprovalPreviews.build(tool, input) == ApprovalPreview.Raw

    // ---- run_commands --------------------------------------------------------------------------

    @Test
    fun `run_commands - one dollar line per command`() {
        assertEquals(listOf("$ bun test --filter parser", "$ git status"), texts("run_commands", """{"commands":["bun test --filter parser","git status"]}"""))
    }

    @Test
    fun `run_commands - recorded fixture input`() {
        // FX 02: inputJson "{\"commands\":[\"echo hello-from-mock\"]}"
        assertEquals(listOf("$ echo hello-from-mock"), texts("run_commands", """{"commands":["echo hello-from-mock"]}"""))
    }

    @Test
    fun `run_commands - structured entries are shell-quoted when an arg contains spaces`() {
        assertEquals(
            listOf("$ git commit -m 'fix the parser'", "$ ls -la"),
            texts("run_commands", """{"commands":[{"command":"git","args":["commit","-m","fix the parser"]},{"command":"ls","args":["-la"]}]}"""),
        )
    }

    @Test
    fun `run_commands - single quotes inside a quoted arg are escaped`() {
        assertEquals(listOf("$ echo 'it'\\''s here'"), texts("run_commands", """{"command":"echo","args":["it's here"]}"""))
    }

    @Test
    fun `run_commands - bare string, command, cmd, arrays and a single structured command`() {
        assertEquals(listOf("$ pwd"), texts("run_commands", "\"pwd\""))
        assertEquals(listOf("$ pwd"), texts("run_commands", """{"command":"pwd"}"""))
        assertEquals(listOf("$ pwd"), texts("run_commands", """{"cmd":"pwd"}"""))
        assertEquals(listOf("$ a", "$ b"), texts("run_commands", """["a","b"]"""))
        assertEquals(listOf("$ git status"), texts("run_commands", """[{"command":"git","args":["status"]}]"""))
        assertEquals(listOf("$ make"), texts("run_commands", """{"commands":"make"}"""))
        assertEquals(listOf("$ make all"), texts("run_commands", """{"commands":{"command":"make","args":["all"]}}"""))
    }

    @Test
    fun `run_commands - a JSON string input is re-parsed`() {
        assertEquals(listOf("$ ls"), lines("run_commands", "\"{\\\"commands\\\":[\\\"ls\\\"]}\"").map { it.plainText("", { "" }, "") })
    }

    @Test
    fun `run_commands - unexpected shapes fall back to raw`() {
        assertEquals(true, isRaw("run_commands", json("""{"commands":[42]}""")))
        assertEquals(true, isRaw("run_commands", json("""{"other":"x"}""")))
        assertEquals(true, isRaw("run_commands", json("""{"command":"git","args":"status"}""")))
        assertEquals(true, isRaw("run_commands", null))
    }

    @Test
    fun `aliases are normalized before choosing the preview`() {
        assertEquals(listOf("$ ls"), texts("bash", """{"commands":["ls"]}"""))
        assertEquals(listOf("/w/a.ts"), texts("read_file", """{"path":"/w/a.ts"}"""))
    }

    @Test
    fun `shellQuote leaves plain args alone and quotes empty and spaced ones`() {
        assertEquals("--flag", ApprovalPreviews.shellQuote("--flag"))
        assertEquals("''", ApprovalPreviews.shellQuote(""))
        assertEquals("'a b'", ApprovalPreviews.shellQuote("a b"))
        assertEquals("'a\tb'", ApprovalPreviews.shellQuote("a\tb"))
    }

    // ---- read_files ------------------------------------------------------------------------------

    @Test
    fun `read_files - paths with ranges`() {
        assertEquals(
            listOf("/w/app.tsx (10–80)", "/w/README.md", "/w/b.ts (5+)", "/w/c.ts (1–9)"),
            texts(
                "read_files",
                """{"files":[{"path":"/w/app.tsx","start_line":10,"end_line":80},{"path":"/w/README.md"},{"path":"/w/b.ts","start_line":5},{"path":"/w/c.ts","end_line":9}]}""",
            ),
        )
    }

    @Test
    fun `read_files - every union variant`() {
        assertEquals(listOf("/a"), texts("read_files", "\"/a\""))
        assertEquals(listOf("/a", "/b"), texts("read_files", """["/a","/b"]"""))
        assertEquals(listOf("/a (3–4)"), texts("read_files", """{"file_path":"/a","start_line":"3","end_line":4}"""))
        assertEquals(listOf("/a"), texts("read_files", """{"filePath":"/a"}"""))
        assertEquals(listOf("/a", "/b"), texts("read_files", """{"file_paths":["/a","/b"]}"""))
        assertEquals(listOf("/a"), texts("read_files", """{"file_paths":"/a"}"""))
        assertEquals(listOf("/a", "/b (2–3)"), texts("read_files", """{"paths":["/a",{"path":"/b","start_line":2,"end_line":3}]}"""))
        assertEquals(listOf("/a"), texts("read_files", """{"files":"/a"}"""))
        assertEquals(listOf("/a"), texts("read_files", """{"files":{"path":"/a","start_line":null}}"""))
    }

    @Test
    fun `read_files - entries without a path fall back to raw`() {
        assertEquals(true, isRaw("read_files", json("""{"files":[{"start_line":1}]}""")))
        assertEquals(true, isRaw("read_files", json("""{"files":[]}""")))
    }

    // ---- editor --------------------------------------------------------------------------------------

    @Test
    fun `editor - replacement shows removed then added lines`() {
        assertEquals(
            listOf("src/parser.ts", "- const x = parse(a)", "+ const x = parse(a, { strict })"),
            texts("editor", """{"path":"src/parser.ts","old_text":"const x = parse(a)","new_text":"const x = parse(a, { strict })"}"""),
        )
        val l = lines("editor", """{"path":"p","old_text":"a","new_text":"b"}""")
        assertEquals(PreviewLine.Path("p"), l[0])
        assertEquals(PreviewLine.Removed("a"), l[1])
        assertEquals(PreviewLine.Added("b"), l[2])
    }

    @Test
    fun `editor - missing or null old_text creates the file`() {
        assertEquals(listOf("p", "Create file", "+ one", "+ two"), texts("editor", """{"path":"p","new_text":"one\ntwo"}"""))
        assertEquals(listOf("p", "Create file", "+ x"), texts("editor", """{"path":"p","old_text":null,"new_text":"x"}"""))
    }

    @Test
    fun `editor - insert_line wins and is coerced from a string`() {
        assertEquals(listOf("p", "Insert at line 12", "+ x"), texts("editor", """{"path":"p","old_text":"ignored","new_text":"x","insert_line":12}"""))
        assertEquals(listOf("p", "Insert at line 3", "+ x"), texts("editor", """{"path":"p","new_text":"x","insert_line":"3"}"""))
    }

    @Test
    fun `editor - each side is capped at 40 lines then an ellipsis`() {
        val old = (1..45).joinToString("\\n") { "o$it" }
        val new = (1..41).joinToString("\\n") { "n$it" }
        val l = lines("editor", """{"path":"p","old_text":"$old","new_text":"$new"}""")
        assertEquals(1 + 40 + 1 + 40 + 1, l.size)
        assertEquals(PreviewLine.Removed("o40"), l[40])
        assertEquals(PreviewLine.More, l[41])
        assertEquals(PreviewLine.Added("n1"), l[42])
        assertEquals(PreviewLine.More, l.last())
    }

    @Test
    fun `editor - malformed input falls back to raw`() {
        assertEquals(true, isRaw("editor", json("""{"path":"p"}""")))
        assertEquals(true, isRaw("editor", json("""{"new_text":"x"}""")))
        assertEquals(true, isRaw("editor", json("""{"path":"p","new_text":"x","old_text":5}""")))
        assertEquals(true, isRaw("editor", json("""{"path":"p","new_text":"x","insert_line":"top"}""")))
    }

    // ---- apply_patch, fetch, search ------------------------------------------------------------------

    @Test
    fun `apply_patch - headers, verbatim diff lines and context`() {
        val l = lines("apply_patch", """{"input":"*** Begin Patch\n*** Update File: a.ts\n@@\n-const a = 1\n+const a = 2\n keep\n*** End Patch\n"}""")
        assertEquals(
            listOf(
                PreviewLine.Header("*** Begin Patch"),
                PreviewLine.Header("*** Update File: a.ts"),
                PreviewLine.Header("@@"),
                PreviewLine.Removed("-const a = 1", verbatim = true),
                PreviewLine.Added("+const a = 2", verbatim = true),
                PreviewLine.Context(" keep"),
                PreviewLine.Header("*** End Patch"),
            ),
            l,
        )
        assertEquals("+const a = 2", l[4].plainText("", { "" }, ""))
        assertEquals(PreviewLine.Header("*** Begin Patch"), lines("apply_patch", "\"*** Begin Patch\"").single())
        assertEquals(true, isRaw("apply_patch", json("""{"patch":"x"}""")))
    }

    @Test
    fun `fetch_web_content - url then prompt per request`() {
        assertEquals(
            listOf(PreviewLine.Url("https://a.dev"), PreviewLine.Prompt("Summarize"), PreviewLine.Url("https://b.dev")),
            lines("fetch_web_content", """{"requests":[{"url":"https://a.dev","prompt":"Summarize"},{"url":"https://b.dev"}]}"""),
        )
        assertEquals(true, isRaw("fetch_web_content", json("""{"requests":[{"prompt":"x"}]}""")))
    }

    @Test
    fun `search_codebase - one line per query in every variant`() {
        assertEquals(listOf("foo", "bar"), texts("search_codebase", """{"queries":["foo","bar"]}"""))
        assertEquals(listOf("foo"), texts("search_codebase", """{"queries":"foo"}"""))
        assertEquals(listOf("foo"), texts("search_codebase", """["foo"]"""))
        assertEquals(listOf("foo"), texts("search_codebase", "\"foo\""))
        assertEquals(true, isRaw("search_codebase", json("""{"q":"foo"}""")))
    }

    @Test
    fun `other tools show only the raw JSON`() {
        listOf("mcp_github_create_issue", "spawn_agent", "team_create", "skills", "tasks").forEach {
            assertEquals(it, true, isRaw(it, json("""{"x":1}""")))
        }
    }

    // ---- desktop formatting functions -------------------------------------------------------------

    @Test
    fun `formatApprovalInput - null, string and pretty JSON with two spaces`() {
        assertEquals("{}", formatApprovalInput(null))
        assertEquals("{}", formatApprovalInput(JsonNull))
        assertEquals("rm -rf build", formatApprovalInput(JsonPrimitive("rm -rf build")))
        assertEquals("{\n  \"commands\": [\n    \"echo hello\"\n  ]\n}", formatApprovalInput(json("""{"commands":["echo hello"]}""")))
        assertEquals("42", formatApprovalInput(JsonPrimitive(42)))
    }

    @Test
    fun `formatApprovalTimestamp - date and time, null when unknown`() {
        assertNull(formatApprovalTimestamp(null))
        assertNull(formatApprovalTimestamp(0))
        assertNull(formatApprovalTimestamp(-5))
        val text = formatApprovalTimestamp(1_791_064_690_209, ZoneId.of("UTC"), Locale.US)!!.replace(' ', ' ')
        assertEquals("10/3/26, 9:58 PM", text)
    }
}
