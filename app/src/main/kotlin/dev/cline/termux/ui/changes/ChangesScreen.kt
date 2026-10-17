package dev.cline.termux.ui.changes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.appGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.diff.SessionDiffHunk
import dev.cline.termux.data.diff.SessionDiffSummary
import dev.cline.termux.data.diff.SessionFileDiff
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.chat.LocalShellLightbox
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ContextMenuItem
import dev.cline.termux.ui.components.EmptyState
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberCopier
import dev.cline.termux.ui.components.rememberLiveRegionState
import dev.cline.termux.ui.diff.ChangedFileHeader
import dev.cline.termux.ui.diff.DiffHunkView
import dev.cline.termux.ui.diff.NoHunkDetails
import dev.cline.termux.ui.diff.splitBasename
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.kernel.quantityResources
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ShellLayout
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which A.4 layout a Changes instance uses. */
enum class ChangesLayout {
    /** Pushed, compact/medium: `MediumTopAppBar`, single-column accordion. */
    Accordion,

    /** Pushed, height < 480dp: small pinned bar, accordion, 24dp gutters. */
    HeightCompact,

    /** Pushed, ≥ 840 × ≥ 480: 320dp file list + the selected file's hunks. */
    TwoPane,

    /** The 400dp supporting pane beside the chat (DS-A1): accordion under a small pane header. */
    Pane;

    companion object {
        /** A.4 "Which layout applies": the height rule wins over the width rule. */
        fun forPushed(layout: ShellLayout): ChangesLayout = when {
            layout.heightCompact -> HeightCompact
            layout.widthDp >= TWO_PANE_MIN_WIDTH -> TwoPane
            else -> Accordion
        }

        const val TWO_PANE_MIN_WIDTH = 840
    }
}

/** Everything the Changes body calls back into (ChangesViewModel events). */
class ChangesActions(
    val onToggle: (String) -> Unit = {},
    val onExpandAll: () -> Unit = {},
    val onCollapseAll: () -> Unit = {},
    val onCopyPath: (String) -> Unit = {},
    val onShareFile: (String) -> Unit = {},
    val onShareAll: () -> Unit = {},
    val onShowMore: (HunkKey) -> Unit = {},
    val onSelect: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onKernelAction: (KernelAction) -> Unit = {},
)

/**
 * The pushed Changes route `chat/{sessionId}/changes` (CT A): layout from the window
 * (A.4), back pops; the route also pops when the summary drops to zero or the session is
 * deleted (A.3).
 */
@Composable
fun ChangesRoute(sessionId: String, graph: AppGraph, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel = changesViewModel(graph, sessionId, key = null)
    val layout = ChangesLayout.forPushed(LocalShellLayout.current)
    ChangesHost(viewModel, layout, onBack = onBack, onClose = onBack, onOpenFullScreen = null, modifier = modifier)
}

/**
 * The supporting-pane Changes (CT A.4 "≥ 1200dp × ≥ 480dp", DS-A1) for
 * `SupportingContent.Changes`: the compact accordion under a small pane header with
 * "Open full screen" and "Close diff view". [onClose] clears the shell's supporting request;
 * [onOpenFullScreen] pushes `chat/{sessionId}/changes` (two-pane, see
 * [openChangesFullScreen]). [activeSessionId] is the session the chat shows: when it differs
 * from [sessionId] the pane closes (A.3 "Session change").
 */
@Composable
fun ChangesPane(
    sessionId: String,
    onClose: () -> Unit,
    onOpenFullScreen: () -> Unit,
    modifier: Modifier = Modifier,
    activeSessionId: String? = null,
) {
    val currentClose by rememberUpdatedState(onClose)
    LaunchedEffect(activeSessionId, sessionId) {
        if (activeSessionId != null && activeSessionId != sessionId) currentClose()
    }
    val viewModel = changesViewModel(LocalContext.current.appGraph, sessionId, key = "changes-pane:$sessionId")
    val instance = rememberSaveable(sessionId) { UUID.randomUUID().toString() }
    LaunchedEffect(viewModel, instance) { viewModel.bindInstance(instance) }
    ChangesHost(viewModel, ChangesLayout.Pane, onBack = null, onClose = onClose, onOpenFullScreen = onOpenFullScreen, modifier = modifier)
}

