package dev.cline.termux.ui.navigation

import dev.cline.termux.core.kernel.CheckId
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.Serializable

/**
 * Every destination of the app (docs/07 §6.2), as navigation-compose 2.10 type-safe routes.
 *
 * Two hosts: [RootRoute]s live in `RootNavHost` (onboarding graph, the kernel screens that
 * replace or sit on top of the shell, the shell itself); [ShellRoute]s live in the shell's
 * `ShellNavHost`. Arguments are only `String?` and `Boolean`, the types navigation's
 * serializer maps without a custom `NavType`.
 *
 * [path] is the deep-link spelling of docs/07 §6.2 (`chat/{id}?sheet=approval`,
 * `kernel/repair?focus=Permission&pushed=true`); [Routes.parse] reads it back. Both are pure,
 * so they are unit-tested on the JVM.
 */
sealed interface AppRoute {
    /** The docs/07 §6.2 deep-link spelling of this route, with its arguments filled in. */
    val path: String
}

/** A destination of `RootNavHost`. */
sealed interface RootRoute : AppRoute

/** A destination of the shell's `ShellNavHost`. */
sealed interface ShellRoute : AppRoute

// ---------------------------------------------------------------------------------- root

/** The onboarding graph (KS §0.2): start destination while `onboarding.completedAt == null`. */
@Serializable
data object OnboardingGraph : RootRoute {
    override val path: String get() = "onboarding"
}

/** A0 Welcome (KS A). */
@Serializable
data object OnboardingWelcome : RootRoute {
    override val path: String get() = "onboarding/welcome"
}

/** A Kernel setup, first-run variant (KS A). */
@Serializable
data object OnboardingKernel : RootRoute {
    override val path: String get() = "onboarding/kernel"
}

/** D "Keep the kernel running", onboarding variant (KS D). */
@Serializable
data object OnboardingReliability : RootRoute {
    override val path: String get() = "onboarding/reliability"
}

/** C Connect a model, first-run variant (KS C). */
@Serializable
data object OnboardingConnect : RootRoute {
    override val path: String get() = "onboarding/connect"
}

/** C5 Done, first-run variant (KS C). */
@Serializable
data object OnboardingDone : RootRoute {
    override val path: String get() = "onboarding/done"
}

/** B Install Termux (KS B): pushed over A, or replacing the shell while Termux is missing. */
@Serializable
data object KernelInstallTermux : RootRoute {
    override val path: String get() = "kernel/install-termux"
}

/**
 * A, repair variant (KS A.3). [pushed] = false is the **blocking** variant that replaces the
 * shell; true is the **pushed** variant on top of it. [focus] is a [CheckId] name.
 */
@Serializable
data class KernelRepair(val focus: String? = null, val pushed: Boolean = false) : RootRoute {
    /** The checklist row to focus, or null when [focus] names no known row. */
    val checkId: CheckId? get() = focus?.let { name -> CheckId.entries.firstOrNull { it.name == name } }

    override val path: String
        get() = Routes.withQuery("kernel/repair", "focus" to focus, "pushed" to if (pushed) "true" else null)

    companion object {
        fun of(focus: CheckId?, pushed: Boolean): KernelRepair = KernelRepair(focus?.name, pushed)
    }
}

/** D, standalone variant (KS D.3). */
@Serializable
data object KernelReliability : RootRoute {
    override val path: String get() = "kernel/reliability"
}

/** C, re-entry variant (KS C.3). */
@Serializable
data object SetupConnect : RootRoute {
    override val path: String get() = "setup/connect"
}

/** C5 Done, re-entry variant (KS C.3). */
@Serializable
data object SetupDone : RootRoute {
    override val path: String get() = "setup/done"
}

/** The app shell (DS §5): start destination once onboarded. */
@Serializable
data object Shell : RootRoute {
    override val path: String get() = "shell"
}

// --------------------------------------------------------------------------------- shell

/** A sheet a deep link opens over a chat (CA §0.6). Sheets are never routes themselves. */
enum class ChatSheet(val wire: String) {
    Approval("approval"),
    Question("question"),
    Queue("queue"),
    ;

