package dev.cline.termux.data.schedules

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.data.tools.collapseWhitespace
import dev.cline.termux.data.tools.parseJsDate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.FormatStyle
import java.util.Locale

/*
 * Ports of the formatting and form helpers of
 * apps/examples/desktop-app/webview/components/views/settings/routine-view.tsx, plus
 * lib/routine-schedule-cron.ts (`preserveRoutineCron`), lib/routine-schedule-timezone.ts
 * (`routineScheduleTimezone`) and `previewText` from extensions-view.tsx (CR §0.7). The
 * account-view.tsx formatters live in ui/account/AccountFormat. Locale-dependent output
 * takes the zone, the locale and the 24-hour flag as parameters so tests are deterministic.
 */

/** `ONE_TIME_SCHEDULE_CRON_PATTERN` (shared/src/hub.ts). */
const val ONE_TIME_SCHEDULE_CRON_PATTERN = "0"

/** `ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY`. */
const val ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY = "__hubScheduleRunAt"

/** `CLINE_DEFAULT_MODEL_ID` (shared/src/providers/defaults.ts), the schedule editor's fallback. */
const val CLINE_DEFAULT_MODEL_ID = "anthropic/claude-sonnet-5"

/** Default mode of a new schedule; edits keep theirs. */
const val NEW_SCHEDULE_MODE = "yolo"

/** `WEEKDAY_OPTIONS`: cron day names in display order (their short labels are [ScheduleWords.dayShort]). */
val WEEKDAY_OPTIONS: List<String> = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")

private val ALL_DAYS = WEEKDAY_OPTIONS

/**
 * The copy the desktop formatters build into their output (routine-view.tsx: "Every day",
 * "Weekdays", "Daily", "Once", "<status> at <when>", "unknown", the `WEEKDAY_OPTIONS` short
 * labels, and the order and separators of "<days> · <time> · <zone>" and "Mon, Wed"). The UI layer resolves it from strings_schedules.xml, so these ports carry no
 * user-visible text of their own.
 */
@Immutable
data class ScheduleWords(
    val everyDay: String,
    val weekdays: String,
    val daily: String,
    val once: String,
    /** `%1$s at %2$s`: the run status, then when it ended. */
    val statusAt: String,
    val unknownStatus: String,
    /** Mon…Sun, in [WEEKDAY_OPTIONS] order. */
    val dayShort: List<String>,
    /** `%1$s · %2$s`: the frequency ("Once", "Daily" or the days), then the time. */
    val triggerPair: String,
    /** `%1$s · %2$s · %3$s`: the frequency, the time, then the schedule's time zone. */
    val triggerWithZone: String,
    /** `%1$s, %2$s`: folds the day labels left to right ("Mon, Wed, Fri"). */
    val daysJoin: String,
) {
    fun dayLabel(cron: String): String = WEEKDAY_OPTIONS.indexOf(cron).let { if (it >= 0) dayShort.getOrElse(it) { cron } else cron }
}
private val WEEKDAYS = listOf("MON", "TUE", "WED", "THU", "FRI")

/** Frequency of the editor. */
enum class ScheduleType(val wire: String) { Daily("daily"), Weekly("weekly"), Once("once") }

/** `ScheduleTiming` / the timing fields of `RoutineFormState`. Hour and minute are form strings. */
@Immutable
data class ScheduleTiming(
    val scheduleType: ScheduleType,
    val scheduleHour: String,
    val scheduleMinute: String,
    val scheduleDays: List<String>,
)

/** The schedule fields the formatters read (`RoutineSchedule`). */
@Immutable
data class ScheduleFacts(
    val cronPattern: String,
    val timezone: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val selectionProviderId: String? = null,
    val selectionModelId: String? = null,
    /** `metadata.__hubScheduleRunAt` when numeric. */
    val oneTimeRunAt: Long? = null,
    val nextRunAt: Long? = null,
)

/** The execution fields the formatters read (`RoutineExecution`). */
@Immutable
data class ExecutionFacts(
    val status: String? = null,
    val triggeredAt: Long? = null,
    val startedAt: Long? = null,
    val endedAt: Long? = null,
)

/*
 * Every time of day below follows [is24Hour], the user's "Use 24-hour format" setting
 * (ClockFormat); its default, the locale's own clock, is what these ports printed before.
 */