@Composable
private fun changesViewModel(graph: AppGraph, sessionId: String, key: String?): ChangesViewModel {
    val noHunks = stringResource(R.string.changes_no_hunks)
    return viewModel(key = key, factory = ChangesViewModel.factory(graph, sessionId, noHunks))
}

@Composable
private fun ChangesHost(
    viewModel: ChangesViewModel,
    layout: ChangesLayout,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    onOpenFullScreen: (() -> Unit)?,
    modifier: Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val live = rememberLiveRegionState()
    val shellKernel = LocalShellKernelUi.current
    val copier = rememberCopier(legacyConfirmation = stringResource(R.string.changes_path_copied))
    val clipLabel = stringResource(R.string.changes_clip_label)
    val copyFailed = stringResource(R.string.changes_copy_failed)
    val chooser = stringResource(R.string.changes_share_chooser)
    val fallbackTitle = stringResource(R.string.changes_share_subject_fallback)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ChangesEffect.Close -> onClose()
                is ChangesEffect.Copy -> copier.copy(clipLabel, effect.text) { ok ->
                    if (ok) viewModel.onCopied(effect.path) else scope.launch { snackbar.showReplacing(copyFailed) }
                }
                is ChangesEffect.Share -> launchShare(
                    context,
                    ShareRequest(
                        text = effect.text,
                        subject = resources.getString(R.string.changes_share_subject, effect.sessionTitle ?: fallbackTitle),
                        chooserTitle = chooser,
                        fileName = effect.fileName,
                        fileMimeType = "text/x-diff",
                    ),
                )
                is ChangesEffect.Announce -> live.announce(quantityResources(resources).getQuantityString(R.plurals.changes_updated, effect.fileCount, effect.fileCount))
            }
        }
    }

    val actions = ChangesActions(
        onToggle = viewModel::onToggle,
        onExpandAll = viewModel::onExpandAll,
        onCollapseAll = viewModel::onCollapseAll,
        onCopyPath = viewModel::onCopyPath,
        onShareFile = viewModel::onShareFile,
        onShareAll = viewModel::onShareAll,
        onShowMore = viewModel::onShowMore,
        onSelect = viewModel::onSelect,
        onRetry = viewModel::onRetry,
        onKernelAction = { action -> shellKernel?.onAction?.invoke(action) ?: viewModel.onStartKernel() },
    )
    Box(modifier) {
        ChangesScreen(
            state = state,
            layout = layout,
            actions = actions,
            onBack = onBack,
            onClose = if (layout == ChangesLayout.Pane) onClose else null,
            onOpenFullScreen = onOpenFullScreen,
            kernelActionInFlight = shellKernel?.actionInFlight,
        )
        LiveRegionAnnouncer(live)
    }
}

