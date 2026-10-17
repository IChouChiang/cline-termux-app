package dev.cline.termux.ui.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CT B7: where the height-compact command layout puts its status line. */
class CommandStatusPlacementTest {
    @Test
    fun `height-compact command moves the status into the app bar at default font sizes`() {
        assertTrue(commandStatusInAppBar(heightCompactCommand = true, fontScale = 1f))
        assertTrue(commandStatusInAppBar(heightCompactCommand = true, fontScale = STATUS_IN_BAR_MAX_FONT_SCALE))
    }

    @Test
    fun `large font scales and other windows keep the status row`() {
        assertFalse(commandStatusInAppBar(heightCompactCommand = true, fontScale = 2f))
        assertFalse(commandStatusInAppBar(heightCompactCommand = false, fontScale = 1f))
    }

    @Test
    fun `the row is composed only for the status text or the command tabs`() {
        assertFalse("one command, status in the bar: no row", showsCompactStatusRow(statusInAppBar = true, commandCount = 1))
        assertTrue("tabs still need the row", showsCompactStatusRow(statusInAppBar = true, commandCount = 2))
        assertTrue("status text in the row", showsCompactStatusRow(statusInAppBar = false, commandCount = 1))
    }
}
