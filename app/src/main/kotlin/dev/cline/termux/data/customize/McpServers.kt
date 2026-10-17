package dev.cline.termux.data.customize

import dev.cline.termux.data.tools.jsBoolean
import dev.cline.termux.data.tools.jsString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * MCP server shapes (customize-and-rest.md §1.8, §2.8): the dashboard's `McpServersResponse`
 * (`apps/cline-hub/src/server/mcp.ts` `readMcpServersResponse`), the same rules applied to the
 * settings file read over RUN_COMMAND, and the desktop editor's `buildServerInput`
 * (`mcp-view.tsx`). `env` and `headers` values live in these objects in memory only; they are
 * never persisted, logged or put in `SavedStateHandle` (SP §0.6).
 */

/** Wire `transportType`: `stdio`, `streamableHttp` (also `http`), `sse`. */
enum class McpTransportType(val wire: String) {
    Stdio("stdio"), StreamableHttp("streamableHttp"), Sse("sse");

    val isRemote: Boolean get() = this != Stdio

    companion object {
        fun parse(value: String?): McpTransportType = when (value?.trim()) {
            "sse" -> Sse
            "streamableHttp", "streamable-http", "http", "streamable_http" -> StreamableHttp
            else -> Stdio
        }
    }
}

data class McpServer(
    val name: String,
    val transportType: McpTransportType,
    /** The raw wire value, shown on the card's transport badge. */
    val transportLabel: String,
    val disabled: Boolean,
    val command: String? = null,
    val args: List<String>? = null,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val metadata: JsonElement? = null,
) {
    /** The fallback route cannot carry these (CR §2.8: the editor refuses to edit such a server). */
    val hasEnvOrCwd: Boolean get() = !env.isNullOrEmpty() || !cwd.isNullOrBlank()

    /** Never prints env or header values. */
    override fun toString(): String =
        "McpServer(name=$name, transport=$transportLabel, disabled=$disabled, env=${env?.keys}, headers=${headers?.keys})"
}

/** Where the MCP list came from (CR §1.8 `McpSource`). */
enum class McpSource { Helper, File }

data class McpServersResponse(
    val settingsPath: String?,
    val hasSettingsFile: Boolean,
    val servers: List<McpServer>,
    val source: McpSource,
) {
    /** Sorted case-insensitively (CR §1.8). */
    val sorted: List<McpServer> get() = servers.sortedBy { it.name.lowercase() }

    fun find(name: String): McpServer? = servers.firstOrNull { it.name == name }

    companion object {
        /** The dashboard reply `{settingsPath, hasSettingsFile, servers[…]}`. */
        fun fromHelper(element: JsonElement?): McpServersResponse {
            val o = element as? JsonObject ?: throw IllegalArgumentException("list_mcp_servers reply is not an object")
            val servers = (o["servers"] as? JsonArray).orEmpty().mapNotNull { server ->
                val s = server as? JsonObject ?: return@mapNotNull null
                val name = s["name"].jsString() ?: return@mapNotNull null
                serverFrom(name, s, transport = null)
            }
            return McpServersResponse(
                settingsPath = o["settingsPath"].jsString(),
                hasSettingsFile = o["hasSettingsFile"].jsBoolean() ?: servers.isNotEmpty(),
                servers = servers,
                source = McpSource.Helper,
            )
        }

        /**
         * `cline_mcp_settings.json` parsed like `readMcpServersResponse`: `transport.*` first,
         * then the top-level keys; [text] null = the file does not exist.
         */
        fun fromSettingsFile(text: String?, settingsPath: String): McpServersResponse {
            if (text == null) return McpServersResponse(settingsPath, hasSettingsFile = false, servers = emptyList(), source = McpSource.File)
            val root = CustomizeJson.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("MCP settings file is not a JSON object")
            val servers = (root["mcpServers"] as? JsonObject).orEmpty().mapNotNull { (name, body) ->
                val record = body as? JsonObject ?: return@mapNotNull null
                serverFrom(name, record, transport = record["transport"] as? JsonObject)
            }
            return McpServersResponse(settingsPath, hasSettingsFile = true, servers = servers, source = McpSource.File)
        }

        private fun serverFrom(name: String, record: JsonObject, transport: JsonObject?): McpServer {
            fun pick(key: String): JsonElement? = transport?.get(key)?.takeIf { it !is kotlinx.serialization.json.JsonNull } ?: record[key]
            val rawType = (transport?.get("type").jsString() ?: record["transportType"].jsString() ?: record["type"].jsString() ?: "stdio").trim()
            return McpServer(
                name = name,
                transportType = McpTransportType.parse(rawType),
                transportLabel = rawType,
                disabled = record["disabled"].jsBoolean() == true,
                command = pick("command").jsString(),
                args = (pick("args") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content },
                cwd = pick("cwd").jsString(),
                env = stringMap(pick("env")),
                url = pick("url").jsString(),
                headers = stringMap(pick("headers")),
                metadata = record["metadata"],
            )
        }

        private fun stringMap(element: JsonElement?): Map<String, String>? =
            (element as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap()
    }
}

/** The desktop `McpServerUpsertInput`, as `upsert_mcp_server {input}` takes it. */
data class McpServerInput(
    val name: String,
    val previousName: String?,
    val transportType: McpTransportType,
    val command: String? = null,
    val args: List<String>? = null,
    val cwd: String? = null,
    val env: Map<String, String>? = null,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val disabled: Boolean = false,
    val metadata: JsonElement? = null,
) {
    val isRename: Boolean get() = previousName != null && previousName != name

    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        previousName?.let { put("previousName", it) }
        put("transportType", transportType.wire)
        if (transportType == McpTransportType.Stdio) {
            command?.let { put("command", it) }
            args?.let { list -> put("args", buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }) }
            cwd?.let { put("cwd", it) }
            env?.let { map -> put("env", buildJsonObject { map.forEach { (k, v) -> put(k, v) } }) }
        } else {
            url?.let { put("url", it) }
            headers?.let { map -> put("headers", buildJsonObject { map.forEach { (k, v) -> put(k, v) } }) }
        }
        put("disabled", disabled)
        metadata?.let { put("metadata", it) }
    }

    /** True when [server] (re-read after an unknown outcome) carries this input (CR §2.9). */
    fun matches(server: McpServer): Boolean =
        server.name == name &&
            server.disabled == disabled &&
            (if (transportType == McpTransportType.Stdio) {
                server.transportType == McpTransportType.Stdio && server.command == command && server.args.orEmpty() == args.orEmpty()
            } else {
                server.transportType == transportType && server.url == url
            })

    override fun toString(): String = "McpServerInput(name=$name, transport=${transportType.wire}, env=${env?.keys}, headers=${headers?.keys})"
}

