package dev.cline.termux.ui.chat.rows

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.cline.termux.BuildConfig
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatRenderItem
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.CheckpointRef
import dev.cline.termux.data.chat.GeneratedMedia
import dev.cline.termux.data.chat.MediaModality
import dev.cline.termux.data.chat.MediaSource
import dev.cline.termux.data.chat.REASON_INTERRUPTED
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.RunErrorStrings
import dev.cline.termux.data.chat.buildChatRenderItems
import dev.cline.termux.data.chat.buildPreviousTimestampMap
import dev.cline.termux.data.chat.buildUserRunCountMap
import dev.cline.termux.data.chat.formatRunError
import dev.cline.termux.data.chat.formatWorkDuration
import dev.cline.termux.data.chat.getThoughtDurationMilliseconds
import dev.cline.termux.data.chat.isDated
import dev.cline.termux.data.chat.isSystemSteeringMessage
import dev.cline.termux.data.chat.jsRound
import dev.cline.termux.data.session.ChatProjector
import dev.cline.termux.data.session.TurnStats
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.tools.ToolRowModel
import dev.cline.termux.data.tools.buildToolPresentation
import dev.cline.termux.data.tools.buildToolRowModel

/*
 * The pure rules behind the transcript rows (home-chat.md §3.3–§3.10), ported from the
 * desktop's `chat-messages.tsx` (row flags, last-conversation message, error banner rule),
 * `message-bubble.tsx` (action sets and their visibility), `reasoning-block.tsx` and
 * `agent-chat/index.tsx` (`formatThoughtLabel`, `formatWorkActivityLabel` as typed labels the
 * UI maps to string resources), and `generated-media.tsx` (which media can render inline).
 * Everything here is plain Kotlin and covered by `RowRulesTest`.
 */

// ---------------------------------------------------------------- transcript model

/** Which composable renders a row; also the LazyColumn `contentType`. */
enum class TranscriptRowType { User, Assistant, Error, Notice, System, Tools, Work, Run }

/**
 * Space above a row (DS §3.3): [Block] is the 8dp gap between transcript blocks, [Turn] adds
 * the 16dp a user message opens a new turn with (desktop `mt-4`), [Tight] is an answer pulled
 * up against the working rows it answers (desktop `-mt-2`), [None] is the first row.
 */
enum class TranscriptGap { None, Tight, Block, Turn }

/**
 * One LazyColumn item. [key] is the first message's `renderKey` (HC §3.3: a `Work`/`Run`/`Tools`
 * item uses its first message's), so it survives reconciliation and a live run folding into a
 * work row. [traversalIndex] orders TalkBack oldest → newest in a reversed list (HC §3.15).
 */
@Immutable
data class TranscriptRow(
    val key: String,
    val type: TranscriptRowType,
    val item: ChatRenderItem,
    val gapAbove: TranscriptGap,
    /** Desktop `isRunActive && itemIndex > lastUserItemIndex`: in-progress tools here spin. */
    val isRunActive: Boolean,
    /** An assistant answer directly under working rows (desktop `followsWorkingRows`). */
    val followsWorkingRows: Boolean,
    val traversalIndex: Float,
    /**
     * The facts of every message this row renders (its own and a run's / work block's
     * children), by message id. The rows read these instead of the whole [TranscriptModel], so
     * a streaming tick that changes one row leaves every other row equal and skipped (DS §6).
     */
    val facts: Map<String, MessageFacts> = emptyMap(),
) {
    fun factsOf(messageId: String): MessageFacts = facts[messageId] ?: MessageFacts.NONE
}

/**
 * What one message's row needs beyond the message itself, computed with the transcript model
 * on the pipeline's dispatcher (docs/07 §8: no JSON parsing or multi-MB scans on Main):
 * the desktop `ChatMessages` per-row flags, the reasoning text and duration, the tool row
 * model (payload parse, summary, capped command output) and the media classification.
 */
@Immutable
data class MessageFacts(
    /** The newest conversation row is this assistant answer (its actions stay pinned). */
    val isLastAssistant: Boolean = false,
    val isStreaming: Boolean = false,
    /** User rows: the run count for checkpoint numbering (desktop `userRunCountByMessage`). */
    val runCount: Int? = null,
    /** Older than the newest genuine user message: an interrupted row here loses "Resend". */
    val superseded: Boolean = false,
    /** Assistant rows: the joined reasoning text, and how long the thinking took. */
    val reasoning: ReasoningContent? = null,
    val reasoningDurationMs: Long? = null,
    /** Tool rows: the row model and its non-image output media. */
    val tool: ToolRowFacts? = null,
    /** Assistant rows: `message.media` as it renders (HC §3.4.8). */
    val media: List<MediaPresentation> = emptyList(),
    /** A11.9 Verbose: the totals of the turn this row is the final answer of (null in Brief). */
    val turnStats: TurnStats? = null,
) {
    companion object {
        val NONE = MessageFacts()
    }
}

