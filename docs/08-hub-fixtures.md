# Hub protocol fixtures (recorded traffic)

_Recorded on 2026-10-03 on the workstation against the fork at cli-v3.0.68 (`@cline/core` 0.0.90, Hub `buildId: source-0.0.90`, Bun 1.4.2 x64), with an isolated `CLINE_DIR`, a mock OpenAI-compatible provider and no real credentials. These are the frames the Android client's Kotlin types and transcript reducer are unit-tested against. Line references point into `~/workspace/cline-termux-v3-phone`._

Fixtures live in `app/src/test/resources/hub-fixtures/`; the tooling in `tools/hub-fixtures/`.

## Regenerating

```bash
# from the app repo root; takes about one minute, touches nothing under ~/.cline
tools/hub-fixtures/regenerate.sh
```

`regenerate.sh` wipes `$CLINE_DIR/data` (refuses a `CLINE_DIR` outside `/tmp`), starts `mock-openai.ts` on 127.0.0.1:25499, runs `cline auth --provider openai-compatible --apikey mock --modelid mock-1 --baseurl http://127.0.0.1:25499/v1`, `cline hub start`, `record.ts`, copies the sanitized mock request log, then `cline hub stop` and kills the mock. Defaults: scratch root `${TMPDIR:-/tmp}/cline-hub-fixtures`, `CLINE_HUB_PORT=25498` (never the device default 25463), fork checkout `~/workspace/cline-termux-v3-phone` (`CLINE_FORK_ROOT`).

The pieces, if you need them separately:

| File | Role |
| --- | --- |
| `tools/hub-fixtures/cline-isolated.sh` | Runs the fork's CLI from source (`bun apps/cli/src/index.ts`) with `CLINE_DIR`, `CLINE_HUB_PORT`, `CLINE_BUILD_ENV=production` (so the discovery record is `data/locks/hub/production.json`, like the Termux package), `CLINE_NO_AUTO_UPDATE=1`, `CLINE_TELEMETRY_DISABLED=1`, `NO_PROXY=127.0.0.1,localhost`. |
| `tools/hub-fixtures/mock-openai.ts` | Deterministic `POST /v1/chat/completions` (SSE) + `GET /v1/models`. Scripted by conversation shape: first `hello-from-mock` prompt -> text + `run_commands` tool call; tool result -> markdown answer (or a one-line acknowledgement if the result says the call was rejected); prompt containing `slow` -> 40 chunks 300 ms apart; anything else -> plain text. Further markers (`mock-edit`, `mock-read`, `mock-long-output`, `mock-long-command`, `mock-think`, `mock-ask`, ...) and, from batch 1: `mock-stall` (headers, then silence for `MOCK_STALL_MS`, default 75 s), `mock-mistakes` (calls a missing tool until the prompt carries the mistake-limit guidance, then "Recovered."), `mock-act-switch` (calls `switch_to_act_mode`, then "Plan executed."), `mock-hook-block` / `mock-hook-context` (one `run_commands` call, then quotes what came back), `mock-api-error[-<status>]` (HTTP 400 or the given status). Logs every request body. |
| `tools/hub-fixtures/record.ts` | The scenario driver (Bun's `WebSocket` with the `protocols` option; raw `node:net` for the HTTP probes because Bun's `fetch` follows `HTTP_PROXY` and ignored `NO_PROXY` on this workstation). `--only 01,02` runs a subset against an already running Hub. |
| `tools/hub-fixtures/sanitize-mock-log.ts` | Collapses repeated system prompts and masks the home directory in the mock log. |

Manual sequence (what the script does): `export CLINE_DIR=/tmp/x/home CLINE_HUB_PORT=25498`; `bun tools/hub-fixtures/mock-openai.ts --port 25499 &`; `tools/hub-fixtures/cline-isolated.sh auth --provider openai-compatible --apikey mock --modelid mock-1 --baseurl http://127.0.0.1:25499/v1`; `tools/hub-fixtures/cline-isolated.sh hub start` (prints `ws://127.0.0.1:25498/hub`); `bun tools/hub-fixtures/record.ts --workspace /tmp/x/workspace`; `tools/hub-fixtures/cline-isolated.sh hub stop`.

## File format

One JSON object per line:

```json
{"t": 449.1, "dir": "recv", "conn": "c1", "frame": {"kind": "event", "envelope": {...}}}
{"t": 449.2, "dir": "send", "conn": "c1", "frame": {"kind": "command", "envelope": {...}}}
{"t": 449.2, "dir": "note", "note": "approval.requested received; approving", "approvalId": "..."}
```

