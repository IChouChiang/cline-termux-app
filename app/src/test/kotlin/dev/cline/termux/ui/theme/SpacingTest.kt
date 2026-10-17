package dev.cline.termux.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared layout constants of DS §3.3 and the §5.1 gutter breakpoint. */
class SpacingTest {

    @Test
    fun gutterIsCompactBelowTheMediumBreakpoint() {
        assertEquals(Spacing.gutterCompact, Spacing.gutterFor(412.dp))
        assertEquals(Spacing.gutterCompact, Spacing.gutterFor(599.9.dp))
    }

    @Test
    fun gutterIsWideFromTheMediumBreakpoint() {
        assertEquals(Spacing.gutterWide, Spacing.gutterFor(600.dp))
        assertEquals(Spacing.gutterWide, Spacing.gutterFor(778.dp))
        assertEquals(Spacing.gutterWide, Spacing.gutterFor(1244.dp))
    }

    @Test
    fun guttersSitOnTheScale() {
        assertEquals(16.dp, Spacing.gutterCompact)
        assertEquals(24.dp, Spacing.gutterWide)
        assertEquals(Spacing.l, Spacing.gutterCompact)
        assertEquals(Spacing.xxl, Spacing.gutterWide)
        assertEquals(600.dp, Spacing.gutterBreakpoint)
    }

    @Test
    fun columnWidths() {
        assertEquals(840.dp, Spacing.contentMaxWidth)
        assertEquals(720.dp, Spacing.pageMaxWidth)
        assertTrue(Spacing.pageMaxWidth < Spacing.contentMaxWidth)
    }
}