/**
 * A tool row's model plus its non-image output media as they render (built off Main).
 * [withoutProceed] is the same row for a host that wired no `run.proceed_while_running`
 * (only built when [model] offers "Proceed while running", whose body section it drives).
 */
@Immutable
data class ToolRowFacts(val model: ToolRowModel, val media: List<MediaPresentation>, val withoutProceed: ToolRowModel? = null) {
    /** The model the row draws: [withoutProceed] when the host cannot proceed. */
    fun modelFor(proceedAvailable: Boolean): ToolRowModel = if (proceedAvailable) model else withoutProceed ?: model
}

/** Builds [ToolRowFacts] for [message]. The "Input" block shows in debug builds. */
fun toolRowFacts(message: ChatMessage, isRunActive: Boolean, showInputPreview: Boolean = BuildConfig.DEBUG): ToolRowFacts {
    val presentation = buildToolPresentation(message)
    val model = buildToolRowModel(presentation, isRunActive, proceedAvailable = true, showInputPreview = showInputPreview)
    val withoutProceed = if (model.canProceed) {
        buildToolRowModel(presentation, isRunActive, proceedAvailable = false, showInputPreview = showInputPreview)
    } else {
        null
    }
    val media = if (model.otherOutputMedia.isEmpty()) {
        emptyList()
    } else {
        presentGeneratedMedia(model.otherOutputMedia.mapIndexed { index, item -> item.asGeneratedMedia("${message.id}_tool_media_$index") })
    }
    return ToolRowFacts(model, media, withoutProceed)
}

/**
 * Keeps the expensive per-message facts between pipeline runs (one per transcript, used on
 * the pipeline's dispatcher only; not thread-safe). A running tool's message changes on every
 * live-output tick, so only that row is rebuilt; every unchanged row keeps its instance, which
 * also makes the rows' equality checks O(1).
 */
class RowFactsCache {
    private class ToolEntry(val message: ChatMessage, val runActive: Boolean, val facts: ToolRowFacts)
    private class MediaEntry(val media: List<GeneratedMedia>, val presentations: List<MediaPresentation>)

    private val tools = HashMap<String, ToolEntry>()
    private val media = HashMap<String, MediaEntry>()

    /** Builds counted for tests: how many tool rows were actually (re)built. */
    var toolBuilds: Int = 0
        private set

    fun tool(message: ChatMessage, isRunActive: Boolean, showInputPreview: Boolean): ToolRowFacts {
        val entry = tools[message.id]
        if (entry != null && entry.runActive == isRunActive && (entry.message === message || entry.message == message)) return entry.facts
        toolBuilds++
        val facts = toolRowFacts(message, isRunActive, showInputPreview)
        tools[message.id] = ToolEntry(message, isRunActive, facts)
        return facts
    }

    fun media(message: ChatMessage): List<MediaPresentation> {
        if (message.media.isEmpty()) return emptyList()
        val entry = media[message.id]
        if (entry != null && (entry.media === message.media || entry.media == message.media)) return entry.presentations
        val presentations = presentGeneratedMedia(message.media)
        media[message.id] = MediaEntry(message.media, presentations)
        return presentations
    }

    /** Drops entries of messages that left the transcript. */
    fun retain(ids: Set<String>) {
        tools.keys.retainAll(ids)
        media.keys.retainAll(ids)
    }
}

/** The transcript as rows plus the per-message facts the rows read (desktop `ChatMessages` memo values). */
@Immutable
data class TranscriptModel(
    /** Oldest first; the chat screen reverses them for its `reverseLayout` list. */
    val rows: List<TranscriptRow>,
    /** The newest message ignoring `Status` rows (desktop `lastConversationMessage`). */
    val lastConversationMessageId: String?,
    /** The newest error row (desktop `lastErrorMessage`), for the error banner rule. */
    val lastErrorMessage: ChatMessage?,
    val streamingMessageId: String?,
    /** User message id → its run count (checkpoint numbering, desktop `userRunCountByMessage`). */
    val runCountByMessageId: Map<String, Int>,
    /** Message id → timestamp of the previous dated message (desktop `previousTimestampByMessage`). */
    val previousTimestampByMessageId: Map<String, Long?>,
    /** Messages older than the newest genuine user message: an interrupted row there loses "Resend". */
    val supersededMessageIds: Set<String>,
) {
    fun isLastAssistantMessage(message: ChatMessage): Boolean =
        message.role == Role.Assistant && message.id == lastConversationMessageId

    fun isStreaming(message: ChatMessage): Boolean = message.id == streamingMessageId

    /** The facts of [messageId] wherever its row is (previews and tests; rows read their own [TranscriptRow.facts]). */
    fun factsOf(messageId: String): MessageFacts = rows.firstNotNullOfOrNull { it.facts[messageId] } ?: MessageFacts.NONE

    companion object {
        val EMPTY = TranscriptModel(emptyList(), null, null, null, emptyMap(), emptyMap(), emptySet())
    }
}

