export const meta = {
  name: 'cline-android-continue',
  description: 'Continue the Cline for Android build from integration: wire and audit main, then device verification against the mock Hub, polish loops and the report (phases 0–3b already merged)',
  phases: [
    { title: 'Phase 0 foundation', detail: 'hub core, termux core (prefs + shared components already merged)' },
    { title: 'Merge 0', detail: 'Opus merge; Fable lead gate' },
    { title: 'Phase 1 core', detail: 'session core, kernel UI + fake kernel, terminal/diff, desktop ports on Opus, each Opus-reviewed' },
    { title: 'Merge 1', detail: 'Opus merge; Fable lead gate' },
    { title: 'Phase 2 shell and data', detail: 'shell/nav, transcript rows, derived stores, services, sessions/providers/tier-4 data on Opus, each Opus-reviewed' },
    { title: 'Merge 2', detail: 'Opus merge; Fable lead gate' },
    { title: 'Phase 3a screens', detail: 'sixteen screen lanes on Opus, each Opus-reviewed and fixed up before merge' },
    { title: 'Merge 3a', detail: 'Opus merge; Fable lead gate' },
    { title: 'Phase 3b chat screen', detail: 'Home, chat thread host, header, ChatViewModel' },
    { title: 'Integration', detail: 'merge; wire AppGraph, MainActivity, nav graph; audit routes, components, strings, lint; Compose UI tests' },
    { title: 'Verification', detail: 'Opus: setup, then eight spec groups driving every screen on the S25U against the mock Hub and the fake kernel' },
    { title: 'Polish', detail: 'Opus critics, judges, fixers and re-verify (one Fable desktop-parity critic in round 1), until two dry rounds' },
    { title: 'Report', detail: 'implementation report for the owner' },
  ],
}

// ---------------------------------------------------------------------------------------------
// Constants

const APP = '~/workspace/cline-termux-app'
const FORK = '~/workspace/cline-termux-v3-phone'
const DESKTOP = `${FORK}/apps/examples/desktop-app/webview`
const SDK_UI = `${FORK}/sdk/packages/ui`
const VOICE = '~/workspace/cline-termux-voice/android/app/src/main/kotlin/dev/cline/voice'
const SPECS = `${APP}/design/screens`
const DS = `${APP}/design/design-system.md`
const ARCH = `${APP}/docs/07-app-architecture.md`

const SPEC = {
  ks: `${SPECS}/onboarding-kernel.md`,
  hc: `${SPECS}/home-chat.md`,
  ca: `${SPECS}/composer-approvals.md`,
  ss: `${SPECS}/sessions.md`,
  sp: `${SPECS}/settings-providers.md`,
  ct: `${SPECS}/changes-tools-checkpoints.md`,
  cr: `${SPECS}/customize-and-rest.md`,
}

const WORK_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string', description: 'git branch holding the work (output of `git branch --show-current` in your worktree)' },
    worktreePath: { type: 'string' },
    headCommit: { type: 'string' },
    buildGreen: { type: 'boolean' },
    testsGreen: { type: 'boolean' },
    testCount: { type: 'integer' },
    files: { type: 'array', items: { type: 'string' }, description: 'repo-relative paths you created or changed' },
    seams: { type: 'string', description: 'exactly how later work consumes this package: public types, factories, NavGraphBuilder extensions, composables to host, AppGraph members, strings file' },
    deviations: { type: 'array', items: { type: 'string' }, description: 'every place the implementation departs from the spec, docs/07 or the design system, with the reason' },
    blockers: { type: 'array', items: { type: 'string' }, description: 'anything you could not complete, with the exact reason; empty when complete' },
    notes: { type: 'string' },
  },
  required: ['branch', 'worktreePath', 'headCommit', 'buildGreen', 'testsGreen', 'testCount', 'files', 'seams', 'deviations', 'blockers'],
}

const MERGE_SCHEMA = {
  type: 'object',
  properties: {
    baseCommit: { type: 'string', description: 'main HEAD before your first merge (git rev-parse HEAD at the start)' },
    headCommit: { type: 'string' },
    merged: { type: 'array', items: { type: 'string' } },
    conflicts: { type: 'array', items: { type: 'string' }, description: 'file + how it was resolved' },
    reconciled: { type: 'array', items: { type: 'string' }, description: 'duplicate types or seams reconciled across branches' },
    buildGreen: { type: 'boolean' },
    testsGreen: { type: 'boolean' },
    testCount: { type: 'integer' },
    notes: { type: 'string' },
  },
  required: ['baseCommit', 'headCommit', 'merged', 'conflicts', 'reconciled', 'buildGreen', 'testsGreen', 'testCount'],
}

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    group: { type: 'string' },
    passed: { type: 'array', items: { type: 'string' }, description: 'acceptance items that pass, each with the screenshot path that shows it' },
    failed: { type: 'array', items: { type: 'string' }, description: 'acceptance items still failing after your fixes, with why' },
    untestable: { type: 'array', items: { type: 'string' }, description: 'items that cannot be exercised in this setup (needs the real Termux Hub, a second device, a human, etc.)' },
    fixesCommitted: { type: 'array', items: { type: 'string' } },
    headCommit: { type: 'string' },
    screenshots: { type: 'array', items: { type: 'string' } },
    notes: { type: 'string' },
  },
  required: ['group', 'passed', 'failed', 'untestable', 'fixesCommitted', 'headCommit', 'screenshots'],
}

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          severity: { type: 'string', enum: ['blocker', 'major', 'minor', 'polish'] },
          area: { type: 'string', description: 'owning package path relative to dev/cline/termux, e.g. ui/chat, ui/settings, data/session, core/kernel, service, or "res"' },
          file: { type: 'string' },
          line: { type: 'integer' },
          screen: { type: 'string' },
          claim: { type: 'string', description: 'what is wrong, citing the DS/spec/docs-07/desktop rule it breaks' },
          fix: { type: 'string', description: 'concrete change to make' },
        },
        required: ['id', 'severity', 'area', 'file', 'claim', 'fix'],
      },
    },
  },
  required: ['findings'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    real: { type: 'boolean' },
    reason: { type: 'string' },
  },
  required: ['real', 'reason'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    acceptable: { type: 'boolean', description: 'true when every criterion passes with evidence and no finding above minor remains' },
    criteria: {
      type: 'array',
      description: 'the done contract: one entry per acceptance item of the spec sections the task names (plus build green, unit tests green, no stubs, existing tests intact), each judged pass/fail with evidence you saw yourself',
      items: {
        type: 'object',
        properties: {
          item: { type: 'string' },
          passes: { type: 'boolean' },
          evidence: { type: 'string', description: 'file:line, test name, preview name or command output that proves it; "none" when it fails' },
        },
        required: ['item', 'passes', 'evidence'],
      },
    },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          file: { type: 'string' },
          claim: { type: 'string', description: 'what is wrong, citing the DS/spec/docs-07/desktop rule' },
          fix: { type: 'string', description: 'the concrete change to make' },
        },
        required: ['severity', 'file', 'claim', 'fix'],
      },
    },
    summary: { type: 'string' },
  },
  required: ['acceptable', 'criteria', 'findings', 'summary'],
}

const FIX_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    worktreePath: { type: 'string' },
    headCommit: { type: 'string' },
    fixed: { type: 'array', items: { type: 'string' } },
    skipped: { type: 'array', items: { type: 'string' }, description: 'finding id + why it was not fixed' },
    buildGreen: { type: 'boolean' },
    testsGreen: { type: 'boolean' },
  },
  required: ['branch', 'worktreePath', 'headCommit', 'fixed', 'skipped', 'buildGreen', 'testsGreen'],
}

// ---------------------------------------------------------------------------------------------
// Shared prompt text

const COMMON = `
You are implementing part of "Cline for Android" (${APP}): a Kotlin + Jetpack Compose Material 3 app,
package dev.cline.termux, a near 1:1 mobile translation of Cline Desktop (${DESKTOP}; shared UI components in
${SDK_UI}) that talks to the Cline Hub running inside Termux on the same phone. The owner wants the complete,
polished app, aligned with the desktop in every part; nothing WIP.

READ FIRST, IN THIS ORDER. They are the contract; do not skip or skim:
1. ${ARCH} — the implementation contract: §0 ten rules, §1 package tree (your files are named there with
   existing/move/new/replace), §1.2 spec-path → architecture-path map, §1.3 AppGraph, the sections for your layer,
   §7 persistence keys, §8 threading, §9 error taxonomy, §10 testing (fakes in app/src/test/kotlin/dev/cline/termux/fakes/),
   §13 screen index, §14 cross-cutting rules, §15 decisions D1–D16 (they override spec text), §16 open questions (decided defaults).
2. ${DS} — all of it (tokens, components, layout, motion, icons, accessibility, parity rules, implementer checklist).
3. ${APP}/docs/02-kernel-contract.md and ${APP}/docs/08-hub-fixtures.md (recorded Hub traffic in
   app/src/test/resources/hub-fixtures/, used by the reducer and client tests).
4. The spec sections named in YOUR TASK, in full. Where a spec quotes desktop source paths, open those TS/TSX files
   and port behaviour, copy and formatting functions 1:1 (and copy their *.test.ts cases into JUnit tests). Where the
   design system says "voice port", the source is ${VOICE}.
5. The existing code you build on (already in the docs/07 layout): core/kernel (KernelCoordinator, hub/HubClient,
   HubFrames, HubTransport, HubDiscovery, HubSessionApi, HubTokenStore; termux/TermuxRunner, TermuxExecutor,
   TermuxCommand, ClineCommands, ProvidersFile, TermuxResultReceiver), core/secure/SecretBox, data/session
   (SessionReducer, Transcript, UserInputFormat), data/chat/ChatMessage, data/catalog/ModelCatalog,
   data/prefs/AppPreferences, service (KernelForegroundService, Notifications), ui/theme (ClineTheme, ClineColors,
   ClineMono/Type, Spacing, Motion, LocalReducedMotion), ui/icons/ClineIcons (+ res/README-material-symbols.md),
   ui/markdown/MarkdownText, ui/Snackbar.kt (LocalSnackbarHost, showReplacing), ui/debug/KernelProbe*; and the merged Phase 0
   foundation-ui package: data/prefs (AppPreferences with every docs/07 §7 key, JsonFileCache, SessionListCache, CatalogCache),
   data/session/SessionVisual.kt (the one DS §1.6 status mapping), ui/components/* (PageScaffold, TooltipIconButton, ConfirmDialog,
   AdaptiveDialog, ClineSheet, EmptyState, InlineErrorCard, DashedNotice, HelperUnavailableCard, Skeleton, Badges, AnchoredContextMenu,
   FocusRing, LtrText, SectionHeader, KeyValueRow, LiveRegionAnnouncer, ScrollToLatestButton, HeroHeading, SessionStatusDot,
   ExternalLinks, CopyToClipboard, Insets, Previews), res/values/strings_components.xml, test/fakes/FakeClock.kt.
   Spec file names (chat/…, sessions/…, settings/…) map onto this layout through docs/07 §1.2; the docs/07 path wins.

HOUSE RULES (violations fail review):
- Material 3 only (androidx.compose.material3 1.4 from the BOM, material3-adaptive 1.3, window-size-class). No M2,
  no AppCompat views, no material-icons-extended. Icons come from ClineIcons; a missing Material Symbol is added the
  way app/src/main/res/README-material-symbols.md documents (Rounded 24px vector as ms_<name>.xml) and exposed in ClineIcons.
- Every user-visible string is a resource in a file YOU own: app/src/main/res/values/strings_<yourpackage>.xml
  (never edit strings.xml or another package's file). Desktop copy verbatim, including "..." versus "…"; strings
  marked (Android) in the specs are new. Counts use <plurals>. Positional format args, never concatenation.
  Copy ids use the prefixes of docs/07 §13.
- No \`Color(0x\` outside ui/theme. Use MaterialTheme.colorScheme roles, ClineTheme.colors for status/diff/terminal,
  ClineMono for code, commands, ids and numbers, MaterialTheme.typography for everything else, Spacing tokens for gaps.
  Status colours only through SessionVisual / SessionStatusDot (DS §1.6).
- Accessibility per DS §8: contentDescription on every icon-only control (the desktop aria-label), 48dp targets,
  stateDescription on disclosures, custom actions for long-press menus, no colour-only signals, live regions where specified.
- @Preview functions for every component state the DS/spec lists, light and dark, via ClinePreviewTheme.
- Unit tests (JUnit 4, kotlinx-coroutines-test, turbine; Robolectric where Android classes are unavoidable) for every
  pure function, reducer, repository and ViewModel, using the fakes in test/fakes; fixture replay where docs/08 provides
  traffic; ported desktop tests keep the desktop's cases. In TestScope, launch long-lived collectors with
  backgroundScope or cancel them at the end.
- Secrets (docs/07 §3.7): never persisted, logged or placed in UiState; TermuxCommand.toString() redacts.
- Ownership: edit only the files your task names (plus new files under the packages it names and your strings file).
  AppGraph.kt, ClineApp.kt, MainActivity.kt and build.gradle.kts are off limits unless your task says otherwise; expose
  your work through the seams docs/07 defines (AppGraph members are added by the foundation phases and the integration
  agent; screens expose \`NavGraphBuilder.<feature>Graph(nav, graph)\` extensions and sheet composables). A missing
  Gradle dependency is a blocker to report, not an edit.
- Build and test IN YOUR WORKTREE (you run in a git worktree on your own branch; \`pwd\` and
  \`git branch --show-current\` tell you where): \`bash scripts/build.sh :app:assembleDebug\` and
  \`bash scripts/build.sh :app:testDebugUnitTest\` must both pass before you finish. Fix every compiler error and test
  failure yourself. Do not install on the phone. Do not push. No \`git worktree\`, no checkout of other branches, nothing on main.
- Commit on your branch when green (conventional commits; message body ends with
  "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"). Finish with a clean \`git status\`.
- Tooling: a \`dcg\` hook screens Bash — write files with Write/Edit, no heredocs piped into shells, no \`rm -rf\`
  outside /tmp, quote globs (zsh). The proxy is in the environment and scripts/build.sh forwards it. Foreground
  \`sleep\` is blocked. Builds take 1–4 minutes; run them in the foreground with a long timeout. Other agents build in
  parallel on this machine, so keep Gradle invocations to the two tasks above.
- The deliverable is COMPLETE: every screen, state and flow in your sections, nothing stubbed, no TODO/FIXME,
  no placeholder copy, no "coming soon". If something is genuinely impossible (missing kernel capability, missing
  dependency), implement everything else fully, surface the gap in the UI only where the spec says so, and report
  it under \`blockers\` with the exact reason.
`

// ---------------------------------------------------------------------------------------------
// Work packages (docs/07 §11 build order, re-cut for parallel worktrees)

const PHASE0 = [
  {
    key: 'hub-core',
    task: `The Hub side of docs/07 Phase 0. Files (docs/07 §1.1, §3.1, §3.2, KS §0.3–0.4):
- BuildInfo.kt (VERSION_NAME/CODE from BuildConfig, MIN_PORT_RELEASE = "3.0.68-termux.2", MIN_PORT_RELEASE_RESTORE: String? = null).
- core/kernel/hub: HubClient.kt replace (global cursor, replay budget 2000 = D2, close ordering, per-command timeouts, sequence
  dedupe, events as a SharedFlow, hub.client.* events), HubCursorStore.kt new (one global cursor in DataStore "sessions" per §7.3;
  delete the per-session cursor code), HubErrors.kt new, HubSessionApi.kt replace (NewSessionSpec, every HubPayloads builder incl.
  restore/fork/steer/attachments/update_connection/pending prompts/schedule.*/team, checkpoint.enabled = true always = D4).
- core/kernel: KernelState.kt, KernelGate.kt (+ KernelState.toGate(), KS F.6), KernelTransitions.kt, KernelCoordinator.kt replace
  (KS §0.3 state model, §0.4 connect sequence, auto-start, passive probe, token rotation, disconnect(), restart phases of KS E.7).
  The coordinator needs Termux commands that another agent is rewriting in parallel: declare the subset you need as an interface
  core/kernel/KernelCommands.kt (readPortVersion, hubStart, hubStatus, hubStop, hubUpgrade, readDiscovery, tailDaemonLog, doctorJson,
  wakeLock/unlock, …) with a FakeKernelCommands in test/fakes, and code against it; the merge step makes ClineCommands implement it.
- Developer Hub override (debug builds only; scripts/dev-hub.sh reverse already sends it):
  app/src/debug/kotlin/dev/cline/termux/debug/HubTokenOverrideReceiver.kt + app/src/debug/AndroidManifest.xml registering it
  exported="true" with android:permission="android.permission.DUMP" (held by the adb shell user, not grantable to apps) for action
  dev.cline.termux.debug.SET_HUB_TOKEN: --es token <t> stores it (DataStore "debug"), --ez clear true removes it. Seam for main code:
  object dev.cline.termux.debug.KernelOverrides with identical signatures in app/src/debug/… and app/src/release/… (release = no-ops,
  hubToken always null); KernelCoordinator consults KernelOverrides.hubToken(context) first. Document the exact adb commands in a new
  "Developer Hub override" section of docs/06-toolchain.md.
- test/fakes: FakeHubTransport (scripted frames + assertion of sent frames), FakeClock, InMemoryCursorStore, FakeKernelCommands.
- Update AppGraph.kt members you own; keep ui/debug/KernelProbe* compiling against the new API (do not move it); keep
  HubClientTest/HubFramesTest/HubPayloadsTest/KernelCoordinatorTest green and extend them (fixtures 01, 04, 05 for replay and cursor rules).
STARTING POINT: two earlier attempts at this package were interrupted. The newer one is committed on branch worktree-wf_5ea87a04-7c7-1
(commit 36acf8d; its hub package — HubClient, HubFrames edits, HubTransport cancel, HubErrors, HubCursorStore, HubProtocol, HubSessionApi —
was reported complete, the kernel package — KernelState, KernelGate, KernelTransitions, KernelCommands, PortRelease, HubSessionActivity,
KernelClock, KernelPreferences, KernelPorts, TermuxKernelCommands, KernelCoordinator, BuildInfo — was in progress; the debug override,
fakes, tests and the build/test run were not done). The older one is branch worktree-wf_5c8b4559-064-1 (commit b90ee6b). List them with
\`git show --stat 36acf8d\` / \`git show --stat b90ee6b\`, read any file with \`git show <commit>:<path>\`, and recreate what is useful in your
worktree with the Write tool (never overwrite main's files from those branches wholesale), then review every file against the spec as if
you had written it; main has moved on since they forked (data/prefs/AppPreferences.kt holds every §7 key incl. sessions.globalCursor and a
HubCursorStore adapter, ui/components and data/session/SessionVisual.kt exist, test/fakes/FakeClock.kt exists) — build on main's
versions. strings_kernel.xml belongs to the later kernel-ui package; do not add it.
Specs: ${SPEC.ks} §0 (all) and §E; ${SPEC.hc} §1.1–1.2; ${SPEC.ca} §0.3; docs/02; docs/08.
Own: BuildInfo.kt, core/kernel/** except termux/ and dashboard/, app/src/debug/** and app/src/release/** (debug package only),
docs/06-toolchain.md (one section), ui/debug/** (compile fixes only), AppGraph.kt (your members), tests + fakes listed.`,
  },
  {
    key: 'termux-core',
    task: `The Termux and dashboard side of docs/07 Phase 0 (docs/07 §3.3–3.7, SP §0.3, SP §0.6):
- core/kernel/termux: TermuxCommand.kt replace (toString() redacts --apikey, --room-secret, stdin; label-only logging), TermuxExecutor.kt
  replace (run + runDetached, ANSI strip on every stdout/stderr, timeout table, 100 KB result cap handling), TermuxRunner.kt (detached mode,
  foreground start fallback, result routing), ClineCommands.kt replace with the FULL catalog of docs/07 §3.4 (every RUN_COMMAND the screen
  index §13 quotes: cat VERSION, cline hub start/status/stop/upgrade, termux-wake-lock/unlock, cat production.json, tail hub-daemon.log,
  cline doctor --json, cline auth …, readProviderPresence(), readGlobalSettings, stat providers.json, realpath/find workspace commands,
  git branch/for-each-ref/checkout/rev-parse, cline config tools/hooks --json, cline mcp install/uninstall, pkill … auth, file search pipeline,
  writeAttachment chunked stdin, tail -c 49152 logs, readlink current, cline --version), ConfiguredProviders.kt new (redacted projection
  of providers.json; delete ProvidersFile.kt and migrate its callers), AnsiStrip.kt.
- core/kernel/dashboard: SettingsHelper.kt (acquire/release lease, room secret in memory only, HelperState, BuildUnsupported persistence,
  /health polling, 30 s release grace), HelperFrames.kt (desktopCommand / desktopCommandResult, lenient decoder), DashboardClient.kt
  (OkHttp ws to /browser, request correlation, 30 s / 60 s / 300 s timeouts) — tests with mockwebserver3 and a FakeDashboardClient.
- core/secure/SecureClipboard.kt (EXTRA_IS_SENSITIVE, 60 s clear).
- test/fakes: FakeTermuxExecutor (label → canned TermuxResult, latency, timeouts), FakeDashboardClient.
- Update AppGraph.kt members you own; keep ui/debug/KernelProbe* compiling (compile fixes only).
STARTING POINT: a previous attempt at this package was interrupted; its partial, unbuilt, unverified work is committed on branch
worktree-wf_5c8b4559-064-2 (commit 39ab857; files: core/kernel/termux/{ClineCommands,TermuxCommand,TermuxExecutor,TermuxRunner,
TermuxResultReceiver,AnsiStrip,CommandOutputs,ConfiguredProviders}.kt, core/kernel/dashboard/*, core/secure/SecureClipboard.kt,
test/.../fakes/*, TestSupport.kt, tests). List them with \`git show --stat 39ab857\`, read any with \`git show 39ab857:<path>\`, and
recreate what is useful in your worktree with the Write tool, then review every file against the spec as if you had written it; main has
moved on since that branch forked (data/prefs/AppPreferences.kt was replaced with every §7 key, test/fakes/FakeClock.kt exists) — do NOT
take the branch's AppPreferences.kt, KernelCoordinator.kt or AppGraph.kt; re-apply only what you need onto main's versions.
Specs: ${SPEC.sp} §0.3–0.7, §13; ${SPEC.ks} §0.5–0.7; ${SPEC.ca} C6.6, C1.9 (the command shapes); ${SPEC.ct} §1, A.7; ${SPEC.cr} §1.8–§9.4 command
tables; docs/02 "On-demand settings helper"; docs/05.
Own: core/kernel/termux/**, core/kernel/dashboard/**, core/secure/SecureClipboard.kt, AppGraph.kt (your members), ui/debug/** (compile fixes
only), tests + fakes listed. Do not touch HubClient/KernelCoordinator (another agent owns them).`,
  },
]

const PHASE1 = [
  {
    key: 'kernel-ui',
    task: `Kernel-typed shared UI and the debug fake kernel (KS §0, §0.9, F; CA §0.3; DS §4.2):
- ui/components/KernelBanner.kt, KernelStatePanel.kt, KernelChip.kt (opens the status sheet through a callback), KernelBlockedCard.kt
  (SP §0.5), mapped from KernelState / KernelGate exactly as KS §0 and F.2 copy tables say; ui/kernel/HubUpdateRequiredDialog.kt (KS E.9);
  ui/kernel/KernelDiagnostics.kt (the diagnostics projection the status sheet, repair screen and About share); pure gate helpers for the
  composer (CA §0.3) in ui/kernel/ComposerGate.kt.
- The debug fake kernel of ${SPEC.ks} §0.9 in app/src/debug/kotlin/dev/cline/termux/debug/fake/: every scenario listed there, implemented
  as scripted TermuxExecutor / KernelCommands / HubProber replacements selected by the intent extra dev.cline.termux.FAKE_KERNEL
  (+ RESET_ONBOARDING); extend the existing dual-source-set object dev.cline.termux.debug.KernelOverrides additively (debug + release
  copies keep identical signatures): applyLaunchIntent(intent), scenario state, and the hook AppGraph/KernelCoordinator consult when
  constructing the executor/prober. Add the single applyLaunchIntent call at the top of MainActivity.onCreate (no other MainActivity edits).
  Release builds compile none of the fake.
- res/values/strings_kernel.xml (KS §0 and F copy, SP §0.5). Unit tests for the state → copy mapping and each fake scenario's timeline.
Specs: ${SPEC.ks} §0 (all), §0.9, §E.7–E.9, §F; ${SPEC.ca} §0.3; ${SPEC.sp} §0.5; DS §1.6, §4.2; docs/07 §3.2, §9.
Own: ui/components/Kernel*.kt, ui/kernel/**, app/src/debug/** (fake package + KernelOverrides additions), app/src/release/** (same
additions), MainActivity.kt (that one line), strings_kernel.xml, tests.`,
  },
  {
    key: 'session-core',
    task: `docs/07 Phase 1, the reducer core (docs/07 §4.1–4.2, HC §1):
- data/session: SessionFacts.kt (status, run phase, approvals, questions, usage, tool records, checkpoint history, pending prompts),
  SessionFactsReducer.kt (replace SessionReducer.kt; pure; every row of §4.2 "facts"), ChatProjector.kt (events → live ChatMessage list;
  HC §1.3.1, every row), HistoryMapper.kt (session.messages → canonical; HC §1.3.2), Reconciler.kt (canonical ∪ live by renderKey;
  HC §1.3.3), TurnRecord.kt (+ persistence of open turns in DataStore chat.turns through AppPreferences; HC §1.3.4), InterruptionDetector.kt
  (CA §0.2, HC §1.3.4), SessionStore.kt + SessionState.kt (app-scoped; dispatch, subscribe, seed, one global subscription, per-session
  subscribes without sinceSequence, session_not_found auto-resume, liveness; HC §3.6, §3.12), delete Transcript.kt (fold its types into
  SessionFacts) and keep SessionReducerFixtureTest's coverage (rename/extend it; fixtures 02–05 replay green).
- data/chat: ChatGrouping.kt (groupChatMessages, collapseCompletedWork, isSystemSteeringMessage, buildUserRunCountMap,
  getThoughtDurationMilliseconds, ChatRenderItem — ports of ${DESKTOP}/components/views/chat/messages/group-messages.ts with its .test.ts),
  RunErrors.kt (formatRunError, isCredentialFailure, resolveCredentialFailureHint/Action from ${DESKTOP}/lib/run-error.ts and
  hooks/chat-session/helpers.ts), ProviderAuthResolver.kt (HC §1.3.5; depends on a tiny interface for catalog lookups you declare),
  SessionCreateBuilder.kt (HC §1.3.6, CT §1.5.2), ChatStatus derivation (HC §1.4), RunPhase (CA §0.2).
- AppGraph.kt: add sessionStore (and nothing else).
Specs: ${SPEC.hc} §1 (all), §3.5–3.8, §3.11–3.13, §7; ${SPEC.ca} §0.2, §0.5; ${SPEC.ct} §0.2; docs/08.
Own: data/session/** (except SessionVisual.kt), data/chat/** (except ChatMessage.kt, which you may extend additively), AppGraph.kt (that
member), tests.`,
  },
  {
    key: 'terminal-diff',
    task: `Terminal and diff, data and UI (CT §1.2, A.4, A.7, B.4; DS §1.5, §1.7):
- data/tools/TerminalBuffer.kt (lines, \\r handling, head caps 48 000 inline / 1 048 576 detail, "[output truncated]" marker, 50 ms
  conflation helper), data/diff/SessionDiff.kt, LineDiff.kt, UnifiedDiffText.kt (pure ports of ${DESKTOP}/lib/session-diff.ts and the
  diff helpers the spec cites, with their tests).
- ui/terminal/AnsiParser.kt (SGR → AnnotatedString with ClineTheme.colors.ansi: 16 colours + 256 + truecolour, bold maps 0–7 to 8–15,
  underline/inverse; tests), ui/terminal/TerminalView.kt (terminal colours, 256dp inline cap, auto-scroll pin/release, blinking cursor
  while running with reduced motion, horizontal scroll, LTR, selection, copy), ui/diff/DiffHunkView.kt (gutter numbers ClineMono.tiny,
  +/- markers, diffAddedLine/diffRemovedLine, horizontal scroll, LTR, expand collapsed context) and ChangedFileHeader.kt (path with
  basename emphasis, +N −N in ClineMono.small).
- res/values/strings_terminal_diff.xml. Previews light/dark, long lines, CJK output.
Specs: ${SPEC.ct} §1.2, A.4, A.7, B.4 and the DS sections above; desktop ${SDK_UI}/components/agent-command-output.tsx, tool-diff.tsx,
agent-changes.tsx.
Own: data/tools/TerminalBuffer.kt, data/diff/**, ui/terminal/**, ui/diff/**, strings_terminal_diff.xml, tests.`,
  },
  {
    key: 'chat-ports',
    task: `Pure desktop ports every lane needs (all pure Kotlin, all with the desktop's test cases):
- data/tools/ToolPresentation.kt: buildToolSummary + label parts + pluralize + team/skill/mcp/question summaries
  (${SDK_UI}/components/agent-chat/tool-summary/*.ts), buildToolPresentation, parseToolPayload, extractRunCommandOutput
  (${DESKTOP}/components/views/chat/messages/tool-summaries.ts), ToolActivity trigger rules; data/chat/WorkLabels.kt (formatThoughtLabel,
  formatWorkDuration, formatWorkActivityLabel from ${SDK_UI}/components/agent-chat/index.tsx); data/chat/ContextUsageCalculator.kt (CA C1.12).
- data/agents/SessionAgents.kt (${DESKTOP}/lib/session-agents.ts: buildSessionAgentActivity, describeAgentActivity, agentEntryState).
- data/sessions/SessionSummary.kt, SessionFormat.kt (normalizeTitle, basenamePath, workspaceLabel, modelLabel, formatCompactTokens,
  formatTokenCount, formatCostUsd, compactAge/formatRelativeTime, scheduleRunLabel, getSessionSourceLabel, uniqueWorkspaceLabel),
  SessionGrouping.kt (groupThreadsByProject, groupScheduledThreads, scheduleGroupKey) — SS §0 with sidebar-session-organization.test.ts,
  sessions-view.test.tsx, use-session-history.test.tsx cases.
- data/schedules/ScheduleFormat.kt + RoutineTemplates.kt (CR §0.7, §4.5); data/marketplace/MarketplaceTypeMeta.kt (TYPE_META, MATURITY_ORDER);
  data/workspace/WorkspacePaths.kt (${DESKTOP}/lib/workspace-paths.ts + tests); data/model/ModelPickerData.kt (buildModelPickerData from
  ${DESKTOP}/lib/model-selection.ts + components/views/chat/featured-models.ts); data/checkpoint/CheckpointIndex.kt (CT C.4: run spans,
  exact / at-or-before lookup, trim; pure, cached per (lastSequence, canonicalCount)); data/about/Changelog.kt (CHANGELOG parsing, CR §7–8 pure parts).
Specs: ${SPEC.hc} §1.5, §3.4.x pure rules; ${SPEC.ss} §0; ${SPEC.ct} §1, C.4; ${SPEC.sp} S8.2, S9; ${SPEC.cr} §0.7, §3, §4.5, §6, §7.
Own: data/tools/ToolPresentation.kt, data/chat/WorkLabels.kt, data/chat/ContextUsageCalculator.kt, data/agents/SessionAgents.kt,
data/sessions/{SessionSummary,SessionFormat,SessionGrouping}.kt, data/schedules/{ScheduleFormat,RoutineTemplates}.kt,
data/marketplace/MarketplaceTypeMeta.kt, data/workspace/WorkspacePaths.kt, data/model/ModelPickerData.kt, data/checkpoint/CheckpointIndex.kt,
data/about/Changelog.kt, tests. No composables, no Android dependencies.`,
  },
]

