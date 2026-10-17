# 10 — Batch 1 report (2026-10-07)

For the owner. Batch 1 builds the first set of decisions in [decided.md](decided.md) as eight
packages, P1 to P8. They were merged into the local branch `batch/decided-1`, verified on the
S25U in one device round and then closed out. Nothing has been pushed and `main` is untouched.
The kernel is `v3.0.69-termux.1` (core 0.0.91). The package briefs are in
[research/batch1-briefs.md](research/batch1-briefs.md). The contract changes are in
[02-kernel-contract.md](02-kernel-contract.md) under "Batch 1 additions", and the app decisions
in [07-app-architecture.md](07-app-architecture.md) §4.8 and §15 D20–D29.

Evidence (screenshots, logcat extracts, gfxinfo dumps, three screen recordings) is in
`screenshots/batch1-verify/` (477 files, local only, not in git). It is named by item id,
for example `V-P7-A13-side-by-side.png`.

## 1. Summary

- **Built:** all eight packages, none dropped. Five of the eight merges conflicted; all were
  resolved by hand (§2.2). Two fixes followed the device round:
  - `941e886`: the A1 run-boundary restart (V-P1-A1 had failed);
  - `449ccc7`: a checkpoint defect that fixture 07 exposed.
- **Verified on the S25U:** 25 items. 22 passed. 3 failed:
  - V-P1-A1: fixed in `941e886`, and the close-out recheck on the device passed (§3.2).
  - V-P5-A5: its block half. Core 0.0.91 stops the run and passes the hook's reason to no
    client, so the expectation was corrected; the stale "Running" row is trial-log row 9.
  - V-P7-A11.9: its recovery-notice half needs an upstream Hub change; the app cannot fix it.

  One item was only partly testable: the G4 list-pane toggle needs an expanded window, which the
  S25U does not have.
- **Jank** on a release build against the real kernel: 0.77 % janky frames, p90 9 ms. The debug
  baseline was 30 to 34 %.
- **Gate on the final code:** see §4.
- **Waiting for you:**
  - three choices: the A′ or A placement, the A13 glyph, and whether the A13 fork cuts the
    history or forks the whole session;
  - four deviations to confirm (§6);
  - nine new trial-log rows ([todo.md](todo.md) rows 8 to 16).

## 2. What was built

### 2.1 Packages

Merge order: P8, P2, P3, P4, P1, P5, P6, P7. Every package passed the gate on its own branch
before it was merged.

