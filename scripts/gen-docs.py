#!/usr/bin/env python3
"""Render docs/research/kernel-and-desktop-synthesis.json into Markdown docs."""
import json
import os
import re

# The checkout this script lives in (a worktree renders into its own docs/).
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = f"{ROOT}/docs/research/kernel-and-desktop-synthesis.json"
OUT = f"{ROOT}/docs"
STAMP = ("_Generated on 2026-10-03 from `docs/research/kernel-and-desktop-synthesis.json` "
         "(multi-agent code reading of the Cline monorepo at cli-v3.0.68 + the Termux port). "
         "Line references point into `~/workspace/cline-termux-v3-phone`._\n")

s = json.load(open(SRC))["result"]["synthesis"]
os.makedirs(OUT, exist_ok=True)


def write(name, body):
    with open(f"{OUT}/{name}", "w") as f:
        f.write(body)
    print("wrote", name)


def para(text):
    # Break the research prose at sentence-level semicolons for readability.
    return text.replace("; ", ";\n  ")


# 01 architecture
a = s["architecture"]
write("01-architecture.md", f"""# Architecture decision

{STAMP}
**Decision: `{a['recommendation']}`** — a native Hub client for everything the Hub serves,
plus a thin Termux-side bridge over the `RUN_COMMAND` intent for lifecycle and the
surfaces the Hub does not expose.

## Rationale

{a['rationale']}

## Rejected alternatives

{a['rejected_alternatives']}
""")

# 02 kernel contract
pl = s["pairing_and_lifecycle"]
# Hand-written corrections (design phase) kept in the generator so a rerun does not drop them.
pl = pl.replace('run.interrupted {reason:"hub_restart"}', 'run.interrupted {runId, error, reason:"hub_restart"}', 1)
pl = pl.replace(
    "offer resend;",
    "offer resend (caveat: the Hub publishes it only from HubRunQueue.recoverOnStartup() for runs "
    "admitted via run.enqueue, hub-server-transport.ts:683-695; the app's own session.send_input = "
    "run.start turns never get it, so detect those from an open run.started with no terminal run.* "
    "plus a changed /version pid/startedAt on reconnect or the replayed shutdown projection "
    "(run.completed {reason:\"disposed\"}, or session.updated with snapshot.status "
    "cancelled/completed and endedAt) — design/screens/home-chat.md §1.3.4);",
    1,
)
sections = []
for block in pl.split("\n\n"):
    m = re.match(r"^([A-Z][A-Z0-9 '’()/\-]+):\s*(.*)$", block, re.S)
    if m:
        sections.append(f"## {m.group(1).title()}\n\n{m.group(2)}\n")
    else:
        sections.append(block + "\n")
