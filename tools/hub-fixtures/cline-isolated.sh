#!/usr/bin/env bash
# Run the Cline CLI from the fork's source against the isolated fixture home.
#
# Everything the recorder and the regeneration steps need is pinned here so no
# command can touch the user's real ~/.cline or the real Hub port:
#   CLINE_DIR        isolated kernel home (settings, sessions, hub locks, logs)
#   CLINE_HUB_PORT   fixture Hub port (the device default is 25463)
#   CLINE_BUILD_ENV  production -> discovery record at data/locks/hub/production.json,
#                    exactly like the Termux package
#   NO_PROXY         keep loopback traffic (mock provider, Hub) off the workstation proxy
set -euo pipefail

FORK_ROOT="${CLINE_FORK_ROOT:-$HOME/workspace/cline-termux-v3-phone}"
: "${CLINE_DIR:?set CLINE_DIR to the isolated fixture home}"
export CLINE_DIR
export CLINE_HUB_PORT="${CLINE_HUB_PORT:-25498}"
export CLINE_BUILD_ENV=production
export CLINE_NO_AUTO_UPDATE=1
export CLINE_TELEMETRY_DISABLED=1
export CLINE_DISABLE_CLINE_PASS_NOTICE=1
export NO_PROXY="127.0.0.1,localhost"
export no_proxy="127.0.0.1,localhost"

cd "$FORK_ROOT"
exec bun apps/cli/src/index.ts "$@"
