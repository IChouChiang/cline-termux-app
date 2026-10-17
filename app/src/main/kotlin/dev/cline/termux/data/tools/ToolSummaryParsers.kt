package dev.cline.termux.data.tools

import dev.cline.termux.data.chat.MediaModality
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Kotlin port of sdk/packages/ui/components/agent-chat/tool-summary/{paths,diff,parsers}.ts.
 * Pure data-in/data-out; a malformed payload degrades, never throws.
 */

// ---------------------------------------------------------------- paths.ts

private val EXT_TO_LANGUAGE: Map<String, String> = mapOf(
    ".ts" to "typescript", ".tsx" to "tsx", ".js" to "javascript", ".jsx" to "jsx",
    ".py" to "python", ".rb" to "ruby", ".rs" to "rust", ".go" to "go", ".java" to "java",
    ".kt" to "kotlin", ".swift" to "swift", ".c" to "c", ".cpp" to "cpp", ".h" to "c",
    ".hpp" to "cpp", ".cs" to "csharp", ".css" to "css", ".scss" to "scss", ".html" to "html",
    ".vue" to "vue", ".svelte" to "svelte", ".json" to "json", ".yaml" to "yaml",
    ".yml" to "yaml", ".toml" to "toml", ".md" to "markdown", ".sql" to "sql", ".sh" to "bash",
    ".bash" to "bash", ".zsh" to "bash", ".fish" to "fish", ".lua" to "lua", ".php" to "php",
    ".r" to "r", ".ex" to "elixir", ".exs" to "elixir", ".erl" to "erlang", ".zig" to "zig",
    ".dockerfile" to "dockerfile", ".xml" to "xml", ".graphql" to "graphql",
    ".proto" to "protobuf",
)

private val SLASHES = Regex("[\\\\/]")

private fun splitSegments(filePath: String): MutableList<String> = filePath.split(SLASHES).toMutableList()

/** Final path segment ("basename"), tolerant of both slash styles. */
fun displayFileName(filePath: String): String =
    splitSegments(filePath).lastOrNull()?.takeIf { it.isNotEmpty() } ?: filePath

/** Shorten a long path to `.../parent/file` form, keeping as many trailing directories as fit. */
fun shortenPath(filePath: String, maxLen: Int = 50): String {
    if (filePath.length <= maxLen) return filePath
    val parts = splitSegments(filePath)
    val fileName = if (parts.isEmpty()) "" else parts.removeAt(parts.lastIndex)
    if (fileName.length >= maxLen - 4) return ".../$fileName"
    var result = fileName
    for (i in parts.indices.reversed()) {
        val candidate = "${parts[i]}/$result"
        if (candidate.length + 4 > maxLen) break
        result = candidate
    }
    return ".../$result"
}

/** Best-effort language id from a file extension or well-known file name. */
fun detectLanguage(filePath: String): String? {
    val base = displayFileName(filePath).lowercase()
    val dotIndex = base.lastIndexOf('.')
    val ext = if (dotIndex > 0) base.substring(dotIndex) else ""
    if (ext.isNotEmpty()) EXT_TO_LANGUAGE[ext]?.let { return it }
    return when (base) {
        "dockerfile" -> "dockerfile"
        "makefile" -> "makefile"
        "cmakelists.txt" -> "cmake"
        else -> null
    }
}

// ---------------------------------------------------------------- diff.ts

/** Honest hunk separator for diffs built from file fragments. */
const val FRAGMENT_HUNK_HEADER = "@@ … @@"

private data class DiffOp(val type: Char, val line: String)

private fun diffLines(oldLines: List<String>, newLines: List<String>): List<DiffOp> {
    val m = oldLines.size
    val n = newLines.size
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (oldLines[i - 1] == newLines[j - 1]) dp[i - 1][j - 1] + 1 else maxOf(dp[i - 1][j], dp[i][j - 1])
        }
    }
    val result = ArrayDeque<DiffOp>()
    var i = m
    var j = n
    while (i > 0 || j > 0) {
        if (i > 0 && j > 0 && oldLines[i - 1] == newLines[j - 1]) {
            result.addFirst(DiffOp(' ', oldLines[i - 1]))
            i--
            j--
        } else if (j > 0 && (i == 0 || dp[i][j - 1] >= dp[i - 1][j])) {
            result.addFirst(DiffOp('+', newLines[j - 1]))
            j--
        } else {
            result.addFirst(DiffOp('-', oldLines[i - 1]))
            i--
        }
    }
    return result
}

