package dev.cline.termux.ui.chat

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.ui.chat.rows.GalleryKind
import dev.cline.termux.ui.chat.rows.ImageGallery
import dev.cline.termux.ui.chat.rows.buildTranscriptModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Screen C scroll rules (§4.4), screen D lightbox rules (§5.4), and the selection row lookup (§3.14). */
class ThreadRulesTest {
    // ---------------------------------------------------------------- scroll

    @Test
    fun `at bottom within 24dp of the newest item only`() {
        assertTrue(TranscriptScrollRules.isAtBottom(0, 0, 24))
        assertTrue(TranscriptScrollRules.isAtBottom(0, 24, 24))
        assertFalse(TranscriptScrollRules.isAtBottom(0, 25, 24))
        assertFalse(TranscriptScrollRules.isAtBottom(1, 0, 24))
    }

    @Test
    fun `scroll button beyond 120dp or past the newest item`() {
        assertFalse(TranscriptScrollRules.showsScrollButton(0, 120, 120))
        assertTrue(TranscriptScrollRules.showsScrollButton(0, 121, 120))
        assertTrue(TranscriptScrollRules.showsScrollButton(1, 0, 120))
    }

    @Test
    fun `sticking is an intent - user scroll releases, bottom restores, programmatic never releases`() {
        assertTrue(TranscriptScrollRules.nextPinned(pinned = false, atBottom = true, userScrolling = true))
        assertFalse(TranscriptScrollRules.nextPinned(pinned = true, atBottom = false, userScrolling = true))
        assertTrue(TranscriptScrollRules.nextPinned(pinned = true, atBottom = false, userScrolling = false))
        assertFalse(TranscriptScrollRules.nextPinned(pinned = false, atBottom = false, userScrolling = false))
    }

    @Test
    fun `jump then glide only from beyond index 6`() {
        assertNull(TranscriptScrollRules.jumpTarget(6))
        assertEquals(3, TranscriptScrollRules.jumpTarget(7))
        assertEquals(3, TranscriptScrollRules.jumpTarget(50))
    }

    @Test
    fun `not following holds the item above the growing one where it was laid out`() {
        // The streaming row (index 0) laid out at -150 with 600px: the row above starts at 450.
        assertEquals(1 to -450, TranscriptScrollRules.holdAnchor(0, -150, 600, 0, 5))
        assertEquals(3 to -460, TranscriptScrollRules.holdAnchor(2, -150, 600, 10, 5))
        // Nothing above the oldest item: nothing to hold.
        assertNull(TranscriptScrollRules.holdAnchor(4, 0, 600, 0, 5))
    }

    // ---------------------------------------------------------------- lightbox

    @Test
    fun `scale clamps to 1x to 5x and double tap toggles 1x and 2_5x`() {
        assertEquals(1f, LightboxRules.clampScale(0.3f))
        assertEquals(5f, LightboxRules.clampScale(9f))
        assertEquals(2.5f, LightboxRules.doubleTapScale(1f))
        assertEquals(1f, LightboxRules.doubleTapScale(2.5f))
        assertEquals(1f, LightboxRules.doubleTapScale(4f))
    }

    @Test
    fun `fit keeps the aspect ratio and centres`() {
        val wide = LightboxRules.fittedRect(Size(400f, 800f), Size(200f, 100f))
        assertEquals(Rect(0f, 300f, 400f, 500f), wide)
        val tall = LightboxRules.fittedRect(Size(400f, 800f), Size(100f, 400f))
        assertEquals(Rect(100f, 0f, 300f, 800f), tall)
        assertEquals(Rect.Zero, LightboxRules.fittedRect(Size(400f, 800f), Size(0f, 10f)))
    }

    @Test
    fun `pan is clamped to the scaled image overflow`() {
        val content = Size(400f, 200f)
        val container = Size(400f, 800f)
        assertOffset(Offset(400f, 0f), LightboxRules.clampPan(Offset(1000f, 1000f), 3f, content, container))
        assertOffset(Offset(0f, 0f), LightboxRules.clampPan(Offset(50f, 50f), 1f, content, container))
        assertOffset(Offset(-400f, 0f), LightboxRules.clampPan(Offset(-1000f, -5f), 3f, content, container))
    }

    /** Component compare: `Offset` equality tells -0f from 0f. */
    private fun assertOffset(expected: Offset, actual: Offset) {
        assertEquals(expected.x, actual.x, 0.001f)
        assertEquals(expected.y, actual.y, 0.001f)
    }

    @Test
    fun `zooming around a tap keeps that point fixed`() {
        val center = Offset(200f, 400f)
        val tap = Offset(300f, 500f)
        val scale = 2.5f
        val pan = LightboxRules.panForZoomAt(tap, center, scale)
        val mapped = center + (tap - center) * scale + pan
        assertEquals(tap.x, mapped.x, 0.001f)
        assertEquals(tap.y, mapped.y, 0.001f)
    }

