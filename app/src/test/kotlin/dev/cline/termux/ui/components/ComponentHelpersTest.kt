package dev.cline.termux.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the shared sheet back gesture and the ExtendedFAB scroll direction. */
class ComponentHelpersTest {

    @Test
    fun `sheet back scale runs from 1 to 0_9 with the gesture`() {
        assertEquals(1f, sheetBackScale(0f), 0.0001f)
        assertEquals(0.95f, sheetBackScale(0.5f), 0.0001f)
        assertEquals(SHEET_BACK_MIN_SCALE, sheetBackScale(1f), 0.0001f)
    }

    @Test
    fun `sheet back scale clamps progress outside 0 to 1`() {
        assertEquals(1f, sheetBackScale(-0.3f), 0.0001f)
        assertEquals(SHEET_BACK_MIN_SCALE, sheetBackScale(1.7f), 0.0001f)
    }

    @Test
    fun `a smaller first visible index is scrolling up whatever the offset`() {
        assertTrue(isScrollingUp(previous = 5 to 0, current = 4 to 300))
        assertFalse(isScrollingUp(previous = 4 to 300, current = 5 to 0))
    }

    @Test
    fun `within one item a smaller offset is up and a larger one is down`() {
        assertTrue(isScrollingUp(previous = 3 to 120, current = 3 to 40))
        assertFalse(isScrollingUp(previous = 3 to 40, current = 3 to 120))
    }

    @Test
    fun `no movement keeps the label shown`() {
        assertTrue(isScrollingUp(previous = 0 to 0, current = 0 to 0))
        assertTrue(isScrollingUp(previous = 7 to 18, current = 7 to 18))
    }
}
