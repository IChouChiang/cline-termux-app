package dev.cline.termux.core.kernel

import dev.cline.termux.BuildInfo
import dev.cline.termux.core.kernel.termux.PortRelease
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.core.kernel.hub.HubDiscoveryRecord
import dev.cline.termux.core.kernel.hub.HubEndpoints
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.HubProtocol
import dev.cline.termux.core.kernel.hub.HubVersion
import dev.cline.termux.core.kernel.hub.isSessionGone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The kernel state machine (docs/07 §3.2; onboarding-kernel.md §0.3 state model, §0.4
 * connect sequence and policies, E.7 restart phases). One instance per process; every
 * public operation is serialized by one [Mutex]. It is the **only** caller of
 * `cline hub start|stop|upgrade`, `termux-wake-lock|unlock` and `cat production.json`.
 *
 * Screens never read anything but [state] (the kernel group's own ViewModels) and [gate]
 * (everyone else). [hubClient] is owned here: this class decides when it connects, and
 * answers its credential lookups ([currentCredentials]).
 */
class KernelCoordinator(
    private val commands: KernelCommands,
    private val prober: HubProber,
    private val tokens: HubTokenCache,
    private val prefs: KernelPreferences,
    private val hubClient: HubClient,
    private val scope: CoroutineScope,
    private val clock: KernelClock = SystemKernelClock,
    private val port: Int = HubEndpoints.DEFAULT_PORT,
    /** Developer Hub override (debug builds): a token that replaces the discovery read (docs/06). */
    private val tokenOverride: suspend () -> String? = { null },
    private val reattach: SessionReattachHook = SessionReattachHook.None,
    private val timing: Timing = Timing(),
    val transitions: KernelTransitions = KernelTransitions(),
    /**
     * True while this app's own foreground service runs (`KeepAlivePolicy.status ==
     * KeepAlive.Running`). KS §0.4 allows RUN_COMMAND "while the app is in the foreground or
     * from its own foreground service", so a drop handled with the service held is recovered
     * like one in the foreground (token re-read, discovery check).
     */
    private val serviceHeld: () -> Boolean = { false },
) {
    /** Budgets and intervals of KS §0.4 / E.7, tunable for tests. */
    data class Timing(
        /** H2: from the tap or the auto-start to the last `/health` poll. */
        val startBudgetMs: Long = KernelCommands.HUB_START_BUDGET_MS,
        val healthPollMs: Long = 1_000L,
        /** `/health` while `HubDown` in the foreground (KS §0.4 passive probe). */
        val passiveProbeMs: Long = 10_000L,
        /** H4: one retry when `production.json` is not written yet. */
        val discoveryRetryMs: Long = 1_000L,
        /** Auto-start on resume only after this long in the background. */
        val resumeThresholdMs: Long = 5_000L,
        /** A Hub that dies this soon after an auto-start counts toward the crash loop. */
        val crashWindowMs: Long = 60_000L,
        /** Two such deaths inside this window suspend auto-start (`CrashLoop`). */
        val crashLoopSpanMs: Long = 300_000L,
        /** E.7 timeout row: keep polling this long for a daemon that may still be booting. */
        val restartExtraPollMs: Long = 30_000L,
        /** How often the `slow` flag of a long start is re-evaluated. */
        val slowTickMs: Long = 1_000L,
        /**
         * Waits before each socket-only reconnect after the client gave up in the background
         * (no RUN_COMMAND: same daemon lifetime, cached token). After the last one fails the drop
         * reads as `HubDown(Asleep)` until the next foreground re-check.
         */
        val backgroundRetryMs: List<Long> = listOf(5_000L, 15_000L, 30_000L),
        /**
         * After a drop, `/health` is re-probed this often while it still answers: a Hub that is
         * stopping closes its sockets before its listener. [dropRecheckLimit] probes at most.
         */
        val dropRecheckMs: Long = 500L,
        val dropRecheckLimit: Int = 8,
    )

    /** What `stopKernel` came to (KS E.7, E.10). */
    sealed interface StopOutcome {
        data object Stopped : StopOutcome

        /** `{"stopped":false}` or a non-zero exit; the Hub may still run ([detail] is kernel text). */
        data class NotStopped(val detail: String?) : StopOutcome

        /** RUN_COMMAND could not run at all. */
        data class Unavailable(val detail: String?) : StopOutcome
    }

    /** The E.7 pre-check: who else holds sessions, and whether this app's own work is running. */
    data class RestartPrecheck(val others: HubSessionActivity, val ownBusy: Boolean, val unknown: Boolean) {
        val needsConfirm: Boolean get() = unknown || others.activeSessionCount > 0 || ownBusy
    }

    /** The E.7 outcome table. */
    sealed interface RestartOutcome {
        data object Restarted : RestartOutcome

        /** `hub upgrade` refused: the old Hub was un-drained and reconnected to; [stderr] shown verbatim. */
        data class StillServing(val stderr: String) : RestartOutcome

        /** Reconnected to the old Hub (or a Hub) but the replacement did not happen; [detail] = last stderr line or null. */
        data class Failed(val detail: String?) : RestartOutcome

        /** The Hub is gone; [state] is the resulting `HubDown` / blocked state. */
        data class Down(val state: KernelState) : RestartOutcome
    }

    /** Result of the "Keep Termux awake" switch (E.7). */
    sealed interface WakeLockOutcome {
        data object Applied : WakeLockOutcome
        data class Failed(val detail: String?) : WakeLockOutcome
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow<KernelState>(KernelState.Unknown)
    val state: StateFlow<KernelState> = _state.asStateFlow()

    private val _commandsAvailable = MutableStateFlow(true)

    /** False in degraded mode (RUN_COMMAND revoked while the Hub stays reachable, KS §0.4). */
    val commandsAvailable: StateFlow<Boolean> = _commandsAvailable.asStateFlow()

    private val _userStopped = MutableStateFlow(false)
    val userStopped: StateFlow<Boolean> = _userStopped.asStateFlow()

    private val _portVersion = MutableStateFlow<PortVersion?>(null)

    /** The last `VERSION` read (E.5 "Kernel" row and the update row). */
    val portVersion: StateFlow<PortVersion?> = _portVersion.asStateFlow()

    private val _wakeLockError = MutableStateFlow<String?>(null)

    /** Kernel text of the last failed `termux-wake-lock`, null when the last call succeeded (E.5). */
    val wakeLockError: StateFlow<String?> = _wakeLockError.asStateFlow()

    /** F.6 projection of [state]; recomputed every second while a start runs so `slow` flips at 20 s. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val gate: StateFlow<KernelGate> = combine(
        _state.flatMapLatest { s ->
            if (s is KernelState.Starting) flow { while (true) { emit(s); delay(timing.slowTickMs) } } else flowOf(s)
        },
        _commandsAvailable,
    ) { s, available -> s.toGate(clock.elapsedMs(), available) }
        .stateIn(scope, SharingStarted.Eagerly, KernelState.Unknown.toGate(0L))

    /** The last Hub this process knew (kept through `HubDown` for the sheet's "@port" line). */
    @Volatile var info: HubInfo? = null
        private set

    /** The HubClient this coordinator drives (session work goes through `HubSessionApi`). */
    val client: HubClient get() = hubClient

    // ---- private bookkeeping ---------------------------------------------------------------

    @Volatile private var sequenceActive = false
    @Volatile private var restarting = false
    @Volatile private var runCommandInFlight = false
    @Volatile private var foreground = false

    /** A drop was handled in the background without RUN_COMMAND; the next [onForeground] re-checks. */
    @Volatile private var pendingRecheck = false
    private var backgroundAtMs: Long? = null
    private var everForegrounded = false
    private var sawReady = false
    private var crashLoop = false
    private var lastAutoStartReadyAtMs: Long? = null
    private val earlyDeaths = ArrayDeque<Long>()
    /** `hubId:pid:startedAt` of the last discovery record this process used (in memory only). */
    private var lastLifetimeKey: String? = null

    /** The token of the connect in progress came from a new daemon lifetime. */
    private var freshLifetime = false
    private var restartPrevious: KernelState? = null
    private var probeJob: Job? = null

    /** The background socket-only reconnect after the client gave up; never an endless `Reconnecting` (CR §12.4). */
    private var backgroundRetryJob: Job? = null

    /** The classification that runs beside the client's reconnect attempts after a drop ([startDropCheck]). */
    private var dropCheckJob: Job? = null

    /** [HubConnectionState.Connected.generation] this coordinator last acted on (re-attach after a reconnect). */
    @Volatile private var handledGeneration: Long? = null

    init {
        scope.launch { hubClient.state.collect { onClientState(it) } }
        scope.launch { hubClient.draining.collect { onDraining(it) } }
        scope.launch { _state.collect { updatePassiveProbe() } }
        scope.launch { runCatching { _userStopped.value = prefs.userStopped() } }
    }

    // ---- public API ------------------------------------------------------------------------

    /**
     * Runs P1–H5 (KS §0.4). [startIfDown] allows `cline hub start`; it is still refused by
     * policy (`kernel.userStopped`, `CrashLoop`) unless [userInitiated] ("Start kernel").
     */
    suspend fun ensureConnected(startIfDown: Boolean = true, userInitiated: Boolean = false) = mutex.withLock {
        if (userInitiated) {
            crashLoop = false
            earlyDeaths.clear()
            if (_userStopped.value) {
                prefs.setUserStopped(false)
                _userStopped.value = false
            }
        }
        val allowStart = startIfDown && (userInitiated || (!_userStopped.value && !crashLoop))
        sequence(allowStart, userInitiated)
    }

    /** `cline hub stop` → `termux-wake-unlock` → `kernel.userStopped = true` (KS E.7). */
    suspend fun stopKernel(): StopOutcome = mutex.withLock {
        withSequence {
            if (hubClient.state.value !is HubConnectionState.Disconnected) hubClient.disconnect()
            val result = cmd { commands.hubStop() }
            releaseWakeLockIfHeld(force = true)
            tokens.clear()
            prefs.setUserStopped(true)
            _userStopped.value = true
            when (result) {
                is KernelCommandResult.Completed -> {
                    val stopped = result.ok && parseStopped(result.stdout) != false
                    if (stopped) {
                        // That daemon lifetime is over; the next one records its own release.
                        prefs.setHubLifetimeRelease(null)
                        emit(KernelState.HubDown(DownReason.Stopped, null, null))
                        StopOutcome.Stopped
                    } else {
                        // E.10: re-probe; a Hub that still answers is one we are merely disconnected from.
                        val alive = prober.health(port) != null
                        emit(if (alive) KernelState.HubDown(DownReason.Disconnected, null, null) else KernelState.HubDown(DownReason.Stopped, null, null))
                        StopOutcome.NotStopped(result.diagnosticText.takeLast(TAIL_CHARS).ifBlank { null })
                    }
                }
                is KernelCommandResult.TimedOut -> {
                    emit(if (prober.health(port) != null) KernelState.HubDown(DownReason.Disconnected, null, null) else KernelState.HubDown(DownReason.Stopped, null, null))
                    StopOutcome.NotStopped(null)
                }
                is KernelCommandResult.Unavailable -> {
                    emit(KernelState.HubDown(DownReason.Stopped, null, null))
                    StopOutcome.Unavailable(result.detail)
                }
            }
        }
    }

    /** Socket only; the Hub keeps running in Termux. The wake lock is released when `kernel.keepAwake` holds it. */
    suspend fun disconnect() = mutex.withLock {
        withSequence {
            hubClient.disconnect()
            if (prefs.keepAwake()) releaseWakeLockIfHeld(force = false)
            emit(KernelState.HubDown(DownReason.Disconnected, null, null))
        }
    }

    /** Re-runs the prerequisite checks P1–P3; when they pass, connects to a Hub that answers (never starts one). */
    suspend fun recheck() = mutex.withLock { sequence(startIfDown = false, userInitiated = false) }

    /** Plain `GET /health` (E.7 refresh); never changes the state by itself. */
    suspend fun probeHealth(): HubHealth? = prober.health(port)

    /**
     * E.7 refresh while the sheet is open: `/health`, `/version` and `hub.status {}` when
     * connected. A changed `(pid, startedAt)` outside a restart means the daemon restarted:
     * the token is re-read and the socket re-opened (E.10).
     */
    suspend fun refreshHubStatus(): HubLiveStatus? {
        val current = _state.value
        val currentInfo = current.hubInfo ?: return null
        if (sequenceActive) return (current as? KernelState.Ready)?.hub
        val health = prober.health(port) ?: return (current as? KernelState.Ready)?.hub
        val version = prober.version(port)
        if (version != null && version.pid != null && "${version.pid}:${version.startedAt}" != currentInfo.lifetimePair) {
            scope.launch { ensureConnected(startIfDown = false) }
            return (current as? KernelState.Ready)?.hub
        }
        val refreshed = currentInfo.withProbe(health, version)
        info = refreshed
        if (!hubClient.isConnected) return null
        val status = runCatching { HubLiveStatus.parse(hubClient.command(HubCommands.HUB_STATUS, HubPayloads.hubStatus())) }.getOrNull()
            ?: return (current as? KernelState.Ready)?.hub
        when (val now = _state.value) {
            is KernelState.Ready -> if (!sequenceActive) emit(KernelState.Ready(refreshed, status))
            is KernelState.Draining -> if (!sequenceActive && !status.draining) emit(KernelState.Ready(refreshed, status))
            else -> Unit
        }
        return status
    }

    /**
     * E.7 step 1: enters `Restarting(CheckingSessions)` and asks the Hub who else holds
     * sessions. The caller shows the confirm dialog when [RestartPrecheck.needsConfirm], then
     * calls [restart] or [cancelRestart]. Outside a connected state the check is skipped
     * (nothing to drain) and the result is "no others, not busy".
     */
    suspend fun beginRestart(): RestartPrecheck = mutex.withLock {
        val current = _state.value
        val currentInfo = current.hubInfo ?: info ?: placeholderInfo()
        restartPrevious = current
        restarting = true
        sequenceActive = true
        emit(KernelState.Restarting(currentInfo, RestartPhase.CheckingSessions))
        if (!hubClient.isConnected) return RestartPrecheck(HubSessionActivity.NONE, ownBusy = false, unknown = false)
        val status = runCatching { HubLiveStatus.parse(hubClient.command(HubCommands.HUB_STATUS, HubPayloads.hubStatus())) }.getOrNull()
        val ownBusy = status != null && status.activeRpcTurns + status.pendingRuns > 0
        val others = runCatching {
            HubSessionActivity.summarizeOtherClients(
                hubClient.command(HubCommands.SESSION_LIST, HubPayloads.sessionList(limit = PRECHECK_SESSION_LIMIT, rootOnly = false)),
                hubClient.clientId,
            )
        }.getOrNull()
        if (others == null) RestartPrecheck(HubSessionActivity.UNKNOWN, ownBusy, unknown = true)
        else RestartPrecheck(others, ownBusy, unknown = false)
    }

    /** "Cancel" on the restart confirm: leaves `Restarting` and changes nothing. */
    fun cancelRestart() {
        if (!restarting) return
        restarting = false
        sequenceActive = false
        restartPrevious?.let { emit(it) }
        restartPrevious = null
        rederiveDrain()
    }

    /**
     * E.7 steps 3–6. [forced] = other clients hold sessions and the user confirmed: `hub stop`
     * + `hub start` instead of `hub upgrade --wait 30`, which would refuse after its wait.
     * Also the E.9 path (not connected): no pre-check, no disconnect.
     */
    suspend fun restart(forced: Boolean): RestartOutcome = mutex.withLock {
        val currentInfo = _state.value.hubInfo ?: info ?: placeholderInfo()
        restarting = true
        sequenceActive = true
        restartPrevious = null
        val pairBefore = info?.takeIf { it.pid != null }?.lifetimePair
        try {
            if (hubClient.state.value !is HubConnectionState.Disconnected) {
                emit(KernelState.Restarting(currentInfo, RestartPhase.Disconnecting))
                hubClient.disconnect()
            }
            emit(KernelState.Restarting(currentInfo, RestartPhase.Replacing))
            val startedAt = clock.elapsedMs()
            val deadline = startedAt + timing.startBudgetMs
            val result: KernelCommandResult = if (forced) {
                cmd { commands.hubStop() }
                emit(KernelState.Restarting(currentInfo, RestartPhase.Starting))
                cmd { commands.hubStart(timing.startBudgetMs) }
            } else {
                cmd { commands.hubUpgrade() }
            }
            when (result) {
                is KernelCommandResult.Completed -> when {
                    result.ok -> {
                        if (pollHealth(deadline) == null) return finishRestart(RestartOutcome.Down(emit(startFailed(result, null))))
                        finishRestart(reconnectAfterRestart(currentInfo))
                    }
                    result.diagnosticText.contains(STILL_BOOTING_MARKER) -> {
                        if (pollHealth(deadline) == null) return finishRestart(RestartOutcome.Down(emit(startFailed(result, null))))
                        finishRestart(reconnectAfterRestart(currentInfo))
                    }
                    result.diagnosticText.contains(STILL_SERVING_MARKER) -> {
                        // The CLI un-drained the old Hub: reconnect to it.
                        val outcome = reconnectAfterRestart(currentInfo)
                        finishRestart(if (outcome is RestartOutcome.Restarted) RestartOutcome.StillServing(result.diagnosticText.trim()) else outcome)
                    }
                    else -> {
                        if (prober.health(port) == null) return finishRestart(RestartOutcome.Down(emit(startFailed(result, null))))
                        val outcome = reconnectAfterRestart(currentInfo)
                        finishRestart(if (outcome is RestartOutcome.Restarted) RestartOutcome.Failed(result.lastStderrLine) else outcome)
                    }
                }
                is KernelCommandResult.TimedOut -> {
                    // Re-probe before declaring anything: a new daemon lifetime means the upgrade succeeded.
                    val health = prober.health(port)
                    val version = if (health != null) prober.version(port) else null
                    val pairNow = version?.takeIf { it.pid != null }?.let { "${it.pid}:${it.startedAt}" }
                    when {
                        health != null && pairNow != null && pairNow != pairBefore -> finishRestart(reconnectAfterRestart(currentInfo))
                        health != null -> {
                            val outcome = reconnectAfterRestart(currentInfo)
                            finishRestart(if (outcome is RestartOutcome.Restarted) RestartOutcome.Failed(null) else outcome)
                        }
                        else -> {
                            if (pollHealth(clock.elapsedMs() + timing.restartExtraPollMs) == null) {
                                finishRestart(RestartOutcome.Down(emit(KernelState.HubDown(DownReason.StartFailed, null, null))))
                            } else {
                                finishRestart(reconnectAfterRestart(currentInfo))
                            }
                        }
                    }
                }
                is KernelCommandResult.Unavailable -> finishRestart(RestartOutcome.Down(emit(unavailableState(result))))
            }
        } finally {
            restarting = false
            sequenceActive = false
            rederiveDrain()
        }
    }

    /** The "Keep Termux awake" switch (E.7): persists, then takes or releases the lock while `Ready`. */
    suspend fun setKeepAwake(enabled: Boolean): WakeLockOutcome = mutex.withLock {
        prefs.setKeepAwake(enabled)
        if (!_commandsAvailable.value) return WakeLockOutcome.Failed(null)
        if (enabled) takeWakeLock() else releaseWakeLockIfHeld(force = true)
    }

    /** "Fix processes" (E.6): `cline doctor fix --json`, then `doctor --json`; the Hub is gone afterwards. */
    suspend fun fixProcesses(): DoctorSummary? = mutex.withLock {
        withSequence {
            if (hubClient.state.value !is HubConnectionState.Disconnected) hubClient.disconnect()
            cmd { commands.doctorFix() }
            val doctor = (cmd { commands.doctorJson() } as? KernelCommandResult.Completed)?.let { DoctorSummary.parseStdout(it.stdout) }
            if (doctor == null || !doctor.hubHealthy) {
                // doctor fix removes the discovery record, so this is a graceful stop from Termux's side.
                prefs.setHubLifetimeRelease(null)
                emit(KernelState.HubDown(DownReason.StoppedInTermux, null, doctor))
            }
            doctor
        }
    }

    /** `ON_START` of the process: auto-start now, and on every resume after more than 5 s away (KS §0.4). */
    fun onForeground() {
        val away = backgroundAtMs?.let { clock.elapsedMs() - it }
        foreground = true
        backgroundAtMs = null
        val shouldRun = !everForegrounded || away == null || away > timing.resumeThresholdMs
        everForegrounded = true
        updatePassiveProbe()
        val recheck = pendingRecheck
        pendingRecheck = false
        if (!shouldRun && !recheck) return
        if (_state.value is KernelState.HubDown && (_state.value as KernelState.HubDown).reason == DownReason.Disconnected) return
        // A drop handled in the background without RUN_COMMAND: re-read the token and classify it
        // now (H4, discovery check). Auto-start still follows the resume rule (> 5 s away).
        scope.launch { if (!sequenceActive) ensureConnected(startIfDown = shouldRun) }
    }

    /** `ON_STOP`: the passive probe stops; RUN_COMMAND is never issued from the background. */
    fun onBackground() {
        foreground = false
        backgroundAtMs = clock.elapsedMs()
        updatePassiveProbe()
        scope.launch { runCatching { hubClient.flushCursor() } }
    }

    /**
     * What `HubClient` presents on every (re)connect: the override token, else the sealed
     * token of the current daemon lifetime. Read from the store each time so no plaintext
     * token lives in a field (docs/07 §3.6).
     */
    suspend fun currentCredentials(): HubClient.HubCredentials? {
        val url = info?.url ?: HubEndpoints.socketUrl(port)
        tokenOverride()?.takeIf { it.isNotBlank() }?.let { return HubClient.HubCredentials(url, it) }
        val stored = tokens.read() ?: return null
        if (stored.port != port) return null
        return HubClient.HubCredentials(url, stored.token)
    }

    // ---- the connect sequence (KS §0.4) ----------------------------------------------------

    private suspend fun sequence(startIfDown: Boolean, userInitiated: Boolean): KernelState = withSequence {
        val startedAt = clock.elapsedMs()
        val deadline = startedAt + timing.startBudgetMs

        // Fast path: an intact connection to the same daemon lifetime needs no re-probe.
        val current = _state.value
        if (hubClient.isConnected && (current is KernelState.Ready || current is KernelState.Draining)) {
            val health = prober.health(port)
            val version = if (health != null) prober.version(port) else null
            if (health != null && version?.pid != null && "${version.pid}:${version.startedAt}" == current.hubInfo?.lifetimePair) {
                // Degraded mode (P2) ends here when RUN_COMMAND was re-granted meanwhile, so
                // recheck() lifts DegradedNoCommands without a full sequence.
                if (!_commandsAvailable.value) {
                    val presence = commands.termuxPresence()
                    _commandsAvailable.value = presence is TermuxPresence.Installed && presence.supported && commands.hasRunCommandPermission()
                }
                return@withSequence current
            }
        }

        // Developer override (debug builds, docs/06): a Hub reached through `adb reverse` needs
        // no Termux at all; RUN_COMMAND stays available only when the real prerequisites hold.
        tokenOverride()?.takeIf { it.isNotBlank() }?.let {
            val health = prober.health(port)
            if (health != null) {
                val presence = commands.termuxPresence()
                val degraded = presence !is TermuxPresence.Installed || !presence.supported || !commands.hasRunCommandPermission()
                _commandsAvailable.value = !degraded
                return@withSequence connectToHub(health, startedAt, degraded)
            }
        }

        // P1 Termux present.
        busy(StartStep.CheckingTermux, startedAt)
        when (val presence = commands.termuxPresence()) {
            TermuxPresence.Missing -> return@withSequence emit(KernelState.TermuxMissing(disabled = false))
            TermuxPresence.Disabled -> return@withSequence emit(KernelState.TermuxMissing(disabled = true))
            is TermuxPresence.Installed -> if (!presence.supported) return@withSequence emit(KernelState.TermuxTooOld(presence.versionName ?: presence.versionCode.toString()))
        }

        // P2 permission; degraded mode when the Hub is alive and the cached token still fits (KS §0.4).
        val permitted = commands.hasRunCommandPermission()
        var degraded = false
        if (!permitted) {
            val health = prober.health(port)
            val version = if (health != null) prober.version(port) else null
            val cached = tokens.read()
            val valid = version?.pid != null && cached != null && cached.port == port && cached.lifetimeKey.endsWith(":${version.pid}:${version.startedAt}")
            if (!valid && tokenOverride() == null) return@withSequence emit(KernelState.PermissionMissing)
            if (health == null) return@withSequence emit(KernelState.PermissionMissing)
            degraded = true
        }
        _commandsAvailable.value = !degraded

        // P3 kernel probe.
        if (!degraded) {
            busy(StartStep.CheckingKernel, startedAt)
            when (val probe = cmd { commands.readPortVersion() }) {
                is KernelCommandResult.Completed -> {
                    if (!probe.delivered) return@withSequence emit(classifyInternalError(probe))
                    if (probe.exitCode != 0) return@withSequence emit(KernelState.PortMissing)
                    val file = PortVersion.parse(probe.stdout)
                    _portVersion.value = file
                    // A VERSION without `release=` is not judged here: the start goes ahead and
                    // the daemon-log check decides on the way to StartFailed (KS §0.4, A.7).
                    if (file.release != null && !PortRelease.satisfies(file.release, BuildInfo.MIN_PORT_RELEASE)) {
                        return@withSequence emit(KernelState.PortTooOld(file.release))
                    }
                }
                is KernelCommandResult.TimedOut -> {
                    // Termux did not answer the intent; a Hub that still answers HTTP is usable anyway.
                    if (prober.health(port) == null) return@withSequence emit(KernelState.HubDown(DownReason.StartTimedOut, null, null))
                }
                is KernelCommandResult.Unavailable -> return@withSequence emit(unavailableState(probe))
            }
        }

        // H1 health + protocol.
        busy(StartStep.CheckingKernel, startedAt)
        var health = prober.health(port)
        if (health == null) {
            if (!startIfDown || degraded) return@withSequence emit(classifyDown())
            // H2 start.
            busy(StartStep.StartingHub, startedAt)
            health = startHub(deadline) ?: return@withSequence _state.value
            if (!userInitiated) lastAutoStartReadyAtMs = clock.elapsedMs()
        }
        return@withSequence connectToHub(health, startedAt, degraded)
    }

    /** H1 (protocol) → H3 → H4 → H5 against a Hub that answers `/health`. */
    private suspend fun connectToHub(health: HubHealth, startedAt: Long, degraded: Boolean): KernelState {
        val previousPair = info?.lifetimePair
        val version = prober.version(port)
        var hubInfo = HubInfo(
            url = HubEndpoints.socketUrl(port),
            port = port,
            pid = version?.pid,
            startedAt = version?.startedAt,
            coreVersion = health.coreVersion ?: version?.coreVersion,
            buildId = health.buildId ?: version?.buildId,
            protocolVersion = health.protocolVersion ?: version?.protocolVersion,
            minClientProtocolVersion = health.minClientProtocolVersion ?: version?.minClientProtocolVersion,
            maxClientProtocolVersion = health.maxClientProtocolVersion ?: version?.maxClientProtocolVersion,
            hubId = info?.takeIf { it.lifetimePair == "${version?.pid}:${version?.startedAt}" }?.hubId,
            release = _portVersion.value?.release,
            cliVersion = _portVersion.value?.cline,
        )
        info = hubInfo
        if (!HubProtocol.isCompatible(hubInfo.protocolVersion, hubInfo.minClientProtocolVersion, hubInfo.maxClientProtocolVersion).compatible) {
            return emit(KernelState.ProtocolUnsupported(hubInfo))
        }

        // H3 wake lock (best effort).
        if (!degraded && prefs.keepAwake()) {
            busy(StartStep.KeepingAwake, startedAt)
            takeWakeLock()
        }

        // H4 token.
        busy(StartStep.ReadingCredentials, startedAt)
        var tokenReady = resolveToken(hubInfo, degraded)
        if (tokenReady is TokenResult.Failed) {
            return emit(KernelState.HubDown(DownReason.StartFailed, null, null, failure = StartFailure.CredentialsUnreadable))
        }
        hubInfo = hubInfo.copy(hubId = (tokenReady as? TokenResult.Ready)?.hubId ?: hubInfo.hubId)
        info = hubInfo

        // H5 socket.
        busy(StartStep.Connecting, startedAt)
        emit(KernelState.Connecting(hubInfo))
        // A socket still open to an earlier daemon lifetime is closed first (fixture 04: never two live sockets).
        if (hubClient.isConnected && previousPair != hubInfo.lifetimePair) hubClient.disconnect()
        var outcome = hubClient.connect()
        if (outcome is HubClient.ConnectOutcome.Unauthorized && !degraded) {
            // Stale token: re-read once (KS §0.4 H4/H5).
            tokens.clear()
            tokenReady = resolveToken(hubInfo, degraded = false)
            if (tokenReady is TokenResult.Failed) {
                return emit(KernelState.HubDown(DownReason.StartFailed, null, null, failure = StartFailure.CredentialsUnreadable))
            }
            hubInfo = hubInfo.copy(hubId = (tokenReady as? TokenResult.Ready)?.hubId ?: hubInfo.hubId)
            info = hubInfo
            outcome = hubClient.connect()
        }
        return when (outcome) {
            is HubClient.ConnectOutcome.Connected -> {
                noteConnectedGeneration()
                onReady()
                val status = outcome.hubStatus
                val draining = health.draining || status?.draining == true
                val ready = emit(if (draining) KernelState.Draining(hubInfo) else KernelState.Ready(hubInfo, status))
                reattachSessions()
                ready
            }
            is HubClient.ConnectOutcome.Unauthorized -> emit(KernelState.AuthFailed(hubInfo))
            is HubClient.ConnectOutcome.Failed -> emit(
                KernelState.HubDown(
                    DownReason.StartFailed,
                    outcome.reason.takeLast(TAIL_CHARS),
                    null,
                    failure = if (outcome.code == null) StartFailure.HandshakeRefused else null,
                ),
            )
        }
    }

    /**
     * H2: `cline hub start` within the shared budget. A non-zero exit is "still starting"
     * (the CLI gives up at 15 s while the daemon keeps booting); `/health` is polled until
     * the budget ends. Returns the health reply, or null after emitting the failure state.
     */
    private suspend fun startHub(deadline: Long): HubHealth? {
        val remaining = (deadline - clock.elapsedMs()).coerceAtLeast(timing.healthPollMs)
        when (val start = cmd { commands.hubStart(remaining) }) {
            is KernelCommandResult.Completed -> {
                val text = start.stdout + "\n" + start.stderr + "\n" + (start.errmsg ?: "")
                if (text.contains(ENTRY_JS_MARKER)) {
                    emit(KernelState.PortTooOld(_portVersion.value?.release))
                    return null
                }
                if (!start.delivered) {
                    emit(classifyInternalError(start))
                    return null
                }
                pollHealth(deadline)?.let { return it }
                emit(startFailed(start, _portVersion.value?.release))
                return null
            }
            is KernelCommandResult.TimedOut -> {
                prober.health(port)?.let { return it }
                if (daemonLogShowsOldPort()) {
                    emit(KernelState.PortTooOld(_portVersion.value?.release))
                    return null
                }
                val doctor = (cmd { commands.doctorJson() } as? KernelCommandResult.Completed)?.let { DoctorSummary.parseStdout(it.stdout) }
                emit(KernelState.HubDown(DownReason.StartTimedOut, null, doctor))
                return null
            }
            is KernelCommandResult.Unavailable -> {
                emit(unavailableState(start))
                return null
            }
        }
    }

    /** The way to `StartFailed`: the daemon-log check first (KS §0.4), then the stderr tail as data. */
    private suspend fun startFailed(result: KernelCommandResult.Completed, release: String?): KernelState {
        if (daemonLogShowsOldPort()) return KernelState.PortTooOld(release ?: _portVersion.value?.release)
        val detail = result.diagnosticText.trim().takeLast(TAIL_CHARS).ifBlank { null }
        return KernelState.HubDown(DownReason.StartFailed, detail, null)
    }

    private suspend fun daemonLogShowsOldPort(): Boolean {
        val tail = cmd { commands.tailDaemonLog() } as? KernelCommandResult.Completed ?: return false
        // Matched in memory and discarded: never shown, logged or put into diagnostics.
        return tail.ok && tail.stdout.contains(ENTRY_JS_MARKER)
    }

    private suspend fun pollHealth(deadline: Long): HubHealth? {
        while (true) {
            prober.health(port)?.let { return it }
            if (clock.elapsedMs() >= deadline) return null
            delay(timing.healthPollMs)
        }
    }

    private sealed interface TokenResult {
        data class Ready(val hubId: String?) : TokenResult
        data object Failed : TokenResult
    }

    /** H4: override → cached token for this `(pid, startedAt)` → `production.json` (one retry after 1 s). */
    private suspend fun resolveToken(hubInfo: HubInfo, degraded: Boolean): TokenResult {
        if (tokenOverride()?.isNotBlank() == true) {
            freshLifetime = false
            return TokenResult.Ready(null)
        }
        val cached = tokens.read()
        if (cached != null && cached.port == port && hubInfo.pid != null && cached.lifetimeKey.endsWith(":${hubInfo.pid}:${hubInfo.startedAt}")) {
            lastLifetimeKey = cached.lifetimeKey
            freshLifetime = false
            return TokenResult.Ready(cached.lifetimeKey.substringBefore(":"))
        }
        if (degraded) return TokenResult.Failed
        var record = readDiscovery()
        if (record == null) {
            delay(timing.discoveryRetryMs)
            record = readDiscovery()
        }
        if (record == null) return TokenResult.Failed
        val previousKey = cached?.lifetimeKey ?: lastLifetimeKey
        freshLifetime = previousKey == null || previousKey != record.lifetimeKey
        lastLifetimeKey = record.lifetimeKey
        tokens.write(record)
        return TokenResult.Ready(record.hubId)
    }

    private suspend fun readDiscovery(): HubDiscoveryRecord? {
        val result = cmd { commands.readDiscovery() } as? KernelCommandResult.Completed ?: return null
        if (!result.ok) return null
        // The raw string is parsed and dropped; only the sealed token and the ids survive.
        return runCatching { HubDiscoveryRecord.parse(result.stdout) }.getOrNull()
    }

    private suspend fun reattachSessions() {
        for (sessionId in reattach.openSessions()) {
            try {
                hubClient.command(HubCommands.SESSION_ATTACH, HubPayloads.sessionAttach(sessionId), sessionId)
                reattach.onAttached(sessionId)
            } catch (e: HubCommandException) {
                if (e.isSessionGone()) reattach.onSessionGone(sessionId)
            } catch (_: HubConnectionClosedException) {
                return
            }
        }
    }

    /**
     * `kernel.hubLifetimeRelease` is the release seen when the current daemon lifetime was
     * first connected (KS §0.8): written only for a new lifetime (a token read from a
     * `production.json` whose `hubId:pid:startedAt` differs from the last one) or when
     * nothing is stored yet, so a relaunch after a port upgrade keeps the old value and E.5
     * can offer "Restart to use <release>".
     */
    private suspend fun onReady() {
        sawReady = true
        val release = _portVersion.value?.release
        if (release != null && (freshLifetime || prefs.hubLifetimeRelease() == null)) prefs.setHubLifetimeRelease(release)
        freshLifetime = false
    }

    // ---- down classification (KS §0.3 decision table) ---------------------------------------

    /**
     * [commandsAllowed] false (a drop noticed in the background): the discovery check is skipped
     * and the drop reads as `Asleep` until the next foreground re-check classifies it.
     * [discovered]: the result of a [discoveryCheck] that already ran for this drop.
     */
    private suspend fun classifyDown(commandsAllowed: Boolean = true, discovered: DownReason? = null): KernelState = when {
        crashLoop -> KernelState.HubDown(DownReason.CrashLoop, null, null)
        _userStopped.value -> KernelState.HubDown(DownReason.Stopped, null, null)
        sawReady -> KernelState.HubDown(discovered ?: if (commandsAllowed) discoveryCheck() else DownReason.Asleep, null, null)
        else -> KernelState.HubDown(DownReason.Unreachable, null, null)
    }

    /**
     * Once per drop (KS §0.3 "Discovery check"): `production.json` present → `Asleep` (an OS
     * kill leaves it); only `cat` exiting 1 with "No such file" → `StoppedInTermux` (a graceful
     * stop removed it). Anything else (a RUN_COMMAND internal error, another exit) means Termux
     * itself is unwell or was killed, which reads as `Asleep`.
     */
    private suspend fun discoveryCheck(): DownReason {
        if (!_commandsAvailable.value) return DownReason.Asleep
        return when (val result = cmd { commands.readDiscovery() }) {
            is KernelCommandResult.Completed -> when {
                result.ok -> DownReason.Asleep
                result.delivered && result.exitCode == 1 && result.diagnosticText.contains("No such file", ignoreCase = true) -> DownReason.StoppedInTermux
                else -> DownReason.Asleep
            }
            is KernelCommandResult.TimedOut -> DownReason.Asleep
            is KernelCommandResult.Unavailable -> DownReason.Asleep
        }
    }

    private fun classifyInternalError(result: KernelCommandResult.Completed): KernelState {
        val text = result.errmsg.orEmpty()
        return when {
            text.contains("allow-external-apps", ignoreCase = true) -> KernelState.ExternalAppsBlocked(text)
            text.contains("permission", ignoreCase = true) -> KernelState.PermissionMissing
            text.contains("/bin/cat") || text.contains("not found", ignoreCase = true) || text.contains("No such file", ignoreCase = true) ->
                KernelState.TermuxNotSetUp
            else -> KernelState.HubDown(DownReason.Unreachable, text.takeLast(TAIL_CHARS).ifBlank { null }, null)
        }
    }

    private fun unavailableState(result: KernelCommandResult.Unavailable): KernelState = when (result.reason) {
        KernelCommandResult.Unavailable.Reason.NotInstalled -> KernelState.TermuxMissing(disabled = false)
        KernelCommandResult.Unavailable.Reason.PermissionMissing -> KernelState.PermissionMissing
        KernelCommandResult.Unavailable.Reason.StartFailed -> KernelState.HubDown(DownReason.StartFailed, result.detail, null)
    }

    // ---- reactions to the client ---------------------------------------------------------------

    /** The sequence's own connect re-attached already: the late `Connected` it caused is not a reconnect. */
    private fun noteConnectedGeneration() {
        (hubClient.state.value as? HubConnectionState.Connected)?.let { handledGeneration = it.generation }
    }

    private fun onClientState(connection: HubConnectionState) {
        if (sequenceActive) return
        val current = _state.value
        val currentInfo = current.hubInfo ?: return
        when (connection) {
            is HubConnectionState.Connected -> {
                val previous = handledGeneration
                handledGeneration = connection.generation
                when {
                    current is KernelState.Reconnecting -> {
                        backgroundRetryJob?.cancel()
                        // G5: Ready or Draining as the new connection's `hub.status` says.
                        emit(connectedState(currentInfo))
                        scope.launch { reattachSessions() }
                    }
                    // The client reconnected while this collector was busy: the Reconnecting
                    // value in between was conflated away, but the new registration still needs
                    // its sessions re-attached, and Ready / Draining must come from the new
                    // connection, not survive from the old one (G5: an old Hub that drained,
                    // "retired by newer install", and was replaced).
                    previous != connection.generation && (current is KernelState.Ready || current is KernelState.Draining) -> {
                        emit(connectedState(currentInfo, previous = current))
                        scope.launch { reattachSessions() }
                    }
                }
            }
            is HubConnectionState.Reconnecting -> if (current is KernelState.Ready || current is KernelState.Draining || current is KernelState.Reconnecting) {
                emit(KernelState.Reconnecting(currentInfo, connection.attempt))
                if (current !is KernelState.Reconnecting) startDropCheck()
            }
            is HubConnectionState.Unavailable -> if (current is KernelState.Ready || current is KernelState.Draining || current is KernelState.Reconnecting || current is KernelState.Connecting) {
                scope.launch { handleUnavailable() }
            }
            HubConnectionState.Connecting, HubConnectionState.Disconnected -> Unit
        }
    }

    /**
     * After the client gave up: a Hub that still answers gets a fresh sequence (token rotated); a
     * silent one is classified. RUN_COMMAND runs only in the foreground or from this app's own
     * foreground service (KS §0.4, [serviceHeld]). Otherwise `/health` (plain HTTP on loopback)
     * still tells the two apart: a live Hub of the same daemon lifetime is retried socket-only
     * with the cached token ([scheduleBackgroundRetry]); one that needs a token re-read, or a
     * silent one, is `HubDown` so nothing holds the foreground service meanwhile (CR §12.4), and
     * the next [onForeground] re-checks it ([pendingRecheck]).
     */
    private suspend fun handleUnavailable() = mutex.withLock {
        if (sequenceActive) return@withLock
        val current = _state.value
        if (current !is KernelState.Ready && current !is KernelState.Draining && current !is KernelState.Reconnecting && current !is KernelState.Connecting) return@withLock
        if (foreground || serviceHeld()) {
            if (prober.health(port) != null) {
                sequence(startIfDown = false, userInitiated = false)
            } else {
                noteHubDeath()
                withSequence { emit(classifyDown()) }
            }
            return@withLock
        }
        pendingRecheck = true
        val currentInfo = current.hubInfo ?: return@withLock
        if (prober.health(port) == null) {
            noteHubDeath()
            withSequence { emit(classifyDown(commandsAllowed = false)) }
            return@withLock
        }
        val unauthorized = (hubClient.state.value as? HubConnectionState.Unavailable)?.unauthorized == true
        if (unauthorized || !sameLifetime(currentInfo)) {
            // The daemon restarted (its token rotated): only a `production.json` read can reconnect.
            withSequence { emit(KernelState.HubDown(DownReason.Asleep, null, null)) }
            return@withLock
        }
        withSequence { emit(KernelState.Reconnecting(currentInfo, hubClient.maxReconnectAttempts)) }
        scheduleBackgroundRetry()
    }

    /**
     * SS kernel states ("within 5 s"): an unexpected close is classified at once, beside the
     * client's reconnect attempts, instead of after them. The attempts alone can take far longer
     * than their backoff (a handshake that never answers waits [HubClient.OPEN_TIMEOUT_MS] each),
     * and the discovery check's RUN_COMMAND adds 1-2 s on top. Only in the foreground or with the
     * service held, where RUN_COMMAND is allowed; a background drop keeps [handleUnavailable].
     */
    private fun startDropCheck() {
        if (!(foreground || serviceHeld())) return
        if (dropCheckJob?.isActive == true) return
        dropCheckJob = scope.launch { dropCheck() }
    }

    /**
     * A Hub that keeps answering `/health` is left to the client (a socket blip or a quick
     * restart); one that is stopping answers for a moment after it closed the sockets, so the
     * probe is repeated a few times ([Timing.dropRecheckMs]). A silent one gets its discovery
     * check while the attempts go on; when they still have not reconnected and `/health` is still
     * silent afterwards, the reconnect loop is stopped and the drop is `HubDown`. A Hub that comes
     * back later is picked up by the passive probe.
     */
    private suspend fun dropCheck() {
        var probes = 0
        while (prober.health(port) != null) {
            if (++probes >= timing.dropRecheckLimit) return
            delay(timing.dropRecheckMs)
            if (!stillDropping() || !(foreground || serviceHeld())) return
        }
        mutex.withLock {
            if (sequenceActive || !stillDropping()) return@withLock
            val discovered = if (!crashLoop && !_userStopped.value && sawReady) discoveryCheck() else null
            if (sequenceActive || !stillDropping() || prober.health(port) != null) return@withLock
            noteHubDeath()
            withSequence {
                hubClient.disconnect()
                emit(classifyDown(discovered = discovered))
            }
        }
    }

    /** The coordinator shows the client's reconnect and the client has not reconnected yet. */
    private fun stillDropping(): Boolean =
        _state.value is KernelState.Reconnecting && hubClient.state.value !is HubConnectionState.Connected

    /** `/version` still names the daemon lifetime [hubInfo] connected to, so the cached token fits. */
    private suspend fun sameLifetime(hubInfo: HubInfo): Boolean {
        val version = prober.version(port) ?: return false
        return version.pid != null && hubInfo.pid != null && "${version.pid}:${version.startedAt}" == hubInfo.lifetimePair
    }

    /**
     * Socket-only reconnects with backoff ([Timing.backgroundRetryMs]) while the app stays in the
     * background. Ends as soon as one connects, the foreground or a sequence takes over, the
     * service is held (then a full sequence runs), or the Hub changed; after the last failed
     * attempt the drop reads as `HubDown(Asleep)`.
     */
    private fun scheduleBackgroundRetry() {
        if (backgroundRetryJob?.isActive == true) return
        backgroundRetryJob = scope.launch {
            for (wait in timing.backgroundRetryMs) {
                delay(wait)
                if (backgroundRetryOnce()) return@launch
            }
            mutex.withLock {
                if (!foreground && !sequenceActive && _state.value is KernelState.Reconnecting) {
                    withSequence { emit(KernelState.HubDown(DownReason.Asleep, null, null)) }
                }
            }
        }
    }

    /** One background attempt; true when the retry loop is over (connected, or someone else's turn). */
    private suspend fun backgroundRetryOnce(): Boolean = mutex.withLock {
        if (foreground || sequenceActive) return@withLock true
        val current = _state.value as? KernelState.Reconnecting ?: return@withLock true
        if (serviceHeld()) {
            sequence(startIfDown = false, userInitiated = false)
            if (_state.value is KernelState.Ready || _state.value is KernelState.Draining) pendingRecheck = false
            return@withLock true
        }
        if (prober.health(port) == null) {
            noteHubDeath()
            withSequence { emit(classifyDown(commandsAllowed = false)) }
            return@withLock true
        }
        if (!sameLifetime(current.info)) {
            withSequence { emit(KernelState.HubDown(DownReason.Asleep, null, null)) }
            return@withLock true
        }
        withSequence {
            when (val outcome = hubClient.connect()) {
                is HubClient.ConnectOutcome.Connected -> {
                    noteConnectedGeneration()
                    pendingRecheck = false
                    val draining = outcome.hubStatus?.draining == true
                    emit(if (draining) KernelState.Draining(current.info) else KernelState.Ready(current.info, outcome.hubStatus))
                    reattachSessions()
                    true
                }
                is HubClient.ConnectOutcome.Unauthorized -> {
                    emit(KernelState.HubDown(DownReason.Asleep, null, null))
                    true
                }
                is HubClient.ConnectOutcome.Failed -> false
            }
        }
    }

    /** A sequence owns the state while it runs; [rederiveDrain] picks up a flip it ignored here (G5). */
    private fun onDraining(draining: Boolean) {
        if (sequenceActive) return
        val current = _state.value
        when {
            draining && current is KernelState.Ready -> emit(KernelState.Draining(current.info))
            !draining && current is KernelState.Draining -> emit(KernelState.Ready(current.info, hubClient.hubStatus.value))
        }
    }

    /**
     * G5: Ready or Draining as the current connection says (`HubClient.draining`, which follows
     * this connection's `hub.status` and its live `hub.drain_changed`). [previous] keeps a Ready
     * that already says the same, so its `hub.status` snapshot is not swapped without need.
     */
    private fun connectedState(info: HubInfo, previous: KernelState? = null): KernelState = when {
        hubClient.draining.value -> previous as? KernelState.Draining ?: KernelState.Draining(info)
        else -> previous as? KernelState.Ready ?: KernelState.Ready(info, hubClient.hubStatus.value)
    }

    /**
     * G5: after a sequence ends, Ready / Draining are re-derived from the connection, so a
     * `hub.drain_changed` that [onDraining] ignored while the sequence ran is not lost, and a
     * Draining taken from a `/health` of the Hub that was being replaced gives way to the new
     * connection's `hub.status`.
     */
    private fun rederiveDrain() {
        if (sequenceActive || !hubClient.isConnected) return
        val current = _state.value
        if (current !is KernelState.Ready && current !is KernelState.Draining) return
        emit(connectedState(current.hubInfo ?: return, previous = current))
    }

    /** CrashLoop bookkeeping: two auto-starts within 5 min that each died within 60 s (KS §0.3). */
    private fun noteHubDeath() {
        val startedAt = lastAutoStartReadyAtMs ?: return
        lastAutoStartReadyAtMs = null
        val now = clock.elapsedMs()
        if (now - startedAt > timing.crashWindowMs) return
        earlyDeaths.addLast(now)
        while (earlyDeaths.isNotEmpty() && now - earlyDeaths.first() > timing.crashLoopSpanMs) earlyDeaths.removeFirst()
        if (earlyDeaths.size >= 2) crashLoop = true
    }

    // ---- passive probe ----------------------------------------------------------------------

    private fun updatePassiveProbe() {
        val current = _state.value
        val eligible = foreground && !crashLoop && current is KernelState.HubDown && current.reason in PROBE_REASONS
        if (eligible) {
            if (probeJob?.isActive != true) probeJob = scope.launch { passiveProbeLoop() }
        } else {
            probeJob?.cancel()
            probeJob = null
        }
    }

    private suspend fun passiveProbeLoop() {
        while (currentCoroutineContextActive()) {
            delay(timing.passiveProbeMs)
            if (runCommandInFlight || sequenceActive) continue
            if (prober.health(port) != null) {
                // Connect from a fresh coroutine: the state change below cancels this job.
                scope.launch { ensureConnected(startIfDown = false) }
                return
            }
        }
    }

    private suspend fun currentCoroutineContextActive(): Boolean = kotlinx.coroutines.currentCoroutineContext().isActive

    // ---- wake lock ----------------------------------------------------------------------------

    private suspend fun takeWakeLock(): WakeLockOutcome {
        return when (val result = cmd { commands.wakeLock() }) {
            is KernelCommandResult.Completed -> if (result.ok) {
                prefs.setWakeLockRequested(true)
                _wakeLockError.value = null
                WakeLockOutcome.Applied
            } else {
                val detail = result.diagnosticText.trim().takeLast(TAIL_CHARS).ifBlank { null }
                _wakeLockError.value = detail
                WakeLockOutcome.Failed(detail)
            }
            is KernelCommandResult.TimedOut -> WakeLockOutcome.Failed(null).also { _wakeLockError.value = null }
            is KernelCommandResult.Unavailable -> WakeLockOutcome.Failed(result.detail).also { _wakeLockError.value = result.detail }
        }
    }

    /** `termux-wake-unlock` when this app holds the lock (or [force], after a stop). */
    private suspend fun releaseWakeLockIfHeld(force: Boolean): WakeLockOutcome {
        if (!force && !prefs.wakeLockRequested()) return WakeLockOutcome.Applied
        if (!_commandsAvailable.value) return WakeLockOutcome.Failed(null)
        val result = cmd { commands.wakeUnlock() }
        prefs.setWakeLockRequested(false)
        return when (result) {
            is KernelCommandResult.Completed -> if (result.ok) WakeLockOutcome.Applied else WakeLockOutcome.Failed(result.diagnosticText.trim().takeLast(TAIL_CHARS).ifBlank { null })
            is KernelCommandResult.TimedOut -> WakeLockOutcome.Failed(null)
            is KernelCommandResult.Unavailable -> WakeLockOutcome.Failed(result.detail)
        }
    }

    // ---- restart helpers ----------------------------------------------------------------------

    private suspend fun reconnectAfterRestart(currentInfo: HubInfo): RestartOutcome {
        emit(KernelState.Restarting(currentInfo, RestartPhase.Reconnecting))
        val health = prober.health(port) ?: return RestartOutcome.Down(emit(KernelState.HubDown(DownReason.StartFailed, null, null)))
        tokens.clear()
        val startedAt = clock.elapsedMs()
        return when (val result = connectToHub(health, startedAt, degraded = false)) {
            is KernelState.Ready, is KernelState.Draining -> {
                prefs.setHubLifetimeRelease(_portVersion.value?.release)
                RestartOutcome.Restarted
            }
            else -> RestartOutcome.Down(result)
        }
    }

    private fun finishRestart(outcome: RestartOutcome): RestartOutcome = outcome

    private fun placeholderInfo(): HubInfo = HubInfo(
        url = HubEndpoints.socketUrl(port), port = port, pid = null, startedAt = null,
        coreVersion = null, buildId = null, protocolVersion = null,
    )

    // ---- plumbing -------------------------------------------------------------------------------

    /**
     * Runs [block] with the client-state reflection muted (the sequence sets the states itself);
     * afterwards the drain state is re-derived from the connection (G5).
     */
    private suspend fun <T> withSequence(block: suspend () -> T): T {
        sequenceActive = true
        try {
            return block()
        } finally {
            if (!restarting) {
                sequenceActive = false
                rederiveDrain()
            }
        }
    }

    /** Marks a RUN_COMMAND in flight (the passive probe pauses meanwhile). */
    private suspend fun <T> cmd(block: suspend () -> T): T {
        runCommandInFlight = true
        try {
            return block()
        } finally {
            runCommandInFlight = false
        }
    }

    private fun busy(step: StartStep, startedAt: Long) {
        if (restarting) {
            val currentInfo = _state.value.hubInfo ?: info ?: placeholderInfo()
            emit(KernelState.Restarting(currentInfo, RestartPhase.Reconnecting))
        } else {
            emit(KernelState.Starting(step, startedAt))
        }
    }

    private fun emit(next: KernelState): KernelState {
        val mapped = if (restarting && (next is KernelState.Starting || next is KernelState.Connecting)) {
            KernelState.Restarting(next.hubInfo ?: info ?: placeholderInfo(), RestartPhase.Reconnecting)
        } else {
            next
        }
        val previous = _state.value
        if (previous != mapped) transitions.record(clock.elapsedMs(), previous, mapped)
        _state.value = mapped
        return mapped
    }

    private fun HubInfo.withProbe(health: HubHealth, version: HubVersion?): HubInfo = copy(
        pid = version?.pid ?: pid,
        startedAt = version?.startedAt ?: startedAt,
        coreVersion = health.coreVersion ?: version?.coreVersion ?: coreVersion,
        buildId = health.buildId ?: version?.buildId ?: buildId,
        protocolVersion = health.protocolVersion ?: protocolVersion,
        minClientProtocolVersion = health.minClientProtocolVersion ?: minClientProtocolVersion,
        maxClientProtocolVersion = health.maxClientProtocolVersion ?: maxClientProtocolVersion,
        release = _portVersion.value?.release ?: release,
        cliVersion = _portVersion.value?.cline ?: cliVersion,
    )

    companion object {
        /** `cline hub stop` prints `{"stopped":true|false}` on its last line. */
        fun parseStopped(stdout: String): Boolean? {
            val line = stdout.lineSequence().lastOrNull { it.trim().startsWith("{") } ?: return null
            return runCatching {
                dev.cline.termux.core.kernel.hub.HubJson.parseToJsonElement(line.trim()).jsonObject["stopped"]?.jsonPrimitive?.booleanOrNull
            }.getOrNull()
        }

        const val TAIL_CHARS = 400
        const val PRECHECK_SESSION_LIMIT = 500
        const val ENTRY_JS_MARKER = "entry.js"
        const val STILL_BOOTING_MARKER = "waiting for detached hub startup"
        const val STILL_SERVING_MARKER = "Hub is still serving sessions after the wait window"

        /** `HubDown` reasons the passive `/health` probe keeps watching (KS §0.4). */
        val PROBE_REASONS = setOf(
            DownReason.Asleep, DownReason.StoppedInTermux, DownReason.Stopped, DownReason.Unreachable,
            DownReason.StartFailed, DownReason.StartTimedOut,
        )
    }
}
