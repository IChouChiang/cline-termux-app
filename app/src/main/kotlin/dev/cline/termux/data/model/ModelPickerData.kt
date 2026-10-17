package dev.cline.termux.data.model

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.tools.jsLocaleCompare

/*
 * Kotlin port of apps/examples/desktop-app/webview/lib/featured-models.ts
 * (`buildModelPickerData`, tests from featured-models.test.ts) and the pure parts of
 * lib/model-selection.ts (selection and reasoning storage shapes, `REASONING_SELECTION_VALUES`),
 * plus the picker rules of design/screens/settings-providers.md S8.2 that sit on top of it
 * (Current model section, search matching, capability badges).
 */

/** `ProviderModelFeaturedTier`. */
enum class FeaturedTier { Recommended, Free, Subscribed;

    companion object {
        fun parse(value: String?): FeaturedTier? = when (value) {
            "recommended" -> Recommended
            "free" -> Free
            "subscribed" -> Subscribed
            else -> null
        }
    }
}

/** `ProviderModelFeatured`: the recommended-models feed stamp. */
@Immutable
data class FeaturedInfo(val tier: FeaturedTier, val rank: Int = 0, val tags: List<String> = emptyList())

/** The fields of a `ProviderModel` the picker reads. */
@Immutable
data class PickerModel(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val featured: FeaturedInfo? = null,
    val inputModalities: List<String>? = null,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
)

/** `SearchComboboxOption`. */
@Immutable
data class PickerOption(
    val label: String,
    val value: String,
    val section: String? = null,
    val description: String? = null,
    /** The recommended tier's first tag ("NEW"). */
    val badge: String? = null,
)

/** `SearchComboboxSection`. */
@Immutable
data class PickerSection(val id: String, val label: String, val description: String? = null)

/** `ModelPickerData`; [sections] null means a flat list. */
@Immutable
data class ModelPickerData(val options: List<PickerOption>, val sections: List<PickerSection>? = null)

/** Section copy mirrors the CLI's featured picker. */
const val FREE_SECTION_DESCRIPTION = "Try with limited usage at no cost"
const val CLINE_PASS_FREE_SECTION_DESCRIPTION = "Try with limited usage, separate from ClinePass quota"

/** Section id of a selected model outside the offer (S8.2). */
const val CURRENT_MODEL_SECTION_ID = "current"

private fun displayName(model: PickerModel): String = model.name?.trim()?.ifEmpty { null } ?: model.id

private val byLabel = Comparator<PickerOption> { a, b -> jsLocaleCompare(a.label, b.label) }

private fun flatOptions(models: List<PickerModel>): List<PickerOption> {
    val labelCounts = models.groupingBy(::displayName).eachCount()
    return models.map { model ->
        val label = displayName(model)
        PickerOption(label = if ((labelCounts[label] ?: 0) > 1) "$label (${model.id})" else label, value = model.id)
    }.sortedWith(byLabel)
}

private fun tierOptions(
    models: List<PickerModel>,
    tier: FeaturedTier,
    section: String,
    badge: ((PickerModel) -> String?)? = null,
): List<PickerOption> = models
    .filter { it.featured?.tier == tier }
    .sortedBy { it.featured?.rank ?: 0 }
    .map { model ->
        PickerOption(
            badge = badge?.invoke(model),
            description = model.description?.trim()?.ifEmpty { null },
            label = displayName(model),
            section = section,
            value = model.id,
        )
    }

/**
 * `buildModelPickerData`: `cline` gets Recommended / Free / All models; `cline-pass` gets
 * Subscribed / Free only (offline: split by the `cline-pass/` prefix); every other provider
 * a flat list ordered by display name with colliding names suffixed " (<id>)".
 */
