#!/usr/bin/env bash
# Developer Hub for app testing without the phone's Termux kernel and without
# touching the owner's real ~/.cline or spending LLM credits.
#
#   scripts/dev-hub.sh start     mock provider (25499) + Hub (25498) on an isolated
#                                CLINE_DIR under /tmp, scratch git workspace
#   scripts/dev-hub.sh stop      stop the Hub and the mock
#   scripts/dev-hub.sh status    show Hub/mock liveness and the discovery record
#   scripts/dev-hub.sh token     print the Hub auth token (production.json)
#   scripts/dev-hub.sh reverse   adb reverse the phone's 127.0.0.1:25498 to the Hub, push
#                                the token and port 25498 to the debug app and stop it
#                                (its next start dials the workstation Hub)
#   scripts/dev-hub.sh unreverse clear that override, remove the reverse, stop the app
#                                (its next start uses the phone's own Hub again)
#   scripts/dev-hub.sh helper    run the settings-helper dashboard (8787) against the
#                                isolated home with a fixed room secret, adb reverse 8787,
#                                and cold-start the debug app with that secret
#   scripts/dev-hub.sh unhelper  stop that dashboard and remove the 8787 reverse
#
# The phone's 127.0.0.1:25463 (the real Hub in Termux) is never reversed, so the owner's app
# (io.github.ichouchiang.clinetermux) keeps its real Hub during bench work; only $APP_PKG gets the override.
#
# Environment (all optional):
#   DEVHUB_ROOT      scratch root, default /tmp/cline-dev-hub (must stay under /tmp)
#   CLINE_HUB_PORT   Hub port, default 25498 (never the device default 25463)
#   MOCK_PORT        mock provider port, default 25499
#   SERIAL           adb serial, default $ANDROID_SERIAL, else the only attached device
#   APP_PKG          the app under test, default io.github.ichouchiang.clinetermux.debug (never the owner's
#                    io.github.ichouchiang.clinetermux, nor its legacy dev.cline.termux)
#
# The mock (tools/hub-fixtures/mock-openai.ts) answers any prompt with streamed
# text; a prompt containing "hello-from-mock" first makes a run_commands tool call
# (so an approval appears when auto-approve is off), then answers. "mock-long-output"
# does the same with `seq 1 80` (80 lines of command output, for the terminal view);
# the other mock-* markers are listed at the top of the mock.
set -euo pipefail
cd "$(dirname "$0")/.."

