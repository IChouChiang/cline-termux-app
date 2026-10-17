package dev.cline.termux.ui.account

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperLease
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.account.AccountOrganization
import dev.cline.termux.data.account.AccountOverview
import dev.cline.termux.data.account.AccountRepository
import dev.cline.termux.data.account.AccountResult
import dev.cline.termux.data.account.ClineSignInCoordinator
import dev.cline.termux.data.account.PaymentTransaction
import dev.cline.termux.data.account.SignInOrigin
import dev.cline.termux.data.account.SignInState
import dev.cline.termux.data.account.UsageTransaction
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The account operations the screen drives (`AccountRepository`, customize-and-rest.md §5.8). */
interface AccountSource {
    suspend fun overview(): AccountResult<AccountOverview>

    /** Null when a newer request superseded this one (desktop `usageGenerationRef`). */
    suspend fun usage(activeOrganization: AccountOrganization?): AccountResult<List<UsageTransaction>>?
    suspend fun billing(): AccountResult<List<PaymentTransaction>>
    suspend fun switchAccount(organizationId: String?): AccountResult<Unit>
    suspend fun signOut(): AccountResult<Unit>
    suspend fun signedInWithoutHelper(): Outcome<Boolean>

    companion object {
        fun of(repository: AccountRepository): AccountSource = object : AccountSource {
            override suspend fun overview() = repository.overview()
            override suspend fun usage(activeOrganization: AccountOrganization?) = repository.usage(activeOrganization)
            override suspend fun billing() = repository.billing()
            override suspend fun switchAccount(organizationId: String?) = repository.switchAccount(organizationId)
            override suspend fun signOut() = repository.signOut()
            override suspend fun signedInWithoutHelper() = repository.signedInWithoutHelper()
        }
    }
}

/** The SP S7 sign-in flow as the Account card uses it (the waiting state renders inline, S7.1). */
interface SignInPort {
    val state: StateFlow<SignInState>
    fun start()
    fun openTermux(): Boolean
    fun cancel()
    fun acknowledge()

    companion object {
        fun of(coordinator: ClineSignInCoordinator): SignInPort = object : SignInPort {
            override val state: StateFlow<SignInState> get() = coordinator.state
            override fun start() = coordinator.start(AccountRepository.CLINE_PROVIDER_ID, SignInOrigin.Account)
            override fun openTermux(): Boolean = coordinator.openTermux()
            override fun cancel() = coordinator.cancel()
            override fun acknowledge() = coordinator.acknowledge()
        }
    }
}

/**
 * C8 Account (customize-and-rest.md §5): the overview (`fetchMe` first; nothing else when signed
 * out), lazily loaded Usage and Billing, one organisation switch at a time, sign-out, the S7
 * sign-in started from the signed-out card, and the reduced page while the helper is down.
 * A helper that dies mid-call is re-acquired once, then the error block shows (§5.9).
 */
