package dev.cline.termux.ui.approval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PendingInputFocusTest {

    @Test
    fun `a collapsed sheet returns focus to its banner in either input mode`() {
        assertEquals("banner", pendingInputReturnTarget(pending = true, keyboard = true, banner = "banner", composer = "composer"))
        assertEquals("banner", pendingInputReturnTarget(pending = true, keyboard = false, banner = "banner", composer = "composer"))
    }

    @Test
    fun `a sheet with nothing left returns focus to the composer field with a keyboard`() {
        assertEquals("composer", pendingInputReturnTarget(pending = false, keyboard = true, banner = "banner", composer = "composer"))
    }

    @Test
    fun `a sheet with nothing left leaves focus alone in touch mode`() {
        assertNull(pendingInputReturnTarget(pending = false, keyboard = false, banner = "banner", composer = "composer"))
    }

    @Test
    fun `approval focus returns to the banner that reopened the sheet`() {
        val focus = PendingInputFocus()
        assertSame(focus.approvalBanner, focus.approvalReturn(pending = true, keyboard = true))

        focus.reviewFrom(pane = true)
        assertSame(focus.paneApprovalBanner, focus.approvalReturn(pending = true, keyboard = true))

        focus.reviewFrom(pane = false)
        assertSame(focus.approvalBanner, focus.approvalReturn(pending = true, keyboard = true))
    }

    @Test
    fun `a gone tool pane banner falls back to the dock banner`() {
        val focus = PendingInputFocus()
        focus.reviewFrom(pane = true)
        focus.paneBannerGone()
        assertSame(focus.approvalBanner, focus.approvalReturn(pending = true, keyboard = false))
    }

    @Test
    fun `approval and question fall back to the composer field once resolved`() {
        val focus = PendingInputFocus()
        focus.reviewFrom(pane = true)
        assertSame(focus.composerField, focus.approvalReturn(pending = false, keyboard = true))
        assertSame(focus.composerField, focus.questionReturn(pending = false, keyboard = true))
        assertNull(focus.questionReturn(pending = false, keyboard = false))
        assertSame(focus.questionBanner, focus.questionReturn(pending = true, keyboard = false))
    }
}
