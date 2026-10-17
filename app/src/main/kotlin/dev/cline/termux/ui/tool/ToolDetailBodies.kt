package dev.cline.termux.ui.tool

import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.BuildConfig
import dev.cline.termux.R
import dev.cline.termux.data.chat.formatWorkDuration
import dev.cline.termux.ui.changes.ErrorEmptyState
import dev.cline.termux.ui.changes.KernelStrip
import dev.cline.termux.ui.changes.KernelUnavailableState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import dev.cline.termux.ui.chat.rows.GeneratedImages
import dev.cline.termux.ui.chat.rows.GeneratedMediaList
import dev.cline.termux.ui.chat.rows.ToolFileDiffView
import dev.cline.termux.ui.chat.rows.asGeneratedMedia
import dev.cline.termux.ui.chat.rows.monoSpan
import dev.cline.termux.ui.chat.rows.toolLabelText
import dev.cline.termux.ui.components.EmptyState
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.bottomSafe
import dev.cline.termux.ui.diff.ChangedFileHeader
import dev.cline.termux.ui.diff.rememberTransientFlag
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.icons.ToolKind
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.markdown.MarkdownText
import dev.cline.termux.ui.navigation.LocalShellLayout
import dev.cline.termux.ui.terminal.TerminalScrollState
import dev.cline.termux.ui.terminal.TerminalView
import dev.cline.termux.ui.terminal.rememberTerminalScrollState
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.LocalReducedMotion
import dev.cline.termux.ui.theme.Spacing

/*
 * The Tool detail bodies (CT B.4): the command layout in its compact, height-compact and
 * expanded variants, and the edit / generic section lists, plus the shared rows (status line,
 * banners, overlines, terminal placeholder).
 */

@Composable
private fun gutterFor(window: ToolWindow): Dp = when {
    window == ToolWindow.Pane -> Spacing.gutterCompact
    window == ToolWindow.HeightCompact -> Spacing.gutterWide
    LocalShellLayout.current.gutter > Spacing.gutterCompact -> Spacing.gutterWide
    else -> Spacing.gutterCompact
}

/** The B.4 status line text: "Running · 12s", "Completed · 3s", "Approval required", … */
@Composable
internal fun statusText(state: ToolDetailUiState): String {
    val label = stringResource(
        when (state.status) {
            ToolDetailStatus.WaitingForOutput, ToolDetailStatus.Running -> R.string.tool_status_running
            ToolDetailStatus.AwaitingApproval -> R.string.tool_status_approval
            ToolDetailStatus.Completed -> R.string.tool_status_completed
            ToolDetailStatus.Failed -> R.string.tool_status_failed
            ToolDetailStatus.Rejected -> R.string.tool_status_rejected
            ToolDetailStatus.Detached -> R.string.tool_status_detached
            ToolDetailStatus.NoResult -> R.string.tool_status_no_result
            ToolDetailStatus.Interrupted -> R.string.tool_status_interrupted
        },
    )
    val withDuration = when (state.status) {
        ToolDetailStatus.WaitingForOutput, ToolDetailStatus.Running, ToolDetailStatus.Completed, ToolDetailStatus.Failed -> true
        else -> false
    }
    val duration = state.durationMs
    return if (withDuration && duration != null) stringResource(R.string.tool_status_line, label, formatWorkDuration(duration)) else label
}

/** Status line (heading): status · duration at the start, the raw tool name end-aligned in mono. */
@Composable
private fun StatusLine(state: ToolDetailUiState, gutter: Dp, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 40.dp).padding(horizontal = gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            statusText(state),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Spacer(Modifier.width(Spacing.s))
        LtrText(text = state.toolName, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = Spacing.m, bottom = Spacing.xs).semantics { heading() },
    )
}

/** B.5 "No result" / "Interrupted" banner (`surfaceContainerHigh`). */
@Composable
private fun StatusBanner(state: ToolDetailUiState, gutter: Dp) {
    val (icon, text, tint) = when (state.status) {
        ToolDetailStatus.NoResult -> Triple(ClineIcons.Info, stringResource(R.string.tool_no_result_banner), MaterialTheme.colorScheme.onSurfaceVariant)
        ToolDetailStatus.Interrupted -> Triple(ClineIcons.Warning, stringResource(R.string.tool_interrupted_banner), ClineTheme.colors.warning.color)
        else -> return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = Spacing.xs),
    ) {
        Row(Modifier.padding(Spacing.m), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(Spacing.m))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun Strip(state: ToolDetailUiState, actions: ToolDetailActions, gutter: Dp, inFlight: KernelAction?) {
    KernelStrip(
        kernel = state.kernel,
        onAction = actions.onKernelAction,
        actionInFlight = inFlight,
        modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.xs),
    )
}

