package dev.cline.termux.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DoctorSummary
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionAnnouncer
import dev.cline.termux.ui.components.CommandBlock
import dev.cline.termux.ui.components.LtrBlock
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.DiagnosticsBlock
import dev.cline.termux.ui.kernel.DiagnosticsUi
import dev.cline.termux.ui.kernel.HubMismatch
import dev.cline.termux.ui.kernel.HubUpdateDialogState
import dev.cline.termux.ui.kernel.UiText
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.components.findActivity
import dev.cline.termux.ui.components.openTermux
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing

/** Every user action of the checklist; the screen wires them to the ViewModel and the system. */
class KernelSetupActions(
    val onBack: (() -> Unit)? = null,
    val onRequestPermission: () -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onOpenTermux: () -> Unit = {},
    val onOpenTermuxSettings: () -> Unit = {},
    val onInstallTermux: () -> Unit = {},
    val onCheckAgain: (CheckId) -> Unit = {},
    val onStartKernel: () -> Unit = {},
    val onRunDiagnostics: () -> Unit = {},
    val onFixProcesses: () -> Unit = {},
    val onRestartHub: () -> Unit = {},
    val onToggleSummary: () -> Unit = {},
    val onContinue: () -> Unit = {},
)

/**
 * A, wired: the permission launcher (row 2), the system intents, `ON_RESUME` re-checks and
 * the back arrow. Navigation events are collected by the caller (the onboarding graph).
 */
@Composable
fun KernelSetupScreen(viewModel: KernelSetupViewModel, onBack: (() -> Unit)?) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val rationale = context.findActivity()?.shouldShowRequestPermissionRationale(TermuxRunner.PERMISSION) == true
        viewModel.onPermissionResult(granted, rationale)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }
    val actions = remember(viewModel, onBack, context) {
        KernelSetupActions(
            onBack = onBack,
            onRequestPermission = { permissionLauncher.launch(TermuxRunner.PERMISSION) },
            onOpenAppSettings = {
                viewModel.onLeftForFix()
                OnboardingIntents.openOwnAppSettings(context)
            },
            onOpenTermux = {
                viewModel.onLeftForFix()
                openTermux(context)
            },
            onOpenTermuxSettings = {
                viewModel.onLeftForFix()
                OnboardingIntents.openTermuxSettings(context)
            },
            onInstallTermux = viewModel::installTermux,
            onCheckAgain = viewModel::recheckFrom,
            onStartKernel = viewModel::startKernel,
            onRunDiagnostics = viewModel::runDiagnostics,
            onFixProcesses = viewModel::fixProcesses,
            onRestartHub = viewModel::restartHub,
            onToggleSummary = viewModel::toggleSummary,
            onContinue = viewModel::onContinue,
        )
    }
    KernelSetupContent(ui, actions)
}