/**
 * `makeUnifiedDiff`. [fragment] marks texts that are fragments of a larger file: hunk
 * headers then render as `@@ … @@` instead of fabricated line positions.
 */
fun makeUnifiedDiff(oldText: String, newText: String, filePath: String, ctx: Int = 3, fragment: Boolean = false): String {
    // Empty text means zero lines, not one blank line.
    val oldArr = if (oldText.isEmpty()) emptyList() else oldText.split("\n")
    val newArr = if (newText.isEmpty()) emptyList() else newText.split("\n")
    val ops = diffLines(oldArr, newArr)
    val output = mutableListOf("--- a/$filePath", "+++ b/$filePath")
    var i = 0
    while (i < ops.size) {
        if (ops[i].type == ' ') {
            i++
            continue
        }
        val hunkStart = maxOf(0, i - ctx)
        var end = i
        while (end < ops.size) {
            if (ops[end].type != ' ') {
                end++
                continue
            }
            var next = end
            while (next < ops.size && ops[next].type == ' ') next++
            if (next < ops.size && next - end <= ctx * 2) end = next + 1 else break
        }
        val hunkEnd = minOf(ops.size, end + ctx)
        var oldCount = 0
        var newCount = 0
        for (k in hunkStart until hunkEnd) {
            if (ops[k].type != '+') oldCount++
            if (ops[k].type != '-') newCount++
        }
        var oldStart = 1
        var newStart = 1
        for (k in 0 until hunkStart) {
            if (ops[k].type != '+') oldStart++
            if (ops[k].type != '-') newStart++
        }
        output += if (fragment) FRAGMENT_HUNK_HEADER else "@@ -$oldStart,$oldCount +$newStart,$newCount @@"
        for (k in hunkStart until hunkEnd) output += "${ops[k].type}${ops[k].line}"
        i = hunkEnd
    }
    return output.joinToString("\n")
}

/** `hunkHeader`: a line-1-relative header for complete contents. */
fun hunkHeader(lines: List<String>): String {
    var oldCount = 0
    var newCount = 0
    for (l in lines) {
        if (!l.startsWith("+")) oldCount++
        if (!l.startsWith("-")) newCount++
    }
    return "@@ -1,$oldCount +1,$newCount @@"
}

/** Net line changes. */
data class DiffCounts(val additions: Int, val deletions: Int)

/** `countDiffLines`: added/removed lines, ignoring `---`/`+++` file headers and `@@` hunk headers. */
fun countDiffLines(diff: String): DiffCounts {
    var additions = 0
    var deletions = 0
    for (line in diff.split("\n")) {
        if (line.startsWith("+++") || line.startsWith("---")) continue
        if (line.startsWith("+")) additions++ else if (line.startsWith("-")) deletions++
    }
    return DiffCounts(additions, deletions)
}

// ---------------------------------------------------------------- parsers.ts

data class ReadFile(val path: String, val startLine: Int? = null, val endLine: Int? = null)

data class ReadFilesInfo(val files: List<ReadFile>)

private fun JsonElement?.jsInt(): Int? = jsNumber()?.takeIf { it == Math.rint(it) }?.toInt()

/** `parseReadFilesInput`: `{files:[{path,start_line,end_line}]}`, `{files:"p"}`, `{file_paths}`, `{paths}`, array, string. */
fun parseReadFilesInput(input: JsonElement?): ReadFilesInfo? {
    val normalized = normalizeJsonValue(input)
    val files = mutableListOf<ReadFile>()
    fun push(value: JsonElement?) {
        val text = value.jsString()
        if (text != null && text.isNotEmpty()) {
            files += ReadFile(text)
            return
        }
        val record = value.asRecord() ?: return
        val path = record["path"].nonEmptyString() ?: return
        files += ReadFile(path, record["start_line"].jsInt(), record["end_line"].jsInt())
    }
    val record = normalized.asRecord()
    val candidates = record?.let { it["files"].takeUnless(JsonElement?::isNullish) ?: it["file_paths"].takeUnless(JsonElement?::isNullish) ?: it["paths"].takeUnless(JsonElement?::isNullish) } ?: normalized
    if (candidates is JsonArray) candidates.forEach(::push) else push(candidates)
    return if (files.isNotEmpty()) ReadFilesInfo(files) else null
}

