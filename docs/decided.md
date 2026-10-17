# Decided: the owner's decision log

Every reply the owner gives to an item in [todo.md](todo.md), with its date, the option
chosen and what it means for the build. An item arrives here only after the owner replied
to it in the TODO (or in chat about that TODO item), and it never goes back. The **Build**
column tracks whether the decision has been implemented.

Build key: **pending** = decided, not yet built · **in progress** · **done** = built and
verified · **n/a** = nothing to build · **later** = a follow-up action is noted below.

| Id | Date | Item | Decision | Build |
| --- | --- | --- | --- | --- |
| P1 | 2026-10-07 | How faithful to the desktop the app should be (the parity rule) | Option 1, the rule as written: (1) every desktop function that makes sense on a phone, behaving exactly like the desktop wherever the kernel or other clients can see it; (2) Material 3 phone presentation over desktop pixels; (3) improvements only where the desktop offers no control and the terminal does (then follow the terminal), or where the desktop's behaviour would be unsafe on a phone; each such case is a numbered decision in docs/07 §15 (D19). | n/a |
| A8 | 2026-10-07 | `/clear` and the terminal's other slash commands | Closed under P1 rule 1: the desktop has no `/clear`; the app's "+" is the equivalent. Typed aliases of existing screens remain an optional rule-2 nicety for a later batch. | n/a |
| A3 | 2026-10-07 | Run hangs after the terminal drove the session (Hub hook proxies bound to a departed client) | Confirmed upstream; leave the kernel as is (no port revision for it). Report upstream after a clean reproduction on the desktop; the community search found related reports but not this case. | later |
| A6 | 2026-10-07 | Composer chips icon-only on a phone | Keep (option 1). | n/a |
| A7 | 2026-10-07 | Dictation fails: `ERROR_LANGUAGE_UNAVAILABLE` (offline-only, no en-US offline pack) | Keep offline recognition; install the Google offline English pack on the phone (option 3). | n/a |
| B1 | 2026-10-07 | Hub left running in the background | Fine as upstream does it (option 1). Keep-connected stays off by default (D11); no "Stop kernel" prompt on exit. | n/a |
| B2 | 2026-10-07 | App language | Leave as is for now (option 3): English copy, system formatters follow the device. | n/a |
| B3 | 2026-10-07 | Cron labels | Keep the app's accurate label (option 2). Also the owner's rule of thumb for presentation: exact functions, a narrow space for details that fit M3 and the phone (written out as P1 in the TODO). | n/a |
| B4 | 2026-10-07 | Keyboard after a sheet closes | Keep (option 1). | n/a |
| B5 | 2026-10-07 | Reliability step after a dismissed notification prompt | Behave like Settings: ask again on the next tap, open system settings only after a permanent denial (option 1). This is Android's own guidance. | done (batch 1, V-P3-B5) |
| B6 | 2026-10-07 | Spec-versus-desktop conflicts | Follow the desktop everywhere (option 1), including CT B9/B12 ("Completed" for a failed command). | n/a |
| A5 | 2026-10-07 | The app does not run the user's hooks | Option 1: build it after A1 and A2 are done, and discuss again before building (the decision is the order, not an automatic go). | done as F1 (batch 1): the Hub already runs the hook files for app sessions, so no app hook runner or contribution was built; owner to confirm (docs/10 §7) |
| B7 | 2026-10-07 | Kernel fix K1 for one-step checkpoint restore | Option 1: K1 goes into the next port revision whenever one is built; until then the app keeps the two-step restore. | later |
| A2 | 2026-10-07 | Thinking level disabled for most providers | Option 2: show the thinking control exactly where the desktop does. Export provider capabilities into the bundled list (a reasoning-capable provider's chat models are reasoning-capable); unknown stays disabled; the live catalog from the settings helper overrides, custom models from the kernel's models file included. | done (batch 1, V-P2-A2); fallback enables every model of a reasoning provider, the live catalog decides per model |
| A4 | 2026-10-07 | The silent hang: a notice with the kernel's timing | Option 1: notice on the Hub's first `run.heartbeat` without any session event, with Restart kernel and Keep waiting; the Stop failure moves from the app's 5 s to the protocol's 30 s command timeout and offers Restart kernel too. | done (batch 1, V-P3-A4, V-P3-STOP) |
| A9 | 2026-10-07 | Terminal functions the desktop lacks (rule 3 scope) | Option 1: rule 3 stays a permission. One Opus research agent inventories every terminal command, shortcut and setting against the desktop and the app; each terminal-only function returns to the TODO as its own item (`/compact` first). | later |
| B8.1 | 2026-10-07 | Browser sign-in with a real Cline account | The owner registers a new Cline account and the sign-in flow (SP S7) and Account screen are tested on the Tab S7+ during the tablet run. | later |
| B8.2 | 2026-10-07 | Tab S7+ run of the wide layouts | After everything on the S25U is finished. | later |
| B8.3 | 2026-10-07 | Streaming jank re-measured on a release build | Option 2: measured in the next real-kernel verification round, once A1, A2 and A4 are built; whether the debug build gets an id suffix is settled then. | done (batch 1, V-B8.3): release build 0.77 % janky, p90 9 ms; debug id suffix `.debug` |
| A1 | 2026-10-07 | Auto-approve control: mechanism and placement | The terminal's mechanism and the kernel's global `toolAutoApprove` (a missing key means on, as in the terminal; Ask sends the terminal's seven read-only auto-approved tool policies). Placement A′: a shield icon button before the title at the left of the top app bar, next to the status dot, on Home and in every chat; states Ask / Auto / pending until this run ends; a two-tap menu; the approval sheet gets "Approve ▾ / Approve and auto-approve from now on"; Settings gets "Auto-approve all" in the Kernel group; a small motion when the pending state applies at the end of a run or on Stop. Full design: docs/research/auto-approve-control.md. | done (batch 1, V-P1-A1: step 7 failed in round 1, fixed in 941e886, passed the device recheck, docs/10 §3.2); the A′ vs A pick was settled by A15 (A′; batch 2, V-ICONS) |
| A10 | 2026-10-07 | App gaps found by the inventory (rule 1) | Fix all four in the next build: G1 auto-compaction on (first), G2 mistake-limit prompt (with A5), G3 worktree toggle, G4 hardware-keyboard shortcuts. | done (batch 1): G1 V-P4-G1, G2 V-P5-G2, G3 V-P8-G3, G4 V-P8-G4 (the expanded-window list-pane toggle untested on the S25U, B8.2) |
| A11 (part) | 2026-10-07 | Terminal-only candidates, rows 1 to 5, 13, 14 | As recommended: 1 `/compact` later (needs a kernel addition; superseded 2026-10-07 by TODO A14, since the desktop has `/compact` after all), 2 compaction strategy setting with G1, 3 is A1, 4 `switch_to_act_mode` later with A5 and G2, 5 steer (long-press Send while a run is active), 13 and 14 no. Rows 6 to 12 continue in the TODO. | done (2, 4, 5: V-P4-A11.2, V-P5-ACT, V-P4-STEER) / later (1, see A14) |
| A11.6 | 2026-10-07 | Prompt history (the terminal's up-arrow) | Approved in chat: a quick horizontal fling on the empty field steps through the last 20 prompts (a slow drag still moves the cursor); the app stays in browsing while the recalled text is unedited, with a chip "Recent prompt n of 20" and an × above the field; typing or clearing ends it. A "Recent prompts" row in the attach sheet opens the same list (the accessible and discoverable path). History read from and appended to the terminal's `~/.cline/data/cache/user_input_history.jsonl`, so both clients share it. | done (batch 1, V-P6-HIST); direction reversed by A18 in batch 2 (V-HIST) |
| B9 | 2026-10-07 | Full-screen editor for long messages | Approved in chat (rule 2, presentation): an expand icon at the end of the field once the text passes about three lines opens a Material full-screen dialog with close, the attachments and Send in its top bar; closing returns the text to the field. Built in the same composer package as steer and prompt history. | done (batch 1, V-P6-B9) |
| R1 | 2026-10-07 | The release path for the app | No GitHub remote for the app yet. A port release is pushed to the fork only if the app's manual test needs a kernel change, and this workflow needs none (A1 writes the settings file through Termux; A5, G1 to G4, steer, history and the editor are app-side; K1 and `/compact` stay deferred). The app reaches the S25U by adb. For the jank measurement the release build is signed with the debug key (no keystore until something is published); the debug build gets the `.debug` id suffix so the two coexist, unless the owner objects. | done (`.debug` suffix; release signed with the debug key, TEMPORARY) / later (remote, keystore, release) |
| A5 (timing) | 2026-10-07 | Hooks in this workflow | Yes, after G2, which lays the capability-request plumbing; A11 row 4 (`switch_to_act_mode`) joins for the same reason. | done (batch 1: G2 and `switch_to_act_mode` built; hooks see A5) |
| A11.7 | 2026-10-07 | `/tools off`; all-on / all-off | No for `/tools off`; the all-tools switch already exists in Customize → Tools. | n/a |
| A11.12 | 2026-10-07 | History export | Covered: `cline history export` writes HTML and the app already runs it; the JSON flag is the list format. | n/a |
| P2 | 2026-10-07 | Order of the next work | 1. Port `cli-v3.0.69` first, as a routine cycle (K1 left out unless the merge is trivially clean). 2. The app workflow and the owner's test. 2.5. Reconsider the Bun runtime (port plan item 3), the landscape and wide-layout test (the Tab S7+ run, B8.2, and the phone's landscape view), then polish and publish the app (R1 comes back then: remote, keystore, release). The upstream contributions (U1 to U3) happen whenever time allows, not as a numbered step. Step 2 built 2026-10-08 (batch 1, docs/10, branch batch/decided-1, release installed on the S25U; the owner's trial and the A15 to A18 replies close it). Step 1 done 2026-10-07: `v3.0.69-termux.1` promoted (candidate, promote and the Tab S7+ run all passed on attempt 1; S25U manual test passed); the app now runs against it. | step 2 in progress: batch 1 built and verified (docs/10); the owner's test next |
| U1 | 2026-10-07 | Upstream bug report: K1 (`session.restore` loses the stored provider key) | After step 2, with a reproduction on the stock CLI. | later |
| U2 | 2026-10-07 | Upstream bug report: A3 (Hub keeps a departed client's hook proxies; a later run waits forever) | After a clean reproduction on the desktop, with the S25U `hub-daemon.log` trace. | later |
| U3 | 2026-10-07 | Upstream feature request: a Hub command to compact a session's context on demand (the terminal's `/compact` for Hub clients) | After step 2; if accepted it replaces the port addition `/compact` would need. | later |
| A11.8 | 2026-10-07 | `/cwd`: change a session's workspace | Built in batch 1 as "Move to workspace…" (delete-then-create under the same id, V-P4-A11.8). **Cancelled 2026-10-08** on the corrected facts: no client has a move (the chat bots' `/cwd` only changes where their next session starts, which the Home folder chip already does), so it is an addition outside rule 3 and the one destructive path no other client has. The menu entry and its code (recovery copy, subagent refusal, tests) are removed in the polish pass. | cancelled; removed in batch 2 (V-MOVE) |
| A11.9 | 2026-10-07 | Verbose per-turn stats | Build, small: the terminal's setting choice (Brief / Verbose) applied to the desktop's reveal row, combined in the app: Verbose adds tokens, cost and duration to the revealed row and shows recoverable mid-run errors inline. The owner's restatement confirmed. | in progress: Verbose stats done (V-P7-A11.9); the inline recovery notices wait on an upstream Hub change (core 0.0.91 drops recoverable agent errors, D20) |
| A11.10 | 2026-10-07 | Custom system prompt (`-s/--system`) | No. Owner's principle, noted for future rule-3 candidates: whoever really needs a developer flag goes to the terminal and works with the TUI. | n/a |
| A11.11 | 2026-10-07 | `cline connect` messaging channels | No: a phone user opens this app directly, so the chat-app bots are not needed. | n/a |
| G5 | 2026-10-07 | App bug: kernel state stuck in Draining after a Hub replacement ("Cline Hub is updating…" forever) | A defect, fixed under the A10 decision: on any connection-generation change the state is re-derived from the new Hub's status, and a drain-cleared signal is never dropped. Trial log row 7. | done (batch 1, V-G5) |
| A13 | 2026-10-07 | Fork in three places | Keep all three (two functions: whole-session fork in the menu and `/fork`; "fork before this turn" on a message). Label the message action "Fork before this turn" and give it its own icon, distinct from the whole-session fork glyph (`fork_right` today): the owner's sketch is the branch arm without the shared stem, so the tiny icon reads as "from here"; the implementer picks between the existing `call_split` symbol and a custom glyph drawn from that sketch, shown on the S25U before the branch is final. | superseded by A16: the message-row fork and its glyph were removed in batch 2 (V-FORK) |
| A14 | 2026-10-07 | `/compact` is a desktop function; the kernel has no command for it | Upstream first, later: no issue or pull request during the app workflow; after the app is polished and released (step 2.5) consider it again (issue first, then a pure-addition pull request with tests, then a second one that moves the terminal and the sidecar onto the command). Until a kernel has it the app hides `/compact`. No downstream protocol extension in the port. Supersedes A11 row 1 and turns U3 from a request into a possible pull request. | later |
| D20 | 2026-10-07 | Port principle: kernel changes | The port changes the kernel only for platform fixes (packaging, Android paths, bundling, native builds). Protocol or feature changes go upstream first; a downstream backport is carried only while an accepted change waits for a release. K1 (B7) follows the same posture when it is taken up. | n/a |
| D1a | 2026-10-07 | Test credentials and real-kernel test rules for the app workflow | Real-model checks prefer DeepSeek (fast and cheap; the owner's quota there is limited, so use it sparingly) and use the owner's Astra (`openai-codex`, slow at the moment) only for Astra-specific checks such as the thinking chip; the configured Minimax is dead and is never used. Test sessions may live in the owner's real Hub, titled with a "[test]" prefix and deleted at the end (copying credentials into an isolated Hub is also allowed, done without reading them). Real-kernel prompts are benign and read-only, run in a scratch workspace under Termux's home. Termux's shared-storage permission is revoked for the duration of the device rounds (Termux may be broken freely; the rest of the phone stays untouched; adb is unrestricted) and restored at close-out. | n/a |
| U4 | 2026-10-08 | Upstream report: a resume via `session.create {resumeSessionId}` stamps `metadata.autoApproveTools` only in memory, and the run-end status write sends the persisted creation value out again (stale policy in records; found by V-P1-A1). | later |
| U5 | 2026-10-08 | Upstream report: a cancelling `PreToolUse` hook file ends the whole run instead of blocking the one call (found by V-P5-A5; trial row 9), and its `errorMessage` reaches no client. Evidence gathered 2026-10-08: the CLI reference and the official `PreToolUse_ClineignoreGuard.sh` example describe and expect per-call blocking with a message for the model; cline/cline#9937 (open, confirmed, CLINE-1589) is the VS Code-side symptom. A candidate for a report first and, if welcome, a pull request that turns `cancel` into a skipped call with the message as the tool result (kernel change, upstream first, D20). | later |
| U6 | 2026-10-08 | Upstream request: recoverable agent errors are dropped by the session event projector (`session-event-projector.ts`, `projectAgentErrorEvent`), so no client can show recovery notices (blocks the second half of A11.9). | later |
| U7 | 2026-10-08 | Upstream bug report: after the first auto-compaction the kernel re-compacts every few large tool results ("124.3k → 43.1k" three times in 48 reads) because the per-turn estimate counts the retained results untruncated while the request keeps them at the 8k-character cap (provider saw 12–23k tokens, kernel counted 124k; diagnostics table and probe recipe in the "Compaction check (2026-10-08)" notes). | later, with the diagnostics table | later |
| U8 | 2026-10-08 | Upstream bug report: `cline -z` (zen) sessions are created by `HubSessionClient.startRuntimeSession` without a `compaction` field, so they never auto-compact (two probes reached 137k real tokens untouched). | later | later |
| U9 | 2026-10-09 | Upstream bug report: the compaction estimator counts attached images as text (JSON characters ÷ 3, `CHARS_PER_TOKEN`), so one 334k-character screenshot counts as 111k tokens where the provider bills about one or two thousand; with the openai-codex cap of 258,400 input tokens a session that OpenAI counts at 104k trips the 232,560 trigger on every turn (MyTest, replay in the "Compaction check 2" notes). | later, with the replay numbers | later |
| U10 | 2026-10-09 | Upstream bug report: when the agentic summariser answers with no text the kernel emits "compaction-skipped" and stops, without the basic fallback it uses when the summariser throws, and retries the whole thing on the next turn (no backoff, up to three summariser calls per turn through the empty-response retry); the Hub daemon has no runtime logger, so the warn line naming the cause is discarded. Seen on MyTest with the .69 daemon, where Astra answered the reasoning-off summary request with no text (the AI SDK defect upstream fixed in .70); on .70 the same session compacted (347,929 → 134,254). | later | later |
| A15 | 2026-10-08 | Shield placement and icon | A′ confirmed. Icons swapped: the ticked shield (`gpp_good`) means Ask each time; Auto is the bolt ("Approve for bolt"); no tinted disc, no ring; pending = the target glyph at low emphasis with a small dot badge. | done (batch 2, V-ICONS) |
| A16 | 2026-10-08 | The message-row fork | Final, after three passes the same day: whole-session fork from the session menu only; the row icon and the popup's `/fork` are removed (one place per function, A19). Corrects the wrong premise in A13. | done (batch 2, V-FORK) |
| A18 | 2026-10-08 | Small defects after batch 1 | Option 1: one Opus polish pass, covering trial rows 8, 11, 12, 13, 14, 16; the prompt-history direction (left to right = previous); the history chip compact, without outline and without its leading icon (it duplicated Restore checkpoint); A15, A16, A19 and the A11.8 removal; a real-kernel `/team` check. The full-screen editor keeps its three-line threshold ("current design is fine"). The branch is merged into main with it. | done (batch 2, docs/11): rows 8 V-R8, 11 V-R11, 12 V-R12, 13 V-R13, 14 V-R14, 16 V-R16; history direction and chip V-HIST; the real-kernel `/team` check failed in round 1 (V-TEAM: no refusal with Teams off), fixed in da81883 and rechecked on the device (refused, nothing sent); a real team start is untestable without the owner enabling Teams |
| A19 | 2026-10-08 | The slash popup's contents; `/clear`, `/undo` | Final: the popup holds `/team` now, `/compact` once a kernel has it (A14), and the owner's skills, workflows and plugin commands. No `/fork` (three-dot only), no `/clear` (the terminal's starts a new session, which is "+", A8), no `/undo` (restore has its place). Rule: one place per command or function; the three existing duplicates are TODO A20. The owner's mapping of the terminal's other commands confirmed: settings, model, account, theme, mcp, plugins, skills are screens the app has; history is the drawer; help and quit none. The popup hides `/compact` until a kernel has it. | done (batch 2, V-FORK: the popup lists `/team` and the owner's workflows, no `/fork`; `/team` refusal fixed in da81883, V-TEAM recheck); `/compact` hidden (A14) |
| A20 | 2026-10-08 | One place per function: the three duplicates | All three stay as they are, as stated exceptions: 1 restore on the message row and in the three-dot picker ("keep the current Restore checkpoint as it is"); 2 session commands in the drawer's row menu (the desktop's) and in the chat's three-dot (the app's, for the open session) ("2. a"); 3 prompt history by the fling and by the attach sheet's "Recent prompts" row ("3. a"). The rule in practice: typed commands and menu entries are never duplicated; a function may sit on a row and in a menu. | n/a (nothing changes) |
| A17 (G2) | 2026-10-08 | The mistake-limit question bills while open | Option 2: keep the current sheet (the desktop's text, choices and 5-minute wait), accepting that the app cannot freeze the run while the sheet is open (the desktop does, through hooks the app cannot use because of A3), so an unanswered sheet keeps calling the model for up to 5 minutes. Polish pass: after "Stop this run" or the timeout, the conversation says why it stopped (trial row 10). **Noted for later:** option 4, "stop first, ask after" (answer stop at once, then a card with "Try a different approach" that sends the guidance as the next message; no timer, nothing billed while waiting) is the better approach when the app is next reworked here. | done (batch 2, V-G2: the stop notice, live, after reopen and cold start) / later (option 4) |
| A21 | 2026-10-08 | Trial row 9: a hook-blocked call shown as Running forever | Option 1, app side only: in a finished turn a tool call with no result is drawn as "Interrupted" instead of "Running" (a presentation fix, rule 2; the desktop draws it as in progress). No kernel or Hub change; the cause (a cancelling hook ends the run and its reason reaches no client) stays an upstream report, U5. Owner's standing preference restated: do not touch the kernel and avoid upstream pull requests, to keep maintenance easy and tidy. | done (batch 2, V-A21) |
| A22 | 2026-10-08 | Default folder for a session started without choosing one (trial row 17) | The Termux home (`/data/data/com.termux/files/home`), what the terminal client on the same phone uses when run from a fresh shell; every user of this app has it. The app no longer sends the kernel's chat-workspace path (which it never created, so `run_commands` failed with a misleading ENOENT) and the Home chip's empty state reads "Home" instead of "Chat"; tapping it still opens the folder picker; the drawer groups those sessions under Home. Deliberate deviation from the desktop, which has no project by default and labels that "Chat" (sessions the kernel imports into its chat workspace keep that label). Nothing is created or written in the home. Checkpoints are unaffected: the kernel takes them only inside a git repository, so neither folder had them. Owner: "Not every user have a workspace folder"; "just the home folder of termux by default". | done (batch 2, V-HOME) |
| A23 | 2026-10-08 | Tool rows folded by default (owner's finding 2, 2026-10-08) | Option 1: no tool row opens by itself. Every tool row (commands, reads, edits, media, the final submit text) starts folded with its spinner or status in the header and opens only when tapped; once opened it stays open. The desktop's auto-open rule (`shouldAutoOpen`: running output, file diffs, media, submit text) is dropped, a rule-2 presentation change for the phone; the function is unchanged. Thinking rows and the "Worked for…" block were folded already. Owner: "option one for A23". | done (batch 2, V-FOLD) |
| A24 | 2026-10-08 | Folded rows too sparse for a phone (owner's finding 3, 2026-10-08) | Compact rows: 40 dp tall (the Material 3 dense list height; the touch target stays 40 dp, allowed for dense lists), 18 dp icons, `bodySmall` labels, the block gap 4 dp, the body's rail inset reduced to match; thinking rows and the "Worked for…" block get the same. Built as one set of tokens (height, icon, label style, gaps, inset) in one place, so the height can be changed to 44 dp later by editing one value. The owner reviews it on the device; 44 dp is the fallback. Owner: "40 dp for me to review, keep the change clean, once I found it is not suitable, we can adjust easily later." | done (batch 2, V-DENSITY); 40 dp for the owner's review, 44 dp is one value (`DisclosureRowTokens.Height`) |
| A25 | 2026-10-08 | "Output before input" in a command row (owner's finding 1, 2026-10-08) | No change. The screenshot came from Cline Debug: the block after the output is the developer-only "INPUT" block, the raw tool arguments as JSON, shown only in the debug build (row body and details screen, like the desktop's debug block). The release app shows the command and then its output everywhere, so there is nothing to fix for users. Owner: "Since it is debug only, it's fine, we can don't handle that." | n/a |
| D1 | 2026-10-07 | Test credentials | Tests may use the owner's real OpenAI ChatGPT subscription (provider `openai-codex`, Astra) when needed. Paid per-token keys stay off limits. | n/a |
| CR2 | 2026-10-16 | Release signing falls back to the debug key when it is only partly configured (code review of `release/public-prep`) | Option 1 (owner: "CR2 yes, fix it with recommendations"): a build that packages a release APK or bundle fails with an error naming the problem when some of the four `clineTermuxRelease*` properties are set but the set is unusable; the debug-key fallback stays only when none is set; docs/release-signing.md "Building without the key" matches. | done: `app/build.gradle.kts`; checked with none set (warns, builds), a `~` store path alone and an alias alone (both fail with the message), an alias alone on `assembleDebug` (builds) and the full key (signs with the release key) |
| CR1 | 2026-10-16 | The app and the port share the name "Cline Termux" (code review of `release/public-prep`) | Option 1, "Cline kernel" (owner, after asking about "Cline Termux kernel": "Use Cline kernel in our project when publish is fine right?", then "Yeah, apply this wording I think"): the port's release reads "Cline kernel 3.0.72-termux.1" in onboarding row 4, in its two too-old texts and in the Kernel status update row; the Hub-newer dialog says "Update the Cline Termux app to keep using this Hub." The specs say the same (onboarding-kernel.md, sessions.md). | done: `strings_onboarding.xml`, `strings_kernel.xml` and the specs; the strings audit has no unresolved item |
| N1 | 2026-10-16 | An unofficial notice at first launch and in About (owner: "show a obvious hint when first time installed and about", in wording that is usual practice, because "I don't want to break the reputation of cline and termux, if users are un satisfied with out app") | The researched wording (owner: "Yeah, apply this wording I think"). The welcome screen, under the name and in place of the desktop tagline: "An unofficial app for the Cline CLI in Termux." and "Not affiliated with or endorsed by Cline Bot Inc. or the Termux project." About, under the version, and the README's licence section: the full notice (unofficial and independent; not affiliated with, endorsed by or sponsored by Cline Bot Inc. or the Termux project; Cline is a trademark of Cline Bot Inc.; the names only name the software the app works with; report problems on this app's GitHub Issues page, not to Cline or Termux). The README's first lines and the release notes: the short notice and where to report. No dialog. | done: `WelcomeScreen`, `AboutScreen` (a tonal card under the version), README, release notes, specs (KS A.4, CR C10); both screens rendered with Robolectric, light and dark, 360, 412 and 800 dp wide |

## Notes per decision

### P1 — the parity rule (2026-10-07)

Chosen in chat ("I've replied P1, chose the Option 1"). What it settled at once: A6 and A7
(presentation), A8 (closed), A2 (a bug, fix with the desktop's data), A5 (a missing
function, so it is built; only the timing is open), A1 (rule 3: the terminal's mechanism
and the kernel's global setting; the control's placement is the app's). Recorded as D19 in
docs/07 §15.


### A3 — Hub hook hang (2026-10-07)

Cause in `sdk/packages/core/src/hub/server/handlers/capability-handlers.ts`
(`requestCapability` publishes and waits with no timeout and no registered-client check)
and `hub-server-transport.ts` (`detachClientFromSessions` cancels only in-flight
requests; the session keeps the departed client's hook proxies). Upstream issues #14513
and #14824 mention the cancellation path ("Capability owner client … disconnected before
request was resolved") but not a request issued after the disconnect. Follow-up: reproduce
on the desktop (open a session the terminal last drove with hooks configured, send a
message) and file the issue with the trace from the S25U's `hub-daemon.log`. Workaround
until then: `cline hub stop` / `start`, then use the session from the app.

### A7 — Dictation offline pack (2026-10-07)

The download screen of "Speech Services by Google" is not exported, so adb cannot open it
or trigger the download; the system "Voice input" settings were opened on the phone
instead (Google → gear → offline speech recognition → download English (United States)).
The app dictates in the phone's first language (en-US now); to dictate in Chinese, put
中文 first in the phone's language list, or ask for a language choice on the microphone (a
new TODO item if wanted). The desktop has voice input too, so the microphone is parity,
not an addition.

### B5 — Notification prompt on the Reliability step (2026-10-07)

Android reports a dismissed prompt as a denial that may be asked again; only after a
permanent denial does the system stop showing the dialog, and that is when an app should
send the user to settings. The Settings → Notifications page already does this; the
Reliability step will be aligned.

### A5 — hooks (2026-10-07)

"Option one, or not build it after A1 and A2 are done, but discuss again." Taken as: hooks
are built (P1 rule 1), after A1 and A2, with a fresh go-ahead asked at that time. The A4
notice that was nested under A5 is its own TODO item now.

### B7 — K1 (2026-10-07)

Design CT §K1: the kernel's `session.restore` loses the provider's stored key. K1 rides the
next port revision (noted in the port's CLAUDE.local.md); the app switches to the one-step
restore once a release carries it.

### A2 — thinking level (2026-10-07)

The owner's restatement, confirmed with one precision: (1) for listed models the brain chip
is live for the supported ones, where "supported" comes from the provider's `reasoning`
capability in the registry (all chat models of such a provider; the bundled list carries
that after the export, the live catalog always does); (2) for a custom model it depends on
the kernel's models file (`models.json` beside the provider settings) declaring
`supportsReasoning` for it; not declared means the chip stays off, exactly as on the
desktop. The mock server's models therefore show no thinking level, on either client.

### A4 — silent hang (2026-10-07)

Both figures are the kernel's: `HUB_RUN_HEARTBEAT_MS` (30 s, `run-handlers.ts`) and
`HUB_DEFAULT_COMMAND_TIMEOUT_MS` (30 s, `sdk/packages/shared/src/hub.ts`). The app receives
`run.heartbeat` today and ignores it (`HubFrames.kt`); `ToolCallStore.STOP_TIMEOUT_MS`
(5 s) is replaced. Restart kernel exists in the kernel status sheet.

### A9 — rule 3 scope (2026-10-07)

Raised by the owner in chat ("so many / commands in TUI didn't in its Desktop"). The first
pass found that of the terminal's 15 slash commands only `/compact` has no desktop control;
the inventory agent covers shortcuts and settings too. Runs in the same go as the A1 design
pass.

### B8.1 and B8.2 — tablet checks (2026-10-07)

"I would register an account then test, on S7+, when we test on S7+" and "After everything
finished on S25U". The sign-in therefore never touches the S25U's real `~/.cline`. The
tablet needs USB debugging enabled first; the mock Hub is enough for the layouts.

### B8.3 — jank measurement (2026-10-07)

Jank = frames drawn late (over about 16 ms at 60 Hz); 30 % janky frames on the debug build
while a reply streams. Release builds are optimised, so the figure is re-measured on a
release build against the real kernel before any fix; one phone session, folded into the
next verification round.

### A1 — auto-approve control (2026-10-07)

Replies: "A'" (the owner's own variant, from the browser's site-permission icon beside the
address). Verified facts that changed the item: `apps/cli/src/main.ts:892` (missing key = on),
`apps/cli/src/runtime/tool-policies.ts` (seven safe tools), `approvals.ts:45` (per-tool policy
honoured client-side). Implementation notes in the design note §7: the app's private
`tools.autoApprove` preference is replaced; a new Termux write command for
`global-settings.json`; an idle open session restarts just before the next send; the app never
restarts a session another client is driving; show A′ and A side by side on the S25U before
the branch is final.

### A10 — app gaps (2026-10-07)

"Yeah Fix all four in the next build." Inventory: docs/research/terminal-only-functions.md §3.

### A11 (part) — terminal-only candidates (2026-10-07)

"Agree with the rest" for the rows without a question. The owner clarified that "not for now"
is a decision for now, to be settled before the next long workflow, and asked for the open rows
to be discussed in detail (rows 6 to 12 in the TODO).

### A11.6 and B9 — composer gestures (2026-10-07)

Discussed in chat: a hold on the input box collides with Android's selection toolbar, so the
owner proposed a horizontal swipe on the empty field (vertical conflicts with scrolling) and,
separately, a full-screen board for long messages as social apps do. Hazard noted: a fling
must start inside the field, since the outer 24 dp of the screen belong to the system back
gesture and the drawer edge.

### R1 and A5 timing (2026-10-07)

"Push the hub if needed for my manual test, if that's not necessary, we even don't need to
create new remote repo yet. And yes include those." Two report-level notes checked at the
same time and found to need no decision: fixture 07 (checkpoint restore traffic) is recorded
during this workflow's real-kernel verification; notification deep links stay intent extras
(D10).

### P2 — order of the next work (2026-10-07)

"So our plan is 1. update our TUI .69 release after our routine manage 2. The workflow and our
test. 3. Check the two bugs and feature again, consider contribute to upstream." Step 1 starts
with the read-only inspection; the candidate build, promotion and the app's device steps each
still wait for a go. Amended in chat: "2.5 if re consider the Bun, polish and publish our app, and the
contribution actually can happen whenever available."

### A11.8 to A11.11 (2026-10-07)

Replies in the file: 8 "would that import a lot effort to implement? If not we should build it as
well" (small, so built); 9 "the setting choice (from TUI) for reveal (from desktop) combined in our
app" (yes); 10 "No, the one who is really interested in it should go to terminal and work with
--TUI. Note this"; 11 "No, since people holding their phone can easily open this cline app".

### G5 — stuck Draining (2026-10-07)

Evidence: the S25U's `hub-daemon.log` has `hub.drain_changed {draining:true, reason:"retired by
newer install"}` at 15:21:18Z followed by a forced exit; `cline hub status` and `/health` of the
new Hub say draining=false while the app's chat still shows the Draining transport line. Code:
`KernelCoordinator.onDraining` returns early while `sequenceActive`; the `Connected` branch for a
changed `connection.generation` only re-attaches sessions and keeps the Draining state.

### A13 — fork icons (2026-10-07)

"1. the message is necessary, and two icons to distinguish them also worth to design as well
... just the > without - might help to make it more obvious a little. And yes keep all three."
Facts, corrected 2026-10-07 by the P7 review (the first version was wrong): the desktop's
message-row fork forks the whole session. `chat-messages.tsx:517-543` calls `onForkSession()`,
which is `page.tsx:2040` `handleForkSession` → `forkSession()` with no cutoff; `page.tsx:2057`
is the user row's Edit (`handleEditMessage`, `forkSession({ beforeRunCount })`). The TODO offered
option 1 as a wording change on the wrong premise that the row already cut. Branch `pkg/p7` builds
the cut as decided (the history before the answer's turn, with `fork.beforeRunCount`, which other
clients can see), so it departs from P1 rule 1 there. Open for the owner, one question: should
the message row cut as built, or fork the whole session with its own icon and label? Until the
reply, the cut stays on that branch. Both Material symbols `ms_fork_right` and `ms_call_split`
are already in the app's drawables.

### A14 and D20 (2026-10-07)

Discussed in chat: the port has never touched the Hub's protocol (its kernel changes are the Hub's
missing `entry.js`, the Android shell, `ws` kept external, OpenTUI for Android), and a port-only
command would be the first divergence. The terminal and the desktop sidecar both compact with core in
their own process (`apps/cli/src/runtime/interactive/compaction.ts`, 132 lines; sidecar
`compactSession`, 75 lines); a Hub command is estimated at 200 to 400 lines, mostly tests. The owner:
"no issue yet in the workflow, wait for us to polish and release the app then consider it again."

### D1a — workflow test rules (2026-10-07)

"don't ask it touch my storage ... I don't mind anything in termux be destructed but just keep the
other thing on my phone read-only ... you can still use ADB freely ... only try with Astra and
deepseek when you need real output, and copy credentials is ok, you can try to do that without look
into it." No hooks and no schedules were configured on the S25U at launch (`cline config hooks`,
`cline schedule list`), so real-kernel sessions run nothing of the owner's. Revoking Termux's
READ/WRITE_EXTERNAL_STORAGE restarts the Termux app; sshd and the Hub are started again over adb.

### A15, A16, A18 (2026-10-08)

Replies in the file after the owner's first look at the batch: the ticked shield must mean the
guarded state, no attention-pulling disc; "Option 3, keep that in the three dot and / command
only"; "Option 1" for the fix pass, with the gesture direction and the chip outline noted.

### A15, A11.8, A18, A19 (2026-10-08)

Replies in the file and in chat after the owner's second look: "Approve for bolt"; on A11.8, the
owner proposed cancelling the move once told that no client has one, and the lead agreed; "the
editor noticed that now, current design is fine, keep it"; and the slash-command review in chat:
"we only need three of those commands" (undo, clear, team on first pass; clear withdrawn in the
same message: "+" and the three-dot's Delete cover it), "/fork ... should be only in Three dot",
the rest are screens the app already has, and session commands must not be duplicated between
the popup and the three-dot. The little icon before "Recent prompt n of 20" goes (it duplicated
Restore checkpoint).

Second pass the same day: "ok now I find fork, it only appear in a existed session, not a new
blank session, so we should keep it in popup and similarly in existed session only"; and, since
`/fork` then lives in two places, the owner proposed `/clear` opening the three-dot's "Delete
session?" dialog, and asked where the `/undo` restore is in the app. Both answered in TODO A19
(the terminal's `/clear` starts a new session and deletes nothing).

Third pass: "ok, so no / clear. / fork stay only in three dot, so other commands or function as well,
only in one place". The lead listed the three functions the app reaches from two places (restore
row icon vs the three-dot picker; drawer row menu vs the chat three-dot; history fling vs the attach
sheet row) as TODO A20, and explained that the pencil is Edit, not `/undo`.

### A17 — G2 (2026-10-08)

After the walk-through (what the user sees today, what the timeout is for, and that the desktop's
screen is copied but the desktop freezes the run while ours cannot): "2, and maybe note this, a
better approach in future maybe." The 30 s in the old option 1 was the lead's number, borrowed
from the Hub's default command wait, withdrawn as unfair.

### A21 (2026-10-08)

"1. doesn't touch hub but only on our app side right? You know I always don't want to touch the
kernel or upstream PR, to keep our maintenance easy and tidy." Confirmed app side only.

### A22 — home folder by default (2026-10-08)

Found while answering the owner's question about the session "Hi" (the model said the command tool
could not be used): the app sent the kernel's chat-workspace path for folder-less sessions without
creating it. The lead first proposed creating that folder; the owner preferred the Termux home as the
default ("Not every user have a workspace folder... that's not a good design"; "Could we just remove
that chat from the screen, just the home folder of termux by default?") and confirmed that checkpoints
are not a concern (git-only). Recorded as A22.

### A23 — tool rows folded by default (2026-10-08)

The owner's second finding of 2026-10-08: "The default behavior of expand the command or fold it, by default it
should be folded." The app had copied the desktop's auto-open rule (`tool-message-block.tsx` `shouldAutoOpen`,
mirrored in `data/tools/ToolPresentation.kt`), which keeps every command's output expanded on a phone. Options
offered: (1) nothing opens by itself, (2) only commands start folded. Owner: option 1. Built in the polish pass
(batch 2, package Q5).

### A24 — row density (2026-10-08)

The owner's third finding of 2026-10-08: "the folded command, thinking etc are not compact enough, too sparse
for phone." Every disclosure row was 48 dp (the design system's touch-target height) with a 20 dp icon and
`bodyMedium` label, no gap between consecutive rows, 8 dp between blocks; the desktop's rows are 28 px. Offered:
40 dp (recommended) or 44 dp. Owner: 40 dp, kept adjustable in one place. Built in the polish pass (batch 2,
package Q5).

### A25 — the debug INPUT block (2026-10-08)

The owner's screenshot of a "Ran 3 commands" row (command lines, OUTPUT block, then an INPUT block) came from
the Cline Debug app. The INPUT block is the debug-only raw-arguments block (`showInputPreview` =
`BuildConfig.DEBUG`); the release app has no such block and prints command then output. Decided: no change, not even in the
debug build (owner: "Since it is debug only, it's fine, we can don't handle that").

### Compaction check (2026-10-08)

Asked by the owner about the "MyTest" session (Astra, 259 messages, 128 model calls): does `/compact` work, and
does auto-compaction loop ("cut off a little, output a little")? Facts, from the session files over ssh and five
"[test]" probe sessions on the S25U (DeepSeek `deepseek-chat`, 48 scratch files of 75k characters read one per
`read_files` call; all deleted afterwards):

- MyTest had not compacted: input tokens rose monotonically 9.8k → 103.6k, no `.compaction.json` sidecar. (Corrected 2026-10-09: the trigger figure given here at the time, 0.9 × 922k from Astra's catalog limits, was wrong for the openai-codex provider, which caps every model at 272k × 0.95 = 258,400 input tokens, trigger 232,560; see "Compaction check 2" below.)
- The app's path works: every create carries `sessionConfig.compaction` (`HubSessionApi.compaction`, G1,
  `{enabled:true}` unless Settings says off), the Hub spreads `sessionConfig` into the runtime config
  (`session-handlers.ts`), and `local-runtime-host.ts` builds the auto-compaction step from it. A Hub-backed CLI
  session with the same field compacted at the 37th read ("Context compacted · 115.6k → 43.1k tokens · 73 → 5
  messages") and wrote the sidecar; an in-process run with `CLINE_LOG_LEVEL=debug` showed the kernel's own numbers
  (trigger 115,200 of a 128,000 budget).
- Kernel defect (upstream, no port patch): after the first compaction the kernel re-compacts every five large tool
  results ("124.3k → 43.1k" three times in 48 reads). Its per-turn estimate counts the retained results untruncated
  (48,180 characters each) while the request it sends keeps every result at the message builder's 8,000-character
  cap, so `actualPreviousInputTokens` stayed at 12–23k while `requestInputTokens` read 124k. Before the first
  compaction the estimate tracked the truncated results (8,230 characters each) correctly. Identical through the
  Hub and in-process. It bites sessions with large tool results on 128k-window models; on Astra it needs ~830k
  estimated tokens first.
- Also found: `cline -z` (zen) sessions are created by `HubSessionClient.startRuntimeSession` without any
  `compaction` field, so they never auto-compact (two zen probes reached 137k real tokens untouched). Upstream
  report material; the app is not affected.
- What the owner saw as "cut off": the kernel echoes each command back in the result after 200 characters as
  "… [command truncated: N more chars; full command is in the tool call input]" and the app prints that echo as
  the `$` line of multi-command rows (trial row 18, batch 2). Outputs are complete in the transcript; the 8k cut
  exists only in what the model is sent. `/compact` from the app stays parked (A19): the terminal runs the
  summariser itself and pushes the state with `session.compaction.update`; the Hub has no command for it.
- The header showing the first prompt instead of "MyTest" in that screenshot is not a defect: the rename came
  four minutes after the screenshot (`session.update` from the app at 23:26:52; screenshot 23:22:50).

### Compaction check 2 (2026-10-09)

The owner: in "MyTest" every new command shows "auto-compacting" and then "auto-compaction skipped"; is it
this old session or another error? Read-only evidence from the phone (Hub event log
`~/.cline/data/db/hub-events-hub-production.db`, the session files, the Hub log) plus an offline replay of the
kernel's compaction step over the transcript on the workstation (`tools/kernel/compaction-replay.ts`, run from
the port repo with a stub provider; the real run's budget numbers matched the replay within 14 tokens):

- The Hub event log holds four pairs of notices today (08:57, 11:14, 11:16, 11:18): `auto-compacting` with
  `triggerTokens 232560, targetTokens 129200, maxInputTokens 258400, messageTargetTokens 118358`, then
  `auto-compaction-skipped` 4.2–6.1 s later. The provider's own count of the same requests: 103.6k–104.1k input
  tokens (assistant message metrics), about 40 % of the cap.
- Why the trigger fires: the openai-codex provider (ChatGPT subscription) caps every model at Codex's
  400k-context / 272k-input budget × 0.95 (`sdk/packages/llms/src/providers/openai-codex-models.ts`, since
  cli-v3.0.62), so Astra's maxInputTokens is 258,400, not the catalog's 922,000. The kernel's estimate of the
  request is characters ÷ 3 over the API messages (`CHARS_PER_TOKEN = 3` in `@cline/shared`): 347,903 tokens for
  MyTest, of which the screenshot attached at message 233 (2026-10-08 16:14, 333,899 base64 characters) is
  111,300 on its own; the tool results are already capped at 8,000 characters each in that count (95 results,
  8 over the cap in the transcript). So the notices most likely started with the turn after that screenshot.
- Why it is skipped: the agentic summariser builds a 100,553-character summary request (33.5k estimated tokens,
  43 truncation actions, so the request is not oversized) and sends it to the session's own model (Astra). The
  replay shows the two possible outcomes: a provider error makes the kernel fall back to basic compaction, which
  succeeds on this transcript (267 → 74 messages, `auto-compacted`); an answer with no text makes the kernel log
  "Skipped agentic compaction: summarizer returned no summary text" and emit `auto-compaction-skipped` with no
  fallback. The phone shows the second, so Astra answers the summary request with no text; for a reasoning model
  the kernel's own likely cause is "output_budget_consumed_by_reasoning", otherwise "empty_response". The exact
  cause is not recoverable from the phone: the Hub daemon constructs its runtime host without a logger
  (`start-shared-server.ts`), so every compaction log line is discarded there; `CLINE_LOG_LEVEL` would not
  help. One in-process Astra run with debug logging would name it when the U10 report is written.
- Cost of the loop: the empty-response retry middleware re-sends the summary request (three identical calls in
  the replay), which fits the 4–6 s and means each turn spends up to three extra model calls of ~33k tokens.
  Nothing is written to the session, so the real turn still runs and answers.
- Not the app (the app only sends `{enabled:true}`, the kernel does the rest), not the v3.0.70 upgrade (the
  compaction code did not change between .69 and .70, and the phone's Hub was still the .69 daemon anyway, see
  trial row 24), and not "old sessions" as such: any session with a screenshot or two plus some tool output
  crosses 232k in the kernel's count long before the provider's real limit. Practical answer for the owner:
  start a new session for new work; MyTest keeps answering (the provider sees 104k of 258k), only the
  compaction attempt is wasted each turn.
- Replay recipe (workstation, no model, no phone writes): copy the session's `<id>.messages.json` to a scratch
  dir, `bun run build:sdk` in the port repo, put `tools/kernel/compaction-replay.ts` under `release/.work/` there
  and run `bun run release/.work/compaction-replay.ts reject|empty <messages.json>`; it prints the kernel's
  "Context compaction diagnostics", the summariser diagnostics and the notice sequence for each outcome.
- Follow-up the same afternoon (owner: "please restart that"): `cline hub stop` ended the .69 daemon in 3 s,
  `cline hub start` brought up the .70 one (pid 27001, core 0.0.92, `3.0.70-termux.1/entry.js`, 11:54). An
  in-process diagnostic turn on MyTest was not possible: `cline --id <session>` always switches to interactive
  mode and drops the prompt argument (`apps/cli/src/main.ts`, `startupTarget = "chat"`), and interactive mode
  needs a TTY. Two more facts narrow the cause: the provider adapter turns any upstream error into a
  `finish` with `reason: "error"` and a message (`ai-sdk.ts`), which the summariser throws on and the kernel
  answers with the basic fallback (that path ends in `auto-compacted`, replay mode `reject`), so the phone's
  `skipped` means the summary request came back 200 with no text; and the kernel sends every summary request
  with reasoning off (`resolveSummarizerConfig`, `thinking: false`), exactly the request shape the v3.0.70
  changelog says failed on GPT-6 Astra before the AI SDK bump ("tasks with reasoning off no longer fail with a
  400 error on GPT-6 Astra"). Whether the .70 daemon now compacts MyTest is decided by the next turn the owner
  sends there: the event log will show `auto-compacted` (summary written, sidecar created) or `skipped` again.
- **Result (owner, 11:58): "Wow, this time auto-compacted works!"** Event log: `auto-compacting` 03:58:25Z,
  `auto-compacted` 03:58:53Z with `tokensBefore 347929, tokensAfter 134254, messagesBefore 268, messagesAfter 36`
  (the replay had said 347,903, so it was faithful to 26 tokens); the sidecar `1791440672593_sukbu.compaction.json`
  exists (383 KB); the provider's input for the turn dropped from 104k to 19.5k tokens; the next turn (03:59:35Z)
  ran with no compaction notice. The screenshot sits inside the 34 preserved recent messages, so it still counts
  111k of the 134k that remain; it leaves the context at a later compaction. So the daily-use breakage was the
  .69 AI SDK's reasoning-off request to Astra, fixed upstream in .70 and live only after the Hub restart. What
  stays for upstream: U9 (image bytes counted as text, the early trigger) and U10, now narrowed to the kernel's
  handling of an empty summary (no basic fallback, no backoff, no log line in the Hub).

### CR1 and CR2 — code review of `release/public-prep` (2026-10-16)

The owner's `/code-review ultra` run against `main`, before the first publish, rated all four of its
findings nits. CR1 and CR2 are in the table. The third, stale `dev.cline.termux.debug` ids in the
debug KDoc and docs/09 §2, was fixed by the review session and checked by the lead. The fourth, the
logo's path data copied into six drawables (three 24-unit, three 108-unit), gets no change: the logo
is final, and `WelcomeHero` is the only place where two copies must line up (`ic_app_logo` and
`ic_app_logo_outline`).

### N1 — the unofficial notice (2026-10-16)

The usual practice the wording follows. The Apache License 2.0 (§6) gives no trademark rights beyond
"reasonable and customary use in describing the origin of the Work". Naming another product to say
what yours works with is nominative fair use when it uses only as much of the name as needed and
suggests no sponsorship (the New Kids test); a disclaimer is not required, but it strengthens the
case (Toyota v. Tabari). Google Play's impersonation policy says a disclaimer does not cure a
look-alike name or icon, which is why the app also has its own name and mark. The standard parts of
such a notice: unofficial and independent; "not affiliated with, endorsed by or sponsored by"; whose
trademark the name is, and that other names belong to their owners; the names used only to identify;
and where to send problems, as Flathub's NewPipe build does ("Please report bugs to the ATL bug
tracker instead of the NewPipe bug tracker"). Termux publishes no trademark notice, so the notice
names "the Termux project" only; the legal name in Cline's LICENSE is Cline Bot Inc.

Sources, read 2026-10-16:

- https://www.apache.org/licenses/LICENSE-2.0 (§6, Trademarks)
- https://klemchuk.com/ideate/nominative-fair-use
- https://www.healthcarelawinsights.com/2016/08/branding-101-nominative-fair-use/
- https://support.google.com/googleplay/android-developer/answer/9888374 (impersonation)
- https://flathub.org/apps/net.newpipe.NewPipe
- https://librewolf.net/license-disclaimers/
- https://pkg.go.dev/github.com/portapps/mirc-portable
- https://docs.redhat.com/en/documentation/red_hat_amq/7.1/html/using_the_amq_.net_client/legal-notice

### D1 — Test credentials (2026-10-07)

Said in chat with the A8 reply: "just use my real GPT subscription during test is
approved, when needed, just use it." Applies to agents verifying on the S25U against the
real kernel. The mock provider stays the default for anything that does not need a real
model.

### Batch 1 build status (2026-10-07)

Built on `batch/decided-1` (eight packages P1-P8, merged in the order P8, P2, P3, P4, P1, P5,
P6, P7), verified on the S25U in one round (bench Hub plus the real kernel for A2 and B8.3) and
closed out with a fix of a checkpoint defect found by fixture 07. Per-item evidence, the
deviations and the owner questions are in [10-batch1-report.md](10-batch1-report.md). Items
still open after the batch: A1 (only the A′ vs A pick), A11.9
(recovery notices, upstream), A13 (glyph and cut). Deviations the owner should confirm: A5 (no
hook runner, F1), A11.8 (delete-then-create, agent sessions refused, a recovery copy in app
storage), G2 (guidance returned in the decision without the desktop's hold hooks; the run keeps
calling the model while the question is open), A13 (the cut departs from the desktop row).

### Batch 2 build status (2026-10-08)

Built on `batch/polish-2` as five packages (Q1 history direction and chip; Q2 A15 glyphs, the
A16/A19 fork and slash changes and the A11.8 removal; Q3 trial rows 8 and 16, A17's stop
notice, A21 and A22; Q4 trial rows 11 to 14; Q5 A23, A24 and trial row 18), merged in the order
Q2, Q3, Q1, Q4, Q5, verified on the S25U against the bench Hub and, for the model-backed checks,
the real kernel with DeepSeek. Eighteen items: seventeen passed in round 1; V-TEAM failed
(`/team <task>` was not refused with Teams off, because core 0.0.91's `settings.list` carries no
built-in tools) and was fixed in `da81883`, which reads `global-settings.json` `disabledTools` as
the desktop does; the close-out rechecked it on the device. Trial row 15 needed no change: the
self-check prose was the model's own answer text. The branch was merged into `main`. Per-item
evidence, deviations and limitations are in [11-batch2-report.md](11-batch2-report.md).
Deviations the owner may want to look at: A22 names the folder "Home" when nothing is chosen but
"~" when the home is chosen explicitly; row 16's title is put back about 1 s after the first run
rather than at once; A17 gives no notice when "Try a different approach" falls back to stop; the
history fling mirrors in right-to-left layouts.
