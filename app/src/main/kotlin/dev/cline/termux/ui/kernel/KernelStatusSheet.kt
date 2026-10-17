package dev.cline.termux.ui.kernel

import android.content.Context
import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.appGraph
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DoctorSummary
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.data.session.DotTone
import dev.cline.termux.ui.LocalShellKernelUi
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.ChevronTurn
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ClineSwitch
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.DisclosureChevron
import dev.cline.termux.ui.components.DisclosureChevronDefaults
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LocalKernelSheetFocusReturn
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.PageScaffold
import dev.cline.termux.ui.components.StatusDot
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.joinSpokenLines
import dev.cline.termux.ui.components.openTermux
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.KernelReliability
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.KernelSettings
import dev.cline.termux.ui.navigation.LocalShellNavigator
import dev.cline.termux.ui.navigation.ShellNavigationIcon
import dev.cline.termux.ui.navigation.ShellNavigator
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The Kernel status sheet (onboarding-kernel.md E): a shell-level `ModalBottomSheet`, never a
 * route. The shell hosts it while its sheet request is set:
 * `ShellSlots(kernelStatusSheet = { request, dismiss -> KernelStatusSheet(open = true, onDismiss = dismiss, scrollToDiagnostics = request.diagnostics, restartRequest = request.restart) })`.
 * [scrollToDiagnostics] opens it on E.6 (the KernelStatePanel's "Kernel status" for the
 * start-failed variants). [restartRequest] (A4, `KernelSheetRequest.restart`) starts the E.7
 * restart once per value, confirm included. The ViewModel is scoped to the shell entry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KernelStatusSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    scrollToDiagnostics: Boolean = false,
    restartRequest: Long? = null,
) {
    if (!open) return
    val context = LocalContext.current
    val graph = context.appGraph
    val viewModel: KernelStatusViewModel = viewModel(key = SHEET_VM_KEY, factory = KernelStatusViewModel.factory(graph, context.applicationContext))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val navigator = LocalShellNavigator.current
    // E.5 snackbars ("Disconnected from the Cline Hub", "Cline kernel stopped", …) are raised
    // while the sheet is up, and the modal sheet's window covers the shell's snackbar host, so
    // the sheet shows them itself, over its own bottom edge.
    val sheetSnackbar = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbarHost provides sheetSnackbar) {
        KernelStatusSheetContent(viewModel, state, navigator, context, onDismiss, modifier, scrollToDiagnostics, sheetSnackbar, restartRequest)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KernelStatusSheetContent(
    viewModel: KernelStatusViewModel,
    state: KernelStatusUiState,
    navigator: ShellNavigator?,
    context: Context,
    onDismiss: () -> Unit,
    modifier: Modifier,
    scrollToDiagnostics: Boolean,
    sheetSnackbar: SnackbarHostState,
    restartRequest: Long?,
) {
    KernelStatusEffects(viewModel, scrollToDiagnostics, restartRequest)
    val paneTitle = stringResource(R.string.kernel_sheet_pane_title)
    // E.4: the sheet is at most 90 % of the window high, so it never runs under the status bar.
    // The scroll body gets what is left of that after the drag handle and header: its incoming
    // max height is the window minus those, so subtracting the other 10 % caps the whole sheet.
    val topGap = with(LocalDensity.current) { (LocalWindowInfo.current.containerSize.height * (1f - MAX_SHEET_HEIGHT_FRACTION)).toDp() }
    // DS §8.1.7: focus goes back to the KernelChip when it opened the sheet.
    val focusReturnSlot = LocalKernelSheetFocusReturn.current
    val returnFocus = remember(focusReturnSlot) { focusReturnSlot?.take() }
    ClineSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        paneTitle = paneTitle,
        returnFocus = returnFocus,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        titleContent = { KernelStatusHeader(state.appVersion) },
    ) {
        val scroll = rememberScrollState()
        BoxWithConstraints {
            Column(Modifier.fillMaxWidth().heightIn(max = (maxHeight - topGap).coerceAtLeast(0.dp)).verticalScroll(scroll)) {
                KernelStatusBody(
                    state = state,
                    inSheet = true,
                    scrollState = scroll,
                    scrollToDiagnostics = scrollToDiagnostics,
                    onAction = { action -> handleAction(action, viewModel, state, navigator, context, onDismiss) },
                    onKeepAwake = viewModel::setKeepAwake,
                    onReliability = {
                        onDismiss()
                        navigator?.openRoot(KernelReliability)
                    },
                    onKernelSettings = {
                        onDismiss()
                        navigator?.push(KernelSettings)
                    },
                    onFixProcesses = viewModel::fixProcesses,
                )
            }
            SnackbarHost(sheetSnackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    KernelStatusConfirm(state, viewModel)
}

private const val SHEET_VM_KEY = "kernel-status-sheet"

/** E.4 "max height 90 %". */
private const val MAX_SHEET_HEIGHT_FRACTION = 0.9f

/**
 * The settings `kernel` page (SP S1 "Kernel" row → this): the status sheet's content as a
 * pushed screen, minus its "Kernel settings" row. Host it as the shell's [KernelSettings]
 * destination (see [kernelSettingsDestination]).
 */
@Composable
fun KernelSettingsScreen(navigator: ShellNavigator?, modifier: Modifier = Modifier, inPane: Boolean = false) {
    val context = LocalContext.current
    val viewModel: KernelStatusViewModel = viewModel(factory = KernelStatusViewModel.factory(context.appGraph, context.applicationContext))
    val state by viewModel.ui.collectAsStateWithLifecycle()
    KernelStatusEffects(viewModel, scrollToDiagnostics = false)
    PageScaffold(
        title = stringResource(R.string.kernel_status_page_title),
        modifier = modifier,
        navigationIcon = navigator?.takeUnless { inPane }?.let { nav -> { ShellNavigationIcon(KernelSettings, nav) } },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = PAGE_MAX_WIDTH).fillMaxWidth().padding(bottom = Spacing.l)) {
                Box(Modifier.padding(horizontal = Spacing.sheetGutter, vertical = Spacing.s)) { KernelStatusHeader(state.appVersion) }
                KernelStatusBody(
                    state = state,
                    inSheet = false,
                    scrollToDiagnostics = false,
                    onAction = { action -> handleAction(action, viewModel, state, navigator, context, onDismiss = {}) },
                    onKeepAwake = viewModel::setKeepAwake,
                    onReliability = { navigator?.openRoot(KernelReliability) },
                    onKernelSettings = {},
                    onFixProcesses = viewModel::fixProcesses,
                )
            }
        }
    }
    KernelStatusConfirm(state, viewModel)
}