/** `buildReadFilesKeys`: the raw path is not unique, so the index is prefixed. */
fun buildReadFilesKeys(files: List<ReadFile>): List<String> = files.mapIndexed { i, f -> "$i:${f.path}" }

data class RunCommandsInfo(val commands: List<String>)

/** `parseRunCommandsInput`: every shape of core's RunCommandsInputUnionSchema. */
fun parseRunCommandsInput(input: JsonElement?): RunCommandsInfo? {
    val normalized = normalizeJsonValue(input)
    val commands = mutableListOf<String>()
    fun pushEntry(entry: JsonElement?) {
        val text = entry.jsString()
        if (text != null && text.isNotEmpty()) {
            commands += text
            return
        }
        val record = entry.asRecord() ?: return
        val command = record["command"].nonEmptyString() ?: return
        val argsArray = record["args"] as? JsonArray
        val args = argsArray?.mapNotNull { it.jsString() }?.joinToString(" ") ?: ""
        commands += if (args.isNotEmpty()) "$command $args" else command
    }
    when {
        normalized.jsString() != null -> pushEntry(normalized)
        normalized is JsonArray -> normalized.forEach(::pushEntry)
        normalized is JsonObject -> {
            val raw = normalized["commands"]
            when {
                raw != null -> if (raw is JsonArray) raw.forEach(::pushEntry) else pushEntry(raw)
                normalized["command"].jsString() != null -> pushEntry(normalized)
                normalized["cmd"].jsString() != null -> pushEntry(normalized["cmd"])
            }
        }
    }
    return if (commands.isNotEmpty()) RunCommandsInfo(commands) else null
}

data class EditorInfo(val path: String, val oldText: String?, val newText: String, val insertLine: Int?)

fun parseEditorInput(input: JsonElement?): EditorInfo? {
    val normalized = normalizeJsonValue(input).asRecord() ?: return null
    val path = normalized["path"].jsString() ?: return null
    val newText = normalized["new_text"].jsString() ?: return null
    return EditorInfo(path, normalized["old_text"].jsString(), newText, normalized["insert_line"].jsInt())
}

data class SearchInfo(val queries: List<String>)

fun parseSearchInput(input: JsonElement?): SearchInfo? {
    val normalized = normalizeJsonValue(input).asRecord() ?: return null
    val queries = (normalized["queries"] as? JsonArray ?: return null).mapNotNull { it.nonEmptyString() }
    return if (queries.isNotEmpty()) SearchInfo(queries) else null
}

data class WebFetchInfo(val urls: List<String>)

fun parseWebFetchInput(input: JsonElement?): WebFetchInfo? {
    val normalized = normalizeJsonValue(input).asRecord() ?: return null
    val requests = normalized["requests"] as? JsonArray ?: return null
    val urls = requests.mapNotNull { it.asRecord() }.map { it["url"].jsString() ?: "" }.filter { it.isNotEmpty() }
    return if (urls.isNotEmpty()) WebFetchInfo(urls) else null
}

data class SpawnAgentInfo(val task: String)

fun parseSpawnAgentInput(input: JsonElement?): SpawnAgentInfo? {
    val normalized = normalizeJsonValue(input).asRecord() ?: return null
    return normalized["task"].jsString()?.let(::SpawnAgentInfo)
}

data class AskQuestionInfo(val question: String, val options: List<String>)

fun parseAskQuestionInput(input: JsonElement?): AskQuestionInfo? {
    val normalized = normalizeJsonValue(input).asRecord() ?: return null
    val question = normalized["question"].jsString() ?: return null
    val options = (normalized["options"] as? JsonArray)?.mapNotNull { it.jsString() } ?: emptyList()
    return AskQuestionInfo(question, options)
}

/**
 * Before/after texts of one diff hunk. [oldText] is null for a created file (the desktop
 * passes `oldText: undefined` to the rich renderer); apply_patch hunks always carry both.
 */
data class DiffHunk(val oldText: String?, val newText: String)

enum class PatchAction { Add, Update, Delete }

data class ApplyPatchFile(
    val path: String,
    val action: PatchAction,
    /** Destination of a `*** Move to:` line. */
    val movedTo: String?,
    val additions: Int,
    val deletions: Int,
    val diff: String,
    /** Per-hunk before/after texts, boundaries preserved (never re-diff concatenated hunks). */
    val hunks: List<DiffHunk>,
)

