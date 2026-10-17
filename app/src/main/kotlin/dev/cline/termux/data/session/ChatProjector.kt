package dev.cline.termux.data.session

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.GeneratedMedia
import dev.cline.termux.data.chat.MediaModality
import dev.cline.termux.data.chat.MediaSource
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.ProviderAuthInfo
import dev.cline.termux.data.chat.REASON_CREDENTIALS
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunFailedPayload
import dev.cline.termux.data.chat.isReasoningOnlyAssistantMessage
import dev.cline.termux.data.chat.looksCredentialRelated
import dev.cline.termux.data.chat.matchingProviderAuth
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.data.tools.appendCappedCommandOutput
import dev.cline.termux.data.tools.executedCommand
import dev.cline.termux.data.tools.runCommandsExecuted
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * The live half of a session's transcript: rows built from events since the last seed or
 * reconciliation, plus the cursor state the projector needs to keep ids stable (HC §1.3.1).
 */
@Immutable
data class LiveProjection(
    val messages: List<ChatMessage> = emptyList(),
    /** The run the next rows belong to (`run.started.requestId`, or `q:<promptId>`). */
    val requestId: String? = null,
    val iteration: Int? = null,
    /** `n` of `a:`/`r:` ids: bumped whenever an assistant or reasoning-only row closes. */
    val segment: Int = 0,
    val openAssistantId: String? = null,
    val openReasoningId: String? = null,
    /** The assistant message receiving deltas (`ChatUiState.streamingMessageId`). */
    val streamingMessageId: String? = null,
    /** toolCallId → last `commandIndex` seen, for the `$ <query>` header of later commands. */
    val commandIndexes: Map<String, Int> = emptyMap(),
    /** Between this run's own `iteration.started` and `iteration.finished`. */
    val iterationOpen: Boolean = false,
    /**
     * Agent loops nested in the open iteration (a `spawn_agent` subagent): the Hub forwards their
     * `iteration.*` / `assistant.*` / `tool.*` events into the parent session with no `agent`
     * marker, so an `iteration.started` while ours is still open opens one (§1.3.1, Q6).
     */
    val nestedDepth: Int = 0,
    /**
     * The direct run whose terminal `run.*` is still to come although its loop already settled:
     * the Hub drains the next queued prompt (`session.pending_prompt_submitted`) before it
     * publishes the previous run's `run.completed`, and the drained prompt never gets a
     * `run.*` of its own. That terminal closes this run, not the queued one.
     */
    val endingRequestId: String? = null,
)

/** Everything the projector reads besides the event. */
data class ProjectContext(
    val sessionId: String,
    val now: Long,
    /** requestIds whose `run.started` is a queued send (facts before this event). */
    val queuedRequestIds: Set<String> = emptySet(),
    /** The session's open turn, whose provider and auth facts stamp error rows (§1.3.5). */
    val openTurn: TurnRecord? = null,
    /** The session's current provider, for rows without a turn record. */
    val sessionProviderId: String? = null,
    val authFor: (String?) -> ProviderAuthInfo? = { null },
    /**
     * The session's agent loop is not running (its status is idle-like) although a run is
     * still open: a `session.pending_prompt_submitted` then starts a new queued turn instead
     * of joining the open run as a steered prompt does.
     */
    val previousRunSettled: Boolean = false,
)

/**
 * Pure projector, Hub event → live `ChatMessage` rows (home-chat.md §1.3.1, docs/07 §4.2 column
 * "Messages", D14). Ids are derived from the event so a replay produces the same ids:
 * `local:<requestId>`, `a:<requestId>:<iteration>:<n>`, `r:<requestId>:<iteration>:<n>`,
 * `t:<toolCallId>`, `n:<sequence>` (notice), `x:<sequence>` (api_error notice),
 * `e:<requestId>` (run failed), `i:<runId|requestId>` (interrupted).
 */
object ChatProjector {

    fun localId(requestId: String): String = "local:$requestId"
    fun toolId(toolCallId: String): String = "t:$toolCallId"
    fun interruptedId(key: String): String = "i:$key"
    fun errorId(key: String): String = "e:$key"

    private const val HOOK_TOOL_CALL_START = "tool_call_start"
    private val NESTED_AGENT_KINDS = setOf("subagent", "teammate")

