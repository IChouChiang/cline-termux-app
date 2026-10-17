package dev.cline.termux.data.schedules

import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.idempotentRead
import dev.cline.termux.data.customize.outcomeOf
import dev.cline.termux.data.customize.reconnects
import dev.cline.termux.data.customize.write
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * Schedules (customize-and-rest.md §4.8, §4.9; docs/07 §4.6): Hub `schedule.*`, always with
 * `allWorkspaces: true`. [state] holds the overview the list shows; while [observe] is
 * collected the repository refreshes silently on `schedule.execution_completed|failed`
 * (debounced 500 ms), when the Hub connection comes back, and every 30 s while any execution is
 * active. Screens call [refresh] on resume.
 */
class SchedulesRepository(
    private val hub: HubClient,
    private val termux: TermuxExecutor,
    private val clockMs: () -> Long = System::currentTimeMillis,
    private val timing: Timing = Timing(),
) {
    data class Timing(
        val eventDebounceMs: Long = 500,
        val activePollMs: Long = 30_000,
        val runNowPollMs: Long = 1_000,
        val runNowWindowMs: Long = 15_000,
    )

    /** The list page's data (desktop overview shape). */
    data class Overview(
        /** Sorted by name, case-insensitively. */
        val schedules: List<ScheduleRecord>,
        val active: List<ScheduleExecution>,
        val upcoming: List<UpcomingRun>,
        /** scheduleId → its latest execution (`list_executions {limit: 1}`). */
        val lastExecutions: Map<String, ScheduleExecution>,
        val loadedAtMs: Long,
    ) {
        fun activeFor(scheduleId: String): ScheduleExecution? = active.firstOrNull { it.scheduleId == scheduleId }
        fun nextRunFor(scheduleId: String): Long? =
            upcoming.firstOrNull { it.scheduleId == scheduleId }?.nextRunAt ?: schedules.firstOrNull { it.scheduleId == scheduleId }?.nextRunAt
        val hasActive: Boolean get() = active.isNotEmpty()
        val names: List<String> get() = schedules.map { it.name }
    }

    data class State(
        val overview: Overview? = null,
        val loading: Boolean = false,
        val refreshing: Boolean = false,
        val error: RepoFailure? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private val refreshMutex = Mutex()

    /**
     * Reloads the overview. [silent] keeps the current content without a loading state (events,
     * polls, switches); the first load shows `loading`, a pull-to-refresh `refreshing`.
     */
    suspend fun refresh(silent: Boolean = false): Outcome<Overview> = refreshMutex.withLock {
        val hadData = _state.value.overview != null
        if (!silent) _state.value = _state.value.copy(loading = !hadData, refreshing = hadData)
        val out = outcomeOf { loadOverview() }
        _state.value = when (out) {
            is Outcome.Ok -> State(overview = out.value)
            is Outcome.Failed -> _state.value.copy(loading = false, refreshing = false, error = out.failure)
        }
        out
    }

    private suspend fun loadOverview(): Overview = coroutineScope {
        val schedules = async { hub.idempotentRead { hub.command(HubCommands.SCHEDULE_LIST, SchedulePayloads.list()) } }
        val active = async { hub.idempotentRead { hub.command(HubCommands.SCHEDULE_ACTIVE, SchedulePayloads.active()) } }
        val upcoming = async { hub.idempotentRead { hub.command(HubCommands.SCHEDULE_UPCOMING, SchedulePayloads.upcoming()) } }
        val records = ScheduleRecord.listFrom(schedules.await()).sortedBy { it.name.lowercase() }
        val activeRuns = ScheduleExecution.listFrom(active.await())
        val upcomingRuns = UpcomingRun.listFrom(upcoming.await())
        val last = records.map { record ->
            async {
                val reply = hub.idempotentRead { hub.command(HubCommands.SCHEDULE_LIST_EXECUTIONS, SchedulePayloads.listExecutions(record.scheduleId, 1)) }
                record.scheduleId to ScheduleExecution.listFrom(reply).firstOrNull()
            }
        }.awaitAll().mapNotNull { (id, execution) -> execution?.let { id to it } }.toMap()
        Overview(records, activeRuns, upcomingRuns, last, clockMs())
    }

    /** Refresh triggers while collected (the list or details screen is visible). */
    @OptIn(FlowPreview::class)
    fun observe(): Flow<State> = channelFlow {
        launch { if (_state.value.overview == null) refresh() }
        launch {
            merge(
                hub.sideEvents
                    .filter { it.event == HubEvents.SCHEDULE_EXECUTION_COMPLETED || it.event == HubEvents.SCHEDULE_EXECUTION_FAILED }
                    .map { }
                    .debounce(timing.eventDebounceMs),
                hub.reconnects(),
            ).collect { refresh(silent = true) }
        }
        launch {
            while (true) {
                delay(timing.activePollMs)
                if (_state.value.overview?.hasActive == true) refresh(silent = true)
            }
        }
        _state.collect { send(it) }
    }

    // ---- details and writes ---------------------------------------------------------------------------

    data class Details(val schedule: ScheduleRecord, val executions: List<ScheduleExecution>)

    suspend fun details(scheduleId: String): Outcome<Details> = outcomeOf {
        coroutineScope {
            val schedule = async { hub.idempotentRead { hub.command(HubCommands.SCHEDULE_GET, SchedulePayloads.byId(scheduleId)) } }
            val runs = async { hub.idempotentRead { hub.command(HubCommands.SCHEDULE_LIST_EXECUTIONS, SchedulePayloads.listExecutions(scheduleId, DETAILS_RUN_LIMIT)) } }
            val record = ScheduleRecord.from(schedule.await()) ?: throw IllegalStateException("schedule.get reply has no schedule")
            Details(record, ScheduleExecution.listFrom(runs.await()))
        }
    }

    suspend fun create(draft: ScheduleDraft): Outcome<ScheduleRecord> = writeSchedule(HubCommands.SCHEDULE_CREATE, SchedulePayloads.create(draft))

    suspend fun update(existing: ScheduleRecord, draft: ScheduleDraft): Outcome<ScheduleRecord> =
        writeSchedule(HubCommands.SCHEDULE_UPDATE, SchedulePayloads.update(existing, draft))

    /** The switch: waits for the reply (no optimistic flip), then a silent refresh. */
    suspend fun setEnabled(scheduleId: String, enabled: Boolean): Outcome<ScheduleRecord> =
        writeSchedule(if (enabled) HubCommands.SCHEDULE_ENABLE else HubCommands.SCHEDULE_DISABLE, SchedulePayloads.byId(scheduleId))

    suspend fun delete(scheduleId: String): Outcome<Unit> {
        val out = outcomeOf { hub.write { hub.command(HubCommands.SCHEDULE_DELETE, SchedulePayloads.byId(scheduleId)) } }
        refresh(silent = true)
        return when (out) {
            is Outcome.Ok -> Outcome.Ok(Unit)
            is Outcome.Failed -> out
        }
    }

    private suspend fun writeSchedule(command: String, payload: JsonObject): Outcome<ScheduleRecord> {
        val out = outcomeOf {
            val reply = hub.write { hub.command(command, payload) }
            ScheduleRecord.from(reply) ?: throw IllegalStateException("$command reply has no schedule")
        }
        refresh(silent = true)
        return out
    }

    /** Run now (CR §4.8): `null` execution = "Run not started". */
    sealed interface TriggerResult {
        data object NotStarted : TriggerResult
        data class Started(val execution: ScheduleExecution) : TriggerResult
    }

    suspend fun trigger(scheduleId: String): Outcome<TriggerResult> {
        val out = outcomeOf {
            val reply = hub.write { hub.command(HubCommands.SCHEDULE_TRIGGER, SchedulePayloads.trigger(scheduleId), envelopeTimeoutMs = TRIGGER_TIMEOUT_MS) }
            ScheduleExecution.from(reply)?.let { TriggerResult.Started(it) } ?: TriggerResult.NotStarted
        }
        refresh(silent = true)
        return out
    }

    /**
     * Polls `list_executions {limit: 5}` every 1 s for up to 15 s until the execution
     * [executionId] has a `sessionId` (only that execution, never "the newest one").
     */
    suspend fun awaitExecutionSession(scheduleId: String, executionId: String): String? {
        val deadline = clockMs() + timing.runNowWindowMs
        while (true) {
            val reply = runCatching { hub.command(HubCommands.SCHEDULE_LIST_EXECUTIONS, SchedulePayloads.listExecutions(scheduleId, RUN_NOW_POLL_LIMIT)) }.getOrNull()
            reply?.let { ScheduleExecution.listFrom(it).firstOrNull { e -> e.executionId == executionId }?.sessionId }?.let { return it }
            if (clockMs() + timing.runNowPollMs > deadline) return null
            delay(timing.runNowPollMs)
        }
    }

    /** RUN_COMMAND `test -d <path>` (CR §4.9): true/false, or null when Termux could not answer. */
    suspend fun workspaceExists(path: String): Boolean? = when (val out = outcomeOf { termux.run(ClineCommands.testDirectory(path)) }) {
        is Outcome.Ok -> if (out.value.delivered) out.value.exitCode == 0 else null
        is Outcome.Failed -> null
    }

    companion object {
        const val DETAILS_RUN_LIMIT = 50
        const val RUN_NOW_POLL_LIMIT = 5
        const val TRIGGER_TIMEOUT_MS = 30_000L
    }
}