data class ApplyPatchInfo(val files: List<ApplyPatchFile>, val additions: Int, val deletions: Int, val diff: String)

private val FILE_ACTION_RE = Regex("^\\*\\*\\* (Add|Update|Delete) File: (.+)")
private val MOVE_TO_RE = Regex("^\\*\\*\\* Move to: (.+)")
private val HUNK_MARKER_RE = Regex("^@@")
private val SKIP_LINES = Regex("^(?:\\*\\*\\* (?:Begin|End) Patch|%%bash|```|EOF|apply_patch\\b|\\*\\*\\* End of File)")

/** `parseApplyPatchInput`: the `*** Add/Update/Delete File:` envelope into per-file counts and a unified diff. */
fun parseApplyPatchInput(input: JsonElement?): ApplyPatchInfo? {
    val normalized = normalizeJsonValue(input)
    val raw = normalized.jsString() ?: normalized.asRecord()?.get("input").jsString()
    if (raw.isNullOrEmpty()) return null

    class Current(val path: String, val action: PatchAction) {
        var movedTo: String? = null
        val lines = mutableListOf("--- a/$path", "+++ b/$path")
        var hunk = mutableListOf<String>()
        var oldLines = mutableListOf<String>()
        var newLines = mutableListOf<String>()
        val hunks = mutableListOf<DiffHunk>()
        var additions = 0
        var deletions = 0
    }

    val files = mutableListOf<ApplyPatchFile>()
    var current: Current? = null

    fun flushHunk() {
        val c = current ?: return
        if (c.hunk.isEmpty()) return
        // Update/Delete sections are file fragments; only Add File carries real positions.
        c.lines += if (c.action == PatchAction.Add) hunkHeader(c.hunk) else FRAGMENT_HUNK_HEADER
        c.lines += c.hunk
        c.hunks += DiffHunk(c.oldLines.joinToString("\n"), c.newLines.joinToString("\n"))
        c.hunk = mutableListOf()
        c.oldLines = mutableListOf()
        c.newLines = mutableListOf()
    }

    fun flushFile() {
        val c = current ?: return
        flushHunk()
        files += ApplyPatchFile(c.path, c.action, c.movedTo, c.additions, c.deletions, c.lines.joinToString("\n"), c.hunks.toList())
        current = null
    }

    for (line in raw.split("\n")) {
        val fileMatch = FILE_ACTION_RE.find(line)
        if (fileMatch != null) {
            flushFile()
            val action = when (fileMatch.groupValues[1]) {
                "Add" -> PatchAction.Add
                "Delete" -> PatchAction.Delete
                else -> PatchAction.Update
            }
            current = Current(fileMatch.groupValues[2].trim(), action)
            continue
        }
        val moveMatch = MOVE_TO_RE.find(line)
        if (moveMatch != null) {
            current?.movedTo = moveMatch.groupValues[1].trim()
            continue
        }
        val trimmed = line.trim()
        if (HUNK_MARKER_RE.containsMatchIn(trimmed)) {
            flushHunk()
            continue
        }
        if (SKIP_LINES.containsMatchIn(trimmed)) continue
        val c = current ?: continue
        when {
            line.startsWith("+") -> {
                c.additions++
                c.newLines += line.substring(1)
            }
            line.startsWith("-") -> {
                c.deletions++
                c.oldLines += line.substring(1)
            }
            else -> {
                val context = if (line.startsWith(" ")) line.substring(1) else line
                c.oldLines += context
                c.newLines += context
            }
        }
        c.hunk += if (line.startsWith("+") || line.startsWith("-") || line.startsWith(" ")) line else " $line"
    }
    flushFile()

    if (files.isEmpty()) return null
    return ApplyPatchInfo(
        files = files,
        additions = files.sumOf { it.additions },
        deletions = files.sumOf { it.deletions },
        diff = files.joinToString("\n") { it.diff },
    )
}

private val EDITOR_ADDED = Regex("^\\+\\d+:")
private val EDITOR_REMOVED = Regex("^-\\d+:")

