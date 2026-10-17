# Screen spec: Onboarding and kernel

Status: design-phase contract, 2026-10-03 (revised the same day after a three-reviewer
pass; every change was checked against the sources named in 0.1). Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`).
Builds on `design/design-system.md` (cited as **DS §n**) and must not redefine anything in it.
Where this group needs a component, symbol or dependency the design system does not have yet,
section 0.7 gives it as a paste-ready DS row. Those rows must be added to the design system
before implementation starts (open question 1).

**Ownership.** This spec is the single definition of three things other specs also show:
the kernel state model (`KernelState`, `DownReason`, 0.3), its UI projection `KernelGate`
(F.6), and the kernel-down surfaces `KernelBanner` / `KernelStatePanel` with their copy (0.7,
F.2). The Home/Chat, Sessions, Composer and Changes specs bind to those names. Section 0.10
lists the exact changes each sibling spec needs so that one state machine and one strings
table cover the kernel.

Screens in this group:

| # | Screen | Kind | Tier (docs/04-screens.md) |
| --- | --- | --- | --- |
| A | Kernel setup (first run), including the Welcome step | full-screen flow step | 1 |
| B | Install Termux state | full-screen state | 1 |
| C | Connect a model (first run), including the Done step | full-screen flow step | 1 |
| D | One UI battery checklist ("Keep the kernel running") | pushed page, also an onboarding step | 2 |
| E | Kernel status sheet, with the Hub update and diagnostics sub-surfaces | modal bottom sheet | 2 |
| F | "Termux asleep / Hub down" state | in-place state on Home, Chat, the drawer and the composer | 1 |

Tiers order the work. They do not cut scope: all six ship.

---

## 0. Shared foundations for this group

### 0.1 Ground truth read for this spec

App repository (`~/workspace/cline-termux-app`): `docs/README.md`, `docs/01-architecture.md`,
`docs/02-kernel-contract.md`, `docs/03-feature-matrix.md`, `docs/04-screens.md`,
`docs/05-open-questions.md`, `docs/06-toolchain.md`, `docs/08-hub-fixtures.md`,
`design/design-system.md`, `CLAUDE.md`, and the existing kernel code:
`kernel/KernelCoordinator.kt`, `kernel/termux/{ClineCommands,TermuxCommand,TermuxRunner,TermuxExecutor}.kt`,
`kernel/hub/{HubDiscovery,HubTokenStore}.kt`, `AndroidManifest.xml`.

Desktop (`~/workspace/cline-termux-v3-phone/apps/examples/desktop-app/webview`, cited as `webview/`):

| File | What this spec copies from it |
| --- | --- |
| `components/views/onboarding/onboarding-view.tsx` | The whole first-run flow: steps `welcome → connect → (github) → (import) → done`; WelcomeStep copy ("Cline", "Build software your way", body, "Get started", footer); ConnectStep ("Set up Cline", the two `SetupOptionCard`s, the Cline benefits list, "Recommended" badge, "Sign in" / "Waiting for browser..." / "Cancel" / "Sign up", "Use a Cline API key", "Find your key", "Use your own API key", the provider `Select`, API-key field, "Get a <Provider> API key", "Connect" / "Connecting...", every error prefix, "Skip"); `sortProvidersForApiKeySetup` and `PREFERRED_PROVIDER_ORDER`; `rememberProviderSelection`; DoneStep ("You're all set", "<Provider> is connected." / "Your Cline account is connected.", ClinePass card, "Start building"); ImportHistoryStep "Skip for now"; the cancel-attempt counter in `signInWithCline`; the URLs `CREATE_ACCOUNT_URL = "https://app.cline.bot"` (line 54) and `CLINE_PASS_SUBSCRIBE_URL = "https://app.cline.bot/onboarding/individual-plan"` (line 56, opened by "Get ClinePass", line 1085); `clineBusy = signingIn \|\| clineKeySaving` (line 465), which disables only the Cline card's "Sign in" button and the "Use a Cline API key" toggle (lines 621, 675); the `providersError` branch that replaces the provider `Select` with the alert line (lines 772–805) |
| `components/views/chat/welcome-setup-notice.tsx` | Entry point into Connect from Home ("Connect a model to start building"), already specified in DS §4.2 |
| `app/page.tsx` (lines ~314–385, 640–652, 2500–2525, 2685–2697) | When onboarding shows (`hasCompletedOnboarding()`); the mount effect "Returning users get the catch-up once; first-run users see onboarding instead, and completing it marks the catch-up as seen"; `completeOnboarding` = `markOnboardingCompleted()` + `markCurrentWhatsNewSeen()` + new thread (lines 640–648); `handleOpenSetup` (re-enter onboarding at `connect`); the boot gate (spinner + "Desktop backend unavailable" / "Connecting..." / "Loading..." + error detail) |
| `lib/onboarding.ts` | Storage key `cline.code.onboarding.v1` `{completedAt}`; unreadable storage counts as completed; reset event |
| `components/agent-sidebar.tsx` (lines 143–152, 830–895) | Hub hover card: product name, "Version <x>" / "Version unavailable", status dot (emerald when connected, muted otherwise), "Cline Hub @<port>" / "@unknown", "Cline Hub is not connected." in destructive text; `hubPort()` |
| `components/hub-update-required-dialog.tsx`, `hub-update-required-helpers.ts` | The file holds **two** dialogs. (1) `unsupported_protocol` / `build_mismatch` (the Hub is newer than the app): title "Cline Hub was updated", body "Another Cline installation updated the shared Cline Hub (core <x>), and it no longer matches this app. …", actions "Later" (dismiss) and "Update and restart" / "Checking for updates…" / "Restarting…" / "Try again"; only this dialog is dismissible, and `isPersistableHubMismatchKey` never persists an `unsupported_protocol` dismissal. **E.9 copies this one.** (2) `outdated_hub` (the app is newer and the Hub was kept alive for other clients): title "Cline Hub update required", body "Cline needs a newer Cline Hub, but the running one is still serving {describeOutdatedHubSessions}." + "Update Now stops that Hub and interrupts its sessions. Quit Cline closes this app and leaves the Hub running, so you can update later.", actions "Quit Cline" / "Update Now" (→ `hub_upgrade`, `HUB_UPGRADE_TIMEOUT_MS = 60_000`, "drain wait + graceful retire + fresh daemon startup"), not dismissible; failure hint "Updating the Cline Hub failed. Try again, or run 'cline doctor fix' in a terminal." **E.7's Restart confirm borrows this one's sentences.** |
| `components/views/chat/chat-messages.tsx` (lines 781–789) | Inline transport line: `Loader2` spinner + "Connecting chat..." / "Reconnecting chat..." / "Chat backend unavailable" (the spinner is drawn for all three), hidden while the error banner shows (`!shouldShowErrorBanner`) |
| `components/views/settings/provider-list-view.tsx` (lines 830–890) | "Sign in to <Provider>", "Connects through your browser. No API key needed.", "Sign in with browser", "Confirm this code in your browser:", "Use an API key instead" |
| `components/views/settings/settings-view.tsx` (lines 1122–1142) | "New user experience" / "Replay the first-run experience new users see when they open Cline for the first time." / "Replay" (the Settings entry back into this flow) |
| `components/views/settings/about-view.tsx`, `export-diagnostics-dialog.tsx` | Version/updates vocabulary, the diagnostics privacy sentence |
| `components/views/settings/add-provider.tsx` | Base URL placeholder "https://api.example.com/v1", "Show API key" / "Hide API key" |
| `lib/provider-key-urls.ts` | `PROVIDER_API_KEY_URLS`, `CLINE_DASHBOARD_URL` |
| `lib/provider-connection.ts` | `OAUTH_PROVIDER_IDS`, `OAUTH_LOGIN_TIMEOUT_MS = 15 min`, `isProviderConnected` = `apiKey` non-blank **or** `oauthAccessTokenPresent` **or** `enabled && configured === true` (the third clause covers keyless local and cloud-credential providers) |
| `lib/model-selection.ts` | `cline.code.model-selection.v1` `{lastProvider, lastModelByProvider}` |
| `lib/whats-new.ts` | `WHATS_NEW_STORAGE_KEY = "cline.code.whats-new.v1"` (the "seen" release id) |
| `hooks/chat-session/helpers.ts` (lines 269–317) | Credential-failure actions "Sign in to Cline" / "Open API providers" (state F hands off to these) |
| `hooks/use-oauth-user-code.ts` | Device-code display contract (not reachable on Android, see C.10) |
| `lib/desktop-client.ts` | Transport states `connecting \| reconnecting \| connected \| unavailable`; a failed connect keeps retrying (line ~548: rather than "parking the UI on 'unavailable' until the app is relaunched") |

Shared UI package (`~/workspace/cline-termux-v3-phone/sdk/packages/ui`; the brief's `packages/ui/src`
path does not exist, DS line 12): `components/agent-welcome-hero.tsx` (variants `full | grid-only | bot-only`,
layouts `full-bleed | wide-grid`, `aria-hidden`).

Kernel side (`~/workspace/cline-termux-v3-phone`): `apps/cli/src/commands/auth.ts` (quick setup
validation messages, "Provider configured: <id> (<model>)", "You are now logged in to <id>",
OAuth via the `open` package), `apps/cli/src/commands/hub.ts` (`hub start` prints the ws URL;
`hub status` prints `{running, url, pid, startedAt, uptime, cliVersion, coreVersion}`; `hub stop`
prints `{stopped}` after `clearHubDiscovery`; `hub upgrade --wait N` drains, polls
`localHubHasNoActiveSessions` once a second for up to N s, un-drains and fails with the stderr
"Hub is still serving sessions after the wait window; not replacing it. Re-run with a longer
--wait, or finish the sessions first." when the Hub is still busy, otherwise stops it and runs
`ensureDetachedHubServer`, then prints `{upgraded:true,url}`; it has no force option, unlike the
desktop sidecar's `upgradeManagedHub({force:true})`), `apps/cli/src/commands/doctor.ts`
(`DoctorStatus` JSON), `release/install-cline-termux.sh` and `release/build-termux-release.sh`
(the `VERSION` file at `$PREFIX/opt/cline-termux/current/VERSION` with `release=`, `tag=`, `cline=`),
`README.md` (the public installer one-liner), `sdk/packages/core/src/services/llms/provider-settings.ts`
(`providers.json` schema: `settings.apiKey`, `settings.auth.{accessToken,refreshToken}`,
`settings.aws`, `settings.gcp`, `settings.sap`), `sdk/packages/shared/src/hub.ts`
(`describeOutdatedHubSessions`, `isHubProtocolCompatible`: missing min/max fields default to the
Hub's own `protocolVersion`), `sdk/packages/shared/src/parse/time.ts` (`formatUptime`: "1d 2h",
"2h 14m", "4m 12s", "9s"), `sdk/packages/shared/src/providers/defaults.ts` (`CLINE_DEFAULT_MODEL_ID`).

Hub internals this spec relies on (`sdk/packages/core/src/hub/`):
`server/hub-websocket-server.ts` (`/health` body `{ok, protocolVersion, minClientProtocolVersion,
maxClientProtocolVersion, coreVersion, buildId, buildEpochMs, draining, host, port, url}`, lines
498–510; `/version` = `{protocolVersion, minClientProtocolVersion, maxClientProtocolVersion,
capabilities, coreVersion, buildId, buildEpochMs, pid, startedAt}` with **no `hubId`**, lines
394–404 and 542–547; `beginClose()` calls `websocket.terminate()` on every socket, so a client
never receives a close frame on shutdown); `server/browser-websocket.ts` (a socket close sends
`client.unregister` for every client id it registered); `server/hub-server-transport.ts`
(`client.unregister` → `detachClientFromSessions`, lines 804–807 and 1161–1176: removes the
client from every session's participants and drops a session's live state when none remain);
`client/index.ts` (`summarizeHubSessionActivity`, lines 1188–1219: a session is active while it
has at least one participant, regardless of status; `localHubHasNoActiveSessions`);
`daemon/index.ts` (`HUB_STARTUP_TIMEOUT_MS = 15_000`, line 65: `ensureDetachedHubServer` throws
"Timed out after 15000ms waiting for detached hub startup." at line 752 while the detached daemon
keeps booting; the daemon log is `~/.cline/data/logs/hub-daemon.log`, line 120).

Voice app (`~/workspace/cline-termux-voice/android/.../dev/cline/voice`): `ui/Components.kt`
(`PageScaffold`, `TextInput` with `secret`), `ui/settings/Prefs.kt` (`PrefRow`, `PrefHeader`,
`StateLine`, `RowProgress`, `OpenOutside`, `PrefFooter`), `ui/SettingsScreen.kt` (battery and
notification rows, `rememberSystemChecks` re-read on `ON_RESUME`), `ui/settings/TermuxPage.kt`
(connect form with live-region status line and discard confirm), `ui/settings/WirelessRecovery.kt`
(expandable fix section). Copied code carries the DS header comment
`// Adapted from cline-termux-voice (same owner): <original path>`.

### 0.2 Where these screens sit in the app

```
MainActivity (singleTask)
 └─ ClineTheme
     └─ RootNavHost
         ├─ graph "onboarding"            shown while onboarding.completedAt == null
         │    ├─ onboarding/welcome        (A, step 0)
         │    ├─ onboarding/kernel         (A)
         │    ├─ onboarding/reliability    (D, onboarding variant)
         │    ├─ onboarding/connect        (C)
         │    └─ onboarding/done           (C, Done step)
         ├─ kernel/install-termux          (B) full-screen, replaces the shell while Termux is missing
         ├─ kernel/repair                  (A, repair variant, "blocking") replaces the shell while a prerequisite is broken
         ├─ setup/connect                  (C, re-entry variant from the setup notice / Settings)
         ├─ setup/done                     (C, Done step of the re-entry variant)
         ├─ kernel/reliability             (D, standalone variant)
         └─ shell (AppShell: drawer / rail / panes, DS §5)
              ├─ kernel/repair?focus=…&pushed=true   (A, repair variant, "pushed") on top of the shell, from a feature or the Degraded banner
              ├─ KernelStatusSheet         (E) shell-level ModalBottomSheet state, not a route
              ├─ HubUpdateRequiredDialog   (E.9) shell-level dialog state
              └─ KernelStatePanel / KernelBanner / composer readOnly / KernelChip   (F)
```

The Kernel status sheet (E) exists only inside the shell. Surfaces outside it (A during
onboarding, both repair variants, B) never open E: where they need diagnostics they render the
shared `DiagnosticsBlock` (0.7) inline, driven by their own ViewModel calling the same
`KernelDiagnostics` use case that E uses. This keeps `KernelStatusViewModel` shell-scoped.

Start destination is decided once, synchronously after the DataStore read (the splash may stay
up for that read only, `setKeepOnScreenCondition { !prefsLoaded }`; DS §10.4 forbids holding it
for the kernel probe):

1. `onboarding.completedAt == null` → `onboarding/welcome`.
2. else `PackageManager` says Termux is missing or disabled → `kernel/install-termux`.
3. else `shell` (Home). The shell starts the connect sequence; B and the blocking repair
   variant of A replace the shell when the coordinator reports them (0.4, A.3); the pushed repair
   variant opens on top of it.

If DataStore cannot be read (corrupt file), treat onboarding as completed, as the desktop does
for unreadable storage (`lib/onboarding.ts`: "never trap the user in the first-run flow").

### 0.3 Kernel state model (replaces `KernelCoordinator.KernelState`)

The existing sealed interface (`kernel/KernelCoordinator.kt`: `Unknown, TermuxMissing,
PermissionMissing, HubDown(clineVersion, lastError), PortTooOld(clineVersion), Starting(step:
String), Connecting, Ready, Degraded(info, connection), Failed(reason, recoverable)`) is replaced by
the following. It is the app's only kernel state model: every screen in this group, and every
sibling spec through `KernelGate` (F.6), derives from it and from nothing else.
`KernelCoordinator.state` is the single source: `StateFlow<KernelState>`. Old names map as
follows, so code and sibling specs written against them can be converted mechanically:
`Degraded(_, Reconnecting)` → `Reconnecting`; `Degraded(_, Unavailable)` and `Failed(reason)` →
`HubDown(StartFailed | Unreachable, lastError = reason)`; old `HubDown` → `HubDown(Asleep)`;
`PortTooOld(clineVersion)` → `PortTooOld(release)`; `Starting(String)` → `Starting(StartStep, …)`.

```kotlin
sealed interface KernelState {
    data object Unknown : KernelState                                    // before the first probe
    data class TermuxMissing(val disabled: Boolean) : KernelState        // <queries> lookup failed, or ApplicationInfo.enabled == false
    data class TermuxTooOld(val versionName: String) : KernelState       // versionName < 0.109
    data object PermissionMissing : KernelState                          // com.termux.permission.RUN_COMMAND not granted
    data class ExternalAppsBlocked(val errmsg: String) : KernelState     // RUN_COMMAND err != -1, errmsg mentions allow-external-apps
    data object TermuxNotSetUp : KernelState                             // $PREFIX/bin/cat itself is missing (bootstrap never ran)
    data object PortMissing : KernelState                                // VERSION file absent
    data class PortTooOld(val release: String?) : KernelState            // release < 3.0.68-termux.2, or the daemon-log check finds entry.js (0.4)
    data class Starting(val step: StartStep, val startedAtElapsedMs: Long) : KernelState
    data class Connecting(val info: HubInfo) : KernelState               // ws opening + client.register
    data class Ready(val info: HubInfo, val hub: HubLiveStatus?) : KernelState
    data class Reconnecting(val info: HubInfo, val attempt: Int) : KernelState   // was Degraded(Reconnecting)
    data class Restarting(val info: HubInfo, val phase: RestartPhase) : KernelState  // E.7 restart in progress
    data class Draining(val info: HubInfo) : KernelState                 // /health draining or hub.drain_changed
    data class ProtocolUnsupported(val info: HubInfo) : KernelState      // isHubProtocolCompatible(...) == false
    data class AuthFailed(val info: HubInfo) : KernelState               // 401 after one token re-read
    data class HubDown(val reason: DownReason, val lastError: String?, val doctor: DoctorSummary?) : KernelState
}

enum class StartStep { CheckingTermux, CheckingKernel, StartingHub, KeepingAwake, ReadingCredentials, Connecting }
enum class RestartPhase { CheckingSessions, Disconnecting, Replacing, Starting, Reconnecting }
enum class DownReason { Asleep, Stopped, StoppedInTermux, Disconnected, StartFailed, StartTimedOut, Unreachable, CrashLoop }

data class HubInfo(                       // existing fields plus:
    val url: String, val port: Int, val pid: Int?, val startedAt: String?,
    val coreVersion: String?, val buildId: String?, val protocolVersion: String?,
    val minClientProtocolVersion: String?, val maxClientProtocolVersion: String?,
    val hubId: String?,                   // from production.json only (never from /version); "More details" row
    val release: String?,                 // from VERSION, e.g. "3.0.68-termux.2"
    val cliVersion: String?,              // from VERSION cline=, e.g. "3.0.68"
)
data class HubLiveStatus(val draining: Boolean, val activeRpcTurns: Int, val pendingRuns: Int, val lastSequence: Long, val idle: Boolean) // hub.status reply
```

`Restarting` exists so an intended restart never looks like an outage: while the coordinator is
in it, a socket drop, a refused `/health` or a pid change do **not** produce `Reconnecting`,
`HubDown(Asleep)` or a `CrashLoop` count, and the KernelBanner is not shown (F.4). It ends in
`Ready` (success) or in the state the final probe finds (E.7).

`DownReason` decision table (the coordinator owns it; screens only render it):

| Reason | When |
| --- | --- |
| `Asleep` | `/health` refused (ECONNREFUSED or timeout) **and** this app process saw the Hub `Ready` earlier **and** `kernel.userStopped == false` **and** the discovery check (below) finds `production.json` still present (an OS kill leaves it behind), or cannot run because RUN_COMMAND itself timed out |
| `Stopped` | `kernel.userStopped == true` (the user tapped "Stop kernel"; persisted so a relaunch does not auto-start) |
| `StoppedInTermux` | `/health` refused after `Ready` and the discovery check finds `production.json` **absent**: a graceful stop removed it (`cline hub stop` → `clearHubDiscovery`, `hub upgrade`, `doctor fix`, or a SIGTERM shutdown through `beginClose` → `clearHubDiscoveryIfOwned`). `kernel.userStopped` is not set, so auto-start on the next resume stays allowed |
| `Disconnected` | the user tapped "Disconnect"; `/health` still answers |
| `StartFailed` | the 60 s start budget (H2) ran out after `cline hub start` exited non-zero, `/health` never answered, and the daemon-log check (0.4) did not find the entry.js failure |
| `StartTimedOut` | no RUN_COMMAND result within 60 s (`TermuxUnavailable.TimedOut`), followed by `cline doctor --json` |
| `Unreachable` | `/health` refused on a launch/resume when auto-start is not allowed and nothing else applies |
| `CrashLoop` | two auto-starts within 5 minutes each followed by the Hub dying within 60 s; auto-start is suspended until the user taps "Start kernel" |

**Discovery check** (run once per drop, after the reconnect attempts in 0.4 fail and `/health`
is refused): RUN_COMMAND `readDiscovery`. Exit 1 with "No such file" → `StoppedInTermux`;
exit 0 → `Asleep` (the record names a dead pid); RUN_COMMAND timeout → `Asleep`. The Hub never
sends a WebSocket close frame on shutdown (`beginClose()` terminates every socket), so close
codes carry no information and are not used. Known imprecision: if another Cline client (the
TUI) starts up after an OS kill, its own start path may clear the stale record first
(`daemon/index.ts` line ~585) and the app then says `StoppedInTermux` instead of `Asleep`; that
client is starting a new Hub at that moment, so the passive probe (0.4) reconnects within 10 s.

### 0.4 Connect sequence as these screens see it

The order follows `docs/02-kernel-contract.md` "CONNECT SEQUENCE", with the prerequisite probe
made explicit so the setup screen can show one row per check:

| Step | Call (exact) | Timeout | Success | Failure → state |
| --- | --- | --- | --- | --- |
| P1 Termux present | `PackageManager.getPackageInfo("com.termux", 0)` (needs `<queries>`) | sync | `versionName >= 0.109` and `applicationInfo.enabled` | `TermuxMissing(disabled)` / `TermuxTooOld` |
| P2 Permission | `ContextCompat.checkSelfPermission(ctx, "com.termux.permission.RUN_COMMAND")` | sync | granted | `PermissionMissing` |
| P3 Kernel probe | RUN_COMMAND background `/data/data/com.termux/files/usr/bin/cat /data/data/com.termux/files/usr/opt/cline-termux/current/VERSION` | 10 s | exit 0, parse `release=` and `cline=` | `err != -1` and `errmsg` contains `allow-external-apps` → `ExternalAppsBlocked`; `err != -1` and `errmsg` names the `cat` path (err 150, "not found") → `TermuxNotSetUp`; exit 1 with `No such file` → `PortMissing`; `release < 3.0.68-termux.2` → `PortTooOld(release)` |
| H1 Health | `GET http://127.0.0.1:25463/health` (1 s connect, 2 s read) then `GET /version` | 3 s | `ok == true` and a 1:1 port of `isHubProtocolCompatible(health, "v1")` returns compatible (a missing `minClientProtocolVersion` / `maxClientProtocolVersion` defaults to `protocolVersion`; an unparsable `protocolVersion` counts as incompatible) | refused → H2 when auto-start is allowed, else `HubDown(Unreachable/Stopped)`; incompatible → `ProtocolUnsupported`; `draining == true` → `Draining` after connect |
| H2 Start | RUN_COMMAND background `$PREFIX/bin/cline hub start` (`ClineCommands.hubStart()`); last stdout line is the ws URL | 60 s **budget from the tap** or from the auto-start (the RUN_COMMAND timeout and the polling below share it) | exit 0, then `/health` ok; **or** non-zero exit followed by `/health` ok within the budget | Non-zero exit is "still starting", not a failure: `ensureDetachedHubServer` gives up after 15 s ("Timed out after 15000ms waiting for detached hub startup.") while the daemon keeps booting, which a cold S25U start (8–15 s, more with Termux in the background) regularly hits. So on any non-zero exit the state stays `Starting(StartingHub)` and the coordinator polls `GET /health` every 1 s until the budget expires; success → H3. Budget expired with no answer → daemon-log check (below), then `HubDown(StartFailed, last 400 chars of stderr)`. No RUN_COMMAND result within the budget → RUN_COMMAND `cline doctor --json` (30 s) → `HubDown(StartTimedOut, doctor)` |
| H3 Wake lock | RUN_COMMAND `$PREFIX/bin/termux-wake-lock` when `kernel.keepAwake == true` | 10 s | exit 0 | ignored (logged as a warning row in E) |
| H4 Token | cached `(pid, startedAt)` vs `/version.pid` / `/version.startedAt` (`/version` carries no `hubId`; fixture 01/05 confirms pid, startedAt, hubId and authToken all change together on restart, so the pair is sufficient); on mismatch RUN_COMMAND `cat /data/data/com.termux/files/home/.cline/data/locks/hub/production.json` | 20 s | parse `HubDiscoveryRecord`; seal `authToken` with `SecretBox` into DataStore; keep `hubId` for E's "More details" | parse failure → retry once after 1 s (the record is written right after the port opens), then `HubDown(StartFailed, "The Cline Hub started but its credentials could not be read.")` |
| H5 Socket | OkHttp `ws://127.0.0.1:25463/hub`, header `Sec-WebSocket-Protocol: cline-hub-auth.<token>`; then `client.register {clientId, clientType:"android", displayName:"Cline Termux", transport:"native", actorKind:"client", capabilities:[{name:"approval.respond"},{name:"tool_executor.askQuestion"}], metadata:{version, platform:"android", pid:0}, workspaceContext, protocolVersion:"v1"}`, then `stream.subscribe {clientId, sinceSequence}` and `hub.status {}` | 10 s | `Ready(info, hub.status)` | HTTP 401 → re-read token (H4) once → second 401 → `AuthFailed`; handshake protocol error (no subprotocol echo, open question 1) → `HubDown(StartFailed, "The Cline Hub refused the connection handshake.")` |