/**
 * The render pipeline (HC §1.5) plus the per-row flags of desktop `chat-messages.tsx` and every
 * row's [MessageFacts]. Runs on the pipeline's dispatcher (`TranscriptController` on
 * `ChatDeps.compute`); [cache] carries the tool and media facts between runs.
 */
fun buildTranscriptModel(
    messages: List<ChatMessage>,
    status: ChatStatus,
    streamingMessageId: String? = null,
    cache: RowFactsCache = RowFactsCache(),
    showInputPreview: Boolean = BuildConfig.DEBUG,
    /** A11.9 Verbose: answer row key (renderKey or id) → its turn's stats (`TurnRecords.statsByAnswer`); empty in Brief. */
    turnStats: Map<String, TurnStats> = emptyMap(),
): TranscriptModel {
    val items = buildChatRenderItems(messages, status)

    var lastConversation: ChatMessage? = null
    var lastError: ChatMessage? = null
    for (index in messages.indices.reversed()) {
        val message = messages[index]
        if (lastConversation == null && message.role != Role.Status) lastConversation = message
        if (lastError == null && message.role == Role.Error) lastError = message
        if (lastConversation != null && lastError != null) break
    }

    val lastUserItemIndex = items.indexOfLast {
        it is ChatRenderItem.Message && it.message.role == Role.User && !isSystemSteeringMessage(it.message)
    }
    val runActive = status.isBusy

    val rows = ArrayList<TranscriptRow>(items.size)
    items.forEachIndexed { index, item ->
        // Steering notes are machinery, never shown (desktop MessageBubble returns null).
        if (item is ChatRenderItem.Message && isSystemSteeringMessage(item.message)) return@forEachIndexed
        val previous = items.getOrNull(index - 1)
        val follows = item is ChatRenderItem.Message && item.message.role == Role.Assistant &&
            previous != null && previous !is ChatRenderItem.Message
        val type = rowTypeOf(item)
        val gap = when {
            rows.isEmpty() -> TranscriptGap.None
            type == TranscriptRowType.User -> TranscriptGap.Turn
            follows -> TranscriptGap.Tight
            else -> TranscriptGap.Block
        }
        rows += TranscriptRow(
            key = rowKeyOf(item),
            type = type,
            item = item,
            gapAbove = gap,
            isRunActive = runActive && index > lastUserItemIndex,
            followsWorkingRows = follows,
            traversalIndex = 0f,
        )
    }
    val lastUserMessageIndex = messages.indexOfLast { it.role == Role.User && !isSystemSteeringMessage(it) }
    val superseded = if (lastUserMessageIndex <= 0) emptySet() else messages.subList(0, lastUserMessageIndex).mapTo(HashSet()) { it.id }

    val runCounts = userRunCountsById(messages)
    val previous = buildPreviousTimestampMap(messages).entries.associate { (message, timestamp) -> message.id to timestamp }

    val facts = FactsBuilder(lastConversation?.id, streamingMessageId, runCounts, previous, superseded, cache, showInputPreview, turnStats)
    val last = rows.lastIndex
    val ordered = rows.mapIndexed { index, row ->
        row.copy(traversalIndex = (index - last).toFloat(), facts = facts.of(row.item, row.isRunActive))
    }
    cache.retain(messages.mapTo(HashSet()) { it.id })

    return TranscriptModel(
        rows = ordered,
        lastConversationMessageId = lastConversation?.id,
        lastErrorMessage = lastError,
        streamingMessageId = streamingMessageId,
        runCountByMessageId = runCounts,
        previousTimestampByMessageId = previous,
        supersededMessageIds = superseded,
    )
}