| Pkg | Decisions | Package commits | Merge | What it does |
| --- | --- | --- | --- | --- |
| P8 | R1 suffix, G3, G4, B8.3 build | `4bc938e` | `e989a67` | **Build identity.** The debug build is `dev.cline.termux.debug` ("Cline Debug", version 0.1.0-debug). The release is `dev.cline.termux`, signed with the debug key (TEMPORARY). `-Pcline.releaseIdSuffix=.bench` gives `dev.cline.termux.bench`. The FileProvider authority is `${applicationId}.files`. **Hub port override** (debug only): `--ei port`. **Scripts:** `install.sh` refuses the owner's package id; `dev-hub.sh reverse` uses 25498 and leaves the phone's 25463 alone. **G4:** Ctrl+, opens Settings, Ctrl+B toggles the drawer. **G3:** a Worktree chip on Home. The app creates the worktree before the first send and removes it when the session is deleted, with the desktop's warning. **Fixture recorder:** `record.ts` gets scenario 07. |
| P2 | A2 | `1db7b8b` | `ecd29b7` | The bundled provider list is exported from the registry with each provider's `capabilities` (schema 2: 229 providers, 209 with reasoning). In fallback mode a reasoning-capable provider's models get the thinking chip. The live catalog decides per model, as before. |
| P3 | A4, G5, B5 | `a1cb339` | `785ad5a` | **Stall notice** on a silent `run.heartbeat`, with Keep waiting and Restart kernel. **Stop** waits 30 s and offers Restart kernel. **Drain:** the draining flag belongs to the connection, and a replayed drain event is ignored. **Notifications:** the system dialog is asked again until a permanent denial. **Bench:** `mock-stall` and `scripts/bench/hub-replace.sh`. |
| P4 | G1, A11 rows 2 and 5, A11.8 | `954fc1c`, `24cc18b`, `dd7f821` | `498df7d` | **G1:** `sessionConfig.compaction` on every create path. **Context compaction** setting (LLM, Truncation, Off), shared with the terminal. **Move to workspace…** by delete-then-create under the same id, with a recovery copy in app storage; sessions with agents are refused. **Steer:** long-press Send during a run. |
| P1 | A1, A′ | `3820da4`, `7f4e37c` | `7955395` | **Auto-approve control.** It uses the kernel's global `toolAutoApprove`. A shield before the title shows Ask, Auto or Pending, with a two-tap menu. The approval sheet gets "Approve and auto-approve from now on", and Settings → Kernel gets an "Auto-approve all" switch. The app restarts a session at a run boundary when its policy differs. External changes are noticed. The app posts no notification for an approval it answers itself. |
| P5 | G2, A5, A11 row 4 | `53db15f`, `b60e84a`, `f37ceee` | `9d7efe6` | **Client contributions:** a mistake-limit question sheet with the desktop's text, and `switch_to_act_mode` for plan sessions. After the switch the session is rebuilt in act mode and the plan continues. **No hook contributions** (F1), guarded by a test. **Bench:** the mock markers and `scripts/bench/hooks.sh`. |
| P6 | A11.6, B9 | `991c929` | `7ae38cb` | **Prompt history,** shared with the terminal's `user_input_history.jsonl`. A fling on the empty field steps through it, a chip shows "Recent prompt n of N" with ×, and the attach sheet has a "Recent prompts" row. **Full-screen editor** once the text passes 3 lines. |
| P7 | A11.9, A13 | `8d9ab95`, `190c41b`, `8758b1e` | `0ac65e1` | **Message details** setting (Brief or Verbose); Verbose adds tokens, cost and duration to the revealed answer row. Run counts stay right after compaction. **A13:** "Fork before this turn", with four candidate glyphs behind a debug extra. |

### 2.2 Integration and close-out commits

- **Merge conflicts.** P4, P1, P5, P6 and P7 conflicted. The case that needed a real decision
  was the two restart paths: P1's run-boundary restart and P5's act-mode rebuild. They now
  share one per-session gate, so they can never detach the same session at once. P5's rebuild
  also carries the compaction state now, as P1's does.
- `6d9be7f`: restores `forkRequest`'s arguments, which the P7 merge had broken.
- `941e886` fixes V-P1-A1. The cause: core 0.0.91 stamps a resumed session's policy only in
  memory, and the records at a run's end carry the creation-time value again. The app now
  trusts the policy of the runtime's latest create it saw (`SessionState.createdAutoApprove`).
  The same commit adds `mock-api-error[-<status>]` and corrects the hook text in the briefs.
- `449ccc7` (close-out) commits fixture 07 with its replay test, `CheckpointRestoreFixtureTest`.
  That test found a defect from Phase 0, now fixed:
  - The app wrote a restored or forked session's inherited checkpoint `createdAt` as a string.
    Its own reader accepted only numbers, so it dropped those entries.
  - Core writes a number and reads with `Number()`; the app now does both (D29).
- Close-out docs: docs/02 (via `scripts/gen-docs.py`), 07, 08, 09, this report, decided.md,
  todo.md, the app's CLAUDE.md, and a regenerated `docs/strings-audit.md` (HC: 134 by key, 36 by
  text, 7 missing, as P7 expected).
- The close-out also re-ran V-P1-A1 on the device after `941e886` (§3.2).

## 3. Verification

