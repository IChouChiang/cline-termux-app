# Terminal functions against the desktop and the app (A9 inventory)

Research note for decision A9 (docs/decided.md): every user-facing function of the
interactive terminal (`cline --tui`, kernel `cli-v3.0.68` as ported in `v3.0.68-termux.2`),
checked against Cline Desktop (`apps/examples/desktop-app`) and this app. Read-only
inventory, 2026-10-07; every row was checked in the source, nothing from memory.

Path prefixes: `cli/` = `cline-termux-v3-phone/apps/cli/src/`, `desk/` =
`cline-termux-v3-phone/apps/examples/desktop-app/`, `core/` =
`cline-termux-v3-phone/sdk/packages/core/src/`, `app/` =
`cline-termux-app/app/src/main/kotlin/dev/cline/termux/`.

Verdicts: **covered** (desktop and app have it) · **app gap** (desktop has it, app does
not: P1 rule 1 defect) · **terminal-only candidate** (no desktop control: rule 3
decision) · **presentation** (rule 2) · **n/a** (terminal-only by nature).

## 1. Inventory

### 1.1 Slash commands (registry `cli/tui/commands/slash-command-registry.ts`, dispatch `cli/tui/hooks/local-command-actions.ts`)

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| A1 | `/settings` | registry.ts:54; local-command-actions.ts:27 | Opens the Settings panel (General + 7 tabs, §1.5) | `desk/webview/components/views/settings/settings-view.tsx` | `app/ui/settings/SettingsHomeScreen.kt:65` | covered |
| A2 | `/config` (hidden alias) | registry.ts:58 | Same as `/settings` | as A1 | as A1 | covered |
| A3 | `/model` | registry.ts:63; `cli/tui/hooks/use-model-selector.tsx` | Provider and model picker, thinking level, key/OAuth entry | `desk/webview/components/views/chat/chat-input-bar.tsx:1648` (ModelSelector), :187 (EFFORT_LEVELS) | `app/ui/model/ModelPickerSheet.kt:147`, `ReasoningMenu.kt:37` | covered |
| A4 | `/theme` | registry.ts:67; `cli/tui/hooks/theme-provider.tsx:59` (writes `tuiTheme`) | Terminal colour theme | `settings-view.tsx:906` (Dark mode) | `app/ui/settings/AppearanceScreen.kt:85` | presentation |
| A5 | `/account` | registry.ts:71; `cli/tui/components/dialogs/account-dialog.tsx:50-77` | Account, credits, switch organization, sign in | `desk/webview/components/views/settings/account-view.tsx` | `app/ui/account/AccountScreen.kt:191`, :470 | covered |
| A6 | `/mcp` | registry.ts:75; `cli/tui/components/dialogs/mcp-manager-dialog.tsx:39`, :133 | Enable/disable MCP servers ("Run cline mcp to add, edit, or remove") | `desk/webview/components/views/settings/mcp-view.tsx` | `app/ui/customize/tabs/McpTab.kt:55`, `customize/mcp/McpServerEditor.kt:159` | covered |
| A7 | `/plugins` | registry.ts:79; local-command-actions.ts:31 | Settings at the Plugins tab | `desk/webview/components/views/settings/customize-view.tsx:217` | `app/ui/customize/tabs/PluginsTab.kt:41` | covered |
| A8 | `/compact` | registry.ts:83; `cli/tui/hooks/use-local-command-actions.tsx:129`; `cli/runtime/interactive/session-runtime.ts:696` | Summarises the conversation now (manual compaction; also Opt+X) | none | none | **terminal-only candidate** |
| A9 | `/skills` | registry.ts:87; `cli/tui/root.tsx:451` | Picks a skill/workflow to insert; link to the skills marketplace | `chat-input-bar.tsx:124` (slash menu), `views/marketplace-view.tsx` | `app/ui/suggestions/SlashMenu.kt:23`, `customize/tabs/SkillsTab.kt:30`, `marketplace/MarketplaceScreen.kt:101` | covered |
| A10 | `/fork` | registry.ts:92; use-local-command-actions.tsx:169; session-runtime.ts:621 | Confirm, then copy the session into a new active one | `chat-input-bar.tsx:113`; `components/agent-sidebar.tsx:717` | `app/ui/chat/ConversationHeader.kt:520` (three-dot "Fork"); `sessions/SessionRowMenu.kt:66` (batch 2: no `/fork` in the popup, A19) | covered |
| A11 | `/undo` | registry.ts:96; root.tsx:344 | Checkpoint picker, restore chat only or chat + workspace, rewound prompt prefilled | `views/chat/chat-messages.tsx:827`, :863 | `app/ui/checkpoint/CheckpointPickerSheet.kt:75`, `CheckpointDialogs.kt:154` | covered |
| A12 | `/clear` | registry.ts:100; root.tsx:321 | Starts a new session | `desk/webview/app/page.tsx:482` (handleNewThread) | `app/ui/AppShell.kt:323` ("+") | covered (A8) |
| A13 | `/history` | registry.ts:104; use-local-command-actions.tsx:62 | Picks and resumes a past session (delete/export in §1.6) | `agent-sidebar.tsx`; `views/sessions/sessions-view.tsx` | `app/ui/sessions/SessionsDrawer.kt:110`, `SessionsScreen.kt:134` | covered |
| A14 | `/help` | registry.ts:108; `cli/tui/components/dialogs/help-dialog.tsx` | Lists keys and commands | none | none | n/a |
| A15 | `/quit` | registry.ts:112 | Exits | none | none | n/a |
| A16 | `/team <task>` | `cli/tui/interactive-welcome.ts:137`; `cli/runtime/interactive/chat-command-runner.ts:52`; `cli/utils/team-command.ts:11` | Turns on agent teams (session restart) and asks for a team | `chat-input-bar.tsx:116` | `app/data/chat/SlashCommandSource.kt:75` | covered |
| A17 | `/<skill>`, `/<workflow>` | registry.ts:162, :259 | Inserts and expands a user command | `chat-input-bar.tsx:124` | `SlashCommandSource.kt:115` | covered |
| A18 | Plugin commands (`api.registerCommand`) | registry.ts:220; `cli/runtime/run-interactive.ts:230`, :597 | Lists and runs plugin commands client-side | `chat-input-bar.tsx:149`, :1026; `desk/sidecar/commands.ts:3578` (`list_plugin_commands`) | none (known: docs/09 §5) | **app gap** |
| A19 | `/new` (typed, not listed) | `cli/utils/chat-commands.ts:316` | Alias of `/clear` | as A12 | as A12 | covered |
| A20 | `/start` (typed) | chat-commands.ts:310 | Prints the connector command list | none | none | n/a |
| A21 | `/whereami` (typed) | chat-commands.ts:359; chat-command-runner.ts:100 | Session id, tools, yolo, cwd | `agent-sidebar.tsx:1643` (getSessionOverviewItems) | `app/ui/sessions/SessionDetailsSheet.kt:86` | covered |
| A22 | `/tools [on\|off\|toggle]` (typed) | chat-commands.ts:369; `cli/runtime/interactive/session-config.ts:24` | Allows or forbids all tools from the next session start | none (`webview/hooks/chat-session/constants.ts:38` always true) | none (`app/core/kernel/hub/HubSessionApi.kt:68` always true) | **terminal-only candidate** |
| A23 | `/yolo [on\|off\|toggle]` (typed) | chat-commands.ts:385; chat-command-runner.ts:82 | Auto-approve for this client only, no global write | none | none | **terminal-only candidate** (A1) |
| A24 | `/cwd <path>` (typed) | chat-commands.ts:401; session-config.ts:25 | Moves cwd/workspace root, applied at the next session restart | workspace choice for new sessions only (`views/chat/welcome-workspace-controls.tsx`) | new sessions only (`app/ui/workspace/WorkspacePickerSheet.kt:156`) | **terminal-only candidate** |
| A25 | `/exit` (typed) | chat-commands.ts:351 | Stops the TUI | none | none | n/a |
| A26 | `/schedule …` (typed) | chat-commands.ts:470 | In the TUI it only answers "Scheduling is not available in this chat." (no schedule context, chat-command-runner.ts:70-109) | n/a | n/a | n/a |

