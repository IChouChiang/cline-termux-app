package dev.cline.termux.data.sessions

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.session.SessionVisualFlags
import dev.cline.termux.data.tools.asRecord
import dev.cline.termux.data.tools.jsBoolean
import dev.cline.termux.data.tools.jsNumber
import dev.cline.termux.data.tools.jsString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/*
 * The sessions row model (design/screens/sessions.md §1.3) and its mapping from a Hub
 * `SessionRecord` (fixture 05 shapes), including the desktop's metadata readers
 * (lib/session-history.ts) and `isValidHistorySession` / subagent guards
 * (hooks/use-session-history.ts). The list policy (refresh, overrides, links) lives in
 * SessionsRepository; everything here is pure.
 */

/** Kernel status of a session record or snapshot. "aborted" and "cancelled" both read Cancelled. */
@Serializable
enum class KernelSessionStatus {
    Idle, Running, Pending, Completed, Aborted, Cancelled, Failed, Unknown;

    /** The kernel's word, as `SessionVisual.of` reads it. */
    val word: String
        get() = when (this) {
            Idle -> "idle"
            Running -> "running"
            Pending -> "pending"
            Completed -> "completed"
            Aborted -> "aborted"
            Cancelled -> "cancelled"
            Failed -> "failed"
            Unknown -> "unknown"
        }

    companion object {
        fun parse(value: String?): KernelSessionStatus = when (value?.trim()?.lowercase()) {
            "idle" -> Idle
            "running", "starting", "provisioning", "stopping" -> Running
            "pending" -> Pending
            "completed" -> Completed
            "aborted" -> Aborted
            "cancelled", "canceled" -> Cancelled
            "failed", "error" -> Failed
            else -> Unknown
        }
    }
}

/** `SessionScheduleInfo`, from metadata (runner stamp) or the schedule link map. */
@Immutable
@Serializable
data class ScheduleInfo(val scheduleId: String? = null, val scheduleName: String? = null, val runNumber: Int? = null)

/** Usage of a row; null fields are unknown. */
@Immutable
@Serializable
data class RowUsage(val inputTokens: Long? = null, val outputTokens: Long? = null, val totalCostUsd: Double? = null)

/** A schedule link for a session (`scheduledSessionLinks`): executions name the schedule. */
@Immutable
@Serializable
data class ScheduleLink(val scheduleId: String? = null, val scheduleName: String? = null)

