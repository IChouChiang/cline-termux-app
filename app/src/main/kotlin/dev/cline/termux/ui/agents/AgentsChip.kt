package dev.cline.termux.ui.agents

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.data.agents.SessionAgentActivity
import dev.cline.termux.data.agents.SessionAgentEntry
import dev.cline.termux.data.agents.buildSessionAgentActivity
import dev.cline.termux.data.agents.mergeAgentActivity
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.StatusGlyph
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.tint
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing

/**
 * The chat header's agents tally (home-chat.md "Agents chip", customize-and-rest.md §6.7
 * "Tally"): the spawn/team tool rows of the session ([derived]) merged with the persisted
 * roster when the sheet has loaded it ([mergeAgentActivity] keeps the larger evidence).
 */
@Immutable
data class AgentsChipModel(val activity: SessionAgentActivity) {
    /** Hidden when no agent was ever started (desktop). */
    val visible: Boolean get() = activity.total > 0

    /** cancelled + unresolved share the `schedule` glyph. */
    val other: Int get() = activity.cancelled + activity.unresolved

    companion object {
        val Hidden = AgentsChipModel(SessionAgentActivity.EMPTY)

        /** The tool-derived tally of [state] (`buildSessionAgentActivity(messages, sessionActive = busy)`). */
        fun derived(state: SessionState): SessionAgentActivity =
            buildSessionAgentActivity(state.messages, sessionActive = state.chatStatus.isBusy)

        /** The chip for [state], merged with the roster [entries] when known. */
        fun of(state: SessionState, entries: List<SessionAgentEntry> = emptyList()): AgentsChipModel =
            AgentsChipModel(mergeAgentActivity(entries, derived(state), sessionActive = state.chatStatus.isBusy))
    }
}

/**
 * The header's agents chip (DS ConversationHeader, home-chat.md): `AssistChip` 32dp on
 * `surfaceContainerHighest`, `smart_toy` 16dp, then in `ClineMono.small` the total, a 12dp
 * spinner + running count, `check` + completed (success), `error` + failed (error) and
 * `schedule` + the rest. CD = `describeAgentActivity`; tap opens [AgentsRosterSheet].
 * Renders nothing while [AgentsChipModel.visible] is false.
 */
@Composable
fun AgentsChip(model: AgentsChipModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (!model.visible) return
    val activity = model.activity
    val description = describeAgentActivity(activity)
    AssistChip(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
        colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
        border = null,
        leadingIcon = { Icon(ClineIcons.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp)) },
        label = {
            Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Text(activity.total.toString(), style = ClineMono.small, color = MaterialTheme.colorScheme.onSurface)
                if (activity.running > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(Spacing.xxs))
                        Text(activity.running.toString(), style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (activity.completed > 0) Tally(ClineIcons.Check, activity.completed, StatusGlyph.Completed.tint())
                if (activity.failed > 0) Tally(ClineIcons.Error, activity.failed, StatusGlyph.Failed.tint())
                if (model.other > 0) Tally(ClineIcons.Schedule, model.other, StatusGlyph.Cancelled.tint())
            }
        },
    )
}

@Composable
private fun Tally(icon: ImageVector, count: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(Spacing.xxs))
        Text(count.toString(), style = ClineMono.small, color = color)
    }
}

@LightDarkPreviews
@Composable
private fun AgentsChipPreview() {
    ComponentPreview {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            AgentsChip(AgentsChipModel(SessionAgentActivity(total = 3, running = 1, completed = 2)), onClick = {})
            AgentsChip(AgentsChipModel(SessionAgentActivity(total = 4, completed = 1, failed = 1, cancelled = 1, unresolved = 1)), onClick = {})
        }
    }
}