### 3.1 Round 1 (S25U, debug build at `6d9be7f`, bench Hub plus the real kernel)

The bench was the workstation Hub on 25498 with the mock provider, reached through
`adb reverse`. The real kernel was used only for V0, V-P2-A2 (no prompt sent) and V-B8.3 (two
DeepSeek prompts). The owner's `dev.cline.termux` was never installed over, launched or
cleared; its `lastUpdateTime` stayed 2026-10-07 04:53:42 throughout.

| Id | Item | Result | Evidence (short) |
| --- | --- | --- | --- |
| V0 | Setup | pass | **Backups:** `global-settings.json` and `user_input_history.jsonl` copied to `~/tmp/batch1-backup`. **Install:** the debug app installed beside the owner's. **Onboarding** on the real kernel without config writes: the app started the Hub (`cline hub start` exit 0 in 4 s) and Home showed the owner's provider. **Note:** `pm list packages` needs `--user 0` on this phone. |
| V-P8-ID | Build identity | pass | **Badging:** the debug APK is `dev.cline.termux.debug` "Cline Debug"; the release is `dev.cline.termux` "Cline". **Refusal:** `install.sh` refuses the release APK before any adb call; it was checked with a stub adb, because the classifier blocked the real-adb run. **Launcher:** search shows "Cline" and "Cline Debug" side by side, plus Dual Messenger copies, because `adb install` also installs into profile 95. |
| V-P8-G4 | Shortcuts | pass (partial) | **Shortcuts:** Ctrl+, opens Settings; Ctrl+B opens and closes the drawer; Ctrl+N opens a new session. **Not covered:** the expanded-window list-pane toggle, because the S25U tops out at w832dp (expanded starts at 840dp). |
| V-P8-G3 | Worktree | pass | **Creation:** `git worktree add` made `~/.cline/worktrees/a00e1/app-test-ws [cline/a00e1]`, and the session's cwd is that path. **Delete:** the dialog shows the desktop's warning text verbatim; after delete the worktree, its folder and the branch are gone. **Persistence:** the chip state and the workspace survived a cold start. **Side issue:** trial-log row 8. |
| V-P2-A2 | Thinking chip | pass | **Fallback catalog:** the chip is enabled for Astra, DeepSeek v4-pro and `deepseek-chat`, and disabled for the mock. **Live catalog:** enabled for Astra and the DeepSeek rows tagged Reasoning; disabled for the mock and for `deepseek-chat` and `deepseek-v4-flash`, because the live catalog decides per model row. **Settings files:** every file in the real settings directory was byte-identical before and after. |
| V-P3-A4 | Stall notice | pass | **Stall:** with `mock-stall` the banner appeared at 32 s; Keep waiting hid it; the answer came at 82 s. **Controls:** no banner during `slow` or `mock-long-command`, but both runs end in under 30 s. **Layout:** with the keyboard open in landscape the banner sits under the status bar (row 13). |
| V-P3-STOP | 30 s Stop | pass | **Method:** `kill -STOP` on the mock cannot make Stop hang, because the Hub cancels its own request. A sleeping PreToolUse hook in the bench home made Stop hang instead. **Timing:** the snackbar "The agent did not stop. Try again." with Restart kernel appeared at 30.05 s and 30.04 s (logcat). **Side notes:** row 11. |
| V-G5 | Drain after a Hub replacement | pass | **Replace:** after `hub-replace.sh replace` the chat was clean at 10 s and 30 s, and Kernel status showed Connected to the new pid. **Drain:** `drain` showed the Draining notices and `undrain` cleared them within 2 s. **Combined:** drain, then replace, also ended clean. **Observation:** the kernel marks the sessions `failed` after the forced exit. |
| V-P3-B5 | Notification re-ask | pass | **Re-ask:** Back on the dialog left the flags unchanged, and the next tap showed the dialog again. **Denials:** deny, tap (dialog), deny again, tap: the app's notification settings opened. **Grant:** after `pm grant` the item shows On. The flags were restored. **Polish:** row 14. |
| V-P4-G1 | Compaction on create | pass | `ClineSessionCfg` logged `create`, `resume` and the fork's `create`, each with `compaction={"enabled":true}`. |
| V-P4-A11.2 | Compaction setting | pass | **Truncation:** the file got `basic`, `true`, and the new session logged `{"enabled":true,"strategy":"basic"}`. **Off:** `false` with the strategy kept, logged `{"enabled":false}`. **LLM:** `agentic`, `true`, logged `{"enabled":true,"strategy":"agentic"}`. **Other keys** never changed. The file was restored byte-identical. |
| V-P4-A11.8 | Move to workspace | pass | **Identity:** the session id stayed the same, the messages were identical, and the root and cwd changed. **Checkpoints:** a session that had them lost them, as decided. **While running:** the item is greyed out with "Stop the run first" and re-enables live when the run ends. A Hub restart after a move was not tried. |
| V-P4-STEER | Steer | pass | **Steer:** "Sent to the running agent", nothing queued, and the same run's next model request carried `steer-check` (the Hub log shows the run's send_input spanning it). **Idle:** a long-press only shows the tooltip. **Haptic:** requested twice (row 12) and muted by the phone's own setting. |
| V-P1-A1 | Auto-approve control | **fail** (step 7) | **Steps 1 to 6, 8 and 9 passed:** the states and the two-tap menu; the file follows each change with other keys intact; the Ask session asks for approval; "Approve and auto-approve from now on" works; the pending ring and both end motions were recorded on video; the outside-change snackbar; the Settings switch. **Step 7 failed:** after a mid-run switch to Ask no restart happened, so the next `mock-long-output` ran its tool without asking while the UI said Ask. **Cause:** stale run-end records (§2.2). Fixed in `941e886`; recheck in §3.2. The file was restored identical. |
| V-P1-AP | A′ versus A | pass | **Screenshot pairs** at 384 dp portrait, 360 dp and landscape; the title keeps at least 122 dp. **Home:** it looks the same in both placements, by design (`HomeTopBar` always uses the title placement), whereas the design note sketches A's Home with the shield at the right. See `V-P1-AP-14-header-compare-portrait.png` and `-15-…-landscape.png`. |
| V-P5-G2 | Mistake limit | pass | **Sheet:** the desktop's question text. **Continue:** guidance steered, then `{action:"continue"}`, and the mock answered "Recovered."; 56 later requests were auto-answered with no second sheet. **Stop:** `{action:"stop", reason:"stopped after mistake_limit_reached prompt"}`. **Reconnect:** the Hub cancelled the request and no duplicate sheet came back. **Observation:** row 10. |
| V-P5-A5 | Hooks | **fail** (block text) | **One hook run per call:** the Hub ran the bench hook exactly once per tool call, so the app contributes no hook (F1 holds). **Context:** reached the model (`<hook_context>`). **Block:** stopped the whole run with no tool event, and the text "blocked by bench hook" reached no client. That is core 0.0.91 behaviour, so the expectation was corrected in the brief. **Display:** the blocked call stays "Running command" forever (row 9). **Bench caveat:** hooks cannot run in app-created bench sessions, because the cwd is a phone path. |
| V-P5-ACT | `switch_to_act_mode` | pass | **Plan session:** the tool succeeds and the run ends; the app detaches and recreates the session in act mode, sends the continuation, and the mock answers "Plan executed."; the composer flips to Act; the synthetic prompt is hidden. **Act turn in a plan runtime:** "Already in act mode.". **Act runtime:** the tool does not exist (CLI-consistent). |
| V-P6-HIST | Prompt history | pass | **Order:** newest first, with the chip and ×. **Guards:** no fling with text in the field, and edge swipes go to system back. **List:** the Recent prompts sheet, where picking keeps the typed draft. **Recording:** a bench send was appended once (20 lines). The file was restored identical. |
| V-P6-B9 | Full-screen editor | pass | **Icon:** shown only after more than 3 lines. **Dialog:** close, attach and Send. **Text:** typed lines carry over both ways. **Send:** from the editor sends all five lines and closes it. |
| V-P7-A11.9 | Message details | **fail** (notices) | **Verbose stats** match the kernel's totals exactly (225.8k in, 5.6k out, 31.1 s; and 2.5k, 120, 0.5 s), survive a cold start, and Brief hides them. **Recovery notices** do not appear, because core 0.0.91 drops recoverable agent errors (`projectAgentErrorEvent`); waiting on an upstream change (D20). **Not exercised:** api_error rows and cost (the bench mock has no price). |
| V-P7-A13 | Fork before this turn | pass | **Action row:** turn 1 offers no fork; turn 2 does. Label "Fork before this turn". A 3 s hold shows the tooltip and does not fork. **Fork:** a tap opens the fork with only turn 1, and metadata `beforeRunCount: 2` with checkpoints filtered. **Glyphs:** all four candidates in `V-P7-A13-side-by-side.png`. The custom glyph reads lighter than its neighbours. |
| V-B8.3 | Release jank | pass | §5 |
| V-F07 | Fixture 07 | pass | Recorded with 103 lines and sanitised (no token, no home paths); the bench workspace was left clean. It was committed in `449ccc7` with a replay test, which found the `createdAt` defect (§2.2). |
| VZ | Cleanup | pass | **Sessions:** all 12 bench `[test]` sessions deleted; the real Hub had no `[test]` sessions left. **Bench:** hooks off, unreverse, stop; no reverses, forwards or bench processes left. **Files:** both owner files byte-identical to the backups (the history file had to be restored). **Phone:** density, rotation and notification permission as found; the worktrees were pruned. |