// ---- command layout ---------------------------------------------------------------------------

/**
 * CT B7, height-compact command layout: the status line ("Running · 12s") sits under the title
 * in the 64dp app bar, so the 48dp status row is composed only for the command tabs. On the
 * S25U (384dp tall at its display size) that row cost the terminal 2.4 of its 12 lines. Large
 * font scales keep the row, because two text lines no longer fit the bar.
 */
internal fun commandStatusInAppBar(heightCompactCommand: Boolean, fontScale: Float): Boolean =
    heightCompactCommand && fontScale <= STATUS_IN_BAR_MAX_FONT_SCALE

/** The height-compact status row: composed for the status text, or for the command tabs. */
internal fun showsCompactStatusRow(statusInAppBar: Boolean, commandCount: Int): Boolean = !statusInAppBar || commandCount > 1

/** Above this the title and the status line no longer fit one 64dp bar (28 + 16sp line heights). */
internal const val STATUS_IN_BAR_MAX_FONT_SCALE = 1.3f

@Composable
internal fun CommandBody(
    state: ToolDetailUiState,
    window: ToolWindow,
    actions: ToolDetailActions,
    padding: PaddingValues,
    dockedBar: Boolean,
    inFlight: KernelAction?,
    endRequests: Flow<Unit> = emptyFlow(),
) {
    val gutter = gutterFor(window)
    val holder = rememberSaveableStateHolder()
    // Without a docked bar the terminal reaches the window's bottom edge and pads inside (B.4 insets).
    val terminalInset = if (dockedBar) PaddingValues() else WindowInsets.bottomSafe.asPaddingValues()
    val terminal: @Composable (Modifier) -> Unit = { modifier ->
        val selected = state.selected
        if (selected != null) {
            holder.SaveableStateProvider(selected.index) {
                val scroll = rememberTerminalScrollState()
                LaunchedEffect(scroll, endRequests) {
                    endRequests.collect {
                        if (!scroll.pinned) {
                            // Re-pinning makes the terminal follow the tail and scroll to the end itself.
                            scroll.pinned = true
                        } else {
                            val count = scroll.listState.layoutInfo.totalItemsCount
                            if (count > 0) scroll.listState.scrollToItem(count - 1)
                        }
                    }
                }
                CommandTerminal(state, selected, scroll, terminalInset, actions, modifier)
            }
        }
    }
    // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
    val direction = LocalLayoutDirection.current
    val top = Modifier.padding(
        start = padding.calculateStartPadding(direction),
        top = padding.calculateTopPadding(),
        end = padding.calculateEndPadding(direction),
    )
    when (window) {
        ToolWindow.Expanded -> Row(top.fillMaxSize()) {
            Column(Modifier.width(COMMAND_LIST_WIDTH).fillMaxHeight()) {
                StatusLine(state, Spacing.l)
                Strip(state, actions, Spacing.l, inFlight)
                StatusBanner(state, Spacing.l)
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(state.commands, key = { _, c -> c.index }) { i, command ->
                        CommandListRow(command, selected = i == state.selectedCommand, onSelect = { actions.onSelectCommand(i) })
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (state.showTerminal && state.status != ToolDetailStatus.AwaitingApproval) {
                    OutputHeader(state, actions, Spacing.l)
                    terminal(Modifier.weight(1f).fillMaxWidth().padding(horizontal = Spacing.l))
                } else {
                    CommandSection(state, Spacing.l, Modifier.weight(1f))
                }
                CommandFooter(state, actions, Spacing.l, maxErrorHeight = ERROR_MAX_HEIGHT)
                if (state.showActionBar) ToolActionBar(state, actions)
            }
        }
        ToolWindow.HeightCompact -> Column(top.fillMaxSize()) {
            Strip(state, actions, gutter, inFlight)
            StatusBanner(state, gutter)
            val statusInBar = commandStatusInAppBar(heightCompactCommand = true, LocalDensity.current.fontScale)
            if (showsCompactStatusRow(statusInBar, state.commands.size)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = gutter), verticalAlignment = Alignment.CenterVertically) {
                    if (!statusInBar) {
                        Text(
                            statusText(state),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics { heading() },
                        )
                    }
                    if (state.commands.size > 1) {
                        if (!statusInBar) Spacer(Modifier.width(Spacing.m))
                        CommandTabs(state, actions, edgePadding = 0.dp, modifier = Modifier.weight(1f))
                    }
                }
            }
            if (state.showTerminal && state.status != ToolDetailStatus.AwaitingApproval) {
                terminal(Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter))
            } else {
                CommandSection(state, gutter, Modifier.weight(1f))
            }
            CommandFooter(state, actions, gutter, maxErrorHeight = 96.dp, showProceedError = false)
        }
        ToolWindow.Compact, ToolWindow.Pane -> Column(top.fillMaxSize().padding(bottom = if (dockedBar) padding.calculateBottomPadding() else 0.dp)) {
            StatusLine(state, gutter)
            Strip(state, actions, gutter, inFlight)
            StatusBanner(state, gutter)
            if (state.commands.size > 1) CommandTabs(state, actions, edgePadding = gutter)
            val terminalShown = state.showTerminal && state.status != ToolDetailStatus.AwaitingApproval
            CommandSection(state, gutter, if (terminalShown) Modifier.heightIn(max = COMMAND_MAX_HEIGHT) else Modifier.weight(1f))
            if (terminalShown) {
                OutputHeader(state, actions, gutter)
                terminal(Modifier.weight(1f).fillMaxWidth().padding(horizontal = gutter))
            }
            CommandFooter(state, actions, gutter, maxErrorHeight = ERROR_MAX_HEIGHT)
        }
    }
}

