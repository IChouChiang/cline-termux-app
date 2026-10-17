package dev.cline.termux.data.chat

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.session.ActModeSwitch
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * 1:1 port of the desktop render pipeline
 * (apps/examples/desktop-app/webview/components/views/chat/messages/group-messages.ts),
 * home-chat.md §1.5. Its unit tests are ported in ChatGroupingTest with the desktop's cases.
 *
 * Two deliberate representation changes, neither changes behaviour:
 * - `createdAt` is a Long; the desktop's non-finite timestamps (NaN) are [UNDATED].
 * - Item ids (`work.id`, `run.id`) are the first message's `renderKey`, not its `id`
 *   (home-chat.md §3.4.5): they equal the id until reconciliation aliases a canonical row
 *   to its live predecessor, and then they must not change (§1.3.3).
 */

/** `ChatMessage.createdAt` for a message without a usable timestamp (the desktop's `NaN`). */
const val UNDATED: Long = Long.MIN_VALUE

fun isDated(timestamp: Long?): Boolean = timestamp != null && timestamp != UNDATED

/** `AgentMessageRole` (`sdk/packages/ui/components/agent-chat`). */
enum class AgentMessageRole { User, Assistant, System, Status, Error, Tool;

    companion object {
        fun of(role: Role): AgentMessageRole = when (role) {
            Role.User -> User
            Role.Assistant -> Assistant
            Role.Tool -> Tool
            Role.System -> System
            Role.Status -> Status
            Role.Error -> Error
        }
    }
}

/** `ChatRenderItem`. */
@Immutable
sealed interface ChatRenderItem {
    @Immutable
    data class Message(
        val agentRole: AgentMessageRole,
        val message: ChatMessage,
        val reasoningMessages: List<ChatMessage>,
    ) : ChatRenderItem

    @Immutable
    data class Tools(val messages: List<ChatMessage>) : ChatRenderItem

    /** A finished run's working rows folded behind its answer. [id] = first collapsed message's renderKey. */
    @Immutable
    data class Work(
        val id: String,
        val items: List<ChatRenderItem>,
        val durationMilliseconds: Long?,
        val toolCallCount: Int,
    ) : ChatRenderItem

    /** A run's still-visible working rows grouped with the tight in-run rhythm. */
    @Immutable
    data class Run(val id: String, val items: List<ChatRenderItem>) : ChatRenderItem
}

/** The desktop `type` discriminator, for tests and logs. */
val ChatRenderItem.type: String
    get() = when (this) {
        is ChatRenderItem.Message -> "message"
        is ChatRenderItem.Tools -> "tools"
        is ChatRenderItem.Work -> "work"
        is ChatRenderItem.Run -> "run"
    }

/**
 * Runtime steering notes injected as user-role messages (completion-tool reminders,
 * team-obligation nudges): rendered as subtle system rows and folded into the run's work.
 * Injected reminders carry `userRunSpan` 0; requiring it keeps a genuine prompt that starts
 * with "[SYSTEM]" visible and turn-counted. The app adds one kind the desktop never sends: the
 * act-mode continuation prompt (`switch_to_act_mode`, HistoryMapper marks it), hidden the same way.
 */
fun isSystemSteeringMessage(message: ChatMessage): Boolean =
    message.role == Role.User &&
        message.meta.userRunSpan == 0 &&
        (message.content.trimStart().startsWith("[SYSTEM]") || message.meta.messageKind == ActModeSwitch.MESSAGE_KIND)

fun hasMessageReasoning(message: ChatMessage): Boolean =
    !message.reasoning.isNullOrBlank() || message.reasoningRedacted

fun isReasoningOnlyAssistantMessage(message: ChatMessage): Boolean =
    message.role == Role.Assistant &&
        hasMessageReasoning(message) &&
        message.content.isBlank() &&
        message.images.isEmpty()

/** Each message → the timestamp of the previous datable message (identity-keyed, like the desktop `Map`). */
fun buildPreviousTimestampMap(messages: List<ChatMessage>): Map<ChatMessage, Long?> {
    val map = IdentityHashMap<ChatMessage, Long?>()
    var previous: Long? = null
    for (message in messages) {
        map[message] = previous
        if (isDated(message.createdAt)) previous = message.createdAt
    }
    return map
}

