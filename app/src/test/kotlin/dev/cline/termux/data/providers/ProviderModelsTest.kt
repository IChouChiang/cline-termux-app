package dev.cline.termux.data.providers

import dev.cline.termux.data.model.FeaturedTier
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ports of lib/provider-connection.test.ts and lib/provider-model-catalog.test.ts, plus the S4/S5 rules. */
class ProviderModelsTest {

    private fun provider(
        id: String = "test",
        enabled: Boolean = false,
        configured: Boolean? = null,
        apiKey: String? = null,
        oauth: Boolean? = null,
        capabilities: List<String>? = null,
    ) = ProviderListItem(id = id, name = "Test", models = 0, enabled = enabled, configured = configured, apiKey = apiKey, oauthAccessTokenPresent = oauth, capabilities = capabilities)

    // ---- isProviderConnected (provider-connection.test.ts) -------------------------------

    @Test fun `counts a provider with an API key`() = assertTrue(isProviderConnected(provider(apiKey = "sk-123")))

    @Test fun `counts a provider with an OAuth access token`() = assertTrue(isProviderConnected(provider(oauth = true)))

    @Test fun `ignores whitespace-only API keys on disabled providers`() = assertFalse(isProviderConnected(provider(apiKey = "   ")))

    @Test fun `never counts a disabled provider without credentials`() =
        assertFalse(isProviderConnected(provider(enabled = false, configured = true)))

    @Test fun `counts an enabled provider the sidecar reports as configured`() =
        assertTrue(isProviderConnected(provider(id = "ollama", enabled = true, configured = true)))

    @Test fun `rejects an enabled provider without sidecar-verified credentials`() {
        assertFalse(isProviderConnected(provider(id = "qwen-code", enabled = true, configured = false)))
        assertFalse(isProviderConnected(provider(id = "sapaicore", enabled = true)))
    }

    @Test fun `a redacted copy keeps the connected state but not the key`() {
        val original = provider(apiKey = "sk-123")
        val redacted = original.redacted()
        assertNull(redacted.apiKey)
        assertTrue(redacted.apiKeyPresent)
        assertTrue(isProviderConnected(redacted))
        assertFalse(redacted.toString().contains("sk-123"))
        assertFalse(original.toString().contains("sk-123"))
    }

    @Test fun `redaction drops secret config values and keeps plain ones`() {
        val item = ProviderListItem(
            id = "bedrock",
            configFields = listOf(
                ConfigField(path = "aws.secretKey", label = "Secret", type = "password"),
                ConfigField(path = "aws.region", label = "Region", type = "text"),
                ConfigField(path = "token", label = "Token", type = "text", secret = true),
            ),
            configValues = mapOf("aws.secretKey" to JsonPrimitive("s3cr3t"), "aws.region" to JsonPrimitive("us-east-1"), "token" to JsonPrimitive("t")),
        )
        assertEquals(mapOf("aws.region" to JsonPrimitive("us-east-1")), item.redacted().configValues)
    }

    // ---- getProviderAuthKind --------------------------------------------------------------

    @Test fun `auth kind follows capabilities and the OAuth id set`() {
        assertEquals(AuthKind.OAuth, providerAuthKind("cline", null))
        assertEquals(AuthKind.OAuth, providerAuthKind("cline-pass", emptyList()))
        assertEquals(AuthKind.OAuth, providerAuthKind("custom", listOf("oauth")))
        assertEquals(AuthKind.Local, providerAuthKind("claude-code", listOf("local-auth")))
        assertEquals(AuthKind.ApiKey, providerAuthKind("anthropic", listOf("popular")))
        // oauth wins over local-auth, as in the desktop's order.
        assertEquals(AuthKind.OAuth, providerAuthKind("x", listOf("local-auth", "oauth")))
    }

    // ---- isChatModel (provider-model-catalog.test.ts) ---------------------------------------

