package dev.cline.termux.core.kernel.termux

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The terminal's prompt history (decision A11.6, brief F5), shared with `cline --tui`: the file
 * `~/.cline/data/cache/user_input_history.jsonl` holds one `{"prompt","ts"}` object per line,
 * **oldest first**, at most [MAX_ENTRIES] (`apps/cli/src/utils/input-history.ts`). The app reads
 * it with `cat` and rewrites it with the device Bun exactly as the terminal's
 * `appendInputHistory` does, so both clients see the same list. Labels are the only part
 * ever logged; prompts are user content and never are.
 */
object InputHistoryCommands {
    /** The terminal's `MAX_HISTORY`. */
    const val MAX_ENTRIES = 20

    const val LABEL_READ = "cat user_input_history.jsonl"
    const val LABEL_APPEND = "append user_input_history.jsonl"

    /** The append script's exit code for a file it could not read (anything but ENOENT): the file is left as it is. */
    const val EXIT_UNREADABLE = 3

    private const val HISTORY_PATH = "${TermuxPaths.CLINE_DATA}/cache/user_input_history.jsonl"

    /** `cat <history file>`; reduce with [readOrNull]. */
    fun read(): TermuxCommand = TermuxCommand.bin("cat", HISTORY_PATH, label = LABEL_READ, timeoutMs = TermuxTimeouts.SHORT_MS)

    /**
     * The file's prompts, **newest first** (`loadInputHistory`): one JSON object per line, a
     * line kept only when its `prompt` is a non-blank string (kept as written, untrimmed),
     * malformed and blank lines skipped, then the last [MAX_ENTRIES] reversed. Strict JSON like
     * the terminal's `JSON.parse` (the lenient command parser would keep lines the terminal skips).
     */
    fun parse(text: String): List<String> {
        val entries = ArrayList<String>()
        for (line in text.split('\n')) {
            if (line.isBlank()) continue
            val obj = runCatching { Json.parseToJsonElement(line) }.getOrNull() as? JsonObject ?: continue
            val prompt = obj["prompt"] as? JsonPrimitive ?: continue
            if (prompt.isString && prompt.content.isNotBlank()) entries += prompt.content
        }
        return entries.takeLast(MAX_ENTRIES).reversed()
    }

    /** ok → [parse]; no file yet → empty; anything else (Termux unreachable, unreadable) → null = unknown. */
    fun readOrNull(result: TermuxResult): List<String>? = when {
        result.ok -> parse(result.stdout)
        result.noSuchFile -> emptyList()
        else -> null
    }

    /**
     * `bun-ffi -e <script>` with [prompt] on stdin, never in the arguments: the terminal's
     * `appendInputHistory` (trim, drop an empty prompt, remove earlier copies, put it first, keep
     * [MAX_ENTRIES], write oldest first with `ts = Date.now()` on every line), written to a temp
     * file and renamed. A file it cannot read for any reason but ENOENT exits [EXIT_UNREADABLE]
     * and stays untouched (the terminal would overwrite it with this one prompt).
     */
    fun append(prompt: String): TermuxCommand = TermuxCommand(
        path = "${TermuxPaths.BIN}/bun-ffi",
        args = listOf("-e", APPEND_SCRIPT),
        stdin = prompt,
        label = LABEL_APPEND,
        timeoutMs = TermuxTimeouts.DISCOVERY_MS,
    )

    /**
     * The in-memory mirror of the terminal's `prependInputHistoryEntry`, applied to a
     * newest-first list: the trimmed prompt first, its earlier copies removed, at most
     * [MAX_ENTRIES]. A blank prompt leaves the list as it is.
     */
    fun prepend(history: List<String>, prompt: String): List<String> {
        val trimmed = prompt.trim()
        if (trimmed.isEmpty()) return history
        return (listOf(trimmed) + history.filter { it != trimmed }).take(MAX_ENTRIES)
    }

    // The only `$` below are Kotlin templates (path, exit code, cap); the generated script has none.
    private val APPEND_SCRIPT = """
        const fs=require("node:fs"),path=require("node:path");
        const p="$HISTORY_PATH";
        const prompt=(await Bun.stdin.text()).trim();
        if(!prompt){process.exit(0)}
        let entries=[];
        try{for(const line of fs.readFileSync(p,"utf8").split("\n")){if(!line.trim())continue;try{const v=JSON.parse(line);if(typeof v.prompt==="string"&&v.prompt.trim())entries.push(v.prompt)}catch{}}}catch(e){if(e.code!=="ENOENT")process.exit($EXIT_UNREADABLE)}
        const recent=[prompt,...entries.reverse().filter(x=>x!==prompt)].slice(0,$MAX_ENTRIES).reverse();
        fs.mkdirSync(path.dirname(p),{recursive:true});
        const tmp=p+".app-"+process.pid+".tmp";
        fs.writeFileSync(tmp,recent.map(x=>JSON.stringify({prompt:x,ts:Date.now()})).join("\n")+"\n");
        fs.renameSync(tmp,p);
    """.trimIndent()
}
