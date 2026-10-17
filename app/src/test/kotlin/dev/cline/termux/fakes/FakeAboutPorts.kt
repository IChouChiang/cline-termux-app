package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.data.about.WhatsNewRelease
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.diagnostics.DiagnosticsBuilder
import dev.cline.termux.data.diagnostics.DiagnosticsBuilder.DiagnosticSession
import dev.cline.termux.ui.about.AboutSources
import dev.cline.termux.ui.about.DiagnosticsFile
import dev.cline.termux.ui.about.DiagnosticsPort
import kotlinx.coroutines.flow.MutableStateFlow

/** Scripted [AboutSources]; a null answer means "could not be read". */
class FakeAboutSources(
    gate: KernelGate = KernelGate.Ready,
    kernelState: KernelState = KernelState.Unknown,
    var cached: String? = null,
    var portRelease: () -> String? = { "3.0.68-termux.2" },
    var cli: () -> String? = { "3.0.68" },
    var health: () -> HubHealth? = { HubHealth(ok = true, coreVersion = "0.42.1", buildId = "9f3c2a1b77d0") },
    var changelogText: () -> String = { "## 0.1.0\n\n- First release.\n" },
    var whatsNew: WhatsNewRelease? = null,
) : AboutSources {
    override val gate = MutableStateFlow(gate)
    override val kernelState = MutableStateFlow(kernelState)
    var portReads = 0
    var cliReads = 0
    var healthProbes = 0

    override fun cachedRelease(): String? = cached

    override suspend fun readPortRelease(): String? {
        portReads++
        return portRelease()
    }

    override suspend fun cliVersion(): String? {
        cliReads++
        return cli()
    }

    override suspend fun probeHealth(): HubHealth? {
        healthProbes++
        return health()
    }

    override suspend fun changelog(): String = changelogText()

    override fun latestWhatsNew(): WhatsNewRelease? = whatsNew
}

/** [DiagnosticsPort] over memory: reports are "written" to fake paths, saves are recorded. */
class FakeDiagnosticsPort(
    var sessions: Outcome<List<DiagnosticSession>> = Outcome.Ok(emptyList()),
) : DiagnosticsPort {
    val built = mutableListOf<List<String>>()
    val saved = mutableListOf<Pair<String, DiagnosticsFile>>()
    var sweeps = 0
    var writeError: Exception? = null
    var saveError: Exception? = null

    override suspend fun sessions(): Outcome<List<DiagnosticSession>> = sessions

    override suspend fun build(sessionIds: List<String>): DiagnosticsBuilder.Report {
        built += sessionIds
        return DiagnosticsBuilder.Report(text = "report", fileName = "cline-diagnostics-test.txt", sessionIds = sessionIds)
    }

    override suspend fun write(report: DiagnosticsBuilder.Report): DiagnosticsFile {
        writeError?.let { throw it }
        return DiagnosticsFile(report.fileName, "/cache/diagnostics/${report.fileName}", "content://test/diagnostics/${report.fileName}")
    }

    override suspend fun sweep() {
        sweeps++
    }

    override suspend fun saveTo(target: String, file: DiagnosticsFile) {
        saveError?.let { throw it }
        saved += target to file
    }
}
