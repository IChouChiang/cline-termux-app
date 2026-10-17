package dev.cline.termux.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.ShellKernelUi
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.marketplace.MATURITY_ORDER
import dev.cline.termux.data.marketplace.MarketplaceType
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.OrderedBackHandler
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing

/** What the list (C3) does when tapped; the route binds each to the ViewModel and the navigator. */
@Immutable
class MarketplaceListActions(
    val onBack: () -> Unit,
    val onOpenInstalled: () -> Unit,
    val onOpenEntry: (key: String) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onAllTypes: () -> Unit,
    val onToggleType: (MarketplaceType) -> Unit,
    val onSelectTag: (String?) -> Unit,
    val onRefresh: () -> Unit,
    val onRetry: () -> Unit,
    val onRetryHelper: () -> Unit,
    val onStartKernel: () -> Unit,
) {
    companion object {
        /** No-op actions for previews. */
        val Preview = MarketplaceListActions({}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
    }
}

/**
 * C3 Marketplace list (customize-and-rest.md §3.3, §3.4): the page (title, description,
 * "Installed" action), search, type chips with counts, the category chip and sheet, the
 * sections in maturity order, and every list state. [paneMode] is the expanded list pane
 * (the selected row is highlighted and the content is not width-clamped); otherwise the
 * content is centred at [maxContentWidth] (720dp on medium, CR §0.6).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    state: MarketplaceUiState,
    actions: MarketplaceListActions,
    modifier: Modifier = Modifier,
    paneMode: Boolean = false,
    gutter: Dp = Spacing.gutterCompact,
    maxContentWidth: Dp = Dp.Unspecified,
    listState: LazyListState = rememberLazyListState(),
) {
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val categoryFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Back order (CR §3.7): sheet (its own window) → search focus → selection → pop.
    OrderedBackHandler(BackLevel.SearchFocus, enabled = searchFocused) { focusManager.clearFocus() }

    PageScaffold(
        title = stringResource(R.string.marketplace_title),
        modifier = modifier,
        navigationIcon = { BackNavIcon(actions.onBack) },
        actions = {
            TooltipIconButton(
                icon = ClineIcons.Extension,
                contentDescription = stringResource(R.string.marketplace_installed_action),
                onClick = actions.onOpenInstalled,
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = actions.onRefresh,
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().widthIn(max = maxContentWidth),
                    contentPadding = PaddingValues(bottom = Spacing.xxl),
                ) {
                    marketplaceListContent(
                        state = state,
                        actions = actions,
                        paneMode = paneMode,
                        gutter = gutter,
                        onSearchFocus = { searchFocused = it },
                        onClearFocus = { focusManager.clearFocus() },
                        onOpenCategories = { sheetOpen = true },
                        categoryFocus = categoryFocus,
                    )
                }
            }
        }
    }

    if (sheetOpen) {
        CategorySheet(
            tags = state.visibleTags,
            selected = state.tag,
            onSelect = { tag ->
                actions.onSelectTag(tag)
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
            returnFocus = categoryFocus,
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.marketplaceListContent(
    state: MarketplaceUiState,
    actions: MarketplaceListActions,
    paneMode: Boolean,
    gutter: Dp,
    onSearchFocus: (Boolean) -> Unit,
    onClearFocus: () -> Unit,
    onOpenCategories: () -> Unit,
    categoryFocus: FocusRequester,
) {
    val padded = Modifier.padding(horizontal = gutter)
    if (state.helper is HelperState.Starting || state.helper is HelperState.Unavailable) {
        item(key = "helper", contentType = "helper") {
            MarketplaceHelperSurface(
                helper = state.helper,
                kernel = state.kernel,
                onRetry = actions.onRetryHelper,
                onStartKernel = actions.onStartKernel,
                modifier = padded.padding(top = Spacing.s),
            )
        }
    }
    item(key = "kernel", contentType = "kernel") {
        MarketplaceKernelSurface(state.kernel, modifier = padded.padding(top = Spacing.s))
    }
    item(key = "description", contentType = "description") {
        Text(
            stringResource(R.string.marketplace_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = padded.padding(top = Spacing.s, bottom = Spacing.m),
        )
    }
    if (state.showFilters) {
        item(key = "search", contentType = "search") {
            MarketplaceSearchField(state.query, actions.onQueryChange, onSearchFocus, onClearFocus, padded)
        }
        item(key = "chips", contentType = "chips") {
            FilterChips(state, actions, onOpenCategories, categoryFocus, gutter)
        }
    }
    if (state.stale) {
        item(key = "stale", contentType = "stale") { StaleNotice(padded.padding(bottom = Spacing.s)) }
    }
    when (val catalog = state.catalogState) {
        MarketplaceCatalogState.Loading -> item(key = "loading", contentType = "loading") { LoadingRow() }
        is MarketplaceCatalogState.Error -> item(key = "error", contentType = "error") {
            InlineErrorCard(
                message = catalog.text(),
                actionLabel = stringResource(R.string.components_retry),
                onAction = actions.onRetry,
                modifier = padded.padding(top = Spacing.s),
            )
        }
        is MarketplaceCatalogState.Loaded -> {
            state.sections.forEach { section ->
                item(key = "header:${section.type.wire}", contentType = "header") {
                    MarketplaceSectionHeader(section.type, Modifier.padding(top = Spacing.s))
                }
                items(section.rows, key = { it.key }, contentType = { "row" }) { row ->
                    MarketplaceListRow(
                        row = row,
                        showTypePill = state.showTypePills,
                        selected = paneMode && row.key == state.selectedKey,
                        onClick = {
                            onClearFocus()
                            actions.onOpenEntry(row.key)
                        },
                    )
                }
            }
            if (state.filteredEmpty) {
                item(key = "empty", contentType = "empty") {
                    Text(
                        stringResource(R.string.marketplace_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = padded.fillMaxWidth().padding(vertical = Spacing.xxl),
                    )
                }
            }
        }
    }
}

@Composable
private fun MarketplaceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onFocus: (Boolean) -> Unit,
    onClearFocus: () -> Unit,
    modifier: Modifier,
) {
    val label = stringResource(R.string.marketplace_search)
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = label,
        clearContentDescription = stringResource(R.string.marketplace_clear_search),
        contentDescription = label,
        onImeAction = onClearFocus,
        modifier = modifier.onFocusChanged { onFocus(it.isFocused) },
    )
}

/** "All", the three type chips with their catalog counts, then the category chip (CR §3.3). */
@Composable
private fun FilterChips(
    state: MarketplaceUiState,
    actions: MarketplaceListActions,
    onOpenCategories: () -> Unit,
    categoryFocus: FocusRequester,
    gutter: Dp,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.s),
        contentPadding = PaddingValues(horizontal = gutter),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "all") {
            FilterChip(
                selected = state.type == null,
                onClick = actions.onAllTypes,
                label = { Text(stringResource(R.string.marketplace_filter_all)) },
            )
        }
        items(MATURITY_ORDER, key = { it.wire }) { type ->
            TypeFilterChip(type, selected = state.type == type, count = state.typeCount(type)) { actions.onToggleType(type) }
        }
        if (state.showCategoryChip) {
            item(key = "category") {
                val all = stringResource(R.string.marketplace_all_categories)
                val label = state.selectedTagLabel ?: all
                val description = stringResource(R.string.marketplace_filter_by_category)
                AssistChip(
                    onClick = onOpenCategories,
                    label = { Text(label, maxLines = 1) },
                    trailingIcon = { Icon(ClineIcons.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.focusRequester(categoryFocus).semantics {
                        contentDescription = description
                        stateDescription = label
                    },
                )
            }
        }
    }
}

