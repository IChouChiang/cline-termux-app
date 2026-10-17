# 11 — Batch 2 report: the polish pass (2026-10-08)

For the owner. Batch 2 builds the polish pass you decided on 2026-10-08 ([decided.md](decided.md)
A18, together with A15, A16, A17, A19, A20, A21, A22, A23, A24 and the cancellation of A11.8).
It was built as five packages, Q1 to Q5, each branched from `batch/decided-1` at `8048eb8`,
merged into the local branch `batch/polish-2`, verified on the S25U and merged into `main`.
Nothing has been pushed. The kernel was not changed: it is `v3.0.69-termux.1` (core 0.0.91).

Contract notes are in [02-kernel-contract.md](02-kernel-contract.md) under "Batch 2 additions".
The app rules are in [07-app-architecture.md](07-app-architecture.md): §14 holds the one-place
rule, the dense-row exception and the folded rows, and §15 holds D24, D27, D28 and D30 to D36.
Evidence (screenshots, UI dumps, log extracts, the Robolectric renders) is in
`screenshots/batch2-verify/`, 464 files plus the close-out's `v6-TEAM-*`. It is local only and
not in git. Each `*-evidence.txt` there holds the measurements an item below cites.

## 1. Summary

- **Built:** all five packages, none dropped. The merges conflicted twice, and both conflicts
  were mechanical. The device round found one defect, which was fixed on the branch (`da81883`,
  `/team` with Teams switched off).
- **Verified on the S25U:** 18 items. 17 passed in round 1. V-TEAM failed. It was fixed in
  `da81883`, and the close-out's device recheck passed (§3.2). Starting a real agent team was
  not testable, because it needs Teams switched on in your real configuration (§3.3).
- **Trial row 15:** no app defect. The self-check prose was DeepSeek's own answer text (§5).
- **`/team`:** it is now refused while Teams is off, as the desktop refuses it (§6).
- **Gate on the final code:** green for debug and release (§4).
- **Waiting for you:** the look of the 40 dp rows (A24 lets you go to 44 dp with one value),
  and five small deviations (§7). Three new small defects are in [todo.md](todo.md) rows 20 to
  22 (§8).

## 2. What was built

### 2.1 Packages

| Package | Commit | Decisions and rows | What changed |
| --- | --- | --- | --- |
| Q1 | `3b59a9a` | A18 (history direction, chip), A11.6 | On the empty field, a left-to-right fling recalls the previous (older) prompt and right-to-left the next (newer) one. The direction is decided only in `HistoryFlingRules.classify`. The browsing chip is a 24 dp tonal pill (`surfaceContainerHighest`) with no outline and no history glyph, holding only the label and a ×. The × keeps a 48 dp target, so the chip row went from 60 dp to 48 dp. |
| Q2 | `e5db267` | A15, A16, A19, A11.8 | The auto-approve control (still at A′, before the title) draws plain glyphs. Ask is the ticked shield, Auto is the bolt, and Pending is the ticked shield at 60 % with a small `tertiary` dot. The disc, the ring and the A/A′ debug switch are gone. The only fork is the whole session, from the chat three-dot and the drawer row menu. The message-row "Fork before this turn" and `/fork` were removed, with their glyphs, overrides and strings. Move to workspace was removed entirely: code, menus, picker modes, recovery copy, the subagent/team refusal and `SessionStore.recreate`. Delete session stays. |
| Q3 | `198136a` | Rows 8, 16; A17; A21; A22 | Every `session.list` reply decides which drawer rows exist, so deleted sessions no longer come back. A create-time title that the kernel overwrites at the first prompt is put back once (`CreateTitleKeeper`). After the app's "stop" to the mistake-limit question, the conversation gets a notice row ("Stopped after N mistakes in a row" with "Latest: …", or "Stopped: the question was not answered within 5 minutes"). The notices are stored in the app. In a finished turn, a tool call with no result reads "Interrupted: <specific>". A session started without a folder runs in the Termux home, and Home's chip and drawer group read "Home". |
| Q4 | `2493916` | Rows 11, 12, 13, 14 | "The agent did not stop" now goes away once that run is over, and a Stop whose request fails says so from the composer straight away, with Restart kernel. A steer long-press gives one haptic, not two. In landscape with the keyboard open, the stall notice stays below the status bar as one 56 dp row. Reliability items that become done collapse, and a done Notifications item shows no Allow. A cross-test hang in the Robolectric suite was fixed at its root (`RouteNavigationTest` drains the main looper). |
| Q5 | `7af249e` | A23, A24, row 18 | No tool row opens by itself, and a row the user opened stays open. Disclosure rows (tools, thinking, "Worked for…") are 40 dp with 18 dp icons, a 14 dp spinner, `bodySmall` labels and a 4 dp block gap, all taken from one token set, `DisclosureRowTokens`. The `$` lines, the empty-output fallback and the tool details print the full command from the call's input, never the kernel's 200-character echo with its truncation note. |