class AccountViewModel(
    private val savedState: SavedStateHandle,
    private val account: AccountSource,
    private val helper: HelperLease,
    private val signIn: SignInPort,
    kernelGate: StateFlow<KernelGate>,
    /** Outlives the ViewModel so the lease release in [onCleared] still runs. */
    private val releaseScope: CoroutineScope,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    /** The user's "Use 24-hour format" setting for the usage times, read per load. */
    private val is24Hour: () -> Boolean = { false },
) : ViewModel() {
    private val owner = "account@" + System.identityHashCode(this).toString(16)

    private val _ui = MutableStateFlow(AccountUiState(tab = AccountTab.fromWire(savedState.get<String>(KEY_TAB)), kernel = kernelGate.value))
    val ui: StateFlow<AccountUiState> = _ui.asStateFlow()

    private val _events = Channel<AccountEvent>(Channel.BUFFERED)
    val events: Flow<AccountEvent> = _events.receiveAsFlow()

    private var overviewJob: Job? = null
    private var usageJob: Job? = null
    private var billingJob: Job? = null
    private var fallbackJob: Job? = null

    /** A helper loss was answered with one re-acquire already (§5.9). */
    private var reacquiredOnce = false

    /** Reload the overview (and the current tab) when the helper is Ready again. */
    private var reloadOnReady = false

    /** This screen started the current sign-in, so its `AlreadySignedIn` is ours to consume. */
    private var signInStartedHere = false

    init {
        viewModelScope.launch { helper.acquire(owner) }
        viewModelScope.launch { helper.state.collect(::onHelper) }
        viewModelScope.launch { signIn.state.collect(::onSignIn) }
        viewModelScope.launch { kernelGate.collect { gate -> _ui.update { it.copy(kernel = gate) } } }
    }

    // ---- intents --------------------------------------------------------------------------

    fun selectTab(tab: AccountTab) {
        savedState[KEY_TAB] = tab.wire
        _ui.update { it.copy(tab = tab) }
        if (_ui.value.signedIn != SignedIn.Yes) return
        when (tab) {
            AccountTab.Overview -> Unit
            AccountTab.Usage -> if (_ui.value.usage is Loadable.Idle) loadUsage()
            AccountTab.Billing -> if (_ui.value.billing is Loadable.Idle) loadBilling()
        }
    }

    /** "Retry" in the error block of the current tab (or the overview when that failed). */
    fun retry() {
        val state = _ui.value
        when {
            state.overview !is Loadable.Ready -> loadOverview()
            state.tab == AccountTab.Usage -> loadUsage()
            state.tab == AccountTab.Billing -> loadBilling()
            else -> loadOverview()
        }
    }

    /** The unavailable card's "Retry": start the helper again. */
    fun retryHelper() {
        reacquiredOnce = false
        viewModelScope.launch { helper.retry() }
    }

    /** Pull to refresh *(Android)*: reloads the current tab, or re-checks without the helper. */
    fun refresh() {
        val state = _ui.value
        if (state.refreshing) return
        if (state.helperUnavailable != null) {
            retryHelper()
            checkWithoutHelper()
            return
        }
        _ui.update { it.copy(refreshing = true) }
        viewModelScope.launch {
            val job = when {
                state.signedIn != SignedIn.Yes || state.tab == AccountTab.Overview -> loadOverview()
                state.tab == AccountTab.Usage -> loadUsage()
                else -> loadBilling()
            }
            job.join()
            _ui.update { it.copy(refreshing = false) }
        }
    }

    /** Organisation row tap: one switch at a time; tapping the active row does nothing (desktop `disabled`). */
    fun switchAccount(row: AccountRow) {
        val state = _ui.value
        if (state.switchingTo != null || row.active || state.signingOut) return
        _ui.update { it.copy(switchingTo = row.switchKey) }
        viewModelScope.launch {
            when (val result = account.switchAccount(row.id)) {
                is AccountResult.Ok -> {
                    _ui.update { it.copy(usage = Loadable.Idle) }
                    loadOverview().join()
                    _events.trySend(AccountEvent.Switched(row.name))
                    if (_ui.value.tab == AccountTab.Usage && _ui.value.signedIn == SignedIn.Yes) loadUsage()
                }
                is AccountResult.SignedOut -> resetSignedOut()
                is AccountResult.OlderHelper -> _events.trySend(AccountEvent.Failed(AccountError.OlderHelper))
                is AccountResult.Failed -> {
                    log("switch", result.failure)
                    _events.trySend(AccountEvent.Failed(AccountError.Failure(result.failure)))
                }
            }
            _ui.update { it.copy(switchingTo = null) }
        }
    }

    /** After the confirm dialog: the desktop Account sign-out payload (§5.8). */
    fun signOut() {
        if (_ui.value.signingOut) return
        _ui.update { it.copy(signingOut = true) }
        viewModelScope.launch {
            when (val result = account.signOut()) {
                is AccountResult.Ok, is AccountResult.SignedOut -> {
                    resetSignedOut()
                    _events.trySend(AccountEvent.SignedOut)
                }
                is AccountResult.OlderHelper -> _events.trySend(AccountEvent.Failed(AccountError.OlderHelper))
                is AccountResult.Failed -> {
                    log("sign-out", result.failure)
                    _events.trySend(AccountEvent.Failed(AccountError.Failure(result.failure)))
                }
            }
            _ui.update { it.copy(signingOut = false) }
        }
    }

    /** Signed-out card "Sign in": SP S7 in Termux; a previous failure or block is cleared first. */
    fun startSignIn() {
        signIn.acknowledge()
        signInStartedHere = true
        signIn.start()
    }

    fun openTermux(): Boolean = signIn.openTermux()

    fun cancelSignIn() = signIn.cancel()

    /** A block card or failure line was acted on: back to the idle card. */
    fun dismissSignInProblem() = signIn.acknowledge()

    // ---- loads ----------------------------------------------------------------------------

    private fun loadOverview(): Job {
        overviewJob?.cancel()
        _ui.update { it.copy(overview = Loadable.Loading) }
        return viewModelScope.launch {
            when (val result = account.overview()) {
                is AccountResult.Ok -> {
                    reacquiredOnce = false
                    _ui.update { it.copy(signedIn = SignedIn.Yes, overview = Loadable.Ready(AccountProjection.overview(result.value, zone()))) }
                    when (_ui.value.tab) {
                        AccountTab.Usage -> if (_ui.value.usage is Loadable.Idle) loadUsage()
                        AccountTab.Billing -> if (_ui.value.billing is Loadable.Idle) loadBilling()
                        AccountTab.Overview -> Unit
                    }
                }
                is AccountResult.SignedOut -> resetSignedOut()
                is AccountResult.OlderHelper -> _ui.update { it.copy(overview = Loadable.Error(AccountError.OlderHelper)) }
                is AccountResult.Failed -> if (!reacquireOnHelperLoss(result.failure)) {
                    log("overview", result.failure)
                    _ui.update { it.copy(overview = Loadable.Error(AccountError.Failure(result.failure))) }
                }
            }
        }.also { overviewJob = it }
    }

    private fun loadUsage(): Job {
        usageJob?.cancel()
        _ui.update { it.copy(usage = Loadable.Loading) }
        val organization = _ui.value.overview.valueOrNull?.activeOrganization
        return viewModelScope.launch {
            when (val result = account.usage(organization) ?: return@launch) {
                is AccountResult.Ok -> _ui.update { it.copy(usage = Loadable.Ready(AccountProjection.usage(result.value, zone(), is24Hour()))) }
                is AccountResult.SignedOut -> resetSignedOut()
                is AccountResult.OlderHelper -> _ui.update { it.copy(usage = Loadable.Error(AccountError.OlderHelper)) }
                is AccountResult.Failed -> if (!reacquireOnHelperLoss(result.failure)) {
                    log("usage", result.failure)
                    _ui.update { it.copy(usage = Loadable.Error(AccountError.Failure(result.failure))) }
                }
            }
        }.also { usageJob = it }
    }

    private fun loadBilling(): Job {
        billingJob?.cancel()
        _ui.update { it.copy(billing = Loadable.Loading) }
        return viewModelScope.launch {
            when (val result = account.billing()) {
                is AccountResult.Ok -> _ui.update { it.copy(billing = Loadable.Ready(AccountProjection.payments(result.value, zone()))) }
                is AccountResult.SignedOut -> resetSignedOut()
                is AccountResult.OlderHelper -> _ui.update { it.copy(billing = Loadable.Error(AccountError.OlderHelper)) }
                is AccountResult.Failed -> if (!reacquireOnHelperLoss(result.failure)) {
                    log("billing", result.failure)
                    _ui.update { it.copy(billing = Loadable.Error(AccountError.Failure(result.failure))) }
                }
            }
        }.also { billingJob = it }
    }

    /** §5.9 "Helper dies mid-tab": re-acquire once and reload on Ready; true when that was done. */
    private suspend fun reacquireOnHelperLoss(failure: RepoFailure): Boolean {
        val lost = failure is RepoFailure.HelperLost || failure is RepoFailure.HelperDown || failure is RepoFailure.HelperTimeout
        if (!lost || reacquiredOnce) return false
        reacquiredOnce = true
        reloadOnReady = true
        helper.retry()
        return true
    }

    /** Without the helper: signed in ⇔ `providers.json` carries an OAuth token for `cline`. */
    private fun checkWithoutHelper() {
        fallbackJob?.cancel()
        _ui.update { it.copy(fallbackSignedIn = Loadable.Loading) }
        fallbackJob = viewModelScope.launch {
            val value = when (val out = account.signedInWithoutHelper()) {
                is Outcome.Ok -> Loadable.Ready(out.value)
                is Outcome.Failed -> {
                    log("providers", out.failure)
                    Loadable.Error(AccountError.Failure(out.failure))
                }
            }
            _ui.update { it.copy(fallbackSignedIn = value) }
        }
    }

    private fun resetSignedOut() {
        overviewJob?.cancel()
        usageJob?.cancel()
        billingJob?.cancel()
        savedState[KEY_TAB] = AccountTab.Overview.wire
        _ui.update {
            it.copy(
                tab = AccountTab.Overview,
                signedIn = SignedIn.No,
                overview = Loadable.Idle,
                usage = Loadable.Idle,
                billing = Loadable.Idle,
                switchingTo = null,
            )
        }
    }

    // ---- inputs ---------------------------------------------------------------------------

    private fun onHelper(state: HelperState) {
        _ui.update { it.copy(helper = state) }
        when (state) {
            HelperState.Ready -> {
                fallbackJob?.cancel()
                val current = _ui.value
                val needsOverview = reloadOnReady || current.overview is Loadable.Idle && current.signedIn != SignedIn.No ||
                    current.overview is Loadable.Error
                reloadOnReady = false
                if (needsOverview) loadOverview()
            }
            is HelperState.Unavailable -> {
                overviewJob?.cancel()
                if (state.reason !in AccountUiState.BLOCKING) checkWithoutHelper()
            }
            is HelperState.Starting, HelperState.Idle -> Unit
        }
    }

    private fun onSignIn(state: SignInState) {
        _ui.update { it.copy(signIn = state) }
        val ours = when (state) {
            is SignInState.Succeeded -> state.origin == SignInOrigin.Account || signInStartedHere
            is SignInState.AlreadySignedIn -> signInStartedHere
            else -> false
        }
        if (!ours) return
        signInStartedHere = false
        signIn.acknowledge()
        // S7 → Account: reload the overview and show it (desktop `loadOverview()` + `setActiveTab("overview")`).
        selectTab(AccountTab.Overview)
        if (_ui.value.helper is HelperState.Ready) loadOverview() else checkWithoutHelper()
    }

    override fun onCleared() {
        releaseScope.launch { helper.release(owner) }
    }

    private fun log(what: String, failure: RepoFailure) {
        runCatching { Log.w(TAG, "$what failed: ${failure.logReason}") }
    }

    companion object {
        const val KEY_TAB = "tab"
        private const val TAG = "Account"

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AccountViewModel(
                    savedState = createSavedStateHandle(),
                    account = AccountSource.of(graph.account),
                    helper = HelperLease.of(graph.settingsHelper),
                    signIn = SignInPort.of(graph.signIn),
                    kernelGate = graph.kernel.gate,
                    releaseScope = graph.appScope,
                    is24Hour = graph::is24HourClock,
                )
            }
        }
    }
}
