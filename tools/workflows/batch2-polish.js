export const meta = {
  name: 'batch2-polish',
  description: 'Polish pass after batch 1: every item decided in docs/decided.md on 2026-10-08 (A15, A16, A18, A19, A21, A11.8 removal, G2 notice, trial rows 8, 11-16), verified on the S25U, merged into main (Opus workers; Fable only reads results)',
  phases: [
    { title: 'Implement', detail: 'implementer -> reviewer -> fixer per package, in worktrees' },
    { title: 'Integrate', detail: 'merge the packages, full gate, release build' },
    { title: 'Verify', detail: 'device rounds on the S25U, one agent at a time, a fixer between rounds' },
    { title: 'Closeout', detail: 'docs, report, merge into main, debug install, test-session cleanup' },
  ],
}

// args: { head: '<commit of batch/decided-1 the packages start from>' }
const HEAD = args && args.head
if (!HEAD) throw new Error('pass args.head: the commit of batch/decided-1 to build on')

const APP = '~/workspace/cline-termux-app'
const FORK = '~/workspace/cline-termux-v3-phone'
const OPUS = { model: 'opus' }
const SHOTS = '~/.claude/jobs/02338d87/tmp/verify2'

const RULES = `
STANDING RULES (every agent):
- App repo: ${APP} (Kotlin, Jetpack Compose, Material 3). Kernel/desktop source for reference: ${FORK} (read-only; never modify it). The app never changes the kernel: if a defect sits in the kernel, the app shows what happened honestly or hides the function; never propose kernel patches.
- Read ${APP}/CLAUDE.md first; then docs/decided.md (the decisions you implement: A15, A16, A18, A19, A20, A21, A11.8 cancelled, A17 (G2), the trial-log rows in docs/todo.md section C), docs/10-batch1-report.md (what batch 1 built and how it was verified), docs/07 and docs/02 as needed.
- The work starts from branch batch/decided-1 at commit ${HEAD} (batch 1 plus the decisions; NOT yet merged into main). Every package branches from that commit.
- Build ONLY through "bash scripts/build.sh <gradle tasks>". The gate for a package is: assembleDebug testDebugUnitTest lintDebug, all green, lint 0 errors (HardcodedText is an error). Never weaken lint or tests to pass. Removing a function means removing its code, strings, menu items, tests and docs mentions, not hiding it.
- Commit on your branch with a clear message; end every commit message with the line "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>". Never push. Never touch main until the close-out agent's final step.
- Parity rule P1 (docs/decided.md): desktop functions exact where the kernel or other clients can see it; Material 3 phone presentation; additions only as decided. When the decision text and your reading of the desktop source disagree, follow the decision and say so in your report.
- No secrets in code, logs or reports; never print Hub tokens or credentials.
- Deliver complete work, not WIP. Report honestly: what you verified, what you could not.
- Device: S25U, adb serial $SERIAL, adb at ~/Android/Sdk/platform-tools/adb (call it by that literal path); ssh alias <phone> (Termux login shell is zsh; use "bash -s" for scripts). Use "pm list packages --user 0" (the bare form is broken by Secure Folder) and "adb install --user 0 -r <apk>" (without --user 0 the Dual Messenger profile gets a copy). The debug build is dev.cline.termux.debug ("Cline Debug"); the owner's own app is dev.cline.termux and must never be reinstalled, launched, cleared or uninstalled by agents. Termux's shared-storage permission may be revoked for the test window: /sdcard and ~/storage can be inaccessible from Termux; do not grant it back and do not "fix" the app's storage checks because of it.
- Dev bench: "bash scripts/dev-hub.sh start|stop|status|reverse|helper" (mock provider :25499, Hub :25498 under /tmp/cline-dev-hub; markers hello-from-mock, mock-long-output, mock-api-error[-status], the mistake-limit and act-mode markers and the bench hook script that batch 1 added, see scripts/bench/ and docs/10). run_commands fails with ENOENT in sessions the app creates (Termux cwd) and hooks only run for sessions created in the bench workspace, so create bench sessions there. The phone reaches the bench Hub through "adb reverse tcp:25498 tcp:25498".
- Real kernel on the phone: Hub on ws://127.0.0.1:25463, the owner's real ~/.cline with the owner's providers. Allowed only for checks that need a real model: use the DeepSeek provider (fast, cheap, limited quota: few, short prompts); the owner's Astra (openai-codex) only for Astra-specific checks. Never use Minimax (dead). Every real session title starts with "[test] "; create them in the scratch workspace /data/data/com.termux/files/home/tmp/app-test-ws; prompts are benign and read-only; never ask a model to modify files outside that workspace; delete the [test] sessions when your round ends. Never run the owner's flows that write to the real Termux config (Connect a model, provider keys, sign-in, schedules, MCP edits) against the real kernel.
- Phone hygiene: one agent uses the phone at a time; leave it unlocked and plugged in; do not reboot it; do not change system settings beyond what a test needs and restore what you changed; take screenshots into ${SHOTS}/ (mkdir -p it).
`