/**
 * "Command" overline + `$ <query>` (mono, selectable). The query is the call input's full command
 * (trial row 18), which can run to thousands of characters, so it scrolls inside the height
 * [modifier] gives it: capped above a terminal, the free height otherwise (the section then also
 * stands in for the spacer, empty or not).
 */
@Composable
private fun CommandSection(state: ToolDetailUiState, gutter: Dp, modifier: Modifier = Modifier) {
    val query = state.selected?.query.orEmpty()
    Column(modifier.padding(horizontal = gutter)) {
        if (query.isNotEmpty()) {
            Overline(stringResource(R.string.tool_overline_command))
            SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                LtrText(
                    text = query.lineSequence().joinToString("\n") { "$ $it" },
                    style = ClineMono.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    readAsWritten = false,
                )
            }
        }
    }
}

/** "Output" overline with the stream filter (only once stderr exists). */
@Composable
private fun OutputHeader(state: ToolDetailUiState, actions: ToolDetailActions, gutter: Dp) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = gutter).padding(bottom = Spacing.xs),
        verticalAlignment = Alignment.Bottom,
    ) {
        Overline(stringResource(R.string.tool_overline_output), Modifier.weight(1f))
        if (state.showFilter) StreamFilterRow(state.effectiveFilter, actions.onFilter)
    }
}

@Composable
private fun StreamFilterRow(selected: StreamFilter, onFilter: (StreamFilter) -> Unit) {
    val entries = StreamFilter.entries
    SingleChoiceSegmentedButtonRow {
        entries.forEachIndexed { index, filter ->
            val description = stringResource(
                when (filter) {
                    StreamFilter.All -> R.string.tool_filter_all_cd
                    StreamFilter.Stdout -> R.string.tool_filter_stdout_cd
                    StreamFilter.Stderr -> R.string.tool_filter_stderr_cd
                },
            )
            SegmentedButton(
                selected = selected == filter,
                onClick = { onFilter(filter) },
                shape = SegmentedButtonDefaults.itemShape(index, entries.size),
                icon = {},
                label = { Text(filterLabel(filter), style = MaterialTheme.typography.labelMedium) },
                modifier = Modifier.semantics { contentDescription = description },
            )
        }
    }
}

