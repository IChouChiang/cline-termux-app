package dev.cline.termux.ui.composer

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.ui.theme.ClinePreviewTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Trial row 12: the steer long-press on Send (A11 row 5) gives one haptic. `combinedClickable`
 * performs its own `LongPress`; the explicit one the button added on top made it buzz twice, 1 ms
 * apart, on the S25U. A real touch on the real button, with the haptics counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SteerHapticTest {
    private class CountingHaptics : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    /** A run is active and the field has text: a tap queues, a long-press steers. */
    private val busyWithDraft = ComposerUiState.initial(ComposerVariant.Conversation, "s1").copy(
        phase = RunPhase.Running,
        isBusy = true,
        hasDraft = true,
        canSend = true,
        canAbort = false,
    )

    private class Harness(val view: View, val center: Offset, val haptics: CountingHaptics, val steers: () -> Int, val sends: () -> Int)

    private var controller: ActivityController<ComponentActivity>? = null

    /**
     * The activity goes with the test and the main looper is drained after it, so nothing this
     * test started is left for the next test's looper reset: a Compose dispatch dropped there
     * leaves Compose's main dispatcher waiting for good, and every later Compose test in this
     * sandbox stops recomposing (see `RouteNavigationTest.drainMainLooper`).
     */
    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
        controller = null
        idle()
    }

    private fun sendButton(): Harness {
        val haptics = CountingHaptics()
        var steers = 0
        var sends = 0
        var center: Offset? = null
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).also { controller = it }.setup().get()
        activity.setContent {
            ClinePreviewTheme(dark = false) {
                CompositionLocalProvider(LocalHapticFeedback provides haptics) {
                    Box(Modifier.onGloballyPositioned { center = it.boundsInRoot().center }) {
                        SendStopGroup(busyWithDraft, onSend = { sends++ }, onStop = {}, onSteer = { steers++ })
                    }
                }
            }
        }
        idle()
        val view = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        return Harness(view, checkNotNull(center) { "Send was not laid out" }, haptics, { steers }, { sends })
    }

    @Test
    fun `a steer long-press on Send buzzes once`() {
        val h = sendButton()
        val down = SystemClock.uptimeMillis()
        h.view.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, h.center.x, h.center.y, 0))
        // The long-press timeout is a coroutine delay on the wall clock; its continuation then runs on the main looper.
        val deadline = System.nanoTime() + 5_000_000_000L
        while (h.steers() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(50)
            shadowOf(Looper.getMainLooper()).idle()
        }
        h.view.dispatchTouchEvent(MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, h.center.x, h.center.y, 0))
        idle()

        assertEquals("the long-press steered", 1, h.steers())
        assertEquals("a long-press is not also a tap", 0, h.sends())
        assertEquals(listOf(HapticFeedbackType.LongPress), h.haptics.performed)
    }

    @Test
    fun `a tap on the busy Send queues without a haptic`() {
        val h = sendButton()
        val down = SystemClock.uptimeMillis()
        h.view.dispatchTouchEvent(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, h.center.x, h.center.y, 0))
        h.view.dispatchTouchEvent(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, h.center.x, h.center.y, 0))
        idle()

        assertEquals(1, h.sends())
        assertEquals(0, h.steers())
        assertTrue(h.haptics.performed.isEmpty())
    }

    private fun idle() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
    }
}
