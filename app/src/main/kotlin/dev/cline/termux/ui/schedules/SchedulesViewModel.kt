package dev.cline.termux.ui.schedules

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.schedules.ClockFormat
import dev.cline.termux.data.schedules.RoutineTemplate
import dev.cline.termux.data.schedules.ScheduleWords
import dev.cline.termux.data.schedules.SchedulesRepository
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The inputs of the keep-alive notice (§4.4): "Keep Termux awake" (`kernel.keepAwake`), the One
 * UI checklist (`reliability.*Confirmed`, all confirmed) and the per-version dismissal
 * (`schedules.keepAliveNoticeDismissedFor`, docs/07 §7.2).
 */
interface KeepAliveInputs {
    val keepAwake: Flow<Boolean>
    val checklistConfirmed: Flow<Boolean>

    suspend fun dismissedFor(): String?

    suspend fun setDismissedFor(version: String)
}

/** The delete confirm ("Delete Routine"). */
@Immutable
data class PendingDelete(val scheduleId: String, val name: String?, val deleting: Boolean = false, val error: RepoFailure? = null)

/** C7 for the selected (expanded) or pushed (compact/medium) schedule. */
@Immutable
data class DetailsUiState(
    val scheduleId: String,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val details: ScheduleDetailsModel? = null,
    val error: RepoFailure? = null,
    val showAllRuns: Boolean = false,
)

/** §4.8 `SchedulesUiState`; [error] and [actionError] are typed and mapped to copy by the screen. */
@Immutable
data class SchedulesUiState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val loaded: Boolean = false,
    val error: RepoFailure? = null,
    val schedules: List<ScheduleCard> = emptyList(),
    val templates: List<RoutineTemplate> = emptyList(),
    val busy: Set<String> = emptySet(),
    val triggering: Set<String> = emptySet(),
    val keepAliveNotice: Boolean = false,
    val reducedScopeWorkspace: String? = null,
    val selectedId: String? = null,
    val kernel: KernelGate = KernelGate.Ready,
    /** The last failed row action's reply (desktop `errorMessage` above the list). */
    val actionError: RepoFailure? = null,
    val pendingDelete: PendingDelete? = null,
    val details: DetailsUiState? = null,
) {
    /** §0.4: switches, Run now, Save and Delete need a usable Hub. */
    val mutationsEnabled: Boolean get() = kernel is KernelGate.Ready || kernel is KernelGate.DegradedNoCommands

    val isEmpty: Boolean get() = loaded && schedules.isEmpty()

    fun card(id: String): ScheduleCard? = schedules.firstOrNull { it.id == id }
}

/** One-shot effects the screen performs (snackbars, navigation). */
sealed interface SchedulesEvent {
    data class RunStarted(val name: String) : SchedulesEvent
    data class RunNotStarted(val name: String) : SchedulesEvent
    data class RunFailed(val failure: RepoFailure) : SchedulesEvent

    /** CR §0.5 transient failures (disconnected, draining) as a Long snackbar. */
    data class Transient(val failure: RepoFailure) : SchedulesEvent

    /** Open `chat/{sessionId}` above Schedules (run-now follow-up, run rows, last session). */
    data class OpenSession(val sessionId: String) : SchedulesEvent

    /** The schedule shown in C7 was deleted: pop to C5 (or clear the expanded selection). */
    data object DetailsDeleted : SchedulesEvent
}

/**
 * C5 + C7 (customize-and-rest.md §4): the list over [SchedulesRepository.observe], the row
 * actions (switch, Run now, delete) with the desktop's per-card busy set, and the details of
 * one schedule: the expanded detail pane's selection ([KEY_SELECTED] in [savedState]) or the
 * pushed `schedules/{scheduleId}` route's argument ([KEY_ROUTE_ID]).
 */
