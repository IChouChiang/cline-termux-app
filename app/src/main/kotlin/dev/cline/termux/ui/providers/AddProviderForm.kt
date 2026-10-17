package dev.cline.termux.ui.providers

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.providers.AddProviderRequest
import kotlinx.serialization.Serializable

/*
 * The add-provider form (settings-providers.md S6), ported from the desktop's
 * add-provider.tsx (`NewProviderForm`, `canSave`, `updateModels`, `handleSave`) and
 * model-id-input.tsx (`addPendingModel`, Backspace on empty). The API key is deliberately
 * not part of this type: it lives only in `remember` state in the form composable and is
 * passed to the save call (docs/07 §3.7), so the rest of the form can go into
 * `SavedStateHandle` (as JSON) and survive process death.
 */

/** `CAPABILITY_OPTIONS`. */
enum class ProviderCapability(val wire: String) {
    Streaming("streaming"),
    Tools("tools"),
    Reasoning("reasoning"),
    Vision("vision"),
    PromptCache("prompt-cache"),
}

/** One "Custom Headers" row. */
@Immutable
@Serializable
data class HeaderRow(val key: String = "", val value: String = "")

@Immutable
@Serializable
data class AddProviderForm(
    val providerId: String = "",
    val name: String = "",
    val baseUrl: String = "",
    val modelsSourceUrl: String = "",
    val models: List<String> = emptyList(),
    /** The chip field's pending text (not yet a chip). */
    val modelInput: String = "",
    val defaultModel: String = "",
    val capabilities: List<String> = DEFAULT_CAPABILITIES,
    val timeoutMs: String = "",
    val headers: List<HeaderRow> = emptyList(),
    val advancedOpen: Boolean = false,
) {

    /** `normalizedProviderId`: trimmed, lowercase, whitespace runs → "-". */
    val normalizedProviderId: String get() = normalizeProviderId(providerId)

    /** Any field changed from its initial value (the discard guard; the API key is checked by the caller). */
    val dirty: Boolean
        get() = copy(advancedOpen = false) != EMPTY

    fun duplicateOf(existingIds: Collection<String>): Boolean = normalizedProviderId in existingIds

    /** `canSave` without the duplicate check. */
    val complete: Boolean
        get() = normalizedProviderId.isNotEmpty() && name.isNotBlank() && baseUrl.isNotBlank() &&
            (models.isNotEmpty() || modelsSourceUrl.isNotBlank())

    fun canSave(existingIds: Collection<String>): Boolean = complete && !duplicateOf(existingIds)

    /** `updateModels`: keeps `defaultModel` valid (first model when the previous default was removed). */
    fun withModels(next: List<String>): AddProviderForm = copy(
        models = next,
        defaultModel = if (defaultModel !in next) next.firstOrNull().orEmpty() else defaultModel.ifEmpty { next.firstOrNull().orEmpty() },
    )

    /** `addPendingModel`: the trimmed input without commas becomes a chip unless empty or already present. */
    fun commitModelInput(): AddProviderForm {
        val value = modelInput.trim().replace(",", "")
        val next = if (value.isNotEmpty() && value !in models) withModels(models + value) else this
        return next.copy(modelInput = "")
    }

    /** Typing in the chip field: a "," commits the text before it (the desktop's "," key). */
    fun typeModelInput(text: String): AddProviderForm =
        if (text.contains(',')) {
            val parts = text.split(',')
            var form = this
            for (part in parts.dropLast(1)) form = form.copy(modelInput = part).commitModelInput()
            form.copy(modelInput = parts.last())
        } else {
            copy(modelInput = text)
        }

    /** Backspace on an empty input removes the last chip. */
    fun removeLastModel(): AddProviderForm = if (modelInput.isEmpty() && models.isNotEmpty()) withModels(models.dropLast(1)) else this

    fun removeModel(model: String): AddProviderForm = withModels(models - model)

    fun toggleCapability(capability: ProviderCapability): AddProviderForm = copy(
        capabilities = if (capability.wire in capabilities) capabilities - capability.wire else capabilities + capability.wire,
    )

    /** `addHeader` (an empty row). */
    fun addHeader(): AddProviderForm = copy(headers = headers + HeaderRow())

    fun updateHeader(index: Int, row: HeaderRow): AddProviderForm =
        copy(headers = headers.mapIndexed { i, existing -> if (i == index) row else existing })

    fun removeHeader(index: Int): AddProviderForm = copy(headers = headers.filterIndexed { i, _ -> i != index })

    /**
     * `handleSave`'s payload: trimmed values, headers with trimmed non-empty keys (a later
     * duplicate key wins, as in the desktop's object), `timeout_ms` = `parseInt`, default model
     * = the chosen one or the first, capabilities omitted when empty. [apiKey] comes from the
     * composable's memory and is sent only when non-blank.
     */
    fun toRequest(apiKey: String): AddProviderRequest = AddProviderRequest(
        providerId = normalizedProviderId,
        name = name.trim(),
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim().ifEmpty { null },
        headers = LinkedHashMap<String, String>().apply {
            for (row in headers) {
                val key = row.key.trim()
                if (key.isNotEmpty()) put(key, row.value)
            }
        },
        timeoutMs = parseIntPrefix(timeoutMs),
        models = models,
        defaultModelId = defaultModel.ifEmpty { models.firstOrNull() },
        modelsSourceUrl = modelsSourceUrl.trim().ifEmpty { null },
        capabilities = capabilities.takeIf { it.isNotEmpty() },
    )

    companion object {
        val DEFAULT_CAPABILITIES: List<String> = listOf(ProviderCapability.Streaming.wire, ProviderCapability.Tools.wire)
        val EMPTY = AddProviderForm()

        fun normalizeProviderId(raw: String): String = raw.trim().lowercase().replace(Regex("\\s+"), "-")

        /** `Number.parseInt(text.trim(), 10)`: leading digits (optional sign), null when there are none. */
        fun parseIntPrefix(text: String): Long? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val match = Regex("^[+-]?\\d+").find(trimmed) ?: return null
            return match.value.toLongOrNull()
        }
    }
}
