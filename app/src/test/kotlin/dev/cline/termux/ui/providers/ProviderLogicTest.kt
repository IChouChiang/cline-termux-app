package dev.cline.termux.ui.providers

import dev.cline.termux.core.kernel.termux.ConfiguredProvider
import dev.cline.termux.data.providers.AuthKind
import dev.cline.termux.data.providers.ConfigField
import dev.cline.termux.data.providers.ConfigFieldOption
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.data.providers.ProviderModel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ported desktop rules behind S4–S5 (provider-list-view.tsx); key URLs are in ProviderKeyUrlsTest. */
class ProviderLogicTest {

    // ---- field values ----

    private val text = ConfigField("region", "Region", ConfigField.FIELD_TEXT)
    private val number = ConfigField("maxTokens", "Max tokens", ConfigField.FIELD_NUMBER)
    private val select = ConfigField(
        "tier", "Tier", ConfigField.FIELD_SELECT,
        options = listOf(ConfigFieldOption("Fast", JsonPrimitive("fast")), ConfigFieldOption("Two", JsonPrimitive(2))),
    )
    private val apiKey = ConfigField(ProviderListItem.API_KEY_PATH, "API Key", ConfigField.FIELD_PASSWORD)

    @Test fun `coerce trims text, nulls empty drafts and parses numbers`() {
        assertEquals(JsonPrimitive("eu"), coerceFieldValue(text, "  eu "))
        assertEquals(JsonNull, coerceFieldValue(text, "   "))
        assertEquals("4096", fieldValueText(coerceFieldValue(number, "4096")))
    }

    @Test fun `select drafts map back to the option's typed value`() {
        assertEquals(JsonPrimitive(2), coerceFieldValue(select, "2"))
        assertEquals(JsonPrimitive("fast"), coerceFieldValue(select, "fast"))
    }

    @Test fun `initial values come from config values, then the secret, base url and default`() {
        val provider = ProviderListItem(
            id = "p",
            baseUrl = "https://base",
            configValues = mapOf("region" to JsonPrimitive("us")),
        )
        assertEquals(JsonPrimitive("us"), initialFieldValue(provider, text))
        assertEquals(JsonPrimitive("sk-1"), initialFieldValue(provider, apiKey, "sk-1"))
        assertNull(initialFieldValue(provider, apiKey))
        val baseUrl = ConfigField(ProviderListItem.BASE_URL_PATH, "Base URL", ConfigField.FIELD_URL)
        assertEquals(JsonPrimitive("https://base"), initialFieldValue(provider, baseUrl))
        val withDefault = ConfigField("x", "X", ConfigField.FIELD_TEXT, defaultValue = JsonPrimitive("d"))
        assertEquals(JsonPrimitive("d"), initialFieldValue(provider, withDefault))
    }

    @Test fun `an unchanged draft is not a change`() {
        assertFalse(draftDiffers(text, " us ", JsonPrimitive("us")))
        assertTrue(draftDiffers(text, "eu", JsonPrimitive("us")))
        assertFalse(draftDiffers(text, "", null))
        assertTrue(draftDiffers(text, "", JsonPrimitive("us")))
    }

    @Test fun `boolean values follow JavaScript truthiness`() {
        assertTrue(fieldChecked(JsonPrimitive(true)))
        assertFalse(fieldChecked(JsonPrimitive("false")))
        assertTrue(fieldChecked(JsonPrimitive("yes")))
        assertFalse(fieldChecked(null))
    }

    // ---- connection section ----

    @Test fun `connection variants`() {
        assertEquals(ConnectionVariant.OAuthSignedIn, connectionVariant(AuthKind.OAuth, oauthSignedIn = true, connected = true, apiKeyPresent = false))
        assertEquals(ConnectionVariant.OAuthWithApiKey, connectionVariant(AuthKind.OAuth, oauthSignedIn = false, connected = true, apiKeyPresent = true))
        assertEquals(ConnectionVariant.OAuthSignIn, connectionVariant(AuthKind.OAuth, oauthSignedIn = false, connected = false, apiKeyPresent = true))
        assertEquals(ConnectionVariant.Local, connectionVariant(AuthKind.Local, false, false, false))
        assertEquals(ConnectionVariant.ApiKey, connectionVariant(AuthKind.ApiKey, false, true, true))
    }

    @Test fun `auto-save note only for connected key providers outside fallback`() {
        assertTrue(showsAutoSaveNote(connected = true, authKind = AuthKind.ApiKey, fallback = false))
        assertFalse(showsAutoSaveNote(connected = true, authKind = AuthKind.ApiKey, fallback = true))
        assertFalse(showsAutoSaveNote(connected = true, authKind = AuthKind.OAuth, fallback = false))
        assertFalse(showsAutoSaveNote(connected = false, authKind = AuthKind.ApiKey, fallback = false))
    }

