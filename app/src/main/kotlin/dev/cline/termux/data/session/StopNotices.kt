package dev.cline.termux.data.session

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.prefs.AppPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/*
 * A17 (G2, trial row 10): the conversation says why the app stopped a run at the mistake limit.
 * `run.aborted` carries only "aborted", so without these the transcript just ends. The notices
 * are the app's own (no kernel or Hub event produces them): [ClientContributions] records one
 * when the Hub took the user's "Stop this run" or the app's stop after the unanswered prompt's
 * five minutes, `SessionStore` shows it as a stop notice row (`Role.Status`, placed by time like
 * every notice) at the end of the run it stopped, and keeps it in the `sessions` DataStore, so it
 * survives leaving the session, its eviction and a cold start. A session the Hub no longer has, or
 * that the app deleted, drops its notices.
 */

/** One stop at the mistake limit, as stored. */
@Immutable
@Serializable
data class StopNotice(
    val sessionId: String,
    /** The mistake-limit `capability.requested` id: one notice per prompt. */
    val requestId: String,
    val kind: StopNoticeKind,
    /** The kernel's detail, trimmed and cut like the sheet's "Latest:" line ([MistakeLimitPrompt.truncatedDetail]). */
    val detail: String = "",
    /**
     * Where the row sits in the transcript (epoch ms): the end of the run it stopped, or when the
     * Hub took the stop while that end is still to come.
     */
    val at: Long,
    /**
     * The run that was still open when the Hub took the stop. Its model call in flight lands after
     * the stop (trial row 21), so the row waits for the run's end and takes its time; null once
     * placed.
     */
    val endsRun: String? = null,
)

@Serializable
enum class StopNoticeKind {
    /** The user answered "Stop this run". */
    Stopped,

    /** Nobody answered within [MistakeLimitPrompt.TIMEOUT_MS], so the app answered stop. */
    TimedOut,
}

object StopNotices {
    /** `meta.reason` of the rows; the transcript words them (`SessionNoticeRow`). */
    const val REASON_STOPPED = "mistake_limit_stopped"
    const val REASON_TIMED_OUT = "mistake_limit_timed_out"

    /** `meta.noticeType` of a stop notice (the transcript's stop glyph). */
    const val NOTICE_TYPE_STOP = "stop"

    /** Notices kept across all sessions; the oldest go first. */
    const val MAX_NOTICES = 200

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val listSerializer = ListSerializer(StopNotice.serializer())

    fun idOf(requestId: String): String = "ms:$requestId"

    fun of(sessionId: String, requestId: String, kind: StopNoticeKind, context: MistakeLimitContext?, at: Long): StopNotice = StopNotice(
        sessionId = sessionId,
        requestId = requestId,
        kind = kind,
        detail = if (kind == StopNoticeKind.Stopped) MistakeLimitPrompt.truncatedDetail(context?.details) else "",
        at = at,
    )

    /** The transcript row: the detail as content. */
    fun row(notice: StopNotice): ChatMessage = ChatMessage(
        id = idOf(notice.requestId),
        sessionId = notice.sessionId,
        role = Role.Status,
        content = notice.detail,
        createdAt = notice.at,
        meta = Meta(
            noticeType = NOTICE_TYPE_STOP,
            reason = when (notice.kind) {
                StopNoticeKind.Stopped -> REASON_STOPPED
                StopNoticeKind.TimedOut -> REASON_TIMED_OUT
            },
        ),
    )

    /**
     * [notices] with each one that waits for its run ([StopNotice.endsRun]) placed once [turns] no
     * longer has that run open: at the run's end, or at its own time when the run is gone.
     */
    fun placed(notices: List<StopNotice>, turns: List<TurnRecord>): List<StopNotice> = notices.map { n ->
        val run = n.endsRun ?: return@map n
        val turn = turns.lastOrNull { it.requestId == run }
        if (turn?.open == true) n else n.copy(at = turn?.closedAt ?: n.at, endsRun = null)
    }

    fun isStopNotice(message: ChatMessage): Boolean =
        message.role == Role.Status && (message.meta.reason == REASON_STOPPED || message.meta.reason == REASON_TIMED_OUT)

    /** One notice per request, the newest [MAX_NOTICES], oldest first. */
    fun prune(notices: List<StopNotice>): List<StopNotice> =
        notices.distinctBy { it.requestId }.sortedBy { it.at }.takeLast(MAX_NOTICES)

    fun encode(notices: List<StopNotice>): String = json.encodeToString(listSerializer, notices)

    fun decode(text: String): List<StopNotice> = runCatching { json.decodeFromString(listSerializer, text) }.getOrDefault(emptyList())
}

/** Where the notices persist (docs/07 rule 6: a fake edge for tests). */
interface StopNoticeStore {
    suspend fun load(): List<StopNotice>
    suspend fun save(notices: List<StopNotice>)

    companion object {
        /** Keeps nothing (tests of everything else). */
        val None: StopNoticeStore = object : StopNoticeStore {
            override suspend fun load(): List<StopNotice> = emptyList()
            override suspend fun save(notices: List<StopNotice>) = Unit
        }
    }
}

/** `sessions.stopNotices` in the `sessions` DataStore (never backed up, docs/07 §7.4). */
class PreferencesStopNoticeStore(private val prefs: AppPreferences) : StopNoticeStore {
    override suspend fun load(): List<StopNotice> = StopNotices.decode(prefs.stopNoticesJson())
    override suspend fun save(notices: List<StopNotice>) {
        prefs.setStopNoticesJson(StopNotices.encode(notices))
    }
}