const IMPL_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    commit: { type: 'string' },
    summary: { type: 'string' },
    filesTouched: { type: 'array', items: { type: 'string' } },
    tests: { type: 'array', items: { type: 'string' } },
    gatePassed: { type: 'boolean' },
    limitations: { type: 'array', items: { type: 'string' } },
    decisionDeviations: { type: 'array', items: { type: 'string' } },
    findings: { type: 'array', items: { type: 'string' } },
  },
  required: ['branch', 'commit', 'summary', 'filesTouched', 'tests', 'gatePassed', 'limitations'],
}

const REVIEW_SCHEMA = {
  type: 'object',
  properties: {
    approved: { type: 'boolean' },
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['blocker', 'major', 'minor'] },
          file: { type: 'string' },
          summary: { type: 'string' },
          fix: { type: 'string' },
        },
        required: ['severity', 'file', 'summary', 'fix'],
      },
    },
  },
  required: ['approved', 'findings'],
}

const INTEGRATE_SCHEMA = {
  type: 'object',
  properties: {
    branch: { type: 'string' },
    commit: { type: 'string' },
    mergedPackages: { type: 'array', items: { type: 'string' } },
    droppedPackages: { type: 'array', items: { type: 'string' } },
    gate: { type: 'string' },
    releaseGate: { type: 'string' },
    debugApk: { type: 'string' },
    releaseApk: { type: 'string' },
    notes: { type: 'string' },
  },
  required: ['branch', 'commit', 'mergedPackages', 'droppedPackages', 'gate', 'releaseGate', 'debugApk', 'releaseApk'],
}

const VERIFY_SCHEMA = {
  type: 'object',
  properties: {
    results: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          status: { type: 'string', enum: ['pass', 'fail', 'untestable'] },
          evidence: { type: 'string' },
          finding: { type: 'string' },
          fixHint: { type: 'string' },
        },
        required: ['id', 'status', 'evidence'],
      },
    },
    phoneLeftClean: { type: 'boolean' },
    notes: { type: 'string' },
  },
  required: ['results', 'phoneLeftClean'],
}

const FIX_SCHEMA = {
  type: 'object',
  properties: {
    commit: { type: 'string' },
    fixed: { type: 'array', items: { type: 'string' } },
    notFixed: { type: 'array', items: { type: 'string' } },
    gatePassed: { type: 'boolean' },
    debugApk: { type: 'string' },
  },
  required: ['commit', 'fixed', 'notFixed', 'gatePassed'],
}

const CLOSEOUT_SCHEMA = {
  type: 'object',
  properties: {
    mainCommit: { type: 'string' },
    reportPath: { type: 'string' },
    docsUpdated: { type: 'array', items: { type: 'string' } },
    releaseApk: { type: 'string' },
    debugInstalled: { type: 'string' },
    testSessionsDeleted: { type: 'boolean' },
    openItems: { type: 'array', items: { type: 'string' } },
  },
  required: ['mainCommit', 'reportPath', 'docsUpdated', 'releaseApk', 'debugInstalled', 'testSessionsDeleted', 'openItems'],
}

