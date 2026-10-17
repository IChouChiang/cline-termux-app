package dev.cline.termux.data.chat

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.AttachmentWriteFailed
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.writeAttachment
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1.9 pipeline rules: limits, encodings, text decoding, names and the stdin transport. */
class AttachmentsTest {

    private data class Ref(override val displayName: String, override val mimeType: String?) : AttachmentRef

    @Test fun base64LengthIsFourCeilThirds() {
        assertEquals(0, Attachments.base64Length(0))
        assertEquals(4, Attachments.base64Length(1))
        assertEquals(4, Attachments.base64Length(3))
        assertEquals(8, Attachments.base64Length(4))
    }

    @Test fun imageSizeLimitsUseTheEncodedSize() {
        // 3,932,160 raw bytes encode to exactly 5 MiB: allowed; one more byte is not.
        assertNull(Attachments.sizeError(Attachments.base64Length(3_932_160), 0))
        assertEquals(ImageLimit.TooLarge, Attachments.sizeError(Attachments.base64Length(3_932_161), 0))
        val four = 4L * 1024 * 1024
        assertNull(Attachments.sizeError(four, four))
        assertEquals(ImageLimit.TotalTooLarge, Attachments.sizeError(four, four + 1))
    }

    @Test fun countCapTakesTheFirstRemaining() {
        assertEquals(5 to false, Attachments.acceptCount(0, 5))
        assertEquals(1 to true, Attachments.acceptCount(4, 3))
        assertEquals(0 to true, Attachments.acceptCount(5, 1))
        assertEquals(2 to false, Attachments.acceptCount(1, 2))
    }

    @Test fun downscaleKeepsTheLongEdgeAt2048() {
        assertEquals(2048 to 1536, Attachments.targetSize(4032, 3024))
        assertEquals(1536 to 2048, Attachments.targetSize(3024, 4032))
        assertEquals(800 to 600, Attachments.targetSize(800, 600))
        assertEquals(96 to 48, Attachments.targetSize(2048, 1024, 96))
    }

    @Test fun pngOnlyForAlphaSourcesThatAreNotGifOrWebp() {
        assertTrue(Attachments.prefersPng("image/png", hasAlpha = true))
        assertFalse(Attachments.prefersPng("image/png", hasAlpha = false))
        assertFalse(Attachments.prefersPng("image/webp", hasAlpha = true))
        assertFalse(Attachments.prefersPng("image/gif", hasAlpha = true))
        assertTrue(Attachments.prefersPng("image/heic", hasAlpha = true))
    }

    @Test fun textDecodingReplacesMalformedAndRefusesNul() {
        assertEquals("héllo", Attachments.decodeText("héllo".toByteArray()))
        assertEquals("a�b", Attachments.decodeText(byteArrayOf(0x61, 0xFF.toByte(), 0x62)))
        assertNull(Attachments.decodeText(byteArrayOf(0x50, 0x4E, 0x47, 0x00, 0x01)))
        assertEquals("x", Attachments.decodeText(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x78)))
    }

    @Test fun imageDetectionByMimeThenExtension() {
        assertEquals("image/png", Attachments.imageMime(Ref("a.bin", "image/png")))
        assertEquals("image/jpeg", Attachments.imageMime(Ref("IMG.JPG", null)))
        assertEquals("image/jpeg", Attachments.imageMime(Ref("photo.jfif", "application/octet-stream")))
        assertNull(Attachments.imageMime(Ref("notes.txt", "text/plain")))
        assertNull(Attachments.imageMime(Ref("fake.png", "text/plain")))
        assertTrue(Attachments.decodable("image/heic"))
        assertFalse(Attachments.decodable("image/svg+xml"))
    }

    @Test fun dataUrl() {
        assertEquals("data:image/png;base64,AQID", Attachments.dataUrl("image/png", byteArrayOf(1, 2, 3)))
    }

    @Test fun chipLabelsAreMiddleEllipsizedByGrapheme() {
        assertEquals("short.png", Attachments.chipLabel("short.png"))
        val long = "a-very-long-screenshot-name-2026.png"
        val label = Attachments.chipLabel(long)
        assertEquals(24, label.codePointCount(0, label.length))
        assertTrue(label.startsWith("a-very-long-") && label.endsWith("26.png") && "…" in label)
        val emoji = "👍🏽".repeat(30)
        val cut = Attachments.chipLabel(emoji)
        assertTrue(cut.startsWith("👍🏽") && cut.endsWith("👍🏽"))
    }

    @Test fun cjkChipLabelsKeepTheExtensionAfterTheMiddleCut() {
        val cjk = "二〇二六年十月三日の設計レビュー議事録と画面キャプチャ一覧.png"
        val label = Attachments.chipLabel(cjk)
        assertEquals(24, label.codePointCount(0, label.length))
        assertTrue(label == "二〇二六年十月三日の設計…キャプチャ一覧.png" && "…" in label)
    }

    @Test fun termuxDirIsPerSessionWithAFreshUuid() {
        assertEquals("${TermuxPaths.ATTACHMENTS_DIR}/s1/u", Attachments.termuxDir("s1", "u"))
        assertEquals("${TermuxPaths.ATTACHMENTS_DIR}/draft/u", Attachments.termuxDir(null, "u"))
    }

    @Test fun textAttachmentsTravelThroughStdinChunksThenRename() = runTest {
        val termux = FakeTermuxExecutor()
        val content = "x".repeat(ClineCommands.ATTACHMENT_CHUNK_CHARS * 2 + 10)
        val result = termux.writeAttachment("/d", ".env notes", content)
        assertEquals("/d/_env notes", result.getOrThrow())
        val chunks = termux.commandsLabelled(ClineCommands.Labels.WRITE_ATTACHMENT_CHUNK)
        assertEquals(3, chunks.size)
        assertEquals(content, chunks.joinToString("") { it.stdin.orEmpty() })
        // Content never appears in the arguments (CA §0.8).
        assertTrue(termux.commands.none { cmd -> cmd.args.any { it.contains("xxxx") } })
        assertEquals(ClineCommands.Labels.FINALIZE_ATTACHMENT, termux.labels.last())
    }

    @Test fun aFailingChunkStopsTheWrite() = runTest {
        val termux = FakeTermuxExecutor()
        termux.on(ClineCommands.Labels.WRITE_ATTACHMENT_CHUNK, TermuxResult.failure(1, "disk full"))
        val failure = termux.writeAttachment("/d", "a.txt", "hello").exceptionOrNull()
        // The typed failure keeps the kernel's reason (docs/07 §9), not a bare exit code.
        assertTrue("$failure", failure is AttachmentWriteFailed)
        assertEquals(ClineCommands.Labels.WRITE_ATTACHMENT_CHUNK, (failure as AttachmentWriteFailed).label)
        assertEquals("disk full", failure.detail)
        assertFalse(termux.ran(ClineCommands.Labels.FINALIZE_ATTACHMENT))
    }

    @Test fun eachWriteGetsSixtySeconds() = runTest {
        val termux = FakeTermuxExecutor()
        termux.latencyMs = 45_000
        assertTrue(termux.writeAttachment("/d", "a.txt", "hello").isSuccess)
        termux.never(ClineCommands.Labels.WRITE_ATTACHMENT_CHUNK)
        assertTrue(termux.writeAttachment("/d", "b.txt", "hello").isFailure)
    }
}
