package dev.cline.termux.core.kernel.termux

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The kernel's global settings that the app reads and writes (batch 1 seam S1, shared by the
 * auto-approve control and the compaction setting). Read with [ClineCommands.readGlobalSettings];
 * written with [KernelGlobalSettingsCommands.write], which merges keys and never clobbers a file
 * it cannot parse.
 */
data class KernelGlobalSettings(
    val toolAutoApprove: Boolean?,
    val compactionStrategy: String?,
    val compactionEnabled: Boolean?,
) {
    /** The terminal reads a missing key as on (apps/cli/src/main.ts). */
    val autoApproveAll: Boolean get() = toolAutoApprove ?: true

    /** [COMPACTION_OFF], [COMPACTION_AGENTIC] or [COMPACTION_BASIC] (apps/cli/src/utils/compaction-mode.ts). */
    val compactionMode: String
        get() = when {
            compactionEnabled == false -> COMPACTION_OFF
            compactionStrategy == COMPACTION_BASIC -> COMPACTION_BASIC
            else -> COMPACTION_AGENTIC
        }

    companion object {
        const val COMPACTION_OFF = "off"
        const val COMPACTION_AGENTIC = "agentic"
        const val COMPACTION_BASIC = "basic"

        val DEFAULT = KernelGlobalSettings(toolAutoApprove = null, compactionStrategy = null, compactionEnabled = null)

        /**
         * Read like the kernel's schema (`global-settings.ts`): an invalid strategy is agentic; a
         * boolean key holding anything but a JSON boolean (the string "false" too) is missing, as
         * `z.boolean().optional().catch(undefined)` reads it. Unparseable JSON or a non-object is
         * null (unknown), where the kernel falls back to its defaults (Auto-approve all on): the
         * app then disables its control rather than claim a mode.
         */
        fun parse(json: String): KernelGlobalSettings? {
            val root = runCatching { CommandJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
            val strategy = (root["compactionStrategy"] as? JsonPrimitive)?.contentOrNull
            return KernelGlobalSettings(
                toolAutoApprove = (root["toolAutoApprove"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
                compactionStrategy = strategy?.let { if (it == COMPACTION_BASIC) COMPACTION_BASIC else COMPACTION_AGENTIC },
                compactionEnabled = (root["compactionEnabled"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
            )
        }

        /** ok → parsed; missing file → [DEFAULT]; anything else (Termux unreachable, bad JSON) → null = unknown. */
        fun readOrNull(result: TermuxResult): KernelGlobalSettings? = when {
            result.ok -> parse(result.stdout)
            result.noSuchFile -> DEFAULT
            else -> null
        }
    }
}

/** The keys a write changes; null keys are left as they are. */
data class KernelGlobalSettingsPatch(
    val toolAutoApprove: Boolean? = null,
    val compactionStrategy: String? = null,
    val compactionEnabled: Boolean? = null,
) {
    fun toJson(): String = buildJsonObject {
        toolAutoApprove?.let { put("toolAutoApprove", it) }
        compactionStrategy?.let { put("compactionStrategy", it) }
        compactionEnabled?.let { put("compactionEnabled", it) }
    }.toString()
}

object KernelGlobalSettingsCommands {
    const val LABEL_WRITE = "write global-settings.json"
    const val EXIT_UNREADABLE = 3

    /**
     * Merges [patch] into global-settings.json with the device Bun: reads stdin, exits
     * [EXIT_UNREADABLE] instead of overwriting a file it cannot parse, writes a temp file and
     * renames it, prints the merged JSON. The script has no `$` and embeds the path.
     */
    fun write(patch: KernelGlobalSettingsPatch): TermuxCommand = TermuxCommand(
        path = "${TermuxPaths.BIN}/bun-ffi",
        args = listOf("-e", MERGE_SCRIPT),
        stdin = patch.toJson(),
        label = LABEL_WRITE,
        timeoutMs = TermuxTimeouts.DISCOVERY_MS,
    )

    private val MERGE_SCRIPT = """
        const fs=require("node:fs"),path=require("node:path");
        const p="${TermuxPaths.GLOBAL_SETTINGS_JSON}";
        const patch=JSON.parse(await Bun.stdin.text());
        let cur={};
        try{const t=fs.readFileSync(p,"utf8");cur=t.trim()?JSON.parse(t):{};}catch(e){if(e.code!=="ENOENT")process.exit(3)}
        if(typeof cur!=="object"||cur===null||Array.isArray(cur))process.exit(3);
        for(const k of ["toolAutoApprove","compactionStrategy","compactionEnabled"]){if(k in patch)cur[k]=patch[k]}
        fs.mkdirSync(path.dirname(p),{recursive:true});
        const tmp=p+".app-"+process.pid+".tmp";
        fs.writeFileSync(tmp,JSON.stringify(cur,null,2)+"\n");fs.renameSync(tmp,p);
        process.stdout.write(JSON.stringify(cur));
    """.trimIndent()
}
