package dev.cline.termux.ui.diff

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.diff.DiffLine
import dev.cline.termux.data.diff.DiffLineKind
import dev.cline.termux.data.diff.HunkDiff
import dev.cline.termux.data.diff.LineDiff
import dev.cline.termux.data.diff.SessionDiffHunk
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One hunk of a file diff (DS #ToolFileDiff/DiffHunk, CT A.4): a `surfaceContainer` card
 * with, per line, an optional gutter (line numbers in `ClineMono.tiny`, shown for complete
 * new contents), a 16dp marker column (`+` in `success.color`, `-` in `error`) and the text
 * in `ClineMono.body`, no wrap, on `diffAddedLine` / `diffRemovedLine`. One horizontal
 * scroll per hunk; always LTR; the line text is selectable, the gutter and marker are not.
 *
 * Unchanged runs farther than four lines from a change collapse into an
 * "N unmodified lines" row that expands on tap. Long hunks show [shownRows] rows (400 by
 * default) and a "Show N more lines" button that calls [onShowMore]; pass both to keep
 * the count in a ViewModel (CT A.8 `shownLinesPerHunk`), or neither to keep it in saved
 * state here. Identical sides render nothing (desktop `ToolFileDiff`).
 *
 * [path], [index] and [count] label the hunk for TalkBack ("Change 2 of 3 in Main.kt").
 */
@Composable
fun DiffHunkView(
    hunk: SessionDiffHunk,
    modifier: Modifier = Modifier,
    path: String? = null,
    index: Int = 0,
    count: Int = 1,
    showLineNumbers: Boolean = hunk.isCompleteNewContents,
    shownRows: Int? = null,
    onShowMore: (() -> Unit)? = null,
) {
    val computed = rememberHunkDiff(hunk) ?: return
    val diff = computed.diff ?: return

    var localShown by rememberSaveable(hunk) { mutableIntStateOf(DiffRows.PAGE_ROWS) }
    val limit = shownRows ?: localShown
    val showMore: () -> Unit = onShowMore ?: { localShown += DiffRows.PAGE_ROWS }

    var expanded by rememberSaveable(hunk) { mutableStateOf(IntArray(0)) }
    val rows = remember(diff, expanded) { DiffRows.build(diff, expanded.toSet()) }
    val visible = if (rows.size > limit) rows.subList(0, limit) else rows
    val remaining = rows.size - visible.size

    val groupLabel = path?.let { stringResource(R.string.diff_hunk_group, index + 1, count, it) }
    val gutter = rememberGutter(diff, showLineNumbers)

    LtrBlock {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = modifier
                .fillMaxWidth()
                .semantics {
                    isTraversalGroup = true
                    if (groupLabel != null) contentDescription = groupLabel
                },
        ) {
            Column {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val viewport = maxWidth
                    SelectionContainer {
                        Column(
                            Modifier
                                .horizontalScroll(rememberScrollState())
                                .widthIn(min = viewport)
                                .width(IntrinsicSize.Max)
                                .padding(vertical = Spacing.xs),
                        ) {
                            for (row in visible) {
                                when (row) {
                                    is DiffRow.Line -> DiffLineRow(row.line, gutter)
                                    is DiffRow.Collapsed -> CollapsedRow(row.count, onExpand = { expanded = expanded + row.id })
                                }
                            }
                        }
                    }
                }
                if (remaining > 0) {
                    TextButton(
                        onClick = showMore,
                        modifier = Modifier.padding(horizontal = Spacing.xs),
                    ) {
                        // CT A.11: the label counts every hidden line ("Show 1600 more lines"); a tap adds 400.
                        Text(copyPluralStringResource(R.plurals.changes_show_more, remaining, remaining))
                    }
                }
            }
        }
    }
}

/** "No hunk details available." for files without hunks (e.g. apply_patch Delete File). */
@Composable
fun NoHunkDetails(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.changes_no_hunks),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Wrapper so "computed, identical" (diff = null) differs from "not computed yet". */
private class ComputedHunk(val diff: HunkDiff?)

/**
 * Hunks of a few KB diff in composition; everything larger on `Dispatchers.Default`
 * (docs/07 §8, cancelled when the hunk changes), keeping the previous result on screen
 * until the new one arrives.
 */
@Composable
private fun rememberHunkDiff(hunk: SessionDiffHunk): ComputedHunk? {
    if (hunk.old.length + hunk.new.length <= SYNC_DIFF_CHARS) {
        return remember(hunk) { ComputedHunk(LineDiff.forHunk(hunk)) }
    }
    val result by produceState<ComputedHunk?>(null, hunk) {
        value = withContext(Dispatchers.Default) { ComputedHunk(LineDiff.forHunk(hunk)) }
    }
    return result
}

/** Gutter layout: no gutter, one column (new numbers) or two (old and new). */
private class Gutter(val columns: Int, val columnWidth: androidx.compose.ui.unit.Dp)

