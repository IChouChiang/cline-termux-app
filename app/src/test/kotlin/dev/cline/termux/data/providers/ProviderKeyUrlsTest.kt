package dev.cline.termux.data.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The desktop's `provider-key-urls.test.ts` cases, plus the trimming rules the ports relied on. */
class ProviderKeyUrlsTest {

    // ---- provider-key-urls.test.ts ----

    @Test fun `prefers the catalog docUrl when present`() {
        assertEquals("https://example.com/keys", getProviderApiKeyUrl("anthropic", docUrl = "https://example.com/keys"))
    }

    @Test fun `falls back to curated URLs for popular providers`() {
        assertEquals("https://console.anthropic.com/settings/keys", getProviderApiKeyUrl("anthropic"))
        assertEquals("https://platform.openai.com/api-keys", getProviderApiKeyUrl("openai-native"))
        assertEquals("https://openrouter.ai/settings/keys", getProviderApiKeyUrl("OpenRouter"))
    }

    @Test fun `returns null for unknown providers`() {
        assertNull(getProviderApiKeyUrl("some-obscure-provider"))
        assertNull(getProviderApiKeyUrl("", docUrl = "   "))
    }

    // ---- trimming ----

    @Test fun `lookup ignores case and surrounding whitespace`() {
        assertEquals(getProviderApiKeyUrl("anthropic"), getProviderApiKeyUrl("  Anthropic "))
    }

    @Test fun `a docUrl is trimmed and a blank one falls back to the curated map`() {
        assertEquals("https://example.com/keys", getProviderApiKeyUrl("anthropic", docUrl = " https://example.com/keys "))
        assertEquals("https://console.anthropic.com/settings/keys", getProviderApiKeyUrl("anthropic", docUrl = "  "))
    }

    @Test fun `the Cline dashboard is the Cline key page`() {
        assertEquals(CLINE_DASHBOARD_URL, getProviderApiKeyUrl("cline"))
    }
}
