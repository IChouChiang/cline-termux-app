package dev.cline.termux.ui.settings

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.data.customize.SettingsSnapshot
import dev.cline.termux.data.prefs.SignInPending
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.providers.isProviderConnected
import dev.cline.termux.data.schedules.SchedulesRepository
import dev.cline.termux.ui.navigation.About as AboutRoute
import dev.cline.termux.ui.navigation.Account as AccountRoute
import dev.cline.termux.ui.navigation.Customize as CustomizeRoute
import dev.cline.termux.ui.navigation.KernelSettings as KernelSettingsRoute
import dev.cline.termux.ui.navigation.Marketplace as MarketplaceRoute
import dev.cline.termux.ui.navigation.Schedules as SchedulesRoute
import dev.cline.termux.ui.navigation.SettingsAppearance as SettingsAppearanceRoute
import dev.cline.termux.ui.navigation.SettingsNotifications as SettingsNotificationsRoute
import dev.cline.termux.ui.navigation.SettingsProviders as SettingsProvidersRoute
import dev.cline.termux.ui.navigation.ShellRoute
import dev.cline.termux.ui.theme.FontSizeSetting
import dev.cline.termux.ui.theme.ThemeMode
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/*
 * Pure models and rules of Settings home (settings-providers.md S1). Everything here is
 * plain Kotlin so the summaries are unit-tested on the JVM; the screen maps each model to
 * its string resources.
 */

/**
 * The navigation rows of S1 (desktop `sections.ts` order, S1.2). [key] is the
 * `settings?section=` spelling (docs/07 §6.2). Telemetry, New user experience and
 * Diagnostics act in place and are not sections.
 */
enum class SettingsSection(val key: String) {
    Appearance("appearance"),
    Notifications("notifications"),
    Kernel("kernel"),
    Providers("providers"),
    Account("account"),
    Installed("installed"),
    Marketplace("marketplace"),
    Schedules("schedules"),
    About("about"),
    ;

    /**
     * The route the row pushes when it is not shown in the expanded detail pane. Installed
     * and Schedules open their pushed variants (customize-and-rest.md §0.2: "two entry kinds").
     */
    val route: ShellRoute
        get() = when (this) {
            Appearance -> SettingsAppearanceRoute
            Notifications -> SettingsNotificationsRoute
            Kernel -> KernelSettingsRoute
            Providers -> SettingsProvidersRoute
            Account -> AccountRoute()
            Installed -> CustomizeRoute(pushed = true)
            Marketplace -> MarketplaceRoute()
            Schedules -> SchedulesRoute(pushed = true)
            About -> AboutRoute
        }

    /**
     * Whether the section is a single page that may sit in the expanded detail pane (SP §0.2).
     * Providers, Installed, Marketplace and Schedules own two-pane scaffolds of their own and
     * always replace the Settings scaffold instead of nesting in it.
     */
    val singlePane: Boolean
        get() = when (this) {
            Appearance, Notifications, Kernel, Account, About -> true
            Providers, Installed, Marketplace, Schedules -> false
        }

    companion object {
        /** The detail pane shown on expanded windows before the user picks a row (SP §0.2). */
        val DEFAULT_DETAIL = Appearance

        fun fromKey(key: String?): SettingsSection? = entries.firstOrNull { it.key == key }
    }
}

/** Appearance row summary (S1.2): "<theme> · <Dynamic colour if on>" plus the font size when ≠ 15. */
@Immutable
data class AppearanceSummary(val theme: ThemeMode, val dynamicColor: Boolean, val fontSize: Int?) {
    companion object {
        /** [dynamicSupported] is API 31+: below it the brand scheme is the only one (DS §1.2.2). */
        fun of(theme: ThemeMode, dynamicColor: Boolean, fontSize: Int, dynamicSupported: Boolean) = AppearanceSummary(
            theme = theme,
            dynamicColor = dynamicColor && dynamicSupported,
            fontSize = FontSizeSetting.clamp(fontSize).takeIf { it != FontSizeSetting.DEFAULT },
        )
    }
}

/** Whether the system lets the app post (S1.3 `NotificationsSummary.permission`). */
enum class SummaryPermission { Granted, Denied, NotRequired }

/** Notifications row summary: "On for N events" / "Off", or the blocked line when the system says no. */
@Immutable
data class NotificationsSummary(val enabledCount: Int, val permission: SummaryPermission) {
    val blocked: Boolean get() = permission == SummaryPermission.Denied
}

/** Providers row summary (desktop `ProviderListContent` subtitle). */
@Immutable
data class ProvidersSummary(val configured: Int, val available: Int, val fallback: Boolean) {
    /** "Connect a provider to start using models." */
    val none: Boolean get() = configured == 0

