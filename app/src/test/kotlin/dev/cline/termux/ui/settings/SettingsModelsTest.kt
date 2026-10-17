package dev.cline.termux.ui.settings

import androidx.compose.runtime.Composable
import dev.cline.termux.ClineApp
import dev.cline.termux.core.kernel.termux.ConfiguredProvider
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.data.customize.SettingsItem
import dev.cline.termux.data.customize.SettingsKind
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.prefs.SignInPending
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.ProviderListItem
import dev.cline.termux.ui.navigation.Customize
import dev.cline.termux.ui.navigation.Schedules
import dev.cline.termux.ui.navigation.SettingsAppearance
import dev.cline.termux.ui.theme.AppIcon
import dev.cline.termux.ui.theme.ThemeMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelsTest {

    @Test
    fun `sections round-trip their keys and only single pages may sit in the pane`() {
        SettingsSection.entries.forEach { assertEquals(it, SettingsSection.fromKey(it.key)) }
        assertNull(SettingsSection.fromKey("nope"))
        assertNull(SettingsSection.fromKey(null))
        assertEquals(
            listOf(SettingsSection.Appearance, SettingsSection.Notifications, SettingsSection.Kernel, SettingsSection.Account, SettingsSection.About),
            SettingsSection.entries.filter { it.singlePane },
        )
        assertEquals(SettingsSection.Appearance, SettingsSection.DEFAULT_DETAIL)
    }

    @Test
    fun `installed and schedules push their pushed variants`() {
        assertEquals(Customize(pushed = true), SettingsSection.Installed.route)
        assertEquals(Schedules(pushed = true), SettingsSection.Schedules.route)
        assertEquals(SettingsAppearance, SettingsSection.Appearance.route)
    }

    @Test
    fun `appearance summary hides dynamic colour below API 31 and the default font size`() {
        assertEquals(AppearanceSummary(ThemeMode.Dark, false, null), AppearanceSummary.of(ThemeMode.Dark, true, 15, dynamicSupported = false))
        assertEquals(AppearanceSummary(ThemeMode.Light, true, 20), AppearanceSummary.of(ThemeMode.Light, true, 25, dynamicSupported = true))
        assertEquals(12, AppearanceSummary.of(ThemeMode.System, false, 3, true).fontSize)
    }

    @Test
    fun `notifications summary is blocked only when the system denies`() {
        assertTrue(NotificationsSummary(4, SummaryPermission.Denied).blocked)
        assertFalse(NotificationsSummary(4, SummaryPermission.Granted).blocked)
        assertFalse(NotificationsSummary(0, SummaryPermission.NotRequired).blocked)
    }

    @Test
    fun `providers summary waits for the catalog and counts connected providers`() {
        val providers = listOf(
            ProviderListItem(id = "anthropic", enabled = true, configured = true),
            ProviderListItem(id = "openai"),
            ProviderListItem(id = "cline", oauthAccessTokenPresent = true),
        )
        assertNull(ProvidersSummary.of(CatalogState(providers = providers)))
        assertNull(ProvidersSummary.of(CatalogState(source = CatalogSource.Fallback, providers = providers, initialized = true)))
        val fresh = ProvidersSummary.of(CatalogState(source = CatalogSource.Catalog, providers = providers, initialized = true))
        assertEquals(ProvidersSummary(configured = 2, available = 3, fallback = false), fresh)
        val fallback = ProvidersSummary.of(
            CatalogState(source = CatalogSource.Fallback, providers = emptyList(), presence = presence(), initialized = true),
        )
        assertEquals(ProvidersSummary(0, 0, fallback = true), fallback)
        assertTrue(fallback!!.none)
    }

    @Test
    fun `account summary covers every sign-in state`() {
        val signedIn = presence("cline" to true)
        assertEquals(AccountSummary.Unknown, AccountSummary.of(null, "Ada", null, null))
        assertEquals(AccountSummary.SignedOut, AccountSummary.of(presence("openai" to true), "Ada", null, null))
        assertEquals(AccountSummary.SignedInUnknown, AccountSummary.of(signedIn, " ", null, null))
        assertEquals(AccountSummary.SignedIn("Ada", null), AccountSummary.of(signedIn, "Ada", "", null))
        assertEquals(AccountSummary.SignedIn("Ada", "Acme"), AccountSummary.of(signedIn, "Ada", "Acme", null))
        assertEquals(AccountSummary.Waiting, AccountSummary.of(signedIn, "Ada", null, SignInPending("cline", 0L)))
        assertEquals(AccountSummary.SignedIn("Ada", null), AccountSummary.of(signedIn, "Ada", null, SignInPending("openai", 0L)))
    }

    @Test
    fun `installed counts fold workflows into skills`() {
        val snapshot = SettingsSnapshot(
            workflows = listOf(item("w")),
            skills = listOf(item("s1"), item("s2")),
            mcp = listOf(item("m")),
            rules = listOf(item("r1"), item("r2"), item("r3")),
        )
        val counts = InstalledCounts.of(snapshot)
        assertEquals(InstalledCounts(skills = 3, mcpServers = 1, plugins = 0, rules = 3), counts)
        assertFalse(counts.isEmpty)
        assertTrue(InstalledCounts.of(SettingsSnapshot()).isEmpty)
    }

    @Test
    fun `telemetry reply reads a boolean or keeps the fallback`() {
        assertTrue(TelemetryReply.optOut(buildJsonObject { put("telemetryOptOut", JsonPrimitive(true)) }, fallback = false))
        assertFalse(TelemetryReply.optOut(buildJsonObject { put("telemetryOptOut", JsonPrimitive(false)) }, fallback = true))
        assertTrue(TelemetryReply.optOut(buildJsonObject { put("telemetryOptOut", JsonPrimitive("false")) }, fallback = true))
        assertTrue(TelemetryReply.optOut(JsonNull, fallback = true))
        assertFalse(TelemetryReply.optOut(null, fallback = false))
    }

    @Test
    fun `telemetry switch is enabled only when known, idle and writable`() {
        assertFalse(TelemetryRowState().switchEnabled)
        assertTrue(TelemetryRowState(enabled = true, loading = false).switchEnabled)
        assertFalse(TelemetryRowState(enabled = true, loading = false, saving = true).switchEnabled)
        assertFalse(TelemetryRowState(enabled = false, loading = false, writable = false).switchEnabled)
    }

    @Test
    fun `launcher plan keeps the chosen alias and defers a retire under the live task`() {
        val midnight = ClineApp.launcherAlias(AppIcon.Midnight)
        val classic = ClineApp.launcherAlias(AppIcon.Classic)
        assertEquals(listOf(midnight, classic), LauncherIconPlan.aliases)
        assertEquals(listOf(classic), LauncherIconPlan.toDisable(AppIcon.Midnight))
        assertNull(LauncherIconPlan.retireOnStop(AppIcon.Classic, baseComponent = midnight))
        assertEquals(listOf(midnight), LauncherIconPlan.retireOnStop(AppIcon.Classic, baseComponent = "dev.cline.termux.MainActivity"))
        assertEquals(listOf(midnight), LauncherIconPlan.retireOnStop(AppIcon.Classic, baseComponent = null))
    }

    @Test
    fun `detail panes always hold appearance and notifications and only supplied single pages`() {
        val kernel: @Composable () -> Unit = {}
        val providers: @Composable () -> Unit = {}
        val panes = SettingsDetailPanes(mapOf(SettingsSection.Kernel to kernel, SettingsSection.Providers to providers))
        assertTrue(panes.showsInPane(SettingsSection.Appearance))
        assertTrue(panes.showsInPane(SettingsSection.Notifications))
        assertTrue(panes.showsInPane(SettingsSection.Kernel))
        assertFalse(panes.showsInPane(SettingsSection.Providers))
        assertFalse(panes.showsInPane(SettingsSection.About))
        assertFalse(SettingsDetailPanes().showsInPane(SettingsSection.Kernel))
        assertNull(panes.content(SettingsSection.About))
    }

    private fun presence(vararg entries: Pair<String, Boolean>) = ConfiguredProviders(
        lastUsedProvider = null,
        providers = entries.associate { (id, key) ->
            id to ConfiguredProvider(id, hasApiKey = key, hasOAuth = false, hasCloudCredentials = false, model = null, hasBaseUrl = false, tokenSource = null, updatedAt = null)
        },
    )

    private fun item(id: String) = SettingsItem(id = id, name = id, path = "/$id", kind = SettingsKind.Skill, source = "global")
}
