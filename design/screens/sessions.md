# Screen spec: Sessions

Status: design-phase contract, 2026-10-03; revised the same day after a three-reviewer pass
(every finding verified against the sources; the one kept as-is carries a "reviewed: kept"
note). Application id `io.github.ichouchiang.clinetermux` (Kotlin package `dev.cline.termux`).
Builds on `design/design-system.md` (cited as **DS §n**) and never redefines anything
in it. Kernel facts come from `docs/02-kernel-contract.md`, `docs/03-feature-matrix.md`
(including its "Corrections from recorded traffic" section) and the recorded traffic in
`docs/08-hub-fixtures.md` (cited as **fixture NN**).

Screens and flows in this file:

| # | Surface | Kind |
| --- | --- | --- |
| S1 | Sessions drawer (compact), session list pane (expanded), navigation rail entry points (medium/expanded) | shell component |
| S2 | Sessions screen (full list: sort, filters, rows with tokens/cost/time, row actions) | pushed route `sessions` |
| S3 | Session search | overlay (full-screen search bar on compact/medium, docked search bar on expanded) |
| F1 | Open and restore (resume) a history session | flow |
| F2 | Rename | flow |
| F3 | Pin / Unpin | flow |
| F4 | Fork | flow |
| F5 | Delete (with confirm) | flow |
| F6 | Session details sheet | sheet (row action) |

Out of scope here (owned by other specs, referenced only): the chat transcript and
the conversation header (`design/screens/home-chat.md`, "chat spec"; its menu reuses
F2-F6 defined here), the composer, approvals, questions and the notification deep-link
contract (`design/screens/composer-approvals.md` §0.6), Kernel setup and the Kernel status
sheet (`design/screens/onboarding-kernel.md`, "kernel spec"), Schedules (Schedules spec,
not yet written).

Strings marked *(Android)* are new; every other string is the desktop's text verbatim,
including `...` versus `…`. All strings live in `res/values/strings.xml` under the keys
listed in section 9.

---

## 0. Desktop sources read (what was copied)

All paths are under `~/workspace/cline-termux-v3-phone`.

| File | Copied |
| --- | --- |
| `apps/examples/desktop-app/webview/components/agent-sidebar.tsx` | Sidebar order (logo/home + search, New/Schedule/Customize actions, "Sessions"/"Projects" header with sort toggle and filter menu, Pinned/Scheduled/Tasks `CategorySection`s, `ProjectSection`s, `ScheduleGroupRow`, `ThreadItem`, account/Settings footer). Section rules (category headers only when something is pinned or scheduled; pinned rows lead each project group). Paging (`INITIAL_VISIBLE_THREAD_COUNT` = 30 rows, "Show more", "Show more in <project>", "Loading...", page-fill loop with the failure halt). Filter menu ("Status": All/Running, "Source": All sources + labels). Sort toggle semantics (icon shows the mode a tap switches to). Rename editor behaviour (Enter commits, Escape cancels, blur commits). Context menu (Pin/Unpin, Rename/Renaming..., Fork/Forking..., Delete/Deleting...; items disabled only on the row whose action is pending, lines 1741-1747). Delete `AlertDialog` copy and its "cannot dismiss while deleting" rule. `getSessionOverviewItems` (details labels and order; its "Source" value is the raw `thread.source`). Footer account name (first word of `displayName`, else the email local part, lines 297-304). In-memory sort mode (`useState("time")`, line 322). Header counts (`count={scheduledRows.length}` for Scheduled, line 1091). Hub hover card copy ("Cline Hub @<port>", "Cline Hub is not connected.", "Version unavailable"). |
| `apps/examples/desktop-app/webview/components/views/sessions/sessions-view.tsx` | Page title and description, search field (local substring filter over title, workspace, provider, model), sort menu (Newest first / Oldest first), facet filter menu (`pinned:yes`, `workspace:<name>`, `status:<s>`, `provider:<p>`, `model:<m>`, OR semantics, "Clear filters", "No filters available", "load all history when filtering"), columns (Title, Workspace, Model, Tokens, Cost, Time), `modelLabel`, `tokensLabel`, `formatCompactTokens`, fallbacks ("No model", "No workspace", "-"), row menu, inline rename (Save title / Cancel rename), empty states, loading copy, the "N-M of T+" pager text (read, then dropped with the pager, 3.2), usage hydration for visible rows only. |
| `apps/examples/desktop-app/webview/components/session-command-bar.tsx` | Search: title "Search session history", description, placeholder "Search all session history…", 180 ms debounce, `limit: 50`, 3 s timeout, batches of 15 revealed on scroll (120 px from the end), "Searching sessions…", "No matching session history.", heading "Session history", hit layout (role + title, two-line snippet, workspace root), "Showing N of M results", empty-query hint, keyboard footer. |
| `apps/examples/desktop-app/webview/hooks/use-session-history.ts` | `SessionThread` fields, `toTitle` fallback chain, `formatRelativeTime`, `basenamePath`, `formatTokenCount`, `formatCostUsd`, activity-timestamp ordering, `INITIAL_HISTORY_FETCH_LIMIT` = 50, `loadOlderSessions` (+50) and `loadAllSessions` (exponential), unread rules (any event for a session that is not the active one marks it unread; opening clears it), optimistic pin with rollback, rename/fork/delete pending action, every toast title and description, `isValidHistorySession` (hide rows without a known provider and model), the subagent guard (`!isSubagent && !parentSessionId`, line 824), title hydration from messages for "Session xxxxxx" rows, the initial-load retry on the 2 s event cadence (`scheduleRefresh` continuation, lines 910-919), `normalizeDiscoveredStatus` (the status vocabulary of the `status:` facet), and the scheduled-session cross-link (`scheduledSessionLinks` from `list_routine_schedules`, lines 604-613, 676-759, 1826-1853: "Scheduled runs executed by the local hub do not reliably carry the hub-schedule origin trigger"). |
| `apps/examples/desktop-app/sidecar/commands.ts` | `list_routine_schedules` = `schedule.list {limit:200}` + `schedule.active` + `schedule.list_executions {limit:50}` (line 1235); `update_chat_session_metadata` restoring the previous `updated_at` after a pin/rename ("Annotating a session is not session activity", line 2611), which the app reproduces client-side (section 1.3 rule 5). |
| `apps/examples/desktop-app/webview/lib/session-history.ts` | Metadata keys (`title`, `pinned`, `sessionHistoryOrigin.trigger == "hub-schedule"`, `scheduleId`, `scheduleName`, `scheduleRunNumber`, `git.branch`), `getSessionSourceLabel` (CLI, Desktop, VS Code, title-casing otherwise). |
| `apps/examples/desktop-app/webview/lib/sidebar-session-organization.ts` | `groupThreadsByProject` + `uniqueWorkspaceLabel` (shortest unique path suffix, "Chat", "Other"), `groupScheduledThreads`, `scheduleGroupKey`, `scheduleRunLabel` ("Run N" or a short date-time), `workspaceDisplayName`. |
| `apps/examples/desktop-app/webview/lib/session-status.ts` | The "one mapping" rule; the app's single mapping is DS §1.6. |
| `apps/examples/desktop-app/webview/components/agent-header.tsx` | "New Session" title fallback, "Delete session" / "Deleting..." as the **only** item of the desktop header menu (lines 246-273); rename there is a click on the title and fork is the message action. The Android header overflow's extra Pin / Rename / Fork / Details items are DS §4.2 ConversationHeader additions *(Android)*, which reuse F2-F4 and F6. |
| `apps/examples/desktop-app/webview/components/utils.ts` + `sdk/packages/shared/src/prompt/format.ts` | `normalizeTitle` = `formatDisplayUserInput` (strips `<user_input>` wrappers and mode notices; `/team` and slash-command envelopes), `formatSessionSearchTitle` (240 chars), `formatSessionSearchPreview` (480 chars), `compactSessionSearchText`. |
| `sdk/packages/shared/src/storage/chat-workspace-paths.ts` | `isChatWorkspacePath` (`.cline/data/workspaces/chat` reads as "Chat"). |
| `sdk/packages/ui/components/agent-session-row.tsx` | Row anatomy (leading glyphs, one-line label, trailing dot + pin + time), "pending actions take precedence over runtime status; unread is idle-only", `AgentSessionRowEditor`, `AgentSessionOverview` (two-column label/mono value grid). |
| `sdk/packages/ui/components/session-status.tsx` | Dot + screen-reader label "Session status: <s>". |
| `apps/cli/src/runtime/interactive/fork/title.ts`, `session-runtime.ts` (`forkCurrentSession`) | Fork title "<base> (fork)" (120 chars max), "Cannot fork an empty session.", fork metadata `{forkedFromSessionId, forkedAt, source}`. |
| `sdk/packages/core/src/hub/server/handlers/session-handlers.ts`, `session/search/session-history-search.ts`, `runtime/host/local-runtime-host.ts` | `session.list` default limit 200 and `rootOnly`; `session.search` clamps `limit` to 1..200, returns one hit per session, snippet markers `[`/`]` and `…`; `session.update` strips server-owned metadata and publishes `session.updated`; `session.delete` stops a live session first, publishes **no** event, and may reply `deleted:false` for a session that is gone. `listSessions` unshifts every live session unfiltered (lines 1279-1292), so `rootOnly` does not hide a live teammate. `getAccumulatedUsage` (lines 1157-1165) reads only live sessions' in-memory usage. |
| `sdk/packages/core/src/session/services/session-service.ts`, `persistence-service.ts` | `session.list` orders by `started_at DESC` before `limit` (line 110); every `updateSession` stamps `updated_at = now` (lines 198-199); `title: ""` normalises to nothing and deletes `metadata.title` (persistence-service lines 243-251). |
| `sdk/packages/core/src/hub/server/hub-server-transport.ts`, `handlers/run-queue-handlers.ts`, `handlers/capability-handlers.ts`, `handlers/approval-handlers.ts`, `browser-websocket.ts`, `hub-event-log.ts` | `run.interrupted` is published only for runs recovered from the durable run queue (`recoverOnStartup`, lines 683-696), which only `run.enqueue` fills; `session.send_input` / `run.start` go straight to `handleSessionInput` (lines 860-862). `hub_draining` refuses only `session.create`, `session.restore`, `session.fork`, `run.start`, `session.send_input`, `run.enqueue` (`DRAIN_REFUSED_COMMANDS`). `capability.resolved {requestId, capabilityName, targetClientId, ok, cancelled?, error?}` is published on respond and on cancel. Pending `approval.requested` events are re-issued on every subscribe regardless of the cursor. The replay pages 200 events up to 1,000 pages per subscribe; the log keeps up to 200,000 rows / 64 MiB / 7 days. |

Voice app code reused (copy with the header comment from DS §0):
`ui/list/SessionRow.kt` (touch-point long-press menu, haptic, row highlight while the
menu is open, TalkBack custom actions), `ui/list/SessionDialogs.kt` (rename dialog
mechanics: selected text, one-line paste, focus request inside the dialog window),
`ui/list/ListNotices.kt` (`ListNotice` inline notice, polite live region),
`ui/SessionListScreen.kt` (`PullToRefreshBox`, "the list never goes blank on a bad
moment", minute tick for ages, keep-top-in-view), `ui/TimeFormat.kt` (`compactAgo`
structure; strings replaced by section 1.4 below).

---

## 1. Shared model (all sessions surfaces bind to this)

### 1.1 Components and ownership

```
app/src/main/kotlin/dev/cline/termux/
  sessions/SessionsRepository.kt     app-scoped singleton: list, events, unread, pending flags, cache
  sessions/SessionSummary.kt         row model + mapping from Hub SessionRecord
  sessions/SessionFormat.kt          ports: normalizeTitle, basenamePath, workspaceLabel, modelLabel,
                                     formatCompactTokens, formatTokenCount, formatCostUsd, compactAge,
                                     scheduleRunLabel, getSessionSourceLabel, uniqueWorkspaceLabel
  sessions/SessionGrouping.kt        ports: groupThreadsByProject, groupScheduledThreads, scheduleGroupKey
  sessions/SessionActions.kt         rename / pin / fork / delete / resume (F1-F5), one pending action at a time
  sessions/SessionListCache.kt       last good list on disk (section 1.8)
  ui/sessions/SessionsDrawer.kt      S1 content (drawer sheet and list pane share it)
  ui/sessions/SessionsDrawerViewModel.kt
  ui/sessions/SessionsScreen.kt      S2
  ui/sessions/SessionsScreenViewModel.kt
  ui/sessions/SessionSearch.kt       S3 (full-screen and docked variants)
  ui/sessions/SessionSearchViewModel.kt
  ui/sessions/SessionDetailsSheet.kt F6
  ui/sessions/DeleteSessionDialog.kt F5
```

Every formatting function in `SessionFormat.kt` and `SessionGrouping.kt` is a 1:1 Kotlin
port with unit tests copied from the desktop's `*.test.ts` where they exist
(`sidebar-session-organization.test.ts`, `sessions-view.test.tsx` for
`formatCompactTokens`/`paginationItems`, `use-session-history.test.tsx` for
`formatCostUsd`/`formatRelativeTime`).

### 1.2 Kernel API used by this group

Quoted from `docs/03-feature-matrix.md`; refinements from the fixtures are noted.

| Need | Command / event / RUN_COMMAND | Notes |
| --- | --- | --- |
| List | `session.list {limit, rootOnly:true}` -> `{sessions: SessionRecord[]}` | First call `limit: 50`; "load older" adds 50; "load all" doubles until a reply holds fewer rows than `limit`. Default limit server-side is 200 when omitted, so always pass it. `rootOnly:true` hides persisted subagent/teammate sessions, but live sessions are prepended unfiltered, so the client guard of 1.3 rule 1 is still needed. **The server pages by creation time** (`ORDER BY started_at DESC LIMIT ?`) while the app sorts by activity: an old session resumed today sits outside the first 50 until it reappears through its `session.updated {session}` event (1.3 rule 2) or after "load all" (desktop has the same gap). |
| Live updates | events `session.created {session}`, `session.updated {sessionId, snapshot}` and `{session, snapshot}` (always a pair, fixture 02) on the **global** `stream.subscribe` | Every session event also reaches the global subscription (Corrections). The repository subscribes globally once and routes by `envelope.sessionId`. The cursor is bounded by the replay budget of 1.2.1. |
| Replay bound | `hub.status {}` -> `{eventLog:{lastSequence}, ...}`, called right before the global subscribe | Section 1.2.1. |
| Status flags | `approval.requested` / `approval.resolved`, `capability.requested` (capabilityName `tool_executor.askQuestion`) / `capability.resolved {requestId, ok, cancelled?}`, `run.started {requestId, clientId}`, `run.completed` / `run.failed` / `run.aborted`, the shutdown `session.updated` pair | Pending approvals are re-issued on every subscribe: dedupe by `approvalId`. `run.completed` with `reason != "completed"` (for example `disposed`) is not success and does not mark unread. `run.interrupted` is **not** used: the Hub emits it only for `run.enqueue` runs, and the app sends `session.send_input` (section 0; interrupted detection is client-side, 1.3 `SessionFlags.interrupted`). |
| Usage for a row | `SessionRecord.metadata.usage` + `metadata.totalCost` from `session.list`; for **live** rows only, `session.get {sessionId}` -> `usage`, `session.metadata.totalCost` | Fixture 05: list records have no top-level `usage`; a `session.update` without a metadata spread wipes `metadata.usage` (Corrections). `session.get` returns usage only from the live session's memory, so a persisted row without `metadata.usage` stays "-" (1.5). |
| Schedule link | `schedule.list {limit:200, allWorkspaces:true}` -> `{schedules[{scheduleId, name, lastRunAt}]}`, `schedule.active {allWorkspaces:true}` -> `{executions[{sessionId, scheduleId}]}`, `schedule.list_executions {limit:50, allWorkspaces:true}` -> `{executions[...]}`; events `schedule.*` (global subscription only) | Section 1.3 rule 6 (desktop `scheduledSessionLinks`). `allWorkspaces` needs token auth, which the app always uses (feature matrix). |
| Search | `session.search {query, limit:50}` -> `{hits[{sessionId, documentId, ordinal, role, title, snippet, startedAt, workspaceRoot, score}]}` | One hit per session, ranked. Matches are bracketed `[...]` in `snippet`. Lags for live sessions: the index refreshes on session end. May be empty when FTS is unavailable. `invalid_search_query` for a blank query (never sent). |
| Open | `session.get {sessionId, includeSnapshot:true}`, `session.messages {sessionId}`, `session.attach {sessionId}` | `session.attach` succeeds on a non-live session but does not make it live (fixture 05). |
| Restore (resume) | `session.create {workspaceRoot, cwd, sessionConfig:{sessionId:<old>, providerId, modelId, mode, enableTools:true, enableSpawnAgent:true, enableAgentTeams:true, checkpoint:{enabled:true}}, initialMessages:<session.messages result>, metadata:{source:'android', interactive:true, provider, model}, toolPolicies, runtimeOptions:{clientContributions:[askQuestion]}}` | Triggered by `session_not_found` on `session.send_input` (recovery matrix). Replies with the same `sessionId`; `title`/`pinned` survive; usage recomputed (fixture 05). |
| Rename, pin | `session.update {sessionId, title?, metadata?}` -> `{updated, session, snapshot}`; event `session.updated` | **Metadata is replaced wholesale**: read the current record (`session.get`), spread `session.metadata`, set the key. The server strips `autoApproveTools` and `hubCapabilityOwnerClientId`. |
| Fork | client-side: `session.messages` -> `session.create {initialMessages, metadata:{fork:{forkedFromSessionId, forkedAt, source}}}` | `session.fork` is declared but never dispatched. |
| Delete | `session.delete {sessionId, deleteCheckpointRefs?}` -> `{deleted}` | Stops a live session first. No event is published, so other clients learn at their next `session.list`. `deleted:false` can mean "no history on disk" although the session is gone: verify with `session.get` (`session_not_found` = success). `deleteCheckpointRefs` is omitted (the app registers no checkpoint contribution, Corrections). |
| Kernel liveness | `GET /health`, `hub.status`, KernelCoordinator `KernelState` (`app/.../kernel/KernelCoordinator.kt`) | Drives the notices in section 1.7. |
| Start kernel (notice action) | RUN_COMMAND background `$PREFIX/bin/cline hub start`, then `termux-wake-lock` (via `KernelCoordinator.ensureConnected(startIfDown = true)`) | 8-15 s cold on the S25U. The screens show "Connecting..." meanwhile. |
| Account footer (optional) | dashboard `desktopCommand cline_account {operation:'fetchMe'}` / `fetchUserOrganizations` | Only when the settings helper is running (Account spec owns it). Without it the footer shows "Settings" alone, as the desktop does when signed out. |

