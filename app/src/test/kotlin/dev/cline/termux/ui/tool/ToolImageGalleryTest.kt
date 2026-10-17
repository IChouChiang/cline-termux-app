package dev.cline.termux.ui.tool

import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.ui.chat.LightboxState
import dev.cline.termux.ui.chat.rows.GalleryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tool output images open in the shared chat lightbox (DS #ImageLightbox / Carousel). */
class ToolImageGalleryTest {
    private val images = listOf(
        ChatImage("img-a", "image/png", "AAAA"),
        ChatImage("img-b", "image/jpeg", "BBBB"),
        ChatImage("img-c", "image/webp", "CCCC"),
    )

    @Test
    fun `tool images form a generated gallery like the thumbnails`() {
        val gallery = toolImageGallery(images)
        assertEquals(GalleryKind.Generated, gallery.kind)
        assertSame(images, gallery.images)
    }

    @Test
    fun `opening a tool image targets the session and the tapped index`() {
        val state = LightboxState()
        state.open("sess-1", toolImageGallery(images), 2)
        val target = state.target
        assertTrue(state.isOpen)
        assertEquals("sess-1", target?.sessionId)
        assertEquals(2, target?.startIndex)
        assertEquals(images, target?.gallery?.images)
    }

    @Test
    fun `an out of range index is clamped and an empty gallery never opens`() {
        val state = LightboxState()
        state.open("sess-1", toolImageGallery(images), 9)
        assertEquals(2, state.target?.startIndex)
        state.close()
        assertNull(state.target)

        state.open("sess-1", toolImageGallery(emptyList()), 0)
        assertFalse(state.isOpen)
    }
}