/** The editor form fields `buildServerInput` reads (CR §2.8). */
data class McpServerForm(
    val name: String,
    val previousName: String,
    val transportType: McpTransportType,
    val command: String = "",
    val argsText: String = "",
    val cwd: String = "",
    val env: List<Pair<String, String>> = emptyList(),
    val url: String = "",
    val headersText: String = "",
    val enabled: Boolean = true,
    val metadata: JsonElement? = null,
)

/** `buildServerInput` validation failures; the desktop messages, for the UI's error box. */
enum class McpInputError(val desktopMessage: String) {
    NameRequired("Server name is required."),
    CommandRequired("Command is required for local servers."),
    UrlRequired("Server URL is required for remote servers."),
}

object McpInputs {
    /** Desktop `splitCsv`: split on ",", trim, drop empties. */
    fun splitCsv(text: String): List<String> = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** Desktop `parseKeyValuePairs`: `k=v` pairs split at the first "="; pairs without a key are skipped. */
    fun parseKeyValuePairs(text: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (pair in splitCsv(text)) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val key = pair.substring(0, idx).trim()
            if (key.isEmpty()) continue
            out[key] = pair.substring(idx + 1).trim()
        }
        return out.ifEmpty { null }
    }

    /** Desktop `stringifyKeyValuePairs`. */
    fun stringifyKeyValuePairs(input: Map<String, String>?): String = input.orEmpty().entries.joinToString(", ") { "${it.key}=${it.value}" }

    /** Desktop `stringifyRedactedKeyValuePairs`. */
    fun stringifyRedactedKeyValuePairs(input: Map<String, String>?): String = input.orEmpty().keys.joinToString(", ") { "$it=[REDACTED]" }

    /** Desktop `buildServerInput`. */
    fun buildServerInput(form: McpServerForm): Result<McpServerInput> {
        val name = form.name.trim()
        if (name.isEmpty()) return Result.failure(McpInputException(McpInputError.NameRequired))
        val previousName = form.previousName.trim().ifEmpty { null }
        if (form.transportType == McpTransportType.Stdio) {
            val command = form.command.trim()
            if (command.isEmpty()) return Result.failure(McpInputException(McpInputError.CommandRequired))
            val env = LinkedHashMap<String, String>()
            form.env.forEach { (key, value) -> key.trim().takeIf { it.isNotEmpty() }?.let { env[it] = value } }
            return Result.success(
                McpServerInput(
                    name = name,
                    previousName = previousName,
                    transportType = McpTransportType.Stdio,
                    command = command,
                    args = splitCsv(form.argsText).ifEmpty { null },
                    cwd = form.cwd.trim().ifEmpty { null },
                    env = env.ifEmpty { null },
                    disabled = !form.enabled,
                    metadata = form.metadata,
                ),
            )
        }
        val url = form.url.trim()
        if (url.isEmpty()) return Result.failure(McpInputException(McpInputError.UrlRequired))
        return Result.success(
            McpServerInput(
                name = name,
                previousName = previousName,
                transportType = form.transportType,
                url = url,
                headers = parseKeyValuePairs(form.headersText),
                disabled = !form.enabled,
                metadata = form.metadata,
            ),
        )
    }
}

class McpInputException(val error: McpInputError) : Exception(error.desktopMessage)
