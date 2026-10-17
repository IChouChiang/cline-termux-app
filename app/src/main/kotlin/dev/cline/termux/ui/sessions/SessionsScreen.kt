package dev.cline.termux.ui.sessions

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.R
import dev.cline.termux.AppGraph
import dev.cline.termux.appGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.sessions.ListLoad
import dev.cline.termux.data.sessions.formatCostUsd
import dev.cline.termux.data.sessions.tokensLabel
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.EmptyState
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.PressPoint
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.SessionStatusDot
import dev.cline.termux.ui.components.Skeleton
import dev.cline.termux.ui.components.SkeletonListRows
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.isScrollingUp
import dev.cline.termux.ui.components.label
import dev.cline.termux.ui.components.rememberLongPressHaptic
import dev.cline.termux.ui.components.tint
import dev.cline.termux.ui.components.toCustomActions
import dev.cline.termux.ui.components.trackPress
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.ChatSelection
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.WidthClass
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The `sessions` route (design/screens/sessions.md S2): binds [SessionsScreenViewModel] to the
 * shell (gate, navigator, snackbars) and renders [SessionsScreenContent].
 */
@Composable
fun SessionsScreen(navigator: ShellNavigator, graph: AppGraph = LocalContext.current.appGraph) {
    val vm: SessionsScreenViewModel = viewModel(factory = SessionsScreenViewModel.factory(graph))
    val state by vm.uiState.collectAsStateWithLifecycle()
    val root = navigator.chatRootEntry
    val selectedFlow = remember(root) { root?.savedStateHandle?.let(ChatSelection::selectedFlow) ?: MutableStateFlow(null) }
    val active by selectedFlow.collectAsStateWithLifecycle()
    SessionsEventsEffect(vm.events, navigator, active)
    val gate = LocalShellKernelUi.current?.gate ?: KernelGate.Ready
    // DS §8.1.7: the filter sheet hands focus back to the app-bar filter button on close.
    val filterFocus = remember { FocusRequester() }
    SessionsScreenContent(
        state = state,
        activeSessionId = active,
        kernelReady = gate is KernelGate.Ready || gate is KernelGate.DegradedNoCommands,
        vm = vm,
        onBack = { navigator.back() },
        onNewSession = navigator::newSession,
        filterFocus = filterFocus,
    )
    state.deleteCandidate?.let { candidate ->
        DeleteSessionDialog(
            summary = candidate,
            deleting = state.pending[candidate.id] == PendingKind.Delete,
            onConfirm = vm.actions::confirmDelete,
            onDismiss = vm.actions::dismissDelete,
        )
    }
    state.detailsFor?.let { id -> SessionDetailsSheetHost(id, vm.port, onDismiss = vm.actions::dismissDetails) }
    if (state.filterSheetOpen) {
        FacetFilterSheet(
            state,
            onToggle = vm::toggleFacet,
            onClear = vm::clearFacets,
            onDismiss = { vm.setFilterSheetOpen(false) },
            returnFocus = filterFocus,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreenContent(
    state: SessionsScreenUiState,
    activeSessionId: String?,
    kernelReady: Boolean,
    vm: SessionsScreenViewModel?,
    onBack: () -> Unit,
    onNewSession: () -> Unit,
    filterFocus: FocusRequester? = null,
) {
    val layout = LocalShellLayout.current
    val listState = rememberLazyListState()
    val showFab = layout.width == WidthClass.Compact
    val fabExpanded = listState.isScrollingUp()
    val now = rememberMinuteTick()

    LaunchedEffect(state.query, state.selectedFacets, state.sort) { listState.scrollToItem(0) }
    if (vm != null) {
        val rowsState by rememberUpdatedState(state.rows)
        LaunchedEffect(listState, vm) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                    (info.key as? String)?.takeIf { it.startsWith(ROW_KEY) }?.removePrefix(ROW_KEY)
                }
            }
                .distinctUntilChanged()
                .collect { ids ->
                    vm.setVisibleRows(ids)
                    val last = ids.lastOrNull()?.let { id -> rowsState.indexOfFirst { it.summary.id == id } } ?: -1
                    if (last >= 0) vm.onScrolled(last)
                }
        }
        DisposableEffect(vm) { onDispose { vm.setVisibleRows(emptyList()) } }
    }

    PageScaffold(
        title = stringResource(R.string.sessions_title),
        navigationIcon = { BackNavIcon(onBack) },
        actions = {
            SortMenuButton(state.sort, onSort = { vm?.setSort(it) })
            TooltipIconButton(
                icon = ClineIcons.FilterList,
                contentDescription = stringResource(if (state.selectedFacets.isNotEmpty()) R.string.sessions_cd_filter_active else R.string.sessions_cd_filter),
                tooltip = stringResource(R.string.sessions_cd_filter),
                onClick = { vm?.setFilterSheetOpen(true) },
                modifier = if (filterFocus != null) Modifier.focusRequester(filterFocus) else Modifier,
                colors = if (state.selectedFacets.isNotEmpty()) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
            )
        },
        loading = state.loadingAll,
        floatingActionButton = {
            if (showFab) {
                val fabLabel = stringResource(R.string.sessions_new_session)
                ExtendedFloatingActionButton(
                    text = { Text(fabLabel) },
                    // M3 clears the text slot's semantics, so the icon names the FAB whether
                    // expanded or collapsed on scroll (DS §8).
                    icon = { Icon(ClineIcons.Add, contentDescription = fabLabel) },
                    expanded = fabExpanded,
                    onClick = onNewSession,
                )
            }
        },
    ) { padding ->
        val gutter = layout.gutter
        val bottom = padding.calculateBottomPadding() + if (showFab) 56.dp + Spacing.l else 0.dp
        // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
        val direction = LocalLayoutDirection.current
        val sides = Modifier.padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction))
        val list: @Composable () -> Unit = {
            BoxWithConstraints(Modifier.fillMaxSize().then(sides)) {
                val table = layout.width == WidthClass.Expanded && titleColumnWidth(maxWidth - gutter * 2) >= 160.dp
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().then(if (state.editing != null) Modifier.imePadding() else Modifier),
                    contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = bottom),
                ) {
                    header(state, gutter, onQuery = { vm?.setQuery(it) }, onRetry = { vm?.retry() }, kernelReady = kernelReady)
                    rowsSection(state, activeSessionId, now, table, gutter, vm, onNewSession, onClearFilters = { vm?.clearFacets() })
                }
            }
        }
        if (kernelReady && vm != null) {
            // The list draws under the top app bar (its padding is the list's content padding),
            // so the indicator starts below the bar instead of behind it.
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
                state = pullState,
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullState,
                        isRefreshing = state.refreshing,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding()),
                    )
                },
            ) { list() }
        } else {
            list()
        }
    }
    if (state.loading) LiveRegionAnnouncer(stringResource(R.string.sessions_loading))
}