/** One sessions row. */
@Immutable
@Serializable
data class SessionSummary(
    val id: String,
    val title: String,
    val hasCustomTitle: Boolean,
    /** `workspaceRoot.ifBlank { cwd }`. */
    val workspaceRoot: String,
    val cwd: String,
    val kernelStatus: KernelSessionStatus,
    val provider: String,
    val model: String,
    /** `metadata.source` ("android", "cli", "vscode", "hub-schedule"...). */
    val source: String,
    val pinned: Boolean,
    val schedule: ScheduleInfo? = null,
    val gitBranch: String? = null,
    val createdAt: Long,
    /** max(updatedAt, createdAt) unless an annotation override applies; sort key and age. */
    val activityAt: Long,
    val usage: RowUsage? = null,
    /** Live participants (persisted records after a restart have none). */
    val participantCount: Int = 0,
    /** The raw metadata, kept for read-modify-write; stripped before caching. */
    val metadata: JsonObject = JsonObject(emptyMap()),
) {
    val isScheduled: Boolean get() = schedule != null

    /** `kernelStatus` Running, Pending or Idle with participants: worth a `session.get` for usage (§1.5). */
    val isLive: Boolean
        get() = participantCount > 0 &&
            (kernelStatus == KernelSessionStatus.Running || kernelStatus == KernelSessionStatus.Pending || kernelStatus == KernelSessionStatus.Idle)

    /** The cache form: no metadata (SS §1.8). */
    fun forCache(): SessionSummary = copy(metadata = JsonObject(emptyMap()))

    companion object {
        /**
         * Map a Hub `SessionRecord`. [link] is the schedule link for this session, if any;
         * metadata stamped by the runner wins, the link only fills gaps (SS §1.3 rule 6).
         */
        fun fromRecord(record: JsonObject, link: ScheduleLink? = null): SessionSummary? {
            val id = record["sessionId"].jsString()?.trim()?.ifEmpty { null } ?: return null
            val metadata = record["metadata"].asRecord() ?: JsonObject(emptyMap())
            val cwd = record["cwd"].jsString().orEmpty()
            val workspaceRoot = record["workspaceRoot"].jsString().orEmpty().ifBlank { cwd }
            val createdAt = record["createdAt"].jsNumber()?.toLong() ?: 0L
            val updatedAt = record["updatedAt"].jsNumber()?.toLong() ?: createdAt
            val customTitle = metadataTitle(metadata)
            return SessionSummary(
                id = id,
                title = sessionTitle(id, customTitle, metadata["prompt"].jsString() ?: record["prompt"].jsString()),
                hasCustomTitle = customTitle.isNotEmpty(),
                workspaceRoot = workspaceRoot,
                cwd = cwd,
                kernelStatus = KernelSessionStatus.parse(record["status"].jsString()),
                provider = metadata["provider"].jsString()?.trim().orEmpty(),
                model = metadata["model"].jsString()?.trim().orEmpty(),
                source = metadataSource(record, metadata),
                pinned = metadataPinned(metadata),
                schedule = scheduleInfo(metadata, link),
                gitBranch = metadataGitBranch(metadata).ifEmpty { null },
                createdAt = createdAt,
                activityAt = maxOf(updatedAt, createdAt),
                usage = rowUsage(metadata),
                participantCount = (record["participants"] as? JsonArray)?.size ?: 0,
                metadata = metadata,
            )
        }
    }
}

/** `PINNED_METADATA_KEY`. */
const val PINNED_METADATA_KEY = "pinned"

/** `SCHEDULED_SESSION_SOURCE`: the `sessionHistoryOrigin.trigger` of schedule runs. */
const val SCHEDULED_SESSION_SOURCE = "hub-schedule"

/** `getSessionMetadataTitle`. */
fun metadataTitle(metadata: JsonObject?): String = metadata?.get("title").jsString()?.trim().orEmpty()

/** `getSessionMetadataPinned`: only a real `true`. */
fun metadataPinned(metadata: JsonObject?): Boolean = metadata?.get(PINNED_METADATA_KEY).jsBoolean() == true

/** `getSessionMetadataIsScheduled`. */
fun metadataIsScheduled(metadata: JsonObject?): Boolean =
    metadata?.get("sessionHistoryOrigin").asRecord()?.get("trigger").jsString()?.trim() == SCHEDULED_SESSION_SOURCE

/** `getSessionMetadataSchedule`: trimmed id and name, positive integer run number. */
fun metadataSchedule(metadata: JsonObject?): ScheduleInfo {
    val scheduleId = metadata?.get("scheduleId").jsString()?.trim().orEmpty()
    val scheduleName = metadata?.get("scheduleName").jsString()?.trim().orEmpty()
    val run = metadata?.get("scheduleRunNumber").jsNumber()
    return ScheduleInfo(
        scheduleId = scheduleId.ifEmpty { null },
        scheduleName = scheduleName.ifEmpty { null },
        runNumber = run?.takeIf { it == Math.rint(it) && it > 0 }?.toInt(),
    )
}

/** `getSessionMetadataGitBranch`. */
fun metadataGitBranch(metadata: JsonObject?): String =
    metadata?.get("git").asRecord()?.get("branch").jsString()?.trim().orEmpty()

/** `getSessionSource`: the record's `source`, else `metadata.source`. */
fun metadataSource(record: JsonObject?, metadata: JsonObject?): String =
    record?.get("source").jsString()?.trim()?.ifEmpty { null } ?: metadata?.get("source").jsString()?.trim().orEmpty()