// The four work packages, fixed by the lead. Every item is decided; the briefs are the decisions.
const PACKAGES = [
  {
    id: 'Q1',
    title: 'Composer: prompt history direction and chip',
    brief: `
1. A18 / A11.6: reverse the fling direction on the EMPTY field: a fling from left to right recalls the PREVIOUS (older) prompt, from right to left the NEXT (newer) one. Update the unit tests, the content descriptions and any doc line that states the direction (docs/07, CLAUDE.md, the research note if it states it).
2. A18: the "Recent prompt n of N" chip (ui/composer/Composer.kt HistoryBrowseChip) becomes compact: no outline border, no leading icon (it duplicated the Restore checkpoint glyph), a smaller surface, the label and the × only; keep the semantics (the chip is a label, the × is "Stop browsing"). Nothing else in the composer changes; the full-screen editor keeps its three-line threshold.
Files: ui/composer/ComposerField.kt (gesture), ui/composer/Composer.kt (chip), their tests, strings if a description changes.`,
  },
  {
    id: 'Q2',
    title: 'Header icons, fork entry points, Move to workspace removal',
    brief: `
1. A15: the auto-approve control (A′, before the title) uses: Ask each time = the ticked shield (ms_gpp_good_fill or the existing gpp_good drawable), plain, no tinted disc, no ring; Auto-approve all = the bolt (ms_bolt), plain; pending-until-run-end = the TARGET state's glyph at low emphasis (onSurfaceVariant or alpha) with a small dot badge at its corner, instead of the ring/disc motion. Remove the disc/ring code and drawables that nothing else uses; update the approval sheet and Settings wording only if it names the old glyphs; keep A′/A behind the existing debug switch if it is still there, or remove the switch (release carries A′). Update tests and the design-note references (docs/research/auto-approve-control.md gets a short "as built after A15" paragraph).
2. A16 + A19: fork lives in the chat three-dot only. Remove the message-row fork icon ("Fork before this turn", the custom glyphs ms_fork_arm/stem variants if nothing else uses them, the beforeRunCount cut path in the app: fork.beforeRunCount must no longer be sent by any app path), and remove the "/fork" built-in from the slash popup: data/chat/SlashCommandSource.kt (BUILTINS, SlashKind.BuiltinFork), data/chat/SendPipeline.kt (BUILTIN_NAMES, the "/fork" resolution), ui/suggestions/SlashMenu.kt, ui/suggestions/ForkCommand.kt's "/fork" draft handling (keep the fork flow that the three-dot and the drawer row menu use), strings (slash_fork_desc etc.), tests. The popup then holds /team, /compact when a kernel has it (unchanged, hidden), and the user's skills, workflows and plugin commands. The drawer row menu keeps its Fork (A20 item 2).
3. A11.8 cancelled: remove "Move to workspace…" entirely: ui/sessions/MoveToWorkspace.kt, data/sessions/MoveRecovery.kt, the move paths in data/sessions/SessionActions.kt and data/sessions/SessionsHub.kt (keep session.delete for Delete session), the MOVE item in ui/sessions/SessionRowMenu.kt and the Move item in ui/chat/ConversationHeader.kt (HeaderAction.Move), the move mode of ui/workspace/WorkspacePickerViewModel.kt, move-only fields in data/sessions/SessionSummary.kt and data/chat/SessionCreateBuilder.kt, AppGraph wiring, strings (session_config_move/moving...), the app-private recovery storage, and their tests; docs/02 and docs/07 lose their move sections (the close-out agent rewrites the prose; you may leave a one-line "removed in batch 2 (A11.8 cancelled)" marker for it). "Delete session" and the subagent/team refusal logic that only the move used go with it; the checkpoint and fork code stays.
Files: ui/chat/ConversationHeader.kt, ui/approval/*, ui/chat/rows/MessageActions.kt, ui/chat/rows/RowRules.kt, drawables, the slash and fork files above, the move files above, tests.`,
  },
  {
    id: 'Q3',
    title: 'Sessions and transcript correctness',
    brief: `
1. Trial row 8: deleted sessions must not come back. filesDir/sessions/list-cache.json is reconciled with session.list whenever a full list arrives from the Hub (connected, authoritative): rows absent from the Hub's list are dropped; a session.get / open that answers session_not_found drops the row at once; sessions deleted by another client disappear on the next list. Keep the cache's purpose (instant drawer on cold start). Unit tests for the reconciliation.
2. Trial row 16: a title set at session.create was replaced by the first prompt after the first send. Find the cause: read the kernel (${FORK}: session title handling in the Hub session store and the agent runtime's auto-title; search for "title" in sdk/packages/core/src/hub and the session runtime) and the app's title paths. If the APP overwrites it (for example by sending the first prompt as the title, or by treating the kernel's auto-title event as authoritative over an explicit title), fix the app. If the KERNEL overwrites an explicit title on the first run, make the app re-apply the owner's title through the Hub's rename command right after the first run of a session the owner titled (one retry, no loop), and say so in your report; do not change the kernel.
3. A17 (G2): after the app answers "stop" at the mistake-limit sheet (the user's "Stop this run") or after the 5-minute timeout, the conversation shows why the run stopped: a notice row in the transcript, Material 3 inline style like the existing interruption notices, "Stopped after N mistakes in a row" with the kernel's detail text, or "Stopped: the question was not answered within 5 minutes". The app knows it gave that answer (data/session/ClientContributions.kt, MistakeLimitPrompt); run.aborted carries no reason, so the notice is synthesised app-side and persisted with the session's app-private state so it survives a reopen. Unit tests.
4. A21 (trial row 9): in a FINISHED turn (the run ended, from history or after a live run.aborted/run.completed), a tool call that has no result is drawn as "Interrupted" (the existing interrupted-row style) instead of "Running …" with a spinner; while the run is live it still shows Running. ui/chat/rows/RowRules.kt projection and tests. The desktop draws it as in progress; this is a decided rule-2 departure (A21).
5. A22 (trial row 17): a session started without choosing a folder uses the Termux home (data/workspace TERMUX_HOME, /data/data/com.termux/files/home) as workspaceRoot and cwd: ui/chat/ChatViewModel.kt homeConfig falls back to TERMUX_HOME instead of TermuxPaths.CHAT_WORKSPACE; remove CHAT_WORKSPACE if nothing else uses it. The Home chip's empty state reads "Home" (string home_workspace_chat becomes a "Home" string; tapping still opens the folder picker); the sessions drawer groups home sessions under "Home" (the path is excluded from the recent-workspaces catalog as before, so the group label must not depend on the catalog). Keep isChatWorkspacePath and the "Chat" label only for sessions whose path IS the kernel's chat workspace (imported sessions). Nothing is created or written in the home. Unit tests for the fallback and the labels; update docs/07 (D-decision A22) and CLAUDE.md where they describe the chat workspace default.
Files: data/sessions/* (list cache, KnownSessions), ui/chat/rows/RowRules.kt, data/session/ClientContributions.kt, transcript notice composables, ui/chat/ChatViewModel.kt, core/kernel/termux/TermuxCommand.kt, ui/home/*, ui/sessions/* (grouping), tests.`,
  },
  {
    id: 'Q4',
    title: 'Small defects: stop snackbar, haptic, landscape notice, notifications step',
    brief: `
1. Trial row 11: "The agent did not stop. Try again." must go away when the run really ends (observe the run state; dismiss on run end or on a successful later stop) and must not outlive the session; and a Stop whose request fails or times out from the composer shows the same message with "Restart kernel" as the approval sheet's Stop does (ComposerController.stop() must not return silently on a failed request). Unit tests.
2. Trial row 12: the steer long-press fires the haptic twice (combinedClickable's own long-click haptic plus the explicit one in SendButton): keep exactly one.
3. Trial row 13: with the keyboard open in landscape (height-compact window, D18) the stall notice is pushed under the status bar: keep it inside the safe area (window insets), or move it below the top bar in that configuration. Verify in the layout preview or a Compose test for the compact-height case.
4. Trial row 14: Reliability → Notifications: once the permission is granted ("Done") the item collapses like the other done items and no longer shows "Allow".
Files: ui/composer/ComposerController.kt, ui/composer/SendButton (or where the steer gesture lives), the stall notice composable, the Reliability step composable, tests.`,
  },
  {
    id: 'Q5',
    title: 'Transcript rows: folded by default (A23), compact 40 dp rows (A24), full command instead of the kernel echo',
    brief: `
1. A23 (decided 2026-10-08, option 1): no tool row opens by itself. Every tool row (commands, reads, edits, media, the final submit text) starts folded with its spinner or status in the header and opens only when tapped; once opened it stays open. Drop the auto-open rule (data/tools/ToolPresentation.kt shouldAutoOpen and whatever RowRules.kt / ToolCallRow.kt do with it); keep the thinking row and the "Worked for…" block folded as today; a running command's live output stays reachable by tapping its row. Unit tests: rows with a diff, with media, with running output and with submit text all start folded; the tap toggles; a reopened session shows them folded. Update design-system.md / docs where the auto-open rule is described.
2. Trial row 18: a multi-command row's "$ <command>" lines and the details screen print the command from the tool call input (run_commands input commands[i], matched by index to the result records) instead of the result's query echo, which the kernel caps at 200 characters and ends with "... [command truncated: N more chars; full command is in the tool call input]". Fall back to the echo only when the call input is missing; never show the echo note. Unit tests with a 300-character command, for the live path and the history path.
3. A24 (decided 2026-10-08): compact disclosure rows. 40 dp row height (the M3 dense list height; the touch target stays 40 dp), 18 dp icons, bodySmall labels, block gap 4 dp (TranscriptGap.Block), the body's rail inset reduced to match; thinking rows (ReasoningBlock.kt) and the "Worked for…" block (WorkBlock.kt) get the same metrics. Keep the change clean: ONE set of named tokens (row height, icon size, label text style, spinner size, block gap, rail inset) declared together in one place (where DisclosureRowMinHeight / ToolIconSlot / ToolSpinnerSize live today, or design tokens next to Spacing) and used by every disclosure row, so the owner can change 40 to 44 by editing one value; no per-row magic numbers. Update design/design-system.md (rows 48 dp becomes the dense 40 dp, with the 44 dp fallback noted). Unit tests where the metrics are testable; before/after screenshots at 360 dp of a run with six tool calls and a thinking row for the report.
Files: ui/chat/rows/ToolCallRow.kt, ui/chat/rows/Disclosure.kt, ui/chat/rows/ReasoningBlock.kt, ui/chat/rows/WorkBlock.kt, ui/chat/rows/RowRules.kt, ui/chat/rows/TranscriptContext.kt, design/design-system.md, ui/tool/ToolDetailBodies.kt, data/tools/ToolPresentation.kt, data/tools/ToolCallStore.kt (historyExecutions), data/tools/ToolSummary.kt, tests.`,
  },
]

