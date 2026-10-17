package dev.cline.termux.data.sessions

import dev.cline.termux.data.chat.takeGraphemePrefix
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.tools.collapseWhitespace
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.isChatWorkspacePath
import dev.cline.termux.data.workspace.normalizeWorkspacePath
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Display formatting for the sessions surfaces, ported 1:1 (design/screens/sessions.md §1.4):
 * webview/components/utils.ts `normalizeTitle` (= shared prompt/format.ts
 * `formatDisplayUserInput`), hooks/use-session-history.ts (`toTitle`, `basenamePath`,
 * `formatTokenCount`, `formatCostUsd`, `parseTimestamp`, `normalizeDiscoveredStatus`),
 * views/sessions/sessions-view.tsx (`formatCompactTokens`, `tokensLabel`),
 * lib/session-history.ts (`getSessionSourceLabel`) and lib/sidebar-session-organization.ts
 * (`uniqueWorkspaceLabel`). Labels with user-visible copy return copy-free shapes the UI turns
 * into strings with its own resources: the S2 workspace and model columns are
 * `SessionsLabels.workspaceKind` / `modelText` (ui/sessions), and date-dependent labels
 * ([compactAge], the Android form of `formatRelativeTime`, and [scheduleRunLabel]) are typed
 * values rendered with `DateUtils`.
 */

// ---------------------------------------------------------------- formatDisplayUserInput

// One port of shared prompt/format.ts lives in data/session ([UserInputFormat]); these
// top-level names forward to it so the sessions surfaces keep their call sites.

/** `normalizeUserInput`: unwrap `<user_input>` / `<user_command>` wrappers. */
fun normalizeUserInput(input: String?): String = UserInputFormat.normalizeUserInput(input)

/** `stripModeNotices`: drop runtime `<mode_notice>` elements (indexOf based, as upstream). */
fun stripModeNotices(input: String?): String = UserInputFormat.stripModeNotices(input)

/** `UserCommandEnvelope`. */
typealias UserCommandEnvelope = UserInputFormat.UserCommandEnvelope

/** `parseUserCommandEnvelope`. */
fun parseUserCommandEnvelope(input: String?): UserCommandEnvelope? = UserInputFormat.parseUserCommandEnvelope(input)

/** `formatDisplayUserInput`: what a persisted user prompt reads as. */
fun formatDisplayUserInput(input: String?): String = UserInputFormat.formatDisplayUserInput(input)

/** `normalizeTitle` (components/utils.ts). */
fun normalizeTitle(title: String?): String = if (title.isNullOrBlank()) "" else formatDisplayUserInput(title)

const val SESSION_SEARCH_TITLE_MAX_LENGTH = 240
const val SESSION_SEARCH_PREVIEW_MAX_LENGTH = 480

/** `compactSessionSearchText`: whitespace collapsed, ellipsized at [maxLength]. */
fun compactSessionSearchText(input: String, maxLength: Int): String {
    val compact = collapseWhitespace(input)
    if (compact.length <= maxLength) return compact
    return "${takeGraphemePrefix(compact, maxLength - 1).trimEnd()}…"
}

/** `formatSessionSearchTitle`. */
fun formatSessionSearchTitle(input: String?): String =
    compactSessionSearchText(formatDisplayUserInput(input), SESSION_SEARCH_TITLE_MAX_LENGTH)

/**
 * `formatSessionSearchPreview`: user and session snippets are unwrapped like a
 * persisted prompt; assistant and tool snippets keep their text verbatim.
 */
fun formatSessionSearchPreview(role: String, input: String?): String {
    val trimmed = input?.trim().orEmpty()
    val normalizedRole = role.lowercase()
    val display = if (normalizedRole == "user" || normalizedRole == "session") formatDisplayUserInput(trimmed) else trimmed
    return compactSessionSearchText(display, SESSION_SEARCH_PREVIEW_MAX_LENGTH)
}

/** The first line of a normalized text, or "" (rows render the first line only). */
fun firstLine(text: String?): String = normalizeTitle(text).trim().split("\n").firstOrNull()?.trim() ?: ""

/**
 * `toTitle`: the custom title, else the first line of the prompt, else
 * "Session <last 6 of id>".
 */