    fun project(state: LiveProjection, event: HubEventEnvelope, ctx: ProjectContext): LiveProjection {
        val p = event.payload ?: EMPTY_OBJECT
        val at = event.timestamp ?: ctx.now
        // Subagent and teammate output feeds the agents tally, not the parent transcript (§1.3.1, Q6):
        // marked by `agent.kind`, or (subagents on the Hub today) unmarked inside a nested loop.
        val marked = p.obj("agent")?.str("kind") in NESTED_AGENT_KINDS
        val nested = marked || state.nestedDepth > 0
        return when (event.event) {
            HubEvents.RUN_STARTED -> {
                val requestId = p.str("requestId")
                if (requestId != null && requestId in ctx.queuedRequestIds) state
                else closeOpen(state).copy(
                    requestId = requestId, iteration = null, segment = 0, commandIndexes = emptyMap(),
                    iterationOpen = false, nestedDepth = 0,
                )
            }
            HubEvents.ITERATION_STARTED -> when {
                marked -> state
                state.iterationOpen -> state.copy(nestedDepth = state.nestedDepth + 1)
                else -> closeOpen(state).copy(iteration = p.int("iteration"), segment = 0, iterationOpen = true)
            }
            HubEvents.ITERATION_FINISHED -> when {
                marked -> state
                state.nestedDepth > 0 -> state.copy(nestedDepth = state.nestedDepth - 1)
                else -> closeOpen(state).copy(iterationOpen = false)
            }
            HubEvents.ASSISTANT_DELTA -> if (nested) state else assistantDelta(state, p.str("text").orEmpty(), ctx, at)
            HubEvents.ASSISTANT_FINISHED -> if (nested) state else assistantFinished(state, p.str("text"), ctx, at)
            HubEvents.REASONING_DELTA -> if (nested) state else reasoningDelta(state, p.str("text").orEmpty(), p.bool("redacted") == true, ctx, at)
            HubEvents.REASONING_FINISHED -> if (nested) state else reasoningFinished(state, p.str("reasoning"), at)
            HubEvents.ASSISTANT_MEDIA -> if (nested) state else assistantMedia(state, p, ctx, at)
            HubEvents.TOOL_STARTED -> if (nested) state else toolStarted(state, p, ctx, at)
            HubEvents.APPROVAL_REQUESTED -> if (nested) state else approvalRequested(state, p, ctx, at)
            HubEvents.TOOL_UPDATED -> if (nested) state else toolUpdated(state, p)
            HubEvents.TOOL_FINISHED -> if (nested) state else toolFinished(state, p, ctx, at)
            HubEvents.USAGE_UPDATED -> if (nested) state else usageUpdated(state, p)
            HubEvents.SESSION_NOTICE -> notice(state, p, event, ctx, at)
            HubEvents.SESSION_PENDING_PROMPT_SUBMITTED -> promptSubmitted(state, p, ctx, at)
            HubEvents.RUN_COMPLETED, HubEvents.RUN_ABORTED, HubEvents.RUN_FAILED -> {
                val ending = state.endingRequestId
                when {
                    // The settled previous run ends; the queued turn that already started keeps going.
                    ending != null && event.event == HubEvents.RUN_FAILED ->
                        endPreviousRun(runFailed(state, p, event, ctx, at, requestIdOverride = ending))
                    ending != null -> endPreviousRun(state)
                    event.event == HubEvents.RUN_FAILED -> endRun(runFailed(state, p, event, ctx, at))
                    else -> endRun(state)
                }
            }
            HubEvents.RUN_INTERRUPTED -> {
                val key = p.str("runId") ?: state.requestId ?: event.sequence?.toString() ?: at.toString()
                val provider = ctx.openTurn?.providerId ?: ctx.sessionProviderId
                endRun(
                    appendInterrupted(
                        state, ctx.sessionId, key, state.requestId ?: ctx.openTurn?.requestId, p.str("error").orEmpty(),
                        provider, ctx.openTurn?.providerAuth ?: ctx.authFor(provider), at,
                    ),
                )
            }
            else -> state
        }
    }

    // ---- the store's own rows -----------------------------------------------------------------