// Device verification plan, fixed by the lead. ids are stable so fixers and re-verification can refer to them.
const VERIFICATION = [
  { id: 'V-HIST', packageId: 'Q1', needsRealModel: false, steps: 'Debug app on the bench. Open a session, empty field: fling left-to-right, expect the most recent prompt; fling again, the older one; fling right-to-left, back to the newer. Screenshot the compact chip (no outline, no leading icon). Slow drag still moves the cursor in a non-empty field.', expected: 'Direction as decided; chip compact; no regression in the attach sheet\'s "Recent prompts" row.' },
  { id: 'V-ICONS', packageId: 'Q2', needsRealModel: false, steps: 'Home and a chat at 384 dp: screenshot the control in Ask (ticked shield), Auto (bolt), and pending (switch during a live bench run: the target glyph at low emphasis with a dot badge; after the run ends it becomes the plain target glyph). Open the two-tap menu; check the approval sheet wording.', expected: 'A15 as decided; no disc, no ring; the pending state resolves at run end.' },
  { id: 'V-FORK', packageId: 'Q2', needsRealModel: false, steps: 'Type "/" in a session with messages: the popup lists /team and the user commands only, no /fork. Tap a user message: the action row shows copy, edit, restore (when a checkpoint exists), no fork icon. Three-dot → Fork: a whole-session fork opens. Sessions drawer row menu → Fork works the same.', expected: 'Fork only from the two menus; /fork gone; the fork inherits the whole session (no beforeRunCount).' },
  { id: 'V-MOVE', packageId: 'Q2', needsRealModel: false, steps: 'Three-dot and the drawer row menu show no "Move to workspace…"; grep the installed app\'s strings via the UI only (menus, Settings); confirm Delete session still works on a bench session.', expected: 'No move entry anywhere; delete intact.' },
  { id: 'V-R8', packageId: 'Q3', needsRealModel: false, steps: 'Create two bench sessions; delete one in the app, the other through the bench helper (another client); force-stop and cold-start the debug app; open the drawer.', expected: 'Neither deleted session comes back; the drawer matches session.list.' },
  { id: 'V-R16', packageId: 'Q3', needsRealModel: false, steps: 'Create a session with an explicit title in the app, send one bench prompt, wait for the run to end, reopen the session and the drawer.', expected: 'The title the owner set is still shown (either never overwritten, or re-applied once as the implementer reported).' },
  { id: 'V-G2', packageId: 'Q3', needsRealModel: false, steps: 'Bench session in the bench workspace with the mistake-limit marker; when the sheet appears tap "Stop this run"; then reopen the session.', expected: 'A notice row "Stopped after N mistakes in a row: …" in the transcript, also after reopen.' },
  { id: 'V-A21', packageId: 'Q3', needsRealModel: false, steps: 'Bench session created in the bench workspace with the bench hook that blocks a tool call (docs/10 V-P5-A5 recipe); trigger a blocked call; wait for the run to end; look at the transcript live and after a reopen.', expected: 'The blocked call is drawn as Interrupted in the finished turn, not Running.' },
  { id: 'V-R11', packageId: 'Q4', needsRealModel: false, steps: 'Start a long bench run (mock-long-output), tap Stop at the moment the run ends (or make the first stop fail by stopping the bench Hub briefly), observe the snackbar; restart the bench and check the Restart-kernel variant from the composer.', expected: 'The snackbar disappears once the run has ended; a failed Stop shows the message with Restart kernel.' },
  { id: 'V-R13', packageId: 'Q4', needsRealModel: false, steps: 'Rotate to landscape, open the keyboard, trigger the stall notice with the bench stall marker; screenshot.', expected: 'The notice is fully visible below the status bar.' },
  { id: 'V-R14', packageId: 'Q4', needsRealModel: false, steps: 'Open Reliability → Notifications with the permission granted (grant it to the debug app with adb if needed).', expected: 'The item shows Done and no "Allow".' },
  { id: 'V-DENSITY', packageId: 'Q5', needsRealModel: false, steps: 'Debug app on the bench at 360 dp: a run with six tool calls and a thinking row; screenshot folded; dump the view bounds (uiautomator dump) and convert with the device density to check the row height; tap a row to confirm the touch target still works; compare with the batch-1 screenshots of the same kind of run.', expected: 'Rows about 40 dp tall with 18 dp icons and smaller labels, 4 dp between blocks; thinking and Worked-for rows match; nothing clipped; one place in the code holds the numbers (name it in the report).' },
  { id: 'V-FOLD', packageId: 'Q5', needsRealModel: false, steps: 'Debug app on the bench: a run that edits a file (diff), runs a long command (live output), returns media and ends with submit text; watch the rows while it runs and after it ends; tap one row open and closed again; screenshot; then reopen the session from the drawer.', expected: 'Every tool row starts folded with its spinner or status in the header, during and after the run; the tap toggles; the reopened session shows them folded; thinking rows and the Worked-for block unchanged.' },
  { id: 'V-ECHO', packageId: 'Q5', needsRealModel: true, steps: 'Real kernel, DeepSeek, a "[test] " session in the scratch workspace: send "Run these two commands: echo start; and then echo followed by a quoted 300-character sentence of your choice". Look at the transcript row and its details screen; also reopen the session from the drawer (history path). Delete the session.', expected: 'Both commands appear in full on the $ lines; the text "[command truncated" appears nowhere; the output is complete.' },
  { id: 'V-R12', packageId: 'Q4', needsRealModel: false, steps: 'Long-press Send during a live bench run; capture "adb logcat" lines of the vibrator/haptic service (or dumpsys vibrator_manager) around the gesture.', expected: 'Exactly one haptic per long-press; if the service logs nothing observable, mark untestable and cite the unit test.' },
  { id: 'V-TEAM', packageId: 'Q2', needsRealModel: true, steps: 'Real kernel, DeepSeek, a "[test] " session in the scratch workspace: send "/team list the files in this folder and say done". Watch the Agents chip/roster; let it finish or stop it after 2 minutes; then delete the session. Also check "/team" alone shows the usage hint.', expected: 'The kernel starts a team, the app shows it in the Agents chip/roster, the run ends cleanly; the usage hint for a bare /team.' },
  { id: 'V-HOME', packageId: 'Q3', needsRealModel: true, steps: 'Real kernel, DeepSeek: on Home make sure no folder is chosen (the chip must read "Home"; screenshot), start a "[test] " session from there and send "Run pwd and show the output". Then open the drawer and look at the group the session sits in. Delete the session.', expected: 'The chip reads Home; pwd prints /data/data/com.termux/files/home; no ENOENT; the drawer groups it under Home.' },
  { id: 'V-R15', packageId: 'Q3', needsRealModel: true, steps: 'Real kernel, DeepSeek with thinking on, a "[test] " session: one short prompt ("In one sentence, what does ls do?"). Capture the raw session events (the app\'s event log or the kernel\'s session file over ssh) and compare the text blocks with the rendered transcript. Delete the session.', expected: 'A verdict for trial row 15: the self-check prose is the model\'s own text block (no app fix) or reasoning rendered as text (file a fix hint).' },
]

