package dev.cline.termux.ui.kernel

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.components.rememberPlainCopier
import dev.cline.termux.ui.components.rememberLongPressHaptic
import dev.cline.termux.ui.components.LtrText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.gestures.detectTapGestures
import dev.cline.termux.core.kernel.DoctorSummary
import dev.cline.termux.core.kernel.KernelCommandResult
import dev.cline.termux.core.kernel.KernelCommands
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One label/value row of the diagnostics block (E.6). Values are data (pids, versions). */
data class DiagnosticRow(@StringRes val label: Int, val value: UiText)

/** The diagnostics block's state (KS 0.7 `DiagnosticsBlock`): never run, running, rows, or an error. */
sealed interface DiagnosticsUi {
    data object Idle : DiagnosticsUi
    data object Loading : DiagnosticsUi
    data class Loaded(val rows: List<DiagnosticRow>, val hubHealthy: Boolean) : DiagnosticsUi
    data class Error(val message: UiText) : DiagnosticsUi
}

/** What a doctor run came to, before projection. */
sealed interface DoctorOutcome {
    data class Report(val summary: DoctorSummary) : DoctorOutcome
    data class Failed(val message: UiText) : DoctorOutcome
}

/**
 * The diagnostics use case shared by the Kernel status sheet (E.6), the setup checklist's
 * Hub row and the repair variant (A row 5), and About: `cline doctor --json` projected to the
 * E.6 rows, plus "Fix processes" (`cline doctor fix --json` through the coordinator, which
 * owns the resulting `HubDown(StoppedInTermux)` state). App-scoped, so every surface shows the
 * same last result. Nothing here ever contains the Hub token or provider keys: `DoctorStatus`
 * carries pids, counts and versions only.
 */
class KernelDiagnostics(private val runner: Runner, private val scope: CoroutineScope) {

    /** The two kernel calls behind diagnostics (fakeable). */
    interface Runner {
        suspend fun doctor(): DoctorOutcome
        suspend fun fix(): DoctorOutcome

        companion object {
            /** Doctor through [commands] (read-only), fix through [coordinator] (it changes the kernel state). */
            fun of(coordinator: KernelCoordinator, commands: KernelCommands): Runner = object : Runner {
                override suspend fun doctor(): DoctorOutcome = outcomeOf(commands.doctorJson())
                override suspend fun fix(): DoctorOutcome =
                    coordinator.fixProcesses()?.let { DoctorOutcome.Report(it) } ?: DoctorOutcome.Failed(UiText.Res(R.string.diag_failed))
            }
        }
    }

    private val _state = MutableStateFlow<DiagnosticsUi>(DiagnosticsUi.Idle)
    val state: StateFlow<DiagnosticsUi> = _state.asStateFlow()

    private val _fixing = MutableStateFlow(false)

    /** "Fix processes" is running (the block shows "Fixing…"). */
    val fixing: StateFlow<Boolean> = _fixing.asStateFlow()

    private var job: Job? = null

    /** "Run diagnostics": ignored while a run or a fix is in flight. */
    fun run(): Job? {
        if (job?.isActive == true) return null
        _state.value = DiagnosticsUi.Loading
        return scope.launch { _state.value = project(runner.doctor()) }.also { job = it }
    }

    /** Confirmed "Fix processes" (E.6): `doctor fix`, then the fresh doctor report. */
    fun fix(): Job? {
        if (job?.isActive == true) return null
        _fixing.value = true
        return scope.launch {
            try {
                _state.value = project(runner.fix())
            } finally {
                _fixing.value = false
            }
        }.also { job = it }
    }

    /** Back to "never run" (a surface that wants a fresh block). */
    fun clear() {
        if (job?.isActive == true) return
        _state.value = DiagnosticsUi.Idle
    }

