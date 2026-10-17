package dev.cline.termux.ui.tool

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.appGraph
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.ui.chat.ImageLightboxOverlay
import dev.cline.termux.ui.chat.hiddenBehindLightbox
import dev.cline.termux.ui.chat.LocalShellLightbox
import dev.cline.termux.ui.chat.rememberLightboxState
import dev.cline.termux.ui.chat.rows.GalleryKind
import dev.cline.termux.ui.chat.rows.ImageGallery
import dev.cline.termux.ui.chat.rows.monoSpan
import dev.cline.termux.ui.chat.rows.toolLabelText
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.changes.DetailBar
import dev.cline.termux.ui.changes.DetailScaffold
import dev.cline.termux.ui.changes.KernelStrip
import dev.cline.termux.ui.changes.KernelUnavailableState
import dev.cline.termux.ui.changes.ShareRequest
import dev.cline.termux.ui.changes.launchShare
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.rememberCopier
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.rememberLiveRegionState
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.navigation.ShellLayout
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which B.4 window variant a Tool detail instance uses. */
enum class ToolWindow {
    /** Pushed, compact / medium portrait. */
    Compact,

    /** Pushed, height < 480dp: everything but the terminal folds into the bar and one row. */
    HeightCompact,

    /** Pushed, ≥ 840 × ≥ 480: command list column beside the terminal. */
    Expanded,

    /** The 400dp supporting pane (compact layout under a pane header). */
    Pane;

    companion object {
        fun forPushed(layout: ShellLayout): ToolWindow = when {
            layout.heightCompact -> HeightCompact
            layout.widthDp >= EXPANDED_MIN_WIDTH -> Expanded
            else -> Compact
        }

        const val EXPANDED_MIN_WIDTH = 840
    }
}

/** Everything the Tool detail body calls back into. */
class ToolDetailActions(
    val onSelectCommand: (Int) -> Unit = {},
    val onFilter: (StreamFilter) -> Unit = {},
    val onToggleWrap: () -> Unit = {},
    val onProceed: () -> Unit = {},
    val onStopAgent: () -> Unit = {},
    val onCopy: (CopyTarget) -> Unit = {},
    /** Edit layout file header: copies the absolute path, then calls `onCopied` (the check icon). */
    val onCopyFilePath: (path: String, onCopied: () -> Unit) -> Unit = { _, _ -> },
    val onShareOutput: () -> Unit = {},
    val onViewAllChanges: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onKernelAction: (KernelAction) -> Unit = {},
    val onOpenImage: (Int) -> Unit = {},
    val onBackToConversation: () -> Unit = {},
)

/**
 * The pushed Tool detail route `chat/{sessionId}/tool/{toolCallId}` (CT B.3). [onViewAllChanges]
 * opens Changes for the session (edit layout overflow).
 */
@Composable
fun ToolDetailRoute(
    sessionId: String,
    toolCallId: String,
    graph: AppGraph,
    onBack: () -> Unit,
    onViewAllChanges: () -> Unit,
    modifier: Modifier = Modifier,
    approvalBanner: @Composable () -> Unit = {},
) {
    val viewModel: ToolDetailViewModel = viewModel(factory = ToolDetailViewModel.factory(graph, sessionId, toolCallId))
    ToolDetailHost(
        viewModel = viewModel,
        window = ToolWindow.forPushed(LocalShellLayout.current),
        onBack = onBack,
        onClose = onBack,
        onViewAllChanges = onViewAllChanges,
        approvalBanner = approvalBanner,
        modifier = modifier,
    )
}

/**
 * The supporting-pane Tool detail (CT B.3, DS-A1) for `SupportingContent.Tool`: the compact
 * layout under a pane header with copy, overflow and "Close tool details". The pane closes
 * when the chat shows another session ([activeSessionId] differs from [sessionId]).
 */
