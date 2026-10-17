package dev.cline.termux.ui.sessions

import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cline.termux.ui.theme.ClinePreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The drawer rename field (DS §4.1 #1: Enter commits, Back cancels) on a real window with the
 * IME hidden, so hardware keys reach the field the way button navigation and a keyboard deliver them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RenameFieldKeysTest {

    private class Harness {
        var title = "Fix login redirect"
        var editing by mutableStateOf(true)
        var commits = 0
        var cancels = 0
        val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        fun show() {
            activity.setContent {
                ClinePreviewTheme(dark = false) {
                    if (editing) {
                        RenameField(
                            draft = RenameDraft("s1", title),
                            pending = false,
                            onCommit = {
                                commits++
                                title = it
                                editing = false
                            },
                            onCancel = {
                                cancels++
                                editing = false
                            },
                            commitOnBlur = true,
                            contentDescription = "Rename $title",
                        )
                    }
                }
            }
            idle()
        }

        /**
         * Sends a press and release through the view tree only (not the activity's own key
         * callbacks or its back dispatcher), so the result says whether the field took the key.
         */
        fun key(code: Int): Pair<Boolean, Boolean> {
            val down = activity.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
            idle()
            val up = activity.window.decorView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            idle()
            return down to up
        }

        fun idle() {
            repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }
        }
    }

    @Test
    fun `back key with the IME hidden cancels and leaves the title unchanged`() {
        val h = Harness()
        h.show()
        // The release finds no field (the cancel closed it); the activity never saw the press,
        // so it is not tracking one and the release cannot start a second back.
        val (down, _) = h.key(KeyEvent.KEYCODE_BACK)
        assertTrue("the field takes the back press", down)
        assertEquals(0, h.commits)
        assertEquals(1, h.cancels)
        assertEquals("Fix login redirect", h.title)
        assertFalse(h.editing)
    }

    @Test
    fun `escape cancels once`() {
        val h = Harness()
        h.show()
        val (down, _) = h.key(KeyEvent.KEYCODE_ESCAPE)
        assertTrue(down)
        assertEquals(0, h.commits)
        assertEquals(1, h.cancels)
    }

    @Test
    fun `enter commits once even as the field leaves and loses focus`() {
        val h = Harness()
        h.show()
        val (down, _) = h.key(KeyEvent.KEYCODE_ENTER)
        assertTrue(down)
        assertEquals(1, h.commits)
        assertEquals(0, h.cancels)
        assertFalse(h.editing)
    }
}