/** The stateless Changes screen (all A.5 states, every A.4 layout). */
@Composable
fun ChangesScreen(
    state: ChangesUiState,
    layout: ChangesLayout,
    actions: ChangesActions,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
    onOpenFullScreen: (() -> Unit)?,
    modifier: Modifier = Modifier,
    kernelActionInFlight: KernelAction? = null,
) {
    val ready = state.content as? ChangesUiState.Content.Ready
    val bar = when (layout) {
        ChangesLayout.Accordion, ChangesLayout.TwoPane -> DetailBar.Medium
        ChangesLayout.HeightCompact -> DetailBar.SmallPinned
        ChangesLayout.Pane -> DetailBar.Pane
    }
    val gutter = when {
        layout == ChangesLayout.Pane -> Spacing.gutterCompact
        layout == ChangesLayout.HeightCompact -> Spacing.gutterWide
        LocalShellLayout.current.gutter > Spacing.gutterCompact -> Spacing.gutterWide
        else -> Spacing.gutterCompact
    }
    DetailScaffold(
        bar = bar,
        title = { ChangesTitle(fileCount = ready?.files?.size) },
        modifier = modifier.onPreviewKeyEvent { event ->
            // Two-pane: Up/Down move the selection, Ctrl+C copies the selected file's path (A.7).
            if (layout != ChangesLayout.TwoPane || ready == null || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            val files = ready.files
            val current = files.indexOfFirst { it.path == state.selectedFile?.path }
            when {
                event.key == Key.DirectionDown && current < files.lastIndex -> { actions.onSelect(files[current + 1].path); true }
                event.key == Key.DirectionUp && current > 0 -> { actions.onSelect(files[current - 1].path); true }
                event.isCtrlPressed && event.key == Key.C -> { state.selectedFile?.let { actions.onCopyPath(it.path) }; true }
                else -> false
            }
        },
        onBack = onBack,
        onClose = onClose,
        closeLabel = stringResource(R.string.changes_close_cd),
        loading = state.content == ChangesUiState.Content.Loading,
        // Beside the chat (supporting pane) the shell's lightbox can cover this page; it then
        // hosts the one snackbar itself (DS §4.1 #17).
        snackbarsVisible = LocalShellLightbox.current?.isOpen != true,
        actions = {
            if (state.hasFiles) {
                if (layout != ChangesLayout.TwoPane) {
                    if (state.anyExpanded) {
                        TooltipIconButton(ClineIcons.UnfoldLess, stringResource(R.string.changes_collapse_all), actions.onCollapseAll)
                    } else {
                        TooltipIconButton(ClineIcons.UnfoldMore, stringResource(R.string.changes_expand_all), actions.onExpandAll)
                    }
                }
                TooltipIconButton(ClineIcons.Share, stringResource(R.string.changes_share_all), actions.onShareAll)
            }
            if (onOpenFullScreen != null) {
                TooltipIconButton(ClineIcons.OpenInFull, stringResource(R.string.changes_open_full), onOpenFullScreen)
            }
        },
    ) { padding ->
        val strip: @Composable () -> Unit = {
            KernelStrip(
                kernel = state.kernel,
                onAction = actions.onKernelAction,
                actionInFlight = kernelActionInFlight,
                modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
            )
        }
        if (layout == ChangesLayout.TwoPane && ready != null && ready.files.isNotEmpty()) {
            TwoPaneChanges(state, ready, actions, padding, strip)
        } else {
            AccordionChanges(state, actions, padding, gutter, strip)
        }
    }
}

@Composable
private fun ChangesTitle(fileCount: Int?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.changes_title),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (fileCount != null) {
            Spacer(Modifier.width(Spacing.s))
            FilesBadge(fileCount)
        }
    }
}

/** DS "Files: N" badge: `extraSmall` on `surfaceContainerHighest`, `ClineMono.small`, `onSurfaceVariant`. */
@Composable
private fun FilesBadge(count: Int) {
    Surface(shape = MaterialTheme.shapes.extraSmall, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text(
            stringResource(R.string.changes_files_badge, count),
            style = ClineMono.small,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = Spacing.s - 2.dp, vertical = Spacing.xxs),
        )
    }
}

