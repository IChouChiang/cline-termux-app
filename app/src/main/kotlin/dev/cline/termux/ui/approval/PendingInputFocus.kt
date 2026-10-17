package dev.cline.termux.ui.approval

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester

/**
 * Where keyboard focus goes when an approval or question sheet closes (DS §8.1.7). While
 * something is still [pending] the sheet has collapsed to its banner, so the [banner] gets it
 * back; once nothing is left the banner is gone and the [composer] field takes it, but only in
 * keyboard input mode ([keyboard]): in touch mode focusing the field would raise the soft
 * keyboard nobody asked for, so focus stays where it is (null).
 */
internal fun <T : Any> pendingInputReturnTarget(pending: Boolean, keyboard: Boolean, banner: T, composer: T): T? =
    when {
        pending -> banner
        keyboard -> composer
        else -> null
    }

/**
 * The chat screen's focus requesters for the approval and question sheets: one per banner that
 * can reopen a sheet and the composer field as the fallback once the sheet has nothing left.
 * The approval sheet can also be reopened from the banner in the tool pane (C3.3); [reviewFrom]
 * records which banner did it so focus returns there.
 */
@Stable
class PendingInputFocus {
    val approvalBanner = FocusRequester()
    val paneApprovalBanner = FocusRequester()
    val questionBanner = FocusRequester()
    val composerField = FocusRequester()

    // State, so a sheet host that read the target recomposes when it changes.
    private var approvalFromPane by mutableStateOf(false)

    /** Call from the banner's action: [pane] is true for the tool pane's banner. */
    fun reviewFrom(pane: Boolean) {
        approvalFromPane = pane
    }

    /** The tool pane's banner left the screen; focus can no longer return to it. */
    fun paneBannerGone() {
        approvalFromPane = false
    }

    /** The approval sheet's return target while [pending] cards remain (see [pendingInputReturnTarget]). */
    fun approvalReturn(pending: Boolean, keyboard: Boolean): FocusRequester? =
        pendingInputReturnTarget(pending, keyboard, if (approvalFromPane) paneApprovalBanner else approvalBanner, composerField)

    /** The question sheet's return target while [pending] questions remain. */
    fun questionReturn(pending: Boolean, keyboard: Boolean): FocusRequester? =
        pendingInputReturnTarget(pending, keyboard, questionBanner, composerField)
}
