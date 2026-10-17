#!/usr/bin/env bash
# Bench PreToolUse hook for the hook checks (batch-1 brief §8.5, finding F1: the Hub runs the
# user's hook files itself, for the app's sessions too). Works only on the bench's isolated
# CLINE_DIR under /tmp: never the owner's ~/.cline, never the phone.
#
#   scripts/bench/hooks.sh on    install the global hook $CLINE_DIR/hooks/PreToolUse.sh
#   scripts/bench/hooks.sh off   remove it (the call log is kept)
#
# The hook appends each PreToolUse payload it gets on stdin, as one line, to
# $DEVHUB_ROOT/hooks/calls.jsonl, then answers by the mock's tool-call id
# (tools/hub-fixtures/mock-openai.ts, markers mock-hook-block / mock-hook-context):
#   call_mock_hook_block_*    {"cancel":true,"errorMessage":"blocked by bench hook"}
#   call_mock_hook_context_*  {"contextModification":"bench hook context"}
#   any other call            no output: the call goes ahead
#
# A cancel stops the whole run in core 0.0.91 (run.aborted, no tool event), and its errorMessage
# reaches no client, so "blocked by bench hook" shows nowhere; calls.jsonl and the absent tool
# result are the evidence.
#
# The Hub spawns a hook in the session's cwd. A session the app creates on the bench has a phone
# path as its cwd, which the workstation lacks (posix_spawn 'bash' ENOENT, the tool goes ahead),
# so hook checks use a session seeded with the isolated CLI in the bench workspace and then
# driven from the app, for example:
#   CLINE_DIR=/tmp/cline-dev-hub/home tools/hub-fixtures/cline-isolated.sh \
#     --cwd /tmp/cline-dev-hub/workspace "[test] seed"
#
# Environment: DEVHUB_ROOT, default /tmp/cline-dev-hub (must stay under /tmp), the same root
# scripts/dev-hub.sh uses, so CLINE_DIR is $DEVHUB_ROOT/home. The Hub reads hook files per
# tool call, so no restart is needed.
set -euo pipefail

ROOT="${DEVHUB_ROOT:-/tmp/cline-dev-hub}"
case "$ROOT" in /tmp/*) ;; *) echo "DEVHUB_ROOT must live under /tmp" >&2; exit 2 ;; esac
HOOK_DIR="$ROOT/home/hooks"
HOOK="$HOOK_DIR/PreToolUse.sh"
LOG="$ROOT/hooks/calls.jsonl"

install_hook() {
	mkdir -p "$HOOK_DIR" "$(dirname "$LOG")"
	{
		echo '#!/usr/bin/env bash'
		echo '# Installed by scripts/bench/hooks.sh on; remove with "scripts/bench/hooks.sh off".'
		echo 'payload="$(cat)"'
		echo "printf '%s\\n' \"\$(printf '%s' \"\$payload\" | tr -d '\\n')\" >> '$LOG'"
		echo 'case "$payload" in'
		echo "*call_mock_hook_block_*) echo '{\"cancel\":true,\"errorMessage\":\"blocked by bench hook\"}' ;;"
		echo "*call_mock_hook_context_*) echo '{\"contextModification\":\"bench hook context\"}' ;;"
		echo 'esac'
	} > "$HOOK"
	chmod +x "$HOOK"
	echo "PreToolUse hook installed: $HOOK (calls: $LOG)"
}

case "${1:-}" in
on) install_hook ;;
off)
	rm -f "$HOOK"
	echo "PreToolUse hook removed: $HOOK"
	;;
*)
	echo "usage: $0 on|off" >&2
	exit 2
	;;
esac
