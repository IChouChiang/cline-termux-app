# Screens

_Generated on 2026-10-03 from `docs/research/kernel-and-desktop-synthesis.json` (multi-agent code reading of the Cline monorepo at cli-v3.0.68 + the Termux port). Line references point into `~/workspace/cline-termux-v3-phone`._

Each screen names its Cline Desktop equivalent so the design phase can align layout, copy and behaviour with the desktop app.

## Tier 1 — connect and chat

### Kernel setup (first run): Termux check, RUN_COMMAND permission, allow-external-apps hint, port version check, Start kernel

- **Desktop equivalent:** Onboarding 'welcome' step + boot gate ('Desktop backend unavailable' / 'Connecting...') + Hub update required dialog
- **Data sources:** PackageManager <queries> com.termux; runtime permission result; RUN_COMMAND `cline --version` and `cline hub start`; GET /health, /version; RUN_COMMAND cat production.json

### Connect a model (first run): Sign in with Cline, or provider + API key + model id

- **Desktop equivalent:** Onboarding 'connect' step / WelcomeSetupNotice ('Connect a model to start building')
- **Data sources:** RUN_COMMAND `cline auth cline` (foreground runner if browser cannot open) or `cline auth --provider --apikey --modelid`; RUN_COMMAND cat providers.json for the configured state; dashboard provider_catalog when available

### Home / new thread (welcome state): hero, workspace chip, model chip, composer

- **Desktop equivalent:** WelcomeScreen (AgentConversationLayout welcome=true) + EnvironmentSelector/WelcomeWorkspaceControls
- **Data sources:** session.create on first send; local recent-workspace list; provider/model from local selection; GET /health for the kernel chip

### Chat thread: transcript (user bubbles, assistant markdown, reasoning disclosure, tool rows, work rows, images), streaming indicator, scroll-to-latest

- **Desktop equivalent:** ChatThreadPane + ChatMessages + message-bubble / tool-message-block / reasoning-block / work-block
- **Data sources:** events assistant.delta/finished, reasoning.delta/finished, tool.started/updated/finished, assistant.media, session.notice, usage.updated, run.*; history via session.messages

### Composer: text, attach image, Send/Stop, model chip, reasoning chip, Plan/Act chip, usage ring, queued-prompt strip

- **Desktop equivalent:** ChatInputBar / AgentComposer (+ AgentPromptQueue, TokenUsageRing, ModelSelector, EFFORT_LEVELS)
- **Data sources:** session.send_input {prompt, mode, delivery, attachments.userImages}; run.abort; session.pending_prompts; usage.updated; session.update_connection for the chips

### Tool approval bottom sheet: tool name, formatted input (command / file / URL), Approve / Reject, 'Request <id> · Iteration N'

- **Desktop equivalent:** ToolApprovalPanel / AgentApprovalCard ('Tool approval required')
- **Data sources:** event approval.requested (inputJson); command approval.respond; event approval.resolved; re-issue on subscribe

### System notifications: task completed / failed, approval needed, question asked, deep link to session; foreground 'Cline is working' notification

- **Desktop equivalent:** Desktop notification settings matrix + show_session_notification (Tauri)
- **Data sources:** events ui.notify, run.completed/failed, approval.requested, capability.requested; foreground service state

## Tier 2 — sessions, settings, model

### Kernel status sheet: Hub up/down, pid, uptime, core/build version, wake lock state, Start / Stop / Upgrade, One UI battery checklist

- **Desktop equivalent:** Sidebar logo HoverCard ('Cline Hub @<port>', 'Cline Hub is not connected.') + About > Updates + HubUpdateRequiredDialog
- **Data sources:** GET /health, /version; hub.status; RUN_COMMAND cline hub start|stop|upgrade, termux-wake-lock|unlock, cline doctor --json

### Ask-question sheet: question, lettered options, multiple-select, free-text answer

- **Desktop equivalent:** AgentAskQuestion card
- **Data sources:** event capability.requested (tool_executor.askQuestion payload args); command capability.respond {requestId, ok, payload:{result}}

### Prompt queue sheet: edit, remove, steer queued prompts

- **Desktop equivalent:** AgentPromptQueue
- **Data sources:** session.pending_prompts, session.update_pending_prompt, session.remove_pending_prompt, session.steer_first_pending_prompt; events session.pending_prompts / pending_prompt_submitted

### Sessions drawer / navigation rail: Pinned, Scheduled, Tasks sections, Projects grouping, status dots, unread, search entry, New session

- **Desktop equivalent:** AgentSidebar (CategorySection, ProjectSection, ThreadItem/AgentSessionRow, context menu Pin/Rename/Fork/Delete)
- **Data sources:** session.list {rootOnly:true}; events session.created/updated; session.update (pin/rename); session.delete; local unread state

### Sessions screen (full): sort, filters (status, workspace, provider, model, pinned), rows with tokens/cost/time, row actions, delete confirm

- **Desktop equivalent:** SessionsView table + Delete Session AlertDialog
- **Data sources:** session.list; session.get for usage; session.update/delete; search via session.search

### Session search: query, hits with role/snippet/title, open hit

- **Desktop equivalent:** SessionCommandBar ('Search session history', Cmd/Ctrl+P)
- **Data sources:** session.search {query, limit:50}

### Conversation header: status dot, editable title, session actions (fork, delete, restore), diff stats '+N -N', agents badge

- **Desktop equivalent:** AgentConversationHeader + AgentActivityStatus
- **Data sources:** session.get; session.update {title}; session.delete; derived diff stats from tool payloads; team.progress

