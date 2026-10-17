package dev.cline.termux.core.kernel.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A11.6 / brief F5: the terminal's history file, read and appended through Termux. */
class InputHistoryCommandsTest {

    private fun line(prompt: String, ts: Long = 1L) = """{"prompt":${quote(prompt)},"ts":$ts}"""

    private fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

    @Test fun theFileIsOldestFirstAndTheListNewestFirst() {
        val file = listOf("first", "second", "third").joinToString("\n", postfix = "\n") { line(it) }
        assertEquals(listOf("third", "second", "first"), InputHistoryCommands.parse(file))
    }

    @Test fun badLinesAreSkipped() {
        val file = listOf(
            line("a"),
            "not json",
            "{\"prompt\":42}",
            "{\"prompt\":\"   \"}",
            "{\"other\":\"x\"}",
            "null",
            "[\"b\"]",
            "{prompt: lenient}",
            "",
            "   ",
            line("c"),
        ).joinToString("\n")
        assertEquals(listOf("c", "a"), InputHistoryCommands.parse(file))
    }

    @Test fun promptsAreKeptAsWrittenUntrimmed() {
        assertEquals(listOf("  spaced \n line "), InputHistoryCommands.parse(line("  spaced \n line ")))
    }

    @Test fun onlyTheLastTwentyAreKept() {
        val file = (1..25).joinToString("\n") { line("p$it") }
        val list = InputHistoryCommands.parse(file)
        assertEquals(20, list.size)
        assertEquals("p25", list.first())
        assertEquals("p6", list.last())
    }

    @Test fun aMissingFileIsAnEmptyHistory() {
        val missing = TermuxResult.failure(exitCode = 1, stderr = "cat: /x/user_input_history.jsonl: No such file or directory")
        assertEquals(emptyList<String>(), InputHistoryCommands.readOrNull(missing))
    }

    @Test fun anUnreadableOrUndeliveredReadIsUnknown() {
        assertNull(InputHistoryCommands.readOrNull(TermuxResult.failure(exitCode = 1, stderr = "cat: Permission denied")))
        assertNull(InputHistoryCommands.readOrNull(TermuxResult.undelivered(err = 150, errmsg = "no bootstrap")))
    }

    @Test fun anOkReadIsParsed() {
        assertEquals(listOf("b", "a"), InputHistoryCommands.readOrNull(TermuxResult.success(line("a") + "\n" + line("b") + "\n")))
    }

    @Test fun readCatsTheTerminalsFile() {
        val command = InputHistoryCommands.read()
        assertEquals("${TermuxPaths.BIN}/cat", command.path)
        assertEquals(listOf(HISTORY_FILE), command.args)
        assertEquals(InputHistoryCommands.LABEL_READ, command.label)
    }

    @Test fun appendSendsThePromptOnStdinOnly() {
        val prompt = "secret-ish \"prompt\"\nwith lines"
        val command = InputHistoryCommands.append(prompt)
        assertEquals("${TermuxPaths.BIN}/bun-ffi", command.path)
        assertEquals("-e", command.args.first())
        assertEquals(2, command.args.size)
        assertEquals(prompt, command.stdin)
        assertEquals(InputHistoryCommands.LABEL_APPEND, command.label)
        assertTrue(command.background)
        assertFalse("the prompt never travels in the arguments", command.args.any { it.contains("secret-ish") })
        assertFalse("the label and toString never carry the prompt", command.toString().contains("secret-ish"))
    }

    @Test fun theAppendScriptHasNoDollarAndNamesTheFileOnce() {
        val script = InputHistoryCommands.append("x").args[1]
        assertFalse(script.contains('$'))
        assertEquals(1, script.split("\"$HISTORY_FILE\"").size - 1)
        assertTrue(script.contains("process.exit(${InputHistoryCommands.EXIT_UNREADABLE})"))
        assertTrue(script.contains(".slice(0,${InputHistoryCommands.MAX_ENTRIES})"))
        assertTrue("temp file + rename", script.contains("renameSync(tmp,p)"))
    }

    @Test fun prependMirrorsTheTerminal() {
        assertEquals(listOf("b", "a", "c"), InputHistoryCommands.prepend(listOf("a", "b", "c"), "  b "))
        assertEquals(listOf("a"), InputHistoryCommands.prepend(listOf("a"), "   "))
        val full = (1..20).map { "p$it" }
        val next = InputHistoryCommands.prepend(full, "new")
        assertEquals(20, next.size)
        assertEquals("new", next.first())
        assertEquals("p19", next.last())
    }

    companion object {
        const val HISTORY_FILE = "/data/data/com.termux/files/home/.cline/data/cache/user_input_history.jsonl"
    }
}