private const val ROW_KEY = "r:"

/** The title column of the expanded table: weight 3 of 10 after the 48dp actions column. */
private fun titleColumnWidth(width: Dp): Dp = (width - 48.dp) * 0.3f

private fun LazyListScope.header(
    state: SessionsScreenUiState,
    gutter: Dp,
    onQuery: (String) -> Unit,
    onRetry: () -> Unit,
    kernelReady: Boolean,
) {
    item(key = "description") {
        Text(
            stringResource(R.string.sessions_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.xs),
        )
    }
    item(key = "search") { SessionsSearchField(state.query, onQuery, Modifier.padding(horizontal = gutter, vertical = Spacing.s)) }
    item(key = "kernel") { ShellKernelBanner(hasComposer = false, modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.xs)) }
    if (state.showingCache && !kernelReady) {
        item(key = "cached") {
            Text(
                stringResource(R.string.sessions_showing_cached),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.xs),
            )
        }
    }
    if (state.load is ListLoad.Failed) {
        item(key = "failed") { LoadFailedNotice(onRetry, Modifier.padding(horizontal = gutter)) }
    }
}

private fun LazyListScope.rowsSection(
    state: SessionsScreenUiState,
    activeSessionId: String?,
    now: Long,
    table: Boolean,
    gutter: Dp,
    vm: SessionsScreenViewModel?,
    onNewSession: () -> Unit,
    onClearFilters: () -> Unit,
) {
    when {
        state.loading -> item(key = "skeleton") { SkeletonListRows(3) }
        state.totalRows == 0 && state.load != ListLoad.Initial -> item(key = "empty") {
            EmptyState(
                icon = ClineIcons.ChatBubble,
                title = stringResource(R.string.sessions_empty),
                body = stringResource(R.string.sessions_empty_body),
                actionLabel = stringResource(R.string.sessions_new_session),
                onAction = onNewSession,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxxl),
            )
        }
        state.rows.isEmpty() && state.totalRows > 0 -> item(key = "filtered-empty") {
            Column(Modifier.fillMaxWidth().padding(top = Spacing.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.sessions_filtered_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = gutter),
                )
                if (state.selectedFacets.isNotEmpty()) {
                    TextButton(onClick = onClearFilters) { Text(stringResource(R.string.sessions_filter_clear)) }
                }
            }
        }
        else -> {
            if (table) stickyHeader(key = "table-header") { TableHeader(gutter) }
            state.rows.forEach { row ->
                item(key = ROW_KEY + row.summary.id) {
                    SessionsScreenRowItem(row, state, activeSessionId, now, table, gutter, vm)
                }
            }
            item(key = "footer") { Footer(state, onRetry = { vm?.retryLoadOlder() }) }
        }
    }
}