### 2.2 Integration and fixes

| Commit | What |
| --- | --- |
| `c307d13` | Q2 merged, with no conflicts. |
| `1005e68` | Q3 merged. The one conflict was in the generated `docs/strings-audit.md`, which was regenerated with `tools/strings-audit/audit.py --markdown`. |
| `4cb3f3f` | Q1 merged, with no conflicts. |
| `68296f8` | Q4 merged, with no conflicts. |
| `6465d84` | Q5 merged. It had four conflicts, all of which Q5 had predicted. They were in `ToolCallRow.kt`, `TranscriptStatusRows.kt`, `ToolPresentationTest.kt` and the home-chat spec §3.4.4 table. Each resolution kept both sides: Q5's tokens with Q3's Interrupted glyph, and Q3's two-line notice with Q5's icon size. |
| `da81883` | The V-TEAM fix. Whether Teams and Skills are switched off is now read from `global-settings.json` `disabledTools` (`DisabledToolsSource`, `cat` at the send that needs it), as the desktop reads it. Before, the app looked in `settings.list`, which in core 0.0.91 lists plugin tools only. The `/team` checks run in the desktop's order: usage, then disabled, then mode. The tests now use the recorded `settings.list` reply. |
| close-out | The docs (this report, docs/02, 07, 09, decided, todo, the auto-approve research note and CLAUDE.md), then `git merge --no-ff batch/polish-2` into `main`. |

The commit `2493916` ends with a "Claude Opus 5.5" trailer instead of the repository's Fable
trailer. Its merge commit carries the right one, and package history was not rewritten.

## 3. Verification

### 3.1 Round 1 (S25U, debug build of `6465d84`, bench Hub plus the real kernel)

The debug APK of `6465d84` (sha256 `904e92c6…`, cert `ea8d4e28…ceb2`) was installed as
`dev.cline.termux.debug` for user 0. Five verifier sessions used the phone one after another.
The bench Hub (mock provider, :25498) served everything that needs no real model. The real
kernel (:25463, DeepSeek) served V-ECHO, V-TEAM, V-HOME and V-R15, with every session titled
`[test] …` in `~/tmp/app-test-ws` and deleted afterwards.

