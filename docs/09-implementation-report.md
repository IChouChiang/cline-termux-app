# 09 — Implementation report

For the owner's review before anything is pushed. State of `main` on 2026-10-06
(integration commits `bde501b`, `ac773a2`, `6224bd4`, `73e6b54`, then four polish
rounds and the review fixes up to `db7b584`). Nothing has been pushed.

Contract and specs: [07-app-architecture.md](07-app-architecture.md),
[../design/design-system.md](../design/design-system.md), the screen specs in
[../design/screens/](../design/screens/), [02-kernel-contract.md](02-kernel-contract.md),
[08-hub-fixtures.md](08-hub-fixtures.md).

## 1. What was built

The app is laid out as in docs/07 §1.1, under
[`app/src/main/kotlin/dev/cline/termux/`](../app/src/main/kotlin/dev/cline/termux/). Every
route in docs/07 §6.2 is registered, and every screen in the §13 index has an implementation.

- **`core/kernel`** (KS §0, docs/02). `KernelCoordinator` runs the KS §0.3 state model and
  the §0.4 connect sequence: Termux presence and permission, the `cat VERSION` port check
  against `BuildInfo.MIN_PORT_RELEASE = 3.0.68-termux.2`, `cline hub start`, discovery and
  `/health`. It also auto-starts the Hub and probes it passively. `KernelGate` is the one
  projection that screens read. `hub/HubClient` keeps one global cursor with a 2000-event
  replay budget (D2), subscribes per session only after the replay has drained, has a 10 s
  handshake deadline and pings every 30 s. The `termux/` bridge covers `TermuxExecutor`
  (`run`, `runDetached`, ANSI stripping, the timeout table), the full `ClineCommands`
  catalog and `ConfiguredProviders` (a redacted projection; `ProvidersFile` is deleted, D5).
  `dashboard/` holds the settings-helper lease and the `DashboardClient` (SP §0.3), and
  `core/secure` holds `SecretBox` and `SecureClipboard`.
- **`data/session`, `data/chat`** (HC §1.3, CA C1/C5/C6). `SessionStore` is the only
  collector of Hub events (D15). It feeds two pure reducers, `SessionFactsReducer` and
  `ChatProjector`, and fixture tests check both against the same input (D14). Around them
  sit `HistoryMapper`, `Reconciler`, `TurnRecord` and `InterruptionDetector`. The send side
  has `SendPipeline`, `SessionCreateBuilder`, slash commands, `@` mention search over
  RUN_COMMAND, attachments (images, plus text files sent through stdin chunks) and the
  context-usage calculator.
- **`data/sessions`, `data/diff`, `data/tools`, `data/checkpoint`** (SS, CT). These cover
  the session list with unread counts, flags and pending actions, plus `KnownSessions`. The
  diff, line-diff and unified-diff code is ported from the desktop, along with tool
  summaries, `TerminalBuffer` and the checkpoint index. Restore uses the split path
  (workspace restore, then `session.create`), because `MIN_PORT_RELEASE_RESTORE` is `null`.
- **`data/providers`, `model`, `workspace`, `catalog`, `prefs`** (SP). The provider catalog
  has a 60 s TTL and falls back to the bundled `fallback_providers.json`. Model selection and
  reasoning settings, `buildModelPickerData`, workspace recents and validation, and every
  docs/07 §7 DataStore key are here, with JSON caches for the session list and the catalog.
- **`data/customize`, `marketplace`, `schedules`, `account`, `agents`, `about`,
  `diagnostics`, `notifications`** (CR). These cover `settings.list`/`toggle` and the CLI
  catalogs, MCP editing through the helper (with a file and CLI fallback), the marketplace
  catalog and installs, `schedule.*` with `allWorkspaces:true`, Cline account operations and
  `ClineSignInCoordinator` (SP S7). They also cover the agents roster, the changelog and
  What's new, the redacted diagnostics bundle, notification preferences and `KeepAlivePolicy`.
