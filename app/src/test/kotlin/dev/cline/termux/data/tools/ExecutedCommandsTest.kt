package dev.cline.termux.data.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trial row 18: the commands a run_commands input runs, in the kernel's order (core helpers.ts
 * `normalizeRunCommandsInput` + `formatRunCommandQuery`, definitions.ts heredoc coalescing, the
 * runtime's JSON-like string normalization), the choice between that command and the
 * kernel's `query` echo (`formatRunCommandQueryPreview`: 200 characters, then a note), and a
 * result presented with those commands.
 */
class ExecutedCommandsTest {
    private fun j(text: String): JsonElement = Json.parseToJsonElement(text)

    /** A 300-character command and the kernel's echo of it. */
    private val long = "echo " + "x".repeat(295)
    private val echo = long.take(200) + " ... [command truncated: 100 more chars; full command is in the tool call input]"

    @Test fun `the test command is 300 characters and its echo is the kernel's capped preview`() {
        assertEquals(300, long.length)
        assertEquals(RUN_COMMAND_QUERY_PREVIEW_LIMIT, long.take(200).length)
        assertTrue(echoMatches(long, echo))
    }

    // runCommandsExecuted

    @Test fun `every input shape the kernel accepts gives its commands in order`() {
        assertEquals(listOf("ls", "pwd"), runCommandsExecuted(j("""{"commands":["ls","pwd"]}""")))
        assertEquals(listOf("ls"), runCommandsExecuted(j("""{"commands":"ls"}""")))
        assertEquals(listOf("ls -la"), runCommandsExecuted(j("""{"commands":{"command":"ls","args":["-la"]}}""")))
        assertEquals(listOf("ls", "git log"), runCommandsExecuted(j("""{"commands":["ls",{"command":"git","args":["log"]}]}""")))
        assertEquals(listOf("ls -la", "pwd"), runCommandsExecuted(j("""[{"command":"ls","args":["-la"]},{"command":"pwd"}]""")))
        assertEquals(listOf("ls a"), runCommandsExecuted(j("""{"command":"ls","args":["a"]}""")))
        assertEquals(listOf("ls -la"), runCommandsExecuted(j("""{"command":"ls -la"}""")))
        // `args: null` fails the structured shape; `{command}` still accepts the call.
        assertEquals(listOf("ls"), runCommandsExecuted(j("""{"command":"ls","args":null}""")))
        assertEquals(listOf("pwd"), runCommandsExecuted(j("""{"cmd":"pwd"}""")))
        assertEquals(listOf("ls", "pwd"), runCommandsExecuted(j("""["ls","pwd"]""")))
        assertEquals(listOf("ls"), runCommandsExecuted(JsonPrimitive("ls")))
        assertEquals(emptyList<String>(), runCommandsExecuted(j("[]")))
    }

    @Test fun `no input, or one no shape accepts, runs nothing`() {
        assertNull(runCommandsExecuted(null))
        assertNull(runCommandsExecuted(j("""{"files":["a"]}""")))
        assertNull(runCommandsExecuted(j("""{"commands":[1,2]}""")))
        assertNull(runCommandsExecuted(j("42")))
    }

    @Test fun `empty commands keep their place, as the kernel runs them`() {
        assertEquals(listOf("", "ls"), runCommandsExecuted(j("""{"commands":["","ls"]}""")))
        assertEquals(listOf(""), runCommandsExecuted(j("""{"command":""}""")))
    }

    @Test fun `structured arguments with spaces or quotes are quoted as JSON strings`() {
        val input = j("""{"commands":[{"command":"echo","args":["a b","plain","x\"y"]}]}""")
        assertEquals(listOf("echo \"a b\" plain \"x\\\"y\""), runCommandsExecuted(input))
    }

    @Test fun `a heredoc split over entries is one command`() {
        val input = j("""{"commands":["cat > notes.txt <<'EOF'","line one","EOF","ls"]}""")
        assertEquals(listOf("cat > notes.txt <<'EOF'\nline one\nEOF", "ls"), runCommandsExecuted(input))
        val dashed = j("""{"commands":["cat <<-END","\tbody","\tEND"]}""")
        assertEquals(listOf("cat <<-END\n\tbody\n\tEND"), runCommandsExecuted(dashed))
    }

    @Test fun `an unterminated heredoc, a here-string and a structured call never join entries`() {
        assertEquals(listOf("cat <<EOF", "x"), runCommandsExecuted(j("""{"commands":["cat <<EOF","x"]}""")))
        assertEquals(listOf("cat <<< hi", "hi"), runCommandsExecuted(j("""{"commands":["cat <<< hi","hi"]}""")))
        assertEquals(listOf("cat <<EOF", "ls", "EOF"), runCommandsExecuted(j("""{"commands":["cat <<EOF",{"command":"ls"},"EOF"]}""")))
    }