phase('Implement')
const implemented = await pipeline(
  PACKAGES,
  pkg => agent(`${RULES}
You are the IMPLEMENTER of work package ${pkg.id} "${pkg.title}" in your own git worktree of the app repo (already checked out for you; confirm with "git status" and "git log -1": HEAD must be ${HEAD}; if it is not, run "git checkout ${HEAD}" first). Create and switch to a branch named pkg/${pkg.id.toLowerCase()} from that commit.
Brief (the decisions are in docs/decided.md; this is their implementation reading):
${pkg.brief}
Implement the whole package with unit tests (and Compose UI tests where the repo already has that pattern), run the gate "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug" until it is green, commit, and return the structured report. If a decision cannot be implemented as written, implement the closest faithful form and list it under decisionDeviations with the reason. Do not use the phone in this phase. Strings go to res/values with proper names; no hardcoded text. When you remove something, remove it everywhere (code, strings, drawables, docs mentions, tests).`, { ...OPUS, label: `impl:${pkg.id}`, phase: 'Implement', schema: IMPL_SCHEMA, isolation: 'worktree' }),
  async (impl, pkg) => {
    if (!impl) return null
    let current = impl
    for (let round = 0; round < 2; round++) {
      const review = await agent(`${RULES}
You are the adversarial REVIEWER of work package ${pkg.id} "${pkg.title}", branch ${current.branch} (commit ${current.commit}) in the app repo. Read the diff against the base ("git -C ${APP} diff ${HEAD}...${current.branch}") and the touched files. Check: fidelity to the decisions in docs/decided.md and the brief below; Material 3 and the app's existing patterns; Hub protocol correctness against the kernel source in ${FORK}; removals that are complete (no dead code, strings, drawables or docs left behind for removed functions); tests that actually exercise the behaviour; strings and accessibility; nothing that touches the owner's real data unexpectedly; the gate claim. Brief: ${pkg.brief}
Implementer's report: ${JSON.stringify(current)}. Approve only if there are no blocker or major findings. Be concrete: file, what is wrong, how to fix.`, { ...OPUS, label: `review:${pkg.id}:${round + 1}`, phase: 'Implement', schema: REVIEW_SCHEMA })
      if (!review || review.approved) return { ...current, review }
      const fix = await agent(`${RULES}
You are the FIXER for work package ${pkg.id} on branch ${current.branch} in the app repo. Add a worktree for that branch under ${APP}/.claude/worktrees/fix-${pkg.id.toLowerCase()}-${round + 1} ("git -C ${APP} worktree add <path> ${current.branch}"), fix every finding below, re-run the gate "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug" until green, commit on the same branch, and return the structured report (branch unchanged, new commit).
Findings: ${JSON.stringify(review.findings)}`, { ...OPUS, label: `fix:${pkg.id}:${round + 1}`, phase: 'Implement', schema: IMPL_SCHEMA })
      if (!fix) return { ...current, review }
      current = { ...fix, branch: current.branch }
    }
    return current
  },
)
const built = implemented.filter(Boolean)
log(`implemented: ${built.map(b => b.branch).join(', ') || 'none'}`)
if (!built.length) throw new Error('no package was built')