    /**
     * The optimistic user bubble `local:<requestId>` of a direct send (§1.3.1, §3.12 step 1).
     * The act-mode continuation ([ActModeSwitch.CONTINUATION_PROMPT]) is a synthetic prompt the
     * app sends for the model, not something the user typed: it gets no bubble (the CLI's
     * `isSyntheticUserText`); its history row is hidden as well (HistoryMapper).
     */
    fun appendLocalUser(
        state: LiveProjection,
        sessionId: String,
        requestId: String,
        prompt: String,
        images: List<ChatImage>,
        createdAt: Long,
        renderKey: String? = null,
    ): LiveProjection {
        if (images.isEmpty() && ActModeSwitch.isContinuationPrompt(prompt)) return state
        val id = localId(requestId)
        if (state.messages.any { it.id == id }) return state
        val message = ChatMessage(
            id = id,
            sessionId = sessionId,
            role = Role.User,
            content = prompt,
            images = images,
            createdAt = createdAt,
            renderKey = renderKey ?: id,
            meta = Meta(requestId = requestId, userRunSpan = 1),
        )
        return state.copy(messages = state.messages + message)
    }

    /**
     * The interrupted error row (§1.3.4): keyed `i:<key>`; when the derived and the genuine
     * interruption both fire for one turn, the first row is kept.
     */
    fun appendInterrupted(
        state: LiveProjection,
        sessionId: String,
        key: String,
        requestId: String?,
        detail: String,
        providerId: String?,
        providerAuth: ProviderAuthInfo?,
        createdAt: Long,
    ): LiveProjection {
        val id = interruptedId(key)
        val duplicate = state.messages.any {
            it.id == id || (requestId != null && it.role == Role.Error && it.meta.reason == REASON_INTERRUPTED && it.meta.requestId == requestId)
        }
        if (duplicate) return state
        val message = ChatMessage(
            id = id,
            sessionId = sessionId,
            role = Role.Error,
            content = detail,
            createdAt = createdAt,
            meta = Meta(
                reason = REASON_INTERRUPTED,
                requestId = requestId,
                providerId = providerId,
                providerAuth = matchingProviderAuth(providerId, providerAuth),
            ),
        )
        return closeOpen(state).copy(messages = state.messages + message)
    }

    /** An error row for a send that failed before its run (`e:<requestId>`). */
    fun appendError(
        state: LiveProjection,
        sessionId: String,
        key: String,
        requestId: String?,
        detail: String,
        providerId: String?,
        providerAuth: ProviderAuthInfo?,
        createdAt: Long,
    ): LiveProjection {
        val id = errorId(key)
        if (state.messages.any { it.id == id }) return state
        return state.copy(messages = state.messages + errorRow(id, sessionId, detail, requestId, providerId, providerAuth, createdAt))
    }

    fun remove(state: LiveProjection, id: String): LiveProjection =
        state.copy(messages = state.messages.filterNot { it.id == id })

    // ---- message details (A11.9) ---------------------------------------------------------------

    /**
     * The `session.notice` recovery reasons that would report a recoverable mid-run error. The
     * terminal shows recoverable errors only in verbose mode; `api_error` is not here, it is an
     * error row with its credential fix in both modes.
     *
     * **Waiting for a Hub that sends them: no kernel up to cli-v3.0.69 does.** The shape is the
     * protocol's declared `AgentNoticeEvent` (`sdk/packages/shared/src/agents/types.ts:181-195`:
     * `noticeType "recovery"`, these reasons), but nothing produces it. The only source of agent
     * notice events is `runtime-event-adapter.ts:247-257`, which always sets `noticeType
     * "status"` and a reason only for the three compaction values. The recoverable errors
     * themselves are agent `error` events with `recoverable: true` (`MistakeTracker.record`,
     * `mistake-tracker.ts:94-99`), and the Hub drops them: `projectAgentErrorEvent`
     * (`session-event-projector.ts:376-396`) returns early for a recoverable error. Showing them
     * needs a Hub change that projects them, which goes upstream first (D20). Until then Brief
     * and Verbose show the same rows on a real Hub; failed tools are failed tool rows in both.
     */
    val RECOVERABLE_ERROR_REASONS: Set<String> = setOf(
        "invalid_tool_call",
        "tool_execution_failed",
        "completion_without_submit",
        "mistake_limit",
    )

    /** A recovery notice row ([notice]) about a recoverable mid-run error (none on current kernels, see above). */
    fun isRecoverableErrorNotice(message: ChatMessage): Boolean =
        message.role == Role.Status && message.meta.noticeType == NOTICE_RECOVERY && message.meta.reason in RECOVERABLE_ERROR_REASONS