# Hand-written: what batch 1 (2026-10-07) added to the contract, checked against core 0.0.91
# (cli-v3.0.69) on the bench and the S25U. Kept here so a rerun does not drop it.
BATCH1_CONTRACT = """## Batch 1 Additions (Core 0.0.91, 2026-10-07)

Verified on the bench Hub (core 0.0.91) and the S25U; results in docs/10-batch1-report.md.

**Shared settings file.** The app reads `~/.cline/data/settings/global-settings.json` with `cat` and writes it only by a one-key merge run as `bun-ffi -e` through RUN_COMMAND (JSON on stdin, temp file plus rename, other keys untouched; `KernelGlobalSettingsCommands.write`). Keys it writes: `toolAutoApprove` (A1; a missing key means on, as in the terminal, `apps/cli/src/main.ts`), `compactionEnabled` and `compactionStrategy` (`agentic` = LLM, `basic` = Truncation; Off keeps the strategy, as `setCompactionModeGlobally` does). The terminal reads the same keys, so both clients share the setting.

**Tool policies on every create path.** `sessionConfig.toolPolicies` as the terminal builds them: Auto-approve all = `{"*":{autoApprove:true}}`; Ask each time = `{"*":{autoApprove:false}}` plus `autoApprove:true` for the seven read-only tools of `SAFE_AUTO_APPROVE_TOOL_NAMES`. A session's policy is fixed when its runtime is created; to change it the app restarts an idle session it drives (`session.detach`, then `session.create {sessionConfig.sessionId:<same>, initialMessages:<session.messages>}`), at the end of a run it started or just before its next send, never while another participant is attached. Two record quirks of core 0.0.91: every `session.update` carrying metadata (a pin) drops `metadata.autoApproveTools`, and a resume stamps the new policy only in memory, so the `session.updated` / `run.*` records at the run's end (and `session.get` afterwards) carry the creation-time value again. The app therefore trusts the policy of the runtime's latest create it saw over any later record (`SessionState.createdAutoApprove`).

**Compaction.** Every `session.create` (new, resume, restore, fork) carries `sessionConfig.compaction`: `{enabled:true}` when the global keys are absent (core's auto-compaction is opt-in; G1), `{enabled:false}` for Off, `{enabled:true,strategy:"agentic"|"basic"}` otherwise. `session.compaction.get {sessionId}` answers `{sessionId, state?}` only to the client that created the session, and `session.detach` ends that right; the app reads it before a detach and passes it back as top-level `initialCompactionState` on the following create (run-boundary restart, act-mode rebuild), like the terminal's `restartWithCurrentMessages` (the app sends the state as read, without re-projecting it). A compaction change does not restart an open idle session; it applies at that session's next create.

**Client contributions.** `runtimeOptions.clientContributions` on every create path: the `askQuestion` tool executor, `{kind:"mistakeLimit", capabilityName:"mistake_limit.decide"}` (G2) and, for a session whose `sessionConfig.mode` is `plan` only, the CLI's `switch_to_act_mode` tool (`kind:"tool"`, `lifecycle.completesRun`). Runtime tools are fixed at create from the mode: after a successful switch the run ends, the app rebuilds the session in act mode (detach plus create, as above) and sends the CLI's continuation prompt in act mode; in an act runtime the tool does not exist. `ClientContributions` answers every `capability.requested` aimed at this client once (dedupe by `requestId`), the mistake-limit decision in the CLI's shape (`{action:"continue", guidance}` or `{action:"stop", reason:"stopped after mistake_limit_reached prompt"}`), and any unknown capability with `ok:false`, so a run never waits on the phone for something it cannot do. With no hold hooks, core keeps calling the model while the mistake-limit question is open (about 60 extra model calls in 11 s on the bench). A capability request is cancelled by the Hub when its owner's socket drops; core treats a cancelled decision as stop.

**Hooks.** The Hub runs the user's hook files itself (global `~/.cline/hooks/<Event>.sh`, workspace `.cline/hooks/<Event>.sh`) for every session, the app's included: core's default config extensions include `hooks`. A hook gets the event JSON on stdin and may print `{"contextModification":"..."}` (added to the next model request as `<hook_context>`) or `{"cancel":true,"errorMessage":"..."}`. In core 0.0.91 a cancelling `PreToolUse` stops the whole run, not only the call (`run.aborted`, no tool event, no tool result), and its `errorMessage` reaches no client (no event, message or log; an upstream report candidate). Hooks spawn in the session's cwd, so a session whose folder does not exist on the Hub's host cannot run them (`posix_spawn` ENOENT; the tool goes ahead). The app registers no hook contribution and never sends `configExtensions`: that would run each hook twice and make every tool call wait for the phone (TODO A3). `UserPromptSubmit` does not fire on the Hub path.

**Heartbeat.** `run.heartbeat {requestId, elapsedMs, timeoutMs, timestamp}` arrives every 30 s (`HUB_RUN_HEARTBEAT_MS`) while a run is open. The app raises its stall notice ("The kernel has been running for 30 s without responding", Keep waiting / Restart kernel) on a live heartbeat when no other event of that session arrived since the previous heartbeat (or since the run start plus 2 s for the first one) and nothing waits on the user or a tool; any other session event clears it. Stop and the approval sheet's Stop give up after the protocol's 30 s command timeout (`HUB_DEFAULT_COMMAND_TIMEOUT_MS`) and offer Restart kernel.

**Drain across a replacement Hub.** A replacement Hub replays the old Hub's `hub.drain_changed` events from the shared durable log. The draining flag belongs to the connection: each new connection's `hub.status` replaces it, a `hub.drain_changed` at or below that status's `lastSequence` is ignored, and the kernel state is re-derived when the connection generation changes (G5).

**Steer.** Long-press Send while a run is active sends `session.send_input {delivery:"steer"}`; the Hub injects the text into the running turn (the next model request of the same run carries it, and the in-flight stream is cut short). No queue entry.

**Checkpoints.** Core 0.0.91 creates checkpoints daemon-side for a plain client (`metadata.checkpoint.history`, kinds `commit` / `stash`, one per run; fixture 07). A restored or forked session's inherited history is written with numeric `createdAt`, as core's `createRestoredCheckpointMetadata` writes it.

**Termux-side files the app now touches.** Prompt history `~/.cline/data/cache/user_input_history.jsonl` (the terminal's file: oldest first, one `{"prompt","ts"}` per line, at most 20; read with `cat`, appended with a `bun-ffi -e` rewrite after a direct send the Hub accepted, as the terminal does). Task worktrees: `git worktree add` into `~/.cline/worktrees/<5 hex>/<repo name>` on branch `cline/<id>` (bash scripts with paths only as positional arguments); removed with the session unless another listed session lives in it, mirroring the desktop's `removeTaskWorktree`.

**Not delivered by the Hub (core 0.0.91).** Recoverable agent errors are dropped by `session-event-projector.ts` (`projectAgentErrorEvent` returns early on `recoverable`), so no client receives recovery notices (A11.9's inline half waits on an upstream change). No Hub command compacts on demand (A14).
"""
sections.append(BATCH1_CONTRACT)
# Hand-written: batch 2 (2026-10-08), same reason.
BATCH2_CONTRACT = """## Batch 2 Additions (Core 0.0.91, 2026-10-08)

Verified on the bench Hub and the S25U; results in docs/11-batch2-report.md.

**Fork is whole-session only.** The app's one fork (decided A16, A19) reads `session.get` and `session.messages` and sends `session.create {initialMessages:<all messages>}` with `metadata.fork {forkedFromSessionId, forkedAt, source, checkpoints}`. It never cuts the history and never writes `fork.beforeRunCount`. It is reached from the chat's three-dot menu and the drawer's row menu; message rows have no fork and a typed `/fork` is plain text to the model. The user row's Edit is a different function, the checkpoint flow, and it still carries the edited run as `beforeRunCount`, as the desktop's edit-fork does. The batch-1 move to another workspace (delete, then create under the same id) is gone with A11.8: the app has no path that deletes a session in order to recreate it.

**Stop at the mistake limit.** No Hub event says why a run stopped: `run.aborted` carries only `aborted`, whether the user answered `stop` to `mistake_limit.decide` or the app gave up after its 5-minute wait. When the Hub accepts the app's `stop`, the app adds its own notice row to the transcript ("Stopped after N mistakes in a row", with the kernel's latest detail as "Latest: ...", or "Stopped: the question was not answered within 5 minutes"). The notices live only in the app's `sessions` DataStore (`sessions.stopNotices`, newest 200) and are dropped with the session; the terminal, the desktop and `cline history export` never see them. The model call already in flight when Stop was answered lands after the notice.

**Interrupted tool calls.** Core 0.0.91 leaves a `tool_use` without a result when a cancelling `PreToolUse` hook ends the run (U5). The desktop projects such a history call as in progress. The app projects a call with no result in a finished turn as "Interrupted: <command, file or tool>" with a warning glyph (decided A21); a call in a live run still shows Running. This is presentation only; nothing is sent to the Hub.

**Titles at the first prompt.** Core 0.0.91 persists a brand-new session only at its first prompt (`ensureSessionPersisted`) and titles it from that prompt, over a title given at `session.create`. When this app sends the first prompt to a session whose stored title was set at create, it renames the session back with `session.update` about 1 s after that run ends, once, and only if the record still holds exactly the prompt-derived title (`CreateTitleKeeper`). Forks are persisted at create and keep their title.

**The session list is authoritative.** The Hub sends no event when another client deletes a session. Every `session.list` reply decides which rows exist; the app's list cache keeps only rows a list confirmed, and replayed events at or before the sequence seen just before a list request cannot add back a session that list did not return.

**Default folder.** A session started without choosing a folder sends the Termux home (`/data/data/com.termux/files/home`) as `workspaceRoot` and `cwd` (decided A22) instead of the kernel's chat-workspace path, which the app never created. The app writes nothing there.

**Teams and skills switches.** Core 0.0.91's `settings.list` returns plugin tools only, never the built-ins, so whether `teams` or `skills` is switched off is read where the desktop reads it: `global-settings.json` `disabledTools`, by `cat` at the send that needs it. `/team <task>` with Teams off is refused before anything is sent; the checks run in the desktop's order (usage, disabled, mode).

**Full commands.** A `run_commands` result's `query` is the kernel's 200-character preview with a truncation note. The app prints each command from the tool call's input (`commands[i]`, normalised as the kernel normalises it) and uses the preview only when the input is missing or does not match it, never showing the note.
"""
sections.append(BATCH2_CONTRACT)
write("02-kernel-contract.md", "# Kernel contract (Termux + Cline Hub)\n\n" + STAMP + "\n" + "\n".join(sections))