private val PAGE_MAX_WIDTH = 640.dp

/** Snackbars, the E.9 hand-off, the 5 s refresh while visible, the first-open diagnostics and a requested restart. */
@Composable
private fun KernelStatusEffects(viewModel: KernelStatusViewModel, scrollToDiagnostics: Boolean, restartRequest: Long? = null) {
    val snackbar = LocalSnackbarHost.current
    val resources = LocalResources.current
    val shellKernel = LocalShellKernelUi.current
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is StatusEvent.Snackbar -> launch { snackbar.showReplacing(event.text.resolve(resources)) }
                StatusEvent.OpenHubUpdate -> shellKernel?.onAction?.invoke(KernelAction.RestartKernel)
            }
        }
    }
    LaunchedEffect(viewModel) { viewModel.onOpened(scrollToDiagnostics) }
    LaunchedEffect(viewModel, restartRequest) { restartRequest?.let(viewModel::restartOnOpen) }
    LifecycleStartEffect(viewModel) {
        viewModel.setVisible(true)
        onStopOrDispose { viewModel.setVisible(false) }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.setVisible(false) } }
}

private fun handleAction(
    action: KernelStatusAction,
    viewModel: KernelStatusViewModel,
    state: KernelStatusUiState,
    navigator: ShellNavigator?,
    context: Context,
    onDismiss: () -> Unit,
) {
    when (action) {
        KernelStatusAction.OpenTermux -> if (!openTermux(context)) viewModel.reportOpenTermuxFailed()
        KernelStatusAction.FixKernel -> {
            onDismiss()
            navigator?.openRoot(KernelRepair.of(state.repairFocus ?: CheckId.Permission, pushed = true))
        }
        else -> viewModel.onAction(action)
    }
}

