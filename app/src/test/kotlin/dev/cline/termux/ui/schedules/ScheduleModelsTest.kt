package dev.cline.termux.ui.schedules

import dev.cline.termux.R
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import dev.cline.termux.data.schedules.ROUTINE_TEMPLATES
import dev.cline.termux.data.schedules.ScheduleExecution
import dev.cline.termux.data.schedules.ScheduleFormError
import dev.cline.termux.data.schedules.ScheduleRecord
import dev.cline.termux.data.schedules.ScheduleType
import dev.cline.termux.data.schedules.SchedulesRepository
import dev.cline.termux.data.schedules.UpcomingRun
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleModelsTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private val us = Locale.US

    /** JDK 20+ formats "9:00 AM" with a narrow no-break space. */
    private fun nb(text: String): String = text.replace('\u202F', ' ')

    /** 2026-10-04T16:00:00Z. */
    private val now = LocalDateTime.of(2026, 10, 4, 16, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun record(id: String, name: String, enabled: Boolean = true, nextRunAt: Long? = null) = ScheduleRecord(
        scheduleId = id,
        name = name,
        cronPattern = "0 17 * * *",
        timezone = "Asia/Shanghai",
        prompt = "p",
        workspaceRoot = "/w",
        enabled = enabled,
        mode = "yolo",
        nextRunAt = nextRunAt,
        lastRunAt = now,
        raw = buildJsonObject { put("scheduleId", JsonPrimitive(id)) },
    )

    @Test
    fun `cards follow the desktop card lines`() {
        val overview = SchedulesRepository.Overview(
            schedules = listOf(record("b", "beta", nextRunAt = 0), record("a", "Alpha", nextRunAt = now + 60_000)),
            active = listOf(ScheduleExecution("e1", "b", startedAt = now, status = "running")),
            upcoming = listOf(UpcomingRun("b", "beta", now + 3_600_000)),
            lastExecutions = mapOf("a" to ScheduleExecution("e0", "a", endedAt = now, status = "completed")),
            loadedAtMs = now,
        )
        val cards = ScheduleProjection.cards(overview, utc, us, SchedulePreviewData.words)
        assertEquals(listOf("Alpha", "beta"), cards.map { it.name })
        val alpha = cards[0]
        assertEquals("Daily · 5:00 PM · Asia/Shanghai", nb(alpha.trigger))
        assertEquals("completed at Oct 4, 2026, 4:00 PM", nb(alpha.lastResult))
        assertEquals("Oct 4, 2026, 4:01 PM", nb(alpha.nextRun))
        assertNull(alpha.active)
        val beta = cards[1]
        // schedule.nextRunAt || upcoming.nextRunAt: 0 falls back to the upcoming run.
        assertEquals("Oct 4, 2026, 5:00 PM", nb(beta.nextRun))
        assertEquals("-", beta.lastResult)
        assertEquals("e1", beta.active!!.executionId)
        assertEquals("Oct 4, 2026, 4:00 PM", nb(beta.activeSince!!))
        assertEquals("-", beta.model)
    }

    @Test
    fun `run rows group statuses as the desktop icons do`() {
        assertEquals(RunStatus.Succeeded, ScheduleProjection.runGroup("success"))
        assertEquals(RunStatus.Succeeded, ScheduleProjection.runGroup("Completed"))
        assertEquals(RunStatus.Failed, ScheduleProjection.runGroup("failed"))
        assertEquals(RunStatus.Failed, ScheduleProjection.runGroup("timeout"))
        assertEquals(RunStatus.Failed, ScheduleProjection.runGroup("aborted"))
        assertEquals(RunStatus.Running, ScheduleProjection.runGroup("running"))
        assertEquals(RunStatus.Waiting, ScheduleProjection.runGroup("pending"))
        assertEquals(RunStatus.Waiting, ScheduleProjection.runGroup(""))
        val row = ScheduleProjection.runRow(ScheduleExecution("x", "s", sessionId = " ", triggeredAt = now, status = "pending", errorMessage = ""), utc, us)
        assertNull(row.sessionId)
        assertNull(row.errorMessage)
        assertEquals("Oct 4, 2026, 4:00 PM", nb(row.timestamp))
    }

    @Test
    fun `details carry the raw record as pretty JSON and preview three runs`() {
        val card = ScheduleProjection.card(record("a", "Alpha"), null, null, null, utc, us, SchedulePreviewData.words)
        val runs = (1..5).map { ScheduleExecution("e$it", "a", status = "completed") }
        val details = ScheduleProjection.details(card, runs, utc, us)
        assertEquals("{\n  \"scheduleId\": \"a\"\n}", details.json)
        assertEquals(3, ScheduleProjection.runsPreview(details.runs, showAll = false).size)
        assertEquals(5, ScheduleProjection.runsPreview(details.runs, showAll = true).size)
    }

    @Test
    fun `suggested templates hide names already used`() {
        val shown = ScheduleProjection.templates(listOf(" daily change DIGEST "))
        assertEquals(ROUTINE_TEMPLATES.size - 1, shown.size)
        assertFalse(shown.any { it.id == "daily-digest" })
    }

    @Test
    fun `keep-alive notice needs an enabled schedule and a weak keep-alive, once per version`() {
        assertTrue(ScheduleProjection.keepAliveNotice(anyEnabled = true, keepAwake = false, checklistConfirmed = true, dismissedFor = null, appVersion = "0.1.0"))
        assertTrue(ScheduleProjection.keepAliveNotice(anyEnabled = true, keepAwake = true, checklistConfirmed = false, dismissedFor = "0.0.9", appVersion = "0.1.0"))
        assertFalse(ScheduleProjection.keepAliveNotice(anyEnabled = true, keepAwake = true, checklistConfirmed = true, dismissedFor = null, appVersion = "0.1.0"))
        assertFalse(ScheduleProjection.keepAliveNotice(anyEnabled = false, keepAwake = false, checklistConfirmed = false, dismissedFor = null, appVersion = "0.1.0"))
        assertFalse(ScheduleProjection.keepAliveNotice(anyEnabled = true, keepAwake = false, checklistConfirmed = false, dismissedFor = "0.1.0", appVersion = "0.1.0"))
    }

    @Test
    fun `a new schedule is once, tomorrow, 9 00, Mon-Fri, default prompt`() {
        val form = ScheduleFormState.create(now, utc, "cline", "m", "/w")
        assertEquals(ScheduleType.Once, form.type)
        assertEquals(LocalDate.of(2026, 10, 5), form.date)
        assertEquals(9 to 0, form.hour to form.minute)
        assertEquals(ScheduleFormState.WEEKDAYS, form.days)
        assertEquals("Review PRs opened yesterday and summarize issues.", form.prompt)
        assertEquals(listOf("MON", "TUE", "WED", "THU", "FRI"), form.toForm().scheduleDays)
        assertEquals("2026-10-05", form.toForm().scheduleDate)
    }

    @Test
    fun `a template sets name, frequency, days, time and prompt`() {
        val template = ROUTINE_TEMPLATES.first { it.id == "security-scan" }
        val form = ScheduleFormState.create(now, utc, "cline", "m", "/w", template)
        assertEquals("Security scan", form.name)
        assertEquals(ScheduleType.Weekly, form.type)
        assertEquals(setOf(DayOfWeek.MONDAY), form.days)
        assertEquals(8 to 0, form.hour to form.minute)
        assertEquals(template.prompt, form.prompt)
        assertEquals(LocalDate.of(2026, 10, 5), form.date)
        val digest = ScheduleFormState.create(now, utc, "cline", "m", "/w", ROUTINE_TEMPLATES.first { it.id == "daily-digest" })
        assertEquals(ScheduleType.Daily, digest.type)
        assertEquals(17, digest.hour)
    }

    @Test
    fun `an edited schedule prefills from its record`() {
        val r = record("a", "Alpha").copy(cronPattern = "*/15 * * * MON-WED", tags = listOf("x", "y"), timeoutSeconds = 60, systemPrompt = null, modelSelection = null)
        val form = ScheduleFormState.edit(r, now, utc)
        assertEquals(ScheduleType.Weekly, form.type)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY), form.days)
        assertEquals(9 to 0, form.hour to form.minute) // "*/15" is not a plain minute: desktop default
        assertEquals("cline", form.provider)
        assertEquals("anthropic/claude-sonnet-5", form.model)
        assertEquals("x,y", form.tags)
        assertEquals("60", form.timeoutSeconds)
        assertEquals("", form.systemPrompt)
        assertEquals("a", form.editingId)
        val once = ScheduleFormState.edit(r.copy(cronPattern = "0", metadata = buildJsonObject { put("__hubScheduleRunAt", JsonPrimitive(now + 7_200_000)) }), now, utc)
        assertEquals(ScheduleType.Once, once.type)
        assertEquals(18 to 0, once.hour to once.minute)
        assertEquals(LocalDate.of(2026, 10, 4), once.date)
        assertEquals(ScheduleFormState.WEEKDAYS, once.days)
    }

    @Test
    fun `once clamps to one minute from now on the minimum date`() {
        val today = ScheduleFormState.create(now, utc, "c", "m", "/w")
        val onToday = OnceClamp.withDate(today.copy(hour = 9, minute = 0), LocalDate.of(2026, 10, 4), now, utc)
        assertEquals(16 to 1, onToday.hour to onToday.minute)
        val later = OnceClamp.withTime(onToday, 18, 30, now, utc)
        assertEquals(18 to 30, later.hour to later.minute)
        val early = OnceClamp.withTime(onToday, 8, 0, now, utc)
        assertEquals(16 to 1, early.hour to early.minute)
        val tomorrow = OnceClamp.withTime(today, 8, 0, now, utc)
        assertEquals(8 to 0, tomorrow.hour to tomorrow.minute)
        val weekly = OnceClamp.withTime(onToday.copy(type = ScheduleType.Weekly), 8, 0, now, utc)
        assertEquals(8 to 0, weekly.hour to weekly.minute)
    }

    @Test
    fun `day chips are Monday first with the desktop labels`() {
        assertEquals(DayOfWeek.MONDAY, DAY_CHIPS.first())
        assertEquals(DayOfWeek.SUNDAY, DAY_CHIPS.last())
        assertEquals(listOf("MON", "SUN"), setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY).toCronDays())
    }

    @Test
    fun `validation errors map to the desktop strings and fields`() {
        assertEquals(R.string.schedule_error_name_required, ScheduleFormError.NameRequired.message())
        assertEquals(FormField.Days, ScheduleFormError.NoWeekday.field())
        assertEquals(FormField.Time, ScheduleFormError.OnceInPast.field())
        assertEquals(FormField.Workspace, ScheduleFormError.WorkspaceRequired.field())
    }

    @Test
    fun `connection losses are transient`() {
        assertTrue(RepoFailure.HubDraining.isTransient)
        assertFalse(RepoFailure.HubTimeout.isTransient)
    }

    @Test
    fun `run status labels capitalise like the desktop`() {
        assertEquals(R.string.schedule_run_completed, runStatusLabel("completed"))
        assertNull(runStatusLabel("queued"))
        assertEquals("Queued", capitalizeStatus("queued"))
    }

    @Test
    fun `timeout accepts empty or a positive whole number`() {
        assertTrue(ScheduleEditorViewModel.isValidTimeout(""))
        assertTrue(ScheduleEditorViewModel.isValidTimeout(" 3600 "))
        assertFalse(ScheduleEditorViewModel.isValidTimeout("0"))
        assertFalse(ScheduleEditorViewModel.isValidTimeout("12s"))
        assertFalse(ScheduleEditorViewModel.isValidTimeout("-5"))
        assertFalse(ScheduleEditorViewModel.isValidTimeout("1.5"))
    }

    @Test
    fun `provider and model options keep the current values`() {
        val catalog = CatalogState(
            source = CatalogSource.Catalog,
            providers = listOf(ProviderListItem("cline", enabled = true), ProviderListItem("openai", enabled = false, configured = true)),
            modelsByProvider = mapOf("cline" to listOf(ProviderModel("a/one"), ProviderModel("a/two"))),
        )
        assertEquals(listOf("cline"), ScheduleEditorViewModel.providerOptions(catalog, "cline"))
        assertEquals(listOf("cline", "custom"), ScheduleEditorViewModel.providerOptions(catalog, "custom"))
        assertEquals(listOf("a/one", "a/two"), ScheduleEditorViewModel.modelOptions(catalog, "cline", "a/two"))
        assertEquals(listOf("a/one", "a/two", "typed"), ScheduleEditorViewModel.modelOptions(catalog, "cline", "typed"))
        assertEquals(emptyList<String>(), ScheduleEditorViewModel.modelOptions(catalog, "openai", ""))
    }

    @Test
    fun `template ids resolve`() {
        assertEquals("Daily change digest", templateById("daily-digest")!!.name)
        assertNull(templateById("nope"))
        assertNull(templateById(null))
    }
}