@Composable
fun ToolPane(
    sessionId: String,
    toolCallId: String,
    onClose: () -> Unit,
    onViewAllChanges: () -> Unit,
    modifier: Modifier = Modifier,
    activeSessionId: String? = null,
    approvalBanner: @Composable () -> Unit = {},
) {
    val currentClose by rememberUpdatedState(onClose)
    LaunchedEffect(activeSessionId, sessionId) {
        if (activeSessionId != null && activeSessionId != sessionId) currentClose()
    }
    val graph = LocalContext.current.appGraph
    val viewModel: ToolDetailViewModel = viewModel(
        key = "tool-pane:$sessionId:$toolCallId",
        factory = ToolDetailViewModel.factory(graph, sessionId, toolCallId),
    )
    ToolDetailHost(
        viewModel = viewModel,
        window = ToolWindow.Pane,
        onBack = null,
        onClose = onClose,
        onViewAllChanges = onViewAllChanges,
        approvalBanner = approvalBanner,
        modifier = modifier,
    )
}

@Composable
private fun ToolDetailHost(
    viewModel: ToolDetailViewModel,
    window: ToolWindow,
    onBack: (() -> Unit)?,
    onClose: () -> Unit,
    onViewAllChanges: () -> Unit,
    approvalBanner: @Composable () -> Unit,
    modifier: Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val live = rememberLiveRegionState()
    val shellKernel = LocalShellKernelUi.current
    val copier = rememberCopier(legacyConfirmation = stringResource(R.string.components_copied))
    val copyFailed = stringResource(R.string.tool_copy_failed)
    val pathCopier = rememberCopier(legacyConfirmation = stringResource(R.string.changes_path_copied))
    val pathLabel = stringResource(R.string.changes_clip_label)
    val pathCopyFailed = stringResource(R.string.changes_copy_failed)
    val chooser = stringResource(R.string.tool_share_chooser)
    val labels = CopyLabels(
        output = stringResource(R.string.tool_output_cd),
        command = stringResource(R.string.tool_clip_command),
        input = stringResource(R.string.tool_clip_input),
        logPath = stringResource(R.string.tool_clip_log_path),
    )
    // Tool output images open in the shared chat lightbox (DS #ImageLightbox / Carousel): the
    // same zoomable pager, Share button, 95 % scrim and predictive back as transcript images.
    // Inside the shell it is the shell's lightbox, so it covers the rail and every pane (HC §5.2).
    val shellLightbox = LocalShellLightbox.current
    val ownLightbox = rememberLightboxState()
    val lightbox = shellLightbox ?: ownLightbox

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ToolDetailEffect.Announce -> live.announce(
                    resources.getString(
                        when (effect.status) {
                            ToolDetailStatus.Failed -> R.string.tool_announce_failed
                            ToolDetailStatus.Detached -> R.string.tool_announce_detached
                            else -> R.string.tool_announce_finished
                        },
                    ),
                )
                is ToolDetailEffect.ProceedFailed -> if (window == ToolWindow.HeightCompact) {
                    snackbar.showReplacing(proceedErrorText(resources, effect.state))
                }
            }
        }
    }

    val latestState by rememberUpdatedState(state)
    val actions = ToolDetailActions(
        onSelectCommand = viewModel::onSelectCommand,
        onFilter = viewModel::onFilter,
        onToggleWrap = viewModel::onToggleWrap,
        onProceed = viewModel::onProceed,
        onStopAgent = viewModel::onStopAgent,
        onCopy = { target ->
            val s = latestState
            scope.launch {
                val (label, text) = when (target) {
                    CopyTarget.Output -> labels.output to withContext(Dispatchers.Default) { s.outputCopyText() }
                    CopyTarget.Command -> labels.command to s.selected?.query.orEmpty()
                    CopyTarget.Input -> labels.input to s.inputJson
                    CopyTarget.LogPath -> labels.logPath to s.detachedLogPath.orEmpty()
                }
                if (text.isEmpty()) return@launch
                copier.copy(label, text) { ok ->
                    if (ok) viewModel.onCopied(target) else scope.launch { snackbar.showReplacing(copyFailed) }
                }
            }
        },
        onCopyFilePath = { path, onCopied ->
            pathCopier.copy(pathLabel, viewModel.absolutePath(path)) { ok ->
                if (ok) onCopied() else scope.launch { snackbar.showReplacing(pathCopyFailed) }
            }
        },
        onShareOutput = {
            val s = latestState
            scope.launch {
                val text = withContext(Dispatchers.Default) { s.shareText() }
                if (text.isNotEmpty()) {
                    launchShare(context, ShareRequest(text, subject = null, chooserTitle = chooser, fileName = "${s.toolCallId}.log", fileMimeType = "text/plain"))
                }
            }
        },
        onViewAllChanges = onViewAllChanges,
        onRetry = viewModel::onRetry,
        onKernelAction = { action -> shellKernel?.onAction?.invoke(action) ?: viewModel.onStartKernel() },
        onOpenImage = { index ->
            val s = latestState
            lightbox.open(s.sessionId, toolImageGallery(s.images), index)
        },
        onBackToConversation = { (onBack ?: onClose)() },
    )
    Box(modifier) {
        ToolDetailScreen(
            state = state,
            window = window,
            actions = actions,
            onBack = onBack,
            onClose = if (window == ToolWindow.Pane) onClose else null,
            modifier = Modifier.hiddenBehindLightbox(lightbox),
            kernelActionInFlight = shellKernel?.actionInFlight,
            approvalBanner = approvalBanner,
            snackbarsVisible = !lightbox.isOpen,
        )
        LiveRegionAnnouncer(live)
        if (shellLightbox == null) ImageLightboxOverlay(lightbox)
    }
}

