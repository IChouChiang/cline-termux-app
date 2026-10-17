package dev.cline.termux.data.tools

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.CommandBuffer
import dev.cline.termux.data.session.OutputStream
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionFactsReducer
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.session.ToolRecord
import dev.cline.termux.data.session.ToolStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The slice of `SessionStore` the derived per-session stores read (docs/07 §4.3, D15): they
 * observe the store's state and never subscribe to the socket themselves.
 */
interface SessionStateSource {
    fun state(sessionId: String): StateFlow<SessionState>

    /** `session.get` + `session.messages` into the session's canonical rows; a no-op while one is in flight. */
    suspend fun seed(sessionId: String)

    companion object {
        fun of(store: SessionStore): SessionStateSource = object : SessionStateSource {
            override fun state(sessionId: String): StateFlow<SessionState> = store.state(sessionId)
            override suspend fun seed(sessionId: String) = store.seed(sessionId)
        }
    }
}

/** Where a tool record came from. */
enum class ToolOrigin {
    /** `tool.started` / `tool.updated` / `tool.finished` seen on this connection (streamed chunks win, CT §1.4). */
    Live,

    /** Rebuilt from a `tool_use` / `tool_result` pair of `session.messages`. */
    History,
}

/** One tool call of a session, across runs (CT §1.4). [record] carries the executions' chunk buffers. */
@Immutable
data class ToolCallEntry(
    val record: ToolRecord,
    val origin: ToolOrigin,
) {
    val toolCallId: String get() = record.toolCallId
    val toolName: String get() = record.toolName

    /** The tool reported an error (desktop `payload.isError` / `tool.finished.error`). */
    val isError: Boolean
        get() = record.status == ToolStatus.Rejected || (record.status as? ToolStatus.Finished)?.ok == false

    /** Still waiting for its result (running or awaiting approval). */
    val inProgress: Boolean get() = record.status == ToolStatus.Running || record.status == ToolStatus.AwaitingApproval

    /** `endedAt - startedAt` unless the run reported its own duration; null when unknown. */
    val durationMs: Long?
        get() = record.durationMs ?: record.endedAt?.let { end -> (end - record.startedAt).takeIf { it >= 0 && origin == ToolOrigin.Live } }
}

/** Every tool call the store knows for a session, plus the run gate the detail screen needs. */
@Immutable
data class ToolCallSnapshot(
    val sessionId: String,
    /** By `toolCallId`, in first-seen order (history first, then live). */
    val entries: Map<String, ToolCallEntry>,
    /** Desktop `isRunActive`: a run is in flight for the session. */
    val runActive: Boolean,
    /** The session is attached on this connection (B.5 "History (not live)" otherwise). */
    val sessionLive: Boolean,
    val seed: SeedState,
) {
    /** `session.messages` has not answered yet (B.5 Loading). */
    val historyPending: Boolean get() = seed == SeedState.NotSeeded || seed == SeedState.Seeding
}

/** One command execution with its terminal views (CT B.4, B.8 `CommandView`). */
@Immutable
data class ExecutionView(
    val executionId: String,
    val commandIndex: Int,
    /**
     * The command, from the call's input (trial row 18, [executedCommand]); the kernel's echo
     * (`CommandBuffer.query`, capped at 200 characters) only stands in when there is no input.
     */
    val query: String,
    /** stdout and stderr interleaved in arrival order (the desktop's one buffer). */
    val output: TerminalSnapshot,
    val stdout: TerminalSnapshot,
    val stderr: TerminalSnapshot,
    val hasStderr: Boolean,
    /** The chat row's tail: 48,000 characters with the head marker (desktop `appendCappedCommandOutput`). */
    val inlineOutput: String,
    val inlineTruncated: Boolean,
    /** Any streamed chunk text arrived (streamed output always wins over the result, CT §1.4). */
    val streamed: Boolean,
    /** The replay lost the head of this execution (B.3). */
    val gapped: Boolean,
    /** The terminal shows `output[commandIndex].result` instead of streamed chunks (history, or gapped + finished). */
    val fromResult: Boolean,
    val truncated: Boolean,
    val kernelDroppedChars: Long,
    val detachable: Boolean,
    val success: Boolean?,
    val result: String?,
    val running: Boolean,
)

