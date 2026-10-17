package dev.cline.termux.service

import android.app.Application
import android.app.Notification
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.notifications.HoldState
import dev.cline.termux.data.notifications.KeepAliveVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Every variant of the foreground notification (customize-and-rest.md §12.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KernelNotificationTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun build(variant: KeepAliveVariant, reconnecting: Boolean = false, stopAvailable: Boolean = true): Notification =
        KernelNotification.build(app, HoldState(hold = true, variant = variant, reconnecting = reconnecting, stopAvailable = stopAvailable))

    private val Notification.title get() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private val Notification.text get() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private val Notification.actionTitles get() = actions?.map { it.title.toString() }.orEmpty()
    private val Notification.tap: Intent get() = shadowOf(contentIntent).savedIntent

    private fun assertServiceBasics(n: Notification) {
        assertEquals(NotificationChannels.KERNEL, n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(Notification.CATEGORY_SERVICE, n.category)
    }

    @Test
    fun oneRunWorkingHasTheChronometerAndBothActions() {
        val n = build(KeepAliveVariant.Working("s1", "Fix the login redirect", 1_790_000_000_000L))
        assertServiceBasics(n)
        assertEquals("Cline is working...", n.title)
        assertEquals("Fix the login redirect", n.text)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(1_790_000_000_000L, n.`when`)
        assertEquals(listOf("Stop agent", "Disconnect"), n.actionTitles)
        assertEquals("s1", n.tap.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Cline is working...", n.publicVersion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val stop = shadowOf(n.actions[0].actionIntent)
        assertTrue(stop.isBroadcastIntent)
        assertEquals(KernelServiceActions.ACTION_STOP_AGENT, stop.savedIntent.action)
        assertEquals("s1", stop.savedIntent.getStringExtra(KernelServiceActions.EXTRA_SESSION_ID))
        assertEquals(KernelServiceActions.ACTION_DISCONNECT, shadowOf(n.actions[1].actionIntent).savedIntent.action)
    }

    @Test
    fun aFirstSendWorksWithoutStopAndOpensHome() {
        val n = build(KeepAliveVariant.Sending("slow story please", 1_790_000_000_000L))
        assertServiceBasics(n)
        assertEquals("Cline is working...", n.title)
        assertEquals("slow story please", n.text)
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Disconnect"), n.actionTitles)
        assertNull(n.tap.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("New Session", build(KeepAliveVariant.Sending(null, 1L)).text)
    }

    @Test
    fun stopAgentOnlyWhileReady() {
        assertEquals(listOf("Disconnect"), build(KeepAliveVariant.Working("s1", "T", 1L), stopAvailable = false).actionTitles)
    }

    @Test
    fun reconnectingReplacesTheTextPausesTheTimerAndDropsStop() {
        val n = build(KeepAliveVariant.Working("s1", "Fix the login redirect", 1L), reconnecting = true, stopAvailable = false)
        assertEquals("Cline is working...", n.title)
        assertEquals("Reconnecting...", n.text)
        assertFalse(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Disconnect"), n.actionTitles)
        // Even if the gate says Ready, a reconnecting state never offers Stop.
        assertEquals(listOf("Disconnect"), build(KeepAliveVariant.Working("s1", "T", 1L), reconnecting = true, stopAvailable = true).actionTitles)
    }

    @Test
    fun waitingForApprovalOpensTheApprovalSheet() {
        val n = build(KeepAliveVariant.WaitingApproval("s1", "run_commands"))
        assertEquals("Waiting for your approval", n.title)
        assertEquals("run_commands is waiting for your approval.", n.text)
        assertFalse(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(listOf("Disconnect"), n.actionTitles)
        assertEquals("approval", n.tap.getStringExtra(NotificationRouter.EXTRA_OPEN_SHEET))
        assertEquals("A tool is waiting for your approval.", build(KeepAliveVariant.WaitingApproval("s1", "")).text)
    }

    @Test
    fun waitingForAnAnswerOpensTheQuestionSheet() {
        val n = build(KeepAliveVariant.WaitingAnswer("s1", null))
        assertEquals("Cline has a question", n.title)
        assertEquals("New Session", n.text)
        assertEquals(listOf("Disconnect"), n.actionTitles)
        assertEquals("question", n.tap.getStringExtra(NotificationRouter.EXTRA_OPEN_SHEET))
    }

    @Test
    fun severalRunsListTheTitlesAndOpenHome() {
        val n = build(KeepAliveVariant.Several(listOf("First", null, "Third", "4", "5", "6")))
        assertEquals("Cline is working on 6 tasks", n.title)
        assertEquals("First, New Session, Third, 4, 5, 6", n.text)
        val lines = n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)!!.map { it.toString() }
        assertEquals(listOf("First", "New Session", "Third", "4", "5"), lines)
        assertEquals(listOf("Disconnect"), n.actionTitles)
        assertNull(n.tap.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
    }

    @Test
    fun keepConnectedIdleIsPublic() {
        val n = build(KeepAliveVariant.ConnectedIdle)
        assertEquals("Connected to the Cline Hub", n.title)
        assertEquals("Cline will notify you when a task needs you.", n.text)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(listOf("Disconnect"), n.actionTitles)
        assertNull(n.tap.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
    }

    @Test
    fun stoppingHasNoActions() {
        val n = build(KeepAliveVariant.Stopping("s1", "Fix the login redirect"))
        assertEquals("Stopping...", n.title)
        assertEquals("Fix the login redirect", n.text)
        assertTrue(n.actionTitles.isEmpty())
        assertEquals("s1", n.tap.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
    }
}