    /**
     * The transcript rows [mode] shows. The projection keeps every notice, so a change of mode
     * re-projects the rows already received: Brief leaves out the recoverable-error notices,
     * Verbose shows them all. Status, stop and other recovery notices (compaction) and error
     * rows are the same in both. Returns [messages] itself when nothing is left out, which on
     * every current kernel is always ([RECOVERABLE_ERROR_REASONS]: no Hub sends such notices yet).
     */
    fun forDetails(messages: List<ChatMessage>, mode: MessageDetails): List<ChatMessage> {
        if (mode == MessageDetails.Verbose || messages.none(::isRecoverableErrorNotice)) return messages
        return messages.filterNot(::isRecoverableErrorNotice)
    }

    private const val NOTICE_RECOVERY = "recovery"

    private fun errorRow(
        id: String,
        sessionId: String,
        detail: String,
        requestId: String?,
        providerId: String?,
        providerAuth: ProviderAuthInfo?,
        createdAt: Long,
    ): ChatMessage {
        val auth = matchingProviderAuth(providerId, providerAuth)
        return ChatMessage(
            id = id,
            sessionId = sessionId,
            role = Role.Error,
            content = detail,
            createdAt = createdAt,
            meta = Meta(
                reason = if (looksCredentialRelated(detail)) REASON_CREDENTIALS else null,
                requestId = requestId,
                providerId = providerId,
                providerAuth = auth,
            ),
        )
    }

    // ---- assistant and reasoning ------------------------------------------------------------

    private fun runKey(state: LiveProjection, ctx: ProjectContext): String = state.requestId ?: ctx.openTurn?.requestId ?: "_"

    private fun assistantId(state: LiveProjection, ctx: ProjectContext) = "a:${runKey(state, ctx)}:${state.iteration ?: 0}:${state.segment}"
    private fun reasoningId(state: LiveProjection, ctx: ProjectContext) = "r:${runKey(state, ctx)}:${state.iteration ?: 0}:${state.segment}"

    private fun rowMeta(state: LiveProjection, ctx: ProjectContext) =
        Meta(requestId = state.requestId ?: ctx.openTurn?.requestId, iteration = state.iteration)

    private fun assistantDelta(state: LiveProjection, text: String, ctx: ProjectContext, at: Long): LiveProjection {
        state.openAssistantId?.let { id ->
            return update(state, id) { it.copy(content = it.content + text) }.copy(streamingMessageId = id)
        }
        val base = closeReasoning(state)
        val id = assistantId(base, ctx)
        return startAssistant(base, id, text, ctx, at).copy(openAssistantId = id, streamingMessageId = id)
    }

    private fun assistantFinished(state: LiveProjection, text: String?, ctx: ProjectContext, at: Long): LiveProjection {
        val open = state.openAssistantId
        if (open != null) {
            val updated = update(state, open) { if (!text.isNullOrEmpty()) it.copy(content = text) else it }
            return updated.copy(openAssistantId = null, streamingMessageId = null, segment = state.segment + 1)
        }
        if (text.isNullOrEmpty()) return state
        val base = closeReasoning(state)
        val id = assistantId(base, ctx)
        if (base.messages.any { it.id == id }) return base.copy(segment = base.segment + 1)
        val started = startAssistant(base, id, text, ctx, at)
        return started.copy(segment = started.segment + 1)
    }

    /**
     * Adds the assistant text row [id]. Text that follows a reasoning-only row of the same run
     * and iteration continues that row instead: `session.messages` stores reasoning and text as
     * one message (HistoryMapper), so live and history keep the same shape. The row takes the
     * assistant id but keeps its `renderKey`, so neither the item key nor the Work id moves
     * when the text starts or when reconciliation swaps in the history row (§1.3.3 step 4).
     */
    private fun startAssistant(base: LiveProjection, id: String, text: String, ctx: ProjectContext, at: Long): LiveProjection {
        val meta = rowMeta(base, ctx)
        val last = base.messages.lastOrNull()
        val carrier = last != null &&
            isReasoningOnlyAssistantMessage(last) &&
            last.media.isEmpty() &&
            last.meta.requestId == meta.requestId &&
            last.meta.iteration == meta.iteration &&
            last.id.startsWith("r:")
        if (carrier) {
            val merged = last!!.copy(id = id, content = text, meta = last.meta.copy(requestId = meta.requestId, iteration = meta.iteration))
            return base.copy(messages = base.messages.dropLast(1) + merged)
        }
        val message = ChatMessage(id = id, sessionId = ctx.sessionId, role = Role.Assistant, content = text, createdAt = at, meta = meta)
        return base.copy(messages = base.messages + message)
    }

