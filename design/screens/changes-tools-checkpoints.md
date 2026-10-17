# Screen spec: Changes, tool detail and checkpoints

Status: design-phase spec, 2026-10-03. Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`). Tier 3 in
`docs/04-screens.md` ("Changes screen", "Tool detail screen", "Checkpoint dialogs").

This file builds on `design/design-system.md` (cited as **DS §n**) and does not redefine
anything it defines. Colours, type, shapes, motion, icons, the status mapping, the
back order and the component inventory come from there. Where this spec needs
something the design system does not have yet, it is listed in section 0.4 as a
**DS amendment** and must be added to the design system before implementation.

Screens covered:

| # | Screen | Kind |
| --- | --- | --- |
| A | Changes ("Uncommitted changes") | pushed route on < 1200dp; supporting pane at ≥ 1200dp |
| B | Tool detail | pushed route on < 1200dp; supporting pane at ≥ 1200dp |
| C1 | "Revert to this checkpoint?" dialog | `AlertDialog` |
| C2 | "Edit and restart from here?" dialog | `AlertDialog` |
| C3 | "Restore to Checkpoint" picker sheet (the CLI's `/undo`) | `ModalBottomSheet` |

---

## 0. Ground rules for this group

### 0.1 Sources this spec was built from

Desktop paths are under `~/workspace/cline-termux-v3-phone`. `webview/...` means
`apps/examples/desktop-app/webview/...` and `ui/...` means `sdk/packages/ui/...`
(DS "How to read this").

| Source | What was taken |
| --- | --- |
| `webview/components/views/chat/diff-view.tsx` | `DiffView`: panel title "Uncommitted changes", empty text "No file changes in this session yet.", per-file sections collapsed only by user toggle (all expanded at first; the collapse state is component state and resets when the view closes), copy path with check feedback for **1600 ms** (lines 124-127), "Copy failed" toast copy, "No hunk details available.", one `ToolFileDiff` per hunk, the "complete new contents" rule (old empty and both starts at 1 → show line numbers, else fragment). The "Open in" editor menu (`Open in`, `System default`) is desktop-only and is replaced by Share (section A.6). |
| `ui/components/agent-changes.tsx` | `AgentChangesPanel` header ("Files: N" badge, close button "Close diff view", optional `notice`, `emptyMessage`), `AgentChangedFile` header order: chevron, path (mono, truncated), copy button ("Copy file path for <path>", title "Copy file path"), spacer that also toggles, host actions, `+N`, `-N`. |
| `webview/lib/session-diff.ts` | The whole diff derivation: `normalizeToolOutput`, `parseApplyPatchInput`, `parseDiffFromEditorResult`, `parseEditorFileDiff`, `parseApplyPatchFileDiffs`, `canonicalizeDiffPath`, `mergeToolDiffs` (keeps the last **30** hunks per file), `summarizeFileDiffs`. Ported 1:1 (section 1.2). |
| `webview/components/views/chat/messages/tool-message-block.tsx` | `ToolCallRow`: running gate (`inProgress && isRunActive`), submit_and_exit labels, command output source (`meta.toolOutput` → `extractRunCommandOutput(result)` → `outputText`), `canProceed` rule (running + command + `toolDetachable === true` + session id), "Proceed while running" outline button with spinner, error "Could not detach the running command.", body order (lines 229-320): details (the `$ <command>` lines, or the full path line of an edit) **above** the per-hunk diffs, then output, images, media, "Input", and the error text **last**. The "Output" overline exists only inside `AgentCommandOutput`; non-command `outputText` is a bare `ToolActivityCode` block. |
| `ui/components/agent-chat/index.tsx` `formatWorkDuration` (lines 837-846) | Duration text, clamped to **at least 1 s** (`Math.max(1, Math.round(ms / 1000))`): "1s", "4m 12s", "1h 3m", "1h". |
| `webview/lib/command-output.ts` | Live output cap `MAX_LIVE_COMMAND_OUTPUT_CHARS = 48_000` with the head marker `ESC[0m[Earlier command output truncated]\n`. |
| `ui/components/agent-command-output.tsx` | "Output" overline, `role=log` box, auto-scroll pinned within **24px** of the bottom, blinking cursor block while running, wrapped text (`whitespace-pre-wrap break-words`), `aria-label="Command output"`, `aria-live="off"`. |
| `ui/components/agent-chat/tool-diff.tsx` | `ToolFileDiff`: unified style, no file header, fragments hide line numbers, trailing newline normalisation, unparsable/identical contents render nothing. |
| `ui/components/agent-chat/tool-summary/index.ts` | `ToolSummaryItem` (`file` with `oldText`/`newText`/`fragment`/`hunks`, `command`, `query`, `url`, `text`), `ToolLabelPart`, `ToolKind`. |
| `webview/components/views/chat/messages/tool-summaries.ts` | `buildToolPresentation`, `extractRunCommandOutput` (multi-command output joined as `$ <query>\n<output>` blocks; the app prints the call input's command instead of `query`, trial row 18), `extractSubmitSummaryText`, `formatToolValue`. |
| `webview/components/views/chat/chat-messages.tsx` | Both checkpoint `AlertDialog`s verbatim ("Revert to this checkpoint?" / "Revert", "Edit and restart from here?" / "Continue", their descriptions, "Cancel"), the disabled rule for edit/restore (lines 665-686: status starting, running or **stopping**, session switching, another versioning action pending), error fallbacks "Could not restore checkpoint.", "Could not restart from this message.", dialogs reset when the session changes. The confirm button **closes the dialog first** (`setCheckpointConfirmation(null)` / `setEditConfirmation(null)`, lines 838-847 and 875-884) and only then starts the async work: the dialog never shows a pending state. |
| `webview/components/views/chat/messages/message-bubble.tsx` | User action row: "Copy user message", "Edit user message" (title "Edit message and restart from this point"), "Restore checkpoint" (shown only when the message carries `meta.checkpoint`), the pending state is a spinner **in the originating icon** (lines 297-340), errors under the row (end-aligned, `text-xs text-destructive`), actions kept visible while pending or failed. |
| `webview/components/views/chat/messages/group-messages.ts` | `buildUserRunCountMap` (run count per user message). |
| `webview/hooks/use-chat-session.ts` | `restoreCheckpoint`: refuses while busy ("Wait for the current turn to finish before undoing"), clears approvals/questions/queue, gets a **new session id**, loads its messages, status "completed" if any; `proceedWhileRunning`: "The command finished before it could be detached." when `detachedCount < 1`; live tool output merges stdout and stderr in arrival order. |
| `webview/app/page.tsx` | Edit = fork before the run (`beforeRunCount`) and open the new session with the message text in the composer. The diff view **replaces the chat body** (line 2619: `DiffView` is rendered in place of `ChatMessages`; there is no side panel), closes on session change/delete (lines 2115, 2193) and closes whenever the summary drops to zero (lines 2471-2475, `if (!hasDiffChanges) setShowDiffView(false)`); `handleOpenDiff` refuses to open at zero. |
| `webview/components/agent-header.tsx` | Diff chip label `+N -N`, CD "Open diff: N additions, N deletions", shown when additions ≠ 0 (DS ConversationHeader). |
| `apps/examples/desktop-app/sidecar/chat-session.ts` | `handleRestoreCheckpoint` (`restore: {messages: true, workspace: true}`, no `sessionMetadata`, so a reverted session does not carry the title), edit-fork: with a checkpoint at or before the run → restore `{messages, workspace: true, omitCheckpointMessageFromSession: true}`; without one → start a new session from the messages trimmed **before** that run on the current workspace. Both edit branches send `forkMetadata = {...sourceMetadata, fork: {forkedFromSessionId, forkedAt, source: sourceSession.source ?? "desktop", beforeRunCount, checkpoints: sourceMetadata.checkpoint}}` (lines 1720-1738), so an edited session keeps its title. The sidecar resolves the provider key in-process (lines 916-932); the app cannot (1.5). |
| `apps/examples/desktop-app/sidecar/session-data/messages.ts` | A user message gets `meta.checkpoint` only when `metadata.checkpoint.history` holds an entry whose `runCount` equals that message's run count (line 437, `checkpointsByRunCount.get(userRunCount)`). |
| `apps/cli/src/tui/components/dialogs/checkpoint-confirm.tsx` | The chat-only vs chat+workspace choice: "Restore chat only" / "Rewind conversation, keep current files", "Restore chat and workspace" / "Rewind conversation and reset files", warning "This runs git reset --hard and git clean -fd in the workspace.", "Restore to: "<preview>"". |
| `apps/cli/src/tui/components/dialogs/checkpoint-picker.tsx`, `apps/cli/src/tui/checkpoint-picker-items.ts`, `apps/cli/src/tui/root.tsx` (`openCheckpointRestore`, lines 344-440), `apps/cli/src/runtime/interactive/session-runtime.ts` (`restoreCheckpoint`, lines 796-830) | The `/undo` picker: title "Restore to Checkpoint", 60-character previews, newest preselected; rows = every user message that has a checkpoint **at or before** its run (`checkpointForRun`), row time = that entry's `createdAt`. Toasts "No checkpoint data available", "No checkpoints available" (empty history), "No checkpoints to restore" (history present, no row maps), "Restored to checkpoint", "Wait for the current run to finish before restoring", "Checkpoint restore already in progress"; status line "Restoring to checkpoint (chat + workspace)..." / "Restoring to checkpoint (chat only)..."; error "Checkpoint restore failed: <message>". The restore always sends `omitCheckpointMessageFromSession: true` and puts the picked message's display text back into the input: the picker is the desktop's **edit and restart** semantic, not a pure revert (C.6). |
| `sdk/packages/core/src/hub/server/handlers/session-handlers.ts` (`handleSessionCreate` lines 447-451, `handleSessionRestore` lines 640-850), `run-handlers.ts` (`handleRunProceedWhileRunning` lines 373-402), `run-queue-handlers.ts` (`DRAIN_REFUSED_COMMANDS` lines 29-36) | Exact Hub payloads, replies and error codes (section 1.5). `handleSessionRestore` builds `apiKey: sessionConfig.apiKey ?? modelSelection.apiKey ?? ""` where `handleSessionCreate` ends in `?? undefined` (open question Q2, answered in 1.5). `hub_draining` gates only `session.create`, `session.restore`, `session.fork`, `run.start`, `session.send_input`, `run.enqueue`. |
| `sdk/packages/core/src/hub/server/hub-server-transport.ts`, `browser-websocket.ts` (lines 239-272), `shared/src/hub.ts` `resolveHubCommandTimeoutMs` | The Hub races each handler against `envelope.timeoutMs` (passed through unclamped), replies `hub_command_timeout` **without cancelling the handler** (1.5). |
| `sdk/packages/core/src/hub/server/hub-event-log.ts` (lines 21-25) | Durable replay is bounded: 7 days, 200,000 rows, 64 MB, pruned every 16 MB appended (B.3). |
| `sdk/packages/core/src/hooks/checkpoint-hooks.ts`, `session/checkpoint-restore.ts`, `session/session-versioning-service.ts`, `hub/runtime-host/hub-runtime-host.ts` | How checkpoints are really created and restored (section 0.3). |
| `sdk/packages/core/src/extensions/tools/executors/editor.ts`, `bash.ts`, `run-command-execution-controller.ts` | Editor result text (`Edited <path>` + a ```` ```diff ```` block of `-N: text` / `+N: text` lines, `File created successfully at: <path>`), detach notice `[Command is still running. Output will continue in <path>]` (bash.ts:887), the leftover-children notice `[Command completed with background processes still running; their output is no longer captured]` (bash.ts:1093), the 48 ms update coalescing and `detachable` flip; `proceedWhileRunning` returns `detachedCount: 0` (never an error) when nothing is registered. |
| `sdk/packages/core/src/session/checkpoint-restore.ts` | `findCheckpointForRun` (highest entry with `runCount ≤ N`), `trimMessagesToCheckpoint`, `trimMessagesBeforeUserRun`, `createRestoredCheckpointMetadata`, and every restore error text quoted in 1.5 (lines 59, 237-242, 259, 275-292, 386, 403-427). |