- **`service`** (CR §11–12). `KernelForegroundService` with its notification variants, Stop
  agent and Disconnect actions, `ClineNotifier`, the eight session channels plus account and
  kernel, `NotificationRouter` (intent extras, D10) and `ShareFiles` behind the one
  FileProvider authority, `dev.cline.termux.files` (D7).
- **`ui`** (one package per spec group; see docs/07 §13):
  - Shell and navigation: drawer on compact windows, navigation rail on medium and expanded,
    list-detail on expanded.
  - Onboarding and kernel (KS): Welcome, kernel setup and repair, Install Termux,
    Reliability, Connect a model, Setup done, the kernel status sheet and the Hub update
    dialog.
  - Home and chat (HC): the chat rows, header, details sheet and lightbox.
  - Composer, queue, approval, question and suggestions (CA).
  - Sessions drawer, sessions screen, search, details sheet and delete dialog (SS).
  - Changes, tool detail, terminal, diff and checkpoint dialogs and picker (CT).
  - Settings, appearance, notifications, providers, model picker and workspace picker with
    folder browser (SP).
  - Customize, the MCP editor, marketplace, schedules, account, agents, About, What's new,
    diagnostics and licenses (CR).
  - `ui/components` holds the DS §4 inventory, `ui/theme` the voice-port theme, and
    `ui/markdown` the voice-port renderer.

  User-visible copy lives in `res/values/strings_<package>.xml`.
  [strings-audit.md](strings-audit.md), generated by `tools/strings-audit/audit.py`, matches
  every spec string to a resource and lists none as unresolved.
- **Debug only** (`app/src/debug`): the developer Hub token override, the scripted fake
  kernel (`debug/fake/`, 29 `FakeKernelScenario`s) and `KernelProbe`. The release
  source set has pass-through stubs with the same signatures.

## 2. Developer loop and phone verification