    @Test
    fun `taps hit the displayed image, zoomed and panned`() {
        val container = Size(400f, 800f)
        val fitted = Rect(0f, 300f, 400f, 500f)
        assertTrue(LightboxRules.hitsImage(Offset(200f, 400f), fitted, 1f, Offset.Zero, container))
        assertFalse(LightboxRules.hitsImage(Offset(200f, 100f), fitted, 1f, Offset.Zero, container))
        assertTrue(LightboxRules.hitsImage(Offset(200f, 100f), fitted, 4f, Offset.Zero, container))
        assertFalse(LightboxRules.hitsImage(Offset(200f, 400f), Rect.Zero, 1f, Offset.Zero, container))
    }

    @Test
    fun `counter only with more than one image`() {
        assertNull(LightboxRules.counter(0, 1))
        assertEquals(2 to 5, LightboxRules.counter(1, 5))
    }

    @Test
    fun `decode bound is twice the window capped at 3072`() {
        assertEquals(2400, LightboxRules.decodeEdge(IntSize(1080, 1200)))
        assertEquals(3072, LightboxRules.decodeEdge(IntSize(1440, 3088)))
    }

    @Test
    fun `full decodes are cached per image, page and bound`() {
        val image = ChatImage("img1", "image/png", "")
        assertEquals("img1:0:2400", LightboxRules.cacheKey(image, 0, 2400))
        assertFalse(LightboxRules.cacheKey(image, 0, 2400) == LightboxRules.cacheKey(image, 0, 3072))
        assertFalse(LightboxRules.cacheKey(image, 0, 2400) == LightboxRules.cacheKey(image, 1, 2400))
    }

    @Test
    fun `sample size keeps the long edge within the bound`() {
        assertEquals(1, LightboxRules.sampleToFit(1000, 800, 4096))
        assertEquals(2, LightboxRules.sampleToFit(5000, 800, 4096))
        assertEquals(4, LightboxRules.sampleToFit(1000, 9000, 4096))
        assertEquals(1, LightboxRules.sampleToFit(0, 0, 4096))
    }

    @Test
    fun `share file names carry the index and the media type extension`() {
        assertEquals("img1-2.png", LightboxRules.shareFileName(ChatImage("img1", "image/png", ""), 1))
        assertEquals("x-1.jpg", LightboxRules.shareFileName(ChatImage("x", "image/jpeg", ""), 0))
        assertEquals("webp", LightboxRules.extensionFor("IMAGE/WEBP"))
        assertEquals("gif", LightboxRules.extensionFor("image/gif"))
        assertEquals("img", LightboxRules.extensionFor("application/octet-stream"))
    }

    @Test
    fun `lightbox state opens clamped and closes on session switch`() {
        val state = LightboxState()
        val gallery = ImageGallery(GalleryKind.Generated, listOf(ChatImage("a", "image/png", ""), ChatImage("b", "image/png", "")))
        state.open("s1", gallery, 9)
        assertEquals(1, state.target!!.startIndex)
        state.closeUnlessSession("s1")
        assertTrue(state.isOpen)
        state.closeUnlessSession("s2")
        assertFalse(state.isOpen)
        state.open("s1", ImageGallery(GalleryKind.Attachment, emptyList()), 0)
        assertFalse(state.isOpen)
    }

    @Test
    fun `lightbox keeps the thumbnail requester and hands it back once on close`() {
        val state = LightboxState()
        val gallery = ImageGallery(GalleryKind.Generated, listOf(ChatImage("a", "image/png", "")))
        val thumb = FocusRequester()
        state.open("s1", gallery, 0, returnFocus = thumb)
        assertSame(thumb, state.target!!.returnFocus)
        assertNull(state.pendingFocusReturn)
        state.close()
        assertFalse(state.isOpen)
        assertSame(thumb, state.pendingFocusReturn)
        assertSame(thumb, state.takeFocusReturn())
        assertNull(state.takeFocusReturn())
        // A session switch removes the thumbnail, so nothing is handed back.
        state.open("s1", gallery, 0, returnFocus = thumb)
        state.closeUnlessSession("s2")
        assertNull(state.pendingFocusReturn)
        // Without a requester, close hands nothing back.
        state.open("s1", gallery, 0)
        state.close()
        assertNull(state.takeFocusReturn())
    }

    // ---------------------------------------------------------------- selection lookup

    @Test
    fun `row key lookup finds messages inside folded work rows`() {
        val messages = ThreadPreviewData.long()
        val model = buildTranscriptModel(messages, ChatStatus.Completed)
        // Turn 1's tool row is folded into a work row keyed by its first collapsed message.
        val key = rowKeyContaining(model, "t1")
        val work = model.rows.first { it.key == key }
        assertTrue(work.item is dev.cline.termux.data.chat.ChatRenderItem.Work)
        assertEquals("a1:2", rowKeyContaining(model, "a1:2"))
        assertNull(rowKeyContaining(model, "nope"))
    }
}
