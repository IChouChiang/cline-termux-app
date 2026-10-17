package dev.cline.termux.data.diff

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Desktop `webview/lib/session-diff.test.ts` cases first, then the Android port's own edge cases. */
class SessionDiffTest {

    private fun editorCreateEvent(path: String, newText: String) = ToolResultEvent(
        toolName = "editor",
        toolInput = buildJsonObject {
            put("path", path)
            put("new_text", newText)
        },
        toolOutput = buildJsonObject {
            put("success", true)
            put("result", "File created: $path")
        },
        toolError = null,
    )

    private fun editorReplaceEvent(path: String, diffBody: String) = ToolResultEvent(
        toolName = "editor",
        toolInput = buildJsonObject {
            put("path", path)
            put("old_text", "before")
            put("new_text", "after")
        },
        toolOutput = buildJsonObject {
            put("success", true)
            put("result", "Edited $path\n```diff\n$diffBody\n```")
        },
        toolError = null,
    )

    private fun applyPatchEvent(patch: JsonElement, output: JsonElement? = buildJsonObject { put("success", true) }, error: String? = null) =
        ToolResultEvent(toolName = "apply_patch", toolInput = patch, toolOutput = output, toolError = error)

    // region desktop session-diff.test.ts

    @Test
    fun `merges relative and absolute spellings of the same file into one entry`() {
        val merged = SessionDiff.mergeToolDiffs(
            listOf(
                editorCreateEvent("journal.txt", "line one\nline two"),
                editorReplaceEvent("/tmp/qa-ws/journal.txt", "+3: line three"),
            ),
            "/tmp/qa-ws",
        )
        assertEquals(1, merged.size)
        assertEquals("journal.txt", merged[0].path)
        assertEquals(3, merged[0].additions)
        assertEquals(0, merged[0].deletions)
        assertEquals(2, merged[0].hunks.size)
    }

    @Test
    fun `collapses parent segments so escaped spellings merge with the absolute path`() {
        val merged = SessionDiff.mergeToolDiffs(
            listOf(
                editorCreateEvent("../shared/config.txt", "x"),
                editorReplaceEvent("/tmp/shared/config.txt", "+2: y"),
            ),
            "/tmp/qa-ws",
        )
        assertEquals(1, merged.size)
        assertEquals("/tmp/shared/config.txt", merged[0].path)
        assertEquals(2, merged[0].additions)
        assertEquals(0, merged[0].deletions)
    }

    @Test
    fun `collapses redundant segments inside the workspace`() {
        val merged = SessionDiff.mergeToolDiffs(
            listOf(
                editorCreateEvent("sub/../journal.txt", "one"),
                editorReplaceEvent("/tmp/qa-ws/./journal.txt", "+2: two"),
            ),
            "/tmp/qa-ws",
        )
        assertEquals(1, merged.size)
        assertEquals("journal.txt", merged[0].path)
        assertEquals(2, merged[0].additions)
    }

    @Test
    fun `keeps canonicalizing when the cwd is the filesystem root`() {
        val merged = SessionDiff.mergeToolDiffs(
            listOf(editorCreateEvent("notes.txt", "one"), editorReplaceEvent("/notes.txt", "+2: two")),
            "/",
        )
        assertEquals(1, merged.size)
        assertEquals("notes.txt", merged[0].path)
        assertEquals(2, merged[0].additions)
    }

    @Test
    fun `merges Windows casing variants of the same file`() {
        val merged = SessionDiff.mergeToolDiffs(
            listOf(
                editorCreateEvent("Journal.txt", "one"),
                editorReplaceEvent("c:\\users\\saoud\\journal.txt", "+2: two"),
            ),
            "C:\\Users\\Saoud",
        )
        assertEquals(1, merged.size)
        assertEquals("Journal.txt", merged[0].path)
        assertEquals(2, merged[0].additions)
    }

    @Test
    fun `keeps files outside the cwd on their resolved path`() {
        val merged = SessionDiff.mergeToolDiffs(listOf(editorCreateEvent("/etc/other/config.txt", "x")), "/tmp/qa-ws")
        assertEquals(listOf("/etc/other/config.txt"), merged.map { it.path })
    }

    @Test
    fun `keeps distinct files separate`() {
        val merged = SessionDiff.mergeToolDiffs(
            listOf(editorCreateEvent("a.txt", "one"), editorCreateEvent("/tmp/qa-ws/b.txt", "two")),
            "/tmp/qa-ws",
        )
        assertEquals(listOf("a.txt", "b.txt"), merged.map { it.path }.sorted())
    }

    @Test
    fun `falls back to raw path keys without a cwd`() {
        val merged = SessionDiff.mergeToolDiffs(
            listOf(editorCreateEvent("journal.txt", "one"), editorCreateEvent("/tmp/qa-ws/journal.txt", "two")),
            null,
        )
        assertEquals(2, merged.size)
    }