| Item | Result | Evidence (in `screenshots/batch2-verify/`) |
| --- | --- | --- |
| V-HIST | pass | The first left-to-right fling showed the newest prompt with "Recent prompt 1 of 20". The next showed 2 of 20. Right to left went back, and a second right-to-left fling left browsing. The chip measured 24 dp, with a pixel scan finding no border row and no icon. A slow drag on the empty field made no step, and the cursor handle still moved in typed text. Attach sheet → Recent prompts lists newest first, and picking one fills the field. Your history file was unchanged (hash compared). `V-HIST-01…13`. |
| V-ICONS | pass | Auto shows a plain bolt in the chat and on Home. In its 48 dp target the only colours are the glyph and the bar, so there is no disc or ring. Picking Ask wrote `toolAutoApprove: false` and drew the filled ticked shield. Pending measured `#7d7f88`, exactly 0.6 × the glyph colour over the bar, with a ~6 dp `tertiary` dot. At the run's end the boundary restart ran (logcat), the dot went and the glyph returned to full emphasis. The approval sheet's ▾ item and Settings → Kernel show the bolt. Your settings file was restored byte for byte. `V-ICONS-01…13`. |
| V-FORK | pass | `/` lists only `/team` and, with a scratch workflow present, that workflow. `/fo` shows "No matching commands". There is no fork icon on any message row. Fork from the three-dot and from the drawer row each made a copy with all 8 messages, and `metadata.fork` held `{checkpoints, forkedAt, forkedFromSessionId, source}` with no `beforeRunCount`. `V-FORK-01…11`. |
| V-MOVE | pass | There is no "Move to workspace…" in the three-dot, the drawer row menu, the title and Details sheets, any Settings group or Home's picker. An `aapt2` string dump of the APK has no move strings. Delete from both menus showed the confirmation and removed the session from the bench `sessions.db`. `V-MOVE-01…10`. |
| V-R8 | pass | A session deleted in the app was gone. A session deleted by another Hub client while the app was open stayed until the cold start, because the Hub sends no delete event, and was gone at about 1 s after it. The same happened with one deleted while the app was stopped. `list-cache.json` was rewritten to match `session.list`. `v2-R8-*`. |
| V-R16 | pass | A rename and a fork were never overwritten by the first prompt. A title given at `session.create` by another client and then sent its first prompt from the app was put back once, by one `session.update` 1.04 s after the run, and the header never showed the prompt title. It held after a reopen and a cold start. `v2-R16-*`, `v2-R16-hub-log.txt`. |
| V-G2 | pass | The sheet showed the desktop's text, "Latest: …" and the two options. After "Stop this run" the header read Cancelled and the transcript gained "Stopped after 6 mistakes in a row" with the "Latest: …" detail. It was still there after a reopen and after a cold start (DataStore `sessions.stopNotices`), and deleting the session dropped it. The sheet was open 143 s, during which the mock got 566 model calls, as A17 option 2 accepts. `v2-G2-*`. |
| V-A21 | pass | A hook-blocked `echo hook-test` reads "⚠ Interrupted: echo hook-test" in the finished turn, live, after a reopen and after a cold start, and never Running. A live `sleep 8` call still showed Running with a spinner. `v2-A21-*`. |
| V-R11 | pass | With a 45 s sleeping hook, Stop's `run.abort` returned ok but the run went on. "The agent did not stop. Try again." with Restart kernel appeared at 30 s and went in the frame after the run ended. With the bench Hub frozen (`SIGSTOP`), the composer showed the failure after the 35 s client timeout, and it cleared once the Hub was resumed and the run ended. A quick Stop showed no snackbar. Restart kernel was not tapped, because it acts on the real Hub. `v3-R11-*`. |
| V-R13 | pass | In landscape with the keyboard shown, the stall notice is one 56 dp row 33 px below the status bar, with all its elements inside and the composer's Stop below it. In batch 1 it sat under the status bar. `v3-R13-*`. |
| V-R14 | pass | Notifications already allowed: "Done, On", collapsed, no Allow. After a revoke and a grant (`pm`, user 0), the item went from "Not set" with Allow to "Done, On", collapsed, with no Allow. The permission was restored to granted. `v3-R14-*`. |
| V-DENSITY | pass | At 360 dp (density 640) the pressed highlight of a Worked-for row and of a Thought row is exactly 40.0 dp, and two Read rows' icon centres are 40.0 dp apart. Labels are `bodySmall` (glyph ratio 12/14 against batch 1), icons sit in an 18 dp slot, the spinner is 14 dp and the rail is at 9 dp with the body at 21 dp. The block gap is 4 dp, and the gap before the next user turn is 20 dp. Nothing is clipped. Taps 2 dp inside a row's edge still toggle it. The accessibility bounds report 48 dp (Compose's minimum touch bounds). No literal sizes appear in the row composables. `v3-*`, `v3-DENSITY-evidence.txt`. |
| V-FOLD | pass | A run with an edit diff, an image read, the submit text and a 20 s live command kept every row folded with its status in the header, and nothing opened by itself. Taps toggled rows during the run, including the live command, which showed its output when opened. After the end, after a reopen and after a cold start everything was folded. The thinking row and Worked-for behave as before. `v4-*` (run 4). |
| V-ECHO | pass | On the real kernel with DeepSeek, `$ echo "…"` showed the whole 308-character command and `$ printf … \| wc -m` the whole 323 characters, live, in the details tabs and from history after a cold start. The stored results do carry "[command truncated: …]", and a UI dump of every screen found that text nowhere. The session and its worktree were deleted in the app. `v4-63…86`. |
| V-R12 | pass | `dumpsys vibrator_manager` recorded exactly one `LONG_PRESS` per steer long-press, in two tries, and none for idle long-presses. The phone has system haptics off, but the requests are still logged. `v4-R12-evidence.txt`. |
| V-TEAM | **fail**, fixed | With Teams off in your real settings, `/team list the files…` was not refused. A normal session ran, and DeepSeek said it had no team tool. Cause: the app looked for a built-in `teams` entry in `settings.list`, which core 0.0.91 never returns. A bare `/team` showed the usage line, which passed. The session was deleted. `v4-101…124`. Fixed in `da81883` and rechecked in §3.2. |
| V-HOME | pass | With "Just chat" the chip reads "Home". DeepSeek's `pwd` printed `/data/data/com.termux/files/home`, the record has `cwd = workspace_root =` the home, and there was no ENOENT anywhere. The drawer's Project view groups the session under "Home", and the old "Hi" session stays under "Chat". The home was unchanged (mtime and entries). `v5-HOME-*`. |
| V-R15 | pass | See §5. `v5-R15-*`. |

### 3.2 Close-out recheck of V-TEAM after `da81883`

The debug build of `da81883` (sha256 `bc27c97f…`) was installed for user 0 and pointed at the
bench Hub. The app's Termux commands still read the phone's real `global-settings.json`, whose
`disabledTools` holds `teams` and every `team_*` tool.

- `/team [test] list the files and say done` → the snackbar "Agent teams are disabled. Enable
  the Teams tool in Customize → Tools." with "Open Tools", and the draft stays. The bench Hub
  log shows only two `settings.list` requests and no `session.create` or `send_input`, and the
  mock got no request (`v6-TEAM-03-sent-1s.png`). **Pass.**
- `/team` with nothing after it → "Usage: /team <task description>. Starts a team of agents for
  the given task." The usage check still comes first, as on the desktop
  (`v6-TEAM-06-bare-sent.png`). **Pass.**
- The popup still lists `/team` while Teams is off. The desktop lists it unconditionally too
  (`BUILTIN_SLASH_COMMANDS`, `chat-input-bar.tsx`).

### 3.3 Not testable here

- **A real agent team** (the Agents chip and roster) needs Teams switched on in your real
  `~/.cline`, which agents must not change. To try it, switch Teams on in Customize → Tools and
  send `/team <a small task>`.
- **Restart kernel** on the Stop-failure snackbar was shown but not tapped. It runs
  `cline hub upgrade` against the real Hub.
- **Light theme:** the history chip's tonal pill was checked in the dark theme only. In the
  light theme its fill is only a small step from the composer (tone 90 on 92), so it may look
  faint. If so, `secondaryContainer` is a one-line change.
- **44 dp fallback:** it was neither rendered nor tested. It is the single value
  `DisclosureRowTokens.Height`.

## 4. Gate on the final code

The gate was run once more on the close-out head of `batch/polish-2` (`c76ff46`). Its code is
that of `da81883`; the close-out changed only documents. The unit tests and the lint report
were forced to rerun (`--rerun`). Logs: `/tmp/closeout/logs/final-*.log`.

| Run | Command | Result |
| --- | --- | --- |
| Debug | `bash scripts/build.sh assembleDebug testDebugUnitTest --rerun lintDebug --rerun` | BUILD SUCCESSFUL in 3 m 27 s. 2987 unit tests, 0 failures, 0 errors, 0 skipped (`PromptQueueSheetBackTest` 171 s). Lint: 0 errors, 171 warnings. |
| Release | `bash scripts/build.sh assembleRelease lintRelease --rerun` | BUILD SUCCESSFUL in 5 m 7 s (R8 minify ran). Lint: 0 errors, 169 warnings. |
| Release tests | `bash scripts/build.sh testReleaseUnitTest --rerun -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false` | BUILD SUCCESSFUL in 4 m. 2956 unit tests, 0 failures, 0 errors. |

The debug APK is `app/build/outputs/apk/debug/app-debug.apk` (`dev.cline.termux.debug`,
`0.1.0-debug`, sha256 `bc27c97f…`). The release APK is
`app/build/outputs/apk/release/app-release.apk` (`dev.cline.termux`, `0.1.0`), which is still
signed with the debug keystore (TEMPORARY, D21; cert `ea8d4e28…ceb2`). The integration gate on
`6465d84` had also passed: 2981 debug and 2950 release unit tests, lint 0 errors.

## 5. Trial row 15: verdict

The self-check prose ("Let me check the word count roughly… The response above is complete.")
was **the model's own answer text, not reasoning drawn as answer text**. The app needs no fix.

- The kernel's own `agent_end` hook record of the original B8.3 run (`hooks.jsonl`, 2026-10-08
  00:39:32Z) has the prose word for word in `turn.outputText`. The kernel builds that text only
  from `text` parts (`textFromMessage`, agents `agent-runtime.ts`).
