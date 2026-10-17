package dev.cline.termux.ui.approval

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.schedules.ClockFormat
import dev.cline.termux.data.tools.asRecord
import dev.cline.termux.data.tools.jsNumber
import dev.cline.termux.data.tools.jsString
import dev.cline.termux.data.tools.jsonStringify
import dev.cline.termux.data.tools.nonEmptyString
import dev.cline.termux.data.tools.normalizeJsonValue
import dev.cline.termux.ui.icons.normalizeToolName
import java.time.Instant
import java.time.ZoneId
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/*
 * The readable per-tool preview above the raw JSON (composer-approvals.md C3.5) and the
 * desktop's two formatting functions from `tool-approval-panel.tsx`
 * (`formatApprovalInput`, `formatApprovalTimestamp`). Pure Kotlin; the sheet maps the lines
 * to styled paragraphs. Every branch falls back to the raw JSON when the input does not
 * match the shape it expects (the core tool schemas accept unions).
 */

/** One paragraph of the detail box. Each line is its own paragraph so a wrapped continuation never looks like a new marker line. */
@Immutable
sealed interface PreviewLine {
    /** `$ <command>` (run_commands). */
    data class Command(val command: String) : PreviewLine

    /** A file path line (editor header, `ClineMono.small`). */
    data class Path(val path: String) : PreviewLine

    /** A read_files entry: `<path>` or `<path> (start–end)`. */
    data class FileRead(val path: String, val startLine: Int? = null, val endLine: Int? = null) : PreviewLine {
        val text: String get() = path + rangeSuffix(startLine, endLine)
    }

    /** Added line (diff added colours): editor text drawn as `+ <text>`, or a verbatim patch line (`+foo`, [verbatim]). */
    data class Added(val text: String, val verbatim: Boolean = false) : PreviewLine

    /** Removed line (diff removed colours): `- <text>`, or a verbatim patch line. */
    data class Removed(val text: String, val verbatim: Boolean = false) : PreviewLine

    /** apply_patch `*** ` / `@@` header, drawn in `onSurfaceVariant`. */
    data class Header(val text: String) : PreviewLine

    /** apply_patch context line (no marker colour). */
    data class Context(val text: String) : PreviewLine

    /** fetch_web_content URL (`primary`, not clickable). */
    data class Url(val url: String) : PreviewLine

    /** fetch_web_content prompt (`bodySmall onSurfaceVariant`). */
    data class Prompt(val prompt: String) : PreviewLine

    /** search_codebase query. */
    data class Query(val query: String) : PreviewLine

    /** "Create file" *(Android)*. */
    data object CreateFile : PreviewLine

    /** "Insert at line N" *(Android)*. */
    data class InsertAt(val line: Int) : PreviewLine

    /** "…" after [ApprovalPreviews.MAX_SIDE_LINES] lines of one side. */
    data object More : PreviewLine
}

/** The preview chosen by the normalized tool name; [Raw] shows only the raw JSON (no "Show raw input" button). */
@Immutable
sealed interface ApprovalPreview {
    data class Lines(val lines: List<PreviewLine>) : ApprovalPreview
    data object Raw : ApprovalPreview
}

object ApprovalPreviews {
    /** Editor previews keep at most this many lines per side, then "…" (C3.5). */
    const val MAX_SIDE_LINES = 40

    /** Builds the preview for [toolName] from the parsed `inputJson`. */
    fun build(toolName: String, input: JsonElement?): ApprovalPreview {
        val lines = when (normalizeToolName(toolName)) {
            "run_commands" -> runCommands(input)
            "read_files" -> readFiles(input)
            "editor" -> editor(input)
            "apply_patch" -> applyPatch(input)
            "fetch_web_content" -> fetchWebContent(input)
            "search_codebase" -> searchCodebase(input)
            else -> null
        }
        return if (lines.isNullOrEmpty()) ApprovalPreview.Raw else ApprovalPreview.Lines(lines)
    }

    // ---- run_commands --------------------------------------------------------------------

