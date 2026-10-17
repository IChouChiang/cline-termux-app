package dev.cline.termux.ui.schedules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.session.StatusGlyph
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.components.KernelBannerHost
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.OverflowMenuButton
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.PanePageAppBar
import dev.cline.termux.ui.components.panePageScrollBehavior
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.icon
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.components.tint
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing

/**
 * C7 (customize-and-rest.md §4.3): the desktop details dialog as a page (compact/medium,
 * pushed) or the expanded detail pane ([inPane]: small app bar with "Close details"). The
 * summary grid first, then every card line the grid lacks, the raw JSON and the runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleDetailsScreen(
    state: SchedulesUiState,
    details: DetailsUiState,
    callbacks: SchedulesCallbacks,
    inPane: Boolean,
    gutter: Dp,
    onBack: () -> Unit = {},
) {
    val card = details.details?.card ?: state.card(details.scheduleId)
    val title = card?.name?.ifBlank { null } ?: stringResource(R.string.schedule_details_fallback_title)
    val busy = details.scheduleId in state.busy
    val canMutate = state.mutationsEnabled && !busy
    val actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
        if (card != null) {
            if (details.scheduleId in state.triggering) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { InlineSpinner() }
            } else {
                TooltipIconButton(ClineIcons.PlayArrow, stringResource(R.string.schedule_cd_run_now, card.name), { callbacks.onRunNow(card.id) }, tooltip = stringResource(R.string.schedule_tooltip_run_now), enabled = canMutate)
            }
            TooltipIconButton(ClineIcons.Edit, stringResource(R.string.schedule_cd_edit, card.name), { callbacks.onEdit(card.id) }, tooltip = stringResource(R.string.schedule_tooltip_edit), enabled = !busy)
            val enabled = card.record.enabled
            OverflowMenuButton(
                contentDescription = stringResource(R.string.schedule_details_more),
                enabled = !busy,
                items = listOf(
                    ContextMenuItem("toggle", stringResource(if (enabled) R.string.schedule_tooltip_pause else R.string.schedule_tooltip_resume), { callbacks.onSetEnabled(card.id, !enabled) }, if (enabled) ClineIcons.Pause else ClineIcons.PlayArrow, enabled = canMutate),
                    ContextMenuItem("delete", stringResource(R.string.schedule_tooltip_delete), { callbacks.onDelete(card.id) }, ClineIcons.Delete, destructive = true, enabled = canMutate),
                ),
            )
        }
    }
    // DS §4.2: a detail load shows one indeterminate line under the app bar, never a skeleton.
    val loading = details.loading || details.refreshing
    val loadingDescription = stringResource(R.string.schedule_loading_a11y)
    if (inPane) {
        val scrollBehavior = panePageScrollBehavior()
        Column(Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection)) {
            PanePageAppBar(
                title = title,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TooltipIconButton(ClineIcons.Close, stringResource(R.string.schedule_details_close), callbacks.onCloseDetails)
                },
                actions = actions,
                loading = loading,
                loadingDescription = loadingDescription,
            )
            DetailsBody(state, details, callbacks, gutter, twoColumnGrid = true, modifier = Modifier.weight(1f))
        }
    } else {
        PageScaffold(title = title, navigationIcon = { BackNavIcon(onBack) }, actions = actions, loading = loading, loadingDescription = loadingDescription) { padding ->
            DetailsBody(state, details, callbacks, gutter, twoColumnGrid = false, modifier = Modifier.padding(top = padding.calculateTopPadding()), bottom = padding.calculateBottomPadding())
        }
    }
    if (!inPane) DeleteScheduleDialog(state.pendingDelete, callbacks)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsBody(
    state: SchedulesUiState,
    details: DetailsUiState,
    callbacks: SchedulesCallbacks,
    gutter: Dp,
    twoColumnGrid: Boolean,
    modifier: Modifier = Modifier,
    bottom: Dp = 0.dp,
) {
    PullToRefreshBox(isRefreshing = details.refreshing, onRefresh = callbacks.onRefresh, modifier = modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxWidth(),
                contentPadding = PaddingValues(start = gutter, end = gutter, top = Spacing.s, bottom = bottom + Spacing.xxl),
                verticalArrangement = Arrangement.spacedBy(Spacing.s),
            ) {
                item(key = "banner") { KernelBannerHost(state.kernel, hasComposer = false, onAction = callbacks.onKernelAction) }
                val loaded = details.details
                if (loaded == null) {
                    // While loading, the app bar's progress line is the only indicator (DS §4.2).
                    if (details.error != null) {
                        item(key = "error") { InlineErrorCard(details.error.text(), actionLabel = stringResource(R.string.schedule_retry), onAction = callbacks.onRetryDetails) }
                    }
                    return@LazyColumn
                }
                details.error?.let { failure ->
                    item(key = "error") { InlineErrorCard(failure.text(), actionLabel = stringResource(R.string.schedule_retry), onAction = callbacks.onRetryDetails) }
                }
                val card = loaded.card
                item(key = "grid") { SummaryGrid(card, twoColumnGrid) }
                item(key = "lines") { CardLines(card, callbacks) }
                if (card.record.prompt.isNotBlank()) {
                    item(key = "prompt") {
                        Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(stringResource(R.string.schedule_details_prompt), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            SelectionContainer { Text(card.record.prompt, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
                if (card.record.tags.isNotEmpty()) {
                    item(key = "tags") {
                        Column(Modifier.padding(top = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            Text(stringResource(R.string.schedule_details_tags), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                                card.record.tags.forEach { TagBadge(it) }
                            }
                        }
                    }
                }
                item(key = "json") { JsonBlock(loaded.json) }
                item(key = "runsHeader") {
                    Row(Modifier.fillMaxWidth().padding(top = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.schedule_details_runs), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
                        Text(
                            copyPluralStringResource(R.plurals.schedule_details_results, loaded.runs.size, loaded.runs.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (loaded.runs.isEmpty()) {
                    item(key = "noRuns") {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Text(
                                stringResource(R.string.schedule_details_no_runs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl, horizontal = Spacing.m),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                } else {
                    item(key = "runs") {
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            val shown = ScheduleProjection.runsPreview(loaded.runs, details.showAllRuns)
                            shown.forEachIndexed { index, run ->
                                RunRowView(run, onOpen = callbacks.onOpenSession)
                                if (index < shown.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                    if (!details.showAllRuns && loaded.runs.size > ScheduleProjection.RUNS_PREVIEW) {
                        item(key = "showAll") {
                            TextButton(onClick = callbacks.onToggleRuns) {
                                Text(copyPluralStringResource(R.plurals.schedule_details_show_all, loaded.runs.size, loaded.runs.size))
                                Icon(ClineIcons.ExpandMore, contentDescription = null, modifier = Modifier.padding(start = Spacing.xs).size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The desktop summary grid: Schedule, Mode, Model, Enabled, Last run, Next run (two columns on expanded). */
