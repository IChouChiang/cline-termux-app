package dev.cline.termux.ui.sessions

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.appGraph
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.ShellDrawerScope
import dev.cline.termux.ui.ShellKernelBanner
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The `search` route (sessions.md S3, compact and medium): DS §5.3's `SearchBar` +
 * `ExpandedFullScreenSearchBar` (M3 1.4). The route opens with the bar already expanded full
 * screen over everything, the field focused with the IME up. The back arrow, Escape and the
 * predictive back gesture collapse it into the bar at the top, and the route pops once the bar
 * has settled collapsed. Selecting a hit opens the session (F1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSearchScreen(navigator: ShellNavigator, graph: AppGraph = LocalContext.current.appGraph) {
    val vm: SessionSearchViewModel = viewModel(factory = SessionSearchViewModel.factory(graph))
    val state by vm.uiState.collectAsStateWithLifecycle()
    SessionsEventsEffect(vm.events, navigator, activeSessionId = null)
    val searchState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val textState = rememberTextFieldState(initialText = state.query)
    val scope = rememberCoroutineScope()
    val paneTitle = stringResource(R.string.search_pane_title)
    val collapse: () -> Unit = { scope.launch { searchState.animateToCollapsed() } }
    LaunchedEffect(textState, vm) {
        snapshotFlow { textState.text.toString() }.collect { query -> if (query != vm.uiState.value.query) vm.setQuery(query) }
    }
    LaunchedEffect(searchState) {
        snapshotFlow { searchState.currentValue == SearchBarValue.Collapsed && !searchState.isAnimating }.first { it }
        navigator.back()
    }
    ReportBackLevel(BackLevel.SearchFocus, true)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(SearchBarDefaults.windowInsets)
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            contentAlignment = Alignment.TopCenter,
        ) {
            SearchBar(
                state = searchState,
                inputField = { FullScreenSearchInput(textState, searchState, vm, onBack = collapse, autoFocus = false) },
            )
        }
    }
    ExpandedFullScreenSearchBar(
        state = searchState,
        inputField = { FullScreenSearchInput(textState, searchState, vm, onBack = collapse, autoFocus = true) },
        modifier = Modifier.semantics { this.paneTitle = paneTitle },
    ) {
        SearchResults(state, vm, onOpen = vm::open)
    }
}

