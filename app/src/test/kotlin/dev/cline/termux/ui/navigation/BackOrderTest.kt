package dev.cline.termux.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackOrderTest {
    @Test
    fun `levels are in DS 5 8 order`() {
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "7a", "8", "9", "10"),
            BackLevel.entries.map { it.label },
        )
        assertTrue(BackLevel.Sheet.precedes(BackLevel.Drawer))
        assertTrue(BackLevel.Drawer.precedes(BackLevel.SupportingPane))
        assertTrue(BackLevel.SupportingPane.precedes(BackLevel.ListDetailPane))
        assertFalse(BackLevel.NavHostPop.precedes(BackLevel.NavHostPop))
    }

    @Test
    fun `the highest open level wins and nothing open leaves the app`() {
        assertEquals(BackLevel.LeaveApp, BackPrecedence.winner(emptyList()))
        assertEquals(BackLevel.Menu, BackPrecedence.winner(listOf(BackLevel.Drawer, BackLevel.Menu, BackLevel.Sheet)))
        assertEquals(BackLevel.SupportingPane, BackPrecedence.winner(listOf(BackLevel.ListDetailPane, BackLevel.SupportingPane)))
    }

    @Test
    fun `a handler consumes only when wanted and nothing higher is open`() {
        val open = listOf(BackLevel.Drawer)
        assertTrue(BackPrecedence.consumes(BackLevel.Drawer, wanted = true, open = open))
        assertFalse(BackPrecedence.consumes(BackLevel.Drawer, wanted = false, open = open))
        assertFalse(BackPrecedence.consumes(BackLevel.ListDetailPane, wanted = true, open = open))
        assertTrue(BackPrecedence.consumes(BackLevel.Sheet, wanted = true, open = open))
    }

    @Test
    fun `the registry tracks owners and their levels`() {
        val order = BackOrder()
        val sheet = Any()
        val drawer = Any()
        assertEquals(BackLevel.LeaveApp, order.current)

        order.report(drawer, BackLevel.Drawer, isOpen = true)
        assertTrue(order.isOpen(BackLevel.Drawer))
        assertTrue(order.allows(BackLevel.Drawer, wanted = true))
        assertFalse(order.allows(BackLevel.ListDetailPane, wanted = true))
        assertTrue(order.hasOpenAbove(BackLevel.NavHostPop))

        order.report(sheet, BackLevel.Sheet, isOpen = true)
        assertEquals(BackLevel.Sheet, order.current)
        assertFalse(order.allows(BackLevel.Drawer, wanted = true))

        order.report(sheet, BackLevel.Sheet, isOpen = false)
        assertEquals(BackLevel.Drawer, order.current)

        order.forget(drawer)
        assertFalse(order.isOpen(BackLevel.Drawer))
        assertEquals(BackLevel.LeaveApp, order.current)
        assertFalse(order.allows(BackLevel.NavHostPop, wanted = false))
    }

    @Test
    fun `one owner holds one level at a time`() {
        val order = BackOrder()
        val owner = Any()
        order.report(owner, BackLevel.Sheet, isOpen = true)
        order.report(owner, BackLevel.Menu, isOpen = true)
        assertFalse(order.isOpen(BackLevel.Sheet))
        assertEquals(BackLevel.Menu, order.current)
    }

    @Test
    fun `collapsed list pane and a selected session - the NavHostPop level is still allowed`() {
        // SS 2.9.1 / HC 2.9 on expanded windows: the list-pane reveal (level 8) only checks
        // allows() and never reports itself, the chat's selection back (level 9) reports.
        val order = BackOrder()
        val listPaneCollapsed = true
        val sessionSelected = true
        val chat = Any()
        order.report(chat, BackLevel.NavHostPop, isOpen = sessionSelected)

        // Both are enabled; the chat's handler is registered later (inside the NavHost), so it wins.
        assertTrue(order.allows(BackLevel.NavHostPop, wanted = sessionSelected))
        assertTrue(order.allows(BackLevel.ListDetailPane, wanted = listPaneCollapsed))
        assertEquals(BackLevel.NavHostPop, order.current)

        // Selection cleared (Home): the reveal is the only enabled handler, then back leaves.
        order.report(chat, BackLevel.NavHostPop, isOpen = false)
        assertFalse(order.allows(BackLevel.NavHostPop, wanted = false))
        assertTrue(order.allows(BackLevel.ListDetailPane, wanted = listPaneCollapsed))
        assertFalse(order.allows(BackLevel.ListDetailPane, wanted = false))
        assertEquals(BackLevel.LeaveApp, order.current)

        // A surface above both (the drawer, a sheet) still stands both down.
        val sheet = Any()
        order.report(chat, BackLevel.NavHostPop, isOpen = true)
        order.report(sheet, BackLevel.Sheet, isOpen = true)
        assertFalse(order.allows(BackLevel.NavHostPop, wanted = true))
        assertFalse(order.allows(BackLevel.ListDetailPane, wanted = listPaneCollapsed))
    }
}
