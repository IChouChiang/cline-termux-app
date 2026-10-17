package dev.cline.termux.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS §2.3 / §8.2.7: file names keep mixed-script text and are cut at whole graphemes. */
class SafeFileNameTest {
    @Test fun `only filesystem-unsafe characters are replaced`() {
        assertEquals("架构图.png", sanitizeFileName("架构图.png", "file", 120))
        assertEquals("a_b_c_d_e_f_g_h_i.txt", sanitizeFileName("a/b\\c:d*e?f\"g<h>i.txt", "file", 120))
        assertEquals("x_y", sanitizeFileName("x\u0000y", "file", 120))
        assertEquals("Ünïcödé 😀 файл.md", sanitizeFileName("  Ünïcödé 😀 файл.md  ", "file", 120))
    }

    @Test fun `no traversal, no hidden file, a fallback when nothing is left`() {
        assertEquals("_etc_passwd", sanitizeFileName("../etc/passwd", "file", 120))
        assertEquals("env", sanitizeFileName(".env", "file", 120))
        assertEquals("media", sanitizeFileName(" .. ", "media", 120))
    }

    @Test fun `the unit cut keeps whole graphemes`() {
        val family = "👩‍👩‍👧"
        assertEquals("a".repeat(10), fitFileName("a".repeat(10) + family, 14))
        assertEquals("a".repeat(10) + family, fitFileName("a".repeat(10) + family, 18))
    }

    @Test fun `the byte cut keeps names within the filesystem limit`() {
        val name = fitFileName("图".repeat(100), 120)
        assertEquals("图".repeat(80), name)
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= MAX_FILE_NAME_BYTES)
        val emoji = fitFileName("😀".repeat(70), 200)
        assertEquals(60, emoji.codePointCount(0, emoji.length))
    }
}