phase('Integrate')
const integ = await agent(`${RULES}
You are the INTEGRATOR. In the app repo (${APP}, the main checkout, no worktree), check out batch/decided-1 (it must be at ${HEAD}), create the branch batch/polish-2 from it and merge these package branches in the order Q2, Q3, Q1, Q4, Q5, resolving conflicts faithfully to both packages: ${built.map(b => `${b.branch} (${b.commit})`).join(', ')}. After the merges run the FULL gate: "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug", then "bash scripts/build.sh assembleRelease lintRelease" and "bash scripts/build.sh testReleaseUnitTest -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false". Fix whatever the merge broke (do not drop a package unless it cannot be made to build; list drops). Report the APK paths (debug: dev.cline.termux.debug; release: signed with the debug keystore as batch 1 configured). Do not use the phone.
Package reports: ${JSON.stringify(built.map(b => ({ branch: b.branch, commit: b.commit, summary: b.summary, limitations: b.limitations, decisionDeviations: b.decisionDeviations, findings: b.findings })))}`, { ...OPUS, label: 'integrate', schema: INTEGRATE_SCHEMA, effort: 'high' })
if (!integ) throw new Error('integrate agent returned nothing')
log(`integrated ${integ.mergedPackages.length} packages on ${integ.branch} @ ${integ.commit}; dropped: ${integ.droppedPackages.join(', ') || 'none'}`)