/**
 * The lightbox gallery for a tool's output images: the same [GalleryKind.Generated] gallery the
 * thumbnails ([dev.cline.termux.ui.chat.rows.GeneratedImages]) build, so the lightbox labels them
 * "Generated image N" like the transcript does.
 */
internal fun toolImageGallery(images: List<ChatImage>): ImageGallery = ImageGallery(GalleryKind.Generated, images)

private class CopyLabels(val output: String, val command: String, val input: String, val logPath: String)

private fun proceedErrorText(resources: android.content.res.Resources, state: ProceedState.Error): String = when (state.error) {
    ProceedError.Generic -> resources.getString(R.string.tool_proceed_error)
    ProceedError.Finished -> resources.getString(R.string.tool_proceed_finished)
    ProceedError.Unsupported -> state.message ?: resources.getString(R.string.tool_proceed_unsupported)
}

@Composable
internal fun proceedErrorText(state: ProceedState.Error): String = proceedErrorText(LocalResources.current, state)

/**
 * The stateless Tool detail screen (every B.5 state, every B.4 layout). [snackbarsVisible] is
 * false while the image lightbox covers it (the lightbox hosts the one snackbar, DS §4.1 #17).
 */
@Composable
fun ToolDetailScreen(
    state: ToolDetailUiState,
    window: ToolWindow,
    actions: ToolDetailActions,
    onBack: (() -> Unit)?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier,
    kernelActionInFlight: KernelAction? = null,
    approvalBanner: @Composable () -> Unit = {},
    snackbarsVisible: Boolean = true,
) {
    val found = state.found
    // End (B.7): the selected terminal jumps to its end and re-pins follow-tail.
    val endRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val bar = when {
        window == ToolWindow.Pane -> DetailBar.Pane
        window == ToolWindow.HeightCompact -> DetailBar.SmallPinned
        found && state.layout == ToolLayout.Command -> DetailBar.SmallPinned
        else -> DetailBar.Medium
    }
    val terminalScreen = found && state.layout == ToolLayout.Command
    val heightCompactCommand = terminalScreen && window == ToolWindow.HeightCompact
    // The docked bar spans the right column in the expanded command layout (drawn there instead).
    val dockedBar = found && state.showActionBar && !heightCompactCommand && !(terminalScreen && window == ToolWindow.Expanded)
    DetailScaffold(
        bar = bar,
        // CT B7: on a height-compact window the status line rides under the title.
        title = {
            if (found) {
                val statusInBar = commandStatusInAppBar(heightCompactCommand, LocalDensity.current.fontScale)
                ToolTitle(state, subtitle = if (statusInBar) statusText(state) else null)
            }
        },
        modifier = modifier.onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || !found) return@onKeyEvent false
            when {
                // Ctrl+C with no selection (a selection consumes it first): copy the output.
                event.isCtrlPressed && event.key == Key.C && state.hasOutput -> { actions.onCopy(CopyTarget.Output); true }
                event.key == Key.Escape && state.canAbort && state.actionsEnabled && !state.abortPending -> { actions.onStopAgent(); true }
                event.key == Key.MoveEnd && terminalScreen -> { endRequests.tryEmit(Unit); true }
                else -> false
            }
        },
        onBack = onBack,
        onClose = onClose,
        closeLabel = stringResource(R.string.tool_close_cd),
        loading = state.loading,
        drawToBottom = terminalScreen && !dockedBar,
        snackbarsVisible = snackbarsVisible,
        actions = { if (found) ToolBarActions(state, actions, heightCompactCommand) },
        bottomBar = {
            Column {
                approvalBanner()
                if (dockedBar) ToolActionBar(state, actions)
            }
        },
    ) { padding ->
        when {
            // A reconnecting / starting kernel keeps the progress bar under its strip (B.5 Offline).
            state.loading -> Column(Modifier.fillMaxSize().padding(padding)) {
                KernelStrip(
                    kernel = state.kernel,
                    onAction = actions.onKernelAction,
                    actionInFlight = kernelActionInFlight,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.xs),
                )
            }
            state.kernelUnavailable -> KernelUnavailableState(state.kernel, actions.onKernelAction, Modifier.padding(padding).fillMaxSize())
            state.notFound -> NotFoundState(actions.onBackToConversation, Modifier.padding(padding))
            state.loadError != null -> LoadErrorState(state, actions, Modifier.padding(padding))
            else -> when (state.layout) {
                ToolLayout.Command -> CommandBody(state, window, actions, padding, dockedBar, kernelActionInFlight, endRequests)
                ToolLayout.Edit, ToolLayout.Generic -> SectionsBody(state, window, actions, padding, kernelActionInFlight)
            }
        }
    }
}

