package dev.cline.termux.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.data.notifications.NotificationPreference
import dev.cline.termux.data.notifications.NotificationSettings
import dev.cline.termux.data.notifications.NotificationStates.T0
import dev.cline.termux.data.notifications.NotificationStates.ended
import dev.cline.termux.data.notifications.NotificationStates.idle
import dev.cline.termux.data.notifications.NotificationStates.running
import dev.cline.termux.data.notifications.NotificationStates.withApproval
import dev.cline.termux.data.notifications.NotificationStates.withMistakeLimit
import dev.cline.termux.data.notifications.NotificationStates.withQuestion
import dev.cline.termux.data.notifications.NotificationStates.withQueued
import dev.cline.termux.data.notifications.NotificationStates.withoutApproval
import dev.cline.termux.data.notifications.NotificationStates.withoutQuestion
import dev.cline.termux.data.notifications.SeenRequestStore
import dev.cline.termux.data.notifications.NotificationStates.factsOf
import dev.cline.termux.data.session.NotifyFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.fakes.FakeClock
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The CR §11.3 delivery matrix, §11.2 anatomy and ids, §11.4 cancel rules, on real notifications. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClineNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = app.getSystemService(NotificationManager::class.java)
    private val states = MutableStateFlow<Map<String, NotifyFacts>>(emptyMap())
    private val settings = MutableStateFlow(NotificationSettings.DEFAULT)
    private val foreground = MutableStateFlow(false)
    private val clock = FakeClock(Instant.ofEpochMilli(T0 + 5_000))
    private val seen = object : SeenRequestStore {
        val ids = mutableListOf<String>()
        override suspend fun load(): List<String> = ids.toList()
        override suspend fun add(requestId: String) {
            ids += requestId
        }
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        NotificationChannels.ensure(app)
        nm.cancelAll()
    }

    private fun TestScope.notifier(): ClineNotifier {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return ClineNotifier(app, states, settings, seen, foreground, clock, backgroundScope, dispatcher).also {
            it.start()
            runCurrent()
        }
    }

    private fun TestScope.put(vararg s: SessionState) {
        states.value = factsOf(*s)
        runCurrent()
    }

    private fun active(): Map<String, StatusBarNotification> = nm.activeNotifications.associateBy { it.tag }
    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private val Notification.subText get() = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

    @Test
    fun completedTaskPostsOnTheQuietChannelWithTheSessionTitle() = runTest {
        notifier()
        put(running("s1"))
        put(running("s1").ended(SessionStatus.Completed, at = T0 + 1_000))
        val sbn = active().getValue("s1/result")
        assertEquals(ClineNotifier.ID_RESULT, sbn.id)
        val n = sbn.notification
        assertEquals("task_completed_quiet", n.channelId)
        assertEquals("Task completed", n.title)
        assertEquals("Cline finished working and the result is ready.", n.text)
        assertEquals("Fix the login redirect", n.subText)
        assertEquals(Notification.CATEGORY_STATUS, n.category)
        assertEquals("session-s1", n.group)
        assertEquals(T0 + 1_000, n.`when`)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Task completed", n.publicVersion.title)
        assertNull(n.publicVersion.text)
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals("s1", tap.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
        assertNull(tap.getStringExtra(NotificationRouter.EXTRA_OPEN_SHEET))
        assertTrue(shadowOf(n.contentIntent).isActivityIntent)
    }

    @Test
    fun soundOnUsesTheSoundingChannel() = runTest {
        settings.value = NotificationSettings.DEFAULT.with(NotificationEventType.ApprovalNeeded, NotificationPreference(enabled = true, sound = true))
        notifier()
        put(running("s1").withApproval("a1"))
        val n = active().getValue("s1/approval/a1").notification
        assertEquals("approval_needed", n.channelId)
        assertEquals(0, n.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    @Test
    fun foregroundPostsNothing() = runTest {
        foreground.value = true
        notifier()
        put(running("s1").withApproval("a1"))
        put(running("s1").ended(SessionStatus.Completed, at = T0 + 1_000))
        assertTrue(active().isEmpty())
    }

    @Test
    fun approvalAnatomyRoutingAndCancel() = runTest {
        notifier()
        put(running("s1").withApproval("a1", toolName = "run_commands"))
        val sbn = active().getValue("s1/approval/a1")
        assertEquals(ClineNotifier.ID_APPROVAL, sbn.id)
        val n = sbn.notification
        assertEquals("approval_needed_quiet", n.channelId)
        assertEquals("Approval needed", n.title)
        assertEquals("run_commands is waiting for your approval.", n.text)
        assertEquals(Notification.CATEGORY_REMINDER, n.category)
        assertEquals("Approval needed", n.publicVersion.title)
        val tap = shadowOf(n.contentIntent).savedIntent
        assertEquals("s1", tap.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
        assertEquals("approval", tap.getStringExtra(NotificationRouter.EXTRA_OPEN_SHEET))
        assertEquals(listOf("a1"), seen.ids)

        put(running("s1").withApproval("a1").withoutApproval("a1"))
        assertNull(active()["s1/approval/a1"])
    }

    @Test
    fun approvalWithoutToolNameUsesTheFallback() = runTest {
        notifier()
        put(running("s1").withApproval("a1", toolName = " "))
        assertEquals("A tool is waiting for your approval.", active().getValue("s1/approval/a1").notification.text)
    }

    @Test
    fun questionBodyIsConciseAndRoutesToTheQuestionSheet() = runTest {
        notifier()
        val question = "Which   branch\n" + "x".repeat(400)
        put(running("s1").withQuestion("q1", question))
        val n = active().getValue("s1/question/q1").notification
        assertEquals("Cline has a question", n.title)
        assertEquals(180, n.text!!.length)
        assertTrue(n.text!!.startsWith("Which branch x"))
        assertTrue(n.text!!.endsWith("…"))
        assertEquals("question", shadowOf(n.contentIntent).savedIntent.getStringExtra(NotificationRouter.EXTRA_OPEN_SHEET))
        put(running("s1").withQuestion("q1", question).withoutQuestion("q1"))
        assertNull(active()["s1/question/q1"])
    }

    @Test
    fun mistakeLimitQuestionShowsTheDesktopQuestionNotTheFallback() = runTest {
        notifier()
        put(running("s1").withMistakeLimit("m1", details = "Tool no_such_tool does not exist"))
        val n = active().getValue("s1/question/m1").notification
        assertEquals("Cline has a question", n.title)
        // The desktop's three lines (its notification carries the ask-question text), on one line.
        assertEquals(
            "Cline detected repeated mistakes or tool calls and needs your guidance. Latest: Tool no_such_tool does not exist " +
                "How should Cline continue?",
            n.text,
        )
        assertEquals("question", shadowOf(n.contentIntent).savedIntent.getStringExtra(NotificationRouter.EXTRA_OPEN_SHEET))

        // Without a detail the "Latest:" line is left out; a long one is cut to the notification's length.
        put(running("s2").withMistakeLimit("m2", details = null))
        assertEquals(
            "Cline detected repeated mistakes or tool calls and needs your guidance. How should Cline continue?",
            active().getValue("s2/question/m2").notification.text,
        )
        put(running("s3").withMistakeLimit("m3", details = "y".repeat(650)))
        val cut = active().getValue("s3/question/m3").notification.text!!
        assertEquals(180, cut.length)
        assertTrue(cut.startsWith("Cline detected repeated mistakes or tool calls and needs your guidance. Latest: yyy"))
    }

    @Test
    fun emptyQuestionUsesTheFallbackBody() = runTest {
        notifier()
        put(running("s1").withQuestion("q1", ""))
        assertEquals("Open this task to answer Cline's question.", active().getValue("s1/question/q1").notification.text)
    }

    @Test
    fun failedTaskShowsTheErrorWithTheErrorAccent() = runTest {
        notifier()
        put(running("s1"))
        put(running("s1").ended(SessionStatus.Failed, at = T0 + 1_000, error = "Provider returned 401"))
        val n = active().getValue("s1/result").notification
        assertEquals("session_error_quiet", n.channelId)
        assertEquals("Task failed", n.title)
        assertEquals("Provider returned 401", n.text)
        assertEquals(Notification.CATEGORY_ERROR, n.category)
        assertEquals(NotificationChannels.accentColor(app, error = true), n.color)
    }

    @Test
    fun failedTaskWithoutTextUsesTheDesktopFallback() = runTest {
        notifier()
        put(running("s1"))
        put(running("s1").ended(SessionStatus.Failed, at = T0 + 1_000))
        assertEquals("Cline encountered an error while running this task.", active().getValue("s1/result").notification.text)
    }

    @Test
    fun abortedRunsAndQueuedCompletionsPostNothing() = runTest {
        notifier()
        put(running("s1"), running("s2").withQueued(2))
        put(running("s1").ended(SessionStatus.Cancelled, at = T0 + 1_000), running("s2").withQueued(2).ended(SessionStatus.Completed, at = T0 + 1_000))
        assertTrue(active().isEmpty())
    }

    @Test
    fun aNewRunCancelsTheOldResult() = runTest {
        notifier()
        put(running("s1"))
        put(running("s1").ended(SessionStatus.Completed, at = T0 + 1_000))
        assertNotNull(active()["s1/result"])
        put(running("s1", runId = "run-2", startedAt = T0 + 2_000, seq = 20))
        assertNull(active()["s1/result"])
    }

    @Test
    fun twoApprovalsAddAGroupSummaryThatLeavesWithTheLastChild() = runTest {
        notifier()
        val two = running("s1").withApproval("a1").withApproval("a2", toolName = "editor", at = T0 + 1)
        put(two)
        val summary = active().getValue("s1/summary")
        assertEquals(ClineNotifier.ID_SUMMARY, summary.id)
        assertTrue(summary.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals("Fix the login redirect", summary.notification.title)
        assertEquals("2 updates", summary.notification.text)
        assertEquals("approval_needed_quiet", summary.notification.channelId)
        assertEquals(Notification.GROUP_ALERT_CHILDREN, summary.notification.groupAlertBehavior)

        put(two.withoutApproval("a1"))
        assertNull(active()["s1/summary"])
        put(two.withoutApproval("a1").withoutApproval("a2"))
        assertTrue(active().isEmpty())
    }

    @Test
    fun visibleChatCancelsEverythingOfThatSessionOnly() = runTest {
        val notifier = notifier()
        put(running("s1").withApproval("a1").withApproval("a2", at = T0 + 1), running("s2").withApproval("b1"))
        notifier.onSessionVisible("s1")
        assertEquals(setOf("s2/approval/b1"), active().keys)
        notifier.onSessionGone("s2")
        assertTrue(active().isEmpty())
    }

    @Test
    fun staleReplayIsNotPosted() = runTest {
        clock.set(Instant.ofEpochMilli(T0 + 3 * 60_000))
        notifier()
        put(running("s1").withApproval("a1", at = T0))
        assertTrue(active().isEmpty())
    }

    @Test
    fun disabledEventOrBlockedChannelOrMissingPermissionPostNothing() = runTest {
        settings.value = NotificationSettings.DEFAULT.with(NotificationEventType.ApprovalNeeded, NotificationPreference(enabled = false))
        notifier()
        put(running("s1").withApproval("a1"))
        assertTrue(active().isEmpty())

        // The user blocks the channel in system settings.
        nm.createNotificationChannel(NotificationChannel("question_asked_quiet", "Question asked (no sound)", NotificationManager.IMPORTANCE_NONE))
        assertTrue(NotificationChannels.isBlocked(app, "question_asked_quiet"))
        put(running("s1").withApproval("a1").withQuestion("q1", "Which?"))
        assertTrue(active().isEmpty())

        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationChannels.canPost(app))
        put(running("s2"))
        put(running("s2").ended(SessionStatus.Completed, at = T0 + 1_000), running("s1"))
        assertTrue(active().isEmpty())
    }

    @Test
    fun seenIdsAreNotRepostedAfterARestart() = runTest {
        seen.ids += "a1"
        notifier()
        put(running("s1").withApproval("a1"))
        assertTrue(active().isEmpty())
    }

    @Test
    fun untitledSessionsUseNewSession() = runTest {
        notifier()
        put(running("s1", title = null).withApproval("a1"))
        assertEquals("New Session", active().getValue("s1/approval/a1").notification.subText)
    }

    @Test
    fun signedInPostsOnTheAccountChannelOnlyInTheBackground() = runTest {
        val notifier = notifier()
        foreground.value = true
        assertFalse(notifier.postSignedIn())
        foreground.value = false
        assertTrue(notifier.postSignedIn())
        val sbn = active().getValue(ClineNotifier.TAG_ACCOUNT)
        assertEquals(ClineNotifier.ID_ACCOUNT, sbn.id)
        assertEquals(NotificationChannels.ACCOUNT, sbn.notification.channelId)
        assertEquals("Signed in to Cline", sbn.notification.title)
        assertEquals("Return to Cline to start building.", sbn.notification.text)
        notifier.cancelSignedIn()
        assertTrue(active().isEmpty())
    }

    @Test
    fun idleSessionsPostNothing() = runTest {
        notifier()
        put(idle("s1"))
        assertTrue(active().isEmpty())
        assertTrue(NotificationManagerCompat.from(app).areNotificationsEnabled())
    }
}