    companion object {
        fun fromWire(value: String?): ChatSheet? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The chat destination (HC §2.9): the shell's fixed start destination, entered once as
 * `Chat()` and never popped. Home when no session is selected; top-level sessions are a
 * **selection** inside this entry ([ChatSelection]: `SavedStateHandle["sessionId"]`), never
 * separate entries. `Chat(id, sheet)` is the deep-link spelling of that selection
 * (`chat/{id}?sheet=approval`, CA §0.6); [ShellNavigator.openSession] applies it to the root
 * entry instead of pushing.
 */
@Serializable
data class Chat(val sessionId: String? = null, val sheet: String? = null) : ShellRoute {
    val chatSheet: ChatSheet? get() = ChatSheet.fromWire(sheet)

    override val path: String
        get() = Routes.withQuery("chat/" + (sessionId?.let(Routes::encode) ?: ""), "sheet" to sheet)
}

/**
 * A subagent (child) session pushed above the chat root (HC §2.9 "subagent sessions are
 * pushed entries", CR §6 agents roster). A destination of its own, so the root stays the only
 * [Chat] entry and `popUpTo<Chat>` always means "back to the chat root".
 */
@Serializable
data class ChildChat(val sessionId: String) : ShellRoute {
    override val path: String get() = "chat/${Routes.encode(sessionId)}?child=true"
}

/** Changes (CT A): pushed below 1200dp, the chat's supporting pane at ≥ 1200 × ≥ 480. */
@Serializable
data class ChatChanges(val sessionId: String) : ShellRoute {
    override val path: String get() = "chat/${Routes.encode(sessionId)}/changes"
}

/** Tool detail (CT B): pushed below 1200dp, the chat's supporting pane at ≥ 1200 × ≥ 480. */
@Serializable
data class ChatTool(val sessionId: String, val toolCallId: String) : ShellRoute {
    override val path: String get() = "chat/${Routes.encode(sessionId)}/tool/${Routes.encode(toolCallId)}"
}

/** S2 Sessions screen (SS §3). */
@Serializable
data object Sessions : ShellRoute {
    override val path: String get() = "sessions"
}

/** S3 Session search, full-screen variant (SS §4); expanded windows use the docked bar instead. */
@Serializable
data object Search : ShellRoute {
    override val path: String get() = "search"
}

/** Schedules (CR C5): top-level (drawer/rail) or, with [pushed], on top of Settings (CR §0.2). */
@Serializable
data class Schedules(val pushed: Boolean = false) : ShellRoute {
    override val path: String get() = Routes.withQuery("schedules", "pushed" to if (pushed) "true" else null)
}

/** Customize (CR C1): top-level or, with [pushed], on top of Settings; [tab] picks the tab. */
@Serializable
data class Customize(val tab: String? = null, val pushed: Boolean = false) : ShellRoute {
    override val path: String
        get() = Routes.withQuery("customize", "tab" to tab, "pushed" to if (pushed) "true" else null)
}

/** Settings home (SP S1), top-level. [section] preselects the expanded list-detail's detail pane. */
@Serializable
data class Settings(val section: String? = null) : ShellRoute {
    override val path: String get() = Routes.withQuery("settings", "section" to section)
}

@Serializable
data object SettingsAppearance : ShellRoute {
    override val path: String get() = "settings/appearance"
}

@Serializable
data object SettingsNotifications : ShellRoute {
    override val path: String get() = "settings/notifications"
}

/** Providers (SP S4): on expanded it replaces the Settings scaffold with its own (SP §0.2). */
@Serializable
data object SettingsProviders : ShellRoute {
    override val path: String get() = "settings/providers"
}

@Serializable
data class SettingsProvider(val providerId: String) : ShellRoute {
    override val path: String get() = "settings/providers/${Routes.encode(providerId)}"
}

/** Add provider (SP S6): an editor (full screen on compact/medium, dialog on expanded). */
@Serializable
data object SettingsProvidersAdd : ShellRoute {
    override val path: String get() = "settings/providers/add"
}

/** Kernel settings (route `kernel`). */
@Serializable
data object KernelSettings : ShellRoute {
    override val path: String get() = "kernel"
}

@Serializable
data class Account(val tab: String? = null) : ShellRoute {
    override val path: String get() = Routes.withQuery("account", "tab" to tab)
}

@Serializable
data object About : ShellRoute {
    override val path: String get() = "about"
}

@Serializable
data class Marketplace(val type: String? = null, val tag: String? = null) : ShellRoute {
    override val path: String get() = Routes.withQuery("marketplace", "type" to type, "tag" to tag)
}

/** Marketplace detail: pushed on compact/medium, the detail pane on expanded (CR §3). */
@Serializable
data class MarketplaceEntryRoute(val type: String, val id: String) : ShellRoute {
    override val path: String get() = "marketplace/entry/${Routes.encode(type)}/${Routes.encode(id)}"
}

/** Schedule details: pushed on compact/medium, the detail pane on expanded (CR §4). */
@Serializable
data class ScheduleDetails(val scheduleId: String) : ShellRoute {
    override val path: String get() = "schedules/${Routes.encode(scheduleId)}"
}

/** MCP server editor (CR C2). */
@Serializable
data class McpEdit(val name: String? = null) : ShellRoute {
    override val path: String get() = Routes.withQuery("customize/mcp/edit", "name" to name)
}

/** Schedule editor (CR C6). */
@Serializable
data class ScheduleEdit(val id: String? = null, val template: String? = null) : ShellRoute {
    override val path: String get() = Routes.withQuery("schedules/edit", "id" to id, "template" to template)
}

/** Export diagnostics (CR C12), an editor-style route. */
@Serializable
data object AboutDiagnostics : ShellRoute {
    override val path: String get() = "about/diagnostics"
}

/** Folder browser (SP S9). */
@Serializable
data class WorkspaceBrowse(val start: String? = null) : ShellRoute {
    override val path: String get() = Routes.withQuery("workspace/browse", "start" to start)
}

@Serializable
data object AboutLicenses : ShellRoute {
    override val path: String get() = "about/licenses"
}

/** The developer kernel probe; registered only in debug builds (`DebugScreens.available`). */
@Serializable
data object DebugKernelProbe : ShellRoute {
    override val path: String get() = "debug/kernel-probe"
}

// ----------------------------------------------------------------------- classification

/** How a shell route enters and leaves (DS §6 transitions, docs/07 §6.2 "Kind"). */
enum class RouteKind {
    /** A drawer/rail destination: fade-through between them. */
    TopLevel,

    /** Pushed: shared axis X. */
    Pushed,

    /** An editor: shared axis Y + fade on compact/medium, a `dialog()` on expanded. */
    Editor,
}

/** The four destinations of DS §5.2 (drawer items on compact, rail items on medium and expanded). */
enum class TopLevelDestination { Chats, Schedules, Customize, Settings }

object Routes {
    /** Every editor route (CR §0.2, SP S6): `if (expanded) dialog(route) else composable(route)`. */
    fun isEditor(route: AppRoute): Boolean =
        route is McpEdit || route is ScheduleEdit || route is AboutDiagnostics || route is WorkspaceBrowse || route is SettingsProvidersAdd

    fun kindOf(route: ShellRoute): RouteKind = when {
        isEditor(route) -> RouteKind.Editor
        topLevelOf(route) != null -> RouteKind.TopLevel
        else -> RouteKind.Pushed
    }

    /** The drawer/rail destination [route] **is** (not the area it belongs to); null for pushed routes. */
    fun topLevelOf(route: ShellRoute): TopLevelDestination? = when (route) {
        is Chat -> TopLevelDestination.Chats
        is Schedules -> if (route.pushed) null else TopLevelDestination.Schedules
        is Customize -> if (route.pushed) null else TopLevelDestination.Customize
        is Settings -> TopLevelDestination.Settings
        else -> null
    }

    /**
     * The area the user is in, from the shell back stack (bottom first): the last top-level
     * entry decides. Used for rail/drawer selection and for showing the sessions list pane
     * on expanded windows (only in the Chats area).
     */
    fun areaOf(backStack: List<ShellRoute>): TopLevelDestination =
        backStack.lastOrNull { topLevelOf(it) != null }?.let(::topLevelOf) ?: TopLevelDestination.Chats

    /** The route a drawer/rail item opens. */
    fun routeOf(destination: TopLevelDestination): ShellRoute = when (destination) {
        TopLevelDestination.Chats -> Chat()
        TopLevelDestination.Schedules -> Schedules()
        TopLevelDestination.Customize -> Customize()
        TopLevelDestination.Settings -> Settings()
    }

    // ------------------------------------------------------------------ path spelling

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    fun decode(value: String): String = URLDecoder.decode(value, Charsets.UTF_8.name())

    /** `base?key=value&…` with null values left out and values encoded. */
    fun withQuery(base: String, vararg params: Pair<String, String?>): String {
        val query = params.filter { it.second != null }.joinToString("&") { (k, v) -> "$k=${encode(v!!)}" }
        return if (query.isEmpty()) base else "$base?$query"
    }

    /**
     * Reads a docs/07 §6.2 path back into its route, or null when the path names no
     * destination. Literal segments win over arguments (`settings/providers/add` is the add
     * editor, `schedules/edit` the schedule editor).
     */
    fun parse(path: String): AppRoute? {
        val trimmed = path.trim().removePrefix("/")
        val base = trimmed.substringBefore('?')
        val query = parseQuery(trimmed.substringAfter('?', ""))
        fun q(key: String): String? = query[key]
        fun flag(key: String): Boolean = q(key) == "true"
        val s = base.split('/').map(::decode)
        return when {
            base == "onboarding" -> OnboardingGraph
            base == "onboarding/welcome" -> OnboardingWelcome
            base == "onboarding/kernel" -> OnboardingKernel
            base == "onboarding/reliability" -> OnboardingReliability
            base == "onboarding/connect" -> OnboardingConnect
            base == "onboarding/done" -> OnboardingDone
            base == "kernel/install-termux" -> KernelInstallTermux
            base == "kernel/repair" -> KernelRepair(q("focus"), flag("pushed"))
            base == "kernel/reliability" -> KernelReliability
            base == "setup/connect" -> SetupConnect
            base == "setup/done" -> SetupDone
            base == "shell" -> Shell
            base == "kernel" -> KernelSettings
            base == "chat" || base == "chat/" -> Chat(sheet = q("sheet"))
            s.size == 2 && s[0] == "chat" -> if (flag("child")) ChildChat(s[1]) else Chat(s[1], q("sheet"))
            s.size == 3 && s[0] == "chat" && s[2] == "changes" -> ChatChanges(s[1])
            s.size == 4 && s[0] == "chat" && s[2] == "tool" -> ChatTool(s[1], s[3])
            base == "sessions" -> Sessions
            base == "search" -> Search
            base == "schedules" -> Schedules(flag("pushed"))
            base == "schedules/edit" -> ScheduleEdit(q("id"), q("template"))
            s.size == 2 && s[0] == "schedules" -> ScheduleDetails(s[1])
            base == "customize" -> Customize(q("tab"), flag("pushed"))
            base == "customize/mcp/edit" -> McpEdit(q("name"))
            base == "settings" -> Settings(q("section"))
            base == "settings/appearance" -> SettingsAppearance
            base == "settings/notifications" -> SettingsNotifications
            base == "settings/providers" -> SettingsProviders
            base == "settings/providers/add" -> SettingsProvidersAdd
            s.size == 3 && s[0] == "settings" && s[1] == "providers" -> SettingsProvider(s[2])
            base == "account" -> Account(q("tab"))
            base == "about" -> About
            base == "about/diagnostics" -> AboutDiagnostics
            base == "about/licenses" -> AboutLicenses
            base == "marketplace" -> Marketplace(q("type"), q("tag"))
            s.size == 4 && s[0] == "marketplace" && s[1] == "entry" -> MarketplaceEntryRoute(s[2], s[3])
            base == "workspace/browse" -> WorkspaceBrowse(q("start"))
            base == "debug/kernel-probe" -> DebugKernelProbe
            else -> null
        }
    }

    private fun parseQuery(query: String): Map<String, String> =
        if (query.isEmpty()) {
            emptyMap()
        } else {
            query.split('&').filter { it.isNotEmpty() }.associate { part ->
                val key = part.substringBefore('=')
                key to decode(part.substringAfter('=', ""))
            }
        }
}
