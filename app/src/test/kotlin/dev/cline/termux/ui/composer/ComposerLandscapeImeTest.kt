package dev.cline.termux.ui.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/07 D18 (CA C1.4 step 6, C1.15 item 17): the landscape IME composer. */
class ComposerLandscapeImeTest {
    @Test fun heightCompactWithTheImeCapsTheFieldAtTwoLines() {
        assertEquals(2, composerMaxLines(heightCompact = true, imeOpen = true))
    }

    @Test fun theCapReturnsWhenTheImeClosesOrTheWindowIsTall() {
        assertEquals(3, composerMaxLines(heightCompact = true, imeOpen = false))
        assertEquals(5, composerMaxLines(heightCompact = false, imeOpen = true))
        assertEquals(5, composerMaxLines(heightCompact = false, imeOpen = false))
    }

    @Test fun theSettingsChipsHideOnlyWhileTheImeIsOpenOnAHeightCompactWindow() {
        assertFalse(showsSettingsRow(heightCompact = true, imeOpen = true))
        assertTrue(showsSettingsRow(heightCompact = true, imeOpen = false))
        assertTrue(showsSettingsRow(heightCompact = false, imeOpen = true))
        assertTrue(showsSettingsRow(heightCompact = false, imeOpen = false))
    }

    @Test fun theFieldStartsAtOneLineWhileTheImeIsOpenOnAHeightCompactWindow() {
        assertEquals(1, composerMinLines(welcome = false, focused = true, heightCompact = true, imeOpen = true))
        assertEquals(1, composerMinLines(welcome = true, focused = true, heightCompact = true, imeOpen = true))
        assertEquals(2, composerMinLines(welcome = false, focused = true, heightCompact = true, imeOpen = false))
        assertEquals(2, composerMinLines(welcome = false, focused = true, heightCompact = false, imeOpen = true))
        assertEquals(2, composerMinLines(welcome = true, focused = false, heightCompact = false, imeOpen = false))
        assertEquals(1, composerMinLines(welcome = false, focused = false, heightCompact = false, imeOpen = false))
    }

    @Test fun sendAndStopMoveToTheFieldRowWhileTheSettingsRowIsHidden() {
        assertTrue(sendInFieldRow(kernelRowNormal = true, heightCompact = true, imeOpen = true))
        assertFalse("chips shown: Send stays in the settings row", sendInFieldRow(kernelRowNormal = true, heightCompact = true, imeOpen = false))
        assertFalse(sendInFieldRow(kernelRowNormal = true, heightCompact = false, imeOpen = true))
        assertFalse("a kernel row replaces Send anyway", sendInFieldRow(kernelRowNormal = false, heightCompact = true, imeOpen = true))
    }
}