ROOT="${DEVHUB_ROOT:-/tmp/cline-dev-hub}"
case "$ROOT" in /tmp/*) ;; *) echo "DEVHUB_ROOT must live under /tmp" >&2; exit 2 ;; esac
export CLINE_DIR="$ROOT/home"
export CLINE_HUB_PORT="${CLINE_HUB_PORT:-25498}"
MOCK_PORT="${MOCK_PORT:-25499}"
HELPER_PORT=8787
# Fixed only for the developer dashboard; the app draws a random secret unless a debug
# build is started with dev.cline.termux.HELPER_SECRET (KernelOverrides item 3).
HELPER_SECRET="${HELPER_SECRET:-0123456789abcdef0123456789abcdef}"
HELPER_DIR="$ROOT/helper"
WORKSPACE="$ROOT/workspace"
MOCK_DIR="$ROOT/mock"
# adb device: SERIAL, else ANDROID_SERIAL; with neither, adb uses the only attached device.
SERIAL="${SERIAL:-${ANDROID_SERIAL:-}}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="${APP_PKG:-io.github.ichouchiang.clinetermux.debug}"
OWNER_PKG="io.github.ichouchiang.clinetermux"
# The application id until 2026-10-08: an install of it is the owner's app too.
LEGACY_OWNER_PKG="dev.cline.termux"
# The phone-side port of the bench Hub (the debug port override); never 25463.
PHONE_HUB_PORT=25498
PHONE_REAL_HUB_PORT=25463
ACTIVITY="$PKG/dev.cline.termux.MainActivity"
CLI="tools/hub-fixtures/cline-isolated.sh"
DISCOVERY="$CLINE_DIR/data/locks/hub/production.json"

mock_alive() { curl -s --noproxy '*' -m 1 "http://127.0.0.1:$MOCK_PORT/v1/models" >/dev/null 2>&1; }
hub_alive() { curl -s --noproxy '*' -m 1 "http://127.0.0.1:$CLINE_HUB_PORT/health" >/dev/null 2>&1; }

start_mock() {
	if mock_alive; then echo "mock already up on $MOCK_PORT"; return; fi
	mkdir -p "$MOCK_DIR"
	nohup bun tools/hub-fixtures/mock-openai.ts --port "$MOCK_PORT" --log "$MOCK_DIR/requests.jsonl" > "$MOCK_DIR/mock.out" 2>&1 &
	for _ in $(seq 1 40); do mock_alive && break; sleep 0.25; done
	mock_alive || { echo "mock did not come up; see $MOCK_DIR/mock.out" >&2; exit 1; }
	echo "mock up on 127.0.0.1:$MOCK_PORT"
}

prepare_home() {
	mkdir -p "$CLINE_DIR" "$WORKSPACE"
	if [ ! -d "$WORKSPACE/.git" ]; then
		printf '# Dev workspace\n\nScratch repository for the Cline for Android developer Hub.\n' > "$WORKSPACE/README.md"
		printf 'console.log("hello from the dev workspace");\n' > "$WORKSPACE/hello.js"
		printf 'fun main() = println("hello")\n' > "$WORKSPACE/Main.kt"
		printf 'a\nb\nc\n' > "$WORKSPACE/notes.txt"
		git -C "$WORKSPACE" init -q
		git -C "$WORKSPACE" -c user.name=dev -c user.email=dev@example.invalid add -A
		git -C "$WORKSPACE" -c user.name=dev -c user.email=dev@example.invalid commit -q -m "scratch workspace"
	fi
	if [ ! -f "$CLINE_DIR/data/settings/providers.json" ]; then
		"$CLI" auth --provider openai-compatible --apikey mock --modelid mock-1 --baseurl "http://127.0.0.1:$MOCK_PORT/v1"
	fi
}

cmd_start() {
	start_mock
	prepare_home
	if hub_alive; then echo "Hub already up on $CLINE_HUB_PORT"; else "$CLI" hub start; fi
	"$CLI" hub status
	echo "workspace: $WORKSPACE"
	echo "token:     $(cmd_token)"
}

cmd_stop() {
	"$CLI" hub stop >/dev/null 2>&1 || true
	pkill -f "mock-openai.ts --port $MOCK_PORT" 2>/dev/null || true
	for _ in $(seq 1 20); do hub_alive || break; sleep 0.5; done
	if pgrep -f "entry\.js .*--port $CLINE_HUB_PORT( |$)" >/dev/null 2>&1; then
		echo "WARNING: a Hub daemon on port $CLINE_HUB_PORT is still running:" >&2
		pgrep -fa "entry\.js .*--port $CLINE_HUB_PORT( |$)" >&2 || true
		exit 1
	fi
	echo "stopped"
}

cmd_status() {
	if mock_alive; then echo "mock:  up (127.0.0.1:$MOCK_PORT)"; else echo "mock:  down"; fi
	if hub_alive; then
		echo "hub:   up (127.0.0.1:$CLINE_HUB_PORT)"
		curl -s --noproxy '*' -m 2 "http://127.0.0.1:$CLINE_HUB_PORT/version"; echo
	else
		echo "hub:   down"
	fi
	[ -f "$DISCOVERY" ] && echo "discovery: $DISCOVERY" || echo "discovery: (none)"
	if command -v "$ADB" >/dev/null 2>&1 && "$ADB" ${SERIAL:+-s "$SERIAL"} get-state >/dev/null 2>&1; then
		echo "adb reverse: $("$ADB" ${SERIAL:+-s "$SERIAL"} reverse --list | tr '\n' ' ')"
	fi
}

cmd_token() {
	[ -f "$DISCOVERY" ] || { echo "no discovery record at $DISCOVERY (is the Hub running?)" >&2; exit 1; }
	python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["authToken"])' "$DISCOVERY"
}

require_test_pkg() {
	if [ "$PKG" = "$OWNER_PKG" ] || [ "$PKG" = "$LEGACY_OWNER_PKG" ]; then
		echo "APP_PKG=$PKG is the owner's app; the bench only ever targets the debug build" >&2
		exit 2
	fi
}

# Older versions of this script reversed the phone's 25463 itself, which hijacked the real Hub
# for every app on the phone; drop such a leftover.
drop_legacy_reverse() {
	if "$ADB" ${SERIAL:+-s "$SERIAL"} reverse --list 2>/dev/null | grep -q "tcp:$PHONE_REAL_HUB_PORT "; then
		"$ADB" ${SERIAL:+-s "$SERIAL"} reverse --remove "tcp:$PHONE_REAL_HUB_PORT" 2>/dev/null || true
		echo "removed a leftover adb reverse of the phone's $PHONE_REAL_HUB_PORT"
	fi
}

# Debug builds register dev.cline.termux.debug.SET_HUB_TOKEN (see docs/06-toolchain.md,
# "Developer Hub override"); release builds ignore it. Prints only the result code.
override_broadcast() {
	local out code
	out="$("$ADB" ${SERIAL:+-s "$SERIAL"} shell am broadcast -a dev.cline.termux.debug.SET_HUB_TOKEN -p "$PKG" "$@" 2>&1)" || true
	code="$(printf '%s\n' "$out" | sed -n 's/.*Broadcast completed: result=\(-\{0,1\}[0-9]*\).*/\1/p' | tail -n 1)"
	[ "$code" = 0 ] || { echo "$PKG did not take the override (result=${code:-none})" >&2; return 1; }
}

