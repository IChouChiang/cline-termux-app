export const meta = {
  name: 'cline-android-build-app',
  description: 'Implement the complete Cline for Android app from design/ and docs/07: foundation, session core, shell, sixteen screen lanes in parallel worktrees, the chat screen, integration, device verification against a mock Hub, polish loops, report',
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
// Phases 0–3b

const done0 = await runPhase('Phase 0 foundation', PHASE0, 2)
await runMerge('Merge 0', done0)

const done1 = await runReviewedPhase('Phase 1 core', PHASE1, 4)
await runMerge('Merge 1', done1)

const done2 = await runReviewedPhase('Phase 2 shell and data', PHASE2, 6)
await runMerge('Merge 2', done2)

const done3a = await runReviewedPhase('Phase 3a screens', PHASE3A, 6)
await runMerge('Merge 3a', done3a)

const done3b = await runReviewedPhase('Phase 3b chat screen', PHASE3B, 1)

// ---------------------------------------------------------------------------------------------
// Integration

phase('Integration')
const mergeChat = await mergeAgent(done3b, 'chat-screen', 'Integration')
log(`Merged the chat screen at ${mergeChat?.headCommit}`)
await runGate('Merge chat', mergeChat, done3b)

const allPackages = [...done0, ...done1, ...done2, ...done3a, ...done3b]
const seamsDigest = allPackages.map(r => `- ${r.branch}: ${r.seams}`).join('\n')
const blockersDigest = allPackages.flatMap(r => (r.blockers ?? []).map(b => `- [${r.branch}] ${b}`)).join('\n') || '- none reported'
const deviationsDigest = allPackages.flatMap(r => (r.deviations ?? []).map(d => `- [${r.branch}] ${d}`)).join('\n') || '- none reported'

const integration = await agent(`${COMMON}
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
await runGate('Integration', { headCommit: integration?.headCommit, baseCommit: mergeChat?.headCommit, conflicts: [], reconciled: [], notes: integration?.notes ?? '' }, [integration].filter(Boolean))

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
  phases: {
    p0: done0.map(r => r.branch), p1: done1.map(r => r.branch), p2: done2.map(r => r.branch),
    p3a: done3a.map(r => r.branch), p3b: done3b.map(r => r.branch),
  },
  integration: { head: integration?.headCommit, buildGreen: integration?.buildGreen, testsGreen: integration?.testsGreen, blockers: integration?.blockers },
  verification: [verifySetup, ...verifyResults].filter(Boolean).map(r => ({ group: r.group, passed: r.passed.length, failed: r.failed.length, untestable: r.untestable.length })),
  polishRounds: round,
}
