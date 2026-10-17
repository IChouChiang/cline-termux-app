package dev.cline.termux.ui.chat.rows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.CredentialAction
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/*
 * The seams between the rows and their host (the chat screen and its ViewModels). The rows
 * own only row-local UI state (disclosures, the revealed action row, the copy check mark, the
 * proceed button's request); everything that changes the session goes out through
 * [TranscriptCallbacks], whose null members hide the matching control (the desktop passes
 * optional handlers the same way).
 */

/** Which lightbox labels to use (HC §5: attachments vs generated images). */
enum class GalleryKind { Attachment, Generated }

/**
 * The images a thumbnail belongs to, so the lightbox can page through them. [thumbnailKeys]
 * are the `ThumbnailCache` keys of the thumbnails on screen (same order as [images]; empty
 * when none were drawn), so the lightbox can show the cached thumbnail scaled up while it
 * decodes the full image (HC §5.5 "Loading").
 */
@Immutable
data class ImageGallery(val kind: GalleryKind, val images: List<ChatImage>, val thumbnailKeys: List<String> = emptyList())

/** The reply of `run.proceed_while_running {sessionId, toolCallId}` → `{detachedCount}`. */
sealed interface ProceedOutcome {
    data class Detached(val count: Int) : ProceedOutcome

    /** `detachedCount == 0`: "The command finished before it could be detached." */
    data object NothingDetached : ProceedOutcome

    /** The command failed; a null [message] shows "Could not detach the running command." */
    data class Failed(val message: String? = null) : ProceedOutcome
}

/**
 * Host actions. Hold one instance per screen (`remember`), since every row compares it.
 *
 * - [onEditMessage] / [onRestoreCheckpoint]: open the checkpoint spec's confirm dialogs.
 * - [onResend]: the interrupted row's "Resend" (HC §3.4.7).
 * - [onCredentialAction]: "Sign in to Cline" → Settings › Account, "Open API providers" → Providers.
 * - [onExpandImage]: open the lightbox (screen D) at an index.
 * - [onProceedWhileRunning]: `run.proceed_while_running`; the row shows the pending spinner.
 * - [onOpenToolDetail]: the Tool detail screen / pane (CT B.3, DS-A5).
 * - [onDisclosureToggled]: a row was expanded or collapsed by the user; the list holds the
 *   row's header in place for [WORK_PIN_MS] (HC §3.7) and receives [onRowHeightDelta].
 */
@Immutable
data class TranscriptCallbacks(
    val onEditMessage: ((messageId: String, content: String, runCount: Int) -> Unit)? = null,
    val onRestoreCheckpoint: ((messageId: String, runCount: Int) -> Unit)? = null,
    val onResend: ((messageId: String) -> Unit)? = null,
    val onCredentialAction: ((CredentialAction) -> Unit)? = null,
    val onExpandImage: ((gallery: ImageGallery, index: Int) -> Unit)? = null,
    val onProceedWhileRunning: (suspend (sessionId: String, toolCallId: String?) -> ProceedOutcome)? = null,
    val onOpenToolDetail: ((sessionId: String, toolCallId: String) -> Unit)? = null,
    val onDisclosureToggled: ((rowKey: String) -> Unit)? = null,
    val onRowHeightDelta: ((rowKey: String, deltaPx: Int) -> Unit)? = null,
)

/** HC §3.7 `WORK_PIN_MS`: how long a toggled row's header is held in place. */
const val WORK_PIN_MS = 350L

/**
 * "Select text" mode (HC §3.14): at most one message body is a selection surface at a time.
 * The chat screen clears it on a tap outside the message; back and a second tap on the
 * message clear it from inside the row.
 */
@Stable
class TranscriptSelection {
    var selectedMessageId: String? by mutableStateOf(null)
        private set

    fun select(messageId: String) {
        selectedMessageId = messageId
    }

    fun clear() {
        selectedMessageId = null
    }

    fun isSelecting(messageId: String): Boolean = selectedMessageId == messageId
}

@Composable
fun rememberTranscriptSelection(): TranscriptSelection = remember { TranscriptSelection() }

/** Set by [TranscriptItem]: rows call it when the user toggles one of their disclosures. */
internal val LocalDisclosureToggled = compositionLocalOf<(() -> Unit)?> { null }

/** The space above a row (DS §3.3): the block gap, plus 16dp above a user turn (desktop `mt-4`). */
val TranscriptGap.height: Dp
    get() = when (this) {
        TranscriptGap.None, TranscriptGap.Tight -> 0.dp
        TranscriptGap.Block -> DisclosureRowTokens.BlockGap
        TranscriptGap.Turn -> DisclosureRowTokens.BlockGap + Spacing.l
    }

/**
 * The compact transcript rows (A24, DS §3.3, §3.4, §4.1 #2–#4): tool rows, thinking rows, the
 * "Worked for…" block and the "Thinking..." indicator take their density metrics (height, icon
 * and spinner, label style, gaps, rail) from here and nowhere else, so the density is changed
 * in this one place (the reviewed fallback is a 44dp [Height]).
 */
internal object DisclosureRowTokens {
    /** Row height, which is also the touch target: the M3 dense list height (DS §3.4). */
    val Height: Dp = 40.dp

    /** The leading slot and the kind, error or chevron glyph in it. */
    val IconSize: Dp = 18.dp

    /** The spinner that takes a running row's icon slot, so the label never moves. */
    val SpinnerSize: Dp = 14.dp
    val SpinnerStroke: Dp = 2.dp

    /** Between the icon slot and the label. */
    val IconLabelGap: Dp = Spacing.s

    /** The label, and the `code` parts inside it (same size and line height as the label). */
    val labelStyle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall
    val labelCodeStyle: TextStyle
        @Composable @ReadOnlyComposable get() = ClineMono.small

    /** The space between transcript blocks ([TranscriptGap.Block]). */
    val BlockGap: Dp = Spacing.xs

    /** The body's rail: a 1dp line under the icon's centre, the content [RailGap] past it. */
    val RailLineInset: Dp = IconSize / 2
    val RailGap: Dp = Spacing.m
    val RailContentInset: Dp = RailLineInset + RailGap
}

/** Message copy check-mark time (HC §3.4.1: desktop 1.6 s overrides DS's generic 2 s). */
const val MESSAGE_COPIED_MILLIS = 1600L
