package dev.cline.termux.ui.kernel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.BuildInfo
import dev.cline.termux.R
import dev.cline.termux.core.kernel.DownReason
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.HubLiveStatus
import dev.cline.termux.core.kernel.HubSessionActivity
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelCoordinator.RestartPrecheck
import dev.cline.termux.core.kernel.KernelCoordinator.StopOutcome
import dev.cline.termux.core.kernel.KernelCoordinator.WakeLockOutcome
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.sessions.KernelSessionStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The coordinator as the Kernel status sheet uses it (E.7, E.8). An interface so the
 * ViewModel is tested against the real coordinator with faked edges, or a scripted fake.
 */
interface StatusKernel {
    val state: StateFlow<KernelState>
    val commandsAvailable: StateFlow<Boolean>
    val portVersion: StateFlow<PortVersion?>
    val wakeLockError: StateFlow<String?>

    /** The last Hub this process knew (kept through `HubDown`). */
    val lastInfo: HubInfo?

    suspend fun ensureConnected(startIfDown: Boolean, userInitiated: Boolean)

    /** E.7 refresh: `/health`, `/version`, `hub.status {}`. */
    suspend fun refreshHubStatus(): HubLiveStatus?

    /** Re-reads the installed VERSION (a port upgrade while connected, trial row 24). */
    suspend fun refreshPortVersion()

    /** `GET /health` only (no RUN_COMMAND): does a Hub answer right now? */
    suspend fun hubAnswers(): Boolean

    suspend fun beginRestart(): RestartPrecheck
    fun cancelRestart()
    suspend fun restart(forced: Boolean): RestartOutcome

    /** The E.7 session-activity pre-check for "Stop kernel" (no state change). */
    suspend fun stopPrecheck(): RestartPrecheck

    suspend fun stopKernel(): StopOutcome
    suspend fun disconnect()
    suspend fun setKeepAwake(enabled: Boolean): WakeLockOutcome

    companion object {
        /** `session.list` page the pre-check reads (feature matrix "Session list", E.7). */
        const val PRECHECK_SESSION_LIMIT = 500

        fun of(coordinator: KernelCoordinator): StatusKernel = object : StatusKernel {
            override val state: StateFlow<KernelState> get() = coordinator.state
            override val commandsAvailable: StateFlow<Boolean> get() = coordinator.commandsAvailable
            override val portVersion: StateFlow<PortVersion?> get() = coordinator.portVersion
            override val wakeLockError: StateFlow<String?> get() = coordinator.wakeLockError
            override val lastInfo: HubInfo? get() = coordinator.info
            override suspend fun ensureConnected(startIfDown: Boolean, userInitiated: Boolean) {
                coordinator.ensureConnected(startIfDown = startIfDown, userInitiated = userInitiated)
            }
            override suspend fun refreshHubStatus(): HubLiveStatus? = coordinator.refreshHubStatus()
            override suspend fun refreshPortVersion() = coordinator.refreshPortVersion()
            override suspend fun hubAnswers(): Boolean = coordinator.probeHealth() != null
            override suspend fun beginRestart(): RestartPrecheck = coordinator.beginRestart()
            override fun cancelRestart() = coordinator.cancelRestart()
            override suspend fun restart(forced: Boolean): RestartOutcome = coordinator.restart(forced)
            override suspend fun stopPrecheck(): RestartPrecheck {
                val client = coordinator.client
                if (!client.isConnected) return RestartPrecheck(HubSessionActivity.NONE, ownBusy = false, unknown = false)
                val status = runCatching { HubLiveStatus.parse(client.command(HubCommands.HUB_STATUS, HubPayloads.hubStatus())) }.getOrNull()
                val ownBusy = status != null && status.activeRpcTurns + status.pendingRuns > 0
                val others = runCatching {
                    HubSessionActivity.summarizeOtherClients(
                        client.command(HubCommands.SESSION_LIST, HubPayloads.sessionList(limit = PRECHECK_SESSION_LIMIT, rootOnly = false)),
                        client.clientId,
                    )
                }.getOrNull()
                return if (others == null) RestartPrecheck(HubSessionActivity.UNKNOWN, ownBusy, unknown = true)
                else RestartPrecheck(others, ownBusy, unknown = false)
            }
            override suspend fun stopKernel(): StopOutcome = coordinator.stopKernel()
            override suspend fun disconnect() {
                coordinator.disconnect()
            }
            override suspend fun setKeepAwake(enabled: Boolean): WakeLockOutcome = coordinator.setKeepAwake(enabled)
        }
    }
}