`t` is milliseconds since the scenario started (monotonic clock), `conn` names the WebSocket connection (`c1`..`c5`, `origin-only`, `token`), `frame` is the raw JSON exactly as sent or received. `note` lines are structured observations (HTTP probe results, CLI output, summaries). Nothing is truncated or dropped inside frames. Two substitutions only: every occurrence of a Hub auth token is replaced by the literal `<hub-auth-token>` (it would otherwise appear in the negotiated subprotocol and the `/status` body), and in `mock-requests.jsonl` the ~5.4 KB system prompt is kept in full in the first chat request and replaced by `<identical to the system prompt of request N: ...>` in the others, plus `/home/<user>` masking. Workspace paths are the recorder's scratch paths. The scratch folder of the recording run named the workstation user, so on 2026-10-09 its prefix was replaced by `/tmp/hubfix` in every fixture; the paths now read `/tmp/hubfix/workspace` and `/tmp/hubfix-home/data/sessions/...`, and nothing else changed.

## Scenarios

| File | Lines | What it records |
| --- | --- | --- |
| `01-register-and-status.jsonl` | 14 | discovery record fields, `GET /health`, `GET /version`, token-auth connect, `client.register` (android/native, approval.respond + tool_executor.askQuestion, workspaceContext), `hub.status`, `settings.list`, `session.list`, global `stream.subscribe` without cursor. |
| `02-run-with-tool-approval.jsonl` | 101 | `session.create` (mock provider, act, tools, checkpoint enabled, `metadata.source: "android"`, `toolPolicies {"*": {autoApprove:false}}`), per-session subscribe, `session.send_input` (`timeoutMs: null`), `approval.requested` -> `approval.respond {approved:true}`, every event through `run.completed`, then the reply. |
| `03-reject-and-abort.jsonl` | 114 | second session; approval rejected with the documented reason; the turn continues and completes; a third `send_input` with a slow stream, `run.abort` after ~1.5 s, `run.aborted`, the aborted reply. |
| `04-history-and-replay.jsonl` | 51 | `session.messages`, `session.get {includeSnapshot:true}`, `session.search` (twice), `session.update` (rename + pin), disconnect, reconnect with the same `clientId`, global `stream.subscribe {sinceSequence: last-3}`, session-scoped `stream.subscribe {sinceSequence: last-12}`, then the two-live-connections same-`clientId` experiment with `client.list` / `client.update` / `client.register`. |
| `05-resume-after-restart.jsonl` | 98 | `hub.status` before, `cline hub stop`, `cline hub start` (new token/pid/hubId), reconnect, replay across the restart, `session.list`, `session.send_input` to the not-yet-resumed session, `session.attach`, `session.messages`, resume via `session.create {sessionConfig.sessionId, initialMessages}`, a second turn through `run.completed`, `session.get`, `session.search`, `session.messages`. |
| `06-auth-negative.jsonl` | 48 | raw HTTP upgrade attempts (status lines), `/status` with and without bearer, an Origin-only connection (register with/without workspaceContext, `session.list/create/delete`, `schedule.list`), `client_authority_mismatch`, unknown command. Notes only for the HTTP probes; frames for the WebSocket parts. |
| `07-checkpoint-restore.jsonl` | 103 | Recorded 2026-10-07 against core 0.0.91 (cli-v3.0.69) on the bench Hub, `--only 07` (opt-in; needs a clean git workspace under `/tmp`). Two `mock-edit` runs with auto-approve, `session.get {includeSnapshot}` with the daemon-side `metadata.checkpoint.history` (run 1 `commit`, run 2 `stash`), the app's split restore at run 1: workspace-only `session.restore {checkpointRunCount:1, restore:{messages:false, workspace:true}}` (reply `{checkpoint:{runCount:1, kind:"commit"}}`), `session.messages`, the transcript cut (`trimMessagesToCheckpoint`), the restored `session.create` (`initialMessages` = run 1's user message, `restoredFromSessionId`, history filtered to run 1), `session.detach` of the source and the restored session's `session.messages` (1). Replayed by `CheckpointRestoreFixtureTest`. |
| `mock-requests.jsonl` | 8 | Every request the runtime sent to the provider: the tool schema it advertises, the exact message shapes, headers (`Authorization` redacted). The two `GET /v1/models` lines are the regeneration script's readiness probe, not the runtime. |