# 03 feature matrix
fm = s["feature_matrix"]
tiers = {}
for f in fm:
    tiers.setdefault(f["tier"], []).append(f)
body = ["# Feature matrix", "", STAMP,
        "`available_via`: **hub** = Hub WebSocket (`ws://127.0.0.1:25463/hub`, protocol v1); "
        "**sidecar** = Termux-side bridge (`RUN_COMMAND` running `$PREFIX/bin/cline`, or the shipped "
        "`cline dashboard` server); **files** = `RUN_COMMAND cat`/write of a `~/.cline` file; "
        "**none** = Android-native or omitted.", ""]
tier_titles = {1: "Tier 1 — connect and chat", 2: "Tier 2 — sessions, settings, model",
               3: "Tier 3 — diffs, terminal, checkpoints, mentions", 4: "Tier 4 — the rest"}
for t in sorted(tiers):
    body.append(f"## {tier_titles.get(t, f'Tier {t}')}")
    body.append("")
    for f in tiers[t]:
        desk = "desktop has it" if f["desktop_has"] else "not in desktop"
        body.append(f"### {f['feature']}")
        body.append("")
        body.append(f"- **Via:** `{f['available_via']}` · {desk}")
        body.append(f"- **Kernel API:** {f['kernel_api']}")
        body.append(f"- **Notes:** {f['notes']}")
        body.append("")
