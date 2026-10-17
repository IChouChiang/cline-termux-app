package dev.cline.termux.ui.about

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.diagnostics.DiagnosticsBuilder
import dev.cline.termux.data.diagnostics.DiagnosticsBuilder.DiagnosticSession
import dev.cline.termux.service.ShareFiles
import dev.cline.termux.ui.account.logReason
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The session list of the export (customize-and-rest.md §9.3). */
sealed interface DiagnosticSessions {
    data object Loading : DiagnosticSessions
    data class Ready(val sessions: List<DiagnosticSession>) : DiagnosticSessions

    /** "Sessions can't be listed while the Cline Hub is not connected…"; Export stays enabled. */
    data object HubDown : DiagnosticSessions

    /** The Hub answered with an error: shown under the list, Export stays enabled. */
    data class Failed(val failure: RepoFailure) : DiagnosticSessions
}

/** The written report: its file name and the FileProvider content URI (as a string, JVM-testable). */
@Immutable
data class DiagnosticsFile(val name: String, val path: String, val contentUri: String)

/** Why an export or a save failed (`error` text under the list / in the done state). */
sealed interface DiagnosticsError {
    data class WriteFailed(val detail: String) : DiagnosticsError
    data class SaveFailed(val detail: String) : DiagnosticsError
}

/** customize-and-rest.md §9.5 `ExportDiagnosticsUiState`. */
@Immutable
data class ExportDiagnosticsUiState(
    val sessions: DiagnosticSessions = DiagnosticSessions.Loading,
    val selected: Set<String> = emptySet(),
    val exporting: Boolean = false,
    val error: DiagnosticsError? = null,
    val result: DiagnosticsFile? = null,
    val saving: Boolean = false,
    /** "Save to Files" succeeded: the host shows "Saved to Files" once this screen closed. */
    val savedToFiles: Boolean = false,
) {
    /** Export is disabled while the list loads and while exporting (desktop `disabled={exporting || loading}`). */
    val exportEnabled: Boolean get() = sessions !is DiagnosticSessions.Loading && !exporting && result == null
}

/** Building, writing and saving the report (`DiagnosticsBuilder` + `ShareFiles`). */
interface DiagnosticsPort {
    suspend fun sessions(): Outcome<List<DiagnosticSession>>
    suspend fun build(sessionIds: List<String>): DiagnosticsBuilder.Report

    /** Writes the report under `cacheDir/diagnostics/` and returns it with its content URI. */
    suspend fun write(report: DiagnosticsBuilder.Report): DiagnosticsFile

    /** Deletes reports older than 24 h (run when the export opens, §9.5). */
    suspend fun sweep()

    /** Copies [file] into the `CreateDocument` target. */
    suspend fun saveTo(target: String, file: DiagnosticsFile)

    companion object {
        fun of(context: Context, graph: AppGraph): DiagnosticsPort {
            val app = context.applicationContext
            return object : DiagnosticsPort {
                private val dir: File get() = ShareFiles.dir(app, ShareFiles.Area.Diagnostics)
                override suspend fun sessions() = graph.diagnostics.sessions()
                override suspend fun build(sessionIds: List<String>) = graph.diagnostics.build(sessionIds)
                override suspend fun write(report: DiagnosticsBuilder.Report): DiagnosticsFile {
                    val file = graph.diagnostics.write(withContext(Dispatchers.IO) { dir }, report)
                    return DiagnosticsFile(file.name, file.absolutePath, ShareFiles.uriFor(app, file).toString())
                }
                override suspend fun sweep() {
                    graph.diagnostics.sweep(withContext(Dispatchers.IO) { dir })
                }
                override suspend fun saveTo(target: String, file: DiagnosticsFile) = withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openOutputStream(Uri.parse(target), "wt") ?: error("no output stream")
                    stream.use { out -> File(file.path).inputStream().use { it.copyTo(out) } }
                    Unit
                }
            }
        }
    }
}

/**
 * C12 Export diagnostics (customize-and-rest.md §9): root sessions from the Hub with the
 * newest pre-selected, the redacted report written to the cache on Export, then the inline
 * done state with Share and Save to Files. With the Hub down the list shows the notice and
 * Export still works (app and kernel info only).
 */
class ExportDiagnosticsViewModel(
    private val savedState: SavedStateHandle,
    private val port: DiagnosticsPort,
    private val gate: StateFlow<KernelGate>,
) : ViewModel() {
    private val _ui = MutableStateFlow(ExportDiagnosticsUiState(selected = savedState.get<ArrayList<String>>(KEY_SELECTED)?.toSet().orEmpty()))
    val ui: StateFlow<ExportDiagnosticsUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch { runCatching { port.sweep() } }
        viewModelScope.launch { loadSessions() }
    }

    fun toggle(sessionId: String) {
        val state = _ui.value
        if (state.exporting || state.result != null) return
        val selected = if (sessionId in state.selected) state.selected - sessionId else state.selected + sessionId
        setSelected(selected)
    }

    fun export() {
        val state = _ui.value
        if (!state.exportEnabled) return
        _ui.update { it.copy(exporting = true, error = null) }
        viewModelScope.launch {
            try {
                val order = (state.sessions as? DiagnosticSessions.Ready)?.sessions?.map { it.sessionId }.orEmpty()
                val ids = order.filter { it in state.selected }
                val report = port.build(ids)
                val file = port.write(report)
                _ui.update { it.copy(exporting = false, result = file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("export", e.javaClass.simpleName)
                _ui.update { it.copy(exporting = false, error = DiagnosticsError.WriteFailed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    /** "Save to Files": [target] is the `CreateDocument` URI (null when the picker was cancelled). */
    fun saveTo(target: String?) {
        val file = _ui.value.result ?: return
        if (target == null || _ui.value.saving) return
        _ui.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                port.saveTo(target, file)
                _ui.update { it.copy(saving = false, savedToFiles = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("save", e.javaClass.simpleName)
                _ui.update { it.copy(saving = false, error = DiagnosticsError.SaveFailed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private suspend fun loadSessions() {
        val sessions = when (val out = port.sessions()) {
            is Outcome.Ok -> DiagnosticSessions.Ready(out.value)
            is Outcome.Failed -> {
                log("sessions", out.failure.logReason)
                if (gate.value != KernelGate.Ready || out.failure.isConnectionLoss()) DiagnosticSessions.HubDown else DiagnosticSessions.Failed(out.failure)
            }
        }
        val selected = when {
            sessions !is DiagnosticSessions.Ready -> emptySet()
            savedState.contains(KEY_SELECTED) -> _ui.value.selected.filterTo(mutableSetOf()) { id -> sessions.sessions.any { it.sessionId == id } }
            else -> setOfNotNull(sessions.sessions.firstOrNull()?.sessionId)
        }
        _ui.update { it.copy(sessions = sessions) }
        setSelected(selected)
    }

    private fun setSelected(selected: Set<String>) {
        savedState[KEY_SELECTED] = ArrayList(selected)
        _ui.update { it.copy(selected = selected) }
    }

    private fun RepoFailure.isConnectionLoss(): Boolean =
        this == RepoFailure.HubDisconnected || this == RepoFailure.HubTimeout || this == RepoFailure.HubDraining

    private fun log(what: String, reason: String) {
        runCatching { Log.w(TAG, "$what: $reason") }
    }

    companion object {
        const val KEY_SELECTED = "diagnostics.selected"
        private const val TAG = "Diagnostics"

        fun factory(context: Context, graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { ExportDiagnosticsViewModel(createSavedStateHandle(), DiagnosticsPort.of(context, graph), graph.kernel.gate) }
        }
    }
}
