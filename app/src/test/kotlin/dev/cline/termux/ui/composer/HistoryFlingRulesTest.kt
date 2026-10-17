package dev.cline.termux.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A11.6 fling rules (brief §9.2): thresholds, edge exclusion, axis lock and direction (A18). */
class HistoryFlingRulesTest {

    /** A18: left to right recalls the previous (older) prompt, right to left the next (newer) one. */
    @Test fun leftToRightIsThePreviousPromptAndRightToLeftTheNext() {
        assertEquals(HistoryStep.Older, HistoryFlingRules.classify(dxDp = 120f, dyDp = 10f, velocityXDpPerS = 2_500f))
        assertEquals(HistoryStep.Newer, HistoryFlingRules.classify(dxDp = -120f, dyDp = 10f, velocityXDpPerS = -2_500f))
    }

    @Test fun aQuickWideFlingStepsFromTheThresholds() {
        assertEquals(HistoryStep.Older, HistoryFlingRules.classify(dxDp = 48f, dyDp = 0f, velocityXDpPerS = 1_000f))
        assertEquals(HistoryStep.Newer, HistoryFlingRules.classify(dxDp = -48f, dyDp = 0f, velocityXDpPerS = -1_000f))
    }

    @Test fun tooShortIsNotAFling() {
        assertNull(HistoryFlingRules.classify(dxDp = 47.9f, dyDp = 0f, velocityXDpPerS = 3_000f))
        assertNull(HistoryFlingRules.classify(dxDp = -47.9f, dyDp = 0f, velocityXDpPerS = -3_000f))
    }

    @Test fun aSlowDragNeverSteps() {
        assertNull(HistoryFlingRules.classify(dxDp = 200f, dyDp = 0f, velocityXDpPerS = 999f))
        assertNull(HistoryFlingRules.classify(dxDp = -200f, dyDp = 0f, velocityXDpPerS = -999f))
    }

    @Test fun itMustBeMoreThanTwiceAsWideAsTall() {
        assertNull(HistoryFlingRules.classify(dxDp = 100f, dyDp = 50f, velocityXDpPerS = 2_000f))
        assertEquals(HistoryStep.Older, HistoryFlingRules.classify(dxDp = 100f, dyDp = 49.9f, velocityXDpPerS = 2_000f))
        assertNull(HistoryFlingRules.classify(dxDp = -100f, dyDp = -60f, velocityXDpPerS = -2_000f))
    }

    @Test fun aFlickBackAgainstTheDragIsNotAFling() {
        assertNull(HistoryFlingRules.classify(dxDp = 100f, dyDp = 0f, velocityXDpPerS = -1_500f))
        assertNull(HistoryFlingRules.classify(dxDp = -100f, dyDp = 0f, velocityXDpPerS = 1_500f))
    }

    /** In a right-to-left layout the start is on the right: right to left is the previous prompt. */
    @Test fun rightToLeftLayoutsMirrorTheDirection() {
        assertEquals(HistoryStep.Older, HistoryFlingRules.classify(dxDp = -100f, dyDp = 0f, velocityXDpPerS = -2_000f, rtl = true))
        assertEquals(HistoryStep.Newer, HistoryFlingRules.classify(dxDp = 100f, dyDp = 0f, velocityXDpPerS = 2_000f, rtl = true))
    }

    @Test fun theOuterTwentyFourDpOfEitherEdgeNeverStartAFling() {
        val width = 412f
        assertFalse(HistoryFlingRules.startAllowed(downXDp = 0f, windowWidthDp = width))
        assertFalse(HistoryFlingRules.startAllowed(downXDp = 23.9f, windowWidthDp = width))
        assertTrue(HistoryFlingRules.startAllowed(downXDp = 24f, windowWidthDp = width))
        assertTrue(HistoryFlingRules.startAllowed(downXDp = 206f, windowWidthDp = width))
        assertTrue(HistoryFlingRules.startAllowed(downXDp = 388f, windowWidthDp = width))
        assertFalse(HistoryFlingRules.startAllowed(downXDp = 388.1f, windowWidthDp = width))
        assertFalse(HistoryFlingRules.startAllowed(downXDp = 412f, windowWidthDp = width))
    }

    @Test fun theLockWaitsForTheSlopThenPicksTheDominantAxis() {
        assertEquals(HistoryDragLock.Undecided, HistoryFlingRules.lock(dxPx = 5f, dyPx = 3f, slopPx = 8f))
        assertEquals(HistoryDragLock.Horizontal, HistoryFlingRules.lock(dxPx = -9f, dyPx = 4f, slopPx = 8f))
        assertEquals(HistoryDragLock.Other, HistoryFlingRules.lock(dxPx = 3f, dyPx = 9f, slopPx = 8f))
        assertEquals(HistoryDragLock.Other, HistoryFlingRules.lock(dxPx = 9f, dyPx = -9f, slopPx = 8f))
    }

    @Test fun theExpandIconFollowsThreeLines() {
        assertFalse(showsExpandEditor(0))
        assertFalse(showsExpandEditor(3))
        assertTrue(showsExpandEditor(4))
    }

    @Test fun listRowsShowAPromptOnOneLogicalLine() {
        assertEquals("fix the bug in main.kt", oneLinePreview("  fix the bug\n\n  in\tmain.kt \n"))
    }
}
