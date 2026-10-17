package dev.cline.termux.ui.navigation

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G4: Ctrl+, (Settings) and Ctrl+B (drawer / list pane) beside the existing shell shortcuts. */
class ShortcutsTest {
    private fun key(code: Int, ctrl: Boolean = true, alt: Boolean = false, shift: Boolean = false, meta: Boolean = false) =
        ShellShortcuts.match(KeyEvent.ACTION_DOWN, code, ctrl, alt, shift, meta, repeatCount = 0)

    @Test
    fun `ctrl comma opens Settings and ctrl b toggles the drawer`() {
        assertEquals(ShellShortcut.Settings, key(KeyEvent.KEYCODE_COMMA))
        assertEquals(ShellShortcut.ToggleDrawer, key(KeyEvent.KEYCODE_B))
    }

    @Test
    fun `the new chords are ctrl only`() {
        assertNull(key(KeyEvent.KEYCODE_B, shift = true))
        assertNull(key(KeyEvent.KEYCODE_COMMA, alt = true))
        assertNull(key(KeyEvent.KEYCODE_COMMA, meta = true))
        assertNull(key(KeyEvent.KEYCODE_B, ctrl = false))
        assertNull(key(KeyEvent.KEYCODE_COMMA, ctrl = false))
        assertNull(ShellShortcuts.match(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_B, ctrl = true, alt = false, shift = false, meta = false))
        assertNull(ShellShortcuts.match(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_COMMA, ctrl = true, alt = false, shift = false, meta = false, repeatCount = 1))
    }

    @Test
    fun `search and new session are unchanged`() {
        assertEquals(ShellShortcut.Search, key(KeyEvent.KEYCODE_K))
        assertEquals(ShellShortcut.Search, key(KeyEvent.KEYCODE_P))
        assertEquals(ShellShortcut.NewSession, key(KeyEvent.KEYCODE_N))
    }

    @Test
    fun `settings and the drawer toggle are no-ops under menus, sheets and the lightbox`() {
        val none: (BackLevel) -> Boolean = { false }
        ShellShortcut.entries.forEach { assertFalse(ShellShortcuts.suppressedByOverlay(it, none)) }
        for (level in listOf(BackLevel.Menu, BackLevel.Sheet, BackLevel.Lightbox)) {
            val open: (BackLevel) -> Boolean = { it == level }
            assertTrue(ShellShortcuts.suppressedByOverlay(ShellShortcut.Settings, open))
            assertTrue(ShellShortcuts.suppressedByOverlay(ShellShortcut.ToggleDrawer, open))
            // Search and New session keep their existing behaviour.
            assertFalse(ShellShortcuts.suppressedByOverlay(ShellShortcut.Search, open))
            assertFalse(ShellShortcuts.suppressedByOverlay(ShellShortcut.NewSession, open))
        }
        // An open drawer is what Ctrl+B closes, so it never suppresses the toggle.
        assertFalse(ShellShortcuts.suppressedByOverlay(ShellShortcut.ToggleDrawer) { it == BackLevel.Drawer })
    }
}
