package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.union
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of ui/components: live-region re-announce and clear, the copy rule, menu actions. */
class ComponentsLogicTest {

    @Test
    fun `live region re-announces an identical message by toggling a zero-width space`() {
        val state = LiveRegionState()
        state.announce("Tool approval required")
        val first = state.message!!
        state.announce("Tool approval required")
        val second = state.message!!
        assertTrue(first != second)
        assertEquals(first, second.trimEnd('​'))
        state.announce("Tool approval required")
        assertEquals(first, state.message)
        state.announce("Follow-up question")
        assertEquals("Follow-up question", state.message)
        state.clear()
        assertEquals(null, state.message)
    }

    @Test
    fun `the timed clear empties the region only while it still shows that announcement`() {
        val state = LiveRegionState()
        state.announce("Agent is working…")
        val working = state.message!!
        state.announce("Run failed: timeout")
        state.clearIfShowing(working)
        assertEquals("Run failed: timeout", state.message)
        state.clearIfShowing("Run failed: timeout")
        assertNull(state.message)
        // After a clear the same sentence is new text again, so TalkBack announces it.
        state.announce("Run failed: timeout")
        assertEquals("Run failed: timeout", state.message)
        assertEquals(1_500L, LIVE_REGION_CLEAR_MILLIS)
    }

    @Test
    fun `the String announcer mirrors its text once and lets the timed clear empty it`() {
        val state = LiveRegionState()
        // A visible "50 results" stays passed in; it is announced once, then the region empties.
        state.follow("50 results")
        assertEquals("50 results", state.message)
        state.clearIfShowing("50 results")
        assertNull(state.message)
        // A new count is a new announcement.
        state.follow("12 results")
        assertEquals("12 results", state.message)
        // Null empties the region at once.
        state.follow(null)
        assertNull(state.message)
        // The same text after a clear is announced again.
        state.follow("12 results")
        assertEquals("12 results", state.message)
    }

    @Test
    fun `page insets lift the FAB and the content above the gesture area in gesture mode`() {
        val density = Density(1f)
        // Gesture mode: a zero navigation-bar inset, a 32dp bottom gesture zone, the 16dp floor.
        val systemBars = WindowInsets(left = 4.dp, top = 24.dp, right = 8.dp, bottom = 0.dp)
        val bottomSafe = WindowInsets(bottom = 0.dp).union(WindowInsets(bottom = 32.dp)).union(WindowInsets(bottom = 16.dp))
        val page = pageWindowInsets(systemBars, bottomSafe)
        assertEquals(32, page.getBottom(density))
        assertEquals(24, page.getTop(density))
        assertEquals(4, page.getLeft(density, LayoutDirection.Ltr))
        assertEquals(8, page.getRight(density, LayoutDirection.Ltr))
        // M3 puts the FAB 16dp above these insets: it clears the gesture zone by 16dp.
        assertTrue(page.getBottom(density) + 16 >= 32 + 16)
        // Three-button navigation: a 48dp bar is already taller than the gesture zone.
        val buttons = pageWindowInsets(WindowInsets(bottom = 48.dp), WindowInsets(bottom = 48.dp).union(WindowInsets(bottom = 16.dp)))
        assertEquals(48, buttons.getBottom(density))
        // No inset at all (a parent consumed it): the 16dp floor still holds.
        assertEquals(16, pageWindowInsets(WindowInsets(0, 0, 0, 0), WindowInsets(bottom = 16.dp)).getBottom(density))
    }

    @Test
    fun `the app confirms a copy itself only below API 33`() {
        assertTrue(showsLegacyConfirmation(26))
        assertTrue(showsLegacyConfirmation(32))
        assertFalse(showsLegacyConfirmation(33))
        assertFalse(showsLegacyConfirmation(36))
    }

    @Test
    fun `context menu items become TalkBack custom actions unless something is pending`() {
        var clicked = ""
        val items = listOf(
            ContextMenuItem("pin", "Pin", { clicked = "pin" }),
            ContextMenuItem("rename", "Rename", { clicked = "rename" }, enabled = false),
            ContextMenuItem("delete", "Delete", { clicked = "delete" }, destructive = true),
        )
        val actions = items.toCustomActions()
        assertEquals(listOf("Pin", "Delete"), actions.map { it.label })
        assertTrue(actions.last().action())
        assertEquals("delete", clicked)
        assertEquals(emptyList<String>(), items.toCustomActions(pendingItemId = "delete").map { it.label })
    }
}