/**
 * The full-screen route's field, bound to [textState] (the view model follows it) and to
 * [searchState]: focusing it expands the bar; the leading icon is the back arrow while expanded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FullScreenSearchInput(
    textState: TextFieldState,
    searchState: SearchBarState,
    vm: SessionSearchViewModel,
    onBack: () -> Unit,
    autoFocus: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    if (autoFocus) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val expanded = searchState.currentValue == SearchBarValue.Expanded
    SearchBarDefaults.InputField(
        textFieldState = textState,
        searchBarState = searchState,
        onSearch = { query ->
            if (query != vm.uiState.value.query) vm.setQuery(query)
            vm.submit()
            keyboard?.hide()
        },
        placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (expanded) {
            {
                IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(ClineIcons.ArrowBack, contentDescription = stringResource(R.string.components_back))
                }
            }
        } else {
            { Icon(ClineIcons.Search, contentDescription = null) }
        },
        trailingIcon = if (textState.text.isNotEmpty()) {
            {
                IconButton(onClick = { textState.clearText() }, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(ClineIcons.Close, contentDescription = stringResource(R.string.search_cd_clear))
                }
            }
        } else {
            null
        },
        modifier = Modifier
            .then(if (autoFocus) Modifier.focusRequester(focus) else Modifier)
            .searchKeys(vm, onEscape = onBack),
    )
}

/**
 * `ShellSlots.dockedSearch` (sessions.md §4.3 expanded): a docked search bar at the top of the
 * list pane; focusing it drops results over the list (up to 70 % of the window height);
 * Ctrl+K / Ctrl+P focus it through [ShellDrawerScope.dockedSearchRequests].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellDrawerScope.SessionSearchDocked() {
    val graph = LocalContext.current.appGraph
    val vm: SessionSearchViewModel = viewModel(key = "sessions.search.docked", factory = SessionSearchViewModel.factory(graph))
    val state by vm.uiState.collectAsStateWithLifecycle()
    SessionsEventsEffect(vm.events, navigator, activeSessionId)
    var expanded by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(dockedSearchRequests) {
        dockedSearchRequests.collect {
            expanded = true
            runCatching { focus.requestFocus() }
        }
    }
    val collapse = {
        expanded = false
        vm.reset()
    }
    ReportBackLevel(BackLevel.SearchFocus, expanded)
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val paneTitle = stringResource(R.string.search_pane_title)
    DockedSearchBar(
        inputField = {
            SearchInput(
                query = state.query,
                vm = vm,
                expanded = expanded,
                onExpandedChange = { if (it) expanded = true else collapse() },
                onBack = collapse,
                modifier = Modifier.focusRequester(focus),
            )
        },
        expanded = expanded,
        onExpandedChange = { if (it) expanded = true else collapse() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.m, vertical = Spacing.xs)
            .semantics { if (expanded) this.paneTitle = paneTitle },
    ) {
        Box(Modifier.heightIn(max = windowHeight * 0.7f)) {
            SearchResults(state, vm, onOpen = { hit ->
                collapse()
                vm.open(hit)
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchInput(
    query: String,
    vm: SessionSearchViewModel,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    SearchBarDefaults.InputField(
        query = query,
        onQueryChange = vm::setQuery,
        onSearch = {
            vm.submit()
            keyboard?.hide()
        },
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        placeholder = { Text(stringResource(R.string.search_placeholder), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (expanded) {
            {
                IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(ClineIcons.ArrowBack, contentDescription = stringResource(R.string.components_back))
                }
            }
        } else {
            { Icon(ClineIcons.Search, contentDescription = null) }
        },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { vm.setQuery("") }, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(ClineIcons.Close, contentDescription = stringResource(R.string.search_cd_clear))
                }
            }
        } else {
            null
        },
        modifier = modifier.searchKeys(vm, onEscape = onBack),
    )
}

/** Hardware keys in a search field (§4.5): Up/Down move the highlight, Enter opens it, Escape leaves. */
private fun Modifier.searchKeys(vm: SessionSearchViewModel, onEscape: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    when (event.key) {
        Key.DirectionDown -> {
            vm.moveHighlight(1)
            true
        }
        Key.DirectionUp -> {
            vm.moveHighlight(-1)
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            val hit = vm.highlightedHit()
            if (hit != null && vm.uiState.value.highlighted >= 0) {
                vm.open(hit)
                true
            } else {
                false
            }
        }
        Key.Escape -> {
            onEscape()
            true
        }
        else -> false
    }
}

/** The S3 body for every phase (§4.5), with polite announcements of phase changes. */
@Composable
fun SearchResults(state: SessionSearchUiState, vm: SessionSearchViewModel?, onOpen: (SearchHit) -> Unit) {
    val hardwareKeyboard = LocalConfiguration.current.keyboard != Configuration.KEYBOARD_NOKEYS
    val announcement = when (state.phase) {
        SearchPhase.Searching -> stringResource(R.string.search_searching)
        SearchPhase.Results -> copyPluralStringResource(R.plurals.search_results_count, state.hits.size, state.hits.size)
        SearchPhase.NoResults -> stringResource(R.string.search_no_results)
        SearchPhase.Failed -> stringResource(R.string.search_failed)
        else -> null
    }
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.weight(1f, fill = false)) {
            when (state.phase) {
                SearchPhase.Idle -> CenteredText(stringResource(R.string.search_idle_hint))
                SearchPhase.KernelDown -> Column(Modifier.padding(Spacing.l)) {
                    if (LocalShellKernelUi.current != null) ShellKernelBanner(hasComposer = false) else CenteredText(stringResource(R.string.sessions_hub_not_connected))
                }
                SearchPhase.Searching -> Row(
                    Modifier.fillMaxWidth().padding(vertical = Spacing.xxxl),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.s))
                    Text(stringResource(R.string.search_searching), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SearchPhase.NoResults -> Column(Modifier.fillMaxWidth().padding(Spacing.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.search_no_results), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    Spacer(Modifier.size(Spacing.xs))
                    Text(stringResource(R.string.search_live_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                SearchPhase.Failed -> Column(Modifier.fillMaxWidth().padding(Spacing.xxxl), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.search_failed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    TextButton(onClick = { vm?.retry() }) { Text(stringResource(R.string.search_retry)) }
                }
                SearchPhase.Results -> HitList(state, vm, onOpen)
            }
        }
        if (hardwareKeyboard) KeyboardFooter()
    }
    LiveRegionAnnouncer(announcement)
}

@Composable
private fun HitList(state: SessionSearchUiState, vm: SessionSearchViewModel?, onOpen: (SearchHit) -> Unit) {
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { if (it) keyboard?.hide() }
    }
    // §4.4: reveal 15 more when the end is near (within about two 88dp rows ≈ 120dp + a row).
    LaunchedEffect(listState, vm) {
        snapshotFlow { listState.layoutInfo.let { info -> (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 2 } }
            .collect { nearEnd -> if (nearEnd) vm?.revealMore() }
    }
    LaunchedEffect(state.highlighted) { if (state.highlighted >= 0) listState.animateScrollToItem(state.highlighted + 1) }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
        item(key = "heading") {
            Text(
                stringResource(R.string.search_group),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.s).semantics { heading() },
            )
        }
        itemsIndexed(state.visibleHits, key = { _, hit -> hit.documentId }) { index, hit ->
            SearchHitRow(hit, highlighted = index == state.highlighted, onClick = { onOpen(hit) })
        }
        if (state.hasMore) {
            item(key = "showing") {
                Text(
                    copyPluralStringResource(R.plurals.search_showing, state.hits.size, state.visibleHits.size, state.hits.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(Spacing.m),
                )
            }
        }
    }
}