Build and test with `bash scripts/build.sh :app:assembleDebug` and
`bash scripts/build.sh :app:testDebugUnitTest`. Install with `scripts/install.sh [apk]`
(`adb install -r -g`, then launch; the debug build `io.github.ichouchiang.clinetermux.debug` by default, and
never an APK whose package is the owner's `io.github.ichouchiang.clinetermux`) and capture the screen with
`scripts/screenshot.sh <name>`, which saves to `screenshots/<name>.png`. Do not run either
on the S25U while a port candidate or the owner's manual test is running there.

**Developer Hub** ([`scripts/dev-hub.sh`](../scripts/dev-hub.sh)) runs a real Hub on this
workstation with an isolated `CLINE_DIR` under `/tmp/cline-dev-hub`, so it never touches
`~/.cline` and spends no LLM credits:

```bash
scripts/dev-hub.sh start      # mock OpenAI provider :25499 + Hub :25498, scratch git workspace
scripts/dev-hub.sh reverse    # adb reverse phone 127.0.0.1:25498 -> Hub, push token + port 25498 to io.github.ichouchiang.clinetermux.debug, stop it
scripts/dev-hub.sh helper     # settings-helper dashboard :8787 (+ reverse), cold-starts the app with its room secret
scripts/dev-hub.sh status     # liveness + discovery record
scripts/dev-hub.sh unhelper; scripts/dev-hub.sh unreverse; scripts/dev-hub.sh stop
```

The mock streams a text answer to any prompt. A prompt containing `hello-from-mock` first
makes a `run_commands` tool call, so an approval sheet appears when auto-approve is off. A
prompt containing `mock-long-output` makes a `run_commands` call of `seq 1 80` (80 lines of
output, for the CT B7 terminal check); the other `mock-*` markers are listed at the top of
`tools/hub-fixtures/mock-openai.ts`.

**Fake kernel** (debug builds; documented in `app/src/debug/.../KernelOverrides.kt`). It
scripts both Termux and the Hub for the KS states that no real phone can be put into on
demand:

```bash
adb shell am start -S -n io.github.ichouchiang.clinetermux.debug/dev.cline.termux.MainActivity \
  --es dev.cline.termux.FAKE_KERNEL hub_start_slow [--ez dev.cline.termux.RESET_ONBOARDING true]
adb shell am start -S -n io.github.ichouchiang.clinetermux.debug/dev.cline.termux.MainActivity --es dev.cline.termux.FAKE_KERNEL off
```

The selection persists across process restarts until `off`. Scenario ids are listed in
`debug/fake/FakeKernelScenario.kt`. While a fake kernel runs, the developer Hub token is set
aside.

**Fixtures**: `tools/hub-fixtures/regenerate.sh` re-records
`app/src/test/resources/hub-fixtures/` (docs/08).

## 3. Acceptance status (S25U, debug build)

Each spec group's acceptance checklist was run on the phone against the developer Hub and the
fake kernel. The phone's installed kernel is `v3.0.68-termux.1`, which the app correctly
reports as too old (`screenshots/setup/20-real-kernel-port-too-old.png`), so no group ran
against a real on-phone Hub.

| Group (spec) | Passed | Failed | Untestable | Screenshots |
| --- | ---: | ---: | ---: | --- |
| setup | 12 | 0 | 2 | [screenshots/setup/](../screenshots/setup/) |
| onboarding-kernel (KS) | 50 | 2 | 7 | [screenshots/onboarding-kernel/](../screenshots/onboarding-kernel/) |
| home-chat (HC) | 47 | 2 | 11 | [screenshots/home-chat/](../screenshots/home-chat/) |
| composer-approvals (CA) | 48 | 1 | 18 | [screenshots/composer-approvals/](../screenshots/composer-approvals/) |
| sessions (SS) | 38 | 2 | 12 | [screenshots/sessions/](../screenshots/sessions/) |
| settings-providers (SP) | 40 | 3 | 14 | [screenshots/settings-providers/](../screenshots/settings-providers/) |
| changes-tools-checkpoints (CT) | 47 | 3 | 11 | [screenshots/changes-tools-checkpoints/](../screenshots/changes-tools-checkpoints/) |
| customize-and-rest (CR) | 38 | 2 | 10 | [screenshots/customize-and-rest/](../screenshots/customize-and-rest/) |
| design-system (DS) | 16 | 0 | 9 | [screenshots/design-system/](../screenshots/design-system/) |
| **Total** | **336** | **15** | **94** | |

The visual polish rounds are in `screenshots/polish-r1/` … `polish-r4/`.

A machine reboot killed five round-3 fixers before they committed, which dropped nine
confirmed findings. On 2026-10-06 each one was re-checked against main. Round 4 had fixed
none of them, so eight were fixed then. Marketplace: CLI-route results are worded from
resources, the dead TYPE_META copy is gone, one shared Kernel-block handler and focus map
replace the copies, `TermuxProblem.block()` and the Customize tab ids each have one
definition, and env-var names no longer use a synthesized mono bold. Onboarding: the app's
start-failure sentences are no longer set in mono LTR. Schedules: only the workspace path and
the id are LTR. The ninth, cjk-i18n-07, was fixed afterwards in `ed0bcce`: every time of day
(schedule cards, runs and editor field, message timestamps, approval times, account usage
times) now follows Android's "Use 24-hour format" setting through `data/schedules/ClockFormat`,
not only the locale's default clock. Two parts of
the others were left on purpose. Account, About and Schedules still send "Allow" through the
pushed repair screen, which makes the permission request there. Markdown keeps its SemiBold
over inline code (`MarkdownModel.kt` `CodeSpan`).

The 15 failures fall into four groups:

- **Fixed and re-verified, still counted** (the evidence rule counts an item that failed
  twice before its fix). SP has three: the S9 workspace sheet did not reopen after "Use this
  folder" (`3f31017`); the S8 sheet height ignored the 90 % cap (`6eb475d`); and text clipped
  on S2 and S4 at font scale 2.0 × size 20 (`d2152a9`, `1a41646`).
- **The specs conflict with each other or with the desktop; you decide** (§6):
  - KS F.3, two items: the inline transport line, and the composer placeholder "Start kernel
    to continue." where KS F.3 says "Cline Hub is not connected.".
  - HC §3.16 #5: `read_files` and `fetch_web_content` rows show no output text, and neither
    does the desktop.
  - CT B9 and B12: a failing or aborted command reads "Completed", because the kernel
    reports `success:false` inside a result that is not an error. The desktop shows the same.
  - SS S3: the "Showing 15 of N" footer cannot be seen while scrolling, as on the desktop.
- **Open defects**:
  - HC §3.16 #23: 30–34 % janky frames during a 40-chunk stream (p90 34–36 ms), against a
    5 % budget. This was a debuggable build without R8, so it should be re-measured on a
    release-like build.
  - KS D.6 item 5, Reliability "Notifications" → "Allow" when the system prompt is
    dismissed without a choice (Back): the result is a denial with no rationale, which the
    screen reads as "denied for good", so it opens the system notification settings in the
    same tap (seen on the S25U after a `pm revoke`). By code reading, an explicit second
    "Don't allow" does the same (not tried on the device). SP S3 does not: it waits for the next tap ("requested before" in saved
    state). The API cannot tell a dismissed prompt from a permanent denial in the result,
    so a fix trades one case for another (SP S3's way needs two taps when the permission
    was denied for good before the screen opened). Not changed; your call whether both
    surfaces should behave like SP S3.
- **Decided by the lead (2026-10-06), no longer open**: CA C1.15 item 17, landscape with
  the IME open **and** a pending approval. The banner (56dp) and the composer fill the
  136dp the Samsung keyboard leaves, so no transcript row is visible
  (`screenshots/closeout/3-ime-open-approval-banner-light.png`). In that state the approval
  banner has priority over transcript rows, because it is the context the user is replying
  to, and no further layout change is made. C1.4 step 6, C1.15 item 17 and docs/07 D18 now
  say so (`bde55e3`). Seen on the device: with a 2-line draft the IME-open stack is taller
  still by the second field line, and the banner's top 24dp run under the status bar
  (y 56 px instead of 146 px) while its text stays readable (`closeout/4-ime-open-2line-draft-banner-light.png`); this is the same
  accepted overflow.
- **Fixed in the final device checks (2026-10-06, S25U, debug build)**:
  - CA C1.15 item 17, approval banner gone after the IME closed in landscape (`1514099`).
    Reproduced on the first try: in a height-compact window with the IME closed the dock is
    bounded to 60 % of the chat pane (≈ 173dp) and scrolls from the composer up. Banner plus
    composer is ≈ 197dp, so even before any typing the banner was clipped (170 of 210 px
    showing). Back closes the IME but leaves the field focused, and the focused field's
    second line (`minLines = 2`) makes the stack ≈ 221dp, so the banner scrolled out of view
    entirely while the run still waited (`closeout/4-banner-missing-after-ime-close.png`).
    Landscape + Back: 4 of 4 runs. Portrait: 0 of 3 (the dock is not bounded there).
    Tapping the transcript or outside the field does not close the IME or clear focus in
    either orientation, so Back is the only close path tested. Fix: with an approval or
    question banner docked, the bound is the whole pane (`DockRules.maxHeight`, unit test
    in `ChatUiStateTest`). After the fix: the banner is fully visible (y 570–780 px, 56dp)
    in 5 of 5 cycles plus 3 of 3 on the final build; the transcript keeps 56dp, or 32dp
    with a 3-line draft, where the banner also stays whole.
  - Notifications-denied leftovers, walked after `pm revoke … POST_NOTIFICATIONS`. KS
    Reliability (onboarding replay, fake kernel `providers_anthropic`): the row reads
    "Notifications", "Not set", "Off — you won't hear about approvals or finished tasks
    while Cline is in the background." with the action "Allow", which raised the system
    prompt, as D.6 item 5 says (`closeout/5-reliability-notifications-denied-light.png`;
    see the open item above for what happens after a dismissal). Keep-connected against
    the developer Hub, switched on in SP S3: with the app in the background
    `KernelForegroundService` ran with `isForeground=true` for 26 s and more, no crash, no
    `ForegroundServiceStartNotAllowedException`, and nothing posted to the shade (CR §11.3:
    the FGS runs, its notification only shows in the active-apps list). Its first run was
    held by a pending approval, the second by keep-connected alone after the approval was
    approved. SP S3 kept its permission card ("Cline needs permission to show
    notifications." + "Allow notifications"). No defect. Afterwards keep-connected went
    back to off, the permission was granted, the fake kernel set to `off` and onboarding
    walked to Home.
  - Dark re-check of `d44a8b0` and `b2bc177` (verification only, landscape): the terminal
    screen shows "Ran command seq 1 80" with "Completed · 1s" under it and 12 full lines
    (69–80), readable on the terminal surface (`closeout/7-terminal-landscape-dark.png`).
    The composer with the IME open shows the field, microphone and Send at the field's end,
    no chips row, the outline and the Send arrow in contrast
    (`closeout/6-ime-open-composer-landscape-dark.png`). No defect.
- **Fixed in the landscape pass (2026-10-06, measured with uiautomator bounds, S25U
  landscape 3120 × 1440 px at 600 dpi = 832 × 384dp)**:
  - CR, Schedules FAB over the first card's switch (`81afaa5`): on height-compact windows
    page FABs stay icon-only beside an 88dp end lane (`components/FabLane.kt`); also the
    MCP tab and Providers. The card now ends at x 2790 px and the FAB spans 2850–3060 px
    (`closeout/1a-schedules-landscape-{light,dark}.png`). The rail overlap was already fixed
    by `0ff657d`: its scroll viewport starts at y 563 px, 28dp below the rail FAB, and
    scrolled items clip there (`closeout/1b-rail-scrolled-landscape-dark.png`).
  - CT B7 (`d44a8b0`): the status line moved under the title in the app bar; the 48dp row
    is composed only for command tabs. `seq 1 80` now shows 12 full lines (69–80), was 9
    (`closeout/2-terminal-landscape-light.png`). With two or more commands the tab row
    still costs about 2.4 lines. Bench note: `run_commands` fails with ENOENT in a
    session the app creates, because its chat cwd is a Termux path; create the session on
    the dev workspace (`session.create` with `cwd` `/tmp/cline-dev-hub/workspace`), then
    send from the app.
  - CA C1.15–17 device check of `56c953f` (`b2bc177`): the IME opened by a tap at the field's
    centre (`mInputShown=true`). Defects found and fixed: Send and Stop were hidden with
    the chips (they now sit at the field's end; sending with the IME open worked), an
    empty 48dp row stayed under the field, and the transcript kept 48dp of pure padding.
    Now the transcript keeps 72dp and shows the newest rows, the chips are gone, a 4-line
    draft shows 2 lines (field 48dp), and closing the IME brings the chips back.
- **Fixed in the close-out (2026-10-06)**:
  - SS kernel states, "Kernel stopped" within 5 s (`b2d004f`). Re-measured first: still
    12.6, 13.8 and 15.1 s after `39a1188`. The coordinator now classifies a drop beside the
    client's first reconnect attempt (foreground or service held). Measured with logcat
    timestamps against the developer Hub: the socket closes at +0.4 s and
    `HubDown(StoppedInTermux)` lands at +4.4 s after the stop, in 3 of 3 runs. Most of the
    remaining time is probes going through the adb reverse tunnel plus the Termux
    RUN_COMMAND round trip, so a real on-phone Hub should be faster. On this bench the
    Hub's own stop sometimes takes 7 s instead of 1 s for reasons on the workstation side;
    the runs above are the 1 s mode. Unit tests cover hanging handshakes, a Hub that
    answers again and a Hub that is still answering while it stops.
  - CA C1.15–17, landscape with the IME open (`56c953f`, lead decision D18): the field is
    capped at 2 lines and the settings chips are hidden while the IME is open on a
    height-compact window. The spec text was updated. This is verified by unit test only:
    the automated landscape check did not manage to open the IME.
- **Notifications-denied path walked** (debug build, `pm revoke … POST_NOTIFICATIONS`):
  the app was killed by the revoke and relaunched without a crash. The Settings list row
  read "Notifications are blocked in system settings." (SP S2 row). The Notifications page
  (SP S3) showed "Cline needs permission to show notifications." with "Allow
  notifications" (the never-asked branch, because a revoke allows asking again), and that
  button opened the system dialog "Allow Cline to send you notifications?". The dialog was
  dismissed without a choice and the permission stayed denied
  (`screenshots/closeout/notifications-denied-light.png`). The KS Reliability
  "Notifications" row and the CR §12 rule that the foreground service still runs without
  the permission were walked later, in the final device checks above. No defect was found.
  The permission was granted again afterwards.
- **Trace, locale of timestamps**: the approval timestamp uses
  `ofLocalizedDateTime(SHORT)` with `Locale.getDefault()` (`formatApprovalTimestamp`,
  unchanged since `dd25cff`), and no commit sets an app locale. The change from
  `2026/10/6 02:05` to `10/6/26, 10:42 PM` came from the phone's own locale list, which is
  now `en-US, zh-Hans-CN`. The first format is the zh-CN pattern.

Untestable items need what this bench lacks: the `termux.2` kernel on the phone, a real
browser sign-in, a second device or form factor, or long background runs.

## 4. Tests

- **Unit**: 2495 JVM tests (`app/src/test`, `app/src/testDebug`), 0 failures, on `3a2c82e`.
  They cover the reducers, with fixture replay of docs/08 scenarios 01–06, and the ported
  desktop `*.test.ts` cases. They also cover the repositories, ViewModels and pure
  formatters. At integration time the count was 2006; the polish rounds added the rest.
- **Compose UI**: 5 instrumented flows in
  [`CriticalFlowsTest.kt`](../app/src/androidTest/kotlin/dev/cline/termux/CriticalFlowsTest.kt):
  first run to a connected Home, send and stream, approval (approve, reject, collapse,
  stop), ask-question, and an interrupted turn that resumes after a Hub restart plus a
  notification deep link into the approval. They run against `FixtureHub`, which replays the fixtures
  in `androidTest/assets`. Their first device run was part of the setup verification. They
  are not part of `testDebugUnitTest`.
- `lintDebug`: 0 errors, 173 warnings on `3a2c82e`.
- **Release variant**, on `3a2c82e`: `assembleRelease` builds with R8 (code and resource
  shrinking; `proguard-rules.pro` needed no new rules). `testReleaseUnitTest` passes 2470
  tests, which are `app/src/test` against the `src/release` stubs. AGP 9 creates that task only
  with `-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false`. `lintRelease`: 0 errors,
  172 warnings. A static check of the shrunk APK found nothing missing:
  - all 77 `$$serializer` classes keep their names and their `Companion.serializer()`;
  - the manifest components and `androidx.startup` initializers keep their names;
  - the field names of the DataStore proto messages are intact;
  - 132 of the 136 `ms_*` drawables ship, and the 4 dropped ones have no caller.
  The release APK is unsigned and has not run on a device yet. It is 5.5 MB; the debug APK is
  23.7 MB.

## 5. Known gaps and blockers

| Gap | Reason |
| --- | --- |
| ~~The real kernel on the phone does not work yet~~ | Resolved: `v3.0.68-termux.2` (2026-10-06) and `v3.0.69-termux.1` (2026-10-07) start the Hub; batch 1 verified against `3.0.69-termux.1` (core 0.0.91). |
| Atomic checkpoint restore | `MIN_PORT_RELEASE_RESTORE = null` until kernel fix K1 ships in a named port release; only the split restore exists (§16 Q1). |
| ~~Fixture 07 (checkpoint restore) not recorded~~ | Recorded 2026-10-07 on core 0.0.91 and replayed by `CheckpointRestoreFixtureTest` (docs/08); it found the `createdAt` string defect fixed in `449ccc7`. |
| Plugin slash commands not listed | No Hub command exposes them yet. |
| Schedules on a reduced-scope connection | There is no Origin-only Hub connection, so `reducedScopeWorkspace = null`. |
| Notification deep links | Intent extras are used (D10); an AppLinks tag scheme is unconfirmed. |

## 6. Deviations from the specs

- The docs/07 §15 decisions D1–D18 override spec text. The ones visible in the UI are D10
  (intent extras instead of a `cline://` scheme), D11 (the keep-connected switch defaults to
  off, on SP S3), D17 (no Sign in with Cline sheet; S7 renders inline on Connect a model,
  Provider detail and Account) and D18 (landscape with the IME open on a height-compact
  window: a 2-line field cap and no settings chips; the CA spec text now says this).
- HC §3.9 deliberately shows "Chat backend unavailable" with a warning icon and the line
  "Start kernel to continue.", where KS F.3/F.9 ask for the desktop's 14 dp spinner. The
  composer's read-only placeholder follows HC, not KS F.3.
- Where a spec and the desktop disagree (HC §3.16 #5, CT B9/B12, SS S3), the app follows the
  desktop.
- Toolchain: `compileSdk` 37 instead of 36, because the pinned AndroidX and OkHttp artifacts
  require it (docs/06).

## 7. Questions for the owner

The open questions now live in [todo.md](todo.md) (discussion) and [decided.md](decided.md)
(the decision log); this section is kept only as a pointer so the list exists in one place.

## 8. Batch 1 (2026-10-07)

The first batch of the owner's decisions (docs/decided.md) was built as eight packages, merged
into `batch/decided-1` and verified on the S25U in one device round. The full report — packages
and commits, every verification item with its evidence, the release-build jank figures,
deviations from the decisions, known limitations and what to try first — is
[10-batch1-report.md](10-batch1-report.md). Contract changes are in
[02-kernel-contract.md](02-kernel-contract.md) ("Batch 1 additions"), the app-side decisions in
[07-app-architecture.md](07-app-architecture.md) §4.8 and §15 D20–D29, the new fixture in
[08-hub-fixtures.md](08-hub-fixtures.md).

Two figures that supersede this report's earlier ones: streaming jank on a **release** build
against the real kernel (DeepSeek, about 500 words) was 0.77 % janky frames, p90 9 ms
(the debug baseline above is 30-34 %); and the debug build now installs as
`dev.cline.termux.debug` ("Cline Debug"; `io.github.ichouchiang.clinetermux.debug` since the id change of
2026-10-08), so the developer commands in §2 use that package.

## 9. Batch 2, polish (2026-10-08)

The polish pass the owner decided on 2026-10-08 (docs/decided.md A18, with A15, A16, A17, A19,
A20, A21, A22, A23, A24 and the A11.8 cancellation) was built as five packages, Q1 to Q5,
merged into `batch/polish-2` and verified on the S25U against the bench Hub and, where a real
model was needed, the real kernel with DeepSeek. One defect the device round found, `/team`
not being refused while Teams is switched off, was fixed on the branch before the close-out.
The full report — packages and commits, every verification item with its evidence, the
deviations from the decisions, the known limitations, the trial-row 15 and `/team` verdicts and
what to try first — is [11-batch2-report.md](11-batch2-report.md).

What changed for the rest of this report: Move to workspace (§1 and batch 1's V-P4-A11.8) and
the message-row "Fork before this turn" with `/fork` (V-P7-A13) no longer exist; the
auto-approve control draws the ticked shield for Ask and the bolt for Auto with no disc; the
prompt-history fling runs left to right for the previous prompt; tool rows start folded and the
dense transcript rows are 40 dp; a session started without a folder runs in the Termux home.
The contract notes are in [02-kernel-contract.md](02-kernel-contract.md) ("Batch 2 additions"),
the app rules in [07-app-architecture.md](07-app-architecture.md) §14 and §15 D24, D27, D28 and
D30 to D36.
