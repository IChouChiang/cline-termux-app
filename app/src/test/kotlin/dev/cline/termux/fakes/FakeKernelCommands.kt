package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.HubTokenCache
import dev.cline.termux.core.kernel.KernelClock
import dev.cline.termux.core.kernel.KernelCommandResult
import dev.cline.termux.core.kernel.KernelCommands
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.hub.HubDiscoveryRecord
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.hub.HubVersion
import dev.cline.termux.core.kernel.hub.StoredHubToken
import kotlinx.coroutines.test.TestCoroutineScheduler

const val FAKE_HUB_TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

/**
 * The Hub daemon as Termux and the loopback see it: one lifetime (`pid`, `startedAt`,
 * `hubId`, token) that [FakeKernelCommands] starts/stops and [FakeHubProber] answers for.
 */
class FakeHubHost {
    var running = false
    var pid = 4242
    var startedAt = "2026-10-03T21:00:00.000Z"
    var hubId = "hub_1"
    var token = FAKE_HUB_TOKEN
    var port = 25463
    var draining = false
    var protocolVersion = "v1"
    var minClientProtocolVersion: String? = "v1"
    var maxClientProtocolVersion: String? = "v1"
    var coreVersion = "3.0.68"
    var buildId = "b1"
    var discoveryPresent: Boolean? = null

    /** A new daemon lifetime: new pid, startedAt and token, like `cline hub start` after a stop. */
    fun restart() {
        pid += 1
        startedAt = "2026-10-03T22:0${pid % 10}:00.000Z"
        token = token.reversed()
        hubId = "hub_${pid}"
        running = true
    }

    fun discoveryJson(): String = """{"hubId":"$hubId","protocolVersion":"$protocolVersion","authToken":"$token","host":"127.0.0.1",
        "port":$port,"url":"ws://127.0.0.1:$port/hub","pid":$pid,"startedAt":"$startedAt","updatedAt":"$startedAt","coreVersion":"$coreVersion","buildId":"$buildId"}"""
}

class FakeHubProber(private val host: FakeHubHost) : HubProber {
    var healthProbes = 0

    /** Round trip of `GET /health` (a busy loopback); a sequence holds the state meanwhile. */
    var healthLatencyMs: Long = 0L
    override suspend fun health(port: Int): HubHealth? {
        healthProbes++
        if (healthLatencyMs > 0) kotlinx.coroutines.delay(healthLatencyMs)
        if (!host.running || port != host.port) return null
        return HubHealth(
            ok = true,
            protocolVersion = host.protocolVersion,
            minClientProtocolVersion = host.minClientProtocolVersion,
            maxClientProtocolVersion = host.maxClientProtocolVersion,
            coreVersion = host.coreVersion,
            buildId = host.buildId,
            draining = host.draining,
            port = port,
        )
    }

    override suspend fun version(port: Int): HubVersion? {
        if (!host.running || port != host.port) return null
        return HubVersion(
            protocolVersion = host.protocolVersion,
            minClientProtocolVersion = host.minClientProtocolVersion,
            maxClientProtocolVersion = host.maxClientProtocolVersion,
            coreVersion = host.coreVersion,
            buildId = host.buildId,
            pid = host.pid,
            startedAt = host.startedAt,
        )
    }
}

class FakeTokenCache : HubTokenCache {
    var stored: StoredHubToken? = null
    var writes = 0
    var clears = 0
    override suspend fun read() = stored
    override suspend fun write(record: HubDiscoveryRecord) {
        writes++
        stored = StoredHubToken(record.authToken, record.lifetimeKey, record.port)
    }
    override suspend fun clear() {
        clears++
        stored = null
    }
}

/** A [KernelClock] that follows the test scheduler's virtual time. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TestKernelClock(private val scheduler: TestCoroutineScheduler, private val wallBase: Long = 1_760_000_000_000L) : KernelClock {
    override fun elapsedMs(): Long = scheduler.currentTime
    override fun wallMs(): Long = wallBase + scheduler.currentTime
}

/**
 * Scripted RUN_COMMAND surface (docs/07 §10.1). Every call is recorded in [calls]; results
 * are plain values a test overrides per scenario. `hubStart` flips [host] to running unless
 * [hubStartResult] is set.
 */
class FakeKernelCommands(val host: FakeHubHost = FakeHubHost()) : KernelCommands {
    val calls = mutableListOf<String>()

    var presence: TermuxPresence = TermuxPresence.Installed(118, "0.118.3")
    var permitted = true
    var portVersionResult: KernelCommandResult = ok("release=3.0.68-termux.2\ntag=v3.0.68-termux.2\ncline=3.0.68\n")

    /** When set, `hub start` returns this instead of starting the host. */
    var hubStartResult: KernelCommandResult? = null