Scenarios 01-06 were recorded on 0.0.90 and share one Hub process until scenario 05 restarts it; 07 was recorded separately on 0.0.91 (its mock requests are not in `mock-requests.jsonl`). Session 1 (`02`, resumed in `05`) and session 2 (`03`) are the only persisted sessions; the Origin-only session in `06` is deleted again.

## Findings

### Authentication and transport

Raw HTTP/1.1 upgrade attempts against `/hub` (fixture `06`, notes A-F) and HTTP endpoints (G, H):

| Request | Result |
| --- | --- |
| no `Sec-WebSocket-Protocol`, no `Origin` | `HTTP/1.1 401 Unauthorized`, `Connection: close`, `Content-Length: 0` |
| `Sec-WebSocket-Protocol: cline-hub-auth.<wrong 64 hex>` | `401 Unauthorized` |
| correct token, path `/nothub` | TCP connection closed with no HTTP response at all (`socket.destroy()`) |
| `Origin: http://localhost`, no token | `101 Switching Protocols`; the 101 carries **no** `Sec-WebSocket-Protocol` header |
| `Origin: http://evil.example`, no token | `401 Unauthorized` |
| correct token | `101 Switching Protocols` with `Sec-WebSocket-Protocol: cline-hub-auth.<token>` echoed back |
| `GET /status` without `Authorization` | `401` body `Unauthorized` |
| `GET /status` with `Authorization: Bearer <token>` | `200`, the full discovery record including `authToken` |
| `GET /health`, `GET /version` | `200` without auth; `/version` adds `capabilities`, `pid`, `startedAt`, `buildEpochMs` |

The subprotocol echo (open question 1) is therefore confirmed for the real `ws` server on the workstation; it is still unverified for the device Bun's built-in `ws` replacement. OkHttp requires that echo, so keep the device check.

Bun's `WebSocket` reported `socket.protocol === "cline-hub-auth.<token>"` after the token-auth open and `""` after the Origin-only open.

Origin-only connections (D, then I-N): the connection is accepted but `client.register` with `workspaceContext.workspaceRoot` set to anything other than the Hub's own `--cwd` fails with `invalid_client_registration` / `"Registration workspace must match the Hub-authorized workspace"` (the Hub here was started with `--cwd` = the fork checkout; on the phone `cline hub start` is run from `$HOME`). Registering **without** `workspaceContext` succeeds, and after that `session.list`, `session.create` in an arbitrary workspace, `session.delete` and `schedule.list` all succeed. The "reduced authority" of the Origin path is only the registration workspace check and the cross-workspace schedule authority, not a read-only mode.

Other error codes seen: a command whose `clientId` differs from the connection's registered one -> `client_authority_mismatch` ("Command clientId does not belong to this connection."); an unknown command name falls through to the schedule handler -> `unsupported_command` ("Unsupported hub schedule command: session.frobnicate"); a second `client.register` on the same connection -> `client_already_registered`.

### Discovery record and daemon lifecycle (`01`, `05`)

`production.json` fields: `hubId, protocolVersion, minClientProtocolVersion, maxClientProtocolVersion, capabilities, coreVersion, buildId, authToken (64 hex), host, port, url, pid, startedAt, updatedAt`. `cline hub start` prints exactly one line, the ws URL; `cline hub stop` prints `{"stopped":true}` and the port refused connections 1.1 s after the stop command returned, with the discovery file already removed. A warm restart on the workstation took 2.3 s. After the restart `pid`, `startedAt`, `hubId` and `authToken` all changed, so the `(pid, startedAt)` check in `02-kernel-contract.md` is sufficient to detect rotation.

`hub.status` reply: `{"hubId","draining":false,"activeRpcTurns":0,"pendingRuns":0,"eventLog":{"lastSequence":3},"idle":true}`. The sequence was already 3 on first contact: `hub start`/`hub status` connect a `clientType: "hub-healthcheck"` client whose `hub.client.registered` / `hub.client.disconnected` events are logged (they also show up in replays, e.g. `05` sequences 112-113). Filter `hub.client.*` by `clientId`.

### Registration, subscription, replay (`01`, `04`, `05`)

