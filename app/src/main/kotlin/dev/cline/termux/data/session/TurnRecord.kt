package dev.cline.termux.data.session

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ProviderAuthInfo
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.isDated
import dev.cline.termux.data.chat.isSystemSteeringMessage
import dev.cline.termux.data.prefs.AppPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** How a turn ended (null while open). */
@Serializable
enum class TurnOutcome { Completed, Failed, Aborted, Interrupted, Ended, NotSent }

/**
 * One `send_input` turn (home-chat.md §1.3.4, docs/07 §4.3): ours are filled from `send()`,
 * turns started elsewhere (the TUI) from `run.started` with an unknown prompt. At most one
 * record per session is open. Open records sent in the last 24 h (and records closed in the
 * last 24 h, for Resend after a cold start) are persisted in DataStore `chat.turns` without
 * image data.
 */
@Immutable
@Serializable
data class TurnRecord(
    val requestId: String,
    val sessionId: String,
    /** The `run.started` sequence (null until it arrives, or for a turn that never started). */
    val startedAtSequence: Long? = null,
    /** The prompt as sent (empty for a turn started elsewhere). Never logged. */
    val prompt: String = "",
    /** [SendRequest.synthetic]: the app's own prompt for the model; an undelivered one is dropped, never returned to the draft. */
    val synthetic: Boolean = false,
    /** Attachment ids only; the image bytes stay in the store's memory. */
    val imageIds: List<String> = emptyList(),
    val mode: String = PromptMode.Act.wire,
    val providerId: String? = null,
    val providerAuth: ProviderAuthInfo? = null,
    val open: Boolean = true,
    /** `run.started` with this requestId was seen. */
    val started: Boolean = false,
    /** `pid:startedAt` of the daemon that took the turn (KC step 3), for interruption check (a). */
    val hubLifetimeKey: String? = null,
    val sentAt: Long,
    val closedAt: Long? = null,
    val outcome: TurnOutcome? = null,
    /** Sent by this client (false: started elsewhere). */
    val ours: Boolean = true,
    /** A11.9: the totals of the terminal `run.*` `result`; null while open or when the event carried none. */
    val stats: TurnStats? = null,
    /**
     * The `renderKey` of the turn's final answer row when the turn ended: reconciliation hands it
     * to the history row that replaces the live one, so the Verbose stats stay on that row.
     */
    val answerRenderKey: String? = null,
    /** The history id of that row, once a reconciliation matched it (it survives a cold start). */
    val answerMessageId: String? = null,
)

/**
 * A finished turn's totals (A11.9 Verbose; F6): the terminal event's `result` is the core
 * `AgentResult`, whose `usage` is the legacy usage shape (`inputTokens`, `outputTokens`,
 * `cacheReadTokens?`, `cacheWriteTokens?`, `totalCost?`, summed over the run's iterations),
 * plus `iterations` and `durationMs`.
 */
@Immutable
@Serializable
data class TurnStats(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long? = null,
    val cacheWriteTokens: Long? = null,
    /** USD as the provider reported it; null when unknown. */
    val totalCost: Double? = null,
    val iterations: Int? = null,
    val durationMs: Long? = null,
) {
    companion object {
        /** Null when [result] is absent or carries neither `usage` nor `durationMs`. */
        fun fromResult(result: JsonObject?): TurnStats? {
            if (result == null) return null
            val usage = result.obj("usage")
            val duration = result.long("durationMs")?.takeIf { it >= 0 }
            if (usage == null && duration == null) return null
            return TurnStats(
                inputTokens = usage?.long("inputTokens")?.coerceAtLeast(0) ?: 0,
                outputTokens = usage?.long("outputTokens")?.coerceAtLeast(0) ?: 0,
                cacheReadTokens = usage?.long("cacheReadTokens"),
                cacheWriteTokens = usage?.long("cacheWriteTokens"),
                totalCost = usage?.double("totalCost")?.takeIf { it.isFinite() },
                iterations = result.int("iterations"),
                durationMs = duration,
            )
        }
    }
}

/** Where open turns survive process death (docs/07 §7.2 `chat.turns`). */
interface TurnStore {
    suspend fun load(): List<TurnRecord>
    suspend fun save(records: List<TurnRecord>)
}