class SchedulesViewModel(
    private val repo: SchedulesRepository,
    kernelGate: StateFlow<KernelGate>,
    private val keepAlive: KeepAliveInputs,
    private val appVersion: String,
    reducedScopeWorkspace: String?,
    private val savedState: SavedStateHandle,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val locale: () -> Locale = { Locale.getDefault() },
    /** The user's "Use 24-hour format" setting, read per projection like the locale (ClockFormat). */
    private val is24Hour: () -> Boolean = { ClockFormat.localeDefaultIs24Hour(locale()) },
    /** The formatters' copy, read per projection so a locale change applies. */
    private val words: () -> ScheduleWords,
) : ViewModel() {

    private data class Local(
        val busy: Set<String> = emptySet(),
        val triggering: Set<String> = emptySet(),
        val actionError: RepoFailure? = null,
        val pendingDelete: PendingDelete? = null,
        val details: DetailsUiState? = null,
        val dismissedFor: String? = null,
        val dismissedLoaded: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val events = Channel<SchedulesEvent>(Channel.BUFFERED)
    val effects: Flow<SchedulesEvent> = events.receiveAsFlow()

    /** The pushed C7 route's schedule, fixed for this entry. */
    private val routeId: String? = savedState.get<String>(KEY_ROUTE_ID)

    /** The expanded detail pane's selection (survives process death). */
    val selectedId: StateFlow<String?> = savedState.getStateFlow(KEY_SELECTED, null)

    /** The screen is started (desktop `mountedRef`): the run-now follow-up only navigates while true. */
    @Volatile private var visible = false
    private var detailsJob: Job? = null

    private val zoneNow get() = zone()
    private val localeNow get() = locale()
    private val is24HourNow get() = is24Hour()

    private val keepAliveFlow: Flow<Pair<Boolean, Boolean>> =
        combine(keepAlive.keepAwake, keepAlive.checklistConfirmed) { awake, confirmed -> awake to confirmed }

    val uiState: StateFlow<SchedulesUiState> = combine(
        repo.observe(),
        kernelGate,
        local,
        keepAliveFlow,
        selectedId,
    ) { repoState, gate, l, (awake, confirmed), selected ->
        val overview = repoState.overview
        val cards = overview?.let { ScheduleProjection.cards(it, zoneNow, localeNow, words(), is24HourNow) }.orEmpty()
        SchedulesUiState(
            loading = repoState.loading || (overview == null && repoState.error == null),
            refreshing = repoState.refreshing,
            loaded = overview != null,
            error = repoState.error,
            schedules = cards,
            templates = if (overview == null) emptyList() else ScheduleProjection.templates(overview.names),
            busy = l.busy,
            triggering = l.triggering,
            keepAliveNotice = l.dismissedLoaded && ScheduleProjection.keepAliveNotice(
                anyEnabled = cards.any { it.record.enabled },
                keepAwake = awake,
                checklistConfirmed = confirmed,
                dismissedFor = l.dismissedFor,
                appVersion = appVersion,
            ),
            reducedScopeWorkspace = reducedScopeWorkspace,
            selectedId = selected,
            kernel = gate,
            actionError = l.actionError,
            pendingDelete = l.pendingDelete,
            details = l.details,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SchedulesUiState(kernel = kernelGate.value, reducedScopeWorkspace = reducedScopeWorkspace))

    init {
        viewModelScope.launch {
            val dismissed = runCatching { keepAlive.dismissedFor() }.getOrNull()
            local.update { it.copy(dismissedFor = dismissed, dismissedLoaded = true) }
        }
        // C7 follows every overview reload (events, polls, writes) silently.
        viewModelScope.launch {
            repo.state.map { it.overview?.loadedAtMs }.distinctUntilChanged().collect { stamp ->
                if (stamp != null && local.value.details?.details != null) loadDetails(silent = true)
            }
        }
        viewModelScope.launch {
            selectedId.collect { if (routeId == null) openDetails(it) }
        }
        if (routeId != null) openDetails(routeId)
    }

    // ------------------------------------------------------------------ lifecycle and refresh

    /** ON_START: refresh silently (CR §4.8 "refresh on resume") and allow follow-up navigation. */
    fun onStart() {
        visible = true
        if (repo.state.value.overview != null) viewModelScope.launch { repo.refresh(silent = true) }
    }

    fun onStop() {
        visible = false
    }

    /** Pull to refresh / the app-bar Refresh: list and the open details. */
    fun refresh() {
        local.update { it.copy(actionError = null) }
        viewModelScope.launch { repo.refresh() }
        if (local.value.details != null) loadDetails(silent = false, pull = true)
    }

    // ------------------------------------------------------------------ selection and details

    /** Expanded: a card tap selects into the detail pane. */
    fun select(scheduleId: String) {
        savedState[KEY_SELECTED] = scheduleId
    }

    /** Expanded back / "Close details". */
    fun clearSelection() {
        savedState[KEY_SELECTED] = null
    }

    fun toggleShowAllRuns() {
        local.update { l -> l.copy(details = l.details?.copy(showAllRuns = !l.details.showAllRuns)) }
    }

    /** The C7 "Retry" after a failed load. */
    fun retryDetails() = loadDetails(silent = false)

    private fun openDetails(id: String?) {
        if (id == null) {
            detailsJob?.cancel()
            local.update { it.copy(details = null) }
            return
        }
        if (local.value.details?.scheduleId == id) return
        local.update { it.copy(details = DetailsUiState(scheduleId = id)) }
        loadDetails(silent = false)
    }

    private fun loadDetails(silent: Boolean, pull: Boolean = false) {
        val id = local.value.details?.scheduleId ?: return
        detailsJob?.cancel()
        if (!silent) {
            local.update { l ->
                val d = l.details ?: return@update l
                l.copy(details = d.copy(loading = d.details == null, refreshing = d.details != null || pull, error = null))
            }
        }
        detailsJob = viewModelScope.launch {
            val out = repo.details(id)
            local.update { l ->
                val d = l.details?.takeIf { it.scheduleId == id } ?: return@update l
                when (out) {
                    is Outcome.Ok -> {
                        val overview = repo.state.value.overview
                        val runs = out.value.executions
                        val card = ScheduleProjection.card(
                            record = out.value.schedule,
                            upcomingNextRunAt = overview?.upcoming?.firstOrNull { it.scheduleId == id }?.nextRunAt,
                            lastExecution = overview?.lastExecutions?.get(id) ?: runs.firstOrNull(),
                            active = overview?.activeFor(id) ?: runs.firstOrNull { it.isActive && it.status == "running" },
                            zone = zoneNow,
                            locale = localeNow,
                            words = words(),
                            is24Hour = is24HourNow,
                        )
                        val model = ScheduleProjection.details(card, runs, zoneNow, localeNow, is24HourNow)
                        l.copy(details = d.copy(loading = false, refreshing = false, error = null, details = model))
                    }
                    is Outcome.Failed -> l.copy(details = d.copy(loading = false, refreshing = false, error = if (silent && d.details != null) d.error else out.failure))
                }
            }
        }
    }

    // ------------------------------------------------------------------ row actions

    private fun nameOf(id: String): String =
        uiState.value.card(id)?.name ?: local.value.details?.details?.card?.takeIf { it.id == id }?.name
            ?: repo.state.value.overview?.schedules?.firstOrNull { it.scheduleId == id }?.name.orEmpty()

    /** Claims the card synchronously; a second tap while busy is ignored (desktop `busyScheduleIds`). */
    private fun claim(id: String): Boolean {
        if (id in local.value.busy) return false
        local.update { it.copy(busy = it.busy + id, actionError = null) }
        return true
    }

    private fun release(id: String) {
        local.update { it.copy(busy = it.busy - id, triggering = it.triggering - id) }
    }

    private fun report(failure: RepoFailure) {
        if (failure.isTransient) {
            events.trySend(SchedulesEvent.Transient(failure))
        } else {
            local.update { it.copy(actionError = failure) }
        }
    }

    /** The switch: `schedule.enable` / `schedule.disable`, waits for the reply (no optimistic flip). */
    fun setEnabled(scheduleId: String, enabled: Boolean) {
        if (!claim(scheduleId)) return
        viewModelScope.launch {
            try {
                (repo.setEnabled(scheduleId, enabled) as? Outcome.Failed)?.let { report(it.failure) }
            } finally {
                release(scheduleId)
            }
        }
    }

    /**
     * Run now (§4.8): "Run not started" without an execution; otherwise "Run started", then the
     * 15 s poll for the session of **that** execution and, while the screen is visible, open it.
     */
    fun runNow(scheduleId: String) {
        if (!claim(scheduleId)) return
        local.update { it.copy(triggering = it.triggering + scheduleId) }
        val name = nameOf(scheduleId)
        viewModelScope.launch {
            try {
                when (val out = repo.trigger(scheduleId)) {
                    is Outcome.Failed -> {
                        local.update { it.copy(actionError = out.failure) }
                        events.trySend(SchedulesEvent.RunFailed(out.failure))
                    }
                    is Outcome.Ok -> when (val result = out.value) {
                        SchedulesRepository.TriggerResult.NotStarted -> events.trySend(SchedulesEvent.RunNotStarted(name))
                        is SchedulesRepository.TriggerResult.Started -> {
                            events.trySend(SchedulesEvent.RunStarted(name))
                            val execution = result.execution
                            val sessionId = execution.sessionId?.trim()?.ifEmpty { null }
                                ?: repo.awaitExecutionSession(scheduleId, execution.executionId)
                            if (sessionId != null && visible) events.trySend(SchedulesEvent.OpenSession(sessionId))
                        }
                    }
                }
            } finally {
                release(scheduleId)
            }
        }
    }

    /** A run row or "Last session" (only rows with a session are clickable). */
    fun openSession(sessionId: String) {
        events.trySend(SchedulesEvent.OpenSession(sessionId))
    }

    fun requestDelete(scheduleId: String) {
        if (scheduleId in local.value.busy) return
        val name = nameOf(scheduleId).ifEmpty { null }
        local.update { it.copy(pendingDelete = PendingDelete(scheduleId, name)) }
    }

    fun dismissDelete() {
        local.update { l -> if (l.pendingDelete?.deleting == true) l else l.copy(pendingDelete = null) }
    }

    fun confirmDelete() {
        val pending = local.value.pendingDelete ?: return
        if (pending.deleting || !claim(pending.scheduleId)) return
        local.update { it.copy(pendingDelete = pending.copy(deleting = true, error = null)) }
        viewModelScope.launch {
            try {
                when (val out = repo.delete(pending.scheduleId)) {
                    is Outcome.Ok -> {
                        local.update { it.copy(pendingDelete = null) }
                        if (routeId == pending.scheduleId) {
                            events.trySend(SchedulesEvent.DetailsDeleted)
                        } else if (selectedId.value == pending.scheduleId) {
                            clearSelection()
                        }
                    }
                    is Outcome.Failed -> local.update { it.copy(pendingDelete = pending.copy(deleting = false, error = out.failure)) }
                }
            } finally {
                release(pending.scheduleId)
            }
        }
    }

    fun dismissKeepAliveNotice() {
        local.update { it.copy(dismissedFor = appVersion) }
        viewModelScope.launch { runCatching { keepAlive.setDismissedFor(appVersion) } }
    }

    fun clearActionError() {
        local.update { it.copy(actionError = null) }
    }

    companion object {
        /** The expanded detail pane's selection. */
        const val KEY_SELECTED = "selectedScheduleId"

        /** The `schedules/{scheduleId}` route argument (`ScheduleDetails.scheduleId`). */
        const val KEY_ROUTE_ID = "scheduleId"
    }
}
