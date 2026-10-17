package dev.cline.termux.data.diff

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One edit region of a file (desktop `SessionDiffHunk`): [old] and [new] are the replaced
 * and replacing text, joined with `\n`, starting at [oldStart] / [newStart] (1-based).
 */
@Immutable
data class SessionDiffHunk(val oldStart: Int, val newStart: Int, val old: String, val new: String) {
    /**
     * Desktop `diff-view.tsx` rule: an editor create or an apply_patch `Add File` carries the
     * complete new contents, so line numbers are meaningful and the gutter is shown. Every
     * other hunk is a fragment of the file and hides its line numbers.
     */
    val isCompleteNewContents: Boolean get() = old.isEmpty() && oldStart == 1 && newStart == 1
}

/** One file of the session's changes (desktop `SessionFileDiff`). */
@Immutable
data class SessionFileDiff(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val hunks: List<SessionDiffHunk>,
    /** Android: true when [SessionDiff.mergeToolDiffs] dropped older hunks (it keeps the last 30). */
    val hunksTruncated: Boolean = false,
)

@Immutable
data class SessionDiffSummary(val additions: Int, val deletions: Int) {
    val isEmpty: Boolean get() = additions == 0 && deletions == 0

    companion object {
        /** Desktop `EMPTY_DIFF_SUMMARY`. */
        val EMPTY = SessionDiffSummary(0, 0)
    }
}

@Immutable
data class SessionDiffState(val fileDiffs: List<SessionFileDiff>, val summary: SessionDiffSummary) {
    companion object {
        val EMPTY = SessionDiffState(emptyList(), SessionDiffSummary.EMPTY)
    }
}

/**
 * The desktop's `SessionHookEvent`, reduced to what the parsers read. The hook event name is
 * always `tool_result`: the app builds these only from `tool.finished` events and history
 * `tool_result` blocks (CT §1.2 mapping table).
 */
@Immutable
data class ToolResultEvent(
    val toolName: String,
    val toolInput: JsonElement?,
    val toolOutput: JsonElement?,
    val toolError: String?,
)

/**
 * Pure port of `apps/examples/desktop-app/webview/lib/session-diff.ts` (and of
 * `resolveWorkspaceFilePath` from `webview/lib/workspace-paths.ts`). Function names and
 * edge cases follow the desktop one to one; only `editor` and `apply_patch` results
 * produce diffs.
 */