/** The E.7 Restart / Stop confirm (Fix processes asks inside the diagnostics block). */
@Composable
private fun KernelStatusConfirm(state: KernelStatusUiState, viewModel: KernelStatusViewModel) {
    val confirm = state.confirm ?: return
    val restart = confirm is StatusConfirm.Restart
    ConfirmDialog(
        title = stringResource(if (restart) R.string.kernel_restart_confirm_title else R.string.kernel_stop_confirm_title),
        text = confirm.text.resolve(),
        confirmLabel = stringResource(if (restart) R.string.kernel_restart else R.string.kernel_stop),
        dismissLabel = stringResource(R.string.diag_cancel),
        destructive = true,
        onConfirm = viewModel::confirm,
        onDismiss = viewModel::dismissConfirm,
    )
}

// ------------------------------------------------------------------------------- content

/** E.4 product header: the app's mark, "Cline Termux", "Version <x>" / "Version unavailable" (desktop Hub card). */
@Composable
fun KernelStatusHeader(appVersion: String?, modifier: Modifier = Modifier) {
    Row(modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically) {
        Icon(ClineIcons.AppLogo, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(Spacing.m))
        Column {
            Text(stringResource(R.string.kernel_sheet_product), style = MaterialTheme.typography.titleMedium)
            Text(
                if (appVersion != null) stringResource(R.string.kernel_sheet_version, appVersion) else stringResource(R.string.kernel_sheet_version_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Everything under the header (E.4), in the E.11 focus order: hub line, detail rows, "More
 * details", the update row, "Keep Termux awake", "Background reliability", "Kernel settings"
 * (sheet only), the one primary, the action rows (Stop kernel always last), diagnostics.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ColumnScope.KernelStatusBody(
    state: KernelStatusUiState,
    inSheet: Boolean,
    scrollToDiagnostics: Boolean,
    onAction: (KernelStatusAction) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onReliability: () -> Unit,
    onKernelSettings: () -> Unit,
    onFixProcesses: () -> Unit,
    /** The scroll state of the column this body is in; Run diagnostics scrolls it (E.7). */
    scrollState: ScrollState? = null,
) {
    HorizontalDivider(Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
    HubLine(state)
    state.rows.forEach { StatusValueRow(it) }
    if (state.moreRows.isNotEmpty()) MoreDetails(state.moreRows)
    HorizontalDivider(Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
    state.updateRow?.let { UpdateRow(it, enabled = !state.actionsLocked) { onAction(KernelStatusAction.Restart) } }
    KeepAwakeRow(state, onKeepAwake)
    ReliabilityRow(state.reliability, onReliability)
    if (inSheet) NavigationRow(stringResource(R.string.kernel_settings_row), onKernelSettings)
    HorizontalDivider(Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
    PrimaryArea(state, onAction)
    state.rowActions.forEach { action -> ActionRow(action, state, onAction) }

    var diagnosticsTop by remember { mutableStateOf<Float?>(null) }
    var askedForDiagnostics by remember { mutableStateOf(scrollToDiagnostics) }
    val diagnosticsShown = state.diagnostics != DiagnosticsUi.Idle
    LaunchedEffect(state.busy) { if (state.busy == KernelStatusAction.RunDiagnostics) askedForDiagnostics = true }
    if (diagnosticsShown) {
        HorizontalDivider(Modifier.padding(vertical = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant)
        DiagnosticsBlock(
            ui = state.diagnostics,
            modifier = Modifier
                .padding(horizontal = Spacing.sheetGutter)
                .onGloballyPositioned { diagnosticsTop = it.positionInParent().y },
            fixing = state.fixing,
            onFix = onFixProcesses,
        )
        LaunchedEffect(askedForDiagnostics, state.diagnostics, diagnosticsTop) {
            // E.7: "the sheet scrolls so the block's overline is at the top" (as far as the
            // content below it allows).
            val top = diagnosticsTop
            if (askedForDiagnostics && scrollState != null && top != null) scrollState.animateScrollTo(top.roundToInt())
        }
    }
    Spacer(Modifier.height(Spacing.l))
}

/** E.4 hub line: the DS §1.6 dot, "Cline Hub @<port>", and the `error` second line. */
@Composable
private fun HubLine(state: KernelStatusUiState) {
    val line1 = state.hubLine.resolve()
    val line2 = state.errorLine?.resolve()
    val status = state.statusLabel.resolve()
    val spokenHub = stringResource(R.string.hub_line_cd, state.hubPortSpoken ?: stringResource(R.string.kernel_status_unknown_port), status)
    val resources = LocalResources.current
    val spoken = joinSpokenLines(
        listOfNotNull(spokenHub, line2),
        sentence = { first, second -> resources.getString(R.string.kernel_cd_two_lines, first, second) },
        continued = { first, second -> resources.getString(R.string.kernel_cd_two_lines_continued, first, second) },
    )
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = Spacing.sheetGutter - Spacing.s)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(tone = state.dot, pulsing = state.dotPulsing)
        Column(Modifier.weight(1f)) {
            Text(line1, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (line2 != null) {
                Text(line2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One E.5 detail row: label `bodyMedium` `onSurfaceVariant`, value `ClineMono.small`, merged
 * for TalkBack ("Process, pid 23817") with a "Copy" custom action; long-press copies the
 * value with a haptic tick and "Copied <label>" (below API 33; the system confirms above).
 */
@Composable
private fun StatusValueRow(row: StatusRow) {
    val label = stringResource(row.label)
    val value = row.value.resolve()
    val detail = row.detail?.resolve()
    val copier = rememberPlainCopier()
    val haptic = LocalHapticFeedback.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val copiedText = stringResource(R.string.kernel_copied_value, label)
    val copyLabel = stringResource(R.string.kernel_status_copy_action)
    val copy = {
        copier.copy(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Unit else scope.launch { snackbar.showReplacing(copiedText) }
        Unit
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pointerInput(value) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    copy()
                })
            }
            .semantics(mergeDescendants = true) {
                customActions = listOf(CustomAccessibilityAction(copyLabel) { copy(); true })
            }
            .padding(horizontal = Spacing.sheetGutter, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 72.dp, max = 120.dp),
        )
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f)) {
            LtrText(text = value, style = ClineMono.small, color = MaterialTheme.colorScheme.onSurface, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** "More details" disclosure: Protocol, Hub ID, Event log. */
@Composable
private fun MoreDetails(rows: List<StatusRow>) {
    var open by rememberSaveable { mutableStateOf(false) }
    val word = stringResource(if (open) R.string.kernel_status_expanded else R.string.kernel_status_collapsed)
    val reduced = ClineTheme.reducedMotion
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(role = Role.Button) { open = !open }
            .semantics { stateDescription = word }
            .padding(horizontal = Spacing.sheetGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.kernel_more_details), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        DisclosureChevron(expanded = open, size = DisclosureChevronDefaults.IconButtonSize, tint = MaterialTheme.colorScheme.primary, turn = ChevronTurn.DownToUp)
    }
    AnimatedVisibility(
        visible = open,
        enter = if (reduced) EnterTransition.None else expandVertically(tween(ClineMotion.Short4)) + fadeIn(tween(ClineMotion.Short4)),
        exit = if (reduced) ExitTransition.None else shrinkVertically(tween(ClineMotion.Short4)) + fadeOut(tween(ClineMotion.Short4)),
    ) {
        Column { rows.forEach { StatusValueRow(it) } }
    }
}

@Composable
private fun transparentListColors() = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0f))

/** E.5 update row (about-view "Updates" pattern): title, one sentence, trailing "Restart". */
@Composable
private fun UpdateRow(row: UpdateRowUi, enabled: Boolean, onRestart: () -> Unit) {
    ListItem(
        colors = transparentListColors(),
        headlineContent = { Text(stringResource(R.string.kernel_update_title, row.release)) },
        supportingContent = { Text(stringResource(R.string.kernel_update_body, row.release)) },
        trailingContent = { TextButton(onClick = onRestart, enabled = enabled) { Text(stringResource(R.string.kernel_update_restart)) } },
    )
}

/** "Keep Termux awake": the whole row toggles; disabled while the kernel is not `Ready`. */
@Composable
private fun KeepAwakeRow(state: KernelStatusUiState, onChange: (Boolean) -> Unit) {
    val error = state.wakeLockError?.resolve()
    ListItem(
        modifier = Modifier.focusRing(RectangleShape, inset = true).toggleable(
            value = state.keepAwake,
            enabled = state.keepAwakeEnabled,
            role = Role.Switch,
            onValueChange = onChange,
        ),
        colors = transparentListColors(),
        headlineContent = { Text(stringResource(R.string.kernel_keep_awake)) },
        supportingContent = {
            if (error != null) {
                Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                Text(stringResource(R.string.kernel_keep_awake_body))
            }
        },
        trailingContent = { ClineSwitch(checked = state.keepAwake, onCheckedChange = null, enabled = state.keepAwakeEnabled) },
    )
}

/**
 * "Background reliability" with "N of M done" and a warning dot while a Required item is not
 * done. The dot is labelled "Required step not done", so the merged row reads "Background
 * reliability, Required step not done, 4 of 5 done".
 */
@Composable
private fun ReliabilityRow(summary: ReliabilitySummary?, onOpen: () -> Unit) {
    val progress = summary?.let { stringResource(R.string.reliability_progress, it.doneCount, it.countedTotal) }
    ListItem(
        modifier = Modifier.focusRing(RectangleShape, inset = true).clickable(role = Role.Button, onClick = onOpen).semantics(mergeDescendants = true) { },
        colors = transparentListColors(),
        headlineContent = { Text(stringResource(R.string.kernel_reliability_row)) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (summary?.requiredMissing == true) {
                    // Colour is never the only signal (DS §8.1.3): the dot carries its words.
                    StatusDot(
                        tone = DotTone.Warning,
                        boxSize = 16.dp,
                        contentDescription = stringResource(R.string.kernel_reliability_required_missing),
                    )
                }
                if (progress != null) Text(progress, style = MaterialTheme.typography.labelMedium)
                Icon(ClineIcons.ChevronRight, contentDescription = null)
            }
        },
    )
}

@Composable
private fun NavigationRow(title: String, onOpen: () -> Unit) {
    ListItem(
        modifier = Modifier.focusRing(RectangleShape, inset = true).clickable(role = Role.Button, onClick = onOpen),
        colors = transparentListColors(),
        headlineContent = { Text(title) },
        trailingContent = { Icon(ClineIcons.ChevronRight, contentDescription = null) },
    )
}

@StringRes
private fun KernelStatusAction.label(): Int = when (this) {
    KernelStatusAction.Start -> R.string.kernel_start
    KernelStatusAction.TryAgain -> R.string.kernel_try_again
    KernelStatusAction.Connect -> R.string.kernel_connect
    KernelStatusAction.Restart -> R.string.kernel_restart
    KernelStatusAction.FixKernel -> R.string.kernel_fix_kernel
    KernelStatusAction.OpenTermux -> R.string.kernel_open_termux
    KernelStatusAction.RunDiagnostics -> R.string.kernel_run_diagnostics
    KernelStatusAction.Disconnect -> R.string.kernel_disconnect
    KernelStatusAction.Stop -> R.string.kernel_stop
}

/** E.5 busy labels ("Start kernel" → "Starting…", …). */
@StringRes
private fun KernelStatusAction.busyLabel(): Int = when (this) {
    KernelStatusAction.Start, KernelStatusAction.TryAgain -> R.string.kernel_starting
    KernelStatusAction.Connect -> R.string.kernel_step_connecting
    KernelStatusAction.Restart -> R.string.kernel_restarting
    KernelStatusAction.Stop -> R.string.kernel_stopping
    KernelStatusAction.RunDiagnostics -> R.string.kernel_running_diagnostics
    else -> label()
}

@Composable
private fun KernelStatusAction.icon(): ImageVector? = when (this) {
    KernelStatusAction.OpenTermux -> ClineIcons.Terminal
    KernelStatusAction.RunDiagnostics -> ClineIcons.Troubleshoot
    KernelStatusAction.Disconnect -> ClineIcons.LinkOff
    KernelStatusAction.Connect -> ClineIcons.Link
    KernelStatusAction.Stop -> ClineIcons.StopCircle
    KernelStatusAction.Restart -> ClineIcons.RestartAlt
    else -> null
}

/**
 * The one primary `Button` (full width on compact, end-aligned on medium+), or the 16dp
 * spinner + step/phase label while work runs without one, then the inline error (assertive).
 */
@Composable
private fun PrimaryArea(state: KernelStatusUiState, onAction: (KernelStatusAction) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = Spacing.sheetGutter, vertical = Spacing.s)) {
        val wide = maxWidth >= 560.dp
        val primary = state.primary
        when {
            primary != null -> {
                val busy = state.busy == primary
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Button(
                        onClick = { onAction(primary) },
                        enabled = !state.actionsLocked,
                        modifier = if (wide) Modifier else Modifier.fillMaxWidth(),
                    ) {
                        if (busy) {
                            InlineSpinner()
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(primary.busyLabel()), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        } else {
                            primary.icon()?.let {
                                Icon(it, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(Spacing.s))
                            }
                            Text(stringResource(primary.label()))
                        }
                    }
                }
            }
            state.primarySpinner -> Row(
                Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (wide) Arrangement.End else Arrangement.Start,
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.s))
                Text(
                    (state.statusDetail ?: state.statusLabel).resolve(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (state.actionError.isNotEmpty()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sheetGutter)
                .padding(bottom = Spacing.s)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Assertive },
        ) {
            state.actionError.forEach { line ->
                Text(line.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** An E.4 action row: 56dp, leading 24dp icon (spinner while busy), headline `bodyLarge`; Stop in `error`. */
@Composable
private fun ActionRow(action: KernelStatusAction, state: KernelStatusUiState, onAction: (KernelStatusAction) -> Unit) {
    val busy = state.busy == action
    val enabled = !state.actionsLocked
    val destructive = action == KernelStatusAction.Stop
    val tint = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val text = stringResource(if (busy) action.busyLabel() else action.label())
    ListItem(
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(enabled = enabled, role = Role.Button) { onAction(action) }
            .alpha(if (enabled || busy) 1f else DISABLED_ALPHA),
        colors = transparentListColors(),
        leadingContent = {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    action.icon()?.let { Icon(it, contentDescription = null, tint = tint) }
                }
            }
        },
        headlineContent = {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = if (busy) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
        },
    )
}

private const val DISABLED_ALPHA = 0.38f

// ------------------------------------------------------------------------------- previews

private val previewInfo = HubInfo(
    url = "ws://127.0.0.1:25463/hub", port = 25463, pid = 23817, startedAt = "2026-10-03T08:00:00Z",
    coreVersion = "0.0.90", buildId = "source-0.0.90", protocolVersion = "v1", hubId = "hub_7f3a", release = "3.0.68-termux.2", cliVersion = "3.0.68",
)
private val previewPort = PortVersion(release = "3.0.68-termux.2", cline = "3.0.68", tag = "v3.0.68-termux.2")
private val previewNow = java.time.Instant.parse("2026-10-03T10:14:00Z").toEpochMilli()

private fun previewState(state: KernelState, local: StatusLocal = StatusLocal(), commands: Boolean = true, lifetime: String? = "3.0.68-termux.2") =
    KernelStatusProjection.project(
        StatusInputs(
            state = state, commandsAvailable = commands, lastInfo = previewInfo, portVersion = previewPort,
            hubLifetimeRelease = lifetime, keepAwake = true, reliability = ReliabilitySummary(3, 5, requiredMissing = true),
            nowWallMs = previewNow, appVersion = "0.4.0",
        ),
        local,
    )

@Composable
private fun SheetPreview(state: KernelStatusUiState) {
    ComponentPreview(padding = false) {
        Column(Modifier.fillMaxWidth()) {
            Box(Modifier.padding(Spacing.sheetGutter)) { KernelStatusHeader(state.appVersion) }
            KernelStatusBody(state, inSheet = true, scrollToDiagnostics = false, onAction = {}, onKeepAwake = {}, onReliability = {}, onKernelSettings = {}, onFixProcesses = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun KernelStatusReadyPreview() {
    SheetPreview(previewState(KernelState.Ready(previewInfo, HubLiveStatus(false, 1, 0, 4812, idle = false))))
}

@LightDarkPreviews
@Composable
private fun KernelStatusUpdateRowPreview() {
    SheetPreview(previewState(KernelState.Ready(previewInfo, HubLiveStatus(false, 0, 0, 4812, idle = true)), lifetime = "3.0.68-termux.1"))
}

@LightDarkPreviews
@Composable
private fun KernelStatusStartingPreview() {
    SheetPreview(previewState(KernelState.Starting(StartStep.StartingHub, 0L)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusRestartingPreview() {
    SheetPreview(previewState(KernelState.Restarting(previewInfo, RestartPhase.Replacing), StatusLocal(busy = KernelStatusAction.Restart)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusReconnectingPreview() {
    SheetPreview(previewState(KernelState.Reconnecting(previewInfo, 2)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusDrainingPreview() {
    SheetPreview(previewState(KernelState.Draining(previewInfo)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusAsleepPreview() {
    SheetPreview(previewState(KernelState.HubDown(DownReason.Asleep, null, null)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusStoppedPreview() {
    SheetPreview(previewState(KernelState.HubDown(DownReason.Stopped, null, null)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusStoppedInTermuxPreview() {
    SheetPreview(previewState(KernelState.HubDown(DownReason.StoppedInTermux, null, null)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusDisconnectedPreview() {
    SheetPreview(previewState(KernelState.HubDown(DownReason.Disconnected, null, null), StatusLocal(busy = KernelStatusAction.Connect)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusStartFailedPreview() {
    val doctor = DoctorSummary("3.0.68", "0.0.90", false, null, null, emptyList(), listOf(24102), 1, emptyList())
    SheetPreview(
        previewState(
            KernelState.HubDown(DownReason.StartFailed, "Timed out after 15000ms waiting for detached hub startup.", doctor),
            StatusLocal(diagnostics = KernelDiagnostics.project(DoctorOutcome.Report(doctor))),
        ),
    )
}

@LightDarkPreviews
@Composable
private fun KernelStatusCrashLoopPreview() {
    SheetPreview(previewState(KernelState.HubDown(DownReason.CrashLoop, null, null)))
}

@LightDarkPreviews
@Composable
private fun KernelStatusProtocolPreview() {
    SheetPreview(previewState(KernelState.ProtocolUnsupported(previewInfo.copy(minClientProtocolVersion = "v2"))))
}

@LightDarkPreviews
@Composable
private fun KernelStatusAuthFailedPreview() {
    SheetPreview(previewState(KernelState.AuthFailed(previewInfo), StatusLocal(actionError = listOf(UiText.Res(R.string.kernel_restart_failed)))))
}

@LightDarkPreviews
@Composable
private fun KernelStatusSetupNeededPreview() {
    SheetPreview(previewState(KernelState.PermissionMissing))
}

@LightDarkPreviews
@Composable
private fun KernelStatusDegradedPreview() {
    SheetPreview(previewState(KernelState.Ready(previewInfo, null), commands = false))
}

@LightDarkPreviews
@Composable
private fun KernelStatusWakeLockErrorPreview() {
    SheetPreview(
        previewState(
            KernelState.Ready(previewInfo, HubLiveStatus(false, 0, 0, 4812, idle = true)),
            StatusLocal(wakeLockFailure = UiText.Raw("termux-wake-lock: not found"), busy = KernelStatusAction.RunDiagnostics, diagnostics = DiagnosticsUi.Loading),
        ),
    )
}
