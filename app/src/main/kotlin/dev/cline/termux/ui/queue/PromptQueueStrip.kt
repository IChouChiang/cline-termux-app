package dev.cline.termux.ui.queue

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * The prompt queue strip (composer-approvals.md C2.3, DS PromptQueueStrip): one docked
 * child of `ComposerSurface` (RectangleShape, `surfaceContainer`, min 48dp). One item shows
 * its prompt on one line; several show "N prompts queued". The Steer button steers the first
 * item when it is not steering yet; tapping anywhere else opens the sheet. Not composed when
 * [PromptQueueState.stripVisible] is false (the caller checks).
 */
@Composable
fun PromptQueueStrip(
    state: PromptQueueState,
    onOpenSheet: () -> Unit,
    onSteerFirst: () -> Unit,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
) {
    val first = state.items.firstOrNull() ?: return
    val count = copyPluralStringResource(R.plurals.queue_count, state.items.size, state.items.size)
    val nextTurn = stringResource(R.string.queue_next_turn)
    val steerLabel = stringResource(R.string.cd_queue_steer)
    val stripDescription = stringResource(R.string.cd_queue_strip, count)
    val canSteer = state.canSteerFirst && state.actionsEnabled
    val steering = state.pendingAction == QueueAction.Steer && state.pendingId == first.id
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RectangleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .focusRing(RectangleShape, inset = true)
                .clickable(role = Role.Button, onClick = onOpenSheet)
                .semantics {
                    contentDescription = stripDescription
                    if (canSteer) {
                        customActions = listOf(CustomAccessibilityAction(steerLabel) { onSteerFirst(); true })
                    }
                }
                .padding(start = Spacing.l, end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            QueueStatusIcon(first.steer)
            Box(Modifier.width(Spacing.m))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                if (state.items.size == 1) {
                    Text(
                        text = first.displayPrompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (first.steer) {
                        Box(Modifier.width(Spacing.s))
                        TagBadge(nextTurn)
                    }
                } else {
                    Text(
                        text = if (first.steer) stringResource(R.string.queue_count_next_turn, count, nextTurn) else count,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (state.canSteerFirst) {
                if (steering) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                } else {
                    TooltipIconButton(
                        icon = ClineIcons.Chrome.SteerQueuedPrompt,
                        contentDescription = steerLabel,
                        tooltip = stringResource(R.string.tt_queue_steer),
                        onClick = onSteerFirst,
                        enabled = canSteer,
                        iconSize = 20.dp,
                    )
                }
            }
            Icon(
                ClineIcons.Chrome.Expand,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.s).size(20.dp),
            )
        }
    }
}

/** Clock for a queued prompt, corner-down-left in `primary` for the one that steers (desktop). */
@Composable
internal fun QueueStatusIcon(steer: Boolean, modifier: Modifier = Modifier) {
    Icon(
        imageVector = if (steer) ClineIcons.Chrome.SteerQueuedPrompt else ClineIcons.Schedule,
        contentDescription = null,
        tint = if (steer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(20.dp),
    )
}

internal val previewItems = listOf(
    QueuedPromptItem("q1", "Fix the failing test first", steer = true, attachmentCount = 0),
    QueuedPromptItem("q2", "Also add tests for the parser", steer = false, attachmentCount = 1),
    QueuedPromptItem("q3", "/team build a hello world script", steer = false, attachmentCount = 0),
)

@LightDarkPreviews
@Composable
private fun PromptQueueStripOnePreview() {
    ComponentPreview(padding = false) {
        PromptQueueStrip(PromptQueueState(items = previewItems.drop(1).take(1), loaded = true), onOpenSheet = {}, onSteerFirst = {})
    }
}

@LightDarkPreviews
@Composable
private fun PromptQueueStripOneSteeringPreview() {
    ComponentPreview(padding = false) {
        PromptQueueStrip(PromptQueueState(items = previewItems.take(1), loaded = true), onOpenSheet = {}, onSteerFirst = {})
    }
}

@LightDarkPreviews
@Composable
private fun PromptQueueStripSeveralPreview() {
    ComponentPreview(padding = false) {
        Column {
            PromptQueueStrip(PromptQueueState(items = previewItems, loaded = true), onOpenSheet = {}, onSteerFirst = {})
            PromptQueueStrip(PromptQueueState(items = previewItems.drop(1), loaded = true), onOpenSheet = {}, onSteerFirst = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun PromptQueueStripPendingPreview() {
    ComponentPreview(padding = false) {
        Column {
            PromptQueueStrip(
                PromptQueueState(items = previewItems.drop(1), loaded = true, pendingId = "q2", pendingAction = QueueAction.Steer),
                onOpenSheet = {},
                onSteerFirst = {},
            )
            PromptQueueStrip(
                PromptQueueState(items = previewItems.drop(1), loaded = true, link = QueueLink.Reconnecting),
                onOpenSheet = {},
                onSteerFirst = {},
            )
        }
    }
}