fun buildModelPickerData(providerId: String, models: List<PickerModel>): ModelPickerData {
    if (providerId == "cline") {
        val recommended = tierOptions(models, FeaturedTier.Recommended, "recommended") { it.featured?.tags?.firstOrNull() }
        val free = tierOptions(models, FeaturedTier.Free, "free")
        if (recommended.isEmpty() && free.isEmpty()) return ModelPickerData(flatOptions(models))
        val rest = models.filter { it.featured == null }
            .map { PickerOption(label = displayName(it), section = "all", value = it.id) }
            .sortedWith(byLabel)
        return ModelPickerData(
            options = recommended + free + rest,
            sections = listOf(
                PickerSection("recommended", "Recommended"),
                PickerSection("free", "Free", FREE_SECTION_DESCRIPTION),
                PickerSection("all", "All models"),
            ),
        )
    }
    if (providerId == "cline-pass") {
        val subscribed = tierOptions(models, FeaturedTier.Subscribed, "subscribed").toMutableList()
        val free = tierOptions(models, FeaturedTier.Free, "free").toMutableList()
        if (subscribed.isEmpty()) {
            val fallback = models.filter { it.featured == null }
            subscribed += flatOptions(fallback.filter { it.id.startsWith("cline-pass/") }).map { it.copy(section = "subscribed") }
            free += flatOptions(fallback.filterNot { it.id.startsWith("cline-pass/") }).map { it.copy(section = "free") }
        }
        return ModelPickerData(
            options = subscribed + free,
            sections = listOf(
                PickerSection("subscribed", "Subscribed"),
                PickerSection("free", "Free", CLINE_PASS_FREE_SECTION_DESCRIPTION),
            ),
        )
    }
    return ModelPickerData(flatOptions(models))
}

/**
 * S8.2 / chat-input-bar.tsx `visibleModelPicker`: a selected model that the offer does not list
 * is appended so the selected value always exists. A sectioned offer gains a "Current model"
 * section for it; a flat list gets it as an ordinary row with no header. [selectedLabel] is the
 * provider's model details name (`detail?.name?.trim() || resolvedModel`).
 */
fun ModelPickerData.withCurrentModel(selectedModelId: String?, selectedLabel: String? = null): ModelPickerData {
    val id = selectedModelId?.trim().orEmpty()
    if (id.isEmpty() || options.any { it.value == id }) return this
    val label = selectedLabel?.trim()?.ifEmpty { null } ?: id
    if (sections.isNullOrEmpty()) return ModelPickerData(options + PickerOption(label = label, value = id), sections)
    return ModelPickerData(
        options = options + PickerOption(label = label, value = id, section = CURRENT_MODEL_SECTION_ID),
        sections = sections + PickerSection(CURRENT_MODEL_SECTION_ID, "Current model"),
    )
}

/** SearchCombobox matching: `"<label> <description> <id>"`, case-insensitive substring. */
fun PickerOption.matches(query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    return "$label ${description.orEmpty()} $value".lowercase().contains(needle)
}

/** S8.2 free-text id row: non-blank, no spaces, matching no option. */
fun ModelPickerData.freeTextModelId(query: String): String? {
    val text = query.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() }) return null
    return text.takeIf { options.none { option -> option.matches(text) } }
}

/** Capability badges after the tier badge (DS #7). */
enum class CapabilityBadge { Images, Reasoning }

fun PickerModel.capabilityBadges(): List<CapabilityBadge> = buildList {
    val images = inputModalities?.contains("image") ?: supportsVision
    if (images) add(CapabilityBadge.Images)
    if (supportsReasoning) add(CapabilityBadge.Reasoning)
}

// ---------------------------------------------------------------- model-selection.ts
//
// The desktop's localStorage records live in DataStore `model.selection` / `model.reasoning`
// (docs/07 §7.2); [ModelSelectionStore] reads and writes them.

/** `REASONING_SELECTION_VALUES` (None / Low / Medium / High / Extra). */
enum class ReasoningEffort(val wire: String) {
    None("none"), Low("low"), Medium("medium"), High("high"), XHigh("xhigh");

    companion object {
        /** Desktop default effort. */
        val DEFAULT = Low

        fun parse(value: String?): ReasoningEffort? = entries.firstOrNull { it.wire == value }
    }
}
