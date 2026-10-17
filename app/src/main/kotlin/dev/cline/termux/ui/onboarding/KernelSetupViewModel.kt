package dev.cline.termux.ui.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.DoctorSummary
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.StartStep
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.kernel.DiagnosticsUi
import dev.cline.termux.ui.kernel.DoctorOutcome
import dev.cline.termux.ui.kernel.HubUpdateDialogState
import dev.cline.termux.ui.kernel.HubUpdatePresenter
import dev.cline.termux.ui.kernel.KernelDiagnostics
import dev.cline.termux.ui.kernel.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Which screen the checklist is (A.3): the first-run step or a repair variant. */
sealed interface SetupVariant {
    data object FirstRun : SetupVariant

    /** [pushed] = on top of the shell (back arrow); false = blocking (replaces the shell). */
    data class Repair(val focus: CheckId?, val pushed: Boolean) : SetupVariant
}

/** "<row title>: <status word>" for the invisible polite status line (A.9); [serial] re-triggers equal texts. */
@Immutable
data class SetupAnnouncement(val row: CheckId?, val status: CheckStatus?, val serial: Int)

@Immutable
data class KernelSetupUiState(
    val variant: SetupVariant = SetupVariant.FirstRun,
    /** Always five, in [KernelSetupRows.ORDER]. */
    val checks: List<SetupCheckUi> = emptyList(),
    val readyCount: Int = 0,
    /** Row 5's step or restart-phase label while it is checking. */
    val startLabel: UiText? = null,
    val startElapsedSec: Int? = null,
    /** Elapsed > 20 s: "This is taking longer than usual." */
    val slowStart: Boolean = false,
    /** 5/5 and the kernel is `Ready`/`Draining`. */
    val canContinue: Boolean = false,
    /** The row whose "Check again" shows its spinner. */
    val checkingRow: CheckId? = null,
    /** Row 5's diagnostics block (A.6 "Run diagnostics"; pre-filled for a timed-out start). */
    val diagnostics: DiagnosticsUi = DiagnosticsUi.Idle,
    val fixing: Boolean = false,
    /** The E.9 copy and restart progress shown inline for a protocol-incompatible Hub. */
    val hubUpdate: HubUpdateDialogState? = null,
    val announcement: SetupAnnouncement? = null,
    /** Row whose title takes accessibility focus next (after "Allow" returns, A.9). */
    val focusRequest: SetupAnnouncement? = null,
    /** Repair variant: the "%1$d steps OK" summary is expanded. */
    val summaryExpanded: Boolean = false,
    /** "Continue" is deciding between D and C. */
    val continuing: Boolean = false,
) {
    val pushed: Boolean get() = (variant as? SetupVariant.Repair)?.pushed == true

    /** Repair variant: the passed rows above the first open row, collapsed into the summary line. */
    val okAbove: Int
        get() {
            if (variant !is SetupVariant.Repair) return 0
            val index = checks.indexOfFirst { it.status != CheckStatus.Passed }
            return if (index < 0) 0 else index
        }
}

/** One-shot navigation (consumed by the screen). */
sealed interface KernelSetupEvent {
    /** "Continue" → D (first time, a verifiable item not done). */
    data object ToReliability : KernelSetupEvent

    /** "Continue" → C. */
    data object ToConnect : KernelSetupEvent

    /** Row 1 "Install Termux" → B pushed on top. */
    data object ToInstallTermux : KernelSetupEvent

    /** Repair variant resolved 600 ms ago; [connected] shows "Cline kernel connected". */
    data class RepairResolved(val connected: Boolean) : KernelSetupEvent
}

/**
 * Kernel setup (A) and its repair variants (onboarding-kernel.md A.5–A.8). Drives the
 * `KernelCoordinator` through [SetupKernel] and renders its `state`; never talks to the Hub.
 * Permission results come from the screen's `RequestPermission` launcher.
 */
