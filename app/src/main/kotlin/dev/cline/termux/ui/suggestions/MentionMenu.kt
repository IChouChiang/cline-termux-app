package dev.cline.termux.ui.suggestions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.MentionProblem
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/**
 * Mention rows (C6.3): 48dp paths in `ClineMono.small`, LTR, middle-ellipsized so the
 * basename stays visible. Notices replace the list for a missing workspace, a stopped
 * kernel or a missing RUN_COMMAND permission; a failed search keeps earlier results above
 * "Could not search files.".
 */
internal fun LazyListScope.mentionRows(
    state: SuggestionMenuState.Mention,
    showHighlight: Boolean,
    onSelect: (Int) -> Unit,
    onChooseWorkspace: () -> Unit,
    onAllowCommands: () -> Unit,
) {
    when (state.problem) {
        MentionProblem.NoWorkspace -> {
            item(key = "mention-no-workspace") {
                SuggestionNoticeRow(stringResource(R.string.mention_no_workspace)) {
                    TextButton(onClick = onChooseWorkspace) { Text(stringResource(R.string.mention_choose)) }
                }
            }
            return
        }
        MentionProblem.KernelDown -> {
            item(key = "mention-kernel-down") { SuggestionNoticeRow(stringResource(R.string.mention_kernel_down)) }
            return
        }
        MentionProblem.PermissionMissing -> {
            item(key = "mention-permission") {
                SuggestionNoticeRow(stringResource(R.string.mention_permission)) {
                    TextButton(onClick = onAllowCommands) { Text(stringResource(R.string.mention_allow)) }
                }
            }
            return
        }
        MentionProblem.Error, null -> Unit
    }
    if (state.items.isEmpty() && state.problem == null) {
        item(key = "mention-empty") {
            SuggestionNoticeRow(stringResource(if (state.loading) R.string.mention_searching else R.string.mention_no_match))
        }
        return
    }
    itemsIndexed(state.items, key = { _, path -> "mention-$path" }) { index, path ->
        SuggestionRow(
            highlighted = showHighlight && index == state.highlighted,
            minHeight = 48.dp,
            rowDescription = stringResource(R.string.cd_mention_row, path),
            onClick = { onSelect(index) },
        ) {
            LtrText(
                text = path,
                style = ClineMono.small,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                softWrap = false,
                readAsWritten = false,
            )
        }
    }
    if (state.problem == MentionProblem.Error) {
        item(key = "mention-error") {
            Text(
                text = stringResource(R.string.mention_error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 32.dp)
                    .padding(horizontal = Spacing.l, vertical = Spacing.s)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    } else if (state.refreshing) {
        item(key = "mention-updating") { SuggestionTailRow(stringResource(R.string.mention_updating)) }
    }
}

private val previewPaths = listOf("src/parser.ts", "src/parser/tokens.ts", "test/parser.test.ts", "docs/parsing.md")

@LightDarkPreviews
@Composable
private fun MentionPanelPopulatedPreview() {
    ComponentPreview(padding = false) {
        Column {
            SuggestionPanel(SuggestionMenuState.Mention("pars", previewPaths, false, false, null, 0), {}, {}, {}, showHighlight = true)
            SuggestionPanel(SuggestionMenuState.Mention("pars", previewPaths, false, true, null, 0), {}, {}, {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun MentionPanelStatesPreview() {
    ComponentPreview(padding = false) {
        Column {
            SuggestionPanel(SuggestionMenuState.Mention("", emptyList(), true, false, null, 0), {}, {}, {})
            SuggestionPanel(SuggestionMenuState.Mention("zzzz", emptyList(), false, false, null, 0), {}, {}, {})
            SuggestionPanel(SuggestionMenuState.Mention("pa", previewPaths.take(2), false, false, MentionProblem.Error, 0), {}, {}, {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun MentionPanelProblemsPreview() {
    ComponentPreview(padding = false) {
        Column {
            SuggestionPanel(SuggestionMenuState.Mention("", emptyList(), false, false, MentionProblem.NoWorkspace, 0), {}, {}, {})
            SuggestionPanel(SuggestionMenuState.Mention("", emptyList(), false, false, MentionProblem.KernelDown, 0), {}, {}, {})
            SuggestionPanel(SuggestionMenuState.Mention("", emptyList(), false, false, MentionProblem.PermissionMissing, 0), {}, {}, {})
        }
    }
}
