# Screen spec: Settings, providers, model and workspace

Status: design-phase contract, 2026-10-03. Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`).
Builds on `design/design-system.md` (cited as **DS §n**) and must not redefine anything
it defines. Kernel names are quoted from `docs/03-feature-matrix.md` (cited as **FM**),
`docs/02-kernel-contract.md` (**KC**), `docs/05-open-questions.md` (**OQ n**) and the
recorded traffic in `docs/08-hub-fixtures.md`.

Screens in this spec:

| # | Screen | Route / host | Tier (FM) |
| --- | --- | --- | --- |
| S1 | Settings home | `settings` | 2 |
| S2 | Appearance | `settings/appearance` | 2 |
| S3 | Notifications | `settings/notifications` | 1 (posting) / 2 (settings) |
| S4 | Providers list | `settings/providers` | 2 |
| S5 | Provider detail | `settings/providers/{providerId}` | 2 |
| S6 | Add OpenAI-compatible provider | `settings/providers/add` (full-screen route on compact/medium, `BasicAlertDialog` on expanded) | 2 |
| S7 | Sign in with Cline flow | `SignInWithClineSheet` overlay + `ClineSignInCoordinator` | 2 |
| S8 | Model picker + reasoning menu | `ModelPickerSheet` (`ModalBottomSheet`), `ReasoningMenu` (`DropdownMenu`) | 2 |
| S9 | Workspace picker + folder browser | `WorkspacePickerSheet`, folder browser route `workspace/browse?start={path}` (dialog on expanded) | 2 |

Not in this spec (linked from here, owned by other screen specs): Kernel settings / Kernel
status sheet, Kernel setup (first run), Connect a model (first run), Account, Customize
(Installed), Marketplace, Schedules, About / Diagnostics export, composer chips (the
model, reasoning and workspace *triggers* live in the composer spec; the sheets and menus
they open are specified here).

Desktop sources read for this spec (all under
`~/workspace/cline-termux-v3-phone/apps/examples/desktop-app/webview/` unless noted):

- `components/views/settings/settings-view.tsx` (shell, General page, provider catalog
  load/resync/generation logic, OAuth login, add provider dialog, telemetry, font size,
  app icon, dark mode, replay onboarding, diagnostics row)
- `components/views/settings/sections.ts` (section list, hidden sections, "Installed"
  label), `components/views/page-layout.tsx` (`PageHeader`, `PageEmptyState`)
- `components/views/settings/provider-list-view.tsx` (`ProviderListContent`,
  `ProviderDetailContent`, `ConfigFieldRow`, models card, favourites, featured badges)
- `components/views/settings/add-provider.tsx`, `model-id-input.tsx`,
  `settings-patch.ts`, `notification-settings.tsx`, `account-view.tsx` (signed-out
  card, sign-in copy), `about-view.tsx` (skimmed for the About summary)
- `components/views/onboarding/onboarding-view.tsx` (connect step: "Sign in with Cline",
  "Use a Cline API key", "Use your own API key", provider ordering, done step copy)
- `components/views/chat/chat-input-bar.tsx` (`ModelSelector`, `EFFORT_LEVELS`,
  reasoning seeding, `FALLBACK_PROVIDER_MODELS`, mobile ≤560px popover)
- `components/views/chat/welcome-workspace-controls.tsx` (`WorkspacePicker`, "Just
  chat"), `workspace-selector.tsx` (conversation variant, path entry),
  `remote-directory-picker.tsx` (Home / Up / path / Refresh browser),
  `welcome-setup-notice.tsx`
- `components/agent-sidebar.tsx` (settings section navigation, account footer row),
  `components/agent-header.tsx` (skimmed; no settings surface)
- `app/page.tsx` (`switchWorkspace` validation, `selectChat`, workspace catalog merge)
- `lib/model-selection.ts`, `lib/featured-models.ts`, `lib/provider-schema.ts`,
  `lib/provider-connection.ts`, `lib/provider-key-urls.ts`, `lib/provider-id.ts`,
  `lib/provider-model-catalog.ts`, `lib/desktop-notifications.ts`,
  `lib/app-font-size.ts`, `lib/app-icon.ts`, `lib/workspace-paths.ts`,
  `hooks/use-oauth-user-code.ts`, `hooks/chat-session/helpers.ts`
  (`resolveCredentialFailureAction`)
- `sdk/packages/ui/components/search-combobox.tsx` (sections, badge, description rows)
- `components/views/settings/audio-model-badges.tsx` (Realtime / Transcription pills),
  `lib/cline-account-state.ts` (sidecar-only account result shape)
- Kernel side, to confirm what the bridge can do: `apps/cline-hub/src/server/desktop-commands.ts`
  and `server/providers.ts` (dashboard commands, OAuth opens `xdg-open`, `loadModels`
  projection), `apps/cline-hub/src/server.ts` (startup order, `/health`, unsolicited
  frames), `server/browser-auth.ts` (Host / Origin / `roomSecret` checks),
  `server/sessions.ts` (`initializePeer`), `src/webview-protocol.ts`
  (`WebviewProviderModel`), `sdk/packages/core/src/hub/server/browser-websocket.ts` and
  `runtime/host/local-runtime-host.ts` (`command_failed` for an unknown session),
  `release/install-cline-termux.sh`, `release/run-cline-termux.sh`, `apps/cli/bin/cline`
  (the launcher exec chain that the cancel pattern must match),
  `apps/cli/src/commands/auth.ts` + `main.ts` (`cline auth` flags, outputs, errors),
  `sdk/packages/core/src/services/workspace/chat-workspace.ts` and
  `hub/server/handlers/session-handlers.ts` (empty workspace = shared chat workspace),
  `sdk/packages/core/src/services/storage/provider-settings-manager.ts` (`providers.json`
  shape).
- Voice app (`~/workspace/cline-termux-voice/android/.../dev/cline/voice`):
  `ui/Components.kt` (`PageScaffold`, `TextInput`, `LocalSnackbarHost`),
  `ui/SettingsScreen.kt` (flat preference list, system checks re-read on resume),
  `ui/settings/Prefs.kt` (`PrefHeader`, `PrefRow`, `PrefSwitch`), `ui/settings/SubPages.kt`
  (`SubPageHost`, `formPadding`), `MainActivity.kt` (notification / battery intents).

---

## 0. Shared rules for every screen in this spec

### 0.1 Code layout

```
app/src/main/kotlin/dev/cline/termux/
  settings/SettingsHomeScreen.kt, SettingsHomeViewModel.kt
  settings/AppearanceScreen.kt, AppearanceViewModel.kt
  settings/NotificationSettingsScreen.kt, NotificationSettingsViewModel.kt
  settings/providers/ProvidersListScreen.kt, ProviderDetailScreen.kt, AddProviderScreen.kt (route on compact/medium, dialog on expanded)
  settings/providers/ProvidersViewModel.kt        one VM for list + detail + add (desktop keeps one state too)
  settings/providers/ProviderCatalogRepository.kt  catalog cache, generation counter, fallback
  settings/helper/SettingsHelper.kt               dashboard lifecycle + /browser WebSocket (KC "On-demand settings helper")
  settings/helper/HelperFrames.kt                 @Serializable desktopCommand / desktopCommandResult frames; lenient decoder (§0.3 step 2)
  account/ClineSignInCoordinator.kt, SignInWithClineSheet.kt
  model/ModelPickerSheet.kt, ReasoningMenu.kt, ModelSelectionStore.kt, ModelPickerData.kt (port of featured-models.ts)
  workspace/WorkspacePickerSheet.kt, FolderBrowserScreen.kt (route on compact/medium, dialog on expanded), WorkspaceStore.kt, WorkspacePaths.kt (port of workspace-paths.ts)
  notifications/NotificationPrefs.kt, ClineNotifier.kt (channels, posting rules of S3)
app/src/main/res/raw/fallback_providers.json      bundled provider list for fallback mode (§0.4)
```

Code adapted from the voice app carries `// Adapted from cline-termux-voice (same owner): <path>` (DS header).

### 0.2 Navigation graph and back

```
settings ──┬─ settings/appearance
           ├─ settings/notifications
           ├─ settings/providers ── settings/providers/{providerId}
           │                    └── settings/providers/add   (full-screen NavHost destination on
           │                                                   compact/medium; BasicAlertDialog on
           │                                                   expanded; §11 DS #16 amendment)
           ├─ kernel            (Kernel settings spec)
           ├─ account           (Account spec)
           ├─ customize, marketplace (Customize spec; "customize" is also a rail destination, DS §5.2)
           ├─ schedules         (Schedules spec; also a rail destination)
           └─ about             (About spec)

workspace/browse?start={path}   folder browser (S9); pushed from any screen hosting the
                                workspace sheet; full-screen on compact/medium, dialog on expanded
```

- `settings/{section?}` (DS §5.2) is the deep-link form: `settings/providers`,
  `settings/providers/cline`, `settings/appearance`, `settings/notifications`. The chat's
  credential-failure action "Open API providers" (desktop `resolveCredentialFailureAction`
  target `models`) navigates to `settings/providers/{providerId of the failing session}`;
  "Sign in to Cline" (target `account`) navigates to `account`, whose signed-out card
  starts S7.
- **Compact and medium:** list → push with shared-axis X (DS §6). Back pops one level
  (predictive, seekable). From `settings` back returns to the previous destination
  (Chats). Never register an always-on `BackHandler` (DS §5.8).