@Composable
private fun SessionsSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    SearchField(
        value = query,
        onValueChange = onQuery,
        placeholder = stringResource(R.string.sessions_search_placeholder),
        clearContentDescription = stringResource(R.string.sessions_cd_clear_search),
        contentDescription = stringResource(R.string.sessions_cd_search),
        modifier = modifier,
    )
}

@Composable
private fun SortMenuButton(sort: SortDirection, onSort: (SortDirection) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ReportBackLevel(BackLevel.Menu, open)
    Box {
        TooltipIconButton(icon = ClineIcons.Sort, contentDescription = stringResource(R.string.sessions_cd_sort), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(SortDirection.Newest to R.string.sessions_sort_newest, SortDirection.Oldest to R.string.sessions_sort_oldest).forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        open = false
                        onSort(value)
                    },
                    trailingIcon = if (sort == value) {
                        { Icon(ClineIcons.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics { selected = sort == value },
                )
            }
        }
    }
}

@Composable
private fun TableHeader(gutter: Dp) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            HeaderCell(R.string.sessions_col_title, 3f)
            HeaderCell(R.string.sessions_col_workspace, 2f)
            HeaderCell(R.string.sessions_col_model, 2f)
            HeaderCell(R.string.sessions_col_tokens, 1f)
            HeaderCell(R.string.sessions_col_cost, 1f)
            HeaderCell(R.string.sessions_col_time, 1f)
            Spacer(Modifier.width(Spacing.touchTarget))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.HeaderCell(text: Int, weight: Float) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(weight).padding(end = Spacing.s).semantics { heading() },
    )
}

/** The rendered cells of one row (§1.4 formats). */
private class RowTexts(
    val title: String,
    val workspace: String,
    val model: String,
    val tokens: String,
    val cost: String,
    val age: String,
)