### 3.2 Recheck of V-P1-A1 after `941e886`

**Result: pass.** Run in close-out on the S25U with the debug build of the final code (`449ccc7`;
APK sha256 prefix `bd247e05`) against the bench Hub, on a fresh session `1791423148104_pq51u`.
This is the round-1 failure path: a session created under Ask, resumed under Auto, then switched
to Ask during a run. The global mode was changed through the file, with the terminal's
mechanism (a `bun-ffi` one-key merge, brief step 8), rather than through the menu.

| Time (UTC) | Step | Hub log / screen |
| --- | --- | --- |
| 01:32:28 | Global Ask; "[test] a1 recheck seed" sent from Home | `session.create` (runtime under Ask), a plain answer |
| 01:33:06 | Global set to Auto outside the app; "[test] slow please" sent | before the send: `session.get`, `session.detach`, `session.create` (restart into Auto), then `send_input` |
| 01:33:11 | Global set to Ask outside the app during the run | snackbar "Auto-approve all turned off outside the app" |
| 01:33:19.337 | Run ends | `send_input` ends; 0.1 s later `session.get`, `session.detach`, `session.create`: the run-end restart into Ask that round 1 never saw |
| 01:34:15 | "[test] mock-long-output please" | sheet "Tool approval required", `run_commands` `$ seq 1 80`, Reject / Approve ▾ (rejected) |