body += [
    "## Corrections from recorded traffic (2026-10-03, see 08-hub-fixtures.md)",
    "",
    "- **Checkpoints are not created by the daemon.** `sessionConfig.checkpoint.enabled` alone leaves "
    "`snapshot.checkpoint.history` empty; creation is a *client contribution* "
    "(`runtimeOptions.clientContributions: [{kind:'checkpoint', capabilityName:'checkpoint.create'}]`, "
    "answered over `capability.requested`). The app must implement that capability itself (a git snapshot "
    "run inside Termux) or ship the Tier-3 checkpoint dialogs disabled. `session.restore` has nothing to "
    "restore without it.",
    "- **`ui.notify` does not fire per turn.** It is published only when a session is disposed. Build the "
    "'task completed / failed / approval needed' notifications from `run.completed`, `run.failed`, "
    "`approval.requested` and `capability.requested`.",
    "- **Every session event is also delivered on the global subscription** (same `eventId`/`sequence`). "
    "Subscribe globally with one persisted cursor and route by `sessionId`, or dedupe by `sequence`.",
    "- **`session.update {metadata}` replaces metadata wholesale.** Spread the current `session.metadata` "
    "and set the changed key (`pinned`, …), as the desktop does.",
    "- **User text is persisted wrapped** as `<user_input mode=\"act\">…</user_input>` (also in "
    "`metadata.prompt`); strip it for display like upstream's `formatDisplayUserInput`, and read the mode "
    "attribute for the per-message Plan/Act marker.",
    "- **Rejecting an approval does not stop the turn**: the tool reports the rejection as its result and "
    "the model continues; 'reject and stop' needs `run.abort` as well.",
    "- `run.started {requestId, clientId}` echoes the `session.send_input` envelope — correlate the turn by "
    "it. `agent.done` precedes `run.*` by a few ms; `run.completed` with `reason != \"completed\"` "
    "(e.g. `disposed`) is not success.",
    "- `session.search` lags for live sessions (the FTS index refreshes on session end); "
    "`session.delete` may reply `deleted:false` for a session without history even though it is gone.",
    "- `metadata.source: \"android\"` advertises the `tasks` tool and the Agenda prompt section; "
    "`ask_question` needs the askQuestion client contribution at `session.create`; `skills` appears only "
    "when at least one skill is configured.",
    "",
]
write("03-feature-matrix.md", "\n".join(body))

# 04 screens
sc = s["screens"]
body = ["# Screens", "", STAMP, "Each screen names its Cline Desktop equivalent so the design "
        "phase can align layout, copy and behaviour with the desktop app.", ""]