    @Test
    fun `sums the summary across canonicalized entries`() {
        val state = SessionDiff.build(
            listOf(
                editorCreateEvent("journal.txt", "line one\nline two"),
                editorReplaceEvent("/tmp/qa-ws/journal.txt", "+3: line three"),
            ),
            "/tmp/qa-ws",
        )
        assertEquals(SessionDiffSummary(3, 0), state.summary)
    }

    // endregion

    // region desktop workspace-paths.test.ts resolveWorkspaceFilePath

    @Test
    fun `resolves workspace file paths like the desktop`() {
        assertEquals("/Users/renee/cline/docs/a.mdx", SessionDiff.resolveAbsolutePath("docs/a.mdx", "/Users/renee/cline"))
        assertEquals("/Users/renee/cline/docs/a.mdx", SessionDiff.resolveAbsolutePath("./docs/a.mdx", "/Users/renee/cline/"))
        assertEquals("/Users/renee/cline/docs/a.mdx", SessionDiff.resolveAbsolutePath("/Users/renee/cline/docs/a.mdx", "/elsewhere"))
        assertEquals("docs/a.mdx", SessionDiff.resolveAbsolutePath("docs/a.mdx", null))
        assertEquals("C:\\Users\\renee\\docs\\a.mdx", SessionDiff.resolveAbsolutePath("docs\\a.mdx", "C:\\Users\\renee"))
    }

    // endregion

    // region editor parsing

    @Test
    fun `editor str_replace result yields one hunk with the editor's line numbers`() {
        val files = SessionDiff.mergeToolDiffs(
            listOf(editorReplaceEvent("src/a.kt", "-3: val x = 1\n+3: val x = 2\n+4: val y = 3")),
            "/ws",
        )
        val file = files.single()
        assertEquals(2, file.additions)
        assertEquals(1, file.deletions)
        val hunk = file.hunks.single()
        assertEquals(SessionDiffHunk(oldStart = 3, newStart = 3, old = "val x = 1", new = "val x = 2\nval y = 3"), hunk)
        assertFalse(hunk.isCompleteNewContents)
    }

    @Test
    fun `editor create is complete new contents and counts non-empty lines`() {
        val file = SessionDiff.mergeToolDiffs(listOf(editorCreateEvent("hello.py", "print(1)\n\nprint(2)\n")), null).single()
        assertEquals(2, file.additions)
        assertTrue(file.hunks.single().isCompleteNewContents)
    }

    @Test
    fun `editor insert and file_text and legacy command shapes`() {
        val insert = ToolResultEvent(
            "editor",
            buildJsonObject {
                put("path", "a.txt")
                put("insert_line", 4)
                put("new_text", "inserted")
            },
            buildJsonObject { put("success", true) },
            null,
        )
        val legacy = ToolResultEvent(
            "editor",
            buildJsonObject {
                put("command", "create")
                put("path", "b.txt")
                put("file_text", "one\ntwo")
            },
            buildJsonObject { put("success", true) },
            null,
        )
        val files = SessionDiff.mergeToolDiffs(listOf(insert, legacy), null)
        assertEquals(listOf("a.txt" to 1, "b.txt" to 2), files.map { it.path to it.additions })
    }

    @Test
    fun `editor path falls back to the output query`() {
        val event = ToolResultEvent(
            "editor",
            buildJsonObject { put("new_text", "x") },
            buildJsonObject {
                put("success", true)
                put("query", "create:notes/a:b.md")
            },
            null,
        )
        assertEquals("notes/a:b.md", SessionDiff.mergeToolDiffs(listOf(event), null).single().path)
    }

    @Test
    fun `failed rejected and unrelated tools never count`() {
        val failed = ToolResultEvent(
            "editor",
            buildJsonObject {
                put("path", "a.txt")
                put("new_text", "x")
            },
            buildJsonObject { put("success", false) },
            null,
        )
        val rejected = editorCreateEvent("b.txt", "x").copy(toolError = "This tool call was rejected by the user")
        val read = editorCreateEvent("c.txt", "x").copy(toolName = "read_files")
        assertTrue(SessionDiff.mergeToolDiffs(listOf(failed, rejected, read), "/ws").isEmpty())
    }

    @Test
    fun `str_replace without a diff block has no hunks but still lists the file`() {
        val file = SessionDiff.mergeToolDiffs(listOf(editorReplaceEvent("a.txt", "no numbered lines")), null).single()
        assertEquals(0, file.additions)
        assertTrue(file.hunks.isEmpty())
    }

    // endregion

    // region tool output shapes