/** User message → its run count (checkpoint numbering; stored counts win). Identity-keyed. */
fun buildUserRunCountMap(messages: List<ChatMessage>): Map<ChatMessage, Int> {
    val map = IdentityHashMap<ChatMessage, Int>()
    var lastRunCount = 0
    for (message in messages) {
        val userRunSpan = message.meta.userRunSpan
            ?: if (message.role == Role.User && !isSystemSteeringMessage(message)) 1 else 0
        val storedRunCount = message.meta.runCount ?: message.meta.checkpoint?.runCount
        if (storedRunCount != null) {
            lastRunCount = maxOf(lastRunCount, storedRunCount)
            if (message.role == Role.User && userRunSpan == 1) map[message] = storedRunCount
            continue
        }
        lastRunCount += userRunSpan
        if (message.role == Role.User && userRunSpan == 1) map[message] = lastRunCount
    }
    return map
}

fun getThoughtDurationMilliseconds(previousTimestamp: Long?, thinkingTimestamp: Long): Long? {
    if (previousTimestamp == null || !isDated(previousTimestamp) || !isDated(thinkingTimestamp) || thinkingTimestamp < previousTimestamp) {
        return null
    }
    return thinkingTimestamp - previousTimestamp
}

/**
 * Working rows collapse, deliverables stay: tool groups plus assistant narration/thinking
 * without images or media. An assistant message with attachments is a span boundary.
 */
private fun isCollapsibleWorkItem(item: ChatRenderItem): Boolean = when (item) {
    is ChatRenderItem.Tools -> true
    is ChatRenderItem.Message -> isSystemSteeringMessage(item.message) ||
        (item.message.role == Role.Assistant && item.message.images.isEmpty() && item.message.media.isEmpty())
    else -> false
}

private fun firstTimestamp(item: ChatRenderItem): Long? = when (item) {
    is ChatRenderItem.Tools -> item.messages.firstOrNull()?.createdAt
    is ChatRenderItem.Message -> (item.reasoningMessages.firstOrNull() ?: item.message).createdAt
    else -> null
}

private fun lastTimestamp(item: ChatRenderItem): Long? = when (item) {
    is ChatRenderItem.Tools -> item.messages.lastOrNull()?.createdAt
    is ChatRenderItem.Message -> item.message.createdAt
    else -> null
}

private fun maxFiniteTimestamp(vararg values: Long?): Long? {
    var max: Long? = null
    for (value in values) {
        if (value == null || !isDated(value)) continue
        if (max == null || value > max) max = value
    }
    return max
}

