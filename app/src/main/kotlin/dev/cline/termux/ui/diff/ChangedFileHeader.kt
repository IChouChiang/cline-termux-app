package dev.cline.termux.ui.diff

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.AnchoredContextMenu
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.DisclosureChevronDefaults
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.OverflowMenuButton
import dev.cline.termux.ui.components.PressPoint
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberLongPressHaptic
import dev.cline.termux.ui.components.toCustomActions
import dev.cline.termux.ui.components.trackPress
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.LocalReducedMotion
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.delay

/** Timings of the file header (CT A.4, A.5). */
object ChangedFileHeaderDefaults {
    /** The copy icon shows a check this long after a copy (desktop `diff-view.tsx:124-127`). */
    const val CopiedMillis = 1_600L

    /** A file whose counts changed flashes `secondaryContainer` → container this long. */
    const val HighlightMillis = 600

    /** Below this width for the path the counts move to a second line (font scale 2.0). */
    val MinPathWidth = 96.dp
}

/**
 * Desktop `AgentChangedFile` header (DS #ChangedFileHeader, CT A.4), `heightIn(min = 48dp)`,
 * in this order: chevron (`expand_more`, −90° collapsed in LTR, +90° in RTL, animated) ·
 * path in `ClineMono.small` with a middle ellipsis that never cuts the file name (the
 * directory in `onSurfaceVariant`, the file name in `onSurface`) · copy button (check in
 * `primary` while [copied]) · flexible spacer · `+N` (`success.color`) · `-N` (`error`) ·
 * overflow menu with [menuItems] as the last element. Counts always show, also `+0 -0`.
 *
 * With [expanded] = null the header is static (Tool detail edit layout, two-pane detail):
 * no chevron and no toggle. Otherwise a tap anywhere but the buttons calls [onToggle], and
 * a long-press opens the menu at the finger with "Expand"/"Collapse" first. TalkBack reads
 * one merged node ("path, 3 additions, 1 deletion, Expanded") with the menu as custom
 * actions; the copy and overflow buttons stay separately focusable. When [highlightKey]
 * changes to a new non-null value the header flashes once (skipped under reduced motion).
 * At very large font scales the counts wrap under the path.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChangedFileHeader(
    path: String,
    additions: Int,
    deletions: Int,
    onCopyPath: () -> Unit,
    modifier: Modifier = Modifier,
    expanded: Boolean? = true,
    onToggle: () -> Unit = {},
    copied: Boolean = false,
    menuItems: List<ContextMenuItem> = emptyList(),
    highlightKey: Any? = null,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.l),
) {
    val reducedMotion = LocalReducedMotion.current
    val haptic = rememberLongPressHaptic()

    val expandLabel = stringResource(R.string.changes_expand)
    val collapseLabel = stringResource(R.string.changes_collapse)
    val toggleItem = expanded?.let { isExpanded ->
        ContextMenuItem(id = "toggle", label = if (isExpanded) collapseLabel else expandLabel, onClick = onToggle)
    }
    val longPressItems = listOfNotNull(toggleItem) + menuItems

    val additionsText = copyPluralStringResource(R.plurals.changes_additions_a11y, additions, additions)
    val deletionsText = copyPluralStringResource(R.plurals.changes_deletions_a11y, deletions, deletions)
    val stateText = when (expanded) {
        true -> stringResource(R.string.changes_state_expanded)
        false -> stringResource(R.string.changes_state_collapsed)
        null -> null
    }
    val description = if (stateText != null) {
        stringResource(R.string.changes_header_a11y, path, additionsText, deletionsText, stateText)
    } else {
        stringResource(R.string.changes_header_static_a11y, path, additionsText, deletionsText)
    }

    val highlight = remember { Animatable(0f) }
    var lastHighlight by remember { mutableStateOf(highlightKey) }
    LaunchedEffect(highlightKey) {
        if (highlightKey != null && highlightKey != lastHighlight && !reducedMotion) {
            highlight.snapTo(1f)
            highlight.animateTo(0f, tween(ChangedFileHeaderDefaults.HighlightMillis, easing = ClineMotion.Standard))
        }
        lastHighlight = highlightKey
    }
    val background = lerp(containerColor, MaterialTheme.colorScheme.secondaryContainer, highlight.value)

    var menuOpen by remember { mutableStateOf(false) }
    val press = remember { PressPoint() }
    val density = LocalDensity.current

    val interactive = expanded != null || longPressItems.isNotEmpty()
    val rowModifier = modifier
        .fillMaxWidth()
        .heightIn(min = Spacing.touchTarget)
        .background(background)
        .then(
            if (interactive) {
                Modifier
                    .focusRing(shape = MaterialTheme.shapes.extraSmall)
                    .combinedClickable(
                        role = if (expanded != null) Role.Button else null,
                        onClick = { if (expanded != null) onToggle() },
                        onLongClick = if (longPressItems.isEmpty()) {
                            null
                        } else {
                            {
                                haptic()
                                menuOpen = true
                            }
                        },
                    )
            } else {
                Modifier
            },
        )
        .semantics(mergeDescendants = true) {
            contentDescription = description
            if (stateText != null) stateDescription = stateText
            heading()
            customActions = longPressItems.toCustomActions()
        }
        .padding(contentPadding)

    val countsStyle = ClineMono.small
    val additionsCount = stringResource(R.string.diff_additions_count, additions)
    val deletionsCount = stringResource(R.string.diff_deletions_count, deletions)
    val measurer = rememberTextMeasurer()

    Box(Modifier.trackPress(press)) {
        BoxWithConstraints(rowModifier) {
            val countsWidth = with(density) {
                (
                    measurer.measure(additionsCount, countsStyle).size.width +
                        measurer.measure(deletionsCount, countsStyle).size.width
                    ).toDp()
            } + Spacing.xs + Spacing.s
            val fixed = (if (expanded != null) CHEVRON_SIZE + Spacing.s else 0.dp) +
                Spacing.touchTarget +
                (if (menuItems.isNotEmpty()) Spacing.touchTarget else 0.dp)
            val stacked = maxWidth - fixed - countsWidth < ChangedFileHeaderDefaults.MinPathWidth

            val chevron: @Composable () -> Unit = {
                if (expanded != null) {
                    DisclosureChevron(
                        expanded = expanded,
                        size = CHEVRON_SIZE,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Spacing.s))
                }
            }
            val copyButton: @Composable () -> Unit = {
                TooltipIconButton(
                    icon = if (copied) ClineIcons.Check else ClineIcons.ContentCopy,
                    contentDescription = if (copied) {
                        stringResource(R.string.changes_copied_path_cd, path)
                    } else {
                        stringResource(R.string.changes_copy_path_cd, path)
                    },
                    tooltip = stringResource(R.string.changes_copy_path_tooltip),
                    onClick = onCopyPath,
                    iconSize = COPY_ICON_SIZE,
                    colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
                        contentColor = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
            val overflow: @Composable () -> Unit = {
                if (menuItems.isNotEmpty()) {
                    OverflowMenuButton(
                        contentDescription = stringResource(R.string.changes_file_actions_cd, path),
                        items = menuItems,
                    )
                }
            }
            val counts: @Composable () -> Unit = {
                // DS §8.2.6: diff counts are forced LTR, so the pair keeps the desktop "+N -N" order
                // in RTL locales while the header around it mirrors.
                LtrBlock {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        modifier = Modifier.clearAndSetSemantics { },
                    ) {
                        Text(additionsCount, style = countsStyle, color = ClineTheme.colors.success.color, maxLines = 1)
                        Text(deletionsCount, style = countsStyle, color = MaterialTheme.colorScheme.error, maxLines = 1)
                    }
                }
            }

            if (!stacked) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    chevron()
                    // Path and copy button take the room the counts leave; the rest is the spacer.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        MiddleEllipsisPath(
                            path,
                            countsStyle,
                            Modifier
                                .weight(1f, fill = false)
                                .clearAndSetSemantics { },
                        )
                        copyButton()
                    }
                    Spacer(Modifier.width(Spacing.s))
                    counts()
                    overflow()
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        chevron()
                        MiddleEllipsisPath(
                            path,
                            countsStyle,
                            Modifier
                                .weight(1f)
                                .clearAndSetSemantics { },
                        )
                        copyButton()
                        overflow()
                    }
                    Box(
                        Modifier.padding(
                            start = if (expanded != null) CHEVRON_SIZE + Spacing.s else 0.dp,
                            bottom = Spacing.s,
                        ),
                    ) { counts() }
                }
            }
        }
        if (longPressItems.isNotEmpty()) {
            AnchoredContextMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                items = longPressItems,
                offset = press.offset,
            )
        }
    }
}

/**
 * A path on one line with a middle ellipsis: whole segments before the file name are
 * replaced by "…" until it fits (`app/src/…/Main.kt`); the file name is never cut (only
 * when the bare name alone overflows does it end in an ellipsis). The directory part is
 * `onSurfaceVariant`, the file name `onSurface`. Read by TalkBack as written.
 */
