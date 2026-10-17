package dev.cline.termux.ui.sessions

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.data.sessions.ActionFailure
import dev.cline.termux.data.sessions.CompactAge
import dev.cline.termux.data.sessions.ScheduleRunLabel
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.data.sessions.basenamePath
import dev.cline.termux.data.sessions.compactAge
import dev.cline.termux.data.workspace.isChatWorkspacePath
import dev.cline.termux.ui.components.copyPluralStringResource
import kotlinx.coroutines.delay

/*
 * Resource-backed renderings of the pure sessions formatters (sessions.md §1.4): the data
 * layer returns typed values and desktop English for data; everything a user reads as app
 * copy is resolved here (docs/07 Merge 1 rule).
 */

/** The row title: the summary's title, or "Session <last 6>" from resources for the id fallback. */
@Composable
fun SessionSummary.displayTitle(): String {
    val firstLine = title.lineSequence().firstOrNull().orEmpty()
    return if (!hasCustomTitle && SessionsLabels.isIdFallback(this)) {
        stringResource(R.string.sessions_title_fallback, id.takeLast(6))
    } else {
        firstLine
    }
}

/** Pure label helpers (unit-tested). */
object SessionsLabels {
    /** `"Session " + id.takeLast(6)`: the data layer's last-resort title. */
    fun isIdFallback(summary: SessionSummary): Boolean = summary.title == "Session " + summary.id.takeLast(6)

    /** S2 workspace column kind (§1.4): none, the chat workspace, Termux home, or a basename. */
    fun workspaceKind(path: String): WorkspaceKind = when {
        path.isBlank() -> WorkspaceKind.None
        isChatWorkspacePath(path) -> WorkspaceKind.Chat
        else -> when (val base = basenamePath(path)) {
            "~" -> WorkspaceKind.Home
            else -> WorkspaceKind.Name(base)
        }
    }

    /** `modelLabel` without its "No model" English (null = none). */
    fun modelText(provider: String, model: String): String? = when {
        provider.isNotEmpty() && model.isNotEmpty() -> "$provider:$model"
        model.isNotEmpty() -> model
        provider.isNotEmpty() -> provider
        else -> null
    }
}

sealed interface WorkspaceKind {
    data object None : WorkspaceKind
    data object Chat : WorkspaceKind
    data object Home : WorkspaceKind
    data class Name(val text: String) : WorkspaceKind
}

@Composable
fun workspaceText(path: String): String = when (val kind = SessionsLabels.workspaceKind(path)) {
    WorkspaceKind.None -> stringResource(R.string.sessions_no_workspace)
    WorkspaceKind.Chat -> stringResource(R.string.sessions_workspace_chat)
    WorkspaceKind.Home -> stringResource(R.string.sessions_workspace_home)
    is WorkspaceKind.Name -> kind.text
}

@Composable
fun modelText(summary: SessionSummary): String =
    SessionsLabels.modelText(summary.provider, summary.model) ?: stringResource(R.string.sessions_no_model)

@Composable
fun ProjectLabel.text(): String = when (this) {
    ProjectLabel.Chat -> stringResource(R.string.sessions_workspace_chat)
    ProjectLabel.Home -> stringResource(R.string.sessions_project_home)
    ProjectLabel.Other -> stringResource(R.string.sessions_workspace_other)
    is ProjectLabel.Path -> text
}

@Composable
fun ScheduleRunLabel.text(): String = when (this) {
    is ScheduleRunLabel.Numbered -> stringResource(R.string.sessions_schedule_run_number, runNumber)
    is ScheduleRunLabel.StartedAt -> DateUtils.formatDateTime(
        LocalContext.current,
        epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_NO_YEAR,
    )
    ScheduleRunLabel.Unknown -> stringResource(R.string.sessions_schedule_run_fallback)
}

