package dev.cline.termux.core.kernel.dashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The browser frames the settings helper uses (SP §0.3 step 2; `apps/cline-hub/src/webview-protocol.ts`
 * `WebviewInboundMessage` / `WebviewOutboundMessage`): `ready` and `desktopCommand` out,
 * `desktopCommandResult` in. Every other inbound `type` (`status`, `defaults`, `providers`,
 * `models`, `provider_catalog`, `sessions`, the 5 s hub-state broadcast…) is ignored without
 * parsing its body: the unsolicited `provider_catalog` carries plaintext keys.
 */
@Serializable
data class DesktopCommandFrame(
    val type: String = TYPE,
    val id: String,
    val command: String,
    val args: JsonObject? = null,
) {
    companion object { const val TYPE = "desktopCommand" }
}

@Serializable
data class ReadyFrame(val type: String = TYPE) {
    companion object { const val TYPE = "ready" }
}

/** What the lenient decoder hands the client. */
sealed interface HelperInbound {
    data class CommandResult(val id: String, val ok: Boolean, val result: JsonElement?, val error: String?) : HelperInbound

    /** Any other, unknown or malformed frame; never closes the socket. */
    data object Ignored : HelperInbound
}

object HelperFrames {
    const val RESULT_TYPE = "desktopCommandResult"

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        encodeDefaults = true
    }

    private val TYPE_FIELD = Regex(""""type"\s*:\s*"([^"\\]*)"""")

    fun encodeReady(): String = json.encodeToString(ReadyFrame.serializer(), ReadyFrame())

    fun encodeCommand(id: String, command: String, args: JsonObject?): String =
        json.encodeToString(DesktopCommandFrame.serializer(), DesktopCommandFrame(id = id, command = command, args = args))

    /**
     * Reads `type` first (a text scan, no object is built for foreign frames) and parses
     * the body only for [RESULT_TYPE]. Malformed input is [HelperInbound.Ignored].
     */
    fun decode(text: String): HelperInbound {
        if (peekType(text) != RESULT_TYPE) return HelperInbound.Ignored
        val root = runCatching { json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return HelperInbound.Ignored
        if ((root["type"] as? JsonPrimitive)?.contentOrNull != RESULT_TYPE) return HelperInbound.Ignored
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull ?: return HelperInbound.Ignored
        val ok = (root["ok"] as? JsonPrimitive)?.booleanOrNull ?: return HelperInbound.Ignored
        val result = root["result"]?.takeUnless { it is JsonNull }
        val error = (root["error"] as? JsonPrimitive)?.contentOrNull
        return HelperInbound.CommandResult(id = id, ok = ok, result = result, error = error)
    }

    /** The first `"type": "…"` in the text; the server writes it as the first key. */
    internal fun peekType(text: String): String? = TYPE_FIELD.find(text)?.groupValues?.get(1)
}
