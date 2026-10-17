package dev.cline.termux.ui.composer

import android.os.Looper
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.foundation.text.input.TextFieldState
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
import org.robolectric.shadows.ShadowDialog

/**
 * B9 on a real dialog window: Back closes the full-screen editor and the shared draft keeps its text.
 *
 * Runs on its own Robolectric sandbox (API 33; every other Robolectric test here uses 34): the
 * editor's focused field and keyboard request leave window-focus / IME state in the shadows that
 * outlives the test and made later key and back tests in the same sandbox order-dependent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FullScreenEditorBackTest {

    @Test
    fun `back closes the editor and the draft keeps its text`() {
        val text = "a long draft\nover\nfour\nlines"
        val draft = TextFieldState(text)
        val editor = FullScreenEditorState(initiallyOpen = true)
        var closes = 0
        var sends = 0
        val actions = FullScreenEditorActions(
            onClose = {
                closes++
                editor.close()
            },
            onSend = { sends++ },
            onAttach = {},
            onRemoveAttachment = {},
            onOpenImage = {},
            keys = FieldKeys(onPanelKey = null, onEnter = { false }, onEscape = { false }),
        )
        val state = ComposerUiState.initial(ComposerVariant.Conversation, "s1").copy(hasDraft = true, canSend = true)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        activity.setContent {
            ClinePreviewTheme(dark = false) {
                if (editor.isOpen) FullScreenEditor(state, draft, actions)
            }
        }
        idle()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        assertTrue(dialog.isShowing)

        dialog.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, closes)
        assertFalse(editor.isOpen)
        assertFalse(dialog.isShowing)
        assertEquals(text, draft.text.toString())
        assertEquals(0, sends)

        // Leave no keyboard or window behind for the Robolectric tests that share this sandbox.
        activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        controller.pause().stop().destroy()
        idle()
    }

    private fun idle() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300)) }
    }
}
