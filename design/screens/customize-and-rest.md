# Screen spec: Customize, MCP, marketplace, schedules, account, agents, about

Status: design-phase contract, 2026-10-03 (revised the same day after a three-reviewer pass). Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`).
Builds on `design/design-system.md` (cited as **DS §n**, components as **DS #n**) and on
the shared rules of `design/screens/settings-providers.md` (cited as **SP §n**): the
settings helper `SettingsHelper` (SP §0.3), fallback mode (SP §0.4), `KernelBlockedCard`
(SP §0.5), secrets (SP §0.6) and the common accessibility rules (SP §0.8); and on
`design/screens/onboarding-kernel.md` (cited as **KS §n**) for `KernelGate`, `KernelBanner`,
`KernelStatePanel`, the kernel status sheet (KS E), the One UI checklist (KS D) and the
kernel persistence keys (KS §0.8); and on `design/screens/composer-approvals.md` (**CA §n**)
for the deep-link contract (CA §0.6) and the foreground-service hand-off (CA §0.7). Nothing
here redefines them. Kernel names are quoted from `docs/03-feature-matrix.md` (**FM**),
`docs/02-kernel-contract.md` (**KC**), `docs/05-open-questions.md` (**OQ n**) and the
recorded traffic in `docs/08-hub-fixtures.md` (**HF**).

Screens in this spec:

| # | Screen | Route / host | Tier (FM) |
| --- | --- | --- | --- |
| C1 | Customize (tabs Tools / Plugins / Skills / Rules / MCP / Hooks) | `customize?tab={tools\|plugins\|skills\|rules\|mcp\|hooks}` (top-level destination, DS §5.2; also Settings row "Installed", §0.2) | 4 |
| C2 | MCP server editor (add / edit) + delete confirm | `customize/mcp/edit?name={name?}` (full-screen `composable()` route on compact/medium, `BasicAlertDialog` on expanded; DS #16 as amended by SP §11) | 4 |
| C3 | Marketplace list | `marketplace?type={skill\|mcp\|plugin?}&tag={tagId?}` | 4 |
| C4 | Marketplace entry detail | `marketplace/entry/{type}/{id}` (pushed on compact/medium; detail pane on expanded) | 4 |
| C5 | Schedules list | `schedules` (top-level destination, DS §5.2; also Settings row "Schedules") | 4 |
| C6 | Schedule editor (create / edit) | `schedules/edit?id={scheduleId?}&template={templateId?}` (full-screen route on compact/medium, `BasicAlertDialog` on expanded) | 4 |
| C7 | Schedule details with runs | `schedules/{scheduleId}` (pushed page on compact/medium; detail pane of the Schedules list-detail scaffold on expanded, §0.6) | 4 |
| C8 | Account (Overview / Usage / Billing) | `account?tab={overview\|usage\|billing}` | 4 |
| C9 | Agents roster sheet | `AgentsRosterSheet` overlay on `chat/{sessionId}` | 4 |
| C10 | About | `about` | 4 |
| C11 | What's new dialog | `WhatsNewDialog` overlay (shell on launch, and About) | 4 |
| C12 | Export diagnostics | `about/diagnostics` (full-screen route on compact/medium, `BasicAlertDialog` on expanded) | 4 |
| C13 | Open source licenses | `about/licenses` | 4 (Android-only, required for Geist Mono OFL, DS §2.2) |
| C14 | System notifications (event matrix, channels, posting rules) | `ClineNotifier` + the Settings page `settings/notifications` (shared with SP S3, see §11.0) | 1 |
| C15 | Foreground "Cline is working..." notification | `KernelForegroundService` (exists in `kernel/`) | 1 |

Not in this spec (linked from here): Settings home, Providers, Sign in with Cline flow
(SP S7, reused by C8), Workspace picker (SP S9, reused by C6 and C1), Kernel settings,
Kernel status sheet, Kernel setup (first run), chat screen and its header (the agents chip
that opens C9 and the "Main Agent Session" chip are in the chat spec; the sheet is here).

Desktop sources read for this spec (under
`~/workspace/cline-termux-v3-phone/apps/examples/desktop-app/webview/` unless noted):

- `components/views/settings/customize-view.tsx` (tab order, labels, counts from
  `list_user_instruction_configs`, `settings.changed` subscription, Marketplace header
  button, page description)
- `components/views/settings/extensions-view.tsx` (`CustomizationSectionView`: every
  tab's cards, badges, switches, uninstall/copy-path menu, tools search and
  "Enable all / Disable all", hooks execution stats, rules/plugins/hooks scope split,
  error and "Partial results" banners, every empty string, 60 s list cache)
- `components/views/settings/mcp-view.tsx` (`McpServersContent`: server card, transport
  labels, details lines, env redaction, OAuth box, editor dialog fields, validation
  strings, delete `AlertDialog`)
- `components/views/marketplace-explorer-view.tsx` (directory, `TYPE_META` labels and
  blurbs, maturity order, type chips with counts, category menu, detail pane, install /
  uninstall states, `INSTALL_TIMEOUT_MS = 300_000`),
  `components/views/marketplace-view.tsx` (embedded "installed" variant used by the
  Customize tabs, page details and empty strings, "Environment setup needed" guidance,
  "Marketplace setup instructions"), `components/views/marketplace-list-row.tsx`,
  `lib/marketplace.ts` (catalog schema and parser)
- `components/views/settings/routine-view.tsx` (`RoutineSchedulesContent`: cards, run
  now flow with the 15 s session poll, pause/resume, delete dialog, editor fields and
  validation strings, details dialog with runs), `routine-templates.ts`,
  `lib/routine-schedule-cron.ts` (`preserveRoutineCron`),
  `lib/routine-schedule-timezone.ts`
- `components/views/settings/account-view.tsx` (tabs, signed-out card, profile,
  balance, organizations and switch, usage and billing tables, URLs, formatters,
  auth-error classification), `lib/cline-account-state.ts`
- `components/agent-header.tsx` (`AgentActivityStatus`, `AgentRoster`,
  `AgentRosterRow`, state icons and classes, `SubagentSessionBadge`),
  `lib/session-agents.ts` (`describeAgentActivity`, `agentEntryState`,
  `mergeAgentActivity`, tool-derived tally)
- `components/views/settings/about-view.tsx` (rows, release notes, report issue),
  `components/whats-new-dialog.tsx`, `lib/whats-new.ts`, `lib/whats-new-content.ts`,
  `lib/changelog.ts`, `lib/app-channel.ts` (`productNameForVersion`, `isBetaVersion`),
  `components/views/settings/export-diagnostics-dialog.tsx`, `app/page.tsx` (What's new
  shown once after onboarding, "See all changes", diagnostics entry points),
  `components/views/settings/settings-view.tsx` ("Diagnostics" row),
  `lib/provider-connection.ts` (`OAUTH_LOGIN_TIMEOUT_MS`), `sidecar/diagnostics.ts`
  (report sections, log tails, `redactDiagnosticsText`, file name)
- `components/views/settings/notification-settings.tsx`, `lib/desktop-notifications.ts`
  (event types, defaults, copy, dedupe, suppression rules)
- `components/views/settings/sections.ts`, `components/views/page-layout.tsx`
  (`PageHeader`, `PageEmptyState`, `CommandBadge`),
  `components/views/settings/web-search-provider-guidance.tsx` (decided not to port, §1.8)
- Kernel side, to confirm what each channel can do:
  `apps/cline-hub/src/server/desktop-commands.ts`, `server/user-instructions.ts`,
  `server/mcp.ts`, `server/marketplace.ts` (install commands, catalog URL
  `https://cline.github.io/marketplace/catalog.json`), `server/schedules.ts` (routine
  command → Hub `schedule.*` mapping), `apps/examples/desktop-app/sidecar/session-data/agents.ts`
  (`listSessionAgents`, `deriveLastAction`), `apps/cli/src/main.ts` + `commands/config.ts`
  (`cline config tools|hooks --json`, `cline mcp install|uninstall`, `cline plugin
  install|uninstall`), `sdk/packages/core/src/settings/{types,settings-service}.ts`
  (`settings.list` / `settings.toggle` shapes), `sdk/packages/core/src/hub/server/hub-server-transport.ts`
  (settings and schedule handlers, which schedule events are published),
  `sdk/packages/core/src/cron/service/schedule-command-service.ts` (workspace scoping,
  `allWorkspaces`), `sdk/packages/shared/src/hub.ts` (`ScheduleRecord`,
  `ScheduleExecutionRecord`, `ONE_TIME_SCHEDULE_CRON_PATTERN = "0"`,
  `ONE_TIME_SCHEDULE_RUN_AT_METADATA_KEY = "__hubScheduleRunAt"`),
  `sdk/packages/core/src/hub/server/hub-session-records.ts` (child-session metadata:
  `parentSessionId`, `agentId`, `prompt`, `messagesPath`),
  `sdk/packages/core/src/hub/server/handlers/session-handlers.ts` (`session.list rootOnly`,
  `session.messages`), `sdk/packages/core/src/account/types.ts` (account reply shapes).
- Voice app (`~/workspace/cline-termux-voice/android/app/src/main/kotlin/dev/cline/voice`):
  `VoiceService.kt` (channels, `startForegroundCompat`, status notification rebuild on a
  flow, per-session notification ids `CHAT_ID + 1 + (sessionId.hashCode() and 0x7fff)`,
  `Tone.notificationColor`), `ui/Components.kt` (`PageScaffold`, `TextInput`,
  `LocalSnackbarHost`), `ui/settings/Prefs.kt`, `ui/settings/SubPages.kt`.

---

## 0. Shared rules for every screen in this spec

### 0.1 Code layout

```
app/src/main/kotlin/dev/cline/termux/
  customize/CustomizeScreen.kt, CustomizeViewModel.kt
  customize/CustomizeRepository.kt         settings.list / settings.toggle + cline config tools|hooks --json, 60 s cache
  customize/tabs/ToolsTab.kt, PluginsTab.kt, SkillsTab.kt, RulesTab.kt, McpTab.kt, HooksTab.kt
  customize/ExtensionCards.kt              ExtensionCard, ScopeBadge, TagBadge, ActionMessage (Appendix A)
  customize/mcp/McpRepository.kt           helper list/upsert/delete + file read + CLI fallback
  customize/mcp/McpServerEditor.kt, McpServerEditorViewModel.kt
  marketplace/MarketplaceRepository.kt     catalog fetch (OkHttp, cached), installed keys, install/uninstall routing
  marketplace/MarketplaceScreen.kt, MarketplaceDetailScreen.kt, MarketplaceViewModel.kt
  marketplace/MarketplaceTypeMeta.kt       port of TYPE_META + MATURITY_ORDER
  schedules/SchedulesRepository.kt         Hub schedule.* with allWorkspaces:true
  schedules/SchedulesScreen.kt, ScheduleEditor.kt, ScheduleDetailsScreen.kt, SchedulesViewModel.kt
  schedules/ScheduleFormat.kt              port of formatScheduleTrigger, parseCronPattern, buildCronPattern,
                                           preserveRoutineCron, routineScheduleTimezone, formatExecutionResult
  schedules/RoutineTemplates.kt            ROUTINE_TEMPLATES copied verbatim (prompts included)
  account/AccountScreen.kt, AccountViewModel.kt    (ClineSignInCoordinator is SP S7)
  agents/AgentsRosterSheet.kt, AgentsRosterViewModel.kt
  agents/SessionAgents.kt                  port of lib/session-agents.ts (all functions, with its tests)
  about/AboutScreen.kt, AboutViewModel.kt, WhatsNewDialog.kt, WhatsNewContent.kt
  about/DiagnosticsExportDialog.kt, ExportDiagnosticsViewModel.kt, LicensesScreen.kt
  diagnostics/DiagnosticsBuilder.kt, Redactor.kt   (§9.4, §9.6; Redactor shared with C1–C3 CLI output)
  notifications/NotificationPrefs.kt, ClineNotifier.kt, NotificationChannels.kt   (names shared with SP §0.1)
  kernel/KernelForegroundService.kt        exists; extended per §12 (specialUse, KC), driven by KeepAlivePolicy.kt
  notifications/KeepAlivePolicy.kt         the hold rule of CA §0.7 + §12.2
  notifications/NotificationRouter.kt      PendingIntents + MainActivity.onNewIntent handling
app/src/main/assets/whats_new.json         (none; WhatsNewContent.kt holds the entries in code, like the desktop)
app/src/main/assets/CHANGELOG.md           bundled app release notes for About (C10)
app/src/main/assets/licenses/index.json + *.txt   license list and texts for C13
```

Code adapted from the voice app carries `// Adapted from cline-termux-voice (same owner): <path>` (DS header).

### 0.2 Navigation graph and back

```
customize ──┬─ customize/mcp/edit?name=           (editor route, see "Editors" below)
            └─ marketplace ── marketplace/entry/{type}/{id}
schedules ──┬─ schedules/{scheduleId} ── schedules/edit?id=
            └─ schedules/edit?id=&template=       (editor route)
account
about ──────┬─ about/diagnostics                  (editor route)
            ├─ about/licenses
            └─ WhatsNewDialog (overlay, not a route)
chat/{sessionId} ── AgentsRosterSheet (overlay) ── chat/{childSessionId}
settings/notifications                            (SP S3 host; C14 content)
```

**Top-level vs pushed (one model, two entry kinds).** `customize` and `schedules` are
top-level destinations (DS §5.2: drawer items on compact, rail items on medium and
expanded). The same composable is also reachable as a pushed page from Settings:

| Entered from | Back stack | Navigation icon (compact) | Transition | Back |
| --- | --- | --- | --- | --- |
| Drawer item "Customize" / "Schedules" (compact), rail item (medium/expanded) | top-level switch: `navigate(route) { popUpTo(chat root) { saveState = true }; launchSingleTop = true; restoreState = true }` | `menu`, CD "Open sessions" (the drawer CD of DS §5.2 / home-chat), opens the `ModalNavigationDrawer` | fade-through (DS §6) | returns to Chats (the chat root kept under every top-level destination), never exits the app while Chats is below; predictive back previews the Chats route |
| Settings row "Installed" / "Schedules" | pushed on top of `settings` | `arrow_back`, CD "Back" | shared axis X, seekable (DS §5.8 item 9) | pops to `settings` |

On medium and expanded the rail is always visible, so the top-level entry shows **no**
navigation icon (as the home-chat medium rule) and the pushed entry shows the back arrow.
The ported voice `PageScaffold` gets a `navigationIcon: @Composable (() -> Unit)?` slot
(replacing its `onBack`-only arrow; Appendix A) so both entries reuse one screen. Two back
stack entries of the same composable may exist (one top-level, one pushed); each has its
own `SavedStateHandle` and ViewModel.

The drawer swipe gesture stays as DS §5.2 / sessions §2.8 define it (chat root only):
Customize hosts a horizontal `HorizontalPager`, and the screen edge is system back under
gesture navigation, so on these pages the drawer opens from the `menu` button only.
*Reviewed: the request to enable drawer gestures here was not applied for that reason.*