    companion object {
        /** Maps a RUN_COMMAND result of `cline doctor --json` to an outcome. */
        fun outcomeOf(result: KernelCommandResult): DoctorOutcome = when (result) {
            is KernelCommandResult.Completed -> {
                val summary = if (result.delivered) DoctorSummary.parseStdout(result.stdout) else null
                when {
                    summary != null -> DoctorOutcome.Report(summary)
                    !result.delivered -> DoctorOutcome.Failed(result.errmsg?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.diag_failed))
                    else -> DoctorOutcome.Failed(result.lastStderrLine?.let { UiText.Raw(it) } ?: UiText.Res(R.string.diag_failed))
                }
            }
            is KernelCommandResult.TimedOut -> DoctorOutcome.Failed(UiText.Res(R.string.diag_failed_timeout))
            is KernelCommandResult.Unavailable -> DoctorOutcome.Failed(result.detail?.let { UiText.Raw(it) } ?: UiText.Res(R.string.diag_failed))
        }

        fun project(outcome: DoctorOutcome): DiagnosticsUi = when (outcome) {
            is DoctorOutcome.Report -> DiagnosticsUi.Loaded(rows(outcome.summary), outcome.summary.hubHealthy)
            is DoctorOutcome.Failed -> DiagnosticsUi.Error(outcome.message)
        }

        /** E.6 rows; "CLI version" and "Hub uptime" are omitted when the report lacks them. */
        fun rows(summary: DoctorSummary): List<DiagnosticRow> = buildList {
            add(
                DiagnosticRow(
                    R.string.diag_hub_healthy,
                    when {
                        !summary.hubHealthy -> UiText.Res(R.string.diag_no)
                        summary.hubPid != null -> UiText.Res(R.string.diag_yes_pid, summary.hubPid)
                        else -> UiText.Res(R.string.diag_yes)
                    },
                ),
            )
            add(DiagnosticRow(R.string.diag_listeners, pids(summary.listeningPids)))
            add(DiagnosticRow(R.string.diag_stale, pids(summary.staleHubPids)))
            add(
                DiagnosticRow(
                    R.string.diag_locks,
                    if (summary.hubStartupLocks > 0) UiText.Raw(summary.hubStartupLocks.toString()) else UiText.Res(R.string.diag_none),
                ),
            )
            add(DiagnosticRow(R.string.diag_cli, pids(summary.staleCliPids)))
            summary.cliVersion?.takeIf { it.isNotBlank() }?.let { add(DiagnosticRow(R.string.diag_cli_version, UiText.Raw(it))) }
            summary.hubUptime?.takeIf { it.isNotBlank() }?.let { add(DiagnosticRow(R.string.diag_uptime, UiText.Raw(it))) }
        }

        private fun pids(list: List<Int>): UiText =
            if (list.isEmpty()) UiText.Res(R.string.diag_none) else UiText.Raw(list.joinToString(", "))
    }
}

/**
 * KS 0.7 `DiagnosticsBlock`: "Diagnostics" overline, label/value rows (long-press copies the
 * value), the optional "Fix processes" `TextButton(error)` with its confirm dialog, and the
 * privacy line. Loading shows a 2dp indeterminate bar with "Running diagnostics…".
 */
