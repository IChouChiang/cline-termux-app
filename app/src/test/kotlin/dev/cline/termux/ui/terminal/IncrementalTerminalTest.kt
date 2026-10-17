package dev.cline.termux.ui.terminal

import dev.cline.termux.data.tools.CommandOutputCap
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalNotices
import dev.cline.termux.data.tools.TerminalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class IncrementalTerminalTest {
    private val marker = CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER

    private fun TerminalSnapshot.texts(): List<String> = lines.map { it.text }

    /** The chat row's input: the projector's capped string after each chunk. */
    private fun cappedVersions(chunks: List<String>, cap: Int): List<String> {
        var current = ""
        return chunks.map { chunk ->
            current = CommandOutputCap.appendCapped(current, chunk, cap).output
            current
        }
    }

    private fun numberedChunks(count: Int): List<String> =
        (1..count).map { "\u001b[3${it % 8}mline $it\u001b[0m of the build log\n" }

    // ---- appendedSuffix ---------------------------------------------------------------------

    @Test
    fun `an extended text yields its new suffix`() {
        assertEquals("def", appendedSuffix("abc", "abcdef"))
        assertEquals("abc", appendedSuffix("", "abc"))
        assertEquals("", appendedSuffix("abc", "abc"))
    }

    @Test
    fun `a text that is not a continuation yields null`() {
        assertNull(appendedSuffix("abcdef", "abc"))
        assertNull(appendedSuffix("hello world", "goodbye"))
    }

    @Test
    fun `a slid head-capped window yields what entered at its end`() {
        val stream = (1..100).joinToString("") { "row $it\n" }
        val old = marker + stream.substring(100, 260)
        val new = marker + stream.substring(130, 290)
        assertEquals(stream.substring(260, 290), appendedSuffix(old, new))
    }

    @Test
    fun `the first head cap continues an uncapped text`() {
        val stream = (1..40).joinToString("") { "row $it\n" }
        val old = stream.substring(0, 200)
        val new = marker + stream.substring(50, 230)
        assertEquals(stream.substring(200, 230), appendedSuffix(old, new))
    }

    @Test
    fun `a head-capped text of another output yields null`() {
        val old = marker + (1..30).joinToString("") { "alpha $it\n" }
        val new = marker + (1..30).joinToString("") { "omega $it\n" }
        assertNull(appendedSuffix(old, new))
    }

    @Test
    fun `an overlap shorter than the probe counts as new output`() {
        val stream = (1..200).joinToString("") { "row $it\n" }
        val old = marker + stream.substring(0, 300)
        // Only 10 characters of the old window survive into the new one.
        val new = marker + stream.substring(290, 590)
        assertNull(appendedSuffix(old, new))
    }

    // ---- IncrementalTerminalParser ----------------------------------------------------------

    @Test
    fun `streamed versions match a parse of the final text without reparsing`() {
        val chunks = numberedChunks(60) + "\u001b[1mDONE\u001b[0m partial"
        val parser = IncrementalTerminalParser()
        var last = TerminalSnapshot.EMPTY
        for (version in cappedVersions(chunks, CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS)) last = parser.update(version)
        val expected = TerminalBuffer.parse(chunks.joinToString(""), CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS)
        assertEquals(expected.lines, last.lines)
        assertEquals(expected.endsWithNewline, last.endsWithNewline)
        assertEquals(0, parser.resets)
    }

    @Test
    fun `an escape split across versions is completed by the next one`() {
        val parser = IncrementalTerminalParser()
        parser.update("ok \u001b[3")
        val snapshot = parser.update("ok \u001b[31mred\u001b[0m\n")
        assertEquals(TerminalBuffer.parse("ok \u001b[31mred\u001b[0m\n").lines, snapshot.lines)
        assertEquals(0, parser.resets)
    }

    @Test
    fun `an unchanged text returns the same snapshot`() {
        val parser = IncrementalTerminalParser()
        val first = parser.update("hello\n")
        assertSame(first, parser.update(String("hello\n".toCharArray())))
    }

    @Test
    fun `a sliding head-capped window is appended, not reparsed`() {
        val chunks = numberedChunks(400)
        val versions = cappedVersions(chunks, cap = 2_000)
        assertTrue(versions.last().startsWith(marker))
        val parser = IncrementalTerminalParser(capChars = 100_000)
        var last = TerminalSnapshot.EMPTY
        for (version in versions) last = parser.update(version)
        assertEquals(0, parser.resets)
        // Fed from the first chunk on, the buffer holds the whole stream, escapes interpreted.
        assertEquals(TerminalBuffer.parse(chunks.joinToString(""), 100_000).lines, last.lines)
        assertFalse(last.headTruncated)
    }

    @Test
    fun `a row composed after the head cap starts from the capped text and keeps appending`() {
        val chunks = numberedChunks(300)
        val versions = cappedVersions(chunks, cap = 1_500)
        val parser = IncrementalTerminalParser(capChars = 100_000)
        val start = versions.indexOfFirst { it.startsWith(marker) } + 20
        var last = TerminalSnapshot.EMPTY
        for (version in versions.drop(start)) last = parser.update(version)
        // The first text is parsed whole into the empty buffer; every later one appends.
        assertEquals(0, parser.resets)
        assertEquals(TerminalNotices.EARLIER_TRUNCATED, last.texts().first())
        assertTrue(last.lines.first().isNotice)
        assertEquals("line 300 of the build log", last.texts().last())
        // The tail matches a fresh parse of the latest capped text line for line.
        val fresh = TerminalBuffer.parse(versions.last(), 100_000).texts()
        assertEquals(fresh.takeLast(20), last.texts().takeLast(20))
    }

    @Test
    fun `the buffer's own cap drops the head and flags it`() {
        val chunks = numberedChunks(300)
        val parser = IncrementalTerminalParser(capChars = 1_000)
        var last = TerminalSnapshot.EMPTY
        for (version in cappedVersions(chunks, cap = 2_000)) last = parser.update(version)
        assertEquals(0, parser.resets)
        assertTrue(last.headTruncated)
        assertEquals("line 300 of the build log", last.texts().last())
        assertTrue(last.texts().joinToString("\n").length <= 1_000)
    }

    @Test
    fun `periodic output keeps appending without a reset`() {
        val chunks = List(500) { "ok\n" }
        val versions = cappedVersions(chunks, cap = 600)
        val parser = IncrementalTerminalParser(capChars = 600)
        var last = TerminalSnapshot.EMPTY
        for (version in versions) last = parser.update(version)
        assertEquals(0, parser.resets)
        assertTrue(last.texts().takeLast(50).all { it == "ok" })
    }

    @Test
    fun `a different output resets the buffer`() {
        val parser = IncrementalTerminalParser()
        parser.update("streamed partial output\n")
        val snapshot = parser.update("final result\n")
        assertEquals(1, parser.resets)
        assertEquals(listOf("final result"), snapshot.texts())
    }

    @Test
    fun `snapshot versions advance with each append`() {
        val parser = IncrementalTerminalParser()
        val a = parser.update("a\n")
        val b = parser.update("a\nb\n")
        assertTrue(b.version > a.version)
        assertEquals(a.firstLineIndex, b.firstLineIndex)
    }
}
