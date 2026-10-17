package dev.cline.termux.ui.model

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.data.model.CURRENT_MODEL_SECTION_ID
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.EmptyState
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch

/**
 * SP S8 host: shows [ModelPickerSheet] while [open] and always collects the picker's effects
 * (results for the composer, close, switch errors). Hoist [open] with `rememberSaveable` in the
 * screen that owns the composer chip (SP §0.2: sheets are not routes); the ViewModel is
 * `viewModel(factory = ModelPickerViewModel.factory(graph))` in that screen, with
 * `setHomeHost()` / `setSessionHost(id)` called as the screen's session changes.
 *
 * [onSetUpProvider] navigates to `settings/providers` after the sheet closed; [returnFocus] is
 * the ModelChip's requester (DS §8.1.7).
 */
@Composable
fun ModelPickerSheetHost(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    viewModel: ModelPickerViewModel,
    onResult: (ModelPickerResult) -> Unit,
    onSetUpProvider: () -> Unit,
    returnFocus: FocusRequester? = null,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetSnackbar = remember { SnackbarHostState() }
    val appSnackbar = LocalSnackbarHost.current
    val currentOpen by rememberUpdatedState(open)
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnOpenChange by rememberUpdatedState(onOpenChange)
    val switchFailed = stringResource(R.string.model_picker_switch_failed)
    val reasoningFailed = stringResource(R.string.model_picker_reasoning_failed)
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ModelPickerEffect.Close -> currentOnOpenChange(false)
                is ModelPickerEffect.Result -> currentOnResult(effect.result)
                is ModelPickerEffect.SwitchFailed -> launch {
                    val host = if (currentOpen) sheetSnackbar else appSnackbar
                    host.showReplacing(switchFailed.format(effect.message))
                }
                is ModelPickerEffect.ReasoningFailed -> launch { appSnackbar.showReplacing(reasoningFailed.format(effect.message)) }
            }
        }
    }
    if (open) {
        LaunchedEffect(Unit) { viewModel.onOpened() }
        ModelPickerSheet(
            state = state,
            onDismissRequest = { onOpenChange(false) },
            onQueryChange = viewModel::setQuery,
            onProviderQueryChange = viewModel::setProviderQuery,
            onPickProvider = viewModel::pickProvider,
            onPickModel = viewModel::pickModel,
            onRefresh = viewModel::refreshModels,
            onSetUpProvider = {
                onOpenChange(false)
                onSetUpProvider()
            },
            snackbarHostState = sheetSnackbar,
            returnFocus = returnFocus,
        )
    }
}

/** The S8.1 sheet: compact (provider chips + model list) below 600dp, two columns from 600dp. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerSheet(
    state: ModelPickerUiState,
    onDismissRequest: () -> Unit,
    onQueryChange: (String) -> Unit,
    onProviderQueryChange: (String) -> Unit,
    onPickProvider: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onRefresh: () -> Unit,
    onSetUpProvider: () -> Unit,
    snackbarHostState: SnackbarHostState? = null,
    returnFocus: FocusRequester? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() } }
    val twoColumn = LocalShellLayout.current.widthDp >= TWO_COLUMN_MIN_WIDTH
    val maxHeight = (LocalConfiguration.current.screenHeightDp * MAX_HEIGHT_FRACTION).dp
    PickerSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        // The 90 % cap covers the whole sheet (handle and title included), not only the lists.
        maxHeight = maxHeight,
        title = stringResource(R.string.model_picker_title),
        snackbarHostState = snackbarHostState,
        returnFocus = returnFocus,
        titleActions = {
            TooltipIconButton(
                icon = ClineIcons.Close,
                contentDescription = stringResource(R.string.model_picker_close),
                onClick = close,
            )
        },
    ) {
        ModelPickerContent(
            state = state,
            twoColumn = twoColumn,
            onQueryChange = onQueryChange,
            onProviderQueryChange = onProviderQueryChange,
            onPickProvider = onPickProvider,
            onPickModel = onPickModel,
            onRefresh = onRefresh,
            onSetUpProvider = onSetUpProvider,
            modifier = Modifier.heightIn(max = maxHeight),
        )
    }
}

/** Everything under the sheet title; stateless so previews render each S8.6 state. */
@Composable
fun ModelPickerContent(
    state: ModelPickerUiState,
    twoColumn: Boolean,
    onQueryChange: (String) -> Unit,
    onProviderQueryChange: (String) -> Unit,
    onPickProvider: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onRefresh: () -> Unit,
    onSetUpProvider: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        if (state.busy) {
            Text(
                stringResource(R.string.model_picker_busy),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.sheetGutter, vertical = Spacing.xs),
            )
        }
        if (twoColumn) {
            TwoColumnBody(state, onQueryChange, onProviderQueryChange, onPickProvider, onPickModel, onRefresh, onSetUpProvider)
        } else {
            CompactBody(state, onQueryChange, onPickProvider, onPickModel, onRefresh, onSetUpProvider)
        }
    }
}