object SessionDiff {
    /** Desktop `mergeToolDiffs`: `[...existing.hunks, ...diff.hunks].slice(-30)`. */
    const val MAX_HUNKS_PER_FILE = 30

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = false }

    fun build(events: List<ToolResultEvent>, cwd: String?): SessionDiffState {
        val fileDiffs = mergeToolDiffs(events, cwd)
        return SessionDiffState(fileDiffs, summarize(fileDiffs))
    }

    fun summarize(files: List<SessionFileDiff>): SessionDiffSummary {
        var additions = 0
        var deletions = 0
        for (file in files) {
            additions += file.additions
            deletions += file.deletions
        }
        return SessionDiffSummary(additions, deletions)
    }

    fun mergeToolDiffs(events: List<ToolResultEvent>, cwd: String?): List<SessionFileDiff> {
        val byPath = LinkedHashMap<String, SessionFileDiff>()
        for (event in events) {
            val diffs = ArrayList<SessionFileDiff>()
            parseEditorFileDiff(event)?.let(diffs::add)
            diffs.addAll(parseApplyPatchFileDiffs(event))
            if (diffs.isEmpty()) continue

            for (diff in diffs) {
                val (key, display) = canonicalizeDiffPath(diff.path, cwd)
                val existing = byPath[key]
                if (existing == null) {
                    byPath[key] = diff.copy(path = display)
                    continue
                }
                val combined = existing.hunks + diff.hunks
                byPath[key] = existing.copy(
                    additions = existing.additions + diff.additions,
                    deletions = existing.deletions + diff.deletions,
                    hunks = combined.takeLast(MAX_HUNKS_PER_FILE),
                    hunksTruncated = existing.hunksTruncated || diff.hunksTruncated ||
                        combined.size > MAX_HUNKS_PER_FILE,
                )
            }
        }
        return byPath.values.toList()
    }

    // region apply_patch

    private fun stripApplyPatchWrapperLines(lines: List<String>): List<String> {
        val result = ArrayList<String>(lines.size)
        var insidePatch = false
        for (line in lines) {
            if (line.startsWith("*** Begin Patch")) {
                insidePatch = true
                result.add(line)
                continue
            }
            if (line == "*** End Patch") {
                insidePatch = false
                result.add(line)
                continue
            }
            val isBashWrapperLine = line.startsWith("%%bash") ||
                line.startsWith("apply_patch") ||
                line == "EOF" ||
                line.startsWith("```")
            if (isBashWrapperLine && !insidePatch) continue
            result.add(line)
        }
        return result
    }

    fun parseApplyPatchInput(input: String): List<SessionFileDiff> {
        val lines = stripApplyPatchWrapperLines(input.split("\n").map { it.removeSuffix("\r") })
        val fileDiffs = ArrayList<SessionFileDiff>()

        fun findNextActionLine(start: Int): Int {
            for (i in start until lines.size) {
                val line = lines[i]
                if (line.startsWith("*** Add File: ") ||
                    line.startsWith("*** Update File: ") ||
                    line.startsWith("*** Delete File: ") ||
                    line == "*** End Patch"
                ) {
                    return i
                }
            }
            return lines.size
        }

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.startsWith(ADD_FILE)) {
                val path = line.substring(ADD_FILE.length).jsTrim()
                val end = findNextActionLine(index + 1)
                val addedLines = ArrayList<String>()
                for (i in index + 1 until end) {
                    val bodyLine = lines[i]
                    if (bodyLine.startsWith("+")) addedLines.add(bodyLine.substring(1))
                }
                fileDiffs.add(
                    SessionFileDiff(
                        path = path,
                        additions = addedLines.size,
                        deletions = 0,
                        hunks = if (addedLines.isNotEmpty()) {
                            listOf(SessionDiffHunk(oldStart = 1, newStart = 1, old = "", new = addedLines.joinToString("\n")))
                        } else {
                            emptyList()
                        },
                    ),
                )
                index = end
                continue
            }

            if (line.startsWith(DELETE_FILE)) {
                val path = line.substring(DELETE_FILE.length).jsTrim()
                fileDiffs.add(SessionFileDiff(path = path, additions = 0, deletions = 0, hunks = emptyList()))
                index += 1
                continue
            }

            if (line.startsWith(UPDATE_FILE)) {
                val path = line.substring(UPDATE_FILE.length).jsTrim()
                val end = findNextActionLine(index + 1)

                var additions = 0
                var deletions = 0
                var oldLineNo = 1
                var newLineNo = 1
                var active: ActiveHunk? = null
                val hunks = ArrayList<SessionDiffHunk>()

                fun flushActiveHunk() {
                    val hunk = active ?: return
                    hunks.add(
                        SessionDiffHunk(
                            oldStart = hunk.oldStart,
                            newStart = hunk.newStart,
                            old = hunk.old.joinToString("\n"),
                            new = hunk.new.joinToString("\n"),
                        ),
                    )
                    active = null
                }

                for (i in index + 1 until end) {
                    val bodyLine = lines[i]
                    when {
                        bodyLine.startsWith("@@") -> flushActiveHunk()
                        bodyLine.startsWith("+") -> {
                            additions += 1
                            val hunk = active ?: ActiveHunk(oldLineNo, newLineNo).also { active = it }
                            hunk.new.add(bodyLine.substring(1))
                            newLineNo += 1
                        }
                        bodyLine.startsWith("-") -> {
                            deletions += 1
                            val hunk = active ?: ActiveHunk(oldLineNo, newLineNo).also { active = it }
                            hunk.old.add(bodyLine.substring(1))
                            oldLineNo += 1
                        }
                        bodyLine.startsWith(" ") -> {
                            flushActiveHunk()
                            oldLineNo += 1
                            newLineNo += 1
                        }
                        bodyLine == "*** End of File" -> flushActiveHunk()
                    }
                }
                flushActiveHunk()

                fileDiffs.add(SessionFileDiff(path = path, additions = additions, deletions = deletions, hunks = hunks))
                index = end
                continue
            }

            index += 1
        }
        return fileDiffs
    }

    private class ActiveHunk(val oldStart: Int, val newStart: Int) {
        val old = ArrayList<String>()
        val new = ArrayList<String>()
    }

    private fun parseApplyPatchFileDiffs(event: ToolResultEvent): List<SessionFileDiff> {
        if (event.toolName != "apply_patch" || !event.toolError.isNullOrEmpty()) return emptyList()
        val output = normalizeToolOutput(event.toolOutput) ?: return emptyList()
        if (output.isFalse("success")) return emptyList()
        // apply_patch accepts either { input: string } or a raw patch string.
        val patchInput = event.toolInput.stringValue() ?: asRecord(event.toolInput)?.get("input").stringValue()
            ?: return emptyList()
        return parseApplyPatchInput(patchInput)
    }

    // endregion

    // region editor

    private val EDITOR_DIFF_LINE = Regex("^([+-])(\\d+):\\s?([^\\n\\r\\u2028\\u2029]*)$")

    private class EditorParse(val additions: Int, val deletions: Int, val hunks: List<SessionDiffHunk>)

    private fun parseDiffFromEditorResult(resultText: String): EditorParse {
        val lines = resultText.split("\n")
        val startIdx = lines.indexOfFirst { it.jsTrim() == "```diff" }
        if (startIdx < 0) return EditorParse(0, 0, emptyList())
        var endIdx = -1
        for (i in startIdx + 1 until lines.size) {
            if (lines[i].jsTrim() == "```") {
                endIdx = i
                break
            }
        }
        val body = lines.subList(startIdx + 1, if (endIdx > startIdx) endIdx else lines.size)

        val old = ArrayList<String>()
        val next = ArrayList<String>()
        var additions = 0
        var deletions = 0
        var oldStart: Int? = null
        var newStart: Int? = null

        for (raw in body) {
            val match = EDITOR_DIFF_LINE.matchEntire(raw) ?: continue
            val op = match.groupValues[1]
            val lineNo = match.groupValues[2].toIntOrNull() ?: continue
            val text = match.groupValues[3]
            if (op == "-") {
                deletions += 1
                old.add(text)
                if (oldStart == null) oldStart = lineNo
                continue
            }
            additions += 1
            next.add(text)
            if (newStart == null) newStart = lineNo
        }

        if (additions + deletions == 0) return EditorParse(0, 0, emptyList())
        return EditorParse(
            additions,
            deletions,
            listOf(
                SessionDiffHunk(
                    oldStart = oldStart ?: 1,
                    newStart = newStart ?: 1,
                    old = old.joinToString("\n"),
                    new = next.joinToString("\n"),
                ),
            ),
        )
    }

    private fun parseEditorFileDiff(event: ToolResultEvent): SessionFileDiff? {
        if (event.toolName != "editor" || !event.toolError.isNullOrEmpty()) return null
        val input = asRecord(event.toolInput) ?: return null
        val output = normalizeToolOutput(event.toolOutput) ?: return null
        if (output.isFalse("success")) return null

        // Current editor schema has no `command` field; derive the operation from the
        // input shape (legacy `command` values still take precedence).
        val command = input["command"].stringValue() ?: when {
            input["insert_line"].let { it != null && it !is JsonNull } -> "insert"
            input["old_text"].stringValue() != null -> "str_replace"
            else -> "create"
        }
        val pathFromInput = input["path"].stringValue()
        val query = output["query"].stringValue()
        val pathFromQuery = if (query != null && query.contains(":")) {
            query.split(":").drop(1).joinToString(":")
        } else {
            null
        }
        val path = pathFromInput?.takeIf { it.isNotEmpty() } ?: pathFromQuery?.takeIf { it.isNotEmpty() } ?: return null

        val resultText = output["result"].stringValue() ?: ""

        if (command == "str_replace" && !resultText.startsWith("File created")) {
            val parsed = parseDiffFromEditorResult(resultText)
            return SessionFileDiff(path = path, additions = parsed.additions, deletions = parsed.deletions, hunks = parsed.hunks)
        }

        if (command == "create" || command == "insert" || command == "str_replace") {
            val newContent = input["new_text"].stringValue()
                ?: input["file_text"].stringValue()
                ?: input["new_str"].stringValue()
                ?: ""
            return SessionFileDiff(
                path = path,
                additions = countAddedLines(newContent),
                deletions = 0,
                hunks = if (newContent.isNotEmpty()) {
                    listOf(SessionDiffHunk(oldStart = 1, newStart = 1, old = "", new = newContent))
                } else {
                    emptyList()
                },
            )
        }
        return null
    }

    private fun countAddedLines(value: String?): Int {
        if (value.isNullOrEmpty()) return 0
        return value.split("\n").count { it.isNotEmpty() }
    }

    // endregion

    // region tool output normalisation

    /**
     * Tool outputs arrive in several shapes depending on the source: a plain record (live
     * events), a JSON-encoded string, or a list of content blocks such as
     * `[{ type: "text", text: "<json>" }]` (persisted history).
     */
    internal fun normalizeToolOutput(value: JsonElement?): JsonObject? {
        if (value is JsonPrimitive && value.isString) {
            val parsed = try {
                lenientJson.parseToJsonElement(value.content)
            } catch (_: Exception) {
                return null
            }
            return asRecord(parsed)
        }
        if (value is JsonArray) {
            for (entry in value) {
                val record = asRecord(entry) ?: continue
                val text = record["text"]
                if (text is JsonPrimitive && text.isString) {
                    val inner = normalizeToolOutput(text)
                    if (inner != null) return inner
                    continue
                }
                return record
            }
            return null
        }
        return asRecord(value)
    }

    /**
     * JS `typeof value === "object"`: arrays count as records too (with no named fields),
     * so an array where the desktop expects a record reads as an empty record here.
     */
    private fun asRecord(value: JsonElement?): JsonObject? = when (value) {
        is JsonObject -> value
        is JsonArray -> EMPTY_RECORD
        else -> null
    }

    private val EMPTY_RECORD = JsonObject(emptyMap())

    /** Desktop `toStringValue`: a non-empty string, else null. */
    private fun JsonElement?.stringValue(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content

    /** `record[key] === false` (a JSON boolean, not the string "false"). */
    private fun JsonObject.isFalse(key: String): Boolean {
        val value = this[key] as? JsonPrimitive ?: return false
        return !value.isString && value.content == "false"
    }

    // endregion

    // region paths

    private val ROOT_PATTERN = Regex("^(?:\\\\\\\\|[A-Za-z]:[\\\\/]|[\\\\/])")
    private val DRIVE_PATTERN = Regex("^[A-Za-z]:")
    private val SEPARATORS = Regex("[\\\\/]+")
    private val WINDOWS_ABSOLUTE = Regex("^(?:[A-Za-z]:[\\\\/]|\\\\\\\\)")
    private val TRAILING_SEPARATORS = Regex("[\\\\/]+$")

    private fun isAbsoluteFilePath(path: String): Boolean = path.startsWith("/") || WINDOWS_ABSOLUTE.containsMatchIn(path)

    /**
     * Port of `resolveWorkspaceFilePath`: tool outputs carry paths as the agent wrote them,
     * sometimes absolute, sometimes relative to the session cwd. Relative paths are joined
     * against the cwd with the separator style the cwd already uses.
     */
    fun resolveAbsolutePath(path: String, cwd: String?): String {
        val trimmed = path.jsTrim()
        val base = (cwd ?: "").jsTrim()
        if (trimmed.isEmpty() || base.isEmpty() || isAbsoluteFilePath(trimmed)) return trimmed
        val separator = if (base.contains("\\") && !base.contains("/")) "\\" else "/"
        return base.replace(TRAILING_SEPARATORS, "") + separator + trimmed.removePrefix("./")
    }

    /**
     * Collapses "." and ".." segments and rebuilds the path with [separator]. Both separator
     * styles count as separators. ".." that would climb above an absolute root is dropped;
     * on relative paths a leading ".." is kept.
     */
    internal fun collapsePathSegments(path: String, separator: String): String {
        val rawRoot = ROOT_PATTERN.find(path)?.value ?: ""
        val root = when {
            rawRoot.isEmpty() -> ""
            rawRoot == "\\\\" -> "\\\\"
            DRIVE_PATTERN.containsMatchIn(rawRoot) -> rawRoot.substring(0, 2) + separator
            else -> separator
        }
        val segments = ArrayList<String>()
        for (segment in path.substring(rawRoot.length).split(SEPARATORS)) {
            if (segment.isEmpty() || segment == ".") continue
            if (segment == "..") {
                if (segments.isNotEmpty() && segments.last() != "..") {
                    segments.removeAt(segments.lastIndex)
                } else if (root.isEmpty()) {
                    segments.add("..")
                }
                continue
            }
            segments.add(segment)
        }
        return root + segments.joinToString(separator)
    }

    /**
     * Tool calls address the same file inconsistently across a session ("journal.txt", then
     * "/tmp/workspace/journal.txt"), so paths are canonicalised against the session cwd:
     * resolved, "." and ".." collapsed, compared case-insensitively for Windows cwds. Files
     * inside the cwd display workspace-relative, everything else as the resolved path; both
     * keep the first spelling seen.
     */
    internal fun canonicalizeDiffPath(path: String, cwd: String?): Pair<String, String> {
        val trimmed = path.jsTrim().removePrefix("./")
        val baseRaw = (cwd ?: "").jsTrim()
        if (baseRaw.isEmpty()) return trimmed to trimmed
        val separator = if (baseRaw.contains("\\") && !baseRaw.contains("/")) "\\" else "/"
        val base = collapsePathSegments(baseRaw, separator)
        if (base.isEmpty()) return trimmed to trimmed
        val resolved = collapsePathSegments(resolveAbsolutePath(trimmed, base), separator)
        val caseInsensitive = DRIVE_PATTERN.containsMatchIn(base) || base.startsWith("\\\\")
        fun comparable(value: String) = if (caseInsensitive) value.lowercase() else value
        val basePrefix = if (base.endsWith(separator)) base else base + separator
        val withinBase = resolved.length > basePrefix.length &&
            comparable(resolved).startsWith(comparable(basePrefix))
        return comparable(resolved) to (if (withinBase) resolved.substring(basePrefix.length) else resolved)
    }

    // endregion

    private const val ADD_FILE = "*** Add File: "
    private const val UPDATE_FILE = "*** Update File: "
    private const val DELETE_FILE = "*** Delete File: "
}

/** JS `String.prototype.trim` (whitespace and line terminators, including U+FEFF). */
internal fun String.jsTrim(): String = trim { it.isWhitespace() || it == '\uFEFF' }