/** Collects the [MessageFacts] of one row's messages (recursing into run / work groups). */
private class FactsBuilder(
    private val lastConversationId: String?,
    private val streamingId: String?,
    private val runCounts: Map<String, Int>,
    private val previous: Map<String, Long?>,
    private val superseded: Set<String>,
    private val cache: RowFactsCache,
    private val showInputPreview: Boolean,
    private val turnStats: Map<String, TurnStats>,
) {
    fun of(item: ChatRenderItem, isRunActive: Boolean): Map<String, MessageFacts> {
        val out = HashMap<String, MessageFacts>()
        collect(item, isRunActive, out)
        return out
    }

    private fun collect(item: ChatRenderItem, isRunActive: Boolean, out: MutableMap<String, MessageFacts>) {
        when (item) {
            is ChatRenderItem.Tools -> item.messages.forEach { message ->
                out[message.id] = MessageFacts(tool = cache.tool(message, isRunActive, showInputPreview), superseded = message.id in superseded)
            }
            is ChatRenderItem.Message -> out[item.message.id] = messageFacts(item)
            is ChatRenderItem.Run -> item.items.forEach { collect(it, isRunActive, out) }
            is ChatRenderItem.Work -> item.items.forEach { collect(it, isRunActive, out) }
        }
    }

    private fun messageFacts(item: ChatRenderItem.Message): MessageFacts {
        val message = item.message
        val assistant = message.role == Role.Assistant
        val reasoning = if (assistant) reasoningContentOf(item.reasoningMessages) else null
        val duration = if (assistant && item.reasoningMessages.isNotEmpty()) {
            reasoningDurationMillis(item.reasoningMessages, previous[item.reasoningMessages.first().id])
        } else {
            null
        }
        return MessageFacts(
            isLastAssistant = assistant && message.id == lastConversationId,
            isStreaming = message.id == streamingId,
            runCount = runCounts[message.id],
            superseded = message.id in superseded,
            reasoning = reasoning,
            reasoningDurationMs = duration,
            media = if (assistant) cache.media(message) else emptyList(),
            turnStats = if (assistant && turnStats.isNotEmpty()) turnStats[message.renderKey] ?: turnStats[message.id] else null,
        )
    }
}

/** Desktop `userRunCountByMessage` by message id: the run count of each span-1 user message. */
fun userRunCountsById(messages: List<ChatMessage>): Map<String, Int> =
    buildUserRunCountMap(messages).entries.associate { (message, count) -> message.id to count }

fun rowTypeOf(item: ChatRenderItem): TranscriptRowType = when (item) {
    is ChatRenderItem.Tools -> TranscriptRowType.Tools
    is ChatRenderItem.Work -> TranscriptRowType.Work
    is ChatRenderItem.Run -> TranscriptRowType.Run
    is ChatRenderItem.Message -> when (item.message.role) {
        Role.User -> TranscriptRowType.User
        Role.Assistant -> TranscriptRowType.Assistant
        Role.Error -> TranscriptRowType.Error
        Role.Status -> TranscriptRowType.Notice
        Role.System, Role.Tool -> TranscriptRowType.System
    }
}

/** The LazyColumn key: the item's first message's renderKey (HC §3.3, §3.4.5). */
fun rowKeyOf(item: ChatRenderItem): String = when (item) {
    is ChatRenderItem.Tools -> item.messages.first().renderKey
    is ChatRenderItem.Message -> item.message.renderKey
    is ChatRenderItem.Work -> item.id
    is ChatRenderItem.Run -> item.id
}

/** Whether a child of a run/work group is rendered (steering notes never are). */
fun isRenderedWorkingChild(item: ChatRenderItem): Boolean =
    !(item is ChatRenderItem.Message && isSystemSteeringMessage(item.message))

// ---------------------------------------------------------------- message text

/** Desktop `formatChatMessageContent` for user and assistant rows (errors go through [formatRunError]). */
fun displayContentOf(message: ChatMessage): String = when (message.role) {
    Role.User -> UserInputFormat.formatDisplayUserInput(message.content)
    else -> message.content
}

/** The error row's sentence: the interrupted copy, or `formatRunError` (HC §3.4.7). */
fun errorRowText(message: ChatMessage, strings: RunErrorStrings, interruptedText: String): String =
    if (message.meta.reason == REASON_INTERRUPTED) interruptedText
    else formatRunError(message.content, message.meta.providerId, message.meta.providerAuth, strings)

// ---------------------------------------------------------------- reasoning

/** The joined thinking text of a message's reasoning messages (desktop `getReasoningProps`). */
@Immutable
data class ReasoningContent(val text: String, val redacted: Boolean) {
    /** Nothing to show: the desktop `ReasoningBlock` returns null. */
    val isEmpty: Boolean get() = text.isEmpty() && !redacted