@Composable
private fun CompactBody(
    state: ModelPickerUiState,
    onQueryChange: (String) -> Unit,
    onPickProvider: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onRefresh: () -> Unit,
    onSetUpProvider: () -> Unit,
) {
    val providerRowState = rememberLazyListState()
    KeepSelectedProviderInView(providerRowState, state.selectedProviderIndex, state.providers)
    Column {
        GroupLabel(stringResource(R.string.model_picker_provider))
        LazyRow(
            state = providerRowState,
            contentPadding = PaddingValues(horizontal = Spacing.sheetGutter),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(state.providers, key = { it.id }) { provider ->
                ProviderChip(provider, state, onPickProvider)
            }
            item(key = "set-up") {
                AssistChip(
                    onClick = onSetUpProvider,
                    label = { Text(stringResource(R.string.model_picker_set_up_provider)) },
                    leadingIcon = { Icon(ClineIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
        }
        if (state.noProviders) {
            NoProvidersState(onSetUpProvider)
            return@Column
        }
        GroupLabel(stringResource(R.string.model_picker_model))
        ModelSearchField(state.query, onQueryChange, Modifier.padding(horizontal = Spacing.sheetGutter))
        LoadingBar(state.loading)
        ModelList(state, onPickModel, onRefresh, gutter = Spacing.sheetGutter)
    }
}

/**
 * Keeps the selected provider chip fully on screen (SP S8.2: the selected value always shows, as
 * the desktop combobox trigger does). The sheet opens on it without animation; a later switch or
 * catalog change animates, and a chip that is already fully visible does not move the row.
 */
@Composable
private fun KeepSelectedProviderInView(listState: LazyListState, selectedIndex: Int, providers: List<PickerProvider>) {
    var positioned by remember { mutableStateOf(false) }
    LaunchedEffect(selectedIndex, providers) {
        if (selectedIndex < 0) return@LaunchedEffect
        if (!isFullyVisible(listState, selectedIndex)) {
            if (positioned) listState.animateScrollToItem(selectedIndex) else listState.scrollToItem(selectedIndex)
        }
        positioned = true
    }
}

private fun isFullyVisible(listState: LazyListState, index: Int): Boolean {
    val info = listState.layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return false
    // The viewport offsets already include the content padding (start is -beforeContentPadding).
    return item.offset >= info.viewportStartOffset && item.offset + item.size <= info.viewportEndOffset
}

@Composable
private fun TwoColumnBody(
    state: ModelPickerUiState,
    onQueryChange: (String) -> Unit,
    onProviderQueryChange: (String) -> Unit,
    onPickProvider: (String) -> Unit,
    onPickModel: (String) -> Unit,
    onRefresh: () -> Unit,
    onSetUpProvider: () -> Unit,
) {
    if (state.noProviders) {
        Column {
            GroupLabel(stringResource(R.string.model_picker_provider))
            NoProvidersState(onSetUpProvider)
        }
        return
    }
    // The outer 8dp plus each column's 16dp content gutter puts labels, fields and ListItem text on the
    // 24dp sheet gutter (DS §3.3, §5.5); ListItem brings its own 16dp, so rows need no extra inset here.
    Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.sheetGutter - LIST_ITEM_PADDING)) {
        Column(Modifier.width(PROVIDER_COLUMN_WIDTH)) {
            GroupLabel(stringResource(R.string.model_picker_provider), gutter = LIST_ITEM_PADDING)
            if (state.showProviderSearch) {
                SearchField(
                    value = state.providerQuery,
                    onValueChange = onProviderQueryChange,
                    placeholder = stringResource(R.string.model_picker_search_providers),
                    clearContentDescription = stringResource(R.string.components_clear_search),
                    modifier = Modifier.padding(horizontal = LIST_ITEM_PADDING),
                )
                Spacer(Modifier.height(Spacing.s))
            }
            LazyColumn(contentPadding = PickerSheetDefaults.listBottomPadding, modifier = Modifier.selectableGroup()) {
                items(state.visibleProviders, key = { it.id }) { provider ->
                    ProviderRow(provider, state, onPickProvider)
                }
                item(key = "set-up") {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.model_picker_set_up_provider)) },
                        leadingContent = { Icon(ClineIcons.AddCircle, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .focusRing(RectangleShape, inset = true)
                            .selectable(selected = false, onClick = onSetUpProvider, role = Role.Button),
                    )
                }
            }
        }
        Column(Modifier.weight(1f)) {
            GroupLabel(stringResource(R.string.model_picker_model), gutter = LIST_ITEM_PADDING)
            ModelSearchField(state.query, onQueryChange, Modifier.padding(horizontal = LIST_ITEM_PADDING))
            LoadingBar(state.loading, gutter = LIST_ITEM_PADDING)
            ModelList(state, onPickModel, onRefresh, gutter = LIST_ITEM_PADDING)
        }
    }
}

