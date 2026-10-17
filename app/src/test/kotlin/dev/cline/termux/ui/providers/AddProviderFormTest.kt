package dev.cline.termux.ui.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S6 form rules, ported from add-provider.tsx and model-id-input.tsx. */
class AddProviderFormTest {

    private val complete = AddProviderForm(providerId = " My Provider ", name = "Mine", baseUrl = "https://x/v1", models = listOf("m1"))

    @Test fun `provider ids are trimmed, lowercased and dashed`() {
        assertEquals("my-provider", complete.normalizedProviderId)
        assertEquals("a-b-c", AddProviderForm.normalizeProviderId("A  b\tC"))
    }

    @Test fun `can save needs id, name, base url and a model or a source url`() {
        assertTrue(complete.canSave(emptyList()))
        assertFalse(complete.copy(name = " ").canSave(emptyList()))
        assertFalse(complete.copy(baseUrl = "").canSave(emptyList()))
        assertFalse(complete.copy(models = emptyList()).canSave(emptyList()))
        assertTrue(complete.copy(models = emptyList(), modelsSourceUrl = "https://x/models").canSave(emptyList()))
    }

    @Test fun `a duplicate id cannot be saved`() {
        assertTrue(complete.duplicateOf(listOf("my-provider")))
        assertFalse(complete.canSave(listOf("my-provider")))
    }

    @Test fun `committing the input adds a trimmed chip once and picks the first default`() {
        val form = AddProviderForm(modelInput = " gpt-4o ").commitModelInput()
        assertEquals(listOf("gpt-4o"), form.models)
        assertEquals("gpt-4o", form.defaultModel)
        assertEquals("", form.modelInput)
        assertEquals(listOf("gpt-4o"), form.copy(modelInput = "gpt-4o").commitModelInput().models)
        assertEquals(form, form.commitModelInput())
    }

    @Test fun `typing a comma commits the text before it`() {
        val form = AddProviderForm().typeModelInput("a,b, c,d")
        assertEquals(listOf("a", "b", "c"), form.models)
        assertEquals("d", form.modelInput)
    }

    @Test fun `removing the default model moves the default to the first one`() {
        val form = AddProviderForm(models = listOf("a", "b"), defaultModel = "b").removeModel("b")
        assertEquals(listOf("a"), form.models)
        assertEquals("a", form.defaultModel)
        assertEquals("", form.removeLastModel().defaultModel)
    }

    @Test fun `backspace on an empty input removes the last chip only then`() {
        val form = AddProviderForm(models = listOf("a", "b"), defaultModel = "a")
        assertEquals(listOf("a"), form.removeLastModel().models)
        assertEquals(listOf("a", "b"), form.copy(modelInput = "x").removeLastModel().models)
    }

    @Test fun `capabilities toggle, defaulting to streaming and tools`() {
        val form = AddProviderForm()
        assertEquals(listOf("streaming", "tools"), form.capabilities)
        assertEquals(listOf("tools"), form.toggleCapability(ProviderCapability.Streaming).capabilities)
        assertTrue("prompt-cache" in form.toggleCapability(ProviderCapability.PromptCache).capabilities)
    }

    @Test fun `dirty ignores the advanced disclosure`() {
        assertFalse(AddProviderForm().dirty)
        assertFalse(AddProviderForm(advancedOpen = true).dirty)
        assertTrue(AddProviderForm(name = "x").dirty)
        assertTrue(AddProviderForm().addHeader().dirty)
    }

    @Test fun `parseInt keeps leading digits like the desktop`() {
        assertEquals(30000L, AddProviderForm.parseIntPrefix(" 30000ms"))
        assertEquals(-5L, AddProviderForm.parseIntPrefix("-5"))
        assertNull(AddProviderForm.parseIntPrefix("abc"))
        assertNull(AddProviderForm.parseIntPrefix(""))
    }

    @Test fun `the request trims values, drops blank header keys and omits a blank key`() {
        val form = complete.copy(
            name = " Mine ",
            timeoutMs = "1500",
            headers = listOf(HeaderRow(" X-A ", "1"), HeaderRow(" ", "lost"), HeaderRow("X-A", "2")),
            capabilities = emptyList(),
        )
        val request = form.toRequest(apiKey = "  ")
        assertEquals("my-provider", request.providerId)
        assertEquals("Mine", request.name)
        assertNull(request.apiKey)
        assertEquals(mapOf("X-A" to "2"), request.headers)
        assertEquals(1500L, request.timeoutMs)
        assertEquals("m1", request.defaultModelId)
        assertNull(request.modelsSourceUrl)
        assertNull(request.capabilities)
        assertEquals("sk", form.toRequest(" sk ").apiKey)
    }

    @Test fun `the saved form round-trips through JSON and never holds a key`() {
        val form = complete.copy(headers = listOf(HeaderRow("k", "v")), advancedOpen = true)
        val json = kotlinx.serialization.json.Json.encodeToString(AddProviderForm.serializer(), form)
        assertEquals(form, ProvidersViewModel.decodeForm(json))
        assertEquals(AddProviderForm.EMPTY, ProvidersViewModel.decodeForm(""))
        assertEquals(AddProviderForm.EMPTY, ProvidersViewModel.decodeForm("{not json"))
        assertFalse(json.contains("apiKey"))
    }
}
