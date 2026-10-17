package dev.cline.termux.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.data.notifications.NotificationStates.running
import dev.cline.termux.data.notifications.NotificationStates.T0
import dev.cline.termux.data.notifications.KeepAlivePolicy
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.notifications.NotificationStates.factsOf
import dev.cline.termux.data.session.NotifyFacts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Channels (SP S3.3, CR §11.7), tap routing (CA §0.6) and the foreground-only service start (CR §12.2). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationChannelsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm: NotificationManager = app.getSystemService(NotificationManager::class.java)

    @Test
    fun ensureCreatesTheEightSessionChannelsAccountAndKernelAndDeletesTheLegacyOnes() {
        nm.createNotificationChannel(NotificationChannel("sessions", "Session updates", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("attention", "Needs your attention", NotificationManager.IMPORTANCE_HIGH))
        NotificationChannels.ensure(app)

        val ids = nm.notificationChannels.map { it.id }.toSet()
        assertEquals(
            setOf(
                "kernel", "account",
                "task_completed", "task_completed_quiet", "approval_needed", "approval_needed_quiet",
                "question_asked", "question_asked_quiet", "session_error", "session_error_quiet",
            ),
            ids,
        )
        assertNull(nm.getNotificationChannel("sessions"))
        assertNull(nm.getNotificationChannel("attention"))

        val kernel = nm.getNotificationChannel("kernel")
        assertEquals(NotificationManager.IMPORTANCE_LOW, kernel.importance)
        assertEquals("Kernel", kernel.name)
        assertFalse(kernel.canShowBadge())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, nm.getNotificationChannel("account").importance)

        fun check(id: String, importance: Int, sound: Boolean, vibrates: Boolean, name: String) {
            val channel = nm.getNotificationChannel(id)
            assertEquals(id, importance, channel.importance)
            assertEquals(id, sound, channel.sound != null)
            assertEquals(id, vibrates, channel.shouldVibrate())
            assertEquals(id, name, channel.name)
            assertEquals(id, NotificationChannels.GROUP_SESSIONS, channel.group)
        }
        check("task_completed", NotificationManager.IMPORTANCE_DEFAULT, sound = true, vibrates = true, name = "Task completed")
        check("task_completed_quiet", NotificationManager.IMPORTANCE_DEFAULT, sound = false, vibrates = false, name = "Task completed (silent)")
        check("approval_needed", NotificationManager.IMPORTANCE_HIGH, sound = true, vibrates = true, name = "Approval needed")
        check("approval_needed_quiet", NotificationManager.IMPORTANCE_HIGH, sound = false, vibrates = true, name = "Approval needed (no sound)")
        check("question_asked", NotificationManager.IMPORTANCE_HIGH, sound = true, vibrates = true, name = "Question asked")
        check("question_asked_quiet", NotificationManager.IMPORTANCE_HIGH, sound = false, vibrates = true, name = "Question asked (no sound)")
        check("session_error", NotificationManager.IMPORTANCE_DEFAULT, sound = true, vibrates = true, name = "Session error")
        check("session_error_quiet", NotificationManager.IMPORTANCE_DEFAULT, sound = false, vibrates = false, name = "Session error (silent)")
        assertEquals("Sessions", nm.getNotificationChannelGroup(NotificationChannels.GROUP_SESSIONS).name)
    }

    @Test
    fun theSoundToggleChoosesTheChannel() {
        assertEquals("task_completed", NotificationChannels.channelFor(NotificationEventType.TaskCompletion, sound = true))
        assertEquals("task_completed_quiet", NotificationChannels.channelFor(NotificationEventType.TaskCompletion, sound = false))
        assertEquals("approval_needed_quiet", NotificationChannels.channelFor(NotificationEventType.ApprovalNeeded, sound = false))
        assertEquals("question_asked", NotificationChannels.channelFor(NotificationEventType.QuestionAsked, sound = true))
        assertEquals("session_error_quiet", NotificationChannels.channelFor(NotificationEventType.SessionError, sound = false))
        assertEquals(8, NotificationChannels.sessionChannelIds.size)
    }

    @Test
    fun routerPutsTheExtrasAndUsesTheTagAsRequestCode() {
        val pending = NotificationRouter.openSession(app, "s1/approval/a1", "s1", NotificationRouter.Sheet.Approval)
        val intent = shadowOf(pending).savedIntent
        assertEquals("s1", intent.getStringExtra(NotificationRouter.EXTRA_SESSION_ID))
        assertEquals("approval", intent.getStringExtra(NotificationRouter.EXTRA_OPEN_SHEET))
        assertNull(intent.data)
        assertEquals("s1/approval/a1".hashCode(), shadowOf(pending).requestCode)
        assertTrue(shadowOf(pending).isImmutable)

        assertEquals(NotificationRouter.Target("s1", NotificationRouter.Sheet.Approval), NotificationRouter.targetOf(intent))
        NotificationRouter.consume(intent)
        assertNull(NotificationRouter.targetOf(intent))
        assertNull(NotificationRouter.targetOf(NotificationRouter.homeIntent(app)))
    }

    @Test
    fun launcherStartsOnlyWhileTheAppIsVisible() = runTest(UnconfinedTestDispatcher()) {
        val states = MutableStateFlow<Map<String, NotifyFacts>>(emptyMap())
        val foreground = MutableStateFlow(false)
        val keepConnected = MutableStateFlow(false)
        val policy = KeepAlivePolicy(states, keepConnected, MutableStateFlow<KernelGate>(KernelGate.Ready), foreground, backgroundScope)
        var starts = 0
        val launcher = KeepAliveLauncher(app, policy, foreground, backgroundScope, starter = { _, _ -> starts++; true }, dispatcher = UnconfinedTestDispatcher(testScheduler))
        launcher.start()

        states.value = factsOf(running("s1"))
        runCurrent()
        assertTrue(policy.state.value.hold)
        assertEquals("a hold that begins in the background cannot start the service", 0, starts)

        states.value = emptyMap()
        foreground.value = true
        runCurrent()
        states.value = factsOf(running("s1", runId = "run-2", startedAt = T0 + 1, seq = 5))
        runCurrent()
        assertEquals(1, starts)

        // ON_PAUSE with keepConnected: the hold begins once backgrounded, so start now.
        policy.reportStopped()
        states.value = emptyMap()
        keepConnected.value = true
        runCurrent()
        launcher.onActivityPaused()
        assertEquals(2, starts)
    }
}