    private fun reasoningDelta(state: LiveProjection, text: String, redacted: Boolean, ctx: ProjectContext, at: Long): LiveProjection {
        // Reasoning is output: like the desktop's `chat_reasoning` (activeAssistantMessageId), the
        // row becomes the streaming message, so "Thinking..." does not show under "Thinking" (§4.3).
        val target = state.openAssistantId ?: state.openReasoningId
        if (target != null) {
            return update(state, target) {
                it.copy(reasoning = (it.reasoning ?: "") + text, reasoningRedacted = it.reasoningRedacted || redacted)
            }.copy(streamingMessageId = target)
        }
        val id = reasoningId(state, ctx)
        val message = ChatMessage(
            id = id,
            sessionId = ctx.sessionId,
            role = Role.Assistant,
            content = "",
            reasoning = text,
            reasoningRedacted = redacted,
            createdAt = at,
            meta = rowMeta(state, ctx),
        )
        return state.copy(messages = state.messages + message, openReasoningId = id, streamingMessageId = id)
    }

    private fun reasoningFinished(state: LiveProjection, reasoning: String?, at: Long): LiveProjection {
        val target = state.openReasoningId ?: state.openAssistantId
            ?: state.messages.lastOrNull { it.role == Role.Assistant && it.meta.requestId == state.requestId && it.reasoning != null }?.id
            ?: return state
        val updated = update(state, target) {
            it.copy(reasoning = reasoning?.takeIf { r -> r.isNotEmpty() } ?: it.reasoning, meta = it.meta.copy(thoughtEndedAt = at))
        }
        // A finished reasoning-only row reads "Thought for Ns", not the streaming "Thinking".
        val streaming = if (state.openAssistantId == null && updated.streamingMessageId == target) null else updated.streamingMessageId
        return closeReasoning(updated.copy(streamingMessageId = streaming))
    }

    private fun assistantMedia(state: LiveProjection, p: JsonObject, ctx: ProjectContext, at: Long): LiveProjection {
        val media = parseMedia(p["media"])
        if (media.isEmpty()) return state
        val target = state.openAssistantId
            ?: state.messages.lastOrNull { it.role == Role.Assistant && it.meta.requestId == state.requestId && it.meta.iteration == state.iteration }?.id
        if (target != null) return update(state, target) { it.copy(media = it.media + media) }
        val id = assistantId(state, ctx)
        val message = ChatMessage(id = id, sessionId = ctx.sessionId, role = Role.Assistant, content = "", media = media, createdAt = at, meta = rowMeta(state, ctx))
        return state.copy(messages = state.messages + message, segment = state.segment + 1)
    }

    // ---- tools ----------------------------------------------------------------------------

    private fun toolStarted(state: LiveProjection, p: JsonObject, ctx: ProjectContext, at: Long): LiveProjection {
        val toolCallId = p.str("toolCallId") ?: return state // hook-derived duplicate (FM)
        val toolName = p.str("toolName") ?: "tool"
        val id = toolId(toolCallId)
        val base = closeOpen(state)
        val content = toolContent(toolName, p["input"], null, null)
        if (base.messages.any { it.id == id }) {
            return update(base, id) { it.copy(content = content, meta = it.meta.copy(toolName = toolName, hookEventName = HOOK_TOOL_CALL_START)) }
        }
        val message = ChatMessage(
            id = id,
            sessionId = ctx.sessionId,
            role = Role.Tool,
            content = content,
            createdAt = at,
            meta = rowMeta(base, ctx).copy(toolName = toolName, toolCallId = toolCallId, hookEventName = HOOK_TOOL_CALL_START),
        )
        return base.copy(messages = base.messages + message)
    }