    @Test fun `JSON-like strings are parsed as the runtime parses them before the tool runs`() {
        assertEquals(listOf("ls", "pwd"), runCommandsExecuted(j("""{"commands":"[\"ls\",\"pwd\"]"}""")))
        assertEquals(listOf("ls"), runCommandsExecuted(JsonPrimitive("""{"commands":["ls"]}""")))
        // The schema's top level is an object, so an array string stays one shell command.
        assertEquals(listOf("""["ls"]"""), runCommandsExecuted(JsonPrimitive("""["ls"]""")))
        // Not JSON: a shell test that starts with a bracket.
        assertEquals(listOf("[ -f a ] && echo yes"), runCommandsExecuted(j("""{"commands":"[ -f a ] && echo yes"}""")))
    }

    // executedCommand

    @Test fun `the input's command replaces the capped echo`() {
        val commands = listOf("ls", long)
        assertEquals(long, executedCommand(commands, 1, echo))
        assertEquals("ls", executedCommand(commands, 0, "ls"))
    }

    @Test fun `without the input the echo stands in, its note replaced by an ellipsis`() {
        assertEquals(long.take(200) + "…", executedCommand(null, 1, echo))
        assertEquals("ls", executedCommand(null, 0, "ls"))
        assertEquals("", executedCommand(null, 0, null))
    }

    @Test fun `an input that does not match the echo never lends another command`() {
        val commands = listOf("ls", "pwd")
        assertEquals("whoami", executedCommand(commands, 1, "whoami"))
        assertEquals(long.take(200) + "…", executedCommand(commands, 3, echo))
        // A capped echo matches only a command of the length it was cut from.
        assertEquals(long.take(200) + "…", executedCommand(listOf(long.take(200) + "zzz"), 0, echo))
    }

    @Test fun `with no echo the input's command is printed`() {
        assertEquals("pwd", executedCommand(listOf("ls", "pwd"), 1, null))
        assertEquals("pwd", executedCommand(listOf("ls", "pwd"), 1, ""))
    }

    @Test fun `a command the kernel echoed whole prints exactly as it ran`() {
        assertEquals("cat <<EOF\nbody\nEOF\n", executedCommand(listOf("cat <<EOF\nbody\nEOF\n"), 0, "cat <<EOF\nbody\nEOF\n"))
        assertEquals("  ls  ", executedCommand(null, 0, "  ls  "))
    }

    @Test fun `the note is recognised only in the kernel's wording at the end of the echo`() {
        assertEquals("abc", withoutQueryPreviewNote("abc"))
        assertEquals("abc…", withoutQueryPreviewNote("abc ... [command truncated: 5 more chars; full command is in the tool call input]"))
        // A cut that ends in spaces keeps the ellipsis next to the text.
        assertEquals("abc…", withoutQueryPreviewNote("abc   ... [command truncated: 5 more chars; full command is in the tool call input]"))
        val quoted = "echo '... [command truncated: 5 more chars; full command is in the tool call input]' done"
        assertEquals(quoted, withoutQueryPreviewNote(quoted))
        assertFalse(executedCommand(listOf("ls", long), 1, echo).contains("command truncated"))
    }

    // withExecutedCommands

    @Test fun `a result's records carry the input's commands, other values pass untouched`() {
        val input = j("""{"commands":["ls",${JsonPrimitive(long)}]}""")
        val result = j("""[{"query":"ls","result":"a"},{"query":${JsonPrimitive(echo)},"result":"","success":true}]""")
        assertEquals(
            j("""[{"query":"ls","result":"a"},{"query":${JsonPrimitive(long)},"result":"","success":true}]"""),
            withExecutedCommands(result, input),
        )
        // Nothing to replace: the very same value.
        val short = j("""[{"query":"ls","result":"a"}]""")
        assertTrue(withExecutedCommands(short, j("""{"commands":["ls"]}""")) === short)
        val plain = JsonPrimitive("plain output")
        assertTrue(withExecutedCommands(plain, input) === plain)
        assertNull(withExecutedCommands(null, input))
    }

    @Test fun `without an input only the note goes, and a JSON string result is read as its records`() {
        val result = j("""[{"query":${JsonPrimitive(echo)},"result":""}]""")
        assertEquals(j("""[{"query":${JsonPrimitive(long.take(200) + "…")},"result":""}]"""), withExecutedCommands(result, null))
        val asString = JsonPrimitive(result.toString())
        assertEquals(
            j("""[{"query":${JsonPrimitive(long)},"result":""}]"""),
            withExecutedCommands(asString, j("""{"commands":[${JsonPrimitive(long)}]}""")),
        )
    }

    @Test fun `an echo matches its own command only`() {
        assertTrue(echoMatches("ls", "ls"))
        assertFalse(echoMatches("ls", "pwd"))
        assertFalse(echoMatches(long + "x", echo))
        assertFalse(echoMatches("y" + long.drop(1), echo))
        assertFalse(echoMatches("ls", echo))
    }
}
