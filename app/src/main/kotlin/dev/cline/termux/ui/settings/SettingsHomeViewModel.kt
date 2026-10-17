package dev.cline.termux.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.approvals.ApprovalMode
import dev.cline.termux.data.approvals.ApprovalWriteFailure
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.data.notifications.NotificationSettings
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.data.prefs.SignInPending
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.CompactionMode
import dev.cline.termux.data.session.CompactionSaveError
import dev.cline.termux.data.session.CompactionSaveException
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.kernel.KernelChipModel
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.theme.AppearancePrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What Settings home reads and writes (S1.2 "Data source" column). The app's adapter is
 * [GraphSettingsHomeSources]; tests pass a fake. Termux calls throw [TermuxUnavailable].
 */
interface SettingsHomeSources {
    val appearance: Flow<AppearancePrefs>
    val notificationSettings: Flow<NotificationSettings>
    val kernelGate: Flow<KernelGate>
    val hubInfo: Flow<HubInfo?>
    val catalog: Flow<CatalogState>

    /** DataStore `account.displayName` / `account.scope` (the last `fetchMe`, non-secret). */
    val account: Flow<Pair<String?, String?>>
    val signInPending: Flow<SignInPending?>
    val helperState: Flow<HelperState>

    /** `helper.unsupported` or the user stopped the kernel: telemetry is read-only (S1.2). */
    val telemetryLocked: Flow<Boolean>

    /** `schedule.list` + `schedule.active` counts while known (refreshed by [refreshSchedules] and Hub events). */
    val scheduleCounts: Flow<ScheduleCounts?>

    /** `settings.changed`, marketplace installs, Hub reconnects: the Installed counts may be stale. */
    val installedInvalidations: Flow<Unit>

    /** `NotificationManagerCompat.areNotificationsEnabled()` + `POST_NOTIFICATIONS`, re-read on resume. */
    fun notificationPermission(): SummaryPermission

    /** Termux missing or RUN_COMMAND not granted (cheap, no command run); null when both are fine. */
    fun termuxBlock(): KernelBlock?

    /** `cat global-settings.json` → `telemetryOptOut` (false when absent). */
    suspend fun readTelemetryOptOut(): Boolean

    /** `cat providers.json`, folded into the catalog's presence (redacted in the bridge). */
    suspend fun refreshPresence()

    /** `settings.list` counts for the Customize workspace; null when the read failed. */
    suspend fun loadInstalled(force: Boolean): InstalledCounts?

    suspend fun refreshSchedules()

    suspend fun acquireHelper(owner: String)

    /** "Each new toggle re-runs acquire" (S1.2): restarts a helper that ended unavailable. */
    suspend fun retryHelper()

    /** Non-suspending: also called from `onCleared()`, after `viewModelScope` is gone. */
    fun releaseHelper(owner: String)

    /** Waits for a start in flight; returns the settled state. */
    suspend fun awaitHelper(): HelperState

    /** `set_telemetry_opt_out {telemetry_opt_out}` → the adopted `telemetryOptOut`. */
    suspend fun setTelemetryOptOut(optOut: Boolean): Boolean

    /** Context compaction (A11 row 2): the shared `global-settings.json` choice; null while unknown. */
    val compactionMode: Flow<CompactionMode?>

    /** Re-reads `global-settings.json` for [compactionMode] (Settings open). */
    suspend fun refreshCompaction()

    /** Writes [mode] (S1 merge) → the adopted choice; throws `CompactionSaveException`. */
    suspend fun setCompactionMode(mode: CompactionMode): CompactionMode

    /** The kernel's global auto-approve (decided A1) as last read or written. */
    val approvalMode: Flow<ApprovalMode>

    /** A write of `toolAutoApprove` is in flight (from here, the header or the approval sheet). */
    val approvalWriting: Flow<Boolean>

    /** Ask, and a session the app has open still runs with auto-approve until its run ends (§4). */
    val approvalPending: Flow<Boolean>

    /** Re-reads `toolAutoApprove` (auto-approve-control.md §6: Settings open). */
    suspend fun refreshApprovalMode()

    /** Writes `toolAutoApprove` (the repository is optimistic and rolls back on failure). */
    suspend fun setAutoApprove(auto: Boolean): ApprovalWriteResult

    /** A11.9: DataStore `transcript.messageDetails`. */
    val messageDetails: Flow<MessageDetails>

