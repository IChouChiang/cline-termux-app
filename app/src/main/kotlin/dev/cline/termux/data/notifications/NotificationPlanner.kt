package dev.cline.termux.data.notifications

import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.session.MistakeLimitPrompt
import dev.cline.termux.data.session.NotifyFacts
import dev.cline.termux.data.session.QuestionKind
import dev.cline.termux.data.session.SessionStatus

/** What `ClineNotifier` posts or cancels (customize-and-rest.md §11.2–11.4). Texts are kernel data or null. */
sealed interface NotificationAction {
    val sessionId: String

    /** taskCompletion ([failed] false) or sessionError ([failed] true); [detail] is the `run.failed` error text. */
    data class PostResult(override val sessionId: String, val title: String?, val failed: Boolean, val detail: String?, val at: Long) :
        NotificationAction

    data class PostApproval(override val sessionId: String, val title: String?, val approvalId: String, val toolName: String, val at: Long) :
        NotificationAction

    /**
     * [question] is the agent's text. A mistake-limit decision (G2, [kind] `MistakeLimit`) has
     * none: the notifier writes the desktop's question from string resources around
     * [mistakeDetail] (the request's detail, trimmed and cut like the sheet's), as the desktop's
     * notification shows the question its ask-question channel carries.
     */
    data class PostQuestion(
        override val sessionId: String,
        val title: String?,
        val requestId: String,
        val question: String,
        val at: Long,
        val kind: QuestionKind = QuestionKind.AskQuestion,
        val mistakeDetail: String? = null,
    ) : NotificationAction

    /** `run.started`: the previous result is stale. */
    data class CancelResult(override val sessionId: String) : NotificationAction

    /** `approval.resolved` (from any client). */
    data class CancelApproval(override val sessionId: String, val approvalId: String) : NotificationAction

    /** `capability.resolved` for the question (answered or cancelled). */
    data class CancelQuestion(override val sessionId: String, val requestId: String) : NotificationAction
}

/** One planning pass: what to do, and the request ids first seen in it (persisted, §11.5). */
data class NotificationPlan(val actions: List<NotificationAction>, val newlySeen: List<String>)

/**
 * The facts a pass needs besides the states. [autoAnswers]: the app answers this session's new
 * approvals itself (decided A1: Auto-approve all is on and its chat is open), so an approval in
 * a run of ours never posts; the request resolves moments later anyway.
 */
data class PlanContext(
    val nowMs: Long,
    val foreground: Boolean,
    val settings: NotificationSettings,
    val autoAnswers: (sessionId: String) -> Boolean = { false },
)

/**
 * The posting contract (settings-providers.md S3.3, customize-and-rest.md §11.3–11.5) as a pure
 * diff over successive session states. The desktop watcher works the same way: it reacts to
 * state snapshots (`tool_approval_state`, session status) and remembers what it already saw.
 *
 * - Approvals and questions are new when their id was not in the previous state; each id
 *   notifies at most once ([MAX_SEEN] LRU, seeded from the persisted copy). Like the desktop,
 *   an id is marked seen even when the notification is suppressed (the user saw it in the app).
 * - A terminal kind notifies at most once per run: `completed` (status `completed`, the
 *   `run.completed` reason) or `error` (`failed`). `cancelled` and other endings never notify.
 *   Completion is suppressed while the session has queued prompts.
 * - Nothing posts in the foreground, when the event is disabled, or for events older than
 *   [STALE_AFTER_MS] (a replay after a reconnect).
 * - A session that leaves the map (the store evicted it after 10 idle minutes) keeps its
 *   track, so its next event is compared with what was already notified rather than treated
 *   as a first observation. The [MAX_TRACKED] most recently planned tracks are kept.
 */
class NotificationPlanner(seen: Collection<String> = emptyList()) {
    private class Track(
        val approvals: Set<String>,
        val questions: Set<String>,
        val runKey: String?,
        val busy: Boolean,
        val status: SessionStatus,
        val terminalNotified: Boolean,
    )

    private val seen = LinkedHashSet<String>().apply { seen.forEach(::add) }

    /** Insertion order is planning recency: a planned session moves to the end. */
    private val tracks = LinkedHashMap<String, Track>()

    fun plan(states: Map<String, NotifyFacts>, ctx: PlanContext): NotificationPlan {
        val actions = mutableListOf<NotificationAction>()
        val newlySeen = mutableListOf<String>()
        for ((sessionId, facts) in states) {
            val next = planSession(sessionId, facts, tracks.remove(sessionId), ctx, actions, newlySeen)
            tracks[sessionId] = next
        }
        if (tracks.size > MAX_TRACKED) {
            val oldest = tracks.keys.iterator()
            while (tracks.size > MAX_TRACKED && oldest.hasNext()) {
                if (oldest.next() !in states) oldest.remove()
            }
        }
        return NotificationPlan(actions, newlySeen)
    }