`/abort`, `/mute`, `/unmute` are connector-only (`isAvailable` false in the TUI,
chat-commands.ts:325-349); typed there they go to the model as a prompt.

### 1.2 Keys (`cli/tui/hooks/use-root-keyboard.ts` unless noted; list in help-dialog.tsx:16-112)

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| B1 | Enter | `cli/tui/hooks/use-prompt-input-controller.ts:428`; `cli/tui/components/input-bar.tsx:238` | Submits, or picks the highlighted menu row | `chat-input-bar.tsx:1339` | `app/ui/composer/ComposerField.kt:196` | covered |
| B2 | Shift/Ctrl/Meta+Enter | input-bar.tsx:239-241 | Newline | `chat-input-bar.tsx:1339` (Shift) | ComposerField.kt:197 | covered |
| B3 | Enter while running | use-prompt-input-controller.ts:438 | Queues the prompt (`delivery: queue`) | `webview/hooks/use-chat-session.ts:3053` | `app/ui/composer/ComposerController.kt:556` | covered |
| B4 | Enter on an empty input with a queue | :216-230; root.tsx:541 | Steers the first queued prompt | `chat-input-bar.tsx:1351` | ComposerController.kt:692; `app/ui/queue/PromptQueueController.kt:182` | covered |
| B5 | Ctrl+S | :314 | Sends the typed text as a steer while running | none (queue, then Steer: chat-input-bar.tsx:546) | none (same two steps: `app/ui/queue/PromptQueueStrip.kt:49`) | **terminal-only candidate** |
| B6 | Up/Down, Tab, Enter, Esc on the queue | :168-214, :263 | Selects, edits, steers, deselects a queued prompt | AgentPromptQueue, `chat-input-bar.tsx:1110` | `app/ui/queue/PromptQueueSheet.kt:125` | covered |
| B7 | Tab | :295; root.tsx:225; run-interactive.ts:780 | Plan/Act toggle; saves `planActMode` globally; a toggle mid-run aborts the run and applies after | `chat-input-bar.tsx:1512` | `app/ui/composer/ComposerSettingsRow.kt:311` | covered |
| B8 | Shift+Tab | :300; `cli/tui/contexts/session-context.tsx:202`; run-interactive.ts:769 | Auto-approve all on/off: global `toolAutoApprove`, restart at run end | none | none | **terminal-only candidate** (A1) |
| B9 | Esc while running | :266 | Aborts the run | `chat-input-bar.tsx:1334` | ComposerController.kt:703 | covered |
| B10 | Esc Esc (idle) | :276-283 | Opens checkpoint restore (= `/undo`) | restore from the message row | restore from the message row | presentation |
| B11 | Esc in menus | :113 | Closes the autocomplete menu | `chat-input-bar.tsx:1302` | suggestion panel | presentation |
| B12 | Ctrl+C | :91 | Clears the input, else exits | none | none | n/a |
| B13 | Ctrl+D | :288 | Exits when idle with an empty input | none | none | n/a |
| B14 | Ctrl+L | :305 | New session; while running only clears the screen | Ctrl+N, `page.tsx:768` | Ctrl+N, `app/ui/navigation/Shortcuts.kt:29` | presentation |
| B15 | Ctrl+P | :151; root.tsx:818 | Command palette of the slash actions | none (desktop Ctrl+P is session search, `page.tsx:771`) | none | presentation |
| B16 | Opt+S/M/P/T/C/G/A/X/W/R/U/L/H/K/Q | `cli/tui/components/dialogs/command-palette-items.ts:39-145`; root.tsx:847 | One-key palette shortcuts (Opt+P change provider, Opt+X `/compact`) | none | none | presentation |
| B17 | Up/Down prompt history | :245-260; `cli/utils/input-history.ts:5-9` | Recalls the last 20 prompts, persisted in `~/.cline/data/cache/user_input_history.jsonl` | none | none | **terminal-only candidate** |
| B18 | Up/Down transcript scroll (port) | `cli/tui/hooks/root-keyboard-routing.ts:18-31`; :232 | Termux touch-scroll mapping | n/a | touch scroll | presentation |
| B19 | PgUp/PgDn, Ctrl+Alt+B/F/U/D, Ctrl+G, Ctrl+Alt+G | `cli/tui/hooks/transcript-keybinds.ts:30-43` | Scrolls the transcript, jumps to first/last | scrolling | touch scroll, `app/ui/components/ScrollToLatestButton.kt` | presentation |
| B20 | Up/Down/Ctrl+P/Ctrl+N/Tab in menus | :111-145 | Moves and picks in the `/` and `@` menus | `chat-input-bar.tsx:1277-1330` | ComposerField.kt:194 (`onPanelKey`) | covered |

