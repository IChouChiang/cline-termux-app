package dev.cline.termux.ui.chat.rows

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import dev.cline.termux.R
import dev.cline.termux.ui.components.ChevronTurn
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.theme.Spacing

/*
 * The folded run (HC §3.4.5, DS §4.1 #4; desktop `work-block.tsx` + `WorkActivity*`): one
 * "Worked for 4m 12s and made 14 tool calls" row, collapsed by default, whose body re-shows
 * the run's rows at transcript level with no rail. The open state is keyed by the work
 * item's stable id so it survives canonical rows replacing live ones (§1.3.3). The row has the
 * tool rows' compact metrics ([DisclosureRowTokens], A24); its chevron is the icon size.
 */

@Composable
fun workLabelText(label: WorkLabel): String = when (label) {
    is WorkLabel.Both -> stringResource(
        R.string.work_label_both,
        label.duration,
        copyPluralStringResource(R.plurals.work_tool_calls, label.toolCalls, label.toolCalls),
    )
    is WorkLabel.Duration -> stringResource(R.string.work_label_duration, label.duration)
    is WorkLabel.Calls -> stringResource(
        R.string.work_label_calls,
        copyPluralStringResource(R.plurals.work_tool_calls, label.toolCalls, label.toolCalls),
    )
    WorkLabel.Worked -> stringResource(R.string.work_label_none)
}

@Composable
fun WorkBlock(
    id: String,
    durationMilliseconds: Long?,
    toolCallCount: Int,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by rememberSaveable(id) { mutableStateOf(initiallyOpen) }
    val toggled = LocalDisclosureToggled.current
    val label = workLabelText(workLabel(durationMilliseconds, toolCallCount))
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        DisclosureHeader(
            expanded = open,
            onToggle = {
                toggled?.invoke()
                open = !open
            },
            contentDescription = label,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = label,
                style = DisclosureRowTokens.labelStyle,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            CompositionLocalProvider(LocalContentColor provides muted) {
                DisclosureChevron(expanded = open, size = DisclosureRowTokens.IconSize, turn = ChevronTurn.EndToUp)
            }
        }
        DisclosureBody(visible = open, rail = false, content = content)
    }
}

/** A run's still-visible working rows with the tight in-run rhythm (desktop `run` item, 0dp gaps). */
@Composable
fun RunGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth(), content = content)
}
