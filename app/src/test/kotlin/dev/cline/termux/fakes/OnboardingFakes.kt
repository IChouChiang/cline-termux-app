package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.PortVersion
import dev.cline.termux.core.kernel.termux.ProviderPresence
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.ui.kernel.DoctorOutcome
import dev.cline.termux.ui.kernel.KernelDiagnostics
import dev.cline.termux.ui.kernel.UiText
import dev.cline.termux.ui.onboarding.ClineSignIn
import dev.cline.termux.ui.onboarding.OnboardingPrefs
import dev.cline.termux.ui.onboarding.ProviderOption
import dev.cline.termux.ui.onboarding.ProviderSetup
import dev.cline.termux.ui.onboarding.ProviderSource
import dev.cline.termux.ui.onboarding.ReliabilityProbe
import dev.cline.termux.ui.onboarding.SetupKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A scripted kernel for the onboarding ViewModels: [state] is set by the test (or by the
 * [onRecheck] / [onStart] hooks, which replay a fake-kernel scenario's timeline); every call
 * is recorded in [calls].
 */
class FakeSetupKernel(
    initial: KernelState = KernelState.Unknown,
    var presence: TermuxPresence = TermuxPresence.Installed(118, "0.118.3"),
    var permitted: Boolean = true,
    private val clock: () -> Long = { 0L },
) : SetupKernel {
    val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<KernelState> get() = mutableState
    val mutablePortVersion = MutableStateFlow<PortVersion?>(PortVersion("3.0.68-termux.2", "3.0.68", "v3.0.68-termux.2"))
    override val portVersion: StateFlow<PortVersion?> get() = mutablePortVersion
    val calls = mutableListOf<String>()

    var onRecheck: suspend FakeSetupKernel.() -> Unit = {}
    var onStart: suspend FakeSetupKernel.() -> Unit = {}
    var onAutoStart: suspend FakeSetupKernel.() -> Unit = {}
    var restartOutcome: RestartOutcome = RestartOutcome.Restarted

    override fun termuxPresence(): TermuxPresence = presence
    override fun hasRunCommandPermission(): Boolean = permitted
    override suspend fun recheck() {
        calls += "recheck"
        onRecheck()
    }
    override suspend fun start() {
        calls += "start"
        onStart()
    }
    override suspend fun autoStart() {
        calls += "autoStart"
        onAutoStart()
    }
    override suspend fun restartForProtocol(): RestartOutcome {
        calls += "restart"
        return restartOutcome
    }
    override fun elapsedMs(): Long = clock()

    fun emit(state: KernelState) {
        mutableState.value = state
    }
}

/** [KernelDiagnostics] over a scripted doctor. */
fun fakeDiagnostics(scope: CoroutineScope, outcome: () -> DoctorOutcome = { DoctorOutcome.Failed(UiText.Raw("doctor failed")) }): KernelDiagnostics =
    KernelDiagnostics(
        object : KernelDiagnostics.Runner {
            override suspend fun doctor(): DoctorOutcome = outcome()
            override suspend fun fix(): DoctorOutcome = outcome()
        },
        scope,
    )

class FakeOnboardingPrefs(var shownAt: Long? = null) : OnboardingPrefs {
    var marks = 0
    override suspend fun reliabilityShownAt(): Long? = shownAt
    override suspend fun markReliabilityShown() {
        marks++
        shownAt = 1L
    }
}

class FakeReliabilityProbe(var done: Boolean = false) : ReliabilityProbe {
    override fun verifiableItemsDone(): Boolean = done
}

class FakeProviderSetup(
    var presence: ProviderPresence = ProviderPresence.Missing,
    var options: ProviderSetup.OptionsResult = ProviderSetup.OptionsResult.Loaded(
        listOf(
            ProviderOption("anthropic", "Anthropic", "claude-sonnet-5", needsBaseUrl = false, keyUrl = "https://console.anthropic.com/settings/keys"),
            ProviderOption("openai-compatible", "OpenAI Compatible", null, needsBaseUrl = true, keyUrl = null),
        ),
        ProviderSource.Bundled,
    ),
    var bundled: List<ProviderOption> = listOf(ProviderOption("anthropic", "Anthropic", "claude-sonnet-5", needsBaseUrl = false, keyUrl = null)),
    var saveResult: ProviderSetup.SaveResult = ProviderSetup.SaveResult.Saved,
    var displayName: String? = null,
) : ProviderSetup {
    var reads = 0
    var loads = 0
    val saves = mutableListOf<List<String?>>()
    val remembered = mutableListOf<Pair<String, String?>>()

    /** Set by a save that succeeded, so the next read sees the provider (like `cline auth` writing providers.json). */
    var afterSave: ConfiguredProviders? = null

    override suspend fun readPresence(): ProviderPresence {
        reads++
        return presence
    }

    override suspend fun loadOptions(force: Boolean): ProviderSetup.OptionsResult {
        loads++
        return options
    }

    override suspend fun bundledOptions(): List<ProviderOption> = bundled

    override suspend fun saveProvider(providerId: String, apiKey: String, modelId: String, baseUrl: String?): ProviderSetup.SaveResult {
        // The key is recorded only as its length: tests assert it never leaks into state.
        saves += listOf(providerId, apiKey.length.toString(), modelId, baseUrl)
        if (saveResult == ProviderSetup.SaveResult.Saved) afterSave?.let { presence = ProviderPresence.Present(it) }
        return saveResult
    }

    override suspend fun rememberSelection(providerId: String, modelId: String?) {
        remembered += providerId to modelId
    }

    override suspend fun accountDisplayName(): String? = displayName
}

class FakeClineSignIn : ClineSignIn {
    val mutableState = MutableStateFlow<SignInState>(SignInState.Idle)
    override val state: StateFlow<SignInState> get() = mutableState
    val calls = mutableListOf<String>()
    val submittedKeyLengths = mutableListOf<Int>()

    override fun start() {
        calls += "start"
    }
    override fun cancel() {
        calls += "cancel"
        mutableState.value = SignInState.Idle
    }
    override fun submitApiKey(apiKey: String) {
        calls += "submitApiKey"
        submittedKeyLengths += apiKey.length
    }
    override fun openTermux(): Boolean {
        calls += "openTermux"
        return true
    }
    override fun acknowledge() {
        calls += "acknowledge"
        val s = mutableState.value
        if (s is SignInState.Succeeded || s is SignInState.AlreadySignedIn || s is SignInState.Failed || s is SignInState.Blocked) {
            mutableState.value = SignInState.Idle
        }
    }
}