    /** Only a redaction marker: the body reads "[redacted]". */
    val showsRedactedPlaceholder: Boolean get() = text.isEmpty() && redacted
}

fun reasoningContentOf(reasoningMessages: List<ChatMessage>): ReasoningContent = ReasoningContent(
    text = reasoningMessages.mapNotNull { it.reasoning?.trim()?.takeIf(String::isNotEmpty) }.joinToString("\n\n"),
    redacted = reasoningMessages.any { it.reasoningRedacted },
)

/**
 * "Thought for Ns" duration. Live rows know when thinking finished (`reasoning.finished` →
 * `meta.thoughtEndedAt`): finished − first delta (DS §4.1 #3). History rows fall back to the
 * desktop rule: the last reasoning message's time minus the previous message's.
 */
fun reasoningDurationMillis(reasoningMessages: List<ChatMessage>, previousTimestamp: Long?): Long? {
    val first = reasoningMessages.firstOrNull() ?: return null
    val last = reasoningMessages.last()
    val ended = last.meta.thoughtEndedAt
    if (ended != null && isDated(first.createdAt) && ended >= first.createdAt) return ended - first.createdAt
    return getThoughtDurationMilliseconds(previousTimestamp, last.createdAt)
}

/** `formatThoughtLabel` as a typed label: the UI maps it to "Thinking" / "Thought for %1$ds". */
sealed interface ThoughtLabel {
    data object Thinking : ThoughtLabel
    data class ThoughtFor(val seconds: Long) : ThoughtLabel
}

/** `ThinkingBlock`: "Thinking" while streaming or while the duration is unknown. */
fun thoughtLabel(streaming: Boolean, durationMilliseconds: Long?): ThoughtLabel {
    if (streaming || durationMilliseconds == null) return ThoughtLabel.Thinking
    val seconds = if (durationMilliseconds == 0L) 0L else maxOf(1L, jsRound(durationMilliseconds / 1000.0))
    return ThoughtLabel.ThoughtFor(seconds)
}

// ---------------------------------------------------------------- work row

/** `formatWorkActivityLabel` as a typed label (duration keeps the desktop's unlocalised "4m 12s"). */
sealed interface WorkLabel {
    data class Both(val duration: String, val toolCalls: Int) : WorkLabel
    data class Duration(val duration: String) : WorkLabel
    data class Calls(val toolCalls: Int) : WorkLabel
    data object Worked : WorkLabel
}

fun workLabel(durationMilliseconds: Long?, toolCallCount: Int?): WorkLabel {
    val duration = durationMilliseconds?.takeIf { it >= 0 }?.let(::formatWorkDuration)
    val calls = toolCallCount?.takeIf { it != 0 }
    return when {
        duration != null && calls != null -> WorkLabel.Both(duration, calls)
        duration != null -> WorkLabel.Duration(duration)
        calls != null -> WorkLabel.Calls(calls)
        else -> WorkLabel.Worked
    }
}

// ---------------------------------------------------------------- message actions

/** An action's failure; a null [message] shows the desktop fallback sentence for that action. */
@Immutable
data class ActionError(val message: String? = null)

/**
 * The host-owned state of the versioning actions (desktop `chat-messages.tsx` keeps the same
 * maps): which message has an edit/restore/resend in flight, their errors, and the session
 * flags that disable them. Copy is row-local (its 1.6 s check mark). Message rows offer no fork
 * (decided A16): the whole-session fork lives in the session menus.
 */
@Immutable
data class MessageActionsState(
    /** Status starting, running or stopping (desktop `editDisabled`). */
    val busy: Boolean = false,
    val switching: Boolean = false,
    /** Another restore/edit is pending in this session (`sessionVersioningPending`). */
    val versioningPending: Boolean = false,
    /**
     * `snapshot.checkpoint.history` by run count (CT C.4 rule 3): a user message whose run
     * count has an entry here has an **exact** checkpoint and shows "Restore checkpoint".
     */
    val checkpointRuns: Map<Int, CheckpointRef> = emptyMap(),
    val editPendingId: String? = null,
    val editErrors: Map<String, ActionError> = emptyMap(),
    val restorePendingId: String? = null,
    val restoreErrors: Map<String, ActionError> = emptyMap(),
    /**
     * The user message whose restore / edit waits for the Hub to finish draining (CT §1.6):
     * it keeps its pending icon and shows "Cline Hub is restarting. Waiting..." under it.
     */
    val drainingMessageId: String? = null,
    /** Interrupted rows whose turn prompt is recoverable (HC §3.4.7). */
    val resendableIds: Set<String> = emptySet(),
    val resendPendingId: String? = null,
) {
    val versioningDisabled: Boolean get() = busy || switching || versioningPending
}

