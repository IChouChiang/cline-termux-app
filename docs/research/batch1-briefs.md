# Batch 1: implementation briefs (P1–P8)

Design lead's briefs for the eight batch-1 packages, written 2026-10-07 against the app at
`main` and the kernel/desktop source at the fork's `cli-v3.0.69` (8ff805546, read-only).
Each brief gives files, Hub and Termux interactions, unit tests, bench additions and merge
dependencies. Section 9 is the verification plan (adb + bench, one entry per decided item, plus
G5). Decisions are in [../decided.md](../decided.md); the A1 design is
[auto-approve-control.md](auto-approve-control.md); the G-items are in
[terminal-only-functions.md](terminal-only-functions.md).

Path shorthands: `K/` = `app/src/main/kotlin/dev/cline/termux/`, `T/` =
`app/src/test/kotlin/dev/cline/termux/`, `R/` = `app/src/main/res/`, `D/` =
`app/src/debug/kotlin/dev/cline/termux/`, `REL/` = `app/src/release/kotlin/dev/cline/termux/`,
`FORK/` = `~/workspace/cline-termux-v3-phone/`.

---

## 0. Verified findings that change or sharpen the computed scope

Each was checked against source and, where marked, with a bench probe (isolated Hub on
25498, mock model, core 0.0.91).

