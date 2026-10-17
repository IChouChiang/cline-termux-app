package dev.cline.termux.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import dev.cline.termux.R
import dev.cline.termux.appGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.prefs.SidebarSortMode
import dev.cline.termux.data.sessions.ListLoad
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.ShellDrawerScope
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.KernelBannerHost
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.Account
import dev.cline.termux.ui.navigation.DrawerVariant
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.navigation.TopLevelDestination
import dev.cline.termux.ui.navigation.shellRoute
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.Flow

// ----------------------------------------------------------------------------- seams

/** The drawer / list-pane ViewModel, shared by the list, the footer and the rail badge (shell scope). */
@Composable
fun sessionsDrawerViewModel(): SessionsDrawerViewModel {
    val graph = LocalContext.current.appGraph
    return viewModel(factory = SessionsDrawerViewModel.factory(graph))
}

/**
 * `ShellSlots.sessionList` (sessions.md S1): the kernel notice, the list header (Sessions /
 * Projects, sort toggle, filter menu), the sections and paging, the delete dialog and the
 * details sheet. Plug in as `sessionList = { SessionsDrawerContent(it) }`.
 */
@Composable
fun ShellDrawerScope.SessionsDrawerContent(modifier: Modifier = Modifier) {
    val vm = sessionsDrawerViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val scope = this
    LaunchedEffect(activeSessionId) { vm.setActiveSession(activeSessionId) }
    val modal = variant != DrawerVariant.ListPane
    LaunchedEffect(drawerOpen, modal) {
        if (modal && drawerOpen) vm.onDrawerOpened()
        // §2.9: a scrim tap (or any close) while renaming in the modal drawer cancels the edit.
        if (modal && !drawerOpen) vm.actions.cancelRename()
    }
    val renaming = state.editing != null
    LaunchedEffect(renaming) { scope.setRenaming(renaming) }
    // The modal drawer covers the screen's own snackbar host, so its failures ("Pin failed. …",
    // "Rename failed. …") show in a host inside the sheet while it is open (DS §4.1 #17).
    val drawerSnackbar = remember { SnackbarHostState() }
    val inDrawer = modal && drawerOpen
    LaunchedEffect(inDrawer) { if (!inDrawer) drawerSnackbar.currentSnackbarData?.dismiss() }
    SessionsEventsEffect(vm.events, navigator, activeSessionId, snackbar = if (inDrawer) drawerSnackbar else LocalSnackbarHost.current)

    val backStack by navigator.nav.currentBackStackEntryAsState()
    val sessionsOnScreen = backStack?.shellRoute() == dev.cline.termux.ui.navigation.Sessions

    Box(modifier) {
        SessionsDrawerList(
            state = state,
            gate = gate,
            kernelActionInFlight = kernelActionInFlight != null,
            onKernelAction = ::onKernelAction,
            commitOnBlur = !modal || drawerOpen,
            sessionsOnScreen = sessionsOnScreen,
            callbacks = DrawerCallbacks(
                onOpen = vm.actions::open,
                onOpenSessions = navigator::openSessions,
                onToggleSort = vm::toggleSortMode,
                onStatusFilter = vm::setStatusFilter,
                onSourceFilter = vm::setSourceFilter,
                onToggleGroup = vm::toggleGroup,
                onShowMore = vm::showMore,
                onCommitRename = vm.actions::commitRename,
                onCancelRename = vm.actions::cancelRename,
                onRetry = vm::retry,
            ),
            actions = vm.actions,
            modifier = Modifier.fillMaxSize(),
        )
        if (modal) SnackbarHost(drawerSnackbar, Modifier.align(Alignment.BottomCenter))
    }

    state.deleteCandidate?.let { candidate ->
        DeleteSessionDialog(
            summary = candidate,
            deleting = state.pending[candidate.id] == dev.cline.termux.data.session.PendingKind.Delete,
            onConfirm = vm.actions::confirmDelete,
            onDismiss = vm.actions::dismissDelete,
        )
    }
    state.detailsFor?.let { id ->
        SessionDetailsSheetHost(sessionId = id, port = vm.port, onDismiss = vm.actions::dismissDetails)
    }
}

