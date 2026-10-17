# Screen spec: Composer, approvals and questions

Status: design-phase contract, 2026-10-03, revised the same day after a three-reviewer
pass (§11). Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`).
Builds on `design/design-system.md` (cited as **DS §n** / **DS #n** for component
inventory rows) and does not redefine anything it defines. Kernel names are quoted from
`docs/03-feature-matrix.md` (**FM**, including its "Corrections from recorded traffic"
section), `docs/02-kernel-contract.md` (**KC**, recovery matrix = **KC-R**),
`docs/05-open-questions.md` (**OQ n**) and the recorded traffic in
`docs/08-hub-fixtures.md` (**FX**). The sibling spec `design/screens/settings-providers.md`
is cited as **SP** (it owns the model picker sheet and reasoning menu, SP S8).

Screens in this spec:

| # | Screen / surface | Host | Tier (FM) |
| --- | --- | --- | --- |
| C1 | Composer (text, attachments, Send/Stop, model chip, reasoning chip, Plan/Act, usage ring) + its attach sheet and context-usage sheet | bottom of `chat/{sessionId?}` (Home = welcome variant, thread = conversation variant) | 1 / 2 |
| C2 | Prompt queue strip + Prompt queue sheet | docked on C1; `ModalBottomSheet` | 2 |
| C3 | Tool approval sheet + approval banner | `ModalBottomSheet` over the chat; banner docked on C1 | 1 |
| C4 | Ask-question sheet + question banner | `ModalBottomSheet` over the chat; banner docked on C1 | 2 |
| C5 | Slash menu (`/team`, skills, workflows; no `/fork`, A19) | suggestion panel docked on C1 | 3 |
| C6 | Mention menu (`@files`) | suggestion panel docked on C1 | 3 |
| C7 | Voice input entry | mic button inside C1's field row | 4 (FM "Voice input") |
| C8 | Welcome setup notice (referenced by SP §0.7 as "composer spec") | docked above C1 on Home only | 2 |

Not in this spec: the transcript rows that show approvals and questions after they are
answered (Chat thread spec), the model picker sheet and reasoning menu contents (SP S8),
the workspace picker (SP S9), system notifications and the foreground service (Notifications
spec; this spec only defines the deep-link contract they use, §0.6), the Kernel status
sheet and Kernel setup (Kernel spec).

## Desktop and kernel sources read

All desktop paths under `~/workspace/cline-termux-v3-phone/apps/examples/desktop-app/webview/`
unless noted.

- `components/views/chat/chat-input-bar.tsx`, the whole file: `ChatInputBarImpl`
  (placeholders, `isBusy`/`canAbort`/`canSend`/`hasDraft`, Enter/Shift+Enter/Esc,
  steer-first on empty Enter, IME `isComposing` guard, `getActiveMention`,
  `getActiveSlash`, slash/mention popovers and their copy, 120 ms mention debounce,
  `limit: 10`, slash cache "stale-while-revalidate", `BUILTIN_SLASH_COMMANDS`,
  `buildUserInstructionSlashCommands`, `EFFORT_LEVELS`, `resolveEffortIndex`,
  `effortLabel`, reasoning seeding per provider, image-capability checks, unsupported
  images toast and inline error, "Add a message to go with your attachments",
  attachments chips, speech-input wiring and streaming-transcript insertion rules,
  `PROMPT_INPUT_*_ROWS`) and `ModelSelector` (busy-disabled trigger, ≤560px icon-only
  "Model and provider" variant, "Set up another provider").
- `components/views/chat/token-usage-ring.tsx`; `components/views/chat/chat-messages.tsx`
  (placement of `ToolApprovalPanel` and `AgentAskQuestion` after the transcript,
  per-request pending/error maps, fallback errors "Could not submit decision." /
  "Could not submit answer.", pruning on resolve); `components/views/chat/messages/tool-approval-panel.tsx`
  (`formatApprovalTimestamp` "Pending now", `formatApprovalInput` pretty JSON,
  "Request <id> · Iteration <n>").
- `components/ai-elements/speech-input.tsx` (labels "Record speech" / "Stop recording",
  pulse while listening, spinner while processing); `components/views/settings/voice-input-view.tsx`
  (copy only).
- `hooks/use-chat-session.ts` (send path with `delivery: "queue"` when busy,
  `respondToolApproval`, `answerAskQuestion`, `steerPromptInQueue` /
  `updatePromptInQueue` / `removePromptInQueue`, `summarizeSessionUsage` = latest request
  for context, summed cost), `hooks/chat-session/types.ts` (`AskQuestionRequestItem`,
  `PromptInQueue`), `hooks/chat-session/attachments.ts`, `lib/image-attachments.ts`,
  `lib/model-selection.ts`, `lib/session-status.ts`, `app/page.tsx` (`ChatInputBar`
  props wiring, `handleModeToggle`, fork entry points).
- `~/workspace/cline-termux-v3-phone/sdk/packages/ui/components/`: `agent-composer.tsx`
  (variants, paddings, send/stop styles), `agent-prompt-queue.tsx` (whole file),
  `agent-approval-card.tsx`, `agent-ask-question.tsx` (letter keys, single-choice
  answers on tap, multiple + Submit, custom answer, keyboard handling),
  `agent-context-usage.tsx` (thresholds, segmented bar, rows, `formatCompactTokens`).
- `apps/examples/desktop-app/sidecar/chat-session.ts` (`expandRuntimeSlashCommand`,
  `rewriteDesktopTeamPrompt`, built-in names),
  `apps/cli/src/tui/commands/slash-command-registry.ts` (`expandUserCommandPrompt`).
- Kernel: `sdk/packages/core/src/runtime/turn-queue/pending-prompt-service.ts` (queue
  ordering, steer semantics, abort discards a queue-initiated turn's queue),
  `core/src/hub/server/handlers/session-handlers.ts` (pending-prompt commands),
  `approval-handlers.ts` (re-issue on subscribe, `approval_not_found`, cancel publishes
  `approval.resolved {cancelled:true}`), `capability-handlers.ts` (`capability.resolved`,
  `ignored:true`, `capability_wrong_client`, cancellation on client disconnect and on
  `run.abort`), `hub-client-contributions.ts` (`payload {executor, args, context}`),
  `core/src/extensions/tools/schemas.ts` (`AskQuestionInputSchema`: `question`, 2–5
  `options`, no multiple-choice flag; tool input shapes for the approval preview),
  `core/src/services/workspace/mention-enricher.ts` + `runtime/host/local-runtime-host.ts`
  (the daemon resolves `@path` mentions from the prompt text itself),
  `core/src/hub/server/handlers/run-handlers.ts` (`handleSessionInput`),
  `shared/src/prompt/format.ts` (`formatUserCommandBlock`, `formatDisplayUserInput`),
  `shared/src/llms/media.ts` (image limits), `core/src/extensions/config/runtime-commands.ts`
  (`normalizeRuntimeCommandName`, skill-over-workflow collisions), `core/src/settings/types.ts`
  (`CoreSettingsItem`).
- Voice app (`~/workspace/cline-termux-voice/android/app/src/main/kotlin/dev/cline/voice/`):
  `ui/chat/Composer.kt` (container, `BasicTextField`, 5-line cap, send/stop morph,
  IME-close grace), `ui/chat/ChatBars.kt` (`NoticeBar` for offline bars), `VoiceService.kt`
  (`SpeechRecognizer` setup, RMS level mapping, error handling and restart delays).
- App code it binds to: `kernel/hub/HubSessionApi.kt` (`HubPayloads.sendInput`,
  `approvalRespond`, `capabilityRespond`, `updatePendingPrompt`, `removePendingPrompt`,
  `pendingPrompts`, `updateConnection`, `settingsList`, `runAbort`, `NewSessionSpec`,
  `sessionCreate`, `SESSION_CREATE_TIMEOUT_MS`), `kernel/termux/TermuxRunner.kt`
  (`EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"`), `session/model/Transcript.kt`,
  `session/model/SessionReducer.kt`, `kernel/termux/ClineCommands.kt`.
- Review pass (2026-10-03), additionally read: `webview/hooks/chat-session/attachments.ts`
  (`serializeAttachments`: every non-image is sent as `file.text()`),
  `webview/lib/image-attachments.ts` (`cloudImageAttachmentError`),
  `webview/app/page.tsx` `handleAttachFiles` (drop-zone path),
  `sdk/packages/ui/components/agent-approval-card.css`,
  `core/src/runtime/host/local/user-files.ts` (`MAX_USER_FILE_BYTES`, NUL check),
  `core/src/hub/server/hub-server-transport.ts` (`run.interrupted` only from
  `HubRunQueue.recoverOnStartup`; `session.send_input` bypasses the run queue),
  `core/src/extensions/config/runtime-commands.ts` (`expandSkillCommands`),
  `core/src/extensions/tools/runtime.ts` (`isSkillsToolAvailable`),
  `apps/cli/src/runtime/prompt.ts` (`shouldExpandSkillSlashCommands`).

---

## 0. Shared rules for every surface in this spec

### 0.1 Code layout

```
app/src/main/kotlin/dev/cline/termux/chat/
  composer/Composer.kt              ComposerSurface (DS #6): field row + settings row + docked stack
  composer/ComposerField.kt         state-based BasicTextField, key handling, mic slot
  composer/ComposerSettingsRow.kt   attach, Plan/Act, ModelChip, ReasoningChip, ring, send/stop; overflow rule §1.4
  composer/ComposerController.kt    draft, attachments, run phase, send pipeline entry (pure Kotlin, unit-tested)
  composer/SendPipeline.kt          slash resolution (/team, workflows, skills), queue vs direct,
                                    resume-on-session_not_found, hub_draining hold
  composer/ActiveToken.kt           ports of getActiveMention / getActiveSlash (unit tests copied from chat-input-bar.test.tsx)
  composer/AttachmentPipeline.kt    photo picker, camera, files, downscale, data URLs, limits
  composer/AttachSheet.kt           attach source sheet (§1.9)
  composer/ContextUsage.kt          ring + ContextUsageSheet + ContextUsageCalculator
  composer/SuggestionPanel.kt       shared panel for C5 and C6
  composer/SlashCommandSource.kt    settings.list + built-ins, cache
  composer/FileMentionSource.kt     RUN_COMMAND file search, cache, ranking
  composer/VoiceInputController.kt  SpeechRecognizer lifecycle (C7)
  composer/WelcomeSetupNotice.kt    C8 (DS §4.2 WelcomeSetupNotice)
  queue/PromptQueueStrip.kt, PromptQueueSheet.kt, PromptQueueController.kt
  approval/ApprovalSheet.kt, ApprovalBanner.kt, ApprovalPreview.kt, ApprovalController.kt
  question/AskQuestionSheet.kt, QuestionBanner.kt, AskQuestionController.kt
  ChatBanners.kt                    ordering of docked banners (§0.4)
```

`ChatViewModel` (Chat thread spec) owns one instance each of `ComposerController`,
`PromptQueueController`, `ApprovalController` and `AskQuestionController` per open
session and exposes their `StateFlow`s. The controllers are plain classes with injected
`HubSessionApi`, `TermuxExecutor`, `ModelSelectionStore` (SP), `ProviderCatalogRepository`
(SP) and a `Clock`, so they are unit-tested against the FX fixtures without Compose.
Code adapted from the voice app carries the DS header comment.

### 0.2 Run phase (one derivation, used by every surface here)

Desktop `ChatSessionStatus` (`starting | running | stopping | …`) mapped onto Hub truth.
`ComposerController` derives it; no composable computes it.

```kotlin
enum class RunPhase { NoSession, Idle, Starting, Running, AwaitingUser, Stopping }
```

