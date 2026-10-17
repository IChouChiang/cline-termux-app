package dev.cline.termux

import android.content.Context
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import dev.cline.termux.data.notifications.AppSeenRequestStore
import dev.cline.termux.data.notifications.KeepAlive
import dev.cline.termux.data.notifications.KeepAlivePolicy
import dev.cline.termux.data.notifications.NotificationPrefs
import dev.cline.termux.data.notifications.asStateSource
import dev.cline.termux.data.notifications.knownSessionStates
import dev.cline.termux.data.session.NotifyFacts
import dev.cline.termux.data.sessions.CreateTitleKeeper
import dev.cline.termux.data.sessions.KnownSessions
import dev.cline.termux.service.ClineNotifier
import dev.cline.termux.service.KeepAliveLauncher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import dev.cline.termux.core.kernel.AppKernelPreferences
import dev.cline.termux.core.kernel.DataStoreHubTokenCache
import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.KernelClock
import dev.cline.termux.core.kernel.KernelCommands
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.OkHttpHubProber
import dev.cline.termux.core.kernel.SessionReattachHook
import dev.cline.termux.core.kernel.SystemKernelClock
import dev.cline.termux.core.kernel.TermuxKernelCommands
import dev.cline.termux.core.kernel.dashboard.DashboardTransport
import dev.cline.termux.core.kernel.dashboard.HelperKernel
import dev.cline.termux.core.kernel.dashboard.HelperUnsupportedStore
import dev.cline.termux.core.kernel.dashboard.OkHttpDashboardTransport
import dev.cline.termux.core.kernel.dashboard.SettingsHelper
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubClientIdentity
import dev.cline.termux.core.kernel.hub.HubCursorStore
import dev.cline.termux.core.kernel.hub.HubEndpoints
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.core.kernel.hub.HubTokenStore
import dev.cline.termux.core.kernel.hub.OkHttpHubTransport
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxRunner
import dev.cline.termux.core.kernel.termux.WorktreeCommands
import dev.cline.termux.core.secure.SecretBox
import dev.cline.termux.core.secure.SecureClipboard
import dev.cline.termux.core.kernel.KernelState
import dev.cline.termux.data.account.ActivityTermuxForeground
import dev.cline.termux.data.account.CatalogInvalidator
import dev.cline.termux.data.account.ClineSignInCoordinator
import dev.cline.termux.data.account.SignInStore
import dev.cline.termux.data.catalog.FallbackProviders
import dev.cline.termux.core.kernel.hub.HubConnectionState
import dev.cline.termux.data.about.WhatsNewRepository
import dev.cline.termux.data.approvals.ApprovalMode
import dev.cline.termux.data.approvals.ApprovalModeRepository
import dev.cline.termux.data.approvals.ApprovalRefresh
import dev.cline.termux.data.approvals.RunBoundaryCoordinator
import kotlinx.coroutines.launch
import dev.cline.termux.data.account.AccountRepository
import dev.cline.termux.data.account.AccountSummaryStore
import dev.cline.termux.data.agents.AgentsRepository
import dev.cline.termux.data.composer.InputHistoryRepository
import dev.cline.termux.data.customize.CustomizeRepository
import dev.cline.termux.data.customize.HelperChannel
import dev.cline.termux.data.customize.McpRepository
import dev.cline.termux.data.customize.asChannel
import dev.cline.termux.data.diagnostics.DiagnosticsBuilder
import dev.cline.termux.data.marketplace.MarketplaceRepository
import dev.cline.termux.data.schedules.SchedulesRepository
import dev.cline.termux.data.chat.ProviderAuthResolver
import dev.cline.termux.data.diff.SessionDiffStore
import dev.cline.termux.data.prefs.AppPreferences
import dev.cline.termux.data.tools.SessionStateSource
import dev.cline.termux.data.tools.ToolCallStore
import dev.cline.termux.data.model.ModelSelectionStore
import dev.cline.termux.data.prefs.CatalogCache
import dev.cline.termux.data.providers.CatalogProjection
import dev.cline.termux.data.providers.CatalogProjectionStore
import dev.cline.termux.data.providers.PresenceSource
import dev.cline.termux.data.providers.ProviderCatalogRepository
import dev.cline.termux.data.providers.asCatalogHelper
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.data.workspace.WorkspacePrefs
import dev.cline.termux.data.workspace.WorkspaceStore
import dev.cline.termux.data.session.ClientContributions
import dev.cline.termux.data.session.HubClientSessionHub
import dev.cline.termux.data.session.PreferencesStopNoticeStore
import dev.cline.termux.data.session.PreferencesTurnStore
import dev.cline.termux.data.session.StopNotice
import dev.cline.termux.data.session.CompactionModeSource
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionStore
import dev.cline.termux.data.chat.ForkTitleStrings
import dev.cline.termux.data.sessions.HubClientSessionsHub
import dev.cline.termux.data.sessions.PreferencesSessionsPersistence
import dev.cline.termux.data.sessions.SessionActions
import dev.cline.termux.data.sessions.SessionEventTap
import dev.cline.termux.data.sessions.SessionsRepository
import dev.cline.termux.data.sessions.sessionsListCache
import dev.cline.termux.debug.KernelOverrides
import dev.cline.termux.ui.composer.SpeechAvailability
import dev.cline.termux.ui.composer.toComposerMode
import dev.cline.termux.ui.kernel.KernelDiagnostics
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Process-wide object graph (manual DI: one place, no annotation processing).
 * Everything long-lived hangs off [ClineApp]; screens reach it through
 * `(context.applicationContext as ClineApp).graph`.
 */