@Composable
private fun SummaryGrid(card: ScheduleCard, twoColumns: Boolean) {
    val rows = listOf(
        stringResource(R.string.schedule_details_schedule) to card.trigger,
        stringResource(R.string.schedule_details_mode) to (card.record.mode ?: "-"),
        stringResource(R.string.schedule_label_model) to card.model,
        stringResource(R.string.schedule_details_enabled) to stringResource(if (card.record.enabled) R.string.schedule_details_yes else R.string.schedule_details_no),
        stringResource(R.string.schedule_label_last_run) to card.lastRun,
        stringResource(R.string.schedule_label_next_run) to card.nextRun,
    )
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        val chunks = if (twoColumns) rows.chunked(2) else rows.map { listOf(it) }
        chunks.forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
                pair.forEach { (label, value) ->
                    ScheduleLine(label, value, Modifier.weight(1f).heightIn(min = 24.dp), valueColor = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                }
                if (pair.size == 1 && twoColumns) Box(Modifier.weight(1f))
            }
        }
    }
}

/** The card lines the grid lacks: ID (copyable), Last result, Last session, Last error, Active. */
@Composable
private fun CardLines(card: ScheduleCard, callbacks: SchedulesCallbacks) {
    val copier = rememberPlainCopier()
    val copiedLabel = stringResource(R.string.schedule_details_copied_id)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val copyIdLabel = stringResource(R.string.schedule_details_copy_id)
            Row(
                Modifier
                    .weight(1f)
                    .pointerInput(card.id) { detectTapGestures(onLongPress = { copier.copy(copiedLabel, card.id) }) }
                    .semantics { onLongClick(label = copyIdLabel) { copier.copy(copiedLabel, card.id); true } },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.schedule_label_id), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The gap sits outside the LTR island so it stays between label and id under RTL too.
                Spacer(Modifier.width(Spacing.xs))
                LtrBlock {
                    Text(card.id, style = ClineMono.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            TooltipIconButton(ClineIcons.ContentCopy, stringResource(R.string.schedule_details_copy_id), { copier.copy(copiedLabel, card.id) })
        }
        ScheduleLine(stringResource(R.string.schedule_label_last_result), card.lastResult, valueColor = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        card.lastExecution?.sessionId?.let { sessionId ->
            val cd = stringResource(R.string.schedule_details_open_last_session)
            ListItem(
                headlineContent = { Text(stringResource(R.string.schedule_details_last_session)) },
                supportingContent = { LtrBlock { Text(sessionId, style = ClineMono.small) } },
                trailingContent = { Icon(ClineIcons.ChevronRight, contentDescription = null) },
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier
                    .focusRing(RectangleShape, inset = true)
                    .clickable(role = Role.Button, onClickLabel = cd) { callbacks.onOpenSession(sessionId) }
                    .semantics { contentDescription = cd },
            )
        }
        card.lastExecution?.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
            ScheduleLine(stringResource(R.string.schedule_label_last_error), error, valueColor = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        card.active?.let { active ->
            ScheduleLine(
                stringResource(R.string.schedule_label_active),
                stringResource(R.string.schedule_active_value, active.executionId, card.activeSince.orEmpty()),
                valueColor = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** Raw JSON: `ClineMono.small` on `surfaceContainer`, max 256dp, scrolls, selectable, LTR (§4.10). */
@Composable
private fun JsonBlock(json: String) {
    val label = stringResource(R.string.schedule_details_json)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth().padding(top = Spacing.s)) {
        LtrBlock {
            SelectionContainer(Modifier.semantics { contentDescription = label }) {
                Text(
                    json,
                    style = ClineMono.small,
                    modifier = Modifier
                        .heightIn(max = 256.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.m),
                )
            }
        }
    }
}

/**
 * One run row (56dp `ListItem`): status icon, status, error line, timestamp, open icon. A run
 * without a session is plain text (no ripple, no open icon, label ends ", no session").
 */
@Composable
private fun RunRowView(run: RunRow, onOpen: (String) -> Unit) {
    val status = runStatusText(run.status)
    val label = if (run.sessionId == null) stringResource(R.string.schedule_details_run_no_session, status) else status
    val openCd = stringResource(R.string.schedule_details_open_session)
    val a11y = run.errorMessage?.let { stringResource(R.string.schedule_details_run_error_a11y, label, it, run.timestamp) }
        ?: stringResource(R.string.schedule_details_run_a11y, label, run.timestamp)
    val rowModifier = if (run.sessionId != null) {
        Modifier
            .focusRing(RectangleShape, inset = true)
            .clickable(role = Role.Button, onClickLabel = openCd) { onOpen(run.sessionId) }
    } else {
        Modifier
    }
    ListItem(
        modifier = rowModifier.heightIn(min = 56.dp).clearAndSetSemantics { contentDescription = a11y },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        leadingContent = { RunStatusIcon(run.group) },
        headlineContent = { Text(status, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = run.errorMessage?.let { error ->
            { Text(error, color = MaterialTheme.colorScheme.error, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(run.timestamp, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (run.sessionId != null) Icon(ClineIcons.OpenInNew, contentDescription = null, modifier = Modifier.padding(start = Spacing.s).size(18.dp))
            }
        },
    )
}

/** ✓ success / ✕ failed through the DS §1.6 glyphs; a spinner while running; the clock otherwise. */
@Composable
private fun RunStatusIcon(group: RunStatus) {
    when (group) {
        RunStatus.Running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = StatusGlyph.Running.tint())
        RunStatus.Succeeded -> StatusGlyph.Completed.icon()?.let { Icon(it, contentDescription = null, tint = StatusGlyph.Completed.tint(), modifier = Modifier.size(20.dp)) }
        RunStatus.Failed -> StatusGlyph.Failed.icon()?.let { Icon(it, contentDescription = null, tint = StatusGlyph.Failed.tint(), modifier = Modifier.size(20.dp)) }
        RunStatus.Waiting -> Icon(ClineIcons.States.Scheduled, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
    }
}

// ------------------------------------------------------------------ previews

@LightDarkPreviews
@Composable
private fun ScheduleDetailsPopulatedPreview() {
    ClinePreviewTheme {
        val details = DetailsUiState("sch_9f2c41d0", loading = false, details = SchedulePreviewData.details())
        ScheduleDetailsScreen(SchedulesUiState(loaded = true, loading = false, details = details), details, SchedulesCallbacks(), inPane = false, gutter = Spacing.gutterCompact)
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleDetailsAllRunsTriggeringPreview() {
    ClinePreviewTheme {
        val details = DetailsUiState("sch_9f2c41d0", loading = false, details = SchedulePreviewData.details(), showAllRuns = true)
        ScheduleDetailsScreen(
            SchedulesUiState(loaded = true, loading = false, details = details, triggering = setOf("sch_9f2c41d0"), busy = setOf("sch_9f2c41d0")),
            details,
            SchedulesCallbacks(),
            inPane = false,
            gutter = Spacing.gutterCompact,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleDetailsNoRunsPreview() {
    ClinePreviewTheme {
        val details = DetailsUiState("sch_9f2c41d0", loading = false, details = ScheduleProjection.details(SchedulePreviewData.card(SchedulePreviewData.paused), emptyList(), java.time.ZoneId.of("UTC"), java.util.Locale.US))
        ScheduleDetailsScreen(SchedulesUiState(loaded = true, loading = false, details = details, kernel = KernelGate.Down(KernelGate.DownVariant.Asleep, null)), details, SchedulesCallbacks(), inPane = false, gutter = Spacing.gutterCompact)
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleDetailsLoadingPreview() {
    ClinePreviewTheme {
        val details = DetailsUiState("sch_9f2c41d0")
        ScheduleDetailsScreen(SchedulesUiState(), details, SchedulesCallbacks(), inPane = false, gutter = Spacing.gutterCompact)
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleDetailsPaneLoadingPreview() {
    ClinePreviewTheme {
        val details = DetailsUiState("sch_9f2c41d0")
        ScheduleDetailsScreen(SchedulesUiState(), details, SchedulesCallbacks(), inPane = true, gutter = Spacing.gutterWide)
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleDetailsPaneRefreshingPreview() {
    ClinePreviewTheme {
        val details = DetailsUiState("sch_9f2c41d0", loading = false, refreshing = true, details = SchedulePreviewData.details())
        ScheduleDetailsScreen(SchedulesUiState(loaded = true, loading = false, details = details), details, SchedulesCallbacks(), inPane = true, gutter = Spacing.gutterWide)
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleDetailsErrorPreview() {
    ClinePreviewTheme {
        val details = DetailsUiState("sch_9f2c41d0", loading = false, error = dev.cline.termux.data.customize.RepoFailure.Hub("schedule_command_failed", "schedule does not exist in this workspace"))
        ScheduleDetailsScreen(SchedulesUiState(), details, SchedulesCallbacks(), inPane = true, gutter = Spacing.gutterWide)
    }
}