    @Test
    fun `tool output may be a JSON string or a content block array`() {
        val input = buildJsonObject {
            put("path", "a.txt")
            put("new_text", "x")
        }
        val asString = ToolResultEvent("editor", input, JsonPrimitive("""{"success":true,"result":"File created: a.txt"}"""), null)
        val asBlocks = ToolResultEvent(
            "editor",
            input,
            buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "not json") })
                add(buildJsonObject { put("type", "text"); put("text", """{"success":true}""") })
            },
            null,
        )
        val failedBlocks = ToolResultEvent(
            "editor",
            input,
            buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", """{"success":false}""") }) },
            null,
        )
        assertEquals(1, SessionDiff.mergeToolDiffs(listOf(asString), null).size)
        assertEquals(1, SessionDiff.mergeToolDiffs(listOf(asBlocks), null).size)
        assertTrue(SessionDiff.mergeToolDiffs(listOf(failedBlocks), null).isEmpty())
        assertNull(SessionDiff.normalizeToolOutput(JsonPrimitive("not json")))
        assertNull(SessionDiff.normalizeToolOutput(JsonArray(emptyList())))
        assertNull(SessionDiff.normalizeToolOutput(null))
    }

    // endregion

    // region apply_patch

    private val updatePatch = """
        *** Begin Patch
        *** Update File: src/app.ts
        @@ function main
         const a = 1;
        -const b = 2;
        +const b = 3;
        +const c = 4;
         const d = 5;
        -const e = 6;
        *** End of File
        *** Add File: docs/new.md
        +# Title
        +
        +Body
        *** Delete File: old.txt
        *** End Patch
    """.trimIndent()

    @Test
    fun `parses update add and delete sections`() {
        val files = SessionDiff.parseApplyPatchInput(updatePatch)
        assertEquals(listOf("src/app.ts", "docs/new.md", "old.txt"), files.map { it.path })

        val update = files[0]
        assertEquals(2, update.additions)
        assertEquals(2, update.deletions)
        assertEquals(
            listOf(
                SessionDiffHunk(oldStart = 2, newStart = 2, old = "const b = 2;", new = "const b = 3;\nconst c = 4;"),
                SessionDiffHunk(oldStart = 4, newStart = 5, old = "const e = 6;", new = ""),
            ),
            update.hunks,
        )

        val add = files[1]
        assertEquals(3, add.additions)
        assertEquals(SessionDiffHunk(1, 1, "", "# Title\n\nBody"), add.hunks.single())
        assertTrue(add.hunks.single().isCompleteNewContents)

        assertEquals(SessionFileDiff("old.txt", 0, 0, emptyList()), files[2])
    }

    @Test
    fun `strips bash heredoc wrappers outside the patch and CRLF endings`() {
        val wrapped = "%%bash\napply_patch <<\"EOF\"\r\n*** Begin Patch\r\n*** Add File: a.txt\r\n+hi\r\n*** End Patch\r\nEOF\n```"
        val files = SessionDiff.parseApplyPatchInput(wrapped)
        assertEquals(SessionFileDiff("a.txt", 1, 0, listOf(SessionDiffHunk(1, 1, "", "hi"))), files.single())
    }

    @Test
    fun `apply_patch accepts a raw string or an input record and skips failures`() {
        val raw = applyPatchEvent(JsonPrimitive(updatePatch))
        val record = applyPatchEvent(buildJsonObject { put("input", updatePatch) })
        val failed = applyPatchEvent(JsonPrimitive(updatePatch), output = buildJsonObject { put("success", false) })
        val errored = applyPatchEvent(JsonPrimitive(updatePatch), error = "patch failed")
        assertEquals(3, SessionDiff.mergeToolDiffs(listOf(raw), null).size)
        assertEquals(3, SessionDiff.mergeToolDiffs(listOf(record), null).size)
        assertTrue(SessionDiff.mergeToolDiffs(listOf(failed, errored), null).isEmpty())
    }

    @Test
    fun `keeps the last 30 hunks per file and flags the truncation while counts keep summing`() {
        val events = (1..35).map { editorReplaceEvent("a.txt", "-$it: old $it\n+$it: new $it") }
        val file = SessionDiff.mergeToolDiffs(events, "/ws").single()
        assertEquals(30, file.hunks.size)
        assertEquals("old 6", file.hunks.first().old)
        assertEquals(35, file.additions)
        assertEquals(35, file.deletions)
        assertTrue(file.hunksTruncated)

        val few = SessionDiff.mergeToolDiffs(events.take(30), "/ws").single()
        assertFalse(few.hunksTruncated)
    }

    @Test
    fun `files keep first-seen order and the first spelling`() {
        val files = SessionDiff.mergeToolDiffs(
            listOf(
                editorCreateEvent("b.txt", "1"),
                editorCreateEvent("a.txt", "1"),
                editorReplaceEvent("/ws/b.txt", "+2: 2"),
            ),
            "/ws",
        )
        assertEquals(listOf("b.txt", "a.txt"), files.map { it.path })
    }

    @Test
    fun `collapsePathSegments handles roots and leading parents`() {
        assertEquals("/a/c", SessionDiff.collapsePathSegments("/a/b/../c", "/"))
        assertEquals("/c", SessionDiff.collapsePathSegments("/../../c", "/"))
        assertEquals("../c", SessionDiff.collapsePathSegments("../c", "/"))
        assertEquals("C:\\x\\y", SessionDiff.collapsePathSegments("C:/x/./y", "\\"))
        assertEquals("\\\\server\\share", SessionDiff.collapsePathSegments("\\\\server\\share\\.", "\\"))
    }

    // endregion
}