### Model picker: provider list (configured first), model list with Recommended/Free sections and capability badges, reasoning effort, 'Set up another provider'

- **Desktop equivalent:** ModelSelector / buildModelPickerData (+ mobile <=560 px popover variant)
- **Data sources:** dashboard provider_catalog + loadModels (fallback: bundled lists + free text); session.update_connection; local persistence like cline.code.model-selection.v1

### Workspace picker: recent workspaces, folder browser (Home/Up/path/Refresh), typed path, 'Just chat'

- **Desktop equivalent:** WorkspacePicker + RemoteDirectoryPicker ('Choose remote workspace')
- **Data sources:** RUN_COMMAND find -maxdepth 1 -type d; local recents; session.create validates

### Settings home: Appearance (dark mode/dynamic colour/font size), Kernel, Providers, Account, Customize, Schedules, Notifications, Telemetry, About

- **Desktop equivalent:** Settings shell + Settings > General (sections map in sections.ts)
- **Data sources:** DataStore; dashboard get_global_settings/set_telemetry_opt_out when available

### Providers: list (Configured / Popular / All, search) and detail (API key fields with show/copy, base URL, Sign in with browser, models card with refresh and favorites, Disconnect), Add OpenAI-compatible provider

- **Desktop equivalent:** Settings > Providers (ProviderListContent, ProviderDetailContent, Add Provider)
- **Data sources:** dashboard list_provider_catalog, list_provider_models, save_provider_settings, add_provider, runProviderOAuthLogin; fallback RUN_COMMAND cline auth

### Image lightbox / carousel for assistant and tool output images

- **Desktop equivalent:** ImageCarousel + ImageLightbox
- **Data sources:** assistant.media {media.source base64|url}; tool.finished output images

## Tier 3 — diffs, terminal, checkpoints, mentions

### Changes screen: per-file +/- counts, expandable hunks, copy path, share

- **Desktop equivalent:** DiffView / AgentChangesPanel ('Uncommitted changes', 'No file changes in this session yet.')
- **Data sources:** derived from editor/apply_patch tool.finished payloads and session.messages tool blocks (desktop lib/session-diff.ts logic)

### Tool detail screen: full command output with ANSI, file diff per hunk, 'Proceed while running'

- **Desktop equivalent:** ToolCallRow expanded (AgentCommandOutput, ToolFileDiff) + proceed_while_running button
- **Data sources:** tool.started/updated/finished; run.proceed_while_running

### Checkpoint dialogs: 'Revert to this checkpoint?', 'Edit and restart from here?' with chat-only vs chat+workspace choice

- **Desktop equivalent:** ChatMessages dialogs (Revert / Continue) + CLI CheckpointConfirm
- **Data sources:** session.get snapshot.checkpoint.history; session.restore {checkpointRunCount, restore:{messages, workspace}}

### Mentions and slash menus in the composer: @files (workspace index), /skills /workflows (settings.list), /fork

- **Desktop equivalent:** ChatInputBar slash menu + @mention menu (search_workspace_files, list_user_instruction_configs)
- **Data sources:** RUN_COMMAND rg --files; settings.list skills/workflows; RUN_COMMAND cat <skill path> for inline expansion

## Tier 4 — the rest

### Customize: Tools / Plugins / Skills / Rules / MCP / Hooks tabs with toggles, counts, Copy path

- **Desktop equivalent:** CustomizeView + CustomizationSectionView + McpServersContent
- **Data sources:** settings.list / settings.toggle; event settings.changed; dashboard list_mcp_servers / upsert / delete / set_mcp_server_disabled; RUN_COMMAND cline config hooks --json

### MCP server editor: Local (command/args/env) or Remote (URL/headers/transport), enabled switch, delete confirm

- **Desktop equivalent:** MCP editor dialog ('Add MCP Server' / 'Edit MCP Server')
- **Data sources:** dashboard upsert_mcp_server {name, previousName, transportType stdio|sse|streamableHttp, command, args, cwd, env, url, headers, disabled}, delete_mcp_server

### Marketplace: search, type chips (Skills / MCP servers / Plugins), tag filter, detail with Install/Uninstall, env setup notes

- **Desktop equivalent:** MarketplaceExplorerView + MarketplaceView
- **Data sources:** dashboard GET /api/marketplace/catalog; list_marketplace_installed_entries; install/uninstall_marketplace_entry (300 s)

### Schedules: cards (enabled dot, mode, trigger, next/last run), create/edit (name, frequency, time, prompt, provider/model, workspace), details with runs

- **Desktop equivalent:** Settings > Schedules (RoutineSchedulesContent, routine templates)
- **Data sources:** schedule.list/active/upcoming/list_executions/create/update/delete/enable/disable/trigger; events schedule.*

### Account: profile, credits balance, organizations (switch), usage and billing tables, sign out

- **Desktop equivalent:** Settings > Account (overview | usage | billing)
- **Data sources:** dashboard cline_account {operation}; sign-out via save_provider_settings {provider:'cline', enabled:false}

### Agents roster sheet: subagent/teammate rows with state, prompt, last action

- **Desktop equivalent:** AgentActivityStatus popover 'Agents' + AgentRoster
- **Data sources:** team.progress events; session.list {rootOnly:false} children by metadata.parentSessionId

### About / What's new / Diagnostics: app version, kernel core/build versions, highlights, 'Export diagnostics', report an issue

- **Desktop equivalent:** Settings > About + WhatsNewDialog + ExportDiagnosticsDialog
- **Data sources:** GET /version; RUN_COMMAND cline --version, cline doctor --json; bundled changelog
