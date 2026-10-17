package dev.cline.termux.ui

import dev.cline.termux.data.about.WhatsNewRepository
import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.R
import dev.cline.termux.core.kernel.CheckId
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.KernelCoordinator.RestartOutcome
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.core.kernel.toGate
import dev.cline.termux.ui.kernel.HubUpdateDialogState
import dev.cline.termux.ui.kernel.HubUpdatePresenter
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.kernel.KernelBannerModel
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.navigation.DeepLink
import dev.cline.termux.ui.navigation.DeepLinks
import dev.cline.termux.ui.navigation.KernelInstallTermux
import dev.cline.termux.ui.navigation.KernelReliability
import dev.cline.termux.ui.navigation.KernelRepair
import dev.cline.termux.ui.navigation.RootRoute
import dev.cline.termux.ui.navigation.ShellLayout
import dev.cline.termux.ui.navigation.SupportingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * The kernel as the shell sees it: the coordinator's state and gate plus the two calls the
 * shell's own surfaces make (KernelBanner / composer primary, the E.9 restart). An interface
 * so the ViewModel is tested with a fake.
 */
interface ShellKernel {
    val state: StateFlow<KernelState>
    val gate: StateFlow<KernelGate>

    /** "Start kernel" / "Try again" (`startIfDown = true`), "Connect" (`false`); always user-initiated here. */
    suspend fun ensureConnected(startIfDown: Boolean, userInitiated: Boolean)

    /** The E.9 restart from the Hub update dialog. */
    suspend fun restart(forced: Boolean): RestartOutcome

    companion object {
        fun of(coordinator: KernelCoordinator): ShellKernel = object : ShellKernel {
            override val state: StateFlow<KernelState> get() = coordinator.state
            override val gate: StateFlow<KernelGate> get() = coordinator.gate
            override suspend fun ensureConnected(startIfDown: Boolean, userInitiated: Boolean) {
                coordinator.ensureConnected(startIfDown = startIfDown, userInitiated = userInitiated)
            }
            override suspend fun restart(forced: Boolean): RestartOutcome = coordinator.restart(forced)
        }
    }
}

/**
 * The What's-new slot (CR §8.4): the About lane's `WhatsNewRepository` implements it. The
 * shell asks on every `ON_START` and shows the dialog on Home; closing it marks it seen.
 */
interface WhatsNewTrigger {
    /** The id of the entry to show now (`latest().id != whatsNew.seenId`), or null. */
    suspend fun pendingId(): String?

    /** Writes `whatsNew.seenId` (close, back, scrim, "Get Started", "See all changes"). */
    suspend fun markSeen(id: String)

    /** No entries bundled: never shows. */
    object None : WhatsNewTrigger {
        override suspend fun pendingId(): String? = null
        override suspend fun markSeen(id: String) = Unit
    }
}

/** The About lane's repository as the shell's trigger: onboarded users only, unseen latest entry. */
fun WhatsNewRepository.asTrigger(): WhatsNewTrigger = object : WhatsNewTrigger {
    override suspend fun pendingId(): String? = pendingForShell()?.id
    override suspend fun markSeen(id: String) = this@asTrigger.markSeen(id)
}

/**
 * The Kernel status sheet request (KS E.3); [diagnostics] scrolls it to E.6. [restart] is a
 * one-shot "Restart kernel" request (A4: the stall notice and the Stop snackbars): the sheet
 * starts its E.7 restart, confirm included, once per value. Never persisted, so a recreated
 * shell never restarts the kernel by itself.
 */
@Immutable
data class KernelSheetRequest(val diagnostics: Boolean, val restart: Long? = null)

/** One-off shell effects the composable performs. */
sealed interface ShellEvent {
    /** One app-wide snackbar (DS §4.1 #17). */
    data class Snackbar(@param:StringRes val text: Int) : ShellEvent

    /** Push a root screen over the shell (pushed repair, reliability). */
    data class OpenRoot(val route: RootRoute) : ShellEvent
}

