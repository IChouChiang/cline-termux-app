package dev.cline.termux.ui.schedules

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.schedules.RoutineTemplate
import dev.cline.termux.data.schedules.ScheduleType
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.DashedNotice
import dev.cline.termux.ui.components.EmptyState
import dev.cline.termux.ui.components.FabLaneTooltip
import dev.cline.termux.ui.components.rememberFabLane
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.components.KernelBannerHost
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.KernelStatePanel
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.MenuNavIcon
import dev.cline.termux.ui.components.OverflowMenuButton
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.Skeleton
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.isScrollingUp
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.OrderedBackHandler
import dev.cline.termux.ui.navigation.rememberPaneDirective
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing
import dev.cline.termux.ui.components.ClineSwitch

/** Everything C5 and C7 do, as callbacks (the graph binds them to the ViewModel and navigator). */
@Immutable
class SchedulesCallbacks(
    val onRefresh: () -> Unit = {},
    val onNew: () -> Unit = {},
    val onTemplate: (String) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onEdit: (String) -> Unit = {},
    val onRunNow: (String) -> Unit = {},
    val onSetEnabled: (String, Boolean) -> Unit = { _, _ -> },
    val onDelete: (String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onDismissDelete: () -> Unit = {},
    val onKeepRunning: () -> Unit = {},
    val onDismissKeepAlive: () -> Unit = {},
    val onClearActionError: () -> Unit = {},
    val onKernelAction: (KernelAction) -> Unit = {},
    val onBlockAction: (KernelBlockAction) -> Unit = {},
    val onCloseDetails: () -> Unit = {},
    val onToggleRuns: () -> Unit = {},
    val onRetryDetails: () -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
)

/**
 * C5 (customize-and-rest.md §4.3): on compact and medium a single page (720dp column on
 * medium); on expanded a list-detail scaffold, list pane 400dp of cards and C7 in the detail
 * pane ([expanded]).
 */
@Composable
fun SchedulesScreen(
    state: SchedulesUiState,
    callbacks: SchedulesCallbacks,
    navigationIcon: (@Composable () -> Unit)?,
    expanded: Boolean,
    medium: Boolean,
    gutter: androidx.compose.ui.unit.Dp,
    kernelActionInFlight: Boolean = false,
) {
    if (expanded) {
        SchedulesListDetail(state, callbacks, navigationIcon, gutter, kernelActionInFlight)
    } else {
        SchedulesPage(state, callbacks, navigationIcon, gutter, twoColumnTemplates = medium, kernelActionInFlight = kernelActionInFlight, maxWidth = if (medium) Spacing.pageMaxWidth else null)
    }
    DeleteScheduleDialog(state.pendingDelete, callbacks)
}

@Composable
private fun SchedulesPage(
    state: SchedulesUiState,
    callbacks: SchedulesCallbacks,
    navigationIcon: (@Composable () -> Unit)?,
    gutter: androidx.compose.ui.unit.Dp,
    twoColumnTemplates: Boolean,
    kernelActionInFlight: Boolean,
    maxWidth: androidx.compose.ui.unit.Dp?,
    wideActions: Boolean = false,
) {
    val listState = rememberLazyListState()
    // DS §5.4: the ExtendedFAB collapses to icon-only while the list scrolls down; on a
    // height-compact window it stays icon-only beside an end lane (FabLane).
    val fabLane = rememberFabLane(listState.isScrollingUp())
    PageScaffold(
        title = stringResource(R.string.schedule_title),
        navigationIcon = navigationIcon,
        actions = {
            TooltipIconButton(
                icon = ClineIcons.Refresh,
                contentDescription = stringResource(R.string.schedule_refresh),
                onClick = callbacks.onRefresh,
                enabled = !state.refreshing,
            )
        },
        loading = state.refreshing,
        floatingActionButton = {
            if (state.kernel !is KernelGate.Blocked) {
                val fabLabel = stringResource(R.string.schedule_new)
                FabLaneTooltip(fabLane, fabLabel) {
                    ExtendedFloatingActionButton(
                        onClick = callbacks.onNew,
                        // M3 clears the text slot's semantics, so the icon names the FAB whether
                        // expanded or collapsed on scroll (DS §8).
                        icon = { Icon(ClineIcons.Add, contentDescription = fabLabel) },
                        text = { Text(fabLabel) },
                        expanded = fabLane.expanded,
                    )
                }
            }
        },
    ) { padding ->
        // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
        val direction = LocalLayoutDirection.current
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = callbacks.onRefresh,
            modifier = Modifier.fillMaxSize().padding(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction),
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxHeight().then(if (maxWidth != null) Modifier.widthIn(max = maxWidth) else Modifier).fillMaxWidth(),
                    // §0.6: no card's trailing buttons sit under the ExtendedFAB (FabLane).
                    contentPadding = PaddingValues(
                        start = gutter,
                        end = if (state.kernel !is KernelGate.Blocked) fabLane.endPadding(gutter) else gutter,
                        bottom = padding.calculateBottomPadding() + fabLane.bottomClearance,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.m),
                ) {
                    schedulesListContent(state, callbacks, twoColumnTemplates, wideActions, kernelActionInFlight)
                }
            }
        }
    }
}