/** `JSON.parse(content)` → the tool payload, or null when the content is not a JSON object. */
fun parseToolPayload(raw: String): JsonObject? =
    runCatching { HubJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

/** A `submit_and_exit` call carries the run's final report: a run ending on one treats it as its deliverable. */
private fun isSubmitAndExitMessage(message: ChatMessage): Boolean {
    if (message.role != Role.Tool) return false
    val toolName = message.meta.toolName?.takeIf { it.isNotEmpty() }
        ?: (parseToolPayload(message.content)?.get("toolName") as? JsonPrimitive)?.contentOrNull
    return toolName?.lowercase() == "submit_and_exit"
}

private fun firstMessageId(item: ChatRenderItem): String = when (item) {
    is ChatRenderItem.Tools -> item.messages.first().renderKey
    is ChatRenderItem.Message -> (item.reasoningMessages.firstOrNull() ?: item.message).renderKey
    is ChatRenderItem.Work -> item.id
    is ChatRenderItem.Run -> item.id
}

/**
 * Folds each finished run's working rows (tool calls, thinking, intermediate narration) into
 * one expandable [ChatRenderItem.Work], keeping the run's final answer visible after it.
 * Rows that stay visible (live stream, tool-less runs, answerless tails) are grouped into a
 * [ChatRenderItem.Run]. [collapseTrailingRun]: true only once the session stopped running.
 */
fun collapseCompletedWork(items: List<ChatRenderItem>, collapseTrailingRun: Boolean): List<ChatRenderItem> {
    var lastUserIndex = -1
    for (index in items.indices.reversed()) {
        val item = items[index]
        if (item is ChatRenderItem.Message && item.message.role == Role.User && !isSystemSteeringMessage(item.message)) {
            lastUserIndex = index
            break
        }
    }

    val out = ArrayList<ChatRenderItem>()
    var span = ArrayList<ChatRenderItem>()
    var runStartTimestamp: Long? = null

    fun flushSpan(nextIndex: Int) {
        if (span.isEmpty()) return
        val last = span.last()
        var answer: ChatRenderItem? = null
        var workRows: List<ChatRenderItem> = span
        if (last is ChatRenderItem.Message && last.message.role == Role.Assistant && last.message.content.isNotBlank()) {
            answer = last
            workRows = span.subList(0, span.size - 1)
        } else if (last is ChatRenderItem.Tools) {
            val lastToolMessage = last.messages.lastOrNull()
            if (lastToolMessage != null && isSubmitAndExitMessage(lastToolMessage)) {
                answer = ChatRenderItem.Tools(listOf(lastToolMessage))
                workRows = if (last.messages.size > 1) {
                    span.subList(0, span.size - 1) + ChatRenderItem.Tools(last.messages.subList(0, last.messages.size - 1))
                } else {
                    span.subList(0, span.size - 1)
                }
            }
        }
        val complete = nextIndex <= lastUserIndex || (collapseTrailingRun && answer != null)
        val collapsed = if (complete && answer != null) workRows else span
        val toolCallCount = collapsed.sumOf { if (it is ChatRenderItem.Tools) it.messages.size else 0 }
        val firstCollapsed = collapsed.firstOrNull()
        if (!complete || toolCallCount == 0 || firstCollapsed == null) {
            val messageAnswer = answer as? ChatRenderItem.Message
            val body = if (messageAnswer != null) span.subList(0, span.size - 1) else span
            val firstBody = body.firstOrNull()
            val hasToolWork = body.any { it is ChatRenderItem.Tools }
            if (body.size >= 2 && hasToolWork && firstBody != null) {
                out += ChatRenderItem.Run(id = firstMessageId(firstBody), items = body.toList())
                if (messageAnswer != null) out += messageAnswer
            } else {
                out.addAll(span)
            }
        } else {
            val startTimestamp = runStartTimestamp ?: firstTimestamp(firstCollapsed)
            val lastCollapsed = collapsed.last()
            val endTimestamp = maxFiniteTimestamp(answer?.let(::lastTimestamp), lastTimestamp(lastCollapsed))
            val duration = if (
                startTimestamp != null && endTimestamp != null &&
                isDated(startTimestamp) && isDated(endTimestamp) && endTimestamp >= startTimestamp
            ) endTimestamp - startTimestamp else null
            out += ChatRenderItem.Work(
                id = firstMessageId(firstCollapsed),
                items = collapsed.toList(),
                durationMilliseconds = duration,
                toolCallCount = toolCallCount,
            )
            if (answer != null) out += answer
        }
        span = ArrayList()
    }

    for (index in items.indices) {
        val item = items[index]
        if (isCollapsibleWorkItem(item)) {
            span += item
            continue
        }
        flushSpan(index)
        out += item
        runStartTimestamp = if (item is ChatRenderItem.Message && item.message.role == Role.User) item.message.createdAt else null
    }
    flushSpan(items.size)
    return out
}

/** Folds reasoning-only assistant messages into the next assistant message and merges consecutive tool rows. */
fun groupChatMessages(messages: List<ChatMessage>): List<ChatRenderItem> {
    val items = ArrayList<ChatRenderItem>()
    var pendingReasoning = ArrayList<ChatMessage>()

    fun pushMessage(message: ChatMessage, agentRole: AgentMessageRole, reasoningMessages: List<ChatMessage>) {
        items += ChatRenderItem.Message(agentRole, message, reasoningMessages)
    }

    fun flushPendingReasoning() {
        val message = pendingReasoning.lastOrNull() ?: return
        pushMessage(message, AgentMessageRole.Assistant, pendingReasoning.toList())
        pendingReasoning = ArrayList()
    }

    for (message in messages) {
        if (isReasoningOnlyAssistantMessage(message)) {
            pendingReasoning += message
            continue
        }
        if (message.role == Role.Assistant && pendingReasoning.isNotEmpty()) {
            val reasoningMessages = if (hasMessageReasoning(message)) pendingReasoning + message else pendingReasoning.toList()
            pushMessage(message, AgentMessageRole.Assistant, reasoningMessages)
            pendingReasoning = ArrayList()
            continue
        }
        flushPendingReasoning()
        val previous = items.lastOrNull()
        if (message.role == Role.Tool) {
            if (previous is ChatRenderItem.Tools) {
                items[items.lastIndex] = ChatRenderItem.Tools(previous.messages + message)
            } else {
                items += ChatRenderItem.Tools(listOf(message))
            }
            continue
        }
        pushMessage(message, AgentMessageRole.of(message.role), if (hasMessageReasoning(message)) listOf(message) else emptyList())
    }
    flushPendingReasoning()
    return items
}

/** home-chat.md §1.5: the whole pipeline; trailing runs fold only once the session is Completed or Idle. */
fun buildChatRenderItems(messages: List<ChatMessage>, status: ChatStatus): List<ChatRenderItem> =
    collapseCompletedWork(
        groupChatMessages(messages),
        collapseTrailingRun = status == ChatStatus.Completed || status == ChatStatus.Idle,
    )
