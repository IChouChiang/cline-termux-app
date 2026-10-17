package dev.cline.termux.ui.suggestions

import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.SlashCommand
import dev.cline.termux.data.chat.SlashCommands
import dev.cline.termux.data.chat.SlashKind
import dev.cline.termux.data.chat.SlashRefusal
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews

/** Slash rows (C5.3): two-line, 56dp; "Loading commands..." / "No matching commands" when empty; "Loading..." tail. */
internal fun LazyListScope.slashRows(
    state: SuggestionMenuState.Slash,
    showHighlight: Boolean,
    onSelect: (Int) -> Unit,
) {
    if (state.items.isEmpty()) {
        item(key = "slash-empty") {
            SuggestionNoticeRow(stringResource(if (state.loading) R.string.slash_loading_commands else R.string.slash_no_match))
        }
        return
    }
    itemsIndexed(state.items, key = { _, command -> "slash-${command.kind}-${command.name}" }) { index, command ->
        val description = slashDescription(command)
        SuggestionRow(
            highlighted = showHighlight && index == state.highlighted,
            minHeight = 56.dp,
            rowDescription = stringResource(R.string.slash_row_cd, command.name, description),
            onClick = { onSelect(index) },
        ) {
            Column {
                Text(
                    text = stringResource(R.string.slash_command_token, command.name),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    if (state.loading) {
        item(key = "slash-loading") { SuggestionTailRow(stringResource(R.string.slash_loading_tail)) }
    }
}

/** The row's supporting line: the configured description, else the built-in or per-kind copy (desktop). */
@Composable
fun slashDescription(command: SlashCommand): String = command.description ?: stringResource(
    when (command.kind) {
        SlashKind.BuiltinTeam -> R.string.slash_team_desc
        SlashKind.Skill -> R.string.slash_skill_desc_fallback
        SlashKind.Workflow -> R.string.slash_workflow_desc_fallback
    },
)

/**
 * The C5.5 sentence for a C5.7 refusal, used by the prompt queue's C2.5 error text
 * ([dev.cline.termux.ui.queue.queueFailureText]). The composer resolves its own copies through ComposerMessage.
 */
fun SlashRefusal.message(resources: Resources): String = when (this) {
    SlashRefusal.TeamUsage -> resources.getString(R.string.team_usage)
    SlashRefusal.TeamDisabled -> resources.getString(R.string.team_disabled)
    SlashRefusal.TeamPlanMode -> resources.getString(R.string.team_unavailable_mode)
    is SlashRefusal.SkillReadFailed -> resources.getString(R.string.skill_read_failed, name)
    is SlashRefusal.WorkflowReadFailed -> resources.getString(R.string.workflow_read_failed, name)
}

private val previewCommands = SlashCommands.BUILTINS + listOf(
    SlashCommand("code-review", "Review the diff for correctness", SlashKind.Skill, "/skills/code-review/SKILL.md"),
    SlashCommand("release-notes", null, SlashKind.Workflow, "/workflows/release-notes.md"),
)

@LightDarkPreviews
@Composable
private fun SlashPanelPopulatedPreview() {
    ComponentPreview(padding = false) {
        SuggestionPanel(SuggestionMenuState.Slash("", previewCommands, loading = false, highlighted = 0), {}, {}, {}, showHighlight = true)
    }
}

@LightDarkPreviews
@Composable
private fun SlashPanelRefreshingPreview() {
    ComponentPreview(padding = false) {
        SuggestionPanel(SuggestionMenuState.Slash("", SlashCommands.BUILTINS, loading = true, highlighted = 0), {}, {}, {})
    }
}

@LightDarkPreviews
@Composable
private fun SlashPanelEmptyPreview() {
    ComponentPreview(padding = false) {
        Column {
            SuggestionPanel(SuggestionMenuState.Slash("zz", emptyList(), loading = false, highlighted = 0), {}, {}, {})
            SuggestionPanel(SuggestionMenuState.Slash("zz", emptyList(), loading = true, highlighted = 0), {}, {}, {})
        }
    }
}
