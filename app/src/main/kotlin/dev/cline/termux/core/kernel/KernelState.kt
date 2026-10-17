package dev.cline.termux.core.kernel

import dev.cline.termux.core.kernel.hub.HubJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The app's only kernel state model (onboarding-kernel.md §0.3, docs/07 §3.2).
 * `KernelCoordinator.state` is the single source; screens render [KernelGate] (its pure
 * projection) and only the kernel group's own ViewModels switch on these states directly.
 */
sealed interface KernelState {
    /** Before the first probe. */
    data object Unknown : KernelState

    /** `<queries>` lookup failed, or `ApplicationInfo.enabled == false`. */
    data class TermuxMissing(val disabled: Boolean) : KernelState

    /** Termux `versionName` below 0.109. */
    data class TermuxTooOld(val versionName: String) : KernelState

    /** `com.termux.permission.RUN_COMMAND` not granted. */
    data object PermissionMissing : KernelState

    /** RUN_COMMAND `err != -1` with an `errmsg` naming `allow-external-apps`. */
    data class ExternalAppsBlocked(val errmsg: String) : KernelState

    /** `$PREFIX/bin/cat` itself is missing: the Termux bootstrap never ran. */
    data object TermuxNotSetUp : KernelState

    /** The port's `VERSION` file is absent. */
    data object PortMissing : KernelState

    /** `release < MIN_PORT_RELEASE`, or the daemon-log check found the `entry.js` failure. */
    data class PortTooOld(val release: String?) : KernelState

    data class Starting(val step: StartStep, val startedAtElapsedMs: Long) : KernelState

    /** Socket opening + `client.register`. */
    data class Connecting(val info: HubInfo) : KernelState

    data class Ready(val info: HubInfo, val hub: HubLiveStatus?) : KernelState

    data class Reconnecting(val info: HubInfo, val attempt: Int) : KernelState

    /** An intended restart (E.7): never an outage while this is the state. */
    data class Restarting(val info: HubInfo, val phase: RestartPhase) : KernelState

    /** `/health.draining` or `hub.drain_changed {draining:true}`. */
    data class Draining(val info: HubInfo) : KernelState

    /** `isHubProtocolCompatible(...)` said no. */
    data class ProtocolUnsupported(val info: HubInfo) : KernelState

    /** HTTP 401 on the upgrade after one token re-read. */
    data class AuthFailed(val info: HubInfo) : KernelState

    /**
     * The Hub is not usable. [lastError] is kernel text (stderr tail, close reason) shown as
     * data; [failure] names a failure the app itself detected, for which the UI owns the copy
     * (KS §0.4 H4/H5 sentences) because no kernel text exists.
     */
    data class HubDown(
        val reason: DownReason,
        val lastError: String?,
        val doctor: DoctorSummary?,
        val failure: StartFailure? = null,
    ) : KernelState

    /** The Hub info this state carries, when it has one (every state from `Connecting` on). */
    val hubInfo: HubInfo?
        get() = when (this) {
            is Connecting -> info
            is Ready -> info
            is Reconnecting -> info
            is Restarting -> info
            is Draining -> info
            is ProtocolUnsupported -> info
            is AuthFailed -> info
            else -> null
        }
}

enum class StartStep { CheckingTermux, CheckingKernel, StartingHub, KeepingAwake, ReadingCredentials, Connecting }

enum class RestartPhase { CheckingSessions, Disconnecting, Replacing, Starting, Reconnecting }

enum class DownReason { Asleep, Stopped, StoppedInTermux, Disconnected, StartFailed, StartTimedOut, Unreachable, CrashLoop }

/** App-detected start failures without kernel text (the UI maps each to its KS §0.4 sentence). */
enum class StartFailure {
    /** H4: the Hub started but `production.json` could not be read or parsed. */
    CredentialsUnreadable,

    /** H5: the WebSocket handshake failed below the protocol (no subprotocol echo, refused upgrade). */
    HandshakeRefused,
}

data class HubInfo(
    val url: String,
    val port: Int,
    val pid: Int?,
    val startedAt: String?,
    val coreVersion: String?,
    val buildId: String?,
    val protocolVersion: String?,
    val minClientProtocolVersion: String? = null,
    val maxClientProtocolVersion: String? = null,
    /** From `production.json` only (`/version` has none); shown under "More details". */
    val hubId: String? = null,
    /** `release=` of the port's VERSION file, e.g. `3.0.68-termux.2`. */
    val release: String? = null,
    /** `cline=` of the port's VERSION file, e.g. `3.0.68`. */
    val cliVersion: String? = null,
) {
    /** One daemon lifetime (docs/08: pid, startedAt, hubId and the token rotate together). */
    val lifetimePair: String get() = "$pid:$startedAt"
}

/** The `hub.status {}` reply. */
data class HubLiveStatus(
    val draining: Boolean,
    val activeRpcTurns: Int,
    val pendingRuns: Int,
    val lastSequence: Long,
    val idle: Boolean,
    val hubId: String? = null,
) {
    companion object {
        fun parse(payload: JsonObject): HubLiveStatus = HubLiveStatus(
            draining = payload["draining"]?.jsonPrimitive?.booleanOrNull ?: false,
            activeRpcTurns = payload["activeRpcTurns"]?.jsonPrimitive?.intOrNull ?: 0,
            pendingRuns = payload["pendingRuns"]?.jsonPrimitive?.intOrNull ?: 0,
            lastSequence = (payload["eventLog"] as? JsonObject)?.get("lastSequence")?.jsonPrimitive?.longOrNull ?: 0L,
            idle = payload["idle"]?.jsonPrimitive?.booleanOrNull ?: true,
            hubId = payload["hubId"]?.jsonPrimitive?.contentOrNull,
        )
    }
}

/**
 * `cline doctor --json` (`apps/cli/src/commands/doctor.ts` `DoctorStatus`), reduced to the
 * rows the Kernel status sheet shows (onboarding-kernel.md E.6). Contains no secrets.
 */
data class DoctorSummary(
    val cliVersion: String?,
    val coreVersion: String?,
    val hubHealthy: Boolean,
    val hubPid: Int?,
    val hubUptime: String?,
    val listeningPids: List<Int>,
    val staleHubPids: List<Int>,
    val hubStartupLocks: Int,
    val staleCliPids: List<Int>,
) {
    companion object {
        fun parse(json: JsonObject): DoctorSummary = DoctorSummary(
            cliVersion = json["cliVersion"]?.jsonPrimitive?.contentOrNull,
            coreVersion = json["coreVersion"]?.jsonPrimitive?.contentOrNull,
            hubHealthy = json["hubHealthy"]?.jsonPrimitive?.booleanOrNull ?: false,
            hubPid = json["hubPid"]?.jsonPrimitive?.intOrNull,
            hubUptime = json["hubUptime"]?.jsonPrimitive?.contentOrNull,
            listeningPids = json["listeningPids"].pids(),
            staleHubPids = json["staleHubPids"].pids(),
            hubStartupLocks = (json["hubStartupLocks"] as? JsonArray)?.size ?: 0,
            staleCliPids = json["staleCliPids"].pids(),
        )

        /** The last JSON object on stdout (the CLI may print progress lines first). */
        fun parseStdout(stdout: String): DoctorSummary? {
            val line = stdout.lineSequence().lastOrNull { it.trim().startsWith("{") } ?: return null
            val obj = runCatching { HubJson.parseToJsonElement(line.trim()).jsonObject }.getOrNull() ?: return null
            return parse(obj)
        }

        private fun JsonElement?.pids(): List<Int> =
            (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
    }
}