class KernelSetupViewModel(
    private val kernel: SetupKernel,
    private val diagnosticsUseCase: KernelDiagnostics,
    private val prefs: OnboardingPrefs,
    private val reliability: ReliabilityProbe,
    private val savedState: SavedStateHandle,
    val variant: SetupVariant,
    /** False when no reliability destination is registered: "Continue" goes straight to C. */
    private val reliabilityAvailable: Boolean = true,
) : ViewModel() {

    private val _ui = MutableStateFlow(KernelSetupUiState(variant = variant))
    val ui: StateFlow<KernelSetupUiState> = _ui.asStateFlow()

    private val _events = Channel<KernelSetupEvent>(Channel.BUFFERED)
    val events: Flow<KernelSetupEvent> = _events.receiveAsFlow()

    private val hubUpdate = HubUpdatePresenter(viewModelScope) { kernel.restartForProtocol() }

    private val startRequested = MutableStateFlow(false)
    private val tick = MutableStateFlow(0)
    private var startBaseMs: Long? = null
    private var sawReady = false
    private var autoStartTried = false
    private var resolvedSent = false
    private var pendingRecheckOnResume = false
    private var tickerJob: Job? = null
    private var lastRows: List<SetupCheckUi> = emptyList()
    private var serial = 0

    init {
        diagnosticsUseCase.clear()
        viewModelScope.launch { kernel.state.collect { hubUpdate.onKernelState(it) } }
        viewModelScope.launch { hubUpdate.dialog.collect { dialog -> _ui.update { it.copy(hubUpdate = dialog) } } }
        viewModelScope.launch {
            combine(diagnosticsUseCase.state, diagnosticsUseCase.fixing) { d, f -> d to f }.collect { (d, f) -> recompute(diagnostics = d, fixing = f) }
        }
        viewModelScope.launch {
            combine(kernel.state, kernel.portVersion, startRequested, tick) { s, _, _, _ -> s }.collect { recompute() }
        }
        // On entry rows 1–4 check by themselves; row 5 connects only to a Hub that already runs (A.5).
        viewModelScope.launch {
            val now = kernel.state.value
            if (now == KernelState.Unknown || !KernelSetupRows.isBusy(now)) kernel.recheck()
        }
    }

    private var diagnosticsUi: DiagnosticsUi = DiagnosticsUi.Idle
    private var fixingNow = false

    private fun recompute(diagnostics: DiagnosticsUi? = null, fixing: Boolean? = null) {
        diagnostics?.let { diagnosticsUi = it }
        fixing?.let { fixingNow = it }
        val state = kernel.state.value
        if (state is KernelState.Ready || state is KernelState.Draining) sawReady = true
        val facts = SetupFacts(
            presence = kernel.termuxPresence(),
            permissionGranted = kernel.hasRunCommandPermission(),
            portVersion = kernel.portVersion.value,
            startRequested = startRequested.value,
            sawReady = sawReady,
            permissionMode = permissionMode(),
        )
        val computed = KernelSetupRows.rows(state, facts)
        // "Check again" keeps its row's fix area on screen with the button's spinner (A.6, A.9:
        // focus stays on the button) while the coordinator's probe passes through its checking steps.
        val holdRow = _ui.value.checkingRow
        val rows = if (holdRow in CHECK_AGAIN_ROWS && KernelSetupRows.isProbing(state, facts) && lastRows.isNotEmpty()) lastRows else computed
        val hubRow = rows.last()
        val hubChecking = hubRow.status == CheckStatus.Checking
        val nowMs = kernel.elapsedMs()
        if (hubChecking) {
            if (startBaseMs == null) startBaseMs = (state as? KernelState.Starting)?.startedAtElapsedMs ?: nowMs
            ensureTicker()
        } else {
            startBaseMs = null
            tickerJob?.cancel()
            tickerJob = null
        }
        val elapsedMs = startBaseMs?.let { (nowMs - it).coerceAtLeast(0) }
        val ready = KernelSetupRows.readyCount(rows)
        val connected = ready == rows.size && (state is KernelState.Ready || state is KernelState.Draining)
        val announcement = announcementFor(lastRows, rows, connected)
        lastRows = rows
        val diag = when {
            state is KernelState.HubDown && (hubRow.fix as? CheckFix.StartFailed)?.timedOut == true && diagnosticsUi == DiagnosticsUi.Idle ->
                state.doctor?.let { KernelDiagnostics.project(DoctorOutcome.Report(it)) } ?: diagnosticsUi
            else -> diagnosticsUi
        }
        _ui.update { current ->
            current.copy(
                checks = rows,
                readyCount = ready,
                startLabel = if (hubChecking) KernelSetupRows.busyLabel(state) ?: KernelCopy.stepLabel(StartStep.StartingHub) else null,
                startElapsedSec = elapsedMs?.let { (it / 1_000).toInt() },
                slowStart = elapsedMs != null && elapsedMs > KernelGate.SLOW_START_MS,
                canContinue = connected,
                diagnostics = diag,
                fixing = fixingNow,
                announcement = announcement ?: current.announcement,
            )
        }
        maybeAutoStart(state, rows)
        maybeResolveRepair(state, rows)
    }

    private fun announcementFor(previous: List<SetupCheckUi>, rows: List<SetupCheckUi>, connected: Boolean): SetupAnnouncement? {
        if (previous.isEmpty()) return null
        val wasConnected = previous.all { it.status == CheckStatus.Passed }
        if (connected && !wasConnected) return SetupAnnouncement(null, null, ++serial)
        val changed = rows.zip(previous).lastOrNull { (now, before) -> now.status != before.status && now.status != CheckStatus.Waiting }?.first
            ?: return null
        return SetupAnnouncement(changed.id, changed.status, ++serial)
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick.value += 1
            }
        }
    }

    /** Repair variant (A.8 "ECONNREFUSED"): once rows 1–4 pass and the Hub is down, start it as policy allows. */
    private fun maybeAutoStart(state: KernelState, rows: List<SetupCheckUi>) {
        if (variant !is SetupVariant.Repair || autoStartTried) return
        if (state !is KernelState.HubDown) return
        if (rows.take(4).any { it.status != CheckStatus.Passed }) return
        if (state.reason !in AUTO_START_REASONS) return
        autoStartTried = true
        viewModelScope.launch { kernel.autoStart() }
    }

    /**
     * Repair variants leave by themselves 600 ms after the focused row (and every row above it)
     * passed and the kernel is no longer starting (A.3 "Left by").
     */
    private fun maybeResolveRepair(state: KernelState, rows: List<SetupCheckUi>) {
        val repair = variant as? SetupVariant.Repair ?: return
        if (resolvedSent) return
        if (KernelSetupRows.isBusy(state) || KernelSetupRows.isPrerequisiteFailure(state)) return
        val focusIndex = KernelSetupRows.ORDER.indexOf(repair.focus ?: CheckId.Kernel).coerceAtLeast(0)
        if (rows.take(focusIndex + 1).any { it.status != CheckStatus.Passed }) return
        // The blocking variant also waits for a usable Hub when the auto-start is still pending.
        if (!repair.pushed && state is KernelState.HubDown && !autoStartTried && state.reason in AUTO_START_REASONS) return
        resolvedSent = true
        val connected = state is KernelState.Ready || state is KernelState.Draining
        viewModelScope.launch {
            delay(RESOLVE_DELAY_MS)
            _events.send(KernelSetupEvent.RepairResolved(connected))
        }
    }

    // ---- permission (row 2) -------------------------------------------------------------------

    private fun permissionMode(): PermissionMode {
        val asked = savedState.get<Boolean>(KEY_ASKED) == true
        val rationale = savedState.get<Boolean>(KEY_RATIONALE) == true
        return when {
            !asked -> PermissionMode.Ask
            rationale -> PermissionMode.AskAgain
            else -> PermissionMode.OpenSettings
        }
    }

    /** The `RequestPermission` result; [showRationale] = `shouldShowRequestPermissionRationale` afterwards. */
    fun onPermissionResult(granted: Boolean, showRationale: Boolean) {
        savedState[KEY_ASKED] = true
        savedState[KEY_RATIONALE] = showRationale
        if (granted) {
            _ui.update { it.copy(focusRequest = SetupAnnouncement(CheckId.ExternalApps, null, ++serial)) }
            recheckFrom(CheckId.Permission)
        } else {
            recompute()
        }
    }

    // ---- intents ------------------------------------------------------------------------------

    /** "Check again" on [row]: re-runs from that row down (P3 covers rows 3 and 4 in one call). */
    fun recheckFrom(row: CheckId) {
        if (_ui.value.checkingRow != null) return
        _ui.update { it.copy(checkingRow = row) }
        viewModelScope.launch {
            try {
                kernel.recheck()
            } finally {
                _ui.update { it.copy(checkingRow = null) }
                recompute()
            }
        }
    }

    /** "Start kernel" / "Try again" (sets `kernel.userStopped = false` through the coordinator). */
    fun startKernel() {
        if (startRequested.value) return
        diagnosticsUseCase.clear()
        startBaseMs = kernel.elapsedMs()
        startRequested.value = true
        viewModelScope.launch {
            try {
                kernel.start()
            } finally {
                startRequested.value = false
            }
        }
    }

    /** "Run diagnostics" in row 5's fix area. */
    fun runDiagnostics() {
        diagnosticsUseCase.run()
    }

    /** Confirmed "Fix processes" inside the diagnostics block. */
    fun fixProcesses() {
        diagnosticsUseCase.fix()
    }

    /** "Restart kernel" / "Try again" of the inline E.9 copy. */
    fun restartHub() {
        hubUpdate.confirm()
    }

    /** "Open Termux" / "Open Termux settings" / "Open app settings" was launched: re-check once on return. */
    fun onLeftForFix() {
        pendingRecheckOnResume = true
    }

    /** `ON_RESUME`: rows 2 and 3 re-check on every return while a prerequisite fails (A.5). */
    fun onResume() {
        val state = kernel.state.value
        val due = pendingRecheckOnResume || KernelSetupRows.isPrerequisiteFailure(state) ||
            ((state is KernelState.Ready || state is KernelState.Draining) && !kernel.hasRunCommandPermission())
        pendingRecheckOnResume = false
        if (due && !KernelSetupRows.isBusy(state)) {
            viewModelScope.launch { kernel.recheck() }
        } else {
            recompute()
        }
    }

    /** "Install Termux" in row 1. */
    fun installTermux() {
        viewModelScope.launch { _events.send(KernelSetupEvent.ToInstallTermux) }
    }

    /** Repair variant: the "%1$d steps OK" line expands on tap. */
    fun toggleSummary() {
        _ui.update { it.copy(summaryExpanded = !it.summaryExpanded) }
    }

    /** "Continue" (A.6): D when it has not been shown and a verifiable item is not done, else C. */
    fun onContinue() {
        if (!_ui.value.canContinue || _ui.value.continuing) return
        _ui.update { it.copy(continuing = true) }
        viewModelScope.launch {
            try {
                val shownAt = prefs.reliabilityShownAt()
                val itemsDone = runCatching { reliability.verifiableItemsDone() }.getOrDefault(false)
                if (reliabilityAvailable && shownAt == null && !itemsDone) {
                    _events.send(KernelSetupEvent.ToReliability)
                } else {
                    // D.4: skipped automatically, and still recorded as shown.
                    if (shownAt == null && reliabilityAvailable) prefs.markReliabilityShown()
                    _events.send(KernelSetupEvent.ToConnect)
                }
            } finally {
                _ui.update { it.copy(continuing = false) }
            }
        }
    }

    companion object {
        const val TICK_MS = 1_000L

        /** A.3: the repair variants leave this long after the last failing row passes. */
        const val RESOLVE_DELAY_MS = 600L

        /** Rows whose fix area carries a "Check again" button (A.5 rows 3 and 4). */
        private val CHECK_AGAIN_ROWS = setOf(CheckId.ExternalApps, CheckId.Kernel)

        private const val KEY_ASKED = "setup.permission.asked"
        private const val KEY_RATIONALE = "setup.permission.rationale"

        /** HubDown reasons the repair variant auto-starts from (A.8 "auto-start in the repair variant"). */
        private val AUTO_START_REASONS = setOf(
            dev.cline.termux.core.kernel.DownReason.Unreachable,
            dev.cline.termux.core.kernel.DownReason.Asleep,
            dev.cline.termux.core.kernel.DownReason.StoppedInTermux,
        )

        /** Doctor rows for a summary (previews). */
        fun diagnosticsOf(summary: DoctorSummary): DiagnosticsUi = KernelDiagnostics.project(DoctorOutcome.Report(summary))
    }
}
