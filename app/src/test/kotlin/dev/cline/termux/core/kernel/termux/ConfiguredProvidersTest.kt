package dev.cline.termux.core.kernel.termux

import dev.cline.termux.expectThrows
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/07 §10.1 "Bridge": plaintext never escapes the projection. */
class ConfiguredProvidersTest {
    private val apiKey = "sk-ant-api03-PLAINTEXT-KEY-9f8e7d6c"
    private val accessToken = "eyJhbGciOiJIUzI1NiJ9.PLAINTEXT.TOKEN"
    private val awsSecret = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"

    private val fixture = """
        {
          "version": 1,
          "lastUsedProvider": "anthropic",
          "providers": {
            "anthropic": {
              "settings": {"provider": "anthropic", "apiKey": "$apiKey", "model": "claude-sonnet-4-6", "baseUrl": "https://api.anthropic.com"},
              "updatedAt": "2026-10-03T10:00:00.000Z",
              "tokenSource": "manual"
            },
            "cline": {
              "settings": {"provider": "cline", "model": "anthropic/claude-sonnet-5",
                "auth": {"accessToken": "$accessToken", "refreshToken": "r", "accountId": "acc_1"}},
              "updatedAt": "2026-10-03T11:00:00.000Z",
              "tokenSource": "oauth"
            },
            "bedrock": {
              "settings": {"provider": "bedrock", "model": "anthropic.claude-sonnet", "aws": {"accessKey": "AKIA", "secretKey": "$awsSecret", "region": "us-east-1"}},
              "updatedAt": "2026-10-03T12:00:00.000Z",
              "tokenSource": "manual"
            },
            "ollama": {
              "settings": {"provider": "ollama", "model": "llama3", "baseUrl": "http://127.0.0.1:11434"},
              "updatedAt": "2026-10-03T13:00:00.000Z",
              "tokenSource": "manual"
            },
            "openai-native": {
              "settings": {"provider": "openai-native", "apiKey": "", "model": "gpt-5.5"},
              "updatedAt": "2026-10-03T14:00:00.000Z",
              "tokenSource": "migration"
            },
            "gemini": {
              "settings": {"provider": "gemini"},
              "updatedAt": "2026-10-03T15:00:00.000Z",
              "tokenSource": "manual"
            }
          }
        }
    """.trimIndent()

    @Test
    fun `projection keeps presence flags only`() {
        val providers = ConfiguredProviders.parse(fixture)
        val anthropic = providers.providers.getValue("anthropic")
        assertTrue(anthropic.hasApiKey && anthropic.hasBaseUrl && !anthropic.hasOAuth && !anthropic.hasCloudCredentials)
        assertEquals("claude-sonnet-4-6", anthropic.model)
        assertEquals("manual", anthropic.tokenSource)
        assertEquals("2026-10-03T10:00:00.000Z", anthropic.updatedAt)

        val cline = providers.providers.getValue("cline")
        assertTrue(cline.hasOAuth && !cline.hasApiKey)
        assertEquals("oauth", cline.tokenSource)

        val bedrock = providers.providers.getValue("bedrock")
        assertTrue(bedrock.hasCloudCredentials && !bedrock.hasApiKey)

        assertFalse(providers.providers.getValue("openai-native").hasApiKey)
        assertEquals("anthropic", providers.lastUsedProvider)
    }

    @Test
    fun `plaintext never escapes the parser`() {
        val providers = ConfiguredProviders.parse(fixture)
        val rendered = providers.toString()
        assertFalse(rendered.contains(apiKey))
        assertFalse(rendered.contains(accessToken))
        assertFalse(rendered.contains(awsSecret))
        assertFalse(rendered.contains("https://api.anthropic.com"))
        assertFalse(rendered.contains("11434"))
        assertFalse(rendered.contains("acc_1"))
    }

