package dev.cline.termux.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rail keeps the selected destination on screen in a height-compact window (DS §5.1, §3.4). */
class RailScrollTargetTest {

    @Test
    fun `fully visible item needs no scroll`() {
        assertNull(railScrollTarget(itemTop = 100, itemBottom = 156, scroll = 0, viewport = 300))
        assertNull(railScrollTarget(itemTop = 100, itemBottom = 156, scroll = 100, viewport = 56))
    }

    @Test
    fun `item below the viewport scrolls just enough to show its bottom`() {
        // Settings at 180..236 in a 200px viewport at the top: scroll to 36.
        assertEquals(36, railScrollTarget(itemTop = 180, itemBottom = 236, scroll = 0, viewport = 200))
    }

    @Test
    fun `item half cut at the bottom edge scrolls into view`() {
        assertEquals(20, railScrollTarget(itemTop = 160, itemBottom = 220, scroll = 0, viewport = 200))
    }

    @Test
    fun `item above the viewport aligns its top`() {
        assertEquals(10, railScrollTarget(itemTop = 10, itemBottom = 66, scroll = 120, viewport = 200))
    }

    @Test
    fun `item taller than the viewport aligns its top`() {
        assertEquals(50, railScrollTarget(itemTop = 50, itemBottom = 400, scroll = 0, viewport = 100))
    }

    @Test
    fun `nothing measured yet returns null`() {
        assertNull(railScrollTarget(itemTop = 180, itemBottom = 236, scroll = 0, viewport = 0))
        assertNull(railScrollTarget(itemTop = 236, itemBottom = 180, scroll = 0, viewport = 200))
    }
}
