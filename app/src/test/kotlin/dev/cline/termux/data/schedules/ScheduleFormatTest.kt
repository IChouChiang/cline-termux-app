package dev.cline.termux.data.schedules

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from lib/routine-schedule-cron.test.ts and lib/routine-schedule-timezone.test.ts
 * (every case), plus the routine-view.tsx helpers and CR §0.7 formatters.
 */
class ScheduleFormatTest {
    private val words = dev.cline.termux.ui.schedules.SchedulePreviewData.words

    private val utc: ZoneId = ZoneOffset.UTC
    private val daily = ScheduleTiming(ScheduleType.Daily, "9", "0", listOf("MON"))
    private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()
    /** JDK 20+ formats "9:00 AM" with a narrow no-break space. */
    private fun nb(text: String): String = text.replace('\u202F', ' ')

    // routine-schedule-cron.test.ts

    @Test fun `preserves advanced cron on unrelated edits`() {
        for (cron in listOf("0 */2 * * *", "0 9 1 * *", "0 9 * 6 *")) {
            assertEquals(cron, preserveRoutineCron(cron, daily, daily.copy(), "0 9 * * *"))
        }
    }

    @Test fun `replaces cron when the user changes timing`() {
        assertEquals("0 10 * * *", preserveRoutineCron("0 9 1 * *", daily, daily.copy(scheduleHour = "10"), "0 10 * * *"))
        assertNull(preserveRoutineCron("0 9 1 * *", daily, daily.copy(scheduleType = ScheduleType.Once), null))
    }

    @Test fun `compares weekly days without depending on selection order`() {
        val weekly = daily.copy(scheduleType = ScheduleType.Weekly, scheduleDays = listOf("MON", "FRI"))
        assertEquals("0 9 * * 1,5", preserveRoutineCron("0 9 * * 1,5", weekly, weekly.copy(scheduleDays = listOf("FRI", "MON")), "replacement"))
        assertEquals("0 9 * * 1", preserveRoutineCron("0 9 * * 1,5", weekly, weekly.copy(scheduleDays = listOf("MON")), "0 9 * * 1"))
    }

    // routine-schedule-timezone.test.ts

    @Test fun `preserves an absent timezone when editing an existing recurring schedule`() {
        assertNull(routineScheduleTimezone(ScheduleType.Daily, null, isExisting = true))
        assertNull(routineScheduleTimezone(ScheduleType.Weekly, null, isExisting = true))
    }

    @Test fun `defaults only new recurring schedules to the device timezone`() {
        val zone = ZoneId.of("Europe/Berlin")
        assertEquals("Europe/Berlin", routineScheduleTimezone(ScheduleType.Daily, null, isExisting = false, deviceZone = zone))
        assertEquals("Europe/Berlin", routineScheduleTimezone(ScheduleType.Weekly, null, isExisting = false, deviceZone = zone))
    }

    @Test fun `preserves explicit zones and excludes timezone for one-time schedules`() {
        assertEquals("Asia/Tokyo", routineScheduleTimezone(ScheduleType.Daily, "Asia/Tokyo", isExisting = true))
        assertNull(routineScheduleTimezone(ScheduleType.Once, "Asia/Tokyo", isExisting = true))
        assertNull(routineScheduleTimezone(ScheduleType.Once, null, isExisting = false))
    }

    // routine-view.tsx helpers