/** A, stateless (previews render every state through it). */
@Composable
fun KernelSetupContent(ui: KernelSetupUiState, actions: KernelSetupActions) {
    val repair = ui.variant is SetupVariant.Repair
    val resources = LocalResources.current
    val focusRequesters = remember { CheckId.entries.associateWith { FocusRequester() } }
    LaunchedEffect(ui.focusRequest) {
        ui.focusRequest?.row?.let { focusRequesters[it]?.let { requester -> runCatching { requester.requestFocus() } } }
    }
    OnboardingScaffold(
        surface = OnboardingSurface.Panel,
        onBack = actions.onBack,
        actionBar = if (repair) {
            null
        } else {
            {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Button(onClick = actions.onContinue, enabled = ui.canContinue && !ui.continuing) {
                        Text(stringResource(R.string.onboarding_continue))
                    }
                }
            }
        },
    ) {
        PanelTitle(stringResource(if (repair) R.string.kernel_setup_repair_title else R.string.kernel_setup_title))
        PanelDescription(stringResource(if (repair) R.string.kernel_setup_repair_body else R.string.kernel_setup_body))
        val announcement = ui.announcement?.let { a ->
            if (a.row == null) {
                stringResource(R.string.kernel_connected_announce)
            } else {
                stringResource(R.string.setup_row_announce, resources.getString(KernelSetupRows.title(a.row)), resources.getString(KernelSetupRows.statusWord(a.status ?: CheckStatus.Waiting)))
            }
        }
        LiveRegionAnnouncer(announcement)
        if (!repair) {
            Spacer(Modifier.height(Spacing.l))
            val firstResolved = ui.checks.firstOrNull()?.status?.let { it != CheckStatus.Checking } == true
            Text(
                if (firstResolved) stringResource(R.string.kernel_setup_progress, ui.readyCount, ui.checks.size) else stringResource(R.string.setup_checking),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Spacing.s))
            LinearProgressIndicator(
                progress = { if (ui.checks.isEmpty()) 0f else ui.readyCount.toFloat() / ui.checks.size },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Spacing.s))
        val openIndex = ui.checks.indexOfFirst { it.status != CheckStatus.Passed }
        ui.checks.forEachIndexed { index, row ->
            val collapsed = repair && ui.okAbove > 0 && index < ui.okAbove && !ui.summaryExpanded
            if (repair && ui.okAbove > 0 && index == 0) OkSummary(ui.okAbove, ui.summaryExpanded, actions.onToggleSummary)
            if (!collapsed) {
                CheckRow(
                    row = row,
                    ui = ui,
                    actions = actions,
                    showFix = index == openIndex,
                    modifier = Modifier
                        .focusRequester(focusRequesters.getValue(row.id))
                        .focusable(),
                )
            }
        }
    }
}