/** The `kernel.*` preferences the sheet reads (E.5 update row, the wake-lock switch). */
interface StatusPrefs {
    val keepAwake: Flow<Boolean>
    suspend fun setKeepAwake(value: Boolean)
    suspend fun hubLifetimeRelease(): String?

    companion object {
        fun of(prefs: AppPreferences): StatusPrefs = object : StatusPrefs {
            override val keepAwake: Flow<Boolean> get() = prefs.keepAwake
            override suspend fun setKeepAwake(value: Boolean) {
                prefs.setKeepAwake(value)
            }
            override suspend fun hubLifetimeRelease(): String? = prefs.hubLifetimeRelease()
        }
    }
}

/** One-off effects of the sheet. */
sealed interface StatusEvent {
    data class Snackbar(val text: UiText) : StatusEvent

    /** `ProtocolUnsupported` → "Restart kernel" opens the shell's E.9 dialog. */
    data object OpenHubUpdate : StatusEvent
}

/**
 * E.8 `KernelStatusViewModel`, scoped to the shell (the sheet) or to the settings `kernel`
 * page. Kernel dependencies are resolved on [work], never on Main (docs/07 §8: building the
 * coordinator builds the Hub client, whose lazy init still reads DataStore blocking).
 *
 * [ownSessionsBusy] is "any session the app shows as Running or Pending" (E.7), OR-ed into the
 * coordinator's own `hub.status` check.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KernelStatusViewModel(
    private val kernel: suspend () -> StatusKernel,
    private val prefs: StatusPrefs,
    private val reliability: suspend () -> ReliabilitySummary?,
    private val diagnostics: suspend () -> KernelDiagnostics,
    private val ownSessionsBusy: Flow<Boolean>,
    private val appVersion: String?,
    private val nowWallMs: () -> Long = System::currentTimeMillis,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS,
) : ViewModel() {
    private val local = MutableStateFlow(StatusLocal())
    private val extras = MutableStateFlow(Extras(now = nowWallMs()))

    private data class Extras(val hubLifetimeRelease: String? = null, val reliability: ReliabilitySummary? = null, val now: Long)

    @Volatile private var kernelInstance: StatusKernel? = null
    @Volatile private var diagnosticsInstance: KernelDiagnostics? = null

    private val kernelFlow: Flow<StatusKernel> = flow { emit(resolvedKernel()) }

    val ui: StateFlow<KernelStatusUiState> = kernelFlow.flatMapLatest { k ->
        val inputs = combine(k.state, k.commandsAvailable, k.portVersion, k.wakeLockError, prefs.keepAwake) { state, commands, port, wake, keep ->
            StatusInputs(state = state, commandsAvailable = commands, portVersion = port, wakeLockError = wake, keepAwake = keep)
        }
        combine(inputs, extras, local) { base, extra, l ->
            KernelStatusProjection.project(
                base.copy(
                    lastInfo = k.lastInfo,
                    hubLifetimeRelease = extra.hubLifetimeRelease,
                    reliability = extra.reliability,
                    nowWallMs = extra.now,
                    appVersion = appVersion,
                ),
                l,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KernelStatusProjection.project(StatusInputs(KernelState.Unknown, appVersion = appVersion)))

    private val _events = Channel<StatusEvent>(Channel.BUFFERED)
    val events: Flow<StatusEvent> = _events.receiveAsFlow()

    private var refreshJob: Job? = null

    init {
        viewModelScope.launch {
            val d = resolvedDiagnostics()
            combine(d.state, d.fixing) { s, f -> s to f }.collect { (s, f) -> local.update { it.copy(diagnostics = s, fixing = f) } }
        }
        viewModelScope.launch {
            // Leaving the update/transient states when the kernel moves on (E.10 "in place").
            resolvedKernel().state.map { it is KernelState.Ready }.distinctUntilChanged().collect { refreshExtras() }
        }
    }

    private suspend fun resolvedKernel(): StatusKernel =
        kernelInstance ?: withContext(work) { kernel() }.also { kernelInstance = it }

    private suspend fun resolvedDiagnostics(): KernelDiagnostics =
        diagnosticsInstance ?: withContext(work) { diagnostics() }.also { diagnosticsInstance = it }

    // ---------------------------------------------------------------- visibility and refresh

    /**
     * The sheet (or page) opened. [diagnostics] scrolls it to E.6 (the KernelStatePanel's
     * "Kernel status" for start-failed variants), which runs diagnostics if never run;
     * `StartTimedOut` runs them on its own (E.5).
     */
    fun onOpened(diagnostics: Boolean) {
        viewModelScope.launch {
            val state = resolvedKernel().state.value
            val timedOut = state is KernelState.HubDown && state.reason == DownReason.StartTimedOut
            if ((diagnostics || timedOut) && local.value.diagnostics == DiagnosticsUi.Idle) resolvedDiagnostics().run()
        }
        viewModelScope.launch {
            // Trial row 24: a port upgrade made while the app stayed connected shows the update row now.
            val k = resolvedKernel()
            if (k.state.value !is KernelState.Ready && k.state.value !is KernelState.Draining) return@launch
            try {
                withContext(work) { k.refreshPortVersion() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The coordinator reports outcomes through its state.
            }
            refreshExtras()
        }
    }

    /** E.7 "Refresh": every 5 s while the sheet is visible; stops when hidden. */
    fun setVisible(visible: Boolean) {
        refreshJob?.cancel()
        refreshJob = if (!visible) null else viewModelScope.launch {
            while (true) {
                refreshOnce()
                delay(refreshIntervalMs)
            }
        }
    }

    private suspend fun refreshOnce() {
        val k = resolvedKernel()
        try {
            when (val state = k.state.value) {
                is KernelState.HubDown -> {
                    // A Hub that came back (TUI, slow boot) is picked up in place, without a start (E.7).
                    val passive = state.reason != DownReason.Disconnected && state.reason != DownReason.CrashLoop
                    if (passive && local.value.busy == null && withContext(work) { k.hubAnswers() }) {
                        withContext(work) { k.ensureConnected(startIfDown = false, userInitiated = false) }
                    }
                }
                is KernelState.Ready, is KernelState.Draining -> withContext(work) { k.refreshHubStatus() }
                else -> Unit
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The coordinator reports outcomes through its state.
        }
        refreshExtras()
    }

    private suspend fun refreshExtras() {
        val release = runCatching { prefs.hubLifetimeRelease() }.getOrNull()
        val summary = runCatching { reliability() }.getOrNull()
        extras.value = Extras(hubLifetimeRelease = release, reliability = summary, now = nowWallMs())
    }

    // ---------------------------------------------------------------- actions

    fun onAction(action: KernelStatusAction) {
        if (ui.value.actionsLocked) return
        when (action) {
            KernelStatusAction.Start, KernelStatusAction.TryAgain -> call(action) { ensureConnected(startIfDown = true, userInitiated = true) }
            KernelStatusAction.Connect -> call(action) { ensureConnected(startIfDown = false, userInitiated = true) }
            KernelStatusAction.Restart -> restart()
            KernelStatusAction.Disconnect -> call(action) {
                disconnect()
                _events.send(StatusEvent.Snackbar(UiText.Res(R.string.kernel_disconnected_snackbar)))
            }
            KernelStatusAction.Stop -> stop()
            KernelStatusAction.RunDiagnostics -> viewModelScope.launch { resolvedDiagnostics().run() }
            // Navigation and the Termux launch are the composable's (they need the activity).
            KernelStatusAction.FixKernel, KernelStatusAction.OpenTermux -> Unit
        }
    }

    /** The last `KernelSheetRequest.restart` handled: a request survives recomposition and rotation, the restart does not repeat. */
    private var handledRestartRequest: Long? = null

    /**
     * "Restart kernel" from outside the sheet (A4: the stall notice, the Stop snackbars): the
     * sheet's own E.7 Restart, its pre-check and confirm included, once per [request].
     */
    fun restartOnOpen(request: Long) {
        if (request == handledRestartRequest) return
        handledRestartRequest = request
        onAction(KernelStatusAction.Restart)
    }

    private fun call(action: KernelStatusAction, block: suspend StatusKernel.() -> Unit) {
        local.update { it.copy(busy = action, actionError = emptyList()) }
        viewModelScope.launch {
            try {
                withContext(work) { resolvedKernel().block() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Outcomes arrive as kernel states.
            } finally {
                local.update { it.copy(busy = null) }
            }
        }
    }

    /** E.7 Restart: pre-check, the confirm when someone is busy, then `hub upgrade` or the forced path. */
    private fun restart() {
        val k = kernelInstance
        if (k != null && k.state.value is KernelState.ProtocolUnsupported) {
            _events.trySend(StatusEvent.OpenHubUpdate)
            return
        }
        local.update { it.copy(busy = KernelStatusAction.Restart, actionError = emptyList()) }
        viewModelScope.launch {
            val kernel = resolvedKernel()
            val pre = runCatching { withContext(work) { kernel.beginRestart() } }.getOrElse {
                if (it is CancellationException) throw it
                RestartPrecheck(HubSessionActivity.UNKNOWN, ownBusy = false, unknown = true)
            }
            val ownBusy = pre.ownBusy || runCatching { ownSessionsBusy.first() }.getOrDefault(false)
            val others = pre.unknown || pre.others.activeSessionCount > 0
            if (others || ownBusy) {
                local.update { it.copy(confirm = StatusConfirm.Restart(confirmText(pre, others, stop = false), forced = others)) }
            } else {
                runRestart(forced = false)
            }
        }
    }

    private suspend fun runRestart(forced: Boolean) {
        val k = resolvedKernel()
        val outcome = try {
            withContext(work) { k.restart(forced) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            RestartOutcome.Failed(null)
        }
        local.update { it.copy(busy = null, actionError = restartError(outcome)) }
        if (outcome is RestartOutcome.Restarted) _events.send(StatusEvent.Snackbar(UiText.Res(R.string.kernel_restarted_snackbar)))
    }

    /** E.7 Stop: the pre-check when connected, the confirm when someone is busy, then `stopKernel`. */
    private fun stop() {
        local.update { it.copy(busy = KernelStatusAction.Stop, actionError = emptyList()) }
        viewModelScope.launch {
            val k = resolvedKernel()
            val connected = k.state.value.let { it is KernelState.Ready || it is KernelState.Draining || it is KernelState.Reconnecting }
            val pre = if (!connected) {
                RestartPrecheck(HubSessionActivity.NONE, ownBusy = false, unknown = false)
            } else {
                runCatching { withContext(work) { k.stopPrecheck() } }.getOrElse {
                    if (it is CancellationException) throw it
                    RestartPrecheck(HubSessionActivity.UNKNOWN, ownBusy = false, unknown = true)
                }
            }
            val ownBusy = pre.ownBusy || runCatching { ownSessionsBusy.first() }.getOrDefault(false)
            val others = pre.unknown || pre.others.activeSessionCount > 0
            if (others || ownBusy) {
                local.update { it.copy(confirm = StatusConfirm.Stop(confirmText(pre, others, stop = true))) }
            } else {
                runStop()
            }
        }
    }

    private suspend fun runStop() {
        val k = resolvedKernel()
        val outcome = try {
            withContext(work) { k.stopKernel() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            StopOutcome.Unavailable(null)
        }
        local.update { it.copy(busy = null, actionError = stopError(outcome)) }
        if (outcome is StopOutcome.Stopped) _events.send(StatusEvent.Snackbar(UiText.Res(R.string.kernel_stopped_snackbar)))
    }

    /** The confirm's destructive button. */
    fun confirm() {
        val c = local.value.confirm ?: return
        local.update { it.copy(confirm = null) }
        viewModelScope.launch {
            when (c) {
                is StatusConfirm.Restart -> runRestart(c.forced)
                is StatusConfirm.Stop -> runStop()
            }
        }
    }

    /** "Cancel" (or back): Restart leaves `Restarting` and changes nothing (E.7 step 2). */
    fun dismissConfirm() {
        val c = local.value.confirm ?: return
        local.update { it.copy(confirm = null, busy = null) }
        if (c is StatusConfirm.Restart) kernelInstance?.cancelRestart()
    }

    /** "Keep Termux awake" (E.7): optimistic, rolled back with the error line on failure. */
    fun setKeepAwake(enabled: Boolean) {
        if (local.value.keepAwakeOverride != null) return
        local.update { it.copy(keepAwakeOverride = enabled, wakeLockFailure = null) }
        viewModelScope.launch {
            val outcome = try {
                withContext(work) { resolvedKernel().setKeepAwake(enabled) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                WakeLockOutcome.Failed(null)
            }
            when (outcome) {
                WakeLockOutcome.Applied -> local.update { it.copy(keepAwakeOverride = null) }
                is WakeLockOutcome.Failed -> {
                    runCatching { prefs.setKeepAwake(!enabled) }
                    val reason = outcome.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) }
                        ?: UiText.Res(R.string.kernel_status_termux_no_answer)
                    local.update { it.copy(keepAwakeOverride = null, wakeLockFailure = reason) }
                }
            }
        }
    }

    /** "Open Termux" found nothing to launch. */
    fun reportOpenTermuxFailed() {
        _events.trySend(StatusEvent.Snackbar(UiText.Res(R.string.kernel_status_open_termux_failed)))
    }

    /** "Fix processes" confirmed inside the diagnostics block (E.6). */
    fun fixProcesses() {
        viewModelScope.launch { resolvedDiagnostics().fix() }
    }

    override fun onCleared() {
        refreshJob?.cancel()
        kernelInstance?.let { k -> if (local.value.confirm is StatusConfirm.Restart) k.cancelRestart() }
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 5_000L

        /** E.7 / E.10 confirm text: others (or unknown) → "still serving …"; otherwise the own-task sentence. */
        fun confirmText(pre: RestartPrecheck, others: Boolean, stop: Boolean): UiText = if (others) {
            val described = KernelCopy.describeSessions(if (pre.unknown) HubSessionActivity.UNKNOWN else pre.others)
            UiText.Res(if (stop) R.string.kernel_stop_confirm_body else R.string.kernel_restart_confirm_body, described)
        } else {
            UiText.Res(if (stop) R.string.kernel_stop_confirm_own else R.string.kernel_restart_confirm_own)
        }

        /** The E.7 outcome table's inline texts. */
        fun restartError(outcome: RestartOutcome): List<UiText> = when (outcome) {
            RestartOutcome.Restarted, is RestartOutcome.Down -> emptyList()
            is RestartOutcome.StillServing -> listOfNotNull(
                outcome.stderr.trim().takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) },
                UiText.Res(R.string.kernel_restart_busy_hint),
            )
            is RestartOutcome.Failed -> listOf(
                outcome.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) } ?: UiText.Res(R.string.kernel_restart_failed),
            )
        }

        /** E.10: "The Cline Hub did not stop. Try again, or use Fix processes." */
        fun stopError(outcome: StopOutcome): List<UiText> = when (outcome) {
            StopOutcome.Stopped -> emptyList()
            is StopOutcome.NotStopped -> listOf(UiText.Res(R.string.kernel_stop_not_stopped))
            is StopOutcome.Unavailable -> listOfNotNull(
                outcome.detail?.trim()?.takeIf { it.isNotEmpty() }?.let { UiText.Raw(it) },
                UiText.Res(R.string.kernel_stop_not_stopped),
            )
        }

        fun factory(graph: AppGraph, context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val checks = ReliabilityChecks.of(context, graph.prefs)
                KernelStatusViewModel(
                    kernel = { StatusKernel.of(graph.kernel) },
                    prefs = StatusPrefs.of(graph.prefs),
                    reliability = { checks.summary() },
                    diagnostics = { graph.kernelDiagnostics },
                    ownSessionsBusy = flow {
                        val repository = withContext(Dispatchers.Default) { graph.sessionsRepository }
                        emitAll(
                            repository.state.map { snapshot ->
                                snapshot.rows.any {
                                    it.summary.kernelStatus == KernelSessionStatus.Running || it.summary.kernelStatus == KernelSessionStatus.Pending
                                }
                            },
                        )
                    },
                    appVersion = BuildInfo.VERSION_NAME.takeIf { it.isNotBlank() },
                )
            }
        }
    }
}
