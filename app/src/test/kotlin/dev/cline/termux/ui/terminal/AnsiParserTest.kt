package dev.cline.termux.ui.terminal

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import dev.cline.termux.data.tools.CommandOutputCap
import dev.cline.termux.data.tools.TermColor
import dev.cline.termux.data.tools.TermStyle
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalLine
import dev.cline.termux.ui.theme.ClineDarkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiParserTest {
    private val palette = TerminalPalette.from(ClineDarkColors)

    private fun colorAt(text: String, char: Char): Color? {
        val styled = AnsiParser.parse(text, palette)
        val index = styled.text.indexOf(char)
        return styled.spanStyles.firstOrNull { index in it.start until it.end }?.item?.color
    }

    @Test
    fun `the 16 colours come from the design system palette`() {
        assertEquals(palette.ansi[1], colorAt("\u001b[31mR", 'R'))
        assertEquals(palette.ansi[10], colorAt("\u001b[92mG", 'G'))
        assertEquals(palette.ansi[4], AnsiParser.spanStyleFor(TermStyle(bg = TermColor.Indexed(4)), palette).background)
    }

    @Test
    fun `bold maps 0-7 to 8-15 and uses SemiBold`() {
        val style = AnsiParser.spanStyleFor(TermStyle(fg = TermColor.Indexed(3), bold = true), palette)
        assertEquals(palette.ansi[11], style.color)
        assertEquals(FontWeight.SemiBold, style.fontWeight)
        // Bright colours stay bright and 256-colour values are not shifted.
        assertEquals(palette.ansi[12], AnsiParser.spanStyleFor(TermStyle(fg = TermColor.Indexed(12), bold = true), palette).color)
        assertEquals(
            AnsiParser.indexed(100, palette),
            AnsiParser.spanStyleFor(TermStyle(fg = TermColor.Indexed(100), bold = true), palette).color,
        )
    }

    @Test
    fun `256 colour cube and grey ramp follow xterm`() {
        assertEquals(Color(red = 0, green = 0, blue = 0), AnsiParser.indexed(16, palette))
        assertEquals(Color(red = 255, green = 175, blue = 0), AnsiParser.indexed(214, palette))
        assertEquals(Color(red = 255, green = 255, blue = 255), AnsiParser.indexed(231, palette))
        assertEquals(Color(red = 8, green = 8, blue = 8), AnsiParser.indexed(232, palette))
        assertEquals(Color(red = 238, green = 238, blue = 238), AnsiParser.indexed(255, palette))
        assertEquals(palette.ansi[7], AnsiParser.indexed(7, palette))
    }

    @Test
    fun `truecolour renders as given`() {
        assertEquals(Color(red = 120, green = 180, blue = 255), colorAt("\u001b[38;2;120;180;255mT", 'T'))
    }

    @Test
    fun `inverse swaps foreground and background including the defaults`() {
        val plain = AnsiParser.spanStyleFor(TermStyle(inverse = true), palette)
        assertEquals(palette.background, plain.color)
        assertEquals(palette.foreground, plain.background)
        val coloured = AnsiParser.spanStyleFor(TermStyle(fg = TermColor.Indexed(1), bg = TermColor.Indexed(4), inverse = true), palette)
        assertEquals(palette.ansi[4], coloured.color)
        assertEquals(palette.ansi[1], coloured.background)
    }

    @Test
    fun `underline strike italic and dim`() {
        val style = AnsiParser.spanStyleFor(TermStyle(underline = true, strike = true, italic = true, dim = true), palette)
        assertEquals(TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)), style.textDecoration)
        assertEquals(FontStyle.Italic, style.fontStyle)
        assertEquals(0.6f, style.color.alpha, 0.01f)
        assertEquals(TextDecoration.Underline, AnsiParser.spanStyleFor(TermStyle(underline = true), palette).textDecoration)
    }

    @Test
    fun `plain text carries no styles and lines join with newlines`() {
        val styled = AnsiParser.parse("one\ntwo\r\nthree", palette)
        assertEquals("one\ntwo\nthree", styled.text)
        assertTrue(styled.spanStyles.isEmpty())
    }

    @Test
    fun `osc 8 becomes a url link annotation`() {
        val styled = AnsiParser.parse("\u001b]8;;https://cline.bot\u0007site\u001b]8;;\u0007", palette)
        val link = styled.getLinkAnnotations(0, styled.length).single()
        assertEquals("https://cline.bot", (link.item as LinkAnnotation.Url).url)
        assertEquals(0, link.start)
        assertEquals(4, link.end)
    }

    @Test
    fun `unwrapped lines clip at the column limit with an ellipsis`() {
        val line = TerminalLine("a".repeat(10))
        assertEquals("aaaa…", AnsiParser.toAnnotatedString(line, palette, maxColumns = 4).text)
        assertEquals("a".repeat(10), AnsiParser.toAnnotatedString(line, palette, maxColumns = 10).text)
        assertEquals(2, AnsiParser.clipIndex("构建成功", 5))
        assertNull(AnsiParser.clipIndex("构建", 4))
    }

    @Test
    fun `snapshots differ when a chunk only lengthens the last line`() {
        // TerminalView's follow-tail effect is keyed on the snapshot: every parsed snapshot has
        // the same version, so equality must still see a growing or overwritten last line.
        val before = TerminalBuffer.parse("collected 3 items\n..")
        val after = TerminalBuffer.parse("collected 3 items\n...")
        assertEquals(before.lines.size, after.lines.size)
        assertEquals(before.version, after.version)
        assertNotEquals(before, after)
        assertEquals(terminalLayout(before, running = true), terminalLayout(after, running = true))

        val progressBefore = TerminalBuffer.parse("50%\r60%")
        val progressAfter = TerminalBuffer.parse("50%\r60%\r70%")
        assertNotEquals(progressBefore, progressAfter)
        assertEquals(progressBefore, TerminalBuffer.parse("50%\r60%"))
    }

    @Test
    fun `terminal layout puts the head notice and the cursor in place`() {
        val running = terminalLayout(TerminalBuffer.parse("a\nb"), running = true)
        assertEquals(TerminalLayout(headNotice = false, lineCount = 2, cursorLine = 1, cursorRow = false), running)
        assertEquals(2, running.itemCount)

        val afterNewline = terminalLayout(TerminalBuffer.parse("a\n"), running = true)
        assertEquals(TerminalLayout(headNotice = false, lineCount = 1, cursorLine = -1, cursorRow = true), afterNewline)
        assertEquals(2, afterNewline.itemCount)

        val finished = terminalLayout(TerminalBuffer.parse("a\n"), running = false)
        assertEquals(TerminalLayout(headNotice = false, lineCount = 1, cursorLine = -1, cursorRow = false), finished)
        assertEquals(1, finished.itemCount)

        val truncated = terminalLayout(TerminalBuffer.parse("x".repeat(100), capChars = 60), running = false)
        assertTrue(truncated.headNotice)
        assertEquals(1 + truncated.lineCount, truncated.itemCount)

        // A marker already in the text is a notice line of its own, not the head row.
        val markerSnapshot = TerminalBuffer.parse(CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER + "tail")
        assertFalse(terminalLayout(markerSnapshot, running = false).headNotice)
        assertTrue(markerSnapshot.lines.first().isNotice)

        // The cursor never sits on a notice line: it gets its own row after it.
        val notice = terminalLayout(TerminalBuffer.parse("a\n[Earlier command output truncated]"), running = true)
        assertEquals(-1, notice.cursorLine)
        assertTrue(notice.cursorRow)

        val empty = terminalLayout(TerminalBuffer.parse(""), running = true)
        assertEquals(TerminalLayout(headNotice = false, lineCount = 0, cursorLine = -1, cursorRow = true), empty)
        assertEquals(1, empty.itemCount)
    }

    @Test
    fun `line keys stay on their output line while the head cap drops lines`() {
        val buffer = TerminalBuffer(capChars = 100)
        buffer.append((1..10).joinToString("") { "line-%02d\n".format(it) }) // 80 chars, under the cap
        val before = buffer.snapshot()
        assertEquals(0L, before.firstLineIndex)
        val keyOfLine08 = before.lineKey(before.lines.indexOfFirst { it.text == "line-08" })

        buffer.append((11..14).joinToString("") { "line-%02d\n".format(it) }) // drops the head
        val after = buffer.snapshot()
        assertTrue(after.headTruncated)
        assertTrue(after.firstLineIndex > 0)
        val index08 = after.lines.indexOfFirst { it.text == "line-08" }
        assertEquals(keyOfLine08, after.lineKey(index08))
        // Every kept whole line keeps its key: absolute index = its number - 1.
        val whole = Regex("line-\\d\\d")
        after.lines.forEachIndexed { i, line ->
            if (whole.matches(line.text)) assertEquals(line.text.takeLast(2).toLong() - 1, after.lineKey(i))
        }
        // Kept lines are the same instances, so a keyed row skips its recomposition.
        assertSame(before.lines.last(), after.lines[index08 + 2])

        buffer.clear()
        assertEquals(0L, buffer.snapshot().firstLineIndex)
    }
}