by_tier = {}
for x in sc:
    by_tier.setdefault(x["tier"], []).append(x)
for t in sorted(by_tier):
    body.append(f"## {tier_titles.get(t, f'Tier {t}')}")
    body.append("")
    for i, x in enumerate(by_tier[t], 1):
        body.append(f"### {x['screen']}")
        body.append("")
        body.append(f"- **Desktop equivalent:** {x['desktop_equivalent']}")
        body.append(f"- **Data sources:** {x['data_sources']}")
        body.append("")
write("04-screens.md", "\n".join(body))

# 05 open questions
bu = s["blocking_unknowns"]
body = ["# Open questions and blocking unknowns", "", STAMP,
        "Status column maintained by hand after each experiment.", ""]
status = {
    2: "**Gate.** `v3.0.68-termux.2` is pushed and inspected but the candidate build has not "
       "been started (owner decision pending). Until it is installed on the S25U, Tier 1 can "
       "only be tested against the workstation Hub (Bun 1.4.2 x64).",
    4: "**Resolved 2026-10-03 (on the S25U, from the app's own RunCommandService bridge).** The "
       "background (APP_SHELL) runner works for the launcher: `$PREFIX/bin/cline --version` → exit 0, "
       "`3.0.68`, 2.5 s; `cline hub status` → exit 0 in 1.5 s; `env` shows `PATH=$PREFIX/bin`, "
       "`PWD=$HOME`, `TERMUX__PREFIX`, `TMPDIR`, `LANG=en_US.UTF-8` (a login-like environment). "
       "Results arrive through the mutable one-shot PendingIntent with `result` bundle keys "
       "`stdout/stderr/exitCode/err/errmsg`; a non-existent executable comes back as `err=150` with an "
       "`errmsg` (exitCode -1) in ~20 ms; `false` → `exitCode=1, err=-1`. `startForegroundService` from "
       "our side is fine (RunCommandService calls startForeground itself). `termux-wake-lock` acquires "
       "`PARTIAL_WAKE_LOCK 'termux:service-wakelock'` (verified in `dumpsys power`) in 0.3 s.",
    5: "**Resolved 2026-10-03 (on the S25U).** `xdg-open https://example.com` from the background "
       "runner exits 0 after 2.9 s but opens nothing (the top activity stays the app), and a "
       "foreground-session command (`BACKGROUND=false`, `SESSION_ACTION=0`) runs but does not bring "
       "Termux to the front either: both are background activity starts that Android 10+ blocks. "
       "Design consequence: for `cline auth cline` the app launches the command in a Termux terminal "
       "session AND itself starts Termux's main activity right after (allowed: the app is in the "
       "foreground), so the user sees the URL/device code in Termux and Termux, now foreground, can "
       "open the browser. The app never needs the browser opened from a background shell. "
       "(`appops get com.termux SYSTEM_ALERT_WINDOW` showed `default; rejectTime=+2m46s` right after the "
       "probe — the rejected background start. Granting Termux 'Display over other apps' would lift "
       "the block; the kernel-setup checklist can mention it as optional, not required.)",
    7: "**Resolved 2026-10-03 (code read).** `resolveToolPolicy` (sdk/packages/agents/src/agent-runtime.ts:216) "
       "merges `policies['*']` then `policies[toolName]`, so per-tool rules key on the built-in tool names: "
       "`read_files`, `search_codebase`, `run_commands`, `editor`, `apply_patch`, `fetch_web_content`, "
       "`ask_question`, `skills`, `submit_and_exit` (definitions.ts). Desktop's 'safe tools auto-approved' "
       "= `{'*': {autoApprove: false}, read_files: {autoApprove: true}, search_codebase: {autoApprove: true}, "
       "fetch_web_content: {autoApprove: true}}` at session.create.",
    9: "**Mostly resolved 2026-10-03 (code read).** The default file-based user-instruction service "
       "implements `createSkillsExecutor` (core/src/extensions/config/user-instruction-service.ts:205,324) "
       "and the aggregate at :71 feeds `executors.skills`, which definitions.ts:964 gates on — so Hub "
       "sessions created without `configExtensions` should expose the `skills` tool and expand `/skill` "
       "commands daemon-side (`resolveRuntimeSlashCommand`). Confirm once with a `/skill` prompt against "
       "the workstation Hub before relying on it; keep the `cat <skill path>` inline fallback otherwise. "
       "Recorded traffic (08-hub-fixtures.md) could not settle it: the `skills` tool is only advertised "
       "when at least one skill is configured, and the fixture home had none.",
    10: "**Resolved 2026-10-03 (code read).** `handleClientRegister` "
        "(core/src/hub/server/handlers/client-handlers.ts:14) does `ctx.clients.set(clientId, ...)` "
        "unconditionally: re-registering with the same clientId replaces the stale registration and "
        "publishes `hub.client.registered` again. `client_already_registered` exists only in the browser "
        "transport (browser-websocket.ts:158). Reuse one stable clientId per install.",
    11: "**Checked 2026-10-03 (read-only, adb + ssh):** `settings_enable_monitor_phantom_procs` is "
        "unset (`null` = platform default, i.e. the phantom-process monitor is ON; the developer "
        "option has not been flipped); `dumpsys deviceidle whitelist` lists `com.termux`, "
        "`com.termux.api` and the owner's `dev.cline.voice`, not yet `io.github.ichouchiang.clinetermux`; Termux "
        "0.118.3; `allow-external-apps=true`; `termux-wake-lock`, `xdg-open` and `termux-open` are "
        "installed; no Hub discovery record exists yet (the installed port is still .1).",
    1: "**Confirmed on the workstation Hub (real `ws` server) 2026-10-03, see 08-hub-fixtures.md:** the "
       "101 echoes `Sec-WebSocket-Protocol: cline-hub-auth.<token>`; wrong/missing token → 401; "
       "`Origin: http://localhost` without token → 101 with no subprotocol header. Still to confirm on the "
       "device Bun (bun-android-ffi's built-in ws server) once `v3.0.68-termux.2` is installed — the "
       "app's kernel probe screen can do it in one tap.",
    6: "**Resolved 2026-10-03 (recorded, fixture 05):** after `hub stop`/`hub start`, "
       "`session.create {sessionConfig.sessionId:<old>, initialMessages:<session.messages>}` returns the "
       "same sessionId, usage recomputed from message `metrics`, metadata merged (title/pinned kept); the "
       "next `session.send_input` runs a normal turn with the full history re-seeded. Checkpoint history "
       "does not come back — see the checkpoint correction in 03-feature-matrix.md.",
    8: "**Resolved 2026-10-03 (recorded, fixture 02/03):** `tool.updated.update` for `run_commands` is "
       "`{stream: stdout|stderr, chunk (incremental, append per executionId+stream), executionId, "
       "detachable, commandIndex, query}`; the first update has an empty chunk; coalesced every 48 ms; "
       "`truncated:true, droppedChars` when output is dropped; ANSI passes through. `tool.finished.output` "
       "is `[{query, result, success}]`; a rejected call still emits started/finished with `output.error`. "
       "`editor`/`apply_patch` payloads were not recorded (no file tools in the mock run).",
    10: "**Resolved with a caveat 2026-10-03 (recorded, fixture 04):** re-registering the same clientId "
        "after the old socket closed is accepted; re-registering while the old socket is still open is "
        "accepted too, but when the old socket is later reaped the Hub unregisters that clientId and the "
        "new connection loses its record. Rule: close the old socket (and wait for the close callback) "
        "before opening the replacement; a half-open socket the Hub has not yet swept (30 s) is the trap.",
    13: "**Resolved 2026-10-03.** Wrapper generated from the Gradle 9.6.0 distribution; "
        "`./gradlew` works. The first build then showed that the Compose 1.12.1 / navigation 2.10.2 / "
        "lifecycle 2.11.0 / OkHttp 5.5.0 AARs require `compileSdk` 37, so SDK platforms "
        "`android-37.0` and `android-37.2` plus build-tools 37.0.0 were installed and the app "
        "compiles against 37 (`targetSdk` stays 36).",
}
for i, q in enumerate(bu, 1):
    text = re.sub(r"^\d+\.\s*", "", q)
    body.append(f"## {i}. {text.split(':')[0]}")
    body.append("")
    body.append(text)
    body.append("")
    if i in status:
        body.append(f"Status: {status[i]}")
        body.append("")