const PHASE2 = [
  {
    key: 'shell-nav',
    task: `docs/07 Phase 2 — shell and navigation (docs/07 §6.1–6.4, DS §5, §5.8, §6):
- ui/navigation/Routes.kt: @Serializable routes for EVERY row of docs/07 §6.2 with their arguments; DeepLinks.kt (SESSION_ID / OPEN_SHEET
  extras → route, CA §0.6); BackOrder.kt (the §6.3 precedence helpers, no always-on BackHandler); RootNavHost.kt (onboarding graph |
  shell, start destination after one DataStore read, splash keep-on condition); ShellNavHost.kt (chat root + top-level + pushed routes,
  editors as composable() or dialog() by window class, the list-detail and supporting-pane placements of DS §5.2) with a PLACEHOLDER
  destination per route (a PageScaffold with the route name) that the integration agent replaces with the real screens; Transitions.kt
  (fade-through top-level, shared-axis X pushes, shared-axis Y editors, all pops seekable), WindowClass.kt (LocalWindowSizeClass,
  isHeightCompact), Insets.kt (bottomSafe).
- ui/AppShell.kt + AppShellViewModel.kt: compact drawer / medium rail + modal drawer / expanded rail + NavigableListDetailPaneScaffold,
  height-compact rules, KernelBanner host, kernelGate flow, drawer state, pendingDeepLink queue, What's-new trigger slot; slots for the
  sessions drawer content, rail items and kernel chip (features plug in).
- MainActivity.kt replace per docs/07 §1.1 and §6.4 (keep installSplashScreen with the keep-on condition, enableEdgeToEdge +
  SystemBarIcons, the KernelOverrides.applyLaunchIntent line, intent routing in onCreate/onNewIntent, FLAG_SECURE owner hook,
  LocalSnackbarHost at the root, hardware key hooks Ctrl+K / Ctrl+P); move ui/debug/KernelProbe* to app/src/debug and expose it through
  a dual-source-set dev.cline.termux.debug.DebugScreens object (release = empty) so About can link it later.
- res/values/strings_shell.xml. Tests for DeepLinks, BackOrder, route (de)serialisation, AppShellViewModel.
Specs: DS §5 (all), §5.8, §6; ${SPEC.ss} §2 (rail/drawer slot shapes); ${SPEC.ca} §0.6; ${SPEC.ks} §0.2, §1; docs/07 §6.
Own: ui/navigation/**, ui/AppShell*.kt, MainActivity.kt, ui/debug/** (the move), app/src/debug/**/DebugScreens.kt + release twin,
strings_shell.xml, tests.`,
  },
  {
    key: 'transcript-rows',
    task: `Transcript rows (HC §3.4 every subsection, §3.9–3.10; DS §1.7, §2, §4.1 #2–#5, §4.2, §6, §8), rendered from ChatRenderItem /
ChatMessage / ToolPresentation / TerminalBuffer / diff types, in ui/chat/rows/: ToolCallRow (disclosure, 20dp icon slot with a 16dp
spinner while running, label with ClineMono.label code parts, +N −N trailing, no chevron, auto-open rule and user-toggle-wins, body
details / "Output" overline with TerminalView / "Input" overline (debug) / "Proceed while running" / errors, tap-through to tool detail
callback), ReasoningBlock (neurology icon, "Thinking" shimmer → "Thought for Ns", 192dp capped scrollable markdown, redacted),
WorkBlock (rotating expand_more, "Worked for … and made N tool calls"), RunGroup, MessageBubble (user bubble surfaceContainerHigh
shape large, 85 % / 60 % max width, images; assistant = MarkdownText full width; error surface with credential action; status rows),
SessionNoticeRow, ToolFileDiff (DiffHunkView + ChangedFileHeader), ImageThumbnails (160dp carousel + lightbox callback), MessageActions
(copy / edit / restore / fork with pending and error states, copied check for 2 s), ShimmerText brush helper (DS §6). Every row sets the
DS §8 semantics. Desktop sources: ${DESKTOP}/components/views/chat/messages/*.tsx, ${SDK_UI}/components/agent-chat/index.tsx,
agent-chat.css. res/values/strings_transcript.xml. Previews for every row state, light/dark, CJK.
Own: ui/chat/rows/**, strings_transcript.xml, tests (pure row rules).`,
  },
  {
    key: 'derived-stores',
    task: `Derived per-session stores (docs/07 §4.3, CT §1.3–1.6, C.6):
- data/tools/ToolCallStore.kt (keyed by (sessionId, toolCallId); one TerminalBuffer per execution; seeds from canonical messages; appends on
  tool.started/updated/finished and run.* events from SessionStore / HubClient events; publishes with conflate() every 50 ms; Registry
  as in AppGraph §1.3).
- data/diff/SessionDiffStore.kt (per session; seeds from canonical tool pairs, appends on tool.finished, ignores seen toolCallIds;
  StateFlow<DiffStoreState>; recompute on Default; Registry).
- data/checkpoint/CheckpointActions.kt (revert / edit-and-restart orchestration over HubSessionApi: session.get includeSnapshot,
  session.restore when BuildInfo.MIN_PORT_RELEASE_RESTORE allows, else the split workspace-only + session.create path; session.detach;
  lost-reply recovery through session.list; git rev-parse check through ClineCommands; CT C.6, §1.6).
- AppGraph.kt: diffStores, toolStores members.
Fixture-backed tests (02, 03, 04) with the fakes. Specs: ${SPEC.ct} §1 (all), C.4–C.6; docs/07 §4.3, §8.
Own: data/tools/ToolCallStore.kt, data/diff/SessionDiffStore.kt, data/checkpoint/CheckpointActions.kt, AppGraph.kt (those members), tests.`,
  },
  {
    key: 'services',
    task: `Service layer and notification data (docs/07 §4.7, §5; SP S3; CR §11, §12; CA §0.6–0.7):
- data/notifications/NotificationPrefs.kt (notifications.v1 JSON with the desktop readPreference fallback), KeepAlivePolicy.kt
  (StateFlow<HoldState> from SessionStore facts restricted to KnownSessions, prefs.keepConnected, the gate and ProcessLifecycleOwner;
  10 s release debounce), data/sessions/KnownSessions.kt (DataStore LRU 500).
- service/NotificationChannels.kt (replace Notifications.kt: eight session channels sounding + _quiet, account, kernel; migration deletes
  sessions and attention), ClineNotifier.kt (SP S3.3 posting contract + CR §11.2–11.5 anatomy, tags/ids of docs/07 §5, background-only,
  KnownSessions only, 2-minute staleness on replay, dedupe LRU 500 + notifications.seenRequestIds, cancel rules), NotificationRouter.kt
  (PendingIntents with SESSION_ID / OPEN_SHEET extras, request code = tag hash), KernelServiceActions.kt (exported=false receiver:
  STOP_AGENT → runAbort, DISCONNECT → kernel.disconnect(); add it to AndroidManifest.xml), KernelForegroundService.kt replace (variants
  CR §12.3, chronometer from run.started, actions, ForegroundServiceStartNotAllowedException → KeepAlive.Denied, 10 s stop debounce,
  started only from the foreground), ShareFiles.kt (FileProvider helpers for cache/share, cache/diagnostics, cache/camera; 24 h sweep;
  authority dev.cline.termux.files is already in the manifest).
- ClineApp.kt: NotificationChannels.ensure, pending launcher-alias disable, the 24 h sweep (the only eager work). AppGraph.kt:
  knownSessions, notificationPrefs, keepAlive, notifier members.
- res/values/strings_notifications.xml (CR §11–12 and SP S3 copy; kernel_notification_* ids move here from strings.xml — remove them there).
  Robolectric tests for the notifier matrix (CR §11.3) and the service variants; turbine tests for KeepAlivePolicy.
Specs: ${SPEC.sp} S3; ${SPEC.cr} §11, §12; ${SPEC.ca} §0.6, §0.7; docs/07 §4.7, §5.
Own: data/notifications/**, data/sessions/KnownSessions.kt, service/**, ClineApp.kt, AppGraph.kt (those members), AndroidManifest.xml
(receiver entry), strings.xml (removal of the two ids only), strings_notifications.xml, tests.`,
  },
  {
    key: 'sessions-data',
    task: `${SPEC.ss} §1 in full (docs/07 §4.4): data/sessions/SessionsRepository.kt (SessionSummary list + SessionFlags, KernelSessionStatus,
unread counts, PendingAction map, SessionListCache use, refresh policy: after connect, resume > 10 s, drawer open, pull, unknown-session
event, after fork/delete, 2 s initial-load retry; schedule links with allWorkspaces:true; flags from SessionStore facts when loaded,
record otherwise; interrupted via inFlightTurns + persisted suspectInterrupted; delete verified by session.get), SessionActions.kt
(F1–F5 rename / pin / fork / delete / resume with one pending action at a time, metadata spread on update), replay budget handling
(SS §1.2.1) and the live-row guard. AppGraph.kt: sessionsRepository. Fixture-backed tests (02, 05) + turbine.
Specs: ${SPEC.ss} §1 (all), §5 F1–F5 data parts, §1.8; ${SPEC.cr} §4 (schedule links); docs/07 §4.4.
Own: data/sessions/SessionsRepository.kt, SessionActions.kt (+ small data classes), AppGraph.kt (that member), tests.`,
  },
  {
    key: 'providers-data',
    task: `Providers, models, workspaces, sign-in data (docs/07 §4.5; SP §0.4, §0.7, S7, S8.3–8.4, S9.3):
- data/providers/ProviderCatalogRepository.kt (SP §0.7 verbatim: 60 s TTL, generation counter, silent resync, retry, non-secret
  projection to CatalogCache, invalidate(), configured(id), Flow<CatalogState(source, generation, providers, modelsByProvider)>),
  ProviderModels.kt (ProviderListItem, ProviderModel, ConfigField, isChatModel, featuredBadges).
- data/catalog/FallbackProviders.kt + res/raw/fallback_providers.json generated from the fork's provider registry (find it under
  ${FORK}/sdk/packages and ${FORK}/apps/cli; commit the generator as tools/catalog/export-fallback-providers.ts beside export-catalog.ts)
  with FALLBACK_PROVIDER_MODELS ported from the desktop.
- data/model/ModelSelectionStore.kt (model.selection / model.reasoning, written only on explicit picks), data/workspace/WorkspaceStore.kt
  (workspace.active, recents MRU 20, validate(path) via realpath -e + find -maxdepth 0 -type d, recentsFromSessions).
- data/account/ClineSignInCoordinator.kt (SP S7: cline auth <id> in the foreground + TermuxActivity launch, pkill … auth,
  readProviderPresence polling, helper save_provider_settings + cline_account fetchMe when Ready, SignInState; waiting survives dismiss).
- The catalog interface ProviderAuthResolver (data/chat) expects: implement/adapt it here.
- AppGraph.kt: providerCatalog, modelSelection, workspaceStore, signIn members.
Specs: ${SPEC.sp} §0 (all), S7, S8.2–8.4, S9.3, §13; ${SPEC.hc} §1.3.5; ${SPEC.ks} C (data parts); docs/07 §4.5.
Own: data/providers/**, data/catalog/FallbackProviders.kt, res/raw/fallback_providers.json, tools/catalog/export-fallback-providers.ts,
data/model/ModelSelectionStore.kt, data/workspace/WorkspaceStore.kt, data/account/ClineSignInCoordinator.kt, AppGraph.kt (those members), tests.`,
  },
  {
    key: 'tier4-data',
    task: `Tier-4 repositories (docs/07 §4.6; CR §1.8, §2.8, §3.8, §4.8, §5.8, §6.7, §7, §8, §9.4, §9.6):
data/customize/CustomizeRepository.kt (settings.list / settings.toggle + cline config tools/hooks --json + cat global-settings.json;
60 s cache keyed by workspace; settings.changed event) and McpRepository.kt (helper list/upsert/delete + file fallback + CLI route);
data/marketplace/MarketplaceRepository.kt (catalog fetch with 10 min memory + 4 MB OkHttp disk cache, installed keys, install/uninstall
routes helper-first then CLI, inventoryChanged shared flow with customize); data/schedules/SchedulesRepository.kt (schedule.* with
allWorkspaces:true, refresh on events / resume / 30 s while active); data/account/AccountRepository.kt (cline_account operations through
the helper, sign-out via save_provider_settings); data/agents/AgentsRepository.kt (session.list rootOnly:false, session.messages per child,
team.progress); data/about/WhatsNewContent.kt + WhatsNewRepository.kt (CR §8); data/diagnostics/DiagnosticsBuilder.kt + Redactor.kt
(CR §9.4, §9.6: session.list, session.get, cline doctor --json, tail -c 49152 of two logs, /health, /version, redaction rules).
Route selection by HelperState everywhere (helper when Ready, CLI/file fallback otherwise). AppGraph.kt: customize, mcp, marketplace,
schedules, account, agents, whatsNew, diagnostics members. Tests with FakeDashboardClient / FakeTermuxExecutor / FakeHubTransport and
mockwebserver3 for the marketplace catalog.
Specs: ${SPEC.cr} §1–§9 (data sections), §0.7; docs/07 §4.6.
Own: data/customize/**, data/marketplace/MarketplaceRepository.kt, data/schedules/SchedulesRepository.kt, data/account/AccountRepository.kt,
data/agents/AgentsRepository.kt, data/about/WhatsNew*.kt, data/diagnostics/**, AppGraph.kt (those members), tests.`,
  },
]

const PHASE3A = [
  {
    key: 'onboarding',
    task: `${SPEC.ks} screens A (Welcome + Kernel setup with KernelSetupViewModel / KernelSetupUiState, also hosted as kernel/repair with
focus= and pushed=), B (Install Termux + InstallTermuxViewModel), C (Connect a model + ConnectModelViewModel / ConnectUiState, the
"Use a Cline API key" path, AlreadyConfigured) and the Setup done step; the first-run flow §1 and §0.2 routing. Package ui/onboarding
(+ ui/kernel/KernelRepairScreen.kt). Expose NavGraphBuilder.onboardingGraph(nav, graph) covering the routes docs/07 §6.2 lists for
onboarding/* and kernel/install-termux, kernel/repair, setup/connect, setup/done. Previews for every state; VM tests against the fakes and
the fake-kernel scenario timelines.
Own: ui/onboarding/**, ui/kernel/KernelRepairScreen.kt, strings_onboarding.xml, tests.`,
  },
  {
    key: 'kernel-status',
    task: `${SPEC.ks} screens D (Reliability / One UI battery checklist + ReliabilityViewModel, routes onboarding/reliability and
kernel/reliability), E (Kernel status sheet + KernelStatusViewModel / KernelStatusUiState: every KernelState, other clients, restart phases
E.7, mismatch E.9, wake-lock, hub stop/upgrade) and F (Termux asleep / Hub down state and the recovery matrix §8), plus the "kernel"
settings route (SP S1 row → the status content as a pushed screen). Package ui/kernel. Expose KernelStatusSheet(open, onDismiss),
NavGraphBuilder.kernelGraph(nav, graph). Previews; VM tests with the fake-kernel scenarios.
Own: ui/kernel/** (except HubUpdateRequiredDialog, KernelDiagnostics, ComposerGate, KernelRepairScreen), strings_kernel_status.xml, tests.`,
  },
  {
    key: 'composer',
    task: `${SPEC.ca} §0 (all shared rules), C1 Composer (field, settings row with responsive overflow, Plan/Act, model chip, reasoning chip,
usage ring + context usage sheet, send/stop morph, attachments pipeline + attach sheet incl. camera through ShareFiles, hardware keyboard,
IME rules of DS §5.7, read-only / kernel-down states through ComposerGate), C7 Voice input (VoiceInputController over SpeechRecognizer,
SpeechAvailability, RECORD_AUDIO flow) and C8 Welcome setup notice. Package ui/composer; pure parts in data/chat: SendPipeline.kt
(C1.7 + C5.7: slash resolution, attachments, delivery mode, failure table), Attachments.kt (C1.9: image pipeline with EXIF orientation,
text files as chunked stdin writes), ActiveToken (tests copied from chat-input-bar.test.tsx). ComposerController + ComposerUiState are
plain classes owned by the ChatViewModel (built later): expose a factory taking (sessionStore, sessionApi, modelSelection, workspaceStore,
gate flow, scope). Model chip / workspace chip open sheets through callbacks (the sheets are another lane's).
Own: ui/composer/**, data/chat/SendPipeline.kt, data/chat/Attachments.kt, data/chat/ActiveToken.kt, strings_composer.xml, tests.`,
  },
  {
    key: 'queue-suggestions',
    task: `${SPEC.ca} C2 (Prompt queue strip + sheet + PromptQueueController / PromptQueueState over session.pending_prompts and its
events), C5 (Slash menu: /fork, /team, skills, workflows, plugin commands; data/chat/SlashCommandSource.kt over settings.list + built-ins,
cache per workspace; fork through session.messages + session.create), C6 (Mention menu @files; data/chat/MentionSearch.kt over the
RUN_COMMAND file search with ranking and LruCache(64)), the shared SuggestionPanel + SuggestionMenuState, and ui/chat/ChatBanners.kt
(the docked stack order of §0.4 as a pure layout composable with slots). Packages ui/queue, ui/suggestions, ui/chat/ChatBanners.kt.
Controllers are plain classes with factories for the ChatViewModel.
Own: ui/queue/**, ui/suggestions/**, ui/chat/ChatBanners.kt, data/chat/SlashCommandSource.kt, data/chat/MentionSearch.kt,
strings_queue_suggestions.xml, tests.`,
  },
  {
    key: 'approval-question',
    task: `${SPEC.ca} C3 (Tool approval sheet, ApprovalBanner, ApprovalPreview, ApprovalController / ApprovalUiState with approvalId dedupe,
reject reason, abort, stale-daemon drop, reconnecting) and C4 (Ask-question sheet, QuestionBanner, AskQuestionController / QuestionUiState
with capability.respond addressed to targetClientId, single vs multiple choice, custom answer, hardware keys), both exposing hasPending for
KeepAlivePolicy. Packages ui/approval, ui/question. Fixture 02/03 replay tests for the controllers.
Own: ui/approval/**, ui/question/**, strings_approvals.xml, tests.`,
  },
  {
    key: 'thread-view',
    task: `${SPEC.hc} §3 Screen B transcript rendering as a reusable composable + pure controller, independent of the ViewModel another lane
builds later: ui/chat/ChatTranscript.kt (LazyColumn reverseLayout pipeline over ChatRenderItem with stable keys, streaming coalescing at
50 ms, auto-scroll pin/release, switching/loading skeletons, transport lines, error banner slot, expansion overrides with user-toggle-wins,
message action wiring, resend/interrupted rows, live-region announcements, selection container), ui/chat/TranscriptState.kt
(TranscriptUiState + a pure TranscriptController that derives it from SessionState/ChatRenderItem lists), §4 Screen C streaming indicator +
ScrollToLatest placement, §5 Screen D ImageLightbox (carousel, pinch zoom, predictive back, share/save through ShareFiles), the KernelLine
mapping of HC §1.6, usage §3.8 rows. Previews with fixture-derived transcripts (long, CJK, streaming, error).
Own: ui/chat/ChatTranscript.kt, ui/chat/TranscriptState.kt, ui/chat/StreamingIndicator.kt, ui/chat/ImageLightbox.kt, ui/chat/KernelLine.kt,
strings_thread.xml, tests.`,
  },
  {
    key: 'sessions-ui',
    task: `${SPEC.ss} S1 (Sessions drawer content for the compact drawer and the expanded list pane, rail entry points, header row with
logo / KernelChip / search, drawer items, list header with sort and filter, Pinned / Scheduled / Tasks or project groups, account footer),
S2 (Sessions screen: compact ListItem rows and expanded column rows, sort, filters, paging, FAB rules), S3 (Session search: full-screen and
docked SearchBar variants), F1–F6 (long-press menu, rename mode, fork, delete with confirm, Session details sheet), §7 motion, §8 a11y.
Package ui/sessions: SessionsDrawer(+VM), SessionRow, SessionRowMenu, SessionsScreen(+VM), SessionSearch(+VM), SessionDetailsSheet,
DeleteSessionDialog; NavGraphBuilder.sessionsGraph(nav, graph) for sessions and search; SessionsDrawerContent for the shell slot.
Own: ui/sessions/**, strings_sessions.xml, tests.`,
  },
  {
    key: 'changes-tool',
    task: `${SPEC.ct} §0, A (Changes screen "Uncommitted changes": pushed route below 1200dp, supporting pane at ≥1200dp; ChangesViewModel /
ChangesUiState over SessionDiffStore; copy/share through ShareFiles; every state of docs/07 §13) and B (Tool detail screen,
ToolDetailViewModel / ToolDetailUiState over ToolCallStore: proceed while running, abort, every state). Packages ui/changes, ui/tool.
Expose NavGraphBuilder.changesGraph / toolGraph and the pane composables ChangesPane / ToolPane for the supporting-pane scaffold.
Own: ui/changes/**, ui/tool/**, strings_changes.xml, tests.`,
  },
  {
    key: 'checkpoints',
    task: `${SPEC.ct} C (Checkpoint dialogs C1 revert / C2 edit-and-restart, C3 checkpoint picker sheet, CheckpointViewModel with the pending
request in SavedStateHandle, every state of docs/07 §13) over CheckpointIndex + CheckpointActions, with the client-checkpoint decision the
spec makes and D-decisions of docs/07 §15 (MIN_PORT_RELEASE_RESTORE null → split path only). Package ui/checkpoint. Expose
CheckpointDialogs(host state) + CheckpointPickerSheet and a factory for the ViewModel so the chat screen can host them.
Own: ui/checkpoint/**, strings_checkpoints.xml, tests.`,
  },
  {
    key: 'settings-core',
    task: `${SPEC.sp} S1 (Settings home with the adaptive list-detail rules of §0.2, SettingsHomeViewModel / SettingsHomeUiState: every state
incl. readProviderPresence, readGlobalSettings, helper get_global_settings / set_telemetry_opt_out, settings.list counts, schedule counts,
/version), S2 (Appearance: theme mode, dynamic colour, font size stepper with live preview, app icon alias switch via
setComponentEnabledSetting + snackbar; AppearanceViewModel), S3 (Notification settings: permission states, channels, keep-alive;
NotificationSettingsViewModel / NotificationSettingsUiState). Package ui/settings; NavGraphBuilder.settingsGraph(nav, graph) with the
list-detail scaffold on expanded (the Providers row replaces it with the providers scaffold — leave a seam for that lane).
Own: ui/settings/**, strings_settings.xml, tests.`,
  },
  {
    key: 'providers-ui',
    task: `${SPEC.sp} S4 (Providers list), S5 (Provider detail: fields, save, list_provider_models, commitPendingDrafts on dispose, OAuth via
S7), S6 (Add OpenAI-compatible provider as route or dialog by window class, discard guard) with ONE ProvidersViewModel scoped to the
settings/providers graph (ProvidersListUiState, ProviderDetailUiState, AddProviderUiState), and S7 Sign in with Cline:
ui/account/SignInWithClineSheet.kt over ClineSignInCoordinator. Secret fields: remember-only state, FLAG_SECURE effect, SecureClipboard.
Package ui/providers (+ that one ui/account file); NavGraphBuilder.providersGraph(nav, graph) incl. the expanded list-detail scaffold.
Own: ui/providers/**, ui/account/SignInWithClineSheet.kt, strings_providers.xml, tests.`,
  },
  {
    key: 'model-workspace-ui',
    task: `${SPEC.sp} S8 (Model picker sheet + reasoning menu, DS #7; ModelPickerViewModel / ModelPickerUiState over ProviderCatalogRepository,
ModelSelectionStore, ModelPickerData; session.update_connection on live sessions; IME union insets) and S9 (Workspace picker sheet +
folder browser; WorkspacePickerViewModel, FolderBrowserViewModel over WorkspaceStore / WorkspacePaths / RUN_COMMAND find; storage permission
flow; browser history back then seekable close). Packages ui/model, ui/workspace; NavGraphBuilder.workspaceGraph for workspace/browse;
sheets exposed as composables with open flags.
Own: ui/model/**, ui/workspace/**, strings_model_workspace.xml, tests.`,
  },
  {
    key: 'customize-ui',
    task: `${SPEC.cr} C1 (Customize: tabs Tools / Plugins / Skills / Rules / MCP / Hooks, search, toggles, uninstall, two entry kinds
top-level and pushed with ?tab=; CustomizeViewModel / CustomizeUiState, every state of docs/07 §13) and C2 (MCP server editor
create/edit/delete, route or dialog by window class, discard guard, consume while saving; McpServerEditorViewModel / McpEditorUiState)
over CustomizeRepository / McpRepository. Package ui/customize; NavGraphBuilder.customizeGraph(nav, graph).
Own: ui/customize/**, strings_customize.xml, tests.`,
  },
  {
    key: 'marketplace-ui',
    task: `${SPEC.cr} C3 (Marketplace list with type/tag filters, category filter sheet, search, stale/offline states) and C4 (entry detail:
pushed on compact/medium, detail pane on expanded; install / uninstall flows with the 300 s helper timeout and CLI fallback;
MarketplaceViewModel / MarketplaceUiState, every state of docs/07 §13) over MarketplaceRepository. Package ui/marketplace;
NavGraphBuilder.marketplaceGraph(nav, graph) incl. the expanded list-detail scaffold.
Own: ui/marketplace/**, strings_marketplace.xml, tests.`,
  },
  {
    key: 'schedules-ui',
    task: `${SPEC.cr} C5 (Schedules list: active run, upcoming, row actions trigger/enable/disable/delete, keep-alive-off notice), C6 (Schedule
editor create/edit from templates, ScheduleFormState @Parcelize, validation, route or dialog by window class, discard guard) and C7
(Schedule details with runs and executions) over SchedulesRepository / ScheduleFormat / RoutineTemplates; SchedulesViewModel,
ScheduleEditorViewModel; every state of docs/07 §13. Package ui/schedules; NavGraphBuilder.schedulesGraph(nav, graph) incl. the expanded
list-detail scaffold.
Own: ui/schedules/**, strings_schedules.xml, tests.`,
  },
  {
    key: 'account-agents-about',
    task: `${SPEC.cr} C8 (Account: Overview / Usage / Billing tabs over AccountRepository, signed-out card starting SignInWithClineSheet,
organisation switch, sign out; AccountViewModel / AccountUiState), C9 (Agents roster sheet over AgentsRepository + SessionAgents;
AgentsRosterViewModel; the header agents-chip model it feeds), C10 (About: versions, kernel checks, licenses incl. Geist Mono OFL and
Material Symbols Apache 2.0, What's new replay, a debug-only Developer row opening DebugScreens' kernel probe; AboutViewModel),
C11 (What's new dialog on launch and from About), C12 (Export diagnostics over DiagnosticsBuilder + ShareFiles; ExportDiagnosticsViewModel),
C13 (Licenses screen from assets). Packages ui/account (AccountScreen + VM), ui/agents, ui/about; NavGraphBuilder.accountGraph / aboutGraph.
Own: ui/account/** (except SignInWithClineSheet.kt), ui/agents/**, ui/about/**, assets/licenses/** additions, strings_account_about.xml, tests.`,
  },
]

const PHASE3B = [
  {
    key: 'chat-screen',
    task: `The chat destination that hosts everything (HC §2 Screen A Home, §3 Screen B thread host, §6 Screen E header, §7 recovery matrix,
§9 acceptance; CA §0.1 controller ownership, §0.4 docked stack, §0.6 deep-link sheet opening; docs/07 §6.5 ChatViewModel row):
ui/chat/ChatViewModel.kt (SavedStateHandle["sessionId"], ChatUiState Home | Session, owns ComposerController, PromptQueueController,
ApprovalController, AskQuestionController, VoiceInputController, CheckpointViewModel factory hand-off, TranscriptController, selection
model HC §2.9, first-send pipeline HC §2.7, session_not_found handling, KernelLine), ui/chat/ChatScreen.kt (SaveableStateHolder keyed by
sessionId ?: "home"; Home = HeroHeading + workspace controls with branch sheet + setup notice slot + persistent composer; Session =
ConversationHeader + ChatTranscript + ChatBanners(approval/question/queue strips) + Composer; sheets: approval, question, queue, context
usage, model picker, workspace picker, attach, branch, agents roster, session details, checkpoint picker; ImageLightbox overlay; the
supporting-pane scaffold for Changes / Tool at ≥1200×≥480 with ChangesPane / ToolPane), ui/home/HomeScreen.kt (hero + composer host,
footer), ui/chat/ConversationHeader.kt (status dot + title, agents chip, diff chip, add/back chip, overflow menu, inline rename, details
sheet reuse, delete confirm), ui/chat/BranchSheet.kt, NavGraphBuilder.chatGraph(nav, graph) for chat/{sessionId?} with ?sheet= and the
pushed chat/{id}/changes and /tool/{toolCallId} routes. Every lane's seams are on main; read their reports in git log and the code.
VM tests with fixtures 02–05 (first send, streaming, approval round-trip, resume after restart, not-found).
Own: ui/chat/** (except rows/, ChatTranscript/TranscriptState/StreamingIndicator/ImageLightbox/KernelLine/ChatBanners), ui/home/**,
strings_chat.xml, tests.`,
  },
]

// ---------------------------------------------------------------------------------------------
// Models. Opus 5.5 implements, reviews, fixes, merges, integrates, verifies and reports. The session
// model (Fable 5.1, no override) is used only for the lead gates — one read-only architecture check
// after each merge and after integration, whose corrections feed the next phase's prompts — and for
// the desktop-parity critic of the first polish round. (Phase 0's termux-core already ran on Fable.)
const OPUS = 'opus'
for (const pkg of PHASE0) if (pkg.key === 'hub-core') pkg.model = OPUS
for (const pkg of [...PHASE1, ...PHASE2, ...PHASE3A, ...PHASE3B]) pkg.model = OPUS
let ADDENDA = ''

// ---------------------------------------------------------------------------------------------
// Helpers

async function pool(items, limit, fn) {
  const results = new Array(items.length).fill(null)
  let next = 0
  async function worker() {
    while (next < items.length) {
      const i = next++
      try {
        results[i] = await fn(items[i], i)
      } catch (e) {
        log(`agent for ${items[i].key ?? i} failed: ${String(e).slice(0, 200)}`)
        results[i] = null
      }
    }
  }
  await Promise.all(Array.from({ length: Math.min(limit, items.length) }, worker))
  return results
}

function implAgent(pkg, phaseTitle) {
  const opts = { label: `impl:${pkg.key}`, phase: phaseTitle, schema: WORK_SCHEMA, isolation: 'worktree', effort: 'high' }
  if (pkg.model) opts.model = pkg.model
  return agent(
    `${COMMON}\nYOUR TASK (work package "${pkg.key}", ${phaseTitle}):\n${pkg.task}\n\nWhen done, report with the schema (branch, worktree path, head commit, build/test status, files, seams, deviations, blockers).`,
    opts,
  )
}