@Composable
fun MiddleEllipsisPath(path: String, style: TextStyle, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val strong = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(modifier) {
        val maxPx = constraints.maxWidth
        val shown = remember(path, style, maxPx) {
            val candidates = pathCandidates(path)
            candidates.firstOrNull { measurer.measure(it, style, maxLines = 1, softWrap = false).size.width <= maxPx }
                ?: candidates.last()
        }
        val (dir, name) = splitBasename(shown)
        val text = remember(shown, variant, strong) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = variant)) { append(dir) }
                withStyle(SpanStyle(color = strong)) { append(name) }
            }
        }
        Text(
            text = text,
            style = style.copy(textDirection = TextDirection.Ltr),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { contentDescription = path },
        )
    }
}

/** Remembers a flag that turns itself off [durationMillis] after [trigger] (the copy check). */
@Composable
fun rememberTransientFlag(durationMillis: Long = ChangedFileHeaderDefaults.CopiedMillis): TransientFlag {
    val flag = remember { TransientFlag() }
    LaunchedEffect(flag.generation) {
        if (flag.generation > 0) {
            delay(durationMillis)
            flag.on = false
        }
    }
    return flag
}

/** See [rememberTransientFlag]. */
class TransientFlag internal constructor() {
    var on by mutableStateOf(false)
        internal set
    internal var generation by mutableStateOf(0)