/** B.4 title: the 24dp icon slot (kind icon, `error`, a spinner while running) and the one-line label. */
@Composable
private fun ToolTitle(state: ToolDetailUiState, subtitle: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when {
                state.status.running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else -> {
                    val (icon, tint) = titleIcon(state)
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
            }
        }
        Spacer(Modifier.width(Spacing.s))
        if (subtitle == null) {
            Text(toolLabelText(state.label, monoSpan(ClineMono.label)), maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Column {
                Text(toolLabelText(state.label, monoSpan(ClineMono.label)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun titleIcon(state: ToolDetailUiState): Pair<ImageVector, androidx.compose.ui.graphics.Color> {
    val kindIcon = ImageVector.vectorResource(ClineIcons.drawableOf(state.icon))
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    return when (state.status) {
        ToolDetailStatus.Failed -> ClineIcons.ErrorFilled to MaterialTheme.colorScheme.error
        ToolDetailStatus.Rejected -> ClineIcons.Block to MaterialTheme.colorScheme.error
        ToolDetailStatus.AwaitingApproval -> ClineIcons.GppMaybe to ClineTheme.colors.warning.color
        ToolDetailStatus.Interrupted -> ClineIcons.Warning to ClineTheme.colors.warning.color
        ToolDetailStatus.Detached -> ClineIcons.Terminal to MaterialTheme.colorScheme.onSurface
        ToolDetailStatus.NoResult -> kindIcon to variant
        else -> kindIcon to MaterialTheme.colorScheme.onSurface
    }
}

/** App bar actions: copy output, the overflow menu; height-compact command adds Stop / Proceed first. */
@Composable
private fun ToolBarActions(state: ToolDetailUiState, actions: ToolDetailActions, heightCompactCommand: Boolean) {
    if (heightCompactCommand) {
        if (state.canAbort) {
            TextButton(
                onClick = actions.onStopAgent,
                enabled = state.actionsEnabled && !state.abortPending,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.tool_stop_agent)) }
        }
        if (state.canProceed) {
            ProceedTextButton(state, actions.onProceed)
        }
    }
    val copyOutput = state.hasOutput && state.layout != ToolLayout.Edit
    if (copyOutput) {
        val copied = state.copied == CopyTarget.Output
        TooltipIconButton(
            icon = if (copied) ClineIcons.Check else ClineIcons.ContentCopy,
            contentDescription = stringResource(if (copied) R.string.tool_copied_output else R.string.tool_copy_output),
            tooltip = stringResource(R.string.tool_copy_output),
            onClick = { actions.onCopy(CopyTarget.Output) },
        )
    }
    ToolOverflow(state, actions, heightCompactCommand)
}

@Composable
private fun ProceedTextButton(state: ToolDetailUiState, onProceed: () -> Unit) {
    val pending = state.proceed == ProceedState.Pending
    val inProgress = stringResource(R.string.tool_in_progress_state)
    TextButton(
        onClick = onProceed,
        enabled = state.actionsEnabled && !pending,
        modifier = Modifier.semantics { if (pending) stateDescription = inProgress },
    ) {
        if (pending) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
        }
        Text(stringResource(R.string.tool_proceed))
    }
}

@Composable
private fun ToolOverflow(state: ToolDetailUiState, actions: ToolDetailActions, heightCompactCommand: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            icon = ClineIcons.MoreVert,
            contentDescription = stringResource(R.string.tool_more_options),
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun pick(block: () -> Unit) {
                open = false
                block()
            }
            if (state.layout == ToolLayout.Command && state.selected?.query?.isNotEmpty() == true) {
                CopyMenuItem(state, CopyTarget.Command, R.string.tool_copy_command, R.string.tool_copied_command) { pick { actions.onCopy(CopyTarget.Command) } }
            }
            CopyMenuItem(state, CopyTarget.Input, R.string.tool_copy_input, R.string.tool_copied_input) { pick { actions.onCopy(CopyTarget.Input) } }
            if (state.hasOutput) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_share_output)) },
                    leadingIcon = { Icon(ClineIcons.Share, contentDescription = null) },
                    onClick = { pick(actions.onShareOutput) },
                )
            }
            if (state.layout == ToolLayout.Command) {
                CheckMenuItem(stringResource(R.string.tool_wrap_lines), state.wrapLines) { pick(actions.onToggleWrap) }
            }
            if (state.layout == ToolLayout.Edit) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.tool_view_all_changes)) },
                    leadingIcon = { Icon(ClineIcons.Difference, contentDescription = null) },
                    onClick = { pick(actions.onViewAllChanges) },
                )
            }
            if (heightCompactCommand && state.showFilter) {
                HorizontalDivider()
                Text(
                    stringResource(R.string.tool_filter_heading),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
                )
                StreamFilter.entries.forEach { filter ->
                    CheckMenuItem(filterLabel(filter), state.effectiveFilter == filter) { pick { actions.onFilter(filter) } }
                }
            }
        }
    }
}

