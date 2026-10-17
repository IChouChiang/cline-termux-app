package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.termux.KernelGlobalSettings
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsPatch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `sessionConfig.compaction` of every `session.create` (decisions G1 and A11 row 2): the CLI's
 * `resolveStartupCompactionMode` (`apps/cli/src/utils/startup-settings.ts`) followed by
 * `buildCliCompactionConfig` (`apps/cli/src/utils/compaction-mode.ts`), without the command-line
 * flag the app does not have.
 *
 * - `compactionEnabled == false` → `{enabled:false}`;
 * - a strategy set (`agentic` / `basic`) → `{enabled:true, strategy}`;
 * - nothing set, or the settings unknown (Termux unreachable) → `{enabled:true}`, core's default
 *   strategy (agentic). Core skips compaction unless `enabled === true`, so G1 never sends less.
 */
fun compactionConfig(settings: KernelGlobalSettings?): JsonObject = buildJsonObject {
    val strategy = settings?.compactionStrategy
    when {
        settings?.compactionEnabled == false -> put("enabled", false)
        strategy != null -> {
            put("enabled", true)
            put("strategy", strategy)
        }
        else -> put("enabled", true)
    }
}

/** The three choices of Settings → Context compaction, in the terminal's order and labels (LLM, Truncation, Off). */
enum class CompactionMode(val wire: String) {
    Llm(KernelGlobalSettings.COMPACTION_AGENTIC),
    Truncation(KernelGlobalSettings.COMPACTION_BASIC),
    Off(KernelGlobalSettings.COMPACTION_OFF),
    ;

    /**
     * The global-settings write for this choice (core `setCompactionModeGlobally`): Off sets only
     * `compactionEnabled:false` and keeps the stored strategy, so turning compaction back on
     * restores it; LLM and Truncation set both keys.
     */
    fun patch(): KernelGlobalSettingsPatch = when (this) {
        Off -> KernelGlobalSettingsPatch(compactionEnabled = false)
        Llm, Truncation -> KernelGlobalSettingsPatch(compactionStrategy = wire, compactionEnabled = true)
    }

    companion object {
        /** [KernelGlobalSettings.compactionMode] (`off` / `agentic` / `basic`) → the choice; unknown reads as LLM. */
        fun of(settings: KernelGlobalSettings): CompactionMode = when (settings.compactionMode) {
            KernelGlobalSettings.COMPACTION_OFF -> Off
            KernelGlobalSettings.COMPACTION_BASIC -> Truncation
            else -> Llm
        }
    }
}