/** Schedule info when the session is a schedule run (origin trigger, runner stamp or link). */
fun scheduleInfo(metadata: JsonObject?, link: ScheduleLink?): ScheduleInfo? {
    val stamped = metadataSchedule(metadata)
    if (!metadataIsScheduled(metadata) && link == null && stamped.scheduleId == null) return null
    return ScheduleInfo(
        scheduleId = stamped.scheduleId ?: link?.scheduleId?.trim()?.ifEmpty { null },
        scheduleName = stamped.scheduleName ?: link?.scheduleName?.trim()?.ifEmpty { null },
        runNumber = stamped.runNumber,
    )
}

/** §1.5 item 1: usage known when `metadata.usage` is present; cost from `metadata.totalCost`. */
fun rowUsage(metadata: JsonObject?): RowUsage? {
    val usage = metadata?.get("usage").asRecord() ?: return null
    return RowUsage(
        inputTokens = usage["inputTokens"].jsNumber()?.toLong(),
        outputTokens = usage["outputTokens"].jsNumber()?.toLong(),
        totalCostUsd = metadata?.get("totalCost").jsNumber() ?: usage["totalCost"].jsNumber(),
    )
}

/** `isKnownModelField`. */
fun isKnownModelField(value: String?): Boolean {
    val trimmed = value?.trim()?.lowercase().orEmpty()
    return trimmed.isNotEmpty() && trimmed != "unknown"
}

/**
 * §1.3 rule 1: drop subagent / teammate records (`!isSubagent && !parentSessionId`) and
 * records without a known provider and model (`isValidHistorySession`), except the ids in
 * [alwaysKeep] (the open session, sessions this process created).
 */
fun isListableRecord(record: JsonObject, alwaysKeep: Set<String> = emptySet()): Boolean {
    val id = record["sessionId"].jsString()?.trim().orEmpty()
    if (id.isEmpty()) return false
    val metadata = record["metadata"].asRecord()
    val isSubagent = record["isSubagent"].jsBoolean() == true || metadata?.get("isSubagent").jsBoolean() == true
    val parent = record["parentSessionId"].jsString()?.trim().orEmpty()
        .ifEmpty { metadata?.get("parentSessionId").jsString()?.trim().orEmpty() }
    if (isSubagent || parent.isNotEmpty()) return false
    if (id in alwaysKeep) return true
    return isKnownModelField(metadata?.get("provider").jsString()) && isKnownModelField(metadata?.get("model").jsString())
}

/** `compareSessionsByActivityDesc`: newest activity first, ties by id descending. */
val SessionActivityDescending: Comparator<SessionSummary> = Comparator { a, b ->
    val byTime = b.activityAt.compareTo(a.activityAt)
    if (byTime != 0) byTime else b.id.compareTo(a.id)
}

/** App-side flags merged by SessionsRepository (DS §1.6 inputs). */
@Immutable
data class SessionFlags(
    /** Our rename / fork / delete on this row in flight (SS §1.10). */
    val pendingAction: PendingKind? = null,
    val openApprovalIds: Set<String> = emptySet(),
    val openQuestionIds: Set<String> = emptySet(),
    val starting: Boolean = false,
    val unread: Boolean = false,
    val interrupted: Boolean = false,
) {
    fun toVisualFlags(): SessionVisualFlags = SessionVisualFlags(
        pendingAction = pendingAction,
        approvalRequired = openApprovalIds.isNotEmpty(),
        questionAsked = openQuestionIds.isNotEmpty(),
        starting = starting,
        unread = unread,
    )
}

/** The one status mapping for a row (DS §1.6). */
fun SessionSummary.visual(flags: SessionFlags = SessionFlags()): SessionVisual =
    SessionVisual.of(kernelStatus.word, flags.toVisualFlags())
