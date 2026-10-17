package dev.cline.termux.data.schedules

import dev.cline.termux.data.tools.jsBoolean
import dev.cline.termux.data.tools.jsNumber
import dev.cline.termux.data.tools.jsString
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The Hub's schedule shapes (`sdk/packages/shared/src/hub.ts` `ScheduleRecord`,
 * `ScheduleExecutionRecord`) and the `schedule.*` payloads of customize-and-rest.md §4.8.
 * Every command carries `allWorkspaces: true` (the token connection's cross-workspace
 * authority, `schedule-command-service.ts` `spansAllWorkspaces`).
 */

data class ScheduleModelSelection(val providerId: String, val modelId: String)

data class ScheduleRecord(
    val scheduleId: String,
    val name: String,
    val cronPattern: String,
    val timezone: String? = null,
    val prompt: String = "",
    val workspaceRoot: String = "",
    val cwd: String? = null,
    val modelSelection: ScheduleModelSelection? = null,
    val enabled: Boolean = true,
    val mode: String? = null,
    val systemPrompt: String? = null,
    val maxIterations: Int? = null,
    val timeoutSeconds: Int? = null,
    val maxParallel: Int? = null,
    val createdAt: Long? = null,
    val updatedAt: Long? = null,
    val nextRunAt: Long? = null,
    val lastRunAt: Long? = null,
    val createdBy: String? = null,
    val tags: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    /** The record as received, for the details page's JSON block (CR §4.3 C7). */
    val raw: JsonObject = JsonObject(emptyMap()),
) {
    val isOneTime: Boolean get() = cronPattern == ONE_TIME_SCHEDULE_CRON_PATTERN

    /** The fields the ported formatters read. */
    fun facts(): ScheduleFacts = ScheduleFacts(
        cronPattern = cronPattern,
        timezone = timezone,
        provider = metadata?.get("provider").jsString(),
        model = metadata?.get("model").jsString(),
        selectionProviderId = modelSelection?.providerId,
        selectionModelId = modelSelection?.modelId,
        oneTimeRunAt = metadata?.get(ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY).jsNumber()?.toLong(),
        nextRunAt = nextRunAt,
    )

    companion object {
        fun parse(o: JsonObject): ScheduleRecord? {
            val id = o["scheduleId"].jsString() ?: return null
            return ScheduleRecord(
                scheduleId = id,
                name = o["name"].jsString().orEmpty(),
                cronPattern = o["cronPattern"].jsString().orEmpty(),
                timezone = o["timezone"].jsString(),
                prompt = o["prompt"].jsString().orEmpty(),
                workspaceRoot = o["workspaceRoot"].jsString().orEmpty(),
                cwd = o["cwd"].jsString(),
                modelSelection = (o["modelSelection"] as? JsonObject)?.let { m ->
                    val provider = m["providerId"].jsString()
                    val model = m["modelId"].jsString()
                    if (provider != null && model != null) ScheduleModelSelection(provider, model) else null
                },
                enabled = o["enabled"].jsBoolean() != false,
                mode = o["mode"].jsString(),
                systemPrompt = o["systemPrompt"].jsString(),
                maxIterations = o["maxIterations"].jsNumber()?.toInt(),
                timeoutSeconds = o["timeoutSeconds"].jsNumber()?.toInt(),
                maxParallel = o["maxParallel"].jsNumber()?.toInt(),
                createdAt = o["createdAt"].jsNumber()?.toLong(),
                updatedAt = o["updatedAt"].jsNumber()?.toLong(),
                nextRunAt = o["nextRunAt"].jsNumber()?.toLong(),
                lastRunAt = o["lastRunAt"].jsNumber()?.toLong(),
                createdBy = o["createdBy"].jsString(),
                tags = (o["tags"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                metadata = o["metadata"] as? JsonObject,
                raw = o,
            )
        }

        fun listFrom(reply: JsonObject, key: String = "schedules"): List<ScheduleRecord> =
            (reply[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }

        fun from(reply: JsonObject): ScheduleRecord? = (reply["schedule"] as? JsonObject)?.let(::parse)
    }
}

/** `ScheduleExecutionStatus` values: pending, running, success, completed, failed, timeout, aborted. */
data class ScheduleExecution(
    val executionId: String,
    val scheduleId: String,
    val sessionId: String? = null,
    val triggeredAt: Long? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
    val status: String = "pending",
    val exitCode: Int? = null,
    val errorMessage: String? = null,
    val iterations: Int? = null,
    val tokensUsed: Long? = null,
    val costUsd: Double? = null,
) {
    val isActive: Boolean get() = status == "pending" || status == "running"

    fun facts(): ExecutionFacts = ExecutionFacts(status = status, triggeredAt = triggeredAt, startedAt = startedAt, endedAt = endedAt)

    companion object {
        fun parse(o: JsonObject): ScheduleExecution? {
            val id = o["executionId"].jsString() ?: return null
            return ScheduleExecution(
                executionId = id,
                scheduleId = o["scheduleId"].jsString().orEmpty(),
                sessionId = o["sessionId"].jsString()?.ifEmpty { null },
                triggeredAt = o["triggeredAt"].jsNumber()?.toLong(),
                startedAt = o["startedAt"].jsNumber()?.toLong(),
                endedAt = o["endedAt"].jsNumber()?.toLong(),
                status = o["status"].jsString() ?: "pending",
                exitCode = o["exitCode"].jsNumber()?.toInt(),
                errorMessage = o["errorMessage"].jsString(),
                iterations = o["iterations"].jsNumber()?.toInt(),
                tokensUsed = o["tokensUsed"].jsNumber()?.toLong(),
                costUsd = o["costUsd"].jsNumber(),
            )
        }

        fun listFrom(reply: JsonObject): List<ScheduleExecution> =
            (reply["executions"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::parse) }

        fun from(reply: JsonObject): ScheduleExecution? = (reply["execution"] as? JsonObject)?.let(::parse)
    }
}

/** `schedule.upcoming` row. */
data class UpcomingRun(val scheduleId: String, val name: String, val nextRunAt: Long?) {
    companion object {
        fun listFrom(reply: JsonObject): List<UpcomingRun> = (reply["runs"] as? JsonArray).orEmpty().mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o["scheduleId"].jsString() ?: return@mapNotNull null
            UpcomingRun(id, o["name"].jsString().orEmpty(), o["nextRunAt"].jsNumber()?.toLong())
        }
    }
}

/**
 * What the editor sends for the trigger and fields (CR §4.8). [cronPattern] is
 * [ONE_TIME_SCHEDULE_CRON_PATTERN] for Once; [metadata] already carries (or no longer carries)
 * `__hubScheduleRunAt`.
 */
data class ScheduleDraft(
    val name: String,
    val cronPattern: String,
    val timezone: String?,
    val metadata: JsonObject?,
    val prompt: String,
    val modelSelection: ScheduleModelSelection,
    val workspaceRoot: String,
    val systemPrompt: String?,
    val timeoutSeconds: Int?,
    val tags: List<String>?,
    val enabled: Boolean = true,
) {
    companion object {
        /**
         * Builds the draft from the validated form. [existing] is the edited schedule (null =
         * create): the timezone follows `routineScheduleTimezone`, and the metadata keeps every
         * existing key but sets or removes `__hubScheduleRunAt` for the timing type.
         */
        fun of(form: ScheduleForm, valid: ValidSchedule, existing: ScheduleRecord?, deviceZone: ZoneId = ZoneId.systemDefault()): ScheduleDraft {
            val once = form.scheduleType == ScheduleType.Once
            val base = existing?.metadata.orEmpty().toMutableMap()
            if (once && valid.runAt != null) base[ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY] = JsonPrimitive(valid.runAt) else base.remove(ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY)
            return ScheduleDraft(
                name = valid.name,
                cronPattern = if (once) ONE_TIME_SCHEDULE_CRON_PATTERN else valid.cronPattern.orEmpty(),
                timezone = routineScheduleTimezone(form.scheduleType, existing?.timezone, existing != null, deviceZone),
                metadata = base.takeIf { it.isNotEmpty() }?.let(::JsonObject),
                prompt = valid.prompt,
                modelSelection = ScheduleModelSelection(form.provider.trim(), form.model.trim()),
                workspaceRoot = valid.workspaceRoot,
                systemPrompt = form.systemPrompt.trim().ifEmpty { null },
                timeoutSeconds = parseOptionalPositiveInt(form.timeoutSeconds),
                tags = parseTags(form.tags),
                enabled = form.enabled,
            )
        }
    }
}

/** `schedule.*` payloads (CR §4.8); every one carries `allWorkspaces: true`. */
object SchedulePayloads {
    const val CREATED_BY = "android"
    const val MODE = NEW_SCHEDULE_MODE

    fun list(limit: Int = 200) = withScope { put("limit", limit) }
    fun active() = withScope { }
    fun upcoming(limit: Int = 30) = withScope { put("limit", limit) }
    fun listExecutions(scheduleId: String, limit: Int) = withScope { put("scheduleId", scheduleId); put("limit", limit) }
    fun byId(scheduleId: String) = withScope { put("scheduleId", scheduleId) }
    fun trigger(scheduleId: String) = withScope { put("scheduleId", scheduleId); put("wait", false) }

    fun create(draft: ScheduleDraft): JsonObject = withScope {
        put("name", draft.name)
        put("cronPattern", draft.cronPattern)
        draft.timezone?.let { put("timezone", it) }
        draft.metadata?.let { put("metadata", it) }
        put("prompt", draft.prompt)
        put("modelSelection", selection(draft.modelSelection))
        put("mode", MODE)
        put("workspaceRoot", draft.workspaceRoot)
        put("cwd", draft.workspaceRoot)
        draft.systemPrompt?.let { put("systemPrompt", it) }
        draft.timeoutSeconds?.let { put("timeoutSeconds", it) }
        put("maxParallel", 1)
        put("enabled", true)
        draft.tags?.let { tags -> put("tags", buildJsonArray { tags.forEach { add(JsonPrimitive(it)) } }) }
        put("createdBy", CREATED_BY)
    }

    /** No `mode` (edits keep it); `timezone`, `systemPrompt`, `timeoutSeconds` and `cwd` are sent as `null` to clear. */
    fun update(existing: ScheduleRecord, draft: ScheduleDraft): JsonObject = withScope {
        put("scheduleId", existing.scheduleId)
        put("name", draft.name)
        put("cronPattern", draft.cronPattern)
        put("timezone", draft.timezone?.let(::JsonPrimitive) ?: JsonNull)
        draft.metadata?.let { put("metadata", it) }
        put("prompt", draft.prompt)
        put("modelSelection", selection(draft.modelSelection))
        put("workspaceRoot", draft.workspaceRoot)
        put("cwd", existing.cwd?.let(::JsonPrimitive) ?: JsonNull)
        put("systemPrompt", draft.systemPrompt?.let(::JsonPrimitive) ?: JsonNull)
        put("timeoutSeconds", draft.timeoutSeconds?.let(::JsonPrimitive) ?: JsonNull)
        put("maxParallel", 1)
        put("enabled", existing.enabled)
        put("tags", buildJsonArray { draft.tags.orEmpty().forEach { add(JsonPrimitive(it)) } })
    }

    private fun selection(selection: ScheduleModelSelection): JsonElement = buildJsonObject {
        put("providerId", selection.providerId)
        put("modelId", selection.modelId)
    }

    private inline fun withScope(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
        put("allWorkspaces", true)
        block()
    }
}