    private fun runCommands(input: JsonElement?): List<PreviewLine>? {
        val value = normalizeJsonValue(input)
        val commands = mutableListOf<String>()
        var malformed = false
        fun entry(e: JsonElement?) {
            val text = e.jsString()
            if (text != null) {
                if (text.isNotEmpty()) commands += text
                return
            }
            val record = e.asRecord()
            val command = record?.get("command").nonEmptyString()
            if (command == null) {
                malformed = true
                return
            }
            val args = record?.get("args")
            if (args != null && args !is JsonNull && args !is JsonArray) {
                malformed = true
                return
            }
            val argv = (args as? JsonArray)?.map { a -> a.jsString() ?: run { malformed = true; "" } }.orEmpty()
            commands += (listOf(command) + argv.map(::shellQuote)).joinToString(" ")
        }
        when (value) {
            is JsonArray -> value.forEach(::entry)
            is JsonObject -> when {
                value["commands"] != null -> {
                    val raw = value["commands"]
                    if (raw is JsonArray) raw.forEach(::entry) else entry(raw)
                }
                value["command"] != null -> entry(value)
                value["cmd"] != null -> entry(value["cmd"])
                else -> return null
            }
            else -> if (value.jsString() != null) entry(value) else return null
        }
        if (malformed || commands.isEmpty()) return null
        return commands.map(PreviewLine::Command)
    }

    /** POSIX single-quoting for an argument that contains whitespace or is empty; other arguments pass unchanged. */
    fun shellQuote(arg: String): String {
        if (arg.isNotEmpty() && arg.none { it.isWhitespace() || it == '\'' }) return arg
        return "'" + arg.replace("'", "'\\''") + "'"
    }

    // ---- read_files ----------------------------------------------------------------------

    private fun readFiles(input: JsonElement?): List<PreviewLine>? {
        val value = normalizeJsonValue(input)
        val files = mutableListOf<PreviewLine.FileRead>()
        var malformed = false
        fun entry(e: JsonElement?) {
            val text = e.jsString()
            if (text != null) {
                if (text.isNotEmpty()) files += PreviewLine.FileRead(text) else malformed = true
                return
            }
            val record = e.asRecord()
            val path = (record?.get("path") ?: record?.get("file_path") ?: record?.get("filePath")).nonEmptyString()
            if (path == null) {
                malformed = true
                return
            }
            files += PreviewLine.FileRead(path, record?.get("start_line").lineNumber(), record?.get("end_line").lineNumber())
        }
        fun entries(e: JsonElement?) = if (e is JsonArray) e.forEach(::entry) else entry(e)
        when (value) {
            is JsonArray -> value.forEach(::entry)
            is JsonObject -> {
                val list = value["files"] ?: value["file_paths"] ?: value["paths"]
                if (list != null) entries(list) else entry(value)
            }
            else -> if (value.jsString() != null) entry(value) else return null
        }
        if (malformed || files.isEmpty()) return null
        return files
    }

    /** `z.coerce.number().int().positive()`: numbers and numeric strings. */
    private fun JsonElement?.lineNumber(): Int? {
        val n = jsNumber() ?: jsString()?.trim()?.toDoubleOrNull() ?: return null
        return n.takeIf { it == Math.rint(it) && it > 0 && it <= Int.MAX_VALUE }?.toInt()
    }