/** The user bubble's action row (desktop `shouldRenderUserActions` and its children). */
@Immutable
data class UserActionSet(
    val edit: Boolean,
    val editEnabled: Boolean,
    val editPending: Boolean,
    val editError: ActionError?,
    val restore: Boolean,
    /** The exact checkpoint "Restore checkpoint" restores to (null when [restore] is false). */
    val checkpoint: CheckpointRef? = null,
    val restoreEnabled: Boolean,
    val restorePending: Boolean,
    val restoreError: ActionError?,
    /** The pending restore / edit waits for a Hub drain (CT §1.6): the line under the row. */
    val draining: Boolean = false,
    /** Pending or errored actions keep the row visible (desktop `keepUserActionsVisible`). */
    val keepVisible: Boolean,
    /** Why edit/restore are unavailable (CT C.3): the icons explain it instead of going inert. */
    val blocked: VersioningBlock? = null,
)

/** CT C.3: the session is busy or switching, or another restore/edit is pending. */
enum class VersioningBlock { Busy, InProgress }

/**
 * [canEdit] / [canRestore]: the host wired the action (desktop `onEditMessage` /
 * `onRestoreCheckpoint`). Edit also needs a run count, non-blank text and checkpoints (§3.13);
 * Restore needs an exact checkpoint: `meta.checkpoint`, or the session history entry for
 * the message's run count ([MessageActionsState.checkpointRuns]).
 */
fun userActionSet(
    message: ChatMessage,
    displayText: String,
    runCount: Int?,
    state: MessageActionsState,
    canEdit: Boolean,
    canRestore: Boolean,
): UserActionSet {
    val editPending = state.editPendingId == message.id
    val restorePending = state.restorePendingId == message.id
    val editError = state.editErrors[message.id]
    val restoreError = state.restoreErrors[message.id]
    // CT C.3 (supersedes HC §3.13): Edit needs no checkpoint; without one it forks chat-only (C.6).
    val edit = canEdit && runCount != null && runCount != 0 && displayText.isNotBlank()
    // runCount is set only for span-1 user messages (buildUserRunCountMap), so a match is exact.
    val checkpoint = message.meta.checkpoint ?: runCount?.let { state.checkpointRuns[it] }
    val restore = canRestore && checkpoint != null
    return UserActionSet(
        edit = edit,
        editEnabled = !state.versioningDisabled && !editPending,
        editPending = editPending,
        editError = editError,
        restore = restore,
        checkpoint = checkpoint?.takeIf { restore },
        restoreEnabled = !state.versioningDisabled && !restorePending,
        restorePending = restorePending,
        restoreError = restoreError,
        draining = state.drainingMessageId == message.id,
        keepVisible = editPending || restorePending || editError != null || restoreError != null,
        blocked = when {
            state.busy || state.switching -> VersioningBlock.Busy
            state.versioningPending -> VersioningBlock.InProgress
            else -> null
        },
    )
}

/**
 * The user bubble's long-press menu in order (HC §3.4.1): [id] keys the menu item. Edit reads
 * "Edit and restart from here" (CT DS-A6), since it starts a new session from this message.
 */
enum class UserMenuAction(val id: String, @StringRes val label: Int) {
    Copy("copy", R.string.msg_menu_copy),
    Edit("edit", R.string.checkpoint_menu_edit),
    Restore("restore", R.string.msg_restore),
    Select("select", R.string.msg_select_text),
}

/** The long-press menu's entries: Edit and Restore only while they can run ([UserActionSet]). */
fun userMenuActions(canEdit: Boolean, canRestore: Boolean): List<UserMenuAction> =
    UserMenuAction.entries.filter {
        when (it) {
            UserMenuAction.Edit -> canEdit
            UserMenuAction.Restore -> canRestore
            UserMenuAction.Copy, UserMenuAction.Select -> true
        }
    }

/**
 * The assistant action row (desktop `shouldRenderAssistantActions`, `keepAssistantActionsVisible`):
 * copy, the time and the Verbose stats. The desktop row also forks the whole session; the app
 * keeps that fork in the session menus only (decided A16, one place per function).
 */
@Immutable
data class AssistantActionSet(
    /** The row exists at all: not streaming, not an error, non-blank content. */
    val visibleAtAll: Boolean,
    /** Always shown: the last conversation row. */
    val pinned: Boolean,
)

