package dev.cline.termux.ui.chat

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** D18: the transcript keeps a visible row above the composer with the IME open in landscape. */
class TranscriptLandscapeImeTest {
    @Test fun theScrollButtonClearanceGoesOnlyWhileTheImeIsOpenOnAHeightCompactWindow() {
        assertEquals(8.dp, transcriptBottomPadding(heightCompact = true, imeOpen = true))
        assertEquals(56.dp, transcriptBottomPadding(heightCompact = true, imeOpen = false))
        assertEquals(56.dp, transcriptBottomPadding(heightCompact = false, imeOpen = true))
        assertEquals(56.dp, transcriptBottomPadding(heightCompact = false, imeOpen = false))
    }
}
