package dev.cline.termux.ui.chat.rows

import android.content.res.Resources
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.CredentialAction
import dev.cline.termux.data.session.MistakeLimitPrompt
import dev.cline.termux.data.session.StopNotices
import dev.cline.termux.ui.components.rememberCopyQuantityResources
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.quantityResources
import dev.cline.termux.ui.theme.Spacing

/*
 * The transcript's non-message rows: `session.notice` rows (HC §3.4.6, an Android design),
 * the transport line (HC §3.9) and the error banner (HC §3.10).
 */

/**
 * A `session.notice` row: 20dp icon + the kernel's message, untranslated (DS §4.2 SessionNotice
 * row). A recoverable mid-run error (A11.9 Verbose) is a muted error row: the outlined `error`
 * glyph in the `error` colour, the text muted like every notice, since the run goes on. No
 * current Hub sends such a notice (`ChatProjector.RECOVERABLE_ERROR_REASONS`); the branch waits for one.
 * The app's own stop notices (A17, [StopNotices]) use the same row with the app's words ([noticeLines]).
 */
@Composable
fun SessionNoticeRow(message: ChatMessage, modifier: Modifier = Modifier) {
    val kind = noticeIconOf(message.meta.noticeType, message.meta.reason)
    val icon = when (kind) {
        NoticeIcon.Info -> ClineIcons.Info
        NoticeIcon.Recovery -> ClineIcons.Autorenew
        NoticeIcon.Stop -> ClineIcons.PauseCircle
        NoticeIcon.RecoverableError -> ClineIcons.Error
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tint = if (kind == NoticeIcon.RecoverableError) MaterialTheme.colorScheme.error else muted
    val resources = LocalResources.current
    val quantity = rememberCopyQuantityResources()
    val lines = remember(message, resources, quantity) { noticeLines(message, resources, quantity) }
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = NoticeRowMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(StatusIconSize))
        Column(Modifier.weight(1f)) {
            Text(lines.text, style = MaterialTheme.typography.bodyMedium, color = muted)
            lines.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
    }
}

/** A notice row's words: [text], and for an app stop notice the kernel's detail as a second line. */
internal data class NoticeLines(val text: String, val detail: String? = null)

/**
 * The kernel's message as it came, or the app's own stop notice (A17) in the app's words: "Stopped
 * after N mistakes in a row" with "Latest: <the kernel's detail>" under it, or "Stopped: the
 * question was not answered within 5 minutes". [quantity] picks the plural forms in the copy's
 * language (`quantityResources`).
 */
internal fun noticeLines(message: ChatMessage, resources: Resources, quantity: Resources = quantityResources(resources)): NoticeLines =
    when (message.meta.reason) {
        StopNotices.REASON_STOPPED -> {
            val mistakes = message.meta.consecutiveMistakes?.takeIf { it > 0 }
            val text = if (mistakes == null) {
                resources.getString(R.string.chat_notice_mistake_stopped_repeated)
            } else {
                quantity.getQuantityString(R.plurals.chat_notice_mistake_stopped, mistakes, mistakes)
            }
            val detail = message.content.trim().takeIf { it.isNotEmpty() }?.let { resources.getString(R.string.mistake_limit_question_latest, it) }
            NoticeLines(text, detail)
        }
        StopNotices.REASON_TIMED_OUT -> {
            val minutes = (MistakeLimitPrompt.TIMEOUT_MS / MINUTE_MS).toInt()
            NoticeLines(quantity.getQuantityString(R.plurals.chat_notice_mistake_timed_out, minutes, minutes))
        }
        else -> NoticeLines(message.content)
    }

private const val MINUTE_MS = 60_000L

private val NoticeRowMinHeight = 40.dp

/**
 * The glyph of notice rows and error surfaces (DS §4.2). These are not disclosure rows, so the
 * compact [DisclosureRowTokens] (A24) leave them as they were.
 */
private val StatusIconSize = 20.dp

/**
 * The line at the end of the transcript while the socket is not connected (HC §3.9): 16dp
 * spinner + `bodySmall` text; "Chat backend unavailable" takes a `warning` glyph instead of
 * the spinner (nothing is retrying), and Termux asleep adds "Start kernel to continue." in
 * `error`. Hidden when [state] is connected or an error banner is shown ([transportLineVisible]).
 */
@Composable
fun TransportLine(state: ChatTransportState, modifier: Modifier = Modifier) {
    if (state == ChatTransportState.Connected) return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val text = stringResource(
        when (state) {
            ChatTransportState.Connecting -> R.string.chat_transport_connecting
            ChatTransportState.Reconnecting -> R.string.chat_transport_reconnecting
            ChatTransportState.Draining -> R.string.chat_transport_draining
            ChatTransportState.Unavailable, ChatTransportState.TermuxAsleep, ChatTransportState.Connected ->
                R.string.chat_transport_unavailable
        },
    )
    val idle = state == ChatTransportState.Unavailable || state == ChatTransportState.TermuxAsleep
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Spacing.l)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Box(Modifier.size(TransportGlyphSize), contentAlignment = Alignment.Center) {
                if (idle) {
                    Icon(ClineIcons.Warning, contentDescription = null, tint = muted, modifier = Modifier.size(TransportGlyphSize))
                } else {
                    CircularProgressIndicator(Modifier.size(TransportGlyphSize), strokeWidth = 2.dp, color = muted)
                }
            }
            Text(text, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        if (state == ChatTransportState.TermuxAsleep) {
            Text(
                text = stringResource(R.string.chat_transport_start_kernel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = TransportGlyphSize + Spacing.s),
            )
        }
    }
}

private val TransportGlyphSize = 16.dp

/**
 * The leading `error` glyph of the error surfaces (DS §8.1.3: error rows show the icon as well
 * as the colour): 20dp in the surface's content colour (`onErrorContainer`), decorative.
 */
@Composable
internal fun ErrorRowIcon(modifier: Modifier = Modifier) {
    Icon(ClineIcons.Tools.Error, contentDescription = null, modifier = modifier.size(StatusIconSize))
}

/** The banner's optional button: "Retry", or the credential action for a credential failure (HC §3.10). */
@Immutable
sealed interface ErrorBannerAction {
    val onClick: () -> Unit

    data class Retry(override val onClick: () -> Unit) : ErrorBannerAction
    data class Credential(val action: CredentialAction, override val onClick: () -> Unit) : ErrorBannerAction
}

/**
 * The error banner under the last row (HC §3.10; desktop `shouldShowErrorBanner` block):
 * `errorContainer`, `shapes.small`, 1dp `error` @ 40 % border, 12dp padding, selectable
 * `bodyMedium`. [showText] = false when the newest error row already carries the same text
 * ([errorBannerShowsText]); the banner then holds only its action.
 */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    showText: Boolean = true,
    action: ErrorBannerAction? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.l),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
    ) {
        Column(Modifier.padding(Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            if (showText) {
                // DS §8.1.3: the error icon as well as the colour; decorative, the text says it.
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    ErrorRowIcon()
                    SelectionContainer(Modifier.weight(1f)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (action != null) {
                OutlinedButton(onClick = action.onClick) {
                    Text(
                        when (action) {
                            is ErrorBannerAction.Retry -> stringResource(R.string.chat_banner_retry)
                            is ErrorBannerAction.Credential -> stringResource(action.action.labelRes)
                        },
                    )
                }
            }
        }
    }
}
