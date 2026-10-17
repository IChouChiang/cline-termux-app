# Release signing

`assembleRelease` signs the release build (`io.github.ichouchiang.clinetermux`) with the project's own release key
when all four Gradle properties below are set and the keystore file exists, and with the local
debug key otherwise (`app/build.gradle.kts`, `signingConfigs.release`). The key and its password
never enter the repo. Release builds made before 2026-10-08 were signed with the debug key and
used the old application id `dev.cline.termux`, so a release-key build installs beside them as a
second app with its own data. Only a debug-key release build of
`io.github.ichouchiang.clinetermux` (one built without the key, see "Building without the key")
conflicts with a release-key build: uninstall it first, which deletes its data.

## The key

| | |
| --- | --- |
| Keystore | `~/.local/share/cline-termux-app/release.jks` on the owner's workstation (PKCS12 format despite the `.jks` name; file mode 600, folder mode 700) |
| Alias | `cline-termux-app` |
| Certificate | self-signed, `CN=Cline Termux App, O=IChouChiang`, RSA 2048, SHA384withRSA |
| Valid | 2026-10-08 to 2054-02-23 (10,000 days) |
| Certificate SHA-256 | `1c5867e1aa40a3760a3707c6c1c25f0330810ad8a86a0c3d3d5d08d4b6c0873c` |
| The same, colon form | `1C:58:67:E1:AA:40:A3:76:0A:37:07:C6:C1:C2:5F:03:30:81:0A:D8:A8:6A:0C:3D:3D:5D:08:D4:B6:C0:87:3C` |

The fingerprint is public: `apksigner` prints the first form and `keytool -list -v` the second.
The store and the key share one generated password (a PKCS12 keystore keeps a single password
for both).

## The four properties

| Gradle property | Value |
| --- | --- |
| `clineTermuxReleaseStoreFile` | absolute path of the keystore |
| `clineTermuxReleaseStorePassword` | the keystore password |
| `clineTermuxReleaseKeyAlias` | `cline-termux-app` |
| `clineTermuxReleaseKeyPassword` | the key password (the same value) |

On the owner's workstation these four lines are kept in
`~/.local/share/cline-termux-app/release-signing.properties` (mode 600), next to the keystore.
Gradle does not read that file by itself. Either append it once to the user-level
`~/.gradle/gradle.properties`, which lives outside every checkout, so that every release build
picks the key up:

```bash
cat ~/.local/share/cline-termux-app/release-signing.properties >> ~/.gradle/gradle.properties
chmod 600 ~/.gradle/gradle.properties
```

or hand the properties to a single build as `ORG_GRADLE_PROJECT_<name>` environment variables.
The subshell keeps them out of your shell, and `--no-daemon` keeps them out of a Gradle daemon
that would outlive the build:

```bash
( while IFS='=' read -r k v; do export "ORG_GRADLE_PROJECT_$k=$v"; done \
    < ~/.local/share/cline-termux-app/release-signing.properties
  bash scripts/build.sh :app:assembleRelease --no-daemon )
```

Do not pass the passwords with `-P`: other processes can read a command line.

## Back up both files

Copy the whole `~/.local/share/cline-termux-app/` folder (the keystore and the properties file)
to storage off this workstation, such as an encrypted drive or a password manager that keeps
files.

- **Losing either one** (the keystore, or the password in the properties file) means no update
  can ever be signed with this key again. Android installs an update over an installed app only
  when the signing certificate matches, so every tester would have to uninstall the app, which
  deletes its data (settings, the stored Hub token), and then install a build signed with a new
  key. Android's key rotation (APK Signature Scheme v3, `apksigner rotate`) needs the old key as
  well, so it does not help after a loss.
- **A leaked keystore together with its password** lets anyone sign an update that testers'
  phones would accept as this app. If that happens, stop using the key, make a new one and have
  testers reinstall.
- Neither file is ever committed. Both live outside the repo, and `.gitignore` also drops
  `*.jks` and `*.keystore`.

## Building without the key

Contributors need nothing. The release key is used only when all four properties are set and
`clineTermuxReleaseStoreFile` names a file that exists.

When none of the four properties is set, a build that packages a release APK or bundle prints

```
release keystore not configured; signing with the debug key
```

and signs the release APK with the local debug key (`~/.android/debug.keystore`,
`CN=Android Debug`). That APK installs for testing, but it cannot update an install signed with
the release key, nor the other way round: uninstall first. The message is a Gradle warning, so
`-q` hides it.

When some of the properties are set but the set is unusable, the same build fails instead:

```
release signing is only partly configured: <problems>. Fix it, or unset all four clineTermuxRelease* properties to sign with the debug key.
```

The problems are `clineTermuxReleaseStoreFile names a missing file` and
`not set: <property names>`. Gradle does not expand `~`, so a store path that starts with `~`
counts as a missing file; the message says so. Other builds, such as `assembleDebug` and the unit
tests, are not affected; debug builds always use the debug key.

## Checking an APK before handing it out

```bash
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

A release-key build shows `certificate DN: CN=Cline Termux App, O=IChouChiang` and the SHA-256
above. `CN=Android Debug` means the build fell back to the debug key; do not hand that APK to
testers.

## How the key was made

On 2026-10-08, with the JDK 21 `keytool` and a password of 24 random bytes in base64
(`head -c 24 /dev/urandom | base64`, 32 characters) held in the exported variable `PW`:

```bash
keytool -genkeypair -keystore ~/.local/share/cline-termux-app/release.jks -storetype PKCS12 \
  -alias cline-termux-app -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Cline Termux App, O=IChouChiang" -storepass:env PW -keypass:env PW
```

The `:env` form reads the password from the environment instead of the command line.
