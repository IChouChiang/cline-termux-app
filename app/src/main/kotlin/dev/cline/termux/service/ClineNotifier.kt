package dev.cline.termux.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.cline.termux.R
import dev.cline.termux.data.notifications.NotificationAction
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.data.notifications.NotificationPlanner
import dev.cline.termux.data.notifications.NotificationSettings
import dev.cline.termux.data.notifications.PlanContext
import dev.cline.termux.data.notifications.SeenRequestStore
import dev.cline.termux.data.notifications.concise
import dev.cline.termux.data.session.MistakeLimitPrompt
import dev.cline.termux.data.session.NotifyFacts
import dev.cline.termux.data.session.QuestionKind
import dev.cline.termux.ui.kernel.quantityResources
import java.time.Clock
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Posts the four session notifications (settings-providers.md S3.3) with the anatomy, ids,
 * delivery matrix, cancel rules and dedupe of customize-and-rest.md §11.2–11.5:
 *
 * - only for sessions in `KnownSessions` ([states] is already restricted to them);
 * - only while the app is in the background ([foreground] false);
 * - nothing for events older than two minutes (replay);
 * - approval / question ids deduped in memory (500) and in DataStore (last 100);
 * - tags `<sessionId>/result` (10), `/approval/<id>` (11), `/question/<id>` (12),
 *   `/summary` (13), `account` (20);
 * - a group summary while a session has two or more notifications.
 *
 * The chat calls [onSessionVisible] on its `ON_RESUME`; the delete flow and a tap that finds
 * the session gone call [onSessionGone].
 */