function implAgentOn(pkg, phaseTitle) {
  const opts = { label: `impl:${pkg.key}`, phase: phaseTitle, schema: WORK_SCHEMA, isolation: 'worktree', effort: 'high' }
  if (pkg.model) opts.model = pkg.model
  return agent(
    `${COMMON}\nYOUR TASK (work package "${pkg.key}", ${phaseTitle}):\n${pkg.task}${ADDENDA ? `\n\nLEAD CORRECTIONS from the gate review of the previous phases (they refine docs/07 for the code as it now stands on main; follow them):\n${ADDENDA}` : ''}\n\nWhen done, report with the schema (branch, worktree path, head commit, build/test status, files, seams, deviations, blockers).`,
    opts,
  )
}

function reviewAgent(pkg, r, phaseTitle) {
  return agent(`${COMMON}
YOU ARE THE REVIEWER (read-only; change nothing) for work package "${pkg.key}", implemented by another agent on branch ${r.branch}
(worktree ${r.worktreePath}, head ${r.headCommit}, build ${r.buildGreen ? 'green' : 'RED'}, tests ${r.testsGreen ? 'green' : 'RED'},
${r.testCount} tests). Its task was:
${pkg.task}

Its report — files: ${r.files.join(', ')}; seams: ${r.seams}; deviations: ${r.deviations.join(' | ') || 'none'}; blockers: ${r.blockers.join(' | ') || 'none'}.

Read the branch (\`git -C ${APP} diff main...${r.branch} --stat\`, \`git -C ${APP} show ${r.branch}:<path>\`, or the files in the worktree)
against the task, the spec sections it names, docs/07 and the design system. Method:
1. Build the done contract: list every acceptance item of the spec sections the task names (their acceptance checklists, state tables and
   the §13 screen-index row), plus "build green", "unit tests green", "no stubs/TODO/placeholder", "no existing test deleted or weakened".
2. Verify by execution, not by the report: run \`bash scripts/build.sh :app:testDebugUnitTest\` in the worktree ${r.worktreePath} (this is
   the one command you may run there; it only writes build outputs) and read its result; grep the branch diff for TODO|FIXME|stub|
   NotImplemented|placeholder|coming soon; check \`git -C ${APP} diff main...${r.branch} -- app/src/test\` for removed or loosened tests.
3. Judge each criterion pass/fail with evidence you saw yourself (file:line, test name, preview, command output).
4. Report findings only for correctness and completeness against the task, the spec, docs/07 and the DS: a criterion that fails, a
   wrong behaviour, a missing state/string/preview/test, a secret or threading rule broken. Do NOT ask for refactors, extra abstraction,
   defensive code, performance work or features beyond the task; a sound implementation gets zero findings. Most severe first;
   \`acceptable\` = true only when every criterion passes and nothing above minor remains. Do not pad; do not report style preferences.`,
    { label: `review:${pkg.key}`, phase: phaseTitle, schema: REVIEW_SCHEMA, effort: 'high', model: OPUS })
}

function fixupAgent(pkg, r, review, phaseTitle) {
  return agent(`${COMMON}
YOU ARE THE FIX-UP AGENT for work package "${pkg.key}". Another agent implemented it on branch ${r.branch} (head ${r.headCommit});
a reviewer found the issues below. In your worktree (fresh from main), first run \`git merge --no-ff --no-edit ${r.branch}\` to bring that
work onto your branch, then apply EVERY finding fully (code, strings, tests, previews), keep the original task's scope complete, build and
unit-test green, commit, and report with the schema (your branch supersedes ${r.branch}; keep its seams unless a finding changes them).
Skip a finding only if applying it would contradict the spec or docs/07 §15; say why under deviations.

The original task:
${pkg.task}

Reviewer summary: ${review.summary}
Failing criteria of the done contract (each must pass, with evidence, when you are done):
${review.criteria.filter(c => !c.passes).map(c => `- ${c.item}`).join('\n') || '- none'}
Findings:
${review.findings.map((f, i) => `- [${i + 1}] (${f.severity}) ${f.file}: ${f.claim}\n  fix: ${f.fix}`).join('\n')}
Apply exactly these; do not add abstraction, defensive code or features the findings do not ask for. Never delete or weaken a test to
make it pass. This is the only fix-up round: the merge agent takes your branch next.`,
    { label: `fixup:${pkg.key}`, phase: phaseTitle, schema: WORK_SCHEMA, isolation: 'worktree', effort: 'high', model: OPUS })
}

async function implementReviewed(pkg, phaseTitle) {
  const r = await implAgentOn(pkg, phaseTitle)
  if (!r || !pkg.model) return r
  const review = await reviewAgent(pkg, r, phaseTitle)
  if (!review) return r
  const actionable = review.findings.filter(f => f.severity !== 'minor')
  log(`review ${pkg.key}: ${review.findings.length} findings (${actionable.length} above minor), acceptable=${review.acceptable}`)
  if (review.acceptable && actionable.length === 0) return { ...r, notes: `${r.notes ?? ''} | reviewed: ${review.summary}` }
  const fixed = await fixupAgent(pkg, r, review, phaseTitle)
  return fixed ? { ...fixed, notes: `${fixed.notes ?? ''} | fix-up after review: ${review.summary}` } : r
}

function mergePrompt(results, title) {
  const list = results.filter(Boolean).map(r =>
    `- branch ${r.branch} (worktree ${r.worktreePath}, head ${r.headCommit}, build ${r.buildGreen ? 'green' : 'RED'}, tests ${r.testsGreen ? 'green' : 'RED'})\n  seams: ${r.seams}\n  blockers: ${(r.blockers ?? []).join('; ') || 'none'}\n  notes: ${r.notes ?? ''}`,
  ).join('\n')
  return `You work in the MAIN checkout ${APP} on branch main (no worktree; do not create one). Task: "${title}".
Record \`git rev-parse HEAD\` first (report it as baseCommit). Merge these branches into main, in this order:
${list}

For each branch: \`git merge --no-ff --no-edit <branch>\`. Resolve conflicts by keeping BOTH sides' intent: the branches were designed to
own disjoint files, so a conflict usually means two agents added to the same file (AppGraph.kt, the manifest, MainActivity.kt: keep both
additions) or two agents defined the same type (keep the more complete definition in the package docs/07 §1.1 assigns, delete the other,
update references). A branch whose build was red is still merged; you fix it. After all merges: \`bash scripts/build.sh :app:assembleDebug\`
and \`bash scripts/build.sh :app:testDebugUnitTest\` must pass — fix compile and test errors yourself with minimal reconciliation, not
rewrites. Then make sure every seam the branches reported is reachable from main (the next phase builds on it): a type one branch expects
under a name another branch chose gets an adapter or a rename; interfaces declared by one side (e.g. KernelCommands) get implemented by
the other side's class. Commit the fixes on main ("merge: reconcile <names>"). End with a clean \`git status\`.
Do not delete branches or worktrees (run only \`git worktree prune\`). Do not push. Tooling: dcg screens Bash (no heredocs piped into
shells, no rm -rf outside /tmp), write files with Write/Edit, no foreground sleep.
Report with the schema.`
}

function mergeAgent(results, title, phaseTitle) {
  return agent(mergePrompt(results, title), { label: `merge:${title}`, phase: phaseTitle, schema: MERGE_SCHEMA, effort: 'high', model: OPUS })
}

async function runPhase(title, packages, limit) {
  phase(title)
  log(`${title}: ${packages.length} packages in parallel worktrees (limit ${limit})`)
  const results = await pool(packages, limit, p => implAgent(p, title))
  const done = results.filter(Boolean)
  log(`${title} done: ${done.length}/${packages.length} reported; blockers: ${done.flatMap(r => r.blockers ?? []).length}`)
  return done
}

async function runReviewedPhase(title, packages, limit) {
  phase(title)
  const opus = packages.filter(p => p.model).length
  log(`${title}: ${packages.length} packages in parallel worktrees (limit ${limit}; ${opus} on Opus with Fable review, ${packages.length - opus} on Fable)`)
  const results = await pool(packages, limit, p => implementReviewed(p, title))
  const done = results.filter(Boolean)
  log(`${title} done: ${done.length}/${packages.length} reported; blockers: ${done.flatMap(r => r.blockers ?? []).length}`)
  return done
}

const GATE_SCHEMA = {
  type: 'object',
  properties: {
    ok: { type: 'boolean', description: 'true when main is sound for the next phase after mainFixes are applied' },
    mainFixes: {
      type: 'array',
      description: 'defects on main that must be fixed before the next phase starts (an Opus agent applies them)',
      items: { type: 'object', properties: { file: { type: 'string' }, change: { type: 'string' } }, required: ['file', 'change'] },
    },
    corrections: {
      type: 'array', items: { type: 'string' },
      description: 'short, concrete instructions for the next phase\'s implementers: the seams as they really are on main, names to use, pitfalls seen, rules that were bent; max 12',
    },
    notes: { type: 'string' },
  },
  required: ['ok', 'mainFixes', 'corrections', 'notes'],
}

function gateAgent(title, m, done) {
  const digest = (done ?? []).map(r => `- ${r.branch}: seams: ${r.seams}; deviations: ${(r.deviations ?? []).join(' | ') || 'none'}; blockers: ${(r.blockers ?? []).join(' | ') || 'none'}`).join('\n')
  return agent(`${COMMON}
YOU ARE THE LEAD GATE after "${title}" (read-only: you change nothing; an Opus agent applies what you list under mainFixes). You work in
${APP} on main (HEAD ${m?.headCommit ?? 'unknown'}; the phase's merges started from ${m?.baseCommit ?? 'unknown'}). Merge report: conflicts
${JSON.stringify(m?.conflicts ?? [])}; reconciled ${JSON.stringify(m?.reconciled ?? [])}; notes: ${m?.notes ?? ''}.
Package reports:
${digest || '- (integration step)'}

Check, in this order, spending effort where it matters and not re-reading what the reports already settle:
1. \`bash scripts/build.sh :app:assembleDebug :app:testDebugUnitTest\` on main must be green (else a mainFix).
2. Seams: every seam the packages report must exist on main under that name (grep); AppGraph.kt holds the docs/07 §1.3 members this phase
   should have added; duplicate or competing types across packages; files edited outside their package's ownership
   (\`git diff --stat ${m?.baseCommit ?? 'main~1'}..HEAD\`).
3. Rules: \`grep -rn "Color(0x" app/src/main/kotlin | grep -v ui/theme\` empty; no TODO/FIXME/stub/placeholder in the phase diff; strings only
   in strings_*.xml; secrets rules (docs/07 §3.7); threading (§8) for anything that touches the main thread; docs/07 §15 decisions respected.
4. Spot-check the two or three highest-risk files of the phase against their spec section (reducer rules, controller logic, navigation).
Output: mainFixes (defects to fix on main now, with file and exact change), corrections (what the next phase's implementers must know or do
differently, as short imperative lines — max 12), ok. Be terse; no praise.`,
    { label: `gate:${title}`, phase: title, schema: GATE_SCHEMA, effort: 'high' })
}

function gatefixAgent(title, gate) {
  return agent(`${COMMON}
YOU ARE THE GATE-FIX AGENT after "${title}", working in the MAIN checkout ${APP} on branch main (no worktree). Apply exactly these fixes the
lead gate listed, nothing more; build and unit tests green; commit on main ("fix: gate corrections after ${title.toLowerCase()}"); clean git status; do not push.
${gate.mainFixes.map((f, i) => `- [${i + 1}] ${f.file}: ${f.change}`).join('\n')}
Gate notes: ${gate.notes}`,
    { label: `gatefix:${title}`, phase: title, schema: WORK_SCHEMA, effort: 'high', model: OPUS })
}

async function runGate(title, m, done) {
  const gate = await gateAgent(title, m, done)
  if (!gate) return
  log(`${title} gate: ok=${gate.ok}, mainFixes ${gate.mainFixes.length}, corrections ${gate.corrections.length}`)
  if (gate.mainFixes.length) {
    const fix = await gatefixAgent(title, gate)
    log(`${title} gate fixes applied at ${fix?.headCommit} (build ${fix?.buildGreen ? 'green' : 'RED'})`)
  }
  if (gate.corrections.length) ADDENDA += gate.corrections.map(c => `- [${title}] ${c}`).join('\n') + '\n'
}

async function runMerge(title, done) {
  phase(title)
  const m = await mergeAgent(done, title.toLowerCase().replace(/\s+/g, '-'), title)
  log(`${title}: head ${m?.headCommit}; tests ${m?.testCount} (${m?.testsGreen ? 'green' : 'RED'}); conflicts ${m?.conflicts?.length ?? '?'}`)
  await runGate(title, m, done)
  return m
}

