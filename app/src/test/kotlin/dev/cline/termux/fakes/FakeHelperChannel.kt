package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.dashboard.HelperUnavailable
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubClientIdentity
import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import dev.cline.termux.data.customize.HelperChannel
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * The settings helper seam for the tier-4 repositories: a settable [HelperState] in front of
 * a [FakeDashboardClient] (canned `desktopCommand` replies, recorded calls).
 */
class FakeHelperChannel(initial: HelperState = HelperState.Ready) : HelperChannel {
    val client = FakeDashboardClient()
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<HelperState> get() = mutableState

    fun unavailable(reason: HelperFailure = HelperFailure.StartTimeout) {
        mutableState.value = HelperState.Unavailable(reason, null)
    }

    override suspend fun desktopCommand(command: String, args: JsonObject, timeoutMs: Long): JsonElement {
        val current = mutableState.value
        if (current !is HelperState.Ready) throw HelperUnavailable.NotRunning((current as? HelperState.Unavailable)?.reason)
        return client.desktopCommand(command, args, timeoutMs)
    }
}

/** A [HubClient] connected to [transport] (a loopback `FakeHubTransport`), its loops on [scope]. */
suspend fun connectedHub(
    transport: FakeHubTransport,
    scope: CoroutineScope,
    replies: (HubCommandEnvelope) -> HubReplyEnvelope? = { null },
): HubClient {
    transport.onCommand = { env -> replies(env) ?: transport.defaultReply(env) }
    val client = HubClient(
        transport = transport,
        identity = HubClientIdentity(clientId = "android-test", appVersion = "0.1.0", workspaceRoot = "/w"),
        scope = scope,
        cursors = InMemoryCursorStore(),
        credentials = { HubClient.HubCredentials("ws://127.0.0.1:25463/hub", "tok") },
        random = Random(1),
    )
    client.connect()
    return client
}

/** `ok:true` with [payload]. */
fun HubCommandEnvelope.ok(payload: JsonObject): HubReplyEnvelope = HubReplyEnvelope(requestId = requestId, ok = true, payload = payload)

/** `ok:false` with [code] / [message]. */
fun HubCommandEnvelope.fail(code: String, message: String = code): HubReplyEnvelope = FakeHubTransport.errorReply(this, code, message)