    private fun JsonElement?.intValue(): Int? {
        val n = jsNumber() ?: jsString()?.trim()?.toDoubleOrNull() ?: return null
        return n.takeIf { it == Math.rint(it) && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    }

    // ---- editor ---------------------------------------------------------------------------

    private fun editor(input: JsonElement?): List<PreviewLine>? {
        val record = normalizeJsonValue(input).asRecord() ?: return null
        val path = record["path"].nonEmptyString() ?: return null
        val newText = record["new_text"].jsString() ?: return null
        val oldRaw = record["old_text"]
        if (oldRaw != null && oldRaw !is JsonNull && oldRaw.jsString() == null) return null
        val oldText = oldRaw.jsString()
        val insertRaw = record["insert_line"]
        val insertLine = if (insertRaw == null || insertRaw is JsonNull) null else insertRaw.intValue() ?: return null
        val lines = mutableListOf<PreviewLine>(PreviewLine.Path(path))
        when {
            insertLine != null -> {
                lines += PreviewLine.InsertAt(insertLine)
                lines += side(newText) { PreviewLine.Added(it) }
            }
            oldText == null -> {
                lines += PreviewLine.CreateFile
                lines += side(newText) { PreviewLine.Added(it) }
            }
            else -> {
                lines += side(oldText) { PreviewLine.Removed(it) }
                lines += side(newText) { PreviewLine.Added(it) }
            }
        }
        return lines
    }

    private fun side(text: String, line: (String) -> PreviewLine): List<PreviewLine> {
        val split = text.split("\n")
        val kept = split.take(MAX_SIDE_LINES).map(line)
        return if (split.size > MAX_SIDE_LINES) kept + PreviewLine.More else kept
    }

    // ---- apply_patch ------------------------------------------------------------------------

    private fun applyPatch(input: JsonElement?): List<PreviewLine>? {
        val text = input.jsString() ?: input.asRecord()?.get("input").jsString() ?: return null
        if (text.isBlank()) return null
        return text.trimEnd('\n').split("\n").map { l ->
            when {
                l.startsWith("*** ") || l.startsWith("@@") -> PreviewLine.Header(l)
                l.startsWith("+") -> PreviewLine.Added(l, verbatim = true)
                l.startsWith("-") -> PreviewLine.Removed(l, verbatim = true)
                else -> PreviewLine.Context(l)
            }
        }
    }

    // ---- fetch_web_content ------------------------------------------------------------------

    private fun fetchWebContent(input: JsonElement?): List<PreviewLine>? {
        val requests = normalizeJsonValue(input).asRecord()?.get("requests") as? JsonArray ?: return null
        val lines = mutableListOf<PreviewLine>()
        for (r in requests) {
            val record = r.asRecord() ?: return null
            val url = record["url"].nonEmptyString() ?: return null
            lines += PreviewLine.Url(url)
            record["prompt"].nonEmptyString()?.let { lines += PreviewLine.Prompt(it) }
        }
        return lines.ifEmpty { null }
    }

    // ---- search_codebase --------------------------------------------------------------------

    private fun searchCodebase(input: JsonElement?): List<PreviewLine>? {
        val value = normalizeJsonValue(input)
        val raw = (value as? JsonObject)?.let { it["queries"] ?: return null } ?: value
        val queries = when (raw) {
            is JsonArray -> raw.map { it.jsString() ?: return null }
            else -> listOf(raw.jsString() ?: return null)
        }.filter { it.isNotEmpty() }
        return queries.map(PreviewLine::Query).ifEmpty { null }
    }
}

/** ` (10–80)` / ` (10+)` / ` (1–80)`: the tool rows' range suffix (`ToolSummary`). */
private fun rangeSuffix(startLine: Int?, endLine: Int?): String = when {
    startLine != null && endLine != null -> " ($startLine–$endLine)"
    startLine != null -> " ($startLine+)"
    endLine != null -> " (1–$endLine)"
    else -> ""
}

/**
 * Desktop `formatApprovalInput`: `{}` for null, a string as is, otherwise
 * `JSON.stringify(input, null, 2)`.
 */
fun formatApprovalInput(input: JsonElement?): String = when {
    input == null || input is JsonNull -> "{}"
    input.jsString() != null -> input.jsString()!!
    else -> jsonStringify(input, "  ")
}

/**
 * Desktop `formatApprovalTimestamp` (`toLocaleString()`: date and time, every time): the
 * localized short date and short time in [zone], the time on the user's 24-hour setting
 * ([is24Hour], ClockFormat); null when the time is unknown, which the card shows as "Pending now".
 */
fun formatApprovalTimestamp(
    epochMs: Long?,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    is24Hour: Boolean = ClockFormat.localeDefaultIs24Hour(locale),
): String? {
    if (epochMs == null || epochMs <= 0L) return null
    return runCatching {
        ClockFormat.formatDateTime(Instant.ofEpochMilli(epochMs).atZone(zone), FormatStyle.SHORT, locale, is24Hour)
    }.getOrNull()
}

/** The plain text of a preview line (copy, TalkBack); [createFile] / [insertAt] supply the *(Android)* strings. */
fun PreviewLine.plainText(createFile: String, insertAt: (Int) -> String, more: String): String = when (this) {
    is PreviewLine.Command -> "$ $command"
    is PreviewLine.Path -> path
    is PreviewLine.FileRead -> text
    is PreviewLine.Added -> if (verbatim) text else "+ $text"
    is PreviewLine.Removed -> if (verbatim) text else "- $text"
    is PreviewLine.Header -> text
    is PreviewLine.Context -> text
    is PreviewLine.Url -> url
    is PreviewLine.Prompt -> prompt
    is PreviewLine.Query -> query
    PreviewLine.CreateFile -> createFile
    is PreviewLine.InsertAt -> insertAt(line)
    PreviewLine.More -> more
}