class AppGraph(private val context: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Application resources, for ViewModels that format copy outside composition (read per use, so a locale change applies). */
    val resources: android.content.res.Resources get() = context.resources

    /** The user's "Use 24-hour format" setting, for ViewModels that format times of day outside composition (read per use). */
    fun is24HourClock(): Boolean = dev.cline.termux.data.schedules.is24HourClock(context)
    /** Monotonic + wall time for the kernel layer; `clock: java.time.Clock` (docs/07 §1.3) stays free for the data layer. */
    val kernelClock: KernelClock = SystemKernelClock

    /**
     * The one single-threaded dispatcher (docs/07 §8) shared by SessionStore.dispatch,
     * SessionsRepository upserts and InterruptionDetector.
     */
    val kernelDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Wall clock for the data layer (docs/07 §1.3); tests substitute FakeClock. */
    val clock: Clock = Clock.systemUTC()

    /** Keystore-backed encryption for secrets at rest (docs/07 §3.7). */
    val secretBox: SecretBox by lazy { SecretBox() }
    val prefs: AppPreferences by lazy { AppPreferences(context, appScope) }

    /** The RUN_COMMAND bridge (docs/07 §3.3); repositories and the coordinator run [commands] through it. */
    val termux: TermuxExecutor by lazy { KernelOverrides.termuxExecutor(context, TermuxRunner(context)) }

    /** The typed command catalog (docs/07 §3.4); pure builders. */
    val commands: ClineCommands get() = ClineCommands

    val tokenStore: HubTokenStore by lazy { HubTokenStore(context) }
    val cursors: HubCursorStore by lazy { prefs.cursorStore() }
    val hubProber: HubProber by lazy { KernelOverrides.hubProber(context, OkHttpHubProber()) }

    /** The RUN_COMMAND subset the coordinator drives (docs/07 §3.2): [commands] rows run through [termux]. */
    val kernelCommands: KernelCommands by lazy { KernelOverrides.kernelCommands(context, TermuxKernelCommands(termux)) }

    /** One socket per process; the coordinator decides when it connects (docs/07 §3.1). */
    val hubClient: HubClient by lazy {
        HubClient(
            transport = KernelOverrides.hubTransport(context, OkHttpHubTransport()),
            identity = HubClientIdentity(clientId = prefs.clientIdBlocking(), appVersion = BuildInfo.VERSION_NAME),
            scope = appScope,
            cursors = cursors,
            credentials = { kernel.currentCredentials() },
            workspaceRoot = { prefs.lastWorkspace() },
        )
    }

    /**
     * The Hub's loopback port: 25463, or in a debug build the developer override pushed with
     * the token (`scripts/dev-hub.sh reverse`, docs/06). Read once, when the coordinator is
     * built off Main (docs/07 §8); with an override it wins over the discovery record's port.
     */
    val hubPort: Int by lazy { KernelOverrides.hubPort(context) ?: HubEndpoints.DEFAULT_PORT }

    val kernel: KernelCoordinator by lazy {
        KernelCoordinator(
            commands = kernelCommands,
            prober = hubProber,
            tokens = DataStoreHubTokenCache(tokenStore),
            prefs = AppKernelPreferences(prefs),
            hubClient = hubClient,
            scope = appScope,
            clock = kernelClock,
            port = hubPort,
            tokenOverride = { KernelOverrides.hubToken(context) },
            reattach = sessionReattach,
            // Read when a drop is handled, never while [kernel] is built ([keepAlive] reads its gate).
            serviceHeld = { keepAlive.status.value == KeepAlive.Running },
        )
    }

    /**
     * Forwards to [sessionStore] without touching it while [kernel] is being built: the store's
     * own lambdas read [kernel], so it is resolved on the first reconnect instead.
     */
    private val sessionReattach: SessionReattachHook = object : SessionReattachHook {
        override suspend fun openSessions(): Set<String> = sessionStore.openSessions()
        override suspend fun onAttached(sessionId: String) = sessionStore.onAttached(sessionId)
        override suspend fun onSessionGone(sessionId: String) = sessionStore.onSessionGone(sessionId)
    }

    /** Status-sheet diagnostics (KS E.6): `kernel status`, `doctor --json`, the guarded fix. */
    val kernelDiagnostics: KernelDiagnostics by lazy {
        KernelDiagnostics(KernelDiagnostics.Runner.of(kernel, kernelCommands), appScope)
    }

    /** Typed command surface over [hubClient] for the session repositories. */
    val sessionApi: HubSessionApi by lazy { HubSessionApi(hubClient) }

    /**
     * The global compaction mode (G1, A11 row 2) from the kernel's `global-settings.json`, shared
     * with the terminal: read on app start and Settings open, written by Settings, carried by
     * every `session.create` (create, resume, fork, restore).
     */
    val compactionMode: CompactionModeSource by lazy { CompactionModeSource.of(termux, appScope) { clock.millis() } }

    /**
     * Per-session state (docs/07 §4.1): the only collector of `hubClient.events` (D15), on the
     * shared [kernelDispatcher] (§8). Pass it to the coordinator as its `SessionReattachHook`.
     * Provider auth facts (home-chat.md §1.3.5) come from [providerCatalog]: the helper catalog,
     * the persisted projection, the bundled fallback list and `providers.json` presence.
     */
    val sessionStore: SessionStore by lazy {
        SessionStore(
            // The pump hands each event to the sessions list first (docs/07 §4.2 "Side"; D15 keeps one collector),
            // then to the client-contribution responder.
            hub = SessionEventTap(HubClientSessionHub(hubClient, sessionApi)) {
                sessionsRepository.observe(it)
                clientContributions.observe(it)
            },
            turnStore = PreferencesTurnStore(prefs),
            providerAuth = ProviderAuthResolver(providerCatalog),
            scope = appScope,
            dispatcher = kernelDispatcher,
            clock = clock,
            hubLifetimeKey = { kernel.state.value.hubInfo?.takeIf { it.pid != null }?.lifetimePair },
            compaction = { compactionMode.forCreate() },
            // Every resume takes the kernel's global auto-approve (decided A1), as last read: the
            // chat re-reads it before each send, on open and on foreground. No read from the background.
            autoApproveNow = {
                if (processForeground.value) approvalMode.autoApproveForNewSession() else approvalMode.mode.value == ApprovalMode.Auto
            },
            stopNoticeStore = PreferencesStopNoticeStore(prefs),
        ).also { it.start() }
    }

    /**
     * The kernel's global auto-approve (`toolAutoApprove` in `global-settings.json`, decided A1):
     * read through the existing `cat`, written through one Termux command. Every new or resumed
     * session takes its tool policies from here.
     */
    val approvalMode: ApprovalModeRepository by lazy { ApprovalModeRepository(run = { termux.run(it) }, scope = appScope) }

    /** Restarts the app's open sessions at their run boundaries when the global mode changed (decided A1). */
    val runBoundary: RunBoundaryCoordinator by lazy {
        RunBoundaryCoordinator(
            store = sessionStore,
            approvals = approvalMode,
            clientId = { hubClient.clientId },
            scope = appScope,
            foreground = { processForeground.value },
        )
    }

    /**
     * Puts back a session's create-time title when the kernel replaced it with this app's first
     * prompt (trial row 16): one rename through [sessionActions] right after that run, one retry.
     */
    val createTitleKeeper: CreateTitleKeeper by lazy {
        CreateTitleKeeper(
            runEnds = sessionStore.runEnds,
            record = { id -> sessionApi.get(id, includeSnapshot = false) },
            rename = { id, title -> sessionActions.rename(id, title) },
            scope = appScope,
        )
    }

    /**
     * Starts the run-boundary restarts and the "after the Hub reconnects" re-read of the
     * approval mode (auto-approve-control.md §6). Called once off Main at the first process
     * ON_START, with the notifier; the foreground re-read itself is ClineApp's.
     */
    fun startApprovalMode() {
        runBoundary.start()
        appScope.launch {
            var generation: Long? = null
            hubClient.state.collect { state ->
                if (state is HubConnectionState.Connected && state.generation != generation) {
                    generation = state.generation
                    if (processForeground.value) approvalMode.refresh(ApprovalRefresh.HubReconnect)
                }
            }
        }
    }

    /**
     * The responder for the client contributions every create path registers (batch-1 brief §8):
     * mistake-limit dedupe, the five-minute timeout and the user's answer (steer, then reply, on
     * the app scope), `switch_to_act_mode` with the rebuild in act mode and the continuation, and
     * a refusal for anything else targeted at this client. Fed by [sessionStore]'s pump
     * through [SessionEventTap]; app-scoped, so its timers run while no chat screen is open.
     */
    val clientContributions: ClientContributions by lazy {
        ClientContributions(
            clientId = hubClient.clientId,
            hub = { requestId, ok, result, error -> sessionApi.respondToCapability(requestId, ok, result, error) },
            sessions = object : ClientContributions.Sessions {
                override fun state(sessionId: String) = sessionStore.state(sessionId)
                override suspend fun sendSynthetic(request: SendRequest) = sessionStore.sendSynthetic(request)
                override suspend fun restart(sessionId: String, mode: PromptMode) = sessionStore.restart(sessionId, mode).isSuccess
                override suspend fun addStopNotice(notice: StopNotice) = sessionStore.addStopNotice(notice)
            },
            persistMode = { sessionId, mode -> prefs.setComposerMode(mode.toComposerMode(), sessionId) },
            scope = appScope,
            dispatcher = kernelDispatcher,
            now = { clock.millis() },
        )
    }

    /** What the derived per-session stores read (docs/07 §4.3); they never touch the socket (D15). */
    private val sessionStateSource: SessionStateSource by lazy { SessionStateSource.of(sessionStore) }

    /** Per-session diffs for the header chip, Changes and Tool detail (CT §1.3). */
    val diffStores: SessionDiffStore.Registry by lazy { SessionDiffStore.Registry(sessionStateSource, appScope, clock) }

    /** Per-session tool calls with their command terminals (CT §1.4). */
    val toolStores: ToolCallStore.Registry by lazy { ToolCallStore.Registry(sessionStateSource, appScope) }

    /**
     * The sessions list (SS §1, docs/07 §4.4): rows, flags, unread, cache and refresh policy.
     * Fed by [sessionStore]'s pump through [SessionEventTap]; the shell calls `onResume()` /
     * `onStop()` from `ProcessLifecycleOwner` and `onDrawerOpened()` when the drawer opens.
     */
    val sessionsRepository: SessionsRepository by lazy {
        SessionsRepository(
            hub = HubClientSessionsHub(hubClient, sessionApi),
            persistence = PreferencesSessionsPersistence(prefs),
            cache = sessionsListCache(context.filesDir, clock),
            scope = appScope,
            dispatcher = kernelDispatcher,
            clock = clock,
            loadedSession = { id -> sessionStore.state(id) },
        ).also { it.start() }
    }

    /** F1-F5 row actions (rename, pin, fork, delete, resume) over [sessionsRepository] and [sessionStore]. */
    val sessionActions: SessionActions by lazy {
        SessionActions(
            hub = HubClientSessionsHub(hubClient, sessionApi),
            repository = sessionsRepository,
            createSession = { request -> sessionStore.create(request) },
            resumeSession = { id, config -> sessionStore.resume(id, config) },
            autoApproveDefault = { approvalMode.autoApproveForNewSession() },
            forkTitleStrings = { ForkTitleStrings.from(context.resources) },
            clock = clock,
            onDeleted = { id ->
                knownSessions.remove(id)
                notifier.onSessionGone(id)
                sessionStore.forgetStopNotices(id)
            },
            // G3: best effort, like the desktop; a failed step stays on the script's stderr.
            removeWorktree = { path -> termux.run(WorktreeCommands.remove(path)) },
            compaction = { compactionMode.forCreate() },
        )
    }

    /** The dashboard's network edges (SP §0.3); no logging interceptor, the secret is in the URL. */
    val dashboardTransport: DashboardTransport by lazy { OkHttpDashboardTransport() }

    /** The settings-helper lease (docs/07 §3.5); screens `acquire` in `ViewModel.init`, `release` in `onCleared()`. */
    val settingsHelper: SettingsHelper by lazy {
        SettingsHelper(
            termux = termux,
            transport = dashboardTransport,
            kernel = helperKernel,
            unsupportedStore = helperUnsupported,
            scope = appScope,
            // Debug builds may pin the secret for the developer dashboard; release always draws a random one.
            secretSource = { KernelOverrides.helperRoomSecret() ?: SettingsHelper.randomRoomSecret() },
        )
    }

    /**
     * Provider catalog (SP §0.7, docs/07 §4.5): helper catalog with the 60 s TTL and generation
     * counter, the non-secret `catalog-cache.json` projection and the §0.4 fallback. Never
     * starts the helper; screens own the lease. Reads its files on [appScope] (IO), not here.
     */
    val providerCatalog: ProviderCatalogRepository by lazy {
        ProviderCatalogRepository(
            helper = settingsHelper.asCatalogHelper(),
            projectionStore = CatalogProjectionStore.of(CatalogCache(context.filesDir, CatalogProjection.serializer())),
            presenceSource = PresenceSource.of(termux),
            fallbackLoader = { FallbackProviders.load(context) },
            scope = appScope,
            clock = clock,
        ).also { it.start() }
    }

    /** `model.selection` / `model.reasoning` (SP S8.3, S8.4), written only on explicit picks. */
    val modelSelection: ModelSelectionStore by lazy { ModelSelectionStore.of(prefs) }

    /**
     * Home workspace (SP S9.3): active choice, recents MRU 20, `realpath -e` + `find` validation,
     * and session workspaces from `session.list {limit: 200, rootOnly: true}` while the Hub is up.
     */
    val workspaceStore: WorkspaceStore by lazy {
        WorkspaceStore(
            termux = termux,
            prefs = WorkspacePrefs.of(prefs),
            sessionSource = {
                if (kernel.state.value !is KernelState.Ready) {
                    null
                } else {
                    (sessionApi.list(limit = 200, rootOnly = true)["sessions"] as? JsonArray)
                        ?.mapNotNull { record -> (record as? JsonObject)?.let { SessionSummary.fromRecord(it) } }
                }
            },
        )
    }

    /**
     * Sign in with Cline (SP S7): one process-wide flow so waiting survives dismissing the sheet.
     * `restore()` recovers a sign-in interrupted by process death. The app shell forwards
     * ProcessLifecycleOwner ON_START / ON_RESUME / ON_STOP to `onAppStarted` / `onAppResumed` /
     * `onAppStopped`; the notifier collects `completions` for the background "Signed in" notice.
     */
    val signIn: ClineSignInCoordinator by lazy {
        ClineSignInCoordinator(
            termux = termux,
            store = SignInStore.of(prefs),
            helper = settingsHelper.asCatalogHelper(),
            catalog = CatalogInvalidator.of(providerCatalog),
            modelSelection = modelSelection,
            termuxForeground = KernelOverrides.termuxForeground(context, ActivityTermuxForeground(context)),
            scope = appScope,
            clock = clock,
        ).also { it.restore() }
    }

    /** Secret copies (API keys, tokens) with the 60 s clear rule (SP §0.6, docs/07 §14). */
    val secureClipboard: SecureClipboard by lazy {
        SecureClipboard(context, appScope, ProcessLifecycleOwner.get().lifecycle)
    }

    /** Whether a speech recogniser exists (CA C7): the composer's mic shows only then (OQ 6). */
    val speech: SpeechAvailability by lazy { SpeechAvailability(context) }

    /**
     * The recent prompts shared with the terminal (A11.6): one cache for every composer, read
     * through Termux on focus (at most once per 30 s) and appended to on every direct send.
     */
    val inputHistory: InputHistoryRepository by lazy {
        InputHistoryRepository(termux, appScope, clockMs = { clock.millis() }, log = { android.util.Log.w("InputHistory", it) })
    }

    /**
     * Builds the members whose first use blocks (docs/07 §8: `prefs.clientIdBlocking()` inside
     * [hubClient]) and the coordinator over them. Called once
     * off Main by the root ViewModel while the splash is still up, so no composable on Main is the
     * first to touch them.
     */
    fun warmUp() {
        prefs
        hubClient
        kernel
    }

    // ---- notifications and keep-alive (docs/07 §4.7, §5) --------------------------------------

    /** Sessions notifications and the keep-alive act on (CR §11.3); DataStore LRU 500. */
    val knownSessions: KnownSessions by lazy { KnownSessions(prefs, appScope) }

    /** `notifications.v1` (SP S3.2): read by [notifier] and the Notifications settings page. */
    val notificationPrefs: NotificationPrefs by lazy { NotificationPrefs(prefs) }

    /** Process visibility (`ProcessLifecycleOwner` at least STARTED). Touch off Main only until the splash releases. */
    val processForeground: StateFlow<Boolean> by lazy {
        ProcessLifecycleOwner.get().lifecycle.currentStateFlow
            .map { it.isAtLeast(Lifecycle.State.STARTED) }
            .stateIn(appScope, SharingStarted.Eagerly, false)
    }

    /** [NotifyFacts] of the known sessions the store holds; shared by [keepAlive] and [notifier], pins no session. */
    private val knownSessionStates: Flow<Map<String, NotifyFacts>> by lazy {
        knownSessionStates(sessionStore.asStateSource(), knownSessions.ids)
            .shareIn(appScope, SharingStarted.WhileSubscribed(), replay = 1)
    }

    /** When the foreground service is held and what it shows (CR §12.2, §12.3). */
    val keepAlive: KeepAlivePolicy by lazy {
        KeepAlivePolicy(
            states = knownSessionStates,
            keepConnected = prefs.keepConnected,
            gate = kernel.gate,
            foreground = processForeground,
            scope = appScope,
        )
    }

    /** Starts [KernelForegroundService] from the foreground only (CR §12.2); ClineApp starts it at the first ON_START. */
    val keepAliveLauncher: KeepAliveLauncher by lazy { KeepAliveLauncher(context, keepAlive, processForeground, appScope) }

    /** System notifications (SP S3.3, CR §11); ClineApp starts it at the first ON_START. */
    val notifier: ClineNotifier by lazy {
        ClineNotifier(
            context = context,
            states = knownSessionStates,
            settings = notificationPrefs.settings,
            seenStore = AppSeenRequestStore(prefs),
            foreground = processForeground,
            clock = clock,
            scope = appScope,
            // The open chat's ApprovalController answers new approvals of our runs under Auto (decided A1).
            autoAnswers = { id -> approvalMode.mode.value == ApprovalMode.Auto && sessionStore.isOpen(id) },
        )
    }

    /**
     * The helper's view of the coordinator (SP §0.3 steps 1.1, 1.2, 4). `isUserStopped` reads
     * the coordinator's live flag, which mirrors the persisted §7.2 key `kernel.userStopped`.
     */
    private val helperKernel: HelperKernel by lazy {
        object : HelperKernel {
            override suspend fun isUserStopped(): Boolean = kernel.userStopped.value || prefs.userStopped()
            override suspend fun isHubHealthy(): Boolean = hubProber.health(hubPort) != null
            override suspend fun startHub() {
                kernel.ensureConnected(startIfDown = true)
            }
            override suspend fun adoptHub() {
                kernel.ensureConnected(startIfDown = false)
            }
        }
    }

    // ---- tier-4 repositories (docs/07 §4.6; customize-and-rest.md §1–§9) ------------------------

    /** The helper as the repositories see it: state for route selection plus `desktopCommand`. */
    private val helperChannel: HelperChannel by lazy { settingsHelper.asChannel() }

    /** Catalog fetch, installed keys, install routes; owns the `inventoryChanged` channel. */
    val marketplace: MarketplaceRepository by lazy {
        MarketplaceRepository(
            http = MarketplaceRepository.httpClient(context.cacheDir),
            hub = hubClient,
            termux = termux,
            helper = helperChannel,
            clock = clock,
        )
    }

    /** settings.list / settings.toggle + CLI catalogs, 60 s cache keyed by workspace. */
    val customize: CustomizeRepository by lazy {
        CustomizeRepository(
            hub = hubClient,
            termux = termux,
            helper = helperChannel,
            inventoryChanged = marketplace.inventoryChanged,
            scope = appScope,
            clock = clock,
        )
    }

    /** Helper list/upsert/delete with the settings-file and `cline mcp` fallback. */
    val mcp: McpRepository by lazy { McpRepository(hubClient, termux, helperChannel, marketplace.inventoryChanged) }

    /** Hub `schedule.*` with `allWorkspaces: true`. */
    val schedules: SchedulesRepository by lazy { SchedulesRepository(hubClient, termux, clockMs = { clock.millis() }) }

    /**
     * `cline_account` through the helper; sign-out via `save_provider_settings`. The settings
     * lane's `ProviderCatalogRepository.invalidate()` is chained into [accountSignedOutHooks].
     */
    val account: AccountRepository by lazy {
        AccountRepository(
            helper = helperChannel,
            termux = termux,
            summary = AccountSummaryStore { name, scope -> prefs.setAccount(name, scope); Unit },
            onSignedOut = { accountSignedOutHooks.forEach { it() } },
        )
    }

    /** Run after a successful Cline sign-out (CR §5.8): provider catalog invalidation and the like. */
    val accountSignedOutHooks: MutableList<suspend () -> Unit> =
        java.util.concurrent.CopyOnWriteArrayList<suspend () -> Unit>().apply {
            // Resolved on sign-out only, so the catalog stays lazy.
            add { providerCatalog.invalidate() }
        }

    /** The agents roster; refreshes follow the parent session's state in [sessionStore] (D15). */
    val agents: AgentsRepository by lazy {
        AgentsRepository(
            hub = hubClient,
            parentActivity = { parentId -> sessionStore.state(parentId).map { it.touchedAt }.distinctUntilChanged() },
        )
    }

    /** What's new seen-tracking over DataStore `whatsNew.seenId`. */
    val whatsNew: WhatsNewRepository by lazy {
        WhatsNewRepository(
            object : WhatsNewRepository.Store {
                override suspend fun seenId(): String? = prefs.whatsNewSeenId()
                override suspend fun setSeenId(id: String) {
                    prefs.setWhatsNewSeenId(id)
                }
                override suspend fun onboardingCompletedAt(): Long? = prefs.onboardingCompletedAt()
            },
        )
    }

    /** The redacted diagnostics report (CR §9.4). */
    val diagnostics: DiagnosticsBuilder by lazy {
        DiagnosticsBuilder(
            hub = hubClient,
            termux = termux,
            prober = hubProber,
            app = DiagnosticsBuilder.AppFacts(
                packageName = context.packageName,
                versionName = BuildInfo.VERSION_NAME,
                versionCode = BuildInfo.VERSION_CODE,
                sdkInt = Build.VERSION.SDK_INT,
                release = Build.VERSION.RELEASE.orEmpty(),
                device = Build.MODEL.orEmpty(),
                abi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
            ),
            kernelFacts = {
                val info = kernel.state.value.hubInfo
                DiagnosticsBuilder.KernelFacts(info?.release, info?.cliVersion, DiagnosticsBuilder.gateName(kernel.gate.value))
            },
            transitions = { kernel.transitions.snapshot() },
            settings = { diagnosticsSettings() },
            nowMs = { clock.millis() },
            port = hubPort,
        )
    }

    /** The header's `settings`: appearance, notification prefs and the two kernel switches. */
    private suspend fun diagnosticsSettings(): JsonObject {
        val appearance = prefs.appearance.first()
        val notifications = prefs.notificationsJson()?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
        return buildJsonObject {
            put("theme", appearance.themeMode.storageKey)
            put("dynamicColor", appearance.dynamicColor)
            put("fontSize", appearance.fontSize)
            put("notifications", notifications ?: JsonNull)
            put("kernel.keepAwake", prefs.keepAwake())
            put("kernel.userStopped", prefs.userStopped())
        }
    }

    private val helperUnsupported: HelperUnsupportedStore by lazy {
        object : HelperUnsupportedStore {
            override suspend fun read(): Boolean = prefs.helperUnsupported()
            override suspend fun write(unsupported: Boolean) {
                prefs.setHelperUnsupported(unsupported)
            }
        }
    }
}

val Context.appGraph: AppGraph get() = (applicationContext as ClineApp).graph