- `client.register` reply is `{"clientId": "<the id you sent>"}` and publishes `hub.client.registered {clientId, clientType, displayName, metadata, connectedAt}` on the global stream (your own registration included).
- **Every session event also reaches the global subscription.** A client that subscribes globally and per session receives each session event twice, byte-identical (same `eventId` and `sequence`); fixture `02` shows every event as a pair. Either subscribe globally only (session events included) and keep one global cursor, or dedupe by `sequence` per connection. Only the per-session `stream.subscribe` supports a per-session replay cursor.
- `session.created` and the two `session.updated` events for a new session arrive on the global subscription **before** the `session.create` reply.
- Replay (`04`): after closing `c1` at sequence 100 and resubscribing on `c2` with `sinceSequence: 97`, the Hub delivered sequences 98-102 in order: the three events before the disconnect plus `hub.client.disconnected` (101, c1's close) and `hub.client.registered` (102, c2's own register, published before the subscribe frame). `sinceSequence` is exclusive. A session-scoped subscribe with `sinceSequence: 88` for session 1 delivered only sequence 100 (the other events in that range belonged to session 2), so scoping filters by `sessionId` inside the durable log.
- Replay survives a daemon restart (`05`): with the cursor 106 taken before `hub stop`, the new daemon replayed 107-114 (c4's disconnect, the shutdown `session.updated` pairs, the healthcheck client, c5's own register) and `hub.status.eventLog.lastSequence` continued at 114. The event log is the durable `hub-events-*.db`; cursors stay valid across restarts and token rotations, so persist them.
- No `run.heartbeat` was recorded (all runs were under 30 s).

### Same `clientId` on a new connection (open question 10)