    fun trigger() {
        on = true
        generation++
    }
}

private val CHEVRON_SIZE = DisclosureChevronDefaults.ToolRowSize
private val COPY_ICON_SIZE = 16.dp

// region previews

private val previewMenu = listOf(
    ContextMenuItem("copy", "Copy file path", {}),
    ContextMenuItem("share", "Share diff", {}),
)

@LightDarkPreviews
@Composable
private fun ChangedFileHeaderExpandedPreview() {
    ComponentPreview(padding = false) {
        ChangedFileHeader(
            path = "app/src/main/kotlin/dev/cline/termux/ui/diff/ChangedFileHeader.kt",
            additions = 30,
            deletions = 5,
            onCopyPath = {},
            menuItems = previewMenu,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ChangedFileHeaderCollapsedCopiedPreview() {
    ComponentPreview(padding = false) {
        ChangedFileHeader(
            path = "README.md",
            additions = 0,
            deletions = 2,
            onCopyPath = {},
            expanded = false,
            copied = true,
            menuItems = previewMenu,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ChangedFileHeaderStaticCjkPreview() {
    ComponentPreview(padding = false) {
        ChangedFileHeader(
            path = "/data/data/com.termux/files/home/项目/文档/说明.md",
            additions = 12,
            deletions = 0,
            onCopyPath = {},
            expanded = null,
        )
    }
}

/** RTL: chevron, path and actions mirror; the "+N -N" counts keep the desktop order (DS §8.2.6). */
@LightDarkPreviews
@Composable
private fun ChangedFileHeaderRtlPreview() {
    ComponentPreview(padding = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ChangedFileHeader(
                path = "app/src/main/kotlin/dev/cline/termux/ui/diff/ChangedFileHeader.kt",
                additions = 1,
                deletions = 1,
                onCopyPath = {},
                menuItems = previewMenu,
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun ChangedFileHeaderNarrowPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.width(240.dp)) {
            ChangedFileHeader(
                path = "app/src/main/kotlin/dev/cline/termux/MainActivity.kt",
                additions = 1234,
                deletions = 567,
                onCopyPath = {},
                expanded = true,
                menuItems = previewMenu,
            )
        }
    }
}

// endregion
