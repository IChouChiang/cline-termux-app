package dev.cline.termux.ui.schedules

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.schedules.RoutineTemplateIcon
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/*
 * Small pieces shared by C5, C6 and C7: the card's "Label: value" lines, the enabled dot, the
 * template icons and the KeepAliveNotice (customize-and-rest.md Appendix A). Typed failures
 * map through the shared `RepoFailure.text()` (ui/components/FailureCopy.kt).
 */

/** The run status as the desktop shows it (`capitalize`), "Unknown result" when empty. */
@Composable
fun runStatusText(status: String): String {
    if (status.isBlank()) return stringResource(R.string.schedule_run_unknown)
    return runStatusLabel(status)?.let { stringResource(it) } ?: capitalizeStatus(status)
}

/** "Label: value" in `bodySmall`, the label in `onSurfaceVariant` (desktop `text-muted-foreground/70`). */
@Composable
fun ScheduleLine(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    maxLines: Int = Int.MAX_VALUE,
) {
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = labelColor)) { append(label) }
            append(' ')
            withStyle(SpanStyle(color = valueColor)) { append(value) }
        },
        style = style,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The 10dp enabled dot: `primary` when enabled, `outlineVariant` when paused (no semantics; §4.10). */
@Composable
fun EnabledDot(enabled: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** The routine template icons (Appendix A: `bug_report`, `shield`, `newspaper`, `menu_book`). */
@Composable
fun RoutineTemplateIcon.vector(): ImageVector = when (this) {
    RoutineTemplateIcon.Bug -> ClineIcons.BugReport
    RoutineTemplateIcon.ShieldAlert -> ClineIcons.Shield
    RoutineTemplateIcon.Newspaper -> ClineIcons.Newspaper
    RoutineTemplateIcon.BookOpen -> ClineIcons.MenuBook
}

/**
 * KeepAliveNotice (Appendix A): `warning.container` surface, `warning` icon, the notice text,
 * "Keep it running" and a close button (CD "Dismiss").
 */
@Composable
fun KeepAliveNotice(onKeepRunning: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ClineTheme.colors.warning
    Surface(color = colors.container, contentColor = colors.onContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = Spacing.l, top = Spacing.m, bottom = Spacing.s, end = Spacing.xs), verticalAlignment = Alignment.Top) {
            Icon(ClineIcons.Warning, contentDescription = null, modifier = Modifier.padding(top = Spacing.xxs).size(20.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.schedule_keep_alive_text), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onKeepRunning, modifier = Modifier.padding(top = Spacing.xs)) {
                    Text(stringResource(R.string.schedule_keep_alive_action), color = colors.onContainer)
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(ClineIcons.Close, contentDescription = stringResource(R.string.schedule_dismiss))
            }
        }
    }
}

/** The editor's error box (`errorContainer`, `warning` icon; assertive live region). */
@Composable
fun FormErrorBox(text: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(Spacing.m), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Icon(ClineIcons.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun KeepAliveNoticePreview() {
    ComponentPreview { KeepAliveNotice(onKeepRunning = {}, onDismiss = {}) }
}

@LightDarkPreviews
@androidx.compose.runtime.Composable
private fun FormErrorBoxPreview() {
    ComponentPreview { FormErrorBox(stringResource(R.string.schedule_error_prompt_required)) }
}
