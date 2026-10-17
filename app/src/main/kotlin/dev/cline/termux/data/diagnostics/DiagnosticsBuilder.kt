package dev.cline.termux.data.diagnostics

import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelTransitions
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEndpoints
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.HubVersion
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.LogTail
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.outcomeOf
import dev.cline.termux.data.sessions.basenamePath
import dev.cline.termux.data.sessions.metadataTitle
import dev.cline.termux.data.tools.jsNumber
import dev.cline.termux.data.tools.jsString
import dev.cline.termux.data.tools.jsonStringify
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Export diagnostics (customize-and-rest.md §9.4, §9.5; desktop `sidecar/diagnostics.ts`
 * `buildDiagnosticsReport`): one text file with, in order, a JSON header (app, system, kernel,
 * app settings), `cline doctor --json`, the kernel transition ring, the last 48 KiB of
 * `cline.log` and `hub-daemon.log` (two RUN_COMMANDs so each fits the 100 KB cap; the partial
 * first line dropped; a missing file skips its section) and the `session.get` record of each
 * chosen session without `metadata.prompt` / `metadata.systemPrompt`. Conversation contents
 * are never read. The whole text then goes through [Redactor].
 */
class DiagnosticsBuilder(
    private val hub: HubClient,
    private val termux: TermuxExecutor,
    private val prober: HubProber,
    private val app: AppFacts,
    private val kernelFacts: suspend () -> KernelFacts,
    private val transitions: () -> List<KernelTransitions.Transition>,
    private val settings: suspend () -> JsonObject,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val port: Int = HubEndpoints.DEFAULT_PORT,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** `app` and `system` of the header (Build facts are passed in; the builder stays JVM-testable). */
    data class AppFacts(
        val packageName: String,
        val versionName: String,
        val versionCode: Int,
        val sdkInt: Int,
        val release: String,
        val device: String,
        val abi: String,
    )

    /** `kernel` of the header besides `/health` and `/version`; [gate] is the `KernelGate` name. */
    data class KernelFacts(val release: String?, val cliVersion: String?, val gate: String)

    /** A row of the session picker (CR §9.5): label = title, else prompt, else id. */
    data class DiagnosticSession(val sessionId: String, val label: String, val workspaceName: String?, val updatedAt: Long?)

    data class Report(val text: String, val fileName: String, val sessionIds: List<String>)

    /** `session.list {limit: 200, rootOnly: true}` sorted by `updatedAt` desc; fails while the Hub is down. */
    suspend fun sessions(): Outcome<List<DiagnosticSession>> = outcomeOf {
        val reply = hub.command(HubCommands.SESSION_LIST, HubPayloads.sessionList(limit = SESSION_LIMIT, rootOnly = true))
        HubPayloads.sessionsOf(reply).mapNotNull(::sessionRow).sortedByDescending { it.updatedAt ?: Long.MIN_VALUE }
    }

    /** Builds the redacted report for [sessionIds] (unknown or unreadable ids are skipped). */
    suspend fun build(sessionIds: List<String>): Report {
        val now = nowMs()
        val health = runCatching { prober.health(port) }.getOrNull()
        val version = runCatching { prober.version(port) }.getOrNull()
        val kernel = runCatching { kernelFacts() }.getOrElse { KernelFacts(null, null, "Unknown") }
        val header = buildJsonObject {
            put("generatedAt", Instant.ofEpochMilli(now).toString())
            putJsonObject("app") {
                put("name", app.packageName)
                put("version", app.versionName)
                put("versionCode", app.versionCode)
            }
            putJsonObject("system") {
                put("platform", "android")
                put("sdkInt", app.sdkInt)
                put("release", app.release)
                put("device", app.device)
                put("abi", app.abi)
            }
            putJsonObject("kernel") {
                put("release", kernel.release)
                put("cliVersion", kernel.cliVersion)
                put("health", health?.let { ENCODER.encodeToJsonElement(HubHealth.serializer(), it) } ?: JsonNull)
                put("version", version?.let { ENCODER.encodeToJsonElement(HubVersion.serializer(), it) } ?: JsonNull)
                put("gate", kernel.gate)
            }
            put("settings", runCatching { settings() }.getOrElse { JsonObject(emptyMap()) })
        }
        val text = StringBuilder(jsonStringify(header)).append('\n')
        text.append(section("doctor", doctor()))
        text.append(section("kernel transitions", formatTransitions(transitions())))
        for (path in LOG_PATHS) tail(path)?.let { text.append(section("tail of $path", it)) }

        val included = mutableListOf<String>()
        for (id in sessionIds.distinct()) {
            if (!SESSION_ID_PATTERN.matches(id)) continue
            val record = runCatching { sessionRecord(id) }.getOrNull() ?: continue
            text.append(section("session $id", jsonStringify(record)))
            included += id
        }
        return Report(Redactor.redact(text.toString()), fileName(app.versionName, now, zone()), included)
    }

    private suspend fun doctor(): String = when (val out = outcomeOf { termux.run(ClineCommands.doctorJson()) }) {
        is Outcome.Ok -> {
            val result = out.value
            if (result.ok) result.stdout else "exit ${result.exitCode}: ${(result.stdout + "\n" + result.stderr).trim()}"
        }
        is Outcome.Failed -> "unavailable: ${describe(out.failure)}"
    }

    /** `tail -c 49152 <path>`; null when the file is missing or Termux could not answer. */
    private suspend fun tail(path: String): String? {
        val result = runCatching { termux.run(ClineCommands.tailLog(path, ClineCommands.LOG_TAIL_BYTES)) }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            return null
        }
        if (!result.ok) return null
        return LogTail.clean(result.stdout, ClineCommands.LOG_TAIL_BYTES)
    }

    private suspend fun sessionRecord(sessionId: String): JsonElement? {
        val reply = hub.command(HubCommands.SESSION_GET, HubPayloads.sessionGet(sessionId, includeSnapshot = false), sessionId)
        val record = reply["session"] as? JsonObject ?: return null
        return stripPrompts(record)
    }

    /** Writes [report] to `<dir>/<fileName>` (the FileProvider `diagnostics/` folder) and returns the file. */
    suspend fun write(dir: File, report: Report): File = withContext(io) {
        dir.mkdirs()
        File(dir, report.fileName).apply { writeText(report.text) }
    }

    /** Deletes files older than 24 h in [dir] (run when the export screen opens, CR §9.5). */
    suspend fun sweep(dir: File) = withContext(io) {
        val cutoff = nowMs() - SWEEP_AGE_MS
        dir.listFiles()?.filter { it.isFile && it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    companion object {
        const val SESSION_LIMIT = 200
        const val SWEEP_AGE_MS = 24 * 60 * 60 * 1000L
        const val DIAGNOSTICS_DIR = "diagnostics"
        val LOG_PATHS = listOf(TermuxPaths.CLI_LOG, TermuxPaths.HUB_DAEMON_LOG)
        private val SESSION_ID_PATTERN = Regex("^[A-Za-z0-9_-]+$")
        private val ENCODER = Json { encodeDefaults = false }
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        /** Desktop `section`: `\n===== <title> =====\n<body>` with a trailing newline. */
        fun section(title: String, body: String): String = "\n===== $title =====\n" + if (body.endsWith("\n")) body else "$body\n"

        /** `cline-diagnostics-<version>-<yyyyMMdd-HHmmss>.txt` in local time. */
        fun fileName(versionName: String, nowMs: Long, zone: ZoneId): String =
            "cline-diagnostics-$versionName-${Instant.ofEpochMilli(nowMs).atZone(zone).format(STAMP)}.txt"

        /** The manifest without the prompt text (desktop rule): `prompt`, `metadata.prompt`, `metadata.systemPrompt`. */
        fun stripPrompts(record: JsonObject): JsonObject {
            val rest = record.filterKeys { it != "prompt" }.toMutableMap()
            (rest["metadata"] as? JsonObject)?.let { metadata -> rest["metadata"] = JsonObject(metadata.filterKeys { it != "prompt" && it != "systemPrompt" }) }
            return JsonObject(rest)
        }

        /** The gate's name for the header: `Ready`, `Busy(Reconnecting)`, `Down(Asleep)`, `Blocked(Termux)`. */
        fun gateName(gate: KernelGate): String = when (gate) {
            is KernelGate.Busy -> "Busy(${gate.kind})"
            is KernelGate.Down -> "Down(${gate.variant})"
            is KernelGate.Blocked -> "Blocked(${gate.focus})"
            KernelGate.Ready -> "Ready"
            KernelGate.DegradedNoCommands -> "DegradedNoCommands"
        }

        fun formatTransitions(list: List<KernelTransitions.Transition>): String =
            if (list.isEmpty()) "(none)" else list.joinToString("\n") { "+${it.atElapsedMs} ms  ${it.from} -> ${it.to}" }

        fun sessionRow(record: JsonObject): DiagnosticSession? {
            val id = record["sessionId"].jsString() ?: return null
            val metadata = record["metadata"] as? JsonObject
            val label = metadataTitle(metadata).ifEmpty { metadata?.get("prompt").jsString()?.trim().orEmpty() }.ifEmpty { id }
            val workspace = record["workspaceRoot"].jsString() ?: record["cwd"].jsString()
            return DiagnosticSession(
                sessionId = id,
                label = label,
                workspaceName = workspace?.let { basenamePath(it) }?.ifEmpty { null },
                updatedAt = record["updatedAt"].jsNumber()?.toLong(),
            )
        }

        private fun describe(failure: RepoFailure): String = failure.detail ?: failure.toString()
    }
}