    @Test fun `keeps chat and image-generation models in the composer`() {
        assertTrue(isChatModel(ProviderModel("chat", "Chat", inputModalities = listOf("text", "audio"), outputModalities = listOf("text"))))
        assertTrue(isChatModel(ProviderModel("legacy", "Legacy")))
        assertTrue(isChatModel(ProviderModel("image", "Image", operation = "image-generation", inputModalities = listOf("text", "image"), outputModalities = listOf("image"))))
        assertFalse(isChatModel(ProviderModel("whisper", "Whisper", operation = "transcription", inputModalities = listOf("audio"), outputModalities = listOf("text"))))
        assertFalse(isChatModel(ProviderModel("operation-only-whisper", "Operation-only Whisper", operation = "transcription", inputModalities = listOf("audio"), outputModalities = listOf("text"))))
        assertFalse(isChatModel(ProviderModel("tts", "TTS", operation = "speech-generation", inputModalities = listOf("text"), outputModalities = listOf("audio"))))
    }

    @Test fun `keeps transcription-only models out of chat while retaining chat audio models`() {
        val models = listOf(
            ProviderModel("gpt-4o-mini-transcribe", "GPT-4o mini Transcribe", operation = "transcription", inputModalities = listOf("audio"), outputModalities = listOf("text")),
            ProviderModel("gpt-audio", "GPT Audio", inputModalities = listOf("text", "audio"), outputModalities = listOf("text", "audio")),
            ProviderModel("gpt-text", "GPT Text", inputModalities = listOf("text"), outputModalities = listOf("text")),
        )
        assertEquals(listOf("gpt-audio", "gpt-text"), filterChatModels(models).map { it.id })
    }

    @Test fun `distinguishes speech-to-text from text-to-speech and chat audio`() {
        assertTrue(isTranscriptionModel(ProviderModel("whisper", operation = "transcription", inputModalities = listOf("audio"), outputModalities = listOf("text"))))
        assertFalse(isTranscriptionModel(ProviderModel("elevenlabs", inputModalities = listOf("text"), outputModalities = listOf("audio"))))
        assertFalse(isTranscriptionModel(ProviderModel("omni", inputModalities = listOf("text", "audio"), outputModalities = listOf("text"))))
    }

    // ---- S5.4 badges ------------------------------------------------------------------------

    @Test fun `featured badges lead with the tier and skip duplicate tags`() {
        assertEquals(emptyList<String>(), featuredBadges(ProviderModel("plain")))
        assertEquals(
            listOf("Recommended", "NEW"),
            featuredBadges(ProviderModel("a", featured = ProviderModelFeatured("recommended", 1, listOf("NEW", "recommended")))),
        )
        assertEquals(listOf("Free"), featuredBadges(ProviderModel("b", featured = ProviderModelFeatured("free", 0, listOf("FREE")))))
        assertEquals(listOf("BEST"), featuredBadges(ProviderModel("c", featured = ProviderModelFeatured("subscribed", 0, listOf("BEST")))))
    }

    @Test fun `audio badges follow audio-model-badges`() {
        assertEquals(AudioBadges(AudioBadge.Realtime, false), audioBadges(ProviderModel("rt", operation = "realtime")))
        assertEquals(AudioBadges(AudioBadge.Realtime, false), audioBadges(ProviderModel("s", operation = "transcription", operationModes = listOf("batch", "streaming"))))
        assertEquals(AudioBadges(AudioBadge.Transcription, true), audioBadges(ProviderModel("t", operation = "transcription", operationModes = listOf("batch"))))
        assertEquals(AudioBadges(null, false), audioBadges(ProviderModel("chat")))
        assertTrue(hasAudioSupport(ProviderModel("a", inputModalities = listOf("text", "audio"), outputModalities = listOf("text"))))
        assertFalse(hasAudioSupport(ProviderModel("w", operation = "transcription", inputModalities = listOf("audio"), outputModalities = listOf("text"))))
    }

    @Test fun `favourites come first in saved order, then catalog order`() {
        val models = listOf("a", "b", "c", "d").map { ProviderModel(it) }
        assertEquals(listOf("c", "a", "b", "d"), sortModelsByFavorites(models, listOf("c", "a")).map { it.id })
        assertEquals(listOf("a", "b", "c", "d"), sortModelsByFavorites(models, emptyList()).map { it.id })
    }

