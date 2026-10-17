package dev.cline.termux.core.kernel.dashboard

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.DetachedHandle
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** SP §0.3 state of the on-demand settings helper (the shipped dashboard server). */
sealed interface HelperState {
    data object Idle : HelperState

    /** UI: `LinearProgressIndicator` + "Starting settings helper...". */
    data class Starting(val sinceMs: Long) : HelperState
    data object Ready : HelperState
    data class Unavailable(val reason: HelperFailure, val detail: String?) : HelperState
}

enum class HelperFailure { TermuxMissing, PermissionMissing, ExternalAppsBlocked, StartTimeout, SocketFailed, BuildUnsupported, PortInUse, KernelStopped }

/**
 * The slice of the `KernelCoordinator` the helper needs (SP §0.3 steps 1.1, 1.2 and 4).
 * The coordinator is owned elsewhere; `AppGraph` adapts it.
 */
interface HelperKernel {
    /** `kernel.userStopped`: the user tapped "Stop kernel" (persisted, §7.2). */
    suspend fun isUserStopped(): Boolean

    /** `GET http://127.0.0.1:25463/health` answers. */
    suspend fun isHubHealthy(): Boolean

    /** The coordinator's own connect sequence (`cline hub start`, wake lock, token). Never throws. */
    suspend fun startHub()

    /** KC steps 2-3 again after the helper is Ready, so the app adopts whatever Hub is on 25463. */
    suspend fun adoptHub()
}

/** DataStore `helper.unsupported` (§7.2): set when the device Bun cannot serve the WebSocket. */
interface HelperUnsupportedStore {
    suspend fun read(): Boolean
    suspend fun write(unsupported: Boolean)
}

/**
 * Process-wide lease on the dashboard server (SP §0.3): kernel first, a per-process room
 * secret that lives only in this object, the probe matrix, the 20 s / 45 s start budgets,
 * one `/browser` socket, and a short grace after the last holder leaves (docs/07 D9: 5 s).
 * Screens call [acquire] from `ViewModel.init` and [release] from `onCleared()`.
 *
 * The secret never reaches DataStore, logs, `SavedStateHandle`, crash reports or
 * [state]; it travels only in the launch argv (`TermuxCommand.toString()` prints the
 * label) and the loopback URL (no logging interceptor on that client).
 */
