package dev.cline.termux.ui.chat

import android.content.res.Configuration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.StallFact
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionState
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/** One stall of one session (A4): what "Keep waiting" dismisses. */
@Immutable
data class StallEpisode(val sessionId: String, val since: Long)

/**
 * When the A4 stall notice shows: the selected session's [SessionFacts.stall] while its run is
 * still Starting or Running, the kernel line is up (Connected or Draining; otherwise the kernel
 * banner speaks), and the user has not chosen "Keep waiting" for this episode. A new episode
 * (the kernel answered, then went silent again) has a new `since` and shows again.
 */
object StallBannerModel {
    /** The stall to show; [stall] and [phase] are the session's [SessionFacts.stall] and [SessionFacts.runPhase]. */
    fun shown(sessionId: String?, stall: StallFact?, phase: RunPhase, line: KernelLine, dismissed: StallEpisode?): StallFact? {
        if (sessionId == null || stall == null) return null
        if (line != KernelLine.Connected && line != KernelLine.Draining) return null
        if (phase != RunPhase.Running && phase != RunPhase.Starting) return null
        return stall.takeIf { dismissed != StallEpisode(sessionId, it.since) }
    }

    /** "Keep waiting": the episode to hide until the next one. */
    fun dismiss(sessionId: String, stall: StallFact): StallEpisode = StallEpisode(sessionId, stall.since)
}

/**
 * The A4 stall notice, docked above the composer like the approval and question banners
 * (`warning.container`): the decided text, then "Keep waiting" (hides this episode) and
 * "Restart kernel" (the Kernel status sheet's restart with its confirm). [episode] identifies the
 * stall: the conversation's polite region announces the text once per episode (DS §8.1.4).
 *
 * [singleRow] is for typing in a height-compact window (D18, trial row 13): the keyboard leaves
 * room for the composer and one 56dp docked banner, which the stacked notice outgrew, so the
 * stack pushed it under the status bar. With [singleRow] the icon, the text and both actions share
 * one row of at least 56dp whenever the text fits on one line beside the actions (an S25U in
 * landscape has the width); otherwise, as in narrow windows or at large font scales, the notice
 * keeps its stacked form and the docked stack's bound (`DockRules.maxHeight`) keeps it inside
 * the pane.
 */
@Composable
fun StallBanner(
    onRestartKernel: () -> Unit,
    onKeepWaiting: () -> Unit,
    modifier: Modifier = Modifier,
    episode: Long? = null,
    liveRegion: LiveRegionState? = null,
    singleRow: Boolean = false,
) {
    val colors = ClineTheme.colors.warning
    val text = stringResource(R.string.stall_notice)
    LaunchedEffect(episode, liveRegion) { liveRegion?.announce(text) }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Layout(
            contents = listOf(
                { Icon(ClineIcons.Hourglass, contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.onContainer) },
                { Text(text, style = MaterialTheme.typography.bodyMedium) },
                {
                    Row {
                        val buttons = ButtonDefaults.textButtonColors(contentColor = colors.onContainer)
                        TextButton(onClick = onKeepWaiting, colors = buttons) { Text(stringResource(R.string.stall_keep_waiting)) }
                        TextButton(onClick = onRestartKernel, colors = buttons) { Text(stringResource(R.string.kernel_restart)) }
                    }
                },
            ),
        ) { (icon, message, actions), constraints ->
            measureStallNotice(icon.single(), message.single(), actions.single(), constraints, singleRow)
        }
    }
}

/** A docked banner's height in the D18 budget: the approval and question banners' minimum (C3.3). */
private val SINGLE_ROW_MIN_HEIGHT = 56.dp

/**
 * The two forms of [StallBanner]. Stacked: 16dp start, 12dp top; the icon and the text in the
 * first row (the text gets the width less 12dp at the end), the actions end-aligned under them,
 * 4dp end and bottom. Single row: the icon, the text and the actions centred in at least 56dp,
 * taken only when the text fits on one line.
 */
