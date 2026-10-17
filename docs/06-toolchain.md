# Toolchain

_Generated on 2026-10-03 from `docs/research/kernel-and-desktop-synthesis.json` (multi-agent code reading of the Cline monorepo at cli-v3.0.68 + the Termux port). Line references point into `~/workspace/cline-termux-v3-phone`._

## Research recommendation (verbatim)

Keep the scaffold's verified stack (confirmed on this machine 2026-10-03 per ~/workspace/cline-termux-app/CLAUDE.md and build.gradle.kts): JDK Temurin 21 at ~/.local/jdk-21 (javac present; the system java 25 is a JRE) with compileOptions/jvmTarget 17; Gradle 9.6.0 via the project wrapper (must be generated first, see blocking unknown 13; AGP 9.4 requires >= 9.6.0; 9.8.0 is the latest and also fine); AGP 9.4.1 (com.android.application); Kotlin 2.4.20 as explicit plugins org.jetbrains.kotlin.android, org.jetbrains.kotlin.plugin.compose, org.jetbrains.kotlin.plugin.serialization with android.builtInKotlin=false in gradle.properties (so Kotlin, Compose compiler and serialization are one version number); compileSdk 36, targetSdk 36, minSdk 26 (Android SDK at ~/Android/Sdk has platforms android-35/36 and build-tools 34/35/36 only; the S25U runs Android 16 / API 36; do not move to compileSdk 37 until platform 37 is installed and the stable Compose BOM needs it); applicationId dev.cline.termux, Kotlin sources under app/src/main/kotlin/dev/cline/termux. Libraries: androidx.compose:compose-bom:2026.09.00 (compose.ui/foundation/animation 1.12.1, material3 1.4.0; add material3-adaptive-navigation-suite and material3-window-size-class for the Tab S7+ sw>=600dp layouts, material-icons-extended), androidx.activity:activity-compose 1.13.0, androidx.navigation:navigation-compose 2.10.2, androidx.lifecycle lifecycle-runtime-compose / lifecycle-viewmodel-compose 2.11.0, androidx.datastore:datastore-preferences 1.2.1, org.jetbrains.kotlinx:kotlinx-coroutines-android 1.11.0, kotlinx-serialization-json 1.11.0 (every Hub/dashboard/RUN_COMMAND message gets a @Serializable Kotlin type), com.squareup.okhttp3:okhttp 5.5.0 (WebSocket with custom Sec-WebSocket-Protocol and Origin headers; auto-pong), org.commonmark 0.30.0 core + gfm-tables + gfm-strikethrough + autolink for the Compose markdown renderer (reuse the owner's renderer and Material You theme from ~/workspace/cline-termux-voice/android with a note of origin), Android Keystore AES key + DataStore for the Hub token (androidx.security:security-crypto is deprecated; do not add it), junit 4.13.2 + kotlinx-coroutines-test 1.11.0 for protocol and ViewModel tests. Manifest additions: INTERNET, com.termux.permission.RUN_COMMAND, <queries> com.termux, POST_NOTIFICATIONS, FOREGROUND_SERVICE + FOREGROUND_SERVICE_SPECIAL_USE (subtype property), networkSecurityConfig allowing cleartext to 127.0.0.1, enableOnBackInvokedCallback=true (already set). Build only through ./scripts/build.sh (passes the env proxy as -D properties because Java ignores HTTP(S)_PROXY and ~/.gradle/gradle.properties pins a stale port), install with ./scripts/install.sh (adb -s $SERIAL; never during a port release candidate's device steps or the owner's manual test), screenshots with ./scripts/screenshot.sh; check `df -h /mnt/e` before the first Gradle/SDK download. Device side: Termux 0.118.3, Cline Termux port >= v3.0.68-termux.2 (entry.js + external ws), bun-android-ffi 1.4.0-canary.1 as the kernel runtime (the app does not depend on the runtime choice, only on a working Hub at ws://127.0.0.1:25463/hub, protocol v1, and on the shipped dist/cline-hub for the optional dashboard helper).

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
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am broadcast -a dev.cline.termux.debug.SET_HUB_TOKEN \
    -p "$PKG" --es token "$(scripts/dev-hub.sh token)" --ei port 25498
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am force-stop "$PKG"
# back to the real kernel: clear the override, drop the reverse
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am broadcast -a dev.cline.termux.debug.SET_HUB_TOKEN \
    -p "$PKG" --ez clear true
"$ADB" ${SERIAL:+-s "$SERIAL"} reverse --remove tcp:25498
"$ADB" ${SERIAL:+-s "$SERIAL"} shell am force-stop "$PKG"
```

The broadcast's result code is 0 on success, 2 when neither `token` nor `clear` was
given or the port is not an int in 1024..65535, 3 when the DataStore write failed. The
override survives app restarts until cleared or the app data is wiped (the sealed blob is
useless off the device: the Keystore key never leaves it). Never run this while a port
release candidate's device steps or the owner's manual test are in progress on the S25U.
