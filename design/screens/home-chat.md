# Screen spec: Home and chat thread

Status: design-phase contract, 2026-10-03; revised the same day after a three-reviewer pass
(every finding verified against the sources; see §10 "Review notes"). Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`).
Builds on `design/design-system.md` (cited as **DS §n**) and never redefines what it
defines. Kernel facts come from `docs/02-kernel-contract.md` (**KC**),
`docs/03-feature-matrix.md` (**FM**) and the recorded traffic in
`docs/08-hub-fixtures.md` (**FX**). Where FX corrects FM, FX wins and the row says so.

Screens in this group:

| # | Screen | Route / host |
| --- | --- | --- |
| A | Home / new thread (welcome state) | start destination `Chat(sessionId = null)`, selection `null` (§2.9) |
| B | Chat thread transcript | the same `Chat()` entry with a selected top-level session, or a pushed `Chat(sessionId = <child>)` for subagent sessions (§2.9) |
| C | Streaming indicator and scroll-to-latest | inside B |
| D | Image lightbox / carousel | in-window overlay above B's `Scaffold` content (and above A's composer attachments), §5 |
| E | Conversation header (+ Session details sheet, rename, delete confirm) | `TopAppBar` of B |

Routes are type-safe Navigation Compose routes:
`@Serializable data class Chat(val sessionId: String? = null)` (Navigation 2.8+ encodes the
nullable argument as a query parameter, `chat?sessionId=…`). The DS §5.2 spelling
`chat/{sessionId?}` is not a valid path pattern (path arguments cannot be optional); see
§10 Q14.

Owned elsewhere and only *referenced* here (each has its own spec in `design/screens/`):
the composer and its chips, usage ring, prompt-queue strip (composer spec); tool approval
sheet and banner; ask-question sheet; workspace picker sheet; kernel setup and connect-a-model
first-run screens; Kernel status sheet; Changes screen; checkpoint dialogs; agents roster
sheet; sessions drawer / rail / list pane; notifications. This spec defines the exact
hand-off (event, state flag, navigation call) to each of them.

---

## 0. Sources

### 0.1 Desktop files read (paths under `~/workspace/cline-termux-v3-phone`)

| File | What was copied |
| --- | --- |
| `apps/examples/desktop-app/webview/app/page.tsx` (lines 2480–2760) | Boot gate copy; how `WelcomeScreen`, `AgentHeader`, `ChatMessages`, `WelcomeSetupNotice` and the persistent composer are composed; header delete dialog |
| `webview/components/views/chat/welcome-chat.tsx` | Welcome composition order: hero → workspace controls → notice → composer → footer; sr-only heading "What would you like to build?"; "Sign in failed: …" |
| `webview/components/views/chat/welcome-setup-notice.tsx` | Notice copy, actions, `<output>` (status) semantics |
| `webview/components/views/chat/welcome-workspace-controls.tsx` | Workspace chip label rules ("Chat" for the chat workspace, basename otherwise), branch picker copy and behaviour, "Just chat", "Open folder..." |
| `webview/components/views/chat/environment-selector.tsx` (skimmed) | Local/cloud/SSH selector: omitted (FM "none") |
| `webview/components/views/chat/chat-messages.tsx` | Render pipeline, indicator rule (`isAwaitingFirstOutput`), indicator offset rule, "Thinking...", "Switching session...", "Loading session..." skeleton, transport lines, error banner rule, auto-scroll on send, action enable rules, copy-failed toast, fork/edit/restore error strings, checkpoint dialog copy |
| `webview/components/views/chat/messages/message-bubble.tsx` | User/assistant/error bubble anatomy, action sets and visibility, timestamps, credential action, `followsWorkingRows` |
| `webview/components/views/chat/messages/group-messages.ts` | `groupChatMessages`, `collapseCompletedWork`, `isSystemSteeringMessage`, run-count map, thought duration (ported 1:1) |
| `webview/components/views/chat/messages/tool-message-block.tsx` | Tool row: label parts, submit_and_exit milestone labels, command output source, proceed-while-running, diff hunks, output images/media, error text. Its auto-open rule is not ported: every row starts folded (A23) |
| `webview/components/views/chat/messages/tool-summaries.ts`, `tool-icons.ts` | `buildToolPresentation`, `extractRunCommandOutput`, `TOOL_NAME_ICONS` / `TOOL_KIND_ICONS` |
| `webview/components/views/chat/messages/reasoning-block.tsx`, `work-block.tsx` | Thinking row and work-summary row wiring |
| `webview/components/views/chat/messages/image-carousel.tsx`, `image-lightbox.tsx` | Image alt / aria strings, counter "1 / N", lightbox dialog label |
| `webview/components/views/chat/message-content.ts`, `webview/lib/run-error.ts`, `webview/hooks/chat-session/helpers.ts` | `formatRunError`, credential hint and action resolution |
| `webview/lib/command-output.ts` | 48 000-char live output cap and its marker |
| `webview/lib/session-status.ts`, `webview/lib/session-agents.ts`, `webview/lib/session-diff.ts` | Header status tone, `buildSessionAgentActivity`, `describeAgentActivity`, `agentEntryState`, header diff summary |
| `webview/components/agent-header.tsx` | Header anatomy, title fallback "New Session", inline title editor (commit on Enter/blur, Esc cancels), diff chip, agents chip, subagent back badge, "Session actions" menu |
| `webview/components/agent-sidebar.tsx` (overview + delete dialog) | Session overview rows (Schedule, Run, Workspace, Branch, Provider, Model, Tokens, Cost, Source), "Delete session?" dialog |
| `webview/hooks/use-session-history.ts` | `formatTokenCount`, `formatCostUsd` |
| `webview/components/ui/markdown.tsx` | Link-safety dialog ("Open external link?"), blocked external image copy |
| `sdk/packages/ui/components/agent-conversation-layout.tsx` | Persistent composer across welcome ⇄ conversation; shared content width |
| `sdk/packages/ui/components/agent-chat/index.tsx` | Stick-to-bottom (24 px) and scroll-button (120 px) logic, "Scroll to latest message", `formatThoughtLabel`, `formatWorkDuration`, `formatWorkActivityLabel`, ToolActivity trigger rules (spinner replaces icon) |
| `sdk/packages/ui/components/agent-chat/tool-summary/index.ts`, `team.ts` | `normalizeToolName`, `classifyTool`, fallback labels, aggregate labels, capText "[output truncated]" |
| `sdk/packages/ui/components/agent-welcome-hero.tsx` + `.css` (`agent-hero-heading.tsx` is exported but no longer mounted) | Bot illustration opacities, grid |
| `sdk/packages/ui/components/agent-image-lightbox.tsx`, `agent-command-output.tsx`, `generated-media.tsx`, `session-status.tsx` | Lightbox close labels, command output anatomy, media fallbacks and limits |
| `sdk/packages/shared/src/hub.ts`, `llms/messages.ts`, `llms/media.ts`, `agents/types.ts`, `prompt/format.ts`, `storage/chat-workspace-paths.ts` | Event names, content blocks, `GeneratedMedia`, notice types/reasons, `formatDisplayUserInput`, chat workspace path |
| `sdk/packages/core/src/hub/server/handlers/session-event-projector.ts`, `session-handlers.ts`, `hub-server-transport.ts` | Which events carry what; `session.update` semantics; `run.failed` payload shapes (`:409-425`, `:456-468`); `run.interrupted {runId, error, reason:"hub_restart"}` is published **only** by `HubRunQueue.recoverOnStartup()` for `run.enqueue` runs (`hub-server-transport.ts:683-695`) |
| `sdk/packages/core/src/hub/server/handlers/run-handlers.ts`, `run-queue-handlers.ts` | `session.send_input` and `run.start` share `handleSessionInput` (`hub-server-transport.ts:860-862`), an RPC turn that is never written to the run queue; the catch-path `run.failed {reason:"error", error}` (`:280-290`) |
| `webview/hooks/use-chat-session.ts` (`:900-930`), `sdk/packages/shared/src/provider-auth.ts` | Error rows always carry `meta.providerId` + `providerAuth` captured at turn start; `ProviderAuthInfo {providerId, capabilities?, localCli?}` |
| `apps/cli/src/runtime/interactive/fork/metadata.ts`, `title.ts` | Fork metadata and the `deriveForkSessionTitle` fallback chain (`:55-66`, max 120 chars) |
| `apps/cli/src/commands/history-command.ts` | `cline history delete --session-id <id>`, `cline history export <sessionId> -o <path>` |

The `packages/ui/src` path in the task brief does not exist; the shared UI package is
`sdk/packages/ui` (DS preamble says the same).

### 0.2 Voice-app code reused (copy with `// Adapted from cline-termux-voice (same owner): <path>`)

Paths under `~/workspace/cline-termux-voice/android/app/src/main/kotlin/dev/cline/voice/`.

| Voice file | Used for |
| --- | --- |
| `ui/markdown/Markdown.kt`, `MarkdownModel.kt` | Assistant and reasoning bodies (`MarkdownText`), code-block copy button, path soft-wrapping |
| `ui/chat/ChatRows.kt` (`UserAligned`, `UserLine`, `rememberCopier`, `ErrorCard`) | User bubble width rule, copy helper (API < 33 snackbar), error surface |
| `ui/ChatScreen.kt` (`bottomSafe`, `Transcript`, `BackToBottom`, `GROWTH_SCROLL_SIGN`, `WORK_PIN_MS`) | `reverseLayout` transcript, follow-the-bottom `SideEffect`, header-holding scroll when a row expands, jump-then-glide scroll-to-latest |
| `ui/chat/WorkRows.kt` (`Unfold`, `WorkHeader`) | Disclosure body measurement and resize reporting |
| `ui/Navigation.kt` (`Motion`) | Shared-axis and fade-through (DS §6) |
| `ui/theme/*`, `ui/Components.kt` | Theme and `PageScaffold` (DS §0) |

Voice copy (Chinese UI strings) is **not** reused; all copy below is the desktop's.

---

## 1. Shared state model (both A and B)

### 1.1 Where state lives

- `SessionStore` (application-scoped singleton, injected): one `SessionState` per
  `sessionId`, fed by `HubClient.events` filtered by `envelope.sessionId`, deduplicated by
  `sequence` (FX: every session event arrives twice when subscribed globally and per
  session). It survives configuration changes, pane changes and the Home → session hand-off.