/** One tool for the detail screen (CT B.8). */
@Immutable
data class ToolCallView(
    val entry: ToolCallEntry,
    val executions: List<ExecutionView>,
    val runActive: Boolean,
    val sessionLive: Boolean,
)

/** What `ToolCallStore.tool` resolves to (B.5 Loading / Not found / the rest). */
@Immutable
sealed interface ToolLookup {
    data object Loading : ToolLookup

    /** Absent from both the live store and `session.messages` (or the session is gone). */
    data object NotFound : ToolLookup

    /** `session.messages` failed and the tool is not in the live store; [message] is kernel text. */
    data class HistoryFailed(val message: String) : ToolLookup

    data class Found(val view: ToolCallView) : ToolLookup
}

/**
 * Per-session live tool state (docs/07 §4.3, CT §1.4), keyed by `(sessionId, toolCallId)`.
 *
 * Seeds from the canonical `tool_use`/`tool_result` rows of the session state, keeps every
 * live [ToolRecord] it sees (the reducer clears `facts.tools` on the next `run.started`; the
 * store keeps them so the detail screen of an earlier tool still shows its streamed output),
 * and feeds each execution's chunks into a [TerminalBuffer] (1,048,576-character head cap)
 * plus the chat row's 48,000-character tail. Appends run on [compute] and publish with
 * `conflate()` at most every [publishPeriodMs] (DS §6 streaming rule).
 *
 * Nothing runs while nobody collects: [snapshots] is shared `WhileSubscribed(5 s)`, and each
 * [tool] collector owns its own terminal buffers (dropped when it stops).
 */
class ToolCallStore(
    val sessionId: String,
    private val source: SessionStateSource,
    scope: CoroutineScope,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val publishPeriodMs: Long = TerminalFeed.CONFLATION_MILLIS,
    private val seedRetryMs: Long = SEED_RETRY_MS,
) {
    private val subscribers = AtomicInteger(0)

    /** Collectors of [snapshots] (including [tool] collectors); the registry drops idle stores. */
    val subscriberCount: Int get() = subscribers.get()

    /** Every tool of the session; conflated to one value per [publishPeriodMs]. */
    val snapshots: SharedFlow<ToolCallSnapshot> = channelFlow {
        val state = source.state(sessionId)
        launch { keepSeeded(state) }
        val accumulator = Accumulator(sessionId)
        state.collect { send(accumulator.apply(it)) }
    }
        .conflateEvery(publishPeriodMs)
        .flowOn(compute)
        .onStart { subscribers.incrementAndGet() }
        .onCompletion { subscribers.decrementAndGet() }
        .shareIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    /** One tool with its terminals, for the detail screen (B.8). */
    fun tool(toolCallId: String): Flow<ToolLookup> = flow {
        val terminals = ToolTerminals()
        snapshots.collect { snapshot -> emit(terminals.lookup(snapshot, toolCallId)) }
    }.flowOn(compute).distinctUntilChanged()

    /** Re-reads `session.messages` (B.5 retry after a history failure). */
    suspend fun reload() = source.seed(sessionId)

    /** Seeds a session nobody opened (a pushed route after process death); retries while the socket is down. */
    private suspend fun keepSeeded(state: StateFlow<SessionState>) {
        while (true) {
            if (state.value.seed == SeedState.NotSeeded) source.seed(sessionId)
            if (state.value.seed != SeedState.NotSeeded && state.value.seed != SeedState.Seeding) return
            delay(seedRetryMs)
        }
    }

    /** Apps-scoped registry (AppGraph `toolStores`, docs/07 §1.3): one store per session id. */
    class Registry(
        private val source: SessionStateSource,
        private val scope: CoroutineScope,
        private val compute: CoroutineDispatcher = Dispatchers.Default,
    ) {
        private val stores = ConcurrentHashMap<String, ToolCallStore>()

        fun store(sessionId: String): ToolCallStore {
            stores[sessionId]?.let { return it }
            prune()
            return stores.computeIfAbsent(sessionId) { ToolCallStore(it, source, scope, compute) }
        }

        fun snapshots(sessionId: String): SharedFlow<ToolCallSnapshot> = store(sessionId).snapshots

        fun tool(sessionId: String, toolCallId: String): Flow<ToolLookup> = store(sessionId).tool(toolCallId)

        /** Keeps the map small: idle stores hold no job, only their last snapshot. */
        private fun prune() {
            if (stores.size < MAX_IDLE_STORES) return
            stores.entries.removeIf { it.value.subscriberCount == 0 }
        }
    }

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val SEED_RETRY_MS = 5_000L
        const val MAX_IDLE_STORES = 16

        /** Live records kept after the reducer cleared them (bounded; the oldest go first). */
        const val MAX_RETAINED_LIVE = 256
    }
}

