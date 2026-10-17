package dev.cline.termux.ui.schedules

import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import dev.cline.termux.R
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.schedules.ClockFormat
import dev.cline.termux.data.schedules.ROUTINE_TEMPLATES
import dev.cline.termux.data.schedules.RoutineTemplate
import dev.cline.termux.data.schedules.ScheduleExecution
import dev.cline.termux.data.schedules.ScheduleForm
import dev.cline.termux.data.schedules.ScheduleFormError
import dev.cline.termux.data.schedules.ScheduleRecord
import dev.cline.termux.data.schedules.ScheduleType
import dev.cline.termux.data.schedules.ScheduleWords
import dev.cline.termux.data.schedules.SchedulesRepository
import dev.cline.termux.data.schedules.WEEKDAY_OPTIONS
import dev.cline.termux.data.schedules.formatDateTime
import dev.cline.termux.data.schedules.formatExecutionResult
import dev.cline.termux.data.schedules.formatExecutionTimestamp
import dev.cline.termux.data.schedules.formatScheduleModel
import dev.cline.termux.data.schedules.formatScheduleTrigger
import dev.cline.termux.data.schedules.minimumOneTimeDateTime
import dev.cline.termux.data.schedules.normalizeScheduleDays
import dev.cline.termux.data.schedules.parseScheduleTrigger
import dev.cline.termux.data.schedules.scheduleProviderModel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import android.os.Parcel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * UI models of the Schedules screens (customize-and-rest.md §4.8 "ViewModel") and the pure
 * projections from the repository's records. Everything here is plain Kotlin so the unit tests
 * pin the desktop card lines (routine-view.tsx) without Compose. Formatting takes the zone and
 * locale as parameters, like the ported formatters in data/schedules.
 */

/** One card of C5 and the line set of C7 (desktop card lines, already formatted). */
@Immutable
data class ScheduleCard(
    val record: ScheduleRecord,
    /** `formatScheduleTrigger`: "Daily · 5:00 PM · Asia/Shanghai". */
    val trigger: String,
    /** `formatScheduleModel`: "provider/model" or "-". */
    val model: String,
    /** `formatDateTime(schedule.nextRunAt || upcoming.nextRunAt)`. */
    val nextRun: String,
    /** `formatDateTime(schedule.lastRunAt)`. */
    val lastRun: String,
    /** `formatExecutionResult(lastExecution)`. */
    val lastResult: String,
    val lastExecution: ScheduleExecution?,
    val active: ScheduleExecution?,
    /** `formatDateTime(active.startedAt)` when [active] is set. */
    val activeSince: String?,
) {
    val id: String get() = record.scheduleId
    val name: String get() = record.name
}

/** The run row's status group (desktop: success/completed ✓, failed/timeout/aborted ✕, else clock). */
enum class RunStatus { Succeeded, Failed, Running, Waiting }

/** One row of C7 "Runs". */
@Immutable
data class RunRow(
    val executionId: String,
    /** The kernel status verbatim (lower case), empty when the Hub sent none. */
    val status: String,
    val group: RunStatus,
    val errorMessage: String?,
    /** `formatExecutionTimestamp`. */
    val timestamp: String,
    val sessionId: String?,
)

/** C7's content for one schedule. */
@Immutable
data class ScheduleDetailsModel(
    val card: ScheduleCard,
    val runs: List<RunRow>,
    /** `JSON.stringify(schedule, null, 2)` of the record as received. */
    val json: String,
)

object ScheduleProjection {
    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun card(
        record: ScheduleRecord,
        upcomingNextRunAt: Long?,
        lastExecution: ScheduleExecution?,
        active: ScheduleExecution?,
        zone: ZoneId,
        locale: Locale,
        words: ScheduleWords,
        /** The user's "Use 24-hour format" setting (ClockFormat). */
        is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
    ): ScheduleCard = ScheduleCard(
        record = record,
        trigger = formatScheduleTrigger(record.facts(), words, zone, locale, is24Hour),
        model = formatScheduleModel(record.facts()),
        // Desktop `schedule.nextRunAt || upcoming?.nextRunAt` (0 counts as missing).
        nextRun = formatDateTime(record.nextRunAt?.takeIf { it != 0L } ?: upcomingNextRunAt, zone, locale, is24Hour),
        lastRun = formatDateTime(record.lastRunAt, zone, locale, is24Hour),
        lastResult = formatExecutionResult(lastExecution?.facts(), words, zone, locale, is24Hour),
        lastExecution = lastExecution,
        active = active,
        activeSince = active?.let { formatDateTime(it.startedAt, zone, locale, is24Hour) },
    )