fun sessionTitle(sessionId: String, metadataTitle: String?, prompt: String?): String {
    val custom = metadataTitle?.trim().orEmpty()
    if (custom.isNotEmpty()) return custom
    val line = firstLine(prompt)
    if (line.isNotEmpty()) return line
    return "Session ${sessionId.takeLast(6)}"
}

/** The desktop's "Session xxxxxx" placeholder title (title hydration candidates). */
fun isPlaceholderTitle(title: String): Boolean = title.startsWith("Session ")

/** `titleFromMessages`: the first user (then assistant) text line. */
fun titleFromMessages(messages: List<Pair<String, String>>): String? {
    for (role in listOf("user", "assistant")) {
        for ((messageRole, content) in messages) {
            if (messageRole != role) continue
            val line = firstLine(content)
            if (line.isNotEmpty()) return line
        }
    }
    return null
}

// ---------------------------------------------------------------- paths and labels

/**
 * `basenamePath`: "Chat" for the chat workspace, "~" for the Termux home *(Android)*,
 * else the last segment; "workspace" when nothing is left.
 */
fun basenamePath(input: String?, home: String = TERMUX_HOME): String {
    if (input.isNullOrEmpty()) return "workspace"
    if (isChatWorkspacePath(input)) return "Chat"
    val trimmed = input.replace(Regex("[\\\\/]+$"), "")
    if (trimmed.isEmpty()) return "workspace"
    if (home.isNotEmpty() && normalizeWorkspacePath(trimmed) == normalizeWorkspacePath(home)) return "~"
    return trimmed.split(Regex("[\\\\/]")).last().ifEmpty { "workspace" }
}

private fun pathSegments(path: String): List<String> =
    path.replace(Regex("[\\\\/]+$"), "").split(Regex("[\\\\/]")).filter { it.isNotEmpty() }

/** `uniqueWorkspaceLabel`: the shortest trailing path unique among [workspacePaths]; "Other" when blank. */
fun uniqueWorkspaceLabel(path: String, workspacePaths: List<String>): String {
    if (path.isEmpty()) return "Other"
    val segments = pathSegments(path)
    val allSegments = workspacePaths.map(::pathSegments)
    for (depth in 1..segments.size) {
        val candidate = segments.takeLast(depth).joinToString("/")
        val matches = allSegments.count { it.takeLast(depth).joinToString("/") == candidate }
        if (matches == 1) return candidate
    }
    return path
}

/** `getSessionSourceLabel`: CLI, Desktop, VS Code; other sources title-cased per part. */
fun getSessionSourceLabel(source: String): String {
    when (source.trim().lowercase()) {
        "cli" -> return "CLI"
        "desktop" -> return "Desktop"
        "vscode", "vscode-webview" -> return "VS Code"
    }
    return source.trim().split(Regex("[-_\\s]+")).filter { it.isNotEmpty() }
        .joinToString(" ") { it.substring(0, 1).uppercase() + it.substring(1) }
}

// ---------------------------------------------------------------- numbers

/** `Number.prototype.toFixed(digits)` on the exact binary value. */
internal fun toFixed(value: Double, digits: Int): String =
    BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

/** `formatCompactTokens` (sessions-view.tsx): "13.8m", "132.6k", "11"; negatives and NaN read "0". */
fun formatCompactTokens(value: Double): String {
    if (!value.isFinite() || value < 0) return "0"
    if (value >= 1_000_000) return "${toFixed(value / 1_000_000, 1)}m"
    if (value >= 1_000) return "${toFixed(value / 1_000, 1)}k"
    return if (value == Math.rint(value)) value.toLong().toString() else value.toString()
}

fun formatCompactTokens(value: Long): String = formatCompactTokens(value.toDouble())

/** `tokensLabel` (S2): "12.5k/3.2k", "-" when both are unknown. */
fun tokensLabel(inputTokens: Long?, outputTokens: Long?): String {
    if (inputTokens == null && outputTokens == null) return "-"
    return "${formatCompactTokens(inputTokens ?: 0)}/${formatCompactTokens(outputTokens ?: 0)}"
}

/** `formatTokenCount` (F6 details): null when the total is ≤ 0; "14k" from 10 000, "2.6k" from 1 000. */
fun formatTokenCount(inputTokens: Long?, outputTokens: Long?): String? {
    val total = (inputTokens ?: 0) + (outputTokens ?: 0)
    if (total <= 0) return null
    if (total >= 1000) return "${toFixed(total / 1000.0, if (total >= 10000) 0 else 1)}k"
    return total.toString()
}