write("05-open-questions.md", "\n".join(body))

# 06 toolchain: hand-written sections kept in the generator (plain strings, no f-string braces).
TOOLCHAIN_PACKAGES = """
## Package ids and signing (batch 1)

The research above recommended the application id `dev.cline.termux`; since 2026-10-08 it is
`io.github.ichouchiang.clinetermux`, while the namespace, the Kotlin packages and the activity
class keep `dev.cline.termux`. The owner's installed app is `io.github.ichouchiang.clinetermux`,
so no test build may carry that id:

- **debug**: `applicationIdSuffix ".debug"`, `versionNameSuffix "-debug"`, launcher label
  "Cline Debug" (`app/src/debug/res/values/strings_debug_app.xml`). It installs beside the
  owner's app; `scripts/install.sh` and `scripts/dev-hub.sh` default to it.
- **release**: `io.github.ichouchiang.clinetermux`, minified, and signed with the project release
  key when the four signing properties are set (`docs/release-signing.md`); without them it falls
  back to the debug keystore, so `assembleRelease` still yields an installable APK for jank checks.
  `-Pcline.releaseIdSuffix=.bench` gives it `io.github.ichouchiang.clinetermux.bench` instead; only that
  variant may be installed on the phone.
- Everything derived from the id follows it: the FileProvider authority is
  `${applicationId}.files` (`ShareFiles.AUTHORITY` = `BuildConfig.APPLICATION_ID + ".files"`),
  and the activity keeps its class name, so a component is always
  `<package>/dev.cline.termux.MainActivity`.
- `scripts/install.sh [--no-launch] [apk]` reads the APK's package with
  `aapt2 dump badging` and refuses exactly `io.github.ichouchiang.clinetermux` ("this would replace the
  owner's app").
"""

