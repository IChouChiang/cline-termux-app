package dev.cline.termux.ui.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Row presentation rules of the model picker (SP S8.1 / S8.5) that the sheet reads from state. */
class ModelPickerRowTest {
    private fun row(id: String, label: String, supporting: String? = null, tier: String? = null, images: Boolean = false, reasoning: Boolean = false) =
        PickerRow(id, label, supporting, tier, images = images, reasoning = reasoning, section = null)

    @Test
    fun `id line shows only without a description and when the id differs from the label`() {
        assertTrue(row("openai/gpt-5.5", "GPT-5.5").showsIdLine)
        assertFalse(row("gpt-4o", "gpt-4o").showsIdLine)
        assertFalse(row("MiniMax-M2.7", "MiniMax-M2.7").showsIdLine)
        assertFalse(row("anthropic/claude-sonnet-5", "Claude Sonnet 5", supporting = "Best for coding").showsIdLine)
    }

    @Test
    fun `selected provider index follows catalog order`() {
        val providers = listOf(
            PickerProvider("cline", "Cline", selected = false),
            PickerProvider("openrouter", "OpenRouter", selected = false),
            PickerProvider("openai-compatible", "OpenAI Compatible", selected = true),
        )
        assertEquals(2, ModelPickerUiState(providers = providers).selectedProviderIndex)
        assertEquals(-1, ModelPickerUiState(providers = providers.map { it.copy(selected = false) }).selectedProviderIndex)
        assertEquals(-1, ModelPickerUiState().selectedProviderIndex)
    }

    @Test
    fun `a11y label folds fragments through the joiner in order`() {
        val join: (String, String) -> String = { a, b -> "$a、$b" }
        val full = row("anthropic/claude-sonnet-5", "Claude Sonnet 5", tier = "NEW", images = true, reasoning = true)
        assertEquals(
            "Claude Sonnet 5、Recommended、Images、Reasoning、NEW",
            modelRowA11yLabel(full, "Recommended", "Images", "Reasoning", join),
        )
        assertEquals("gpt-4o", modelRowA11yLabel(row("gpt-4o", "gpt-4o"), null, "Images", "Reasoning", join))
        assertEquals(
            "Grok 4, All, Images",
            modelRowA11yLabel(row("x-ai/grok-4", "Grok 4", images = true), "All", "Images", "Reasoning") { a, b -> "$a, $b" },
        )
    }
}