@Composable
private fun AccordionChanges(
    state: ChangesUiState,
    actions: ChangesActions,
    padding: PaddingValues,
    gutter: Dp,
    strip: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    // The strip item is empty (zero height) while connected, so the list anchors on the
    // summary below it; when the kernel drops, the strip would grow above the viewport.
    // A list resting at its top scrolls back to item 0 so the strip is seen (CT A.11).
    val stripShown = !state.kernel.connected
    LaunchedEffect(stripShown) {
        if (stripShown && listState.firstVisibleItemIndex <= 1 && listState.firstVisibleItemScrollOffset == 0) {
            listState.scrollToItem(0)
        }
    }
    // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
    val direction = LocalLayoutDirection.current
    Box(
        Modifier.fillMaxSize().padding(
            start = padding.calculateStartPadding(direction),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(direction),
        ),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().widthIn(max = Spacing.contentMaxWidth),
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
        ) {
            item(key = "strip") { strip() }
            when (val content = state.content) {
                ChangesUiState.Content.Loading -> item(key = "loading") { DelayedLoading(Modifier.fillParentMaxSize()) }
                is ChangesUiState.Content.Failed -> item(key = "failed") {
                    ErrorEmptyState(
                        title = stringResource(R.string.changes_load_error),
                        body = content.message,
                        actionLabel = stringResource(R.string.changes_retry),
                        onAction = actions.onRetry,
                        modifier = Modifier.fillParentMaxSize(),
                    )
                }
                ChangesUiState.Content.Unavailable -> item(key = "unavailable") {
                    KernelUnavailableState(state.kernel, actions.onKernelAction, Modifier.fillParentMaxSize())
                }
                is ChangesUiState.Content.Ready -> if (content.files.isEmpty()) {
                    item(key = "empty") {
                        EmptyState(
                            title = stringResource(R.string.changes_empty),
                            body = stringResource(R.string.changes_empty_body),
                            icon = ClineIcons.Difference,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    populated(state, content, actions, gutter)
                }
            }
        }
    }
}

private fun LazyListScope.populated(
    state: ChangesUiState,
    content: ChangesUiState.Content.Ready,
    actions: ChangesActions,
    gutter: Dp,
) {
    item(key = "summary") { SummaryLine(content.summary, content.files.size, Modifier.padding(horizontal = gutter)) }
    if (content.anyTruncated) item(key = "truncated") { TruncationNotice(Modifier.padding(horizontal = gutter)) }
    content.files.forEachIndexed { fileIndex, file ->
        val expanded = state.isExpanded(file.path)
        item(key = "file:${file.path}") {
            Column {
                if (fileIndex > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                FileHeader(
                    file = file,
                    expanded = expanded,
                    copied = state.copiedPath == file.path,
                    highlightKey = if (file.path in content.changedPaths) content.updatedAt else null,
                    actions = actions,
                    gutter = gutter,
                )
            }
        }
        if (expanded) fileBody(file, state, actions, gutter)
    }
}

private fun LazyListScope.fileBody(file: SessionFileDiff, state: ChangesUiState, actions: ChangesActions, gutter: Dp) {
    if (file.hunks.isEmpty()) {
        item(key = "nohunks:${file.path}") {
            NoHunkDetails(Modifier.padding(horizontal = gutter, vertical = Spacing.m))
        }
        return
    }
    file.hunks.forEachIndexed { index, hunk ->
        item(key = "hunk:${file.path}:$index") {
            HunkItem(file, hunk, index, state, actions, Modifier.padding(horizontal = gutter).padding(top = if (index == 0) Spacing.m else Spacing.s, bottom = if (index == file.hunks.lastIndex) Spacing.m else 0.dp))
        }
    }
}

@Composable
private fun HunkItem(
    file: SessionFileDiff,
    hunk: SessionDiffHunk,
    index: Int,
    state: ChangesUiState,
    actions: ChangesActions,
    modifier: Modifier,
) {
    val key = HunkKey(file.path, index)
    DiffHunkView(
        hunk = hunk,
        path = file.path,
        index = index,
        count = file.hunks.size,
        shownRows = state.shownRows(key),
        onShowMore = { actions.onShowMore(key) },
        modifier = modifier,
    )
}

@Composable
private fun FileHeader(
    file: SessionFileDiff,
    expanded: Boolean?,
    copied: Boolean,
    highlightKey: Any?,
    actions: ChangesActions,
    gutter: Dp,
) {
    ChangedFileHeader(
        path = file.path,
        additions = file.additions,
        deletions = file.deletions,
        onCopyPath = { actions.onCopyPath(file.path) },
        expanded = expanded,
        onToggle = { actions.onToggle(file.path) },
        copied = copied,
        menuItems = fileMenu(file.path, actions),
        highlightKey = highlightKey,
        // Without a chevron (two-pane detail) the path starts on the gutter; the trailing
        // overflow button's own padding keeps its glyph on the gutter at the end.
        contentPadding = PaddingValues(start = if (expanded == null) gutter else gutter - Spacing.s, end = gutter - Spacing.s),
        modifier = Modifier
            .semantics { heading() }
            .onKeyEvent { event ->
                // Ctrl+C on a focused header copies its path (A.7 hardware keyboard).
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.C) {
                    actions.onCopyPath(file.path)
                    true
                } else {
                    false
                }
            },
    )
}

@Composable
private fun fileMenu(path: String, actions: ChangesActions): List<ContextMenuItem> = listOf(
    ContextMenuItem("copy", stringResource(R.string.changes_copy_path), { actions.onCopyPath(path) }, icon = ClineIcons.ContentCopy),
    ContextMenuItem("share", stringResource(R.string.changes_share_diff), { actions.onShareFile(path) }, icon = ClineIcons.Share),
)

/** "`+120 -34` across 7 files" (Android): mono counts in `success.color` / `error`; a heading for TalkBack. */
@Composable
private fun SummaryLine(summary: SessionDiffSummary, fileCount: Int, modifier: Modifier = Modifier) {
    val added = stringResource(R.string.diff_additions_count, summary.additions)
    val removed = stringResource(R.string.diff_deletions_count, summary.deletions)
    // Marks stand in for the counts so a translation may reorder or wrap them (DS §8.2.3).
    val counts = stringResource(R.string.changes_summary_counts, SUMMARY_ADDED_MARK.toString(), SUMMARY_REMOVED_MARK.toString())
    val sentence = copyPluralStringResource(R.plurals.changes_summary, fileCount, counts, fileCount)
    val mono = ClineMono.small.toSpanStyle()
    val success = ClineTheme.colors.success.color
    val error = MaterialTheme.colorScheme.error
    val text = remember(sentence, added, removed, mono, success, error) {
        spliceSummaryCounts(
            formatted = sentence,
            added = added,
            removed = removed,
            addedStyle = mono.merge(SpanStyle(color = success)),
            removedStyle = mono.merge(SpanStyle(color = error)),
        )
    }
    Box(modifier.fillMaxWidth().heightIn(min = 40.dp).semantics { heading() }, contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TruncationNotice(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(bottom = Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        Icon(ClineIcons.Info, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Spacing.s))
        Text(stringResource(R.string.changes_truncated_notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A.5 Loading: the body text appears only after 300 ms, so a fast seed does not flash it. */
@Composable
private fun DelayedLoading(modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(LOADING_TEXT_DELAY_MS)
        visible = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (visible) {
            Text(
                stringResource(R.string.changes_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A.5 "Offline / kernel down, nothing cached": `cloud_off`, DS §1.6 copy, the strip's action. */
@Composable
internal fun KernelUnavailableState(kernel: KernelConnectionVisual, onAction: (KernelAction) -> Unit, modifier: Modifier = Modifier) {
    val action = kernel.action ?: KernelAction.StartKernel
    EmptyState(
        title = stringResource(R.string.hub_not_connected),
        body = stringResource(R.string.changes_kernel_start_to_continue),
        icon = ClineIcons.CloudOff,
        actionLabel = stringResource(R.string.kernel_start),
        onAction = { onAction(action) },
        modifier = modifier,
    )
}

/** An EmptyState whose icon is `error` in `error` (A.5 "Error"; B tool load error). */
@Composable
internal fun ErrorEmptyState(
    title: String,
    body: String?,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector = ClineIcons.Error,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xxl, vertical = Spacing.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = 480.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.heightIn(min = Spacing.l))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            if (!body.isNullOrBlank()) {
                Spacer(Modifier.heightIn(min = Spacing.s))
                LtrText(text = body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.heightIn(min = Spacing.xxl))
                FilledTonalButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
private fun TwoPaneChanges(
    state: ChangesUiState,
    ready: ChangesUiState.Content.Ready,
    actions: ChangesActions,
    padding: PaddingValues,
    strip: @Composable () -> Unit,
) {
    val selected = state.selectedFile ?: return
    val direction = LocalLayoutDirection.current
    Column(
        Modifier.fillMaxSize().padding(
            start = padding.calculateStartPadding(direction),
            top = padding.calculateTopPadding(),
            end = padding.calculateEndPadding(direction),
        ),
    ) {
        strip()
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.width(LIST_WIDTH).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
            ) {
                item(key = "summary") { SummaryLine(ready.summary, ready.files.size, Modifier.padding(horizontal = Spacing.l)) }
                if (ready.anyTruncated) item(key = "truncated") { TruncationNotice(Modifier.padding(horizontal = Spacing.l)) }
                items(ready.files, key = { it.path }) { file ->
                    FileListRow(file, selected = file.path == selected.path, onSelect = { actions.onSelect(file.path) })
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(bottom = padding.calculateBottomPadding()),
            ) {
                item(key = "header:${selected.path}") {
                    FileHeader(
                        file = selected,
                        expanded = null,
                        copied = state.copiedPath == selected.path,
                        highlightKey = if (selected.path in ready.changedPaths) ready.updatedAt else null,
                        actions = actions,
                        gutter = Spacing.gutterWide,
                    )
                }
                fileBody(selected, state, actions, Spacing.gutterWide)
            }
        }
    }
}

/** Two-pane list row: 56dp `ListItem`, basename / parent directory / `+N -N`; selected on `secondaryContainer`. */
@Composable
private fun FileListRow(file: SessionFileDiff, selected: Boolean, onSelect: () -> Unit) {
    val (directory, name) = splitBasename(file.path)
    val added = stringResource(R.string.diff_additions_count, file.additions)
    val removed = stringResource(R.string.diff_deletions_count, file.deletions)
    val description = stringResource(
        R.string.changes_list_row_a11y,
        file.path,
        copyPluralStringResource(R.plurals.changes_additions_a11y, file.additions, file.additions),
        copyPluralStringResource(R.plurals.changes_deletions_a11y, file.deletions, file.deletions),
    )
    ListItem(
        headlineContent = { LtrText(text = name, style = MaterialTheme.typography.bodyLarge, maxLines = 1) },
        supportingContent = if (directory.isNotEmpty()) {
            {
                LtrText(
                    text = directory,
                    style = ClineMono.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        } else {
            null
        },
        trailingContent = {
            // DS §8.2.6: diff counts stay "+N -N" (LTR) while the list row around them mirrors.
            LtrBlock {
                Row {
                    Text(added, style = ClineMono.small, color = ClineTheme.colors.success.color)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(removed, style = ClineMono.small, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = selected, onClick = onSelect, role = Role.Tab)
            .clearAndSetSemantics { contentDescription = description },
    )
}

private val LIST_WIDTH = 320.dp
private const val LOADING_TEXT_DELAY_MS = 300L

// ---- previews ---------------------------------------------------------------------------------

private val PreviewFiles = listOf(
    SessionFileDiff(
        path = "app/src/main/kotlin/dev/cline/Main.kt",
        additions = 2,
        deletions = 1,
        hunks = listOf(SessionDiffHunk(3, 3, "val x = 1\n", "val x = 2\nval y = 3\n")),
    ),
    SessionFileDiff(path = "hello.py", additions = 2, deletions = 0, hunks = listOf(SessionDiffHunk(1, 1, "", "print(\"hello\")\nprint(\"hi\")\n"))),
    SessionFileDiff(path = "README.md", additions = 0, deletions = 2, hunks = emptyList(), hunksTruncated = true),
)

private fun previewState(content: ChangesUiState.Content, kernel: KernelConnectionVisual = KernelConnectionVisual.Connected, collapsed: Set<String> = emptySet()) =
    ChangesUiState(sessionId = "s1", sessionTitle = "Demo", kernel = kernel, content = content, collapsed = collapsed, copiedPath = "hello.py")

private val PreviewReady = ChangesUiState.Content.Ready(PreviewFiles, SessionDiffSummary(4, 3), setOf("hello.py"), anyTruncated = true, updatedAt = 1L)

@LightDarkPreviews
@Composable
private fun ChangesPopulatedPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(previewState(PreviewReady, collapsed = setOf("README.md")), ChangesLayout.Accordion, ChangesActions(), onBack = {}, onClose = null, onOpenFullScreen = null)
    }
}

@LightDarkPreviews
@Composable
private fun ChangesEmptyPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(
            previewState(ChangesUiState.Content.Ready(emptyList(), SessionDiffSummary(0, 0), emptySet(), false, 1L)),
            ChangesLayout.Accordion,
            ChangesActions(),
            onBack = {},
            onClose = null,
            onOpenFullScreen = null,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ChangesLoadingPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(previewState(ChangesUiState.Content.Loading), ChangesLayout.Accordion, ChangesActions(), onBack = {}, onClose = null, onOpenFullScreen = null)
    }
}

@LightDarkPreviews
@Composable
private fun ChangesErrorPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(
            previewState(ChangesUiState.Content.Failed("session.messages failed: command_failed: disk I/O error")),
            ChangesLayout.Accordion,
            ChangesActions(),
            onBack = {},
            onClose = null,
            onOpenFullScreen = null,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ChangesOfflineCachedPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(
            previewState(PreviewReady, kernel = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.StartFailed, null))),
            ChangesLayout.Accordion,
            ChangesActions(),
            onBack = {},
            onClose = null,
            onOpenFullScreen = null,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ChangesOfflineEmptyPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(
            previewState(ChangesUiState.Content.Unavailable, kernel = KernelConnectionVisual(KernelGate.Down(KernelGate.DownVariant.Stopped, null))),
            ChangesLayout.Accordion,
            ChangesActions(),
            onBack = {},
            onClose = null,
            onOpenFullScreen = null,
        )
    }
}

/** A.5 "Running / streaming": the session is still working, so the counts may move. */
@LightDarkPreviews
@Composable
private fun ChangesStreamingPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(previewState(PreviewReady).copy(isSessionBusy = true), ChangesLayout.Accordion, ChangesActions(), onBack = {}, onClose = null, onOpenFullScreen = null)
    }
}

/** A.5 "Permission missing": RUN_COMMAND revoked, the cached diff stays with the kernel strip. */
@LightDarkPreviews
@Composable
private fun ChangesPermissionMissingPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(
            previewState(PreviewReady, kernel = KernelConnectionVisual(KernelGate.Blocked(dev.cline.termux.core.kernel.CheckId.Permission))),
            ChangesLayout.Accordion,
            ChangesActions(),
            onBack = {},
            onClose = null,
            onOpenFullScreen = null,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ChangesPanePreview() {
    ComponentPreview(padding = false) {
        Box(Modifier.width(400.dp).background(MaterialTheme.colorScheme.surface)) {
            ChangesScreen(previewState(PreviewReady), ChangesLayout.Pane, ChangesActions(), onBack = null, onClose = {}, onOpenFullScreen = {})
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Two-pane", widthDp = 1000, heightDp = 700)
@androidx.compose.ui.tooling.preview.Preview(
    name = "Two-pane dark",
    widthDp = 1000,
    heightDp = 700,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun ChangesTwoPanePreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(previewState(PreviewReady), ChangesLayout.TwoPane, ChangesActions(), onBack = {}, onClose = null, onOpenFullScreen = null)
    }
}

/** RTL: the screen mirrors, each list row's "+N -N" counts keep the desktop order (DS §8.2.6). */
@androidx.compose.ui.tooling.preview.Preview(name = "Two-pane RTL", locale = "ar", widthDp = 1000, heightDp = 700)
@androidx.compose.ui.tooling.preview.Preview(
    name = "Two-pane RTL dark",
    locale = "ar",
    widthDp = 1000,
    heightDp = 700,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun ChangesTwoPaneRtlPreview() {
    ComponentPreview(padding = false) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            ChangesScreen(previewState(PreviewReady), ChangesLayout.TwoPane, ChangesActions(), onBack = {}, onClose = null, onOpenFullScreen = null)
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Height-compact", widthDp = 891, heightDp = 412)
@androidx.compose.ui.tooling.preview.Preview(
    name = "Height-compact dark",
    widthDp = 891,
    heightDp = 412,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES or android.content.res.Configuration.UI_MODE_TYPE_NORMAL,
)
@Composable
private fun ChangesHeightCompactPreview() {
    ComponentPreview(padding = false) {
        ChangesScreen(previewState(PreviewReady), ChangesLayout.HeightCompact, ChangesActions(), onBack = {}, onClose = null, onOpenFullScreen = null)
    }
}
