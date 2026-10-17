package dev.cline.termux.ui.providers

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.components.FabLaneTooltip
import dev.cline.termux.ui.components.KernelBlockHandler
import dev.cline.termux.ui.components.HelperUnavailableCard
import dev.cline.termux.ui.components.rememberFabLane
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.isScrollingUp
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.Spacing

/*
 * S4 Providers list (settings-providers.md S4; desktop `ProviderListContent`). The compact /
 * medium screen is a [PageScaffold] with the ExtendedFAB; the expanded list pane is
 * [ProvidersListPane] inside the providers list-detail scaffold (ProvidersGraph.kt).
 */

/** What the list's rows and cards do. */
class ProvidersListActions(
    val onQuery: (String) -> Unit,
    val onToggleSection: (ProviderSection) -> Unit,
    val onOpen: (String) -> Unit,
    val onAdd: () -> Unit,
    val onRefresh: () -> Unit,
    val onRetryLoad: () -> Unit,
    val onRetryHelper: () -> Unit,
    val onStartKernel: () -> Unit,
    val blockHandler: KernelBlockHandler,
)

/** Compact and medium: the full screen with the ExtendedFAB (DS §5.4 "Providers: Add provider"). */
@Composable
fun ProvidersListScreen(
    state: ProvidersListUiState,
    actions: ProvidersListActions,
    navigationIcon: @Composable () -> Unit,
    gutter: Dp,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    // DS §5.4 / FabLane: icon-only beside an end lane on a height-compact window.
    val fabLane = rememberFabLane(listState.isScrollingUp())
    val addLabel = stringResource(R.string.providers_add)
    PageScaffold(
        title = stringResource(R.string.providers_title),
        modifier = modifier,
        navigationIcon = navigationIcon,
        loading = state.loading || state.helperStarting,
        floatingActionButton = {
            if (state.showFab) {
                FabLaneTooltip(fabLane, addLabel) {
                    ExtendedFloatingActionButton(
                        text = { Text(addLabel) },
                        icon = { Icon(ClineIcons.Add, contentDescription = null) },
                        onClick = actions.onAdd,
                        expanded = fabLane.expanded,
                        // The label stays in semantics when the FAB collapses to its icon (S4.6).
                        modifier = Modifier.semantics { contentDescription = addLabel },
                    )
                }
            }
        },
    ) { padding ->
        ProvidersListBody(
            state = state,
            actions = actions,
            contentPadding = padding,
            gutter = gutter,
            endExtra = if (state.showFab) fabLane.endPadding(gutter) - gutter else 0.dp,
            bottomExtra = fabLane.bottomClearance,
            selectedId = null,
            listState = listState,
        )
    }
}

/**
 * The scrolling body shared by the screen and the expanded pane: subtitle, search, cards and
 * the three sections in one `LazyColumn` with pull-to-refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvidersListBody(
    state: ProvidersListUiState,
    actions: ProvidersListActions,
    contentPadding: PaddingValues,
    gutter: Dp,
    bottomExtra: Dp,
    selectedId: String?,
    endExtra: Dp = 0.dp,
    listState: androidx.compose.foundation.lazy.LazyListState = rememberLazyListState(),
) {
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // DS §5.6: all four sides of the Scaffold's padding (landscape 3-button navigation, a side cutout).
    val direction = LocalLayoutDirection.current
    val padding = PaddingValues(
        start = contentPadding.calculateStartPadding(direction),
        top = contentPadding.calculateTopPadding(),
        end = contentPadding.calculateEndPadding(direction) + endExtra,
        bottom = contentPadding.calculateBottomPadding() + bottomExtra + if (bottomExtra > 0.dp) navBottom else 0.dp,
    )
    PullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = actions.onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "banner") { Box(Modifier.padding(horizontal = gutter)) { ShellKernelBanner(hasComposer = false) } }
            item(key = "subtitle") {
                Text(
                    text = if (state.configuredCount == 0) {
                        stringResource(R.string.providers_subtitle_empty)
                    } else {
                        stringResource(R.string.providers_subtitle_counts, state.configuredCount, state.totalCount)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.s),
                )
            }
            if (state.blocked != null) {
                item(key = "blocked") {
                    KernelBlockedCard(
                        block = state.blocked,
                        onAction = { action -> actions.blockHandler.onAction(state.blocked, action) },
                        permissionPermanentlyDenied = actions.blockHandler.permissionPermanentlyDenied,
                        modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
                    )
                }
                return@LazyColumn
            }
            item(key = "search") {
                ProviderSearchField(
                    query = state.query,
                    enabled = !state.loading && !state.helperStarting,
                    onQuery = actions.onQuery,
                    modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
                )
            }
            if (state.helperCard != null && !state.helperStarting) {
                item(key = "helper") {
                    HelperUnavailableCard(
                        model = state.helperCard,
                        onRetry = actions.onRetryHelper,
                        onStartKernel = actions.onStartKernel,
                        modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
                    )
                }
            }
            when {
                state.helperStarting -> item(key = "starting") { CenteredStatus(stringResource(R.string.providers_helper_starting)) }
                state.loading -> item(key = "loading") { CenteredStatus(stringResource(R.string.providers_loading)) }
                state.error != null && state.rows.isEmpty -> item(key = "error") {
                    LoadError(state.error, onRetry = actions.onRetryLoad)
                }
                state.emptySearch -> item(key = "nomatch") { NoProviderMatch(state.query, gutter) }
                else -> providerSections(state, actions, selectedId, gutter)
            }
        }
    }
}

private fun LazyListScope.providerSections(
    state: ProvidersListUiState,
    actions: ProvidersListActions,
    selectedId: String?,
    gutter: Dp,
) {
    for (section in ProviderSection.entries) {
        val rows = state.rows.of(section)
        if (rows.isEmpty()) continue
        val expanded = section !in state.collapsed
        item(key = "header-${section.name}") {
            ProviderSectionHeader(
                section = section,
                count = rows.size,
                expanded = expanded,
                onToggle = { actions.onToggleSection(section) },
                horizontalPadding = gutter,
            )
        }
        if (section == ProviderSection.Popular && state.showPopularDescription) {
            item(key = "popular-description") {
                Text(
                    stringResource(R.string.providers_popular_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.xs),
                )
            }
        }
        items(rows, key = { "${section.name}-${it.id}" }) { row ->
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard)) + fadeIn(tween(ClineMotion.Short3, delayMillis = 50)),
                exit = shrinkVertically(tween(ClineMotion.Short4, easing = ClineMotion.Standard)) + fadeOut(tween(ClineMotion.Short2)),
            ) {
                ProviderRow(row, selected = row.id == selectedId, onClick = { actions.onOpen(row.id) }, gutter = gutter)
            }
        }
    }
}

/** S4.1 search field: leading `search`, trailing clear, CD "Search model providers". */
@Composable
fun ProviderSearchField(query: String, enabled: Boolean, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    SearchField(
        value = query,
        onValueChange = onQuery,
        placeholder = stringResource(R.string.providers_search_placeholder),
        clearContentDescription = stringResource(R.string.providers_search_clear),
        contentDescription = stringResource(R.string.providers_search_cd),
        enabled = enabled,
        modifier = modifier,
    )
}

