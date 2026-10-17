# Feature matrix

_Generated on 2026-10-03 from `docs/research/kernel-and-desktop-synthesis.json` (multi-agent code reading of the Cline monorepo at cli-v3.0.68 + the Termux port). Line references point into `~/workspace/cline-termux-v3-phone`._

`available_via`: **hub** = Hub WebSocket (`ws://127.0.0.1:25463/hub`, protocol v1); **sidecar** = Termux-side bridge (`RUN_COMMAND` running `$PREFIX/bin/cline`, or the shipped `cline dashboard` server); **files** = `RUN_COMMAND cat`/write of a `~/.cline` file; **none** = Android-native or omitted.

## Tier 1 — connect and chat

### Kernel liveness and version probe

- **Via:** `hub` · desktop has it
- **Kernel API:** GET http://127.0.0.1:25463/health (no auth: ok, protocolVersion, buildId, coreVersion, draining, url); GET /version (adds pid, startedAt, capabilities)
- **Notes:** Replaces the sidebar Hub HoverCard dot and the boot gate. /version pid+startedAt detect a daemon restart (token rotation).

### Start/stop/upgrade the Hub daemon, read the auth token, keep Termux alive

- **Via:** `files` · desktop has it
- **Kernel API:** RUN_COMMAND background: $PREFIX/bin/cline hub start|status|stop|upgrade; cat /data/data/com.termux/files/home/.cline/data/locks/hub/production.json (authToken, hubId, pid, url, port); termux-wake-lock / termux-wake-unlock
- **Notes:** Desktop does this through ensureDetachedHubServer in-process. Needs v3.0.68-termux.2+ on the phone (entry.js + external ws). hub start takes 8-15 s cold on the S25U; its last stdout line is the ws URL.

### Register client and subscribe to the event stream with replay

- **Via:** `hub` · desktop has it
- **Kernel API:** command client.register {clientId, clientType:'android', transport:'native', actorKind:'client', capabilities:[{name:'approval.respond'},{name:'tool_executor.askQuestion'}], metadata, workspaceContext{workspaceRoot,cwd}}; frame stream.subscribe {clientId, sessionId?, sinceSequence?}; events carry sequence (durable log hub-events-hub-production.db)
- **Notes:** Pending approval.requested events are re-issued on every subscribe (dedupe by approvalId). Hub-wide events (ui.notify, settings.changed, schedule.*, hub.client.*) only reach the global subscription.

### New session (workspace, provider, model, mode, tool policy)

- **Via:** `hub` · desktop has it
- **Kernel API:** session.create {workspaceRoot, cwd, sessionConfig:{providerId, modelId, mode:'act'|'plan', enableTools:true, enableSpawnAgent, enableAgentTeams, checkpoint:{enabled:true}, thinking?, reasoningEffort?, systemPrompt?}, metadata:{source:'android', interactive:true, title?, provider, model}, toolPolicies:{'*':{autoApprove:false|true}}} -> {session, snapshot}; event session.created
- **Notes:** OMIT sessionConfig.apiKey so the daemon merges the stored key / refreshes OAuth (apiKey:'' wipes a stored plain key). Do not pass configExtensions: the default loads rules/skills/workflows/plugins/hooks daemon-side. metadata.source other than cli/vscode gives all built-in tools (incl. tasks).

### Send prompt and stream the reply

- **Via:** `hub` · desktop has it
- **Kernel API:** session.send_input (= run.start) {prompt, mode?, delivery?, attachments?:{userImages:[dataURL...]}} with envelope.sessionId and timeoutMs:null; events run.started, assistant.delta{text}, assistant.finished{text}, iteration.started/finished, run.heartbeat (30 s), run.completed|failed|aborted {reason, error?, result{text,usage,finishReason}, snapshot}
- **Notes:** The reply blocks for the whole turn; treat the run.* terminal event as truth so a dropped socket is recovered by replay.

### Reasoning (thinking) blocks