| Phase | Entered on | Left on |
| --- | --- | --- |
| `NoSession` | Home (welcome variant), no `sessionId` yet | `session.create` reply |
| `Idle` | `session.updated` status `idle`/`completed`/`aborted`/`failed`; any `run.completed`/`run.failed`/`run.aborted`; a turn marked interrupted (below) | a send |
| `Starting` | the user sends a direct (non-queued) prompt; also while `session.create` for the first send is in flight | `run.started` whose `requestId` equals our **direct** `session.send_input` envelope `requestId` (FX: correlate by it), or an error reply |
| `Running` | `run.started` for a direct send (ours or another client's), `session.updated` status `running`, `iteration.started` | terminal `run.*` event, or status `pending` → `AwaitingUser` |
| `AwaitingUser` | `session.updated` status `pending`, or an open approval / question for this session | `approval.resolved` / `capability.resolved` / status back to `running` |
| `Stopping` | the user pressed Stop and `run.abort` was sent | terminal `run.*` event (`run.aborted` normally) |

```kotlin
val isBusy   = phase in setOf(Starting, Running, AwaitingUser, Stopping)          // desktop isBusy
val canAbort = phase in setOf(Running, AwaitingUser, Stopping) || runningAgents > 0 // desktop canAbort (+ hasRunningAgents from team.progress)
```

`run.completed` with `reason != "completed"` (e.g. `disposed`, FX "Shutdown projections")
still ends the turn (→ `Idle`) but is not success; the transcript renders it. `agent.done`
is ignored for phase changes (FX: `run.*` is authoritative).

**Queued sends emit `run.started` too.** `handleSessionInput` publishes
`run.started {requestId, clientId}` on receipt, before it looks at `delivery`
(`core/src/hub/server/handlers/run-handlers.ts:246-256`), so a `delivery:"queue"` or
`"steer"` send produces a `run.started` carrying its requestId at enqueue time, not when
the prompt runs. The controller keeps the set of requestIds it sent with a `delivery`
and ignores a `run.started` whose requestId is in that set, for both phase and turn
correlation. The real start of a queued prompt is `session.pending_prompt_submitted`
followed by `iteration.started`. Unit test: a recorded queued send must not open a turn
(FX has none yet; record one when the fixtures are regenerated).

**Interrupted turns are derived locally.** `run.interrupted {reason:"hub_restart"}` is
published only by `HubRunQueue.recoverOnStartup()` for runs admitted through
`run.enqueue` (`hub-server-transport.ts:683-697`); `session.send_input` goes straight to
`handleSessionInput` and never enters that durable queue (`hub-server-transport.ts:860-862`),
so this app's turns never receive it. The controller instead marks a turn
**interrupted** when the phase was `Starting`, `Running` or `AwaitingUser` and either
(a) the reconnect finds a new daemon (`/version` `pid` or `startedAt` changed, the same
check §0.5 uses to drop approvals), or (b) the replay carries the shutdown projection for
this session (`session.updated` with `snapshot.endedAt` set, or
`run.completed {reason:"disposed"}`). An interrupted turn → `Idle` plus the C1.7
"interrupted" handling. A real `run.interrupted` for this session is handled the same way
(no-op-safe if the turn was already marked).

### 0.3 Kernel availability (shared with the DS §1.6 kernel-connection table)

```kotlin
sealed interface KernelAvailability {
    data object Connected : KernelAvailability
    data object Connecting : KernelAvailability          // first connect, `cline hub start` running
    data object Reconnecting : KernelAvailability        // backoff loop (KC keep-alive)
    data object Unavailable : KernelAvailability         // /health refused after hub start
    data object TermuxAsleep : KernelAvailability        // /health fails, Termux resolvable
    data class Blocked(val reason: KernelBlock) : KernelAvailability // Termux missing, RUN_COMMAND permission, allow-external-apps
}
```

Provided by `KernelCoordinator` (existing). Every surface below states what it does in
each value. "Kernel down" in this document means `Unavailable`, `TermuxAsleep` or
`Blocked`.

### 0.4 The docked stack above the composer

From top to bottom, each item present only when its condition holds. The stack and the
composer move together on the IME (DS §5.7), and the ScrollToLatest FAB (DS §4.2) sits
16dp above the topmost item.

1. **Approval banner** (C3) — this session has open approvals and the approval sheet is
   collapsed.
2. **Question banner** (C4) — this session has an open question and the sheet is
   collapsed.
3. **Suggestion panel** (C5 or C6) — a slash or mention token is active.
4. **Prompt queue strip** (C2) — the session has queued prompts.
5. **Welcome setup notice** (C8) — Home only, no provider configured.
6. **Composer** (C1).

Banners 1 and 2 are mutually exclusive in practice (the agent waits on one thing at a
time); if both exist, both show, approval first.

Items 3 and 4 (and the C1.7 held-for-Hub note) are drawn **inside** `ComposerSurface`
as its docked children, so they share its one outline (C1.3); items 1, 2 and 5 are
separate surfaces with 8dp (banners) / 12dp (C8) gaps. Item 5 exists only on Home,
where item 4 cannot. In height-compact windows with the IME open, item 4 is not composed
(C1.4 step 6).

### 0.5 Recovery matrix: what this spec must surface

| KC-R / FX case | Where it surfaces | Behaviour |
| --- | --- | --- |
| Upgrade 401, stale token | nowhere | `KernelCoordinator` re-reads `production.json` once; the composer stays `Reconnecting` meanwhile |
| ECONNREFUSED on 25463 | C1 read-only state | "Start kernel" replaces the settings row (§1.6) |
| RUN_COMMAND `err != -1`, permission / allow-external-apps | C1 read-only state, C6 row | "Start kernel" routes to Kernel setup instead of starting |
| Termux not resolvable | C1 read-only state | "Start kernel" routes to the Install Termux screen |
| `hub_draining` reply on send | C1 hold state (§1.7) | prompt held, resent on `hub.drain_changed {draining:false}` |
| `session_not_found` on send | C1 resume state (§1.7) | `session.messages` → `HubSessionApi.create(<the session's NewSessionSpec> with resumeSessionId + initialMessages)` → resend |
| Turn interrupted by a daemon restart (KC-R `run.interrupted`; derived locally, §0.2) | C1 (§1.7) | phase → `Idle`; prompt put back; snackbar with "Resend" |
| `approval.requested` with nobody able to answer | C3 | re-issued on every subscribe; dedupe by `approvalId`; the notification deep link opens C3 (§0.6) |
| Daemon restarted while an approval was open | C3 | Hub resolves it as rejected on shutdown **without** an event; on reconnect, if `/version` `pid`/`startedAt` changed, drop every approval and question held for the old daemon |
| `capability.requested` (ask_question) after reconnect | C4 | the Hub cancels it when our client unregisters (`capability.resolved {cancelled:true}` in the replay); C4 closes and says so; the turn continues without the answer |
| RUN_COMMAND never returns (60 s) | C1 Start kernel, C5 workflow read, C6 search, C1 file attach | each shows its own error (strings in §8) |

### 0.6 Deep links into these surfaces

Notifications (Notifications spec) and in-app snackbars open a session with an intent /
route argument so the right sheet is up when the chat appears:

- Route `chat/{sessionId}?sheet=approval|question|queue` (navigation-compose argument
  `sheet`, nullable).
- Intent extra `dev.cline.termux.extra.SESSION_ID` + `dev.cline.termux.extra.OPEN_SHEET`
  (`"approval"` | `"question"`), mapped to that route by **both** entry points:
  `MainActivity.onCreate` when `savedInstanceState == null` (reads `activity.intent`;
  this is the cold-start path for a notification tapped while the app is not running)
  and `MainActivity.onNewIntent` (warm, single-top). After routing, the handled extras
  are removed (`intent.removeExtra(EXTRA_SESSION_ID)`, `removeExtra(EXTRA_OPEN_SHEET)`,
  then `setIntent(intent)`), so a rotation or process recreation does not reopen the
  sheet.
- If the requested approval or question no longer exists when the chat opens (answered
  elsewhere, cancelled), no sheet opens and a snackbar says "This request was already
  answered." *(Android)* (§8).

### 0.7 Foreground service hand-off

KC keep-alive: the specialUse foreground service ("Cline is working...") must be held
while a turn runs **and** while an approval or question is pending, because a frozen app
drops its socket and an open question is then cancelled by the Hub. `ApprovalController`
and `AskQuestionController` expose `hasPending: StateFlow<Boolean>`; the service owner
(Notifications spec) holds the service while `RunPhase in {Starting, Running, AwaitingUser,
Stopping} || approvals.hasPending || questions.hasPending`.

### 0.8 User content is never logged

Prompts, drafts, queued prompt text, attachment bytes and file names' contents,
approval `inputJson` (a `run_commands` input can carry exported keys), question text,
options and answers are **never** written to logcat, crash reports, analytics or the
diagnostics export. Logs and error records carry only ids (`sessionId`, `approvalId`,
`requestId`), error codes, byte sizes and counts. Every "record the error" instruction
in C1.7, C3.7 and C4.6 means exactly this. File attachments are passed to Termux via
stdin, never via command arguments (C1.9), so their content is not visible in
`/proc/<pid>/cmdline` or in Termux's plugin-error notification.

---

## C1. Composer

### C1.1 Purpose

The primary action of the app: type or dictate a prompt, attach images or text files,
pick Plan or Act, see and change the model and thinking level, see context-window
pressure, send, queue while the agent works, and stop it. One composer, two variants:
**welcome** (Home, no session yet) and **conversation** (thread).

### C1.2 Desktop equivalent and what was copied

`ChatInputBar` (`chat-input-bar.tsx`) composed from `@cline/ui` `AgentComposer*`
(`agent-composer.tsx`), with `AgentPromptQueue`, `TokenUsageRing`/`AgentContextUsage`,
`ModelSelector`, `EFFORT_LEVELS`, `SpeechInput`.

Copied: all placeholders and their precedence; the busy rules (`isBusy`, `canAbort`,
`canSend`, Send hidden while busy unless there is a draft, Stop shown while it can
abort); Enter sends / Shift+Enter newline / Esc stops / empty Enter steers the first
queued prompt (hardware keyboard); queue-while-busy (`delivery: "queue"`); the 1→2→5
line growth; image-capability gating and its two error texts; the text-required rule for
attachments; the model chip disabled while busy and icon-only on narrow widths (desktop
≤560px), and labelled with both provider and model on wide widths (desktop >560px
shows the provider select and the model select side by side); the reasoning chip disabled without reasoning support, its label rules and
per-provider memory; the usage ring thresholds, its hide-when-unknown rule and its
"latest request" metric; the usage details rows; "Attach files" behaviour with the "this
model doesn’t support images" title.

Translated: the popover selects become a bottom sheet (SP S8) and a `DropdownMenu`
(SP S8 `ReasoningMenu`); the usage popover becomes a bottom sheet; the hidden file input
becomes the attach source sheet (photo picker, camera, files); paste of images from the
clipboard becomes Compose `contentReceiver`; the always-visible desktop workspace
selector inside the composer is not repeated (the workspace chip lives on Home, SP S9).

Deliberate Android translations (implementers must not "fix" these back to desktop):

- **Send/Stop move to the settings row and the mic takes the field-row slot.** The
  desktop keeps Stop, mic and Send in `AgentComposerActions` beside the textarea and ends
  the settings row with the workspace selector + usage ring. On a phone the primary
  action belongs at the bottom edge within thumb reach, on 48dp targets (DS #6).
- **First run (no provider) intercepts Send** with the "Connect a model" snackbar, and
  the ModelChip stays **enabled** so it can open SP S8's empty state. The desktop local
  target sends anyway and shows the provider error in the transcript, and disables the
  model trigger when `providers.length === 0`. A failed first prompt is the worst
  first-run moment (the reason `welcome-setup-notice.tsx` exists), so the app routes the
  user to setup instead.
- **The unsupported-images rule applies to every image ingress** (attach sheet,
  `contentReceiver` paste, drag and drop). The desktop applies it in the composer's
  `handleAttachFiles` (file input and paste) but its page-level `AttachmentDropZone`
  handler does not check image support; one rule is simpler and avoids a chip that can
  only produce an error.
- **Image size limits apply locally** (desktop applies `cloudImageAttachmentError` only
  for the cloud target), because each image travels in one WebSocket frame to the device
  Bun (OQ 11).

Not copied: the cloud execution target (repository required, cloud models), the pull
request bar, `#PR` references (the welcome placeholder keeps the desktop words verbatim,
DS #6), the workspace/branch selector in the settings row.

### C1.3 Anatomy (DS #6)

```
ComposerSurface  Surface(shape = shapes.large, color = surfaceContainerHigh,
                         border = 1dp outlineVariant; 1dp primary while the field is focused)
                 — the ONLY rounded, bordered surface; everything below is a child
├─ DockedChildren       in §0.4 order, each only when present: SuggestionPanel (C5, C6),
│                       PromptQueueStrip (C2), held-for-Hub note; RectangleShape, no own
│                       border, each followed by a 1dp outlineVariant HorizontalDivider
├─ AttachmentChips      FlowRow of InputChip, 8dp gaps, 12dp top padding (only with attachments)
├─ InlineError          bodySmall error, 16dp horizontal, liveRegion Polite (only with unsupported images)
├─ FieldRow             Row(Modifier.heightIn(min = 56.dp), padding start 16dp / end 4dp)
│   ├─ BasicTextField   bodyLarge onSurface, cursor primary, weight 1, vertical padding 16dp
│   └─ MicSlot          IconButton 48dp (C7) — only when voice input is available
└─ SettingsRow          Row(Modifier.heightIn(min = 48.dp), padding horizontal 4dp,
                            verticalAlignment = CenterVertically)
    ├─ LeadingLane      attach · Plan/Act · ModelChip · ReasoningChip     (§1.4 overflow rule)
    └─ TrailingGroup    UsageRing · [Stop] · [Send]                         (never scrolls)
```

No container in this spec that holds scalable text has a fixed height (DS §8.1.8:
"Rows use `heightIn(min = …)`"). The 40dp `FilledIconButton`s (Send, Stop) and 48dp
`IconButton`s keep their size and are vertically centred when the row grows taller with
font scale; `SegmentedButton` and `AssistChip` grow with their text (M3 default). The
docked stack (§0.4) keeps its order: the approval/question banners (C3/C4) and the C8
notice remain **separate** surfaces above `ComposerSurface` with the 8dp/12dp gaps
specified there; the queue strip, suggestion panel and held note are inside it, so the
outline drawn in the wireframes is one outline around strip + field + settings row.

- Horizontal placement: compact 16dp screen gutters; medium/expanded 24dp; the surface
  is centred in the 840dp transcript column (DS §3.3). Bottom inset: DS §5.7
  (`WindowInsets.ime.union(bottomSafe)`), 8dp between the surface and the inset.
- **Text field** (state-based `BasicTextField(state: TextFieldState)`, foundation 1.7+):
  ```kotlin
  BasicTextField(
      state = draft,
      lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = minLines, maxHeightInLines = maxLines),
      readOnly = readOnly,            // §1.6: kernel down, starting kernel, resuming, voice active
      enabled = !sessionLoading,      // §1.6: only the Loading (session switching) state
      textStyle = MaterialTheme.typography.bodyLarge.copy(color = onSurface),
      cursorBrush = SolidColor(primary),
      decorator = { inner ->
          Box {
              if (draft.text.isEmpty()) Text(placeholder, style = bodyLarge, color = onSurfaceVariant,
                                              maxLines = if (minLines == 1) 1 else 2, overflow = Ellipsis)
              inner()
          }
      },
  )
  ```
  No `TextFieldDefaults.DecorationBox` (that is the filled-field decoration and would
  need every colour overridden inside the custom surface).
  - Lines: conversation variant unfocused `minLines = 1`, focused `minLines = 2`; welcome
    variant `minLines = 2`; `maxLines = 5`, then it scrolls inside
    (desktop `PROMPT_INPUT_COLLAPSED_ROWS = 1`, `EXPANDED_ROWS = 2`, `MAX_ROWS = 5`).
    In height-compact windows (DS §5.1, e.g. S25U landscape ≈ 412dp tall) `maxLines = 3`
    *(Android)*. At font size 15 that is ≈ 24dp / 48dp minimum and ≈ 120dp maximum;
    these are expectations, not specified sizes (line height drives them, so font scale
    and CJK line heights change them).
  - `KeyboardOptions(capitalization = Sentences, imeAction = ImeAction.Default,
    autoCorrectEnabled = true)`. The soft keyboard's Enter inserts a newline; only the
    Send button sends on touch (voice app precedent; desktop Enter-to-send is kept for
    hardware keyboards, §1.8).
  - `Modifier.contentReceiver` accepts `image/*` from the clipboard and keyboards
    (Gboard stickers/screenshots): each item goes through the attachment pipeline
    (§1.9, including its image-support and 5-image checks) instead of being pasted as
    text (desktop `imageFilesFromClipboard` → `handleAttachFiles`).
- **Attach** `IconButton` (`attach_file`, 20dp icon, `onSurfaceVariant`).
- **Plan/Act**: `SingleChoiceSegmentedButtonRow` with two `SegmentedButton`s "Plan" and
  "Act" (`labelLarge`, no icons, height 40dp visible / 48dp target, DS §4.3). The
  desktop hides this control; FM says ship it as the CLI does.
- **ModelChip**: `AssistChip` with leading `memory` 18dp and the model label (DS #7).
  Icon-only form: `IconButton` with `memory` 20dp (desktop ≤560px `Cpu` button).
- **ReasoningChip**: `AssistChip` with leading `neurology` 18dp and the effort label.
  Icon-only form: `IconButton` with `neurology`.
- **UsageRing** (DS #8): `IconButton` holding a 20dp `Canvas` ring, stroke 3.5dp, round
  cap, from −90°, 65 % alpha at rest, 100 % while its sheet is open.
- **Send**: `FilledIconButton` 40dp (48dp target), `arrow_upward` 20dp,
  `containerColor = primary`, `contentColor = onPrimary`; disabled uses the M3 disabled
  colours. **Stop**: same size, `stop` 20dp, `containerColor = error`,
  `contentColor = onError`. The desktop's `bg-foreground text-background` would map to
  `inverseSurface`, which M3 reserves for snackbars and plain tooltips; it renders as a
  near-black circle beside a dynamic `primary` Send in light schemes. The error pair is
  the one Stop colour of the app (C7's recording Stop and C3's "Stop agent" use it too).
  Send ⇄ Stop morph per DS §6. While `Stopping`, Stop is disabled and shows a 16dp
  `CircularProgressIndicator` in `LocalContentColor.current` (the button's disabled
  content colour) instead of the icon.
- **Starting slot**: while `RunPhase.Starting` and there is no draft, the trailing slot
  shows a 20dp indeterminate `CircularProgressIndicator` (`onSurfaceVariant`) centred in
  48dp, `contentDescription = "Starting"` (DS §1.6 label). The desktop shows nothing in
  this window; an empty slot reads as a broken button on touch.

### C1.4 Responsive rules for the settings row

The row never wraps and never truncates the trailing group. Measured with a custom
`Layout`, in this order:

1. Trailing group width = ring (48dp, when shown) + Stop (48dp, when `canAbort`) + Send
   (48dp, when `!isBusy || canSend`) or the Starting slot (48dp).
2. Lane width = row width − trailing width.
3. **Compact width class** (< 600dp): ReasoningChip is always icon-only (desktop
   ≤560px: label `sr-only`). ModelChip shows its label (truncated to 16 graphemes with
   `BreakIterator`, DS #7, ellipsis "…") only if attach + Plan/Act + reasoning icon +
   the measured labelled chip fit the lane; otherwise icon-only. While `isBusy` the
   ModelChip is disabled but keeps whichever form the width rule chose (desktop only
   sets `disabled={isBusy}`; changing form on every send would reflow the row). On the
   S25U portrait (≈412dp) this means the model chip is normally icon-only, as on the
   desktop's narrow layout; long-press shows the full "Provider / Model" tooltip.
4. **Medium and expanded**: both chips show labels. The ModelChip label is
   "<provider name> / <model name>" (desktop >560px shows the provider select, a
   divider and the model select), max width 240dp. When it does not fit, the **model
   part is truncated first**: a `Row` of `Text("<provider> / ", Modifier.widthIn(max =
   96.dp), maxLines = 1, overflow = Ellipsis)` and `Text(model, Modifier.weight(1f,
   fill = false), maxLines = 1, overflow = Ellipsis)`, so the provider never takes more
   than 96dp and the model shrinks first. No 16-grapheme cut. Reasoning label is the
   effort word.
5. If the lane content still overflows (font scale 2.0 × in-app 20, DS §8.1.8, or
   height-compact landscape, DS §5.1), the lane becomes a horizontal `LazyRow`-free
   `Row(Modifier.horizontalScroll(...))` with a 16dp fade on its end edge
   (`drawWithContent` gradient `surfaceContainerHigh` → transparent). The trailing group
   stays pinned. This solves width only; height is solved by `heightIn(min = 48.dp)`
   on the row (§1.3), which lets the row grow with the chips' text.
6. **Height-compact windows** (DS §5.1) with the IME open *(Android)*: the field is
   capped at 2 lines (3 lines on a height-compact window without the IME, §1.3), the
   settings chip row is not composed, and the C2 queue strip is not composed (the queue
   count stays reachable: the approval/question banner, if any, still shows). The chip
   row and the strip return, and the cap goes back to 3 lines, when the IME closes or
   the window is no longer height-compact. The Samsung keyboard takes about 233 of the
   S25U's 412dp in landscape. With no approval pending this keeps at least one transcript
   row visible; with an approval pending the banner (56dp) and the composer fill the
   ≈ 136dp the keyboard leaves, so the banner stays visible and takes priority over
   transcript rows: it is the context the user is replying to, and no further layout
   change is made (lead decision 2026-10-06, docs/07 D18). The same priority holds with the
   IME closed: the docked stack is then bounded to 60 % of the chat pane, or to the whole
   pane while an approval, question or stall banner docks, so the banner is never scrolled
   out of the bounded stack. With the IME open the stack keeps that whole-pane bound while
   a banner docks (trial row 13): the app bar steps aside while typing, and an unbounded
   stack that outgrew the room above the keyboard drew the banner under the status bar;
   bounded, it stays inside the safe area and the rest scrolls inside the stack, starting
   at the composer. The A4 stall notice there takes one row of at least 56dp (icon, text,
   Keep waiting, Restart kernel side by side, `StallBanner(singleRow)`) whenever its text
   fits on one line beside the actions, as on the S25U; in narrower windows or at large
   font scales it keeps its stacked form inside the bound.

### C1.5 Wireframes

Legend: `[@]` attach, `[Plan|Act]` segmented (selected side in caps brackets),
`(M)` model chip icon-only, `(M Claude Son…)` model chip with label, `(T)` reasoning chip
icon-only, `(T Low)` with label, `o` usage ring, `[^]` Send, `[#]` Stop, `[~]` Starting
spinner, `[mic]` voice, `|` caret.

**Compact (S25U portrait, 412dp), conversation variant, idle, empty, unfocused:**

```
 ............ transcript ............                       (^) ScrollToLatest
┌──────────────────────────────────────────────┐
│ Enter your question or type / for comman… [mic]│  field row, 1 line
├──────────────────────────────────────────────┤
│ [@] [PLAN|act] (M) (T)              o   [^]  │  settings row; Send disabled
└──────────────────────────────────────────────┘
 ▔▔▔▔▔▔▔▔▔▔▔ gesture handle area (bottomSafe) ▔▔▔▔▔
```

**Compact, welcome variant (Home), with the setup notice (C8) above:**

```
            (bot illustration, sr-only heading)      (hero, Home spec)
┌──────────────────────────────────────────────┐
│ [cable] Connect a model to start building     │  C8
│ Sign in with Cline or add an API key — it     │
│ takes under a minute.                          │
│ [ Connect a model ]  Model settings            │
└──────────────────────────────────────────────┘
┌──────────────────────────────────────────────┐
│ Ask to make changes, @mention files,     [mic]│  2 lines min
│ reference #PRs, or run /commands.             │
├──────────────────────────────────────────────┤
│ [@] [plan|ACT] (M) (T)                  [^]  │  no ring in welcome
└──────────────────────────────────────────────┘
```

**Compact, focused, typing, with two image attachments and the IME open:**

```
┌──────────────────────────────────────────────┐
│ (img) screenshot-1.png  x   (img) IMG_2…  x   │  InputChips
│ Fix the layout shown in these two|       [mic]│  2..5 lines
│                                               │
├──────────────────────────────────────────────┤
│ [@] [plan|ACT] (M) (T)              o   [^]  │  Send enabled
└──────────────────────────────────────────────┘
┌──────────────────── IME ─────────────────────┐
```

**Compact, running, empty draft, two prompts queued (C2 strip docked):**

```
┌──────────────────────────────────────────────┐
│ [clock] 2 prompts queued   ·Also add t…  [↵] v│  C2 strip, min 48dp, inside the surface
├──────────────────────────────────────────────┤
│ Agent is working... submit to queue anoth…[mic]│
├──────────────────────────────────────────────┤
│ [@] [plan|ACT] (M) (T)              o   [#]  │  Stop only
└──────────────────────────────────────────────┘
```

**Compact, running, with a draft (Send queues it):**

```
│ Then run the tests too|                  [mic]│
│ [@] [plan|ACT] (M) (T)          o  [#]  [^]  │  Stop + Send
```

**Compact, kernel down (read-only):**

```
┌──────────────────────────────────────────────┐
│ Start kernel to continue.                     │  field readOnly (selectable), draft kept
├──────────────────────────────────────────────┤
│ (dot) Termux asleep              Start kernel │  TextButton replaces settings row
└──────────────────────────────────────────────┘
```

**Compact, unsupported images:**

```
│ (img) photo.jpg  x                            │
│ This model doesn’t support the attached       │  bodySmall error
│ images. Remove them or choose a model that    │
│ supports images before sending.               │
│ What is wrong in this photo?|            [mic]│
│ [@] [plan|ACT] (M) (T)              o   [^]  │
```

**Tablet (Tab S7+ landscape, expanded, list pane + detail pane), conversation, idle:**

```
┌rail┐┌── list pane 360/400dp ──┐┌──────────── detail pane ──────────────────────┐
│    ││                          ││ ............ transcript (max 840dp) ......... │
│    ││                          ││  ┌───────────────────────────────────────┐   │
│    ││                          ││  │ Enter your question or type / for     │   │
│    ││                          ││  │ commands or @ for context        [mic]│   │
│    ││                          ││  ├───────────────────────────────────────┤   │
│    ││                          ││  │ [@] [plan|ACT] (M Claude Sonnet 4.6)  │   │
│    ││                          ││  │     (T Low)                o    [^]   │   │
│    ││                          ││  └───────────────────────────────────────┘   │
└────┘└──────────────────────────┘└───────────────────────────────────────────────┘
```

(The second settings line in the drawing is wrapping of the drawing only; the row is one
line, min 48dp. On expanded widths everything fits; the model chip reads
"Anthropic / Claude Sonnet 4.6" when there is room, §1.4 step 4.) Medium (Tab S7+ portrait, 778dp)
looks the same without the list pane, with 24dp gutters.

### C1.6 States

| State | Trigger | Field | Settings row | Trailing |
| --- | --- | --- | --- | --- |
| First run (no provider) | `ProviderCatalogRepository` reports no configured provider (SP §0.7) | enabled; welcome placeholder | ModelChip label "Model" (desktop placeholder), **enabled** *(Android, §1.2)*, opens SP S8 whose empty state offers "Set up another provider" | Send enabled with a draft; tapping it does not send *(Android, §1.2)*: snackbar "Connect a model to start building" with action "Connect a model" (→ Connect a model screen). C8 is shown above on Home |
| Loading (session switching) | Chat thread spec hydrating `session.messages` | `enabled = false` (the only state that uses it), draft for the target session restored | chips disabled | Send disabled |
| Empty | draft blank, no attachments, `!isBusy` | placeholder per §1.10 | all enabled | Send disabled |
| Has draft | `draft.isNotBlank() || attachments.isNotEmpty()` | | | Send enabled |
| Attachments only | attachments, blank text | | | Send enabled; tap → snackbar "Add a message to go with your attachments. Describe what you want Cline to do with the attached files before sending." (desktop toast) |
| Unsupported images | ≥1 image chip and the model **then** changes to one with `supportsImages == false` (images are refused at attach time otherwise, §1.9 step 0; desktop `unsupportedDraftImageCount`) | inline error above the field, announced politely | attach tooltip changes | Send enabled; tap → images-unsupported snackbar, nothing sent |
| Starting | `RunPhase.Starting` | stays editable | ModelChip disabled (form unchanged, §1.4) | Starting slot, or Send if there is a draft (queues) |
| Running / AwaitingUser | phases | busy placeholder | ModelChip disabled (form unchanged); Plan/Act and reasoning stay enabled. Plan/Act applies to the next prompt (it is a `send_input` field); a reasoning change goes through `session.update_connection`, which has no running guard and mutates `session.config` in place (`session-handlers.ts:1092-1116`, `local-runtime-host.ts:1599-1626`), so it applies from the next model request, possibly inside the running turn | Stop; plus Send when there is a draft |
| Running with queue | `queue.isNotEmpty()` | busy-with-queue placeholder (hardware keyboard only, §1.10) | | |
| Stopping | `RunPhase.Stopping` | editable | | Stop disabled with spinner; Send if draft |
| Listening (C7) | voice session active | read-only, placeholder "Listening…" while empty | | Send disabled (desktop: `!speechInputActive`) |
| Transcribing (C7) | recognizer processing the last utterance | read-only, placeholder "Transcribing voice input…" | | Send disabled |
| Connecting / Reconnecting | `KernelAvailability` | editable (draft kept) | lane replaced by a status line: pulsing `warning.dot` + "Connecting..." / "Reconnecting..." (`bodyMedium onSurfaceVariant`) | Send disabled |
| Kernel down | `Unavailable` / `TermuxAsleep` / `Blocked` | `readOnly = true` (text kept, selectable and copyable, no IME; DS #6 readOnly); placeholder = the DS §1.6 line 2 ("Cline Hub is not connected." / "Start kernel to continue.") or, for `Blocked`, the Kernel setup title | row replaced by: 8dp `outline` dot + DS line 1 ("Cline Hub @unknown" / "Termux asleep") + end-aligned `TextButton` "Start kernel" | none |
| Starting kernel | the user tapped "Start kernel" | `readOnly = true` | `TextButton` shows a 16dp spinner + "Starting kernel..." *(Android)*, disabled | none |
| Resuming session | `session_not_found` on send (§1.7) | `readOnly = true` | lane disabled | Starting slot; status announced "Resuming session..." *(Android)* |
| Held for Hub restart | `hub_draining` (§1.7) | editable | lane enabled | Starting slot; inside the composer surface above the field row (C1.3 docked child) a `bodySmall onSurfaceVariant` note (up to 2 lines) "Cline Hub is restarting. Your message will be sent when it is back." *(Android)* |
| Session deleted | the open session was deleted elsewhere (`session.get` → `session_not_found` and absent from `session.list`) | composer replaced by the voice app's `NoticeBar` pattern: "This session was deleted." *(Android)* + `TextButton` "New session" *(Android desktop label "New session")* | — | — |

"Offline" for this app is the kernel being down or unreachable: the phone's own network
does not matter to the composer (the Hub is loopback). Provider-side network failures
arrive as `run.failed`/`session.notice api_error` and render in the transcript.

### C1.7 Interactions

**Send (touch).** Enabled per §1.6. On tap:

1. If `canSend` is false (empty, listening, kernel not connected) nothing happens.
2. If there are unsupported images → snackbar (§8 `composer_images_unsupported`), stop.
3. If the text is blank but attachments exist → snackbar (`composer_needs_text`), stop.
4. If no provider is configured → snackbar `composer_connect_model`, stop *(Android, §1.2)*.
5. Slash resolution (C5 §5.7): `/team …`
   is rewritten (or refused); workflows are expanded; skills are expanded or sent as typed
   depending on the `skills` tool (C5.7 step 4); anything else is sent as typed.
6. The field and chips clear immediately (desktop `setPromptInput("")`); the draft is
   kept in a `pendingSend` slot until the Hub accepts it, so a failed send restores it.
7. **Direct send** (`!isBusy`): phase → `Starting`;
   `HubSessionApi.sendInput(sessionId, prompt, mode, delivery = null, imageDataUrls, userFiles)`
   with `timeoutMs = null` (FM). On the welcome variant the Home spec first runs
   `session.create` (FM "New session") and then this call.
8. **Queued send** (`isBusy`): same call with `delivery = "queue"` (FM "Queue / steer";
   desktop `shouldQueue`). The queue strip updates from `session.pending_prompts`; the
   reply is not awaited for UI purposes. Its `run.started` is ignored (§0.2). If the
   connection reported `pending_prompts_unavailable` (C2.6), busy sends go out without
   `delivery`.
9. The keyboard stays open after an idle send (follow-ups) and closes after a busy send
   (DS §5.7.4).

**Send failure handling** (reply `ok:false`):

| `error.code` | Behaviour |
| --- | --- |
| `session_not_found` | Resume: state "Resuming session..."; `session.messages {sessionId}` → `HubSessionApi.create(spec)` (timeout `HubPayloads.SESSION_CREATE_TIMEOUT_MS` = 120 s, FX; the Hub default of 30 s is too short for a cold daemon) where `spec` is **the session's original `NewSessionSpec`** (same `workspaceRoot`/`cwd`, provider, model, mode, thinking/reasoningEffort, `enableSpawnAgent`, `enableAgentTeams`, `toolPolicies`, `contributeAskQuestion = true`; `HubPayloads.sessionCreate` always writes `checkpoint.enabled = false`, the FM correction) copied with `resumeSessionId = <old id>` and `initialMessages = <session.messages>` (KC step 7, FX `05`) → resend once. If any step fails: restore the draft, snackbar "Could not resume this session." *(Android)* with action "Retry" *(Android)*. **One spec, three uses:** Home's first `session.create`, this resume and the auto-approve recreate (FM "Auto-approve all toggle", §10 item 9) all build from the same stored `NewSessionSpec` (persisted per session by the Chat thread spec, with the latest model/thinking from SP S8 folded in), so `clientContributions` (`askQuestion`, C4) and `enableAgentTeams` (`/team`, C5) can never be lost on resume. |
| `hub_draining` | Hold the prompt (state "Held for Hub restart"); on `hub.drain_changed {draining:false}` resend once; if the socket closes instead, the normal reconnect runs and the held prompt is resent after `client.register` (at most once). The user can cancel the hold by editing the draft (the held text is put back into the field and the hold is dropped). |
| `hub_connection_closed` (command failed while disconnected) | restore the draft; snackbar "Message not sent. Cline Hub disconnected." *(Android)* with "Retry". Not retried automatically (KC: commands are not retried except idempotent reads). |
| `invalid_session_input` | cannot happen with the rules above; record the error code (§0.8: never the prompt) and restore the draft |
| anything else | restore the draft; snackbar "Message not sent. <error.message>" *(Android)* + "Retry" |

**Interrupted turn** (derived locally, §0.2: new daemon `pid`/`startedAt` after a
reconnect, or the shutdown projection for this session in the replay, while the phase
was `Starting`/`Running`/`AwaitingUser`): phase → `Idle`; if the draft is empty, put the
interrupted turn's prompt back into the field (cursor at end; the controller keeps the
last direct prompt's text in memory until the turn ends); snackbar "The run was
interrupted by a kernel restart." *(Android)* with action "Resend" *(Android)* (sends
the field content as a direct send; if the session is no longer live, the
`session_not_found` resume row above runs first). A reconnect to the **same** daemon
(unchanged `pid` and `startedAt`) never marks a turn interrupted.

**Stop.** Tap → phase `Stopping`; `HubSessionApi.abort(sessionId)` =
`run.abort {sessionId, reason:"User pressed Stop"}` (FM; replies `{applied:true}`). The
Hub also cancels pending approvals and questions (`approval.resolved {cancelled:true}`,
`capability.resolved {cancelled:true}`), which closes C3/C4. If the aborted turn was
started from the queue, the kernel discards the remaining queue
(`PendingPromptService.discardQueue`); the strip disappears via `session.pending_prompts`.
If no terminal `run.*` event arrives within the Hub's command timeout (**30 s**, A4), phase
returns to `Running` and a snackbar says "The agent did not stop. Try again." *(Android)* with
"Restart kernel"; a `run.abort` that fails shows the same at once, as the approval sheet's
"Stop agent" does. The snackbar stands only while that run does (trial row 11): it goes when
the run is over (also when another run took its place or the session was deleted), when a
later Stop's `run.abort` is accepted (a new attempt is under way, and its own failure brings
the snackbar back) and when the composer moves to another session.

**Plan / Act.** Selecting a segment sets `mode` for the next send (desktop
`handleModeToggle`; sent as `session.send_input {mode:'act'|'plan'}`, FM "Plan / Act
mode"). Not a session mutation; no kernel call on toggle. The value is per session, kept
in the controller and in DataStore `composer.mode.<sessionId>`; a new session starts from
the app default `composer.mode.default` (last value chosen on Home). When a session is
opened from history the initial value is the mode of its last user message
(`<user_input mode="…">`, `parseUserInputMode`, FX "History").

**ModelChip.** Tap → opens SP S8 `ModelPickerSheet` (focus moves to its title). Disabled
while `isBusy` (desktop `disabled={isBusy}`). Selection handling, persistence
(`ModelSelectionStore`) and the live-session `session.update_connection` call are SP S8's.
The composer only reflects `ModelChipState` (§1.11).

**ReasoningChip.** Tap → opens SP S8 `ReasoningMenu` anchored to the chip. Disabled when
`supportsReasoning != true` (desktop `modelSupportsReasoning !== true`), with long-press
tooltip "The selected model does not report reasoning support". Label (desktop
`effortLabel`): "Reasoning" when nothing was chosen and support is unknown; "None" when
nothing was chosen and the model lacks support; otherwise the level label ("None", "Low",
"Medium", "High", "Extra"). Seeding per provider and the default "Low" are SP S8's.

**UsageRing.** Tap → `ContextUsageSheet` (§1.12).

**Attach.** Tap → `AttachSheet` (§1.9). Disabled in the kernel-down state.

**Attachment chip.** `InputChip(selected = false)`: leading avatar = 24dp image thumbnail
(`shapes.extraSmall`) for images, `description` icon for files; label = file name
(`labelLarge`, middle-ellipsized at 24 graphemes); trailing `close` icon button with
`contentDescription = "Remove <name>"`. Tap on an image chip opens the lightbox (DS §4.2
ImageLightbox) over the draft image.

**Long-press on the field.** Platform text selection / paste toolbar (no app menu). This
also works in the read-only states (§1.6), so a kept draft can be copied out while the
kernel is down.

**Drag and drop (tablet, DeX).** `Modifier.dragAndDropTarget` accepts any URI (desktop
`AttachmentDropZone` accepts any file) and feeds §1.9: images go through
`addImage` (with step 0's support check and the 5-image cap), everything else through
`addFile`.

### C1.8 Hardware keyboard (Tab S7+ Book Cover, DeX; desktop key map)

Handled in `Modifier.onPreviewKeyEvent` on the field, only for events whose
`nativeKeyEvent.device` is not virtual (soft keyboards that send `KEYCODE_ENTER` must
still insert a newline), and never while the IME has an active composition
(`TextFieldState.composition != null`, desktop `isComposing || keyCode 229`).

| Key | When | Action |
| --- | --- | --- |
| Up / Down | slash or mention panel open with items | move highlight (wraps) |
| Enter / Tab | panel open with items | insert highlighted item |
| Esc | panel open | dismiss the panel for this token (desktop `dismissedSlashKey`) |
| Esc | no panel, `canAbort` | Stop |
| Enter | `canSend` | Send |
| Enter | no draft, busy, first queued prompt is not already steering, no Ctrl/Alt/Meta, not a repeat | steer first queued prompt (C2 §2.7) |
| Shift+Enter | always | newline |
| Ctrl+K / Ctrl+P | anywhere in chat | open session search (DS §5.7.5; Sessions spec) |

### C1.9 Attachments (attach sheet and pipeline)

**AttachSheet** *(Android)*: `ModalBottomSheet`, title "Attach files" (desktop
attach-button label), three `ListItem`s, each `heightIn(min = 56.dp)`:

| Item | Icon | Supporting | Action |
| --- | --- | --- | --- |
| "Photos" | `photo_library` | "PNG, JPEG, GIF or WebP" | by remaining image slots, `remaining = 5 − current image attachments`: **≥ 2** → `ActivityResultContracts.PickMultipleVisualMedia(maxItems = remaining)`; **== 1** → `ActivityResultContracts.PickVisualMedia()` (single); **== 0** → no picker, snackbar `attach_too_many_images` "Attach up to 5 images." from the row. Both with `PickVisualMediaRequest(ImageOnly)` (system photo picker; no permission) |
| "Camera" | `photo_camera` | none | `remaining == 0` → snackbar `attach_too_many_images`, no launch; else `ActivityResultContracts.TakePicture()` into a `FileProvider` URI under `cacheDir/attachments/` (no CAMERA permission is declared, so none is needed) |
| "Files" | `description` | "Any file; text up to 1 MB" | `ActivityResultContracts.OpenMultipleDocuments()` launched with `arrayOf("*/*")` (desktop `<input accept="*/*" multiple>`); images picked here go through the image path (with its checks), everything else through the text-file path |

`PickMultipleVisualMedia` throws `IllegalArgumentException("Max items must be higher than
1")` for `maxItems <= 1` (androidx.activity 1.13.0), hence the three-way rule. Compose
wiring: two launchers registered unconditionally,
`multi = rememberLauncherForActivityResult(remember(maxOf(remaining, 2)) { PickMultipleVisualMedia(maxOf(remaining, 2)) })`
and `single = rememberLauncherForActivityResult(PickVisualMedia())`; the row picks one
by `remaining`. The pipeline's own cap (image step 2) still applies to whatever comes
back.

Every row **first dismisses the sheet** and launches the system UI only after the hide
animation completes (`scope.launch { sheetState.hide() }.invokeOnCompletion { open =
false; launcher.launch(…) }`); otherwise the `ModalBottomSheet` stays under the picker
and the user needs an extra back on return.

When the selected model reports `supportsImages == false`, "Photos" and "Camera" stay
visible with supporting text "This model doesn’t support images" *(desktop title
fragment)* and tapping them shows the images-unsupported snackbar instead of the picker.
Nothing is hidden or disabled (DS §9: no disabled placeholders; here the explanation is
the affordance).

**Image pipeline** (`AttachmentPipeline.addImages(uris)`, the one entry for the attach
sheet, Files, `contentReceiver` and drag and drop; desktop `handleAttachFiles`):

0. **Image support.** If the current model reports `supportsImages == false`, drop every
   image of the batch and show `composer_images_unsupported` once (desktop
   `allowed = imagesUnsupported ? files.filter(!image) : files` + toast); non-image
   items of the same batch continue to the text-file path. An unsupported image never
   becomes a chip; the inline-error state (§1.6) only arises when the model is switched
   after attaching (desktop `unsupportedDraftImageCount`). `supportsImages == null`
   (unknown) allows.
1. Decode with `ImageDecoder` (API 28+) or `BitmapFactory` (26–27), honouring EXIF
   orientation; HEIC/HEIF/AVIF/TIFF/BMP are decoded and re-encoded.
2. **Count cap.** At most **5** images per message: if the batch would exceed it, the
   first `remaining` images are added and `attach_too_many_images` shows once (desktop
   cloud rule and copy, applied locally, §1.2).
3. Downscale so the long edge ≤ **2048 px**.
4. Encode: PNG if the source has alpha and the PNG is ≤ 1.5 MB, else JPEG quality 85.
   GIF keeps only its first frame (JPEG). WebP sources → JPEG.
5. **Size limits**, computed on the re-encoded bytes exactly as the desktop does
   (`cloudImageAttachmentError`): `encoded = 4 * ceil(bytes / 3)`; any image with
   `encoded > 5 MiB` (`DEFAULT_MAX_IMAGE_BASE64_BYTES`) → "Each image must be 3.75 MB or
   smaller."; the sum over the message's images `> 8 MiB`
   (`DEFAULT_MAX_TOTAL_MEDIA_BYTES`) → "Attachments must be 6 MB or smaller in total."
   (desktop strings verbatim: they state the decoded equivalents of the base64 limits).
   Over a limit → snackbar and the image is not added.
6. Store the bytes in `cacheDir/attachments/<uuid>.<ext>` (never in DataStore) and keep a
   96px thumbnail in memory.
7. At send: `data:<mime>;base64,<…>` strings in `attachments.userImages` (FM "Image
   attachments"; CLI `prompt.ts:194` precedent). Files are deleted from cache after the
   send reply or when the chip is removed or the session is closed.

**Text-file pipeline** (`AttachmentPipeline.addFile(uri)`): the Hub's `userFiles` are
Termux paths (FM), so the app materialises the file inside Termux (the desktop sidecar
does the same with `materializeUserFiles`; desktop `serializeAttachments` reads every
non-image with `file.text()`, any size):

1. Read through the `ContentResolver`. Reject if larger than **1 MB** (1,048,576 bytes)
   *(Android ceiling: the daemon accepts up to `MAX_USER_FILE_BYTES` = 20,480,000 bytes
   and inlines the whole file into the model context, so 1 MB, ≈ 250k tokens, already
   exceeds most context windows; the desktop has no cap)*. Decode as UTF-8 with
   replacement of malformed sequences (`CodingErrorAction.REPLACE`, same as the
   desktop's `file.text()`). Reject if the decoded text contains U+0000: the daemon's
   `loadUserFileContent` would refuse it ("Cannot read binary file into context.").
2. Write it through **stdin**, never through arguments (§0.8): RUN_COMMAND background
   `$PREFIX/bin/bash` with arguments
   `["-c", "umask 077 && mkdir -p \"$1\" && cat >> \"$1/.$2.part\"", "_", <dir>, <name>]`
   and `TermuxRunner.EXTRA_STDIN` (`com.termux.RUN_COMMAND_STDIN`) = one chunk of the
   text, then a final command `["-c", "mv -f \"$1/.$2.part\" \"$1/$2\"", "_", <dir>, <name>]`.
   Chunks are at most **128 Ki chars** (a Java `String` extra is UTF-16 in the parcel,
   so ≈ 256 KB per intent, well under the ~1 MB Binder transaction buffer), sent strictly
   one after the other (each waits for exit 0), so a 1 MB file is at most 9 commands.
   `<dir>` = `/data/data/com.termux/files/home/.cline/data/attachments/android/<sessionId or "draft">/<uuid>`
   (fresh `<uuid>` per file, so the `.part` file never pre-exists) and `<name>` is the
   display name with `/` and NUL removed and a leading `.` replaced by `_`. New helper
   `ClineCommands.writeAttachment(dir, name, content): Result<String>` (does the
   chunking; returns `<dir>/<name>`).
3. The chip shows a 16dp spinner until the last command exits 0; any failure or a 60 s
   timeout per command → snackbar "Could not attach <name>." *(Android)* and the chip is
   removed (the partial `.part` file is left for "Clear attachments").
4. At send: `attachments.userFiles = [<dir>/<name>, …]` (new `userFiles` parameter on
   `HubPayloads.sendInput`).
5. The materialised files are left in place (the transcript may reference them); the
   Kernel settings "Clear attachments" action (Kernel spec) removes the folder.

### C1.10 Copy (all strings, `res/values/strings.xml`)

Placeholders, highest precedence first (desktop order, cloud branches removed):

| Condition | String id | Text |
| --- | --- | --- |
| C7 processing | `composer_placeholder_transcribing` | "Transcribing voice input…" |
| C7 listening, field empty | `composer_placeholder_listening` | "Listening…" *(Android)* |
| kernel down | `kernel_line2_unavailable` / `kernel_line2_asleep` | "Cline Hub is not connected." / "Start kernel to continue." (DS §1.6) |
| busy, conversation, queue non-empty, hardware keyboard attached | `composer_placeholder_busy_queue` | "Agent is working... submit to queue another message, or Enter to send the first message from the queue" |
| busy, conversation | `composer_placeholder_busy` | "Agent is working... submit to queue another message" |
| welcome | `composer_placeholder_welcome` | "Ask to make changes, @mention files, reference #PRs, or run /commands." |
| conversation | `composer_placeholder_conversation` | "Enter your question or type / for commands or @ for context" |

"Hardware keyboard attached" = `Configuration.keyboard == KEYBOARD_QWERTY &&
hardKeyboardHidden == HARDKEYBOARDHIDDEN_NO`; without one, the "or Enter…" clause would
describe a key the user does not have.

Controls:

| Id | Text | Use |
| --- | --- | --- |
| `cd_send` / `tt_send` | "Send message" / "Send (Enter)" | Send CD / tooltip |
| `cd_stop` / `tt_stop` | "Stop agent" / "Stop the agent (Esc)" | Stop CD / tooltip |
| `cd_starting` | "Starting" | Starting slot |
| `cd_attach` / `tt_attach` | "Attach files" / "Attach files" | attach |
| `tt_attach_no_images` | "Attach files (this model doesn’t support images)" | attach tooltip when images unsupported |
| `cd_remove_attachment` | "Remove %1$s" | chip close |
| `mode_plan` / `mode_act` / `cd_mode_group` | "Plan" / "Act" / "Mode" *(Android)* | segmented row; group label |
| `cd_model_chip` | "Model and provider: %1$s / %2$s" (DS #7: full "Provider / Model" in the TalkBack label) | ModelChip CD |
| `tt_model_chip` | "%1$s / %2$s" (desktop `title`) | ModelChip tooltip |
| `model_chip_placeholder` | "Model" | no model resolved (desktop placeholder) |
| `tt_model_busy` | "The model can be changed when the agent is idle." *(Android)* | disabled ModelChip tooltip |
| `cd_thinking_level` | "Thinking level" | ReasoningChip CD; with value: "Thinking level: %1$s" *(Android composition)* |
| `effort_none` … `effort_extra`, `effort_unknown` | "None", "Low", "Medium", "High", "Extra", "Reasoning" | chip label |
| `tt_reasoning_unsupported` | "The selected model does not report reasoning support" | |
| `cd_context_ring` | "Context window: %1$s of %2$s tokens used (%3$d%%)" | ring CD (numbers via `NumberFormat`) |
| `composer_images_unsupported_inline` | "This model doesn’t support the attached images. Remove them or choose a model that supports images before sending." | inline error |
| `composer_images_unsupported` | "This model doesn’t support image input. Choose a model that supports images or remove the images before sending. Other files can still be attached." | snackbar (desktop toast title + description, DS #17 "Title. Description") |
| `composer_needs_text` | "Add a message to go with your attachments. Describe what you want Cline to do with the attached files before sending." | snackbar |
| `composer_connect_model` / `action_connect_model` | "Connect a model to start building" / "Connect a model" | snackbar + action (C8 copy) |
| `kernel_start` / `kernel_starting` | "Start kernel" / "Starting kernel..." *(Android)* | read-only row |
| `kernel_connecting` / `kernel_reconnecting` | "Connecting..." / "Reconnecting..." | DS §1.6 |
| `composer_resuming` | "Resuming session..." *(Android)* | |
| `composer_resume_failed` | "Could not resume this session." *(Android)* | snackbar, action `action_retry` "Retry" *(Android)* |
| `composer_held_for_hub` | "Cline Hub is restarting. Your message will be sent when it is back." *(Android)* | note |
| `composer_send_failed` | "Message not sent. %1$s" *(Android)* | snackbar + "Retry" |
| `composer_send_failed_disconnected` | "Message not sent. Cline Hub disconnected." *(Android)* | |
| `composer_run_interrupted` / `action_resend` | "The run was interrupted by a kernel restart." / "Resend" *(Android)* | |
| `composer_stop_failed` | "The agent did not stop. Try again." *(Android)* | |
| `composer_session_deleted` / `action_new_session` | "This session was deleted." *(Android)* / "New session" | NoticeBar |
| `kernel_start_failed` / `action_details` | "Could not start the kernel." / "Details" *(Android)* | snackbar; "Details" opens the Kernel status sheet |

Attach sheet and pipeline:

| Id | Text |
| --- | --- |
| `attach_sheet_title` | "Attach files" |
| `attach_photos` / `attach_photos_support` | "Photos" / "PNG, JPEG, GIF or WebP" *(Android)* |
| `attach_camera` | "Camera" *(Android)* |
| `attach_files` / `attach_files_support` | "Files" / "Any file; text up to 1 MB" *(Android)* |
| `attach_images_unsupported_support` | "This model doesn’t support images" |
| `attach_too_many_images` | "Attach up to 5 images." (desktop) |
| `attach_image_too_large` | "Each image must be 3.75 MB or smaller." (desktop verbatim; checked as `4 * ceil(bytes / 3) > 5 MiB`) |
| `attach_total_too_large` | "Attachments must be 6 MB or smaller in total." (desktop verbatim; checked as the sum of encoded sizes `> 8 MiB`) |
| `attach_image_unreadable` | "Could not read the image." *(Android)* |
| `attach_unsupported_image_type` | "Choose PNG, JPEG, GIF, or WebP images." (desktop) — only for formats the decoder cannot open |
| `attach_file_too_large` | "%1$s is larger than 1 MB." *(Android)* |
| `attach_file_not_text` | "%1$s is not a text file and cannot be attached." *(Android; shown when the decoded content contains NUL, the daemon's binary check)* |
| `attach_file_failed` | "Could not attach %1$s." *(Android)* |
| `cd_attachment_image` | "Attachment %1$d" (desktop alt) |

### C1.11 Data bindings and ViewModel state

Kernel API used by C1 (quoted from FM):

| Purpose | Call / event |
| --- | --- |
| Send | `session.send_input` (= `run.start`) `{prompt, mode?, delivery?, attachments?:{userImages:[dataURL...], userFiles?:[path...]}}` with `envelope.sessionId` and `timeoutMs:null` |
| Turn lifecycle | events `run.started {requestId, clientId}` (ignored for queued/steered sends, §0.2), `run.completed|failed|aborted {reason, error?, result, snapshot}`, `iteration.started`, `session.pending_prompt_submitted`, `session.updated {session|snapshot}` (status `idle|running|pending|completed|aborted|failed`; `snapshot.endedAt` = shutdown projection); `run.interrupted` (only for `run.enqueue` runs, handled no-op-safe, §0.2); `/version` `pid`/`startedAt` for the interrupted derivation |
| Stop | `run.abort {sessionId, reason?} -> {applied:true}`; events `run.aborted`, `approval.resolved{cancelled:true}` |
| Usage | event `usage.updated {delta{inputTokens,outputTokens,cacheReadTokens,cacheWriteTokens,totalCost}, totals, usage, aggregateUsage}`; `session.get {includeSnapshot:true}`; history `session.messages` assistant `metrics` |
| Model / reasoning (via SP S8) | `session.update_connection {sessionId, updates:{providerId?, modelId?, thinking?, reasoningEffort?}}` |
| Agents running (for `canAbort`) | event `team.progress` (Chat thread spec counts running agents) |
| Resume | `session.messages {sessionId}`, `session.create` built by `HubPayloads.sessionCreate(<the session's NewSessionSpec>.copy(resumeSessionId, initialMessages))`, timeout 120 s (C1.7) |
| Hub drain | reply error `hub_draining`; event `hub.drain_changed` |
| Kernel start | RUN_COMMAND background `$PREFIX/bin/cline hub start` then `termux-wake-lock` (KC connect sequence; run by `KernelCoordinator`) |
| File attachment | RUN_COMMAND background `$PREFIX/bin/bash -c …` with the content in `com.termux.RUN_COMMAND_STDIN`, chunked (§1.9) |
| Capabilities | `ProviderCatalogRepository` (SP §0.7): `supportsVision` / `inputModalities`, `supportsReasoning`, `contextWindow` for the current provider/model |

```kotlin
@Immutable
data class ComposerUiState(
    val variant: ComposerVariant,                 // Welcome | Conversation
    val sessionId: String?,                       // null on Home
    val phase: RunPhase,                          // §0.2
    val kernel: KernelAvailability,               // §0.3
    val hasDraft: Boolean,                        // text or attachments; the TextFieldState itself lives in the controller
    val attachments: List<DraftAttachment>,
    val imagesUnsupported: Boolean,               // supportsImages == false && any image attachment
    val mode: PromptMode,                         // Act | Plan (Yolo never set from the UI)
    val model: ModelChipState,
    val reasoning: ReasoningChipState,
    val usage: ContextUsage?,                     // null = ring hidden
    val queueSize: Int,                           // C2 owns the list
    val menu: SuggestionMenuState?,               // C5 / C6
    val voice: VoiceState,                        // C7
    val holdReason: HoldReason?,                  // ResumingSession | HubDraining
    val canSend: Boolean,                         // hasDraft && kernel == Connected && voice !in {Listening, Processing} && holdReason == null
    val isBusy: Boolean,
    val canAbort: Boolean,
    val hardwareKeyboard: Boolean,
)

sealed interface DraftAttachment {
    val id: String; val displayName: String
    data class Image(override val id: String, override val displayName: String, val cacheFile: File,
                     val mime: String, val base64Bytes: Long, val thumbnail: ImageBitmap) : DraftAttachment
    data class TextFile(override val id: String, override val displayName: String, val termuxPath: String?,
                        val state: FileAttachState /* Writing | Ready | Failed */) : DraftAttachment
}

data class ModelChipState(
    val providerId: String?, val providerName: String?, val modelId: String?, val modelName: String?,
    val enabled: Boolean,                         // !isBusy
    val supportsImages: Boolean?,                 // null = unknown → allow
    val supportsReasoning: Boolean?,
    val contextWindow: Long?,
)
data class ReasoningChipState(val label: String, val enabled: Boolean, val unsupportedReason: Boolean)

data class ContextUsage(                          // desktop AgentContextUsageData + cost
    val tokensIn: Long, val tokensOut: Long, val cacheReadTokens: Long,
    val contextWindow: Long, val totalCostUsd: Double?,
) { val ratio get() = ((tokensIn + tokensOut).toDouble() / contextWindow).coerceIn(0.0, 1.0) }
```

**Usage derivation** (desktop `summarizeSessionUsage`: "the latest request represents
current context-window pressure; summing every request's input would make the ring
saturate even after context compaction. Cost remains a session-wide sum"):

- `tokensIn/tokensOut/cacheReadTokens` = the latest `usage.updated.delta` of the lead
  agent (`agent.kind == "lead"`, FX) for this session; on open from history = the
  `metrics` of the last assistant message from `session.messages`.
- `totalCostUsd` = `usage.updated.usage.totalCost` (session totals); on open =
  `session.get` `session.usage.totalCost`, else `metadata.totalCost`.
- `contextWindow` from `ProviderCatalogRepository` for the session's provider/model.
- `usage = null` (ring hidden) when `contextWindow` is unknown or ≤ 0, or
  `tokensIn + tokensOut <= 0` (desktop returns null), and always on the welcome variant.

**Draft persistence:** text drafts per key (`sessionId` or `"home"`) in DataStore
`composer.draft.<key>`, debounced 500 ms, cleared on a successful send. Attachments are
not persisted across process death (cache files are swept on next launch). The
`TextFieldState` survives configuration changes in the controller.

### C1.12 Context usage sheet

`ModalBottomSheet` (DS #8), opened from the ring. Content (desktop `AgentContextUsage`
details, 24dp gutters):

```
┌──────────────── drag handle ─────────────────┐
│ Context window              12.3k / 200k (6%)│  titleMedium label; value ClineMono.small
│ ████▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░  │  4dp segmented bar
│ Input tokens                          12,034 │
│ Output tokens                            311 │
│ Cached tokens                          8,192 │
│ Cost                                 $0.0421 │  only when cost is known
└──────────────────────────────────────────────┘
```

- Value `formatCompactTokens` (desktop `agent-context-usage.tsx`: ≥1M "1.2M", ≥1k "12.3k"
  with ".0" dropped, else localized integer) + " (" + percent + "%)".
- Bar: track `surfaceContainerHighest`, full radius; segments (DS §1.7) uncached input
  `primary`, cached input `primary @ 60 %`, output `info.color`; widths animate 120 ms.
  Segment widths, ported literally from `agent-context-usage.tsx:56-61` into
  `ContextUsageCalculator`:
  ```ts
  const cachedTokens = Math.min(usage.cacheReadTokens, usage.tokensIn);
  const uncachedInputTokens = Math.max(usage.tokensIn - cachedTokens, 0);
  const segmentScale = totalTokens > contextWindow ? contextWindow / totalTokens : 1;
  const segmentWidth = (tokens) => (tokens / contextWindow) * segmentScale;   // fraction of the track
  ```
  with `totalTokens = tokensIn + tokensOut`. The clamp matters: providers report
  `cacheReadTokens` larger than `inputTokens` (Anthropic counts cache reads
  separately), and without it the uncached width goes negative (Compose throws on a
  negative size). Each fraction is additionally `coerceIn(0f, 1f)` before conversion to
  dp. Unit tests: `cacheReadTokens > tokensIn` gives cached = tokensIn, uncached = 0;
  `totalTokens > contextWindow` makes the three widths sum to exactly 1.
- Rows `heightIn(min = 40.dp)`: label `bodyMedium onSurfaceVariant`, value `ClineMono.small onSurface`,
  localized integers; Cost via `formatCostUsd` (DS #8).
- Updates live while open. If usage becomes hidden (model switch to unknown window) the
  sheet closes.

### C1.13 Navigation and back

- Lives at the bottom of the `chat/{sessionId?}` route (Home when null). Not a route.
- Entering a chat focuses the field only when the session is not busy and the user came
  from "New session" (desktop `subscribeToPromptInputFocus`); otherwise focus is not
  requested (the IME should not pop up when reading history).
- Back order (DS §5.8): open tooltip/menu → open sheet (attach, usage, model, reasoning)
  → suggestion panel (dismiss for this token) → IME (system) → the chat's own back. The
  composer never registers a permanent `BackHandler`; the panel registers one only while
  shown.
- Leaving the chat stops voice input (C7) and keeps the draft.

### C1.14 Accessibility

- The field: `contentDescription` none (the placeholder is the hint, M3 text field
  semantics read it); `stateDescription` "Listening" / "Transcribing" while C7 runs.
- Send/Stop/attach/mic/ring CDs per §1.10; tooltips on every icon-only control (DS #14).
- Plan/Act: `Modifier.semantics { contentDescription = "Mode" }` on the row;
  each segment is `Role.RadioButton` (M3 default) — TalkBack: "Plan, not selected, 1 of
  2".
- Disabled controls keep their CD; the disabled ModelChip exposes the busy tooltip
  text as `stateDescription`.
- Live region (DS §8.1.4): the conversation's single polite status text announces
  "Agent is working…" on `run.started`; this spec adds "Resuming session...",
  "Starting kernel...", the held-for-Hub note and the inline unsupported-images error
  (`composer_images_unsupported_inline`, when it first appears after a model switch) to
  the same region (no second region). The C2 sheet's row errors are assertive (C2.4).
- The settings row's horizontal scroll (§1.4 step 5) keeps TalkBack traversal order:
  attach → Plan → Act → model → reasoning → ring → Stop → Send.
- Touch targets: every control 48dp; chips use `minimumInteractiveComponentSize`.
- Font scale 2.0 × size 20: field grows, row scrolls horizontally (§1.4 step 5) and
  grows vertically (`heightIn(min = 48.dp)`, §1.3), nothing clips. Required previews:
  `@Preview(fontScale = 2f)` for `ComposerSettingsRow`, `PromptQueueStrip`,
  `ApprovalBanner` and `QuestionBanner`, each with the in-app size 20 typography.

### C1.15 Acceptance checklist (S25U, then Tab S7+)

Preconditions: the Termux port at `v3.0.68-termux.2` or later is installed, the Hub is
running, a provider is configured, tool auto-approve is off, a workspace with source
files is chosen. Do not run these while a port release candidate's device steps or the
owner's manual test are in progress (repo CLAUDE.md).

1. Home: the field shows 2 lines with "Ask to make changes, @mention files, reference
   #PRs, or run /commands." and there is no usage ring. Send is disabled.
2. Type "hello"; Send enables. Tap Send: the field clears at once, the trailing slot shows
   the spinner ("Starting"), then Stop appears when `run.started` arrives; the keyboard
   stays open.
3. While running and the draft is empty, only Stop shows; type "and then summarise":
   Stop and Send both show; Send queues (strip "1 prompt queued" appears, C2) and the
   keyboard closes.
4. Tap Stop: the button turns into a spinner; within a second the run ends as cancelled
   and Send returns; if the aborted turn came from the queue, the strip disappears too.
5. With the model chip on the S25U portrait it is icon-only; long-press shows
   "Provider / Model"; it is disabled while running and does not change form when a
   send starts. On the Tab S7+ the label reads "<Provider> / <Model>"; with a long
   model name the model part is ellipsized first.
6. Switch Plan/Act on Home, send, then open the transcript's user bubble: it is marked
   with the chosen mode (Chat thread spec); reopening the session restores that mode.
7. Pick a model without reasoning support: the thinking chip is disabled and long-press
   reads "The selected model does not report reasoning support".
8. After the first turn the ring appears; its colour is `primary` below 50 %; open it:
   "Context window", the bar and four rows appear with localized numbers; a session with
   an unknown context window shows no ring.
9. Attach two photos from the picker: chips with thumbnails appear above the field;
   sending with an empty field shows "Add a message to go with your attachments…"; with
   text the images reach the model (it describes them).
10. Choose a model without image input: the attach tooltip changes; attaching a photo
    (Photos, Files, Gboard paste or a drop) shows the image-unsupported snackbar and no
    chip appears. Attach a photo on an image model, then switch to the text-only model:
    the inline error appears and TalkBack announces it; Send shows the snackbar.
    With 4 images attached, "Photos" opens the single-image picker; with 5 it shows
    "Attach up to 5 images." without opening a picker; the attach sheet is gone when the
    picker appears and one back from the picker returns to the composer.
11. Attach a 3 KB `.txt` file: the chip shows a spinner then settles; the prompt
    "summarise the attached file" makes the model quote it. A 300 KB `.log` attaches
    (chunked, several RUN_COMMANDs) and `wc -c` in Termux on the materialised file
    matches the original byte count; a 2 MB file is refused with "<name> is larger than
    1 MB."; a `.png` renamed `.bin` with NUL bytes is refused as not a text file. During
    the write, `ps -eo args` in Termux never shows file content in a command line.
12. Paste a screenshot from the clipboard with Gboard: it becomes an image chip, not
    text.
13. Kill Termux (`ssh <phone> pkill -f "entry.js"` or swipe it away): the composer
    turns read-only with "Termux asleep" / "Cline Hub @unknown" and "Start kernel"; the
    typed draft is still there; "Start kernel" shows "Starting kernel..." and returns to
    the normal composer within ~15 s.
14. With the Hub restarted behind the app's back (`cline hub stop && cline hub start` in
    Termux), send a prompt to the open session: "Resuming session..." shows briefly and
    the turn runs with the previous context; in the resumed session an ask_question
    prompt still opens C4 and "/team …" still starts a team (same `NewSessionSpec`).
    Restart the Hub **during** a running turn: after reconnect the prompt is back in the
    field with "The run was interrupted by a kernel restart." + "Resend". Force only a
    reconnect mid-turn (background the app, `adb shell am kill io.github.ichouchiang.clinetermux`, reopen;
    the Hub keeps running): same `pid`, so no interrupted snackbar.
15. TalkBack: walk the composer; every control is announced with the CD in §1.10, the
    ring reads "Context window: … tokens used (…%)", Plan/Act read as a two-item group.
16. Set system font size to the largest and the in-app font size to 20: the settings
    row scrolls horizontally with a fade and grows taller instead of clipping the
    Plan/Act labels; Stop/Send stay visible and vertically centred; the queue strip and
    the approval/question banners grow instead of clipping. The four
    `@Preview(fontScale = 2f)` previews (§1.14) exist and render without clipping.
17. Rotate the S25U to landscape: the hero hides, the composer keeps its draft and
    attachments. With the IME open and three prompts queued, the field stops growing at
    2 lines, the settings chip row and the queue strip are hidden; with no approval
    pending at least one transcript row stays visible; with an approval pending the
    banner stays visible and takes priority over transcript rows. Closing the IME brings
    the chips and the strip back, and a pending approval's banner stays fully visible
    (D18).
18. Tab S7+ with the Book Cover keyboard: Enter sends, Shift+Enter adds a line, Esc
    stops a running agent, the busy placeholder mentions Enter only when the keyboard is
    attached.
19. Light and dark theme, dynamic colour on and off: the composer surface, border,
    Send (primary) and Stop (error) colours follow DS; the queue strip and the composer
    share one outline with a divider between them (no seam); screenshots saved.

---

## C2. Prompt queue strip and Prompt queue sheet

### C2.1 Purpose

Show prompts submitted while the agent works, let the user promote one to the next turn
("steer"), edit it, or remove it, without leaving the composer.

### C2.2 Desktop equivalent and what was copied

`AgentPromptQueue` (`sdk/packages/ui/components/agent-prompt-queue.tsx`) inside
`AgentComposerBody`, fed by `use-chat-session.ts` `steerPromptInQueue` /
`updatePromptInQueue` / `removePromptInQueue` and displayed through
`formatDisplayUserInput`.

Copied: the count copy and pluralization, the per-row status icon (clock for queued,
corner-down-left in primary for steer), the "Next turn" badge, the "N attachment(s)"
note, the three row actions and their labels, edit with Enter-to-save / Esc-to-cancel,
"all actions disabled while one is pending", per-row error text and its fallbacks,
auto-cancel of an edit whose item disappeared, collapse when the queue empties.
Translated: the desktop's inline expandable list above the textarea becomes a strip
(`heightIn(min = 48.dp)`, one line of prompt text, ellipsized) inside the composer
surface (DS PromptQueueStrip; §1.3 `DockedChildren`) plus a bottom sheet for the full list.

### C2.3 Wireframes

**Strip, compact, one queued prompt:**

```
┌──────────────────────────────────────────────┐
│ [clock] Also add tests for the parser…  [↵] v │  min 48dp, surfaceContainer, RectangleShape (inside ComposerSurface)
├──────────────────────────────────────────────┤
│ (composer field)                              │
```

**Strip, several prompts, first one already steering:**

```
│ [↵] 3 prompts queued · Next turn            v │
```

**Prompt queue sheet, compact:**

```
┌──────────────── drag handle ─────────────────┐
│ 3 prompts queued                              │  titleMedium
│                                               │
│ [↵] Fix the failing test first     Next turn  │  steer row tinted primary @ 5 %
│                                  [edit] [del] │
│ [clock] Also add tests for the parser         │
│         1 attachment          [↵] [edit] [del]│
│ [clock] Then update the changelog             │
│                               [↵] [edit] [del]│
└──────────────────────────────────────────────┘
```

**Row in edit mode (IME open, sheet resized above it):**

```
│ [clock] ┌──────────────────────────────────┐  │
│         │ Also add tests for the parser|   │  │  OutlinedTextField, 1..4 lines
│         └──────────────────────────────────┘  │
│         Could not update the queued prompt.   │  error, only after a failure
│                                  [save] [x]   │
└──────────────── IME ─────────────────────────┘
```

**Tablet:** same sheet, centred, max width 640dp (DS §5.5); the strip spans the composer
width inside the 840dp column.

### C2.4 States

| State | Strip | Sheet |
| --- | --- | --- |
| Empty | not composed | closes automatically (desktop `setExpanded(false)` on empty) |
| One item | status icon + prompt (1 line) + badge "Next turn" if steering + inline Steer button if not steering + `expand_more` | one row |
| Several | status icon of the first item + "N prompts queued" + " · Next turn" when the first is steering + Steer button for the first non-steering item at index 0 + `expand_more` | all rows |
| Action pending | Steer button shows a 16dp spinner; all actions disabled | the row's actions show a spinner on the pressed one; every action in every row disabled; row `aria-busy` → `stateDescription` "Updating" *(Android)* |
| Action error | snackbar "Could not steer queued message. <error>" (desktop toast title) when steering from the strip | `bodySmall error` under the row, `LiveRegionMode.Assertive` |
| Editing | — | the row's text becomes an `OutlinedTextField`; Save disabled while blank |
| Kernel reconnecting | strip shows the last known list, actions disabled | actions disabled; a `LinearProgressIndicator` under the title |
| Kernel down | strip hidden (the queue lives in the daemon; nothing to act on) | sheet closes |
| First run / loading | not applicable: the queue only exists for a live session; on open the controller fetches `session.pending_prompts` and shows nothing until it replies | |

### C2.5 Copy

| Id | Text |
| --- | --- |
| `queue_count` (plurals) | one: "%d prompt queued" · other: "%d prompts queued" (desktop `{n} prompt{s} queued`) |
| `queue_next_turn` | "Next turn" |
| `queue_attachments` (plurals) | one: "%d attachment" · other: "%d attachments" |
| `cd_queue_steer` / `tt_queue_steer` | "Steer queued prompt" / "Steer" |
| `cd_queue_edit` | "Edit queued prompt" (also the edit field's label) |
| `cd_queue_remove` | "Remove queued prompt" |
| `cd_queue_save` | "Save queued prompt" |
| `cd_queue_cancel_edit` | "Cancel editing queued prompt" |
| `queue_err_update` / `queue_err_steer` / `queue_err_remove` | "Could not update the queued prompt." / "Could not steer the queued prompt." / "Could not remove the queued prompt." (used when the error has no message) |
| `queue_steer_failed` | "Could not steer queued message. %1$s" (desktop toast "Could not steer queued message" + description) |
| `cd_queue_strip` | "%1$s. Open the prompt queue" *(Android)* (`%1$s` = the count string) |
| `queue_state_updating` | "Updating" *(Android)* |

### C2.6 Data bindings and state

| Purpose | Kernel API (FM "Queue / steer follow-ups and the prompt queue editor") |
| --- | --- |
| Enqueue (from C1) | `session.send_input {delivery:'queue'}` |
| Load | `session.pending_prompts {sessionId} -> {prompts[{id,prompt,delivery,attachmentCount}]}` on open, on reconnect, and on `session.attach` |
| Live list | event `session.pending_prompts {sessionId, prompts}` (replaces the list) |
| Item left the queue | event `session.pending_prompt_submitted {sessionId, prompt}` (the Chat thread spec appends the user bubble; the list update comes from `session.pending_prompts`) |
| Steer first | `session.steer_first_pending_prompt {sessionId}` (no-op reply `updated:false` when the first already steers) — new `HubPayloads.steerFirstPendingPrompt(sessionId)` |
| Steer a specific item | `session.update_pending_prompt {promptId, delivery:'steer'}` (the kernel moves it to the front and wakes the agent, `PendingPromptService.update`) |
| Edit | `session.update_pending_prompt {promptId, prompt}` |
| Remove | `session.remove_pending_prompt {promptId}` |
| Errors | `pending_prompts_unavailable` means **this kernel build has no prompt queue** (`ctx.sessionHost.pendingPrompts` undefined, `session-handlers.ts:1208-1214` and its three siblings); it is deterministic, so no retry: hide the strip, close the sheet and send busy prompts **directly** (`delivery = null`, the Hub accepts them) for the life of the connection. A session that is not live simply returns `ok:true, prompts: []`, the normal empty state. `prompt cannot be empty` → Save is disabled for blank anyway |

All mutation replies carry `{prompts}`; the controller replaces its list from the reply
**only if** no `session.pending_prompts` event with a newer sequence arrived meanwhile
(desktop `queueRevisionRef` guard).

```kotlin
@Immutable
data class PromptQueueState(
    val items: List<QueuedPrompt>,
    val pendingId: String?,             // one action at a time
    val pendingAction: QueueAction?,    // Steer | Save | Remove
    val editingId: String?,
    val editingText: String,            // TextFieldState in the sheet, mirrored here for tests
    val error: Pair<String, String>?,   // promptId to message
    val sheetOpen: Boolean,
)
data class QueuedPrompt(val id: String, val displayPrompt: String, val steer: Boolean, val attachmentCount: Int)
```

`displayPrompt = formatDisplayUserInput(prompt)` (port of
`shared/src/prompt/format.ts`: strips `<user_input>`/`<mode_notice>`, folds
`<user_command slash="team">spawn a team of agents for the following task: X</user_command>`
back to "/team X", other envelopes to "/name …"). Saving an edit re-runs C5's send-time
resolution on the edited text (`/team` rewrite, workflow expansion) before
`update_pending_prompt`, so the round trip is lossless (desktop comment in
`chat-input-bar.tsx`).

### C2.7 Interactions

- Tap the strip (anywhere but the Steer button) → opens the sheet; focus to its title.
- Strip Steer button → `steer_first_pending_prompt` when index 0 is not steering.
  Hardware Enter on an empty composer does the same (C1 §1.8), after waiting up to
  **1 s** for queued sends still in flight to be acknowledged (desktop
  `QUEUE_ACKNOWLEDGEMENT_WAIT_MS` behaviour), so Enter right after a queued send steers
  the right prompt.
- Sheet row Steer (only on non-steering rows) → `update_pending_prompt {delivery:'steer'}`.
- Edit → `OutlinedTextField` prefilled with `displayPrompt`, `minLines = 1`,
  `maxLines = 4`, `Modifier.heightIn(min = 56.dp)`, `KeyboardOptions(imeAction =
  ImeAction.Default)` so the soft keyboard's Enter inserts a newline (a queued prompt
  may have several paragraphs); focus + IME; Save is the `check` button; `close` or back
  cancels (DS §5.8.4); hardware Enter saves,
  Shift+Enter newline, Esc cancels (desktop).
- Remove → immediate (`remove_pending_prompt`), no confirm (desktop).
- If the edited item disappears (it was submitted), the edit is cancelled silently
  (desktop effect).
- Swipe-to-dismiss on rows is **not** offered: remove is one tap and a swipe would race
  with the sheet drag.

### C2.8 Navigation and back

Strip: part of the composer stack. Sheet: `ModalBottomSheet`, not a route; deep link
`chat/{id}?sheet=queue`. Back: cancels an edit first, then collapses the sheet. The
sheet closes by itself when the queue empties or the run ends with the queue empty.

### C2.9 Accessibility

- The strip is one `Role.Button` with CD `cd_queue_strip` and a custom action "Steer
  queued prompt" (DS §8.1.6), plus the visible Steer button as its own target.
- Rows: merged semantics "prompt text, Next turn, 1 attachment"; actions as buttons and
  as custom actions.
- Errors assertive; "Next turn" is text, not colour only.

### C2.10 Acceptance checklist (S25U)

1. Start a long turn ("Run `sleep 20 && echo done`", approve it). Send two more prompts:
   the strip reads "2 prompts queued".
2. Tap the strip's Steer button: the first item gains "Next turn" and the steer icon;
   after the current tool call ends, it runs before the turn would otherwise finish.
3. Open the sheet, edit the second prompt, save: the text updates; while saving all
   buttons are disabled.
4. Remove the remaining prompt: it disappears; the sheet closes when the queue is empty.
5. Queue "/team build a hello world script" while busy: the sheet shows "/team build a
   hello world script", not the envelope.
6. Stop a turn that came from the queue while one more prompt is queued: the strip
   disappears (the kernel discarded the queue).
7. Kill the Hub while the sheet is open: actions disable, the progress bar shows; after
   reconnect the list reloads from `session.pending_prompts`.
8. TalkBack: the strip announces "2 prompts queued. Open the prompt queue" and offers
   the "Steer queued prompt" action.

---

## C3. Tool approval sheet and approval banner

### C3.1 Purpose

When a session runs with `toolPolicies` that do not auto-approve a tool, the agent stops
before each such call; the user reviews the exact input and approves or rejects it. An
unanswered approval parks the turn forever (FM), so the sheet must be reachable,
dismissible without answering, and always offer "Stop agent".

### C3.2 Desktop equivalent and what was copied

`ToolApprovalPanel` (`components/views/chat/messages/tool-approval-panel.tsx`) using
`AgentApprovalCard` (`@cline/ui`), placed after the transcript by `chat-messages.tsx`,
answered through `use-chat-session.ts` `respondToolApproval`.

Copied: header "Tool approval required" with the shield icon in amber, the explanation
line, one card per request, card title = tool name, meta = clock icon + local date/time
("Pending now" when the timestamp is unparseable), description "Request <id> · Iteration
<n>", the pretty-printed JSON input (2-space indent; string input shown as is; `{}` for
null), soft-wrapped detail text (`white-space: pre-wrap; overflow-x: hidden;
overflow-wrap: break-word` in `agent-approval-card.css`), max detail height then
scroll, per-card error, "Approving..." / "Rejecting..." with
spinner and both buttons disabled, cards disappear when resolved, decision errors keep
the card actionable, fallback error "Could not submit decision.".
Translated (DS #9): the inline panel becomes a `ModalBottomSheet` that collapses into a
banner; a readable per-tool preview is added above the raw JSON (the phone has no room
for raw JSON as the only view); "Stop agent" is added to the sheet because the composer
is covered; the buttons are **Reject then Approve, end-aligned** (desktop renders
Approve first, start-aligned: `.cline-ui-agent-approval-card__actions { display:flex;
gap:0.5rem }`). The order follows DS #9 ("Approve end-aligned on compact") and the M3
dialog convention of the confirming action last, nearest the thumb.
Rejection reason: the FM convention "This tool call was rejected by the user and not
executed." (recorded in FX `03`; DS #9 lists the same string). The desktop sends "Tool
call rejected from desktop approval prompt" (`use-chat-session.ts`
`respondToolApproval`), which names the wrong client. **This note is the single source
of truth** for the string the model reads; DS §9's sentence "Rejecting sends the desktop
rejection reason" is wrong and is listed for its owner in §9 item 5.

### C3.3 Wireframes

**Compact, one request (opens fully expanded):**

```
┌──────────────── drag handle ─────────────────┐
│ [shield] Tool approval required               │  titleMedium; icon warning.color
│ Review each tool call and approve or reject   │  bodySmall onSurfaceVariant
│ it before execution.                          │
│ ┌──────────────────────────────────────────┐ │  OutlinedCard, border warning @ 40 %
│ │ run_commands              [clock] 14:02   │ │  title titleMedium; meta labelMedium
│ │ Request approval_1791…v5gc4 · Iteration 1 │ │  bodySmall onSurfaceVariant
│ │ ┌──────────────────────────────────────┐ │ │
│ │ │ $ bun test --filter parser           │ │ │  preview, ClineMono.body, surfaceContainer
│ │ │ $ git status                         │ │ │  max 176dp then scrolls
│ │ └──────────────────────────────────────┘ │ │
│ │ Show raw input                            │ │  TextButton (Android)
│ │                       [ Reject ] [Approve]│ │  OutlinedButton + Button, end-aligned (C3.2)
│ └──────────────────────────────────────────┘ │
│                                   Stop agent  │  TextButton, error content
└──────────────────────────────────────────────┘
```

**Compact, editor call preview:**

```
│ │ editor                    [clock] 14:05   │ │
│ │ Request approval_…  · Iteration 3         │ │
│ │ ┌──────────────────────────────────────┐ │ │
│ │ │ src/parser.ts                        │ │ │  path, ClineMono.small
│ │ │ - const x = parse(a)                 │ │ │  error colour, diff removedLine bg
│ │ │ + const x = parse(a, { strict })     │ │ │  success colour, diff addedLine bg
│ │ └──────────────────────────────────────┘ │ │
```

**Compact, two requests (opens half-expanded, first card visible):**

```
│ [shield] Tool approval required               │
│ Review each tool call and approve…            │
│ ┌ read_files ─────────────── [clock] 14:06 ┐ │
│ │ …                    [ Reject ] [Approve] │ │
│ └──────────────────────────────────────────┘ │
│ ┌ fetch_web_content ─────── [clock] 14:06 ┐  │  (scroll / drag up)
```

**Collapsed banner (sheet dismissed without answering), docked above the composer:**

```
┌──────────────────────────────────────────────┐
│ [shield] Tool approval required      Review  │  min 56dp, warning.container, onContainer
├──────────────────────────────────────────────┤
│ Agent is working... submit to queue anoth…   │
│ [@] [plan|ACT] (M) (T)              o   [#]  │
```

With 2+ requests the banner reads "Tool approval required" followed by a `Badge` with
the count. Banner layout (C3 and C4): `Surface` with `Modifier.heightIn(min = 56.dp)`,
title `Text(maxLines = 2, overflow = Ellipsis)` in a `weight(1f)` slot, `Badge` after
it, action `TextButton` end-aligned and vertically centred; it grows with font scale
instead of clipping. It stays a separate surface 8dp above `ComposerSurface` (§1.3).

**Card in "Approving..." state:**

```
│ │                 [ Reject ] [(o) Approving...]│  both disabled
```

**Tablet (Tab S7+ landscape):** the same sheet centred over the detail pane, max width
640dp; the scrim covers the whole window; cards keep the compact layout; buttons stay
end-aligned.

### C3.4 States

| State | Presentation |
| --- | --- |
| First run | the first approval ever: same as Waiting (no tutorial; the explanation line is the help) |
| Waiting | buttons enabled; sheet opens automatically if the app is foreground on this session (DS §5.5); on another screen of the app: snackbar "Tool approval required" + "Review" (deep link) and the session's dot turns "Approval required" (DS §1.6); app in background: notification (Notifications spec) |
| Approving / Rejecting | pressed button shows 16dp spinner + "Approving..." / "Rejecting..."; both buttons disabled; the card is `aria-busy` |
| Error | `bodySmall error` line under the detail, `LiveRegionMode.Polite`; buttons enabled again; text = the reply's `error.message`, else "Could not submit decision." |
| Already answered (`approval_not_found` reply, or `approval.resolved` from another client) | the card animates out (`shrinkVertically + fadeOut`, DS §6); if the user had pressed a button: snackbar "This request was already answered." *(Android)* |
| Cancelled (`approval.resolved {cancelled:true}`: Stop, run timeout) | card removed; no snackbar (the transcript shows the cancelled run) |
| Multiple | stacked cards in arrival order; each independent |
| All resolved | sheet closes (`hide()`), banner removed |
| Collapsed | banner shows; transcript and composer usable (queue, stop, read) |
| Reconnecting | buttons disabled, a `LinearProgressIndicator` under the header; the card stays (the Hub keeps it and re-issues it on subscribe) |
| Kernel down | sheet stays open read-only with the progress bar; header supporting text changes to "Cline Hub is not connected." (DS §1.6); if the daemon restarted (new `pid`/`startedAt`), all cards are dropped and the sheet closes (§0.5) |
| Permission missing | n/a (no RUN_COMMAND here) |

### C3.5 Detail preview (`ApprovalPreview`)

`inputJson` is a JSON string (FX); parse it once. The preview is chosen by the
normalized tool name (`normalizeToolName`, DS §7.2). Every branch falls back to the raw
JSON if the input does not match the expected shape (the tool schemas accept unions).

| Tool | Preview lines (`ClineMono.body`, LTR) |
| --- | --- |
| `run_commands` | one line per command: `$ <command>`; structured entries `{command, args}` → `$ command arg1 arg2` (shell-quoted when an arg contains spaces); bare string or `{command}`/`{cmd}` forms accepted |
| `read_files` | one line per file: `<path>` or `<path> (start–end)`; `file_paths`/`paths`/`files` variants accepted |
| `editor` | line 1 `<path>` (`ClineMono.small`); then: `insert_line` present → "Insert at line N" *(Android)* + `+ ` lines of `new_text`; `old_text` null/absent → "Create file" *(Android)* + `+ ` lines; else `- ` lines of `old_text` then `+ ` lines of `new_text`. Max 40 lines each side, then "…" |
| `apply_patch` | the patch text (`input` or the bare string), lines starting `+`/`-` coloured as diff lines, `*** ` headers in `onSurfaceVariant` |
| `fetch_web_content` | per request: the URL (`primary`, not clickable here) and, on the next line, the prompt in `bodySmall onSurfaceVariant` |
| `search_codebase` | one line per query |
| anything else (MCP tools, `spawn_agent`, `team_*`, `skills`, `tasks`) | the raw JSON only, no "Show raw input" button |

Below a non-raw preview, a `TextButton` "Show raw input" / "Hide raw input" *(Android)*
swaps the preview for the desktop's `formatApprovalInput` text (pretty JSON, 2 spaces).
Diff lines use DS §1.5 `diffAddedLine`/`diffRemovedLine` backgrounds and marker colours.
The detail box: `Surface(surfaceContainer, shapes.small)`, 12dp padding, max 176dp then
`verticalScroll` (nested-scroll aware so the sheet drag still works), inside a
`SelectionContainer`. **Lines soft-wrap, nothing scrolls horizontally** (desktop
`.cline-ui-agent-approval-card__detail`: `white-space: pre-wrap; overflow-x: hidden;
overflow-wrap: break-word`), so the tail of a long command, the thing being approved,
is always visible. Implementation: `Text(softWrap = true)` in `ClineMono` with
`LineBreak.Simple`; Android's line breaker already splits a token wider than the line at
a character boundary (the `overflow-wrap: break-word` equivalent), so paths and URLs
need no extra handling and the copied text is unchanged. Each diff/command line is its own paragraph, so a wrapped continuation never
looks like a new `+`/`-`/`$` line: the marker stays at line start and continuation lines
are indented by the marker width (`ParagraphStyle(textIndent = TextIndent(restLine =
2.ch))`). The raw-JSON view ("Show raw input") wraps the same way.

### C3.6 Copy

| Id | Text |
| --- | --- |
| `approval_title` | "Tool approval required" |
| `approval_description` | "Review each tool call and approve or reject it before execution." |
| `approval_request_meta` | "Request %1$s · Iteration %2$d"; without iteration: "Request %1$s" |
| `approval_pending_now` | "Pending now" |
| `approval_approve` / `approval_reject` | "Approve" / "Reject" |
| `approval_approving` / `approval_rejecting` | "Approving..." / "Rejecting..." |
| `approval_stop` | "Stop agent" |
| `approval_error_fallback` | "Could not submit decision." |
| `approval_reject_reason` (sent, not shown) | "This tool call was rejected by the user and not executed." |
| `approval_banner_title` / `approval_banner_action` | "Tool approval required" / "Review" *(Android)* |
| `approval_show_raw` / `approval_hide_raw` | "Show raw input" / "Hide raw input" *(Android)* |
| `approval_create_file` / `approval_insert_at` | "Create file" / "Insert at line %1$d" *(Android)* |
| `request_already_answered` | "This request was already answered." *(Android)* |
| `cd_approval_card` | "%1$s, approval required. %2$s" *(Android)* (`%2$s` = the preview's first line) |

Time format: always `DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)` of
`envelope.timestamp` in the device zone (desktop `formatApprovalTimestamp` →
`toLocaleString()`, date and time, every time); unparseable → "Pending now".

### C3.7 Data bindings and state

| Purpose | Kernel API (FM "Tool approval (approve / reject)", "Stop / abort the agent") |
| --- | --- |
| Request | event `approval.requested {approvalId, sessionId, toolCallId, toolName, inputJson, iteration, agentId, conversationId, policy}`; re-issued on every subscribe → dedupe by `approvalId` |
| Answer | `approval.respond {approvalId, approved, reason?} -> {approvalId, approved}` (`HubPayloads.approvalRespond`); reject sends `reason` = `approval_reject_reason` |
| Resolved anywhere | event `approval.resolved {approvalId, approved, reason?, cancelled?}` |
| Stop | `run.abort {sessionId, reason?}` (C1) |
| Session status | `session.updated` status `pending` while any approval is open (FX) |

```kotlin
@Immutable
data class ApprovalUiState(
    val sessionId: String,
    val cards: List<ApprovalCard>,      // arrival order
    val sheet: SheetVisibility,         // Hidden | Expanded | PartiallyExpanded | Collapsed(banner)
    val reconnecting: Boolean,
)
data class ApprovalCard(
    val approvalId: String, val toolCallId: String?, val toolName: String, val iteration: Int?,
    val requestedAtMs: Long?, val preview: ApprovalPreview, val rawInput: String,
    val showRaw: Boolean, val action: ApprovalAction?,   // Approving | Rejecting
    val error: String?,
)
```

Controller rules:

1. Only approvals whose `sessionId` equals the open session are shown here; others raise
   that session's status dot and the in-app snackbar. (Approvals from a subagent or
   teammate session of the open session: open question 3.)
2. Rejecting does **not** stop the turn (FX `03`: the model receives the rejection as the
   tool result and continues). The "Stop agent" button is the way to stop.
3. A `respond` error leaves the card; `approval_not_found` removes it with the "already
   answered" snackbar.
4. On reconnect with the same daemon (`pid`, `startedAt` unchanged), keep cards and merge
   re-issued events; with a new daemon, drop all (§0.5).
5. The reducer also marks the matching transcript tool row as awaiting approval
   (`ToolStatus.AwaitingApproval`, existing `SessionReducer.approvalRequested`).
6. `inputJson` is never logged (§0.8); a `respond` failure records only `approvalId`
   and the error code.

### C3.8 Interactions and gestures

- Auto-open: when the first card arrives and the chat for that session is visible and
  resumed, `show()`; with exactly one card call `expand()` so both buttons are visible
  without dragging; with more, partially expanded (DS #9 `skipPartiallyExpanded = false`).
  A new card arriving while the banner shows re-opens the sheet only if the previous
  cards were answered; otherwise the banner count updates.
- Swipe down / scrim tap / back → collapse to the banner, never reject (DS §5.8.2).
- Banner "Review" or tap anywhere on the banner → reopens the sheet.
- Approve / Reject: haptic `HapticFeedbackType.Confirm` (API 30+, else `LongPress`) on
  success reply.
- "Stop agent" → `run.abort`; the sheet stays until `approval.resolved {cancelled:true}`
  removes the cards (then closes).
- Long-press inside the detail selects text (copy via the system toolbar).
- Hardware keyboard: on open, focus is on the sheet title (C3.10; no `FocusRequester`
  is pointed at Approve); the first `Tab` stop is Reject of the first card, the second
  Approve, then the next card's controls, then "Stop agent". Enter/Space activate the
  focused control (platform behaviour); Esc collapses the sheet. No sheet-wide key
  shortcuts: keys act only on the control that has focus.

### C3.9 Navigation and back

Not a route. Hosted by the chat screen; deep link `chat/{id}?sheet=approval` (§0.6). On
compact the drawer edge-swipe is disabled while the sheet is open. Back: tooltip → sheet
collapse to banner → (banner itself does not consume back).

### C3.10 Accessibility

- On open, focus moves to the sheet title; TalkBack announces "Tool approval required"
  via the conversation's polite region (DS §8.1.4).
- Each card is a `semantics(mergeDescendants = false)` group with heading = tool name;
  the detail is read as written (DS §8.1.12), raw JSON readable but long, so the preview
  is read first.
- Buttons are 48dp targets; Approve and Reject are never adjacent without the 8dp gap.
- Colour is not the only signal: the header icon and the word "required" carry the
  state; diff lines keep their `+`/`-` markers.

### C3.11 Acceptance checklist (S25U)

1. With auto-approve off, prompt "Run `echo hello` in the terminal": the sheet opens
   fully, showing `run_commands`, the time, "Request approval_… · Iteration 1" and
   `$ echo hello`.
2. Tap "Show raw input": the pretty JSON `{"commands": ["echo hello"]}` shows; tap again
   to return.
3. Approve: the button shows "Approving...", the sheet closes, the tool row runs.
4. Prompt for two file reads in one turn: two cards stack; answer the second first; the
   first stays.
5. Prompt a file edit: the preview shows the path and `-`/`+` lines in diff colours.
6. Swipe the sheet down: the banner "Tool approval required · Review" appears above the
   composer; nothing was rejected (the tool row still waits). Tap Review: the sheet
   returns.
7. Reject: the turn continues and the model acknowledges the rejection (FX `03`).
8. While a card waits, tap "Stop agent": the card disappears and the run ends cancelled.
9. Put the app in the background while a card waits; answer from the notification
   (Notifications spec) or reopen via its tap: the sheet opens on that session.
10. Approve the same request from the Termux TUI attached to the session (or a second
    client): the card disappears in the app.
11. Turn on airplane mode (no effect expected: loopback) — the sheet keeps working.
    Then kill the Hub while a card waits and restart it: the card is dropped and the
    session shows the interrupted run.
12. TalkBack: the header, tool name, preview, Reject and Approve are announced; the
    banner announces "Tool approval required, Review".
13. Tab S7+ landscape: the sheet is centred, 640dp wide, buttons end-aligned.
14. Prompt a `run_commands` call with a command longer than the card (e.g. a 200-char
    `curl` line): it wraps inside the detail box with the `$` only on its first line;
    there is no horizontal scroll; the end of the command is visible without dragging
    sideways. The date and time both show in the meta line.

---

## C4. Ask-question sheet and question banner

### C4.1 Purpose

The agent's `ask_question` tool asks one clarifying question with 2–5 options; the user
picks one or types an answer. The answer is returned to the agent as the tool result.

### C4.2 Desktop equivalent and what was copied

`AgentAskQuestion` (`sdk/packages/ui/components/agent-ask-question.tsx`) rendered after
the transcript by `chat-messages.tsx`, answered through `use-chat-session.ts`
`answerAskQuestion`.

Copied: the "Follow-up question" region, question heading, "Select all that apply." for
multiple choice, the lettered options (A, B, C …, `optionLabel` with base-26 after Z),
de-duplicated options, single choice answers on tap, multiple choice toggles and needs
Submit, the custom-answer field "Or type your own answer…" for single choice only,
Submit enabled rules, "Sending…" with spinner, everything disabled while sending, the
error line with `role="alert"`, keyboard letters / arrows / Enter, fallback error "Could
not submit answer.".
Translated: inline card → `ModalBottomSheet` (DS #10) with the same collapse-to-banner
rule as approvals, so the composer is reachable (Stop, read the transcript).
Note: the kernel's `AskQuestionInputSchema` has no multiple-choice flag (`question`,
`options` 2–5), so multiple choice never arrives over the Hub; the component keeps the
state for parity and previews, and the controller always sets `multiple = false`.

### C4.3 Wireframes

**Compact, single choice:**

```
┌──────────────── drag handle ─────────────────┐
│ Which database should the new service use?   │  titleMedium (heading)
│                                               │
│ ┌───┐                                         │
│ │ A │ PostgreSQL                              │  option rows, min 56dp
│ └───┘                                         │
│ ┌───┐                                         │
│ │ B │ SQLite                                  │
│ └───┘                                         │
│ ┌───┐                                         │
│ │ C │ Keep the current JSON files             │
│ └───┘                                         │
├──────────────────────────────────────────────┤
│ ┌──────────────────────────────┐ [ Submit ]  │  footer pinned above the IME
│ │ Or type your own answer…     │             │
│ └──────────────────────────────┘             │
└──────────────────────────────────────────────┘
```

**Compact, an option tapped (sending):**

```
│ [A] PostgreSQL                      (o)       │  secondaryContainer + 16dp spinner
│ [B] SQLite                                    │  disabled
│ ...                                [Sending…] │
```

**Collapsed banner:**

```
┌──────────────────────────────────────────────┐
│ [?] Follow-up question               Answer  │  min 56dp, warning.container (layout as C3 banner)
└──────────────────────────────────────────────┘
```

**Tablet:** centred sheet, max width 640dp; options keep full width of the sheet.

### C4.4 States

| State | Presentation |
| --- | --- |
| First run | same as Idle |
| Idle | options enabled; accessibility focus on the question heading (C4.9); with a hardware keyboard the first `Tab` stop is option A (desktop `autoFocus` on the first option; the app keeps one initial focus target, as C3); sheet opens automatically when the chat for that session is visible (DS §5.5); otherwise in-app snackbar "Follow-up question" + "Answer" *(Android)*, the dot shows "Question asked" (DS §1.6), background → notification |
| Custom text typed | Submit (`FilledTonalButton`) enabled when the text is not blank |
| Sending | the chosen option (or the Submit button) shows the 16dp spinner; Submit label "Sending…"; all options and the field disabled |
| Error | `bodySmall error` above the footer row, `LiveRegionMode.Assertive`; controls enabled again; text = reply error message, else "Could not submit answer." |
| Answered | sheet closes; the transcript's tool row shows "Asked "…"" with the answer (Chat thread spec) |
| Cancelled (`capability.resolved {cancelled:true}`: Stop, run timeout, our client disconnected) | sheet closes; snackbar "The question was cancelled." *(Android)* unless the cancel came from the user's own Stop |
| Already resolved (`capability.respond` reply `ignored:true`) | sheet closes silently |
| Collapsed | banner |
| Reconnecting | controls disabled + `LinearProgressIndicator` under the question; when the reconnect completes the replay normally contains `capability.resolved {cancelled:true}` (the Hub cancels questions when our client unregisters) → Cancelled |
| Kernel down | sheet closes with the cancelled snackbar (the question cannot survive) |

### C4.5 Copy

| Id | Text |
| --- | --- |
| `question_pane_title` | "Follow-up question" (sheet pane title, desktop `aria-label`) |
| `question_options_label` | "Answer options" |
| `question_multiple_hint` | "Select all that apply." |
| `question_custom_placeholder` / `cd_question_custom` | "Or type your own answer…" / "Custom answer" |
| `question_submit` / `question_sending` | "Submit" / "Sending…" |
| `question_error_fallback` | "Could not submit answer." |
| `question_banner_title` / `question_banner_action` | "Follow-up question" / "Answer" *(Android)* |
| `question_cancelled` | "The question was cancelled." *(Android)* |
| `cd_question_option` | "Option %1$s: %2$s" *(Android)* (letter, label) |

### C4.6 Data bindings and state

| Purpose | Kernel API (FM "Ask-question card") |
| --- | --- |
| Enable the tool | `session.create runtimeOptions.clientContributions:[{kind:'toolExecutor', executor:'askQuestion', capabilityName:'tool_executor.askQuestion'}]` (Home / resume spec; without it the tool is absent, FX) and `client.register capabilities:[…,{name:'tool_executor.askQuestion'}]` (KC step 5) |
| Request | event `capability.requested {requestId, targetClientId, capabilityName, payload{executor, args, context}}` with `capabilityName == "tool_executor.askQuestion"`; `args = [question: String, options: String[]]` (positional, `hub-client-contributions.ts`); `context = {agentId, conversationId, iteration, metadata}` |
| Answer | `capability.respond {requestId, ok:true, payload:{result:"<answer>"}}` with `envelope.clientId = targetClientId` (`HubPayloads.capabilityRespond(requestId, ok = true, result = JsonPrimitive(answer))`) |
| Outcome | event `capability.resolved {requestId, ok, cancelled?, error?}`; reply `{requestId}` or `{requestId, ignored:true}`; error `capability_wrong_client` / `capability_wrong_session` |

Only requests whose `targetClientId` equals this install's `clientId` are shown (another
client, e.g. the TUI, answers its own). Question text, options and the answer are never
logged (§0.8).

```kotlin
@Immutable
data class QuestionUiState(
    val sessionId: String,
    val items: List<QuestionItem>,      // normally 0 or 1
    val sheet: SheetVisibility,
    val reconnecting: Boolean,
)
data class QuestionItem(
    val requestId: String, val question: String, val options: List<String>, // distinct(), order kept
    val multiple: Boolean = false,
    val selected: Set<String>,          // multiple only
    val customAnswer: String,
    val pendingAnswer: String?,         // the option or custom text being sent
    val error: String?,
)
```

### C4.7 Interactions

- Single choice: tap an option → sends that option's text immediately (desktop).
- Custom answer: type, then Submit or IME action Send; hardware Enter submits.
- Multiple (parity only): tap toggles (`secondaryContainer` + trailing `check`), Submit
  sends the selected labels joined with ", " *(Android; the desktop passes an array to
  `onAnswers`, which the Hub path does not use)*.
- Swipe down / back / scrim → collapse to the banner (no answer sent).
- Banner "Answer" → reopen.
- Hardware keyboard, **scoped like the desktop** (`handleOptionKeyDown` is attached to
  the options `<fieldset>` only, `agent-ask-question.tsx:116-150,190`; the custom input
  has its own handler that only intercepts Enter, `:241-242`):
  - `Modifier.onPreviewKeyEvent` on the options `Column`, **not** on the sheet. It acts
    only while an option row has keyboard focus, never while sending, and ignores events
    with Alt/Ctrl/Meta. Letter A… picks the option with that index (single choice:
    answers; multiple: toggles) and moves focus to it; Down/Right and Up/Left move focus
    to the next/previous option, wrapping; Enter/Space activate the focused option.
  - While the custom-answer `OutlinedTextField` has focus, every key goes to the field
    (typing "c" inserts "c"); only Enter without Shift submits (when not blank).
  - Esc collapses the sheet from anywhere in it.
- While the sheet is open the transcript behind is not interactive (modal); collapsing
  gives access to Stop in the composer.

### C4.8 Navigation and back

Not a route; deep link `chat/{id}?sheet=question`. Back order: IME → sheet collapse.

### C4.9 Accessibility

- Opening moves focus to the question heading (`semantics { heading() }`); the polite
  region announces "Follow-up question".
- Options are `Role.Button` (single) or `Role.Checkbox` (multiple) with CD
  `cd_question_option`; the letter key glyph is decorative.
- The footer stays above the IME (`imePadding`) and above the navigation bar.
- Errors are assertive (desktop `role="alert"`).

### C4.10 Acceptance checklist (S25U)

1. Prompt: "Before doing anything, use the ask_question tool to ask me which colour I
   prefer, with the options Red, Green and Blue." The sheet opens with the question and
   options A–C.
2. Tap "B Green": the row shows a spinner, everything disables, the sheet closes, the
   agent continues with "Green".
3. Repeat; type "Purple" in the custom field and Submit: the agent gets "Purple".
4. Repeat; swipe the sheet down: the banner "Follow-up question · Answer" appears; tap
   Answer: the sheet returns with the same state.
5. Repeat; tap Stop in the composer while collapsed: the banner disappears, the run ends
   cancelled, no "cancelled" snackbar (own Stop).
6. Repeat; force-stop the app from Android settings, reopen: the question is gone and the
   transcript shows the run continued without an answer (the Hub cancelled it).
7. TalkBack: the heading is read first, options read "Option A: Red", the custom field
   reads "Custom answer".
8. Tab S7+ with the keyboard: Tab to option A, press "c": the agent gets "Blue". On the
   next question, Tab into "Or type your own answer…" and type "cyan": the field reads
   "cyan" and nothing was submitted; Enter submits "cyan".

---

## C5. Slash menu (`/team`, skills, workflows)

### C5.1 Purpose

Type `/` to pick a built-in command or one of the user's skills and workflows; the menu
inserts `/name ` and the send pipeline runs it.

### C5.2 Desktop equivalent and what was copied

`chat-input-bar.tsx` slash popover (`getActiveSlash`, `BUILTIN_SLASH_COMMANDS`,
`buildUserInstructionSlashCommands`, `filteredSlashCommands`, `insertSlashCommandItem`,
`cachedSlashCommands`), with resolution from the sidecar (`expandRuntimeSlashCommand`,
`rewriteDesktopTeamPrompt`) and the CLI (`expandUserCommandPrompt`).

Copied: activation rules (slash at start or after whitespace, no whitespace in the query,
no second command after a completed one), the `/team` built-in and its description,
fallback descriptions "Skill command" / "Workflow command", de-duplication with built-ins
first and skills owning collisions with workflows, the unfiltered list in build order
(no sort), filtering (contains, prefix matches first, then `localeCompare`), max 10
rows, insertion of `/name` plus a conditional separator keeping text after the cursor, menu copy "Loading commands...", "No matching commands", "Loading...", cache
across composer instances refreshed on each open, Esc dismissal per token.
Translated: the popover becomes a suggestion panel docked above the composer (a
`DropdownMenu` would take focus from the IME).
Not copied: plugin commands (`list_plugin_commands` / `runPluginSlashCommand` are
sidecar-only; open question 5), and the desktop's `/fork` built-in: decided A19
(2026-10-08), the fork lives in the chat's three-dot only, so typed `/fork` is sent as
text like any unknown command. The popup holds `/team`, `/compact` once a kernel has it
(A14, hidden until then), and the owner's skills, workflows and plugin commands.

### C5.3 Wireframes

**Compact, `/` typed in an empty conversation composer:**

```
┌──────────────────────────────────────────────┐
│ /team                                         │  two-line rows, 56dp
│ Start the task with an agent team             │
│ /code-review                                  │  skill
│ Review the diff for correctness               │
│ /release-notes                                │  workflow, no description
│ Workflow command                              │
└──────────────────────────────────────────────┘  max 224dp, scrolls
┌──────────────────────────────────────────────┐
│ /|                                       [mic]│
│ [@] [plan|ACT] (M) (T)              o   [^]  │
└──────────────────────────────────────────────┘
```

**Loading with nothing cached:**

```
│ Loading commands...                           │  one 48dp row
```

**No match:**

```
│ No matching commands                          │
```

**Tablet:** the panel spans the composer width (≤ 840dp); with a hardware keyboard the
highlighted row uses `secondaryContainer`.

No fork confirmation here any more: the `/fork` built-in and its dialog were removed in
batch 2 (A19).

### C5.4 States

| State | Panel |
| --- | --- |
| Hidden | no active slash token, or dismissed for this token |
| First open, loading | built-ins shown immediately (they need no kernel); a trailing 32dp row "Loading..." (`labelSmall onSurfaceVariant`) while `settings.list` runs (desktop shows "Loading commands..." only when nothing at all is cached; built-ins are always cached here) |
| Populated | ≤ 10 rows |
| Refreshing | cached rows + trailing "Loading..." row |
| Empty filter | one row "No matching commands" |
| Kernel down / reconnecting | built-ins only, no loading row (skills and workflows need the Hub; the last cached list is shown if present) |
| Error (`settings.list` failed) | keep built-ins and the cache (desktop "Keep built-in commands on error"); no error row |

### C5.5 Copy

| Id | Text |
| --- | --- |
| `slash_team_desc` | "Start the task with an agent team" |
| `slash_skill_desc_fallback` / `slash_workflow_desc_fallback` | "Skill command" / "Workflow command" |
| `slash_loading_commands` / `slash_no_match` / `slash_loading_tail` | "Loading commands..." / "No matching commands" / "Loading..." |
| `fork_failed` | "Fork failed: %1$s" (CLI; now the chat three-dot's fork error, home-chat §3.4.2) |
| `team_usage` | "Usage: /team <task description>. Starts a team of agents for the given task." (desktop) |
| `team_unavailable_mode` | "Agent teams are not available in plan mode." (desktop template with `plan`) |
| `team_disabled` / `action_open_tools` | "Agent teams are disabled. Enable the Teams tool in Customize → Tools." (desktop wording, with the app's screen name for "Customizations → Tools") / "Open Tools" *(Android)* |
| `skill_read_failed` | "Could not read the skill %1$s." *(Android)* |
| `workflow_read_failed` | "Could not read the workflow %1$s." *(Android)* |
| `cd_slash_panel` | "Command suggestions" *(Android)* |

### C5.6 Data bindings and state

| Purpose | Kernel API (FM "Slash commands for skills and workflows") |
| --- | --- |
| List | `settings.list {workspaceRoot, cwd}` → `snapshot.skills`, `snapshot.workflows` (`CoreSettingsItem {id, name, path, kind, source, enabled?, description?}`); refreshed on each panel open and on event `settings.changed` |
| Skill invoke | mirrors the desktop sidecar and the CLI (`expandRuntimeSlashCommand` / `shouldExpandSkillSlashCommands`: `expandSkillCommands = !isSkillsToolAvailable(…)`). The daemon itself never expands slash commands on the turn path (`prepareTurnInput`, `local-runtime-host.ts:2130-2179`, only normalizes, resolves @mentions and wraps the mode), so the client decides: **`skills` tool available** (`skills` not in `global-settings.json` `disabledTools`, the list the desktop passes as `disabledToolIds`; not `snapshot.tools`, which in core 0.0.91 holds plugin tools only, `settings-service.ts`; the act and plan tool presets both enable skills, only yolo, never set by the app, does not: `core/src/extensions/tools/presets.ts`) → sent as typed, `/name args`, and the model loads the skill through the `skills` tool (the persisted transcript keeps the typed command, as upstream intends); **otherwise** → expanded client-side like a workflow, from `item.path` (the skill's `SKILL.md`) |
| Workflow invoke | RUN_COMMAND background `cat <item.path>` (`ClineCommands` new `readTextFile(path)`), strip a leading YAML front matter block (`---` … `---`), then the CLI's `expandUserCommandPrompt`: `<user_command slash="<name>">instructions</user_command>` + (" " + rest when rest is not blank) via `formatUserCommandBlock` |
| `/team <task>` | rewrite to `formatUserCommandBlock("spawn a team of agents for the following task: <task>", "team")` (desktop `rewriteDesktopTeamPrompt`); an empty task gets the usage error first, then it is refused when the `teams` tool is disabled (`global-settings.json` `disabledTools` has `teams`, read at send time as the desktop's `readGlobalSettings().disabledTools.has("teams")`; `settings.list` cannot tell, its `snapshot.tools` holds plugin tools only), then in plan mode; requires the session to have been created with `enableAgentTeams: true` (the shared `NewSessionSpec`, C1.7) |

```kotlin
data class SlashCommand(val name: String, val description: String, val kind: SlashKind, val path: String?)
enum class SlashKind { BuiltinTeam, Skill, Workflow }

sealed interface SuggestionMenuState {
    val highlighted: Int
    data class Slash(val query: String, val items: List<SlashCommand>, val loading: Boolean,
                     override val highlighted: Int) : SuggestionMenuState
    data class Mention(val query: String, val items: List<String>, val loading: Boolean,
                       val refreshing: Boolean, val problem: MentionProblem?,
                       override val highlighted: Int) : SuggestionMenuState
}
```

Name rules: `name = normalizeRuntimeCommandName(item.name)` (port of
`runtime-commands.ts`: lowercase, whitespace → "-", other symbols → "-", Unicode letters
and digits kept); items with `enabled == false` are skipped; a workflow whose name equals
a skill's is dropped; the built-in name `team` hides user commands of the same
name. Order is the build order, **not sorted**: built-ins first, then skills and
workflows in the order `settings.list` returns them (desktop `slashCommands`). The
unfiltered list is `slashCommands.take(10)` (desktop `slashCommands.slice(0, 10)` for an
empty query); a non-empty query filters by `name.lowercase().contains(query)`, sorts
prefix matches first and the rest with a locale-aware `Collator` (desktop
`localeCompare`), then takes 10. Cache: process-wide
(`SlashCommandSource` singleton) keyed by workspace root.

### C5.7 Send-time resolution (in `SendPipeline`, order matters)

1. Trim. (Batch 1's `/fork` step is removed, A19: `/fork` falls through to step 5.)
2. If it starts with `/team` followed by whitespace or end, in the desktop's
   `rewriteDesktopTeamPrompt` order: empty task → snackbar `team_usage`, keep the draft;
   `teams` in `global-settings.json` `disabledTools` (RUN_COMMAND `cat`, read at send time
   like the desktop's `readGlobalSettings()`; a missing or unreadable file disables
   nothing) → snackbar `team_disabled` with action "Open Tools" (→ Customize → Tools),
   keep the draft, no session is created; mode Plan → snackbar `team_unavailable_mode`,
   keep the draft; else rewrite and send. Not the cached `settings.list` snapshot: in
   core 0.0.91 its `tools` are plugin tools only, so a built-in `teams` entry never
   appears there (found by the 2026-10-08 real-kernel check, V-TEAM).
3. If the first token `/<name>` matches a workflow in the cache: read it (spinner in the
   Send slot; 60 s RUN_COMMAND timeout); failure → snackbar `workflow_read_failed`, keep
   the draft; else expand and send.
4. If the first token matches a **skill**: when the `skills` tool is available (`skills`
   not in `global-settings.json` `disabledTools`, read as in step 2; Plan and Act both
   qualify)
   send unchanged; otherwise read `item.path` exactly like
   step 3 (RUN_COMMAND `cat`, strip the leading YAML front matter, 60 s timeout; failure
   → snackbar `skill_read_failed`, keep the draft) and send
   `formatUserCommandBlock(instructions, name)` + (" " + rest when rest is not blank)
   (CLI `expandUserCommandPrompt`).
5. Otherwise (unknown `/words`, `/fork`, normal text): send unchanged.

### C5.8 Interactions

- Panel opens as the user types `/` per `getActiveSlash` (ported with its unit tests).
  Typing filters. Tap a row → `insertSlashCommandItem` ported verbatim: replace only the
  typed `/query` (`slashIndex` up to the cursor), keep the suffix after the cursor,
  `separator = if (suffix.firstOrNull()?.isWhitespace() == true) "" else " "`,
  new text = `prefix + "/" + name + separator + suffix`, cursor =
  `slashIndex + name.length + 2` (just past the separator, or past the suffix's own
  leading whitespace). The whitespace ends the token, which closes the panel.
- Back while open → dismiss for this token (re-typing a different query reopens it).
- Hardware keys per C1 §1.8.
- The panel is one of the composer's `DockedChildren` (C1.3): `Surface(surfaceContainer,
  RectangleShape, tonalElevation 0)` inside `ComposerSurface`, above the field row and
  separated from it by a 1dp `outlineVariant` divider (the outer surface owns the
  rounded corners and the border, so there is no seam), 4dp vertical padding, `LazyColumn` max height
  **224dp** (desktop `max-h-56`). Rows: headline `/name` `labelLarge onSurface`
  (SemiBold), supporting description `bodySmall onSurfaceVariant` 1 line ellipsized,
  16dp horizontal padding, min 56dp; the highlighted row (hardware keyboard only)
  `secondaryContainer`.
- The panel never takes focus; the IME stays up.

### C5.9 Navigation and back

Part of the composer stack. Back closes it before anything else in the composer (DS
§5.8 item 5).

### C5.10 Accessibility

- The field gets `semantics { stateDescription = "%d suggestions" }` *(Android)* when the
  panel opens; TalkBack users swipe into the panel (it follows the field in traversal
  order because it is drawn above it: set `traversalIndex` so the field comes first).
- Each row: `Role.Button`, CD "/name, description".
- The panel is a `CollectionInfo(rowCount = n, columnCount = 1)`.

### C5.11 Acceptance checklist (S25U)

Preconditions: one skill and one workflow configured (they show in Customize).

1. Type `/` in an empty composer: "/team" appears at once (no "/fork", A19), the skill
   and workflow appear after "Loading..." disappears.
2. Type `/te`: only "/team" remains (prefix first); type `/zz`: "No matching commands".
3. Tap the workflow: the field reads "/<workflow> " with the cursor after the space;
   send it: the agent follows the workflow's instructions; the strip/transcript show the
   command form (Chat thread spec decides the bubble text). Same for the skill: with
   the Skills tool on, the agent calls the `skills` tool for it; turn the Skills tool off
   in Customize → Tools and send it again: the skill's instructions are inlined and the
   agent follows them without a `skills` call. Typing `/` with the cursor before
   " world" and tapping a command gives "/name world" (no double space).
4. Send "/team" alone: the usage snackbar shows and the draft stays, also with the Teams
   tool disabled.
5. In Plan mode send "/team write a script": "Agent teams are not available in plan
   mode." With the Teams tool disabled in Customize → Tools, "/team write a script" in
   Act shows "Agent teams are disabled. Enable the Teams tool in Customize → Tools." and
   "Open Tools"; from Home no session is created.
6. Send "/fork" in a session: it goes to the model as typed text (no dialog, no fork;
   the fork is the three-dot's "Fork", A19).
7. Kill the Hub and type `/`: only the built-ins appear, no loading row.
8. Tab S7+ keyboard: arrows move the highlight, Tab inserts, Esc closes.

---

## C6. Mention menu (`@files`)

### C6.1 Purpose

Type `@` to reference a workspace file; the daemon attaches the file's content to the
turn (`enrichPromptWithMentions` resolves `@relative/path` tokens against the session's
cwd and its file index), so the app only has to insert the path text.

### C6.2 Desktop equivalent and what was copied

`chat-input-bar.tsx` mention popover (`getActiveMention`, `search_workspace_files`
with `limit: 10`, 120 ms debounce, per-query cache keyed by environment + workspace +
query, `insertMentionFile` inserting `@path `). Copied: activation (`@` at start or
after whitespace, no whitespace or second `@` in the token), debounce, limit, cache,
"Searching files..." / "No matching files" / "Updating...", insertion and cursor
placement, Esc dismissal per token. Translated: sidecar `search_workspace_files` →
RUN_COMMAND file search inside Termux (FM "@file mentions": `mention_files.search` is
undispatched on the Hub); popover → the shared suggestion panel.

### C6.3 Wireframes

**Compact, `@pars` typed:**

```
┌──────────────────────────────────────────────┐
│ src/parser.ts                                 │  48dp rows, ClineMono.small
│ src/parser/tokens.ts                          │  middle-ellipsized
│ test/parser.test.ts                           │
│ docs/parsing.md                               │
└──────────────────────────────────────────────┘
│ Fix the bug in @pars|                    [mic]│
```

**Searching (nothing cached):**

```
│ Searching files...                            │
```

**Cached results while a new search runs:**

```
│ src/parser.ts                                 │
│ …                                             │
│ Updating...                                   │  32dp tail row, labelSmall
```

**No workspace / kernel down / permission missing:**

```
│ Choose a workspace to mention files.   Choose │  TextButton → SP S9
│ Start kernel to search files.                 │
│ Cline can't run commands in Termux yet. Allow │  TextButton → permission (SP §0.5)
```

### C6.4 States

| State | Panel |
| --- | --- |
| Hidden | no active `@` token, or dismissed |
| Searching (first time for this query, nothing shown yet) | "Searching files..." |
| Populated | ≤ 10 paths |
| Refreshing | previous results + "Updating..." |
| Empty | "No matching files" |
| No workspace (Home with "Just chat", SP S9) | "Choose a workspace to mention files." + "Choose" |
| Kernel down | "Start kernel to search files." (the search needs Termux and the session cwd) |
| Permission missing (RUN_COMMAND) | "Cline can't run commands in Termux yet." + "Allow" (SP §0.5 wording) |
| Error (exit ≠ 0, timeout 10 s) | "Could not search files." *(Android)*; cached results, if any, stay above it |

### C6.5 Copy

| Id | Text |
| --- | --- |
| `mention_searching` / `mention_no_match` / `mention_updating` | "Searching files..." / "No matching files" / "Updating..." |
| `mention_no_workspace` / `mention_choose` | "Choose a workspace to mention files." / "Choose" *(Android)* |
| `mention_kernel_down` | "Start kernel to search files." *(Android)* |
| `mention_permission` / `mention_allow` | "Cline can't run commands in Termux yet." / "Allow" (SP §0.5) |
| `mention_error` | "Could not search files." *(Android)* |
| `cd_mention_row` | "File %1$s" *(Android)* |
| `cd_mention_panel` | "File suggestions" *(Android)* |

### C6.6 Data bindings and state

| Purpose | Kernel API |
| --- | --- |
| Search | RUN_COMMAND background `$PREFIX/bin/bash` with args `["-c", "cd \"$1\" && { if command -v rg >/dev/null 2>&1; then rg --files --hidden -g '!.git' 2>/dev/null; else find . -type f -not -path './.git/*' -not -path '*/node_modules/*' -printf '%P\\n' 2>/dev/null; fi; } | grep -i -F -- \"$2\" | head -n 200", "_", <workspaceRoot>, <query>]` (FM: "RUN_COMMAND rg --files <workspace> \| head -2000 (or find -type f)"; filtered per query so the 100 KB result cap is never hit). New `ClineCommands.searchWorkspaceFiles(root, query)` |
| Workspace | the session's `workspaceRoot` (`session.list`/`session.get`), or on Home the selected workspace (`WorkspaceStore`, SP S9) |
| Insert | none: the text `@<relative path> ` goes into the prompt; the daemon resolves it |

Ranking (in the app, after the command returns, take 10): exact basename match >
basename starts with query > basename contains query > path contains query; ties by
shorter path, then alphabetical. Empty query: the first 10 by depth then name.
Debounce 120 ms; one command in flight per composer (a newer query cancels the wait for
the older result, whose result is still cached). Cache: `LruCache(64)` keyed by
`(workspaceRoot, query)`, cleared when the workspace changes or after a turn that ran
`editor`/`apply_patch`/`run_commands` (files may have changed).

### C6.7 Interactions, navigation, accessibility

- Tap a row → replace the token with `@<path> ` and place the cursor after the space
  (desktop `insertMentionFile`); the panel closes because the token ended.
- Paths render LTR (DS §8.2.6) in `ClineMono.small`, middle ellipsis (keep the
  basename visible).
- Back dismisses for this token; hardware keys per C1 §1.8.
- TalkBack: rows read "File src/parser.ts"; the panel has collection semantics as C5.

### C6.8 Acceptance checklist (S25U)

1. In a workspace session type "explain @": up to 10 files appear (Searching files...
   first time).
2. Type "@pars": rows narrow to paths containing "pars", basename matches first.
3. Tap `src/parser.ts`: the field reads "explain @src/parser.ts " and the cursor is
   after the space; send it: the model quotes the file without a `read_files` call.
4. Type "@zzzz": "No matching files".
5. On Home with "Just chat": "Choose a workspace to mention files." and "Choose" opens
   the workspace picker.
6. A workspace without ripgrep installed in Termux (`pkg uninstall ripgrep` in a test
   phase only, or a device without it) still returns results via `find`.
7. Kill the Hub: the panel shows "Start kernel to search files."

---

## C7. Voice input entry

### C7.1 Purpose

Dictate a prompt into the composer with on-device speech recognition (FM "Voice input":
`android.speech.SpeechRecognizer` into the composer, no kernel involvement). Text appears
as you speak; Stop ends the session; nothing is sent automatically.

### C7.2 Desktop equivalent and what was copied

`SpeechInput` (`components/ai-elements/speech-input.tsx`) in `ChatInputBar`'s actions,
with the streaming insertion handlers (`handleStreamingTranscriptionStart/Change/End`)
and the settings page `voice-input-view.tsx` ("Speak instead of typing… Text appears as
you speak; Stop ends the live session.").

Copied: the mic only appears when voice input is usable (desktop: once a voice model is
configured), "Record speech" / "Stop recording" labels and `aria-pressed`, the pulsing
ring while listening, the spinner while processing, the field read-only and Send
disabled while active, insertion at the cursor with automatic spacing ("leadingSpace"
when the text before does not end in whitespace, "trailingSpace" when the text after
does not start with whitespace), live replacement of the inserted range by newer
partial results, abandoning the live range if anything else changed the draft, the
"Speech input failed" error with the microphone-permission description.
Translated: provider streaming transcription → Android `SpeechRecognizer` (the voice
app's proven setup: partial results, RMS level, restart delays).

### C7.3 Wireframes

**Idle (mic in the field row):**

```
│ Enter your question or type / for comman… [mic]│
```

**Listening (field read-only, live text, level ring):**

```
┌──────────────────────────────────────────────┐
│ Fix the parser so that empty lines    ((stop))│  error-filled button; ring pulses with RMS
│ are skipped|                                  │
├──────────────────────────────────────────────┤
│ [@] [plan|ACT] (M) (T)              o   [^]  │  Send disabled
```

**Processing the last utterance:**

```
│ Transcribing voice input…                 (o) │  spinner in the mic slot
```

### C7.4 States

| State | Mic slot | Field |
| --- | --- | --- |
| Unavailable | not composed (desktop: no dead control) | normal |
| Disabled by setting | not composed (open question 2: Settings toggle "Enable voice input") | normal |
| Idle | `IconButton` `mic` `onSurfaceVariant`, CD "Record speech", tooltip "Record speech" | normal |
| Permission missing | same as Idle; tap → `RECORD_AUDIO` request (system dialog). Denied → snackbar "Speech input failed. Check the microphone permission for Cline and try again." with action "Settings" *(Android)* (opens app details) once "Don't ask again" applies |
| Listening | `FilledIconButton(containerColor = error, contentColor = onError)`, icon `stop`, CD "Stop recording", `stateDescription` "Listening"; behind it a 48dp circle in `error @ 30 %` scaled 1.0→1.35 by the RMS level (voice app mapping `((rmsdB + 2) / 12).coerceIn(0, 1)`, smoothed 100 ms; static under reduced motion) | read-only; placeholder "Listening…" while empty; partial text shown in place |
| Processing | 20dp `CircularProgressIndicator` in the slot, CD "Transcribing voice input" | read-only; placeholder "Transcribing voice input…" while empty |
| Error | back to Idle + snackbar | normal |
| Kernel down | not composed: dictation itself needs no kernel, but the field is read-only in that state (C1.6), so there is nothing to dictate into | read-only |
| Busy agent | allowed (dictating a follow-up to queue) | |

Availability = `SpeechRecognizer.isRecognitionAvailable(context)` (needs a `<queries>`
entry for `android.speech.RecognitionService`; the voice app notes the system recogniser
may still work when it reports false, so a `false` result is re-checked by trying
`createSpeechRecognizer` once on first use) and `RECORD_AUDIO` declared.

### C7.5 Copy

| Id | Text |
| --- | --- |
| `cd_record_speech` / `cd_stop_recording` | "Record speech" / "Stop recording" |
| `cd_transcribing` | "Transcribing voice input" |
| `voice_state_listening` | "Listening" *(Android)* |
| `voice_failed_permission` | "Speech input failed. Check the microphone permission for Cline and try again." |
| `voice_failed_generic` | "Speech input failed. %1$s" |
| `voice_err_network` | "Speech recognition needs a network connection on this device." *(Android)* |
| `voice_err_busy` | "Speech recognition is busy. Try again in a moment." *(Android)* |
| `voice_err_audio` | "The microphone is in use by another app." *(Android)* |
| `voice_err_unavailable` | "Speech recognition is not available on this device." *(Android)* |
| `action_settings` | "Settings" *(Android)* |

### C7.6 Data bindings and state

No kernel API. `VoiceInputController` (adapted from the voice app's `VoiceService`
recogniser code, with the DS origin header):

- `SpeechRecognizer.createSpeechRecognizer(context)`; intent
  `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` with `EXTRA_LANGUAGE_MODEL = FREE_FORM`,
  `EXTRA_PARTIAL_RESULTS = true`, `EXTRA_PREFER_OFFLINE = true`, `EXTRA_LANGUAGE` = the
  app's current locale tag (system default).
- Continuous dictation until the user taps Stop: on `onResults` commit the text and
  restart after 250 ms; `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` restart after 250 ms;
  two timeouts in a row with no text (≈10 s of silence) end the session; `ERROR_RECOGNIZER_BUSY`
  retries once after 900 ms, then `voice_err_busy`; `ERROR_AUDIO` → `voice_err_audio`;
  `ERROR_NETWORK*` → `voice_err_network`; `ERROR_INSUFFICIENT_PERMISSIONS` →
  `voice_failed_permission`; others recreate the recogniser once, then
  `voice_failed_generic` with the error name.
- Tap Stop → `stopListening()`; the final `onResults` commits; state → Processing until
  it arrives (max 3 s, then Idle).
- Lifecycle: stop and `destroy()` on `ON_STOP`, on leaving the chat, on send, on
  session switch.

```kotlin
sealed interface VoiceState {
    data object Unavailable : VoiceState
    data object Idle : VoiceState
    data class Listening(val level: Float, val partial: String) : VoiceState
    data object Processing : VoiceState
}
```

Insertion (port of the desktop handlers): on start remember `range = selection` and
`expected = current text`; each partial/final result replaces `range` with
`leadingSpace + text + trailingSpace` and updates `range`/`expected`; if the field text
no longer equals `expected` (another writer), drop the range and ignore further results
of this utterance. The cursor goes to the end of the inserted text.

Manifest addition: `<uses-permission android:name="android.permission.RECORD_AUDIO"/>`
and `<queries><intent><action android:name="android.speech.RecognitionService"/></intent></queries>`
(not in KC's manifest list yet; open question 1).

### C7.7 Interactions, navigation, accessibility

- Tap mic → permission if needed → Listening. Tap again → stop.
- The IME may stay open; its own dictation key is independent.
- Back while listening stops listening first (a `BackHandler` registered only while
  listening), then normal composer back.
- TalkBack: the mic is a toggle (`Role.Switch` semantics are wrong for a recorder; use
  `Role.Button` + `stateDescription` "Listening"); starting announces "Listening"
  politely; the inserted text is not announced per partial (only the final result,
  through the field's own semantics when focused).
- Haptic `LongPress` on start and stop (voice app precedent for mic toggles).

### C7.8 Acceptance checklist (S25U)

1. With a recogniser available, the mic shows in the field row; on a device or profile
   without one it is absent.
2. First tap asks for the microphone; deny twice: the snackbar with "Settings" appears;
   "Settings" opens the app's permission page.
3. Grant, tap the mic, say "fix the parser": words appear live at the cursor with a
   space inserted before them; Send is disabled while listening.
4. Pause 10 s: listening ends by itself; the text stays; Send enables.
5. Type in the middle of the text, then dictate: the words go in at the cursor with
   correct spacing.
6. Background the app while listening: recording stops (no microphone indicator stays
   in the status bar).
7. Under "Remove animations" the level ring is static.

---

## C8. Welcome setup notice (Home)

### C8.1 Purpose, desktop equivalent

Tell a user with no connected model how to start, right above the composer (desktop
`welcome-setup-notice.tsx`, DS §4.2 `WelcomeSetupNotice`). Copied: icon tile, title,
body, primary "Connect a model" and secondary "Model settings", polite announcement.

### C8.2 Layout, states, copy

DS §4.2 defines the component and copy: "Connect a model to start building", "Sign in
with Cline or add an API key — it takes under a minute.", `Button` "Connect a model",
`TextButton` "Model settings". Placement: docked stack item 5 (§0.4), full composer
width, 12dp above the composer.

| State | Shown |
| --- | --- |
| Loading (catalog not read yet) | not shown (no flash) |
| No configured provider | shown |
| Configured | not shown |
| Kernel down / Termux missing | not shown (the composer's read-only row and Kernel setup take over) |

Actions: "Connect a model" → Connect a model screen (first-run flow, Kernel/onboarding
spec); "Model settings" → `settings/providers` (SP S4).

### C8.3 Data binding

`ProviderCatalogRepository` (SP §0.7) `configured` flags; in fallback mode RUN_COMMAND
`cat …/providers.json` parsed in memory (FM "Welcome setup notice": "Read once at launch
and after any auth action; never persist the file contents").

### C8.4 Acceptance

1. On a fresh Termux home without providers, Home shows the notice above the composer
   and TalkBack announces it once.
2. After `cline auth --provider …` (from the app) the notice disappears without
   restarting the app.

---

## 8. String resources summary

All strings above live in `res/values/strings.xml` with the ids given; plurals for
`queue_count` and `queue_attachments`; positional formats only (DS §8.2.3). Kernel text
(tool names, `error.message`, question text and options) is shown untranslated (DS
§8.2.8). Ellipses are copied exactly: "..." in "Agent is working...", "Approving...",
"Rejecting...", "Loading commands...", "Searching files...", "Updating...",
"Loading...", "Creating forked session...", "Starting kernel...", "Resuming
session...", "Connecting...", "Reconnecting..."; "…" in "Transcribing voice input…",
"Or type your own answer…", "Sending…", "Listening…". The desktop's curly apostrophe is
kept in "doesn’t".

## 9. Additions this spec needs in the design system

DS says screen specs add new things to it first. These are not in DS yet and are listed
here so they can be folded in (open question 1):

1. Icons (DS §7.4): `mic` (voice idle), `stop` reused for recording, `photo_library`,
   `photo_camera`, `description` (file attachment / Files), `contact_support` reused for
   the question banner.
2. Components (DS §4.2): **QuestionBanner** (same as ApprovalBanner with
   `contact_support` and action "Answer"); **SuggestionPanel** (C5/C6 panel spec in
   C5.8); **AttachSheet** (C1.9); **Starting slot** (C1.3).
3. DS #6: the settings-row overflow behaviour on compact widths (C1.4); Stop colours
   `error`/`onError` (C1.3); Send/Stop in the settings row and the mic in the field row
   (C1.2); one `ComposerSurface` owning the border with the queue strip and suggestion
   panel as children (C1.3); `heightIn(min = …)` instead of the fixed "Settings row
   48dp"; the text field `decorator` + `TextFieldLineLimits` instead of
   `TextFieldDefaults.DecorationBox`; `readOnly` (not disabled) for kernel down.
4. Manifest (KC): `RECORD_AUDIO`, `<queries>` for `android.speech.RecognitionService`,
   a `FileProvider` for camera captures.
5. DS §9 "Behaviour" bullet says "Rejecting sends the desktop rejection reason"; it must
   say the FM reason "This tool call was rejected by the user and not executed." (as DS
   #9 already does; C3.2 is the source of truth).
6. KC: `ClineCommands.writeAttachment` (stdin, chunked, C1.9), `readTextFile` (C5),
   `searchWorkspaceFiles` (C6) as new RUN_COMMAND helpers.

## 10. Open questions

1. **Design-system and manifest additions** in §9 need to be folded into
   `design/design-system.md` and `docs/02-kernel-contract.md` by their owners.
2. **Voice input setting.** The desktop shows the mic only after Settings → Voice input
   is configured. Android needs no model, so this spec shows the mic whenever a
   recogniser exists. Should Settings (SP) carry an "Enable voice input" switch (desktop
   label), and is it on by default?
3. **Approvals and questions from subagents/teammates.** `approval.requested.sessionId`
   for a spawned agent may be the child session. Should the open (parent) session's sheet
   show them, labelled with the agent, or should they only raise the child session's
   status? Needs a team-run recording (FX has none).
4. ~~**Skills pass-through (OQ 9).**~~ Closed by code read (2026-10-03): the daemon
   never expands slash commands on the turn path; the desktop and CLI send `/skill`
   as typed only when the `skills` tool is available and expand it client-side
   otherwise (`expandSkillCommands`), and C5.7 step 4 now does the same. What remains
   is OQ 9's device check that Hub sessions advertise the `skills` tool when a skill is
   configured; if they do not, C5.7 step 4's expansion branch is the fix (treat the
   tool as unavailable).
5. **Plugin slash commands.** Desktop lists and runs plugin commands
   (`list_plugin_commands`, sidecar-run handlers). The Hub exposes neither; omitted here.
   Is there a Hub path planned upstream?
6. **Workflow display in the transcript.** Expanding a workflow client-side persists
   `<user_command slash="x">…instructions…</user_command>`; `formatDisplayUserInput`
   would show "/x <instructions>" rather than the desktop's raw "/x". The Chat thread spec
   may need to collapse `user_command` bodies to "/x" + rest for display.
7. **File attachments via RUN_COMMAND stdin.** C1.9 writes files through
   `RUN_COMMAND_STDIN` in chunks of ≤ 128 Ki chars (≈ 256 KB UTF-16 per intent, under the
   ~1 MB Binder transaction buffer). Verify on the S25U with a 1 MB file that every chunk
   arrives intact and in order (byte count and `sha256sum` match), that Termux's
   background runner accepts stdin of that size, and whether `ripgrep` is present in a
   default port install (C6).
8. ~~**Reasoning changes mid-turn.**~~ Closed by code read (2026-10-03):
   `handleSessionUpdateConnection` has no running guard
   (`core/src/hub/server/handlers/session-handlers.ts:1092-1116`) and
   `updateSessionConnection` mutates `session.config` in place
   (`core/src/runtime/host/local-runtime-host.ts:1599-1626`), so a change during a turn is
   accepted and applies from the next provider request of that same turn (C1.6). The
   ReasoningChip stays enabled while busy (desktop); the ModelChip stays disabled while
   busy (desktop `disabled={isBusy}`).
9. **Auto-approve toggle placement.** FM lists an "Auto-approve all toggle"
   (recreate-session based) but the desktop composer has none; which spec places it
   (Home/new session, conversation header overflow)? The approval sheet deliberately has
   no "Always allow".
10. **Usage ring cost scope.** This spec uses the lead session's `usage.totalCost`; the
    sessions screen may show `aggregateUsage` (with subagents). Pick one meaning for
    "Cost" app-wide.
11. **Large image frames on the device Bun.** 8 MiB of data URLs in one
    `session.send_input` frame works on the workstation `ws`; verify the device Bun's
    built-in WebSocket server accepts frames that large (relates to OQ 1).
12. **Context window without the settings helper.** In fallback mode the catalog has no
    `contextWindow`, so the ring is hidden. Bundle a small table for the fallback models?

## 11. Review log (2026-10-03)

Every finding was checked against the sources listed at the top before the change.

| Finding | Resolution |
| --- | --- |
| `PickMultipleVisualMedia(maxItems ≤ 1)` crashes; no cap at 5 images on the row | Fixed: three-way launcher rule, cap on Camera, Files, paste and drop; sheet dismissed before any picker (C1.9) |
| File cap 100 KB and argv transport | Fixed: stdin transport, chunked, 1 MB *(Android)* ceiling, `*/*` picker, NUL check (C1.9, §0.8, OQ 7) |
| Unsupported images only gated on Photos/Camera | Fixed: step 0 for every ingress (C1.9). Reviewed: the desktop drop zone itself does not filter (`page.tsx` `handleAttachFiles`); the app applies the rule uniformly and says so (C1.2) |
| Approval detail scrolls horizontally | Fixed: soft-wrap like `agent-approval-card.css` (C3.5) |
| Context bar formula without clamp | Fixed: desktop lines quoted, unit tests (C1.12) |
| Fixed row heights vs font scale 2.0 | Fixed: `heightIn(min = …)` throughout, `@Preview(fontScale = 2f)` (C1.3, C1.14, C2.2, C3.3) |
| Ask-question letter keys not scoped | Fixed: options-only scope, field keeps its keys (C4.7, C4.10 item 8); C3.8 has no sheet-wide shortcuts |
| `run.interrupted` unreachable for `send_input` turns | Fixed: derived locally (§0.2, C1.7, C1.15 item 14) |
| Resume payload drops contributions, wrong checkpoint, no timeout | Fixed: one shared `NewSessionSpec`, 120 s (C1.7) |
| Skills "served by the daemon" | Fixed in part: the daemon does not expand them. Reviewed: kept "send as typed" when the `skills` tool is available, because that is what the desktop sidecar and the CLI do (`expandSkillCommands = !isSkillsToolAvailable`); client-side expansion only when the tool is off (C5.6, C5.7 step 4; OQ 4 closed) |
| `run.started` for queued sends | Fixed: ignored for phase and correlation (§0.2) |
| `pending_prompts_unavailable` misread | Fixed: no queue for the connection, direct sends (C2.6) |
| OQ 8 answerable from source | Closed (§10 item 8, C1.6) |
| No no-logging rule | Added §0.8 |
| ModelChip provider hidden on wide screens; icon-only while busy | Fixed (C1.4) |
| Approve/Reject order, rejection reason across documents | Listed as translations (C3.2); DS §9 sentence flagged (§9 item 5) |
| Same-day time shortening | Fixed: always date + time (C3.6) |
| Slash list order and separator | Fixed: build order, conditional separator (C5.6, C5.8) |
| First-run Send interception, enabled ModelChip, Send/Stop placement | Listed as deliberate translations (C1.2) |
| `/team` with Teams tool disabled | Added `team_disabled` (C5.5, C5.7) |
| Image size strings | Desktop strings verbatim with encoded-size check (C1.9, C1.10) |
| `DecorationBox`, dp line heights | Fixed: `decorator` + `TextFieldLineLimits` (C1.3) |
| Kernel-down field disabled | Fixed: `readOnly` (C1.6) |
| Cold-start deep link | Fixed: `onCreate` + `onNewIntent`, extras cleared (§0.6) |
| Stacked rounded surfaces | Fixed: one `ComposerSurface` with docked children (C1.3, §0.4, C5.8) |
| Stop in `inverseSurface` | Fixed: `error`/`onError` (C1.3) |
| Queue edit `ImeAction.Done` | Fixed: `ImeAction.Default`, Save button (C2.7) |
| Inline image error not announced | Fixed: polite live region (C1.3, C1.14) |
| Two initial focus targets in C3 | Fixed: title only (C3.8, C3.10; C4.4 matches) |
| `*/*` mixed with other MIME types | Fixed: `*/*` only (C1.9) |
| Landscape IME leaves no transcript | Fixed: on a height-compact window with the IME open, 2-line cap, settings chips and queue strip hidden; all return when the IME closes (C1.4 step 6, C1.15 item 17, docs/07 D18) |