@Composable
private fun CommandTabs(state: ToolDetailUiState, actions: ToolDetailActions, edgePadding: Dp, modifier: Modifier = Modifier) {
    PrimaryScrollableTabRow(selectedTabIndex = state.selectedCommand, edgePadding = edgePadding, modifier = modifier) {
        state.commands.forEachIndexed { index, command ->
            Tab(
                selected = index == state.selectedCommand,
                onClick = { actions.onSelectCommand(index) },
                selectedContentColor = MaterialTheme.colorScheme.primary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { LtrText(text = stringResource(R.string.tool_command_query, command.queryLine), style = ClineMono.label, maxLines = 1) },
            )
        }
    }
}

/** Expanded layout: one 56dp row per command (`$ <query>`, its status, success / error glyph). */
@Composable
private fun CommandListRow(command: CommandView, selected: Boolean, onSelect: () -> Unit) {
    val status = stringResource(
        when {
            command.running -> R.string.tool_status_running
            command.success == false -> R.string.tool_status_failed
            else -> R.string.tool_status_completed
        },
    )
    ListItem(
        headlineContent = { LtrText(text = stringResource(R.string.tool_command_query, command.queryLine), style = ClineMono.body, maxLines = 1) },
        supportingContent = { Text(status) },
        trailingContent = when {
            command.running -> null
            command.success == false -> {
                { Icon(ClineIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
            }
            else -> {
                { Icon(ClineIcons.CheckCircle, contentDescription = null, tint = ClineTheme.colors.success.color) }
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.heightIn(min = 56.dp).selectable(selected = selected, onClick = onSelect, role = Role.Tab),
    )
}

/** One command's terminal: the B.4 terminal, or the "Waiting for output..." / "No output" placeholder. */
@Composable
private fun CommandTerminal(
    state: ToolDetailUiState,
    command: CommandView,
    scroll: TerminalScrollState,
    inset: PaddingValues,
    actions: ToolDetailActions,
    modifier: Modifier,
) {
    val snapshot = command.lines(state.effectiveFilter)
    val running = command.running && state.status.running
    if (snapshot.isEmpty) {
        TerminalPlaceholder(
            text = stringResource(if (running) R.string.tool_waiting_output else R.string.tool_no_output),
            cursor = running,
            modifier = modifier.padding(bottom = inset.calculateBottomPadding()),
        )
    } else {
        TerminalView(
            snapshot = snapshot,
            running = running,
            inline = false,
            wrap = state.wrapLines,
            state = scroll,
            contentPadding = inset,
            // The screen's checked copy (the same text, built off the main thread): a clip past
            // the Binder limit reports "Copy failed" instead of crashing (docs/07 §14).
            onCopyOutput = { actions.onCopy(CopyTarget.Output) },
            modifier = modifier,
        )
    }
}

/**
 * Empty terminal (B.5 "Waiting for output" / "Empty output"): the terminal ground with the
 * message in ANSI colour 8 and, while running, the blinking cursor block.
 */
@Composable
internal fun TerminalPlaceholder(text: String, cursor: Boolean, modifier: Modifier = Modifier) {
    val colors = ClineTheme.colors
    val reducedMotion = LocalReducedMotion.current
    val alpha: State<Float> = if (cursor && !reducedMotion) {
        rememberInfiniteTransition(label = "placeholder-cursor").animateFloat(
            initialValue = 1f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(tween(CURSOR_HALF_MS, easing = ClineMotion.Pulse), RepeatMode.Reverse),
            label = "placeholder-cursor-alpha",
        )
    } else {
        remember { mutableFloatStateOf(1f) }
    }
    val description = stringResource(R.string.tool_output_cd)
    LtrBlock {
        Surface(
            color = colors.terminalBackground,
            contentColor = colors.terminalForeground,
            shape = MaterialTheme.shapes.small,
            modifier = modifier.semantics { contentDescription = description },
        ) {
            Box(Modifier.fillMaxSize().padding(Spacing.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = ClineMono.body, color = colors.ansi[8])
                    if (cursor) {
                        Spacer(Modifier.width(Spacing.xs))
                        Box(
                            Modifier
                                .size(width = 6.dp, height = 12.dp)
                                // Read the animated value only in the draw phase so the row does not
                                // recompose on every frame while the command waits for output.
                                .graphicsLayer { this.alpha = alpha.value }
                                .background(colors.terminalCursor),
                        )
                    }
                }
            }
        }
    }
}

/** Below the terminal: the Error section, "Copy log path" (detached), and the proceed error line. */
@Composable
private fun ColumnScope.CommandFooter(
    state: ToolDetailUiState,
    actions: ToolDetailActions,
    gutter: Dp,
    maxErrorHeight: Dp,
    showProceedError: Boolean = true,
) {
    val error = state.errorText
    if (error != null) {
        Column(
            Modifier
                .padding(horizontal = gutter)
                .heightIn(max = if (state.showTerminal) maxErrorHeight else Dp.Unspecified)
                .verticalScroll(rememberScrollState()),
        ) {
            Overline(stringResource(R.string.tool_overline_error))
            SelectionContainer {
                Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    val logPath = state.detachedLogPath
    if (logPath != null && state.status == ToolDetailStatus.Detached) {
        TextButton(onClick = { actions.onCopy(CopyTarget.LogPath) }, modifier = Modifier.padding(horizontal = gutter - Spacing.s)) {
            Icon(if (state.copied == CopyTarget.LogPath) ClineIcons.Check else ClineIcons.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.s))
            Text(stringResource(if (state.copied == CopyTarget.LogPath) R.string.tool_copied_log_path else R.string.tool_copy_log_path))
        }
    }
    val proceed = state.proceed
    if (showProceedError && proceed is ProceedState.Error) {
        Text(
            proceedErrorText(proceed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.xs),
        )
    }
}

// ---- edit and generic layouts -----------------------------------------------------------------

@Composable
internal fun SectionsBody(
    state: ToolDetailUiState,
    window: ToolWindow,
    actions: ToolDetailActions,
    padding: PaddingValues,
    inFlight: KernelAction?,
) {
    val gutter = gutterFor(window)
    // DS §5.6: the Scaffold's start/end insets too (landscape 3-button navigation, a side cutout).
    val direction = LocalLayoutDirection.current
    Box(
        Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(direction), end = padding.calculateEndPadding(direction)),
        contentAlignment = Alignment.TopCenter,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().widthIn(max = Spacing.contentMaxWidth),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.l),
        ) {
            item(key = "label") {
                SelectionContainer {
                    Text(
                        toolLabelText(state.label, monoSpan(ClineMono.body)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
                    )
                }
            }
            item(key = "status") { StatusLine(state, gutter) }
            item(key = "strip") { Strip(state, actions, gutter, inFlight) }
            item(key = "banner") { StatusBanner(state, gutter) }
            if (state.details.isNotEmpty()) {
                item(key = "details") { DetailsSection(state, gutter) }
            }
            if (state.layout == ToolLayout.Edit) {
                editFiles(state, actions, gutter)
            } else {
                genericSections(state, actions, gutter)
            }
            state.errorText?.let { error ->
                item(key = "error") {
                    Column(Modifier.padding(horizontal = gutter)) {
                        Overline(stringResource(R.string.tool_overline_error))
                        SelectionContainer {
                            Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            val proceed = state.proceed
            if (proceed is ProceedState.Error) {
                item(key = "proceed-error") {
                    Text(proceedErrorText(proceed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.xs))
                }
            }
        }
    }
}

/** "Details" — the summary detail lines; queries, URLs and paths in mono (read, edit, search, web, command kinds). */
@Composable
private fun DetailsSection(state: ToolDetailUiState, gutter: Dp) {
    val mono = state.kind in MONO_DETAIL_KINDS
    Column(Modifier.padding(horizontal = gutter)) {
        Overline(stringResource(R.string.tool_overline_details))
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                state.details.forEach { line ->
                    if (mono) {
                        LtrText(text = line, style = ClineMono.body, color = MaterialTheme.colorScheme.onSurface, readAsWritten = false)
                    } else {
                        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.editFiles(state: ToolDetailUiState, actions: ToolDetailActions, gutter: Dp) {
    state.fileItems.forEach { file ->
        item(key = "file:${file.path}") { EditFileHeader(file, actions, gutter) }
        file.diffs.forEachIndexed { index, diff ->
            item(key = "diff:${diff.key}") {
                ToolFileDiffView(
                    diff = diff,
                    index = index,
                    count = file.diffs.size,
                    showHeader = false,
                    modifier = Modifier.padding(horizontal = gutter).padding(bottom = Spacing.s),
                )
            }
        }
    }
}

/** The edit layout's file header: no chevron (not collapsible), copy the absolute path (A.7), `+N -N`. */
@Composable
private fun EditFileHeader(file: ToolFileItemView, actions: ToolDetailActions, gutter: Dp) {
    val copied = rememberTransientFlag()
    ChangedFileHeader(
        path = file.path,
        additions = file.additions,
        deletions = file.deletions,
        onCopyPath = { actions.onCopyFilePath(file.path) { copied.trigger() } },
        expanded = null,
        copied = copied.on,
        // No chevron and no overflow: the path and the counts sit on the gutter, aligned
        // with the Details line and the hunk cards (B.4 edit layout wireframe).
        contentPadding = PaddingValues(horizontal = gutter),
        modifier = Modifier.padding(top = Spacing.m).semantics { heading() },
    )
}

private fun LazyListScope.genericSections(state: ToolDetailUiState, actions: ToolDetailActions, gutter: Dp) {
    val submit = state.submitMarkdown
    val output = state.outputText
    when {
        submit != null -> item(key = "submit") {
            MarkdownText(submit, Modifier.padding(horizontal = gutter, vertical = Spacing.s))
        }
        output != null -> item(key = "output") {
            Column(Modifier.padding(horizontal = gutter)) {
                Overline(stringResource(R.string.tool_overline_output))
                MonoSection(output, wrap = true)
            }
        }
    }
    if (state.images.isNotEmpty()) {
        item(key = "images") {
            GeneratedImages(
                cacheKeyPrefix = "tool:${state.toolCallId}",
                images = state.images,
                onExpand = { _, index -> actions.onOpenImage(index) },
                modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
            )
        }
    }
    if (state.otherMedia.isNotEmpty()) {
        item(key = "media") {
            GeneratedMediaList(
                media = state.otherMedia.mapIndexed { index, media -> media.asGeneratedMedia("${state.toolCallId}_media_$index") },
                cacheKeyPrefix = "tool:${state.toolCallId}:media",
                onExpandImage = null,
                modifier = Modifier.padding(horizontal = gutter, vertical = Spacing.s),
            )
        }
    }
    if (BuildConfig.DEBUG && state.inputJson.isNotEmpty()) {
        item(key = "input") {
            Column(Modifier.padding(horizontal = gutter)) {
                Overline(stringResource(R.string.tool_overline_input))
                MonoSection(state.inputJson, wrap = false)
            }
        }
    }
}

/** `surfaceContainer` mono block, `shapes.small`, selectable, no height cap on this screen. */
@Composable
private fun MonoSection(text: String, wrap: Boolean) {
    Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        LtrBlock {
            Box(
                Modifier
                    .then(if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                    .padding(Spacing.s),
            ) {
                SelectionContainer {
                    LtrText(text = text, style = ClineMono.body, color = MaterialTheme.colorScheme.onSurface, softWrap = wrap, readAsWritten = false)
                }
            }
        }
    }
}

// ---- screen-level states --------------------------------------------------------------------------

/** B.5 "Not found": `search_off`, "This tool call is no longer in the session.", "Back to conversation". */
@Composable
internal fun NotFoundState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    EmptyState(
        title = stringResource(R.string.tool_not_found),
        body = null,
        icon = ClineIcons.SearchOff,
        actionLabel = stringResource(R.string.tool_back_to_conversation),
        onAction = onBack,
        modifier = modifier.fillMaxSize(),
    )
}

/** `session.messages` failed: the kernel-down state while the Hub is away, otherwise the error with Retry. */
@Composable
internal fun LoadErrorState(state: ToolDetailUiState, actions: ToolDetailActions, modifier: Modifier = Modifier) {
    if (!state.kernel.connected) {
        KernelUnavailableState(state.kernel, actions.onKernelAction, modifier.fillMaxSize())
    } else {
        ErrorEmptyState(
            title = stringResource(R.string.tool_load_error),
            body = state.loadError,
            actionLabel = stringResource(R.string.changes_retry),
            onAction = actions.onRetry,
            modifier = modifier.fillMaxSize(),
        )
    }
}

private val COMMAND_LIST_WIDTH = 320.dp
private val ERROR_MAX_HEIGHT = 160.dp

/** The Command section above a terminal: its overline and some seven lines of `ClineMono.body`, then it scrolls. */
private val COMMAND_MAX_HEIGHT = 160.dp
private const val CURSOR_HALF_MS = 500
private val MONO_DETAIL_KINDS = setOf(ToolKind.Read, ToolKind.Edit, ToolKind.Search, ToolKind.Web, ToolKind.Command)