Evidence: `V-P1-A1-recheck-01.png` (Home, Ask shield), `-02-midrun.png` (Auto disc while the
slow run streams; taken just before the external change landed), `-03-approval.png`.

**Restored afterwards:** `global-settings.json` and `user_input_history.jsonl` were copied back
from `~/tmp/batch1-backup` and checked with `cmp` (identical). The app appends sent prompts to
the shared history, as designed. The bench session was deleted through the app.

**Not rechecked on the device:** the opposite direction (created under Auto, restarted into Ask,
then no restart before every send). The JVM tests in `941e886` cover it.

## 4. Gate on the final code

Run on code commit `449ccc7` on 2026-10-07 by the close-out. The close-out's later commit only
changes docs and `scripts/gen-docs.py`; it touches nothing that Gradle builds.

| Gate | Command | Result |
| --- | --- | --- |
| Debug | `bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug` | BUILD SUCCESSFUL in 7 m 42 s. **2938 tests**, 0 failures, 0 errors, 0 skipped. Lint **0 errors**, 174 warnings, 2 hints. |
| Release build | `bash scripts/build.sh assembleRelease lintRelease` | BUILD SUCCESSFUL in 5 m 5 s. Lint **0 errors**, 172 warnings, 2 hints. The APK is `dev.cline.termux` 0.1.0 (aapt2). |
| Release tests | `bash scripts/build.sh testReleaseUnitTest -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false` | BUILD SUCCESSFUL in 5 m 15 s. **2904 tests**, 0 failures, 0 errors, 0 skipped. |