phase('Verify')
const chunk = (arr, n) => arr.reduce((acc, x, i) => (i % n ? acc[acc.length - 1].push(x) : acc.push([x]), acc), [])
const builtIds = new Set(built.map(b => b.branch.replace('pkg/', '').toUpperCase()))
let pending = VERIFICATION.filter(v => builtIds.has(v.packageId) && !integ.droppedPackages.includes(v.packageId))
let debugApk = integ.debugApk
let headCommit = integ.commit
const allResults = []
for (let round = 0; round < 2 && pending.length; round++) {
  const groups = chunk(pending, 4)
  const roundResults = []
  for (const [gi, group] of groups.entries()) {
    const res = await agent(`${RULES}
You are DEVICE VERIFIER ${gi + 1} of round ${round + 1}. Branch batch/polish-2 at ${headCommit}; debug APK ${debugApk} (dev.cline.termux.debug). ${gi === 0 && round === 0 ? 'Install the debug APK on the S25U first ("adb -s $SERIAL install --user 0 -r <apk>"), start the bench if down ("bash scripts/dev-hub.sh status|start|reverse"), and point the debug app at the bench as docs/09 and CLAUDE.md describe for the developer loop.' : 'The debug APK should already be installed at this commit and the bench up; re-check both (reinstall if the installed versionName/commit differs) before starting.'}
Verify exactly these items, in order, one at a time, with adb (screenshots, input, logcat) and the bench or the real kernel as each item says:
${JSON.stringify(group, null, 1)}
Record evidence (what you saw, screenshot paths under ${SHOTS}/, log lines). A fail needs a concrete finding and a fix hint. Mark untestable only when the environment truly prevents the check (say why). Real-model items: DeepSeek, "[test] "-titled sessions in the scratch workspace, deleted at the end of your run. Point the debug app back at the bench when you are done with the real kernel. Leave the phone as you found it (app in a sane state, no stray dialogs), report phoneLeftClean honestly.`, { ...OPUS, label: `verify:r${round + 1}:g${gi + 1}`, phase: 'Verify', schema: VERIFY_SCHEMA })
    if (res) roundResults.push(...res.results.map(r => ({ ...r, round: round + 1 })))
  }
  allResults.push(...roundResults)
  const failed = roundResults.filter(r => r.status === 'fail')
  log(`verify round ${round + 1}: ${roundResults.filter(r => r.status === 'pass').length} pass, ${failed.length} fail, ${roundResults.filter(r => r.status === 'untestable').length} untestable`)
  if (!failed.length) { pending = []; break }
  if (round === 1) { log(`round 2 still has ${failed.length} failures; they go to the report as open`); break }
  const fix = await agent(`${RULES}
You are the ROUND-${round + 1} FIXER on branch batch/polish-2 (commit ${headCommit}) in the app repo (the main checkout of that branch, no worktree; nobody else edits it now). Fix every failed verification item below at the root cause, with a unit test where one applies, re-run the gate "bash scripts/build.sh assembleDebug testDebugUnitTest lintDebug" until green, commit, and return the new commit and the debug APK path. Do not use the phone.
Failures: ${JSON.stringify(failed, null, 1)}`, { ...OPUS, label: `fix:round${round + 1}`, phase: 'Verify', schema: FIX_SCHEMA, effort: 'high' })
  if (!fix) break
  headCommit = fix.commit
  if (fix.debugApk) debugApk = fix.debugApk
  const fixedIds = new Set(fix.fixed)
  pending = VERIFICATION.filter(v => failed.some(f => f.id === v.id && fixedIds.has(f.id)))
  log(`round ${round + 1} fixer: fixed ${fix.fixed.length}, not fixed ${fix.notFixed.length}; re-verifying ${pending.length}`)
}

