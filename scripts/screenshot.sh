#!/usr/bin/env bash
# Capture the phone screen to screenshots/<name>.png for visual review.
set -euo pipefail
cd "$(dirname "$0")/.."

# adb device: SERIAL, else ANDROID_SERIAL; with neither, adb uses the only attached device.
SERIAL="${SERIAL:-${ANDROID_SERIAL:-}}"
ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
NAME="${1:-screen}"
mkdir -p screenshots
"$ADB" ${SERIAL:+-s "$SERIAL"} exec-out screencap -p > "screenshots/$NAME.png"
echo "screenshots/$NAME.png"