    @Test fun `model search matches name or id case-insensitively`() {
        val model = ProviderModel("anthropic/claude-sonnet-5", "Claude Sonnet 5")
        assertTrue(model.matchesQuery("SONNET"))
        assertTrue(model.matchesQuery("anthropic/"))
        assertFalse(model.matchesQuery("gpt"))
    }

    @Test fun `picker model carries the featured tier`() {
        val picker = ProviderModel("m", "M", featured = ProviderModelFeatured("free", 2, listOf("NEW")), supportsReasoning = true).toPickerModel()
        assertEquals(FeaturedTier.Free, picker.featured?.tier)
        assertEquals(2, picker.featured?.rank)
        assertTrue(picker.supportsReasoning)
    }

    @Test fun `images side output prefers modalities over supportsVision`() {
        assertEquals(true, ProviderModel("a", inputModalities = listOf("text", "image"), supportsVision = false).supportsImages)
        assertEquals(false, ProviderModel("b", inputModalities = listOf("text"), supportsVision = true).supportsImages)
        assertEquals(true, ProviderModel("c", supportsVision = true).supportsImages)
        assertNull(ProviderModel("d").supportsImages)
    }

    // ---- S4.3 sections -----------------------------------------------------------------------

    @Test fun `sections split configured, popular and the rest in catalog order`() {
        val providers = listOf(
            provider(id = "cline", oauth = true, capabilities = listOf("popular", "oauth")),
            provider(id = "anthropic", capabilities = listOf("popular")),
            provider(id = "openrouter", apiKey = "k", capabilities = listOf("popular")),
            provider(id = "zai"),
        )
        val all = sectionProviders(providers, "")
        assertEquals(listOf("cline", "openrouter"), all.configured.map { it.id })
        assertEquals(listOf("anthropic"), all.popular.map { it.id })
        assertEquals(listOf("zai"), all.others.map { it.id })
        val filtered = sectionProviders(providers, "AN")
        assertEquals(listOf("anthropic"), filtered.popular.map { it.id })
        assertTrue(filtered.configured.isEmpty())
    }

    // ---- toSettingsPatch -----------------------------------------------------------------------

    @Test fun `settings patch nests dotted paths`() {
        val patch = toSettingsPatch(mapOf("aws.region" to JsonPrimitive("us-east-1"), "aws.profile" to null, "timeout" to JsonPrimitive(30), ".." to JsonPrimitive("skip")))
        val expected = buildJsonObject {
            put("aws", buildJsonObject { put("region", "us-east-1"); put("profile", JsonNull) })
            put("timeout", 30)
        }
        assertEquals(expected, patch)
        assertEquals(JsonPrimitive("us-east-1"), patch["aws"]!!.jsonObject["region"])
    }

    @Test fun `number fields drop non-finite values`() {
        assertEquals(JsonPrimitive(42L), numberFieldValue(" 42 "))
        assertEquals(JsonPrimitive(1.5), numberFieldValue("1.5"))
        assertEquals(JsonNull, numberFieldValue(""))
        assertEquals(JsonNull, numberFieldValue("abc"))
        assertEquals(JsonNull, numberFieldValue("Infinity"))
    }

    @Test fun `catalog response decodes leniently`() {
        val text = """{"providers":[{"id":"anthropic","name":"Anthropic","enabled":true,"configured":true,"apiKey":"sk-x",
            "unknownField":1,"capabilities":["popular"],"modelList":[{"id":"claude","name":"Claude","contextWindow":200000,
            "featured":{"tier":"recommended","rank":0,"tags":[]}}],"auth":{"providerId":"anthropic","capabilities":["popular"]}}],
            "settingsPath":"/x"}"""
        val payload = ProviderJson.decodeFromString(ProviderCatalogResponse.serializer(), text)
        val item = payload.providers.single()
        assertEquals("sk-x", item.apiKey)
        assertEquals(200_000L, item.modelList?.single()?.contextWindow)
        assertEquals(AuthKind.ApiKey, item.authKind())
    }
}
