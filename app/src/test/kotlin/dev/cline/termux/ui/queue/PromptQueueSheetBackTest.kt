package dev.cline.termux.ui.queue

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import dev.cline.termux.ui.theme.ClinePreviewTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * C2.8 back on the real sheet window: the ModalBottomSheet runs in its own dialog with its
 * own back dispatcher, so back while editing must cancel the edit there and leave the sheet open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptQueueSheetBackTest {

    @Test
    fun `back while editing cancels the edit and keeps the sheet, then goes to the sheet`() {
        var cancels = 0
        var dismissals = 0
        val state = mutableStateOf(
            PromptQueueState(items = previewItems, loaded = true, sheetOpen = true, editingId = "q2", editingText = "Also add tests"),
        )
        val actions = PromptQueueActions(
            onSteer = {},
            onStartEdit = {},
            onEditTextChange = {},
            onSaveEdit = {},
            onCancelEdit = {
                cancels++
                state.value = state.value.copy(editingId = null, editingText = "")
            },
            onRemove = {},
            onDismiss = { dismissals++ },
        )
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { ClinePreviewTheme(dark = false) { PromptQueueSheet(state.value, actions) } }
        idle()
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        assertTrue(dialog.isShowing)

        dialog.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, cancels)
        assertEquals(0, dismissals)
        assertTrue(dialog.isShowing)

        // No edit open: the edit handler steps aside and back goes to the sheet's own
        // handler, which collapses the sheet (its hide animation then calls onDismiss).
        assertTrue(dialog.onBackPressedDispatcher.hasEnabledCallbacks())
        dialog.onBackPressedDispatcher.onBackPressed()
        idle()
        assertEquals(1, cancels)
    }

    private fun idle() {
        repeat(5) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500)) }
    }
}