`StartStep` labels shown during H2–H5 are in the strings table (section 9, `kernel_step_*`).

H1 note: the `/health` range fields were verified in `hub-websocket-server.ts` lines 498–510. The
feature matrix row "Kernel liveness and version probe" lists an abbreviated body (`ok,
protocolVersion, buildId, coreVersion, draining, url`); this spec's field list is the complete
one and must not be "corrected" toward the matrix (the matrix row is regenerated with the full
list via `scripts/gen-docs.py` when the docs are next rebuilt).

The existing coordinator probes `cline --version` (2.5 s) before `hub start`. Replace that probe
with P3 (`cat VERSION`, one RUN_COMMAND that answers external-apps, bootstrap, install and version
at once). **P3's `release=` comparison is the PortTooOld detector.** On the .1 port the
`Module not found ".../entry.js"` error is written by the re-exec'd daemon to
`~/.cline/data/logs/hub-daemon.log`; the parent `cline hub start` only prints the 15 s timeout,
so a match on `hub start` output is opportunistic (check it, but never rely on it). The real
fallback, for a VERSION file without a `release=` line, is the **daemon-log check**: only on the
way to `StartFailed` or `StartTimedOut`, RUN_COMMAND `tailDaemonLog` (0.5) and match the literal
`entry.js` (the .1 failure line is `Module not found ".../entry.js"`; the S25U log of the same
failure also held `CouldntReadCurrentDirectory` lines, which alone are not specific enough to
count); a match → `PortTooOld(release)`. The log tail is matched in memory and discarded; it is
never displayed, logged or put into diagnostics (open question 16).

Version comparison: parse `release=` as `X.Y.Z-termux.N`. Compare `X.Y.Z` as semver, then `N`
numerically. Minimum: `3.0.68-termux.2` (`MIN_PORT_RELEASE` constant, one place). A `release`
that does not parse (`cline=` only) counts as too old.

**Auto-start policy** (on `ON_START` of `MainActivity` and on every resume after more than 5 s in
the background): run P1–H5 with auto-start allowed when `kernel.userStopped == false` and the
state is not `CrashLoop`. While the app is in the foreground and the socket drops, the client
reconnects with the NodeHubClient backoff (`min(250 ms × 2^attempt, 5 s)`, 50 % jitter,
DS §1.6 "reconnecting"). After 3 failed attempts it probes `/health`; refused → the discovery
check (0.3) → `HubDown(Asleep)` or `HubDown(StoppedInTermux)`, **without** auto-starting (the
user sees why and taps "Start kernel"; this avoids a silent restart loop while a turn is on
screen). RUN_COMMAND is only ever issued while the app is in the foreground or from its own
foreground service (`docs/02-kernel-contract.md`).

**Passive probe (no auto-start).** Like the desktop transport, which keeps retrying "rather than
parking the UI on 'unavailable'", the app never parks silently: while `MainActivity` is at least
`STARTED` and the state is `HubDown(Asleep | StoppedInTermux | Stopped | Unreachable |
StartFailed | StartTimedOut)`, the coordinator issues `GET /health` every **10 s** (plain HTTP on
loopback, no RUN_COMMAND, no `hub start`). When it answers, the coordinator runs H1 → H4 → H5
and lands in `Ready` (a Hub started from the TUI in split screen, or one that finished a slow
boot after the 60 s budget, is picked up without a tap). The probe stops on `ON_STOP`, in
`CrashLoop` (the user must decide), in `Disconnected` (the user chose to be disconnected) and
while any RUN_COMMAND of this group is in flight. `Stopped` is included because `userStopped`
only forbids the app from *starting* the Hub, not from using one someone else started.

**Degraded mode, permission revoked but Hub alive:** if P2 fails but H1 answers and the cached
token is still valid for the current `(pid, startedAt)`, connect anyway (`Ready`) and show
the KernelBanner variant "Cline can't run commands in Termux" (section F.3). Anything that needs
RUN_COMMAND (sign-in, provider setup, workspace browsing, start/stop) opens the **pushed** repair
variant (A.3) when invoked, on top of the screen the user was on.

### 0.5 RUN_COMMAND calls this group issues

All go through `TermuxExecutor.run` with `RUN_COMMAND_BACKGROUND = true`, workdir
`/data/data/com.termux/files/home`, unless marked *foreground*.

| Id | Executable + args | Timeout | Used by |
| --- | --- | --- | --- |
| `probeVersion` | `$PREFIX/bin/cat $PREFIX/opt/cline-termux/current/VERSION` (new `ClineCommands.readPortVersion()`) | 10 s | A, B (after install), E |
| `hubStart` | `$PREFIX/bin/cline hub start` | 60 s (shared start budget, H2) | A, E, F |
| `hubStop` | `$PREFIX/bin/cline hub stop` (prints `{"stopped":true}`) | 30 s | E |
| `hubUpgrade` | `$PREFIX/bin/cline hub upgrade --wait 30` (prints `{"upgraded":true,"url":...}`) | **75 s** = `--wait` + 45 s (stop ≤ 15 s, `ensureDetachedHubServer` ≤ 15 s, 15 s margin). Change both numbers together: `HUB_UPGRADE_WAIT_S = 30`, `HUB_UPGRADE_TIMEOUT_S = HUB_UPGRADE_WAIT_S + 45` | E.7, E.9 |
| `wakeLock` / `wakeUnlock` | `$PREFIX/bin/termux-wake-lock` / `termux-wake-unlock` | 10 s | A, E |
| `readDiscovery` | `$PREFIX/bin/cat $HOME/.cline/data/locks/hub/production.json` | 20 s | A, E, F (H4 and the discovery check) |
| `tailDaemonLog` | `$PREFIX/bin/tail -c 4000 $HOME/.cline/data/logs/hub-daemon.log` (matched for `entry.js`, then discarded) | 10 s | A, F (daemon-log check, 0.4) |
| `statProviders` | `$PREFIX/bin/stat -c %Y $HOME/.cline/data/settings/providers.json` (prints the mtime in epoch seconds; coreutils is part of the Termux bootstrap; exit 1 "No such file" = no providers yet). Carries no secrets | 10 s | C (sign-in completion poll) |
| `readProviders` | `$PREFIX/bin/cat $HOME/.cline/data/settings/providers.json`. **Issued at most once per trigger, never on a timer** (triggers: entering C, `ON_RESUME` of C, an auth action finishing, `statProviders` reporting an mtime later than `onboarding.signIn.startedAt`) | 10 s | C, Home setup notice |
| `authProvider` | `$PREFIX/bin/cline auth --provider <id> --apikey <key> --modelid <model> [--baseurl <url>]` | 30 s | C |
| `authClineInteractive` | *foreground* `$PREFIX/bin/cline auth cline`, `BACKGROUND=false`, `SESSION_ACTION=0`, label "Sign in to Cline" | none (fire and poll) | C |
| `cancelClineAuth` | `$PREFIX/bin/pkill -f "index.js auth cline"` (pattern to verify, open question 4) | 10 s | C |
| `doctor` | `$PREFIX/bin/cline doctor --json` | 30 s | A, E, F |
| `doctorFix` | `$PREFIX/bin/cline doctor fix --json` | 60 s | E |

`$PREFIX` = `/data/data/com.termux/files/usr`, `$HOME` = `/data/data/com.termux/files/home`
(RunCommandService expands neither, `TermuxPaths`).

`cline hub status` is deliberately not called: it reads the same discovery file and probes the
same `/health` + `/version` the app already probes, and when the Hub is down it returns
`running:false` with no pid or uptime. `cliVersion` comes from P3's `cline=` line instead.

Hub-protocol calls this group issues (not RUN_COMMAND; over the H5 socket): `hub.status {}` (E
refresh), `session.list {limit:500}` (E.7 pre-checks; feature matrix "Session list"),
`client.register`, `stream.subscribe` (H5).

### 0.6 Secrets and logging rules (MUST)

1. The Hub token is only ever held sealed in DataStore (`HubTokenStore`) and in memory. It is never
   shown in any UI in this group, never copied, never logged, never part of diagnostics.