    /** The list's cards, sorted by name case-insensitively (the repository already sorts). */
    fun cards(
        overview: SchedulesRepository.Overview,
        zone: ZoneId,
        locale: Locale,
        words: ScheduleWords,
        is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
    ): List<ScheduleCard> =
        overview.schedules.sortedBy { it.name.lowercase(Locale.ROOT) }.map { record ->
            card(
                record = record,
                upcomingNextRunAt = overview.upcoming.firstOrNull { it.scheduleId == record.scheduleId }?.nextRunAt,
                lastExecution = overview.lastExecutions[record.scheduleId],
                active = overview.activeFor(record.scheduleId),
                zone = zone,
                locale = locale,
                words = words,
                is24Hour = is24Hour,
            )
        }

    fun runGroup(status: String): RunStatus = when (status.lowercase(Locale.ROOT)) {
        "success", "completed" -> RunStatus.Succeeded
        "failed", "timeout", "aborted" -> RunStatus.Failed
        "running" -> RunStatus.Running
        else -> RunStatus.Waiting
    }

    fun runRow(
        execution: ScheduleExecution,
        zone: ZoneId,
        locale: Locale,
        is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
    ): RunRow = RunRow(
        executionId = execution.executionId,
        status = execution.status.trim().lowercase(Locale.ROOT),
        group = runGroup(execution.status.trim()),
        errorMessage = execution.errorMessage?.takeIf { it.isNotBlank() },
        timestamp = formatExecutionTimestamp(execution.facts(), zone, locale, is24Hour),
        sessionId = execution.sessionId?.trim()?.ifEmpty { null },
    )

    fun details(
        card: ScheduleCard,
        executions: List<ScheduleExecution>,
        zone: ZoneId,
        locale: Locale,
        is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
    ): ScheduleDetailsModel = ScheduleDetailsModel(
        card = card,
        runs = executions.map { runRow(it, zone, locale, is24Hour) },
        json = prettyJson.encodeToString(JsonObject.serializer(), card.record.raw),
    )

    /** "Suggested": templates whose name no schedule uses yet (desktop `visibleTemplates`). */
    fun templates(names: Collection<String>): List<RoutineTemplate> =
        dev.cline.termux.data.schedules.suggestedTemplates(names)

    /**
     * The keep-alive notice (§4.4 "Keep-alive off"): some schedule is enabled, and either
     * "Keep Termux awake" is off or the One UI checklist has unconfirmed items, and the user has
     * not dismissed it for this app version.
     */
    fun keepAliveNotice(
        anyEnabled: Boolean,
        keepAwake: Boolean,
        checklistConfirmed: Boolean,
        dismissedFor: String?,
        appVersion: String,
    ): Boolean = anyEnabled && (!keepAwake || !checklistConfirmed) && dismissedFor != appVersion

    /** The run rows C7 shows: the first three until "Show all N runs" (desktop `slice(0, 3)`). */
    fun runsPreview(runs: List<RunRow>, showAll: Boolean): List<RunRow> = if (showAll) runs else runs.take(RUNS_PREVIEW)

    const val RUNS_PREVIEW = 3
}

// ------------------------------------------------------------------ editor form

/** A form field the error box points at (`isError` on that field). */
enum class FormField { Name, Date, Time, Days, Prompt, Provider, Model, Workspace, Timeout }

/**
 * The editor form (§4.8 `ScheduleFormState`), kept in `SavedStateHandle`. [editingId] names
 * the edited schedule (the record itself stays in the ViewModel; it is re-read after process
 * death). No secrets.
 */