- Settings row "Marketplace" opens `marketplace` directly (pushed).
- **Editors** (`customize/mcp/edit`, `schedules/edit`, `about/diagnostics`): per the DS #16
  amendment in SP §11 (and SP S6's reasons), on **compact and medium** they are ordinary
  `composable()` destinations rendered full-screen (M3 full-screen dialog anatomy:
  `Scaffold` + small `TopAppBar(navigationIcon = close (CD "Close"), title, actions =
  TextButton(confirm))`, no footer). Because they are in the activity window they get
  `enableEdgeToEdge()`, `imePadding`, `FLAG_SECURE` and the seekable predictive back.
  Enter/exit use shared axis Y + fade (`ClineMotion`, DS §6), seekable on back. On
  **expanded** they are `BasicAlertDialog` (560dp, footer buttons only) hosted by a
  `dialog()` destination with `DialogProperties(usePlatformDefaultWidth = false,
  decorFitsSystemWindows = false)` and `Modifier.imePadding()` on the dialog content; the
  route is the same, the destination type is chosen from the window class when the graph
  is built (`if (expanded) dialog(route) else composable(route)`; a window-class change
  while an editor is open rebuilds the graph and the editor keeps its state through its
  ViewModel).
- Editor back: with unsaved edits a `BackHandler(enabled = dirty && !saving)` shows the
  discard confirm `AlertDialog` ("Discard changes?" §2.5); with no edits back closes. While
  a save is in flight `BackHandler(enabled = saving)` consumes back and ignores it (desktop
  `disabled={busy}` on Cancel). No always-on `BackHandler` (DS §5.8).
- Deep links (notifications, §11.6): `chat/{sessionId}?sheet=approval|question` and the
  intent extras of CA §0.6. Schedules "Run now" and run rows navigate to
  `chat/{sessionId}`.
- Expanded (≥ 840dp): Customize uses the rail + single content pane with a max content
  width (§0.6); Marketplace and Schedules use `NavigableListDetailPaneScaffold` (list
  400dp + detail), §3.3 and §4.3. As SP §0.2 requires, each is its own route with its own
  scaffold, never nested in another scaffold's pane.

### 0.3 Channels used here

| Channel | What uses it here | Client |
| --- | --- | --- |
| Hub (`ws://127.0.0.1:25463/hub`) | `settings.list`, `settings.toggle`, event `settings.changed` (C1); `schedule.*` and events `schedule.execution_completed` / `schedule.execution_failed` (C5–C7); `session.list`, `session.messages`, event `team.progress` (C9); every chat event for notifications (C14, C15); `/health`, `/version` (C10, C12) | `HubClient.command(name, payload, timeoutMs)` |
| RUN_COMMAND | `cline config tools --json`, `cline config hooks --json` (C1); `cline mcp install|uninstall`, `cline plugin install|uninstall`, `npx -y skills@latest add …`, `cline skill remove …` (fallback routes, C2–C4); `cat` of `cline_mcp_settings.json` (C1 fallback); `cline --version`, `readlink` of the port tree, `cline doctor --json`, `tail -c` of logs (C10, C12) | `TermuxExecutor.run(TermuxCommand, timeoutMs)` |
| Settings helper (dashboard `/browser`) | `list_mcp_servers`, `upsert_mcp_server`, `delete_mcp_server` (C1, C2); `list_marketplace_installed_entries`, `install_marketplace_entry`, `uninstall_marketplace_entry`, `uninstall_local_primitive` (C1, C3, C4); `cline_account`, `save_provider_settings` (C8); `list_provider_catalog`, `loadModels` (C6 provider/model lists via `ProviderCatalogRepository`, SP §0.7) | `SettingsHelper` (SP §0.3) |
| Internet (OkHttp, direct) | marketplace catalog `GET https://cline.github.io/marketplace/catalog.json` (C3); external links in Custom Tabs | `MarketplaceRepository`, `openExternal` (§0.8) |

Which screens acquire the helper (SP §0.3 `acquire(owner)` on enter, `release` on leave):

| Screen | Acquires on enter | Without the helper (fallback, SP §0.4) |
| --- | --- | --- |
| C1 Tools, Plugins, Skills, Rules, Hooks tabs | no; uses it opportunistically if it is already `Ready` (uninstall) | full lists and toggles work (Hub + RUN_COMMAND); uninstall uses CLI routes where they exist (§1.8) |
| C1 MCP tab, C2 | yes (MCP tab selected or editor open) | list from the settings file, toggles over the Hub, add/edit/delete through `cline mcp install|uninstall` without env/cwd (§2.8) |
| C3, C4 Marketplace | yes | catalog still loads (direct HTTPS); installed state approximated from `settings.list`; install/uninstall through CLI routes (§3.8) |
| C6 Schedule editor | no (reads `ProviderCatalogRepository`, which may be cached) | provider list from `providers.json`, models from the fallback table + free text (SP §0.4) |
| C8 Account | yes | reduced page (§5.4 "Helper unavailable"): signed-in state from `providers.json`, "Sign in" (SP S7), "Open dashboard" |
| C5, C7, C9, C10–C15 | no | not affected |

### 0.4 Kernel state → what every screen in this spec shows

The app-wide gate is `KernelGate` (`AppShellViewModel.kernelGate`, defined in
`design/screens/onboarding-kernel.md` §F.6, cited as **KS F.6**), derived from
`KernelCoordinator.state`. The surfaces are the Kernel spec's `KernelBanner` and
`KernelStatePanel` (KS §0.7) with the variant copy of KS §F.2 and the precedence of KS §F.4;
this spec only says where they go and what stays usable. Hub connection labels are the
DS §1.6 kernel-connection table.

| `KernelGate` | Screens that need the Hub (C1 lists, C5–C7, C9) | Screens that need only RUN_COMMAND / helper (C2 file route, C3/C4 browsing, C8, C10–C13) | Content already loaded |
| --- | --- | --- | --- |
| `Busy(Starting \| Connecting)` with nothing loaded | loading skeleton (DS "Loading skeleton", 3 cards) under a `LinearProgressIndicator` | normal | kept, only local actions enabled (search, filters) |
| `Ready` | normal | normal | normal |
| `Busy(Reconnecting \| Draining)` | `KernelBanner` (KS §0.7) at the top of the content column; mutations disabled (switches, Run now, Save) with `stateDescription` "Unavailable while reconnecting" *(Android)* | not affected | kept |
| `Down(variant)` (Asleep, Stopped, Disconnected, StartFailed, CrashLoop, Protocol, Auth) | nothing loaded: `KernelStatePanel` (KS §0.7) with that variant's KS §F.2 copy and action ("Start kernel" / "Connect" / …) replaces the content; something loaded: `KernelBanner` with the same variant | RUN_COMMAND screens still work (Termux wakes to run the command) | kept, mutations disabled |
| `DegradedNoCommands` (Hub alive, RUN_COMMAND revoked) | normal | the action that needs RUN_COMMAND shows `KernelBlockedCard` (SP §0.5) when invoked | kept |
| `Blocked(focus)` (Termux missing, permission missing, port too old) | `KernelBlockedCard` (SP §0.5) in place of the content | same | not shown |

"Start kernel" calls `KernelCoordinator.ensureConnected(startIfDown = true)`; while it
runs the button shows a 16dp spinner and the label "Connecting..." (DS §1.6 copy).

### 0.5 Hub reply errors handled the same way everywhere

| Reply / condition (KC recovery matrix) | Behaviour |
| --- | --- |
| `hub_connection_closed` (command in flight when the socket dropped) | Optimistic UI change is reverted. Snackbar "Cline Hub disconnected. Try again when it reconnects." *(Android)*, `Long`. Idempotent reads (`settings.list`, `schedule.list`, `session.list`) are retried once automatically after `Ready`; writes never are (KC). |
| `hub_draining` (retryable) | Writes wait for `hub.drain_changed {draining:false}` (max 30 s) and are sent once more; while waiting the control shows its pending state. After 30 s: snackbar "Cline Hub is restarting. Try again in a moment." *(Android)*. |
| `client_authority_mismatch`, `client_not_found` | The connection has lost its client record (HF "Same clientId", fixture 04 c3); a second `client.register` on the same socket is rejected with `client_already_registered`, so it is **never sent**. `KernelCoordinator` closes the WebSocket (OkHttp `close(1000)`, `cancel()` after 2 s, and waits for `onClosed`/`onFailure`), reconnects with the KC backoff, registers with the same `clientId`, re-subscribes with cursors and re-attaches sessions. The screen shows the `Busy(Reconnecting)` row of §0.4 meanwhile and re-runs its reads once on the next `Ready`; a write that got this reply is not resent (KC). |
| `settings_list_failed` / `settings_toggle_failed` / `schedule_command_failed` | The reply `error.message` is shown verbatim in the screen's error surface (kernel text is data, DS §8.2 item 8). |
| any reply after `timeoutMs` | Treated as `hub_connection_closed` for writes; for reads the screen error "The Cline Hub did not answer in time." *(Android)* + "Retry". |

Timeouts sent in the envelope: reads 30 s (Hub default), `settings.list` with
`includePluginTools:true` **60 s** (plugin setup runs), `schedule.trigger` 30 s.

### 0.6 Window classes for this group

| Class | Customize, Account, About | Schedules | Marketplace | Editors (C2, C6, C12) |
| --- | --- | --- | --- | --- |
| Compact (< 600dp, S25U portrait ≈ 412dp) | `PageScaffold` (voice), single column, 16dp gutters | same; C7 pushed | list → pushed detail | full-screen route |
| Medium (600–839dp, Tab S7+ portrait ≈ 778dp) | single column, content max width **720dp** centred, 24dp gutters | same; C7 pushed | list → pushed detail | full-screen route, form column 720dp centred |
| Expanded (≥ 840dp, Tab S7+ landscape ≈ 1244dp) | cards in `LazyVerticalGrid(columns = GridCells.Fixed(if (contentWidth >= 720.dp) 2 else 1))`, `contentWidth` measured with `BoxWithConstraints` after the max-width clamp, content max width **1040dp** centred | `NavigableListDetailPaneScaffold`: list pane 400dp of single-column cards + detail pane C7 (max content width 720dp); no selection → placeholder (§4.4) | `NavigableListDetailPaneScaffold`: list 400dp + detail (max 720dp); no selection → placeholder (§3.4) | `BasicAlertDialog` 560dp (§0.2) |
| Height-compact (< 480dp tall, phone landscape) | `PageScaffold` uses the small `TopAppBar` (no medium collapse) | same, single pane | list → pushed detail | full-screen route |

`GridCells.Fixed` is used because `GridCells.Adaptive` cannot cap the column count (a DeX
or wider window would get 3 columns).

Lists that sit under an ExtendedFAB (C1 MCP tab, C5) use
`contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 88.dp)`
(56dp FAB + 16dp margin + 16dp), so the last card's trailing buttons are never under the
FAB. On a height-compact window (phone landscape) the short viewport puts the bottom-end
FAB over the first card's switch at the initial scroll position, which bottom padding cannot
fix: there the FAB stays icon-only (label in its tooltip and content description, like the
compact rail) and the list reserves an 88dp end lane instead (`components/FabLane.kt`), so
no card runs under it. Providers (SP S4) follows the same rule.

### 0.7 Formatting helpers used here

All ported unchanged from the desktop (DS §9 "Formatting functions"), each with a unit test:

- `formatDateTime(value)` (routine-view.tsx): `"-"` for missing/invalid, otherwise
  `DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)` in the
  device zone (the desktop's `toLocaleString`).
- `formatScheduleTrigger`, `formatScheduleDays` ("Every day", "Weekdays", "Mon, Wed"),
  `formatScheduleTime` (localized short time), `formatScheduleModel`
  (`"<provider>/<model>"`, `"-"`), `formatExecutionResult` (`"<status> at <when>"`).
- `formatCreditBalance(value, decimals = 2)` = `value / 1_000_000` with exactly 2
  fraction digits, en-US grouping (desktop hard-codes `en-US`; keep it, credits are a
  technical unit shared with app.cline.bot).
- Account dates: `MMM d, yyyy` and `h:mm a`, `Locale.US` (desktop `en-US`).
- `previewText(input, 150)` (extensions-view.tsx): collapse whitespace, cut at 150, trim,
  append `"..."`.
- `describeAgentActivity`, `agentEntryState`, `mergeAgentActivity` (session-agents.ts).

### 0.8 External links (`ui/OpenExternal.kt`)

Every external link in this spec (Marketplace "Learn more", Author, "Get value"; Account
dashboard, credits, usage, create links; About issues, releases, changelog) goes through one
helper, `openExternal(context, url, colorScheme)`:

```kotlin
CustomTabsIntent.Builder()
    .setShowTitle(true)
    .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
    .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT,
        CustomTabColorSchemeParams.Builder().setToolbarColor(lightSurface.toArgb()).build())
    .setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK,
        CustomTabColorSchemeParams.Builder().setToolbarColor(darkSurface.toArgb()).build())
    .build().launchUrl(context, Uri.parse(url))
```

`lightSurface` / `darkSurface` are the current scheme's `surface` in each mode (the
deprecated `setToolbarColor` on the builder is not used). With no Custom Tabs provider it
falls back to `Intent.ACTION_VIEW`; with no browser at all, snackbar "No browser found."
*(Android)*.


---

## 1. C1 — Customize

### 1.1 Purpose

The installed inventory of everything that extends Cline in this Termux kernel: built-in
and plugin tools, plugins, skills and workflows, rules, MCP servers and hooks, each with
its live count. Toggle what can be toggled, see where each item lives (global vs project),
copy its path, uninstall what the marketplace or the user installed, and jump to the
Marketplace to add more.

### 1.2 Desktop equivalent

`CustomizeView` (`customize-view.tsx`) hosting `CustomizationSectionView`
(`extensions-view.tsx`) per tab and `McpServersContent` (`mcp-view.tsx`) for MCP; each tab
embeds the "installed" variant of `MarketplaceView` (`marketplace-view.tsx`).

Copied: the page title and description, the tab order **Tools, Plugins, Skills, Rules,
MCP, Hooks** and default tab Tools, counts per tab (skills = skills + workflows), the
"Marketplace" header action (as an icon button, §1.3), every card's field order and badges
(scope "Global"/"Project", type, "Agent Plugin"/"Cline Plugin", "Marketplace",
transport), the MCP card's status dot as its enabled signal (the live `McpServersContent`
card has no "Disabled" badge; the badge exists only in `extensions-view.tsx`'s
`renderMcpServerCard`, which no live path mounts (Customize's MCP tab renders
`McpServersContent`), so the app shows the dot + the switch only), switch semantics and their disabled cases with the desktop tooltip texts
(shown as visible supporting text, §1.5), the "More actions" menu (Copy path, Uninstall),
"Uninstalling..." states and the per-item result message, the tools search,
"BuiltIn Tools" / "Plugin Tools" sections with "Enable all / Disable all", plugin
contributions disclosure, rules/hooks/plugins scope rules, the red load error ("Failed to
load configuration lists: …") and the amber "Partial results" banner, every empty string,
and the 60 s list cache.

Translated: the underline tab bar becomes `PrimaryScrollableTabRow` (DS §4.3); per-tab
refresh buttons become one app-bar refresh plus pull-to-refresh; hover-only edit/delete
on MCP cards become always-visible icon buttons; `<details>` becomes an expandable card;
"Open settings file" (opens an editor on the desktop) becomes "Copy file path".

Not ported: the "Connectors" tab (Composio, org-provisioned, desktop-only per FM
"none" rows); the tools-tab web-search provider guidance (`web-search-provider-guidance.tsx`
configures desktop search providers; a `web_search` row, if the CLI catalog lists one, is a
plain tool card, §1.8); and **hook execution stats** ("<n> executed", "Last run: …", "Execution
status is based on hook events in session <id>."): the kernel's dashboard answers
`read_session_hooks` with a hard-coded `[]` and `list_cli_sessions` with its own in-memory
sessions, not Hub history (`apps/cline-hub/src/server/desktop-commands.ts`), so the badges
could never render. The Hooks tab lists hook files only.

### 1.3 Layout

Compact (S25U portrait):

```
┌────────────────────────────────────────────┐
│ ≡  Customize                    ⟳  🏬      │  MediumTopAppBar (collapses on scroll);
│                                            │  ≡ = menu (top-level entry), ← when pushed
│                                            │  from Settings (§0.2)
│                                            │
│ Extend what Cline can do and how it works. │  bodyMedium onSurfaceVariant
│ Explore the marketplace for more options.  │
│ [📁 cline-termux-app ▾]                    │  WorkspaceScopeChip (AssistChip)
├────────────────────────────────────────────┤
│ Tools 12 │ Plugins 2 │ Skills 5 │ Rules 3 │ M…  PrimaryScrollableTabRow (sticky)
├────────────────────────────────────────────┤
│ Inspect built-in tools and tools           │  tab description
│ contributed by plugins.                    │
│ ┌────────────────────────────────────────┐ │
│ │ 🔍 Search tools                         │ │  OutlinedTextField (search)
│ └────────────────────────────────────────┘ │
│ BuiltIn Tools 9          [☑] Disable all   │  section header + checkbox row
│ ┌────────────────────────────────────────┐ │
│ │ 🔧 read_files                    [●━]  │ │  ExtensionCard: icon, title, Switch
│ │ Read the contents of one or more files │ │  description, 2 lines
│ └────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────┐ │
│ │ 🔧 editor                        [●━]  │ │
│ │ Edit files with exact replacements…    │ │
│ │ editor, apply_patch                     │ │  headless names, ClineMono.small
│ └────────────────────────────────────────┘ │
│ Plugin Tools 3           [ ] Enable all    │
│ ┌────────────────────────────────────────┐ │
│ │ 🔧 lint_fix   [my-plugin]        [━○]  │ │  plugin badge
│ │ No description available.              │ │
│ └────────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

MCP tab on compact (ExtendedFAB "Add MCP Server" bottom end, DS §5.4):

```
│ Editing this list updates                  │
│ cline_mcp_settings.json.                   │
│ MCP settings path:                         │
│ ~/.cline/data/settings/cline_mcp_se…  ⧉    │  ClineMono.small + copy IconButton
│ ┌────────────────────────────────────────┐ │
│ │ 🔍 Search mcp servers                   │ │
│ └────────────────────────────────────────┘ │
│ Installed                               2  │
│ ┌────────────────────────────────────────┐ │
│ │ ● github  [Local · stdio]       [●━]   │ │
│ │ Command: npx                           │ │
│ │ Args: -y, @modelcontextprotocol/serv…  │ │
│ │ Env: GITHUB_TOKEN=[REDACTED]           │ │
│ │ › Marketplace setup instructions       │ │
│ │                          ✎   🗑        │ │  Edit / Delete IconButtons
│ └────────────────────────────────────────┘ │
│                    ╭───────────────────╮   │
│                    │ + Add MCP Server  │   │  ExtendedFloatingActionButton
│                    ╰───────────────────╯   │
```

Plugins tab card (collapsed / expanded):

```
│ ┌────────────────────────────────────────┐ │
│ │ 🧩 my-plugin [Global] [Cline Plugin]   │ │
│ │                        [●━]  ⋮   ⌄     │ │  Switch, overflow, expand chevron
│ ├────────────────────────────────────────┤ │  (expanded part)
│ │ Adds lint helpers for TypeScript.      │ │
│ │ Contributions [Tools 2] [Skills 1]     │ │
│ │ Tools    lint_fix  lint_check          │ │
│ │ Skills   ts-style                       │ │
│ └────────────────────────────────────────┘ │
```

Expanded (Tab S7+ landscape), rail on the left, content 1040dp centred, cards in 2 columns:

```
┌──┬──────────────────────────────────────────────────────────────────────────────┐
│≡ │ Customize                                                         ⟳   🏬     │
│✎ │ Extend what Cline can do and how it works. Explore the marketplace for more   │
│  │ options.          [📁 cline-termux-app ▾]                [🏬 Marketplace]     │
│💬│ Tools 12   Plugins 2   Skills 5   Rules 3   MCP 2   Hooks 1                   │
│⏱ │ ───────                                                                       │
│🧩│ Inspect built-in tools and tools contributed by plugins.                      │
│⚙ │ [🔍 Search tools                                           ]                  │
│  │ BuiltIn Tools 9                                         [☑] Disable all       │
│  │ ┌───────────────────────────────────┐ ┌───────────────────────────────────┐  │
│  │ │ 🔧 read_files              [●━]   │ │ 🔧 search_codebase          [●━]  │  │
│  │ │ Read the contents of one or more… │ │ Search the codebase with ripgrep… │  │
│  │ └───────────────────────────────────┘ └───────────────────────────────────┘  │
│◉ │ …                                                                             │
└──┴──────────────────────────────────────────────────────────────────────────────┘
```

The app bar's actions slot holds icon buttons only: "Refresh" (`refresh`) and
"Marketplace" (`storefront`), each an `IconButton` with a `TooltipBox` and the CD of §1.5,
on every width. On medium and expanded the page additionally shows a labelled
`OutlinedButton` (`storefront` + "Marketplace", the desktop outline button) at the end of
the description row, next to the workspace chip (where the desktop draws it visually);
both open the same route. The ExtendedFAB on the MCP tab collapses to icon-only on scroll
(`expanded = listState.isScrollingUp()`, DS §5.4); the MCP list uses the FAB bottom
padding of §0.6.

### 1.4 States

Per tab (`TabUiState`), plus page-level kernel states from §0.4.

| State | Trigger | What shows |
| --- | --- | --- |
| First load | no cache, `settings.list` in flight | tab row with no counts; content: 3 skeleton cards (DS skeleton, 88dp tall) |
| Loading with cache | cache older than 60 s, refresh in flight | cached cards; `LinearProgressIndicator` (indeterminate) pinned under the tab row; refresh icon replaced by a 24dp spinner |
| Populated | data | cards as in §1.3 |
| Empty (per tab) | list empty | `PageEmptyState`-style dashed card (Appendix A `DashedNotice`) with the tab's empty string (§1.5) |
| Search empty (Tools, Skills, Plugins, MCP) | query matches nothing | dashed notice "No tools match your search." (Tools) / "No skills match the current filters." / "No plugins match the current filters." / "No MCP servers match the current filters." |
| Load error | `settings.list` failed or RUN_COMMAND for tools/hooks failed with no data | `InlineErrorCard` (Appendix A) "Failed to load configuration lists: <message>" + "Retry" *(Android)*; cached cards stay below if present |
| Partial results | a secondary source failed (tools or hooks RUN_COMMAND, plugin inspection) | amber card (`Surface(warning.container)`, `warning` icon) "Partial results" + bullet list of `"<source>: <message>"` (e.g. "hooks: Termux did not answer") |
| Item toggling | switch tapped | that switch disabled, 16dp spinner replaces nothing (switch keeps position until the reply; see §1.6), row `stateDescription` "Saving" |
| Item uninstalling | Uninstall confirmed | the menu item / button label "Uninstalling..." with a 16dp spinner; card dimmed to 60 % alpha; other items enabled |
| Item result | uninstall finished | `ActionMessage` under the card: success `bodySmall onSurfaceVariant` "Uninstalled <name>." (or the helper's message), failure `bodySmall error` with the error; polite live region |
| Running session | any session `running` | unchanged; Plugins tab shows the note "Changes apply when a session is rebuilt or started." under the description *(Android, string reused from desktop source: the second sentence of the non-embedded Plugins page in `extensions-view.tsx`, which Customize itself never mounts)* |
| Offline / kernel down | §0.4 | `KernelStatePanel` or `KernelBanner` (KS §0.7) |
| Helper starting (MCP tab only) | helper `Starting` | `LinearProgressIndicator` + "Starting settings helper..." (SP §0.3) above the list; list from the file route shows meanwhile |
| Helper unavailable (MCP tab only) | helper `Unavailable` | SP §0.3 unavailable card above the list; fallback behaviour §2.8 |
| Permission missing | RUN_COMMAND not granted | `KernelBlockedCard` (SP §0.5) in the Tools and Hooks tabs only (the other tabs use the Hub) |

### 1.5 Copy

Page:

| Element | String |
| --- | --- |
| App bar title | "Customize" |
| Page description | "Extend what Cline can do and how it works. Explore the marketplace for more options." |
| Marketplace action | "Marketplace" (label, tooltip and CD) |
| Refresh action | CD and tooltip "Refresh" *(Android)* |
| Workspace scope chip | label = workspace basename; CD "Project items from <path>. Change workspace" *(Android)*; when no workspace is known: "No workspace" *(desktop fallback string)* |
| Tabs | "Tools", "Plugins", "Skills", "Rules", "MCP", "Hooks"; count after the label in `labelMedium` |
| Tab TalkBack | "<label>, <count> items" *(Android)*; without a count: "<label>" |

Tab descriptions (desktop `sectionDescriptions`):

| Tab | Description |
| --- | --- |
| Tools | "Inspect built-in tools and tools contributed by plugins." |
| Plugins | "Manage installed plugins and add new plugins from the marketplace." |
| Skills | "Manage installed skills and add new skills from the marketplace." |
| Rules | "Review project and global rule files that shape Cline behavior." |
| MCP | "Editing this list updates cline_mcp_settings.json." / no file: "No MCP settings file found yet. Add a server to create it." |
| Hooks | "Inspect hook configuration." *(Android: the desktop's "Inspect hook configuration and recent execution status." minus the execution-status half, which is not ported, §1.2)* |

Cards and sections:

| Element | String |
| --- | --- |
| Search fields | placeholder and CD: "Search tools"; "Search skills"; "Search plugins"; "Search mcp servers" (desktop lowercases the page title; kept verbatim); CD of the MCP one "Search MCP Servers" |
| Installed section header (Plugins, Skills, MCP, Rules, Hooks) | "Installed" + count |
| Tools sections | "BuiltIn Tools" + count; "Plugin Tools" + count |
| Bulk checkbox label | "Disable all" when all shown are on, otherwise "Enable all"; CD "Disable all builtin tools" / "Enable all builtin tools" / "Disable all plugin tools" / "Enable all plugin tools" |
| Tool card description fallback | "No description available." (also used for a `web_search` row: plain card, no provider guidance, §1.8) |
| Switch CD | "Toggle <name>" (tools, skills, plugins, rules); MCP: "Enable <name>" |
| Disabled switch reason (desktop tooltip text, shown as visible supporting text) | rules: "Toggling rules isn't supported yet"; workflows: "Toggling workflows isn't supported yet"; `bodySmall onSurfaceVariant` under the badges row of that card (a disabled `Switch` gets no pointer input, so a tooltip anchored on it could never show) |
| Scope badges | "Global", "Project" |
| Type badges | "skill", "workflow" (lowercase, desktop); "Agent Plugin"; "Cline Plugin"; "Marketplace"; transport "Local · stdio" / "Remote · SSE (legacy)" / "Remote · Streamable HTTP" (MCP enabled state = status dot + switch, no "Disabled" badge, §1.2) |
| Overflow button CD | "More actions for <name>" (fallback "plugin") |
| Menu items | "Copy path"; "Uninstall" / "Uninstalling..." |
| Uninstall confirm *(Android, DS §3.4)* | title "Uninstall <name>?"; text "This removes <name> from this Cline environment." ; buttons "Cancel", "Uninstall" (`error`) |
| Uninstall result | "Uninstalled <name>." (fallback when the route returns no message) |
| Copy path snackbar (API ≤ 32 only; API 33+ shows the system clipboard UI) | "Copied path" *(Android)* |
| Plugin details | description text; load error text in `error`; "Enable this plugin to inspect its dynamic contributions."; "Contributions"; group labels "Tools", "Skills", "Rules", "Hooks", "Commands", "MCP servers", "Providers", "Capabilities" with count badge "<label> <n>"; "No plugin contributions found." |
| Plugins running note | "Changes apply when a session is rebuilt or started." *(Android, string reused from desktop source, §1.4)* |
| Hooks | card = file name (`titleSmall`), event badge = `hookEventName` (omitted when absent), path (`ClineMono.small`), scope badge; no execution badges (§1.2) |
| MCP card details | "Command: <cmd>", "Args: <a, b>", "CWD: <dir>", "URL: <url>", "Env: KEY=[REDACTED], …", "Headers: <k=v, …>" (values of secret-looking keys shown as `[REDACTED]`, §1.10); no command: "No launch command configured." |
| MCP path row | "MCP settings path:" + path; copy CD "Copy file path" (DS ChangedFileHeader string) |
| MCP card actions | CD "Edit <name>", "Delete <name>" |
| MCP FAB | "Add MCP Server" |
| Marketplace setup disclosure | "Marketplace setup instructions" / with several matches "Marketplace setup instructions (<entry name>)"; inside: "Environment setup needed", "Add these values to your Cline/plugin environment after install.", env badges "Required"/"Optional", link "Get value" |

Empty strings:

| Tab | Empty | Search empty |
| --- | --- | --- |
| Tools | "No builtin tools found." / "No plugin tools found." (per section) | "No tools match your search." |
| Plugins | "No plugins installed. Browse the marketplace to add one." | "No plugins match the current filters." |
| Skills | "No skills installed. Browse the marketplace to add one." | "No skills match the current filters." |
| Rules | "No rules found." | (no search) |
| MCP | "No MCP servers installed. Browse the marketplace or add a server manually." | "No MCP servers match the current filters." |
| Hooks | "No hooks found." | (no search) |

Errors: "Failed to load configuration lists: <message>"; "Partial results"; toggle
failure snackbar = the reply `error.message` (desktop shows it in the red banner; the app
uses a `Long` snackbar plus the banner when it persists).

### 1.6 Interactions and gestures

- **Tab switch:** tap a tab or swipe the content horizontally (`HorizontalPager` bound to
  the tab row, `beyondViewportPageCount = 0`). The selected tab is saved in
  `SavedStateHandle` and in the route argument so back/forward and process death keep it.
  The settings helper (§0.3) is acquired when `pagerState.settledPage` has been MCP for
  500 ms (`snapshotFlow { settledPage }.debounce(500)`), never on `currentPage`, so a
  swipe from Rules to Hooks that passes through MCP does not boot the dashboard or show
  "Starting settings helper...". Leaving MCP releases the lease with SP §0.3's 30 s grace,
  so a quick swipe back reuses the running helper.
- **Pull to refresh** (`PullToRefreshBox`, M3 1.4) on every tab: forces `refresh(force = true)`
  (bypasses the 60 s cache). The app-bar refresh does the same. While refreshing, the
  indicator stays until all sources answered.
- **Switch (tool, skill, plugin, MCP):** the whole card row is not the toggle target; only
  the `Switch` (48dp target) toggles, because the card body expands (plugins) or has
  other actions. On tap: disable the switch, send the command, and on a reply apply the
  returned snapshot (the switch moves to the server value). On failure: snackbar with the
  error and the switch stays at its old value. No optimistic flip (the desktop waits for
  the reply too).
- **Enable all / Disable all (Tools):** a `Checkbox` + label row (48dp). It applies to the
  *filtered* tools of that section (desktop). It sends one `settings.toggle` per name
  sequentially (§1.9) and disables every affected switch until the last reply.
- **Search:** filters as you type (no debounce needed; client-side). Tools match name,
  description, plugin name, path and headless names (desktop `toolMatchesQuery`). Skills,
  plugins and MCP match name, description, path. Clear `IconButton` ("Clear search"
  *(Android)*) when non-empty. The IME action is `Search` and hides the keyboard.
- **Overflow menu ⋮** on skills, workflows, plugins (not Agent Plugins), rules: "Copy path"
  always; "Uninstall" for skills, workflows and Cline plugins (not rules: desktop
  `showDelete: false`). Long-press on the card opens the same menu at the touch point
  (DS #15) and the same items are TalkBack custom actions.
- **Uninstall:** confirm dialog (Android addition, DS §3.4), then §1.8 routing. MCP
  uninstall is the Delete button and C2's delete dialog.
- **Plugin card expand:** tap anywhere on the card except the switch and overflow toggles
  the details (DS disclosure motion). `stateDescription` "Expanded"/"Collapsed".
- **MCP card:** tap the card body = Edit (opens C2); Edit and Delete icon buttons are
  always visible bottom-end (no hover on touch). The settings-path row's copy button copies
  the absolute Termux path.
- **"Marketplace setup instructions"** disclosure inside a matched card expands the env
  list; "Get value" opens the URL with `openExternal` (§0.8).
- **Workspace scope chip:** opens the Workspace picker (SP S9) in "scope" mode (no "Just
  chat"); choosing a folder re-runs every source with the new `workspaceRoot`/`cwd`. The
  choice is remembered per app (DataStore `customize.workspaceRoot`), default = the
  workspace of the most recently opened session, else `/data/data/com.termux/files/home`.

### 1.7 Navigation and back

- Entered from the drawer/rail "Customize" (top-level entry, `menu` icon on compact, back
  → Chats) and the Settings row "Installed" (pushed entry, back arrow, back → Settings),
  §0.2; the empty states ("Browse the marketplace…" strings are plain text; the
  Marketplace action is in the app bar); and deep link `customize?tab=mcp` from the chat's
  MCP-related errors (pushed entry over the chat).
- Back order (DS §5.8): open menu → tooltip → search field focus (first back clears focus
  and hides the IME, it does not clear the query) → pop the destination.
- The Marketplace action pushes `marketplace` with `type` preset to the current tab when it
  is Plugins (`plugin`), Skills (`skill`) or MCP (`mcp`); from Tools, Rules, Hooks it opens
  unfiltered. Returning from Marketplace re-reads the inventory if an install or
  uninstall finished meanwhile (`MarketplaceRepository.inventoryChanged` flow, the
  desktop's `invalidateExtensionInventoryCache`).

### 1.8 Data bindings

Workspace input for every source: `ws = customize.workspaceRoot` (Termux absolute path).

| Need | Call | Notes |
| --- | --- | --- |
| Skills, workflows, rules, plugins (+ contributions), plugin tools, MCP names/enabled | Hub `settings.list {workspaceRoot: ws, cwd: ws, includePluginTools: true}` → `{snapshot: CoreSettingsSnapshot}` | `timeoutMs: 60000`. Item = `CoreSettingsItem {id, name, path, kind, source, enabled?, description?, toggleable?, pluginName?, pluginPath?, agentPlugin?, loadError?, contributions?}`. HF recorded the empty shape `{workflows:[], rules:[], plugins:[], skills:[], tools:[], mcp:[]}`. |
| Built-in tools | RUN_COMMAND `cline config tools --json` with `RUN_COMMAND_WORKDIR = ws` | stdout = JSON array: built-in entries `{id, description, defaultEnabled, headlessToolNames[]}` followed by plugin entries `{name, type:"plugin", pluginName, path, source, enabled, description}`. Use only the entries without `type:"plugin"` (plugin tools come from the Hub snapshot). `enabled = defaultEnabled`. Timeout 60 s. **The catalog is CLI-scoped** (`apps/cli/src/runtime/tools.ts` calls `getCoreBuiltinToolCatalog` with `clientType: "cli"` and the CLI's enabled model tools), so: (1) `tasks` is absent (its catalog entry has `unavailableClientTypes: ["cli", "vscode"]`), yet sessions the app creates with `metadata.source: "android"` get it (FM). The app appends a `tasks` card when the catalog lacks it: id `tasks`, headless names `["tasks"]`, description "Create and manage explicitly requested one-time and recurring agent schedules." (copied from `BASE_TOOL_CATALOG`, `sdk/packages/core/src/extensions/tools/runtime.ts`), toggled like every other tool. Its `enabled` state comes from the global disabled-tool list, which neither source exposes for built-ins (`settings.list`'s `tools` are plugin tools only), so the same `cline config tools --json` RUN_COMMAND is followed by RUN_COMMAND `cat /data/data/com.termux/files/home/.cline/data/settings/global-settings.json` → `enabled = "tasks" !in disabledTools` (missing file or key = enabled; `resolveGlobalSettingsPath`, `sdk/packages/shared/src/storage/paths.ts`; only `disabledTools` is kept, the rest of the parsed object is discarded). (2) `web_search` appears only when the CLI's configured provider/model supports it (`supportsModelTool`); if it appears it is a plain tool card (no provider guidance, §1.2). |
| Hooks | RUN_COMMAND `cline config hooks --json`, workdir `ws` | stdout = JSON array `{fileName, hookEventName?, path}`. Timeout 60 s. No execution stats (§1.2). |
| MCP details (command, args, env, url, headers) | helper `list_mcp_servers` → `{settingsPath, hasSettingsFile, servers[{name, transportType, disabled, command?, args?, cwd?, env?, url?, headers?, metadata?}]}`; fallback: RUN_COMMAND `cat /data/data/com.termux/files/home/.cline/data/settings/cline_mcp_settings.json`, parsed with the same rules as the dashboard's `readMcpServersResponse` (`transport.*` first, then top-level keys; missing file = `hasSettingsFile:false`) | Env values are held in memory only and rendered redacted (SP §0.6). |
| Toggle skill | Hub `settings.toggle {type:"skills", path: item.path, enabled: !item.enabled, workspaceRoot: ws, cwd: ws}` | reply `{snapshot, changedTypes}`; Hub publishes `settings.changed {types, snapshot}`. |
| Toggle plugin | `settings.toggle {type:"plugins", path, enabled, workspaceRoot, cwd}` | Agent Plugins too when `toggleable == true`. |
| Toggle tool (built-in or plugin) | `settings.toggle {type:"tools", name, enabled}` once per name in `distinct([tool.id] + tool.headlessToolNames)` (desktop sends the same name set to `set_tool_disabled`), sequentially | After the last reply: built-in tools are updated in place (`enabled = new value`, the CLI catalog is not re-run); plugin tools from the reply snapshot. |
| Toggle MCP server | `settings.toggle {type:"mcp", name, enabled}` | Works without the helper. Afterwards the MCP details list is re-read (helper or file). |
| Uninstall (skill, workflow, plugin) with helper `Ready` | helper `uninstall_local_primitive {type: "skill"\|"workflow"\|"plugin", id, name, path}` → `{message}` | then `refresh(force = true)` |
| Uninstall without helper | plugin: RUN_COMMAND `cline plugin uninstall <path> --json`; skill: RUN_COMMAND `cline skill remove <dir> -g -y`, where `<dir>` is the installed skill directory name: `basename(dirname(item.path))` when `item.path` ends in `/SKILL.md`, else `basename(item.path)` (`cline skill remove` forwards to `npx -y skills@latest remove <installedName> -g -y`, the kernel's own form, `sdk/packages/core/src/services/marketplace.ts` `uninstallMarketplaceSkill`); workflow: not offered (menu shows only "Copy path") | timeouts 120 s (dashboard `INSTALL_COMMAND_TIMEOUT_MS`) |
| Events | `settings.changed` on the global subscription | If `types` intersects the visible data: re-run `settings.list` with this screen's `ws` (the event's snapshot may have been computed for another client's workspace, so it is not applied directly) and invalidate the 60 s cache. |
| Marketplace matching (badges + setup disclosure) | `MarketplaceRepository.catalog` (C3), if already loaded or loadable | match values = desktop `getLocalMarketplaceMatchValues(id, name, path)` (path segments, segment pairs, basenames without extension) against `entry.id` and `entry.name`; never blocks rendering. |

Scope rules (ported from `extensions-view.tsx`; `root = normalize(ws)`):

- Rule = **Project** when its path equals or is under `root/.clinerules` or
  `root/.cline/rules`; otherwise Global.
- Hook = Project when under `root/` and the path contains `/.clinerules/hooks`.
- Plugin = Project when `source == "workspace-plugin"` or the path is under `root/` and
  contains `/.cline/plugins` or `/.agents/plugins`.
- Skill / workflow = Project when under `root/`.
- Lists show Global first, then Project (desktop `scopedRules` etc.). Skills and workflows
  are merged and sorted by name (`localeCompare` → `Collator.getInstance()`); tools sorted
  by name; MCP sorted case-insensitively.

Counts on the tabs (desktop `refreshCounts`): Tools = built-in + plugin tools; Plugins =
plugins; Skills = skills + workflows; Rules = rules; MCP = MCP servers; Hooks = hooks.

ViewModel:

```kotlin
enum class CustomizeTab { Tools, Plugins, Skills, Rules, Mcp, Hooks }

data class CustomizeUiState(
    val tab: CustomizeTab = CustomizeTab.Tools,
    val workspaceRoot: String?,                  // null = none known yet
    val kernel: KernelGate,                      // KS F.6
    val loading: Boolean, val refreshing: Boolean,
    val loadError: String?, val warnings: List<String>,
    val counts: Map<CustomizeTab, Int>,          // empty until first load
    val tools: ToolsState, val plugins: List<PluginCard>, val commands: List<CommandCard>,
    val rules: List<RuleCard>, val mcp: McpTabState, val hooks: HooksState,
    val queries: Map<CustomizeTab, String>,
    val pending: Set<String>,                    // item keys toggling or uninstalling
    val messages: Map<String, ActionMessage>,    // item key -> success/failure line
    val sessionRunning: Boolean,
)
data class ToolsState(val builtin: List<ToolCard>, val plugin: List<ToolCard>)
data class ToolCard(val key: String, val name: String, val description: String?, val enabled: Boolean,
                    val pluginName: String?, val path: String?, val headlessNames: List<String>)
data class CommandCard(val key: String, val type: CommandType, val name: String, val preview: String,
                       val path: String, val scope: Scope, val enabled: Boolean, val toggleable: Boolean,
                       val agentPlugin: Boolean, val marketplaceMatches: List<MarketplaceEntry>)
data class McpTabState(val settingsPath: String?, val hasSettingsFile: Boolean, val servers: List<McpServerCard>,
                       val helper: HelperState, val source: McpSource /* Helper | File */)
data class HooksState(val hooks: List<HookCard>)
```

`CustomizeRepository` owns the 60 s in-memory cache (desktop `EXTENSION_LISTS_CACHE_TTL_MS`)
keyed by `ws`. Nothing in this screen is persisted to disk
except the selected tab and `customize.workspaceRoot`.

### 1.9 Edge cases from the recovery matrix

- **Hub restarts while the screen is open** (`/version` pid changes, token rotation): the
  coordinator reconnects; this screen re-runs `settings.list` on the transition to `Ready`
  (cache invalidated because the daemon may have reloaded plugins).
- **`hub_draining`** on a toggle: §0.5 wait-and-resend once.
- **RUN_COMMAND blocked** (`err != -1`, allow-external-apps): Tools and Hooks tabs show
  `KernelBlockedCard`; the other tabs still work (Hub). The page-level "Partial results"
  banner lists "tools: Termux is blocking Cline" so the Tools count is not silently 0.
- **RUN_COMMAND never returns** (60 s): partial-results entry "<source>: Termux did not
  answer" + the Tools tab shows the `KernelBlockedCard` "Termux did not answer" with
  "Retry".
- **Workspace no longer exists** (`settings.list` succeeds but project items vanish): the
  scope chip shows the basename with an `error` outline and the snackbar "That workspace
  folder no longer exists." *(Android)* when the workdir RUN_COMMAND fails with
  `No such file or directory`; the chip reverts to `$HOME`.
- **Uninstall finishes while the app is backgrounded:** the helper socket may be frozen
  and the reply lost; on resume the screen force-refreshes and clears any stale
  "Uninstalling..." state older than 120 s, showing the snackbar "Check the result:
  the uninstall may have finished in Termux." *(Android)*.
- **`settings.changed` from another client** (CLI TUI toggling a tool): reloads within one
  event; the switch moves without a snackbar.

### 1.10 Accessibility

- Tabs: `Role.Tab`, selected state, "<label>, <count> items" (§1.5). Swiping the pager is
  not the only way: tabs are tappable and keyboard-navigable (arrow keys on the Tab S7+
  Book Cover).
- Each card is one semantics node with merged descendants except the switch, overflow,
  expand and icon buttons, which stay separate focus targets in reading order: title →
  badges → description → path → switch → overflow.
- Switch: `Role.Switch`, label "Toggle <name>", state "On"/"Off"; disabled rules/workflows
  read "Toggle <name>, on, unavailable. Toggling rules isn't supported yet" (the visible
  reason line of §1.5 appended to `contentDescription`).
- Paths and commands are LTR (`TextDirection.Ltr`) and read raw (DS §8.1 item 12).
- Secret-looking values in MCP `headers` and all `env` values are never put in the
  semantics tree; TalkBack reads "redacted" for them.
- `ActionMessage` lines are polite live regions; load errors assertive.
- Badges are text (never colour-only); the MCP status dot has CD "Enabled"/"Disabled".

### 1.11 Acceptance checklist (S25U)

- [ ] Open the drawer → "Customize": the app bar shows the `menu` icon (it reopens the
      drawer) and back returns to Chats; Settings → "Installed" shows the back arrow and
      back returns to Settings.
- [ ] Open the drawer → "Customize": the title, description and six tabs Tools, Plugins,
      Skills, Rules, MCP, Hooks appear in that order, Tools selected, counts appear after
      load and match `cline config tools --json` + `settings.list` counts in Termux.
- [ ] Swipe between tabs; rotate the phone on Hooks; the Hooks tab is still selected.
- [ ] Tools: type "edit" in "Search tools": only matching tools remain; clear restores.
      Toggle `read_files` off: the switch waits for the reply, then `cline config tools`
      in Termux shows it disabled; toggle back on.
- [ ] Tools: with all built-in tools on, the label reads "Disable all"; tap it and every
      filtered built-in tool turns off; tap "Enable all" to restore.
- [ ] Plugins: a Cline plugin card expands to show its contributions; a disabled plugin
      shows "Enable this plugin to inspect its dynamic contributions."
- [ ] Skills: a workflow's switch is disabled and its card shows the line "Toggling
      workflows isn't supported yet" under the badges. Skill toggle round-trips through
      `settings.toggle`.
- [ ] Tools: the list contains a `tasks` card; toggling it off adds `tasks` to
      `disabledTools` in `~/.cline/data/settings/global-settings.json`; toggle back on.
- [ ] Rules: a `.clinerules` file in the scoped workspace shows "Project"; `~/Documents/Cline/Rules`
      items show "Global"; the menu has only "Copy path".
- [ ] MCP: the path row shows `…/cline_mcp_settings.json`; env values show `[REDACTED]`;
      toggling a server off over the Hub updates the file (`cat` it in Termux).
- [ ] Hooks: every file from `cline config hooks --json` is listed with its event badge
      and scope; no execution badges appear.
- [ ] MCP tab with ≥ 4 servers: scroll to the end; the last card's Edit and Delete buttons
      are tappable with the FAB visible.
- [ ] Swipe quickly from Rules to Hooks: no "Starting settings helper..." line appears and
      no `cline dashboard` process starts (`pgrep -f "index.js dashboard"` in Termux).
- [ ] Workspace chip: switch to another project; project rules/hooks change accordingly.
- [ ] Uninstall a skill installed from the Marketplace: confirm dialog → "Uninstalling..."
      → "Uninstalled <name>." and the count drops.
- [ ] Stop the Hub (`cline hub stop` in Termux) with the screen open: banner "Cline Hub is
      not connected." + "Start kernel"; switches disabled; "Start kernel" brings it back
      and the list refreshes.
- [ ] Revoke the RUN_COMMAND permission: Tools and Hooks tabs show the permission card;
      Plugins/Skills/Rules still load.
- [ ] TalkBack: a tab reads "Plugins, 2 items, tab"; a tool switch reads "Toggle
      read_files, switch, on"; every overflow item is also a custom action.
- [ ] Tab S7+ landscape: cards in two columns, content centred, the app bar has the
      `storefront` icon button and the description row a labelled "Marketplace" outlined
      button; portrait: one column ≤ 720dp. A DeX/freeform window wider than 1244dp still
      shows at most two columns.
- [ ] Font scale 2.0 × in-app 20: tab row scrolls, cards grow, nothing clips.

---

## 2. C2 — MCP server editor and delete

### 2.1 Purpose

Add a local (stdio) or remote (Streamable HTTP / SSE) MCP server to the kernel's
`cline_mcp_settings.json`, edit or rename one, switch it off without removing it, or
delete it.

### 2.2 Desktop equivalent

The `Dialog` and `AlertDialog` inside `McpServersContent` (`mcp-view.tsx`): titles
"Add MCP Server" / "Edit MCP Server", description with the settings path, fields Server
name, Server type (radio cards Local / Remote), Command, Args, Environment variables
(key/value rows, password values), Server URL, Headers, Transport select, the "Advanced"
collapsible (Working directory, Metadata JSON), the "Enabled" row, the red form error box,
Cancel + "Add server" / "Save changes" / "Saving...", and the "Delete MCP Server" alert.
`buildServerInput` validation strings are copied verbatim. Saving goes through
`upsert_mcp_server {input}`; deleting through `delete_mcp_server {name}`.

Deliberate differences:

- **"Metadata JSON" is not editable, and saving drops existing metadata.** The kernel's
  dashboard `upsertMcpServer` (`apps/cline-hub/src/server/mcp.ts`) builds
  `next = {transport, disabled}` and assigns `servers[name] = next`, **replacing the whole
  record**: any `metadata` (and any other non-transport key) on the edited server is
  deleted on Save. The desktop has the same effect (it never sends `input.metadata`
  either). The CLI fallback (`cline mcp install`, `addMcpServer` sets
  `servers[name] = {transport}`) drops it too. So an editable metadata field would do
  nothing (no disabled placeholders, DS §9), and the app instead **warns**: when the
  server's file entry has `metadata` or any key other than `transport`/`disabled`
  (`McpServerCard.extraKeys`, from the helper's `metadata` field or the file read), the
  editor shows a read-only note in Advanced (§2.5 "Metadata note") and Save first asks
  "Save and remove metadata?" (§2.5). OQ C-2 asks upstream to spread the existing record.
- **Name collision guard** *(Android)*: creating a server whose name already exists shows
  an error instead of silently overwriting it (the desktop's upsert overwrites).
- **Discard confirm** *(Android)*: back or Close with edits asks first (touch dismissal is
  easy to trigger by accident).
- **Delete also from the editor** (edit mode overflow), as `docs/04-screens.md` lists
  "delete confirm" under the editor.

### 2.3 Layout

Compact and medium: full-screen `composable()` route (§0.2, DS #16 as amended by SP §11):
`Scaffold` + small `TopAppBar(navigationIcon = close, title, actions = TextButton("Add
server" | "Save changes"))`, no footer; the form column uses voice `formPadding`
(`consumeWindowInsets(innerPadding).imePadding()`), which works because the route is in the
activity window; the focused field is brought into view (`BringIntoViewRequester`). On
medium the form is a 720dp centred column.

```
┌────────────────────────────────────────────┐
│ ✕  Add MCP Server               Add server │
├────────────────────────────────────────────┤
│ The server is saved to                     │
│ ~/.cline/data/settings/cline_mcp_settings… │  ClineMono.small
│                                            │
│ Server name                                │
│ ┌────────────────────────────────────────┐ │
│ │ github                                 │ │
│ └────────────────────────────────────────┘ │
│ Server type                                │
│ ┌────────────────────────────────────────┐ │
│ │ (●) Local                              │ │  selectable OutlinedCard, primary border
│ │     Runs a command on this machine     │ │  when selected
│ │     (stdio). Recommended when available│ │
│ └────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────┐ │
│ │ ( ) Remote                             │ │
│ │     Connects to a hosted server over   │ │
│ │     HTTP by URL.                       │ │
│ └────────────────────────────────────────┘ │
│ Command                                    │
│ ┌────────────────────────────────────────┐ │
│ │ npx                                    │ │
│ └────────────────────────────────────────┘ │
│ Args                                       │
│ ┌────────────────────────────────────────┐ │
│ │ -y, @modelcontextprotocol/server-github│ │  2–5 lines
│ └────────────────────────────────────────┘ │
│ Environment variables                  [+] │
│ [−] ┌─KEY──────────┐ ┌─VALUE────────── 👁┐ │
│     └──────────────┘ └──────────────────┘ │
│ › Advanced                                 │
│ ┌────────────────────────────────────────┐ │
│ │ Enabled                          [●━]  │ │
│ │ Disable the server without removing it │ │
│ │ from settings.                         │ │
│ └────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────┐ │
│ │ ⚠ Command is required for local servers│ │  errorContainer
│ └────────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

On compact the KEY/VALUE pair stacks vertically when the window is narrower than 360dp or
the font scale exceeds 1.3 (KEY above VALUE, the remove button trailing the KEY row).

Remote type replaces Command / Args / Environment variables with:

```
│ Server URL                                 │
│ [ https://example.com/mcp                ] │
│ Headers                                    │
│ [ Authorization=Bearer token             ] │
│ Transport                                  │
│ [ Streamable HTTP (recommended)        ▾ ] │  ExposedDropdownMenuBox
```

Expanded: `BasicAlertDialog` (`dialog()` destination, `DialogProperties(usePlatformDefaultWidth
= false, decorFitsSystemWindows = false)`, `securePolicy = SecureOn` while an env value is
revealed, `imePadding` on the content), width 560dp, max height 90 % of the window, title
`headlineSmall`, the same fields in one column, footer row `TextButton` "Cancel" + `Button`
"Add server" / "Save changes" end-aligned (desktop footer), no top-bar action.

Delete confirm (all sizes): `AlertDialog` titled "Delete MCP Server".

### 2.4 States

| State | What shows |
| --- | --- |
| Create (fresh) | name empty, type Local, one empty env row, Enabled on, Advanced collapsed |
| Edit | fields from the server; Advanced expanded when `cwd` is set or the entry has metadata/extra keys (desktop opens it when cwd or metadata exist); app bar overflow `more_vert` with "Delete server" *(Android)* |
| Edit, entry has metadata | Advanced shows the metadata note (read-only `Surface(surfaceContainerHighest)`, the metadata keys listed in `ClineMono.small`, values not shown); Save → "Save and remove metadata?" confirm, then the normal save |
| Dirty | any field differs from the initial state; enables the discard confirm |
| Validation error | error box at the bottom (polite live region) **and** the first invalid field gets `isError = true` with the same message as `supportingText`; focus moves to that field |
| Saving | primary action label "Saving..." with a 16dp spinner, every field and Cancel/Close disabled, back consumed |
| Save error | error box with the helper/CLI message verbatim; fields enabled again |
| Helper starting | `LinearProgressIndicator` under the app bar + "Starting settings helper..." (SP §0.3); Save waits for `Ready` or `Unavailable` (max 20 s) before choosing its route |
| Helper unavailable (fallback) | the SP §0.3 card at the top (reduced wording, §2.5); the env section and Working directory field are replaced by a one-line note (§2.5); editing a server whose file entry has `env` or `cwd` shows the blocking note and hides Save (§2.8) |
| Kernel down | Save disabled; `KernelBanner` "Cline Hub is not connected." (the file route needs only RUN_COMMAND, but the follow-up `settings.toggle` for Enabled needs the Hub; to keep one rule the editor requires `Ready`) |
| Deleting | delete dialog confirm shows "Deleting..." + spinner, both buttons disabled (DS #16) |

### 2.5 Copy

| Element | String |
| --- | --- |
| Title | "Add MCP Server" / "Edit MCP Server" |
| Description | create: "The server is saved to <path>." · edit: "Update the MCP server stored in <path>." · path = `settingsPath`, or "cline_mcp_settings.json" while unknown (path in `ClineMono.small`) |
| Server name | label "Server name", placeholder "github" |
| Server type | label "Server type"; "Local" — "Runs a command on this machine (stdio). Recommended when available."; "Remote" — "Connects to a hosted server over HTTP by URL." |
| Command | label "Command", placeholder "npx" |
| Args | label "Args", placeholder "-y, @modelcontextprotocol/server-github", supporting text "Separate arguments with commas." *(Android)* |
| Env | label "Environment variables"; add CD "Add environment variable" *(Android)*; key placeholder "KEY"; value placeholder "VALUE"; remove CD "Remove env var <key>" or "Remove env var row"; value visibility CD "Show value" / "Hide value" *(Android)* |
| Server URL | label "Server URL", placeholder "https://example.com/mcp" |
| Headers | label "Headers", placeholder "Authorization=Bearer token", supporting text "Separate headers with commas." *(Android)* |
| Transport | label "Transport"; options "Streamable HTTP (recommended)", "SSE (legacy)" |
| Advanced | "Advanced"; "Working directory", placeholder "/path/to/project" |
| Enabled row | "Enabled" — "Disable the server without removing it from settings."; switch CD "Enable MCP server" |
| Actions | "Cancel"; "Add server" / "Save changes"; busy "Saving..."; close CD "Close" |
| Validation | "Server name is required."; "Command is required for local servers."; "Server URL is required for remote servers."; collision *(Android)*: "A server named \"<name>\" already exists." |
| Discard *(Android)* | title "Discard changes?"; text "Your changes to this server will be lost."; buttons "Keep editing", "Discard" |
| Delete dialog | title "Delete MCP Server"; text "Delete MCP server \"<name>\" from settings?" (fallback "Delete this MCP server from settings?"); buttons "Cancel", "Delete"; busy "Deleting..." |
| Overflow (edit) *(Android)* | "Delete server" (error colour) |
| Metadata note *(Android)* | title "Metadata"; text "This server has extra settings in the file (<keys>). Saving here removes them." |
| Metadata confirm *(Android)* | title "Save and remove metadata?"; text "Saving rewrites this server's entry and removes its metadata (<keys>). Edit cline_mcp_settings.json in Termux to keep it."; buttons "Cancel", "Save" |
| Fallback note *(Android)* | "Environment variables and the working directory need the settings helper." |
| Fallback blocking note *(Android)* | "This server uses environment variables or a working directory. Editing it needs the settings helper." + `TextButton` "Retry" |
| Fallback card *(Android)* | the SP §0.3 card with body "Cline could not start its settings helper in Termux, so servers are saved with the Cline command line instead." |

### 2.6 Interactions and gestures

- Server type cards are a single-choice group (`Modifier.selectableGroup()`, each card
  `selectable(role = Role.RadioButton)`). Switching Local → Remote sets transport
  `streamableHttp` unless the previous remote choice was `sse` (desktop `onValueChange`).
- Env rows: "+" appends an empty row; "−" removes the row, or clears it when it is the
  last one (desktop). Empty keys are ignored on save. Values are password fields
  (`PasswordVisualTransformation`, `KeyboardType.Password`, no autocorrect); the eye
  toggle reveals one value and sets `FLAG_SECURE` while any value is revealed (SP §0.6).
- IME actions: Next through the fields; `ImeAction.Done` on the last field validates and
  submits exactly like the primary action, on every width (same rule as Ctrl+Enter).
- Hardware keyboard: Ctrl+Enter saves, Esc = Close (with discard confirm when dirty).
- Delete from the card (C1) or from the editor overflow opens the delete dialog; confirm
  deletes and, from the editor, closes the editor on success.

### 2.7 Navigation and back

- Entered from C1 MCP tab: FAB "Add MCP Server" (`customize/mcp/edit`), card body or
  "Edit <name>" (`customize/mcp/edit?name=<name>`).
- Back order: open dropdown → discard confirm when dirty → close. On compact/medium the
  route pops with the seekable shared-axis-Y exit (predictive back previews C1 under it);
  on expanded the dialog closes with the M3 dialog exit.
- Closing returns to C1 with the MCP tab still selected and scroll position kept.

### 2.8 Data bindings

Input built exactly like the desktop `buildServerInput`:

- `name = name.trim()`; `previousName = initialName.trim()` or absent.
- stdio: `command = command.trim()`; `args = splitCsv(argsText)` (split on ",", trim,
  drop empties) or absent; `cwd = cwd.trim()` or absent; `env` from rows with non-blank
  keys or absent; `disabled = !enabled`.
- remote: `url = url.trim()`; `headers = parseKeyValuePairs(headersText)` (split on ",",
  each `k=v` split at the first "=", skip pairs without key) or absent; `transportType`
  `streamableHttp` | `sse`; `disabled`.

Routes:

| Action | Helper `Ready` | Fallback (helper `Unavailable`) |
| --- | --- | --- |
| Load server for edit | from the C1 list (helper `list_mcp_servers` result) | from the C1 file read |
| Save (create / edit) | `upsert_mcp_server {input}` → `McpServersResponse` (timeout 30 s) | `cline mcp install` overwrites a same-name server in place (`addMcpServer` sets `servers[name] = {transport}`, `sdk/packages/core/src/services/mcp-install.ts`), which also discards that entry's `disabled` and `metadata` (the §2.2 metadata confirm covers it). (1) **rename only** (`previousName != name`): RUN_COMMAND `cline mcp uninstall <previousName> --json`; same-name edits skip it; (2) RUN_COMMAND `cline mcp install <name> --yes --json -- <command> <args…>` (stdio) or `cline mcp install <name> <url> --transport streamable-http\|sse [--header "<Header-Name>: <value>"]… --yes --json` (remote; one `--header` per `k=v` pair of the form, the CLI's `parseHeader` splits at the first ":"); (3) if `disabled`: Hub `settings.toggle {type:"mcp", name, enabled:false}` (always re-applied, because (2) reset it). If (2) fails after (1) succeeded, re-install the previous configuration from memory with the same command form and show the error. Timeouts 120 s each. |
| Delete | `delete_mcp_server {name}` → `McpServersResponse` | RUN_COMMAND `cline mcp uninstall <name> --json` |
| Afterwards | apply the response to C1's list; `MarketplaceRepository.inventoryChanged.emit()` | re-read the file (C1) and `settings.list` |

In fallback the editor never offers env or cwd (the CLI install path has no flags for
them, `apps/cli/src/main.ts` `mcp install`), and refuses to edit a server whose file entry
has `env` or `cwd` (saving would drop them).

ViewModel:

```kotlin
data class McpEditorUiState(
    val mode: Mode,                    // Create | Edit(originalName)
    val name: String, val type: ServerType /* Local | Remote */, val transport: Transport /* StreamableHttp | Sse */,
    val command: String, val argsText: String, val cwd: String,
    val env: List<EnvRow>,             // EnvRow(id: Long, key: String, value: String, revealed: Boolean)
    val url: String, val headersText: String, val enabled: Boolean,
    val advancedOpen: Boolean, val dirty: Boolean,
    val fieldError: Field?, val formError: String?,
    val saving: Boolean, val deleting: Boolean,
    val route: McpRoute,               // Helper | Fallback | Waiting (helper Starting)
    val settingsPath: String?, val blockedInFallback: Boolean,
)
```

`env` values and `headersText` live only in this VM (not `SavedStateHandle`); on process
death the editor reopens with secrets blank and the note "Re-enter environment values."
*(Android)* under the env header when the original server had env keys.

### 2.9 Edge cases

- **Concurrent edit** (the CLI TUI or the desktop edits the file meanwhile): the helper
  route locks the file (`updateMcpSettingsFileSync`), so the last writer wins per server;
  after saving, C1 re-reads and shows the true file state.
- **`settings.toggle` for `disabled` fails** after a fallback install: the server stays
  enabled; the snackbar shows the error and the C1 switch shows the real state.
- **Helper dies mid-save** (socket closed): treat as unknown outcome: re-read the list;
  if the server matches the input, show success; otherwise show "Could not confirm that
  the server was saved." *(Android)* in the error box.
- **Kernel down while the editor is open:** Save disabled with the banner; fields keep
  their content.

### 2.10 Accessibility

- The dialog title is announced on open and receives initial focus (DS §8.1 item 7);
  closing returns focus to the FAB or the card that opened it.
- Each text field has a visible label (not placeholder-only); errors are attached via
  `supportingText` and `semantics { error(message) }`.
- Radio cards: "Local, radio button, selected, Runs a command on this machine (stdio).
  Recommended when available."
- Env value fields are excluded from autofill (`ContentType` none) and from the semantics
  text when hidden ("VALUE, password field").

### 2.11 Acceptance checklist (S25U)

- [ ] C1 MCP tab → "Add MCP Server": full-screen page with edge-to-edge bars matching C1;
      Save without a name shows "Server name is required." under the field and in the
      error box.
- [ ] Focus the last env VALUE field: the keyboard does not cover it and the app-bar
      "Add server" stays visible; IME "Done" saves.
- [ ] Back gesture on the editor (no edits): the predictive preview shows C1 behind it.
- [ ] Edit a server whose file entry has `"metadata": {...}`: the Metadata note lists the
      keys; Save asks "Save and remove metadata?"; after saving the file entry has no
      `metadata`.
- [ ] Add `github`, Local, Command `npx`, Args `-y, @modelcontextprotocol/server-github`,
      env `GITHUB_TOKEN=x` → "Add server": back on C1 the card shows "Local · stdio",
      "Env: GITHUB_TOKEN=[REDACTED]"; `cat ~/.cline/data/settings/cline_mcp_settings.json`
      in Termux shows the `transport` block.
- [ ] Edit it, rename to `gh`: the file no longer has `github`, has `gh`.
- [ ] Switch to Remote, URL empty → "Server URL is required for remote servers."
- [ ] Type in a field, press back: "Discard changes?" appears; "Keep editing" keeps the text.
- [ ] Delete from the card: "Delete MCP Server" dialog, "Delete" removes it from the file.
- [ ] Reveal an env value: a screenshot attempt is blocked (FLAG_SECURE); hide it again.
- [ ] Kill the dashboard (`pkill -f "index.js dashboard"`) and make it fail to start (rename
      nothing; use `helper.unsupported` debug switch): the fallback card shows, env
      section is replaced by the note, a stdio server without env saves through
      `cline mcp install`.
- [ ] Tab S7+ landscape: the editor is a 560dp dialog with Cancel / Add server in the
      footer and the IME does not cover the focused field; portrait: the full-screen page
      with a 720dp form column.
- [ ] TalkBack: radio cards, env remove buttons ("Remove env var GITHUB_TOKEN") and the
      error box are announced.

---

## 3. C3 / C4 — Marketplace

### 3.1 Purpose

Browse the curated Cline marketplace (skills, MCP servers, plugins), filter by type and
category, read an entry's details and setup requirements, and install or uninstall it into
the Termux kernel.

### 3.2 Desktop equivalent

`MarketplaceExplorerView` (`marketplace-explorer-view.tsx`) with `MarketplaceListRow`
(`marketplace-list-row.tsx`), catalog schema and parser from `lib/marketplace.ts`.

*(Android, strings reused from desktop source)*: the page title, description and
"Installed" header action come from `marketplace-view.tsx` (`directoryPageDetails`,
`onOpenInstalled`), a code path with no call site: the live Settings page renders
`MarketplaceExplorerView` (`settings-view.tsx`), which has no header. The app needs a title
on a standalone page, so it reuses those strings.

Copied: search across name/tagline/description/type/author/tag labels, type chips "All",
"Skills", "MCP servers", "Plugins" with counts and toggle-off behaviour, the category menu
("All categories", tag counts from the current type+query, selected tag always listed,
sorted by global count), sections in maturity order (skills, MCP, plugins) with type glyph,
plural and blurb, featured-first sorting, rows with verified badge, type pill only when
searching across all types, installed check, the detail pane (glyph, name, Verified, type
pill, tagline, Install/Uninstall with "Checking..." / "Installing..." / "Uninstalling...",
"Learn more", result message, type explainer, Author, About with tag chips that filter,
Environment setup cards with Required/Optional and "Get value", install notes), the
300 s install timeout, and the "No entries match the current filters." empty state.

Translated: master/detail becomes list → pushed detail on compact/medium and a list-detail
pane scaffold on expanded; the category dropdown becomes a bottom sheet; per-type tints
use M3 container roles (§3.5 "Type tints").

Not ported: the Connectors type and section (Composio, desktop-only).

### 3.3 Layout

C3 compact:

```
┌────────────────────────────────────────────┐
│ ←  Marketplace                         🧩  │  🧩 = "Installed" (opens Customize)
│ A curated set of plugins, MCP servers, and │
│ skills from the Cline community.           │
│ ┌────────────────────────────────────────┐ │
│ │ 🔍 Search marketplace                   │ │
│ └────────────────────────────────────────┘ │
│ (All) (⚡Skills 42) (▣ MCP servers 31) (🧩 …│  LazyRow of FilterChips, then
│                     [All categories ▾]     │  category AssistChip (end of row)
├────────────────────────────────────────────┤
│ ⚡ Skills  Step-by-step instructions Cline  │  section header (glyph 20dp + titleSmall
│           follows for a workflow. No setup.│   + blurb bodySmall)
│ ┌──┐ Code review                       ✓   │  ListItem 72dp: glyph tile 40dp,
│ │⚡│ Review diffs for bugs and style      │  headline + verified, tagline,
│ └──┘                                       │  trailing installed check
│ ┌──┐ Figma ✔                               │  ✔ = verified (info.color)
│ │⚡│ Turn Figma frames into components     │
│ └──┘                                       │
│ ▣ MCP servers  Live tools from an external │
│               server. You install and…     │
│ ┌──┐ GitHub ✔                          ✓   │
│ │▣ │ Issues, PRs and repos as tools        │
│ └──┘                                       │
└────────────────────────────────────────────┘
```

C4 compact (pushed):

```
┌────────────────────────────────────────────┐
│ ←  GitHub                                  │  MediumTopAppBar, title = entry name
├────────────────────────────────────────────┤
│ ┌────┐ [✔ Verified] [▣ MCP]                │  glyph 48dp, badges
│ │ ▣  │ Issues, PRs and repos as tools      │  tagline bodyMedium onSurfaceVariant
│ └────┘                                     │
│ [ Install ]  [ 🌐 Learn more ↗ ]           │  Button + OutlinedButton
│ Installed GitHub.                          │  result line (bodySmall)
│ ┌────────────────────────────────────────┐ │
│ │ ▣ MCP server. Live tools from an       │ │  type explainer, info.container
│ │   external server. You install and     │ │
│ │   configure it.                        │ │
│ └────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────┐ │
│ │ 👤 Author                               │ │  OutlinedCard, tappable when url
│ │ GitHub, Inc. ↗                         │ │
│ └────────────────────────────────────────┘ │
│ About                                      │  titleSmall
│ The official GitHub MCP server …           │
│ (Developer tools) (Git)                    │  AssistChips → filter by tag
│ Environment setup                          │
│ ┌────────────────────────────────────────┐ │
│ │ GITHUB_PERSONAL_ACCESS_TOKEN [Required]│ │
│ │ A token with repo scope.               │ │
│ │ Get value ↗                            │ │
│ └────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────┐ │
│ │ Requires Docker or npx …               │ │  install notes, surfaceContainer
│ └────────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

Expanded (Tab S7+ landscape): rail + `NavigableListDetailPaneScaffold` in its canonical
layout: list pane 400dp (search, chips, sections, one column) and the detail pane (max
content width 720dp, 32dp padding, desktop `px-8 py-8`) are **always both shown**. With no
selection the detail pane shows a placeholder (`EmptyState`, DS §4.2: `storefront` icon 48dp
`onSurfaceVariant`, title "Select an entry" *(Android)*, text "Tap a skill, MCP server or
plugin to see its details and install it." *(Android)*), so the first tap does not resize
the list. *(Desktop "Initially the catalog list fills the view" is not copied: making the
list fill the window would need a hidden detail pane and a layout jump on the first tap.)*
The detail header shows the name in `headlineSmall` and a close `IconButton` "Close
details" (clears the selection back to the placeholder) instead of the app-bar back arrow.

```
┌──┬───────────────────────────┬────────────────────────────────────────────────┐
│≡ │ Marketplace            🧩 │ ┌────┐ GitHub  [✔ Verified] [▣ MCP]         ✕ │
│✎ │ [🔍 Search marketplace   ] │ │ ▣  │ Issues, PRs and repos as tools         │
│  │ (All)(Skills)(MCP)(Plug…) │ └────┘ [Install] [🌐 Learn more ↗]           │
│💬│ [All categories ▾]        │ ┌────────────────────────────────────────────┐ │
│⏱ │ ▣ MCP servers  Live tools…│ │ ▣ MCP server. Live tools from an external… │ │
│🧩│ ▶ GitHub ✔             ✓  │ └────────────────────────────────────────────┘ │
│⚙ │   Linear                  │ Author  GitHub, Inc. ↗                         │
│  │   Sentry ✔                │ About …                                        │
└──┴───────────────────────────┴────────────────────────────────────────────────┘
```

Medium (Tab S7+ portrait): as compact, centred at 720dp.

Category sheet (`ModalBottomSheet`, DS §5.5): title "Filter by category", a radio list
(`ListItem` + `RadioButton`, 56dp rows): "All categories", then each visible tag with its
count trailing (`labelMedium`).

### 3.4 States

| State | C3 list | C4 detail |
| --- | --- | --- |
| No selection (expanded only) | — | placeholder "Select an entry" (§3.3) |
| Loading catalog | `CircularProgressIndicator` 24dp + "Loading marketplace..." centred in the list area; chips hidden | expanded: placeholder; compact/medium: not reachable |
| Catalog error | `InlineErrorCard` with the error (role alert) + "Retry" *(Android)*; chips hidden | detail of a previously open entry stays (in-memory catalog) |
| Stale catalog (offline, cached copy) | list from the OkHttp cache + `Surface(surfaceContainerHighest)` notice "Showing the last downloaded catalog." *(Android)* at the top | normal |
| Populated | sections | full detail |
| Filtered empty | "No entries match the current filters." centred | — |
| Checking installed state | rows without checks | primary button disabled, spinner + "Checking..." |
| Installing / uninstalling | the row keeps its place; no check change until done | button disabled, spinner + "Installing..." / "Uninstalling..." |
| Installed / uninstalled | check appears / disappears | message `bodySmall onSurfaceVariant` (helper/CLI message, e.g. "Installed GitHub.") |
| Failed | — | message `bodySmall error` with the error (redacted, max 12 lines then "Show more" *(Android)* expands) |
| Helper starting | `LinearProgressIndicator` + "Starting settings helper..." under the app bar | same |
| Helper unavailable | SP §0.3 card at the top with body "Cline could not start its settings helper in Termux. Installs run with the Cline command line instead, and installed checks may miss items." *(Android)* | same card above the buttons |
| Kernel down / Termux blocked | catalog still browsable; `KernelBanner` / `KernelBlockedCard` at the top | Install/Uninstall disabled with the banner |
| No network and no cache | error "Couldn't reach the marketplace. Check your connection." *(Android)* + "Retry" | — |

### 3.5 Copy

| Element | String |
| --- | --- |
| Title | "Marketplace" |
| Description | "A curated set of plugins, MCP servers, and skills from the Cline community." |
| Installed action | "Installed" (tooltip, CD) |
| Search | placeholder and CD "Search marketplace"; clear CD "Clear search" *(Android)* |
| Type chips | "All"; "Skills", "MCP servers", "Plugins" + count (`labelSmall`, 70 % emphasis via `onSurfaceVariant`) |
| Category chip | "All categories" or the tag label; CD "Filter by category" |
| Category sheet | title "Filter by category"; first row "All categories" |
| Type meta (label / plural / short / blurb) | skill: "Skill" / "Skills" / "Skill" / "Step-by-step instructions Cline follows for a workflow. No setup."; mcp: "MCP server" / "MCP servers" / "MCP" / "Live tools from an external server. You install and configure it."; plugin: "Plugin" / "Plugins" / "Plugin" / "A bundle of tools, hooks, and skills built for Cline." |
| Row badges | verified CD "Verified"; installed check CD "Installed"; type pill = short label |
| Loading | "Loading marketplace..." |
| Empty | "No entries match the current filters." |
| HTTP error | "Failed to fetch marketplace: <status>" (desktop `lib/marketplace.ts`) |
| Detail buttons | "Install" · "Uninstall" · "Checking..." · "Installing..." · "Uninstalling..." · "Learn more" |
| Detail close (expanded) | CD "Close details" |
| Detail placeholder (expanded) *(Android)* | "Select an entry" / "Tap a skill, MCP server or plugin to see its details and install it." |
| Verified badge | "Verified" |
| Explainer | "<Label>." (SemiBold, type tint) + " " + blurb |
| Author | "Author"; CD "Author: <name>" (+ ", opens in browser" when a URL exists) |
| About | "About"; tag chip CD "Filter by <tag label>" |
| Env | "Environment setup"; badges "Required" / "Optional"; link "Get value" |
| Uninstall confirm *(Android)* | "Uninstall <name>?" / "This removes <name> from this Cline environment." / "Cancel" / "Uninstall" |
| Install success fallback (CLI route) | "Installed <name>." (dashboard wording); skill: "Installed <name> globally for Cline." |
| Install failure (CLI route) | "MCP install failed with exit code <n>:\n<output>" · "Skill install failed with exit code <n>:\n<output>" · "Plugin install failed with exit code <n>:\n<output>" (dashboard wording) |
| Interrupted *(Android)* | "Check the result: the install may have finished in Termux." |

Type tints (map desktop amber/sky/violet onto M3 roles, never hard-coded colours):

| Type | Glyph (DS §7) | Tile / pill container | Glyph + pill text |
| --- | --- | --- | --- |
| skill | `bolt` *(new symbol, Appendix A)* | `ClineColors.warning.container` | `ClineColors.warning.onContainer` |
| mcp | `dns` *(new symbol; desktop `Server`)* | `ClineColors.info.container` | `ClineColors.info.onContainer` |
| plugin | `extension` | `primaryContainer` | `onPrimaryContainer` |

### 3.6 Interactions and gestures

- Search filters live (client side). Type chip tap: select that type; tapping the selected
  type returns to All (desktop toggle). Changing type or query keeps the selected tag
  even when it now has zero matches (desktop rule, so a filter never silently empties the
  list while its option is hidden).
- Category chip opens the sheet; choosing a row applies and closes; back or swipe closes
  without change.
- Row tap → C4 (compact/medium push; expanded: detail pane, selected row gets
  `secondaryContainer` background). The selection is resolved against the full catalog so
  an open detail stays open while filters change (desktop).
- Install: tap → (helper route or CLI route, §3.8). Uninstall: tap → confirm dialog → route.
  One entry can have one action at a time; different entries may run in parallel.
- Tag chip in C4: sets the tag filter and returns to C3 (compact/medium pops; expanded
  keeps the pane and scrolls the list to top).
- "Learn more" (homepage, else repo), Author (url) and "Get value" (env url) open with
  `openExternal(url)` (§0.8).
- Pull to refresh on C3 re-fetches the catalog (bypassing the in-memory cache, not the HTTP
  cache validation) and the installed keys.

### 3.7 Navigation and back

- Entered from C1 (app-bar "Marketplace", with the type preset §1.7), Settings row
  "Marketplace", and deep link `marketplace?type=mcp`.
- "Installed" action navigates to `customize?tab=<plugins|skills|mcp>` matching the active
  type filter (Tools when All), popping to an existing `customize` entry if it is on the
  back stack instead of pushing a second one.
- Back order: sheet → search focus → (expanded) selection → pop. On expanded both panes
  stay visible, so back with a selection clears it to the placeholder (navigator
  `navigateBack()`, the scaffold's built-in predictive pane back); back with no selection
  pops `marketplace`.
- Leaving C3/C4 releases the helper lease (SP §0.3, 30 s grace).

### 3.8 Data bindings

| Need | Call | Notes |
| --- | --- | --- |
| Catalog | OkHttp `GET https://cline.github.io/marketplace/catalog.json`, header `Accept: application/json` | The URL the dashboard proxies (`apps/cline-hub/src/server/marketplace.ts` `MARKETPLACE_CATALOG_URL`); fetched directly so browsing never needs the helper. OkHttp disk cache 4 MB in `cacheDir/marketplace`; on network failure retry with `Cache-Control: only-if-cached, max-stale=2592000` (30 days) and show the stale notice. In-memory TTL 10 min. Parse with a Kotlin port of `fetchMarketplaceCatalog` (drop entries without id/type/name/tagline/description/`install.command` or with empty `install.args`). |
| Installed keys (helper) | helper `list_marketplace_installed_entries {entries: catalog.entries}` → `{installedKeys: ["<type>:<id>", …]}` | after the catalog loads and after every install/uninstall |
| Installed keys (fallback) | Hub `settings.list {workspaceRoot: ws, cwd: ws}` (no plugin tools) | mcp entry installed ⇔ an MCP item name equals `install.args[0]`; skill ⇔ a skill name equals `entry.id` or `entry.name` (case-insensitive); plugin ⇔ a plugin `name` equals `entry.id` or its path contains `/<entry.id>` (desktop match values). Marked approximate (the helper card says so). |
| Install (helper) | helper `install_marketplace_entry {entry}` (the full entry object, as the desktop sends) with client timeout **300 s** | result `{status, message, output?}` |
| Uninstall (helper) | helper `uninstall_marketplace_entry {entry}`, 300 s | same result |
| Install (fallback) | mcp: RUN_COMMAND `cline mcp install --yes --json <install.args…>`; plugin (exactly one arg): `cline plugin install <install.args[0]> --json`; skill: `npx -y skills@latest add <install.args…> -g -a cline -y` (the dashboard's own commands) | timeout 300 s; exit 0 = success; message per §3.5; output redacted with the `Redactor` (§9.6) |
| Uninstall (fallback) | mcp: `cline mcp uninstall <install.args[0]> --json`; plugin: `cline plugin uninstall <install.args[0]> --json`; skill: `cline skill remove <dir> -g -y` where `<dir>` is the installed directory name of the matching `settings.list` skill (the §1.8 rule: `basename(dirname(path))` for a `…/SKILL.md` path), matching the kernel's `npx -y skills@latest remove <installedName> -g -y`; no matching skill → the entry is treated as not installed (kernel message "<name> is not installed.") | 120 s |
| After a change | `MarketplaceRepository.inventoryChanged.emit()` → C1 invalidates its cache | |

ViewModel:

```kotlin
data class MarketplaceUiState(
    val catalog: CatalogLoad,              // Loading | Error(message) | Loaded(catalog, stale: Boolean)
    val query: String, val type: MarketplacePrimitiveType?, val tag: String?,
    val sections: List<MarketplaceSection>,// derived: MATURITY_ORDER, featured first
    val visibleTags: List<TagCount>, val typeCounts: Map<MarketplacePrimitiveType, Int>,
    val showTypePills: Boolean,            // type == null && query.isNotBlank()
    val installedKeys: Set<String>, val installedReady: Boolean, val installedApproximate: Boolean,
    val actions: Map<String, EntryAction>, // Idle | Installing | Uninstalling | Done(message) | Failed(message)
    val selectedKey: String?,              // expanded pane selection
    val helper: HelperState, val kernel: KernelGate,
)
```

`query`, `type`, `tag` and `selectedKey` are in `SavedStateHandle`.

### 3.9 Edge cases

- **App backgrounded during an install:** the helper socket freezes after ~10 s (KC); the
  install continues in Termux. On resume, any action older than its timeout or whose
  socket closed is resolved by re-reading installed keys: if the key flipped, show the
  normal success message; otherwise the "Check the result…" message.
- **RUN_COMMAND result truncated** (100 KB cap, KC): only the exit code and the tail are
  used; the message shows the last 12 lines.
- **Termux asleep** when Install is tapped: RUN_COMMAND wakes Termux; the helper route
  first needs the helper (`Starting`), which also boots the Hub (SP §0.3 item 4).
- **Hub draining** during the fallback `settings.list`: installed checks wait (§0.5).
- **Entry disappears from a refreshed catalog** while its detail is open: the detail stays
  with the last known data; Install becomes unavailable and shows "This entry is no longer
  in the marketplace." *(Android)*.

### 3.10 Accessibility

- Rows: one focus target reading "<name>, <type label>, verified, installed, <tagline>"
  (omitting absent parts).
- Chips: `Role.Checkbox`-like selected state via `FilterChip` defaults; counts read as
  "Skills, 42".
- The category sheet's title is focused on open; radio rows are `selectable`.
- Detail result messages are polite live regions; failures assertive.
- Type tints are never the only signal: every glyph has the type label nearby
  (section header or pill).

### 3.11 Acceptance checklist (S25U)

- [ ] Settings → "Marketplace": "Loading marketplace..." then Skills, MCP servers, Plugins
      sections in that order, featured entries first.
- [ ] Type "git": rows from several types show type pills; select "MCP servers": pills
      disappear; tap "MCP servers" again: back to All.
- [ ] Category sheet: counts change with the type filter; pick a tag; set a type with no
      entries in that tag: the list is empty and the tag remains selected in the chip.
- [ ] Open an MCP entry with env vars: "Environment setup" lists them with Required /
      Optional; "Get value" opens a Custom Tab.
- [ ] Install an MCP entry: "Installing..." then "Installed <name>."; the row shows the
      check; C1 MCP tab lists it with the "Marketplace" badge and setup instructions.
- [ ] Uninstall it: confirm dialog; "Uninstalling..."; check disappears; C1 no longer lists it.
- [ ] Install a skill, switch to another app for 2 minutes, come back: the result is
      reconciled (check shown or the "Check the result…" line).
- [ ] Airplane mode with a cached catalog: list shows with "Showing the last downloaded
      catalog."; without cache (clear data): the connection error + Retry.
- [ ] Tab S7+ landscape: list pane 400dp + detail pane with the "Select an entry"
      placeholder before any tap; tapping a row fills the detail without resizing the
      list; "Close details" and predictive back return to the placeholder; back again
      leaves Marketplace.
- [ ] TalkBack: a row reads its type, verified and installed state in words.

---

## 4. C5 / C6 / C7 — Schedules

### 4.1 Purpose

Create routines that run an agent on a schedule inside the Cline Hub (daily, weekly on
chosen days, or once at a date and time), see when each runs next and how the last run
went, pause/resume, run now, edit, delete, and open the session a run produced.

### 4.2 Desktop equivalent

`RoutineSchedulesContent` (`routine-view.tsx`), `ROUTINE_TEMPLATES`
(`routine-templates.ts`), `preserveRoutineCron`, `routineScheduleTimezone`. The desktop
calls the sidecar's `*_routine_schedule` commands, which map 1:1 onto Hub `schedule.*`
(`apps/cline-hub/src/server/schedules.ts`); the app calls the Hub directly (FM "Via: hub").

Copied: page description and refresh, the card fields (enabled dot, name, mode badge,
trigger badge via `formatScheduleTrigger`, prompt, model, last run, last result, last
session, last error, next run, active execution, tags), the switch with its tooltips, Edit /
Run now / Pause / Resume / Delete actions and their CDs, the run-now flow (toast, 15 s poll
for the session id of *that* execution only, then open the session), "Suggested"
templates hidden once a schedule with the same name exists, the editor (title "Schedule",
create/update descriptions, Name, Frequency Daily/Weekly/Once, Date, Days, Time, the
current-cron note, Prompt, Provider, Model, Workspace, System prompt, Timeout seconds, Tags,
every validation string, default prompt, default mode `yolo` for new schedules, edits
keep the mode), the details dialog (summary grid, raw JSON, Runs list with status icons,
"Show all N runs"), and the "Delete Routine" alert.

Translated: the details dialog becomes a pushed page on compact/medium and the detail
pane of a list-detail scaffold on expanded; the editor dialog becomes a full-screen route on
compact/medium (`BasicAlertDialog` on expanded); HTML date/time inputs become the M3 `DatePickerDialog` and
`TimePicker`; the days dropdown becomes a row of seven `FilterChip`s; the provider/model
comboboxes become `ExposedDropdownMenuBox`es; toasts become snackbars.

Deviations: the app-bar title is "Schedules" (the navigation label, DS §5.2; the desktop
page header says "Schedule"); the card on compact and in the expanded list pane shows a
subset of lines (§4.3) and C7 shows **every** desktop card line (ID, Prompt in full, Model,
Last run, Last result, Last session, Last error, Next run, Active, Tags): the desktop
summary grid first, then the card lines the grid lacks, so nothing the desktop card shows
is reachable only through the raw JSON; the details page loads up to 50 runs (the
desktop dialog only had the last run per schedule in memory).

### 4.3 Layout

C5 compact:

```
┌────────────────────────────────────────────┐
│ ≡  Schedules                           ⟳   │  ≡ = menu (top-level), ← when pushed from Settings
│ Run agents on cron schedules for recurring │
│ automations like daily summaries and code  │
│ reviews.                                   │
│ ┌────────────────────────────────────────┐ │
│ │ ⚠ Schedules run inside the Cline Hub in│ │  KeepAliveNotice (warning.container),
│ │ Termux. Keep the kernel running so they│ │  only when kernel.keepAwake is off or an
│ │ fire on time.      [Keep it running]   │ │  enabled schedule exists
│ └────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────┐ │
│ │ ● Daily change digest           [●━]   │ │  OutlinedCard, tap → C7
│ │ [yolo] [Daily · 5:00 PM · Asia/Shanghai]│ │  badges (Surface extraSmall, labelSmall)
│ │ You write a daily engineering digest   │ │  prompt, 2 lines, onSurfaceVariant
│ │ for this repository. ## Task Review…   │ │
│ │ Next run: Oct 3, 2026, 5:00 PM         │ │  bodySmall
│ │ Last result: completed at Oct 2, 2026… │ │
│ │ Last error: Provider returned 401      │ │  error colour, only when present
│ │ Active: exec_9f2… since 5:00:12 PM     │ │  only when running
│ │ ─────────────────────────────────────  │ │
│ │ [▶ Run now]                    ✎   ⋮   │ │  FilledTonalButton + Edit + overflow
│ └────────────────────────────────────────┘ │
│ SUGGESTED                                  │  labelSmall overline
│ ┌────────────────────────────────────────┐ │
│ │ [🐞] Find critical bugs        [Daily] │ │  OutlinedCard, tap → C6 with template
│ │      Sweep recent commits for high-    │ │
│ │      severity bugs that slipped past…  │ │
│ └────────────────────────────────────────┘ │
│                    ╭────────────────────╮  │
│                    │ + New Schedule     │  │  ExtendedFloatingActionButton
│                    ╰────────────────────╯  │
└────────────────────────────────────────────┘
```

The FAB sits over the list; the list uses the FAB bottom padding of §0.6. Medium: the
same single column, 720dp centred, "Suggested" cards in two columns (desktop
`sm:grid-cols-2`).

Expanded (Tab S7+ landscape): rail + `NavigableListDetailPaneScaffold` (§0.6). List pane
400dp: the compact cards (one column) and "Suggested" (one column), the FAB at the list
pane's bottom end; the card's action row shows icon buttons Edit, Run now, Pause/Resume,
Delete, then the switch (desktop order) because the pane is wide enough for 48dp targets.
Detail pane: C7 for the selected schedule (selected card gets a `secondaryContainer`
container); with no selection, a placeholder (`EmptyState`: `schedule` icon, "Select a
schedule" *(Android)*, "Tap a schedule to see its details and runs." *(Android)*). Card
tap selects (does not push). Back with a selection clears it; back with none pops /
returns to Chats per §0.2.

C6 editor compact (full-screen route, §0.2):

```
┌────────────────────────────────────────────┐
│ ✕  Schedule                Create Schedule │
├────────────────────────────────────────────┤
│ Create a scheduler routine.                │
│ Name                                       │
│ [ Daily code review                      ] │
│ Schedule                                   │
│ ┌────────────────────────────────────────┐ │
│ │ Frequency                              │ │
│ │ [ Once                               ▾]│ │
│ │ Date                    Time           │ │
│ │ [ Oct 4, 2026  📅 ]     [ 9:00 AM  🕘 ] │ │  read-only fields → pickers
│ └────────────────────────────────────────┘ │
│ (weekly) Days                              │
│ (Mon)(Tue)(Wed)(Thu)(Fri)(Sat)(Sun)        │  FilterChips, wrap (FlowRow)
│ Prompt                                     │
│ [ Review PRs opened yesterday and        ] │  4–10 lines
│ [ summarize issues.                      ] │
│ Provider                                   │
│ [ cline                               ▾ ] │
│ Model                                      │
│ [ anthropic/claude-sonnet-5           ▾ ] │  editable dropdown
│ Workspace                                  │
│ [ /data/data/com.termux/files/home/app 📂] │  trailing "Choose workspace"
│ System prompt (optional)                   │
│ [                                        ] │
│ Timeout seconds (optional)                 │
│ [ 3600                                   ] │
│ Tags (comma-separated, optional)           │
│ [ automation,review                      ] │
│ ┌────────────────────────────────────────┐ │
│ │ ⚠ Prompt is required.                  │ │  errorContainer
│ └────────────────────────────────────────┘ │
└────────────────────────────────────────────┘
```

Medium: the same full-screen route with the form in a 720dp centred column and the
desktop's two-column grid (Provider | Model, Timeout | Tags side by side; the rest full
width). Expanded: `BasicAlertDialog` 560dp wide (§0.2 dialog properties, `imePadding` on the
content), max 90 % height, the same two-column grid, footer "Cancel" + "Create Schedule",
no top-bar action.

C7 details compact (pushed):

```
┌────────────────────────────────────────────┐
│ ←  Daily change digest           ▶  ✎  ⋮  │  Run now, Edit, overflow
├────────────────────────────────────────────┤
│ Schedule:  Daily · 5:00 PM · Asia/Shanghai │  label bodySmall onSurfaceVariant,
│ Mode:      yolo                            │  value bodyMedium (DS #13 rows)
│ Model:     cline/anthropic/claude-sonnet-5 │  (desktop summary grid)
│ Enabled:   yes                             │
│ Last run:  Oct 2, 2026, 5:00 PM            │
│ Next run:  Oct 3, 2026, 5:00 PM            │
│ ID:        sch_9f2c…                   ⧉   │  ClineMono.small; long-press or ⧉ copies
│ Last result: completed at Oct 2, 5:03 PM   │
│ Last session                           ›   │  ListItem 56dp → chat/{sessionId}
│ sess_81a…                                  │  (ClineMono.small supporting text)
│ Last error: Provider returned 401          │  error colour, only when present
│ Active: exec_9f2… since 5:00:12 PM         │  only when running
│ Prompt                                     │  titleSmall
│ You write a daily engineering digest for   │  full prompt, bodyMedium, selectable,
│ this repository. ## Task Review every …    │  no line clamp
│ Tags                                       │
│ (automation) (review)                      │  AssistChip-styled read-only labels
│ ┌────────────────────────────────────────┐ │
│ │ {                                      │ │  raw JSON, ClineMono.small,
│ │   "scheduleId": "sch_…",               │ │  surfaceContainer, max 256dp, scroll,
│ │   …                                    │ │  selectable
│ └────────────────────────────────────────┘ │
│ Runs                           12 results  │
│ ┌────────────────────────────────────────┐ │
│ │ ✓ Completed       Oct 2, 5:03 PM   ↗   │ │  ListItem 56dp, tap → chat
│ │ ✕ Failed          Oct 1, 5:01 PM   ↗   │ │
│ │   Provider returned 401                │ │  error line
│ │ ◷ Pending         Oct 3, 5:00 PM       │ │  no sessionId: not clickable, no ↗
│ └────────────────────────────────────────┘ │
│ [ Show all 12 runs ⌄ ]                     │  TextButton, when > 3
└────────────────────────────────────────────┘
```

Medium: the same page in a 720dp centred column. Expanded: C7 is the **detail pane** of the
Schedules list-detail scaffold (above), not a dialog: the same column, max 720dp, with the
summary grid in two columns (desktop `sm:grid-cols-2`) and the app-bar actions (Run now,
Edit, overflow) in the pane's own small `TopAppBar` with a close `IconButton` "Close
details" in place of the back arrow. "Last session" and run rows navigate the NavHost to
`chat/{sessionId}` (the Schedules scaffold is left; back returns to it with the same
selection).

### 4.4 States

| State | C5 | C6 | C7 |
| --- | --- | --- | --- |
| Loading (first) | `PageEmptyState` "Loading schedules..." | — | `LinearProgressIndicator` + skeleton rows |
| Refreshing | cached cards + refresh spinner | — | progress bar |
| Empty | `PageEmptyState` "No schedules yet. Start from a suggestion below, or create your own with New Schedule." then "Suggested" | — | Runs: "No runs yet." |
| Populated | cards sorted by name (case-insensitive) | — | grid + JSON + runs |
| Row busy | that card's actions and switch disabled (desktop `busyScheduleIds`, a second tap on the same card is ignored synchronously) | — | app-bar actions disabled |
| Triggering | Run now shows a 16dp spinner and "Run now" stays; snackbar on reply | — | same |
| Active run | card line "Active: <executionId> since <time>"; status dot stays `primary` (enabled) | — | run row with spinner |
| Saving | — | primary action "Creating..." / "Saving...", everything disabled, back consumed | — |
| Validation error | — | error box + the field marked `isError` | — |
| Error | red `InlineErrorCard` with the reply message above the list | error box with the message | error card + "Retry" |
| Kernel down | §0.4 (`KernelStatePanel`, or cached cards under the `KernelBanner`) | Save disabled + banner | banner |
| Reduced authority (Origin-only connection, KC developer switch) | notice "Showing schedules for <workspace basename> only." *(Android)*. On an Origin-only connection the Hub always scopes the client to its **own** `--cwd` (`browser-websocket.ts` `registrationAuthority`: registering with any other `workspaceRoot` fails with `invalid_client_registration`, HF fixture 06; registering without `workspaceContext` is given the Hub's root), and `crossWorkspace` is false, so `allWorkspaces` is ignored. The dev path therefore registers with `workspaceContext = {workspaceRoot: $HOME, cwd: $HOME}` (the phone's `cline hub start` runs from `$HOME`), and `<workspace basename>` is always "home". *Reviewed: the claim that registering without `workspaceContext` makes `schedule.*` fail does not match HF fixture 06 (`schedule.list` succeeded) or the source; the explicit `$HOME` context is still sent so the scope is visible in code.* | workspace field locked to `$HOME`; other folders are rejected before sending with the field error "Schedules can only use the home folder on this connection." *(Android)* | — |
| No selection (expanded only) | — | — | detail pane placeholder "Select a schedule" / "Tap a schedule to see its details and runs." *(Android)* |
| Keep-alive off | `KeepAliveNotice` (Appendix A) at the top when any schedule is enabled and either `kernel.keepAwake` (Kernel status sheet E, "Keep Termux awake") is off or the One UI checklist (Kernel spec D) has unconfirmed items; dismissible per app version | — | — |

### 4.5 Copy

C5:

| Element | String |
| --- | --- |
| Title | "Schedules" (navigation label; desktop page header "Schedule") |
| Description | "Run agents on cron schedules for recurring automations like daily summaries and code reviews." |
| Refresh | CD/tooltip "Refresh" *(Android)* |
| FAB | "New Schedule" |
| Loading | "Loading schedules..." |
| Empty | "No schedules yet. Start from a suggestion below, or create your own with New Schedule." |
| Card lines (labels) | "ID:", "Prompt:", "Model:", "Last run:", "Last result:", "Last session:", "Last error:", "Next run:", "Active:" ("<executionId> since <time>"), "Tags:" |
| Trigger badge | `formatScheduleTrigger`: "Once · <date-time>", "Daily · <time>", "<days> · <time>" (+ " · <timezone>" when set); days: "Every day", "Weekdays", or "Mon, Wed, Fri" |
| Mode badge | `schedule.mode` verbatim ("act", "plan", "yolo") |
| Model | "<provider>/<model>" or "-" |
| Last result | "<status> at <date-time>" or "<status>" or "-" |
| Action CDs | "Edit <name>", "Run <name> now", "Pause <name>" / "Resume <name>", "Delete <name>", switch "Enable <name>" |
| Tooltips | "Edit schedule", "Run now", "Pause schedule" / "Resume schedule", "Delete schedule", switch "Enabled — tap to disable" / "Disabled — tap to enable" *(desktop "click" → "tap")* |
| Run now label (compact button) | "Run now" |
| Overflow (compact) | "Pause schedule" / "Resume schedule", "Delete schedule" |
| Suggested | overline "Suggested"; template badge "Daily" / "Weekly" |
| Snackbars | "Run started. \"<name>\" was queued to run now." · "Run not started. \"<name>\" did not queue a run — the schedule may be disabled or deleted." (Long) · "Failed to start run. <message>" (Long) |
| Keep-alive notice *(Android)* | "Schedules run inside the Cline Hub in Termux. Keep the kernel running so they fire on time." button "Keep it running" → Kernel spec D ("Keep the kernel running" checklist); close CD "Dismiss" |
| Delete dialog | title "Delete Routine"; text "This will delete \"<name>\" and remove future scheduled runs." (fallback name "this routine"); buttons "Cancel", "Delete" (error); busy "Deleting..." *(Android, DS #16)* |

Templates (copy `ROUTINE_TEMPLATES` verbatim into `RoutineTemplates.kt`, including the
four full prompt constants `FIND_CRITICAL_BUGS_PROMPT`, `SECURITY_SCAN_PROMPT`,
`DAILY_DIGEST_PROMPT`, `UPDATE_DOCS_PROMPT` from
`apps/examples/desktop-app/webview/components/views/settings/routine-templates.ts`; a unit
test asserts their SHA-256 equals the desktop file's string literals):

| id | Title | Description | name | Frequency | Days | Time | Icon (DS §7 style) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| find-critical-bugs | "Find critical bugs" | "Sweep recent commits for high-severity bugs that slipped past review, and fix the ones with a concrete trigger." | "Find critical bugs" | daily | all | 9:00 | `bug_report` |
| security-scan | "Scan for vulnerabilities" | "Audit the codebase for exploitable security issues with a validated end-to-end attack path." | "Security scan" | weekly | MON | 8:00 | `shield` |
| daily-digest | "Summarize changes daily" | "Get a skimmable digest of everything that landed in the last 24 hours, plus risks worth watching." | "Daily change digest" | daily | all | 17:00 | `newspaper` |
| update-docs | "Keep docs updated" | "Refresh documentation whenever the code drifts away from it, verified against the source." | "Update docs" | weekly | FRI | 10:00 | `menu_book` |

C6:

| Element | String |
| --- | --- |
| Title | "Schedule" |
| Description | "Create a scheduler routine." / "Update this scheduler routine." |
| Name | label "Name", placeholder "Daily code review" |
| Schedule group | label "Schedule"; edit of a recurring schedule: "Current cron expression: <cron>. Changing the timing controls replaces this expression." (cron in `ClineMono.small`) |
| Frequency | label "Frequency"; "Daily", "Weekly", "Once" |
| Date | label "Date"; picker title "Select date" (M3 default) |
| Days | label "Days"; chips "Mon" "Tue" "Wed" "Thu" "Fri" "Sat" "Sun"; summary under the chips = `formatScheduleDays` or "Choose days" when none |
| Time | label "Time" |
| Prompt | label "Prompt"; default text "Review PRs opened yesterday and summarize issues." |
| Provider / Model | labels "Provider", "Model"; empty menus "No providers found." / "No models found." |
| Workspace | label "Workspace"; trailing CD "Choose workspace" *(Android)* |
| Optional fields | "System prompt (optional)"; "Timeout seconds (optional)" placeholder "3600"; "Tags (comma-separated, optional)" placeholder "automation,review" |
| Actions | "Cancel"; "Create Schedule" / "Save Changes"; busy "Creating..." / "Saving..." |
| Validation | "Routine name is required."; "Choose a one-time date and time in the future."; "Select at least one weekday."; "Prompt is required."; "Workspace is required."; timeout not a positive integer *(Android)*: "Enter a whole number of seconds, or leave it empty." |
| Discard *(Android)* | "Discard changes?" / "Your changes to this schedule will be lost." / "Keep editing" / "Discard" |

C7:

| Element | String |
| --- | --- |
| Title | schedule name (fallback "Schedule") |
| Grid labels | "Schedule:", "Mode:", "Model:", "Enabled:" ("yes" / "no"), "Last run:", "Next run:" |
| Card lines below the grid (desktop card labels) | "ID:" (copy `IconButton` CD "Copy schedule ID" *(Android)*, snackbar "Copied schedule ID" on API ≤ 32), "Last result:", "Last session" row (supporting text = session id, CD "Open last session" *(Android)*), "Last error:", "Active:" ("<executionId> since <time>"), "Prompt" (full text), "Tags" (one label per tag); absent values hide their line exactly as the desktop card does |
| Runs header | "Runs"; count plurals "%d result" / "%d results" |
| Run row | status capitalised ("Completed", "Failed", "Timeout", "Aborted", "Running", "Pending", "Success"); fallback "Unknown result"; error line; timestamp `formatDateTime(endedAt ?: startedAt ?: triggeredAt)`; open icon CD "Open session" *(Android)*; a run without `sessionId` is not clickable, has no open icon, and its row label ends with ", no session" *(Android)* (desktop `disabled={!execution.sessionId}`) |
| Empty | "No runs yet." |
| More | "Show all %d runs" |

### 4.6 Interactions and gestures

- **Card tap** → C7 (compact/medium: push; expanded: select into the detail pane).
  Controls inside the card are excluded (desktop `closest()` rule).
- **New Schedule defaults** (desktop `createForm` initial state and `openCreateDialog`
  without a template): Name empty; Frequency **Once**; Date = **tomorrow** (device zone);
  Time **9:00** (`hour = 9`, `minute = 0`); Days preset **Mon–Fri** (kept in the form, so
  switching to Weekly shows Mon–Fri selected); Prompt "Review PRs opened yesterday and
  summarize issues."; Provider/Model = the remembered selection if available (§4.8), else
  `cline` / `CLINE_DEFAULT_MODEL_ID`; Workspace per the rule below; System prompt, Timeout
  and Tags empty; Enabled on. A template overrides name, frequency, days, time and prompt;
  the date is still tomorrow.
- **Switch** → `schedule.enable` / `schedule.disable`, then a silent refresh (no loading
  state). The switch waits for the reply (no optimistic flip).
- **Run now** → §4.8 trigger flow. While the 15 s poll runs the button shows its spinner;
  navigating away cancels the follow-up navigation (desktop `mountedRef`).
- **Edit** → C6 prefilled (`parseScheduleTrigger`, `getScheduleProviderModel` defaults
  `cline` / `CLINE_DEFAULT_MODEL_ID`); the schedule's provider/model are added to the
  choice lists if missing (desktop).
- **Suggested card** → C6 with the template (name, frequency, days, time, prompt).
- **Frequency** switch keeps time and the days set (Mon–Fri unless the user changed it);
  an edited schedule whose stored days are empty gets Mon–Fri (desktop
  `parseScheduleTrigger`).
- **Date field** (Once): opens `DatePickerDialog`, `selectableDates` = today onward
  (minimum = now + 1 minute, seconds zeroed, desktop `minimumOneTimeDateTime`). If the
  chosen date is the minimum date and the time is earlier than the minimum time, the time
  is set to the minimum time (desktop clamp).
- **Time field**: opens an M3 `TimePicker` in a dialog, 24h when
  `DateFormat.is24HourFormat(context)`; for Once on the minimum date, earlier times are
  clamped to the minimum.
- **Days chips**: multi-select, kept in Mon…Sun order (`normalizeScheduleDays`).
- **Model**: an editable dropdown: typing filters the list and a typed id not in the list
  is accepted (fallback mode has one model per provider; the desktop combobox is
  read-only because its list is complete).
- **Workspace**: free text plus the picker (SP S9, "scope" mode). Default for a new
  schedule = the most recently used workspace (`WorkspaceStore`), else
  `/data/data/com.termux/files/home` (the desktop's `get_process_context` workspace).
- **Delete** (card overflow, C7 overflow) → "Delete Routine" dialog → `schedule.delete`.
- **Run rows** (C7): rows with a `sessionId` open `chat/{sessionId}`; rows without one are
  plain, non-clickable `ListItem`s (no ripple, no open icon).
- **Last session** row (C7): opens `chat/{sessionId}` of the last execution.
- **Pull to refresh** on C5 and C7.

### 4.7 Navigation and back

- `schedules` is a top-level destination (drawer/rail; `menu` icon on compact, back →
  Chats) and a pushed page from the Settings row "Schedules" (back arrow, back →
  Settings), §0.2. Run rows, "Last session" and the run-now follow-up navigate to
  `chat/{sessionId}`; back from the chat returns to Schedules (C5 or C7, or on expanded the
  scaffold with the same selection).
- C6 back: discard confirm when dirty; consumed while saving. After a successful save:
  close C6 and refresh C5 (and C7 if it opened C6).
- After deleting from C7: pop to C5.

### 4.8 Data bindings

Every schedule command carries `allWorkspaces: true` (the app's token-authenticated
connection has cross-workspace authority, `schedule-command-service.ts`
`spansAllWorkspaces`; without it the Hub scopes every command to the registration
workspace and `schedule.get` on another workspace's schedule fails with "schedule does not
exist in this workspace"). The coordinator always registers with a `workspaceContext`
(default `$HOME`), because schedule commands require "a registered workspace client".

| Need | Call |
| --- | --- |
| List | `schedule.list {limit: 200, allWorkspaces: true}` → `{schedules: ScheduleRecord[]}`; in parallel `schedule.active {allWorkspaces: true}` → `{executions}` and `schedule.upcoming {limit: 30, allWorkspaces: true}` → `{runs: [{scheduleId, name, nextRunAt}]}`; then per schedule `schedule.list_executions {scheduleId, limit: 1, allWorkspaces: true}` → `{executions}` (the desktop overview shape) |
| Details | `schedule.get {scheduleId, allWorkspaces: true}` → `{schedule}`; `schedule.list_executions {scheduleId, limit: 50, allWorkspaces: true}` |
| Create | `schedule.create {allWorkspaces: true, name, cronPattern, timezone?, metadata?, prompt, modelSelection: {providerId, modelId}, mode: "yolo", workspaceRoot, cwd: workspaceRoot, systemPrompt?, timeoutSeconds?, maxParallel: 1, enabled: true, tags?, createdBy: "android"}` → `{schedule}` |
| Update | `schedule.update {allWorkspaces: true, scheduleId, name, cronPattern, timezone, metadata?, prompt, modelSelection, workspaceRoot, cwd: existing.cwd ?: null, systemPrompt: value or null, timeoutSeconds: value or null, maxParallel: 1, enabled: existing.enabled, tags: list}` (no `mode`: edits keep it) → `{schedule}` |
| Enable / disable | `schedule.enable` / `schedule.disable {scheduleId, allWorkspaces: true}` → `{schedule}` |
| Run now | `schedule.trigger {scheduleId, wait: false, allWorkspaces: true}` → `{execution: ScheduleExecutionRecord?}` |
| Delete | `schedule.delete {scheduleId, allWorkspaces: true}` → `{deleted}` |
| Events | `schedule.execution_completed`, `schedule.execution_failed` (the only schedule events the Hub publishes, `hub-server-transport.ts` `eventPublisher`) → silent refresh, debounced 500 ms; also refresh on resume and every 30 s while any execution is active |
| Provider / model lists | `ProviderCatalogRepository` (SP §0.7): enabled providers and their models; fallback `providers.json` configured ids + `FALLBACK_PROVIDER_MODELS` (SP §0.4); the last selection from `ModelSelectionStore` (SP §0.1, desktop `readModelSelectionStorageFromWindow`) pre-selects provider/model exactly as the desktop's `openCreateDialog` does |

Timing encoding (`ScheduleFormat.kt`, ported):

- Once: `cronPattern = "0"` (`ONE_TIME_SCHEDULE_CRON_PATTERN`), `metadata =
  {...existing.metadata, "__hubScheduleRunAt": runAtEpochMs}`, `timezone` omitted on
  create / `null` on update to once.
- Daily: `cronPattern = "<m> <h> * * *"`; Weekly: `"<m> <h> * * MON,WED"` (all seven
  days collapse to `*`, `buildCronPattern`); hour clamped 0–23 (default 9), minute 0–59
  (default 0).
- Editing a recurring schedule keeps an advanced cron expression untouched when the timing
  controls were not changed (`preserveRoutineCron`).
- `timezone = routineScheduleTimezone(type, existing)`: once → none; new recurring →
  `ZoneId.systemDefault().id`; edit → the existing value (timezone-less schedules stay so).
- Switching an edited schedule from once to recurring removes `"__hubScheduleRunAt"` from
  the metadata it sends.

Run-now follow-up: if the reply has no `execution` → "Run not started" snackbar and a
refresh. Otherwise snackbar "Run started…"; `executionId = execution.executionId`; poll
`schedule.list_executions {scheduleId, limit: 5, allWorkspaces: true}` every 1 s for up to
15 s until the execution with that id has a `sessionId`, then navigate to
`chat/{sessionId}` (only that execution, never "the newest one").

ViewModel:

```kotlin
data class SchedulesUiState(
    val loading: Boolean, val refreshing: Boolean, val error: String?,
    val schedules: List<ScheduleCard>,           // sorted by name
    val templates: List<RoutineTemplate>,        // minus names already used
    val busy: Set<String>, val triggering: Set<String>,
    val keepAliveNotice: Boolean, val reducedScopeWorkspace: String?,
    val selectedId: String?,                     // expanded detail pane; SavedStateHandle
    val kernel: KernelGate,
)
data class ScheduleCard(val record: ScheduleRecord, val trigger: String, val model: String,
                        val nextRun: String, val lastResult: String, val lastExecution: ScheduleExecutionRecord?,
                        val active: ScheduleExecutionRecord?)
data class ScheduleFormState(val name: String, val type: ScheduleType /* Daily | Weekly | Once */,
                             val date: LocalDate, val hour: Int, val minute: Int, val days: Set<DayOfWeek>,
                             val prompt: String, val provider: String, val model: String,
                             val workspaceRoot: String, val systemPrompt: String, val timeoutSeconds: String,
                             val tags: String, val enabled: Boolean, val editing: ScheduleRecord?)
```

`ScheduleFormState` is `@Parcelize` in `SavedStateHandle` (no secrets in it).

### 4.9 Edge cases from the recovery matrix

- **Termux killed / Hub down at fire time:** runs do not happen (FM "they need Termux
  alive at fire time"). The `KeepAliveNotice` warns in advance; after the kernel comes
  back the list refresh shows the missed `nextRunAt` already advanced by the Hub (the app
  never back-fills).
- **Hub restart:** schedules persist in the daemon's store; the list re-reads on `Ready`.
- **`schedule_command_failed`** with "schedule cwd is outside the client workspace scope":
  shown verbatim; the workspace field is marked as the error field.
- **Trigger while the schedule is already running and `maxParallel = 1`:** the Hub returns
  no execution → "Run not started" snackbar (desktop wording covers it).
- **Workspace does not exist:** the Hub accepts the schedule; the run fails at fire time
  and the run row shows the error. The editor validates with RUN_COMMAND `test -d <path>`
  before saving and shows "That workspace folder doesn't exist in Termux." *(Android)* as
  a field error (save still blocked only for that reason).
- **Clock change / time zone travel:** recurring schedules keep their stored `timezone`;
  the trigger badge always prints it so the user sees which zone applies.

### 4.10 Accessibility

- Card: one focus target reading "<name>, enabled|disabled, <trigger>, next run <time>,
  last result <result>"; switch, Run now, Edit, overflow are separate targets.
- The enabled dot has no separate semantics (the state is in the card label); disabled
  schedules also show the switch off, so state never relies on the dot colour.
- Date and time fields are read-only text fields with `Role.Button` and CD "Date, <value>,
  double-tap to change" *(Android)*.
- Day chips: "Monday, selected" (full day names in CDs via `DayOfWeek.getDisplayName(FULL)`).
- The raw JSON block is LTR and selectable; TalkBack reads it as one text block.

### 4.11 Acceptance checklist (S25U)

- [ ] Drawer → "Schedules" with no schedules: the empty text and four "Suggested" cards.
- [ ] Tap "Summarize changes daily": C6 opens with name "Daily change digest", Daily,
      5:00 PM, the full digest prompt; Workspace prefilled; "Create Schedule" creates it;
      the card shows "Daily · 5:00 PM · <device zone>" and the template disappears from
      Suggested.
- [ ] New Schedule → Once with today and a past time: the time clamps to now + 1 min; set
      a time 2 minutes ahead; it runs; the card's "Last result" updates without a manual
      refresh (event) and the run appears in C7 with a working "Open session".
- [ ] Weekly with no days → "Select at least one weekday."
- [ ] Edit a schedule created by `cline schedule` with a custom cron (e.g. `*/15 * * * *`):
      the note shows the cron; changing only the prompt keeps the cron.
- [ ] Toggle the switch off: `cline schedule list` in Termux shows it disabled.
- [ ] Run now: snackbar "Run started…", then the app opens the new session within 15 s.
- [ ] Delete: "Delete Routine" dialog; the schedule is gone in Termux.
- [ ] Turn off "Keep Termux awake" in the Kernel status sheet: the keep-alive notice appears; "Keep it running" opens the One UI checklist.
- [ ] Hub down: banner; switches and Run now disabled; Start kernel restores.
- [ ] Tab S7+ landscape: list pane of cards with inline icon actions and a "Select a
      schedule" placeholder; tapping a card shows C7 in the detail pane with every
      desktop card line; the editor is a two-column 560dp dialog whose focused field
      stays above the keyboard.
- [ ] C7 on the phone shows ID (copyable), the full prompt, Last session (opens the
      chat) and Tags; a run row without a session is not tappable.
- [ ] New Schedule with no template: Once, tomorrow, 9:00 AM, the default prompt;
      switching to Weekly shows Mon–Fri selected.
- [ ] With ≥ 3 schedules, the last card's overflow and Edit buttons are tappable with the
      FAB visible.
- [ ] Drawer → "Schedules" shows `menu` and back returns to Chats; Settings →
      "Schedules" shows the back arrow and back returns to Settings.
- [ ] TalkBack reads each card's state and next run; day chips read full day names.

---

## 5. C8 — Account

### 5.1 Purpose

Show who is signed in to Cline in the kernel, the credit balance, the personal account and
organisations (and switch between them), recent usage and payments; sign in and sign out.
The app never sees the Cline tokens: every call runs in Termux through the settings helper.

### 5.2 Desktop equivalent

`AccountView` (`account-view.tsx`), `cline-account-state.ts`
(`ACCOUNT_NOT_AUTHENTICATED`), `provider-connection.ts` (`OAUTH_LOGIN_TIMEOUT_MS`), all
`cline_account` operations in `apps/cline-hub/src/server/desktop-commands.ts`, and the
account types in `sdk/packages/core/src/account/types.ts`.

Copied: the page title "Account", the three tabs and their lazy loading, the order of the
overview fetches (`fetchMe` first; nothing else is fired when signed out), the signed-out
card, the profile card, the balance card with its two titles and the "Personal account"
line, the "Organizations" list with "Personal" first and the switch flow (one switch at a
time), the usage and billing tables with their empty strings, "See More", the error block
with "Retry", the signed-out detection rules (`isAccountAuthError`: only "no cline account
auth token found", "requires re-authentication", "failed with status 401"), the
"Sign Out" / "Signing Out" action and its `save_provider_settings` payload, every external
URL.

Translated: sign-in runs the SP S7 Termux flow (`ClineSignInCoordinator.start("cline",
Origin.Account)`), not `run_provider_oauth_login`, because the dashboard's background
`xdg-open` opens nothing on Android (OQ 5); the device code is shown by Termux, so the
desktop line "Confirm this code in your browser:" is not rendered here; the tables become
list rows on compact; external links open in a Custom Tab (DS external-link rule).

Added *(Android)*: a sign-out confirm dialog (destructive and not undoable from the
phone); the helper-unavailable page (§5.4); the older-helper message.

### 5.3 Layout

Compact, signed in, Overview:

```
┌────────────────────────────────────────────┐
│ ←  Account                          ⋮      │  overflow: "Sign Out" (error colour)
│ ┌──────────┬───────────┬──────────┐        │  PrimaryTabRow (fixed, 3 tabs)
│ │ Overview │   Usage   │ Billing  │        │
│ └━━━━━━━━━━┴───────────┴──────────┘        │
│ ┌────────────────────────────────────────┐ │
│ │ (S)  Sam Lee                       ↗   │ │  OutlinedCard; 48dp initial avatar
│ │      sam@example.com                   │ │  (primaryContainer); ↗ = "Open dashboard"
│ │      Member since Mar 4, 2025          │ │
│ └────────────────────────────────────────┘ │
│ ┌────────────────────────────────────────┐ │
│ │ Credits Balance              [Credit ↗]│ │  OutlinedCard; FilledTonalButton
│ │ $12.40                                 │ │  displaySmall, ClineMono numerals
│ │ Personal account: 3.10 credits         │ │  only when an org is active
│ └────────────────────────────────────────┘ │
│ Organizations                   [Create ↗] │  titleSmall + TextButton
│ ┌────────────────────────────────────────┐ │
│ │ (👤) Personal                 [Active] │ │  ListItem 72dp, radio semantics
│ │      sam@example.com                   │ │
│ ├────────────────────────────────────────┤ │
│ │ (A)  Acme                       Switch │ │  trailing "Switch" labelMedium
│ │      Admin, Member                     │ │  onSurfaceVariant; roles capitalised
│ └────────────────────────────────────────┘ │
│            [ ⎋ Sign Out ]                  │  OutlinedButton, end of Overview
└────────────────────────────────────────────┘
```

Compact, Usage (Billing is the same shape with Date / Amount / Credits):

```
│ Recent API usage and token consumption     │  bodyMedium onSurfaceVariant
│ across all providers.                      │
│ ┌────────────────────────────────────────┐ │
│ │ claude-sonnet-5            12,345 tok  │ │  ListItem: model (bodyLarge),
│ │ Anthropic · Oct 2, 2026 4:03 PM  0.48  │ │  provider + date/time, trailing
│ ├────────────────────────────────────────┤ │  tokens (ClineMono.small) and credits
│ │ …                                      │ │
│ └────────────────────────────────────────┘ │
│              [ See More ↗ ]                │  OutlinedButton
```

Medium / expanded: content column max 720dp (desktop `max-w-3xl`); the usage table is the
desktop's four columns ("Model" | "Tokens" | "Credits" | "Time", right-aligned numbers,
header row `labelMedium onSurfaceVariant`, `outlineVariant` dividers) inside an
`OutlinedCard`; billing likewise ("Date" | "Amount" | "Credits"); the profile card and the
balance card sit side by side on expanded (≥ 840dp).

Signed out (any width):

```
│ ┌────────────────────────────────────────┐ │
│ │ [logo]                                 │ │  OutlinedCard, centred, max 480dp
│ │ Sign in to Cline                       │ │  titleMedium
│ │ Connect your Cline account to review   │ │  bodyMedium onSurfaceVariant
│ │ credits, usage, billing, and           │ │
│ │ organization details.                  │ │
│ │ [ Sign in ]   [ Create account ↗ ]     │ │  Button + OutlinedButton
│ └────────────────────────────────────────┘ │
```

While S7 waits, the card swaps to S7's waiting content ("Waiting for browser...",
"Finish signing in in Termux and your browser, then come back here.", "Open Termux",
"Cancel"; SP S7.2). While not signed in (or while the first `fetchMe` is unresolved) the
tab row is **hidden** and the page is the single signed-out card: M3 tabs have no disabled
state, and two dimmed tabs that TalkBack announces as unavailable add nothing to a one-state
page (the desktop's `opacity-45` tabs are not copied). Signed in, all three tabs are
enabled.

Both cards on the Overview are `OutlinedCard` (one card style per screen group, DS §3.2:
no shadows), the profile card and the balance card alike. "Sign Out" is the last element of
the Overview tab (`OutlinedButton` with `logout` icon, content `error`) and also an
overflow-menu item in the app bar (`more_vert`, CD "More options" *(Android)*), so it is
reachable from every tab without being a top-bar text action.

### 5.4 States

| State | Behaviour |
| --- | --- |
| First open / loading | Helper `Starting`: SP §0.3 "Starting settings helper..." line; then a `LinearProgressIndicator` + 2 skeleton cards (desktop `renderLoading`) |
| Signed out | the signed-out card only (no tab row, no overflow menu) |
| Populated | as §5.3 |
| Switching account | the target row shows a 20dp spinner; every other non-active row is disabled at 60 % alpha; then the overview reloads |
| Usage / Billing loading | progress + skeleton in that tab only (lazy on first selection, desktop) |
| Usage / Billing empty | "No usage transactions yet." / "No payment transactions yet." |
| Error (any tab) | centred block: 32dp `error` icon in `error` colour, the message (`bodyMedium onSurfaceVariant`, max 448dp), `OutlinedButton` "Retry" with `refresh` icon (desktop `renderError`) |
| Token expired mid-session | any `desktopCommandResult {ok:false, error}` whose message satisfies `isAccountAuthError` ("no cline account auth token found", "requires re-authentication", "failed with status 401", case-insensitive) resets the data and shows the signed-out card (desktop). The dashboard helper never returns the sidecar's `{signedIn:false, code:"ACCOUNT_NOT_AUTHENTICATED"}` object: when signed out it throws `Error("No Cline account auth token found")` (`apps/cline-hub/src/server/desktop-commands.ts`); FM's `ACCOUNT_NOT_AUTHENTICATED` row describes the desktop sidecar channel, not this one |
| Signing out | the Overview "Sign Out" button (or the menu item) shows a 16dp spinner + "Signing Out"; the whole page is disabled |
| Helper unavailable (`Unavailable`, or `helper.unsupported`) | SP §0.3 unavailable card ("Settings helper unavailable", "Retry", "Details") at the top, then the reduced page: signed-in state from `providers.json` (SP S1 Account row rule): signed in → `ListItem` "Signed in to Cline" *(Android)* with supporting "Account details need the settings helper in Termux." *(Android)* and `OutlinedButton` "Open dashboard" (Custom Tab, `DASHBOARD_URL`); signed out → the signed-out card (S7 works without the helper). Sign Out is hidden (SP S7: not available in fallback mode) |
| Older helper | the reply "unsupported desktop command: cline_account" becomes "The settings helper in Termux is running an older build that does not support account commands. Update the Cline kernel, then try again." *(Android; desktop wording names the sidecar)* |
| Kernel blocked | `KernelBlockedCard` (SP §0.5); the helper and S7 both need RUN_COMMAND |
| Hub down | not relevant (the helper is its own process); C8 works with the Hub down |
| Offline (device has no network) | the account API fails inside the helper; the message is shown verbatim in the error block with "Retry" |

### 5.5 Copy

| Element | String |
| --- | --- |
| Title | "Account" |
| Tabs | "Overview", "Usage", "Billing" |
| Sign-out action | Overview button and overflow item "Sign Out" / "Signing Out"; overflow CD "More options" *(Android)* |
| Sign-out confirm *(Android)* | title "Sign out of Cline?" (SP S7); text "Cline removes the saved credentials for Cline from Termux. Sessions using it stop working until you connect it again." (SP S5.3 pattern); buttons "Cancel", "Sign out" (error) |
| Signed out | "Sign in to Cline"; "Connect your Cline account to review credits, usage, billing, and organization details."; "Sign in" / "Signing in"; "Create account" |
| Profile | name = `displayName` or `email`; `email`; "Member since <MMM d, yyyy>"; avatar initial = first char of `displayName`, else of `email`, else "?"; icon button CD/tooltip "Open dashboard" |
| Balance | title "<org name> Balance" when an organisation is active, else "Credits Balance"; amount "$" + `formatCreditBalance(balance)`; "Personal account: <n> credits"; button "Credit" |
| Organizations | header "Organizations"; button "Create"; personal row "Personal" / `email` or "Personal account"; org row `name` / roles with each word capitalised (`roles.joinToString(", ") { it.replaceFirstChar { c -> c.titlecase(Locale.US) } }`, desktop CSS `capitalize`); active badge "Active"; inactive trailing text "Switch" (`labelMedium onSurfaceVariant`, desktop) |
| Usage | "Recent API usage and token consumption across all providers." or "Recent API usage and token consumption for <org name>."; columns "Model", "Tokens", "Credits", "Time"; empty "No usage transactions yet."; "See More" |
| Billing | "Payment history and credit purchases."; columns "Date", "Amount", "Credits"; amount "$" + `amountCents / 100` with 2 decimals; credits "+" + `formatCreditBalance(credits)`; empty "No payment transactions yet." |
| Error | message + "Retry" |
| Snackbars *(Android)* | after sign-out: "Signed out of Cline."; after a switch: "Switched to <name>." |

Number formats: `formatCreditBalance(v, 2)` = `v / 1_000_000` with exactly two decimals,
grouped exactly like the desktop (`Intl.NumberFormat("en-US")`; credits and dollar amounts
are technical units and are not localised, DS §8.2 item 4); token counts use
`NumberFormat.getIntegerInstance()` (DS §8.2 item 5). Dates: desktop
`formatDate` (`MMM d, yyyy`) and `formatTime` (`h:mm a`), via the §0.7 helpers.

URLs (constants in `account/AccountUrls.kt`, verbatim): `DASHBOARD_URL =
https://app.cline.bot/dashboard`, `USAGE_DASHBOARD_URL = https://app.cline.bot/dashboard/usage`,
`USER_CREDITS_URL = https://app.cline.bot/dashboard/account?tab=credits&redirect=true`,
`ORGANIZATION_CREDITS_URL = https://app.cline.bot/dashboard/organization?tab=credits&redirect=true`,
`CREATE_ORGANIZATION_URL = https://app.cline.bot/onboarding?step=1`,
`CREATE_ACCOUNT_URL = https://app.cline.bot`.

### 5.6 Interactions and gestures

- Tabs (signed in only): tap or horizontal swipe (`HorizontalPager` bound to the tab row).
- Pull to refresh reloads the current tab (desktop has no refresh; added *(Android)*).
- Organisation row tap → `switchAccount`; the active row and all rows during a switch are
  not clickable (desktop `disabled`).
- "Credit" opens the organisation or user credits URL (desktop rule). Every URL opens with
  `openExternal(url)` (§0.8).
- Sign Out → confirm → call (§5.8) → reset → Overview signed out → snackbar.
- Sign in → SP S7. On success S7 returns here and C8 reloads the overview (desktop
  `loadOverview()` then `setActiveTab("overview")`).

### 5.7 Navigation and back

- Route `account?tab=…`; entered from Settings (SP S1 "Account" row), the drawer's account
  footer and the chat's "Sign in to Cline" credential action (SP S7.1). Back pops.
- `tab` is saved in `SavedStateHandle`; a deep link with `tab=usage` while signed out
  lands on Overview.

### 5.8 Data bindings

All through `SettingsHelper.acquire()` (SP §0.3) and
`helper.desktopCommand("cline_account", payload)` with
`payload = {action: "clineAccount", operation, …}`. Timeouts 30 s; released on leave
(30 s grace).

| Need | Operation | Reply |
| --- | --- | --- |
| Profile | `fetchMe` | `ClineAccountUser {id, email, displayName, photoUrl, createdAt, updatedAt, organizations}`; signed out arrives as `desktopCommandResult {ok:false, error:"No Cline account auth token found"}`, detected only by `isAccountAuthError(error)` (§5.4) |
| Balance | `fetchBalance` (parallel with the next) | `{balance, userId}` |
| Organisations | `fetchUserOrganizations` | `ClineAccountOrganization[] {organizationId, name, roles, active, memberId}` |
| Org balance | `fetchOrganizationBalance {organizationId}` (only when one is active) | `{balance, organizationId}` |
| Usage | `fetchOrganizationUsageTransactions {organizationId, memberId?}` when an org is active, else `fetchUsageTransactions` | `[{id, aiModelName, aiInferenceProviderName, totalTokens, creditsUsed, createdAt, …}]` (org rows add `memberDisplayName`, `memberEmail`, not shown, desktop) |
| Billing | `fetchPaymentTransactions` | `[{paidAt, amountCents, credits, creatorId}]` |
| Switch | `switchAccount {organizationId: String?}` (null = personal) | — then reload overview |
| Sign out | `desktopCommand("save_provider_settings", {provider:"cline", api_key:"", settings:{auth:{accessToken:"", refreshToken:"", accountId:""}}})` (desktop Account payload) | then `ProviderCatalogRepository.invalidate()`, clear `account.displayName`/`account.scope` |
| Signed-in check without helper | RUN_COMMAND `cat providers.json` → `ConfiguredProviders` (KS §0.6 rule 2) | `hasOAuth` for `cline` |

`photoUrl` is not loaded (desktop shows the initial only; the app has no image loader).
On every successful `fetchMe` the ViewModel writes the non-secret summary
`account.displayName` (= `displayName` or `email`) and `account.scope` (= active org name
or "Personal") for SP S1's Account row. A usage generation counter discards a reply that
arrives after the active organisation changed (desktop `usageGenerationRef`).

```kotlin
data class AccountUiState(
    val tab: AccountTab,                        // Overview | Usage | Billing
    val helper: HelperState,                    // SP §0.3
    val signedIn: SignedIn,                     // Unknown | Yes | No
    val overview: Loadable<AccountOverview>,    // Loading | Ready | Error(message)
    val usage: Loadable<List<UsageRow>>, val billing: Loadable<List<PaymentRow>>,
    val switchingTo: String?,                   // org id, "" = personal, null = none
    val signingOut: Boolean, val signIn: SignInState,   // SP S7.4
    val kernel: KernelGate,
)
data class AccountOverview(val name: String, val email: String, val initial: Char, val memberSince: String,
                           val balanceTitle: String, val balance: String?, val personalLine: String?,
                           val accounts: List<AccountRow>)
data class AccountRow(val id: String?, val name: String, val subtitle: String, val active: Boolean)
```

### 5.9 Edge cases from the recovery matrix

- **Termux killed while signing in:** SP S7 restores `signin.pending` after process death.
- **Helper dies mid-tab:** the reply fails with `hub_connection_closed`-like transport
  error → SP §0.3 re-acquire once, then the error block.
- **401 from the account API:** treated as signed out (desktop rule); other errors stay
  errors with Retry (never turned into a sign-in card).
- **Signed out from another client (CLI `cline auth` elsewhere):** the next load shows the
  signed-out card; SP S1's cached summary is cleared on that result.

### 5.10 Accessibility

- Tabs are `Tab`s with `Role.Tab` (only shown when signed in, so none is disabled).
- The balance amount has CD "Credits balance, 12 dollars 40" (`stateDescription` built
  with `MeasureFormat` currency) so TalkBack does not read "$12.40" digit by digit.
- Organisation rows: `Modifier.selectable(selected = active, role = Role.RadioButton)` in a
  `selectableGroup`; switching announces "Switching to <name>" (polite).
- Usage rows read "<model>, <provider>, <tokens> tokens, <credits> credits, <date time>".
- External-link buttons carry the `open_in_new` icon and CD suffix ", opens in browser"
  *(Android)*.

### 5.11 Acceptance checklist (S25U)

- [ ] Signed out: Settings → Account shows only the sign-in card (no tab row); a deep
      link `account?tab=usage` lands on it too.
- [ ] "Sign in" brings Termux forward with the device-code URL; after finishing in the
      browser and returning, the overview shows the profile and balance.
- [ ] Balance matches app.cline.bot; "Credit" opens the credits page in a Custom Tab.
- [ ] Inactive organisation rows end with "Switch" and roles read "Admin, Member".
- [ ] With an organisation: tap it → spinner on that row → it becomes "Active", the
      balance title becomes "<org> Balance" and Usage shows "…for <org>.".
- [ ] Usage and Billing load only when first selected; empty accounts show the empty
      strings.
- [ ] Sign Out → confirm → signed-out card; `cline auth` status in Termux shows no Cline
      token; the Settings Account row reads "Sign in to Cline".
- [ ] Kill the helper (`pkill -f "cline dashboard"`) on the page: Retry recovers.
- [ ] Tab S7+ landscape: four-column usage table; profile and balance side by side.
- [ ] TalkBack reads the balance as currency and the org rows as radio buttons.

---

## 6. C9 — Agents roster sheet

### 6.1 Purpose

From a chat that started subagents or team tasks, list each child agent by the task it was
given, its state and its latest step, and open that agent's own session.

### 6.2 Desktop equivalent

`AgentActivityStatus` + `AgentRoster` + `AgentRosterRow` (`components/agent-header.tsx`),
`describeAgentActivity`, `agentEntryState`, `summarizeAgentEntries`, `mergeAgentActivity`
(`lib/session-agents.ts`), and the sidecar's `listSessionAgents`
(`sidecar/session-data/agents.ts`). The chip that opens the sheet is the chat spec's
(`home-chat.md` header table, "Agents chip"); its CD and tally rules are not repeated here.

Copied: header "Agents" with the `describeAgentActivity` line under it, the loading,
waiting, none-recorded and stale-refresh strings, row anatomy (state icon, task clamped
to 2 lines, "team <name>" overline for team tasks, last action or its fallbacks, chevron),
the row tooltip "Open this agent's session", the 5 state icons/colours, the 160-char
last-action truncation and the "Running <tool>" rule, refresh-keeps-last-good-list.
Translated: popover → `ModalBottomSheet`; `max-h-80` scroller → sheet height (half
expanded, full on drag).

### 6.3 Layout

```
┌────────────────────────────────────────────┐
│                    ───                     │  DragHandle
│ Agents                                     │  titleMedium
│ 3 agents: 1 running, 2 completed           │  bodySmall onSurfaceVariant
├────────────────────────────────────────────┤
│ ◌  Audit the auth module for injection     │  ListItem; spinner 16dp
│    bugs and report file:line findings      │  task: bodyMedium, 2 lines
│    Running read_files                      │  bodySmall onSurfaceVariant, 1 line
│                                         ›  │
├────────────────────────────────────────────┤
│ ✓  TEAM reviewers                          │  labelSmall overline (team task)
│    Review the migration for data loss      │
│    The migration is safe; one index is…   │
│                                         ›  │
├────────────────────────────────────────────┤
│ ⏲  Untitled task                           │
│    No activity recorded (unresolved)       │  italic when no last action
│                                         ›  │
├────────────────────────────────────────────┤
│ Could not refresh — showing the last known │  stale footer, bodySmall
│ agents. <error>                            │
└────────────────────────────────────────────┘
```

Medium / expanded: the same sheet, max width 640dp, centred (DS §5.5); on expanded with a
supporting pane (≥ 1200dp) it is still a sheet (the roster is transient).

### 6.4 States

| State | Content |
| --- | --- |
| Loading, no rows yet | 16dp spinner + "Loading agents..." |
| No rows, tally has running agents | "Waiting for the first agent to report in..." (+ error line if any) |
| No rows, nothing running | "No agent details were recorded for this session." (+ error line) |
| Populated | rows sorted by `startedAt` ascending (sidecar order) |
| Refresh failed with rows | rows stay; footer "Could not refresh — showing the last known agents. <error>" |
| Kernel down | rows from the last load stay; footer uses the KS §F.2 banner text of the current variant as `<error>` |
| Child has no transcript yet (`hasMessages == false`) | row enabled; opening shows the chat's own empty/loading state |

State icon map (DS §7 symbols, colours from `ClineColors`): running → 16dp
`CircularProgressIndicator` `onSurface`; completed → `check` `success.color`; failed →
`error` `error`; cancelled and unresolved → `schedule` `onSurfaceVariant`.

### 6.5 Copy

"Agents"; `describeAgentActivity`: "<n> agent(s)" or "<n> agents: <k> running, <k> completed,
<k> failed, <k> cancelled, <k> with no recorded outcome" (only non-zero parts, in that
order; "agent" when total is 1); "Loading agents..."; "Waiting for the first agent to report
in..."; "No agent details were recorded for this session."; "Could not refresh — showing the
last known agents."; task fallback "Untitled task"; team overline "team <name>" or "team"
(the string is lowercase as the desktop; rendered `labelSmall` with `letterSpacing = 0.sp`
and `text.uppercase(Locale.getDefault())`, DS §2.1: no tracking; TalkBack gets the
lowercase string);
last-action fallbacks "Starting up..." (running) / "No activity recorded (<state>)"; row
action label "Open this agent's session".

### 6.6 Interactions and navigation

- Opened from the chat header's agents chip; `ModalBottomSheet` with
  `skipPartiallyExpanded = false`.
- Row tap → dismiss the sheet, then navigate `chat/{childSessionId}` (desktop closes the
  popover first so it does not linger). The child chat shows the chat spec's
  "Main Agent Session" back chip; system back returns to the parent chat, not to the sheet.
- Back / scrim tap / drag down → dismiss (predictive back on the sheet, M3 built-in).
- While open the roster refreshes on `team.progress` and on `run.completed|failed|aborted`
  of the parent session (debounced 750 ms), and every 5 s while any row is running.

### 6.7 Data bindings

| Need | Call |
| --- | --- |
| Children | `session.list {limit: 200, rootOnly: false}` → `{sessions: HubSessionRecord[]}`; keep records whose `metadata.parentSessionId == parentId` and that have `metadata.agentId` (sidecar `listChildren` + `agentId` filter). `session.list` honours `limit`, so when the first reply returns exactly 200 records and fewer children than the tally expects, the sheet re-requests once with `limit: 1000` (OQ C-6) |
| Kind | `sessionId.contains("__teamtask__")` → `teamtask`, else `subagent` |
| Status | `HubSessionRecord.status` → `agentEntryState` (running/pending → running; completed; failed; cancelled; idle and anything else → unresolved) |
| Task | `metadata.prompt` |
| Team name | `HubSessionRecord.runtimeSession?.team?.teamId` (`toHubSessionRecord` maps the store's `teamName` column there, `sdk/packages/core/src/hub/server/hub-session-records.ts`; the sidecar's `record.teamName` is the same column). `metadata` never carries it (`cloneSessionMetadata` adds only parentSessionId/parentAgentId/agentId/conversationId/messagesPath/prompt/provider/model/source/pid). Blank or absent → overline "team" (team tasks only) |
| Last action | `session.messages {sessionId: child}` → derive with the ported `deriveLastAction` (latest assistant message, latest `tool_use` block → "Running <name>", else text collapsed and cut at 160 chars + "..."). The Hub resolves child transcripts: `handleSessionMessages` → `readSessionMessages`, which reads the row's `messagesPath` and falls back to the manifest (`session-handlers.ts`, `local-runtime-host.ts`). Fetched for running rows on every refresh and once for finished rows, cached per `(sessionId, updatedAt)`; a failed call leaves `lastAction` null (fallback strings of §6.5) |
| Tally | `mergeAgentActivity(rosterSummary, toolDerivedActivity)` — the chat spec computes the tool-derived tally; the sheet only provides the roster summary |

```kotlin
data class AgentsRosterUiState(
    val headerLine: String,                     // describeAgentActivity(merged)
    val loading: Boolean, val error: String?,
    val agents: List<AgentRowUi>,
    val runningInTally: Int,                    // drives the waiting vs none-recorded text
)
data class AgentRowUi(val sessionId: String, val kind: AgentKind, val state: AgentRunState,
                      val task: String?, val lastAction: String?,
                      val teamName: String?)   // runtimeSession?.team?.teamId?.takeIf { it.isNotBlank() }
```

### 6.8 Edge cases

- **Hub restarted mid-run:** children that were running come back `idle`/`failed` →
  "unresolved"/"failed"; the tally from tool messages may still say running until the
  parent's events settle (desktop `mergeAgentActivity` keeps the larger evidence).
- **More than 200 sessions in total:** handled by the one-time `limit: 1000` re-request
  (§6.7); beyond 1000 children are missed and the sheet shows what it found. OQ C-6 asks
  for a parent filter.
- **Child deleted:** opening it shows the chat spec's "session not found" state.

### 6.9 Accessibility

- Each row is one target: "<state>, <task>, <last action>, team <name>; double-tap to open
  this agent's session" (`onClickLabel = "Open this agent's session"`).
- The header line is a polite live region so a changing tally is announced while the sheet
  is open, at most once per 5 s.
- The spinner has no separate semantics (state is in the row label).

### 6.10 Acceptance checklist (S25U)

- [ ] Prompt "use two subagents to list files in two folders": the chip appears; the sheet
      shows two rows with spinners and "Running …" lines that update.
- [ ] When done, both show checks and the header says "2 agents: 2 completed".
- [ ] Tap a row: the child session opens with "Main Agent Session"; back returns to the
      parent chat.
- [ ] Stop the Hub while the sheet is open: rows stay with the stale footer.
- [ ] A `/team` run shows overlines "TEAM <NAME>" (the whole string uppercased, as the
      desktop's CSS `uppercase`) carrying the team's name, i.e. the value of
      `runtimeSession.team.teamId` in `session.list`; a team task without a team name shows
      "TEAM"; TalkBack reads "team <name>" in lowercase.
- [ ] TalkBack reads state, task and last action for each row.

---

## 7. C10 — About

### 7.1 Purpose

Show which app and kernel versions are installed, replay the latest "What's new", read the
release notes, report an issue, and reach diagnostics and licences.

### 7.2 Desktop equivalent

`AboutContent` (`about-view.tsx`), `app-channel.ts` (`productNameForVersion`,
`isBetaVersion`), `changelog.ts` (`parseChangelog`, `RECENT_RELEASE_COUNT = 5`,
`releaseUrl`, `CHANGELOG_URL_ON_GITHUB`, `ISSUES_URL`), and the Settings "Diagnostics" row
(SP S1, which opens C12).

Copied: version meta with the "Beta" badge rule, "Highlights" / "A short tour of the
biggest recent additions." / "Show what's new", "Report an issue" / "Found a bug or have a
request? Open an issue on GitHub." / "Open GitHub issues", "Release notes" with "Full
changelog", the release list (last 5, "v<version>", "Installed" badge, bullet notes),
"Loading release notes…", the not-available message.

Not copied: the desktop `UpdateRow` (Tauri auto-updater; the app is sideloaded and has no
updater, DS §9 no desktop-only chrome; OQ C-9). Added *(Android)*: the "Kernel" version
group (the kernel is a separate install), the unofficial notice under the header, and
"Open source licenses".

### 7.3 Layout (compact; medium/expanded = same list, max width 720dp)

```
┌────────────────────────────────────────────┐
│ ←  About                                   │
│                   [mark]                   │  ic_app_logo 56dp, primary
│                Cline Termux                │  headlineSmall
│               v0.1.0  [Beta]               │  ClineMono.small + Badge when -beta
│ ╭────────────────────────────────────────╮ │  unofficial notice (Android): Surface
│ │ ⓘ Cline Termux is an unofficial,       │ │  surfaceContainerHigh, shape large, 16dp
│ │   independent app. It is not …         │ │  padding; ms_info 20dp, bodyMedium
│ ╰────────────────────────────────────────╯ │  onSurfaceVariant
│ VERSIONS                                   │  PrefHeader (Android)
│ App                          0.1.0 (1)     │  ListItem, trailing ClineMono.small
│ Kernel release           3.0.68-termux.2   │
│ Cline CLI                         3.0.68   │
│ Cline Hub         core 3.0.68 · b1c2d3e4   │  long-press copies
│ Kernel status                          ›   │  → KS E sheet
│ ─────────────────────────────────────────  │
│ Highlights                                 │  ListItem 72dp
│ A short tour of the biggest recent         │
│ additions.               [Show what's new] │  OutlinedButton trailing (medium+);
│                                            │  on compact the row itself is the target
│ Report an issue                        ↗   │
│ Found a bug or have a request? Open an     │
│ issue on GitHub.                           │
│ Diagnostics                      [Export…] │  (SP S1 copy) → C12
│ Open source licenses                   ›   │  → C13
│ ─────────────────────────────────────────  │
│ Release notes              [Full changelog]│  titleMedium + TextButton
│ v0.1.0  [Installed]                        │  TextButton-styled link, titleSmall
│ • First Android release: chat with the     │  Markdown bullets (DS markdown renderer)
│   Cline kernel in Termux…                  │
│ v0.0.9                                     │
│ • …                                        │
└────────────────────────────────────────────┘
```

### 7.4 States

| State | Behaviour |
| --- | --- |
| Loading kernel versions | each kernel row shows "Checking…" (SP copy style) until its source answers |
| Kernel down | "Cline Hub" row: "Not running" *(Android)*; CLI and release rows still read through RUN_COMMAND |
| Termux missing / permission missing | kernel rows "Unavailable" *(Android)* with the `KernelBlockedCard` (SP §0.5) under the Versions header |
| Release notes loading | "Loading release notes…" |
| Release notes error | "Release notes aren't available in this build. The full changelog is on GitHub." (`bodySmall onSurfaceVariant`) |
| No What's new entry | the Highlights row is hidden (desktop) |

### 7.5 Copy

| Element | String |
| --- | --- |
| App bar | "About" |
| Header | product name: "Cline Termux" *(Android; desktop "Cline")*, or "Cline Termux Beta" when `versionName` contains "-beta" (desktop `BETA_PRODUCT_NAME` rule); meta "v<versionName>"; badge "Beta" |
| Notice *(Android, 2026-10-16)* | "Cline Termux is an unofficial, independent app. It is not affiliated with, endorsed by or sponsored by Cline Bot Inc. or the Termux project. Cline is a trademark of Cline Bot Inc., and other names belong to their owners; the app uses them only to name the software it works with. Please report problems with this app on its GitHub Issues page, not to Cline or Termux." (the README says the same) |
| Versions *(Android)* | header "Versions"; "App" = "<versionName> (<versionCode>)"; "Kernel release" = port release (`3.0.68-termux.2`); "Cline CLI" = `cline --version`; "Cline Hub" = "core <coreVersion> · <buildId first 8>"; "Kernel status"; values "Checking…", "Not running", "Unavailable"; snackbar "Copied <label>" (KS copy) |
| Highlights | "Highlights" / "A short tour of the biggest recent additions." / "Show what's new" |
| Report | "Report an issue" / "Found a bug or have a request? Open an issue on GitHub." / button "Open GitHub issues" (medium+) |
| Diagnostics | "Diagnostics" / "Export app info, recent logs, and the metadata of sessions you choose as a file you can attach when reporting a problem." / "Export…" (SP S1 row, same copy) |
| Licenses *(Android)* | "Open source licenses" / "Libraries and fonts used by this app" |
| Release notes | "Release notes"; "Full changelog"; "v<version>"; "Installed"; link CD "Open this release on GitHub"; "Loading release notes…"; error above |

### 7.6 Interactions and navigation

- Route `about`, from Settings (SP S1 "About" row) and the drawer footer. Back pops.
- "Show what's new" → C11 without "See all changes" (desktop: omitted when opened from
  About).
- "Report an issue" → `openExternal(ISSUES_URL)` (§0.8; OQ C-8 decides the repository).
- "Kernel status" → KS E sheet. "Export…" → C12. "Open source licenses" → C13.
- Release version link → `openExternal(releaseUrl(version))`; "Full changelog" →
  `openExternal(CHANGELOG_URL_ON_GITHUB)` (app repository constants, OQ C-8).
- Long-press a version row → copy value (`ClipData.newPlainText(label, value)`).

### 7.7 Data bindings

| Row | Source |
| --- | --- |
| App | `BuildConfig.VERSION_NAME`, `VERSION_CODE` |
| Kernel release | RUN_COMMAND `readlink /data/data/com.termux/files/usr/opt/cline-termux/current` → basename (the port's install layout, `X.Y.Z-termux.N`); the same value KS uses for `MIN_PORT_RELEASE` checks, read from KS's cached probe when present |
| Cline CLI | RUN_COMMAND `$PREFIX/bin/cline --version` → first line trimmed |
| Cline Hub | `GET http://127.0.0.1:25463/health` (`coreVersion`, `buildId`) from `KernelCoordinator`'s last probe (no new request when < 60 s old) |
| Release notes | `assets/CHANGELOG.md` (the app's own changelog, bundled at build time) → ported `parseChangelog` (`about/Changelog.kt`) (`## <version>` headings, `- ` bullets, continuation lines) → first 5 releases; "Installed" when `version == VERSION_NAME` |
| Highlights | `WhatsNewRepository.latest()` (§8) |

```kotlin
data class AboutUiState(
    val productName: String, val version: String, val beta: Boolean,
    val kernelRelease: VersionValue, val cliVersion: VersionValue, val hub: VersionValue,  // Checking | Value(s) | NotRunning | Unavailable
    val whatsNew: WhatsNewRelease?, val releases: Loadable<List<ChangelogRelease>>,
    val kernel: KernelGate,
)
```

### 7.8 Accessibility

- Version rows: "Kernel release, 3.0.68-termux.2" with `onLongClickLabel = "Copy"`; values
  LTR.
- Release headings are `heading()`; notes are list items to TalkBack (markdown renderer).

### 7.9 Acceptance checklist (S25U)

- [ ] About shows App 0.1.0 (1), Kernel release equal to `readlink …/current` in Termux,
      CLI equal to `cline --version`, Hub core/build equal to `curl 127.0.0.1:25463/health`.
- [ ] Hub stopped: Cline Hub row "Not running", others still filled.
- [ ] "Show what's new" opens C11 without "See all changes".
- [ ] "Open GitHub issues" opens a Custom Tab on the configured repository.
- [ ] Release notes list the bundled changelog with "Installed" on the current version.

---

## 8. C11 — What's new dialog

### 8.1 Purpose and desktop equivalent

A one-time catch-up after an app update that carries a new highlights entry, replayable
from About. Desktop: `WhatsNewDialog` (`components/whats-new-dialog.tsx`), `whats-new.ts`
(`latestWhatsNew`, `pendingWhatsNew`, `markWhatsNewSeen`, `markCurrentWhatsNewSeen`),
`whats-new-content.ts` (the entry shape `{id, title, highlights[{title, description,
icon: LucideIcon}]}`), and the shell wiring in `app/page.tsx` (shown only to onboarded
users; finishing onboarding marks the current entry seen; closing marks it seen; "See all
changes" marks it seen and opens About). About replays the latest entry **without**
marking it seen (`about-view.tsx` passes only `onOpenChange`; `whats-new-content.ts`:
"replays the latest entry without marking it seen").

Android entry shape (`about/WhatsNewContent.kt`):

```kotlin
data class WhatsNewHighlight(val title: String, val description: String, val icon: ImageVector)  // DS §7 Material Symbol
data class WhatsNewRelease(val id: String, val title: String, val highlights: List<WhatsNewHighlight>)
```

Copied: the per-highlight icon above each highlight title, the two-column highlights grid,
the hero with overline and title, the footer with "See all changes" (shell only) and
"Get Started", the seen rules.

### 8.2 Layout

```
┌────────────────────────────────────────┐
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │  hero (brand: fixed violet; dynamic: primaryContainer)
│ ▓ WHAT'S NEW IN CLINE                ▓ │  labelMedium SemiBold, uppercase, letterSpacing 0
│ ▓ Cline in your pocket               ▓ │  headlineSmall
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
│ 💬                 ✅                  │  20dp icon, primary
│ Native chat        Approvals on the go │  titleSmall
│ Talk to the Cline  Approve tools and   │  bodySmall onSurfaceVariant
│ kernel in Termux…  answer Cline's…     │
│ ⏱                  🧩                  │
│ Schedules and      Customize and       │
│ agents             marketplace         │
│ …                  …                   │
│ ────────────────────────────────────── │  outlineVariant divider
│ [See all changes →]     [ Get Started ]│  TextButton (shell only) + Button
└────────────────────────────────────────┘
```

`BasicAlertDialog` (DS #16), width min(560dp, screen − 32dp), shape `extraLarge`. The
highlights are a grid: **2 columns** when the dialog's content width is ≥ 400dp (the
full 560dp dialog on the Tab S7+ in both orientations and on phone landscape) and **1
column** below (S25U portrait: 412dp − 32dp margins − 48dp padding = 332dp), via
`BoxWithConstraints` + `FlowRow(maxItemsInEachRow = columns)`, 24dp horizontal / 20dp
vertical gaps (desktop `gap-x-6 gap-y-5`). Each highlight: icon 20dp `primary` (desktop
`size-4.5 text-primary`), 8dp, title `titleSmall`, 2dp, description `bodySmall
onSurfaceVariant`. Highlights scroll if taller than 60 % of the screen.

Hero (min height 168dp, desktop `min-h-42`; 24dp horizontal padding, 40dp top, 22dp bottom),
following the DS §1.7 hero rule:

| Scheme | Base | Glows (`Brush.radialGradient`) | Overline | Title |
| --- | --- | --- | --- | --- |
| Brand (fallback) scheme, light and dark | `whatsNew.heroBase` `#2A1852` (oklch 0.27 0.1 293) | `#7C49E3` (oklch 0.55 0.22 293) centred at 15 % / 0 %, radius 120 % of the width, fading to transparent at 55 %; `#3F46AC` (oklch 0.45 0.16 275) at 100 % / 100 %, radius 90 %, transparent at 60 % | `whatsNew.heroOverline` `#EEC6FF` (oklch 0.88 0.09 315) | `#FFFFFF` |
| Dynamic colour | `primaryContainer` | `primary` at 15 % / 0 % (alpha 0.2) and `tertiary` at 100 % / 100 % (alpha 0.45), same radii | `onPrimaryContainer` (full opacity) | `onPrimaryContainer` |

The brand tokens are scoped to the brand scheme (desktop: fixed in both themes, which the
brand scheme keeps); under dynamic colour the dialog follows the wallpaper like every other
surface (DS §1.2 item 4: only status colours stay fixed). Hex values are converted from
the desktop's `HERO_BACKGROUND` oklch literals.

Dynamic-colour contrast (DS §1.8, §8.1): the overline is `labelMedium`, normal-size text that
needs 4.5:1, and a long or large-font title pushes it up to the hero's top-start, under the
primary glow's centre. So the overline stays full-opacity `onPrimaryContainer` (no alpha tint),
and the primary glow is capped at alpha 0.2, the highest value at which a tone-90 overline still
reaches 4.5:1 over a tone-80 glow on a tone-30 container (dark; light passes with margin). The
tertiary glow sits at the bottom-end corner behind the title only, which is large text (3:1), so
it keeps 0.45. `WhatsNewHeroContrastTest` checks both against the brand and baseline schemes.

### 8.3 Copy

Overline "What's new in Cline Termux"; title and highlights from the entry; buttons "See all
changes" (shell only, trailing `arrow_forward` 14dp) and "Get Started". The first Android
entry (`id = "android-0.1"`, written for this app, not copied from the desktop's "Work
anywhere, in parallel"):

| Highlight title | Description | Icon (DS §7, Rounded) |
| --- | --- | --- |
| "Native chat" | "Talk to the Cline kernel in Termux from a real Android app, with streaming replies and Markdown." | `chat` |
| "Approvals on the go" | "Approve tools and answer Cline's questions, and get a notification when Cline needs you." | `task_alt` |
| "Schedules and agents" | "Run routines on a schedule inside the Cline Hub and follow every subagent a task starts." | `schedule` |
| "Customize and marketplace" | "Turn skills, plugins, tools and MCP servers on or off, and install more from the marketplace." | `extension` |

Entry title: "Cline in your pocket". All strings *(Android)*; the owner reviews them. Icons
are decorative (`contentDescription = null`; the title carries the meaning).

### 8.4 Behaviour and bindings

- `WhatsNewRepository`: `latest()` = the first of a bundled Kotlin list (desktop order:
  newest first); DataStore key `whatsNew.seenId` (desktop `cline.code.whats-new.v1`).
  Unreadable DataStore counts as seen (desktop rule).
- Shell: on `ON_START` of `MainActivity`, if `onboarding.completedAt != null` (KS §0.8) and
  `latest().id != seenId`, show C11 once the first frame of Home is drawn (never over the
  onboarding flow or a kernel repair screen). Completing onboarding writes `seenId =
  latest().id` (desktop `markCurrentWhatsNewSeen`).
- Close (back, scrim, "Get Started") → `seenId = id`. "See all changes" → `seenId = id`,
  then navigate `about`.
- From About: same dialog without "See all changes"; closing does **not** write `seenId`
  (desktop: About replays without marking seen). So a user who opens About before the
  shell has shown a pending entry still gets the shell dialog once.
- Back = close (predictive back on the dialog).

### 8.5 Accessibility and acceptance

- The overline + title are one heading; focus starts on the title; "Get Started" is the
  default action (IME/keyboard Enter).
- Contrast: white on `#2A1852` ≈ 15:1 and `#EEC6FF` on `#2A1852` ≈ 10.5:1 (brand);
  `onPrimaryContainer` on `primaryContainer` is an M3 role pair (dynamic). The glows only
  lighten the corners away from the text block.
- Acceptance: install over a build with an older `seenId` → C11 shows once on Home; close
  → not again after restart; fresh install → onboarding, then no C11; About → "Show what's
  new" shows it without "See all changes", and with a still-unseen entry the shell shows
  it again on the next start; on the S25U portrait the highlights are one column, on the
  Tab S7+ two columns, each with its icon; with a green wallpaper and dynamic colour on,
  the hero is green-toned, with the brand scheme it is violet; TalkBack reads the title
  then each highlight.

---

## 9. C12 — Export diagnostics

### 9.1 Purpose and desktop equivalent

Produce one text file with app info, kernel info, recent kernel logs and the metadata of
the sessions the user picks, to attach to an issue. Desktop: `ExportDiagnosticsDialog`
(`export-diagnostics-dialog.tsx`) and `sidecar/diagnostics.ts` (`buildDiagnosticsReport`,
`redactDiagnosticsText`, `DIAGNOSTICS_LOG_TAIL_BYTES = 2 MiB`, prompt and system prompt
removed from manifests, `messages.json` never read, file name
`cline-diagnostics-<version>-<yyyyMMdd-HHmmss>.txt`). Opened from SP S1's "Diagnostics"
row and from About.

Translated: the sidecar writes to `~/Downloads`; the app builds the file in its cache and
hands it to the share sheet or a "Save" document picker *(Android)*. Log tails are capped
by RUN_COMMAND's 100 KB output limit (KC), so each log contributes its last 48 KiB, not
2 MiB.

### 9.2 Layout

Compact/medium: full-screen route (§0.2; medium 720dp column); expanded:
`BasicAlertDialog` 560dp with footer "Cancel" / "Export".

```
┌────────────────────────────────────────────┐
│ ✕  Export diagnostics              Export  │
├────────────────────────────────────────────┤
│ Saves a text file with app info, recent    │
│ logs, and the metadata of the sessions you │
│ pick. Prompts, conversation contents, and  │
│ API keys are never included.               │
│ Sessions                                   │  labelLarge (desktop label)
│ ┌────────────────────────────────────────┐ │
│ │ ☑ Fix the login redirect               │ │  ListItem + Checkbox (whole row toggles)
│ │   app · 5 min ago                      │ │  basename(workspace) · relative time
│ │ ☐ Summarize changes daily              │ │
│ │   home · yesterday                     │ │
│ └────────────────────────────────────────┘ │  max 320dp, scrolls
│ ⚠ <error>                                  │  bodySmall error
└────────────────────────────────────────────┘
```

On success the same surface switches to its **done state** inline (no sheet, no snackbar
while it is open, so nothing is stacked on top of a modal):

```
│ ✓ Diagnostics exported                     │  titleMedium, success.color icon
│ cline-diagnostics-0.1.0-20261003-171205.txt│  ClineMono.small, selectable
│ [ Share ]  [ Save to Files ]               │  Button + OutlinedButton
```

The app-bar action becomes "Done" (closes); "Share" opens the system share sheet and
"Save to Files" opens `ActivityResultContracts.CreateDocument("text/plain")`; after a
successful save the snackbar "Saved to Files" *(Android)* is shown by the **host** screen
once C12 has closed (it is posted to the host's `SnackbarHostState` on close, so it is never
hidden behind a dialog window on expanded).

### 9.3 States and copy

| State | Copy / behaviour |
| --- | --- |
| Title / description | "Export diagnostics" / "Saves a text file with app info, recent logs, and the metadata of the sessions you pick. Prompts, conversation contents, and API keys are never included." (desktop) |
| Sessions label | "Sessions" (`labelLarge`, above the list; desktop) |
| Loading sessions | "Loading sessions…"; **Export disabled** while the list loads (desktop `disabled={exporting \|\| loading}`) |
| No sessions | "No local sessions on this machine yet." → *(Android)* "No sessions in Termux yet." |
| Populated | root sessions only (desktop excludes subagents and cloud); the most recent is pre-selected |
| Hub down | the list cannot load: "Sessions can't be listed while the Cline Hub is not connected. The report will include app and kernel info only." *(Android)*; Export is enabled (the loading state has ended; the deliberate exception to the desktop rule above) |
| Exporting | "Export" disabled with a 16dp spinner; "Cancel" disabled (desktop) |
| Error | the message in `error` under the list |
| Done | inline done state: "Diagnostics exported" (desktop toast title) + file name, buttons "Share", "Save to Files" *(Android)*; primary action "Done" *(Android)*; after a save, host snackbar "Saved to Files" *(Android)* |
| Buttons | "Cancel", "Export" |

### 9.4 Report contents (`DiagnosticsBuilder`)

In this order, each section headed `\n===== <title> =====\n` (desktop `section`):

1. JSON header: `{generatedAt, app: {name: "io.github.ichouchiang.clinetermux", version, versionCode},
   system: {platform: "android", sdkInt, release, device: Build.MODEL, abi},
   kernel: {release, cliVersion, health: /health body, version: /version body,
   gate: KernelGate name}, settings: {theme, notifications prefs, kernel.keepAwake,
   kernel.userStopped}}`. No `globalSettings` (the desktop reads them in-process; the app
   would need another RUN_COMMAND and they hold nothing a bug report needs; OQ C-10).
2. `doctor`: RUN_COMMAND `cline doctor --json` stdout (KS E.6 source).
3. `kernel transitions`: the in-memory ring of the last 50 `KernelCoordinator` state
   changes with timestamps (no payloads).
4. `tail of ~/.cline/data/logs/cline.log` and `tail of ~/.cline/data/logs/hub-daemon.log`:
   RUN_COMMAND `tail -c 49152 <path>` each (separate calls, so each fits the 100 KB cap);
   the partial first line is dropped (desktop `readTail`); a missing file skips its
   section.
5. `session <id>` for each selected id: `session.get {sessionId}` record with
   `metadata.prompt` and `metadata.systemPrompt` removed (desktop rule), pretty JSON.

Then the whole text goes through the `Redactor` (§9.6).

### 9.5 Bindings and ViewModel

- Sessions: `session.list {limit: 200, rootOnly: true}` → sorted by `updatedAt` desc;
  label = metadata title (chat spec `getSessionMetadataTitle` port) or `metadata.prompt`
  trimmed or `sessionId`; subtitle = `basename(workspaceRoot or cwd) · formatRelativeTime`.
  Only the label is shown in the dialog; no prompt text goes into the file.
- Write: `context.cacheDir/diagnostics/<file name>`; shared through a new
  `androidx.core.content.FileProvider` (manifest entry to add: authority
  `io.github.ichouchiang.clinetermux.files`, `exported="false"`, `grantUriPermissions="true"`,
  `res/xml/file_paths.xml` with only `<cache-path name="diagnostics" path="diagnostics/"/>`)
  via `Intent.ACTION_SEND`, `text/plain`, `FLAG_GRANT_READ_URI_PERMISSION`. "Save to Files"
  copies the bytes into the `CreateDocument` URI. Files older than 24 h in that folder are
  deleted when C12 opens.

```kotlin
data class ExportDiagnosticsUiState(
    val sessions: Loadable<List<DiagnosticSession>>, val selected: Set<String>,
    val exporting: Boolean, val error: String?, val result: DiagnosticsFile?,   // uri + name
)
```

### 9.6 Redactor (`diagnostics/Redactor.kt`, also used for the CLI output shown by §1.8, §2.8 and §3.8)

1. The desktop `SECRET_FIELD_PATTERN`, verbatim:
   `((?:api[_-]?key|token|secret|password|authorization)"?\s*[:=]\s*"?)([^",}\s]+)` (case
   insensitive) → `$1<redacted>`.
2. `Sec-WebSocket-Protocol` values `cline-hub-auth\.[0-9a-f]+` → `cline-hub-auth.<redacted>`.
3. Any standalone 64-hex run (the Hub token shape, KC) → `<redacted>` (KS §0.6 rule 1).
4. `Bearer \S+` → `Bearer <redacted>`.
5. `--apikey \S+` / `-k \S+` → `--apikey •••` (KS §0.6 rule 4 form).
6. `/data/data/com.termux/files/home` → `~` (desktop home replacement).

Unit tests: one fixture per rule, plus a fixture containing a real-shaped
`production.json` and a `providers.json` excerpt that must come out with no secret values.

### 9.7 Accessibility and acceptance

- Rows are `toggleable(role = Role.Checkbox)` with the label + subtitle as one target.
- The description is read first on open; errors are assertive live regions.
- Acceptance (S25U): open from Settings → Diagnostics; the newest session is pre-checked;
  "Export" is disabled until the list has loaded; Export → the inline "Diagnostics
  exported" state with the file name → "Save to Files" → after closing, "Saved to Files";
  the file contains the header, doctor
  output, both log tails and the selected session; `grep -E '[0-9a-f]{64}|sk-|Bearer [^<]'`
  on it finds nothing; with the Hub stopped, the notice appears and Export still works.

---

## 10. C13 — Open source licenses *(Android)*

- **Purpose:** list the libraries and fonts the app ships with their licences (required for
  Geist Mono's OFL, DS §2.2, and the Apache-2.0 notices of AndroidX, Kotlin, OkHttp).
- **Desktop equivalent:** none (the desktop bundles notices in its installer).
- **Layout:** `PageScaffold` "Open source licenses"; `LazyColumn` of `ListItem`s (name
  `bodyLarge`, "<licence> · <version>" `bodyMedium onSurfaceVariant`); tap → pushed page
  with the licence text in `ClineMono.small`, selectable, LTR.
- **Data:** a hand-maintained `assets/licenses/index.json`
  `[{name, version, license, url, file}]` with texts under `assets/licenses/` (Geist Mono
  OFL already planned there by DS §2.2); a unit test fails the build when a dependency in
  `app/build.gradle.kts` has no entry (no licence plugin is added; OQ C-11).
- **Entries at 0.1.0:** Jetpack Compose / AndroidX (Apache-2.0), Kotlin + kotlinx
  coroutines/serialization (Apache-2.0), OkHttp (Apache-2.0), commonmark-java (BSD-2-Clause),
  Geist Mono (OFL-1.1), Material Symbols (Apache-2.0), Cline (Apache-2.0, for the copied
  strings, templates and logo).
- **States:** always populated (bundled). **Back:** pops. **Accessibility:** items are
  headings for TalkBack navigation; text pages scroll with the volume keys disabled
  (default).
- **Acceptance:** every entry opens its text; Geist Mono shows the OFL; dark and light
  readable.

---

## 11. C14 — System notifications

### 11.0 Ownership

SP S3 owns the **settings page** (`settings/notifications`) and the **posting contract** of
`ClineNotifier` (SP S3.3: the **eight** session channels, a sounding and a `_quiet` channel
per event, chosen by the Sound toggle, `setSilent` never used; which Hub event triggers
which notification, title/body copy, suppression, grouping per session). This section does
not restate them. It adds what SP S3 leaves to "the System notifications
screen spec": the notification anatomy, the delivery matrix across app and kernel states,
ids and tags, cancel rules, deep links, the channel migration from the current code, and
the acceptance tests. Where a value appears in both, SP S3.3 wins, with **one exception,
the tap contract**: SP S3.3's line "deep link `cline://chat/<sessionId>`" conflicts with CA
§0.6 (the intent-extras contract both entry points implement) and with the sessions spec
(§2.9: "the manifest declares no `cline://` scheme and none is needed"). This spec uses the
CA §0.6 extras (§11.2 "Tap"); the correction of SP S3.3 is filed in Appendix A.

### 11.1 Purpose and desktop equivalent

Tell the user, while Cline is in the background, that a task finished or failed, a tool
waits for approval, or Cline asked a question; one tap opens that session with the right
sheet up. Desktop: `lib/desktop-notifications.ts` (event → copy, defaults, the
background-only rule, dedupe of request ids with a 500-entry memory, no notification on
cancel, completion suppressed while prompts are queued) and the Tauri
`show_session_notification` bridge (not ported; Android `NotificationManagerCompat`
instead, FM).

### 11.2 Anatomy (all four kinds)

```
┌────────────────────────────────────────────┐
│ [bot] Cline · Fix the login redirect · now │  small icon ic_stat_cline (DS §10.5),
│ Approval needed                            │  app name, subText = session title
│ run_commands is waiting for your approval. │  contentTitle / contentText (SP S3.3)
└────────────────────────────────────────────┘
```

| Field | Value |
| --- | --- |
| Small icon | `R.drawable.ic_stat_cline` (DS §10.5) |
| Colour | DS §1.7 "Notification accent"; `session_error` uses the error pair (`#B3261E` / `#F2B8B5`) |
| Title / text | SP S3.3 copy |
| Sub-text *(Android)* | session title (chat spec title rule; fallback "New Session", desktop `agent-header.tsx`) so several sessions stay distinguishable |
| Style | `BigTextStyle(text)` (approval and error bodies can wrap); no large icon |
| When | event timestamp (`setShowWhen(true)`) |
| Category | completion `CATEGORY_STATUS`; error `CATEGORY_ERROR`; approval and question `CATEGORY_REMINDER` (heads-up comes from the HIGH channel, not from the category) |
| Priority (API < 26 fallback is moot: minSdk 26) | from the channel |
| Group | `session-<sessionId>` (SP S3.3); a group summary is posted when a session has ≥ 2 live notifications: title = session title, text "%d updates" *(Android, plurals "%d update" / "%d updates")*, `setGroupSummary(true)`, `GROUP_ALERT_CHILDREN` |
| Tap | `PendingIntent.getActivity(MainActivity, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT)` with the CA §0.6 extras `dev.cline.termux.extra.SESSION_ID` and, for approval/question, `dev.cline.termux.extra.OPEN_SHEET`; request code = the tag's hash so intents stay distinct. No `cline://` data URI (§11.0) |
| Auto-cancel | `setAutoCancel(true)` |
| Actions | none (desktop has none; acting on an approval without seeing its details is unsafe; OQ C-13 asks about a reply action for questions) |
| Visibility on the lock screen | `VISIBILITY_PRIVATE` with a public version: title only ("Approval needed"), no tool name or question text |
| Sound | the channel decides: `ClineNotifier` posts on the sounding channel (`task_completed`, `approval_needed`, `question_asked`, `session_error`) when the event's Sound pref is on and on its `_quiet` twin when off (SP S3.3). `setSilent` is **never** called: a silent notification loses heads-up, so approvals and questions would stop peeking with the default Sound-off preference |
| Channel id | per SP S3.3 table (eight ids); group summary posts on the same channel as its newest child |
| Timeout | none (they stay until opened, answered or cancelled, §11.4) |

Tags and ids (`NotificationManagerCompat.notify(tag, id, n)`):

| Kind | Tag | Id |
| --- | --- | --- |
| taskCompletion / sessionError | `"<sessionId>/result"` (a newer result replaces the older one) | 10 |
| approvalNeeded | `"<sessionId>/approval/<approvalId>"` | 11 |
| questionAsked | `"<sessionId>/question/<requestId>"` | 12 |
| group summary | `"<sessionId>/summary"` | 13 |
| account sign-in (SP S7) | `"account"` | 20 |
| foreground service | (no tag) | 1 (`KernelForegroundService.NOTIFICATION_ID`, existing) |

### 11.3 Delivery matrix

Posting needs four things: the event reaches the app, it belongs to a **known session**,
the app is in the background, and the system lets it show.

- **Events reach the app for every Hub session.** KC connect step 6 opens a global
  `stream.subscribe`, and HF "Every session event also reaches the global subscription":
  `approval.requested`, `capability.requested` and `run.*` of every session arrive there,
  including TUI-, CLI- and schedule-started sessions the app never opened.
- **Rule (Android decision): `ClineNotifier` and `KeepAlivePolicy` (§12.2) act only on
  sessions in `KnownSessions`** and ignore global-stream events for any other `sessionId`.
  `KnownSessions` = every session this install created (`session.create` from the app),
  opened (the chat route resumed for it at least once, including child sessions opened
  from C9 and sessions opened from a schedule run row or Run now), or opened from a
  notification. Stored in DataStore `notifications.knownSessionIds` (most recent 500,
  LRU by last open; removed on `session.delete` from the app). A TUI session the user has
  opened once in the app is known from then on (so §12.6's TUI item posts); a schedule run
  nobody opened is not (OQ C-15).
- Events are deduplicated by `sequence` per connection (HF: a session event can arrive
  twice when a chat also holds a per-session subscription).

| App state | Kernel / socket | What happens |
| --- | --- | --- |
| Foreground (`ProcessLifecycleOwner` ≥ STARTED) | any | nothing posted (desktop rule); the chat shows the sheet/banner itself |
| Background, keep-alive held (§12) | `Ready` | posted live per SP S3.3 |
| Background, keep-alive held | `Busy(Reconnecting)` | events missed during the gap arrive by replay after reconnect (`sinceSequence`); posted if younger than 2 min (SP S3.3), otherwise dropped |
| Background, no keep-alive (frozen after ~10 s, KC) | socket dropped | nothing posted; on resume the replay updates the UI and the app is foreground, so nothing is posted either. This is why §12 holds the service while a run or request is pending |
| Process killed (swipe from Recents, OOM) | — | nothing; the next start re-subscribes and the chat shows pending approvals (re-issued on subscribe) |
| `POST_NOTIFICATIONS` not granted / app notifications off | any | nothing posted; SP S3 shows the permission card; the FGS still runs but its notification only appears in the system's active-apps list (Android 13+) |
| Channel blocked | any | that kind is not posted; SP S3 row shows "Blocked in system settings" |
| Do Not Disturb | any | system rules apply (HIGH channels can be allowed in DND by the user) |
| Kernel down (`Down(*)`) | — | no Hub events, so no posts; the kernel state itself is never a notification *(Android decision, avoids noise when the user stops the kernel)* |

### 11.4 Cancel rules

| Trigger | Cancels |
| --- | --- |
| `approval.resolved {approvalId}` (from any client) | `<sessionId>/approval/<approvalId>` |
| `capability.resolved` for a question request id (answered or cancelled) | `<sessionId>/question/<requestId>` |
| The chat for `sessionId` becomes visible (ON_RESUME of that route) | every notification of that session, including the summary |
| `run.started` for the session | its `result` notification (a new run makes the old result stale) |
| Session deleted from the app (`session.delete` reply ok), or a tap finds it gone (`session.get` → unknown session) | all of that session's notifications (the Hub publishes no delete event) |
| Sign-out / kernel stop | nothing (results stay readable) |
| The last child of a group is cancelled | the group summary |

### 11.5 Dedupe and ordering

- Seen approval and question ids are kept in an in-memory LRU of 500 (desktop) plus a
  DataStore copy of the last 100, so a process restart and replay do not re-post requests
  the user already saw *(Android)*.
- Terminal kinds: at most one per session per run (SP S3.3 "same terminal kind already
  notified ... since its last `run.started`").
- Events are handled in sequence order; a `run.completed` that arrives after the session's
  `approval.requested` still posts both (the approval is cancelled when resolved).

### 11.6 Deep link handling

`MainActivity.onNewIntent` (and `onCreate` for a cold start) maps the CA §0.6 extras to
`chat/{sessionId}?sheet=approval|question` (CA §0.6 owns the extra names and their removal
after routing; `NotificationRouter` only builds the intents). The tapped session is added to
`KnownSessions`. Cold start from a notification goes
through the normal kernel connect sequence first (KS §0.4); the chat shows its loading
state until `Ready`, then opens the sheet if the request still exists, else CA's "This
request was already answered." snackbar. If onboarding is incomplete, the intent is
dropped and onboarding shows (cannot happen in practice: no notifications before
onboarding).

### 11.7 Channel migration (current code → this spec)

`Notifications.kt` today creates `kernel` (LOW), `sessions` (DEFAULT) and `attention`
(HIGH). On first launch of the version that ships `ClineNotifier`:

1. Create SP S3.3's eight session channels in the group "Sessions": `task_completed`,
   `task_completed_quiet`, `approval_needed`, `approval_needed_quiet`, `question_asked`,
   `question_asked_quiet`, `session_error`, `session_error_quiet` (importance, sound and
   vibration exactly as the SP S3.3 table), and SP §11's `account`.
2. Keep `kernel` (§12.1).
3. `deleteNotificationChannel("sessions")` and `("attention")` (nothing was ever posted on
   them; deleting avoids two stale rows in system settings).

Channel ids never change after this release (DS: ids are stable).

### 11.8 Accessibility

- Title and text are full sentences (TalkBack reads the notification as-is); the public
  lock-screen version keeps the title so the user knows something is waiting.
- Tool names and question text are kernel data, untranslated (DS §8.2 item 8).
- Opening from a notification moves focus to the opened sheet's title (DS §8.1 item 7).

### 11.9 Acceptance checklist (S25U)

Run on the debug fake kernel first (KS §0.9), then once on the real kernel outside any
port release window (CLAUDE.md).

- [ ] Start a long task, press Home: the FGS notification appears (§12); when the task
      finishes, "Task completed" posts on `task_completed_quiet` (no sound, no vibration,
      in the shade) with the session title as sub-text.
- [ ] Same with a task that fails: "Task failed" with the error text, error accent.
- [ ] With the default Sound-off prefs, an approval in the background posts a **heads-up**
      (on `approval_needed_quiet`, vibration, no sound); tapping opens the chat with the
      approval sheet; approving it in the TUI instead removes the notification. Turn
      Sound on for approvals: the next one sounds (on `approval_needed`).
- [ ] A session started in the TUI and never opened in the app waits for an approval
      while the app is backgrounded: nothing posts and the FGS is not started for it.
      Open that session once in the app, background it, trigger another approval: it
      posts.
- [ ] Two approvals in one session: two notifications plus a group summary "2 updates".
- [ ] Ask-question in the background: "Cline has a question" with the question text; on
      the lock screen only "Cline has a question" shows.
- [ ] Open the session from the app (not the notification): its notifications disappear.
- [ ] Queue a prompt while a run is going, background the app: no "Task completed" for the
      first run (queued prompts suppress it, SP S3.3).
- [ ] Cancel a run from the TUI: no notification.
- [ ] Revoke the permission: nothing posts; SP S3 shows the card.
- [ ] After upgrading from a build with the old channels, system settings list only
      Kernel, Account and the eight Sessions channels (no "sessions" or "attention").

---

## 12. C15 — Foreground "Cline is working..." notification

### 12.1 Purpose, ownership and desktop equivalent

Keep the app's process unfrozen so the Hub socket survives while the agent works or waits
for the user, and show that it is doing so. CA §0.7 names this spec as the service owner.
No desktop equivalent (a desktop window is never frozen). Service: the existing
`kernel/KernelForegroundService.kt` (type `specialUse`, manifest property already set),
channel `kernel` ("Kernel", IMPORTANCE_LOW, description "Shown while Cline keeps a
session connected in the background", no badge; existing code, kept).

### 12.2 When the service is held (`KeepAlivePolicy`)

```
hold = anyRun(phase in {Starting, Running, AwaitingUser, Stopping})   // CA §0.7
    || approvals.hasPending || questions.hasPending                     // CA §0.7
    || (prefs.keepConnected && kernelGate is Ready && app in background)  // KC "keep connected"
// every term counts only sessions in KnownSessions (§11.3): a TUI- or schedule-started
// session the app never opened neither holds the service nor appears in its text
```

- **Start** only while the app is in the foreground (Android 12+ background-start
  restriction): when `hold` becomes true with the app visible (the user just sent a
  prompt), or at `ON_PAUSE` of `MainActivity` (still visible) if `hold` would be true once
  backgrounded (`keepConnected`). `ForegroundServiceStartNotAllowedException` is caught: no
  crash, the state is recorded as `KeepAlive.Denied` and SP S3's "Background connection"
  row shows "Android did not allow Cline to stay connected." *(Android)*.
- **Stop** when `hold` has been false for 10 s (debounce, so iterations and
  approval → run transitions do not flap the notification): `stopForeground(REMOVE)` +
  `stopSelf()`.
- **Update** in place (same id 1, `notify`) on every state change, at most once per second.
- The service never issues RUN_COMMAND itself and never holds the Hub token; it only keeps
  the process alive. The connection lives in the app graph (existing design).
- `prefs.keepConnected` (DataStore `notifications.keepConnected`, Boolean, default
  **false**) is the "keep connected" switch of KC; its row is SP S3's "Background
  connection" (which links to the kernel sheet). OQ C-12 asks where the switch itself lives.

### 12.3 Variants and copy

| Situation | Title | Text | Chronometer | Actions | Tap |
| --- | --- | --- | --- | --- | --- |
| One run working | "Cline is working..." (DS §10.5, KC) | session title | yes, from `run.started` (`setUsesChronometer(true)`, `setWhen(startedAt)`) | "Stop agent" (DS #6 CD), "Disconnect" | `chat/{sessionId}` |
| One run waiting for approval | "Waiting for your approval" (existing `kernel_notification_waiting`) | "<toolName> is waiting for your approval." (desktop body) | no | "Disconnect" | `chat/{sessionId}?sheet=approval` |
| One run waiting for an answer | "Cline has a question" (desktop title) | session title | no | "Disconnect" | `chat/{sessionId}?sheet=question` |
| Several runs | "Cline is working on %d tasks" *(Android, plurals)* | the session titles joined with ", " (`InboxStyle`, max 5 lines) | no | "Disconnect" | Home (session list) |
| Keep connected, idle | "Connected to the Cline Hub" *(Android)* | "Cline will notify you when a task needs you." *(Android)* | no | "Disconnect" | Home |
| Reconnecting (any of the above, `Busy(Reconnecting)`) | unchanged | "Reconnecting..." (DS §1.6 label) | paused | **"Stop agent" omitted** (no socket to send `run.abort` on; KC does not retry writes); "Disconnect" kept | unchanged |
| Stopping a run | "Stopping..." *(Android)* | session title | no | none | `chat/{sessionId}` |

String fix: `strings.xml` `kernel_notification_working` currently reads "Cline is
working…" (single-character ellipsis); it becomes "Cline is working..." (DS §10.5 and KC
spelling; SP §12 keeps "..." vs "…" exact).

Actions:

- **Stop agent** (shown only while `KernelGate is Ready`) → broadcast to an
  exported=false receiver → `run.abort {sessionId}` (the composer's stop, CA). The
  notification switches to "Stopping..." only after the command reply is `ok`, and stays
  until `run.aborted` (or any terminal `run.*`). If the reply fails (`hub_connection_closed`,
  timeout) the notification returns to its previous variant and, if the app has
  notification permission, nothing else is posted; the chat shows CA's stop failure on
  return. If the gate leaves `Ready` between rendering and the tap, the receiver drops the
  action (the variant is re-rendered without it).
- **Disconnect** → `KernelCoordinator.disconnect()` (KS E.7 "Disconnect": the Hub keeps
  running, Termux wake lock released if `kernel.keepAwake`), then the service stops; the
  running task continues inside the Hub and the app catches up on the next resume. No
  confirm (the action is reversible: "Connect" in the app).

Builder: `setOngoing(true)`, `setSilent(true)`, `setOnlyAlertOnce(true)`,
`CATEGORY_SERVICE`, `FOREGROUND_SERVICE_IMMEDIATE`, colour DS §1.7, small icon
`ic_stat_cline`, `VISIBILITY_PUBLIC` for the generic variants and `VISIBILITY_PRIVATE`
(public version "Cline is working...") when the text holds a session title or tool name.

### 12.4 Edge cases (recovery matrix)

- **Hub dies while held (Termux killed):** the socket closes → KS reconnect backoff →
  `/health` refused → `Down(Asleep)`; the policy's run phases end (`run.failed` is not
  delivered); after 3 failed attempts the service stops and, if notifications are allowed,
  posts nothing extra (the chat shows the KS banner on return). Auto-start from the service
  is never attempted (KS: RUN_COMMAND only from foreground or this service, and the policy
  deliberately does not restart a stopped kernel).
- **User swipes the FGS notification away (Android 14+ allows it):** the service keeps
  running; the next state change re-posts it.
- **App process killed while held:** the service dies with it (same process); nothing to
  clean up; the Hub keeps the run going.
- **`specialUse` review:** the app is sideloaded; no Play declaration needed. If it ever
  ships on Play, the documented alternative is `dataSync` with its 6 h / 24 h budget (KC).
- **Battery:** the service holds no wake lock of its own; Termux's wake lock is KS's.

### 12.5 Accessibility

- Action titles are verbs ("Stop agent", "Disconnect") and readable out of context.
- The chronometer is announced by the system; no custom semantics.

### 12.6 Acceptance checklist (S25U)

- [ ] Send a prompt, press Home: "Cline is working..." with the session title and a
      running timer; it stays for a 5-minute run with the screen off.
- [ ] Trigger an approval: the notification switches to "Waiting for your approval" with
      the tool name; approving in the app returns it to working.
- [ ] "Stop agent" from the shade aborts the run; the notification disappears 10 s later.
- [ ] "Disconnect" from the shade: the service stops; `cline hub status` in Termux still
      shows the Hub; reopening the app shows "Disconnected" (KS F.2) and "Connect" works.
- [ ] Two sessions running: "Cline is working on 2 tasks" with both titles.
- [ ] `notifications.keepConnected` on, no run: backgrounding shows "Connected to the Cline
      Hub"; an approval from a TUI-started session the app had opened before (so it is in
      `KnownSessions`) still posts; one from a TUI session never opened in the app does
      not, and does not switch the notification to "Waiting for your approval".
- [ ] Debug fake kernel `asleep_after_ready` (KS §0.9) with a run going and the app in the
      background: while the notification reads "Reconnecting..." it has no "Stop agent"
      action.
- [ ] Kill Termux from Recents while held: the service ends within ~30 s, no crash, no
      restart loop.
- [ ] `adb shell dumpsys activity services io.github.ichouchiang.clinetermux` shows the service only while
      the policy says hold.

---

## Appendix A — Additions this spec needs in `design-system.md`

DS says screen specs must not define system-level things; these are requested additions
for the owner to merge (until merged, implement them exactly as written). Components that
other specs already propose are used, not redefined: `KernelBanner`, `KernelStatePanel`
(KS §0.7), `KernelGate` (KS F.6), `KernelBlockedCard` and the helper `Unavailable` card
(SP §11).

**Components** (in `ui/components/` unless a feature folder is named, each with light/dark
`@Preview`s of every state):

| Component | M3 / Compose | Spec |
| --- | --- | --- |
| **InlineErrorCard** | `Surface(color = errorContainer, shape = medium)` row: 20dp `error` icon, message `bodyMedium onErrorContainer`, optional trailing `TextButton` ("Retry") | Min height 56dp, 12dp padding; `liveRegion = Assertive`; message is kernel data (verbatim) |
| **DashedNotice** | `Box` with a 1dp dashed `outlineVariant` border (`drawBehind` + `PathEffect.dashPathEffect(4dp, 4dp)`), `shape = medium`, centred `bodyMedium onSurfaceVariant` text, optional action | Desktop `PageEmptyState`'s dashed variant used for per-tab empty/search-empty text; 24dp padding |
| **ExtensionCard** (`customize/`) | `OutlinedCard` with leading 40dp icon tile (`surfaceContainerHighest`, `shapes.small`, 20dp icon), title `titleSmall`, badges row, description `bodyMedium onSurfaceVariant` (3 lines, "Show more"), trailing `Switch` or overflow, expandable details (DS §6 disclosure motion) | Min height 72dp; the switch is its own 48dp target; card tap = expand (or Edit for MCP) |
| **ScopeBadge / TagBadge** (`customize/`) | `Surface(shape = extraSmall, color = surfaceContainerHighest)` + `labelSmall` | Text badges ("Global", "Workspace", "Built-in", "skill", "Marketplace", transport labels); never colour-only |
| **ActionMessage** (`customize/`) | `Text(bodySmall)` under a card, `onSurfaceVariant` (success) or `error` (failure) | Polite live region; cleared on the next action for that card |
| **WorkspaceScopeChip** (`customize/`) | `AssistChip` with `folder_open` leading icon, label = workspace basename (`ClineMono.small`), trailing `arrow_drop_down`; opens SP S9 in "scope" mode | CD "Workspace: <path>, double-tap to change" |
| **KeepAliveNotice** (`schedules/`) | `Surface(color = ClineColors.warning.container, shape = medium)`: `warning` icon, `bodyMedium warning.onContainer` text, trailing `TextButton` action, close `IconButton` (CD "Dismiss") | §4.4; dismissal stored per app version (`schedules.keepAliveNoticeDismissedFor`) |
| **TypeTile** (`marketplace/`) | 40dp `Surface(shape = small)` with the type tint and 20dp icon (§3.5 table) | Used by marketplace rows, detail header and Customize empty states |

**Material Symbols (DS §7.4)**, Rounded, weight 400, fill 0 unless noted: `bolt` (skill
type), `dns` (MCP type), `verified` (verified entry, fill 1), `bug_report`, `shield`,
`newspaper`, `menu_book` (routine templates), `play_arrow` (Run now), `pause` (Pause
schedule), `calendar_month` (Date field), `link_off` (Disconnect action icon in-app),
`open_in_new` (external links; also requested by SP §11), `refresh` (also SP §11),
`storefront` (Marketplace action and detail placeholder), `logout` (Sign Out),
`chat`, `task_alt` (What's new highlights; `schedule` and `extension` exist),
`arrow_forward` ("See all changes").

**Components, amendments to existing ones:**

- **`PageScaffold` (voice port, DS §5.3):** replace `onBack: (() -> Unit)?` with
  `navigationIcon: @Composable (() -> Unit)?` (helpers `BackNavIcon(onBack)` = `arrow_back`
  "Back", `MenuNavIcon(onOpen)` = `menu` "Open sessions"), so a top-level destination
  (Customize, Schedules) on compact shows the drawer button and the same page pushed from
  Settings shows the back arrow (§0.2). DS §5.3's "back arrow with CD Back" line then reads
  "back arrow when pushed, `menu` when it is a top-level destination on compact, none on
  medium/expanded".
- **DS #16:** this spec adopts SP §11's amendment (editors are full-screen `composable()`
  destinations on compact and medium; `BasicAlertDialog` with `decorFitsSystemWindows =
  false` + `imePadding` on expanded only) for the MCP server editor, the schedule editor
  **and** the Export diagnostics surface, which SP's amendment names only "by extension".
  DS #16's current text (`Dialog(usePlatformDefaultWidth = false)` on compact, dialog on
  "medium and above") must be replaced by that amendment.
- **`openExternal(url)` (§0.8):** a shared helper for every external link (Custom Tabs with
  `COLOR_SCHEME_SYSTEM` and per-scheme toolbar colours), referenced by DS's external-link
  rule.

**Cross-spec correction for SP S3.3** (owner of that file to merge): replace "Tap →
`PendingIntent` to `MainActivity` with deep link `cline://chat/<sessionId>`" with "Tap →
`PendingIntent` to `MainActivity` carrying the CA §0.6 extras `SESSION_ID` / `OPEN_SHEET`
(composer-approvals §0.6; no URI scheme is declared, sessions §2.9)". Until merged, §11.0's
exception applies.

**Colour tokens** (DS §1.5 extension, **brand scheme only**, the same in light and dark;
under dynamic colour the hero uses `primaryContainer` / `primary` / `tertiary` /
`onPrimaryContainer`, §8.2, matching DS §1.7's hero-gradient rule):
`whatsNew.heroBase = #2A1852`, `whatsNew.heroGlow1 = #7C49E3`, `whatsNew.heroGlow2 =
#3F46AC`, `whatsNew.heroOverline = #EEC6FF`, `whatsNew.heroOnColor = #FFFFFF` (converted from
the desktop's oklch literals). Contrast of white on `#2A1852` ≈ 15.5:1, of `#EEC6FF` ≈ 10.5:1.

**Notification channels** (DS §10.5 lists the uses, not the ids): the eight SP S3.3
session channels (four sounding + four `_quiet`), `account` (SP §11), and `kernel` as kept
by §12.1; the migration of §11.7.

**DataStore keys** (app file, non-secret): `whatsNew.seenId`,
`notifications.keepConnected`, `notifications.seenRequestIds` (last 100),
`notifications.knownSessionIds` (last 500, §11.3),
`schedules.keepAliveNoticeDismissedFor`, `account.displayName` and `account.scope` (SP S1,
listed for completeness).

**Copy marked *(Android)*** in this file, for `strings.xml`; every desktop string is
verbatim, including "..." vs "…" (SP §12 rule).

---

## 13. Global acceptance (all screens of this spec)

- [ ] Light, dark, brand fallback and dynamic colour render every screen with only the
      allowed role pairs (DS §8.1 item 9).
- [ ] Font scale 2.0 × in-app 20/15: no clipped text in cards, tables or dialogs.
- [ ] Predictive back previews on every pushed page, sheet and dialog; no always-on
      `BackHandler` (DS §5.8).
- [ ] Edge-to-edge: no content under the status or gesture bars, and the editors (C2,
      C6, C12) have the same status/gesture bar colours and icon tint as every other
      screen on One UI; FABs clear the gesture bar and never cover a list's last trailing
      button; the IME does not cover the focused field in C2, C6 and C12, on the S25U
      (full-screen routes) and on the Tab S7+ landscape (dialogs).
- [ ] TalkBack: every icon-only control has a label; no colour-only state.
- [ ] Tab S7+ portrait (rail + single pane, 720dp column) and landscape (expanded layouts)
      match §0.6.
- [ ] No secret appears in any screen, log, diagnostics file or notification: grep the
      diagnostics export and `adb logcat --pid=$(pidof io.github.ichouchiang.clinetermux)` for 64-hex runs,
      `sk-`, `Bearer `, `cline-hub-auth.` followed by hex.
- [ ] The app never reads Termux files except through RUN_COMMAND (`cat`, `tail`,
      `readlink`), and never installs or runs on the S25U during a port candidate's device
      steps or the owner's manual test (CLAUDE.md).

---

## 14. Open questions

Each has the default this spec implements until the owner decides.

1. **C-1 Notification ownership overlap.** SP S3 defines the posting contract and this
   spec the anatomy, matrix and FGS. Default: as split in §11.0 (SP S3.3 wins on overlap).
   Should one spec own all of it?
2. **C-2 MCP metadata.** The helper's `upsert_mcp_server` replaces the whole server record
   with `{transport, disabled}` (and `cline mcp install` with `{transport}`), so saving
   deletes any `metadata` and other non-transport keys, on the desktop too. Chosen default
   (option a): the field is not editable; the editor shows a read-only Metadata note for
   such servers and asks "Save and remove metadata?" before saving (§2.2). Open: ask
   upstream to spread the existing record in `upsertMcpServer` (`servers[name] =
   {...existing, ...next}`) and to accept `input.metadata`, after which the note and confirm
   are removed and a metadata field can be added?
3. **C-3 CLI forms: resolved from source, device confirmation only.** `cline mcp install`
   overwrites a same-name server (`addMcpServer`: `servers[name] = {transport}`, dropping
   `disabled` and `metadata`), so §2.8 uninstalls first only on renames and always re-applies
   `disabled`; the header form is `--header "Header-Name: header value"` (`parseHeader`
   splits at the first ":"); skill removal is `cline skill remove <installed dir> -g -y`,
   forwarding to the kernel's `npx -y skills@latest remove <installedName> -g -y`, with the
   installed directory name derived from `settings.list` (§1.8, §3.8). Remaining: run each
   once on the S25U outside a release window.
4. **C-4 "New Schedule" vs "New schedule".** DS §5.4 writes "New schedule"; the desktop
   string is "New Schedule". Default: desktop casing (verbatim rule); DS to be corrected?
5. **C-5 MCP OAuth.** The dashboard server has no `authorize_mcp_server_oauth`, so the
   desktop's "OAuth authorization required" box and its "Connect" button are omitted; such
   servers can only be authorised with `cline mcp` in a visible Termux session (FM).
   Default: no OAuth UI; add an overflow "Authorize in Termux" (foreground runner) later?
6. **C-6 Child-session data for the agents roster.** Transcript half resolved:
   `session.messages` reads the child row's `messagesPath` (manifest fallback), so §6.7
   binds it unconditionally (a failed call still leaves the line empty; never `cat` a
   transcript, 100 KB cap). Open: `session.list` has no parent filter; it honours `limit`, so
   the sheet re-requests once with `limit: 1000` when the first page returns 200 rows. Ask
   upstream for a `parentSessionId` filter?
7. **C-7 Sign-out payload.** Desktop Account clears the tokens
   (`save_provider_settings {api_key:"", settings.auth: {...:""}}`), SP S7 / S5 removes the
   provider (`enabled:false`). Default: C8 uses the desktop Account payload; SP S5 keeps
   its own. Unify? And sign-out without the helper has no CLI command (SP open question 1).
8. **C-8 Issue and changelog links.** Desktop `ISSUES_URL` points to cline/cline; Android
   bugs belong elsewhere. Default: one constant `AppLinks.ISSUES_URL =
   https://github.com/IChouChiang/cline-termux/issues/new` and release/changelog links to
   the same repository until the app has a public repository. Which repository?
9. **C-9 App updates.** The desktop's update row has no Android counterpart (sideloaded,
   no updater). Default: omitted. Check GitHub releases of the app from About later?
10. **C-10 Global settings in diagnostics.** The desktop includes `readGlobalSettings()`.
    Default: omitted (another RUN_COMMAND, little value). Include `cline config --json`
    output instead?
11. **C-11 Licence list maintenance.** Default: hand-maintained `index.json` plus a unit
    test. Adopt a licence plugin (for example AboutLibraries) instead?
12. **C-12 Where the "keep connected" switch lives.** KC and SP S3 refer to it; KS E has
    "Keep Termux awake" but no "Keep connected". Default: DataStore
    `notifications.keepConnected` (false), surfaced as a switch on SP S3's "Background
    connection" row until KS adds it to the kernel sheet.
13. **C-13 Inline reply for questions.** A `RemoteInput` "Reply" on "Cline has a question"
    would answer free-text questions from the shade. Default: no actions (desktop parity,
    and approvals must be seen). Add later for single free-text questions only?
14. **C-14 Android 16 Live Updates.** The FGS notification could use
    `Notification.ProgressStyle` / promoted ongoing notifications on One UI 8. Default: plain
    ongoing notification. Adopt when the S25U's One UI supports it?
15. **C-15 Which sessions notify.** Every session's events reach the app on the global
    subscription (HF), so the app needs a rule. Default (§11.3): notifications and the
    foreground service consider only `KnownSessions` (created, opened or notification-opened
    in this install); TUI/CLI sessions never opened in the app and schedule runs nobody
    opened stay silent. Should "keep connected" also post results for schedule runs, or
    for all Hub sessions?
16. **C-16 What's new copy.** The first Android entry (§8.3) is new text written for the
    app. Owner review needed before release.
