package dev.cline.termux.ui.navigation

import dev.cline.termux.core.kernel.CheckId

/** One instance of every route, with arguments that need encoding (spaces, slashes, `?&=`, `+`, non-ASCII). */
object RouteSamples {
    const val AWKWARD_ID = "sess/ä b+c?d&e=f%"

    val root: List<RootRoute> = listOf(
        OnboardingGraph,
        OnboardingWelcome,
        OnboardingKernel,
        OnboardingReliability,
        OnboardingConnect,
        OnboardingDone,
        KernelInstallTermux,
        KernelRepair(),
        KernelRepair.of(CheckId.Permission, pushed = true),
        KernelRepair.of(CheckId.Hub, pushed = false),
        KernelReliability,
        SetupConnect,
        SetupDone,
        Shell,
    )

    val shell: List<ShellRoute> = listOf(
        Chat(),
        Chat("s-1"),
        Chat(AWKWARD_ID, ChatSheet.Approval.wire),
        Chat(sheet = ChatSheet.Queue.wire),
        ChildChat(AWKWARD_ID),
        ChatChanges(AWKWARD_ID),
        ChatTool(AWKWARD_ID, "call/1 2"),
        Sessions,
        Search,
        Schedules(),
        Schedules(pushed = true),
        Customize(),
        Customize("mcp", pushed = true),
        Settings(),
        Settings("advanced"),
        SettingsAppearance,
        SettingsNotifications,
        SettingsProviders,
        SettingsProvider("open router"),
        SettingsProvidersAdd,
        KernelSettings,
        Account(),
        Account("billing"),
        About,
        Marketplace(),
        Marketplace("mcp", "web & search"),
        MarketplaceEntryRoute("skill", "a/b"),
        ScheduleDetails("nightly build"),
        McpEdit(),
        McpEdit("git hub"),
        ScheduleEdit(),
        ScheduleEdit("id 1", "daily"),
        AboutDiagnostics,
        WorkspaceBrowse(),
        WorkspaceBrowse("/data/data/com.termux/files/home/my project"),
        AboutLicenses,
        DebugKernelProbe,
    )

    val all: List<AppRoute> = root + shell
}