- **Expanded (≥ 840dp):** `NavigableListDetailPaneScaffold` (DS §5.2): list pane 360dp =
  S1, detail pane = the selected section. Default detail when Settings opens with no
  section = Appearance (the desktop opens "General", whose rows Appearance carries).
  **Providers is not a detail of that scaffold.** Tapping the Providers row navigates
  the NavHost to `settings/providers`, a separate route that *replaces* the Settings
  scaffold with its own single `NavigableListDetailPaneScaffold` (providers list pane
  360dp + provider detail pane, the desktop's two-column grid). Its list pane's
  `MediumTopAppBar` carries a back arrow to `settings`. There is never a scaffold nested
  in another scaffold's pane, so only one `ThreePaneScaffoldNavigator` exists at a time
  and it owns pane back alone (three panes side by side would also leave the provider
  detail under 450dp on the Tab S7+). The same rule applies to any other section that
  needs two panes of its own (Customize, Schedules: their specs). Back order on expanded,
  Providers: provider detail → providers list is not a step (both panes are visible, so
  pane back has nothing to pop; the scaffold uses
  `BackNavigationBehavior.PopUntilScaffoldValueChange`, so switching between providers
  does not grow a back history) → `settings` (NavHost pop, seekable shared-axis);
  `settings/providers/add` (dialog) closes first when open. On medium (single pane),
  Providers behaves like compact: detail → list (pane back) → `settings` (NavHost pop).
- Sheets (S7, S8, S9) are not routes. They are hoisted into the screen that opened them,
  survive rotation (`rememberSaveable` of "open" + the VM), and close on back with the M3
  predictive collapse (DS §5.8 item 2).

### 0.3 The settings helper (`SettingsHelper`)

S4, S5, S6, S1's Telemetry row, S8's catalog refresh and S7's API-key verification use
the shipped dashboard server as KC "On-demand settings helper" describes, with the
hardening below (room secret, port-scoped kill, Hub adoption). One process-wide
singleton, scoped to the Application:

1. `acquire(owner)` increments a holder count. On the first holder:
   1. **Kernel first.** If the user pressed "Stop kernel" in this app process
      (`KernelCoordinator.userStopped == true`), do not start anything: the state goes
      straight to `Unavailable(KernelStopped)` and the screen runs in fallback mode
      (§0.4). Opening Providers must never silently restart a kernel the user stopped
      (resolves §13 item 5 in the conservative direction).
   2. Otherwise, if the Hub's `GET http://127.0.0.1:25463/health` fails, ask the
      `KernelCoordinator` to run its own connect sequence first (KC steps 2-3:
      `cline hub start`, `termux-wake-lock`, token re-read) and wait for it (its own
      8-15 s cold path, shown as "Connecting..." on the kernel dot, DS §1.6). The
      dashboard's `attachHub` then attaches to a Hub the app owns instead of spawning
      one the app has not adopted. If the coordinator fails, the helper is still tried
      (the dashboard can boot its own Hub); after `Ready`, the coordinator runs KC steps
      2-3 again (token re-read, wake lock) so it adopts whatever Hub is now on 25463.
   3. Generate a per-launch `roomSecret` = 32 random hex characters (`SecureRandom`,
      16 bytes). It lives only in the singleton's memory: never DataStore, logs,
      `SavedStateHandle` or crash reports. A new app process makes a new secret.
   4. Probe `GET http://127.0.0.1:8787/health?roomSecret=<secret>` with headers
      `Host: 127.0.0.1:8787` and `Origin: http://127.0.0.1:8787` (1 s timeout; `/health`
      is not a public route, so the dashboard answers 403 `unauthorized_browser` without
      them):
      - **refused**, or no HTTP answer within 1 s (timeout, reset, garbage) → start
        (step 5); if a non-dashboard process holds the port, the dashboard fails to
        bind and exits non-zero, which step 5 reports as `StartTimeout` with its stderr;
      - **200** → a dashboard is already up. Probe once more *without* `roomSecret`: if
        that also answers 200, it is a dashboard without a secret (user-started in a
        Termux terminal); it is **not reused** (any app on the phone could read
        plaintext keys through it) → `Unavailable(PortInUse)`. If the second probe is
        403, it is ours from this process → `Ready` path (step 6);
      - **403** → a dashboard with a different secret: ours from an earlier app process
        (secret lost with the process). RUN_COMMAND
        `pkill -f "index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret [0-9a-f]{32}( |$)"`,
        wait for the port to be refused (500 ms polls, up to 5 s), then start (step 5).
        The pattern only matches the app's own launch line, never a dashboard the user
        started by hand; if the port still answers after 5 s, it is the user's own
        dashboard with a secret → `Unavailable(PortInUse)`.
   5. **Start:** `TermuxExecutor.runDetached` (§0.5) background
      `$PREFIX/bin/cline dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret <secret>`
      (`apps/cli/src/main.ts` `--room-secret`). The command never exits; the executor
      applies no completion timeout and uses the result `PendingIntent` only to detect
      an early exit (exit code ≠ 0 → `Unavailable(StartTimeout)` with the stderr tail as
      `detail`). Poll `/health` (as in step 4, with the secret) every 500 ms. Budget:
      **20 s** when the Hub was already up when polling began, **45 s** otherwise (the
      dashboard awaits `attachHub` → `ClineCore.create` *before* `Bun.serve` binds,
      `apps/cline-hub/src/server.ts`, so a cold Hub start sits inside the poll). While
      polling, the state is `Starting`.
   6. OkHttp WebSocket `ws://127.0.0.1:8787/browser?roomSecret=<secret>` with headers
      `Host: 127.0.0.1:8787` and `Origin: http://127.0.0.1:8787` (`browser-auth.ts`
      checks Host, Origin and, because the dashboard was started with a secret, the
      `roomSecret` query parameter). The secret is in the URL, so OkHttp's
      `HttpLoggingInterceptor` is never installed on this client.
2. Send `{type:"ready"}`. Requests this spec uses:
   `{type:"desktopCommand", id, command, args}` → `{type:"desktopCommandResult", id, ok, result|error}`.
   That is the only request type: provider catalog = `desktopCommand list_provider_catalog`,
   models = `desktopCommand list_provider_models {provider}` (§0.7). The browser frames
   `{type:"loadModels"}` → `{type:"models"}` and `{type:"loadProviderCatalog"}` are
   **not used by this spec**: the `models` reply is `WebviewProviderModel`
   (`apps/cline-hub/src/webview-protocol.ts`), which strips `featured`, `description`,
   `contextWindow`, `supportsVision` and `supportsAttachments`; the desktop never uses it
   for settings or the picker either (`lib/provider-model-catalog.ts`
   `loadProviderModels` = `list_provider_models`).
   **Decoder:** `ready` makes the dashboard push unsolicited frames
   (`apps/cline-hub/src/server/sessions.ts` `initializePeer`): `status`, `defaults`,
   `providers`, `models` (for the default provider), `error`, `provider_catalog`,
   `sessions`, the hub-state payload, and a hub-state broadcast every 5 s. The decoder
   reads `type` first, handles only `desktopCommandResult` (correlated by `id`), and
   **ignores every other or unknown `type`** without closing the socket
   (`Json { ignoreUnknownKeys = true }`, no closed sealed hierarchy over all types).
   The unsolicited `provider_catalog` frame carries plaintext keys: it is dropped
   unparsed.
   Each request has a client timeout of 30 s, except `add_provider` (60 s, it may fetch
   a model source URL).
3. `release(owner)` decrements. At zero, wait **5 s** (grace, so moving between
   Providers and Account does not restart a Bun process), then close the socket and
   RUN_COMMAND `pkill -f "index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret <secret>( |$)"`
   (the exact secret: only this process's helper). A helper that is still `Starting` is
   never killed while its poll budget runs; if every holder left, the kill happens when
   the poll ends (ready or timed out). KC says "stop it when leaving settings"; the 5 s
   grace is the only refinement. The Hub stays up (the coordinator owns it).
4. Starting the helper never leaves an un-adopted Hub behind: either the coordinator
   started the Hub first (1.2), or it adopts the one the dashboard booted right after
   `Ready`. The kernel dot shows "Connecting..." meanwhile; this is expected, not an
   error.

Why the secret (deviation from KC "no roomSecret on loopback"): Android's loopback is
shared by every app on the device. Without a secret, any app could open
`ws://127.0.0.1:8787/browser` with a forged `Origin` header, send `ready` and receive the
full `provider_catalog` with plaintext `apiKey`s, or call `save_provider_settings`. The
secret travels in the launch argv (other apps cannot read Termux's `/proc` on Android 7+)
and in the loopback URL only.

```kotlin
sealed interface HelperState {
    data object Idle : HelperState
    data class Starting(val sinceMs: Long) : HelperState           // UI: LinearProgressIndicator + "Starting settings helper..."
    data object Ready : HelperState
    data class Unavailable(val reason: HelperFailure, val detail: String?) : HelperState
}
enum class HelperFailure { TermuxMissing, PermissionMissing, ExternalAppsBlocked, StartTimeout, SocketFailed, BuildUnsupported, PortInUse, KernelStopped }
```

`BuildUnsupported` is set permanently (DataStore `helper.unsupported = true`, cleared on
app update or when the Kernel status sheet's "Check again" succeeds) when the helper
starts but its WebSocket upgrade fails on the device Bun (OQ 3). Then every screen here
runs in **fallback mode** (§0.4) without trying to start it again.

**Unavailable card** (DS #12 "helper unavailable"): `Surface(warning.container, shape = large)`
at the top of the content, `onContainer` text.

| Reason | Title | Body | Actions |
| --- | --- | --- | --- |
| StartTimeout, SocketFailed | "Settings helper unavailable" *(Android)* | "Cline could not start its settings helper in Termux, so this page shows a reduced set of options. Keys you save still go straight to Termux." *(Android)* | `TextButton` "Retry" *(Android)* · `TextButton` "Details" *(Android)* (opens an `AlertDialog` titled "Settings helper unavailable" with the RUN_COMMAND `errmsg`/stderr tail in `ClineMono.body`, button "Close") |
| BuildUnsupported | "Settings helper unavailable" | "This Cline kernel build cannot run the settings helper on this phone. You can still add API keys and choose models." *(Android)* | "Details" |
| PortInUse | "Settings helper unavailable" | "Another Cline dashboard is already running in Termux on port 8787. Stop it to see every option here. You can still add API keys and choose models." *(Android)* | "Retry" · "Details" (body: "A dashboard started without an invite secret can be read by other apps on this phone, so Cline does not use it." *(Android)*) |
| KernelStopped | "Kernel is stopped" *(Android)* | "You stopped the Cline kernel, so this page shows a reduced set of options. Keys you save still go straight to Termux." *(Android)* | `TextButton` "Start kernel" *(Android)* (KernelCoordinator start, clears `userStopped`, then retries the helper) |
| TermuxMissing, PermissionMissing, ExternalAppsBlocked | see §0.5 | | |

### 0.4 Fallback mode (no helper)

The design degrades exactly as FM and KC say: providers = `cline auth`, models = a bundled
list plus a free-text model id, account = omitted.

- **Provider catalog:** `res/raw/fallback_providers.json`, one entry per provider id the
  CLI accepts (`anthropic, openai-native, openrouter, gemini, xai, groq, mistral,
  deepseek, ollama, openai-compatible, cline, cline-pass, openai-codex, oca, cerebras,
  fireworks, together, wandb, …`; generate it from `listLocalProviders` at app build time
  and commit it), each with `{id, name, popular:Boolean, authKind:"oauth"|"local"|"api-key", supportsBaseUrl:Boolean}`.
  `supportsBaseUrl` is true only for `openai-native` and `openai-compatible` (the CLI
  rejects `--baseurl` elsewhere: "base URL is only supported for OpenAI and
  OpenAI-compatible providers").
- **Configured state:** RUN_COMMAND `cat /data/data/com.termux/files/home/.cline/data/settings/providers.json`
  (`ClineCommands.readProvidersJson()`), parsed in memory and dropped
  (FM: "never persist the file contents"). From `providers[<id>].settings` the app keeps
  only: `configured = apiKey.isNotBlank() || auth.accessToken.isNotBlank()`, `model`,
  `baseUrl` presence (not value), and `lastUsedProvider`. The raw string, keys and tokens
  are never stored, logged or put in UI state.
- **Models:** the desktop `FALLBACK_PROVIDER_MODELS` table, ported 1:1:
  `cline → ["anthropic/claude-sonnet-5"]` (`CLINE_DEFAULT_MODEL_ID`),
  `anthropic → ["claude-sonnet-4-6"]`, `openai-native → ["gpt-5.5"]`,
  `openrouter → ["anthropic/claude-sonnet-4.6"]`, `gemini → ["gemini-3-pro-latest"]`,
  plus the `model` the user saved for each configured provider, plus the free-text row
  (S8). The desktop's `FALLBACK_PROVIDER_REASONING_MODELS` table is ported next to it
  (today identical to `FALLBACK_PROVIDER_MODELS`, keep them as two tables so they can
  diverge). Reasoning support in fallback (`reasoningCapabilitySource = "fallback"`,
  `chat-input-bar.tsx`): `true` for a model listed in the reasoning table, `false` for a
  model listed in `FALLBACK_PROVIDER_MODELS` but not in the reasoning table, `null`
  (unknown) for any other model (a saved `model` or a free-text id). S8.3 and S8.6 use
  this rule.
- **Save:** RUN_COMMAND `cline auth --provider <id> --apikey <key> --modelid <model> [--baseurl <url>]`
  (`ClineCommands.authProvider`). **Success = exit code 0**, never a stdout match: the
  CLI writes its lines through an unconditional colour table (`apps/cli/src/commands/auth.ts`
  `const c = {reset:"\x1b[0m", green:"\x1b[32m", cyan:"\x1b[36m", …}`, used whether or not
  stdout is a TTY), so stdout reads `ESC[32mProvider configured:ESC[0m ESC[36m<id>ESC[0m (<model>)`.
  `ClineCommands` strips ANSI SGR sequences (`Regex("\u001B\\[[0-9;]*m")`) from stdout
  and stderr of **every** RUN_COMMAND before matching, displaying or logging. The success
  snackbar is the app's own string *(Android)* "Provider configured: <id> (<model>)",
  rebuilt from the arguments, not echoed from stdout. Errors (exit ≠ 0) show the
  stripped last non-blank stderr line verbatim (examples: "auth quick setup requires
  --apikey <key>", "invalid provider \"x\"").
- **Not available in fallback** (hidden, never shown disabled, DS §9): Disconnect /
  Sign out, the models card refresh, favourites persistence still works (local), custom
  OpenAI-compatible providers (S6 becomes the built-in `openai-compatible` detail),
  Telemetry write (read-only row), Cline API-key verification.

### 0.5 Termux and kernel failure surfaces

Every RUN_COMMAND here goes through `TermuxExecutor`. Its failures map to one inline
`KernelBlockedCard` (`Surface(errorContainer, shape = large)`, 16dp padding, `error`
icon, title `titleSmall`, body `bodyMedium`, one `TextButton`) placed where the content
would be. The long explanations live in the Kernel setup spec; this card is the short form.

| Condition (KC recovery matrix) | Title *(Android)* | Body *(Android)* | Action |
| --- | --- | --- | --- |
| `com.termux` not resolvable via `<queries>` | "Termux is not installed" | "Cline runs its kernel inside Termux." | "Install Termux" → Install Termux screen |
| RUN_COMMAND permission not granted | "Cline can't run commands in Termux yet" | "Allow the Termux \"Run commands\" permission to continue." | "Allow" → runtime permission request; on permanent denial "Open app settings" |
| result `err != -1`, `errmsg` mentions allow-external-apps | "Termux is blocking Cline" | "Turn on allow-external-apps in Termux, then try again." | "Open kernel setup" |
| RUN_COMMAND never returns (60 s; ordinary calls only, not detached ones) | "Termux did not answer" | "The command did not finish within a minute." | "Retry" + overflow "Run diagnostics" (`cline doctor --json`, About spec) |

`TermuxExecutor` has two modes:

- `run(cmd)` (default): waits for the result `PendingIntent`, 60 s completion timeout
  (the last row above), stdout/stderr ANSI-stripped (§0.4 "Save").
- `runDetached(cmd)`: for commands that deliberately do not return or return only when
  the user is done: the dashboard launch (§0.3), `cline auth <oauth provider>` in the
  foreground (S7) and `termux-setup-storage` (S9). **No completion timeout**; the result
  `PendingIntent` is used only to detect an *early* exit: exit code ≠ 0 → the caller's
  failure path with the stripped stderr tail; exit code 0 → the caller's own completion
  logic (S7 success, S9 re-check). Delivery failures (`err != -1`, permission) map to
  the table above as usual.

Hub states (connected / connecting / reconnecting / unavailable / Termux asleep) are the
DS §1.6 kernel-connection table. Which screen needs what:

| Screen | Needs Termux (RUN_COMMAND) | Needs the helper | Needs the Hub |
| --- | --- | --- | --- |
| S1 Settings home | for summaries only | Telemetry write only | Schedules/Customize summaries only |
| S2 Appearance, S3 Notifications | no | no | no |
| S4/S5/S6 Providers | yes | preferred (fallback otherwise) | no |
| S7 Sign in with Cline | yes | only for "Use a Cline API key" verification | no |
| S8 Model picker | no (cached catalog) | refresh only | only to switch a **live** session |
| S9 Workspace picker | yes (folder listing, validation) | no | no (validated again by `session.create`) |

### 0.6 Secrets

- Provider keys and OAuth tokens never enter DataStore, logs, crash reports, `SavedStateHandle`,
  `rememberSaveable`, analytics or screenshots. Text fields holding a secret use
  `KeyboardType.Password`, `autoCorrectEnabled = false`, and the screen sets
  `WindowManager.LayoutParams.FLAG_SECURE` on the activity window while a secret value is
  revealed (S5, S6, S7). That flag does not reach a separate dialog window, so every
  surface that can reveal a secret is either in the activity window (S5, S6 on
  compact/medium are NavHost destinations; S7's sheet is a `ModalBottomSheet`, whose
  window inherits the secure policy via `ModalBottomSheetProperties(securePolicy =
  SecureFlagPolicy.SecureOn)` while the key is shown) or, for S6 on expanded, a
  `BasicAlertDialog` with `DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)`
  while a secret is shown.
- `ProviderListItem` from `list_provider_catalog` carries the plaintext `apiKey` (FM). It
  lives only in the VM's in-memory catalog and is wiped on `onCleared()`. The persisted
  catalog cache (§0.7) strips `apiKey`, `configValues` and `baseUrl` before writing.
- "Copy" on a secret puts it on the clipboard with `ClipDescription.EXTRA_IS_SENSITIVE = true`
  (API 33+) so One UI hides the preview, and records `secretCopiedAtMs` in memory. The
  app cannot check whether its clip is still the primary one while in the background
  (API 29+ returns null), so it clears unconditionally with `clearPrimaryClip()`: when the
  60 s timer fires while the activity is `RESUMED`, otherwise at the next `ON_RESUME`
  after the 60 s have passed. A non-secret clip the user copied afterwards in another
  app may be cleared too; that is the accepted cost.
- A key passed to `cline auth --apikey` travels in a RUN_COMMAND intent extra. The
  `TermuxCommand.label` for that call is `"cline auth <providerId>"`; the argument array
  is never logged (the bridge logs `label` only). The same applies to the helper's
  `roomSecret` (§0.3): labels "start settings helper" / "stop settings helper".

### 0.7 Catalog cache (`ProviderCatalogRepository`)

Port of the desktop's `providerCatalogCache` + `catalogGenerationRef`:

- In-memory TTL **60 s** (`PROVIDER_CATALOG_CACHE_TTL_MS`).
- Every optimistic mutation and every load claims `generation++`; a response whose
  generation is older than the current one is discarded ("an older disk snapshot can
  never overwrite a newer edit").
- After a successful save, a silent resync (`list_provider_catalog`) refreshes
  sidecar-computed fields (`configured`) without showing the loading state.
- After a failed save: show the error, then reload with up to 3 attempts while a newer
  generation supersedes, then bump `detailResetToken` so the detail's field drafts remount
  from the reloaded values (desktop `setDetailResetToken`).
- A non-secret projection is written to `filesDir/catalog-cache.json` (provider id, name,
  enabled, configured, capabilities, authKind, defaultModelId, and per provider the
  model list: `id, name, description, featured, contextWindow, supportsVision,
  supportsReasoning, supportsAttachments, inputModalities, outputModalities, operation,
  operationModes` = the `ProviderModel` fields) so S8 opens instantly without the helper.
  `ProviderListItem` (`sdk/packages/shared/src/rpc/runtime.ts`) has no `docUrl` /
  `docLabel`, so the projection has none either (S5.2 link rule).
- **Model lists come only from `desktopCommand list_provider_models {provider}`** →
  `{providerId, models: ProviderModel[]}` (dashboard `getLocalProviderModels`, which
  also stamps `featured` for `cline` / `cline-pass` through `applyClineFeaturedModels`,
  `sdk/packages/core/src/services/providers/local-provider-service.ts`). S5 and S8 both
  use it; a result replaces that provider's cached list only when it is non-empty or the
  call succeeded with an explicit empty list.
- `invalidate()` is called after any provider save, add, disconnect, OAuth sign-in or
  `cline auth` success; S8, the welcome setup notice (composer spec) and S1's summary
  observe the repository's `Flow`.

### 0.8 Common accessibility rules

DS §8.1 applies everywhere. In addition, for these screens:

- Every switch row is one `toggleable` target with `Role.Switch` (voice `PrefSwitch`):
  the whole row toggles, TalkBack reads "<headline>, <supporting>, switch, on".
- Navigation rows are `Role.Button` with a trailing `chevron_right`, which is decorative
  (`contentDescription = null`).
- Section headers carry `semantics { heading() }` (voice `PrefHeader`).
- Inline errors under a control are `LiveRegionMode.Polite`; save failures that roll a
  value back are `Assertive` (desktop `role="alert"`).
- Saving rows announce "Saving" *(Android)* via `stateDescription` while their spinner shows.

---
## S1. Settings home

**Purpose.** One flat Material 3 preference list that states where each setting stands
now and leads to every settings section. Telemetry is changed in place.

**Desktop equivalent.** The settings shell (`settings-view.tsx`), the sidebar's
`SettingsSectionNavigation` (`agent-sidebar.tsx`, section icons and order from
`sections.ts`), and the "General" page (`GeneralSettingsContent`). Copied: the page
title "Settings", the section names and order (General → Providers → Schedules → Account
→ About, plus the Customize group with "Installed" / "Marketplace"), the Telemetry,
"New user experience" and "Diagnostics" rows with their exact copy, the account footer's
"Personal" scope fallback. Translated: the sidebar list becomes the S1 list (list pane on
expanded); "General" splits into Appearance (S2) and Notifications (S3) as DS #12 says;
hidden or desktop-only sections (Voice, Channels, Import, Remote) and the "Keep CLI up to
date" / "Cloud sessions" / "Accent color" rows are omitted (FM: auto-update irrelevant on
the installer-managed port; cloud and remote are "none"; accent comes from dynamic colour,
DS §9).

### S1.1 Layout

Compact (S25U portrait, 412dp):

```
┌──────────────────────────────────────────┐
│ ←  Settings                              │  MediumTopAppBar (voice PageScaffold), collapses
│                                          │
│ Manage preferences for this app and the  │  description, bodyMedium onSurfaceVariant
│ Cline kernel in Termux.                  │
│                                          │
│ App                                      │  PrefHeader (titleSmall, primary)
│ [palette]  Appearance                  › │
│            System default · Dynamic colour│
│ [notif]    Notifications               › │
│            On for 4 events               │
│ Kernel                                   │
│ [logo]     Kernel                      › │
│            ● Cline Hub @25463            │  DS §1.6 dot + line 1
│ Models                                   │
│ [power]    Providers                   › │
│            2 configured · 41 available   │
│ [account]  Account                     › │
│            Ada Lovelace · Personal       │
│ Customize                                │
│ [extension] Installed                  › │
│            12 skills · 3 MCP servers     │
│ [store]    Marketplace                 › │
│ [schedule] Schedules                   › │
│            3 schedules · 2 active        │
│ Privacy                                  │
│ [analytics] Telemetry               [●══]│  inline Switch
│            Enable error and usage reports│
│            to help improve Cline.        │
│ Support                                  │
│ [restart]  New user experience           │  action row (whole row = button)
│            Replay the first-run ...      │
│ [share]    Diagnostics                   │  action row → Export dialog
│            Export app info, recent logs..│
│ [info]     About                       › │
│            Cline Termux 1.0.0 ·          │
│            kernel 3.0.68                 │
└──────────────────────────────────────────┘
```

Medium (Tab S7+ portrait, 778dp): same single list, content column max width 720dp,
centred, 24dp gutters (DS §3.3). Rail visible with Settings selected.

Expanded (Tab S7+ landscape, 1244dp):

```
┌────┬───────────────────────────┬──────────────────────────────────────────────┐
│rail│ Settings                  │ Appearance                                   │
│    │ Manage preferences for... │ Choose how Cline looks on this device.       │
│ ◉  │ App                       │ ...S2 content...                             │
│ chat│▌Appearance            ›  │                                              │
│ ⏱  │ Notifications          ›  │                                              │
│ ⧉  │ Kernel                    │                                              │
│ ⚙  │ ● Kernel               ›  │                                              │
│    │ Models                    │                                              │
│    │ Providers              ›  │                                              │
│    │ ...                       │                                              │
└────┴───────────────────────────┴──────────────────────────────────────────────┘
```

The selected row gets the `secondaryContainer` indicator (DS §1.4 "selected row") and
`aria-current` equivalent `semantics { selected = true }`. Telemetry, New user experience
and Diagnostics act in place (switch / dialog) and never become the selected row. On expanded the list pane has no back
arrow (it is the root of the destination); the detail pane's `MediumTopAppBar` shows a
back arrow only when the list pane is hidden.

### S1.2 Rows, copy and bindings

Row anatomy: `ListItem` with leading 24dp Material Symbol (DS §7.4), headline
`bodyLarge`, supporting `bodyMedium onSurfaceVariant` (max 2 lines), trailing
`chevron_right` for navigation rows; action rows (New user experience, Diagnostics) have
no trailing element and are one `Role.Button` target each (no button inside a list item:
one focus stop per row, no wrapping at large font scale). Group headers *(Android)*: "App", "Kernel",
"Models", "Customize", "Privacy", "Support".

| Row | Icon | Headline | Supporting (live summary) | Data source | Tap |
| --- | --- | --- | --- | --- | --- |
| Appearance | `palette` | "Appearance" *(Android)* | "<theme> · <Dynamic colour if on>" e.g. "System default · Dynamic colour", "Dark", "Light · Font size 17" (font size appended only when ≠ 15) | DataStore `appearance.*` | S2 |
| Notifications | `notifications` | "Notifications" | permission granted: "On for N events" / "Off" (all disabled); permission denied: "Notifications are blocked in system settings." in `error` | `NotificationPrefs` + `NotificationManagerCompat.areNotificationsEnabled()` re-read on resume (voice `rememberSystemChecks`) | S3 |
| Kernel | `ic_cline_logo` | "Kernel" *(Android)* | DS §1.6 kernel-connection dot + line 1 ("Cline Hub @25463", "Connecting...", "Reconnecting...", "Cline Hub @unknown", "Termux asleep") | `KernelCoordinator.state` | Kernel settings |
| Providers | `power` | "Providers" | desktop `ProviderListContent` subtitle: "Connect a provider to start using models." when 0 configured, else "N configured · M available". Fallback mode: "N configured" | `ProviderCatalogRepository` (cache) or `providers.json` | S4 |
| Account | `account_circle` | "Account" | signed in: "<display name or email> · <active organisation name or "Personal">" from the last `cline_account {action:"clineAccount", operation:"fetchMe"}` cached in DataStore (`account.displayName`, `account.scope`, non-secret); signed in but never fetched: "Signed in to Cline" *(Android)*; signed out: "Sign in to Cline" | `providers.json` → signed in = `providers.cline.settings.apiKey` non-blank **or** `providers.cline.settings.auth.accessToken` non-blank (the §0.4 `configured` predicate; the dashboard treats a Cline API key as account-capable, `resolveHubClineAccountAuthToken` falls back to `getPersistedProviderApiKey`) | Account (signed-out Account card starts S7) |
| Installed | `extension` | "Installed" (desktop `CUSTOMIZATION_SECTION_LABELS`) | Hub connected: counts from `settings.list {workspaceRoot, cwd}` snapshot, e.g. "12 skills · 3 MCP servers · 2 plugins" (plurals; zero kinds omitted; all zero → "Nothing installed yet" *(Android)*); kernel down: no supporting line | Hub `settings.list`; refresh on `settings.changed` | Customize |
| Marketplace | `storefront` | "Marketplace" | none | none | Marketplace |
| Schedules | `schedule` | "Schedules" | Hub connected: "N schedules · M active" (plurals) from `schedule.list` + `schedule.active`; none: "No schedules yet" *(Android)*; kernel down: no line | Hub `schedule.list`, `schedule.active`; events `schedule.*` | Schedules |
| Telemetry | `analytics` | "Telemetry" | "Enable error and usage reports to help improve Cline." | helper `get_global_settings` → `{telemetryOptOut}`; write `set_telemetry_opt_out {telemetry_opt_out}`; fallback read `cat ~/.cline/data/settings/global-settings.json` | toggles in place |
| New user experience | `restart_alt` | "New user experience" | "Replay the first-run experience new users see when they open Cline for the first time." | none | whole row → `AlertDialog` *(Android)* title "Replay the first-run experience?", text "Your settings, providers and sessions stay as they are.", buttons "Cancel" / "Replay" (desktop button label) → clears DataStore `onboarding.completed` and navigates to Kernel setup's first step (desktop `resetOnboarding`) |
| Diagnostics | `share` (DS §7.4 "Export / share") | "Diagnostics" | "Export app info, recent logs, and the metadata of sessions you choose as a file you can attach when reporting a problem." | none | whole row → the About spec's Export diagnostics dialog directly; the desktop button label "Export…" becomes the row's click label (`Modifier.clickable(onClickLabel = "Export…")`) |
| About | `info` | "About" | "Cline Termux <versionName> · kernel <coreVersion>" *(Android)*; kernel unknown: "Cline Termux <versionName>" | `BuildConfig.VERSION_NAME`; `GET /version` `coreVersion` (cached last value) | About |

Title: "Settings". Description *(Android, per DS #12)*: "Manage preferences for this app
and the Cline kernel in Termux."

Telemetry row behaviour (desktop `updateTelemetryOptOut`):

- The switch is `checked = !telemetryOptOut`. Toggling sets it optimistically, then
  (DS #12 "saving") keeps the `Switch` visible with `enabled = false` and places a 16dp
  `CircularProgressIndicator` *before* it in the trailing `Row` (8dp gap), calls
  `set_telemetry_opt_out {telemetry_opt_out}`, then adopts the returned value. On error it
  rolls back and shows, under the supporting text in `bodySmall error`
  (`LiveRegionMode.Assertive`): "Failed to update telemetry setting: <message>". The row
  is enabled again afterwards, so toggling again is the retry.
- Loading (`get_global_settings` in flight): switch `enabled = false`, no spinner.
- The helper is acquired only while S1 is visible **and** the user toggles (S1 does not
  start a Bun process just to render). To render the value S1 reads
  `global-settings.json` with `cat` once per visit; the toggle then acquires the helper,
  shows "Starting settings helper..." *(Android)* as the supporting line while it boots,
  and performs the write.
- **An acquire that fails during a toggle** (any `Unavailable` reason reached because of
  this toggle, for example port 8787 occupied → `StartTimeout`) is a write failure: the
  value rolls back with "Failed to update telemetry setting: <detail>" (detail = the
  helper's `detail`, else "Settings helper unavailable" *(Android)*) and the row stays
  enabled. Each new toggle re-runs `acquire`.
- **Known unavailable before the toggle** (fallback mode: `BuildUnsupported` or
  `KernelStopped` already recorded, §0.3): the switch shows the value read from the file
  and is disabled; supporting text becomes "Telemetry can be changed when the settings
  helper is available." *(Android)*.

### S1.3 ViewModel

```kotlin
data class SettingsHomeUiState(
    val appearanceSummary: AppearanceSummary,            // theme, dynamic, fontSize
    val notifications: NotificationsSummary,             // enabledCount, permission: Granted|Denied|NotRequired
    val kernel: KernelConnectionVisual,                  // DS §1.6 row (dot, line1)
    val providers: ProvidersSummary?,                    // configured, available, fallback; null while unknown
    val account: AccountSummary,                         // SignedIn(name, scope?) | SignedInUnknown | SignedOut | Unknown
    val installed: InstalledCounts?,                     // null when the Hub is not connected
    val schedules: ScheduleCounts?,                      // null when the Hub is not connected
    val telemetry: TelemetryRowState,                    // value: Boolean?, saving, error: String?, writable: Boolean
    val appVersion: String, val kernelVersion: String?,
    val selected: SettingsSection?,                      // expanded only
)
```

Load order on every `ON_RESUME` (voice re-reads system checks on resume): DataStore
(instant) → kernel state (already observed) → `providers.json` and `global-settings.json`
via one RUN_COMMAND each in parallel (skip if Termux is blocked) → Hub summaries if
connected. Nothing on S1 shows a full-screen loading state; each summary renders
as soon as its source answers and is simply absent until then.

### S1.4 States

| State | What the user sees |
| --- | --- |
| First run (onboarding not completed) | S1 is reachable from the drawer. Kernel row shows "Termux asleep" or the setup state; Providers "Connect a provider to start using models."; Account "Sign in to Cline". |
| Loading | Rows render immediately with headlines; summaries fade in (`fadeIn(tween(150))`) as they arrive. No skeletons. |
| Populated | As wireframe. |
| Running (a turn is active elsewhere) | No change. Settings never blocks on a running session. |
| Offline / kernel down | Kernel row shows the down state; Installed and Schedules lose their supporting line; Telemetry stays readable via `cat`. All rows remain tappable; destinations show their own down states. |
| Termux missing / permission missing | A `KernelBlockedCard` (§0.5) is item 0 above the "App" header. Providers / Account summaries are absent. |
| Error | Summary sources fail silently (row without supporting line); Telemetry write errors as above. |

### S1.5 Accessibility

- TalkBack order: title, description, then each header and its rows. Kernel row reads
  "Kernel, Cline Hub at 25463, connected" (dot is described in words, DS §8.1.3).
- New user experience and Diagnostics are one focus stop each: "New user experience,
  Replay the first-run experience …, button"; Diagnostics announces its click label
  ("double-tap to Export…").
- At font scale 2.0 × size 20, rows grow (`heightIn(min = 56.dp)`); nothing has to wrap
  beside the text because no row holds a trailing button.

### S1.6 Acceptance (S25U)

- [ ] Open Settings from the drawer footer: title "Settings", description copy exact,
      groups App / Kernel / Models / Customize / Privacy / Support in that order.
- [ ] Kernel summary matches the Kernel status sheet's line 1 and dot in every kernel state
      (stop the Hub with `cline hub stop` in Termux → "Termux asleep" or "Cline Hub @unknown"
      within one resume).
- [ ] With 0 configured providers the Providers line reads "Connect a provider to start using
      models."; after S7 or S5 it reads "1 configured · N available".
- [ ] Telemetry toggle round-trips: flip it, check `cat ~/.cline/data/settings/global-settings.json`
      in Termux shows `"telemetryOptOut": true`, flip back.
- [ ] With the helper stopped (`pkill -f "index.js dashboard .*--port 8787( |$)"` in
      Termux) the toggle shows "Starting settings helper..." and then succeeds; with port
      8787 occupied (`nc -l -p 8787` in a second Termux session) it rolls back with
      "Failed to update telemetry setting: …" and the switch stays enabled (S1.2 "acquire
      that fails during a toggle").
- [ ] Revoke the RUN_COMMAND permission in Android settings → the blocked card appears on
      resume; granting it again removes the card.
- [ ] "New user experience" → "Replay" in the dialog returns to the first-run flow;
      finishing it returns to Home. "Cancel" changes nothing.
- [ ] After S7 "Use a Cline API key" the Account row reads "Signed in to Cline" (or the
      fetched name), never "Sign in to Cline".
- [ ] Tab S7+ landscape: list pane 360dp + Appearance detail by default; tapping
      Providers replaces the Settings scaffold with one providers list + detail scaffold
      (§0.2); back returns to Settings. Tab S7+ portrait: back steps provider detail →
      providers list → Settings.
- [ ] TalkBack reads every row with its summary; no unlabeled control in Accessibility Scanner.

---

## S2. Appearance

**Purpose.** Theme (light / dark / system), dynamic colour, font size and launcher icon.
Everything is app-local DataStore and applies without restart (DS §11).

**Desktop equivalent.** `GeneralSettingsContent` rows "Dark mode", "Font size" (with
`MIN_APP_FONT_SIZE` 12, `MAX_APP_FONT_SIZE` 20, default 15, `lib/app-font-size.ts`),
"App icon" (`lib/app-icon.ts`, default "Midnight"), "Accent color". Copied: row titles
"Dark mode", "Font size", "App icon", the font-size description, the stepper button
labels and the slider, the app-icon error string. Translated: the dark-mode switch
becomes a three-way choice (DS §1.2.3); "Accent color" becomes "Dynamic colour" (DS
§1.2, §9); the app-icon picker offers Midnight and Classic as launcher aliases (DS §10.6).

### S2.1 Layout

Compact:

```
┌──────────────────────────────────────────┐
│ ←  Appearance                            │
│ Choose how Cline looks on this device.   │
│                                          │
│ Dark mode                                │  PrefHeader
│ (•) System default                       │  RadioButton rows, Role.RadioButton,
│ ( ) Light                                │  selectableGroup()
│ ( ) Dark                                 │
│ Choose light or dark, or follow the      │  bodyMedium onSurfaceVariant, 16dp inset
│ system setting.                          │
│                                          │
│ Dynamic colour                    [●══]  │  PrefSwitch (API 31+ only)
│ Use colours from your wallpaper. Turn    │
│ off to use Cline's violet.               │
│                                          │
│ Font size                                │  PrefHeader
│ Adjust the size of text and interface    │
│ elements throughout the app.             │
│  (−)  ━━━━━━━●━━━━━━━━━━━━━  (+)   15    │  IconButton · Slider(12..20, steps 7) · IconButton · value
│ ┌──────────────────────────────────────┐ │  Preview card (surfaceContainerLow, shape large)
│ │                 Fix the failing test │ │   user bubble (DS #5)
│ │ Ran command `bun test`               │ │   tool row label (DS #2, static)
│ │ The test now passes. 测试已通过。      │ │   assistant markdown body
│ └──────────────────────────────────────┘ │
│                                          │
│ App icon                                 │  PrefHeader
│ Pick the icon Cline shows on your home   │
│ screen.                                  │
│   ┌────┐      ┌────┐                     │  64dp adaptive-icon previews, 12dp gap
│   │ ◉  │      │ ◉  │                     │  selected: 2dp primary ring, 2dp offset
│   └────┘      └────┘                     │
│  Midnight     Classic                    │  labelMedium; selected SemiBold onSurface
└──────────────────────────────────────────┘
```

Medium and expanded: same content in a 720dp max column; on expanded it is the detail
pane of S1. The font-size row keeps the slider at a fixed 320dp width with the value
label trailing (desktop `w-64`).

### S2.2 Controls, copy, bindings

| Control | Copy | Binding | Behaviour |
| --- | --- | --- | --- |
| Page title / description | "Appearance" *(Android)* / "Choose how Cline looks on this device." *(Android)* | | |
| Dark mode (header) | "Dark mode" | DataStore `appearance.theme` = `system`(default) / `light` / `dark` | Options "System default", "Light", "Dark" *(Android labels, DS §1.2.3)*. Applies instantly: `ClineTheme` recomposes; status-bar icon colour follows (DS §1.2.5). Helper text *(Android)*: "Choose light or dark, or follow the system setting." |
| Dynamic colour | "Dynamic colour" *(Android)*; supporting "Use colours from your wallpaper. Turn off to use Cline's violet." *(Android)* | `appearance.dynamicColor` (Boolean, default true) | Hidden entirely below API 31 (DS §1.2.2: brand scheme only). Turning off switches to `ClineLightScheme`/`ClineDarkScheme`. Never calls `dynamicLight/DarkColorScheme` (DS §1.2.1). |
| Font size | "Font size"; "Adjust the size of text and interface elements throughout the app." | `appearance.fontSize` Int 12–20, default 15 (DS §2.4) | `IconButton` `remove` CD "Decrease font size" (disabled at 12), `Slider` CD "Font size" with `stateDescription` "%d" (the number only), `IconButton` `add` CD "Increase font size" (disabled at 20), value `Text` "15" in `ClineMono.small` with tabular figures (`fontFeatureSettings = "tnum"`), fixed 40dp width, end-aligned, CD "Selected font size" (desktop `<output>` in tabular mono). Deliberate deviation: the desktop shows "15px" and its slider's `aria-valuetext` says pixels; on Android the value is a multiplier (n/15 on top of the system font scale, DS §2.4), not a pixel size, so the unit is dropped from both the label and TalkBack. Every change rebuilds `ClineTypography`/`ClineMono` with factor n/15 (never `LocalDensity`). Slider snaps to integers; the preview updates live on every `onValueChange`, and the value is persisted on `onValueChangeFinished` (on release; the stepper buttons persist on each tap). The desktop persists on every tick; persisting on release avoids a DataStore write per drag frame and is otherwise identical. |
| Preview *(Android)* | user bubble "Fix the failing test", tool label "Ran command `bun test`", assistant "The test now passes. 测试已通过。" *(Android sample strings; the Chinese line checks CJK weight per DS §2.3)* | none | Static composables from the component inventory, `clearAndSetSemantics { contentDescription = "Preview" }` *(Android)* so TalkBack does not read the fake transcript as content. |
| App icon | "App icon"; "Pick the icon Cline shows on your home screen." *(Android adaptation of "Pick the icon Cline shows in the {Dock\|Taskbar\|desktop}.")* | `appearance.appIcon` = `midnight`(default) / `classic`; `PackageManager.setComponentEnabledSetting` on `.LauncherMidnight` / `.LauncherClassic` (DS §10.6) | Tiles are `selectable(role = Role.RadioButton)` in a `selectableGroup`, CD = label ("Midnight", "Classic"). Both aliases target `MainActivity` with identical `MAIN`/`LAUNCHER` intent filters, so a cold start through either works. On switch: **enable the new alias at once** (`COMPONENT_ENABLED_STATE_ENABLED`, `DONT_KILL_APP`) so the launcher shows the new icon, store the choice in `appearance.appIcon` and the alias to retire in `appearance.pendingIconDisable`, and snackbar "The launcher may take a moment to update the icon." *(Android)*. The old alias is **not** disabled while the task is live: the current task's base activity is that alias, and disabling it under a live task makes One UI and stock launchers drop the task from Recents or finish it when the user leaves. The disable runs (a) in `Application.onCreate` of the next cold start, or (b) in `ProcessLifecycleOwner` `ON_STOP` if the task's base intent component is not the alias being disabled; whichever comes first. Always disable every alias except the one in `appearance.appIcon`, so after two quick switches only the later choice stays enabled (desktop `appIconRequestRef`: the later request wins). On `SecurityException` / failure: roll back the selection and show under the tiles `bodySmall error` "Failed to change app icon: <message>". |

### S2.3 States

| State | Behaviour |
| --- | --- |
| First run | Defaults: System default, Dynamic colour on (API 31+), 15, Midnight. |
| Loading | DataStore values are read before first frame by `ClineTheme`; this screen has no loading UI. |
| Error | Only the app-icon failure above. |
| Offline / kernel down / running | Irrelevant; the screen is fully functional. |
| Permission missing | None needed. |

### S2.4 Accessibility

- Radio rows: one target each, `Role.RadioButton`, `selected` semantics; the group is a
  `selectableGroup()` so TalkBack reads "1 of 3".
- The slider and both stepper buttons are separate focus stops; hardware-keyboard arrows
  move the slider by 1.
- The preview is hidden from TalkBack except its single label.
- At font size 20 and system scale 2.0 the row must still fit the value label: the
  slider row wraps to two lines (buttons + value on the first, slider on the second)
  below 360dp of available width.

### S2.5 Acceptance (S25U)

- [ ] Each Dark mode option changes the whole app instantly, including the status-bar icon
      colour; "System default" follows the One UI quick-panel dark toggle without restart.
- [ ] With Dynamic colour on, change the wallpaper: primary follows it; `ClineColors`
      success/warning/info stay fixed (DS §1.2.4). Off: violet `#6249C2` (light) /
      `#CBBEFF` (dark) primary.
- [ ] Font size 12 → 20 rescales the preview and, after going back, the transcript, drawer
      and composer; buttons disable at the ends; value persists across process death
      (`adb shell am kill io.github.ichouchiang.clinetermux`).
- [ ] Switching to Classic changes the launcher icon within a few seconds and shows the
      snackbar; survives a reboot; back to Midnight works.
- [ ] Switch the icon, press Home, reopen Cline from Recents: the task is still there with
      its back stack. Switch twice in a row (Classic, then Midnight) and cold-start: only
      Midnight is on the launcher.
- [ ] TalkBack: "Font size, 15, slider"; "Decrease font size, button"; the value label
      reads "Selected font size, 15".

---

## S3. Notifications

**Purpose.** Choose which session events notify, with or without sound, and fix the
Android permission. The same preferences drive `ClineNotifier`, which posts the
notifications (the "System notifications" screen spec owns their layout; the rules
below are what these toggles control).

**Desktop equivalent.** `NotificationSettings` (`notification-settings.tsx`) and
`lib/desktop-notifications.ts`. Copied: the four event types with their labels and
descriptions, the Notify/Sound choices, the defaults (all enabled, sound off), the
permission control states ("Allowed by system", "Allow notifications", "Check
permission", "Checking…"), "Notifications are blocked in system settings.", the
notification titles/bodies, the "only while in the background" rule, dedupe by request
id, suppression of task-completed while prompts are queued, no notification on cancel.
Translated: browser/Tauri permission → `POST_NOTIFICATIONS` (API 33+) and per-channel
blocking; the sound flag → a choice between two notification channels per event
(sounding and quiet, S3.3); the Event / Notify / Sound table becomes grouped list rows
on every size class.

### S3.1 Layout

Compact:

```
┌──────────────────────────────────────────┐
│ ←  Notifications                         │
│ Notify only while Cline is in the        │  description
│ background. Tapping a notification opens │
│ its session.                             │
│ ┌──────────────────────────────────────┐ │  permission card (only when not granted)
│ │ ⚠ Notifications are blocked in system│ │
│ │   settings.          [Check permission]│
│ └──────────────────────────────────────┘ │
│ Events                                   │  PrefHeader
│ Task completed                    [●══]  │  PrefSwitch = Notify
│ When Cline finishes a task or turn.      │
│     Sound                         [══○]  │  nested PrefSwitch, 56dp start inset,
│                                          │  enabled only when Notify is on
│ Approval needed                   [●══]  │
│ When a tool is waiting for your approval.│
│     Sound                         [══○]  │
│ Question asked                    [●══]  │
│ When Cline needs an answer before        │
│ continuing.                              │
│     Sound                         [══○]  │
│ Session error                     [●══]  │
│ When a task stops because of an error.   │
│     Sound                         [══○]  │
│ Delivery                                 │  PrefHeader (Android)
│ Background connection                  › │  → Kernel settings ("Keep connected")
│ Cline can notify you only while it stays │
│ connected to the kernel.                 │
│ System notification settings          ↗ │  → ACTION_APP_NOTIFICATION_SETTINGS
│ Channels, sounds and Do Not Disturb      │
└──────────────────────────────────────────┘
```

Medium / expanded: the same grouped list inside the 720dp content column (DS §3.3), on
expanded as the S1 detail pane. The desktop's three-column switch table with column
headers is desktop chrome with no M3 settings equivalent; one row anatomy keeps TalkBack
order and touch targets identical on every device. The permission card is the same as on
compact.

### S3.2 Copy and bindings

| Element | Copy | Source |
| --- | --- | --- |
| Title | "Notifications" | desktop "Desktop notifications" without "Desktop" *(Android)* |
| Description | "Notify only while Cline is in the background. Tapping a notification opens its session." | desktop "Notify only while the Cline window is in the background. Clicking a notification opens its session." adapted *(Android)* |
| Permission: granted | no card; the desktop's "Allowed by system" moves into the System notification settings row's supporting text (below) | desktop |
| Permission: checking | "Checking…" | desktop |
| Permission: not yet asked | card body "Cline needs permission to show notifications." *(Android)* + `OutlinedButton` "Allow notifications" | desktop button |
| Permission: denied | card body "Notifications are blocked in system settings." (`error`) + `OutlinedButton` "Check permission" | desktop |
| Event: taskCompletion | "Task completed" / "When Cline finishes a task or turn." | desktop `EVENT_COPY` |
| Event: approvalNeeded | "Approval needed" / "When a tool is waiting for your approval." | desktop |
| Event: questionAsked | "Question asked" / "When Cline needs an answer before continuing." | desktop |
| Event: sessionError | "Session error" / "When a task stops because of an error." | desktop |
| Sound sub-row | "Sound" | desktop column header |
| Switch CDs | "<label> notifications", "<label> sound" (e.g. "Task completed notifications") | desktop `aria-label` |
| Channel blocked (per event) | supporting line replaced by "Blocked in system settings" *(Android)* in `error`, trailing `TextButton` "Open" *(Android)* | the event's **active** channel (sounding or `_quiet`, per its Sound toggle, S3.3) has `NotificationManagerCompat.getNotificationChannelCompat(id)?.importance == IMPORTANCE_NONE`; re-evaluated when Sound flips and on every `ON_RESUME` |
| Background connection row | "Background connection" *(Android)* / "Cline can notify you only while it stays connected to the kernel." *(Android)* | → Kernel settings ("Keep connected" switch, Kernel spec) |
| System row | "System notification settings" *(Android)* / "Channels, sounds and Do Not Disturb" *(Android)*; when the permission is granted the supporting text is "Allowed by system. Channels, sounds and Do Not Disturb" (desktop string + Android); trailing `open_in_new` | `Settings.ACTION_APP_NOTIFICATION_SETTINGS` + `EXTRA_APP_PACKAGE` (voice `MainActivity`) |

Persistence: DataStore `notifications.v1` = JSON with the desktop shape
`{taskCompletion:{enabled,sound}, approvalNeeded:{…}, questionAsked:{…}, sessionError:{…}}`,
defaults `{enabled:true, sound:false}` for all four, parsed with the desktop's
`readPreference` fallback rules (non-boolean fields fall back to defaults).

Permission flow (desktop `updatePreference` asks when a toggle is turned on without
permission):

1. API < 33: `NotRequired`; only `areNotificationsEnabled()` matters (denied → card with
   "Check permission").
2. API ≥ 33, never asked: "Allow notifications" → `ActivityResultContracts.RequestPermission(POST_NOTIFICATIONS)`.
   Turning any Notify switch on also triggers it once.
3. Denied with `shouldShowRequestPermissionRationale == false` (permanent) or
   app-level notifications off: "Check permission" → `ACTION_APP_NOTIFICATION_SETTINGS`.
   The state is re-read on every `ON_RESUME`.

### S3.3 What the toggles govern (contract for `ClineNotifier`)

Channels (created once in `Application.onCreate`, group "Sessions" *(Android)*). An app
cannot change a channel's sound after creating it, and `NotificationCompat.Builder
.setSilent(true)` makes a notification non-alerting (no sound, no vibration **and no
heads-up peek**; it lands in the shade's Silent section), so the **Sound** toggle is a
choice between two channels per event, never a builder flag. `setSilent` is not used.

| Event | Sound on → channel id | Sound off → channel id | Importance (both) | Channel names *(Android)* |
| --- | --- | --- | --- | --- |
| taskCompletion | `task_completed` (default sound, vibration on) | `task_completed_quiet` (`setSound(null, null)`, `enableVibration(false)`) | `IMPORTANCE_DEFAULT` | "Task completed" / "Task completed (silent)" |
| approvalNeeded | `approval_needed` (default sound, vibration on) | `approval_needed_quiet` (`setSound(null, null)`, `enableVibration(true)`) | `IMPORTANCE_HIGH` | "Approval needed" / "Approval needed (no sound)" |
| questionAsked | `question_asked` (default sound, vibration on) | `question_asked_quiet` (`setSound(null, null)`, `enableVibration(true)`) | `IMPORTANCE_HIGH` | "Question asked" / "Question asked (no sound)" |
| sessionError | `session_error` (default sound, vibration on) | `session_error_quiet` (`setSound(null, null)`, `enableVibration(false)`) | `IMPORTANCE_DEFAULT` | "Session error" / "Session error (silent)" |

A soundless `IMPORTANCE_HIGH` channel that vibrates still alerts, so approval and
question notifications peek as heads-up with the shipped defaults (Sound off). The task
and error quiet channels stay `IMPORTANCE_DEFAULT` without sound or vibration: they
appear in the status bar and shade without interrupting, as the desktop's soundless
notification does. The foreground-service channel ("Cline is working...") is owned by
the Kernel spec.

All four bodies go through the desktop's `concise(body)` (`lib/desktop-notifications.ts`
`notify()`): whitespace runs collapsed to one space, trimmed; longer than **180 characters** →
the first 179 characters + "…" (180 in total). This applies to every row below, including the question text
and the approval body.

| Event | Trigger (FM corrections: not `ui.notify`) | Title / body (desktop copy) | Suppressed when |
| --- | --- | --- | --- |
| taskCompletion | `run.completed` with `reason == "completed"` | "Task completed" / "Cline finished working and the result is ready." | app in foreground (`ProcessLifecycleOwner` ≥ STARTED); session has queued prompts (`session.pending_prompts` count > 0); same terminal kind already notified for this session since its last `run.started` |
| sessionError | `run.failed` | "Task failed" / `error` text or "Cline encountered an error while running this task." | foreground; already notified |
| approvalNeeded | `approval.requested` (dedupe by `approvalId`, last 500, re-issued on subscribe) | "Approval needed" / "<toolName> is waiting for your approval." (toolName fallback "A tool") | foreground; seen id. Cancelled when `approval.resolved` arrives |
| questionAsked | `capability.requested` with `capabilityName == "tool_executor.askQuestion"` (dedupe by `requestId`) | "Cline has a question" / the question text or "Open this task to answer Cline's question." | foreground; seen id. Cancelled when answered or cancelled |
| (none) | `run.aborted` | no notification (desktop: cancelled never notifies) | |

Tap → `PendingIntent` to `MainActivity` with deep link `cline://chat/<sessionId>`.
Notifications are grouped per session (`setGroup("session-<id>")`). Posting needs the
socket alive while backgrounded (KC keep-alive); otherwise events are delivered by
replay on resume and the notifier posts nothing for events older than 2 minutes.

### S3.4 ViewModel

```kotlin
data class NotificationSettingsUiState(
    val permission: NotificationPermission,          // Checking | Granted | NotAsked | Denied | NotRequired
    val requesting: Boolean,                         // "Allow notifications" disabled while true
    val events: List<EventPrefUi>,                    // order: taskCompletion, approvalNeeded, questionAsked, sessionError
)
data class EventPrefUi(val type: EventType, val enabled: Boolean, val sound: Boolean, val channelBlocked: Boolean)
```

### S3.5 States

| State | Behaviour |
| --- | --- |
| First run | All four on, sound off; permission card "Allow notifications" on API 33+. |
| Loading | "Checking…" for the permission until the first read (one frame in practice). |
| Permission missing | Card as above; switches stay operable (they record intent); turning one on requests permission. |
| Channel blocked | That event's row shows "Blocked in system settings" + "Open" (`ACTION_CHANNEL_NOTIFICATION_SETTINGS` with `EXTRA_CHANNEL_ID` = the active channel). Its switches stay as stored. Turning Sound on or off switches the active channel, so the row re-checks the other one (a user may have blocked only the sounding channel). |
| Offline / kernel down / running | Settings fully usable; nothing is posted while disconnected. |

### S3.6 Accessibility

- Notify rows are `PrefSwitch` targets; the Sound sub-row's CD is "<label> sound" so it is
  unambiguous out of context; when Notify is off the Sound row is disabled and TalkBack
  says "dimmed".
- The permission card is a polite live region when its state changes on resume.

### S3.7 Acceptance (S25U)

- [ ] Fresh install on Android 16: card shows "Allow notifications"; tapping it shows the
      system dialog; Allow → card disappears.
- [ ] Deny twice → "Notifications are blocked in system settings." + "Check permission" opens
      the app's notification page; enabling there and returning removes the card.
- [ ] With the app in the background and "Keep connected" on, a finished turn posts "Task
      completed" silently; with Sound on it plays the default sound.
- [ ] With the shipped defaults (Approval needed Sound **off**), an approval request in the
      background posts a heads-up that peeks over the current app and vibrates without
      sound: "Approval needed — run_commands is waiting for your approval."; approving it
      from another client removes it. With Sound on it also plays the default sound.
- [ ] `adb shell dumpsys notification --noredact | grep -A3 io.github.ichouchiang.clinetermux` shows the
      approval posted on `approval_needed_quiet` with Sound off and `approval_needed` with
      Sound on; no notification carries the silent flag.
- [ ] A 400-character question body is shown as 179 characters + "…".
- [ ] With Task completed off, nothing posts for a finished turn; Session error still posts
      for a failed one.
- [ ] Block the "Question asked" channel in system settings → that row shows "Blocked in
      system settings" on return.
- [ ] Tab S7+: the same grouped list in the 720dp column (no table).

---
## S4. Providers list

**Purpose.** Show every model provider the kernel knows, which ones are configured, and
lead to each provider's detail or to adding a custom OpenAI-compatible provider.

**Desktop equivalent.** `ProviderListContent` (`provider-list-view.tsx`) inside the
Settings → Providers section (`settings-view.tsx` `providerContent`). Copied: heading
"Model Providers", the subtitle rules, "Add provider", the search field ("Search
providers", aria "Search model providers", clear "Clear provider search"), the three
collapsible sections "Configured" / "Popular" / "All providers" with the Popular
description "Sign in or add an API key to connect.", row anatomy (name, "Configured" or
auth-kind hint "Sign in" / "Local CLI" / "API key", chevron), "No providers match
"<q>".", "Loading providers...", "Failed to load providers: <msg>", search resetting
collapsed sections, the catalog cache/generation rules (§0.7), and the "detail is always
open, default to the first connected provider" rule for wide layouts. Translated: the
header button becomes the M3 `ExtendedFloatingActionButton` (DS §5.4 "Providers: Add
provider"); the grid split becomes list-detail panes.

### S4.1 Layout

Compact:

```
┌──────────────────────────────────────────┐
│ ←  Model Providers                       │  MediumTopAppBar
│ 2 configured · 41 available              │  subtitle (first list item)
│ ┌──────────────────────────────────────┐ │
│ │ 🔍 Search providers               ✕ │ │  OutlinedTextField, leading search, trailing clear
│ └──────────────────────────────────────┘ │
│ ▾ Configured                             │  section header button (titleSmall, onSurfaceVariant)
│ Cline                       Configured › │
│ OpenRouter                  Configured › │
│ ▾ Popular                                │
│ Anthropic                  ⚿ API key   › │  auth hint: 16dp icon + labelMedium
│ OpenAI                     ⚿ API key   › │
│ OpenAI Codex               🌐 Sign in  › │
│ ▸ All providers                          │  collapsed
│                                          │
│                       ┌────────────────┐ │
│                       │ + Add provider │ │  ExtendedFAB (`add`), collapses to icon on scroll
│                       └────────────────┘ │
└──────────────────────────────────────────┘
```

Section headers are `Row(clickable, role = Button)` with a leading `expand_more`
rotated as DS #4 does (−90°, pointing to the end, when collapsed; 0° when expanded; DS §6
chevron motion; the wireframe's ▾/▸ show the two states), label `titleSmall onSurfaceVariant` in sentence case
("Configured", "Popular", "All providers"; the desktop's CSS uppercases them, the app
does not, because uppercase hurts CJK and TalkBack).

The list is one `LazyColumn` with `contentPadding = PaddingValues(bottom = 88.dp +
WindowInsets.navigationBars bottom)` (56dp FAB + 16dp margin + 16dp air), so the last
"All providers" row can scroll clear of the FAB and stay tappable. The same rule applies
to every screen in this spec that shows a FAB.

Medium (single pane, 720dp column) is the compact layout with 24dp gutters and the FAB
in the content (the rail's FAB is "New session", DS §5.4; the Providers FAB stays because
it is a different action).

Expanded (Providers owns the full detail width, §0.2):

```
┌────┬──────────────────────────────┬────────────────────────────────────────────┐
│rail│ ← Model Providers [+ Add provider]│ Cline                     Configured  │
│    │ 2 configured · 41 available  │                         [ Sign out ]        │
│    │ 🔍 Search providers          │ Signed in via browser                       │
│    │ ▾ Configured                 │ This provider authenticates with your ...   │
│    │▌Cline           Configured › │ ┌ Models ⟳ ───────────────────────────────┐ │
│    │ OpenRouter      Configured › │ │ 🔍 Search models by name or ID          │ │
│    │ ▾ Popular                    │ │ Claude Sonnet 5  RECOMMENDED  🖼 🧠   ☆ │ │
│    │ ...                          │ │ anthropic/claude-sonnet-5 ⧉            │ │
└────┴──────────────────────────────┴────────────────────────────────────────────┘
```

On expanded the "Add provider" action is a `FilledTonalButton` in the list pane's top
bar (no FAB in a pane, DS §5.4 spirit); the detail pane always shows a provider:
`selectedId ?: firstConnected?.id ?: providers.first().id` (desktop
`effectiveSelectedProviderId`). The selected row uses `secondaryContainer`.

### S4.2 Copy

| Element | Copy |
| --- | --- |
| App bar title | "Model Providers" |
| Subtitle | 0 configured: "Connect a provider to start using models."; else "<n> configured · <m> available" (plurals not needed: numbers only, desktop format) |
| Search placeholder / CD / clear CD | "Search providers" / "Search model providers" / "Clear provider search" |
| Sections | "Configured", "Popular", "All providers" |
| Popular description (only when nothing is configured and no query) | "Sign in or add an API key to connect." |
| Row status | "Configured" (`labelMedium onSurfaceVariant`) or auth hint: `language` + "Sign in" (oauth), `devices` + "Local CLI" (local-auth), `key` + "API key" |
| No match | "No providers match \"<query>\"." |
| Loading | "Loading providers..." (centred, `bodyMedium onSurfaceVariant`, plus `LinearProgressIndicator` under the app bar) |
| Helper starting | `LinearProgressIndicator` + "Starting settings helper..." *(Android)* in place of the loading text |
| Error | "Failed to load providers: <message>" (centred, `error`) + `TextButton` "Retry" *(Android)* |
| FAB | "Add provider" (`add`, DS §5.4), CD "Add provider" |
| Section header semantics | visible text = section name; `contentDescription` = "<section>, <n> providers" *(Android, plurals resource: "1 provider" / "<n> providers")*; `stateDescription` = "Expanded" / "Collapsed" (DS §8.1.5); `role = Role.Button`; `heading()` |

### S4.3 Data and ViewModel

On enter: `SettingsHelper.acquire("providers")`; `ProviderCatalogRepository.load()`
(cache ≤ 60 s → instant; else `desktopCommand list_provider_catalog` →
`{providers: ProviderListItem[], settingsPath}`). Fallback mode builds the same list from
`fallback_providers.json` + `providers.json` (§0.4). On leave: `release("providers")`.

Classification (desktop, verbatim):

- `isProviderConnected(p) = p.apiKey?.isNotBlank() == true || p.oauthAccessTokenPresent == true || (p.enabled && p.configured == true)`
- `getProviderAuthKind(p) = "oauth"` if `"oauth" in p.capabilities || p.id in {cline, cline-pass, oca, openai-codex}`; `"local"` if `"local-auth" in capabilities`; else `"api-key"`.
- Filter by query on `name` or `id` (case-insensitive). Configured = filtered ∩ connected;
  Popular = not connected with capability `popular`; All providers = the rest. Catalog
  order is preserved (it arrives sorted by popular rank, then name).
- Typing in search clears the collapsed set (desktop).

```kotlin
data class ProvidersListUiState(
    val helper: HelperState,
    val fallback: Boolean,
    val loading: Boolean,
    val error: String?,
    val query: String,
    val collapsed: Set<ProviderSection>,                 // rememberSaveable via VM SavedStateHandle (no secrets)
    val configured: List<ProviderRowUi>, val popular: List<ProviderRowUi>, val others: List<ProviderRowUi>,
    val configuredCount: Int, val totalCount: Int,
    val selectedId: String?,                             // expanded only
    val blocked: KernelBlock?,                           // §0.5
)
data class ProviderRowUi(val id: String, val name: String, val connected: Boolean, val authKind: AuthKind)
```

### S4.4 States

| State | Behaviour |
| --- | --- |
| First run (nothing configured) | Subtitle "Connect a provider to start using models."; no Configured section; Popular shows its description; Cline sits in Popular with "Sign in". |
| Loading | As copy; search field disabled; FAB hidden until loaded. Deliberate deviation: the desktop replaces the whole Providers page with "Loading providers..." (`settings-view.tsx` `providerContent`, no title, subtitle or search); Android keeps the app bar (and its back arrow) for predictable back, and keeps the subtitle and a disabled search field so the layout does not jump when the list arrives. |
| Populated | As wireframe. |
| Empty search | Only the "No providers match …" row (bordered top/bottom, `bodyLarge onSurfaceVariant`). |
| Error | Error text + Retry. Retry clears the cache and reloads. |
| Helper unavailable | Unavailable card (§0.3) above the list; list rendered from fallback (§0.4); FAB opens the built-in `openai-compatible` detail (S5) instead of S6. |
| Kernel down (Hub) | The helper's acquire runs the KernelCoordinator connect first (§0.3 step 1.2); the list shows "Starting settings helper..." for up to 45 s. If the user stopped the kernel in this app session: the KernelStopped card (§0.3) + fallback list. |
| Termux missing / permission missing | `KernelBlockedCard` replaces the list. |
| Running | No effect. |

### S4.5 Interactions

- Tap row → S5 (push on compact/medium; select in the detail pane on expanded).
- Tap section header → toggle collapsed (animate with DS §6 disclosure motion).
- FAB → S6 (or the fallback detail, above).
- Pull-to-refresh *(Android)* (`PullToRefreshBox`) → forced reload (cache cleared). The
  desktop reloads on section entry only; pull-to-refresh is the touch equivalent of
  re-entering.
- Back: compact/medium: provider detail → providers list (pane back) → S1 (NavHost pop).
  Expanded: list and detail are both visible, so back from either pops the
  `settings/providers` route to `settings` (NavHost pop, seekable). One scaffold, one
  navigator (§0.2).

### S4.6 Accessibility

- Rows: `contentDescription` "<name>, Configured" or "<name>, API key" etc.; chevron is
  decorative.
- Section headers are buttons with `heading()`; TalkBack reads "Popular, 6 providers,
  Expanded, button, heading" (S4.2 semantics, no repeated name); collapsing moves no
  focus.
- The FAB's label stays in semantics when collapsed to icon-only.

### S4.7 Acceptance (S25U)

- [ ] Open Providers with the Hub stopped by `cline hub stop` in Termux (not by the app's
      "Stop kernel"): the kernel dot shows "Connecting..." while the coordinator starts
      the Hub, then "Starting settings helper...", then the list within **45 s** (with the
      Hub already running: within 20 s). Afterwards the Kernel status sheet shows a
      connected Hub with a valid token, and `termux-wake-lock` is held (Termux
      notification reads "wake lock held").
- [ ] Press "Stop kernel" in Kernel settings, then open Providers: no Bun process starts
      (`pgrep -f "index.js (hub|dashboard)"` stays empty), the "Kernel is stopped" card
      shows with "Start kernel", and the fallback list renders.
- [ ] Configured section lists exactly the providers whose keys/tokens exist in
      `providers.json` (compare with `cat ~/.cline/data/settings/providers.json` in
      Termux: keys of `providers` whose `settings.apiKey` or `settings.auth.accessToken`
      is non-empty).
- [ ] While the helper runs, `curl -s -H 'Origin: http://127.0.0.1:8787' http://127.0.0.1:8787/health`
      from Termux answers `{"error":"unauthorized_browser"}` (403): the room secret is
      enforced. `ps -ef | grep "index.js dashboard"` shows `--room-secret` with 32 hex
      characters.
- [ ] Start `cline dashboard --no-open --host 127.0.0.1 --port 8787` by hand in Termux,
      then open Providers: the "Another Cline dashboard is already running…" card shows,
      the fallback list renders, and the hand-started dashboard keeps running.
- [ ] Search "open" filters to OpenAI / OpenRouter / OpenAI Codex / OpenAI Compatible;
      collapsed sections re-expand; "zzz" shows `No providers match "zzz".`.
- [ ] Leave Providers and wait 10 s: `pgrep -f "index.js dashboard .*--port 8787( |$)"` in
      Termux returns nothing; the Hub on 25463 is still up.
- [ ] Force the helper to fail (`chmod -x` is not allowed; instead start with port 8787
      occupied by `nc -l -p 8787` in Termux): unavailable card appears, fallback list
      renders, Retry recovers after freeing the port.
- [ ] Large lists: with all sections expanded, fling the list end to end; the last row
      clears the FAB and opens on tap.
- [ ] Tab S7+ landscape: list + detail side by side, detail defaults to the first configured
      provider.

---

## S5. Provider detail

**Purpose.** Connect, configure, inspect and disconnect one provider: credentials or
browser sign-in, provider-specific fields, and its model list with capabilities and
favourites.

**Desktop equivalent.** `ProviderDetailContent` + `ConfigFieldRow`
(`provider-list-view.tsx`), the persist/connect/disconnect/OAuth handlers in
`settings-view.tsx`. Copied: title + "Configured"/"Not configured" pill, "Changes to the
fields below are saved automatically.", "Disconnect" / "Sign out", the three connection
sections by auth kind with all their copy, field rows with description and "Get a
<Name> API key" link (curated `PROVIDER_API_KEY_URLS`), show/hide and copy for secrets,
select fields with "Not set", boolean fields, the Models card (title, refresh, search
"Search models by name or ID", featured badges, capability icons, description, copy
model id with "Copied", favourite star, favourites sorted first, "No models match …",
"Loading models...", the empty text), the save-failure resync. Translated: inline
auto-save on blur → save on focus loss or IME Done; `window.alert` → snackbar; device-code
display → the Termux sign-in flow (S7), because the dashboard's OAuth opens the browser
with `xdg-open` from a background process, which Android blocks (OQ 5).

**Omitted:** the Models card "Add model" (+) button. The desktop calls
`update_provider_models`, which the dashboard does not dispatch
(`apps/cline-hub/src/server/desktop-commands.ts` has no such command). Free-text model ids
are still possible in S8. See open questions.

### S5.1 Layout

Compact, api-key provider, configured:

```
┌──────────────────────────────────────────┐
│ ←  Anthropic                 [Disconnect]│  MediumTopAppBar; action TextButton(error content)
│ (Configured)                             │  status pill: Surface(shape=full, 1dp outline), labelMedium
│ Changes to the fields below are saved    │  bodySmall onSurfaceVariant
│ automatically.                           │
│                                          │
│ API Key                                  │  field label titleMedium
│ Your Anthropic API key.                  │  field description bodyMedium onSurfaceVariant
│ Get a Anthropic API key ↗                │  TextButton-styled link, primary, open_in_new
│ ┌──────────────────────────────────────┐ │
│ │ ••••••••••••••••••••     👁   ⧉   ◌  │ │  OutlinedTextField; trailing show/hide, copy, save spinner
│ └──────────────────────────────────────┘ │
│ Base URL                                 │
│ ┌──────────────────────────────────────┐ │
│ │ 🔗 https://api.anthropic.com         │ │
│ └──────────────────────────────────────┘ │
│                                          │
│ ┌ Models  ⟳ ───────────────────────────┐ │  card look (see "Scrolling" below): header 48dp on surfaceContainer, rows on surfaceContainerLow
│ │ 🔍 Search models by name or ID       │ │
│ │ ──────────────────────────────────── │ │
│ │ Claude Sonnet 4.6   🖼 🧠          ☆ │ │  name bodyLarge; capability icons 16dp
│ │ Fast, capable model for coding.      │ │  description bodySmall
│ │ claude-sonnet-4-6 ⧉                  │ │  id ClineMono.small + copy
│ │ ──────────────────────────────────── │ │
│ │ Claude Opus 4.6     🖼 🧠          ★ │ │
│ └──────────────────────────────────────┘ │
└──────────────────────────────────────────┘
```

Compact, OAuth provider (Cline), not signed in:

```
┌──────────────────────────────────────────┐
│ ←  Cline                                 │
│ (Not configured)                         │
│ ┌──────────────────────────────────────┐ │  OutlinedCard
│ │ Sign in to Cline                     │ │  titleSmall SemiBold
│ │ Connects through your browser. No API│ │  bodyMedium onSurfaceVariant
│ │ key needed.                          │ │
│ │ [ Sign in with browser ]             │ │  Button (filled)
│ │ Use an API key instead ⌄             │ │  TextButton, expands the apiKey field row
│ └──────────────────────────────────────┘ │
│ ┌ Models ⟳ ...                         ┐ │
└──────────────────────────────────────────┘
```

While S7 is waiting the card shows: Button → disabled with 16dp spinner and label
"Waiting for browser..."; below it `bodyMedium`: "Finish signing in in Termux and your
browser, then come back here." *(Android)* and `TextButton`s "Open Termux" *(Android)* and
"Cancel".

Signed in (OAuth): `OutlinedCard` "Signed in via browser" / "This provider authenticates
with your account — no API key needed."; top bar action "Sign out".

Compact, OAuth provider **connected with an API key** (desktop `connectionSection`, middle
branch; reached by S7 "Use a Cline API key", by the "Use an API key instead" field, or by
fallback `cline auth --provider cline --apikey`):

```
┌──────────────────────────────────────────┐
│ ←  Cline                     [Disconnect]│  top-bar action "Disconnect" (not "Sign out")
│ (Configured)                             │
│ Configured with an API key.              │  bodyMedium onSurfaceVariant
│ API Key                                  │  the apiKey ConfigFieldRow (S5.2): label,
│ <field.description>                      │  description, "Get a Cline API key" link,
│ Get a Cline API key ↗                    │  field with show/hide + copy + save spinner,
│ ┌──────────────────────────────────────┐ │  auto-save on focus loss / IME Done
│ │ ••••••••••••••••••••     👁   ⧉   ◌  │ │
│ └──────────────────────────────────────┘ │
│ ┌ Models ⟳ ...                         ┐ │
└──────────────────────────────────────────┘
```

Condition (desktop, verbatim): `authKind == oauth && !oauthSignedIn && connected &&
apiKeyValue.isNotBlank()`, where `oauthSignedIn = provider.oauthAccessTokenPresent` and
`apiKeyValue` = the in-memory catalog's `apiKey` (or, in fallback mode, the
`providers.json` read: `settings.apiKey` non-blank; the field then shows the fallback
Save button of S5.2 instead of auto-save, and the value is never prefilled: the field is
empty with placeholder "Saved in Termux" *(Android)*). The three OAuth variants in order of
precedence: signed in via browser → configured with an API key → "Sign in to <name>" card.
In fallback mode the browser-vs-key distinction comes from `providers.json`
`providers.<id>.tokenSource` (`"oauth"` = browser) and `settings.auth.accessToken`.

Local-auth provider: `OutlinedCard` "Uses your local CLI sign-in" / "Credentials come from
the provider's own CLI in Termux — no API key needed." *(Android: "on this machine" →
"in Termux")*, trailing `Button` "Connect" when not connected.

**Scrolling (compact and medium).** The whole page body is **one `LazyColumn`**; there is
no list nested inside a vertically scrolling parent (a `LazyColumn` inside
`verticalScroll` throws "Vertically scrollable component was measured with an infinity
maximum height", and a plain `Column` would compose 300+ three-line rows eagerly). Items
in order: status/description, connection section, field rows (one item each), then the
Models "card" drawn per item: `item("models-header")` (48dp, `surfaceContainer`, top
corners `shapes.medium`, 1dp `outlineVariant` border on top/sides), `item("models-search")`,
then `items(models, key = { it.id })` each on `surfaceContainerLow` with 1dp side borders
and a top `outlineVariant` divider, the last row (or the empty/loading/no-match row) with
the bottom corners and bottom border. Whole-role colours only, no alpha (DS §1.4, §1.8):
the desktop's `bg-muted/40` header becomes `surfaceContainer`, one step above the
`surfaceContainerLow` rows. Large catalogs (OpenRouter, Cline: 300+ models) scroll at
60 fps because only visible rows compose.

Expanded: the same content as the detail pane (no back arrow; the pane has a small
`TopAppBar` with the title and actions). Here the fields sit in a top `Column`
(`verticalScroll`, max 50 % of the pane height, desktop `max-h-1/2`) and the models card
below it is its own `LazyColumn` with `Modifier.weight(1f)` (desktop `min-h-64 flex-1`):
two sibling scroll containers with bounded heights, never nested.

### S5.2 Fields

Rendered from `provider.configFields` (catalog) in order; in fallback mode from the
bundled set: `apiKey` (password, "API Key"), `baseUrl` (url, "Base URL", only when
`supportsBaseUrl`), and `modelId` (text, "Model ID" *(Android)*, required by
`cline auth --modelid`, prefilled from the saved `model` or the provider's first
fallback model).

| `field.type` | Control | Notes |
| --- | --- | --- |
| `text` | `OutlinedTextField` single line | placeholder = `field.placeholder` |
| `password` or `secret` | `OutlinedTextField` with `PasswordVisualTransformation`; trailing `IconButton` `visibility`/`visibility_off` CD "Show secret"/"Hide secret", `IconButton` `content_copy` CD "Copy <label>" | §0.6 clipboard + FLAG_SECURE while shown |
| `url` | leading `link` icon, `KeyboardType.Uri` | |
| `number` | `KeyboardType.Number` | non-finite → null (desktop `coerceFieldValue`) |
| `select` | `ExposedDropdownMenuBox`, first item "Not set" | commits on pick |
| `boolean` | `ListItem` + `Switch` (whole row toggles) | commits on toggle |

Commit rules (desktop `commitField` on blur): text-like fields commit on focus loss and on
IME Done; empty trimmed → `null`. Each commit is optimistic (`enabled = true`, merged
`configValues`) and calls `desktopCommand save_provider_settings`
`{provider, enabled?, api_key?, base_url?, settings: toSettingsPatch(configValues)}`
(`apiKey` path also sets `api_key`, `baseUrl` also `base_url`). While saving, the field
shows a trailing 16dp spinner and `stateDescription` "Saving". Success → silent resync.
Failure → snackbar (Long) "Failed to save provider settings for <id>: <message>", resync
(§0.7), drafts remount from the reloaded values.

**Leaving with a dirty draft** (Android-only path: back once closes the IME without
blurring the field, back again pops the screen, so focus loss may never fire): the
screen's `DisposableEffect(Unit) { onDispose { vm.commitPendingDrafts() } }` commits
every field whose draft differs from its stored value, exactly as a blur would. The VM is
scoped to the Providers graph, so the save completes after the pop; while it runs, the S4
row for that provider shows a trailing 16dp spinner with `stateDescription` "Saving", and
a failure shows the same snackbar on S4. Fallback mode does not auto-commit on leave (its
Save is explicit); a dirty fallback form leaves without saving.

Fallback mode commit: no auto-save. A `Button` "Save" *(Android)* under the fields,
enabled when API key and Model ID are non-blank, runs `cline auth --provider <id>
--apikey <key> --modelid <model> [--baseurl <url>]`. Success (exit code 0, §0.4) →
snackbar *(Android)* "Provider configured: <id> (<model>)", built from the arguments
(not echoed from the CLI's ANSI-coloured stdout), status pill → "Configured". Failure →
the ANSI-stripped last stderr line under the button in `bodySmall error`.

"Changes to the fields below are saved automatically." shows only when the desktop shows
it: `connected && authKind != oauth && authKind != local` (`provider-list-view.tsx` title
block), and never in fallback mode. So it is absent for a "Not configured" api-key
provider, for every OAuth provider (including the "Configured with an API key." variant)
and for local-auth providers.

The "Get a <Name> API key" link: label always "Get a <provider.name> API key"; URL from
the curated map (`lib/provider-key-urls.ts` `PROVIDER_API_KEY_URLS`: anthropic,
openai-native, openai, openrouter, gemini, xai, groq, mistral, deepseek, cerebras,
fireworks, together, cline, wandb). The catalog's `ProviderListItem` has no
`docUrl`/`docLabel` today (the desktop file notes the catalog "does not populate it");
if a future catalog adds them, they take precedence over the map. Opens in a Custom Tab
(`androidx.browser`); no link when the provider is not in the map.

Not-connected api-key provider footer (desktop): `bodySmall onSurfaceVariant` "Saving an
API key configures this provider automatically. Use Connect if it reads credentials from
your environment or a local endpoint." + `OutlinedButton` "Connect" → optimistic
`enabled = true` + `save_provider_settings {provider, enabled:true}`. Hidden in fallback mode.

### S5.3 Connection actions

| Action | Shown when | Binding | Copy |
| --- | --- | --- | --- |
| Sign in with browser | oauth kind, not signed in | S7 with `providerId` (`cline`, `cline-pass` → `cline`, `openai-codex`, `oca`) | "Sign in with browser" / pending "Waiting for browser..." |
| Use an API key instead | oauth kind with an `apiKey` config field, not signed in, not connected with a key | expands the field row (chevron rotates 180°) | "Use an API key instead" |
| (API key row, connected with a key) | oauth kind, `!oauthSignedIn && connected && apiKey` non-blank (S5.1 third wireframe) | the `apiKey` field row with show/hide, copy and auto-save (S5.2 commit rules) | line "Configured with an API key." above the row |
| Connect | local kind not connected; api-key kind not connected (footer) | `save_provider_settings {provider, enabled:true}` | "Connect" |
| Disconnect / Sign out | connected (helper mode only) | confirm *(Android)* → `save_provider_settings {provider, enabled:false}` (deletes the entry and clears legacy keys, FM) → reload catalog (cache cleared) → `invalidate()` | Button: "Sign out" when oauth and signed in via browser, else "Disconnect" (so an OAuth provider configured with an API key shows "Disconnect", desktop). Dialog title *(Android)*: "Disconnect <name>?" / "Sign out of <name>?"; text *(Android)*: "Cline removes the saved credentials for <name> from Termux. Sessions using it stop working until you connect it again."; buttons "Cancel" / "Disconnect" or "Sign out" (error colour) |

The confirmation is an Android addition: the desktop disconnects in one click, but the
key is not recoverable from the app (it never stores it), and a mis-tap on a phone is
likely. On success local drafts and shown-secret flags are cleared (desktop
`handleDisconnect`).

### S5.4 Models card

Header (48dp): "Models" (`titleMedium onSurfaceVariant`) + `IconButton` `refresh` CD
"Refresh models" (spins while loading; disabled while loading). Hidden refresh in
fallback mode.

Data: on open and on refresh, `desktopCommand list_provider_models {provider}` →
`{providerId, models: ProviderModel[]}`, for every provider including `cline` and
`cline-pass`: the dashboard's `getLocalProviderModels` already stamps `featured` on them
(`applyClineFeaturedModels`), so the featured tiers render from this one call (desktop
`provider-list-view.tsx` `FEATURED_PROVIDER_IDS` effect and `loadProviderModels` both go
through `list_provider_models`). The `{type:"loadModels"}` browser frame is never used:
its reply strips `featured`, `description`, `contextWindow`, `supportsVision` and
`supportsAttachments` (§0.3 step 2). Results are scoped to the provider id and the
catalog revision they were fetched for (desktop: stale lists must not shadow the next
provider). They are published to `ProviderCatalogRepository` so S8 sees them.

Row (min 72dp, `ListItem` three-line, not clickable as a whole):

- Headline: model `name` (`bodyLarge`) + badges from `featuredBadges(model)`: "Recommended"
  (tier recommended), "Free" (tier free), then each feed tag not already present
  (e.g. "NEW") — `Surface(shape = extraSmall, color = surfaceContainerHighest)` +
  `labelSmall` (DS §4.3 Badge). Then capability icons 16dp `onSurfaceVariant`, each with a
  CD: `draft` "File support" (`supportsAttachments`), `image` "Image support"
  (`supportsVision`), `mic` "Audio support" (audio modality and operation not
  transcription/realtime), `neurology` "Reasoning support" (`supportsReasoning`).
  Then the desktop's `AudioModelBadges` (`components/views/settings/audio-model-badges.tsx`),
  which appear for ordinary providers (OpenAI, Groq): when `operation == "realtime"` or
  (`operation == "transcription"` and `operationModes` contains `streaming`) a Badge
  (same DS §4.3 style) with a 12dp leading `graphic_eq` icon and "Realtime"; else when
  `operation == "transcription"` a Badge with `mic` and "Transcription". Their long-press
  tooltips (and appended CDs) are the desktop titles "Live audio with streaming updates" /
  "Audio-to-text transcription". After the badge, when `operationModes` contains `batch`
  but not `streaming`, a 14dp `wifi_off` icon with CD and tooltip "Streaming not
  supported". Labels in sentence case (the desktop's CSS uppercases them, as in S4.1).
  These rows still appear here (S5 lists every model); S8 hides audio-operation models via
  `isChatModel`.
- Supporting line 1: `description` (`bodySmall`, 1 line, ellipsis) when present.
- Supporting line 2: a 48dp-tall `TextButton`-like row: model id in `ClineMono.small` +
  `content_copy` 16dp, CD "Copy model ID <id>", tooltip "Copy model ID"; after copying,
  "Copied" (`labelSmall onSurface`) for 1.6 s.
- Trailing: `IconToggleButton` `star` / `star` fill 1 in `warning.color` when favourite,
  CD "Favorite <name>" / "Unfavorite <name>".
- Order: favourites first, then catalog order (desktop sort).
- Favourites persist in DataStore `providers.favoriteModels` = `{providerId: [modelId]}`
  (desktop `cline.favorite-provider-models.v1`). Local only.

States: search (only when the list is non-empty) placeholder "Search models by name or
ID", CD "Search models"; no match: "No models match \"<q>\"."; empty + loading: "Loading
models..."; empty + idle: "No models available. Tap refresh to load models." *(Android
adaptation of "Click refresh")*; error: an `errorContainer` strip with the error text
(the list is hidden when a refresh failed, desktop: "the bundled placeholder models would
only read as a fallback"). Fallback mode: the bundled list, no refresh, no search when ≤ 5
models.

### S5.5 ViewModel

```kotlin
data class ProviderDetailUiState(
    val provider: ProviderUi?,                         // null while loading
    val connected: Boolean, val authKind: AuthKind, val oauthSignedIn: Boolean,
    val fields: List<FieldUi>,                         // draft values in memory only; secret drafts never saved to SavedStateHandle
    val shownSecrets: Set<String>,                     // not saved; reset on recreate
    val saving: Set<String>,                           // field paths with a save in flight
    val manualKeyExpanded: Boolean,
    val signIn: SignInState,                           // from ClineSignInCoordinator (S7)
    val models: ModelsCardUi,                          // loading, error, query, rows, copiedId
    val favorites: Set<String>,
    val fallback: Boolean, val fallbackSave: FallbackSaveState,   // Idle | Saving | Error(msg) | Done
    val confirmDisconnect: Boolean, val disconnecting: Boolean,
    val resetToken: Int,                               // desktop detailResetToken
)
```

### S5.6 States

| State | Behaviour |
| --- | --- |
| First run | Opened from S4 or from the composer's "Open API providers"; status pill "Not configured". |
| Loading | Title from the list row; content shows `LinearProgressIndicator`; models card "Loading models...". |
| Populated / connected | As wireframes. |
| Saving | Per-field spinners; navigation stays possible (saves complete in the background; the VM is scoped to the Providers graph so it survives the pop). A dirty draft is committed when the screen leaves composition (`commitPendingDrafts()`, S5.2), and its spinner then shows on the S4 row until the save finishes. |
| Error | Snackbar + resync as above; models card error strip. |
| Sign-in running | S7 waiting state inside the card; Disconnect hidden. |
| Helper unavailable | Unavailable card on top; fallback fields + Save; no Disconnect, no Connect, no models refresh. |
| Termux missing / permission | `KernelBlockedCard` replaces the content. |
| Kernel down (Hub) | As S4.4: the helper acquire starts the Hub through the KernelCoordinator first; KernelStopped → fallback. |
| Provider vanished (deleted elsewhere) | After a resync without the id: snackbar "This provider is no longer available." *(Android)* and pop to S4. |

### S5.7 Accessibility

- The status pill is part of the title semantics: "Anthropic, Configured".
- Secret field: TalkBack reads "API Key, edit box, password" without the value; Show
  secret toggles `stateDescription` "Shown"/"Hidden".
- Capability icons are inside the headline's merged semantics: "Claude Sonnet 4.6, Image
  support, Reasoning support, Recommended".
- Copy and favourite are separate focus stops with their desktop labels.
- Custom TalkBack actions on each model row: "Copy model ID", "Favorite"/"Unfavorite".

### S5.8 Acceptance (S25U)

- [ ] Anthropic: paste a key, tap outside → spinner, then "Configured";
      `cat ~/.cline/data/settings/providers.json` in Termux shows `providers.anthropic`
      with `settings.apiKey`; the key is not in `adb logcat` (grep for its last 6 chars).
- [ ] Type a new Base URL, press back twice quickly (IME closes, screen pops): the S4 row
      shows the saving spinner, then `providers.json` holds the new `baseUrl`.
- [ ] Show secret reveals the key and blocks screenshots (FLAG_SECURE: the screenshot is
      black); hiding it re-allows screenshots.
- [ ] Copy API key → One UI clipboard preview hides it; the clipboard is empty within 60 s
      of the copy while staying in the app, or immediately on returning to the app when
      more than 60 s have passed (switch to Termux for 90 s to test).
- [ ] "Changes to the fields below are saved automatically." shows for a configured
      api-key provider only: absent on a "Not configured" one, on Cline and on a
      local-auth provider.
- [ ] Cline connected with an API key (S7 "Use a Cline API key"): the detail shows
      "Configured with an API key." with the API Key row, the top bar reads "Disconnect",
      and no "Sign in to Cline" card is visible.
- [ ] Disconnect → confirm → status "Not configured", entry removed from `providers.json`.
- [ ] Cline (signed out): "Sign in with browser" starts S7; after success the card reads
      "Signed in via browser" and the top bar shows "Sign out".
- [ ] Models card: refresh spins and reloads; favourite a model, leave, return: it stays on top.
- [ ] Featured tiers on Cline: "Recommended" / "Free" badges and the "NEW" tag appear, and
      still appear after a refresh; descriptions and capability icons survive a refresh.
- [ ] OpenAI: transcription models show "Transcription" (or "Realtime") with the tooltip;
      a batch-only one shows the "Streaming not supported" icon.
- [ ] OpenRouter (300+ models): fling from the fields to the last model and back with no
      dropped frames (Profile GPU rendering under 16 ms) and no crash.
- [ ] Fallback mode (helper unavailable): fields API Key / Model ID (+ Base URL for OpenAI
      Compatible), Save runs `cline auth` and shows "Provider configured: openrouter (…)"
      with no escape characters (`[32m`, `[0m`) visible in any snackbar or error.
- [ ] Save failure (kill the helper mid-save): snackbar with the desktop text; fields
      revert to the stored values.

---

## S6. Add OpenAI-compatible provider

**Purpose.** Register a custom OpenAI-compatible endpoint with its models.

**Desktop equivalent.** `AddProviderContent` (`add-provider.tsx`) in the "Add Provider"
dialog (`settings-view.tsx` `addProviderDialog`), `ModelIdInput` (`model-id-input.tsx`),
`saveNewProvider` (`add_provider` then reload and select). Copied: every label,
placeholder, helper text, validation rule and button label (except the models
placeholder, see S6.2). Translated: the modal dialog becomes a full-screen **NavHost
destination** on compact/medium (an M3 full-screen dialog: confirm in the app bar,
dismiss = close icon, no footer) and a `BasicAlertDialog` (max 560dp, scrollable, footer
buttons only) on expanded (DS #16 as amended in §11); the chip input uses M3
`InputChip`s; capabilities use `FilterChip`s; "Advanced Settings" is an expandable
section.

Why a destination, not a `Dialog` window, on compact/medium: a Compose `Dialog` is a
separate window, so (1) it is not edge-to-edge by default (`decorFitsSystemWindows`) and
does not get the activity's `WindowInsets.ime` handling, (2) the activity's `FLAG_SECURE`
(§0.6) does not cover the API key revealed in it, and (3) it receives only
`onDismissRequest`, so predictive back cannot scrub a seekable transition. As a
`settings/providers/add` destination it gets edge-to-edge, `imePadding`, the activity's
`FLAG_SECURE` and the shared-axis predictive back for free.

### S6.1 Layout

Compact and medium (full-screen destination, `Scaffold` + `TopAppBar(navigationIcon = close, actions = TextButton)`; on medium the form is a 720dp centred column):

```
┌──────────────────────────────────────────┐
│ ✕  Add Provider              [Add Provider]│  action disabled until valid; "Saving..." while saving
│ Add an OpenAI-compatible provider and    │  description bodyMedium onSurfaceVariant
│ choose its available models.             │
│ OpenAI-Compatible Provider               │  titleSmall SemiBold (group label)
│ ┌ Provider ID ─────────────────────────┐ │
│ │ my-provider                          │ │
│ └──────────────────────────────────────┘ │
│ Lowercase ID used in provider registry.  │  supporting
│ ┌ Provider Name ───────────────────────┐ │
│ │ My Provider                          │ │
│ └──────────────────────────────────────┘ │
│ ┌ Base URL ────────────────────────────┐ │
│ │ https://api.example.com/v1           │ │
│ └──────────────────────────────────────┘ │
│ ┌ Model Source URL (Optional) ─────────┐ │
│ │ https://api.example.com/v1/models    │ │
│ └──────────────────────────────────────┘ │
│ Supported JSON: OpenAI `/models` shape   │
│ with a `data` array, or a direct model   │
│ array.                                   │
│ Models                                   │
│ ┌──────────────────────────────────────┐ │  chip field
│ │ [gpt-4o-mini ✕] [llama-3.1-70b ✕]    │ │  InputChip, ClineMono.small label
│ │ Type a model ID, then tap Done       │ │  inline BasicTextField placeholder
│ └──────────────────────────────────────┘ │
│ Add at least one model or set a Model    │
│ Source URL.                              │
│ ┌ Default Model ▾ ─────────────────────┐ │  only when ≥ 2 models
│ │ gpt-4o-mini                          │ │
│ └──────────────────────────────────────┘ │
│ ┌ API Key (Optional) ──────────────────┐ │
│ │ sk-...                      👁   ⧉  │ │
│ └──────────────────────────────────────┘ │
│ Capabilities                             │
│ [✓ streaming] [✓ tools] [reasoning]      │  FilterChip row (FlowRow)
│ [vision] [prompt cache]                  │
│ Advanced Settings                      ⌄ │  48dp row, expands
│   ┌ Timeout (ms) ────────────────────┐   │
│   │ 30000                            │   │
│   └──────────────────────────────────┘   │
│   Custom Headers                         │
│   ┌ Header name ┐ ┌ Value ────┐ 🗑       │
│   [+ Add Header]                         │
└──────────────────────────────────────────┘
```

No bottom button bar: the primary action is only the top-bar `TextButton` ("Add
Provider", "Saving..." while saving) and cancel is only the close icon (CD "Close"), so
TalkBack meets one "Add Provider, button". A save error is shown in `bodySmall error`
(`LiveRegionMode.Polite`) directly under the description at the top of the form, and the
form scrolls to it (`bringIntoViewRequester`), because the top-bar action is where the
user's attention is.

Expanded: `BasicAlertDialog`, 560dp wide, title "Add Provider", the description, the
form in a scrolling column, footer `TextButton` "Cancel" + `Button` "Add Provider"
(footer buttons only, no top-bar action); the save error sits above the footer.
`DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`, with
`securePolicy = SecureFlagPolicy.SecureOn` while the API key is shown (§0.6), and
`Modifier.imePadding()` on the dialog content. Provider ID and Provider Name sit side by
side ≥ 560dp (desktop `md:grid-cols-2`).

### S6.2 Fields, validation, copy

| Field | Label | Placeholder | Helper / error | Keyboard |
| --- | --- | --- | --- | --- |
| providerId | "Provider ID" | "my-provider" | "Lowercase ID used in provider registry."; duplicate → `isError`, "This provider ID already exists." | Text, no autocorrect, `ImeAction.Next` |
| name | "Provider Name" | "My Provider" | | Text, `Next` |
| baseUrl | "Base URL" | "https://api.example.com/v1" | | Uri, `Next` |
| modelsSourceUrl | "Model Source URL (Optional)" | "https://api.example.com/v1/models" | "Supported JSON: OpenAI `/models` shape with a `data` array, or a direct model array." | Uri |
| models | "Models" | "Type a model ID, then tap Done" *(Android; desktop "Type model ID and press Enter" names a key the soft keyboard does not show)* (only while no chips) | "Add at least one model or set a Model Source URL." | Text; IME action Done adds the chip (hardware-keyboard Enter too, `onPreviewKeyEvent`); typing "," adds the chip (commas stripped); Backspace on an empty input removes the last chip |
| defaultModel | "Default Model" | | shown only when models ≥ 2; options = models | dropdown |
| apiKey | "API Key (Optional)" | "sk-..." | show/hide CDs "Show API key"/"Hide API key"; copy CD "Copy API key" | Password, `ClineMono.body` |
| capabilities | "Capabilities" | | chips "streaming", "tools", "reasoning", "vision", "prompt cache" (label = value with "-"→" "); default selected: streaming, tools | |
| timeoutMs | "Timeout (ms)" (in "Advanced Settings") | "30000" | | Number |
| headers | "Custom Headers" | "Header name" / "Value" | row remove CD "Remove header"; button "Add Header" | Text, mono |

Chip CD: "Remove <model>". Dialog close CD: "Close" *(Android)*.

Validation (desktop `canSave`, verbatim): `normalizedProviderId = providerId.trim().lowercase().replace(Regex("\\s+"), "-")`
non-empty, `name.trim()` non-empty, `baseUrl.trim()` non-empty, (`models` non-empty or
`modelsSourceUrl.trim()` non-empty), and not a duplicate of an existing catalog id. Adding
or removing models keeps `defaultModel` valid (first model when the previous default was
removed).

Save: `desktopCommand add_provider {provider_id, name, base_url, api_key?, headers?
(trimmed keys, empty keys dropped), timeout_ms? (parseInt), models, default_model_id
(defaultModel or models[0]), models_source_url?, capabilities? (omitted when empty)}`.
The confirm action shows "Saving..." (it and the close icon / Cancel disabled). Success →
`invalidate()`, reload the catalog, pop `settings/providers/add` (or close the dialog),
open S5 for the new id (`popUpTo("settings/providers")`, then navigate). Failure → the
message in `bodySmall error` (`LiveRegionMode.Polite`) at the top of the form
(compact/medium) or above the footer (expanded), form stays.

Discard guard *(Android)*: back, close or (expanded) a scrim tap with a dirty form (any
field changed from its initial value) opens `AlertDialog` "Discard this provider?" /
"The details you entered will be lost." / "Keep editing" · "Discard". The guard uses
`BackHandler(enabled = dirty)`; a clean form pops with the seekable shared-axis
predictive back (compact/medium) or closes the dialog (expanded). The API key draft is
never written to `SavedStateHandle`; the rest of the form is, so rotation keeps it.

Fallback mode: S6 is not used; the FAB opens S5 for the built-in `openai-compatible`
provider (fields Base URL, API Key, Model ID; `cline auth --provider openai-compatible
--apikey … --modelid … --baseurl …`). The CLI requires a non-empty key; for keyless local
servers the helper text *(Android)* under API Key reads "Keyless servers accept any value
here, for example \"none\"."

### S6.3 States and acceptance

| State | Behaviour |
| --- | --- |
| Empty | Add Provider disabled; no errors shown until a field is touched (except duplicate id, live). |
| Saving | "Saving...", form read-only, back blocked except via the discard dialog after completion. |
| Error | Message at the top of the form (compact/medium, scrolled into view) or above the footer (expanded); actions re-enabled. |
| Helper lost mid-form | Save fails with the helper error; unavailable card appears at the top; the form stays so the user can copy their inputs. |
| IME | The focused field stays above the keyboard: the destination's form column uses voice `formPadding` (`consumeWindowInsets(innerPadding).imePadding()`), which works because it is in the activity window; the top-bar action stays visible. Expanded: `imePadding` on the dialog content (`decorFitsSystemWindows = false`). |

Acceptance (S25U):

- [ ] Duplicate id "openrouter" shows "This provider ID already exists." and keeps Save disabled.
- [ ] Typing "My Local" as id normalises to `my-local` in the saved catalog.
- [ ] Models: "a, b" + Done creates chips `a` and `b`; Backspace on empty removes `b`;
      Default Model appears with two chips.
- [ ] Save with a reachable endpoint creates the provider; S5 opens on it; it lists the models.
- [ ] Back with edits shows "Discard this provider?"; Keep editing keeps the text. Back on
      a clean form scrubs the shared-axis pop preview.
- [ ] TalkBack meets exactly one "Add Provider" button (top bar) on the S25U.
- [ ] Reveal the API key and take a screenshot: it is black (activity `FLAG_SECURE` covers
      the destination); the status bar area is drawn edge-to-edge behind the app bar.
- [ ] Rotation keeps every field (the VM survives it); after process death
      (`adb shell am kill io.github.ichouchiang.clinetermux` while backgrounded) every field except the API key
      draft is restored.
- [ ] Tab S7+ landscape: centred 560dp dialog, ID and Name side by side, Cancel / Add
      Provider in the footer only.

---
## S7. Sign in with Cline flow

**Purpose.** Connect the user's Cline account (WorkOS device-code OAuth) so the kernel in
Termux holds the tokens, without the app ever seeing them; or connect with a Cline API
key. The same flow serves `openai-codex` and `oca` (the other OAuth providers
`cline auth <id>` supports).

**Desktop equivalent.** Onboarding connect step "Sign in with Cline"
(`onboarding-view.tsx`: benefits list, "Sign in" / "Waiting for browser..." / "Cancel" /
"Sign up", "Confirm this code in your browser:", "Sign in failed: <msg>", "Use a Cline
API key" with "Find your key" and "Connecting..." / "Connect", "Failed to save API key:
<msg>"), Settings → Providers OAuth (`runOAuthProviderLogin`, 15 min
`OAUTH_LOGIN_TIMEOUT_MS`), Account signed-out card ("Sign in to Cline", "Connect your
Cline account to review credits, usage, billing, and organization details.", "Sign in" /
"Signing in", "Create account"). Translated: the sidecar's in-process device flow
becomes `cline auth cline` in a visible Termux session that the app brings to the front
(OQ 5: background browser opening is blocked on Android 10+; the dashboard's
`run_provider_oauth_login` would call `xdg-open` from a background process and open
nothing). The device code is shown by Termux, so the app does not render it.

### S7.1 Entry points

| From | Trigger | After success |
| --- | --- | --- |
| Connect a model (first-run spec) | "Sign in" on the Sign in with Cline card | that flow's done step ("You're all set" / "Your Cline account is connected.") |
| S5 (oauth provider) | "Sign in with browser" | S5 shows "Signed in via browser" |
| Account (signed out) | "Sign in" | Account loads the profile |
| Chat credential error (`resolveCredentialFailureAction` → "Sign in to Cline") | via Account | Account, then back to the chat |
| Welcome setup notice "Connect a model" | Connect a model flow | as above |

All entry points call `ClineSignInCoordinator.start(providerId, origin)`; the UI below
is the `SignInWithClineSheet` (`ModalBottomSheet`) that the coordinator's state drives,
except where an entry point renders the waiting state inline (S5 card, Account card,
Connect-a-model card), which then shows the same strings.

### S7.2 Sheet layout (compact)

```
┌──────────────────────────────────────────┐
│                 ───                      │  DragHandle
│ [logo] Sign in with Cline   (Recommended)│  titleMedium; Badge "Recommended" (onboarding)
│ ✓ Regular free model promotions          │  benefits, bodyMedium, check in primary
│ ✓ Subscribe to ClinePass for generous    │
│   usage across the best open weights     │
│   models like DeepSeek, Kimi, and GLM    │
│ ✓ No API key needed                      │
│                                          │
│ How it works                             │  titleSmall (Android)
│ 1  Termux opens and shows a sign-in link │  numbered steps, bodyMedium (Android)
│    and code.                             │
│ 2  Your browser opens. Confirm the code  │
│    and sign in.                          │
│ 3  Come back to Cline when Termux says   │
│    you are logged in.                    │
│                                          │
│ [   Sign in   ]            [ Sign up ↗ ] │  Button + TextButton
│ Use a Cline API key ⌄                    │  TextButton, expands
│ ┌ Cline API key ───────────────────────┐ │  (expanded) password field
│ └──────────────────────────────────────┘ │
│ Find your key ↗              [ Connect ] │
└──────────────────────────────────────────┘
```

Waiting state (sheet stays, content swaps with `AnimatedContent` fade):

```
│ [spinner]  Waiting for browser...        │  titleMedium
│ Finish signing in in Termux and your     │  bodyMedium onSurfaceVariant (Android)
│ browser, then come back here.            │
│ [ Open Termux ]               [ Cancel ] │  FilledTonalButton + TextButton
```

Tablet: the same sheet, centred, max width 640dp (DS §5.5).

IME: the sheet holds the API-key field, so it follows the S8.1 sheet IME rule (content
`imePadding`, no autofocus, the field and "Connect" scroll above the keyboard). While the
key is revealed the sheet window uses `SecureFlagPolicy.SecureOn` (§0.6).

### S7.3 The Termux sign-in sequence

1. **Pre-flight.** Termux resolvable and RUN_COMMAND granted (else `KernelBlockedCard`
   inside the sheet, §0.5). If `providers.json` already shows the provider as connected
   → state `AlreadySignedIn` (below). For `cline` that is the S1 Account predicate:
   `providers.cline.settings.apiKey` non-blank **or** `settings.auth.accessToken`
   non-blank (a Cline API key is account-capable on the dashboard too); for
   `openai-codex` / `oca`, `settings.auth.accessToken` non-blank.
2. **Start.** Record `signin.pending = {providerId, startedAtMs}` in DataStore (so a
   process death during the browser round-trip is recovered). Issue
   `ClineCommands.authClineInteractive()` through `TermuxExecutor.runDetached` (§0.5: no
   completion timeout; the 15-minute limit is step 8's) — RUN_COMMAND
   `$PREFIX/bin/cline auth cline` (`auth <providerId>` for openai-codex / oca) with
   `RUN_COMMAND_BACKGROUND=false`,
   `RUN_COMMAND_SESSION_ACTION=0`, a result `PendingIntent`, label "Sign in to Cline".
   Then, while still in the foreground, start Termux's activity
   (`Intent().setClassName("com.termux", "com.termux.app.TermuxActivity")`,
   `FLAG_ACTIVITY_NEW_TASK`), because Termux cannot bring itself forward (OQ 5).
3. **In Termux** (not app UI): the CLI prints the verification URL and code, opens the
   browser through `xdg-open` → `termux-open` (allowed: Termux is now foreground), waits,
   saves tokens to `providers.json`, prints "You are now logged in to cline" (in green
   through the CLI's ANSI colour table; the app never reads or matches this line) and
   exits 0. On failure it prints the error to stderr and exits 1.
4. **Completion signals,** whichever comes first (success is keyed on exit code 0 or on
   `providers.json`, never on output text):
   - the result `PendingIntent` (foreground sessions report `exitCode` only, no stdout);
   - `ON_RESUME` of the app → `cat providers.json` → token present with
     `providers.<providerId>.updatedAt` ≥ `startedAt`.
5. **Success:** clear `signin.pending`; `ProviderCatalogRepository.invalidate()`; if the
   model selection has no `lastProvider`, set `lastProvider = "cline"` with model
   `anthropic/claude-sonnet-5` unless the catalog later offers a recommended model
   (desktop `rememberProviderSelection`); dismiss the sheet; snackbar "Your Cline account
   is connected." (desktop done-step copy); if the helper is up, fetch
   `desktopCommand cline_account {action:"clineAccount", operation:"fetchMe"}` (the
   `ClineAccountActionRequest` shape every desktop call sends, `account-view.tsx`,
   `onboarding-view.tsx`; the dashboard switches only on `operation` today, but the full
   shape keeps the app valid against a future runtime guard) to cache
   `account.displayName`/`account.scope`.
   If the app is in the background when the result arrives (and notifications are
   permitted), post a notification on its own channel `account`
   *(Android, IMPORTANCE_DEFAULT, name "Account", §11)*: "Signed in to Cline" /
   "Return to Cline to start building." *(Android)*, tap opens the origin screen.
6. **Failure:** exit code ≠ 0 → state `Failed("Sign-in did not finish in Termux (exit code <n>).")`
   *(Android detail)*; RUN_COMMAND `err != -1` → `Failed(errmsg)`; rendered as
   "Sign in failed: <detail>" (`bodySmall error`, `LiveRegionMode.Assertive`, desktop
   onboarding copy, `onboarding-view.tsx`) with the idle buttons back. Decision recorded:
   desktop Settings → Providers uses a different string, "Failed to sign in to <id>:
   <message>" (`settings-view.tsx` `window.alert`); the app uses the onboarding form for
   every origin (S5 included) because the waiting/failed UI is one shared coordinator
   state rendered by the sheet and the inline cards alike.
7. **Cancel:** RUN_COMMAND background (`TermuxExecutor.run`)
   `$PREFIX/bin/pkill` with arguments `["-f", "index.js auth (cline|openai-codex|oca)$"]`,
   then clear `signin.pending`, state `Idle`. Why this pattern: the live processes are
   `node …/current/cline-node-wrapper.cjs auth cline` (the launcher `$PREFIX/bin/cline`
   `exec`s it, `release/install-cline-termux.sh`) and its `spawnSync` child
   `<bun> …/current/index.js auth cline` (`CLINE_BIN_PATH` = `run-cline-termux.sh`, which
   `exec`s Bun, `release/run-cline-termux.sh`); neither argv contains "cline auth". The
   pattern kills the Bun child only; the node wrapper sees the child's signal, re-raises
   it on itself and exits (`apps/cli/bin/cline` `run()`), and Termux shows the process
   ended. pkill exit 0 (killed) and 1 (nothing matched: it already finished) both count
   as cancelled; then re-read `providers.json`: if a token with `updatedAt ≥ startedAt`
   is already there, the sign-in won the race and is treated as success (step 5) rather
   than pretending it was cancelled. Otherwise a later-completing browser authorisation
   can no longer save tokens, because the process holding the device code is gone
   (desktop `cancel_provider_oauth_login` rationale).
8. **Timeout:** 15 min after `startedAt` (`OAUTH_LOGIN_TIMEOUT_MS`) the coordinator
   cancels as in 7 and shows "Sign in failed: The sign-in timed out." *(Android)*.
9. **Open Termux** in the waiting state re-runs the activity start of step 2 (for users
   who switched away before finishing).

**Use a Cline API key** (desktop onboarding):

- Field CD/placeholder "Cline API key" (password); `TextButton` "Find your key" (opens
  `https://app.cline.bot` in a Custom Tab); `Button` "Connect" / "Connecting..." (enabled
  when non-blank; IME Done submits).
- Helper mode: `save_provider_settings {provider:"cline", enabled:true, api_key}`, then
  verify with `desktopCommand cline_account {action:"clineAccount", operation:"fetchMe"}`.
  The dashboard never returns the desktop sidecar's typed
  `{signedIn:false, code:"ACCOUNT_NOT_AUTHENTICATED"}` (`webview/lib/cline-account-state.ts`);
  it **throws**, delivered as `desktopCommandResult {ok:false, error}`
  (`apps/cline-hub/src/server/desktop-commands.ts`). Binding: `ok:true` → verified;
  `ok:false` with error "No Cline account auth token found" → the save did not stick
  (signed out) → rollback; any other `ok:false` after a fresh key save → the key could not
  be verified (bad key, account API error) → rollback. Rollback =
  `save_provider_settings {provider:"cline", api_key:""}`, then "Failed to save API key:
  the key could not be verified (<error>)". The same error binding applies everywhere
  this spec reads the account (S1 Account summary: "No Cline account auth token found" =
  signed out, not an error).
- Fallback mode: `cline auth --provider cline --apikey <key> --modelid <lastModel or anthropic/claude-sonnet-5>`;
  no verification (shown as success when the CLI exits 0, §0.4).
- Success as in step 5. Afterwards S5 for Cline shows the "Configured with an API key."
  variant (S5.1), not "Signed in via browser", and its top-bar action is "Disconnect".

**Sign up** opens `https://app.cline.bot` (desktop `CREATE_ACCOUNT_URL`) in a Custom Tab.

**Already signed in:** the sheet shows "Signed in as <name or email>" (when the account
is cached) or "Signed in to Cline" *(Android)* and a `Button` "Continue" (onboarding
origin) or closes immediately with the success snackbar (other origins).

**Sign out** (S5 for `cline`, and the Account spec): confirm "Sign out of Cline?" (S5.3)
→ `save_provider_settings {provider:"cline", enabled:false}`; clears the cached account
summary. Not available in fallback mode.

### S7.4 Coordinator state

```kotlin
sealed interface SignInState {
    data object Idle : SignInState
    data class AlreadySignedIn(val displayName: String?) : SignInState
    data class WaitingForTermux(val providerId: String, val startedAtMs: Long) : SignInState
    data class SavingKey(val providerId: String) : SignInState
    data class Failed(val detail: String, val kind: Kind) : SignInState { enum class Kind { SignIn, ApiKey } }
    data object Succeeded : SignInState                      // transient; consumed by the UI
}
```

Process-wide singleton (Application scope) so S5, Account, Connect-a-model and the sheet
observe one flow. Only one sign-in runs at a time; a second `start()` while waiting
re-opens Termux instead of starting another command.

### S7.5 States

| State | UI |
| --- | --- |
| Idle | Benefits, how-it-works, Sign in / Sign up, API-key expander |
| Waiting | Spinner title "Waiting for browser...", helper line, Open Termux, Cancel. Sheet dismiss (swipe/back) keeps waiting: the inline cards and the S1 Account row show "Waiting for browser..." until the result |
| Saving key | "Connecting..." on Connect, field disabled |
| Failed | "Sign in failed: …" or "Failed to save API key: …" in `error`; buttons enabled |
| Succeeded | Sheet closes, snackbar |
| Termux missing / permission | `KernelBlockedCard` in the sheet; Sign in hidden |
| Kernel down (Hub) | Irrelevant: sign-in needs only Termux |
| Offline (phone has no network) | Not detectable from the app; the CLI fails in Termux and the result arrives as Failed |

### S7.6 Accessibility

- Opening the sheet moves focus to its title; the benefits list is one merged node per item.
- The numbered steps are a list (`collectionInfo`), read "1 of 3 …".
- Waiting state is announced politely: "Waiting for browser. Finish signing in in Termux and
  your browser, then come back here."
- Returning from Termux with success: the snackbar text is announced.

### S7.7 Acceptance (S25U, Termux 0.118.3, port ≥ v3.0.68-termux.2)

- [ ] From S5 → Cline → "Sign in with browser": Termux comes to the front within 2 s showing
      the URL and code; the browser opens from Termux.
- [ ] Confirm in the browser; Termux prints "You are now logged in to cline"; switch back to
      Cline: the snackbar "Your Cline account is connected." shows and S5 reads "Signed in
      via browser" without any manual refresh.
- [ ] Repeat but kill the app (`adb shell am kill io.github.ichouchiang.clinetermux`) while in the browser:
      relaunching shows the success state (recovered from `signin.pending` + `providers.json`).
- [ ] Cancel while waiting: the Termux session shows the process ended within 2 s;
      `pgrep -f "auth (cline|openai-codex|oca)$"` returns nothing; then complete the
      browser page anyway: `cat ~/.cline/data/settings/providers.json` shows no new
      `providers.cline.updatedAt` (unchanged from before the attempt) and no new
      `auth.accessToken`.
- [ ] Leave the sign-in waiting for 15 minutes: "Sign in failed: The sign-in timed out."
      appears and the Termux process has ended (same check).
- [ ] "Use a Cline API key" with a bad key: "Failed to save API key: the key could not be
      verified (…)" and no `apiKey` left in `providers.json`. With a good key: S1's Account
      row reads "Signed in to Cline" (or the name) and S5 shows "Configured with an API key.".
- [ ] Sign out from S5 → confirm → Account row in S1 reads "Sign in to Cline".
- [ ] With Termux uninstalled the sheet shows "Termux is not installed" and no Sign in button.

---
## S8. Model picker and reasoning menu

**Purpose.** Choose the provider and model for the next session (Home) or switch them on
the current session, with Recommended / Free sections, capability badges, and a way to
set up another provider; choose the reasoning effort.

**Desktop equivalent.** `ModelSelector` and the thinking-level `Select` in
`chat-input-bar.tsx`, `buildModelPickerData` (`lib/featured-models.ts`),
`SearchCombobox` (`sdk/packages/ui/components/search-combobox.tsx`), `lib/model-selection.ts`
(`cline.code.model-selection.v1`, `cline.code.reasoning-selection.v1`), the ≤ 560px
mobile popover (Provider / Model labels, "Close model selector"). Copied: section logic
(cline: Recommended / Free / All models; cline-pass: Subscribed / Free; others: flat
list sorted by label, duplicate names suffixed with " (<id>)"), section descriptions,
the recommended tier's first tag as badge, the "Current model" section for a selected
model outside the offer, "Set up another provider" as the last provider entry,
"No providers found." / "No models found.", "Search providers" / "Search models", the
provider-switch model resolution (remembered model, else first visible), "remember only
explicit picks", disabled while busy, `EFFORT_LEVELS` (None / Low / Medium / High /
Extra, default Low), reasoning seeding per provider, the label rules, the unsupported
tooltip. Translated: the two comboboxes become one `ModalBottomSheet` (DS #7); the
effort `Select` becomes a `DropdownMenu` on the reasoning chip.

### S8.1 Layout

Compact (`ModalBottomSheet`, up to 90 % height, `skipPartiallyExpanded = true`):

```
┌──────────────────────────────────────────┐
│                 ───                      │
│ Model and provider                    ✕  │  titleMedium; IconButton close, CD "Close model selector"
│ Provider                                 │  labelLarge onSurfaceVariant
│ [✓ Cline] [OpenRouter] [Anthropic] [+ Set up another provider] →  LazyRow
│ Model                                    │
│ ┌──────────────────────────────────────┐ │
│ │ 🔍 Search models                     │ │  OutlinedTextField
│ └──────────────────────────────────────┘ │
│ ═══════════ (LinearProgress while loading)│
│ Recommended                              │  section header titleSmall onSurfaceVariant
│ Claude Sonnet 5         NEW  Images Reasoning ✓ │  56–72dp ListItem
│ anthropic/claude-sonnet-5                │  supporting: description or id (ClineMono.small)
│ GPT-5.5                      Images Reasoning   │
│ Free                                     │
│ Try with limited usage at no cost        │  section description bodySmall
│ Kimi K2.5 Free                Reasoning  │
│ All models                               │
│ ...                                      │
└──────────────────────────────────────────┘
```

**IME inside the sheet** (DS §5.5 mandates `imePadding` only for the ask-question and
prompt-queue footers; this sheet needs it too): `ModalBottomSheet(contentWindowInsets =
{ BottomSheetDefaults.windowInsets.union(WindowInsets.ime) })`, so the sheet content
shrinks above the keyboard instead of being covered by it. The search field does **not**
autofocus on compact (the keyboard would cover most of the list on open); on medium and
expanded with a hardware keyboard attached it may. The model `LazyColumn` gets
`contentPadding = PaddingValues(bottom = 16.dp)` plus the navigation-bar inset (DS §5.5
bottom inset) so the trailing free-text "Use model id …" row and the "Refresh models"
button scroll fully into view with the IME open. The search field uses
`ImeAction.Search`; the action hides the keyboard (`LocalSoftwareKeyboardController`)
and keeps the query.

Medium and expanded (sheet 640dp wide, two columns):

```
┌──────────────────────────────────────────────────────────────┐
│ Model and provider                                        ✕  │
│ ┌ Provider ──────────────┐ ┌ Model ─────────────────────────┐ │
│ │ 🔍 Search providers    │ │ 🔍 Search models               │ │
│ │▌Cline              ✓   │ │ Recommended                    │ │
│ │ OpenRouter             │ │ Claude Sonnet 5  NEW Images ...│ │
│ │ Anthropic              │ │ ...                            │ │
│ │ ⊕ Set up another provider│ │                              │ │
│ └────────────────────────┘ └────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

Provider column 220dp: `ListItem`s 48dp, selected with `secondaryContainer`, provider
search shown only when > 6 providers. The DS #7 compact row of `FilterChip`s is used
below 600dp width.

Reasoning menu (anchored `DropdownMenu` on the composer's reasoning chip, DS #7):

```
        ┌──────────────┐
        │   None       │
        │ ✓ Low        │
        │   Medium     │
        │   High       │
        │   Extra      │
        └──────────────┘
   [🧠 Low ▾]  ← chip
```

### S8.2 Data

- **Providers shown:** configured providers that have at least one chat model
  (desktop `enabledProviderIds`: `enabled && modelIds.isNotEmpty()`), plus the current
  session's provider even if not in that set (so the selected value always exists),
  ordered as the catalog. Display name = catalog `name` (fallback: id).
- **Models per provider:** `ProviderCatalogRepository` (cache → instant), filtered to chat
  models (`isChatModel`: `operation == "image-generation"` or chat-compatible modalities,
  port of `isChatCompatibleModel`; the filter runs app-side, as in the desktop, because
  `list_provider_models` returns every model). On sheet open, if the helper is `Ready`,
  refresh only the active provider via `desktopCommand list_provider_models {provider}` →
  `{providerId, models}` (desktop `chat-input-bar.tsx` `refreshActiveProviderModels` →
  `loadProviderModels`; the only call that carries `featured`, `description`,
  `contextWindow` and the modalities, §0.7), and keep the current list if it fails. The
  `{type:"loadModels"}` frame is never used (its reply would erase descriptions, tiers and
  the usage ring's `contextWindow`, §0.3 step 2). If the helper is not
  running, the picker does **not** start it; it shows the cache or the fallback lists
  (§0.4). A `TextButton` "Refresh models" *(Android)* under the list then acquires the
  helper on demand.
- **Sections:** `ModelPickerData.build(providerId, models)` is a 1:1 Kotlin port of
  `buildModelPickerData`, with its unit tests from `featured-models.test.ts`:
  - `cline`: Recommended (tier `recommended`, sorted by `featured.rank`, badge =
    `featured.tags[0]`, description), Free (tier `free`, description "Try with limited
    usage at no cost"), All models (unfeatured, sorted by label). No tiers at all → flat.
  - `cline-pass`: Subscribed + Free ("Try with limited usage, separate from ClinePass
    quota"); offline fallback splits by the `cline-pass/` id prefix.
  - others: flat, sorted by label, duplicates labelled "<name> (<id>)".
  - Selected model not in the offer → appended under "Current model".
- **Capability badges** (DS #7): "Images" when `inputModalities` contains `image` (else
  `supportsVision`), "Reasoning" when `supportsReasoning`. Tier badge before them.
  Badges are `Surface(shape = extraSmall, color = surfaceContainerHighest)` + `labelSmall`.
- **Search** matches `"<label> <description> <id>"` case-insensitively (SearchCombobox).
  Section headers stay visible while searching (desktop note: same-named options in two
  sections).
- **Free-text model id:** when the search text is non-blank, contains no spaces and
  matches no option, the list ends with a row `add` + "Use model id “<text>”" *(Android)*
  (`ClineMono.small` for the id). Picking it selects that id for the active provider.
  Always available (helper mode too), because the desktop's catalog is "discovery data,
  not validation".

### S8.3 Selection behaviour

Host modes: `Home` (no session yet) and `Session(sessionId, live: Boolean)`.

**Liveness is decided before sending**, never inferred from an error: `live = true` only
when this app's Hub connection has a successful `session.attach` reply for that session
since the socket last (re)connected, and no `run.interrupted {reason:"hub_restart"}` or
socket reconnect has happened since (both reset `live` to false until the next attach).
The Hub does not translate an unknown session on `session.update_connection` into
`session_not_found`: `handleSessionUpdateConnection`
(`sdk/packages/core/src/hub/server/handlers/session-handlers.ts`) lets the local runtime
host's `SessionNotFoundError` (`local-runtime-host.ts` `getSessionOrThrow`, message
"session not found: <id>") escape, and `browser-websocket.ts` answers it as
`error.code: "command_failed"`. A not-live host therefore goes straight to the
pending-connection column below without sending.

| Action | Home | Live session | Not-live session (daemon restarted) |
| --- | --- | --- | --- |
| Pick a model | update local selection; close sheet | `session.update_connection {sessionId, updates:{providerId, modelId}}` (omit `apiKey`, FM); row shows a 16dp spinner; close on reply | store as the session's pending connection; applied by the resume `session.create {sessionConfig:{sessionId, providerId, modelId, …}, initialMessages}` on next send (composer spec); close |
| Pick a provider chip | resolve model = remembered `lastModelByProvider[p]` if offered (or live-only), else first offered model; update both; sheet stays open on the new provider's list | same resolution, one `session.update_connection {providerId, modelId}`; sheet stays open | as Home, stored pending |
| "Set up another provider" | close sheet, navigate to `settings/providers` (desktop: opens Settings → Providers) | same | same |

- **Remember** `{lastProvider, lastModelByProvider}` (DataStore `model.selection`, desktop
  key name kept in a comment) only on explicit picks, never when a session's config is
  loaded (desktop: "Mirroring every provider/model prop change … would silently replace
  the user's chosen default").
- **Home defaults:** provider = remembered `lastProvider` if configured, else
  `providers.json` `lastUsedProvider`, else the first configured provider; model =
  remembered, else the provider's `defaultModelId`, else first offered.
- **Busy:** when the session status is `running` the composer chip is disabled (desktop
  `disabled={isBusy}`). If a run starts while the sheet is open, all rows and chips are
  disabled and a line *(Android)* "You can change the model when the agent finishes."
  appears under the title; the sheet stays open.
- **Errors** on `session.update_connection` (sent only when `live`): `session_not_found`,
  or `command_failed` whose message starts with "session not found" (the shape the Hub
  actually sends today, see above) → the session was not live after all: set
  `live = false`, store pending, close, no error; `hub_draining` → keep the spinner, retry after
  `hub.drain_changed {draining:false}` (KC); other errors → row returns to normal,
  snackbar "Could not switch model: <message>" *(Android)*; the chip keeps the old model.
- **Side outputs** to the composer (composer spec): `modelSupportsImages`
  (`inputModalities` contains image, else `supportsVision`, else null),
  `modelSupportsReasoning` (from the catalog's `supportsReasoning`; in fallback, the §0.4
  rule: `true` for a model in `FALLBACK_PROVIDER_REASONING_MODELS`, `false` for one only
  in `FALLBACK_PROVIDER_MODELS`, `null` for any other model), `contextWindow` (for the
  usage ring; null hides it).

### S8.4 Reasoning effort

Desktop `EFFORT_LEVELS`: None (`none`), Low (`low`, default), Medium (`medium`), High
(`high`), Extra (`xhigh`).

- Chip label: no explicit choice and support unknown → "Reasoning"; no explicit choice
  and unsupported → "None"; else the level label.
- Chip enabled only when `modelSupportsReasoning == true`. Disabled tooltip (long-press):
  "The selected model does not report reasoning support". CD "Thinking level".
- Picking a level: Home → stored, sent at `session.create` as
  `sessionConfig.thinking`/`reasoningEffort` (None → `thinking:false`, no effort; others →
  `thinking:true, reasoningEffort:<value>`); live session → `session.update_connection
  {sessionId, updates:{thinking, reasoningEffort}}` (None sends `thinking:false`; FM:
  "thinking:false|null clears"). Persist per provider in DataStore `model.reasoning`
  (`{providerId: level}`, desktop `cline.code.reasoning-selection.v1`).
- Seeding (desktop effect): when the model supports reasoning and the session has no
  explicit choice, or the provider changed and that provider has a remembered level, apply
  the remembered level, else Low.
- Menu items are `DropdownMenuItem`s with a trailing `check` on the current value; 48dp
  items, min width 200dp (DS #15).

### S8.5 ViewModel

```kotlin
data class ModelPickerUiState(
    val host: PickerHost,                               // Home | Session(id, live)
    val busy: Boolean,
    val providers: List<PickerProvider>,                // id, name, selected
    val activeProviderId: String?,
    val query: String, val providerQuery: String,
    val sections: List<PickerSection>,                  // id, label, description?, rows
    val freeTextRow: String?,                           // "Use model id “…”"
    val selectedModelId: String?,
    val loading: Boolean,                               // refresh in flight
    val source: CatalogSource,                          // Catalog | Cache | Fallback
    val pendingModelId: String?,                        // update_connection in flight
)
data class PickerRow(val id: String, val label: String, val supporting: String?, val tierBadge: String?,
                     val images: Boolean, val reasoning: Boolean, val section: String?)
```

### S8.6 States

| State | Behaviour |
| --- | --- |
| First run (no provider) | Provider row shows only "Set up another provider"; body EmptyState "No providers found." (`titleMedium`) + `FilledTonalButton` "Set up another provider". The composer's model chip in this state shows "Model" and opens this sheet. Deliberate deviation: the desktop disables the model selector when `providers.length === 0` (`chat-input-bar.tsx`) and relies on the welcome setup notice; Android keeps the chip enabled and opens the sheet so the empty state can offer setup from the control the user just tapped (a disabled chip gives a touch user no explanation). |
| Loading | `LinearProgressIndicator` under the search field; cached rows stay visible. |
| Populated | Sections as above. |
| No models | "No models found." under the search (for a provider with an empty list, or a search with no match and no free-text row). |
| Fallback | Bundled list; info line *(Android)* `bodySmall onSurfaceVariant`: "Showing a built-in model list. Open Providers to load the full list." with the "Refresh models" button. Reasoning support follows §0.4: for a bundled model (e.g. `claude-sonnet-4-6`) it is `true`, so the chip is enabled and, after seeding, reads "Low"; for a saved or free-text model outside the tables it is unknown and the chip reads "Reasoning" (disabled). |
| IME open | Sheet content sits above the keyboard (S8.1 IME rule); the list scrolls under the search field; the last row ("Use model id …" or "Refresh models") can be scrolled fully into view; the Search action closes the keyboard. |
| Running | Busy rules above. |
| Kernel down | Home: works (local). Session: the composer is read-only (composer spec) so the chip is disabled; the sheet is not reachable. |
| Error | Refresh failures are silent (desktop); switch failures → snackbar. |

### S8.7 Accessibility

- The sheet title is focused on open; close button CD "Close model selector".
- Provider chips: `FilterChip` with `selected` semantics, CD = provider name; "Set up
  another provider" is an `AssistChip` with `add` icon.
- Model rows: `Role.RadioButton`, `selected`, CD "<label>, <section>, Images, Reasoning,
  <tier badge>"; the description is read after.
- Section headers have `heading()`.
- The composer chip's CD is "Model and provider: <Provider> / <Model>" (desktop `title`
  format) even when the visible label is truncated to 16 graphemes (DS #7).

### S8.8 Acceptance (S25U)

- [ ] Signed in to Cline: the sheet shows Recommended (with a "NEW"-style badge where the
      feed tags one), Free with "Try with limited usage at no cost", and All models; close
      and reopen the sheet with the helper running (refresh on open): the sections,
      descriptions and the usage ring are unchanged.
- [ ] Type in the search on the S25U with the keyboard open: the last row can be scrolled
      into view above the keyboard; "Search" on the keyboard closes it.
- [ ] A session the daemon lost (run `cline hub stop` and `cline hub start`, then open the
      old session): picking another model shows no error, closes the sheet, and the next
      send's resume `session.create` carries the new `modelId`.
- [ ] Fallback mode with `anthropic` / `claude-sonnet-4-6`: the reasoning chip is enabled
      and reads "Low".
- [ ] Pick a Free model on Home, send a prompt: `session.create` carries that
      `providerId`/`modelId` (check the Hub log or session details sheet).
- [ ] In a live idle session switch to an OpenRouter model: the reply comes from it on the
      next turn; the session details sheet shows the new model.
- [ ] While a turn runs the chip is disabled; opening the sheet before the run starts and
      sending from another device disables the rows with the busy line.
- [ ] Type `my-custom-model` in search for OpenAI Compatible → "Use model id “my-custom-model”"
      selects it.
- [ ] Reasoning chip: disabled with the tooltip on a non-reasoning model; on a reasoning
      model pick High, start a new session: `sessionConfig.thinking:true,
      reasoningEffort:"high"`; Extra maps to `xhigh`.
- [ ] Kill the helper: the sheet still opens instantly from the cache.
- [ ] Tab S7+: two-column sheet; "Search providers" appears with more than 6 providers.

---

## S9. Workspace picker and folder browser

**Purpose.** Choose the folder (inside Termux) a new session works in: recent workspaces,
a folder browser, a typed path, or "Just chat" (the kernel's shared chat workspace).

**Desktop equivalent.** `WorkspacePicker` in `welcome-workspace-controls.tsx` (search
"Search workspaces, or type a folder path", "Open folder “<path>”", empty texts, "Open
folder...", "Just chat", error "Couldn't open "<path>". Check that the folder exists and
try again.", `formatWorkspacePath` "~/…", "Chat" label), `WorkspaceSelector` in
`workspace-selector.tsx` (conversation variant is disabled; typed path "/path/to/workspace"
+ "Go"), `RemoteDirectoryPicker` (`remote-directory-picker.tsx`: Home / Parent / path /
Refresh, "Loading remote directories…", "No subdirectories", truncation note, "Cancel" /
"Use this folder"), `app/page.tsx` (`switchWorkspace` validates before adopting, the
catalog merges session history with stored workspaces, `selectChat` = empty workspace),
`lib/workspace-paths.ts` (`looksLikeFolderPath`, `normalizeWorkspacePath`,
`isExcludedWorkspacePath`, `workspacePathsFromSessions`). Translated: the popover becomes
a bottom sheet; the native/zenity folder dialog becomes the in-app folder browser over
RUN_COMMAND `find` (FM: `list_workspace_directories`/`pick_workspace_directory` are
sidecar-only); "remote" wording becomes "Termux".

Branch picker (desktop `BranchPicker`, git checkout) is Tier 3/4 (FM "Git branch display")
and not part of this sheet.

### S9.1 Trigger

The composer's workspace chip (composer spec) on Home: `AssistChip` with `folder` and the
label = last path segment, or "Chat" for the chat workspace (desktop). In a conversation
the chip is display-only (desktop `WorkspaceSelector disabled`): tapping does nothing,
long-press shows a tooltip with the full path (desktop `TooltipContent`).

### S9.2 Sheet layout (compact)

```
┌──────────────────────────────────────────┐
│                 ───                      │
│ Choose workspace                         │  titleMedium (Android, from "Choose remote workspace")
│ ┌──────────────────────────────────────┐ │
│ │ 🔍 Search workspaces, or type a      │ │  OutlinedTextField, autofocus off on compact
│ │    folder path                       │ │  (keyboard would cover the list)
│ └──────────────────────────────────────┘ │
│ 📂 Open folder “~/projects/new-app”      │  only when the text looks like a path
│ Workspaces                               │  section header
│ 📁 ~/projects/cline-termux-app        ✓  │  ListItem 56dp, ClineMono.small path, check on active
│ 📁 ~/storage/shared/Code/site            │
│ 📁 ~/notes                               │
│ ──────────────────────────────────────── │
│ ⊕ Open folder...                         │  ListItem → folder browser
│ 💬 Just chat                             │  ListItem → chat workspace
│ (error surface when validation fails)    │
└──────────────────────────────────────────┘
```

Tablet: same sheet, centred at 640dp.

**IME inside the sheet:** the same rule as S8.1: `contentWindowInsets =
{ BottomSheetDefaults.windowInsets.union(WindowInsets.ime) }`, no autofocus on compact,
the list's bottom `contentPadding` = navigation bars + 16dp so "Open folder..." and "Just
chat" scroll above the keyboard; the field's IME action is `ImeAction.Go`, which runs
"Open folder “<text>”" when the text is path-like and otherwise just hides the keyboard.

**Folder browser host.** Compact and medium: the NavHost destination
`workspace/browse?start={path}` (full screen, `Scaffold` + `TopAppBar(navigationIcon =
close, actions = TextButton("Use this folder"))`), for the same reasons as S6 (edge-to-edge,
IME, seekable predictive back; a `Dialog` window has none of them). "Open folder..." hides
the sheet but keeps its `rememberSaveable` "open" flag, then navigates; when the browser
pops without a choice, the host screen recomposes and the sheet reappears; after "Use
this folder" the flag is cleared before the pop, so the sheet stays closed. Expanded: a
`BasicAlertDialog` (560dp, `DialogProperties(usePlatformDefaultWidth = false,
decorFitsSystemWindows = false)`) shown over the still-open sheet, with footer buttons
"Cancel" / "Use this folder" and no top-bar action.

Folder browser (compact/medium destination):

```
┌──────────────────────────────────────────┐
│ ✕  Choose workspace           [Use this folder]│
│ Browse folders in Termux. Cline works    │  description (Android)
│ inside Termux, so only Termux folders are│
│ shown here.                              │
│ [Home] [Shared storage]                  │  FilterChip roots (Shared storage only if ~/storage exists)
│ (⌂) (↑)  ~/projects               (⟳)   │  IconButtons + path (ClineMono.small, start-ellipsized)
│ ──────────────────────────────────────── │
│ 📁 cline-termux-app                    › │  ListItem 56dp; tap = open folder
│ 📁 cline-termux-voice                  › │
│ 📁 site                                › │
│ ...                                      │
│ Only the first directories are shown.    │  when truncated
│ Open a folder to continue browsing.      │
└──────────────────────────────────────────┘
```

No bottom bar on compact/medium: "Use this folder" lives only in the app bar and cancel is
the close icon (CD "Close"), so the action is announced once. The list's bottom
`contentPadding` is the navigation-bar inset + 16dp.

### S9.3 Data and bindings

- **Home path:** `/data/data/com.termux/files/home` (KC: no `~` expansion in RUN_COMMAND
  arguments; the app expands `~` and `~/…` itself before every call).
- **Recents** ("Workspaces" section): MRU merge, deduped by `normalizeWorkspacePath`:
  (1) the active workspace (if not chat), (2) DataStore `workspace.recents` (max 20,
  newest first; desktop `workspace-selection.v2` shape `{lastWorkspace, workspaces}`),
  (3) distinct `workspaceRoot || cwd` of `session.list {limit: 200, rootOnly: true}`
  records ordered by last activity (desktop `workspacePathsFromSessions`), when the Hub is
  connected. Then `filterWorkspacePaths`: drop the chat workspace
  (`…/.cline/data/workspaces/chat`), any path containing a `.cline` segment, the home
  directory itself, and `~/storage/shared` itself (the Android equivalent of the
  desktop's home/Desktop exclusion). Filtered by the search text (substring,
  case-insensitive).
- **Display:** `formatWorkspacePath`: the Termux home prefix → `~` (also keep the
  desktop's `/home/<u>`, `/Users/<u>` rules for sessions imported from elsewhere).
- **Path-like text** (`looksLikeFolderPath`: starts with `/`, is `~`, or starts with `~/`)
  adds the row "Open folder “<text>”"; with no matching recents the empty text is "Press
  the option above to open this folder"; otherwise "No workspaces found — type a full
  folder path to add one".
- **Validation** (desktop `validate_workspace_directory` → Android): RUN_COMMAND
  `$PREFIX/bin/realpath -e <expanded path>` (exit 0, stdout = canonical path), then
  `$PREFIX/bin/find <canonical> -maxdepth 0 -type d` (stdout non-empty = a directory).
  Failure → error surface `Surface(errorContainer, shape = small)` `bodySmall`:
  "Couldn't open "<path>". Check that the folder exists and try again." The canonical
  path is what gets stored (desktop adopts the resolved form).
- **Folder listing:** `ClineCommands.listDirectories(dir)` = RUN_COMMAND
  `find <dir> -maxdepth 1 -mindepth 1 -type d -not -name ".*"`; parse stdout lines, sort
  by name (locale-aware, case-insensitive), show at most 500. Truncated when the result
  hit the 100 KB cap or more than 500 lines (KC result cap) → the truncation note.
  `exitCode != 0` with stderr → the error state with the stderr's last line (e.g.
  "find: ‘/data/data/com.termux/files/home/x’: Permission denied").
- **Parent:** computed client-side (`File(path).parent`); Up is disabled only at `/`.
  Termux can read above its home (`/data/data/com.termux/files`) and outside it through
  the `~/storage/shared` symlink, so the app does not invent a boundary; a folder Termux
  cannot read shows the listing error.
- **Shared storage root:** shown when `find /data/data/com.termux/files/home/storage -maxdepth 0`
  succeeds (cached per sheet open). Root = `~/storage/shared`. Otherwise a `ListItem`
  *(Android)* "Set up shared storage" / "Lets Cline open folders in your phone's storage.
  Termux asks for storage permission." → RUN_COMMAND foreground `termux-setup-storage`
  through `TermuxExecutor.runDetached` (§0.5; `BACKGROUND=false`, `SESSION_ACTION=0`) and bring Termux to the front (same mechanism
  as S7 step 2, because the permission dialog belongs to Termux); re-check on resume.
- **Just chat:** clears the Home selection (desktop `setWorkspacePath("")`): no folder is
  chosen, the chip reads "Home", and the next `session.create` sends the Termux home
  `/data/data/com.termux/files/home` as `workspaceRoot` and `cwd` *(Android, decided A22;
  HC §2.7 step 3)*. Nothing is created or written there. The desktop sends no folder and the
  kernel falls back to its own working directory; the kernel creates
  `~/.cline/data/workspaces/chat` only when it imports sessions (`ensureChatWorkspace`), so the
  app no longer sends that path. `client.register`'s `workspaceContext` keeps the last real
  workspace (KC step 5); it is not re-sent on workspace change.
- **Adopt:** a validated folder becomes the Home selection, is pushed to the front of
  `workspace.recents`, and is used for `session.create {workspaceRoot, cwd}` on first send.
  `session.create` validates again; a failure there is the composer's error.

### S9.4 Interactions and navigation

| Action | Result |
| --- | --- |
| Tap a recent | validate → adopt → close sheet. Active one → just close (desktop). |
| Tap "Open folder “x”" or IME Go on path-like text | validate → adopt → close; failure shows the error inside the sheet (sheet stays). |
| "Open folder..." | opens the folder browser at the active workspace (or Home when chat) immediately: no pending label, unlike the desktop's "Opening folder picker..." (which waits for a native dialog). Disabled, with "Just chat", while a recents validation runs (desktop `disabled={switching \|\| picking \|\| selectingChat}`). |
| Browser: tap folder | navigates into it (no selection yet) and pushes it on the browser's history stack. |
| Browser: Home / Up / Refresh | reload accordingly; disabled while loading; Home disabled when at Home. Home and Up push their target on the history stack; Refresh does not. |
| Browser: root chips | jump to Home or Shared storage (pushed on the history stack). |
| Browser: "Use this folder" | validate current path → adopt → close browser and sheet. Disabled while loading or after a listing error (desktop). |
| Browser: back | goes back through the folders visited, as DocumentsUI, Files by Google and Samsung My Files do: `BackHandler(enabled = history.size > 1)` pops one folder (an instant pop, no seekable preview, while the stack is non-empty). At the starting folder back closes the browser (NavHost pop with the seekable shared-axis preview on compact/medium; dialog dismiss on expanded) and returns to the sheet. Up stays available for the parent folder. |
| "Just chat" | adopt chat → close. Local and instantaneous on Android (it only sets the Home selection), so there is no "Switching to chat..." pending label; disabled while a recents validation runs. |
| Sheet back / swipe | closes without changing the selection. |

### S9.5 ViewModel

```kotlin
data class WorkspacePickerUiState(
    val active: WorkspaceChoice,                       // Folder(path) | Chat
    val query: String,
    val recents: List<WorkspaceRow>,                   // path, display, isActive
    val pathLike: Boolean,
    val validating: String?,                           // path being validated (row spinner)
    val error: String?,
    val blocked: KernelBlock?,
)
data class FolderBrowserUiState(
    val path: String, val parent: String?, val atHome: Boolean,
    val history: List<String>,                         // visited folders, start first; back pops it (rememberSaveable via SavedStateHandle)
    val entries: List<FolderEntry>,                    // name, path
    val loading: Boolean, val error: String?, val truncated: Boolean,
    val sharedStorage: SharedStorageState,             // Available | NotSetUp | Checking
)
```

### S9.6 States

| State | Behaviour |
| --- | --- |
| First run | No recents: the "Workspaces" section shows "No workspaces found — type a full folder path to add one"; Open folder... and Just chat are always present. Default Home selection on first run = Chat (the desktop starts with an empty workspace). |
| Loading | Session-history recents arrive asynchronously; stored recents show immediately. Validation shows a 16dp spinner on the tapped row and disables the other rows, "Open folder..." and "Just chat" (desktop). Browser: centred `CircularProgressIndicator` + "Loading directories…" *(from "Loading remote directories…")*. |
| Empty folder | "No subdirectories". |
| Error | Sheet: the validation error surface. Browser: `error` icon + message centred (desktop). |
| Termux missing / permission | `KernelBlockedCard` replaces the list; Just chat stays available (it needs no RUN_COMMAND). |
| Kernel down (Hub) | Works; session-history recents are missing until the Hub is back. |
| Running | Home only, so not applicable; in conversation the chip is display-only. |
| IME open | Sheet content sits above the keyboard (S9.2 IME rule); "Open folder “<text>”", the recents, "Open folder..." and "Just chat" stay reachable by scrolling; Go runs the path row or hides the keyboard. |
| Storage permission missing | "Set up shared storage" row instead of the Shared storage chip; a listing inside `~/storage/shared` that fails with "Permission denied" shows that error plus the same row. |

### S9.7 Accessibility

- Recents: `Role.RadioButton` with `selected` on the active one; CD = the full path read
  as written (DS §8.1.12), not the `~` short form.
- Browser rows: CD "<name>, folder"; the path bar's CD is "Current folder <full path>".
- Icon buttons: "Home directory", "Parent directory", "Refresh directories"
  (from "Remote home directory", "Parent remote directory", "Refresh remote directories").
- Paths are forced LTR (DS §8.2.6).

### S9.8 Acceptance (S25U)

- [ ] On Home tap the workspace chip: recents list shows `~/…` paths from earlier sessions,
      the active one checked.
- [ ] Type `~/does-not-exist` + Go: "Couldn't open "~/does-not-exist". Check that the folder
      exists and try again." and the sheet stays.
- [ ] Open folder... → browse to `~/projects/x` → Use this folder: chip shows `x`; the next
      session's details show workspace `/data/data/com.termux/files/home/projects/x`.
- [ ] Just chat: chip shows "Home"; a new session's workspace is
      `/data/data/com.termux/files/home` (A22), its `run_commands` run there, and nothing new
      appears in the home.
- [ ] Without `termux-setup-storage`: "Set up shared storage" brings Termux forward with its
      permission prompt; after granting and returning, the Shared storage chip lists
      `~/storage/shared` folders.
- [ ] A folder with > 500 subfolders shows the truncation note.
- [ ] With the Hub stopped the picker still lists stored recents and validates paths.
- [ ] In the browser go Home → `projects` → `x`, then press back twice: the list shows
      `projects`, then Home; a third back closes the browser and the sheet is open again.
- [ ] Type a path with the keyboard open: "Just chat" can be scrolled above the keyboard;
      Go opens the typed folder.
- [ ] While a recent is validating, "Open folder..." and "Just chat" are disabled.

---
## 10. Recovery matrix: what each screen surfaces

From KC "Recovery matrix" plus the helper and RUN_COMMAND cases this spec adds.

| Condition | Detected by | S1 | S4/S5/S6 | S7 | S8 | S9 |
| --- | --- | --- | --- | --- | --- | --- |
| Termux not installed | `<queries>` resolution fails | blocked card item 0 | blocked card | blocked card in sheet | works from cache (Home) | blocked card; Just chat still works |
| RUN_COMMAND permission missing | permission check / `SecurityException` | blocked card | blocked card | blocked card | as above | as above |
| allow-external-apps off | result `err != -1`, `errmsg` | blocked card | blocked card | blocked card | as above | as above |
| RUN_COMMAND hung (60 s; `run` only, never `runDetached`, §0.5) | executor timeout | summaries absent | "Termux did not answer" card + Retry + "Run diagnostics" (`cline doctor --json`) | Failed("The command did not finish within a minute.") | n/a | error surface in the sheet/browser |
| Helper start timeout / socket failure | §0.3 | Telemetry write rolls back with the error | unavailable card + fallback | key verification skipped → fallback `cline auth` | silent, cache/fallback | n/a |
| Helper unsupported build (OQ 3) | §0.3 | Telemetry read-only | fallback mode permanently | Termux flow only (it never needed the helper) | fallback lists | n/a |
| Port 8787 held by a dashboard without our secret | §0.3 step 1.4 probes | Telemetry write rolls back | PortInUse card + fallback | key verification skipped → fallback `cline auth` | silent, cache/fallback | n/a |
| User pressed "Stop kernel" this app session | `KernelCoordinator.userStopped` | Telemetry read-only | KernelStopped card ("Start kernel") + fallback; nothing is started | Termux flow only | fallback lists | session-history recents absent |
| ECONNREFUSED on 25463 (Hub down) | `/health` | Kernel row state; Hub summaries absent | helper acquire runs the KernelCoordinator connect first (§0.3 step 1.2), "Starting settings helper..." up to 45 s | none | session switching unavailable (composer read-only) | session-history recents absent |
| `hub_draining` | reply error | — | — | — | keep spinner, retry on `hub.drain_changed` | — |
| Session not live (not attached on this connection, or reconnect / `hub_restart` since) | app state before sending (S8.3) | — | — | — | no `update_connection` sent; store as pending connection for the resume `session.create` | — |
| `session_not_found`, or `command_failed` with "session not found: <id>", on `update_connection` | reply error (the Hub answers `command_failed` today, S8.3) | — | — | — | mark not-live, store as pending connection, no error | — |
| `run.interrupted {reason:"hub_restart"}` | event | — | — | — | the session becomes not-live: pending-connection path | — |
| Stale catalog after another client edits | 60 s TTL / resync | — | pull-to-refresh, silent resync after saves | — | refresh on open when helper ready | — |
| Sign-in interrupted by process death | `signin.pending` in DataStore | Account row "Waiting for browser..." until resolved | S5 card waiting | resumes into Waiting, then success/failed on resume | — | — |

## 11. Additions this spec needs in `design-system.md`

DS says screen specs must not define new system-level things; these are requested
additions for the owner to merge (until merged, implement them exactly as listed):

- **Icons (DS §7.4):** `refresh` (refresh models, refresh directories), `visibility` /
  `visibility_off` (show/hide secret), `star` + fill (favourite model), `key` (API-key auth
  hint), `language` (sign-in auth hint), `devices` (local-CLI auth hint), `open_in_new`
  (external links, system settings rows), `link` (URL fields), `image`, `draft`, `mic`
  (model capabilities), `graphic_eq` ("Realtime" audio badge), `wifi_off` ("Streaming
  not supported"), `add_comment` (Just chat), `home` (folder browser home),
  `restart_alt` (New user experience), `analytics` (Telemetry), `remove` (font size
  decrease), `chevron_right` (navigation-row chevrons only; disclosure uses DS
  `expand_more`), `sd_storage` (Shared storage chip). Existing DS glyphs are reused, not
  replaced: Add provider FAB = `add` (DS §5.4), Diagnostics = `share` (DS §7.4 "Export /
  share"), section disclosure = `expand_more` rotated (DS #4).
- **Component:** `KernelBlockedCard` (§0.5) and the helper `Unavailable` card (§0.3, now
  with the `PortInUse` and `KernelStopped` rows) as inventory rows next to DS #12's
  "helper unavailable".
- **DS #16 amendment (editors on compact/medium):** full-screen editors (add provider,
  folder browser, and by extension MCP server and schedule editors) are **NavHost
  destinations** rendered full-screen (`Scaffold` + `TopAppBar(navigationIcon = close,
  actions = TextButton(confirm))`, no footer), not `Dialog(usePlatformDefaultWidth =
  false)` windows: a `Dialog` window is not edge-to-edge by default, does not get the
  activity's IME handling or `FLAG_SECURE`, and cannot drive the seekable shared-axis
  predictive back (S6 introduction). `BasicAlertDialog` stays for expanded only, with
  `DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)`,
  `securePolicy = SecureFlagPolicy.SecureOn` while a secret is shown, and footer buttons
  only (no top-bar action). The `BackHandler(enabled = dirty)` discard guard is kept.
- **DS §5.5 amendment:** every sheet that holds a text field (model picker, workspace
  picker, sign-in sheet, besides ask question and prompt-queue edit) uses
  `contentWindowInsets = { BottomSheetDefaults.windowInsets.union(WindowInsets.ime) }`,
  no autofocus on compact, and a list bottom `contentPadding` of navigation bars + 16dp.
- **DS #7 amendment:** the model picker's data binding is `desktopCommand
  list_provider_models {provider}`, not the `loadModels {providerId}` browser frame (its
  reply strips `featured`, `description`, `contextWindow`, `supportsVision`,
  `supportsAttachments`; §0.3 step 2).
- **Notification channels:** `account` ("Account", IMPORTANCE_DEFAULT) for the sign-in
  completion notice (S7 step 5), and the four sounding + four `_quiet` session channels
  of S3.3, next to the channels listed in DS §10.5.
- **Copy marked *(Android)*** in this file, for `strings.xml`.

## 12. Global acceptance (all screens of this spec)

- [ ] `grep -R "apiKey\|accessToken\|authToken" app/src/main` finds no persistence call
      (DataStore, SharedPreferences, files) taking a secret value; `catalog-cache.json`
      contains no `apiKey`, `configValues` or `baseUrl` after a full Providers session.
- [ ] `adb logcat -d | grep -i <last 6 chars of a test key>` is empty after S5, S6 and S7.
- [ ] Every screen works at system font scale 2.0 × in-app size 20 without clipped text
      (S25U Display → Font size max).
- [ ] Predictive back on S2–S6 (S6 and the folder browser as compact/medium destinations)
      scrubs the shared-axis pop; on sheets it collapses the sheet; S6 with edits asks
      before discarding; inside the folder browser back first walks the visited folders
      (instant pops) before the seekable close.
- [ ] No snackbar, error line or dialog anywhere in this spec shows an ANSI escape
      remnant (`[32m`, `[0m`, `\u001B`) from a RUN_COMMAND's output.
- [ ] The helper's room secret never appears in `adb logcat`, DataStore files or
      `catalog-cache.json` (grep for the 32-hex value seen in `ps` in Termux).
- [ ] Every string comes from `strings.xml`; desktop strings match character for character
      ("..." vs "…" included: "Loading providers...", "Checking…", "Export…").
- [ ] Light and dark, dynamic colour on and off: Accessibility Scanner reports no contrast
      or touch-target issue on S1, S4, S5, S8 and S9.
- [ ] Tab S7+ portrait (medium) and landscape (expanded) screenshots for S1, S4/S5, S6,
      S8, S9 are in `screenshots/`.

## 13. Open questions

1. **Disconnect / sign out without the helper.** The CLI has no logout or remove-provider
   command (`apps/cli/src/main.ts` commands: auth, config, plugin, skill, connect, mcp,
   doctor, hook, schedule, hub, dashboard, update, version, kanban), so fallback mode hides
   Disconnect. Options: a port-side `cline auth --remove <id>` (needs a port change), or an
   app-side edit of `providers.json` through RUN_COMMAND (fragile). Decide before shipping
   fallback mode as the default anywhere.
2. **"Add model" on a provider.** The desktop's `update_provider_models` is not dispatched
   by the shipped dashboard, so S5 omits the (+) button and relies on S8's free-text
   model id. Confirm this is acceptable or add the command to the dashboard in the port.
3. ~~Cancel pattern for `cline auth cline`.~~ **Resolved from source** (S7.3 step 7):
   the live argvs are `node …/cline-node-wrapper.cjs auth cline` and
   `<bun> …/current/index.js auth cline`; cancel and timeout use
   `pkill -f "index.js auth (cline|openai-codex|oca)$"`. The S7.7 cancel acceptance
   (process gone, `providers.cline.updatedAt` unchanged) confirms it on the S25U; a
   future launcher change that renames `index.js` must update this pattern.
4. **Foreground RUN_COMMAND result.** Verify that Termux 0.118.3 delivers the result
   `PendingIntent` (with `exitCode`) when a `BACKGROUND=false` session's process exits, and
   whether the session stays open ("press Enter"). S7 does not depend on it (resume +
   `providers.json` is the primary signal), but the background "Signed in to Cline"
   notice does.
5. ~~Dashboard boots the Hub.~~ **Resolved conservatively** (§0.3 step 1): when the
   user pressed "Stop kernel" in this app session, Providers runs in fallback mode and
   starts nothing; otherwise the KernelCoordinator starts (and adopts: token, wake lock)
   the Hub first, and re-adopts after the helper is ready. Remaining owner question
   (docs OQ 12 on the Hub's lifetime): should a Hub stopped from Termux, not from the app,
   also be treated as intentionally stopped?
6. **Helper grace period.** Set to **5 s** (§0.3 step 3): enough to move between
   Providers and Account without restarting Bun, short enough that the secret-protected
   dashboard does not linger. KC says "stop it when leaving settings"; confirm 5 s or 0.
7. **Shared storage via `termux-setup-storage`.** Needs the foreground-runner trick from
   S7; confirm on the S25U that the Termux storage permission dialog appears when Termux is
   brought to the front by the app.
8. **Telemetry fallback write.** In fallback mode Telemetry is read-only. A port-side
   `cline config telemetry on|off` would make it writable without the helper.
9. **Catalog freshness for the model picker.** S8 never starts the helper on open, so a
   user who never visits Providers sees a catalog up to one app session old. Confirm, or
   allow S8 to acquire the helper in the background on first open per app launch.
10. **Model id for `cline auth --provider cline --apikey`.** The CLI requires `--modelid`;
    S7 passes the remembered model or `anthropic/claude-sonnet-5`
    (`CLINE_DEFAULT_MODEL_ID`). Confirm the Cline provider accepts that id for API-key
    accounts.
11. **Reusing a user-started dashboard.** A dashboard on 8787 without a room secret is
    never reused (§0.3 step 1.4, `PortInUse`), because any app on the phone could read
    plaintext keys through it. Should there be an explicit opt-in (Kernel settings) for
    users who run their own dashboard on purpose?
12. **Kernel-contract update.** KC "On-demand settings helper" says "no roomSecret on
    loopback" and "poll /health up to 20 s"; this spec deviates (per-launch room secret,
    45 s budget when the Hub was down, Host/Origin headers also on `/health`, which is not
    a public dashboard route). Fold these into `docs/02-kernel-contract.md` when the owner
    accepts them.
