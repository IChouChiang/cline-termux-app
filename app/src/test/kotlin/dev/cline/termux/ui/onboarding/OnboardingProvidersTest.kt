package dev.cline.termux.ui.onboarding

import dev.cline.termux.data.catalog.FallbackProviders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The desktop's `api-key-providers.test.ts` cases, plus C.7 sources and validation (key URLs: ProviderKeyUrlsTest). */
class OnboardingProvidersTest {

    private fun sorted(vararg candidates: ApiKeySetupCandidate): List<String> =
        sortProvidersForApiKeySetup(candidates.toList()) { it }.map { it.id }

    private fun key(id: String, name: String = id) = ApiKeySetupCandidate(id, name, listOf("apiKey"))

    @Test
    fun `OAuth providers are dropped and preferred ids lead, the rest by name`() {
        assertEquals(
            listOf("anthropic", "openrouter", "baseten", "zai"),
            sorted(key("zai", "Z.ai"), key("cline", "Cline"), key("openai-codex", "OpenAI Codex"), key("openrouter", "OpenRouter"), key("anthropic", "Anthropic"), key("baseten", "Baseten")),
        )
    }

    @Test
    fun `providers needing more than a key are dropped, key plus base URL is kept`() {
        assertEquals(
            listOf("anthropic", "ollama"),
            sorted(
                ApiKeySetupCandidate("vertex", "Vertex", listOf("region", "projectId")),
                ApiKeySetupCandidate("bedrock", "Bedrock", listOf("apiKey", "region", "awsAccessKey")),
                ApiKeySetupCandidate("claude-code", "Claude Code", listOf("claudeCodePath")),
                ApiKeySetupCandidate("ollama", "Ollama", listOf("apiKey", "baseUrl")),
                key("anthropic", "Anthropic"),
            ),
        )
    }

    @Test
    fun `missing field metadata reads as a plain key field`() {
        assertTrue(isApiKeyOnlyProvider(null))
        assertFalse(isApiKeyOnlyProvider(emptyList()))
        assertFalse(isApiKeyOnlyProvider(listOf("baseUrl")))
        assertEquals(listOf("anthropic", "aaa"), sorted(ApiKeySetupCandidate("aaa", "Aaa", null), key("anthropic", "Anthropic")))
    }

    @Test
    fun `the bundled list is the preferred API-key providers then openai-compatible`() {
        val file = listOf("src/main/res/raw/fallback_providers.json", "app/src/main/res/raw/fallback_providers.json").map(::File).first { it.isFile }
        val options = ProviderOptions.bundled(FallbackProviders.parse(file.readText()))
        assertEquals("anthropic", options.first().id)
        val compatible = options.last()
        assertEquals(OPENAI_COMPATIBLE_ID, compatible.id)
        assertNull(compatible.defaultModelId)
        assertTrue(compatible.needsBaseUrl)
        val preferredIds = options.dropLast(1).map { it.id }
        assertEquals(preferredIds, PREFERRED_PROVIDER_ORDER.filter { it in preferredIds })
        assertTrue(options.dropLast(1).none { it.needsBaseUrl })
        assertEquals(options.size, options.map { it.id }.distinct().size)
    }

    @Test
    fun `base URLs need http or https and a host`() {
        assertTrue(ConnectValidation.isValidBaseUrl("https://api.example.com/v1"))
        assertTrue(ConnectValidation.isValidBaseUrl(" http://192.168.1.4:11434 "))
        assertFalse(ConnectValidation.isValidBaseUrl("api.example.com"))
        assertFalse(ConnectValidation.isValidBaseUrl("https://"))
        assertFalse(ConnectValidation.isValidBaseUrl("ftp://host"))
        assertFalse(ConnectValidation.isValidBaseUrl("https://bad host"))
    }

    @Test
    fun `field errors stay quiet until touched and Connect needs every required field`() {
        val compatible = ProviderOption(OPENAI_COMPATIBLE_ID, "OpenAI Compatible", null, needsBaseUrl = true, keyUrl = null)
        val anthropic = ProviderOption("anthropic", "Anthropic", "claude", needsBaseUrl = false, keyUrl = null)
        assertEquals(emptyMap<ConnectField, ConnectFieldError>(), ConnectValidation.fieldErrors(compatible, "", "", emptySet()))
        assertEquals(
            mapOf(ConnectField.Model to ConnectFieldError.ModelRequired, ConnectField.BaseUrl to ConnectFieldError.BaseUrlInvalid),
            ConnectValidation.fieldErrors(compatible, " ", "nope", setOf(ConnectField.Model, ConnectField.BaseUrl)),
        )
        assertEquals(emptyMap<ConnectField, ConnectFieldError>(), ConnectValidation.fieldErrors(anthropic, "claude", "nope", setOf(ConnectField.BaseUrl)))

        assertFalse(ConnectValidation.canSubmit(null, "k", "m", "", saving = false))
        assertFalse(ConnectValidation.canSubmit(anthropic, " ", "m", "", saving = false))
        assertFalse(ConnectValidation.canSubmit(anthropic, "k", "", "", saving = false))
        assertFalse(ConnectValidation.canSubmit(anthropic, "k", "m", "", saving = true))
        assertTrue(ConnectValidation.canSubmit(anthropic, "k", "m", "", saving = false))
        assertFalse(ConnectValidation.canSubmit(compatible, "k", "m", "", saving = false))
        assertTrue(ConnectValidation.canSubmit(compatible, "k", "m", "https://h/v1", saving = false))
    }

    @Test
    fun `the save error detail is the last non-blank stderr line`() {
        assertEquals("Error: invalid key", ConnectValidation.lastLine("warn\nError: invalid key\n\n  "))
        assertNull(ConnectValidation.lastLine(" \n "))
    }

    @Test
    fun `the connection survives the SavedStateHandle round trip`() {
        listOf(OnboardingConnection.Cline(), OnboardingConnection.Cline(unverified = true), OnboardingConnection.Provider("OpenRouter"))
            .forEach { assertEquals(it, OnboardingConnection.decode(it.encode())) }
        assertNull(OnboardingConnection.decode(null))
        assertNull(OnboardingConnection.decode("other"))
    }
}