/** docs/07 §6.5 `ShellUiState(gate, windowClass, banner, drawerOpen, pendingDeepLink)` and the shell's other surfaces. */
@Immutable
data class ShellUiState(
    val gate: KernelGate = INITIAL_GATE,
    /** The Hub port for the KernelChip line ("Cline Hub @25463"); null until a Hub is known. */
    val hubPort: Int? = null,
    val layout: ShellLayout = ShellLayout.PhonePortrait,
    /** The KernelBanner for page hosts (no composer: the trailing action is F.2's primary). */
    val banner: KernelBannerModel? = null,
    val drawerOpen: Boolean = false,
    /** A drawer row is in rename mode: the drawer drag gesture is off (SS §2.7). */
    val drawerRenaming: Boolean = false,
    /** Expanded windows: the user collapsed the sessions list pane with the rail `menu` (SS §2.9.1). */
    val listPaneCollapsed: Boolean = false,
    val kernelSheet: KernelSheetRequest? = null,
    /** The kernel action whose call is running ("Starting…" on its button). */
    val kernelActionInFlight: KernelAction? = null,
    val hubUpdateDialog: HubUpdateDialogState? = null,
    /** Non-null while the What's-new dialog should show on Home. */
    val whatsNewId: String? = null,
    /** The chat's supporting pane content at ≥ 1200dp. */
    val supporting: SupportingContent? = null,
    /** A deep link waiting for the shell NavHost (docs/07 §6.4). */
    val pendingDeepLink: DeepLink? = null,
) {
    companion object {
        val INITIAL_GATE: KernelGate = KernelState.Unknown.toGate(0L)
    }
}

/**
 * The shell's ViewModel (docs/07 §6.1, §6.5), scoped to the `shell` back-stack entry.
 *
 * The coordinator is resolved through [kernel] on [work] (never on Main): building it builds
 * the Hub client, whose lazy init still reads DataStore blocking (docs/07 §8 "the two existing
 * `runBlocking` reads move behind the splash").
 */
