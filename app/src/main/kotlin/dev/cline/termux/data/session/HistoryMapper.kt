package dev.cline.termux.data.session

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.GeneratedMedia
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.REASON_CREDENTIALS
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.UNDATED
import dev.cline.termux.data.chat.looksCredentialRelated
import dev.cline.termux.data.checkpoint.RunMessage
import dev.cline.termux.data.checkpoint.getUserRunSpan
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A mapped `session.messages` reply: rows plus the raw message index each row came from. */
@Immutable
data class HistoryMapping(
    val messages: List<ChatMessage>,
    /** `rawIndex[i]` = index in the reply's `messages[]` of `messages[i]` (for the §1.3.3 turn slice). */
    val rawIndex: List<Int>,
    /** The reply's raw message count (`SessionState.canonicalCount`). */
    val rawCount: Int,
)

/**
 * Pure mapper, `session.messages {sessionId}` → canonical `ChatMessage` rows (home-chat.md
 * §1.3.2). Message shape (docs/08): `{id, role:"user"|"assistant", content: Block[], ts,
 * modelInfo?, metrics?}`.
 *
 * - user `text` → one `User` row per message (id = the daemon's message id), `content` raw;
 *   the UI shows `UserInputFormat.formatDisplayUserInput(content)`. A `[SYSTEM]` text that is
 *   not wrapped in `<user_input>` is a steering note: `userRunSpan = 0`, hidden by grouping.
 * - user `image` → that row's images; user `tool_result` → merged into the `t:<tool_use_id>`
 *   row as `result` / `isError` (a JSON string content is parsed).
 * - assistant `text` → `h:<msgId>:<blockIndex>`; `thinking` / `redacted_thinking` attach to
 *   the next text of the message, or become a reasoning-only row before a `tool_use`.
 * - assistant `tool_use` → `t:<id>` with `hookEventName = "history_tool_use"` until its result merges.
 * - `media` → media of the message's assistant row; `file` is not rendered.
 * - Run counts are the kernel's (`getUserRunSpan` over the raw messages), stamped as the desktop
 *   sidecar does (`session-data/messages.ts`): every row gets `runCount` = the absolute run
 *   count at its message once it is above 0, and the rows of a user-role message whose span is
 *   not 1 carry `userRunSpan` (a compaction summary shown as a system row spans the runs it
 *   folded; reminders and continuation prompts span 0). The app's counting
 *   (`buildUserRunCountMap`, the A13 fork map) then matches the kernel's after compaction.
 */
object HistoryMapper {
    const val HOOK_HISTORY_TOOL_USE = "history_tool_use"