/** Folds successive [SessionState]s into a [ToolCallSnapshot]; owned by one collector. */
internal class Accumulator(private val sessionId: String) {
    private var canonicalRef: List<ChatMessage>? = null
    private var history: LinkedHashMap<String, ToolRecord> = LinkedHashMap()
    private val retained = LinkedHashMap<String, ToolRecord>()

    fun apply(state: SessionState): ToolCallSnapshot {
        if (state.canonical !== canonicalRef) {
            canonicalRef = state.canonical
            history = historyRecords(state.canonical)
        }
        for ((id, record) in state.facts.tools) retained[id] = record
        while (retained.size > ToolCallStore.MAX_RETAINED_LIVE) retained.remove(retained.keys.first())

        val entries = LinkedHashMap<String, ToolCallEntry>(history.size + retained.size)
        for ((id, record) in history) {
            val live = retained[id]
            entries[id] = if (live != null) ToolCallEntry(mergeLive(live, record), ToolOrigin.Live) else ToolCallEntry(record, ToolOrigin.History)
        }
        for ((id, record) in retained) if (id !in entries) entries[id] = ToolCallEntry(record, ToolOrigin.Live)
        val facts = state.facts
        return ToolCallSnapshot(
            sessionId = sessionId,
            entries = entries,
            runActive = facts.activeRun != null || facts.status.isRunning,
            sessionLive = state.live_ && !state.persistedHint,
            seed = state.seed,
        )
    }

    /** Streamed chunks and live statuses win; the history fills a result the live record never got. */
    private fun mergeLive(live: ToolRecord, history: ToolRecord): ToolRecord {
        val unsettled = live.status == ToolStatus.Running || live.status == ToolStatus.AwaitingApproval || live.status == ToolStatus.Cancelled
        if (!unsettled || history.output == null) {
            return if (live.output == null && history.output != null) live.copy(output = history.output) else live
        }
        return live.copy(status = history.status, output = history.output, error = history.error, endedAt = live.endedAt ?: history.endedAt)
    }
}

/** History rows (`t:<toolCallId>`, HistoryMapper) as tool records; a row without `result` has no result yet. */
internal fun historyRecords(canonical: List<ChatMessage>): LinkedHashMap<String, ToolRecord> {
    val out = LinkedHashMap<String, ToolRecord>()
    for (message in canonical) {
        if (message.role != Role.Tool) continue
        val id = message.meta.toolCallId ?: continue
        out[id] = historyRecord(message, id) ?: continue
    }
    return out
}

internal fun historyRecord(message: ChatMessage, toolCallId: String): ToolRecord? {
    val payload = parseToolPayload(message.content) ?: return null
    val toolName = message.meta.toolName?.takeIf { it.isNotEmpty() } ?: payload.toolName ?: "tool"
    val hasResult = payload.result != null
    val errorText = when {
        !hasResult -> null
        payload.isError -> SessionFactsReducer.extractError(payload.result) ?: resultText(payload.result).ifEmpty { null } ?: ""
        else -> SessionFactsReducer.extractError(payload.result)
    }
    val status = when {
        !hasResult -> ToolStatus.Running
        errorText != null && errorText.contains(SessionFactsReducer.REJECTED_MARKER) -> ToolStatus.Rejected
        else -> ToolStatus.Finished(errorText)
    }
    return ToolRecord(
        toolCallId = toolCallId,
        toolName = toolName,
        input = payload.input,
        status = status,
        iteration = message.meta.iteration,
        requestId = message.meta.requestId,
        startedAt = message.createdAt,
        output = payload.result?.takeIf { hasResult },
        error = errorText,
        executions = historyExecutions(payload.result),
    )
}