/**
 * One S4 row: name, then "Configured" or the auth hint, then the decorative chevron. When the
 * text is large for the row's width ([providerRowStacked]: system scale × in-app size), the
 * status moves under the name and only the chevron stays trailing, so the headline gets the
 * full width and wraps at word boundaries instead of splitting "Usage-Billing" mid-word
 * (DS §8.1.8).
 */
@Composable
fun ProviderRow(row: ProviderRowUi, selected: Boolean, onClick: () -> Unit, gutter: Dp = Spacing.l) {
    val status = if (row.connected) stringResource(R.string.providers_row_configured) else authHintLabel(row.authKind)
    val description = stringResource(R.string.providers_row_cd, row.name, status)
    val textScale = LocalDensity.current.fontScale *
        (MaterialTheme.typography.bodyLarge.fontSize.value / DEFAULT_BODY_LARGE_SP)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = providerRowStacked(maxWidth.value, textScale)
        ListItem(
            // Wraps freely: a line limit cut "Cline Usage-Billing" to "Cline Usa…" beside the
            // trailing status (SP §12: no clipped text).
            headlineContent = { Text(row.name) },
            supportingContent = if (stacked) {
                { ProviderRowStatus(row, status) }
            } else {
                null
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!stacked) {
                        ProviderRowStatus(row, status)
                        Spacer(Modifier.width(Spacing.xs))
                    }
                    Icon(ClineIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            colors = ListItemDefaults.colors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                headlineColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier
                .focusRing(RectangleShape, inset = true)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description }
                .padding(horizontal = (gutter - Spacing.l).coerceAtLeast(0.dp)),
        )
    }
}

/** The M3 baseline `bodyLarge` size; the theme's size over this is the in-app font size multiplier. */
private const val DEFAULT_BODY_LARGE_SP = 16f

/** The row's status: the saving spinner, the auth hint icon when not configured, and the label. */
@Composable
private fun ProviderRowStatus(row: ProviderRowUi, status: String) {
    val savingState = stringResource(R.string.providers_state_saving)
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (row.saving) {
            CircularProgressIndicator(
                Modifier.size(16.dp).semantics { stateDescription = savingState },
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(Spacing.s))
        }
        if (!row.connected) {
            AuthHintIcon(row.authKind)
            Spacer(Modifier.width(Spacing.xs))
        }
        Text(status, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenteredStatus(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.huge, horizontal = Spacing.l)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun LoadError(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = Spacing.xxxl, horizontal = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text(
            stringResource(R.string.providers_load_failed, message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.components_retry)) }
    }
}

@Composable
private fun NoProviderMatch(query: String, gutter: Dp) {
    Column(Modifier.fillMaxWidth().padding(vertical = Spacing.s)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(R.string.providers_no_match, query.trim()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = gutter, vertical = Spacing.l),
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Expanded: the top-bar "Add provider" (no FAB in a pane). */
@Composable
fun AddProviderTonalButton(onClick: () -> Unit, enabled: Boolean) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(end = Spacing.s)) {
        Icon(ClineIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(R.string.providers_add))
    }
}