No RUN_COMMAND is used for listing, searching or editing sessions: `cline history --json`
is **not** a fallback, because it would bypass the Hub's view and costs a 2.5 s Bun start
per call.

#### 1.2.1 Replay budget for the global subscription *(Android)*

The Hub's replay is unbounded from the client's point of view: it pages 200 events at a
time for up to 1,000 pages, i.e. the whole retained log (200,000 rows, 64 MiB, 7 days),
and that log holds every `assistant.delta` of every CLI and schedule session. A cursor
kept forever would make the first reconnect after a day of CLI use stream tens of
thousands of frames before the drawer is trustworthy. `SessionsRepository` therefore:

1. On every (re)connect calls `hub.status {}` first and reads `eventLog.lastSequence` (L).
2. With the persisted global cursor C (DataStore `sessions.globalCursor`):
   - C absent, or `L - C > REPLAY_BUDGET` (**2,000** events): subscribe with
     `sinceSequence = max(0, L - REPLAY_BUDGET)` and mark the gap `replayTruncated = true`.
     Statuses then come from the `session.list` that follows (1.9 item 1); unread for the
     skipped range is unknown and is **not** inferred.
   - otherwise: subscribe with `sinceSequence = C`.
   - `L < C` (a reset event log): treat as C absent.
3. Pending approvals are unaffected: the Hub re-issues every open `approval.requested`
   on subscribe whatever the cursor is (they carry their original sequence, which the
   adapter does not filter for re-issued events), so Approval required dots are always
   correct.
4. The cursor advances to the highest delivered `sequence` and is written at most once a
   second and on `ON_STOP`.
5. Unread from replay is additionally capped by time: a replayed event marks a session
   unread only when its `envelope.timestamp` is newer than `sessions.lastDisconnectAt`
   (the wall-clock time the app last had a live socket, written on socket close and on
   `ON_STOP`). Live events are not capped.

Expected cost on the S25U: at most 2,000 frames (about 10 pages) per reconnect.

### 1.3 `SessionSummary` (row model)

```kotlin
// sessions/SessionSummary.kt
@Immutable
data class SessionSummary(
    val id: String,                    // SessionRecord.sessionId
    val title: String,                 // section 1.4 "Title"
    val hasCustomTitle: Boolean,       // metadata.title non-blank
    val workspaceRoot: String,         // record.workspaceRoot.ifBlank { record.cwd }
    val cwd: String,
    val kernelStatus: KernelSessionStatus, // record.status, or the newest session.updated status
    val provider: String,              // metadata.provider (trimmed)
    val model: String,                 // metadata.model (trimmed)
    val source: String,                // metadata.source ("android", "cli", "vscode", "hub-schedule"...)
    val pinned: Boolean,               // metadata.pinned == true
    val schedule: ScheduleInfo?,       // non-null when metadata.sessionHistoryOrigin.trigger == "hub-schedule"
                                       // or the session is in the schedule link map (rule 6)
    val gitBranch: String?,            // metadata.git.branch
    val createdAt: Long,               // ms
    val activityAt: Long,              // max(updatedAt, createdAt), unless an annotation
                                       // override applies (rule 5); the sort key and the age shown
    val usage: RowUsage?,              // null until known (section 1.5)
    val metadata: JsonObject,          // raw record metadata, kept for read-modify-write (F2, F3)
)

enum class KernelSessionStatus { Idle, Running, Pending, Completed, Aborted, Cancelled, Failed, Unknown }
// "aborted" (record) and "cancelled" (snapshot) both display as "Cancelled" (DS §1.6).
// Pending here is the KERNEL status `pending` (an approval or capability is outstanding,
// fixture 05). It is a different thing from a pending ACTION (SessionFlags.pendingAction).

@Immutable data class ScheduleInfo(val scheduleId: String?, val scheduleName: String?, val runNumber: Int?)
@Immutable data class RowUsage(val inputTokens: Long?, val outputTokens: Long?, val totalCostUsd: Double?)

// App-side flags, merged by SessionsRepository (DS §1.6 inputs):
@Immutable data class SessionFlags(
    val pendingAction: PendingAction.Kind? = null,   // our rename/fork/delete on this row in flight (1.10)
    val openApprovalIds: Set<String> = emptySet(),   // approval.requested minus approval.resolved / run.* terminal
    val openQuestionIds: Set<String> = emptySet(),   // capability.requested (askQuestion) minus capability.resolved
                                                     // with the same requestId (ok or cancelled); run.* terminal as fallback
    val starting: Boolean = false,                   // our session.create (new, fork, restore) in flight
    val unread: Boolean = false,                     // section 1.6
    val interrupted: Boolean = false,                // client-side rule below; cleared on the next run.started
)
// Visual = SessionVisual.of(kernelStatus, flags) from ui/components/SessionStatus.kt. No other code picks a colour.
```

**Pending action in the visual** (desktop `ThreadItem` passes `status="pending"` while
`pendingAction` is set, and `AgentSessionRow` paints it `bg-yellow-400` ahead of
provisioning, running and unread). `SessionVisual.of` checks `flags.pendingAction`
**first**: a non-null value yields the visual "Action pending" = static `warning.dot`
(desktop yellow-400, no pulse), label = the menu's pending label ("Renaming...",
"Forking...", "Deleting..."), TalkBack "Session status: <pending label>". This is the
DS §1.6 sentence "Pending actions win over runtime status" made concrete; DS §1.6's table
needs this row added by its owner (open question 6). The kernel status `pending` keeps its
own DS §1.6 "Pending" row below Question asked.

**Question flag.** `openQuestionIds` gains a `requestId` only for `capability.requested`
with `capabilityName == "tool_executor.askQuestion"` (any `targetClientId`, so a question
raised for the CLI also shows the amber dot), and loses it on `capability.resolved` with
the same `requestId` whatever `ok`/`cancelled` say (answered here, answered by another
client, or cancelled because the target unregistered). A terminal `run.*` for the session
clears all of them as a fallback. The chat spec offers an answer card only when
`targetClientId` equals the app's own clientId.