    /**
     * The Hub asks for approval before it publishes `tool.started` (FX `02`, `03`), so the call's
     * row appears here, in the "Approval required" state of the facts' tool record (CA C3.7 rule 5);
     * the later `tool.started` updates the same `t:<toolCallId>` row. Re-issued requests dedupe by id.
     */
    private fun approvalRequested(state: LiveProjection, p: JsonObject, ctx: ProjectContext, at: Long): LiveProjection {
        val toolCallId = p.str("toolCallId") ?: return state
        if (state.messages.any { it.id == toolId(toolCallId) }) return state
        val input = p.str("inputJson")?.let { runCatching { HubJson.parseToJsonElement(it) }.getOrNull() } ?: p["input"]
        val start = buildJsonObject {
            put("toolCallId", JsonPrimitive(toolCallId))
            put("toolName", JsonPrimitive(p.str("toolName") ?: "tool"))
            input?.let { put("input", it) }
        }
        return toolStarted(state, start, ctx, at)
    }

    private fun toolUpdated(state: LiveProjection, p: JsonObject): LiveProjection {
        val toolCallId = p.str("toolCallId") ?: return state
        val id = toolId(toolCallId)
        if (state.messages.none { it.id == id }) return state
        val update = p.obj("update") ?: return state
        val stream = update.str("stream")
        val chunk = if (stream == "stdout" || stream == "stderr") update.str("chunk").orEmpty() else ""
        val detachable = update.bool("detachable")
        val sourceTruncated = update.bool("truncated") == true
        val commandIndex = update.int("commandIndex")
        val previousIndex = state.commandIndexes[toolCallId]
        val header = if (commandIndex != null && commandIndex > 0 && commandIndex != previousIndex) {
            "\n$ ${liveCommand(state, id, commandIndex, update.str("query"))}\n"
        } else ""
        val indexes = if (commandIndex != null) state.commandIndexes + (toolCallId to commandIndex) else state.commandIndexes
        if (chunk.isEmpty() && header.isEmpty() && detachable == null && !sourceTruncated) return state.copy(commandIndexes = indexes)
        return update(state, id) { message ->
            val appended = appendCappedCommandOutput(message.meta.toolOutput.orEmpty(), header + chunk)
            message.copy(
                meta = message.meta.copy(
                    toolOutput = appended.output,
                    toolOutputTruncated = message.meta.toolOutputTruncated || appended.truncated || sourceTruncated,
                    toolDetachable = detachable ?: message.meta.toolDetachable,
                ),
            )
        }.copy(commandIndexes = indexes)
    }

    /**
     * The `$ <command>` header of a later command's output: the command from the call's input on
     * the row (trial row 18), not the update's `query`, the kernel's echo capped at 200 characters.
     */
    private fun liveCommand(state: LiveProjection, id: String, commandIndex: Int, echo: String?): String {
        val input = state.messages.firstOrNull { it.id == id }?.let { parseObject(it.content)?.get("input") }
        return executedCommand(runCommandsExecuted(input), commandIndex, echo)
    }

    private fun toolFinished(state: LiveProjection, p: JsonObject, ctx: ProjectContext, at: Long): LiveProjection {
        val toolCallId = p.str("toolCallId") ?: return state
        val id = toolId(toolCallId)
        val output = p["output"]
        val isError = p.str("error") != null || SessionFactsReducer.extractError(output) != null
        val existing = state.messages.firstOrNull { it.id == id }
        if (existing == null) {
            val toolName = p.str("toolName") ?: "tool"
            val message = ChatMessage(
                id = id,
                sessionId = ctx.sessionId,
                role = Role.Tool,
                content = toolContent(toolName, null, output ?: JsonNull, isError),
                createdAt = at,
                meta = rowMeta(state, ctx).copy(toolName = toolName, toolCallId = toolCallId),
            )
            return closeOpen(state).copy(messages = state.messages + message)
        }
        return update(state, id) { message ->
            val payload = parseObject(message.content)
            val toolName = message.meta.toolName ?: payload?.str("toolName") ?: p.str("toolName") ?: "tool"
            message.copy(
                content = toolContent(toolName, payload?.get("input"), output ?: JsonNull, isError),
                meta = message.meta.copy(hookEventName = null),
            )
        }
    }

