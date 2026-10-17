# App architecture (Kotlin, Jetpack Compose, Material 3)

Status: implementation contract, 2026-10-03. Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`), app name "Cline
for Android". This document says **how the code is organised and how data flows**; the
screen specs under `design/` say what each screen looks like and does. Where a screen spec
and this document disagree on a code-level matter (package, class name, persistence key,
threading, reducer rule), **this document wins** and §15 lists every such decision. Where
they disagree on UI behaviour or copy, the screen spec wins.

Sources this document was built from, all read in full before writing:

| Kind | Files |
| --- | --- |
| Kernel ground truth | `docs/README.md`, `docs/01-architecture.md`, `docs/02-kernel-contract.md` (**KC**), `docs/03-feature-matrix.md` (**FM**), `docs/04-screens.md`, `docs/05-open-questions.md` (**OQ**), `docs/06-toolchain.md`, `docs/08-hub-fixtures.md` (**HF**) and the fixtures in `app/src/test/resources/hub-fixtures/` |
| Design | `design/design-system.md` (**DS**), `design/screens/home-chat.md` (**HC**), `onboarding-kernel.md` (**KS**), `sessions.md` (**SS**), `composer-approvals.md` (**CA**), `changes-tools-checkpoints.md` (**CT**), `settings-providers.md` (**SP**), `customize-and-rest.md` (**CR**) |
| Existing code | every file under `app/src/main/kotlin/dev/cline/termux/`, `app/build.gradle.kts`, `AndroidManifest.xml`, `res/values/strings.xml`, the unit tests under `app/src/test/kotlin` |
| Desktop (under `~/workspace/cline-termux-v3-phone`) | `apps/examples/desktop-app/webview/lib/chat-schema.ts`, `lib/session-status.ts`, `lib/session-diff.ts`, `lib/model-selection.ts`, `lib/featured-models.ts`, `lib/desktop-notifications.ts`, `lib/command-output.ts`, `hooks/use-chat-session.ts`, `app/page.tsx` (navigation and workspace parts), `sdk/packages/ui/components/` (inventory: `agent-approval-card`, `agent-ask-question`, `agent-changes`, `agent-command-output`, `agent-composer`, `agent-context-usage`, `agent-prompt-queue`, `agent-session-row`, `search-combobox`, `session-status`, `agent-chat/`) |
| Voice app | `~/workspace/cline-termux-voice/android/app/src/main/kotlin/dev/cline/voice/` (`VoiceService.kt`, `bridge/`, `ui/Components.kt`, `ui/chat/`, `ui/settings/`, `ui/theme/`, `ui/markdown/`) |

Conventions: **existing** = file is in the tree today; **move** = existing file relocated
with `git mv` (history kept); **new** = to be written; **replace** = existing file whose
public shape changes. "Hub" = the Cline Hub daemon in Termux; "kernel" = Hub + CLI +
Termux together, as the user sees it.

---

## 0. The ten rules

1. **The kernel is the source of truth; the app is a projection.** Every durable fact
   (sessions, transcripts, approvals, schedules, settings, credentials) lives in Termux.
   The app persists only cursors, preferences, caches it can rebuild, and ids it needs to
   route notifications. Nothing the app stores is required for correctness after a
   reinstall.
2. **Events are truth, replies are hints** (KC). Every command reply is optional; the
   reducer is driven by the event stream. A missing reply never leaves the UI stuck: it
   shows the pending state until the event arrives or the correlation times out.
3. **One global subscription, one persisted cursor, dedupe by `sequence`** (HF, HC §1.1,
   SS §1.2.1). Per-session subscribes are sent only to force approval re-issue and carry
   no cursor.
4. **Secrets never enter the app's storage, logs or UI state.** The Hub token is sealed
   with an Android Keystore key; provider keys exist only in `remember` state while typed;
   `providers.json` is read through RUN_COMMAND and reduced to a redacted projection
   inside the bridge; the dashboard room secret lives in one object's memory (SP §0.3,
   KS §0.6, CA §0.8).
5. **The app never reads Termux files directly.** Only `RUN_COMMAND` (`cat`, `tail`,
   `readlink`, `find`, `realpath`), with the 100 KB result cap always in mind (KC).
6. **Pure reducers, fake edges.** Everything between a decoded frame and a UI state is a
   pure Kotlin function tested against recorded fixtures. The two edges (OkHttp socket,
   Termux intents) hide behind interfaces with fakes.
7. **One `KernelCoordinator`, one `KernelGate`.** Screens never run `cline hub start`
   themselves; they ask the coordinator and render the gate (KS §0.3, F.6).
8. **ViewModels own controllers, repositories own stores.** App-scoped singletons hold
   per-session state (`SessionStore`); screen-scoped ViewModels hold UI selection and
   transient controllers; nothing in `ui/` talks to `HubClient` or `TermuxExecutor`
   directly, always through a repository or a controller.
9. **Material 3 only** (DS §0): `androidx.compose.material3` 1.4 + `material3-adaptive`
   1.3; `ClineTheme` with `paletteColorScheme` on API 31+ and the brand schemes below,
   never `dynamicLightColorScheme`; no `material-icons-extended`; every string in
   `strings.xml`; `HardcodedText` is a lint error; edge-to-edge, predictive back, 48dp
   targets, TalkBack labels, no desktop-only chrome.
10. **Device rule** (CLAUDE.md): the app is never installed or launched on the S25U while a
    port candidate's device steps or the owner's manual test run.

---

## 1. Package layout

Root: `app/src/main/kotlin/dev/cline/termux/`. The four top-level layers are `core`,
`data`, `service`, `ui`, plus the app entry files. Screen specs name files by feature
(`chat/…`, `sessions/…`, `settings/…`); §1.2 maps those names onto this layout so a reader
of a spec finds the file. The split rule: a class that talks to the Hub, Termux or the
dashboard is `core`; a class that turns frames or command output into app state is `data`;
a class that is an Android component (service, receiver, notifier) is `service`; composables,
ViewModels and UI-only models are `ui`.

### 1.1 Tree

```
dev/cline/termux/
  ClineApp.kt                                   existing (Application; graph, channels, cache sweeps, pending icon disable)
  MainActivity.kt                               replace (splash keep-on, RootNavHost, intent routing, FLAG_SECURE owner)
  AppGraph.kt                                   replace (manual DI; every singleton below is a `by lazy` here)
  BuildInfo.kt                                  new (VERSION_NAME/CODE, MIN_PORT_RELEASE = "3.0.68-termux.2", MIN_PORT_RELEASE_RESTORE: String? = null)

  core/
    kernel/
      KernelCoordinator.kt                      move+replace (KS §0.3 state model, §0.4 connect sequence, auto-start, passive probe)
      KernelState.kt                            new (sealed KernelState, StartStep, RestartPhase, DownReason, HubInfo, HubLiveStatus)
      KernelGate.kt                             new (sealed KernelGate + `KernelState.toGate()`; KS F.6)
      KernelTransitions.kt                      new (ring buffer of last 50 transitions for diagnostics; no payloads)
      hub/
        HubFrames.kt                            existing (HubJson, frame envelopes, HubCommands, HubEvents, HubCapabilities)
        HubTransport.kt                         existing (HubSocket, OkHttpHubTransport, AUTH_PROTOCOL_PREFIX)
        HubClient.kt                            replace (§3.1: global cursor, replay budget, close ordering, per-command timeouts)
        HubCursorStore.kt                       new (single global cursor; replaces per-session HubCursorStore in HubClient.kt)
        HubDiscovery.kt                         existing (HubHealth, HubVersion, HubDiscoveryRecord, HubEndpoints, HubProbe)
        HubSessionApi.kt                        replace (NewSessionSpec, HubPayloads incl. restore/fork/steer/attachments; §3.2)
        HubTokenStore.kt                        existing (DataStore "kernel", sealed token + lifetime key)
        HubErrors.kt                            new (HubCommandException codes as constants, `isLostReply()`, `isSessionGone()`)
      termux/
        TermuxCommand.kt                        replace (toString() redacts `--apikey`, `--room-secret`, stdin; label-only logging)
        TermuxExecutor.kt                       replace (`run` + `runDetached`, ANSI strip, timeout table; §3.3)
        TermuxRunner.kt                         existing (intent plumbing; gains detached mode and foreground start fallback)
        TermuxResultReceiver.kt                 move (manifest `.core.kernel.termux.TermuxResultReceiver`)
        ClineCommands.kt                        replace (full command catalog §3.4; `readProviderPresence()` replaces `readProvidersJson()`)
        ConfiguredProviders.kt                  new (redacted projection of providers.json; replaces ProvidersFile.kt, which is deleted)
        AnsiStrip.kt                            new (`Regex("\u001B\\[[0-9;]*m")` applied to every stdout/stderr)
      dashboard/
        SettingsHelper.kt                       new (acquire/release lease, room secret, HelperState; SP §0.3)
        HelperFrames.kt                         new (desktopCommand / desktopCommandResult; lenient decoder)
        DashboardClient.kt                      new (OkHttp ws to /browser, request correlation, 30 s / 60 s / 300 s timeouts)
      secure/
        SecretBox.kt                            existing (AES/GCM Keystore alias dev.cline.termux.hub-token)
        SecureClipboard.kt                      new (EXTRA_IS_SENSITIVE, 60 s clear rule SP §0.6)

  data/
    prefs/
      AppPreferences.kt                         replace (every DataStore key of §7, typed accessors, flows)
      SessionListCache.kt                       new (filesDir/sessions/list-cache.json; SS)
      CatalogCache.kt                           new (filesDir/catalog-cache.json; SP §0.7)
    catalog/
      ModelCatalog.kt                           existing (bundled assets/catalog/catalog.json)
      FallbackProviders.kt                      new (res/raw/fallback_providers.json + FALLBACK_PROVIDER_MODELS tables; SP §0.4)
    session/                                    (per-session state: the reducer core)
      SessionStore.kt                           new (app-scoped: Map<sessionId, SessionState>, dispatch, subscribe, seed; §4.1)
      SessionState.kt                           new (facts + canonical + live + turns; §4.1)
      SessionFacts.kt                           new (status, run phase, approvals, questions, usage, tool records, checkpoint history, pending prompts)
      SessionFactsReducer.kt                    move+replace of session/model/SessionReducer.kt (pure; §4.2 column "facts")
      ChatProjector.kt                          new (events → live ChatMessage list; HC §1.3.1; §4.2 column "messages")
      HistoryMapper.kt                          new (session.messages → canonical ChatMessage list; HC §1.3.2)
      Reconciler.kt                             new (canonical ∪ live by renderKey; HC §1.3.3)
      TurnRecord.kt                             new (persisted open turns; HC §1.3.4)
      InterruptionDetector.kt                   new (client-side run.interrupted derivation; CA §0.2, HC §1.3.4)
      UserInputFormat.kt                        move (existing parse of <user_input mode>, <mode_notice>, <user_command>)
      SessionVisual.kt                          new (DS §1.6 status mapping `SessionVisual.of(status, flags)`)
    chat/
      ChatMessage.kt                            move (existing chat/model/ChatMessage.kt: Role, Meta, ChatStatus, RunFailedPayload)
      ChatGrouping.kt                           new (groupChatMessages, collapseCompletedWork ports; HC §1.5)
      ProviderAuthResolver.kt                   new (HC §1.3.5)
      SessionCreateBuilder.kt                   new (buildSessionCreate helper; HC §1.3.6, CT §1.5.2)
      SendPipeline.kt                           new (CA C1.7 + C5.7: slash resolution, attachments, delivery mode, failure table)
      SlashCommandSource.kt                     new (settings.list → SlashCommand list, cache per workspace; CA C5.6)
      MentionSearch.kt                          new (RUN_COMMAND file search, ranking, LruCache(64); CA C6.6)
      Attachments.kt                            new (image pipeline, text file chunked stdin writes; CA C1.9)
      ContextUsageCalculator.kt                 new (CA C1.12)
    sessions/
      SessionsRepository.kt                     new (list, flags, unread, pending actions, refresh policy; SS §1)
      SessionSummary.kt, SessionFormat.kt, SessionGrouping.kt, SessionActions.kt   new (SS §0 code layout)
      KnownSessions.kt                          new (LRU 500 in DataStore; CR §11.3)
    diff/
      SessionDiff.kt, LineDiff.kt, UnifiedDiffText.kt     new (pure ports; CT §1.2, A.4, A.7)
      SessionDiffStore.kt                       new (per-session, fed by SessionStore events + history; CT §1.3)
    tools/
      ToolCallStore.kt                          new (per-session tool records + CommandBuffer; CT §1.4)
      ToolPresentation.kt                       new (buildToolSummary / tool-summaries.ts port)
      TerminalBuffer.kt                         new (lines, \r handling, head caps 48 000 / 1 048 576; CT B.4)
    checkpoint/
      CheckpointIndex.kt                        new (run spans, exact/at-or-before lookup, trim ports; CT C.4)
      CheckpointActions.kt                      new (revert / edit-restart orchestration, lost-reply recovery; CT C.6, 1.6)
    providers/
      ProviderCatalogRepository.kt              new (60 s TTL, generation counter, resync, cache projection; SP §0.7)
      ProviderModels.kt                         new (ProviderListItem, ProviderModel, ConfigField, isChatModel, featuredBadges)
    model/
      ModelSelectionStore.kt                    new (DataStore model.selection / model.reasoning; SP S8.3, S8.4)
      ModelPickerData.kt                        new (buildModelPickerData port; SP S8.2)
    workspace/
      WorkspaceStore.kt                         new (active choice, recents MRU 20, validation via RUN_COMMAND; SP S9.3)
      WorkspacePaths.kt                         new (workspace-paths.ts port)
    customize/
      CustomizeRepository.kt                    new (settings.list/toggle + CLI catalogs; 60 s cache; CR §1.8)
      McpRepository.kt                          new (helper list/upsert/delete + file fallback + CLI route; CR §2.8)
    marketplace/
      MarketplaceRepository.kt                  new (catalog fetch + OkHttp cache, installed keys, install routes; CR §3.8)
      MarketplaceTypeMeta.kt                    new (TYPE_META, MATURITY_ORDER)
    schedules/
      SchedulesRepository.kt                    new (schedule.* with allWorkspaces:true; CR §4.8)
      ScheduleFormat.kt, RoutineTemplates.kt    new (ports; CR §0.7, §4.5)
      ClockFormat.kt                            new (every time of day on the "Use 24-hour format" setting; ui reads it via ui/components/DeviceClock.kt)
    account/
      AccountRepository.kt                      new (cline_account operations through the helper; CR §5.8)
      ClineSignInCoordinator.kt                 new (SP S7: Termux sign-in flow, SignInState)
    agents/
      SessionAgents.kt                          new (session-agents.ts port), AgentsRepository.kt (CR §6.7)
    about/
      Changelog.kt, WhatsNewContent.kt, WhatsNewRepository.kt   new (CR §7, §8)
    diagnostics/
      DiagnosticsBuilder.kt, Redactor.kt        new (CR §9.4, §9.6)
    notifications/
      NotificationPrefs.kt                      new (notifications.v1 JSON; SP S3.2)
      KeepAlivePolicy.kt                        new (hold rule; CR §12.2)

  service/
    KernelForegroundService.kt                  move+replace (variants CR §12.3, actions receiver, 10 s stop debounce)
    KernelServiceActions.kt                     new (exported=false BroadcastReceiver: STOP_AGENT, DISCONNECT)
    ClineNotifier.kt                            new (posting rules SP S3.3 + anatomy CR §11.2–11.5)
    NotificationChannels.kt                     replace of Notifications.kt (eight session channels, account, kernel; migration CR §11.7)
    NotificationRouter.kt                       new (PendingIntents with SESSION_ID / OPEN_SHEET extras; CA §0.6)
    ShareFiles.kt                               new (FileProvider helpers for cache/share, cache/diagnostics, cache/camera; 24 h sweep)

  ui/
    AppShell.kt, AppShellViewModel.kt           new (window class, drawer/rail, KernelBanner host, kernelGate flow)
    navigation/
      Routes.kt                                 new (@Serializable route objects; §6.2)
      RootNavHost.kt                            new (onboarding | shell graph; start destination after one DataStore read)
      ShellNavHost.kt                           new (chat root + top-level + pushed routes; expanded list-detail scaffolds)
      DeepLinks.kt                              new (intent extras → route; CA §0.6)
      BackOrder.kt                              new (documented back precedence helpers; DS §5.8)
    theme/                                      existing (ClineTheme, BrandScheme, ClineColors, PaletteScheme, Type, Shape, Spacing, Motion)
    icons/                                      existing (ClineIcons, ToolIcons) + new ms_* drawables per spec appendices
    markdown/                                   existing (voice-app renderer)
    components/                                 new (DS §4 inventory: PageScaffold, SessionStatusDot, KernelChip, KernelBanner, KernelStatePanel,
                                                 KernelBlockedCard, HelperUnavailableCard, InlineErrorCard, DashedNotice, EmptyState, Skeleton,
                                                 ExternalLinks.kt `openExternal`, Badges, ConfirmDialogs, ...; SuggestionPanel lives in suggestions/)
    onboarding/                                 new (KS: Welcome, KernelSetup(+VM), InstallTermux(+VM), ConnectModel(+VM), Done; Reliability lives in kernel/)
    kernel/                                     new (KernelGraph, KernelStatusSheet + KernelStatusViewModel, HubUpdateRequiredDialog, KernelRepairScreen,
                                                 ReliabilityScreen + ReliabilityViewModel + ReliabilityChecks: one checklist for both
                                                 `onboarding/reliability` and `kernel/reliability`, registered from KernelGraph; KS D)
    home/                                       new (HomeScreen hero + composer host; HC A)
    chat/                                       new (ChatScreen, ChatViewModel, ChatUiState, rows, header, details sheet, lightbox, scroll button; HC B–E)
    composer/                                   new (Composer, ComposerController, ComposerUiState, chips, attach sheet, voice; CA C1, C7, C8)
    queue/                                      new (PromptQueueStrip, PromptQueueSheet, PromptQueueController; CA C2)
    approval/                                   new (ApprovalSheet, ApprovalBanner, ApprovalController, ApprovalPreview; CA C3)
    question/                                   new (AskQuestionSheet, QuestionBanner, AskQuestionController; CA C4)
    suggestions/                                new (SuggestionPanel, SlashMenu, MentionMenu, SuggestionMenuState; CA C5, C6)
    sessions/                                   new (SessionsDrawer(+VM), SessionsScreen(+VM), SessionSearch(+VM), SessionDetailsSheet, DeleteSessionDialog; SS)
    changes/                                    new (ChangesScreen, ChangesViewModel, ChangesPane; CT A)
    tool/                                       new (ToolDetailScreen, ToolDetailViewModel; CT B)
    terminal/                                   new (AnsiParser, TerminalView; CT B.4)
    diff/                                       new (DiffHunkView; CT A.4)
    checkpoint/                                 new (CheckpointDialogs, CheckpointPickerSheet, CheckpointViewModel; CT C)
    settings/                                   new (SettingsHome(+VM), Appearance(+VM), NotificationSettings(+VM); SP S1–S3)
    providers/                                  new (ProvidersList, ProviderDetail, AddProvider, ProvidersViewModel; SP S4–S6)
    account/                                    new (AccountScreen(+VM), FailureText; CR C8. No SignInWithClineSheet: SP S7 renders inline, D17)
    model/                                      new (ModelPickerSheet(+VM), ReasoningMenu; SP S8)
    workspace/                                  new (WorkspacePickerSheet(+VM), FolderBrowserScreen(+VM); SP S9)
    customize/                                  new (CustomizeScreen(+VM), tabs, ExtensionCards, McpServerEditor(+VM); CR C1, C2)
    marketplace/                                new (MarketplaceScreen, MarketplaceDetailScreen, MarketplaceViewModel; CR C3, C4)
    schedules/                                  new (SchedulesScreen, ScheduleEditor, ScheduleDetailsScreen, SchedulesViewModel; CR C5–C7)
    agents/                                     new (AgentsRosterSheet(+VM); CR C9)
    about/                                      new (AboutScreen(+VM), WhatsNewDialog, DiagnosticsExportDialog(+VM), LicensesScreen; CR C10–C13)
    debug/                                      existing KernelProbe* (kept under src/debug only; §9.6 fake kernel lives beside it)