class AppShellViewModel(
    private val savedState: SavedStateHandle,
    private val kernel: suspend () -> ShellKernel,
    private val whatsNew: WhatsNewTrigger = WhatsNewTrigger.None,
    private val work: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val _ui = MutableStateFlow(
        ShellUiState(
            listPaneCollapsed = savedState[KEY_LIST_COLLAPSED] ?: false,
            kernelSheet = savedState.get<Boolean>(KEY_KERNEL_SHEET)?.let(::KernelSheetRequest),
            supporting = savedState.get<String>(KEY_SUPPORTING)?.let(::decodeSupporting),
            pendingDeepLink = DeepLinks.decode(savedState[KEY_DEEP_LINK]),
        ),
    )
    val ui: StateFlow<ShellUiState> = _ui.asStateFlow()

    private val _replacement = MutableStateFlow<RootRoute?>(null)

    /**
     * A root screen that must replace the shell (KS A.3 blocking repair, B.3 Install Termux):
     * observed by `RootNavHost` while the shell is on screen.
     */
    val replacement: StateFlow<RootRoute?> = _replacement.asStateFlow()

    private val _events = Channel<ShellEvent>(Channel.BUFFERED)
    val events: Flow<ShellEvent> = _events.receiveAsFlow()

    private val _composerFocus = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** "New session" while Home is shown (HC §2.9): the Home composer requests focus. */
    val composerFocusRequests: SharedFlow<Unit> = _composerFocus.asSharedFlow()

    private val _dockedSearch = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Ctrl+K / search on expanded windows: the list pane's docked search bar takes focus (SS §4.4). */
    val dockedSearchRequests: SharedFlow<Unit> = _dockedSearch.asSharedFlow()

    private val hubUpdate = HubUpdatePresenter(viewModelScope) { resolvedKernel().restart(forced = false) }

    @Volatile private var kernelInstance: ShellKernel? = null

    /** Source of [KernelSheetRequest.restart]. */
    private var restartRequests = 0L

    init {
        viewModelScope.launch {
            val k = resolvedKernel()
            combine(k.state, k.gate) { state, gate -> state to gate }.collect { (state, gate) ->
                hubUpdate.onKernelState(state)
                _replacement.value = replacementFor(state, gate)
                _ui.update {
                    it.copy(
                        gate = gate,
                        // The last known port stays for the variants that still name it (F.2 Disconnected).
                        hubPort = state.hubInfo?.port ?: it.hubPort,
                        banner = KernelCopy.banner(gate, hasComposer = false),
                    )
                }
            }
        }
        viewModelScope.launch { hubUpdate.dialog.collect { dialog -> _ui.update { it.copy(hubUpdateDialog = dialog) } } }
        viewModelScope.launch { hubUpdate.restarted.drop(1).collect { _events.send(ShellEvent.Snackbar(R.string.kernel_restarted_snackbar)) } }
    }

    private suspend fun resolvedKernel(): ShellKernel =
        kernelInstance ?: withContext(work) { kernel() }.also { kernelInstance = it }

    // ---------------------------------------------------------------- lifecycle

    /** Activity `ON_START`: a dismissed Hub update dialog returns (E.9); What's new is checked (CR §8.4). */
    fun onStart() {
        hubUpdate.onStart()
        viewModelScope.launch {
            val id = runCatching { whatsNew.pendingId() }.getOrNull()
            if (id != null) _ui.update { it.copy(whatsNewId = id) }
        }
    }

    fun onLayout(layout: ShellLayout) {
        _ui.update { it.copy(layout = layout) }
    }

    // ---------------------------------------------------------------- kernel surfaces

    /** A KernelBanner / KernelChip / composer-row / panel action (KS F.2). */
    fun onKernelAction(action: KernelAction) {
        when (action) {
            KernelAction.StartKernel, KernelAction.TryAgain -> runKernelCall(action) { ensureConnected(startIfDown = true, userInitiated = true) }
            KernelAction.Connect -> runKernelCall(action) { ensureConnected(startIfDown = false, userInitiated = true) }
            // E.9 while the Hub speaks another protocol; otherwise the status sheet's E.7 restart (A4).
            KernelAction.RestartKernel -> if (isProtocolMismatch()) hubUpdate.reopen() else openKernelSheet(diagnostics = false, restart = true)
            KernelAction.KernelStatus, KernelAction.Details -> openKernelSheet(diagnostics = false)
            KernelAction.KernelStatusDiagnostics -> openKernelSheet(diagnostics = true)
            KernelAction.WhyDidThisHappen -> _events.trySend(ShellEvent.OpenRoot(KernelReliability))
            // Degraded mode is RUN_COMMAND revoked while the Hub stays reachable (KS §0.4).
            KernelAction.Fix -> _events.trySend(ShellEvent.OpenRoot(KernelRepair.of(CheckId.Permission, pushed = true)))
        }
    }

    private fun runKernelCall(action: KernelAction, call: suspend ShellKernel.() -> Unit) {
        if (_ui.value.kernelActionInFlight != null) return
        _ui.update { it.copy(kernelActionInFlight = action) }
        viewModelScope.launch {
            try {
                resolvedKernel().call()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The coordinator reports every outcome through its state; the gate shows it.
            } finally {
                _ui.update { it.copy(kernelActionInFlight = null) }
            }
        }
    }

    fun openKernelSheet(diagnostics: Boolean = false, restart: Boolean = false) {
        savedState[KEY_KERNEL_SHEET] = diagnostics
        _ui.update { it.copy(kernelSheet = KernelSheetRequest(diagnostics, if (restart) ++restartRequests else null)) }
    }

    private fun isProtocolMismatch(): Boolean =
        (_ui.value.gate as? KernelGate.Down)?.variant == KernelGate.DownVariant.Protocol

    fun dismissKernelSheet() {
        savedState.remove<Boolean>(KEY_KERNEL_SHEET)
        _ui.update { it.copy(kernelSheet = null) }
    }

    /** E.9 "Restart kernel" / "Try again". */
    fun confirmHubUpdate() {
        hubUpdate.confirm()
    }

    /** E.9 "Later" / back. */
    fun dismissHubUpdate() = hubUpdate.later()

    // ---------------------------------------------------------------- drawer and panes

    fun setDrawerOpen(open: Boolean) {
        _ui.update { it.copy(drawerOpen = open, drawerRenaming = if (open) it.drawerRenaming else false) }
    }

    fun setDrawerRenaming(renaming: Boolean) {
        _ui.update { it.copy(drawerRenaming = renaming) }
    }

    /**
     * The rail `menu`. While the chat's supporting pane is open the list pane is hidden for it
     * (CT DS-A1), so the menu closes that pane first and shows the list (the chat never drops
     * below ≈ 764dp, CT A.4).
     */
    fun toggleListPane() {
        if (_ui.value.supporting != null) {
            clearSupporting()
            setListPaneCollapsed(false)
        } else {
            setListPaneCollapsed(!_ui.value.listPaneCollapsed)
        }
    }

    fun setListPaneCollapsed(collapsed: Boolean) {
        savedState[KEY_LIST_COLLAPSED] = collapsed
        _ui.update { it.copy(listPaneCollapsed = collapsed) }
    }

    fun showSupporting(content: SupportingContent) {
        savedState[KEY_SUPPORTING] = json.encodeToString(SupportingContent.serializer(), content)
        _ui.update { it.copy(supporting = content) }
    }

    fun clearSupporting() {
        savedState.remove<String>(KEY_SUPPORTING)
        _ui.update { it.copy(supporting = null) }
    }

    fun requestComposerFocus() {
        _composerFocus.tryEmit(Unit)
    }

    fun requestDockedSearch() {
        _dockedSearch.tryEmit(Unit)
    }

    // ---------------------------------------------------------------- deep links

    /**
     * A link from `MainActivity` (CA §0.6). The status sheet opens at once; a session waits in
     * [ShellUiState.pendingDeepLink] until the shell NavHost exists, then [consumeDeepLink].
     */
    fun offerDeepLink(link: DeepLink) {
        when (link) {
            DeepLink.KernelStatus -> openKernelSheet(diagnostics = false)
            is DeepLink.Session -> {
                savedState[KEY_DEEP_LINK] = DeepLinks.encode(link)
                _ui.update { it.copy(pendingDeepLink = link) }
            }
        }
    }

    /** Takes the pending link (once). */
    fun consumeDeepLink(): DeepLink? {
        val link = _ui.value.pendingDeepLink ?: return null
        savedState.remove<String>(KEY_DEEP_LINK)
        _ui.update { it.copy(pendingDeepLink = null) }
        return link
    }

    // ---------------------------------------------------------------- what's new

    /** Closes the What's-new dialog and marks the entry seen (CR §8.4; "See all changes" also opens About). */
    fun closeWhatsNew() {
        val id = _ui.value.whatsNewId ?: return
        _ui.update { it.copy(whatsNewId = null) }
        viewModelScope.launch { runCatching { whatsNew.markSeen(id) } }
    }

    companion object {
        private const val KEY_LIST_COLLAPSED = "shell.listPaneCollapsed"
        private const val KEY_KERNEL_SHEET = "shell.kernelSheetDiagnostics"
        private const val KEY_SUPPORTING = "shell.supporting"
        private const val KEY_DEEP_LINK = "shell.pendingDeepLink"

        private val json = Json { ignoreUnknownKeys = true }

        private fun decodeSupporting(value: String): SupportingContent? =
            runCatching { json.decodeFromString(SupportingContent.serializer(), value) }.getOrNull()

        /**
         * KS A.3 / B.3: after onboarding, a missing (or disabled) Termux replaces the shell with
         * Install Termux; every other blocked prerequisite replaces it with the blocking repair
         * variant focused on the failing row. Degraded mode (Hub usable) never replaces it.
         */
        fun replacementFor(state: KernelState, gate: KernelGate): RootRoute? = when {
            state is KernelState.TermuxMissing -> KernelInstallTermux
            gate is KernelGate.Blocked -> KernelRepair.of(gate.focus, pushed = false)
            else -> null
        }

        /** Production wiring; the integration agent passes the About lane's [WhatsNewTrigger]. */
        fun factory(graph: AppGraph, whatsNew: WhatsNewTrigger = WhatsNewTrigger.None): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                AppShellViewModel(
                    savedState = createSavedStateHandle(),
                    kernel = { ShellKernel.of(graph.kernel) },
                    whatsNew = whatsNew,
                )
            }
        }
    }
}