/** Pure helpers for the persisted list. */
object TurnRecords {
    const val CLOSED_RETENTION_MS = 24L * 60 * 60 * 1000
    const val MAX_PERSISTED = 100

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
    private val serializer = ListSerializer(TurnRecord.serializer())

    fun encode(records: List<TurnRecord>): String = json.encodeToString(serializer, records)

    /** Unreadable JSON reads as no turns (the store is a projection; nothing correct depends on it). */
    fun decode(raw: String?): List<TurnRecord> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())

    /**
     * An open turn older than this never saw its terminal event (a replay gap, or a cold start
     * whose replay window missed it). `SessionStore` verifies open turns with `session.get` once
     * connected; this bound keeps one it never could verify from pinning the session, its
     * replay floor and the persisted list on every launch.
     */
    const val OPEN_RETENTION_MS = 24L * 60 * 60 * 1000

    /** An open record past [OPEN_RETENTION_MS]: dropped from the list and from the replay floor. */
    fun isExpired(record: TurnRecord, now: Long): Boolean = record.open && record.sentAt < now - OPEN_RETENTION_MS

    /** Open records for 24 h after their send; closed ones for 24 h after closing; newest [MAX_PERSISTED] overall. */
    fun prune(records: List<TurnRecord>, now: Long): List<TurnRecord> = records
        .filter { if (it.open) !isExpired(it, now) else (it.closedAt ?: it.sentAt) >= now - CLOSED_RETENTION_MS }
        .sortedBy { it.sentAt }
        .takeLast(MAX_PERSISTED)

    // ---- A11.9 per-turn stats -------------------------------------------------------------

    /**
     * [record]'s terminal event arrived with [result]: the stats it carries (kept when it has
     * none) and the turn's final answer in [live], the last non-blank assistant row of the turn.
     */
    fun ended(record: TurnRecord, result: JsonObject?, live: List<ChatMessage>): TurnRecord {
        val stats = TurnStats.fromResult(result) ?: return record
        val answer = live.lastOrNull { it.role == Role.Assistant && it.meta.requestId == record.requestId && it.content.isNotBlank() }
        return record.copy(stats = stats, answerRenderKey = answer?.renderKey ?: record.answerRenderKey)
    }

    /**
     * After a reconciliation: a record whose answer row is now a history row (it inherited the
     * live row's renderKey) learns that row's id. Returns [turns] itself when nothing changed.
     */
    fun anchorAnswers(turns: List<TurnRecord>, canonical: List<ChatMessage>): List<TurnRecord> {
        if (turns.none { it.stats != null && it.answerRenderKey != null && it.answerMessageId == null }) return turns
        val idByKey = canonical.associate { it.renderKey to it.id }
        var changed = false
        val next = turns.map { record ->
            val id = record.answerRenderKey?.takeIf { record.stats != null && record.answerMessageId == null }?.let(idByKey::get)
            if (id == null) record else record.copy(answerMessageId = id).also { changed = true }
        }
        return if (changed) next else turns
    }

    /**
     * Answer row key (its renderKey or history id) → the turn's stats, for the Verbose action
     * row. The cost is left out (null) where [showsCost] says the kernel hides it for the turn's
     * provider (`shouldShowCliUsageCost`: subscription providers), the turn's own provider
     * first, else [sessionProviderId].
     */
    fun statsByAnswer(
        turns: List<TurnRecord>,
        sessionProviderId: String? = null,
        showsCost: (providerId: String?) -> Boolean = { true },
    ): Map<String, TurnStats> {
        val out = HashMap<String, TurnStats>()
        for (record in turns) {
            val recorded = record.stats ?: continue
            val hidden = recorded.totalCost != null && !showsCost(record.providerId ?: sessionProviderId)
            val stats = if (hidden) recorded.copy(totalCost = null) else recorded
            record.answerRenderKey?.let { out[it] = stats }
            record.answerMessageId?.let { out[it] = stats }
        }
        return out
    }

    /**
     * A11.9 Verbose for the turns that have no recorded stats: a turn another client started
     * (the terminal, the desktop), a queued prompt the Hub drained, a session whose records were
     * pruned ([CLOSED_RETENTION_MS]). Only this app's `run.start` reply carries `result`; the Hub's
     * own terminal events do not. The totals here are what the kernel persisted on the turn's
     * messages in [canonical] (history rows): tokens and cost sum the `metrics` of the turn's model
     * calls (the per-call deltas the run's `usage` adds up), and the duration runs from the
     * prompt's `ts` to the last model call's `ts` (the kernel stamps an assistant message when its
     * response finished).
     *
     * Keyed by the final answer's id (the turn's last non-blank assistant row), the cost left out
     * as in [statsByAnswer]. Turns count as in the A13 fork map: any row with a run span opens
     * one, and only a turn opened by a span-1 user row gets totals (merged turns and compaction
     * summaries fold several runs, and compaction strips their metrics). No entry for a turn with
     * no metrics, one that failed (an error row, as the terminal prints no totals then), one
     * holding a row in [recordedKeys] (its recorded stats win), nor for the last turn while
     * [lastTurnRunning].
     */
    fun historyStatsByAnswer(
        canonical: List<ChatMessage>,
        sessionProviderId: String? = null,
        showsCost: (providerId: String?) -> Boolean = { true },
        recordedKeys: Set<String> = emptySet(),
        lastTurnRunning: Boolean = false,
    ): Map<String, TurnStats> {
        val out = HashMap<String, TurnStats>()
        var opener: ChatMessage? = null
        val rows = ArrayList<ChatMessage>()

        fun flush() {
            val prompt = opener ?: return
            historyTurnStats(prompt, rows, sessionProviderId, showsCost, recordedKeys)?.let { (answerId, stats) -> out[answerId] = stats }
        }

        for (message in canonical) {
            val span = message.meta.userRunSpan
                ?: if (message.role == Role.User && !isSystemSteeringMessage(message)) 1 else 0
            if (span >= 1) {
                flush()
                opener = message.takeIf { it.role == Role.User && span == 1 }
                rows.clear()
                if (opener == null) continue
            }
            if (opener != null) rows += message
        }
        if (!lastTurnRunning) flush()
        return out
    }

    private fun historyTurnStats(
        prompt: ChatMessage,
        rows: List<ChatMessage>,
        sessionProviderId: String?,
        showsCost: (providerId: String?) -> Boolean,
        recordedKeys: Set<String>,
    ): Pair<String, TurnStats>? {
        if (rows.any { it.role == Role.Error || it.renderKey in recordedKeys || it.id in recordedKeys }) return null
        val answer = rows.lastOrNull { it.role == Role.Assistant && it.content.isNotBlank() } ?: return null
        val calls = rows.filter { (it.role == Role.Assistant || it.role == Role.Tool) && it.hasMetrics() }
        if (calls.isEmpty()) return null
        val cacheRead = calls.mapNotNull { it.meta.cacheReadTokens?.toLong() }
        val costs = calls.mapNotNull { it.meta.totalCost?.takeIf(Double::isFinite) }
        val provider = rows.lastOrNull { it.role == Role.Assistant && it.meta.providerId != null }?.meta?.providerId ?: sessionProviderId
        val cost = costs.takeIf { it.isNotEmpty() }?.sum()?.takeIf { showsCost(provider) }
        val end = rows.filter { (it.role == Role.Assistant || it.role == Role.Tool) && isDated(it.createdAt) }.maxOfOrNull { it.createdAt }
        val duration = if (end != null && isDated(prompt.createdAt)) (end - prompt.createdAt).takeIf { it > 0 } else null
        return answer.id to TurnStats(
            inputTokens = calls.sumOf { (it.meta.inputTokens ?: 0).toLong().coerceAtLeast(0) },
            outputTokens = calls.sumOf { (it.meta.outputTokens ?: 0).toLong().coerceAtLeast(0) },
            cacheReadTokens = cacheRead.takeIf { it.isNotEmpty() }?.sum(),
            totalCost = cost,
            durationMs = duration,
        )
    }

    private fun ChatMessage.hasMetrics(): Boolean =
        meta.inputTokens != null || meta.outputTokens != null || meta.cacheReadTokens != null || meta.totalCost != null
}

/** [TurnStore] over `AppPreferences.chatTurnsJson` (DataStore `app`, key `chat.turns`). */
class PreferencesTurnStore(private val prefs: AppPreferences) : TurnStore {
    override suspend fun load(): List<TurnRecord> = TurnRecords.decode(prefs.chatTurnsJson())
    override suspend fun save(records: List<TurnRecord>) {
        prefs.setChatTurnsJson(TurnRecords.encode(records))
    }
}