fun assistantActionSet(message: ChatMessage, isStreaming: Boolean, isLastAssistantMessage: Boolean): AssistantActionSet =
    AssistantActionSet(
        visibleAtAll = message.role == Role.Assistant && !isStreaming && message.content.isNotBlank(),
        pinned = isLastAssistantMessage,
    )

/** The interrupted row's "Resend" (HC §3.4.7): recoverable prompt, and no newer user message yet. */
fun showsResend(message: ChatMessage, model: TranscriptModel, state: MessageActionsState): Boolean =
    showsResend(message, superseded = message.id in model.supersededMessageIds, state = state)

/** [showsResend] from the row's own facts ([MessageFacts.superseded]). */
fun showsResend(message: ChatMessage, superseded: Boolean, state: MessageActionsState): Boolean =
    message.role == Role.Error &&
        message.meta.reason == REASON_INTERRUPTED &&
        message.id in state.resendableIds &&
        !superseded

// ---------------------------------------------------------------- notices

/**
 * SessionNotice row icons (HC §3.4.6). [RecoverableError]: a recovery notice about a recoverable
 * mid-run error (A11.9; only Verbose shows those rows), drawn as a muted error row. It waits for
 * a Hub that sends such notices; no kernel up to cli-v3.0.69 does (`ChatProjector.RECOVERABLE_ERROR_REASONS`).
 */
enum class NoticeIcon { Info, Recovery, Stop, RecoverableError }

private val COMPACTION_REASONS = setOf("auto_compaction", "manual_compaction", "compaction_budget_emergency")

fun noticeIconOf(noticeType: String?, reason: String?): NoticeIcon = when {
    reason in COMPACTION_REASONS -> NoticeIcon.Recovery
    noticeType == "recovery" && reason in ChatProjector.RECOVERABLE_ERROR_REASONS -> NoticeIcon.RecoverableError
    noticeType == "recovery" -> NoticeIcon.Recovery
    noticeType == "stop" -> NoticeIcon.Stop
    else -> NoticeIcon.Info
}

// ---------------------------------------------------------------- generated media

/** `SUPPORTED_IMAGE_MEDIA_TYPES` (sdk/packages/shared/src/llms/media.ts). */
val SUPPORTED_IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

/** `DEFAULT_MAX_IMAGE_DECODED_BYTES`. */
const val MAX_IMAGE_DECODED_BYTES = 6L * 1024 * 1024

/** `DEFAULT_MAX_TOTAL_MEDIA_BYTES`: the base64 length limit of one inline item and the per-message total. */
const val MAX_TOTAL_MEDIA_BYTES = 8L * 1024 * 1024

private val MEDIA_TYPE_PATTERN = Regex("^[a-z0-9][a-z0-9!#$&^_.+-]*/[a-z0-9][a-z0-9!#$&^_.+-]*$", RegexOption.IGNORE_CASE)

/**
 * `isCanonicalBase64`: padded standard alphabet, no whitespace, at most two `=` and only at the
 * end. A loop, not a regex: inline media runs to megabytes and a repeated regex group recurses
 * once per quantum on the JVM.
 */
fun isCanonicalBase64(data: String): Boolean {
    if (data.length % 4 != 0) return false
    val padding = when {
        data.endsWith("==") -> 2
        data.endsWith("=") -> 1
        else -> 0
    }
    val end = data.length - padding
    for (index in 0 until end) {
        val c = data[index]
        val ok = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') || c == '+' || c == '/'
        if (!ok) return false
    }
    return true
}

/** Bytes a canonical base64 string decodes to. */
fun base64DecodedSize(data: String): Long {
    if (data.isEmpty()) return 0
    val padding = data.takeLast(2).count { it == '=' }
    return data.length / 4L * 3 - padding
}

/** `renderableMediaType` of generated-media.tsx; null when the item cannot be shown inline. */
fun renderableMediaType(modality: MediaModality, mediaType: String): String? {
    val type = mediaType.trim().lowercase()
    if (!MEDIA_TYPE_PATTERN.matches(type)) return null
    return when (modality) {
        MediaModality.Image -> type.takeIf { it in SUPPORTED_IMAGE_MEDIA_TYPES }
        MediaModality.Audio -> type.takeIf { it.startsWith("audio/") }
        MediaModality.Video -> type.takeIf { it.startsWith("video/") }
        MediaModality.File -> type
    }
}

/** How one generated media item renders (HC §3.4.8). */
sealed interface MediaPresentation {
    val media: GeneratedMedia

