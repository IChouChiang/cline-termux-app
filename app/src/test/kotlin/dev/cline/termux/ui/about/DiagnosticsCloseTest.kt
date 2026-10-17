package dev.cline.termux.ui.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsCloseTest {
    @Test
    fun `back, cancel and an outside tap close only while nothing is in flight`() {
        assertTrue(diagnosticsCloseAllowed(ExportDiagnosticsUiState()))
        assertFalse(diagnosticsCloseAllowed(ExportDiagnosticsUiState(exporting = true)))
        assertFalse(diagnosticsCloseAllowed(ExportDiagnosticsUiState(saving = true)))
    }

    @Test
    fun `back is intercepted only while busy or in the dialog window`() {
        val idle = ExportDiagnosticsUiState()
        assertEquals(DiagnosticsBack.NavHost, diagnosticsBack(idle, asDialog = false))
        assertEquals(DiagnosticsBack.Close, diagnosticsBack(idle, asDialog = true))
        for (asDialog in listOf(false, true)) {
            assertEquals(DiagnosticsBack.Consume, diagnosticsBack(ExportDiagnosticsUiState(exporting = true), asDialog))
            assertEquals(DiagnosticsBack.Consume, diagnosticsBack(ExportDiagnosticsUiState(saving = true), asDialog))
        }
    }
}