- **Via:** `hub` · desktop has it
- **Kernel API:** events reasoning.delta {text, redacted}, reasoning.finished {reasoning}
- **Notes:** 'Thinking' / 'Thought for Ns' labels as in @cline/ui formatThoughtLabel.

### Tool call rows (commands, edits, reads, web, MCP) with live output

- **Via:** `hub` · desktop has it
- **Kernel API:** events tool.started {toolCallId, toolName, input}, tool.updated {toolCallId, toolName, update}, tool.finished {toolCallId, toolName, output, error?}; a second hook-derived tool.started/finished {toolName} without ids must be ignored
- **Notes:** Same data the desktop renders via chat_tool_call_* chunks. Icons/summaries per TOOL_KIND_ICONS and tool-summary.ts.

### Tool approval (approve / reject)

- **Via:** `hub` · desktop has it
- **Kernel API:** event approval.requested {approvalId, sessionId, toolCallId, toolName, inputJson, iteration, agentId, conversationId, policy}; command approval.respond {approvalId, approved, reason?} -> {approvalId, approved}; event approval.resolved; session only prompts when toolPolicies autoApprove===false
- **Notes:** No ownership check (approval-handlers.ts handleApprovalRespond). Rejection reason convention: 'This tool call was rejected by the user and not executed.' An unanswered approval parks the turn forever; always offer abort.

### Stop / abort the agent

- **Via:** `hub` · desktop has it
- **Kernel API:** run.abort {sessionId, reason?} -> {applied:true}; events run.aborted, approval.resolved{cancelled:true}
- **Notes:** Also cancels pending approvals and capability requests.

### Session status, notices and error banners

- **Via:** `hub` · desktop has it
- **Kernel API:** events session.updated {session|snapshot} (status idle|running|pending|completed|aborted|failed), session.notice {message, noticeType recovery|stop|status, reason api_error|mistake_limit|auto_compaction|...}, run.failed {error}
- **Notes:** Credential failures arrive as run.failed/session.notice api_error; map to the desktop's 'Sign in to Cline' / 'Open API providers' actions.

### Token usage ring and cost

- **Via:** `hub` · desktop has it
- **Kernel API:** event usage.updated {delta{inputTokens,outputTokens,cacheReadTokens,cacheWriteTokens,totalCost}, totals, aggregateUsage}; session.get {includeSnapshot:true} usage/aggregateUsage; contextWindow from the model catalog
- **Notes:** Context window size comes from list_provider_models (dashboard) or a bundled table; hide the ring when unknown, as the desktop does.

### Android notifications (task completed, approval needed, question asked, session error)

- **Via:** `hub` · desktop has it
- **Kernel API:** events ui.notify {title, body, severity, sessionId} (published by the Hub on completion), approval.requested, capability.requested (ask_question), run.failed
- **Notes:** Needs the socket alive while backgrounded (foreground service) or is delivered on resume. Desktop's show_session_notification is Tauri-only.

### Hub health indicator and transport states

- **Via:** `hub` · desktop has it
- **Kernel API:** /health poll + WebSocket state; hub.status {} -> {hubId, draining, activeRpcTurns, pendingRuns, eventLog{lastSequence}, idle}; event hub.drain_changed
- **Notes:** connecting | reconnecting | connected | unavailable as in desktop-client.ts, plus 'Termux asleep' when /health fails.

### Provider setup minimal (API key + model id) for first run

- **Via:** `sidecar` · desktop has it
- **Kernel API:** RUN_COMMAND: cline auth --provider <id> --apikey <key> --modelid <model> [--baseurl <url>] (exit 0, 'Provider configured: <id> (<model>)'); writes ~/.cline/data/settings/providers.json and lastUsedProvider
- **Notes:** Keeps keys in Termux so the TUI and the Hub share them; the app never stores provider secrets. Provider ids come from a bundled list (anthropic, openai-native, openrouter, gemini, xai, groq, mistral, deepseek, ollama, openai-compatible, ...).

## Tier 2 — sessions, settings, model