Against the integration commit `6d9be7f` (2928 debug and 2894 release tests), there are 10 new
tests in each set:
- 5 from the A1 fix in `941e886`;
- 5 from `449ccc7`: the fixture-07 replay tests in `CheckpointRestoreFixtureTest`. The
  `CheckpointIndexTest` and `HubPayloadsTest` cases were extended in place, not added.

The lint warning counts are unchanged from the integration (main had 173).

## 5. Jank (B8.3)

Release build (`dev.cline.termux.bench`, R8, debug-key signed), real kernel, DeepSeek
`deepseek-v4-pro`, thinking Low. The prompt: "[test] Explain in about 500 words how a CPU cache
works. Plain prose, no lists."

| Run | Window | Frames | Janky (deadline) | Legacy janky | p50 | p90 | p95 | p99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2 (valid) | 14.2 s, dumped 2.3 s after the reply ended | 1422 | **11 (0.77 %)** | 92 (6.47 %) | 5 ms | 9 ms | 11 ms | 15 ms |
| 1 (as scripted) | 344 s, idle tail included | 2022 | 602 (29.77 %) | 143 (7.07 %) | 5 ms | 16 ms | 17 ms | 18 ms |
| Idle, no model | 60 s, focused composer | 121 | 119 (98.35 %) | 1.65 % | 16 ms | — | — | — |

The debug baseline (docs/09) was 30–34 % janky with p90 34–36 ms. The release build streams
well inside the 5 % budget.

Run 1's figure is mostly idle tail, not a regression. After the reply, the blinking cursor in
the focused composer draws about 2 frames a second, and 98 % of those frames count as janky
under the 120 Hz deadline metric, although nothing visible happens. Future jank checks should
dump at the run's end and read the legacy line as well. The dumps are
`B8.3-gfxinfo-run2.txt`, `B8.3-gfxinfo.txt` and `B8.3-gfxinfo-idle60s.txt` in the evidence
folder.

## 6. Deviations from the decisions

Each one follows the decision's intent but differs from its wording or from the desktop. The
first four need your confirmation.

1. **A5, hooks (F1).** The decision was to build hooks. A bench probe and V-P5-A5 show the Hub
   already runs the user's hook files for the app's sessions. So the app registers no hook
   contribution and has no RUN_COMMAND hook runner. A hook proxy would run every hook twice,
   and every tool call would wait for the phone (the A3 hazard). A guard test keeps it that way.
2. **A11.8, Move to workspace.** The decision says "the resume path the app already has".
   P4 moves by delete-then-create under the same id instead, because a resume reuses the stored
   manifest and a Hub restart would put the session back in its old folder. Consequences:
   - The start time becomes the time of the move.
   - The old folder's checkpoints go, as decided.
   - The compaction state is carried over.
   - Until the create is confirmed, a copy of the session's messages, metadata and compaction
     state is kept in app-private storage (`filesDir/move-recovery/`, excluded from backup).
   - Sessions that have subagents or teams, and subagent or teammate sessions, are refused,
     because `session.delete` would remove the child sessions.

   Your questions: is delete-then-create fine? Is the recovery copy fine? Is refusing agent
   sessions fine, or should moving them be designed later?