TOOLCHAIN_DEV_HUB = """
## Developer Hub override (debug builds only)

A debug build can talk to a Hub that is not the phone's Termux kernel: the workstation's
isolated developer Hub (`scripts/dev-hub.sh start`, port 25498, scratch `CLINE_DIR` under
`/tmp`) reached through `adb reverse tcp:25498 tcp:25498`. The override carries the port as
well as the token, so the debug app dials `ws://127.0.0.1:25498/hub` while the phone's own
Hub keeps 25463 for the owner's app and Termux (25463 is never reversed). Two pieces make
that work, both in `app/src/debug` only:

- `dev.cline.termux.debug.KernelOverrides`: `hubToken(context)` returns the override
  token (sealed with the Keystore `SecretBox` in the `debug` DataStore) or `null`;
  `hubPort(context)` returns the port stored with it, or `null`. `AppGraph` reads the port
  once when the graph is built and uses it for the `KernelCoordinator`, the `/health` probe
  and diagnostics; it wins over the port of the phone's discovery record. The
  `app/src/release` twin has the same signatures and always returns `null`, so a release
  build can only use the token it read from `production.json` through Termux, on 25463.
- `dev.cline.termux.debug.HubTokenOverrideReceiver`: registered by the debug manifest
  for the action `dev.cline.termux.debug.SET_HUB_TOKEN`, `exported="true"` behind
  `android.permission.DUMP`, which only the adb shell holds. `--es token <t>` stores the
  token, the optional `--ei port <1024..65535>` the port (a token without a port drops an
  older port), `--ez clear true` removes both.

`KernelCoordinator` consults `KernelOverrides.hubToken(context)` first: with a token set
and `/health` answering, it skips the Termux checks and the `production.json` read and
connects directly (RUN_COMMAND stays available only when Termux and its permission are
really there, otherwise the app runs in degraded mode: sockets yes, kernel commands no).
Termux commands (global settings, history, the branch chip, worktrees) still run in the
phone's real Termux. The token value is never logged or shown. Both values are read at the
next cold start, so `reverse` and `unreverse` force-stop the app. With the override set but
the workstation Hub down, the app does not switch to the phone's Hub by itself; run
`unreverse` to go back.

Exact commands (`scripts/dev-hub.sh reverse` / `unreverse` run the same ones; `APP_PKG`
overrides the package, never `io.github.ichouchiang.clinetermux`):

```bash
ADB="$HOME/Android/Sdk/platform-tools/adb"
PKG=io.github.ichouchiang.clinetermux.debug
# SERIAL (optional) picks the phone by adb serial; without it adb uses the only attached device
# forward the phone's loopback 25498 to the workstation Hub, push its token and port
"$ADB" ${SERIAL:+-s "$SERIAL"} reverse tcp:25498 tcp:25498
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am broadcast -a dev.cline.termux.debug.SET_HUB_TOKEN \\
    -p "$PKG" --es token "$(scripts/dev-hub.sh token)" --ei port 25498
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am force-stop "$PKG"
# back to the real kernel: clear the override, drop the reverse
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am broadcast -a dev.cline.termux.debug.SET_HUB_TOKEN \\
    -p "$PKG" --ez clear true
"$ADB" ${SERIAL:+-s "$SERIAL"} reverse --remove tcp:25498
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am force-stop "$PKG"
```

The broadcast's result code is 0 on success, 2 when neither `token` nor `clear` was
given or the port is not an int in 1024..65535, 3 when the DataStore write failed. The
override survives app restarts until cleared or the app data is wiped (the sealed blob is
useless off the device: the Keystore key never leaves it). Never run this while a port
release candidate's device steps or the owner's manual test are in progress on the S25U.
"""