/** A hit (§4.3): role + title, a two-line snippet with the matches in SemiBold, the workspace root. */
@Composable
fun SearchHitRow(hit: SearchHit, highlighted: Boolean, onClick: () -> Unit) {
    val spans = remember(hit.snippet) { SessionSearchModel.snippetSpans(hit.snippet) }
    val emphasis = SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    val snippet: AnnotatedString = remember(spans, emphasis) {
        buildAnnotatedString {
            append(spans.text)
            spans.bold.forEach { addStyle(emphasis, it.first, it.last + 1) }
        }
    }
    val description = stringResource(R.string.search_hit_a11y, hit.role, hit.title, spans.text, hit.workspaceRoot)
    val openLabel = stringResource(R.string.sessions_click_open)
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .then(if (highlighted) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier)
            .focusRing(RectangleShape, inset = true)
            .clickable(onClickLabel = openLabel, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hit.role, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(Spacing.s))
            Text(hit.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (snippet.isNotEmpty()) {
            Text(snippet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (hit.workspaceRoot.isNotEmpty()) {
            LtrText(
                hit.workspaceRoot,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.l, vertical = Spacing.xxxl),
    )
}

/** Hardware keyboard footer (§4.5): the navigation hint and the "Ctrl+P" key chip. */
@Composable
private fun KeyboardFooter() {
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = Spacing.l), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.search_keyboard_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
            Text(
                stringResource(R.string.search_keyboard_shortcut),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = Spacing.s, vertical = Spacing.xxs),
            )
        }
    }
}

// ---------------------------------------------------------------------------- previews

private val previewHits = listOf(
    SearchHit("s1", "d1", "User", "Fix login redirect", "...the [redirect] loop happens when the callback URL lacks the...", "/data/data/com.termux/files/home/projects/app"),
    SearchHit("s2", "d2", "Assistant", "Port OpenTUI 0.4.4", "Rebuilt the [redirect] handler for the Bionic package.", "/data/data/com.termux/files/home/cline-termux"),
)

@LightDarkPreviews
@Composable
private fun SearchResultsPreview() {
    ComponentPreview(padding = false) {
        SearchResults(SessionSearchUiState(query = "redirect", phase = SearchPhase.Results, hits = previewHits + previewHits.map { it.copy(documentId = it.documentId + "b") }, visibleCount = 2, highlighted = 0), null) {}
    }
}

@LightDarkPreviews
@Composable
private fun SearchIdlePreview() {
    ComponentPreview(padding = false) { SearchResults(SessionSearchUiState(), null) {} }
}

@LightDarkPreviews
@Composable
private fun SearchSearchingPreview() {
    ComponentPreview(padding = false) { SearchResults(SessionSearchUiState(query = "redir", phase = SearchPhase.Searching), null) {} }
}

@LightDarkPreviews
@Composable
private fun SearchNoResultsPreview() {
    ComponentPreview(padding = false) { SearchResults(SessionSearchUiState(query = "zzz", phase = SearchPhase.NoResults), null) {} }
}

@LightDarkPreviews
@Composable
private fun SearchFailedPreview() {
    ComponentPreview(padding = false) { SearchResults(SessionSearchUiState(query = "zzz", phase = SearchPhase.Failed), null) {} }
}

@LightDarkPreviews
@Composable
private fun SearchKernelDownPreview() {
    ComponentPreview(padding = false) { SearchResults(SessionSearchUiState(query = "zzz", phase = SearchPhase.KernelDown), null) {} }
}
