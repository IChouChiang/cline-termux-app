package dev.cline.termux.ui.onboarding

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.termux.ConfiguredProviders
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.data.account.ClineSignInCoordinator
import dev.cline.termux.data.account.SignInFailure
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.providers.OAUTH_PROVIDER_IDS
import dev.cline.termux.ui.kernel.UiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SetupMethod { Cline, ApiKey }

/** What Done confirms (desktop `OnboardingConnection`). */
sealed interface OnboardingConnection {
    /** [unverified]: a Cline API key saved without the helper's `fetchMe` check (Done's extra line). */
    data class Cline(val unverified: Boolean = false) : OnboardingConnection

    data class Provider(val name: String) : OnboardingConnection

    /** The SavedStateHandle spelling Connect hands to Done. */
    fun encode(): String = when (this) {
        is Cline -> if (unverified) "cline:unverified" else "cline"
        is Provider -> "provider:$name"
    }

    companion object {
        fun decode(value: String?): OnboardingConnection? = when {
            value == null -> null
            value == "cline" -> Cline()
            value == "cline:unverified" -> Cline(unverified = true)
            value.startsWith("provider:") -> Provider(value.removePrefix("provider:"))
            else -> null
        }
    }
}

sealed interface ClineSignInUi {
    data object Idle : ClineSignInUi
    data class Waiting(val startedAtEpochMs: Long, val attempt: Int) : ClineSignInUi

    /** [displayName] from the cached `fetchMe` ("Signed in as %1$s"), else "Signed in to Cline". */
    data class SignedIn(val displayName: String?) : ClineSignInUi
    data class Error(val message: UiText) : ClineSignInUi
}

sealed interface ProviderOptionsUi {
    data object Loading : ProviderOptionsUi

    /** [catalogError]: the helper answered but `list_provider_catalog` failed; the list is then the bundled one. */
    data class Loaded(val options: List<ProviderOption>, val source: ProviderSource, val catalogError: String? = null) : ProviderOptionsUi
}

/** C.7 `ConnectUiState`. The key texts are never here: they live in the composable's `remember`. */
@Immutable
data class ConnectUiState(
    /** P1–P3 pass; false shows the "Cline can't reach Termux right now." card and disables actions. */
    val kernelUsable: Boolean = true,
    /** The repair row "Open kernel setup" focuses. */
    val kernelFocus: CheckId? = null,
    /** Null while the first `readProviders` runs (C.5 "loading"); redacted (KS §0.6). */
    val configured: ConfiguredProviders? = null,
    val method: SetupMethod = SetupMethod.Cline,
    val cline: ClineSignInUi = ClineSignInUi.Idle,
    val clineKeyFormExpanded: Boolean = false,
    val clineKeySaving: Boolean = false,
    val clineKeyError: UiText? = null,
    val providers: ProviderOptionsUi = ProviderOptionsUi.Loading,
    val selectedProviderId: String? = null,
    val modelId: String = "",
    val baseUrl: String = "",
    val modelEdited: Boolean = false,
    val touched: Set<ConnectField> = emptySet(),
    val saving: Boolean = false,
    val saveError: UiText? = null,
) {
    val loading: Boolean get() = configured == null

    /** Desktop `clineBusy = signingIn || clineKeySaving`. */
    val clineBusy: Boolean get() = cline is ClineSignInUi.Waiting || clineKeySaving

    val options: List<ProviderOption> get() = (providers as? ProviderOptionsUi.Loaded)?.options.orEmpty()

    val selectedOption: ProviderOption? get() = options.firstOrNull { it.id == selectedProviderId }

    val fieldErrors: Map<ConnectField, ConnectFieldError>
        get() = ConnectValidation.fieldErrors(selectedOption, modelId, baseUrl, touched)

    /** The connected bring-your-own-key provider's display name ("%1$s is connected." + Continue). */
    val connectedProviderName: String?
        get() {
            val providers = configured ?: return null
            val ids = providers.providers.keys.filter { it !in OAUTH_PROVIDER_IDS && providers.isConnected(it) }
            if (ids.isEmpty()) return null
            val id = providers.lastUsedProvider?.takeIf { it in ids } ?: ids.first()
            return options.firstOrNull { it.id == id }?.name ?: id
        }

    fun canSubmitKey(apiKey: String): Boolean =
        kernelUsable && !loading && ConnectValidation.canSubmit(selectedOption, apiKey, modelId, baseUrl, saving)

    fun canSubmitClineKey(apiKey: String): Boolean = kernelUsable && !loading && apiKey.isNotBlank() && !clineBusy
}