@Composable
private fun OkSummary(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (expanded) R.string.setup_state_expanded else R.string.setup_state_collapsed)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = stringResource(if (expanded) R.string.setup_collapse_summary else R.string.setup_expand_summary), onClick = onToggle)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StatusGlyph(CheckStatus.Passed) }
        Spacer(Modifier.width(Spacing.l))
        Text(
            UiText.Plural(R.plurals.kernel_setup_ok_summary, count, listOf(count)).resolve(),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (expanded) ClineIcons.UnfoldLess else ClineIcons.UnfoldMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CheckRow(row: SetupCheckUi, ui: KernelSetupUiState, actions: KernelSetupActions, showFix: Boolean, modifier: Modifier = Modifier) {
    val extra = buildList {
        if (row.id == CheckId.Hub && row.status == CheckStatus.Checking) {
            val label = ui.startLabel?.resolve()
            if (label != null && ui.startElapsedSec != null) add(stringResource(R.string.check_hub_step_elapsed, label, ui.startElapsedSec))
            if (ui.slowStart) add(stringResource(R.string.check_hub_slow))
        }
    }
    val fix = row.fix.takeIf { showFix && (row.status == CheckStatus.NeedsAction || row.status == CheckStatus.Failed) }
    SetupCheckRow(
        title = stringResource(KernelSetupRows.title(row.id)),
        status = row.status,
        supporting = row.supporting.resolve(),
        extraLines = extra,
        modifier = modifier,
        fixArea = fix?.let { f -> { FixArea(row.id, f, ui, actions) } },
    )
}

@Composable
private fun ColumnScope.FixArea(id: CheckId, fix: CheckFix, ui: KernelSetupUiState, actions: KernelSetupActions) {
    val checking = ui.checkingRow == id
    val checkAgain: @Composable () -> Unit = {
        BusyTextButton(
            label = stringResource(R.string.setup_check_again),
            busy = checking,
            busyLabel = stringResource(R.string.setup_checking),
            onClick = { actions.onCheckAgain(id) },
        )
    }
    when (fix) {
        CheckFix.InstallTermux -> Button(onClick = actions.onInstallTermux) { Text(stringResource(R.string.check_termux_install)) }
        CheckFix.OpenTermuxSettings -> FilledTonalButton(onClick = actions.onOpenTermuxSettings) { Text(stringResource(R.string.check_termux_open_settings)) }
        is CheckFix.RequestPermission -> when (fix.mode) {
            PermissionMode.Ask, PermissionMode.AskAgain -> Button(onClick = actions.onRequestPermission) { Text(stringResource(R.string.check_permission_allow)) }
            PermissionMode.OpenSettings -> {
                FilledTonalButton(onClick = actions.onOpenAppSettings) { Text(stringResource(R.string.check_permission_open_settings)) }
                Text(
                    stringResource(R.string.check_permission_path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is CheckFix.RunInTermux -> {
            CommandBlock(fix.command)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                FilledTonalButton(onClick = actions.onOpenTermux) { Text(stringResource(R.string.setup_open_termux)) }
                checkAgain()
            }
        }
        CheckFix.OpenTermux -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FilledTonalButton(onClick = actions.onOpenTermux) { Text(stringResource(R.string.setup_open_termux)) }
            checkAgain()
        }
        CheckFix.StartKernel -> Button(onClick = actions.onStartKernel) { Text(stringResource(R.string.kernel_start)) }
        is CheckFix.StartFailed -> {
            fix.detail?.let { detail ->
                if (detail is UiText.Raw) {
                    // Kernel output (the Hub's lastError) is data: Geist Mono, forced LTR (DS §2.2, §8.2.6).
                    LtrBlock {
                        SelectionContainer {
                            Text(
                                detail.resolve(),
                                style = ClineMono.small,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    // The app's own sentence is prose: body face, the locale's reading direction.
                    Text(detail.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = actions.onStartKernel) { Text(stringResource(R.string.kernel_try_again)) }
                val diagnosticsShown = ui.diagnostics is DiagnosticsUi.Loaded || ui.diagnostics is DiagnosticsUi.Error
                if (!fix.timedOut && !diagnosticsShown) {
                    val running = ui.diagnostics == DiagnosticsUi.Loading
                    FilledTonalButton(onClick = actions.onRunDiagnostics, enabled = !running) {
                        if (running) {
                            InlineSpinner()
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.kernel_running_diagnostics))
                        } else {
                            Text(stringResource(R.string.kernel_run_diagnostics))
                        }
                    }
                }
            }
            DiagnosticsBlock(ui = ui.diagnostics, fixing = ui.fixing, onFix = actions.onFixProcesses)
        }
        CheckFix.UpdateHub -> {
            val update = ui.hubUpdate
            update?.body?.forEach { line ->
                Text(line.resolve(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            update?.error?.forEach { line ->
                Text(line.resolve(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val busy = update?.busy == true
            Button(onClick = actions.onRestartHub, enabled = !busy) {
                if (busy) {
                    InlineSpinner()
                    Spacer(Modifier.width(Spacing.s))
                }
                Text((update?.confirmLabel ?: UiText.Res(R.string.kernel_restart)).resolve())
            }
        }
    }
}

// ---- previews -----------------------------------------------------------------------------------

private val previewDoctor = DoctorSummary(
    cliVersion = "3.0.68", coreVersion = "0.0.90", hubHealthy = false, hubPid = null, hubUptime = null,
    listeningPids = emptyList(), staleHubPids = listOf(23817), hubStartupLocks = 1, staleCliPids = emptyList(),
)

private fun previewState(
    checks: List<SetupCheckUi>,
    variant: SetupVariant = SetupVariant.FirstRun,
    diagnostics: DiagnosticsUi = DiagnosticsUi.Idle,
    elapsed: Int? = null,
    slow: Boolean = false,
    hubUpdate: HubUpdateDialogState? = null,
) = KernelSetupUiState(
    variant = variant,
    checks = checks,
    readyCount = KernelSetupRows.readyCount(checks),
    startLabel = if (elapsed != null) UiText.Res(R.string.kernel_step_starting_hub) else null,
    startElapsedSec = elapsed,
    slowStart = slow,
    canContinue = KernelSetupRows.readyCount(checks) == 5,
    diagnostics = diagnostics,
    hubUpdate = hubUpdate,
)

private val presence = dev.cline.termux.core.kernel.termux.TermuxPresence.Installed(118, "0.118.3")
private val portVersion = dev.cline.termux.core.kernel.termux.PortVersion("3.0.68-termux.2", "3.0.68", "v3.0.68-termux.2")
private val facts = SetupFacts(presence, permissionGranted = true, portVersion = portVersion)
private val previewInfo = dev.cline.termux.core.kernel.HubInfo(
    url = "ws://127.0.0.1:25463/hub", port = 25463, pid = 23817, startedAt = "2026-10-03T08:00:00Z",
    coreVersion = "0.0.90", buildId = "b1", protocolVersion = "v1",
)

private fun rowsFor(state: dev.cline.termux.core.kernel.KernelState, f: SetupFacts = facts) = KernelSetupRows.rows(state, f)

@LightDarkPreviews
@Composable
private fun SetupCheckingPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.Unknown)), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupTermuxMissingPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.TermuxMissing(false))), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupPermissionPreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.PermissionMissing, facts.copy(permissionMode = PermissionMode.OpenSettings))),
            KernelSetupActions(onBack = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun SetupExternalAppsPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.ExternalAppsBlocked("allow-external-apps"))), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupPortMissingPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.PortMissing)), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupPortOldPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.PortTooOld("3.0.68-termux.1"))), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupNotSetUpPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.TermuxNotSetUp)), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupHubIdlePreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.HubDown(dev.cline.termux.core.kernel.DownReason.Unreachable, null, null))),
            KernelSetupActions(onBack = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun SetupHubStartingPreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(
                rowsFor(dev.cline.termux.core.kernel.KernelState.Starting(dev.cline.termux.core.kernel.StartStep.StartingHub, 0L)),
                elapsed = 22,
                slow = true,
            ),
            KernelSetupActions(onBack = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun SetupHubFailedPreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(
                rowsFor(
                    dev.cline.termux.core.kernel.KernelState.HubDown(
                        dev.cline.termux.core.kernel.DownReason.StartFailed,
                        "Timed out after 15000ms waiting for detached hub startup.",
                        null,
                    ),
                ),
                diagnostics = KernelSetupViewModel.diagnosticsOf(previewDoctor),
            ),
            KernelSetupActions(onBack = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun SetupHubTimedOutPreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(
                rowsFor(dev.cline.termux.core.kernel.KernelState.HubDown(dev.cline.termux.core.kernel.DownReason.StartTimedOut, null, previewDoctor)),
                diagnostics = KernelSetupViewModel.diagnosticsOf(previewDoctor),
            ),
            KernelSetupActions(onBack = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun SetupProtocolPreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(
                rowsFor(dev.cline.termux.core.kernel.KernelState.ProtocolUnsupported(previewInfo)),
                hubUpdate = HubUpdateDialogState(HubMismatch.HubNewer, "0.0.91"),
            ),
            KernelSetupActions(onBack = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun SetupAuthFailedPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.AuthFailed(previewInfo))), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupAllPassedPreview() {
    ClinePreviewTheme { KernelSetupContent(previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.Ready(previewInfo, null))), KernelSetupActions(onBack = {})) }
}

@LightDarkPreviews
@Composable
private fun SetupStoppedMeanwhilePreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.HubDown(dev.cline.termux.core.kernel.DownReason.StoppedInTermux, null, null), facts.copy(sawReady = true))),
            KernelSetupActions(onBack = {}),
        )
    }
}

@LightDarkPreviews
@Composable
private fun RepairBlockingPreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.PermissionMissing), variant = SetupVariant.Repair(CheckId.Permission, pushed = false)),
            KernelSetupActions(onBack = null),
        )
    }
}

@LightDarkPreviews
@Composable
private fun RepairPushedPreview() {
    ClinePreviewTheme {
        KernelSetupContent(
            previewState(rowsFor(dev.cline.termux.core.kernel.KernelState.PortTooOld("3.0.68-termux.1")), variant = SetupVariant.Repair(CheckId.Kernel, pushed = true))
                .copy(summaryExpanded = true),
            KernelSetupActions(onBack = {}),
        )
    }
}