    companion object {
        /**
         * Counts as the desktop does (`providers.filter(isProviderConnected)` over the whole
         * list); null while the source is unknown: the catalog has not initialised, or in
         * fallback mode `providers.json` has not been read yet.
         */
        fun of(state: CatalogState): ProvidersSummary? {
            if (!state.initialized) return null
            val fallback = state.source == CatalogSource.Fallback
            if (fallback && state.presence == null) return null
            return ProvidersSummary(
                configured = state.providers.count(::isProviderConnected),
                available = state.providers.size,
                fallback = fallback,
            )
        }
    }
}

/** Account row summary (S1.2). */
@Immutable
sealed interface AccountSummary {
    /** `providers.json` not read (or Termux blocked): no supporting line. */
    data object Unknown : AccountSummary

    /** "<display name or email> · <organisation or "Personal">" from the cached `fetchMe`. */
    data class SignedIn(val name: String, val scope: String?) : AccountSummary

    /** Signed in, never fetched: "Signed in to Cline". */
    data object SignedInUnknown : AccountSummary

    /** "Sign in to Cline". */
    data object SignedOut : AccountSummary

    /** A Termux sign-in survived process death (SP §10): "Waiting for browser...". */
    data object Waiting : AccountSummary

    companion object {
        const val CLINE_PROVIDER_ID = "cline"

        /**
         * Signed in = `providers.cline.settings.apiKey` or `auth.accessToken` non-blank (the
         * SP §0.4 `configured` predicate). A pending Cline sign-in wins until it resolves.
         */
        fun of(presence: ConfiguredProviders?, displayName: String?, scope: String?, pending: SignInPending?): AccountSummary = when {
            pending?.providerId == CLINE_PROVIDER_ID -> Waiting
            presence == null -> Unknown
            !presence.isConfigured(CLINE_PROVIDER_ID) -> SignedOut
            displayName.isNullOrBlank() -> SignedInUnknown
            else -> SignedIn(displayName, scope?.takeIf { it.isNotBlank() })
        }
    }
}

/** Installed row counts from the `settings.list` snapshot; zero kinds are omitted on screen. */
@Immutable
data class InstalledCounts(val skills: Int, val mcpServers: Int, val plugins: Int, val rules: Int) {
    val isEmpty: Boolean get() = skills == 0 && mcpServers == 0 && plugins == 0 && rules == 0

    companion object {
        /** Skills count workflows too, as the desktop Customize counts do. */
        fun of(snapshot: SettingsSnapshot): InstalledCounts = InstalledCounts(
            skills = snapshot.skills.size + snapshot.workflows.size,
            mcpServers = snapshot.mcp.size,
            plugins = snapshot.plugins.size,
            rules = snapshot.rules.size,
        )
    }
}

/** Schedules row counts: `schedule.list` size and `schedule.active` runs. */
@Immutable
data class ScheduleCounts(val schedules: Int, val active: Int) {
    companion object {
        fun of(overview: SchedulesRepository.Overview): ScheduleCounts =
            ScheduleCounts(schedules = overview.schedules.size, active = overview.active.size)
    }
}

/** Why a telemetry write failed; the screen formats "Failed to update telemetry setting: <detail>". */
sealed interface TelemetryError {
    /** Kernel or helper text, shown as data. */
    data class Message(val text: String) : TelemetryError

    /** The helper could not start and gave no detail: "Settings helper unavailable". */
    data object HelperUnavailable : TelemetryError
}

/** The Telemetry row (S1.2 behaviour, desktop `updateTelemetryOptOut`). */
@Immutable
data class TelemetryRowState(
    /** `!telemetryOptOut`; null while unknown (not read yet, or Termux blocked). */
    val enabled: Boolean? = null,
    /** `global-settings.json` read in flight: switch disabled, no spinner. */
    val loading: Boolean = true,
    /** A write is in flight: switch disabled with a 16dp spinner before it. */
    val saving: Boolean = false,
    /** The helper is booting for this write: "Starting settings helper..." replaces the supporting line. */
    val startingHelper: Boolean = false,
    val error: TelemetryError? = null,
    /** False in fallback mode (`BuildUnsupported` / `KernelStopped` known before the toggle). */
    val writable: Boolean = true,
) {
    val switchEnabled: Boolean get() = enabled != null && !loading && !saving && writable
}

object TelemetryReply {
    /** `get_global_settings` / `set_telemetry_opt_out` reply → `telemetryOptOut`, or [fallback] when absent. */
    fun optOut(reply: JsonElement?, fallback: Boolean): Boolean =
        ((reply as? JsonObject)?.get("telemetryOptOut") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: fallback
}
