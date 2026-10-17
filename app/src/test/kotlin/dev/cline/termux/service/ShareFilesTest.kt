package dev.cline.termux.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The 24 h sweep of the FileProvider areas (docs/07 §7.4) and file naming. */
class ShareFilesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val now = 1_790_000_000_000L

    private fun file(path: String, ageMs: Long): File = File(tmp.root, path).apply {
        parentFile!!.mkdirs()
        writeText("x")
        setLastModified(now - ageMs)
    }

    @Test
    fun sweepRemovesOnlyFilesOlderThanADay() {
        val oldShare = file("share/diff.patch", ShareFiles.MAX_AGE_MS + 1)
        val newShare = file("share/output.txt", 1_000)
        val oldDiag = file("diagnostics/run/bundle.zip", ShareFiles.MAX_AGE_MS * 2)
        val oldCamera = file("camera/photo-1.jpg", ShareFiles.MAX_AGE_MS + 60_000)
        val oldAttachment = file("attachments/a.txt", ShareFiles.MAX_AGE_MS + 1)
        val unrelated = file("image_cache/keep.bin", ShareFiles.MAX_AGE_MS * 3)

        assertEquals(4, ShareFiles.sweep(tmp.root, now))

        assertFalse(oldShare.exists())
        assertTrue(newShare.exists())
        assertFalse(oldDiag.exists())
        assertFalse("empty sub-directories go too", File(tmp.root, "diagnostics/run").exists())
        assertTrue("the area roots stay", File(tmp.root, "diagnostics").exists())
        assertFalse(oldCamera.exists())
        assertFalse(oldAttachment.exists())
        assertTrue("outside the areas nothing is touched", unrelated.exists())
    }

    @Test
    fun sweepToleratesMissingAreas() {
        assertEquals(0, ShareFiles.sweep(File(tmp.root, "nothing-here"), now))
    }

    @Test
    fun namesAreSanitized() {
        assertEquals("a_b_c.txt", ShareFiles.sanitize("a/b\\c.txt"))
        assertEquals("file", ShareFiles.sanitize(" .. "))
        assertEquals("x_y", ShareFiles.sanitize("x\u0001y"))
        assertEquals(120, ShareFiles.sanitize("n".repeat(300)).length)
    }

    @Test
    fun namesKeepNonAsciiAndNeverSplitAGrapheme() {
        assertEquals("架构图.png", ShareFiles.sanitize("架构图.png"))
        // 119 units then an emoji across the 120-unit limit: no lone surrogate is left.
        assertEquals("n".repeat(119), ShareFiles.sanitize("n".repeat(119) + "😀"))
        // 120 CJK characters would be 360 UTF-8 bytes; the cut stays within 240.
        assertEquals("图".repeat(80), ShareFiles.sanitize("图".repeat(200)))
    }
}