    @Test
    fun `isConfigured follows the key-or-oauth clause and isConnected adds the keyless approximation`() {
        val providers = ConfiguredProviders.parse(fixture)
        assertTrue(providers.isConfigured("anthropic"))
        assertTrue(providers.isConfigured("cline"))
        assertFalse(providers.isConfigured("bedrock"))
        assertFalse(providers.isConfigured("ollama"))
        assertFalse(providers.isConfigured("openai-native"))
        assertFalse(providers.isConfigured("missing"))

        assertTrue(providers.isConnected("bedrock")) // cloud credentials + model
        assertTrue(providers.isConnected("ollama")) // keyless provider + model
        assertFalse(providers.isConnected("gemini")) // nothing at all
        assertFalse(providers.isConnected("openai-native")) // empty key, not keyless
        assertTrue(providers.anyConnected)
        assertEquals(listOf("anthropic", "cline"), providers.configuredIds)
        assertEquals("anthropic", providers.defaultProviderId)
        assertEquals("llama3", providers.defaultModelId("ollama"))
        assertNull(providers.defaultModelId(null))
    }

    @Test
    fun `updatedSince reads ISO and epoch stamps`() {
        val providers = ConfiguredProviders.parse(fixture)
        assertTrue(providers.updatedSince("cline", 1_790_000_000_000L))
        assertFalse(providers.updatedSince("cline", 1_800_000_000_000L))
        assertFalse(providers.updatedSince("missing", 0))
        val epoch = ConfiguredProviders.parse("""{"providers":{"x":{"settings":{"provider":"x"},"updatedAt":"1700000000000"}}}""")
        assertTrue(epoch.updatedSince("x", 1_699_999_999_999L))
        val garbage = ConfiguredProviders.parse("""{"providers":{"x":{"settings":{"provider":"x"},"updatedAt":"yesterday"}}}""")
        assertFalse(garbage.updatedSince("x", 0))
    }

    @Test
    fun `lastUsedProvider that is not connected does not become the default`() {
        val providers = ConfiguredProviders.parse(
            """{"version":1,"lastUsedProvider":"gemini","providers":{"gemini":{"settings":{"provider":"gemini"},"updatedAt":"t","tokenSource":"manual"},
               "xai":{"settings":{"provider":"xai","apiKey":"k","model":"grok"},"updatedAt":"t","tokenSource":"manual"}}}""",
        )
        assertEquals("xai", providers.defaultProviderId)
    }

    @Test
    fun `empty and malformed inputs`() {
        val empty = ConfiguredProviders.parse("""{"version":1,"providers":{}}""")
        assertTrue(empty.isEmpty)
        assertNull(empty.defaultProviderId)
        assertFalse(empty.anyConnected)
        expectThrows<Exception> { ConfiguredProviders.parse("not json") }
        expectThrows<IllegalArgumentException> { ConfiguredProviders.parse("[]") }
    }

    @Test
    fun `presence reduces the RUN_COMMAND result in the bridge`() {
        val present = ProviderPresence.from(TermuxResult.success(fixture))
        assertTrue(present is ProviderPresence.Present)
        assertTrue(present.providersOrEmpty.isConfigured("anthropic"))

        val missing = ProviderPresence.from(TermuxResult.failure(1, "cat: ${TermuxPaths.PROVIDERS_JSON}: No such file or directory"))
        assertEquals(ProviderPresence.Missing, missing)
        assertTrue(missing.providersOrEmpty.isEmpty)

        val garbage = ProviderPresence.from(TermuxResult.success("{broken"))
        assertTrue(garbage is ProviderPresence.Failed)
        assertFalse(garbage.toString().contains("broken"))

        val undelivered = ProviderPresence.from(TermuxResult.undelivered(150, "Executable not found"))
        assertEquals(ProviderPresence.Failed("Executable not found"), undelivered)
    }

    @Test
    fun `readProviderPresence issues one cat and keeps no stdout`() = runTest {
        val termux = FakeTermuxExecutor().apply { on(ClineCommands.Labels.READ_PROVIDER_PRESENCE, TermuxResult.success(fixture)) }
        val presence = termux.readProviderPresence()
        assertEquals(listOf(ClineCommands.Labels.READ_PROVIDER_PRESENCE), termux.labels)
        assertTrue(presence.providersOrEmpty.isConfigured("cline"))
        assertFalse(presence.toString().contains(apiKey))
    }
}
