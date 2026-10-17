package dev.cline.termux.ui.about

import androidx.lifecycle.SavedStateHandle
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.diagnostics.DiagnosticsBuilder.DiagnosticSession
import dev.cline.termux.fakes.FakeDiagnosticsPort
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExportDiagnosticsViewModelTest {
    private val main = UnconfinedTestDispatcher()
    private val sessions = listOf(
        DiagnosticSession("newest", "Fix login", "app", 2_000),
        DiagnosticSession("older", "Docs", null, 1_000),
    )
    private val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `newest session is pre-selected and the export sweeps old reports`() = runTest(main) {
        val port = FakeDiagnosticsPort(Outcome.Ok(sessions))
        val vm = ExportDiagnosticsViewModel(SavedStateHandle(), port, gate)
        assertEquals(1, port.sweeps)
        assertEquals(DiagnosticSessions.Ready(sessions), vm.ui.value.sessions)
        assertEquals(setOf("newest"), vm.ui.value.selected)
        assertTrue(vm.ui.value.exportEnabled)
    }

    @Test
    fun `export builds the selection in list order and shows the file`() = runTest(main) {
        val port = FakeDiagnosticsPort(Outcome.Ok(sessions))
        val vm = ExportDiagnosticsViewModel(SavedStateHandle(), port, gate)
        vm.toggle("older")
        vm.export()
        assertEquals(listOf(listOf("newest", "older")), port.built)
        val result = vm.ui.value.result!!
        assertEquals("cline-diagnostics-test.txt", result.name)
        assertFalse(vm.ui.value.exporting)
        assertFalse(vm.ui.value.exportEnabled)

        vm.toggle("newest") // ignored once done
        assertEquals(setOf("newest", "older"), vm.ui.value.selected)
    }

    @Test
    fun `hub down keeps export enabled with no sessions`() = runTest(main) {
        gate.value = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
        val port = FakeDiagnosticsPort(Outcome.Failed(RepoFailure.HubDisconnected))
        val vm = ExportDiagnosticsViewModel(SavedStateHandle(), port, gate)
        assertEquals(DiagnosticSessions.HubDown, vm.ui.value.sessions)
        assertTrue(vm.ui.value.selected.isEmpty())
        vm.export()
        assertEquals(listOf(emptyList<String>()), port.built)
        assertTrue(vm.ui.value.result != null)
    }

    @Test
    fun `a hub error with the gate up is shown as a failure`() = runTest(main) {
        val failure = RepoFailure.Hub("command_failed", "store unavailable")
        val vm = ExportDiagnosticsViewModel(SavedStateHandle(), FakeDiagnosticsPort(Outcome.Failed(failure)), gate)
        assertEquals(DiagnosticSessions.Failed(failure), vm.ui.value.sessions)
        assertTrue(vm.ui.value.exportEnabled)
    }

    @Test
    fun `selection survives process death and drops vanished sessions`() = runTest(main) {
        val saved = SavedStateHandle(mapOf(ExportDiagnosticsViewModel.KEY_SELECTED to arrayListOf("older", "gone")))
        val vm = ExportDiagnosticsViewModel(saved, FakeDiagnosticsPort(Outcome.Ok(sessions)), gate)
        assertEquals(setOf("older"), vm.ui.value.selected)
        vm.toggle("older")
        assertEquals(arrayListOf<String>(), saved.get<ArrayList<String>>(ExportDiagnosticsViewModel.KEY_SELECTED))
    }

    @Test
    fun `write and save failures are reported and save success is flagged`() = runTest(main) {
        val port = FakeDiagnosticsPort(Outcome.Ok(sessions))
        port.writeError = IOException("No space left on device")
        val vm = ExportDiagnosticsViewModel(SavedStateHandle(), port, gate)
        vm.export()
        assertEquals(DiagnosticsError.WriteFailed("No space left on device"), vm.ui.value.error)
        assertNull(vm.ui.value.result)

        port.writeError = null
        vm.export()
        assertNull(vm.ui.value.error)

        vm.saveTo(null) // picker cancelled
        assertTrue(port.saved.isEmpty())

        port.saveError = IOException("denied")
        vm.saveTo("content://docs/1")
        assertEquals(DiagnosticsError.SaveFailed("denied"), vm.ui.value.error)
        assertFalse(vm.ui.value.savedToFiles)

        port.saveError = null
        vm.saveTo("content://docs/1")
        assertEquals("content://docs/1", port.saved.single().first)
        assertTrue(vm.ui.value.savedToFiles)
        assertFalse(vm.ui.value.saving)
    }
}
