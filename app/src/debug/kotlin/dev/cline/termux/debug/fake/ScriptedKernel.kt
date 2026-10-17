package dev.cline.termux.debug.fake

import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.KernelCommandResult
import dev.cline.termux.core.kernel.KernelCommands
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubEndpoints
import dev.cline.termux.core.kernel.hub.HubFrame
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.core.kernel.hub.HubReplyError
import dev.cline.termux.core.kernel.hub.HubSocket
import dev.cline.termux.core.kernel.hub.HubSocketClosed
import dev.cline.termux.core.kernel.hub.HubTransport
import dev.cline.termux.core.kernel.hub.HubUpgradeFailed
import dev.cline.termux.core.kernel.hub.HubVersion
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.DetachedHandle
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * One scripted Termux + Cline Hub for a [FakeKernelScenario] (onboarding-kernel.md §0.9):
 * a little model of the phone (Termux presence, permission, the port's VERSION file, a Hub
 * process with its `production.json`, `providers.json`) driven by timers, exposed through the
 * four seams the app uses: [executor] (RUN_COMMAND), [commands] (the coordinator's view of
 * it), [prober] (`/health`, `/version`) and [transport] (the Hub WebSocket).
 *
 * Every timeline runs on [parentScope] with `delay`, so the JVM tests drive it in virtual
 * time and the debug app in real time. Labels of the commands run are kept in [trace] (labels
 * only, never arguments: the `auth` argv carries a key).
 */