@Composable
fun DiagnosticsBlock(
    ui: DiagnosticsUi,
    modifier: Modifier = Modifier,
    fixing: Boolean = false,
    allowFix: Boolean = true,
    onFix: () -> Unit = {},
) {
    if (ui == DiagnosticsUi.Idle) return
    var confirmFix by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().animateContentSize()) {
        Text(
            stringResource(R.string.diag_overline),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = Spacing.s).semantics { heading() },
        )
        when (ui) {
            DiagnosticsUi.Loading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
                Spacer(Modifier.height(Spacing.s))
                Text(
                    stringResource(R.string.kernel_running_diagnostics),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            is DiagnosticsUi.Loaded -> {
                DiagnosticsRows(ui.rows)
                if (allowFix) {
                    TextButton(onClick = { confirmFix = true }, enabled = !fixing) {
                        if (fixing) {
                            InlineSpinner()
                            Spacer(Modifier.width(Spacing.s))
                            Text(stringResource(R.string.diag_fixing))
                        } else {
                            Text(stringResource(R.string.diag_fix), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            is DiagnosticsUi.Error -> Text(
                ui.message.resolve(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
            )
            DiagnosticsUi.Idle -> Unit
        }
        Spacer(Modifier.height(Spacing.s))
        Text(
            stringResource(R.string.diag_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (confirmFix) {
        ConfirmDialog(
            title = stringResource(R.string.diag_fix_confirm_title),
            text = stringResource(R.string.diag_fix_confirm_body),
            confirmLabel = stringResource(R.string.diag_fix),
            dismissLabel = stringResource(R.string.diag_cancel),
            destructive = true,
            onConfirm = {
                confirmFix = false
                onFix()
            },
            onDismiss = { confirmFix = false },
        )
    }
}

/**
 * The label/value rows as two aligned columns (DS §4.1 #13 "two columns, max-content +
 * flexible"): the label column is as wide as the widest label, capped at
 * [DIAGNOSTICS_LABEL_MAX_WIDTH] (longer labels wrap). No horizontal padding of their own: the
 * caller's gutter (the sheet, or row 5's text column in A) applies. Long-press copies the value.
 */
@Composable
private fun DiagnosticsRows(rows: List<DiagnosticRow>) {
    val labelStyle = MaterialTheme.typography.bodyMedium
    val labels = rows.map { stringResource(it.label) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val widest = remember(labels, labelStyle, density) { labels.maxOfOrNull { measurer.measure(it, labelStyle).size.width } ?: 0 }
    val labelWidth = with(density) { widest.toDp() }.coerceIn(DIAGNOSTICS_LABEL_MIN_WIDTH, DIAGNOSTICS_LABEL_MAX_WIDTH)
    rows.zip(labels).forEach { (row, label) ->
        val value = row.value.resolve()
        val copier = rememberPlainCopier()
        val haptic = rememberLongPressHaptic()
        val copyLabel = stringResource(R.string.components_copy_value, label)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .pointerInput(value) { detectTapGestures(onLongPress = { haptic(); copier.copy(label, value) }) }
                .semantics(mergeDescendants = true) {
                    customActions = listOf(CustomAccessibilityAction(copyLabel) { copier.copy(label, value); true })
                }
                .padding(vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = labelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(labelWidth))
            Spacer(Modifier.width(Spacing.l))
            LtrText(
                text = value,
                style = ClineMono.small,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val DIAGNOSTICS_LABEL_MIN_WIDTH = 72.dp
private val DIAGNOSTICS_LABEL_MAX_WIDTH = 168.dp

private val previewSummary = DoctorSummary(
    cliVersion = "3.0.68", coreVersion = "0.0.90", hubHealthy = true, hubPid = 23817, hubUptime = "2h 14m",
    listeningPids = listOf(23817), staleHubPids = emptyList(), hubStartupLocks = 0, staleCliPids = emptyList(),
)

@LightDarkPreviews
@Composable
private fun DiagnosticsBlockLoadedPreview() {
    ComponentPreview { DiagnosticsBlock(KernelDiagnostics.project(DoctorOutcome.Report(previewSummary))) }
}

@LightDarkPreviews
@Composable
private fun DiagnosticsBlockTwoHubsPreview() {
    ComponentPreview {
        DiagnosticsBlock(
            KernelDiagnostics.project(
                DoctorOutcome.Report(previewSummary.copy(listeningPids = listOf(23817, 24102), staleHubPids = listOf(24102), hubStartupLocks = 1)),
            ),
            fixing = true,
        )
    }
}

@LightDarkPreviews
@Composable
private fun DiagnosticsBlockLoadingPreview() {
    ComponentPreview { DiagnosticsBlock(DiagnosticsUi.Loading) }
}

@LightDarkPreviews
@Composable
private fun DiagnosticsBlockErrorPreview() {
    ComponentPreview { DiagnosticsBlock(DiagnosticsUi.Error(UiText.Res(R.string.diag_failed_timeout))) }
}

@LightDarkPreviews
@Composable
private fun DiagnosticsBlockHubDownPreview() {
    ComponentPreview {
        Row(horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
            DiagnosticsBlock(
                KernelDiagnostics.project(DoctorOutcome.Report(previewSummary.copy(hubHealthy = false, hubPid = null, listeningPids = emptyList(), hubUptime = null))),
                allowFix = false,
            )
        }
    }
}