    @Test fun `formats days as every day, weekdays or a list`() {
        assertEquals("Every day", formatScheduleDays(listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"), words))
        assertEquals("Weekdays", formatScheduleDays(listOf("FRI", "MON", "TUE", "WED", "THU"), words))
        assertEquals("Mon, Wed", formatScheduleDays(listOf("WED", "MON", "XYZ"), words))
    }

    @Test fun `builds and parses cron patterns`() {
        assertEquals("0 9 * * *", buildCronPattern(listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"), "9", "0"))
        assertEquals("30 23 * * MON,FRI", buildCronPattern(listOf("FRI", "MON"), "99", "30"))
        assertEquals("0 9 * * MON", buildCronPattern(listOf("MON"), "x", "y"))
        assertEquals("", buildCronPattern(emptyList(), "9", "0"))
        assertEquals(ScheduleTiming(ScheduleType.Daily, "9", "0", listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")), parseCronPattern("0 9 * * *"))
        assertEquals(ScheduleTiming(ScheduleType.Weekly, "8", "15", listOf("MON", "TUE", "WED")), parseCronPattern("15 8 * * MON-WED"))
        // Numeric day lists are not understood by the desktop editor: weekdays by default; a missing field means every day.
        assertEquals(ScheduleTiming(ScheduleType.Weekly, "9", "0", listOf("MON", "TUE", "WED", "THU", "FRI")), parseCronPattern("0 9 * * 1,5"))
        assertEquals(ScheduleTiming(ScheduleType.Weekly, "9", "0", listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")), parseCronPattern("x 99"))
    }

    @Test fun `formats triggers for daily, weekly and once schedules`() {
        assertEquals("Daily · 9:00 AM", nb(formatScheduleTrigger(ScheduleFacts("0 9 * * *"), words, utc, Locale.US)))
        assertEquals("Weekdays · 5:30 PM · Europe/Berlin", nb(formatScheduleTrigger(ScheduleFacts("30 17 * * MON-FRI", timezone = "Europe/Berlin"), words, utc, Locale.US)))
        val once = formatScheduleTrigger(ScheduleFacts(ONE_TIME_SCHEDULE_CRON_PATTERN, oneTimeRunAt = at("2026-09-13T14:05:00")), words, utc, Locale.US)
        assertTrue(once.startsWith("Once · Sep 13, 2026"))
        assertEquals("Once · -", formatScheduleTrigger(ScheduleFacts(ONE_TIME_SCHEDULE_CRON_PATTERN), words, utc, Locale.US))
    }

    @Test fun `trigger order and day separators come from the words, so a translation can reorder them`() {
        val translated = words.copy(
            dayShort = listOf("月", "火", "水", "木", "金", "土", "日"),
            triggerPair = "%2\$s(%1\$s)",
            triggerWithZone = "%3\$s %2\$s(%1\$s)",
            daysJoin = "%1\$s、%2\$s",
        )
        assertEquals("月、水、金", formatScheduleDays(listOf("FRI", "MON", "WED"), translated))
        assertEquals("Mon, Wed, Fri", formatScheduleDays(listOf("FRI", "MON", "WED"), words))
        assertEquals("", formatScheduleDays(emptyList(), translated))
        assertEquals("9:00 AM(月、水)", nb(formatScheduleTrigger(ScheduleFacts("0 9 * * MON,WED"), translated, utc, Locale.US)))
        assertEquals("Asia/Tokyo 9:00 AM(Daily)", nb(formatScheduleTrigger(ScheduleFacts("0 9 * * *", timezone = "Asia/Tokyo"), translated, utc, Locale.US)))
        assertEquals("-(Once)", formatScheduleTrigger(ScheduleFacts(ONE_TIME_SCHEDULE_CRON_PATTERN), translated, utc, Locale.US))
    }

    @Test fun `formats models, results and dates`() {
        assertEquals("cline/m", formatScheduleModel(ScheduleFacts("0 9 * * *", provider = "x", model = "y", selectionProviderId = "cline", selectionModelId = "m")))
        assertEquals("y", formatScheduleModel(ScheduleFacts("0 9 * * *", model = " y ")))
        assertEquals("-", formatScheduleModel(ScheduleFacts("0 9 * * *")))
        assertEquals("cline" to CLINE_DEFAULT_MODEL_ID, scheduleProviderModel(ScheduleFacts("0 9 * * *")))
        assertEquals("-", formatExecutionResult(null, words))
        assertEquals("unknown", formatExecutionResult(ExecutionFacts(), words, utc, Locale.US))
        assertTrue(formatExecutionResult(ExecutionFacts("completed", endedAt = at("2026-09-13T14:05:00")), words, utc, Locale.US).startsWith("completed at Sep 13, 2026"))
        assertEquals("-", formatDateTime(null as Long?, utc, Locale.US))
        assertEquals("-", formatDateTime("  ", utc, Locale.US))
        assertEquals("soon", formatDateTime("soon", utc, Locale.US))
    }

    @Test fun `parses form fields like the desktop`() {
        assertNull(parseOptionalPositiveInt(""))
        assertNull(parseOptionalPositiveInt("0"))
        assertEquals(30, parseOptionalPositiveInt(" 30s"))
        assertNull(parseTags(" , "))
        assertEquals(listOf("a", "b"), parseTags("a, ,b"))
        assertEquals("9:05 AM", nb(formatScheduleTime("9", "5", Locale.US)))
    }

    @Test fun `once schedules build a local instant and default to tomorrow`() {
        assertEquals(at("2026-09-14T07:05:00"), buildRunAt("2026-09-14", "7", "5", utc))
        assertNull(buildRunAt("", "7", "5", utc))
        assertEquals("2026-09-14", defaultScheduleDate(at("2026-09-13T23:00:00"), utc))
        assertEquals(LocalDateTime.parse("2026-09-13T12:01:00"), minimumOneTimeDateTime(at("2026-09-13T12:00:30"), utc))
        val (timing, date) = parseScheduleTrigger(ScheduleFacts(ONE_TIME_SCHEDULE_CRON_PATTERN, oneTimeRunAt = at("2026-10-01T06:30:00")), 0, utc)
        assertEquals(ScheduleType.Once, timing.scheduleType)
        assertEquals("6", timing.scheduleHour)
        assertEquals("30", timing.scheduleMinute)
        assertEquals("2026-10-01", date)
    }

    @Test fun `validates the form in the desktop order`() {
        val now = at("2026-09-13T12:00:00")
        fun error(form: ScheduleForm, editing: String? = null) =
            (validateScheduleForm(form, editing, now, utc).exceptionOrNull() as? ScheduleFormException)?.error
        val valid = ScheduleForm(name = "Daily", prompt = "Do it", workspaceRoot = "/w")
        assertEquals(ScheduleFormError.NameRequired, error(valid.copy(name = " ")))
        assertEquals(ScheduleFormError.OnceInPast, error(valid.copy(scheduleType = ScheduleType.Once, scheduleDate = "2026-09-13", scheduleHour = "11")))
        assertEquals(ScheduleFormError.NoWeekday, error(valid.copy(scheduleType = ScheduleType.Weekly, scheduleDays = emptyList())))
        assertEquals(ScheduleFormError.PromptRequired, error(valid.copy(prompt = "")))
        assertEquals(ScheduleFormError.WorkspaceRequired, error(valid.copy(workspaceRoot = " ")))
        assertEquals("0 9 * * *", validateScheduleForm(valid, null, now, utc).getOrThrow().cronPattern)
        // An unchanged timing keeps an advanced cron.
        val edited = valid.copy(scheduleDays = listOf("TUE"))
        assertEquals("0 9 1 * *", validateScheduleForm(edited, "0 9 1 * *", now, utc).getOrThrow().cronPattern)
        val once = validateScheduleForm(valid.copy(scheduleType = ScheduleType.Once, scheduleDate = "2026-09-14", scheduleHour = "8"), null, now, utc).getOrThrow()
        assertNull(once.cronPattern)
        assertEquals(at("2026-09-14T08:00:00"), once.runAt)
    }

    // CR §0.7

    @Test fun `preview text collapses whitespace and appends three dots`() {
        assertEquals("a b", previewText(" a \n b "))
        assertEquals("${"x".repeat(150)}...", previewText("x".repeat(200)))
    }

    @Test fun `preview text never cuts a surrogate pair on the boundary`() {
        val grin = "😀"
        // The emoji occupies units 149-150, so the 150-unit cut backs off to 149.
        assertEquals("${"x".repeat(149)}...", previewText("x".repeat(149) + grin + "more"))
        assertEquals("${"x".repeat(148)}$grin...", previewText("x".repeat(148) + grin + "more"))
        assertEquals("${grin.repeat(75)}...", previewText(grin.repeat(80)))
    }
}