3. **G2, the mistake limit (F14).** The guidance goes back in the decision, the CLI's shape. The
   app does not copy the desktop's steer plus hold hooks (the A3 hazard). As a result core keeps
   calling the model while the question is open: about 60 calls in 11 s on the bench, billed on
   a real model, for up to the 5-minute timeout. The app always asks, like the desktop, even
   under Auto (the CLI stops at once there). Trial-log row 10 lists the options.
4. **A13, the cut.** The message row forks the history before the answer's turn, as decided.
   The desktop's message row forks the whole session (`chat-messages.tsx:517-543` →
   `page.tsx:2040`). The cut is visible to other clients through `fork.beforeRunCount`. Your
   question: keep the cut, or fork the whole session with its own icon and label?
5. **A4.**
   - The first stall notice comes 2 s after the run start rather than "no event since the run
     started". The opening burst of events would otherwise push it to about 60 s.
   - The chat's "Restart kernel" opens the Kernel status sheet with a one-shot restart request,
     so the restart goes through the sheet's pre-check and confirm.
6. **G5.** The draining flag is replaced in one step by the new connection's `hub.status` rather
   than reset first, so sends held during a drain are not released early. Replayed drain events
   at or below the status's `lastSequence` are ignored.
7. **A11.6.**
   - Only a direct send the Hub accepted is recorded, as the terminal does; queued and steered
     sends are not.
   - The append script leaves an unreadable file alone, where the terminal would overwrite it.
8. **A11 row 4.** The tool is contributed to plan sessions only, and the session is rebuilt in
   act mode before the continuation. The brief had it on every session, but runtime tools are
   fixed at create.
9. **A2.** The fallback list enables every model of a reasoning-capable provider. The live
   catalog decides per model, the desktop's rule. So `deepseek-chat` is on in fallback mode and
   off live.
10. **P1, the restart compaction state.** The app sends the state exactly as
    `session.compaction.get` returns it. The terminal re-projects it on the client first. The
    two are equivalent while the message prefix matches.
11. **G3.**
    - On a worktree failure the draft comes back with "Message not sent. Couldn't create a
      worktree: <git line>".
    - Only listed root sessions are checked before a worktree is removed.
    - The create script removes the empty `<id>` folder when `git worktree add` fails.
12. **Integration note 1 not done.** A compaction-setting change does not restart an open idle
    session; it applies at that session's next create.

## 7. Known limitations

- **A1 runtime policy is in memory.** After process death, or once an idle unopened session is
  evicted, the app restarts a session once at its next boundary. That is the safe direction.
- **A1 notification gap.** An approval the app answers itself is marked seen. If that automatic
  answer then fails, the request shows as a card in the chat but posts no notification.
- **Plan/Act toggle.** The app's per-turn toggle still only wraps the prompt. An Act turn in a
  plan-created session gets no edit tools, and a Plan turn in an act-created session keeps them.
  `SessionStore.restart(id, mode)` is ready for a restart-before-send.
- **A11.8 recovery.**
  - It runs on every Hub connection and at the first process start with the UI. A service-only
    start runs none.
  - If the old folder is gone, the copy is retried on every connection and nothing tells the
    user it is waiting.
- **A11.6.** The read is subject to Termux's 100 KB result cap, and the editor and the field
  share one `TextFieldState`. The second part's keyboard behaviour on the device was not
  examined in detail.
- **A11.9.**
  - Verbose shows no stats for turns whose metrics compaction stripped, or that ended in an
    error row.
  - The duration runs to the last model call, not to the end of any tool time after it.