@Composable
private fun rowTexts(row: SessionsScreenRow, now: Long): RowTexts {
    val usage = row.summary.usage
    val dash = stringResource(R.string.sessions_dash)
    return RowTexts(
        title = row.summary.displayTitle(),
        workspace = workspaceText(row.summary.workspaceRoot),
        model = modelText(row.summary),
        tokens = tokensLabel(usage?.inputTokens, usage?.outputTokens).takeIf { it != "-" } ?: dash,
        cost = formatCostUsd(usage?.totalCostUsd) ?: dash,
        age = ageText(row.summary.activityAt, now),
    )
}

/** §3.3: the status label leads line 1 only for Failed, Approval required and Question asked. */
private fun SessionVisual.leadsSupportingLine(): Boolean =
    this == SessionVisual.Failed || this == SessionVisual.ApprovalRequired || this == SessionVisual.QuestionAsked

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionsScreenRowItem(
    row: SessionsScreenRow,
    state: SessionsScreenUiState,
    activeSessionId: String?,
    now: Long,
    table: Boolean,
    gutter: Dp,
    vm: SessionsScreenViewModel?,
) {
    val summary = row.summary
    val texts = rowTexts(row, now)
    val pendingKind = state.pending[summary.id]
    val pendingItem = SessionMenuIds.pendingItem(pendingKind)
    val editing = state.editing?.takeIf { it.sessionId == summary.id }
    val items = if (vm != null) sessionMenuItems(summary, state.actionsEnabled, vm.actions) else emptyList()
    val context = LocalContext.current
    val statusLabel = row.visual.rowStatusLabel()
    val usage = summary.usage
    val spokenTokens = if (usage?.inputTokens != null || usage?.outputTokens != null) {
        stringResource(R.string.sessions_tokens_spoken, spokenCount(usage.inputTokens ?: 0), spokenCount(usage.outputTokens ?: 0))
    } else {
        null
    }
    val description = stringResource(
        R.string.sessions_open_session_a11y,
        context.joinA11y(
            listOfNotNull(
                texts.title,
                statusLabel,
                stringResource(R.string.sessions_a11y_pinned).takeIf { summary.pinned },
                texts.workspace,
                texts.model,
                spokenTokens,
                formatCostUsd(usage?.totalCostUsd),
                context.spokenAge(summary.activityAt, now),
            ),
        ),
    )
    var menuOpen by remember { mutableStateOf(false) }
    val press = remember { PressPoint() }
    val haptic = rememberLongPressHaptic()
    val selected = summary.id == activeSessionId
    val palette = sessionRowPalette(MaterialTheme.colorScheme, selected = selected, menuOpen = menuOpen)
    val openLabel = stringResource(R.string.sessions_click_open)
    val menuLabel = stringResource(R.string.sessions_long_click_label)
    val rowModifier = Modifier
        .fillMaxWidth()
        .trackPress(press)
        .menuKey { if (editing == null) menuOpen = true }
        .focusRing(RectangleShape, inset = true)
        .combinedClickable(
            enabled = pendingKind == null && editing == null,
            onClickLabel = openLabel,
            onLongClickLabel = menuLabel,
            onClick = { vm?.actions?.open(summary.id) },
            onLongClick = {
                haptic()
                menuOpen = true
            },
        )
    Box {
        if (editing != null) {
            EditingRow(editing, pendingKind == PendingKind.Rename, texts, row, table, gutter, vm)
        } else if (table) {
            Surface(color = palette.container, contentColor = palette.content, modifier = rowModifier.semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                customActions = items.toCustomActions(pendingItem)
            }) {
                Row(Modifier.heightIn(min = 56.dp).padding(start = gutter, end = gutter - Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(3f).padding(end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        SessionStatusDot(row.visual, standalone = false)
                        Spacer(Modifier.width(Spacing.xs))
                        Text(texts.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (summary.pinned) PinGlyph(palette.muted)
                    }
                    Row(Modifier.weight(2f).padding(end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ClineIcons.Folder, contentDescription = null, modifier = Modifier.size(16.dp), tint = palette.muted)
                        Spacer(Modifier.width(Spacing.xs))
                        Cell(texts.workspace, palette.muted, Modifier.weight(1f))
                    }
                    Cell(texts.model, palette.muted, Modifier.weight(2f), ltr = true)
                    UsageCell(texts.tokens, row.usageLoading, palette.muted, Modifier.weight(1f))
                    UsageCell(texts.cost, row.usageLoading, palette.muted, Modifier.weight(1f))
                    Cell(texts.age, palette.muted, Modifier.weight(1f))
                    RowTrailingAction(texts.title, items, pendingItem)
                }
            }
        } else {
            ListItem(
                modifier = rowModifier.heightIn(min = 88.dp).semantics(mergeDescendants = true) {
                    contentDescription = description
                    this.selected = selected
                    customActions = items.toCustomActions(pendingItem)
                },
                colors = ListItemDefaults.colors(
                    containerColor = palette.container,
                    headlineColor = palette.content,
                    supportingColor = palette.muted,
                    trailingIconColor = palette.muted,
                ),
                leadingContent = { SessionStatusDot(row.visual, standalone = false) },
                headlineContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(texts.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (summary.pinned) PinGlyph(palette.muted)
                    }
                },
                supportingContent = { SupportingLines(row, texts, palette.muted) },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(texts.age, style = MaterialTheme.typography.labelMedium, color = palette.muted)
                        RowTrailingAction(texts.title, items, pendingItem)
                    }
                },
            )
        }
        SessionRowMenu(
            expanded = menuOpen && editing == null,
            onDismissRequest = { menuOpen = false },
            items = items,
            offset = press.offset,
            pendingItemId = pendingItem,
        )
    }
}

