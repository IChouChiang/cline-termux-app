package dev.cline.termux.data.checkpoint

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.CheckpointRef
import dev.cline.termux.data.sessions.formatDisplayUserInput
import dev.cline.termux.data.tools.asRecord
import dev.cline.termux.data.tools.collapseWhitespace
import dev.cline.termux.data.tools.jsNumber
import dev.cline.termux.data.tools.jsString
import java.text.BreakIterator
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Which messages have checkpoints (design/screens/changes-tools-checkpoints.md C.4). Ports:
 * - sdk/packages/core/src/session/user-run-messages.ts `getUserRunSpan`: tool-result-only
 *   and synthetic user messages count 0 runs, a compaction summary spans the runs it folded;
 * - sdk/packages/core/src/session/checkpoint-restore.ts `readSessionCheckpointHistory`,
 *   `findCheckpointForRun`, `trimMessagesToCheckpoint`, `trimMessagesBeforeUserRun`,
 *   `createRestoredCheckpointMetadata`, with their error texts verbatim;
 * - shared/src/parse/string.ts `truncateStr` for the 60-character previews (grapheme-safe).
 * Pure; [CheckpointIndexCache] memoizes one build per (lastSequence, canonicalCount).
 */

private val SYNTHETIC_USER_MESSAGE_KINDS = setOf(
    "auto_compaction",
    "compaction_budget_emergency",
    "completion_reminder",
    "loop_detection_notice",
    "manual_compaction",
    "mistake_stop_notice",
    "recovery_notice",
)

private const val ACT_MODE_CONTINUATION = "The user approved switching to act mode. Continue with the approved plan now."

/** The fields of a persisted message the run walk reads (`MessageLike`). */
@Immutable
data class RunMessage(
    val id: String?,
    val role: String?,
    /** A string, a content-block array, or null when the consumer projected role and metadata only. */
    val content: JsonElement?,
    val metadata: JsonObject?,
) {
    companion object {
        /** One `session.messages` item. */
        fun fromJson(message: JsonObject): RunMessage = RunMessage(
            id = message["id"].jsString(),
            role = message["role"].jsString(),
            content = message["content"],
            metadata = message["metadata"].asRecord(),
        )

        /** A plain user prompt (text content). */
        fun userText(id: String?, text: String, metadata: JsonObject? = null): RunMessage =
            RunMessage(id, "user", JsonPrimitive(text), metadata)
    }
}

private fun readStoredUserRunSpan(metadata: JsonObject?): Int? {
    val span = metadata?.get("userRunSpan").jsNumber() ?: return null
    return if (span == Math.rint(span) && span >= 0) span.toInt() else null
}

private data class VisibleContent(val hasAttachment: Boolean, val text: String)

private fun readVisibleUserContent(message: RunMessage): VisibleContent {
    message.content.jsString()?.let { return VisibleContent(false, it) }
    val blocks = message.content as? JsonArray ?: return VisibleContent(false, "unknown")
    var hasAttachment = false
    var hasToolResult = false
    val text = mutableListOf<String>()
    for (item in blocks) {
        val block = item.asRecord() ?: continue
        when (block["type"].jsString()) {
            "text" -> block["text"].jsString()?.let { text += it }
            "tool_result", "tool-result" -> hasToolResult = true
            "file", "image" -> hasAttachment = true
        }
    }
    // Media in tool results belongs to the tool output, not a visible prompt.
    return VisibleContent(hasAttachment && !hasToolResult, text.joinToString("\n"))
}

private fun isSyntheticContinuationPrompt(text: String): Boolean {
    val normalized = formatDisplayUserInput(text)
    return normalized.startsWith("[TASK RESUMPTION]") || normalized == ACT_MODE_CONTINUATION
}

/** `getUserRunSpan`: how many absolute root runs a persisted message represents. */
fun getUserRunSpan(message: RunMessage): Int {
    if (message.role != "user") return 0
    val metadata = message.metadata
    val kind = metadata?.get("kind").jsString()
    val storedSpan = readStoredUserRunSpan(metadata)
    if (kind == "compaction" || kind == "compaction_summary") return storedSpan ?: 1
    if (storedSpan != null) return storedSpan
    if (kind != null && kind in SYNTHETIC_USER_MESSAGE_KINDS) return 0
    val content = readVisibleUserContent(message)
    if (!content.hasAttachment && formatDisplayUserInput(content.text).isEmpty()) return 0
    if (!content.hasAttachment && isSyntheticContinuationPrompt(content.text)) return 0
    return 1
}

/** `countUserRunMessages`. */
fun countUserRunMessages(messages: List<RunMessage>): Int = messages.sumOf(::getUserRunSpan)