/** `formatCostUsd`: null for unknown, non-finite or ≤ 0; 4 / 3 / 2 decimals below $0.01 / $1 / above. */
fun formatCostUsd(value: Double?): String? {
    if (value == null || !value.isFinite() || value <= 0) return null
    return when {
        value < 0.01 -> "$${toFixed(value, 4)}"
        value < 1 -> "$${toFixed(value, 3)}"
        else -> "$${toFixed(value, 2)}"
    }
}

// ---------------------------------------------------------------- time

private val TEN_DIGITS = Regex("^\\d{10}$")

/** `parseTimestamp`: epoch seconds (10 digits), epoch millis, or a date string; null when unparseable. */
fun parseTimestamp(value: String?): Long? {
    if (value.isNullOrEmpty()) return null
    val trimmed = value.trim()
    trimmed.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { epoch ->
        return if (TEN_DIGITS.matches(trimmed)) (epoch * 1000).toLong() else epoch.toLong()
    }
    return dev.cline.termux.data.tools.parseJsDate(trimmed)
}

/** [compactAge] result; the UI renders it with its own strings and `DateUtils`. */
sealed interface CompactAge {
    data object Now : CompactAge
    data class Minutes(val count: Long) : CompactAge
    data class Hours(val count: Long) : CompactAge
    data object Yesterday : CompactAge
    data class Days(val count: Long) : CompactAge
    /** An abbreviated date; [sameYear] drops the year (`FORMAT_NO_YEAR`). */
    data class Date(val epochMillis: Long, val sameYear: Boolean) : CompactAge
}

/**
 * `compactAge` (SS §1.4, Android adaptation of `formatRelativeTime`): < 1 min now; < 1 h
 * Nm; < 24 h Nh; previous calendar day Yesterday; < 7 days Nd; then a date.
 */
fun compactAge(activityAtMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): CompactAge {
    val diff = maxOf(0L, nowMs - activityAtMs)
    val minute = 60_000L
    val hour = 60 * minute
    if (diff < minute) return CompactAge.Now
    if (diff < hour) return CompactAge.Minutes(maxOf(1, diff / minute))
    if (diff < 24 * hour) return CompactAge.Hours(maxOf(1, diff / hour))
    val then = Instant.ofEpochMilli(activityAtMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(then, today)
    if (days <= 1L) return CompactAge.Yesterday
    if (days < 7L) return CompactAge.Days(days)
    return CompactAge.Date(activityAtMs, then.year == today.year)
}

/** [scheduleRunLabel] result. */
sealed interface ScheduleRunLabel {
    /** "Run N". */
    data class Numbered(val runNumber: Int) : ScheduleRunLabel
    /** A short date-time of when the run started. */
    data class StartedAt(val epochMillis: Long) : ScheduleRunLabel
    /** "Run". */
    data object Unknown : ScheduleRunLabel
}

/** `scheduleRunLabel`: "Run N" when the runner numbered it, else the start time, else "Run". */
fun scheduleRunLabel(runNumber: Int?, startedAtMs: Long?): ScheduleRunLabel = when {
    runNumber != null && runNumber > 0 -> ScheduleRunLabel.Numbered(runNumber)
    startedAtMs != null -> ScheduleRunLabel.StartedAt(startedAtMs)
    else -> ScheduleRunLabel.Unknown
}

/** `normalizeDiscoveredStatus`: the desktop status vocabulary of the `status:` facet. */
fun normalizeDiscoveredStatus(status: String?, prompt: String?): String {
    val normalized = (status ?: "").lowercase()
    val hasPrompt = !prompt.isNullOrBlank()
    return when {
        normalized == "ended" || normalized == "expired" || "complete" in normalized || "done" in normalized -> "completed"
        "cancel" in normalized || "abort" in normalized || "interrupt" in normalized -> "cancelled"
        "provision" in normalized -> "provisioning"
        "fail" in normalized || "error" in normalized -> "failed"
        "run" in normalized || "start" in normalized -> if (hasPrompt) "running" else "idle"
        else -> "idle"
    }
}