/**
 * The colours of one Sessions-screen row, kept to the DS §1.8 text/background pairs (§8.1.9):
 * the open menu lifts the row to `surfaceContainerHighest` (onSurface / onSurfaceVariant), the
 * active session paints `secondaryContainer` with every line in `onSecondaryContainer` (DS §1.4
 * pairs `--accent` with it), and any other row sits on `surface`.
 */
internal data class SessionRowPalette(val container: Color, val content: Color, val muted: Color)

internal fun sessionRowPalette(scheme: ColorScheme, selected: Boolean, menuOpen: Boolean): SessionRowPalette = when {
    menuOpen -> SessionRowPalette(scheme.surfaceContainerHighest, scheme.onSurface, scheme.onSurfaceVariant)
    selected -> SessionRowPalette(scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.onSecondaryContainer)
    else -> SessionRowPalette(scheme.surface, scheme.onSurface, scheme.onSurfaceVariant)
}

@Composable
private fun PinGlyph(tint: Color) {
    Spacer(Modifier.width(Spacing.xs))
    Icon(ClineIcons.PushPinFilled, contentDescription = null, modifier = Modifier.size(16.dp), tint = tint)
}

@Composable
private fun SupportingLines(row: SessionsScreenRow, texts: RowTexts, muted: Color) {
    Column {
        // One flowing line (wrapping to two at most, §8.5): the Failed / Approval required /
        // Question asked label leads it in its state colour, then "<workspace> · <model>".
        val prefix = if (row.visual.leadsSupportingLine()) stringResource(R.string.sessions_status_prefix, row.visual.label()) else null
        val prefixColor = row.visual.glyph?.tint() ?: muted
        val join = stringResource(R.string.sessions_supporting_join, texts.workspace, texts.model)
        Text(
            buildAnnotatedString {
                if (prefix != null) withStyle(SpanStyle(color = prefixColor)) { append(prefix) }
                append(join)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.usageLoading) {
                Skeleton(Modifier.width(12.dp).height(12.dp))
                Spacer(Modifier.width(Spacing.xs))
            }
            LtrText(
                stringResource(R.string.sessions_supporting_join, texts.tokens, texts.cost),
                style = ClineMono.small,
                color = muted,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun Cell(text: String, color: Color, modifier: Modifier, ltr: Boolean = false) {
    if (ltr) {
        LtrText(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(end = Spacing.s))
    } else {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.padding(end = Spacing.s))
    }
}

@Composable
private fun UsageCell(text: String, loading: Boolean, color: Color, modifier: Modifier) {
    Row(modifier.padding(end = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            Skeleton(Modifier.width(12.dp).height(12.dp))
            Spacer(Modifier.width(Spacing.xs))
        }
        LtrText(text, style = ClineMono.small, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The overflow `more_vert` (CD "Session actions for <title>"), or a 16dp spinner while the row's action runs. */
@Composable
private fun RowTrailingAction(title: String, items: List<ContextMenuItem>, pendingItemId: String?) {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        if (pendingItemId != null) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        } else {
            var anchorOpen by remember { mutableStateOf(false) }
            TooltipIconButton(
                icon = ClineIcons.MoreVert,
                contentDescription = stringResource(R.string.sessions_cd_actions_for, title),
                tooltip = stringResource(R.string.sessions_long_click_label),
                onClick = { anchorOpen = true },
                enabled = items.isNotEmpty(),
            )
            SessionRowMenu(expanded = anchorOpen, onDismissRequest = { anchorOpen = false }, items = items, pendingItemId = pendingItemId)
        }
    }
}

/** §3.6 Rename mode: the dense field in the headline (spanning Title + Workspace on the table) + Save / Cancel. */
@Composable
private fun EditingRow(
    draft: RenameDraft,
    pending: Boolean,
    texts: RowTexts,
    row: SessionsScreenRow,
    table: Boolean,
    gutter: Dp,
    vm: SessionsScreenViewModel?,
) {
    var text by remember(draft.sessionId) { mutableStateOf(draft.initialTitle) }
    val field: @Composable (Modifier) -> Unit = { modifier ->
        RenameField(
            draft = draft,
            pending = pending,
            onCommit = { if (it.isNotBlank()) vm?.actions?.commitRename(it) },
            onCancel = { vm?.actions?.cancelRename() },
            commitOnBlur = false,
            contentDescription = stringResource(R.string.sessions_cd_rename_title, texts.title),
            onTextChange = { text = it },
            modifier = modifier,
        )
    }
    val buttons: @Composable () -> Unit = {
        IconButton(
            onClick = { vm?.actions?.commitRename(text) },
            enabled = text.isNotBlank() && !pending,
            modifier = Modifier.focusRing(CircleShape),
        ) {
            if (pending) {
                InlineSpinner()
            } else {
                Icon(ClineIcons.Check, contentDescription = stringResource(R.string.sessions_cd_save_title))
            }
        }
        IconButton(onClick = { vm?.actions?.cancelRename() }, enabled = !pending, modifier = Modifier.focusRing(CircleShape)) {
            Icon(ClineIcons.Close, contentDescription = stringResource(R.string.sessions_cd_cancel_rename))
        }
    }
    // The editing row sits on `surface`, so its secondary lines keep onSurfaceVariant.
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (table) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = gutter), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(5f).padding(end = Spacing.s)) { field(Modifier) }
            Cell(texts.model, muted, Modifier.weight(2f), ltr = true)
            Cell(texts.tokens, muted, Modifier.weight(1f))
            Cell(texts.cost, muted, Modifier.weight(1f))
            Cell(texts.age, muted, Modifier.weight(1f))
            buttons()
        }
    } else {
        ListItem(
            modifier = Modifier.heightIn(min = 88.dp),
            leadingContent = { SessionStatusDot(row.visual, standalone = false) },
            headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { field(Modifier) }
                    buttons()
                }
            },
            supportingContent = { SupportingLines(row, texts, muted) },
        )
    }
}