Voice app reuse (copy with the header
`// Adapted from cline-termux-voice (same owner): <original path>`):
`ui/Components.kt` `PageScaffold` + `LocalSnackbarHost` + `bottomSafe` (pushed pages; the copy
gets the changes listed as DS-A10: a composable title slot, a selectable app-bar kind,
and the back CD from `R.string.action_back` instead of the voice app's hard-coded "返回"),
`ui/chat/WorkRows.kt` rail drawing (`StepList`: 1dp `outlineVariant` line) for the
inline tool body the detail screen mirrors, `ui/markdown/Markdown.kt` for
`submit_and_exit` summaries and markdown tool output.

### 0.2 Code layout for this group

```
app/src/main/kotlin/dev/cline/termux/
  session/diff/SessionDiff.kt          port of webview/lib/session-diff.ts (pure, unit-tested)
  session/diff/SessionDiffStore.kt     per-session diff state, fed by events + history
  session/diff/LineDiff.kt             Myers line diff for hunk rendering (pure)
  session/diff/UnifiedDiffText.kt      builds shareable unified-diff text (pure)
  session/tools/ToolCallStore.kt       per-session live tool state incl. command buffers
  session/tools/ToolPresentation.kt    port of tool-summaries.ts + buildToolSummary bridge
  session/checkpoint/CheckpointIndex.kt  run counts, checkpoint lookup, previews (pure)
  session/checkpoint/CheckpointActions.kt  revert / edit-restart orchestration
  ui/terminal/AnsiParser.kt            SGR/CSI/OSC parser -> styled spans (pure)
  ui/terminal/TerminalBuffer.kt        line model with \r handling and head cap
  ui/terminal/TerminalView.kt          composable (Section B.4)
  ui/diff/DiffHunkView.kt              composable for one hunk (Section A.4)
  ui/changes/ChangesScreen.kt, ChangesViewModel.kt, ChangesPane.kt
  ui/tool/ToolDetailScreen.kt, ToolDetailViewModel.kt
  ui/checkpoint/CheckpointDialogs.kt, CheckpointPickerSheet.kt, CheckpointViewModel.kt
```

`TranscriptItem.ToolCall`, `CommandExecution`, `SessionReducer`, `HubPayloads`,
`HubSessionApi` and `UserInputFormat` already exist in `session/model/` and
`kernel/hub/`; this spec extends them and does not fork them.

### 0.3 Correction to `docs/08-hub-fixtures.md` and the current `HubPayloads`

`docs/08-hub-fixtures.md` ("Checkpoints are not created by the daemon") and the
comment in `HubPayloads.sessionCreate` (`checkpoint { enabled = false } // client
contribution only`) are **wrong** on one point, verified by reading the code:

- `services/local-runtime-bootstrap.ts:544-556` installs `createCheckpointHooks` in the
  daemon whenever `sessionConfig.checkpoint.enabled === true`.
- `hooks/checkpoint-hooks.ts` `createCheckpoint` uses a client's custom function **only
  if one is contributed**; otherwise it runs the built-in git snapshot in the daemon
  (`git stash create` style commits, refs under `refs/cline/checkpoints/<sessionId>/`).
- `hub-runtime-host.ts:341` registers the `checkpoint.create` contribution only when
  the CLI's local runtime has a custom `createCheckpoint`; the stock CLI has none.
- The built-in path first runs `git rev-parse --is-inside-work-tree` in the session
  cwd and silently skips the checkpoint when that fails. The fixture's scratch workspace
  had no `.git`, which is why `checkpoint.history` stayed `[]`.

Consequences, binding for implementers:

1. `HubPayloads.sessionCreate` and every resume/fork/restore payload send
   `sessionConfig.checkpoint = {enabled: true}` (as `docs/03-feature-matrix.md` says).
2. Checkpoints exist only when **(a)** the workspace is inside a Git work tree and
   **(b)** `git` is installed in Termux (`pkg install git`; the daemon `execFile`s `git`
   from Termux's `PATH`). The app never creates `.git` itself. Section C3 explains this
   to the user and detects which of the two is missing.
3. A new fixture `07-checkpoint-restore.jsonl` must be recorded against a Git workspace
   before Tier 3 is built (open question Q1).

### 0.4 DS amendments this spec needs (add to `design/design-system.md` first)

| # | Amendment | Why |
| --- | --- | --- |
| DS-A1 | *(Android extension, not a desktop copy.)* At width ≥ 1200dp **and** height ≥ 480dp the chat's `NavigableSupportingPaneScaffold` (adaptive-navigation 1.3) hosts a 400dp supporting pane holding **either** Changes **or** Tool detail (one at a time, last opened wins). While that pane is open the Chats **list pane is collapsed** (the same state the rail `menu` toggle drives, DS §5.2) and it is restored to its previous state when the pane closes, so the widths are rail 80 + chat ≈ 764 + pane 400 on the Tab S7+ landscape (≈ 1244dp). Correct the DS §5.2 sentence "matching the desktop's side panel": the desktop **swaps the chat body** for the diff view (`page.tsx:2619`); Android pushes a route below 1200dp and shows the pane beside the chat at ≥ 1200dp so the conversation stays visible. | A full-screen push on a 1244dp window wastes the width and hides the conversation the changes belong to; keeping the 400dp list pane open as well would leave the chat ≈ 364dp, narrower than a phone, and the composer settings row (DS #6) would not fit. |
| DS-A2 | Component **KernelStrip**: a row with `heightIn(min = 48.dp)` (no fixed height, DS §8.1 #8) (`surfaceContainerHigh`, `shapes.large`, 16dp gutters) with the DS §1.6 kernel dot, line 1 / line 2 of the kernel-connection table, and a trailing `TextButton` "Start kernel" when the state is *unavailable* or *Termux asleep*. The content is a `FlowRow`: at large font scales the button wraps under the text. Used at the top of Changes and Tool detail. | Both screens are reachable while the kernel is down and must show it in the same words as the drawer. |
| DS-A3 | `CommandOutput` cap: the desktop code caps live output at **48,000 characters** and drops the **head** with the marker "[Earlier command output truncated]" (`webview/lib/command-output.ts`), not 64 KiB with an "[output truncated]" tail. The inline row uses that rule; the Tool detail screen keeps a larger buffer (B.6). | DS #CommandOutput disagrees with the desktop source. |
| DS-A4 | ConversationHeader overflow gains "Restore checkpoint…" *(Android, from the CLI `/undo`)*, between "Details" and "Export". | Entry point for C3. |
| DS-A5 | ToolCallRow body gains a trailing `TextButton` "View details" *(Android)* and the row's long-press menu gains "View details", "Copy command", "Copy output". | Entry points for B. |
| DS-A6 | User message long-press menu: "Copy message", "Edit and restart from here" *(Android)*, "Restore checkpoint". | Entry points for C1/C2 on touch. |
| DS-A7 | Add these Material Symbols Rounded drawables to DS §7.4 (DS §11 makes that table exhaustive): `ms_unfold_more`, `ms_unfold_less` (Changes expand/collapse all), `ms_difference` (Changes empty), `ms_cloud_off` (kernel down, nothing cached), `ms_search_off` (tool not found), `ms_history` (C3 empty), `ms_block` (rejected tool), `ms_warning` (interrupted tool, git warning line), `ms_open_in_full` (pane "Open full screen"), and the filled variant `ms_error_fill` (failed tool icon, fill 1). Also note in DS §8.2 #6: the collapsed chevron is `expand_more` rotated **−90° in LTR and +90° in RTL** (rotation is not auto-mirrored). | Nine glyphs used here are missing from the DS icon tables. |
| DS-A8 | Resolve three token conflicts in the DS, then this spec cites them: (a) dialog titles use `headlineSmall` (M3 `AlertDialog` default; fix the DS §2.1 `titleLarge` row "dialog titles"); (b) the dialog icon uses the M3 default `AlertDialogDefaults.iconContentColor` (`secondary`); (c) destructive confirms in `AlertDialog`s are `TextButton(colors = textButtonColors(contentColor = error))` (DS #16, M3 dialogs use text buttons): change DS §4.3 "destructive" to "`TextButton` with `error` content everywhere; a filled `error` `Button` only on full-screen destructive pages". | DS §2.1, §4.3 and #16 disagree. |
| DS-A9 | DS §5.8 back order gains, between items 7 and 8: "7a. Supporting pane open (`NavigableSupportingPaneScaffold`): predictive close of the pane (built-in), focus returns to the chat". DS §5.2 names `NavigableSupportingPaneScaffold` + `rememberSupportingPaneScaffoldNavigator()` instead of plain `SupportingPaneScaffold` (which registers no back handling). | Without it, back on the Tab S7+ pops the chat while the pane stays open. |
| DS-A10 | The copied voice `PageScaffold` becomes `PageScaffold(title: @Composable () -> Unit, appBar: PageAppBar = PageAppBar.Medium, onBack, actions, bottomBar: @Composable () -> Unit = {}, floatingActionButton, content)` with `PageAppBar.Medium` (`MediumTopAppBar` + `exitUntilCollapsedScrollBehavior`, as now) and `PageAppBar.SmallPinned` (`TopAppBar`, 64dp, `pinnedScrollBehavior`, hairline on scroll). The back `IconButton` CD is `stringResource(R.string.action_back)` ("Back"). A convenience overload keeps `title: String`. | Tool detail needs a pinned bar over a scrolling terminal (B.4) and both screens need composed titles (badge, kind icon, mono parts). |

If the owner rejects DS-A1, Changes and Tool detail are pushed routes at every width and
the two-pane layouts in A.4 and B.4 apply inside them.

---

## 1. Shared data layer

All three screens read the same per-session state. Nothing here talks to the UI
directly; ViewModels collect `StateFlow`s.

### 1.1 Kernel API used by this group (quoted from `docs/03-feature-matrix.md`)

| Kind | Name | Payload / use here |
| --- | --- | --- |
| event | `tool.started` | `{toolCallId, toolName, input}`. Starts a tool record. Events without `toolCallId` (hook-derived duplicates) are ignored. Envelope `timestamp` = start time. |
| event | `tool.updated` | `{toolCallId, toolName, update}`; for `run_commands` `update = {stream:"stdout"\|"stderr", chunk, executionId, detachable, commandIndex, query, truncated?, droppedChars?}`, chunks **incremental**, first chunk empty. |
| event | `tool.finished` | `{toolCallId, toolName, output, error?}`. Feeds the diff store and the tool record. Envelope `timestamp` = end time. |
| event | `run.started`, `run.completed`, `run.failed`, `run.aborted` | `isRunActive` gate; `run.completed.payload.result.toolCalls[{id, name, input, output, durationMs, startedAt, endedAt}]` refines durations. |
| event | `run.interrupted` | `{reason:"hub_restart"}` → tools still running become *Interrupted* (B.5). |
| event | `session.updated` | `{session?, snapshot}`; `snapshot.status` (busy gate), `snapshot.checkpoint.history[]` (`{ref, createdAt, runCount, kind?}`). |
| event | `session.created` | published for the session that `session.restore` starts. |
| event | `approval.requested` | the approval sheet opens over A and B when they belong to that session (DS §5.5). |
| event | `hub.drain_changed` | ends the *draining* wait (1.6). |
| command | `session.messages {sessionId}` | history: assistant `tool_use {id, name, input}` + user `tool_result {tool_use_id, name, content, is_error?}`; user text blocks for run counts and previews. |
| command | `session.get {sessionId, includeSnapshot:true}` | `session.cwd`, `workspaceRoot`, `metadata` (provider, model, mode, title, `checkpoint.history`), `snapshot.checkpoint.history`, `snapshot.status`. |
| command | `run.proceed_while_running` | `{sessionId, toolCallId?} -> {detachedCount}` (B). |
| command | `run.abort` | `{sessionId, reason?} -> {applied:true}` ("Stop agent" in B). |
| command | `session.restore` | atomic path: `{sessionId, checkpointRunCount, restore:{messages:true, workspace, omitCheckpointMessageFromSession?}, sessionConfig, metadata} -> {session, snapshot, messages, checkpoint}` (C, needs kernel fix K1, 1.5). Split path step 1: `{sessionId, checkpointRunCount, restore:{messages:false, workspace:true}} -> {checkpoint}` (resets the workspace, starts no session). |
| command | `session.create` | edit without a checkpoint, and split path step 2: `{sessionConfig, initialMessages, metadata:{…, fork?, restoredFromSessionId?, restoredCheckpointRunCount?}}` (C2, 1.5). |
| command | `session.detach {sessionId}` | detach from the source session after a restore; best-effort (may answer `session_not_found` for a source that was not live; never fails the flow). |
| command | `session.list {limit, rootOnly:true}` | lost-reply recovery for restore (1.6). |
| frame | `stream.subscribe {clientId, sessionId?, sinceSequence?}` | replay rebuilds live tool output after process death, as far as the bounded durable log still holds it (B.3). |
| RUN_COMMAND | `cline hub start`, `termux-wake-lock` | "Start kernel" in KernelStrip, through the existing `KernelCoordinator` (never called from these ViewModels directly). |
| RUN_COMMAND | `/data/data/com.termux/files/usr/bin/git -C <cwd> rev-parse --is-inside-work-tree` | C3 empty state diagnosis only (C3.5). Background runner, 10 s timeout. |

Envelope rules (from `docs/08-hub-fixtures.md`): commands are
`{kind:"command", envelope:{version:"v1", command, requestId, clientId, sessionId?, timeoutMs?, payload}}`.
Session-scoped commands put the session id in `envelope.sessionId` **and**
`payload.sessionId`. Every reply is optional: terminal truth is the event stream.

### 1.2 `SessionDiff.kt` (port of `webview/lib/session-diff.ts`)

Port every function verbatim, same names in camelCase, same edge cases, with the
desktop's `session-diff.test.ts` cases copied into `SessionDiffTest.kt`.

```kotlin
data class SessionDiffHunk(val oldStart: Int, val newStart: Int, val old: String, val new: String) {
    /** desktop DiffHunk: complete new contents (editor create, apply_patch Add File) */
    val isCompleteNewContents: Boolean get() = old.isEmpty() && oldStart == 1 && newStart == 1
}
data class SessionFileDiff(val path: String, val additions: Int, val deletions: Int, val hunks: List<SessionDiffHunk>,
                           /** Android: true when mergeToolDiffs dropped older hunks (kept the last 30). */
                           val hunksTruncated: Boolean = false)
data class SessionDiffSummary(val additions: Int, val deletions: Int)
data class SessionDiffState(val fileDiffs: List<SessionFileDiff>, val summary: SessionDiffSummary)

/** The desktop's SessionHookEvent, reduced to what the parsers read. hookEventName is always "tool_result". */
data class ToolResultEvent(val toolName: String, val toolInput: JsonElement?, val toolOutput: JsonElement?, val toolError: String?)

object SessionDiff {
    const val MAX_HUNKS_PER_FILE = 30                       // desktop .slice(-30)
    fun parseApplyPatchInput(input: String): List<SessionFileDiff>
    fun mergeToolDiffs(events: List<ToolResultEvent>, cwd: String?): List<SessionFileDiff>
    fun summarize(files: List<SessionFileDiff>): SessionDiffSummary
    fun build(events: List<ToolResultEvent>, cwd: String?): SessionDiffState
    fun resolveAbsolutePath(path: String, cwd: String?): String  // port of resolveWorkspaceFilePath
}
```

Mapping the Hub to `ToolResultEvent` (the desktop reads sidecar hook events; the app
reads Hub events):

| Source | toolName | toolInput | toolOutput | toolError |
| --- | --- | --- | --- | --- |
| live `tool.finished` | `payload.toolName` | the `input` remembered from the matching `tool.started` (by `toolCallId`) | `payload.output` | `payload.error` (string) or `null` |
| history `tool_result` block | `block.name` (or the paired `tool_use.name`) | the paired `tool_use.input` (by `tool_use_id`) | `block.content` | `block.content` as text when `is_error == true`, else `null` |

Only `editor` and `apply_patch` produce diffs (desktop rule). `normalizeToolOutput`
must accept a record, a JSON string and a content-block array, exactly as ported.
`cwd` is `session.cwd ?: session.workspaceRoot` from `session.get`.

### 1.3 `SessionDiffStore`

One instance per open session, owned by the session repository (not by a screen), so
the ConversationHeader chip, Changes and Tool detail agree.

- **Seed:** on first subscription for a session, call `session.messages`, build the
  `ToolResultEvent` list from the `tool_use`/`tool_result` pairs, `SessionDiff.build`.
- **Live:** on each `tool.finished` for that session, append one event and rebuild
  incrementally (`mergeToolDiffs` over the cached event list; recompute is O(events)
  and runs on `Dispatchers.Default`).
- **After replay:** events carry `sequence`; ignore a `tool.finished` whose
  `toolCallId` is already in the event list (replays and the global+session double
  delivery from `docs/08` §Registration).
- **Exposes** `StateFlow<DiffStoreState>`:

```kotlin
sealed interface DiffStoreState {
    data object Loading : DiffStoreState
    data class Ready(val diff: SessionDiffState, val cwd: String?, val updatedAt: Long,
                     /** paths whose counts changed in the last update; drives the highlight in A.7 */
                     val changedPaths: Set<String>) : DiffStoreState
    data class Failed(val message: String) : DiffStoreState
}
```

### 1.4 `ToolCallStore` and command buffers

Extends the existing `TranscriptItem.ToolCall`/`CommandExecution` (the reducer already
appends chunks per `executionId` and stream). For the detail screen the store also
keeps, per execution, an **interleaved** buffer in arrival order (the desktop shows one
buffer: stdout and stderr merged as they arrive) plus per-stream buffers for the filter
in B:

```kotlin
data class OutputChunk(val stream: Stream, val text: String)   // Stream = Stdout | Stderr
data class CommandBuffer(
    val executionId: String, val commandIndex: Int, val query: String,
    val chunks: List<OutputChunk>,          // head-capped, see caps below
    val headDroppedChars: Long,             // app-side cap
    val kernelDroppedChars: Long,           // sum of update.droppedChars (kernel-side)
    val detachable: Boolean,
    val success: Boolean?,                  // from tool.finished output[commandIndex].success
    val result: String?,                    // from tool.finished output[commandIndex].result
)
```

Caps:

| Consumer | Cap | Rule |
| --- | --- | --- |
| Inline tool row (chat) | 48,000 chars | desktop `appendCappedCommandOutput`: keep the tail, prefix `ESC[0m[Earlier command output truncated]\n` |
| Tool detail screen | 1,048,576 chars per execution | same head-drop rule and marker; the marker line is rendered as a notice row (B.4) |

The kernel's own skip marker (`ESC[0m[N command-output characters skipped while
streaming]\n`, sent with `truncated: true, droppedChars: N`) is kept as text and also
rendered as a notice row.

Output source precedence (desktop `commandOutputSource`, `tool-message-block.tsx:99-104`):

1. Streamed chunks, if any, **always win**, also when the tool then fails: a command
   that streamed 500 lines and exited non-zero keeps those 500 lines.
2. Otherwise (history, or a command that streamed nothing) `extractRunCommandOutput`
   of `tool.finished.output` / `tool_result.content`, then the summary `outputText`.
   Both read the result as presented, with the call input's commands in its records'
   `query` (trial row 18, B.9), so neither prints the kernel's capped echo or its note.
   This fallback is **dropped when the payload is an error** (`isError` /
   `tool.finished.error`): an errored tool without streamed output shows only the
   error section.

Exception for gapped replays (B.3): after `tool.finished`, when the replayed chunk
buffer of an execution is known to be incomplete, the detail screen prefers
`output[commandIndex].result` (the kernel's own middle-truncated final text) over the
gapped buffer.

### 1.5 Exact Hub payloads for this group

#### 1.5.1 Credentials on restore (open question Q2, answered from the source)

`handleSessionRestore.buildStartInput` (`session-handlers.ts:737-741`) sets
`apiKey: sessionConfig.apiKey ?? modelSelection.apiKey ?? ""`, where `handleSessionCreate`
(lines 447-451) ends in `?? undefined`. Nothing downstream rescues the empty string for
API-key providers: `local-runtime-host.ts:383-403 applyInitialOAuthCredentials` refills a
blank key only for providers with a managed OAuth handler
(`runtime-oauth-token-manager.ts:67-76`), and `local-runtime-bootstrap.ts:239`
(`apiKey: config.apiKey ?? stored?.apiKey`) lets `""` beat the stored key. So a
`session.restore` without `apiKey` (the app never holds keys) starts a session whose first
turn fails `Unauthorized` on anthropic, openrouter, openai-compatible and every other
non-OAuth provider; OAuth providers (cline, openai-codex, …) are unaffected. The desktop
never hits this because its sidecar resolves the key in-process, and the CLI sends its key
in `sessionConfig`.

Binding:

- **Kernel fix K1** (Termux port, owner decision): in `handleSessionRestore`, change the
  final `: ""` of the `apiKey` expression to `: undefined`, as in `handleSessionCreate`.
  `session-handlers.ts` then becomes a port-modified upstream file and, like
  `sdk/packages/shared/src/parse/shell.ts`, stays out of `allowedConflictPaths`. The
  port's sandbox test gains: create a session on a provider with a stored API key, restore
  it, send one prompt, expect a reply (no `Unauthorized`). Fixture `07` is recorded
  against such a provider (Q1).
- **Gate:** `MIN_PORT_RELEASE_RESTORE` (one constant beside `MIN_PORT_RELEASE` in the
  kernel spec, compared with the `release=` value of the port's `VERSION` file the
  kernel probe already reads). Its value is the first port release that carries K1
  (`3.0.68-termux.2` if K1 lands before that candidate is built, otherwise the next
  `-termux.N`). Until such a release exists the constant is `null`.
- **Atomic path** (kernel release ≥ `MIN_PORT_RELEASE_RESTORE`): one `session.restore`
  (1.5.2). The kernel resets the workspace inside a transaction that rolls back if the
  new session fails to start, and retains the checkpoint refs for the new session.
- **Split path** (older kernel, or the constant is `null`): two commands (1.5.3), each
  of which takes a key-safe route. Not atomic: if step 2 fails after step 1 reset the
  workspace, the workspace stays reset (C.6 states it to the user).

The docs/02 minimum (`3.0.68-termux.2`) stays the minimum for the app as a whole; only
the restore path depends on the newer gate.

#### 1.5.2 Builders

Add to `HubPayloads` (pure builders, unit-tested against fixture `07`):

```kotlin
fun proceedWhileRunning(sessionId: String, toolCallId: String?)   // exists; envelope.sessionId = sessionId

/** Atomic path. Only when the kernel release is >= MIN_PORT_RELEASE_RESTORE (1.5.1). */
fun sessionRestore(
    sourceSessionId: String,
    checkpointRunCount: Int,
    restoreWorkspace: Boolean,
    omitCheckpointMessage: Boolean,          // true for edit-and-restart (C2, also from the C3 picker), false for revert (C1)
    spec: NewSessionSpec,                    // provider/model/mode/workspace of the source session
    metadata: JsonObject,                    // restoreMetadata(...) below
): JsonObject = buildJsonObject {
    put("sessionId", sourceSessionId)
    put("checkpointRunCount", checkpointRunCount)
    putJsonObject("restore") {
        put("messages", true)
        put("workspace", restoreWorkspace)
        if (omitCheckpointMessage) put("omitCheckpointMessageFromSession", true)
    }
    put("workspaceRoot", spec.workspaceRoot); put("cwd", spec.cwd)
    put("sessionConfig", sessionConfig(spec))  // no sessionId: the Hub mints a new one
    put("metadata", metadata)
    putJsonObject("toolPolicies") { putJsonObject("*") { put("autoApprove", spec.autoApprove) } }
    putJsonObject("runtimeOptions") {          // re-contribute ask_question, else the tool disappears
        put("clientContributions", askQuestionContributionArray())
    }
}

/** Shared by restore and create. Never contains apiKey: the daemon merges the stored key (feature matrix). */
fun sessionConfig(spec: NewSessionSpec): JsonObject = buildJsonObject {
    put("providerId", spec.providerId); put("modelId", spec.modelId)
    put("cwd", spec.cwd); put("workspaceRoot", spec.workspaceRoot)
    put("mode", spec.mode); put("enableTools", true)
    put("enableSpawnAgent", spec.enableSpawnAgent); put("enableAgentTeams", spec.enableAgentTeams)
    putJsonObject("checkpoint") { put("enabled", true) }
    spec.thinking?.let { put("thinking", it) }; spec.reasoningEffort?.let { put("reasoningEffort", it) }
}

/** Split path, step 1: workspace only. No sessionConfig needed (session-versioning-service.ts:286-290). */
fun sessionRestoreWorkspaceOnly(sourceSessionId: String, checkpointRunCount: Int): JsonObject =
    buildJsonObject {
        put("sessionId", sourceSessionId); put("checkpointRunCount", checkpointRunCount)
        putJsonObject("restore") { put("messages", false); put("workspace", true) }
    }   // reply {checkpoint}; without sessionConfig the kernel uses the source record's cwd/workspaceRoot

/** Split path step 2, and edit without a checkpoint: a plain create (apiKey stays undefined daemon-side). */
fun sessionCreateRestored(spec: NewSessionSpec, initialMessages: JsonArray, metadata: JsonObject): JsonObject

/**
 * Metadata for the new session.
 * C1 (revert): {source:"android", interactive:true, provider, model} only. The desktop's
 *   handleRestoreCheckpoint passes no sessionMetadata, so a reverted session is untitled ("New Session").
 * C2 (edit, all branches, also from the C3 picker): desktop forkMetadata = the source session's
 *   metadata minus the keys the kernel derives or rewrites (checkpoint, fork, restoredFromSessionId,
 *   restoredCheckpointRunCount, pid, messagesPath, usage, totalCost), then
 *   source:"android", interactive:true, provider, model, and
 *   fork = {forkedFromSessionId, forkedAt (ISO now), source: sourceSession.source ?: "android",
 *           beforeRunCount, checkpoints: sourceSession.metadata.checkpoint (when present)}.
 *   So the edited session keeps its title, pin and other user metadata (desktop parity).
 * Split path only (the Hub adds these itself on the atomic path, session-handlers.ts:683-688):
 *   restoredFromSessionId, restoredCheckpointRunCount, and
 *   checkpoint = the source history filtered to runCount <= N (port of createRestoredCheckpointMetadata:
 *   {latest, history}), so earlier undo icons keep working while the source session exists.
 */
fun restoreMetadata(kind: RestoreKind, source: SessionRecord, runCount: Int, splitPath: Boolean, nowIso: String): JsonObject

const val SESSION_RESTORE_TIMEOUT_MS = 120_000L   // envelope.timeoutMs: git reset/clean/stash apply on a large tree
const val SESSION_RESTORE_CLIENT_TIMEOUT_MS = 130_000L  // HubClient's own timer: must exceed the envelope value
```

The Hub honours `envelope.timeoutMs` exactly as sent (`resolveHubCommandTimeoutMs`
does not clamp), so the `HubClient` request timer for these commands must be longer
than the envelope value; otherwise the app gives up before the Hub's own
`hub_command_timeout` reply can arrive.

#### 1.5.3 Split path, step by step

1. If the user chose the workspace: `sessionRestoreWorkspaceOnly(source, N)` with
   `timeoutMs = SESSION_RESTORE_TIMEOUT_MS` → `ok {checkpoint}`. Errors as 1.5.4.
2. `session.messages {sessionId: source}` (already cached if younger than 10 s), then
   trim with the pure Kotlin ports in `CheckpointIndex`: C1 → `trimMessagesToCheckpoint`
   (messages up to and including that run's user message); C2 →
   `trimMessagesBeforeUserRun` (messages before it; throws the compacted-run error for a
   span ≠ 1, shown verbatim).
3. `sessionCreateRestored(spec, trimmed, restoreMetadata(..., splitPath = true))`.
4. If step 3 fails after step 1 succeeded, the error line reads "The workspace was
   restored, but the new session could not start: %1$s" *(Android)*; nothing is rolled
   back, and a retry (chat only is then the sensible choice) runs step 2–3 again.

Differences from the atomic path, accepted for older kernels: no rollback transaction,
no `retainCheckpointRefs` for the new session (its inherited history points at the
source's refs and stops working if the source session is deleted), two lost-reply
windows instead of one (1.6 matches both by the metadata the app set).

#### 1.5.4 Replies and errors

From `handleSessionRestore`, `SessionVersioningService` and `checkpoint-restore.ts`,
all shown verbatim as data except where mapped:

| Reply | Meaning | App handling |
| --- | --- | --- |
| `ok`, `{session, snapshot, messages, checkpoint}` | new session started; `session.sessionId` is new | C.6 success path |
| `ok`, `{checkpoint}` | split path step 1: workspace restored, no session | continue with step 2 (1.5.3) |
| `invalid_restore` "session.restore requires a session id" / "checkpointRunCount must be a positive integer" / "sessionConfig is required when restore.messages is true"; `restore_failed` "checkpointRunCount must be a positive integer" (non-integer number) | app bug | error under the message row = message; log `restore_invalid` (no payload contents) |
| `session_not_found` "Unknown session: <id>" | source gone (deleted elsewhere) | error = message; that message's actions disabled until the session list refreshes |
| `session_messages_not_found` "No messages found for session <id>" | empty history | error = message |
| `restore_failed` "No checkpoint found at or before run N in session <id>" | history changed | error = message; refresh the checkpoint index |
| `restore_failed` "Could not find user message for run N" | the app's run count does not map (run-span port bug or history changed) | error = message; refresh the index; log `restore_run_unmapped` |
| `restore_failed` "Run N is folded into a compacted message spanning runs A-B" / "Cannot fork before run N because it is folded into a compacted message" | auto-compaction folded that turn | error = message; that message's restore/edit actions are hidden from then on (C.4) |
| `restore_failed` "Cannot restore the workspace: N commits were added to the current branch after this checkpoint and would be removed by the restore. Restore the chat only, or move the branch back to <sha> manually (e.g. with git reset) before restoring the workspace." (and the variant "…the current branch is no longer on the commit this checkpoint was created from (e.g. after a rebase or branch switch)…") | commits, a rebase or a branch switch happened after the checkpoint; the kernel refuses rather than drop commits | **workspace refusal** (C.6): the dialog reopens with the kernel text, "Restore chat only" selected, the radio still editable |
| `restore_failed` "<cwd> is not a git repository" | workspace deleted or `.git` removed after the checkpoint | **workspace refusal** (C.6): the dialog reopens with the kernel text and only "Restore chat only" (the workspace option is hidden for this dialog's life) |
| `restore_failed` "cwd or workspaceRoot is required to restore a checkpoint" | the session record has no cwd (app sent none and the record lacks one) | error = message; log `restore_no_cwd` |
| `restore_failed` "Checkpoint restore did not start a session" | kernel failure | error = message |
| `hub_draining` (retryable) | Hub restarting | 1.6 |
| `hub_command_timeout` "Hub command session.restore did not complete within 120000ms. …" | the Hub stopped waiting but **did not cancel** the handler: the restore may still finish, reset the workspace and publish `session.created` | **lost reply** (1.6), exactly like `hub_connection_closed`; never re-enable the confirm for an automatic or one-tap retry |
| `hub_connection_closed` (client-side), or the client-side timer (`SESSION_RESTORE_CLIENT_TIMEOUT_MS`) | socket dropped or no reply | **lost reply** (1.6); **never** auto-resend |
| any other error | — | error = the kernel message, else the fallback of the flow (C.7) |

`run.proceed_while_running` replies (`run-handlers.ts:373-402`): `ok {detachedCount}`;
`detachedCount < 1` → "The command finished before it could be detached." (this is also
what a session with nothing registered returns: there is no `session_not_found` on
proceed); `unsupported_command` "This runtime does not support proceeding while
commands are running." (verbatim); `invalid_session_id` (app bug); `hub_connection_closed`
→ re-evaluate `canProceed` after reconnect (the button returns only if the command is
still running and detachable); any other error → "Could not detach the running command."
with the kernel message logged. Proceed is not drain-gated, so it never gets
`hub_draining`.

### 1.6 Recovery behaviour shared by the group (`docs/02-kernel-contract.md` matrix)

| Contract case | Where it surfaces here | Behaviour |
| --- | --- | --- |
| upgrade 401 → stale token → re-read `production.json` | any screen | handled by `HubClient`; screens show KernelStrip "Reconnecting..." meanwhile; no user action |
| ECONNREFUSED on 25463 → `cline hub start` | A, B, C | KernelStrip "Cline Hub @unknown" / "Cline Hub is not connected." + "Start kernel"; actions that need the Hub are disabled (listed per screen) |
| `/health` fails and Termux resolvable → Termux asleep | A, B, C | KernelStrip "Termux asleep" / "Start kernel to continue." |
| RUN_COMMAND `err != -1`, errmsg mentions allow-external-apps or permission | "Start kernel" tap | navigate to the Kernel setup screen (owned by the kernel spec) with that fix; this screen stays on the back stack |
| Termux not resolvable via `<queries>` | "Start kernel" tap | navigate to "Install Termux" |
| reply `hub_draining` | `session.restore` and `session.create` of C (the only drain-gated commands here) | keep the pending state and show the extra line "Cline Hub is restarting. Waiting..." *(Android)* under the message row; wait for `hub.drain_changed {draining:false}` up to 30 s, then send the command **once** more; on timeout show the flow's fallback error |
| `run.proceed_while_running` outcome | B "Proceed while running" | only two signals exist: `ok {detachedCount:0}` → hide the button, show "The command finished before it could be detached."; `hub_connection_closed` → re-evaluate `canProceed` after reconnect |
| `session.detach` on the source after a restore | C | best-effort: a `session_not_found` (source was not live) is ignored and never fails the flow |
| `run.interrupted {reason:"hub_restart"}` | B, chat | running tools become *Interrupted* (B.5); output freezes; chat offers resend (chat spec) |
| `approval.requested` while A or B is showing for that session | A, B | the approval sheet opens over the screen (DS §5.5); dismissing it collapses to the banner, a `Surface(warning.container)` row with `heightIn(min = 56.dp)`, "Tool approval required" + `TextButton` "Review". On A it sits above the bottom inset; on B it stacks directly **above the `BottomAppBar`** when that bar is shown, else above the bottom inset |
| `capability.requested` (ask_question) | A, B | same as approval with the question sheet |
| pending restore fails with `hub_connection_closed`, `hub_command_timeout`, the client timer, or the app process died while it was pending | restore (C) | **lost-reply recovery:** after (re)connect, `session.list {limit:20, rootOnly:true}`; if a session has `metadata.restoredFromSessionId == source` and `metadata.restoredCheckpointRunCount == runCount` (or, for an edit without a checkpoint, `metadata.fork.forkedFromSessionId == source` and `metadata.fork.forkedAt == the forkedAt the app sent`) and `createdAt ≥ requestSentAt − 5 s`, treat it as the success result (C.6). Otherwise show the "Restore status unknown" dialog: "The connection dropped before the restore finished. Check the session list before trying again." *(Android)* with buttons "Open sessions" and "Close". Never resend automatically and never re-enable a one-tap retry: restore is not idempotent and creates a session (and may reset the workspace) each time. The pending request (`source`, `runCount`, `forkedAt`, `requestSentAt`, kind) is kept in `SavedStateHandle` so this recovery also runs after process death |
| RUN_COMMAND never returns | C3 git probe | 10 s timeout → treat as "unknown" and show the generic checkpoint explanation |

---

## A. Changes screen ("Uncommitted changes")

### A.1 Purpose

Show every file Cline changed in this session, with per-file `+N -N`, expandable
hunks rendered as diffs, copy path and share. It is the desktop `DiffView`. Changes are
reconstructed from the agent's own `editor` / `apply_patch` calls (the desktop does
the same); edits made outside Cline are not shown, and the title keeps the desktop's
wording.

### A.2 Desktop equivalent

`webview/components/views/chat/diff-view.tsx` (`DiffView`, `DiffFileSection`,
`DiffHunk`) inside `ui/components/agent-changes.tsx` (`AgentChangesPanel`,
`AgentChangedFile`), fed by `webview/lib/session-diff.ts`, opened from the header diff
chip (`webview/components/agent-header.tsx`, `onOpenDiff`). Copied: title, badge,
empty text, header order, copy-path affordance and its error copy, all-expanded
default, per-hunk rendering with the fragment/complete rule, 30-hunk cap, path
canonicalisation, close on session change and when the summary drops to zero.
Translated: the desktop swaps the chat body for the diff view; Android pushes a route,
and at ≥ 1200dp shows it beside the chat so the conversation stays visible (DS-A1, an
Android extension); the hover-revealed copy button is always visible; the "Open in"
editor menu becomes a per-file overflow menu with "Copy file path" and "Share diff";
the close `X` becomes the back arrow (pushed) or a close button (pane); the file
header puts `+N -N` before the overflow button (M3 list rows end with the overflow).

### A.3 Navigation

| Aspect | Rule |
| --- | --- |
| Route | `chat/{sessionId}/changes` (pushed) on windows < 1200dp wide or < 480dp tall; `SupportingContent.Changes(sessionId)` in the chat's `NavigableSupportingPaneScaffold` (navigator `rememberSupportingPaneScaffoldNavigator()`) at ≥ 1200dp × ≥ 480dp (DS §5.2, DS-A1, DS-A9) |
| Entered from | the ConversationHeader diff chip (CD "Open diff: N additions, N deletions"); visible only when additions ≠ 0 (desktop, DS ConversationHeader); never opened at zero changes (desktop `handleOpenDiff`) |
| Deep link | none |
| Transition | DS §6 shared axis X (pushed); pane slide-in is the scaffold default |
| Back (pushed) | system back / top-left arrow (CD "Back") pops to the chat at its previous scroll position; predictive back uses the seekable pop transition (DS §5.8 item 9). Before that, back first closes an open menu (item 1) and then an open sheet (item 2) |
| Back (pane) | system back closes the supporting pane with a predictive close (`NavigableSupportingPaneScaffold` wires the navigator to back; DS-A9 inserts this as item 7a of the DS §5.8 order), the list pane returns to its previous state (DS-A1) and the chat keeps focus; the pane's close button (CD "Close diff view") does the same |
| Session change | if the chat switches session while the pane is open, the pane **closes** (desktop `setShowDiffView(false)` on switch, `page.tsx:2115, 2193`; same as Tool detail in B.3). A pushed Changes route stays bound to its `sessionId` argument (the drawer is not reachable from it on compact) |
| Summary drops to zero | on a **transition** from at least one file to none while the screen is showing (re-seed after a history refresh or a restore), the pane closes and a pushed Changes route pops (desktop `page.tsx:2471-2475` effect on `hasDiffChanges`). A screen that is (re)created and seeds to zero (stale chip count, process-death restore) shows the Empty state instead of vanishing |
| Session deleted | if `session.delete` succeeds for this session (from anywhere), pop the route / close the pane and show the snackbar the delete flow already shows |
| Process death | the route argument `sessionId` restores the screen; the store re-seeds from `session.messages` |

### A.4 Layout

Components (all from DS §4 unless noted):

- **App bar.** Pushed: `PageScaffold(appBar = PageAppBar.Medium)` (DS-A10:
  `MediumTopAppBar`, collapsing, back arrow CD "Back"). Title slot (composable) =
  `Row { Text("Uncommitted changes", maxLines = 1, overflow = Ellipsis); Spacer(8dp); FilesBadge }`.
  `FilesBadge` = `Surface(shape = extraSmall, color = surfaceContainerHighest)` with
  "Files: N" in `ClineMono.small`, `onSurfaceVariant` (DS "Files: N" badge). Actions:
  `IconButton` expand/collapse all (`unfold_less` "Collapse all" when any file is
  expanded, else `unfold_more` "Expand all") and `IconButton` `share` "Share all
  changes". Both hidden in the empty and loading states. Pane: small `TopAppBar`
  (64dp, `surfaceContainerLow`), same title and actions, `navigationIcon` absent,
  trailing extra action `close` "Close diff view".
- **KernelStrip** (DS-A2) as the first list item when the kernel is not *connected*.
- **Summary line** *(Android)*: first list item under the strip, `heightIn(min = 40.dp)`,
  16/24dp gutter, wraps at large font scales:
  "`+120 -34` across 7 files" — counts in `ClineMono.small` `success.color` / `error`,
  the rest `bodyMedium` `onSurfaceVariant`. Uses plurals ("across 1 file"). Hidden when
  empty.
- **Truncation notice** (desktop `notice` slot, Android copy): shown under the summary
  when any file has `hunksTruncated`: `info` icon 20dp + "Only the last 30 edits to a
  file are shown." `bodySmall` `onSurfaceVariant`.
- **ChangedFileHeader** (DS #ChangedFileHeader), one per file, `heightIn(min = 48.dp)`,
  full-width clickable toggle (role Button), background `surfaceContainerLow`, in this
  order: `expand_more` 20dp rotated −90° collapsed (+90° under RTL, DS-A7) / 0° expanded
  (DS §6 chevron) · path `ClineMono.small` `onSurface`, single line, **middle ellipsis**
  (keep the file name: render `dir/…/name.kt` by trimming whole leading segments until
  it fits, never cut the basename) · copy `IconButton` (`content_copy` 16dp glyph, 48dp
  target; `check` in `primary` for **1600 ms** after a copy, desktop `diff-view.tsx:124-127`)
  · flexible spacer (part of the toggle) · `+N` `ClineMono.small` `success.color` · 4dp ·
  `-N` `ClineMono.small` `error` · overflow `IconButton` `more_vert` CD "File actions for
  <path>" as the **last** trailing element (M3 list-row convention, DS #11; the desktop
  puts its host actions before the counts). Counts always show both, also `+0`/`-0`
  (desktop).
- **File body** (expanded): the desktop's `bg-card/40` body becomes plain `surface`
  (no translucent fills, DS §1.4), with 12dp vertical padding and 16dp (compact) /
  24dp (medium+) horizontal padding, then the hunks with 8dp between them.
  A 1dp `outlineVariant` divider separates files (decorative).
- **DiffHunkView** (DS #ToolFileDiff/DiffHunk) per hunk: `Surface(shape = small,
  color = surfaceContainer)` clipping its content; inside, a `horizontalScroll` row
  of: gutter (only when `isCompleteNewContents`: new line numbers, `ClineMono.tiny`,
  `onSurfaceVariant`, right-aligned, width = digits × char width + 12dp) · marker
  column 16dp (`+` `success.color`, `-` `error`, space for context) · line text
  `ClineMono.body` `onSurface`, no wrap. Line backgrounds `ClineColors.diffAddedLine` /
  `diffRemovedLine`, context lines none. The marker plus the background carry the
  meaning; colour is never alone (DS §8.1 #3).
- **Line content of a hunk:** run `LineDiff.myers(old.lines, new.lines)` (pure Kotlin,
  ≤ 2,000 × 2,000 lines; above that fall back to "all old lines removed, then all new
  lines added") and render equal lines as context, deletions before insertions within a
  change block (unified style, as `@pierre/diffs` renders). A `null`/identical result
  (old == new) renders nothing, like `ToolFileDiff`. Trailing newline: normalise as
  `ensureTrailingNewline` (no "No newline at end of file" marker).
- **Long hunks:** render at most 400 lines per hunk at first, then a `TextButton` "Show
  %d more lines" *(Android)* that adds 400 at a time. This keeps each hunk a single
  lazy item with its own horizontal scroll.
- **Files with no hunks** (e.g. apply_patch Delete File): body text "No hunk details
  available." `bodySmall` `onSurfaceVariant` (desktop).
- The whole screen is one `LazyColumn` (items: strip, summary, notice, then per file a
  header item and, when expanded, one item per hunk). `contentPadding` = scaffold
  `innerPadding` + `bottomSafe`. Max content width 840dp centred on wide windows.

**Compact phone (S25U portrait, ~412dp):**

```
┌──────────────────────────────────────────┐
│ ←                                        │  MediumTopAppBar (collapses on scroll)
│ Uncommitted changes [Files: 3]   ⇲  ⤴    │  ⇲ Collapse all  ⤴ Share all changes
├──────────────────────────────────────────┤
│ +42 -7 across 3 files                    │  summary (Android)
│ ▾ app/src/…/Main.kt        ⧉  +30 -5  ⋮  │  ChangedFileHeader, 48dp min
│ ┌──────────────────────────────────────┐ │
│ │ - val x = 1                          │ │  DiffHunkView (fragment: no gutter)
│ │ + val x = 2                          │ │  horizontal scroll per hunk
│ │ + val y = 3                          │ │
│ └──────────────────────────────────────┘ │
│ ┌──────────────────────────────────────┐ │
│ │   fun main() {                       │ │  second hunk of the same file
│ │ -   println(x)                       │ │
│ │ +   println(x + y)                   │ │
│ └──────────────────────────────────────┘ │
│ ▾ hello.py                 ⧉  +12 -0  ⋮  │
│ ┌──────────────────────────────────────┐ │
│ │ 1 + print("hello")                   │ │  complete new contents: gutter shown
│ │ 2 + print("hi")                      │ │
│ └──────────────────────────────────────┘ │
│ ▸ README.md                ⧉   +0 -2  ⋮  │  collapsed by the user
└──────────────────────────────────────────┘
```

**Medium (Tab S7+ portrait ~778dp, S25U split screen):** same single column, gutters
24dp, max width 840dp.

**Which layout applies.** The height rule wins over the width rule:

| Window | Layout |
| --- | --- |
| height < 480dp (S25U landscape ≈ 891 × 412dp, any phone landscape) | **height-compact**: pushed route, small pinned `TopAppBar`, compact accordion, no two-pane |
| width < 840dp, height ≥ 480dp (S25U portrait, Tab S7+ portrait, split screen) | pushed route, compact accordion |
| width ≥ 840dp and height ≥ 480dp, pushed (DeX windows 840–1199dp; Tab S7+ landscape after "Open full screen") | pushed route, **two-pane** |
| width ≥ 1200dp and height ≥ 480dp, from the chip (Tab S7+ landscape ≈ 1244 × 778dp) | **supporting pane** 400dp (DS-A1) |

**Expanded, pushed (width ≥ 840dp and height ≥ 480dp):** two panes inside the route
(`ListDetailPaneScaffold` with its own navigator, list 320dp):

```
┌───────────────────────────┬─────────────────────────────────────────────┐
│ ← Uncommitted changes [Files: 3]                               ⇲   ⤴    │
├───────────────────────────┼─────────────────────────────────────────────┤
│ +42 -7 across 3 files     │ app/src/main/kotlin/…/Main.kt  ⧉  +30 -5 ⋮  │
│ ┃app/…/Main.kt     +30 -5 │ ┌─────────────────────────────────────────┐ │
│  hello.py          +12 -0 │ │ - val x = 1                             │ │
│  README.md          +0 -2 │ │ + val x = 2                             │ │
│                           │ └─────────────────────────────────────────┘ │
│                           │ ┌─────────────────────────────────────────┐ │
│                           │ │   fun main() { …                        │ │
└───────────────────────────┴─────────────────────────────────────────────┘
```

List rows are 56dp `ListItem`s (headline = basename `bodyLarge`, supporting = parent
directory `ClineMono.small`, trailing `+N -N`), selected row `secondaryContainer`. The
detail pane shows the selected file's header (no chevron) and all its hunks.
"Collapse all"/"Expand all" is hidden in this layout. First file selected on open.
Back inside the route: detail → list is not a step here (both panes visible), so back
pops the route.

**≥ 1200dp × ≥ 480dp (Tab S7+ landscape ≈ 1244dp): supporting pane, 400dp wide.** The
Chats list pane collapses while the pane is open and comes back when it closes (DS-A1):

```
┌ rail 80 ┬────────────── chat (detail pane) ≈ 764dp ──────────────┬──── supporting 400dp ─────┐
│  ☰      │ ☰  Title  ● +42 -7  ⋮                                  │ Uncommitted changes [3] ✕ │
│  ✎      │ …transcript…                                           │ ▾ app/…/Main.kt ⧉ +30 -5 ⋮│
│  ◷      │                                                        │ ┌──────────────────────┐  │
│  ⚙      │                                                        │ │ - val x = 1          │  │
│         │ [ composer: + · Plan|Act · model · effort · ◔ · ➤ ]    │ └──────────────────────┘  │
└─────────┴────────────────────────────────────────────────────────┴───────────────────────────┘
```

Opening the list pane from the rail `menu` while the supporting pane is open closes the
supporting pane first (the chat never drops below ≈ 764dp). The pane uses the compact
accordion layout. Its title row is the small `TopAppBar`
(64dp) described above with the extra action `open_in_full` "Open full screen"
*(Android)*, which pushes `chat/{sessionId}/changes` (the two-pane layout).

**Height-compact (height < 480dp, e.g. S25U landscape 891 × 412dp):** the pushed screen
uses `PageScaffold(appBar = PageAppBar.SmallPinned)` (64dp, DS-A10), the compact
accordion and 24dp gutters; no two-pane, no supporting pane.

### A.5 States

| State | Trigger | What shows |
| --- | --- | --- |
| First run | session never had `editor`/`apply_patch` results | identical to Empty; no coach marks |
| Loading | `DiffStoreState.Loading` (history seed in flight) | app bar without badge/actions; `LinearProgressIndicator` under the app bar (DS: detail panes use a linear indicator); body text "Loading changes..." *(Android)* centred `bodyMedium` `onSurfaceVariant` after 300 ms (avoid a flash) |
| Empty | `Ready` with 0 files on the first seed of this screen instance (A.3 "Summary drops to zero") | EmptyState (DS #EmptyState): icon `difference` 48dp, title "No file changes in this session yet." (desktop), body "File edits Cline makes in this session appear here." *(Android)*; badge "Files: 0"; actions hidden |
| Populated | `Ready` with files | as A.4; all files expanded on open (desktop); user toggles live as long as this screen instance (`SavedStateHandle`, so they survive rotation and process death) and reset when the screen or pane closes (desktop `collapsedFiles` is component state) |
| Running / streaming | `snapshot.status` running or pending | populated or empty layout as above; new files are appended at the end in first-seen order (desktop `Map` insertion order); a file whose counts changed gets a 600 ms `secondaryContainer` → transparent highlight on its header (skipped under reduced motion); TalkBack polite announcement "Changes updated: %d files" *(Android)* at most once per 5 s |
| Error | `Failed` (history `session.messages` error) | EmptyState icon `error` in `error`, title "Could not load changes." *(Android)*, body = kernel message (`bodySmall`), `FilledTonalButton` "Retry" (re-seeds) |
| Offline / kernel down, data cached | kernel not connected, store `Ready` | KernelStrip on top, content still readable, copy and share work (local data) |
| Offline / kernel down, nothing cached | kernel not connected, store `Loading` never completed | KernelStrip on top, EmptyState icon `cloud_off`, title "Cline Hub is not connected." (DS §1.6 copy), body "Start kernel to continue." (DS §1.6), button "Start kernel" |
| Permission missing | "Start kernel" tapped and RUN_COMMAND permission not granted | navigate to Kernel setup (1.6); nothing changes here |
| Session deleted | `session.delete` for this id | pop / close (A.3) |

### A.6 Copy

| Element | String | Source |
| --- | --- | --- |
| Title | "Uncommitted changes" | desktop |
| Badge | "Files: %1$d" | desktop |
| Empty | "No file changes in this session yet." | desktop |
| Empty body | "File edits Cline makes in this session appear here." | Android |
| No hunks | "No hunk details available." | desktop |
| Summary | "%1$s across %2$d file" / "%1$s across %2$d files" (plurals; %1$s = the coloured "+N -N" span) | Android |
| Truncation notice | "Only the last 30 edits to a file are shown." | Android |
| Loading | "Loading changes..." | Android |
| Load error title | "Could not load changes." | Android |
| Retry | "Retry" | Android |
| Copy path CD | "Copy file path for %1$s" | desktop |
| Copy path tooltip | "Copy file path" | desktop |
| Copied CD (1600 ms) | "Copied file path for %1$s" | Android |
| Clipboard label (`ClipDescription`, shown by some clipboard UIs) | "File path" | Android |
| Copied snackbar (API < 33 only; API 33+ shows the system clipboard confirmation) | "File path copied" | Android |
| Copy failed snackbar | "Copy failed. The file path could not be copied to the clipboard." | desktop title + description |
| File overflow CD | "File actions for %1$s" | Android |
| File menu | "Copy file path", "Share diff" | desktop title / Android |
| Expand / collapse all | "Expand all" / "Collapse all" | Android |
| Share all | "Share all changes" | Android |
| Share chooser title | "Share changes" | Android |
| Share subject | "Changes in %1$s" (session title, fallback "New Session") | Android |
| Show more | "Show %1$d more lines" | Android |
| Pane close CD | "Close diff view" | desktop |
| Open full screen | "Open full screen" | Android |
| Header TalkBack | "%1$s, %2$d additions, %3$d deletions, %4$s" (path, counts, "Expanded"/"Collapsed") | Android |
| Live announcement | "Changes updated: %1$d files" (plurals) | Android |
| Kernel strip | "Cline Hub @%1$s", "Cline Hub is not connected.", "Termux asleep", "Start kernel to continue.", "Connecting...", "Reconnecting...", "Start kernel" | DS §1.6 |

### A.7 Interactions and gestures

| Gesture | Target | Result |
| --- | --- | --- |
| Tap | file header (anywhere except the buttons) | toggle expand/collapse; chevron animates (DS §6); state saved |
| Tap | copy button | write `SessionDiff.resolveAbsolutePath(path, cwd)` (absolute Termux path, as the desktop copies the resolved path) to the clipboard as plain text with `ClipDescription` label `R.string.changes_clip_label` ("File path"); icon → `check` for 1600 ms; on `SecurityException`/failure show the "Copy failed..." snackbar |
| Tap | overflow → "Copy file path" | same as the copy button |
| Tap | overflow → "Share diff" | build `UnifiedDiffText.forFile(file)` and open `Intent.createChooser(ACTION_SEND type "text/plain", EXTRA_TEXT, EXTRA_SUBJECT "Changes in <title>")`, title "Share changes". Above 100,000 characters write `cacheDir/share/<sessionId>-<n>.diff` and share it through the app's `FileProvider` (`content://dev.cline.termux.fileprovider/share/...`, type `text/x-diff`, `FLAG_GRANT_READ_URI_PERMISSION`); files under `cache/share` older than 24 h are deleted on app start |
| Tap | "Share all changes" | `UnifiedDiffText.forSession(files)` with the same rules |
| Tap | "Expand all" / "Collapse all" | sets every file's state; the icon flips |
| Tap | "Show N more lines" | appends 400 lines to that hunk |
| Long-press | file header | the same overflow menu anchored at the touch point (DS #15), plus "Expand"/"Collapse" as the first item |
| Long-press | diff line text | text selection inside the hunk's `SelectionContainer` (DS §8.1 #11); selection never includes the gutter or marker (render them outside the `SelectionContainer`) |
| Horizontal drag | hunk | scrolls that hunk only; vertical drags pass to the list |
| Tap | list row (two-pane) | selects that file in the detail pane |
| Hardware keyboard | Up/Down in two-pane list; Enter toggles a focused header; Ctrl+C copies the focused file's path | |

`UnifiedDiffText` format, per file (pure, unit-tested):

```
--- a/<display path>
+++ b/<display path>
@@ -<oldStart>,<oldCount> +<newStart>,<newCount> @@
-<old line>
+<new line>
```

Counts come from the `LineDiff` result of that hunk, context lines are prefixed with a
space, fragments still get headers with the stored starts. Files without hunks emit
the two header lines and `# No hunk details available.`

### A.8 Data binding and ViewModel

```kotlin
@Immutable data class ChangesUiState(
    val sessionId: String,
    val sessionTitle: String,                 // for the share subject
    val kernel: KernelConnectionVisual,       // DS §1.6 row
    val content: Content,
    val isSessionBusy: Boolean,               // snapshot.status running|pending
    val expanded: Set<String>,                // display paths; default = all
    val copiedPath: String?,                  // clears after 1600 ms (desktop)
    val selectedPath: String?,                // two-pane only
    val shownLinesPerHunk: Map<HunkKey, Int>, // default 400
) {
    sealed interface Content {
        data object Loading : Content
        data class Ready(val files: List<SessionFileDiff>, val summary: SessionDiffSummary,
                         val changedPaths: Set<String>, val anyTruncated: Boolean) : Content
        data class Failed(val message: String) : Content
        data object Unavailable : Content    // kernel down and nothing cached
    }
}
data class HunkKey(val path: String, val index: Int)   // desktop key: path + index (hunks never reorder)
```

`ChangesViewModel(sessionId)` collects `SessionDiffStore.state(sessionId)`, the
session record flow (title, status, cwd) and `KernelCoordinator.connection`. Events:
`onToggle(path)`, `onExpandAll()`, `onCollapseAll()`, `onCopyPath(path)`,
`onShareFile(path)`, `onShareAll()`, `onShowMore(key)`, `onSelect(path)`,
`onRetry()`, `onStartKernel()`. No Hub command is sent by this screen except the
re-seed `session.messages` on Retry. `expanded`, `selectedPath` and
`shownLinesPerHunk` go into `SavedStateHandle`.

### A.9 Edge cases

- **Same file, two spellings** (`journal.txt` vs `/…/ws/journal.txt`): one entry, counts
  summed, display = first spelling (ported `canonicalizeDiffPath`).
- **Paths outside the workspace** display as absolute paths.
- **More than 30 edits to one file:** older hunks dropped, notice shown, counts still
  summed over all edits (desktop behaviour: counts accumulate, hunks are capped).
- **Failed edits** (`toolError` set or `output.success == false`) never count.
- **Rejected tool calls** carry `error` → ignored.
- **Session restored** (C1/C2): the chat moves to the new session id, which is a session
  change, so the pane closes (A.3; desktop closes the diff view on switch). The new
  session usually has no edits, so the chip is hidden until it makes some. The old
  session's changes are still reachable from its own chat.
- **Very long single line** (minified JS, 200 KB): the line is clipped to 4,000
  characters with a trailing "…" in the hunk view (the share text keeps it whole).
- **Binary-looking content** (NUL bytes): replace control characters except `\t` with
  `U+FFFD` for display only.
- **RTL locale:** the whole diff region is forced LTR (DS §8.2 #6); the app bar
  mirrors.
- **Font scale 2.0 × size 20:** header wraps the counts below the path (the `Row`
  becomes a `FlowRow` when the path would get less than 96dp).

### A.10 Accessibility

- File header: one merged node, `role = Button`, `stateDescription`
  "Expanded"/"Collapsed", `contentDescription` = the "Header TalkBack" string; custom
  actions "Copy file path", "Share diff", "Expand"/"Collapse" (DS §8.1 #6). The copy
  and overflow buttons stay separately focusable for Switch Access.
- Diff lines: each line is a node with `contentDescription` "Added line %1$d: %2$s",
  "Removed line: %1$s", "Unchanged line: %1$s" *(Android)* (line number only when the
  gutter is shown); a hunk is a `semantics(isTraversalGroup = true)` container with
  heading-less label "Change %1$d of %2$d in %3$s" *(Android)*.
- Counts never rely on colour: `+`/`-` glyphs are always present.
- The summary line is a heading (`semantics { heading() }`) so TalkBack users can jump
  to the file list; each file header is also a heading.
- Focus: opening the screen focuses the title; closing the pane returns focus to the
  diff chip.

### A.11 Acceptance checklist (S25U)

Setup in Termux once: `pkg install git`, then
`mkdir -p ~/ws/demo && cd ~/ws/demo && git init && printf 'a\nb\nc\n' > notes.txt && git add -A && git commit -m init`.
Start a session in `~/ws/demo`.

- [ ] Prompt "Create hello.py that prints hello, then change it to print hi, then delete
      line b from notes.txt". The header chip shows `+N -N`; tapping it opens
      "Uncommitted changes" with "Files: 2".
- [ ] `hello.py` shows one hunk with line numbers (complete new contents) and one hunk
      without line numbers (fragment). `notes.txt` shows `-b` only, header `+0 -1`.
- [ ] Both files are expanded on open; collapsing one survives rotation and process
      death (Developer options > Don't keep activities, reopen).
- [ ] Copy on a file header, then long-press the copy icon: the tooltip says "Copy file
      path"; the copy icon sits before `+N -N` and the overflow `⋮` is the last element.
- [ ] Copy on `hello.py` puts `/data/data/com.termux/files/home/ws/demo/hello.py` on the
      clipboard; the icon shows a check for about 1.6 s; Android's clipboard confirmation appears
      and no extra snackbar.
- [ ] "Share diff" opens the share sheet; pasting into a notes app gives a valid unified
      diff (`--- a/hello.py`, `+++ b/hello.py`, `@@ … @@`).
- [ ] Prompt a 2,000-line file creation: the hunk shows 400 lines and "Show 1600 more
      lines"; scrolling stays smooth (no dropped frames visible in GPU profiling bars).
- [ ] While the agent is editing, a file appears/updates live and its header flashes
      once; with "Remove animations" on, no flash.
- [ ] Stop the Hub (`cline hub stop` in Termux): the KernelStrip appears with "Cline Hub
      is not connected." and "Start kernel"; content stays readable; Start kernel
      restores the strip to hidden.
- [ ] Fresh app start with the Hub stopped, then open Changes from a cached session:
      the "Cline Hub is not connected." empty state shows.
- [ ] Light and dark themes, dynamic colour on and off: added lines green with `+`,
      removed red with `-`, Accessibility Scanner reports no contrast or target issues.
- [ ] TalkBack reads "app/…/Main.kt, 30 additions, 5 deletions, Expanded" and offers the
      custom actions; swiping into a hunk reads "Added line: …".
- [ ] Tab S7+ landscape: Changes opens as a 400dp supporting pane next to the chat and
      the Chats list pane collapses; predictive back previews and closes the pane and the
      list pane comes back; the chat stays on screen throughout; "Open full screen"
      pushes the two-pane layout.
- [ ] S25U landscape (891 × 412dp): small pinned `TopAppBar`, single-column accordion, no
      two-pane. DeX window ≥ 840dp wide and ≥ 480dp tall, or Tab S7+ landscape full
      screen: two-pane.
- [ ] Switch session from the drawer with the Tab S7+ pane open: the pane closes.
- [ ] Collapse a file, close Changes, reopen it: every file is expanded again (desktop).
- [ ] RTL pseudo-locale (`ar-XB`): diff text stays LTR.

---

## B. Tool detail screen

### B.1 Purpose

A full-screen view of one tool call: the complete command output with ANSI colours
(larger buffer than the inline row, wrap toggle, stream filter, follow-tail), every
file diff hunk of an edit, all details, errors, images and the input, plus "Proceed
while running" and "Stop agent" while the command runs. It is the desktop's expanded
`ToolCallRow` given a whole screen, because the inline body is capped at 256dp on a
phone.

### B.2 Desktop equivalent

`webview/components/views/chat/messages/tool-message-block.tsx` (`ToolCallRow`
expanded body: `ToolActivityDetails` with `$ <command>` lines, `ToolFileDiff` per
hunk, `AgentCommandOutput` + `ansi-to-react`, `submit_and_exit` markdown, output text
block, images carousel, other media, debug "Input", error text, "Proceed while
running"), `ui/components/agent-command-output.tsx`, `webview/lib/command-output.ts`,
`webview/hooks/use-chat-session.ts` `proceedWhileRunning`. Copied: section order,
labels, the running gate, the proceed rule and errors, the output source precedence,
auto-scroll threshold 24dp, cursor block, wrapped output by default. Translated: the
inline disclosure becomes a pushed page; hover tooltips become long-press tooltips;
the debug-only "Input" stays debug-only in the body and is always available as "Copy
input" in the overflow. **Deliberate deviation:** the desktop renders output through
`ansi-to-react` (SGR colours only) in a `<pre>` with no cursor emulation, so a progress
bar shows as repeated lines and copy returns the raw text. The Android terminal emulates
`\r`, `\b` and line clears (B.4 parser table), so a progress bar is one updating line and
"Copy output" returns the emulated final text of each line (ANSI stripped).

### B.3 Navigation

| Aspect | Rule |
| --- | --- |
| Route | `chat/{sessionId}/tool/{toolCallId}` pushed on windows < 1200dp wide or < 480dp tall; `SupportingContent.Tool(sessionId, toolCallId)` in the chat's `NavigableSupportingPaneScaffold` at ≥ 1200dp × ≥ 480dp (DS-A1, DS-A9) |
| Entered from | (1) "View details" `TextButton` at the end of an expanded tool row body (DS-A5); (2) the tool row's long-press menu "View details"; (3) the TalkBack custom action "View details" on the row; (4) inside a folded "Worked for…" block, the same row entries |
| Not entered from | rows without a `toolCallId` (none should exist after the reducer guard) |
| Back | pops to the chat with the originating row still expanded and scrolled into view; predictive back per DS §5.8; menus and sheets first |
| Pane | close `IconButton` `close` CD "Close tool details" *(Android)*; system back closes the pane with a predictive close through the `NavigableSupportingPaneScaffold` navigator (DS-A9, item 7a of DS §5.8); the list pane returns (DS-A1) |
| Session change | pane: closes (a tool belongs to one session). Pushed: stays bound to its arguments |
| Session restored | if C1/C2 replaces the session, a pushed Tool detail of the old session stays valid (history is read-only); "Proceed while running" and "Stop agent" are hidden because the session is not live |
| Tool not found | `toolCallId` absent from both the live store and `session.messages` → "Not found" state (B.5) |
| Process death | arguments restore the screen; a running tool's output is rebuilt from the `stream.subscribe` replay (`sinceSequence` cursor) **as far as the durable log still holds it**: the log keeps 7 days, 200,000 rows and 64 MB in total, shared with every other session's `assistant.delta`/`tool.updated` traffic, and prunes every 16 MB appended (`hub-event-log.ts:21-25`). A multi-megabyte stream can therefore push the start of the output below the pruned horizon. The app detects a gap when the first replayed `tool.updated` for an `executionId` is not the empty announce chunk (the kernel always sends that one first). Sequence numbers are global across sessions, so a jump in `sequence` alone is **not** a gap signal. On a gap the app renders the "[Earlier command output truncated]" notice row at the top of that execution and marks the buffer as gapped. After `tool.finished` a gapped buffer is replaced by `output[commandIndex].result` (1.4) |

### B.4 Layout

Three body layouts, chosen from `ToolSummary.kind` (port of `buildToolSummary`):

1. **Command layout** (`kind == command`): the terminal fills the screen.
2. **Edit layout** (any `file` items with `oldText/newText` or `hunks`): a list of hunks
   like Changes, grouped by file, for **this call only**.
3. **Generic layout** (everything else, including `submit_and_exit`, read, search, web,
   MCP, spawn, team, skill, question, other): sections in a `LazyColumn`.

Common chrome:

- **App bar** (DS-A10 `PageScaffold` with a composable title slot):
  - **Command layout:** `PageAppBar.SmallPinned` (64dp `TopAppBar`, pinned, never
    collapses). The terminal's internal `LazyColumn` must not drive an app-bar collapse:
    a collapsing bar would change the terminal's height while it scrolls and break
    follow-tail and the remembered scroll offset. Same reasoning as the chat header in
    DS §5.3 ("the transcript scrolls under a stable header").
  - **Edit and Generic layouts:** `PageAppBar.Medium` (`MediumTopAppBar`, 112dp expanded,
    collapses with the body `LazyColumn`).
  - **Title, all layouts: one line, ellipsized** (the medium bar's expanded title row is
    112 − 64 = 48dp, which holds one `headlineSmall` line). The composable title is a
    `Row`: a leading 24dp slot with the kind icon (DS §7.2), or `error` (fill 1,
    `error`), or a 16dp `CircularProgressIndicator` while running, then the tool label
    from `buildToolSummary` (`labelParts`; code parts in `ClineMono.label`).
  - **Full label row:** in the Edit and Generic layouts the full, unclipped label is the
    first body row (`bodyLarge`, code parts `ClineMono.body`, wraps, selectable). The
    command layout needs none: its "Command" section shows the full `$ <command>`, from
    the call input (trial row 18, B.9).
  - Actions: `content_copy` "Copy output" (command and generic layouts with output),
    `more_vert` "More options" with "Copy command" (command), "Copy input", "Share
    output", a checkable "Wrap lines" (command layout), "View all changes" *(edit
    layout; opens A)*.
- **Status line** (header row, `heightIn(min = 40.dp)`, wraps at large font scales,
  `bodyMedium` `onSurfaceVariant`): status label + " · " + duration
  (`formatWorkDuration`, clamped to at least 1 s, so a sub-second tool shows "1s"),
  e.g. "Running · 12s", "Completed · 3s", "Failed · 1s". Then the raw tool name in
  `ClineMono.small` (`run_commands`) end-aligned. Duration = `endedAt − startedAt` from
  `run.completed.result.toolCalls[]` when known, else the envelope timestamps of
  `tool.started`/`tool.finished`, else (history) omitted. While running it ticks every
  second.
- **KernelStrip** (DS-A2) under the status line when not connected.
- **Bottom action bar** (only while `canProceed` or the run can be aborted): an M3
  `BottomAppBar` (80dp, handles the navigation-bar inset itself) in the Scaffold's
  `bottomBar` slot, i.e. **docked**: the content ends above it. Content, 16dp gutters:
  `TextButton` "Stop agent" (content `error`, DS §4.3) at the **start** · spacer ·
  `OutlinedButton` "Proceed while running" at the **end** (only when `canProceed`; 18dp
  spinner at the start of the button while pending). The confirming action takes M3's
  end slot and the un-confirmed destructive abort stays away from the bottom-end thumb
  corner (DS §3.4). When present, the proceed error shows in `bodySmall` `error` as a
  line directly above the bar (inside the content, above the terminal's bottom edge).
  The collapsed approval banner (1.6) stacks above the `BottomAppBar`.
- **Insets of the terminal:** the terminal is drawn to the window's bottom edge when no
  `BottomAppBar` is shown, with `bottomSafe` applied as its inner `LazyColumn`'s
  bottom `contentPadding` (same pattern as the transcript), so no light `surface`
  strip appears under the dark panel on gesture navigation. With the bar shown, the
  terminal ends at the bar.

**Which layout applies** (height rule first, as in A.4): height < 480dp →
height-compact; width ≥ 840dp and height ≥ 480dp, pushed → expanded two-column; ≥ 1200dp
× ≥ 480dp from the chat → supporting pane; otherwise compact (24dp gutters on medium).

**Command layout, compact:**

```
┌──────────────────────────────────────────┐
│ ← ⟳ Ran command  bun test        ⧉   ⋮   │  small pinned TopAppBar, 64dp, 1 line
├──────────────────────────────────────────┤
│ Running · 12s                run_commands│  status line
│ [ $ bun test ][ $ bun lint ]             │  PrimaryScrollableTabRow (only if >1 command)
│ COMMAND                                  │  overline labelSmall
│ $ bun test                               │  ClineMono.body, selectable, ≤160dp then scrolls
│ OUTPUT          [All|stdout|stderr]      │  overline + segmented filter (only if stderr)
│┌────────────────────────────────────────┐│
││ bun test v1.4.2                        ││  terminal: #0E0E12, ANSI colours
││ ✓ parser > parses SGR [2.1ms]          ││  fills remaining height
││ ✗ diff > counts lines                  ││
││ ...                                    ││
││ 12 pass  1 fail█                       ││  █ cursor while running
│└────────────────────────────────────────┘│
│                                     (↓)  │  SmallFAB "Scroll to latest output"
├──────────────────────────────────────────┤
│ Stop agent      [ ⟳ Proceed while running ]│  BottomAppBar 80dp (running only)
└──────────────────────────────────────────┘
```

**Command layout, height-compact (height < 480dp, e.g. S25U landscape 891 × 412dp):**
everything except the terminal is folded so the terminal keeps ≈ 270dp:

- Small pinned `TopAppBar` (64dp) with the one-line title; its actions are, in order,
  `TextButton` "Stop agent" (`error`, while abortable), `TextButton` "Proceed while
  running" (while `canProceed`; 18dp spinner while pending), `content_copy`, `more_vert`.
  No `BottomAppBar` in this layout.
- The status text (`labelMedium` `onSurfaceVariant`, e.g. "Running · 12s") sits under
  the title in the app bar, and one row, `heightIn(min = 48.dp)`, holds the command
  picker as a `PrimaryScrollableTabRow` (`edgePadding = 0`) only when there is more than
  one command. *(Amended 2026-10-06: the S25U is 384dp tall at its display size, not
  412dp, and a separate 48dp status row left 9 terminal lines; with the status in the bar
  a single command shows 12.)* Above font scale 1.3 the two bar lines no longer fit
  64dp, so the status returns to the start of that row (`labelLarge`).
- The "Command" overline and line are dropped (the title and tab labels carry
  `$ <query>`; "Copy command" stays in the overflow). The "Output" overline is dropped;
  the stream filter moves into the overflow as three checkable items "All", "stdout",
  "stderr" under the heading "Show" *(Android)*.
- The terminal takes the rest of the height and draws to the bottom edge with
  `bottomSafe` as inner padding. The proceed error shows as a snackbar in this layout.

```
┌───────────────────────────────────────────────────────────────────────────────┐
│ ← ⟳ Ran command bun test          Stop agent   Proceed while running   ⧉   ⋮  │
│ Running · 12s   [ $ bun test ][ $ bun lint ]                                  │
│┌─────────────────────────────────────────────────────────────────────────────┐│
││ bun test v1.4.2                                                             ││
││ ...                                                                         ││
││ 12 pass  1 fail█                                                       (↓)  ││
│└─────────────────────────────────────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────────────────────────────┘
```

**Command layout, expanded pushed (width ≥ 840dp and height ≥ 480dp: DeX windows, Tab
S7+ landscape when pushed full screen):** a 320dp left column holds status, the command
list (one 56dp `ListItem` per command: headline `$ <query>` mono 1 line, supporting
"Completed"/"Failed"/"Running", trailing success `check_circle` / `error`) instead of
tabs; the terminal fills the right side; the `BottomAppBar` spans the right side only.

```
┌──────────────────────────────┬───────────────────────────────────────────────┐
│ ← ⟳ Ran command bun test                                           ⧉   ⋮     │
├──────────────────────────────┼───────────────────────────────────────────────┤
│ Running · 12s   run_commands │ OUTPUT                     [All|stdout|stderr]│
│┃$ bun test        Running    │┌─────────────────────────────────────────────┐│
│ $ bun lint        Completed ✓││ bun test v1.4.2                              ││
│                              ││ ...                                          ││
│                              │└─────────────────────────────────────────────┘│
│                              │ Stop agent        [ ⟳ Proceed while running ] │
└──────────────────────────────┴───────────────────────────────────────────────┘
```

**≥ 1200dp × ≥ 480dp supporting pane (400dp):** the compact layout inside a small
`TopAppBar` pane header (title 1 line, actions copy + overflow + close); the
`BottomAppBar` spans the pane's width.

**Medium portrait (Tab S7+ ~778dp):** compact layout with 24dp gutters; terminal width
is the full content width.

**Edit layout, compact** (desktop order: the fuller path line leads, above the diff,
`tool-summary/index.ts:738-740`; error last):

```
┌──────────────────────────────────────────┐
│ ←                                    ⋮   │  MediumTopAppBar (collapses with the list)
│ ✎ Edited file  Main.kt                   │  one line, ellipsized
├──────────────────────────────────────────┤
│ Edited file Main.kt                      │  full label row (wraps, selectable)
│ Completed · 1s                    editor │  status line (sub-second tools show "1s")
│ DETAILS                                  │  only if summary.details non-empty
│ app/src/main/kotlin/dev/…/Main.kt        │  full path, wraps, selectable
│ app/src/main/kotlin/…/Main.kt  ⧉  +2 -1  │  file header (no chevron; not collapsible)
│ ┌──────────────────────────────────────┐ │
│ │ - val x = 1                          │ │  DiffHunkView, one per hunk
│ │ + val x = 2                          │ │
│ └──────────────────────────────────────┘ │
│ ERROR                                    │  only if errorText
│ No replacement performed: text not found │
└──────────────────────────────────────────┘
```

A `file` item with only `diff` text (no rich texts) renders the unified text in a
`surfaceContainer` code block (`ClineMono.body`, horizontal scroll), as the desktop's
`ToolActivityCode` fallback.

**Generic layout:** the full label row, the status line, then `LazyColumn` sections in
desktop order, each with an overline (`labelSmall`, uppercase, `onSurfaceVariant`):

1. "Details" — `summary.details` lines (`bodyMedium`; `ClineMono.body` for queries,
   URLs and paths), selectable.
2. Output, by kind:
   - `submit_and_exit`: the summary rendered with the markdown renderer in `onSurface`
     (desktop: "full foreground color").
   - otherwise `summary.outputText` in a `surfaceContainer` `shapes.small` block,
     `ClineMono.body`, wrapped, no height cap on this screen, selectable; overline
     "Output" *(Android: the desktop renders this block bare; the overline exists there
     only for command output)*.
3. Images: `LazyRow` of 160dp thumbnails (DS #ImageLightbox carousel); tap opens the
   lightbox. Other media: audio → a play/pause row with the file name; video → an
   inline player 16:9 max 240dp tall; file → a `ListItem` with `attach_file` and the
   name; unavailable → `Surface(surfaceContainerHighest)` "Media unavailable"
   *(Android)*.
4. "Input" — `BuildConfig.DEBUG` only (desktop `IS_DEBUG`): `formatToolValue(input)`
   pretty JSON, `ClineMono.body`, `surfaceContainer` block.
5. "Error" — `summary.errorText` in `bodyMedium` `error`, selectable.

**Terminal view (`TerminalView`).** Background `ClineColors.terminalBackground`
(`#0E0E12`), text `terminalForeground`, `shapes.small`, 12dp padding, `ClineMono.body`.
Lines are items of an internal `LazyColumn` (so 20,000 lines scroll smoothly); the
outer screen is a `Column` whose terminal takes `weight(1f)`, not a nested scroll.

- **Wrap on** (default, desktop `whitespace-pre-wrap break-words`): each line wraps;
  break anywhere for long tokens.
- **Wrap off:** the `LazyColumn` sits in a `Box(Modifier.horizontalScroll(h))` and gets an
  explicit width = `maxColumns × cellWidth + 24dp`, where `cellWidth` is measured once
  with `TextMeasurer` on "0" and `maxColumns` is the longest line in display cells
  (East Asian wide characters count 2), capped at 2,000 columns (longer lines are
  clipped with "…"). Lines are never re-measured per frame.
- **Cursor:** a 6 × 12dp block in `terminalForeground` after the last character while
  running, blinking 1 s (alpha 1 ⇄ 0, `Pulse` easing); static under reduced motion;
  absent when finished (desktop).
- **Follow-tail:** pinned while within 24dp of the bottom (desktop); a user drag up
  unpins; a `SmallFloatingActionButton` `arrow_downward` "Scroll to latest output"
  *(Android)* appears when more than 120dp from the bottom (DS ScrollToLatest
  thresholds), placed 16dp above the `BottomAppBar` when it is shown, else 16dp above
  `bottomSafe`. The pinned app bar (B.4 chrome) keeps the terminal's height constant
  while it scrolls, so the 24dp test and the per-command scroll offset stay stable.
- **Notice rows** inside the terminal (`labelMedium` in ANSI colour 8, `#71717A`), each
  matched as a whole line and rendered as received: the head-cap marker "[Earlier
  command output truncated]" (desktop, also used for a gapped replay, B.3), the kernel
  marker "[N command-output characters skipped while streaming]", the detach notice
  "[Command is still running. Output will continue in <path>]" (bash.ts:887, B.5) and
  the leftover-children notice "[Command completed with background processes still
  running; their output is no longer captured]" (bash.ts:1093: the command exited but
  children still hold its pipes; the status stays "Completed"). They are not part of
  copied output.
- **Pinch** with two fingers scales the terminal text 0.75×–2.0× for this screen only
  (`rememberSaveable`); double-tap with two fingers resets. Not available when a
  TalkBack/Switch Access service is on (use the system font size instead).
- **Selection:** long-press starts selection within a line; "Copy output" copies the
  whole (filtered) buffer without ANSI escapes.

**ANSI parser (`AnsiParser`, pure, unit-tested):**

| Input | Handling |
| --- | --- |
| SGR `ESC[...m`: 0 reset, 1 bold (SemiBold weight + bright colour for 30–37), 2 dim (alpha 0.6), 3 italic, 4 underline, 7 inverse, 9 strikethrough, 22/23/24/27/29 resets, 30–37/90–97 fg, 40–47/100–107 bg, 39/49 defaults, `38;5;n`/`48;5;n` 256-colour, `38;2;r;g;b`/`48;2;r;g;b` truecolour | DS §1.5 16-colour palette; 256-cube computed (16–231 6×6×6, 232–255 greys); truecolour as given |
| Other CSI (`ESC[K`, `ESC[2K`, cursor moves `A B C D G H`, `ESC[?25l` etc.) | `K`/`2K` clear the current line; `G n`/`D n`/`C n` move the cursor within the current line; the rest are dropped |
| OSC `ESC]...BEL` or `ESC]...ESC\` | dropped, except OSC 8 hyperlinks → `LinkAnnotation.Url` (opens via `CustomTabs`/browser with the system confirmation of `UriHandler`) |
| `\r\n` | newline |
| lone `\r` | return to column 0 of the current line; following text overwrites (progress bars render as one updating line) |
| `\b` | move back one cell |
| `\t` | expand to the next multiple of 8 columns |
| other C0 controls | dropped |
| escape split across two chunks | parser keeps up to 64 bytes of pending escape state between chunks |

Stream filter: "All" (interleaved, desktop), "stdout", "stderr"
(`SingleChoiceSegmentedButtonRow`, DS §4.3). Shown only once any stderr chunk exists.
In "All", stderr text keeps its own ANSI colours (the desktop does not tint stderr).

### B.5 States

`ToolStatus` from the existing reducer, refined for this screen:

| State | Derived from | Status line | Icon slot | Body | Bottom bar |
| --- | --- | --- | --- | --- | --- |
| Loading | route opened, tool not yet in the live store, `session.messages` in flight | — | — | `LinearProgressIndicator` under the app bar | none |
| Waiting for output | running, command, no chunk text yet | "Running · 2s" | spinner | terminal with "Waiting for output..." *(Android)* in `#71717A` and the cursor | as running |
| Running / streaming | `ToolStatus.Running` and `isRunActive` (desktop gate: in progress **and** the run is active) | "Running · Ns" (ticks) | 16dp spinner | live terminal / sections | "Proceed while running" if `canProceed`; "Stop agent" |
| Awaiting approval | `ToolStatus.AwaitingApproval` | "Approval required" (DS §1.6 label) | `gpp_maybe` in `warning.color` | command preview only; the approval sheet opens over the screen (1.6) | "Stop agent" |
| Completed | `Finished(error == null)` | "Completed · Ns" | kind icon | final output | none |
| Failed | `Finished(error != null)` or `payload.isError` | "Failed · Ns" | `error` (fill 1) in `error` | desktop order: any **streamed** output first (it is kept, 1.4), then the Error section; without streamed output only the Error section | none |
| Rejected | `ToolStatus.Rejected` (error contains "This tool call was rejected by the user") | "Rejected" *(Android)* | `block` in `error` | Error section with the kernel text | none |
| Still running in the background | completed with a result containing `[Command is still running. Output will continue in <path>]` | "Still running in the background" *(Android)* | `terminal` | output up to the detach + notice row; `TextButton` "Copy log path" *(Android)* copies `<path>` | none |
| Completed, children left running | completed with the line `[Command completed with background processes still running; their output is no longer captured]` | "Completed · Ns" | kind icon | output + that notice row (B.4), which explains why the output stops | none |
| No result | in progress but the run is no longer active (desktop: "A missing result can outlive its run") | "No result" *(Android)* | kind icon in `onSurfaceVariant` | banner (`surfaceContainerHigh`, `info`): "The run ended before this tool reported a result." *(Android)* | none |
| Interrupted | `run.interrupted {reason:"hub_restart"}` while running | "Interrupted" *(Android)* | `warning` in `warning.color` | banner: "The Cline Hub restarted while this tool was running." *(Android)*; output frozen | none |
| History (not live) | session not live (resumed from `session.messages` only) | as Completed/Failed, no duration if unknown | as above | from `tool_use`/`tool_result` | none |
| Empty output | finished, no output text | normal | normal | terminal shows "No output" *(Android)*; generic layout omits the Output section | none |
| Not found | id absent from store and history | — | — | EmptyState icon `search_off`, title "This tool call is no longer in the session." *(Android)*, `FilledTonalButton` "Back to conversation" *(Android)* | none |
| Offline / kernel down | KernelStrip shown | unchanged | unchanged | cached content stays; live updates pause | "Proceed while running" and "Stop agent" **disabled** with the strip explaining why |
| Reconnecting mid-stream | socket dropped, backoff | unchanged | spinner keeps spinning | output resumes from the replay without duplicates (dedupe by `sequence`) as far as the log retains it; a gap shows the head notice row (B.3) | disabled until reconnected |
| Permission missing | "Start kernel" without RUN_COMMAND permission | — | — | navigate to Kernel setup (1.6) | — |
| First run | — | no special state | | | |

`canProceed` (desktop): `isRunning && kind == command && executions.any { detachable } &&
sessionLive`. While a proceed request is pending: button disabled with an 18dp spinner
and the same label, "Stop agent" stays enabled. After `ok {detachedCount ≥ 1}` the
button disappears; the tool finishes moments later with the detach notice.

### B.6 Copy

| Element | String | Source |
| --- | --- | --- |
| Title | `buildToolSummary` label, e.g. "Ran command `bun test`", "Edited file", "Read 3 files", "Completing scheduled task" / "Scheduled task completed" / "Scheduled task failed"; for a call in progress whose run is no longer active, the transcript row's "Interrupted: `<specific>`" (HC §3.4.4, decided A21) | desktop (DS #2); A21 *(Android)* |
| Status labels | "Running", "Completed", "Failed", "Approval required", "Rejected", "Still running in the background", "No result", "Interrupted" | DS §1.6 / Android |
| Status line format | "%1$s · %2$s" (label, duration) | Android |
| Overlines | "Command", "Output", "Details", "Input", "Error" | "Output" desktop for command output, Android for the generic output block; "Input" desktop; rest Android |
| Height-compact filter heading | "Show" | Android |
| Proceed | "Proceed while running" | desktop |
| Proceed errors | "Could not detach the running command.", "The command finished before it could be detached.", "This runtime does not support proceeding while commands are running." | desktop / kernel |
| Stop | "Stop agent" | desktop |
| Waiting | "Waiting for output..." | Android |
| No output | "No output" | Android |
| Head-cap marker | "[Earlier command output truncated]" | desktop |
| Detach notice | kernel text as received; button "Copy log path" | kernel / Android |
| Leftover-children notice | kernel text as received | kernel |
| No result banner | "The run ended before this tool reported a result." | Android |
| Interrupted banner | "The Cline Hub restarted while this tool was running." | Android |
| Not found | "This tool call is no longer in the session.", "Back to conversation" | Android |
| Actions | "Copy output", "Copy command", "Copy input", "Share output", "Wrap lines", "View all changes", "More options" | Android ("More options" = M3 overflow CD) |
| Copied CDs (1600 ms, as A) | "Copied output", "Copied command", "Copied input", "Copied log path" | Android |
| Filter | "All", "stdout", "stderr" | Android (stream names as the kernel spells them) |
| Terminal CD | "Command output" | desktop |
| Scroll FAB | "Scroll to latest output" | Android |
| Pane close | "Close tool details" | Android |
| Media fallback | "Media unavailable" | Android |
| Draining | "Cline Hub is restarting. Waiting..." | Android |
| Announcements | "Command finished", "Command failed", "Command detached" | Android |

### B.7 Interactions and gestures

| Gesture | Result |
| --- | --- |
| Tap "Proceed while running" | send `run.proceed_while_running` (envelope `sessionId`, payload `{sessionId, toolCallId}`); pending UI; result per 1.5 |
| Tap "Stop agent" | `run.abort {sessionId, reason: "User pressed Stop"}` (existing builder); no confirm (desktop Stop has none); the button shows "Stop agent" disabled until `run.aborted` |
| Tap "Copy output" | copies the current filter's text, ANSI stripped, notice rows excluded |
| Overflow "Copy command" | copies `query` of the selected command (multi-command: the selected tab) |
| Overflow "Copy input" | copies `formatToolValue(input)` (pretty JSON). Inputs never contain Hub tokens; the app does not redact |
| Overflow "Share output" | `ACTION_SEND text/plain` with "$ <query>\n<output>" per command; above 100,000 chars via `FileProvider` as in A.7 (`.log`, `text/plain`) |
| Overflow "Wrap lines" | toggles wrap; persisted app-wide in DataStore `toolDetail.wrapLines` (default true) |
| Tab / list selection | switches the visible command; scroll position per command remembered |
| Drag up in terminal | unpins follow-tail |
| Tap FAB | animated scroll to the end, re-pins |
| Pinch | text scale (B.4) |
| Tap image | lightbox (DS #ImageLightbox) |
| Overflow "View all changes" | opens A for this session |
| Hardware keyboard | Ctrl+C copies selection or, with none, the output; End jumps to the bottom; Esc stops the agent when it can abort (DS §5.7) |

### B.8 Data binding and ViewModel

```kotlin
@Immutable data class ToolDetailUiState(
    val sessionId: String,
    val toolCallId: String,
    val kernel: KernelConnectionVisual,
    val loading: Boolean,
    val notFound: Boolean,
    val toolName: String,                     // raw, e.g. "run_commands"
    val kind: ToolKind,
    val labelParts: List<ToolLabelPart>,
    val status: ToolDetailStatus,             // the B.5 rows
    val durationMs: Long?,                    // null = unknown
    val layout: Layout,                       // Command | Edit | Generic
    val commands: List<CommandView>,          // command layout
    val selectedCommand: Int,
    val streamFilter: StreamFilter,           // All | Stdout | Stderr
    val wrapLines: Boolean,
    val fileItems: List<ToolFileItemView>,    // edit layout: path, displayPath, additions, deletions, hunks, fragment
    val details: List<String>,
    val outputText: String?,
    val submitMarkdown: String?,
    val images: List<ChatImage>,
    val otherMedia: List<ToolOutputMedia>,
    val inputJson: String?,                   // always computed; shown only in DEBUG
    val errorText: String?,
    val detachedLogPath: String?,
    val canProceed: Boolean,
    val proceed: ActionState,                 // Idle | Pending | Error(message)
    val canAbort: Boolean,
    val abortPending: Boolean,
)
data class CommandView(val index: Int, val query: String, val lines: TerminalLines,
                       val hasStderr: Boolean, val success: Boolean?, val running: Boolean,
                       /** replay lost the head of this execution (B.3); after finish, lines come from result */
                       val gapped: Boolean)
```

`ToolDetailViewModel(sessionId, toolCallId)`:

- collects `ToolCallStore.tool(sessionId, toolCallId)` (live reducer state: `tool.started`,
  `tool.updated`, `tool.finished`) and `SessionThread.status` / run state
  (`run.started`, `run.completed`, `run.failed`, `run.aborted`, `run.interrupted`);
- if the tool is not in the live store, calls `session.messages {sessionId}` once and
  builds the tool from the `tool_use`/`tool_result` pair;
- feeds chunk appends into `TerminalBuffer` off the main thread, publishing at most every
  50 ms (DS §6 streaming rule) with `conflate()`;
- sends `run.proceed_while_running` and `run.abort` through `HubSessionApi`;
- persists `selectedCommand`, `streamFilter` and the text scale in `SavedStateHandle`.

### B.9 Edge cases

- **Multiple commands in one call:** tabs/list by `commandIndex`; executions announced by
  the empty first chunk appear immediately as "Waiting for output...". History output
  for multi-command calls is split per command from `output[]` (`{query, result,
  success}`), not from the joined `$ query` text.
- **Long commands (trial row 18):** the command this screen prints (Command section,
  tabs, list, "Copy command", "Share output") is entry `commandIndex` of the call input's
  commands, in the kernel's order (`normalizeRunCommandsInput`, heredoc entries joined,
  `data/tools/ExecutedCommands.kt`). The `query` of a `tool.updated` or a result record is
  the kernel's echo, capped at 200 characters and ended with "... [command truncated: N
  more chars; full command is in the tool call input]"; it is used only when the call has
  no input or its entry does not match the echo, and then with "…" in place of the note.
  Above the terminal the Command section is capped at 160dp and scrolls; without a
  terminal it takes the free height.
- **Detach available only after the process probe:** `detachable` flips to `true` in a
  later empty update (bash.ts); the button appears then, never before.
- **Proceed races completion:** `detachedCount: 0` → the finished-before message; the
  button hides.
- **Huge output (`seq 1 2000000`, about 14 MB):** live, the kernel coalesces and may drop
  (`truncated`), the app keeps the last 1,048,576 characters, the terminal shows both
  markers in order, and scrolling stays fluid (LazyColumn of lines). After an app kill
  the replay is **not** guaranteed to cover such a stream (B.3); the screen then shows
  the head notice and, once finished, the kernel's final `result`.
- **Progress bars with `\r`:** one line updates in place; copy output gives the final
  state of each line.
- **Binary output:** control characters dropped; invalid UTF-8 already replaced by the
  kernel.
- **Approval for this very call:** the screen shows the Awaiting approval state and the
  sheet; approving continues streaming here.
- **Tool from a subagent:** same screen; the title has no agent prefix (desktop has none).
- **Session busy toggles while open:** the docked `BottomAppBar` appears/disappears and
  the terminal's height changes by the bar height; the terminal keeps its bottom item
  anchored, so a pinned follow-tail stays pinned and an unpinned view keeps the same
  first visible line.

### B.10 Accessibility

- Terminal: `semantics { contentDescription = "Command output"; liveRegion = none }`
  (desktop `aria-live="off"`), each line a text node read as written (DS §8.1 #12). On
  finish, the screen's polite live region announces "Command finished" / "Command
  failed" / "Command detached".
- Status line is a heading. The kind icon has `contentDescription = null` (the status
  line says it).
- "Proceed while running" and "Stop agent" are 48dp tall targets; the pending spinner has
  no separate description, the button gets `stateDescription` "In progress" *(Android)*.
- Stream filter segments read "Show all output", "Show stdout only", "Show stderr only"
  *(Android)*.
- Pinch zoom is disabled under TalkBack (B.4); text scaling follows DS §2.4 and §8.1 #8.
- Contrast: terminal pairs are DS §1.5 (≥ 4.0 except dim black by design, which is never
  used for app-drawn text).

### B.11 Acceptance checklist (S25U)

- [ ] Prompt `Run: printf '\e[31mred\e[0m \e[1;32mbold green\e[0m \e[38;5;208morange\e[0m\n'`.
      "View details" opens the screen; colours render; bold green is brighter and
      SemiBold; copied output has no escape codes.
- [ ] Prompt `Run: for i in $(seq 1 20); do printf '\r%d%%' $((i*5)); sleep 0.3; done; echo`.
      One line counts up to 100%; the cursor blinks while running and disappears after.
- [ ] Prompt `Run: for i in $(seq 1 90); do echo line $i; sleep 1; done`. The status ticks
      "Running · Ns"; after a few seconds "Proceed while running" appears; tapping it
      shows a spinner, then the agent continues, the screen shows "Still running in the
      background" and "Copy log path" copies a path under `~/.cline`.
- [ ] Repeat with `seq 1 2000000` (live check only): both truncation markers appear;
      scrolling is smooth; follow-tail stays pinned until you drag up; the FAB appears
      and re-pins; the app bar never collapses while the terminal scrolls.
- [ ] A command writing to stderr (`ls /nonexistent; echo ok`) shows the stdout/stderr
      filter; each filter shows the right lines.
- [ ] Two-command call (`Run these two commands separately: echo a; echo b`) shows two
      tabs on the phone in portrait and in landscape (tabs share the status row there);
      a DeX window ≥ 840dp × ≥ 480dp or Tab S7+ landscape full screen shows the 320dp
      command list.
- [ ] S25U landscape (891 × 412dp) during the 90 s loop: small pinned bar with "Stop
      agent" and "Proceed while running" as text buttons, status and tabs in one row, no
      bottom bar; the terminal shows at least 12 lines at the default font size.
- [ ] Prompt `Run: (sleep 30 &) ; echo done`: the output ends with the notice row
      "[Command completed with background processes still running; their output is no
      longer captured]" in grey; status "Completed"; "Copy output" omits that line.
- [ ] A command that streams lines and then fails (`seq 1 50; exit 3`): the 50 lines
      stay visible, the Error section follows them.
- [ ] While the 90 s loop runs, "Stop agent" sits at the start of the bottom bar and
      "Proceed while running" at the end; with no bar shown, the dark terminal reaches the
      bottom edge with no light strip under it (gesture navigation).
- [ ] "Wrap lines" off: a 300-character line scrolls horizontally; the setting persists
      after app restart.
- [ ] "Stop agent" during the 90 s loop aborts the run; status becomes "No result" or
      "Failed" per the kernel's final event.
- [ ] `cline hub stop` during a stream: KernelStrip appears, buttons disable; `cline hub
      start` and reconnect: output resumes without duplicated lines.
- [ ] Kill the app process during a bounded stream (`seq 1 200000` with a short sleep
      per 10,000 lines; `adb shell am kill io.github.ichouchiang.clinetermux` after backgrounding), reopen
      the route: the output is rebuilt from the replay with no duplicated lines, or, if
      the log no longer holds its head, starts with "[Earlier command output
      truncated]"; after the tool finishes the kernel's final result is shown.
- [ ] Edit call: the screen shows each hunk with the same rendering as Changes.
- [ ] TalkBack: the terminal reads lines; finishing announces "Command finished".
- [ ] Tab S7+ landscape: details open in the 400dp supporting pane and the Chats list
      pane collapses; predictive back closes the pane and the list pane returns.
- [ ] An `editor` call that took under a second shows "Completed · 1s"; the Details path
      line sits above the hunks.

---

## C. Checkpoint dialogs

### C.1 Purpose

Let the user rewind a session to the checkpoint taken before one of their messages:
**Revert** (keep that message, discard what came after) or **Edit and restart** (start
again from just before that message with its text in the composer). Both offer the
CLI's choice: rewind the **chat only** or the **chat and the workspace** files. A
picker sheet (the CLI's `/undo`) lists every restorable message; picking one is the
CLI's semantic, which is **edit and restart** (the CLI restores with
`omitCheckpointMessageFromSession: true` and puts the message back into the input), so
the picker opens C2.

### C.2 Desktop equivalent

`webview/components/views/chat/chat-messages.tsx` (both `AlertDialog`s, the
close-on-confirm behaviour, disabled rules), `messages/message-bubble.tsx` (user
actions, pending spinner in the icon, error under the row), `hooks/use-chat-session.ts`
`restoreCheckpoint`, `app/page.tsx` `handleEditMessage` + `openForkedSession`,
`sidecar/chat-session.ts` `handleRestoreCheckpoint` and the edit-fork branch (with its
`forkMetadata`). Choice and picker from the CLI:
`apps/cli/src/tui/components/dialogs/checkpoint-confirm.tsx`, `checkpoint-picker.tsx`,
`tui/checkpoint-picker-items.ts`, `tui/root.tsx` `openCheckpointRestore`,
`runtime/interactive/session-runtime.ts` `restoreCheckpoint`. Copied: titles,
descriptions, confirm labels, the dialog closing on confirm, pending and error shown on
the message row, error fallbacks, the busy rule (incl. *stopping*), new-session
semantics, composer prefill for edit, the edited session keeping the source metadata,
the CLI's option labels and git warning, the picker's title, rows (at-or-before
checkpoint), empty texts, previews, newest-first preselection, status and error strings.
Translated: the desktop's hover icons become the action row + long-press menu (DS-A6);
the CLI's keyboard list becomes radio rows inside an M3 `AlertDialog`; the CLI's picker
becomes a bottom sheet; the CLI's in-transcript status entry becomes an indefinite
snackbar (picker path only, the sheet has closed and the row may be off-screen).

### C.3 Navigation and entry points

| Entry | Opens | Visible when |
| --- | --- | --- |
| User message action row, `undo` "Restore checkpoint" (desktop) | C1 for that message | the message has an **exact** checkpoint (C.4 rule 3) |
| User message action row, `edit` "Edit user message" (desktop) | C2 for that message | the message has a run count and non-blank text (desktop) |
| User message long-press menu (DS-A6) "Restore checkpoint" / "Edit and restart from here" | C1 / C2 | as above |
| ConversationHeader overflow "Restore checkpoint…" (DS-A4) | C3 picker | the session is a non-cloud local session (always on Android) |
| C3 row tap | **C2 for that message, picker mode** (CLI `/undo` semantic; C.6) | the row is enabled (C.8) |

**Busy rule.** All entries are unavailable while the session status is starting,
running, pending or **stopping**, while a `run.abort` is pending (the chat's stop
request or B.8 `abortPending`), while the session is switching, or while any versioning
action (restore, edit, fork) is pending (desktop `editDisabled` / `restoreDisabled`,
`chat-messages.tsx:665-686`; `BUSY_STATUSES` = starting, running, stopping).

How "unavailable" looks and reads:

- **Action-row icons** keep `enabled = true` so they still take a tap, a long-press
  tooltip and TalkBack focus, but draw with the disabled content colour
  (`LocalContentColor` at 38 %). A tap shows a Short snackbar with the reason, and the
  node gets `stateDescription` = the same reason: "Wait for the current turn to finish
  before undoing" (desktop busy text) when the session is busy, "Checkpoint restore
  already in progress" (CLI) when a versioning action is pending. A disabled
  `IconButton` would receive no long-press and TalkBack would read only "disabled".
- **Long-press menu items** use `enabled = false` (the action row explains why).
- **C3 rows** are disabled and the sheet's supporting line explains (C.8).

Dialogs are not routes. Their state lives in `CheckpointViewModel` (scoped to the chat
route) and `SavedStateHandle`, so they survive rotation and process death. Back and an
outside tap dismiss a dialog (DS §5.8 item 1 level). **Confirm closes the dialog
immediately** (desktop) and the work continues in the ViewModel, so a dialog never sits
in a pending state and never blocks back. The picker sheet follows DS §5.5 (predictive
collapse); C2 opened from it sits on top of the sheet: Cancel returns to the sheet,
Continue closes both. Switching session closes any open dialog and the sheet (desktop
resets on `sessionId` change).

### C.4 Which messages have checkpoints (`CheckpointIndex`, pure)

1. Read `snapshot.checkpoint.history` from `session.get {includeSnapshot:true}` and from
   every `session.updated` snapshot for the session (fallback
   `session.metadata.checkpoint.history`). Entries `{ref, createdAt, runCount, kind?}`.
2. Walk `session.messages` in order with a Kotlin port of core's `getUserRunSpan`
   (`core/src/session/user-run-messages.ts`: tool-result-only user messages count 0,
   synthetic kinds count 0, a compaction summary spans the runs it folded) to give every
   user message its run count. Do not count raw `role == "user"` messages (the CLI's
   comment: that overcounts and the core cannot map the run).
3. `hasExactCheckpoint(runCount)`: a user message whose span is exactly 1 and whose run
   count equals a history entry's `runCount`. Used **only for the C1 undo icon and menu
   item** (desktop `checkpointsByRunCount.get(userRunCount)`, `messages.ts:437`).
4. `checkpointAtOrBefore(runCount)` = port of `findCheckpointForRun` (highest entry with
   `runCount ≤` the message's run count). Used for **C2's workspace option** (sidecar
   `canRestoreWorkspace`) and for **C3 rows** (CLI `checkpointForRun`). This is also how
   the kernel itself resolves a restore ("No checkpoint found at or before run N").
5. Edit availability: span 1, display text (`UserInputFormat.display`) not blank.
6. `restorableMessages()` for C3: every user message with span 1, a non-blank preview
   and `checkpointAtOrBefore(runCount) != null`, oldest first; each row carries the
   matched entry's `createdAt` as its time (CLI). *Reviewed: kept span 1 only although the
   CLI also lists compaction summaries (span > 1), because the kernel always refuses
   those with "Cannot fork before run N because it is folded into a compacted message".*
7. Messages folded into a compacted summary (span > 1) have no actions.
8. Live updates: a new turn's checkpoint arrives with the `session.updated` that follows
   `run.started`; the undo icon appears on that user message then.
9. Also ported here (pure, for the split path 1.5.3 and the no-checkpoint edit):
   `trimMessagesToCheckpoint`, `trimMessagesBeforeUserRun`,
   `createRestoredCheckpointMetadata`, with their error texts verbatim.

Previews (C2 "Restore to:" line, C3 rows): display text with whitespace collapsed,
truncated to 60 characters with "…" (CLI `truncateStr(..., 60)`, grapheme-safe per DS
§8.2 #7). C1 uses the same line.

### C.5 Layout

Dialog tokens (DS #16 with DS-A8): `AlertDialog`, `surfaceContainerHigh`, 28dp corners,
max width 560dp on medium+; title `headlineSmall` (M3 default); icon 24dp in the M3
default `secondary`; dismiss `TextButton`; destructive confirm
`TextButton(contentColor = error)`.

**C1 "Revert to this checkpoint?"** — icon `undo`.

```
┌──────────────────────────────────────────┐
│                    ↶                     │  icon, secondary
│       Revert to this checkpoint?         │  headlineSmall
│                                          │
│ Restore to: "Add a dark mode toggle to…" │  bodyMedium onSurfaceVariant, 2 lines max
│                                          │
│ (•) Restore chat and workspace           │  RadioButton row, 56dp min
│     Rewind conversation and reset files  │  bodySmall onSurfaceVariant
│ ( ) Restore chat only                    │
│     Rewind conversation, keep current    │
│     files                                │
│                                          │
│ Workspace files and conversation history │  consequence text (changes with choice)
│ after this point will be discarded. This │
│ cannot be undone.                        │
│ ⚠ This runs git reset --hard and git     │  warning.color, only for workspace option
│   clean -fd in the workspace.            │
│ The current session stays in history.    │  bodySmall onSurfaceVariant
│                                          │
│                      Cancel     Revert   │  TextButton / TextButton(error)
└──────────────────────────────────────────┘
```

- Option order and default: **"Restore chat and workspace" first and selected**, because
  the desktop's single "Revert" restores both (`workspace: true`); "Restore chat only"
  second (CLI). The choice is not remembered between dialogs (Q5).
- Radio rows: `Modifier.selectable(role = Role.RadioButton)` on the whole row,
  `selectableGroup()` on the column; label `bodyLarge`, detail `bodySmall`.
- The text slot scrolls (`verticalScroll`) when the content exceeds the dialog height
  (font scale 2.0, landscape).
- An **error line** (`bodySmall` `error`, polite live region) sits above the buttons only
  in three cases: the kernel is down, the session became busy while the dialog was open
  (C.8), or the dialog was **reopened after a workspace refusal** (C.6).

**C2 "Edit and restart from here?"** — same structure, icon `edit`:

```
┌──────────────────────────────────────────┐
│                    ✎                     │
│       Edit and restart from here?        │
│                                          │
│ Restore to: "Add a dark mode toggle to…" │
│ (•) Restore chat and workspace           │  options only when checkpointAtOrBefore
│ ( ) Restore chat only                    │  exists (C.4 rule 4)
│                                          │
│ This creates a new session and restores  │  desktop text for the workspace option
│ the workspace to its checkpoint before   │
│ placing this message in the composer.    │
│ Workspace and conversation changes after │
│ this point will be discarded.            │
│ ⚠ This runs git reset --hard and git     │
│   clean -fd in the workspace.            │
│ The current session stays in history.    │
│                      Cancel   Continue   │
└──────────────────────────────────────────┘
```

Without a checkpoint at or before that run, the options are not shown and the text is
the no-checkpoint text (C.7). In picker mode (from C3) a checkpoint always exists.

**After confirm (desktop: the dialog is already gone).** On the originating user
message's action row:

- **Pending:** the `undo` (C1) or `edit` (C2, also picker mode) icon becomes a 16dp
  `CircularProgressIndicator` inside the same 48dp target; the action row stays visible
  (desktop `keepActionsVisible`); the node's `stateDescription` is "In progress". When the
  Hub is draining, a `bodySmall` `onSurfaceVariant` line "Cline Hub is restarting.
  Waiting..." shows under the row. Picker mode additionally shows an Indefinite snackbar
  with the CLI status "Restoring to checkpoint (chat + workspace)..." / "Restoring to
  checkpoint (chat only)...".
- **Error:** end-aligned `bodySmall` `error` text under the row (desktop), polite live
  region; the icon returns. Text = the kernel message, else the fallback "Could not
  restore checkpoint." (C1) / "Could not restart from this message." (C2). Picker mode
  replaces the status snackbar with a Long snackbar "Checkpoint restore failed: %1$s"
  (CLI; `%1$s` = kernel message, or "no result returned." when the reply had no session)
  and shows the same text under the row.
- **Success:** the chat moves to the new session (C.6); C1 and picker mode show the Short
  snackbar "Restored to checkpoint" (CLI); C2 from the row shows none (desktop).

**C3 "Restore to Checkpoint" picker** — `ModalBottomSheet` (DS §5.5), title row
"Restore to Checkpoint" (`titleMedium`), supporting line "Choose a message to restart
from." *(Android)* `bodyMedium` `onSurfaceVariant`, then a `LazyColumn` of two-line
`ListItem`s from `restorableMessages()`, oldest first (CLI order) and **scrolled to and
focused on the newest** (CLI preselects the last): leading `undo` 24dp; headline =
preview (`bodyLarge`, 2 lines max); supporting = relative time of the matched
checkpoint's `createdAt` (`DateUtils.getRelativeTimeSpanString(...,
FORMAT_ABBREV_RELATIVE)`, DS §8.2 #5) and, when the message's mode is not act, " ·
Plan". Tap → C2 in picker mode for that message (C.3).

```
┌──────────────────────────────────────────┐
│                  ────                    │  drag handle
│ Restore to Checkpoint                    │
│ Choose a message to restart from.        │
│ ↶ Create hello.py that prints hello      │
│   12 min. ago                            │
│ ↶ Change it to print hi                  │
│   8 min. ago                             │
│ ↶ Add a dark mode toggle to settings…    │  newest, focused
│   1 min. ago                             │
└──────────────────────────────────────────┘
```

Tablet: dialogs keep M3 dialog widths (280–560dp), centred; the sheet uses the DS
640dp max width centred. No other difference.

### C.6 Flows and kernel bindings

**Common to every confirm:**

1. Guard (re-checked at confirm time): the busy rule of C.3 (session starting, running,
   pending or stopping, a `run.abort` pending, switching) → keep the dialog open with
   the error line "Wait for the current turn to finish before undoing" (desktop); kernel
   not connected → confirm is already disabled (C.8); another versioning action pending
   → close the dialog, Short snackbar "Checkpoint restore already in progress" (CLI).
2. Close the dialog (and, in picker mode, the sheet). Record the pending request
   `{kind, source, runCount, restoreWorkspace, forkedAt?, requestSentAt}` in
   `SavedStateHandle` (lost-reply recovery after process death, 1.6) and show the
   pending state on the row (C.5).
3. Build `NewSessionSpec` from the session record: `workspaceRoot`, `cwd`
   (`session.cwd ?: workspaceRoot`), `providerId`/`modelId` (`metadata.provider` /
   `metadata.model`, falling back to `snapshot.model`), `mode` (`metadata.mode`),
   `autoApprove` (the app's stored per-session value), thinking/effort (local
   selection), `contributeAskQuestion = true`.
4. Choose the path by kernel release (1.5.1): **atomic** (`release ≥
   MIN_PORT_RELEASE_RESTORE`) or **split** (otherwise). Every `session.restore` is sent
   with `timeoutMs = SESSION_RESTORE_TIMEOUT_MS` and a client timer of
   `SESSION_RESTORE_CLIENT_TIMEOUT_MS`.
5. Map the reply per 1.5.4. `hub_connection_closed`, `hub_command_timeout` and the client
   timer go to the lost-reply recovery (1.6), never to a retry.
6. **Workspace refusal** (reply text starts with "Cannot restore the workspace:" or ends
   with "is not a git repository"): clear the pending state and **reopen the same
   dialog** for the same message with the kernel text verbatim in the error line and
   "Restore chat only" selected. After "Cannot restore the workspace:" the radio stays
   editable; after "is not a git repository" the workspace option is hidden for the rest
   of that dialog's life. Confirm then retries with `workspace: false`. Nothing was
   changed by the refused attempt (the kernel checks before touching the tree).
7. **Success** (`ok` with a new `session.sessionId`; on the split path, the `ok` of the
   final `session.create`):
   - clear this session's pending approvals, questions and queued prompts in the UI
     (desktop does);
   - `session.detach {sessionId: source}` (best-effort, 1.6) and `stream.unsubscribe`
     for the source;
   - `stream.subscribe {sessionId: new}` without a cursor (the session is new);
   - replace the chat route's `sessionId` with the new id (`navigate(...,
     popUpTo(chat) { inclusive = true })` so back does not return to the old session);
   - seed the transcript from `reply.messages` (fall back to `session.messages` if
     absent), status "completed" when there are messages, else idle (desktop);
   - the Changes pane / Tool detail pane of the old session close (session change, A.3,
     B.3);
   - clear the pending request from `SavedStateHandle`.
8. The source session stays in the session list (the Hub does not delete it). The new
   one arrives via `session.created`.

**Revert (C1 confirm):** atomic path `HubPayloads.sessionRestore(source, runCount,
restoreWorkspace = choice, omitCheckpointMessage = false, spec,
restoreMetadata(Revert, …))`; split path 1.5.3 with `trimMessagesToCheckpoint`. On
success, the Short snackbar "Restored to checkpoint" (CLI).

**Edit and restart (C2 confirm, from the row or in picker mode):**

- **With `checkpointAtOrBefore(runCount)`** (always true in picker mode): atomic path
  `sessionRestore(source, runCount, restoreWorkspace = choice, omitCheckpointMessage =
  true, spec, restoreMetadata(Edit, …))`, i.e. the metadata spreads the source session's
  metadata (title kept) and adds `fork` (1.5.2). Split path 1.5.3 with
  `trimMessagesBeforeUserRun`. On success: step 7, then put
  `UserInputFormat.display(message full text)` into the composer of the new session,
  place the cursor at the end, request focus and show the IME (desktop
  `openForkedSession(result, content)`; CLI `populateInputRef(formatDisplayUserInput(...))`).
  Picker mode also shows "Restored to checkpoint" (CLI); from the row no snackbar
  (desktop). Attachments of the original message are not restored (desktop does not).
- **Without a checkpoint** (only from the row; only "chat only" is possible): client-side
  fork like the sidecar: `session.messages {sessionId: source}` →
  `trimMessagesBeforeUserRun` (throws the compacted-run error for span ≠ 1, shown
  verbatim) → `HubPayloads.sessionCreateRestored(spec, trimmed, restoreMetadata(Edit, …,
  splitPath = false))` (source metadata spread + `fork`, no `restoredFrom…` keys). This
  is a plain `session.create`, so the stored provider key is used on every kernel. Then
  the same success steps and composer prefill.

**Picker (C3):** on open, refresh `session.get {includeSnapshot:true}` and
`session.messages` if the cached copies are older than 10 s (the sheet shows its loading
state meanwhile), then compute `restorableMessages()`. No other Hub command until a row
is confirmed in C2.

### C.7 Copy

| Element | String | Source |
| --- | --- | --- |
| C1 title | "Revert to this checkpoint?" | desktop |
| C1 confirm | "Revert" | desktop |
| C2 title | "Edit and restart from here?" | desktop |
| C2 confirm | "Continue" | desktop |
| Cancel | "Cancel" | desktop |
| Preview line | "Restore to: \"%1$s\"" | CLI |
| Option 1 | "Restore chat and workspace" / "Rewind conversation and reset files" | CLI |
| Option 2 | "Restore chat only" / "Rewind conversation, keep current files" | CLI |
| Git warning | "This runs git reset --hard and git clean -fd in the workspace." | CLI |
| C1 text, workspace | "Workspace files and conversation history after this point will be discarded. This cannot be undone." | desktop |
| C1 text, chat only | "Conversation history after this point will be discarded. Workspace files are kept as they are now." | Android |
| C2 text, workspace | "This creates a new session and restores the workspace to its checkpoint before placing this message in the composer. Workspace and conversation changes after this point will be discarded." | desktop |
| C2 text, chat only | "This creates a new session and places this message in the composer. Workspace files are kept as they are now." | Android |
| C2 text, no checkpoint | "This creates a new session from the conversation before this message and places the message in the composer. No checkpoint was recorded for this message, so workspace files stay as they are." | Android |
| History note | "The current session stays in history." | Android |
| Busy error | "Wait for the current turn to finish before undoing" | desktop |
| Revert error fallback | "Could not restore checkpoint." | desktop |
| Edit error fallback | "Could not restart from this message." | desktop |
| Split path, step 2 failed | "The workspace was restored, but the new session could not start: %1$s" | Android |
| Pending status (picker mode snackbar) | "Restoring to checkpoint (chat + workspace)..." / "Restoring to checkpoint (chat only)..." | CLI |
| Pending state description (row icon) | "In progress" | Android (shared with B.10) |
| Picker-mode error | "Checkpoint restore failed: %1$s"; no-result variant "Checkpoint restore failed: no result returned." | CLI |
| Kernel down in dialog | "Cline Hub is not connected." | DS §1.6 |
| Draining | "Cline Hub is restarting. Waiting..." | Android |
| Lost reply | title "Restore status unknown"; text "The connection dropped before the restore finished. Check the session list before trying again."; buttons "Open sessions" / "Close" | Android |
| Success snackbar | "Restored to checkpoint" | CLI |
| Already pending | snackbar and state description "Checkpoint restore already in progress" | CLI |
| Action row CDs | "Restore checkpoint", "Edit user message"; tooltips "Restore checkpoint", "Edit message and restart from this point" | desktop |
| Menu items | "Restore checkpoint", "Edit and restart from here" | desktop / Android |
| Header overflow | "Restore checkpoint…" | Android (DS-A4) |
| C3 title | "Restore to Checkpoint" | CLI |
| C3 supporting | "Choose a message to restart from." | Android |
| C3 busy | "Wait for the current run to finish before restoring" | CLI |
| C3 empty title, no history | "No checkpoints available" | CLI |
| C3 empty title, history but no row | "No checkpoints to restore" | CLI |
| C3 empty body, history but no row | "Only messages that are not part of a compacted summary can be restored." | Android |
| C3 load error title | "No checkpoint data available" + button "Retry" | CLI / Android |
| C3 empty body, not a repo | "Checkpoints are recorded only when the workspace is a Git repository. Run git init in this folder to start recording them." | Android |
| C3 empty body, no git | "Checkpoints need Git in Termux. Install it with pkg install git, then start a new turn." | Android |
| C3 empty body, repo ok | "A checkpoint is recorded at the start of each turn. Send a message to create the first one." | Android |
| C3 empty body, unknown | "Checkpoints are recorded at the start of each turn when the workspace is a Git repository and Git is installed in Termux." | Android |
| C3 copy-command buttons | "Copy command" (copies `git init` or `pkg install git`) | Android |
| C3 row CD | "Restore to %1$s, %2$s" (preview, relative time) | Android |

### C.8 States

| Surface | State | Behaviour |
| --- | --- | --- |
| C1/C2 | idle | as wireframes; confirm enabled if the kernel is connected |
| C1/C2 | kernel down / reconnecting | error line "Cline Hub is not connected." (or "Reconnecting..." in `onSurfaceVariant`), confirm disabled, choice still editable; re-enables on connect |
| C1/C2 | session became busy while open (e.g. a scheduled run, or Stop pressed) | confirm disabled, error line "Wait for the current turn to finish before undoing" |
| C1/C2 | reopened after a workspace refusal | error line = kernel text, "Restore chat only" selected (C.6 step 6) |
| C1/C2 | message lost its checkpoint (history refreshed) | C1: dialog closes with snackbar "Could not restore checkpoint."; C2: switches to the no-checkpoint text |
| message row | pending / draining / error / success | C.5 "After confirm" |
| message row | lost reply | the pending icon returns to normal; the "Restore status unknown" `AlertDialog` opens (1.6) unless recovery found the new session, then the success path runs |
| C3 | loading | `LinearProgressIndicator` under the title while `session.get`/`session.messages` refresh |
| C3 | load error | EmptyState inside the sheet: icon `error` in `error`, "No checkpoint data available" (CLI), body = kernel message (`bodySmall`), `FilledTonalButton` "Retry" |
| C3 | empty, no history | EmptyState: icon `history`, "No checkpoints available" (CLI), body by diagnosis (C3.5), optional "Copy command" |
| C3 | empty, history but no restorable row | EmptyState: icon `history`, "No checkpoints to restore" (CLI), body "Only messages that are not part of a compacted summary can be restored." |
| C3 | populated | list |
| C3 | busy session | rows disabled, supporting line replaced by "Wait for the current run to finish before restoring" (CLI) |
| C3 | kernel down | rows from cache stay visible but disabled; KernelStrip at the top of the sheet |
| all | first run | the first message of a new session shows no undo icon until its checkpoint arrives (C.4 rule 8); no coach mark |
| all | permission missing | only reachable through "Start kernel" → Kernel setup (1.6) |

**C3.5 empty-state diagnosis** (only for "No checkpoints available"): when the sheet
opens with an empty history, run RUN_COMMAND `git -C <cwd> rev-parse
--is-inside-work-tree` (background, 10 s): `err == 150` or errmsg "No such file" → "no
git"; `exitCode == 128` → "not a repo"; stdout `true` → "repo ok"; anything else or
timeout → "unknown". If the RUN_COMMAND permission is missing, use "unknown" without
prompting.

### C.9 Edge cases (from the kernel and the recovery matrix)

- **Restore is not idempotent:** a retry after an unknown outcome could create two
  sessions and run `git reset --hard` twice. A `hub_command_timeout` is an unknown
  outcome too: the Hub stops waiting but the handler keeps running. Hence the lost-reply
  recovery (1.6), no automatic resend and no one-tap retry.
- **Workspace changed outside Cline** since the checkpoint:
  - *uncommitted* edits and untracked files: "Restore chat and workspace" discards them
    (`git reset --hard` + `git clean -fd`); the warning line covers it;
  - *committed* changes (the agent or the user committed after the checkpoint, common
    since Cline sessions often end with a commit), a rebase or a branch switch: the
    kernel **refuses** the workspace restore ("Cannot restore the workspace: …") rather
    than knock commits off the branch; C.6 step 6 reopens the dialog with "Restore chat
    only".
- **Workspace no longer a Git repository** (`.git` removed, folder deleted): "<cwd> is
  not a git repository"; C.6 step 6 offers chat only.
- **Untracked files:** checkpoints with a third (untracked) parent restore them; older
  checkpoints leave untracked files alone (kernel behaviour; nothing to show).
- **Compacted turns:** after auto-compaction, earlier messages may be folded; their
  actions disappear (C.4 rule 7) and a stale dialog shows the kernel's "Run N is folded
  into a compacted message…" error under the row.
- **Session not live after a Hub restart:** `session.restore` reads the persisted session,
  so it works without resuming first; no `session_not_found` expected (open question
  Q4 confirms).
- **Scheduled sessions** (`metadata.scheduleId`): allowed. A revert is a normal
  interactive session without the source metadata (desktop). An edit copies the source
  metadata, `scheduleId` included (desktop `forkMetadata` spreads it), so the edited
  session lists under the same schedule.
- **Subagent sessions:** actions hidden for messages in a subagent view (desktop shows
  versioning actions only on top-level transcript rows).
- **ask_question contribution:** re-sent on restore (1.5.2), otherwise the restored
  session silently loses the tool.
- **Credentials:** `sessionConfig.apiKey` is never sent. On a kernel without K1 the split
  path keeps the stored key working (1.5.1); OAuth providers work on both paths.
- **Process death while pending:** the pending request in `SavedStateHandle` triggers the
  lost-reply recovery on the next connect (1.6).

### C.10 Accessibility

- Dialog opens with focus on the title (M3 default `paneTitle`); TalkBack reads title,
  preview, the selected option ("Restore chat and workspace, selected, 1 of 2"), the
  consequence text, then the buttons. Changing the option announces the new consequence
  text (polite live region on that `Text`).
- The warning line has a leading `warning` icon with `contentDescription` "Warning"
  *(Android)* and `warning.color` text (DS §1.8 pairs on `surfaceContainerHigh`).
- The destructive confirm is never the initially focused control (DS §3.4); initial
  focus after the title goes to the selected radio row.
- Busy action-row icons stay focusable and read "Restore checkpoint, Wait for the current
  turn to finish before undoing" (C.3); a pending icon reads "…, In progress"; the error
  under the row is a polite live region.
- C3 rows expose the custom action "Restore to this message" *(Android)*; the newest row
  receives accessibility focus when the sheet opens.
- All targets ≥ 48dp; the radio rows are full-width.

### C.11 Acceptance checklist (S25U)

Setup as A.11 (`git init` workspace, `pkg install git`). Use a provider with a **stored
API key** (for example Anthropic added in Providers), not only the Cline account, so the
credential path of 1.5.1 is exercised.

- [ ] Send three prompts that each create or edit a file. Each of your messages shows the
      `undo` icon once its turn starts (TalkBack: "Restore checkpoint").
- [ ] While a turn runs, and again while "Stop agent" is pending, the undo and edit icons
      look disabled; tapping one shows "Wait for the current turn to finish before
      undoing"; TalkBack reads that reason; the long-press items are disabled.
- [ ] Revert on the 2nd message with "Restore chat and workspace": the dialog shows the
      preview, the desktop text and the git warning; tapping Revert closes the dialog at
      once and the undo icon spins; the chat moves to a new session containing messages
      1–2 (no reply to 2); files from turn 3 are gone (`ls` in Termux); snackbar
      "Restored to checkpoint"; back from the chat does not return to the old session;
      the old session is still in the drawer.
- [ ] In the reverted session, send a prompt: the reply arrives (no `Unauthorized`), on
      the kernel path the app selected (atomic with K1, split without).
- [ ] Revert with "Restore chat only": the transcript rewinds, the files of turn 3 stay.
- [ ] Edit on the 2nd message with workspace: a new session holds message 1 and its
      reply, **keeps the source session's title** in the drawer; the composer contains
      message 2's text (without `<user_input>` wrapper), focused with the keyboard up;
      files are as after turn 1; no snackbar. Sending it gets a reply.
- [ ] Edit in a workspace that is not a Git repository: the dialog shows only the
      no-checkpoint text; Continue creates the fork with message 2 in the composer.
- [ ] Header overflow "Restore checkpoint…" lists the three messages, newest focused,
      each with its checkpoint's time; tapping one opens "Edit and restart from here?";
      Cancel returns to the sheet; Continue closes both, shows "Restoring to checkpoint
      (chat + workspace)...", then "Restored to checkpoint", and the composer holds that
      message's text.
- [ ] In Termux run `git commit -am wip` after turn 2, then Revert on message 2 with the
      workspace option: the dialog reopens with "Cannot restore the workspace: 1 commit
      was added …" and "Restore chat only" selected; Revert again succeeds and the commit
      is still on the branch (`git log`).
- [ ] In Termux `mv .git ../demo-git.bak`, then Revert with workspace: the dialog
      reopens with "… is not a git repository" and only "Restore chat only"; move `.git`
      back afterwards.
- [ ] In a non-Git workspace the sheet says "No checkpoints available" with the `git init`
      explanation; with git uninstalled (`pkg uninstall git` in a test setup) it shows the
      `pkg install git` text.
- [ ] Stop the Hub with the dialog open: confirm disables with "Cline Hub is not
      connected."; start it: confirm re-enables.
- [ ] Kill the Hub in Termux (`pkill -f entry.js`) right after tapping Revert: the app
      reconnects after `hub start` and either lands on the new session or shows "Restore
      status unknown"; it never restores twice.
- [ ] Debug build with `SESSION_RESTORE_TIMEOUT_MS` overridden to 50 ms on a large
      workspace: the Hub answers `hub_command_timeout`; the app runs the lost-reply
      recovery and lands on the new session once it appears (or shows "Restore status
      unknown"); there is no retry button and only one new session exists.
- [ ] Rotate while the undo icon spins: the spinner persists and the flow completes.
      Kill the app process while it spins and reopen: the recovery finds the new session.
- [ ] Font scale 2.0 × in-app 20: the dialog text scrolls; buttons stay visible.
- [ ] Light/dark, dynamic colour on/off: "Revert"/"Continue" in `error`; the dialog icon
      in `secondary`; the warning in `warning.color`; Accessibility Scanner clean.
- [ ] Tab S7+: dialogs centred at ≤ 560dp; the sheet centred at ≤ 640dp.

---

## D. All new strings (`res/values/strings.xml` keys)

Desktop/CLI strings are verbatim; *(Android)* strings are new. Plurals marked (p).

```
changes_title                    Uncommitted changes
changes_files_badge              Files: %1$d
changes_empty                    No file changes in this session yet.
changes_empty_body               File edits Cline makes in this session appear here.          (Android)
changes_no_hunks                 No hunk details available.
changes_summary (p)              %1$s across %2$d file / %1$s across %2$d files               (Android)
changes_truncated_notice         Only the last 30 edits to a file are shown.                  (Android)
changes_loading                  Loading changes...                                           (Android)
changes_load_error               Could not load changes.                                      (Android)
action_retry                     Retry                                                        (Android)
action_back                      Back                                                         (Android; back-arrow CD of the copied PageScaffold, DS-A10)
changes_copy_path_cd             Copy file path for %1$s
changes_copy_path_tooltip        Copy file path
changes_copied_path_cd           Copied file path for %1$s                                    (Android)
changes_clip_label               File path                                                    (Android; ClipDescription label)
changes_path_copied              File path copied                                             (Android)
changes_copy_failed              Copy failed. The file path could not be copied to the clipboard.
changes_file_actions_cd          File actions for %1$s                                        (Android)
changes_share_diff               Share diff                                                   (Android)
changes_share_all                Share all changes                                            (Android)
changes_share_chooser            Share changes                                                (Android)
changes_share_subject            Changes in %1$s                                              (Android)
changes_expand_all               Expand all                                                   (Android)
changes_collapse_all             Collapse all                                                 (Android)
changes_expand                   Expand                                                       (Android)
changes_collapse                 Collapse                                                     (Android)
changes_show_more (p)            Show %1$d more line / Show %1$d more lines                   (Android)
changes_close_cd                 Close diff view
changes_open_full                Open full screen                                             (Android)
changes_header_a11y              %1$s, %2$d additions, %3$d deletions, %4$s                   (Android)
changes_updated (p)              Changes updated: %1$d file / Changes updated: %1$d files     (Android)
diff_line_added                  Added line %1$d: %2$s                                        (Android)
diff_line_added_nonum            Added line: %1$s                                             (Android)
diff_line_removed                Removed line: %1$s                                           (Android)
diff_line_context                Unchanged line: %1$s                                         (Android)
diff_hunk_group                  Change %1$d of %2$d in %3$s                                  (Android)
tool_status_running              Running
tool_status_completed            Completed
tool_status_failed               Failed
tool_status_approval             Approval required
tool_status_rejected             Rejected                                                     (Android)
tool_status_detached             Still running in the background                              (Android)
tool_status_no_result            No result                                                    (Android)
tool_status_interrupted          Interrupted                                                  (Android)
tool_status_line                 %1$s · %2$s                                                  (Android)
tool_overline_command            Command                                                      (Android)
tool_overline_output             Output
tool_overline_details            Details                                                      (Android)
tool_overline_input              Input
tool_overline_error              Error                                                        (Android)
tool_proceed                     Proceed while running
tool_proceed_error               Could not detach the running command.
tool_proceed_finished            The command finished before it could be detached.
tool_stop_agent                  Stop agent
tool_waiting_output              Waiting for output...                                        (Android)
tool_no_output                   No output                                                    (Android)
tool_output_truncated_head       [Earlier command output truncated]
tool_copy_log_path               Copy log path                                                (Android)
tool_no_result_banner            The run ended before this tool reported a result.            (Android)
tool_interrupted_banner          The Cline Hub restarted while this tool was running.         (Android)
tool_not_found                   This tool call is no longer in the session.                  (Android)
tool_back_to_conversation        Back to conversation                                         (Android)
tool_view_details                View details                                                 (Android)
tool_copy_output                 Copy output                                                  (Android)
tool_copy_command                Copy command                                                 (Android)
tool_copy_input                  Copy input                                                   (Android)
tool_share_output                Share output                                                 (Android)
tool_wrap_lines                  Wrap lines                                                   (Android)
tool_view_all_changes            View all changes                                             (Android)
tool_more_options                More options                                                 (Android)
tool_copied_output               Copied output                                                (Android)
tool_copied_command              Copied command                                               (Android)
tool_copied_input                Copied input                                                 (Android)
tool_copied_log_path             Copied log path                                              (Android)
tool_filter_all                  All                                                          (Android)
tool_filter_stdout               stdout                                                       (Android)
tool_filter_stderr               stderr                                                       (Android)
tool_filter_all_cd               Show all output                                              (Android)
tool_filter_stdout_cd            Show stdout only                                             (Android)
tool_filter_stderr_cd            Show stderr only                                             (Android)
tool_filter_heading              Show                                                         (Android; height-compact overflow)
tool_output_cd                   Command output
tool_scroll_latest               Scroll to latest output                                      (Android)
tool_close_cd                    Close tool details                                           (Android)
tool_media_unavailable           Media unavailable                                            (Android)
tool_in_progress_state           In progress                                                  (Android)
tool_announce_finished           Command finished                                             (Android)
tool_announce_failed             Command failed                                               (Android)
tool_announce_detached           Command detached                                             (Android)
hub_draining_wait                Cline Hub is restarting. Waiting...                          (Android)
checkpoint_revert_title          Revert to this checkpoint?
checkpoint_revert_confirm        Revert
checkpoint_edit_title            Edit and restart from here?
checkpoint_edit_confirm          Continue
action_cancel                    Cancel
checkpoint_restore_to            Restore to: "%1$s"
checkpoint_opt_workspace         Restore chat and workspace
checkpoint_opt_workspace_detail  Rewind conversation and reset files
checkpoint_opt_chat              Restore chat only
checkpoint_opt_chat_detail       Rewind conversation, keep current files
checkpoint_git_warning           This runs git reset --hard and git clean -fd in the workspace.
checkpoint_warning_cd            Warning                                                      (Android)
checkpoint_revert_text_ws        Workspace files and conversation history after this point will be discarded. This cannot be undone.
checkpoint_revert_text_chat      Conversation history after this point will be discarded. Workspace files are kept as they are now.   (Android)
checkpoint_edit_text_ws          This creates a new session and restores the workspace to its checkpoint before placing this message in the composer. Workspace and conversation changes after this point will be discarded.
checkpoint_edit_text_chat        This creates a new session and places this message in the composer. Workspace files are kept as they are now.   (Android)
checkpoint_edit_text_none        This creates a new session from the conversation before this message and places the message in the composer. No checkpoint was recorded for this message, so workspace files stay as they are.   (Android)
checkpoint_history_note          The current session stays in history.                        (Android)
checkpoint_busy                  Wait for the current turn to finish before undoing
checkpoint_restore_error         Could not restore checkpoint.
checkpoint_edit_error            Could not restart from this message.
checkpoint_split_partial         The workspace was restored, but the new session could not start: %1$s   (Android)
checkpoint_status_ws             Restoring to checkpoint (chat + workspace)...
checkpoint_status_chat           Restoring to checkpoint (chat only)...
checkpoint_failed                Checkpoint restore failed: %1$s
checkpoint_failed_no_result      Checkpoint restore failed: no result returned.
checkpoint_lost_title            Restore status unknown                                       (Android)
checkpoint_lost_text             The connection dropped before the restore finished. Check the session list before trying again.   (Android)
checkpoint_open_sessions         Open sessions                                                (Android)
action_close                     Close
checkpoint_restored              Restored to checkpoint
checkpoint_in_progress           Checkpoint restore already in progress
checkpoint_action_restore        Restore checkpoint
checkpoint_action_edit_cd        Edit user message
checkpoint_action_edit_tooltip   Edit message and restart from this point
checkpoint_menu_edit             Edit and restart from here                                   (Android)
checkpoint_header_menu           Restore checkpoint…                                          (Android)
checkpoint_picker_title          Restore to Checkpoint
checkpoint_picker_supporting     Choose a message to restart from.                            (Android)
checkpoint_picker_busy           Wait for the current run to finish before restoring
checkpoint_picker_empty          No checkpoints available
checkpoint_picker_no_rows        No checkpoints to restore
checkpoint_picker_no_rows_body   Only messages that are not part of a compacted summary can be restored.   (Android)
checkpoint_picker_load_error     No checkpoint data available
checkpoint_picker_not_repo       Checkpoints are recorded only when the workspace is a Git repository. Run git init in this folder to start recording them.   (Android)
checkpoint_picker_no_git         Checkpoints need Git in Termux. Install it with pkg install git, then start a new turn.   (Android)
checkpoint_picker_repo_ok        A checkpoint is recorded at the start of each turn. Send a message to create the first one.   (Android)
checkpoint_picker_unknown        Checkpoints are recorded at the start of each turn when the workspace is a Git repository and Git is installed in Termux.   (Android)
checkpoint_picker_row_cd         Restore to %1$s, %2$s                                        (Android)
checkpoint_picker_row_action     Restore to this message                                      (Android)
```

KernelStrip strings come from DS §1.6 and are not duplicated here.

---

## E. Cross-screen acceptance (run once after A, B and C pass)

- [ ] Unit tests: `SessionDiffTest` (desktop `session-diff.test.ts` cases ported),
      `LineDiffTest`, `UnifiedDiffTextTest`, `AnsiParserTest` (every row of the B.4
      table, split escapes, `\r` overwrite), `TerminalBufferTest` (head cap and
      markers, gapped-replay detection), `CheckpointIndexTest` (run spans incl.
      tool-result-only user messages and a compaction summary, `hasExactCheckpoint` vs
      `checkpointAtOrBefore`, `restorableMessages` incl. a turn whose own snapshot was
      skipped but an earlier one exists, `trimMessagesToCheckpoint`,
      `trimMessagesBeforeUserRun`, `createRestoredCheckpointMetadata`),
      `HubPayloadsTest.sessionRestore` / `sessionRestoreWorkspaceOnly` /
      `sessionCreateRestored` / `restoreMetadata` against fixture `07` (no `apiKey` key
      anywhere; the edit metadata carries the source title).
- [ ] Reducer tests replay fixture `02` and assert the tool detail state for
      `call_mock_run_1` (two updates, first empty, `success: true`).
- [ ] No string literal in these composables (lint `HardcodedText` = error).
- [ ] No `Color(0x…)` outside `ui/theme/` (DS §11).
- [ ] Predictive back from Tool detail and Changes previews the chat underneath on the
      S25U (Android 16) and completes or cancels cleanly.
- [ ] With "Remove animations" on: no highlight flash, no cursor blink, static spinners
      replaced per DS §6.
- [ ] Screenshots of every state in this spec (light and dark) saved under
      `screenshots/tier3/` with `./scripts/screenshot.sh`, never during a port
      candidate's device steps or the owner's manual test (CLAUDE.md hard rule).

---

## F. Open questions

| # | Question | Experiment / decision | Blocks |
| --- | --- | --- | --- |
| Q1 | Record fixture `07-checkpoint-restore.jsonl` against a provider with a **stored API key**: `session.create` with `checkpoint.enabled: true` in a **Git** workspace, two turns, `session.get` (shape of `snapshot.checkpoint.history`), `session.restore` with `workspace` true and false, `omitCheckpointMessageFromSession: true`, the `session.created` event, the new session id, one prompt in the restored session (authenticates only with K1), the split path (`restore.messages:false` reply `{checkpoint}`, then `session.create` with `metadata.checkpoint` carried: does the kernel keep that key?), a `hub_command_timeout` with a tiny `timeoutMs`, and a commit after turn 2 (the "Cannot restore the workspace" text). Confirms sections 0.3 and 1.5 and corrects `docs/08`. | `tools/hub-fixtures/record.ts --only 07` with `git init` in the scratch workspace | C |
| Q2 | **Answered from the source (2026-10-03), decision pending:** a restore without `apiKey` yields `apiKey: ""`, which beats the stored key for every non-OAuth provider, so the first turn fails `Unauthorized` (1.5.1). The spec binds to kernel fix K1 (port change `?? ""` → `?? undefined` in `handleSessionRestore`) with the split path for kernels without it. Owner: accept K1 into the port and name the release that carries it (sets `MIN_PORT_RELEASE_RESTORE`); if K1 is rejected, the constant stays `null` and the split path is the only path. | owner (port maintainer) | C (atomic path only) |
| Q3 | Does `detachable` ever become `true` on the device? `bash.ts` needs `processStartTokenProbe(pid)` (reads `/proc/<pid>/stat`) to succeed under Termux/bun-android-ffi. If it never flips, "Proceed while running" never shows on Android. | S25U: run a 60 s loop, watch `tool.updated.update.detachable` | B (proceed only) |
| Q4 | `session.restore` on a session that is not live after a Hub restart: does `ctx.sessionHost.getSession` read the persisted record (expected) or answer `session_not_found`? | part of fixture `07` (`hub stop/start` before restore) | C |
| Q5 | Default option in C1/C2: the spec preselects "Restore chat and workspace" (desktop parity: desktop always restores the workspace). The CLI preselects "Restore chat only" (safer). Owner decision. | owner | C |
| Q6 | DS-A1..A10 need the owner's acceptance and an edit of `design/design-system.md`. | owner / design-system author | A, B, C |
| Q7 | DS ConversationHeader shows the diff chip only when additions ≠ 0 (desktop quirk), so a deletions-only session cannot open Changes. Keep parity or show when additions + deletions > 0? | owner | A |
| Q8 | "Share file" (the full current file contents, not the diff) would need RUN_COMMAND `cat <path>` (100 KB result cap) or `termux-open --send`, and `termux-open` from the background runner is blocked (open question 5 in `docs/05`). Out of scope for v1 unless the owner wants it. | owner | — |
| Q9 | Does `session.updated` carry the refreshed `snapshot.checkpoint.history` right after the checkpoint is written at the start of a turn, or only at the end? If only at the end, the undo icon appears late (C.4 rule 8); fall back to a `session.get` on `iteration.started {iteration:1}`. | fixture `07` | C (timing only) |
| Q10 | Syntax highlighting in diffs and tool output (the desktop's `@pierre/diffs` highlights) is not in v1 (DS #ToolFileDiff). Confirm it stays out, or pick a Kotlin highlighter later. | owner | — |
| Q11 | A `/undo` slash command in the composer (the CLI has it) would be a third entry to C3; it belongs to the mentions/slash spec. | slash-menu spec author | — |