@Composable
private fun TypeFilterChip(type: MarketplaceType, selected: Boolean, count: Int, onClick: () -> Unit) {
    val plural = stringResource(type.copy().plural)
    val description = stringResource(R.string.marketplace_type_chip_description, plural, count)
    val tint = type.tint()
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = description },
        leadingIcon = {
            Icon(
                type.glyph,
                contentDescription = null,
                tint = if (selected) androidx.compose.material3.LocalContentColor.current else tint.content,
                modifier = Modifier.size(18.dp),
            )
        },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(plural)
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) androidx.compose.material3.LocalContentColor.current else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

@Composable
private fun StaleNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(ClineIcons.CloudOff, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(R.string.marketplace_stale_notice), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.huge)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(Spacing.s))
        Text(
            stringResource(R.string.marketplace_loading),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---- previews --------------------------------------------------------------------------------

@Composable
private fun ListPreview(state: MarketplaceUiState) {
    ClinePreviewTheme { MarketplaceScreen(MarketplaceUiState.derive(state), MarketplaceListActions.Preview) }
}

@LightDarkPreviews
@Composable
private fun MarketplaceLoadingPreview() = ListPreview(MarketplaceUiState(catalogState = MarketplaceCatalogState.Loading))

@LightDarkPreviews
@Composable
private fun MarketplacePopulatedPreview() = ListPreview(MarketplacePreviewData.loaded())

@LightDarkPreviews
@Composable
private fun MarketplaceSearchPreview() = ListPreview(MarketplacePreviewData.loaded().copy(query = "git"))

@LightDarkPreviews
@Composable
private fun MarketplaceFilteredEmptyPreview() =
    ListPreview(MarketplacePreviewData.loaded().copy(type = MarketplaceType.Plugin, tag = "git"))

@LightDarkPreviews
@Composable
private fun MarketplaceStalePreview() = ListPreview(MarketplacePreviewData.loaded(stale = true))

@LightDarkPreviews
@Composable
private fun MarketplaceHttpErrorPreview() = ListPreview(MarketplaceUiState(catalogState = MarketplaceCatalogState.Error(503)))

@LightDarkPreviews
@Composable
private fun MarketplaceOfflinePreview() = ListPreview(MarketplaceUiState(catalogState = MarketplaceCatalogState.Error(null)))

@LightDarkPreviews
@Composable
private fun MarketplaceHelperStartingPreview() =
    ListPreview(MarketplacePreviewData.loaded().copy(helper = HelperState.Starting(0), installedReady = false))

@LightDarkPreviews
@Composable
private fun MarketplaceHelperUnavailablePreview() =
    ListPreview(MarketplacePreviewData.loaded().copy(helper = HelperState.Unavailable(HelperFailure.SocketFailed, null), installedApproximate = true))

@LightDarkPreviews
@Composable
private fun MarketplaceKernelDownPreview() {
    val gate = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
    ClinePreviewTheme {
        CompositionLocalProvider(LocalShellKernelUi provides ShellKernelUi(gate, 25463, null) {}) {
            MarketplaceScreen(MarketplaceUiState.derive(MarketplacePreviewData.loaded().copy(kernel = gate)), MarketplaceListActions.Preview)
        }
    }
}

@LightDarkPreviews
@Composable
private fun MarketplaceKernelBlockedPreview() =
    ListPreview(MarketplacePreviewData.loaded().copy(kernel = KernelGate.Blocked(CheckId.Permission)))