- The missing space falls where two text parts meet. DeepSeek sent reasoning, text, reasoning,
  text, and the kernel, the desktop's live path and the app all join consecutive text parts
  without a separator. The app shows the second reasoning segment in the same Thought row.
- A fresh repro with `deepseek-v4-pro` mapped the thinking block exactly to the Thought row,
  draft sentence included, and the text block exactly to the answer, both live and from history.

The trial log marks row 15 closed.

## 6. `/team`: verdict

- **Before the fix**, with Teams off, the app sent `/team <task>` as the team prompt, and the
  kernel ran an ordinary session in which the model could not form a team. This was not desktop
  parity: the desktop refuses before any session exists (`rewriteDesktopTeamPrompt`, sidecar
  `chat-session.ts`).
- **Now**, the app refuses it the same way and in the same order (usage, disabled, plan mode),
  reading the same file the desktop reads. The Skills check had the same blind spot and now
  reads the same source.
- **Not shown:** a real team starting from the app. That waits on you switching Teams on (§3.3).

## 7. Deviations from the decisions

- **A18, history direction in right-to-left layouts:** the mapping is mirrored there (start to
  end = previous), as batch 1 mirrored it and as Android pagers do. In your left-to-right layout
  it is exactly as decided. Dropping `!= rtl` in `HistoryFlingRules.classify` gives the same
  physical direction everywhere.