@Composable
private fun CopyMenuItem(state: ToolDetailUiState, target: CopyTarget, label: Int, copiedLabel: Int, onClick: () -> Unit) {
    val copied = state.copied == target
    DropdownMenuItem(
        text = { Text(stringResource(if (copied) copiedLabel else label)) },
        leadingIcon = { Icon(if (copied) ClineIcons.Check else ClineIcons.ContentCopy, contentDescription = null) },
        onClick = onClick,
    )
}

/** A checkable menu item: the check glyph marks the selected state, which TalkBack also reads. */
@Composable
private fun CheckMenuItem(label: String, checked: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = if (checked) {
            { Icon(ClineIcons.Check, contentDescription = null) }
        } else {
            null
        },
        onClick = onClick,
        modifier = Modifier.semantics {
            role = Role.Checkbox
            toggleableState = ToggleableState(checked)
        },
    )
}

@Composable
internal fun filterLabel(filter: StreamFilter): String = stringResource(
    when (filter) {
        StreamFilter.All -> R.string.tool_filter_all
        StreamFilter.Stdout -> R.string.tool_filter_stdout
        StreamFilter.Stderr -> R.string.tool_filter_stderr
    },
)

/** B.4 docked `BottomAppBar` (80dp): "Stop agent" at the start, "Proceed while running" at the end. */
@Composable
internal fun ToolActionBar(state: ToolDetailUiState, actions: ToolDetailActions, modifier: Modifier = Modifier) {
    BottomAppBar(modifier = modifier) {
        Row(Modifier.padding(horizontal = Spacing.l - Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
            if (state.canAbort) {
                TextButton(
                    onClick = actions.onStopAgent,
                    enabled = state.actionsEnabled && !state.abortPending,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.tool_stop_agent)) }
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.canProceed) {
            val pending = state.proceed == ProceedState.Pending
            val inProgress = stringResource(R.string.tool_in_progress_state)
            OutlinedButton(
                onClick = actions.onProceed,
                enabled = state.actionsEnabled && !pending,
                modifier = Modifier
                    .padding(end = Spacing.l)
                    .semantics { if (pending) stateDescription = inProgress },
            ) {
                if (pending) {
                    InlineSpinner()
                    Spacer(Modifier.width(Spacing.s))
                }
                Text(stringResource(R.string.tool_proceed))
            }
        }
    }
}
