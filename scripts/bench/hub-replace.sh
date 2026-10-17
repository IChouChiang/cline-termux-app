#!/usr/bin/env bash
# Bench: drain or replace the developer Hub the way a port install retires the old one
# (decision G5: the app must not stay "Draining" after a Hub replacement).
#
#   scripts/bench/hub-replace.sh drain     hub.drain with reason "retired by newer install"
#   scripts/bench/hub-replace.sh undrain   lift the drain (hub.drain --off)
#   scripts/bench/hub-replace.sh replace   drain, kill -9 the daemon named by the discovery
#                                          record (the forced exit after the drain), start a
#                                          new Hub, wait for /health, push its token (and the
#                                          bench port) to the debug app
#   scripts/bench/hub-replace.sh status    /health of the bench Hub (draining flag included)
#
# Runs against the Hub of scripts/dev-hub.sh (start it first) and uses its environment:
#   DEVHUB_ROOT      scratch root, default /tmp/cline-dev-hub (must stay under /tmp)
#   CLINE_HUB_PORT   bench Hub port, default 25498; the phone's real 25463 is refused
#   SERIAL, ADB      adb device and binary (the token push)
#   APP_PKG          the app that takes the push, default io.github.ichouchiang.clinetermux.debug
#   PHONE_PORT       the port the app reaches the bench on (adb reverse), default 25498
#   PUSH=0           skip the token push (workstation-only runs)
# The token is never printed.
set -euo pipefail
cd "$(dirname "$0")/../.."

ROOT="${DEVHUB_ROOT:-/tmp/cline-dev-hub}"
case "$ROOT" in /tmp/*) ;; *) echo "DEVHUB_ROOT must live under /tmp" >&2; exit 2 ;; esac
export CLINE_DIR="$ROOT/home"
export CLINE_HUB_PORT="${CLINE_HUB_PORT:-25498}"
if [ "$CLINE_HUB_PORT" = "25463" ]; then
	echo "refusing port 25463: that is the phone's real Hub port" >&2
	exit 2
fi
# adb device: SERIAL, else ANDROID_SERIAL; with neither, adb uses the only attached device.
SERIAL="${SERIAL:-${ANDROID_SERIAL:-}}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="${APP_PKG:-io.github.ichouchiang.clinetermux.debug}"
PHONE_PORT="${PHONE_PORT:-25498}"
CLI="tools/hub-fixtures/cline-isolated.sh"
DISCOVERY="$CLINE_DIR/data/locks/hub/production.json"
REASON="retired by newer install"

hub_health() { curl -s --noproxy '*' -m 1 "http://127.0.0.1:$CLINE_HUB_PORT/health" 2>/dev/null || true; }
hub_alive() { [ -n "$(hub_health)" ]; }
discovery_field() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))[sys.argv[2]])' "$DISCOVERY" "$1"; }

require_hub() {
	hub_alive || { echo "no bench Hub on 127.0.0.1:$CLINE_HUB_PORT (scripts/dev-hub.sh start)" >&2; exit 1; }
}

cmd_drain() {
	require_hub
	"$CLI" hub drain --reason "$REASON"
}

cmd_undrain() {
	require_hub
	"$CLI" hub drain --off
}

cmd_status() {
	if hub_alive; then hub_health; echo; else echo "hub: down"; fi
}

# The daemon the discovery record names, only when it really is this port's Hub daemon.
bench_daemon_pid() {
	[ -f "$DISCOVERY" ] || { echo "no discovery record at $DISCOVERY" >&2; return 1; }
	local pid args
	pid="$(discovery_field pid)"
	case "$pid" in '' | *[!0-9]*) echo "discovery record has no pid" >&2; return 1 ;; esac
	args="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)"
	if ! printf '%s' "$args" | grep -Eq "entry\.(js|ts) .*--port $CLINE_HUB_PORT( |$)"; then
		echo "pid $pid is not the bench Hub daemon on port $CLINE_HUB_PORT; not killing it" >&2
		return 1
	fi
	echo "$pid"
}

push_token() {
	if [ "${PUSH:-1}" = "0" ]; then echo "token push skipped (PUSH=0)"; return; fi
	if ! "$ADB" ${SERIAL:+-s "$SERIAL"} get-state >/dev/null 2>&1; then
		echo "adb device ${SERIAL:-(default)} not reachable; token not pushed" >&2
		return 1
	fi
	local token
	token="$(discovery_field authToken)"
	# Debug builds only (HubTokenOverrideReceiver); the port extra is the bench port override.
	"$ADB" ${SERIAL:+-s "$SERIAL"} shell am broadcast -a dev.cline.termux.debug.SET_HUB_TOKEN -p "$PKG" \
		--es token "$token" --ei port "$PHONE_PORT" >/dev/null
	echo "new token and port $PHONE_PORT pushed to $PKG"
}

cmd_replace() {
	require_hub
	local old_pid old_started new_pid
	old_pid="$(bench_daemon_pid)"
	old_started="$(discovery_field startedAt)"
	"$CLI" hub drain --reason "$REASON"
	# The forced exit that follows the drain on a port install: no graceful stop, the
	# discovery record stays behind like after an OS kill.
	kill -9 "$old_pid"
	for _ in $(seq 1 40); do hub_alive || break; sleep 0.25; done
	if hub_alive; then echo "the old Hub still answers /health after kill -9" >&2; exit 1; fi
	echo "old Hub (pid $old_pid) killed after its drain"
	"$CLI" hub start
	for _ in $(seq 1 120); do hub_alive && break; sleep 0.25; done
	hub_alive || { echo "the new Hub did not answer /health" >&2; exit 1; }
	new_pid="$(discovery_field pid)"
	if [ "$new_pid" = "$old_pid" ] || [ "$(discovery_field startedAt)" = "$old_started" ]; then
		echo "the discovery record still names the old Hub" >&2
		exit 1
	fi
	echo "new Hub up (pid $new_pid): $(hub_health)"
	push_token
}

case "${1:-}" in
	drain) cmd_drain ;;
	undrain) cmd_undrain ;;
	replace) cmd_replace ;;
	status) cmd_status ;;
	*) sed -n '2,23p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