@Composable
private fun rememberGutter(diff: HunkDiff, show: Boolean): Gutter? {
    if (!show) return null
    val style = ClineMono.tiny
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(diff, style, density) {
        val twoColumns = diff.lines.any { it.kind != DiffLineKind.Added }
        val largest = maxOf(diff.oldStart + diff.oldCount, diff.newStart + diff.newCount)
        val digits = largest.toString().length
        val charWidth = measurer.measure("0", style).size.width
        val width = with(density) { (charWidth * digits).toDp() } + GUTTER_EXTRA
        Gutter(if (twoColumns) 2 else 1, width)
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, gutter: Gutter?) {
    val colors = ClineTheme.colors
    val background = when (line.kind) {
        DiffLineKind.Added -> colors.diffAddedLine
        DiffLineKind.Removed -> colors.diffRemovedLine
        DiffLineKind.Context -> Color.Transparent
    }
    val markerColor = when (line.kind) {
        DiffLineKind.Added -> colors.success.color
        DiffLineKind.Removed -> MaterialTheme.colorScheme.error
        DiffLineKind.Context -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val text = remember(line.text) { DiffRows.displayText(line.text) }
    val description = when (line.kind) {
        DiffLineKind.Added -> if (gutter != null && line.newNumber != null) {
            stringResource(R.string.diff_line_added, line.newNumber, line.text)
        } else {
            stringResource(R.string.diff_line_added_nonum, line.text)
        }
        DiffLineKind.Removed -> stringResource(R.string.diff_line_removed, line.text)
        DiffLineKind.Context -> stringResource(R.string.diff_line_context, line.text)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        DisableSelection {
            if (gutter != null) {
                if (gutter.columns == 2) GutterNumber(line.oldNumber, gutter)
                GutterNumber(line.newNumber, gutter)
            }
            Text(
                text = when (line.kind) {
                    DiffLineKind.Added -> "+"
                    DiffLineKind.Removed -> "-"
                    DiffLineKind.Context -> " "
                },
                style = ClineMono.body,
                color = markerColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(MARKER_WIDTH),
            )
        }
        Text(
            text = text,
            style = ClineMono.body.copy(textDirection = TextDirection.Ltr),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
            maxLines = 1,
            modifier = Modifier.padding(end = Spacing.m),
        )
    }
}

@Composable
private fun GutterNumber(number: Int?, gutter: Gutter) {
    Text(
        text = number?.toString() ?: "",
        style = ClineMono.tiny,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        maxLines = 1,
        modifier = Modifier
            .width(gutter.columnWidth)
            .padding(end = Spacing.xs),
    )
}

/** The `@pierre/diffs` hunk separator: "N unmodified lines", tap to show them. */
@Composable
private fun CollapsedRow(count: Int, onExpand: () -> Unit) {
    val label = copyPluralStringResource(R.plurals.diff_unmodified_lines, count, count)
    val expandLabel = stringResource(R.string.changes_expand)
    DisableSelection {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Spacing.touchTarget)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .focusRing(RectangleShape, inset = true)
                .clickable(onClickLabel = expandLabel, role = Role.Button, onClick = onExpand)
                .padding(horizontal = Spacing.m),
        ) {
            Icon(
                ClineIcons.UnfoldMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = label,
                style = ClineMono.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val SYNC_DIFF_CHARS = 4_096
private val MARKER_WIDTH = 16.dp
private val GUTTER_EXTRA = 12.dp

// region previews

@LightDarkPreviews
@Composable
private fun DiffHunkFragmentPreview() {
    ComponentPreview {
        DiffHunkView(
            hunk = SessionDiffHunk(oldStart = 12, newStart = 12, old = "fun main() {\n    println(x)\n}", new = "fun main() {\n    println(x + y)\n}"),
            path = "app/src/main/kotlin/Main.kt",
        )
    }
}

@LightDarkPreviews
@Composable
private fun DiffHunkNewFilePreview() {
    ComponentPreview {
        DiffHunkView(
            hunk = SessionDiffHunk(oldStart = 1, newStart = 1, old = "", new = "print(\"hello\")\nprint(\"hi\")\n# 你好，世界\n"),
            path = "hello.py",
        )
    }
}

@LightDarkPreviews
@Composable
private fun DiffHunkCollapsedContextPreview() {
    val context = (1..14).joinToString("\n") { "    line($it)" }
    ComponentPreview {
        DiffHunkView(
            hunk = SessionDiffHunk(
                oldStart = 1,
                newStart = 1,
                old = "val a = 1\n$context\nval b = 2",
                new = "val a = 10\n$context\nval b = 20",
            ),
        )
    }
}

@LightDarkPreviews
@Composable
private fun DiffHunkLongLinePreview() {
    ComponentPreview {
        DiffHunkView(
            hunk = SessionDiffHunk(
                oldStart = 3,
                newStart = 3,
                old = "const url = \"https://example.com/a/very/long/path/that/keeps/going/and/going/well/past/the/edge/of/the/screen\";",
                new = "const url = \"https://example.com/a/very/long/path/that/keeps/going/and/going/well/past/the/edge/of/the/screen?v=2\";\n// 中文注释：保持 LTR 方向",
            ),
        )
    }
}

@LightDarkPreviews
@Composable
private fun DiffHunkShowMorePreview() {
    ComponentPreview {
        DiffHunkView(
            hunk = SessionDiffHunk(oldStart = 1, newStart = 1, old = "", new = (1..420).joinToString("\n") { "row $it" }),
            shownRows = 6,
            onShowMore = {},
        )
    }
}

@LightDarkPreviews
@Composable
private fun NoHunkDetailsPreview() {
    ComponentPreview { NoHunkDetails() }
}

// endregion