private fun MeasureScope.measureStallNotice(
    icon: Measurable,
    message: Measurable,
    actions: Measurable,
    constraints: Constraints,
    singleRow: Boolean,
): MeasureResult {
    val start = Spacing.l.roundToPx()
    val end = Spacing.xs.roundToPx()
    val gap = Spacing.m.roundToPx()
    val iconPlaceable = icon.measure(Constraints())
    val actionsWidth = actions.maxIntrinsicWidth(Constraints.Infinity)
    val oneLine = message.maxIntrinsicWidth(Constraints.Infinity)
    val beforeActions = Spacing.xs.roundToPx()
    val rowWidth = start + iconPlaceable.width + gap + oneLine + beforeActions + actionsWidth + end
    val width = if (constraints.hasBoundedWidth) constraints.maxWidth else rowWidth.coerceAtLeast(constraints.minWidth)
    val actionsPlaceable = actions.measure(Constraints(maxWidth = width))
    if (singleRow && rowWidth <= width) {
        val messagePlaceable = message.measure(Constraints(maxWidth = oneLine))
        val padding = Spacing.xs.roundToPx()
        val content = maxOf(iconPlaceable.height, messagePlaceable.height, actionsPlaceable.height)
        val height = maxOf(SINGLE_ROW_MIN_HEIGHT.roundToPx(), content + 2 * padding).coerceIn(constraints.minHeight, constraints.maxHeight)
        return layout(width, height) {
            iconPlaceable.placeRelative(start, (height - iconPlaceable.height) / 2)
            messagePlaceable.placeRelative(start + iconPlaceable.width + gap, (height - messagePlaceable.height) / 2)
            actionsPlaceable.placeRelative(width - end - actionsPlaceable.width, (height - actionsPlaceable.height) / 2)
        }
    }
    val top = Spacing.m.roundToPx()
    val bottom = Spacing.xs.roundToPx()
    val textWidth = (width - start - iconPlaceable.width - gap - gap - end).coerceAtLeast(0)
    val messagePlaceable = message.measure(Constraints(maxWidth = textWidth))
    val firstRow = maxOf(iconPlaceable.height, messagePlaceable.height)
    val height = (top + firstRow + actionsPlaceable.height + bottom).coerceIn(constraints.minHeight, constraints.maxHeight)
    return layout(width, height) {
        iconPlaceable.placeRelative(start, top + (firstRow - iconPlaceable.height) / 2)
        messagePlaceable.placeRelative(start + iconPlaceable.width + gap, top + (firstRow - messagePlaceable.height) / 2)
        actionsPlaceable.placeRelative(width - end - actionsPlaceable.width, top + firstRow)
    }
}

@LightDarkPreviews
@Composable
private fun StallBannerPreview() {
    ComponentPreview { StallBanner(onRestartKernel = {}, onKeepWaiting = {}) }
}

@Preview(name = "Font scale 2, light", fontScale = 2f)
@Preview(name = "Font scale 2, dark", fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun StallBannerLargeFontPreview() {
    ComponentPreview { StallBanner(onRestartKernel = {}, onKeepWaiting = {}) }
}

/** Trial row 13: typing in landscape on the S25U, the docked column is about 760dp wide. */
@Preview(name = "Typing in landscape, light", widthDp = 792)
@Preview(name = "Typing in landscape, dark", widthDp = 792, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun StallBannerSingleRowPreview() {
    ComponentPreview { StallBanner(onRestartKernel = {}, onKeepWaiting = {}, singleRow = true) }
}

/** A single row that does not fit (narrow window or large text) keeps the stacked form. */
@Preview(name = "Typing, too narrow for one row", widthDp = 360)
@Composable
private fun StallBannerSingleRowNarrowPreview() {
    ComponentPreview { StallBanner(onRestartKernel = {}, onKeepWaiting = {}, singleRow = true) }
}
