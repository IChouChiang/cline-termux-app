package dev.cline.termux.ui.chat.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.isDated
import dev.cline.termux.data.schedules.ClockFormat
import dev.cline.termux.data.session.TurnStats
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberCopier
import dev.cline.termux.ui.components.rememberIs24HourClock
import dev.cline.termux.ui.diff.TransientFlag
import dev.cline.termux.ui.diff.rememberTransientFlag
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/*
 * Message action rows (HC §3.4.1–§3.4.2, DS §4.1 #5; desktop `MessageActions` /
 * `MessageAction` in message-bubble.tsx): 40dp icon buttons with 48dp targets and 16dp icons,
 * the short time in `labelMedium`, a 16dp spinner in place of a pending action's icon, and
 * the action errors in `bodySmall` `error`. Copy is row-local with a 1.6 s check mark.
 */

/** 16dp action icons (HC §3.4.1). */
internal val ActionIconSize = 16.dp

/** One action: [pending] swaps the icon for a spinner and disables it; the tooltip is the desktop `title`. */
@Composable
internal fun MessageActionButton(
    icon: ImageVector,
    contentDescription: String,
    tooltip: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    pending: Boolean = false,
    unavailableReason: String? = null,
) {
    // CT C.3: an unavailable icon with a reason keeps taking taps (the host answers with that
    // reason in a snackbar), long-presses and TalkBack focus; it only draws at 38 %.
    val explained = unavailableReason != null && (pending || !enabled)
    PlainTooltipBox(text = tooltip) {
        IconButton(
            onClick = onClick,
            enabled = explained || (enabled && !pending),
            modifier = Modifier
                .focusRing(CircleShape)
                .then(if (explained) Modifier.semantics { stateDescription = unavailableReason.orEmpty() } else Modifier),
        ) {
            if (pending) {
                InlineSpinner(Modifier.size(ActionIconSize).semantics { this.contentDescription = contentDescription })
            } else {
                val tint = LocalContentColor.current.let { if (explained) it.copy(alpha = UNAVAILABLE_ALPHA) else it }
                Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(ActionIconSize))
            }
        }
    }
}

private const val UNAVAILABLE_ALPHA = 0.38f

/**
 * Desktop `toLocaleTimeString(hour:numeric, minute:2-digit)` → localized short time (DS §8.2 #5)
 * on the user's 24-hour setting ([is24Hour], ClockFormat), in the default format locale.
 */
internal fun formatMessageTime(
    createdAt: Long,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(Locale.Category.FORMAT),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String? {
    if (!isDated(createdAt) || createdAt <= 0) return null
    return ClockFormat.formatTime(Instant.ofEpochMilli(createdAt).atZone(zone), locale, is24Hour)
}

@Composable
internal fun MessageTime(createdAt: Long) {
    val is24Hour = rememberIs24HourClock()
    val time = remember(createdAt, is24Hour) { formatMessageTime(createdAt, is24Hour = is24Hour) } ?: return
    Text(time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Copies a message's text as a plain clip and flips [flag] for 1.6 s; a clipboard failure
 * shows "Copy failed. The message could not be copied to the clipboard." (desktop toast).
 */
@Composable
internal fun rememberMessageCopy(flag: TransientFlag): (label: String, text: String) -> Unit {
    val failed = stringResource(
        R.string.msg_copy_failed_snackbar,
        stringResource(R.string.msg_copy_failed_title),
        stringResource(R.string.msg_copy_failed_body),
    )
    val copier = rememberCopier(failureMessage = failed)
    return remember(copier, flag) {
        { label, text -> copier.copy(label, text) { ok -> if (ok) flag.trigger() } }
    }
}

@Composable
internal fun rememberCopiedFlag(): TransientFlag = rememberTransientFlag(MESSAGE_COPIED_MILLIS)

/** The user bubble's action row: copy, edit, restore, then the time; errors end-aligned under it. */
@Composable
internal fun UserActionRow(
    message: ChatMessage,
    displayText: String,
    runCount: Int?,
    actions: UserActionSet,
    copied: Boolean,
    onCopy: () -> Unit,
    callbacks: TranscriptCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MessageActionButton(
                icon = if (copied) ClineIcons.Chrome.Copied else ClineIcons.Chrome.Copy,
                contentDescription = stringResource(if (copied) R.string.msg_copied_user else R.string.msg_copy_user),
                tooltip = stringResource(if (copied) R.string.msg_copied_tooltip else R.string.msg_copy_tooltip),
                onClick = onCopy,
            )
            val blocked = when (actions.blocked) {
                VersioningBlock.Busy -> stringResource(R.string.checkpoint_busy)
                VersioningBlock.InProgress -> stringResource(R.string.checkpoint_in_progress)
                null -> null
            }
            val inProgress = stringResource(R.string.checkpoint_pending_state)
            val edit = callbacks.onEditMessage
            if (actions.edit && edit != null && runCount != null) {
                MessageActionButton(
                    icon = ClineIcons.Chrome.EditMessage,
                    contentDescription = stringResource(R.string.msg_edit_user),
                    tooltip = stringResource(R.string.msg_edit_tooltip),
                    onClick = { edit(message.id, displayText, runCount) },
                    enabled = actions.editEnabled,
                    pending = actions.editPending,
                    unavailableReason = if (actions.editPending) inProgress else blocked,
                )
            }
            val restore = callbacks.onRestoreCheckpoint
            val checkpoint = actions.checkpoint
            if (actions.restore && restore != null && checkpoint != null) {
                MessageActionButton(
                    icon = ClineIcons.Chrome.RestoreCheckpoint,
                    contentDescription = stringResource(R.string.msg_restore),
                    tooltip = stringResource(R.string.msg_restore),
                    onClick = { restore(message.id, checkpoint.runCount) },
                    enabled = actions.restoreEnabled,
                    pending = actions.restorePending,
                    unavailableReason = if (actions.restorePending) inProgress else blocked,
                )
            }
            MessageTime(message.createdAt)
        }
        actions.restoreError?.let { ActionErrorText(it.message ?: stringResource(R.string.msg_restore_error), TextAlign.End) }
        actions.editError?.let { ActionErrorText(it.message ?: stringResource(R.string.msg_edit_error), TextAlign.End) }
    }
}

/**
 * The assistant action row: copy raw content, then the time and the turn's stats in Verbose
 * ([stats], A11.9). The row wraps when the stats do not fit beside the time. No fork here
 * (decided A16): the whole-session fork is in the chat's three-dot and the drawer's row menu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantActionRow(
    message: ChatMessage,
    copied: Boolean,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
    stats: TurnStats? = null,
) {
    FlowRow(modifier, horizontalArrangement = Arrangement.Start, itemVerticalAlignment = Alignment.CenterVertically) {
        MessageActionButton(
            icon = if (copied) ClineIcons.Chrome.Copied else ClineIcons.Chrome.Copy,
            contentDescription = stringResource(if (copied) R.string.msg_copied_assistant else R.string.msg_copy_assistant),
            tooltip = stringResource(if (copied) R.string.msg_copied_tooltip else R.string.msg_copy_raw_tooltip),
            onClick = onCopy,
        )
        MessageTime(message.createdAt)
        if (stats != null) TurnStatsText(stats, Modifier.padding(start = Spacing.s))
    }
}

/** CT §1.6: the pending restore / edit waits for the Hub drain; end-aligned under the user row. */
@Composable
internal fun DrainingWaitLine(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.checkpoint_draining),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/** A restore / edit / fork failure under the action row: a polite live region (CT C.9/C.10, HC §3.10). */
@Composable
private fun ActionErrorText(text: String, align: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        textAlign = align,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}