| # | Finding | Effect |
| --- | --- | --- |
| F1 | **Bench probe:** the Hub itself runs the user's file hooks (`~/.cline/hooks/*`, `<workspace>/.cline/hooks/*`) for sessions created by a plain client. `DEFAULT_RUNTIME_CONFIG_EXTENSIONS` includes `"hooks"` and `local-runtime-bootstrap` builds `createHookConfigFileExtension` inside the Hub. A workspace `PreToolUse.sh` received the `tool_call` payload with no hook contribution and no `configExtensions` from the client. The terminal's client hooks only ingest audit events (`session.hook` → `replaySubagentHookEvent`). Hooks spawn as `["bash", path]` via PATH; output fields are `cancel`, `contextModification`, `errorMessage`. `UserPromptSubmit` did not fire on the Hub path. | **A5 deviation.** Registering hook contributions would run every hook twice and bind every tool call to the phone app (the A3 hang hazard when Android freezes the app). P5 ships **no hook contributions**: a guard test that the app never sends `configExtensions`/hook contributions, bench verification that block and context work, and docs/02 text. The computed RUN_COMMAND path is the fallback only if bench verification V-P5-A5 contradicts the probe. Owner to confirm. |
| F2 | **Bench probe:** on a live, idle session, `session.detach` then `session.create {sessionConfig.sessionId:<same>, initialMessages:<session.messages>}` replaces the runtime: the new `toolPolicies` apply (`metadata.autoApproveTools` false; the next run asked for approval) and the history is kept (4 → 8 messages after one more turn). Detach alone does not dispose the runtime (it only drops the participant). The terminal's own stop is `session.detach` (`FORK/apps/cli/src/runtime/hub-runtime-host.ts:1305`). | This is P1's restart primitive (and P4's Move to workspace). |
| F3 | **Bench probe:** core 0.0.91 creates checkpoints daemon-side for a plain client (`snapshot.checkpoint.latest/history`, `runCount 1`, kind `commit`). docs/08's "checkpoints are not created by the daemon" is outdated. | Fixture 07 is recordable (P8 adds scenario 07 to `record.ts`). |
| F4 | The real Stop timeouts are `ComposerController.Config.stopTimeoutMs = 10_000` (→ `ComposerMessage.StopTimeout`) and `ApprovalController.STOP_TIMEOUT_MS = 10_000` (→ `ApprovalEffect.StopFailed`). `ToolCallStore.STOP_TIMEOUT_MS = 5_000` is a `WhileSubscribed` timeout, unrelated. | P3 changes the two 10 s values to 30 s; leave ToolCallStore alone. |
| F5 | The terminal's history file `~/.cline/data/cache/user_input_history.jsonl` is written **oldest first**, one `{"prompt","ts"}` per line, at most 20; `loadInputHistory` takes the last 20 and reverses; `appendInputHistory` rewrites the whole file (dedupe, prepend, slice 20, write oldest first, every `ts = Date.now()`) (`FORK/apps/cli/src/utils/input-history.ts`). | P6 follows the real format; "Recent prompt 1" is the newest. |
| F6 | The Hub does not project recoverable agent `error` events (`session-event-projector.ts:396`); clients only get `session.notice {noticeType: recovery\|stop\|status, reason, …}`. The desktop routes notices to its core log, not the transcript; the app shows every notice as a Status row today. Per-turn stats come from `run.completed` `result {usage, iterations, durationMs}`. | P7 "Brief" hides recovery notices with reasons `invalid_tool_call`, `tool_execution_failed`, `completion_without_submit`, `mistake_limit` (keeps `api_error` with its credential fix); "Verbose" shows them all inline, plus stats. |
| F7 | The release applicationId equals the owner's installed app (`dev.cline.termux`); installing a release would replace it. | The jank build uses `-Pcline.releaseIdSuffix=.bench` (→ `dev.cline.termux.bench`); `install.sh` refuses any APK whose package is exactly `dev.cline.termux` (checked with `aapt2 dump badging`). |
| F8 | The FileProvider authority is hardcoded (`AndroidManifest.xml` `dev.cline.termux.files`; `AUTHORITY` in `K/service/ShareFiles.kt:20` and `K/ui/chat/rows/MediaRows.kt:179`). A second install with the same authority fails with `INSTALL_FAILED_CONFLICTING_PROVIDER`. | P8 uses `${applicationId}.files` and `BuildConfig.APPLICATION_ID + ".files"`. |
| F9 | With a suffix, `am start -n dev.cline.termux.debug/.MainActivity` resolves to the wrong class. | Scripts use `dev.cline.termux.debug/dev.cline.termux.MainActivity`. |
| F10 | `scripts/dev-hub.sh reverse` maps phone `tcp:25463` → bench 25498, which collides with the real Hub on the phone. | P8 adds a debug **port override** (`--ei port 25498`, persisted with the token override); `reverse` becomes `adb reverse tcp:25498 tcp:25498`. **Bench caveat:** with the bench Hub, the app's Termux commands (global-settings writes, history, git/worktree) still hit the phone's real Termux; verification backs up and restores those files. App-created bench sessions have Termux cwd paths, so bench `run_commands` fails with ENOENT on the workstation; approvals still appear before execution, and contributions need app-created sessions. |
| F11 | The owner's `global-settings.json` has no `toolAutoApprove` key (keys today: `autoUpdateEnabled`, `telemetryOptOut`, `planActMode`, `tuiTheme`, `disabledTools`, `tools`). Missing = on (`FORK/apps/cli/src/main.ts:892`). | After P1 the app opens in **Auto-approve all**. Owner note in the closeout. |
| F12 | `ms_call_split` is already the Home branch-chip icon (`ClineIcons.Chrome.Branch`). | P7 recommends the custom glyph `ms_fork_from_here.xml` (fork_right with the upper stem and its arrow removed); both are shown side by side via a debug-only extra. |
| F13 | docs/06's "Developer Hub override" section was hand-appended (f6c1c38) and is not in `scripts/gen-docs.py`; regenerating would drop it. | P8 ports it into gen-docs.py (and updates it for the port override and the suffix). |
| F14 | The desktop answers the mistake limit by steering the guidance plus hold hooks (`beforeModel`/`beforeTool`/`afterTool`); the CLI returns guidance in the decision. | P5 returns the guidance in the decision (CLI shape) and does **not** add hold hooks (A3 hazard). Deviation from the desktop, report it. |
| F15 | Read-only S25U check: `$PREFIX/bin/bun-ffi` → `opt/bun-android-ffi/current/bun` exists; the history file has 20 lines; `git`, `python3`, `node` exist; `~/tmp/app-test-ws` exists. | Termux-side writers use `bun-ffi -e` (JSON-safe, atomic rename). |

---

## 1. Shared seam S1: `K/core/kernel/termux/KernelGlobalSettings.kt`

P1 (toolAutoApprove) and P4 (compaction) both read and write
`~/.cline/data/settings/global-settings.json`. Both packages **create this file
byte-identically** (copy it from here, no edits); whichever merges second sees no diff. Any
extra need goes in the package's own file. Reading stays the existing
`ClineCommands.readGlobalSettings` (`cat`), so the file is read once per refresh and parsed by
both `GlobalSettingsView` and this class.

```kotlin
package dev.cline.termux.core.kernel.termux

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The kernel's global settings that the app reads and writes (batch 1 seam S1, shared by the
 * auto-approve control and the compaction setting). Read with [ClineCommands.readGlobalSettings];
 * written with [KernelGlobalSettingsCommands.write], which merges keys and never clobbers a file
 * it cannot parse.
 */
data class KernelGlobalSettings(
    val toolAutoApprove: Boolean?,
    val compactionStrategy: String?,
    val compactionEnabled: Boolean?,
) {
    /** The terminal reads a missing key as on (apps/cli/src/main.ts). */
    val autoApproveAll: Boolean get() = toolAutoApprove ?: true

    /** [COMPACTION_OFF], [COMPACTION_AGENTIC] or [COMPACTION_BASIC] (apps/cli/src/utils/compaction-mode.ts). */
    val compactionMode: String
        get() = when {
            compactionEnabled == false -> COMPACTION_OFF
            compactionStrategy == COMPACTION_BASIC -> COMPACTION_BASIC
            else -> COMPACTION_AGENTIC
        }

    companion object {
        const val COMPACTION_OFF = "off"
        const val COMPACTION_AGENTIC = "agentic"
        const val COMPACTION_BASIC = "basic"

        val DEFAULT = KernelGlobalSettings(toolAutoApprove = null, compactionStrategy = null, compactionEnabled = null)

        /** Invalid strategies read as agentic, like the kernel; unparseable JSON is null. */
        fun parse(json: String): KernelGlobalSettings? {
            val root = runCatching { CommandJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
            val strategy = (root["compactionStrategy"] as? JsonPrimitive)?.contentOrNull
            return KernelGlobalSettings(
                toolAutoApprove = (root["toolAutoApprove"] as? JsonPrimitive)?.booleanOrNull,
                compactionStrategy = strategy?.let { if (it == COMPACTION_BASIC) COMPACTION_BASIC else COMPACTION_AGENTIC },
                compactionEnabled = (root["compactionEnabled"] as? JsonPrimitive)?.booleanOrNull,
            )
        }

        /** ok → parsed; missing file → [DEFAULT]; anything else (Termux unreachable, bad JSON) → null = unknown. */
        fun readOrNull(result: TermuxResult): KernelGlobalSettings? = when {
            result.ok -> parse(result.stdout)
            result.noSuchFile -> DEFAULT
            else -> null
        }
    }
}

/** The keys a write changes; null keys are left as they are. */
data class KernelGlobalSettingsPatch(
    val toolAutoApprove: Boolean? = null,
    val compactionStrategy: String? = null,
    val compactionEnabled: Boolean? = null,
) {
    fun toJson(): String = buildJsonObject {
        toolAutoApprove?.let { put("toolAutoApprove", it) }
        compactionStrategy?.let { put("compactionStrategy", it) }
        compactionEnabled?.let { put("compactionEnabled", it) }
    }.toString()
}

object KernelGlobalSettingsCommands {
    const val LABEL_WRITE = "write global-settings.json"
    const val EXIT_UNREADABLE = 3

    /**
     * Merges [patch] into global-settings.json with the device Bun: reads stdin, exits
     * [EXIT_UNREADABLE] instead of overwriting a file it cannot parse, writes a temp file and
     * renames it, prints the merged JSON. The script has no `$` and embeds the path.
     */
    fun write(patch: KernelGlobalSettingsPatch): TermuxCommand = TermuxCommand(
        path = "${TermuxPaths.BIN}/bun-ffi",
        args = listOf("-e", MERGE_SCRIPT),
        stdin = patch.toJson(),
        label = LABEL_WRITE,
        timeoutMs = TermuxTimeouts.DISCOVERY_MS,
    )

    private val MERGE_SCRIPT = """
        const fs=require("node:fs"),path=require("node:path");
        const p="${TermuxPaths.GLOBAL_SETTINGS_JSON}";
        const patch=JSON.parse(await Bun.stdin.text());
        let cur={};
        try{const t=fs.readFileSync(p,"utf8");cur=t.trim()?JSON.parse(t):{};}catch(e){if(e.code!=="ENOENT")process.exit(3)}
        if(typeof cur!=="object"||cur===null||Array.isArray(cur))process.exit(3);
        for(const k of ["toolAutoApprove","compactionStrategy","compactionEnabled"]){if(k in patch)cur[k]=patch[k]}
        fs.mkdirSync(path.dirname(p),{recursive:true});
        const tmp=p+".app-"+process.pid+".tmp";
        fs.writeFileSync(tmp,JSON.stringify(cur,null,2)+"\n");fs.renameSync(tmp,p);
        process.stdout.write(JSON.stringify(cur));
    """.trimIndent()
}
```

(The only `$` are Kotlin templates for the two path constants; the generated JS has none.
A JSON parse error inside the `try` exits 3 as well. Test the script on the workstation with
`bun -e` against a copy whose `p` points under `/tmp`, including: missing file, `{}`, unrelated
keys kept, invalid JSON → exit 3 and file unchanged.)

Shared unit test `T/core/kernel/termux/KernelGlobalSettingsTest.kt` (also byte-identical in P1
and P4): missing key → `autoApproveAll` true; `false` → false; strategy `"basic"` → basic,
`"weird"` → agentic, `compactionEnabled:false` → off whatever the strategy; bad JSON → null;
`readOrNull` on `noSuchFile` → DEFAULT, on `delivered=false` → null; patch JSON omits null
keys; `write()` stdin equals `toJson()`, path ends `/bun-ffi`, script contains no `$`.

---

## 2. Conventions for every package

- Build only through `bash scripts/build.sh <tasks>`; gate `assembleDebug testDebugUnitTest
  lintDebug`, lint 0 errors (HardcodedText is an error). P8 additionally runs
  `assembleRelease` and `apksigner verify`. Never weaken lint or tests.
- Commit on the package branch; every commit message ends with
  `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>` (app CLAUDE.md). Never push,
  never touch main.
- No secrets in code, logs or reports; never print Hub tokens.
- **Own files, own anchors** (conflict map):
  - Termux commands: each package adds a new `K/core/kernel/termux/<Package>Commands.kt` with
    its own labels and private path constants; do not extend `ClineCommands`/`Labels`/`TermuxPaths`
    except P8 (none needed) — read paths are private constants in the new file.
  - Strings: one new file per package: P1 `strings_approval_mode.xml`, P3
    `strings_resilience.xml`, P4 `strings_session_config.xml`, P5 `strings_contributions.xml`,
    P6 `strings_composer_history.xml`, P7 `strings_message_details.xml`, P8
    `strings_worktree.xml` (+ shortcut strings there). Existing strings are changed in place
    only where the brief says so.
  - `ClineIcons.kt`: insert new entries alphabetically inside the right object; new drawables
    are new files; add a provenance row to `R/drawable/README-material-symbols.md` (rows sorted
    by file name).
  - Settings rows (`K/ui/settings/SettingsHomeScreen.kt`): P1 inserts after the last Kernel row,
    before `"models-header"`; P4 at the end of the Models group, before `"customize-header"`; P7
    at the end of the App group, before `"kernel-header"`. State and actions are added as new
    fields at the end of `SettingsHomeUiState` / the actions class.
  - Mock markers in `tools/hub-fixtures/mock-openai.ts`: append after the last marker branch.
    Bench scripts: new files under `scripts/bench/`.
  - Docs: packages do not edit docs except P8 (CLAUDE.md, `scripts/gen-docs.py` for 06, docs/09
    commands). The closeout writes docs/02, 07, 08; the integrator reruns `python3
    scripts/gen-docs.py` after the merge.
- **Merge order** (workflow ORDER): P8, P2, P3, P4, P1, P5, P6, P7. `dependsOn` lists what must
  merge first; a later package rebases on the integrated branch.
- Every create path (`session.create`, resume, restore, createRestored, fork) goes through
  `HubPayloads`; P1, P4 and P5 each touch it — keep each package's change to its own helper
  (`toolPolicies`, `compaction`, `runtimeOptions`) so the hunks do not overlap.

### Integration notes for the integrate agent

1. After P1 and P4 are in, wire a compaction change into P1's `RunBoundaryCoordinator` restart
   for the open idle session (otherwise the new mode applies at the next create/resume). Small
   follow-up commit; deviation noted.
2. After P5, check P7's Brief filter hides `mistake_limit` notices only when P5's sheet handled
   the request (both look at the same reason string).
3. Rerun `scripts/gen-docs.py` and the full gate on the integrated branch.

---
## 3. P8 housekeeping (G3, G4, debug id suffix, signed release) — merges first

**Decisions:** G3 worktree toggle like the desktop; G4 Ctrl+, opens Settings and Ctrl+B
toggles the drawer, fix the `Shortcuts.kt` Ctrl+N comment; debug `applicationIdSuffix
".debug"` with every script and doc updated; release signed with the debug keystore
(commented, temporary) so `assembleRelease` installs. Also carries the bench plumbing every
later verification needs (F7–F10, F13) and record.ts scenario 07 (F3).

### 3.1 Build and identity

- `app/build.gradle.kts`:
  - `debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }`.
  - `release { signingConfig = signingConfigs.getByName("debug") }` preceded by
    `// TEMPORARY (batch 1): signed with the debug keystore so assembleRelease produces an
    installable APK for on-device measurements (B8.3). Replace with a real release key before
    anything is distributed.`
  - `release`: `providers.gradleProperty("cline.releaseIdSuffix").orNull?.let {
    applicationIdSuffix = it }` (comment: used only by the jank bench, `.bench`, so a release
    build never replaces the owner's `dev.cline.termux`).
- `app/src/debug/res/values/strings_debug_app.xml`: `app_name` = "Cline Debug" (overrides
  the main value; check the launcher aliases' labels too — if they use their own strings,
  override those the same way so the two icons are distinguishable).
- FileProvider (F8): `AndroidManifest.xml` `android:authorities="${applicationId}.files"`;
  `K/service/ShareFiles.kt` and `K/ui/chat/rows/MediaRows.kt` use
  `BuildConfig.APPLICATION_ID + ".files"` (one constant in ShareFiles, MediaRows reads it).
- Check every other hardcoded `"dev.cline.termux"` at runtime (`grep -rn '"dev\.cline\.termux'
  app/src/main`): intent actions and extras keep their names (they are names, not the
  package); `ComponentName(this, …)` is fine; anything that names the package for
  `setPackage`/`Uri "package:"` must use `context.packageName`. The Termux RUN_COMMAND
  permission and result PendingIntent already use the own package.
- Unit test `T/BuildIdentityTest.kt`: `BuildConfig.APPLICATION_ID` ends with `.debug` in the
  debug unit-test variant; the FileProvider authority constant equals
  `APPLICATION_ID + ".files"`.

### 3.2 Debug Hub port override (F10)

- `D/debug/HubTokenOverrideReceiver.kt`: optional `--ei port <1024..65535>` stored with the
  token (`--ez clear true` clears both). `D/debug/KernelOverrides.kt`: `hubPort(context): Int?`
  (DataStore, read once with `runBlocking` at graph construction, like the existing loaded
  flag); `REL/debug/KernelOverrides.kt`: `fun hubPort(context: Context): Int? = null`.
- `K/AppGraph.kt`: `KernelCoordinator(port = KernelOverrides.hubPort(context) ?:
  HubEndpoints.DEFAULT_PORT)` and the same port for the health prober (line ≈408). Wherever
  `HubTokenStore` takes a port from the discovery record, the override wins in debug (the real
  Hub's discovery on the phone says 25463). Takes effect on the next cold start.
- Unit test: receiver parsing (valid port stored; out-of-range rejected with
  `RESULT_BAD_REQUEST`; clear removes both) if the receiver logic is extracted into a pure
  `HubOverrideRequest.parse(...)`.

### 3.3 Scripts and docs that name the package

- `scripts/install.sh`: install `app-debug.apk` (default) or a path given as `$1`; before
  installing run `aapt2 dump badging <apk> | sed -n "s/^package: name='\([^']*\)'.*/\1/p"`
  (aapt2 from `$ANDROID_HOME/build-tools/<latest>`) and **refuse if the package is exactly
  `dev.cline.termux`** ("this would replace the owner's app"). Launch with
  `am start -W -n <pkg>/dev.cline.termux.MainActivity` (F9).
- `scripts/dev-hub.sh`: `PKG=${APP_PKG:-dev.cline.termux.debug}`; `reverse` = `adb reverse
  tcp:25498 tcp:$CLINE_HUB_PORT`, then broadcast `-p $PKG --es token … --ei port 25498`, then
  `am force-stop $PKG` (next start uses the bench); `unreverse` clears the override
  (`--ez clear true`) and removes the reverse; `helper` cold-starts
  `$PKG/dev.cline.termux.MainActivity`. Update the header comment (the phone's 25463 is never
  touched any more, so the owner's app keeps its real Hub during bench work).
- `scripts/screenshot.sh`, `CLAUDE.md` (fake-kernel command lines), `scripts/gen-docs.py`
  (docs/06 — port the hand-written "Developer Hub override" section into the generator, with
  the port override and `.debug` package, F13), `docs/09-implementation-report.md` command
  lines. Rerun `python3 scripts/gen-docs.py` and check `git diff docs/06-toolchain.md` keeps
  the section.

### 3.4 G4 shortcuts

- `K/ui/navigation/Shortcuts.kt`: add `ShellShortcut.Settings` (Ctrl+`KEYCODE_COMMA`) and
  `ShellShortcut.ToggleDrawer` (Ctrl+`KEYCODE_B`); same modifier rule (Ctrl only). Fix the
  comment: the desktop has Cmd/Ctrl+N new session, Cmd/Ctrl+P command bar, Cmd/Ctrl+,
  settings (`page.tsx:758-778`) and Cmd/Ctrl+B sidebar (`sidebar.tsx`).
- `K/ui/AppShell.kt` (collector ≈478): Settings → the same navigation the drawer's Settings
  entry uses; ToggleDrawer → modal drawer open/close on compact, the list-pane toggle on
  expanded (the same action as `ListPaneToggle`). No-op while a dialog or sheet is up.
- Tests: `T/ui/navigation/ShortcutsTest.kt` (new): Ctrl+, → Settings, Ctrl+B →
  ToggleDrawer, Ctrl+Shift+B → null, Alt+Ctrl+, → null, existing K/P/N unchanged.

### 3.5 G3 worktree toggle

- New `K/core/kernel/termux/WorktreeCommands.kt` (own labels):
  - `create(cwd, id)`: `bash -c` script, args `[cwd, id]`: `root=$(git -C "$1" rev-parse
    --show-toplevel)`, `dest="$HOME/.cline/worktrees/$2/$(basename "$root")"`, `mkdir -p
    "$(dirname "$dest")"`, `git -C "$root" worktree add -b "cline/$2" "$dest" HEAD >&2`,
    print `root` and `dest` on two lines. Exit 2 with "Not a git repository: <cwd>" when
    rev-parse fails. (Escape `$` as `\$` in the Kotlin string.)
  - `remove(path)`: verify `path` matches `$HOME/.cline/worktrees/<id>/<name>` exactly (the
    desktop's `isTaskWorktreePath`), then `common=$(git -C "$path" rev-parse
    --path-format=absolute --git-common-dir)`, `git -C "$path" worktree remove --force
    "$path"`, `git -C "$(dirname "$common")" branch -D "cline/<id>"` (failure ignored), `rm
    -rf "$(dirname "$path")"` — best effort, every step's failure logged, mirrors
    `removeTaskWorktree` (`FORK/apps/examples/desktop-app/sidecar/commands.ts:950-985`).
  - `isTaskWorktreePath(path)` pure helper (exact `<root>/<id>/<repo>` shape).
- `id` = 5 lowercase hex chars from `SecureRandom` (desktop: `randomUUID` slice 5).
- Home (`K/ui/home/HomeScreen.kt` `HomeChips`): a `FilterChip` "Worktree" after the branch
  chip, shown only when the branch chip is (the workspace is a git repo); leading icon a new
  `ms_account_tree.xml` (Material Symbols Rounded, provenance row; `call_split` is already the
  branch chip's) as `ClineIcons.Chrome.Worktree`; tooltip (long-press) "Runs the task on a
  separate copy of this folder on its own branch, so your files stay untouched until you
  merge." Persisted pref `home.workInWorktree` (AppPreferences, new key at the end).
- `ChatViewModel.createSession` (Home's first send only, never resume/fork): when the toggle is
  on, run `create` before `SessionCreateBuilder.firstSend`, then use `workspaceRoot = cwd =
  dest`; a prompt sent while it runs waits (the existing `creating` hold). On a failed create
  of the worktree: error snackbar "Couldn't create a worktree: <stderr last line>", nothing
  sent. On a failed `session.create` afterwards: `remove(dest)` (rollback).
- The remembered Home workspace never becomes a worktree path: after the session starts, Home's
  last workspace stays the repo (desktop `page.tsx:1433`, `use-chat-session.ts:3812`); the
  recent-workspaces list filters task worktree paths.
- Delete (`K/ui/sessions/DeleteSessionDialog.kt` + the delete action in `SessionActions`): when
  the session's `workspaceRoot`/`cwd` is a task worktree path, the dialog adds "This session
  ran in its own git worktree. Deleting it also removes that worktree and its generated
  branch, including any uncommitted changes in it." and the delete runs `remove(path)` after
  `session.delete` succeeds.
- Strings in `R/values/strings_worktree.xml` (chip, tooltip, errors, delete warning, shortcut
  labels if any).
- Tests: `T/core/kernel/termux/WorktreeCommandsTest.kt` (args, labels, path shape accepts
  `/…/.cline/worktrees/5e0b3/repo`, rejects `/…/worktrees/5e0b3`, `/…/5e0b3/repo/sub`, other
  roots); `ChatViewModelTest` additions with a fake executor: toggle on → create runs first and
  the create spec uses dest; worktree failure → no `session.create`; create failure → remove
  called; toggle hidden for non-git workspaces; `SessionActionsTest`: worktree session delete →
  remove called after delete, non-worktree → not.

### 3.6 Fixture 07 recorder (F3)

- `tools/hub-fixtures/record.ts`: scenario `07-checkpoint-restore`: in the bench workspace
  (`--workspace`, a git repo) run two `mock-edit` turns with auto-approve, read
  `snapshot.checkpoint.history`, then the two-step restore the app does
  (`K/data/checkpoint/CheckpointActions.kt`): `session.restore` workspace-only at run 1, the
  messages split before run 2, `session.create` restored with `restoreMetadata`; finally `git
  -C <workspace> checkout -- . && git clean -fd` so the workspace is clean. Mirrors the
  existing scenario layout and sanitizer. **Do not commit a recorded fixture in P8**; the
  verification round records it (V-F07).
- No test change in P8 for 07 (the fixture replay test is added when the fixture is committed).

### 3.7 Gate

`bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug assembleRelease`, then
`apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` (debug cert) and
`aapt2 dump badging` of both APKs (`dev.cline.termux.debug`; release `dev.cline.termux`, and
with `-Pcline.releaseIdSuffix=.bench`, `dev.cline.termux.bench`).

---

## 4. P2 thinking chip from provider capabilities (A2 option 2) — no deps

**Decision:** export provider capabilities into `res/raw/fallback_providers.json`; a
reasoning-capable provider's chat models are reasoning-capable; unknown stays disabled; the live
catalog overrides; custom models from `models.json` are honoured through the live catalog.

- `tools/catalog/export-fallback-providers.ts`: import
  `sdk/packages/llms/src/index.ts` (source; the fork's `dist` is stale, built for .68), falling
  back to `dist/index.js` only when the source import fails; add `capabilities: string[]` (the
  registry's array, sorted, deduped) to every row; `schema: 2`. Update the header comment and
  the run line (no `build:sdk` needed). Regenerate `R/raw/fallback_providers.json` against the
  fork at `cli-v3.0.69` (expect 229 providers; check `openai-codex`, `deepseek`, `anthropic`
  carry `reasoning`, `openai-compatible` does not).
- `K/data/catalog/FallbackProviders.kt`: `FallbackProvider.capabilities: List<String> =
  emptyList()` (schema 1 files still parse); `FallbackProvider.supportsReasoning get() =
  "reasoning" in capabilities`. Replace the top-level `fallbackReasoningSupport(providerId,
  modelId)` by `FallbackProviders.reasoningSupport(providerId, modelId): Boolean?` with the order
  (1) model in `FALLBACK_PROVIDER_REASONING_MODELS` → true; (2) provider has `reasoning` → true;
  (3) model in `FALLBACK_PROVIDER_MODELS` → false; (4) null (chip disabled). Keep a thin
  top-level wrapper only if a caller has no `FallbackProviders` at hand; prefer injecting it
  (`K/data/model/ModelSelectionStore.kt:174`, `K/data/providers/ProviderCatalogRepository.kt:747`,
  `K/AppGraph.kt`).
- Live path unchanged: `CatalogModel.supportsReasoning = "reasoning" in capabilities ||
  reasoningOptions`, and the core maps a `models.json` `supportsReasoning` to that capability
  (`local-provider-registry.ts:371`). Add a test that pins this (a live row with the capability →
  chip on; without → off, even for a provider whose fallback says reasoning).
- Tests (`T/data/catalog/FallbackProvidersTest.kt`): schema-1 row parses with empty
  capabilities; openai-codex any model → true; deepseek `deepseek-chat` → true;
  openai-compatible `mock-model` → null; anthropic listed model → true; an unknown provider →
  null; the bundled JSON has `schema` 2 and every row a `capabilities` array;
  `ModelSelectionStore` fallback branch uses the new rule.
- Strings: none. Files touched are only P2's (no overlap).

---

## 5. P3 resilience (A4, G5, B5) — depends on P8

**Decisions:** A4: a notice on the first `run.heartbeat` without any session event, "The kernel
has been running for 30 s without responding", with Restart kernel and Keep waiting; Stop fails
after the protocol's 30 s instead of the app's 10 s and offers Restart kernel. G5: Draining
must not survive a connection-generation change. B5: the Reliability step asks again on the next
tap and opens settings only after a permanent denial. Bench: a stall marker and `hub.drain`.

### 5.1 A4 stall notice

- Heartbeats: `K/core/kernel/hub/HubFrames.kt` ignores `run.heartbeat` today; route it to the
  session reducer as a typed event `RunHeartbeat(requestId, elapsedMs, timeoutMs, timestamp)`.
- `K/data/session/SessionFactsReducer.kt` + `SessionFacts.kt`: new `stall: StallFact?`
  (`since`, `requestId`). Track `lastActivityAt` = the timestamp of the last non-heartbeat event
  of this session (any projected event: chunks, tool events, notices, capability requests,
  status) and `lastHeartbeatAt`. On a heartbeat: stalled when the run is active, no non-heartbeat
  event arrived since the previous heartbeat (or since the run started, for the first one), no
  approval / question / capability is pending for this session, no tool call is executing
  (started, not finished: a long command is the kernel working), and the heartbeat is live
  (not part of a replay; use the reducer's replay flag, or `timestamp` within 45 s of the clock).
  Any non-heartbeat event or the run's end clears `stall`.
- UI: `K/ui/chat/StallBanner.kt` (new), shown above the composer while `stall != null` and not
  dismissed for that `since`: text "The kernel has been running for 30 s without responding",
  actions "Restart kernel" (`KernelAction.RestartKernel` through `LocalShellKernelUi.onAction`,
  reusing whatever confirm that action already has in the kernel status sheet) and "Keep
  waiting" (dismisses this episode; a new episode after activity shows it again).
  `LiveRegion` polite announcement. Strings in `R/values/strings_resilience.xml`.
- Tests: reducer — heartbeat after silence → stall; heartbeat after a chunk → none; second
  heartbeat after a chunk then silence → stall; pending approval → none; tool executing →
  none; replayed heartbeat → none; chunk after stall → cleared; run end → cleared. Banner
  model: Keep waiting hides until the next episode.

### 5.2 Stop timeouts

- `K/ui/composer/ComposerController.kt` `Config.stopTimeoutMs` and
  `K/ui/approval/ApprovalController.kt` `STOP_TIMEOUT_MS`: 30_000, from one constant
  `HubCommands.DEFAULT_TIMEOUT_MS` (= the kernel's `HUB_DEFAULT_COMMAND_TIMEOUT_MS`; add it if
  missing, with a comment naming `sdk/packages/shared/src/hub.ts`). Do not touch
  `ToolCallStore.STOP_TIMEOUT_MS` (F4).
- `ComposerMessage.StopTimeout` and `ApprovalEffect.StopFailed`: the snackbar gains the action
  "Restart kernel" (same `KernelAction.RestartKernel`). Text stays "The agent did not stop. Try
  again."
- Tests: `ComposerControllerTest` / `ApprovalControllerTest` with a virtual clock — no timeout at
  29 s, timeout at 30 s; the message carries the restart action.

### 5.3 G5 Draining across a new connection

- `K/core/kernel/hub/HubClient.kt`: `_draining` is connection-scoped: reset to false when a new
  connection generation starts, before `registerAndSubscribe` reads `hub.status.draining`.
- `K/core/kernel/KernelCoordinator.kt`: on `Connected` with a changed generation, re-derive
  the state from the new connection (`draining.value` → Draining, else Ready) in addition to
  `reattachSessions`; after any sequence ends (`sequenceActive` → false), re-derive the same way,
  so an ignored `onDraining(false)` during a sequence is not lost.
- Tests (`T/core/kernel/KernelCoordinatorTest.kt`): draining true → socket drops → new generation
  with `hub.status draining=false` → Ready; same with the flip arriving during a sequence →
  Ready after the sequence; new Hub still draining → Draining; `drain_changed false` on the same
  generation → Ready (regression).

### 5.4 B5 Reliability notification step

- Pure rules in `K/ui/kernel/ReliabilityViewModel.kt` (or a new
  `NotificationRequestRules`): at tap time — granted → nothing; `knownPermanent` → open the app's
  notification settings; else launch the request and record the launch time. In the result
  callback — granted → refresh; not granted, no rationale and the callback came back in under
  400 ms (the system showed no dialog) → `knownPermanent = true` and open settings now (this tap
  meant "grant"); not granted with rationale true → remember `deniedOnce = true`; not granted, no
  rationale, slow, and `deniedOnce` → `knownPermanent = true` (second explicit denial; settings
  open on the next tap); anything else (a dismissed dialog) → nothing, the next tap asks again.
  `knownPermanent`, `deniedOnce` live in the VM's `SavedStateHandle`; on resume, a granted
  permission clears both.
- `K/ui/kernel/ReliabilityScreen.kt`: the launcher callback calls the VM; it never opens settings
  by itself except through the rule above.
- Tests (`T/ui/kernel/ReliabilityViewModelTest.kt`): first tap launches; dismissed (slow, no
  rationale, no prior denial) → next tap launches again; denied once (rationale) → next tap
  launches; instant callback → settings; second slow denial → next tap settings; grant clears.

### 5.5 Bench additions

- `tools/hub-fixtures/mock-openai.ts` marker `mock-stall`: sends the SSE headers, then **no
  bytes** for 75 s (env `MOCK_STALL_MS`), then a normal short answer.
- `scripts/bench/hub-replace.sh` (new): `drain` → `$CLI hub drain --reason "retired by newer
  install"`; `replace` → drain, then `kill -9` the pid from the discovery record, `$CLI hub
  start`, wait for `/health`, re-push the token (and port) with the debug broadcast; `undrain` →
  `$CLI hub drain --off`. Uses dev-hub.sh's variables (source a shared `scripts/bench/env.sh` or
  duplicate the four lines; never touches 25463).

Files: `HubFrames.kt`, `HubClient.kt`, `KernelCoordinator.kt`, `SessionFactsReducer.kt`,
`SessionFacts.kt`, `ChatScreen.kt` (banner slot only), `StallBanner.kt`, `ComposerController.kt`,
`ApprovalController.kt`, the two snackbar call sites, `ReliabilityScreen.kt`,
`ReliabilityViewModel.kt`, `strings_resilience.xml`, mock, `scripts/bench/hub-replace.sh`.

---
## 6. P4 session config (G1, compaction setting, Move to workspace, steer) — depends on P8, P3

**Decisions:** G1 `compaction {enabled:true}` in every `session.create`; A11 row 2 compaction
strategy LLM / Truncation / Off in Settings, through `sessionConfig.compaction` and the global
`compactionStrategy` / `compactionEnabled`; A11.8 "Move to workspace…" in the session menu;
A11 row 5 long-press Send while a run is active sends `session.send_input {delivery:"steer"}`.

### 6.1 Compaction on every create path

- Create S1 (`KernelGlobalSettings.kt` + its test) byte-identically (§1).
- New `K/data/session/SessionCompaction.kt`: `fun compactionConfig(settings:
  KernelGlobalSettings?): JsonObject` = the CLI's `resolveStartupCompactionMode` +
  `buildCliCompactionConfig` (`FORK/apps/cli/src/utils/startup-settings.ts:37`,
  `compaction-mode.ts`): `compactionEnabled == false` → `{enabled:false}`; a strategy set →
  `{enabled:true, strategy}`; nothing set, or settings unknown (null) → `{enabled:true}` (G1).
- `K/core/kernel/hub/HubSessionApi.kt`: `NewSessionSpec.compaction: JsonObject? = null`; a new
  `HubPayloads.compaction(...)` helper put into `sessionConfig` by `sessionCreate`,
  `sessionCreateRestored` and the resume path; `session.restore` too if its payload carries a
  `sessionConfig`. Keep the hunk inside `sessionConfig` building, apart from P1's
  `toolPolicies` and P5's `runtimeOptions` changes.
- `SessionConfigInput` / `SessionCreateBuilder` carry `compaction`; `ChatViewModel.homeConfig`,
  `SessionActions.forkRequest`, `SessionStore.configFromFacts` (resume), `CheckpointViewModel`
  restore all fill it from a `CompactionModeSource` (new, `K/data/session/CompactionModeSource.kt`):
  holds the last read `KernelGlobalSettings`, refreshed with `ClineCommands.readGlobalSettings`
  on app start, on Settings open, before a create when older than 60 s. Failure keeps
  `{enabled:true}`.
- Settings (`K/ui/settings/SettingsHomeScreen.kt`, end of the Models group, before
  `"customize-header"`): row "Context compaction", supporting text = current label ("LLM",
  "Truncation", "Off"), tap → single-choice dialog with the three options and the line
  "Shared with Cline in the terminal. New and resumed sessions use it." Writes with
  `KernelGlobalSettingsCommands.write`: Off → `{compactionEnabled:false}` (strategy kept, as
  `setCompactionModeGlobally` does), LLM → `{compactionEnabled:true,
  compactionStrategy:"agentic"}`, Truncation → `{…"basic"}`. Saving spinner and rollback with
  snackbar "Couldn't save the compaction setting: <reason>" (the Telemetry row pattern);
  disabled with "Start kernel to change this." when Termux commands are unavailable.
  `SettingsHomeViewModel` / `SettingsSources` get new fields at the end.
- Debug-only evidence for the phone check: when `BuildConfig.DEBUG`, log one line per create /
  resume / restore / createRestored, tag `ClineSessionCfg`, `"<command> compaction=<json>"` (no
  ids beyond the session id, no prompts, no tokens). Put the call where the `sessionConfig` is
  assembled in `HubSessionApi.kt` so it covers every path.
- The open idle session is not restarted for a compaction change in P4 (integration note 1).

### 6.2 Move to workspace… (A11.8)

- `K/ui/sessions/SessionRowMenu.kt` and the chat `HeaderOverflow`: item "Move to workspace…",
  disabled while the session's run is active (supporting tooltip "Stop the run first") and for a
  session another client is driving.
- Flow: `WorkspacePickerSheet` in a move mode (title "Move to workspace", current root
  excluded) → confirm dialog "Move this session to <folder>? It continues there with its
  messages. Checkpoints of the old folder do not follow." → `session.detach` (if attached) →
  `SessionStore.resume(sessionId, config.copy(workspaceRoot = new, cwd = new))` with a
  metadata transform that drops `checkpoint` (verify on the bench that the Hub does not merge
  the old checkpoint history back; if it does, send `checkpoint: {history: []}`). Errors: snackbar
  "Couldn't move the session: <reason>", the session stays where it was (resume the old root).
- Files: `K/data/sessions/SessionActions.kt` (`moveToWorkspace`), `SessionStore.kt` (metadata
  transform parameter on resume), `WorkspacePickerSheet.kt` / `WorkspacePickerViewModel.kt` (mode
  flag), `SessionRowMenu.kt`, `ConversationHeader.kt` overflow, `strings_session_config.xml`.
- Tests: `SessionActionsTest` — move runs detach then create with the new root and cwd, no
  `checkpoint` in metadata, same sessionId and messages; disabled when running; failure path
  re-resumes the old root.

### 6.3 Steer by long-press (A11 row 5)

- `K/ui/composer/ComposerSettingsRow.kt` `SendButton`: while busy (a run is active and the field
  has text) use `Modifier.combinedClickable(onClick = queue send, onLongClick = steer,
  onLongClickLabel = "Send now (steer)")` with `HapticFeedbackType.LongPress`; the tooltip
  (`PlainTooltipBox`, long-press) wraps the button only when idle, so the two long-presses
  never compete. While idle, long-press keeps showing the tooltip.
- `K/ui/composer/ComposerController.kt`: `steer()` = the send path with `Delivery.Steer`
  (`SessionStore.Delivery`), no queue entry; snackbar "Sent to the running agent" on accept.
- Tests: `ComposerControllerTest` — busy + long-press → `send_input` delivery steer, nothing
  queued; idle long-press → no send; busy tap → queue (unchanged).

Strings: `R/values/strings_session_config.xml`. Bench: none new (use `slow` for steer).

---

## 7. P1 approvals (A1, A′ placement) — depends on P8, P4

**Decisions:** A′ shield before the title on Home and in every chat, Ask / Auto / pending
states, two-tap menu; the kernel's global `toolAutoApprove` read through the existing cat and
written through one new Termux command (missing = on); Ask sends the terminal's seven read-only
policies; the private `tools.autoApprove` preference is replaced everywhere; approval sheet
"Approve" + "▾" with "Approve and auto-approve from now on"; Settings "Auto-approve all" in the
Kernel group; ring/disc motion when pending applies at a run end or Stop; re-read on resume,
run end and session open. Copy, colours and motion are in
[auto-approve-control.md](auto-approve-control.md) §2A, §3–§6 — implement those exactly; the
only change is the **placement** (A′): the button sits in the top app bar's navigation slot,
after `ListPaneToggle` and before the status dot / title, like a browser's site-permission
icon.

### 7.1 Data

- Create S1 byte-identically (§1) if P4 has not merged yet; after P4 it is already there.
- `HubPayloads.toolPolicies(autoApprove)`: Auto → `{"*":{autoApprove:true}}`; Ask →
  `{"*":{autoApprove:false}}` plus `{autoApprove:true}` for `ask_followup_question`,
  `ask_question`, `fetch_web_content`, `read_files`, `search_codebase`, `skills`,
  `submit_and_exit` (`FORK/apps/cli/src/runtime/tool-policies.ts:3-11`, `applyInteractiveAutoApproveOverride`).
- New `K/data/approvals/ApprovalModeRepository.kt`: `mode: StateFlow<ApprovalMode>`
  (`Unknown`, `Auto`, `Ask`); `refresh(reason)` reads with `ClineCommands.readGlobalSettings` +
  `KernelGlobalSettings.readOrNull` (unknown result keeps the last known value; with none, Ask is
  used for new sessions — the safe value — and the button shows the disabled shield);
  `set(auto)` writes `KernelGlobalSettingsPatch(toolAutoApprove = auto)` optimistically with
  rollback and an error result; `externalChanges: Flow<Boolean>` when a refresh finds a value
  different from the last one the app wrote or read (snackbar "Auto-approve all turned on outside
  the app" / "… turned off"). Refresh triggers: process foreground, session open, every run end
  in an open session (before the boundary decision), menu or Settings open, Hub reconnect, and
  `SessionStore.resume`.
- New `K/data/approvals/RunBoundaryCoordinator.kt`: per open session, compare the session's
  `metadata.autoApproveTools` with the global mode. (a) At the end of a run the app started
  (`run.completed` any reason, or user Stop) → restart if different. (b) Just before the app
  sends to an idle session → refresh, restart if different, then send (holds the send like
  `creating`). Restart = F2: `session.detach` then the existing `SessionStore.resume` path with
  the new policies. Never restart when another participant is attached or another client
  started the run (session facts' participants / run owner), nor when they already match.
- Auto-answer: `K/ui/approval/ApprovalController.kt` approves, by itself, **new** approval
  requests that arrive after the mode turned Auto, in runs the app started; requests already on
  screen stay for the user.
- Replace `prefs.autoApproveNow()` at `K/AppGraph.kt`, `K/ui/chat/ChatViewModel.kt` (≈246, 362,
  789), `K/data/sessions/SessionActions.kt` (`autoApproveDefault` lambda), `K/data/session/SessionStore.kt:521`
  (`configFromFacts`; new `autoApproveNow` dep) and `K/ui/checkpoint/CheckpointViewModel.kt:98`
  with `approvalModeRepository.autoApproveForNewSession()`; delete the
  `tools.autoApprove(.<id>)` keys from `K/data/prefs/AppPreferences.kt` and their tests; remove
  the old private toggle UI wherever it was (grep `autoApprove` in `ui/`).

### 7.2 UI

- New `K/ui/approval/ApprovalModeButton.kt`: 48 dp `IconButton` with the three states (§2A),
  `DropdownMenu` with header "Tool approvals", items "Auto-approve all" / "Every tool call runs",
  "Ask each time" / "Read-only tools still run", footer "Shared with Cline in the terminal.", the
  pending note "This run keeps auto-approving until it ends. Stop to switch now.", the desktop
  note "This session auto-approves: it was started in Cline Desktop." when `metadata.source ==
  "desktop"`. Tooltip "Tool approvals: <state>"; disabled tooltip "Start kernel to change this.";
  semantics per §2A; motion per §5 (`ClineMotion.Short4`, `Medium2`, `Short3`; reduced motion =
  instant).
- Placement A′: `K/ui/chat/ConversationHeader.kt` (navigationIcon Row, after `ListPaneToggle`)
  and `K/ui/chat/ChatScreen.kt` `HomeTopBar` (same slot). **Debug comparison switch** for the
  owner's A′ vs A check (decided A1: show both on the S25U before the branch is final): new
  `D/debug/ApprovalPlacementOverride.kt` reading the launch intent extra
  `dev.cline.termux.APPROVAL_PLACEMENT` (`title` default | `actions`) from
  `LocalActivity.current?.intent`, release stub `REL/debug/ApprovalPlacementOverride.kt` always
  `title`; `actions` places the same button before New session (design A).
- Approval sheet split button (`K/ui/approval/ApprovalSheet.kt`, §3): "Approve" + 48 dp
  `arrow_drop_down` part (CD "More approve options"), 2 dp gap, `shapes.extraSmall` inner
  corners, chevron rotates 180° while open; item "Approve and auto-approve from now on" /
  "Turns on Auto-approve all (shared with the terminal)". Approve first, then write; failure
  snackbar "Couldn't turn on Auto-approve all: <reason>".
- Settings: Kernel group, after the Kernel status row and before `"models-header"`: `PrefSwitchRow`
  "Auto-approve all" with §4's supporting text (the last sentence only while a session is
  pending), spinner + rollback, disabled without Termux.
- New icon `R/drawable/ms_gpp_good_fill.xml` (Material Symbols Rounded, FILL 1, from
  google/material-design-icons, provenance row); `ClineIcons.Approval.Auto`/`Ask` entries.
- Strings: `R/values/strings_approval_mode.xml`.

### 7.3 Tests

- `T/core/kernel/hub/HubPayloadsTest.kt`: Ask policies exactly the seven names + `*` false;
  Auto only `*` true.
- `T/data/approvals/ApprovalModeRepositoryTest.kt`: missing key → Auto; false → Ask; unreadable →
  keeps last / Unknown; write success; write failure rolls back; external change emits once;
  write command stdin `{"toolAutoApprove":true}`.
- `T/data/approvals/RunBoundaryCoordinatorTest.kt`: run end with mismatch → detach + create with
  new policies and same messages; match → nothing; other participant → nothing; another
  client's run → nothing; idle send with mismatch → restart before `send_input`; Stop → same as
  end.
- `ApprovalControllerTest`: Auto mode answers a new request; an on-screen request stays; a
  request in a run another client started stays.
- `AppPreferencesTest`: old keys gone (adjust). Compose flow test (if the suite has a header
  test): the menu needs two taps.

Bench: none new (`hello-from-mock` on the first prompt, `mock-long-output` afterwards).

---
## 8. P5 contributions (G2, A5, act-mode tool) — depends on P1

**Decisions:** G2 the `mistakeLimit` contribution answered with "Try a different approach" /
"Stop this run" through a sheet; A5 hooks (computed: hook contributions answered by running
hook scripts through RUN_COMMAND, plus a docs/02 section) — **replaced by F1's finding, see
8.3**; A11 row 4 the `switch_to_act_mode` client tool contribution. Bench: markers for the
mistake limit and the act-mode tool, and a hook script.

### 8.1 Contribution plumbing

- `K/core/kernel/hub/HubSessionApi.kt` `HubPayloads.runtimeOptions()`: `clientContributions` =
  the existing askQuestion executor + `{kind:"mistakeLimit", capabilityName:
  "mistake_limit.decide"}` + the tool (8.4). Used by every create path (create, resume,
  createRestored, fork). Never `configExtensions`, never `kind:"hook"`.
- New `K/data/session/ClientContributions.kt`: one responder for `capability.requested` events
  targeted at this client (`targetClientId == own clientId`): dispatches by `capabilityName`
  (`tool_executor.askQuestion` stays with `AskQuestionController`; `mistake_limit.decide` → 8.2;
  `custom_tool.switch_to_act_mode` → 8.4); dedupes by `requestId` (replays and reconnects must not
  answer twice); answers any other capability targeted at the app with `capability.respond {ok:
  false, error: "Unsupported capability: <name>"}` so a run never waits on the phone for
  something it cannot do. Reattach after a reconnect re-registers the same contributions (check
  `reattachSessions`).
- `K/data/session/SessionFactsReducer.kt` `capabilityRequested`: besides `ASK_QUESTION`, turn
  `mistake_limit.decide` into a `PendingQuestion` with `kind = MistakeLimit` and the context
  (`iteration`, `consecutiveMistakes`, `maxConsecutiveMistakes`, `reason`, `details`).

### 8.2 G2 mistake limit

- Question text (desktop copy, `FORK/apps/examples/desktop-app/sidecar/chat-session.ts:534-640`):
  "Cline detected repeated mistakes or tool calls and needs your guidance." / "Latest: <details,
  ≤600 chars, … when cut>" / "How should Cline continue?"; options "Try a different approach",
  "Stop this run"; free text allowed as in the ask-question sheet. Shown with the existing
  `AskQuestionSheet` (title from the kind), notification as for a question.
- Answer → decision (`capability.respond {ok:true, payload:{result: decision}}`):
  stop answers (`2`, `stop this run`, `stop`, `n`, `no`, case-insensitive) → `{action:"stop",
  reason:"stopped after mistake_limit_reached prompt"}`; otherwise `{action:"continue",
  guidance}` with guidance = "The run reached the limit for repeated mistakes or tool calls." +
  " Latest: <detail>" + " Do not repeat the same call. Re-check the tool's parameter
  requirements, fix the call, and try a different approach." + (" User guidance: <text>" when
  the answer is free text, not `1` or the option). Returned **in the decision** (the CLI's shape,
  `FORK/apps/cli/src/runtime/interactive/mistakes.ts`) — not steered, no hold hooks (F14,
  deviation). Keep `continuedThroughIteration` per session: a request whose `iteration` ≤ it is
  answered `{action:"continue"}` without asking (desktop dedupe).
- Session stop/abort/reset while the sheet is up: the pending question is dropped (the Hub
  cancels the request); no answer is sent afterwards.
- The CLI stops at once in auto-approve-all mode without asking; the app follows the desktop and
  always asks (note for the owner).
- Tests: reducer → PendingQuestion kind MistakeLimit; each stop spelling → stop decision; option 1
  → guidance without "User guidance"; free text → with it; detail truncation at 600 + "…";
  dedupe by iteration; dedupe by requestId; unknown capability → error response.

### 8.3 A5 hooks — no hook contributions (F1)

- The Hub already runs `~/.cline/hooks/*` and `<workspace>/.cline/hooks/*` for app sessions. The
  package adds **no** hook contribution and no RUN_COMMAND hook runner. It adds:
  - a guard test (`T/core/kernel/hub/HubPayloadsTest.kt`): no create payload contains
    `configExtensions` or a contribution of kind `hook`;
  - the transcript shows `session.hook` audit events if the projector does not already (check
    `ChatProjector`). Corrected after V-P5-A5 (round 1): a hook that cancels a tool does **not**
    show as the tool's error row. In core 0.0.91 the cancel stops the whole run (`beforeTool`
    → stop): no tool event, no tool result, `run.aborted` a few ms after the hook, and the
    hook's `errorMessage` is in no event, message or log, so no Hub client can show it. The
    blocked call stays in the history as a `tool_use` without a result (the next turn's model
    sees core's "Tool execution was interrupted before a result was produced.");
  - the docs/02 text below, handed to the closeout (packages do not edit docs):

    > **Hooks.** The Hub runs the user's hook files itself (global `~/.cline/hooks/<Event>.sh`,
    > workspace `.cline/hooks/<Event>.sh`) for every session, including the app's: core's
    > default config extensions include `hooks`. A hook gets the event JSON on stdin and may
    > print `{"contextModification":"…"}` to add context to the next model request, or
    > `{"cancel":true,"errorMessage":"…"}` to cancel. In core 0.0.91 a cancelling `PreToolUse`
    > stops the whole run, not only that tool call (`run.aborted`, no tool event, no tool
    > result), and its `errorMessage` reaches no client: it is in no event, message or log,
    > so the app cannot say why the run stopped (an upstream report candidate: the reason
    > belongs in `run.aborted` or a tool error). Hooks run with the session's cwd, so a session
    > whose folder does not exist on the Hub's host cannot run them (`posix_spawn` ENOENT, the
    > tool goes ahead). The app registers no hook contributions:
    > that would run each hook twice and make every tool call wait for the phone app, which
    > Android may freeze (TODO A3). `UserPromptSubmit` does not fire on the Hub path in core
    > 0.0.91.
- If V-P5-A5 contradicts F1 (hooks do not run for app-created sessions), stop and report; the
  computed RUN_COMMAND design then needs the owner's go-ahead (decided A5 asks for a fresh
  go-ahead anyway).

### 8.4 A11 row 4 `switch_to_act_mode`

- Contribution `{kind:"tool", capabilityName:"custom_tool.switch_to_act_mode", name:
  "switch_to_act_mode", description: <the CLI's text verbatim, FORK/apps/cli/src/runtime/interactive/mode.ts>,
  inputSchema:{type:"object", properties:{}}, lifecycle:{completesRun:true}}`.
- Tools are fixed per session but the app's Plan|Act is per turn (`send_input.mode`, the Hub only
  changes the mode prompt), so the tool is contributed to every session and the handler checks
  the **current turn's** mode: act → `{ok:false, error:"Already in act mode."}`; plan →
  `{ok:true, payload:{result:"You successfully switched to act mode, proceed with the plan. You
  now have access to editing files and running commands. (The switch_to_act_mode tool is only
  available in plan mode.)"}}` and mark a pending tool-initiated switch.
- When that run ends with `run.completed` reason `completed` and a tool-initiated switch is
  pending: flip the composer's mode to Act (the same state the Plan|Act control uses) and send
  "The user approved switching to act mode. Continue with the approved plan now." with `mode:
  "act"`. A user toggle in between cancels the continuation (CLI `sendTurnWithActModeContinuation`).
  `K/data/session/ChatProjector.kt` hides a user message whose text is exactly that prompt.
- The approval sheet labels the tool (if it ever asks): "Switch to Act mode".
- Tests: plan turn → success result + continuation sent with mode act after completed; aborted run
  → no continuation; act turn → error; projector hides the synthetic prompt; contribution JSON.

### 8.5 Bench additions

- `mock-openai.ts` markers: `mock-mistakes` — every turn calls a nonexistent tool
  `no_such_tool` until the last user/tool message contains "Do not repeat the same call", then
  answers "Recovered."; `mock-act-switch` — calls `switch_to_act_mode` (no args) on the first
  turn, then answers "Plan executed." when the prompt is the continuation; `mock-hook-block` /
  `mock-hook-context` — one `run_commands` call `echo hook-test` then a final answer that quotes
  any tool error / extra context it received.
- `scripts/bench/hooks.sh on|off`: installs/removes a global
  `$CLINE_DIR/hooks/PreToolUse.sh` (bench `CLINE_DIR=/tmp/cline-dev-hub/home`) that appends its
  stdin to `/tmp/cline-dev-hub/hooks/calls.jsonl` and, when the payload contains
  `mock-hook-block`, prints `{"cancel":true,"errorMessage":"blocked by bench hook"}`, when it
  contains `mock-hook-context`, prints `{"contextModification":"bench hook context"}`; never
  touches the phone.

Files: `HubSessionApi.kt`, `ClientContributions.kt` (new), `SessionFactsReducer.kt`,
`SessionFacts.kt`, `AskQuestionController.kt`, `AskQuestionSheet.kt`, `ChatProjector.kt`,
`ComposerController.kt` (mode flip), `AppGraph.kt` (wire the responder),
`strings_contributions.xml`, mock, `scripts/bench/hooks.sh`.

---

## 9. P6 composer (A11.6 prompt history, B9 full-screen editor) — depends on P4

**Decisions:** a quick horizontal fling on the **empty** field steps through the last 20 prompts
of `~/.cline/data/cache/user_input_history.jsonl`; browsing shows a chip "Recent prompt n of 20"
with an ×; a "Recent prompts" row in the attach sheet; the app appends its own sent prompts to
that file through Termux. B9: an expand icon once the text passes about three lines opens a
full-screen dialog with close, attachments and Send; closing returns the text.

### 9.1 History I/O (F5)

- New `K/core/kernel/termux/InputHistoryCommands.kt` (own labels):
  - `read()` = `cat <HOME>/.cline/data/cache/user_input_history.jsonl` (private constant).
    Parse: one JSON per line, keep string non-blank `prompt`s, `takeLast(20).reversed()` → newest
    first; a missing file (`noSuchFile`) → empty; bad lines skipped.
  - `append(prompt)` = `bun-ffi -e <script>` with the prompt on stdin, the terminal's
    `appendInputHistory` semantics (dedupe, prepend, keep 20, write oldest first, every `ts =
    Date.now()`, temp file + rename, exit 3 on a read error other than ENOENT):

```js
const fs=require("node:fs"),path=require("node:path");
const p="/data/data/com.termux/files/home/.cline/data/cache/user_input_history.jsonl";
const prompt=(await Bun.stdin.text()).trim(); if(!prompt){process.exit(0)}
let entries=[]; try{for(const line of fs.readFileSync(p,"utf8").split("\n")){if(!line.trim())continue;try{const v=JSON.parse(line);if(typeof v.prompt==="string"&&v.prompt.trim())entries.push(v.prompt)}catch{}}}catch(e){if(e.code!=="ENOENT")process.exit(3)}
const recent=[prompt,...entries.reverse().filter(x=>x!==prompt)].slice(0,20).reverse();
fs.mkdirSync(path.dirname(p),{recursive:true}); const tmp=p+".app-"+process.pid+".tmp";
fs.writeFileSync(tmp,recent.map(x=>JSON.stringify({prompt:x,ts:Date.now()})).join("\n")+"\n"); fs.renameSync(tmp,p);
```

  (Check against `FORK/apps/cli/src/utils/input-history.ts` once more: if the terminal trims or
  keeps the raw prompt, match it exactly.)
- New `K/data/composer/InputHistoryRepository.kt`: cached list, `refresh()` on composer focus /
  attach-sheet open (at most once per 30 s), `record(prompt)` after a send is Accepted (fire and
  forget, failures logged, never shown), the in-memory list updated at once.

### 9.2 Fling and chip

- `K/ui/composer/ComposerField.kt`: a `pointerInput` horizontal-drag detector **inside the field**,
  only while the text is empty or the field shows a history entry unchanged; a fling = |dx| ≥
  48 dp and velocity ≥ 1 000 dp/s, |dx| > 2·|dy|; ignore drags that start within 24 dp of either
  screen edge (system back / drawer). Fling left to right = older (n+1), right to left = newer
  (n-1, at 0 leaves browsing and clears the field); A18 (2026-10-08) reversed batch 1's direction,
  which had left = older. Haptic `TextHandleMove` per step. Typing or editing the text ends
  browsing (the text stays). TalkBack: custom accessibility actions "Previous prompt" / "Next
  prompt" on the empty field.
- `ComposerController` state `history: HistoryBrowse?(index, total)`; the chip "Recent prompt
  n of N" (N = list size ≤ 20) with a trailing × (CD "Stop browsing", clears the field) above the
  field, `AssistChip` style of the composer's other chips (A18, 2026-10-08: compact instead, no
  outline and no leading icon, the label and the × only).
- Attach sheet (`K/ui/composer/AttachSheet.kt`): row "Recent prompts" (icon `ms_history`) opens a
  list sheet of the 20 (newest first, two lines max each); tapping one fills the field (browsing
  state at that index).

### 9.3 Full-screen editor (B9)

- `ComposerField`: when the text layout has > 3 lines, an `open_in_full` `IconButton` (CD
  "Expand editor") at the field's trailing edge.
- New `K/ui/composer/FullScreenEditor.kt`: `Dialog(properties = DialogProperties(
  usePlatformDefaultWidth = false, decorFitsSystemWindows = false))`, `Scaffold` with a
  `TopAppBar`: close (CD "Close editor", returns the text), the attach action, Send (enabled as
  in the composer; sends and closes). The body is the **same `TextFieldState`** (no copy, so
  closing loses nothing), `imePadding`, attachments row under the top bar. Back = close.
  Shift+Enter inserts a newline, Enter does what the composer's Enter does (respect the existing
  hardware-keyboard rule).
- Strings: `R/values/strings_composer_history.xml`.

### 9.4 Tests

- `T/core/kernel/termux/InputHistoryCommandsTest.kt`: parse order (file oldest first → list
  newest first), skips bad lines, 20 cap, missing file → empty, append command stdin = prompt,
  script has no `$`.
- Run the append script with workstation `bun` against a `/tmp` copy (a test helper that rewrites
  `p`): dedupe, cap, order, ENOENT creation, unreadable file → exit 3 untouched.
- `ComposerControllerTest`: fling on empty → entry 1; again → 2; newer (right to left since A18)
  at 1 → empty and no chip; typing ends browsing; fling ignored with text; send of a history entry
  records it once; Accepted only.
- Fling detector rule as a pure function (`HistoryFlingRules`): thresholds, edge exclusion,
  direction.
- Editor: closing keeps the text (state shared); Send closes.

---

## 10. P7 transcript (A11.9 Message details, A13 fork icon) — depends on P1, P4, P5

**Decisions:** Settings "Message details: Brief / Verbose"; Verbose adds tokens, cost and
duration to the revealed row and shows recoverable mid-run errors inline. A13: the message-row
fork becomes "Fork before this turn" with its own icon; choose between `ms_call_split` and a
custom glyph (the branch arm without the stem); show both on the S25U.

### 10.1 Message details

- Pref `transcript.messageDetails` (`brief` default | `verbose`) in `AppPreferences` (new key at
  the end).
- Settings: App group, last row before `"kernel-header"`: "Message details", supporting "Brief" /
  "Verbose", single-choice dialog; Verbose supporting text "Shows tokens, cost and duration on
  revealed messages, and recoverable errors in the transcript."
- Stats: from `run.completed` `result {usage, iterations, durationMs}` (usage fields as in
  `AgentUsage`/`AgentTokenUsage`, `FORK/sdk/packages/shared/src/agent.ts:118`: input/output/cache
  tokens and `totalCost?`) per turn (`TurnRecord`); the tap-to-reveal row in
  `K/ui/chat/rows/MessageActions.kt` / `MessageBubble.kt` adds, in Verbose, "1.2k in · 340 out
  · $0.004 · 8.1 s" (format helper in `SessionFormat`/new `TurnStatsFormat.kt`; cost only when
  known; locale-aware numbers).
- Notices (F6): `K/data/session/ChatProjector.kt` — in Brief, `session.notice` with `noticeType
  == "recovery"` and reason in {`invalid_tool_call`, `tool_execution_failed`,
  `completion_without_submit`, `mistake_limit`} is not projected; `api_error` rows (with the
  credential fix) and status/stop notices stay. In Verbose all are rows, recovery ones styled
  as a muted error row. The projector takes the mode as an input (re-project on change).
- Tests: projector filter in both modes for each reason; stats formatting (no cost, thousands,
  sub-second); pref default Brief.

### 10.2 A13 fork icon

- `K/ui/chat/rows/MessageActions.kt`: the message-row action text "Fork before this turn",
  tooltip "Fork before this turn - start a new session with the history before this message";
  change `msg_fork` / `msg_fork_tooltip` values in place (they are only used there — check
  with grep; if the session-menu fork uses them, add new strings instead and leave those).
- New `R/drawable/ms_fork_from_here.xml`: `ms_fork_right.xml`'s path with the upper stem and its
  up-arrow removed (keep the lower stem and the right arm with its arrowhead), viewport 960,
  provenance comment "derived from Material Symbols Rounded fork_right (Apache-2.0)" + README
  row. `ClineIcons.Chrome.ForkBeforeTurn`.
- **Debug side-by-side switch:** new `D/debug/ForkIconOverride.kt` reading the launch extra
  `dev.cline.termux.FORK_ICON` (`custom` default | `call_split` | `fork_right`) from
  `LocalActivity.current?.intent`; release stub `REL/debug/ForkIconOverride.kt` always custom.
  The recommendation is `custom` (F12: `call_split` is already the Home branch chip). The
  final choice is the owner's after V-P7-A13.
- Strings: `R/values/strings_message_details.xml`.
- Tests: the action uses the new label; override parsing (unknown → custom).

---
## 11. Verification plan (one phone round after integration)

Run on the integrated branch, one agent on the S25U at a time, phone unlocked and plugged in.
Never install, launch or clear `dev.cline.termux` (the owner's app). Real-kernel prompts use
**DeepSeek** (few, short, benign, read-only; titles start with `[test] `; workspace
`/data/data/com.termux/files/home/tmp/app-test-ws`); Astra (openai-codex) only for the thinking
chip, without a prompt. Screenshots go to `~/.claude/jobs/02338d87/tmp/verify/<id>-<n>.png`
(`$ADB -s $S exec-out screencap -p > …`). UI driving: `uiautomator dump` + `input tap`, or
`input keyevent`/`keycombination`.

Common variables: `ADB=$HOME/Android/Sdk/platform-tools/adb`, `S=$SERIAL`,
`PKG=dev.cline.termux.debug`, `ACT=$PKG/dev.cline.termux.MainActivity`,
`BK='~/tmp/batch1-backup'`. Device shell commands go through `ssh -o ServerAliveInterval=15
<phone> bash -s <<'REMOTE' … REMOTE` (the login shell is zsh; never name a variable `status`).

| Id | Pkg | Real model | Steps | Expected |
| --- | --- | --- | --- | --- |
| V0 | setup | no | 1. `ssh <phone> 'mkdir -p ~/tmp/batch1-backup && cp -n ~/.cline/data/settings/global-settings.json ~/tmp/batch1-backup/ && cp -n ~/.cline/data/cache/user_input_history.jsonl ~/tmp/batch1-backup/ && ls -l ~/tmp/batch1-backup'`. 2. `bash scripts/build.sh assembleDebug`; `bash scripts/install.sh` (installs `$PKG`, `-r -g`). 3. Walk onboarding on the real kernel without any config write (skip Connect a model; the owner's providers exist). 4. `$ADB -s $S shell pm list packages \| grep dev.cline.termux`. | Both backups exist; `package:dev.cline.termux` and `package:dev.cline.termux.debug` both listed; the debug app reaches Home connected to the real Hub (25463); the owner's app untouched (`dumpsys package dev.cline.termux \| grep lastUpdateTime` unchanged from before). |
| V-P8-ID | P8 | no | 1. `aapt2 dump badging app/build/outputs/apk/debug/app-debug.apk \| head -1`. 2. Try `bash scripts/install.sh <path to an APK whose package is dev.cline.termux>` (the plain release APK). 3. Launcher: screenshot of the app drawer. | 1 says `name='dev.cline.termux.debug'`; 2 refuses with the "would replace the owner's app" message and installs nothing; 3 shows "Cline" and "Cline Debug" side by side. |
| V-P8-G4 | P8 | no | With a hardware-keyboard event path: `$ADB -s $S shell input keycombination KEYCODE_CTRL_LEFT KEYCODE_COMMA` from a chat; back; `input keycombination KEYCODE_CTRL_LEFT KEYCODE_B` twice; `KEYCODE_CTRL_LEFT KEYCODE_N`. Screenshot each. | Ctrl+, opens Settings; Ctrl+B opens then closes the drawer (or toggles the list pane on an expanded window); Ctrl+N still opens a new session. |
| V-P8-G3 | P8 | no (bench) | 1. `scripts/dev-hub.sh start && scripts/dev-hub.sh reverse` (pushes token + port 25498, force-stops `$PKG`); start `$ACT`. 2. Make sure the phone repo exists: `ssh <phone> 'cd ~/tmp/app-test-ws && (git rev-parse --git-dir >/dev/null 2>&1 \|\| (git init -q && git commit -q --allow-empty -m init))'`. 3. Home: pick workspace `~/tmp/app-test-ws`, turn on the Worktree chip, send `[test] hello-from-mock`. 4. `ssh <phone> 'git -C ~/tmp/app-test-ws worktree list; ls ~/.cline/worktrees'`. 5. Session details sheet: cwd. 6. Delete the session (read the dialog), then repeat step 4. 7. Kill the app, cold start: Worktree chip state persisted; Home workspace is still `app-test-ws`. | Step 4 lists `~/.cline/worktrees/<5 hex>/app-test-ws [cline/<id>]`; the session's cwd is that path; the delete dialog shows the worktree warning; after delete the worktree, its `<id>` directory and branch `cline/<id>` are gone; the chip state persisted; the remembered workspace never became the worktree path. |
| V-P2-A2 | P2 | no (Astra/DeepSeek chosen, no prompt) | Real kernel (`scripts/dev-hub.sh unreverse`, cold start). Settings helper not running (fallback catalog): open the model picker, choose Astra (openai-codex) model, screenshot the composer brain chip; choose DeepSeek `deepseek-chat`, screenshot; on the bench (`reverse`) with the mock `openai-compatible` model, screenshot. Then `scripts/dev-hub.sh helper` (live catalog) and repeat the Astra check. | Brain chip enabled for Astra and DeepSeek in fallback and in live; disabled for the mock (openai-compatible, no `reasoning`). No prompt sent. |
| V-P3-A4 | P3 | no (bench) | Bench. In an app-created session send `[test] mock-stall please`. Watch for 30–60 s, screenshot the banner. Tap Keep waiting; screenshot. Do **not** tap Restart kernel on the bench: it restarts the phone's real Hub through Termux; its routing is covered by the unit test, here only check the button is present and labelled. | Banner "The kernel has been running for 30 s without responding" with Restart kernel and Keep waiting appears after the first silent heartbeat (≈30 s, at most ≈60 s); Keep waiting hides it and the answer arrives at ≈75 s; no banner during `slow` (40 chunks) or `mock-long-command`. |
| V-P3-STOP | P3 | no (bench) | Bench. Send `[test] mock-stall please`; tap Stop at once; time it. (The mock keeps the stream open; if the Hub aborts promptly the timeout cannot be seen: then stop the mock process with `kill -STOP <mock pid>` before tapping Stop, and `kill -CONT` afterwards.) | No "did not stop" message before 30 s; if the stop never confirms, at 30 s the snackbar "The agent did not stop. Try again." with action Restart kernel. |
| V-P3-G5 | P3 | no (bench) | Bench, app on a chat. `scripts/bench/hub-replace.sh replace` (drain "retired by newer install", kill -9, new Hub, token re-push). Screenshot after 10 s and 30 s. Then `drain` alone, screenshot, `undrain`, screenshot. | After replace: the app reconnects and shows Ready (no "Cline Hub is updating…" spinner) within the reconnect time; `drain` alone shows the Draining line; `undrain` clears it. |
| V-P3-B5 | P3 | no | 1. `$ADB -s $S shell pm revoke $PKG android.permission.POST_NOTIFICATIONS && $ADB -s $S shell pm clear-permission-flags $PKG android.permission.POST_NOTIFICATIONS user-set user-fixed`. 2. Open Kernel status → Keep the kernel running (Reliability) → Notifications action: dismiss the dialog with Back. 3. Tap again. 4. Deny; tap; deny again; tap. 5. Restore: `pm grant $PKG android.permission.POST_NOTIFICATIONS`. | 2–3: the system dialog appears again on the second tap (settings not opened); after the second explicit denial the next tap opens the app's notification settings; after the restore the item shows granted. |
| V-P4-G1 | P4 | no (bench) | Bench. `$ADB -s $S logcat -c`. Create a session from Home (`[test] hello-from-mock`), then resume an older bench session (send to it) and fork one. `$ADB -s $S logcat -d -s ClineSessionCfg`. | Every `session.create` carries `sessionConfig.compaction` = `{enabled:true}` when the global keys are absent. |
| V-P4-A11.2 | P4 | no (bench + phone file) | V0 backup in place. Settings → Context compaction → Truncation; `ssh <phone> 'cat ~/.cline/data/settings/global-settings.json'`; new bench session; `logcat -d -s ClineSessionCfg` (as V-P4-G1). Then Off, then LLM, same checks. Restore: `ssh <phone> 'cp ~/tmp/batch1-backup/global-settings.json ~/.cline/data/settings/global-settings.json'`. | File gets `compactionEnabled:true, compactionStrategy:"basic"` → `compactionEnabled:false` (strategy kept) → `true/"agentic"`, other keys unchanged; sessions get `{enabled:true,strategy:"basic"}`, `{enabled:false}`, `{enabled:true,strategy:"agentic"}`; the row shows the label; file restored byte-identical (`cmp`). |
| V-P4-A11.8 | P4 | no (bench) | Bench, a finished app-created session with 2 turns. Session menu → Move to workspace… → pick `/data/data/com.termux/files/home/tmp` (or another listed folder) → confirm. Session details; send `[test] hello-from-mock`; `snapshot.checkpoint.history` via a probe. Try the action while a `slow` run is active. | Same session id, same messages, workspaceRoot/cwd changed; old checkpoints absent; disabled while running. |
| V-P4-STEER | P4 | no (bench) | Bench. Send `[test] slow please`; while streaming type `steer-check` and long-press Send. `grep -c steer-check /tmp/cline-dev-hub/mock/requests.jsonl`; queue strip screenshot. Idle long-press shows the tooltip only. | Haptic; nothing queued; the next mock request of the same run contains `steer-check` (count ≥ 1); idle long-press only shows the tooltip. |
| V-P1-A1 | P1 | no (bench + phone file) | V0 backup in place. Bench. 1. Home: shield state matches the file (owner file has no key → Auto disc). Screenshot. 2. Tap → menu (screenshot) → Ask each time. `cat` the file (`toolAutoApprove:false`). 3. New session `[test] hello-from-mock`: approval appears; read-only tools would not ask. 4. In the sheet: ▾ → Approve and auto-approve from now on; file → true; the header disc grows. 5. Start `[test] slow please` in Auto, switch to Ask mid-run: ring (pending) + menu note; let it end: ring-letting-go motion (record with `$ADB -s $S shell screenrecord --time-limit 20 /sdcard/Download/a1-end.mp4`, then `adb pull` it into the verify folder and delete it from the phone). 6. Repeat 5 with Stop. 7. Next send in that session: approval appears (restart applied; `metadata.autoApproveTools` false). 8. Edit outside the app (the file says true after step 4): background the app, `ssh <phone>` set `toolAutoApprove` to false with a one-line `bun-ffi -e` merge on the file, bring the app to the foreground: snackbar "Auto-approve all turned off outside the app" and the Ask shield. 9. Settings → Kernel → Auto-approve all switch mirrors and changes it. 10. Restore the file (`cp` from backup, `cmp`). | States/colours as auto-approve-control.md §2A; two taps needed; the file follows each change with other keys intact; Ask session asks for `run_commands`; pending ring and both end motions; restart before the next send; external-change snackbar; Settings switch works; file restored identical. |
| V-P1-AP | P1 | no | Owner's A′ vs A check: cold start `$ACT --es dev.cline.termux.APPROVAL_PLACEMENT title`, screenshot a chat header and Home at the phone's width; again with `actions`. Optionally repeat at a 360 dp width with `$ADB -s $S shell wm density <dpi that gives 360 dp>` and restore with `wm density reset` at once. | Two screenshot pairs for the owner (shield before the title vs beside "+"); the title keeps ≥ 96 dp in both. |
| V-P5-G2 | P5 | no (bench) | Bench, app-created session (Ask or Auto). Send `[test] mock-mistakes please`. When the sheet appears, screenshot; choose Try a different approach. Second run: choose Stop this run. | Sheet with the desktop question text and the two options; Continue → the mock sees "Do not repeat the same call" and answers "Recovered."; Stop → the run ends with reason "stopped after mistake_limit_reached prompt"; one question per limit (no duplicate after a reconnect). |
| V-P5-A5 | P5 | no (bench) | Bench. `scripts/bench/hooks.sh on`. Not an app-created session: hooks spawn in the session's cwd, and an app-created bench session has a phone path the workstation lacks (`posix_spawn` ENOENT, the tool goes ahead). Seed a session with the isolated CLI in `/tmp/cline-dev-hub/workspace` (see `scripts/bench/hooks.sh`) and drive it from the app: `[test] mock-hook-block please`, then `[test] mock-hook-context please`. `cat /tmp/cline-dev-hub/hooks/calls.jsonl`; `grep -c 'bench hook context' /tmp/cline-dev-hub/mock/requests.jsonl`. `hooks.sh off`. Optional real-kernel check only if the owner already has hooks: none is added to the real `~/.cline`. | The hook log has one PreToolUse entry per tool call (not two: no app contribution); the block stops the run (`run.aborted`, the tool never runs; core 0.0.91 delivers no "blocked by bench hook" text to any client, corrected after round 1); the context reaches the model request. If no entry appears, F1 is contradicted: stop and report. |
| V-P5-ACT | P5 | no (bench) | Bench. Plan mode, `[test] mock-act-switch please`. Screenshot composer mode and transcript. Then in Act mode send the same marker. | Plan run ends after the tool; the composer flips to Act; a continuation turn runs (mock answers "Plan executed.") and its synthetic prompt is not shown; in Act the tool returns "Already in act mode." |
| V-P6-HIST | P6 | no (phone file) | V0 backup in place. `ssh <phone>` write a test history file of 20 lines `{"prompt":"[test] history n","ts":…}` (oldest n=1). Real kernel or bench (history is Termux-side either way). Empty composer: `$ADB shell input swipe 300 <fieldY> 700 <fieldY> 80` (left to right since A18, inside the field, away from edges; batch 1 ran 700 → 300 under its old direction) ×2, screenshot (chip "Recent prompt 2 of 20", field "[test] history 19"); swipe right to left ×2 → empty, chip gone; swipe once, tap ×; attach sheet → Recent prompts row → pick one. Send one entry on the bench (`[test] history 7 hello-from-mock`), then `ssh … tail -2 ~/.cline/data/cache/user_input_history.jsonl`. Restore the file (`cp`, `cmp`). | Order newest first; chip and × behave; fling ignored with text in the field; a swipe from the screen edge goes back instead; the sent prompt is now the file's last line once, without duplicates, 20 lines; restored identical. |
| V-P6-B9 | P6 | no | Type four lines (`input text` + `input keycombination KEYCODE_SHIFT_LEFT KEYCODE_ENTER`). Expand icon appears; tap; screenshot; type more; close; screenshot; expand again and Send (bench). | Icon only after > 3 lines; full-screen dialog with close, attachments and Send; closing returns all text; Send sends and closes. |
| V-P7-A11.9 | P7 | no (bench) | Bench. Settings → Message details → Verbose. Run `[test] mock-mistakes please` and `[test] hello-from-mock`; tap the assistant message to reveal; `[test] mock-api-error please` for an API-error row (the mock answers HTTP 400; `mock-api-error-503` for a server error). Switch to Brief, reopen the session. | Verbose: tokens · cost (when known) · duration on the revealed row; Brief: no stats, api_error rows still shown in both. Recovery notices: none on core 0.0.91, which drops recoverable agent errors (`session-event-projector.ts` `projectAgentErrorEvent` returns early on `recoverable`); waiting on an upstream Hub change (round 1). |
| V-P7-A13 | P7 | no (bench) | Bench session with 2 turns. Cold start with `--es dev.cline.termux.FORK_ICON custom`, reveal a message row, screenshot (crop the action row); again with `call_split` and `fork_right`; tap Fork before this turn once. | Three screenshots side by side for the owner (custom recommended); label "Fork before this turn"; the fork contains only the history before that message (`beforeRunCount`). |
| V-B8.3 | P8 | **yes (DeepSeek, 1 prompt)** | 1. `bash scripts/build.sh assembleRelease -Pcline.releaseIdSuffix=.bench`; `aapt2 dump badging app/build/outputs/apk/release/app-release.apk \| head -1` must say `dev.cline.termux.bench`. 2. `bash scripts/install.sh app/build/outputs/apk/release/app-release.apk`. 3. Onboarding on the real kernel, no config writes; workspace `~/tmp/app-test-ws`; model DeepSeek. 4. `$ADB -s $S shell dumpsys gfxinfo dev.cline.termux.bench reset`. 5. Send `[test] Explain in about 500 words how a CPU cache works. Plain prose, no lists.`; keep the transcript at the bottom until the reply ends. 6. `$ADB -s $S shell dumpsys gfxinfo dev.cline.termux.bench > …/verify/B8.3-gfxinfo.txt`. 7. Delete the `[test]` session; `$ADB uninstall dev.cline.termux.bench`. | Report "Janky frames: x (y %)" plus 50/90/95/99th percentiles vs the 30 % debug baseline; the owner's app untouched; session deleted, `.bench` uninstalled. |
| V-F07 | P8 | no (bench, workstation only) | `scripts/dev-hub.sh start`; make sure `/tmp/cline-dev-hub/workspace` is a clean git repo; `CLINE_DIR=/tmp/cline-dev-hub/home CLINE_HUB_PORT=25498 bun tools/hub-fixtures/record.ts --only 07 --workspace /tmp/cline-dev-hub/workspace --out app/src/test/resources/hub-fixtures`; `bun tools/hub-fixtures/sanitize-mock-log.ts` as the other fixtures; inspect the JSONL for tokens. | `07-checkpoint-restore.jsonl` with `snapshot.checkpoint.history` (2 entries), the workspace-only `session.restore` response and the restored `session.create`; no tokens or home paths; the bench workspace clean afterwards; file left uncommitted for the integrator (with a replay test then). |
| VZ | cleanup | no | Delete every `[test]` session (bench and real); `scripts/bench/hooks.sh off`; `scripts/dev-hub.sh unreverse && scripts/dev-hub.sh stop`; restore and `cmp` both backups; `pm grant $PKG android.permission.POST_NOTIFICATIONS` if revoked; `wm density reset` if changed; `ssh <phone> 'git -C ~/tmp/app-test-ws worktree prune; ls ~/.cline/worktrees 2>/dev/null'`; leave `$PKG` installed. | Owner's files identical to the backups; no test sessions, worktrees, reverses or bench processes left; owner's app untouched. |

Notes for the verifier:

- The bench Hub only replaces the WebSocket: the app's Termux commands still run on the phone
  (F10), hence the backups. App-created bench sessions have phone paths, so bench `run_commands`
  fails with ENOENT after approval; that is expected and does not affect the checks above.
- Keep Termux in the foreground on the S25U during real-kernel steps (port CLAUDE.local.md).
- If the phone answers ping but not ssh, it is in Wi-Fi doze: retry with a longer
  `ConnectTimeout`.

## 12. Items for the owner (closeout)

1. **A5 deviation (F1):** no hook contributions; the Hub already runs hook files for app
   sessions. Built: guard test, bench check, docs/02 text. The RUN_COMMAND hook runner is not
   built unless the bench contradicts F1 (A5 asked for a fresh go-ahead in any case).
2. **Default on (F11):** the owner's `global-settings.json` has no `toolAutoApprove`, so the app
   (like the terminal) shows Auto-approve all from the first start of the new build.
3. **Mistake limit (F14):** guidance is returned in the decision (CLI shape); the desktop's
   steer + hold hooks are not copied (A3 hazard). The app asks even in Auto (desktop behaviour;
   the CLI stops at once in auto mode).
4. **A′ vs A (A1):** screenshots of both placements (V-P1-AP) before the P1 branch is final.
5. **Fork icon (A13):** three screenshots (V-P7-A13); recommendation custom.
6. **Jank build id (F7):** measured as `dev.cline.termux.bench`, so the owner's app is never
   replaced; the release signing with the debug key is temporary and commented.
7. **Compaction for an open idle session:** applies at its next create/resume unless the
   integration follow-up (§2 note 1) wires it into P1's restart.
8. **Real config writes during verification:** V-P1-A1, V-P4-A11.2 and V-P6-HIST write the
   owner's `global-settings.json` / `user_input_history.jsonl` through Termux (the features'
   own mechanism); both are backed up first and restored byte-identical.
