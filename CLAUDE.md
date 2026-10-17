# Cline for Termux — native Android front end

## What this is

A native Android app (Kotlin, Jetpack Compose, Material 3) that is the front
end for the Cline "kernel" running inside Termux on the same phone: the Cline
CLI from the Termux port (github.com/IChouChiang/cline-termux) and its Hub
daemon. The app embeds no runtime and no kernel; it connects to what Termux
runs. The goal: as close to a 1:1 functional copy of Cline Desktop
(`apps/examples/desktop-app` in the Cline monorepo) as makes sense on a phone,
natural Material 3, finished and polished.

The port's checkout carries the upstream source (docs cite it as
`~/workspace/cline-termux-v3-phone`); it is read-only from here, and its release
manager is never run from this repo. Research and decisions live under `docs/`.

## Hard rules

- Device- and owner-specific rules and notes (the test phone and when it may be used, its
  adb serial and ssh alias, local paths, this machine's quirks) live in `CLAUDE.local.md`
  (untracked); read it too when it exists. In tracked docs `$SERIAL` stands for the phone's
  adb serial and `<phone>` for the ssh host of its Termux.
- Nothing is published (GitHub, Play, any store) without the owner's review.
  Tags and releases are the owner's call.
- No secrets in the repo: no tokens, keystores or `local.properties`.
- The Hub auth token and `~/.cline` are Termux-private. The app obtains the
  token only through a sanctioned channel (Termux `RUN_COMMAND` intent or the
  owner pasting it), never by reading Termux's files.
- Keep the kernel contract thin and explicit: everything the app needs from
  Termux is documented in `docs/02-kernel-contract.md`, and every protocol
  message the app sends or receives has a Kotlin type.

## Where things are

- Contract: `docs/07-app-architecture.md` (package tree §1, AppGraph §1.3, routes §6.2,
  DataStore keys §7, screen index §13, decisions §15, owner questions §16). Kernel:
  `docs/02-kernel-contract.md`; recorded Hub traffic: `docs/08-hub-fixtures.md`. State of the
  build, acceptance results, gaps and open questions: `docs/09-implementation-report.md`; batch 1
  (2026-10-07: approvals, compaction, contributions, resilience, composer, transcript) and its
  device verification: `docs/10-batch1-report.md`; batch 2 (2026-10-08, the polish pass) and
  its verification: `docs/11-batch2-report.md`. Decisions: `docs/decided.md`; open items and
  the trial log: `docs/todo.md`.
- Device evidence stays local and out of git (`.gitignore` drops all of `screenshots/` but `.gitkeep`): batch 1 in
  `screenshots/batch1-verify/`, batch 2 in `screenshots/batch2-verify/` (named by item id,
  such as `V-HIST-02-chip-crop.png` or `v3-R13-series/`, with `q5/` holding Robolectric renders;
  the `*-evidence.txt` files hold the measurements and log extracts each item cites).
- Design: `design/design-system.md` and `design/screens/*.md` (KS, HC, CA, SS, SP, CT, CR).
- Code: `app/src/main/kotlin/dev/cline/termux/` in four layers. `core/kernel` (`hub/`,
  `termux/`, `dashboard/`) and `core/secure` talk to the Hub, Termux and the settings helper.
  `data/<feature>` turns frames and command output into state; `data/session/SessionStore`
  is the single Hub-event collector. `service/` holds the Android components. `ui/<feature>`
  holds the composables and ViewModels; each feature exposes a
  `NavGraphBuilder.<feature>Graph(nav, graph)`. Manual DI is in `AppGraph.kt`. Strings live in
  `res/values/strings_<package>.xml`; icons are in `ui/icons/ClineIcons` plus
  `res/drawable/ms_*.xml` (see `res/README-material-symbols.md`).
- Debug-only code is in `app/src/debug` (developer Hub token override, fake kernel in
  `debug/fake/`, KernelProbe), with pass-through stubs in `app/src/release`.
- Tests: JVM in `app/src/test` (fakes in `test/fakes/`, fixtures in
  `test/resources/hub-fixtures/`) and `app/src/testDebug`. Compose UI flows are in
  `app/src/androidTest` (`CriticalFlowsTest` against `FixtureHub`).

## Toolchain

- JDK 21 with `javac` (Temurin): `scripts/build.sh` uses `JAVA_HOME`, default
  `~/.local/jdk-21`, and `ANDROID_HOME`, default `~/Android/Sdk` (platform-tools plus the
  platform for `compileSdk` 37). Gradle 9.6 via the project wrapper (AGP 9.4 needs ≥ 9.6),
  AGP 9.4.1 with its built-in Kotlin (the `org.jetbrains.kotlin.android` plugin is
  incompatible with AGP 9's DSL; the Compose and serialization compiler plugins are pinned
  to 2.2.10, the Kotlin version AGP 9.4.1 bundles), Compose BOM 2026.09. The wrapper was
  generated from a Gradle 9.6.0 distribution; `./gradlew` works from a clean checkout. The
  app targets Android 16 (API 36); `minSdk` 26.
- Java tools ignore `HTTP(S)_PROXY`, and a user-level `~/.gradle/gradle.properties`
  overrides project settings. Always build through `./scripts/build.sh`, which passes the
  proxy from the environment as `-D` system properties (highest precedence).
- Build and test: `bash scripts/build.sh :app:assembleDebug` and
  `bash scripts/build.sh :app:testDebugUnitTest`.
- Package ids: the installed release app is `io.github.ichouchiang.clinetermux`. The debug build is
  `io.github.ichouchiang.clinetermux.debug` ("Cline Termux Debug", installs beside it); the release build is
  `io.github.ichouchiang.clinetermux`, signed with the project release key (`docs/release-signing.md`;
  the debug key only when none of the four signing properties is set; a partial set fails), and
  `-Pcline.releaseIdSuffix=.bench` makes it `io.github.ichouchiang.clinetermux.bench`. Never install, launch,
  clear or force-stop `io.github.ichouchiang.clinetermux` from here. The activity class keeps its name, so a
  component is `<package>/dev.cline.termux.MainActivity`.
- Device loop: `./scripts/install.sh [--no-launch] [apk]` (adb install and launch; the debug
  APK by default; it refuses any APK whose package is exactly `io.github.ichouchiang.clinetermux`) and
  `./scripts/screenshot.sh <name>` (adb screencap to `screenshots/<name>.png`).
  Read screenshots with the Read tool. Acceptance evidence goes in `screenshots/<spec group>/`.
  These scripts, `scripts/dev-hub.sh` and `scripts/bench/hub-replace.sh` pick the phone by
  `SERIAL`, else `ANDROID_SERIAL`, else adb's only attached device.
- Developer Hub (bench): `scripts/dev-hub.sh start` runs a mock provider and a Hub on an
  isolated `/tmp/cline-dev-hub` home (never `~/.cline`, no LLM credits). `reverse` maps the
  phone's 127.0.0.1:25498 to it, pushes the token and port 25498 to `io.github.ichouchiang.clinetermux.debug`
  (`APP_PKG` overrides, never the owner's id) and force-stops it; the phone's 25463 is never
  touched, so the owner's app keeps the real Hub. `helper` adds the settings-helper dashboard
  on 8787. Undo with `unhelper`, `unreverse` (clears token and port) and `stop`. A prompt
  containing `hello-from-mock` triggers a tool approval. Other mock markers: `slow`,
  `mock-long-output`, `mock-long-command`, `mock-edit`, `mock-read`, and since batch 1
  `mock-stall` (75 s of silence), `mock-mistakes` (mistake-limit question), `mock-act-switch`
  (plan → act), `mock-hook-block` / `mock-hook-context` and `mock-api-error[-<status>]` (list in
  docs/08). Bench helpers: `scripts/bench/hooks.sh on|off` (a global PreToolUse hook in the
  bench home; hooks spawn in the session's cwd, so drive a session seeded in
  `/tmp/cline-dev-hub/workspace`, not an app-created one) and `scripts/bench/hub-replace.sh
  drain|undrain|replace|status` (drain or replace the bench Hub; refuses 25463). App-created
  bench sessions have phone paths (with no folder chosen, the Termux home: decided A22, docs/07
  D30), so their `run_commands` fail with ENOENT on the workstation.
  The bench replaces only the WebSocket: the app's Termux commands (settings writes, prompt
  history, git worktrees) still run on the phone's real Termux, so back those files up first.
  Details: docs/06 "Developer Hub override". Batch 2 added no markers; its verifiers used
  `mock-sleep`, `mock-cmd-loop`, `mock-think`, `mock-chain-edit`, `mock-two-reads` and
  `mock-mistakes` as listed in `tools/hub-fixtures/mock-openai.ts`, plus three recipes worth
  reusing: a Stop that fails is produced by freezing the bench Hub (`kill -STOP`, then
  `-CONT`), never the real one; haptics are counted in `dumpsys vibrator_manager` ("Recent
  vibrations", recorded even with system haptics off); and a run of four different tools came
  from a throwaway copy of the mock under `/tmp`, deleted afterwards.
- Unit-test isolation: a Compose test that writes snapshot state outside composition must
  drain the main looper afterwards (`RouteNavigationTest`'s `@After`), or later sdk-34 Compose
  tests in the same Robolectric sandbox stop recomposing. Tests that show a spinner set
  `ANIMATOR_DURATION_SCALE` to 0 (`DisclosureRowsTest`). `PromptQueueSheetBackTest` takes 4 to
  9 minutes of every gate.
- Fake kernel (debug builds) for the KS states:
  `adb shell am start -S -n io.github.ichouchiang.clinetermux.debug/dev.cline.termux.MainActivity --es dev.cline.termux.FAKE_KERNEL <scenario>`
  (ids in `debug/fake/FakeKernelScenario.kt`; `off` returns to the real kernel; add
  `--ez dev.cline.termux.RESET_ONBOARDING true` to replay onboarding).
- Strings audit: `python3 tools/strings-audit/audit.py --markdown` regenerates
  `docs/strings-audit.md`. Hub fixtures: `tools/hub-fixtures/regenerate.sh` (docs/08).

## Removed functions (batch 2) and rules that keep them out

These were built once and then removed by decision; do not bring them back without a new one.
- **Move to workspace…** (A11.8 cancelled): no move code, menu entry, picker mode, recovery
  copy (`files/move-recovery/` is neither read nor excluded from backup any more) or
  `SessionStore.recreate`. Delete session stays.
- **"Fork before this turn"** on message rows and the typed **`/fork`** (A16, A19): the only
  fork is the whole session from the chat three-dot and the drawer row menu; no
  `fork.beforeRunCount` except the user row's Edit (checkpoint flow). Only `team` is a
  built-in slash name, so a typed `/fork` goes to the model as text.
- The batch-1 debug extras **`ApprovalPlacementOverride`** (A/A′ comparison) and
  **`ForkIconOverride`**: the approval control is A′ only, Ask = ticked shield, Auto = bolt,
  no disc or ring (A15).
- The desktop's tool-row **auto-open** (`shouldAutoOpen`, A23): every tool row starts folded.
- The **chat-workspace default** (`TermuxPaths.CHAT_WORKSPACE`, A22): a folder-less session
  runs in the Termux home and Home's chip reads "Home".
- One place per function (A19, A20, docs/07 §14): typed commands and menu entries never
  duplicate each other. The three stated exceptions are restore (row and picker), session
  commands (drawer row menu and chat three-dot) and prompt history (fling and attach sheet).
- Built-in tool switches (`teams`, `skills`) come from `global-settings.json` `disabledTools`
  (`DisabledToolsSource`), never from `settings.list`, which lists plugin tools only.
- Dense transcript rows take every size from `DisclosureRowTokens`; never put a literal
  height, icon size or gap in a row composable.

## Design process

- Material 3 as the system designs it: dynamic colour, the M3 type scale,
  M3 components and motion.
- Desktop parity means the same features, states and vocabulary as Cline
  Desktop, laid out for a phone — not a copy of its desktop layout.

## Conventions

- Kotlin official style; the docs/07 §1 layout (core / data / service / ui, one
  feature per package within each); UI state in ViewModels backed by the single
  `HubClient` and `SessionStore`; no business logic in composables. Material 3
  only, no `Color(0x` outside `ui/theme`, every user-visible string a resource.
- Commit messages: `type(scope): summary`, body says why. End with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` when written here.
- Tests: JUnit 4 + coroutines-test + turbine (Robolectric only where Android
  classes are unavoidable) for every reducer, repository, ViewModel and pure
  function; ported desktop `*.test.ts` cases keep their cases; fixture replay
  where docs/08 has traffic. UI review uses device screenshots.
- Docs: `docs/01`–`06` and `docs/README.md` are generated by
  `scripts/gen-docs.py`; edit the script. `07`–`10` are hand-written.
