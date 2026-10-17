#!/usr/bin/env bash
# Regenerate the Hub protocol fixtures from a clean, isolated kernel home.
#
#   tools/hub-fixtures/regenerate.sh
#
# Environment (all optional):
#   HUBFIX_SCRATCH   scratch root, default ${TMPDIR:-/tmp}/cline-hub-fixtures
#   CLINE_DIR        isolated kernel home, default $HUBFIX_SCRATCH/home (must be under /tmp)
#   CLINE_HUB_PORT   fixture Hub port, default 25498 (never the device default 25463)
#   MOCK_PORT        mock provider port, default 25499
#   CLINE_FORK_ROOT  checkout of the Cline fork, default ~/workspace/cline-termux-v3-phone
#
# What it does: stops a Hub left over on that home, wipes the home's data/, starts
# the mock provider, configures it as the openai-compatible provider, starts the
# Hub, runs record.ts, copies the sanitized mock request log next to the
# fixtures, stops the Hub and kills the mock. Nothing touches ~/.cline.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_ROOT="$(cd "$HERE/../.." && pwd)"
FIXTURES="$APP_ROOT/app/src/test/resources/hub-fixtures"
SCRATCH="${HUBFIX_SCRATCH:-${TMPDIR:-/tmp}/cline-hub-fixtures}"
export CLINE_DIR="${CLINE_DIR:-$SCRATCH/home}"
export CLINE_HUB_PORT="${CLINE_HUB_PORT:-25498}"
MOCK_PORT="${MOCK_PORT:-25499}"
WORKSPACE="$SCRATCH/workspace"
MOCK_DIR="$SCRATCH/mock"
CLI="$HERE/cline-isolated.sh"

case "$CLINE_DIR" in
	/tmp/*) ;;
	*) echo "refusing to wipe CLINE_DIR=$CLINE_DIR: it must live under /tmp" >&2; exit 2 ;;
esac

log() { printf '\n[regenerate] %s\n' "$*"; }

log "stopping any Hub left on $CLINE_DIR (port $CLINE_HUB_PORT)"
"$CLI" hub stop >/dev/null 2>&1 || true
pkill -f "mock-openai.ts --port $MOCK_PORT" 2>/dev/null || true

log "resetting the isolated home and workspace"
rm -rf "$CLINE_DIR/data"
mkdir -p "$CLINE_DIR" "$WORKSPACE" "$MOCK_DIR" "$FIXTURES"
printf 'hello from the fixture workspace\n' > "$WORKSPACE/README.md"
printf 'console.log("fixture");\n' > "$WORKSPACE/hello.js"
rm -f "$MOCK_DIR/requests.jsonl"

log "starting the mock provider on 127.0.0.1:$MOCK_PORT"
bun "$HERE/mock-openai.ts" --port "$MOCK_PORT" --log "$MOCK_DIR/requests.jsonl" > "$MOCK_DIR/mock.out" 2>&1 &
MOCK_PID=$!
cleanup() {
	"$CLI" hub stop >/dev/null 2>&1 || true
	kill "$MOCK_PID" 2>/dev/null || true
}
trap cleanup EXIT
for _ in $(seq 1 40); do
	if curl -s --noproxy '*' "http://127.0.0.1:$MOCK_PORT/v1/models" >/dev/null 2>&1; then break; fi
	sleep 0.25
done
curl -s --noproxy '*' "http://127.0.0.1:$MOCK_PORT/v1/models" >/dev/null

log "configuring the mock as the openai-compatible provider"
"$CLI" auth --provider openai-compatible --apikey mock --modelid mock-1 --baseurl "http://127.0.0.1:$MOCK_PORT/v1"

log "starting the Hub"
"$CLI" hub start
"$CLI" hub status

log "recording"
bun "$HERE/record.ts" --workspace "$WORKSPACE" --out "$FIXTURES"

log "copying the sanitized mock request log"
bun "$HERE/sanitize-mock-log.ts" "$MOCK_DIR/requests.jsonl" "$FIXTURES/mock-requests.jsonl"

log "stopping the Hub and the mock"
"$CLI" hub stop || true
kill "$MOCK_PID" 2>/dev/null || true
trap - EXIT
sleep 1
if pgrep -f "entry\.js .*--port $CLINE_HUB_PORT( |$)" >/dev/null 2>&1; then
	echo "[regenerate] WARNING: a Hub daemon on port $CLINE_HUB_PORT is still running:" >&2
	pgrep -fa "entry\.js .*--port $CLINE_HUB_PORT( |$)" >&2 || true
fi
log "done: $(ls "$FIXTURES" | wc -l) files in $FIXTURES"