- **A18, the chip:** "no outline, smaller" is built as a tonal pill rather than a bare label,
  because with neither an outline nor a fill there is no visible chip.
- **A16, `beforeRunCount`:** both fork entry points send none. The user row's Edit still sends
  it, because it is the checkpoint flow, which works like the desktop's edit-fork. It is not a
  fork entry point.
- **A15, Pending:** built as `onSurfaceVariant` at 60 % plus a `tertiary` Badge dot that fades
  and scales. The A/A′ debug switch was removed rather than kept.
- **A22, naming:** the folder reads "Home" when nothing is chosen, but "~" when you pick the
  Termux home explicitly. Choosing the kernel's chat workspace explicitly shows its folder name,
  "chat". The picker's "Just chat" now means the Home default, and with no folder chosen
  @-mentions and the branch chips stay off. You may want one name and "Just chat" renamed.
- **Row 16:** the title is put back about 1 s after the first run ends, not at once, so the
  run's records settle first. This happens only if the record still holds exactly the
  prompt-derived title, with one retry.
- **A17:** only the two decided stops ("Stop this run" and the 5-minute timeout) get a notice.
  When "Try a different approach" cannot deliver its guidance, the app answers stop and shows no
  notice. The notice is a headline plus a "Latest: …" line, not a single line.
- **Row 18:** the echo is also replaced in the empty-output JSON fallback and in the details'
  tabs, copy and share. It is used only when the input is missing or does not match, and then
  "…" replaces the kernel's note. The details' Command section scrolls within 160 dp.
- **A24 values the decision left open:** spinner 14 dp with a 2 dp stroke; code in labels
  `ClineMono.small`; rail at 9 dp with the body 12 dp past it; 20 dp before a user turn. Notice
  and error rows keep 20 dp glyphs, and the Changes screen's file headers stay 48 dp.