/** The once-a-minute clock rows re-evaluate their ages on (voice `rememberMinuteTick`). */
@Composable
fun rememberMinuteTick(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(MINUTE_MS - now % MINUTE_MS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

private const val MINUTE_MS = 60_000L

/** `compactAge` rendered (§1.4): "now", "5m", "3h", "Yesterday", "4d", "Sep 13", "Sep 13, 2025". */
@Composable
fun ageText(activityAt: Long, now: Long): String = when (val age = compactAge(activityAt, now)) {
    CompactAge.Now -> stringResource(R.string.sessions_age_now)
    is CompactAge.Minutes -> stringResource(R.string.sessions_age_minutes, age.count.toInt())
    is CompactAge.Hours -> stringResource(R.string.sessions_age_hours, age.count.toInt())
    CompactAge.Yesterday -> stringResource(R.string.sessions_age_yesterday)
    is CompactAge.Days -> stringResource(R.string.sessions_age_days, age.count.toInt())
    is CompactAge.Date -> DateUtils.formatDateTime(
        LocalContext.current,
        age.epochMillis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or (if (age.sameYear) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR),
    )
}

/**
 * TalkBack's unabbreviated age ("5 minutes ago"). Clamped to [now] like [compactAge], so a record
 * stamped a moment ahead of this device's clock never reads as "in 0 minutes"; under a minute it
 * reads the visible "now" instead of `DateUtils`' "0 minutes ago".
 */
fun Context.spokenAge(activityAt: Long, now: Long): String =
    if (compactAge(activityAt, now) == CompactAge.Now) {
        getString(R.string.sessions_age_now)
    } else {
        DateUtils.getRelativeTimeSpanString(minOf(activityAt, now), now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

/** A token count read aloud ("12 thousand"), ICU long compact form. */
fun spokenCount(value: Long, locale: java.util.Locale = java.util.Locale.getDefault()): String =
    android.icu.text.CompactDecimalFormat.getInstance(locale, android.icu.text.CompactDecimalFormat.CompactStyle.LONG).format(value)

/** Joins TalkBack parts with the localised separator ("a, b, c"). */
fun Context.joinA11y(parts: List<String>): String =
    parts.filter { it.isNotBlank() }.reduceOrNull { acc, part -> getString(R.string.sessions_a11y_join, acc, part) }.orEmpty()

/** "<title>. <description>" for a [SessionsMessage] (DS §4.1 #17). */
fun Context.messageText(message: SessionsMessage): String = when (message) {
    SessionsMessage.LoadMoreFailed -> getString(
        R.string.sessions_snackbar,
        getString(R.string.sessions_load_more_failed_title),
        getString(R.string.sessions_unavailable),
    )
    is SessionsMessage.ActionFailed -> {
        val (title, fallback) = when (message.action) {
            FailedAction.Rename -> R.string.sessions_rename_failed to R.string.sessions_rename_failed_body
            FailedAction.Pin -> R.string.sessions_pin_failed to R.string.sessions_pin_failed_body
            FailedAction.Unpin -> R.string.sessions_unpin_failed to R.string.sessions_pin_failed_body
            FailedAction.Fork -> R.string.sessions_fork_failed to R.string.sessions_fork_failed_body
            FailedAction.Delete -> R.string.sessions_delete_failed to R.string.sessions_delete_failed_body
        }
        getString(R.string.sessions_snackbar, getString(title), failureText(message.failure, fallback))
    }
}

/** The description part of a failure snackbar (sessions.md §5, §6). */
fun Context.failureText(failure: ActionFailure, fallback: Int): String = when (failure) {
    is ActionFailure.Kernel -> failure.message.trim().ifEmpty { getString(fallback) }
    ActionFailure.NotConnected -> getString(R.string.sessions_hub_not_connected)
    ActionFailure.Gone -> getString(R.string.sessions_session_gone)
    ActionFailure.EmptySession -> getString(R.string.sessions_fork_empty)
    ActionFailure.NotApplied, ActionFailure.MissingModel, ActionFailure.Busy, ActionFailure.TimedOut -> getString(fallback)
}

/** "N runs" / "1 run". */
@Composable
fun runCountText(count: Int): String = copyPluralStringResource(R.plurals.sessions_schedule_runs, count, count)
