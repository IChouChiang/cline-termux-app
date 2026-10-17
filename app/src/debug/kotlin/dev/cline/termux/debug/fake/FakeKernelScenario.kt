package dev.cline.termux.debug.fake

/**
 * The debug fake kernel scenarios of onboarding-kernel.md §0.9, selected with
 * `am start -S -n io.github.ichouchiang.clinetermux.debug/dev.cline.termux.MainActivity --es dev.cline.termux.FAKE_KERNEL <id>`
 * (`off` returns to the real kernel). Each id is the spec's name verbatim.
 */
enum class FakeKernelScenario(val id: String) {
    TermuxMissing("termux_missing"),
    TermuxDisabled("termux_disabled"),
    TermuxOld("termux_old"),
    PermissionMissing("permission_missing"),
    ExternalAppsBlocked("external_apps_blocked"),
    TermuxNotSetUp("termux_not_set_up"),
    PortMissing("port_missing"),
    PortOld("port_old"),
    HubStartSlow("hub_start_slow"),
    HubStartFail("hub_start_fail"),
    HubStartHang("hub_start_hang"),
    HubStartPortOld("hub_start_port_old"),
    StoppedInTermux("stopped_in_termux"),
    HubComesBack("hub_comes_back"),
    RestartBusyOtherClient("restart_busy_other_client"),
    RestartSlow("restart_slow"),
    HubCrashLoop("hub_crash_loop"),
    AsleepAfterReady("asleep_after_ready"),
    ProtocolUnsupported("protocol_unsupported"),
    AuthFailed("auth_failed"),
    Draining("draining"),
    ProvidersEmpty("providers_empty"),
    ProvidersCline("providers_cline"),
    ProvidersAnthropic("providers_anthropic"),
    ProvidersOllama("providers_ollama"),
    AuthOk("auth_ok"),
    AuthFailInvalidProvider("auth_fail_invalid_provider"),
    SignInSuccessAfter20s("signin_success_after_20s"),
    SignInNever("signin_never"),
    ;

    /** A Hub process is already running in Termux when the app starts. */
    val hubUpAtLaunch: Boolean
        get() = this in setOf(
            StoppedInTermux, HubComesBack, AsleepAfterReady, RestartBusyOtherClient, RestartSlow,
            ProtocolUnsupported, AuthFailed, Draining,
        )

    companion object {
        const val OFF = "off"

        fun fromId(id: String?): FakeKernelScenario? = entries.firstOrNull { it.id == id?.trim() }
    }
}