### Image attachments in prompts

- **Via:** `hub` · desktop has it
- **Kernel API:** session.send_input attachments.userImages: string[] of data: URLs (CLI pushes image.dataUrl, apps/cli/src/runtime/prompt.ts:194); userFiles are Termux filesystem paths
- **Notes:** Photo picker -> downscale -> data URL. File attachments would need a RUN_COMMAND copy into Termux; defer.

### Ask-question card (lettered options + free text)

- **Via:** `hub` · desktop has it
- **Kernel API:** session.create runtimeOptions.clientContributions:[{kind:'toolExecutor', executor:'askQuestion', capabilityName:'tool_executor.askQuestion'}]; event capability.requested {requestId, targetClientId, capabilityName, payload{executor,args,context}}; command capability.respond {requestId, ok, payload:{result}} with envelope.clientId = targetClientId
- **Notes:** Without the contribution the ask_question tool is simply absent (definitions.ts:971). The contribution is cancelled when the app disconnects, so hold the foreground service while a turn runs.

### Queue / steer follow-ups and the prompt queue editor

- **Via:** `hub` · desktop has it
- **Kernel API:** session.send_input {delivery:'queue'|'steer'}; session.pending_prompts {sessionId} -> {prompts[{id,prompt,delivery,attachmentCount}]}; session.steer_first_pending_prompt; session.update_pending_prompt {promptId, prompt?, delivery?}; session.remove_pending_prompt {promptId}; events session.pending_prompts, session.pending_prompt_submitted
- **Notes:** Composer placeholder 'Agent is working... submit to queue another message' when status is running.

### Session list (sidebar, Pinned/Scheduled/Tasks, projects grouping)

- **Via:** `hub` · desktop has it
- **Kernel API:** session.list {limit, rootOnly:true} -> {sessions: SessionRecord[]} (status, workspaceRoot, metadata{title, pinned, provider, model, prompt, source, scheduleId, totalCost, usage}, createdAt/updatedAt)
- **Notes:** Persisted sessions from sessions.db, not only live ones. Poll on resume + session.created/updated events; no 12 s poller.

### Session search

- **Via:** `hub` · desktop has it
- **Kernel API:** session.search {query, limit<=200, workspaceRoot?} -> {hits[{sessionId, documentId, role, title, snippet, startedAt, workspaceRoot, score}]}
- **Notes:** FTS5 index; may be empty when unavailable.

### Open / resume a history session

- **Via:** `hub` · desktop has it
- **Kernel API:** session.messages {sessionId} -> {messages: MessageWithMetadata[]} (works for non-live sessions: readHubSessionRecord + readSessionMessages); live: session.attach {sessionId}; not live after a daemon restart: session.create {sessionConfig:{sessionId:<old>, providerId, modelId,...}, initialMessages:<messages>, metadata} (isReadOnlyResumeStart, local-runtime-host.ts:409-414)
- **Notes:** Transcript rows: role user|assistant with content blocks text/image/tool_use/tool_result/thinking; render tool_use+tool_result pairs as tool rows like read_session_messages does.

### Rename, pin, session metadata

- **Via:** `hub` · desktop has it
- **Kernel API:** session.update {sessionId, title?, metadata?:{pinned?}} -> {updated, session}; event session.updated
- **Notes:** Server strips autoApproveTools and hubCapabilityOwnerClientId from client metadata.

### Delete session

- **Via:** `hub` · desktop has it
- **Kernel API:** session.delete {sessionId, deleteCheckpointRefs?} -> {deleted}
- **Notes:** Task-worktree removal is sidecar-only; not applicable.

### Fork session

- **Via:** `hub` · desktop has it
- **Kernel API:** client-side: session.messages -> session.create {initialMessages, metadata:{fork:{forkedFromSessionId, forkedAt, source}}} (session.fork is declared in HubCommandName but never dispatched)
- **Notes:** Same approach as the CLI's forkCurrentSession.

### Model / provider switch mid-session