- **Row 11:** the approval sheet's "did not stop" snackbar follows the same end rule as the
  composer's.
- **Row 14:** "collapses once done" applies to every Reliability item, not only Notifications.

## 8. Known limitations

- **Old folder-less sessions** created before A22 keep the kernel's missing chat-workspace path,
  so their commands still fail with ENOENT, and with Move removed the app cannot change their
  folder. Run `mkdir -p ~/.cline/data/workspaces/chat` in Termux, or start a new session.
- **Stop notices** exist only in this app (newest 200). The terminal, the desktop and
  `cline history export` do not show them. A notice is not the last row of its turn: the model
  call in flight when Stop was answered lands after it (todo row 21). The headline's mistake
  count (6) and the detail's identical-call count (5) are two different kernel counters.
- **Row 16's pending rename** is held in memory only. If the app is killed between the first
  send and the end of that run, the kernel's prompt title stays.
- **Row 18's port** of the kernel's command normalisation matches cli-v3.0.69. If the kernel
  changes it, rows fall back to the echo with "…", but they never show a wrong command. A
  command rewritten by a PreToolUse hook shows the echo.
- **Typed `/fork`** goes to the model as plain text. A skill or workflow named `fork` now shows
  in the popup.
- **Plugin commands** are still missing from the popup (`list_plugin_commands` is sidecar-only),
  as in batch 1.
- **`files/move-recovery/`** may remain from an unfinished batch-1 move. The app neither reads
  nor deletes it, and it is no longer excluded from backup.
- **Row 14** compares readings within one process. If the app dies while you are in system
  settings, an item you had opened stays open on return.
- **Row 13** at font scale 1.3 and above: the stall notice keeps its stacked form, which is
  clipped at the top while typing and scrolls inside the docked stack.
- **A23:** "Proceed while running" sits in the row body, so it is now one tap further away.
- New small defects not fixed: todo rows 20 (an "Unread" mark after a cold start), 21 (the
  stop notice's position) and 22 (Cline battery still shows Allow once done).
- `PromptQueueSheetBackTest` takes 4 to 9 minutes of every unit-test run.

## 9. What to try first

1. Open a chat with tool calls. Every row is folded and 40 dp tall. Tap one to open it. If 40 dp
   feels cramped, the 44 dp fallback is one value.
2. Look at the control before the title. The bolt means Auto-approve all, and the ticked shield
   means Ask each time. Switch to Ask during a run to see the dimmed shield with its dot.
3. On an empty field, fling left to right for your previous prompt and right to left for the
   next one. Check the small chip and its ×.
4. Start a session from Home without choosing a folder. The chip says Home, and `pwd` runs in
   your Termux home.
5. Open the three-dot and the drawer row menu. Fork copies the whole session. There is no Move
   and no fork icon on messages.
6. Send `/team something` while Teams is off. It is refused with "Open Tools". Switch Teams on
   if you want to see a real team.
7. Delete a session from the terminal (`cline history delete`) and reopen the app. It is gone
   from the drawer.

## 10. Phone state at close-out

- **Real Hub:** no `[test]` sessions are left. `cline history --json` lists 11 sessions, all
  yours. Every verifier deleted its own sessions and worktrees in the app, and the close-out's
  V-TEAM recheck created none.
- **Debug app:** `dev.cline.termux.debug` is the build of the final code (sha256 `bc27c97f…`,
  installed with `--user 0`). The bench override is cleared and the 25498 reverse is removed.
  The drawer shows "Cline Hub @25463", the real kernel (`v6-99-real-kernel-drawer.png`).
- **Your app** `dev.cline.termux` was not installed, launched, cleared or stopped by this batch.
  The lead installs the release build after its own gate.
- **Settings:** your `global-settings.json` was restored byte for byte after V-ICONS. Teams is
  still off as you had it (`disabledTools`), and `toolAutoApprove` is true. Notifications
  permission, rotation and density were restored by the verifiers that changed them.
- **Bench Hub:** still running on the workstation (`/tmp/cline-dev-hub`). Stop it with
  `bash scripts/dev-hub.sh stop`.