    private fun planSession(
        sessionId: String,
        facts: NotifyFacts,
        track: Track?,
        ctx: PlanContext,
        actions: MutableList<NotificationAction>,
        newlySeen: MutableList<String>,
    ): Track {
        val title = facts.title?.trim()?.takeIf { it.isNotEmpty() }
        val allowed = { type: NotificationEventType, at: Long -> !ctx.foreground && ctx.settings[type].enabled && fresh(at, ctx.nowMs) }

        // run.started: a new run makes the old result stale and re-arms the terminal notice.
        val runKey = facts.activeRun?.let { "${it.runId ?: it.requestId.orEmpty()}@${it.startedAt}" }
        var terminalNotified = track?.terminalNotified ?: false
        if (runKey != null && runKey != track?.runKey) {
            terminalNotified = false
            actions += NotificationAction.CancelResult(sessionId)
        }

        // Terminal kinds (desktop handleTerminal).
        val terminal = if (facts.activeRun == null) {
            when (facts.status) {
                SessionStatus.Completed -> Terminal.Completed
                SessionStatus.Failed, SessionStatus.Error -> Terminal.Error
                else -> null
            }
        } else null
        val ended = when {
            terminal == null -> false
            track == null -> facts.sequenced
            else -> track.busy || track.status != facts.status
        }
        if (terminal != null && ended && !terminalNotified) {
            terminalNotified = true
            val at = facts.eventAt ?: 0L
            when (terminal) {
                Terminal.Completed -> if (!facts.hasQueuedPrompts && allowed(NotificationEventType.TaskCompletion, at)) {
                    actions += NotificationAction.PostResult(sessionId, title, failed = false, detail = null, at = at)
                }
                Terminal.Error -> if (allowed(NotificationEventType.SessionError, at)) {
                    actions += NotificationAction.PostResult(sessionId, title, failed = true, detail = facts.failureDetail, at = at)
                }
            }
        }

        // Approvals (dedupe by approvalId) and their resolution. One the app answers itself is
        // seen but never posted (Auto-approve all, decided A1).
        val previousApprovals = track?.approvals ?: emptySet()
        val selfAnswered = facts.activeRun?.ours == true && ctx.autoAnswers(sessionId)
        for (approval in facts.approvals.values.sortedBy { it.requestedAt }) {
            if (approval.approvalId in previousApprovals || !markSeen(approval.approvalId, newlySeen)) continue
            if (!selfAnswered && allowed(NotificationEventType.ApprovalNeeded, approval.requestedAt)) {
                actions += NotificationAction.PostApproval(sessionId, title, approval.approvalId, approval.toolName.trim(), approval.requestedAt)
            }
        }
        for (id in previousApprovals - facts.approvals.keys) actions += NotificationAction.CancelApproval(sessionId, id)

        // Questions (dedupe by requestId) and their resolution.
        val previousQuestions = track?.questions ?: emptySet()
        for (question in facts.questions.values.sortedBy { it.requestedAt }) {
            if (question.requestId in previousQuestions || !markSeen(question.requestId, newlySeen)) continue
            if (allowed(NotificationEventType.QuestionAsked, question.requestedAt)) {
                actions += NotificationAction.PostQuestion(
                    sessionId, title, question.requestId, question.question.trim(), question.requestedAt,
                    kind = question.kind,
                    mistakeDetail = if (question.kind == QuestionKind.MistakeLimit) {
                        MistakeLimitPrompt.truncatedDetail(question.mistakeLimit?.details).ifEmpty { null }
                    } else {
                        null
                    },
                )
            }
        }
        for (id in previousQuestions - facts.questions.keys) actions += NotificationAction.CancelQuestion(sessionId, id)

        return Track(
            approvals = facts.approvals.keys.toSet(),
            questions = facts.questions.keys.toSet(),
            runKey = runKey ?: track?.runKey,
            busy = facts.activeRun != null || facts.runPhase.isBusy,
            status = facts.status,
            terminalNotified = terminalNotified,
        )
    }

    /** Desktop `addSeenRequest`: false when already seen; the oldest id drops past [MAX_SEEN]. */
    private fun markSeen(id: String, newlySeen: MutableList<String>): Boolean {
        if (id in seen) return false
        seen += id
        if (seen.size > MAX_SEEN) seen.remove(seen.first())
        newlySeen += id
        return true
    }

    private enum class Terminal { Completed, Error }

    companion object {
        /** Desktop `MAX_SEEN_REQUESTS`. */
        const val MAX_SEEN = 500

        /** Tracks kept for sessions no longer in the map: as many as `KnownSessions` holds. */
        const val MAX_TRACKED = AppPreferences.MAX_KNOWN_SESSIONS

        /** SP S3.3: nothing posts for events older than 2 minutes (delivered by replay). */
        const val STALE_AFTER_MS = 2 * 60_000L

        fun fresh(eventAt: Long, nowMs: Long): Boolean = nowMs - eventAt <= STALE_AFTER_MS
    }
}

/**
 * Desktop `concise(value, maxLength = 180)`: whitespace runs become one space, trimmed; longer
 * than [maxLength] → the first `maxLength - 1` UTF-16 units + "…", cut back to the previous
 * grapheme-cluster boundary so model and user text never split mid-grapheme (DS §8.2.7).
 */
fun concise(value: String, maxLength: Int = 180): String {
    val normalized = value.replace(WHITESPACE, " ").trim()
    return if (normalized.length > maxLength) takeGraphemePrefix(normalized, maxLength - 1) + "…" else normalized
}

/** JavaScript's `\s` (ASCII whitespace plus the Unicode space separators, NBSP, BOM). */
private val WHITESPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+")

/** The persisted copy of seen approval / question ids (DataStore `notifications.seenRequestIds`, last 100). */
interface SeenRequestStore {
    suspend fun load(): List<String>
    suspend fun add(requestId: String)
}

class AppSeenRequestStore(private val prefs: AppPreferences) : SeenRequestStore {
    override suspend fun load(): List<String> = prefs.seenRequestIds()
    override suspend fun add(requestId: String) {
        prefs.addSeenRequestId(requestId)
    }
}
