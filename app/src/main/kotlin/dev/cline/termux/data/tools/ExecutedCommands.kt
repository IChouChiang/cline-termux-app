package dev.cline.termux.data.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The commands a `run_commands` call executes, in the kernel's order (trial row 18). A
 * result record and a `tool.updated` chunk carry `query`, the kernel's echo of their command,
 * which core caps at 200 characters and ends with "... [command truncated: N more chars; full
 * command is in the tool call input]" (`formatRunCommandQueryPreview`). The app prints the
 * command from the call's input instead: entry `i` here is the command of result record `i`
 * and of `commandIndex == i`.
 *
 * Kotlin port of sdk/packages/core/src/extensions/tools/helpers.ts (`normalizeRunCommandsInput`
 * over `RunCommandsInputUnionSchema`, `formatRunCommandQuery`, `formatRunCommandQueryPreview`),
 * definitions.ts (`coalesceAdjacentStringHeredocs`) and the runtime's input normalization
 * (agents agent-runtime.ts `prepareToolExecution`). Unlike the desktop summary parser
 * ([parseRunCommandsInput], which feeds the row's details) it keeps empty commands and joins
 * a heredoc split over several entries, because the kernel does, so the indexes line up.
 */

/** The kernel's shell tool. */
const val RUN_COMMANDS_TOOL = "run_commands"

/** core `RUN_COMMAND_QUERY_PREVIEW_LIMIT`: the characters of a command the echo keeps. */
const val RUN_COMMAND_QUERY_PREVIEW_LIMIT = 200

/** The note core appends to a capped echo; the group is the number of characters cut. */
private val QUERY_PREVIEW_NOTE =
    Regex(""" \.\.\. \[command truncated: (\d+) more chars; full command is in the tool call input]\z""")

/**
 * The commands [input] runs, rendered as the kernel renders them (`formatRunCommandQuery`), or
 * null when there is no input or it fails every shape of `RunCommandsInputUnionSchema` (the
 * kernel then runs nothing).
 */
fun runCommandsExecuted(input: JsonElement?): List<String>? {
    val entries = normalizeRunCommandsInput(normalizeJsonLikeInput(input ?: return null)) ?: return null
    return coalesceAdjacentStringHeredocs(entries).map(::formatRunCommandQuery)
}

/**
 * The command to print for result record or execution [index]: entry [index] of [commands]
 * ([runCommandsExecuted]) when it is the command the kernel's [echo] stands for; otherwise (no
 * input, or an input shape this port does not know) the echo without its note
 * ([withoutQueryPreviewNote]). The check means a row never prints a neighbour's command, and a
 * command short enough for the kernel to echo whole prints exactly as before.
 */
fun executedCommand(commands: List<String>?, index: Int, echo: String?): String {
    val fromInput = commands?.getOrNull(index)
    if (fromInput != null && (echo.isNullOrEmpty() || echoMatches(fromInput, echo))) return fromInput
    return echo?.let(::withoutQueryPreviewNote).orEmpty()
}

/**
 * A run_commands [result] whose records carry [executedCommand] as their `query`, for whatever
 * prints a result as it is: the `$ <query>` lines of [extractRunCommandOutput] and the desktop's
 * JSON fallback for a call that printed nothing (`extractOutputText`), which would otherwise
 * show the kernel's capped echo and its note. [result] itself (the same instance) when no
 * record changes, so any other value passes untouched.
 */
fun withExecutedCommands(result: JsonElement?, input: JsonElement?): JsonElement? {
    val normalized = normalizeJsonValue(result)
    val records = when (normalized) {
        is JsonArray -> normalized
        is JsonObject -> JsonArray(listOf(normalized))
        else -> return result
    }
    if (records.none { it.asRecord()?.get("query").jsString() != null }) return result
    val commands = runCommandsExecuted(input)
    var changed = false
    val presented = records.mapIndexed { index, element ->
        val record = element.asRecord() ?: return@mapIndexed element
        val echo = record["query"].jsString() ?: return@mapIndexed element
        val command = executedCommand(commands, index, echo)
        if (command == echo) {
            element
        } else {
            changed = true
            JsonObject(record + ("query" to JsonPrimitive(command)))
        }
    }
    if (!changed) return result
    return if (normalized is JsonArray) JsonArray(presented) else presented.single()
}

/** The kernel's echo with core's truncation note replaced by "…", so the cut stays visible. */
fun withoutQueryPreviewNote(echo: String): String {
    val note = QUERY_PREVIEW_NOTE.find(echo) ?: return echo
    return echo.substring(0, note.range.first).trimEnd() + "…"
}

/** [echo] is what `formatRunCommandQueryPreview([command])` returns. */
internal fun echoMatches(command: String, echo: String): Boolean {
    if (command == echo) return true
    val note = QUERY_PREVIEW_NOTE.find(echo) ?: return false
    val prefix = echo.substring(0, note.range.first)
    val cut = note.groupValues[1].toIntOrNull() ?: return false
    return prefix.length == RUN_COMMAND_QUERY_PREVIEW_LIMIT && command.length == prefix.length + cut && command.startsWith(prefix)
}

// ---------------------------------------------------------------- agents agent-runtime.ts

/**
 * `normalizeJsonLikeStringsForSchema(input, tool.inputSchema)` for the run_commands schema
 * (`{commands: string[]}`), which the runtime applies before the tool runs (shared
 * parse/json.ts): an object sent as a JSON string, and `commands` sent as a JSON array
 * string, are parsed. `tool.started` already carries the result; a history row keeps the
 * input as the model sent it, so it is applied here as well (a no-op on a parsed input).
 */
private fun normalizeJsonLikeInput(input: JsonElement): JsonElement {
    val record = parsedJsonLike(input, '{') as? JsonObject ?: return input
    val commands = record["commands"] ?: return record
    val parsed = parsedJsonLike(commands, '[')
    return if (parsed === commands) record else JsonObject(record + ("commands" to parsed))
}

/** `parseJsonStringForSchema` for a schema that accepts only the kind [opener] starts. */
private fun parsedJsonLike(value: JsonElement, opener: Char): JsonElement {
    val trimmed = value.jsString()?.replace(JS_TRIM, "") ?: return value
    if (!trimmed.startsWith(opener)) return value
    val parsed = parseJsonOrNull(trimmed) ?: return value
    val accepted = if (opener == '{') parsed is JsonObject else parsed is JsonArray
    return if (accepted) parsed else value
}

// ---------------------------------------------------------------- core helpers.ts

/** One entry of the normalized input: a shell string, or a structured `{command, args?}` call. */
private sealed interface CommandEntry {
    data class Shell(val text: String) : CommandEntry

    /** [args] null when the input had no `args` key. */
    data class Structured(val command: String, val args: List<String>?) : CommandEntry
}

/** `StructuredCommandInputSchema`: `command` a non-empty string, `args` absent or an array of strings. */
private fun structuredEntry(element: JsonElement?): CommandEntry.Structured? {
    val record = element as? JsonObject ?: return null
    val command = record["command"].jsString()?.takeIf { it.isNotEmpty() } ?: return null
    if (!record.containsKey("args")) return CommandEntry.Structured(command, null)
    val args = record["args"] as? JsonArray ?: return null
    return CommandEntry.Structured(command, args.map { it.jsString() ?: return null })
}

/** `StructuredCommandEntrySchema`: a shell string or a structured call. */
private fun commandEntry(element: JsonElement?): CommandEntry? =
    element.jsString()?.let(CommandEntry::Shell) ?: structuredEntry(element)

private fun <T> JsonArray.allOrNull(entry: (JsonElement) -> T?): List<T>? = map { entry(it) ?: return null }

/**
 * `normalizeRunCommandsInput`: the first member of `RunCommandsInputUnionSchema` that accepts
 * [input] (zod tries them in order and strips unknown keys), then the function's own unwrapping.
 */
private fun normalizeRunCommandsInput(input: JsonElement): List<CommandEntry>? {
    input.jsString()?.let { return listOf(CommandEntry.Shell(it)) }
    if (input is JsonArray) {
        // z.array(StructuredCommandInputSchema), then z.array(z.string()).
        return input.allOrNull(::structuredEntry) ?: input.allOrNull { element -> element.jsString()?.let(CommandEntry::Shell) }
    }
    val record = input as? JsonObject ?: return null
    val commands = record["commands"]
    if (commands is JsonArray) {
        // RunCommandsInputSchema (strings), then StructuredCommandsInputSchema (strings or calls).
        commands.allOrNull(::commandEntry)?.let { return it }
    } else if (commands != null) {
        commandEntry(commands)?.let { return listOf(it) }
    }
    structuredEntry(record)?.let { call -> return listOf(if (call.args != null) call else CommandEntry.Shell(call.command)) }
    record["command"].jsString()?.let { return listOf(CommandEntry.Shell(it)) }
    record["cmd"].jsString()?.let { return listOf(CommandEntry.Shell(it)) }
    return null
}

/** `/[\s"]/u`: an argument `formatRunCommandQuery` quotes. */
private val ARG_NEEDS_QUOTES = Regex("$JS_WHITESPACE_CLASS|\"")

/** `formatRunCommandQuery`: a shell string as is; a call as `command arg…`, quoting arguments with spaces or quotes. */
private fun formatRunCommandQuery(entry: CommandEntry): String = when (entry) {
    is CommandEntry.Shell -> entry.text
    is CommandEntry.Structured -> {
        val args = entry.args.orEmpty()
        if (args.isEmpty()) {
            entry.command
        } else {
            entry.command + " " + args.joinToString(" ") { arg ->
                if (ARG_NEEDS_QUOTES.containsMatchIn(arg)) jsonStringify(JsonPrimitive(arg), "") else arg
            }
        }
    }
}

// ---------------------------------------------------------------- core definitions.ts

/** `getHeredocDelimiter`'s pattern: `<<DELIM`, `<<-DELIM`, `<<'DELIM'`, `<<"DELIM"`, never `<<<`. */
private val HEREDOC_DELIMITER =
    Regex("""(?<![<])<<-?$JS_WHITESPACE_CLASS*(?:"([^"]+)"|'([^']+)'|([A-Za-z0-9_./-]+))""")

private val JS_TRIM = Regex("^$JS_WHITESPACE_CLASS+|$JS_WHITESPACE_CLASS+$")

private fun heredocDelimiter(command: String): String? {
    val groups = HEREDOC_DELIMITER.find(command)?.groups ?: return null
    return groups[1]?.value ?: groups[2]?.value ?: groups[3]?.value
}

/** `coalesceSplitHeredocCommands`: a heredoc opener and the entries up to its delimiter line become one command. */
private fun coalesceSplitHeredocCommands(commands: List<String>): List<String> {
    val coalesced = mutableListOf<String>()
    var index = 0
    while (index < commands.size) {
        val command = commands[index]
        val delimiter = heredocDelimiter(command)
        val endIndex = if (delimiter == null) {
            -1
        } else {
            commands.indices.firstOrNull { next -> next > index && commands[next].replace(JS_TRIM, "") == delimiter } ?: -1
        }
        if (endIndex == -1) {
            coalesced += command
        } else {
            coalesced += commands.subList(index, endIndex + 1).joinToString("\n")
            index = endIndex
        }
        index++
    }
    return coalesced
}

/** `coalesceAdjacentStringHeredocs`: only runs of shell strings coalesce; a structured call ends a run. */
private fun coalesceAdjacentStringHeredocs(entries: List<CommandEntry>): List<CommandEntry> {
    val coalesced = mutableListOf<CommandEntry>()
    val run = mutableListOf<String>()
    fun flush() {
        if (run.isEmpty()) return
        coalesceSplitHeredocCommands(run).mapTo(coalesced, CommandEntry::Shell)
        run.clear()
    }
    for (entry in entries) {
        if (entry is CommandEntry.Shell) {
            run += entry.text
        } else {
            flush()
            coalesced += entry
        }
    }
    flush()
    return coalesced
}