/** The editor result encodes changed lines as `+12: …` / `-12: …`; the fallback when input texts are missing. */
fun parseEditorResultDiffCounts(result: JsonElement?): DiffCounts? {
    val normalized = normalizeJsonValue(result)
    val value = (normalized.asRecord()?.get("result") ?: normalized).jsString() ?: return null
    var additions = 0
    var deletions = 0
    for (line in value.split("\n")) {
        if (EDITOR_ADDED.containsMatchIn(line)) additions += 1
        if (EDITOR_REMOVED.containsMatchIn(line)) deletions += 1
    }
    if (additions == 0 && deletions == 0) return null
    return DiffCounts(additions, deletions)
}

/** `ToolOutputMedia`: validated inline media from a tool result. */
data class ToolOutputMedia(
    val modality: MediaModality,
    val mediaType: String,
    val data: String,
    val name: String? = null,
)

private fun contentBlockMediaType(part: JsonObject): String {
    for (key in listOf("mimeType", "mediaType", "mime_type")) {
        val value = part[key].jsString()
        if (value != null && value.trim().isNotEmpty()) return value.trim()
    }
    return ""
}

private fun chunkBase64(data: String): String = data.chunked(76).joinToString("\n").ifEmpty { data }

private fun contentBlockText(part: JsonObject): String? {
    val type = part["type"].jsString()
    if (type == "text") part["text"].jsString()?.let { return it }
    val resource = part["resource"].asRecord()
    if (type == "resource" && resource != null) {
        resource["text"].jsString()?.let { return it }
        val blob = resource["blob"].jsString()
        if (!blob.isNullOrEmpty()) {
            val label = resource["uri"].jsString()?.let { "[resource: $it]" } ?: "[resource]"
            return "$label\n${chunkBase64(blob)}"
        }
        resource["uri"].jsString()?.let { return "[resource: $it]" }
        return "[resource]"
    }
    if (type == "resource_link") part["uri"].jsString()?.let { return "[resource_link: $it]" }
    if (type == "image" || type == "audio") {
        val mediaType = contentBlockMediaType(part)
        return if (mediaType.isNotEmpty()) "[$type: $mediaType]" else "[$type]"
    }
    val media = part["media"].asRecord()
    if (type == "media" && media != null) {
        val modality = media["modality"].jsString() ?: "media"
        val mediaType = media["mediaType"].jsString() ?: ""
        return if (mediaType.isNotEmpty()) "[$modality: $mediaType]" else "[$modality]"
    }
    return type?.let { "[$it]" }
}

private fun extractOutputArrayText(raw: JsonArray): String? {
    val parts = mutableListOf<String>()
    for (item in raw) {
        val text = item.jsString()
        if (text != null) {
            if (text.isNotEmpty()) parts += text
            continue
        }
        val record = item.asRecord() ?: continue
        if ("result" in record) {
            extractOutputText(record["result"])?.takeIf { it.isNotEmpty() }?.let { parts += it }
            continue
        }
        val blockText = contentBlockText(record)
        if (!blockText.isNullOrEmpty()) {
            parts += blockText
            continue
        }
        val content = record["content"]
        if (content is JsonArray) extractOutputText(content)?.takeIf { it.isNotEmpty() }?.let { parts += it }
    }
    return if (parts.isNotEmpty()) parts.joinToString("\n") else null
}

private val SUPPORTED_IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
private const val MAX_IMAGE_ENCODED_BYTES = 5 * 1024 * 1024
private const val MAX_IMAGE_DECODED_BYTES = 6 * 1024 * 1024

/** Result of [validateImageMedia]: the effective media type and the bare base64 payload. */
data class ValidImage(val mediaType: String, val base64: String)