- **A4.**
  - A run already going when the session is opened raises its first stall at about 60 s.
  - "A later silent heartbeat in the same episode stays hidden after Keep waiting" was not seen
    on the device.
- **G4.** The expanded-window list-pane toggle is untested on a device; the Tab S7+ run (B8.2)
  covers it.
- **Debug port override.** While the override is set and the workstation Hub is down, the debug
  app does not fall back to the phone's Hub until `dev-hub.sh unreverse`.
- **Bench limits.**
  - App-created bench sessions have phone paths, so tools and hooks cannot run there.
  - The bench needs a seeded workstation session for hook and Stop-hang checks (documented in
    `scripts/bench/hooks.sh` and the briefs).
  - `record.ts` scenario 07 leaves the kernel's checkpoint ref in the bench workspace until the
    session is deleted.
- **Leftovers.**
  - `design/screens/onboarding-kernel.md:452` still shows the old `dev.cline.termux/.MainActivity`.
  - `pkg/p5` still points at `b60e84a`; the merged commit `f37ceee` is on `pkg/p5-fix2`.
  - The package worktrees under `.claude/worktrees/` are still checked out.

## 8. What to try first

1. **Auto-approve.** In a chat, switch the shield to Ask while a run is going, let the run end,
   then ask for a command. It must ask for approval (the V-P1-A1 fix). Then look at A′ against A
   in `V-P1-AP-14-header-compare-portrait.png` and pick one.
2. **A13.** Pick the fork glyph from `V-P7-A13-side-by-side.png`, and answer the cut question
   (§6 item 4).
3. **Thinking chip.** On Astra and DeepSeek it is now enabled with the settings helper off.
4. **Plan mode.** Ask for a plan, then let Cline switch itself to Act: it continues without a
   prompt from you.
5. **Long-press Send** while a reply streams, to steer it.
6. **Prompt history.** On an empty field, fling from left to right (since A18; batch 1 had it the
   other way round); the history is shared with the terminal.
7. **Move to workspace…** from a finished session's menu, then answer the A11.8 questions in §6.
8. **Background reliability.** Revoke the notification permission once and walk the
   Notifications step.
9. **Long messages.** Type more than three lines and open the full-screen editor.

## 9. Phone state at close-out

- **Owner's app:** `dev.cline.termux` was never installed over, launched, stopped or cleared;
  `lastUpdateTime` is 2026-10-07 04:53:42, as before the round.
- **Debug build:** `dev.cline.termux.debug` is installed from the final code (`449ccc7`; the
  later commits only change docs). It points at the real kernel: the Hub override was cleared
  with `dev-hub.sh unreverse`, and the drawer shows "Cline Hub @25463". `adb reverse --list`
  and `forward --list` are empty. The bench mock and Hub are stopped.
- **`[test]` sessions on the real Hub: none.**
  - The Hub's `sessions.db` (read-only query) and `cline history` list no `[test]` session.
  - The debug app's drawer still showed the two V-B8.3 sessions from its stale list cache
    (trial-log row 8). Deleting one through the drawer made the list refresh and both rows
    vanish; the cache now holds 10 rows, none `[test]`. No other session was touched.
- **Owner's Termux files:** `global-settings.json` and `user_input_history.jsonl` are
  byte-identical to `~/tmp/batch1-backup` (`cmp`). The backup folder is left in place for the
  owner to remove.
- **As VZ left them:** density and rotation; POST_NOTIFICATIONS granted to the debug app;
  `~/tmp/app-test-ws` is a git repo with only its main worktree; the `~/.cline/worktrees`
  folder is empty.
- **Termux shared storage:** its permission is still revoked, as the test window requires. D1a
  says it is restored at close-out; this agent was told not to grant it, so that is left to the
  owner or the lead.
- **Release APK:** `app/build/outputs/apk/release/app-release.apk` is the plain release
  (`dev.cline.termux`). It must not be installed on the S25U by test agents.