/**
 * `run_commands` results `[{query, result, success}]`, split per command (B.9). `query` stays the
 * kernel's echo, as on a live buffer; [ToolTerminals] prints the input's command instead.
 */
internal fun historyExecutions(result: JsonElement?): List<CommandBuffer> {
    val array = result as? JsonArray ?: return emptyList()
    return array.mapIndexedNotNull { index, element ->
        val item = element as? JsonObject ?: return@mapIndexedNotNull null
        val query = (item["query"] as? JsonPrimitive)?.contentOrNull ?: return@mapIndexedNotNull null
        CommandBuffer(
            executionId = "history:$index",
            commandIndex = index,
            query = query,
            success = (item["success"] as? JsonPrimitive)?.booleanOrNull,
            result = (item["result"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull,
        )
    }
}

/** A tool result as text: a string as is, anything else as JSON. */
internal fun resultText(value: JsonElement?): String = when (value) {
    null, JsonNull -> ""
    is JsonPrimitive -> value.contentOrNull.orEmpty()
    else -> HubJson.encodeToString(JsonElement.serializer(), value)
}

/** The terminal buffers of one [ToolCallStore.tool] collector. Not thread-safe; one collector owns it. */
internal class ToolTerminals {
    private var toolCallId: String? = null
    private val executions = LinkedHashMap<String, ExecTerminals>()
    private var commandsInput: JsonElement? = null
    private var commands: List<String>? = null

    fun lookup(snapshot: ToolCallSnapshot, id: String): ToolLookup {
        val entry = snapshot.entries[id] ?: return when {
            snapshot.seed == SeedState.Missing -> ToolLookup.NotFound
            snapshot.seed is SeedState.Failed -> ToolLookup.HistoryFailed((snapshot.seed as SeedState.Failed).message)
            snapshot.historyPending -> ToolLookup.Loading
            else -> ToolLookup.NotFound
        }
        if (toolCallId != id) {
            toolCallId = id
            executions.clear()
            commandsInput = null
            commands = null
        }
        val running = entry.inProgress && snapshot.runActive
        val buffers = entry.record.executions
        val commands = if (buffers.isEmpty()) null else commandsOf(entry.record.input)
        val views = buffers.sortedBy { it.commandIndex }.map { buffer ->
            val terminals = executions.getOrPut(buffer.executionId) { ExecTerminals() }
            terminals.update(buffer)
            terminals.view(buffer, running && buffer.success == null && buffer.result == null, executedCommand(commands, buffer.commandIndex, buffer.query))
        }
        return ToolLookup.Found(ToolCallView(entry, views, snapshot.runActive, snapshot.sessionLive))
    }

    /** The call's commands in the kernel's order, parsed again only when the record's input changes. */
    private fun commandsOf(input: JsonElement?): List<String>? {
        if (input == null) return null
        if (input !== commandsInput) {
            commandsInput = input
            commands = runCommandsExecuted(input)
        }
        return commands
    }
}

/**
 * One execution's buffers: interleaved, stdout, stderr, and the chat row tail. Chunks arrive
 * as whole [CommandBuffer] snapshots; the delta is found from the total characters ever
 * appended (`headDroppedChars + length`), so skipped intermediate snapshots lose nothing
 * while the reducer still holds the text.
 */
internal class ExecTerminals {
    private val all = TerminalBuffer()
    private val out = TerminalBuffer()
    private val err = TerminalBuffer()
    private var seen = 0L
    private var inline = ""
    private var inlineTruncated = false
    private var hasStderr = false
    private var streamed = false
    private var fromResult = false
    private var markerWritten = false
    private var resultNoticesWritten = false

    private var allSnap = TerminalSnapshot.EMPTY
    private var outSnap = TerminalSnapshot.EMPTY
    private var errSnap = TerminalSnapshot.EMPTY
    private var dirty = true

    fun update(buffer: CommandBuffer) {
        val result = buffer.result
        if (fromResult) return
        // A gapped buffer is replaced by the kernel's final text once it exists (B.3); history has only that.
        if (result != null && (buffer.gapped || buffer.chunks.isEmpty() && buffer.headDroppedChars == 0L)) {
            resetAll()
            if (buffer.gapped) appendMarker()
            fromResult = true
            appendPiece(OutputStream.Stdout, result)
            return
        }
        val total = buffer.headDroppedChars + buffer.length
        if (total < seen) resetAll()
        val delta = total - seen
        if (delta > 0) {
            val held = buffer.length.toLong()
            if (!markerWritten && (buffer.gapped || delta > held)) appendMarker()
            for ((stream, text) in tail(buffer, minOf(delta, held).toInt())) appendPiece(stream, text)
            seen = total
        }
        if (result != null && !resultNoticesWritten) appendResultNotices(result)
    }

    /**
     * The detach and leftover-children notices exist only in the kernel's final result
     * (bash.ts:887 and :1093 never stream them), so a streamed terminal would end without the
     * notice row CT B.4/B.5 promise. Once the result is known, each such line it carries that
     * the streamed text lacks is appended as a notice row after the output.
     */
    private fun appendResultNotices(result: String) {
        resultNoticesWritten = true
        val notices = result.lineSequence().map { it.trimEnd('\r') }.filter(TerminalNotices::isResultOnly).toList()
        if (notices.isEmpty()) return
        val shown = all.snapshot().lines.mapTo(HashSet()) { it.text }
        for (notice in notices) {
            if (notice in shown) continue
            appendNoticeLine(all, notice)
            appendNoticeLine(out, notice)
        }
        dirty = true
    }

    private fun appendNoticeLine(buffer: TerminalBuffer, notice: String) {
        val snapshot = buffer.snapshot()
        val needsBreak = snapshot.lines.isNotEmpty() && !snapshot.endsWithNewline
        buffer.append(if (needsBreak) "\n$notice\n" else "$notice\n")
    }

    fun view(buffer: CommandBuffer, running: Boolean, command: String): ExecutionView {
        if (dirty) {
            allSnap = all.snapshot()
            outSnap = out.snapshot()
            errSnap = err.snapshot()
            dirty = false
        }
        return ExecutionView(
            executionId = buffer.executionId,
            commandIndex = buffer.commandIndex,
            query = command,
            output = allSnap,
            stdout = outSnap,
            stderr = errSnap,
            hasStderr = hasStderr,
            inlineOutput = inline,
            inlineTruncated = inlineTruncated,
            streamed = streamed,
            gapped = buffer.gapped,
            fromResult = fromResult,
            truncated = buffer.truncated,
            kernelDroppedChars = buffer.kernelDroppedChars,
            detachable = buffer.detachable,
            success = buffer.success,
            result = buffer.result,
            running = running,
        )
    }

    private fun appendMarker() {
        markerWritten = true
        val marker = CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER
        all.append(marker)
        out.append(marker)
        err.append(marker)
        inline = marker
        inlineTruncated = true
        dirty = true
    }

    private fun appendPiece(stream: OutputStream, text: String) {
        if (text.isEmpty()) return
        all.append(text)
        if (stream == OutputStream.Stderr) {
            err.append(text)
            hasStderr = true
        } else {
            out.append(text)
        }
        if (!fromResult) streamed = true
        val capped = CommandOutputCap.appendCapped(inline, text)
        inline = capped.output
        inlineTruncated = inlineTruncated || capped.truncated
        dirty = true
    }

    private fun resetAll() {
        all.clear()
        out.clear()
        err.clear()
        seen = 0
        inline = ""
        inlineTruncated = false
        hasStderr = false
        streamed = false
        markerWritten = false
        resultNoticesWritten = false
        dirty = true
    }

    /** The last [count] characters of the interleaved chunks, split back into stream pieces, oldest first. */
    private fun tail(buffer: CommandBuffer, count: Int): List<Pair<OutputStream, String>> {
        var remaining = count
        val pieces = ArrayDeque<Pair<OutputStream, String>>()
        for (i in buffer.chunks.indices.reversed()) {
            if (remaining <= 0) break
            val chunk = buffer.chunks[i]
            val text = if (chunk.text.length <= remaining) chunk.text else chunk.text.substring(chunk.text.length - remaining)
            remaining -= text.length
            pieces.addFirst(chunk.stream to text)
        }
        return pieces
    }
}
