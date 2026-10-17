package dev.cline.termux.ui.schedules

import dev.cline.termux.data.schedules.ROUTINE_TEMPLATES
import dev.cline.termux.data.schedules.ScheduleExecution
import dev.cline.termux.data.schedules.ScheduleModelSelection
import dev.cline.termux.data.schedules.ScheduleRecord
import dev.cline.termux.data.schedules.ScheduleWords
import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Sample records for the `@Preview`s of C5–C7 (fixed instants, UTC). */
internal object SchedulePreviewData {
    private val zone: ZoneId = ZoneId.of("UTC")
    private const val T0 = 1_791_043_200_000L // 2026-10-04T16:00Z

    val digest = ScheduleRecord(
        scheduleId = "sch_9f2c41d0",
        name = "Daily change digest",
        cronPattern = "0 17 * * *",
        timezone = "Asia/Shanghai",
        prompt = ROUTINE_TEMPLATES.first { it.id == "daily-digest" }.prompt,
        workspaceRoot = "/data/data/com.termux/files/home/app",
        modelSelection = ScheduleModelSelection("cline", "anthropic/claude-sonnet-5"),
        enabled = true,
        mode = "yolo",
        nextRunAt = T0 + 86_400_000,
        lastRunAt = T0,
        tags = listOf("automation", "review"),
        raw = buildJsonObject {
            put("scheduleId", JsonPrimitive("sch_9f2c41d0"))
            put("name", JsonPrimitive("Daily change digest"))
            put("cronPattern", JsonPrimitive("0 17 * * *"))
            put("enabled", JsonPrimitive(true))
        },
    )

    val paused = digest.copy(scheduleId = "sch_1b7e", name = "Security scan", cronPattern = "0 8 * * MON", enabled = false, tags = emptyList(), lastRunAt = null)

    val runs = listOf(
        ScheduleExecution("exec_3", "sch_9f2c41d0", sessionId = null, triggeredAt = T0 + 86_400_000, status = "running", startedAt = T0 + 86_400_000),
        ScheduleExecution("exec_2", "sch_9f2c41d0", sessionId = "sess_81a2", startedAt = T0, endedAt = T0 + 180_000, status = "completed"),
        ScheduleExecution("exec_1", "sch_9f2c41d0", sessionId = "sess_77c0", startedAt = T0 - 86_400_000, endedAt = T0 - 86_340_000, status = "failed", errorMessage = "Provider returned 401"),
        ScheduleExecution("exec_0", "sch_9f2c41d0", sessionId = null, triggeredAt = T0 - 172_800_000, status = "pending"),
    )

    /** Fixture copy for previews (the app resolves it from strings_schedules.xml, `scheduleWords`). */
    val words = ScheduleWords(
        everyDay = "Every day",
        weekdays = "Weekdays",
        daily = "Daily",
        once = "Once",
        statusAt = "%1${'$'}s at %2${'$'}s",
        unknownStatus = "unknown",
        dayShort = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"),
        triggerPair = "%1${'$'}s · %2${'$'}s",
        triggerWithZone = "%1${'$'}s · %2${'$'}s · %3${'$'}s",
        daysJoin = "%1${'$'}s, %2${'$'}s",
    )

    fun card(record: ScheduleRecord = digest, active: Boolean = false): ScheduleCard =
        ScheduleProjection.card(record, null, runs[1].takeIf { record.enabled }, runs[0].takeIf { active }, zone, Locale.US, words)

    fun details(): ScheduleDetailsModel = ScheduleProjection.details(card(), runs, zone, Locale.US)

    val cards: List<ScheduleCard> get() = listOf(card(active = true), card(paused))
}
