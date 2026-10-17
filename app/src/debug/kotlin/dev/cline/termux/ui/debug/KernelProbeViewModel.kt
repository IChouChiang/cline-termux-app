package dev.cline.termux.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


/** One probe run shown in the developer "Kernel probe" screen. */
data class ProbeEntry(
    val title: String,
    val running: Boolean = true,
    val summary: String = "",
    val body: String = "",
)

data class ProbeUiState(
    val termuxInstalled: Boolean = false,
    val permissionGranted: Boolean = false,
    val foregroundStart: Boolean = true,
    val entries: List<ProbeEntry> = emptyList(),
)

/** Exercises [TermuxRunner] against the real Termux on the device. Developer tool. */
class KernelProbeViewModel(application: Application) : AndroidViewModel(application) {
    private val runner = TermuxRunner(application)
    private val _state = MutableStateFlow(ProbeUiState())
    val state: StateFlow<ProbeUiState> = _state

    init { refreshStatus() }

    fun refreshStatus() {
        _state.update {
            it.copy(termuxInstalled = runner.isTermuxInstalled().installed, permissionGranted = runner.hasPermission())
        }
    }

    fun setForegroundStart(enabled: Boolean) = _state.update { it.copy(foregroundStart = enabled) }

    val probes: List<Pair<String, TermuxCommand>> = listOf(
        "env (PATH / HOME / LD_PRELOAD)" to TermuxCommand.bin("env"),
        "cline --version" to TermuxCommand.cline("--version"),
        "cline hub status" to TermuxCommand.cline("hub", "status"),
        "cat hub production.json" to TermuxCommand.bin("cat", TermuxPaths.HUB_DISCOVERY),
        "xdg-open example.com" to TermuxCommand.bin("xdg-open", "https://example.com"),
        "termux-wake-lock" to TermuxCommand.bin("termux-wake-lock"),
        "termux-wake-unlock" to TermuxCommand.bin("termux-wake-unlock"),
        "false (exit 1)" to TermuxCommand.bin("false"),
        "missing binary" to TermuxCommand(path = "${TermuxPaths.BIN}/definitely-not-here"),
        "foreground session: cline --version" to TermuxCommand.cline("--version").copy(background = false, sessionAction = 0),
    )

    fun run(title: String, command: TermuxCommand) {
        val index = _state.value.entries.size
        _state.update { it.copy(entries = it.entries + ProbeEntry(title = title, body = command.toString())) }
        viewModelScope.launch {
            val entry = try {
                val r = runner.run(command, timeoutMs = 45_000)
                ProbeEntry(
                    title = title,
                    running = false,
                    summary = "exit=${r.exitCode} err=${r.err} in ${r.elapsedMillis} ms" +
                        (if (r.truncated) " (truncated)" else ""),
                    body = buildString {
                        append(command.toString()).append('\n')
                        if (!r.errmsg.isNullOrBlank()) append("errmsg: ").append(r.errmsg.take(2000)).append('\n')
                        if (r.stdout.isNotBlank()) append("stdout:\n").append(r.stdout.take(4000)).append('\n')
                        if (r.stderr.isNotBlank()) append("stderr:\n").append(r.stderr.take(2000)).append('\n')
                    },
                )
            } catch (e: TermuxUnavailable) {
                ProbeEntry(title = title, running = false, summary = "unavailable: ${e.javaClass.simpleName}", body = e.message ?: "")
            } catch (e: Exception) {
                ProbeEntry(title = title, running = false, summary = "failed: ${e.javaClass.simpleName}", body = e.toString())
            }
            _state.update { s ->
                s.copy(entries = s.entries.mapIndexed { i, old -> if (i == index) entry else old })
            }
        }
    }

    fun clear() = _state.update { it.copy(entries = emptyList()) }
}