    suspend fun setMessageDetails(mode: MessageDetails)
}

/** The "Couldn't turn on/off Auto-approve all" line under the switch (§4). */
@Immutable
data class AutoApproveError(val turningOn: Boolean, val failure: ApprovalWriteFailure)

/** The Kernel group's "Auto-approve all" switch (decided A1; auto-approve-control.md §4). */
@Immutable
data class AutoApproveRowState(
    val mode: ApprovalMode = ApprovalMode.Unknown,
    val saving: Boolean = false,
    /** Termux commands can run (not blocked, not degraded): the file can be read and written. */
    val commandsAvailable: Boolean = true,
    val pending: Boolean = false,
    val error: AutoApproveError? = null,
) {
    val checked: Boolean get() = mode == ApprovalMode.Auto

    /** Disabled with "Start kernel to change this." before the first read or without commands. */
    val enabled: Boolean get() = mode != ApprovalMode.Unknown && commandsAvailable
}

/** S1.3 `SettingsHomeUiState`. Absent summaries are null (the row shows no supporting line). */
@Immutable
data class SettingsHomeUiState(
    val appearanceSummary: AppearanceSummary,
    val notifications: NotificationsSummary,
    val kernel: KernelChipModel,
    /** The Hub port while connected (TalkBack reads "Cline Hub at 25463, connected"). */
    val kernelPort: Int?,
    val kernelConnected: Boolean,
    val providers: ProvidersSummary?,
    val account: AccountSummary,
    val installed: InstalledCounts?,
    val schedules: ScheduleCounts?,
    val telemetry: TelemetryRowState,
    val appVersion: String,
    val kernelVersion: String?,
    /** S1.4 "Termux missing / permission missing": the card is item 0. */
    val blocked: KernelBlock?,
    /** Expanded only: the row whose page fills the detail pane; null = the default (Appearance). */
    val selected: SettingsSection?,
    /** Models → Context compaction (A11 row 2). */
    val compaction: CompactionRowState = CompactionRowState(),
    /** Kernel group: "Auto-approve all" after the Kernel row (decided A1). */
    val autoApprove: AutoApproveRowState = AutoApproveRowState(),
    /** A11.9: App → Message details (Brief / Verbose). */
    val messageDetails: MessageDetails = MessageDetails.Brief,
)

/**
 * Settings home (S1). Every summary renders as soon as its source answers; nothing here shows
 * a full-screen loading state (S1.3). [onResume] re-runs the load order: DataStore (flows),
 * kernel state (observed), `providers.json` and `global-settings.json` in parallel, then Hub
 * summaries when connected. The settings helper is acquired only for a telemetry write and
 * released when the screen stops or the ViewModel is cleared (SP §0.3, D9).
 */