Re-registering the same `clientId` on a new connection **after** the old one closed is accepted (`04`, c2). Re-registering while the old connection is still open is also accepted (`04`, c3: `ok: true`, `client.list` shows the single entry with c3's `connectedAt`), **but** when the older connection then closes, the adapter's close handler sends `client.unregister` for that `clientId` and removes the record c3 now relies on: `client.list` becomes empty, `client.update` on c3 fails with `client_not_found`, and c3 cannot re-register (`client_already_registered`, the connection still owns its authority). Rule for the app: never open the replacement socket before the old one is closed (OkHttp `cancel()` and wait for the close callback), or mint a new `clientId` per connection and re-attach sessions. A half-open socket the Hub has not yet reaped (30 s ping sweep) is exactly this trap.

### Session create and the run timeline (`02`)

`session.create` reply: `{session: SessionRecord, snapshot: CoreSessionSnapshot}`. Relevant shapes:

- `session`: `{sessionId, workspaceRoot, cwd, createdAt (ms), updatedAt, createdByClientId, status, participants[{clientId, attachedAt, role:"creator"}], metadata, runtimeOptions{enableTools, enableSpawn, enableTeams, mode}, usage, aggregateUsage}`. `metadata` after create: `{source:"android", interactive:true, provider, model, mode:"act", checkpointEnabled:true, autoApproveTools:false, sessionHistoryOrigin:{mode:"user"}, pid}`.
- `snapshot`: `{version:1, sessionId, source, status, createdAt (ISO), updatedAt, endedAt, exitCode, interactive, workspace{cwd, root}, model{providerId, modelId}, capabilities{enableTools, enableSpawn, enableTeams}, lineage{isSubagent}, team{name}, prompt?, metadata, artifacts{messagesPath}?, usage, aggregateUsage, checkpoint{enabled, history[]}, messages: []}`. `snapshot.messages` was empty in every reply and event, even with `includeSnapshot: true` after a run; read transcripts with `session.messages`.

Event order for the approved tool turn (sequences 7-49 in `02`, ~430 ms end to end with the mock):

```
run.started {requestId, clientId}
session.updated {sessionId, snapshot(status:running)}        session.updated {session(status:running), snapshot}
iteration.started {iteration:1}
assistant.delta {text} x5          (one per provider SSE chunk)
usage.updated                      assistant.finished {text}
session.updated (status:pending)   approval.requested          session.updated (status:pending)
approval.resolved {approvalId, approved:true}
session.updated (status:running) x2
tool.started -> tool.updated x2 -> tool.finished
iteration.finished {iteration:1, hadToolCalls:true, toolCallCount:1}
iteration.started {iteration:2} -> assistant.delta x13 -> usage.updated -> assistant.finished -> iteration.finished
agent.done {reason:"completed", text, iterations:2, usage}
session.updated (status:idle) x2
run.completed {reason:"completed", result, snapshot}
<reply to session.send_input> {result, snapshot}
```

`session.updated` always comes as a pair: first `{sessionId, snapshot}` (projected from the runtime's snapshot event, no `session` key), then `{session, snapshot}` (projected from the status change). `snapshot.status` goes `idle -> running -> pending` (while an approval is outstanding) `-> running -> idle`. The `pending` state is a free "waiting for you" indicator.

`run.completed.payload.result` is the full `AgentResult`: `{text, usage{inputTokens, outputTokens, totalCost}, messages[...the whole transcript...], toolCalls[{id, name, input, output, durationMs, startedAt, endedAt}], iterations, finishReason, model{id, provider}, startedAt, endedAt, durationMs}`. It repeats every message of the session, so it grows with the transcript (4.4 KB after one turn here); do not depend on `result.messages`, use it only for `text`, `usage` and `finishReason`. The `session.send_input` reply arrives after `run.completed` and carries the same `{result, snapshot}`.

`usage.updated` payload: `{sessionId, delta{inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, totalCost}, totals{same keys}, usage{...}, aggregateUsage{...}, agent{kind:"lead", teamRole:"lead"}}`. `reasoningTokenCount` is omitted when undefined. `usage`/`aggregateUsage` are the session totals the sidebar needs; `totals` are the agent-run totals.

`approval.requested` payload: `{approvalId, sessionId, agentId, conversationId, iteration, toolCallId, toolName, inputJson (a JSON string, parse it), policy{autoApprove:false}}`; `agendaTaskId` is absent when undefined. `approval.respond` reply: `{approvalId, approved}`; event `approval.resolved {approvalId, approved, reason?}` (`reason` present only when one was sent).

### Tool events for `run_commands` (open question 8)

```json
tool.started  {"toolCallId":"call_mock_run_1","toolName":"run_commands","input":{"commands":["echo hello-from-mock"]}}
tool.updated  {"toolCallId":"...","toolName":"run_commands","update":{"stream":"stdout","chunk":"","executionId":"<uuid>","detachable":false,"commandIndex":0,"query":"echo hello-from-mock"}}
tool.updated  {"toolCallId":"...","toolName":"run_commands","update":{"stream":"stdout","chunk":"hello-from-mock\n","executionId":"<uuid>","detachable":false,"commandIndex":0,"query":"echo hello-from-mock"}}
tool.finished {"toolCallId":"...","toolName":"run_commands","output":[{"query":"echo hello-from-mock","result":"hello-from-mock\n","success":true}]}
```

- `update.chunk` is **incremental**: append chunks per `(executionId, stream)`. The first update has an empty `chunk` and announces the execution (`executionId`, `commandIndex`, `query`); `stream` is `"stdout"` or `"stderr"`.
- The executor coalesces output every 48 ms into at most one update per stream, with a bounded pending tail; when it drops output the update carries `truncated: true`, `droppedChars: N` and the chunk starts with `\u001b[0m[N command-output characters skipped while streaming]\n` (`core/src/extensions/tools/executors/bash.ts:513-620`). ANSI escape sequences are passed through untouched.
- `detachable` flips to `true` once the command may be left running (`run.proceed_while_running`).
- `tool.finished.output` is an array with one `{query, result, success}` per command in the call. On a **rejected** approval (`03`, sequence 71) the tool still emits `tool.started` and then `tool.finished` with `output: {"error": "This tool call was rejected by the user and not executed. -- NOT a tool or system failure. Clarify with user before proceeding."}` **and** a top-level `error` string containing the same object JSON-encoded. The turn then continues: the model gets the error as a tool result and answers, and the run ends with `run.completed {reason:"completed"}`, not `run.failed`.
- The hook-derived `tool.started/finished {toolName}` without `toolCallId` mentioned in `03-feature-matrix.md` did not occur in these runs (no hooks configured); keep the guard.

Not recorded: `editor`, `apply_patch`, `read_files`, `fetch_web_content` payloads.

### Reject and abort (`03`)

After `approval.respond {approved:false, reason}` the session does not stop; `approval.resolved` carries the reason, the tool events above follow, iteration 2 runs and `run.completed` arrives with `result.finishReason: "completed"` and `result.toolCalls[0].output.error` set. A client that wants "reject = stop" must also call `run.abort`.

`run.abort {sessionId, reason}` replied `{applied: true}` 7 ms later; then `agent.done {reason:"aborted", text:<last assistant text>, iterations:1, usage{0,0}}`, two `session.updated` (status `idle`), `run.aborted {reason:"aborted", result, snapshot}` and the pending `session.send_input` reply with `ok: true`, `result.finishReason: "aborted"`. No `approval.resolved {cancelled:true}` appeared because no approval was pending at that moment. The aborted user prompt stays in the transcript as a trailing `user` message with no assistant reply (`result.messages` has 5 entries).

### History (`04`)

`session.messages` reply `{sessionId, messages[]}`. Message shape: `{id, role:"user"|"assistant", content: Block[], ts (ms), modelInfo?{id, provider}, metrics?{inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens}}`. Note `ts`, not `createdAt`, and no `metadata` key in practice. Blocks:

- `{"type":"text","text":"..."}`
- `{"type":"tool_use","id":"call_mock_run_1","name":"run_commands","input":{...}}` (assistant)
- `{"type":"tool_result","tool_use_id":"call_mock_run_1","name":"run_commands","content":[{"query":..,"result":..,"success":true}]}` (role `user`; `content` is the raw tool output, an array here, a JSON **string** plus `"is_error": true` for the rejected call)

**User text is persisted wrapped: `<user_input mode="act">Run echo hello-from-mock and summarize</user_input>`.** The same wrapped string is `session.metadata.prompt`, `snapshot.prompt` and the auto-generated `metadata.title` is the unwrapped prompt. Strip the wrapper (and `<mode_notice>` blocks) at display time the way upstream's `formatDisplayUserInput` does (`shared/src/prompt/format.ts:179`); the mode attribute is also how upstream recovers the per-message plan/act mode (`parseUserInputMode`). The wire request to the provider in `mock-requests.jsonl` shows the model receives the plain text, so the wrapper is a storage format, not a prompt format, from the app's point of view.

`session.get {includeSnapshot:true}` after the run: `session.metadata` had grown `title` (= first prompt), `prompt` (wrapped), `totalCost`, `aggregatedAgentsCost`, `usage`, `aggregateUsage`, `messagesPath` (absolute path into `$CLINE_DIR/data/sessions/<id>/<id>.messages.json`), `pid`.

**`session.update {metadata}` replaces the stored metadata wholesale** (`session-service.ts:168-170` writes `metadata_json = ?`). Sending `{title: "...", metadata: {pinned: true}}` left the persisted record with `metadata = {pinned, title, messagesPath, prompt, provider, model, source, pid}` (the last six are re-derived columns): `usage`, `totalCost`, `mode`, `checkpointEnabled`, `interactive` and `sessionHistoryOrigin` were gone from the record and stayed gone after the restart (`05` `session.list`). The desktop knows this and sends `metadata: {...session.metadata, pinned}` (`webview/hooks/use-session-history.ts:1580`). Do the same: read the current record, spread its metadata, set `pinned`. The reply is `{updated: true, session, snapshot}` and a `session.updated {session, snapshot}` event is published (sequence 100).

`session.search {query:"hello", limit:20}` returned `{hits: []}` right after the run and again 3 s later; after the daemon restart and the resumed turn it returned two hits. The FTS index is refreshed on the session `ended` event (`hub-server-transport.ts:467-471`), which fires when a session is disposed (Hub shutdown, delete), not after each turn, so search lags for live sessions. Hit shape: `{sessionId, documentId: "<sessionId>:metadata", ordinal: -1, role: "session", startedAt (ISO), workspaceRoot, title, snippet: "Run echo [hello-from-mock] and summarize", score (negative BM25)}`; matches are bracketed in `snippet`.

`settings.list {workspaceRoot, cwd, includePluginTools:true}` replied `{snapshot: {workflows:[], rules:[], plugins:[], skills:[], tools:[], mcp:[]}}` in the empty home (shapes of the entries are not covered).

### Resume after a daemon restart (open question 6, `05`)

1. After `hub stop`/`hub start`, `session.list {limit:50, rootOnly:true}` returned both sessions from `sessions.db` with `createdByClientId: "hub"`, `participants: []`, `status` `completed` (session 1) and `aborted` (session 2; its snapshot status at shutdown was `cancelled`, so the two status vocabularies differ), `metadata.title`/`pinned` preserved for session 1.
2. `session.send_input` to session 1 before resuming: `ok: false`, `error.code: "session_not_found"`, `message: "session not found: <id>"` (the `session.get` variant of the message is `"Unknown session: <id>"`).
3. `session.attach {sessionId}` on the non-live session **succeeds** (`status: "completed"`, the client is added as `participant`) but does not make it live; `send_input` would still fail. Attach is not a resume.
4. `session.messages` still works for the persisted session (4 messages).
5. `session.create {workspaceRoot, cwd, sessionConfig:{sessionId:<old>, providerId, modelId, mode, enableTools, checkpoint:{enabled:true}}, initialMessages:<those 4 messages>, metadata:{source:"android", interactive:true, provider, model}, toolPolicies}` replied `ok` in 35 ms with the **same** `sessionId`, `session.status: "completed"` (the persisted status; it becomes `running`/`idle` once a turn runs), `usage` and `aggregateUsage` recomputed from the message `metrics` (`inputTokens: 2500, outputTokens: 120` = 1200+1300 / 24+96), `createdByClientId` = our client, and `metadata` merged from the persisted record plus the new config (`pinned`, `title` kept; `mode`, `checkpointEnabled`, `autoApproveTools`, `sessionHistoryOrigin` re-added). `snapshot.checkpoint` was `{enabled:true, history:[]}`; no compaction state is reported anywhere in these replies (`session.compaction.get` was not exercised; it is creator-gated).
6. The next `session.send_input` ran a normal turn (`run.started` ... `run.completed {reason:"completed"}`); the provider request (`mock-requests.jsonl` #8) carried all six messages, i.e. the history was really re-seeded. Afterwards `session.get` showed `usage 3900/138` (2500+1400, 120+18) and `session.messages` returned 6 messages (`user, assistant, user, assistant, user, assistant`).

So the documented resume path works end to end; usage continuity comes from the message `metrics`, and checkpoint history does not come back (see next point).

### Checkpoints: daemon-side since core 0.0.91 (`07`)

**Core 0.0.91 (cli-v3.0.69), fixture 07:** the daemon creates checkpoints for a plain client with no checkpoint contribution: after two runs `metadata.checkpoint` (and the snapshot) held `latest` plus a `history` of two entries `{ref (40 hex), createdAt (epoch ms number), runCount, kind}`, run 1 `commit` and run 2 `stash`. The workspace-only `session.restore` put the files back to run 1 (the mock's edit lines went to 0, git status clean) and answered with the checkpoint it used. The kernel leaves `refs/cline/checkpoints/<sessionId>/<run>` in the workspace; deleting the session removes them. Scenario 07's own cleanup only resets the working tree, so the bench workspace keeps the ref until the session is deleted.

The recorded restored `session.create` carries the inherited history with `createdAt` as a string: that was the app's `HubPayloads.filteredCheckpoints` until batch 1's close-out, and the app's own reader then rejected those entries. Core writes a number (`createRestoredCheckpointMetadata`) and reads with `Number()`; the app now does both (docs/07 D29), and the fixture keeps the old shape as recorded.

The rest of this section is the **0.0.90** finding, kept for history: with `sessionConfig.checkpoint.enabled: true` the snapshot's `checkpoint.history` stayed `[]` through two runs and the workspace got no `.git`. Checkpoint creation is a **client contribution**: the daemon calls back into the client that registered `runtimeOptions.clientContributions: [{kind:"checkpoint", capabilityName:"checkpoint.create", config}]` via `capability.requested` (`hub-client-contributions.ts:574-650`; the CLI registers it in `hub-runtime-host.ts:341-352` when its local runtime has `createCheckpoint`). Without that contribution there are no checkpoints and `session.restore` has nothing to restore. `03-feature-matrix.md` ("checkpoints are created daemon-side") is wrong on this point; the Android app would have to implement the capability itself (a git snapshot run inside Termux) or ship without checkpoints.

### No `ui.notify` per turn

`ui.notify` never appeared in any fixture. It is published only from the session `ended` projection with reason `completed` (`session-event-projector.ts:448-453`), and `ended` fires when a session is disposed (Hub shutdown, `session.delete`), not when a turn finishes. Build the "task completed" notification from `run.completed` / `run.failed` / `approval.requested` yourself; the `ui.notify` branch in `03-feature-matrix.md` will not fire for the app's own turns.

### Shutdown projections

When the daemon stops, each live session is disposed and the log receives `session.updated` pairs with `snapshot.status: "completed"` (idle session) or `"cancelled"` (aborted session), `endedAt` set and `exitCode: 0`; in one of two recordings a `run.completed {reason: "disposed", snapshot}` (no `result`) was also published for the idle session. Treat any `run.completed` whose `reason` is not `"completed"` as "the turn ended for another reason", never as success, and expect these events in the replay after a reconnect.

### `session.delete`

Deleting the freshly created Origin-only session (no messages yet) replied `{deleted: false}`, yet `session.list` no longer showed it and `session.get` answered `session_not_found`. `deleted: false` means "no canonical message history was on disk", not failure; verify with `session.get`.

### What the runtime sends to the provider (`mock-requests.jsonl`)

- `POST /v1/chat/completions` with `{model, messages, tools, tool_choice:"auto", stream:true, stream_options:{include_usage:true}}`; user-agent `ai-sdk/openai-compatible/3.0.37 ai-sdk/provider-utils/5.0.30 runtime/bun/1.4.2`; `Authorization: Bearer <apiKey>`. The runtime never called `GET /v1/models`.
- With `metadata.source: "android"` the session advertised 25 tools: `read_files, search_codebase, run_commands, fetch_web_content, editor, spawn_agent, team_* (18 team tools), tasks`. Absent: `ask_question` (needs the `tool_executor.askQuestion` client contribution at `session.create`, as the feature matrix says), `skills` (gated on at least one configured skill; the home had none, so open question 9 stays unconfirmed), `apply_patch`, `submit_and_exit`. The `tasks` tool and the Agenda/`# Tasks` system-prompt section are present because `source` is neither `cli` nor `vscode` (as predicted in `03-feature-matrix.md`).
- `run_commands` schema: `{"type":"object","properties":{"commands":{"type":"array","items":{"type":"string"}}},"required":["commands"]}`; per-tool policies key on these names (`read_files`, `search_codebase`, `run_commands`, `fetch_web_content`, `editor`, ...).
- The system prompt (5431 chars) names the workspace path and the date; it is the one place the scratch path appears outside workspace fields.

### Smaller refinements to `02-kernel-contract.md` / `03-feature-matrix.md`

- `run.started` payload is `{requestId, clientId}` of the `session.send_input` envelope: correlate the turn by it instead of waiting for the reply.
- `iteration.finished` payload: `{iteration, hadToolCalls, toolCallCount}`; `agent.done` (`{reason, text, iterations, usage}`) precedes `run.*` by a few ms and is the earliest "turn over" signal, but `run.completed` is still the authoritative one (it carries `result` and `snapshot`).
- `assistant.delta` granularity equals the provider's chunk granularity (one event per SSE chunk, ~195 bytes of envelope per few characters of text); plan on batching UI updates.
- `session.list` records for non-live sessions have `createdByClientId: "hub"` and `participants: []`; a live session created by you shows your `clientId` and a `creator` participant. Use `status` and your own session cache, not `createdByClientId`, to recognise your sessions.
- `hub.client.registered` is also published for the Hub's own `hub-healthcheck` probe client every time `cline hub status` (or `hub start`) runs.
- The `session.create` reply was never slower than 50 ms here, but the Hub's default per-command timeout is 30 s (`HUB_DEFAULT_COMMAND_TIMEOUT_MS`); the recorder sent `timeoutMs: 120000` on `session.create` for cold-start safety and `timeoutMs: null` on `session.send_input`.

## Not covered / caveats

- Recorded on the workstation Hub (real `ws` package, Bun 1.4.2 x64), not on the device Bun; the subprotocol echo and WebSocket behaviour of bun-android-ffi are untested here (open questions 1 and 3).
- No reasoning events, images, queued/steered prompts, `capability.requested` (ask_question, mistake limit, client tools), hooks, team events, schedules or connectors. Batch 1 checked steer, the mistake limit, `switch_to_act_mode`, hooks, `run.heartbeat` and drain replay on the bench by hand (docs/10), without recording fixtures. `session.restore` is covered by `07` (workspace-only path; the atomic path waits on K1).
- The `01` to `06` fixtures and `mock-requests.jsonl` contain the scratch paths `/tmp/hubfix/workspace` and `/tmp/hubfix-home/...` (rewritten, see "File format"); tests should treat workspace strings as opaque. A re-record keeps local paths out when it runs with a neutral scratch root, such as `HUBFIX_SCRATCH=/tmp/hubfix`; the workspace is then `/tmp/hubfix/workspace`, the suffix that `CheckpointActionsTest` checks.
- `mock-requests.jsonl` is sanitized as described under "File format"; everything else is verbatim apart from the scratch-path prefix.