@Immutable
data class ScheduleFormState(
    val name: String = "",
    val type: ScheduleType = ScheduleType.Once,
    val date: LocalDate,
    val hour: Int = 9,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = WEEKDAYS,
    val prompt: String = DEFAULT_PROMPT,
    val provider: String = "",
    val model: String = "",
    val workspaceRoot: String = "",
    val systemPrompt: String = "",
    val timeoutSeconds: String = "",
    val tags: String = "",
    val enabled: Boolean = true,
    val editingId: String? = null,
) : Parcelable {

    // Written by hand: the parcelize compiler plugin is applied but its runtime
    // (`kotlinx.parcelize.Parcelize`) is not on the classpath under AGP's built-in Kotlin.
    override fun describeContents(): Int = 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(name)
        dest.writeString(type.name)
        dest.writeString(date.toString())
        dest.writeInt(hour)
        dest.writeInt(minute)
        dest.writeString(days.toCronDays().joinToString(","))
        dest.writeString(prompt)
        dest.writeString(provider)
        dest.writeString(model)
        dest.writeString(workspaceRoot)
        dest.writeString(systemPrompt)
        dest.writeString(timeoutSeconds)
        dest.writeString(tags)
        dest.writeInt(if (enabled) 1 else 0)
        dest.writeString(editingId)
    }

    /** The ported form (`RoutineFormState`) the validators read. */
    fun toForm(): ScheduleForm = ScheduleForm(
        name = name,
        scheduleType = type,
        scheduleDate = date.toString(),
        scheduleHour = hour.toString(),
        scheduleMinute = minute.toString(),
        scheduleDays = days.toCronDays(),
        prompt = prompt,
        provider = provider,
        model = model,
        workspaceRoot = workspaceRoot,
        systemPrompt = systemPrompt,
        timeoutSeconds = timeoutSeconds,
        tags = tags,
        enabled = enabled,
    )

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ScheduleFormState> = object : Parcelable.Creator<ScheduleFormState> {
            override fun createFromParcel(source: Parcel): ScheduleFormState = ScheduleFormState(
                name = source.readString().orEmpty(),
                type = runCatching { ScheduleType.valueOf(source.readString().orEmpty()) }.getOrDefault(ScheduleType.Once),
                date = LocalDate.parse(source.readString()),
                hour = source.readInt(),
                minute = source.readInt(),
                days = source.readString().orEmpty().split(",").filter { it.isNotEmpty() }.toDaysOfWeek(),
                prompt = source.readString().orEmpty(),
                provider = source.readString().orEmpty(),
                model = source.readString().orEmpty(),
                workspaceRoot = source.readString().orEmpty(),
                systemPrompt = source.readString().orEmpty(),
                timeoutSeconds = source.readString().orEmpty(),
                tags = source.readString().orEmpty(),
                enabled = source.readInt() == 1,
                editingId = source.readString(),
            )

            override fun newArray(size: Int): Array<ScheduleFormState?> = arrayOfNulls(size)
        }

        /** Desktop default prompt of a new schedule. */
        const val DEFAULT_PROMPT = "Review PRs opened yesterday and summarize issues."

        /** Mon–Fri, the desktop's preset days. */
        val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

        /**
         * A new schedule (desktop `openCreateDialog`): Once, tomorrow, 9:00, Mon–Fri, the
         * default prompt; a [template] sets name, frequency, days, time and prompt.
         */
        fun create(
            nowMs: Long,
            zone: ZoneId,
            provider: String,
            model: String,
            workspaceRoot: String,
            template: RoutineTemplate? = null,
        ): ScheduleFormState {
            val tomorrow = java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1)
            val base = ScheduleFormState(date = tomorrow, provider = provider, model = model, workspaceRoot = workspaceRoot)
            if (template == null) return base
            return base.copy(
                name = template.name,
                type = template.scheduleType,
                days = template.scheduleDays.toDaysOfWeek(),
                hour = template.scheduleHour.toIntOrNull() ?: 9,
                minute = template.scheduleMinute.toIntOrNull() ?: 0,
                prompt = template.prompt,
            )
        }

        /** An existing schedule (desktop `openEditDialog`). */
        fun edit(record: ScheduleRecord, nowMs: Long, zone: ZoneId): ScheduleFormState {
            val (timing, date) = parseScheduleTrigger(record.facts(), nowMs, zone)
            val (provider, model) = scheduleProviderModel(record.facts())
            return ScheduleFormState(
                name = record.name,
                type = timing.scheduleType,
                date = runCatching { LocalDate.parse(date) }.getOrElse { java.time.Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1) },
                hour = timing.scheduleHour.toIntOrNull() ?: 9,
                minute = timing.scheduleMinute.toIntOrNull() ?: 0,
                days = timing.scheduleDays.toDaysOfWeek().ifEmpty { WEEKDAYS },
                prompt = record.prompt,
                provider = provider,
                model = model,
                workspaceRoot = record.workspaceRoot,
                systemPrompt = record.systemPrompt.orEmpty(),
                timeoutSeconds = record.timeoutSeconds?.toString().orEmpty(),
                tags = record.tags.joinToString(","),
                enabled = record.enabled,
                editingId = record.scheduleId,
            )
        }
    }
}

private val CRON_DAY: Map<DayOfWeek, String> = mapOf(
    DayOfWeek.MONDAY to "MON", DayOfWeek.TUESDAY to "TUE", DayOfWeek.WEDNESDAY to "WED", DayOfWeek.THURSDAY to "THU",
    DayOfWeek.FRIDAY to "FRI", DayOfWeek.SATURDAY to "SAT", DayOfWeek.SUNDAY to "SUN",
)