cmd_reverse() {
	require_test_pkg
	hub_alive || { echo "start the Hub first" >&2; exit 1; }
	"$ADB" ${SERIAL:+-s "$SERIAL"} shell pm path "$PKG" 2>/dev/null | grep -q '^package:' ||
		{ echo "$PKG is not installed on the phone (scripts/install.sh)" >&2; exit 1; }
	drop_legacy_reverse
	"$ADB" ${SERIAL:+-s "$SERIAL"} reverse "tcp:$PHONE_HUB_PORT" "tcp:$CLINE_HUB_PORT"
	token="$(cmd_token)"
	override_broadcast --es token "$token" --ei port "$PHONE_HUB_PORT"
	# The override is read once at the next cold start.
	"$ADB" ${SERIAL:+-s "$SERIAL"} shell am force-stop "$PKG"
	echo "phone 127.0.0.1:$PHONE_HUB_PORT -> workstation Hub :$CLINE_HUB_PORT; token and port pushed to $PKG (stopped; start it again)"
}

cmd_unreverse() {
	require_test_pkg
	override_broadcast --ez clear true || true
	"$ADB" ${SERIAL:+-s "$SERIAL"} reverse --remove "tcp:$PHONE_HUB_PORT" 2>/dev/null || true
	drop_legacy_reverse
	"$ADB" ${SERIAL:+-s "$SERIAL"} shell am force-stop "$PKG" 2>/dev/null || true
	echo "Hub override of $PKG cleared (token and port); $PHONE_HUB_PORT reverse removed; app stopped"
}

helper_alive() {
	curl -s --noproxy '*' -m 1 -o /dev/null -w '%{http_code}' -H "Host: 127.0.0.1:$HELPER_PORT" -H "Origin: http://127.0.0.1:$HELPER_PORT" \
		"http://127.0.0.1:$HELPER_PORT/browser?roomSecret=$HELPER_SECRET" 2>/dev/null | grep -q 400
}

cmd_helper() {
	require_test_pkg
	hub_alive || { echo "start the Hub first" >&2; exit 1; }
	if helper_alive; then
		echo "helper dashboard already up on $HELPER_PORT"
	else
		mkdir -p "$HELPER_DIR"
		nohup "$CLI" dashboard --no-open --host 127.0.0.1 --port "$HELPER_PORT" --room-secret "$HELPER_SECRET" > "$HELPER_DIR/dashboard.out" 2>&1 &
		for _ in $(seq 1 120); do helper_alive && break; sleep 0.25; done
		helper_alive || { echo "dashboard did not come up; see $HELPER_DIR/dashboard.out" >&2; exit 1; }
		echo "helper dashboard up on 127.0.0.1:$HELPER_PORT"
	fi
	"$ADB" ${SERIAL:+-s "$SERIAL"} reverse tcp:$HELPER_PORT tcp:$HELPER_PORT
	"$ADB" ${SERIAL:+-s "$SERIAL"} shell am start -S -n "$ACTIVITY" --es dev.cline.termux.HELPER_SECRET "$HELPER_SECRET" >/dev/null
	echo "phone 127.0.0.1:$HELPER_PORT -> workstation dashboard; $PKG restarted with the developer secret"
}

cmd_unhelper() {
	pkill -f "dashboard --no-open --host 127.0.0.1 --port $HELPER_PORT --room-secret $HELPER_SECRET" 2>/dev/null || true
	"$ADB" ${SERIAL:+-s "$SERIAL"} reverse --remove tcp:$HELPER_PORT 2>/dev/null || true
	echo "helper dashboard stopped; 8787 reverse removed"
}

case "${1:-}" in
	start) cmd_start ;;
	stop) cmd_stop ;;
	status) cmd_status ;;
	token) cmd_token ;;
	reverse) cmd_reverse ;;
	unreverse) cmd_unreverse ;;
	helper) cmd_helper ;;
	unhelper) cmd_unhelper ;;
	*) sed -n '2,35p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