class SettingsHelper(
    private val termux: TermuxExecutor,
    private val transport: DashboardTransport,
    private val kernel: HelperKernel,
    private val unsupportedStore: HelperUnsupportedStore,
    private val scope: CoroutineScope,
    private val elapsedMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val port: Int = DashboardEndpoints.DEFAULT_PORT,
    private val timing: Timing = Timing(),
    secretSource: () -> String = ::randomRoomSecret,
) {
    data class Timing(
        val releaseGraceMs: Long = 5_000,
        val startBudgetHubUpMs: Long = 20_000,
        val startBudgetHubDownMs: Long = 45_000,
        val pollIntervalMs: Long = 500,
        val foreignKillWaitMs: Long = 5_000,
        val foreignKillPollMs: Long = 500,
    )

    /** 32 hex characters from `SecureRandom`; a new app process makes a new one. */
    private val roomSecret: String = secretSource()

    private val mutex = Mutex()
    private val holders = LinkedHashSet<String>()
    private val _state = MutableStateFlow<HelperState>(HelperState.Idle)
    val state: StateFlow<HelperState> = _state.asStateFlow()

    private var client: DashboardClient? = null
    private var process: DetachedHandle? = null

    /** True once this process started or adopted a dashboard with its secret (so a stop is due). */
    private var ownsDashboard = false
    private var startJob: Job? = null
    private var graceJob: Job? = null

    val holderCount: Int get() = holders.size

    /** Adds a holder; the first one (or one arriving after a shutdown) starts the sequence. */
    suspend fun acquire(owner: String) = mutex.withLock {
        graceJob?.cancel()
        graceJob = null
        val first = holders.isEmpty()
        holders += owner
        if (first) launchStartLocked()
    }

    /**
     * Removes a holder; at zero the helper is stopped after the grace, never mid-start:
     * a start that is still polling finishes (ready or timed out) before the kill.
     */
    suspend fun release(owner: String) = mutex.withLock {
        if (!holders.remove(owner) || holders.isNotEmpty()) return@withLock
        graceJob = scope.launch {
            delay(timing.releaseGraceMs)
            startJob?.join()
            mutex.withLock { if (holders.isEmpty()) shutdownLocked() }
        }
    }

    /** The unavailable card's "Retry" (and "Start kernel" after the coordinator started it). */
    suspend fun retry() = mutex.withLock {
        if (holders.isNotEmpty()) launchStartLocked()
    }

    /** Kernel status sheet "Check again" succeeded, or the app was updated: the helper may be tried again. */
    suspend fun clearUnsupported() = unsupportedStore.write(false)

    /** Waits for a running start sequence; returns the state it settled in. */
    suspend fun awaitSettled(): HelperState {
        startJob?.join()
        return _state.value
    }

    /**
     * Sends a `desktopCommand` over the lease's socket.
     * @throws HelperUnavailable.NotRunning when the helper is not Ready (after a start settles).
     */
    suspend fun desktopCommand(
        command: String,
        args: JsonObject = JsonObject(emptyMap()),
        timeoutMs: Long = DashboardTimeouts.forCommand(command),
    ): JsonElement {
        if (_state.value is HelperState.Starting) awaitSettled()
        val current = client
        val settled = _state.value
        if (settled !is HelperState.Ready || current == null) {
            throw HelperUnavailable.NotRunning((settled as? HelperState.Unavailable)?.reason)
        }
        return current.desktopCommand(command, args, timeoutMs)
    }

    private fun launchStartLocked() {
        if (startJob?.isActive == true) return
        if (_state.value is HelperState.Ready && client?.isOpen == true) return
        startJob = scope.launch { startSequence() }
    }

    private suspend fun startSequence() {
        _state.value = HelperState.Starting(elapsedMs())

        // 1.1 Kernel first: never restart a kernel the user stopped.
        if (kernel.isUserStopped()) return fail(HelperFailure.KernelStopped)
        if (unsupportedStore.read()) return fail(HelperFailure.BuildUnsupported)
        if (!termux.isTermuxInstalled().installed) return fail(HelperFailure.TermuxMissing)
        if (!termux.hasPermission()) return fail(HelperFailure.PermissionMissing)

        // 1.2 Let the coordinator own the Hub before the dashboard can spawn one.
        var hubUp = kernel.isHubHealthy()
        if (!hubUp) {
            runCatching { kernel.startHub() }
            hubUp = kernel.isHubHealthy()
        }

        // 1.4 Probe matrix.
        var killedForeign = false
        when (transport.probeHealth(port, roomSecret)) {
            HealthProbe.Ok -> {
                return if (transport.probeHealth(port, null) is HealthProbe.Ok) {
                    fail(HelperFailure.PortInUse) // secret-less dashboard: never reused
                } else {
                    ownsDashboard = true
                    connectAndFinish(adopt = false) // ours, from this process
                }
            }
            HealthProbe.Forbidden -> {
                if (!killForeignHelper()) return fail(HelperFailure.PortInUse)
                killedForeign = true
            }
            HealthProbe.Refused, is HealthProbe.Unexpected -> Unit
        }

        // 1.5 Start and poll. One helper per lease: a launch of an earlier attempt that is still
        // booting (a slow cold start that ran past its budget) is polled again, not launched
        // twice; a second `cline dashboard` would only race it for the port. The generic kill
        // above also matches our own launch line, so after it the earlier launch is not trusted.
        val earlier = process
        val handle = if (earlier != null && !earlier.earlyExit.isCompleted && !killedForeign) {
            earlier
        } else {
            if (earlier != null) {
                // It exited (or was killed): make sure nothing of it is left before relaunching.
                process = null
                earlier.cancel()
                runCatching { termux.run(ClineCommands.dashboardStop(roomSecret)) }
            }
            try {
                termux.runDetached(ClineCommands.dashboardStart(roomSecret))
            } catch (e: TermuxUnavailable) {
                return failFor(e)
            }
        }
        process = handle
        ownsDashboard = true
        val budget = if (hubUp) timing.startBudgetHubUpMs else timing.startBudgetHubDownMs
        val deadline = elapsedMs() + budget
        while (true) {
            if (handle.earlyExit.isCompleted) return failForEarlyExit(handle)
            if (transport.probeHealth(port, roomSecret) is HealthProbe.Ok) break
            if (elapsedMs() >= deadline) return fail(HelperFailure.StartTimeout)
            delay(timing.pollIntervalMs)
        }
        connectAndFinish(adopt = true)
    }

    /** 1.6 `/browser` with the secret; a non-101 answer from a dashboard that passed the secret probe is the device Bun's limit (OQ 3). */
    private suspend fun connectAndFinish(adopt: Boolean) {
        val connected = try {
            transport.connect(port, roomSecret) { onTransportClosed() }
        } catch (e: DashboardUpgradeFailed) {
            val status = e.httpStatus
            return if (status != null && status != 401 && status != 403) {
                unsupportedStore.write(true)
                fail(HelperFailure.BuildUnsupported, "HTTP $status")
            } else {
                fail(HelperFailure.SocketFailed, e.message)
            }
        } catch (e: IOException) {
            return fail(HelperFailure.SocketFailed, e.message)
        }
        client = connected
        _state.value = HelperState.Ready
        if (adopt) scope.launch { runCatching { kernel.adoptHub() } }
    }

    /** 1.4 "403": an earlier app process left its dashboard; only the app's own launch line matches. */
    private suspend fun killForeignHelper(): Boolean {
        runCatching { termux.run(ClineCommands.dashboardStop(roomSecret = null)) }
        val deadline = elapsedMs() + timing.foreignKillWaitMs
        while (elapsedMs() < deadline) {
            delay(timing.foreignKillPollMs)
            if (transport.probeHealth(port, roomSecret) is HealthProbe.Refused) return true
        }
        return transport.probeHealth(port, roomSecret) is HealthProbe.Refused
    }

    private fun onTransportClosed() {
        scope.launch {
            mutex.withLock {
                client = null
                if (_state.value is HelperState.Ready) _state.value = HelperState.Unavailable(HelperFailure.SocketFailed, null)
            }
        }
    }

    /** Step 3: close the socket and `pkill` exactly this process's dashboard. The Hub stays up. */
    private suspend fun shutdownLocked() {
        _state.value = HelperState.Idle
        client?.close()
        client = null
        process?.cancel()
        process = null
        if (ownsDashboard) {
            ownsDashboard = false
            runCatching { termux.run(ClineCommands.dashboardStop(roomSecret)) }
        }
    }

    private fun fail(reason: HelperFailure, detail: String? = null) {
        _state.value = HelperState.Unavailable(reason, detail)
    }

    private fun failFor(e: TermuxUnavailable) = when (e) {
        is TermuxUnavailable.NotInstalled -> fail(HelperFailure.TermuxMissing)
        is TermuxUnavailable.PermissionMissing -> fail(HelperFailure.PermissionMissing)
        is TermuxUnavailable.ExternalAppsBlocked -> fail(HelperFailure.ExternalAppsBlocked, e.errmsg)
        is TermuxUnavailable.StartFailed, is TermuxUnavailable.TimedOut -> fail(HelperFailure.StartTimeout, e.message)
    }

    /** 1.5: an exit before the secret probe answered; the stripped stderr tail is the card's "Details". */
    private suspend fun failForEarlyExit(handle: DetachedHandle) {
        val result: TermuxResult = try {
            handle.earlyExit.await()
        } catch (e: TermuxUnavailable) {
            return failFor(e)
        } catch (_: Exception) {
            return fail(HelperFailure.StartTimeout, null)
        }
        if (result.internalError) return fail(HelperFailure.StartTimeout, result.errmsg)
        fail(HelperFailure.StartTimeout, result.stderr.trim().takeLast(STDERR_TAIL_CHARS).ifBlank { null })
    }

    companion object {
        private const val STDERR_TAIL_CHARS = 400

        /** 32 lowercase hex characters (16 random bytes). */
        fun randomRoomSecret(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
