package dev.cline.termux.data.diff

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionFactsReducer
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.ToolRecord
import dev.cline.termux.data.session.ToolStatus
import dev.cline.termux.data.tools.SessionStateSource
import dev.cline.termux.data.tools.historyRecords
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Changes screen and the header chip read (CT §1.3). */
@Immutable
sealed interface DiffStoreState {
    data object Loading : DiffStoreState

    data class Ready(
        val diff: SessionDiffState,
        val cwd: String?,
        val updatedAt: Long,
        /** Display paths whose counts changed in the last update; drives the highlight in A.7. Empty on the first build. */
        val changedPaths: Set<String>,
    ) : DiffStoreState

    /** `session.messages` / `session.get` failed; [message] is kernel text. */
    data class Failed(val message: String) : DiffStoreState
}

/**
 * Per-session diff state (docs/07 §4.3, CT §1.3), shared by the ConversationHeader chip,
 * Changes and Tool detail so they agree.
 *
 * - **Seed:** once the session's canonical rows are loaded, the `tool_use`/`tool_result`
 *   pairs become [ToolResultEvent]s (CT §1.2 mapping table); a session nobody opened is
 *   seeded through [SessionStateSource.seed].
 * - **Live:** every `tool.finished` the reducer records (`facts.tools`, finished or rejected)
 *   appends one event, with the `input` remembered from its `tool.started`.
 * - **Replay / double delivery:** a `toolCallId` already in the event list is ignored, so the
 *   reconciliation that later adds the same call to the canonical rows changes nothing.
 * - Recompute (`SessionDiff.build`, O(events)) runs on [compute], only when the event list or
 *   the cwd changed. Only `editor` and `apply_patch` results are kept (the desktop rule).
 */
class SessionDiffStore(
    val sessionId: String,
    private val source: SessionStateSource,
    scope: CoroutineScope,
    private val clock: Clock,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val seedRetryMs: Long = SEED_RETRY_MS,
) {

    private val collecting = AtomicInteger(0)

    /** True while someone collects [state] (or within its 5 s stop timeout). */
    val active: Boolean get() = collecting.get() > 0

    val state: StateFlow<DiffStoreState> = channelFlow {
        val sessionState = source.state(sessionId)
        launch { keepSeeded(sessionState) }
        val builder = DiffBuilder()
        sessionState.collect { s -> builder.apply(s, clock.millis())?.let { send(it) } }
    }
        .flowOn(compute)
        .onStart { collecting.incrementAndGet() }
        .onCompletion { collecting.decrementAndGet() }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DiffStoreState.Loading)

    /** Retry after [DiffStoreState.Failed] (A.8 `onRetry`): re-seeds `session.messages`. */
    suspend fun retry() = source.seed(sessionId)

    private suspend fun keepSeeded(state: StateFlow<SessionState>) {
        while (true) {
            if (state.value.seed == SeedState.NotSeeded) source.seed(sessionId)
            if (state.value.seed != SeedState.NotSeeded && state.value.seed != SeedState.Seeding) return
            delay(seedRetryMs)
        }
    }

    /** App-scoped registry (AppGraph `diffStores`, docs/07 §1.3). */
    class Registry(
        private val source: SessionStateSource,
        private val scope: CoroutineScope,
        private val clock: Clock,
        private val compute: CoroutineDispatcher = Dispatchers.Default,
    ) {
        private val stores = ConcurrentHashMap<String, SessionDiffStore>()

        fun store(sessionId: String): SessionDiffStore {
            stores[sessionId]?.let { return it }
            if (stores.size >= MAX_IDLE_STORES) stores.entries.removeIf { !it.value.active }
            return stores.computeIfAbsent(sessionId) { SessionDiffStore(it, source, scope, clock, compute) }
        }

        fun state(sessionId: String): StateFlow<DiffStoreState> = store(sessionId).state

        suspend fun retry(sessionId: String) = store(sessionId).retry()
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SEED_RETRY_MS = 5_000L
        const val MAX_IDLE_STORES = 16

        /** The only tools that produce diffs (desktop `session-diff.ts`). */
        val DIFF_TOOLS = setOf("editor", "apply_patch")
    }
}

/** Folds [SessionState]s into [DiffStoreState]s; owned by one collector. Pure apart from its own fields. */
internal class DiffBuilder {
    private val events = ArrayList<ToolResultEvent>()
    private val seen = HashSet<String>()
    private var canonicalRef: List<ChatMessage>? = null
    private var cwd: String? = null
    private var last: DiffStoreState.Ready? = null

    /** The next state, or null when nothing a reader sees changed. */
    fun apply(state: SessionState, now: Long): DiffStoreState? {
        when (val seed = state.seed) {
            SeedState.NotSeeded, SeedState.Seeding -> return if (last == null) DiffStoreState.Loading else null
            SeedState.Missing -> if (last == null) return DiffStoreState.Failed("Unknown session: ${state.sessionId}")
            is SeedState.Failed -> if (last == null && state.facts.tools.isEmpty()) return DiffStoreState.Failed(seed.message)
            is SeedState.Seeded -> Unit
        }
        var appended = false
        if (state.canonical !== canonicalRef) {
            canonicalRef = state.canonical
            for ((id, record) in historyRecords(state.canonical)) {
                if (record.status == ToolStatus.Running) continue
                appended = append(id, record) || appended
            }
        }
        val live = state.facts.tools.values
            .filter { it.status is ToolStatus.Finished || it.status == ToolStatus.Rejected }
            .sortedBy { it.endedAt ?: it.startedAt }
        for (record in live) appended = append(record.toolCallId, record) || appended
        val nextCwd = state.facts.cwd ?: state.facts.workspaceRoot
        val previous = last
        if (previous != null && !appended && nextCwd == cwd) return null
        cwd = nextCwd
        val diff = SessionDiff.build(events, nextCwd)
        val changed = if (previous == null) emptySet() else changedPaths(previous.diff, diff)
        return DiffStoreState.Ready(diff, nextCwd, now, changed).also { last = it }
    }

    private fun append(toolCallId: String, record: ToolRecord): Boolean {
        if (!seen.add(toolCallId)) return false
        if (record.toolName !in SessionDiffStore.DIFF_TOOLS) return false
        events += toolResultEvent(record)
        return true
    }
}

/** CT §1.2 mapping: live `tool.finished` and history `tool_result` both arrive here as records. */
internal fun toolResultEvent(record: ToolRecord): ToolResultEvent = ToolResultEvent(
    toolName = record.toolName,
    toolInput = record.input,
    toolOutput = record.output,
    toolError = when (val status = record.status) {
        is ToolStatus.Finished -> status.error
        ToolStatus.Rejected -> record.error ?: SessionFactsReducer.REJECTED_MARKER
        else -> record.error
    },
)

/** Display paths that are new or whose additions/deletions changed between two builds. */
internal fun changedPaths(before: SessionDiffState, after: SessionDiffState): Set<String> {
    val old = before.fileDiffs.associateBy { it.path }
    return after.fileDiffs.filter { file ->
        val prior = old[file.path]
        prior == null || prior.additions != file.additions || prior.deletions != file.deletions
    }.mapTo(LinkedHashSet()) { it.path }
}