class ScriptedKernel(
    val scenario: FakeKernelScenario,
    parentScope: CoroutineScope,
    private val wallClockMs: () -> Long = System::currentTimeMillis,
    private val onTrace: (String) -> Unit = {},
    random: Random = Random.Default,
) {
    private val scope: CoroutineScope = parentScope + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job])
    private val rng = random
    private val lock = Any()

    /** Hub process facts; the token rotates with every lifetime (docs/08). */
    private data class Proc(val pid: Int, val startedAt: String, val token: String, val hubId: String)

    private var proc: Proc? = null

    /** What `production.json` holds: removed by a graceful stop, left behind by an OS kill. */
    private var discovery: Proc? = null
    private var nextPid = FIRST_PID
    private var firstPid: Int? = null
    private var firstReadyHandled = false
    private val sockets = mutableListOf<ScriptedSocket>()
    private var providersJson: String? = initialProviders()
    private var providersMtimeS: Long = wallClockMs() / 1000
    private var signIn: CompletableDeferred<TermuxResult>? = null
    private val executionIds = AtomicInteger(1000)
    private val _trace = mutableListOf<String>()

    /** RUN_COMMAND labels in order (the debug "RUN_COMMAND trace" of KS F.9). */
    val trace: List<String> get() = synchronized(lock) { _trace.toList() }

    val presence: TermuxPresence = when (scenario) {
        FakeKernelScenario.TermuxMissing -> TermuxPresence.Missing
        FakeKernelScenario.TermuxDisabled -> TermuxPresence.Disabled
        FakeKernelScenario.TermuxOld -> TermuxPresence.Installed(101, "0.101.0")
        else -> TermuxPresence.Installed(118, "0.118.3")
    }

    val permitted: Boolean = scenario != FakeKernelScenario.PermissionMissing

    init {
        if (scenario.hubUpAtLaunch) synchronized(lock) { startProcLocked() }
    }

    /** Stops every timer of this kernel (a new scenario replaces it). */
    fun close() {
        synchronized(lock) { sockets.toList() }.forEach { it.drop() }
        scope.cancel()
    }

    // ---- Hub process ---------------------------------------------------------------------

    val hubRunning: Boolean get() = synchronized(lock) { proc != null }

    private fun startProcLocked(): Proc {
        val pid = nextPid
        nextPid += PID_STEP
        val next = Proc(
            pid = pid,
            startedAt = Instant.ofEpochMilli(wallClockMs()).toString(),
            token = hex(64),
            hubId = "hub_" + hex(12),
        )
        proc = next
        discovery = next
        if (firstPid == null) firstPid = pid
        if (scenario == FakeKernelScenario.HubCrashLoop) {
            scope.launch {
                delay(CRASH_AFTER_START_MS)
                kill(graceful = false, onlyPid = pid)
            }
        }
        return next
    }

    private fun startProc(): Proc = synchronized(lock) { startProcLocked() }

    /** Ends the Hub: [graceful] removes `production.json` (`hub stop`, `doctor fix`); an OS kill leaves it. */
    private fun kill(graceful: Boolean, onlyPid: Int? = null) {
        val dropped = synchronized(lock) {
            val current = proc ?: return
            if (onlyPid != null && current.pid != onlyPid) return
            proc = null
            if (graceful) discovery = null
            sockets.toList().also { sockets.clear() }
        }
        // The Hub terminates its sockets without a close frame (KS 0.3).
        dropped.forEach { it.drop() }
    }

    private fun hex(length: Int): String = buildString { repeat(length) { append(HEX[rng.nextInt(16)]) } }

    // ---- /health and /version ---------------------------------------------------------------

    private val protocol: String get() = if (scenario == FakeKernelScenario.ProtocolUnsupported) "v2" else "v1"

    fun health(port: Int): HubHealth? {
        if (port != HubEndpoints.DEFAULT_PORT) return null
        synchronized(lock) { proc } ?: return null
        return HubHealth(
            ok = true,
            protocolVersion = protocol,
            minClientProtocolVersion = protocol,
            maxClientProtocolVersion = protocol,
            coreVersion = CORE_VERSION,
            buildId = BUILD_ID,
            draining = scenario == FakeKernelScenario.Draining,
            host = "127.0.0.1",
            port = port,
            url = HubEndpoints.socketUrl(port),
        )
    }

    fun version(port: Int): HubVersion? {
        if (port != HubEndpoints.DEFAULT_PORT) return null
        val current = synchronized(lock) { proc } ?: return null
        return HubVersion(
            protocolVersion = protocol,
            minClientProtocolVersion = protocol,
            maxClientProtocolVersion = protocol,
            coreVersion = CORE_VERSION,
            buildId = BUILD_ID,
            pid = current.pid,
            startedAt = current.startedAt,
        )
    }

    // ---- RUN_COMMAND ------------------------------------------------------------------------

    /** [TermuxExecutor.run] with the real runner's contract (exceptions for undeliverable commands). */
    suspend fun run(command: TermuxCommand, timeoutMs: Long): TermuxResult {
        record(command.label)
        deliveryCheck()
        when (scenario) {
            FakeKernelScenario.ExternalAppsBlocked -> {
                delay(LATENCY_MS)
                throw TermuxUnavailable.ExternalAppsBlocked(EXTERNAL_APPS_ERRMSG)
            }
            FakeKernelScenario.TermuxOld -> {
                // Termux before 0.109 never posts a result.
                delay(timeoutMs)
                throw TermuxUnavailable.TimedOut(command.label, timeoutMs)
            }
            FakeKernelScenario.TermuxNotSetUp -> {
                delay(LATENCY_MS)
                return TermuxResult.undelivered(TermuxResult.ERR_EXECUTABLE_FAILED, "Failed to execute \"${command.path}\": not found")
            }
            else -> Unit
        }
        delay(LATENCY_MS)
        return when {
            command.label == ClineCommands.Labels.PROBE_VERSION -> probeVersion()
            command.label == ClineCommands.Labels.READ_PORT_RELEASE -> readPortRelease()
            command.label == ClineCommands.Labels.CLI_VERSION -> cliOrMissing { TermuxResult.success("$CLI_VERSION\n") }
            command.label == ClineCommands.Labels.HUB_START -> cliOrMissing { hubStart(command, timeoutMs) }
            command.label == ClineCommands.Labels.HUB_STATUS -> cliOrMissing { hubStatus() }
            command.label == ClineCommands.Labels.HUB_STOP -> cliOrMissing { hubStop() }
            command.label == ClineCommands.Labels.HUB_UPGRADE -> cliOrMissing { hubUpgrade(command, timeoutMs) }
            command.label == ClineCommands.Labels.WAKE_LOCK || command.label == ClineCommands.Labels.WAKE_UNLOCK -> TermuxResult.success("")
            command.label == ClineCommands.Labels.READ_HUB_DISCOVERY -> readDiscovery()
            command.label == ClineCommands.Labels.TAIL_DAEMON_LOG -> TermuxResult.success(daemonLog())
            command.label == ClineCommands.Labels.TAIL_LOG -> TermuxResult.success("")
            command.label == ClineCommands.Labels.DOCTOR -> cliOrMissing { TermuxResult.success(doctorJson()) }
            command.label == ClineCommands.Labels.DOCTOR_FIX -> cliOrMissing { doctorFix() }
            command.label == ClineCommands.Labels.STAT_PROVIDERS -> statProviders()
            command.label == ClineCommands.Labels.READ_PROVIDER_PRESENCE -> readProviders()
            command.label == ClineCommands.Labels.CANCEL_AUTH -> cancelSignIn()
            command.label.startsWith(AUTH_LABEL_PREFIX) && command.args.contains("--provider") -> cliOrMissing { authProvider(command) }
            else -> unscripted(command)
        }
    }

    /** [TermuxExecutor.runDetached]: the foreground sign-in; anything else ends at once as unscripted. */
    fun runDetached(command: TermuxCommand): DetachedHandle {
        record(command.label)
        deliveryCheck()
        val exit = CompletableDeferred<TermuxResult>()
        if (command.label == ClineCommands.Labels.SIGN_IN_CLINE && scenario != FakeKernelScenario.PortMissing) {
            synchronized(lock) { signIn = exit }
            if (scenario != FakeKernelScenario.SignInNever) {
                scope.launch {
                    delay(SIGN_IN_AFTER_MS)
                    writeProvider("cline", clineEntry())
                    exit.complete(TermuxResult.success(""))
                }
            }
        } else {
            exit.complete(unscripted(command))
        }
        val id = executionIds.incrementAndGet()
        return object : DetachedHandle {
            override val executionId: Int = id
            override val command: TermuxCommand = command
            override val earlyExit: Deferred<TermuxResult> = exit
            override fun cancel() {
                exit.cancel()
            }
        }
    }

    private fun record(label: String) {
        synchronized(lock) { _trace += label }
        onTrace(label)
    }

    private fun deliveryCheck() {
        if (!presence.installed) throw TermuxUnavailable.NotInstalled()
        if (!permitted) throw TermuxUnavailable.PermissionMissing()
    }

    private inline fun cliOrMissing(block: () -> TermuxResult): TermuxResult =
        if (scenario == FakeKernelScenario.PortMissing) {
            TermuxResult.undelivered(TermuxResult.ERR_EXECUTABLE_FAILED, "Failed to execute \"${TermuxPaths.CLINE}\": No such file or directory")
        } else {
            block()
        }

    private val release: String?
        get() = when (scenario) {
            FakeKernelScenario.PortOld -> "3.0.68-termux.1"
            FakeKernelScenario.HubStartPortOld, FakeKernelScenario.PortMissing -> null
            else -> "3.0.68-termux.2"
        }

    private fun probeVersion(): TermuxResult = when (scenario) {
        FakeKernelScenario.PortMissing -> TermuxResult.failure(1, "cat: ${TermuxPaths.PORT_VERSION_FILE}: No such file or directory\n")
        FakeKernelScenario.HubStartPortOld -> TermuxResult.success("cline=$CLI_VERSION\n")
        else -> TermuxResult.success("release=$release\ntag=v$release\ncline=$CLI_VERSION\n")
    }

    private fun readPortRelease(): TermuxResult = when (val r = release) {
        null -> if (scenario == FakeKernelScenario.PortMissing) {
            TermuxResult.failure(1, "")
        } else {
            TermuxResult.success("${TermuxPaths.PREFIX}/opt/cline-termux/3.0.68-termux.1\n")
        }
        else -> TermuxResult.success("${TermuxPaths.PREFIX}/opt/cline-termux/$r\n")
    }

    private suspend fun hubStart(command: TermuxCommand, timeoutMs: Long): TermuxResult = when (scenario) {
        FakeKernelScenario.HubStartSlow -> {
            // The CLI gives up at 15 s while the daemon keeps booting; /health answers at 25 s.
            scope.launch {
                delay(SLOW_HEALTH_AT_MS - LATENCY_MS)
                startProc()
            }
            delay(DETACHED_GIVE_UP_MS - LATENCY_MS)
            TermuxResult.failure(1, STILL_BOOTING_STDERR)
        }
        FakeKernelScenario.HubStartFail, FakeKernelScenario.HubStartPortOld -> {
            delay(DETACHED_GIVE_UP_MS - LATENCY_MS)
            TermuxResult.failure(1, STILL_BOOTING_STDERR)
        }
        FakeKernelScenario.HubStartHang -> {
            delay(timeoutMs)
            throw TermuxUnavailable.TimedOut(command.label, timeoutMs)
        }
        else -> {
            if (!hubRunning) {
                delay(START_MS)
                startProc()
            }
            TermuxResult.success("${HubEndpoints.socketUrl()}\n")
        }
    }

    private fun hubStatus(): TermuxResult {
        val current = synchronized(lock) { proc }
        val json = if (current == null) {
            "{\"running\":false}"
        } else {
            "{\"running\":true,\"pid\":${current.pid},\"url\":\"${HubEndpoints.socketUrl()}\",\"startedAt\":\"${current.startedAt}\"}"
        }
        return TermuxResult.success(json + "\n")
    }

    private fun hubStop(): TermuxResult {
        val wasRunning = hubRunning
        kill(graceful = true)
        return TermuxResult.success("{\"stopped\":$wasRunning}\n")
    }

    private suspend fun hubUpgrade(command: TermuxCommand, timeoutMs: Long): TermuxResult {
        if (scenario == FakeKernelScenario.RestartSlow) {
            // The old Hub goes at 5 s, the new one answers at 70 s, the result never arrives in time.
            scope.launch {
                delay(UPGRADE_STOP_MS)
                kill(graceful = true)
                delay(RESTART_SLOW_UP_AT_MS - UPGRADE_STOP_MS)
                startProc()
            }
            delay(timeoutMs)
            throw TermuxUnavailable.TimedOut(command.label, timeoutMs)
        }
        if (foreignSessionLive()) {
            delay(UPGRADE_WAIT_MS)
            return TermuxResult.failure(1, STILL_SERVING_STDERR)
        }
        delay(UPGRADE_STOP_MS)
        kill(graceful = true)
        delay(START_MS)
        startProc()
        return TermuxResult.success("{\"upgraded\":true,\"url\":\"${HubEndpoints.socketUrl()}\"}\n")
    }

    private fun readDiscovery(): TermuxResult {
        val record = synchronized(lock) { discovery }
            ?: return TermuxResult.failure(1, "cat: ${TermuxPaths.HUB_DISCOVERY}: No such file or directory\n")
        val json = buildJsonObject {
            put("hubId", record.hubId)
            put("protocolVersion", protocol)
            put("authToken", record.token)
            put("host", "127.0.0.1")
            put("port", HubEndpoints.DEFAULT_PORT)
            put("url", HubEndpoints.socketUrl())
            put("pid", record.pid)
            put("startedAt", record.startedAt)
            put("updatedAt", record.startedAt)
            put("coreVersion", CORE_VERSION)
            put("buildId", BUILD_ID)
        }
        return TermuxResult.success(json.toString() + "\n")
    }

    private fun daemonLog(): String = if (scenario == FakeKernelScenario.HubStartPortOld) {
        "[hub-daemon] starting\nerror: Module not found \"${TermuxPaths.PREFIX}/opt/cline-termux/3.0.68-termux.1/entry.js\"\n"
    } else {
        "[hub-daemon] listening on ${HubEndpoints.socketUrl()}\n"
    }

    private fun doctorJson(): String {
        val current = synchronized(lock) { proc }
        return buildJsonObject {
            put("cliVersion", CLI_VERSION)
            put("coreVersion", CORE_VERSION)
            put("hubHealthy", current != null)
            if (current != null) {
                put("hubPid", current.pid)
                put("hubUptime", "12s")
            }
            putJsonArray("listeningPids") { if (current != null) add(kotlinx.serialization.json.JsonPrimitive(current.pid)) }
            putJsonArray("staleHubPids") {}
            putJsonArray("hubStartupLocks") {}
            putJsonArray("staleCliPids") {}
        }.toString() + "\n"
    }

    private fun doctorFix(): TermuxResult {
        kill(graceful = true)
        return TermuxResult.success(doctorJson())
    }

    private fun statProviders(): TermuxResult = synchronized(lock) {
        if (providersJson == null) {
            TermuxResult.failure(1, "stat: cannot statx '${TermuxPaths.PROVIDERS_JSON}': No such file or directory\n")
        } else {
            TermuxResult.success("$providersMtimeS\n")
        }
    }

    private fun readProviders(): TermuxResult = synchronized(lock) {
        providersJson?.let { TermuxResult.success(it) }
            ?: TermuxResult.failure(1, "cat: ${TermuxPaths.PROVIDERS_JSON}: No such file or directory\n")
    }

    private suspend fun authProvider(command: TermuxCommand): TermuxResult {
        val providerId = command.args.valueAfter("--provider").orEmpty()
        delay(AUTH_MS)
        if (scenario == FakeKernelScenario.AuthFailInvalidProvider) {
            return TermuxResult.failure(1, "invalid provider \"$providerId\"\n")
        }
        val model = command.args.valueAfter("--modelid").orEmpty()
        val hasBaseUrl = command.args.contains("--baseurl")
        // The key itself is never copied into the fake file: a placeholder marks its presence.
        writeProvider(providerId, keyedEntry(providerId, model, hasBaseUrl))
        return TermuxResult.success("")
    }

    private fun cancelSignIn(): TermuxResult {
        val pending = synchronized(lock) { signIn.also { signIn = null } }
        pending?.complete(TermuxResult.failure(SIGTERM_EXIT, ""))
        return TermuxResult.success("")
    }

    private fun unscripted(command: TermuxCommand): TermuxResult =
        TermuxResult.failure(UNSCRIPTED_EXIT, "${command.label}: not available while the debug fake kernel runs (${scenario.id}).\n")

    private fun List<String>.valueAfter(flag: String): String? = indexOf(flag).takeIf { it >= 0 && it + 1 < size }?.let { this[it + 1] }

    // ---- providers.json -------------------------------------------------------------------

    private fun initialProviders(): String? = when (scenario) {
        FakeKernelScenario.ProvidersEmpty, FakeKernelScenario.AuthOk, FakeKernelScenario.AuthFailInvalidProvider,
        FakeKernelScenario.SignInSuccessAfter20s, FakeKernelScenario.SignInNever -> null
        FakeKernelScenario.ProvidersCline -> providersFile("cline", mapOf("cline" to clineEntry()))
        FakeKernelScenario.ProvidersOllama -> providersFile("ollama", mapOf("ollama" to ollamaEntry()))
        else -> providersFile("anthropic", mapOf("anthropic" to keyedEntry("anthropic", "claude-sonnet-4-5", hasBaseUrl = false)))
    }

    private fun writeProvider(id: String, entry: JsonObject) {
        synchronized(lock) {
            val existing = providersJson?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
            val entries = LinkedHashMap<String, JsonObject>()
            (existing?.get("providers") as? JsonObject)?.forEach { (key, value) -> (value as? JsonObject)?.let { entries[key] = it } }
            entries[id] = entry
            providersJson = providersFile(id, entries)
            providersMtimeS = wallClockMs() / 1000
        }
    }

    private fun providersFile(lastUsed: String, entries: Map<String, JsonObject>): String = buildJsonObject {
        put("version", 1)
        put("lastUsedProvider", lastUsed)
        putJsonObject("providers") { entries.forEach { (id, entry) -> put(id, entry) } }
    }.toString()

    private fun updatedAt(): String = Instant.ofEpochMilli(wallClockMs()).toString()

    private fun keyedEntry(id: String, model: String, hasBaseUrl: Boolean): JsonObject = buildJsonObject {
        putJsonObject("settings") {
            put("provider", id)
            put("apiKey", FAKE_KEY_PLACEHOLDER)
            if (model.isNotBlank()) put("model", model)
            if (hasBaseUrl) put("baseUrl", "https://api.example.com/v1")
        }
        put("tokenSource", "manual")
        put("updatedAt", updatedAt())
    }

    private fun clineEntry(): JsonObject = buildJsonObject {
        putJsonObject("settings") {
            put("provider", "cline")
            put("model", "anthropic/claude-sonnet-4.5")
            putJsonObject("auth") {
                put("accessToken", FAKE_KEY_PLACEHOLDER)
                put("accountId", "fake-account")
            }
        }
        put("tokenSource", "oauth")
        put("updatedAt", updatedAt())
    }

    private fun ollamaEntry(): JsonObject = buildJsonObject {
        putJsonObject("settings") {
            put("provider", "ollama")
            put("model", "qwen3-coder:30b")
            put("baseUrl", "http://127.0.0.1:11434")
        }
        put("tokenSource", "manual")
        put("updatedAt", updatedAt())
    }

    // ---- the Hub socket -------------------------------------------------------------------

    private fun foreignSessionLive(): Boolean = synchronized(lock) {
        scenario == FakeKernelScenario.RestartBusyOtherClient && proc != null && proc?.pid == firstPid
    }

    suspend fun open(url: String, token: String?): HubSocket {
        delay(SOCKET_OPEN_MS)
        if (scenario == FakeKernelScenario.AuthFailed) {
            throw HubUpgradeFailed(401, "websocket upgrade failed: Expected HTTP 101 response but was '401 Unauthorized'")
        }
        val socket = synchronized(lock) {
            val current = proc ?: return@synchronized null
            if (token == null || token != current.token) {
                throw HubUpgradeFailed(401, "websocket upgrade failed: Expected HTTP 101 response but was '401 Unauthorized'")
            }
            ScriptedSocket(current.pid).also { sockets += it }
        }
        return socket ?: throw HubUpgradeFailed(null, "websocket upgrade failed: Failed to connect to /127.0.0.1:${HubEndpoints.DEFAULT_PORT}")
    }

    /** The first registration served by this kernel starts the "after Ready" timelines of §0.9. */
    private fun onRegistered() {
        val start = synchronized(lock) {
            if (firstReadyHandled) false else true.also { firstReadyHandled = true }
        }
        if (!start) return
        when (scenario) {
            FakeKernelScenario.AsleepAfterReady -> scope.launch {
                delay(DIES_AFTER_READY_MS)
                kill(graceful = false)
            }
            FakeKernelScenario.StoppedInTermux -> scope.launch {
                delay(DIES_AFTER_READY_MS)
                kill(graceful = true)
            }
            FakeKernelScenario.HubComesBack -> scope.launch {
                delay(DIES_AFTER_READY_MS)
                kill(graceful = false)
                delay(COMES_BACK_AFTER_MS)
                startProc()
            }
            else -> Unit
        }
    }

    private inner class ScriptedSocket(private val pid: Int) : HubSocket {
        private val inbound = Channel<HubFrame>(Channel.UNLIMITED)
        @Volatile private var closed = false

        override val incoming: Flow<HubFrame> = inbound.receiveAsFlow()

        override fun send(frame: HubFrame): Boolean {
            if (closed) return false
            if (frame is HubFrame.Command) {
                val envelope = frame.envelope
                val reply = reply(envelope.command)
                inbound.trySend(HubFrame.Reply(reply.copy(requestId = envelope.requestId)))
                if (envelope.command == HubCommands.CLIENT_REGISTER) onRegistered()
            }
            return true
        }

        private fun reply(command: String): HubReplyEnvelope = when (command) {
            HubCommands.CLIENT_REGISTER -> ok(buildJsonObject { put("registered", true) })
            HubCommands.HUB_STATUS -> ok(
                buildJsonObject {
                    put("hubId", synchronized(lock) { proc?.hubId }.orEmpty())
                    put("draining", scenario == FakeKernelScenario.Draining)
                    put("activeRpcTurns", 0)
                    put("pendingRuns", 0)
                    putJsonObject("eventLog") { put("lastSequence", 0) }
                    put("idle", true)
                },
            )
            HubCommands.SESSION_LIST -> ok(
                buildJsonObject {
                    putJsonArray("sessions") {
                        if (foreignSessionLive() && pid == firstPid) {
                            add(
                                buildJsonObject {
                                    put("sessionId", "sess_fake_tui")
                                    put("status", "running")
                                    putJsonArray("participants") {
                                        add(buildJsonObject { put("clientId", FOREIGN_CLIENT_ID); put("role", "creator") })
                                    }
                                },
                            )
                        }
                    }
                },
            )
            in HubCommands.DRAIN_GATED -> HubReplyEnvelope(
                ok = false,
                error = HubReplyError("command_failed", "The debug fake kernel does not run agent sessions."),
            )
            else -> ok(JsonObject(emptyMap()))
        }

        private fun ok(payload: JsonObject) = HubReplyEnvelope(ok = true, payload = payload)

        override fun close(code: Int, reason: String) {
            if (closed) return
            closed = true
            synchronized(lock) { sockets.remove(this) }
            inbound.close(HubSocketClosed(code, reason))
        }

        override fun cancel() {
            closed = true
            synchronized(lock) { sockets.remove(this) }
            inbound.close(HubSocketClosed(1001, "client cancelled"))
        }

        fun drop() {
            closed = true
            inbound.close(HubSocketClosed(1006, "connection lost"))
        }
    }

    // ---- seams ------------------------------------------------------------------------------

    val executor: TermuxExecutor = object : TermuxExecutor {
        override fun isTermuxInstalled(): TermuxPresence = presence
        override fun hasPermission(): Boolean = permitted
        override suspend fun run(command: TermuxCommand, timeoutMs: Long): TermuxResult = this@ScriptedKernel.run(command, timeoutMs)
        override fun runDetached(command: TermuxCommand): DetachedHandle = this@ScriptedKernel.runDetached(command)
    }

    val prober: HubProber = object : HubProber {
        override suspend fun health(port: Int): HubHealth? = this@ScriptedKernel.health(port)
        override suspend fun version(port: Int): HubVersion? = this@ScriptedKernel.version(port)
    }

    val transport: HubTransport = object : HubTransport {
        override suspend fun open(url: String, token: String?): HubSocket = this@ScriptedKernel.open(url, token)
    }

    /**
     * The coordinator's RUN_COMMAND view over [executor]: catalog rows run and exceptions folded
     * the way the coordinator classifies them (an `allow-external-apps` refusal stays a Termux
     * internal error with its `errmsg`, which the coordinator turns into `ExternalAppsBlocked`).
     */
    val commands: KernelCommands = object : KernelCommands {
        override fun termuxPresence(): TermuxPresence = presence
        override fun hasRunCommandPermission(): Boolean = permitted
        override suspend fun readPortVersion() = fold(ClineCommands.probeVersion())
        override suspend fun hubStart(timeoutMs: Long) = fold(ClineCommands.hubStart(), timeoutMs)
        override suspend fun hubStatus() = fold(ClineCommands.hubStatus())
        override suspend fun hubStop() = fold(ClineCommands.hubStop())
        override suspend fun hubUpgrade() = fold(ClineCommands.hubUpgrade())
        override suspend fun readDiscovery() = fold(ClineCommands.readHubDiscovery())
        override suspend fun tailDaemonLog() = fold(ClineCommands.tailDaemonLog())
        override suspend fun doctorJson() = fold(ClineCommands.doctorJson())
        override suspend fun doctorFix() = fold(ClineCommands.doctorFix())
        override suspend fun wakeLock() = fold(ClineCommands.wakeLock())
        override suspend fun wakeUnlock() = fold(ClineCommands.wakeUnlock())

        private suspend fun fold(command: TermuxCommand, timeoutMs: Long = command.timeoutMs): KernelCommandResult = try {
            val result = run(command, timeoutMs)
            KernelCommandResult.Completed(result.stdout, result.stderr, result.exitCode, result.err, result.errmsg?.takeIf { it.isNotBlank() })
        } catch (e: TermuxUnavailable.TimedOut) {
            KernelCommandResult.TimedOut(e.afterMillis)
        } catch (_: TermuxUnavailable.NotInstalled) {
            KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.NotInstalled, null)
        } catch (_: TermuxUnavailable.PermissionMissing) {
            KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.PermissionMissing, null)
        } catch (e: TermuxUnavailable.ExternalAppsBlocked) {
            KernelCommandResult.Completed("", "", -1, EXTERNAL_APPS_ERR, e.errmsg)
        } catch (e: TermuxUnavailable.StartFailed) {
            KernelCommandResult.Unavailable(KernelCommandResult.Unavailable.Reason.StartFailed, e.message)
        }
    }

    companion object {
        const val CORE_VERSION = "0.0.90"
        const val BUILD_ID = "source-0.0.90"
        const val CLI_VERSION = "3.0.68"
        const val FIRST_PID = 23817
        const val PID_STEP = 311
        const val FOREIGN_CLIENT_ID = "cli-tui-fake"
        const val FAKE_KEY_PLACEHOLDER = "debug-fake-kernel-placeholder"
        const val AUTH_LABEL_PREFIX = "cline auth "
        const val EXTERNAL_APPS_ERR = 2
        const val EXTERNAL_APPS_ERRMSG =
            "RUN_COMMAND Intent not allowed: allow-external-apps is not set to true in ~/.termux/termux.properties"
        const val STILL_BOOTING_STDERR = "Timed out after 15000ms waiting for detached hub startup.\n"
        const val STILL_SERVING_STDERR =
            "Hub is still serving sessions after the wait window; not replacing it. Finish or detach the active sessions, then retry.\n"
        const val UNSCRIPTED_EXIT = 127
        const val SIGTERM_EXIT = 143

        const val LATENCY_MS = 300L
        const val START_MS = 3_000L
        const val SOCKET_OPEN_MS = 50L
        const val AUTH_MS = 1_500L
        const val DETACHED_GIVE_UP_MS = 15_000L
        const val SLOW_HEALTH_AT_MS = 25_000L
        const val DIES_AFTER_READY_MS = 10_000L
        const val COMES_BACK_AFTER_MS = 30_000L
        const val CRASH_AFTER_START_MS = 8_000L
        const val UPGRADE_STOP_MS = 5_000L
        const val UPGRADE_WAIT_MS = 30_000L
        const val RESTART_SLOW_UP_AT_MS = 70_000L
        const val SIGN_IN_AFTER_MS = 20_000L

        private const val HEX = "0123456789abcdef"
    }
}
