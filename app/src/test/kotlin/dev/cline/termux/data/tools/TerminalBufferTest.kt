package dev.cline.termux.data.tools

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalBufferTest {

    private fun texts(raw: String, cap: Int = CommandOutputCap.MAX_DETAIL_COMMAND_OUTPUT_CHARS) =
        TerminalBuffer.parse(raw, cap).lines.map { it.text }

    // region desktop command-output.ts

    @Test
    fun `caps command output while preserving the newest tail`() {
        val result = CommandOutputCap.appendCapped("head\n", "x".repeat(CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS) + "tail")
        assertTrue(result.truncated)
        assertTrue(result.output.length <= CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS)
        assertTrue(result.output.startsWith("\u001b[0m[Earlier command output truncated]"))
        assertTrue(result.output.endsWith("tail"))
    }

    @Test
    fun `under the cap output is joined and truncation is remembered`() {
        assertEquals(CommandOutputCap.Capped("ab", false), CommandOutputCap.appendCapped("a", "b"))
        val marked = CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER + "x"
        assertEquals(CommandOutputCap.Capped(marked + "y", true), CommandOutputCap.appendCapped(marked, "y"))
        val detail = CommandOutputCap.appendCapped("", "z".repeat(60_000), CommandOutputCap.MAX_DETAIL_COMMAND_OUTPUT_CHARS)
        assertFalse(detail.truncated)
    }

    // endregion

    @Test
    fun `splits lines and keeps the partial last line`() {
        val snapshot = TerminalBuffer.parse("one\ntwo\r\nthree")
        assertEquals(listOf("one", "two", "three"), snapshot.lines.map { it.text })
        assertFalse(snapshot.endsWithNewline)
        val ended = TerminalBuffer.parse("one\n")
        assertEquals(listOf("one"), ended.lines.map { it.text })
        assertTrue(ended.endsWithNewline)
        assertTrue(TerminalBuffer.parse("").isEmpty)
    }

    @Test
    fun `lone carriage return overwrites the current line like a progress bar`() {
        assertEquals(listOf("Progress 100%"), texts("Progress 10%\rProgress 100%"))
        assertEquals(listOf("abcd"), texts("xyzd\rabc"))
        assertEquals(listOf("done"), texts("50%\r\u001b[2Kdone"))
    }

    @Test
    fun `carriage return split from its newline across chunks stays one newline`() {
        val buffer = TerminalBuffer()
        buffer.append("first\r")
        buffer.append("\nsecond")
        assertEquals(listOf("first", "second"), buffer.snapshot().lines.map { it.text })
    }

    @Test
    fun `backspace tab and erase in line`() {
        assertEquals(listOf("abX"), texts("abc\bX"))
        assertEquals(listOf("a" + " ".repeat(7) + "b"), texts("a\tb"))
        assertEquals(listOf("12345678" + " ".repeat(16) + "c"), texts("12345678\t\tc"))
        assertEquals(listOf("hel"), texts("hello\b\b\u001b[K"))
        assertEquals(listOf("    o"), texts("hello\b\b\u001b[1K"))
        assertEquals(listOf(" ".repeat(5) + "x"), texts("hello\u001b[2Kx"))
    }

    @Test
    fun `cursor moves within the current line`() {
        assertEquals(listOf("aXc"), texts("abc\u001b[2GX"))
        assertEquals(listOf("abcY"), texts("abc\u001b[4GY"))
        assertEquals(listOf("Ybc"), texts("abc\u001b[GY"))
        assertEquals(listOf("aZc"), texts("abc\u001b[2DZ"))
        assertEquals(listOf("ab   Q"), texts("ab\u001b[3CQ"))
        assertEquals(listOf("one", "two"), texts("one\n\u001b[1Atwo"))
    }

    @Test
    fun `sgr produces styled spans and resets`() {
        val line = TerminalBuffer.parse("plain \u001b[1;31mred\u001b[0m tail").lines.single()
        assertEquals("plain red tail", line.text)
        assertEquals(listOf(TermSpan(6, 9, TermStyle(fg = TermColor.Indexed(1), bold = true))), line.spans)
    }

    @Test
    fun `sgr colour forms`() {
        fun styleOf(seq: String) = TerminalBuffer.parse("\u001b[${seq}mx").lines.single().spans.single().style
        assertEquals(TermColor.Indexed(9), styleOf("91").fg)
        assertEquals(TermColor.Indexed(12), styleOf("104").bg)
        assertEquals(TermColor.Indexed(214), styleOf("38;5;214").fg)
        assertEquals(TermColor.Indexed(17), styleOf("48;5;17").bg)
        assertEquals(TermColor.Rgb(0x78B4FF), styleOf("38;2;120;180;255").fg)
        assertEquals(TermColor.Rgb(0x010203), styleOf("38:2::1:2:3").fg)
        assertEquals(TermColor.Indexed(200), styleOf("38:5:200").fg)
        assertEquals(TermStyle(fg = TermColor.Indexed(2), underline = true), styleOf("4;32"))
        assertEquals(TermStyle(dim = true, italic = true, inverse = true, strike = true), styleOf("2;3;7;9"))
        // A truecolour run followed by more attributes in the same sequence.
        assertEquals(TermStyle(fg = TermColor.Rgb(0xFF0000), bold = true), styleOf("38;2;255;0;0;1"))
    }

    @Test
    fun `attribute resets and defaults`() {
        val buffer = TerminalBuffer()
        buffer.append("\u001b[1;2;3;4;7;9;31;42ma\u001b[22;23;24;27;29;39;49mb\u001b[31m\u001b[mc")
        val line = buffer.snapshot().lines.single()
        assertEquals("abc", line.text)
        assertEquals(1, line.spans.size)
        assertEquals(0, line.spans.single().start)
        assertEquals(1, line.spans.single().end)
    }

    @Test
    fun `escape sequences split across chunks are completed`() {
        val buffer = TerminalBuffer()
        buffer.append("a\u001b")
        buffer.append("[3")
        buffer.append("2mgreen\u001b[0")
        buffer.append("m!")
        val line = buffer.snapshot().lines.single()
        assertEquals("agreen!", line.text)
        assertEquals(listOf(TermSpan(1, 6, TermStyle(fg = TermColor.Indexed(2)))), line.spans)
    }

    @Test
    fun `other escapes and controls are dropped`() {
        assertEquals(listOf("titletext"), texts("\u001b]0;window title\u0007title\u001b[?25ltext\u001b[?25h"))
        assertEquals(listOf("ab"), texts("a\u0007\u0001\u007Fb"))
        assertEquals(listOf("xy"), texts("x\u001b(By\u001b=\u001bPdcs payload\u001b\\"))
        assertEquals(listOf("ok"), texts("\u001b[2J\u001b[H\u001b[10;20Hok"))
    }

    @Test
    fun `osc 8 hyperlinks become link styles`() {
        val line = TerminalBuffer.parse("see \u001b]8;;https://cline.bot\u001b\\docs\u001b]8;;\u001b\\ now").lines.single()
        assertEquals("see docs now", line.text)
        assertEquals(listOf(TermSpan(4, 8, TermStyle(link = "https://cline.bot"))), line.spans)
    }

    @Test
    fun `oversized sequences are swallowed whole`() {
        val longCsi = "\u001b[" + "1;".repeat(100) + "31m"
        assertEquals(listOf("visible"), texts(longCsi + "visible"))
        val longOsc = "\u001b]8;;https://example.com/" + "a".repeat(5_000) + "\u0007"
        val line = TerminalBuffer.parse(longOsc + "link?").lines.single()
        assertEquals("link?", line.text)
        assertTrue(line.spans.isEmpty())
    }

    @Test
    fun `notice lines are recognised and left out of copied text`() {
        val raw = CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER +
            "tail\n[2048 command-output characters skipped while streaming]\nmore\n" +
            "[Command is still running. Output will continue in /data/tmp/x.log]\n" +
            "[Command completed with background processes still running; their output is no longer captured]\n" +
            "[not a notice]"
        val snapshot = TerminalBuffer.parse(raw)
        assertEquals(listOf(true, false, true, false, true, true, false), snapshot.lines.map { it.isNotice })
        assertEquals("tail\nmore\n[not a notice]", snapshot.copyText())
    }

    @Test
    fun `head cap drops whole lines and trims the first kept line like the desktop`() {
        val cap = 100
        val buffer = TerminalBuffer(cap)
        val raw = (1..20).joinToString("\n") { "line-%02d".format(it) } // 20 × 7 chars + 19 newlines = 159
        buffer.append(raw)
        val snapshot = buffer.snapshot()
        assertTrue(snapshot.headTruncated)
        val kept = snapshot.lines.joinToString("\n") { it.text }
        val marker = TerminalNotices.EARLIER_TRUNCATED.length + 1
        assertEquals(raw.takeLast(cap - marker), kept)
        assertTrue(buffer.length <= cap - marker)
    }

    @Test
    fun `a single huge line is cut at the head`() {
        val buffer = TerminalBuffer(64)
        buffer.append("\u001b[32m" + "a".repeat(200) + "END")
        val line = buffer.snapshot().lines.single()
        assertTrue(line.text.endsWith("END"))
        assertEquals(64 - TerminalNotices.EARLIER_TRUNCATED.length - 1, line.text.length)
        assertEquals(TermColor.Indexed(2), line.spans.single().style.fg)
        assertEquals(0, line.spans.single().start)
    }

    @Test
    fun `display columns count wide characters twice`() {
        assertEquals(5, displayColumns("hello"))
        assertEquals(8, displayColumns("构建成功"))
        assertEquals(6, displayColumns("ＡＢＣ"))
        assertEquals(1, displayColumns("é"))
        assertEquals(2, displayColumns("😀"))
        val snapshot = TerminalBuffer.parse("ab\n构建成功 ok")
        assertEquals(11, snapshot.maxColumns)
    }

    @Test
    fun `clear resets everything`() {
        val buffer = TerminalBuffer(50)
        buffer.append("x".repeat(80))
        buffer.clear()
        assertTrue(buffer.snapshot().isEmpty)
        assertFalse(buffer.snapshot().headTruncated)
    }

    @Test
    fun `feed publishes the first chunk at once and conflates the rest every 50 ms`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val feed = TerminalFeed(backgroundScope, capChars = 1_000, dispatcher = dispatcher)
        feed.snapshots.test {
            assertTrue(awaitItem().isEmpty)
            feed.append("a\n")
            runCurrent()
            assertEquals(listOf("a"), awaitItem().lines.map { it.text })
            feed.append("b\n")
            feed.append("c\n")
            advanceTimeBy(10)
            runCurrent()
            expectNoEvents()
            advanceTimeBy(45)
            runCurrent()
            assertEquals(listOf("a", "b", "c"), awaitItem().lines.map { it.text })
            feed.close()
            advanceTimeBy(100)
            runCurrent()
            expectNoEvents()
        }
    }

    @Test
    fun `conflateEvery keeps the first and the last value`() = runTest {
        val values = flow {
            for (i in 1..10) {
                emit(i)
                delay(10)
            }
        }.conflateEvery(50).toList()
        assertEquals(1, values.first())
        assertEquals(10, values.last())
        assertTrue(values.size < 10)
    }
}