phase('Closeout')
const closeout = await agent(`${RULES}
You are the CLOSE-OUT agent on branch batch/polish-2 (commit ${headCommit}) in the app repo. Tasks, in order:
1. Docs for what changed: docs/02-kernel-contract.md (remove the move section; note the fork is whole-session only; the mistake-limit stop notice; the Interrupted projection), docs/07-app-architecture.md (the one-place rule and its three exceptions from A20; the A15 icon mapping; the removal of Move; the prompt-history direction), docs/09-implementation-report.md (a new section "Batch 2, polish (2026-10-08)" pointing at the report), docs/research/auto-approve-control.md ("as built after A15"), CLAUDE.md of the app (anything a future agent must know: removed functions, the verify2 screenshots, bench markers added). Prose, not markers.
2. Write docs/11-batch2-report.md: what was built per package (with commits), the verification results of every round (pass/fail/untestable with evidence), deviations from decisions, known limitations, the row-15 verdict, the /team verdict, and what the owner should try first. Base it on: ${JSON.stringify({ built: built.map(b => ({ branch: b.branch, commit: b.commit, summary: b.summary, limitations: b.limitations, decisionDeviations: b.decisionDeviations, findings: b.findings })), integ, results: allResults })}
3. Update docs/decided.md's Build column for every item this batch built ("done (batch 2, <V-id>)" or "pending" with the reason), and docs/todo.md section C: set the status of trial rows 8, 9, 10, 11, 12, 13, 14, 15, 16 from the results; add rows only for new defects found and not fixed. Commit everything on batch/polish-2.
4. Run the full gate once more on the final head (debug: assembleDebug testDebugUnitTest lintDebug; release: assembleRelease lintRelease, testReleaseUnitTest with -Pandroid.onlyEnableUnitTestForTheTestedBuildType=false) and record the result in the report (amend the report commit).
5. Merge into main: "git checkout main && git merge --no-ff batch/polish-2" (main must fast-forward or merge cleanly; if it does not, stop and report instead of forcing), then run "bash scripts/build.sh assembleRelease" on main and report the release APK path (signed with the debug keystore). Do not delete the batch branches.
6. Phone: every [test] session in the owner's real Hub is deleted (the app's session list on the debug build, or "cline history" over ssh; never touch other sessions); the debug app points back at the real kernel; install the debug build (dev.cline.termux.debug) from main's final commit with "--user 0". Do NOT install over dev.cline.termux (the owner's app); the lead does that after its own gate.
Return the structured report.`, { ...OPUS, label: 'closeout', schema: CLOSEOUT_SCHEMA, effort: 'high' })

return {
  branch: integ.branch,
  verifiedCommit: headCommit,
  packages: built.map(b => b.branch),
  dropped: integ.droppedPackages,
  verification: allResults,
  closeout,
}