    @Test fun `sign out only for browser sign-ins`() {
        assertTrue(isSignOutAction(AuthKind.OAuth, oauthSignedIn = true))
        assertFalse(isSignOutAction(AuthKind.OAuth, oauthSignedIn = false))
        assertFalse(isSignOutAction(AuthKind.ApiKey, oauthSignedIn = true))
    }

    @Test fun `effective selection falls back to the first connected, then the first provider`() {
        val providers = listOf(ProviderListItem("a"), ProviderListItem("b", enabled = true, apiKey = "k"), ProviderListItem("c"))
        assertEquals("c", effectiveSelectedProviderId("c", providers))
        assertEquals("b", effectiveSelectedProviderId("gone", providers))
        assertEquals("a", effectiveSelectedProviderId(null, listOf(ProviderListItem("a"))))
        assertNull(effectiveSelectedProviderId(null, emptyList()))
    }

    // ---- models card ----

    @Test fun `model search shows for any list, in fallback only above five`() {
        assertFalse(showsModelSearch(0, fallback = false))
        assertTrue(showsModelSearch(1, fallback = false))
        assertFalse(showsModelSearch(5, fallback = true))
        assertTrue(showsModelSearch(6, fallback = true))
    }

    @Test fun `model rows filter by query and put favourites first`() {
        val models = listOf(ProviderModel("a", "Alpha"), ProviderModel("b", "Beta"), ProviderModel("c", "Gamma"))
        assertEquals(listOf("c", "a", "b"), modelRows(models, "", favorites = listOf("c")).map { it.id })
        assertTrue(modelRows(models, "", favorites = listOf("c")).first().favorite)
        assertEquals(listOf("b"), modelRows(models, "bet", favorites = emptyList()).map { it.id })
    }

    // ---- fallback connection (S5.1) ----

    private fun entry(hasApiKey: Boolean, hasOAuth: Boolean, tokenSource: String?) = ConfiguredProvider(
        id = "cline", hasApiKey = hasApiKey, hasOAuth = hasOAuth, hasCloudCredentials = false,
        model = null, hasBaseUrl = false, tokenSource = tokenSource, updatedAt = null,
    )

    @Test fun `fallback browser sign-in needs the token and the oauth token source or no key`() {
        assertTrue(fallbackOAuthSignedIn(entry(hasApiKey = false, hasOAuth = true, tokenSource = "oauth")))
        assertTrue(fallbackOAuthSignedIn(entry(hasApiKey = true, hasOAuth = true, tokenSource = "oauth")))
        assertTrue(fallbackOAuthSignedIn(entry(hasApiKey = false, hasOAuth = true, tokenSource = null)))
        assertFalse(fallbackOAuthSignedIn(entry(hasApiKey = true, hasOAuth = true, tokenSource = "manual")))
        assertFalse(fallbackOAuthSignedIn(entry(hasApiKey = true, hasOAuth = false, tokenSource = "manual")))
        assertFalse(fallbackOAuthSignedIn(entry(hasApiKey = false, hasOAuth = false, tokenSource = "oauth")))
        assertFalse(fallbackOAuthSignedIn(null))
    }

    @Test fun `the fallback form shows alone for key providers and as the key row for OAuth`() {
        assertTrue(fallbackFormShown(true, ConnectionVariant.ApiKey, "anthropic"))
        assertTrue(fallbackFormShown(true, ConnectionVariant.OAuthWithApiKey, "openai-codex"))
        assertTrue(fallbackFormShown(true, ConnectionVariant.OAuthSignIn, "cline"))
        assertFalse(fallbackFormShown(true, ConnectionVariant.OAuthSignIn, "openai-codex"))
        assertFalse(fallbackFormShown(true, ConnectionVariant.OAuthSignIn, "oca"))
        assertFalse(fallbackFormShown(true, ConnectionVariant.OAuthSignedIn, "cline"))
        assertFalse(fallbackFormShown(true, ConnectionVariant.Local, "claude-code"))
        assertFalse(fallbackFormShown(false, ConnectionVariant.ApiKey, "anthropic"))
    }

    // ---- S4 row layout at large text (DS §8.1.8) ----

    @Test fun `a phone row at default text keeps the status trailing`() {
        assertFalse(providerRowStacked(widthDp = 412f, textScale = 1f))
        assertFalse(providerRowStacked(widthDp = 360f, textScale = 1f))
    }

    @Test fun `system scale 2 times size 20 stacks the status under the name`() {
        val scale = 2f * (20f / 15f)
        assertTrue(providerRowStacked(widthDp = 412f, textScale = scale))
    }

    @Test fun `a narrow row stacks even at default text`() {
        assertTrue(providerRowStacked(widthDp = 320f, textScale = 1f))
    }

    @Test fun `a wide pane keeps the status trailing at moderate scale`() {
        assertFalse(providerRowStacked(widthDp = 840f, textScale = 1.3f))
        assertTrue(providerRowStacked(widthDp = 412f, textScale = 1.3f))
    }

    @Test fun `a degenerate text scale does not divide by zero`() {
        assertFalse(providerRowStacked(widthDp = 412f, textScale = 0f))
    }
}