- **Subscriptions and cursors (one rule for the whole app).** The single global
  `stream.subscribe {clientId, sinceSequence: <persisted global cursor>}` (KC step 6) owns
  the only persisted cursor; every session event also arrives on it (FX), so it alone is
  authoritative for catch-up after a reconnect, a Hub restart or app death (§7 "App killed
  during a turn"). Per-session `stream.subscribe {clientId, sessionId}` frames are sent
  **without** `sinceSequence` and only to force the Hub to re-issue pending approvals and
  questions for the open session (KC); their duplicates are dropped by the `sequence`
  dedupe. This refines KC step 6, which also persists per-session cursors; this group
  never reads or writes them.
- `ChatViewModel` (one per `Chat` back-stack entry) holds only UI-local state (draft is in
  the composer VM, expansion overrides, lightbox target, dialogs) and exposes
  `uiState: StateFlow<ChatUiState>` combining `SessionStore`, `KernelCoordinator.state` and
  `HubClient.state`.
- The **selected session** of the start entry lives in `SavedStateHandle["sessionId"]`
  (initialised from the route argument, so a deep link `Chat(sessionId = id)` works). When
  Home creates a session, or the user opens a top-level session from the drawer, list
  pane, search or a notification, the VM **writes the id into `SavedStateHandle`**; it does
  not push a back-stack entry. The same composable keeps its composer, IME and focus
  (desktop `AgentConversationLayout` keeps the composer mounted for the same reason).
  Per-session UI state (scroll position, expansion overrides) is kept by a
  `SaveableStateHolder` keyed by `sessionId ?: "home"`, which is what Navigation's
  `saveState`/`restoreState` would otherwise give. Back from a selected top-level session
  returns to Home (§2.9); subagent sessions are real pushed entries.

### 1.2 `ChatUiState`

```kotlin
@Immutable data class ChatUiState(
    val sessionId: String?,                    // null = Home (screen A)
    val welcome: Boolean,                      // sessionId == null && no optimistic turn
    val kernel: KernelLine,                    // §1.6
    val transport: TransportLine?,             // §3.9, null when connected
    val home: HomeState?,                      // non-null only when welcome
    val header: HeaderState?,                  // non-null only when !welcome (screen E)
    val items: List<ChatRenderItem>,           // oldest first; §1.5
    val streamingMessageId: String?,           // the assistant message receiving deltas
    val indicator: Indicator?,                 // screen C
    val errorBanner: ErrorBanner?,             // §3.10
    val switching: Switching,                  // None | Loading | Switching (§3.5, §3.11)
    val status: ChatStatus,                    // §1.4
    val liveness: Liveness,                    // Live | Persisted | Missing
    val versioningPending: Boolean,            // fork in flight (disables fork/edit/restore)
    val pendingApprovals: Int,                 // count only; sheet owned by the approval spec
    val pendingQuestions: Int,                 // count only; sheet owned by the ask-question spec
    val usage: UsageTotals?,                   // §3.8
)
```

### 1.3 `ChatMessage` (Kotlin port of desktop `lib/chat-schema.ts`)

```kotlin
enum class Role { User, Assistant, Tool, System, Status, Error }
@Immutable data class ChatMessage(
    val id: String,                // stable: see 1.3.1
    val sessionId: String,
    val role: Role,
    val content: String,           // user/assistant text, tool payload JSON {toolName,input,result?,isError?}, error detail
    val reasoning: String? = null,
    val reasoningRedacted: Boolean = false,
    val images: List<ChatImage> = emptyList(),   // {id, mediaType png|jpeg|gif|webp, data base64}
    val media: List<GeneratedMedia> = emptyList(),
    val createdAt: Long,           // epoch ms
    val renderKey: String = id,    // LazyColumn key + saveable-state key; inherited across reconciliation (§1.3.3)
    val meta: Meta = Meta(),
)
@Immutable data class Meta(
    val toolName: String? = null, val toolCallId: String? = null,
    val toolOutput: String? = null, val toolOutputTruncated: Boolean = false,
    val toolDetachable: Boolean = false, val hookEventName: String? = null,
    val reason: String? = null,             // "credentials" | "interrupted" | notice reason
    val noticeType: String? = null,         // recovery | stop | status
    val providerId: String? = null,         // set on EVERY Error row: the turn's provider (TurnRecord, §1.3.4)
    val providerAuth: ProviderAuthInfo? = null, // §1.3.5; null = auth facts unknown
    val requestId: String? = null,          // the turn this row belongs to (live rows only)
    val userRunSpan: Int? = null, val runCount: Int? = null,
    val checkpoint: CheckpointRef? = null,  // {ref, createdAt, runCount}; never set until the app registers checkpoint.create (FX)
    val thoughtEndedAt: Long? = null,       // live reasoning.finished time
)
// Port of sdk/packages/shared/src/provider-auth.ts
@Immutable data class ProviderAuthInfo(val providerId: String,
    val capabilities: List<String> = emptyList(), val localCli: LocalCli? = null)
@Immutable data class LocalCli(val command: String, val docsUrl: String? = null)

// run.failed payloads differ by publisher (session-event-projector.ts:409-425 and :456-468,
// run-handlers.ts:280-290): `error` is a plain string, never an object, and may be absent.
// The run.start success path (run-handlers.ts:303-316) adds `result` (AgentResult).
@Serializable data class RunFailedPayload(val reason: String, val error: String? = null,
    val text: String? = null, val result: JsonObject? = null, val snapshot: JsonObject? = null)
// detail = error ?: text ?: ""   (empty → "The run failed before a response was produced.")
```

Fixture-backed unit tests decode all four `run.failed` shapes above (add a synthetic
frame for each to the FX `02`/`03` test set) and assert none throws.

#### 1.3.1 Event reducer (live), one row per Hub event

All events are `HubEventEnvelope {event, sessionId, sequence, payload}`. Ids are derived so
that a replay after reconnect (FX: `sinceSequence` exclusive, replays survive restarts)
produces the same ids and the reducer is idempotent. Every live row the reducer creates
carries `meta.requestId` = the session's open `TurnRecord.requestId` (§1.3.4), which is what
reconciliation (§1.3.3) uses to drop a finished turn's live rows.

| Event (KC/FM/FX) | Reducer effect |
| --- | --- |
| `run.started {requestId, clientId}` | `status = Running`; open the session's `TurnRecord` (§1.3.4) for `requestId`; if the turn's optimistic user message (id `local:<requestId>`) exists, keep it; else (turn started elsewhere, e.g. the TUI) do nothing (its prompt arrives via the reconciliation at the terminal event, §1.3.3) |
| `iteration.started {iteration}` | remember `iteration` for ids |
| `assistant.delta {text}` | append to the open assistant message `a:<requestId>:<iteration>:<n>`; create it on the first delta (`createdAt` = event receive time); `streamingMessageId` = its id. Coalesce: apply at most every 50 ms (DS §6 "Streaming text") |
| `assistant.finished {text}` | replace the open message's `content` with `text`; close it (`n++`); `streamingMessageId = null` |
| `reasoning.delta {text, redacted}` | append to `reasoning` of the open assistant message, or create a reasoning-only assistant message `r:<requestId>:<iteration>:<n>` if none is open; `redacted:true` sets `reasoningRedacted` |
| `reasoning.finished {reasoning}` | set full `reasoning`, `thoughtEndedAt = now` |
| `assistant.media {media}` | append `GeneratedMedia` to the open (or last) assistant message of this iteration |
| `tool.started {toolCallId, toolName, input}` | **ignore when `toolCallId` is missing** (hook-derived duplicate, FM); else add `Tool` message id `t:<toolCallId>`, `content = {"toolName","input"}`, `meta.toolCallId/toolName`, `hookEventName = "tool_call_start"` |
| `tool.updated {toolCallId, update{stream, chunk, executionId, detachable, commandIndex, query, truncated?, droppedChars?}}` | `meta.toolOutput = appendCappedCommandOutput(meta.toolOutput, chunkWithHeader)` where `chunkWithHeader` prefixes `"\n$ <command>\n"` when `commandIndex > 0` changes, `<command>` being entry `commandIndex` of the row's `tool.started` input (trial row 18; the update's `query` is the kernel's echo, capped at 200 characters with a "... [command truncated: …]" note, and stands in only when the row has no input, the note replaced by "…"); `meta.toolDetachable = update.detachable`; first update has empty chunk (FX) |
| `tool.finished {toolCallId, output, error?}` | set `result = output`, `isError = error != null \|\| output.error != null` in `content`; clear `hookEventName` |
| `session.notice {message, noticeType, reason?}` | `reason == "api_error"` → `Error` message `x:<sequence>` (detail = `message`; `meta.providerId`/`meta.providerAuth` from the session's open `TurnRecord`, else from the session's current provider via §1.3.5; `meta.reason = "credentials"` when the detail is empty or `isCredentialFailure(message)`, desktop `looksCredentialRelated`); otherwise `Status` message `n:<sequence>` with `noticeType`, `reason` |
| `usage.updated {delta, totals, usage, aggregateUsage}` | `usage = aggregateUsage ?: usage` (FX: these are the session totals) |
| `approval.requested` / `approval.resolved` | `pendingApprovals` count by distinct `approvalId` (re-issued on every subscribe, KC); details owned by the approval spec |
| `capability.requested {capabilityName:"tool_executor.askQuestion"}` / `capability.resolved` | `pendingQuestions` count by `requestId` |
| `session.updated {session?, snapshot?}` | header status and title from `session.status` / `snapshot.status`, `session.metadata.title` (FX: always a pair; take the later) |
| `run.completed {reason, result?, snapshot?}` | Terminal events carry no `requestId`; they close the session's open `TurnRecord` (§1.3.4). `reason == "completed"` → `status = Completed`… then `Idle` when `session.updated` reports `idle`. `reason == "disposed"` (Hub shutdown, FX) while a turn is open → the turn is **interrupted** (§1.3.4). Any other reason is not success (FX): `status = Idle`, no work folding of a trailing run without an answer (the collapse rule already requires one). Then reconcile (§1.3.3) |
| `run.failed` (`RunFailedPayload`, §1.3) | `Error` message `e:<requestId of the open turn>` (or `e:<sequence>` when no turn is open), `content` = detail = `error ?: text ?: ""` (rendered through `formatRunError`, §3.4.7), `meta.providerId`/`meta.providerAuth` from the `TurnRecord` (§1.3.5), `meta.reason = "credentials"` when the detail is empty or `isCredentialFailure(detail)`; `status = Failed`; then reconcile (§1.3.3) |
| `run.aborted {reason}` | `status = Cancelled`; rows of the trailing run stay unfolded (desktop); close the turn; reconcile (§1.3.3) |
| `run.interrupted {runId, error, reason:"hub_restart"}` | Published **only** for runs admitted through `run.enqueue` (`HubRunQueue.recoverOnStartup`), never for the app's own `session.send_input` turns. Handled for completeness (TUI-queued or scheduled runs): `Error` message `i:<runId>` with `meta.reason = "interrupted"`, detail `error`; `status = Idle`. The app's own turns are detected by §1.3.4 |
| `team.progress` | agents tally (screen E §6.6) |
| `agent.done` | ignored for state (earliest "turn over" hint only, FX) |

`approval.requested` while the turn waits produces `snapshot.status == "pending"` (FX); it
maps to ChatStatus `Running` for the transcript, and to the "Approval required" visual for
the dot (DS §1.6).

Events whose payload carries `agent.kind` `subagent` or `teammate` are **not** rendered in
the parent transcript; they only feed the agents tally (FM "Agents roster": `assistant.delta`
has no agent field, follow the CLI's `use-agent-events.ts`). See open question Q6.

#### 1.3.2 History mapping (`session.messages {sessionId}` → `{messages[]}`)

FX shape: `{id, role:"user"|"assistant", content: Block[], ts, modelInfo?, metrics?}`.

| Block | Becomes |
| --- | --- |
| user `text` | `User` message, `content` = raw text; display text = `formatDisplayUserInput(raw)` (strips `<user_input mode="…">` and `<mode_notice>`, renders `/team …`, `/<slash> …`; port of `shared/src/prompt/format.ts:179`) |
| user `image {data, mediaType}` | `images` of that user message |
| user `tool_result {tool_use_id, content, is_error}` | merged into the `Tool` message `t:<tool_use_id>` as `result` / `isError`; `content` may be a JSON string (FX) |
| assistant `text` | `Assistant` message `h:<msgId>:<blockIndex>` |
| assistant `thinking {thinking}` | reasoning of the next assistant text message (or a reasoning-only message) |
| assistant `redacted_thinking` | `reasoningRedacted = true` |
| assistant `tool_use {id, name, input}` | `Tool` message `t:<id>`, `hookEventName = "history_tool_use"` until its result is merged |
| `media {media}` | `media` of the assistant message |
| `file` | not rendered (paths attached by the daemon) |
| any message with `metadata.displayRole` `error` / `system` / `status` | an `Error` / `System` / `Status` row with the text blocks (core `resolveMessageDisplayRole`); an error row takes `modelInfo.provider` as `meta.providerId` and the credentials reason when the text looks credential-related, and reconciliation hands the live `e:` row's key to it (§1.3.3) |

`createdAt` = message `ts`; history thought duration = desktop rule
`getThoughtDurationMilliseconds(previous message ts, reasoning message ts)`; live duration =
`thoughtEndedAt − first reasoning.delta time` (DS §4.1 #3).

**System steering messages** (desktop `isSystemSteeringMessage`) are hidden: a `User`
message whose raw text starts with `[SYSTEM]` and is **not** wrapped in `<user_input>`
(genuine prompts are always wrapped, FX). See Q5.

#### 1.3.3 Reconciliation after a turn (live rows → canonical rows)

Live ids (`local:<requestId>`, `a:<requestId>:<iteration>:<n>`, `r:…`, `t:<toolCallId>`) and
history ids (`h:<msgId>:<blockIndex>`, the daemon's user-message `id`, `t:<tool_use_id>`)
share only `t:`. So "replace rows whose ids match" is **not** the rule. The rule:

1. `SessionState` holds `canonical: List<ChatMessage>` (the last `session.messages` mapping,
   §1.3.2), `canonicalCount` (its raw message count), and `live: List<ChatMessage>` (rows
   built from events since). Rendered messages = `canonical + live`, with live-only rows
   (`Error`, `Status`) placed by `createdAt` (same device clock as the daemon's `ts`; ties
   go after).
2. On every terminal event (`run.completed`, `run.failed`, `run.aborted`, interruption per
   §1.3.4) for **any** turn (ours or started elsewhere), request `session.messages`. When
   it answers, the **turn slice** is the raw history messages at index `≥ canonicalCount`
   (everything the daemon appended since the last reconciliation, including turns started
   from the TUI).
3. Replace, in one state update: `canonical = mapping of the whole reply`, and drop from
   `live` every `User`/`Assistant`/`Tool` row whose `meta.requestId` belongs to a closed
   turn. `Error` and `Status` rows stay (they are not in history). Rows of a turn that is
   still open (a queued follow-up already running) stay live.
4. **Key inheritance.** Before dropping, build `alias: liveId → canonicalId` over the turn
   slice: tool rows map by `t:` id (identical); the turn's `local:<requestId>` user row maps
   to the slice's first `user` text message; assistant and reasoning rows map in order (the
   k-th live `a:`/`r:` row of the turn ↔ the k-th assistant text / reasoning-only row of
   the slice). Each canonical row that has an alias takes `renderKey = <live renderKey>`.
   Unmatched canonical rows keep `renderKey = id`; unmatched live rows simply disappear,
   except in a turn that was stopped (`run.aborted`) or interrupted (§1.3.4): the daemon does
   not persist what was streaming when the run was cancelled, so those rows move into
   `canonical` after the turn's last history row and keep their place in later
   reconciliations (§3.16 "the partial rows stay").
   Because `LazyColumn` keys, `rememberSaveable` expansion state, the VM's expansion
   overrides and the work-row id (§3.4.5 "first collapsed message's `renderKey`") all use
   `renderKey`, nothing flickers, collapses or jumps when canonical rows replace live ones.
5. A reply that fails leaves `live` untouched (no data loss) and retries once on the next
   terminal event or on reconnect.
6. Opening a session (§3.6) is the same operation with an empty `live`.

Unit tests: replay FX `02`, `03` and `05` through the reducer, reconcile, and assert (a) no
duplicate user/assistant/tool rows, (b) every `renderKey` that existed before reconciliation
still exists after it (for matched rows), (c) the error row of a failed turn survives.

#### 1.3.4 Turn records and interruption detection

`TurnRecord {requestId, sessionId, startedAtSequence, prompt, images, mode, providerId,
providerAuth, open: Boolean}`, one per `run.started` (ours: filled from `send()`; started
elsewhere: prompt unknown until reconciliation). At most one record per session is open
(a busy send is a queued delivery, composer spec). Records live in `SessionStore`; the
open ones (without image data) are also written to DataStore so a cold start can still
detect an interruption.

The Hub publishes `run.interrupted` only for `run.enqueue` runs, so for the app's
`session.send_input` (= `run.start`) turns an interruption is **derived**. A turn is
interrupted when its record is open (a `run.started` and no terminal `run.*` since) and
either:

- **(a) Hub restarted under it.** The socket dropped and the reconnect's `GET /version`
  reports a different `pid` or `startedAt` (the same check that rotates the token, KC
  step 3). Evaluate only after the replay is drained: after re-subscribing, read
  `hub.status` → `eventLog.lastSequence` and wait until the applied global cursor reaches
  it (the replay may still deliver the turn's real terminal event, which wins).
- **(b) The replay or live stream carries the shutdown projection for that session** (FX
  "Shutdown projections"): `run.completed {reason:"disposed"}`, or a `session.updated`
  whose `snapshot.status` is `cancelled` or `completed` with `snapshot.endedAt` set while
  the record is open.

Then: close the record; append `Error` message `i:<requestId>` (`meta.reason =
"interrupted"`, `meta.requestId`, `meta.providerId`/`providerAuth` from the record);
`status = Idle`; `liveness = Persisted` (the restarted Hub does not have the session live,
FX `05`); reconcile (§1.3.3). The genuine `run.interrupted` event (§1.3.1) produces the
same row keyed `i:<runId>`; when both fire for one turn the reducer keeps the first.
"Resend" (§3.4.7) re-sends `record.prompt` (+ images if still in memory), or, when the
record came from DataStore or the turn started elsewhere, the text of the last `User`
row at or after the turn's slice; it is hidden when neither exists.

The mid-run restart is not recorded in FX (`05` restarted with idle sessions only): Q12.

#### 1.3.5 Provider auth facts for error rows

The desktop stamps every error row with the turn's provider and its auth facts and only
then offers an action (`use-chat-session.ts:915-925`, `helpers.ts:269-318`). The app does
the same. At `send()` time, `ProviderAuthResolver.resolve(providerId)` fills the
`TurnRecord`:

1. `providerId` normalises to `cline` → `ProviderAuthInfo("cline")` (the hint and action
   for `cline` do not depend on the rest).
2. Settings-helper catalog in memory (settings-providers spec §0.7): `ProviderAuthInfo(id,
   capabilities, localCli = metadata.localCliCommand?.let { LocalCli(it, docsUrl) })`.
3. Else the persisted non-secret catalog projection (`filesDir/catalog-cache.json`, same
   spec §0.7), else the bundled `res/raw/fallback_providers.json` (§0.4): by `authKind` —
   `"local"` → **`null`** (the CLI command name is not known offline, so the desktop's
   unknown-auth path applies: generic hint, no button); `"oauth"` / `"api-key"` →
   `ProviderAuthInfo(id, capabilities = [authKind])`.
4. Else, when `ProviderPresence.configuredIds` (§2.6) contains the id (a custom
   OpenAI-compatible provider) → `ProviderAuthInfo(id)` (**Android rule**: a provider the
   user configured is "known", so "Open API providers" is offered).
5. Else `null`.

Resolution of the hint and action is then the 1:1 port of `resolveCredentialFailureHint`
and `resolveCredentialFailureAction` (§3.4.7). Rows created without a turn record
(history load, cold start) resolve with the session's `metadata.provider`.

#### 1.3.6 `buildSessionCreate` (one helper for every `session.create`)

Every `session.create` the app sends — first send (§2.7), resume (§3.6), fork (§3.4.2),
Resend after an interruption (§3.4.7), auto-resume on `session_not_found` (§3.12) — goes
through one function, so no path loses a client contribution:

```kotlin
fun buildSessionCreate(
    config: SessionConfigInput,          // workspaceRoot, cwd, providerId, modelId, mode, flags, thinking, reasoningEffort, resumeSessionId?
    metadata: JsonObject,                // see the per-caller table below
    toolPolicies: JsonObject,
    initialMessages: JsonArray? = null,
): JsonObject = buildJsonObject {
    put("workspaceRoot", config.workspaceRoot); put("cwd", config.cwd)
    put("sessionConfig", config.toJson())                     // never apiKey, never configExtensions (FM)
    put("metadata", metadata); put("toolPolicies", toolPolicies)
    initialMessages?.let { put("initialMessages", it) }
    put("runtimeOptions", buildJsonObject { put("clientContributions", buildJsonArray {
        add(contribution(kind = "toolExecutor", executor = "askQuestion",
                         capabilityName = "tool_executor.askQuestion"))
        // + checkpoint.create once the checkpoint spec registers it (§3.13)
    }) })
}
```

| Caller | `sessionConfig.sessionId` | `initialMessages` | `metadata` |
| --- | --- | --- | --- |
| First send (§2.7) | — | — | `{source:"android", interactive:true, provider, model}` |
| Resume / auto-resume / Resend (§3.6, §3.12, §3.4.7) | old id | `session.messages` result | `{source: <persisted metadata.source ?: "android">, interactive:true, provider, model}`; the Hub merges this with the persisted record (`title`, `pinned` kept, FX `05` step 5). **Keeping the persisted `source`** means a TUI session stays `cli`, so its tool set does not change mid-history (Q13) |
| Fork (§3.4.2) | — | `session.messages` result | `{...current metadata minus "fork", source:"android", interactive:true, title, fork:{…}}` |

Unit test: every caller's JSON contains `runtimeOptions.clientContributions[0].capabilityName
== "tool_executor.askQuestion"`.

### 1.4 `ChatStatus` (port of desktop `ChatSessionStatus`)

`Idle · Starting · Running · Stopping · Completed · Cancelled · Failed`.

| Source | ChatStatus |
| --- | --- |
| `session.create` in flight (new or resume), or `session.send_input` sent and `run.started` not yet received | Starting |
| `status` / `snapshot.status` `running` or `pending` | Running |
| `run.abort` sent, `run.aborted` not yet received | Stopping |
| `completed` / `run.completed{completed}` | Completed |
| `aborted` / `cancelled` / `run.aborted` | Cancelled |
| `failed` / `run.failed` | Failed |
| `idle` | Idle |

"Busy" = Starting, Running or Stopping (desktop `BUSY_STATUSES`). The visual mapping (dot,
icon, label, TalkBack) is **only** DS §1.6 `SessionVisual.of(...)`.

### 1.5 Render pipeline (ported 1:1, unit tests copied from the desktop `*.test.ts`)

```
items = collapseCompletedWork(
          groupChatMessages(messages),
          collapseTrailingRun = status in {Completed, Idle})
```

`ChatRenderItem` = `Message(agentRole, message, reasoningMessages)` | `Tools(messages)` |
`Run(id, items)` | `Work(id, items, durationMs?, toolCallCount)`, exactly as
`group-messages.ts`. Port `group-messages.test.ts` (744 lines) and
`tool-summaries.test.ts` as JUnit tests. `buildToolPresentation`, `buildToolSummary`,
`normalizeToolName`, `classifyTool`, `formatThoughtLabel`, `formatWorkActivityLabel`,
`formatWorkDuration`, `appendCappedCommandOutput`, `extractRunCommandOutput`,
`formatRunError`, `isCredentialFailure`, `resolveCredentialFailureAction`,
`formatTokenCount`, `formatCostUsd`, `buildSessionAgentActivity`, `describeAgentActivity`,
`agentEntryState` live in `dev.cline.termux.chat.model` as pure Kotlin.

### 1.6 Kernel line (from `KernelCoordinator.state` + `HubClient.state`)

Uses the DS §1.6 kernel table; this group adds where each state shows.

| `KernelState` | KernelLine | Home (A) | Thread (B) | Composer |
| --- | --- | --- | --- | --- |
| `Ready` + `Connected` | connected | nothing | nothing | enabled |
| `Unknown`, `Connecting`, `Starting(step)` | connecting | Kernel notice "Connecting..." (no action) | transport line "Connecting chat..." | send disabled (field editable) |
| `Degraded(Reconnecting)` | reconnecting | Kernel notice "Reconnecting..." | "Reconnecting chat..." | field editable; send queues locally until connected (§3.12) |
| `HubDown` | Termux asleep | Kernel notice "Termux asleep" / "Start kernel to continue." | "Chat backend unavailable" + line 2 | read-only; "Start kernel" replaces the settings row (DS §4.1 #6) |
| `Failed`, `Degraded(Unavailable)` | unavailable | Kernel notice "Cline Hub @unknown" / "Cline Hub is not connected." | "Chat backend unavailable" | read-only + "Start kernel" |
| `TermuxMissing`, `PermissionMissing`, `PortTooOld` | — | not shown: the app routes to the Kernel setup screen before Home (kernel-setup spec) | same | — |

---

## 2. Screen A: Home / new thread (welcome state)

### 2.1 Purpose

The empty "new session" surface: say what Cline can do, show where it will work (workspace,
branch), warn before the first send when no model is connected or the kernel is down, and
host the composer. The first send creates the session and turns this screen into screen B
without moving the composer.

### 2.2 Desktop equivalent

`WelcomeScreen` (`welcome-chat.tsx`) inside `AgentConversationLayout welcome=true`, with
`AgentWelcomeHero`, `WelcomeWorkspaceControls`, `WelcomeSetupNotice` and the welcome-variant
`ChatInputBar`. Copied: the order (hero → workspace/branch controls → setup notice →
composer), the sr-only heading text, the notice copy and both actions, the workspace chip
label rules, the branch picker, "Just chat". Translated: the desktop centres the column at
`clamp(8rem, 26vh, 17rem)` from the top with the composer in the flow; on Android the
composer is **docked to the bottom** on every window class (the IME pushes it, and the first
send does not move it, matching the desktop's "persistent composer" intent). Like the
desktop, Home renders "What would you like to build?" for screen readers only and shows just
the bot (`AgentHeroHeading` is no longer mounted since upstream 35583ee8b; DS §4.2
WelcomeHero). Omitted (FM
"none"): environment selector (local/cloud/SSH), cloud onboarding card, worktree toggle,
agenda quick actions (`AGENDA_UI_ENABLED = false` on desktop too).

### 2.3 Layout

**Compact (S25U portrait, ~412 dp)**

```
┌────────────────────────────────────────────┐  status bar (edge-to-edge, surface)
│ [≡]                                        │  TopAppBar 64dp, empty title (DS §5.3)
│                                            │
│                                            │  hero block, vertically centred in the
│            ╭──────────╮                    │  space above the notice/composer
│            │  (bot)   │  72dp, faint grid  │
│            ╰──────────╯                    │  (sr-only heading on the hero, no text)
│                                            │  24dp
│   (📁 cline-app ▾)  (⑂ main ▾)              │  AssistChips, 8dp gap, wrap to 2 lines
│                                            │
│ ┌────────────────────────────────────────┐ │  Kernel notice (only when not connected)
│ │ ● Termux asleep                        │ │
│ │   Start kernel to continue.            │ │
│ └────────────────────────────────────────┘ │  8dp
│ ┌────────────────────────────────────────┐ │  WelcomeSetupNotice (only when no provider)
│ │[⌁] Connect a model to start building   │ │
│ │    Sign in with Cline or add an API    │ │
│ │    key — it takes under a minute.      │ │
│ │              [Connect a model] Model…  │ │
│ └────────────────────────────────────────┘ │  8dp
│ ╭────────────────────────────────────────╮ │  Composer (composer spec), welcome
│ │ Ask to make changes, @mention files,   │ │  placeholder
│ │ reference #PRs, or run /commands.      │ │
│ │ [📎] [Plan|Act] [▣ model] [🧠 Low]  [↑]│ │
│ ╰────────────────────────────────────────╯ │
└────────────────────────────────────────────┘  ime ∪ bottomSafe (DS §5.6–5.7)
```

**Medium (Tab S7+ portrait ~778 dp)**: `NavigationRail` (80 dp) at start; the content
column is centred, max 840 dp, gutters 24 dp; bot 96 dp; chips on one
line. **Expanded (Tab S7+ landscape ~1244 dp)**: rail + list pane (360 dp) + this pane;
same column inside the detail pane.

```
Expanded (1244dp)
┌────┬───────────────────┬─────────────────────────────────────────────────┐
│rail│ list pane 360dp   │ [⇤]                                             │ TopAppBar: left_panel_close
│ ≡  │ Cline  ●Hub  🔍   │                                                 │
│ ✎  │ New session       │              ╭────────────╮                     │
│ 💬 │ Pinned  …         │              │   (bot)    │ 96dp                │
│ ⏱  │ …                 │                                                 │
│ 🧩 │                   │   (📁 cline-app ▾) (⑂ main ▾)                   │
│ ⚙  │                   │                                                 │
│    │                   │   ┌ setup / kernel notice ───────────────────┐  │
│ ●  │                   │   ╭ composer (max 840dp, centred) ──────────╮  │
│Hub │                   │   ╰──────────────────────────────────────────╯  │
└────┴───────────────────┴─────────────────────────────────────────────────┘
```

**Height-compact (phone landscape, height < 480 dp)**: hero hidden (DS §5.1); chips row
stays at the top of the pane; composer docked.

Spacing: hero block bottom → chips 24 dp; notices 8 dp apart and 8 dp above the composer;
gutters DS §3.3. The hero block is a `Column` inside a `verticalScroll` so it scrolls when
font scale 2.0 × size 20 leaves too little room (the composer never scrolls away).

### 2.4 Components

| Element | Spec |
| --- | --- |
| App bar | DS §5.3 Home: compact → `TopAppBar` with `navigationIcon` = `menu` "Open sessions"; expanded → `TopAppBar` with `left_panel_open`/`left_panel_close` ("Expand sidebar" / "Collapse sidebar"). Empty title, no actions. **Medium: no `TopAppBar` at all** (the rail holds `menu`, and an empty 64 dp bar would only push the hero down); the hero column takes `WindowInsets.statusBars` as top content padding instead |
| Hero | DS §4.2 WelcomeHero, no visible heading (desktop `welcome-chat.tsx`: sr-only `<h1>` + `AgentWelcomeHero`). TalkBack: a node with `heading()` and `contentDescription` "What would you like to build?" wraps the decorative illustration; on height-compact windows, where the hero is hidden, it stays as a 1 dp full-width node |
| Workspace chip | `AssistChip`, leading `folder` 18 dp, label = `"Home"` when no folder is chosen (an empty/blank root: "Just chat" or a fresh install), since the first send then starts in the Termux home (§2.7 step 3, *Android, decided A22*; a deliberate deviation from the desktop, whose `!workspaceRoot.trim() \|\| isChatWorkspacePath(workspaceRoot)` reads "Chat", `welcome-workspace-controls.tsx:1034-1035`) — `"~"` when the Termux home `/data/data/com.termux/files/home` is the chosen folder *(Android)*, otherwise the basename (the kernel's chat workspace, if ever chosen as a folder, reads as its basename); max label width 176 dp (desktop `max-w-44`), ellipsis; trailing `expand_more`. CD "Workspace: <full path or Home>". Tap → Workspace picker sheet (workspace-picker spec; its copy: "Search workspaces, or type a folder path", "Open folder...", "Just chat") |
| Branch chip | Shown only when a folder is chosen and is a git repository (desktop `!isChatWorkspace && currentBranch !== null && currentBranch !== "no-git"`, `welcome-workspace-controls.tsx:1167-1169`); with no folder chosen ("Home", A22) the git probe is not run at all, even when the Termux home is a git checkout (§2.6). `AssistChip`, leading `call_split`, label = branch name, max 176 dp, ellipsis, trailing `expand_more`. CD "Branch: <name>". Tap → Branch sheet (§2.5) |
| Kernel notice | `Surface(shape = large, color = surfaceContainerHigh, border = 1dp outlineVariant)`, 16 dp padding: 8 dp dot (DS §1.6 kernel table, pulsing for connecting/reconnecting) + line 1 `titleSmall` + line 2 `bodyMedium` `error` (only for unavailable / Termux asleep) + trailing `TextButton` "Kernel status" *(Android)* → Kernel status sheet. The "Start kernel" action is the composer's read-only row (DS §4.1 #6), so it is not duplicated here. Polite live region |
| WelcomeSetupNotice | DS §4.2. Buttons: `Button` "Connect a model" → Connect-a-model screen; `TextButton` "Model settings" → Settings > Providers. Hidden while the kernel notice shows "Termux asleep"/unavailable (one problem at a time; the kernel comes first) |
| Composer | composer spec, `variant = welcome` |

### 2.5 Branch sheet

`ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
contentWindowInsets = { WindowInsets.systemBars.union(WindowInsets.ime) })`, so the sheet
opens fully expanded and its content (search field and list) sits above the keyboard when
it rises (M3 1.4's default sheet insets cover the system bars only). Title "Branch"
*(Android pane title; desktop's popover has none)*, `OutlinedTextField` placeholder
"Search branches" (leading `search`), then a `LazyColumn` (`weight(1f)`) of 48 dp rows:
`call_split` 16 dp + name `labelLarge` + trailing `check` on the current one, current row
tinted `secondaryContainer`. The search field is **not** auto-focused on touch devices (the
keyboard would cover the list before the user asks to search); it is focused on open only
when a hardware keyboard is attached (`LocalConfiguration.current.keyboard !=
KEYBOARD_NOKEYS`). The list scrolls to the current branch on open (desktop
`scrollCurrentOptionIntoView`), before any focus change. States: loading → one row
"Loading..."; empty after filter → "No branches found"; switching → all rows disabled and
a 16 dp spinner on the tapped row; error → snackbar (below); 500-branch cap hit → a
`bodySmall` `onSurfaceVariant` footer row "Showing the 500 most recent branches"
*(Android)*. Tapping the current branch closes the sheet.

Bindings (FM "Git branch display, branch list/checkout", RUN_COMMAND via `TermuxRunner`,
`RUN_COMMAND_PATH = /data/data/com.termux/files/usr/bin/git`):

| Action | Invocation | Result |
| --- | --- | --- |
| Current branch | `git -C <ws> branch --show-current` | exit 0 + name → chip; exit 0 + empty → detached: label = `git -C <ws> rev-parse --short HEAD`; exit 128 → not a repo → chip hidden; `err == 150` (no git) → chip hidden |
| List | `git -C <ws> for-each-ref --count=500 --sort=-committerdate --format=%(refname:short) refs/heads` | one branch per line, most recently committed first; loaded each time the sheet opens (desktop). The cap keeps the result under the RUN_COMMAND 100 KB stdout+stderr limit (KC bridge; there is no shell to pipe through), so a huge repository is never truncated mid-line. Exactly 500 lines → show the cap footer |
| Switch | `git -C <ws> checkout <branch>` | exit 0 → chip updates, sheet closes; else snackbar "Couldn't switch to <branch>: <first stderr line>" *(Android)*, `SnackbarDuration.Long` |

While any session in this workspace is busy, the chip still opens the sheet (so the user
can see the branches and learn why), but every row is disabled and a `bodySmall`
`onSurfaceVariant` line under the title reads "Branch switching is unavailable while an
agent is running in this workspace." *(Android)*; the chip's CD gains the same sentence.
Reason: the agent's checkout would move under it *(Android rule; the desktop allows
switching only on the welcome screen, where no session is open)*.

### 2.6 States

| State | Trigger | What shows |
| --- | --- | --- |
| First run, kernel not set up | `TermuxMissing` / `PermissionMissing` / `PortTooOld` | Home is not reached; Kernel setup screen (kernel-setup spec) |
| First run, kernel ready, no provider | `ProviderPresence.hasConfiguredProvider == false` (see "Provider presence" below) | Hero, chips, WelcomeSetupNotice, composer enabled (desktop lets the user type; the notice is the warning) |
| Loading | app start, `KernelState.Unknown/Connecting/Starting` | Hero, chips (workspace from DataStore immediately; branch chip appears when git answers), Kernel notice "Connecting...", composer send disabled. **No full-screen spinner**: the desktop's boot gate ("Connecting...") is replaced by this inline state; the splash never waits for the kernel (DS §10.4) |
| Empty / ready | connected, provider configured | Hero, chips, composer |
| Kernel down / Termux asleep | `HubDown` | Kernel notice "Termux asleep" / "Start kernel to continue."; composer read-only with "Start kernel" |
| Unavailable | `Failed`, `Degraded(Unavailable)` | Kernel notice "Cline Hub @unknown" / "Cline Hub is not connected."; composer read-only with "Start kernel" |
| Reconnecting | `Degraded(Reconnecting)` | Kernel notice "Reconnecting..."; composer typing allowed; send waits (§3.12) |
| providers.json unreadable | RUN_COMMAND error or bad JSON → `ProviderPresence = null` | no setup notice (never nag on uncertainty); re-read after any auth action and on resume |
| Sending (first send) | `session.create` in flight | screen switches to B immediately (optimistic, §2.7) |
| Send failed before a session exists | `session.create` reply `ok:false` or timeout | stays in B with the optimistic user bubble and an error banner (§3.10) "Couldn't start the session: <message>" *(Android)* + "Retry" |
| Permission missing at runtime | a RUN_COMMAND result `err != -1` with `errmsg` mentioning permission / allow-external-apps (KC recovery matrix) | navigate to Kernel setup with the exact fix (kernel-setup spec); Home is restored on return |

**Provider presence (secrets rule).** `~/.cline/data/settings/providers.json` holds plaintext
provider API keys (FM "Provider catalog"; FM "Welcome setup notice": never persist the file
contents; DS §11). The bridge exposes only a typed call
`ClineCommands.readProviderPresence(): ProviderPresence?` with
`data class ProviderPresence(val hasConfiguredProvider: Boolean, val lastUsedProvider: String?, val configuredIds: Set<String>)`,
computed **inside the bridge** from `RUN_COMMAND cat /data/data/com.termux/files/home/.cline/data/settings/providers.json`
(`hasConfiguredProvider` = `lastUsedProvider` is present **and** at least one entry has a
non-blank `apiKey` or `auth.accessToken`, the settings-providers §0.4 "configured" rule;
`configuredIds` = the ids of those entries). The stdout
string is parsed and discarded inside that call; the result `Bundle` of this call is never
logged, persisted, attached to diagnostics or passed to the generic bridge logger (only its
`label` "cat providers.json" and exit code are). No other field of the file reaches
`HomeState`, `ChatUiState`, `SavedStateHandle` or any `Flow`.

### 2.7 First send (Home → thread hand-off)

1. Composer calls `ChatViewModel.send(prompt, images, mode, delivery = null)`.
2. VM sets `welcome = false`, appends optimistic `User` message `local:<uuid>` (images
   attached), `status = Starting`, indicator "Thinking...". Welcome ⇄ conversation swap
   animation (DS §6, 180 ms fade + 4 dp rise). The header (screen E) appears with title =
   first line of `formatDisplayUserInput(prompt)` and the Starting dot (pulsing).
3. With no folder chosen, `<ws>` below is the Termux home `/data/data/com.termux/files/home`
   *(Android, decided A22)*: every Termux has it, and nothing is created or written there.
   Before A22 the app sent the kernel's chat workspace path without creating it, so every
   `run_commands` failed with a misleading `ENOENT` (trial row 17); the desktop sends no
   folder and the kernel falls back to its own working directory. Checkpoints are
   unaffected: the kernel takes them only inside a git repository.
4. `session.create` (FM "New session"), `timeoutMs = 120000` (FX cold-start safety), built
   by `buildSessionCreate` (§1.3.6), which yields:
   ```json
   {"workspaceRoot": "<ws>", "cwd": "<ws>",
    "sessionConfig": {"providerId": "<p>", "modelId": "<m>", "mode": "act|plan",
      "enableTools": true, "enableSpawnAgent": true, "enableAgentTeams": true,
      "checkpoint": {"enabled": true}, "thinking": <bool?>, "reasoningEffort": "<low|medium|high|xhigh>?"},
    "metadata": {"source": "android", "interactive": true, "provider": "<p>", "model": "<m>"},
    "toolPolicies": {"*": {"autoApprove": <app default>}},
    "runtimeOptions": {"clientContributions": [{"kind": "toolExecutor", "executor": "askQuestion",
      "capabilityName": "tool_executor.askQuestion"}]}}
   ```
   Never send `sessionConfig.apiKey` (FM). Never send `configExtensions` (FM).
   `session.created` and two `session.updated` may arrive before the reply (FX); the
   SessionStore keys them by `sessionId` and attaches them when the reply lands.
5. On reply: write `sessionId` to `SavedStateHandle`; `HubClient.subscribe(sessionId)` with
   no cursor (approval re-issue only; the global subscription owns the cursor, §1.1); add the workspace to `home.recentWorkspaces` (DataStore, max 10, most recent
   first); re-key the optimistic message to `local:<requestId>` of the next step.
6. `session.send_input` (= `run.start`, FM "Send prompt") with `envelope.sessionId`,
   `timeoutMs = null`, payload `{prompt, mode, attachments: {userImages: [dataUrl…]}}`.
   The reply is only a correlation id; `run.started.requestId` matches it (FX). The
   `TurnRecord` (§1.3.4) is prepared with the prompt, images, mode and the provider auth
   facts (§1.3.5) and opened by `run.started`.
7. From here screen B rules apply.

### 2.8 Interactions and gestures

- Tap workspace chip → Workspace picker sheet; on selection the chip label updates, the
  branch chip re-probes, the recents list is not touched until a session is created there.
- Tap branch chip → Branch sheet.
- Tap "Connect a model" / "Model settings" / "Kernel status" as in §2.4.
- Edge-swipe from start (compact) opens the drawer (DS §5.2).
- Hardware keyboard: Ctrl+K / Ctrl+P → session search; Ctrl+N → stays (already new).

### 2.9 Navigation and back

Model: `Chat()` (Home) is the fixed **start destination** and is never popped. Top-level
sessions are a *selection inside that entry* (§1.1), not separate entries; subagent
sessions are pushed entries.

- Entered: app launch (start destination `Chat()`, selection `null`), drawer/rail "New
  session", header `add` "New session", Delete session (after
  success). Each of these sets the selection to `null`; if another route is on top
  (Settings, Sessions, Search, Changes, a subagent session), first
  `navController.popBackStack<Chat>(inclusive = false)` back to the start entry.
- "New session" while Home is already shown: no navigation; focus the composer.
- Opening a top-level session (drawer, list pane, Sessions screen, search, notification
  deep link, fork result): pop to the start entry as above, then
  `ChatViewModel.select(id)`; the switch animates with shared axis X (DS §6). The unsent
  Home draft is kept by the composer spec's per-route draft store (keyed `home`).
- **Back from a selected top-level session returns to Home**, and only Home leaves the app.
  Implementation: `PredictiveBackHandler(enabled = selection != null && isStartEntry)`
  registered in the chat host *below* the sheet/menu/rename/drawer handlers (DS §5.8 items
  1–7), driving the shared-axis-X pop seekably with the gesture progress; commit →
  `select(null)`; cancel → restore. It is not an always-on handler (DS §5.8): it is
  disabled at Home, so the system back-to-home preview works there.
- Back at Home with nothing open: not intercepted → system back-to-home (DS §5.8 item 10).
  Open sheets/menus consume back first (DS §5.8 order).
- Why not one back-stack entry per session: the first send must not remount the composer
  (IME and focus stay, §1.1), and drawer navigation must not grow the stack; a selection in
  the start entry gives both, with the back behaviour Android expects from a drawer
  destination (back lands on the start destination, then leaves).

### 2.10 Accessibility

- Reading order: app bar → heading → workspace chip → branch chip → notices → composer.
- The heading is screen-reader only (desktop sr-only `<h1>`): a node with
  `semantics { heading(); contentDescription = "What would you like to build?" }` around
  the hero, whose illustration clears its own semantics.
- Chips: `Role.Button`, CDs above, 48 dp targets.
- Kernel notice and setup notice are `LiveRegionMode.Polite` and announce once when they
  appear (desktop `<output>`).
- Reduced motion (DS §6): dots static (the hero is static already).

### 2.11 Acceptance (S25U unless noted)

- [ ] Fresh install with the kernel ready and a provider configured: Home shows the bot, "What would you like to build?" with the verb cycling build → create → fix → know every 5 s, chip "Home" (A22), composer with the welcome placeholder. No full-screen spinner at any point.
- [ ] Rename `~/.cline/data/settings/providers.json` aside in Termux, relaunch: the setup notice appears with "Connect a model to start building" and both buttons; TalkBack announces it once. Restore the file, return to the app: the notice disappears on resume.
- [ ] `adb logcat -d` captured across a launch and a resume contains no provider key material (grep for the configured key's first 8 characters and for `apiKey`): zero hits.
- [ ] `cline hub stop` in Termux while Home is open: within 5 s the kernel notice reads "Termux asleep" / "Start kernel to continue." (or "Cline Hub @unknown" / "Cline Hub is not connected." when Termux itself is gone), the setup notice hides, the composer shows "Start kernel". Tapping it brings the Hub up and the notices clear.
- [ ] Pick a git workspace: the branch chip appears with the current branch; the sheet opens fully expanded, scrolled to the current branch, with no keyboard; it lists local branches, filters by "Search branches", switches branch, and shows "No branches found" for a nonsense filter. Pick a non-git folder: no branch chip. Pick "Just chat" while `~` is a git checkout: no branch chip.
- [ ] Branch sheet with the keyboard open: tap "Search branches", type two letters: the filtered rows are visible between the field and the keyboard and can be scrolled and tapped.
- [ ] Start a long run in a git workspace, return to Home with that workspace: the branch sheet opens with every row disabled and "Branch switching is unavailable while an agent is running in this workspace."
- [ ] Type and send "hello": the composer does not move, the hero fades out and the transcript appears with the user bubble and "Thinking..." within one frame; the session appears in the drawer.
- [ ] Rotate to landscape (height-compact): hero hidden, composer docked, nothing clipped.
- [ ] Tab S7+ portrait and landscape: rail present, column max 840 dp centred, `displaySmall` heading, 96 dp bot; landscape shows the list pane. Portrait (medium) has no empty app bar above the hero.
- [ ] Font scale 2.0 + in-app size 20: hero scrolls, chips wrap, composer stays docked and fully visible.
- [ ] Back from Home leaves the app with the system back-to-home preview.
- [ ] Open a session from the drawer, press back: Home appears (the predictive gesture previews the shared-axis pop); press back again: the app exits. Same after a first send from Home: back from the new session shows Home.
- [ ] Dark, light, dynamic colour on and off: all text/background pairs from DS §1.8.

---

## 3. Screen B: Chat thread transcript

### 3.1 Purpose

Show one session's conversation as the desktop does: the user's turns, the assistant's
answers in Markdown, its reasoning, every tool call with live output, finished runs folded
into "Worked for …", generated images and media, notices and errors; stream live; survive
disconnects, Hub restarts and app death without losing or duplicating rows.

### 3.2 Desktop equivalent

`ChatMessages` (`chat-messages.tsx`) inside `ChatThreadPane`, with `MessageBubble`,
`ReasoningBlock`, `ToolMessageBlock` / `ToolCallRow`, `WorkBlock`, `MessageImageCarousel`,
`GeneratedMediaContent`, `AgentCommandOutput`, `ToolFileDiff`. Copied verbatim: grouping and
collapse rules, label functions, the indicator rule, the
error banner rule, action sets and their disabled rules, all strings. Translated: hover
actions → tap-to-reveal action row + long-press menu + TalkBack actions (DS §9); the 28 px
rows whose bodies open by themselves → 40 dp rows that start folded (A23, A24);
`ToolApprovalPanel`/`AgentAskQuestion` cards at the end of the transcript → bottom sheets
with a banner (approval / ask-question specs); `role="log"` + `aria-live` → one polite
status `Text` (DS §8.1.4). Deliberately omitted: the desktop's transient "Session started:
<id>" status row (it is not persisted, so it would vanish on reopen), the imported-session
notice (import is FM "none").

### 3.3 Layout

```
Compact (412dp)                                  ← 16dp gutters
┌────────────────────────────────────────────┐
│ [≡] ● Fix the login crash          [+] [⋮] │  header (screen E)
├────────────────────────────────────────────┤  hairline on scroll
│                    ╭─────────────────────╮ │  user bubble, end-aligned, ≤85%
│                    │ The login button    │ │  surfaceContainerHigh, shape large
│                    │ crashes on tap, fix │ │
│                    │ it                  │ │
│                    ╰─────────────────────╯ │
│                    [⧉] [✎] 10:42           │  action row (revealed on tap)
│ ▸ Worked for 1m 12s and made 6 tool calls  │  work row (finished run, collapsed)
│ The crash came from a null `session` in    │  assistant markdown, full width
│ `LoginViewModel`. I added a guard and a    │
│ test:                                      │
│ ╭────────────────────────────── [⧉] ─────╮ │  code block, surfaceContainer
│ │ if (session == null) return            │ │
│ ╰────────────────────────────────────────╯ │
│ [⧉] [⑂] 10:44                              │  assistant actions (revealed on tap here; pinned
                                                  only while the answer is the last row, §3.4.2)
│                    ╭─────────────────────╮ │
│                    │ Now run the tests   │ │
│                    ╰─────────────────────╯ │
│ 🧠 Thought for 3s                          │  reasoning row (collapsed)
│ ⟳  Running command  ./gradlew test         │  tool row, spinner in the icon slot
│ │  Output                                  │  rail 9dp, body opened by a tap (A23)
│ │ ┌────────────────────────────────────┐   │  terminal surface #0E0E12, ≤256dp
│ │ │> Task :app:testDebugUnitTest       │   │
│ │ │BUILD SUCCESSFUL in 41s▌            │   │  blinking cursor while running
│ │ └────────────────────────────────────┘   │
│ │ [Proceed while running]                  │  only when detachable
│                                     (↓)    │  ScrollToLatest, 16dp above composer
│ ╭────────────────────────────────────────╮ │
│ │ Agent is working... submit to queue…   │ │  composer (composer spec)
│ ╰────────────────────────────────────────╯ │
└────────────────────────────────────────────┘
```

Tablet (medium and expanded): the transcript column is centred, max 840 dp, gutters 24 dp;
user bubbles max 60 % of the column (DS §4.1 #5). Expanded adds the list pane at start and,
at ≥ 1200 dp, the Changes supporting pane at end (DS §5.2) when opened from the diff chip;
the transcript column then centres in what remains.

The transcript is a `LazyColumn(reverseLayout = true)`; items are `items.asReversed()` with
`key = item.renderKey` (§1.3.3; a `Work`/`Run`/`Tools` item uses its first message's
`renderKey`) and `contentType` per item kind (voice `contentTypeOf`). Content padding:
top 8 dp, bottom 16 dp + 40 dp clearance for the scroll button (desktop `pb-16`), horizontal
gutters. Item spacing per DS §3.3: 4 dp between blocks (A24); a user message adds 16 dp above
(desktop `mt-4`); an assistant answer directly under working rows pulls up by the gap, so it
sits flush (`followsWorkingRows`); rows inside a `Run` item have 0 dp gaps.

App-bar hairline ("hairline on scroll" in the sketch): with a reversed list,
`firstVisibleItemIndex > 0` means scrolled toward *older* content, so it is not the trigger.
The hairline shows when `listState.canScrollForward` is true (older content exists above the
viewport, i.e. content runs under the app bar) and hides when the oldest row is fully
visible.

### 3.4 Row types

#### 3.4.1 User message

- Anatomy: DS §4.1 #5 user bubble (`surfaceContainerHigh`, `shapes.large`, 16 × 10 dp,
  `bodyLarge`), end-aligned with the voice `UserAligned` rule (85 % compact, 60 % medium+).
  Text = `formatDisplayUserInput(content)`; `/team …` and `/<skill> …` stay as written.
- Images: above the text, a `Column` of thumbnails with 8 dp spacing, one per row (desktop
  `grid max-w-2xl gap-2`, `message-bubble.tsx:50-66`; only generated images use the
  carousel), each fits within 225 × 225 dp (desktop `max-h-56.25 max-w-56.25`), `ContentScale.Fit`, `shapes.small`, 1 dp `outlineVariant`
  border, background `surfaceContainerHighest`. CD "Expand attachment N"; image alt
  "Attachment N". Tap → lightbox (screen D) on that image.
- Action row (end-aligned, below the bubble; 40 dp tall icon buttons with 48 dp targets,
  16 dp icons, then the time `labelMedium` `onSurfaceVariant`, DS §8.2.5 short time):
  - Copy: CD "Copy user message" → after copying "Copied user message" and icon `check`
    for 1.6 s (desktop `chat-messages.tsx:390-395`; DS's generic 2 s is overridden for
    message copy so the timing matches the desktop); tooltip "Copy message" / "Copied". Copies the display text.
  - Edit: only when the message has a run count and non-blank text **and** the session
    exposes checkpoints (§3.13). CD "Edit user message", tooltip "Edit message and restart
    from this point". Opens "Edit and restart from here?" (checkpoint spec).
  - Restore: only when `meta.checkpoint` exists. CD "Restore checkpoint", tooltip "Restore
    checkpoint". Opens "Revert to this checkpoint?" (checkpoint spec).
  - Edit/Restore disabled when busy, while switching, or when `versioningPending`.
  - Pending: the tapped icon becomes a 16 dp spinner. Errors: `bodySmall` `error`,
    end-aligned under the row ("Could not restore checkpoint.", "Could not restart from
    this message.").
- Visibility: the row is hidden by default and toggles on a tap on the bubble (Android
  equivalent of the desktop hover reveal); it stays visible while an action is pending or
  errored (desktop `keepUserActionsVisible`).
- Long-press → `DropdownMenu` at the touch point (DS §4.1 #15): "Copy", "Edit and
  restart from here" (if available; the CT copy table's wording, `checkpoint_menu_edit`),
  "Restore checkpoint" (if available), "Select text" *(Android)*. "Select text"
  wraps this bubble's text in a `SelectionContainer` and starts a selection of the whole
  text; tapping outside or back ends it (§3.14).
- Steering messages (§1.3.2) are not rendered.

#### 3.4.2 Assistant message

- No container; `MarkdownText` (voice renderer) at full column width, `bodyLarge`,
  `onSurface`, DS §2.1 markdown metrics. While `isStreaming` the renderer re-parses only the
  last block (DS §6) and shows no caret.
- Above the text, in this order: reasoning row (§3.4.3) when the message carries reasoning;
  images carousel (§3.4.8); media (§3.4.8).
- Links: `primary`, underlined; tap → Chrome Custom Tab: `CustomTabsIntent.Builder()
  .setColorScheme(if dark COLOR_SCHEME_DARK else COLOR_SCHEME_LIGHT)
  .setDefaultColorSchemeParams(CustomTabColorSchemeParams.Builder().setToolbarColor(
  colorScheme.surface.toArgb()).build()).build().launchUrl(context, uri)`; if no Custom
  Tabs provider is installed, fall back to `Intent.ACTION_VIEW`; if that throws
  `ActivityNotFoundException`, snackbar "No app can open this link." *(Android)*. A
  **deceptive link**
  (visible text looks like a URL whose host, port or stated scheme differs from the target —
  desktop `isDeceptiveLink`, ported) opens `AlertDialog` "Open external link?" / "You are
  about to leave Cline and visit this address." + the URL in a mono block (`surfaceContainer`,
  max 128 dp, scroll) + "Cancel" / "Open link" ("Open link" uses the same Custom Tab →
  `ACTION_VIEW` → snackbar chain). External images in Markdown are not loaded:
  "External image blocked for privacy" or "External image blocked for privacy: <alt>".
- Code blocks: voice `CodeBlockView` with its copy button (CD "Copy code" *(Android)*;
  check icon for 1.6 s, same as message copy).
- Action row (start-aligned, below the text): Copy (CD "Copy assistant message" →
  "Copied assistant message", tooltip "Copy raw assistant output"; copies the raw
  `content`), then the time. No fork on the row (A16, decided 2026-10-08: the whole-session
  fork lives only in the chat's three-dot and the drawer's row menu, one place per function;
  batch 1's "Fork before this turn" (A13) was removed in batch 2, and the desktop row's own
  whole-session fork button is not mirrored). Shown only when not streaming,
  not an error, content non-blank. **Always visible while this assistant message is the last
  conversation row** — the newest message of the whole transcript ignoring `Status` rows
  (desktop `isLastAssistantMessage = role == assistant && lastConversationMessage ===
  message`, `chat-messages.tsx:134-153, 602-605, 652-654`). As soon as anything newer
  arrives (the user's follow-up bubble, tool rows, the next answer, an error row) the row
  hides again. Otherwise it is revealed by a tap on the message (as for user bubbles) and
  offered as long-press menu items "Copy", "Select text" *(Android)* and as TalkBack custom
  actions.
- Fork binding (FM "Fork session", CLI `forkCurrentSession`): the chat three-dot's "Fork"
  (§6) and the drawer row menu's "Fork" fork the whole session, with no cut and no
  `fork.beforeRunCount` (only the user row's Edit writes that, CT C.6). No `/fork` in the
  slash popup (A19). Pending and failure show where the fork started: the header's
  "Forking..." then a snackbar "Fork failed: <reason>" ("Could not fork session." when the
  kernel gives no reason), the drawer's as in the sessions spec (F4). `session.messages
  {sessionId}` → `session.create` built by `buildSessionCreate` (§1.3.6) with
  `sessionConfig: <current provider, model, mode, flags>`, `initialMessages: <messages>`,
  `metadata: {...current.metadata minus "fork", source:"android", interactive:true,
  title, fork: {forkedFromSessionId, forkedAt: <ISO>, source: "android"}}`,
  `toolPolicies: <current>` → select the new session as a top-level session (§2.9; it
  replaces the current selection, back goes to Home). Title = CLI
  `deriveForkSessionTitle` (`fork/title.ts:55-66`), ported 1:1: base = the first non-blank
  of (1) the source title, (2) the source prompt, (3) the first user message text, then the
  first assistant message text, each normalised through `formatDisplayUserInput`, (4)
  "Untitled"; the base is truncated so that base + " (fork)" fits in 120 characters. Empty
  history → error "Cannot fork an empty session." (CLI string).

#### 3.4.3 Reasoning row

DS §4.1 #3 (40 dp row, 18 dp `neurology` icon, `bodySmall` label, A24; label
`formatThoughtLabel`, body capped at 192 dp, scrollable,
colour `lerp(onSurfaceVariant, onSurface, .75f)`, markdown `bodyMedium`). Streaming: label
"Thinking" with shimmer; done: "Thought for Ns"; redacted only: body "[redacted]".
Collapsed by default, also while streaming (desktop default). Multiple reasoning-only
messages before an answer join into one row (`reasoningMessages`, contents joined with a
blank line).

#### 3.4.4 Tool rows

DS §4.1 #2 anatomy and DS §7.2 icons. Per row, from `buildToolPresentation(message)`:

| Field | Rule |
| --- | --- |
| Status | `error` when `payload.isError`; `running` when `inProgress && isRunActive` (the row sits after the last user message and the session is busy); `interrupted` when `inProgress` and not `isRunActive` *(Android, decided A21)*; else `success`. The desktop keeps a result-less row of a finished run in progress ("a missing result can outlive its run"), so a call whose result never came (a cancelling `PreToolUse` hook ends the whole run and tells no client why, upstream U5; a Hub restart) read "Running …" forever (trial row 9). A finished turn has no running call: the run ended (history, `run.completed`, `run.aborted`, `run.failed`) or a later user message opened another turn. While the run is live (Starting, Running, Stopping) the row stays `running` |
| Row | 40 dp tall and full width; the whole row is the touch target (DS §3.4, `DisclosureRowTokens`, A24) |
| Leading slot (18 dp) | running → 14 dp `CircularProgressIndicator` (stroke 2 dp, `LocalContentColor`); error → `error` (fill 1) in `error`; interrupted → `warning` in `warning.color` (the tool detail's Interrupted glyph, CT B.5); else `getToolNameIcon(toolName)` |
| Label | `summary.labelParts` (`bodySmall`, code parts `ClineMono.small`), 1 line, ellipsis; shimmer while running. `submit_and_exit` uses "Completing scheduled task" / "Scheduled task failed" / "Scheduled task completed". Interrupted *(Android)*: "Interrupted: `<specific>`" with the in-progress label's command, file name, query, URL, skill, task, question or tool name (code parts stay mono), or "Interrupted" when it names none; TalkBack status word "interrupted". The tool detail's title follows (its status line keeps "No result") |
| Trailing | `+N -N` (`ClineMono.small`, `success.color` / `error`) when `summary.diff` is non-zero |
| Expandable | iff any of: details, file diffs, submit text, output text (or command output), media, error text, proceed button. Not expandable → no ripple, no click, no chevron (desktop hides the chevron on tool rows anyway) |
| Open state | Folded until the user taps it, whatever the body holds (A23: the desktop's auto-open rule for file diffs, submit text, output media and running output is not ported). Once opened it stays open while the row updates and its run ends (`rememberSaveable(message.renderKey)`, kept per session within the process) |

Body (under the rail, DS §3.3), in desktop order:

1. Details: `summary.details`, one per line; commands as `$ <command>` in `ClineMono.body`;
   others `bodyMedium` `onSurfaceVariant`.
2. File diffs: one `ToolFileDiff` per hunk (DS §4.2), or a mono code block for a text-only
   diff.
3. Output: commands → `CommandOutput` (DS §4.2: "Output" overline, terminal surface, ANSI,
   max 256 dp, auto-scroll pinned within 24 dp, blinking cursor while running, CD "Command
   output"); source = `meta.toolOutput` (live) or `extractRunCommandOutput(result)`
   (history), capped by `appendCappedCommandOutput` (48 000 chars, marker
   "[Earlier command output truncated]"). The kernel's own marker "[N command-output
   characters skipped while streaming]" (FX) is shown as received. With several commands
   each part starts with `$ <command>`. The result is presented with the call input's
   commands in its records' `query` (`commands[i]` in the kernel's order, heredoc entries
   joined as the kernel joins them; `withExecutedCommands`, trial row 18), so neither these
   lines nor the JSON fallback of a call that printed nothing show the kernel's echo, which
   it caps at 200 characters and ends with "... [command truncated: N more chars; full
   command is in the tool call input]"; the echo stands in only when the call has no input,
   with "…" for the note. `submit_and_exit` →
   Markdown in `onSurface`. Other tools → `summary.outputText` in a mono block
   (`surfaceContainer`, `shapes.small`, max 256 dp, scroll), capped at 64 KiB with
   "[output truncated]".
4. Output images → carousel (§3.4.8); other media → media rows (§3.4.8).
5. Error text: `bodyMedium` `error` (e.g. the rejected-call text "This tool call was
   rejected by the user and not executed. -- NOT a tool or system failure. Clarify with user
   before proceeding." as received, FX).
6. Proceed: `OutlinedButton` "Proceed while running" when running, a command, and
   `meta.toolDetachable`; binding `run.proceed_while_running {sessionId, toolCallId}` (FM)
   → `{detachedCount}`; pending → 16 dp spinner in the button; error under it "Could not
   detach the running command." (or the reply's message; "The command finished before it
   could be detached." when `detachedCount == 0`).

Per kind (labels come from the port; these are the cases the S25U tester must see):

| Kind | Typical label (done / running) | Body |
| --- | --- | --- |
| command (`run_commands`) | "Ran command `echo hello-from-mock`" / "Running command `…`"; several → "Ran 3 commands" | `$ cmd` lines + Output terminal; proceed button |
| edit (`editor`, `apply_patch`) | "Edited file `LoginViewModel.kt`", "Created file `…`", "Applied patch" + `+12 -3` | per-hunk diffs |
| read (`read_files`) | "Read file `app.tsx (10–80)`", "Read 3 files" | file list; output collapsed |
| search (`search_codebase`) | "Searched `query`", aggregated "Explored 2 searches" | output |
| web (`fetch_web_content`) | one URL: "Fetched `https://…`" / "Fetching `https://…`" (`tool-summary/index.ts:553-563`); URL unknown: "Fetched web content" / "Fetching web content" (`:301`) | output text |
| mcp (`mcp…` names, fuzzy) | humanized tool name / "Called MCP tool" | output text, output images |
| spawn / team / skill / question / other | per port ("Spawned agent: …", "Used skill …", "Asked \"…\"", "Running <Name>") | per port |

Consecutive tool messages form one `Tools` item (0 dp gaps); aggregates merge per the port.

#### 3.4.5 Work row (folded run)

DS §4.1 #4 (40 dp row, `bodySmall` label, 18 dp chevron, A24). Label
`formatWorkActivityLabel` ("Worked for 4m 12s and made 14 tool calls",
"Worked for 3s", "Made 1 tool call", "Worked"). Collapsed by default; expanding re-shows the
run's rows at transcript level (no rail). Folding happens only per `collapseCompletedWork`:
a span settles when a later user message exists, or the trailing span when status is
Completed/Idle **and** the run ended on an answer. Cancelled or failed tails stay unfolded.
Expansion state is keyed by the work item's stable id (the first collapsed message's
`renderKey`, §1.3.3), so the row keeps its state when canonical rows replace live ones.

#### 3.4.6 Notice rows (`session.notice`) *(Android)*

The desktop webview does not render `session.notice` at all (neither `chat-messages.tsx`
nor `use-chat-session.ts` handles it; its only status row is the transient "Session
started: <id>", omitted here, §3.2). This row is an Android design. The reason list comes
from the notice types in `sdk/packages/shared/src/agents/types.ts`; the DS §4.2
attribution to `chat-messages.tsx` is wrong (Q14). DS §4.2 SessionNotice row: 20 dp icon (`info` for `status`, `autorenew` for `recovery` and
for reasons `auto_compaction` / `manual_compaction` / `compaction_budget_emergency`,
`pause_circle` for `stop`) + message `bodyMedium` `onSurfaceVariant`, kernel text untranslated.
`reason == "api_error"` renders as an error message instead (§3.4.7). Notices are live-only
(not in `session.messages`); they are kept in the SessionStore for the process lifetime and
are not re-created after a cold start.

**Stop notices (Android, decided A17).** When the Hub takes the app's "stop" answer to the
mistake-limit question (the user's "Stop this run", or the stop sent after the question went
unanswered for `MistakeLimitPrompt.TIMEOUT_MS`), `run.aborted` carries only "aborted", so the
app adds its own notice row (`StopNotices`, `Role.Status`, id `ms:<capability requestId>`,
`noticeType = "stop"`): `pause_circle` + "Stopped after repeated mistakes" with the kernel's
detail under it as "Latest: <detail>" (`bodySmall`, trimmed and cut like the sheet's line), or
"Stopped: the question was not answered within 5 minutes". The headline names no count: on a
loop stop the kernel forces `consecutiveMistakes` to the limit, so "6 mistakes" stood over a
detail about 5 identical calls (trial row 21). The row sits at the end of the run it stopped:
the model call in flight when the Hub took the stop still lands after the stop, so while that
run is open the row waits (`StopNotice.endsRun`), and once the run is closed it takes the run's
end time (`TurnRecord.closedAt`: the terminal event's Hub time, or when the app marked it
interrupted or found it settled on a reconnect). A stop the Hub took with no open run shows at
once, at the time the Hub took it; so does a waiting notice whose run the app no longer knows
after a cold start. An answer the Hub ignored (`ignored:true`: the request was
already over) or that failed adds none. The stop the app sends when the guidance steer of
"Try a different approach" fails (`MistakeLimitPrompt.answer`) is outside A17's two cases and
adds none either (the user chose to continue; that failure has no notice yet). Unlike
kernel notices these persist in the app's private state (`sessions.stopNotices`, newest 200,
docs/07 §7.3), so they come back after leaving the session, its eviction and a cold start; a
deleted session, or one the Hub no longer has, drops its notices. The kernel's own export
(`cline history export`) does not contain them.

#### 3.4.7 Error message

DS §4.1 #5 error surface (`errorContainer`, `shapes.medium`, 12 dp padding, `onErrorContainer`,
selectable). Text = `formatRunError(detail, meta.providerId, meta.providerAuth)` (port of
`webview/lib/run-error.ts`): "The run failed: <detail>" (unless it already starts with "The
run failed"), or "The run failed before a response was produced." when empty, followed by
the hint **when the detail is empty or `isCredentialFailure(detail)`** (desktop
`looksCredentialRelated = !description || isCredentialFailure(description)`, so the
empty-detail sentence always carries a hint) and the detail does not already contain it.
Hint = `resolveCredentialFailureHint(providerId, providerAuth)`, in this order:
`providerAuth.localCli` set → "Sign in again with the `<command>` CLI in a terminal, then
try again."; provider `cline` → "Sign in to Cline again in Settings → Account, then try
again."; `providerAuth == null` → "Sign in again using your provider's authentication
method, then try again."; else → "Check your model connection in Settings → Providers (or
sign in with Cline), then try again.".

Credential action (`resolveCredentialFailureAction`, shown only when `meta.reason ==
"credentials"`): provider `cline` → `OutlinedButton` "Sign in to Cline" → Settings >
Account; `providerAuth` known and without `localCli` → "Open API providers" → Settings >
Providers; otherwise no button. `providerAuth` comes from §1.3.5, so with the settings
helper down an `api-key`/`oauth` provider from the bundled list, or any provider configured
in `providers.json`, still gets "Check your model connection…" + "Open API providers"; a
`local` provider, or an id the app cannot find anywhere, gets the generic "Sign in again
using your provider's authentication method…" and no button. When the settings helper is
unavailable (blocking unknown 3) both buttons go to the Connect-a-model screen.

`formatRunError` unit tests (ported from the desktop's `run-error` tests plus): empty detail
→ "The run failed before a response was produced. <hint>"; "401 Unauthorized" with
`cline`, with a known api-key provider, with `null` auth, with a `localCli` provider; a
non-credential detail ("maximum context tokens exceeded") → no hint; a detail already
containing the hint → not repeated.

Interrupted run (`meta.reason == "interrupted"`, produced by the derived detection in
§1.3.4 for the app's own turns, or by a genuine `run.interrupted` for queue-admitted runs):
same surface, text "This run was interrupted because the Cline Hub restarted." *(Android)*
+ `OutlinedButton` "Resend" *(Android)*. Resend resumes the session with
`buildSessionCreate` (§1.3.6, resume row: old id + `session.messages`, askQuestion
contribution included) because a restarted Hub never has it live, then sends the turn's
prompt (§1.3.4) with `session.send_input`; pending → 16 dp spinner in the button; failure →
the §3.12 resume-error banner. The button is hidden when no prompt is recoverable, and
disappears once a newer user message exists.

#### 3.4.8 Images and media

- Assistant images (`message.images` on assistant messages, tool output images): one image
  → a single thumbnail fitted in 225 dp; two or more → `LazyRow` of 160 dp-high thumbnails
  (DS §4.2), 8 dp apart, `shapes.small`, 1 dp `outlineVariant`. CDs "Expand generated
  image N", alt "Generated result N". Tap → lightbox at that index.
- `assistant.media` / history `media` (`GeneratedMedia {id, modality, mediaType, source,
  name?}`; port `generated-media.tsx` rules):
  - `image` with `source.type == "base64"` and a supported type (png, jpeg, gif, webp),
    decoded size ≤ 6 MiB, total ≤ 8 MiB (`shared/src/llms/media.ts`): image thumbnail as
    above, alt = `name` or "Generated image".
  - `audio` / `video` / `file` with base64 source: a 56 dp `ListItem` (leading
    `audio_file` / `movie` / `description`, headline `name` or "Generated file
    (<mediaType>)", trailing `TextButton` "Open" *(Android)*). "Open" writes the bytes to
    `cacheDir/media/<id>` and fires `ACTION_VIEW` through the app's `FileProvider` with the
    media type; no app → snackbar "No app can open this file." *(Android)*.
  - `url` source: text row "Generated <modality> requires a trusted remote-media
    resolver." (the app never loads provider URLs, same policy as desktop).
  - `artifact` source: "Generated <modality> is stored as an artifact (<artifactId>)."
  - undecodable: "Generated <modality> could not be rendered."
  Fallback rows use `Surface(surfaceContainerHighest, shapes.small)`, 12 dp padding,
  `bodyMedium`.
- Decoding: off the main thread (`Dispatchers.Default`), `BitmapFactory` with
  `inSampleSize` to the displayed size, cached in an `LruCache` keyed by `<messageId>:<index>`
  (32 MiB). A thumbnail shows a `surfaceContainerHighest` placeholder of its final box while
  decoding.

#### 3.4.9 Transport line, switching, error banner

See §3.9–3.11.

### 3.5 States

| State | Trigger | What shows |
| --- | --- | --- |
| Loading (opening a session) | a session is selected and no messages are cached | after 180 ms (desktop delay): the DS detail-pane loader — an indeterminate `LinearProgressIndicator` directly under the `TopAppBar`, full width, `primary` on a `surfaceContainerHighest` track (DS §4.2 "Loading skeleton": skeletons are for list first loads only, detail panes use this). Its `contentDescription` and the §3.15 polite status text say "Loading session...". The transcript area is empty meanwhile. `session.messages` + `session.get {includeSnapshot:true}` + `HubClient.subscribe(id)` (no cursor, §1.1) |
| Switching with cache | messages cached for `id` | cached rows at once + the same `LinearProgressIndicator` after 180 ms, `contentDescription` / status text "Switching session...", until `session.messages` returns. (The desktop's floating pill and skeleton card, `chat-messages.tsx:760-778`, are desktop chrome; the copy is kept, the component is the DS one) |
| Empty | session has no messages (just created, or deleted history) | nothing but the indicator if Starting; never the generic empty state (desktop shows nothing) |
| Populated idle | Idle / Completed / Cancelled | rows; the answer's action row visible when it is the last conversation row (§3.4.2) |
| Running / streaming | Running | rows unfolded, live deltas, tool spinners, indicator rule (screen C) |
| Stopping | Stopping | as running; composer shows stop pending (composer spec) |
| Approval pending | `pendingApprovals > 0` | indicator hidden; the approval sheet/banner (approval spec); header dot "Approval required" |
| Question pending | `pendingQuestions > 0` | indicator hidden; ask-question sheet (ask-question spec) |
| Failed | `run.failed` | error message row; header dot Failed |
| Interrupted | derived interruption (§1.3.4) or a genuine `run.interrupted` | interrupted error row with "Resend" |
| Persisted, not live | **Authoritative:** a `send_input` reply `session_not_found` (§3.12 step 4: resume once, resend). **Hint only:** the session is not in this process's live set (`SessionStore` saw no `session.create`/turn for it since the last Hub start) and `session.get` status is `completed`/`aborted`. FX says not to use `createdByClientId` for this, and a just-resumed live session still reports `completed` until a turn runs (FX `05` step 5), so the hint can be wrong both ways | rows from history; composer enabled. With the hint set, the first send resumes up front (§3.12 step 3); without it, the send goes straight out and step 4 recovers. No visible difference to the user either way |
| Missing | `session.get` → `session_not_found` ("Unknown session: <id>", FX) | full-pane `EmptyState` (DS §4.2): icon `chat_bubble`, title "This session no longer exists." *(Android)*, body "It may have been deleted from another device or the Termux CLI." *(Android)*, `FilledTonalButton` "New session" → Home |
| Offline / kernel down | KernelLine connecting / reconnecting / unavailable / Termux asleep | rows stay (cached); transport line (§3.9); composer per §1.6 |
| History load error | `session.messages` fails (not `session_not_found`) | error banner "Couldn't load this session: <message>" *(Android)* + `TextButton` "Retry" |
| Permission missing | RUN_COMMAND permission error from any bridge call on this screen (e.g. branch, export) | navigate to Kernel setup (kernel-setup spec) |

### 3.6 Data bindings summary

| Need | Kernel API (FM / FX) |
| --- | --- |
| Open | `session.get {sessionId, includeSnapshot:true}`; `session.messages {sessionId}` (reconciliation with empty `live`, §1.3.3); `stream.subscribe {clientId, sessionId}` **without** `sinceSequence` (approval re-issue only; catch-up comes from the global cursor, §1.1); if live: `session.attach {sessionId}` (FX: attach does not make a persisted session live) |
| Live rows | §1.3.1 events |
| Refresh after a turn | `session.messages` on every terminal event (`run.completed` / `run.failed` / `run.aborted` / derived interruption); the turn-slice replacement with key inheritance of §1.3.3 (not id matching); `Error`/`Status` rows kept |
| Fork | `session.messages` → `buildSessionCreate` fork row (§1.3.6, §3.4.2) |
| Proceed | `run.proceed_while_running {sessionId, toolCallId}` |
| Resume (not live) | `buildSessionCreate` resume row (§1.3.6): `{workspaceRoot, cwd, sessionConfig:{sessionId:<old>, providerId, modelId, mode, enableTools, checkpoint:{enabled:true}}, initialMessages:<session.messages>, metadata:{source:<persisted ?: "android">, interactive:true, provider, model}, toolPolicies, runtimeOptions:{clientContributions:[askQuestion]}}` — FX `05`'s recorder payload plus the contribution, without which `ask_question` disappears from every reopened session |
| Usage | `usage.updated`, `session.get` usage/aggregateUsage |

### 3.7 Interactions and gestures

- Scroll: vertical fling; no `imeNestedScroll` (DS §5.7: swiping must not close the IME).
- Tap a disclosure row (tool, reasoning, work) → toggle; animation DS §6. While the list is
  not pinned to the bottom, the tapped row's header is held in place: for `WORK_PIN_MS =
  350 ms` the VM disables follow-the-bottom and the row reports its height delta, which the
  list compensates with `dispatchRawDelta(GROWTH_SCROLL_SIGN * delta)` (voice
  `ChatScreen.kt`; reverse layout would otherwise push the header up).
- Tap a user bubble or an assistant message (outside links and code) → toggle its action
  row. Long-press any message → menu (§3.4.1–3.4.2).
- Tap an image → lightbox. Tap a link → Custom Tab (or the deceptive-link dialog).
- Pull-to-refresh: none (live stream; desktop has none).
- Hardware keyboard: Esc stops the agent when abortable (composer spec); PageUp/PageDown
  scroll the transcript when it has focus.

### 3.8 Usage in the thread

The transcript has **no per-message usage row** (desktop has none). `usage.updated` and
`session.get` fill `ChatUiState.usage` (`inputTokens`, `outputTokens`, `cacheReadTokens`,
`cacheWriteTokens`, `totalCost` from `aggregateUsage`, FX), consumed by the composer's
context ring (composer spec) and the Session details sheet rows "Tokens" and "Cost" (§6.5).

### 3.9 Transport line

Shown at the end of the transcript (above the composer, inside the column) when the socket
is not connected and no error banner is shown (desktop rule): 16 dp spinner + `bodySmall`
`onSurfaceVariant`: "Connecting chat...", "Reconnecting chat...", or "Chat backend
unavailable"; for Termux asleep add a second line in `error` "Start kernel to continue.".
**Deviation:** the desktop shows its `Loader2` spinner for all three states
(`chat-messages.tsx:781-790`); the app shows **no spinner for "Chat backend unavailable"**,
because on the phone nothing is in progress in that state until the user taps "Start
kernel" (the app does not retry a stopped Termux by itself), and a spinner would promise a
recovery that is not happening. A `warning_amber` 16 dp icon in `onSurfaceVariant` takes
the spinner's slot so the line keeps its geometry. Hub draining (`hub_draining` reply or `hub.drain_changed {draining:true}`):
"Cline Hub is updating..." *(Android)* with spinner.

### 3.10 Error banner

Shown under the last row when `errorBanner != null` and it is not the same text as the
last error message (desktop `shouldShowErrorBanner`): `Surface(errorContainer, shapes.small,
1 dp error @ 40 %)`, 12 dp padding, `bodyMedium` `onErrorContainer`, selectable, optional
`OutlinedButton` action ("Retry"). Sources: create/resume failure, history load failure;
fork failure is inline (not a banner). A create/resume failure banner carries the
attempted config's `providerId` and `providerAuth` (§1.3.5); when `isCredentialFailure
(message)` its action is the credential action of §3.4.7 instead of "Retry".

### 3.11 Session switching

Opening another session: the previous `SessionState` stays in the store; the new one
shows the loading or switching indicator per §3.5; checkpoint/edit confirmation dialogs belonging to the
old session close (desktop resets them on `sessionId` change); an open lightbox belonging to
the old session closes.

### 3.12 Sending in a thread (hand-off to the composer)

The composer calls `ChatViewModel.send(...)`. Rules this screen owns:

1. Optimistic user message `local:<requestId>` appended; `AutoScrollOnSend`: whenever the
   count of user messages grows, scroll to the newest item even if the user had scrolled up.
2. Busy session: `delivery = "queue"` (composer spec), no optimistic bubble (the queue strip
   shows it).
3. Not live (the §3.5 hint is set): run the resume `session.create` (§3.6, built by
   `buildSessionCreate`) first (status Starting, indicator
   "Thinking..."), then `send_input`. Resume failure → error banner "Couldn't resume this
   session: <message>" *(Android)* + "Retry".
4. `send_input` reply `session_not_found` (KC) → resume once (`buildSessionCreate` resume
   row), then resend. This is the authoritative "not live" signal.
5. `hub_draining` (retryable, KC) → keep the optimistic bubble with the transport line
   "Cline Hub is updating..." and resend on `hub.drain_changed {draining:false}`.
6. Reconnecting → the send waits in memory (max one prompt per session) and goes out after
   re-register + re-subscribe; on `Unavailable` it is returned to the composer draft and a
   snackbar says "Message not sent. Start kernel to continue." *(Android)*.

Snackbars of this group (this one, branch errors §2.5, rename/delete/export §6.6, copy and
link failures) often fire while the IME is open. The chat and Home `Scaffold`s exclude the
bottom inset (DS §5.7) and `SnackbarHost` does not apply IME insets, so the Scaffold's
`snackbarHost` slot is **not** used here. Instead the `SnackbarHost` (same app-wide
`SnackbarHostState`, DS §4.1 #17) sits in the bottom container directly above the composer,
8 dp above its top edge; that container already carries
`Modifier.windowInsetsPadding(WindowInsets.ime.union(bottomSafe))` (DS §5.7), so a snackbar
rides on the keyboard when it is open and on the navigation bar when it is closed, and
never covers the composer.
7. Pending commands fail with `hub_connection_closed` and are not retried except idempotent
   reads (KC); `send_input` is never auto-retried after it was written to the socket — the
   event stream is the truth (a `run.started` with our `requestId` after reconnect means it
   went through).

### 3.13 Checkpoint-dependent actions

FX shows checkpoints exist only if the client registers the `checkpoint.create`
contribution. Until the checkpoint spec ships that capability, `snapshot.checkpoint.history`
is empty and **Edit and Restore are not rendered** (no disabled placeholders, DS §9). When
history is non-empty, `meta.checkpoint` is filled from it by `runCount` and the actions
appear with the desktop dialogs (checkpoint spec).

### 3.14 Text selection

Message bodies are not permanently inside a `SelectionContainer`, because a long-press must
open the message menu (DS §4.1 #15). "Select text" in that menu turns the message body into
a `SelectionContainer` with the whole text selected and the system text toolbar shown
(Copy / Share / Select all); the mode ends on a tap outside the message, on back, or when
the message leaves composition (scrolled far out of the list's composed window). It does
**not** end when scrolling starts: the user must be able to scroll while extending a
selection through a message taller than the viewport (selection handles drag-scroll the
list at its edges), as in Messages or Gmail. Code
blocks, command output, diff lines and the error surface are always selectable (no menu
there). See Q8 (DS §8.1.11 wording).

### 3.15 Accessibility

- One invisible polite status `Text` per conversation (DS §8.1.4): "Agent is working…" on
  `run.started`; the first 200 characters of the final assistant text on `run.completed`;
  "Run failed: <error>" on `run.failed`; "Loading session..." / "Switching session..." when
  the §3.5 progress indicator appears (after its 180 ms delay).
- Tool / reasoning / work rows: `Role.Button`, `stateDescription` "Expanded"/"Collapsed",
  CD = full label + status ("Ran command bun test, error"); not-expandable tool rows are
  plain text with CD = label + status.
- Each message exposes `customActions` equal to its long-press menu and action row
  ("Copy user message", "Edit user message", "Restore checkpoint", "Copy assistant
  message", "Select text"); no fork on message rows (A16).
- The user bubble's tap toggles the action row; TalkBack users get the actions through
  custom actions, so the toggle has `onClickLabel = "Show message actions"` *(Android)*.
- The `LazyColumn` has `Modifier.semantics { paneTitle = "Agent conversation";
  isTraversalGroup = true }` (desktop `aria-label="Agent conversation"`,
  `chat-messages.tsx:552`).
- Reading order is top → bottom = oldest → newest. `isTraversalGroup` alone does not order
  siblings, and the reversed layout composes index 0 (newest) at the bottom, so each item
  sets `Modifier.semantics { traversalIndex = -index.toFloat() }` (index in the reversed
  item list: newest = 0, so older rows get lower, earlier indices). Inside an item, its
  own children keep natural order (`isTraversalGroup = true` on the item).
- Commands and paths read as written (DS §8.1.12). Code/diff/output LTR (DS §8.2.6).
- Text scaling: no fixed heights except the capped bodies (192 dp reasoning, 256 dp
  output), which scroll.

### 3.16 Acceptance (S25U)

- [ ] Send "Run echo hello-from-mock and summarize" with tools on and auto-approve off: "Thinking..." appears; after approval the row reads "Running command `echo hello-from-mock`" with a spinner in the icon slot and stays folded; tapping it opens the Output terminal, which shows `hello-from-mock`; the row then shows the terminal icon and "Ran command …"; the answer streams in Markdown; when the run completes the rows fold into "Worked for Ns and made 1 tool call" with the answer below it.
- [ ] Expand the work row: the reasoning row, tool row and narration re-appear at transcript level; collapse again; the header of the tapped row does not jump while doing so (scroll up a screen first to test the not-pinned case).
- [ ] A model with reasoning (e.g. a Claude model with thinking on): "Thinking" shimmers during the stream, then "Thought for Ns"; the body scrolls inside 192 dp.
- [ ] Ask Cline to edit a file: the row shows "Edited file `…`" with `+N -N` and stays folded; tapping it shows the hunk with green/red lines; the header diff chip appears.
- [ ] Ask Cline to read a file and to fetch a URL: rows "Read file `…`" and "Fetched `…`" collapsed; expanding shows the output text in a mono block.
- [ ] Reject a tool call: the row turns into the error state with the rejection text, the run continues and completes (FX `03`).
- [ ] Ask Cline to run `sleep 60; echo done`: the row stays folded with its spinner; tapping it opens the body, and when that shows "Proceed while running", tap the button; the run continues without waiting; the button shows a spinner while pending.
- [ ] Stop the agent mid-stream: status becomes Cancelled, the partial rows stay unfolded, no work row appears.
- [ ] Toggle airplane mode for 20 s during a stream (the Hub is local, so instead: force-stop and relaunch the app mid-stream): on return the transcript catches up via replay with no duplicated rows and no missing deltas.
- [ ] `cline hub stop && cline hub start` in Termux mid-run (a `sleep 60` command running): after the app reconnects (new `/version` pid) and the replay drains, an error row "This run was interrupted because the Cline Hub restarted." with "Resend" appears and the status is Idle, not Cancelled or Completed; "Resend" resumes the session and runs the prompt again. Repeat with the app force-stopped during the restart and relaunched afterwards: the same row appears (detected from the replayed shutdown projection and the persisted turn record).
- [ ] Send a turn that completes, then one with a tool and reasoning: after each run ends the transcript shows no duplicated user, assistant, reasoning or tool rows, an expanded reasoning or tool row stays expanded, and the viewport does not jump (reconciliation, §1.3.3).
- [ ] Open a session created yesterday (persisted): history renders identically to a live one (user text without `<user_input>` wrappers); the first send resumes it and continues the conversation.
- [ ] In that reopened session (and again in a fork, and after a Resend), ask "Ask me a multiple-choice question before you continue": the ask-question sheet appears (the askQuestion contribution was sent on resume).
- [ ] Delete the open session from the TUI (`cline history delete --session-id <id>`) and reopen it in the app: "This session no longer exists." with "New session".
- [ ] Long-press a user bubble: menu "Copy", "Select text"; "Select text" selects the whole text with the system toolbar. Tap the bubble: the action row with Copy and the time toggles.
- [ ] "Select text" on an assistant answer longer than one screen: drag the end handle down; the list scrolls and the selection keeps extending; the selection survives a manual scroll and ends on a tap outside or back.
- [ ] The newest answer, when it is the last row, shows Copy and the time without a tap (no Fork, A16); after sending a follow-up, its action row hides (tap or long-press brings it back). The three-dot's Fork creates "<title> (fork)" and opens it with the full history; back from the fork returns to Home. Fork a session whose title and prompt are blank: the fork is titled from the first message text, or "Untitled (fork)".
- [ ] Send an image with a prompt: the user bubble shows the thumbnail; tapping opens the lightbox.
- [ ] Tap a normal link: it opens in a Custom Tab with the app's surface colour; back returns to the chat. With every browser disabled (`adb shell pm disable-user` on the browsers): snackbar "No app can open this link.".
- [ ] Deceptive Markdown link `[https://github.com](https://evil.example)`: "Open external link?" dialog with the real URL.
- [ ] A credential failure (set a wrong API key for `anthropic` with `cline auth`) with the settings helper running: error row "The run failed: … Check your model connection in Settings → Providers (or sign in with Cline), then try again." with "Open API providers". Repeat with the helper stopped (`pkill -f "index.js dashboard"`) and the catalog cache cleared: same hint and button (resolved from the bundled provider list). With the `cline` provider and an expired token: "Sign in to Cline again in Settings → Account, then try again." with "Sign in to Cline".
- [ ] With the keyboard open and the kernel down (`cline hub stop`), send: the snackbar "Message not sent. Start kernel to continue." is visible above the keyboard and the composer.
- [ ] TalkBack: each row announces label + state; custom actions list the menu items; the status text announces "Agent is working…" and the answer start. From the header, swipe right: "Agent conversation" is announced, then the oldest visible row, continuing to the newest; swiping past the last visible row scrolls to newer content. Opening a session announces "Loading session...".
- [ ] 60 fps while streaming 40 chunks 300 ms apart (FX "slow" mock) with 200 prior rows: no dropped-frame warnings in `adb shell dumpsys gfxinfo io.github.ichouchiang.clinetermux` beyond 5 %.
- [ ] Tab S7+: column max 840 dp, user bubbles ≤ 60 %, landscape list pane + transcript; ≥ 1200 dp diff chip opens Changes as a side pane.

---

## 4. Screen C: Streaming indicator and scroll-to-latest

### 4.1 Purpose

Tell the user the agent is working before any output exists, without a layout jump when the
first real row arrives; and give a way back to the newest content when the user has scrolled
away, without fighting the user while new content streams.

### 4.2 Desktop equivalent

The `Loader2` + "Thinking..." row at the end of `ChatMessages` (rule `isAwaitingFirstOutput`,
offset rule `indicatorFollowsWorkingRows`), `Conversation`'s stick-to-bottom logic
(`STICK_TO_BOTTOM_THRESHOLD_PX = 24`, `SCROLL_BUTTON_THRESHOLD_PX = 120`, "sticking is an
intent, not a position"), `ConversationScrollButton` ("Scroll to latest message"),
`AutoScrollOnSend`. Copied verbatim: both thresholds, the intent rule, auto-scroll on send,
the label. Translated: the 28 px row → a 40 dp row matching the tool-row geometry (DS §3.4, A24),
the round scroll button → `SmallFloatingActionButton` (DS §4.2).

### 4.3 Indicator

```
│ ⟳  Thinking...                              │  40dp row, 18dp icon slot with 14dp spinner,
                                                  bodySmall onSurfaceVariant, shimmer label (A24)
```

- Shown when `status == Starting`, or `status == Running` and no message is streaming and
  the last conversation row is not an in-progress tool and no approval/question is pending
  (desktop `isAwaitingFirstOutput`), and not while switching sessions.
- Label: "Thinking..." (three ASCII dots, desktop). No other labels on Android (the
  desktop's `startingLabel` is cloud-only and `activityLabel` is import-only; both omitted).
- Position: last item of the transcript. Gap above: 0 dp when the previous item is a
  `Tools`/`Run` item or an assistant message (desktop `-mt-3` tight offset), else 4 dp
  (the block gap, A24).
  The geometry equals a tool row's leading slot and label baseline, so the first real row
  replaces it in place (cross-fade 150 ms, no height animation).
- TalkBack: the row is `liveRegion = Polite` only through the conversation status text
  (§3.15); the row itself has CD "Thinking...".
- Reduced motion: static label, spinner kept (M3 indeterminate indicator, system-scaled).

### 4.4 Scroll-to-latest

- State: `atBottom = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset <= 24.dp`;
  `pinned` (intent) starts true; a user drag/fling toward older content sets it false;
  reaching `atBottom` sets it true; a programmatic scroll never clears it.
- While `pinned`, new content (`items` change, a streaming message grows, a tool body
  the user opened grows) calls `listState.requestScrollToItem(0)` in a `SideEffect` before
  measure (voice `Transcript`), so there is no frame where the newest line is hidden.
- `showButton = distanceFromBottom > 120.dp`, where the distance is
  `firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 120.dp.toPx()`.
- Button: DS §4.2 ScrollToLatestButton (`SmallFloatingActionButton`, `surfaceContainerHigh`,
  `arrow_downward`, end-aligned, 16 dp above the composer's top edge and above the approval
  banner when it shows), CD "Scroll to latest message", tooltip "Scroll to latest message".
  Enter/exit DS §6.
- Tap: if `firstVisibleItemIndex > 6`, `scrollToItem(3)` then `animateScrollToItem(0)`
  (voice jump-then-glide); sets `pinned = true`. Reduced motion → `scrollToItem(0)`.
- New user message (count grows) → scroll to 0 regardless of `pinned` (desktop
  `AutoScrollOnSend`).
- IME open/close: no correction needed (reverse layout keeps the bottom anchored, DS §5.7).
- Opening a session lands at the bottom (`pinned = true`), except when returning to a
  session whose scroll position was saved — back from a subagent session to its parent, or
  re-selecting a session in this process — where the per-session `SaveableStateHolder`
  (§1.1) restores the `LazyListState`.

### 4.5 States

| State | Indicator | Button |
| --- | --- | --- |
| Idle at bottom | hidden | hidden |
| Starting / awaiting first output | shown | per distance |
| Streaming, pinned | hidden (a message streams) | hidden |
| Streaming, user scrolled up | hidden | shown; content keeps arriving below without moving the viewport |
| Approval / question pending | hidden | per distance |
| Kernel down | hidden | per distance |

### 4.6 Acceptance (S25U)

- [ ] Send a prompt: "Thinking..." appears immediately at the bottom in the tool-row geometry; the first reasoning or tool row replaces it without the transcript shifting.
- [ ] With the "slow" mock (40 chunks, 300 ms): stay at the bottom → every chunk is visible without manual scrolling; drag up one screen → the viewport stays still while chunks arrive, the button appears; tap it → glides to the newest line and following resumes.
- [ ] Scroll up less than 24 dp: still pinned (streaming keeps following). Between 24 and 120 dp: no button but no following. Beyond 120 dp: button.
- [ ] From 50+ items up, tap the button: it jumps then glides in under 400 ms.
- [ ] Send a new message while scrolled up: the list jumps to the bottom.
- [ ] Open and close the IME at the bottom: the newest line stays visible; no jump.
- [ ] TalkBack: the button announces "Scroll to latest message"; developer option "Remove animations": button appears without scale, indicator label static.

---

## 5. Screen D: Image lightbox / carousel

### 5.1 Purpose

View an attachment or a generated image full size, zoom into it, page through the images of
the same message, and share it.

### 5.2 Desktop equivalent

`ChatImageLightbox` (`image-lightbox.tsx`, dialog label "Expanded attachment", backdrop
`bg-background/95`, Escape closes), `AgentImageLightboxContent` (close buttons "Close expanded
attachment" on the backdrop and "Close image viewer" on the X), `MessageImageCarousel`
(single image with previous/next and "1 / N"). Copied: labels, backdrop-tap closes, one image
per page, counter format. Translated: the carousel's arrows → `HorizontalPager` swipe (with
arrow buttons kept for TalkBack and hardware keyboards); pinch/double-tap zoom and predictive
back added; "Share" added *(Android)*.

**Host: an in-window overlay, not a `Dialog`.** The desktop lightbox is itself an
`absolute inset-0` overlay inside the chat pane, and a Compose `Dialog` is a separate
window: with default `DialogProperties` it does not draw under the system bars, its status
bar icon appearance is its own, `dismissOnBackPress` would bypass the predictive animation,
and the app's `SnackbarHost` would sit hidden behind it. So the lightbox is a composable
`ImageLightboxOverlay` placed in the app's root `Box` above the `NavHost`/pane scaffold
(`Modifier.fillMaxSize().zIndex(10f)`), so it covers the rail and list pane too:

- It consumes all pointer input outside its controls (a `pointerInput` on the scrim layer
  under the image that consumes every change), so nothing behind it reacts.
- It inherits edge-to-edge from the Activity: the scrim draws under the status and
  navigation bars; the top bar applies `WindowInsets.statusBars` + `displayCutout` padding,
  the arrows `WindowInsets.navigationBars`.
- System bar icons: a `DisposableEffect` sets
  `WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false` and
  `isAppearanceLightNavigationBars = false` while shown (light icons on the dark scrim),
  and restores the previous values in `onDispose`.
- Back: its own `PredictiveBackHandler` (enabled only while shown), composed after the
  screen's handlers so it wins (DS §5.8 item 3).
- Snackbars (share failure) use the app-wide `SnackbarHostState`; while the overlay is
  shown, a `SnackbarHost` inside the overlay (bottom, above `navigationBars`, 16 dp) renders
  them, so they appear above the scrim.
- `paneTitle` on the overlay root; focus moves into it on open and back to the thumbnail on
  close (§5.6).

DS §4.2 ImageLightbox still says `Dialog(usePlatformDefaultWidth = false)`; Q14.

### 5.3 Layout

```
┌────────────────────────────────────────────┐  scrim @ 95% (DS §4.2), status bar icons light
│ [✕]              2 / 5               [⇪]   │  transparent TopAppBar: close · counter · share
│                                            │
│                                            │
│          ┌──────────────────────┐          │
│          │                      │          │  image, ContentScale.Fit, max = pane
│          │        image         │          │  minus 16dp, shapes.large corners
│          │                      │          │
│          └──────────────────────┘          │
│ [‹]                                   [›]  │  arrow IconButtons (only when > 1 image;
│                                            │  hidden while zoomed; disabled at the ends)
└────────────────────────────────────────────┘
```

Tablet: identical, image bounded by the window; the overlay always covers the whole window
(including rail and list pane), because it is hosted above the `NavHost`.

### 5.4 Behaviour

- Entry: tap a thumbnail in a user bubble, an assistant carousel, or a tool output carousel.
  The pager holds **all images of that one message or tool row** in order; the start page is
  the tapped image.
- Counter: "N / M" (`titleMedium`, `onSurface` over the scrim), shown only when M > 1.
- Zoom: `Modifier.transformable` — pinch 1× to 5×, double-tap toggles 1× ↔ 2.5× around the
  tap point, pan clamped to the image bounds when zoomed; the pager's swipe is disabled while
  zoom > 1×. Zoom resets on page change.
- Chrome: tap on the image toggles the top bar and arrows (fade 150 ms); tap on the scrim
  outside the image closes (desktop backdrop). Swipe down on the image at 1× by more than
  96 dp closes *(Android)*.
- Share *(Android)*: decode the base64 to `cacheDir/share/<messageId>-<n>.<ext>`, share via
  `FileProvider` + `ACTION_SEND` (`type` = image media type), chooser title "Share image".
  CD "Share image", tooltip "Share image". Failure → snackbar "Couldn't share this image."
  *(Android)* in the overlay's own `SnackbarHost` (§5.2).
- Predictive back (DS §5.8 item 3): `PredictiveBackHandler` scales the content 1 → 0.9 and
  fades the scrim 95 % → 0 with progress; commit closes, cancel restores.
- Hardware keyboard: Esc closes; Left/Right page; `+`/`-` zoom.
- Closing returns focus to the thumbnail that opened it (DS §8.1.7).
- The lightbox closes itself when its session is switched away (desktop keys it by session).

### 5.5 States

| State | What shows |
| --- | --- |
| Loading (decoding full size) | the cached thumbnail scaled up + 24 dp `CircularProgressIndicator` centred |
| Loaded | image |
| Decode failure | `broken_image` 48 dp + "Generated image could not be rendered." (`bodyMedium`, `onSurface`), share disabled |
| Single image | no counter, no arrows, swipe does nothing |
| Offline / kernel down | unaffected (images are local data) |

Bindings: none at open time. Images come from the SessionStore (`message.images`,
`summary.outputMedia`, `assistant.media` base64). Full-size decode is bounded to the window
size × 2 (for zoom), max 4096 px on the long edge.

### 5.6 Accessibility

- Overlay root `paneTitle = "Expanded attachment"`; while it is shown the content behind it
  is removed from the accessibility tree (`Modifier.clearAndSetSemantics {}` on the
  `NavHost` container, applied only while the overlay is up), and focus moves to the close
  button on open.
- Close: CD "Close image viewer" (desktop X label); tooltip "Close". Scrim tap target has
  CD "Close expanded attachment" and is excluded from focus traversal
  (`backdropTabIndex` analogue).
- Image CD: user attachments "Attachment N", generated "Generated result N" (or `name`).
- Arrows: "Previous generated image" / "Next generated image" (desktop), for attachments
  "Previous attachment" / "Next attachment" *(Android)*; disabled at the ends.
- Page changes announce "N / M" politely.

### 5.7 Acceptance (S25U)

- [ ] Tap an attached image: lightbox opens over everything, the scrim continues under the status and navigation bars, status bar icons are light (also in light theme), counter hidden for one image; closing restores dark status bar icons in light theme.
- [ ] A message with 3 generated images: opens at the tapped one, "2 / 3", swipe and arrows page, arrows disabled at the ends.
- [ ] Pinch to 5×, pan, double-tap back to 1×; swipe paging is blocked while zoomed.
- [ ] Predictive back gesture: the image shrinks and the scrim fades with the finger; releasing closes, cancelling restores.
- [ ] Share opens the Android share sheet with the image; sending to Quick Share or Gmail works. Force a share failure (an image whose decode fails): the snackbar "Couldn't share this image." is visible above the scrim.
- [ ] TalkBack with the lightbox open: swiping never reaches the transcript behind it.
- [ ] Tap outside the image closes; TalkBack focus returns to the thumbnail.
- [ ] Tab S7+ landscape: the lightbox covers rail and list pane.

---

## 6. Screen E: Conversation header

### 6.1 Purpose

Identify the open session and its state at a glance (status dot, title), and hold its
session-level actions: rename, pin, fork, details, export, delete, new session, the
diff-stats entry to Changes and the agents tally entry to the roster.

### 6.2 Desktop equivalent

`AgentHeader` (`agent-header.tsx`) on `AgentConversationHeader`, with `SessionStatus`,
`AgentActivityStatus`, `SubagentSessionBadge`; session overview hover card
(`getSessionOverviewItems` in `agent-sidebar.tsx`); delete `AlertDialog`. Copied: title
fallback "New Session", title normalisation, editor commit/cancel rules, diff chip rule
(shown only when additions ≠ 0) and its label, agents tally buckets and
`describeAgentActivity`, subagent badge copy, "Delete session" / "Deleting...". Translated:
`MoreHorizontal` → `more_vert` with a fuller M3 overflow menu (DS §4.2 ConversationHeader
lists its items), hover card → "Session details" bottom sheet, popover roster → Agents roster
sheet (agents-roster spec). The header is hidden on Home, as on the desktop.

### 6.3 Layout

```
Compact (412dp), TopAppBar 64dp, container surface, hairline on scroll
┌────────────────────────────────────────────┐
│ [≡]  ● Fix the login crash…  [🤖3 ⟳1] [+12 -3] [+] [⋮] │
└────────────────────────────────────────────┘
 nav   dot+title (tap: details)   agents   diff  new  overflow

Rename mode (title slot becomes a field; actions hidden except a check)
┌────────────────────────────────────────────┐
│ [✕]  [ Fix the login crash____________ ]  [✓] │
└────────────────────────────────────────────┘

Expanded (detail pane)
┌──────────────────────────────────────────────────────────────┐
│ [⇤]  ● Fix the login crash             [🤖 3 ⟳1 ✓2] [+12 -3] [+] [⋮] │
└──────────────────────────────────────────────────────────────┘
```

- `navigationIcon`: compact `menu` "Open sessions"; medium none (rail); expanded
  `left_panel_close` "Collapse sidebar" / `left_panel_open` "Expand sidebar" (desktop
  "Expand sidebar"). In rename mode it is `close` "Cancel rename".
- Title slot: `Row` of `SessionStatusDot` (24 dp box, DS §4.2) + title `titleMedium`
  1 line ellipsis. The row is one touch target (min 48 dp): tap → Session details sheet;
  long-press → rename mode. CD "<title>, <status label>"; `onClickLabel` "Session details",
  `onLongClickLabel` "Rename". **Deviation, kept on purpose:** the desktop's title click
  opens the inline editor (`agent-header.tsx`) and has no details surface on the title; on
  a phone the title is the natural tap target for the details the desktop shows in a hover
  card, so tap → details. Long-press is only a **shortcut**: "Rename" is always in the
  overflow menu and in the title row's TalkBack custom actions (§6.10), so no path depends
  on discovering the long-press.
- Actions, in order: agents chip, diff chip, "New session" (`add`) or the subagent back chip,
  overflow `more_vert` "Session actions".
- Width rule (compact): the title keeps at least 96 dp. If it would get less, the agents chip
  moves into the overflow menu as "Agents · <describeAgentActivity>"; if still less, the diff
  chip moves too as "Changes · +N -N". Implement with a custom `Layout` measuring actions
  first. The overflow and New session buttons never move.

### 6.4 Elements

| Element | Spec |
| --- | --- |
| Status dot | DS §1.6 `SessionVisual.of(status, flags)` — the only colour source. Neutral states draw nothing but keep the 24 dp box |
| Title | `normalizeTitle(session.metadata.title)` (= `formatDisplayUserInput`, first line for display) or "New Session". While Starting on a brand-new session: the first line of the prompt |
| Agents chip | Hidden when `activity.total == 0` (desktop). `AssistChip` (32 dp, `surfaceContainerHighest`), leading `smart_toy` 16 dp, then `ClineMono.small`: total in `onSurface`; if running > 0: 12 dp spinner + count; completed > 0: `check` + count in `success.color`; failed > 0: `error` + count in `error`; cancelled+unresolved > 0: `schedule` + count. CD = `describeAgentActivity` ("3 agents: 1 running, 2 completed"). Tap → Agents roster sheet (agents-roster spec: "Agents", "Loading agents...", "Waiting for the first agent to report in...", "No agent details were recorded for this session.", "Untitled task", "Could not refresh — showing the last known agents.") |
| Diff chip | Shown only when `additions != 0` (desktop). `AssistChip`, label `+N` in `success.color` and `-N` in `error`, `ClineMono.small`. CD "Open diff: N additions, N deletions". Tap → Changes (supporting pane ≥ 1200 dp, pushed route otherwise; changes spec). Source: `session-diff.ts` port over the tool messages (FM "Diff view"), recomputed when tool messages change |
| New session | `IconButton` `add`, CD "New session", tooltip "Start a new session" → Home (§2.9) |
| Subagent back chip | Replaces New session when the session's `metadata.parentSessionId` is set. `AssistChip`, leading icons `u_turn_left` 16 dp then `smart_toy` 16 dp, 4 dp apart (desktop `CornerUpLeft` + `Bot`, `agent-header.tsx:318-320`), label "Main Agent Session" (max 160 dp, ellipsis). CD/tooltip "Back to the main agent session: <parent title>" or "Back to the main agent session". Tap → the parent: `popBackStack()` if the previous entry is `Chat(parentId)`; else, when the parent is a top-level session, pop to the start entry and select it (§2.9); else push `Chat(parentId)` |
| Overflow | `DropdownMenu` (DS §4.1 #15), items: "Rename" (`edit`), "Pin" / "Unpin" (`push_pin`), "Fork" (`fork_right`), "Details" (`info`), "Export" (`share`, *(Android)* CLI feature), divider, "Delete session" (`delete`, error colours). Pending labels: "Renaming...", "Forking...", "Deleting..." with a 16 dp spinner, all items disabled while one is pending. "Fork" disabled when busy (desktop `forkDisabled`) |

### 6.5 Session details sheet

`ModalBottomSheet`, title = first line of the normalised title (`getSessionOverviewTitle`),
then two-column rows (DS §4.1 #13: label `bodyMedium` `onSurfaceVariant`, value
`ClineMono.small` `onSurface`, 40 dp visible / 48 dp targets). Rows are omitted when their
value is missing (desktop `filter(Boolean)`):

| Label | Value source |
| --- | --- |
| "Schedule" | `metadata.scheduleId` → `schedule.get` name (schedules spec) |
| "Run" | schedule run number when present |
| "Workspace" | basename of `session.workspaceRoot` (`basenamePath`: "Chat" for the kernel's chat workspace, "~" for the Termux home); long-press copies the full path |
| "Branch" | `git -C <ws> branch --show-current` (cached from §2.5) |
| "Provider" | `metadata.provider` |
| "Model" | `metadata.model` |
| "Tokens" | `formatTokenCount(aggregateUsage.inputTokens, aggregateUsage.outputTokens)`, the sum of both: "950", "1.2k" (1 000–9 999 keep one decimal), "12k" (from 10 000 the decimal is dropped: `toFixed(total >= 10000 ? 0 : 1)`, `use-session-history.ts:356-358`) |
| "Cost" | `formatCostUsd(metadata.totalCost ?: aggregateUsage.totalCost)` ("$0.0042", "$0.123", "$1.23") |
| "Source" | `metadata.source` ("android", "cli", …) |
| "Session ID" *(Android)* | `sessionId`, long-press copies |

Long-press any value copies it; snackbar on API < 33 "Copied" *(Android)*. Every row also
exposes a TalkBack custom action "Copy <label>" (e.g. "Copy Session ID"); the Workspace row's
action is "Copy full path" and copies the full path, not the basename. Loading: a
`LinearProgressIndicator` under the title until `session.get {includeSnapshot:true}` answers.
Opened from the title tap, overflow "Details", and the drawer row menu "Details".

### 6.6 Bindings

| Action | Kernel API |
| --- | --- |
| Status, title | `session.updated {session, snapshot}`; `session.get {sessionId, includeSnapshot:true}` |
| Rename | `session.update {sessionId, title, metadata: {...current.metadata, title}}` — **spread the current metadata** because `session.update {metadata}` replaces it wholesale (FX `04`); `current` is the latest `session.updated`/`session.get` record. Reply `{updated, session, snapshot}`; `ok:false` → keep the old title, snackbar "Rename failed: <error.message>" (desktop toast title "Rename failed" with the error as its description, `page.tsx:2451-2457`, joined into one snackbar line) |
| Pin / Unpin | `session.update {sessionId, metadata: {...current.metadata, pinned: true|false}}` (desktop `use-session-history.ts:1580`) |
| Fork | §3.4.2 |
| Export *(Android)* | RUN_COMMAND `cline history export <id> -o /data/data/com.termux/files/home/storage/downloads/cline-<id>.html` (FM "Session export"); exit 0 → snackbar "Saved to Download/cline-<id>.html" *(Android)*; storage not set up (`~/storage` missing → non-zero exit naming the path) → `AlertDialog` "Allow storage access" / "Run termux-setup-storage in Termux, then try again." / "Open Termux" / "Cancel" *(Android)*; other failure → snackbar "Couldn't export this session: <first stderr line>" *(Android)*. See Q4 |
| Delete | confirm (§6.7) → `session.delete {sessionId}`; reply `deleted:false` is **not** failure (FX): confirm with `session.get` → `session_not_found` = deleted. Then navigate Home. Any other error → dialog stays with `bodySmall` `error` text "Couldn't delete this session: <message>" *(Android)* |
| Agents tally | `buildSessionAgentActivity(messages, sessionActive = busy)` (spawn/team tool rows) merged with `session.list {rootOnly:false}` children whose `metadata.parentSessionId == sessionId` (FM "Agents roster") and `team.progress` |
| Diff stats | `buildSessionDiffState(toolMessages).summary` (port of `session-diff.ts`) |

### 6.7 Dialogs

- Delete: DS §4.1 #16 `AlertDialog`: title "Delete session?", text "This removes \"<title>\"
  from local history." — the sidebar row's wording (`agent-sidebar.tsx:1294-1298`), used for
  **one** dialog shared by the header overflow and the drawer row menu. The desktop header
  has its own variant, "Delete Session?" / "This session will be removed from local
  history." (`page.tsx:2724-2728`); the app deliberately drops it so both entry points say
  the same thing and name the session. Buttons "Cancel" and "Delete" (`error` text button); confirming shows
  "Deleting..." with a spinner, both disabled, dismiss blocked (desktop). Deleting a busy
  session: `run.abort` first, then delete (the desktop disables nothing here; the Hub would
  otherwise keep running a turn for a deleted record).
- Rename mode (inline, desktop `isEditingTitle`): `OutlinedTextField` single line,
  `imeAction = Done`, pre-filled and fully selected, autofocus with IME. Commit on Done, on the
  `check` button ("Save title"), or on focus loss (desktop `onBlur`). Cancel on back, the
  `close` button ("Cancel rename") or Esc. Unchanged text → exit without a command. Blank text
  → exit without a command *(Android: the desktop would clear the custom title; the app keeps
  it, see Q7)*. While saving: field disabled, title shows the new text optimistically,
  reverted on failure.

### 6.8 States

| State | Header |
| --- | --- |
| Starting (new) | Starting dot (pulsing), prompt-derived title, actions: New session + overflow (Rename/Pin/Fork/Delete disabled until the id exists) |
| Running | Running dot; Fork disabled |
| Approval / question pending | "Approval required" / "Question asked" dot |
| Idle / Completed / Cancelled / Failed | per DS §1.6 |
| Unread | never in the header (the open session is read) |
| Kernel down / offline | title and dot from cache; every command action disabled with a tooltip "Start kernel to continue." *(Android)*; Details still opens (cached values) |
| Missing session | header shows "New Session", no actions except New session |
| Subagent session | back chip instead of New session |

### 6.9 Navigation and back

- The header belongs to the `Chat` entry; it has no back arrow on top-level sessions (the
  drawer is the navigator, DS §5.2). A subagent session opened from the roster or a parent's
  tool row is **pushed** as `Chat(childId)`, so system back returns to the parent; the back
  chip does the same.
- Back order (DS §5.8): overflow menu → details sheet → rename mode → drawer → pane →
  (pushed subagent entry) pop to the parent → (selected top-level session) **back to Home**
  (§2.9, shared-axis pop, seekable) → (Home) leave the app.
- After delete: Home (selection `null`). After fork: the new session replaces the selection
  (back → Home). After "Export": stays.

### 6.10 Accessibility

- Title row: one focusable with the CD and click/long-click labels in §6.3; custom actions
  "Rename", "Session details", "Pin"/"Unpin", "Fork", "Delete session".
- Session details rows: custom action "Copy <label>" each; Workspace "Copy full path"
  (§6.5).
- Chips: CDs per §6.4; spinners inside chips are decorative.
- Rename field: label "Session title" *(Android)*; the save/cancel buttons have CDs "Save
  title" / "Cancel rename" (DS §4.1 #11 strings).
- Opening the details sheet moves focus to its title; closing returns it to the title row.

### 6.11 Acceptance (S25U)

- [ ] Open a running session: green dot, title from the first prompt, Fork disabled in the overflow.
- [ ] While an approval is pending the dot shows the approval colour and TalkBack says "Session status: approval required".
- [ ] Long-press the title: rename field with the text selected and the IME up; Done saves; the drawer row updates; `session.get` shows `title` changed and **`usage`, `totalCost`, `mode` still present** in metadata (FX wholesale-replace trap).
- [ ] Rename then press back: cancelled, old title kept. Rename to blank: nothing changes.
- [ ] Pin from the overflow: the session moves to "Pinned" in the drawer; Unpin reverses.
- [ ] Edit a file through the agent: the diff chip appears with the right `+N -N`; tapping opens Changes (full-screen on the S25U, side pane on the Tab S7+ landscape).
- [ ] Ask for a subagent ("use a subagent to list files"): the agents chip appears with a spinner, then a check; its CD reads like "1 agent: 1 completed"; tapping opens the roster; opening the child shows "Main Agent Session", and back returns to the parent.
- [ ] Tap the title: Session details shows Workspace, Provider, Model, Tokens, Cost, Source and Session ID; long-press a value copies it. Tokens reads like "1.2k" below 10 000 and "12k" above.
- [ ] Without using the title tap or long-press, reach Session details and Rename from the overflow only; both work.
- [ ] TalkBack in Session details: the Session ID row's actions include "Copy Session ID"; the Workspace row's "Copy full path" puts the full path on the clipboard.
- [ ] Delete from the overflow: "Delete session?" with the title, "Deleting..." while pending, then Home; the session is gone from the drawer and `cline history` in Termux. The drawer row menu's Delete shows the identical dialog.
- [ ] Rename with a forced `ok:false` (e.g. the Hub stopped between opening the field and pressing Done): the title reverts and the snackbar reads "Rename failed: …" above the keyboard.
- [ ] Export: with `termux-setup-storage` done, the file appears in Download; without it, the "Allow storage access" dialog.
- [ ] Narrow width (S25U split screen, ~300 dp): the title keeps ≥ 96 dp; the agents and diff chips move into the overflow with their labels.
- [ ] Tab S7+ expanded: `left_panel_close` collapses the list pane and the icon flips to `left_panel_open`.

---

## 7. Recovery matrix (KC) — what this group surfaces

| Condition (KC / FX) | Detection | Surface in this group |
| --- | --- | --- |
| Upgrade 401 (stale token) | `HubClient` handshake 401 | silent re-read of `production.json` once; transport line "Reconnecting chat..." meanwhile; if still 401 → "Chat backend unavailable" |
| ECONNREFUSED on 25463 | `/health` refused | Home kernel notice / transport line; composer "Start kernel" → `cline hub start` (+ `termux-wake-lock`) via `KernelCoordinator.ensureConnected(startIfDown = true)` |
| RUN_COMMAND permission / allow-external-apps error | result `err != -1` + `errmsg` | navigate to Kernel setup with the fix; return restores the screen |
| Termux not installed | `<queries>` lookup fails | Kernel setup "Install Termux" (before Home) |
| `hub_draining` | reply error / `hub.drain_changed` | "Cline Hub is updating..." transport line; sends wait for `draining:false` |
| `session_not_found` on send | `send_input` reply | auto-resume once (§3.12), then resend |
| `session_not_found` on open | `session.get` | "This session no longer exists." empty state |
| Hub restarted under one of the app's own turns (`session.send_input` = `run.start`; the Hub never publishes `run.interrupted` for these) | open `TurnRecord` + (a) `/version` pid/startedAt changed on reconnect, evaluated after the replay drains to `hub.status.eventLog.lastSequence`, or (b) the session's shutdown projection in replay/live (`run.completed {reason:"disposed"}`, or `session.updated` with `snapshot.status` `cancelled`/`completed` + `endedAt`), §1.3.4 | interrupted error row + "Resend" (resume via `buildSessionCreate`, then send) |
| `run.interrupted {runId, error, reason:"hub_restart"}` (only `run.enqueue` runs, e.g. queued from elsewhere) | event | the same row keyed `i:<runId>`; "Resend" only when a prompt is recoverable |
| Approval re-issued on subscribe | duplicate `approvalId` | count deduped; no duplicate sheet (approval spec) |
| `capability.requested` cancelled after reconnect | `capability.resolved` / no answer | question count drops; the turn continues; the ask-question spec shows its snackbar |
| `run.completed` with reason ≠ `completed` (e.g. `disposed` on shutdown, FX) | event | not treated as success: status Idle, no fold of an answerless tail, no "completed" notification |
| Shutdown `session.updated` pairs in replay | replay after restart | applied idempotently by `sequence`; with no open turn, status becomes Completed/Cancelled per snapshot; with an open turn they mark it interrupted (row above) |
| Same-clientId replacement trap (FX `04`) | `client_not_found` / `client_already_registered` | HubClient opens a new socket only after the old one closed (KC); this group just shows "Reconnecting chat..." |
| RUN_COMMAND never returns (hub start hung) | 60 s timeout | Kernel notice switches to "Cline Hub @unknown" / "Cline Hub is not connected." and the Kernel status sheet offers diagnostics (`cline doctor --json`) |
| App killed during a turn | cold start | SessionStore rebuilt from `session.messages` + replay from the **persisted global cursor** (the only persisted cursor, §1.1) + the open `TurnRecord`s from DataStore (§1.3.4); the indicator shows if the replay says Running; an interruption is detected as above |

---

## 8. Strings (all strings of this group; `res/values/strings.xml`)

Desktop verbatim unless marked *(Android)*. Plurals marked `[p]`.

| Key | Text |
| --- | --- |
| `home_heading_a11y` | What would you like to build? |
| `home_heading_prefix` | What would you like to %1$s? *(verb parts: build, create, fix, know)* |
| `home_workspace_default` | Home *(Android, decided A22: no folder chosen)* |
| `home_workspace_home` | ~ *(Android)* |
| `home_workspace_cd` | Workspace: %1$s *(Android)* |
| `home_branch_cd` | Branch: %1$s *(Android)* |
| `branch_sheet_title` | Branch *(Android)* |
| `branch_search_placeholder` | Search branches |
| `branch_loading` | Loading... |
| `branch_empty` | No branches found |
| `branch_switch_error` | Couldn't switch to %1$s: %2$s *(Android)* |
| `branch_busy` | Branch switching is unavailable while an agent is running in this workspace. *(Android)* |
| `branch_capped` | Showing the 500 most recent branches *(Android)* |
| `setup_notice_title` | Connect a model to start building |
| `setup_notice_body` | Sign in with Cline or add an API key — it takes under a minute. |
| `setup_notice_connect` | Connect a model |
| `setup_notice_settings` | Model settings |
| `kernel_connecting` | Connecting... |
| `kernel_reconnecting` | Reconnecting... |
| `kernel_hub_port` | Cline Hub @%1$s |
| `kernel_hub_unknown` | Cline Hub @unknown |
| `kernel_not_connected` | Cline Hub is not connected. |
| `kernel_termux_asleep` | Termux asleep |
| `kernel_start_to_continue` | Start kernel to continue. |
| `kernel_status_action` | Kernel status *(Android)* |
| `chat_thinking` | Thinking... |
| `chat_switching` | Switching session... *(progress indicator CD + status text)* |
| `chat_loading` | Loading session... *(progress indicator CD + status text)* |
| `chat_pane_title` | Agent conversation |
| `chat_transport_connecting` | Connecting chat... |
| `chat_transport_reconnecting` | Reconnecting chat... |
| `chat_transport_unavailable` | Chat backend unavailable |
| `chat_transport_draining` | Cline Hub is updating... *(Android)* |
| `chat_status_working` | Agent is working… *(Android, live region)* |
| `chat_status_failed` | Run failed: %1$s *(Android, live region)* |
| `chat_start_error` | Couldn't start the session: %1$s *(Android)* |
| `chat_resume_error` | Couldn't resume this session: %1$s *(Android)* |
| `chat_history_error` | Couldn't load this session: %1$s *(Android)* |
| `chat_retry` | Retry |
| `chat_missing_title` | This session no longer exists. *(Android)* |
| `chat_missing_body` | It may have been deleted from another device or the Termux CLI. *(Android)* |
| `chat_new_session` | New session |
| `chat_not_sent` | Message not sent. Start kernel to continue. *(Android)* |
| `msg_copy_user` / `msg_copied_user` | Copy user message / Copied user message |
| `msg_copy_tooltip` / `msg_copied_tooltip` | Copy message / Copied |
| `msg_edit_user` / `msg_edit_tooltip` | Edit user message / Edit message and restart from this point |
| `msg_restore` | Restore checkpoint |
| `msg_copy_assistant` / `msg_copied_assistant` | Copy assistant message / Copied assistant message |
| `msg_copy_raw_tooltip` | Copy raw assistant output |
| `msg_select_text` | Select text *(Android)* |
| `message_details_title` | Message details *(Android, A11.9: the terminal's Brief / Verbose setting in Settings → App)* |
| `message_details_brief` / `message_details_brief_supporting` | Brief / Copy and time under each answer *(Android)* |
| `message_details_verbose` / `message_details_verbose_supporting` | Verbose / Also shows each turn's tokens, cost and duration *(Android)* |
| `message_details_stats_in` / `message_details_stats_out` | %1$s in / %1$s out *(Android, the Verbose line on a turn's final answer)* |
| `message_details_stats_seconds` / `message_details_stats_minutes` | %1$s s / %1$s min %2$s s *(Android)* |
| `msg_show_actions` | Show message actions *(Android)* |
| `msg_copy_failed_title` / `msg_copy_failed_body` | Copy failed / The message could not be copied to the clipboard. |
| `msg_restore_error` | Could not restore checkpoint. |
| `msg_edit_error` | Could not restart from this message. |
| `msg_fork_error` | Could not fork session. |
| `msg_fork_empty` | Cannot fork an empty session. |
| `msg_fork_title_suffix` | (fork) *(CLI; appended with a leading space)* |
| `fork_title_untitled` | Untitled *(CLI `deriveForkSessionTitle` last fallback)* |
| `msg_attachment_expand` / `msg_attachment_alt` | Expand attachment %1$d / Attachment %1$d |
| `msg_generated_expand` / `msg_generated_alt` | Expand generated image %1$d / Generated result %1$d |
| `msg_generated_image_default` | Generated image |
| `msg_generated_file` | Generated file (%1$s) |
| `media_open` | Open *(Android)* |
| `media_no_app` | No app can open this file. *(Android)* |
| `media_remote` | Generated %1$s requires a trusted remote-media resolver. |
| `media_artifact` | Generated %1$s is stored as an artifact (%2$s). |
| `media_unrenderable` | Generated %1$s could not be rendered. |
| `md_link_title` / `md_link_body` | Open external link? / You are about to leave Cline and visit this address. |
| `md_link_cancel` / `md_link_open` | Cancel / Open link |
| `md_image_blocked` / `md_image_blocked_alt` | External image blocked for privacy / External image blocked for privacy: %1$s |
| `md_copy_code` | Copy code *(Android)* |
| `link_no_app` | No app can open this link. *(Android)* |
| `run_failed_prefix` | The run failed: %1$s |
| `run_failed_empty` | The run failed before a response was produced. |
| `cred_hint_cline` | Sign in to Cline again in Settings → Account, then try again. |
| `cred_hint_provider` | Check your model connection in Settings → Providers (or sign in with Cline), then try again. |
| `cred_hint_unknown` | Sign in again using your provider's authentication method, then try again. |
| `cred_hint_cli` | Sign in again with the `%1$s` CLI in a terminal, then try again. |
| `cred_action_cline` / `cred_action_providers` | Sign in to Cline / Open API providers |
| `run_interrupted` | This run was interrupted because the Cline Hub restarted. *(Android)* |
| `run_resend` | Resend *(Android)* |
| `chat_notice_mistake_stopped_repeated` | Stopped after repeated mistakes *(Android, A17)* |
| `chat_notice_mistake_timed_out` | `[p]` Stopped: the question was not answered within %d minute / Stopped: the question was not answered within %d minutes *(Android, A17)* |
| `chat_tool_label_interrupted` / `chat_tool_label_interrupted_named` | Interrupted / Interrupted: %1$s *(Android, A21)* |
| `chat_tool_status_interrupted` | interrupted *(Android, A21: the row's TalkBack status word)* |
| `tool_output` | Output |
| `tool_input` | Input |
| `tool_command_output_cd` | Command output |
| `tool_proceed` | Proceed while running |
| `tool_proceed_error` | Could not detach the running command. |
| `tool_proceed_finished` | The command finished before it could be detached. |
| `tool_output_truncated` | [output truncated] |
| `tool_live_truncated` | [Earlier command output truncated] |
| `tool_submit_running` / `tool_submit_failed` / `tool_submit_done` | Completing scheduled task / Scheduled task failed / Scheduled task completed |
| `reasoning_thinking` / `reasoning_thought` / `reasoning_redacted` | Thinking / Thought for %1$ds / [redacted] |
| `work_label_*` | Worked for %1$s and made %2$s · Worked for %1$s · Made %1$s · Worked; `[p]` %d tool call / %d tool calls |
| `scroll_latest` | Scroll to latest message |
| `lightbox_title` | Expanded attachment |
| `lightbox_close` / `lightbox_close_tooltip` | Close image viewer / Close |
| `lightbox_backdrop` | Close expanded attachment |
| `lightbox_prev_generated` / `lightbox_next_generated` | Previous generated image / Next generated image |
| `lightbox_prev_attachment` / `lightbox_next_attachment` | Previous attachment / Next attachment *(Android)* |
| `lightbox_counter` | %1$d / %2$d |
| `lightbox_share` / `lightbox_share_error` | Share image / Couldn't share this image. *(Android)* |
| `header_title_fallback` | New Session |
| `header_status_cd` | %1$s, %2$s *(title, status label)* |
| `header_details` | Session details *(Android)* |
| `header_rename` / `header_renaming` | Rename / Renaming... |
| `header_pin` / `header_unpin` | Pin / Unpin |
| `header_fork` / `header_forking` | Fork / Forking... |
| `header_details_item` | Details *(Android)* |
| `header_export` | Export *(Android)* |
| `header_delete` / `header_deleting` | Delete session / Deleting... |
| `header_actions_cd` | Session actions |
| `header_new_cd` / `header_new_tooltip` | New session / Start a new session |
| `header_diff_cd` | Open diff: %1$d additions, %2$d deletions |
| `header_main_agent` | Main Agent Session |
| `header_main_agent_hint` / `header_main_agent_hint_title` | Back to the main agent session / Back to the main agent session: %1$s |
| `header_agents_overflow` | Agents · %1$s *(Android)* |
| `header_changes_overflow` | Changes · +%1$d -%2$d *(Android)* |
| `header_sidebar_expand` / `header_sidebar_collapse` | Expand sidebar / Collapse sidebar *(collapse: Android)* |
| `header_open_sessions` | Open sessions |
| `header_kernel_disabled` | Start kernel to continue. |
| `agents_describe_*` | %1$d agent(s) `[p]`; parts "%d running", "%d completed", "%d failed", "%d cancelled", "%d with no recorded outcome"; joined "%1$s: %2$s" |
| `rename_label` / `rename_save` / `rename_cancel` | Session title *(Android)* / Save title / Cancel rename |
| `rename_error` | Rename failed: %1$s *(desktop toast title + description in one line)* |
| `delete_title` / `delete_body` | Delete session? / This removes "%1$s" from local history. *(sidebar wording for both entry points; header variant "Delete Session?" not used, §6.7)* |
| `delete_cancel` / `delete_confirm` | Cancel / Delete |
| `delete_error` | Couldn't delete this session: %1$s *(Android)* |
| `details_*` | Schedule · Run · Workspace · Branch · Provider · Model · Tokens · Cost · Source · Session ID *(last: Android)* |
| `copied` | Copied *(Android, API < 33 only)* |
| `details_copy_action` / `details_copy_path` | Copy %1$s / Copy full path *(Android, TalkBack custom actions)* |
| `export_saved` | Saved to Download/%1$s *(Android)* |
| `export_storage_title` / `export_storage_body` | Allow storage access / Run termux-setup-storage in Termux, then try again. *(Android)* |
| `export_open_termux` | Open Termux *(Android)* |
| `export_error` | Couldn't export this session: %1$s *(Android)* |

---

## 9. Consolidated acceptance run (S25U, then Tab S7+)

Preconditions: Cline Termux port ≥ `v3.0.68-termux.2` installed, Termux in the foreground
once to grant RUN_COMMAND, a real provider configured, the app installed with
`./scripts/install.sh` outside any port release window (CLAUDE.md hard rule).

1. Run §2.11, §3.16, §4.6, §5.7, §6.11 in order on the S25U portrait, light theme, dynamic
   colour on.
2. Repeat §3.16 items 1–3 and §6.11 items 1, 3, 9 in dark theme with dynamic colour off
   (brand scheme).
3. Repeat §2.11 item 6 and §4.6 item 2 in landscape and in split screen.
4. TalkBack walk-through: Home → send → tool rows → (approval sheet, other spec) → answer →
   header details → delete; every control announced with its desktop label, every status in
   words (DS §11).
5. Accessibility Scanner on Home, a populated thread, the lightbox and the header menu: no
   touch-target or contrast findings.
6. Tab S7+: §2.11 item 7, §3.16 last item, §5.7 last item, §6.11 last item, portrait and
   landscape; Book Cover keyboard: Enter sends, Esc stops, Ctrl+P opens search, Esc closes
   the lightbox.
7. Screenshots of each screen state into `screenshots/home-chat/` with `./scripts/screenshot.sh`.

---

## 10. Open questions

- **Q1 (assistant.media shape on the device).** `assistant.media {media}` and history `media`
  blocks were not recorded (FX "Not covered: images"). Record one image-generating turn on the
  workstation Hub before building §3.4.8 and §5; confirm `source.type` is `base64`.
- **Q2 (editor / apply_patch payloads).** FX did not record `editor`, `apply_patch`,
  `read_files`, `fetch_web_content`; the diff rows and diff chip depend on the port of
  `buildToolSummary` handling the Hub's exact shapes. Add those scenarios to
  `tools/hub-fixtures/record.ts`.
- **Q3 (chat workspace). Resolved by A22 (2026-10-08).** The daemon creates
  `~/.cline/data/workspaces/chat` only when it imports sessions (`ensureChatWorkspace`), and the
  app sent that path without creating it (trial row 17). For a session with no folder chosen the
  app now sends the Termux home (§2.7 step 3) and creates nothing.
- **Q4 (export destination).** `cline history export -o ~/storage/downloads/...` needs
  `termux-setup-storage`; the app cannot open a file it does not own in Download on API 33+.
  Owner decision: accept "Saved to Download/…" without an "Open" action, or stream the HTML
  through stdout (100 KB RUN_COMMAND cap makes that unreliable).
- **Q5 (steering messages on the Hub).** The desktop hides `[SYSTEM]` steering messages using
  `meta.userRunSpan == 0`, which `session.messages` does not carry. The proposed rule (raw text
  starts with `[SYSTEM]` and is not wrapped in `<user_input>`) needs one recorded scheduled run
  to confirm.
- **Q6 (subagent events in the parent stream).** Whether subagent/teammate `tool.*` and
  `assistant.*` events reach the parent session's subscription (and carry `agent.kind`) was
  not traced; §1.3.1 drops them from the transcript. Record a `spawn_agent` turn.
- **Q7 (blank rename).** Desktop clears a custom title when the field is emptied; the app keeps
  the old title on blank input. Confirm with the owner; if clearing is wanted, define what
  `session.update` must send to remove `metadata.title`.
- **Q8 (design-system wording).** DS §8.1.11 says message bodies are inside a
  `SelectionContainer`, which conflicts with DS §4.1 #15's long-press menu on messages. This
  spec resolves it with "Select text" (§3.14); update DS §8.1.11 to say "selectable on
  request" so the two sections agree.
- **Q9 (carousel form).** The desktop carousel shows one image with arrows and "1 / N"; DS §4.2
  specifies a `LazyRow` of 160 dp thumbnails. This spec follows DS and moves the counter into
  the lightbox; confirm the owner is happy with that deviation.
- **Q10 (hero heading). Resolved:** follow the current desktop. Home shows only the bot and
  keeps "What would you like to build?" for screen readers; DS §4.2 and §6 were updated
  (the visible cycling `AgentHeroHeading` was written against a desktop before upstream
  35583ee8b).
- **Q11 (lightbox close label).** DS §4.2 lists the lightbox close CD as "Close"; the desktop
  aria-label is "Close image viewer". This spec uses the desktop text for the CD and "Close" as
  the tooltip; align DS.
- **Q12 (mid-run Hub restart, unrecorded).** FX `05` restarted the Hub with idle sessions
  only. Record `tools/hub-fixtures` scenario `06`: start a `sleep 30` turn via
  `session.send_input`, `cline hub stop && cline hub start`, reconnect, replay. Confirm that
  the replay carries the shutdown projection for the running session (§1.3.4 (b)) and that no
  terminal `run.*` with `reason:"completed"` is published for the cut-off turn, and whether
  the turn's user message is persisted in `session.messages` (Resend's fallback prompt
  source). Until then (a) alone is sufficient for the detection.
- **Q13 (`metadata.source` and the `tasks` tool) — decision recorded.** Any `source` other
  than `cli`/`vscode` makes the Hub advertise the `tasks` tool and the Agenda prompt section
  (FX, FM line 373), while FM "Agenda tasks" omits the Agenda UI. Decision for this spec: new
  app sessions use `source:"android"` and accept the `tasks` tool; its calls render as
  ordinary tool rows (the port's "other" kind, label from `buildToolPresentation`), so nothing
  the model does is invisible. Resume, auto-resume and Resend **keep the persisted `source`**
  (§1.3.6), so a TUI-created session keeps its `cli` tool set mid-history. Owner to confirm,
  or to choose `source:"cli"` for app sessions if the Agenda prompt section proves noisy.
- **Q14 (design-system alignment found while reviewing this spec).** DS §5.2 route
  `chat/{sessionId?}` → `@Serializable data class Chat(val sessionId: String? = null)`
  (optional path arguments do not exist) and its navigation model (start entry + selection,
  §2.9); DS §4.2 SessionNotice cites `chat-messages.tsx`, which never renders
  `session.notice` (the row is an Android design, §3.4.6); DS §4.2 ImageLightbox says
  `Dialog(usePlatformDefaultWidth = false)` → in-window overlay (§5.2); DS's generic 2 s
  copy feedback vs the desktop's 1.6 s for message copy (§3.4.1). Update DS to match.

Review notes (findings checked and kept as written): none — every reviewer finding of
2026-10-03 was verified against the sources and applied; the two where the reviewer offered
a choice record it inline (§3.9 spinner: deviation stated with its reason; §6.3 title tap:
desktop rename-on-click not copied, reason stated).