/** Monday-first cron day names (`normalizeScheduleDays`). */
fun Set<DayOfWeek>.toCronDays(): List<String> = normalizeScheduleDays(map { CRON_DAY.getValue(it) })

fun List<String>.toDaysOfWeek(): Set<DayOfWeek> =
    mapNotNullTo(linkedSetOf()) { name -> CRON_DAY.entries.firstOrNull { it.value == name.uppercase(Locale.ROOT) }?.key }

/** The chips in Mon…Sun order (`WEEKDAY_OPTIONS`); their labels are [ScheduleWords.dayShort]. */
val DAY_CHIPS: List<DayOfWeek> = WEEKDAY_OPTIONS.map { cron -> CRON_DAY.entries.first { it.value == cron }.key }

/**
 * The once-schedule minimum (desktop `minimumOneTimeDateTime`): one minute from now, seconds
 * dropped. Picking the minimum date with an earlier time clamps the time to it.
 */
object OnceClamp {
    fun minimum(nowMs: Long, zone: ZoneId): LocalDateTime = minimumOneTimeDateTime(nowMs, zone)

    /** The form after the date changes to [date] (time clamped on the minimum date). */
    fun withDate(form: ScheduleFormState, date: LocalDate, nowMs: Long, zone: ZoneId): ScheduleFormState {
        val min = minimum(nowMs, zone)
        val next = form.copy(date = date)
        return if (date == min.toLocalDate() && isBefore(next.hour, next.minute, min)) next.copy(hour = min.hour, minute = min.minute) else next
    }

    /** The form after the time changes; for Once on the minimum date earlier times clamp to the minimum. */
    fun withTime(form: ScheduleFormState, hour: Int, minute: Int, nowMs: Long, zone: ZoneId): ScheduleFormState {
        val next = form.copy(hour = hour, minute = minute)
        if (form.type != ScheduleType.Once) return next
        val min = minimum(nowMs, zone)
        return if (form.date == min.toLocalDate() && isBefore(hour, minute, min)) next.copy(hour = min.hour, minute = min.minute) else next
    }

    private fun isBefore(hour: Int, minute: Int, min: LocalDateTime): Boolean = hour * 60 + minute < min.hour * 60 + min.minute
}

// ------------------------------------------------------------------ copy mapping

/** The message of an editor validation failure. */
@StringRes
fun ScheduleFormError.message(): Int = when (this) {
    ScheduleFormError.NameRequired -> R.string.schedule_error_name_required
    ScheduleFormError.OnceInPast -> R.string.schedule_error_once_in_past
    ScheduleFormError.NoWeekday -> R.string.schedule_error_no_weekday
    ScheduleFormError.PromptRequired -> R.string.schedule_error_prompt_required
    ScheduleFormError.WorkspaceRequired -> R.string.schedule_error_workspace_required
}

fun ScheduleFormError.field(): FormField = when (this) {
    ScheduleFormError.NameRequired -> FormField.Name
    ScheduleFormError.OnceInPast -> FormField.Time
    ScheduleFormError.NoWeekday -> FormField.Days
    ScheduleFormError.PromptRequired -> FormField.Prompt
    ScheduleFormError.WorkspaceRequired -> FormField.Workspace
}

/** CR §0.5: these go to a snackbar; the rest are shown in the screen's error surface. */
val RepoFailure.isTransient: Boolean
    get() = this == RepoFailure.HubDisconnected || this == RepoFailure.HubDraining

/** The run status label resource (desktop `capitalize`); null for statuses the Hub may add later. */
@StringRes
fun runStatusLabel(status: String): Int? = when (status.lowercase(Locale.ROOT)) {
    "completed" -> R.string.schedule_run_completed
    "failed" -> R.string.schedule_run_failed
    "timeout" -> R.string.schedule_run_timeout
    "aborted" -> R.string.schedule_run_aborted
    "running" -> R.string.schedule_run_running
    "pending" -> R.string.schedule_run_pending
    "success" -> R.string.schedule_run_success
    else -> null
}

/** CSS `capitalize` for an unknown kernel status (shown as data). */
fun capitalizeStatus(status: String): String =
    status.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

/** The template a `schedules/edit?template=` route names. */
fun templateById(id: String?): RoutineTemplate? = id?.let { wanted -> ROUTINE_TEMPLATES.firstOrNull { it.id == wanted } }