2. `providers.json` contains plaintext API keys and OAuth tokens. Its stdout is parsed immediately
   into a redacted model and the raw string is dropped:
   ```kotlin
   data class ConfiguredProvider(val id: String, val modelId: String?, val hasApiKey: Boolean,
                                 val hasOAuth: Boolean, val hasCloudCredentials: Boolean,
                                 val tokenSource: String, val updatedAt: String)
   data class ConfiguredProviders(val lastUsedProvider: String?, val providers: List<ConfiguredProvider>)
   ```
   `hasOAuth` = `settings.auth?.accessToken` non-blank. `hasCloudCredentials` = a non-empty
   `settings.aws` (bedrock), `settings.gcp` (vertex) or `settings.sap` (sapaicore) object; only
   the presence is kept, never a value. The model is kept in memory only; nothing from it is
   written to DataStore except `model.selection` (0.8). A missing file (`cat` exit 1, "No such
   file") means "no providers", not an error.

   **"Connected"** follows the desktop's `isProviderConnected` (`lib/provider-connection.ts`),
   which has three clauses: `apiKey` non-blank, OAuth token present, or `enabled && configured
   === true`. The third uses the sidecar's per-provider readiness check, which `providers.json`
   does not carry. So:
   - When the settings helper is up, use `list_provider_catalog`'s `enabled` and `configured`
     flags exactly as the desktop does (all three clauses).
   - Otherwise (**A**, approximation): `hasApiKey || hasOAuth || (modelId non-blank && (id in
     KEYLESS_PROVIDER_IDS || hasCloudCredentials))`, with `KEYLESS_PROVIDER_IDS =
     {"ollama", "lmstudio"}` emitted by `tools/gen-provider-fallback.ts` (C.7) from the ids
     that exist in `providers.generated.ts` at the pinned tag (verified: `ollama`, `lmstudio`,
     `bedrock`, `vertex`, `sapaicore` all exist there). A user who configured Ollama or Bedrock
     from the TUI therefore does not see the "Connect a model" notice.
3. API keys typed into C live in `remember` state only (never `rememberSaveable`, so they never enter
   the saved-instance Bundle), are cleared on success, on leaving the screen and on `ON_STOP` after
   10 s in the background. While C is on screen, `Activity.setRecentsScreenshotEnabled(false)`
   (API 33+) hides the Recents thumbnail.
4. `TermuxCommand.toString()` currently joins all arguments, and `TermuxUnavailable.TimedOut` puts
   it into the exception message. It must redact the value after `--apikey` (and `-k`) as `•••`
   before any of this group ships (open question 2). `authProvider`'s `label` stays
   "cline auth <provider>" without the key.
5. `TermuxResultReceiver` never logs result bundles. The diagnostics shown in E and in
   `DiagnosticsBlock` come only from `doctor --json` and `/health` + `/version`, none of which
   contain secrets. `tailDaemonLog` output is matched and dropped, never shown (0.4).
6. `providers.json` crosses the Termux → app boundary only through `readProviders`, which is
   never polled (0.5). Sign-in completion is polled with the secret-free `statProviders`.

### 0.7 New components, symbols and dependency (paste-ready DS additions)

These are needed by this group and are not in the design system yet. The tables below are
written as DS rows (DS §4.2, §7.3/§7.4, §0, §4.3) so they can be pasted into
`design/design-system.md` unchanged; until they land, this section is their definition. Each
component lives in `ui/components/` with `@Preview`s for every state, light and dark, at font
scale 1.0 and 2.0.

**DS §4.2 rows:**

| Component | Desktop source | M3 / Compose | Key specs and copy |
| --- | --- | --- | --- |
| **OnboardingScaffold** | `onboarding-view.tsx` `OnboardingContent` | `Scaffold(containerColor = surface, contentWindowInsets = WindowInsets.systemBars.only(Top + Horizontal), snackbarHost = { SnackbarHost(LocalSnackbarHost.current) })` + optional small `TopAppBar` (back arrow only, CD "Back") + `WelcomeHero` grid backdrop (DS §4.2 WelcomeHero; grid only, `outlineVariant @ 20 %`, 40dp cells, drawn behind content, decorative → `Modifier.clearAndSetSemantics {}`) + a content column + an optional bottom action bar (`Surface(color = surface)` with a 1dp `outlineVariant` top hairline that appears only when content scrolls under it) | **Content column** is `Column(Modifier.verticalScroll(rememberScrollState()))`: compact full width, 16dp gutters; medium/expanded a centred card `Surface(shape = large, color = surface, border = 1dp outline)` max width **592dp** (desktop `max-w-148`), internal padding 32dp (desktop `p-8`; 20dp on compact = desktop `max-[720px]:p-5`). Variants `plain` (Welcome, Done: no border, transparent over the grid) and `panel` (Kernel, Connect, Reliability: bordered card on medium+, plain on compact). **Panel title** style, one for every step: `headlineSmall` with `FontWeight.SemiBold` (desktop `text-2xl font-semibold` on both ConnectStep and the kernel step), marked `heading()`; description `bodyMedium` `onSurfaceVariant` 8dp below. **Plain title** style (Done, B; desktop `text-3xl font-semibold` on DoneStep and ImportHistoryStep): `headlineMedium` SemiBold, centred, `heading()`. A0's "Cline" keeps its own hero sizes (A.4). **Insets and IME** (voice `ChatScreen.kt` pattern, DS §5.7): the bottom action bar (compact) or the content column's last child (medium+, where the action bar sits inside the card) applies `Modifier.windowInsetsPadding(WindowInsets.ime.union(bottomSafe))`, so the action bar rides on the IME when it is open and on the navigation bar when it is closed; when there is no action bar the scroll column itself takes that padding. No `imePadding()` anywhere else (no double padding). **Snackbars** sit above the bottom action bar (the Scaffold places them there because the action bar is the Scaffold's `bottomBar`) |
| **SetupCheckRow** | none (replaces the desktop boot gate) | custom `Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).semantics(mergeDescendants = true) { … })`: a 24dp leading status glyph (top-aligned with the title's first line), then a text `Column(Modifier.weight(1f))` with title `bodyLarge`, supporting `bodyMedium` `onSurfaceVariant`, and an optional **value line** `ClineMono.small` `onSurfaceVariant` (e.g. "0.118.3", "3.0.68-termux.2") as the last line; then `AnimatedVisibility` fix area below the text column (DS §6 disclosure motion). Not an M3 `ListItem` and no trailing slot: the value is part of the text column so it wraps with it at any font scale | Status glyphs, each a different shape so status is never colour-only: Waiting = 8dp `outline` ring in the 24dp box; Checking = 20dp `CircularProgressIndicator(strokeWidth = 2.dp)`; Passed = `check_circle` (fill 1) in `ClineTheme.colors.success.color`; NeedsAction = `warning` (triangle, fill 1) in `ClineTheme.colors.warning.color`; Failed = `error` (circle, fill 1) in `error`. Rows are not clickable; actions live in the fix area. contentDescription (merged) "<title>, <status word>, <supporting>" |
| **CommandBlock** | none | `Surface(color = surfaceContainer, shape = small)` with `SelectionContainer { Text(ClineMono.body) }` forced LTR, horizontally scrollable, plus a trailing `IconButton` `content_copy` → `check` for 2 s | CD "Copy command" / "Copied command". Copy writes `ClipData.newPlainText("command", text)` and shows the snackbar "Command copied" except on API 33+ where the system clipboard toast already confirms it |
| **SetupOptionCard** | `onboarding-view.tsx` `SetupOptionCard` | the **non-clickable** `Card(modifier = Modifier.selectable(selected = selected, enabled = !selected, role = Role.RadioButton, onClick = onSelect), shape = MaterialTheme.shapes.medium)` inside a `Modifier.selectableGroup()` column. One click handler per node: never the `Card(onClick)` overload, which would add a second clickable (a button and a radio in one TalkBack node, and a double ripple). `selectable` is enabled only while the card is unselected (desktop overlays its select button only when `!selected`), so taps on the selected card's body reach the controls inside it | Selected: `CardDefaults.cardColors(containerColor = primaryContainer.copy(alpha = .35f))` + 1dp `primary @ 40 %` border (desktop `border-primary/20 bg-primary/4 ring-primary/20`, raised for contrast); unselected: `surface` + 1dp `outline` border. Header grid: 32dp icon tile (`surfaceContainerHighest`, `shapes.small`, icon 20dp `onSurfaceVariant`), title `titleMedium` SemiBold, optional trailing badge, description `bodyMedium` `onSurfaceVariant`. The body (forms, buttons) is composed only while selected (desktop `inert`) and expands with the DS §6 disclosure motion (desktop 120ms grid-rows transition) |
| **ReliabilityItem** | none | `ListItem` with leading 24dp icon, headline, supporting (state line, `StateLine` from voice `Prefs.kt` with `Tone`), trailing status `Surface(shape = extraSmall)` label, and an expandable instructions area with one primary action `FilledTonalButton` | States: Done (`success`), Not set (`warning`), Check yourself (neutral, with a `Checkbox` "I've done this"), Not needed (row hidden). At font scale ≥ 1.3 or `maxWidth < 360dp` the status label moves into the supporting column (same rule as SetupCheckRow's value line) |
| **KernelStatePanel** | `app/page.tsx` boot gate, `ConversationEmptyState` | `EmptyState` (DS §4.2) layout with a 48dp icon, `titleMedium` title (`heading()`), `bodyMedium` body, optional `ClineMono.small` detail block (`CommandBlock` without copy), and a vertical button stack (primary `Button`, secondary `OutlinedButton`, tertiary `TextButton`s) | Max width 480dp, centred in the pane, 24dp gutters. Announced with `liveRegion = Polite` when its variant changes. **Action rule:** the panel shows its primary action (F.2 "Primary") only when no composer is composed under it; when the composer is there, its read-only row (DS §4.1 #6) carries that action and the panel shows only its secondary action |
| **KernelBanner** | `chat-messages.tsx` transport line, `agent-sidebar.tsx` Hub card | `Surface(shape = large, color = surfaceContainerHigh, border = 1dp outlineVariant)`, 16dp padding, placed at the top of the content column (inside the 840dp column, 8dp from the app bar; on Home above the composer). Row: 8dp kernel dot (DS §1.6 kernel table, pulsing for busy variants) **or** a 16dp `CircularProgressIndicator` for busy variants, then a `Column(weight 1f)` with line 1 `titleSmall` `onSurface` and optional line 2 `bodyMedium` in `error` (down variants) or `onSurfaceVariant` (others), then one trailing `TextButton` | One neutral treatment for every variant (DS §1.6 marks asleep/unavailable with the `outline` dot, and amber stays reserved for approvals). Min height 56dp. Lines and icons per variant: F.2. **Action rule:** the trailing action is "Kernel status" (→ E) on surfaces that have a composer (Home, Chat), because the composer's read-only row carries "Start kernel" / "Connect"; on surfaces without a composer (Sessions page, Settings, Customize, Schedules, Changes, Tool detail) it is the variant's primary action from F.2. Degraded variant: "Fix" everywhere. One banner at a time, highest precedence wins (F.4). Polite live region, announces line 1 once per variant change |
| **DiagnosticsBlock** | `export-diagnostics-dialog.tsx` (privacy sentence) | `Column`: "Diagnostics" overline (`labelSmall`, `onSurfaceVariant`), then label/value rows (label `bodyMedium` `onSurfaceVariant`, value `ClineMono.small` `onSurface`, 40dp visible, merged semantics, long-press copies the value), then an optional `TextButton(error)` "Fix processes", then the privacy line `bodySmall` `onSurfaceVariant` | States: Loading (rows replaced by a 2dp indeterminate `LinearProgressIndicator` and "Running diagnostics…"), Loaded(rows), Error(message, `bodySmall` `error`). Rows and copy: E.6. Used by E.6, A row 5 and the repair variant, so diagnostics look identical inside and outside the shell |

**Shared helper (DS §4.2 row "External links"):** `openUrl(context, url)` in
`ui/components/ExternalLinks.kt` opens a Custom Tab (`CustomTabsIntent.Builder()
.setShowTitle(true).build().launchUrl(context, uri)`, colours from the current scheme), falls
back to `Intent(ACTION_VIEW, uri)`, and when neither resolves copies the URL to the clipboard and
shows the snackbar "No browser found to open %1$s." (**A**). Every external link in this group
(B's store links, C's "Sign up", "Find your key", "Get a <Provider> API key", "Get ClinePass")
calls it; no screen builds its own intent.

**DS §0 dependency:** `implementation("androidx.browser:browser:1.9.0")` (verify the latest
stable on Google Maven when adding, as DS §0 does for `core-splashscreen`). It is not in
`app/build.gradle.kts` yet.

**DS §4.3 button size rule (no new size):** every `Button`, `FilledTonalButton`,
`OutlinedButton` and `TextButton` in this group uses the M3 default 40dp visible height with a
48dp touch target (`minimumInteractiveComponentSize()`), as DS §4.1 #5 and #9 do. Hero CTAs
("Get started", "Start building", B's store buttons) differ only in width (`fillMaxWidth`, max
256dp / 320dp), not height.

**DS §7.3 / §7.4 symbol rows** (Material Symbols Rounded, delivered as DS §7.1 drawables):

| State / use | Symbol | Notes |
| --- | --- | --- |
| Setup check needs action | `warning` (fill 1) | distinct shape from Failed's `error` circle |
| Termux asleep | `bedtime` | F.2; also the Sessions notice (0.10) |
| Kernel stopped (by the user or in Termux) | `stop_circle` | F.2, E action row "Stop kernel" |
| Disconnected | `link_off` | F.2, E action row "Disconnect"; `link` for "Connect" |
| Draining / restarting | `hourglass` | F.2 |
| Restart kernel | `restart_alt` | E action row |
| Run diagnostics | `troubleshoot` | E action row |
| Open Termux | `terminal` | already DS §7.3 "Termux"; used as the E action row icon |
| External link | `open_in_new` | trailing on link buttons (desktop `ExternalLink`) |

### 0.8 Persistence (DataStore `app` file, app-local, no secrets)

| Key | Type | Meaning |
| --- | --- | --- |
| `onboarding.completedAt` | String ISO-8601 | mirrors desktop `cline.code.onboarding.v1.completedAt`; null = show onboarding |
| `onboarding.signIn.startedAt` | Long epoch ms | set when `authClineInteractive` is issued, cleared on success/cancel/timeout; restores the "Waiting for browser..." state after process death |
| `onboarding.reliabilityShownAt` | String ISO | the checklist was shown once (contract: "shows the One UI checklist once") |
| `kernel.userStopped` | Boolean (false) | the user stopped the kernel; blocks auto-start |
| `kernel.keepAwake` | Boolean (true) | hold `termux-wake-lock` while connected |
| `kernel.wakeLockRequested` | Boolean | this app issued `termux-wake-lock` and has not released it (the app cannot query Termux's wake lock; E shows this belief) |
| `kernel.hubLifetimeRelease` | String | `release` seen when the current `(pid, startedAt)` was first connected; drives "Restart to use <release>" |
| `reliability.neverSleepingConfirmed`, `reliability.childProcessConfirmed`, `reliability.overlayConfirmed` | Boolean | self-checks for items the app cannot verify |
| `model.selection` | JSON `{lastProvider, lastModelByProvider}` | mirrors desktop `cline.code.model-selection.v1` (`rememberProviderSelection`) |
| `whatsNew.seenReleaseId` (owned by the About spec) | String | mirrors desktop `cline.code.whats-new.v1`. Written by this group only when onboarding finishes (section 1, both the Done and the Skip exit) with the id of the newest bundled What's new release, so a user who just finished onboarding is not shown the catch-up dialog. If the About spec names the key differently, its name wins; this group calls the About spec's `markCurrentWhatsNewSeen()` and never writes the key directly |

### 0.9 Debug fake kernel (debug builds only, for the acceptance checklists)

The owner's S25U is the daily phone and the port's release-test device (`CLAUDE.md`): tests must
not uninstall Termux, edit `termux.properties` or force-stop Termux unless the owner agrees. Debug
builds therefore accept a fake kernel scenario that replaces `TermuxExecutor` and `HubProber`
with scripted fakes:

```
adb -s $SERIAL shell am start -n io.github.ichouchiang.clinetermux.debug/dev.cline.termux.MainActivity --es dev.cline.termux.FAKE_KERNEL <scenario> [--ez dev.cline.termux.RESET_ONBOARDING true]
```

Scenarios: `termux_missing`, `termux_disabled`, `termux_old` (0.101.0), `permission_missing`,
`external_apps_blocked`, `termux_not_set_up`, `port_missing`, `port_old` (3.0.68-termux.1),
`hub_start_slow` (the `hub start` RUN_COMMAND exits 1 at 15 s with stderr "Timed out after 15000ms
waiting for detached hub startup."; `/health` starts answering at 25 s), `hub_start_fail` (exits
1 at 15 s with that stderr; `/health` never answers), `hub_start_hang` (never returns, then a
doctor JSON), `hub_start_port_old` (as `hub_start_fail`, VERSION without `release=`, daemon-log
tail containing `Module not found ".../entry.js"`), `stopped_in_termux` (Ready, then `/health`
refused and `production.json` absent), `hub_comes_back` (Asleep, then `/health` answers after
30 s without any `hub start`), `restart_busy_other_client` (`session.list` shows one session with
a foreign participant), `restart_slow` (`hub upgrade` result after 70 s, new pid), `hub_crash_loop`, `asleep_after_ready` (Ready, then `/health` refused after 10 s),
`protocol_unsupported`, `auth_failed`, `draining`, `providers_empty`, `providers_cline`,
`providers_anthropic`, `providers_ollama` (keyless entry with a model), `auth_ok`, `auth_fail_invalid_provider`, `signin_success_after_20s`,
`signin_never`. `--es dev.cline.termux.FAKE_KERNEL off` returns to the real kernel. The fake is
compiled out of release builds (`src/debug/`).

### 0.10 Cross-spec contract (changes the sibling specs must make)

Three specs currently define their own kernel-down component and two still bind to the old
`KernelState` names. This section is the reconciliation; the sibling authors apply it, and until
they do, this table wins where they disagree.

| Spec | Today | Change to |
| --- | --- | --- |
| `home-chat.md` §2.4 "Kernel notice" row, state tables (`Unknown/Connecting/Starting`, `Degraded(Reconnecting)`, `HubDown`, `Failed`/`Degraded(Unavailable)`) | own "Kernel notice" (`surfaceContainerHigh` + 1dp `outlineVariant`, trailing "Kernel status", "Start kernel" only in the composer) | Name it **KernelBanner** (0.7). Its visual treatment and action rule are already the ones adopted here, so only the name and the bindings change. Bind rows to `KernelGate` (F.6) and copy F.2's lines (e.g. `Down(StartFailed)` → "Cline Hub @unknown" / "Cline Hub is not connected.") |
| `sessions.md` §1.7 notice table (lines ~351–366), §F rows 1244–1248, strings `kernel_termux_asleep`, `kernel_update_title` | `HubDown` → `terminal` + "Start kernel"; `Failed(reason)` / `Degraded(Unavailable)` → "Retry"; `PortTooOld(version)` → "Kernel update required" → status sheet | KernelBanner with F.2's lines and Primary (no composer on S1/S2, so the banner carries it): Asleep → `bedtime` panel icon, "Start kernel"; StartFailed → "Try again" (**D**, replaces "Retry"); `PortTooOld` and the other prerequisite states are `KernelGate.Blocked` → the blocking repair variant replaces the shell, so the Sessions notice never shows them ("Kernel update required" and its body string are dropped). Reuse this spec's string keys (`kernel_status_asleep`, `kernel_try_again`, …) instead of defining new ones |
| `changes-tools-checkpoints.md` DS-A2 "KernelStrip" and its strings row | own 48dp strip, trailing "Start kernel" | Delete DS-A2 and reference **KernelBanner**; Changes and Tool detail have no composer, so the trailing action is F.2's Primary, which keeps their current "Start kernel" behaviour |
| `composer-approvals.md` read-only row (line ~475: `Unavailable` / `TermuxAsleep` / `Blocked`) | own state names | Bind to `KernelGate`: `Down(*)` → dot + F.2 chip line 1 + F.2 Primary; `Busy(*)` → spinner + chip line 1; `Blocked` → placeholder = repair title, no button (the blocking repair replaces the shell anyway) |
| `settings-providers.md` Kernel row | DS §1.6 line 1 list | add "Stopped in Termux" / "Restarting…" from E.5 to its line-1 list |
| `design-system.md` | — | the 0.7 rows (open question 1) |

---

## 1. First-run flow at a glance

```
              ┌──────────────┐  Get started  ┌──────────────┐ all 5 checks pass ┌───────────────────┐
 launch ───▶  │ A0 Welcome   │ ────────────▶ │ A  Kernel    │ ───── Continue ─▶ │ D  Keep the kernel│
 (completedAt │  (plain)     │ ◀──── back ── │   setup      │ ◀───── back ───── │   running (once)  │
  == null)    └──────────────┘               └──────┬───────┘                   └─────────┬─────────┘
                                                    │ Termux missing                       │ Continue / Skip for now
                                                    ▼                                      ▼
                                             ┌──────────────┐                     ┌───────────────────┐
                                             │ B Install    │                     │ C  Connect a model│──Skip──▶ Home (setup notice)
                                             │   Termux     │                     │  (Set up Cline)   │
                                             └──────────────┘                     └─────────┬─────────┘
                                                                                            │ connected
                                                                                            ▼
                                                                                  ┌───────────────────┐
                                                                                  │ C5 Done           │── Start building ─▶ Home (new thread)
                                                                                  │ "You're all set"  │
                                                                                  └───────────────────┘
```

D is skipped automatically when every verifiable item is already done (D.4). The desktop's
`github` step (feature-flagged) and `import` step (feature matrix: import is "none") are omitted.
"Skip" on C finishes onboarding, exactly like the desktop's `onSkip = onComplete`.
Finishing (Done → "Start building", or Skip) does what the desktop's `completeOnboarding` does,
in the same order: (1) writes `onboarding.completedAt`; (2) marks the current What's new
release as seen (`markCurrentWhatsNewSeen()`, key in 0.8; desktop: "first-run users see
onboarding instead, and completing it marks the catch-up as seen"); (3) clears the onboarding
back stack (`popUpTo("onboarding") { inclusive = true }`) and opens Home with a fresh thread
(`handleNewThread`). The re-entry variant (`setup/*`) does none of these; it only pops.

---

## A. Kernel setup (first run), with the Welcome step

### A.1 Purpose

Get a phone from "app just installed" to "Hub connected": Termux present, the RUN_COMMAND
permission granted, Termux accepting external apps, the Cline Termux port at
v3.0.68-termux.2 or later, and the Hub started and connected. Each prerequisite is one visible
row that checks itself and, when it fails, shows exactly one fix. The same screen in its
**repair** variant handles a prerequisite that breaks later (permission revoked, port downgraded).

### A.2 Desktop equivalent

- `onboarding-view.tsx` **WelcomeStep**: copied as step A0 (hero, title, tagline, body, "Get started",
  footer), with the app's own mark and name and, since 2026-10-16, the unofficial notice in place
  of the desktop tagline (**A**). The Kernel step itself has no desktop screen because the
  desktop spawns its backend in-process; it replaces the desktop's **boot gate** (`app/page.tsx`
  `!isAppReady`: spinner + "Connecting..." / "Desktop backend unavailable" + the error text) with an
  explicit checklist, and borrows the `OnboardingContent` panel surface, the back `IconButton`
  ("Back"), the `text-2xl` title + `text-sm` muted description rhythm and the bottom-centred ghost
  action of `ConnectStep`.
- `hub-update-required-dialog.tsx`: a protocol-incompatible Hub is handled by E.9 (copied from its
  `unsupported_protocol` dialog); a too-old **port** is a check row here, because on Android the
  fix is reinstalling the port in Termux, not an app update.

### A.3 Navigation

| | Behaviour |
| --- | --- |
| Lives at | `onboarding/welcome` (A0), `onboarding/kernel` (A). Repair variant, two entries: **blocking** `kernel/repair?focus={checkId}` (replaces the shell) and **pushed** `kernel/repair?focus={checkId}&pushed=true` (on top of the shell) |
| Entered from | A0: start destination when `onboarding.completedAt == null`; Settings → "New user experience" → "Replay" (resets `completedAt`, opens A0; owned by the Settings spec). A: "Get started" on A0. **Blocking repair:** the coordinator reports `PermissionMissing`, `ExternalAppsBlocked`, `TermuxNotSetUp`, `PortMissing`, `PortTooOld` or `TermuxTooOld` after onboarding **and** the Hub is not usable (no degraded mode, 0.4), i.e. the shell cannot work. **Pushed repair:** the user invokes a RUN_COMMAND feature (workspace picker, sign-in, provider setup, start/stop) while the app runs in degraded mode, taps "Fix" on the Degraded banner, or taps "Fix the Cline kernel" in E / "Open kernel setup" in C |
| Left by | A0 → A ("Get started"). A → D or C ("Continue", enabled when all five rows pass). A → B when row 1 fails with Termux missing ("Install Termux"). Blocking repair → the shell (Home, or the destination saved before it replaced the shell), automatically, 600 ms after the last failing row passes, with the snackbar "Cline kernel connected". Pushed repair → pops back to the caller, automatically 600 ms after the focused row passes (snackbar "Cline kernel connected"), or by back at any time; the caller then retries its own action only on a new tap |
| Back | A0: not intercepted, back leaves the app (DS §5.8 item 10). A: top-bar back arrow and system back → A0 (desktop ConnectStep back → welcome). While row 5 is starting the Hub, back still navigates; the start continues in the coordinator and its result is shown when the user returns. Blocking repair: not intercepted (the shell is unusable without the kernel), back leaves the app with the back-to-home preview. Pushed repair: top-bar back arrow (CD "Back") and system back pop to the caller (DS §5.8 item 9) |
| Predictive back | A → A0 and pushed repair → caller use the shared-axis-X seekable pop (DS §6); the preview shows the caller (e.g. the live chat), never the launcher |

### A.4 Layout

**A0 Welcome, compact (S25U portrait, 412dp):**

```
┌────────────────────────────────────────┐
│ status bar (edge-to-edge)              │
│ ┊  ┊  ┊  ┊  ┊  ┊  ┊  ┊  ┊  ┊  ┊  ┊   │ ← 40dp grid backdrop, outlineVariant @20 %
│                                        │
│               ╭──────╮                 │
│               │  C_  │  mark 72dp      │ ← ic_app_logo, primary 13 % fill / 12 % stroke
│               ╰──────╯                 │
│                                        │
│              Cline Termux              │ ← headlineMedium SemiBold (displaySmall on medium+)
│       An unofficial app for the        │ ← titleMedium onSurface (one line on the S25U)
│          Cline CLI in Termux.          │
│   Not affiliated with or endorsed by   │ ← bodyMedium onSurfaceVariant; both lines break
│ Cline Bot Inc. or the Termux project.  │   balanced (LineBreak.Heading)
│                                        │
│   Cline is an AI coding agent. It      │ ← bodyLarge onSurfaceVariant, centred
│   reads your code, edits files, runs   │
│   commands, and works through tasks    │
│   with you — in any project on this    │
│   device.                              │
│                                        │
│      ┌──────────────────────────┐      │
│      │       Get started        │      │ ← Button, fillMaxWidth, max 256dp, 40dp (48dp target)
│      └──────────────────────────┘      │
│                                        │
│  Takes less than a minute. Everything  │ ← bodySmall onSurfaceVariant
│  can be changed later in Settings.     │
│ gesture bar                            │
└────────────────────────────────────────┘
```

**A Kernel setup, compact:**

```
┌────────────────────────────────────────┐
│ ←                                      │ TopAppBar small, no title
│                                        │
│ Set up the Cline kernel                │ headlineSmall SemiBold (panel title, 0.7)
│ Cline runs its agent inside Termux on  │ bodyMedium onSurfaceVariant
│ this device, and this app connects to  │
│ it. Each step is checked for you.      │
│ 3 of 5 ready                           │ labelLarge onSurfaceVariant + live region
│ ━━━━━━━━━━━━━━━━━━━━━━░░░░░░░░░░░░░░░░ │ LinearProgressIndicator (determinate)
│                                        │
│ ✔  Termux                              │ SetupCheckRow (Passed)
│    Termux 0.118.3 is installed         │
│ ✔  Permission to run commands          │
│    Allowed                             │
│ ✔  Termux accepts commands from Cline  │
│    allow-external-apps is on           │
│ ▲  Cline kernel                        │ (NeedsAction: warning triangle, fix area open)
│    The Cline kernel is not installed   │
│    in Termux. Run the installer in     │
│    Termux:                             │
│    ┌──────────────────────────────┬──┐ │
│    │ curl -fsSL https://github.co…│⧉ │ │ CommandBlock (scrolls horizontally)
│    └──────────────────────────────┴──┘ │
│    [ Open Termux ]   Check again       │ FilledTonalButton + TextButton
│ ○  Cline Hub                           │ (Waiting, disabled look)
│    Waiting for the steps above.        │
│                                        │
├────────────────────────────────────────┤ hairline when content scrolls under
│                         [ Continue ]   │ Button (disabled until 5/5)
└────────────────────────────────────────┘
```

**A Kernel setup, Tab S7+ portrait (medium, ~778dp) and landscape (expanded, ~1244dp):**

```
┌──────────────────────────────────────────────────────────────────────────────┐
│ ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   ┊   │ grid backdrop full-bleed
│                ╭──────────────── 592dp panel ────────────────╮              │
│                │ ←                                           │              │
│                │ Set up the Cline kernel                     │ headlineSmall│
│                │ Cline runs its agent inside Termux ...      │   SemiBold   │
│                │ 4 of 5 ready ━━━━━━━━━━━━━━━━━━━━━━━━░░░░░░ │              │
│                │ ✔ Termux                                    │              │
│                │   Termux 0.118.3 is installed               │              │
│                │ ✔ Permission to run commands                │              │
│                │ ✔ Termux accepts commands from Cline        │              │
│                │ ✔ Cline kernel                              │              │
│                │   Cline kernel 3.0.68-termux.2              │              │
│                │ ⟳ Cline Hub                                 │              │
│                │   Starting the Cline kernel in Termux…      │              │
│                │   Starting Cline Hub… · 9s                  │              │
│                │                               [ Continue ]  │ action bar   │
│                ╰─────────────────────────────────────────────╯   inside card│
└──────────────────────────────────────────────────────────────────────────────┘
```

On medium and expanded the action bar is the last row inside the card (not docked to the
window bottom), and the card is vertically centred when it fits, top-aligned with 48dp margin when
it does not. Height-compact windows (phone landscape, DS §5.1) hide the hero bot on A0 and use the
compact column with the card border dropped.

### A.5 States and copy

String keys and texts are listed in full in section 9. Desktop-verbatim strings are marked **D**,
new ones **A** (Android). New Android strings use "…" (U+2026); desktop strings keep their own
punctuation, character for character.

**A0 Welcome:** single state. Under the title, the unofficial notice (**A**, 2026-10-16) takes the
place of the desktop tagline, in two lines:
"An unofficial app for the Cline CLI in Termux." (`titleMedium`) and
"Not affiliated with or endorsed by Cline Bot Inc. or the Termux project." (`bodyMedium`).
About carries the full notice (CR C10). The footer text depends on P1, evaluated synchronously:
Termux installed → "Takes less than a minute. Everything can be changed later in Settings." (**D**);
Termux missing → "Takes a few minutes if Termux still needs installing. Everything can be changed
later in Settings." (**A**). Body text is the desktop's with "on your machine" replaced by "on this
device" (**A**, adapted).

**A, per row.** Rows run top to bottom. A row starts only when every row above it has passed; rows
below a failing row show Waiting with the supporting text "Waiting for the steps above." (**A**).
On entry the screen runs rows 1–4 automatically; row 5 waits for an explicit "Start kernel" tap on
first run (it starts a ~300 MB background process, contract "Termux liveness"), except when H1
finds the Hub already running, in which case row 5 connects without asking.

| Row | Title (**A**) | Checking | Passed (supporting; the version is part of the text, so SetupCheckRow's value line is not used here) | Needs action / failed (supporting + fix area) |
| --- | --- | --- | --- | --- |
| 1 Termux | "Termux" | "Checking…" | "Termux %1$s is installed" | Missing: "Termux is not installed" + `Button` "Install Termux" (→ B). Disabled: "Termux is turned off" + `FilledTonalButton` "Open Termux settings" (`ACTION_APPLICATION_DETAILS_SETTINGS`, `package:com.termux`). Too old: "Termux %1$s is too old. Cline needs Termux 0.109 or later." + "Install Termux" (→ B, which explains where to update) |
| 2 Permission | "Permission to run commands" | "Checking…" | "Allowed" | First ask: "Cline needs this to start the kernel and read its status." + `Button` "Allow" → `ActivityResultContracts.RequestPermission()` for `com.termux.permission.RUN_COMMAND` (the dialog is Termux's custom permission, label "Run commands in Termux environment"). Denied once (`shouldShowRequestPermissionRationale == true`): same text + "Allow" again. Permanently denied: "Permission denied. Turn on “Run commands in Termux environment” in Cline Termux's app permissions." + `FilledTonalButton` "Open app settings" (`ACTION_APPLICATION_DETAILS_SETTINGS`, own package) and the instruction line "Permissions → Additional permissions → Run commands in Termux environment" (`bodySmall`). Re-checked on every `ON_RESUME` |
| 3 External apps | "Termux accepts commands from Cline" | "Checking…" | "allow-external-apps is on" | "Termux blocks commands from other apps until you allow it. Run this once in Termux:" + CommandBlock `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings` + `FilledTonalButton` "Open Termux" (launches `com.termux/.app.TermuxActivity`) + `TextButton` "Check again". On returning (`ON_RESUME`) the row re-checks automatically once |
| 4 Kernel | "Cline kernel" | "Checking…" | "Cline kernel %1$s" (release) | Missing: "The Cline kernel is not installed in Termux. Run the installer in Termux:" + CommandBlock `curl -fsSL https://github.com/IChouChiang/cline-termux/releases/latest/download/install-cline-termux.sh \| bash` + "Open Termux" + "Check again". Too old: "Cline kernel %1$s can't start the Cline Hub. Update it to 3.0.68-termux.2 or later by running the installer again:" + the same block and buttons. Termux not set up: "Termux hasn't finished its first-run setup. Open Termux, wait for the prompt, then check again." + "Open Termux" + "Check again" |
| 5 Hub | "Cline Hub" | Starting: "Starting the Cline kernel in Termux…" + a second line with the current `StartStep` label and elapsed seconds since the tap ("Starting Cline Hub… · 9s"); after 20 s an extra line "This is taking longer than usual." The row stays in this state through a non-zero `hub start` exit while the 60 s budget polls `/health` (H2), so the 15 s CLI timeout is never shown as a failure | "Cline Hub @%1$s · core %2$s" (port from `/health.url`, `coreVersion`) | Idle (first run): "Starts the Cline Hub in Termux. It keeps running in the background while Cline is connected and uses about 300 MB of memory." + `Button` "Start kernel". Failed (`StartFailed`, the 60 s budget ran out): "Couldn't start the Cline Hub." + detail (CLI stderr tail, mono, max 6 lines, selectable) + `Button` "Try again" + `FilledTonalButton` "Run diagnostics", which runs `doctor` and expands a `DiagnosticsBlock` (0.7, rows as E.6) inside the fix area; the button turns into "Running diagnostics…" while it runs and disappears once the rows show. Timed out (`StartTimedOut`): "Termux didn't answer within 60 seconds." + the `DiagnosticsBlock` already filled from the doctor run H2 made + "Try again". Protocol unsupported: "This Cline Hub speaks a protocol this app doesn't support." + `Button` "Restart kernel" (the E.9 flow inline, its copy in place of the dialog). Auth failed: "The Cline Hub rejected this app's credentials." + "Try again" |

Screen-level states:

| State | Visual |
| --- | --- |
| first-run, entering | rows 1–4 Checking in sequence (each typically < 1 s; P3 ~0.3–2.5 s), row 5 Waiting |
| loading | as above; the progress line reads "Checking…" until row 1 resolves |
| empty | not applicable (the list is fixed) |
| populated, all passed | 5/5, progress full, "Continue" enabled, a polite announcement "Cline kernel connected" |
| running (Hub starting) | row 5 Checking with step label + elapsed; "Continue" disabled; other rows stay Passed |
| error | the failing row's fix area open, rows below Waiting, "Continue" disabled |
| offline / kernel down | after passing once, if the Hub dies while A is visible (e.g. the user stopped it from Termux), row 5 returns to Idle with "The Cline Hub stopped." (**A**) and "Start kernel"; the passive probe (0.4) reconnects by itself if the Hub comes back |
| permission missing | row 2 as above; rows 3–5 Waiting |
| repair variant | same screen, title "Fix the Cline kernel" (**A**), description "Something Cline needs in Termux changed. Fix the step below to keep working." (**A**); rows above the failing one show Passed collapsed into a single summary line "%1$d steps OK" that expands on tap; CTA hidden (auto-dismiss on success). Blocking entry: no back arrow. Pushed entry: back arrow (CD "Back") in the top bar |

### A.6 Interactions and gestures

- "Get started" (A0) → navigate to A; `onboarding.reliabilityShownAt` untouched.
- Rows are not tappable. All actions are buttons in the fix area. The fix area of the first failing
  row opens automatically; it cannot be collapsed while the row is failing.
- "Check again" re-runs from the tapped row downward (P3 covers rows 3 and 4 together: a single
  `probeVersion` call). While checking, the button shows a 16dp spinner and the text "Checking…".
- "Open Termux" uses `packageManager.getLaunchIntentForPackage("com.termux")`; the app records
  `pendingRecheckOnResume = true` so the row re-checks on `ON_RESUME`.
- Copy in CommandBlock: copies the exact command; never includes a trailing newline.
- "Start kernel" → `KernelCoordinator.ensureConnected(startIfDown = true)`; sets
  `kernel.userStopped = false`.
- "Run diagnostics" (row 5 Failed) → `KernelDiagnostics.run()` (RUN_COMMAND `doctor`, 30 s);
  the result fills the row's `DiagnosticsBlock`. "Fix processes" inside it asks first with the
  E.6 dialog, then runs `doctorFix` and re-runs `doctor`. Diagnostics never open E from here:
  E lives in the shell, which is not composed during onboarding or the blocking repair (0.2).
- "Continue" → `onboarding/reliability` if any verifiable D item is not done and
  `onboarding.reliabilityShownAt == null`, else `onboarding/connect`.
- Pull-to-refresh: not used (the screen re-checks itself).
- Hardware keyboard: Tab moves through buttons in reading order; Enter activates.

### A.7 Data bindings and ViewModel

`KernelSetupViewModel(coordinator: KernelCoordinator, termux: TermuxExecutor, prefs: AppPrefs)`.
It never talks to the Hub directly; it drives `KernelCoordinator` and observes `state`.

```kotlin
enum class CheckId { Termux, Permission, ExternalApps, Kernel, Hub }
enum class CheckStatus { Waiting, Checking, Passed, NeedsAction, Failed }
sealed interface CheckFix {
    data object InstallTermux : CheckFix
    data object OpenTermuxSettings : CheckFix
    data class RequestPermission(val mode: PermissionMode) : CheckFix   // Ask | AskAgain | OpenSettings
    data class RunInTermux(val command: String) : CheckFix              // external apps, installer
    data object OpenTermux : CheckFix                                    // not set up
    data object StartKernel : CheckFix
    data class StartFailed(val detail: String?, val doctor: DoctorSummary?, val timedOut: Boolean) : CheckFix
    data object UpdateHub : CheckFix                                     // protocol unsupported
}
data class SetupCheckUi(val id: CheckId, val status: CheckStatus, val value: String?, val fix: CheckFix?)
data class KernelSetupUiState(
    val variant: Variant,                    // FirstRun | Repair(focus: CheckId)
    val checks: List<SetupCheckUi>,          // always 5, in CheckId order
    val readyCount: Int,
    val startStep: StartStep?, val startElapsedSec: Int?,   // row 5 while Starting
    val slowStart: Boolean,                  // elapsed > 20 s
    val canContinue: Boolean,                // readyCount == 5 && state is Ready
    val checkingRow: CheckId?,               // for the "Check again" spinner
    val diagnostics: DiagnosticsUi?,         // row 5 fix area: null | Loading | Loaded(rows) | Error(msg), shared with E.6
    val pushed: Boolean,                     // repair variant entered on top of the shell (back arrow shown)
    val statusAnnouncement: String?,         // "<row title>: <status word>", invisible live region (A.9)
)
```

Mapping from `KernelState` (the only input besides permission results and P1):

| `KernelState` | Rows 1–5 |
| --- | --- |
| `Unknown` | 1 Checking, rest Waiting |
| `TermuxMissing(disabled)` | 1 NeedsAction (`InstallTermux` / `OpenTermuxSettings`) |
| `TermuxTooOld(v)` | 1 NeedsAction (`InstallTermux`) |
| `PermissionMissing` | 1 Passed, 2 NeedsAction |
| `ExternalAppsBlocked` | 1–2 Passed, 3 NeedsAction (`RunInTermux(allowExternalApps)`) |
| `TermuxNotSetUp` | 1–3 Passed, 4 NeedsAction (`OpenTermux`) (row 3 cannot be proven without `cat`; it shows Passed because RUN_COMMAND was accepted) |
| `PortMissing` / `PortTooOld(r)` | 1–3 Passed, 4 NeedsAction (`RunInTermux(installer)`) |
| `HubDown(Unreachable, Stopped, StoppedInTermux or Asleep)` on first run | 1–4 Passed, 5 NeedsAction (`StartKernel`) |
| `Starting(step)` / `Connecting` / `Restarting` | 1–4 Passed, 5 Checking |
| `HubDown(StartFailed / StartTimedOut / CrashLoop)` | 5 Failed (`StartFailed`) |
| `ProtocolUnsupported` | 5 Failed (`UpdateHub`) |
| `AuthFailed` | 5 Failed (`StartFailed` with the auth text) |
| `Ready` / `Draining` | all Passed |

Kernel API used (feature matrix rows "Kernel liveness and version probe" and "Start/stop/upgrade
the Hub daemon, read the auth token, keep Termux alive"): `GET /health`, `GET /version`,
RUN_COMMAND `cline hub start`, `termux-wake-lock`, `cat .../locks/hub/production.json`, then
`client.register`, `stream.subscribe`, `hub.status` ("Register client and subscribe to the event
stream with replay").

### A.8 Edge cases (recovery matrix and beyond)

| Case | Surface |
| --- | --- |
| RUN_COMMAND `err != -1` with `errmsg` mentioning allow-external-apps (recovery matrix) | row 3 with the exact fix command |
| RUN_COMMAND `err != -1` mentioning permission | row 2 (permission revoked between P2 and the call) |
| Termux not resolvable via `<queries>` (recovery matrix) | row 1 → B |
| ECONNREFUSED on 25463 (recovery matrix) | row 5 Idle on first run ("Start kernel"); auto-start in the repair variant |
| `hub start` hung (recovery matrix: 60 s timeout then `cline doctor --json`) | row 5 timed-out variant with doctor rows |
| Upgrade 401 (recovery matrix: stale token, re-read once) | invisible on first 401; second 401 → row 5 `AuthFailed` |
| Port .1 installed but its VERSION file has no `release=` line | the `hub start` CLI only prints the 15 s timeout (the `entry.js` error goes to `hub-daemon.log`); when the 60 s budget runs out the daemon-log check (0.4) finds `entry.js` and row 4 shows the too-old text. A match on `hub start` output is checked too but is not relied on |
| `hub start` exits 1 at 15 s ("Timed out after 15000ms waiting for detached hub startup.") and the Hub answers at 20 s | row 5 stays Starting through the exit; passes at 20 s (H2) |
| The Hub comes up by itself while row 5 shows Failed or Timed out (slow boot, or the user ran `cline hub start` in Termux) | the passive probe (0.4) connects within 10 s; row 5 passes |
| Termux installed after Cline | P2 becomes askable only after Termux defines the permission; the row re-checks on `PACKAGE_ADDED` (B) and on resume |
| Termux reinstalled (new signature, permission reset) | row 2 NeedsAction again on next probe; the repair variant opens |
| User grants the permission in Settings while A is backgrounded | `ON_RESUME` re-runs from row 2 |
| Process death while row 5 starts | on relaunch the coordinator probes H1 first; a Hub that came up meanwhile is simply connected |
| `production.json` not yet written when read (race right after start) | one retry after 1 s, then the failure text in H4 |
| Two Cline front ends (TUI and app) start the Hub at once | `cline hub start` is idempotent (`ensureDetachedHubServer`); the app treats exit 0 as success and connects to whichever Hub answers |
| Hub already running from the TUI with a different build | connects normally; E shows its `buildId`; mismatch handling is E.9 |

### A.9 Accessibility

- A0: the hero is decorative (`clearAndSetSemantics {}`); reading order: "Cline Termux", the two
  notice lines, body, "Get started", footer. "Cline Termux" is `heading()`.
- A: "Set up the Cline kernel" is `heading()`. The visible progress line "3 of 5 ready" is plain
  text (not a live region). Row status changes are announced through one invisible status
  `Text` (DS §8.1 item 4 pattern: `Modifier.size(0.dp)` with its semantics kept,
  `liveRegion = LiveRegionMode.Polite`) whose text is set to "<row title>: <status word>" on
  each change ("Cline kernel: needs action"), so TalkBack names the row instead of re-reading
  the count, and rows themselves are not live regions (no chatter).
- Each row merges its title, status word and supporting text: contentDescription
  "Termux, passed, Termux 0.118.3 is installed". Status words: "waiting", "checking", "passed",
  "needs action", "failed" (**A**, `setup_status_*`).
- Status is never colour-only: every state has its own glyph shape (ring / spinner / check
  circle / warning triangle / error circle), and the status word is in the merged description.
- CommandBlock text is read as written (DS §8.1 item 12); its copy button is a separate focus stop.
- After "Allow" returns, focus moves to the next row's title. After "Check again", focus stays on
  the button.
- Buttons are 40dp visible with 48dp touch targets (DS rule, 0.7). Layout verified at font
  scale 2.0 × in-app 20/15: rows grow; SetupCheckRow has no trailing slot, so the title, the
  supporting text (which carries the version) and the value line wrap in the full-width text
  column and nothing ellipsizes.

### A.10 Acceptance checklist (S25U, then Tab S7+)

Preconditions: debug build installed with `./scripts/install.sh`; no port release candidate or
manual test running on the S25U; owner informed for any "real" step.

- [ ] Fresh install (`adb shell pm clear io.github.ichouchiang.clinetermux`) opens A0 with the mark, "Cline Termux",
      the unofficial notice, the adapted body and "Get started"; footer reads "Takes less than a
      minute…" because Termux is installed. Back from A0 leaves the app with the back-to-home preview.
- [ ] "Get started" → A; rows 1–4 check by themselves within 3 s on the real kernel and show
      "Termux 0.118.3 is installed", "Allowed", "allow-external-apps is on", "Cline kernel
      3.0.68-termux.2" (requires the .2 port installed; with .1 installed, row 4 must show the
      too-old text).
- [ ] With the permission not yet granted, row 2 shows "Allow"; tapping it opens Termux's
      permission dialog; denying twice switches to "Open app settings"; granting there and
      returning re-checks automatically.
- [ ] `adb shell pm revoke io.github.ichouchiang.clinetermux com.termux.permission.RUN_COMMAND` with the Hub
      stopped → the blocking repair variant replaces the shell on next resume (no back arrow;
      back leaves the app); granting returns to Home with "Cline kernel connected".
- [ ] Same revoke with the Hub running and a chat open → the app stays in the chat with the
      Degraded banner; tapping the workspace chip opens the pushed repair variant with a back
      arrow; the predictive-back preview shows the chat; back returns to the chat with the
      transcript intact; granting instead pops back automatically with "Cline kernel connected".
- [ ] Fake `external_apps_blocked`: row 3 shows the exact command; Copy puts exactly
      `mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings`
      on the clipboard; "Open Termux" opens Termux; returning re-checks once.
- [ ] Fakes `port_missing`, `port_old`, `termux_not_set_up`: row 4 copy and buttons as in A.5.
- [ ] Real, Hub stopped (`ssh <phone> cline hub stop`, owner OK): row 5 shows "Start kernel";
      tapping it shows step labels and elapsed seconds; "Cline Hub @25463 · core …" within 15 s;
      `adb shell dumpsys power | grep termux` shows `termux:service-wakelock`.
- [ ] Fake `hub_start_slow`: the RUN_COMMAND exits 1 at 15 s and row 5 keeps showing "Starting
      Cline Hub… · 15s" (no failure text); "This is taking longer than usual." appears at 20 s;
      the row passes at 25 s.
- [ ] Fake `hub_start_fail`: at 60 s row 5 shows "Couldn't start the Cline Hub." with the stderr;
      "Run diagnostics" fills the Diagnostics rows inside the row, without leaving A.
- [ ] Fake `hub_start_port_old`: at 60 s row 4 (not row 5) shows the too-old text.
- [ ] Fake `hub_start_hang`: at 60 s row 5 shows "Termux didn't answer within 60 seconds." and the
      doctor rows; "Try again" restarts the attempt.
- [ ] Fake `protocol_unsupported`, `auth_failed`: row 5 texts and buttons as in A.5.
- [ ] "Continue" is disabled until 5/5, then opens D (first time) or C.
- [ ] Tab S7+ portrait and landscape: the 592dp card is centred on the grid; the action bar sits
      inside the card; nothing is clipped at font scale 2.0.
- [ ] S25U at system font scale 2.0 × in-app 20: "Termux accepts commands from Cline" wraps on
      at most two lines and "Cline kernel 3.0.68-termux.2" is fully visible (no ellipsis).
- [ ] TalkBack: A0 reads in the order above; on A, a row reads "Termux, passed, Termux 0.118.3 is
      installed"; each status change is announced once as "<row>: <status>" (e.g. "Cline kernel:
      needs action"), never as "3 of 5 ready".
- [ ] Dark and light, dynamic colour on and off: Passed glyphs use the fixed success colour, warning
      glyphs the fixed warning colour (DS §1.2 rule 4).

---

## B. Install Termux state

### B.1 Purpose

Explain that the kernel lives in Termux, send the user to a supported Termux build, and come back
by itself the moment Termux is installed and has finished its first run.

### B.2 Desktop equivalent

None: the desktop bundles its runtime. The screen borrows the desktop's
`ImportHistoryStep` "found" layout (large centred icon, `text-3xl` title, muted body, one
full-width primary button max 256dp, a small ghost secondary under it) and the
`OnboardingContent surface="transparent"` treatment.

### B.3 Navigation

| | Behaviour |
| --- | --- |
| Lives at | `kernel/install-termux` |
| Entered from | Row 1 "Install Termux" in A (first run, pushed on top of A); start destination when onboarding is complete but Termux is missing or disabled; the coordinator reporting `TermuxMissing` at any time (replaces the shell, `popUpTo(shell) { inclusive = false }` + `launchSingleTop`) |
| Left by | Automatically when P1 passes **and** the Termux bootstrap has run (P3 does not return `TermuxNotSetUp`): back to A (first run) or to the shell (otherwise). "Check again" does the same check on demand |
| Back | Pushed on top of A: pops to A. As a shell replacement: not intercepted (leaves the app) |

### B.4 Layout

Compact:

```
┌────────────────────────────────────────┐
│ ←                                      │ (only when pushed from A)
│                                        │
│              ▣  terminal 48dp          │ icon onSurfaceVariant
│                                        │
│            Install Termux              │ headlineMedium SemiBold, centred (plain title, 0.7)
│                                        │
│  Cline runs its agent inside Termux,   │ bodyLarge onSurfaceVariant
│  a terminal app for Android. Install   │
│  Termux, then come back here. Cline    │
│  continues by itself.                  │
│                                        │
│  Use the F-Droid or GitHub build.      │ bodyMedium onSurfaceVariant
│  Cline is tested with Termux 0.118.    │
│                                        │
│   ┌────────────────────────────────┐   │
│   │   Get Termux on F-Droid    ↗   │   │ Button, max 320dp
│   └────────────────────────────────┘   │
│   ┌────────────────────────────────┐   │
│   │   Get Termux on GitHub     ↗   │   │ OutlinedButton
│   └────────────────────────────────┘   │
│              Check again               │ TextButton
│                                        │
│  ⟳ Waiting for Termux…                 │ bodySmall + 16dp spinner, polite live region
└────────────────────────────────────────┘
```

Tablet: the same column inside the `plain` OnboardingScaffold card (592dp, transparent over the
grid), vertically centred. Expanded landscape uses the same single column (the content is short).

### B.5 States and copy

| State | Trigger | Title | Body | Actions |
| --- | --- | --- | --- | --- |
| Missing | P1 `NameNotFoundException` | "Install Termux" | "Cline runs its agent inside Termux, a terminal app for Android. Install Termux, then come back here. Cline continues by itself." + "Use the F-Droid or GitHub build. Cline is tested with Termux 0.118." | "Get Termux on F-Droid" (opens `https://f-droid.org/packages/com.termux/`), "Get Termux on GitHub" (opens `https://github.com/termux/termux-app/releases/latest`), "Check again" |
| Too old | `versionName < 0.109` | "Update Termux" | "Termux %1$s is too old for Cline. Install Termux 0.109 or later from the same place you got it, then come back here." | same three buttons |
| Disabled | `applicationInfo.enabled == false` | "Turn on Termux" | "Termux is turned off on this device. Turn it back on in Settings, then come back here." | "Open Termux settings" (`ACTION_APPLICATION_DETAILS_SETTINGS`, `package:com.termux`), "Check again" |
| Installed, not set up | P1 passes, P3 → `TermuxNotSetUp` | "Open Termux once" | "Termux is installed. Open it once and wait until it shows a prompt, so it can finish installing its basic packages." | "Open Termux" (primary), "Check again" |
| Checking | "Check again" tapped, or `PACKAGE_ADDED` received | unchanged | status line "Checking…" | buttons disabled |
| Waiting (idle) | default in the first three states | unchanged | status line "Waiting for Termux…" with spinner | |

Browser links go through the shared `openUrl` helper (0.7): Custom Tab (`androidx.browser`),
then `ACTION_VIEW`, then the snackbar "No browser found to open %1$s." (**A**) with the URL
copied to the clipboard.

### B.6 Interactions

- Register a context receiver for `Intent.ACTION_PACKAGE_ADDED`, `ACTION_PACKAGE_REPLACED` and
  `ACTION_PACKAGE_CHANGED` with `addDataScheme("package")` while B is in the composition; filter for
  `com.termux`; on receipt run P1 → P3.
- On every `ON_RESUME`, run P1 (and P3 if P1 passes).
- When everything passes: announce "Termux is ready" (**A**) and navigate as in B.3 after 600 ms.

### B.7 Data bindings and ViewModel

`InstallTermuxViewModel(termux: TermuxExecutor, coordinator)`:

```kotlin
data class InstallTermuxUiState(
    val variant: Variant,            // Missing | TooOld(versionName) | Disabled | NotSetUp
    val checking: Boolean,
    val pushedFromSetup: Boolean,    // shows the back arrow
)
```

Kernel API: `PackageManager` (`<queries><package android:name="com.termux"/></queries>`, already
in the manifest), RUN_COMMAND `probeVersion` for the bootstrap check. No Hub calls.

### B.8 Edge cases

- Termux installed from Google Play: package id is also `com.termux`; the app treats it like any
  other build. Whether the Play build's RUN_COMMAND and `$PREFIX` work with the port is open
  question 10.
- Termux:API or other plugins are not required (`termux-wake-lock` is in `termux-tools`).
- The user installs Termux while Cline is killed: start destination logic (0.2) handles it.
- The F-Droid client is not installed: the Custom Tab shows the F-Droid page, which offers the APK.

### B.9 Accessibility

Title is `heading()`; the status line is a polite live region; the external-link icon is decorative
and the buttons' CDs say "Get Termux on F-Droid, opens in browser" (**A**).

### B.10 Acceptance checklist

- [ ] Fake `termux_missing`: B shows "Install Termux", both store buttons open the right pages,
      "Check again" shows "Checking…" then returns to "Waiting for Termux…".
- [ ] Fake `termux_disabled` and `termux_old`: titles, bodies and buttons as in B.5.
- [ ] Fake `termux_not_set_up`: "Open Termux once" opens Termux; returning with the fake switched to
      `off` (real kernel) proceeds automatically within 1 s of resume.
- [ ] As a shell replacement, back leaves the app; when pushed from A, back returns to A.
- [ ] Tab S7+: centred column, no horizontal scroll, buttons ≤ 320dp wide.
- [ ] TalkBack reads the title as a heading and the status line changes.

---

## C. Connect a model (first run), with the Done step

### C.1 Purpose

Give the kernel a working model before the first prompt: "Sign in with Cline" (browser device
flow, run inside Termux) or "Use your own API key" (provider + key + model id, saved by
`cline auth` inside Termux so the TUI and the Hub share it). The app never stores provider secrets.
After success the Done step confirms what is connected.

### C.2 Desktop equivalent

`onboarding-view.tsx` **ConnectStep** and **DoneStep**, copied in structure and copy:
back `IconButton`, "Set up Cline", description, two `SetupOptionCard`s (Cline selected by default,
"Recommended" badge, the three benefits with check icons, "Sign in" / "Waiting for browser..." +
"Cancel" / "Sign up", the collapsible "Use a Cline API key" form with "Find your key"; "Use your
own API key" with "Anthropic, OpenAI, OpenRouter, and more.", provider `Select`, password field,
"Get a <Provider> API key" link, "Connect" / "Connecting..."), the error prefixes ("Sign in failed:",
"Failed to save API key:", "Failed to load providers:", "Failed to save provider:"), the centred
"Skip"; DoneStep's check icon, "You're all set", "<Provider> is connected." / "Your Cline account is
connected.", ClinePass card ("ClinePass", "Generous usage across the best open weights models like
DeepSeek, Kimi, and GLM.", "Get ClinePass") and "Start building". Re-entry from Home copies
`app/page.tsx` `handleOpenSetup` (open onboarding at `connect`). `sortProvidersForApiKeySetup`,
`PREFERRED_PROVIDER_ORDER`, `isApiKeyOnlyProvider`, `OAUTH_PROVIDER_IDS`, `getProviderApiKeyUrl`
and `rememberProviderSelection` are ported to Kotlin 1:1.

Translated: the desktop `Select` becomes an `ExposedDropdownMenuBox` (DS §4.3); the panel card
becomes the OnboardingScaffold `panel`; the desktop's in-app device-code line ("Confirm this code
in your browser:") is shown in Termux instead (C.10).

### C.3 Navigation

| | Behaviour |
| --- | --- |
| Lives at | `onboarding/connect` + `onboarding/done` (first run); `setup/connect` + `setup/done` (re-entry) |
| Entered from | First run: "Continue" on A or D. Re-entry: Home's WelcomeSetupNotice "Connect a model" (DS §4.2), a credential error's "Sign in to Cline" when no Cline credentials exist at all (chat spec), Settings → Providers empty state (settings spec) |
| Left by | Success → Done. "Skip" → finishes onboarding → Home (first run) / pops back (re-entry). Done → "Start building" → Home with a new thread (first run: writes `completedAt`, clears the graph) / pops to the entry point (re-entry) |
| Back | Top-bar back and system back: while a Cline sign-in is waiting, back first cancels it (same as "Cancel", DS §5.8 item 4 analogue), a second back navigates. Otherwise first run → A (or D if it was shown); re-entry → pops. From Done: back returns to Connect, which now shows the connected state with "Continue" (never re-runs a sign-in) |
| Predictive back | shared-axis-X seekable pop; disabled (`BackHandler` intercept) only during the sign-in cancel case |

### C.4 Layout

**Connect, compact:**

```
┌────────────────────────────────────────┐
│ ←                                      │
│ Set up Cline                           │ headlineSmall SemiBold
│ Choose how Cline connects to models.   │ bodyMedium onSurfaceVariant
│ You can add more providers anytime in  │
│ Settings.                              │
│                                        │
│ ┌────────────────────────────────────┐ │ SetupOptionCard (selected)
│ │ [▣] Sign in with Cline [Recommended]│ │
│ │ ✓ Regular free model promotions     │ │
│ │ ✓ Subscribe to ClinePass for        │ │
│ │   generous usage across the best    │ │
│ │   open weights models like          │ │
│ │   DeepSeek, Kimi, and GLM           │ │
│ │ ✓ No API key needed                 │ │
│ │                                     │ │
│ │ [ Sign in ]   Sign up               │ │ Button + TextButton
│ │                                     │ │
│ │ Use a Cline API key ⌄               │ │ TextButton (labelLarge) + expand_more
│ └────────────────────────────────────┘ │
│ ┌────────────────────────────────────┐ │ SetupOptionCard (unselected, whole card taps)
│ │ [⚿] Use your own API key           │ │
│ │ Anthropic, OpenAI, OpenRouter, and │ │
│ │ more.                              │ │
│ └────────────────────────────────────┘ │
│                                        │
│                 Skip                   │ TextButton centred
└────────────────────────────────────────┘
```

**Connect, Cline card while waiting:**

```
│ │ [ ⟳ Waiting for browser... ] Cancel │ │ Button disabled with spinner + TextButton
│ │ Termux opened to sign you in. Open  │ │ bodyMedium onSurfaceVariant
│ │ the link it shows, approve the      │ │
│ │ sign-in in your browser, then come  │ │
│ │ back to Cline.                      │ │
│ │ Open Termux                         │ │ TextButton
```

**Connect, API-key card selected:**

```
│ ┌────────────────────────────────────┐ │
│ │ [⚿] Use your own API key           │ │
│ │ Anthropic, OpenAI, OpenRouter, and │ │
│ │ more.                              │ │
│ │ ┌ Provider ────────────────────▾┐  │ │ ExposedDropdownMenuBox (OutlinedTextField, readOnly)
│ │ │ Anthropic                     │  │ │
│ │ └───────────────────────────────┘  │ │
│ │ ┌ API key ─────────────────── 👁┐  │ │ OutlinedTextField, password, trailing show/hide
│ │ │ ••••••••••••••••              │  │ │
│ │ └───────────────────────────────┘  │ │
│ │ ┌ Model ────────────────────────┐  │ │ OutlinedTextField, ClineMono.body text
│ │ │ claude-…                      │  │ │ supporting: "Cline starts new sessions…"
│ │ └───────────────────────────────┘  │ │
│ │ Get a Anthropic API key ↗          │ │ TextButton (desktop wording kept, see C.5)
│ │                       [ Connect ]  │ │ Button end-aligned
│ │ Failed to save provider: …         │ │ bodySmall error (role alert)
│ └────────────────────────────────────┘ │
```

For `openai-compatible`, a "Base URL" field (placeholder "https://api.example.com/v1") appears
between "API key" and "Model" and is required.

**Keyboard and insets (Connect is the only screen in this group with text fields).** The
OnboardingScaffold rules apply (0.7): the content column scrolls, and on compact the screen has
no bottom action bar, so the scroll column takes `windowInsetsPadding(WindowInsets.ime.union(
bottomSafe))`. Each field (Cline API key, Provider, API key, Base URL, Model) has a
`BringIntoViewRequester`; on focus gain it calls `bringIntoView()` for a rect that spans the
field **down to the end-aligned "Connect" button and the error line under it**, so the focused
field, its supporting text, "Connect" and any error stay visible above the IME. Verified on a
412×915dp window at font scale 1.0 and 2.0. On medium+ the card scrolls inside the window the
same way. Opening the provider dropdown hides the IME (the field is `readOnly`).

**Done, compact:**

```
┌────────────────────────────────────────┐
│                                        │
│               ✔ 40dp                   │ check_circle fill, primary
│           You're all set               │ headlineMedium SemiBold (plain title, 0.7)
│     Your Cline account is connected.   │ bodyLarge onSurfaceVariant
│                                        │
│ ┌────────────────────────────────────┐ │ OutlinedCard (only for kind == cline)
│ │ ClinePass                          │ │ titleSmall SemiBold
│ │ Generous usage across the best     │ │ bodySmall onSurfaceVariant
│ │ open weights models like DeepSeek, │ │
│ │ Kimi, and GLM.                     │ │
│ │                  [ Get ClinePass ↗]│ │ FilledTonalButton
│ └────────────────────────────────────┘ │
│                                        │
│      ┌──────────────────────────┐      │
│      │      Start building      │      │ Button, max 256dp
│      └──────────────────────────┘      │
└────────────────────────────────────────┘
```

**Tablet (medium and expanded):** Connect sits in the 592dp `panel` card; the two option cards
stack vertically inside it (as on the desktop). Done sits in the `plain` card over the `wide-grid`
backdrop variant (the grid is drawn only in a horizontal band behind the content, desktop
`layout="wide-grid"`). The provider dropdown menu width matches its field.

### C.5 States and copy

**Connect, screen-level:**

| State | Visual and copy |
| --- | --- |
| loading (first entry) | both cards render immediately; the Cline card's buttons and the API-key form are disabled for up to 1 s while `readProviders` runs; a `LinearProgressIndicator` (indeterminate, 2dp) sits under the description |
| empty (no provider configured) | default: Cline card selected, buttons enabled |
| populated: Cline already connected (`hasOAuth` or `hasApiKey` on provider `cline`) | Cline card body replaced by the desktop signed-in row: "Signed in as %1$s" (**D**, when the settings helper is available and `cline_account {operation:"fetchMe"}` returns a name/email) or "Signed in to Cline" (**A**, otherwise) + `Button` "Continue" (end-aligned) |
| populated: an API-key provider already connected | API-key card shows a line "%1$s is connected." (**D**, the Done-step string) + "Continue" above the form; the form stays usable to add another |
| running: Cline sign-in waiting | as in the wireframe. Matching the desktop, `clineBusy` disables only the Cline card's "Sign in" button and the "Use a Cline API key" toggle; the API-key card stays selectable and usable, and selecting it does **not** cancel the sign-in (the Cline card body is then absent, desktop `inert`, while detection keeps running; selecting the Cline card again shows "Waiting for browser..." + "Cancel"). If the sign-in completes while the API-key card is selected, the app goes to Done as usual. "Skip" stays enabled |
| running: saving a key | the active "Connect" shows a 16dp spinner + "Connecting..." (**D**); fields disabled |
| error | inline `bodySmall` `error` text with `LiveRegionMode.Assertive` under the action that failed (desktop `role="alert"`): "Sign in failed: %1$s", "Failed to save API key: %1$s", "Failed to save provider: %1$s" (all **D**) |
| error: provider catalog | only possible when the settings helper answered `/health` but `list_provider_catalog` failed (without the helper the bundled list is used and cannot fail). Unlike the desktop, which replaces the `Select` with the alert line and offers no picker, the app keeps a usable picker: "Failed to load providers: %1$s" (**D**) shows in `error` **above** the Provider field, the field falls back to the bundled list (source `Bundled`, "More providers are in Settings → Providers." shown), and a `TextButton` "Try again" (**D**, `kernel_try_again`) next to the message re-requests the catalog (**A**: deviation because the bundled list always exists) |
| offline / kernel down | the kernel is needed only for RUN_COMMAND, not for the Hub. If P1–P3 fail on entry: a `Surface(warning.container, shape = large)` card above the options: "Cline can't reach Termux right now." (**A**) + `TextButton` "Open kernel setup" (→ the **pushed** repair variant `kernel/repair?focus=…&pushed=true`, back returns here). All actions are disabled until it is fixed |
| permission missing | same card, button opens the repair variant focused on row 2 |

**Cline card copy (all **D** unless marked):** title "Sign in with Cline"; badge "Recommended";
benefits "Regular free model promotions", "Subscribe to ClinePass for generous usage across the
best open weights models like DeepSeek, Kimi, and GLM", "No API key needed"; buttons "Sign in" /
"Waiting for browser..." / "Cancel" / "Sign up"; the waiting help text (**A**) "Termux opened to
sign you in. Open the link it shows, approve the sign-in in your browser, then come back to Cline Termux.";
"Open Termux" (**A**); disclosure "Use a Cline API key" (**D**), field label/placeholder "Cline API
key", "Connect" / "Connecting...", "Find your key"; timeout error (**A**, inside the **D** prefix)
"Sign in failed: the sign-in did not finish within 15 minutes."; Termux exit error (**A**) "Sign in
failed: cline auth exited with code %1$d in Termux."

**API-key card copy:** title "Use your own API key" (**D**); description "Anthropic, OpenAI,
OpenRouter, and more." (**D**); provider field label "Provider" (**D**, desktop aria-label),
placeholder "Choose a provider" / "Loading providers..." (**D**); key field label "API key" (**D**),
placeholder "%1$s API key" (**D**, e.g. "Anthropic API key"); show/hide CDs "Show API key" / "Hide
API key" (**D**, add-provider.tsx); model field label "Model" (**D**, ModelSelector section label),
supporting "Cline starts new sessions with this model. You can switch models in the composer."
(**A**); base URL label "Base URL" (**A**), placeholder "https://api.example.com/v1" (**D**); key
link "Get a %1$s API key" (**D**; the catalog `docLabel` wins when the settings helper supplies
one); bundled-list note when the helper is unavailable (**A**) "More providers are in Settings →
Providers."; "Connect" / "Connecting..." (**D**).

Validation (client side, before any RUN_COMMAND): provider chosen; key non-blank after trim; model
non-blank; base URL required for `openai-compatible` and must parse as `http(s)://`. Errors under
the field (`supportingText`, `isError`): "Enter a model ID." (**A**), "Enter a base URL that starts
with http:// or https://." (**A**). "Connect" is disabled until valid (desktop:
`!selectedProvider || !apiKey.trim() || saving`).

**Done copy (**D**):** "You're all set"; "%1$s is connected." (provider display name) or "Your
Cline account is connected."; ClinePass card only for `kind == cline`. The desktop's "Free models"
list (`list_cline_recommended_models`) is a desktop-sidecar command the kernel does not expose, so it
is omitted; the desktop renders the ClinePass row alone when that call fails, which is what the app
shows. When a Cline API key was saved without verification (C.7) an extra `bodySmall` line reads
"Cline checks the key when you send your first message." (**A**). Button "Start building".
"Get ClinePass" opens `CLINE_PASS_SUBSCRIBE_URL = "https://app.cline.bot/onboarding/individual-plan"`
(desktop `onboarding-view.tsx` line 56) through `openUrl` (0.7), with the trailing
`open_in_new` icon; its CD is "Get ClinePass, opens in browser" (**A**, `common_link_cd`
template).

### C.6 Interactions and gestures

- Tapping an unselected option card selects it (desktop `setSelectedMethod`); selecting "Use your
  own API key" collapses the Cline-key form. The newly selected card's first enabled control
  receives focus (desktop `SetupOptionCard` focus effect).
- **Sign in (Cline):**
  1. Record `onboarding.signIn.startedAt = now`, increment `signInAttempt` (desktop
     `signInAttemptRef`).
  2. Issue `authClineInteractive` (foreground runner: new Termux session runs
     `cline auth cline`), then immediately `startActivity(Intent().setClassName("com.termux",
     "com.termux.app.TermuxActivity").addFlags(FLAG_ACTIVITY_NEW_TASK))`. This is allowed because the
     app is in the foreground, and it makes Termux the foreground app so its `xdg-open` /
     `termux-open` can open the browser (open question 5 finding).
  3. Show "Waiting for browser..." + help text + "Open Termux".
  4. Completion detection, three triggers, none of which polls the secrets file: (a) every 3 s
     while Connect is visible and the app is `STARTED`, RUN_COMMAND `statProviders` (the mtime
     only, no contents); when the mtime (seconds × 1000) is later than `signIn.startedAt`, run
     `readProviders` **once**; (b) on `ON_RESUME`, run `readProviders` once; (c) when a
     RUN_COMMAND result for the foreground session arrives (open question 11), run
     `readProviders` once. Success when provider `cline` has `hasOAuth == true` and its
     `updatedAt` is after `startedAt`; a read that does not show success leaves the stat poll
     running (the mtime baseline moves to the read's mtime, so the same write is not read twice).
     `readProviders` is never issued on a timer. Give up after 15 minutes
     (`OAUTH_LOGIN_TIMEOUT_MS`) with the timeout error. If the foreground-session result arrives
     with `exitCode != 0`, show the exit error.
  5. On success: `rememberProviderSelection(cline)` → `model.selection.lastProvider = "cline"`; clear
     `signIn.startedAt`; navigate to Done with `OnboardingConnection.Cline`.
- **Cancel (sign-in):** increment `signInAttempt` (a late success is then ignored for navigation but
  still refreshes the configured state, desktop comment "credentials were saved"); clear
  `signIn.startedAt`; issue `cancelClineAuth` (retry up to 3 times with 250/500/750 ms, desktop
  loop). The Termux session shows the process ended; the app does not close Termux sessions.
- **Sign up:** opens `https://app.cline.bot` (desktop `CREATE_ACCOUNT_URL`) via `openUrl` (0.7).
- **Use a Cline API key:** toggles the disclosure (`stateDescription` Expanded/Collapsed). "Connect"
  issues `authProvider("cline", key, CLINE_DEFAULT_MODEL_ID)`. When the settings helper is available
  (dashboard, open question 3), it then verifies with `cline_account {operation:"fetchMe"}`; a
  `{signedIn:false}` result or an error rolls back with `save_provider_settings {provider:"cline",
  api_key:""}` and shows "Failed to save API key: the key could not be verified (%1$s)" (**D**
  wording from `connectWithClineApiKey`). Without the helper the key is saved unverified (Done shows
  the extra line). Enter on the key field submits when valid. "Find your key" opens
  `https://app.cline.bot` (`CLINE_DASHBOARD_URL`).
- **Provider dropdown:** options in `sortProvidersForApiKeySetup` order. Choosing one clears the save
  error, prefills "Model" with the provider's `defaultModelId` (only if the user has not typed a
  model), and shows/hides "Base URL".
- **Connect (API key):** `authProvider(id, key.trim(), model.trim(), baseUrl?.trim())`. Success =
  exit 0 (stdout "Provider configured: <id> (<model>)" after stripping ANSI). Failure = the last
  non-blank stderr line, ANSI stripped, inside "Failed to save provider: %1$s" (e.g. `invalid
  provider "x"`, `base URL is only supported for OpenAI and OpenAI-compatible providers`). On
  success: `rememberProviderSelection(id, model)`; clear the key from memory; Done with
  `OnboardingConnection.Provider(name)`.
- **Get a <Provider> API key:** opens `getProviderApiKeyUrl(provider)` via `openUrl`; hidden when null.
- **Get ClinePass (Done):** opens `CLINE_PASS_SUBSCRIBE_URL` via `openUrl`; Done stays on screen.
- **Skip:** first run → `completeOnboarding()`; re-entry → pop. Home then shows the setup notice.
- **Continue (already connected):** → Done with the matching connection.
- **Start building (Done):** first run → `completeOnboarding()` (write `completedAt`, clear the
  onboarding graph, open Home with a new thread); re-entry → pop to the entry point and refresh the
  configured-provider state so the setup notice disappears.
- Keyboard: IME action Next moves Provider → API key → (Base URL) → Model; Done on the last field
  submits when valid. Hardware Enter submits the focused form. Every focus change brings the
  field and "Connect" into view above the IME (C.4 "Keyboard and insets").

### C.7 Data bindings and ViewModel

`ConnectModelViewModel(termux, settingsHelper: SettingsHelper?, prefs, clock)`:

```kotlin
enum class SetupMethod { Cline, ApiKey }
sealed interface OnboardingConnection { data object Cline : OnboardingConnection; data class Provider(val name: String) : OnboardingConnection }
sealed interface ClineSignInUi {
    data object Idle : ClineSignInUi
    data class Waiting(val startedAtEpochMs: Long, val attempt: Int) : ClineSignInUi
    data class SignedIn(val displayName: String?) : ClineSignInUi
    data class Error(val message: String) : ClineSignInUi
}
data class ProviderOption(val id: String, val name: String, val defaultModelId: String?,
                          val needsBaseUrl: Boolean, val keyUrl: String?, val docLabel: String?)
sealed interface ProviderOptionsUi {
    data object Loading : ProviderOptionsUi
    data class Loaded(val options: List<ProviderOption>, val source: Source,       // Helper | Bundled
                      val catalogError: String? = null) : ProviderOptionsUi       // helper up, catalog failed → Bundled + message
}
data class ConnectUiState(
    val kernelUsable: Boolean,                    // P1–P3 pass
    val configured: ConfiguredProviders?,         // null while loading; redacted (0.6)
    val method: SetupMethod = SetupMethod.Cline,
    val cline: ClineSignInUi,
    val clineKeyFormExpanded: Boolean,
    val clineKeySaving: Boolean, val clineKeyError: String?,
    val providers: ProviderOptionsUi,
    val selectedProviderId: String?,
    val modelId: String, val baseUrl: String,
    val saving: Boolean, val saveError: String?,
    val fieldErrors: Map<Field, String>,
)
// The API key texts are NOT in ConnectUiState: they are `remember { mutableStateOf("") }` in the
// composable and passed to the ViewModel only as a function argument at submit time.
```

Kernel API (feature matrix): "Provider setup minimal (API key + model id) for first run" →
RUN_COMMAND `cline auth --provider <id> --apikey <key> --modelid <model> [--baseurl <url>]` (exit 0,
"Provider configured: <id> (<model>)"; writes `providers.json` and `lastUsedProvider`). "Sign in with
Cline (device-code OAuth) and sign out" → RUN_COMMAND `cline auth cline` in the foreground runner.
"Welcome setup notice (no provider connected)" → RUN_COMMAND `cat ~/.cline/data/settings/providers.json`
(read once at entry and after every auth action, never persisted). When the settings helper runs:
"Provider catalog (configured / popular / all, auth kind, config fields)" → dashboard
`list_provider_catalog` (filtered by `sortProvidersForApiKeySetup`), "Account" → `cline_account
{operation:"fetchMe"}`, "Save provider settings" → `save_provider_settings` for the rollback only.

Provider list sources, in order:
1. Settings helper available (`GET http://127.0.0.1:8787/health` answers within 1 s; the app does
   **not** start the dashboard for onboarding): `list_provider_catalog`, filtered and sorted.
2. Otherwise the bundled list `app/src/main/assets/kernel/providers-fallback.json`, generated at
   build time by `tools/gen-provider-fallback.ts` from
   `sdk/packages/llms/src/providers/providers.generated.ts` at the pinned kernel tag: the
   `PREFERRED_PROVIDER_ORDER` ids that exist there (`anthropic`, `openai-native`, `openrouter`,
   `gemini`, `xai`, `groq`, `mistral`, `deepseek`, and `ollama` only if its entry is API-key-only),
   each with `name` and `defaultModelId` copied from the generated file (never typed by hand), plus
   `openai-compatible` (name "OpenAI Compatible", no default model, `needsBaseUrl = true`). The Cline
   default model comes from `CLINE_DEFAULT_MODEL_ID` in `sdk/packages/shared/src/providers/defaults.ts`
   through the same generator.

### C.8 Edge cases

| Case | Behaviour |
| --- | --- |
| App killed while Termux is foreground during sign-in | relaunch restores `Waiting` from `onboarding.signIn.startedAt` (if < 15 min old) and polls |
| Sign-in finished but user stays in the browser | detected on the next resume |
| Browser does not open from Termux (no default browser) | the URL and code are visible in Termux; the help text already says "Open the link it shows" |
| User signs in from the TUI in parallel | detected the same way: the stat poll sees the new mtime and one `readProviders` confirms it |
| `statProviders` keeps failing (Termux busy, RUN_COMMAND timeouts) | the poll backs off to every 10 s after two consecutive timeouts; the resume trigger still works |
| `providers.json` unreadable JSON | treated as "no providers"; logged as a warning without contents |
| `cline auth` returns `invalid provider` (catalog drift between bundled list and kernel) | the **D** save error with the stderr text; the provider stays selectable |
| Kernel becomes unavailable mid-save (RUN_COMMAND timeout 30 s) | "Failed to save provider: Termux didn't answer within 30 seconds." (**A** detail) |
| Re-entry while a session is running | allowed; saving a provider never touches live sessions (the next `session.create` picks it up; `session.update_connection` is the model picker's job) |
| Credentials expired later | not this screen: `run.failed` / `session.notice api_error` map to "Sign in to Cline" / "Open API providers" (chat spec) |

### C.9 Accessibility

- The two option cards are a radio group (`selectableGroup`, `Role.RadioButton`, contentDescription =
  title, stateDescription "Selected"/"Not selected"). The desktop's hidden select button label
  ("Sign in with Cline" / "Use your own API key") is the card's click label.
- Unselected card bodies are absent from the tree (desktop `inert`).
- Password fields set `semantics { password() }`; the show/hide toggle is a separate 48dp
  `IconButton` with CD "Show API key" / "Hide API key".
- Errors are assertive live regions; the waiting state announces "Waiting for browser..." politely.
- "Recommended" badge is read after the title ("Sign in with Cline, Recommended").
- Done: "You're all set" is `heading()`; the check icon is decorative.

### C.10 Device-code note

The desktop shows "Confirm this code in your browser: <code>" from `provider_oauth_user_code`
pushes. On Android the device code is printed by `cline auth cline` in the Termux session and
prefilled in the verification URL, so the app does not show it. If the settings-helper route ever
proves able to drive sign-in and deliver the code (open question 3 and 5), the app adds the desktop
line verbatim under the waiting button.

### C.11 Acceptance checklist

- [ ] Fake `providers_empty`: Connect shows "Set up Cline", the Cline card selected, both cards and
      "Skip" as in the wireframe; strings match section 9 character for character.
- [ ] Real Cline sign-in (owner OK, outside release windows): "Sign in" opens a Termux session
      running `cline auth cline` and brings Termux to the front; the browser opens from Termux;
      after approving and returning, Connect advances to Done ("Your Cline account is connected.")
      within 2 s of resume.
- [ ] During that sign-in, a debug-build RUN_COMMAND trace (the executor's in-memory call log,
      ids only) shows `statProviders` every 3 s and `readProviders` at most once per trigger
      (entry, resume, mtime change), never on a timer.
- [ ] While "Waiting for browser..." shows, the API-key card can be selected and a key saved;
      the sign-in keeps waiting (Termux session still running) and Cancel is back when the Cline
      card is selected again.
- [ ] Done for the Cline path: "Get ClinePass" opens
      `https://app.cline.bot/onboarding/individual-plan` in a Custom Tab; returning shows Done
      unchanged.
- [ ] Cancel during waiting stops `cline auth cline` in Termux (`ssh <phone> pgrep -af "auth cline"`
      shows nothing within 3 s) and returns the card to "Sign in".
- [ ] Back during waiting cancels first, then navigates.
- [ ] Force-stop the app during waiting (`adb shell am force-stop io.github.ichouchiang.clinetermux`), relaunch: the
      waiting state is restored.
- [ ] Fake `signin_never`: after 15 minutes (or a debug clock override) the timeout error shows.
- [ ] API key path with fake `auth_ok`: choose "Anthropic", type a key, model prefilled from the
      generated list, "Connect" → "Connecting..." → Done "Anthropic is connected.".
- [ ] Fake `auth_fail_invalid_provider`: "Failed to save provider: invalid provider …" under Connect.
- [ ] `openai-compatible` requires a base URL; "Connect" stays disabled until it parses.
- [ ] With the keyboard open on the Model field (S25U portrait, font scale 1.0 and 2.0), the
      field, its supporting text and "Connect" are visible; no content is behind the IME; the
      same holds with an error line shown under "Connect".
- [ ] Helper up with a failing catalog (debug fake): "Failed to load providers: …" shows above a
      working Provider field filled from the bundled list; "Try again" re-requests.
- [ ] Fake `providers_ollama`: Home shows no "Connect a model" notice; C's API-key card shows
      "Ollama is connected." + "Continue".
- [ ] Rotate the S25U and background the app with a key typed: after returning, the key field is
      empty if more than 10 s passed; the Recents thumbnail does not show the screen (API 36).
- [ ] `adb logcat -d | grep -i -E "apikey|sk-"` after a save shows nothing from `io.github.ichouchiang.clinetermux`.
- [ ] Fake `providers_anthropic`: the API-key card shows "Anthropic is connected." + "Continue".
- [ ] "Skip" on first run lands on Home with the "Connect a model to start building" notice; its
      "Connect a model" button opens `setup/connect`; finishing there pops back and hides the notice.
- [ ] After either exit (Skip or "Start building") no What's new dialog appears, and
      `whatsNew.seenReleaseId` equals the newest bundled release id.
- [ ] Tab S7+ both orientations: 592dp panel, dropdown menu aligned to its field, Done on the
      wide-grid band.
- [ ] TalkBack: radio-group semantics on the cards, password semantics, errors announced at once.

---

## D. One UI battery checklist ("Keep the kernel running")

### D.1 Purpose

Prevent the most common cause of "Termux asleep": Android/One UI freezing or killing Termux (and
with it the Hub) or this app in the background, and Android's phantom-process killer ending the
Hub's child processes. One page lists every relevant setting, verifies the ones the app can read,
and lets the user confirm the ones it cannot.

### D.2 Desktop equivalent

None (no battery management on desktop). Layout follows the desktop Settings section pattern
(`PageFrame` + `PageHeader` title/description + bordered rows with a title, a muted description and
one trailing action, `settings-view.tsx` "New user experience" row), translated to the voice app's
`PageScaffold` + `PrefRow` + `StateLine` system rows (`ui/SettingsScreen.kt` battery and
notification rows, re-read on `ON_RESUME`).

### D.3 Navigation

| | Behaviour |
| --- | --- |
| Lives at | `onboarding/reliability` (onboarding variant, OnboardingScaffold `panel`, with footer actions); `kernel/reliability` (standalone, `PageScaffold` with `MediumTopAppBar` and back arrow, CD "Back") |
| Entered from | Onboarding: "Continue" on A, once (D.4). Standalone: Kernel status sheet row "Background reliability"; the Termux-asleep panel's "Why did this happen?"; Settings → Kernel → "Background reliability" (settings spec) |
| Left by | Onboarding: "Continue" or "Skip for now" (**D**, ImportHistoryStep) → C; both set `onboarding.reliabilityShownAt`. Standalone: back |
| Back | Onboarding: → A. Standalone: pop (predictive shared-axis-X). Leaving for a system settings screen and returning re-reads every check (`ON_RESUME`) |

### D.4 When it shows automatically

Exactly once, during onboarding, after A passes, **only if** at least one verifiable item (1, 2, 5
below) is not done. Otherwise it is skipped and `onboarding.reliabilityShownAt` is still written.

### D.5 Layout

Compact (standalone):

```
┌────────────────────────────────────────┐
│ ←                                      │
│ Keep the kernel running                │ MediumTopAppBar title (collapses on scroll)
├────────────────────────────────────────┤
│ Android and One UI stop apps in the    │ bodyMedium onSurfaceVariant (page description)
│ background to save battery. When that  │
│ happens to Termux, the Cline Hub stops │
│ and running tasks are interrupted.     │
│ These settings prevent it.             │
│ 3 of 5 done  ━━━━━━━━━━━━━━━━━░░░░░░░  │ labelLarge + determinate LinearProgressIndicator
│                                        │
│ Required                               │ titleSmall primary (PrefHeader)
│ ▣ Termux battery            [ Done  ]  │ ReliabilityItem, status pill success
│   Unrestricted                         │
│ ▣ Cline battery             [Not set]  │ status pill warning, expanded:
│   Optimized — Android may stop Cline   │
│   in the background.                   │
│   [ Allow ]                            │ FilledTonalButton
│ ▣ Never sleeping apps   [Check yourself]│
│   Add Termux and Cline to Never        │
│   sleeping apps.                       │
│   Settings → Battery → Background      │ bodySmall instructions
│   usage limits → Never sleeping apps   │
│   [ Open battery settings ]            │
│   ☐ I've done this                     │ Checkbox row, 48dp
│                                        │
│ Recommended                            │
│ ▣ Child process restrictions [Not set] │
│ ▣ Notifications              [ Done  ] │
│                                        │
│ Optional                               │
│ ▣ Termux: display over other apps      │
└────────────────────────────────────────┘
```

Onboarding variant: same rows inside the `panel` card, title "Keep the kernel running" in the
OnboardingScaffold panel title style (`headlineSmall` SemiBold, 0.7) in the body (no app bar title), and a footer with `TextButton` "Skip for now"
(start) and `Button` "Continue" (end).

Tablet: standalone page content column max 640dp centred under the app bar; on expanded with the
settings list-detail scaffold (DS §5.2) it renders in the detail pane when opened from Settings,
and as a pushed full-screen page when opened from the sheet or the asleep panel.

### D.6 Items, checks, actions and copy

All texts **A**. "Done" / "Not set" / "Check yourself" are the status pill labels.

| # | Group | Shown when | Title | Verify (re-read on every `ON_RESUME`) | Supporting text by status | Action |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Required | always | "Termux battery" | `PowerManager.isIgnoringBatteryOptimizations("com.termux")` | Done "Unrestricted"; Not set "Optimized — Android may stop Termux in the background." | "Open Termux settings" → `ACTION_APPLICATION_DETAILS_SETTINGS` `package:com.termux`; instruction line "Tap Battery, then choose Unrestricted." |
| 2 | Required | always | "Cline battery" | `PowerManager.isIgnoringBatteryOptimizations(packageName)` | Done "Unrestricted"; Not set "Optimized — Android may stop Cline in the background." | "Allow" → `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` with `package:io.github.ichouchiang.clinetermux` (system dialog; needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, open question 7); fallback app details + "Tap Battery, then choose Unrestricted." |
| 3 | Required | `Build.MANUFACTURER.equals("samsung", true)` | "Never sleeping apps" | not verifiable → self-check `reliability.neverSleepingConfirmed` | Check yourself "Add Termux and Cline to Never sleeping apps." + instruction "Settings → Battery → Background usage limits → Never sleeping apps" ; Done (confirmed) "You added Termux and Cline." | "Open battery settings" → explicit Samsung component (open question 5 to verify on One UI 8), fallback `Settings.ACTION_SETTINGS`; `Checkbox` "I've done this" |
| 4 | Recommended | `SDK_INT >= 31` | "Child process restrictions" | try `Settings.Global.getString(cr, "settings_enable_monitor_phantom_procs")`: `"false"` = Done; `null`/`"true"` = Not set; `SecurityException` = self-check `reliability.childProcessConfirmed` | Not set "Android may stop the Hub's helper processes when many are running. Turn on “Disable child process restrictions” in Developer options." ; when Developer options are off (`Settings.Global.DEVELOPMENT_SETTINGS_ENABLED == 0`) an extra line "Turn on Developer options first: Settings → About phone → Software information → tap Build number 7 times."; on API 31–33 the developer toggle does not exist: show CommandBlock `adb shell settings put global settings_enable_monitor_phantom_procs false` with "Run this from a computer with adb." | "Open developer options" → `ACTION_APPLICATION_DEVELOPMENT_SETTINGS` (hidden on API 31–33) |
| 5 | Recommended | `SDK_INT >= 33` | "Notifications" | `NotificationManagerCompat.areNotificationsEnabled()` | Done "On"; Not set "Off — you won't hear about approvals or finished tasks while Cline is in the background." | "Allow" → `RequestPermission(POST_NOTIFICATIONS)` first time, then `ACTION_APP_NOTIFICATION_SETTINGS`; none once On, and the item then has nothing to expand into, so its row is not a toggle (trial row 14) |
| 6 | Optional | always | "Termux: display over other apps" | not verifiable → self-check `reliability.overlayConfirmed` | Check yourself "Lets Termux open your browser by itself during Cline sign-in. Not needed for anything else." | "Open Termux setting" → `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` `package:com.termux`; `Checkbox` "I've done this" |

Counting: "%1$d of %2$d done" counts shown items in groups Required + Recommended (optional is not
counted). Self-checked items count as done when confirmed.

Screen states:

| State | Visual |
| --- | --- |
| loading | first frame computes all checks synchronously (all are local calls); no spinner |
| populated | as above; the first not-done item is expanded, others collapsed with their status. An item that becomes done collapses like the items that were done already, also when the user had opened it (trial row 14); a done item the user opens stays open |
| all done | a `Surface(success.container)` line at the top "Cline and Termux can keep running in the background." (**A**) and every item collapsed |
| error | an intent fails to resolve (`ActivityNotFoundException`): snackbar "This setting isn't available on this device." (**A**) and the item's instructions stay visible |
| offline / kernel down | not relevant (no kernel calls); the page works without Termux running |
| permission missing | n/a, except notifications (item 5) |

### D.7 Data bindings and ViewModel

No kernel API. Android APIs only (feature matrix rows "Kernel settings: wake lock, keep Hub
running…" context and kernel contract "shows the One UI checklist once").

```kotlin
enum class ReliabilityItemId { TermuxBattery, ClineBattery, NeverSleeping, ChildProcess, Notifications, TermuxOverlay }
enum class ItemStatus { Done, NotSet, SelfCheck, SelfConfirmed }
data class ReliabilityItemUi(val id: ReliabilityItemId, val group: Group, val status: ItemStatus,
                             val developerOptionsOff: Boolean = false, val legacyAdbOnly: Boolean = false)
data class ReliabilityUiState(val items: List<ReliabilityItemUi>, val doneCount: Int, val countedTotal: Int,
                              val variant: Variant)   // Onboarding | Standalone
```

`ReliabilityViewModel.refresh()` runs on every `ON_RESUME` (voice `rememberSystemChecks`
pattern). `doneCount`/`countedTotal` are also exposed to E ("%1$d of %2$d done").

### D.8 Edge cases

- Non-Samsung device: item 3 hidden; copy elsewhere stays generic ("Android").
- The user turns battery optimisation back on later: the next resume shows Not set again; E's
  count updates.
- `isIgnoringBatteryOptimizations("com.termux")` throws or returns false on a vendor ROM that
  restricts cross-package queries: treat as Not set and keep the action (verification is advisory).
- The checklist never blocks anything: kernel features work without it.

### D.9 Accessibility

Each item merges title + status pill + supporting text ("Termux battery, done, Unrestricted"). The
status pill text is visible (never colour-only). Checkboxes are full-width 48dp rows with the label
"I've done this" and `Role.Checkbox`. The progress line is a polite live region updated on resume.

### D.10 Acceptance checklist

- [ ] On the S25U, `adb shell dumpsys deviceidle whitelist | grep -E "com.termux|io.github.ichouchiang.clinetermux"`
      matches items 1 and 2 (Done when listed).
- [ ] Item 2 "Allow" shows the system "Let app always run in the background?" dialog; accepting
      turns the pill to Done on return.
- [ ] Item 1 action opens Termux's App info; switching Battery to Unrestricted and returning shows
      Done.
- [ ] Item 3 appears on the S25U and Tab S7+ (Samsung); "Open battery settings" lands on the
      battery page or, failing that, Settings; the checkbox persists across relaunch.
- [ ] Item 4 matches `adb shell settings get global settings_enable_monitor_phantom_procs` (unset
      today → Not set); flipping the developer toggle and returning shows Done, or the self-check
      appears if reading throws.
- [ ] Item 5 reflects the notification permission and requests it.
- [ ] Onboarding variant appears once after A only when an item is not done; "Skip for now" and
      "Continue" both lead to C and it never appears automatically again.
- [ ] Tab S7+ standalone page: 640dp column; from Settings on expanded, it renders in the detail pane.
- [ ] TalkBack reads each item with its status word.

---

## E. Kernel status sheet

### E.1 Purpose

One place that answers "is the kernel OK, what is it running, and what can I do about it": Hub
connection, versions, process, uptime, activity, wake lock, background reliability, and the
lifecycle actions (start, restart, stop, disconnect, diagnostics). Also hosts the Hub update
dialog and the diagnostics results.

### E.2 Desktop equivalent

- `agent-sidebar.tsx` logo **HoverCard** (opened by hovering the Cline logo): product name
  (`productNameForVersion` → "Cline"), "Version <x>" / "Version unavailable", a top border, then the
  8px status dot (emerald connected / muted otherwise) with "Cline Hub @<port>" (`hubPort()`,
  "unknown" fallback) and, when disconnected, the error or "Cline Hub is not connected." in
  destructive text. Copied as the sheet's header block, verbatim. DS §4.1 #13 already maps this
  hover card to "the Kernel status sheet, opened from the kernel chip".
- `about-view.tsx` **Updates** row (title + one-sentence description + one trailing action that
  becomes a spinner while busy): copied for the "Restart to use <release>" row.
- `hub-update-required-dialog.tsx`: E.9 copies its `unsupported_protocol` dialog; E.7's
  Restart confirm borrows the `outdated_hub` dialog's "still serving … interrupts its sessions"
  sentences (0.1 names both).
- `export-diagnostics-dialog.tsx`: the privacy sentence pattern for E.6 ("…are never included").

### E.3 Navigation

| | Behaviour |
| --- | --- |
| Lives at | Shell-level `ModalBottomSheet` (DS §5.5), state `kernelSheetVisible` in `AppShellViewModel`; not a route |
| Entered from | KernelChip in the drawer header (compact), the rail's bottom KernelChip item (medium/expanded), the KernelBanner "Kernel status" / "Details" action (F), the KernelStatePanel "Kernel status" text button (F, opens scrolled to E.6 for the start-failed variants), a `kernel://status` deep link from the foreground-service notification (notifications spec). Never from A or the repair variants (they render `DiagnosticsBlock` inline, 0.2) |
| Left by | Swipe down, scrim tap, back (DS §5.8 item 2, predictive collapse); after "Stop kernel" completes the sheet stays open showing the stopped state |
| Back | collapses the sheet; inside it, an open confirm dialog closes first |

### E.4 Layout

Compact (sheet, `skipPartiallyExpanded = true`, max height 90 %):

```
┌────────────────────────────────────────┐
│                 ───                    │ DragHandle
│ [mark] Cline Termux                    │ titleMedium (desktop text-sm font-medium)
│       Version 0.4.0                    │ bodySmall onSurfaceVariant
│ ────────────────────────────────────── │ outlineVariant divider
│ ● Cline Hub @25463                     │ 8dp dot success + labelLarge SemiBold
│                                        │ (when down: second line in error, bodySmall)
│ Status        Connected                │ details rows: label bodyMedium onSurfaceVariant /
│ Kernel        3.0.68-termux.2          │   value ClineMono.small onSurface, 40dp visible,
│ Core          0.0.90                   │   long-press copies the value
│ Build         source-0.0.90            │
│ Process       pid 23817                │
│ Uptime        2h 14m                   │
│ Active runs   1 running · 0 queued     │
│ ────────────────────────────────────── │
│ Keep Termux awake               [ ● ]  │ ListItem + Switch
│ Holds Termux's wake lock so Android    │
│ does not stop the Cline Hub while      │
│ Cline is connected.                    │
│ Background reliability          3 of 5 │ ListItem, trailing chevron → D
│ Kernel settings                     ›  │ ListItem → Settings › Kernel
│ ────────────────────────────────────── │
│ ┌────────────────────────────────────┐ │
│ │ ⟲      Restart kernel              │ │ primary Button, full width on compact
│ └────────────────────────────────────┘ │
│ ⌨  Open Termux                         │ action ListItems (56dp, leading 24dp icon,
│ ⚕  Run diagnostics                     │   headline bodyLarge, no trailing)
│ ⛓  Disconnect                          │   link_off
│ ■  Stop kernel                         │   stop_circle, headline + icon in error, last
│ ────────────────────────────────────── │
│ DIAGNOSTICS                            │ DiagnosticsBlock (0.7), after Run diagnostics
│ Hub healthy         Yes (pid 23817)    │
│ Hub listeners       23817              │
│ Stale hub daemons   None               │
│ Hub startup locks   None               │
│ CLI processes       None               │
│ [ Fix processes ]                      │ TextButton(error)
│ Prompts, conversation contents, API    │ bodySmall onSurfaceVariant
│ keys and the Hub token are never shown │
│ here.                                  │
└────────────────────────────────────────┘
```

Action block rule: exactly one primary `Button` per state (E.5 "Primary"), full width on
compact and end-aligned at its intrinsic width on medium+; every other action is an action
`ListItem` (56dp, leading 24dp icon `onSurfaceVariant`, headline `bodyLarge`, `Modifier.clickable
(role = Role.Button)`), in this order: Open Termux (`terminal`), Run diagnostics
(`troubleshoot`), Disconnect (`link_off`) / Connect (`link`), Stop kernel (`stop_circle`; icon and
headline in `error`, always last). A ListItem whose action is unavailable in the current state is
omitted, not disabled. A busy action shows a 24dp `CircularProgressIndicator` in place of its
icon and its busy label as the headline; while any action is busy the others are disabled
(38 % alpha, M3 disabled).

Down variant: the header dot is `outline`, line 1 "Cline Hub @unknown" (or the last known port),
line 2 in `error` the reason line from F.2 (e.g. "Termux asleep" → "Start kernel to continue." per
DS §1.6). The detail rows show only "Status" and "Kernel" (from the last `probeVersion`). The
primary becomes "Start kernel"; the rows are Open Termux and Run diagnostics.

Tablet: sheet max width 640dp centred (M3 default); same single column of rows.

### E.5 States and copy

| State (`KernelState`) | Header line 1 / line 2 | Status value | Primary `Button` | Action rows (E.4 order) |
| --- | --- | --- | --- | --- |
| `Ready` | "Cline Hub @%1$s" / — | "Connected" (**A**) | Restart kernel | Open Termux, Run diagnostics, Disconnect, Stop kernel |
| `Connecting` / `Starting` | "Cline Hub @%1$s" / — | "Connecting..." (**D**, DS §1.6) with the `StartStep` label under it | none (16dp spinner + step label in its place) | Open Termux |
| `Restarting(phase)` | "Cline Hub @%1$s" / — | "Restarting…" (**D**) with the phase label under it (`kernel_restart_phase_*`) | none (spinner) | none |
| `Reconnecting` | "Cline Hub @%1$s" / — | "Reconnecting..." (**D**) | none | Open Termux, Disconnect |
| `Draining` | "Cline Hub @%1$s" / — | "Draining" (**A**) + note "The Cline Hub is finishing current work before it restarts." (**A**) | none until `hub.drain_changed {draining:false}` or the Hub restarts | Open Termux |
| `HubDown(Asleep)` | "Cline Hub @%1$s" / "Termux asleep" … see F.2 | "Termux asleep" (**D**-system, DS §1.6) | Start kernel | Open Termux, Run diagnostics |
| `HubDown(Stopped)` | "Cline Hub @unknown" / "Cline Hub is not connected." (**D**) | "Kernel stopped" (**A**) | Start kernel | Open Termux |
| `HubDown(StoppedInTermux)` | "Cline Hub @unknown" / "Cline Hub is not connected." (**D**) | "Stopped in Termux" (**A**) | Start kernel | Open Termux |
| `HubDown(Disconnected)` | "Cline Hub @%1$s" / "Cline Hub is not connected." | "Disconnected" (**A**) | Connect (**A**) | Open Termux, Stop kernel |
| `HubDown(StartFailed / StartTimedOut / Unreachable / CrashLoop)` | "Cline Hub @unknown" / the error text, else "Cline Hub is not connected." | "Not connected" (**A**) | Start kernel ("Try again" wording after a failure) | Open Termux, Run diagnostics (auto-run for StartTimedOut) |
| `ProtocolUnsupported` | "Cline Hub @%1$s" / "This Cline Hub speaks a protocol this app doesn't support." (**A**) | "Update required" (**A**) | Restart kernel (opens E.9) | Open Termux, Run diagnostics |
| `AuthFailed` | "Cline Hub @%1$s" / "The Cline Hub rejected this app's credentials." (**A**) | "Not connected" | Try again | Open Termux, Run diagnostics; Restart kernel as a row (`restart_alt`) |
| prerequisites broken (`PermissionMissing` etc.) | "Cline Hub @unknown" / the repair title | "Setup needed" (**A**) | "Fix the Cline kernel" (→ pushed `kernel/repair?…&pushed=true`; the sheet closes first) | Open Termux |

Header product line: "Cline Termux" (**A**, the app's name; the desktop shows its product name) and "Version %1$s" (**D**) with the app's `versionName`, or
"Version unavailable" (**D**).

Detail rows (labels **A**, values data): "Status"; "Kernel" = `release` from VERSION (e.g.
"3.0.68-termux.2"); "Core" = `/health.coreVersion`; "Build" = `/health.buildId`; "Process" = "pid
%1$d" from `/version.pid`; "Uptime" = `formatUptime(now − /version.startedAt)`, a 1:1 Kotlin port of
`sdk/packages/shared/src/parse/time.ts` ("9s", "4m 12s", "2h 14m", "1d 2h"; the same formatter
`cline hub status` and `doctor --json` use, so this row and Diagnostics' "Hub uptime" always agree;
`formatWorkDuration` stays reserved for transcript work rows);
"Protocol" = `/health.protocolVersion` ("v1"), shown only in the expanded "More details" disclosure
together with "Hub ID" (`hubId` from the last `production.json` read, H4; `/version` has none) and
"Event log" ("sequence %1$d" = `hub.status.eventLog.lastSequence`);
"Active runs" = "%1$d running · %2$d queued" from `hub.status.activeRpcTurns` / `pendingRuns`.
Missing values omit their row (desktop session overview `filter(Boolean)`).

Update row (only when `VERSION.release != kernel.hubLifetimeRelease`): title "Restart to use %1$s"
(**A**), description "Cline kernel %1$s is installed, but the running Cline Hub started before the
update." (**A**), trailing `TextButton` "Restart" → the restart flow (E.7).

Wake-lock row: "Keep Termux awake" / "Holds Termux's wake lock so Android does not stop the Cline
Hub while Cline is connected." (**A**). Switch value = `kernel.keepAwake`; if the last
`termux-wake-lock` call failed, supporting text switches to "Couldn't take Termux's wake lock: %1$s"
(**A**, `error`).

Reliability row: "Background reliability" (**A**) with trailing "%1$d of %2$d done" (`labelMedium`)
and a `warning.dot` before it when any Required item is not done.

Buttons and their busy labels: "Start kernel" → "Starting…" (**A**); "Restart kernel" (**A**) →
"Restarting…" (**D**); "Stop kernel" (**A**) → "Stopping…" (**A**); "Disconnect" (**A**); "Connect"
(**A**); "Open Termux" (**A**); "Run diagnostics" (**A**) → "Running diagnostics…" (**A**); "Fix
processes" (**A**) → "Fixing…" (**A**).

Snackbars (DS §4.1 #17): "Cline kernel stopped" (**A**), "Cline Hub restarted" (**A**),
"Disconnected from the Cline Hub" (**A**), "Copied %1$s" (**A**, long-press copy of a value, label
in the placeholder).

### E.6 Diagnostics

"Run diagnostics" issues `doctor` (`cline doctor --json`) through the `KernelDiagnostics` use
case and renders a `DiagnosticsBlock` (0.7; the same component A row 5 and the repair variant
use) with `DoctorStatus` rows: "Hub healthy" = "Yes (pid %1$d)" / "No" (**A**); "Hub listeners" =
`listeningPids` joined or "None"; "Stale hub daemons" = `staleHubPids` or "None"; "Hub startup
locks" = count or "None"; "CLI processes" = `staleCliPids` or "None"; "CLI version" = `cliVersion`;
"Hub uptime" = `hubUptime` (the CLI already formats it with `formatUptime`, the formatter E.5's
"Uptime" row ports, so the two rows show the same text for the same Hub). Privacy line (**A**,
pattern from the desktop export dialog): "Prompts, conversation contents, API keys and the Hub
token are never shown here." The full report is not exported from here (About → "Export
diagnostics" belongs to the About spec).

"Fix processes" opens an `AlertDialog`: title "Stop all Cline processes?" (**A**), text "This runs
cline doctor fix in Termux. It stops the Cline Hub and every other Cline process there, including
open TUI sessions." (**A**), confirm `TextButton(error)` "Fix processes", dismiss "Cancel" (**D**).
Confirm runs `doctorFix`, shows "Fixing…", then re-runs `doctor` and, if `hubHealthy == false`,
the state becomes `HubDown(StoppedInTermux)` (doctor fix removes the discovery record) with
"Start kernel".

### E.7 Interactions

**Session-activity pre-check** (`HubSessionActivity`, used by Restart and Stop). Over the live
socket, `session.list {limit:500}` (feature matrix "Session list"), then a 1:1 Kotlin port of
`summarizeHubSessionActivity` (`sdk/packages/core/src/hub/client/index.ts` lines 1188–1219) that
first removes participants whose `clientId` equals the app's own `clientId`:

```kotlin
data class HubSessionActivity(val activeSessionCount: Int, val participantClientCount: Int)
fun summarizeOtherClients(sessions: List<SessionRecord>, ownClientId: String): HubSessionActivity
// a session counts when at least one participant other than ownClientId remains (status is
// ignored, as upstream: participants are live socket subscriptions and cannot go stale);
// participantClientCount = distinct remaining clientIds
```

The app's own work is checked separately: `ownBusy = hub.status.activeRpcTurns +
hub.status.pendingRuns > 0 || any session the app shows as Running or Pending`. The description
string is `describeOutdatedHubSessions(others)` ported 1:1 ("1 active session from 2 connected
Cline clients", "2 active sessions", fallback "active sessions from other Cline clients").

- **Start kernel:** `kernel.userStopped = false`; `coordinator.ensureConnected(startIfDown = true)`.
- **Restart kernel** (primary in `Ready`, the "Restart" trailing action of the update row, the
  `ProtocolUnsupported` primary via E.9, a row in `AuthFailed`). The coordinator enters
  `Restarting(phase)` for the whole flow (0.3): no Reconnecting/Asleep/CrashLoop transitions, no
  KernelBanner, every other action in the sheet disabled, the primary shows "Restarting…" and the
  status value the phase label.
  1. `CheckingSessions` (only when connected): the pre-check above.
  2. If `others.activeSessionCount > 0` **or** `ownBusy`: confirm `AlertDialog`, title "Restart the
     Cline kernel?" (**A**). Text when others hold sessions: "The Cline Hub is still serving %1$s.
     Restart kernel stops that Hub and interrupts its sessions." (**A**, adapted from the desktop
     `outdated_hub` dialog's "…the running one is still serving {describeOutdatedHubSessions}." and
     "Update Now stops that Hub and interrupts its sessions."); text when only the app's own task
     runs: "A task is still running. Restarting the Cline kernel interrupts it." (**A**). Confirm
     `TextButton(error)` "Restart kernel", dismiss "Cancel" (**D**); Cancel leaves `Restarting` and
     changes nothing. Nothing is asked when both are zero.
  3. `Disconnecting`: `coordinator.disconnect()` and wait for OkHttp's `onClosed` / `onFailure`
     (max 3 s, then `cancel()`). The Hub's close handler sends `client.unregister` for the app,
     which removes it from every session's participants (`detachClientFromSessions`), so the app's
     own sessions no longer keep the Hub "busy". A session left without participants loses its
     live Hub state; after the restart the chat spec resumes it through `session.create
     {sessionConfig.sessionId, initialMessages}` (kernel contract step 7).
  4. `Replacing`:
     - no other client holds a session → RUN_COMMAND `hubUpgrade` (`cline hub upgrade --wait 30`,
       75 s, 0.5);
     - other clients hold sessions and the user confirmed → the forced path, because `hub upgrade`
       has no force option and would refuse after its wait: RUN_COMMAND `hubStop`, then `hubStart`
       with the H2 start budget (phase `Starting`).
  5. `Reconnecting`: H1 → H4 (new token) → H5 with the same `clientId` (the old socket is already
     closed, fixture 04 rule).
  6. Outcomes:

     | Result | What the app does | Shown |
     | --- | --- | --- |
     | exit 0 and H5 `Ready` | update `kernel.hubLifetimeRelease` | snackbar "Cline Hub restarted" |
     | non-zero, stderr contains `waiting for detached hub startup` | the old Hub is gone and the new one is still booting: poll `/health` 1 s within the remaining budget (H2), then reconnect | same as exit 0 on success; `HubDown(StartFailed)` when the budget runs out |
     | non-zero, stderr "Hub is still serving sessions after the wait window; not replacing it. …" (a session appeared during the wait, or the AuthFailed path could not check first) | the CLI un-drained the old Hub; reconnect to it (H1–H5) | inline `bodySmall` `error` under the primary: the stderr verbatim (data) + "Finish the sessions in the other Cline app, then try again." (**A**) |
     | any other non-zero | probe `/health`; reconnect if it answers | inline error: the stderr's last non-blank line, or "Restarting the Cline Hub failed. Try again, or run 'cline doctor fix' in a terminal." (**A**, adapted from the desktop hint) |
     | RUN_COMMAND timeout (75 s) | re-probe `/health` + `/version` before declaring anything: a `(pid, startedAt)` different from the one cached before step 3 means the upgrade succeeded → H4/H5 as for exit 0; the same pair → the old Hub is still running → reconnect and show the generic inline error; refused → keep polling `/health` 1 s for 30 s more (the new daemon may still be booting), then `HubDown(StartFailed)` | as the matching row |

- **Stop kernel:** run the pre-check (when connected). If `others.activeSessionCount > 0` or
  `ownBusy`, open the confirm `AlertDialog`: title "Stop the Cline kernel?" (**A**); text when
  others hold sessions "The Cline Hub is still serving %1$s. Stopping it interrupts them." (**A**,
  `%1$s` = `describeOutdatedHubSessions(others)`), otherwise "A task is still running. Stopping the
  Cline kernel interrupts it." (**A**); confirm `TextButton(error)` "Stop kernel", dismiss
  "Cancel". Otherwise stop at once. Stop = `coordinator.stopKernel()` (existing: disconnect,
  `cline hub stop`, `termux-wake-unlock`, clear token) + `kernel.userStopped = true` +
  `kernel.wakeLockRequested = false` + snackbar "Cline kernel stopped".
- **Disconnect:** `coordinator.disconnect()` (Hub keeps running), `wakeUnlock` if
  `kernel.keepAwake` (the lock exists for the connection), state `HubDown(Disconnected)`, snackbar.
  **Connect** undoes it.
- **Keep Termux awake switch:** on → `wakeLock`; off → `wakeUnlock`; persist; optimistic with
  rollback and the error supporting text on failure. Disabled while the kernel is not `Ready`.
- **Open Termux:** launch intent for `com.termux`.
- **Run diagnostics:** E.6; the sheet scrolls so the block's overline is at the top.
- **Long-press a value row:** copies the value, haptic `LongPress`, snackbar "Copied <label>".
- **Background reliability / Kernel settings:** close the sheet, then navigate (D / Settings › Kernel).
- **Refresh:** while the sheet is visible, every 5 s: `GET /health`, `GET /version` and, if
  connected, `hub.status {}`. Stops when hidden. No pull-to-refresh. In the down states the same
  `/health` result feeds the coordinator, so a Hub that comes back (started from the TUI, or a
  slow boot finishing) switches the sheet to `Connecting` → `Ready` in place without a tap; the
  shell-wide 10 s passive probe (0.4) does the same when the sheet is closed.

### E.8 Data bindings and ViewModel

Feature-matrix rows: "Kernel liveness and version probe" (`/health`, `/version`), "Hub health
indicator and transport states" (`/health` poll + WebSocket state; `hub.status {}` →
`{hubId, draining, activeRpcTurns, pendingRuns, eventLog{lastSequence}, idle}`; event
`hub.drain_changed`), "Session list" (`session.list {limit:500}` for the E.7 pre-check, read only
for `participants`), "Start/stop/upgrade the Hub daemon…" (RUN_COMMAND `cline hub
start|stop|upgrade`, `termux-wake-lock` / `termux-wake-unlock`; `cline hub status` is not used,
0.5), "Kernel settings: wake lock, keep Hub running, stop kernel, hub upgrade" (`/version buildId
vs installed`), "Diagnostics export" (`cline doctor --json`), "About / What's new / release notes"
(`/version` coreVersion, buildId).

```kotlin
data class KernelStatusUiState(
    val appVersion: String?,                 // "Version %s" / "Version unavailable"
    val dot: KernelDot,                      // Success | WarningPulsing | Outline (DS §1.6)
    val hubLine: String,                     // "Cline Hub @25463" / "Cline Hub @unknown"
    val errorLine: String?,                  // second line in error colour
    val statusLabel: String,
    val rows: List<DetailRow>,               // label/value, value null → row omitted
    val moreRows: List<DetailRow>,           // Protocol, Hub ID, Event log
    val updateRow: UpdateRowUi?,             // release mismatch
    val keepAwake: Boolean, val keepAwakeEnabled: Boolean, val wakeLockError: String?,
    val reliability: Pair<Int, Int>, val reliabilityWarning: Boolean,
    val primary: KernelAction?,              // Start | Restart | Connect | TryAgain | FixKernel (E.5)
    val rowActions: List<KernelAction>,      // ordered E.4: OpenTermux, RunDiagnostics, Disconnect|Connect, Restart (AuthFailed), Stop
    val busy: KernelAction?,                 // which action shows its busy label
    val restartPhase: RestartPhase?,         // while KernelState.Restarting
    val actionError: String?,
    val diagnostics: DiagnosticsUi?,         // null until run; Loading | Loaded(rows) | Error(msg)
    val confirm: ConfirmUi?,                 // StopKernel(text) | RestartKernel(text) | FixProcesses
    val showUpdateDialog: Boolean,           // E.9
)
```

`KernelStatusViewModel(coordinator, termux, hubClient, prefs, reliability: ReliabilityChecks,
diagnostics: KernelDiagnostics)` is scoped to the shell (so the KernelChip and the sheet share
it). `KernelDiagnostics` is an app-scoped use case so A and the repair variant can call it
without the shell.

### E.9 Hub mismatch dialog (`ProtocolUnsupported`)

Shown by the shell (over any screen) when the state becomes `ProtocolUnsupported`, and from E for
that state. Copied from the desktop's **`unsupported_protocol`** dialog ("Cline Hub was updated",
dismissible with "Later"), adapted because the app cannot update itself.

The direction comes from the H1 check: `minClientProtocolVersion > v1` means the Hub is newer
than the app (the only direction possible today: the app speaks v1 and v1 is the oldest
protocol); `maxClientProtocolVersion < v1` means it is older.

- `AlertDialog`, not dismissible by scrim; back = "Later".
- **Hub newer** (desktop `unsupported_protocol` frame):
  - Title: "Cline Hub was updated" (**D**).
  - Text: "Another Cline installation updated the shared Cline Hub (core %1$s), and it no longer
    matches this app." (**D**; without a `coreVersion` the parenthetical is dropped, as the
    desktop does, `hub_updated_body_no_core`) + "Update the Cline Termux app to keep using this Hub.
    If you recently went back to an older Cline kernel in Termux, Restart kernel starts the Hub
    that is installed there." (**A**, replaces the desktop's "Update and restart Cline to stay in
    sync with the running Hub.", which assumes a self-updater the app does not have).
- **Hub older** (desktop `outdated_hub` title, made dismissible):
  - Title: "Cline Hub update required" (**D**).
  - Text: "This app needs a newer Cline Hub than the one running. Restart kernel starts the Hub
    that is installed in Termux. If that doesn't help, update the Cline kernel by running the
    installer again in Termux:" (**A**) + CommandBlock with the installer command (A.5 row 4).
- Confirm (both): `TextButton` "Restart kernel" (**A**; the desktop's "Update and restart" /
  "Update Now" assume an app updater) → the E.7 restart flow from step 4 (the app is not
  connected, so there is no pre-check and no disconnect; `hub upgrade` itself refuses when
  another client is busy, which then shows as the "still serving" row of E.7's outcome table).
  Busy label "Restarting…" (**D**). If the restarted Hub is still unsupported, the text gains
  "The Cline Hub installed in Termux still doesn't match this app." (**A**) and the confirm
  label becomes "Try again" (**D**). Restart failures show E.7's inline texts inside the dialog.
- Dismiss: "Later" (**D**, desktop `unsupported_protocol`; for the "Hub older" variant this is an
  **A** choice, because the desktop's alternative "Quit Cline" has no Android equivalent: the app
  stays usable read-only). As the desktop's `isPersistableHubMismatchKey` does for
  `unsupported_protocol`, a dismissal is never persisted: the dialog returns on the next
  reconnect, the next `ON_START`, or the next launch. While dismissed, F shows the "Couldn't
  connect to the Cline Hub" variant (F.2).

### E.10 Edge cases

| Case | Behaviour |
| --- | --- |
| `hub_draining` reply on any command (recovery matrix: wait for `hub.drain_changed`) | state `Draining`; the sheet shows the note; commands are retried by their owners after `hub.drain_changed {draining:false}` |
| Daemon restart detected (`/version` `(pid, startedAt)` changed, outside `Restarting`) | token re-read (H4); `kernel.hubLifetimeRelease` reset to the current VERSION release; uptime restarts |
| `hub stop` returns `{"stopped":false}` | inline error "The Cline Hub did not stop. Try again, or use Fix processes." (**A**); state re-probed |
| `/health` answers but WebSocket refuses with 401 twice | `AuthFailed` row texts |
| `termux-wake-lock` fails (termux-tools missing) | wake-lock error line; everything else works |
| Two Hubs (stale daemon on 25463 plus another) | diagnostics rows list them; "Fix processes" is the remedy |
| Sheet open while the kernel goes down | header and rows switch to the down variant in place; the action area switches; focus stays on the sheet title |
| Restart while the TUI has an open session | the pre-check shows "Restart the Cline kernel?" with "1 active session from 1 connected Cline client" before anything is drained; Cancel changes nothing |
| `session.list` fails during the pre-check | treat as "unknown": show the confirm with the fallback text "The Cline Hub is still serving active sessions from other Cline clients. Restart kernel stops that Hub and interrupts its sessions." (the ported `describeOutdatedHubSessions` fallback) and use the forced path on confirm |

### E.11 Accessibility

- Sheet pane title for TalkBack: "Kernel status" (**A**); focus moves to "Cline Termux" on open and back to
  the KernelChip on close (DS §8.1 item 7).
- The header dot has no separate focus; the hub line reads "Cline Hub at port 25463, connected"
  (**A** CD template "Cline Hub at port %1$s, %2$s").
- Detail rows merge label and value ("Process, pid 23817"); custom action "Copy" on each.
- Busy actions announce their busy label politely; inline errors are assertive.
- Focus order: header → detail rows → "More details" → update row → wake-lock switch →
  reliability row → Kernel settings → the primary `Button` → action rows in E.4 order (Open
  Termux, Run diagnostics, Disconnect/Connect, Stop kernel) → Diagnostics block. Each action row
  is one focus stop with `Role.Button` and its headline as the label; icons are decorative.
- Destructive actions (Stop kernel, Fix processes) are never first in focus order and never
  default (DS §3.4); Stop kernel is always the last action row.

### E.12 Acceptance checklist

- [ ] With the real Hub up, tapping the drawer KernelChip opens the sheet: "Cline Termux", "Version <app>",
      green dot, "Cline Hub @25463", rows Status/Kernel/Core/Build/Process/Uptime/Active runs with
      values matching `curl -s 127.0.0.1:25463/health` and `/version` run via `ssh <phone>`.
- [ ] Uptime and Active runs update within 5 s while the sheet is open (start a turn from the app).
- [ ] Long-press "Build" copies the build id; the snackbar names it.
- [ ] "Keep Termux awake" off → `adb shell dumpsys power | grep termux:service-wakelock` shows
      nothing; on → present again.
- [ ] "Stop kernel" with a running turn in the app shows "Stop the Cline kernel?" with "A task is
      still running…"; with a TUI session open in Termux it shows "…still serving 1 active session
      from 1 connected Cline client…"; confirming stops the Hub (`ssh <phone> cline hub status` →
      `running:false`), the sheet shows the stopped variant, the app does not auto-start on the
      next launch.
- [ ] "Start kernel" from the stopped variant brings the Hub back within 15 s.
- [ ] "Restart kernel" while idle **with a chat open in the app** (the app is a `creator`
      participant of that session) → no confirm, status "Restarting…" with phase labels, no
      KernelBanner and no "Termux asleep" at any point, snackbar "Cline Hub restarted" in under
      40 s, Process pid changes; the chat resumes on the next send.
- [ ] "Restart kernel" with a TUI session open (owner OK) → the confirm appears **before** any
      drain; Cancel changes nothing (`cline hub status` pid unchanged); confirm stops and starts
      the Hub and the TUI session is interrupted.
- [ ] Fake `restart_slow`: the RUN_COMMAND times out at 75 s, the re-probe sees the new pid, and the
      result is "Cline Hub restarted", not an error.
- [ ] "Disconnect" leaves `cline hub status` running and shows "Disconnected"; "Connect" restores.
- [ ] "Run diagnostics" fills the Diagnostics rows; "Fix processes" asks first.
- [ ] Fake `protocol_unsupported` (Hub `minClientProtocolVersion` v2): E.9 appears over Home with
      the title "Cline Hub was updated" and the D first sentence; "Later" closes it; it returns
      after the next reconnect and after relaunch; while dismissed, Chat shows "Couldn't connect to
      the Cline Hub".
- [ ] Fake `draining`: Status "Draining" with its note; actions disabled.
- [ ] Tab S7+ (rail KernelChip): sheet 640dp centred; one primary button, then the action rows.
- [ ] S25U at font scale 2.0: every action row is a single 56dp+ row with its icon; nothing wraps
      into a second button row.
- [ ] Fake `hub_comes_back` with the sheet open in the Asleep variant: within 10 s, without a tap,
      the sheet shows Connecting → Connected.
- [ ] TalkBack reads "Kernel status", the hub line with its state, and each row with its value.
- [ ] No row ever shows the Hub token or any provider key (inspect with TalkBack and a screenshot).

---

## F. "Termux asleep / Hub down" state

### F.1 Purpose

Whenever the Hub is not usable after onboarding, every surface says so in the same words, keeps
what can still be shown, and offers the one action that fixes it. This is the Android counterpart of
the desktop's boot gate and its inline "Reconnecting chat..." line, plus the Termux-specific case of
Android stopping Termux in the background.

### F.2 Variants and copy

One table for every surface (0.10 makes the sibling specs use it). Panel icons are 48dp
`onSurfaceVariant`; the KernelBanner never shows these icons, only the DS §1.6 dot or a spinner
(0.7). "Primary" is shown on a panel or banner only where no composer is composed under it
(0.7 action rules); otherwise the composer's read-only row carries it.

| Variant (`KernelGate`) | Panel icon | Panel title | Panel body | Primary | Secondary | Banner line 1 / line 2 | Chip line 1 / dot (DS §1.6) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `Down(Asleep)` | `bedtime` | "Termux asleep" (**DS**) | "Android stopped Termux while it was in the background, and the Cline Hub stopped with it. Your sessions are saved. Start kernel to continue." (**A** + **DS** line 2) | "Start kernel" | "Why did this happen?" (→ D) | "Termux asleep" / "Start kernel to continue." (**DS**) | "Termux asleep" / `outline` |
| `Down(Stopped)` | `stop_circle` | "Kernel stopped" (**A**) | "You stopped the Cline kernel. Start it again to keep working." (**A**) | "Start kernel" | — | "Kernel stopped" / "Cline Hub is not connected." (**D**) | "Cline Hub @unknown" / `outline` |
| `Down(StoppedInTermux)` | `stop_circle` | "Kernel stopped" (**A**) | "The Cline Hub was stopped in Termux. Start it again to keep working." (**A**) | "Start kernel" | "Kernel status" (→ E) | "Kernel stopped" / "Cline Hub is not connected." | "Cline Hub @unknown" / `outline` |
| `Down(Disconnected)` | `link_off` | "Disconnected" (**A**) | "Cline is not connected to the Cline Hub. The Hub is still running in Termux." (**A**) | "Connect" | — | "Disconnected from the Cline Hub." (**A**) / — | "Cline Hub @25463" / `outline` |
| `Down(StartFailed / StartTimedOut / Unreachable / CrashLoop)` | `error` | "Couldn't start the Cline Hub" (**A**) | "Cline Hub is not connected." (**D**) + detail block (stderr tail or "Termux didn't answer within 60 seconds."); crash loop adds "The Cline Hub stopped twice right after starting, so Cline stopped restarting it." (**A**) | "Try again" | "Kernel status" (→ E, scrolled to diagnostics) | "Cline Hub @unknown" / "Cline Hub is not connected." (**DS** unavailable) | "Cline Hub @unknown" / `outline`, line 2 "Cline Hub is not connected." |
| `Down(Protocol)` | `error` | "Couldn't connect to the Cline Hub" (**A**) | "This Cline Hub speaks a protocol this app doesn't support." (**A**) | "Restart kernel" (E.9 flow; reopens the dialog) | "Kernel status" | "Couldn't connect to the Cline Hub" / "This Cline Hub speaks a protocol this app doesn't support." | "Cline Hub @%1$s" / `outline` |
| `Down(Auth)` | `error` | "Couldn't connect to the Cline Hub" (**A**) | "The Cline Hub rejected this app's credentials." (**A**) | "Try again" | "Kernel status" | "Couldn't connect to the Cline Hub" / "The Cline Hub rejected this app's credentials." | "Cline Hub @%1$s" / `outline` |
| `Busy(Starting / Connecting)` | 40dp `CircularProgressIndicator` | "Connecting..." (**D**, boot-gate wording) | the current `StartStep` label (e.g. "Starting Cline Hub…") + after 20 s "This is taking longer than usual." | — | "Kernel status" | spinner + "Connecting..." (**D**) / step label | "Connecting..." / `warning.dot` pulsing |
| `Busy(Reconnecting)` | (no panel: content stays) | — | — | — | — | spinner + "Reconnecting..." (**D**) / — | "Reconnecting..." / `warning.dot` pulsing |
| `Busy(Restarting)` | (no panel) | — | — | — | — | **no banner** (0.3: an intended restart is not an outage); the composer row shows a 16dp spinner + "Restarting…" (**D**) | "Restarting…" / `warning.dot` pulsing |
| `Busy(Draining)` | (no panel) | — | — | — | "Details" (→ E) | dot + "The Cline Hub is finishing current work before it restarts." (**A**) / — | "Cline Hub @25463" / `warning.dot` |
| `DegradedNoCommands` (permission revoked, Hub alive, 0.4) | (no panel) | — | — | — | "Fix" (→ pushed repair, row 2) | dot + "Cline can't run commands in Termux." (**A**) / "Some features are unavailable." (**A**) | "Cline Hub @25463" / `success.dot` |

### F.3 Where it appears

| Surface | Behaviour while the kernel is not `Ready` |
| --- | --- |
| **Home (welcome)** | The hero and composer stay. A `KernelBanner` sits above the composer (not the hero) with F.2's lines; its trailing action is "Kernel status" (→ E), because the composer's read-only row carries "Start kernel" / "Connect" (home-chat's "Kernel notice" is this component, 0.10). The setup notice (DS §4.2 WelcomeSetupNotice) is hidden while the kernel is down (it cannot be evaluated; the kernel comes first) |
| **Chat, transcript cached in memory** | Transcript stays visible and scrollable (read-only). `KernelBanner` at the top of the transcript column (sticky under the app bar), trailing "Kernel status". The desktop's inline transport line is rendered as the last transcript row exactly as `chat-messages.tsx` draws it: a 14dp spinner plus "Connecting chat..." / "Reconnecting chat..." / "Chat backend unavailable" (`bodySmall` `onSurfaceVariant`); the spinner is kept for "unavailable" too, because the passive probe (0.4) keeps retrying, as the desktop transport does. The line is **hidden while the last transcript row is an error message with an action** (a credential error with "Sign in to Cline" / "Open API providers", desktop `!shouldShowErrorBanner`), and while `Busy(Restarting)` |
| **Chat, nothing cached** (e.g. opened from a notification after process death) | The detail pane shows `KernelStatePanel` for the variant with its secondary action only; the composer under it carries the primary. On compact the app bar keeps the title from the deep link or "New Session" |
| **Composer** | readOnly (DS §4.1 #6): field disabled with placeholder "Cline Hub is not connected." (**D**); the settings row is replaced by an 8dp `outline` dot + F.2 chip line 1 + an end-aligned `TextButton` with F.2's Primary ("Start kernel", "Connect", "Try again", "Restart kernel"), or for Busy variants a 16dp spinner + the chip line 1 instead of a button. Draft text typed before the drop is kept and sent after reconnect only on an explicit tap. This row is the only place the primary appears on Home and Chat |
| **Drawer / list pane** | The last session list stays (in-memory cache from the last `session.list`) at **full opacity**: rows remain tappable and open the cached transcript if present. Their Hub-dependent menu items (Rename, Pin, Fork, Delete) are disabled (M3 disabled 38 %) and the row's `stateDescription` gains "unavailable" (**A**). A one-line header item above the sections is the single visual signal: dot + chip line 1 + `TextButton` with F.2's Primary (no composer in the drawer). "New session" stays enabled; it opens Home |
| **KernelChip** (drawer header / rail) | dot and line 1 from F.2; tap opens E |
| **Tool approval / ask-question sheets** | if open when the kernel drops: the sheet stays, buttons disabled, footer line "Cline Hub is not connected." (**D**); pending requests are re-issued on the next subscribe (dedupe by `approvalId`) |
| **Sessions page, Settings, Customize, Schedules, Changes, Tool detail** | `KernelBanner` at the top of the page with F.2's lines and its Primary as the trailing action (no composer there). Changes' "KernelStrip" and the Sessions notice are this component (0.10) |

### F.4 Precedence and timing

One banner at a time: Degraded-permission < Draining < Reconnecting < Starting/Connecting <
Disconnected < Stopped / StoppedInTermux < Asleep < Start failed / Protocol / Auth.
`Busy(Restarting)` shows no banner at all. The KernelBanner animates in with `expandVertically +
fadeIn` (DS §6 disclosure) and is never shown for reconnects shorter than 1.5 s (debounce, so a
brief network blip on resume does not flash it).

`Reconnecting` → after 3 failed attempts and `/health` refused → the discovery check (0.3) →
`Asleep` or `StoppedInTermux` (or `Unreachable` if the Hub never was Ready in this process). On
`ON_RESUME` with `Asleep` / `StoppedInTermux` / `Unreachable` and auto-start allowed, the app
starts the kernel automatically and the variant goes to Starting (0.4 auto-start policy). While
the app stays in the foreground it does not auto-start, but the passive `/health` probe every
10 s (0.4) reconnects as soon as a Hub answers, so a Hub started from the TUI in split screen or
one that finished a slow boot is picked up without a tap.

### F.5 Layout

Compact, chat with nothing cached:

```
┌────────────────────────────────────────┐
│ ☰  New Session                     ⋮   │ TopAppBar
│                                        │
│                                        │
│                 ☾ 48dp                 │ bedtime, onSurfaceVariant
│             Termux asleep              │ titleMedium
│   Android stopped Termux while it was  │ bodyMedium onSurfaceVariant, centred
│   in the background, and the Cline Hub │
│   stopped with it. Your sessions are   │
│   saved. Start kernel to continue.     │
│                                        │
│          Why did this happen?          │ TextButton (secondary only: the composer
│                                        │   below carries the primary)
│ ┌────────────────────────────────────┐ │
│ │ Cline Hub is not connected.        │ │ composer readOnly
│ │ ○ Termux asleep      Start kernel  │ │ dot + chip line 1 + TextButton
│ └────────────────────────────────────┘ │
└────────────────────────────────────────┘
```

Compact, chat with cached transcript:

```
┌────────────────────────────────────────┐
│ ☰  ● Fix the login form            ⋮   │
│ ┌────────────────────────────────────┐ │
│ │ ○ Termux asleep      Kernel status │ │ KernelBanner: surfaceContainerHigh,
│ │   Start kernel to continue.        │ │   1dp outlineVariant; line 2 in error
│ └────────────────────────────────────┘ │
│   … transcript (read-only) …           │
│   ⟳ Chat backend unavailable           │ desktop inline line, 14dp spinner (F.3)
│ ┌────────────────────────────────────┐ │
│ │ Cline Hub is not connected.        │ │
│ │ ○ Termux asleep      Start kernel  │ │ the only "Start kernel" on screen
│ └────────────────────────────────────┘ │
└────────────────────────────────────────┘
```

Tablet expanded: the list pane keeps its cached list at full opacity with the header item; the
detail pane shows the panel (max 480dp, centred) or the banner over the cached transcript; the
rail KernelChip shows the `outline` dot.

### F.6 Data bindings and ViewModel

Derived entirely from `KernelCoordinator.state` and the Hub client's transport state (feature
matrix "Hub health indicator and transport states": `connecting | reconnecting | connected |
unavailable` as in `desktop-client.ts`, plus "Termux asleep" when `/health` fails). `KernelGate`
is the projection every surface in every spec binds to (0.10); no screen switches on
`KernelState` directly except this group's own ViewModels.

```kotlin
sealed interface KernelGate {   // AppShellViewModel.kernelGate: StateFlow<KernelGate>
    data object Ready : KernelGate
    data class Busy(val kind: BusyKind, val stepLabel: String?, val slow: Boolean) : KernelGate   // Starting | Connecting | Reconnecting | Restarting | Draining
    data class Down(val variant: DownVariant, val detail: String?) : KernelGate                   // Asleep | Stopped | StoppedInTermux | Disconnected | StartFailed | CrashLoop | Protocol | Auth
    data object DegradedNoCommands : KernelGate
    data class Blocked(val focus: CheckId) : KernelGate       // → blocking kernel/repair or kernel/install-termux
}
```

Projection: `Unknown`, `Starting`, `Connecting` → `Busy(Starting/Connecting)`; `Reconnecting` →
`Busy(Reconnecting)`; `Restarting` → `Busy(Restarting)`; `Draining` → `Busy(Draining)`;
`HubDown(reason)` → `Down(reason)` (`StartTimedOut` and `Unreachable` → `StartFailed`);
`ProtocolUnsupported` → `Down(Protocol)`; `AuthFailed` → `Down(Auth)`; `Ready` with P2 failing →
`DegradedNoCommands`; `TermuxMissing`, `TermuxTooOld`, `PermissionMissing`,
`ExternalAppsBlocked`, `TermuxNotSetUp`, `PortMissing`, `PortTooOld` (without a usable Hub) →
`Blocked(focus)`.

On returning to `Ready` after a drop, the existing reconnect path runs (contract step 6–7): re-register
with the same `clientId` only after the old socket closed (fixture 04 rule), re-subscribe with the
persisted cursors, `session.attach` live sessions, resume non-live ones with `session.create
{sessionConfig.sessionId, initialMessages}`. Those belong to the chat spec; this state only exposes
`Ready` again and removes the banner with a polite announcement "Cline kernel connected" (**A**).

### F.7 Edge cases (recovery matrix)

| Recovery-matrix entry | What this state shows |
| --- | --- |
| ECONNREFUSED on 25463 → `cline hub start` | Asleep/Unreachable panel; auto-start on resume, "Start kernel" while foreground, passive probe every 10 s |
| `session_not_found` on send → resume via `session.create` | not visible here (chat spec); the composer is enabled again once `Ready` |
| `run.interrupted {reason:"hub_restart"}` | chat spec marks the run interrupted and offers resend; this state only covers the outage itself |
| approval pending when the app was killed → re-issued on next subscribe | approval sheet reappears after `Ready` (approval spec); while down, the drawer row keeps the "Approval required" dot from the cache |
| `capability.requested` (ask_question) cancelled when the app unregistered | after `Ready`, a snackbar "The question was cancelled while Cline was disconnected." is the ask-question spec's; this state does not show it |
| `hub_draining` → wait for `hub.drain_changed` | Draining banner |
| RUN_COMMAND never returns → 60 s, then `cline doctor --json` | Start-failed panel with "Termux didn't answer within 60 seconds." and "Kernel status" opening E's diagnostics |
| Upgrade 401 | `AuthFailed` texts |

Also: the Hub is killed by the phantom-process killer while a turn runs (Asleep variant; the "Why
did this happen?" link leads to D item 4); the device reboots (Termux not running at all; on
launch the auto-start brings it back, the user sees Starting); the user stops the Hub from the
TUI (`cline hub stop`): the socket drops without a close frame (the Hub terminates its sockets,
0.1), the reconnects fail, `/health` is refused, and the discovery check finds `production.json`
removed (`hub stop` deletes it before returning) → `StoppedInTermux` ("The Cline Hub was stopped
in Termux."), not Asleep, and `kernel.userStopped` stays false (auto-start on the next resume
stays allowed). An OS kill leaves the file behind → Asleep. If the RUN_COMMAND for the check
itself times out, Termux is the thing that is asleep → Asleep.

### F.8 Accessibility

- KernelStatePanel title is `heading()`; the panel is a polite live region and announces its title
  once when the variant changes.
- The banner merges its two lines into one node and keeps the trailing action as a separate
  button; TalkBack reads "Termux asleep, Start kernel to continue." then "Kernel status, button".
- Drawer rows keep full contrast and their labels; their `stateDescription` gains "unavailable"
  (**A**) and the disabled menu items are announced as disabled.
- The composer's disabled field still reads its placeholder; its row's button reads "Start
  kernel, button".

### F.9 Acceptance checklist

- [ ] Fake `asleep_after_ready`: after 10 s the chat shows the banner "Termux asleep" / "Start
      kernel to continue." with "Kernel status", the inline "Chat backend unavailable" with its
      spinner, the readOnly composer with "Start kernel" (the only "Start kernel" on screen), the
      drawer header item and the KernelChip `outline` dot with "Termux asleep"; all within 2 s of
      the drop (after the 3 reconnect attempts).
- [ ] Same fake, chat with nothing cached: the panel shows "Why did this happen?" and no button of
      its own; the composer row has "Start kernel".
- [ ] "Start kernel" from the composer goes Starting ("Connecting..." pulsing chip) → Ready; the
      banner leaves with "Cline kernel connected" announced.
- [ ] Real (owner OK): `ssh <phone> cline hub stop` while the app is foreground shows the Kernel
      stopped variant with "The Cline Hub was stopped in Termux." (discovery file absent), never
      "Termux asleep"; the next resume auto-starts. Fake `stopped_in_termux` reproduces it.
- [ ] Real (owner OK): with the app in the background, `adb shell am force-stop com.termux`, then
      resume the app: the app auto-starts the kernel (Starting → Ready) without showing Asleep.
- [ ] Fake `hub_comes_back`: with the app foreground in Asleep, the Hub answering after 30 s is
      picked up within 10 s with no tap and no `hub start` issued (debug RUN_COMMAND trace empty).
- [ ] Fake `hub_crash_loop`: after the second death the panel shows the crash-loop sentence and no
      further auto-start or passive reconnect happens until "Try again".
- [ ] Network blip shorter than 1.5 s (toggle airplane mode on and off quickly; loopback is
      unaffected, so use the fake `asleep_after_ready` with a 1 s outage variant) shows no banner.
- [ ] Opening a session from a notification after process death with the Hub down shows the panel,
      not an empty transcript.
- [ ] Permission revoked while the Hub is alive: the degraded banner appears; the workspace picker
      and sign-in open the **pushed** repair screen, and back returns to the chat.
- [ ] A credential error row with "Sign in to Cline" as the last transcript row hides the inline
      transport line.
- [ ] Drawer rows stay at full opacity while down; their Rename/Pin/Fork/Delete items are disabled.
- [ ] Tab S7+ expanded: cached list pane plus panel in the detail pane; rail chip dot `outline`.
- [ ] TalkBack announces each variant change once.

---

## 8. Recovery matrix coverage (`docs/02-kernel-contract.md`)

| Entry | Detected by | Shown in |
| --- | --- | --- |
| upgrade 401 → stale token → re-read production.json | H5 | silent; second 401 → A row 5, E, F (`AuthFailed`) |
| ECONNREFUSED on 25463 → `cline hub start` | H1, then the discovery check (0.3) | A row 5 (first run), F Asleep / StoppedInTermux / Unreachable + auto-start on resume + passive probe |
| RUN_COMMAND err with allow-external-apps / permission errmsg → Kernel setup with the exact fix | P3 / any RUN_COMMAND | A row 3 / row 2, repair variant |
| Termux not resolvable via `<queries>` → Install Termux screen | P1 | B |
| reply error `hub_draining` → wait for `hub.drain_changed` | Hub client | E Draining, F banner |
| `session_not_found` on send | chat | chat spec (composer re-enabled by F) |
| `run.interrupted {reason:"hub_restart"}` | chat | chat spec |
| approval with no UI able to answer → re-issued on subscribe | Hub client | approval spec; F keeps cached dots |
| ask_question cancelled after reconnect | Hub client | ask-question spec |
| RUN_COMMAND never returns → 60 s timeout then `cline doctor --json` | H2 | A row 5 timed out, E.6, F start-failed |

---

## 9. Strings (`res/values/strings.xml`)

**D** = desktop verbatim (character for character, including "..." vs "…"), **DS** = already defined
in the design system, **A** = new Android string (uses "…").

| Key | Text | Src |
| --- | --- | --- |
| `onboarding_welcome_title` | Cline Termux | A (the app's name) |
| `onboarding_welcome_tagline` | An unofficial app for the Cline CLI in Termux. | A (the unofficial notice, in place of the desktop tagline) |
| `onboarding_welcome_notice` | Not affiliated with or endorsed by Cline Bot Inc. or the Termux project. | A |
| `onboarding_welcome_body` | Cline is an AI coding agent. It reads your code, edits files, runs commands, and works through tasks with you — in any project on this device. | D, adapted ("on this device") |
| `onboarding_welcome_cta` | Get started | D |
| `onboarding_welcome_footer` | Takes less than a minute. Everything can be changed later in Settings. | D |
| `onboarding_welcome_footer_no_termux` | Takes a few minutes if Termux still needs installing. Everything can be changed later in Settings. | A |
| `common_back` | Back | D |
| `kernel_setup_title` | Set up the Cline kernel | A |
| `kernel_setup_body` | Cline runs its agent inside Termux on this device, and this app connects to it. Each step is checked for you. | A |
| `kernel_setup_repair_title` | Fix the Cline kernel | A |
| `kernel_setup_repair_body` | Something Cline needs in Termux changed. Fix the step below to keep working. | A |
| `kernel_setup_progress` | %1$d of %2$d ready | A |
| `kernel_setup_ok_summary` | %1$d steps OK | A |
| `setup_checking` | Checking… | A |
| `setup_waiting_above` | Waiting for the steps above. | A |
| `setup_status_waiting` / `_checking` / `_passed` / `_needs_action` / `_failed` | waiting / checking / passed / needs action / failed | A |
| `setup_check_again` | Check again | A |
| `setup_open_termux` | Open Termux | A |
| `setup_copy_command` / `setup_copied_command` | Copy command / Copied command | A |
| `setup_command_copied` | Command copied | A |
| `check_termux_title` | Termux | A |
| `check_termux_passed` | Termux %1$s is installed | A |
| `check_termux_missing` | Termux is not installed | A |
| `check_termux_disabled` | Termux is turned off | A |
| `check_termux_too_old` | Termux %1$s is too old. Cline needs Termux 0.109 or later. | A |
| `check_termux_install` | Install Termux | A |
| `check_termux_open_settings` | Open Termux settings | A |
| `check_permission_title` | Permission to run commands | A |
| `check_permission_passed` | Allowed | A |
| `check_permission_body` | Cline needs this to start the kernel and read its status. | A |
| `check_permission_allow` | Allow | A |
| `check_permission_denied` | Permission denied. Turn on “Run commands in Termux environment” in Cline Termux's app permissions. | A |
| `check_permission_path` | Permissions → Additional permissions → Run commands in Termux environment | A |
| `check_permission_open_settings` | Open app settings | A |
| `check_external_title` | Termux accepts commands from Cline | A |
| `check_external_passed` | allow-external-apps is on | A |
| `check_external_body` | Termux blocks commands from other apps until you allow it. Run this once in Termux: | A |
| `check_kernel_title` | Cline kernel | A |
| `check_kernel_passed` | Cline kernel %1$s | A |
| `check_kernel_missing` | The Cline kernel is not installed in Termux. Run the installer in Termux: | A |
| `check_kernel_too_old` | Cline kernel %1$s can't start the Cline Hub. Update it to 3.0.68-termux.2 or later by running the installer again: | A |
| `check_kernel_not_set_up` | Termux hasn't finished its first-run setup. Open Termux, wait for the prompt, then check again. | A |
| `check_hub_title` | Cline Hub | A |
| `check_hub_idle` | Starts the Cline Hub in Termux. It keeps running in the background while Cline is connected and uses about 300 MB of memory. | A |
| `check_hub_starting` | Starting the Cline kernel in Termux… | A (existing coordinator string) |
| `check_hub_step_elapsed` | %1$s · %2$ds | A |
| `check_hub_slow` | This is taking longer than usual. | A |
| `check_hub_passed` | Cline Hub @%1$s · core %2$s | A (uses DS "Cline Hub @%1$s") |
| `check_hub_failed` | Couldn't start the Cline Hub. | A |
| `check_hub_timed_out` | Termux didn't answer within 60 seconds. | A |
| `check_hub_protocol` | This Cline Hub speaks a protocol this app doesn't support. | A |
| `check_hub_auth` | The Cline Hub rejected this app's credentials. | A |
| `check_hub_stopped_meanwhile` | The Cline Hub stopped. | A |
| `check_hub_credentials_unreadable` | The Cline Hub started but its credentials could not be read. | A |
| `check_hub_handshake` | The Cline Hub refused the connection handshake. | A |
| `kernel_start` | Start kernel | DS |
| `kernel_starting` | Starting… | A |
| `kernel_try_again` | Try again | D |
| `kernel_run_diagnostics` / `kernel_running_diagnostics` | Run diagnostics / Running diagnostics… | A |
| `kernel_step_checking_termux` | Checking Termux… | A |
| `kernel_step_checking_kernel` | Checking the Cline kernel… | A |
| `kernel_step_starting_hub` | Starting Cline Hub… | A |
| `kernel_step_keeping_awake` | Keeping Termux awake… | A |
| `kernel_step_reading_credentials` | Reading Hub credentials… | A |
| `kernel_step_connecting` | Connecting… | A |
| `kernel_connected_announce` | Cline kernel connected | A |
| `onboarding_continue` | Continue | D |
| `install_termux_title` | Install Termux | A |
| `install_termux_body` | Cline runs its agent inside Termux, a terminal app for Android. Install Termux, then come back here. Cline continues by itself. | A |
| `install_termux_builds` | Use the F-Droid or GitHub build. Cline is tested with Termux 0.118. | A |
| `install_termux_fdroid` / `_github` | Get Termux on F-Droid / Get Termux on GitHub | A |
| `common_link_cd` | %1$s, opens in browser | A (all external links, 0.7 `openUrl`) |
| `install_termux_update_title` | Update Termux | A |
| `install_termux_update_body` | Termux %1$s is too old for Cline. Install Termux 0.109 or later from the same place you got it, then come back here. | A |
| `install_termux_disabled_title` | Turn on Termux | A |
| `install_termux_disabled_body` | Termux is turned off on this device. Turn it back on in Settings, then come back here. | A |
| `install_termux_setup_title` | Open Termux once | A |
| `install_termux_setup_body` | Termux is installed. Open it once and wait until it shows a prompt, so it can finish installing its basic packages. | A |
| `install_termux_waiting` | Waiting for Termux… | A |
| `install_termux_ready` | Termux is ready | A |
| `common_no_browser` | No browser found to open %1$s. | A |
| `connect_title` | Set up Cline | D |
| `connect_body` | Choose how Cline connects to models. You can add more providers anytime in Settings. | D |
| `connect_cline_title` | Sign in with Cline | D |
| `connect_recommended` | Recommended | D |
| `connect_cline_benefit_1` | Regular free model promotions | D |
| `connect_cline_benefit_2` | Subscribe to ClinePass for generous usage across the best open weights models like DeepSeek, Kimi, and GLM | D |
| `connect_cline_benefit_3` | No API key needed | D |
| `connect_signed_in_as` | Signed in as %1$s | D |
| `connect_signed_in` | Signed in to Cline | A |
| `connect_sign_in` | Sign in | D |
| `connect_waiting_browser` | Waiting for browser... | D |
| `connect_waiting_help` | Termux opened to sign you in. Open the link it shows, approve the sign-in in your browser, then come back to Cline Termux. | A |
| `common_cancel` | Cancel | D |
| `connect_sign_up` | Sign up | D |
| `connect_sign_in_failed` | Sign in failed: %1$s | D |
| `connect_sign_in_timeout` | the sign-in did not finish within 15 minutes. | A (detail) |
| `connect_sign_in_exit` | cline auth exited with code %1$d in Termux. | A (detail) |
| `connect_cline_key_toggle` | Use a Cline API key | D |
| `connect_cline_key_field` | Cline API key | D |
| `connect_connect` / `connect_connecting` | Connect / Connecting... | D |
| `connect_find_key` | Find your key | D |
| `connect_cline_key_failed` | Failed to save API key: %1$s | D |
| `connect_cline_key_unverified_detail` | the key could not be verified (%1$s) | D (detail) |
| `connect_api_title` | Use your own API key | D |
| `connect_api_body` | Anthropic, OpenAI, OpenRouter, and more. | D |
| `connect_providers_failed` | Failed to load providers: %1$s | D |
| `connect_provider_label` | Provider | D |
| `connect_provider_loading` | Loading providers... | D |
| `connect_provider_placeholder` | Choose a provider | D |
| `connect_api_key_label` | API key | D |
| `connect_api_key_placeholder` | %1$s API key | D |
| `connect_show_key` / `connect_hide_key` | Show API key / Hide API key | D |
| `connect_model_label` | Model | D |
| `connect_model_supporting` | Cline starts new sessions with this model. You can switch models in the composer. | A |
| `connect_model_required` | Enter a model ID. | A |
| `connect_base_url_label` | Base URL | A |
| `connect_base_url_placeholder` | https://api.example.com/v1 | D |
| `connect_base_url_invalid` | Enter a base URL that starts with http:// or https://. | A |
| `connect_get_key` | Get a %1$s API key | D |
| `connect_more_providers` | More providers are in Settings → Providers. | A |
| `connect_save_failed` | Failed to save provider: %1$s | D |
| `connect_save_timeout_detail` | Termux didn't answer within 30 seconds. | A (detail) |
| `connect_kernel_unavailable` | Cline can't reach Termux right now. | A |
| `connect_open_kernel_setup` | Open kernel setup | A |
| `connect_skip` | Skip | D |
| `done_title` | You're all set | D |
| `done_provider_connected` | %1$s is connected. | D |
| `done_cline_connected` | Your Cline account is connected. | D |
| `done_cline_unverified` | Cline checks the key when you send your first message. | A |
| `done_clinepass_title` | ClinePass | D |
| `done_clinepass_body` | Generous usage across the best open weights models like DeepSeek, Kimi, and GLM. | D |
| `done_clinepass_cta` | Get ClinePass | D |
| `done_start_building` | Start building | D |
| `reliability_title` | Keep the kernel running | A |
| `reliability_body` | Android and One UI stop apps in the background to save battery. When that happens to Termux, the Cline Hub stops and running tasks are interrupted. These settings prevent it. | A |
| `reliability_progress` | %1$d of %2$d done | A |
| `reliability_group_required` / `_recommended` / `_optional` | Required / Recommended / Optional | A |
| `reliability_status_done` / `_not_set` / `_check_yourself` | Done / Not set / Check yourself | A |
| `reliability_all_done` | Cline and Termux can keep running in the background. | A |
| `reliability_confirm` | I've done this | A |
| `reliability_unavailable` | This setting isn't available on this device. | A |
| `reliability_skip` | Skip for now | D |
| `rel_termux_battery_title` | Termux battery | A |
| `rel_battery_done` | Unrestricted | A |
| `rel_termux_battery_not_set` | Optimized — Android may stop Termux in the background. | A |
| `rel_battery_instructions` | Tap Battery, then choose Unrestricted. | A |
| `rel_open_termux_settings` | Open Termux settings | A |
| `rel_cline_battery_title` | Cline battery | A |
| `rel_cline_battery_not_set` | Optimized — Android may stop Cline in the background. | A |
| `rel_allow` | Allow | A |
| `rel_never_sleeping_title` | Never sleeping apps | A |
| `rel_never_sleeping_body` | Add Termux and Cline to Never sleeping apps. | A |
| `rel_never_sleeping_path` | Settings → Battery → Background usage limits → Never sleeping apps | A |
| `rel_never_sleeping_done` | You added Termux and Cline. | A |
| `rel_open_battery_settings` | Open battery settings | A |
| `rel_child_title` | Child process restrictions | A |
| `rel_child_not_set` | Android may stop the Hub's helper processes when many are running. Turn on “Disable child process restrictions” in Developer options. | A |
| `rel_child_dev_off` | Turn on Developer options first: Settings → About phone → Software information → tap Build number 7 times. | A |
| `rel_child_adb` | Run this from a computer with adb. | A |
| `rel_open_dev_options` | Open developer options | A |
| `rel_notifications_title` | Notifications | A |
| `rel_notifications_done` | On | A |
| `rel_notifications_not_set` | Off — you won't hear about approvals or finished tasks while Cline is in the background. | A |
| `rel_overlay_title` | Termux: display over other apps | A |
| `rel_overlay_body` | Lets Termux open your browser by itself during Cline sign-in. Not needed for anything else. | A |
| `rel_open_termux_setting` | Open Termux setting | A |
| `kernel_sheet_pane_title` | Kernel status | A |
| `kernel_sheet_product` | Cline Termux | A (the app's name) |
| `kernel_sheet_version` / `kernel_sheet_version_unavailable` | Version %1$s / Version unavailable | D |
| `hub_at_port` | Cline Hub @%1$s | D |
| `hub_at_unknown` | Cline Hub @unknown | D |
| `hub_not_connected` | Cline Hub is not connected. | D |
| `hub_line_cd` | Cline Hub at port %1$s, %2$s | A |
| `kernel_status_connected` | Connected | A |
| `kernel_status_connecting` | Connecting... | D |
| `kernel_status_reconnecting` | Reconnecting... | D |
| `kernel_status_draining` | Draining | A |
| `kernel_status_asleep` | Termux asleep | DS |
| `kernel_status_asleep_line2` | Start kernel to continue. | DS |
| `kernel_status_stopped` | Kernel stopped | A |
| `kernel_status_stopped_in_termux` | Stopped in Termux | A |
| `kernel_status_restarting` | Restarting… | D (hub-update-required-dialog.tsx) |
| `kernel_status_disconnected` | Disconnected | A |
| `kernel_status_not_connected` | Not connected | A |
| `kernel_status_update_required` | Update required | A |
| `kernel_status_setup_needed` | Setup needed | A |
| `kernel_row_status` / `_kernel` / `_core` / `_build` / `_process` / `_uptime` / `_protocol` / `_hub_id` / `_event_log` / `_active_runs` | Status / Kernel / Core / Build / Process / Uptime / Protocol / Hub ID / Event log / Active runs | A |
| `kernel_value_pid` | pid %1$d | A |
| `kernel_value_sequence` | sequence %1$d | A |
| `kernel_value_runs` | %1$d running · %2$d queued | A |
| `kernel_more_details` | More details | A |
| `kernel_draining_note` | The Cline Hub is finishing current work before it restarts. | A |
| `kernel_update_title` | Restart to use %1$s | A |
| `kernel_update_body` | Cline kernel %1$s is installed, but the running Cline Hub started before the update. | A |
| `kernel_update_restart` | Restart | A |
| `kernel_keep_awake` | Keep Termux awake | A |
| `kernel_keep_awake_body` | Holds Termux's wake lock so Android does not stop the Cline Hub while Cline is connected. | A |
| `kernel_keep_awake_failed` | Couldn't take Termux's wake lock: %1$s | A |
| `kernel_reliability_row` | Background reliability | A |
| `kernel_settings_row` | Kernel settings | A |
| `kernel_restart` / `kernel_restarting` | Restart kernel / Restarting… | A / D |
| `kernel_stop` / `kernel_stopping` | Stop kernel / Stopping… | A |
| `kernel_disconnect` / `kernel_connect` | Disconnect / Connect | A |
| `kernel_fix_kernel` | Fix the Cline kernel | A |
| `kernel_restart_failed` | Restarting the Cline Hub failed. Try again, or run 'cline doctor fix' in a terminal. | A (adapted from D) |
| `kernel_restart_busy_hint` | Finish the sessions in the other Cline app, then try again. | A |
| `kernel_restart_confirm_title` | Restart the Cline kernel? | A |
| `kernel_restart_confirm_body` | The Cline Hub is still serving %1$s. Restart kernel stops that Hub and interrupts its sessions. | A (adapted from the D `outdated_hub` sentences) |
| `kernel_restart_confirm_own` | A task is still running. Restarting the Cline kernel interrupts it. | A |
| `kernel_restart_phase_checking` / `_disconnecting` / `_replacing` / `_starting` / `_reconnecting` | Checking sessions… / Disconnecting… / Replacing the Cline Hub… / Starting Cline Hub… / Reconnecting... | A / A / A / A / D |
| `hub_sessions_count` (plurals) | one: %1$d active session · other: %1$d active sessions | D (`describeOutdatedHubSessions`, shared/src/hub.ts) |
| `hub_sessions_from_clients` (plurals on %2$d) | one: %1$s from %2$d connected Cline client · other: %1$s from %2$d connected Cline clients | D (same) |
| `hub_sessions_fallback` | active sessions from other Cline clients | D (same) |
| `kernel_stop_not_stopped` | The Cline Hub did not stop. Try again, or use Fix processes. | A |
| `kernel_stop_confirm_title` | Stop the Cline kernel? | A |
| `kernel_stop_confirm_body` | The Cline Hub is still serving %1$s. Stopping it interrupts them. | A |
| `kernel_stop_confirm_own` | A task is still running. Stopping the Cline kernel interrupts it. | A |
| `kernel_stopped_snackbar` | Cline kernel stopped | A |
| `kernel_restarted_snackbar` | Cline Hub restarted | A |
| `kernel_disconnected_snackbar` | Disconnected from the Cline Hub | A |
| `kernel_copied_value` | Copied %1$s | A |
| `diag_overline` | Diagnostics | A |
| `diag_hub_healthy` / `diag_yes_pid` / `diag_no` | Hub healthy / Yes (pid %1$d) / No | A |
| `diag_listeners` / `diag_stale` / `diag_locks` / `diag_cli` / `diag_cli_version` / `diag_uptime` | Hub listeners / Stale hub daemons / Hub startup locks / CLI processes / CLI version / Hub uptime | A |
| `diag_none` | None | A |
| `diag_privacy` | Prompts, conversation contents, API keys and the Hub token are never shown here. | A |
| `diag_fix` / `diag_fixing` | Fix processes / Fixing… | A |
| `diag_fix_confirm_title` | Stop all Cline processes? | A |
| `diag_fix_confirm_body` | This runs cline doctor fix in Termux. It stops the Cline Hub and every other Cline process there, including open TUI sessions. | A |
| `hub_updated_title` | Cline Hub was updated | D (`unsupported_protocol` dialog) |
| `hub_updated_body` | Another Cline installation updated the shared Cline Hub (core %1$s), and it no longer matches this app. | D (desktop template with the core part) |
| `hub_updated_body_no_core` | Another Cline installation updated the shared Cline Hub, and it no longer matches this app. | D (desktop template without it) |
| `hub_updated_body_android` | Update the Cline Termux app to keep using this Hub. If you recently went back to an older Cline kernel in Termux, Restart kernel starts the Hub that is installed there. | A |
| `hub_outdated_title` | Cline Hub update required | D (`outdated_hub` dialog) |
| `hub_outdated_body` | This app needs a newer Cline Hub than the one running. Restart kernel starts the Hub that is installed in Termux. If that doesn't help, update the Cline kernel by running the installer again in Termux: | A |
| `hub_still_unsupported` | The Cline Hub installed in Termux still doesn't match this app. | A |
| `hub_update_later` / `hub_update_try_again` | Later / Try again | D |
| `state_asleep_body` | Android stopped Termux while it was in the background, and the Cline Hub stopped with it. Your sessions are saved. Start kernel to continue. | A + DS |
| `state_asleep_why` | Why did this happen? | A |
| `state_stopped_body` | You stopped the Cline kernel. Start it again to keep working. | A |
| `state_stopped_in_termux_body` | The Cline Hub was stopped in Termux. Start it again to keep working. | A |
| `state_disconnected_body` | Cline is not connected to the Cline Hub. The Hub is still running in Termux. | A |
| `state_failed_title` | Couldn't start the Cline Hub | A |
| `state_connect_failed_title` | Couldn't connect to the Cline Hub | A |
| `state_crash_loop` | The Cline Hub stopped twice right after starting, so Cline stopped restarting it. | A |
| `state_kernel_status` | Kernel status | A |
| `state_details` | Details | A |
| `banner_stopped` | Kernel stopped | A (line 1; line 2 is `hub_not_connected`; Asleep uses `kernel_status_asleep` + `_line2`) |
| `banner_disconnected` | Disconnected from the Cline Hub. | A |
| `banner_degraded` / `banner_degraded_line2` | Cline can't run commands in Termux. / Some features are unavailable. | A |
| `banner_fix` | Fix | A |
| `chat_transport_connecting` / `_reconnecting` / `_unavailable` | Connecting chat... / Reconnecting chat... / Chat backend unavailable | D |
| `composer_kernel_down_placeholder` | Cline Hub is not connected. | D |
| `list_unavailable_state` | unavailable | A |

---

## 10. Cross-cutting acceptance (this group)

- [ ] Every string in section 9 exists in `strings.xml` under its key, desktop strings byte-identical
      to the quoted source (a unit test compares the **D** rows against a fixture extracted from the
      desktop files at the pinned tag). The fixture takes `hub_updated_title` and the two
      `hub_updated_body*` templates from the `unsupported_protocol` branch of
      `hub-update-required-dialog.tsx`, `hub_outdated_title` from its `outdated_hub` branch,
      `hub_update_later` / `hub_update_try_again` / `kernel_status_restarting` from the same file,
      and the `hub_sessions_*` strings from `describeOutdatedHubSessions` in
      `sdk/packages/shared/src/hub.ts`. "Update Now", "Updating…", "Quit Cline" and "Update and
      restart" are deliberately absent (no app updater, no quit on Android).
- [ ] Onboarding completes end to end on the S25U with the real kernel: Welcome → Kernel (5/5) →
      Keep the kernel running (if shown) → Connect (Cline or API key) → Done → Home with a new
      thread; relaunching goes straight to Home.
- [ ] Settings → "New user experience" → "Replay" replays from Welcome without touching the kernel
      or providers.
- [ ] No screen in this group calls `dynamicLightColorScheme`; all colours come from roles or
      `ClineTheme.colors` (grep `Color(0x` outside `ui/theme/` is empty).
- [ ] Predictive back follows A.3 / B.3 / C.3 / D.3 / E.3 on Android 16; no always-on `BackHandler`.
- [ ] Debug fake-kernel scenarios in 0.9 all render the states their sections describe; release build
      contains none of them (`apkanalyzer dex packages` shows no `fake` package).
- [ ] `adb logcat -d --pid=$(adb shell pidof io.github.ichouchiang.clinetermux)` after a full onboarding contains no
      Hub token (64 hex), no `authToken`, no `apiKey`, no `--apikey` value.
- [ ] Layouts hold at system font scale 2.0 × in-app font size 20 on the S25U and the Tab S7+, light
      and dark, dynamic colour on and off.
- [ ] A debug-build RUN_COMMAND trace over a full onboarding plus 15 minutes idle on Home shows
      `readProviders` only at the triggers in 0.5 and no RUN_COMMAND at all from the passive
      probe (it is HTTP only).
- [ ] Every external link in this group opens through `openUrl` (grep for `ACTION_VIEW` and
      `CustomTabsIntent` outside `ExternalLinks.kt` is empty).

---

## 11. Open questions

1. The DS rows in 0.7 (components, `openUrl`, the `androidx.browser` dependency, the button-size
   rule, the symbols `warning`, `bedtime`, `stop_circle`, `link_off`/`link`, `hourglass`,
   `restart_alt`, `troubleshoot`, `open_in_new`) must be pasted into `design-system.md` §0, §4.2,
   §4.3 and §7.3/§7.4 before implementation, together with the 0.10 changes to the sibling
   specs. *Reviewed: kept as an open item because this revision's author of record is this spec
   only; the design system and the sibling specs have their own authors working in parallel. The
   0.7 tables are written as paste-ready rows so that commit is mechanical, and until it lands
   0.7 and 0.10 are normative.*
2. **Existing-code defect:** `TermuxCommand.toString()` joins all arguments, so
   `TermuxUnavailable.TimedOut` (and any log of a command) would contain the `--apikey` value from
   `ClineCommands.authProvider`. Redact before C ships (0.6 rule 4).
3. Termux may log RUN_COMMAND arguments at its default log level. Check `adb logcat -s
   Termux:* TermuxRunCommand:*` during an `authProvider` call; if the key appears, switch to
   `RUN_COMMAND_STDIN` with `bash -c 'read -r k; exec cline auth --provider "$1" --apikey "$k" --modelid "$2"'`.
4. The `pkill -f "index.js auth cline"` pattern for cancelling sign-in is unverified against the
   launcher's real command line (`node cline-node-wrapper.cjs …`); check `pgrep -af auth` on the S25U.
5. The One UI 8 component for "Never sleeping apps" (Samsung Device Care, `com.samsung.android.lool`)
   is unverified; resolve with `adb shell cmd package query-activities` before hard-coding it.
6. Reading `settings_enable_monitor_phantom_procs` from `Settings.Global` with `targetSdk 36` may throw
   (non-@Readable key); D item 4 falls back to a self-check, but confirm on the S25U.
7. `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is not in the kernel contract's manifest list; adding it is
   fine for a sideloaded app but should be recorded in `docs/02-kernel-contract.md`.
8. The contract says the app installs nothing in Termux, so B/A only offer "Copy command" + "Open
   Termux" for the installer and the allow-external-apps fix. Should the app also offer a visible
   "Run in Termux" (foreground session the user watches)? Needs the owner's call.
9. Owner decision 12 (keep the Hub running after the app exits) decides whether "Disconnect" and the
   app's exit should also release the wake lock and whether the app auto-stops the Hub when the last
   session ends; E's actions are designed for either default.
10. Whether the Google Play build of Termux works with the port and RUN_COMMAND; B currently steers to
    F-Droid/GitHub.
11. Whether Termux delivers the PendingIntent result for a foreground-session command (sign-in exit
    code). If not, sign-in failure is only detected by the 15-minute timeout or the user's Cancel.
12. Without the settings helper (blocking unknown 3), C cannot show the Cline account name ("Signed in
    to Cline" instead of "Signed in as …") and cannot verify a Cline API key before Done.
13. Closed: `cline hub status` is no longer called (0.5), so `HubStatusOutput` needs no new
    fields. Still open: the coordinator's `cline --version` probe must be replaced by
    `cat VERSION` (0.4).
14. The desktop's `outdated_hub` case (a newer app finding an older Hub kept alive for other
    clients) maps on Android to the "Restart to use <release>" row plus E.7's restart confirm,
    which reuses that dialog's sentences. Whether a port upgrade with the TUI holding a session can
    leave a Hub that is *protocol*-incompatible (E.9 "Hub older") is unverified; today it cannot,
    because every shipped Hub accepts exactly v1.
15. Degraded mode (0.4: permission revoked but Hub alive with a cached token) adds a path the current
    `KernelCoordinator.ensureConnected` does not have (it returns at `PermissionMissing`); confirm the
    owner wants it rather than always showing the repair screen.
16. `~/.cline/data/logs/hub-daemon.log` is read only for the `entry.js` match (0.4) and never
    shown. Before relying on it, confirm on the S25U that the daemon log never contains the auth
    token or provider keys (`ssh <phone> grep -c -E "[0-9a-f]{64}|sk-" ~/.cline/data/logs/hub-daemon.log`
    returns 0); if it can, keep the match but read only lines containing `entry.js` via
    `grep -m1 -F entry.js` instead of `tail`.
17. `statProviders` relies on `$PREFIX/bin/stat` from coreutils (Termux bootstrap) and on the CLI
    rewriting `providers.json` (new mtime) when `cline auth cline` finishes. Verify with
    `ssh <phone> stat -c %Y ~/.cline/data/settings/providers.json` before and after a sign-in.
18. The Hub's close behaviour on the device uses bun-android-ffi's built-in `ws` (the port keeps
    `ws` external). The discovery-file discriminator (0.3) does not depend on close codes, but
    record one `cline hub stop` with the app connected on the S25U (OkHttp `onFailure` vs
    `onClosed`, and the code if any) as a fixture so the reconnect path is tested against the real
    behaviour.