### 1.3 Input

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| C1 | `@file` mention | `cli/tui/hooks/use-autocomplete.ts:23-73` | Fuzzy workspace file search, inserts `@path` | `chat-input-bar.tsx:1307` | `app/ui/suggestions/MentionMenu.kt:34` | covered |
| C2 | Image paste, Ctrl+V clipboard image | input-bar.tsx:147-191 | Attaches images as `[Image N]` | `chat-input-bar.tsx:1259` | ComposerField.kt:99; `app/ui/composer/AttachSheet.kt:101` | covered |
| C3 | Large paste (≥ 5 lines) | input-bar.tsx:161; `cli/tui/utils/pasted-snippets.ts:6` | Collapses into a marker, expanded on send | none | none | presentation |
| C4 | Mouse selection copy | root.tsx:481-492 | Copies the selection via OSC 52 | copy buttons | `app/ui/chat/rows/MessageActions.kt:132` | presentation |

### 1.4 Prompts and indicators

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| D1 | Tool approval | `cli/tui/components/inline-tool-response.tsx:182-245` | y/Enter approve, n/Esc deny | `views/chat/messages/tool-approval-panel.tsx` | `app/ui/approval/ApprovalSheet.kt:98` | covered |
| D2 | Ask question | inline-tool-response.tsx:247-400 | Numbered options plus a typed answer | `chat-messages.tsx:750` | `app/ui/question/AskQuestionSheet.kt:117` | covered |
| D3 | Mistake-limit prompt | `cli/runtime/interactive/mistakes.ts:9-60`; session-config.ts:29 | After repeated mistakes asks "Try a different approach / Stop this run" (typed guidance accepted) | `desk/sidecar/chat-session.ts:444-583` | none (only the askQuestion contribution, `HubSessionApi.kt:89-99`) | **app gap** |
| D4 | `switch_to_act_mode` tool | `cli/runtime/interactive/mode.ts:31-66`, :76-105; run-interactive.ts:255-261 | In Plan mode the model switches to Act after the user approved, and the TUI continues the plan by itself | none (no hit in desk) | none | **terminal-only candidate** |
| D5 | Hub version dialogs | root.tsx:586-701 | Outdated Hub: upgrade now or later; protocol mismatch: update | `components/hub-update-required-dialog.tsx` | `app/ui/kernel/HubUpdateRequiredDialog.kt:205`, `KernelStatusSheet.kt:144` | covered |
| D6 | Onboarding | root.tsx:1059; `cli/tui/views/onboarding/` | First-run sign-in or provider + key + model | `views/onboarding/onboarding-view.tsx` | `app/ui/onboarding/ConnectModelScreen.kt:121` | covered |
| D7 | Status bar | `cli/tui/components/status-bar.tsx:184-278` | Model, context tokens, cost, Plan/Act, auto-approve state, workspace, branch, diff | `views/chat/token-usage-ring.tsx`; `components/agent-header.tsx` | `app/ui/composer/ContextUsage.kt:88`; `chat/ConversationHeader.kt:164`; `chat/BranchSheet.kt:61` | covered (auto-approve state: A1) |
| D8 | Home notices | root.tsx:555-584; interactive-welcome.ts:170 | Welcome line (email, credits, org), ClinePass and migration notices | `components/whats-new-dialog.tsx` | `app/ui/about/WhatsNewDialog.kt` | presentation |
| D9 | Team progress | `cli/tui/hooks/use-agent-events.ts` (team events) | Shows teammates' activity | `agent-header.tsx:326` | `app/ui/agents/AgentsRosterSheet.kt` | covered |
| D10 | Terminal title | `cli/tui/hooks/use-terminal-title.ts` | Window title follows the session | none | none | n/a |