```

Deleted after the move: `kernel/termux/ProvidersFile.kt` (plaintext keys in memory;
replaced by `ConfiguredProviders`), `Notifications.kt` (replaced by
`service/NotificationChannels.kt`), `session/model/Transcript.kt` (its types fold into
`data/session/SessionFacts.kt`), the per-session cursor code in `HubClient.kt`.

### 1.2 Spec path → architecture path

| Spec says | Lives at |
| --- | --- |
| `kernel/*` (KS, CA, CT) | `core/kernel/*` |
| `kernel/hub/HubPayloads`, `HubSessionApi` | `core/kernel/hub/HubSessionApi.kt` |
| `chat/model/ChatMessage`, `groupChatMessages`, `collapseCompletedWork`, `TurnRecord` (HC) | `data/chat/ChatMessage.kt`, `data/chat/ChatGrouping.kt`, `data/session/TurnRecord.kt` |
| `SessionStore`, `SessionState` (HC §1.1) | `data/session/` |
| `session/model/SessionReducer` (CT §0.2) | `data/session/SessionFactsReducer.kt` |
| `chat/composer/*`, `chat/queue/*`, `chat/approval/*`, `chat/question/*`, `chat/ChatBanners.kt` (CA) | `ui/composer`, `ui/queue`, `ui/approval`, `ui/question`, `ui/chat/ChatBanners.kt`; their pure parts (`SendPipeline`, `MentionSearch`, `Attachments`, `ContextUsageCalculator`) in `data/chat/` |
| `sessions/*Repository`, `SessionListCache` (SS) | `data/sessions/`, `data/prefs/SessionListCache.kt` |
| `ui/sessions/*` (SS) | `ui/sessions/` |
| `session/diff/*`, `session/tools/*`, `session/checkpoint/*` (CT) | `data/diff/`, `data/tools/`, `data/checkpoint/` |
| `ui/terminal/*`, `ui/diff/*`, `ui/changes/*`, `ui/tool/*`, `ui/checkpoint/*` (CT) | same names under `ui/` (`AnsiParser` + `TerminalView` in `ui/terminal`, `TerminalBuffer` in `data/tools`) |
| `settings/*`, `settings/providers/*`, `settings/helper/*` (SP) | `ui/settings`, `ui/providers`, `data/providers`, `core/kernel/dashboard` |
| `account/*`, `model/*`, `workspace/*`, `notifications/*` (SP) | `data/account` + `ui/account`, `data/model` + `ui/model`, `data/workspace` + `ui/workspace`, `data/notifications` + `service/` |
| `customize/*`, `marketplace/*`, `schedules/*`, `agents/*`, `about/*`, `diagnostics/*` (CR) | `data/<feature>` for repositories and ports, `ui/<feature>` for screens |
| `ui/OpenExternal.kt` (CR §0.8) and `ui/components/ExternalLinks.kt` (KS §0.7) | one file `ui/components/ExternalLinks.kt` with `openExternal(context, url)` and `openUrl` as an alias |
| `notifications/NotificationRouter.kt`, `KeepAlivePolicy.kt` (CR) | `service/NotificationRouter.kt`, `data/notifications/KeepAlivePolicy.kt` |
| Reliability(+VM) under onboarding (KS D) | `ui/kernel/ReliabilityScreen.kt`, `ReliabilityViewModel.kt`, `ReliabilityChecks.kt`: the same screen serves the onboarding and the kernel-status routes, so it sits with `KernelGraph`, which registers both |
| `composer/SuggestionPanel.kt` (CA §0.4) and SuggestionPanel in the DS §4 inventory | `ui/suggestions/SuggestionPanel.kt`, beside the two menus it hosts |
| `account/SignInWithClineSheet.kt` (SP S7) | no file: each entry point renders the S7 states inline (D17) |

### 1.3 Object graph (`AppGraph`)

Manual DI stays (no Hilt). Every singleton is a `by lazy` on `AppGraph`, created on first
use; the only eager work in `ClineApp.onCreate` is `NotificationChannels.ensure`, the
pending-launcher-alias disable (SP S2.2) and the 24 h cache sweep on an IO coroutine.

```kotlin
class AppGraph(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("app"))
    val kernelDispatcher = Dispatchers.Default.limitedParallelism(1)           // §8
    val clock: Clock = Clock.systemUTC()
    val prefs: AppPreferences
    val tokenStore: HubTokenStore;  val secretBox: SecretBox
    val termux: TermuxExecutor                                              // TermuxRunner(app)
    val commands: ClineCommands                                             // typed catalog over termux
    val hubClient: HubClient                                                // one instance for the process
    val kernel: KernelCoordinator                                           // owns hubClient lifecycle
    val sessionApi: HubSessionApi
    val sessionStore: SessionStore
    val sessionsRepository: SessionsRepository
    val diffStores: SessionDiffStore.Registry;  val toolStores: ToolCallStore.Registry
    val settingsHelper: SettingsHelper;  val providerCatalog: ProviderCatalogRepository
    val modelSelection: ModelSelectionStore;  val workspaceStore: WorkspaceStore
    val customize: CustomizeRepository;  val mcp: McpRepository;  val marketplace: MarketplaceRepository
    val schedules: SchedulesRepository;  val account: AccountRepository;  val signIn: ClineSignInCoordinator
    val agents: AgentsRepository;  val whatsNew: WhatsNewRepository;  val diagnostics: DiagnosticsBuilder
    val knownSessions: KnownSessions;  val notificationPrefs: NotificationPrefs
    val keepAlive: KeepAlivePolicy;  val notifier: ClineNotifier
    val speech: SpeechAvailability
}
val Context.appGraph: AppGraph get() = (applicationContext as ClineApp).graph
```

ViewModels receive dependencies through a `viewModelFactory { }` per screen reading
`LocalContext.current.appGraph` once in the composable (pattern already used by
`KernelProbeViewModel`). Tests construct ViewModels directly with fakes.

---

## 2. Runtime overview

```
Android process                                            Termux process group
┌────────────────────────────────────────────────┐          ┌─────────────────────────────┐
│ ui/  ViewModels ── UiState (StateFlow)          │          │ Cline Hub daemon :25463      │
│   ▲ collect            │ intents                │   ws     │   /hub (token subprotocol)   │
│ data/ SessionStore ◄── HubClient.events ────────┼──────────┤   /health /version           │
│       repositories ◄── HubClient.command()      │          │ cline dashboard :8787 (opt.) │
│       stores        ◄── TermuxExecutor results  │  intent  │   /browser (room secret)     │
│ core/ KernelCoordinator ── RUN_COMMAND ─────────┼──────────┤ RunCommandService            │
│       SettingsHelper  ── DashboardClient ───────┼── ws ────┤   cline / cat / tail / find  │
│ service/ KernelForegroundService, ClineNotifier │          │   termux-wake-lock           │
└────────────────────────────────────────────────┘          └─────────────────────────────┘
```

Data flows in one direction: Hub events → `SessionStore.dispatch` (on `kernelDispatcher`)
→ pure reducers → `StateFlow<SessionState>` → ViewModels map to `UiState` → composables.
Commands flow back through repositories/controllers → `HubSessionApi` → `HubClient`.

---

## 3. Core: the kernel layer

### 3.1 `HubClient`

Transport and framing are the existing `OkHttpHubTransport` / `HubFrames.kt`. The client
changes in five places relative to today's file.

**Identity and registration** (KC step 5): `client.register` payload exactly as KC;
`clientId = "android-<uuid>"` from `client.id` (created once, never rotated);
`workspaceContext = {workspaceRoot, cwd}` = the last real workspace or `$HOME`
(`/data/data/com.termux/files/home`) when none (CR §4.8 needs a registered workspace
client). `capabilities = [approval.respond, tool_executor.askQuestion]`. A second
`client.register` on the same socket is never sent (`client_already_registered`).

**Subscriptions and the one cursor.**

```kotlin
interface HubCursorStore { suspend fun get(): Long?; suspend fun put(sequence: Long) }  // DataStore sessions.globalCursor
```

- On every (re)connect, after `client.register`: `hub.status {}` → `L = eventLog.lastSequence`;
  `C = cursors.get()`. If `C == null || L - C > REPLAY_BUDGET (2000)`: subscribe with
  `sinceSequence = max(0, L - 2000)` and emit `HubClient.Notice.ReplayTruncated(fromSequence)`
  (SessionsRepository marks `replayTruncated`, HC/SS copy); else `sinceSequence = C`.
- The frame is `{kind:"stream.subscribe", clientId, sinceSequence}` with **no sessionId**.
- `subscribe(sessionId)` sends `{kind:"stream.subscribe", clientId, sessionId}` with **no
  cursor**; it exists only to re-issue pending approvals for the open session (HC §1.1)
  and is re-sent on reconnect for the set of currently open sessions. `unsubscribe(sessionId)`
  on leave.
- Every event arrives on the global stream; per-session duplicates are dropped by
  `(sequence)` in `dedupe = LongRingSet(4096)`. The cursor advances to the highest
  `sequence` seen; `cursors.put` is throttled to once per second and flushed on
  `ProcessLifecycleOwner` `ON_STOP` and on `disconnect()`.
- The old `hub.cursor.<sessionId>` keys are migrated once: the max of all stored values
  seeds `sessions.globalCursor`, then they are removed.

**Request/reply correlation and timeouts.**

```kotlin
suspend fun command(name: String, payload: JsonObject, sessionId: String? = null,
                    envelopeTimeoutMs: Long? = defaultEnvelopeTimeout(name),
                    clientTimeoutMs: Long = defaultClientTimeout(name)): JsonObject
```

| Command | `envelope.timeoutMs` | client timer |
| --- | --- | --- |
| `session.send_input`, `run.start` | `null` (LONG_RUNNING) | none: the reply is only a correlation id; `run.*` events are truth |
| `session.create` (incl. resume/fork) | 120 000 | 125 000 |
| `session.restore` | 120 000 (`SESSION_RESTORE_TIMEOUT_MS`) | 130 000 (`SESSION_RESTORE_CLIENT_TIMEOUT_MS`) |
| `settings.list` with `includePluginTools:true` | 60 000 | 65 000 |
| every other command | 30 000 | 35 000 |

The client timer is always longer than the envelope value so the Hub's own
`hub_command_timeout` can arrive (CT §1.5.2). Pending requests are a
`Map<requestId, CompletableDeferred>`; a reply with an unknown `requestId` is logged at
debug level and dropped.

**Reconnect policy** (KC): delay `min(250 ms · 2^attempt, 5 s)` + 50 % jitter, same
`clientId`, re-register, `hub.status`, global subscribe with the cursor, per-session
subscribes for open sessions, then the coordinator re-attaches sessions (§3.2). The
replacement socket is opened **only after** the old one reported `onClosed` or `onFailure`
(fixture 04: a second socket while the first is alive is rejected): `disconnectForReplace()`
sends `close(1000)`, waits up to 2 s, then `cancel()` and waits for the callback.
`maxReconnectAttempts = 3` consecutive failures → state `Unavailable(reason)`; the
coordinator takes over (probe `/health`, discovery check, `HubDown`). On close, every
pending command fails with `HubCommandException(code = "hub_connection_closed")`; nothing
is retried by the client. Idempotent reads are retried once by their repository after the
next `Connected` (CR §0.5).

**Event fan-out.** `events: SharedFlow<HubEventEnvelope>(replay = 0, extraBufferCapacity =
4096, onBufferOverflow = SUSPEND)` collected by exactly one consumer, `SessionStore.pump`,
on `kernelDispatcher`; every other interested party (`ClineNotifier`, `KeepAlivePolicy`,
`SessionsRepository`, `SchedulesRepository`, `CustomizeRepository`) observes
`SessionStore`'s derived flows or `HubClient.sideEvents` (a `SharedFlow` of the non-session
events: `hub.*`, `schedule.*`, `settings.changed`). Back-pressure on the pump suspends the
OkHttp reader thread, never drops frames.

**Draining.** `hub.drain_changed {draining}` is mirrored on `HubClient.draining:
StateFlow<Boolean>`. A caller that gets `hub_draining` uses
`HubClient.awaitNotDraining(30_000)` and sends once more (CR §0.5); only the drain-gated
commands (`session.create|restore|fork`, `run.start`, `session.send_input`, `run.enqueue`)
can receive it.

### 3.2 `KernelCoordinator`

State model, steps and policies are KS §0.3–§0.5 verbatim; this section fixes the code
shape.

```kotlin
sealed interface KernelState {
    data object Unknown; data class TermuxMissing(val disabled: Boolean); data class TermuxTooOld(val versionName: String)
    data object PermissionMissing; data class ExternalAppsBlocked(val errmsg: String); data object TermuxNotSetUp
    data object PortMissing; data class PortTooOld(val release: String)
    data class Starting(val step: StartStep, val startedAtElapsedMs: Long)
    data class Connecting(val info: HubInfo); data class Ready(val info: HubInfo, val hub: HubLiveStatus?)
    data class Reconnecting(val info: HubInfo, val attempt: Int); data class Restarting(val info: HubInfo, val phase: RestartPhase)
    data class Draining(val info: HubInfo); data class ProtocolUnsupported(val info: HubInfo); data class AuthFailed(val info: HubInfo)
    data class HubDown(val reason: DownReason, val lastError: String?, val doctor: DoctorSummary?)
}
```

API (all `suspend`, serialized by one `Mutex`): `ensureConnected(startIfDown: Boolean)`,
`stopKernel()` (`cline hub stop` → `termux-wake-unlock` → `kernel.userStopped = true`),
`disconnect()` (socket only; wake lock released when `kernel.keepAwake`), `restart()`
(KS E.7 phases), `recheck()` (P1–P3 only), `probeHealth()`; `state: StateFlow<KernelState>`,
`gate: StateFlow<KernelGate>`, `info: HubInfo?`, `userStopped: StateFlow<Boolean>`,
`transitions: KernelTransitions`.

Connect sequence as implemented (KS §0.4, KC):

| Step | Call | Outcome |
| --- | --- | --- |
| P1 | `PackageManager.getPackageInfo("com.termux")`, enabled flag, `versionCode ≥ 109` | `TermuxMissing` / `TermuxTooOld` |
| P2 | `checkSelfPermission(com.termux.permission.RUN_COMMAND)` | `PermissionMissing` |
| P3 | `commands.probeVersion()` = `cat $PREFIX/opt/cline-termux/current/VERSION` (10 s) | parse `release=`/`cline=`; `err == 150` + "not found" → `TermuxNotSetUp`; errmsg mentions allow-external-apps → `ExternalAppsBlocked`; file missing → `PortMissing`; `release < MIN_PORT_RELEASE` → `PortTooOld` |
| H1 | `HubProbe.health()` (1 s/2 s) + `version()` | protocol must be `v1` and inside min/max client range else `ProtocolUnsupported` |
| H2 | if refused and `startIfDown`: `commands.hubStart()` (60 s budget from the tap); non-zero exit whose stderr contains "Timed out after 15000ms" counts as still starting → poll `/health` every 1 s until the budget ends; on failure `commands.tailDaemonLog()` and match `entry.js` → `PortTooOld` else `HubDown(StartFailed|StartTimedOut)` | |
| H3 | `commands.wakeLock()` when `kernel.keepAwake` (default true) | best effort |
| H4 | token: `tokenStore.read()` valid when `lifetimeKey == "${hubId}:${pid}:${startedAt}"`, else `commands.readHubDiscovery()` → `HubDiscoveryRecord.parse` → `tokenStore.write(sealed)` | 401 on upgrade → re-read once → `AuthFailed` |
| H5 | `hubClient.connect(credentials)`; on `Connected` → `Ready`; then re-attach: for each session in `sessionStore.openSessions()` `session.attach {sessionId}`; `session_not_found` → mark `live = false` (resume happens lazily on the next send, CA C1.7) | |

Policies: auto-start on `ON_START` and on resume after > 5 s away; after 3 failed
reconnects probe `/health` → discovery check (`production.json` absent → `StoppedInTermux`)
→ `HubDown` without auto-start; passive `/health` probe every 10 s while `HubDown` and the
app is foreground; degraded mode (`Ready` with RUN_COMMAND revoked) exposes
`gate = DegradedNoCommands`. `KernelGate` is derived purely:

```kotlin
sealed interface KernelGate { data object Ready; data class Busy(val kind: BusyKind, val stepLabel: Int?, val slow: Boolean)
    data class Down(val variant: DownVariant, val detail: String?); data object DegradedNoCommands; data class Blocked(val focus: CheckId) }
```

The `KernelCoordinator` is the **only** caller of `cline hub start|stop`,
`termux-wake-lock|unlock` and `cat production.json`.

### 3.3 `TermuxExecutor` (the RUN_COMMAND bridge)

```kotlin
interface TermuxExecutor {
    fun isTermuxInstalled(): TermuxPresence                 // Missing | Disabled | Installed(versionCode)
    fun hasPermission(): Boolean
    suspend fun run(command: TermuxCommand, timeoutMs: Long = 60_000): TermuxResult      // waits for the result intent
    fun runDetached(command: TermuxCommand): DetachedHandle                              // no completion timeout; early-exit signal only
}
data class TermuxResult(val stdout: String, val stderr: String, val exitCode: Int, val err: Int, val errmsg: String?) {
    val ok get() = err == -1 && exitCode == 0;  val delivered get() = err == -1
    val lastStderrLine: String?;  val truncated: Boolean  // stdout.length + stderr.length >= 100_000
}
sealed interface TermuxUnavailable : Exception { NotInstalled; PermissionMissing; StartFailed(cause); TimedOut(label, afterMs); ExternalAppsBlocked(errmsg) }
```

Rules (KC, SP §0.5, KS §0.5, CA §0.8):

- Every intent is built by `TermuxRunner` with `RUN_COMMAND_PATH`, `_ARGUMENTS`,
  `_WORKDIR` (default `$HOME`), `_BACKGROUND` (true unless foreground), `_STDIN`,
  `_SESSION_ACTION`, `_PENDING_INTENT` (getBroadcast, `FLAG_ONE_SHOT | FLAG_UPDATE_CURRENT
  | FLAG_MUTABLE`, request code = execution id hash). `startForegroundService` first, falling
  back to `startService`; `ForegroundServiceStartNotAllowedException` / `IllegalStateException`
  → `StartFailed`.
- stdout and stderr are **ANSI-stripped** before they are matched, displayed or logged.
- Success of a CLI call is `exitCode == 0`, never a stdout match.
- Logging: `label` and timing only; never arguments, stdin, stdout or stderr.
- `TermuxCommand.toString()` prints `label` plus argument count; `--apikey`,
  `--room-secret`, `--header` values and stdin are never rendered.
- The 100 KB cap: callers that read files use `tail -c 49152` or per-query filters
  (`grep … | head -n 200`) so a result is never truncated silently; `truncated` is checked
  where it matters (folder listing, search).
- `runDetached` is used for the dashboard launch, `cline auth <oauth>` (foreground session)
  and `termux-setup-storage`; its handle exposes `earlyExit: Deferred<TermuxResult>` and
  `cancel()` (which only forgets the handle; killing is the caller's `pkill`).
- Foreground-session commands (`BACKGROUND=false`, `SESSION_ACTION=0`) are followed by
  `Intent().setClassName("com.termux", "com.termux.app.TermuxActivity")` with
  `FLAG_ACTIVITY_NEW_TASK` from the activity (OQ 5).

### 3.4 `ClineCommands` catalog

Each entry: id, path (`$PREFIX` = `/data/data/com.termux/files/usr`, `$HOME` =
`/data/data/com.termux/files/home`), timeout, result shape, spec. All run in the background
runner unless marked *fg* or *detached*.

| Id | Command | Timeout | Result | Spec |
| --- | --- | --- | --- | --- |
| `probeVersion` | `cat $PREFIX/opt/cline-termux/current/VERSION` | 10 s | `PortVersion(release, cline)` | KS §0.5 |
| `readPortRelease` | `readlink $PREFIX/opt/cline-termux/current` → basename | 10 s | String | CR §7.7 |
| `cliVersion` | `$PREFIX/bin/cline --version` | 10 s | first line | CR §7.7 |
| `hubStart` | `cline hub start` | 60 s | last stdout line = ws URL | KC |
| `hubStatus` | `cline hub status --json` | 15 s | `HubStatusOutput` | existing |
| `hubStop` | `cline hub stop` | 30 s | — | KS |
| `hubUpgrade` | `cline hub upgrade --wait 30` | 75 s | — | KS E.9 |
| `wakeLock` / `wakeUnlock` | `termux-wake-lock` / `termux-wake-unlock` | 10 s | — | KC |
| `readHubDiscovery` | `cat $HOME/.cline/data/locks/hub/production.json` | 20 s | `HubDiscoveryRecord` (parsed in the bridge; raw string discarded) | KC |
| `readHubCmdline(pid)` | `bash -c "tr '\0' '\n' < \"/proc/\$1/cmdline\"" _ <pid>` | 10 s | the release tree of the `…/entry.js` argument (`PortRelease.fromHubCmdline`), else null | KS §0.5, trial row 24 |
| `tailDaemonLog` | `tail -c 4000 $HOME/.cline/data/logs/hub-daemon.log` | 10 s | String | KS §0.4 |
| `tailLog(path, bytes=49152)` | `tail -c <bytes> <path>` | 15 s | String, first partial line dropped | CR §9.4 |
| `doctorJson` | `cline doctor --json` | 30 s | JsonObject | KS, CR §9.4 |
| `doctorFix` | `cline doctor --fix` | 60 s | — | KS |
| `statProviders` | `stat -c %Y $HOME/.cline/data/settings/providers.json` | 10 s | mtime | KS §0.5 (3 s poll during sign-in only) |
| `readProviderPresence` | `cat …/providers.json` | 10 s | `ConfiguredProviders` (redacted inside the bridge; never on a timer) | KS §0.6, SP §0.4 |
| `readGlobalSettings` | `cat $HOME/.cline/data/settings/global-settings.json` | 10 s | `GlobalSettingsView(telemetryOptOut, disabledTools)` only | SP S1.2, CR §1.8 |
| `authProvider(id, key, model, baseUrl?)` | `cline auth --provider <id> --apikey <key> --modelid <model> [--baseurl <url>]` | 30 s | exit code | SP §0.4 |
| `authInteractive(providerId)` *fg, detached* | `cline auth <providerId>` | — | early exit only | SP S7.3 |
| `cancelAuth` | `pkill -f "index.js auth (cline\|openai-codex\|oca)$"` | 10 s | exit 0/1 both = done | SP S7.3 |
| `dashboardStart(secret)` *detached* | `cline dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret <secret>` | — | early exit only | SP §0.3 |
| `dashboardStop(secret?)` | `pkill -f "index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret <secret or [0-9a-f]{32}>( \|$)"` | 10 s | — | SP §0.3 |
| `configToolsJson(ws)` / `configHooksJson(ws)` | `cline config tools --json` / `hooks --json`, workdir `ws` | 60 s | JsonArray | CR §1.8 |
| `readMcpSettings` | `cat $HOME/.cline/data/settings/cline_mcp_settings.json` | 10 s | parsed; env values redacted in memory | CR §1.8 |
| `mcpInstall(args)`, `mcpUninstall(name)` | `cline mcp install … --yes --json` / `uninstall <name> --json` | 120 s | exit + output | CR §2.8 |
| `pluginInstall/Uninstall`, `skillAdd/Remove` | per CR §1.8 / §3.8 | 120–300 s | exit + output (through `Redactor`) | CR |
| `gitBranch(ws)`, `gitBranches(ws)`, `gitCheckout(ws, b)` | `git -C <ws> branch --show-current` / `for-each-ref --count=500 …` / `checkout <b>` | 10 s / 10 s / 30 s | lines | HC §2.5 |
| `gitIsWorkTree(cwd)` | `git -C <cwd> rev-parse --is-inside-work-tree` | 10 s | `err 150`→no git; exit 128→not repo; `true`→ok | CT C3.5 |
| `realpath(path)`, `isDirectory(path)` | `realpath -e <p>` / `find <p> -maxdepth 0 -type d` | 10 s | canonical path / non-empty | SP S9.3 |
| `listDirectories(dir)` | `find <dir> -maxdepth 1 -mindepth 1 -type d -not -name ".*"` | 15 s | lines, ≤ 500, `truncated` | SP S9.3 |
| `hasSharedStorage` | `find $HOME/storage -maxdepth 0` | 10 s | exit | SP S9.3 |
| `setupStorage` *fg, detached* | `termux-setup-storage` | — | — | SP S9.3 |
| `searchWorkspaceFiles(root, q)` | `$PREFIX/bin/bash -c '<rg/find pipeline>' _ <root> <q>` (CA C6.6 script verbatim) | 10 s | lines | CA C6.6 |
| `readTextFile(path)` | `cat <path>` | 60 s | String (≤ 100 KB) | CA C5.6 |
| `writeAttachment(dir, name, chunks)` | `mkdir -p` then `sh -c 'cat >> "$1"' _ <path>` per ≤ 128 Ki-char stdin chunk | 30 s each | path | CA C1.9 |
| `historyExport(sessionId, out)` | existing | 60 s | — | existing |

`ConfiguredProviders` is the only thing that leaves `readProviderPresence`:

```kotlin
data class ConfiguredProvider(val id: String, val hasApiKey: Boolean, val hasOAuth: Boolean, val model: String?,
                              val hasBaseUrl: Boolean, val tokenSource: String?, val updatedAt: String?)
data class ConfiguredProviders(val lastUsedProvider: String?, val providers: Map<String, ConfiguredProvider>) {
    fun isConfigured(id: String) = providers[id]?.let { it.hasApiKey || it.hasOAuth } == true
}
```

### 3.5 `SettingsHelper` and `DashboardClient`

Implemented exactly as SP §0.3 (lease count, kernel-first, per-process `roomSecret`
from `SecureRandom`, probe matrix, 20 s/45 s start budgets, 5 s release grace,
`HelperState`, `HelperFailure`, `helper.unsupported` latch). `DashboardClient` is a
second, independent OkHttp WebSocket (`ws://127.0.0.1:8787/browser?roomSecret=…`, headers
`Host` and `Origin: http://127.0.0.1:8787`), with:

- `suspend fun desktopCommand(command: String, args: JsonObject, timeoutMs: Long = 30_000): JsonElement`
  (60 s for `add_provider`, 300 s for `install_marketplace_entry` / `uninstall_marketplace_entry`);
- a decoder that handles only `desktopCommandResult` by `id` and ignores every other
  `type` (the unsolicited `provider_catalog` frame is dropped unparsed);
- no `HttpLoggingInterceptor`, ever (the secret is in the URL);
- `HelperTransportClosed` thrown to callers when the socket drops; repositories treat it as
  an unknown outcome and re-read (CR §2.9, §3.9).

Which screens acquire the lease is the union of SP §0.5 and CR §0.3; `acquire(owner)` is
called from `ViewModel.init`, `release(owner)` from `onCleared()`.

### 3.6 `HubTokenStore` and `SecretBox`

Unchanged design: DataStore file `kernel` holds `hub.token.sealed` (AES/GCM under the
Keystore alias `dev.cline.termux.hub-token`), `hub.token.lifetime`
(`"$hubId:$pid:$startedAt"`), `hub.token.port`. The token is decrypted only inside
`credentialsFor()` and handed to `OkHttpHubTransport` as the subprotocol header; it is
never kept in a `String` field longer than the connect call. The developer-only
Origin-fallback path (`Origin: http://localhost`, no token) is behind `BuildConfig.DEBUG`
and a debug switch; it registers with `workspaceContext = $HOME` (CR §4.4).

### 3.7 Secrets, in one place

| Secret | Where it may exist | Never |
| --- | --- | --- |
| Hub `authToken` | sealed in DataStore `kernel`; in the upgrade header | logs, UI state, `SavedStateHandle`, diagnostics (Redactor rule 2–3) |
| Provider API keys (typed) | `remember { }` in the field composable; the argument array of one `TermuxCommand`; one `save_provider_settings` frame | DataStore, `rememberSaveable`, `SavedStateHandle`, logs, snackbars |
| Provider keys from `list_provider_catalog` | the `ProvidersViewModel` in-memory catalog, wiped in `onCleared()` | `catalog-cache.json` (projection strips `apiKey`, `configValues`, `baseUrl`) |
| `providers.json` contents | the bridge's parse call frame | anything but `ConfiguredProviders` |
| Dashboard `roomSecret` | `SettingsHelper` field; the launch argv; the ws URL | DataStore, logs, crash reports |
| MCP `env` / `headers` values | the editor ViewModel; rendered `[REDACTED]` | `SavedStateHandle`, semantics tree |
| User content (prompts, `inputJson`, question text, answers) | state and frames | logs (CA §0.8) |

Screens that reveal a secret set `FLAG_SECURE` on the activity window (SP §0.6) through
`MainActivity.secureFlagHolder.hold(owner)` / `release(owner)`; sheets and expanded
dialogs pass `SecureFlagPolicy.SecureOn` while revealed. `setRecentsScreenshotEnabled(false)`
is set by the Connect-a-model and provider-detail screens while a key field is on screen.

---

## 4. Data: sessions, transcripts and the reducer

### 4.1 `SessionStore` and `SessionState`

App-scoped singleton; one `MutableStateFlow<SessionState>` per session id, created on first
reference and evicted when not open, not pending and untouched for 10 minutes (never while
a `TurnRecord` is open).

```kotlin
data class SessionState(
    val sessionId: String,
    val facts: SessionFacts,                 // reducer output: status, run, approvals, questions, usage, tools, checkpoints, pending prompts, flags
    val canonical: List<ChatMessage>,        // from session.messages (HistoryMapper); empty until seeded
    val canonicalCount: Int,
    val live: List<ChatMessage>,             // from events since the seed (ChatProjector)
    val turns: List<TurnRecord>,             // open and recently closed send_input turns (persisted)
    val seed: SeedState,                     // NotSeeded | Seeding | Seeded(atSequence) | Failed(message)
    val live_: Boolean,                      // attached on this connection (SP S8.3 liveness)
    val replayGap: Boolean,                  // replay began above the session's last known sequence
) { val messages: List<ChatMessage> get() = Reconciler.merge(canonical, live) }   // memoized
```

```kotlin
data class SessionFacts(
    val status: SessionStatus,               // idle|starting|running|stopping|completed|cancelled|failed|error|pending
    val runPhase: RunPhase,                  // NoSession|Idle|Starting|Running|AwaitingUser|Stopping (CA)
    val activeRun: RunRef?,                  // requestId/runId, startedAt, clientId
    val title: String?, val pinned: Boolean, val metadata: JsonObject, val workspaceRoot: String?, val cwd: String?,
    val providerId: String?, val modelId: String?, val mode: String, val thinking: Boolean?, val reasoningEffort: String?,
    val approvals: Map<String, PendingApproval>, val questions: Map<String, PendingQuestion>,
    val tools: Map<String, ToolRecord>,      // toolCallId → record incl. executions (feeds ToolCallStore / SessionDiffStore)
    val usage: UsageTotals?, val aggregateUsage: UsageTotals?, val currentIteration: Int?,
    val checkpointHistory: List<CheckpointEntry>, val pendingPrompts: List<QueuedPrompt>, val queueUnavailable: Boolean,
    val lastSequence: Long, val lastEventAt: Long?,
)
```

Public API (all callers are repositories or controllers, never composables):

- `state(sessionId): StateFlow<SessionState>`; `open(sessionId)` / `close(sessionId)`
  (drives `subscribe/unsubscribe(sessionId)` and `session.attach`);
- `seed(sessionId)` = `session.get {includeSnapshot:true}` + `session.messages` →
  `fromRecord` + `HistoryMapper` (also used by the "resume" path and after restore);
- `appendLocalUserMessage(sessionId, turn)` (HC §1.3.1 `local:<requestId>`);
- `dispatch(envelope)` — internal, called by the pump; `dispatchSide(envelope)` for
  non-session events;
- `markInterrupted(sessionId, runId, reason)` from `InterruptionDetector`;
- `openSessions(): Set<String>`; `isOpen(sessionId)`.

The two existing transcript models are reconciled as follows: **`ChatMessage` is the
canonical transcript** (HC §1.3; desktop `chat-schema.ts`). `SessionReducer`'s
`SessionThread` is renamed `SessionFacts`; its `TranscriptItem` list is **removed** (the
projector builds messages instead) while its tool-call/execution bookkeeping, approval
marking, usage and status handling are kept as `SessionFactsReducer`. The fixture test
`SessionReducerFixtureTest` is kept and extended: it now asserts both the facts and the
projected `ChatMessage` list for each fixture.

### 4.2 Event → state rules

Every event in `HubEvents`. "Route" says how the pump finds the session; "Facts" and
"Messages" are the pure reducers; "Side" lists the observers that react to the resulting
state change. Ids follow HC §1.3.1: `local:<requestId>`, `a:<requestId>:<iteration>:<n>`,
`r:<requestId>:<iteration>:<n>`, `t:<toolCallId>`, `x:<sequence>` (status/system),
`n:<sequence>` (notice), `e:<requestId>` (error), `i:<runId>` (interrupted),
`h:<msgId>:<blockIndex>` (history). Every event first passes `dedupe(sequence)` and
advances `facts.lastSequence`.

| Event | Route | Facts | Messages | Side |
| --- | --- | --- | --- | --- |
| `hub.client.registered` / `.disconnected` / `.updated` | none | — | — | `KernelStatusViewModel` other-clients list (`summarizeOtherClients`); healthcheck clients ignored |
| `hub.drain_changed {draining}` | none | — | — | `HubClient.draining`; coordinator `Draining`/`Ready` |
| `session.created {session, snapshot}` | `sessionId` | create state `fromRecord`; `title`, `workspaceRoot`, provider/model/mode from metadata | — | `SessionsRepository.upsert` (drops `parentSessionId` records and invalid provider/model from the list, SS); `KnownSessions.add` when `metadata.source == "android"` or the app's pending create matches |
| `session.updated` (`{sessionId, snapshot}` then `{session, snapshot}`) | `sessionId` | `status = snapshot.status`; `checkpointHistory = snapshot.checkpoint.history`; metadata/title/pinned from `session` when present; `runPhase` recomputed; shutdown projection (status `cancelled|completed` + `endedAt` while a turn is open) → `InterruptionDetector` | — | sessions list row; `CheckpointIndex` refresh; `KeepAlivePolicy`; unread rule (SS: annotation-only updates are not activity) |
| `session.attached` / `session.detached` | `sessionId` | `live_ = true/false` for this client's attach | — | — |
| `session.forked {sourceSessionId, sessionId}` | new id | as created | — | sessions list |
| `session.pending_prompts {prompts}` | `sessionId` | `pendingPrompts = prompts` | — | `PromptQueueController`; `ClineNotifier` suppression check |
| `session.pending_prompt_submitted {promptId, requestId}` | `sessionId` | remove from queue; remember `requestId` as **queued** (its `run.started` must not open a `TurnRecord`) | `local:<requestId>` message from the queued prompt text | — |
| `session.notice {message, level}` | `sessionId` | — | `n:<sequence>` role `system`, `meta.noticeType` | — |
| `run.started {requestId, runId, clientId, iteration?}` | `sessionId` | if `requestId` is one of this client's open `TurnRecord`s: `activeRun = {runId, startedAt}`, `runPhase = Running`; if queued: ignore for phase; else (other client) `runPhase = Running`, `activeRun` set; clear `tools` of the previous run | `x:<sequence>` status "started" (hidden by grouping) | `ClineNotifier.cancelResult(sessionId)`; `KeepAlivePolicy`; checkpoint icon appears with the following `session.updated` |
| `run.heartbeat` | `sessionId` | `lastEventAt` | — | — |
| `run.enqueued {requestId}` | `sessionId` | mark requestId queued | — | — |
| `iteration.started {iteration}` | `sessionId` | `currentIteration` | — | — |
| `iteration.finished` | `sessionId` | — | closes the open assistant/reasoning message of that iteration | — |
| `assistant.delta {requestId, iteration, text}` | `sessionId` | — | append to `a:<requestId>:<iteration>:<n>` (new message after a tool call in the same iteration → `n+1`); `renderKey` kept | 50 ms conflation at the ViewModel |
| `assistant.media {media}` | `sessionId` | — | `media += GeneratedMedia` on the open assistant message | — |
| `assistant.finished` | `sessionId` | — | close assistant message | — |
| `reasoning.delta` / `reasoning.finished` | `sessionId` | — | `r:<requestId>:<iteration>:<n>` reasoning text; `reasoningRedacted` flag; `thoughtEndedAt` on finish | — |
| `tool.started {toolCallId, toolName, input}` | `sessionId` | `tools[id] = ToolRecord(Running, input, startedAt = ts)`; events without `toolCallId` ignored | `t:<toolCallId>` role `tool`, `meta.toolName`, `toolCallId`, `iteration` | `ToolCallStore` |
| `tool.updated {toolCallId, update}` | `sessionId` | `run_commands`: append `CommandBuffer` chunk by `executionId`/`stream` (incremental); `detachable`, `commandIndex`, `query`, `truncated`, `droppedChars` tracked; gap detection (first chunk not the empty announce → `gapped`) | `meta.toolOutput` (48 000-char tail cap with head marker), `toolDetachable` | `ToolCallStore` publishes at most every 50 ms |
| `tool.finished {toolCallId, output, error?}` | `sessionId` | `Finished(error)`; `Rejected` when error contains "This tool call was rejected by the user"; `output[commandIndex].{result,success}` into buffers; endedAt | `meta.toolOutput` final (streamed chunks win; fallback dropped when `error`), `toolOutputTruncated` | `SessionDiffStore.append(ToolResultEvent)` for `editor`/`apply_patch`; `MentionSearch.cache.clear()` after `editor|apply_patch|run_commands` |
| `approval.requested {approvalId, …, inputJson, policy}` | `sessionId` | `approvals[approvalId]` (dedupe); `tools[toolCallId].status = AwaitingApproval`; `status = pending` | tool row shows awaiting | `ApprovalController` (open session) / status dot + snackbar (other); `ClineNotifier.approval`; `KeepAlivePolicy` |
| `approval.resolved {approvalId, approved, cancelled?}` | `sessionId` | remove approval; tool → `Running` / `Rejected` / removed when cancelled | — | sheet card removed; `ClineNotifier.cancel(approval)` |
| `capability.requested {requestId, targetClientId, capabilityName, payload}` | `sessionId` | only when `targetClientId == clientId && capabilityName == "tool_executor.askQuestion"`: `questions[requestId] = (question = args[0], options = args[1])` | — | `AskQuestionController`; `ClineNotifier.question`; `KeepAlivePolicy` |
| `capability.resolved {requestId, ok, cancelled?}` | `sessionId` | remove question | tool row "Asked …" | sheet closes; cancelled snackbar unless own Stop; `ClineNotifier.cancel(question)` |
| `usage.updated {delta, totals, usage, aggregateUsage, agent{kind}}` | `sessionId` | `usage`, `aggregateUsage` (lead session only when `agent.kind == "lead"`) | token fields on the current assistant message | context ring |
| `agent.done` | `sessionId` | — | — | — |
| `team.progress` | `sessionId` | — | — | `AgentsRepository.refresh` (debounced 750 ms) |
| `diff.created`, `artifact.created` | `sessionId` | — | — | ignored in v1 (diffs come from tool results) |
| `run.completed {requestId, runId, reason, result}` | `sessionId` | `reason == "completed"` → `status = completed`, `runPhase = Idle`; `reason == "disposed"` with an open turn → interruption; `result.toolCalls[]` refine durations; close `TurnRecord`; clear `approvals`/`questions` of this run | `x:<sequence>` status "completed" (reason ≠ completed → "cancelled"/"interrupted") | `ClineNotifier.taskCompletion` (only `reason == "completed"`); `KeepAlivePolicy`; `SessionsRepository` unread |
| `run.failed {requestId, error}` | `sessionId` | `status = failed`, `runPhase = Idle`; close turn | `e:<requestId>` role `error`, `RunFailedPayload` | `ClineNotifier.sessionError`; credential-failure action (`resolveCredentialFailureAction`) |
| `run.aborted {requestId}` | `sessionId` | `status = cancelled`, `runPhase = Idle`; approvals/questions cleared; close turn | `x:<sequence>` "cancelled" | no notification; Stop button resets |
| `run.interrupted {runId, reason:"hub_restart"}` | `sessionId` | `tools` running → `Interrupted`; close turn as interrupted | `i:<runId>` role `status` "interrupted" + resend affordance | chat banner |
| `schedule.created|updated|deleted|triggered`, `schedule.execution_completed|failed` | none | — | — | `SchedulesRepository.refresh` (500 ms debounce; only the two execution events are actually published today) |
| `settings.changed {types}` | none | — | — | `CustomizeRepository.invalidate`; `SlashCommandSource.invalidate` |
| `ui.notify` | `sessionId` | — | — | ignored (HF: never fires per turn; notifications are derived) |

Derived interruption (CA §0.2, HC §1.3.4): `InterruptionDetector` runs after every
reconnect once replay has drained to `hub.status.lastSequence`; for each open `TurnRecord`
without a terminal `run.*`: (a) `/version` `pid`/`startedAt` differ from the turn's
`hubLifetimeKey` → interrupted; (b) a shutdown projection arrived → interrupted. It then
calls `sessionStore.markInterrupted` and the chat offers resend of `turn.prompt`.

### 4.3 Derived stores

- **`SessionDiffStore`** (CT §1.3): keyed by session; seeds from `canonical` tool pairs,
  appends on `tool.finished`, ignores already-seen `toolCallId`s; exposes
  `StateFlow<DiffStoreState>`; recompute on `Dispatchers.Default`.
- **`ToolCallStore`** (CT §1.4): keyed by `(sessionId, toolCallId)`; `TerminalBuffer` per
  execution with the 1 048 576-char cap for the detail screen and the 48 000 cap for the
  inline row; publishes with `conflate()` every 50 ms.
- **`CheckpointIndex`** (CT C.4): pure, rebuilt from `facts.checkpointHistory` +
  `messages`; cached per `(lastSequence, canonicalCount)`.
- **`TurnRecord`** persistence: DataStore `chat.turns` = JSON list of open turns
  `{requestId, sessionId, startedAtSequence, prompt, images: ids only, mode, providerId,
  providerAuth, open, hubLifetimeKey, sentAt}`; closed turns are dropped after 24 h.

### 4.4 `SessionsRepository` (SS §1)

Owns `SessionSummary` list + `SessionFlags`, `KernelSessionStatus`, unread counts,
`PendingAction` map, `SessionListCache` (file), the refresh policy (after connect, on
resume > 10 s, drawer open, pull, unknown-session event, after fork/delete, 2 s
initial-load retry) and the schedule links (`schedule.active/list_executions/list` with
`allWorkspaces:true`). Flags come from `SessionStore` facts when the session is loaded,
from the record otherwise. `interrupted` uses `inFlightTurns` + `suspectInterrupted`
(persisted `sessions.suspectInterrupted`). Verifies `session.delete` with `session.get` →
`session_not_found` when the reply says `deleted:false`.

### 4.5 Providers, models, workspaces

- `ProviderCatalogRepository`: SP §0.7 verbatim; `Flow<CatalogState(source: Catalog|Cache|Fallback, generation, providers, modelsByProvider)>`;
  `invalidate()`; `configured(id)`; model lists only from `list_provider_models`.
- `ModelSelectionStore`: `model.selection` `{lastProvider, lastModelByProvider}` and
  `model.reasoning` `{providerId: level}`, written only on explicit picks.
- Thinking chip (decided A2, batch 1): a live or cached catalog row decides by its own
  `supportsReasoning` (custom models from the kernel's `models.json` included), like the
  desktop's `hasReasoningCapability`. In fallback mode `FallbackProviders.reasoningSupport`
  answers from the bundled list (`res/raw/fallback_providers.json`, schema 2, exported from the
  registry with each provider's `capabilities` by `tools/catalog/export-fallback-providers.ts`):
  a model in the desktop's reasoning table → on; a provider with the `reasoning` capability → on
  for all its models; a model in the plain table → off; otherwise unknown (off). The bundle
  travels on `CatalogState.bundle`. So in fallback mode every model of a reasoning provider is
  on, while the live catalog may turn a single model off (`deepseek-chat` on the S25U).
- `WorkspaceStore`: `workspace.active` (`Folder(path)` | `Chat`, no folder: the session
  starts in the Termux home, D30), `workspace.recents`
  (≤ 20), `validate(path)` via `realpath -e` + `find -maxdepth 0 -type d`,
  `recentsFromSessions` merged from `SessionsRepository`.

### 4.6 Customize, marketplace, schedules, account, agents

Each repository is CR §1.8 / §2.8 / §3.8 / §4.8 / §5.8 / §6.7 in code: in-memory caches
(60 s customize lists keyed by `ws`; 10 min marketplace catalog + 4 MB OkHttp disk cache;
schedules refreshed on events, resume and every 30 s while an execution is active),
`inventoryChanged: MutableSharedFlow<Unit>` between marketplace and customize, and the
helper/CLI route selection by `HelperState`.

### 4.7 Notifications and keep-alive data

- `KnownSessions`: DataStore `notifications.knownSessionIds` LRU 500; `add` on create,
  open, notification tap; `remove` on delete.
- `NotificationPrefs`: `notifications.v1` JSON with the desktop `readPreference` fallback.
- `KeepAlivePolicy`: `StateFlow<HoldState(hold: Boolean, variant: Variant)>` computed from
  `SessionStore` facts restricted to `KnownSessions`, `prefs.keepConnected`, the gate and
  `ProcessLifecycleOwner` state (CR §12.2); 10 s release debounce.

### 4.8 Approvals model (decided A1, batch 1)

- **One setting, the kernel's.** `ApprovalModeRepository` holds `ApprovalMode`
  (`Unknown | Auto | Ask`) read from `toolAutoApprove` in Termux's `global-settings.json`
  (missing key = Auto, as the terminal); writes go through `KernelGlobalSettingsCommands.write`
  (a `bun-ffi -e` one-key merge). The app's former private `tools.autoApprove(.<id>)` keys are
  no longer read. Refreshed on foreground, session open, run end, menu and Settings open, Hub
  reconnect, resume, new session and before a send (at most once per 5 s for the last one);
  an outside change while the app was away shows "Auto-approve all turned off outside the app"
  (or on).
- **Per session the runtime's policy.** `HubPayloads.toolPolicies(auto)` goes on every create
  path (docs/02, batch 1 additions). `SessionState.createdAutoApprove` is the policy of the live
  runtime's latest create the app saw (its own create, or a `session.created` event); it
  outranks the Hub's records until the next create, the app's own detach or a new Hub
  lifetime, because core 0.0.91 echoes the creation-time value at a resumed session's run end.
  Without a seen create the record's `metadata.autoApproveTools` decides, then
  `runtimeAutoApprove` (last value seen; a pin drops the key); unknown counts as different.
- **Restart at run boundaries.** `RunBoundaryCoordinator` restarts a session whose policy
  differs from the global mode (`SessionStore.restart`: `session.compaction.get`,
  `session.detach`, resume create with `initialCompactionState`) when a run this client started
  ends (completed, failed or Stop) and just before a send to an idle session; never while
  another participant is attached, never for another client's run. P5's mode rebuild
  (`restart(id, mode)`) shares the same per-session gate, so the two never detach the same
  session at once; a send waits for a restart in flight (`HoldReason.PreparingSend`, then
  "Resuming session...").
- **UI.** `ApprovalModeButton`: a plain icon button before the title (placement A′, confirmed in
  A15; the batch-1 comparison extra is gone), states Ask (the ticked shield, `gpp_good` fill 1),
  Auto (the bolt) and Pending (the ticked shield at low emphasis with a small `tertiary` dot
  badge while the running run keeps its old policy); no disc, no ring (A15); a two-tap menu
  "Tool approvals" with "Shared with Cline in the terminal." The
  approval sheet's split button adds "Approve and auto-approve from now on"; Settings → Kernel
  has the "Auto-approve all" switch. Every place that means one of the two modes takes its glyph
  from `ClineIcons.Approval` (`Ask` = `ms_gpp_good_fill`, `Auto` = `ms_bolt`): the header
  control, its menu, the sheet's item and the Settings switch, so the mapping cannot drift
  between them. Pending is drawn by `ApprovalModeButton` alone: the target glyph in
  `onSurfaceVariant` at 60 % alpha (38 % when disabled) with an M3 `Badge` dot in `tertiary`
  that fades and scales in and out over `Short4`, instantly under reduced motion. Verified on
  the S25U in batch 2 (V-ICONS). `ClineNotifier` skips posting an approval the app answers
  itself (global Auto and the session open in the store).

---

## 5. Service layer

| Component | Manifest | Behaviour |
| --- | --- | --- |
| `KernelForegroundService` | `foregroundServiceType="specialUse"`, property `PROPERTY_SPECIAL_USE_FGS_SUBTYPE = hub-session`, id 1, channel `kernel` (LOW) | started only from the foreground (CR §12.2); variants CR §12.3 ("Cline is working...", "Waiting for your approval", "Cline has a question", "Cline is working on %d tasks", "Connected to the Cline Hub", "Stopping..."), chronometer from `run.started`, actions STOP_AGENT (only while `Ready`) and DISCONNECT via `KernelServiceActions`; `ForegroundServiceStartNotAllowedException` caught → `KeepAlive.Denied` |
| `KernelServiceActions` | `exported="false"` receiver | STOP_AGENT → `sessionApi.runAbort(sessionId)`; DISCONNECT → `kernel.disconnect()` |
| `ClineNotifier` | — | SP S3.3 posting contract + CR §11.2 anatomy; tags `<sessionId>/result` id 10, `/approval/<id>` 11, `/question/<id>` 12, `/summary` 13, `account` 20; background-only; `KnownSessions` only; 2-minute staleness on replay; dedupe LRU 500 + DataStore `notifications.seenRequestIds` (100); cancel rules CR §11.4 |
| `NotificationChannels` | — | eight session channels (sounding + `_quiet`), `account`, `kernel`; migration deletes `sessions` and `attention` |
| `NotificationRouter` | — | `PendingIntent.getActivity(MainActivity, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)` with extras `dev.cline.termux.extra.SESSION_ID` and `OPEN_SHEET` (`approval`\|`question`), request code = tag hash; no `cline://` scheme |
| `TermuxResultReceiver` | `exported="false"`, action `dev.cline.termux.action.TERMUX_RESULT` | routes the result bundle to `TermuxRunner.complete(executionId, raw)` |
| `FileProvider` | authority `io.github.ichouchiang.clinetermux.files`, `exported="false"`, `grantUriPermissions="true"`, `res/xml/file_paths.xml` with `cache-path` entries `share/`, `diagnostics/`, `camera/` | one provider for diff/output sharing (CT A.7), diagnostics (CR §9.5) and camera captures (CA C1.9); `ShareFiles` sweeps files older than 24 h on app start |

---

## 6. UI layer

### 6.1 Theme and shell

`ClineTheme(appearance)` as existing: `paletteColorScheme` (voice-app port) on API 31+
when `appearance.dynamicColor`, else `ClineLightScheme`/`ClineDarkScheme`; `ClineColors`
(success/warning/info `StatusColors`, diff, terminal, 16 ANSI, `whatsNew.*` brand-only);
typography factor `fontSize / 15` rebuilding `ClineTypography`/`ClineMono`, letter-spacing
0, SemiBold emphasis, Geist Mono; `ClineMotion` tokens; `enableEdgeToEdge()` +
`isNavigationBarContrastEnforced = false` + `SystemBarIcons()`; `WindowSizeClass` from
`currentWindowAdaptiveInfo()`.

`AppShell` (DS §5.2): Compact → `ModalNavigationDrawer` (drawer = `SessionsDrawer`);
Medium → `NavigationRail` + modal drawer; Expanded → rail + `NavigableListDetailPaneScaffold`
(list = sessions, detail = chat NavHost); height-compact (< 480dp) drops the list pane and
the hero. `AppShellViewModel` exposes `kernelGate`, `windowClass`, drawer state, the
`KernelBanner` model and the What's-new trigger.

### 6.2 Navigation graph

Routes are `@Serializable` objects/classes in `ui/navigation/Routes.kt`
(`navigation-compose` 2.10 type-safe API); the string forms below are the deep-link spelling.

| Route | Kind | Host | Spec |
| --- | --- | --- | --- |
| `onboarding/{welcome,kernel,reliability,connect,done}` | graph, start when `onboarding.completedAt == null` | `RootNavHost` | KS §0.2 |
| `kernel/install-termux`, `kernel/repair?focus=&pushed=`, `kernel/reliability`, `setup/connect`, `setup/done` | pushed | Root | KS |
| `shell` | the app shell; start when onboarded | Root | KS §0.2 |
| `chat/{sessionId?}` (`Chat(sessionId: String? = null)`) | fixed start destination of the shell; Home when null; top-level sessions are a **selection** inside it (`SavedStateHandle["sessionId"]`); child sessions pushed as `Chat(childId)` | Shell | HC §2.9 |
| `chat/{sessionId}?sheet=approval\|question\|queue` | deep link into the same destination | Shell | CA §0.6 |
| `chat/{sessionId}/changes`, `chat/{sessionId}/tool/{toolCallId}` | pushed < 1200dp; `SupportingContent.Changes/Tool` in the chat's `NavigableSupportingPaneScaffold` at ≥ 1200 × ≥ 480 | Shell | CT A.3, B.3 |
| `sessions`, `search` | pushed | Shell | SS |
| `schedules`, `customize?tab=`, `settings` | top-level (drawer/rail): `navigate { popUpTo(chat root) { saveState = true }; launchSingleTop = true; restoreState = true }` | Shell | DS §5.2, CR §0.2 |
| `settings/appearance`, `settings/notifications`, `settings/providers`, `settings/providers/{providerId}`, `settings/providers/add`, `kernel` (settings), `account?tab=`, `about`, `marketplace?type=&tag=` | pushed; on expanded `settings` is a list-detail scaffold whose Providers row **replaces** it with the providers scaffold (never nested) | Shell | SP §0.2, CR §0.2 |
| `marketplace/entry/{type}/{id}`, `schedules/{scheduleId}` | pushed on compact/medium; detail pane on expanded | Shell | CR |
| `customize/mcp/edit?name=`, `schedules/edit?id=&template=`, `about/diagnostics`, `workspace/browse?start=` | editors: `composable()` full-screen on compact/medium, `dialog()` with `BasicAlertDialog` on expanded (`if (expanded) dialog(route) else composable(route)` when the graph is built) | Shell | SP §11, CR §0.2 |
| `about/licenses` | pushed | Shell | CR C13 |

Sheets are not routes (approval, ask-question, model picker, prompt queue, context usage,
session details, kernel status, workspace picker, attach, branch, agents roster, checkpoint
picker, category filter, sign-in): hoisted `rememberSaveable` "open" flags in the hosting
screen plus their ViewModel state. Overlays above the NavHost: `ImageLightbox`,
`WhatsNewDialog`, `HubUpdateRequiredDialog`.

Transitions: fade-through between top-level destinations, shared-axis X for pushes,
shared-axis Y + fade for editors; all pops seekable for predictive back (DS §6, §5.8).

### 6.3 Back order

Implemented as a documented precedence (DS §5.8 + CT DS-A9), each level a `BackHandler`
enabled only while its condition holds; **no always-on `BackHandler`**:

1. open menu/tooltip → 2. open sheet (predictive collapse) → 3. lightbox → 4. inline edit
(queue item) → 5. slash/mention panel (per token) → 6. search field focus → 7. drawer →
7a. supporting pane → 8. list-detail pane back → 9. NavHost pop (seekable) → 10. leave app.
Editors add "discard confirm when dirty" at level 1 and "consume while saving".

### 6.4 Intent routing (`MainActivity`)

`onCreate(savedInstanceState == null)` and `onNewIntent` read `SESSION_ID` / `OPEN_SHEET`,
remove the extras, add the session to `KnownSessions`, and navigate `chat/{id}?sheet=…`
once the shell exists (queued in `AppShellViewModel.pendingDeepLink` until the NavHost is
composed; dropped while onboarding is incomplete). `installSplashScreen().setKeepOnScreenCondition
{ !prefsLoaded }` guards the one DataStore read that picks the start destination.

### 6.5 ViewModels and UiStates

One ViewModel per screen, `UiState` an `@Immutable` data class or sealed type exposed as
`StateFlow` (`stateIn(viewModelScope, WhileSubscribed(5_000), initial)`). Transient
controllers (`ComposerController`, `PromptQueueController`, `ApprovalController`,
`AskQuestionController`, `VoiceInputController`, `CheckpointActions`) are plain classes
owned by the ViewModel, created in `init`, closed in `onCleared()`.

| Screen | ViewModel | UiState | Scope |
| --- | --- | --- | --- |
| Shell | `AppShellViewModel` | `ShellUiState(gate, windowClass, banner, drawerOpen, pendingDeepLink)` | shell back-stack entry |
| Onboarding | `KernelSetupViewModel`, `InstallTermuxViewModel`, `ConnectModelViewModel`, `ReliabilityViewModel` | `KernelSetupUiState`, `ConnectUiState` (no key text), … | per route |
| Kernel status sheet | `KernelStatusViewModel` | `KernelStatusUiState` | shell |
| Home + Chat | `ChatViewModel(savedState["sessionId"])` | `ChatUiState` (HC §1.4) + `ComposerUiState`, `PromptQueueState`, `ApprovalUiState`, `QuestionUiState`, `SuggestionMenuState`, `VoiceState` | `Chat` entry |
| Sessions drawer / screen / search | `SessionsDrawerViewModel`, `SessionsScreenViewModel`, `SessionSearchViewModel` | SS UiStates | shell / route |
| Changes, Tool detail | `ChangesViewModel(sessionId)`, `ToolDetailViewModel(sessionId, toolCallId)` | `ChangesUiState`, `ToolDetailUiState` | route or pane |
| Checkpoints | `CheckpointViewModel` | dialog state + pending request in `SavedStateHandle` | chat route |
| Settings home / Appearance / Notifications | `SettingsHomeViewModel`, `AppearanceViewModel`, `NotificationSettingsViewModel` | SP S1.3, S2, S3.4 | route |
| Providers (list + detail + add) | `ProvidersViewModel` | `ProvidersListUiState`, `ProviderDetailUiState`, `AddProviderUiState` | the `settings/providers` graph (survives detail pops) |
| Model picker, Workspace picker, Folder browser | `ModelPickerViewModel`, `WorkspacePickerViewModel`, `FolderBrowserViewModel` | SP S8.5, S9.5 | hosting screen / route |
| Account | `AccountViewModel` | `AccountUiState` | route |
| Customize, MCP editor | `CustomizeViewModel`, `McpServerEditorViewModel` | `CustomizeUiState`, `McpEditorUiState` | route |
| Marketplace | `MarketplaceViewModel` | `MarketplaceUiState` | route (list + detail) |
| Schedules, editor | `SchedulesViewModel`, `ScheduleEditorViewModel` | `SchedulesUiState`, `ScheduleFormState` (`@Parcelize`) | route |
| Agents roster | `AgentsRosterViewModel(parentId)` | `AgentsRosterUiState` | sheet host |
| About, Diagnostics | `AboutViewModel`, `ExportDiagnosticsViewModel` | `AboutUiState`, `ExportDiagnosticsUiState` | route |

What goes in `SavedStateHandle` (survives process death): selections, tab, query, expanded
sets, scroll anchors, pending non-secret requests (restore/edit, deep link). What never
does: secrets, drafts of secret fields, transcripts.

---

## 7. Persistence schema

No Room. Three DataStore `Preferences` files, a handful of JSON files under `filesDir`,
caches under `cacheDir`. Every key is non-secret except the sealed token.

### 7.1 DataStore `kernel` (existing)

`hub.token.sealed` (String, AES/GCM), `hub.token.lifetime` (String), `hub.token.port` (Int).
Debug builds only (`app/src/debug/.../HubOverride.kt`): `hub.token.override` (String) and
`hub.port.override` (Int, 1024..65535), set and cleared by the developer receiver
(`scripts/dev-hub.sh reverse|unreverse`); the release stub returns null.

### 7.2 DataStore `app`

| Key | Type | Default | Owner |
| --- | --- | --- | --- |
| `client.id` | String | `android-<uuid>` on first read | HubClient identity |
| `onboarding.completedAt`, `onboarding.signIn.startedAt`, `onboarding.reliabilityShownAt` | Long? | null | KS §0.8 (`onboarding.done` is migrated into `completedAt`) |
| `kernel.keepAwake` | Boolean | true | KS (renames existing `kernel.keepRunning`) |
| `kernel.userStopped`, `kernel.wakeLockRequested` | Boolean | false | KS |
| `kernel.hubLifetimeRelease` | String? | null | KS |
| `reliability.batteryConfirmed`, `.sleepingConfirmed`, `.phantomConfirmed` | Boolean | false | KS D |
| `appearance.theme` (`system\|light\|dark`), `appearance.dynamicColor` (true), `appearance.fontSize` (15), `appearance.appIcon` (`midnight`), `appearance.pendingIconDisable` | — | — | SP S2 |
| `model.selection` (JSON `{lastProvider, lastModelByProvider}`), `model.reasoning` (JSON) | String | `{}` | SP S8 (replaces `model.provider` / `model.id`) |
| ~~`tools.autoApprove`, `tools.autoApprove.<sessionId>`~~ | Boolean | — | no longer read since batch 1: the kernel's `toolAutoApprove` (§4.8, D22) |
| `home.workInWorktree` | Boolean | false | G3 (Home "Worktree" chip; desktop `cline.code.work-in.v1`) |
| `transcript.messageDetails` (`brief\|verbose`) | String | `brief` | A11.9 (Settings → App → Message details) |
| `composer.mode.default`, `composer.mode.<sessionId>` (`act\|plan`) | String | `act` | CA C1 |
| `composer.draft.<key>` | String | — | CA C1 (debounced 500 ms; never for secret text) |
| `chat.turns` | JSON list of `TurnRecord` | `[]` | §4.3 |
| `workspace.active` (JSON `Folder\|Chat`; `Chat` = no folder chosen, so a first send starts in the Termux home, D30), `workspace.recents` (JSON ≤ 20), `customize.workspaceRoot` | String | Chat / `[]` / null | SP S9, CR §1.6 (replaces `workspace.last`) |
| `providers.favoriteModels` | JSON `{providerId: [modelId]}` | `{}` | SP S5.4 |
| `helper.unsupported` | Boolean | false | SP §0.3 |
| `signin.pending` | JSON `{providerId, startedAtMs}` | null | SP S7.3 |
| `account.displayName`, `account.scope` | String? | null | SP S1, CR C8 |
| `notifications.v1` | JSON | all enabled, sound off | SP S3.2 |
| `notifications.keepConnected` | Boolean | false | CR §12.2 |
| `notifications.seenRequestIds` (≤ 100), `notifications.knownSessionIds` (≤ 500) | JSON lists | `[]` | CR §11 |
| `toolDetail.wrapLines` | Boolean | true | CT B.7 |
| `whatsNew.seenId` | String? | null | CR §8.4 |
| `schedules.keepAliveNoticeDismissedFor` | String? | null | CR §4.4 |

### 7.3 DataStore `sessions`

| Key | Type | Owner |
| --- | --- | --- |
| `sessions.globalCursor` | Long | HubClient (§3.1; seeded from the max of legacy `hub.cursor.*`, which are then deleted) |
| `sessions.lastDisconnectAt` | Long | SessionsRepository unread cap |
| `sessions.unread` (JSON `{sessionId: count}`), `sessions.lastSeen` (JSON `{sessionId: sequence}`) | String | SS |
| `sessions.suspectInterrupted` (JSON set) | String | SS interrupted rule |
| `sessions.sidebarSort` | String | SS |
| `sessions.stopNotices` (JSON list, newest 200) | String | SessionStore: the app's mistake-limit stop notices (A17), dropped with their session |

### 7.4 Files

| Path | Content | Backup |
| --- | --- | --- |
| `filesDir/sessions/list-cache.json` | last `session.list` projection (SS) | excluded (`data_extraction_rules`) |
| `filesDir/catalog-cache.json` | non-secret provider/model projection (SP §0.7) | excluded |
| `cacheDir/marketplace/` | OkHttp cache 4 MB | n/a |
| `cacheDir/share/`, `cacheDir/diagnostics/`, `cacheDir/camera/`, `cacheDir/attachments/` | FileProvider-served temporaries; swept at 24 h | n/a |

Backup: `android:dataExtractionRules` excludes the two DataStore files that hold the token
and cursors (`kernel`, `sessions`) and both JSON caches; `app` preferences
may be backed up.

---

## 8. Threading and coroutine scopes

| Scope / dispatcher | Lives | Runs |
| --- | --- | --- |
| `appScope` (`SupervisorJob + Default`) | process | `SessionStore.pump`, repositories' refresh jobs, `KeepAlivePolicy`, `ClineNotifier`, `SettingsHelper` lease timers, cursor flusher |
| `kernelDispatcher` (`Default.limitedParallelism(1)`) | process | **all reducer dispatch** (`SessionStore.dispatch`, `SessionsRepository` upserts, `InterruptionDetector`) — guarantees event order without locks |
| `Dispatchers.IO` | — | DataStore, file caches, FileProvider writes, image decoding, Keystore ops |
| `Dispatchers.Default` | — | diff recompute, `TerminalBuffer` appends, markdown parsing, catalog filtering |
| `Dispatchers.Main.immediate` | — | `StateFlow` collection in composables, `SnackbarHostState`, navigation |
| `viewModelScope` | per ViewModel | UI intents, debounced writes (drafts 500 ms, cursor 1 s), polls (sign-in 3 s, run-now 1 s, schedules 30 s), controllers |
| OkHttp threads | library | socket callbacks only; every callback hops to `kernelDispatcher` (`trySend` to a `Channel(UNLIMITED)` consumed by the pump) |
| `ProcessLifecycleOwner` | process | `ON_START` → `kernel.ensureConnected(startIfDown = policy)`, `ON_STOP` → cursor flush, `KeepAlivePolicy` background flag, pending icon disable |

Rules: never `runBlocking` on Main (the two existing `runBlocking` reads in `AppGraph`
move behind the splash keep-on condition); every `StateFlow` exposed to UI uses
`WhileSubscribed(5_000)`; long CPU work (`LineDiff`, ANSI parsing, image resize) is
`withContext(Default)` and cancellable; `Mutex` only in `KernelCoordinator` and
`SettingsHelper`.

---

## 9. Error taxonomy and recovery

Three exception families, one mapping to the recovery matrix (KC, KS §0.3, CA §0.5,
CT §1.6, SP §10, CR §0.5).

```kotlin
class HubCommandException(val command: String, val code: String, message: String, val details: JsonElement?) : Exception
class HubConnectionClosedException : Exception                       // mapped to code "hub_connection_closed"
sealed interface TermuxUnavailable : Exception                        // §3.3
sealed interface HelperUnavailable : Exception { NotRunning(HelperFailure); TransportClosed; Timeout }
```

| Condition | Code / detection | Recovery (who) |
| --- | --- | --- |
| Hub refuses on 25463 | `/health` connect refused | `KernelCoordinator` → `cline hub start` when policy allows; UI `KernelStatePanel`/`KernelBanner` "Start kernel" |
| `/health` fails, Termux resolvable | timeout | `HubDown(Asleep)`; banner "Termux asleep"; passive probe |
| `production.json` absent after Hub down | discovery check | `HubDown(StoppedInTermux)`, no auto-start |
| Upgrade 401 | `HubUpgradeFailed(401)` | re-read discovery once → `AuthFailed` |
| Protocol out of range | `/health` | `ProtocolUnsupported`; `HubUpdateRequiredDialog` → `hubUpgrade` |
| Socket closed mid-command | `hub_connection_closed` | pending fail; writes never retried; idempotent reads retried once after `Connected` by the repository; UI reverts optimistic change, snackbar "Cline Hub disconnected. Try again when it reconnects." |
| `hub_draining` | reply | `awaitNotDraining(30 s)` then one resend; else "Cline Hub is restarting. Try again in a moment." |
| `client_authority_mismatch`, `client_not_found`, `client_already_registered` | reply | coordinator closes and reconnects with the same `clientId` (never a second register on the socket) |
| `session_not_found` on send/update | reply | resume via `session.create {sessionConfig.sessionId, initialMessages}` from the session's stored `NewSessionSpec` (CA C1.7); on `update_connection` mark not-live and store pending (SP S8.3); `command_failed` whose message starts with "session not found" is treated the same |
| `hub_command_timeout` on `session.restore`/`create` | reply | **lost reply**: recovery by `session.list` match on the metadata the app set (CT §1.6); never auto-resend; "Restore status unknown" dialog |
| `approval_not_found`, `capability.respond` `ignored:true` | reply | remove card silently / "already answered" snackbar |
| `pending_prompts_unavailable` | reply | queue disabled for this connection; direct sends (CA C2.6) |
| `restore_failed` variants | reply text (CT §1.5.4) | workspace refusal → reopen dialog with "Restore chat only"; others → error under the row |
| `settings_list_failed`, `settings_toggle_failed`, `schedule_command_failed` | reply | message verbatim in the screen's error surface |
| `run.interrupted` / derived interruption | event / detector | tools `Interrupted`, banner, resend affordance |
| RUN_COMMAND `err != -1`, errmsg mentions allow-external-apps | result | `ExternalAppsBlocked` → Kernel setup with that fix / `KernelBlockedCard` |
| Termux not resolvable | `<queries>` | `TermuxMissing` → Install Termux |
| `SecurityException` on `startService` | permission | `PermissionMissing` → request / app settings |
| RUN_COMMAND never returns (60 s, `run` only) | timeout | `TimedOut` → "Termux did not answer" + Retry + "Run diagnostics" |
| Helper start timeout / socket failed / port in use / unsupported / kernel stopped | `HelperState.Unavailable(reason)` | fallback mode per SP §0.4; unavailable card |
| Helper transport closed mid-write | `HelperUnavailable.TransportClosed` | unknown outcome → re-read and compare (MCP, marketplace, telemetry) |
| Account `isAccountAuthError` | reply message | signed-out card, cached summary cleared |
| `ForegroundServiceStartNotAllowedException` | service start | `KeepAlive.Denied`; row text in Notifications settings |
| Network for marketplace | OkHttp | stale cache (`only-if-cached, max-stale=2592000`) with notice; else error + Retry |

---

## 10. Testing strategy

### 10.1 Unit (JVM, `app/src/test`)

- **Reducer fixture replay.** `SessionReducerFixtureTest` (existing harness) replays every
  `hub-fixtures/0N-*.jsonl` through `SessionFactsReducer` + `ChatProjector` + `Reconciler`
  and asserts per fixture: statuses, approvals, tool records (fixture 02: `call_mock_run_1`
  two updates, first empty, `success:true`), rejection (03), history+replay dedupe and
  renderKey inheritance (04), resume (05). New fixture `07-checkpoint-restore.jsonl`
  (CT Q1) once recorded; regenerate with `tools/hub-fixtures/regenerate.sh`.
- **`HubClientTest`** with `FakeHubTransport` scripted from `mock-requests.jsonl`:
  register → `hub.status` → global subscribe with cursor; replay budget 2000; per-session
  subscribe without cursor; dedupe by sequence; reconnect waits for close; backoff bounds;
  pending fail on close; `LONG_RUNNING` null timeouts; client timer > envelope.
- **`KernelCoordinatorTest`** with `FakeTermuxExecutor` + `FakeHubProber`: every row of
  KS's decision table (TermuxMissing … HubDown reasons), start budget, `PortTooOld` from the
  daemon log, lifetime-key token reuse, degraded mode.
- **Pure ports with the desktop tests copied:** `SessionDiffTest`, `LineDiffTest`,
  `UnifiedDiffTextTest`, `AnsiParserTest`, `TerminalBufferTest`, `CheckpointIndexTest`,
  `ModelPickerDataTest` (`featured-models.test.ts`), `WorkspacePathsTest`,
  `SessionAgentsTest`, `ScheduleFormatTest`, `RoutineTemplatesTest` (SHA-256 of prompts),
  `ChangelogTest`, `RedactorTest` (one fixture per rule + production.json/providers.json
  excerpts), `SlashCommandsTest` (`getActiveSlash`, `insertSlashCommandItem`),
  `MentionRankingTest`, `ContextUsageCalculatorTest`, `UserInputFormatTest`,
  `ConciseBodyTest` (180-char rule), `SessionVisualTest` (precedence).
- **Payload builders:** `HubPayloadsTest` asserts no `apiKey` key anywhere,
  `checkpoint.enabled = true`, metadata spread on `session.update`, fork/restore metadata,
  `userFiles`, `steerFirstPendingPrompt`.
- **Bridge:** `TermuxCommandTest` (toString redaction), `AnsiStripTest`,
  `ConfiguredProvidersTest` (plaintext never escapes), `ClineCommandsTest` (every catalog
  row builds the documented argv).
- **Persistence:** DataStore via `PreferenceDataStoreFactory` on a temp dir: cursor
  migration from `hub.cursor.*`, `TurnRecord` round trip, `KnownSessions` LRU.
- **Notifier:** Robolectric `ClineNotifierTest` for channel selection by Sound pref,
  tags/ids, suppression and cancel rules.

Fakes live in `app/src/test/kotlin/dev/cline/termux/fakes/`: `FakeHubTransport`
(scripted frames + assertion of sent frames), `FakeTermuxExecutor` (map label → canned
`TermuxResult`, latency, timeouts), `FakeDashboardClient`, `FakeClock`,
`InMemoryCursorStore`, `TestAppGraph`.

### 10.2 Compose UI tests (`app/src/androidTest`, `ui-test-junit4`)

Run against `TestAppGraph` with the fakes above and the debug fake kernel (KS §0.9,
intent extra `dev.cline.termux.FAKE_KERNEL`). The five critical flows:

1. **First run → connected.** Welcome → Kernel setup checks (fake: Termux present, port
   ok) → Hub starts → Connect a model (fallback `cline auth` route) → Done → Home shows
   "What can I do for you?" and the kernel chip reads connected.
2. **Send → stream → complete.** Type a prompt, Send: `local:` bubble appears, fixture 02
   frames stream an assistant message and a tool row, `run.completed` sets status, the
   composer returns to Idle, the session appears in the drawer.
3. **Approval sheet.** Fixture 02 `approval.requested` opens the sheet expanded; Approve
   sends `approval.respond {approved:true}`; replay the fixture again and Reject sends the
   reason string and the turn continues; collapse to banner never rejects; "Stop agent"
   sends `run.abort`.
4. **Ask-question.** `capability.requested` opens the sheet; tapping option B sends
   `capability.respond {payload.result:"Green"}`; custom answer via Submit; collapse →
   banner → reopen keeps state.
5. **Resume and interruption.** Fixture 05: Hub restart while a turn is open → chat shows
   the interrupted status and resend; next send performs `session.create {sessionConfig.
   sessionId}` with `initialMessages` and the new daemon's events render; notification
   deep link `SESSION_ID` + `OPEN_SHEET=approval` cold-starts into the sheet.

Also: screenshot runs of every spec state with `./scripts/screenshot.sh` into
`screenshots/`, light/dark, never during a port release window.

### 10.3 Device acceptance

The checklists in each screen spec (HC §9, KS, SS §8, CA C1.15–C8.4, CT A.11/B.11/C.11/E,
SP S1.6–S9.8/§12, CR §1.11–§13) are the manual gate on the S25U and Tab S7+; the
architecture adds the §12.6/§11.9 kernel-behaviour checks and the SP §12 secret greps.

---

## 11. Build order

Fan-out is only possible once the shared files exist. Each phase names the files that must
compile and be unit-tested before the next starts.

**Phase 0 — foundation (one engineer, blocks everything).**
`BuildInfo.kt`; `data/prefs/AppPreferences.kt` with every §7 key; `core/kernel/hub/HubClient.kt`
rewrite + `HubCursorStore.kt` + `HubErrors.kt`; `core/kernel/termux/TermuxExecutor.kt`
(`run`/`runDetached`, ANSI strip), `TermuxCommand.kt` redaction, `ClineCommands.kt` catalog,
`ConfiguredProviders.kt`; `KernelState.kt`, `KernelGate.kt`, `KernelCoordinator.kt`;
`HubSessionApi.kt` (`NewSessionSpec`, all `HubPayloads`, `checkpoint.enabled = true`);
`AppGraph.kt`; the fakes in `test/fakes`; `strings.xml` seeded with every id the specs name
(empty values fail lint, so seed with the spec text); `ui/components/` skeletons that every
screen imports: `PageScaffold` (voice port with `navigationIcon` slot and `PageAppBar`),
`EmptyState`, `InlineErrorCard`, `KernelBanner`, `KernelStatePanel`, `KernelBlockedCard`,
`HelperUnavailableCard`, `ExternalLinks.kt`, `ConfirmDialog`, `Badge`, `Skeleton`,
`SessionStatusDot`, `SessionVisual`.

**Phase 1 — session core (one engineer; can overlap Phase 0's tail).**
`data/session/*` (`SessionFacts`, `SessionFactsReducer`, `ChatProjector`, `HistoryMapper`,
`Reconciler`, `TurnRecord`, `InterruptionDetector`, `SessionStore`), `data/chat/ChatMessage.kt`
move, `ChatGrouping.kt`, `SessionCreateBuilder.kt`; fixture tests green.

**Phase 2 — shell and navigation (one engineer).**
`ui/navigation/*`, `AppShell` + `AppShellViewModel`, `MainActivity` routing, `RootNavHost`
with onboarding placeholder destinations, drawer/rail/list-detail skeleton, `KernelGate`
wiring, `ClineTheme` font-size factor.

**Phase 3 — parallel screen work (fan-out, five lanes).** Each lane owns its `data/` and
`ui/` packages; shared touches go through Phase 0 files only.

| Lane | Screens | Depends on |
| --- | --- | --- |
| A Kernel/onboarding | KS: onboarding graph, Install Termux, Kernel repair, Reliability, Connect a model, Kernel status sheet, `KernelStatusViewModel`, `KernelDiagnostics` | Phase 0 |
| B Chat | HC + CA: Home, Chat thread, header, details sheet, lightbox, Composer + controllers, queue, approval, question, slash/mention, voice, attachments, `SendPipeline` | Phases 0–2 |
| C Sessions + Tier 3 | SS: drawer, sessions screen, search, details, delete; CT: `SessionDiffStore`, `ToolCallStore`, Changes, Tool detail, terminal, checkpoints | Phases 0–2 (checkpoints need lane B's message actions) |
| D Settings | SP: `SettingsHelper`/`DashboardClient`, `ProviderCatalogRepository`, Settings home, Appearance, Notifications settings, Providers, Add provider, Sign-in flow, Model picker, Workspace picker | Phase 0 |
| E Tier 4 + services | CR: Customize, MCP editor, Marketplace, Schedules, Account, Agents roster, About, What's new, Diagnostics, Licenses; `ClineNotifier`, `NotificationChannels`, `NotificationRouter`, `KeepAlivePolicy`, `KernelForegroundService` | Phase 0; Account needs lane D's helper; notifier needs Phase 1 |

**Phase 4 — integration.** Cross-spec contracts: `KernelGate` in the composer (KS §0.10),
`KernelBanner` replacing per-screen kernel notices, deep links end to end, FGS hand-off
(CA §0.7), five UI flows green, device acceptance.

---

## 12. Gradle changes

`app/build.gradle.kts` additions beyond the current file (Compose BOM `2026.09.00` stays
the version source for every `androidx.compose.*` artifact). Versions below are the
current releases compatible with AGP 9.4 / compileSdk 37 at the time of writing; the first
`./scripts/build.sh` after adding them is the resolution check (§16 Q4).

```kotlin
plugins {
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.10"     // ScheduleFormState, pending restore request
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")                              // NotificationCompat, FileProvider, ContextCompat
    implementation("androidx.browser:browser:1.9.0")                             // Custom Tabs (KS §0.7, CR §0.8)
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")                // ProcessLifecycleOwner (keep-alive, cursor flush)
    implementation("androidx.exifinterface:exifinterface:1.4.1")                 // image attachment orientation (CA C1.9)
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.4.0")  // PersistentList in UiStates (SS)
    implementation("androidx.compose.material3:material3-adaptive-navigation-suite")   // present; keep
    implementation("androidx.compose.material3.adaptive:adaptive-layout:1.3.0")  // present; NavigableListDetail/SupportingPane
    implementation("androidx.compose.material3.adaptive:adaptive-navigation:1.3.0")    // present

    testImplementation("app.cash.turbine:turbine:1.2.1")
    testImplementation("com.squareup.okhttp3:mockwebserver3:5.5.0")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core:1.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
android {
    testOptions.unitTests.isIncludeAndroidResources = true          // Robolectric notifier tests
    lint { error += listOf("HardcodedText"); abortOnError = true }
    buildTypes.getByName("debug") { buildConfigField("boolean", "FAKE_KERNEL", "true") }
}
```

Deliberately **not** added: `material-icons-extended` (DS), Hilt, Room, `security-crypto`
(SecretBox exists), Coil (no image loader needed: attachments are local bitmaps,
`photoUrl` is not shown), AboutLibraries (CR OQ C-11: hand-maintained list).

Manifest additions: `android.permission.RECORD_AUDIO`; `<queries><intent><action
android:name="android.speech.RecognitionService"/></intent></queries>`; the `FileProvider`
(§5) with `res/xml/file_paths.xml`; receiver `.service.KernelServiceActions`
(`exported="false"`); receiver path `.core.kernel.termux.TermuxResultReceiver`; service
path `.service.KernelForegroundService`; `android:dataExtractionRules` (§7.4);
`android:supportsPictureInPicture` stays absent. `strings.xml`:
`kernel_notification_working` = "Cline is working..." (three dots).

---

## 13. Screen index

Each row points at the authoritative spec section for states, copy, navigation and the
acceptance checklist, and quotes the kernel bindings so an engineer can grep them. Copy
ids follow the prefix shown and live in `strings.xml`.

| Screen | Route / host | VM · UiState | Kernel bindings (commands / events / RUN_COMMAND) | States (names) | Copy prefix | Nav & back | Acceptance |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Welcome | `onboarding/welcome` | `KernelSetupViewModel` | none | single | `onboarding_` | → kernel | KS |
| Kernel setup | `onboarding/kernel`, `kernel/repair` | `KernelSetupViewModel` · `KernelSetupUiState` | P1–P3, H1–H5 (§3.2); `cat VERSION`, `cline hub start`, `termux-wake-lock`, `cat production.json`, `tail hub-daemon.log`, `cline doctor --json` | Checking, each `KernelState` of KS §0.3 with KS F.2 copy | `kernel_setup_` | predictive back to Welcome; from repair back pops | KS acceptance |
| Install Termux | `kernel/install-termux` | `InstallTermuxViewModel` | `PackageManager` | NotInstalled, Disabled, TooOld, Installed | `install_termux_` | opens F-Droid/GitHub via `openExternal` | KS |
| Reliability (One UI checklist) | `onboarding/reliability`, `kernel/reliability` | `ReliabilityViewModel` | none; `reliability.*Confirmed` | per item confirmed/unconfirmed | `reliability_` | → connect / pop | KS D |
| Connect a model | `setup/connect` | `ConnectModelViewModel` · `ConnectUiState` | `cline auth --provider … --apikey …`, `cline auth cline` (fg), `stat providers.json` poll, `readProviderPresence`, helper `save_provider_settings`+`cline_account fetchMe` when Ready | Idle, SigningIn(Waiting), SavingKey, Failed, AlreadyConfigured, Done | `connect_` | → done; back keeps the sheet rules of SP S7 | KS, SP S7.7 |
| Setup done | `setup/done` | — | none | single | `setup_done_` | → shell (popUpTo root inclusive) | KS |
| Kernel status sheet | sheet over shell | `KernelStatusViewModel` · `KernelStatusUiState` | `hub.status`, `/version`, `cline hub status/stop/upgrade`, `termux-wake-lock/unlock`, events `hub.client.*` | every `KernelState`; other clients; restart phases E.7; mismatch E.9 | `kernel_status_` | sheet collapse | KS E |
| Home | `chat/` (null) | `ChatViewModel` · `ChatUiState.Home` | `session.create` on first send; `settings.list` (slash); `readProviderPresence` (welcome notice) | FirstRun(no provider), Ready, KernelDown, Blocked | `home_`, `composer_` | drawer; back leaves app | HC A, CA C8.4 |
| Chat thread | `chat/{id}` | `ChatViewModel` · `ChatUiState.Session` | `session.get`, `session.messages`, `session.attach`, `session.send_input`, `run.abort`, `session.update`, `session.delete`, events §4.2 | Loading, Seeded, Streaming, AwaitingUser, Interrupted, ReplayTruncated, NotFound, KernelDown | `chat_` | header actions; details sheet; branch sheet (`git branch`/`for-each-ref`/`checkout`) | HC B–E, §9 |
| Composer | docked in chat | `ComposerController` · `ComposerUiState` | `session.send_input {prompt, mode, delivery, imageDataUrls, userFiles}`, `session.update_connection`, `writeAttachment` | Idle, Starting, Busy, ReadOnly(kernel down), Draft, Attachments, Voice* | `composer_`, `attach_` | docked stack order CA §0.4 | CA C1.15 |
| Prompt queue | strip + sheet | `PromptQueueController` · `PromptQueueState` | `session.pending_prompts`, `session.update_pending_prompt`, `session.remove_pending_prompt`, `session.steer_first_pending_prompt`; events `session.pending_prompts`, `session.pending_prompt_submitted` | Empty, Items, Editing, Unavailable | `queue_` | sheet collapse; inline edit back | CA C2 |
| Approval sheet / banner | sheet in chat | `ApprovalController` · `ApprovalUiState` | `approval.requested`, `approval.respond`, `approval.resolved`, `run.abort` | Hidden, Expanded, PartiallyExpanded, Collapsed(banner), Approving, Rejecting, Error, Reconnecting | `approval_` | collapse never rejects | CA C3.11 |
| Ask-question sheet / banner | sheet in chat | `AskQuestionController` · `QuestionUiState` | `capability.requested`, `capability.respond {payload.result}`, `capability.resolved` | Idle, CustomTyped, Sending, Error, Answered, Cancelled, AlreadyResolved, Collapsed, Reconnecting | `question_` | collapse; IME first | CA C4.10 |
| Slash menu | panel in composer | `SuggestionMenuState.Slash` | `settings.list` → skills/workflows; `cat <item.path>`; no `/fork` (A19: the chat's three-dot only) | Hidden, Loading, Populated, Refreshing, Empty, KernelDown | `slash_`, `team_` | back dismisses per token | CA C5.11 |
| Mention menu | panel | `SuggestionMenuState.Mention` | `searchWorkspaceFiles` (RUN_COMMAND bash rg/find pipeline) | Hidden, Searching, Populated, Refreshing, Empty, NoWorkspace, KernelDown, PermissionMissing, Error | `mention_` | back dismisses | CA C6.8 |
| Voice input | mic in composer | `VoiceInputController` · `VoiceState` | none (SpeechRecognizer) | Unavailable, Idle, PermissionMissing, Listening, Processing, Error | `voice_` | back stops listening first | CA C7.8 |
| Sessions drawer | drawer | `SessionsDrawerViewModel` · `SessionsDrawerUiState` | `session.list {limit:200, rootOnly:true}`, `schedule.active/list_executions/list {allWorkspaces:true}`, `session.update`, `session.delete`, events `session.*`, `run.*`, `approval.*` | Loading, Cached, Populated, Empty, KernelDown, ReplayTruncated | `sessions_` | drawer close | SS §8 |
| Sessions screen / search | `sessions`, `search` | `SessionsScreenViewModel`, `SessionSearchViewModel` | as drawer + `session.search {query, limit:50}` | + Searching, NoMatch | `sessions_`, `search_` | pop | SS |
| Session details sheet, delete dialog | sheets | in `ChatViewModel` / drawer VM | `session.get`, `session.update {metadata spread, title, pinned}`, `session.delete` (+ `session.get` verify) | Loading, Ready, Renaming, Deleting, Error | `session_details_` | sheet / dialog | SS F2–F6 |
| Changes | `chat/{id}/changes` / pane | `ChangesViewModel` · `ChangesUiState` | `session.messages` (seed), events `tool.started/updated/finished`, `session.updated`; clipboard; share | FirstRun, Loading, Empty, Populated, Streaming, Error, OfflineCached, OfflineEmpty, PermissionMissing, SessionDeleted | `changes_`, `diff_` | pushed pop / pane close; closes on session change and summary→0 | CT A.11 |
| Tool detail | `chat/{id}/tool/{toolCallId}` / pane | `ToolDetailViewModel` · `ToolDetailUiState` | `session.messages`, `run.proceed_while_running {sessionId, toolCallId}`, `run.abort`, events tool/run/`run.interrupted` | Loading, WaitingForOutput, Running, AwaitingApproval, Completed, Failed, Rejected, Detached, ChildrenLeft, NoResult, Interrupted, History, EmptyOutput, NotFound, Offline, Reconnecting | `tool_` | pop / pane close | CT B.11 |
| Checkpoint dialogs + picker | dialogs/sheet in chat | `CheckpointViewModel` | `session.get {includeSnapshot:true}`, `session.messages`, `session.restore` (atomic ≥ `MIN_PORT_RELEASE_RESTORE`) or workspace-only + `session.create` (split), `session.detach`, `session.list` (lost-reply), `git rev-parse --is-inside-work-tree` | C1/C2: Idle, KernelDown, Busy, ReopenedAfterRefusal, LostCheckpoint; row: Pending, Draining, Error, Success, LostReply; C3: Loading, LoadError, EmptyNoHistory(diagnosis), EmptyNoRows, Populated, Busy, KernelDown | `checkpoint_` | dialog back = cancel; picker sheet | CT C.11 |
| Settings home | `settings` | `SettingsHomeViewModel` · `SettingsHomeUiState` | `readProviderPresence`, `readGlobalSettings`, helper `get_global_settings`/`set_telemetry_opt_out`, `settings.list`, `schedule.list/active`, `/version` | FirstRun, Loading, Populated, Running, Offline, Blocked, Error | `settings_` | list-detail on expanded | SP S1.6 |
| Appearance | `settings/appearance` | `AppearanceViewModel` | none (DataStore, `setComponentEnabledSetting`) | FirstRun, Populated, IconError | `appearance_` | pop | SP S2.5 |
| Notification settings | `settings/notifications` | `NotificationSettingsViewModel` · `NotificationSettingsUiState` | none (permission, channels) | Checking, Granted, NotAsked, Denied, NotRequired, ChannelBlocked, KeepAliveDenied | `notif_` | pop | SP S3.7 |
| Providers list | `settings/providers` | `ProvidersViewModel` · `ProvidersListUiState` | helper `list_provider_catalog`; fallback `fallback_providers.json` + `readProviderPresence` | FirstRun, Loading, HelperStarting, Populated, EmptySearch, Error, HelperUnavailable, KernelDown, Blocked | `providers_` | list-detail scaffold replaces settings on expanded | SP S4.7 |
| Provider detail | `settings/providers/{id}` | same VM · `ProviderDetailUiState` | helper `save_provider_settings`, `list_provider_models`; fallback `cline auth …`; S7 for OAuth | Loading, Populated, Saving, Error, SignInRunning, HelperUnavailable, Blocked, KernelDown, Vanished | `provider_` | pop; `commitPendingDrafts()` on dispose | SP S5.8 |
| Add provider | `settings/providers/add` (route / dialog) | same VM · `AddProviderUiState` | helper `add_provider` | Empty, Dirty, Saving, Error, HelperLost | `add_provider_` | discard guard when dirty | SP S6.3 |
| Sign in with Cline | inline on Connect a model, Provider detail and Account (D17) | `ClineSignInCoordinator` · `SignInState` | `cline auth <id>` (fg + TermuxActivity), `pkill … auth`, `readProviderPresence`, helper `save_provider_settings` + `cline_account fetchMe` | Idle, AlreadySignedIn, WaitingForTermux, SavingKey, Failed(SignIn/ApiKey), Succeeded, Blocked | `signin_` (also `connect_`, `account_` on their cards) | inline; waiting survives leaving the screen | SP S7.7 |
| Model picker + reasoning menu | sheet / menu | `ModelPickerViewModel` · `ModelPickerUiState` | `ProviderCatalogRepository`; helper `list_provider_models {provider}` when Ready; `session.update_connection {providerId, modelId}` / `{thinking, reasoningEffort}` on live sessions | FirstRun(no provider), Loading, Populated, NoModels, Fallback, Busy, KernelDown, Error | `model_picker_`, `reasoning_` | sheet; IME union insets | SP S8.8 |
| Workspace picker + folder browser | sheet / `workspace/browse` | `WorkspacePickerViewModel`, `FolderBrowserViewModel` | `realpath -e`, `find -maxdepth 0 -type d`, `find … -maxdepth 1 -type d`, `find ~/storage -maxdepth 0`, `termux-setup-storage` (fg); `session.list` recents | FirstRun, Loading, Validating, Empty, Error, Blocked, KernelDown, StoragePermissionMissing, Truncated | `workspace_` | browser history back then seekable close | SP S9.8 |
| Customize | `customize?tab=` | `CustomizeViewModel` · `CustomizeUiState` | `settings.list {workspaceRoot, cwd, includePluginTools:true}` (60 s), `settings.toggle {type, path\|name, enabled}`, `cline config tools --json`, `cline config hooks --json`, `cat global-settings.json`, helper `list_mcp_servers`, `uninstall_local_primitive`; CLI uninstall routes; event `settings.changed` | FirstLoad, LoadingWithCache, Populated, Empty, SearchEmpty, LoadError, Partial, Toggling, Uninstalling, Result, Running, Offline, HelperStarting, HelperUnavailable, PermissionMissing | `customize_` | top-level or pushed (two entry kinds) | CR §1.11 |
| MCP server editor | `customize/mcp/edit?name=` | `McpServerEditorViewModel` · `McpEditorUiState` | helper `upsert_mcp_server`, `delete_mcp_server`; fallback `cline mcp install/uninstall`, `settings.toggle {type:"mcp"}` | Create, Edit, EditWithMetadata, Dirty, ValidationError, Saving, SaveError, HelperStarting, Fallback, KernelDown, Deleting | `mcp_` | discard guard; consume while saving | CR §2.11 |
| Marketplace list / detail | `marketplace`, `marketplace/entry/{type}/{id}` | `MarketplaceViewModel` · `MarketplaceUiState` | `GET catalog.json`; helper `list_marketplace_installed_entries`, `install_marketplace_entry`, `uninstall_marketplace_entry` (300 s); fallback `settings.list` + CLI installs | NoSelection, Loading, CatalogError, Stale, Populated, FilteredEmpty, Checking, Installing, Uninstalling, Done, Failed, HelperStarting, HelperUnavailable, KernelDown, Offline | `marketplace_` | list → pushed detail / list-detail | CR §3.11 |
| Schedules list / editor / details | `schedules`, `schedules/edit`, `schedules/{id}` | `SchedulesViewModel`, `ScheduleEditorViewModel` | `schedule.list/active/upcoming/list_executions/get/create/update/enable/disable/trigger/delete` (all `allWorkspaces:true`); events `schedule.execution_completed/failed`; `test -d <ws>` | Loading, Refreshing, Empty, Populated, RowBusy, Triggering, ActiveRun, Saving, ValidationError, Error, KernelDown, ReducedAuthority, NoSelection, KeepAliveOff | `schedule_` | top-level or pushed; editor discard guard | CR §4.11 |
| Account | `account?tab=` | `AccountViewModel` · `AccountUiState` | helper `cline_account {action:"clineAccount", operation: fetchMe\|fetchBalance\|fetchUserOrganizations\|fetchOrganizationBalance\|fetchUsageTransactions\|fetchOrganizationUsageTransactions\|fetchPaymentTransactions\|switchAccount}`, `save_provider_settings` (sign out); S7 | Loading, SignedOut, Populated, Switching, TabLoading, TabEmpty, Error, TokenExpired, SigningOut, HelperUnavailable, OlderHelper, Blocked | `account_` | pop; tabs in SavedStateHandle | CR §5.11 |
| Agents roster | sheet over chat | `AgentsRosterViewModel` · `AgentsRosterUiState` | `session.list {limit:200, rootOnly:false}` (+ `limit:1000` once), `session.messages` per child, event `team.progress` | Loading, Waiting, NoneRecorded, Populated, RefreshFailed, KernelDown | `agents_` | row → `chat/{childId}` after dismiss | CR §6.10 |
| About | `about` | `AboutViewModel` · `AboutUiState` | `readlink …/current`, `cline --version`, `/health` (cached), bundled `CHANGELOG.md` | Checking, Populated, KernelDown, Blocked, NotesLoading, NotesError, NoWhatsNew | `about_` | pop | CR §7.9 |
| What's new | dialog | `WhatsNewRepository` | none | Pending, Replay(from About) | `whats_new_` | close marks seen (shell only) | CR §8.5 |
| Export diagnostics | `about/diagnostics` | `ExportDiagnosticsViewModel` · `ExportDiagnosticsUiState` | `session.list {limit:200, rootOnly:true}`, `session.get`, `cline doctor --json`, `tail -c 49152` of two logs, `/health`, `/version` | LoadingSessions, Populated, NoSessions, HubDown, Exporting, Error, Done | `diagnostics_` | consume while exporting | CR §9.7 |
| Licenses | `about/licenses` | — | none (assets) | Populated | `licenses_` | pop | CR §10 |
| System notifications | `ClineNotifier` | — | events `run.completed/failed`, `approval.requested/resolved`, `capability.requested/resolved`, `session.pending_prompts` | per CR §11.3 matrix | `notif_` | tap → `chat/{id}?sheet=` | CR §11.9 |
| Foreground notification | `KernelForegroundService` | `KeepAlivePolicy` | `run.abort` (Stop agent); `kernel.disconnect()` | Working, WaitingApproval, WaitingAnswer, Several, ConnectedIdle, Reconnecting, Stopping, Denied | `kernel_notification_` | tap → chat / Home | CR §12.6 |

---

## 14. Cross-cutting rules the code must enforce

- **Liveness before sending** (SP S8.3): `SessionState.live_` is true only after a
  successful `session.attach` on the current socket; reconnect and `run.interrupted` reset
  it; a not-live session stores pending connection changes and resumes on the next send.
- **`session.update` is wholesale** (HF): always built by `HubPayloads.sessionUpdate(current
  metadata, …)`.
- **Resume keeps `source`**; fork/edit metadata strips `checkpoint, fork,
  restoredFromSessionId, restoredCheckpointRunCount, pid, messagesPath, usage, totalCost`
  (CT §1.5.2).
- **Every `session.create`** sends `checkpoint.enabled = true`, `metadata.source = "android"`,
  the ask-question contribution, `enableSpawnAgent`/`enableAgentTeams`, tool policies from
  the session's auto-approve value; never `apiKey`.
- **`run.started` for queued sends** (`run.enqueued`, `pending_prompt_submitted`) never
  opens a `TurnRecord`.
- **Unread** is capped by `sessions.lastDisconnectAt`; annotation-only updates (pin,
  rename) are not activity.
- **Notifications and keep-alive act only on `KnownSessions`.**
- **Attachments**: images ≤ 5, ≤ 2048 px, 3.75 MB each / 6 MB per send; text files ≤ 1 MB
  through stdin chunks, never arguments; NUL check before send.
- **Clipboard**: secrets via `SecureClipboard` only; file paths and ids as plain clips with
  a `ClipDescription` label; API ≤ 32 shows the "Copied" snackbar, API 33+ relies on the
  system UI.
- **No `Color(0x…)` outside `ui/theme/`**, no string literals in composables, Material
  Symbols Rounded only, 48dp targets, `heightIn(min)` never fixed heights. The one exception
  to the 48dp target is the transcript's dense disclosure rows (tool calls, thinking, "Worked
  for…"), 40dp tall and 40dp to touch (decided A24), whose sizes all come from
  `DisclosureRowTokens` (`ui/chat/rows/TranscriptContext.kt`): height, icon, spinner, label
  style, block gap and rail inset. No row carries its own numbers, so the 44dp fallback is a
  change of `DisclosureRowTokens.Height` alone.
- **One place per function** (decided A19, A20). A typed command and a menu entry never do
  the same thing, and no function gets a second menu entry. The slash popup holds `/team`, the
  owner's skills and workflows (plugin commands once the Hub lists them, `/compact` once a
  kernel has it) and nothing that a menu already offers: no `/fork`, `/clear` or `/undo`.
  A function may sit on a row and in a menu. Three exceptions are stated and stay: (1) Restore
  checkpoint on the user message row and in the three-dot's checkpoint picker; (2) the session
  commands (pin, rename, fork, details, delete) in the drawer's row menu, as the desktop has
  them, and in the chat's three-dot for the open session; (3) prompt history by the fling on
  the empty field and by the attach sheet's "Recent prompts" row. A new entry point that is not
  one of these three needs a decision first.
- **Tool rows start folded** (decided A23). No tool row opens by itself; the desktop's
  `shouldAutoOpen` (running output, diffs, media, submit text) is not ported. A row opens only
  when tapped and keeps that state per row (`rememberSaveable(renderKey)`) while its output
  grows and after its run ends.

---

## 15. Decisions that override spec text or existing code

| # | Topic | Decision |
| --- | --- | --- |
| D1 | Transcript model | `ChatMessage` (HC §1.3) is canonical; `SessionReducer.SessionThread` becomes `SessionFacts` without `TranscriptItem`; CT's references to `TranscriptItem.ToolCall`/`CommandExecution` map to `ToolRecord`/`CommandBuffer` in `SessionFacts.tools` |
| D2 | Cursors | One persisted global cursor (`sessions.globalCursor`), replay budget 2000, per-session subscribes without `sinceSequence`; KC step 6's per-session cursors are **not** implemented; existing `hub.cursor.*` keys migrated then deleted |
| D3 | Kernel state | KS §0.3 model replaces `KernelCoordinator.KernelState`; `cline --version` probe replaced by `cat VERSION`; `KernelGate` is the only kernel projection screens read |
| D4 | `checkpoint.enabled` | `true` on every create/resume/fork/restore (CT §0.3 corrects HF and the current `HubPayloads` comment) |
| D5 | Secrets | `ProvidersFile` deleted; `ConfiguredProviders` computed in the bridge; `TermuxCommand.toString()` redacts; `--room-secret` dashboard; no logging interceptor |
| D6 | Package layout | `core/kernel`, `data`, `service`, `ui` per §1; spec file paths are mapped in §1.2; manifest component names updated accordingly |
| D7 | FileProvider | one authority (CR §9.5) for share, diagnostics and camera; CT A.7's `dev.cline.termux.fileprovider` is this one. Since batch 1 it is `${applicationId}.files` (`ShareFiles.AUTHORITY = BuildConfig.APPLICATION_ID + ".files"`), so `io.github.ichouchiang.clinetermux.files` for the release and `io.github.ichouchiang.clinetermux.debug.files` for the debug build (D21) |
| D8 | External links | one helper `openExternal` in `ui/components/ExternalLinks.kt` (KS `openUrl` and CR `OpenExternal.kt` merged) |
| D9 | Helper grace | 5 s (SP §0.3) wins over CR's "30 s grace" mentions |
| D10 | Notification tap | intent extras (CA §0.6), no `cline://` scheme (CR §11.0 over SP S3.3) |
| D11 | Keep-connected switch | DataStore `notifications.keepConnected` (false) surfaced on SP S3's "Background connection" row (CR OQ C-12 default) |
| D12 | `KernelAvailability` (CA) | replaced by `KernelGate` (KS §0.10) |
| D13 | Composer mode keys | `composer.mode.default` / `composer.mode.<sessionId>` (CA) replace the older `model.*` mode notion; model selection moves to `model.selection` |
| D14 | Two reducers | `SessionFactsReducer` and `ChatProjector` are separate pure functions fed by the same dispatch; fixture tests assert both so they cannot diverge silently |
| D15 | Event fan-out | exactly one collector of `HubClient.events` (`SessionStore.pump`); observers derive from store flows |
| D16 | No Room | DataStore + JSON files are sufficient; revisit only if `KnownSessions`/unread grow beyond a few KB |
| D17 | Sign in with Cline (SP S7) | no `SignInWithClineSheet`. S7.1 lets an entry point render the waiting state inline, and every entry point does: Connect a model (`ui/onboarding/ConnectModelScreen`, Cline card), Provider detail (`ui/providers/ProviderDetailScreen.SignInCard`, oauth providers) and Account (`ui/account/AccountScreen.SignedOutCard`); the chat credential error and the Welcome notice reach S7 through Account and Connect a model. All three drive the one `ClineSignInCoordinator` and `SignInState`, and each maps `SignInFailure` to the S7 failure sentences in its own lane's strings (`connect_`, `signin_` via `ui/account/FailureText.signInFailureText`, `account_`), so the cards show the same copy without a shared sheet |
| D18 | Landscape IME composer (CA C1.4 step 6, C1.15 item 17) | Landscape IME composer: 2-line cap and chips hidden on height-compact windows; lead decision 2026-10-06. While the IME is open on a height-compact window the field is capped at 2 lines (`ui/composer/Composer.composerMaxLines`) and the settings chip row is not composed (`showsSettingsRow`), on top of the queue strip rule; everything returns when the IME closes or the window is not height-compact. The Samsung keyboard takes about 233 of 412dp on the S25U, so the 3-line cap alone left no room for the approval banner and the transcript. With the IME open and an approval pending, the banner (56dp) and the composer fill the ≈ 136dp the keyboard leaves and no transcript row fits: the approval banner has priority over transcript rows, because it is the context the user is replying to, and no further layout change is made (lead decision 2026-10-06). With no approval pending at least one transcript row stays visible. The same priority holds with the IME closed: the bounded dock (`ui/chat/DockRules.maxHeight`) takes 60 % of the chat pane, or the whole pane while an approval, question or stall banner docks. With the IME open the stack stays bounded to the whole pane while a banner docks (trial row 13, 2026-10-08): the app bar steps aside while typing, and the unbounded stack had drawn the stall notice under the status bar; bounded, the banner stays inside the safe area and whatever does not fit scrolls inside the stack, starting at the composer. There the stall notice also takes one row of at least 56dp, icon, text and both actions side by side (`StallBanner(singleRow)`), whenever its text fits on one line beside the actions (S25U landscape); otherwise (narrow windows, large font scales) it keeps its stacked form inside the bound |
| D19 | Parity rule (owner, 2026-10-07) | functions exact and kernel-visible behaviour identical to the desktop; Material 3 phone presentation; improvements only where the desktop offers no control and the terminal does, or where the desktop's behaviour is unsafe on a phone; see docs/decided.md P1 |
| D20 | Port principle (owner, 2026-10-07) | the port changes the kernel only for platform fixes; protocol and feature changes go upstream first (docs/decided.md D20) |
| D21 | Build identity (batch 1, R1) | debug = `io.github.ichouchiang.clinetermux.debug` (`applicationIdSuffix ".debug"`, version `<versionName>-debug`, label "Cline Termux Debug" since 2026-10-16), so it installs beside the owner's `io.github.ichouchiang.clinetermux`; release = `io.github.ichouchiang.clinetermux`, signed with the project release key kept outside the repo (docs/release-signing.md; the debug key is the fallback when none of the four signing properties is set, and a partial set fails the build, CR2); `-Pcline.releaseIdSuffix=.bench` gives `io.github.ichouchiang.clinetermux.bench` for release-build measurements. The activity keeps its class, so components are `<package>/dev.cline.termux.MainActivity`. `scripts/install.sh` refuses any APK whose package is exactly `io.github.ichouchiang.clinetermux`. Debug builds take a Hub port override beside the token override (`--ei port`), so the bench Hub is reached on the phone's 25498 and the real Hub on 25463 is never shadowed |
| D22 | Approvals (A1) | the kernel's global `toolAutoApprove` is the only approval setting; sessions follow it at run boundaries by restart; the runtime's created policy outranks the Hub's later records (§4.8) |
| D23 | Compaction (G1, A11 row 2) | every create path sends `sessionConfig.compaction` from the global `compactionEnabled` / `compactionStrategy` (default `{enabled:true}`); every detach-then-create carries `initialCompactionState` read just before the detach. A compaction change does not restart an open idle session (applies at its next create) |
| D24 | Move to workspace (A11.8) | cancelled 2026-10-08 and removed in batch 2: no client has a move, and it was the only path that deleted a session to recreate it. Its code, menu entries (drawer row menu, chat three-dot), picker and folder-browser modes, recovery copy and the subagent/team refusal are gone; Delete session (`session.delete`) is unchanged. A batch-1 install may still hold `files/move-recovery/` if a move never finished; the app no longer reads or deletes it |
| D25 | Contributions and hooks (G2, A5, A11 row 4) | contributions: askQuestion, mistakeLimit, and `switch_to_act_mode` for plan runtimes only; no hook contribution and no `configExtensions` (the Hub runs hook files itself; F1). The mistake-limit guidance goes back in the decision (CLI shape), without the desktop's hold hooks (A3 hazard). The act switch rebuilds the session in act mode before the continuation |
| D26 | Stall and Stop (A4) | the stall notice follows `run.heartbeat` with no other session event; Stop gives up at the protocol's 30 s command timeout (`HubCommands.DEFAULT_TIMEOUT_MS`) and offers Restart kernel, and a `run.abort` request that fails says so at once, from the composer as from the approval sheet; that message stands only while the run it was about does: it goes when that run ends, another run takes its place, a later Stop is accepted, the session is deleted or the user moves to another session (trial row 11, `data/session/StopFailure`); the chat's Restart kernel opens the Kernel status sheet with a one-shot restart request (its pre-check and confirm) |
| D27 | Prompt history (A11.6) | the terminal's `user_input_history.jsonl` is the store; only a direct send the Hub accepted is recorded, as the terminal records a prompt only when it was neither queued nor steered (`if (!delivery)`). Direction (A18, batch 2): on the empty field a left-to-right fling steps to the previous (older) prompt and right-to-left to the next (newer) one, leaving browsing past the newest; the mapping lives only in `HistoryFlingRules.classify` and mirrors in a right-to-left layout. The browse chip is a 24dp tonal pill (`surfaceContainerHighest`) with the label and a × only, no outline and no history glyph |
| D28 | Fork before this turn (A13) | removed in batch 2 (A16, A19): the one fork is the whole session (`SessionActions.fork`), from the chat three-dot and the drawer row menu, without `fork.beforeRunCount`. No message-row fork, no `/fork` (a typed `/fork` goes to the model as text; a skill or workflow named `fork` now shows in the popup). The user row's Edit keeps the checkpoint flow's `beforeRunCount` (`HubPayloads.restoreMetadata(runCount)`) |
| D29 | Checkpoint `createdAt` | a restored or forked session's inherited history is written with numeric `createdAt` (core's `createRestoredCheckpointMetadata`) and read with core's `Number()` rule, numeric strings included (older app builds wrote strings) |
| D30 | Default folder (A22, trial row 17) | a session started with no folder chosen gets the Termux home (`TERMUX_HOME`) as `workspaceRoot` and `cwd` (`ChatViewModel.homeConfig`); the app creates and writes nothing there. Home's chip reads "Home" for that state and the drawer's project mode groups those sessions under "Home" by path (the recent-workspaces catalog leaves the home out). "Chat" stays only for sessions whose path is the kernel's chat workspace (imported). A deliberate deviation from the desktop, which sends no folder and reads "Chat"; the app used to send the chat workspace path without creating it, so every `run_commands` failed with `ENOENT` |
| D31 | One place per function (A19, A20) | see §14: typed commands and menu entries never duplicate each other; three stated exceptions (restore on the row and in the picker, session commands in the drawer row menu and the chat three-dot, prompt history by fling and by the attach sheet) |
| D32 | Tool rows (A23, A24) | every tool row starts folded and opens only on a tap; the dense disclosure rows are 40dp with 18dp icons, a 14dp spinner, `bodySmall` labels, a 4dp block gap and the rail at 9dp, all from `DisclosureRowTokens`; the desktop's 28px rows and auto-open differ by decision (design-system §9) |
| D33 | Stop notice and Interrupted (A17, A21) | after the app's accepted `stop` to `mistake_limit.decide` (the user's "Stop this run" or the 5-minute timeout) the transcript gets an app-side notice row at the end of the run it stopped (trial row 21), stored in `sessions.stopNotices`; the "Try a different approach" fallback to stop (guidance undeliverable) gets none. A history tool call with no result in a finished turn is drawn "Interrupted: <specific>" with the warning glyph; a live call stays Running |
| D34 | Session list and titles (trial rows 8, 16) | every `session.list` reply decides which rows exist (a row inserted while the request was in flight survives one reply); replayed events at or before the pre-list sequence, or for a session forgotten since, cannot revive it (up to 500 forget marks). A create-time title overwritten by the kernel at the first prompt is put back once by `CreateTitleKeeper` (~1 s after the run, only if the record holds exactly the prompt-derived title); the pending note is in memory only |
| D35 | Teams and skills switches | read from `global-settings.json` `disabledTools` by `cat` at the send that needs it (`DisabledToolsSource`), as the desktop's `readGlobalSettings()`; never from `settings.list` (core 0.0.91 lists plugin tools only). An unreadable file disables nothing. `/team` checks run usage, disabled, mode, as `rewriteDesktopTeamPrompt` |
| D36 | Full commands (trial row 18) | the `$` lines, the empty-output JSON fallback and the tool details print `commands[i]` from the call's input (`data/tools/ExecutedCommands.kt`, a port of the kernel's normalisation as of cli-v3.0.69); the result's 200-character `query` preview is used only when the input is missing or does not match, with "…" in place of the kernel's truncation note |

---

## 16. Open questions for the owner

1. **`MIN_PORT_RELEASE_RESTORE`** (CT Q2): accept kernel fix K1 into the port and name the
   release; until then the constant is `null` and only the split restore path exists.
2. **Hub left running in the background** (KC, CLAUDE.local.md): the default of
   `notifications.keepConnected` and whether "Stop kernel" is offered on exit depend on it.
3. ~~**Fixture 07** must be recorded (CT Q1)~~ — recorded 2026-10-07 on the bench (core 0.0.91)
   and replayed by `CheckpointRestoreFixtureTest` (docs/08).
4. **Dependency versions**: `core-ktx 1.17.0`, `browser 1.9.0`, `lifecycle-process 2.11.0`,
   `exifinterface 1.4.1`, `kotlinx-collections-immutable 0.4.0`, `turbine 1.2.1`,
   `mockwebserver3 5.5.0`, `robolectric 4.16`, `androidx.test` 1.7.0/1.3.0 are to be
   confirmed by the first proxied `./scripts/build.sh`; adjust patch versions if resolution
   fails.
5. **Which sessions notify** (CR C-15): `KnownSessions` only is the implemented default.
6. **Voice input setting** (CA OQ 2): mic shown whenever a recogniser exists; no switch.
7. **Subagent approvals in the parent sheet** (CA OQ 3): v1 shows only the open session's
   approvals; children raise their own status dot.
8. **Device verifications still pending** that the architecture assumes: `detachable`
   flipping on the device (CT Q3), 1 MB stdin attachments (CA OQ 7), 8 MiB image frames
   on the device Bun (CA OQ 11), foreground RUN_COMMAND result delivery (SP 13.4),
   `termux-setup-storage` from a brought-forward Termux (SP 13.7), Bun.serve dashboard on
   the device (docs OQ 3: decides whether fallback mode is the norm).
