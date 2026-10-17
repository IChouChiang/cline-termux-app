#!/usr/bin/env bash
# Build through this script. Java tools ignore HTTP(S)_PROXY, and the owner's
# ~/.gradle/gradle.properties pins a proxy port that no longer reaches
# dl.google.com and overrides project settings; command-line -D system
# properties outrank both. JDK: the system java is a JRE without javac, so the
# user-local Temurin under ~/.local/jdk-21 is the default.
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/jdk-21}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -x "$JAVA_HOME/bin/javac" ] || { echo "no JDK at $JAVA_HOME" >&2; exit 1; }

proxy_args=()
proxy="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [ -n "$proxy" ]; then
	hostport="${proxy#*://}"
	hostport="${hostport%%/*}"
	host="${hostport%:*}"
	port="${hostport##*:}"
	proxy_args=(
		"-Dhttp.proxyHost=$host" "-Dhttp.proxyPort=$port"
		"-Dhttps.proxyHost=$host" "-Dhttps.proxyPort=$port"
		"-Dhttp.nonProxyHosts=localhost|127.*"
	)
fi

if [ "$#" -eq 0 ]; then
	set -- :app:assembleDebug
fi
exec ./gradlew "${proxy_args[@]}" "$@"
