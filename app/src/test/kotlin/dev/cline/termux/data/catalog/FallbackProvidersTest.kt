package dev.cline.termux.data.catalog

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SP §0.4: the generated bundle and the desktop's fallback model tables; A2's reasoning rule. */
class FallbackProvidersTest {

    private val bundleText: String by lazy {
        listOf("src/main/res/raw/fallback_providers.json", "app/src/main/res/raw/fallback_providers.json")
            .map(::File).first { it.isFile }.readText()
    }

    private val bundle: FallbackProviders by lazy { FallbackProviders.parse(bundleText) }

    @Test fun `the bundle lists every provider id the CLI accepts`() {
        val required = listOf(
            "anthropic", "openai-native", "openrouter", "gemini", "xai", "groq", "mistral", "deepseek", "ollama",
            "openai-compatible", "cline", "cline-pass", "openai-codex", "oca", "cerebras", "fireworks", "together", "wandb",
        )
        for (id in required) assertNotNull("missing $id", bundle[id])
        assertEquals("anthropic/claude-sonnet-5", bundle.clineDefaultModelId)
    }

    @Test fun `only OpenAI and OpenAI-compatible accept a base URL`() {
        assertEquals(setOf("openai-native", "openai-compatible"), bundle.providers.filter { it.supportsBaseUrl }.map { it.id }.toSet())
    }

    @Test fun `auth kinds follow getProviderAuthKind`() {
        for (id in listOf("cline", "cline-pass", "openai-codex", "oca")) assertEquals(FallbackProvider.AUTH_KIND_OAUTH, bundle.authKind(id))
        assertEquals(FallbackProvider.AUTH_KIND_API_KEY, bundle.authKind("anthropic"))
        assertTrue(bundle.providers.any { it.authKind == FallbackProvider.AUTH_KIND_LOCAL })
        // Aliases resolve like the desktop's normalizeProviderId.
        assertEquals("openai-native", bundle["openai"]?.id)
        assertNull(bundle["no-such-provider"])
    }

    @Test fun `popular providers lead in catalog order`() {
        val firstNotPopular = bundle.providers.indexOfFirst { !it.popular }
        assertTrue(bundle.providers.drop(firstNotPopular).none { it.popular })
        assertTrue(bundle["cline"]!!.popular)
    }

    @Test fun `fallback models add the saved model after the table`() {
        assertEquals(listOf("anthropic/claude-sonnet-5"), fallbackModelIds("cline"))
        assertEquals(listOf("gpt-5.5", "gpt-6"), fallbackModelIds("openai-native", "gpt-6"))
        assertEquals(listOf("gpt-5.5"), fallbackModelIds("openai", "gpt-5.5"))
        assertEquals(listOf("llama"), fallbackModelIds("ollama", " llama "))
        assertEquals(emptyList<String>(), fallbackModelIds("xai", null))
    }

    @Test fun `the bundled JSON is schema 2 with a sorted capabilities array on every row`() {
        val root = Json.parseToJsonElement(bundleText).jsonObject
        assertEquals(2, root.getValue("schema").jsonPrimitive.int)
        val rows = root.getValue("providers").jsonArray
        assertTrue(rows.isNotEmpty())
        assertEquals(rows.size, bundle.providers.size)
        for (row in rows) {
            val obj = row.jsonObject
            val id = obj.getValue("id").jsonPrimitive.content
            val capabilities = obj["capabilities"]
            assertTrue("$id has no capabilities array", capabilities is JsonArray)
            val values = capabilities!!.jsonArray.map { (it as JsonPrimitive).content }
            assertEquals("$id capabilities sorted and distinct", values.distinct().sorted(), values)
            // The parsed row carries the same array.
            assertEquals(id, values, bundle[id]!!.capabilities)
        }
    }

