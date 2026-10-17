package dev.cline.termux.ui.queue

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Test

/** C2.7 key map of the queue edit field, ported from agent-prompt-queue.tsx's editor onKeyDown. */
class QueueEditKeyTest {

    private fun keyOf(
        key: Key,
        shift: Boolean = false,
        composing: Boolean = false,
        hardware: Boolean = true,
        keyDown: Boolean = true,
    ) = queueEditKey(keyDown = keyDown, hardware = hardware, key = key, shift = shift, composing = composing)

    @Test
    fun `hardware Enter saves`() {
        assertEquals(QueueEditKey.Save, keyOf(Key.Enter))
        assertEquals(QueueEditKey.Save, keyOf(Key.NumPadEnter))
    }

    @Test
    fun `Enter during an IME composition goes to the IME and does not save`() {
        assertEquals(QueueEditKey.PassThrough, keyOf(Key.Enter, composing = true))
        assertEquals(QueueEditKey.PassThrough, keyOf(Key.NumPadEnter, composing = true))
    }

    @Test
    fun `Shift+Enter inserts a newline`() {
        assertEquals(QueueEditKey.PassThrough, keyOf(Key.Enter, shift = true))
    }

    @Test
    fun `Escape cancels, as on the desktop, composition or not`() {
        assertEquals(QueueEditKey.Cancel, keyOf(Key.Escape))
        assertEquals(QueueEditKey.Cancel, keyOf(Key.Escape, composing = true))
    }

    @Test
    fun `soft keyboard and key-up events are left to the field`() {
        assertEquals(QueueEditKey.PassThrough, keyOf(Key.Enter, hardware = false))
        assertEquals(QueueEditKey.PassThrough, keyOf(Key.Escape, hardware = false))
        assertEquals(QueueEditKey.PassThrough, keyOf(Key.Enter, keyDown = false))
    }

    @Test
    fun `other keys pass through`() {
        assertEquals(QueueEditKey.PassThrough, keyOf(Key.A))
    }
}