private fun isBase64Char(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/'

private fun isCanonicalBase64(base64: String): Boolean {
    if (base64.length % 4 != 0) return false
    val padding = when {
        base64.length >= 2 && base64.endsWith("==") -> 2
        base64.endsWith("=") -> 1
        else -> 0
    }
    val body = base64.length - padding
    for (i in 0 until body) if (!isBase64Char(base64[i])) return false
    return true
}

/**
 * Port of `validateImageMedia` (sdk/packages/shared/src/llms/media.ts) with the default
 * limits: supported type (or a matching `data:` URL), canonical base64, 5 MiB encoded and
 * 6 MiB decoded.
 */
fun validateImageMedia(mediaType: String?, data: String): ValidImage? {
    var effective = mediaType?.lowercase()
    var payload = data.trim()
    if (payload.length >= 5 && payload.substring(0, 5).equals("data:", ignoreCase = true)) {
        val comma = payload.indexOf(',', 5)
        if (comma != -1 && comma - 5 <= 128) {
            val metadata = payload.substring(5, comma).lowercase()
            if (metadata.endsWith(";base64")) {
                val urlType = metadata.removeSuffix(";base64")
                if (urlType.isNotEmpty() && ';' !in urlType) {
                    if (effective != null && effective != urlType) return null
                    effective = urlType
                    payload = payload.substring(comma + 1)
                }
            }
        }
    }
    if (effective == null || effective !in SUPPORTED_IMAGE_MEDIA_TYPES) return null
    if (payload.isEmpty() || payload.length > MAX_IMAGE_ENCODED_BYTES) return null
    if (!isCanonicalBase64(payload)) return null
    val padding = if (payload.endsWith("==")) 2 else if (payload.endsWith("=")) 1 else 0
    val decoded = payload.length * 3 / 4 - padding
    if (decoded > MAX_IMAGE_DECODED_BYTES) return null
    return ValidImage(effective, payload)
}

private fun inlineContentBlockMedia(part: JsonObject): ToolOutputMedia? {
    val type = part["type"].jsString()
    if (type != "image" && type != "audio") return null
    val mediaType = contentBlockMediaType(part)
    val data = part["data"].jsString() ?: part["image"].jsString() ?: ""
    if (data.isEmpty()) return null
    if (type == "image") {
        val valid = validateImageMedia(mediaType.ifEmpty { null }, data) ?: return null
        return ToolOutputMedia(MediaModality.Image, valid.mediaType, valid.base64)
    }
    if (mediaType.isEmpty()) return null
    return ToolOutputMedia(MediaModality.Audio, mediaType, data)
}

private fun canonicalInlineMedia(part: JsonObject): ToolOutputMedia? {
    if (part["type"].jsString() != "media") return null
    val media = part["media"].asRecord() ?: return null
    val modality = when (media["modality"].jsString()) {
        "image" -> MediaModality.Image
        "audio" -> MediaModality.Audio
        "video" -> MediaModality.Video
        "file" -> MediaModality.File
        else -> return null
    }
    val mediaType = media["mediaType"].jsString() ?: return null
    val source = media["source"].asRecord() ?: return null
    if (source["type"].jsString() != "base64") return null
    val data = source["data"].jsString() ?: return null
    val output = ToolOutputMedia(modality, mediaType, data, media["name"].jsString())
    if (modality != MediaModality.Image) return output
    val valid = validateImageMedia(mediaType, data) ?: return null
    return output.copy(mediaType = valid.mediaType, data = valid.base64)
}

private fun collectOutputMedia(raw: JsonElement?, output: MutableList<ToolOutputMedia>) {
    val normalized = normalizeJsonValue(raw)
    if (normalized is JsonArray) {
        normalized.forEach { collectOutputMedia(it, output) }
        return
    }
    val record = normalized.asRecord() ?: return
    val direct = inlineContentBlockMedia(record) ?: canonicalInlineMedia(record)
    if (direct != null) {
        output += direct
        return
    }
    if ("result" in record) collectOutputMedia(record["result"], output)
    val content = record["content"]
    if (content is JsonArray) collectOutputMedia(content, output)
}

/** Inline media blocks from direct, wrapped or batched tool output, deduplicated. */
fun extractOutputMedia(raw: JsonElement?): List<ToolOutputMedia> {
    val output = mutableListOf<ToolOutputMedia>()
    collectOutputMedia(raw, output)
    return output.distinctBy { Triple(it.modality, it.mediaType, it.data) }
}

/**
 * Human-readable text of a tool result: strings, `[{result}]` arrays, content-block arrays
 * and MCP `{content:[...]}`; binary payloads become compact placeholders.
 */
fun extractOutputText(raw: JsonElement?): String? {
    if (raw.isNullish) return null
    raw.jsString()?.let { return it }
    if (raw is JsonArray) extractOutputArrayText(raw)?.takeIf { it.isNotEmpty() }?.let { return it }
    if (raw is JsonObject || raw is JsonArray) {
        val content = (raw as? JsonObject)?.get("content")
        if (content is JsonArray) extractOutputArrayText(content)?.takeIf { it.isNotEmpty() }?.let { return it }
        if (raw is JsonObject && "result" in raw) extractOutputText(raw["result"])?.takeIf { it.isNotEmpty() }?.let { return it }
        return jsonStringify(raw!!, "  ")
    }
    return jsonStringify(raw!!, "")
}