    /** When set, `hub start` returns this (e.g. the CLI's 15 s give-up) but the daemon still comes up. */
    var hubStartDetached: KernelCommandResult? = null
    var hubStopResult: KernelCommandResult? = null
    var hubUpgradeResult: KernelCommandResult? = null
    var discoveryResult: KernelCommandResult? = null

    /** RUN_COMMAND round trip of [readDiscovery] (Termux answers in about 1-2 s on the S25U). */
    var discoveryLatencyMs: Long = 0L
    var daemonLog: String = ""
    var doctorJsonResult: KernelCommandResult = ok("""{"cliVersion":"3.0.68","coreVersion":"3.0.68","hubHealthy":true,"hubPid":4242,"hubUptime":"4m 12s","listeningPids":[4242],"staleHubPids":[],"hubStartupLocks":[],"staleCliPids":[]}""")
    var doctorFixResult: KernelCommandResult = ok("")
    var wakeLockResult: KernelCommandResult = ok("")
    var wakeUnlockResult: KernelCommandResult = ok("")

    /**
     * The release tree the daemon runs from, as its argv names it ([readHubCmdline]). `hub start`
     * and `hub upgrade` set it to the installed release, as a daemon started now runs that tree.
     */
    var daemonRelease: String? = "3.0.68-termux.2"

    /** When set, [readHubCmdline] returns this instead of the argv of [daemonRelease]. */
    var hubCmdlineResult: KernelCommandResult? = null

    override fun termuxPresence(): TermuxPresence = presence.also { calls += "termuxPresence" }
    override fun hasRunCommandPermission(): Boolean = permitted.also { calls += "hasRunCommandPermission" }
    override suspend fun readPortVersion(): KernelCommandResult = portVersionResult.also { calls += "readPortVersion" }

    override suspend fun hubStart(timeoutMs: Long): KernelCommandResult {
        calls += "hubStart"
        hubStartResult?.let { return it }
        daemonRelease = installedRelease() ?: daemonRelease
        hubStartDetached?.let {
            host.running = true
            return it
        }
        host.running = true
        return ok("ws://127.0.0.1:${host.port}/hub\n")
    }

    override suspend fun hubStatus(): KernelCommandResult = ok("""{"running":${host.running}}""").also { calls += "hubStatus" }

    override suspend fun hubStop(): KernelCommandResult {
        calls += "hubStop"
        hubStopResult?.let { return it }
        host.running = false
        return ok("""{"stopped":true}""" + "\n")
    }

    override suspend fun hubUpgrade(): KernelCommandResult {
        calls += "hubUpgrade"
        hubUpgradeResult?.let { return it }
        host.restart()
        daemonRelease = installedRelease() ?: daemonRelease
        return ok("hub upgraded\n")
    }

    override suspend fun readHubCmdline(pid: Int): KernelCommandResult {
        calls += "readHubCmdline"
        hubCmdlineResult?.let { return it }
        val tree = daemonRelease ?: "current"
        return ok("$PREFIX/opt/bun-android-ffi/1.4.0-canary.1/bun\n$PREFIX/opt/cline-termux/$tree/entry.js\n--cwd\n/data/data/com.termux/files/home\n--port\n${host.port}\n")
    }

    private fun installedRelease(): String? = (portVersionResult as? KernelCommandResult.Completed)?.let { PortVersion.parse(it.stdout).release }

    override suspend fun readDiscovery(): KernelCommandResult {
        calls += "readDiscovery"
        if (discoveryLatencyMs > 0) kotlinx.coroutines.delay(discoveryLatencyMs)
        discoveryResult?.let { return it }
        val present = host.discoveryPresent ?: host.running
        return if (present) ok(host.discoveryJson()) else fail(1, "cat: ${'$'}HOME/.cline/data/locks/hub/production.json: No such file or directory")
    }

    override suspend fun tailDaemonLog(): KernelCommandResult = ok(daemonLog).also { calls += "tailDaemonLog" }
    override suspend fun doctorJson(): KernelCommandResult = doctorJsonResult.also { calls += "doctorJson" }
    override suspend fun doctorFix(): KernelCommandResult = doctorFixResult.also { calls += "doctorFix" }
    override suspend fun wakeLock(): KernelCommandResult = wakeLockResult.also { calls += "wakeLock" }
    override suspend fun wakeUnlock(): KernelCommandResult = wakeUnlockResult.also { calls += "wakeUnlock" }

    companion object {
        private const val PREFIX = "/data/data/com.termux/files/usr"

        fun ok(stdout: String): KernelCommandResult.Completed = KernelCommandResult.Completed(stdout, "", 0)
        fun fail(exitCode: Int, stderr: String): KernelCommandResult.Completed = KernelCommandResult.Completed("", stderr, exitCode)
        fun internal(errmsg: String, err: Int = 1): KernelCommandResult.Completed = KernelCommandResult.Completed("", "", 1, err, errmsg)
    }
}