@Composable
private fun Footer(state: SessionsScreenUiState, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        when {
            state.loadMoreHalted -> TextButton(onClick = onRetry) { Text(stringResource(R.string.sessions_show_more)) }
            state.mayHaveMore -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.sessions_loading_ellipsis), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> Text(
                copyPluralStringResource(R.plurals.sessions_count, state.totalFiltered, state.totalFiltered),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** §3.4 "Filter sessions" sheet: "Clear filters", then one checkbox row per facet (verbatim, mono). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FacetFilterSheet(
    state: SessionsScreenUiState,
    onToggle: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester? = null,
) {
    // No half-height anchor: a long facet list opens full and one predictive back closes it (DS §5.8).
    ClineSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        title = stringResource(R.string.sessions_filter_sheet_title),
        returnFocus = returnFocus,
    ) {
        if (state.selectedFacets.isNotEmpty()) {
            TextButton(onClick = onClear, modifier = Modifier.padding(horizontal = Spacing.m)) { Text(stringResource(R.string.sessions_filter_clear)) }
        }
        if (state.facetOptions.isEmpty()) {
            ListItem(
                headlineContent = {
                    Text(stringResource(R.string.sessions_filter_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                colors = sheetListItemColors(),
            )
        }
        state.facetOptions.forEach { facet ->
            val checked = facet in state.selectedFacets
            ListItem(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(facet) }),
                headlineContent = { LtrText(facet, style = ClineMono.small) },
                trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                colors = sheetListItemColors(),
            )
        }
    }
}

/** Rows inside a sheet sit on the sheet's own container (`surfaceContainerLow`), not on `surface`. */
@Composable
private fun sheetListItemColors(): ListItemColors = ListItemDefaults.colors(containerColor = Color.Transparent)


// --------------------------------------------------------------------------- previews

private fun previewScreenState(rows: List<SessionsScreenRow> = SessionsPreviewData.rows.map { SessionsScreenRow(it.summary, it.visual, it.summary.usage == null) }) =
    SessionsScreenUiState(
        connected = true,
        actionsEnabled = true,
        load = ListLoad.Loaded,
        loading = false,
        rows = rows,
        totalRows = rows.size,
        totalFiltered = rows.size,
        facetOptions = listOf("model:claude-sonnet-4-5", "pinned:yes", "provider:anthropic", "status:running", "workspace:app"),
    )

@LightDarkPreviews
@Composable
private fun SessionsScreenPopulatedPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.height(720.dp)) {
            SessionsScreenContent(previewScreenState(), activeSessionId = "s1", kernelReady = true, vm = null, onBack = {}, onNewSession = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun SessionsScreenLoadingPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.height(480.dp)) {
            SessionsScreenContent(SessionsScreenUiState(), activeSessionId = null, kernelReady = true, vm = null, onBack = {}, onNewSession = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun SessionsScreenEmptyPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.height(480.dp)) {
            SessionsScreenContent(previewScreenState(emptyList()), activeSessionId = null, kernelReady = true, vm = null, onBack = {}, onNewSession = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun SessionsScreenFilteredEmptyPreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.height(480.dp)) {
            SessionsScreenContent(
                previewScreenState(emptyList()).copy(totalRows = 5, query = "zzz", selectedFacets = setOf("status:failed")),
                activeSessionId = null,
                kernelReady = true,
                vm = null,
                onBack = {},
                onNewSession = {},
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun SessionsScreenOfflinePreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.height(600.dp)) {
            SessionsScreenContent(
                previewScreenState().copy(showingCache = true, connected = false, actionsEnabled = false, mayHaveMore = false, load = ListLoad.Failed("timeout")),
                activeSessionId = null,
                kernelReady = false,
                vm = null,
                onBack = {},
                onNewSession = {},
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun FacetFilterSheetPreview() {
    ComponentPreview {
        Column {
            val state = previewScreenState().copy(selectedFacets = setOf("status:running"))
            state.facetOptions.forEach { facet ->
                ListItem(
                    headlineContent = { LtrText(facet, style = ClineMono.small) },
                    trailingContent = { Checkbox(checked = facet in state.selectedFacets, onCheckedChange = null) },
                )
                HorizontalDivider()
            }
        }
    }
}