/** The text a user message displays (its text blocks, unwrapped). */
fun displayText(message: RunMessage): String = formatDisplayUserInput(readVisibleUserContent(message).text.takeUnless { it == "unknown" } ?: "")

/** `readSessionCheckpointHistory` from `metadata.checkpoint.history` (or a snapshot's `checkpoint`). */
fun readCheckpointHistory(checkpoint: JsonObject?): List<CheckpointRef> {
    val history = checkpoint?.get("history") as? JsonArray ?: return emptyList()
    return history.mapNotNull { element ->
        val entry = element.asRecord() ?: return@mapNotNull null
        val ref = (entry["ref"].jsString() ?: entry["ref"]?.let { (it as? JsonPrimitive)?.content }).orEmpty().trim()
        val createdAt = checkpointCreatedAt(entry["createdAt"])
        val runCount = entry["runCount"].jsNumber()
        if (ref.isEmpty() || createdAt == null || !createdAt.isFinite() || runCount == null || runCount != Math.rint(runCount) || runCount < 1) {
            return@mapNotNull null
        }
        val kind = entry["kind"].jsString()?.takeIf { it == "stash" || it == "commit" }
        CheckpointRef(ref = ref, createdAt = createdAt.toLong(), runCount = runCount.toInt(), kind = kind)
    }
}

/**
 * `Number(entry.createdAt ?? 0)`: a number, or a numeric string (an older app build wrote the
 * restored history's `createdAt` as a string; core reads it with `Number`, so must the app).
 * A blank string is 0, as in JS; anything else that is not numeric is rejected.
 */
private fun checkpointCreatedAt(value: JsonElement?): Double? {
    if (value == null || value is JsonNull) return 0.0
    val primitive = value as? JsonPrimitive ?: return null
    if (!primitive.isString) return primitive.jsNumber()
    val text = primitive.content.trim()
    return if (text.isEmpty()) 0.0 else text.toDoubleOrNull()
}

/** History from a session record's metadata (`metadata.checkpoint.history`). */
fun readSessionCheckpointHistory(sessionMetadata: JsonObject?): List<CheckpointRef> =
    readCheckpointHistory(sessionMetadata?.get("checkpoint").asRecord())

/** `findCheckpointForRun`: the highest entry with `runCount ≤` [runCount]. */
fun findCheckpointForRun(history: List<CheckpointRef>, runCount: Int): CheckpointRef? =
    history.fold(null as CheckpointRef?) { best, entry ->
        when {
            entry.runCount > runCount -> best
            best == null || entry.runCount > best.runCount -> entry
            else -> best
        }
    }

/** `CheckpointMetadata` written on a restored session: the history up to the run, latest last. */
@Immutable
data class RestoredCheckpointMetadata(val latest: CheckpointRef, val history: List<CheckpointRef>)

/** `createRestoredCheckpointMetadata`. */
fun createRestoredCheckpointMetadata(history: List<CheckpointRef>, runCount: Int): RestoredCheckpointMetadata? {
    val kept = history.filter { it.runCount <= runCount }
    val latest = kept.lastOrNull() ?: return null
    return RestoredCheckpointMetadata(latest, kept)
}

/** A run that cannot be resolved; [message] is the kernel's text verbatim. */
class CheckpointRunException(message: String) : IllegalStateException(message)

private fun <T> findUserRunMessage(messages: List<T>, runCount: Int, span: (T) -> Int): Pair<Int, Int> {
    var userRunCount = 0
    for ((index, message) in messages.withIndex()) {
        val messageSpan = span(message)
        if (messageSpan < 1) continue
        val firstRunCount = userRunCount + 1
        userRunCount += messageSpan
        if (userRunCount == runCount) return index to messageSpan
        if (userRunCount > runCount) {
            throw CheckpointRunException("Run $runCount is folded into a compacted message spanning runs $firstRunCount-$userRunCount")
        }
    }
    throw CheckpointRunException("Could not find user message for run $runCount")
}

/** `trimMessagesToCheckpoint`: everything up to and including run [runCount]'s user message. */
fun <T> trimMessagesToCheckpoint(messages: List<T>, runCount: Int, span: (T) -> Int): List<T> {
    val (index, _) = findUserRunMessage(messages, runCount, span)
    return messages.subList(0, index + 1).toList()
}

/** `trimMessagesBeforeUserRun`: everything before run [runCount]'s user message (span 1 only). */
fun <T> trimMessagesBeforeUserRun(messages: List<T>, runCount: Int, span: (T) -> Int): List<T> {
    val (index, messageSpan) = findUserRunMessage(messages, runCount, span)
    if (messageSpan != 1) {
        throw CheckpointRunException("Cannot fork before run $runCount because it is folded into a compacted message")
    }
    return messages.subList(0, index).toList()
}