write("06-toolchain.md", f"""# Toolchain

{STAMP}
## Research recommendation (verbatim)

{s['toolchain']}

## What the repository actually uses (2026-10-03)

The recommendation above assumed `org.jetbrains.kotlin.android` 2.4.20 with
`android.builtInKotlin=false`. That plugin failed to configure under AGP 9.4.1's new
DSL on this machine, so the scaffold uses AGP's **built-in Kotlin** and pins the Compose
and serialization compiler plugins to **2.2.10** (the Kotlin AGP 9.4.1 bundles). The
recommendation also assumed `compileSdk` 36; the pinned AndroidX/OkHttp artifacts require
**37**, which is installed (`platforms;android-37.0`, `platforms;android-37.2`,
`build-tools;37.0.0`) and set in `app/build.gradle.kts` (`targetSdk` 36). The launcher icon is a
placeholder adaptive icon until the design phase delivers the real one. See the root
`build.gradle.kts` and `CLAUDE.md`.
""" + TOOLCHAIN_PACKAGES + TOOLCHAIN_DEV_HUB)

write("README.md", """# Cline for Android — design docs

| Doc | Content |
| --- | --- |
| [01-architecture.md](01-architecture.md) | The hybrid decision (native Hub client + RUN_COMMAND bridge) and rejected alternatives |
| [02-kernel-contract.md](02-kernel-contract.md) | Prerequisites, manifest, RUN_COMMAND bridge, connect sequence, keep-alive, recovery matrix, batch 1 additions (settings file, tool policies, compaction, contributions, hooks, heartbeat, steer), batch 2 additions (whole-session fork, stop notice, Interrupted, titles, list, home folder, teams switch, full commands) |
| [03-feature-matrix.md](03-feature-matrix.md) | Every desktop feature → kernel API, channel and tier |
| [04-screens.md](04-screens.md) | Screens with their Cline Desktop equivalents and data sources |
| [05-open-questions.md](05-open-questions.md) | Blocking unknowns with experiments and status |
| [06-toolchain.md](06-toolchain.md) | Build stack and where it differs from the recommendation |
| [07-app-architecture.md](07-app-architecture.md) | Kotlin app architecture (written by the design phase) |
| [08-hub-fixtures.md](08-hub-fixtures.md) | Recorded Hub protocol traffic (`app/src/test/resources/hub-fixtures/`): regeneration, scenarios, observed frame shapes and behaviour findings (hand-written, not generated) |
| [09-implementation-report.md](09-implementation-report.md) | What was built, developer loop (dev Hub, fake kernel), acceptance status per spec group, tests, gaps, deviations, owner questions (hand-written) |
| [10-batch1-report.md](10-batch1-report.md) | Batch 1 (2026-10-07): packages and commits, device verification per item, jank figures, deviations, limitations (hand-written) |
| [strings-audit.md](strings-audit.md) | Spec copy → string resource audit (generated by `tools/strings-audit/audit.py --markdown`) |
| research/ | Raw synthesis JSON (readers' dossier included) |

`01`–`06` and this README are rendered by `scripts/gen-docs.py` from the research JSON plus the
status notes kept in that script; edit the script, not these files. `07`–`10` are hand-written.

Design: [../design/design-system.md](../design/design-system.md) (tokens, components, parity
rules) and the seven screen specs in [../design/screens/](../design/screens/) (KS, HC, CA, SS,
SP, CT, CR). Code layout: docs/07 §1; device evidence: `../screenshots/<spec group>/`.
""")