@Composable
private fun ProviderChip(provider: PickerProvider, state: ModelPickerUiState, onPick: (String) -> Unit) {
    val pending = state.pendingProviderId == provider.id
    val switching = stringResource(R.string.model_picker_switching)
    FilterChip(
        selected = provider.selected,
        onClick = { onPick(provider.id) },
        enabled = state.interactive || provider.selected,
        label = { Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = when {
            pending -> {
                { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
            }
            provider.selected -> {
                { Icon(ClineIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            }
            else -> null
        },
        // M3 chips draw a 32dp container and already reserve a 48dp touch target (DS §3.4, §4.1 #6).
        modifier = Modifier.semantics {
            contentDescription = provider.name
            if (pending) stateDescription = switching
        },
    )
}

@Composable
private fun ProviderRow(provider: PickerProvider, state: ModelPickerUiState, onPick: (String) -> Unit) {
    val pending = state.pendingProviderId == provider.id
    val switching = stringResource(R.string.model_picker_switching)
    ListItem(
        headlineContent = { Text(provider.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = when {
            pending -> {
                { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
            }
            provider.selected -> {
                { Icon(ClineIcons.Check, contentDescription = null) }
            }
            else -> null
        },
        colors = ListItemDefaults.colors(
            containerColor = if (provider.selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            headlineColor = if (provider.selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(
                selected = provider.selected,
                enabled = state.interactive || provider.selected,
                role = Role.RadioButton,
                onClick = { onPick(provider.id) },
            )
            .semantics { if (pending) stateDescription = switching },
    )
}

@Composable
private fun ModelList(state: ModelPickerUiState, onPickModel: (String) -> Unit, onRefresh: () -> Unit, gutter: Dp) {
    // `gutter` is where text starts; ListItem already pads 16dp, so rows only add the remainder.
    val rowInset = (gutter - LIST_ITEM_PADDING).coerceAtLeast(0.dp)
    LazyColumn(contentPadding = PickerSheetDefaults.listBottomPadding, modifier = Modifier.fillMaxWidth().selectableGroup()) {
        sectionRows(state, onPickModel, gutter, rowInset)
        state.freeTextRow?.let { id ->
            item(key = "free-text") { FreeTextRow(id, enabled = state.interactive, inset = rowInset, onPick = { onPickModel(id) }) }
        }
        if (state.noModels) {
            item(key = "no-models") {
                Text(
                    stringResource(R.string.model_picker_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.m),
                )
            }
        }
        if (state.showFallbackNotice || state.canRefresh) {
            item(key = "refresh") { RefreshFooter(state, onRefresh, gutter) }
        }
    }
}

private fun LazyListScope.sectionRows(state: ModelPickerUiState, onPickModel: (String) -> Unit, gutter: Dp, rowInset: Dp) {
    state.sections.forEach { section ->
        if (section.id != null) {
            item(key = "header:${section.id}") { SectionHeaderRow(section, gutter) }
        }
        items(section.rows, key = { "row:${section.id}:${it.id}" }) { row ->
            ModelRow(
                row = row,
                sectionLabel = section.id,
                selected = row.id == state.selectedModelId,
                pending = row.id == state.pendingModelId,
                enabled = state.interactive,
                inset = rowInset,
                onClick = { onPickModel(row.id) },
            )
        }
    }
}

@Composable
private fun sectionLabel(id: String?): String? = when (id) {
    "recommended" -> stringResource(R.string.model_picker_section_recommended)
    "free" -> stringResource(R.string.model_picker_section_free)
    "all" -> stringResource(R.string.model_picker_section_all)
    "subscribed" -> stringResource(R.string.model_picker_section_subscribed)
    CURRENT_MODEL_SECTION_ID -> stringResource(R.string.model_picker_section_current)
    else -> null
}

@Composable
private fun SectionHeaderRow(section: PickerSectionUi, gutter: Dp) {
    val label = sectionLabel(section.id) ?: return
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter).padding(top = Spacing.m, bottom = Spacing.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        if (section.id == "free") {
            Text(
                stringResource(if (section.clinePass) R.string.model_picker_free_description_cline_pass else R.string.model_picker_free_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelRow(
    row: PickerRow,
    sectionLabel: String?,
    selected: Boolean,
    pending: Boolean,
    enabled: Boolean,
    inset: Dp,
    onClick: () -> Unit,
) {
    val section = sectionLabel(sectionLabel)
    val images = stringResource(R.string.model_picker_badge_images)
    val reasoning = stringResource(R.string.model_picker_badge_reasoning)
    val switching = stringResource(R.string.model_picker_switching)
    val resources = LocalResources.current
    val description = remember(row, section, images, reasoning, resources) {
        modelRowA11yLabel(row, section, images, reasoning) { acc, part -> resources.getString(R.string.model_picker_a11y_join, acc, part) }
    }
    ListItem(
        headlineContent = { Text(row.label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = when {
            row.supporting != null -> {
                { Text(row.supporting, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            row.showsIdLine -> {
                { LtrText(row.id, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, readAsWritten = false) }
            }
            else -> null
        },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                row.tierBadge?.let { TagBadge(it) }
                if (row.images) TagBadge(images)
                if (row.reasoning) TagBadge(reasoning)
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        pending -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        selected -> Icon(ClineIcons.Check, contentDescription = null)
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = description
                if (pending) stateDescription = switching
            }
            // Inside the selectable, so the ripple stays full width while text, badges and check sit on the gutters.
            .padding(horizontal = inset),
    )
}

@Composable
private fun FreeTextRow(id: String, enabled: Boolean, inset: Dp, onPick: () -> Unit) {
    val full = stringResource(R.string.model_picker_use_model_id, id)
    val mono = ClineMono.small
    val text = remember(full, id, mono) {
        buildAnnotatedString {
            append(full)
            val start = full.indexOf(id)
            if (start >= 0) addStyle(SpanStyle(fontFamily = mono.fontFamily, fontSize = mono.fontSize), start, start + id.length)
        }
    }
    ListItem(
        headlineContent = { Text(text) },
        leadingContent = { Icon(ClineIcons.Add, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = false, enabled = enabled, role = Role.Button, onClick = onPick)
            .padding(horizontal = inset),
    )
}

@Composable
private fun RefreshFooter(state: ModelPickerUiState, onRefresh: () -> Unit, gutter: Dp) {
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.s)) {
        if (state.showFallbackNotice) {
            Text(
                stringResource(R.string.model_picker_fallback_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.canRefresh) {
            TextButton(onClick = onRefresh, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(ClineIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(stringResource(R.string.model_picker_refresh))
            }
        }
    }
}

@Composable
private fun NoProvidersState(onSetUpProvider: () -> Unit) {
    EmptyState(
        title = stringResource(R.string.model_picker_no_providers),
        body = null,
        actionLabel = stringResource(R.string.model_picker_set_up_provider),
        onAction = onSetUpProvider,
    )
}

@Composable
private fun GroupLabel(text: String, gutter: Dp = Spacing.sheetGutter) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = gutter, end = gutter, top = Spacing.m, bottom = Spacing.s),
    )
}

@Composable
private fun LoadingBar(loading: Boolean, gutter: Dp = Spacing.sheetGutter) {
    val label = stringResource(R.string.model_picker_loading)
    Box(Modifier.fillMaxWidth().height(4.dp).padding(horizontal = gutter)) {
        if (loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = label })
        }
    }
    Spacer(Modifier.height(Spacing.xs))
}

@Composable
private fun ModelSearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    // The SearchCombobox input: `ImeAction.Search` hides the keyboard and keeps the query (S8.1).
    SearchField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = stringResource(R.string.model_picker_search_models),
        clearContentDescription = stringResource(R.string.components_clear_search),
        modifier = modifier,
    )
}

private val PROVIDER_COLUMN_WIDTH = 220.dp

/** M3 `ListItem`'s built-in horizontal content padding; rows add only what reaches the 24dp sheet gutter. */
private val LIST_ITEM_PADDING = 16.dp
private const val TWO_COLUMN_MIN_WIDTH = 600
private const val MAX_HEIGHT_FRACTION = 0.9f

// ------------------------------------------------------------------------------- previews

private val previewRows = listOf(
    PickerRow("anthropic/claude-sonnet-5", "Claude Sonnet 5", "Best for coding and agents", "NEW", images = true, reasoning = true, section = "recommended"),
    PickerRow("openai/gpt-5.5", "GPT-5.5", null, null, images = true, reasoning = true, section = "recommended"),
)

private val previewState = ModelPickerUiState(
    providers = listOf(
        PickerProvider("cline", "Cline", selected = true),
        PickerProvider("openrouter", "OpenRouter", selected = false),
        PickerProvider("anthropic", "Anthropic", selected = false),
    ),
    activeProviderId = "cline",
    sections = listOf(
        PickerSectionUi("recommended", clinePass = false, rows = previewRows),
        PickerSectionUi("free", clinePass = false, rows = listOf(PickerRow("moonshotai/kimi-k2.5:free", "Kimi K2.5 Free", null, null, images = false, reasoning = true, section = "free"))),
        PickerSectionUi("all", clinePass = false, rows = listOf(PickerRow("x-ai/grok-4", "Grok 4", null, null, images = true, reasoning = false, section = "all"))),
    ),
    selectedModelId = "anthropic/claude-sonnet-5",
    source = CatalogSource.Catalog,
    canRefresh = false,
)

@Composable
private fun PreviewContent(state: ModelPickerUiState, twoColumn: Boolean = false) {
    ComponentPreview(padding = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            ModelPickerContent(state, twoColumn, {}, {}, {}, {}, {}, {}, Modifier.heightIn(max = 720.dp))
        }
    }
}

@LightDarkPreviews
@Composable
private fun ModelPickerPopulatedPreview() = PreviewContent(previewState)

@LightDarkPreviews
@Composable
private fun ModelPickerLoadingPreview() = PreviewContent(previewState.copy(loading = true))

@LightDarkPreviews
@Composable
private fun ModelPickerFirstRunPreview() = PreviewContent(ModelPickerUiState(noProviders = true))

@LightDarkPreviews
@Composable
private fun ModelPickerNoModelsPreview() = PreviewContent(previewState.copy(sections = emptyList(), query = "zzz", noModels = true))

@LightDarkPreviews
@Composable
private fun ModelPickerFallbackPreview() = PreviewContent(
    ModelPickerUiState(
        providers = listOf(PickerProvider("anthropic", "Anthropic", selected = true)),
        activeProviderId = "anthropic",
        sections = listOf(PickerSectionUi(null, false, listOf(PickerRow("claude-sonnet-4-6", "claude-sonnet-4-6", null, null, images = false, reasoning = true, section = null)))),
        selectedModelId = "claude-sonnet-4-6",
        source = CatalogSource.Fallback,
        canRefresh = true,
    ),
)

@LightDarkPreviews
@Composable
private fun ModelPickerFreeTextPreview() = PreviewContent(
    previewState.copy(query = "my-custom-model", sections = emptyList(), freeTextRow = "my-custom-model"),
)

@LightDarkPreviews
@Composable
private fun ModelPickerBusyPreview() = PreviewContent(previewState.copy(busy = true, host = PickerHost.Session("s1", live = true)))

@LightDarkPreviews
@Composable
private fun ModelPickerSwitchingPreview() = PreviewContent(previewState.copy(pendingModelId = "openai/gpt-5.5", host = PickerHost.Session("s1", live = true)))

@LightDarkPreviews
@Composable
private fun ModelPickerTwoColumnPreview() = PreviewContent(previewState, twoColumn = true)

/** The selected provider sits past the row's right edge in catalog order; the row opens scrolled to it. */
@LightDarkPreviews
@Composable
private fun ModelPickerSelectedProviderOffscreenPreview() = PreviewContent(
    previewState.copy(
        providers = listOf(
            PickerProvider("cline", "Cline", selected = false),
            PickerProvider("openrouter", "OpenRouter", selected = false),
            PickerProvider("anthropic", "Anthropic", selected = false),
            PickerProvider("openai-compatible", "OpenAI Compatible", selected = true),
        ),
        activeProviderId = "openai-compatible",
        sections = listOf(
            PickerSectionUi(null, false, listOf(PickerRow("gpt-4o", "gpt-4o", null, null, images = true, reasoning = false, section = null))),
        ),
        selectedModelId = "gpt-4o",
    ),
)