fun trimMessagesToCheckpoint(messages: List<RunMessage>, runCount: Int): List<RunMessage> =
    trimMessagesToCheckpoint(messages, runCount, ::getUserRunSpan)

fun trimMessagesBeforeUserRun(messages: List<RunMessage>, runCount: Int): List<RunMessage> =
    trimMessagesBeforeUserRun(messages, runCount, ::getUserRunSpan)

/** `truncateStr` that never splits a grapheme: at most [maxLen] UTF-16 units including "…". */
fun truncatePreview(text: String, maxLen: Int = PREVIEW_MAX_LENGTH): String {
    if (text.length <= maxLen) return text
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var cut = 0
    var next = iterator.next()
    while (next != BreakIterator.DONE && next <= maxLen - 1) {
        cut = next
        next = iterator.next()
    }
    return "${text.substring(0, cut)}…"
}

/** C2 / C3 preview width (CLI `truncateStr(..., 60)`). */
const val PREVIEW_MAX_LENGTH = 60

/** One user message of the walk. */
@Immutable
data class UserRunEntry(
    /** Position in the message list. */
    val index: Int,
    val messageId: String?,
    /** Runs this message represents (0 = not a run, >1 = compaction summary). */
    val span: Int,
    /** Cumulative run count at this message (the run it ends). */
    val runCount: Int,
    /** Display text, whitespace collapsed (not truncated). */
    val preview: String,
)

/** A C3 row: a restorable user message and the checkpoint it resolves to. */
@Immutable
data class RestorableMessage(val entry: UserRunEntry, val checkpoint: CheckpointRef) {
    /** C3 row time: the matched entry's `createdAt` (CLI). */
    val time: Long get() = checkpoint.createdAt
    val previewLine: String get() = truncatePreview(entry.preview)
}

/** The built index for one session. */
@Immutable
class CheckpointIndex(val history: List<CheckpointRef>, val runs: List<UserRunEntry>) {
    private val byRunCount: Map<Int, CheckpointRef> = history.associateBy { it.runCount }
    private val byMessageId: Map<String, UserRunEntry> = runs.mapNotNull { run -> run.messageId?.let { it to run } }.toMap()

    /** C1 undo icon and menu item: span exactly 1 and an entry with exactly this run count. */
    fun hasExactCheckpoint(entry: UserRunEntry): Boolean = entry.span == 1 && entry.runCount in byRunCount

    fun exactCheckpoint(entry: UserRunEntry): CheckpointRef? = if (entry.span == 1) byRunCount[entry.runCount] else null

    /** C2 workspace option and C3 rows: `findCheckpointForRun`. */
    fun checkpointAtOrBefore(runCount: Int): CheckpointRef? = findCheckpointForRun(history, runCount)

    /** Edit availability: span 1 and a non-blank display text. Folded messages have no actions. */
    fun canEdit(entry: UserRunEntry): Boolean = entry.span == 1 && entry.preview.isNotBlank()

    fun entryForMessage(messageId: String): UserRunEntry? = byMessageId[messageId]

    fun entryAt(index: Int): UserRunEntry? = runs.firstOrNull { it.index == index }

    /** C3: every user message with span 1, a non-blank preview and a checkpoint at or before it, oldest first. */
    fun restorableMessages(): List<RestorableMessage> = runs.mapNotNull { entry ->
        if (entry.span != 1 || entry.preview.isBlank()) return@mapNotNull null
        checkpointAtOrBefore(entry.runCount)?.let { RestorableMessage(entry, it) }
    }

    /** The "Restore to:" line of C2 for a message. */
    fun previewFor(entry: UserRunEntry): String = truncatePreview(entry.preview)

    companion object {
        /** Walk [messages] in order and give every run message its run count. */
        fun build(history: List<CheckpointRef>, messages: List<RunMessage>): CheckpointIndex {
            var runCount = 0
            val runs = mutableListOf<UserRunEntry>()
            messages.forEachIndexed { index, message ->
                val span = getUserRunSpan(message)
                if (span < 1) return@forEachIndexed
                runCount += span
                runs += UserRunEntry(index, message.id, span, runCount, collapseWhitespace(displayText(message)))
            }
            return CheckpointIndex(history.sortedBy { it.runCount }, runs)
        }
    }
}

/** Memoizes the last build per (lastSequence, canonicalCount) (docs/07 §4.3). */
class CheckpointIndexCache {
    private var key: Pair<Long, Int>? = null
    private var value: CheckpointIndex? = null

    @Synchronized
    fun get(lastSequence: Long, canonicalCount: Int, build: () -> CheckpointIndex): CheckpointIndex {
        val requested = lastSequence to canonicalCount
        val cached = value
        if (cached != null && key == requested) return cached
        return build().also {
            key = requested
            value = it
        }
    }

    @Synchronized
    fun clear() {
        key = null
        value = null
    }
}