**Interrupted rule** (client-side; replaces the kernel contract's `run.interrupted`
binding, which the app's `session.send_input` turns can never produce, section 0).
`SessionsRepository` keeps `inFlightTurns: Map<sessionId, requestId>`: an entry is added on
`run.started {requestId, clientId == own clientId}` and removed on any terminal
`run.completed` / `run.failed` / `run.aborted` for that session. When the socket closes
with entries left, they move to `suspectInterrupted` (persisted in DataStore with the
turn's prompt text, so a process death in between does not lose it). After the next
connect, for each suspect session, `flags.interrupted = true` when any of these arrives
first, from the reconnect's `hub.status`, the replay or the following `session.list`:

- `hub.status.hubId` differs from the hubId of the connection the turn ran on (a new
  daemon: the turn cannot have survived; covers a killed daemon that wrote no shutdown
  projection, fixture 05 shows a new hubId after `hub stop`/`hub start`);
- a `session.updated` whose `snapshot.endedAt` is set or whose status is `completed`,
  `aborted`/`cancelled` or `idle` (the daemon's shutdown projection pair, fixture 05);
- `run.completed` with `reason != "completed"` (for example `disposed`);
- a `session.list` record whose status is not `running`.

A `run.completed {reason:"completed"}`, `run.failed` or `run.aborted` for that session
instead resolves the suspect without the flag (the turn really ended). `flags.interrupted`
clears on the next `run.started` for the session or when the user dismisses the notice.
F1 step 6 shows the notice.

Mapping rules from a Hub `SessionRecord` (fixture 05 shapes):

1. Records with `metadata.isSubagent == true` or a non-blank `metadata.parentSessionId`
   are dropped (desktop guard `!isSubagent && !parentSessionId`; the Hub prepends live
   teammate sessions to a `rootOnly` list). Records whose `metadata.provider` or
   `metadata.model` is blank or `"unknown"` are
   dropped (desktop `isValidHistorySession`), **except** the session currently open and
   any session this app created in this process (a brand-new session may list before its
   metadata is filled).
2. `session.updated {sessionId, snapshot}` updates `kernelStatus` from `snapshot.status`;
   `{session, snapshot}` replaces the whole summary from `session` (keep `usage` if the new
   record has none). `session.created {session}` inserts (or replaces) by `id`.
3. Order: `activityAt` descending, ties by `id` descending (desktop
   `compareSessionsByActivityDesc`). The Sessions screen's "Oldest first" reverses it.
4. The list is a `PersistentList` keyed by `id`; every LazyColumn uses `key = id`.
5. **Annotation is not activity** (desktop sidecar restores `updated_at` after a
   metadata write; the app has no SQL access, so it does the same client-side). The
   Hub stamps `updated_at = now` on every `session.update`, so a pin or rename would
   otherwise move a three-week-old session to the top with age "now". Before F2/F3 issue
   `session.update`, `SessionsRepository` records `annotationOverride[id] =
   (prevActivityAt = current activityAt, armedAt = now)`. While an override exists, any
   record for that session (the `session.update` reply, `session.updated {session}`, a
   later `session.list` row) whose `updatedAt` is newer than `prevActivityAt` keeps
   `activityAt = prevActivityAt`. The override is dropped (and `activityAt` follows the
   record again) as soon as a `run.started`, `assistant.*` or `tool.*` event arrives for
   that session, or a record arrives whose status is `running`. Overrides persist with the
   list cache (1.8) so they survive relaunch; at most 500, oldest dropped. Other clients
   (CLI `cline history`, the desktop) still see the bumped time; that is a kernel
   behaviour the app cannot change.
6. **Schedule link** (desktop `scheduledSessionLinks`: runs executed by the local Hub do
   not reliably carry the `hub-schedule` origin trigger). `SessionsRepository` keeps
   `scheduleLinks: Map<sessionId, (scheduleId?, scheduleName?)>`, filled from
   `schedule.active` + `schedule.list_executions {limit:50}` (each execution's
   `sessionId`, `scheduleId`) with names from `schedule.list {limit:200}`, all with
   `allWorkspaces:true` (1.2). New links **merge** into the map, never replace it (the
   executions list is a rolling window). A row in the map gets `schedule = ScheduleInfo(
   metadata value ?: link value)` for id and name (metadata stamped by the runner wins;
   the link only fills gaps), `runNumber` from metadata only. Refresh: after each
   (re)connect, on `ON_RESUME` when the last refresh is older than 2 minutes, and 1 s after
   any `schedule.*` event (debounced); the map persists with the list cache. A failure is
   silent (rows fall back to the metadata check), as in the desktop (`.catch(() => null)`).

### 1.4 Display formatting (ports; exact output)

| Function | Rule | Examples |
| --- | --- | --- |
| Title (`toTitle`) | `normalizeTitle(metadata.title)` if non-blank; else the first non-blank line of `normalizeTitle(metadata.prompt)`; else `"Session " + id.takeLast(6)`. `normalizeTitle` = port of `formatDisplayUserInput` (strip `<user_input mode="...">` wrapper and `<mode_notice>` blocks; `/team` and slash envelopes become `/team …`, `/<slash> …`). Rows render the first line only (`getSessionOverviewTitle`). | `Run echo hello-from-mock and summarize`; `Session k3p9x2` |
| Title hydration | For at most 4 of the newest rows that are not open and whose title starts with `"Session "` and have no custom title: 1200 ms after a list load, `session.messages` -> first user (then assistant) text line. Same rule as the desktop's hydration effect. | |
| Workspace label (S2 column, details) | `basenamePath(workspaceRoot)`: `isChatWorkspacePath` -> "Chat"; `/data/data/com.termux/files/home` -> "~" *(Android)*; else last path segment; empty -> "No workspace" (S2) | `cline-termux-app`, `Chat`, `~` |
| Project label (S1 project mode) | `uniqueWorkspaceLabel` (shortest unique trailing path among all groups); the kernel's chat workspace (sessions it imported) -> "Chat"; the Termux home `/data/data/com.termux/files/home`, where a session with no folder chosen starts, -> "Home" *(Android, decided A22; by the path itself, since the recent-workspaces catalog leaves the home out)*; blank path -> "Other" | `app`, `client/app`, `Home` |
| Model label (S2) | `"$provider:$model"` when both; else model, else provider, else "No model" | `anthropic:claude-sonnet-4-5` |
| Tokens (S2) | `formatCompactTokens(in) + "/" + formatCompactTokens(out)`; "-" when both are null. `formatCompactTokens`: <0 or non-finite -> "0"; ≥1,000,000 -> one decimal + "m"; ≥1,000 -> one decimal + "k"; else integer | `12.5k/3.2k`, `1.2m/40.0k`, `-` |
| Tokens (F6 details) | `formatTokenCount(in, out)`: total ≤0 -> row omitted; ≥10,000 -> "Nk" (0 decimals); ≥1,000 -> one decimal + "k"; else integer | `2.6k`, `14k` |
| Cost | `formatCostUsd`: null, non-finite or ≤0 -> "-" (S2) / row omitted (F6); <0.01 -> 4 decimals; <1 -> 3 decimals; else 2 decimals; always `$` prefix | `$0.0042`, `$0.123`, `$1.23` |
| Age (S1 rows, S2 Time column) | `compactAge(activityAt)` *(Android adaptation of `formatRelativeTime`, satisfying DS §8.2.5)*: <1 min "now"; <1 h "Nm"; <24 h "Nh"; previous calendar day "Yesterday"; <7 days "Nd"; same year `DateUtils.formatDateTime(FORMAT_ABBREV_MONTH or FORMAT_NO_YEAR)`; else with year. Re-evaluated by a once-a-minute tick (voice `rememberMinuteTick`). TalkBack reads `DateUtils.getRelativeTimeSpanString(activityAt, now, MINUTE_IN_MILLIS)` (unabbreviated, "5 minutes ago") | `now`, `5m`, `3h`, `Yesterday`, `4d`, `Sep 13`, `Sep 13, 2025` |
| Schedule run label (nested rows) | `"Run N"` when `runNumber > 0`; else `DateUtils.formatDateTime(createdAt, FORMAT_SHOW_DATE or FORMAT_ABBREV_MONTH or FORMAT_SHOW_TIME or FORMAT_NO_YEAR)`; else "Run" | `Run 12`, `Sep 13, 2:05 PM` |
| Schedule group label | `scheduleName` trimmed, else the run's title | `Daily standup` |
| Source label (filter menu; F6 *(Android: the desktop hover card prints the raw value, e.g. `hub-schedule`)*) | `getSessionSourceLabel`: `cli` -> "CLI", `desktop` -> "Desktop", `vscode`/`vscode-webview` -> "VS Code", else split on `-_ ` and title-case each part | `Android`, `Hub Schedule` |

### 1.5 Usage hydration (tokens and cost)

1. From the list record: `metadata.usage.inputTokens/outputTokens` and
   `metadata.totalCost`. If `metadata.usage` is present the row's usage is known.
2. Otherwise the row is "usage unknown". `session.get` top-level `usage` comes from the
   live session's memory only (`getAccumulatedUsage`), so asking for a persisted row buys
   nothing. The Sessions screen (S2) asks the repository for the rows on screen
   (`requestUsage(visibleIds)`, like the desktop) but the repository reads only rows that
   are **live**: `kernelStatus` Running, Pending or Idle and a non-empty
   `participants` list (persisted records after a restart have `participants: []`,
   fixture 05). At most **10** rows, at most **4** concurrent `session.get {sessionId}`
   calls, results cached by `(id, activityAt)`. While a live row's usage is loading its
   tokens/cost show "-" with the shimmer; a non-live row without `metadata.usage` shows
   "-" with no shimmer and is never read. Leaving S2 calls `requestUsage(emptyList())` and
   cancels pending reads.
3. Live sessions: `usage.updated {usage, ...}` updates the row's tokens in place;
   `metadata.totalCost` arrives with the next `session.updated {session}`.
4. The drawer (S1) never triggers usage reads (it does not show tokens).

### 1.6 Unread

Desktop rule: any session event for a session that is not the active one marks it
unread; opening it clears the mark. The app keeps that rule with two filters that the
Hub's durable replay makes necessary:

- **Counts:** `assistant.finished`, `run.completed` (reason `completed`), `run.failed`,
  `run.aborted`, `approval.requested`, `capability.requested`, `session.notice`,
  `session.created` by another client.
- **Does not count:** `session.updated` (shutdown projections arrive as pairs after a
  daemon restart, fixture 05), `run.completed {reason: "disposed"}`, `usage.updated`,
  deltas, `hub.*`, events for the session that is visible on screen right now (chat
  route resumed and in the foreground), events whose `sequence` is ≤ the session's
  persisted `lastSeenSequence`, and replayed events older than
  `sessions.lastDisconnectAt` (1.2.1 item 5), so a week-old `assistant.finished` never
  marks a CLI session unread. When the replay was truncated (1.2.1 item 2), nothing is
  marked for the skipped range.
- The unread set and `lastSeenSequence` per session persist in DataStore
  (`sessions.unread`: `Set<String>`, `sessions.lastSeen`: JSON map `sessionId -> Long`,
  capped at 500 entries, oldest dropped). Opening a session sets its `lastSeenSequence`
  to the highest sequence seen for it and removes it from the set. Deleting a session
  removes both.
- Unread shows only in the idle-ish visual states (DS §1.6 precedence).
- Rail badge *(Android)*: when any session is unread and the session list is not on
  screen (medium window, or expanded with the list pane collapsed), the rail's "Chats"
  item shows a 6dp `Badge()` (no number), TalkBack "Chats, unread sessions".

### 1.7 Kernel states as list notices

Mapping from `KernelCoordinator.state` (`KernelState`) to the notice at the top of S1
and S2 (and the body of S3). The notice is the voice `ListNotice` (DS-compatible:
`surfaceContainerHigh`, `shapes.large`, 20dp icon, polite live region). The copy for
connection lines is DS §1.6 "Kernel connection"; the setup lines are shared with the
Kernel setup spec (the first spec implemented adds the keys, the other reuses them).

| `KernelState` | Icon | Title | Supporting | Action | Action does |
| --- | --- | --- | --- | --- | --- |
| `Unknown` (first 1.5 s after launch) | none | (no notice; grace period, voice precedent) | | | |
| `Starting(step)` / `Connecting` | 16dp `CircularProgressIndicator` | "Connecting..." | none | none | |
| `Degraded(Reconnecting)` | 16dp progress | "Reconnecting..." | none | none | |
| `HubDown` | `terminal` | "Termux asleep" | "Start kernel to continue." | "Start kernel" | `KernelCoordinator.ensureConnected(startIfDown = true)` -> RUN_COMMAND `cline hub start` + `termux-wake-lock` |
| `Failed(reason)` / `Degraded(Unavailable)` | `error` in `error` | "Cline Hub is not connected." | `reason` (kernel text, untranslated) | "Retry" | `ensureConnected(startIfDown = true)` |
| `TermuxMissing` | `terminal` | "Termux is not installed" *(Android)* | "Install Termux to run the Cline kernel." *(Android)* | "Set up" *(Android)* | navigate to Kernel setup |
| `PermissionMissing` | `gpp_maybe` | "Termux permission needed" *(Android)* | "Allow Cline to run commands in Termux." *(Android)* | "Set up" | navigate to Kernel setup |
| `PortTooOld(version)` | `info` | "Kernel update required" *(Android)* | "Cline kernel v3.0.68-termux.2 or later is needed." *(Android)* | "Details" *(Android)* | open the Kernel status sheet |
| `Ready` | (no notice) | | | | |

When a notice other than "Connecting..."/"Reconnecting..." shows and a cached list exists
(section 1.8), a caption follows the notice: "Showing the last loaded list." *(Android)*
(`labelMedium`, `onSurfaceVariant`, 16dp gutters). Rows from the cache are tappable (they
open the chat route, which shows its own read-only state) but every row action (rename,
pin, fork, delete) is disabled, menu items render disabled, and TalkBack custom actions
are omitted. "New session" stays enabled (Home handles a down kernel).

### 1.8 Local list cache

`SessionListCache` writes the last successful `session.list` result (up to 200
`SessionSummary`s without `metadata`, plus the annotation overrides of 1.3 rule 5 and the
schedule links of 1.3 rule 6, as JSON via kotlinx-serialization) to
`filesDir/sessions/list-cache.json` after each successful load and reads it at process
start, so the drawer is never blank while the kernel is down or still connecting. It
holds titles and workspace paths only, never tokens, keys or transcripts. The file is
excluded from backup and device transfer (`dataExtractionRules`/`fullBackupContent`
exclude `sessions/`). A successful live list always replaces the cache view; cached rows
never mix with live rows.

### 1.9 Refresh policy (no poller)

The desktop polls every 12 s; the app does not (feature matrix). `session.list` runs:

1. after every successful (re)connect, right after the global `stream.subscribe`;
2. on `ON_RESUME` of the activity when the last list is older than 10 s;
3. when the drawer opens (compact/medium) and the last list is older than 10 s;
4. on pull-to-refresh in S2;
5. 250 ms after any event for a `sessionId` the repository does not know, when that event
   carries no `session` record to insert (defensive; `session.created` normally inserts
   the row itself, desktop `scheduleRefresh` for unknown sessions);
6. after F4 fork (50 ms) and F5 delete (to pick up the server's view);
7. **initial-load retry** (desktop `scheduleRefresh` continuation: "a failed fetch ...
   retries on the short event cadence instead of stranding the sidebar"): while
   `load == Initial` (nothing has loaded on this connection), a failed or timed-out
   `session.list` is retried every **2 s** (desktop `MIN_EVENT_HISTORY_REFRESH_INTERVAL_MS`)
   until the first success or until `KernelState` leaves `Ready` (the reconnect then
   re-runs item 1). During these retries the body stays "Loading session history..." (or
   the cached rows under the cache rules of 1.7). The "Could not load sessions" notice
   (2.7 Error) is reserved for failures **after** a successful load on this connection.
   This is the common phone case: the first list right after `cline hub start`, or a
   socket Doze dropped mid-reply.

Concurrent triggers coalesce into one in-flight request (the newest `limit` wins; a call
that raised the limit is never answered by a smaller one, desktop `loadMoreSessions`).

### 1.10 Pending actions

`SessionActions` tracks rename/fork/delete per session: `data class PendingAction(val
sessionId: String, val kind: Kind)` with `Kind { Rename, Fork, Delete }`, held as
`pending: Map<String, PendingAction.Kind>` and mirrored into each row's
`SessionFlags.pendingAction`. As in the desktop, only the row whose action is pending is
disabled (no click; its menu shows the pending label with every item disabled, DS §4.1
#15); every other row stays fully usable, including starting a second action on another
row. *(Android: the desktop keeps a single `pendingAction` slot that a second action
overwrites, which re-enables the first row while its call is still running; a map keeps
each row's spinner honest. Read-modify-write safety is per session, so actions on two
different sessions cannot clobber each other's metadata.)* A second action on the
**same** row is impossible because the row is disabled. Pin is optimistic and never
pending (F3).

### 1.11 ViewModel state

```kotlin
// ui/sessions/SessionsDrawerViewModel.kt
@Immutable
data class SessionsDrawerUiState(
    val notice: KernelNotice?,                 // section 1.7; null when Ready
    val showingCache: Boolean,                 // rows come from SessionListCache
    val load: ListLoad,                        // Initial (retrying, 1.9 item 7) | Loaded | Failed(message) after a load
    val sortMode: SidebarSortMode,             // Time | Project   (DataStore "sessions.sidebarSort"; Android: desktop resets per launch)
    val statusFilter: StatusFilter,            // All | Running    (in memory, resets per launch like desktop)
    val sourceFilter: String?,                 // null = "All sources"
    val sourceOptions: List<SourceOption>,     // distinct sources of loaded rows, sorted, with labels
    val body: DrawerBody,                      // below
    val activeSessionId: String?,              // the chat on screen (route chat/{id})
    val homeActive: Boolean,                   // route chat/ with no id ("New session" highlighted)
    val editing: RenameDraft?,                 // sessionId + TextFieldValue
    val pending: Map<String, PendingAction.Kind>, // 1.10
    val deleteCandidate: SessionSummary?,
    val isLoadingMore: Boolean,
    val mayHaveMore: Boolean,                  // last reply size >= limit
    val account: AccountFooter?,               // null -> footer shows "Settings" only
)

sealed interface DrawerBody {
    data object Loading : DrawerBody                         // "Loading session history..."
    data object Empty : DrawerBody                           // "No sessions found in history."
    data class Flat(val rows: List<DrawerRow>, val showMore: ShowMore?) : DrawerBody   // nothing pinned or scheduled
    data class Categories(val sections: List<CategorySection>) : DrawerBody            // Pinned / Scheduled / Tasks
    data class Projects(val groups: List<ProjectGroup>, val showMore: ShowMore?) : DrawerBody
}
data class CategorySection(val id: Category, val count: Int /* 2.5 item 2 */, val collapsed: Boolean, val rows: List<DrawerRow>, val showMore: ShowMore?)
enum class Category { Pinned, Scheduled, Tasks }
data class ProjectGroup(val id: String, val label: String, val collapsed: Boolean, val rows: List<DrawerRow>, val showMore: ShowMore?)
sealed interface DrawerRow {
    data class Session(val summary: SessionSummary, val visual: SessionVisual, val nestedLabel: String?) : DrawerRow
    data class ScheduleGroup(val id: String, val label: String, val runCount: Int, val running: Boolean,
                             val unread: Boolean, val expanded: Boolean, val containsActive: Boolean,
                             val runs: List<Session>) : DrawerRow
}
data class ShowMore(val label: ShowMoreLabel, val loading: Boolean)  // "Show more" | "Show more in <project>"
```

```kotlin
// ui/sessions/SessionsScreenViewModel.kt
@Immutable
data class SessionsScreenUiState(
    val notice: KernelNotice?,
    val showingCache: Boolean,
    val load: ListLoad,
    val query: String,                         // local filter text (rememberSaveable)
    val sort: SortDirection,                   // Newest | Oldest
    val selectedFacets: Set<String>,           // "status:running", "workspace:app", ...
    val facetOptions: List<String>,            // sorted with String.compareTo(ignoreCase) like localeCompare
    val loadingAll: Boolean,                   // loadAllSessions in flight (query/facets/oldest need full history)
    val rows: List<SessionsScreenRow>,         // filtered + sorted, windowed for display
    val totalFiltered: Int,
    val mayHaveMore: Boolean,
    val isLoadingMore: Boolean,
    val refreshing: Boolean,                   // pull-to-refresh
    val editing: RenameDraft?,
    val pending: Map<String, PendingAction.Kind>, // 1.10
    val deleteCandidate: SessionSummary?,
    val detailsFor: String?,                   // F6 sheet open for this id
)
data class SessionsScreenRow(
    val summary: SessionSummary, val visual: SessionVisual,
    val workspace: String, val model: String, val tokens: String, val cost: String, val age: String,
    val usageLoading: Boolean,
)
```

```kotlin
// ui/sessions/SessionSearchViewModel.kt
@Immutable
data class SessionSearchUiState(
    val query: String,
    val phase: SearchPhase,     // Idle (blank query) | Searching | Results | NoResults | Failed | KernelDown
    val hits: List<SearchHit>,  // formatted (section 4.6)
    val visibleCount: Int,      // 15, +15 per reveal
    val notice: KernelNotice?,
)
```

All three ViewModels collect `SessionsRepository.state: StateFlow<SessionsSnapshot>` and
`KernelCoordinator.state`; they never call the Hub directly except through
`SessionsRepository` and `SessionActions`.

---

## 2. S1: Sessions drawer, list pane and rail entry points

### 2.1 Purpose

The primary navigator (DS §5.2: "the session list is the primary navigator"). It lists
every root session the Hub knows (all clients and workspaces: this app, the Termux CLI,
schedules), shows which are running, need attention or are unread, and starts, opens,
renames, pins, forks and deletes sessions.

### 2.2 Desktop equivalent

`AgentSidebar` in `webview/components/agent-sidebar.tsx` with `AgentSessionRow` /
`AgentSessionRowEditor` from `@cline/ui`. Copied: order, labels, sections, grouping,
paging, filter and sort controls, menus and the delete dialog (section 0). Translated:

| Desktop | Android |
| --- | --- |
| Permanent, collapsible sidebar | `ModalNavigationDrawer` (compact), rail + the same modal drawer (medium), rail + persistent list pane (expanded), DS §5.2 |
| Previous/Next page buttons in the title bar | Omitted: system back |
| Beta badge, app-update indicator | Omitted (installer-managed, DS §9) |
| Hover card on a row (`AgentSessionOverview`) | "Details" menu item -> F6 sheet |
| Hover card on the logo (Hub status) | KernelChip in the header -> Kernel status sheet (kernel spec) |
| Hover-revealed trash button on a row | Removed; "Delete" lives in the long-press menu and the TalkBack actions |
| Right-click context menu | Long-press `DropdownMenu` at the touch point (voice `SessionRow` mechanics) |
| Cloud session glyph, "Import" | Omitted (no cloud, no import on Android) |

### 2.3 Layout

**Compact (S25U portrait, ~412dp).** `ModalDrawerSheet` (max width 360dp,
`surfaceContainerLow`, DS §5.2), content top to bottom:

```
+--------------------------------------+------+
| (bot) Cline     [(o) Hub]      [srch]|      |  header row, 56dp
|                                      |      |
| (+)  New session                     |      |  NavigationDrawerItem, 56dp
| (cl) Schedules                       |  s   |
| (pz) Customize                       |  c   |
|                                      |  r   |
| Sessions             [fold] [filter] |  i   |  list header, 48dp
| v Pinned                          2  |  m   |  CategorySection header, 48dp
|   Fix login redirect     (o) ^  5m   |      |  session row, 48dp: dot, pin, age
|   Port OpenTUI 0.4.4         ^  3d   |      |
| v Scheduled                       1  |      |
|   > (cl) Daily standup  (o) 4 runs   |      |  schedule group row, 48dp
| v Tasks                          27  |      |
|   Refactor parser        (o)    3h   |      |
|   [ Rename field............ ] (*)   |      |  rename mode replaces a row
|   Update README                 4d   |      |
|   Show more  v                       |      |  TextButton, 48dp
|--------------------------------------|      |
| (A) Ada               Personal  [gear]|     |  footer, 64dp
+--------------------------------------+------+
legend: (o) 8dp status dot   ^ push_pin   (cl) schedule   (pz) extension   [fold] sort toggle
```

Header row: `IconButton` with `ic_cline_logo` (CD "Cline Termux home", tooltip "Home": opens
Home and closes the drawer), "Cline Termux" (`titleMedium`), the KernelChip (DS §4.2) and a
search `IconButton` (`search`, CD "Search sessions", tooltip "Search sessions"; the
tooltip becomes "Search sessions (Ctrl+P)" *(Android adaptation of "Search sessions
(Cmd/Ctrl+P)")* only while a hardware keyboard is attached, the same
`Configuration.keyboard != KEYBOARD_NOKEYS` check as section 4.5, re-read on
configuration change).

Destination items (`NavigationDrawerItem`, `labelLarge`, 24dp icons): "New session"
*(Android adaptation of the desktop row label "Session", aria-label "New")* (`add`, the
desktop `Plus`; tooltip "Start a new session"; selected when `homeActive`), "Schedules"
*(Android adaptation of the row label "Schedule"; "Schedules" is the desktop's tooltip and
settings-section name)* (`schedule`; selected on the Schedules route), "Customize"
(`extension`; tooltip "Customize Cline with plugins, rules, and more"; selected on
Customize). The two renames read better as drawer destination labels; DS §5.2 needs the
same *(Android)* mark (open question 6).

List header (48dp, 16dp start / 4dp end padding): a `TextButton`-styled label "Sessions"
(time mode) or "Projects" (project mode) in `titleSmall` `onSurfaceVariant` (tap opens
S2 and closes the drawer; `onSurface` while S2 is on screen); then the sort toggle and the
filter `IconButton`s (48dp targets, 20dp icons).

Footer (a 1dp `outlineVariant` divider above it): when an account is known, a row with a
32dp `primary` circle holding the initial (first character of the name, upper-cased;
`labelLarge` SemiBold `onPrimary`), name (`labelLarge`; desktop rule: the first word of
`displayName` (`displayName.trim().substringBefore(' ')`), else the part of `email`
before `@`, else the fallback below) and scope (`labelMedium` `onSurfaceVariant`: organisation name or
"Personal"), CD "Account settings", opening Settings > Account; plus a `settings`
`IconButton` (CD "Settings"). The row's TalkBack description is "Account settings, <full
email>" and its long-press tooltip is the email (desktop `title={user.email}`). Without an account: one `NavigationDrawerItem` "Settings"
(`settings`). Name fallback when the account has no display name or email: "Cline"
*(Android; desktop "Cline Desktop")*.

The session list scrolls; header, destination items and the list header are fixed above
it, the footer is fixed below it. When the list is scrolled, a 16dp fade from
`surfaceContainerLow` to transparent overlays its top edge (desktop gradient fade).

**Medium (Tab S7+ portrait ~778dp, S25U split screen).** `NavigationRail` (80dp,
`surface`) + single pane. The drawer content above opens as the same modal drawer from
the rail's `menu` button (CD "Open sessions"):

```
+------+-------------------------------------------+
| [=]  |  (chat / home / pushed screen)            |
| [/+] |                                           |  rail header: menu, FAB "New session" (edit_square)
|      |                                           |
| (ch)*|  * = 6dp unread badge on "Chats"          |
| (cl) |                                           |
| (pz) |                                           |
| (gr) |                                           |
|      |                                           |
| (o)H |  KernelChip as the bottom rail item       |
+------+-------------------------------------------+
legend: [=] menu  [/+] FAB edit_square  (ch) Chats  (cl) Schedules  (pz) Customize  (gr) Settings
```

On medium the modal drawer omits the destination items (the rail has them) but keeps
"New session" so the drawer works on its own; the footer is omitted (Settings is a rail
destination; the account row moves to Settings > Account).

Icon note (reviewed: kept because DS §7.4 assigns them): "New session" is `add` in the
drawer item and app bars (the desktop `Plus`) and `edit_square` on the rail FAB, the M3
compose-FAB idiom. A reviewer asked for one icon everywhere; that is a DS §7.4 decision,
raised in open question 6, and this spec follows the DS until it changes.

**Expanded (Tab S7+ landscape ~1244dp, S25U landscape ~891dp, DeX).** Rail + **list
pane** (360dp; 400dp at ≥1200dp) + detail pane, via `NavigableListDetailPaneScaffold`
(DS §5.2). The list pane is this drawer content minus the destination items, with the
docked search bar (S3) replacing the header's search icon:

```
+------+--------------------------------+-----------------------------------------+
| [=]  | (bot) Cline        [(o) Hub]   |  detail pane: chat/{id}, Home, S2       |
| [/+] | [srch Search all session hist…]|                                         |
|      |                                |                                         |
| (ch) | Sessions        [fold][filter] |                                         |
| (cl) | v Pinned                    2  |                                         |
| (pz) |   Fix login redirect (o) ^  5m |                                         |
| (gr) | v Tasks                    27  |                                         |
|      |   Refactor parser     (o)   3h |                                         |
| (o)H |   ...                          |                                         |
|      | (A) Ada     Personal           |                                         |
+------+--------------------------------+-----------------------------------------+
```

The rail's `menu` button toggles the list pane (CD "Collapse sessions" / "Expand
sessions" *(Android, desktop "Expand sidebar")*). Choosing a row opens the chat in the
detail pane and keeps the list pane. Height-compact windows (<480dp tall) have no list
pane (DS §5.1): they behave like medium. The pane structure and who owns Back are
defined in section 2.9.1.

### 2.4 Row anatomy

**Session row** (DS §4.1 #1): 48dp, `Surface(shape = CircleShape)`, 16dp start / 12dp
end padding. Left to right: leading `schedule` 16dp `onSurfaceVariant` when the session is
scheduled and not nested in a group (CD "Scheduled"); label (`labelLarge`, one line,
ellipsis; the title, or `scheduleRunLabel` when nested); trailing, 6dp apart:
SessionStatusDot (DS §4.2, 8dp in a 24dp box; nothing drawn for neutral states), `push_pin`
fill 16dp `onSurfaceVariant` when pinned (CD "Pinned"), age (`labelMedium`
`onSurfaceVariant`). Selected (active session): `secondaryContainer` container,
`onSecondaryContainer` content. Nested rows indent 16dp and draw a 1dp `outlineVariant`
rail at their start (desktop `border-l ml-4`).

**Schedule group row** (desktop `ScheduleGroupRow`): 48dp. `expand_more` 16dp rotated −90°
when collapsed, `schedule` 16dp (CD "Scheduled"), label (`labelLarge`), trailing dot
(`success.dot` if any run is running, else `info.dot` if any run is unread, else none)
and "N runs" / "1 run" (`labelMedium`). Selected styling when it contains the active run
and is collapsed. Default expansion: expanded only while it contains the active session;
a user toggle is remembered per group id for the process lifetime; opening a run from
elsewhere drops the stored choice for its group (desktop effect).

**Category / project header** (desktop `CategorySection`, `ProjectSection`): 48dp,
`expand_more` 16dp (rotated −90° when collapsed, `ClineMotion` chevron spec), label
`titleSmall` `onSurface`, count `labelMedium` `onSurfaceVariant` end-aligned (categories
only). Semantics: `heading()`, `stateDescription` "Expanded"/"Collapsed", role Button.
Project groups indent their rows 12dp.

### 2.5 Content rules (exact port of the desktop)

1. `filtered` = loaded rows, then the source filter (if any), then `Running` keeps
   `kernelStatus == Running` only.
2. **Time mode.** `pinned` = filtered pinned rows; `scheduled` = not pinned and scheduled;
   `tasks` = the rest. Category headers show only when `pinned` or `scheduled` is
   non-empty; otherwise the body is the flat `tasks` list (no header).
   - Pinned shows all its rows.
   - Scheduled shows `groupScheduledThreads(scheduled)` rows, 30 at a time with its own
     "Show more" (+30, no fetch).
   - Header counts (desktop `CategorySection count`): Pinned = number of pinned rows;
     **Scheduled = `groupScheduledThreads(scheduled).size`, i.e. groups after folding, not
     runs** (`count={scheduledRows.length}`), so a schedule with 4 runs counts 1; Tasks =
     number of task rows in `tasks` (all loaded, not only the visible window).
   - Tasks shows the first `showMoreCount` (initially 30) rows. "Show more" appears when
     `tasks.size > showMoreCount` or (`statusFilter == All` and `mayHaveMore`). A tap
     adds 30 to `showMoreCount` and clears the failure halt; the page-fill loop then calls
     `loadOlderSessions()` (+50) until Tasks has `showMoreCount` rows or history ends. A
     failed fetch halts the loop until the next tap and shows the snackbar "Could not load
     more sessions. Session history is unavailable right now."
   - The Tasks header shows when `tasks` is non-empty or "Show more" is offered.
3. **Project mode.** Groups = `groupThreadsByProject(pinned-first(filtered))`, each listing
   `groupScheduledThreads(group)` rows, 30 visible with "Show more in <label>" (+30). A
   final "Show more" (`statusFilter == All` and `mayHaveMore`) calls `loadOlderSessions()`
   directly.
4. **Empty.** Time mode with `filtered` empty, or project mode with no groups:
   "No sessions found in history." (`bodyMedium` `onSurfaceVariant`, 16dp padding). Shown
   only after a definitive reply (`load == Loaded`); before the first reply the body is
   "Loading session history...".
5. Changing a filter resets every visible count to 30. Changing sort keeps them.
6. Collapsed categories and projects: `rememberSaveable` sets (survive rotation and
   process death; reset on a fresh launch, like the desktop's in-memory state).

### 2.6 Controls

**Sort toggle** (desktop `sortToggle`): one tap flips the mode. The icon shows the mode a
tap switches to: `folder` in time mode, `schedule` in project mode. CD "Sort sessions:
Time" / "Sort sessions: Project" (the current mode). Tooltip "Sorted by time — tap to
group by project" / "Grouped by project — tap to sort by time" *(Android: "click" ->
"tap")*. The mode persists in DataStore `sessions.sidebarSort` *(Android: the desktop
keeps it in memory and resets to time order on every launch; a phone app is relaunched
far more often, so the choice is kept)*.

**Filter menu** (`filter_list`, CD "Filter sessions"): a `DropdownMenu` (min width
200dp) anchored to the button. This follows DS §5.2 item 3 and the desktop's
`DropdownMenuRadioGroup` (agent-sidebar.tsx:630-645); DS §4.3's last row maps the same
control to a `SingleChoiceSegmentedButtonRow`, which is a DS inconsistency raised in open
question 6. For this screen the menu wins.

```
Status                (label, labelSmall, onSurfaceVariant, not focusable)
( ) All               RadioButton items, 48dp
(o) Running
-------------------
Source                (only when at least one source exists)
(o) All sources
( ) Android
( ) CLI
```

Each option is `DropdownMenuItem(leadingIcon = RadioButton(selected, onClick = null),
modifier = Modifier.selectable(selected, role = Role.RadioButton, onClick = ...))`, so
TalkBack reads "All, radio button, selected"; the group labels "Status" and "Source" are
`Text` rows with `semantics { heading() }` and no click. Picking an option closes the menu.

While a non-default filter is active the button uses `IconButtonDefaults.filledTonal`
colours, and its CD becomes "Filter sessions, filtered" *(Android)*.

### 2.7 States

| State | Trigger | Drawer / list pane shows |
| --- | --- | --- |
| First run (no sessions ever) | `load == Loaded`, zero rows, kernel Ready | Header, destinations, "Sessions" header, "No sessions found in history.". Sort and filter buttons stay enabled. |
| Loading | before the first `session.list` reply, no cache | "Loading session history..." in the list area. No skeleton (DS §4.1 #1). |
| Loading with cache | before the first reply, cache present | Cached rows (read-only rules of 1.7) under a "Connecting..." notice. |
| Populated | rows present | As wireframes. |
| Running / streaming | any row `Running`, `Pending`, approval or question flags | Status dots per DS §1.6; rows update in place from events; no reordering while a row's activity time changes by less than 1 minute (avoid jumping under the finger: re-sort at most once every 5 s). |
| Initial load failing | `load == Initial`, `session.list` failed or timed out | Silent retry every 2 s (1.9 item 7); body stays "Loading session history..." (or cached rows under "Connecting..."-style rules). No error notice yet. |
| Error (connected, list failed after a successful load) | reply `ok:false` or timeout (30 s default) after `load == Loaded` on this connection | Keep the last list (live or cache); notice "Could not load sessions" *(Android)* / "Session history is unavailable right now." / "Retry". |
| Offline / kernel down | `KernelState` not Ready | Notice per 1.7 + cached rows (read-only) + caption "Showing the last loaded list."; without a cache only the notice. |
| Permission missing / Termux missing | `PermissionMissing` / `TermuxMissing` | Notice per 1.7, no list (no cache can exist before first setup, or the cached list is shown read-only if it does). |
| Rename mode | F2 started from a row | That row becomes the inline editor (F2). The drawer's drag gesture is disabled while editing (2.8). The drawer sheet pads for the IME and the field is brought into view (F2 step 1). |
| Action pending | `pending[row] != null` | Row disabled (no click, alpha 0.38 on the label and age) **plus the static `warning.dot`** in the dot slot (1.3 "Pending action in the visual"). Together they are the translation of the desktop's `status="pending"` row (yellow dot, disabled). Menu labels "Renaming..." / "Forking..." / "Deleting..." with a 16dp spinner, all items disabled. Other rows are unaffected (1.10). |

### 2.8 Interactions and gestures

| Gesture | Target | Result |
| --- | --- | --- |
| Tap | session row | F1 open; closes the drawer (compact/medium); on expanded opens in the detail pane. Ignored while the row has a pending action. |
| Long-press | session row | Haptic `LongPress`; `DropdownMenu` at the touch point (DS §4.1 #15): "Pin"/"Unpin" (`push_pin`), "Rename" (`edit`), "Fork" (`fork_right`), "Details" (`info`), divider, "Delete" (`delete`, `error` colours). Row stays highlighted (`surfaceContainerHighest`) while the menu is open (voice). |
| Tap | schedule group row | Toggle expansion. |
| Long-press | schedule group row | Nothing (desktop has no group menu). |
| Tap | category/project header | Toggle collapse. |
| Tap | "Show more" / "Show more in <project>" | See 2.5. Shows "Loading..." with a 16dp spinner and is disabled while loading. |
| Tap | list header label | Open S2. |
| Tap | search icon | Close drawer, open S3 (full-screen variant). |
| Tap | KernelChip | Kernel status sheet. |
| Tap | app-bar `menu` (chat/Home, compact) or rail `menu` (medium) | **Primary way to open the drawer.** |
| Horizontal drag start→end over the content | chat root (compact) | Opens the drawer: `ModalNavigationDrawer(gesturesEnabled = chatAtRoot && editing == null && !searchExpanded)`. The M3 1.4 drawer is not edge-gated: with gestures enabled it follows a horizontal drag **anywhere** over the content, and under gesture navigation (the S25U default) a swipe that starts at the screen edge is the **system Back gesture**, not this one. Horizontally scrolling children (code blocks, diff lines, the height-compact composer settings row) consume their own drags first, because nested scroll gives the inner `horizontalScroll` priority; the drawer only gets drags that start on non-scrolling content (message text, empty space). When the drawer is open, gestures are always enabled so a drag closes it. |
| Hardware keyboard | anywhere in the shell | Ctrl+K / Ctrl+P open S3; Ctrl+N starts a new session *(Android, desktop has no binding)*; in the list, Up/Down move focus, Enter opens, Shift+F10 or the Menu key opens the row menu. |

No swipe-to-dismiss on rows: it would collide with the drawer gesture and delete must
always confirm (DS §3.4).

### 2.9 Navigation and back

- Lives in the `AppShell` (DS §5.2), not in the NavHost. The drawer opens from the chat
  or Home app bar `menu` button (primary), from the rail `menu` button (medium), and by a
  content drag on compact (2.8; never from the screen edge, which is system Back under
  gesture navigation). DS §5.2's "by an edge swipe" needs the same correction by its owner
  (open question 6).
- Choosing any destination, row, Home or S2 closes the drawer.
- Back order inside S1 (DS §5.8): open menu -> close; rename mode -> cancel the edit;
  drawer open -> predictive close. On expanded see 2.9.1.
- A scrim tap while renaming in the drawer **cancels** the edit (same as Back) and closes
  the drawer; it does not commit, although the field loses focus (F2 step 2).
- Deleting the open session navigates the chat route to Home (`chat/` without id).
- Entering from a notification (composer-approvals §0.6 owns the intent contract:
  extras `dev.cline.termux.extra.SESSION_ID` / `OPEN_SHEET`, mapped by
  `MainActivity.onNewIntent`; the manifest declares no `cline://` scheme and none is
  needed): the app builds the back stack `chat/` (Home) **under** `chat/{id}`, so Back
  from that chat returns to Home, not out of the app. On expanded the detail NavHost gets
  the same two entries (2.9.1).

#### 2.9.1 Navigation model on expanded

Structure (one owner per Back step, outermost last):

```
AppShell
 ├─ NavigationRail
 └─ NavigableListDetailPaneScaffold(navigator = rememberListDetailPaneScaffoldNavigator<Nothing>())
     ├─ listPane   = SessionsDrawer content (S1, list-pane variant) + docked S3
     └─ detailPane = NavHost(detailNavController, startDestination = "chat/")
                        routes: "chat/" (Home), "chat/{id}", "sessions" (S2)
```

- **Pane roles.** With the list pane shown (default, rail `menu` "Collapse sessions"
  not pressed) the scaffold value is list = Expanded, detail = Expanded: both panes are
  visible and there is **no** pane-level back step (`navigator.navigateBack()` returns
  false because the scaffold value would not change; default
  `BackNavigationBehavior.PopUntilScaffoldValueChange`). The scaffold's own back handler
  is therefore inactive (`navigator.canNavigateBack() == false`) and never intercepts.
- **Rows, hits and "Sessions".** Tapping a row or hit calls
  `detailNavController.navigate("chat/{id}") { popUpTo("chat/") }` (one chat on top of
  Home, never a stack of chats); the header label "Sessions" pushes `sessions` on top of
  whatever the detail pane shows; a row tapped inside S2 pushes `chat/{id}` above
  `sessions`, so Back returns to S2 (3.7).
- **Back order on expanded:** (1) menus, sheets, dialogs, rename, search (DS §5.8 items
  1-6); (2) the detail `NavHost` pops (`chat/{id}` -> `sessions` or `chat/`) with the
  seekable shared-axis transition (DS §5.8 item 9); (3) at `chat/` with nothing open,
  Back is not intercepted and leaves the app (DS §5.8 item 10). The list pane is never
  hidden by Back.
- **List pane collapsed** (rail `menu` pressed): the navigator is created with
  `scaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo())
  .copy(maxHorizontalPartitions = if (listCollapsed) 1 else it.maxHorizontalPartitions)`
  and the app calls `navigator.navigateTo(ListDetailPaneScaffoldRole.Detail)`, so only the
  detail pane shows. Back then (after steps 1-2 above) is the scaffold's **pane back**:
  predictive back reveals the list pane again and clears `listCollapsed`; the next Back
  at `chat/` leaves the app. The rail `menu` button ("Expand sessions") does the same
  without Back. `listCollapsed` is `rememberSaveable`.
- **Handler precedence.** The detail `NavHost`'s built-in back handling
  (navigation-compose 2.10: enabled while its back stack holds more than one entry,
  seekable) is composed inside the pane content, so it registers after the scaffold's
  pane-back handler; the back dispatcher asks the most recently registered enabled
  callback first, so the NavHost pops before pane back runs. No extra `BackHandler` is
  added for this. The Tab S7+ acceptance items check the order.
- **Window class changes** (rotation, unfolding, DeX resize): the detail `NavHost`'s
  back stack is the single source of truth and is shared with the compact/medium
  `NavHost` (same `NavController`, hoisted in `AppShell`), so the same chat stays open.

### 2.10 Accessibility

- Row: `semantics(mergeDescendants = true)`; `contentDescription` = "<title>, <status
  label>[, pinned], <spoken age>", for example "Fix login redirect, Running, pinned, 5
  minutes ago" (status label = DS §1.6 "Label" column, or the pending-action label
  "Renaming..." etc.; omitted for Idle and for neutral states with no dot). The
  standalone "Session status: <s>" form (`cd_session_status`) is reserved for a
  SessionStatusDot shown on its own (DS §4.2), never inside a merged row;
  `onClickLabel` "Open session"; `onLongClickLabel` "Session actions" *(Android)*;
  `selected = true` for the active row; `customActions` = the menu items in menu order
  (omitted when actions are disabled). Nested runs prefix the group name: "Daily standup,
  Run 12, running, 3 hours ago".
- Schedule group: "<label>, scheduled, <N runs>[, running][, unread]", state
  "Expanded"/"Collapsed".
- Section headers are headings; TalkBack heading navigation jumps between Pinned,
  Scheduled, Tasks and project groups.
- The notice is a polite live region; its action button is a separate focus stop.
- The drawer's pane title is "Sessions" *(Android)* (`paneTitle`), announced on open.
- Focus order: header (home, kernel chip, search), destinations, list header (label,
  sort, filter), list, footer.

---

## 3. S2: Sessions screen

### 3.1 Purpose

The full history: every root session with workspace, model, tokens, cost and age;
local search, sort, facet filters; the same row actions as the drawer.

### 3.2 Desktop equivalent

`SessionsView` (`webview/components/views/sessions/sessions-view.tsx`): header with
title, description, "Search" field, Import, sort and filter menus; a 7-column table with
10-row pages and a pager; delete `AlertDialog`. Copied: copy, columns, fallbacks, filter
facets and semantics, "load all history" triggers, usage hydration of visible rows.
Translated: table pages -> infinite list (DS §9); the pager's "N-M of T+" range text is
dropped with the pager (a range label without a pager is not an M3 list idiom) and
replaced by a loading row while more history exists and a plain count at the end
(3.5); Import omitted (feature matrix: none); the dropdown filter -> bottom
sheet; the pager's First/Previous/Next buttons omitted.

### 3.3 Layout

Route `sessions`, pushed (DS §5.2). Voice `PageScaffold`: `MediumTopAppBar` "Sessions"
with back arrow (CD "Back"), `exitUntilCollapsedScrollBehavior`. Actions: sort
`IconButton` (`sort`, CD and tooltip "Sort sessions") and filter `IconButton`
(`filter_list`, CD and tooltip "Filter sessions"; `filledTonal` colours while facets are
selected, as the desktop's `default` variant).

**Compact (S25U portrait).**

```
+------------------------------------------+
| <-                       [sort] [filter] |  MediumTopAppBar (collapses to small)
| Sessions                                 |
|------------------------------------------|
| Recent sessions across clients and       |  page description, bodyMedium
| workspaces.                              |
| [ (srch) Search                    (x) ] |  OutlinedTextField, 56dp, shapes.extraLarge
| [Termux asleep . Start kernel ...      ] |  notice (only when not Ready)
|------------------------------------------|
| (o)  Fix login redirect  ^          5m :|  three-line ListItem, 88dp (M3 three-line)
|      app . anthropic:claude-sonnet-4-5   |
|      12.5k/3.2k . $0.123                 |
|------------------------------------------|
| (!)  Port OpenTUI 0.4.4             3d : |
|      Failed . cline-termux . openai:gpt-5|  failed/approval/question label leads line 2
|      40.1k/2.0k . $1.23                  |
|------------------------------------------|
|  ...                                     |
|   (spinner) Loading...                   |  footer: loading row while mayHaveMore,
|                                          |  "34 sessions" (labelMedium) once exhausted
|                       ( + New session )  |  ExtendedFAB, collapses to icon on scroll
+------------------------------------------+
```

Row (DS §4.1 #11): `ListItem` three-line, **88dp** minimum (M3
`ListItemThreeLineContainerHeight`; DS §3.4 / §4.1 #11 say 72dp, which is the two-line
height, raised in open question 6). `leadingContent` = SessionStatusDot in a 24dp
box. `headlineContent` = title (`bodyLarge`, one line) followed by `push_pin` 16dp when
pinned. `supportingContent` line 1 = `[<status label> · ]<workspace> · <model label>`
(`bodyMedium` `onSurfaceVariant`; the status label is present only for Failed, Approval
required and Question asked, DS §8.1.3, in that state's colour role); line 2 = `<tokens>
· <cost>` (`ClineMono.small`), tokens shown as `-` with a 12dp-wide shimmer block while
`usageLoading`. `trailingContent` = age (`labelMedium`) above an overflow `IconButton`
(`more_vert`, CD "Session actions for <title>"; replaced by a 16dp spinner while the row
has a pending action). The active session's row uses `secondaryContainer`.

**Medium (Tab S7+ portrait).** Same as compact with 24dp gutters; the rail is present, so
the ExtendedFAB is not shown (DS §5.4).

**Expanded (≥840dp; S2 opens in the detail pane, the list pane stays).** Table rows per
DS §4.1 #11:

```
+------+---------------+-------------------------------------------------------------------+
| rail | list pane     | <- Sessions                                       [sort] [filter] |
|      |               | Recent sessions across clients and workspaces.                    |
|      |               | [ (srch) Search                         ]                         |
|      |               | Title            Workspace   Model           Tokens   Cost   Time  |  sticky header
|      |               | (o) Fix login ^  app         anthropic:cl... 12.5k/3.2k $0.123 5m :|  56dp rows
|      |               | (!) Port Open... cline-te... openai:gpt-5    40.1k/2.0k $1.23  3d :|
|      |               |                                   34 sessions                      |
+------+---------------+-------------------------------------------------------------------+
```

Columns: Title weight 3 (dot + title + pin), Workspace 2 (`folder` 16dp + label), Model 2,
Tokens 1, Cost 1, Time 1 (all `bodyMedium`, tokens/cost in `ClineMono.small`,
`onSurfaceVariant` except Title in `onSurface` SemiBold as the desktop's `font-semibold`),
Actions 48dp. Header row `labelLarge` `onSurfaceVariant` on `surfaceContainerLow`,
`stickyHeader`. Cells ellipsize. There is **no per-cell tooltip** (the desktop's `title=`
attribute): long-press on any part of a row is the row menu on every window class (3.7),
and the full workspace path and model id are available in the F6 Details sheet (in the
menu) and in the row's merged `contentDescription` (3.8).

### 3.4 Search, sort, filter

- **Search field** (desktop "Search" input): `OutlinedTextField`, leading `search`,
  placeholder "Search", trailing `close` `IconButton` (CD "Clear search" *(Android)*) when
  non-empty, CD "Search sessions", `ImeAction.Search` (hides the IME). Matches rows whose
  joined lowercase `title, workspace basename, provider, model, workspaceRoot, cwd`
  contains the trimmed lowercase query (no debounce; it is local). This is not S3: it
  filters loaded rows by metadata, S3 searches message text.
- **Sort** menu: "Newest first", "Oldest first" (`DropdownMenuItem`, trailing `check` on
  the selected one *(Android)*). Default Newest. Not persisted (desktop).
- **Filter** sheet: `ModalBottomSheet` titled "Filter sessions" (DS §5.5). First item
  "Clear filters" (`TextButton`, only when something is selected). Then one
  `ListItem` + `Checkbox` per facet string, verbatim and alphabetical:
  `model:<m>`, `pinned:yes`, `provider:<p>`, `status:<s>`, `workspace:<basename>`
  (`ClineMono.small` labels, because they are data). `<s>` is the **desktop's normalised
  vocabulary** (`normalizeDiscoveredStatus`, which `sessionFilterDetails` reads through
  `thread.status`), never the raw Hub value: `completed`, `cancelled` (Hub `aborted` and
  `cancelled`), `failed`, `running`, `idle` (Hub `idle`, `pending` and unknown values).
  So an aborted session yields `status:cancelled`, never `status:aborted`. Port it as
  `SessionFormat.facetStatus(KernelSessionStatus)` with unit-test rows
  `Aborted -> "cancelled"`, `Cancelled -> "cancelled"`, `Pending -> "idle"`,
  `Unknown -> "idle"`, `Running -> "running"`, `Completed -> "completed"`,
  `Failed -> "failed"`, `Idle -> "idle"`. With none: a disabled row "No filters
  available". Selection applies live. Semantics are the desktop's **OR**: a row shows if
  any of its facets is selected.
- **Complete history.** When the query is non-blank, a facet is selected, or the sort is
  "Oldest first", and `mayHaveMore`, the ViewModel runs `loadAllSessions()` (doubling
  `limit` until exhausted), showing a `LinearProgressIndicator` under the app bar. Opening
  the filter sheet also starts it (so facet options cover all history).
- Any change to query, facets or sort scrolls the list to the top (desktop `setPage(0)`).

### 3.5 Paging

Infinite scroll replaces pages: when the last visible index is within 5 of the end,
`mayHaveMore`, no complete-history requirement, and nothing is loading,
`loadOlderSessions()` (+50). The footer item (48dp, centred, `labelMedium`
`onSurfaceVariant`) is:

- while `mayHaveMore` and loading or about to load: a 16dp spinner + "Loading...";
- after a failed fetch: the snackbar "Could not load more sessions. Session history is
  unavailable right now." and a footer `TextButton` "Show more" *(reused desktop label)*
  that retries;
- once history is exhausted (`!mayHaveMore`): the plain count `plurals sessions_count`
  "%d session" / "%d sessions" *(Android)* of `totalFiltered`.

`mayHaveMore` is a loading affordance, never text. The desktop's "N-M of T+" pager text is
not used (3.2).

List padding: `contentPadding = PaddingValues(bottom = 56.dp (ExtendedFAB) + 16.dp +
bottomSafe)` on compact, where the FAB shows; `bottomSafe` alone on medium/expanded (no
FAB, DS §5.4). The last row and the footer therefore clear the ExtendedFAB and the
gesture handle.

### 3.6 States

| State | Shows |
| --- | --- |
| First run (zero sessions, Ready) | DS EmptyState: `chat_bubble` 48dp, title "No sessions yet.", body "Start a new session to see it here." *(Android)*, `FilledTonalButton` "New session" (opens Home). |
| Loading (no reply yet) | 3 skeleton rows (DS §4.2 Loading skeleton), no empty copy. A11y: one polite announcement "Loading session history...". |
| Populated | Rows. |
| Filtered empty | "No sessions match the current filters." (`bodyMedium` `onSurfaceVariant`, centred, 32dp top padding) + `TextButton` "Clear filters" when facets are selected. |
| Running / streaming | Live dots and tokens per 1.5 step 3; rows re-sort at most every 5 s. |
| Refreshing | `PullToRefreshBox` indicator (voice). Pull is disabled when the kernel is not Ready. |
| Error (list failed) | Keep rows; notice "Could not load sessions" / "Session history is unavailable right now." / "Retry". |
| Offline / kernel down / permission missing | Notice per 1.7; cached rows read-only with the caption; tokens and cost "-" for rows whose usage is not cached; the ExtendedFAB stays. |
| Rename mode | Row headline becomes the dense rename field of F2 step 1 (CD "Rename <title>") with trailing `IconButton`s `check` (CD "Save title"; disabled only when the field is blank or the rename is pending, spinner while renaming; an **unchanged** title stays enabled and commits as a no-op that just closes the editor, desktop S2 `!editingTitle.trim()`) and `close` (CD "Cancel rename"); supporting lines stay. Expanded: the field spans Title + Workspace columns. IME: while `editing != null` the `PageScaffold` uses `contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime)` and the field calls `BringIntoViewRequester.bringIntoView()` once the IME inset has settled (`WindowInsets.isImeVisible` turned true), so a row in the lower half stays visible above the keyboard. |
| Delete confirm / deleting | F5 dialog. |
| Details | F6 sheet. |

### 3.7 Interactions, navigation, back

- Tap row: F1 open (pushes `chat/{id}` on compact/medium; on expanded pushes `chat/{id}`
  above `sessions` in the detail pane's `NavHost`, 2.9.1, so Back returns to S2). Ignored
  while pending.
- Long-press row or tap overflow: menu "Pin"/"Unpin", "Rename", "Fork", "Details", divider,
  "Delete" (desktop order plus Details). Long-press opens at the touch point; overflow
  anchors to the button.
- Entered from: the drawer/list-pane header label "Sessions"/"Projects" (the only entry,
  as in the desktop). Left by back or the app bar arrow.
- Back order: menu/sheet/dialog first; rename mode cancels; then pop to the previous
  route with the shared-axis-X seekable transition (DS §6). The search field never
  consumes back (clearing it is the `close` button).

### 3.8 Accessibility

- Row: merged; `contentDescription` "Open session: <title>, <status label>[, pinned],
  <workspace>, <model label>, <tokens spoken as "12.5 thousand input, 3.2 thousand
  output tokens">, <cost>, <spoken age>" *(Android extension of the desktop's sr-only
  "Open session: ")*. Custom actions as S1.
- Expanded table: header cells are `heading()`; each row is one focus stop (merged), so
  TalkBack does not read 7 cells separately.
- Facet checkboxes: `toggleableState`, label read as written.

---

## 4. S3: Session search

### 4.1 Purpose

Full-text search across every session's messages (FTS5 index in the Hub), opening the
session of a hit.

### 4.2 Desktop equivalent

`SessionCommandBar` (`webview/components/session-command-bar.tsx`), a `CommandDialog`
opened by the sidebar search button and Cmd/Ctrl+P. Copied: copy, debounce, limit,
timeout, batching, hit layout. Translated: command dialog -> M3 1.4
`SearchBar` + `ExpandedFullScreenSearchBar` (compact, medium) or a docked `SearchBar`
in the list pane (expanded) (DS §5.3).

### 4.3 Layout

**Compact / medium** (full-screen expanded search bar over everything, status bar
inset kept):

```
+------------------------------------------+
| [<-] Search all session history…    [x]  |  input field (56dp), auto-focused, IME open
|------------------------------------------|
| Session history                          |  group heading, titleSmall onSurfaceVariant
| User   Fix login redirect                |  role (labelMedium) + title (bodyLarge, 1 line)
| ...the [redirect] loop happens when the  |  snippet, bodyMedium, 2 lines, match in SemiBold
| callback URL lacks the...                |
| ~/projects/app                           |  workspace root, labelMedium, 1 line
|------------------------------------------|
| Assistant  Port OpenTUI 0.4.4            |
| ...                                      |
|        Showing 15 of 50 results          |  labelMedium, centred
+------------------------------------------+
```

**Expanded:** a docked `SearchBar` at the top of the list pane (below the header row),
placeholder "Search all session history…". Focusing it expands a results dropdown of the
list pane's width and up to 70 % of the window height, same content as above; the session
list stays underneath. Selecting a hit opens it in the detail pane and collapses the
search.

Hit item: `ListItem`-like custom row, min 88dp, 16dp gutters, 12dp vertical padding;
role (`labelMedium` `onSurfaceVariant`, capitalised: "User", "Assistant", "Session",
"Tool"...), 8dp, title (`bodyLarge` `onSurface`, one line); snippet (`bodyMedium`
`onSurfaceVariant`, max 2 lines, ellipsis): bracketed matches are rendered without the
brackets as `SemiBold` `onSurface` spans *(Android: the desktop prints the brackets)*;
workspace root (`labelMedium` `onSurfaceVariant`, one line, middle-ellipsis, LTR).

### 4.4 Behaviour

- Entry: drawer/list-pane search icon or docked bar; Ctrl+K / Ctrl+P anywhere in the shell
  (DS §5.7). The query starts empty each time (desktop resets on close).
- Typing: each change clears hits, sets `Searching` if non-blank; after **180 ms** of no
  typing, `session.search {query: trimmed, limit: 50}` with `timeoutMs = 3000`. A newer
  query cancels the older request's result (`cancelled` flag, desktop).
- Results: `title = formatSessionSearchTitle(hit.title)` (240 chars),
  `snippet = formatSessionSearchPreview(hit.role, hit.snippet)` (480 chars), shown in the
  Hub's order (score, then `startedAt` desc). Never re-ranked or re-filtered.
- Reveal 15 at a time; when the list is within 120dp of its end, reveal 15 more. While
  more remain: footer "Showing <visible> of <total> results".
- Select hit: collapse search (and close the drawer), F1 open `hit.sessionId`. If the
  session is gone (`session_not_found` from `session.get`): snackbar "This session no
  longer exists." *(Android)*, remove it from the repository.
- IME: `ImeAction.Search` runs the query immediately (skips the debounce) and hides the
  IME; scrolling results hides the IME.

### 4.5 States and copy

| Phase | Shows |
| --- | --- |
| Idle (blank query) | Centred `bodyMedium` `onSurfaceVariant`: "Search messages, commands, errors, and file paths." |
| Searching | 16dp spinner + "Searching sessions…" centred, 32dp vertical padding. |
| Results | Heading "Session history" + hits. |
| No results | "No matching session history." and, below it in `bodySmall`, "Sessions that are still running may not appear until they end." *(Android; the index refreshes on session end, Corrections)*. |
| Failed (timeout, `ok:false`, socket closed) | "Search is unavailable right now." *(Android)* + `TextButton` "Retry". The desktop silently shows nothing; the app says so because a down kernel is common on a phone. |
| Kernel down / permission missing | The section 1.7 notice in place of results; the field stays editable; the query runs when the kernel becomes Ready. |
| Hardware keyboard attached (`Configuration.keyboard != KEYBOARD_NOKEYS`) | Footer bar: "Navigate with ↑↓ and open with ↵" and a key chip "Ctrl+P" *(Android: desktop "Cmd/Ctrl+P")*; Up/Down move the highlighted hit, Enter opens it. Hidden on touch-only devices. |

### 4.6 Data binding

```kotlin
data class SearchHit(
    val sessionId: String, val documentId: String, // LazyColumn key = documentId
    val role: String,                               // capitalised for display
    val title: String, val snippet: AnnotatedString, val workspaceRoot: String,
)
```

`SessionSearchViewModel` debounces with `snapshotFlow { query }.debounce(180)` +
`mapLatest`, calls `SessionsRepository.search(query, 50)`.

### 4.7 Navigation and back

Not a NavHost route: an `AppShell` overlay state (`searchExpanded`), so back is the M3
search bar's own predictive collapse (DS §5.8 item 6). Back from an open hit's chat
returns to wherever the user was before search (the search is not reopened).

### 4.8 Accessibility

- The expanded bar announces "Search session history" (pane title) and keeps focus in the
  field.
- Each hit: merged; `contentDescription` "<role>: <title>. <snippet without brackets>.
  <workspace>"; `onClickLabel` "Open session".
- Phase changes ("Searching sessions…", the result count "50 results" *(Android plural
  `search_results_count`)*, "No matching session history.") are announced politely.

---

## 5. Flows

### F1. Open and restore (resume) a history session

Entered from: S1 row, S2 row, S3 hit, a notification (intent extras of
composer-approvals §0.6, mapped to `chat/{id}` with Home beneath it, so Back returns to
Home, 2.9), the Schedules run history, and after F4 (open the fork).

1. `SessionsRepository.markSeen(id)` (clears unread, stores `lastSeenSequence`).
2. Navigate to `chat/{id}` (compact/medium push; expanded detail pane).
3. Chat ViewModel (chat spec) loads: `session.get {sessionId, includeSnapshot:true}` and
   `session.messages {sessionId}` in parallel, then `session.attach {sessionId}`. Events
   come from the global subscription, routed by `sessionId`; no per-session subscribe is
   needed (Corrections: every session event reaches the global stream).
   - `session.get` -> `session_not_found` ("Unknown session: <id>"): pop back, snackbar
     "This session no longer exists." *(Android)*, remove the row.
4. **Live vs. persisted.** Record status `running`, `pending` or `idle` with the app as a
   participant means live. Any other status (`completed`, `aborted`, `failed`) may be a
   persisted, non-live session (after a daemon restart every session is non-live). The
   transcript renders either way.
5. **Restore on first send** (recovery matrix: `session_not_found` on send): the user's
   prompt goes out as `session.send_input`. If it fails with `session_not_found`:
   - The composer keeps the draft and shows "Restoring session..." *(Android)* with the
     send button replaced by a 20dp spinner; the session's visual becomes Starting (pulse).
   - `session.create {workspaceRoot, cwd, sessionConfig:{sessionId:<id>, providerId:
     metadata.provider, modelId: metadata.model, mode, enableTools:true,
     enableSpawnAgent:true, enableAgentTeams:true, checkpoint:{enabled:true}},
     initialMessages:<the session.messages result>, metadata:{source:'android',
     interactive:true, provider, model}, toolPolicies:<app default>, runtimeOptions:
     {clientContributions:[{kind:'toolExecutor', executor:'askQuestion',
     capabilityName:'tool_executor.askQuestion'}]}}` with `timeoutMs = 120000`
     (fixture 05: same `sessionId` back; `title`, `pinned` kept).
   - Then resend the same `session.send_input` once. On success the draft clears as
     usual.
   - `hub_draining` on the restore `session.create` or on the resend (both are in the
     Hub's `DRAIN_REFUSED_COMMANDS`): keep "Restoring session...", wait for
     `hub.drain_changed {draining:false}` up to 30 s, retry once, then the failure below.
   - Failure: the chat shows an error message (DS §4.1 #5) "Could not restore this
     session." *(Android)* + the kernel's message, keeps the draft, and offers "Retry"
     *(Android)*. If the provider is no longer configured (`run.failed`/`session.notice`
     `api_error`), the credential actions "Sign in to Cline" / "Open API providers" apply
     (chat spec).
6. **Interrupted runs.** `flags.interrupted` is set by the client-side rule of 1.3 (the
   app saw `run.started` for its own `session.send_input`, the socket closed before a
   terminal `run.*`, and after reconnect a new `hubId`, the replayed shutdown
   `session.updated` pair or a `run.completed {reason != "completed"}` shows the turn
   did not finish). The pending `session.send_input` itself fails with the app's
   `hub_connection_closed`, which the composer does **not** report as a send error while
   the session is a suspect. When the flag is set for the open session, the chat shows
   the notice row "The kernel restarted. Send your message again to continue."
   *(Android)* (SessionNotice row, `autorenew` icon) and the turn's prompt (kept with the
   suspect, 1.3) is placed back into the composer when the composer is empty (recovery
   matrix: "mark the run interrupted and offer resend"). The resend normally hits
   `session_not_found` and goes through step 5. The Hub's own `run.interrupted` event is
   not relied on: it exists only for `run.enqueue` runs (section 0).
7. Checkpoint "Restore" (the message-level undo) is **not** part of this flow: it needs
   the checkpoint client contribution the app does not register (Corrections); the chat
   spec ships it disabled until then.

### F2. Rename

Entry: S1 row menu or TalkBack action, S2 row menu, the conversation title (chat spec
reuses this flow and the inline editor in the app bar; the desktop renames by clicking
the header title, and the header overflow's "Rename" is a DS §4.2 addition *(Android)*).

1. **Editor.** S1: the row becomes a dense outlined field. The high-level
   `OutlinedTextField` cannot be 48dp (it enforces `TextFieldDefaults.MinHeight` 56dp and
   16dp vertical padding, which would make the list jump), so build it as
   `BasicTextField(modifier = Modifier.heightIn(min = 48.dp), decorationBox = { inner ->
   OutlinedTextFieldDefaults.DecorationBox(..., contentPadding = PaddingValues(horizontal
   = 16.dp, vertical = 8.dp), container = { OutlinedTextFieldDefaults.Container(...,
   shape = shapes.extraLarge) }) })` with `labelLarge` text; it grows with font scale
   (§8.5) instead of clipping. Prefilled with the current title, cursor at the start
   (desktop `setSelectionRange(0, 0)`), auto-focused with the IME opened, `singleLine`,
   `ImeAction.Done`; a pasted newline becomes a space (voice). S2: the same field in the
   headline + Save/Cancel (3.6).
   **IME.** The drawer sheet is `ModalDrawerSheet(windowInsets =
   WindowInsets.systemBars.union(WindowInsets.ime))` (its default is `systemBars` only),
   so the list area shrinks above the keyboard; the field holds a
   `BringIntoViewRequester` and calls `bringIntoView()` once `WindowInsets.isImeVisible`
   turns true, so a row in the lower half of the list (for example the 25th) is never
   covered. The list pane on expanded applies `Modifier.imePadding()` to its
   `LazyColumn` for the same reason.
2. **Commit** on IME Done, Enter (hardware), the S2 `check`, or focus loss inside the
   drawer (desktop `onBlur` commits; S1 only). **Cancel** on Back (DS §5.8 item 4),
   Escape, S2 `close`, or a **scrim tap** that closes the drawer (the scrim tap also blurs
   the field; it is treated as Back, not as blur, so closing the drawer never saves by
   accident). The drawer's drag gesture is off while editing (2.8).
3. **Normalise:** `normalizeTitle(input).trim()`. Unchanged -> close the editor, no call.
   Blank (S1 only; S2's Save is disabled for blank, desktop S2) -> **clear the custom
   title**, as the desktop does: the Hub's `handleSessionUpdate` passes `title: ""`
   through and `PersistenceService.updateSession` normalises it to nothing and deletes
   `metadata.title` (section 0), so the row falls back to the first prompt (1.4 "Title").
   Blank when the row has no custom title -> no call.
4. **Call:** add `pending[id] = Rename` and arm the annotation override (1.3 rule 5);
   `session.get {sessionId}` -> `M = session.metadata`; set:
   `session.update {sessionId, title: T, metadata: M + {"title": T}}`, or to clear:
   `session.update {sessionId, title: "", metadata: M - "title"}`. The field is disabled
   with a 16dp trailing spinner meanwhile.
5. **Success** (`updated: true`): close the editor; the `session.updated {session}` event
   (or the reply's `session`) updates every surface. No snackbar.
6. **Failure:** keep the editor open with the typed text; snackbar "Rename failed. <kernel
   message or "The session title could not be updated.">". (`session.update` is not
   drain-refused, so `hub_draining` cannot occur here.)
7. Kernel not Ready: "Rename" is disabled (1.7).
8. **Create-time title kept** *(Android, trial row 16)*. Core 0.0.91 writes a brand-new
   session's record only at its first prompt and titles it after that prompt, over the
   create's `metadata.title` (`createRootSessionWithArtifacts` → `resolveMetadataWithTitle`;
   seeded sessions such as forks are written at their create and keep their title). When
   this app sends the first prompt into a session that already carries a title, then right
   after that run ends (about 1 s later) it reads the record and, if the title is now the
   one derived from the stored first prompt (`metadata.prompt`, core
   `deriveTitleFromPrompt`), renames it back with steps 4-5, silently (`CreateTitleKeeper`).
   One retry 3 s later if the read or the rename fails, then nothing; a title that is
   neither the create's nor the prompt's (renamed meanwhile) is left alone. The app's own
   first send carries no title, so this meets sessions another client created with one.

### F3. Pin / Unpin

Entry: menus and TalkBack actions in S1/S2, the conversation header overflow *(Android:
the desktop header has no Pin; DS §4.2 ConversationHeader adds it)*.

1. Optimistic: flip `pinned` locally at once (the row moves to/from "Pinned"; its age and
   its position among unpinned rows do not change, 1.3 rule 5). Arm the annotation
   override.
2. `session.get {sessionId}` -> `M`; `session.update {sessionId, metadata: M +
   {"pinned": true}}` to pin, or `M - "pinned"` to unpin (desktop writes `null`/omits).
3. Success: then `session.list` refresh after 50 ms (desktop `scheduleRefresh`).
4. Failure: roll back; snackbar "Pin failed. <message or "The session could not be
   updated.">" / "Unpin failed. …".
5. No pending state, no confirmation, no snackbar on success.

### F4. Fork

Entry: menus in S1/S2 and the conversation header overflow *(Android: DS §4.2
ConversationHeader addition; the desktop header menu holds only "Delete session")*.
Decided A16 and A19 (2026-10-08): no message-row fork (the desktop's row entry) and no
`/fork` in the slash popup, one place per function; the fork never cuts the history and
never writes `fork.beforeRunCount`.

1. `pending[source] = Fork` (menu label "Forking...").
2. `session.get {sessionId}` -> source record `R`; `session.messages {sessionId}` -> `msgs`.
   Empty `msgs`: fail with "Cannot fork an empty session." (CLI).
3. Title: `deriveForkSessionTitle` port: base = normalized `R.metadata.title`, else the
   normalized prompt, else the first user then assistant text in `msgs`, else "Untitled";
   collapse whitespace; truncate to 120 − 7 chars; append " (fork)".
4. Metadata: `R.metadata` minus `fork`, `title`, `usage`, `aggregateUsage`, `totalCost`,
   `aggregatedAgentsCost`, `messagesPath`, `pid`, `prompt`, `sessionHistoryOrigin`,
   `scheduleId`, `scheduleName`, `scheduleExecutionId`, `scheduleRunNumber`, `pinned`;
   plus `{source:'android', interactive:true, provider, model, title, fork:
   {forkedFromSessionId: R.sessionId, forkedAt: <ISO now>, source: 'android'}}`. (The
   fork is a fresh task: not pinned, not a scheduled run; see open questions.)
5. `session.create {workspaceRoot: R.workspaceRoot, cwd: R.cwd, sessionConfig:{providerId:
   R.metadata.provider, modelId: R.metadata.model, mode: R.metadata.mode ?: 'act',
   enableTools:true, enableSpawnAgent:true, enableAgentTeams:true, checkpoint:{enabled:
   true}}, initialMessages: msgs, metadata, toolPolicies:<app default>, runtimeOptions:
   {clientContributions:[askQuestion]}}`, `timeoutMs = 120000`. `apiKey` omitted (feature
   matrix). `hub_draining` (session.create is drain-refused): keep "Forking...", wait for
   `hub.drain_changed {draining:false}` up to 30 s, retry once, then step 7.
6. Success: clear pending, refresh the list (50 ms), F1-open the new session (it arrives
   by `session.created` first, fixture 02). Forking a running source does not stop it; the
   fork holds the messages persisted at that moment.
7. Failure: snackbar "Fork failed. <message or "The session could not be forked.">".

### F5. Delete

Entry: S1/S2 menus and TalkBack actions, header overflow "Delete session" (the desktop
header's only item).

1. Dialog (DS §4.1 #16, voice `DeleteSessionDialog` mechanics), compact and expanded alike:

```
+--------------------------------------+
| Delete session?                      |  AlertDialog title (M3 default headlineSmall)
|                                      |
| This removes "Fix login redirect"    |  bodyMedium onSurfaceVariant
| from local history.                  |
| The running task will be stopped.    |  only when the session is Running/Pending (Android)
|                                      |
|                  Cancel     Delete   |  TextButton, TextButton(error content)
+--------------------------------------+
```

   - Title "Delete session?"; text `This removes "<title>" from local history.` (title =
     first line, ellipsized at 120 graphemes with "…" *(Android cap)*); when the session is
     Running or Pending append " The running task will be stopped." *(Android; the daemon
     stops a live session before deleting it)*.
   - Buttons: "Cancel" (dismiss, default focus), "Delete" (`TextButton` with `error`
     content, DS §4.1 #16; M3 dialogs use text buttons). The desktop uses a filled
     destructive action and DS §4.3 says filled `Button(error)` in dialogs, so the DS
     contradicts itself; raised in open question 6, this spec follows §4.1 #16. Never
     auto-confirm.
2. Confirm: `pending[id] = Delete`; the confirm button shows a 16dp spinner + "Deleting...";
   both buttons disabled; outside tap and back are ignored (desktop blocks dismissal
   while deleting).
3. `session.delete {sessionId}` (no `deleteCheckpointRefs`).
   - `deleted: true` -> success.
   - `deleted: false` -> `session.get {sessionId}`: `session_not_found` -> success
     (`docs/08-hub-fixtures.md`, "`session.delete`"); otherwise failure "The session could not be removed from local history."
4. Success: close the dialog; remove the row everywhere; drop its unread entry, cursor,
   usage cache and open approval/question flags; cancel its notifications; if it is the
   open chat, navigate to Home. Refresh the list. No undo (the kernel cannot restore a
   deleted session), so no snackbar action.
5. Failure: close the dialog; snackbar "Delete failed. <message or "The session could not
   be removed from local history.">". The app follows the desktop **sidebar** (dialog
   closes) on both S1 and S2; the desktop Sessions view keeps its dialog open on failure
   (`if (deleted) setDeleteCandidate(null)`). One behaviour everywhere is simpler, and the
   snackbar carries the reason. `session.delete` is not drain-refused.

### F6. Session details sheet

Entry: "Details" in the S1/S2 menus; tapping the conversation title (chat spec).

`ModalBottomSheet` (DS §4.1 #13): title = first line of the session title
(`titleMedium`, wraps); then the two-column grid of `getSessionOverviewItems`, rows
omitted when empty, in this order with these labels:

| Label | Value |
| --- | --- |
| "Schedule" | `scheduleName` |
| "Run" | `runNumber` |
| "Workspace" | workspace label (1.4); long-press copies the full `workspaceRoot` |
| "Branch" | `gitBranch` |
| "Provider" | `provider` |
| "Model" | `model` |
| "Tokens" | `formatTokenCount(in, out)` |
| "Cost" | `formatCostUsd` |
| "Source" | `getSessionSourceLabel(source)` *(Android: the desktop hover card prints the raw `thread.source`, e.g. "hub-schedule"; the label is the filter menu's)* |

Values in `ClineMono.small`; long-press copies the value (`ClipboardManager`; snackbar
"Copied" *(Android)* only below API 33, where the system shows no clipboard confirmation).
Loading: when usage is unknown **and the session is live** (1.5 step 2) the sheet calls
`session.get` and shows a `LinearProgressIndicator` under the title until it returns.
Tokens and Cost rows are omitted when the record carries no usage (a persisted session
without `metadata.usage`); no read is made for those. Kernel down: cached values, rows
that need the kernel omitted.

---

## 6. Recovery matrix: what these screens surface

| Kernel contract / fixture case | Where it shows | Behaviour |
| --- | --- | --- |
| ECONNREFUSED on 25463 / `HubDown` | S1, S2, S3 notice | "Termux asleep" + "Start kernel" -> `cline hub start` (8-15 s; notice turns to "Connecting..."). Cached list read-only. |
| Upgrade 401, token rotation | (kernel layer) | Notice "Reconnecting..." during re-read; then a fresh `session.list`. |
| RUN_COMMAND `err != -1` mentioning `allow-external-apps` or permission | notice | "Termux permission needed" -> Kernel setup. |
| Termux not resolvable | notice | "Termux is not installed" -> Kernel setup. |
| Port older than v3.0.68-termux.2 | notice | "Kernel update required" -> Kernel status sheet. |
| `hub_draining` reply | F1 (restore `session.create`, resend `session.send_input`), F4 (`session.create`) | Keep the pending state, wait for `hub.drain_changed {draining:false}`, retry once within 30 s, then the flow's failure. Never on F2/F3/F5: `session.update` and `session.delete` are not in `DRAIN_REFUSED_COMMANDS`. |
| `hub_connection_closed` (socket dropped mid-command) | any action | Action fails with its snackbar, description "Cline Hub is not connected."; reads (list, search) retry after reconnect automatically. |
| `session_not_found` on send | F1 | Restore via `session.create {sessionId, initialMessages}`, then resend. |
| `session_not_found` on get/open | F1, S3 | "This session no longer exists.", row removed. |
| Hub restart mid-turn (kernel contract's `run.interrupted {reason:"hub_restart"}` row; the Hub emits that event only for `run.enqueue` runs, which the app never uses) | F1 (chat), row flags | Client-side rule (1.3 "Interrupted rule"): own `run.started` without a terminal `run.*` before the socket closed, then a new `hubId`, the replayed shutdown `session.updated` pair or `run.completed {reason != "completed"}`. Notice row + prompt back in the composer; the row shows the replayed status (Completed/Cancelled) afterwards. |
| Daemon restart replay (`session.updated` shutdown pairs, `run.completed {reason:"disposed"}`) | S1/S2 | Statuses update to Completed/Cancelled; no unread marks; no "task completed" notifications. |
| `approval.requested` re-issued on subscribe | S1/S2 dots | Dedupe by `approvalId`; the dot stays "Approval required" until `approval.resolved` or a terminal `run.*`. |
| `capability.requested` cancelled because the target unregistered, or answered by another client | S1/S2 dots | `capability.resolved {requestId, ok, cancelled?}` with the same `requestId` clears the Question flag; a terminal `run.*` clears it as a fallback. |
| Long reconnect gap (thousands of events since the cursor) | S1/S2 | Replay budget 1.2.1: subscribe from `lastSequence - 2000`; statuses from `session.list`; no unread inferred for the gap. |
| `session.list` fails before anything loaded | S1/S2 | Silent 2 s retry (1.9 item 7), loading copy stays. |
| `session.search` empty for live sessions | S3 | The "still running" hint under "No matching session history.". |
| `session.delete` -> `deleted:false` | F5 | Verify with `session.get`. |
| `session.update` wiping metadata | F2, F3 | Always read-modify-write with the current `session.metadata`. |
| Same clientId on a new socket before the old closed | (kernel layer) | Not surfaced here; the list just reloads after reconnect. |
| A RUN_COMMAND that never returns (`hub start` hung, 60 s) | notice | Notice becomes "Cline Hub is not connected." with the doctor summary as supporting text (kernel spec) and "Retry". |

---

## 7. Motion

All from DS §6: chevrons (`Short4`, `Standard`), disclosure of sections and schedule
groups (`expandVertically` + `fadeIn`), status-dot pulse for Starting/Connecting only,
skeleton shimmer, shared-axis-X push/pop for S2, M3 defaults for drawer, sheets, menus,
dialogs and search bar. List changes use `Modifier.animateItem()` (fade + placement,
M3 defaults) so pinned rows move between sections visibly. Reduced motion: no pulse, no
shimmer, instant placement.

---

## 8. Accessibility summary (MUST, in addition to DS §8)

1. Every icon-only control has the CD listed in this spec; tooltips on long-press.
2. Status is always spoken ("Session status: running" or the row description), never
   colour only; Failed / Approval required / Question asked also appear as text on S2.
3. Every long-press menu is mirrored by `customActions`; disabled actions are omitted, not
   announced as disabled.
4. 48dp targets for rows, headers, "Show more", menu items, chips and icon buttons.
5. Layouts hold at font scale 2.0 × in-app size 20: rows grow (`heightIn`), the S2 row's
   supporting lines wrap to two lines each at most, the expanded table falls back to the
   three-line `ListItem` layout when the Title column would be under 160dp.
6. Paths, model ids, facets and token values are LTR and read as written.

---

## 9. Strings (`res/values/strings.xml`)

| Key | English (exact) | Origin |
| --- | --- | --- |
| `sessions_drawer_title` | Cline Termux | DS §5.2 |
| `sessions_drawer_pane` | Sessions | *(Android)* pane title |
| `cd_cline_home` | Cline Termux home | *(Android)* |
| `tooltip_home` | Home | desktop |
| `cd_search_sessions` | Search sessions | desktop |
| `tooltip_search_sessions` | Search sessions | desktop (without the shortcut) |
| `tooltip_search_sessions_kbd` | Search sessions (Ctrl+P) | *(Android adaptation of "Search sessions (Cmd/Ctrl+P)")*, hardware keyboard only |
| `nav_new_session` | New session | *(Android adaptation of the desktop row label "Session")*, DS §5.2 |
| `tooltip_new_session` | Start a new session | desktop |
| `nav_schedules` | Schedules | *(Android adaptation of the row label "Schedule"; the desktop tooltip reads "Schedules")*, DS §5.2 |
| `nav_customize` | Customize | desktop |
| `tooltip_customize` | Customize Cline with plugins, rules, and more | desktop |
| `nav_settings` | Settings | desktop |
| `cd_account_settings` | Account settings | desktop |
| `account_scope_personal` | Personal | desktop |
| `account_name_fallback` | Cline | *(Android; desktop "Cline Desktop")*, used only when neither `displayName` nor `email` yields a name |
| `sessions_header_time` | Sessions | desktop |
| `sessions_header_project` | Projects | desktop |
| `cd_sort_sessions_mode` | Sort sessions: %1$s | desktop |
| `sort_mode_time` | Time | desktop |
| `sort_mode_project` | Project | desktop |
| `tooltip_sorted_by_time` | Sorted by time — tap to group by project | *(Android adaptation)* |
| `tooltip_grouped_by_project` | Grouped by project — tap to sort by time | *(Android adaptation)* |
| `cd_filter_sessions` | Filter sessions | desktop |
| `cd_filter_sessions_active` | Filter sessions, filtered | *(Android)* |
| `filter_status` | Status | desktop |
| `filter_all` | All | desktop |
| `filter_running` | Running | desktop |
| `filter_source` | Source | desktop |
| `filter_all_sources` | All sources | desktop |
| `section_pinned` | Pinned | desktop |
| `section_scheduled` | Scheduled | desktop |
| `section_tasks` | Tasks | desktop |
| `plurals schedule_runs` | %d run / %d runs | desktop |
| `schedule_run_number` | Run %1$d | desktop |
| `schedule_run_fallback` | Run | desktop |
| `cd_scheduled` | Scheduled | desktop |
| `cd_pinned` | Pinned | desktop |
| `show_more` | Show more | desktop |
| `show_more_in` | Show more in %1$s | desktop |
| `loading_ellipsis` | Loading... | desktop |
| `sessions_loading` | Loading session history... | desktop |
| `sessions_empty_drawer` | No sessions found in history. | desktop |
| `sessions_load_failed_title` | Could not load sessions | *(Android)* |
| `sessions_unavailable` | Session history is unavailable right now. | desktop |
| `load_more_failed_title` | Could not load more sessions | desktop |
| `action_retry` | Retry | *(Android)* (reused app-wide) |
| `showing_cached_list` | Showing the last loaded list. | *(Android)* |
| `kernel_connecting` | Connecting... | DS §1.6 |
| `kernel_reconnecting` | Reconnecting... | DS §1.6 |
| `kernel_not_connected` | Cline Hub is not connected. | desktop |
| `kernel_termux_asleep` | Termux asleep | DS §1.6 |
| `kernel_start_to_continue` | Start kernel to continue. | DS §1.6 |
| `action_start_kernel` | Start kernel | DS §4.1 #6 |
| `kernel_termux_missing_title` | Termux is not installed | *(Android)* |
| `kernel_termux_missing_body` | Install Termux to run the Cline kernel. | *(Android)* |
| `kernel_permission_title` | Termux permission needed | *(Android)* |
| `kernel_permission_body` | Allow Cline to run commands in Termux. | *(Android)* |
| `kernel_update_title` | Kernel update required | *(Android)* |
| `kernel_update_body` | Cline kernel v3.0.68-termux.2 or later is needed. | *(Android)* |
| `action_set_up` | Set up | *(Android)* |
| `action_details` | Details | *(Android)* |
| `cd_open_sessions` | Open sessions | DS §5.2 |
| `cd_collapse_sessions` / `cd_expand_sessions` | Collapse sessions / Expand sessions | *(Android)* |
| `cd_chats_unread` | Chats, unread sessions | *(Android)* |
| `menu_pin` / `menu_unpin` | Pin / Unpin | desktop |
| `menu_rename` / `menu_renaming` | Rename / Renaming... | desktop |
| `menu_fork` / `menu_forking` | Fork / Forking... | desktop |
| `menu_details` | Details | DS §4.1 #15 *(Android)* |
| `menu_delete` / `menu_deleting` | Delete / Deleting... | desktop |
| `cd_session_actions_label` | Session actions | *(Android)* long-click label |
| `click_open_session` | Open session | desktop sr-only text |
| `sessions_title` | Sessions | desktop |
| `sessions_description` | Recent sessions across clients and workspaces. | desktop |
| `sessions_search_placeholder` | Search | desktop |
| `cd_clear_search` | Clear search | *(Android)* |
| `cd_sort_sessions` | Sort sessions | desktop |
| `sort_newest_first` / `sort_oldest_first` | Newest first / Oldest first | desktop |
| `filter_sheet_title` | Filter sessions | desktop |
| `filter_clear` | Clear filters | desktop |
| `filter_none_available` | No filters available | desktop |
| `col_title` … `col_time` | Title, Workspace, Model, Tokens, Cost, Time | desktop |
| `fallback_no_model` | No model | desktop |
| `fallback_no_workspace` | No workspace | desktop |
| `fallback_dash` | - | desktop |
| `workspace_chat` | Chat | desktop |
| `workspace_other` | Other | desktop |
| `workspace_home` | ~ | *(Android)* |
| `project_home` | Home | *(Android, decided A22)* project-mode group of the Termux home |
| `session_title_fallback` | Session %1$s | desktop |
| `plurals sessions_count` | %d session / %d sessions | *(Android; replaces the desktop pager's "N-M of T+", 3.5)* |
| `sessions_empty` | No sessions yet. | desktop |
| `sessions_empty_body` | Start a new session to see it here. | *(Android)* |
| `sessions_filtered_empty` | No sessions match the current filters. | desktop |
| `cd_session_actions_for` | Session actions for %1$s | desktop |
| `cd_rename_title` | Rename %1$s | desktop |
| `cd_save_title` | Save title | desktop |
| `cd_cancel_rename` | Cancel rename | desktop |
| `search_pane_title` | Search session history | desktop |
| `search_description` | Search messages across all Cline sessions | desktop |
| `search_placeholder` | Search all session history… | desktop |
| `search_searching` | Searching sessions… | desktop |
| `search_no_results` | No matching session history. | desktop |
| `search_live_hint` | Sessions that are still running may not appear until they end. | *(Android)* |
| `search_failed` | Search is unavailable right now. | *(Android)* |
| `search_group` | Session history | desktop |
| `search_showing` | Showing %1$d of %2$d results | desktop |
| `plurals search_results_count` | %d result / %d results | *(Android)* |
| `search_idle_hint` | Search messages, commands, errors, and file paths. | desktop |
| `search_keyboard_hint` | Navigate with ↑↓ and open with ↵ | desktop |
| `search_keyboard_shortcut` | Ctrl+P | *(Android adaptation)* |
| `delete_title` | Delete session? | desktop |
| `delete_body` | This removes "%1$s" from local history. | desktop |
| `delete_body_running` | The running task will be stopped. | *(Android)* |
| `action_cancel` | Cancel | desktop |
| `action_delete` | Delete | desktop |
| `action_deleting` | Deleting... | desktop |
| `rename_failed` | Rename failed | desktop |
| `rename_failed_body` | The session title could not be updated. | desktop |
| `pin_failed` / `unpin_failed` | Pin failed / Unpin failed | desktop |
| `pin_failed_body` | The session could not be updated. | desktop |
| `fork_failed` | Fork failed | desktop |
| `fork_failed_body` | The session could not be forked. | desktop |
| `fork_empty` | Cannot fork an empty session. | CLI |
| `fork_title_suffix` | " (fork)" (leading space) | CLI |
| `fork_title_fallback` | Untitled | CLI |
| `delete_failed` | Delete failed | desktop |
| `delete_failed_body` | The session could not be removed from local history. | desktop |
| `session_gone` | This session no longer exists. | *(Android)* |
| `restoring_session` | Restoring session... | *(Android)* |
| `restore_failed` | Could not restore this session. | *(Android)* |
| `kernel_restarted_notice` | The kernel restarted. Send your message again to continue. | *(Android)* |
| `details_schedule` … `details_source` | Schedule, Run, Workspace, Branch, Provider, Model, Tokens, Cost, Source | desktop |
| `copied` | Copied | *(Android)* |
| `status_*` | Failed, Approval required, Question asked, Pending, Starting, Running, Unread, Completed, Cancelled, Idle | DS §1.6 |
| `cd_session_status` | Session status: %1$s | desktop (standalone dot only, 2.10) |
| `cd_account_row` | Account settings, %1$s | *(Android)* (email appended) |

Snackbars use DS §4.1 #17: "<title>. <description>" (for example "Rename failed. The
session title could not be updated."), `SnackbarDuration.Long` for errors.

---

## 10. Acceptance checklist (S25U first, then Tab S7+)

Preconditions: Cline Termux port ≥ v3.0.68-termux.2 installed on the S25U, Termux
`allow-external-apps=true`, the app granted RUN_COMMAND. Never run this checklist while
a port release candidate's device steps or the owner's manual test are in progress
(CLAUDE.md hard rule). Seed data from Termux: `cline "say hello"` in `~` and in two
different project folders with the same leaf name (for example `~/a/app` and
`~/b/app`), at least 35 sessions in total (a loop of short prompts is fine), one
schedule with two runs, one session left running with a long prompt (`slow` work), and
one session waiting on an approval.

Drawer (S1), S25U portrait
- [ ] Cold start with the kernel up: the drawer shows "Loading session history..." for no
      longer than the first reply, then rows; never "No sessions found in history."
      before a reply.
- [ ] Sections: "Pinned" appears only after pinning one session; "Scheduled" shows the
      schedule as one group row "N runs" with a clock; expanding it shows "Run 1",
      "Run 2" (or dates); "Tasks" lists the rest with a count. Unpinning the last pinned
      session with nothing scheduled removes all three headers (flat list).
- [ ] Status dots: the running session is green (steady); the approval-waiting one is
      amber with TalkBack "approval required"; a failed one is red; completed and idle
      show no dot. Approve in the chat: the dot changes within 1 s.
- [ ] Unread: while chat A is open, run a prompt in session B from the Termux CLI; B gets
      the blue dot when it finishes. Opening B clears it. Kill the app, restart the Hub
      (`cline hub stop && cline hub start`), reopen: no session gains an unread dot from
      the shutdown replay.
- [ ] "Show more" under Tasks after 30 rows loads more (fetches older history beyond 50)
      and shows "Loading..." while fetching.
- [ ] Sort toggle switches to "Projects": `~/a/app` and `~/b/app` appear as "a/app" and
      "b/app"; sessions started with no folder chosen show under "Home" (A22), sessions the
      kernel imported into its chat workspace under "Chat"; pinned sessions lead
      each group. The choice survives an app restart.
- [ ] Filter "Running" shows only the running session; "Source: CLI" shows only CLI
      sessions; the filter button turns tonal while filtered.
- [ ] Long-press a row: haptic, menu at the finger with Pin, Rename, Fork, Details,
      Delete (red). The row stays highlighted while the menu is open.
- [ ] The app-bar `menu` button opens the drawer. A horizontal drag from the middle of the
      transcript (on message text, not on a code block) opens it; a swipe from the left
      screen edge under gesture navigation performs system Back instead (expected) and
      does not open the drawer; a drag on a horizontally scrolling code block scrolls the
      block. Neither gesture opens the drawer during rename.
- [ ] Rename the 25th row of the drawer with the IME open on the S25U: the field stays
      visible above the keyboard while typing. Tapping the scrim while renaming closes the
      drawer and keeps the old title.
- [ ] Cold start right after `cline hub start` (first `session.list` fails or times out):
      the drawer keeps "Loading session history..." and fills by itself within a few
      seconds of the Hub answering, without pull-to-refresh or reopening. Then kill the
      Hub mid-list and restart it: the drawer fills again without user action.
- [ ] Leave the app closed while the CLI runs a long session (thousands of deltas);
      reopen: the drawer is live within 3 s and only sessions that finished after the app
      last saw them gain the unread dot.
- [ ] Rename a session while it is visible: its dot turns yellow (warning, steady) and the
      row dims until the rename finishes; another row can be pinned or renamed meanwhile.
- [ ] A scheduled run whose record lacks `sessionHistoryOrigin` (any run listed by
      `schedule.list_executions`) still appears under "Scheduled" in its schedule's group;
      the "Scheduled" header count equals the number of group rows, not runs.
- [ ] Back with the drawer open plays the predictive close; back in rename mode cancels
      the edit without saving.

Sessions screen (S2)
- [ ] Tapping "Sessions" in the drawer pushes S2 with the description line and search.
- [ ] Rows show "<workspace> · <provider:model>" and "<in>/<out> · <cost>" in mono; rows
      whose usage was not in the list fill in within 2 s of being on screen ("-" before).
- [ ] Typing "app" in Search narrows to matching titles/workspaces with no lag; "Oldest
      first" reverses the order and the progress bar shows while older history loads.
- [ ] Filter sheet lists facets like `status:running`, `workspace:app`; selecting two
      shows the union (OR); "Clear filters" resets; with nothing loaded the sheet says
      "No filters available".
- [ ] Scrolling to the end shows "Loading..." while older sessions load; when history is
      exhausted the footer reads "N sessions"; the last row and the footer are not hidden
      behind the ExtendedFAB.
- [ ] Pull to refresh works when connected and is disabled when the kernel is down.
- [ ] Inline rename: the `check` button is disabled for a blank title, enabled for an
      unchanged one (Save then just closes the editor, no call); Save
      updates the row and the drawer row at once.
- [ ] The ExtendedFAB "New session" collapses to an icon on scroll down and opens Home.

Search (S3)
- [ ] The drawer search icon opens the full-screen search with the IME up; "Search
      messages, commands, errors, and file paths." shows for an empty query.
- [ ] Typing a word that occurs in an ended session shows "Searching sessions…" briefly,
      then hits with role, title, two-line snippet (matched word bold, no brackets) and
      workspace root. More than 15 hits reveal in batches with "Showing 15 of N results".
- [ ] A word only in the still-running session gives "No matching session history." plus
      the running-session hint.
- [ ] With the Hub stopped, search shows the "Termux asleep" notice instead of a blank
      list; starting the kernel runs the pending query.
- [ ] Tapping a hit opens that session; back returns to where search was opened from.

Flows
- [ ] Rename from the drawer, S2 and the chat header: the new title appears everywhere;
      `session.get` afterwards still has `metadata.usage` and `totalCost` (metadata was
      spread, not replaced).
- [ ] Pin an old session (weeks old): it moves to "Pinned" but keeps its age and its
      position relative to other old rows; it does not show "now", and unpinning puts it
      back where it was. The same holds after an app restart.
- [ ] Pin is instant; with the Hub stopped mid-pin (airplane of the socket: `cline hub
      stop` right before tapping) it rolls back and shows "Pin failed. …".
- [ ] Fork: the new session "<title> (fork)" opens with the full transcript, appears under
      Tasks (not Pinned, not Scheduled), and the source keeps running if it was running.
      Forking an empty session shows "Fork failed. Cannot fork an empty session.".
- [ ] Delete: the dialog quotes the title; for the running session it adds "The running
      task will be stopped."; during "Deleting..." back and outside taps do nothing; on
      success the row disappears and, if it was open, the app shows Home. Running `cline
      history` in Termux confirms it is gone.
- [ ] Restore: open an ended session, `cline hub stop && cline hub start` in Termux, send a
      prompt: the composer shows "Restoring session...", then the turn runs with the old
      history (the reply references earlier messages) and the same session id.
- [ ] Interrupted: start a long turn from the app, run `cline hub stop && cline hub start`
      in Termux mid-turn; after reconnect (trigger: the new `hubId` / the replayed
      shutdown projection, not `run.interrupted`) the chat shows "The kernel restarted.
      Send your message again to continue." with the prompt back in the composer and no
      send-error message; sending it restores the session (F1 step 5).
- [ ] Clearing the title in the drawer editor (select all, delete, Done) restores the
      first-prompt title on every surface; `session.get` no longer has `metadata.title`.
- [ ] Details sheet lists only non-empty rows in the order Schedule, Run, Workspace,
      Branch, Provider, Model, Tokens, Cost, Source; long-press copies a value.

Kernel states
- [ ] `cline hub stop` in Termux: within 5 s the drawer and S2 show "Termux asleep / Start
      kernel to continue." with the cached list and "Showing the last loaded list.";
      row actions are disabled; "Start kernel" brings the list back live within 20 s.
- [ ] Revoke the RUN_COMMAND permission in Android settings: the notice reads "Termux
      permission needed" and "Set up" opens Kernel setup.

Accessibility and theming
- [ ] TalkBack: a drawer row reads "Fix login redirect, Running, pinned, 5 minutes ago"
      (the 2.10 form; no "Session status:" prefix inside a row); the drawer filter options
      read "Running, radio button, selected"; the account row reads the email and exposes Pin, Rename, Fork, Details, Delete as actions; headings
      navigate between sections; the delete dialog focuses "Cancel" first.
- [ ] Font scale 2.0 with in-app size 20: no clipped rows in drawer, S2, S3 or dialogs.
- [ ] Light, dark and dynamic colour (two different wallpapers): selected rows use the
      drawer indicator colour, dots keep their fixed colours, Accessibility Scanner reports
      no contrast or target issues.
- [ ] RTL pseudo-locale `ar-XB`: layout mirrors; paths, model ids and facets stay LTR.

Tab S7+
- [ ] Portrait (medium): rail with the "New session" FAB and the KernelChip; the `menu`
      button opens the modal drawer (without destination items and footer); the Chats
      item shows the unread badge when a session is unread.
- [ ] Landscape (expanded): rail + list pane (400dp at ≥1200dp) + chat; the docked search
      bar opens results over the list pane; S2 opens in the detail pane as a table with a
      sticky header; tapping a row keeps the list pane; the rail `menu` collapses and
      expands the list pane. With both panes shown: open a chat from S2, Back returns to
      S2, Back again returns to Home in the detail pane, Back again leaves the app (the
      list pane never hides on Back). With the list pane collapsed at Home, Back reveals
      the list pane first, then leaves the app. Back from a chat opened via a
      notification lands on Home.
- [ ] Book Cover keyboard: Ctrl+P opens search, arrows and Enter open a hit, Ctrl+N
      starts a new session, Escape cancels a rename.

---

## 11. Open questions

1. ~~**Blank rename.**~~ Resolved from source: `handleSessionUpdate` passes `title: ""`
   through and `PersistenceService.updateSession` deletes `metadata.title` for a title that
   normalises to nothing, so the app clears the custom title on a blank S1 commit, as the
   desktop does (F2 step 3). A fixture recording the call would still be welcome.
2. **Facet filter semantics.** The desktop ORs every selected facet (status, workspace,
   provider, model, pinned), so "status:running" + "workspace:app" shows the union. This
   spec copies it for parity; AND-across-groups would be more useful on a phone. Owner
   decision.
3. **Fork metadata.** The CLI copies all source metadata except `fork` (so a forked
   scheduled run would stay "Scheduled" and a pinned source would yield a pinned fork);
   the desktop sends only `fork`. This spec strips schedule provenance, usage, `pinned` and
   server-derived keys so the fork lands under Tasks. Confirm.
4. **Tokens and cost source.** Rows use the lead session's `usage` and
   `metadata.totalCost` (desktop parity: per-session transcript totals). Sessions with
   agent teams also have `aggregateUsage` / `aggregatedAgentsCost`; showing the aggregate
   would be more honest about spend. Owner decision.
5. **Live vs. persisted detection.** F1 resumes lazily on `session_not_found`. A direct
   "is this session live" signal would allow an eager `session.attach`-or-resume on open;
   `session.list` gives `participants` and `createdByClientId: "hub"` for non-live
   records (fixture 05), but a CLI-created live session was not recorded. A fixture with a
   live CLI session would settle it.
6. **Design-system additions this spec relies on** (to be folded into
   `design/design-system.md` by its owner; none conflict with it): the age format
   `compactAge` (section 1.4) as the concrete form of DS §8.2.5's "2m, 3h, Yesterday";
   the rail unread `Badge` on "Chats"; the `folder`/`schedule` icons for the sort toggle;
   the "Source" group in the drawer filter menu (desktop has it; DS §5.2 lists only
   "Status"); the drawer sort mode persisted across launches *(Android: the desktop
   resets it per launch, 2.6)*; "Details" and "Ctrl+N" as Android additions. Corrections for the DS owner
   found in review:
   - DS §1.6: add the "Action pending" row (static `warning.dot`, highest precedence,
     label = the pending menu label) and state that the kernel status `pending` is a
     different input (1.3).
   - DS §5.2: mark "New session" / "Schedules" *(Android adaptation of "Session" /
     "Schedule")*; replace "by an edge swipe" with "by a content drag
     (`gesturesEnabled`), never the screen edge, which is system Back under gesture
     navigation" (2.8).
   - DS §4.3 last row: drop the sidebar "All"/"Running" case (it is a menu with radio
     items per DS §5.2 and the desktop), keep Plan/Act.
   - DS §4.3 vs §4.1 #16: one rule for the dialog's destructive button (this spec uses
     `TextButton` with `error` content, the M3 dialog convention).
   - DS §3.4 / §4.1 #11: the compact S2 row is 88dp (M3 three-line), not 72dp.
   - DS §5.6: `ModalDrawerSheet` needs `systemBars ∪ ime` when it hosts a text field.
   - DS §5.8 item 8 ("Detail → list") applies only when one pane shows; with two panes
     there is no pane step (2.9.1).
   - DS §7.4: one icon for "New session" (`add` in the drawer/app bars vs `edit_square`
     on the rail FAB) was requested by a reviewer; the DS decides.
7. **Delete of a scheduled run** removes only that run's session; the schedule keeps
   firing. Should the Scheduled group's long-press offer "Open schedule" (Schedules spec)?
   The desktop has no group menu; left out.
8. **Session list cache on disk.** Titles and workspace paths are user data stored in the
   app's private files (excluded from backup). Confirm the owner is fine with a cache, or
   drop it and show only the notice while the kernel is down.
9. **Kernel contract correction (not this spec's file).** `docs/02-kernel-contract.md`'s
   recovery matrix binds "mark the run interrupted and offer resend" to
   `run.interrupted {reason:"hub_restart"}`. The Hub publishes that event only for runs
   recovered from the durable run queue, which only `run.enqueue` fills
   (`hub-server-transport.ts:683-696`); `session.send_input` turns never produce it.
   `run.enqueue` also auto-requeues queued-but-unstarted runs on restart, which the app
   does not want. The contract owner should rewrite the row to the client-side rule of
   1.3, and the sibling specs that bind to `run.interrupted`
   (`composer-approvals.md` §0/§1.7 and line 517, `home-chat.md` event table) need the
   same change; this spec uses the client-side rule already.
10. **Replay budget value.** 2,000 events (1.2.1) is a first guess sized for about 10
    replay pages; measure the S25U reconnect time with a day of CLI traffic and tune it.
