package dev.cline.termux.ui.chat

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.composer.ComposerDock
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
import org.robolectric.annotation.GraphicsMode

/**
 * Trial row 13: with the keyboard open in landscape (a height-compact window, D18) the stall
 * notice was pushed under the status bar. Laid out for real (native graphics, so text has its
 * real width) in an S25U-sized landscape window: the notice fits the 56dp a docked banner has
 * there, and the docked stack keeps a banner inside the pane whatever its height.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StallNoticeLayoutTest {
    private val controllers = mutableListOf<ActivityController<ComponentActivity>>()

    /** The activities go with the test and the main looper is drained, as in `SteerHapticTest`. */
    @After
    fun tearDown() {
        controllers.forEach { it.pause().stop().destroy() }
        controllers.clear()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun `typing in landscape the stall notice is one row within a docked banner's 56dp`() {
        // The S25U's docked column in landscape is about 760dp wide.
        assertTrue(noticeHeight(760.dp, singleRow = true) <= 56f)
        // Why it needs the row: the stacked notice is taller than the banner the keyboard leaves room for.
        assertTrue(noticeHeight(760.dp, singleRow = false) > 56f)
    }

    @Test
    fun `a notice whose text does not fit beside the actions keeps the stacked form`() {
        // A narrow window, then large text: the row would squeeze the text, so the notice stays stacked.
        assertEquals(noticeHeight(360.dp, singleRow = false), noticeHeight(360.dp, singleRow = true), 0.5f)
        assertEquals(noticeHeight(760.dp, singleRow = false, fontScale = 2f), noticeHeight(760.dp, singleRow = true, fontScale = 2f), 0.5f)
    }

    @Test
    fun `while typing in a height-compact window a docked banner stays inside the pane`() {
        // A docked stack a little taller than the room the keyboard leaves under the status bar
        // (the app bar steps aside while typing): the stacked notice above a composer.
        val statusBar = 24.dp
        val pane = 150.dp
        val bound = DockRules.maxHeight(pane, heightCompact = true, imeOpen = true, bannerVisible = true)
        val bounded = noticeBounds(statusBar, pane, bound)
        assertEquals("clipped at the top of the pane, not under the status bar", statusBar.value, bounded.top, 0.5f)
        assertTrue("the rest of it scrolls into view", bounded.bottom > bounded.top)
        // The unbounded stack of C1.4 step 6, which the stall notice met: it reached under the status bar.
        assertTrue(noticeBounds(statusBar, pane, Dp.Unspecified).top < statusBar.value - 1f)
    }

    /** The notice's height in dp, laid out [width] wide. */
    private fun noticeHeight(width: Dp, singleRow: Boolean, fontScale: Float = 1f): Float {
        var height = -1
        val activity = compose {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.width(width).onSizeChanged { height = it.height }) {
                    StallBanner(onRestartKernel = {}, onKeepWaiting = {}, singleRow = singleRow)
                }
            }
        }
        assertTrue("laid out", height > 0)
        return height / activity.resources.displayMetrics.density
    }

    /**
     * What the notice draws (its bounds after the clips above it), in dp from the window top,
     * docked over a 40dp stand-in for the composer in a [pane] under [statusBar].
     */
    private fun noticeBounds(statusBar: Dp, pane: Dp, bound: Dp): Rect {
        var bounds: Rect? = null
        val activity = compose {
            Column(Modifier.fillMaxSize()) {
                Spacer(Modifier.fillMaxWidth().height(statusBar))
                Box(Modifier.fillMaxWidth().height(pane)) {
                    ComposerDock(maxHeight = bound) {
                        StallBanner(
                            onRestartKernel = {},
                            onKeepWaiting = {},
                            modifier = Modifier.onGloballyPositioned { bounds = it.boundsInRoot() },
                        )
                        Box(Modifier.fillMaxWidth().height(40.dp))
                    }
                }
            }
        }
        val density = activity.resources.displayMetrics.density
        val px = checkNotNull(bounds) { "not laid out" }
        return Rect(px.left / density, px.top / density, px.right / density, px.bottom / density)
    }

    private fun compose(content: @Composable () -> Unit): ComponentActivity {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).also { controllers += it }.setup().get()
        activity.setContent { ClinePreviewTheme(dark = false) { content() } }
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }
        return activity
    }
}