// ---------------------------------------------------------------------------------------------
// Continuation state, extracted from run wf_5ea87a04-7c7's journal on 2026-10-04: phases 0–3b and the
// chat screen are merged on main (chat merge 4bf733bd059d316c39d2be6c09ea95fc92e7d9c6); this script runs the rest.
// args.stage: 'integration' (stop after the integration gate) | 'verification' (skip integration;
// pass args.integration = the integration result) | 'all'.
const STAGE = (args && args.stage) || 'all'
const allPackages = [
 {
  "branch": "worktree-wf_5ea87a04-7c7-2",
  "seams": "PACKAGE dev.cline.termux.core.kernel.termux — `interface TermuxExecutor { isTermuxInstalled(): TermuxPresence (Missing|Disabled|Installed(versionCode, versionName), .installed); hasPermission(): Boolean; suspend run(command: TermuxCommand, timeoutMs = command.timeoutMs): TermuxResult; runDetached(command): DetachedHandle }`. `TermuxRunner(context, elapsed = SystemClock::elapsedRealtime)` is the production implementation (companion: PERMISSION, intent extra/result-key constants, `termuxActivityIntent()` to bring Termux to the front after a foreground command). `TermuxCommand(path, args, workdir=HOME, stdin, background=true, sessionAction, label, description, timeoutMs)`: toString() = \"<label> (N args[, stdin])\", never arguments; `TermuxCommand.bin(...)`/`cline(...)` builders; SENSITIVE_FLAGS/redactArgs. `TermuxResult(stdout, stderr, exitCode, err, errmsg?, stdoutOriginalLength, stderrOriginalLength, elapsedMillis)` with ok/delivered/internalError/truncated/lastStdoutLine/lastStderrLine/stdoutLines/noSuchFile/executableFailed/mentionsExternalApps; factories success/failure/undelivered. Exceptions `TermuxUnavailable.{NotInstalled, PermissionMissing, StartFailed(cause), TimedOut(label, afterMillis), ExternalAppsBlocked(errmsg)}`. `DetachedHandle { executionId; command; earlyExit: Deferred<TermuxResult>; cancel() }`. `TermuxPaths` (PREFIX, HOME, BIN, CLINE, PORT_CURRENT, CLINE_DATA, HUB_DISCOVERY, *_LOG, PROVIDERS_JSON, GLOBAL_SETTINGS_JSON, MCP_SETTINGS_JSON, ATTACHMENTS_DIR, CHAT_WORKSPACE, STORAGE, SHARED_STORAGE, expandHome()). `TermuxTimeouts` table. `AnsiStrip.strip()` / `String.stripAnsi()`. `object ClineCommands` = the full docs/07 §3.4 catalog, each returning a TermuxCommand with its own timeout: probeVersion, readPortRelease, cliVersion, hubStart/hubStatus/hubStop/hubUpgrade, wakeLock/wakeUnlock, readHubDiscovery, tailDaemonLog, tailLog(path, bytes), doctorJson, doctorFix, statProviders, readProviderPresence, readGlobalSettings, authProvider(id, apiKey, modelId, baseUrl?), authInteractive(providerId) [foreground session], cancelAuth, dashboardStart(secret)/dashboardStop(secret?)/dashboardStopPattern, configToolsJson(ws)/configHooksJson(ws), readMcpSettings, mcpInstallStdio/mcpInstallRemote(…McpTransport, headers)/mcpInstallMarketplace/mcpUninstall, pluginInstall/pluginUninstall, skillAdd/skillRemove/installedSkillDirectory, gitBranch/gitShortHead/gitBranches/gitCheckout/gitIsWorkTree, realpath/isDirectory/testDirectory/listDirectories, hasSharedStorage/setupStorage, searchWorkspaceFiles(root, query), readTextFile, historyExport, attachmentWriteCommands(dir, name, content)/attachmentChunks/sanitizeAttachmentName/attachmentPath; `ClineCommands.Labels.*` constants are the keys fakes and logs use; `enum McpTransport`; extension `suspend TermuxExecutor.writeAttachment(dir, name, content): Result<String>` (+ `AttachmentWriteFailed`). CommandOutputs.kt: `HubStatusOutput.parse(stdout)`, `PortVersion.parse` (+ parsedRelease), `PortRelease.parse/fromReadlink` (Comparable), `GlobalSettingsView.parse/from/DEFAULT/isToolEnabled`, `LogTail.clean(stdout, requestedBytes)`, `DirectoryListing.from(result)`, `GitBranchProbe.from(result)`, `GitWorkTreeProbe.from(result)`. ConfiguredProviders.kt: `ConfiguredProviders.parse(json)` → redacted projection (`ConfiguredProvider(id, hasApiKey, hasOAuth, hasCloudCredentials, model, hasBaseUrl, tokenSource, updatedAt)`), isConfigured/isConnected/anyConnected/configuredIds/defaultProviderId/defaultModelId/updatedSince; `ProviderPresence.{Present, Missing, Failed}.from(result)` + `.providersOrEmpty`; extension `suspend TermuxExecutor.readProviderPresence()`. ProvidersFile.kt is gone — use ConfiguredProviders.\n\nPACKAGE dev.cline.termux.core.kernel.dashboard — `SettingsHelper(termux, transport, kernel: HelperKernel, unsupportedStore: HelperUnsupportedStore, scope, elapsedMs, port=8787, timing: Timing, secretSource)`: `state: StateFlow<HelperState>` (Idle | Starting(sinceMs) | Ready | Unavailable(reason: HelperFailure, detail)), `suspend acquire(owner)` from ViewModel.init, `suspend release(owner)` from onCleared (5 s grace, never kills mid-start), `retry()`, `clearUnsupported()`, `awaitSettled()`, `suspend desktopCommand(command, args: JsonObject, timeoutMs = DashboardTimeouts.forCommand(command)): JsonElement` (throws HelperUnavailable.NotRunning/TransportClosed/Timeout or DesktopCommandException(command, error)). `HelperFailure {TermuxMissing, PermissionMissing, ExternalAppsBlocked, StartTimeout, SocketFailed, BuildUnsupported, PortInUse, KernelStopped}`. `interface HelperKernel { suspend isUserStopped(); isHubHealthy(); startHub(); adoptHub() }` and `interface HelperUnsupportedStore { read(); write() }` are adapted in AppGraph. `interface DashboardClient { isOpen; desktopCommand(...); close() }`, `interface DashboardTransport { probeHealth(port, secret?): HealthProbe; connect(port, secret, onClosed): DashboardClient }`, production `OkHttpDashboardTransport()`. `DashboardEndpoints`, `DashboardTimeouts` (30 s / add_provider 60 s / (un)install_marketplace_entry 300 s), `HelperFrames.encodeReady/encodeCommand/decode`.\n\nPACKAGE dev.cline.termux.core.secure — `SecureClipboard(context, scope, lifecycle?, clearAfterMs=60_000, elapsedMs)`: `copySecret(label: CharSequence, secret)`, `clearNow()`, `holdsSecret`; plain clips bypass it.\n\nAppGraph members: `termux: TermuxExecutor`, `commands: ClineCommands`, `hubProber: HubProber`, `dashboardTransport: DashboardTransport`, `settingsHelper: SettingsHelper`, `secureClipboard: SecureClipboard` (ProcessLifecycleOwner lifecycle); existing prefs/tokenStore/cursors/catalog/kernel kept. Uses `prefs.userStopped()`, `prefs.helperUnsupported()`, `prefs.setHelperUnsupported()`, `kernel.ensureConnected(startIfDown)`.\n\nTest support (app/src/test): `dev.cline.termux.fakes.FakeTermuxExecutor` (presence, permitted, latencyMs, `on(label, result|responder)`, `never(label)`, `throwing(label, error)`, `commands`/`labels`/`detached` with `FakeDetachedHandle.exitWith/failWith`), `FakeDashboardClient` (`respond(command, result|responder)`, `fail(command, error)`, `calls`, `dropConnection()`), `FakeDashboardTransport` (`probeResponder`, `connectFailure`, `onConnect`, `clients`/`lastClient`), `dev.cline.termux.expectThrows<T>{}` and `Result.failedWith<T>()`. No strings file: this package has no user-visible strings (HelperFailure/HelperState are enums/sealed values the UI maps to its own resources).",
  "deviations": [
   "KernelCoordinator.kt and KernelCoordinatorTest.kt (owned by the kernel package) received compile-only edits forced by the new TermuxExecutor shape: `isTermuxInstalled().installed`, `run(command)` without the Duration parameter (each catalog command carries its timeout), `ClineCommands.version()` -> `cliVersion()`, nullable `errmsg`, and the test's FakeTermux implementing `TermuxPresence`/`runDetached`/`run(command, timeoutMs)` and recording `command.label`. No behaviour change.",
   "ui/debug/KernelProbeViewModel.kt: compile-only adjustments for the same interface change (`timeoutMs = 45_000`, `truncated`, nullable errmsg).",
   "docs/07 §3.4 lists `cline hub status --json` and `cline doctor --fix --json`; the CLI source in ~/workspace/cline-termux-v3-phone has no `--json` on `hub status` (it already prints a JSON object) and the fix subcommand is `cline doctor fix --json`, so the catalog follows the CLI (`hubStatus()` = `cline hub status`, `doctorFix()` = `cline doctor fix --json`).",
   "docs/07 §7.2 says `helper.unsupported` is also cleared on app update; this package only exposes `SettingsHelper.clearUnsupported()` (ClineApp/startup integration is out of this package's ownership).",
   "`HelperKernel.isUserStopped()` is a suspend read of the persisted `kernel.userStopped` preference rather than a StateFlow projection of the coordinator, because the coordinator is owned by another agent; AppGraph adapts it.",
   "No `strings_<pkg>.xml` was added: the package has no user-visible strings (states and failures are typed values; the detail strings surfaced are the CLI's own stderr text as the spec requires)."
  ],
  "blockers": [],
  "notes": "Both gates run from the worktree: `bash scripts/build.sh :app:assembleDebug` (BUILD SUCCESSFUL, no warnings) and `bash scripts/build.sh :app:testDebugUnitTest` (180 tests, 0 failures; 101 of them belong to this package, the rest are the pre-existing hub/kernel/data/ui suites which still pass). Nothing was pushed and nothing installed on a device. Two test-harness lessons worth keeping: a helper launched into `TestScope.backgroundScope` is not driven by `advanceUntilIdle()` (background delays are skipped), so SettingsHelperTest uses the test scope itself; and a MockWebServer websocket listener "
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-1",
  "seams": "AppGraph members (owned): kernelClock: KernelClock (SystemKernelClock; I left the name `clock` free for docs/07's java.time.Clock), cursors: HubCursorStore (= prefs.cursorStore()), kernelCommands: KernelCommands (TermuxKernelCommands(termux, packageManager)), hubClient: HubClient (one per process; its credentials come from kernel.currentCredentials()), kernel: KernelCoordinator (tokenOverride = KernelOverrides.hubToken(context)), sessionApi: HubSessionApi(hubClient).\n\nHubClient (core/kernel/hub): connect(): ConnectOutcome; disconnect(); disconnectForReplace(); subscribe(sessionId)/unsubscribe(sessionId) (per-session, never carries a cursor; re-sent on reconnect); command(name, payload, sessionId?, envelopeTimeoutMs, clientTimeoutMs): JsonObject (throws HubCommandException / HubConnectionClosedException); send(...): HubPendingCommand for send_input/run.start; state: StateFlow<HubConnectionState>; events: SharedFlow<HubEventEnvelope> (exactly one collector, SessionStore.pump; deduped by sequence); sideEvents: SharedFlow (events without a sessionId: hub.client.*, hub.drain_changed, schedule.*, settings.changed); notices: SharedFlow<Notice.ReplayTruncated(fromSequence, lastSequence)> for SessionsRepository; draining, hubStatus, lastSequence StateFlows; awaitNotDraining(ms); flushCursor(). Extension HubClient.withDrainRetry { }. Companion defaultEnvelopeTimeout/defaultClientTimeout (the §3.1 table), REPLAY_BUDGET = 2000.\n\nHubErrors: HubCommandException(command, code, message, details), HubErrorCodes.*, isLostReply(), isSessionGone(), isDraining(), isAuthorityLost(), isPendingPromptsUnavailable(). HubProtocol.isCompatible/direction (port of isHubProtocolCompatible, plus the E.9 direction).\n\nHubSessionApi + HubPayloads: NewSessionSpec, TeamMembership, sessionCreate/sessionConfig (checkpoint.enabled always true), restoreMetadata/filteredCheckpoints/revertMetadata, sessionRestore/sessionRestoreWorkspaceOnly/sessionCreateRestored, sendInput (userImages/userFiles), approvalRespond, capabilityRespond ({requestId, ok, payload:{result}} as upstream reads response.result), pending prompts incl. steerFirstPendingPrompt, sessionUpdate (metadata spread), updateConnection ({sessionId, updates}), settingsList/Toggle, schedule.* (ScheduleDraft), reply readers sessionsOf/messagesOf/metadataOf/sessionIdOf.\n\nKernelCoordinator (core/kernel): state: StateFlow<KernelState>; gate: StateFlow<KernelGate> (re-ticks every second during Starting so `slow` flips at 20 s); commandsAvailable, userStopped, portVersion, wakeLockError StateFlows; info: HubInfo?; transitions: KernelTransitions. Calls: ensureConnected(startIfDown, userInitiated) (\"Start kernel\" passes userInitiated = true, which clears userStopped and the crash loop); stopKernel(): StopOutcome; disconnect(); recheck(); probeHealth(); refreshHubStatus() (the sheet's 5 s refresh); beginRestart(): RestartPrecheck (needsConfirm; ownBusy from hub.status, so the VM must OR in its own Running/Pending sessions) then restart(forced) or cancelRestart(); setKeepAwake(enabled): WakeLockOutcome; fixProcesses(): DoctorSummary?; runDoctor(): DoctorSummary?; onForeground()/onBackground() for ProcessLifecycleOwner ON_START/ON_STOP (auto-start after more than 5 s away, passive 10 s probe, cursor flush). SessionReattachHook (openSessions/onAttached/onSessionGone) is a constructor parameter for SessionStore to implement (default None). KernelGate: Ready | Busy(kind, stepLabel: BusyLabel.Step(StartStep)|Phase(RestartPhase), slow) | Down(variant, detail) | DegradedNoCommands | Blocked(CheckId). KernelState.toGate(nowElapsedMs, commandsAvailable). HubDown also has `failure: StartFailure?` (CredentialsUnreadable / HandshakeRefused), which kernel-ui maps to the KS §0.4 sentences. HubSessionActivity.summarizeOtherClients/describe and formatUptime are 1:1 ports for E.5 and E.7.\n\nKernelCommands is the interface the termux lane's ClineCommands should implement: termuxPresence, hasRunCommandPermission, readPortVersion, hubStart(timeoutMs), hubStatus, hubStop, hubUpgrade, readDiscovery, tailDaemonLog, doctorJson, doctorFix, wakeLock, wakeUnlock, all returning KernelCommandResult (Completed/TimedOut/Unavailable). TermuxKernelCommands is the bridge until then and can be deleted at the merge.\n\nDebug override: object dev.cline.termux.debug.KernelOverrides { suspend hubToken(ctx): String?; setHubToken; clearHubToken } in src/debug (sealed in DataStore \"debug\") and src/release (no-ops). The receiver is registered in src/debug/AndroidManifest.xml. The adb commands are in docs/06 \"Developer Hub override\".\n\nFakes (test/fakes): FakeHubTransport (scripted replies/replay, sent-frame assertions, attemptedTokens/openedTokens, opensWhilePreviousOpen), InMemoryCursorStore, FakeKernelCommands + FakeHubHost + FakeHubProber + FakeTokenCache + TestKernelClock, plus the existing FakeClock. This package adds no strings file; strings_kernel.xml is left to kernel-ui as instructed.",
  "deviations": [
   "KernelGate.Busy.stepLabel is a typed BusyLabel (Step(StartStep) | Phase(RestartPhase)), not docs/07's `Int?` string resource and not the KS F.6 String: strings_kernel.xml belongs to the kernel-ui package, so it maps the label to its kernel_step_* / kernel_restart_phase_* strings.",
   "KernelState.HubDown has an extra `failure: StartFailure?` field (default null) for the two app-detected failures that have no kernel text (credentials unreadable, handshake refused), so the UI owns that copy instead of the coordinator putting English text into lastError.",
   "doctorFix runs `cline doctor fix --json`, as KS §0.5 says, and not docs/07 §3.4's `cline doctor --fix`. Upstream apps/cli/src/commands/doctor.ts defines `fix` as a subcommand; `--fix` is not an option and would be rejected by exitOverride. docs/07 §3.4 should be corrected.",
   "Edited data/prefs/AppPreferences.kt (the cursorStore() adapter) and AppPreferencesTest, which are outside the listed files. HubCursorStore became docs/07's `suspend get()/put(sequence)` with no sessionId, and throttling and flushing moved into HubClient (1 s throttle, flush on disconnect and ON_STOP), so the adapter now writes through monotonically and flushCursor() was removed from AppPreferences.",
   "AppGraph exposes `kernelClock: KernelClock` rather than `clock`, so docs/07 §1.3's `clock: java.time.Clock` stays available. The coordinator needs monotonic elapsed time, which java.time.Clock does not provide.",
   "KernelCommands carries the extra members termuxPresence() and hasRunCommandPermission() (P1/P2), so the coordinator needs no PackageManager or Context and is fully unit-testable. Several termux files (TermuxPaths, TermuxCommand, TermuxExecutor) are used as-is, unmodified.",
   "Register order follows docs/07 §3.1: client.register, then hub.status, then the global subscribe. KS §0.4 H5 lists the subscribe before hub.status; docs/07 wins on code-level matters. A fresh install (null cursor) subscribes with sinceSequence = max(0, L-2000) rather than omitting it, and a cursor ahead of the Hub's log (log reset) is treated as truncated.",
   "HubLiveStatus also parses hubId, and RestartPrecheck adds an `unknown` flag (session.list failed), which forces the confirm dialog (KS E.10).",
   "KernelTransitions does not record a transition when the from and to labels are identical (a payload refresh such as a new HubInfo or hub.status is not a transition)."
  ],
  "blockers": [],
  "notes": "All work is in one commit, f6c1c38. I squashed the two interrupted WIP snapshots (36acf8d on this branch) into it. Both `bash scripts/build.sh :app:assembleDebug` and `bash scripts/build.sh :app:testDebugUnitTest --rerun` pass: 141 tests across 19 suites, 0 failures. git status is clean.\n\nDefects found and fixed while reviewing the inherited WIP:\n1. HubClient.connect() only returned once the socket closed: the first-attempt result was completed after attemptOnce returned. Every coordinator connect would have hung, and the reattach test timed out. It now completes as soon as registration and su"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-5",
  "seams": "PURE PROJECTION (ui/kernel/KernelCopy.kt): KernelCopy.panel(gate, detail = gateDetail(gate)): KernelPanelModel?; KernelCopy.banner(gate, hasComposer): KernelBannerModel?; KernelCopy.chip(gate, port: Int?): KernelChipModel; KernelCopy.primary(gate): KernelAction?; KernelCopy.downDetail(state: KernelState): UiText? (StartFailure sentences, the 60 s timeout sentence, or the stderr tail; pass it as `detail` because the gate drops these); stepLabel/phaseLabel/busyLabel; describeSessions(HubSessionActivity): UiText (plurals; use it instead of HubSessionActivity.describe, which is English-only); connectedAnnouncement(prev, gate). enum KernelAction {StartKernel, TryAgain, Connect, RestartKernel, KernelStatus, KernelStatusDiagnostics, WhyDidThisHappen, Details, Fix}; hosts map them: Start/TryAgain -> kernel.ensureConnected(startIfDown = true, userInitiated = true); Connect -> ensureConnected(startIfDown = false, userInitiated = true); RestartKernel -> HubUpdatePresenter.reopen() (Protocol variant); KernelStatus/Details -> open the status sheet; KernelStatusDiagnostics -> sheet scrolled to diagnostics; WhyDidThisHappen -> reliability checklist; Fix -> pushed kernel/repair. UiText (ui/kernel/KernelText.kt): Res/Plural/Raw with resolve(resources) and @Composable resolve().\nCOMPOSABLES (ui/components): KernelBannerHost(gate, hasComposer, onAction, modifier, actionInFlight) is what Home/Chat/Sessions/Settings/Changes put at the top of the content column (debounce 1.5 s, disclosure motion, live-region announcements incl. \"Cline kernel connected\"); KernelBanner(model, onAction, actionInFlight) is the raw component. KernelStatePanel(gate, hasComposer, onAction, detail = ..., primaryInFlight) for Chat with nothing cached. KernelChip(gate, port, onOpenStatus, placement = KernelChipPlacement.DrawerHeader | Rail) opens the sheet through the callback. KernelBlockedCard(block: KernelBlock, onAction: (KernelBlockAction) -> Unit, permissionPermanentlyDenied); KernelBlock.of(TermuxUnavailable) / KernelBlock.of(KernelGate.Blocked); KernelBlockAction {InstallTermux, RequestPermission, OpenAppSettings, OpenKernelSetup, Retry, RunDiagnostics}.\nCOMPOSER: ComposerGate.of(gate, port): readOnly, kernelAllowsSend, placeholder: UiText?, row: ComposerKernelRow.Normal | Busy(line1) | Down(line1, action?); ComposerKernelButton(action, inFlight).label/enabled for the row's TextButton.\nE.9: HubUpdatePresenter(scope, restart = { graph.kernel.restart(forced = false) }) lives in AppShellViewModel: feed onKernelState(kernel.state) on every emission, onStart() on activity ON_START, render HubUpdateRequiredDialog(state = presenter.dialog.value!!, onRestart = presenter::confirm, onLater = presenter::later) when dialog != null; presenter.restarted increments -> snackbar kernel_restarted_snackbar.\nDIAGNOSTICS: AppGraph member to add: `val kernelDiagnostics by lazy { KernelDiagnostics(KernelDiagnostics.Runner.of(kernel, kernelCommands), appScope) }`; surfaces collect kernelDiagnostics.state / fixing, call run()/fix(); render DiagnosticsBlock(ui, fixing, allowFix, onFix) (owns the \"Stop all Cline processes?\" confirm).\nFAKE KERNEL (debug only): AppGraph must wrap its seams (integration agent; AppGraph is off limits to me): `termux = KernelOverrides.termuxExecutor(context, TermuxRunner(context))`, `kernelCommands = KernelOverrides.kernelCommands(context, TermuxKernelCommands(termux))`, `hubProber = KernelOverrides.hubProber(context, OkHttpHubProber())`, and HubClient(transport = KernelOverrides.hubTransport(context, OkHttpHubTransport()), ...). Release twins return `real`. Select with `adb shell am start -S -n dev.cline.termux/.MainActivity --es dev.cline.termux.FAKE_KERNEL <scenario> [--ez dev.cline.termux.RESET_ONBOARDING true]` (`off` = real kernel; persisted until off). KernelOverrides.fakeKernelScenario(context) and fakeKernelTrace() expose state; RUN_COMMAND labels also go to logcat tag ClineFakeKernel. MainActivity.onCreate first line: KernelOverrides.applyLaunchIntent(this, intent).\nSTRINGS: res/values/strings_kernel.xml owns kernel_*, hub_*, state_*, banner_*, diag_*, kernel_blocked_* (including every E.5/E.6/E.7 key the status sheet needs, e.g. kernel_row_*, kernel_restart_confirm_*, kernel_stop_confirm_*, snackbars). Other packages must reuse these keys, not redefine them (duplicate names break the merge). I did not define onboarding_*, setup_*, check_*, install_termux_*, connect_*, done_*, reliability_*, rel_*, common_*, chat_transport_*, composer_kernel_down_placeholder or list_unavailable_state.",
  "deviations": [
   "KernelOverrides.applyLaunchIntent takes (context, intent), not (intent): it needs the context to persist the scenario and reset onboarding.",
   "The fake is a dynamic switch, not a one-time replacement. The AppGraph seams are lazy singletons, so each wrapper checks the active scenario on every call and falls back to the real implementation when the scenario is off. A fourth hook, hubTransport, was needed because auth_failed (401) and restart_busy_other_client (session.list) only exist on the socket, and a fake Hub token would otherwise hit the real Hub.",
   "Fake-kernel commands the scenarios do not cover (git, realpath, dashboard, MCP and so on) fail with exit 127 and a 'not available while the debug fake kernel runs' stderr. They are never passed to the real Termux.",
   "Selecting a scenario clears kernel.userStopped, so the scenario's auto-start runs. RESET_ONBOARDING clears onboarding.completedAt, signIn.startedAt and reliabilityShownAt once per process, using runBlocking(IO). This is debug-only, so the reset lands before the start destination is read.",
   "restart_slow: §0.9 says 'result after 70 s' but E.12 expects a 75 s timeout followed by a re-probe that sees the new pid. The new Hub answers from 70 s and the upgrade result never arrives before the 75 s timeout, so both texts are satisfied.",
   "hub_start_port_old: VERSION has no release= line, so the coordinator's P3 check already reports PortTooOld(null) before any hub start. The daemon-log entry.js line is still scripted for paths that reach it.",
   "Composer while busy follows CA C1.6 for the field (editable, Send disabled) and KS 0.10 for the row (16dp spinner + chip line 1). Draining leaves the composer usable (CA 'Held for Hub restart'). Down variants are read-only. Placeholder: 'Start kernel to continue.' for Asleep, 'Cline Hub is not connected.' otherwise, and 'Fix the Cline kernel' when Blocked.",
   "Drawer KernelChip shows the F.2 chip line 1 as its label and the dot as its leading icon, without the logo, because the drawer header already has the logo IconButton. The rail variant follows DS: logo with the dot, label 'Hub'. TalkBack reads 'Kernel status: <line 1>' (the existing components_kernel_status_a11y string).",
   "Banners with no F.2 primary (Starting/Connecting, Reconnecting) use 'Kernel status' as their trailing action, since DS gives the banner exactly one trailing button.",
   "KernelBlockedCard has a fifth Android row, KernelSetup ('Fix the Cline kernel' / 'The Cline kernel in Termux needs attention before this works.' / 'Open kernel setup'), for Blocked(Kernel/Hub), which SP §0.5 does not cover. The timeout card's overflow button needs an accessibility label, so I added the new Android string 'More actions'.",
   "New Android strings that are not in KS §9: diag_yes ('Yes', when doctor reports a healthy Hub without a pid), diag_failed, diag_failed_timeout, diag_cancel, kernel_rail_label, kernel_lines_cd, kernel_copy_command / kernel_copied_command / kernel_command_copied, and the kernel_failure_* keys. The kernel_failure_* keys carry the check_hub_* sentences under kernel-owned names, so they cannot collide with the onboarding package's keys.",
   "DiagnosticsBlock lives in ui/kernel/KernelDiagnostics.kt, not ui/components, because my ownership covers ui/components/Kernel*.kt only. The installer CommandBlock is a private composable inside HubUpdateRequiredDialog.kt, because the shared CommandBlock belongs to the onboarding package."
  ],
  "blockers": [
   "Defect in core/kernel/TermuxKernelCommands.kt (not my file): it folds TermuxUnavailable.ExternalAppsBlocked into KernelCommandResult.Unavailable(StartFailed, errmsg). TermuxRunner throws that exception for every allow-external-apps refusal, so on a real device the coordinator reports HubDown(StartFailed) instead of KernelState.ExternalAppsBlocked. The fix is one line: return KernelCommandResult.Completed(\"\", \"\", -1, err = <non -1>, errmsg = e.errmsg), as ScriptedKernel.commands does. KernelCoordinatorTest does not catch it because its fake commands return the result directly.",
   "The fake kernel does nothing in the running app until the integration agent wraps the AppGraph seams with the KernelOverrides hooks listed under seams (AppGraph.kt was off limits). Until then, a FAKE_KERNEL scenario is saved but the app still talks to the real kernel.",
   "Only :app:assembleDebug and :app:testDebugUnitTest ran, as instructed. The release KernelOverrides twin was not compiled; its imports and signatures match the debug object."
  ],
  "notes": "Done and committed as 50ef3b8 on branch worktree-wf_5ea87a04-7c7-5; the debug build and the debug unit tests both pass. Of the 291 tests in that run, 49 are new: 14 in KernelCopyTest, 12 in KernelSurfacesTest, 3 in KernelStringsTest and 20 in FakeKernelScenarioTest. The fake-kernel scenarios run the real KernelCoordinator and HubClient against the scripted kernel in virtual time. Every scenario lands on the state its section describes: the blocked states, slow start (still starting at 21 s, Ready after /health answers at 25 s), start failure after the 60 s budget, start hang into a timeout plu"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-11",
  "seams": "The seams are the same as on worktree-wf_5ea87a04-7c7-7, which this branch merges (--no-ff) and replaces. In ui/terminal/TerminalView.kt: CommandOutput(output, isRunning, modifier, onCopyOutput); TerminalView(snapshot|output, running, modifier, inline, wrap, state, contentPadding, scrollButtonPadding, onCopyOutput); rememberTerminalSnapshot(output, capChars); rememberTerminalScrollState()/TerminalScrollState; TerminalDefaults. Also data/tools/TerminalBuffer (TerminalBuffer, TerminalSnapshot, TerminalLine, CommandOutputCap, TerminalBuffer.parse), the data/diff pure ports (SessionDiff, LineDiff, UnifiedDiffText), ui/diff/DiffHunkView, NoHunkDetails, ChangedFileHeader and DiffRows, plus strings_terminal_diff.xml. Behaviour changes in this round: rememberTerminalSnapshot now always parses on Dispatchers.Default and keeps the previous snapshot. Only the first frame of output up to 4,096 characters is parsed in composition. Follow-tail is keyed on the rows by value, not on snapshot.version. The running cursor is an overlay outside the text, with its semantics cleared. Hunks over 4,096 characters are diffed on Default.",
  "deviations": [],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-7 with --no-ff, then committed 617cf52 on top. Build is green and all 315 unit tests pass (0 failures).\n\n[1] Follow-tail (TerminalView.kt): the LaunchedEffect is now keyed on (rows, state.pinned). The rows are compared by value, so a chunk that only lengthens the last line also restarts the effect and scrolls while pinned. New test in AnsiParserTest: \"terminal rows change when a chunk only lengthens the last line\". It shows that parse() returns the same version for both snapshots and that the rows still differ, for pytest dots and for \\r progress lines.\n\n[2] C"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-8",
  "seams": "All packages below are under dev.cline.termux.data. Everything is pure Kotlin: no composables, no Android APIs, and no AppGraph members. Callers use top-level functions or stateless objects, and the only stateful helpers are CheckpointIndexCache and ToolRowOpenState.\n\nTOOLS (data.tools)\n- Payload parsing:\n  - parseToolPayload(String?) -> ToolPayload\n  - buildToolPresentation(ChatMessage) -> ToolPresentation\n  - isToolCallInProgress\n  - formatToolValue\n  - extractRunCommandOutput\n  - extractSubmitSummaryText\n- Live command output:\n  - appendCappedCommandOutput(prev, chunk) -> CappedOutput\n  - MAX_LIVE_COMMAND_OUTPUT_CHARS\n  - COMMAND_OUTPUT_TRUNCATION_MARKER\n- Row rules:\n  - buildToolRowModel(message or presentation, isRunActive, proceedAvailable, showInputPreview) -> ToolRowModel, which carries status: ToolActivityStatus and fileDiffs: List<ToolFileDiff>\n  - ToolRowOpenState, with initial, onModel and onUserToggle, for expansion.\n- Summaries:\n  - buildToolSummary(ToolSummaryPayload, ToolSummaryOptions) -> ToolSummary, which carries label parts, aggregate and items (sealed ToolSummaryItem File/Command/Query/Url/Text)\n  - buildGroupedToolLabel(List<GroupedToolLabelInput>)\n  - ToolSummary.groupInput(inProgress)\n  - pluralize\n  - teamSummary\n- Diff and path helpers:\n  - makeUnifiedDiff\n  - countDiffLines\n  - parseApplyPatchInput\n  - parseEditorInput\n  - shortenPath\n  - displayFileName\n  - detectLanguage\n  - validateImageMedia\n  - extractOutputMedia\n  - extractOutputText\n- JS-compatible JSON helpers (JsonValues.kt):\n  - parseJsonOrNull, jsonStringify\n  - JsonElement?.jsString / jsNumber / jsBoolean / asRecord\n  - collapseWhitespace\n  - parseJsDate\n  - jsLocaleCompare\n- ToolKind and classifyTool are reused from ui.icons and not duplicated.\n\nCHAT (data.chat)\n- Work labels:\n  - formatThoughtLabel(Long?)\n  - formatWorkDuration(Long)\n  - formatWorkActivityLabel(durationMs?, toolCallCount?)\n- Context usage, via ContextUsageCalculator:\n  - compute(ContextUsageData, Locale) -> ContextUsage, which has fractions, tone and labels\n  - segmentWidths(...)\n  - formatCompactTokens(Long, Locale)\n\nAGENTS (data.agents)\n- buildSessionAgentActivity(messages, sessionActive) -> SessionAgentActivity\n- mergeAgentActivity(entries: List<SessionAgentEntry>, derived, sessionActive)\n- describeAgentActivity\n- isAgentRunToolName\n\nSESSIONS (data.sessions)\n- SessionSummary.fromRecord(JsonObject session.list record, ScheduleLink?) -> SessionSummary?\n  - Members: isLive, isScheduled, forCache(), visual(SessionFlags) -> data.session.SessionVisual\n- isListableRecord(record, alwaysKeep)\n- SessionActivityDescending (comparator)\n- KernelSessionStatus (@Serializable)\n- Grouping:\n  - groupThreadsByProject -> List<ProjectGroup>\n  - groupScheduledThreads -> List<SidebarListRow>\n  - INITIAL_VISIBLE_THREAD_COUNT=30\n- SessionFormat:\n  - sessionTitle, titleFromMessages\n  - formatDisplayUserInput\n  - formatSessionSearchTitle / formatSessionSearchPreview\n  - workspaceLabel, uniqueWorkspaceLabel\n  - modelLabel\n  - tokensLabel, formatCostUsd\n  - formatRelativeTime\n  - compactAge(activityAt, now, zone) -> sealed CompactAge\n  - scheduleRunLabel(runNumber?, startedAtMs?) -> sealed ScheduleRunLabel\n  - CompactAge and ScheduleRunLabel are typed results; the UI formats them with DateUtils and its own string resources.\n\nSCHEDULES (data.schedules)\n- Form model:\n  - ScheduleForm / ScheduleTiming / ScheduleFacts / ExecutionFacts\n  - validateScheduleForm(form, editingCron, nowMs, zone): Result<ValidSchedule>; a failure is ScheduleFormException(error: ScheduleFormError), an enum the UI maps to strings.\n  - routineScheduleTimezone(type, existing, isExisting, deviceZone)\n- Cron:\n  - buildCronPattern\n  - parseCronPattern\n  - parseScheduleTrigger\n  - preserveRoutineCron\n- Formatters:\n  - formatScheduleTrigger\n  - formatScheduleDays\n  - formatScheduleTime\n  - formatDateTime\n  - formatScheduleModel\n  - formatExecutionResult\n- Shared CR 0.7 helpers:\n  - previewText\n  - formatCreditBalance\n  - formatAccountDate / formatAccountTime\n- Routine templates:\n  - ROUTINE_TEMPLATES\n  - suggestedTemplates(existingNames)\n  - RoutineTemplate.toForm()\n  - RoutineTemplateIcon (the UI maps it to icons)\n\nMARKETPLACE (data.marketplace)\n- Types:\n  - MarketplaceType (wire/parse)\n  - MarketplaceType.meta (label/plural/short/glyph/blurb/tint)\n  - MarketplaceListEntry\n- List rules:\n  - filterByTypeAndQuery\n  - tagCounts\n  - visibleTags\n  - groupByMaturity\n  - showTypePills\n  - entryKey\n\nWORKSPACE (data.workspace)\n- WorkspacePathRules, with the default WorkspacePathRules.Termux:\n  - filterWorkspacePaths\n  - workspacePathsFromSessions\n  - parseWorkspaceSelectionStorage / serializeWorkspaceSelectionStorage\n- Top-level path functions:\n  - formatWorkspacePath\n  - workspaceName\n  - resolveWorkspaceFilePath\n  - normalizeWorkspacePath\n  - mergeWorkspacePaths\n- Constants: TERMUX_HOME, WORKSPACE_SELECTION_STORAGE_KEY\n\nMODEL (data.model)\n- Picker:\n  - buildModelPickerData(providerId, List<PickerModel>) -> ModelPickerData(options, sections?)\n  - .withCurrentModel(id)\n  - PickerOption.matches(query)\n  - ModelPickerData.freeTextModelId(query)\n  - PickerModel.capabilityBadges()\n- Selection storage:\n  - parseModelSelectionStorage / serializeModelSelectionStorage\n  - parseReasoningSelection / serializeReasoningSelection\n  - ReasoningEffort\n  - MODEL_SELECTION_STORAGE_KEY / REASONING_SELECTION_STORAGE_KEY\n\nCHECKPOINT (data.checkpoint)\n- Build: CheckpointIndex.build(history, List<RunMessage>), where RunMessage.fromJson(session.messages item).\n- Index queries:\n  - hasExactCheckpoint / exactCheckpoint\n  - checkpointAtOrBefore\n  - canEdit\n  - entryForMessage / entryAt\n  - restorableMessages\n  - previewFor\n- Cache: CheckpointIndexCache.get(lastSequence, canonicalCount) { build }\n- History:\n  - readSessionCheckpointHistory(metadata)\n  - findCheckpointForRun\n  - createRestoredCheckpointMetadata\n- Trimming:\n  - trimMessagesToCheckpoint / trimMessagesBeforeUserRun; these throw CheckpointRunException with the kernel's own error text.\n- Other: truncatePreview, getUserRunSpan, countUserRunMessages\n\nABOUT (data.about)\n- parseChangelog(markdown) -> List<ChangelogRelease>\n- recentReleases\n- isInstalledRelease\n- isBetaVersion\n- CHANGELOG_ASSET=\"CHANGELOG.md\"\n- AppLinks: REPO_URL, ISSUES_URL, CHANGELOG_URL_ON_GITHUB, releaseUrl(v)\n\nNo strings file was created; see deviations.",
  "deviations": [
   "The desktop's English copy stays as constants inside the pure functions, as a 1:1 port. Examples: tool labels; 'Chat', 'Other', 'No workspace', 'No model' and 'Current model'; and schedule texts such as 'Every day', 'Weekdays', 'Daily · …' and 'Once · …'. It is not in strings.xml, and no strings file was added, because Android resources are off limits for pure data code. Exceptions: ScheduleFormError is an enum the UI maps to strings, and the tool-row proceed copy was left to the UI.",
   "Date-relative labels return typed results (sealed CompactAge and ScheduleRunLabel) instead of the desktop's formatted strings, so the UI can localize them with DateUtils.",
   "The desktop's module-level globals (host home, task worktree root, excluded paths) became a WorkspacePathRules class, with a Termux default (TERMUX_HOME, shared storage excluded).",
   "Added helper files the task did not name, all inside the named packages: data/tools/JsonValues.kt (JS-compatible typeof, JSON.stringify, Date.parse and localeCompare), data/tools/ToolSummary.kt and data/tools/ToolSummaryParsers.kt. These split the desktop's tool-summary index.ts, team.ts, parsers.ts, paths.ts and diff.ts out of ToolPresentation.kt.",
   "AppLinks.releaseUrl uses the tag scheme 'app-v<version>' on github.com/IChouChiang/cline-termux. This follows the CR OQ C-8 default; the tag scheme is my assumption, not confirmed.",
   "Not ported: the paginationItems helper (the pager was dropped) and the marketplace Connectors type.",
   "Locale-sensitive time formatting uses the JDK, which puts a narrow no-break space (U+202F) before AM/PM. Tests normalize it; UI text gets the platform's spacing.",
   "Commit attribution: the trailer reads 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>', per the harness attribution reminder and the actual model. The task text asked for 'Claude Fable 5.1'. Amend the trailer if the orchestrator really wants that one."
  ],
  "blockers": [],
  "notes": "I ran both required Gradle tasks: :app:testDebugUnitTest and :app:assembleDebug both ended in BUILD SUCCESSFUL. The test count is 471 for the whole module; 229 of those are new in this package. The work is committed as 84e73f04d on branch worktree-wf_5ea87a04-7c7-8, and git status is clean. Nothing was pushed.\n\nThere is no CHANGELOG.md asset in app/src/main/assets yet. Whoever owns the About screen must bundle one (CHANGELOG_ASSET) before parseChangelog has anything to read.\n\nSessionSummaryTest reads the hub fixture app/src/test/resources/hub-fixtures/05-resume-after-restart.jsonl. RoutineTemp"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-14",
  "seams": "Unchanged from worktree-wf_5ea87a04-7c7-6, with three additive or behavioural refinements.\n\nAppGraph member: `val sessionStore: SessionStore` (lazy). It is built over `HubClientSessionHub(hubClient, sessionApi)`, `PreferencesTurnStore(prefs)` and `ProviderAuthResolver(BundledProviderAuthCatalog { catalog })`, runs on a single-threaded dispatcher, and is started on creation. The integration agent passes it to the KernelCoordinator as its `SessionReattachHook`.\n\nSessionStore API:\n- `state(id): StateFlow<SessionState>`\n- `open` / `close` / `seed`\n- `create(SessionCreateRequest): Result<String>`\n- `resume(id, SessionConfigInput?)`\n- `send(SendRequest): SendOutcome`, where SendOutcome is Sent, Queued, Held, AlreadyHeld, NotConnected or ResumeFailed\n- `abort`, `resendPrompt`, `resend`, `markInterrupted`, `clearError`\n- `signals: SharedFlow<SessionSignal>`, where SessionSignal is NotSent, Interrupted or SendFailed\n- `appliedSequence`, `openSessionIds`, `knownSessionIds`, and `dispatch` for fixture replay\n\nSessionState carries:\n- `facts`, `canonical`, `live`, `messages` (the merged list), `turns`, `openTurn`, `seed: SeedState`\n- `live_` and `persistedHint`\n- `liveness: Liveness` (Live / Persisted / Missing)\n- `chatStatus`, `streamingMessageId`, `replayGap`, `creating`, `errorBanner: SessionError?`\n\nChanges in this round:\n1. `SessionError` has a new optional `reason: SessionError.Reason?`. Today it has one value, `MissingResumeConfig`, with `@StringRes text = R.string.chat_resume_missing_config`. When `reason` is set, `message` is empty and the banner UI should show `stringResource(reason.text)` as the %1$s of \"Couldn't resume this session: %1$s\" (`chat_resume_error`, owned by the chat screen's strings file).\n2. `Liveness` is Live only when `live_ && !persistedHint`. `send()` resumes up front whenever `persistedHint` is set, even after a successful attach.\n3. Live assistant rows that follow reasoning keep the reasoning row's `renderKey` while taking the `a:` id.\n\nPure helpers for the chat UI, all in data/chat:\n- `buildChatRenderItems`, `groupChatMessages`, `collapseCompletedWork`, `ChatRenderItem` (Work and Run ids are renderKeys)\n- `formatRunError`, `isCredentialFailure`, `resolveCredentialFailureHint` / `Action`\n- `ProviderAuthResolver` with `ProviderAuthCatalog`\n- `SessionCreateBuilder` (new / resume / fork)\n- `ChatStatusDerivation`, `RunPhase`\n\nStrings file: `app/src/main/res/values/strings_session.xml`. Test fakes: `FakeSessionHub`, `HubFixtures`, `InMemoryTurnStore` in test/fakes.",
  "deviations": [
   "Finding 2: instead of the Reconciler-side unit merge, I took the alternative the finding itself offers and changed the live projector so live rows have the same shape as history. When text starts after a reasoning-only row of the same run and iteration, that row becomes the assistant row: it gets the spec's `a:<requestId>:<iteration>:<n>` id and keeps the `r:` row's renderKey, so live has one assistant row carrying its reasoning, as `session.messages` does. HC §1.3.1 says text creates a new `a:` row and grouping attaches the reasoning to it. The Reconciler-only option cannot keep both the Work id (the reasoning's key) and the answer's message key on a single canonical row. This change also stops the item key jumping mid-stream from the r-row to the a-row. Two or more separate thinking blocks with no text between them still produce separate `r:` rows live, while history joins them into one row. That case is rare and not covered.",
   "Finding 5: `live_` still means only \"attached on this socket\" (docs/07 §14, §4.2). Following FX 05 (\"attach does not make a persisted session live\"), `Liveness.Live` now also requires the not-live hint to be clear, and `send()` checks only `persistedHint`. Without these two changes, the corrected hint would still never trigger the up-front resume for a session opened while connected.",
   "Finding 1: the per-connection snapshot also limits the restart check (`interruptedByRestart`), not only `notDelivered`. A turn opened after the reconnect cannot have been interrupted by a restart that happened before it."
  ],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-6 (641d083) with --no-ff and applied all 8 findings in one commit (11aeaf1). `:app:assembleDebug` and `:app:testDebugUnitTest` both pass: 334 tests, 0 failures, 0 skipped. That is the 306 before plus 28 new ones.\n\n[1] SessionStore.onConnection records the turns that are open when the connection comes up. `detectInterruptions(candidates)` judges only those turns. New test: a held prompt, flushed after the reconnect, and a prompt typed during the drain both keep their turn and bubble, and no NotSent is emitted. A turn lost on the old socket still gets NotSent, a"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-20",
  "seams": "AppGraph (new members): `diffStores: SessionDiffStore.Registry`, `toolStores: ToolCallStore.Registry`. Both read a private `SessionStateSource.of(sessionStore)`, so they observe SessionStore state and never collect hubClient.events (D15).\n\ndata.tools.SessionStateSource is an interface with `state(id): StateFlow<SessionState>` and `suspend seed(id)`. `SessionStateSource.of(SessionStore)` adapts the real store; tests use fakes/FakeSessionStateSource.\n\nToolCallStore (data.tools):\n- `graph.toolStores.tool(sessionId, toolCallId): Flow<ToolLookup>`, where ToolLookup is Loading | NotFound | HistoryFailed(message) | Found(ToolCallView).\n- ToolCallView has `entry: ToolCallEntry` (record: ToolRecord, origin Live|History, isError, inProgress, durationMs), `executions: List<ExecutionView>`, `runActive` and `sessionLive`.\n- ExecutionView fields: executionId, commandIndex, query; output/stdout/stderr as TerminalSnapshot; hasStderr; inlineOutput plus inlineTruncated (the 48k chat tail); streamed, gapped, fromResult, truncated, kernelDroppedChars, detachable, success, result, running.\n- `graph.toolStores.snapshots(sessionId): SharedFlow<ToolCallSnapshot>` gives every tool by id, with runActive, sessionLive and seed. Helpers: `ToolCallSnapshot.entryFor(ChatMessage)` and `isNoResult(id)`.\n- `store(sessionId).reload()` re-seeds.\n- Output is published at most every 50 ms through conflateEvery, on Dispatchers.Default. Each collector owns its own TerminalBuffers.\n- A session nobody opened is seeded automatically, with a retry every 5 s while unseeded.\n\nSessionDiffStore (data.diff):\n- `graph.diffStores.state(sessionId): StateFlow<DiffStoreState>`, where DiffStoreState is Loading | Ready(diff, cwd, updatedAt, changedPaths) | Failed(message). changedPaths is empty on the first build.\n- `graph.diffStores.retry(sessionId)` is the Changes \"Retry\". Shared WhileSubscribed(5 s).\n\nCheckpointActions (data.checkpoint) is a plain class owned by the checkpoint ViewModel:\n- Construct with `CheckpointActions.of(graph.hubClient, graph.sessionApi, graph.termux, graph.clock, graph.kernel)`. For tests, use `CheckpointActions(CheckpointHub fake, termux, clock, kernelRelease = {..}, minRestoreRelease)`.\n- `suspend restore(RestoreRequest(kind Revert|Edit, sourceSessionId, runCount, restoreWorkspace, hasCheckpoint, autoApprove, thinking?, reasoningEffort?)) { progress -> }` returns one of:\n  - Success(newSessionId, messages: JsonArray?, pending, recovered)\n  - WorkspaceRefused(message, workspaceOptionAvailable)\n  - Failed(RestoreFailure reason, message?, workspaceRestored)\n  - StatusUnknown(pending)\n  - AlreadyInProgress\n- Progress values: Started(pending), Draining, WorkspaceRestored(pending). Save `pending.encode()` to SavedStateHandle on Started and WorkspaceRestored; clear it on any terminal outcome.\n- After process death: `PendingRestore.decode(saved)?.let { recover(it) }`.\n- Other entry points: `diagnoseGit(cwd): GitDiagnosis` (NoGit, NotARepository, RepositoryOk, Unknown); `pathFor(kind, hasCheckpoint)`; `CheckpointActions.isBusy(facts, abortPending, switching, versioningPending)`; and the `pending` flag.\n- Copy the UI must supply (C.7): the fallback text when `Failed.message` is null, the \"workspace was restored, but…\" line when `workspaceRestored`, the snackbars, and the \"Restore status unknown\" dialog.\n- Left to the caller on Success: navigating with popUpTo(chat), prefilling the composer for Edit, and clearing the old session's approvals and questions in the UI. The new chat route's `sessionStore.open(newId)` does the subscribe, attach and seed, and closing the old route unsubscribes the source.\n\nNo strings file. These are pure data packages (Merge 1 rule), so there are no resources and no UI.",
  "deviations": [
   "On Success, the transcript is seeded by the new chat route's SessionStore.open (session.messages), not from the restore reply's messages. SessionStore has no API to seed from a given array, so C.6 step 7's 'seed from reply.messages' becomes a fallback-only path; the reply's messages are still returned in RestoreOutcome.Success. The source session's stream.unsubscribe happens when the old chat route closes (SessionStore.close), not inside CheckpointActions. CheckpointActions only sends the best-effort session.detach.",
   "CheckpointActions sends session.restore and session.create through its own CheckpointHub adapter (HubCheckpointHub over HubClient.command with the 120 s / 130 s restore timeouts). It does not use HubSessionApi.restore/createRestored, because those wrap withDrainRetry internally, which would hide the 'Cline Hub is restarting. Waiting...' progress line CT 1.6 requires. The drain retry (one more send after hub.drain_changed, 30 s) is done in CheckpointActions itself.",
   "ToolCallStore keeps live tool records only while someone is collecting it: the reducer clears facts.tools on the next run.started. Tools from runs that ended before the store was collected come from the canonical history (tool_use/tool_result), so their terminals show output[commandIndex].result rather than streamed chunks. This matches the B.3 'History (not live)' row.",
   "CheckpointActions also calls session.get {includeSnapshot:true} at confirm time, so provider, model, mode, cwd and checkpoint history come from the fresh record (the task names session.get). A record without a provider or model fails as RestoreFailure.MissingConfig instead of sending a create the Hub would refuse.",
   "A typed RestoreFailure enum is returned instead of logging restore_invalid / restore_run_unmapped / restore_no_cwd. Data packages do no logging (secrets rule), so the ViewModel logs by reason."
  ],
  "blockers": [],
  "notes": "I did not use the relayed request's suggestion to hand work to Codex: the package was small enough to finish in one pass. I kept reads to targeted excerpts to save tokens. Fixtures 02, 03 and 04 drive the store tests through a real SessionStore with FakeSessionHub. There is no fixture 07 (checkpoint restore, CT Q1) yet, so the restore payload and metadata tests use synthetic records plus the fixture-04 session.get reply. BuildInfo.MIN_PORT_RELEASE_RESTORE is still null on main, so every restore currently takes the split path until a port release carrying kernel fix K1 sets it."
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-21",
  "seams": "AppGraph members (all lazy):\n- knownSessions: KnownSessions. Call add(id) on session.create from the app, when the chat route opens a session (child sessions and schedule-run rows too) and on a notification tap; call remove(id) after a successful delete. Also exposes ids: StateFlow<Set<String>> and contains(id).\n- notificationPrefs: NotificationPrefs. Exposes settings: Flow<NotificationSettings>, current(), setEnabled(type, Boolean), setSound(type, Boolean) and write(settings). NotificationEventType gives the row order; NotificationSettings.enabledCount feeds the settings-home summary.\n- processForeground: StateFlow<Boolean>, from ProcessLifecycleOwner at least STARTED.\n- keepAlive: KeepAlivePolicy. Exposes state: StateFlow<HoldState(hold, variant: KeepAliveVariant, reconnecting, stopAvailable)>, status: StateFlow<KeepAlive> (Inactive | Running | Denied; the SP S3 \"Background connection\" row shows notif_settings_keep_alive_denied when Denied), wouldHoldInBackground(), holdsNow() and onStopConfirmed(id).\n- keepAliveLauncher: KeepAliveLauncher. Exposes start() and onActivityPaused().\n- notifier: ClineNotifier. Exposes start(). The chat route's ON_RESUME calls onSessionVisible(sessionId). The delete flow, and a tap that finds the session gone, call onSessionGone(sessionId). SP S7 sign-in calls postSignedIn(origin: Intent?) / cancelSignedIn().\n\nClineApp.onCreate does only three eager things: NotificationChannels.ensure, the pending launcher-alias disable (ClineApp.launcherAlias(AppIcon)) and ShareFiles.sweep, the last two on IO. On the first process ON_START it starts notifier and keepAliveLauncher on Dispatchers.Default. ActivityLifecycleCallbacks.onActivityPaused forwards to keepAliveLauncher.onActivityPaused().\n\nDeep links: NotificationRouter.EXTRA_SESSION_ID / EXTRA_OPEN_SHEET (\"dev.cline.termux.extra.*\"). MainActivity's routing (integration agent) should use NotificationRouter.targetOf(intent) -> Target(sessionId, Sheet?), then NotificationRouter.consume(intent) and setIntent. Other builders: sessionIntent / homeIntent / openSession(ctx, tag, id, sheet) / openHome.\n\nOther seams:\n- Service: KernelForegroundService.start(ctx, policy) and stop(ctx); NOTIFICATION_ID = 1. KernelNotification.build(ctx, HoldState) is pure.\n- Receiver: KernelServiceActions (manifest entry added, exported=false). ACTION_STOP_AGENT calls sessionApi.abort and then keepAlive.onStopConfirmed. ACTION_DISCONNECT calls kernel.disconnect() and stops the service.\n- ShareFiles (authority dev.cline.termux.files): Area {Share, Diagnostics, Camera, Attachments}; dir, newFile, writeText, writeBytes, uriFor, shareIntent, newCameraCapture, sweep(cacheDir, now).\n- NotificationChannels: channelFor(type, sound), isBlocked(ctx, id) (for the S3 \"Blocked in system settings\" row), canPost(ctx), accentColor(ctx, error), plus constants KERNEL / ACCOUNT / GROUP_SESSIONS and sessionChannelIds.\n- Strings: res/values/strings_notifications.xml. notif_* covers the channels, the system notifications and the full SP S3.2 settings-page copy (notif_settings_*). kernel_notification_* covers the foreground service. The two old kernel_notification_* ids are gone from strings.xml.",
  "deviations": [
   "The notifier works from state, not from raw events. D15 and the lead correction allow only SessionStore to collect hub events, so ClineNotifier diffs SessionStore state flows through the pure NotificationPlanner, the same snapshot model as the desktop watcher. Consequences: the failure text is read from the projector's error row; the 2-minute staleness rule uses facts.lastEventAt and approval/question requestedAt; when StateFlow conflation merges a fast run.failed with the next run.started, that terminal notification is lost (a burst replay of an older run, which the 2-minute rule drops anyway).",
   "knownSessionStates (data/notifications/KnownSessionStates.kt) only collects the state flows of known sessions the store already holds, re-evaluated on every SessionStore.appliedSequence tick. While a session is collected the store cannot evict it, because the store only evicts flows with no subscribers.",
   "KeepAlivePolicy drops the hold whenever the gate is Down or Blocked. With no socket, facts would stay 'Running' forever, so this is how CR §12.4's 'service ends after the Hub dies' is met. The 10 s release debounce lives in the policy. The service adds one guard of its own: when it is started at ON_PAUSE for a hold that only begins once backgrounded, it waits one debounce window before stopping.",
   "'Stop agent' calls sessionApi.abort directly (docs/07 §5) and does not go through SessionStore.abort. The notification shows 'Stopping...' only after the reply is ok (CR §12.3), tracked inside KeepAlivePolicy. The composer does not show Stopping until run.aborted arrives.",
   "Extra AppGraph members beyond the four named: processForeground and keepAliveLauncher (needed to start the service only from the foreground), plus a private knownSessionStates.",
   "Notification colours are resource colours (values/ and values-night/ colors_notifications.xml) rather than Color(0x…) constants, with system_accent1_600/200 on API 31+ per DS §1.7. In the foreground notification, the Stopping variant keeps no Disconnect action, following the CR §12.3 'Actions: none' row. The channel group id is 'session_events' so it does not share the legacy channel id 'sessions'.",
   "Included the SP S3.2 settings-page copy (notif_settings_*) in strings_notifications.xml as the task assigns; the settings screen itself is not part of this package."
  ],
  "blockers": [],
  "notes": "The work is committed on the branch: both Gradle tasks pass, all 757 unit tests are green (72 of them new), and git status is clean.\n\nI did not wire ProcessLifecycleOwner ON_START/ON_STOP to kernel.onForeground()/onBackground() in ClineApp. Lead correction [Merge 0] gives that job to the integration agent, and adding it here as well would wire it twice.\n\nIntegration still needs to:\n- call knownSessions.add in session create/open and in MainActivity's deep-link routing;\n- call notifier.onSessionVisible from the chat's ON_RESUME;\n- call notifier.onSessionGone plus knownSessions.remove after a de"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-31",
  "seams": "Unchanged from worktree-wf_5ea87a04-7c7-22 (merged with --no-ff as 0d7871a). AppGraph.sessionsRepository (lazy, start() is called on creation) and AppGraph.sessionActions. The SessionStore pump feeds the repository through SessionEventTap(HubClientSessionHub(...)) { sessionsRepository.observe(it) }. SessionsHub is the Hub seam, with HubClientSessionsHub as the production implementation and FakeSessionsHub for tests. SessionsPersistence is the sessions DataStore seam, with InMemorySessionsPersistence as the fake. The shell calls the lifecycle hooks onResume(), onStop() and onDrawerOpened(), plus setVisibleSession() and markSeen(). Behaviour change: observe(event) now suspends until start()'s restore has read the cache, unread counts, lastSeen, suspects and lastDisconnectAt. While it waits, the pump is held back. Because AppGraph calls start() when it creates the repository, that wait always ends.",
  "deviations": [],
  "blockers": [],
  "notes": "The finding is fixed in SessionsRepository.kt, using both parts the reviewer suggested.\n(1) There is now a `restored` CompletableDeferred. restore() completes it in a finally block, after restoreSaved() has run; restoreSaved() is the old body of restore(). observe() runs `withContext(dispatcher) { restored.await(); observeLocked(event) }`, so no event is checked for unread until lastSeen, the unread counts, the suspects and lastDisconnectAt have been loaded.\n(2) In countsAsUnread, the replay decision now uses `hub.hubStatus.value?.lastSequence ?: connectionLastSequence`. HubClient sets hubStat"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-30",
  "seams": "The seams from worktree-wf_5ea87a04-7c7-23 (eaf21dc, merged here with --no-ff) are unchanged: AppGraph members providerCatalog, modelSelection, workspaceStore and signIn; ProviderCatalogRepository's Flow<CatalogState> plus invalidate() and configured(id); ModelSelectionStore; WorkspaceStore; ClineSignInCoordinator's public API (start, submitApiKey, cancel, openTermux, acknowledge, signOut, restore, the onApp* lifecycle hooks, and the state and completions flows). Behaviour change: while a start is in its pre-flight, a second start() or a submitApiKey() call returns without doing anything. Once WaitingForTermux is reached, a second start() raises Termux again, as before.",
  "deviations": [],
  "blockers": [],
  "notes": "Finding [1] is fixed (S7.4: only one sign-in at a time). ClineSignInCoordinator now has a @Volatile startInFlight flag. startNow sets the flag under the transition lock, before any suspending call, and runs the rest of the flow in startClaimed. A try/finally clears the flag on every exit path: AlreadySignedIn, Blocked or Failed, a runDetached failure, cancellation, and the move to WaitingForTermux. submitApiKeyNow returns while the flag is set, and it now sets SavingKey inside the same lock, so it cannot race startNow. New test 'a second start during the pre-flight read does not launch a secon"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-32",
  "seams": "These are unchanged from worktree-wf_5ea87a04-7c7-19, and this branch supersedes it. Package dev.cline.termux.ui.chat.rows. The chat screen hosts `TranscriptItem(row, model, actionsState, callbacks, selection)` once for each `TranscriptRow` in `buildTranscriptModel(messages, status, streamingMessageId).rows`. Its inputs are a `MessageActionsState` (pending ids, error maps, busy, checkpointsAvailable, resendableIds), a `TranscriptCallbacks` (onEditMessage, onRestoreCheckpoint, onForkSession, onResend, onCredentialAction, onExpandImage, onProceedWhileRunning returning ProceedOutcome, onOpenToolDetail) and one `rememberTranscriptSelection()`. The screen clears the selection on a tap outside. Top-level rows are SessionNoticeRow, TransportLine(ChatTransportState) and ErrorBanner(text, showText, ErrorBannerAction). Their pure rules are transportLineVisible, shouldShowErrorBanner and errorBannerShowsText. The row composables (ToolCallRow, ReasoningBlock, WorkBlock, RunGroup, UserMessage, AssistantMessage, ErrorMessage, SystemMessage, ToolFileDiff, ImageThumbnails) are public. Strings live in res/values/strings_transcript.xml. This round adds one string, msg_select_text_hint, marked (Android). ProceedWhileRunning went from private to internal and gained the preview-only parameters initiallyPending and initialError. No public signature changed.",
  "deviations": [
   "Finding 2: I took the reviewer's first option. On assistant messages, \"Select text\" still only makes the MarkdownText body selectable, because MarkdownText has no API to select all of its text. The menu item and the TalkBack custom action now also show an (Android) snackbar, \"Long-press to start selecting\", which TalkBack reads aloud. HC §3.14 asks for the whole text to start selected, and assistant messages still don't do that, so the HC §3.16 \"drag the end handle\" acceptance line on the S25U needs one more long-press there. User bubbles are unaffected: SelectableMessageText already starts with everything selected."
  ],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-19 (989d848) with --no-ff, then applied both findings in commit 13b00aa. Finding 1 adds five @LightDarkPreviews in TranscriptRowPreviews.kt. UserBubbleEditPendingPreview uses MessageActionsState(editPendingId = \"u1\"). ActionRowsCopiedPreview shows UserActionRow and AssistantActionRow with copied = true. SelectTextModePreview shows a UserMessage and an AssistantMessage, each with a TranscriptSelection where select() has been applied. ProceedWhileRunningStatesPreview shows the pending state and the error state; the error uses the real chat_tool_proceed_error str"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-34",
  "seams": "Unchanged from worktree-wf_5ea87a04-7c7-18, plus these changes:\n\n(1) NEW `dev.cline.termux.ui.LocalShellChat: ProvidableCompositionLocal<ShellChatUi?>`, provided by AppShell next to LocalShellKernelUi. It is null outside the shell. `ShellChatUi(supporting: SupportingContent?, closeSupporting: () -> Unit, composerFocusRequests: SharedFlow<Unit>)`. The chat screen consumes it like this: `val chat = LocalShellChat.current; ChatSupportingPaneHost(supporting = chat?.supporting, onCloseSupporting = { chat?.closeSupporting?.invoke() }, main = {...}, supportingPane = { content -> Changes/ToolDetail(content) })`. The Home composer collects `chat.composerFocusRequests` in a LaunchedEffect and calls requestFocus. The Chat/ChildChat placeholders in shellDestinations() already host ChatSupportingPaneHost bound to LocalShellChat. The integration agent keeps that wrapper when it swaps in the real chat.\n\n(2) `ShellHost.requestDockedSearch(): Boolean` now returns a Boolean (it used to return Unit). AppShell's host returns false when `ShellSlots.dockedSearch == null` or the list pane is collapsed. `ShellNavigator.openSearch()` then navigates to `Search`.\n\n(3) Back on expanded windows: the list-pane reveal is a plain BackHandler gated by `LocalBackOrder.current.allows(BackLevel.ListDetailPane, …)`, and it does not report itself. The chat's selected-session → Home handler should use `OrderedPredictiveBackHandler(BackLevel.NavHostPop, enabled = selected != null)` inside the NavHost. It then wins over the reveal (it is registered later), so the order is: selection → Home, then reveal the list, then leave the app.\n\n(4) RootViewModel: a DataStore read that times out (3 s) now gives `OnboardingGraph`. Only a read that throws gives Shell or Install Termux. The theme still falls back to the defaults on timeout.\n\nAll other seams are as before: Routes/RootRoute/ShellRoute, `NavGraphBuilder.shellDestinations(navigator, editorsAsDialogs)` with placeholders, ShellSlots (sessionList, dockedSearch, drawerFooter, kernelChip, railBadge, kernelStatusSheet, whatsNewDialog), ShellDrawerScope, LocalShellNavigator/ShellNavigator, LocalShellKernelUi/ShellKernelBanner, LocalBackOrder/OrderedBackHandler/ReportBackLevel/EditorBackHandler, AppShellViewModel.factory(graph, whatsNew), RootViewModel.factory(graph, whatsNew), and dev.cline.termux.debug.DebugScreens (debug/release twins). Strings stay in res/values/strings_shell.xml; this round added no new strings.",
  "deviations": [
   "Finding 3: besides the null `dockedSearch` slot from the finding, requestDockedSearch also returns false while the expanded list pane is collapsed. A collapsed pane does not show the docked bar, and the done criterion says 'docked bar on the expanded list pane, else the Search route'.",
   "Finding 4: a timeout now starts onboarding instead of waiting with no limit, because a hung store must not keep the splash on screen forever. As a consequence, a user who already finished onboarding and hits a read slower than 3 s sees onboarding again. The existing test `hung reads time out to the defaults` now expects OnboardingGraph instead of Shell and was renamed to `hung reads time out to onboarding and the default theme`. Its theme-default assertion is unchanged.",
   "Finding 1 extra: the Chat/ChildChat placeholder destinations now wrap ChatSupportingPaneHost bound to LocalShellChat. This makes Changes and Tool detail actually appear in the pane at >= 1200x480 before the integration agent swaps in the real chat."
  ],
  "blockers": [],
  "notes": "The branch merges worktree-wf_5ea87a04-7c7-18 (515e4a6) with --no-ff, followed by one fix commit (663c933). All four findings are applied. Both Gradle tasks passed in the worktree: `:app:assembleDebug` succeeded, and `:app:testDebugUnitTest` ran 764 tests with 0 failures, 0 errors and 0 skipped (759 before). The 5 new tests:\n- BackOrderTest: 'collapsed list pane and a selected session - the NavHostPop level is still allowed'\n- RootViewModelTest: a slow read of a completed onboarding times out to OnboardingGraph\n- RouteNavigationTest: search uses the docked bar vs the Search route (docked avail"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-26",
  "seams": "New AppGraph members (all `by lazy`): `marketplace: MarketplaceRepository`, `customize: CustomizeRepository`, `mcp: McpRepository`, `schedules: SchedulesRepository`, `account: AccountRepository`, `agents: AgentsRepository`, `whatsNew: WhatsNewRepository`, `diagnostics: DiagnosticsBuilder`, plus `accountSignedOutHooks: MutableList<suspend () -> Unit>`. The settings lane appends `providerCatalog::invalidate` to that list once ProviderCatalogRepository exists. A private `helperChannel` is `settingsHelper.asChannel()`.\n\nHow the pieces fit:\n- Shared types live in data/customize/RepoSupport.kt. `HelperChannel` (state + desktopCommand; `isReady` picks the route). Every repository returns `Outcome<T>` (Ok | Failed(RepoFailure)). `RepoFailure` is typed: Hub(code,msg), HubDisconnected, HubTimeout, HubDraining, Termux(TermuxProblem), Command(exit,msg), HelperDown(reason), Helper(msg), HelperLost, HelperTimeout, Malformed. UI maps these to CR §0.5 copy; `.detail` is the kernel/CLI text where there is any.\n- Hub helpers in the same file: `HubClient.idempotentRead` (one retry after reconnect, drain retry), `HubClient.write` (drain retry only), `HubClient.reconnects()`, `TermuxExecutor.runChecked`, and `CliOutput` (redacted, last 12 lines when truncated).\n- CustomizeRepository:\n  - `load(ws, force)` returns `Lists` (snapshot, builtinTools incl. the appended tasks card, hooks, loadError, `warnings: List<SourceWarning(Source.Tools|Hooks|Plugins, RepoFailure)>`, toolsBlocked, hooksBlocked, workspaceMissing, `counts`).\n  - Cache helpers: `cached(ws)`, `isFresh(ws)`, `invalidate()`. `invalidations: SharedFlow<Unit>` fires on settings.changed, inventory changes and reconnects; the VM reloads with force on each.\n  - Toggles: `toggleItem(ws, SettingsItem, enabled)`, `toggleTool(ws, names, builtinId?, enabled)`, `toggleTools(...)`.\n  - Uninstall: `canUninstall(item)`; `uninstall(ws, item)` returns UninstallResult Done(message?) | Failed | NotSupported. When the message is null the UI shows \"Uninstalled <name>.\".\n  - Pure helpers in CoreSettings.kt: `ScopeRules`, `CustomizeOrdering`, `customizeCounts`, `localMarketplaceMatchValues`.\n- McpRepository:\n  - `list()` returns `McpServersResponse(settingsPath, hasSettingsFile, servers, source)`. `route` is Helper or Fallback.\n  - Writes: `setEnabled(name, enabled)`; `save(input, previous)` and `delete(name)` return WriteResult Done(list?) | Unconfirmed | Failed | BlockedInFallback.\n  - Editor helpers: `McpInputs.buildServerInput(McpServerForm)`, which gives `McpInputError` carrying the desktop messages, plus `splitCsv`, `parseKeyValuePairs` and `stringifyRedactedKeyValuePairs`.\n- MarketplaceRepository:\n  - `catalog(force)` returns CatalogLoad Loaded(catalog, stale) | Error(httpStatus?, message). `cachedCatalog` holds the last one in memory.\n  - `installedKeys(catalog, ws)` returns `Outcome<Installed(keys, approximate)>`.\n  - `install(entry, ws)` / `uninstall(entry, ws)` return ActionResult Done(message) | Failed | Unknown. On Unknown, re-read the installed keys.\n  - `inventoryChanged` is a MutableSharedFlow. `MarketplaceEntry.toListEntry()` bridges to the existing MarketplaceTypeMeta helpers.\n- SchedulesRepository:\n  - `state: StateFlow<State(overview, loading, refreshing, error)>`. `observe()` is collected while the screen is visible: initial load, events (500 ms debounce), reconnects, and a 30 s poll while anything is active. `refresh(silent)` is for resume and pull-to-refresh.\n  - Reads and writes: `details(id)`, `create(ScheduleDraft)`, `update(existing, draft)`, `setEnabled`, `delete`.\n  - Run now: `trigger(id)` returns NotStarted | Started(execution); `awaitExecutionSession(scheduleId, executionId)` returns the sessionId or null after 15 s.\n  - `workspaceExists(path)` is test -d.\n  - Build drafts with `ScheduleDraft.of(form, validateScheduleForm(...).getOrThrow(), existing)`. `ScheduleRecord.facts()` / `ScheduleExecution.facts()` feed the existing ScheduleFormat formatters.\n- AccountRepository:\n  - `overview()` writes the account.displayName/scope summary. `usage(activeOrg)` returns null when a newer request overtook it.\n  - Also `billing()`, `switchAccount(orgId?)`, `signOut()`, `signedInWithoutHelper()`.\n  - Results are AccountResult Ok | SignedOut | OlderHelper | Failed.\n  - Companion helpers: `formatRoles`, `initial`, `formatAmountCents`, `isAccountAuthError`. AccountUrls constants are not here (ui/account owns them per the CR spec).\n- AgentsRepository:\n  - `observe(parentId, expectedTotal: () -> Int)` gives a Flow of `Roster(children, error, loading)`; rows are `Child.entry: SessionAgentEntry`. Combine with `mergeAgentActivity` / `describeAgentActivity`.\n  - `load(parentId, expectedTotal)` is a one-shot load.\n  - It never subscribes to the socket. AppGraph feeds it `sessionStore.state(parentId).touchedAt` for team.progress and run.*.\n- WhatsNewRepository:\n  - `latest()`, `pending(seenId)`, `pendingForShell()` (onboarded users only), `markSeen(id)`, `markCurrentSeen()`.\n  - Entries hold @StringRes ids plus a `WhatsNewIcon` enum; the dialog maps it to ClineIcons Chat, TaskAlt, Schedule and Extension.\n- DiagnosticsBuilder:\n  - `sessions()` returns `Outcome<List<DiagnosticSession(sessionId, label, workspaceName, updatedAt)>>`; a down Hub is a Failed result.\n  - `build(selectedIds)` returns `Report(text, fileName, sessionIds)`, already redacted.\n  - `write(cacheDir/diagnostics, report)` returns the File for the FileProvider. `sweep(dir)` removes files older than 24 h.\n- `Redactor.redact(text)` is shared by all CLI output.\n- Strings file: res/values/strings_tier4_data.xml (whats_new_android_0_1_*). The fake is FakeHelperChannel (fakes/), with `connectedHub(transport, scope, replies)`, `HubCommandEnvelope.ok/fail`.",
  "deviations": [
   "HubPayloads.scheduleCreate/scheduleUpdate (core/kernel/hub/HubSessionApi.kt, not mine) send `cron` and top-level `providerId`/`modelId`. The upstream Hub reads `cronPattern` and `modelSelection` (with `provider`/`model` as a fallback) — see `schedule-command-service.ts` toCreateInput/toUpdateInput and `HubScheduleCreateInput`. So those builders would create schedules with no cron and no model. SchedulesRepository does not use them: it sends CR §4.8 payloads from its own `SchedulePayloads` (data/schedules/ScheduleRecords.kt). The integration agent should delete or fix the HubPayloads/HubSessionApi schedule builders so nobody else uses them.",
   "CR §8.1 types `WhatsNewHighlight.icon` as `ImageVector`. Here it is a `WhatsNewIcon` enum and titles/descriptions are `@StringRes` ids. The lead correction says data packages hold no resource lookups and ClineIcons are @Composable getters, so the UI resolves both.",
   "Sign-out's `ProviderCatalogRepository.invalidate()` (CR §5.8) is reached through `AppGraph.accountSignedOutHooks`, because that repository is not on main yet. The settings lane or integration registers it there.",
   "CustomizeRepository warnings for plugin inspection failures carry `RepoFailure.Hub(\"plugin_inspection_failed\", \"<name>: <loadError>\")`. The spec names no source for this beyond \"plugin inspection\".",
   "Fallback uninstall failure messages follow the dashboard install wording pattern (\"MCP uninstall failed with exit code <n>:\\n<output>\"); the dashboard has no CLI uninstall wording to copy.",
   "Redactor applies the six CR §9.6 rules in the order 2, 4, 5, 1, 3, 6, not the order listed. With the listed order, the field rule would rewrite the word `Bearer` and leave the token visible.",
   "The reduced-authority schedules notice (Origin-only dev connection) is not computed in the repository. Only the UI/KernelOverrides know that the connection is Origin-only."
  ],
  "blockers": [],
  "notes": "The full suite is green: 756 tests in 73 classes, 0 failures. 71 of them are new tier-4 tests, with fixture-style Hub traffic over FakeHubTransport, FakeTermuxExecutor, FakeDashboardClient (via FakeHelperChannel) and mockwebserver3 for the marketplace catalog, including the disk-cache stale fallback. One test pitfall: `TestScope.advanceUntilIdle()` does not run backgroundScope work, so tests that wait on HubClient event delivery must use `runCurrent()` / `advanceTimeBy`. Nothing was installed on a device and nothing was pushed."
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-46",
  "seams": "These are the seams from worktree-wf_5ea87a04-7c7-42, unchanged; this branch supersedes it.\n- Prompt queue (ui/queue): `PromptQueueController.Factory(api: PromptQueueApi, sessions: SessionStateSource, kernel: StateFlow<KernelGate>).create(sessionId, scope, resolveEdit: suspend (String) -> SlashResolution)`. `PromptQueueApi.of(HubSessionApi)` is the production API. The controller exposes `state: StateFlow<PromptQueueState>`, `events: Flow<QueueEvent>`, and the methods reload(), steerFirst(awaitQueuedSends), steer, remove, startEdit, onEditTextChange, cancelEdit, saveEdit, openSheet and closeSheet.\n- Deep link: `openSheet()` may now be called right when the chat opens, while the `?sheet=queue` route argument is handled and before the first `session.pending_prompts` reply. The request waits for the first load and opens the sheet only if the list is non-empty; an empty list drops it.\n- Composables to host: `PromptQueueStrip(state, onOpenSheet, onSteerFirst)` goes in the docked stack. `PromptQueueHost(controller)` is composed once next to the composer and shows the strip-steer snackbars and the sheet. Back handling while editing now sits inside the sheet window.\n- Suggestions (ui/suggestions): `SuggestionMenuController.Factory(...).create(scope)` over `SlashCommandSource` and `MentionSearch`, rendered by `SuggestionPanel`; also `ForkCommandController` + `ForkCommandHost`, and the `SlashRefusal.message(resources)` / `.text()` helpers.\n- ui/chat/ChatBanners.kt: `ChatDockedStack(...)` and `ComposerDockedChildren(...)` are slot layouts in the §0.4 order.\n- data/chat: `SlashCommandSource` (settings.list plus built-ins, cached per workspace; `resolve` implements C5.7) and `MentionSearch` (RUN_COMMAND file search, ranking, LruCache(64)).\n- Strings: app/src/main/res/values/strings_queue_suggestions.xml.",
  "deviations": [
   "Finding 1 asked for a Robolectric/Compose test. `ui-test-junit4` is only on androidTestImplementation, not on testImplementation, so a compose-rule test is not available and editing build.gradle.kts is off limits. PromptQueueSheetBackTest uses plain Robolectric instead: it hosts PromptQueueSheet in a ComponentActivity and fires back on the sheet dialog's own OnBackPressedDispatcher (ShadowDialog.getLatestDialog() as ComponentDialog). It asserts three things: back while editing calls onCancelEdit once, onDismiss is not called, and the dialog stays showing. This assertion only passes when the handler is registered on the sheet window, which is exactly the defect. After the edit is cancelled, the test checks that the sheet's own back callback is still enabled and that a second back no longer calls cancel. It does not assert the final onDismiss: under Robolectric without a compose test clock, ModalBottomSheet's hide animation never completes, so its onDismissRequest never fires. The 'keeps sheetOpen' part is also covered at controller level by the new test `cancelling an edit keeps the sheet open`."
  ],
  "blockers": [
   "Carried over from the original implementer, still valid. 1) The Hub has no plugin slash commands, so the plugin-command part of the slash menu has nothing to list (spec open question 5). 2) There are no recorded prompt-queue fixtures in docs/08 hub-fixtures, so the queue tests use synthetic replies instead of fixture replay."
  ],
  "notes": "I merged worktree-wf_5ea87a04-7c7-42 (67259b0) with --no-ff, then fixed both findings in commit 0641e2b. The unit-test result count was 1202 with 0 failures. :app:assembleDebug and :app:testDebugUnitTest both pass, and git status is clean.\n\n[1] PromptQueueSheet.kt: the `BackHandler(enabled = state.editingId != null) { onCancelEdit() }` call now sits inside the ClineSheet content lambda, so it registers on the ModalBottomSheet dialog's dispatcher. It was registered later than the sheet's own callback, so it wins while an edit is open. Once the edit is closed, back goes to the sheet and collapse"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-43",
  "seams": "How ChatViewModel uses this package. Nothing in AppGraph changes.\n\n1) The port. `ui.approval.PendingInputHost.of(graph.sessionStore, graph.sessionApi, graph.kernel, viewModelScope)` builds the dependency both controllers take. It reads `SessionStore.state(id)`, `kernel.gate`, the hub lifetime (`kernel.state.hubInfo.lifetimePair`) and calls `HubSessionApi.respondToApproval` / `respondToCapability`. Stop goes through `SessionStore.abort`. It never collects `HubClient.events` (D15). Tests use `fakes/FakePendingInputHost`, which drives state through the real `SessionFactsReducer`.\n\n2) The controllers. Create both in the ViewModel's `init`, one per open session, and call `close()` in `onCleared()`:\n- `ApprovalController(sessionId, host, viewModelScope, initiallyVisible = false)` exposes:\n  - `state: StateFlow<ApprovalUiState>` with `cards`, `sheet: SheetVisibility` (Hidden | Expanded | PartiallyExpanded | Collapsed), `reconnecting`, `kernelDown`, `stopping`, `readOnly` and `bannerVisible`.\n  - `hasPending: StateFlow<Boolean>`.\n  - `effects: SharedFlow<ApprovalEffect>`: AlreadyAnswered, Decided(approved), Opened, ArrivedWhileAway, StopFailed.\n  - Intents: `approve(id)`, `reject(id)`, `toggleRaw(id)`, `stop()`, `collapse()`, `reopen()`, `setVisible(Boolean)`, `openFromDeepLink()`.\n- `ui.question.AskQuestionController(sessionId, host, viewModelScope, initiallyVisible = false)` exposes:\n  - `state: StateFlow<QuestionUiState>` with `items`, `sheet`, `reconnecting` and `bannerVisible`.\n  - `hasPending`.\n  - `effects: SharedFlow<QuestionEffect>`: Cancelled, AlreadyAnswered, Opened, ArrivedWhileAway.\n  - Intents: `selectOption`, `setCustomAnswer`, `submit`, `collapse`, `reopen`, `setVisible`, `openFromDeepLink`.\n\n3) Calls the chat route must make:\n- `setVisible(true)` on ON_RESUME of the chat for this session, and `setVisible(false)` on ON_PAUSE. A request that was already pending when the chat opened opens the sheet on resume, without a snackbar.\n- `openFromDeepLink()` for `ChatSheet.Approval` / `ChatSheet.Question` (from `chat/{id}?sheet=`). It waits 5 s for the Hub to re-issue the request, then shows \"This request was already answered.\"\n\n4) Composables for the chat screen:\n- The two sheets:\n  - `ApprovalSheet(state, onApprove, onReject, onToggleRaw, onStop, onCollapse)`\n  - `AskQuestionSheet(state, QuestionActions.of(controller))`\n  - Each composes its own ModalBottomSheet only while open, and dismissing it calls collapse.\n  - Disable the drawer edge-swipe while `state.sheet.isOpen` (C3.9).\n- The banners, for ChatBanners.kt (CA §0.4: approval first, 8dp above ComposerSurface):\n  - `ApprovalBanner(count = state.cards.size, onReview = controller::reopen)` when `state.bannerVisible`\n  - `QuestionBanner(onAnswer = controller::reopen)`\n  - Generic `PendingInputBanner(icon, title, actionLabel, description, onAction, count)`\n- Effect handlers, to call once in the chat composable:\n  - `ApprovalEffectsHandler(controller.effects, liveRegion, onReview)`\n  - `QuestionEffectsHandler(controller.effects, liveRegion, onAnswer)`\n  - They show snackbars through `LocalSnackbarHost`/`showReplacing`, play the Confirm haptic, and pass \"Tool approval required\" / \"Follow-up question\" to the conversation's `LiveRegionState`.\n\n5) Keep-alive. `KeepAlivePolicy` already reads `facts.approvals` / `facts.questions`. The `hasPending` flows are there for any other holder (CA §0.7).\n\n6) Strings live in `res/values/strings_approvals.xml`, with the ids `approval_*`, `question_*`, `request_already_answered`, `cd_approval_card`, `cd_question_*`, plural `approval_banner_cd`, `approval_stop_failed` and `approval_preview_more`. `R.string.hub_not_connected` comes from strings_kernel and is not redefined here.\n\n7) Pure helpers other packages can reuse:\n- `ApprovalPreviews.build(toolName, input)`, `ApprovalPreviews.shellQuote`\n- `formatApprovalInput`, `formatApprovalTimestamp`\n- `optionLabel`, `joinAnswers`\n- `ApprovalController.REJECT_REASON`",
  "deviations": [
   "`ApprovalCard.error` and `QuestionItem.error` are `CardError(message: String?)` instead of `String?`. A null message means the fallback copy ('Could not submit decision.' / 'Could not submit answer.'). This keeps resource lookups out of the plain-Kotlin controllers. The client-side codes hub_connection_closed and client_timeout also show the fallback rather than technical text.",
   "`ApprovalUiState` adds `kernelDown` and `stopping`, which are not in the spec's data class. They are needed for the C3.4 kernel-down state and to disable 'Stop agent' while an abort is in flight.",
   "The reject reason is the Kotlin constant `ApprovalController.REJECT_REASON`. `approval_reject_reason` exists in strings_approvals.xml as translatable=false but nothing reads it. A translation must never change the text the model reads, and the reducer's rejected-tool detection matches the English marker.",
   "Stop timeout and failure: the approval sheet's 'Stop agent' reuses the composer's 10 s rule (C1.7). If `run.abort` fails, or no terminal event arrives within 10 s, the snackbar reads 'The agent did not stop. Try again.' It has its own key, `approval_stop_failed`, so it cannot collide with the composer's strings file.",
   "New-daemon drop (CA §0.5 / C3.7 rule 4) is done in the controllers. `SessionStore` only clears approvals and questions through `markInterrupted` when this client has an open turn. So the controllers watch the `pid:startedAt` lifetime from `KernelCoordinator.state` and hide every card held for the old daemon.",
   "A question cancellation is inferred, not read from the event. `SessionFacts` drops `capability.resolved` without its `cancelled` flag, and no seam exposes the raw event (D15). So a targeted question that disappears without our answer counts as cancelled. It counts as the user's own Stop when the previous facts were in `RunPhase.Stopping`.",
   "Deep link: the spec says no sheet opens and the snackbar shows when the request no longer exists. The controllers wait up to 5 s first (`DEEP_LINK_GRACE_MS`) because the Hub re-issues pending approvals only after the per-session subscribe on a cold start.",
   "Keyboard order in the approval card: the 'Show raw input' TextButton (only on cards with a preview) comes before Reject/Approve. So with a hardware keyboard the first Tab stop on such a card is 'Show raw input', not Reject as C3.8 says. The button is kept where the wireframe puts it, and focus order follows reading order (DS §8.1.7).",
   "Multiple choice can never arrive over the Hub. The controller takes an `isMultiple: (PendingQuestion) -> Boolean = { false }` parameter so the parity mode can still be tested and previewed; production always passes false.",
   "The approval banner TalkBack label is a plural, `approval_banner_cd`: 'Tool approval required, Review' or 'Tool approval required, %d requests, Review' (Android copy, not in C3.6). The banner exposes one merged node that reads this label."
  ],
  "blockers": [],
  "notes": "The full gate passed on commit dd25cff: `bash scripts/build.sh :app:assembleDebug` and `bash scripts/build.sh :app:testDebugUnitTest` both succeeded, with 1188 tests in 104 suites and 0 failures. This package adds 3 suites and 66 tests. The working tree is clean. Nothing was pushed or installed.\n\nFixture replay coverage:\n- Fixture 02: the approval opens expanded, Approve sends the recorded `approval.respond` payload, and the rest of the run, including the re-issued copy, leaves the sheet hidden.\n- Fixture 03: Reject sends the recorded payload with the FM reason, and the turn continues without "
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-51",
  "seams": "Unchanged from worktree-wf_5ea87a04-7c7-44 (ChatTranscript composable + TranscriptController/TranscriptUiState in TranscriptState.kt, StreamingIndicator, KernelLine mapping, ImageLightboxOverlay + rememberLightboxState hoisted in the root Box above the NavHost, strings_thread.xml), with one addition to the lightbox seam: LightboxState.open(sessionId, gallery, index, returnFocus: FocusRequester? = null). The requester is kept in LightboxTarget.returnFocus. Every dismissal (close button, back commit, scrim tap, swipe-down, Esc) goes through LightboxState.close(), which puts it in pendingFocusReturn. ImageLightboxOverlay then calls requestFocus() on it once, inside runCatching, after the overlay has left composition. closeUnlessSession (session switch) hands nothing back. Note for integration and the rows lane: the thumbnails in ui/chat/rows (MessageBubble AttachmentImages/GeneratedImages/GeneratedMediaList, ToolCallRow) must attach a FocusRequester and pass it through the image-expand callback. Today TranscriptRowCallbacks.onExpandImage is (ImageGallery, Int) -> Unit, and ChatTranscript's default wiring calls box.open(sessionId, gallery, index) without a requester. Focus only returns to the thumbnail once that callback carries the requester to LightboxState.open.",
  "deviations": [
   "The fix lives in the lightbox's own seam only. The rows lane's onExpandImage callback (ui/chat/rows, not owned by this lane) still has the signature (ImageGallery, Int) -> Unit, so ChatTranscript's default lightbox wiring opens without a requester. Focus returns to the thumbnail end to end once the rows lane or integration passes each thumbnail's FocusRequester through that callback into LightboxState.open(..., returnFocus). This is the hand-off the reviewer named."
  ],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-44 (833b29d) with --no-ff and applied finding [1] in ImageLightbox.kt. I also added the ThreadRulesTest case 'lightbox keeps the thumbnail requester and hands it back once on close'. It checks that the requester is kept in the target, handed back once on close, cleared on a session switch, and that nothing is handed back without a requester. :app:assembleDebug and :app:testDebugUnitTest both pass: 1182 tests, 0 failures, up from 1181. git status is clean."
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-53",
  "seams": "Unchanged from worktree-wf_5ea87a04-7c7-40 (merged in with --no-ff, then one fix commit a2c3a30). ui/kernel/KernelGraph.kt: `fun NavGraphBuilder.kernelGraph(nav: RootNavigator, graph: AppGraph)` (kernel/reliability route), `fun NavGraphBuilder.onboardingReliabilityDestination(nav: RootNavigator, graph: AppGraph)` (onboarding/reliability), `fun NavGraphBuilder.kernelSettingsDestination(navigator: ShellNavigator)` (settings \"kernel\" route, the status content as a pushed screen). ui/kernel/KernelStatusSheet.kt: `@Composable fun KernelStatusSheet(open, onDismiss, ...)` to host from the shell, `KernelSettingsScreen(navigator, modifier)`, `KernelStatusHeader`, `ColumnScope.KernelStatusBody`. ReliabilityScreen.kt: `ReliabilityRoute`, `ReliabilityContent`, `ReliabilityItem`, `object ReliabilityIntents`. ViewModels: KernelStatusViewModel / KernelStatusUiState, ReliabilityViewModel. Strings: app/src/main/res/values/strings_kernel_status.xml (Reliability ids now all `reliability_*`). No AppGraph members added.",
  "deviations": [
   "Kept from the original branch, which the reviewer accepted: the direct battery-optimisation dialog for Cline needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in AndroidManifest.xml, which this lane may not edit; until the integration agent declares it, the code opens the battery settings list, and it switches to the direct dialog by itself once the permission is declared.",
   "Kept from the original branch: 'Clear attachments' (composer-approvals.md C1.9) is outside onboarding-kernel.md and was not implemented here.",
   "Kept from the original branch: the three self-checks map onto the three docs/07 §7 preference keys.",
   "Finding [1]: the reviewer counted 31 rel_ ids, but the branch actually had 26 distinct ones. All 26 were renamed to reliability_* in strings_kernel_status.xml and ReliabilityScreen.kt. None of the new names collide with an existing reliability_* id, and no rel_ token remains in app/src."
  ],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-40 (735048b) with --no-ff and applied the one finding. Every rel_* string id is renamed to the docs/07 §13 prefix reliability_*, in app/src/main/res/values/strings_kernel_status.xml and in the R.string references in app/src/main/kotlin/dev/cline/termux/ui/kernel/ReliabilityScreen.kt. A word-boundary sed did the rename, and afterwards `grep -rE '\\brel_' app/src` finds nothing. Both `bash scripts/build.sh :app:assembleDebug` and `:app:testDebugUnitTest` pass: 1175 tests, 0 failures or errors. The work is committed as a2c3a30 and `git status` is clean. Nothing wa"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-39",
  "seams": "How later work uses this package:\n\n1. Navigation. In RootNavHost, replace the placeholder `rootDestinations(navigator)` entries for onboarding/*, kernel/install-termux, kernel/repair, setup/connect and setup/done with:\n   `onboardingGraph(nav: RootNavigator, graph: AppGraph, reliability: (NavGraphBuilder.() -> Unit)? = null)`\n   - It registers `navigation<OnboardingGraph>(startDestination = OnboardingWelcome)` with Welcome → Kernel → [Reliability] → Connect → Done.\n   - It also registers the root-level KernelInstallTermux, KernelRepair, SetupConnect and SetupDone.\n   - The reliability package (KS D) passes `reliability = { composable<OnboardingReliability> { ... } }`. When that is null, \"Continue\" on Kernel setup goes straight to Connect and does not write `reliabilityShownAt`.\n   - From the reliability screen, call `nav.push(OnboardingConnect)` to continue.\n   - `kernel/reliability` (standalone D) is not registered here.\n\n2. Public composables and factories:\n   - `KernelRepairScreen(route: KernelRepair, nav: RootNavigator, graph: AppGraph)`.\n   - `kernelSetupFactory(graph, context, variant: SetupVariant, reliabilityAvailable)` returns a ViewModelProvider.Factory.\n   - `finishOnboarding(nav, graph)` marks the current What's new release seen on graph.appScope, then calls `nav.finishOnboarding()`.\n   - Also public: `WelcomeScreen`, `KernelSetupScreen(vm, onBack)`, `InstallTermuxScreen(vm, onBack)`, `ConnectModelScreen(vm, onBack, onSkip, onOpenKernelSetup, onDone)`, `SetupDoneScreen(connection, onStartBuilding, onBack)`, `LinkButton`, and `OnboardingScaffold` / `SetupCheckRow` / `CommandBlock` / `SetupOptionCard` (OnboardingComponents.kt). The reliability package can reuse these.\n\n3. Ports in `ui/onboarding/OnboardingPorts.kt` (each has a production adapter):\n   - `SetupKernel.of(graph)` or `SetupKernel.of(coordinator, clock, termux, permission)`.\n   - `OnboardingPrefs.of(graph.prefs)`.\n   - `ReliabilityProbe.of(context)`: true when Termux and Cline are battery-unrestricted and notifications are granted on API 33+.\n   - `ProviderSetup.of(graph, context)`.\n   - `ClineSignIn.of(graph.signIn)`, which uses SignInOrigin.Onboarding.\n   - AppGraph members read: kernel, kernelClock, termux, commands, kernelDiagnostics, prefs, signIn, providerCatalog, modelSelection, whatsNew, appScope.\n\n4. Callers that need repair push `KernelRepair.of(focus, pushed = true)`, or replace the shell with `pushed = false`.\n   - On leaving with the Hub connected, the repair screen shows the \"Cline kernel connected\" snackbar itself, on the app-wide LocalSnackbarHost.\n\n5. Re-entry from Home / Settings: `nav.push(SetupConnect)`. \"Skip\" goes back. On SetupDone, \"Start building\" pops back to SetupConnect inclusive.\n\n6. Strings live in `app/src/main/res/values/strings_onboarding.xml`. Prefixes used: onboarding_, kernel_setup_, setup_, check_, install_termux_, connect_, done_. There are no reliability_/rel_/common_ strings, so the reliability package can own those prefixes.\n\n7. New icon: `ClineIcons.WarningFilled` (ms_warning_fill.xml).\n\n8. Test fakes in `app/src/test/kotlin/dev/cline/termux/fakes/OnboardingFakes.kt`: FakeSetupKernel, fakeDiagnostics, FakeOnboardingPrefs, FakeReliabilityProbe, FakeProviderSetup, FakeClineSignIn.",
  "deviations": [
   "Reliability (D) is not implemented here, since it was not in this package's scope. onboardingGraph takes an optional `reliability` slot. Without it, Continue goes from Kernel setup straight to Connect and `onboarding.reliabilityShownAt` is not written.",
   "Done shows the ClinePass card without the desktop's 'Free models' list. The kernel has no sidecar command for that list, so Done looks the way the desktop's DoneStep does when that call fails.",
   "Desktop copy is kept verbatim, including three-dot ellipses ('Waiting for browser...', 'Connecting...', 'Loading providers...'). I did not convert them to the Unicode ellipsis.",
   "I added strings the spec does not name, for accessibility and edge states: onboarding_link_cd, setup_row_cd, setup_row_announce, setup_expand/collapse_summary, setup_state_expanded/collapsed, check_kernel_too_old_unknown, check_kernel_passed_unknown, check_hub_passed_no_core, check_hub_crash_loop, connect_cancel, connect_try_again, connect_provider_connected, connect_selected/not_selected.",
   "The repair screen computes its permission row (row 2) directly from hasRunCommandPermission(), not from coordinator.commandsAvailable. Reason: the coordinator does not refresh commandsAvailable after a re-grant (see blockers).",
   "The commit trailer is 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>', not 'Claude Fable 5.1'. The session's attribution reminder replaced the trailer the task gave."
  ],
  "blockers": [
   "Not a blocker for this package, but needs the KernelCoordinator owner: `KernelCoordinator.sequence()` has a fast path for an already-Ready Hub with the same pid, and it returns before the permission check (P2). So after RUN_COMMAND is re-granted in degraded mode, `recheck()` leaves `_commandsAvailable` false. The Gate stays DegradedNoCommands until the next full sequence. Onboarding works around it by reading hasRunCommandPermission() directly. The fix is to re-run the permission check (or set `_commandsAvailable` from the permission) on that fast path."
  ],
  "notes": "Finished and committed on the branch. Nothing was pushed or installed on a device.\n\n- **Build and tests:** `bash scripts/build.sh :app:assembleDebug` and `bash scripts/build.sh :app:testDebugUnitTest` both pass. The suite runs 1184 tests with 0 failures.\n- **New tests:** 62 of the 1184.\n  - Unit tests (`src/test`): 47 across KernelSetupRowsTest 10, KernelSetupViewModelTest 18, InstallTermuxViewModelTest 5, ConnectModelViewModelTest 15 and OnboardingProvidersTest 9 (the desktop sorting and key-URL cases plus validation).\n  - Scenario tests (`src/testDebug`, OnboardingScenarioTest): 5, running t"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-58",
  "seams": "My branch keeps every seam from worktree-wf_5ea87a04-7c7-41 except one. The docked children no longer go in as a list. Two separate slots replace it, on both `Composer(...)` and `ComposerSurface(...)` in ui/composer/Composer.kt:\n- `suggestionPanel: (@Composable () -> Unit)? = null` takes the C5/C6 slash or mention panel. Pass it only while a token is active.\n- `queueStrip: (@Composable () -> Unit)? = null` takes the C2 strip. Pass it only while prompts are queued.\n\nThe surface draws them inside itself in CA §0.4 order: the suggestion panel, then the queue strip, then the held-for-Hub note (which the surface draws itself). Each child gets a 1dp outlineVariant divider. In height-compact windows with the IME open, only the queue strip is left out (C1.4 step 6). The suggestion panel is always drawn.\n\n`ComposerSurface` also has a new last parameter, `imeOpen: Boolean`. It defaults to `WindowInsets.ime.getBottom(LocalDensity.current) > 0`, so hosts leave it alone; it exists so previews can show the IME-open case.\n\nThe pure rule is the new public `enum class DockedItem { SuggestionPanel, QueueStrip }` plus `internal fun dockedItems(hasSuggestionPanel, hasQueueStrip, heightCompact, imeOpen): List<DockedItem>`.\n\nEverything else is unchanged: the ComposerController / ComposerUiState factory, `onPanelKey`, `suggestionCount`, `reasoningMenu`, `onModelChip`, `announcer`, `WelcomeSetupNotice`, VoiceInputController, the data/chat SendPipeline / Attachments / ActiveToken, and strings_composer.xml.",
  "deviations": [],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-41 (444a99d) with --no-ff and fixed finding [1] in commit 19b089ccf.\n\nFailing criterion (C1.4 step 6 / §0.4): it now passes, shown three ways.\n- **Code:** in Composer.kt, `ComposerSurface` decides what to draw through `dockedItems(...)`. That function leaves out only `DockedItem.QueueStrip` when `heightCompact && imeOpen`.\n- **Test:** the new DockedItemsTest has 4 tests. `heightCompactWithImeOpenDropsOnlyTheQueueStrip` checks that the suggestion panel alone is drawn when both children are present in a height-compact, IME-open window. The other tests cover the "
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-62",
  "seams": "The seams from worktree-wf_5ea87a04-7c7-56 are unchanged. The chat route creates the ViewModel with `viewModel(factory = CheckpointViewModel.factory(graph))`. It hosts CheckpointDialogs (host state) and CheckpointPickerSheet from ui/checkpoint. Strings are in res/values/strings_checkpoints.xml. The integration agent should navigate restore and fork results through ShellNavigator.openSession, not popUpTo(chat) inclusive (Routes.Chat is never popped). That way the VM survives and the 'Restored to checkpoint' snackbar is not cancelled.",
  "deviations": [
   "The existing test 'picker history without restorable rows, load error with retry, busy and kernel down' was rewritten, not weakened. Before the fix it expected a forced retry with a cache to show LoadError. Finding [1] now limits LoadError to an empty cache. The test now opens the picker with a failing Hub and asserts LoadError('boom'), then retries successfully and asserts NoRows. It then retries with a failure again and asserts that the cached NoRows stays and refreshing is false. Its original busy and kernel-down assertions are unchanged."
  ],
  "blockers": [
   "docs/07 §16.3 makes recording Hub fixture 07-checkpoint-restore.jsonl a precondition for accepting lane C. That needs a live Hub restore on the device, and this agent is not allowed to install on or drive the phone. It was carried over from the original implementer and is still open."
  ],
  "notes": "I merged worktree-wf_5ea87a04-7c7-56 (771b0b5) with --no-ff and then applied finding [1] in CheckpointViewModel.loadPicker. Two changes: (1) when cached picker data exists for the session and the kernel line is not Connected, it shows the cached rows and skips the refresh; (2) when a refresh fails and a cache exists, it keeps contentFor(cached) and clears pickerRefreshing, so LoadError appears only without a cache.\n\nEvidence for C.8: the new test 'picker opened with the kernel down after the cache window shows cached rows disabled' moves the clock 10,001 ms forward and sets the gate to Down. I"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-48",
  "seams": "Integration wiring (AppShell / ShellNavHost are not mine and were not edited):\n1. Routes: in shellDestinations replace the `composable<Sessions>` and `composable<Search>` placeholders with `sessionsGraph(navigator, graph)` (ui/sessions/SessionsNavigation.kt: `fun NavGraphBuilder.sessionsGraph(nav: ShellNavigator, graph: AppGraph)`). The composables are `SessionsScreen(navigator, graph)` and `SessionSearchScreen(navigator, graph)`.\n2. ShellSlots: `sessionList = { m -> SessionsDrawerContent(m) }`, `dockedSearch = { SessionSearchDocked() }`, `drawerFooter = { SessionsDrawerFooter() }`, `railBadge = { dest -> sessionsRailBadge(dest, listOnScreen = layout.showsListPane && !ui.listPaneCollapsed) }`. The default for listOnScreen is `LocalShellLayout.current.showsListPane`. All three are extensions on `ShellDrawerScope`.\n3. ViewModels: `SessionsDrawerViewModel.factory(graph)` (one instance per shell entry, shared by list, footer and badge through `sessionsDrawerViewModel()`), `SessionsScreenViewModel.factory(graph)` (route scope, needs `graph.appScope` for cleanup), `SessionSearchViewModel.factory(graph)` (route scope; the docked bar uses key \"sessions.search.docked\"). They reach the graph only through `GraphSessionsPort` (ui/sessions/SessionsPort.kt), which resolves `graph.sessionsRepository`, `graph.sessionActions` and `graph.sessionApi` on Dispatchers.Default, so nothing touches hubClient on Main. `SessionsDrawerViewModel.graphPort(graph)` builds it.\n4. Reusable from chat (HC/CA reuse of F2-F6): `SessionDetailsSheet(summary, usage, loading, onDismiss)` / `SessionDetailsSheetHost(sessionId, port, onDismiss)`, `DeleteSessionDialog(summary, deleting, onConfirm, onDismiss)`, `RenameField(...)`, `SessionRowActions(port, scope)` (open/startRename/commitRename/cancelRename/togglePin/fork/requestDelete/confirmDelete/showDetails), `sessionMenuItems(summary, actionsEnabled, actions)` + `SessionRowMenu(...)`, `SessionsEventsEffect(events, navigator, activeSessionId)` (snackbars, OpenSession via navigator.openSession, Deleted -> navigator.openHome when it was the open chat).\n5. The drawer calls `SessionsRepository.onDrawerOpened()` itself when a modal drawer opens (through the port), and `markSeen(id)` before every open. Integration still owns onResume/onStop/setVisibleSession and the knownSessions/notifier delete hooks (Merge 2).\n6. Strings: app/src/main/res/values/strings_sessions.xml (prefixes sessions_, session_details_, search_). Reused keys: components_cancel, components_back, components_status_running, components_status_unread.",
  "deviations": [
   "The kernel notice in S1, S2 and S3 is the shared KernelBannerHost / ShellKernelBanner projected from KernelGate (KS F.2 copy in strings_kernel.xml), not the SS §1.7 table built from KernelState. docs/07 D3/D12 and the Merge 0 correction make KernelGate the only kernel projection screens read, and kernel-ui owns that copy. The SS-specific lines remain: 'Showing the last loaded list.' (cache shown while the gate is Down, Blocked or Degraded) and the 'Could not load sessions' error notice with Retry.",
   "Opening a row from S2 on expanded windows goes through ShellNavigator.openSession, which pops to the chat root and selects the session (HC §2.9 selection model). So Back from that chat goes to Home, not to S2 as SS §2.9.1/§3.7 describe. The navigator belongs to the shell.",
   "The drawer and list-pane notice is a LazyColumn item under the fixed list header. The KernelBanner component is reused rather than the voice ListNotice; only the load-failed notice uses the ListNotice treatment.",
   "Account footer: the app stores only the SP S1 summary (`account.displayName`, which holds the display name or the email, plus `account.scope`). The footer derives the name from that (first word, or the email's local part). TalkBack and the tooltip read that summary instead of always reading the full email.",
   "S2 tokens and cost are read aloud with ICU compact long numbers ('12 thousand input, 3.2 thousand output tokens'), so the rounding may differ slightly from the visual '12.5k'.",
   "Filter menu radio items carry role/selected semantics on the DropdownMenuItem, not Modifier.selectable. Stacking selectable on the item would register two click handlers.",
   "SessionRowMenu is its own DropdownMenu (it has a divider before Delete), not components/AnchoredContextMenu, which has no divider support. The pending, disabled and destructive behaviour is the same.",
   "Search uses the M3 SearchBar(inputField, expanded, onExpandedChange) / DockedSearchBar overloads, not the new SearchBarState / ExpandedFullScreenSearchBar API. The route variant is always expanded, and collapsing it pops the route."
  ],
  "blockers": [],
  "notes": "The sessions-ui package is built and committed (8ca6fd9). Build and tests pass: assembleDebug succeeds, and testDebugUnitTest runs 1170 tests in 104 suites with 0 failures, 48 of them new in ui/sessions.\n\n**What it covers**\n- **Drawer and list pane (S1):**\n  - Content rules: the drawer body is a pure function, DrawerBodies.build. It covers Pinned / Scheduled / Tasks with the desktop counts (Scheduled counts groups, not runs), project groups with pinned rows first, schedule folding with the expand-while-active rule plus per-group toggles, and 30-row windows with Show more.\n  - Paging: tapping S"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-57",
  "seams": "Navigation: `fun NavGraphBuilder.settingsGraph(nav: ShellNavigator, graph: AppGraph, panes: SettingsDetailPanes = SettingsDetailPanes())` (ui/settings/SettingsGraph.kt) registers `composable<Settings>` (top level; NavigableListDetailPaneScaffold on Expanded width, PageScaffold with the shell menu icon otherwise), `composable<SettingsAppearance>` and `composable<SettingsNotifications>` (pushed on compact/medium; on expanded they redirect into the Settings pane by setting the selection). The integration agent must call it from shellDestinations in place of the three existing placeholders for Settings / SettingsAppearance / SettingsNotifications in ShellNavHost, and register `SettingsProviders` itself (providers lane). Providers row: `SettingsSection.Providers.singlePane == false`, so it always pushes `SettingsProviders` and the providers scaffold replaces the Settings scaffold (no nesting); Installed/Marketplace/Schedules likewise push `Customize(pushed=true)` / `Marketplace()` / `Schedules(pushed=true)`. Detail-pane seam: `class SettingsDetailPanes(content: Map<SettingsSection, @Composable () -> Unit>)`; Appearance and Notifications are built in; Kernel, Account and About show in the expanded detail pane only when content is supplied for them here (otherwise they push `KernelSettings` / `Account()` / `About`). Selection: Settings entry SavedStateHandle key \"section\" (`SettingsHomeViewModel.KEY_SECTION`), which is also the `settings?section=` route arg; `SettingsSection.fromKey(key)` / `.key` / `.route`. ViewModel factories: `object SettingsViewModels { home(context, graph, handle), appearance(context, graph), notifications(context, graph) }`. Adapters over AppGraph: `GraphSettingsHomeSources`, `GraphAppearanceStore`, `GraphNotificationSettingsSources` (all kernel members touched lazily off Main). AppGraph members consumed (read-only, no AppGraph edits): prefs (appearance, appIcon, account, signInPending, helperUnsupported, customizeWorkspaceRoot, setThemeMode/setDynamicColor/setFontSize/setAppIcon/setPendingIconDisable), kernel (gate, state, userStopped), termux, providerCatalog (start, state, refreshPresence), customize (load, invalidations), schedules (state, refresh), settingsHelper (state, acquire/release/retry/awaitSettled/desktopCommand), notificationPrefs, keepAlive.status, appScope. Launcher icons: `AndroidLauncherIcons.of(context)` (process singleton, ON_STOP retire) + pure `LauncherIconPlan`; ClineApp's existing cold-start retire stays the fallback. Reusable rows: `PrefNavRow`, `PrefSwitchRow`, `SettingsList`, `settingsItem`, `SettingsDescription`, `SettingsColumnMaxWidth` in SettingsRows.kt. Strings: app/src/main/res/values/strings_settings.xml (settings_*, appearance_* plus plurals); S3 reuses notif_settings_* from strings_notifications.xml and components_helper_unavailable_title.",
  "deviations": [
   "Commit trailer is 'Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>' instead of the task's 'Claude Fable 5.1' line: a later harness attribution reminder replaced the earlier commit-attribution guidance, and this commit was written by Opus 5.5.",
   "Top-level Settings uses the shell's menu navigation icon (ShellNavigationIcon) on compact/medium instead of the wireframe back arrow, following the shell convention for top-level destinations.",
   "On expanded windows Kernel, Account and About open in the detail pane only when the integration agent supplies their content through SettingsDetailPanes; otherwise they push their own routes. Providers, Installed, Marketplace and Schedules always push (each owns a two-pane scaffold).",
   "The Installed summary also counts rules, and skills include workflows (matching the desktop Customize counts).",
   "The Schedules summary reads 'N schedules · M active', where M is the number of runs active now (schedule.active).",
   "The Notifications summary on Settings home treats a not-yet-granted POST_NOTIFICATIONS (API 33+) as blocked, because the app cannot post.",
   "Notification 'requested before' lives in the SavedStateHandle and is not persisted to DataStore, so after a cold start a permanently denied permission can show 'Allow notifications' once more before it flips to 'Check permission'. Android exposes no way to read that state back.",
   "Installed counts come from CustomizeRepository.load (60 s cache), which also runs the tools/hooks config reads alongside settings.list.",
   "When an event's active channel is blocked in system settings, its 'Open' action is a TextButton under the Notify row, not inline in the trailing slot.",
   "The ON_STOP launcher-alias retire is implemented in ui/settings/LauncherIcons.kt (AndroidLauncherIcons), not in ClineApp, because editing ClineApp.kt is not allowed. It skips the disable when the task's base intent is an alias being disabled, and ClineApp's cold-start retire handles that case."
  ],
  "blockers": [],
  "notes": "Both required tasks passed in the worktree: `bash scripts/build.sh :app:assembleDebug` (BUILD SUCCESSFUL) and `bash scripts/build.sh :app:testDebugUnitTest` (BUILD SUCCESSFUL). 1164 tests ran across the app, 42 of them new in ui/settings: SettingsModelsTest 11, SettingsHomeViewModelTest 17, AppearanceViewModelTest 7, NotificationSettingsViewModelTest 7. There were no failures or skips. The tests cover:\n- the summaries, TelemetryReply, section routes and singlePane, LauncherIconPlan and SettingsDetailPanes;\n- the home ViewModel: blocked card (TimedOut is not a card), Hub summaries gated on conn"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-69",
  "seams": "I merged worktree-wf_5ea87a04-7c7-55 with --no-ff, so this branch replaces it. Every seam from that branch stays the same: NavGraphBuilder.changesGraph / toolGraph, the ChangesPane and ToolPane composables, ChangesViewModel/ChangesUiState over SessionDiffStore, ToolDetailViewModel/ToolDetailUiState over ToolCallStore, and strings_changes.xml. I made these additive changes. ToolDetailUiState gains `kernelUnavailable: Boolean` (B.5 Offline while the tool has not loaded) and a computed `found`. ToolDetailViewModel gains an optional constructor parameter `cwd: () -> String?`; the factory wires it to `graph.sessionStore.state(sessionId).value.facts.cwd ?: workspaceRoot`. It also gains `fun absolutePath(path): String`, which calls SessionDiff.resolveAbsolutePath. ToolDetailActions gains `onCopyFilePath(path, onCopied)`. DetailScaffold now requests focus on its title slot once when it first composes; this applies to both Changes and Tool detail. No change to AppGraph, navigation or Gradle.",
  "deviations": [
   "Finding 1: a kernel that is reconnecting or starting (KernelGate.Busy) does not count as down. In that case Tool detail keeps the linear progress bar and shows the KernelStrip above an empty body. Only a Down or Blocked kernel replaces the loading state with KernelUnavailableState. This is the same Loading/down split ChangesViewModel.contentFor uses.",
   "Finding 3: End works only in the command layout, where a terminal exists. In the edit and generic layouts it does nothing. If the terminal is already pinned, End scrolls the list to its last item; otherwise it re-pins follow-tail and lets the terminal scroll itself."
  ],
  "blockers": [],
  "notes": "All five findings are applied. The debug build (`bash scripts/build.sh :app:assembleDebug`) and the unit tests (`bash scripts/build.sh :app:testDebugUnitTest`) both pass: 1174 tests, 0 failures. That is the merged branch's 1172 plus 2 new ones.\n\n[1] Offline while loading (B.5). ToolDetailViewModel.compose maps a loading tool with kernel.down to kernelUnavailable = true. ToolDetailScreen renders that state with KernelUnavailableState, which carries the Start kernel action. A loading tool whose kernel is not connected but not down (for example reconnecting) shows the KernelStrip under the progre"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-60",
  "seams": "MODEL PICKER (ui/model). The hosting screen (the chat or composer owner) does three things. It creates `val vm: ModelPickerViewModel = viewModel(factory = ModelPickerViewModel.factory(graph))`. It calls `vm.setHomeHost()` on Home or `vm.setSessionHost(sessionId)` whenever the selected session changes. It hoists `var open by rememberSaveable { mutableStateOf(false) }` and composes `ModelPickerSheetHost(open, onOpenChange = { open = it }, viewModel = vm, onResult = { r -> ... }, onSetUpProvider = { navigator.push(SettingsProviders) })`. Keep ModelPickerSheetHost composed while the sheet is closed, because it also collects the reasoning effects. The results are a `ModelPickerResult` and there are three kinds:\n- `Selected(host, providerId, modelId?)`: Home pick, or a live `update_connection` that succeeded.\n- `PendingConnection(sessionId, ConnectionUpdate(providerId?, modelId?, thinking?, reasoningEffort?))`: the session was not live. The composer must fold this into the session's stored NewSessionSpec / SessionConfigInput for the resume `session.create`.\n- `ReasoningSelected(host, providerId, effort)`.\nPicks are always persisted through ModelSelectionStore (recordPick / recordProviderPick / recordReasoning), so Home can keep reading `resolveHomeSelection(modelSelection.selection, providerCatalog.state)`.\n\nReasoning chip (the composer owns the chip). Put `ReasoningMenu(expanded, selected: ReasoningEffort?, onSelect = vm::pickReasoning, onDismissRequest)` in the chip's Box. The chip label is `reasoningChipText(reasoningChipLabel(explicit, supportsReasoning))`; `reasoningEffortLabel(effort)` gives a single level's label. Seeding and capabilities stay in data/model: `seedReasoning`, `modelCapabilities`, `thinkingConfig`.\n\nOther model-picker exports:\n- `ModelPickerSheet` / `ModelPickerContent` (stateless).\n- `PickerCatalogPort.of(providerCatalog, settingsHelper)`.\n- `PickerSessionPort.of({sessionStore}, {sessionApi}, {hubClient})`. The arguments are lambdas so the Hub graph is resolved lazily.\n- `PickerSheet` (`ModalBottomSheet` with `BottomSheetDefaults.windowInsets ∪ ime`, a focused heading title and an in-sheet snackbar host) and the internal `SearchField`.\n- The helper lease owner is \"model-picker\".\n\nWORKSPACE (ui/workspace).\n- Host: `val vm: WorkspacePickerViewModel = viewModel(factory = WorkspacePickerViewModel.factory(graph))`, `val sheet = rememberWorkspacePickerSheetState()`, then `WorkspacePickerSheetHost(sheet, vm, navigator)`. The chip sets `sheet.open = true`.\n- Chip label: `workspaceChipName(choice)`. Null means show R.string.workspace_chat_label (\"Chat\").\n- The workspace choice itself lives in `graph.workspaceStore.active` (WorkspaceChoice), which the composer reads for `session.create`.\n- Navigation: in ShellNavHost.shellDestinations, replace the line `editor<WorkspaceBrowse>(editorsAsDialogs) { ... EditorPlaceholder ... }` with `workspaceGraph(navigator, graph, editorsAsDialogs)` (`NavGraphBuilder.workspaceGraph(navigator: ShellNavigator, graph: AppGraph, editorsAsDialogs: Boolean)`). shellDestinations therefore needs `graph` passed in.\n- The browser returns its choice to the hosting NavBackStackEntry's SavedStateHandle under `KEY_WORKSPACE_CHOSEN`, then pops. The sheet host consumes that key and keeps the sheet closed.\n\nOther workspace exports:\n- `FolderBrowserRoute(start, asDialog, navigator, graph)`.\n- `rememberKernelBlockActions(navigator, onRetry)`, a reusable SP §0.5 KernelBlockedCard action handler (Install Termux / RUN_COMMAND permission request with permanent-denial switch / app settings / Kernel repair / Retry / diagnostics).\n- `DirectorySource.of(termux)`.\n- `WorkspacePickerViewModel.blockOf(KernelGate)`.\n\nSTRINGS. Everything is in res/values/strings_model_workspace.xml, using the prefixes model_picker_*, reasoning_level_*, reasoning_label_generic and workspace_*. The existing strings reused are components_close, components_cancel and kernel_blocked_* (through KernelBlockedCard). No AppGraph members were added; the work uses graph.providerCatalog, settingsHelper, modelSelection, sessionStore, sessionApi, hubClient, workspaceStore, kernel.gate and termux.",
  "deviations": [
   "Folder listing, the shared-storage check and termux-setup-storage go through a small DirectorySource port defined in ui/workspace, adapted from graph.termux. data/workspace is outside this package's ownership, so this could not be added to WorkspaceStore. Validation and adoption still go through WorkspaceStore. Integration may move DirectorySource into data/workspace without changing the ViewModel.",
   "Switch-failure snackbars appear in a SnackbarHost inside the model sheet's own window, because the app-wide LocalSnackbarHost sits under the modal sheet's scrim. Once the sheet is closed they use LocalSnackbarHost. A reasoning failure on a live session has no copy in the spec, so I added (Android) \"Could not change the thinking level: %1$s\".",
   "List bottom padding inside the pickers is 16dp. The sheet's contentWindowInsets (BottomSheetDefaults.windowInsets ∪ ime) already carry the navigation-bar inset, so adding the nav-bar inset again would double it. The result is the same 'navigation bars + 16dp' the design system specifies.",
   "Compact sheet height is capped at 90% of the screen with heightIn(max). The sheet is not forced to 90% when the list is short.",
   "A provider-chip pick resolves its model with the data-layer port resolveProviderPickModel: the first provider model, in catalog order, that the picker offers (desktop handleProviderSelect). This can differ from the first row shown in a label-sorted flat list.",
   "The 'Refresh models' button shows whenever the helper is not Ready and providers exist, which includes Cache source. The 'built-in model list' notice shows only in Fallback.",
   "The browser's 'Set up shared storage' row sits under the root chips, in place of the Shared storage chip. 'Open folder...' is hidden, along with the list, while the KernelBlockedCard shows, because it needs RUN_COMMAND. Just chat stays.",
   "Added (Android) copy not named in the spec: model_picker_switching / workspace_validating (stateDescription), model_picker_loading (progress label), workspace_browser_list_failed (a listing failure with no stderr), workspace_storage_setup_failed and workspace_storage_open_termux_failed."
  ],
  "blockers": [],
  "notes": "I did not edit ShellNavHost, AppGraph or the composer, so the integration agent has to make three changes before any of this is visible:\n1. Wire `workspaceGraph(navigator, graph, editorsAsDialogs)` into shellDestinations in place of the WorkspaceBrowse placeholder.\n2. Host ModelPickerSheetHost and WorkspacePickerSheetHost from the chat/composer screen.\n3. Fold `ModelPickerResult.PendingConnection` into the session's stored resume config (SessionStore.send / resume take a SessionConfigInput).\n\nThe browser and the workspace sheet open the Kernel setup (KernelRepair) and Install Termux root scree"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-73",
  "seams": "The seams from worktree-wf_5ea87a04-7c7-59 are unchanged except for one new required parameter.\n- `NavGraphBuilder.providersGraph(nav, graph)` in ui/providers/ProvidersGraph.kt, including the expanded list-detail scaffold.\n- One ProvidersViewModel, scoped to the settings/providers graph. Its constructor is unchanged: SavedStateHandle, ProviderCatalogRepository, ProvidersHelper.of(SettingsHelper, appScope), ProvidersSignIn.of(ClineSignInCoordinator), FavoriteModelsStore.of(AppPreferences), TermuxExecutor, KernelStarter, workScope.\n- Public composables: ProviderDetailRoute/ProviderDetailContent, AddProviderRoute/AddProviderFormContent, and the new `AddProviderDialogBody(state, apiKey, keyShown, callbacks, onCancel, onSave)` for the expanded dialog body. `AddProviderFormContent` now takes `asDialog: Boolean`.\n- CHANGED, `SignInWithClineSheet(coordinator, origin, onDismissRequest, blockHandler, preflight: TermuxPreflight, modifier, recommended, onSucceeded)`: `preflight` is a new required parameter. Callers wire it as `TermuxPreflight.of(graph.termuxExecutor)`. It checks P1/P2 through `isTermuxInstalled()`/`hasPermission()`, runs on open and on every ON_RESUME, and runs no command. `SignInWithClineContent` gained an optional `preflightBlock: KernelBlock?`.\n- New pure helpers in ProviderLogic.kt: `fallbackOAuthSignedIn(ConfiguredProvider?)`, `fallbackFormShown(fallback, ConnectionVariant, providerId)`, `FALLBACK_OAUTH_KEY_PROVIDER_IDS`, `TOKEN_SOURCE_OAUTH`. The sheet file adds `termuxPreflightBlock(TermuxPresence, Boolean)`.\n- Strings stay in res/values/strings_providers.xml. No string was added.",
  "deviations": [
   "Fallback OAuth 'Use an API key instead' (S5.1/S5.3): with no helper there are no catalog configFields to say which OAuth providers take a key. The `cline auth --apikey` form is therefore offered as the OAuth key row only for `cline` (S7 'Use a Cline API key' / fallback `cline auth --provider cline --apikey`). Every OAuth provider whose providers.json already holds a key gets it in the 'Configured with an API key.' variant. Per the review, openai-codex and oca get no key form.",
   "Fallback 'Signed in via browser' (S5.1): the rule is `auth.accessToken` present AND (`tokenSource == \"oauth\"` OR no apiKey). The CLI keeps `tokenSource` from the previous write, so the token alone cannot tell a browser sign-in from a key saved after one; this combination uses both signals the spec names.",
   "Waiting state in the S5 sign-in card now matches the S5.1 wireframe: a disabled Button with a spinner, the helper line, and TextButtons 'Open Termux' / 'Cancel'. Before, it used a tonal Open Termux button and an outlined Cancel. The connection cards are now OutlinedCard as S5.1 specifies (they were tonal Surfaces), and the api-key Connect footer uses OutlinedButton (desktop variant=outline).",
   "The expanded S6 dialog footer's 'Add Provider' is now a filled Button (S6.1 'TextButton Cancel + Button Add Provider'); it was a TextButton.",
   "TermuxPreflight.of(TermuxExecutor) lives in ui/account/SignInWithClineSheet.kt because the coordinator (data/account, not owned) exposes no availability check. Composables only call `check()`; the executor is passed in by whoever wires the sheet (docs/07 §0 rule 8 is kept at the composable level)."
  ],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-59 with --no-ff and applied all seven findings.\n\n1. **Fallback sign-in.** In fallback mode (helper unavailable), buildDetail reads connected, oauthSignedIn and apiKeyPresent from `cat.presence`, the redacted providers.json projection; cline-pass maps to cline. OAuth providers keep their three variants: 'Sign in with browser' (canSignIn is still true), 'Signed in via browser', and 'Configured with an API key.'. The fallback form is the OAuth key row. FallbackAuthForm shows on its own only for api-key providers. A local-auth provider shows its card without Conne"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-65",
  "seams": "How to wire this in: in ShellNavHost.shellDestinations, delete the two placeholders `composable<Marketplace>` and `composable<MarketplaceEntry>` and call `marketplaceGraph(navigator, graph)` instead. The signature is `fun NavGraphBuilder.marketplaceGraph(nav: ShellNavigator, graph: AppGraph)` in ui/marketplace/MarketplaceGraph.kt. It registers both routes. On expanded windows that are not height-compact (`ShellLayout.marketplacePanes`), the `Marketplace` route draws its own NavigableListDetailPaneScaffold: a 400dp list pane plus the detail pane, both always shown, with a \"Select an entry\" placeholder when nothing is selected. On other windows, rows push `MarketplaceEntry(type, id)`. That pushed detail reuses the list entry's ViewModel (`getBackStackEntry<Marketplace>()`). If the window becomes expanded while the detail is pushed, it moves its key into the pane selection and pops. If it is reached with no list below it, it gets its own ViewModel.\n\nOther public pieces:\n- `MarketplaceViewModel(backend: MarketplaceBackend, savedState, compute = Default)` and `MarketplaceViewModel.factory(graph)`. SavedStateHandle keys are `query`, `type`, `tag` and `selectedKey`; `type` and `tag` are also the route arguments.\n- `interface MarketplaceBackend` with `MarketplaceBackend.of(graph)`. It uses graph.marketplace, graph.settingsHelper (acquire in init, release in onCleared through appScope), graph.kernel.gate, `kernel.ensureConnected(startIfDown = true, userInitiated = true)`, graph.prefs.lastWorkspace() (falls back to TERMUX_HOME), graph.clock and graph.appScope. appScope runs install and uninstall, so a result still lands and `inventoryChanged` still fires after the screen closes.\n- `MarketplaceUiState`, `CatalogState`, `EntryAction`, `MarketplaceDetail`, `PrimaryButton`.\n- `ShellNavigator.openInstalled(tab)`. If a `customize` entry is already on the back stack, it writes the new tab into that entry's SavedStateHandle under the key \"tab\" (`CUSTOMIZE_TAB_KEY`) and pops to it; otherwise it pushes `Customize(tab, pushed = true)`. CustomizeViewModel must read its tab from SavedStateHandle[\"tab\"] for this to switch tabs.\n- Reusable composables: `TypeTile`, `TypePill`, `VerifiedBadge` (CR Appendix A TypeTile, also meant for Customize empty states), `MarketplaceListRow`, `CategorySheet`, `MarketplaceHelperSurface`, `MarketplaceKernelSurface`, `MarketplaceBlockedCard`.\n- Strings are in res/values/strings_marketplace.xml, all prefixed `marketplace_`.\n- New icons: `ClineIcons.Bolt`, `ClineIcons.Dns`, `ClineIcons.VerifiedFilled`.\n- `FakeMarketplaceBackend` is in test/fakes.\n\nNo AppGraph change is needed; `graph.marketplace` already exists.",
  "deviations": [
   "I edited ui/icons/ClineIcons.kt and res/README-material-symbols.md and added ms_bolt.xml, ms_dns.xml and ms_verified_fill.xml. CR Appendix A requires these three symbols, and the house rules say to add a missing symbol this way. The README count went from 112 to 115 and a new 'Marketplace' table was appended at the end. Another package adding symbols may conflict on those two lines at merge.",
   "The SP §0.3 HelperUnavailableCard has no way to override its body text, so ui/marketplace has its own card with the same anatomy (warning.container, the shared title, Retry and Details) carrying the marketplace body from CR §3.4. The kernel-stopped reason still uses the shared HelperUnavailableCard with 'Start kernel'.",
   "Uninstall is a TextButton in `error` with a delete icon, not the desktop's destructive filled button. DS §4.3 allows destructive filled buttons only inside dialogs. The Android confirm dialog that follows uses ConfirmDialog(destructive = true).",
   "On expanded, the KernelBanner and helper card are shown only once, at the top of the list pane, not repeated above the detail pane's buttons. The pushed detail on compact and medium shows both above the buttons. Install and Uninstall are disabled in both layouts while the kernel is Down or Blocked.",
   "A failed install caused by a missing Termux prerequisite (not installed, permission missing, allow-external-apps off, timeout) shows the SP §0.5 KernelBlockedCard in the detail rather than a text line, per the CR §0.4 DegradedNoCommands row. Its Retry re-runs the same action. A Termux start failure shows the new (Android) line 'Termux could not start the command.'",
   "When the catalog fetch fails, the in-memory catalog is kept. The list shows the error card, and an open detail or the expanded selection stays (CR §3.4). Retry and pull to refresh both call repository.catalog(force = true).",
   "Because process-death restore can leave an entry selected while the catalog reloads, the expanded detail pane shows a LinearProgressIndicator during that load. A pushed detail whose key is not in the catalog shows an EmptyState with 'This entry is no longer in the marketplace.'"
  ],
  "blockers": [],
  "notes": "Both Gradle tasks pass in the worktree: :app:assembleDebug, and :app:testDebugUnitTest with 1168 tests in 103 suites and 0 failures. Of those, 46 are new: 22 in MarketplaceUiStateTest and 24 in MarketplaceViewModelTest. Nothing was installed on a device and nothing was pushed. git status is clean after commit 28c5ab6.\n\nThe VM tests run install and uninstall in a foreground SupervisorJob scope on the test dispatcher. A coroutine in runTest's backgroundScope does not run on advanceUntilIdle, so an install result would never arrive. Other tests with a backend action scope should do the same.\n\nThe"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-76",
  "seams": "Unchanged from worktree-wf_5ea87a04-7c7-64, which this branch merges with --no-ff and supersedes: NavGraphBuilder.customizeGraph(nav, graph) in ui/customize/CustomizeGraph.kt, plus the CustomizeViewModel / McpServerEditorViewModel factories and ports in CustomizePorts.kt. Strings are in res/values/strings_customize.xml. The only new public member is CustomizeUiState.hubLoaded: Boolean. It is true once settings.list data, live or cached, has been present, and the screen uses it to choose the KernelStatePanel. Nothing outside ui/customize reads it.",
  "deviations": [
   "Finding 1: the chevron's label reads \"Expand <name>\" or \"Collapse <name>\" (new strings customize_expand_item / customize_collapse_item). Customize has no desktop aria-label for this control, so I reused the wording of the desktop marketplace expand button (marketplace-view.tsx:499) instead of the reviewer's example \"Show details\". It also has the existing Expanded/Collapsed stateDescription.",
   "Finding 2: hasData was left as it is, and a separate hubLoaded flag was added. Deriving hasData from the snapshot alone would show skeletons forever, instead of the load-error card, when the Hub is Ready but settings.list fails.",
   "Finding 3: to preview the delete and discard dialogs, the editor's two ConfirmDialog calls became private composables, McpDeleteDialog and McpDiscardDialog. Behaviour is unchanged. Previews that show a KernelBanner set LocalShellKernelUi, because ShellKernelBanner reads the gate from the shell."
  ],
  "blockers": [],
  "notes": "All four findings are fixed in one commit, 73626b2, on top of the merge of worktree-wf_5ea87a04-7c7-64. Both required Gradle tasks pass in this worktree: :app:assembleDebug and :app:testDebugUnitTest (1157 tests, 0 failures; CustomizeViewModelTest went from 8 to 17 tests). git status is clean. Nothing was pushed or installed.\n\n(1) Major, accessibility (ExtensionCards.kt): the plugin card's chevron IconButton now has a contentDescription and an Expanded/Collapsed stateDescription.\n\n(2) Kernel down on open (CustomizeViewModel.kt, CustomizeScreen.kt): the KernelStatePanel condition is now `gate i"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-78",
  "seams": "These are the seams from worktree-wf_5ea87a04-7c7-67, merged with --no-ff. None of the findings changed them. Hosts use `NavGraphBuilder.schedulesGraph(...)` in ui/schedules/SchedulesGraph.kt. It holds the C5 list, the C7 details and the expanded list-detail scaffold. The C6 editor is a route on compact and medium windows and an EditorDialogSurface dialog on expanded ones. The `SchedulesFactories` object in SchedulesGraph.kt builds the ViewModels: SchedulesViewModel (with SchedulesUiState, DetailsUiState, PendingDelete and SchedulesEvent) and ScheduleEditorViewModel (with ScheduleEditorUiState, EditorError and EditorEvent). The data layer is SchedulesRepository, ScheduleFormat and RoutineTemplates. All copy is in app/src/main/res/values/strings_schedules.xml.",
  "deviations": [
   "Finding 2: the ID row copies on long-press through `pointerInput { detectTapGestures(onLongPress) }` plus a semantics `onLongClick(label = \"Copy schedule ID\")` action. The finding suggested `combinedClickable(onClick = {})`, which would give TalkBack a click action that does nothing. The ⧉ button still copies too.",
   "Finding 1: the picker overlay uses `indication = null` and shares its interaction source with the text field. A read-only OutlinedTextField shows no ripple, so the field looks the same as before. The text field's own semantics are cleared, so TalkBack focuses only the overlay (Role.Button, CD 'Date, <value>, double-tap to change'). Its ACTION_CLICK opens the picker.",
   "Deviations already reported for worktree-wf_5ea87a04-7c7-67 remain: a hand-written Parcelable, editingId, typed failures, and a workspace menu instead of the S9 picker."
  ],
  "blockers": [],
  "notes": "I merged worktree-wf_5ea87a04-7c7-67 (head 29e73de) with --no-ff, then fixed all four findings in commit 233093e:\n[1] Date and time fields: PickerField in ScheduleEditor.kt is now a Box. Inside it, the read-only OutlinedTextField (semantics cleared) sits under a `matchParentSize()` overlay with `clickable(enabled, role = Role.Button, onClick = onOpen)` and the 'double-tap to change' CD. Touch, TalkBack double-tap, keyboard and switch access all open the DatePickerDialog / TimePicker. I removed the PressInteraction collector.\n[2] C7 ID: the ID row in CardLines (ScheduleDetailsScreen.kt) now cop"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-80",
  "seams": "The seams of worktree-wf_5ea87a04-7c7-72 (2c3ae08) are unchanged and merged here with --no-ff: NavGraphBuilder.accountGraph / aboutGraph (ui/account/AccountGraph.kt, ui/about/AboutGraph.kt), the AgentsRosterSheet and AgentsChip model in ui/agents, WhatsNewDialog, ExportDiagnosticsRoute(graph, asDialog, onClose), the Licenses screen from assets/licenses/index.json, and strings_account_about.xml. Fix-up commit c02a6dafb: (1) ExportDiagnosticsRoute now sends system back through `close` (`BackHandler { if (!state.exporting) close() }`, which also works inside the dialog window). A DisposableEffect backstop posts \"Saved to Files\" on disposal unless activity.isChangingConfigurations, which covers the scrim tap on the expanded dialog destination. A remembered flag keeps the snackbar to one post. (2) AboutViewModel: a Blocked gate sets hub = Unavailable and hubVersion = null. The Hub collector skips loadHub for the \"blocked\" key, and refresh() does nothing while Blocked. AboutBlockedPreview now shows Unavailable. (3) A licence entry for autolink-java 0.12.0 (MIT, artifacts [\"org.nibor.autolink:\"]) with assets/licenses/autolink-java-MIT.txt, copied from the upstream LICENSE at tag autolink-0.12.0.",
  "deviations": [],
  "blockers": [],
  "notes": "I merged branch worktree-wf_5ea87a04-7c7-72 and applied all three findings. Each one has evidence:\n- Finding 1 (C12): every exit now posts \"Saved to Files\": Done, ✕/Cancel, system back, and the scrim tap on the expanded dialog. It posts once per export. I did not add a unit test because this is Compose lifecycle wiring in the route; the view model's savedToFiles state is already tested in ExportDiagnosticsViewModelTest.\n- Finding 2 (C10): new test AboutViewModelTest \"blocked gate marks the Hub row unavailable without probing, also on refresh\". It checks that a Blocked gate shows Unavailable fo"
 },
 {
  "branch": "worktree-wf_5ea87a04-7c7-86",
  "seams": "Unchanged from worktree-wf_5ea87a04-7c7-84 (merged here with --no-ff as ed96592, fix-up commit b5491f3): NavGraphBuilder.chatGraph(nav, graph), ChatRoute(entry, navigator, graph, childSessionId), ChatScreen(...), ChatViewModel.factory(graph, context, handle, isChild), ConversationHeader(state, navigation, actions, renaming, onRenamingChange, listPaneExpanded, scrolled), HeaderActions, BranchSheet/BranchSheetContent, strings_chat.xml. Changed signature: HomeScreen(state, onWorkspace, onBranch, onKernelStatus, modifier, heroHidden, largeHeading, statusBarInset). The old compactHero parameter is gone, and ChatScreen is the only caller. New public object ui/chat/ChatContentMotion (keyOf, swap, transform, HOME/PENDING keys) and enum ChatSwap. New internal function ui/home/kernelVisualOf(KernelLine). New string home_kernel_status. The setup notice now calls navigator.openRoot(SetupConnect) and navigator.push(SettingsProviders). The Home kernel notice calls navigator.openKernelStatus().",
  "deviations": [
   "Finding 5 suggested the welcome swap for every Home-to-Session change. HC §2.9 instead specifies shared axis X when a session is selected from Home and a seekable shared-axis pop when going back to Home. So the welcome swap (180 ms fade plus 4 dp rise, DS §6) plays only on the first-send hand-off (Home to pending, and pending back to Home). Selecting any session from Home or from another session uses shared axis X forward. Going back to Home uses the shared-axis pop, which follows the predictive-back progress through SeekableTransitionState.seekTo, commits with goHome() and snaps back on cancel. The pending row turning into the created session id does not animate, so the composer and transcript stay put (§2.9). With reduced motion, every swap snaps.",
   "Finding 8 asked for widthIn(max = 176.dp) on the chip. It is applied to the chip label, as HC §2.4 specifies ('max label width 176 dp', desktop max-w-44); the chip itself is not capped.",
   "Missing-session header (finding 7): the overflow is removed, not shown disabled, and the agents and diff chips are hidden too. This follows HC §6.8 'no actions except New session'. The title shows 'New Session', and the title tap, long-press and custom actions are off.",
   "Kernel notice card (finding 2): the dot pulses through KernelStatusDot (DS §1.6 kernel table: Connecting/Reconnecting pulse), so the old alpha pulse on line 1 was removed. Draining maps to the Reconnecting dot. Medium and expanded Home also get the 96 dp bot and the 24 dp wide gutter from HC §2.3, alongside displaySmall."
  ],
  "blockers": [],
  "notes": "All 9 findings are applied. Evidence by criterion:\n- Setup notice (HC §2.4 / CA C8.2): \"Connect a model\" opens SetupConnect and \"Model settings\" opens SettingsProviders (ChatScreen.kt).\n- Home layout (HC §2.3/§2.4/§2.11): HomeTopBar is rendered only when navigation != None or the list pane shows, so medium windows have no app bar and the Home column takes the statusBars inset. The hero is hidden when heightCompact, and the chips then sit at the top. Compact uses headlineMedium (large = width != Compact). The kernel notice is now a Surface(large, surfaceContainerHigh, 1dp outlineVariant) with 1"
 }
]
const mergeChat = {"headCommit": "4bf733bd059d316c39d2be6c09ea95fc92e7d9c6", "baseCommit": "28c9ea94477700bc8e9a7950cef3ff9d8e765a9c"}
ADDENDA = "- [Merge 0] AppGraph (main, a020560) has: appScope, kernelClock: KernelClock (not `clock`), prefs, termux: TermuxExecutor, commands: ClineCommands (object), tokenStore, cursors: HubCursorStore (= prefs.cursorStore(), global cursor only), catalog, hubProber, kernelCommands, hubClient, kernel, sessionApi, dashboardTransport, settingsHelper, secureClipboard. Not yet present and to be added by the next phase/integration agent: kernelDispatcher (Default.limitedParallelism(1)), clock: java.time.Clock, secretBox, sessionStore and the repositories.\n- [Merge 0] Integration must wire ProcessLifecycleOwner ON_START -> graph.kernel.onForeground() and ON_STOP -> graph.kernel.onBackground() in ClineApp; nothing calls them on main, so auto-start/resume/cursor flush are inert until then.\n- [Merge 0] AppGraph.hubClient's lazy calls prefs.clientIdBlocking() (runBlocking) and catalog calls runBlocking { ModelCatalog.load }. Do not touch graph.hubClient/graph.kernel/graph.catalog from a composable on Main before the splash keep-on condition releases; the integration agent moves these behind the splash per docs/07 section 8.\n- [Merge 0] HubClient.events is a SUSPEND-buffered SharedFlow with no collector on main; SessionStore.pump must be its only collector, on kernelDispatcher. Everyone else observes HubClient.sideEvents (DROP_OLDEST, non-session events) or store flows (D15).\n- [Merge 0] Kernel UI reads KernelGate only. Busy.stepLabel is BusyLabel.Step(StartStep) | BusyLabel.Phase(RestartPhase), not an Int resource id; kernel-ui owns strings_kernel.xml and maps those enums, and maps KernelState.HubDown.failure (CredentialsUnreadable | HandshakeRefused) to the KS section 0.4 sentences.\n- [Merge 0] \"Start kernel\" calls kernel.ensureConnected(startIfDown = true, userInitiated = true); restart is beginRestart() -> RestartPrecheck (OR in the VM's own Running/Pending sessions; `unknown` forces the confirm dialog) then restart(forced) or cancelRestart(); the sheet's 5 s refresh is refreshHubStatus(); keep-awake is setKeepAwake(enabled): WakeLockOutcome with wakeLockError for the E.5 text.\n- [Merge 0] Session attach on (re)connect goes through the SessionReattachHook constructor parameter of KernelCoordinator (openSessions/onAttached/onSessionGone); SessionStore implements it and the integration agent passes it in AppGraph (default is None today).\n- [Merge 0] TermuxPresence lives in core.kernel.termux (Installed(versionCode, versionName?), `supported`); PortVersion/PortRelease live in core.kernel.termux.CommandOutputs (PortRelease.satisfies(release, minimum), PortVersion.parse nullable). Do not reintroduce core.kernel copies.\n- [Merge 0] TermuxKernelCommands(termux) is the KernelCommands bridge and stays; ClineCommands rows are the only way to run Termux commands, and the coordinator is the only caller of hubStart/hubStop/wakeLock/readHubDiscovery. SettingsHelper starts the Hub only via HelperKernel.startHub() -> kernel.ensureConnected.\n- [Merge 0] CLI facts the specs get wrong: `cline hub status` prints JSON without --json; doctor fix is `cline doctor fix --json` (not `--fix`). Correct docs/07 section 3.4 rather than the code.\n- [Merge 0] SettingsHelper lease: acquire(owner) in ViewModel.init, release(owner) in onCleared(); release grace is 5 s (D9). HelperState.Unavailable.detail is raw stripped stderr; screens supply the Details dialog copy.\n- [Merge 0] Secrets: SecureClipboard.copySecret(label, secret) for API keys/tokens; TermuxCommand.toString() is label-only and SENSITIVE_FLAGS are redacted; `--room-secret` is allowed only on the dashboard argv. Keep tokens and keys out of UiState, SavedStateHandle and logs.\n- [Merge 1] Use graph.kernelDispatcher (after the mainFix) for every reducer-style dispatch (SessionsRepository upserts, InterruptionDetector); never create another limitedParallelism(1).\n- [Merge 1] Only SessionStore collects hubClient.events (D15). Repositories observe SessionStore.state(id) / signals; do not subscribe to the socket.\n- [Merge 1] MainActivity.onCreate already calls KernelOverrides.applyLaunchIntent(this, intent); do not add it again. graph.kernelDiagnostics already exists.\n- [Merge 1] HubUpdatePresenter lives in ui/kernel/HubUpdateRequiredDialog.kt (no separate file). AppShellViewModel wires onKernelState(kernel.state), onStart() on ON_START, HubUpdateRequiredDialog when dialog != null, snackbar kernel_restarted_snackbar on `restarted`.\n- [Merge 1] SessionError.reason (MissingResumeConfig) carries @StringRes text; the chat banner renders stringResource(reason.text) as %1$s of chat_resume_error, which the chat screen's strings file must define.\n- [Merge 1] Liveness.Live == live_ && !persistedHint; SessionStore.send() resumes up front whenever persistedHint is set and returns SendOutcome.ResumeFailed on failure. Build UI on SendOutcome, not on live_.\n- [Merge 1] Same-named helpers with different return types: data.chat.parseToolPayload vs data.tools.parseToolPayload; SessionDiff.parseApplyPatchInput vs data.tools.parseApplyPatchInput; data.sessions.formatCompactTokens vs ContextUsageCalculator.formatCompactTokens(Long, Locale). Never import both into one file.\n- [Merge 1] Single definitions to reuse: data.tools.CommandOutputCap (output caps), data.session.UserInputFormat (prompt/format.ts port; data.sessions.SessionFormat forwards), data.session.SessionVisual (status colours).\n- [Merge 1] strings_kernel.xml owns kernel_*, hub_*, state_*, banner_*, diag_*, kernel_blocked_*; reuse those keys. Still undefined: onboarding_*, setup_*, check_*, install_termux_*, connect_*, done_*, reliability_*, rel_*, common_*, chat_transport_*, composer_kernel_down_placeholder, list_unavailable_state. A duplicate name across strings_*.xml fails the resource merge.\n- [Merge 1] Pure data packages (data.sessions/schedules/tools/marketplace) keep desktop English inline and return typed results (CompactAge, ScheduleRunLabel, ScheduleFormError, RoutineTemplateIcon); the UI maps those to resources/DateUtils/icons. Do not add resource lookups to data/*.\n- [Merge 1] About screen owner must bundle app/src/main/assets/CHANGELOG.md (data.about.CHANGELOG_ASSET); it is absent on main. AppLinks.releaseUrl assumes tag scheme app-v<version>, unconfirmed.\n- [Merge 1] Debug fake kernel: scenario chosen via FAKE_KERNEL extra, persisted until `off`; wrappers re-check the scenario per call, so no AppGraph rebuild is needed. Release twin returns the real objects.\n- [Merge 2] Build is green on main: assembleDebug + testDebugUnitTest, 1120 tests / 101 suites, 0 failures.\n- [Merge 2] Every seam from the package reports exists under the reported name; `HubClient.idempotentRead` and `WhatsNewRepository.asTrigger` are extension functions (data/customize/RepoSupport.kt, ui/AppShellViewModel.kt), not members.\n- [Merge 2] Two `MarketplaceEntry` types exist: ui.navigation.MarketplaceEntry (a ShellRoute) and data.marketplace.MarketplaceEntry (catalog row). In the marketplace screen import the route with an alias (`import ...navigation.MarketplaceEntry as MarketplaceEntryRoute`).\n- [Merge 2] Session state for observers is `data.tools.SessionStateSource` (state+seed); `data.notifications.LoadedSessionSource` is a different contract for the notifier/keep-alive. Do not add a third.\n- [Merge 2] Schedule writes go only through `SchedulesRepository` / `SchedulePayloads` (cronPattern + modelSelection). Never add schedule builders to HubPayloads.\n- [Merge 2] Deep links: map NotificationRouter.Sheet.wire -> ChatSheet by wire string; ChatSheet.Queue has no notifier counterpart and must stay app-internal.\n- [Merge 2] Integration must wire the lifecycle hooks the merge left open: signIn.onAppStarted/onAppResumed/onAppStopped, sessionsRepository.onResume/onStop/onDrawerOpened/setVisibleSession/markSeen, kernel.onForeground/onBackground, chat route ON_RESUME -> knownSessions.add + notifier.onSessionVisible, delete -> knownSessions.remove + notifier.onSessionGone, and collect signIn.completions into notifier.postSignedIn/cancelSignedIn.\n- [Merge 2] Chat screen: keep the ChatSupportingPaneHost + LocalShellChat wrapper from shellDestinations(); selected-session -> Home back uses OrderedPredictiveBackHandler(BackLevel.NavHostPop) inside the NavHost.\n- [Merge 2] Assistant-message 'Select text' only makes the MarkdownText body selectable (MarkdownText lacks select-all); the S25U acceptance line needs an extra long-press there. If a selectable-all MarkdownText API is added, drop the msg_select_text_hint snackbar.\n- [Merge 2] Settings 'Background connection' row: show notif_settings_keep_alive_denied when keepAlive.status == Denied; NotificationChannels.isBlocked feeds the 'Blocked in system settings' row.\n- [Merge 2] Repository errors are typed RepoFailure; UI maps them to CR §0.5 copy and logs by reason (data packages never log). RestoreFailure likewise.\n- [Merge 2] The Only-Origin dev-connection reduced-authority schedules notice is computed in the UI from KernelOverrides, not the repository.\n- [Merge 3a] Resolve hub members off Main in ViewModel factories: CheckpointDeps.of(graph) and AgentsRosterViewModel.factory touch graph.hubClient / graph.sessionStore eagerly (AppGraph.hubClient's lazy runs prefs.clientIdBlocking()). Follow GraphSessionsPort / PickerSessionPort: lambdas or withContext(Default).\n- [Merge 3a] Same simple names in different packages; use import aliases in ShellNavHost and ChatViewModel: MarketplaceEntry and ScheduleDetails (ui.navigation routes vs data/marketplace and ui/schedules models), HubVersion (core.kernel.hub vs ui.about), PickerRow (ui.checkpoint vs ui.model).\n- [Merge 3a] Composer docked children: use Composer's `suggestionPanel` and `queueStrip` slots only; do not wire ChatBanners.ComposerDockedChildren / ChatDockedStack, delete them after integration.\n- [Merge 3a] Queue edits: pass `resolveEdit = { sendPipeline.resolve(it, mode, snapshot) }` to PromptQueueController.Factory.create and delete SlashCommandSource.resolve afterwards (SlashCommandSource keeps the catalog/cache for the suggestion menu).\n- [Merge 3a] ShellNavigator.openInstalled(tab) and CUSTOMIZE_TAB_KEY are in ui/marketplace/MarketplaceGraph.kt (extension on ShellNavigator), not ui/shell; customizeGraph already consumes the key.\n- [Merge 3a] Lightbox focus return: extend TranscriptRowCallbacks.onExpandImage to carry a FocusRequester from each thumbnail (MessageBubble attachment/generated images, ToolCallRow) into LightboxState.open(..., returnFocus).\n- [Merge 3a] ChatViewModel must fold ModelPickerResult.PendingConnection(sessionId, ConnectionUpdate) into the resume session.create config; keep ModelPickerSheetHost composed while closed (it collects reasoning effects).\n- [Merge 3a] Chat route: ApprovalController/AskQuestionController setVisible(true) on ON_RESUME, setVisible(false) on ON_PAUSE, openFromDeepLink() for ChatSheet.Approval/Question, drawer edge-swipe disabled while state.sheet.isOpen; compose ApprovalEffectsHandler/QuestionEffectsHandler once.\n- [Merge 3a] Checkpoint restore/fork and session open results navigate via ShellNavigator.openSession; never popUpTo(Routes.Chat) inclusive.\n- [Merge 3a] AndroidManifest (integration-owned): declare REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; ReliabilityScreen switches to the direct dialog by itself once declared.\n- [Merge 3a] Accepted patterns, do not spread: hero colour literals as res colours (colors_about.xml, components_hero_*) outside ui/theme; post-navigation snackbars via graph.appScope.launch(Dispatchers.Main) { showReplacing } (KernelRepairScreen, DiagnosticsExportDialog).\n- [Merge 3a] Still open outside code: Hub fixture 07-checkpoint-restore.jsonl (device recording), no prompt-queue fixtures, no plugin slash commands on the Hub.\n- [Merge chat] Build + 2002 unit tests green on main 4bf733b; phase diff touches only ui/chat, ui/home, ClineIcons (+ms_u_turn_left, README) and strings_chat.xml — ownership clean.\n- [Merge chat] ShellNavHost.shellDestinations() (lines 118-119) still renders ChatPlaceholder for Chat/ChildChat; integration replaces both lines with `chatGraph(navigator, graph)` and deletes ChatPlaceholder.\n- [Merge chat] chatGraph already registers changesGraph(nav, graph) and toolGraph(nav, graph); do not register them again in ShellNavHost or routes collide.\n- [Merge chat] ChatRoute warms AppGraph lazies off Main via ChatGraphWarmup (produceState on Dispatchers.Default) and shows HomePlaceholder until done; do not add a second warm-up or a runBlocking in the shell.\n- [Merge chat] ChatViewModel, CheckpointViewModel, ModelPickerViewModel and WorkspacePickerViewModel are all scoped to the Chat NavBackStackEntry (viewModelStoreOwner = entry); the selection is ChatSelection in that entry's SavedStateHandle (ShellNavigator.select writes it, KEY_SHEET for ?sheet=).\n- [Merge chat] Back from a selected top-level session is handled inside ChatScreen (OrderedPredictiveBackHandler at BackLevel.NavHostPop, seekable shared-axis pop, commits with vm.goHome()); the shell must not add its own back interception for that case (HC §2.9).\n- [Merge chat] Child sessions are ChildChat pushed entries; ChatEvent.OpenParent pops when the previous entry is the parent, else navigator.openSession(parentId) — keep that contract if you touch ShellNavigator.\n- [Merge chat] HomeScreen(state, onWorkspace, onBranch, onKernelStatus, modifier, heroHidden, largeHeading, statusBarInset) is the only signature; compactHero is gone. Status colours go through kernelVisualOf/KernelStatusDot (internal to ui/home).\n- [Merge chat] home_* strings live in strings_chat.xml (chat/home share one ViewModel per docs/07 §6.5); add Home copy there, not in a new file.\n- [Merge chat] ChatViewModel.onCleared() closes the last open session on graph.appScope (store.close(id)); any shell-level 'close all sessions' logic must tolerate a second close.\n- [Merge chat] No AppGraph members were added this phase and none were required by docs/07 §1.3 for Home/Chat; ChatDeps.of(graph, context) composes everything from existing members.\n"

const seamsDigest = allPackages.map(r => `- ${r.branch}: ${r.seams}`).join('\n')
const blockersDigest = allPackages.flatMap(r => (r.blockers ?? []).map(b => `- [${r.branch}] ${b}`)).join('\n') || '- none reported'
const deviationsDigest = allPackages.flatMap(r => (r.deviations ?? []).map(d => `- [${r.branch}] ${d}`)).join('\n') || '- none reported'

phase('Integration')
let integration = (args && args.integration) || null
if (STAGE !== 'verification') integration = await agent(`${COMMON}
YOU ARE THE INTEGRATION AGENT and you work in the MAIN checkout ${APP} on branch main (no worktree). Every package is merged. Wire the app
(docs/07 §1.3, §6, §11 Phase 4). Lead corrections gathered so far (follow them):
${ADDENDA || '- none'}
1. AppGraph.kt: every singleton of docs/07 §1.3 present, \`by lazy\`, in dependency order; remove dead wiring; ClineApp.onCreate does only
   the three eager things §1.3 names.
2. MainActivity.kt / RootNavHost / ShellNavHost: replace every placeholder destination with the real screen through the lanes'
   NavGraphBuilder extensions; start destination rules; deep links end to end (CA §0.6) incl. the OPEN_SHEET hand-off into ChatViewModel;
   adaptive placements of DS §5.2 (list pane = SessionsDrawerContent; supporting pane = Changes/Tool; Settings and Providers list-detail;
   editors as dialog() on expanded); What's new on launch; the kernel probe reachable only from About's debug Developer row;
   KernelGate in the composer and KernelBanner replacing per-screen kernel notices (KS §0.10); FGS hand-off through KeepAlivePolicy (CA §0.7).
3. Compose UI tests of docs/07 §10.2: the five critical flows in app/src/androidTest against TestAppGraph + the fake kernel scenarios
   (they will run on the device in the next phase; make them compile with \`bash scripts/build.sh :app:compileDebugAndroidTestKotlin\`).
4. AUDIT against the contract and fix what you find, in this order: (a) every route of docs/07 §6.2 exists in Routes.kt and is registered;
   (b) every component row of DS §4.1/§4.2 exists in ui/components or ui/chat/rows; (c) every string table in every spec (their
   "Strings"/"Copy" sections) is present in some strings_*.xml with the exact text — write docs/strings-audit.md listing per spec the keys
   found and the missing ones, then add the missing ones to the owning package file; (d) \`grep -rn "TODO\\|FIXME\\|NotImplemented\\|placeholder\\|coming soon\\|stub" app/src/main\`
   and resolve each; (e) \`grep -rn "Color(0x" app/src/main/kotlin | grep -v ui/theme\` must be empty; (f) \`bash scripts/build.sh :app:lintDebug\`
   passes (HardcodedText, ContentDescription, NewApi, …; fix, do not baseline); (g) duplicate type names across packages; (h) docs/07 §14
   cross-cutting rules; (i) no composable does I/O; (j) every screen of docs/07 §13 has a @Preview per state.
5. Build, unit tests, commit on main (several commits are fine). Clean \`git status\`. Do not push.

Seams reported by the packages:
${seamsDigest}

Blockers reported (resolve the ones integration can; list the rest in your notes for the report):
${blockersDigest}

Deviations reported (check each is justified by docs/07 §15 or the spec; revert unjustified ones):
${deviationsDigest}

Report with the schema; in \`notes\` list what the audit found and fixed, and what remains.`,
  { label: 'integration', phase: 'Integration', schema: WORK_SCHEMA, effort: 'high', model: OPUS })
log(`Integration at ${integration?.headCommit}: build ${integration?.buildGreen ? 'green' : 'RED'}, tests ${integration?.testsGreen ? 'green' : 'RED'}`)
if (STAGE !== 'verification') await runGate('Integration', { headCommit: integration?.headCommit, baseCommit: mergeChat?.headCommit, conflicts: [], reconciled: [], notes: integration?.notes ?? '' }, [integration].filter(Boolean))
if (STAGE === 'integration') return { stage: 'integration', integration }

// ---------------------------------------------------------------------------------------------
// Verification

phase('Verification')

const DEVICE = `
DEVICE AND HUB SETUP for verification (read before acting):
- Phone: Samsung S25 Ultra, adb serial $SERIAL, adb at ~/Android/Sdk/platform-tools/adb. Use the literal path (dcg
  rejects a variable executable followed by quoted inline code; split commands instead of chaining them in one quoted string). Install
  with \`bash scripts/install.sh\` after \`bash scripts/build.sh :app:assembleDebug\`; screenshots with \`bash scripts/screenshot.sh <name>\`
  (saved to screenshots/<name>.png; view them with the Read tool); UI tree with \`adb shell uiautomator dump /sdcard/ui.xml\` then
  \`adb pull /sdcard/ui.xml <scratch>\`; taps/swipes/text with \`adb shell input\`. Prefer uiautomator bounds over guessed coordinates.
  Dark mode: \`adb shell cmd uimode night yes|no\`; font scale: \`adb shell settings put system font_scale 2.0\` (restore 1.0); landscape:
  \`adb shell settings put system accelerometer_rotation 0\` then \`user_rotation 1\` (restore 0, re-enable). Never open Termux's own UI,
  never run anything in the phone's real ~/.cline beyond what the app does by design, never install anything else.
- Workstation Hub: \`bash scripts/dev-hub.sh start\` (mock provider 25499 + isolated Hub 25498 under /tmp/cline-dev-hub, scratch git
  workspace), \`bash scripts/dev-hub.sh reverse\` (adb reverse 25463 → 25498 and the SET_HUB_TOKEN debug broadcast the debug build
  accepts), \`status\`, \`stop\`, \`unreverse\`. The mock answers any prompt; a prompt containing "hello-from-mock" first makes a run_commands
  tool call (an approval when auto-approve is off). Nothing of this touches the owner's real ~/.cline or spends credits.
- Kernel states without touching Termux: the debug fake kernel of ${SPEC.ks} §0.9 —
  \`adb shell am start -n dev.cline.termux/.MainActivity --es dev.cline.termux.FAKE_KERNEL <scenario>\` (+ \`--ez dev.cline.termux.RESET_ONBOARDING true\`),
  \`--es dev.cline.termux.FAKE_KERNEL off\` returns to the real kernel. The phone's own Termux kernel cannot start its Hub on the installed
  port build (missing entry.js), so real-kernel flows end in the setup / port-too-old states — verify those states there; do not try to fix the port.
- Compose UI tests: \`bash scripts/build.sh :app:connectedDebugAndroidTest\` runs on the phone.
- Keep Termux in the background; the app is in the foreground. One agent uses the device at a time (you are alone now). If adb shows no
  device, report it under untestable instead of retrying for long.
`

const verifySetup = await agent(`${COMMON}
${DEVICE}
YOUR TASK: prepare verification. Build the debug APK from main, install it, start the workstation mock Hub, set up adb reverse and the
token override, open the app, and confirm it reaches the connected kernel state (the kernel chip / status sheet shows the Hub on 25463),
can create a session from Home with the mock model, stream an answer, and shows an approval for a "hello-from-mock" prompt. Then run the
Compose UI tests on the device. Fix whatever blocks these paths (in the main checkout, committed), because every group verification depends
on them. Save screenshots under screenshots/setup/. Report with the schema (group = "setup").`,
  { label: 'verify:setup', phase: 'Verification', schema: VERIFY_SCHEMA, effort: 'high', model: OPUS })
log(`Verification setup: passed ${verifySetup?.passed?.length}, failed ${verifySetup?.failed?.length}`)

const VERIFY_GROUPS = [
  { key: 'onboarding-kernel', spec: SPEC.ks, sections: 'A–F acceptance lists and §10, using the fake-kernel scenarios' },
  { key: 'home-chat', spec: SPEC.hc, sections: '§2–§6 acceptance and §9' },
  { key: 'composer-approvals', spec: SPEC.ca, sections: 'C1.15, C2.10, C3.11, C4.10, C5.11, C6.8, C7.8, C8 acceptance' },
  { key: 'sessions', spec: SPEC.ss, sections: '§10' },
  { key: 'settings-providers', spec: SPEC.sp, sections: 'S1–S9 acceptance and §12' },
  { key: 'changes-tools-checkpoints', spec: SPEC.ct, sections: 'A.11, B.11, C.11 and §E' },
  { key: 'customize-and-rest', spec: SPEC.cr, sections: 'C1–C13 acceptance incl. notifications §11.9 and the foreground service §12.6' },
  { key: 'design-system', spec: DS, sections: '§11 implementer checklist (theme, type at font scale 2.0 × size 20, layout classes incl. landscape, dark mode, motion, a11y with TalkBack labels via uiautomator)' },
]

const verifyResults = []
for (const g of VERIFY_GROUPS) {
  const r = await agent(`${COMMON}
${DEVICE}
YOUR TASK: verify "${g.key}" on the device against ${g.spec} (${g.sections}) and the design system. The mock Hub and adb reverse should be
up from the setup step (check \`bash scripts/dev-hub.sh status\`; restart if needed). For every acceptance item that this setup can
exercise: drive the UI, screenshot the state to screenshots/${g.key}/<item>.png, look at the screenshot, and judge it against the spec
text, the DS and the desktop (open the desktop TSX when in doubt about copy or hierarchy). Check both light and dark for the main
screens, and compact portrait plus landscape (height-compact) at least once. When something fails, FIX IT in the main checkout (you are
alone on main; commit each fix with a conventional message), rebuild, reinstall, re-verify. Items that need the real Termux Hub, another
device or a human (e.g. the browser sign-in) go under \`untestable\` with the reason. Unit tests must stay green.
Evidence and flakes: an item passes only with a screenshot that shows it; re-run a failing item once in isolation (fresh app start)
before calling it failed; an item that passes once and fails once is reported as failed with both observations, never as passed. Do not
widen timeouts or add retries in the app to make an item pass.
Report with the schema.`,
    { label: `verify:${g.key}`, phase: 'Verification', schema: VERIFY_SCHEMA, effort: 'high', model: OPUS })
  verifyResults.push(r)
  log(`verify ${g.key}: passed ${r?.passed?.length ?? '?'}, failed ${r?.failed?.length ?? '?'}, untestable ${r?.untestable?.length ?? '?'}`)
}

// ---------------------------------------------------------------------------------------------
// Polish loop

phase('Polish')

const LENSES = [
  ['desktop-parity', 'copy, information hierarchy, states and precedence, behaviour and formatting functions versus the desktop sources (DS §9); open the desktop TSX for every screen and diff the experience'],
  ['material3', 'M3 component correctness: right component for the job (DS §4.3), insets and edge-to-edge, touch targets, elevation/tonal layers, shapes, motion specs of DS §6, predictive back order of DS §5.8 / docs/07 §6.3, adaptive layouts of DS §5'],
  ['accessibility', 'DS §8.1 point by point: content descriptions, state descriptions, custom actions, live regions, focus, text scale 2.0 × font size 20 layouts, contrast pairs of DS §1.8, selection containers'],
  ['cjk-i18n', 'DS §2.3 and §8.2: letter spacing, weights, line breaking, mixed-script rendering, every string in resources with plurals and positional args, no concatenation, relative dates, RTL mirroring, LTR code'],
  ['kernel-robustness', 'docs/02 recovery matrix, docs/07 §9 and the specs\' recovery sections: reconnect/replay/cursor handling, approval dedupe, interrupted turns, session_not_found resume, helper lifecycle, foreground service hold, token rotation, no secrets in logs/state'],
  ['performance', 'recomposition hygiene (stable params, keys on LazyColumn items, derivedStateOf), no I/O or JSON parsing on the main thread, streaming coalescing at 50 ms, bitmap decoding limits, startup work, memory held by stores'],
  ['code-quality', 'dead code, duplicated helpers across packages, TODO/placeholder remnants, missing previews, missing tests for pure logic, naming consistent with docs/07, hard-coded colours/sizes/strings'],
]

const seen = new Set()
let dryRounds = 0
let round = 0
while (dryRounds < 2 && round < 4) {
  round++
  log(`Polish round ${round}: ${LENSES.length} critics`)
  const found = (await parallel(LENSES.map(([lens, brief]) => () => agent(`${COMMON}
YOU ARE A CRITIC (read-only; change nothing) with the lens "${lens}": ${brief}.
Review the whole app in ${APP} (main checkout, HEAD), the screenshots under ${APP}/screenshots/** from the device verification, and the
specs. Produce concrete, verifiable findings with file and line, the rule they break (quote the DS/spec/docs-07/desktop source), and the
exact fix. Severity: blocker (wrong behaviour or crash), major (visible deviation from desktop/DS), minor (small deviation), polish (nicety).
Do not report what is already fixed at HEAD; do not pad. Up to 40 findings, most severe first.`,
    (round === 1 && lens === 'desktop-parity')
      ? { label: `critic:${lens}:r${round}`, phase: 'Polish', schema: FINDINGS_SCHEMA, effort: 'high' }
      : { label: `critic:${lens}:r${round}`, phase: 'Polish', schema: FINDINGS_SCHEMA, effort: 'high', model: OPUS })))).filter(Boolean).flatMap(r => r.findings)

  const fresh = found.filter(f => {
    const k = `${f.file}:${f.line ?? 0}:${f.claim.slice(0, 80)}`
    if (seen.has(k)) return false
    seen.add(k)
    return true
  })
  log(`round ${round}: ${found.length} findings, ${fresh.length} new`)
  if (fresh.length === 0) { dryRounds++; continue }

  const judged = await parallel(fresh.map(f => () =>
    parallel([0, 1, 2].map(v => () => agent(`${COMMON}
YOU ARE A VERIFIER (read-only). A critic claims about ${APP} at HEAD:
file ${f.file}${f.line ? `:${f.line}` : ''} — ${f.claim}
Proposed fix: ${f.fix}
Open the file and the rule it cites (design system, spec, docs/07, desktop source). Decide whether this is a REAL defect worth fixing in
this app (real=true) or not (already correct, misreads the rule, contradicts the spec or docs/07 §15, or purely subjective). Verifier
#${v + 1}; be strict and brief.`,
      { label: `judge:${f.id}:${v + 1}`, phase: 'Polish', schema: VERDICT_SCHEMA, effort: 'medium', model: OPUS })))
      .then(vs => ({ f, real: vs.filter(Boolean).filter(x => x.real).length >= 2 })),
  ))
  const confirmed = judged.filter(Boolean).filter(j => j.real).map(j => j.f)
  log(`round ${round}: ${confirmed.length} confirmed findings`)
  if (confirmed.length === 0) { dryRounds++; continue }
  dryRounds = 0

  const byArea = new Map()
  for (const f of confirmed) {
    if (!byArea.has(f.area)) byArea.set(f.area, [])
    byArea.get(f.area).push(f)
  }
  const areas = [...byArea.entries()].map(([area, fs]) => ({ key: `fix-${area}`, area, findings: fs }))
  const fixResults = await pool(areas, 5, a => agent(`${COMMON}
YOU ARE A FIXER for area "${a.area}" in your own worktree. Apply these confirmed findings, each fully (code, strings, tests, previews),
then build + unit tests green, commit, and report. Skip a finding only if applying it would contradict the spec, docs/07 or another rule;
say why.
${a.findings.map(f => `- [${f.id}] (${f.severity}) ${f.file}${f.line ? `:${f.line}` : ''}: ${f.claim}\n  fix: ${f.fix}`).join('\n')}`,
    { label: `fix:${a.area}:r${round}`, phase: 'Polish', schema: FIX_SCHEMA, isolation: 'worktree', effort: 'high', model: OPUS }))
  const fixDone = fixResults.filter(Boolean)
  const mergeFix = await agent(mergePrompt(fixDone.map(r => ({ ...r, seams: 'polish fixes', blockers: r.skipped, notes: r.fixed.join('; ') })), `polish-round-${round}`),
    { label: `merge:polish-r${round}`, phase: 'Polish', schema: MERGE_SCHEMA, effort: 'high' })
  log(`round ${round}: merged fixes at ${mergeFix?.headCommit}`)

  const reverify = await agent(`${COMMON}
${DEVICE}
YOUR TASK: re-verify on the device after polish round ${round}. Build main, install, bring the mock Hub up, and walk the main paths once
(fake-kernel first-run states, Home → new session → streamed answer → approval → answer, sessions drawer and screen, settings
home/appearance/providers, model picker, changes screen, schedules, about) in dark and light; run the Compose UI tests; screenshot to
screenshots/polish-r${round}/. Any regression: fix on main, commit, re-check. Report with the schema (group = "polish-r${round}").`,
    { label: `reverify:r${round}`, phase: 'Polish', schema: VERIFY_SCHEMA, effort: 'high', model: OPUS })
  log(`round ${round}: re-verify failed ${reverify?.failed?.length ?? '?'}`)
}

// ---------------------------------------------------------------------------------------------
// Report

phase('Report')
const report = await agent(`${COMMON}
YOUR TASK: write ${APP}/docs/09-implementation-report.md for the owner (who will review the app before anything is pushed) and update
${APP}/docs/README.md and ${APP}/CLAUDE.md where they describe the code layout and the developer loop. Contents: what was built (one
paragraph per docs/07 package group, mapped to the spec screens), how to run the developer Hub (scripts/dev-hub.sh) and the fake kernel
and verify on the phone, the acceptance status per spec group from these verification reports (passed / failed / untestable counts with
the screenshot folders), known gaps and blockers with their reasons, deviations from the specs with reasons, the unit and UI test counts,
and the open questions that still need the owner (docs/07 §16 plus anything needing the termux.2 kernel release on the phone, the browser
sign-in, the Tab S7+ run). Be factual and short; link files. Commit on main. Do not push.

Verification reports:
${[verifySetup, ...verifyResults].filter(Boolean).map(r => `- ${r.group}: passed ${r.passed.length}, failed ${r.failed.length} (${r.failed.slice(0, 5).join(' | ')}), untestable ${r.untestable.length}`).join('\n')}

Integration notes: ${integration?.notes ?? ''}
Remaining blockers: ${(integration?.blockers ?? []).join('; ')}`,
  { label: 'report', phase: 'Report', schema: WORK_SCHEMA, effort: 'medium', model: OPUS })

return {
  headCommit: report?.headCommit,
  packages: allPackages.map(r => r.branch),
  integration: { head: integration?.headCommit, buildGreen: integration?.buildGreen, testsGreen: integration?.testsGreen, blockers: integration?.blockers },
  verification: [verifySetup, ...verifyResults].filter(Boolean).map(r => ({ group: r.group, passed: r.passed.length, failed: r.failed.length, untestable: r.untestable.length })),
  polishRounds: round,
}
