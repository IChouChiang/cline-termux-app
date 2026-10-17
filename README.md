# Cline Termux (unofficial)

Cline Termux is an unofficial Android app for the Cline _command-line interface_ (_CLI_) in Termux.
Version 0.1.0-pre.2 is the second public prerelease, for testers.

Cline Termux is not affiliated with or endorsed by Cline Bot Inc. or the Termux project.
Report problems with this app on the [Issues](https://github.com/IChouChiang/cline-termux-app/issues) page of this repository, not to Cline or Termux.

## Learn what the app does

The app is a native front end for the Cline CLI in Termux on the same phone.
The app uses Kotlin, Jetpack Compose and Material 3.
The app aims to offer the functions of Cline Desktop that are useful on a phone.

The app contains no agent and no model.
The app sends your tasks to the _Cline Hub_ on your phone.
The Hub is the background service of the Cline CLI.
In this README, the _kernel_ is the Cline CLI and the Hub together, as in the app.

In the app, you can chat with Cline, approve tool calls, review changes and restore checkpoints.
You can also manage providers, tools and schedules, and examine the health of the kernel.
For the full list, see the [release notes of 0.1.0-pre.1](docs/release-notes/v0.1.0-pre.1.md).
For the changes since then, see the [release notes of 0.1.0-pre.2](docs/release-notes/v0.1.0-pre.2.md).

## Prepare your phone

Before you install the app, make sure that your phone has these items:

- **Android 8.0 (API level 26) or later.**
- **Termux 0.109 or later**, from [F-Droid](https://f-droid.org/packages/com.termux/) or [GitHub](https://github.com/termux/termux-app/releases/latest).
  The project tests the app with Termux 0.118.
  After you install Termux, open Termux once.
  Termux then installs the basic packages.
- **The cline-termux port, release 3.0.68-termux.2 or later.**
  The [port](https://github.com/IChouChiang/cline-termux) is a build of the Cline CLI for Termux.
  Use the latest release of the port.
- **A model provider.**
  Connect a provider during the first setup of the app, or run `cline auth` in Termux.
- **The Cline Hub.**
  When you tap **Start kernel** during the first setup, the app starts the Hub.
  A `cline` session in Termux or the `cline hub start` command also starts the Hub.

### Install the port

To install the port, run the one-command installer of the port in Termux:

```bash
curl -fsSL https://github.com/IChouChiang/cline-termux/releases/latest/download/install-cline-termux.sh | bash
```

To see the installed release of the port, run this command in Termux:

```bash
grep '^release=' $PREFIX/opt/cline-termux/current/VERSION
```

The output shows the release, for example:

```
release=3.0.70-termux.1
```

If the command shows an error, no line or a release older than 3.0.68-termux.2, run the installer again.
For more information about the port, see the [port repository](https://github.com/IChouChiang/cline-termux).

### Use shared storage (optional)

To work in folders of the shared storage of your phone, run this command in Termux:

```bash
termux-setup-storage
```

When Android asks, allow the storage permission for Termux.
Termux then links the shared storage in `~/storage`.

## Install the app

The **Releases** page of this repository on GitHub has an _Android package_ (_APK_) file for each prerelease.
The release key of the project signs each APK.
A later prerelease installs over an earlier one and keeps your app data.

1. Open the **Releases** page of this repository on your phone.
2. Download the APK file of the latest prerelease.
3. Open the APK file.
   If Android asks, allow your browser or file manager to install unknown apps.
4. Tap **Install**.

On your home screen, the app has the name **Cline Termux**.
The application ID of the app is `io.github.ichouchiang.clinetermux`.

If you installed a release build of this app that you made yourself, uninstall that build first.
Android cannot install an update that has a different signing key.
When you uninstall the app, Android deletes the app data.
Your sessions stay in Termux.

### Verify the APK signature (optional)

Use a computer that has the build tools of the Android _software development kit_ (_SDK_).
To make sure that an APK is from this project, examine the signing certificate of the APK:

```bash
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs APK_FILE
```

Replace `APK_FILE` with the path of the downloaded APK file.
The output includes these lines:

```
V2 Signer: certificate DN: CN=Cline Termux App, O=IChouChiang
V2 Signer: certificate SHA-256 digest: 1c5867e1aa40a3760a3707c6c1c25f0330810ad8a86a0c3d3d5d08d4b6c0873c
```

Older build tools, such as version 34.0.0, start each line with `Signer #1` instead of `V2 Signer:`.
If the SHA-256 digest is different, do not install the APK.

## Complete the first setup

When you open the app for the first time, the app shows a setup flow.

1. Tap **Get started** on the welcome screen.
2. Do the fix for each failed check on **Set up the Cline kernel**.
   The table after these steps lists the five checks.
3. When the five checks pass, tap **Continue**.
4. If the app shows **Keep the kernel running**, do the items that apply to your phone.
   To do the items later, tap **Skip for now**.
5. Select **Sign in with Cline** or **Use your own API key** on **Set up Cline**.
   To connect a provider later, tap **Skip**.
6. Tap **Start building** on the last screen.

| Check | Fix |
| --- | --- |
| **Termux** | Install Termux 0.109 or later from F-Droid or GitHub. If Android disabled Termux, enable Termux in the Android settings. |
| **Permission to run commands** | Tap **Allow**. Allow **Run commands in Termux environment** in the dialog. |
| **Termux accepts commands from Cline** | Run the command after this table in Termux. Tap **Check again**. |
| **Cline kernel** | Run the installer of the port in Termux. Tap **Check again**. |
| **Cline Hub** | Tap **Start kernel**. If the Hub is already active, the app connects without a tap. |

The fix for **Termux accepts commands from Cline** is this command.
The app shows the same command with a copy button.

```bash
mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && termux-reload-settings
```

The Hub uses about 300 MB of memory.
The Hub stays active in Termux after you close the app.

**Keep the kernel running** lists battery and notification settings for Termux and the app.
These settings help prevent Android from stopping Termux and the app in the background.

If you select **Sign in with Cline**, tap **Sign in**.
Termux opens and shows a link.
Open the link that Termux shows.
Approve the sign-in in your browser.
Return to the app.

If you select **Use your own API key**, select the provider and type the API key.
Tap **Connect**.

### Reconnect to the Hub

If Android stops Termux in the background, the Hub stops too.
The app then shows a status such as **Termux asleep** or **Kernel stopped**.
The message field shows **Cline Hub is not connected.**

To start the Hub again, do one of these steps:

- Tap **Start kernel** in the app.
- Run `cline hub start` in Termux.
  While the app is in the foreground, the app connects to the Hub within 10 seconds.

The `cline hub start` command prints the address of the Hub:

```
ws://127.0.0.1:25463/hub
```

After the first setup, if the Hub is not active when you open the app, the app starts the Hub.
When you return to the app after more than five seconds in the background, the app also starts the Hub.
If you stopped the kernel with **Stop kernel**, the app does not start the Hub automatically.
To help prevent stops, do the items in **Settings** > **Kernel** > **Background reliability**.

### Find the settings

- **Settings** contains the settings of the app and of the kernel.
- The app stores the app settings, such as appearance and notifications, on your phone.
- The kernel stores the kernel settings in Termux, under `~/.cline`.
  The kernel settings include providers, auto-approve, compaction, tools and telemetry.
- The `cline` CLI in Termux uses the same kernel settings.
  A change in the app also applies in Termux.
- **Settings** > **Kernel** shows the status of the Hub.
  On this page, you can stop or restart the kernel.
- To see the setup flow again, open **Settings** > **New user experience**.
  Tap **Replay** in the dialog.

## Review the known limits

The IDs in parentheses refer to the decision log in [docs/decided.md](docs/decided.md) and the trial log in [docs/todo.md](docs/todo.md).
U1 to U10 are kernel issues that this project plans to report to upstream Cline.

The kernel causes these limits:

- The app has no `/compact` command, because the Hub has no command for manual compaction (U3).
- If a session used hooks in the `cline` terminal, a later turn from the app can wait forever (U2).
  To recover, restart the Hub with the commands after these lists.
- If a `PreToolUse` hook cancels a tool call, the kernel stops the whole turn (U5).
  The app shows the call as **Interrupted**, without a reason.
- Sessions that you start with `cline -z` (zen mode) never compact automatically (U8).
  The problem does not affect sessions that the app starts.
- Automatic compaction starts too early, because the kernel counts each attached image as text (U9).
  The ChatGPT subscription provider (`openai-codex`) has the problem most, because the provider accepts at most 258,400 input tokens.
- If the model sends an empty summary, the kernel skips compaction and tries again at each turn (U10).
- After the first compaction, the kernel can compact again after every five large tool results (U7).
- The app restores a checkpoint in two separate kernel steps, because the one-step restore loses a stored API key (U1).
  If the new session cannot start, the workspace stays restored to the checkpoint.
- The app cannot show recovery notices, because the kernel does not send recoverable errors to clients (U6).
- The slash menu does not show plugin commands, because no Hub command lists plugin commands.
- After you upgrade the port, the old Hub can stay active until you restart the Hub (row 24).
  **Settings** > **Kernel** then shows **Restart to use** and the new release.

The app causes these limits:

- If the **Teams** tool is off in **Customize** > **Tools**, the app does not start agent teams.
  The project has not tested agent teams from the app yet.
- **Plan** and **Act** in the composer change only the prompt, not the tools of the session.
  A session keeps the tools of the mode that the session started in.
- While the mistake-limit question waits for an answer, the model calls continue for up to five minutes (A17).
- Dictation requests offline speech recognition.
  If dictation shows a language error, install the offline speech pack for your phone language (A7).
- The project has not tested **Sign in with Cline** with a real Cline account yet (B8.1).
- The project has not tested tablet layouts and other wide layouts on a device yet (B8.2).

To restart the Hub, run these commands in Termux:

```bash
cline hub stop
cline hub start
```

The first command prints `{"stopped":true}`.
The second command prints the address of the Hub, `ws://127.0.0.1:25463/hub`.
A restart of the Hub stops all turns in progress.

## Review privacy and network use

The app has no analytics and no crash reporting.
The app makes these network connections:

- To the Cline Hub, at `127.0.0.1` on your phone.
  The default port of the Hub is 25463.
- To the settings helper of the kernel, at `127.0.0.1:8787`.
  The app starts the helper in Termux for some pages, such as **Providers**, **Account** and **Marketplace**.
- To `https://cline.github.io/marketplace/catalog.json`.
  When you open **Customize** or **Marketplace**, the app downloads the public marketplace catalog from this address.

Links, such as sign-in pages and the API key pages of providers, open in your browser.
The kernel in Termux, not the app, sends your prompts to your model provider.
The **Telemetry** switch in **Settings** controls the error and usage reports of the kernel.
For dictation, the app uses the speech recognition service of your phone and requests offline recognition.

### Understand how the app keeps secrets

- The app gets the Hub token from the discovery file of the kernel, `~/.cline/data/locks/hub/production.json`.
  Termux keeps the discovery file private.
  The app runs `cat` on the file in Termux, through the Termux permission **Run commands in Termux environment**.
- The app encrypts the Hub token with a key in the Android Keystore.
  The app never writes the token to a log.
- The Hub makes a new token each time the Hub starts.
  The app then reads the discovery file again.
- Android backups and device transfers do not include the Hub token or the session caches of the app.
- When you save an API key in the app, the app gives the key to the kernel in Termux.
  The app does not store the key.
- **Sign in with Cline** runs `cline auth` in a Termux session.
  The app never sees the sign-in code or the tokens.
- A diagnostics export contains app and phone information, the app settings, the last 48 KiB of the kernel logs `cline.log` and `hub-daemon.log`, and the metadata of the sessions that you select.
  The metadata of a session includes its title, which the kernel makes from the first prompt.
  The export does not contain the conversation contents.
  The app removes text that looks like an API key or a token from the export.

## Build from source

To build the app, install these tools:

- _Java Development Kit_ (_JDK_) 21 with `javac`, for example Eclipse Temurin 21.
  A Java runtime without `javac` cannot build the app.
- The Android SDK, with platform-tools and the SDK platform for API level 37.

The repository includes the Gradle wrapper, `./gradlew`, which downloads Gradle 9.6.0.

1. Set `JAVA_HOME` to the JDK folder and `ANDROID_HOME` to the Android SDK folder:

   ```bash
   export JAVA_HOME=/path/to/jdk-21
   export ANDROID_HOME=/path/to/android-sdk
   ```

2. Build the debug APK in the root folder of the repository:

   ```bash
   ./gradlew assembleDebug
   ```

   If the build succeeds, Gradle prints `BUILD SUCCESSFUL` and the build time.
   The debug APK is `app/build/outputs/apk/debug/app-debug.apk`.

3. Run the unit tests (optional).
   The unit tests take several minutes.

   ```bash
   ./gradlew testDebugUnitTest
   ```

The debug build has the application ID `io.github.ichouchiang.clinetermux.debug` and the name **Cline Termux Debug**.
The debug build installs next to the release app and does not replace the release app.

If your network needs a proxy, build with `bash scripts/build.sh :app:assembleDebug`.
Java tools ignore `HTTPS_PROXY`, so the script gives the proxy to Gradle as Java system properties.
If you do not set `JAVA_HOME` or `ANDROID_HOME`, the script uses `~/.local/jdk-21` or `~/Android/Sdk`.

### Build a release APK

To build a release APK, run this command:

```bash
./gradlew assembleRelease
```

The release APK is `app/build/outputs/apk/release/app-release.apk`.
The release build has the application ID `io.github.ichouchiang.clinetermux`.
The release key of the project is not in the repository.
Without the release key, the build signs the release APK with your debug key.
Gradle then shows this warning:

```
release keystore not configured; signing with the debug key
```

An APK with the debug signature cannot update an installed prerelease.
For the release key and the four signing properties, see [docs/release-signing.md](docs/release-signing.md).

## Report a problem

1. Open **Settings** > **Diagnostics** in the app.
2. Select the sessions to include in the report.
3. Tap **Export**.
4. Tap **Share** or **Save to Files**.
5. Open an issue on the **Issues** page of this repository on GitHub.
6. Describe the problem in the issue.
   Attach the diagnostics file to the issue.

If the same problem occurs in the `cline` terminal in Termux, the problem is in the kernel.
If the kernel problem also occurs with Cline on a computer, report the problem to [upstream Cline](https://github.com/cline/cline/issues).
If the kernel problem occurs only in Termux, report the problem to the [cline-termux port](https://github.com/IChouChiang/cline-termux/issues).

## Review the license

The Apache License 2.0 applies to this project.
For the full text, see [LICENSE](LICENSE).

The app includes code and text adapted from [Cline](https://github.com/cline/cline), which is also under the Apache License 2.0.
In the app, **Settings** > **About** > **Open source licenses** lists the third-party components and their licenses.

Cline Termux is an unofficial, independent app.
It is not affiliated with, endorsed by or sponsored by Cline Bot Inc. or the Termux project.
Cline is a trademark of Cline Bot Inc., and other names belong to their owners.
The app uses these names only to name the software that it works with.
The app has its own name, **Cline Termux**, and its own icon: a C followed by a terminal cursor.
The app does not use the Cline logo.
Report problems with this app on the [Issues](https://github.com/IChouChiang/cline-termux-app/issues) page of this repository, not to Cline or Termux.