- **Via:** `hub` · desktop has it
- **Kernel API:** session.update_connection {sessionId, updates:{providerId?, modelId?, thinking?, reasoningEffort?, thinkingBudgetTokens?, apiKey?, baseUrl?}} -> {sessionId, updated:true}
- **Notes:** Stored credentials for the new provider are merged daemon-side; omit apiKey.

### Reasoning effort selector (None/Low/Medium/High/Extra)

- **Via:** `hub` · desktop has it
- **Kernel API:** sessionConfig.thinking/reasoningEffort at create; session.update_connection thinking:false|null clears; values low|medium|high|xhigh
- **Notes:** Disabled when the model lacks reasoning (ProviderModel.supportsReasoning from the catalog).

### Plan / Act mode

- **Via:** `hub` · desktop has it
- **Kernel API:** sessionConfig.mode at create; per-turn session.send_input {mode:'act'|'plan'|'yolo'}
- **Notes:** Desktop hides the toggle (className hidden) but the CLI exposes it; ship it as the CLI does.

### Auto-approve all toggle

- **Via:** `hub` · desktop has it
- **Kernel API:** toolPolicies at session.create only (session.update strips autoApproveTools); flipping = recreate the session with sessionConfig.sessionId + initialMessages (what the CLI's restartWithCurrentMessages does)
- **Notes:** Persist the app default locally; the CLI's own persisted default lives in global-settings.json toolAutoApprove.

### Provider catalog (configured / popular / all, auth kind, config fields)

- **Via:** `sidecar` · desktop has it
- **Kernel API:** dashboard ws://127.0.0.1:8787/browser desktopCommand list_provider_catalog -> {providers: ProviderListItem[], settingsPath}; fallback RUN_COMMAND cat providers.json (configured ids only)
- **Notes:** ProviderListItem carries the plaintext apiKey; keep it in memory only, loopback only. Dashboard started on demand: RUN_COMMAND cline dashboard --no-open --host 127.0.0.1 --port 8787.

### Model list per provider (featured Recommended/Free tiers, capabilities)

- **Via:** `sidecar` · desktop has it
- **Kernel API:** dashboard {type:'loadModels', providerId} -> {type:'models', providerId, models} or desktopCommand list_provider_models {provider}
- **Notes:** No Hub or CLI JSON command lists models. Fallback: bundled static lists (FALLBACK_PROVIDER_MODELS) plus free-text model id.

### Save provider settings (key, base URL, enable/disable), add OpenAI-compatible provider

- **Via:** `sidecar` · desktop has it
- **Kernel API:** dashboard desktopCommand save_provider_settings {provider, enabled?, api_key?, base_url?}, add_provider {provider_id, name, base_url, api_key?, models, default_model_id}; fallback RUN_COMMAND cline auth --provider --apikey --modelid --baseurl
- **Notes:** enabled:false deletes the entry and clears legacy secrets.json keys (sign-out).

### Sign in with Cline (device-code OAuth) and sign out

- **Via:** `sidecar` · desktop has it
- **Kernel API:** RUN_COMMAND cline auth cline (WorkOS device flow; opens verification_uri_complete via xdg-open->termux-open; exits when tokens are saved) or dashboard runProviderOAuthLogin {providerId:'cline'} -> provider_oauth_login_done; sign-out = save_provider_settings {provider:'cline', enabled:false}
- **Notes:** The device code is prefilled in the URL, so the app needs no code display if the browser opens; if xdg-open is missing under the background shell, run the command with the foreground (terminal) runner so the user sees URL+code in Termux.

### Welcome setup notice (no provider connected)

- **Via:** `files` · desktop has it
- **Kernel API:** RUN_COMMAND cat ~/.cline/data/settings/providers.json -> lastUsedProvider + providers map (or dashboard list_provider_catalog configured flags)
- **Notes:** Read once at launch and after any auth action; never persist the file contents.

### Workspace picker (choose a folder for a new session)

- **Via:** `sidecar` · desktop has it
- **Kernel API:** RUN_COMMAND /data/data/com.termux/files/usr/bin/find <dir> -maxdepth 1 -mindepth 1 -type d (stdout list) + typed path; validity confirmed by session.create (list_workspace_directories/pick_workspace_directory are sidecar-only)
- **Notes:** Default roots: $HOME and ~/storage/shared if termux-setup-storage was run. Remember recent workspaces locally.

### Kernel settings: wake lock, keep Hub running, stop kernel, hub upgrade

- **Via:** `sidecar` · desktop has it
- **Kernel API:** RUN_COMMAND termux-wake-lock|termux-wake-unlock, cline hub stop, cline hub upgrade --wait 30; /version buildId vs installed
- **Notes:** Desktop equivalent is the Hub update dialog + hub_upgrade. Upstream's Hub has no idle exit (~300 MB RSS on the S25U), so expose an explicit stop.

### Theme (dark mode, dynamic colour, font size), app icon

- **Via:** `none` · desktop has it
- **Kernel API:** app-local (DataStore); Material You dynamic colour
- **Notes:** Reuse the owner's proven theme from ~/workspace/cline-termux-voice/android.

## Tier 3 — diffs, terminal, checkpoints, mentions

### Diff view (uncommitted changes per session, per-file hunks, +/- counts)

- **Via:** `hub` · desktop has it
- **Kernel API:** derived from tool.finished payloads of editor/apply_patch/write tools (input + output), same as desktop lib/session-diff.ts over chat_tool_call_* chunks; history via session.messages tool_use/tool_result blocks
- **Notes:** No generic file read/diff command exists on the Hub or the sidecar; the desktop derives diffs from tool payloads too.

### Terminal / command output with ANSI

- **Via:** `hub` · desktop has it
- **Kernel API:** tool.started {input{command}}, tool.updated {update} streaming chunks, tool.finished {output, error}
- **Notes:** Needs an ANSI-to-AnnotatedString renderer; exact update chunk shape for run_commands to be read in executors/bash.ts before building it.

### Proceed while a command keeps running

- **Via:** `hub` · desktop has it
- **Kernel API:** run.proceed_while_running {sessionId, toolCallId?} -> {detachedCount}
- **Notes:** Show the button on long-running command rows as the desktop does for toolDetachable.

### Checkpoints: edit-and-restart from a message, revert workspace

- **Via:** `hub` · desktop has it
- **Kernel API:** checkpoint history from session.get {includeSnapshot:true} snapshot.checkpoint.history / message metadata; session.restore {sessionId, checkpointRunCount, restore:{messages:true, workspace:true|false}, sessionConfig, metadata} -> {session, snapshot, messages, checkpoint}; checkpoints are created daemon-side when sessionConfig.checkpoint.enabled=true
- **Notes:** Desktop dialogs 'Revert to this checkpoint?' and 'Edit and restart from here?' map to restore with/without a new prompt.

### @file mentions / workspace file search

- **Via:** `sidecar` · desktop has it
- **Kernel API:** RUN_COMMAND rg --files <workspace> | head -2000 (or find -type f) cached in the app; mention_files.search is undispatched on the Hub
- **Notes:** Attach as userFiles paths (Termux paths) so the daemon reads them.

### Slash commands for skills and workflows

- **Via:** `hub` · desktop has it
- **Kernel API:** list: settings.list {workspaceRoot, cwd} snapshot.skills/workflows (id, name, path, enabled); invoke: the daemon's own user-instruction service handles skills when the skills executor exists, otherwise inline the file: RUN_COMMAND cat <item.path> and send as a user_command block like expandUserCommandPrompt
- **Notes:** createDefaultExecutors has no skills executor (definitions.ts:964 gates the skills tool); verify whether the daemon registers one for Hub sessions before relying on /skill passthrough.

### Git branch display, branch list/checkout, worktree

- **Via:** `sidecar` · desktop has it
- **Kernel API:** RUN_COMMAND git -C <ws> branch --show-current / for-each-ref / checkout; create_git_worktree is sidecar-only
- **Notes:** Branch label in the header; checkout and worktree are tier 4.

## Tier 4 — the rest

### Customize: rules / skills / workflows / plugins / tools / MCP toggles

- **Via:** `hub` · desktop has it
- **Kernel API:** settings.list {cwd, workspaceRoot, includePluginTools:true} -> {snapshot{workflows,rules,plugins,skills,tools,mcp}}; settings.toggle {type: skills|plugins|tools|mcp, id|path|name, enabled?} -> {snapshot, changedTypes}; event settings.changed
- **Notes:** Rules/workflows are not toggleable (same as desktop). Hooks list via RUN_COMMAND cline config hooks --json.

### MCP server list / add / edit / delete / OAuth

- **Via:** `sidecar` · desktop has it
- **Kernel API:** dashboard desktopCommand list_mcp_servers, upsert_mcp_server, delete_mcp_server, set_mcp_server_disabled, ensure_mcp_settings_file; OAuth via RUN_COMMAND cline mcp (wizard, foreground runner)
- **Notes:** authorize_mcp_server_oauth is desktop-sidecar-only.

### Marketplace browse / install / uninstall (skills, MCP, plugins)

- **Via:** `sidecar` · desktop has it
- **Kernel API:** dashboard GET /api/marketplace/catalog; desktopCommand list_marketplace_installed_entries, install_marketplace_entry, uninstall_marketplace_entry, uninstall_local_primitive
- **Notes:** Installs run git clones inside Termux.

### Schedules (routines): list, create, edit, pause/resume, run now, runs history

- **Via:** `hub` · desktop has it
- **Kernel API:** schedule.list/get/create/update/delete/enable/disable/trigger/list_executions/stats/active/upcoming; events schedule.*; requires a registered client with workspaceContext.workspaceRoot; allWorkspaces needs token auth
- **Notes:** Scheduled sessions run inside the daemon; they need Termux alive at fire time (wake lock).

### Account (credits balance, usage, billing, organizations, switch)

- **Via:** `sidecar` · desktop has it
- **Kernel API:** dashboard desktopCommand cline_account {operation: fetchMe|fetchBalance|fetchUserOrganizations|fetchOrganizationBalance|fetchUsageTransactions|fetchPaymentTransactions|switchAccount}
- **Notes:** Returns {signedIn:false, code:'ACCOUNT_NOT_AUTHENTICATED'} when signed out.

### Global settings: telemetry opt-out, auto-update

- **Via:** `sidecar` · desktop has it
- **Kernel API:** dashboard desktopCommand get_global_settings, set_telemetry_opt_out {telemetry_opt_out}, set_auto_update_enabled {auto_update_enabled} (file ~/.cline/data/settings/global-settings.json)
- **Notes:** Auto-update is irrelevant on the Termux port (installer-managed); show telemetry only.

### Agents roster (subagents / teams) and team progress

- **Via:** `hub` · desktop has it
- **Kernel API:** event team.progress; session.list {rootOnly:false} children via metadata.parentSessionId; events carry payload.agent {kind lead|subagent|teammate}
- **Notes:** assistant.delta has no agent field; follow the CLI's use-agent-events.ts handling.

### Session export (HTML / JSON) and share

- **Via:** `sidecar` · not in desktop
- **Kernel API:** RUN_COMMAND cline history export <id> -o ~/storage/downloads/<id>.html then Android share of the file (needs termux-setup-storage)
- **Notes:** CLI-only feature; cheap to add.

### Diagnostics export

- **Via:** `sidecar` · desktop has it
- **Kernel API:** RUN_COMMAND cline doctor --json; tail of ~/.cline/data/logs/hub-daemon.log and cline.log (100 KB result cap)
- **Notes:** export_diagnostics is sidecar-only.

### Channels / connectors (Telegram, Slack, Discord, WhatsApp, GChat, Linear)

- **Via:** `hub` · desktop has it
- **Kernel API:** connector.channels, connector.configure, connector.delete_config, connector.start, connector.stop, connector.supervised
- **Notes:** Hidden in desktop (HIDDEN_SECTIONS); fully available on the Hub. Ship behind a 'Labs' toggle or omit.

### Agenda tasks

- **Via:** `hub` · not in desktop
- **Kernel API:** task.create/list/get/update/approve/cancel/run, task.automation.get/set
- **Notes:** Feature-flagged off in desktop (AGENDA_UI_ENABLED=false) and compile-time disabled in the Hub tool; omit.

### Manual /compact

- **Via:** `none` · not in desktop
- **Kernel API:** none over the Hub (the CLI runs its own summarizer, then session.compaction.update which is creator-gated); auto-compaction runs daemon-side
- **Notes:** Omit; auto-compaction covers it.

### Voice input (streaming transcription)

- **Via:** `none` · desktop has it
- **Kernel API:** none (create_streaming_transcription_session is desktop-sidecar-only); Android SpeechRecognizer is on-device
- **Notes:** Use android.speech.SpeechRecognizer into the composer; no kernel involvement.

### Import sessions (Claude Code / Codex / opencode)

- **Via:** `none` · desktop has it
- **Kernel API:** none (sidecar-only; those tools do not exist in Termux)
- **Notes:** Omit.

### Cloud sessions, remote SSH environments, pull-request bar, open-in-editor, folder picker dialog, app updater, tray

- **Via:** `none` · desktop has it
- **Kernel API:** none applicable on Android (sidecar/Tauri-only; gh/editors absent in Termux)
- **Notes:** Omit; 'Open in' becomes Android share via termux-open where a path exists.

### About / What's new / release notes

- **Via:** `hub` · desktop has it
- **Kernel API:** GET /version (coreVersion, buildId); RUN_COMMAND cline --version; app changelog bundled
- **Notes:** Release notes link to github.com/IChouChiang/cline-termux releases.

## Corrections from recorded traffic (2026-10-03, see 08-hub-fixtures.md)

- **Checkpoints are not created by the daemon.** `sessionConfig.checkpoint.enabled` alone leaves `snapshot.checkpoint.history` empty; creation is a *client contribution* (`runtimeOptions.clientContributions: [{kind:'checkpoint', capabilityName:'checkpoint.create'}]`, answered over `capability.requested`). The app must implement that capability itself (a git snapshot run inside Termux) or ship the Tier-3 checkpoint dialogs disabled. `session.restore` has nothing to restore without it.
- **`ui.notify` does not fire per turn.** It is published only when a session is disposed. Build the 'task completed / failed / approval needed' notifications from `run.completed`, `run.failed`, `approval.requested` and `capability.requested`.
- **Every session event is also delivered on the global subscription** (same `eventId`/`sequence`). Subscribe globally with one persisted cursor and route by `sessionId`, or dedupe by `sequence`.
- **`session.update {metadata}` replaces metadata wholesale.** Spread the current `session.metadata` and set the changed key (`pinned`, …), as the desktop does.
- **User text is persisted wrapped** as `<user_input mode="act">…</user_input>` (also in `metadata.prompt`); strip it for display like upstream's `formatDisplayUserInput`, and read the mode attribute for the per-message Plan/Act marker.
- **Rejecting an approval does not stop the turn**: the tool reports the rejection as its result and the model continues; 'reject and stop' needs `run.abort` as well.
- `run.started {requestId, clientId}` echoes the `session.send_input` envelope — correlate the turn by it. `agent.done` precedes `run.*` by a few ms; `run.completed` with `reason != "completed"` (e.g. `disposed`) is not success.
- `session.search` lags for live sessions (the FTS index refreshes on session end); `session.delete` may reply `deleted:false` for a session without history even though it is gone.
- `metadata.source: "android"` advertises the `tasks` tool and the Agenda prompt section; `ask_question` needs the askQuestion client contribution at `session.create`; `skills` appears only when at least one skill is configured.