    /** `{"toolName","input","result"?,"isError"?}` (desktop tool payload). */
    fun toolContent(toolName: String, input: JsonElement?, result: JsonElement?, isError: Boolean?): String =
        HubJson.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("toolName", JsonPrimitive(toolName))
                put("input", input ?: JsonObject(emptyMap()))
                if (result != null) put("result", result)
                if (isError == true) put("isError", JsonPrimitive(true))
            },
        )

    private fun parseObject(raw: String): JsonObject? = runCatching { HubJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()

    private fun usageUpdated(state: LiveProjection, p: JsonObject): LiveProjection {
        val target = state.openAssistantId
            ?: state.messages.lastOrNull { it.role == Role.Assistant && it.meta.requestId == state.requestId && state.requestId != null }?.id
            ?: return state
        val delta = p.obj("delta") ?: return state
        return update(state, target) {
            it.copy(
                meta = it.meta.copy(
                    inputTokens = delta.long("inputTokens")?.toInt() ?: it.meta.inputTokens,
                    outputTokens = delta.long("outputTokens")?.toInt() ?: it.meta.outputTokens,
                    cacheReadTokens = delta.long("cacheReadTokens")?.toInt() ?: it.meta.cacheReadTokens,
                    totalCost = delta.double("totalCost") ?: it.meta.totalCost,
                ),
            )
        }
    }

    // ---- notices, queued prompts, run ends ---------------------------------------------------

    private fun notice(state: LiveProjection, p: JsonObject, event: HubEventEnvelope, ctx: ProjectContext, at: Long): LiveProjection {
        val message = p.str("message").orEmpty()
        val reason = p.str("reason")
        val key = event.sequence?.toString() ?: event.eventId ?: at.toString()
        if (reason == "api_error") {
            val id = "x:$key"
            if (state.messages.any { it.id == id }) return state
            val provider = ctx.openTurn?.providerId ?: ctx.sessionProviderId
            val auth = ctx.openTurn?.providerAuth ?: ctx.authFor(provider)
            return state.copy(messages = state.messages + errorRow(id, ctx.sessionId, message, state.requestId ?: ctx.openTurn?.requestId, provider, auth, at))
        }
        val id = "n:$key"
        if (state.messages.any { it.id == id }) return state
        val row = ChatMessage(
            id = id,
            sessionId = ctx.sessionId,
            role = Role.Status,
            content = message,
            createdAt = at,
            meta = Meta(noticeType = p.str("noticeType") ?: p.str("type") ?: p.str("level"), reason = reason),
        )
        return state.copy(messages = state.messages + row)
    }

    private fun promptSubmitted(state: LiveProjection, p: JsonObject, ctx: ProjectContext, at: Long): LiveProjection {
        val prompt = p.obj("prompt") ?: return state
        val promptId = prompt.str("id") ?: return state
        val queuedKey = SessionFactsReducer.queuedRunKey(promptId)
        val id = localId(queuedKey)
        if (state.messages.any { it.id == id }) return state
        val open = state.requestId
        // A steered prompt joins the running loop; a drained one starts a turn of its own, also
        // when the settled direct run's `run.completed` has not arrived yet (it follows).
        val startsTurn = open == null || ctx.previousRunSettled || SessionFactsReducer.isQueuedRunKey(open)
        val requestId = if (startsTurn) queuedKey else open
        val ending = open?.takeIf { startsTurn && !SessionFactsReducer.isQueuedRunKey(it) }
        val images = prompt.arr("userImages").orEmpty().mapIndexedNotNull { index, element ->
            (element as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.let { dataUrlImage("$promptId:$index", it) }
        }
        val message = ChatMessage(
            id = id,
            sessionId = ctx.sessionId,
            role = Role.User,
            content = prompt.str("prompt").orEmpty(),
            images = images,
            createdAt = at,
            meta = Meta(requestId = requestId, userRunSpan = 1),
        )
        val closed = closeOpen(state)
        if (!startsTurn) return closed.copy(messages = closed.messages + message, requestId = requestId)
        return closed.copy(
            messages = closed.messages + message,
            requestId = requestId, iteration = null, segment = 0, commandIndexes = emptyMap(), iterationOpen = false,
            nestedDepth = 0, endingRequestId = ending,
        )
    }

    /** The settled previous run's terminal arrived after the queued turn started: only the cursor flag clears. */
    private fun endPreviousRun(state: LiveProjection): LiveProjection = state.copy(endingRequestId = null)

    /**
     * A drained queued turn ended (its loop went idle; it never gets a `run.*`): the next rows
     * belong to no run until one starts. A direct run is left alone.
     */
    fun endQueuedRun(state: LiveProjection): LiveProjection =
        if (state.requestId?.let(SessionFactsReducer::isQueuedRunKey) == true) endRun(state) else state

    /**
     * `session.get` says the run ended but its terminal `run.*` fell outside a truncated replay:
     * the open rows close (no streaming cursor stays behind) and the next rows belong to no run.
     */
    fun endVerifiedRun(state: LiveProjection): LiveProjection = endRun(state)

    private fun runFailed(
        state: LiveProjection,
        p: JsonObject,
        event: HubEventEnvelope,
        ctx: ProjectContext,
        at: Long,
        requestIdOverride: String? = null,
    ): LiveProjection {
        val payload = RunFailedPayload(
            reason = p.str("reason") ?: "failed",
            error = p.str("error") ?: p.obj("error")?.str("message"),
            text = p.str("text"),
        )
        val requestId = requestIdOverride ?: ctx.openTurn?.requestId ?: state.requestId
        val key = requestId ?: event.sequence?.toString() ?: at.toString()
        val provider = ctx.openTurn?.providerId ?: ctx.sessionProviderId
        val auth = ctx.openTurn?.providerAuth ?: ctx.authFor(provider)
        return appendError(closeOpen(state), ctx.sessionId, key, requestId, payload.detail, provider, auth, at)
    }

    private fun endRun(state: LiveProjection): LiveProjection =
        closeOpen(state).copy(
            requestId = null, iteration = null, segment = 0, commandIndexes = emptyMap(), iterationOpen = false, nestedDepth = 0,
            endingRequestId = null,
        )

    private fun closeReasoning(state: LiveProjection): LiveProjection =
        if (state.openReasoningId == null) state else state.copy(openReasoningId = null, segment = state.segment + 1)

    private fun closeOpen(state: LiveProjection): LiveProjection {
        var s = closeReasoning(state)
        if (s.openAssistantId != null) s = s.copy(openAssistantId = null, segment = s.segment + 1)
        return s.copy(streamingMessageId = null)
    }

    private inline fun update(state: LiveProjection, id: String, transform: (ChatMessage) -> ChatMessage): LiveProjection {
        val index = state.messages.indexOfFirst { it.id == id }
        if (index < 0) return state
        val next = state.messages.toMutableList()
        next[index] = transform(next[index])
        return state.copy(messages = next)
    }

    // ---- media ------------------------------------------------------------------------------

    /** `GeneratedMedia[]` (`sdk/packages/shared/src/llms/media.ts`); malformed entries are skipped. */
    fun parseMedia(element: JsonElement?): List<GeneratedMedia> {
        val items = when (element) {
            is JsonArray -> element
            is JsonObject -> JsonArray(listOf(element))
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            val source = o.obj("source")
            val mediaSource = when (source?.str("type")) {
                "base64" -> source.str("data")?.let { MediaSource.Base64(it) }
                "url" -> source.str("url")?.let { MediaSource.Url(it) }
                "artifact" -> source.str("artifactId")?.let { MediaSource.Artifact(it) }
                else -> o.str("data")?.let { MediaSource.Base64(it) } ?: o.str("url")?.let { MediaSource.Url(it) }
            } ?: return@mapNotNull null
            val modality = when (o.str("modality")) {
                "image" -> MediaModality.Image
                "audio" -> MediaModality.Audio
                "video" -> MediaModality.Video
                else -> MediaModality.File
            }
            GeneratedMedia(
                id = o.str("id") ?: "media:${item.hashCode()}",
                modality = modality,
                mediaType = o.str("mediaType") ?: "application/octet-stream",
                source = mediaSource,
                name = o.str("name"),
                sizeBytes = o.long("sizeBytes"),
            )
        }
    }

    /** `data:image/png;base64,…` → [ChatImage]; null for anything that is not a base64 image data URL. */
    fun dataUrlImage(id: String, dataUrl: String): ChatImage? {
        val match = DATA_URL.matchEntire(dataUrl.trim()) ?: return null
        return ChatImage(id = id, mediaType = match.groupValues[1].lowercase(), data = match.groupValues[2])
    }

    private val DATA_URL = Regex("""data:(image/[a-zA-Z0-9.+-]+);base64,([\s\S]+)""")
}
