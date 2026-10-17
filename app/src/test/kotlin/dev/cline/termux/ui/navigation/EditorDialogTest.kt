package dev.cline.termux.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class EditorDialogTest {
    @Test
    fun `the editor dialog window never dismisses itself`() {
        // DialogNavigator pops the entry on a window dismissal, skipping the editor's guards.
        assertFalse(EditorDialogProperties.dismissOnBackPress)
        assertFalse(EditorDialogProperties.dismissOnClickOutside)
        assertFalse(EditorDialogProperties.usePlatformDefaultWidth)
        assertFalse(EditorDialogProperties.decorFitsSystemWindows)
    }

    @Test
    fun `a close request is ignored while saving, confirms when dirty, else closes`() {
        assertEquals(EditorCloseAction.Close, editorCloseAction(dirty = false, saving = false))
        assertEquals(EditorCloseAction.ConfirmDiscard, editorCloseAction(dirty = true, saving = false))
        assertEquals(EditorCloseAction.Ignore, editorCloseAction(dirty = false, saving = true))
        assertEquals(EditorCloseAction.Ignore, editorCloseAction(dirty = true, saving = true))
    }
}