/** `formatDateTime` for an epoch-millis value ("Sep 13, 2026, 2:05 PM"): "-" when missing. */
fun formatDateTime(
    value: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String {
    if (value == null) return "-"
    return ClockFormat.formatDateTime(Instant.ofEpochMilli(value).atZone(zone), FormatStyle.MEDIUM, locale, is24Hour)
}

/** `formatDateTime` for a string value: "-" when blank, the text itself when unparseable. */
fun formatDateTime(
    value: String?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return "-"
    val epoch = trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: parseJsDate(trimmed, zone) ?: return trimmed
    return formatDateTime(epoch, zone, locale, is24Hour)
}

/** `formatScheduleModel`: "provider/model", else either, else "-". */
fun formatScheduleModel(schedule: ScheduleFacts): String {
    val provider = schedule.selectionProviderId?.trim()?.ifEmpty { null } ?: schedule.provider?.trim()?.ifEmpty { null }
    val model = schedule.selectionModelId?.trim()?.ifEmpty { null } ?: schedule.model?.trim()?.ifEmpty { null }
    if (provider != null && model != null) return "$provider/$model"
    return model ?: provider ?: "-"
}

/** `getScheduleProviderModel`: the editor's values, defaulting to cline + the default model. */
fun scheduleProviderModel(schedule: ScheduleFacts): Pair<String, String> {
    val provider = schedule.selectionProviderId?.trim()?.ifEmpty { null } ?: schedule.provider?.trim()?.ifEmpty { null } ?: "cline"
    val model = schedule.selectionModelId?.trim()?.ifEmpty { null } ?: schedule.model?.trim()?.ifEmpty { null } ?: CLINE_DEFAULT_MODEL_ID
    return provider to model
}

/** `formatExecutionResult`: "<status> at <when>", the status alone without a time, "-" without an execution. */
fun formatExecutionResult(
    execution: ExecutionFacts?,
    words: ScheduleWords,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String {
    if (execution == null) return "-"
    val status = execution.status?.trim()?.ifEmpty { null } ?: words.unknownStatus
    val `when` = formatDateTime(execution.endedAt ?: execution.startedAt ?: execution.triggeredAt, zone, locale, is24Hour)
    return if (`when` == "-") status else words.statusAt.format(status, `when`)
}

/** `formatExecutionTimestamp`. */
fun formatExecutionTimestamp(
    execution: ExecutionFacts,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String = formatDateTime(execution.endedAt ?: execution.startedAt ?: execution.triggeredAt, zone, locale, is24Hour)

/** `Number.parseInt(text, 10)`: leading integer digits, null when none. */
internal fun jsParseInt(text: String): Int? {
    val match = Regex("^\\s*([+-]?\\d+)").find(text) ?: return null
    return match.groupValues[1].toBigInteger().let { if (it.bitLength() < 32) it.toInt() else null }
}

/** `parseOptionalPositiveInt`: the timeout field. */
fun parseOptionalPositiveInt(value: String?): Int? {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return null
    return jsParseInt(trimmed)?.takeIf { it > 0 }
}

/** `parseTags`: comma separated, trimmed, empty → null. */
fun parseTags(value: String?): List<String>? {
    if (value == null) return null
    return value.split(",").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { null }
}

/** `normalizeScheduleDays`: known days in Monday-first order, deduplicated. */
fun normalizeScheduleDays(days: List<String>): List<String> {
    val selected = days.toSet()
    return ALL_DAYS.filter { it in selected }
}

/** `formatScheduleDays`: "Every day", "Weekdays", or "Mon, Wed". */
fun formatScheduleDays(days: List<String>, words: ScheduleWords): String {
    val normalized = normalizeScheduleDays(days)
    if (normalized.size == ALL_DAYS.size) return words.everyDay
    if (normalized == WEEKDAYS) return words.weekdays
    return normalized.map { value -> words.dayLabel(value) }.reduceOrNull { joined, day -> words.daysJoin.format(joined, day) }.orEmpty()
}

/** `formatScheduleTime`: localized short time on the device clock ("9:00 AM", "09:00"). */
fun formatScheduleTime(
    hour: String,
    minute: String,
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String {
    val h = (jsParseInt(hour) ?: 0).let { ((it % 24) + 24) % 24 }
    val m = (jsParseInt(minute) ?: 0).let { ((it % 60) + 60) % 60 }
    return ClockFormat.formatTime(LocalTime.of(h, m), locale, is24Hour)
}

/** `formatLocalDateInput`: yyyy-MM-dd. */
fun formatLocalDateInput(date: LocalDate): String = date.toString()

/** `minimumOneTimeDateTime`: one minute from now, seconds dropped. */
fun minimumOneTimeDateTime(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    Instant.ofEpochMilli(nowMs + 60_000).atZone(zone).toLocalDateTime().withSecond(0).withNano(0)

/** `defaultScheduleDate`: tomorrow. */
fun defaultScheduleDate(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    formatLocalDateInput(Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1))

/** `buildRunAt`: the once-schedule instant from the form's date, hour and minute (local time). */
fun buildRunAt(scheduleDate: String, scheduleHour: String, scheduleMinute: String, zone: ZoneId = ZoneId.systemDefault()): Long? {
    if (scheduleDate.isEmpty()) return null
    val text = "${scheduleDate}T${scheduleHour.padStart(2, '0')}:${scheduleMinute.padStart(2, '0')}:00"
    return runCatching { LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
}

/** `formatScheduleTrigger`: "Once · <when>", "Daily · 9:00 AM", "Weekdays · 9:00 AM · Europe/Berlin". */
fun formatScheduleTrigger(
    schedule: ScheduleFacts,
    words: ScheduleWords,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String {
    if (schedule.cronPattern == ONE_TIME_SCHEDULE_CRON_PATTERN) {
        return words.triggerPair.format(words.once, formatDateTime(schedule.oneTimeRunAt, zone, locale, is24Hour))
    }
    val parsed = parseCronPattern(schedule.cronPattern)
    val time = formatScheduleTime(parsed.scheduleHour, parsed.scheduleMinute, locale, is24Hour)
    val frequency = if (parsed.scheduleType == ScheduleType.Daily) words.daily else formatScheduleDays(parsed.scheduleDays, words)
    val timezone = schedule.timezone
    return if (!timezone.isNullOrEmpty()) words.triggerWithZone.format(frequency, time, timezone) else words.triggerPair.format(frequency, time)
}

/** `buildCronPattern`: "M H * * *" for every day, "M H * * MON,FRI" otherwise, "" without days. */
fun buildCronPattern(days: List<String>, hour: String, minute: String): String {
    val normalizedDays = normalizeScheduleDays(days)
    if (normalizedDays.isEmpty()) return ""
    val cronHour = jsParseInt(hour)?.coerceIn(0, 23) ?: 9
    val cronMinute = jsParseInt(minute)?.coerceIn(0, 59) ?: 0
    if (normalizedDays.size == ALL_DAYS.size) return "$cronMinute $cronHour * * *"
    return "$cronMinute $cronHour * * ${normalizedDays.joinToString(",")}"
}

private val DAY_RANGE = Regex("^([A-Z]{3})-([A-Z]{3})$")

/** `expandCronDays`: "*" → every day; names and name ranges ("MON-FRI"); anything else is ignored. */
fun expandCronDays(dayExpression: String?): List<String> {
    val raw = dayExpression?.trim()?.uppercase()
    if (raw.isNullOrEmpty() || raw == "*") return ALL_DAYS
    val values = mutableSetOf<String>()
    for (part in raw.split(",")) {
        val trimmed = part.trim()
        if (trimmed.isEmpty()) continue
        val range = DAY_RANGE.find(trimmed)
        if (range != null) {
            val start = ALL_DAYS.indexOf(range.groupValues[1])
            val end = ALL_DAYS.indexOf(range.groupValues[2])
            if (start >= 0 && end >= start) values += ALL_DAYS.subList(start, end + 1)
            continue
        }
        if (trimmed in ALL_DAYS) values += trimmed
    }
    return normalizeScheduleDays(values.toList())
}

private val WHITESPACE = Regex("\\s+")

/** `parseCronPattern`: the editor's timing from a cron string (defaults 9:00, weekdays). */
fun parseCronPattern(cronPattern: String): ScheduleTiming {
    val parts = cronPattern.trim().split(WHITESPACE)
    val minute = jsParseInt(parts.getOrNull(0) ?: "")
    val hour = jsParseInt(parts.getOrNull(1) ?: "")
    val days = expandCronDays(parts.getOrNull(4))
    return ScheduleTiming(
        scheduleType = if (parts.getOrNull(4) == "*") ScheduleType.Daily else ScheduleType.Weekly,
        scheduleHour = if (hour != null && hour in 0..23) hour.toString() else "9",
        scheduleMinute = if (minute != null && minute in 0..59) minute.toString() else "0",
        scheduleDays = days.ifEmpty { WEEKDAYS },
    )
}

/** `parseScheduleTrigger`: the editor's timing plus date for an existing schedule. */
fun parseScheduleTrigger(schedule: ScheduleFacts, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<ScheduleTiming, String> {
    if (schedule.cronPattern == ONE_TIME_SCHEDULE_CRON_PATTERN) {
        val date = Instant.ofEpochMilli(schedule.oneTimeRunAt ?: schedule.nextRunAt ?: nowMs).atZone(zone)
        return ScheduleTiming(ScheduleType.Once, date.hour.toString(), date.minute.toString(), WEEKDAYS) to formatLocalDateInput(date.toLocalDate())
    }
    return parseCronPattern(schedule.cronPattern) to defaultScheduleDate(nowMs, zone)
}

/** `preserveRoutineCron`: unrelated edits must not reduce advanced cron expressions to daily/weekly. */
fun preserveRoutineCron(original: String, initial: ScheduleTiming, current: ScheduleTiming, replacement: String?): String? {
    val unchanged = initial.scheduleType == current.scheduleType &&
        initial.scheduleHour == current.scheduleHour &&
        initial.scheduleMinute == current.scheduleMinute &&
        (initial.scheduleType != ScheduleType.Weekly || initial.scheduleDays.sorted() == current.scheduleDays.sorted())
    return if (unchanged) original else replacement
}

/** `routineScheduleTimezone`: defaults apply only to new recurring schedules; edits keep theirs. */
fun routineScheduleTimezone(
    scheduleType: ScheduleType,
    existingTimezone: String?,
    isExisting: Boolean,
    deviceZone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (scheduleType == ScheduleType.Once) return null
    if (isExisting) return existingTimezone
    return deviceZone.id
}

/** The editor form (`RoutineFormState`). */
@Immutable
data class ScheduleForm(
    val name: String = "",
    val scheduleType: ScheduleType = ScheduleType.Daily,
    val scheduleDate: String = "",
    val scheduleHour: String = "9",
    val scheduleMinute: String = "0",
    val scheduleDays: List<String> = WEEKDAYS,
    val prompt: String = "",
    val provider: String = "",
    val model: String = "",
    val workspaceRoot: String = "",
    val systemPrompt: String = "",
    val timeoutSeconds: String = "",
    val tags: String = "",
    val enabled: Boolean = true,
) {
    val timing: ScheduleTiming get() = ScheduleTiming(scheduleType, scheduleHour, scheduleMinute, scheduleDays)
}

/** Validation failures of `submitCreateForm`, in the desktop's order. */
enum class ScheduleFormError {
    /** "Routine name is required." */
    NameRequired,
    /** "Choose a one-time date and time in the future." */
    OnceInPast,
    /** "Select at least one weekday." */
    NoWeekday,
    /** "Prompt is required." */
    PromptRequired,
    /** "Workspace is required." */
    WorkspaceRequired,
}

/** A validated form: what `schedule.create` / `schedule.update` receives for the trigger. */
@Immutable
data class ValidSchedule(val name: String, val cronPattern: String?, val runAt: Long?, val prompt: String, val workspaceRoot: String)

/**
 * `submitCreateForm`'s checks and trigger build. [editingCron] is the edited schedule's
 * cron (null for a new one); an unchanged timing keeps an advanced cron as it was.
 */
fun validateScheduleForm(form: ScheduleForm, editingCron: String?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Result<ValidSchedule> {
    fun fail(error: ScheduleFormError) = Result.failure<ValidSchedule>(ScheduleFormException(error))
    val name = form.name.trim()
    if (name.isEmpty()) return fail(ScheduleFormError.NameRequired)
    val runAt = if (form.scheduleType == ScheduleType.Once) buildRunAt(form.scheduleDate, form.scheduleHour, form.scheduleMinute, zone) else null
    if (form.scheduleType == ScheduleType.Once && (runAt == null || runAt <= nowMs)) return fail(ScheduleFormError.OnceInPast)
    val edited = when (form.scheduleType) {
        ScheduleType.Daily -> buildCronPattern(ALL_DAYS, form.scheduleHour, form.scheduleMinute)
        ScheduleType.Weekly -> buildCronPattern(form.scheduleDays, form.scheduleHour, form.scheduleMinute)
        ScheduleType.Once -> null
    }
    val cronPattern = if (editingCron != null && editingCron != ONE_TIME_SCHEDULE_CRON_PATTERN) {
        preserveRoutineCron(editingCron, parseCronPattern(editingCron), form.timing, edited)
    } else {
        edited
    }
    if (form.scheduleType == ScheduleType.Weekly && cronPattern.isNullOrEmpty()) return fail(ScheduleFormError.NoWeekday)
    val prompt = form.prompt.trim()
    if (prompt.isEmpty()) return fail(ScheduleFormError.PromptRequired)
    val workspaceRoot = form.workspaceRoot.trim()
    if (workspaceRoot.isEmpty()) return fail(ScheduleFormError.WorkspaceRequired)
    return Result.success(ValidSchedule(name, cronPattern, runAt, prompt, workspaceRoot))
}

/** Carries a [ScheduleFormError] through [Result]. */
class ScheduleFormException(val error: ScheduleFormError) : Exception(error.name)

// ---------------------------------------------------------------- other CR §0.7 helpers

/** `previewText` (extensions-view.tsx): collapse whitespace, cut at [maxLength], trim, "...". */
fun previewText(input: String, maxLength: Int = 150): String {
    val compact = collapseWhitespace(input)
    if (compact.length <= maxLength) return compact
    return "${takeGraphemePrefix(compact, maxLength).trimEnd()}..."
}
