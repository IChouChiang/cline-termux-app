package dev.cline.termux.core.kernel.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class AnsiStripTest {
    @Test
    fun `strips the CLI colour table output of cline auth`() {
        val line = "\u001B[32mProvider configured:\u001B[0m \u001B[36manthropic\u001B[0m (claude-sonnet-4-6)\n"
        assertEquals("Provider configured: anthropic (claude-sonnet-4-6)\n", AnsiStrip.strip(line))
    }

    @Test
    fun `strips multi-parameter SGR sequences`() {
        assertEquals("bold red", "\u001B[1;31mbold red\u001B[0m".stripAnsi())
        assertEquals("x", "\u001B[38;5;208mx\u001B[m".stripAnsi())
        assertEquals("", AnsiStrip.SGR.replace("\u001B[0m\u001B[1;32m", ""))
    }

    @Test
    fun `strips cursor and private-mode CSI and OSC sequences`() {
        assertEquals("done", "\u001B[?25l\u001B[2Kdone\u001B[?25h".stripAnsi())
        assertEquals("link", "\u001B]8;;https://example.com\u0007link\u001B]8;;\u0007".stripAnsi())
        assertEquals("title", "\u001B]0;cline\u001B\\title".stripAnsi())
    }

    @Test
    fun `leaves plain text untouched and returns the same instance`() {
        val plain = "no escapes [32m here"
        assertSame(plain, AnsiStrip.strip(plain))
    }

    @Test
    fun `no escape byte survives`() {
        val noisy = "\u001B[33mwarn\u001B[0m \u001B[1A\u001B[K\u001B]2;t\u0007"
        assertFalse(noisy.stripAnsi().contains('\u001B'))
    }
}