/**
 * `ShellSlots.drawerFooter` (sessions.md §2.3): the account row (initial, first name, scope →
 * Settings > Account) plus the Settings button, or a "Settings" item without an account.
 */
@Composable
fun ShellDrawerScope.SessionsDrawerFooter() {
    val vm = sessionsDrawerViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    SessionsFooter(
        account = state.account,
        settingsSelected = area == TopLevelDestination.Settings,
        onAccount = {
            navigator.closeDrawer()
            navigator.push(Account())
        },
        onSettings = { navigator.open(TopLevelDestination.Settings) },
    )
}

/**
 * `ShellSlots.railBadge` (sessions.md §1.6): the 6dp badge on "Chats" while a session is unread
 * and the session list is not on screen. [listOnScreen] is the shell's "expanded list pane shown"
 * (layout `showsListPane` and not collapsed).
 */
@Composable
fun sessionsRailBadge(destination: TopLevelDestination, listOnScreen: Boolean = LocalShellLayout.current.showsListPane): Boolean {
    if (destination != TopLevelDestination.Chats) return false
    val vm = sessionsDrawerViewModel()
    val state by vm.uiState.collectAsStateWithLifecycle()
    return state.anyUnread && !listOnScreen
}

/** Snackbars and navigation for the sessions surfaces (F1 open, F4 open the fork, F5 Home). */
@Composable
fun SessionsEventsEffect(
    events: Flow<SessionsEvent>,
    navigator: ShellNavigator,
    activeSessionId: String?,
    snackbar: SnackbarHostState = LocalSnackbarHost.current,
) {
    val host by rememberUpdatedState(snackbar)
    val context = LocalContext.current
    val active by rememberUpdatedState(activeSessionId)
    LaunchedEffect(events, navigator) {
        events.collect { event ->
            when (event) {
                is SessionsEvent.OpenSession -> navigator.openSession(event.sessionId)
                is SessionsEvent.Deleted -> if (event.sessionId == active) navigator.openHome()
                is SessionsEvent.Message -> host.showReplacing(
                    context.messageText(event.message),
                    duration = androidx.compose.material3.SnackbarDuration.Long,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- the list

class DrawerCallbacks(
    val onOpen: (String) -> Unit,
    val onOpenSessions: () -> Unit,
    val onToggleSort: () -> Unit,
    val onStatusFilter: (StatusFilter) -> Unit,
    val onSourceFilter: (String?) -> Unit,
    val onToggleGroup: (String, Boolean) -> Unit,
    val onShowMore: (ShowMoreKind) -> Unit,
    val onCommitRename: (String) -> Unit,
    val onCancelRename: () -> Unit,
    val onRetry: () -> Unit,
)

/** The stateless S1 list area: list header fixed, notices and sections scrolling under a 16dp fade. */
@Composable
fun SessionsDrawerList(
    state: SessionsDrawerUiState,
    gate: KernelGate,
    kernelActionInFlight: Boolean,
    onKernelAction: (KernelAction) -> Unit,
    commitOnBlur: Boolean,
    sessionsOnScreen: Boolean,
    callbacks: DrawerCallbacks,
    actions: SessionRowActions?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val collapsed = rememberSaveableStringSet()
    val now = rememberMinuteTick()
    Column(modifier) {
        ListHeader(state, sessionsOnScreen, callbacks)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().imePadding(),
                contentPadding = PaddingValues(start = Spacing.m, end = Spacing.m, bottom = Spacing.s),
            ) {
                item(key = "kernel") {
                    KernelBannerHost(gate, hasComposer = false, onAction = onKernelAction, actionInFlight = kernelActionInFlight, modifier = Modifier.padding(vertical = Spacing.xs))
                }
                val kernelDown = gate !is KernelGate.Ready && gate !is KernelGate.Busy && gate !is KernelGate.DegradedNoCommands
                if (state.showingCache && kernelDown) {
                    item(key = "cached") { CachedCaption() }
                }
                if (state.load is ListLoad.Failed) {
                    item(key = "failed") { LoadFailedNotice(onRetry = callbacks.onRetry) }
                }
                body(state, collapsed, now, commitOnBlur, callbacks, actions)
            }
            ScrolledFade(listState)
        }
    }
}

private fun LazyListScope.body(
    state: SessionsDrawerUiState,
    collapsed: MutableState<Set<String>>,
    now: Long,
    commitOnBlur: Boolean,
    callbacks: DrawerCallbacks,
    actions: SessionRowActions?,
) {
    when (val body = state.body) {
        DrawerBody.Loading -> item(key = "loading") { BodyText(stringResource(R.string.sessions_loading)) }
        DrawerBody.Empty -> item(key = "empty") { BodyText(stringResource(R.string.sessions_empty_drawer)) }
        is DrawerBody.Flat -> {
            rows(body.rows, state, now, commitOnBlur, callbacks, actions, nestedIndent = false)
            body.showMore?.let { showMoreItem("more:tasks", it, callbacks) }
        }
        is DrawerBody.Categories -> body.sections.forEach { section ->
            val key = "cat:" + section.category.name
            val expanded = key !in collapsed.value
            item(key = key) {
                SectionToggleHeader(
                    label = stringResource(
                        when (section.category) {
                            Category.Pinned -> R.string.sessions_section_pinned
                            Category.Scheduled -> R.string.sessions_section_scheduled
                            Category.Tasks -> R.string.sessions_section_tasks
                        },
                    ),
                    expanded = expanded,
                    count = section.count,
                    onToggle = { collapsed.value = collapsed.value.toggle(key) },
                    modifier = rowAnimation(),
                )
            }
            if (expanded) {
                rows(section.rows, state, now, commitOnBlur, callbacks, actions, nestedIndent = false)
                section.showMore?.let { showMoreItem("more:" + section.category.name, it, callbacks) }
            }
        }
        is DrawerBody.Projects -> {
            body.groups.forEach { group ->
                val key = "proj:" + group.id
                val expanded = key !in collapsed.value
                item(key = key) {
                    SectionToggleHeader(
                        label = group.label.text(),
                        expanded = expanded,
                        onToggle = { collapsed.value = collapsed.value.toggle(key) },
                        modifier = rowAnimation(),
                    )
                }
                if (expanded) {
                    rows(group.rows, state, now, commitOnBlur, callbacks, actions, nestedIndent = true, keyPrefix = group.id)
                    group.showMore?.let { showMoreItem("more:" + group.id, it, callbacks) }
                }
            }
            body.showMore?.let { showMoreItem("more:history", it, callbacks) }
        }
    }
}

private fun LazyListScope.rows(
    rows: List<DrawerRow>,
    state: SessionsDrawerUiState,
    now: Long,
    commitOnBlur: Boolean,
    callbacks: DrawerCallbacks,
    actions: SessionRowActions?,
    nestedIndent: Boolean,
    keyPrefix: String = "",
) {
    rows.forEach { row ->
        when (row) {
            is DrawerRow.Session -> item(key = keyPrefix + row.key) {
                SessionItem(row, state, now, commitOnBlur, callbacks, actions, nested = false, indent = nestedIndent)
            }
            is DrawerRow.ScheduleGroup -> {
                item(key = keyPrefix + row.key) {
                    Box(rowAnimation().padding(start = if (nestedIndent) Spacing.m else 0.dp)) {
                        ScheduleGroupRow(row, onToggle = { callbacks.onToggleGroup(row.id, row.expanded) })
                    }
                }
                if (row.expanded) {
                    row.runs.forEach { run ->
                        item(key = keyPrefix + row.key + "/" + run.key) {
                            SessionItem(run, state, now, commitOnBlur, callbacks, actions, nested = true, indent = nestedIndent)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.SessionItem(
    row: DrawerRow.Session,
    state: SessionsDrawerUiState,
    now: Long,
    commitOnBlur: Boolean,
    callbacks: DrawerCallbacks,
    actions: SessionRowActions?,
    nested: Boolean,
    indent: Boolean,
) {
    val summary = row.summary
    val editing = state.editing?.takeIf { it.sessionId == summary.id }
    val pendingKind = state.pending[summary.id]
    val modifier = rowAnimation().padding(start = if (indent) Spacing.m else 0.dp)
    if (editing != null) {
        val title = summary.displayTitle()
        Box(modifier.padding(vertical = Spacing.xxs)) {
            RenameField(
                draft = editing,
                pending = pendingKind == dev.cline.termux.data.session.PendingKind.Rename,
                onCommit = callbacks.onCommitRename,
                onCancel = callbacks.onCancelRename,
                commitOnBlur = commitOnBlur,
                contentDescription = stringResource(R.string.sessions_cd_rename_title, title),
            )
        }
        return
    }
    val items = if (actions != null) sessionMenuItems(summary, state.actionsEnabled, actions) else emptyList()
    SessionDrawerRow(
        item = row,
        selected = summary.id == state.activeSessionId,
        now = now,
        actionsEnabled = state.actionsEnabled,
        menuItems = items,
        pendingItemId = SessionMenuIds.pendingItem(pendingKind),
        onOpen = { callbacks.onOpen(summary.id) },
        nested = nested,
        modifier = modifier,
    )
}

/** `Modifier.animateItem()` (M3 defaults), or instant placement under reduced motion (§7). */
@Composable
fun LazyItemScope.rowAnimation(): Modifier = if (ClineTheme.reducedMotion) Modifier else Modifier.animateItem()

private fun LazyListScope.showMoreItem(key: String, more: ShowMore, callbacks: DrawerCallbacks) {
    item(key = key) {
        val label = when (val kind = more.kind) {
            is ShowMoreKind.Project -> stringResource(R.string.sessions_show_more_in, kind.label.text())
            else -> stringResource(R.string.sessions_show_more)
        }
        TextButton(
            onClick = { callbacks.onShowMore(more.kind) },
            enabled = !more.loading,
            modifier = rowAnimation().heightIn(min = 48.dp).padding(start = Spacing.xs),
        ) {
            if (more.loading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.sessions_loading_ellipsis))
            } else {
                Text(label)
                Spacer(Modifier.width(Spacing.xs))
                Icon(ClineIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BodyText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(Spacing.l),
    )
}

@Composable
private fun CachedCaption() {
    Text(
        stringResource(R.string.sessions_showing_cached),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xs),
    )
}

/**
 * The list notice for a failed `session.list` after a successful load (§2.7 "Error"): the
 * voice `ListNotice` treatment (`surfaceContainerHigh`, `shapes.large`, 20dp icon, polite).
 */
@Composable
fun LoadFailedNotice(onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val title = stringResource(R.string.sessions_load_failed_title)
    val body = stringResource(R.string.sessions_unavailable)
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs),
    ) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(ClineIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.sessions_retry)) }
        }
    }
    LiveRegionAnnouncer(stringResource(R.string.sessions_snackbar, title, body))
}

/** The desktop's gradient fade over the scrolled list's top edge (16dp). */
@Composable
private fun ScrolledFade(listState: LazyListState) {
    val scrolled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
    if (!scrolled) return
    val top = MaterialTheme.colorScheme.surfaceContainerLow
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Brush.verticalGradient(listOf(top, Color.Transparent))),
    )
}

// ------------------------------------------------------------------------ list header

@Composable
private fun ListHeader(state: SessionsDrawerUiState, sessionsOnScreen: Boolean, callbacks: DrawerCallbacks) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = Spacing.l - Spacing.m, end = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The label takes the free width (and ellipsizes inside it), so the sort and filter
        // buttons stay end-aligned whatever the label's length ("Sessions" vs "Projects").
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            TextButton(onClick = callbacks.onOpenSessions) {
                Text(
                    stringResource(if (state.sortMode == SidebarSortMode.Time) R.string.sessions_header_time else R.string.sessions_header_project),
                    style = MaterialTheme.typography.titleSmall,
                    color = if (sessionsOnScreen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val time = state.sortMode == SidebarSortMode.Time
        TooltipIconButton(
            icon = if (time) ClineIcons.Folder else ClineIcons.Schedule,
            contentDescription = stringResource(
                R.string.sessions_cd_sort_mode,
                stringResource(if (time) R.string.sessions_sort_mode_time else R.string.sessions_sort_mode_project),
            ),
            tooltip = stringResource(if (time) R.string.sessions_tooltip_sorted_by_time else R.string.sessions_tooltip_grouped_by_project),
            onClick = callbacks.onToggleSort,
            iconSize = 20.dp,
        )
        FilterMenuButton(state, callbacks)
    }
}

@Composable
private fun FilterMenuButton(state: SessionsDrawerUiState, callbacks: DrawerCallbacks) {
    var open by remember { mutableStateOf(false) }
    dev.cline.termux.ui.navigation.ReportBackLevel(dev.cline.termux.ui.navigation.BackLevel.Menu, open)
    Box {
        TooltipIconButton(
            icon = ClineIcons.FilterList,
            contentDescription = stringResource(if (state.filtered) R.string.sessions_cd_filter_active else R.string.sessions_cd_filter),
            tooltip = stringResource(R.string.sessions_cd_filter),
            onClick = { open = true },
            iconSize = 20.dp,
            colors = if (state.filtered) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(200.dp)) {
            MenuGroupLabel(stringResource(R.string.sessions_filter_status))
            // Each radio set is its own single-choice group for TalkBack (DS §8).
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                RadioMenuItem(stringResource(R.string.sessions_filter_all), state.statusFilter == StatusFilter.All) {
                    open = false
                    callbacks.onStatusFilter(StatusFilter.All)
                }
                RadioMenuItem(stringResource(R.string.sessions_filter_running), state.statusFilter == StatusFilter.Running) {
                    open = false
                    callbacks.onStatusFilter(StatusFilter.Running)
                }
            }
            if (state.sourceOptions.isNotEmpty()) {
                HorizontalDivider()
                MenuGroupLabel(stringResource(R.string.sessions_filter_source))
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    RadioMenuItem(stringResource(R.string.sessions_filter_all_sources), state.sourceFilter == null) {
                        open = false
                        callbacks.onSourceFilter(null)
                    }
                    state.sourceOptions.forEach { option ->
                        RadioMenuItem(option.label, state.sourceFilter == option.source) {
                            open = false
                            callbacks.onSourceFilter(option.source)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s).semantics { heading() },
    )
}

@Composable
private fun RadioMenuItem(label: String, selected: Boolean, onSelect: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onSelect,
        leadingIcon = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
        },
    )
}

// ---------------------------------------------------------------------------- footer

@Composable
fun SessionsFooter(account: AccountFooter?, settingsSelected: Boolean, onAccount: () -> Unit, onSettings: () -> Unit) {
    if (account == null) {
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.sessions_settings), maxLines = 1) },
            icon = { Icon(if (settingsSelected) ClineIcons.Chrome.SettingsSelected else ClineIcons.Chrome.Settings, contentDescription = null) },
            selected = settingsSelected,
            onClick = onSettings,
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding).padding(vertical = Spacing.xs).focusRing(CircleShape),
        )
        return
    }
    val name = account.name.ifEmpty { stringResource(R.string.sessions_account_name_fallback) }
    val initial = (account.initial ?: name.firstOrNull()?.uppercaseChar())?.toString().orEmpty()
    val description = stringResource(R.string.sessions_cd_account_row, account.summary)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
        PlainTooltipBox(text = account.summary, modifier = Modifier.weight(1f)) {
            Surface(
                onClick = onAccount,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0f),
                modifier = Modifier.heightIn(min = 48.dp).focusRing(CircleShape).semantics(mergeDescendants = true) { contentDescription = description },
            ) {
                Row(Modifier.padding(horizontal = Spacing.s, vertical = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary, CircleShape), contentAlignment = Alignment.Center) {
                        Text(initial, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(Spacing.m))
                    Text(name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        account.scope ?: stringResource(R.string.sessions_account_scope_personal),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        TooltipIconButton(
            icon = if (settingsSelected) ClineIcons.Chrome.SettingsSelected else ClineIcons.Chrome.Settings,
            contentDescription = stringResource(R.string.sessions_settings),
            onClick = onSettings,
        )
    }
}

// ----------------------------------------------------------------------------- helpers

private fun Set<String>.toggle(key: String): Set<String> = if (key in this) this - key else this + key

/** A `rememberSaveable` string set (collapsed sections survive rotation and process death, §2.5 item 6). */
@Composable
fun rememberSaveableStringSet(): MutableState<Set<String>> =
    rememberSaveable(saver = StringSetSaver) { mutableStateOf(emptySet()) }

private val StringSetSaver: Saver<MutableState<Set<String>>, ArrayList<String>> = Saver(
    save = { ArrayList(it.value) },
    restore = { mutableStateOf(it.toSet()) },
)

// ---------------------------------------------------------------------------- previews

private val previewCallbacks = DrawerCallbacks({}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {})

@Composable
private fun DrawerPreviewFrame(state: SessionsDrawerUiState, gate: KernelGate = KernelGate.Ready) {
    ComponentPreview(padding = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.width(360.dp).height(560.dp)) {
                SessionsDrawerList(state, gate, false, {}, true, false, previewCallbacks, null, Modifier.weight(1f))
                HorizontalDivider()
                SessionsFooter(state.account, settingsSelected = false, onAccount = {}, onSettings = {})
            }
        }
    }
}

