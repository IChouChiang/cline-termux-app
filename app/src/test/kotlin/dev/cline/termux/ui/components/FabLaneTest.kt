package dev.cline.termux.ui.components

import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.Spacing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DS §5.4 / CR §0.6: where a page FAB leaves room in the list under it. */
class FabLaneTest {
    @Test
    fun `normal window expands while scrolling up and pads the bottom only`() {
        val lane = fabLane(heightCompact = false, scrollingUp = true)
        assertTrue(lane.expanded)
        assertFalse(lane.endLane)
        assertEquals(24.dp, lane.endPadding(24.dp))
        assertEquals(FAB_CLEARANCE, lane.bottomClearance)
    }

    @Test
    fun `normal window collapses while scrolling down`() {
        assertFalse(fabLane(heightCompact = false, scrollingUp = false).expanded)
    }

    @Test
    fun `height-compact window keeps the FAB icon-only beside an end lane`() {
        for (scrollingUp in listOf(true, false)) {
            val lane = fabLane(heightCompact = true, scrollingUp = scrollingUp)
            assertFalse("never expanded at rest or on scroll", lane.expanded)
            assertTrue(lane.endLane)
            // 56dp FAB + 16dp margin + 16dp air: no item reaches under the FAB.
            assertEquals(88.dp, lane.endPadding(24.dp))
            assertEquals(88.dp, lane.endPadding(16.dp))
            assertEquals(Spacing.l, lane.bottomClearance)
        }
    }

    @Test
    fun `the lane never narrows a gutter wider than the FAB clearance`() {
        assertEquals(100.dp, fabLane(heightCompact = true, scrollingUp = true).endPadding(100.dp))
    }
}
