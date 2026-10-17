package dev.cline.termux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.Spacing

/**
 * The docked stack above the composer (composer-approvals.md §0.4), top to bottom, each
 * slot composed only when non-null:
 *
 * 1. [approvalBanner] (C3) and 2. [questionBanner] (C4): separate surfaces, 8dp apart;
 *    both show when both exist, approval first.
 * 5. [setupNotice] (C8, Home only): 12dp above the composer.
 * 6. [composer] (C1), whose `ComposerSurface` draws items 3 and 4 itself through
 *    [ComposerDockedChildren].
 *
 * [scrollToLatest] (DS §4.2 FAB) is placed 16dp above the topmost item, end-aligned. It
 * overhangs the stack's top edge into the transcript and adds no height to the stack, so
 * the transcript above keeps its size. The stack and the composer ride the IME together:
 * the caller applies `imePadding()` to this composable, not to its children (DS §5.7).
 */
@Composable
fun ChatDockedStack(
    composer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    approvalBanner: (@Composable () -> Unit)? = null,
    questionBanner: (@Composable () -> Unit)? = null,
    setupNotice: (@Composable () -> Unit)? = null,
    scrollToLatest: (@Composable () -> Unit)? = null,
) {
    Layout(
        modifier = modifier,
        content = {
            Column(Modifier.layoutId(STACK).fillMaxWidth()) {
                val banners = listOfNotNull(approvalBanner, questionBanner)
                banners.forEach { banner ->
                    banner()
                    DockGap(BANNER_GAP)
                }
                if (setupNotice != null) {
                    setupNotice()
                    DockGap(SETUP_NOTICE_GAP)
                }
                composer()
            }
            if (scrollToLatest != null) {
                Box(Modifier.layoutId(FAB)) { scrollToLatest() }
            }
        },
    ) { measurables, constraints ->
        val stack = measurables.first { it.layoutId == STACK }.measure(constraints)
        val fab = measurables.firstOrNull { it.layoutId == FAB }?.measure(constraints.copy(minWidth = 0, minHeight = 0))
        val gap = FAB_GAP.roundToPx()
        layout(stack.width, stack.height) {
            stack.placeRelative(0, 0)
            fab?.placeRelative(stack.width - fab.width - Spacing.l.roundToPx(), -fab.height - gap)
        }
    }
}

/**
 * Items 3 and 4 of §0.4 (and the C1.7 held-for-Hub note) inside `ComposerSurface`, above
 * its field row: the suggestion panel (C5/C6), then the prompt queue strip (C2), then the
 * held note, each followed by a 1dp `outlineVariant` divider so the outer surface keeps one
 * outline with no seam (C1.3). [queueStrip] is passed null in height-compact windows with
 * the IME open (C1.4 step 6).
 */
@Composable
fun ComposerDockedChildren(
    modifier: Modifier = Modifier,
    suggestionPanel: (@Composable () -> Unit)? = null,
    queueStrip: (@Composable () -> Unit)? = null,
    heldNote: (@Composable () -> Unit)? = null,
) {
    val children = listOfNotNull(suggestionPanel, queueStrip, heldNote)
    if (children.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        children.forEach { child ->
            child()
            HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun DockGap(height: Dp) {
    Spacer(Modifier.fillMaxWidth().height(height))
}

private const val STACK = "stack"
private const val FAB = "fab"

/** Between docked banners (§0.4). */
private val BANNER_GAP: Dp = Spacing.s

/** Above the composer for the C8 setup notice (§0.4). */
private val SETUP_NOTICE_GAP: Dp = Spacing.m

/** ScrollToLatest sits this far above the topmost docked item. */
private val FAB_GAP: Dp = Spacing.l

@Composable
private fun PreviewBlock(label: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Text(label, Modifier.padding(Spacing.l), style = MaterialTheme.typography.bodyMedium)
    }
}

@LightDarkPreviews
@Composable
private fun ChatDockedStackFullPreview() {
    ComponentPreview {
        Column {
            Spacer(Modifier.height(64.dp))
            ChatDockedStack(
                approvalBanner = { PreviewBlock("Approval banner") },
                questionBanner = { PreviewBlock("Question banner") },
                scrollToLatest = { PreviewBlock("FAB", Modifier.width(56.dp)) },
                composer = {
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column {
                            ComposerDockedChildren(
                                suggestionPanel = { Text("Suggestion panel", Modifier.padding(Spacing.l)) },
                                queueStrip = { Text("2 prompts queued", Modifier.padding(Spacing.l)) },
                            )
                            Text("Composer field", Modifier.padding(Spacing.l))
                        }
                    }
                },
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun ChatDockedStackHomePreview() {
    ComponentPreview {
        ChatDockedStack(
            setupNotice = { PreviewBlock("Welcome setup notice") },
            composer = { PreviewBlock("Composer") },
        )
    }
}
