# Open questions and blocking unknowns

_Generated on 2026-10-03 from `docs/research/kernel-and-desktop-synthesis.json` (multi-agent code reading of the Cline monorepo at cli-v3.0.68 + the Termux port). Line references point into `~/workspace/cline-termux-v3-phone`._

Status column maintained by hand after each experiment.

## 1. Hub WebSocket subprotocol echo on the device

Hub WebSocket subprotocol echo on the device: the Hub server runs under the device Bun's built-in `ws` replacement (the port keeps `ws` external), and OkHttp fails a handshake whose 101 does not echo `cline-hub-auth.<token>`. The CLI's own client (also Bun's native WebSocket) connects, which suggests the echo works, but it is unproven for OkHttp. Experiment (5 min, S25U via adb/ssh, outside release windows): `cline hub start`, then an OkHttp or `curl -i -H 'Sec-WebSocket-Protocol: cline-hub-auth.<token>' --http1.1 -H 'Upgrade: websocket' -H 'Connection: Upgrade' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Sec-WebSocket-Version: 13' http://127.0.0.1:25463/hub` and check the response header. Fallback if it fails: Origin: http://localhost without a subprotocol (reduced authority).

Status: **Confirmed on the workstation Hub (real `ws` server) 2026-10-03, see 08-hub-fixtures.md:** the 101 echoes `Sec-WebSocket-Protocol: cline-hub-auth.<token>`; wrong/missing token → 401; `Origin: http://localhost` without token → 101 with no subprotocol header. Still to confirm on the device Bun (bun-android-ffi's built-in ws server) once `v3.0.68-termux.2` is installed — the app's kernel probe screen can do it in one tap.

## 2. The Hub only starts from v3.0.68-termux.2 (commits a350d0d74 entry.js, 54388558c external ws, 1ebf0a433) which is committed locally, unpushed and unreleased; the installed stable .1 cannot run the Hub at all. The app's Tier 1 cannot be tested on the phone until that candidate is installed on the S25U (plan item 2

The Hub only starts from v3.0.68-termux.2 (commits a350d0d74 entry.js, 54388558c external ws, 1ebf0a433) which is committed locally, unpushed and unreleased; the installed stable .1 cannot run the Hub at all. The app's Tier 1 cannot be tested on the phone until that candidate is installed on the S25U (plan item 2: push, `manage.sh candidate cli-v3.0.68 --revision 2`, manual test). Not a code unknown, but the first gate.

Status: **Gate.** `v3.0.68-termux.2` is pushed and inspected but the candidate build has not been started (owner decision pending). Until it is installed on the S25U, Tier 1 can only be tested against the workstation Hub (Bun 1.4.2 x64).

## 3. Bun.serve WebSocket upgrades on bun-android-ffi 1.4.0-canary.1 are unverified; this decides whether the shipped `cline dashboard` server can be the settings helper. Experiment

Bun.serve WebSocket upgrades on bun-android-ffi 1.4.0-canary.1 are unverified; this decides whether the shipped `cline dashboard` server can be the settings helper. Experiment: on the S25U run `cline dashboard --no-open --host 127.0.0.1 --port 8787`, `curl -s 127.0.0.1:8787/health`, then a WebSocket handshake to /browser with `Origin: http://127.0.0.1:8787`. If it fails, Providers/Account/MCP/Marketplace use the RUN_COMMAND-only fallback (`cline auth`, bundled model lists, no account tab).

## 4. Background RUN_COMMAND environment for the launcher

Background RUN_COMMAND environment for the launcher: $PREFIX/bin/cline execs `node cline-node-wrapper.cjs` when ca-certs.cjs is present (install-cline-termux.sh:478-519), so `node` must be on the APP_SHELL PATH, LD_PRELOAD (termux-exec) and HOME must be set as in a login shell, and `cline hub start` must detach correctly from a RunCommandService task. Experiment via adb (the owner allowed adb): `adb shell am startservice --user 0 -n com.termux/com.termux.app.RunCommandService -a com.termux.RUN_COMMAND --es com.termux.RUN_COMMAND_PATH /data/data/com.termux/files/usr/bin/cline --esa com.termux.RUN_COMMAND_ARGUMENTS hub,status --ez com.termux.RUN_COMMAND_BACKGROUND true`, then check ~/.cline/data/logs for the result (or run the app's own spike with the PendingIntent receiver).

Status: **Resolved 2026-10-03 (on the S25U, from the app's own RunCommandService bridge).** The background (APP_SHELL) runner works for the launcher: `$PREFIX/bin/cline --version` → exit 0, `3.0.68`, 2.5 s; `cline hub status` → exit 0 in 1.5 s; `env` shows `PATH=$PREFIX/bin`, `PWD=$HOME`, `TERMUX__PREFIX`, `TMPDIR`, `LANG=en_US.UTF-8` (a login-like environment). Results arrive through the mutable one-shot PendingIntent with `result` bundle keys `stdout/stderr/exitCode/err/errmsg`; a non-existent executable comes back as `err=150` with an `errmsg` (exitCode -1) in ~20 ms; `false` → `exitCode=1, err=-1`. `startForegroundService` from our side is fine (RunCommandService calls startForeground itself). `termux-wake-lock` acquires `PARTIAL_WAKE_LOCK 'termux:service-wakelock'` (verified in `dumpsys power`) in 0.3 s.

## 5. Browser opening from the kernel on Android for OAuth flows

Browser opening from the kernel on Android for OAuth flows: `cline auth cline` (apps/cli/src/utils/open.ts -> the `open` package) and the dashboard's openExternalUrl (spawns xdg-open) both depend on termux-tools' xdg-open/termux-open working from a background shell. Experiment: RUN_COMMAND `xdg-open https://example.com` in background mode. If it fails, run the sign-in command with the foreground (terminal) runner so the user sees URL and code in Termux; the WorkOS verification_uri_complete carries the code.

Status: **Resolved 2026-10-03 (on the S25U).** `xdg-open https://example.com` from the background runner exits 0 after 2.9 s but opens nothing (the top activity stays the app), and a foreground-session command (`BACKGROUND=false`, `SESSION_ACTION=0`) runs but does not bring Termux to the front either: both are background activity starts that Android 10+ blocks. Design consequence: for `cline auth cline` the app launches the command in a Termux terminal session AND itself starts Termux's main activity right after (allowed: the app is in the foreground), so the user sees the URL/device code in Termux and Termux, now foreground, can open the browser. The app never needs the browser opened from a background shell. (`appops get com.termux SYSTEM_ALERT_WINDOW` showed `default; rejectTime=+2m46s` right after the probe — the rejected background start. Granting Termux 'Display over other apps' would lift the block; the kernel-setup checklist can mention it as optional, not required.)

## 6. End-to-end resume of a persisted session after a daemon restart over the Hub

End-to-end resume of a persisted session after a daemon restart over the Hub: session.create with sessionConfig.sessionId=<old> + initialMessages=<session.messages result> takes the isReadOnlyResumeStart path (local-runtime-host.ts:409-414 verified), but whether usage, checkpoint history and compaction state come back, and whether run.start then continues the conversation correctly, was not traced. Experiment on the workstation (no device): `cline hub start`, create a session with the CLI, `cline hub stop && cline hub start`, replay the sequence with a scripted ws client against Bun 1.4.2.

Status: **Resolved 2026-10-03 (recorded, fixture 05):** after `hub stop`/`hub start`, `session.create {sessionConfig.sessionId:<old>, initialMessages:<session.messages>}` returns the same sessionId, usage recomputed from message `metrics`, metadata merged (title/pinned kept); the next `session.send_input` runs a normal turn with the full history re-seeded. Checkpoint history does not come back — see the checkpoint correction in 03-feature-matrix.md.

## 7. Per-tool approval policy keys

Per-tool approval policy keys: toolPolicies['*'] is enough for the auto-approve toggle, but per-tool rules (desktop 'Off = safe tools auto-approved, others prompt') need the headless tool names resolveToolPolicy matches (read_files? run_commands? editor?) — read sdk/packages/agents/src/agent-runtime.ts:2417-2432 resolveToolPolicy and sdk/packages/core/src/extensions/tools/definitions.ts tool names before implementing the per-tool matrix.

Status: **Resolved 2026-10-03 (code read).** `resolveToolPolicy` (sdk/packages/agents/src/agent-runtime.ts:216) merges `policies['*']` then `policies[toolName]`, so per-tool rules key on the built-in tool names: `read_files`, `search_codebase`, `run_commands`, `editor`, `apply_patch`, `fetch_web_content`, `ask_question`, `skills`, `submit_and_exit` (definitions.ts). Desktop's 'safe tools auto-approved' = `{'*': {autoApprove: false}, read_files: {autoApprove: true}, search_codebase: {autoApprove: true}, fetch_web_content: {autoApprove: true}}` at session.create.

## 8. Streaming shape of tool.updated for run_commands (incremental stdout chunks vs cumulative, ANSI preserved?) and for editor/apply_patch (diff hunks in output?) — read sdk/packages/core/src/extensions/tools/executors/bash.ts and the editor/apply_patch executors plus sdk/packages/shared/src/agents/types.ts before building the terminal and diff renderers (Tier 3).

Streaming shape of tool.updated for run_commands (incremental stdout chunks vs cumulative, ANSI preserved?) and for editor/apply_patch (diff hunks in output?) — read sdk/packages/core/src/extensions/tools/executors/bash.ts and the editor/apply_patch executors plus sdk/packages/shared/src/agents/types.ts before building the terminal and diff renderers (Tier 3).

Status: **Resolved 2026-10-03 (recorded, fixture 02/03):** `tool.updated.update` for `run_commands` is `{stream: stdout|stderr, chunk (incremental, append per executionId+stream), executionId, detachable, commandIndex, query}`; the first update has an empty chunk; coalesced every 48 ms; `truncated:true, droppedChars` when output is dropped; ANSI passes through. `tool.finished.output` is `[{query, result, success}]`; a rejected call still emits started/finished with `output.error`. `editor`/`apply_patch` payloads were not recorded (no file tools in the mock run).

## 9. Whether the daemon registers a `skills` executor for Hub-hosted sessions (createDefaultExecutors has none; definitions.ts

Whether the daemon registers a `skills` executor for Hub-hosted sessions (createDefaultExecutors has none; definitions.ts:964 gates the skills tool on executors.skills) and therefore whether /skill prompts pass through or must be inlined by the app from the skill file (RUN_COMMAND cat). Read sdk/packages/core/src/hub/server/hub-client-contributions.ts createDefaultToolExecutors paths and local-runtime-host session build.

Status: **Mostly resolved 2026-10-03 (code read).** The default file-based user-instruction service implements `createSkillsExecutor` (core/src/extensions/config/user-instruction-service.ts:205,324) and the aggregate at :71 feeds `executors.skills`, which definitions.ts:964 gates on — so Hub sessions created without `configExtensions` should expose the `skills` tool and expand `/skill` commands daemon-side (`resolveRuntimeSlashCommand`). Confirm once with a `/skill` prompt against the workstation Hub before relying on it; keep the `cat <skill path>` inline fallback otherwise. Recorded traffic (08-hub-fixtures.md) could not settle it: the `skills` tool is only advertised when at least one skill is configured, and the fixture home had none.

## 10. Re-registration with the same clientId from a new connection while the Hub still holds the stale registration (half-open socket before the 30-60 s heartbeat sweep)

Re-registration with the same clientId from a new connection while the Hub still holds the stale registration (half-open socket before the 30-60 s heartbeat sweep): client-handlers.ts handleClientRegister was not read to the end; NodeHubClient reuses its clientId, which suggests replacement is tolerated. Read handleClientRegister or, if rejected (client_already_registered), mint a fresh clientId per connection and re-attach sessions.

Status: **Resolved with a caveat 2026-10-03 (recorded, fixture 04):** re-registering the same clientId after the old socket closed is accepted; re-registering while the old socket is still open is accepted too, but when the old socket is later reaped the Hub unregisters that clientId and the new connection loses its record. Rule: close the old socket (and wait for the close callback) before opening the replacement; a half-open socket the Hub has not yet swept (30 s) is the trap.

## 11. Phantom-process and battery state on the S25U (settings_enable_monitor_phantom_procs, Termux battery exemption, Developer options 'Disable child process restrictions')

Phantom-process and battery state on the S25U (settings_enable_monitor_phantom_procs, Termux battery exemption, Developer options 'Disable child process restrictions'): checkable now with `adb shell settings get global settings_enable_monitor_phantom_procs` and `adb shell dumpsys deviceidle whitelist | grep termux`; affects reliability of long tool runs, not the design.

Status: **Checked 2026-10-03 (read-only, adb + ssh):** `settings_enable_monitor_phantom_procs` is unset (`null` = platform default, i.e. the phantom-process monitor is ON; the developer option has not been flipped); `dumpsys deviceidle whitelist` lists `com.termux`, `com.termux.api` and the owner's `dev.cline.voice`, not yet `io.github.ichouchiang.clinetermux`; Termux 0.118.3; `allow-external-apps=true`; `termux-wake-lock`, `xdg-open` and `termux-open` are installed; no Hub discovery record exists yet (the installed port is still .1).

## 12. Owner decision still open from CLAUDE.local.md plan item 2

Owner decision still open from CLAUDE.local.md plan item 2: whether a Hub left running in the background (no idle exit, ~300 MB RSS) is acceptable; it sets the default of the app's 'keep kernel running after exit' switch and whether the app should auto-stop the Hub when the last session ends.

## 13. Android build bootstrap

Android build bootstrap: ~/workspace/cline-termux-app has no Gradle wrapper (no gradlew, no gradle/), ~/.gradle/wrapper/dists holds only 8.11.1/8.14.3/9.2.0, and AGP 9.4.1 needs Gradle >= 9.6.0; generate the wrapper first (run the cached gradle-9.2.0 binary with `wrapper --gradle-version 9.6.0` through scripts/build.sh's proxy flags, or download the 9.6.0 distribution) before any build.

Status: **Resolved 2026-10-03.** Wrapper generated from the Gradle 9.6.0 distribution; `./gradlew` works. The first build then showed that the Compose 1.12.1 / navigation 2.10.2 / lifecycle 2.11.0 / OkHttp 5.5.0 AARs require `compileSdk` 37, so SDK platforms `android-37.0` and `android-37.2` plus build-tools 37.0.0 were installed and the app compiles against 37 (`targetSdk` stays 36).
