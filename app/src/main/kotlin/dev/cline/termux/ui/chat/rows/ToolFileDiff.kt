package dev.cline.termux.ui.chat.rows

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.data.diff.SessionDiffHunk
import dev.cline.termux.data.tools.ToolFileDiff
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.diff.ChangedFileHeader
import dev.cline.termux.ui.diff.DiffHunkView
import dev.cline.termux.ui.diff.rememberTransientFlag
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/*
 * One file diff inside a tool row (desktop `ToolFileDiff`, `agent-chat/tool-diff.tsx`): one
 * [DiffHunkView] per hunk ("re-diffing concatenated hunks pairs deletions with unrelated
 * additions"), line numbers hidden for fragments, or a mono block for a text-only unified
 * diff. The desktop renders tool diffs without a file header (`disableFileHeader: true`), so
 * tool rows pass [showHeader] = false; other hosts can add the [ChangedFileHeader].
 */

/** The hunk a [ToolFileDiff] renders: created files have no old side; complete contents start at line 1. */
internal fun ToolFileDiff.toSessionHunk(): SessionDiffHunk? {
    val hunk = hunk ?: return null
    return SessionDiffHunk(oldStart = 1, newStart = 1, old = hunk.oldText.orEmpty(), new = hunk.newText)
}

@Composable
fun ToolFileDiffView(
    diff: ToolFileDiff,
    modifier: Modifier = Modifier,
    index: Int = 0,
    count: Int = 1,
    showHeader: Boolean = false,
) {
    Column(modifier.fillMaxWidth().padding(top = Spacing.xs)) {
        if (showHeader) {
            val copier = rememberPlainCopier()
            val copied = rememberTransientFlag()
            ChangedFileHeader(
                path = diff.item.path,
                additions = diff.item.additions ?: 0,
                deletions = diff.item.deletions ?: 0,
                onCopyPath = {
                    copier.copy(diff.item.path, diff.item.path)
                    copied.trigger()
                },
                expanded = null,
                copied = copied.on,
            )
        }
        val sessionHunk = remember(diff) { diff.toSessionHunk() }
        if (sessionHunk != null) {
            DiffHunkView(
                hunk = sessionHunk,
                path = diff.item.path,
                index = index,
                count = count,
                showLineNumbers = diff.item.fragment != true,
            )
        } else {
            diff.item.diff?.let { text -> MonoBlock(text, wrap = false) }
        }
    }
}

/**
 * The desktop `ToolActivityCode` block: `ClineMono.body` on `surfaceContainer`, `shapes.small`,
 * at most [maxHeight] tall then scrolling, always LTR and selectable. [wrap] = false scrolls
 * long lines horizontally (diff text) instead of wrapping them (output text).
 */
@Composable
internal fun MonoBlock(
    text: String,
    modifier: Modifier = Modifier,
    wrap: Boolean = true,
    maxHeight: Dp = MonoBlockMaxHeight,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(top = Spacing.xs),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        LtrBlock {
            Box(
                Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(Spacing.s),
            ) {
                SelectionContainer {
                    LtrText(text = text, style = ClineMono.body, color = MaterialTheme.colorScheme.onSurface, softWrap = wrap)
                }
            }
        }
    }
}

/** Desktop `max-h-64` (256px) for tool output blocks. */
internal val MonoBlockMaxHeight = 256.dp