sealed interface ConnectEvent {
    data class ToDone(val connection: OnboardingConnection) : ConnectEvent

    /** A successful save: the screen clears the key fields from memory. */
    data object ClearKeys : ConnectEvent
}

/**
 * Connect a model (onboarding-kernel.md C.5–C.8): the "Sign in with Cline" card on the
 * process-wide [ClineSignIn] (SP S7; its `stat` poll, 15-minute timeout and cancel retries),
 * the "Use a Cline API key" form on the same flow, and "Use your own API key" through
 * `cline auth --provider` ([ProviderSetup]). `readProviders` runs only at the C.6 triggers:
 * entry, `ON_RESUME`, and after an auth action finished.
 */
class ConnectModelViewModel(
    private val kernel: SetupKernel,
    private val setup: ProviderSetup,
    private val signIn: ClineSignIn,
) : ViewModel() {

    private val _ui = MutableStateFlow(ConnectUiState())
    val ui: StateFlow<ConnectUiState> = _ui.asStateFlow()

    private val _events = Channel<ConnectEvent>(Channel.BUFFERED)
    val events: Flow<ConnectEvent> = _events.receiveAsFlow()

    private var presenceJob: Job? = null
    private var attempt = 0

    init {
        viewModelScope.launch { kernel.state.collect { updateKernel(it) } }
        viewModelScope.launch { signIn.state.collect { onSignInState(it) } }
        refreshPresence()
        loadOptions(force = false)
    }

    private fun updateKernel(state: KernelState) {
        val focus = focusFor(state, kernel.hasRunCommandPermission())
        _ui.update { it.copy(kernelUsable = focus == null, kernelFocus = focus) }
    }

    // ---- providers.json (readProviders) -----------------------------------------------------------

    /** One `readProviders`; never on a timer (KS §0.5). */
    fun refreshPresence() {
        if (presenceJob?.isActive == true) return
        presenceJob = viewModelScope.launch {
            val presence = setup.readPresence()
            val providers = presence.providersOrEmpty
            val name = if (ClineSignInCoordinator.isSignedIn(providers, ClineSignInCoordinator.CLINE)) setup.accountDisplayName() else null
            _ui.update { current ->
                current.copy(
                    configured = providers,
                    cline = when {
                        current.cline is ClineSignInUi.Waiting || current.cline is ClineSignInUi.Error -> current.cline
                        ClineSignInCoordinator.isSignedIn(providers, ClineSignInCoordinator.CLINE) -> ClineSignInUi.SignedIn(name)
                        else -> ClineSignInUi.Idle
                    },
                )
            }
        }
    }

    /** `ON_RESUME` (C.6 trigger b). */
    fun onResume() {
        updateKernel(kernel.state.value)
        refreshPresence()
    }

    private fun loadOptions(force: Boolean) {
        viewModelScope.launch {
            if (force) _ui.update { it.copy(providers = ProviderOptionsUi.Loading) }
            val loaded = when (val result = setup.loadOptions(force)) {
                is ProviderSetup.OptionsResult.Loaded -> ProviderOptionsUi.Loaded(result.options, result.source)
                is ProviderSetup.OptionsResult.CatalogFailed -> ProviderOptionsUi.Loaded(setup.bundledOptions(), ProviderSource.Bundled, result.message)
            }
            _ui.update { current ->
                val keep = current.selectedProviderId?.takeIf { id -> loaded.options.any { it.id == id } }
                current.copy(providers = loaded, selectedProviderId = keep)
            }
        }
    }

    /** "Try again" next to "Failed to load providers: %1$s". */
    fun retryCatalog() = loadOptions(force = true)

    // ---- the two cards -----------------------------------------------------------------------------

    fun selectMethod(method: SetupMethod) {
        _ui.update { it.copy(method = method, clineKeyFormExpanded = if (method == SetupMethod.ApiKey) false else it.clineKeyFormExpanded) }
    }

    fun toggleClineKeyForm() {
        if (_ui.value.clineBusy) return
        _ui.update { it.copy(clineKeyFormExpanded = !it.clineKeyFormExpanded, clineKeyError = null) }
    }

    /** "Sign in": `cline auth cline` in a foreground Termux session, Termux brought to the front. */
    fun signIn() {
        val state = _ui.value
        if (!state.kernelUsable || state.clineBusy) return
        attempt += 1
        _ui.update { it.copy(cline = ClineSignInUi.Idle) }
        signIn.start()
    }

    /** "Cancel" while waiting. */
    fun cancelSignIn() {
        attempt += 1
        signIn.cancel()
    }

    /** "Open Termux" in the waiting state. */
    fun openTermux(): Boolean = signIn.openTermux()

    /** Back while a sign-in waits cancels it first (C.3); true = consumed. */
    fun onBack(): Boolean {
        if (_ui.value.cline !is ClineSignInUi.Waiting) return false
        cancelSignIn()
        return true
    }

    /** "Connect" on "Use a Cline API key". [apiKey] is passed through, never kept. */
    fun submitClineKey(apiKey: String) {
        if (!_ui.value.canSubmitClineKey(apiKey)) return
        _ui.update { it.copy(clineKeySaving = true, clineKeyError = null) }
        signIn.submitApiKey(apiKey.trim())
    }

    private fun onSignInState(state: SignInState) {
        when (state) {
            SignInState.Idle -> _ui.update { it.copy(clineKeySaving = false, cline = if (it.cline is ClineSignInUi.Waiting) ClineSignInUi.Idle else it.cline) }
            is SignInState.WaitingForTermux -> _ui.update { it.copy(cline = ClineSignInUi.Waiting(state.startedAtMs, attempt)) }
            is SignInState.SavingKey -> _ui.update { it.copy(clineKeySaving = true) }
            is SignInState.Failed -> _ui.update {
                when (state.kind) {
                    SignInState.Failed.Kind.SignIn -> it.copy(cline = ClineSignInUi.Error(signInFailureText(state.failure)), clineKeySaving = false)
                    SignInState.Failed.Kind.ApiKey -> it.copy(clineKeyError = keyFailureText(state.failure), clineKeySaving = false)
                }
            }
            is SignInState.AlreadySignedIn -> {
                _ui.update { it.copy(cline = ClineSignInUi.SignedIn(state.displayName), clineKeySaving = false) }
                signIn.acknowledge()
                refreshPresence()
            }
            is SignInState.Succeeded -> {
                _ui.update { it.copy(clineKeySaving = false, cline = ClineSignInUi.Idle) }
                signIn.acknowledge()
                refreshPresence()
                if (state.providerId == ClineSignInCoordinator.CLINE) {
                    viewModelScope.launch {
                        _events.send(ConnectEvent.ClearKeys)
                        _events.send(ConnectEvent.ToDone(OnboardingConnection.Cline(unverified = state.viaApiKey && !state.verified)))
                    }
                }
            }
            is SignInState.Blocked -> {
                _ui.update { it.copy(kernelUsable = false, kernelFocus = focusFor(state.error), clineKeySaving = false, cline = ClineSignInUi.Idle) }
                signIn.acknowledge()
            }
        }
    }

    // ---- "Use your own API key" -----------------------------------------------------------------------

    /** Choosing a provider clears the save error, prefills "Model" (unless typed) and toggles "Base URL". */
    fun selectProvider(id: String) {
        val option = _ui.value.options.firstOrNull { it.id == id } ?: return
        _ui.update {
            it.copy(
                selectedProviderId = id,
                saveError = null,
                modelId = if (it.modelEdited) it.modelId else option.defaultModelId.orEmpty(),
                touched = it.touched - ConnectField.BaseUrl,
            )
        }
    }

    fun setModelId(value: String) {
        _ui.update { it.copy(modelId = value, modelEdited = true, touched = it.touched + ConnectField.Model, saveError = null) }
    }

    fun setBaseUrl(value: String) {
        _ui.update { it.copy(baseUrl = value, touched = it.touched + ConnectField.BaseUrl, saveError = null) }
    }

    /** A field lost focus: its error may show from now on. */
    fun markTouched(field: ConnectField) {
        _ui.update { it.copy(touched = it.touched + field) }
    }

    /** "Connect": `cline auth --provider …`; success = exit 0, then Done with the provider's name. */
    fun connect(apiKey: String) {
        val state = _ui.value
        if (!state.canSubmitKey(apiKey)) {
            _ui.update { it.copy(touched = it.touched + ConnectField.Model + ConnectField.BaseUrl) }
            return
        }
        val option = state.selectedOption ?: return
        val model = state.modelId.trim()
        val baseUrl = state.baseUrl.trim().takeIf { option.needsBaseUrl && it.isNotEmpty() }
        _ui.update { it.copy(saving = true, saveError = null) }
        viewModelScope.launch {
            val result = setup.saveProvider(option.id, apiKey.trim(), model, baseUrl)
            when (result) {
                ProviderSetup.SaveResult.Saved -> {
                    setup.rememberSelection(option.id, model)
                    _ui.update { it.copy(saving = false) }
                    _events.send(ConnectEvent.ClearKeys)
                    refreshPresence()
                    _events.send(ConnectEvent.ToDone(OnboardingConnection.Provider(option.name)))
                }
                is ProviderSetup.SaveResult.Failed -> _ui.update {
                    it.copy(saving = false, saveError = UiText.Res(R.string.connect_save_failed, UiText.Raw(result.detail)))
                }
                ProviderSetup.SaveResult.TimedOut -> _ui.update {
                    it.copy(saving = false, saveError = UiText.Res(R.string.connect_save_failed, UiText.Res(R.string.connect_save_timeout_detail)))
                }
                is ProviderSetup.SaveResult.Unavailable -> _ui.update {
                    it.copy(saving = false, kernelUsable = false, kernelFocus = focusFor(result.error))
                }
            }
        }
    }

    /** "Continue" on an already-connected card (C.6). */
    fun continueConnected(method: SetupMethod) {
        val state = _ui.value
        val connection = when (method) {
            SetupMethod.Cline -> OnboardingConnection.Cline()
            SetupMethod.ApiKey -> OnboardingConnection.Provider(state.connectedProviderName ?: return)
        }
        viewModelScope.launch { _events.send(ConnectEvent.ToDone(connection)) }
    }

    override fun onCleared() {
        // A failure shown here should not greet the next surface that observes the sign-in.
        if (signIn.state.value is SignInState.Failed) signIn.acknowledge()
    }

    companion object {
        /** The repair row for a blocked kernel, or null when P1–P3 pass. */
        fun focusFor(state: KernelState, permissionGranted: Boolean): CheckId? = when (state) {
            is KernelState.TermuxMissing, is KernelState.TermuxTooOld -> CheckId.Termux
            KernelState.PermissionMissing -> CheckId.Permission
            is KernelState.ExternalAppsBlocked -> CheckId.ExternalApps
            KernelState.TermuxNotSetUp, KernelState.PortMissing, is KernelState.PortTooOld -> CheckId.Kernel
            else -> if (permissionGranted) null else CheckId.Permission
        }

        fun focusFor(error: TermuxUnavailable): CheckId = when (error) {
            is TermuxUnavailable.NotInstalled -> CheckId.Termux
            is TermuxUnavailable.PermissionMissing -> CheckId.Permission
            is TermuxUnavailable.ExternalAppsBlocked -> CheckId.ExternalApps
            else -> CheckId.Kernel
        }

        /** "Sign in failed: %1$s" with the C.5 details. */
        fun signInFailureText(failure: SignInFailure): UiText = UiText.Res(R.string.connect_sign_in_failed, detail(failure))

        /** "Failed to save API key: %1$s" (desktop `connectWithClineApiKey`). */
        fun keyFailureText(failure: SignInFailure): UiText = UiText.Res(
            R.string.connect_cline_key_failed,
            when (failure) {
                is SignInFailure.KeyNotVerified -> UiText.Res(R.string.connect_cline_key_unverified_detail, failure.error)
                else -> detail(failure)
            },
        )

        private fun detail(failure: SignInFailure): UiText = when (failure) {
            is SignInFailure.ExitCode -> UiText.Res(R.string.connect_sign_in_exit, failure.code)
            SignInFailure.TimedOut -> UiText.Res(R.string.connect_sign_in_timeout)
            is SignInFailure.Termux -> UiText.Raw(failure.errmsg)
            is SignInFailure.KeyNotVerified -> UiText.Res(R.string.connect_cline_key_unverified_detail, failure.error)
            is SignInFailure.KeySaveFailed -> UiText.Raw(failure.message)
            SignInFailure.TermuxDidNotAnswer -> UiText.Res(R.string.connect_save_timeout_detail)
        }
    }
}