    @Test fun `the registry's reasoning capability reaches the bundle`() {
        for (id in listOf("openai-codex", "deepseek", "anthropic", "cline", "openai-native", "openrouter", "gemini")) {
            assertTrue("$id should carry reasoning", bundle[id]!!.supportsReasoning)
        }
        assertFalse(bundle["openai-compatible"]!!.supportsReasoning)
        assertFalse(FallbackProvider.CAPABILITY_REASONING in bundle["openai-compatible"]!!.capabilities)
    }

    @Test fun `a schema-1 row parses with empty capabilities`() {
        val legacy = FallbackProviders.parse(
            """{"schema":1,"clineDefaultModelId":"anthropic/claude-sonnet-5","providers":[
                {"id":"anthropic","name":"Anthropic","popular":true,"authKind":"api-key","supportsBaseUrl":false,"defaultModelId":"claude-sonnet-5"},
                {"id":"deepseek","name":"DeepSeek","popular":true,"authKind":"api-key","supportsBaseUrl":false,"defaultModelId":"deepseek-v4-flash"}]}""",
        )
        val anthropic = legacy["anthropic"]!!
        assertEquals(emptyList<String>(), anthropic.capabilities)
        assertFalse(anthropic.supportsReasoning)
        // Without capabilities the rule is the desktop's tables alone.
        assertEquals(true, legacy.reasoningSupport("anthropic", "claude-sonnet-4-6"))
        assertNull(legacy.reasoningSupport("anthropic", "claude-opus-9"))
        assertNull(legacy.reasoningSupport("deepseek", "deepseek-chat"))
    }

    @Test fun `openai-codex reasons for any model`() {
        assertEquals(true, bundle.reasoningSupport("openai-codex", "gpt-5.6-terra"))
        assertEquals(true, bundle.reasoningSupport("openai-codex", "a-model-the-registry-does-not-know"))
    }

    @Test fun `deepseek-chat reasons through the provider capability`() {
        assertEquals(true, bundle.reasoningSupport("deepseek", "deepseek-chat"))
        assertEquals(true, bundle.reasoningSupport("deepseek", "deepseek-v4-flash"))
    }

    @Test fun `a listed anthropic model reasons, and so does an unlisted one`() {
        assertEquals(true, bundle.reasoningSupport("anthropic", "claude-sonnet-4-6"))
        assertEquals(true, bundle.reasoningSupport("anthropic", "claude-opus-9"))
    }

    @Test fun `openai-compatible models stay unknown, so the mock shows no thinking level`() {
        assertNull(bundle.reasoningSupport("openai-compatible", "mock-model"))
    }

    @Test fun `an unknown provider stays unknown`() {
        assertNull(bundle.reasoningSupport("my-proxy", "llama"))
        assertNull(FallbackProviders.EMPTY.reasoningSupport("xai", "grok"))
    }

    @Test fun `the reasoning rule checks the table, then the provider, then the plain table`() {
        // Step 1: the reasoning table wins even for a provider without the capability.
        val bare = FallbackProviders(listOf(FallbackProvider("gemini"), FallbackProvider("xai", capabilities = listOf("tools"))))
        assertEquals(true, bare.reasoningSupport("gemini", "gemini-3-pro-latest"))
        // Aliases resolve like the desktop's normalizeProviderId.
        assertEquals(true, bare.reasoningSupport("google", "gemini-3-pro-latest"))
        assertEquals(true, bundle.reasoningSupport("openai", "some-gpt"))
        // Step 2: the provider's capability covers every other model.
        val reasoning = FallbackProviders(listOf(FallbackProvider("xai", capabilities = listOf("reasoning"))))
        assertEquals(true, reasoning.reasoningSupport("xai", "grok"))
        // Step 4: neither table nor capability → unknown.
        assertNull(bare.reasoningSupport("gemini", "gemini-2"))
        assertNull(bare.reasoningSupport("xai", "grok"))
        // Step 3 (a model only in FALLBACK_PROVIDER_MODELS → false) needs the two desktop tables
        // to diverge; today they list the same models, as on the desktop.
        assertEquals(FALLBACK_PROVIDER_MODELS.keys, FALLBACK_PROVIDER_REASONING_MODELS.keys)
    }
}
