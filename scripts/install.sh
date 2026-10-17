#!/usr/bin/env bash
# Install an APK on the phone (the debug build by default) and, unless --no-launch, start it.
#   scripts/install.sh [--no-launch] [path/to/app.apk]
# The owner's own app is io.github.ichouchiang.clinetermux (and the legacy dev.cline.termux): this script never
# installs an APK with either package id (read from the APK with aapt2), so it cannot replace the owner's app. The debug
# build is io.github.ichouchiang.clinetermux.debug; a bench release build (-Pcline.releaseIdSuffix=.bench) is
# io.github.ichouchiang.clinetermux.bench. The activity keeps its class name (dev.cline.termux.MainActivity)
# whatever the package id is.
# Never run this while a Termux-port release candidate is in its device steps
# or the owner is testing there: launching the app puts Termux in the
# background and trips the port's 7-second render gate.
set -euo pipefail
cd "$(dirname "$0")/.."

# adb device: SERIAL, else ANDROID_SERIAL; with neither, adb uses the only attached device.
SERIAL="${SERIAL:-${ANDROID_SERIAL:-}}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
OWNER_PKG="io.github.ichouchiang.clinetermux"
# The application id until 2026-10-08: an install of it is the owner's app too.
LEGACY_OWNER_PKG="dev.cline.termux"
APK="app/build/outputs/apk/debug/app-debug.apk"
LAUNCH=1
while [ "$#" -gt 0 ]; do
	case "$1" in
		--no-launch) LAUNCH=0 ;;
		-h | --help) sed -n '2,8p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
		-*) echo "unknown option: $1" >&2; exit 2 ;;
		*) APK="$1" ;;
	esac
	shift
done

[ -f "$APK" ] || { echo "missing $APK; run scripts/build.sh first" >&2; exit 1; }

# aapt2 from the newest build-tools (AAPT2 overrides).
if [ -z "${AAPT2:-}" ]; then
	tools="$(find "$SDK/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
	AAPT2="$tools/aapt2"
fi
[ -x "$AAPT2" ] || { echo "no aapt2 under $SDK/build-tools (set AAPT2)" >&2; exit 1; }
PKG="$("$AAPT2" dump badging "$APK" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
[ -n "$PKG" ] || { echo "could not read the package id of $APK" >&2; exit 1; }
if [ "$PKG" = "$OWNER_PKG" ] || [ "$PKG" = "$LEGACY_OWNER_PKG" ]; then
	echo "refusing to install $APK: its package is $PKG, so this would replace the owner's app" >&2
	echo "(install the debug build, or a release built with -Pcline.releaseIdSuffix=.bench)" >&2
	exit 1
fi

"$ADB" ${SERIAL:+-s "$SERIAL"} install -r -g "$APK"
echo "installed $PKG"
if [ "$LAUNCH" = 1 ]; then
	"$ADB" ${SERIAL:+-s "$SERIAL"} shell am start -W -n "$PKG/dev.cline.termux.MainActivity" >/dev/null
fi