class SettingsHomeViewModel(
    private val savedState: SavedStateHandle,
    private val sources: SettingsHomeSources,
    private val appVersion: String,
    private val dynamicColorSupported: Boolean,
) : ViewModel() {

    private val selection: StateFlow<String?> = savedState.getStateFlow(KEY_SECTION, null)
    private val permission = MutableStateFlow(SummaryPermission.Granted)
    private val blocked = MutableStateFlow<KernelBlock?>(null)
    private val telemetry = MutableStateFlow(TelemetryRowState())
    private val installed = MutableStateFlow<InstalledCounts?>(null)
    private val kernelVersion = MutableStateFlow<String?>(null)

    private var refreshJob: Job? = null
    private var telemetryJob: Job? = null
    private var holdsHelper = false
    private var visible = false

    private val gate: StateFlow<KernelGate> = sources.kernelGate
        .stateIn(viewModelScope, SharingStarted.Eagerly, KernelGate.Busy(KernelGate.BusyKind.Connecting, null, slow = false))

    private val hubInfo: StateFlow<HubInfo?> = sources.hubInfo.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val helperState: StateFlow<HelperState> = sources.helperState.stateIn(viewModelScope, SharingStarted.Eagerly, HelperState.Idle)

    private val locked: StateFlow<Boolean> = sources.telemetryLocked.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Context compaction: the stored choice and the one being saved (optimistic, rolled back on failure). */
    private val storedCompaction: StateFlow<CompactionMode?> = sources.compactionMode.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private val compactionPending = MutableStateFlow<CompactionMode?>(null)
    private val _events = Channel<SettingsHomeEvent>(Channel.BUFFERED)

    /** Snackbars: a failed compaction save. */
    val events: Flow<SettingsHomeEvent> = _events.receiveAsFlow()

    private data class Local(
        val appearance: AppearancePrefs,
        val notifications: NotificationSettings,
        val permission: SummaryPermission,
        val selection: String?,
        val messageDetails: MessageDetails,
    )

    private data class Kernel(val gate: KernelGate, val info: HubInfo?, val version: String?, val blocked: KernelBlock?)

    private data class Termux(
        val catalog: CatalogState,
        val account: Pair<String?, String?>,
        val pending: SignInPending?,
        val installed: InstalledCounts?,
        val schedules: ScheduleCounts?,
    )

    private data class Telemetry(val row: TelemetryRowState, val helper: HelperState, val locked: Boolean)

    private val local: Flow<Local> = combine(
        sources.appearance.onStart { emit(AppearancePrefs()) },
        sources.notificationSettings.onStart { emit(NotificationSettings.DEFAULT) },
        permission,
        selection,
        sources.messageDetails.onStart { emit(MessageDetails.Brief) },
    ) { appearance, notifications, permission, selection, details -> Local(appearance, notifications, permission, selection, details) }
        .distinctUntilChanged()

    private val kernel: Flow<Kernel> = combine(gate, hubInfo, kernelVersion, blocked) { gate, info, version, blocked ->
        Kernel(gate, info, version, blocked)
    }

    private val termuxData: Flow<Termux> = combine(
        sources.catalog,
        sources.account.onStart { emit(null to null) },
        sources.signInPending.onStart { emit(null) },
        installed,
        sources.scheduleCounts.onStart { emit(null) },
    ) { catalog, account, pending, installed, schedules -> Termux(catalog, account, pending, installed, schedules) }

    private val telemetryData: Flow<Telemetry> = combine(
        telemetry,
        helperState,
        locked,
    ) { row, helper, locked -> Telemetry(row, helper, locked) }

    private val compactionRow: Flow<CompactionRowState> = combine(storedCompaction, compactionPending, gate, blocked) { stored, pending, gate, block ->
        CompactionRowState.of(stored, pending, gate, block)
    }

    private val approvalError = MutableStateFlow<AutoApproveError?>(null)
    private var approvalJob: Job? = null

    private val approvalData: Flow<AutoApproveRowState> = combine(
        sources.approvalMode.onStart { emit(ApprovalMode.Unknown) },
        sources.approvalWriting.onStart { emit(false) },
        sources.approvalPending.onStart { emit(false) },
        approvalError,
    ) { mode, writing, pending, error -> AutoApproveRowState(mode = mode, saving = writing, pending = pending, error = error) }

    /** The Models and Kernel group rows that read `global-settings.json` (A11 row 2, decided A1). */
    private val globalRows: Flow<Pair<CompactionRowState, AutoApproveRowState>> = combine(compactionRow, approvalData) { compaction, approval -> compaction to approval }

    val uiState: StateFlow<SettingsHomeUiState> = combine(local, kernel, termuxData, telemetryData, globalRows) { local, kernel, data, tele, (compaction, approval) ->
        val connected = kernel.gate == KernelGate.Ready || kernel.gate == KernelGate.DegradedNoCommands
        val termuxBlocked = kernel.blocked != null
        SettingsHomeUiState(
            appearanceSummary = AppearanceSummary.of(
                local.appearance.themeMode,
                local.appearance.dynamicColor,
                local.appearance.fontSize,
                dynamicColorSupported,
            ),
            notifications = NotificationsSummary(local.notifications.enabledCount, local.permission),
            kernel = KernelCopy.chip(kernel.gate, kernel.info?.port),
            kernelPort = kernel.info?.port,
            kernelConnected = connected,
            providers = if (termuxBlocked) null else ProvidersSummary.of(data.catalog),
            account = if (termuxBlocked) {
                AccountSummary.Unknown
            } else {
                AccountSummary.of(data.catalog.presence, data.account.first, data.account.second, data.pending)
            },
            installed = if (connected) data.installed else null,
            schedules = if (connected) data.schedules else null,
            telemetry = tele.row.copy(
                startingHelper = tele.row.saving && tele.helper is HelperState.Starting,
                writable = !(tele.locked || tele.helper.knownUnavailable()),
            ),
            appVersion = appVersion,
            kernelVersion = kernel.version,
            blocked = kernel.blocked,
            selected = SettingsSection.fromKey(local.selection),
            compaction = compaction,
            autoApprove = approval.copy(
                commandsAvailable = !termuxBlocked && kernel.gate !is KernelGate.Blocked && kernel.gate != KernelGate.DegradedNoCommands,
            ),
            messageDetails = local.messageDetails,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialState())

    init {
        // Keep the last known kernel version: About's summary survives a Hub restart (S1.2 "cached last value").
        viewModelScope.launch {
            hubInfo.collect { info -> info?.coreVersion?.let { kernelVersion.value = it } }
        }
        // Hub summaries follow the connection: refresh when it becomes usable.
        viewModelScope.launch {
            gate.map { it == KernelGate.Ready || it == KernelGate.DegradedNoCommands }
                .distinctUntilChanged()
                .filter { it }
                .collect { if (visible) launch { refreshHub(force = false) } }
        }
        viewModelScope.launch {
            sources.installedInvalidations.collect { if (gateConnected()) loadInstalled(force = true) }
        }
    }

    private fun initialState() = SettingsHomeUiState(
        appearanceSummary = AppearanceSummary.of(AppearancePrefs().themeMode, true, AppearancePrefs().fontSize, dynamicColorSupported),
        notifications = NotificationsSummary(NotificationSettings.DEFAULT.enabledCount, SummaryPermission.Granted),
        kernel = KernelCopy.chip(gate.value, null),
        kernelPort = null,
        kernelConnected = false,
        providers = null,
        account = AccountSummary.Unknown,
        installed = null,
        schedules = null,
        telemetry = TelemetryRowState(),
        appVersion = appVersion,
        kernelVersion = null,
        blocked = null,
        selected = SettingsSection.fromKey(selection.value),
    )

    /** ON_RESUME (S1.3 load order); also the blocked card's "Retry". */
    fun onResume() {
        visible = true
        permission.value = sources.notificationPermission()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { refresh() }
    }

    /** ON_STOP: the helper is not held while S1 is invisible (unless a write is still in flight). */
    fun onStop() {
        visible = false
        if (telemetryJob?.isActive != true) releaseHelper()
    }

    /** Expanded only: the detail pane shows [section] (saved, so it survives rotation and process death). */
    fun select(section: SettingsSection?) {
        savedState[KEY_SECTION] = section?.key
    }

    private suspend fun refresh() = coroutineScope {
        val block = sources.termuxBlock()
        blocked.value = block
        if (block == null) {
            launch { readTelemetry() }
            launch { runCatching { sources.refreshPresence() }.onFailure { if (it is CancellationException) throw it } }
            launch { runCatching { sources.refreshCompaction() }.onFailure { if (it is CancellationException) throw it } }
            // auto-approve-control.md §6: the terminal may have changed the mode since the last read.
            launch { runCatching { sources.refreshApprovalMode() }.onFailure { if (it is CancellationException) throw it } }
        } else {
            telemetry.update { it.copy(loading = false) }
        }
        if (gateConnected()) launch { refreshHub(force = false) }
    }

    private suspend fun readTelemetry() {
        if (telemetry.value.saving) return
        try {
            val optOut = sources.readTelemetryOptOut()
            telemetry.update { if (it.saving) it else it.copy(enabled = !optOut, loading = false) }
        } catch (e: TermuxUnavailable) {
            // A hung command only drops the summary (SP §10); the other failures are the blocked card.
            KernelBlock.of(e)?.takeIf { it != KernelBlock.TimedOut }?.let { blocked.value = it }
            telemetry.update { it.copy(loading = false) }
        }
    }

    private suspend fun refreshHub(force: Boolean) = coroutineScope {
        launch { loadInstalled(force) }
        launch { runCatching { sources.refreshSchedules() }.onFailure { if (it is CancellationException) throw it } }
    }

    private suspend fun loadInstalled(force: Boolean) {
        val counts = try {
            sources.loadInstalled(force)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        // A failed read leaves the row without a supporting line (S1.4 "Error").
        installed.value = counts
    }

    private fun gateConnected(): Boolean = gate.value.let { it == KernelGate.Ready || it == KernelGate.DegradedNoCommands }

    /**
     * The Telemetry switch (desktop `updateTelemetryOptOut`): optimistic, then the helper write,
     * then the returned value; any failure, including an acquire that ends unavailable, rolls
     * back with the error and leaves the row enabled so toggling again is the retry.
     */
    fun setTelemetryEnabled(enabled: Boolean) {
        val previous = telemetry.value.enabled
        val writable = !(locked.value || helperState.value.knownUnavailable())
        if (!writable || telemetry.value.saving || previous == null || telemetry.value.loading) return
        telemetry.update { it.copy(enabled = enabled, saving = true, error = null) }
        telemetryJob = viewModelScope.launch {
            try {
                if (!holdsHelper) {
                    holdsHelper = true
                    sources.acquireHelper(HELPER_OWNER)
                } else if (helperState.value is HelperState.Unavailable) {
                    sources.retryHelper()
                }
                val settled = sources.awaitHelper()
                if (settled !is HelperState.Ready) {
                    val detail = (settled as? HelperState.Unavailable)?.detail?.takeIf { it.isNotBlank() }
                    rollback(previous, detail?.let(TelemetryError::Message) ?: TelemetryError.HelperUnavailable)
                    return@launch
                }
                val optOut = sources.setTelemetryOptOut(!enabled)
                telemetry.update { it.copy(enabled = !optOut, saving = false, error = null) }
            } catch (e: CancellationException) {
                telemetry.update { it.copy(enabled = previous, saving = false) }
                throw e
            } catch (e: Exception) {
                rollback(previous, TelemetryError.Message(e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName))
            } finally {
                if (!visible) releaseHelper()
            }
        }
    }

    /**
     * The "Auto-approve all" switch (decided A1): the repository flips the mode at once and rolls
     * back on failure; the failure stays under the switch until the next attempt.
     */
    fun setAutoApprove(auto: Boolean) {
        if (approvalJob?.isActive == true) return
        approvalError.value = null
        approvalJob = viewModelScope.launch {
            val result = sources.setAutoApprove(auto)
            if (result is ApprovalWriteResult.Failed) approvalError.value = AutoApproveError(turningOn = auto, failure = result.failure)
        }
    }

    /** App → Message details (A11.9): a DataStore write; the open transcripts re-project from the flow. */
    fun setMessageDetails(mode: MessageDetails) {
        viewModelScope.launch { sources.setMessageDetails(mode) }
    }

    private fun rollback(previous: Boolean, error: TelemetryError) {
        telemetry.update { it.copy(enabled = previous, saving = false, error = error) }
    }

    /**
     * Context compaction (A11 row 2): the row shows [mode] with a spinner while the S1 merge
     * writes it; a failure rolls back to the stored choice and reports the reason in a snackbar.
     * Ignored while a save runs, without Termux commands, or for the current choice.
     */
    fun setCompactionMode(mode: CompactionMode) {
        if (compactionPending.value != null || mode == storedCompaction.value) return
        if (CompactionRowState.of(storedCompaction.value, null, gate.value, blocked.value).needsKernel) return
        compactionPending.value = mode
        viewModelScope.launch {
            try {
                val adopted = sources.setCompactionMode(mode)
                // Keep the spinner until the stored choice shows the write, so the row never flashes the old one.
                withTimeoutOrNull(ADOPT_WAIT_MS) { storedCompaction.first { it == adopted } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: CompactionSaveException) {
                _events.send(SettingsHomeEvent.CompactionSaveFailed(e.error))
            } catch (e: Exception) {
                _events.send(SettingsHomeEvent.CompactionSaveFailed(CompactionSaveError.Failed(e.message.orEmpty())))
            } finally {
                compactionPending.value = null
            }
        }
    }

    private fun releaseHelper() {
        if (!holdsHelper) return
        holdsHelper = false
        sources.releaseHelper(HELPER_OWNER)
    }

    override fun onCleared() {
        releaseHelper()
    }

    companion object {
        /** The `settings?section=` argument doubles as the expanded selection (same SavedStateHandle key). */
        const val KEY_SECTION = "section"
        const val HELPER_OWNER = "settings-home"

        /** How long a saved compaction choice waits for the source to show it. */
        const val ADOPT_WAIT_MS = 2_000L
    }
}

/** `BuildUnsupported` or `KernelStopped` recorded: the write is known to fail before the toggle (S1.2). */
internal fun HelperState.knownUnavailable(): Boolean =
    this is HelperState.Unavailable && (reason == HelperFailure.BuildUnsupported || reason == HelperFailure.KernelStopped)