    /** Inline image: thumbnail + lightbox. */
    data class Image(override val media: GeneratedMedia, val data: String, val mediaType: String) : MediaPresentation

    /** Audio, video or file bytes: a row with "Open". */
    data class Openable(override val media: GeneratedMedia, val data: String, val mediaType: String) : MediaPresentation

    /** "Generated <modality> requires a trusted remote-media resolver." */
    data class Remote(override val media: GeneratedMedia) : MediaPresentation

    /** "Generated <modality> is stored as an artifact (<artifactId>)." */
    data class StoredArtifact(override val media: GeneratedMedia, val artifactId: String) : MediaPresentation

    /** "Generated <modality> could not be rendered." */
    data class Unrenderable(override val media: GeneratedMedia) : MediaPresentation
}

/**
 * The fallback sentences, one resource per modality (DS §8.2.3): the desktop splices its
 * lower-case modality word into "Generated <modality> …", which a translation cannot inflect.
 */
@StringRes
fun MediaModality.remoteRes(): Int = when (this) {
    MediaModality.Image -> R.string.media_remote_image
    MediaModality.Audio -> R.string.media_remote_audio
    MediaModality.Video -> R.string.media_remote_video
    MediaModality.File -> R.string.media_remote_file
}

/** "Generated <modality> is stored as an artifact (%1$s)." with %1$s = the artifact id. */
@StringRes
fun MediaModality.artifactRes(): Int = when (this) {
    MediaModality.Image -> R.string.media_artifact_image
    MediaModality.Audio -> R.string.media_artifact_audio
    MediaModality.Video -> R.string.media_artifact_video
    MediaModality.File -> R.string.media_artifact_file
}

/** "Generated <modality> could not be rendered." */
@StringRes
fun MediaModality.unrenderableRes(): Int = when (this) {
    MediaModality.Image -> R.string.media_unrenderable_image
    MediaModality.Audio -> R.string.media_unrenderable_audio
    MediaModality.Video -> R.string.media_unrenderable_video
    MediaModality.File -> R.string.media_unrenderable_file
}

/** Classifies a message's media in order; the decoded total across the message stays within 8 MiB. */
fun presentGeneratedMedia(media: List<GeneratedMedia>): List<MediaPresentation> {
    var total = 0L
    return media.map { item ->
        when (val source = item.source) {
            is MediaSource.Url -> MediaPresentation.Remote(item)
            is MediaSource.Artifact -> MediaPresentation.StoredArtifact(item, source.artifactId)
            is MediaSource.Base64 -> {
                val type = renderableMediaType(item.modality, item.mediaType)
                val data = source.data
                val size = if (isCanonicalBase64(data)) base64DecodedSize(data) else -1L
                val fits = size >= 0 && data.length <= MAX_TOTAL_MEDIA_BYTES &&
                    total + size <= MAX_TOTAL_MEDIA_BYTES &&
                    (item.modality != MediaModality.Image || size <= MAX_IMAGE_DECODED_BYTES)
                if (type == null || !fits) {
                    MediaPresentation.Unrenderable(item)
                } else {
                    total += size
                    if (item.modality == MediaModality.Image) MediaPresentation.Image(item, data, type)
                    else MediaPresentation.Openable(item, data, type)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- transport line and error banner

/** The chat's socket state as the transcript shows it (HC §1.6 KernelLine, §3.9). */
enum class ChatTransportState { Connected, Connecting, Reconnecting, Unavailable, TermuxAsleep, Draining }

/** Desktop `chatTransportState !== "connected" && !shouldShowErrorBanner`. */
fun transportLineVisible(state: ChatTransportState, errorBannerShown: Boolean): Boolean =
    state != ChatTransportState.Connected && !errorBannerShown

/**
 * Desktop `shouldShowErrorBanner`: an error is set, and either it carries an action, or no error
 * row exists, or the newest error row does not already say the same thing.
 */
fun shouldShowErrorBanner(error: String?, hasAction: Boolean, lastErrorMessage: ChatMessage?, strings: RunErrorStrings): Boolean {
    if (error.isNullOrEmpty()) return false
    if (hasAction || lastErrorMessage == null) return true
    val providerId = lastErrorMessage.meta.providerId
    val auth = lastErrorMessage.meta.providerAuth
    return formatRunError(lastErrorMessage.content, providerId, auth, strings) != formatRunError(error, providerId, auth, strings)
}

/** The banner repeats the text only when the newest error row does not carry exactly it (desktop). */
fun errorBannerShowsText(error: String, lastErrorMessage: ChatMessage?): Boolean = lastErrorMessage?.content != error