/** The list's items, shared by the page and the expanded list pane. */
private fun LazyListScope.schedulesListContent(
    state: SchedulesUiState,
    callbacks: SchedulesCallbacks,
    twoColumnTemplates: Boolean,
    wideActions: Boolean,
    kernelActionInFlight: Boolean,
) {
    item(key = "description") {
        Text(
            stringResource(R.string.schedule_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val gate = state.kernel
    if (gate is KernelGate.Blocked) {
        item(key = "blocked") { KernelBlockedCard(KernelBlock.of(gate), onAction = callbacks.onBlockAction) }
        return
    }
    if (!state.loaded && gate is KernelGate.Down) {
        item(key = "panel") { KernelStatePanel(gate, hasComposer = false, onAction = callbacks.onKernelAction, primaryInFlight = kernelActionInFlight) }
        return
    }
    val starting = gate is KernelGate.Busy && (gate.kind == KernelGate.BusyKind.Starting || gate.kind == KernelGate.BusyKind.Connecting)
    if (!state.loaded && starting) {
        item(key = "skeleton") { ScheduleSkeleton() }
        return
    }
    item(key = "banner") { KernelBannerHost(gate, hasComposer = false, onAction = callbacks.onKernelAction, actionInFlight = kernelActionInFlight) }
    state.reducedScopeWorkspace?.let { ws ->
        item(key = "scope") { DashedNotice(stringResource(R.string.schedule_reduced_scope, ws)) }
    }
    if (state.keepAliveNotice) {
        item(key = "keepalive") { KeepAliveNotice(onKeepRunning = callbacks.onKeepRunning, onDismiss = callbacks.onDismissKeepAlive) }
    }
    state.error?.let { failure ->
        item(key = "error") {
            InlineErrorCard(failure.text(), actionLabel = stringResource(R.string.schedule_retry), onAction = callbacks.onRefresh)
        }
    }
    state.actionError?.let { failure ->
        item(key = "actionError") {
            InlineErrorCard(failure.text(), actionLabel = stringResource(R.string.schedule_dismiss), onAction = callbacks.onClearActionError)
        }
    }
    if (!state.loaded) {
        if (state.error == null) item(key = "loading") { PageEmptyText(stringResource(R.string.schedule_loading)) }
        return
    }
    if (state.schedules.isEmpty()) {
        item(key = "empty") { PageEmptyText(stringResource(R.string.schedule_empty)) }
    }
    items(state.schedules, key = { "s-" + it.id }) { card ->
        ScheduleCardView(
            card = card,
            busy = card.id in state.busy,
            triggering = card.id in state.triggering,
            mutationsEnabled = state.mutationsEnabled,
            reconnecting = state.kernel is KernelGate.Busy,
            selected = card.id == state.selectedId,
            wideActions = wideActions,
            callbacks = callbacks,
        )
    }
    if (state.templates.isNotEmpty()) {
        item(key = "suggested") {
            Text(
                stringResource(R.string.schedule_suggested).uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xxl).semantics { heading() },
            )
        }
        val rows = if (twoColumnTemplates) state.templates.chunked(2) else state.templates.map { listOf(it) }
        items(rows, key = { row -> "t-" + row.joinToString { it.id } }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                row.forEach { template ->
                    TemplateCard(template, onClick = { callbacks.onTemplate(template.id) }, modifier = Modifier.weight(1f))
                }
                if (row.size == 1 && twoColumnTemplates) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Desktop `PageEmptyState`: centred muted text in a padded box. */
@Composable
private fun PageEmptyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxxl, horizontal = Spacing.l),
    )
}

/** Loading skeleton (DS "Loading skeleton", 3 cards) under a progress line (§0.4). */
@Composable
private fun ScheduleSkeleton() {
    val label = stringResource(R.string.schedule_loading_a11y)
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        repeat(3) { Skeleton(Modifier.fillMaxWidth().heightIn(min = 140.dp), shape = MaterialTheme.shapes.medium) }
    }
}

/**
 * One schedule card (§4.3): enabled dot, name, switch; mode and trigger badges; prompt (2
 * lines); Next run, Last result, Last error, Active; then Run now + Edit + overflow
 * ([wideActions] = the expanded list pane's inline icon buttons and the switch at the end).
 */
@Composable
fun ScheduleCardView(
    card: ScheduleCard,
    busy: Boolean,
    triggering: Boolean,
    mutationsEnabled: Boolean,
    reconnecting: Boolean,
    selected: Boolean,
    wideActions: Boolean,
    callbacks: SchedulesCallbacks,
    modifier: Modifier = Modifier,
) {
    val name = card.name
    val enabled = card.record.enabled
    val canMutate = mutationsEnabled && !busy
    val stateWord = stringResource(if (enabled) R.string.schedule_state_enabled else R.string.schedule_state_disabled)
    val label = stringResource(R.string.schedule_card_a11y, name, stateWord, card.trigger, card.nextRun, card.lastResult)
    val unavailable = stringResource(R.string.schedule_unavailable_reconnecting)
    val kernelState: Modifier = if (!mutationsEnabled && reconnecting) Modifier.semantics { stateDescription = unavailable } else Modifier
    OutlinedCard(
        onClick = { callbacks.onOpen(card.id) },
        modifier = modifier.fillMaxWidth().focusRing(MaterialTheme.shapes.medium).semantics { contentDescription = label },
        colors = if (selected) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors(),
    ) {
        Column(Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                EnabledDot(enabled)
                Spacer(Modifier.width(Spacing.m))
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).clearAndSetSemantics { },
                )
                if (!wideActions) EnableSwitch(name, enabled, canMutate, kernelState, callbacks.onSetEnabled.let { f -> { v: Boolean -> f(card.id, v) } })
            }
            Column(Modifier.padding(end = Spacing.s).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs), modifier = Modifier.padding(top = Spacing.xs)) {
                    card.record.mode?.takeIf { it.isNotBlank() }?.let { TagBadge(it) }
                    TagBadge(card.trigger)
                }
                if (card.record.prompt.isNotBlank()) {
                    Text(
                        card.record.prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ScheduleLine(stringResource(R.string.schedule_label_next_run), card.nextRun)
                ScheduleLine(stringResource(R.string.schedule_label_last_result), card.lastResult)
                card.lastExecution?.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
                    ScheduleLine(stringResource(R.string.schedule_label_last_error), error, valueColor = MaterialTheme.colorScheme.error, maxLines = 3)
                }
                card.active?.let { active ->
                    ScheduleLine(stringResource(R.string.schedule_label_active), stringResource(R.string.schedule_active_value, active.executionId, card.activeSince.orEmpty()))
                }
            }
            HorizontalDivider(Modifier.padding(top = Spacing.m, end = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
            if (wideActions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TooltipIconButton(ClineIcons.Edit, stringResource(R.string.schedule_cd_edit, name), { callbacks.onEdit(card.id) }, tooltip = stringResource(R.string.schedule_tooltip_edit), enabled = !busy)
                    RunNowIcon(name, triggering, canMutate, kernelState) { callbacks.onRunNow(card.id) }
                    TooltipIconButton(
                        if (enabled) ClineIcons.Pause else ClineIcons.PlayArrow,
                        stringResource(if (enabled) R.string.schedule_cd_pause else R.string.schedule_cd_resume, name),
                        { callbacks.onSetEnabled(card.id, !enabled) },
                        modifier = kernelState,
                        tooltip = stringResource(if (enabled) R.string.schedule_tooltip_pause else R.string.schedule_tooltip_resume),
                        enabled = canMutate,
                    )
                    TooltipIconButton(ClineIcons.Delete, stringResource(R.string.schedule_cd_delete, name), { callbacks.onDelete(card.id) }, tooltip = stringResource(R.string.schedule_tooltip_delete), enabled = canMutate)
                    Spacer(Modifier.weight(1f))
                    EnableSwitch(name, enabled, canMutate, kernelState) { callbacks.onSetEnabled(card.id, it) }
                }
            } else {
                val runCd = stringResource(R.string.schedule_cd_run_now, name)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = Spacing.xs)) {
                    FilledTonalButton(
                        onClick = { callbacks.onRunNow(card.id) },
                        enabled = canMutate,
                        modifier = kernelState.semantics { contentDescription = runCd },
                    ) {
                        if (triggering) InlineSpinner() else Icon(ClineIcons.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.s))
                        Text(stringResource(R.string.schedule_run_now))
                    }
                    Spacer(Modifier.weight(1f))
                    TooltipIconButton(ClineIcons.Edit, stringResource(R.string.schedule_cd_edit, name), { callbacks.onEdit(card.id) }, tooltip = stringResource(R.string.schedule_tooltip_edit), enabled = !busy)
                    OverflowMenuButton(
                        contentDescription = stringResource(R.string.schedule_cd_actions, name),
                        enabled = !busy,
                        items = listOf(
                            ContextMenuItem(
                                id = "toggle",
                                label = stringResource(if (enabled) R.string.schedule_tooltip_pause else R.string.schedule_tooltip_resume),
                                onClick = { callbacks.onSetEnabled(card.id, !enabled) },
                                icon = if (enabled) ClineIcons.Pause else ClineIcons.PlayArrow,
                                enabled = canMutate,
                            ),
                            ContextMenuItem(
                                id = "delete",
                                label = stringResource(R.string.schedule_tooltip_delete),
                                onClick = { callbacks.onDelete(card.id) },
                                icon = ClineIcons.Delete,
                                destructive = true,
                                enabled = canMutate,
                            ),
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun EnableSwitch(name: String, enabled: Boolean, canMutate: Boolean, kernelState: Modifier, onChange: (Boolean) -> Unit) {
    val cd = stringResource(R.string.schedule_cd_enable, name)
    PlainTooltipBox(stringResource(if (enabled) R.string.schedule_tooltip_enabled else R.string.schedule_tooltip_disabled)) {
        ClineSwitch(
            checked = enabled,
            onCheckedChange = onChange,
            enabled = canMutate,
            modifier = kernelState.semantics { contentDescription = cd },
        )
    }
}

@Composable
private fun RunNowIcon(name: String, triggering: Boolean, enabled: Boolean, kernelState: Modifier, onClick: () -> Unit) {
    if (triggering) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { InlineSpinner() }
    } else {
        TooltipIconButton(
            ClineIcons.PlayArrow,
            stringResource(R.string.schedule_cd_run_now, name),
            onClick,
            modifier = kernelState,
            tooltip = stringResource(R.string.schedule_tooltip_run_now),
            enabled = enabled,
        )
    }
}

/** A "Suggested" template card: icon tile, title, Daily/Weekly badge, description (desktop `sm:grid-cols-2`). */
@Composable
fun TemplateCard(template: RoutineTemplate, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedCard(onClick = onClick, modifier = modifier.fillMaxWidth().focusRing(MaterialTheme.shapes.medium)) {
        Row(Modifier.padding(Spacing.l), horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.size(40.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(template.icon.vector(), contentDescription = null, modifier = Modifier.size(20.dp)) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text(stringResource(template.title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    TagBadge(stringResource(if (template.scheduleType == ScheduleType.Daily) R.string.schedule_template_daily else R.string.schedule_template_weekly))
                }
                Text(stringResource(template.description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Delete Routine" (desktop AlertDialog; "Deleting..." busy state, DS #16). */
@Composable
fun DeleteScheduleDialog(pending: PendingDelete?, callbacks: SchedulesCallbacks) {
    pending ?: return
    dev.cline.termux.ui.components.ConfirmDialog(
        title = stringResource(R.string.schedule_delete_title),
        text = stringResource(R.string.schedule_delete_text, pending.name ?: stringResource(R.string.schedule_delete_fallback_name)),
        confirmLabel = stringResource(R.string.schedule_delete_confirm),
        onConfirm = callbacks.onConfirmDelete,
        onDismiss = callbacks.onDismissDelete,
        destructive = true,
        confirming = pending.deleting,
        confirmingLabel = stringResource(R.string.schedule_deleting),
        error = pending.error?.text(),
    )
}

// ------------------------------------------------------------------ expanded

/**
 * Expanded (§0.6, §4.3): `NavigableListDetailPaneScaffold`, list pane 400dp of cards with inline
 * icon actions and the FAB at its bottom end, C7 in the detail pane (max 720dp) or the
 * "Select a schedule" placeholder. Back with a selection clears it (level 8).
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun SchedulesListDetail(
    state: SchedulesUiState,
    callbacks: SchedulesCallbacks,
    navigationIcon: (@Composable () -> Unit)?,
    gutter: androidx.compose.ui.unit.Dp,
    kernelActionInFlight: Boolean,
) {
    val navigator = rememberListDetailPaneScaffoldNavigator<Nothing>(scaffoldDirective = rememberPaneDirective(LIST_PANE_WIDTH))
    OrderedBackHandler(BackLevel.ListDetailPane, enabled = state.selectedId != null) { callbacks.onCloseDetails() }
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                Row {
                    Box(Modifier.weight(1f)) {
                        SchedulesPage(state, callbacks, navigationIcon, Spacing.l, twoColumnTemplates = false, kernelActionInFlight = kernelActionInFlight, maxWidth = null, wideActions = true)
                    }
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        detailPane = {
            AnimatedPane {
                val details = state.details
                if (state.selectedId == null || details == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyState(
                            title = stringResource(R.string.schedule_select_title),
                            body = stringResource(R.string.schedule_select_body),
                            icon = ClineIcons.Schedule,
                        )
                    }
                } else {
                    ScheduleDetailsScreen(state, details, callbacks, inPane = true, gutter = gutter)
                }
            }
        },
        defaultBackBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange,
    )
}

private val LIST_PANE_WIDTH = 400.dp

// ------------------------------------------------------------------ previews

@LightDarkPreviews
@Composable
private fun SchedulesPopulatedPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(loading = false, loaded = true, schedules = SchedulePreviewData.cards, templates = dev.cline.termux.data.schedules.ROUTINE_TEMPLATES.take(2), keepAliveNotice = true, triggering = setOf("sch_9f2c41d0"), busy = setOf("sch_9f2c41d0")),
            SchedulesCallbacks(),
            navigationIcon = { MenuNavIcon {} },
            expanded = false,
            medium = false,
            gutter = Spacing.gutterCompact,
        )
    }
}

@LightDarkPreviews
@Composable
private fun SchedulesEmptyPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(loading = false, loaded = true, templates = dev.cline.termux.data.schedules.ROUTINE_TEMPLATES),
            SchedulesCallbacks(),
            navigationIcon = null,
            expanded = false,
            medium = true,
            gutter = Spacing.gutterWide,
        )
    }
}

@LightDarkPreviews
@Composable
private fun SchedulesLoadingPreview() {
    ClinePreviewTheme {
        SchedulesScreen(SchedulesUiState(), SchedulesCallbacks(), navigationIcon = { MenuNavIcon {} }, expanded = false, medium = false, gutter = Spacing.gutterCompact)
    }
}

@LightDarkPreviews
@Composable
private fun SchedulesErrorPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(loading = false, loaded = true, schedules = SchedulePreviewData.cards, error = dev.cline.termux.data.customize.RepoFailure.Hub("schedule_command_failed", "schedule store is locked")),
            SchedulesCallbacks(),
            navigationIcon = { MenuNavIcon {} },
            expanded = false,
            medium = false,
            gutter = Spacing.gutterCompact,
        )
    }
}

@LightDarkPreviews
@Composable
private fun SchedulesKernelDownPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(loading = false, kernel = KernelGate.Down(KernelGate.DownVariant.Asleep, null)),
            SchedulesCallbacks(),
            navigationIcon = { MenuNavIcon {} },
            expanded = false,
            medium = false,
            gutter = Spacing.gutterCompact,
        )
    }
}

@LightDarkPreviews
@Composable
private fun SchedulesCachedReconnectingPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(loading = false, loaded = true, schedules = SchedulePreviewData.cards, reducedScopeWorkspace = "home", kernel = KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, slow = false)),
            SchedulesCallbacks(),
            navigationIcon = { MenuNavIcon {} },
            expanded = false,
            medium = false,
            gutter = Spacing.gutterCompact,
        )
    }
}

@LightDarkPreviews
@Composable
private fun SchedulesDeleteDialogPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(loading = false, loaded = true, schedules = SchedulePreviewData.cards, pendingDelete = PendingDelete("sch_9f2c41d0", "Daily change digest", deleting = true)),
            SchedulesCallbacks(),
            navigationIcon = { MenuNavIcon {} },
            expanded = false,
            medium = false,
            gutter = Spacing.gutterCompact,
        )
    }
}

@Preview(name = "Expanded, no selection", widthDp = 1244, heightDp = 800)
@Preview(name = "Expanded, no selection (dark)", widthDp = 1244, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SchedulesExpandedNoSelectionPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(loading = false, loaded = true, schedules = SchedulePreviewData.cards, templates = dev.cline.termux.data.schedules.ROUTINE_TEMPLATES.take(1)),
            SchedulesCallbacks(),
            navigationIcon = null,
            expanded = true,
            medium = false,
            gutter = Spacing.gutterWide,
        )
    }
}

@Preview(name = "Expanded, selected", widthDp = 1244, heightDp = 800)
@Preview(name = "Expanded, selected (dark)", widthDp = 1244, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun SchedulesExpandedSelectedPreview() {
    ClinePreviewTheme {
        SchedulesScreen(
            SchedulesUiState(
                loading = false,
                loaded = true,
                schedules = SchedulePreviewData.cards,
                selectedId = "sch_9f2c41d0",
                details = DetailsUiState("sch_9f2c41d0", loading = false, details = SchedulePreviewData.details()),
            ),
            SchedulesCallbacks(),
            navigationIcon = null,
            expanded = true,
            medium = false,
            gutter = Spacing.gutterWide,
        )
    }
}