private fun previewState(body: DrawerBody, account: AccountFooter? = AccountFooter("Ada Lovelace", null)) =
    SessionsDrawerUiState(body = body, actionsEnabled = true, load = ListLoad.Loaded, activeSessionId = "s1", account = account)

@LightDarkPreviews
@Composable
private fun DrawerCategoriesPreview() {
    val rows = SessionsPreviewData.rows
    DrawerPreviewFrame(
        previewState(
            DrawerBody.Categories(
                listOf(
                    CategorySection(Category.Pinned, 1, listOf(rows[0].asDrawerRow()), null),
                    CategorySection(Category.Scheduled, 1, listOf(SessionsPreviewData.group(expanded = false)), null),
                    CategorySection(Category.Tasks, 4, rows.drop(1).map { it.asDrawerRow() }, ShowMore(ShowMoreKind.Tasks, loading = false)),
                ),
            ),
        ),
    )
}

@LightDarkPreviews
@Composable
private fun DrawerFlatLoadingMorePreview() {
    val rows = SessionsPreviewData.rows
    DrawerPreviewFrame(previewState(DrawerBody.Flat(rows.map { it.asDrawerRow() }, ShowMore(ShowMoreKind.Tasks, loading = true)), account = null))
}

@LightDarkPreviews
@Composable
private fun DrawerProjectsPreview() {
    val rows = SessionsPreviewData.rows
    DrawerPreviewFrame(
        previewState(
            DrawerBody.Projects(
                listOf(
                    ProjectSection("a", ProjectLabel.Path("a/app"), rows.take(2).map { it.asDrawerRow() }, null),
                    ProjectSection("chat", ProjectLabel.Chat, rows.drop(2).map { it.asDrawerRow() }, ShowMore(ShowMoreKind.Project("chat", ProjectLabel.Chat), false)),
                ),
                ShowMore(ShowMoreKind.History, false),
            ),
        ).copy(sortMode = SidebarSortMode.Project, statusFilter = StatusFilter.Running),
    )
}

@LightDarkPreviews
@Composable
private fun DrawerLoadingPreview() {
    DrawerPreviewFrame(SessionsDrawerUiState(body = DrawerBody.Loading), gate = KernelGate.Busy(KernelGate.BusyKind.Connecting, null, slow = false))
}

@LightDarkPreviews
@Composable
private fun DrawerEmptyPreview() {
    DrawerPreviewFrame(previewState(DrawerBody.Empty))
}

@LightDarkPreviews
@Composable
private fun DrawerCachedKernelDownPreview() {
    val rows = SessionsPreviewData.rows
    DrawerPreviewFrame(
        previewState(DrawerBody.Flat(rows.map { it.asDrawerRow() }, null)).copy(showingCache = true, actionsEnabled = false),
        gate = KernelGate.Down(KernelGate.DownVariant.Asleep, null),
    )
}

@LightDarkPreviews
@Composable
private fun DrawerLoadFailedPreview() {
    val rows = SessionsPreviewData.rows
    DrawerPreviewFrame(previewState(DrawerBody.Flat(rows.map { it.asDrawerRow() }, null)).copy(load = ListLoad.Failed("timeout")))
}