class ClineNotifier(
    context: Context,
    private val states: Flow<Map<String, NotifyFacts>>,
    private val settings: Flow<NotificationSettings>,
    private val seenStore: SeenRequestStore,
    private val foreground: StateFlow<Boolean>,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** [PlanContext.autoAnswers]: the app answers this session's new approvals itself (decided A1). */
    private val autoAnswers: (sessionId: String) -> Boolean = { false },
) {
    private val context: Context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)
    private val started = AtomicBoolean(false)

    /** Latest titles, for the group summary. */
    private val titles = HashMap<String, String?>()

    /** Starts observing; idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(dispatcher) {
            val planner = NotificationPlanner(runCatching { seenStore.load() }.getOrDefault(emptyList()))
            val latestSettings = settings.stateIn(this, SharingStarted.Eagerly, NotificationSettings.DEFAULT)
            combine(states, latestSettings) { map, prefs -> map to prefs }.collect { (map, prefs) ->
                val plan = planner.plan(map, PlanContext(clock.millis(), foreground.value, prefs, autoAnswers))
                plan.newlySeen.forEach { id -> runCatching { seenStore.add(id) } }
                synchronized(titles) {
                    titles.clear()
                    map.forEach { (id, s) -> titles[id] = s.title?.trim()?.takeIf(String::isNotEmpty) }
                }
                execute(plan.actions, prefs)
            }
        }
    }

    /** The chat for [sessionId] became visible (§11.4): every notification of that session goes. */
    fun onSessionVisible(sessionId: String) = cancelAll(sessionId)

    /** The session was deleted from the app, or a tap found it gone (§11.4). */
    fun onSessionGone(sessionId: String) = cancelAll(sessionId)

    /**
     * Sign in with Cline finished while the app was in the background (settings-providers.md S7
     * step 5): "Signed in to Cline" on the `account` channel; [origin] is the screen to reopen.
     * Returns false when nothing was posted (foreground, permission, blocked channel).
     */
    @SuppressLint("MissingPermission")
    fun postSignedIn(origin: Intent? = null): Boolean {
        if (foreground.value || !NotificationChannels.canPostOn(context, NotificationChannels.ACCOUNT)) return false
        val title = context.getString(R.string.notif_signed_in_title)
        val text = context.getString(R.string.notif_signed_in_body)
        val tap = origin?.let { NotificationRouter.open(context, TAG_ACCOUNT, it) } ?: NotificationRouter.openHome(context, TAG_ACCOUNT)
        val notification = NotificationCompat.Builder(context, NotificationChannels.ACCOUNT)
            .setSmallIcon(R.drawable.ic_stat_cline)
            .setColor(NotificationChannels.accentColor(context))
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setWhen(clock.millis())
            .build()
        return post(TAG_ACCOUNT, ID_ACCOUNT, notification)
    }

    fun cancelSignedIn() = manager.cancel(TAG_ACCOUNT, ID_ACCOUNT)

    // ---- execution -------------------------------------------------------------------------

    private fun execute(actions: List<NotificationAction>, prefs: NotificationSettings) {
        if (actions.isEmpty()) return
        val touched = LinkedHashMap<String, String?>()
        for (action in actions) {
            when (action) {
                is NotificationAction.PostResult -> {
                    val type = if (action.failed) NotificationEventType.SessionError else NotificationEventType.TaskCompletion
                    val channel = NotificationChannels.channelFor(type, prefs[type].sound)
                    if (postSession(resultTag(action.sessionId), ID_RESULT, channel, buildResult(action, channel))) {
                        touched[action.sessionId] = channel
                    }
                }
                is NotificationAction.PostApproval -> {
                    val type = NotificationEventType.ApprovalNeeded
                    val channel = NotificationChannels.channelFor(type, prefs[type].sound)
                    val tag = approvalTag(action.sessionId, action.approvalId)
                    if (postSession(tag, ID_APPROVAL, channel, buildApproval(action, channel, tag))) {
                        touched[action.sessionId] = channel
                    }
                }
                is NotificationAction.PostQuestion -> {
                    val type = NotificationEventType.QuestionAsked
                    val channel = NotificationChannels.channelFor(type, prefs[type].sound)
                    val tag = questionTag(action.sessionId, action.requestId)
                    if (postSession(tag, ID_QUESTION, channel, buildQuestion(action, channel, tag))) {
                        touched[action.sessionId] = channel
                    }
                }
                is NotificationAction.CancelResult -> cancel(action.sessionId, resultTag(action.sessionId), ID_RESULT, touched)
                is NotificationAction.CancelApproval -> cancel(action.sessionId, approvalTag(action.sessionId, action.approvalId), ID_APPROVAL, touched)
                is NotificationAction.CancelQuestion -> cancel(action.sessionId, questionTag(action.sessionId, action.requestId), ID_QUESTION, touched)
            }
        }
        touched.forEach { (sessionId, channel) -> updateSummary(sessionId, channel) }
    }

    private fun cancel(sessionId: String, tag: String, id: Int, touched: MutableMap<String, String?>) {
        manager.cancel(tag, id)
        if (sessionId !in touched) touched[sessionId] = null
    }

    /** A blocked channel or a missing permission posts nothing (§11.3 matrix). */
    private fun postSession(tag: String, id: Int, channel: String, notification: Notification): Boolean =
        NotificationChannels.canPostOn(context, channel) && post(tag, id, notification)

    @SuppressLint("MissingPermission")
    private fun post(tag: String, id: Int, notification: Notification): Boolean {
        if (!NotificationChannels.canPost(context)) return false
        return try {
            manager.notify(tag, id, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /** §11.2 "Group": a summary while ≥ 2 children are live; the last child gone takes it along. */
    private fun updateSummary(sessionId: String, postedChannel: String?) {
        val children = sessionNotifications(sessionId).filter { it.tag != summaryTag(sessionId) }
        if (children.size < 2) {
            manager.cancel(summaryTag(sessionId), ID_SUMMARY)
            return
        }
        val channel = postedChannel ?: children.maxBy { it.postTime }.notification.channelId ?: return
        val title = sessionTitle(synchronized(titles) { titles[sessionId] })
        val text = quantityResources(context.resources).getQuantityString(R.plurals.notif_group_summary, children.size, children.size)
        val summary = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_cline)
            .setColor(NotificationChannels.accentColor(context))
            .setContentTitle(title)
            .setContentText(text)
            .setGroup(groupKey(sessionId))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setContentIntent(NotificationRouter.openSession(context, summaryTag(sessionId), sessionId))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        post(summaryTag(sessionId), ID_SUMMARY, summary)
    }

    private fun cancelAll(sessionId: String) {
        for (sbn in sessionNotifications(sessionId)) manager.cancel(sbn.tag, sbn.id)
        manager.cancel(summaryTag(sessionId), ID_SUMMARY)
    }

    private fun sessionNotifications(sessionId: String) =
        NotificationChannels.manager(context).activeNotifications.filter { it.tag?.startsWith("$sessionId/") == true }

    // ---- anatomy (§11.2) ---------------------------------------------------------------------

    private fun buildResult(action: NotificationAction.PostResult, channel: String): Notification {
        val title = context.getString(if (action.failed) R.string.notif_task_failed_title else R.string.notif_task_completed_title)
        val body = when {
            !action.failed -> context.getString(R.string.notif_task_completed_body)
            !action.detail.isNullOrBlank() -> action.detail
            else -> context.getString(R.string.notif_task_failed_body)
        }
        val tag = resultTag(action.sessionId)
        return base(
            channel = channel,
            sessionId = action.sessionId,
            sessionTitle = action.title,
            title = title,
            body = body,
            at = action.at,
            category = if (action.failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS,
            error = action.failed,
            tap = NotificationRouter.openSession(context, tag, action.sessionId),
        )
    }

    private fun buildApproval(action: NotificationAction.PostApproval, channel: String, tag: String): Notification {
        val tool = action.toolName.ifBlank { context.getString(R.string.notif_approval_tool_fallback) }
        return base(
            channel = channel,
            sessionId = action.sessionId,
            sessionTitle = action.title,
            title = context.getString(R.string.notif_approval_title),
            body = context.getString(R.string.notif_approval_body, tool),
            at = action.at,
            category = NotificationCompat.CATEGORY_REMINDER,
            error = false,
            tap = NotificationRouter.openSession(context, tag, action.sessionId, NotificationRouter.Sheet.Approval),
        )
    }

    private fun buildQuestion(action: NotificationAction.PostQuestion, channel: String, tag: String): Notification = base(
        channel = channel,
        sessionId = action.sessionId,
        sessionTitle = action.title,
        title = context.getString(R.string.notif_question_title),
        body = questionBody(action),
        at = action.at,
        category = NotificationCompat.CATEGORY_REMINDER,
        error = false,
        tap = NotificationRouter.openSession(context, tag, action.sessionId, NotificationRouter.Sheet.Question),
    )

    /**
     * The agent's question; for a mistake-limit decision (G2) the desktop's question, the same
     * lines the sheet shows (its notification carries the ask-question text). [base] collapses
     * the line breaks and cuts the text to the notification's length.
     */
    private fun questionBody(action: NotificationAction.PostQuestion): String = when (action.kind) {
        QuestionKind.MistakeLimit -> MistakeLimitPrompt.question(
            intro = context.getString(R.string.mistake_limit_question_intro),
            latest = action.mistakeDetail?.let { context.getString(R.string.mistake_limit_question_latest, it) },
            ask = context.getString(R.string.mistake_limit_question_ask),
        )
        QuestionKind.AskQuestion -> action.question.ifBlank { context.getString(R.string.notif_question_body_fallback) }
    }

    private fun base(
        channel: String,
        sessionId: String,
        sessionTitle: String?,
        title: String,
        body: String,
        at: Long,
        category: String,
        error: Boolean,
        tap: PendingIntent,
    ): Notification {
        val text = concise(body)
        val color = NotificationChannels.accentColor(context, error)
        // Lock screen: the title only, no tool name or question text (§11.2).
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_cline)
            .setColor(color)
            .setContentTitle(title)
            .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_cline)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(sessionTitle(sessionTitle))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setShowWhen(true)
            .setWhen(at)
            .setCategory(category)
            .setGroup(groupKey(sessionId))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
    }

    private fun sessionTitle(title: String?): String = title ?: context.getString(R.string.notif_session_untitled)

    companion object {
        const val ID_RESULT = 10
        const val ID_APPROVAL = 11
        const val ID_QUESTION = 12
        const val ID_SUMMARY = 13
        const val ID_ACCOUNT = 20
        const val TAG_ACCOUNT = "account"

        fun resultTag(sessionId: String) = "$sessionId/result"
        fun approvalTag(sessionId: String, approvalId: String) = "$sessionId/approval/$approvalId"
        fun questionTag(sessionId: String, requestId: String) = "$sessionId/question/$requestId"
        fun summaryTag(sessionId: String) = "$sessionId/summary"
        fun groupKey(sessionId: String) = "session-$sessionId"
    }
}
