package dev.cline.termux.ui.components

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The inline edit key map and single exit shared by the drawer and header rename fields (DS §4.1 #1, chat §6.7). */
class InlineEditTest {

    // ---- key map -----------------------------------------------------------------------------

    @Test
    fun `enter and numpad enter commit on key down only`() {
        assertEquals(InlineEditKey.Commit, inlineEditKeyAction(Key.Enter, KeyEventType.KeyDown))
        assertEquals(InlineEditKey.Commit, inlineEditKeyAction(Key.NumPadEnter, KeyEventType.KeyDown))
        assertNull(inlineEditKeyAction(Key.Enter, KeyEventType.KeyUp))
        assertNull(inlineEditKeyAction(Key.NumPadEnter, KeyEventType.KeyUp))
    }

    @Test
    fun `escape cancels on key down only`() {
        assertEquals(InlineEditKey.Cancel, inlineEditKeyAction(Key.Escape, KeyEventType.KeyDown))
        assertNull(inlineEditKeyAction(Key.Escape, KeyEventType.KeyUp))
    }

    @Test
    fun `back cancels on key down and its release is swallowed, never left to become a focus exit`() {
        assertEquals(InlineEditKey.Cancel, inlineEditKeyAction(Key.Back, KeyEventType.KeyDown))
        assertEquals(InlineEditKey.Swallow, inlineEditKeyAction(Key.Back, KeyEventType.KeyUp))
    }

    @Test
    fun `other keys stay with the text field`() {
        listOf(Key.A, Key.Spacebar, Key.Tab, Key.DirectionLeft, Key.Backspace, Key.Menu).forEach { key ->
            assertNull(inlineEditKeyAction(key, KeyEventType.KeyDown))
            assertNull(inlineEditKeyAction(key, KeyEventType.KeyUp))
        }
    }

    // ---- single exit -------------------------------------------------------------------------

    private class Recorder {
        val events = mutableListOf<String>()
        val exit = InlineEditExit(onCommit = { events += "commit" }, onCancel = { events += "cancel" })
    }

    @Test
    fun `a commit followed by the blur of the leaving field commits once`() {
        val r = Recorder()
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.commit()
        r.exit.onFocusChanged(focused = false, commitOnBlur = true)
        r.exit.commit()
        assertEquals(listOf("commit"), r.events)
    }

    @Test
    fun `back then the focus loss it causes cancels and never commits`() {
        val r = Recorder()
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.cancel()
        r.exit.onFocusChanged(focused = false, commitOnBlur = true)
        r.exit.cancel()
        assertEquals(listOf("cancel"), r.events)
    }

    @Test
    fun `nothing exits after a cancel, even after re-arming`() {
        val r = Recorder()
        r.exit.cancel()
        r.exit.rearm()
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.commit()
        r.exit.onFocusChanged(focused = false, commitOnBlur = true)
        assertEquals(listOf("cancel"), r.events)
    }

    @Test
    fun `blur commits only after the field had focus and only with commitOnBlur`() {
        val r = Recorder()
        r.exit.onFocusChanged(focused = false, commitOnBlur = true)
        assertEquals(emptyList<String>(), r.events)
        r.exit.onFocusChanged(focused = true, commitOnBlur = false)
        r.exit.onFocusChanged(focused = false, commitOnBlur = false)
        assertEquals(emptyList<String>(), r.events)
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.onFocusChanged(focused = false, commitOnBlur = true)
        assertEquals(listOf("commit"), r.events)
    }

    @Test
    fun `a field disabled while its save runs does not commit a second time on the blur`() {
        val r = Recorder()
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.commit()
        // pending: the drawer passes commitOnBlur && !pending
        r.exit.onFocusChanged(focused = false, commitOnBlur = false)
        assertEquals(listOf("commit"), r.events)
    }

    @Test
    fun `a failed save left open re-arms when the field takes focus again`() {
        val r = Recorder()
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.commit()
        r.exit.onFocusChanged(focused = false, commitOnBlur = false)
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.commit()
        assertEquals(listOf("commit", "commit"), r.events)
    }

    @Test
    fun `an edit after a refused commit re-arms the exit`() {
        val r = Recorder()
        r.exit.onFocusChanged(focused = true, commitOnBlur = true)
        r.exit.commit()
        r.exit.commit()
        assertEquals(listOf("commit"), r.events)
        r.exit.rearm()
        r.exit.commit()
        assertEquals(listOf("commit", "commit"), r.events)
    }

    @Test
    fun `a cancel after a commit in the same interaction is dropped`() {
        val r = Recorder()
        r.exit.commit()
        r.exit.cancel()
        assertEquals(listOf("commit"), r.events)
    }
}