### 1.5 Settings panel (`cli/tui/views/config-view.tsx`; tabs `cli/tui/views/config-view-helpers.ts:20-29`)

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| E1 | Provider | config-view.tsx:479 | Opens the provider picker | `views/settings/provider-list-view.tsx` | `app/ui/providers/ProvidersListScreen.kt:96` | covered |
| E2 | Model | :480 | Opens the model picker | as A3 | as A3 | covered |
| E3 | Mode | :481, :626 | Plan/Act (as Tab) | as B7 | as B7 | covered |
| E4 | Theme | :482 | Opens `/theme` | as A4 | as A4 | presentation |
| E5 | Compaction LLM / Truncation / Off | :483, :644; `cli/utils/compaction-mode.ts:3-20`; run-interactive.ts:774 | Sets the strategy, saves it globally, restarts the session | none (always on) | none | **terminal-only candidate** |
| E5a | Auto-compaction on by default | compaction-mode.ts:5-8, :36-37; `cli/main.ts:1075` | Sessions compact automatically near the context limit | `desk/sidecar/chat-session.ts:633-635` (`compaction: { enabled: true }`) | none: `HubSessionApi.kt:61-73` sends no `compaction` | **app gap** |
| E6 | Auto-approve all | :484, :633 | As Shift+Tab | none | none | **terminal-only candidate** (A1) |
| E7 | Auto update | :489, :637 | Global `autoUpdateEnabled` (CLI self-update) | `settings-view.tsx:1057` ("Keep CLI up to date") | none | n/a (the port's launcher exports `CLINE_NO_AUTO_UPDATE=1`, `release/install-cline-termux.sh:501`; `cli/commands/update.ts:312`) |
| E8 | Verbose | :490, :650; use-agent-events.ts:244; use-prompt-input-controller.ts:371 | Per-turn line (tokens, cost, time, iterations) and recoverable-error notices; this run only | none | none | **terminal-only candidate** |
| E9 | MCP tab | :559 | List, toggle, open the MCP manager | `customize-view.tsx:206` | McpTab.kt:55 | covered |
| E10 | Skills tab | :521 | List, toggle | `customize-view.tsx:198` | SkillsTab.kt:30 | covered |
| E11 | Rules tab | :538 | List | `customize-view.tsx:225` | `app/ui/customize/tabs/RulesTab.kt:24` | covered |
| E12 | Tools tab | :506 | List, toggle (plugin tools included) | `customize-view.tsx:237` | `app/ui/customize/tabs/ToolsTab.kt:32` | covered |
| E13 | Plugins tab | :523; :731 (`d` deletes) | Toggle, delete, details | `customize-view.tsx:217` | PluginsTab.kt:41; `app/core/kernel/termux/ClineCommands.kt:185` | covered |
| E14 | Agents tab | config-view-helpers.ts:27; `cli/tui/interactive-config.ts:206` | Read-only list of agent YAML configs | none reachable (`views/settings/extensions-view.tsx:1680` exists, but `customize-view.tsx:39-45` has no Agents tab and `showTabs` is false, extensions-view.tsx:386) | none | **terminal-only candidate** |
| E15 | Hooks tab | config-view-helpers.ts:28 | List | `customize-view.tsx:231` | `app/ui/customize/tabs/HooksTab.kt:25` | covered |

### 1.6 History dialog (`cli/tui/views/history-view.tsx`)

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| F1 | Resume (Enter) | history-view.tsx:286 | Resumes the session | as A13 | as A13 | covered |
| F2 | Delete (Left, then y) | :308, :250 | Deletes a session | `sessions-view.tsx:813` | `app/ui/sessions/DeleteSessionDialog.kt:62` | covered |
| F3 | Export (Right: HTML or JSON) | :315; `cli/tui/views/history-export-picker.ts:5-11` | Writes the session into cwd | none | HTML only, `app/ui/chat/ChatViewModel.kt:975` (an addition already built) | **terminal-only candidate** |

### 1.7 Command-line flags (`cli/commands/program.ts`)

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| G1 | `-p/--plan`, `-a/--act` | :26, :87 | Start mode | `chat-input-bar.tsx:1512` | ComposerSettingsRow.kt:311 | covered |
| G2 | `--auto-approve <bool>` | :28 | Tool auto-approval for the run | none | none | **terminal-only candidate** (A1) |
| G3 | `-y/--yolo` | :92 | Headless one-shot, all tools approved | n/a | n/a | n/a |
| G4 | `-z/--zen` | :54; `cli/runtime/run-zen.ts:13` | Fire-and-forget task into the Hub, auto-approve | n/a | n/a | n/a |
| G5 | `--thinking <level>` | :35 | Reasoning effort | `chat-input-bar.tsx:187` | ReasoningMenu.kt:37 | covered |
| G6 | `--compaction <mode>` | :38 | As E5 | none | none | **terminal-only candidate** (E5) |
| G7 | `-c/--cwd` | :33 | Workspace | `welcome-workspace-controls.tsx` | WorkspacePickerSheet.kt:156 | covered |
| G8 | `--id <session>` | :43 | Resumes a session | sidebar | SessionsDrawer.kt:110 | covered |
| G9 | `-P`, `-k`, `-m` | :44-49 | Provider, key, model | model picker, providers | ModelPickerSheet.kt:147 | covered |
| G10 | `-s/--system` | :51 | Overrides the system prompt | none for chats (`constants.ts:34` undefined) | none | **terminal-only candidate** |
| G11 | `--worktree` | :77; `cli/utils/worktree.ts:71` | Runs the task in a fresh git worktree under `~/.cline/worktrees` | "Worktree" toggle: `welcome-workspace-controls.tsx:931`, :1184; `page.tsx:1930`; `sidecar/commands.ts:3517` | none | **app gap** |
| G12 | `--retries`, `-t/--timeout`, `--json` | :56, :60, :27 | Headless tuning | n/a | n/a | n/a |
| G13 | `--acp`, `--config`, `--data-dir`, `--hooks-dir`, `--team-name`, `-v` | :64-82, :99 | Editor protocol, paths, verbose output | n/a | n/a | n/a |
| G14 | `--update`, `cline update` | :80; `cli/main.ts:652` | Self-update | `settings-view.tsx:1057` | none | n/a (port, see E7) |
| G15 | `--kanban`, `cline kanban` | :81; main.ts:675; `cli/commands/kanban.ts` | Installs and runs the separate kanban web app | none | none | n/a |

### 1.8 Subcommands (`cli/main.ts`)

| # | Terminal function | Where | What it does | Desktop equivalent | App equivalent | Verdict |
| --- | --- | --- | --- | --- | --- | --- |
| H1 | `cline auth` | main.ts:189 | Configures a provider (key or OAuth) | `provider-list-view.tsx` | ConnectModelScreen.kt:121; ClineCommands.kt:89 | covered |
| H2 | `cline config` | :271 | Prints configuration and lists | n/a | used internally, ClineCommands.kt:141-146 | n/a |
| H3 | `cline plugin install\|uninstall` | :284, :290, :332 | Installs/removes plugins | marketplace, customize | ClineCommands.kt:181-185 | covered |
| H4 | `cline skill` | :356 | Skills CLI passthrough | marketplace | ClineCommands.kt:189-197 | covered |
| H5 | `cline mcp [install\|uninstall]` | :464, :477, :511 | Adds/removes MCP servers | `mcp-view.tsx` | McpServerEditor.kt:159; ClineCommands.kt:155-177 | covered |
| H6 | `cline connect [channel]` | :378 | Runs Cline as a bot on a messaging channel | `views/settings/channels-view.tsx:410`, :499, hidden (`views/settings/sections.ts:42`) | none | **terminal-only candidate** |
| H7 | `cline doctor [fix\|log]` | :537; `cli/commands/doctor.ts:845`, :860 | Diagnoses, kills stale processes, shows logs | `views/settings/export-diagnostics-dialog.tsx` | `app/ui/kernel/KernelDiagnostics.kt`; ClineCommands.kt:72-75 | covered |
| H8 | `cline hook` | :564 | Hook payload handler (internal) | n/a | n/a | n/a |
| H9 | `cline schedule` (create, list, get, update, delete, pause, resume, trigger, history, active, upcoming, stats) | :587; `cli/commands/schedule/handlers.ts:31-386` | Manages schedules | `views/settings/routine-view.tsx` | `app/ui/schedules/SchedulesScreen.kt:124` | covered |
| H10 | `cline schedule export\|import` | `cli/commands/schedule/import-export.ts:56`, :127 | Schedule to/from a file | none | none | **terminal-only candidate** |
| H11 | `cline hub ensure\|start\|status\|stop\|upgrade` | :602; `cli/commands/hub.ts:99-201` | Manages the daemon | boot gate, HubUpdateRequiredDialog | KernelStatusSheet.kt:144; ClineCommands.kt:38-47 | covered |
| H12 | `cline hub drain` | hub.ts:166 | Stops taking new runs | none | none | n/a |
| H13 | `cline dashboard` | :613 | Browser dashboard | n/a | the app's settings helper, ClineCommands.kt:119 | n/a |
| H14 | `cline version`, `-V` | :666 | Prints the version | About | `app/ui/about/AboutScreen.kt` | covered |
| H15 | `cline history [delete\|update\|export]` | `cli/commands/history-command.ts:40-107` | Lists, deletes, retitles, exports HTML | sidebar, sessions | SessionsScreen.kt:134; DeleteSessionDialog.kt:62; `sessions/SessionRow.kt:320` (rename); ChatViewModel.kt:975 | covered |

**Counts (109 rows):** covered 56 · terminal-only candidate 18 rows (14 distinct
functions) · app gap 4 · presentation 12 · n/a 19.

## 2. Terminal-only candidates

Distinct functions, most useful first. "Hub" = a command in
`core/hub/server/hub-server-transport.ts:794-945`. The Hub has no command to read or
write global settings: `settings.get` and `settings.patch` reply `not_implemented`
(hub-server-transport.ts:922-932), and no Hub code reads `global-settings.json` for
session defaults (no hit for `compactionStrategy`/`readGlobalSettings` under `core/hub`).

1. **`/compact` (A8, Opt+X).** Summarises the whole transcript now. Backing: none in the
   Hub. The terminal runs the summary in its own process with the provider's key
   (`cli/runtime/interactive/compaction.ts:52`, core `createContextCompactionPrepareTurn`,
   mode `manual`) and stores it with `session.compaction.update`
   (`core/hub/server/handlers/session-handlers.ts:1118`), which only the session's owner
   client may call (session-handlers.ts:241-263). Cost: high. The app has no LLM client and
   no provider secrets, so it needs a kernel addition (a Hub `session.compact`, or a Termux
   helper that returns the compaction state for the app to store), then a menu action and
   the "Compacting…" row.
2. **Compaction strategy LLM / Truncation / Off (E5, G6).** Backing: `sessionConfig.compaction`
   at `session.create` (the Hub passes `sessionConfig` through, session-handlers.ts:288)
   plus the global `compactionStrategy`/`compactionEnabled` (`core/services/global-settings.ts:295`)
   that only the terminal reads. Cost: low after app gap 1 below (one field at create,
   a file write through Termux like A1, and the A1 restart-with-messages path for a change
   mid-session).
3. **Auto-approve all (B8, E6, A23 `/yolo`, G2).** Already decision A1 (open): `toolPolicies`
   at `session.create` plus the global `toolAutoApprove` (global-settings.ts:332), written
   through Termux; restart when the run ends.
4. **`switch_to_act_mode` (D4).** Backing: a client tool contribution (`kind: "tool"`,
   `core/hub/server/hub-client-contributions.ts:158`) served through `capability.requested`
   / `capability.respond`; the tool ends the run (`completesRun`), then the client recreates
   the session in Act mode and sends the continuation prompt (mode.ts:28). The Plan-mode
   system prompt differs when the tool exists (`sdk/packages/shared` `PLAN_MODE_INSTRUCTIONS`).
   Cost: medium.
5. **Ctrl+S, steer the typed text directly (B5).** Backing: `session.send_input
   {delivery: "steer"}` (`core/hub/server/handlers/run-handlers.ts:204`). The app already
   sends `queue` (ComposerController.kt:556). Cost: low (a Send long-press or menu item);
   the desktop reaches the same result in two steps.
6. **Prompt history recall (B17).** Backing: the file `~/.cline/data/cache/user_input_history.jsonl`
   (20 entries, input-history.ts:5-9); no Hub. Cost: low (Termux `cat`, recall on a
   hardware Up or a "recent prompts" list; appending keeps the terminal in step).
7. **`/tools off` (A22).** Backing: `sessionConfig.enableTools` at `session.create` (the app
   hard-codes `true`, HubSessionApi.kt:68). Cost: low; a change mid-session needs the
   restart-with-messages path.
8. **`/cwd` (A24).** Backing: `session.create` with `initialMessages` and the new
   `cwd`/`workspaceRoot` (the terminal restarts the same session id, session-runtime.ts:440-470).
   Cost: low to medium; the desktop only picks a workspace for new sessions.
9. **Verbose (E8).** Backing: none needed (`run.completed` usage and the recoverable `error`
   events the app already receives). Cost: low; an app preference.
10. **`-s/--system` (G10).** Backing: `sessionConfig.systemPrompt` at `session.create`
    (session-handlers.ts:308). Cost: low (a field in new-session options).
11. **`cline connect` channels (H6).** Backing: Hub `connector.channels/configure/start/stop/supervised`
    (hub-server-transport.ts:915-921, `core/hub/server/handlers/connector-handlers.ts:256`) and
    the settings helper's `list/start/stop_connector_channel`
    (`apps/cline-hub/src/server/desktop-commands.ts`). The desktop built the view but hides it.
    Cost: medium.
12. **History export as JSON (F3).** The app already exports HTML through `cline history export`
    (rule 3 applied). JSON is written by the TUI's own exporter, not that subcommand. Cost: low.
13. **Schedule export/import (H10).** Backing: `cline schedule export <id> --to <path>` /
    `import <path>` through Termux (import-export.ts:56, :127). Cost: low plus a file picker.
14. **Agents tab (E14).** Backing: none (YAML files in the agent config paths,
    interactive-config.ts:206). Cost: low (a read-only list in Customize).

## 3. App gaps (rule-1 defects)

1. **Auto-compaction is off in every app session (E5a).** The desktop sends
   `compaction: { enabled: true }` (`desk/sidecar/chat-session.ts:633-635`, its comment:
   "Core auto-compaction is opt-in; without this the 90% trigger never runs and long desktop
   sessions overflow the context window"); the terminal sends it too (main.ts:1075, default
   `agentic`). The app's `sessionConfig` has no `compaction` (HubSessionApi.kt:61-73), and core
   skips compaction unless `enabled === true` (`core/extensions/context/compaction.ts:291`).
   Fix: one field, the desktop's value. New finding.
2. **Mistake-limit prompt (D3).** The desktop registers `onConsecutiveMistakeLimitReached`
   (`desk/sidecar/chat-session.ts:444-583`), which reaches the Hub as the `mistakeLimit` client
   contribution (`core/hub/runtime-host/hub-runtime-host.ts:356`, hub-client-contributions.ts:208,
   :655), and asks "Try a different approach / Stop this run" through ask-question. The app
   registers only askQuestion (HubSessionApi.kt:89-99), so a run that hits the limit simply
   stops without saying why. The desktop also holds the run with awaited hooks while the user
   answers (chat-session.ts:580-583); the app runs no client hooks (A5). New finding.
3. **"Worktree" for a new task (G11).** The desktop's composer offers a Worktree toggle
   (`welcome-workspace-controls.tsx:931`, :1184; `page.tsx:1930`) backed by `create_git_worktree`
   (`sidecar/commands.ts:3517`), plus a delete warning for worktree sessions
   (`sessions-view.tsx:819`). The app has none (docs/03 calls worktree tier 4; not in docs/09 §5).
   On the phone it would be `git worktree add` through Termux, then `session.create` in that path.
4. **Plugin slash commands (A18).** Listed and run in the desktop sidecar
   (`chat-input-bar.tsx:149`, :1026; `sidecar/commands.ts:3578`); no Hub command. Already in
   docs/09 §5.

Not defects, for the record: "Keep CLI up to date" (`settings-view.tsx:1057`) has no effect on
the port (E7). Desktop hardware shortcuts the app lacks: Ctrl+, opens Settings (`page.tsx:774`)
and Ctrl+B toggles the sidebar (`components/ui/sidebar.tsx:34`, :176); also, the comment in
`app/ui/navigation/Shortcuts.kt:14` says the desktop has no Ctrl+N binding, but `page.tsx:768`
binds Cmd/Ctrl+N.

## 4. Method

Read in full: `cli/tui/commands/slash-command-registry.ts`, `cli/tui/hooks/local-command-actions.ts`,
`use-local-command-actions.tsx`, `use-root-keyboard.ts`, `root-keyboard-routing.ts`,
`transcript-keybinds.ts`, `use-prompt-input-controller.ts`, `use-config-panel.tsx`,
`cli/tui/components/input-bar.tsx`, `dialogs/command-palette-items.ts`, `cli/tui/root.tsx`
(100-1140), `cli/utils/chat-commands.ts` (95-546), `cli/runtime/interactive/chat-command-runner.ts`,
`approvals.ts`, `mistakes.ts`, `cli/utils/compaction-mode.ts`, `cli/commands/program.ts`; read in
part: `config-view.tsx` (400-760), `help-dialog.tsx`, `history-view.tsx`, `inline-tool-response.tsx`,
`run-interactive.ts` (225-870), `session-runtime.ts` (440-860), `mode.ts`, `main.ts` (185-700,
1060-1080), `update.ts`, `team-command.ts`, `interactive-welcome.ts`, `interactive-config.ts`.
Grepped `main.ts`, `commands/*` and `commands/schedule/*` for `.command(`, the TUI for `key.name`,
`key.ctrl`, `useKeyboard`, `useDialogKeyboard`. Hub: the dispatch switch in
`hub-server-transport.ts`, `session-handlers.ts` (create, compaction get/update, owner check),
`hub-client-contributions.ts` kinds, `runtime-host.ts`, `extensions/context/compaction.ts`,
`services/global-settings.ts`. Desktop: grepped `webview` and `sidecar` for `metaKey|ctrlKey`,
`compact`, `steer`, `mistake`, `switch_to_act_mode`, `worktree`, `export`, `systemPrompt`,
`enableTools`, `autoUpdate`; read `chat-input-bar.tsx` (111-160, 1250-1360), `page.tsx` (758-780),
`customize-view.tsx` (190-245), `sections.ts`, `extensions-view.tsx` tabs,
`sidecar/chat-session.ts` (420-640), `sidecar/commands.ts` (3200-3225, 3560-3600). App:
`CLAUDE.md`, `docs/decided.md`, `docs/todo.md` (A1), `docs/04-screens.md`, `docs/03-feature-matrix.md`
(parts), `docs/09-implementation-report.md` §5, `SlashCommandSource.kt`, `Shortcuts.kt`,
`ComposerField.kt`, `ComposerController.kt`, `HubSessionApi.kt` (55-100), `ClineCommands.kt`; grepped
`app/src/main/kotlin` for `compaction`, `mistake`, `worktree`, `autoUpdate`, `enableTools`,
`clientContributions`, `steer`, `pasted`, `history`, `connector`. Port: `release/install-cline-termux.sh:501`.