    fun map(sessionId: String, messages: JsonArray): HistoryMapping {
        val rows = ArrayList<ChatMessage>()
        val rawIndex = ArrayList<Int>()
        var previousTs: Long = UNDATED
        var userRunCount = 0
        var runs = RunStamp.NONE

        fun add(row: ChatMessage, index: Int) {
            rows += runs.stamp(row)
            rawIndex += index
        }

        messages.forEachIndexed { index, element ->
            val m = element as? JsonObject ?: return@forEachIndexed
            val msgId = m.str("id") ?: "m$index"
            val ts = m["ts"].epochMillis() ?: m["createdAt"].epochMillis() ?: previousTs
            if (ts != UNDATED) previousTs = ts
            val blocks: List<JsonObject> = when (val content = m["content"]) {
                is JsonArray -> content.mapNotNull { it as? JsonObject }
                is JsonPrimitive -> if (content is JsonNull) emptyList() else listOf(JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to content)))
                else -> emptyList()
            }
            val span = getUserRunSpan(RunMessage.fromJson(m))
            userRunCount += span
            runs = RunStamp(
                runCount = userRunCount.takeIf { it > 0 },
                userRunSpan = span.takeIf { m.str("role") == "user" && it != 1 },
            )
            val display = displayRoleOf(m)
            if (display != null) {
                mapDisplayOnly(sessionId, msgId, ts, blocks, m, display)?.let { add(it, index) }
                return@forEachIndexed
            }
            when (m.str("role")) {
                "user" -> mapUser(sessionId, msgId, ts, blocks, rows, index, ::add)
                "assistant" -> mapAssistant(sessionId, msgId, ts, blocks, m, index, ::add)
            }
        }
        return HistoryMapping(rows, rawIndex, messages.size)
    }

    /** The kernel's run facts of the raw message being mapped, stamped on each of its rows. */
    private data class RunStamp(val runCount: Int?, val userRunSpan: Int?) {
        /** A steering note keeps the 0 [mapUser] gave it (older notes have no stored span). */
        fun stamp(row: ChatMessage): ChatMessage {
            if (runCount == null && userRunSpan == null) return row
            val span = row.meta.userRunSpan ?: userRunSpan
            return row.copy(meta = row.meta.copy(runCount = runCount, userRunSpan = span))
        }

        companion object {
            val NONE = RunStamp(null, null)
        }
    }

    /**
     * `metadata.displayRole` (core `resolveMessageDisplayRole`, desktop sidecar): a persisted
     * run failure is an assistant message with `displayRole: "error"`, so a reopened session
     * shows the error row with its hint and action, not the raw text as an answer.
     */
    private fun displayRoleOf(message: JsonObject): Role? =
        when (message.obj("metadata")?.str("displayRole")?.trim()?.lowercase()) {
            "error" -> Role.Error
            "system" -> Role.System
            "status" -> Role.Status
            else -> null
        }

    private fun mapDisplayOnly(sessionId: String, msgId: String, ts: Long, blocks: List<JsonObject>, message: JsonObject, role: Role): ChatMessage? {
        val text = blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString("\n")
        if (text.isBlank() && role != Role.Error) return null
        val providerId = message.obj("modelInfo")?.str("provider") ?: message.str("providerId")
        val meta = if (role == Role.Error) {
            Meta(reason = if (looksCredentialRelated(text)) REASON_CREDENTIALS else null, providerId = providerId)
        } else {
            Meta()
        }
        return ChatMessage(id = msgId, sessionId = sessionId, role = role, content = text, createdAt = ts, meta = meta)
    }

    private fun mapUser(
        sessionId: String,
        msgId: String,
        ts: Long,
        blocks: List<JsonObject>,
        rows: MutableList<ChatMessage>,
        index: Int,
        add: (ChatMessage, Int) -> Unit,
    ) {
        val texts = ArrayList<String>()
        val images = ArrayList<ChatImage>()
        blocks.forEachIndexed { blockIndex, block ->
            when (block.str("type")) {
                "text" -> block.str("text")?.let { texts += it }
                "image" -> imageOf(block, "$msgId:$blockIndex")?.let { images += it }
                "tool_result" -> mergeToolResult(rows, block)
            }
        }
        if (texts.isEmpty() && images.isEmpty()) return
        val raw = texts.joinToString("\n")
        val steering = raw.trimStart().startsWith("[SYSTEM]") && !UserInputFormat.isWrappedUserInput(raw)
        // The act-mode continuation the app (or the CLI) sent for the model: kept for the mode it
        // carries, hidden like a steering note and counted as no run, as core counts it (CT C.4).
        val continuation = !steering && images.isEmpty() && ActModeSwitch.isContinuationPrompt(UserInputFormat.formatDisplayUserInput(raw))
        add(
            ChatMessage(
                id = msgId,
                sessionId = sessionId,
                role = Role.User,
                content = raw,
                images = images,
                createdAt = ts,
                meta = Meta(
                    userRunSpan = if (steering || continuation) 0 else null,
                    messageKind = if (continuation) ActModeSwitch.MESSAGE_KIND else null,
                ),
            ),
            index,
        )
    }

    private fun mapAssistant(
        sessionId: String,
        msgId: String,
        ts: Long,
        blocks: List<JsonObject>,
        message: JsonObject,
        index: Int,
        add: (ChatMessage, Int) -> Unit,
    ) {
        var pendingReasoning: String? = null
        var pendingRedacted = false
        var pendingIndex = -1
        val media = ArrayList<GeneratedMedia>()
        val produced = ArrayList<ChatMessage>()

        fun flushReasoning() {
            if (pendingReasoning == null && !pendingRedacted) return
            produced += ChatMessage(
                id = "h:$msgId:$pendingIndex",
                sessionId = sessionId,
                role = Role.Assistant,
                content = "",
                reasoning = pendingReasoning,
                reasoningRedacted = pendingRedacted,
                createdAt = ts,
            )
            pendingReasoning = null
            pendingRedacted = false
        }

        blocks.forEachIndexed { blockIndex, block ->
            when (block.str("type")) {
                "thinking", "reasoning" -> {
                    val text = block.str("thinking") ?: block.str("text") ?: block.str("reasoning") ?: ""
                    if (pendingReasoning == null && !pendingRedacted) pendingIndex = blockIndex
                    pendingReasoning = (pendingReasoning?.let { "$it\n" } ?: "") + text
                }
                "redacted_thinking" -> {
                    if (pendingReasoning == null && !pendingRedacted) pendingIndex = blockIndex
                    pendingRedacted = true
                }
                "text" -> {
                    val text = block.str("text").orEmpty()
                    if (text.isBlank() && pendingReasoning == null && !pendingRedacted) return@forEachIndexed
                    produced += ChatMessage(
                        id = "h:$msgId:$blockIndex",
                        sessionId = sessionId,
                        role = Role.Assistant,
                        content = text,
                        reasoning = pendingReasoning,
                        reasoningRedacted = pendingRedacted,
                        createdAt = ts,
                    )
                    pendingReasoning = null
                    pendingRedacted = false
                }
                "tool_use" -> {
                    flushReasoning()
                    val toolCallId = block.str("id") ?: "$msgId:$blockIndex"
                    val toolName = block.str("name") ?: "tool"
                    produced += ChatMessage(
                        id = ChatProjector.toolId(toolCallId),
                        sessionId = sessionId,
                        role = Role.Tool,
                        content = ChatProjector.toolContent(toolName, block["input"], null, null),
                        createdAt = ts,
                        meta = Meta(toolName = toolName, toolCallId = toolCallId, hookEventName = HOOK_HISTORY_TOOL_USE),
                    )
                }
                "media" -> media += ChatProjector.parseMedia(block["media"] ?: block)
                "image" -> imageOf(block, "$msgId:$blockIndex")?.let { image ->
                    produced += ChatMessage(id = "h:$msgId:$blockIndex", sessionId = sessionId, role = Role.Assistant, content = "", images = listOf(image), createdAt = ts)
                }
            }
        }
        flushReasoning()
        if (media.isNotEmpty()) {
            val target = produced.indexOfLast { it.role == Role.Assistant }
            if (target >= 0) {
                produced[target] = produced[target].copy(media = produced[target].media + media)
            } else {
                produced += ChatMessage(id = "h:$msgId:media", sessionId = sessionId, role = Role.Assistant, content = "", media = media, createdAt = ts)
            }
        }
        // Per-message metrics and model on the message's last assistant row.
        val metrics = message.obj("metrics")
        val model = message.obj("modelInfo")
        val last = produced.indexOfLast { it.role == Role.Assistant }
        if (last >= 0 && (metrics != null || model != null)) {
            val row = produced[last]
            produced[last] = row.copy(
                meta = row.meta.copy(
                    inputTokens = metrics?.long("inputTokens")?.toInt(),
                    outputTokens = metrics?.long("outputTokens")?.toInt(),
                    cacheReadTokens = metrics?.long("cacheReadTokens")?.toInt(),
                    totalCost = metrics?.double("cost") ?: metrics?.double("totalCost"),
                    modelId = model?.str("id"),
                    providerId = model?.str("provider"),
                ),
            )
        } else if (metrics != null && produced.isNotEmpty()) {
            // A model call that only used tools: its usage stays on its last tool row, so a
            // turn's totals (A11.9 `TurnRecords.historyStatsByAnswer`) count every call.
            val row = produced.last()
            produced[produced.lastIndex] = row.copy(
                meta = row.meta.copy(
                    inputTokens = metrics.long("inputTokens")?.toInt(),
                    outputTokens = metrics.long("outputTokens")?.toInt(),
                    cacheReadTokens = metrics.long("cacheReadTokens")?.toInt(),
                    totalCost = metrics.double("cost") ?: metrics.double("totalCost"),
                ),
            )
        }
        produced.forEach { add(it, index) }
    }

    private fun mergeToolResult(rows: MutableList<ChatMessage>, block: JsonObject) {
        val useId = block.str("tool_use_id") ?: return
        val id = ChatProjector.toolId(useId)
        val target = rows.indexOfLast { it.id == id }
        if (target < 0) return
        val row = rows[target]
        val payload = runCatching { HubJson.parseToJsonElement(row.content) as? JsonObject }.getOrNull()
        val toolName = row.meta.toolName ?: payload?.str("toolName") ?: block.str("name") ?: "tool"
        val result = decodeResult(block["content"])
        val isError = block.bool("is_error") == true || SessionFactsReducer.extractError(result) != null
        rows[target] = row.copy(
            content = ChatProjector.toolContent(toolName, payload?.get("input"), result, isError),
            meta = row.meta.copy(hookEventName = null),
        )
    }

    /** `tool_result.content`: an array/object as is; a JSON string is parsed when it parses (docs/08). */
    private fun decodeResult(content: JsonElement?): JsonElement = when (content) {
        null -> JsonNull
        is JsonPrimitive -> if (content.isString) {
            val text = content.content
            val trimmed = text.trimStart()
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) runCatching { HubJson.parseToJsonElement(text) }.getOrDefault(content) else content
        } else content
        else -> content
    }

    private fun imageOf(block: JsonObject, id: String): ChatImage? {
        block.str("data")?.let { data ->
            return ChatImage(id, block.str("mediaType") ?: block.str("media_type") ?: "image/png", data)
        }
        val source = block.obj("source") ?: return null
        val data = source.str("data") ?: return null
        return ChatImage(id, source.str("media_type") ?: source.str("mediaType") ?: "image/png", data)
    }
}
