package dev.cline.termux.core.kernel.termux

import kotlinx.coroutines.Deferred

/** Well-known Termux paths; `~` is never expanded by RunCommandService, so callers use these. */
object TermuxPaths {
    const val PACKAGE = "com.termux"
    const val ACTIVITY = "com.termux.app.TermuxActivity"
    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BIN = "$PREFIX/bin"
    const val CLINE = "$BIN/cline"
    const val BASH = "$BIN/bash"
    const val NPX = "$BIN/npx"
    const val PORT_CURRENT = "$PREFIX/opt/cline-termux/current"
    const val PORT_VERSION_FILE = "$PORT_CURRENT/VERSION"
    const val CLINE_DATA = "$HOME/.cline/data"
    const val HUB_DISCOVERY = "$CLINE_DATA/locks/hub/production.json"
    const val HUB_DAEMON_LOG = "$CLINE_DATA/logs/hub-daemon.log"
    const val CLI_LOG = "$CLINE_DATA/logs/cline.log"
    const val PROVIDERS_JSON = "$CLINE_DATA/settings/providers.json"
    const val GLOBAL_SETTINGS_JSON = "$CLINE_DATA/settings/global-settings.json"
    const val MCP_SETTINGS_JSON = "$CLINE_DATA/settings/cline_mcp_settings.json"
    const val ATTACHMENTS_DIR = "$CLINE_DATA/attachments/android"
    const val STORAGE = "$HOME/storage"
    const val SHARED_STORAGE = "$STORAGE/shared"

    /** Expands `~` and `~/…` the way the app must before every call (SP S9.3). */
    fun expandHome(path: String): String = when {
        path == "~" -> HOME
        path.startsWith("~/") -> HOME + path.substring(1)
        else -> path
    }
}

/** The timeout table of docs/07 §3.4; every catalog entry carries its own value. */
object TermuxTimeouts {
    const val DEFAULT_MS = 60_000L
    const val SHORT_MS = 10_000L
    const val MEDIUM_MS = 15_000L
    const val DISCOVERY_MS = 20_000L
    const val HUB_START_MS = 60_000L
    const val HUB_STATUS_MS = 15_000L
    const val HUB_STOP_MS = 30_000L

    /** `cline hub upgrade --wait N`; change both numbers together (KS §0.5). */
    const val HUB_UPGRADE_WAIT_S = 30
    const val HUB_UPGRADE_MS = (HUB_UPGRADE_WAIT_S + 45) * 1_000L
    const val DOCTOR_MS = 30_000L
    const val DOCTOR_FIX_MS = 60_000L
    const val AUTH_MS = 30_000L
    const val CONFIG_MS = 60_000L
    const val INSTALL_MS = 120_000L
    const val MARKETPLACE_INSTALL_MS = 300_000L
    /** Per command of an attachment write: each chunk append and the rename (CA C1.9 step 3). */
    const val ATTACHMENT_CHUNK_MS = 60_000L
    const val GIT_CHECKOUT_MS = 30_000L
    const val READ_FILE_MS = 60_000L

    /** stdout + stderr come back truncated to this many characters combined (KC). */
    const val RESULT_CAP_CHARS = 100_000
}

/**
 * One command for Termux's `RunCommandService`.
 *
 * [background] = true runs it as an app shell (no terminal session, no activity);
 * false opens a terminal session according to [sessionAction] (0 = new session, switch
 * to it), the only runner where the user can see URLs and device codes. The caller then
 * brings Termux to the front itself ([TermuxRunner.termuxActivityIntent], OQ 5).
 *
 * [label] is the only part of a command that is ever logged or rendered (docs/07 §3.3,
 * KS §0.6 rule 4): arguments may carry secrets (`--apikey`, `--room-secret`, `--header`)
 * and [stdin] carries user files.
 */
data class TermuxCommand(
    val path: String,
    val args: List<String> = emptyList(),
    val workdir: String = TermuxPaths.HOME,
    val stdin: String? = null,
    val background: Boolean = true,
    val sessionAction: Int? = null,
    val label: String = defaultLabel(path, args),
    val description: String? = null,
    /** Completion timeout for [TermuxExecutor.run]; ignored by [TermuxExecutor.runDetached]. */
    val timeoutMs: Long = TermuxTimeouts.DEFAULT_MS,
) {
    val foreground: Boolean get() = !background
    val argumentCount: Int get() = args.size
    val hasStdin: Boolean get() = stdin != null

    /** Label plus argument count; never arguments or stdin (docs/07 §3.3). */
    override fun toString(): String = buildString {
        append(label).append(" (").append(args.size).append(if (args.size == 1) " arg" else " args")
        if (stdin != null) append(", stdin")
        append(')')
    }

    companion object {
        /** Flags whose following value is a secret and is never rendered anywhere. */
        val SENSITIVE_FLAGS: Set<String> = setOf("--apikey", "-k", "--room-secret", "--header")
        const val REDACTED = "•••"

        /** The argument list with every secret value replaced by [REDACTED] (labels only). */
        fun redactArgs(args: List<String>): List<String> {
            val out = ArrayList<String>(args.size)
            var hide = false
            for (arg in args) {
                when {
                    hide -> { out += REDACTED; hide = false }
                    arg in SENSITIVE_FLAGS -> { out += arg; hide = true }
                    SENSITIVE_FLAGS.any { flag -> arg.startsWith("$flag=") } -> out += arg.substringBefore('=') + "=" + REDACTED
                    else -> out += arg
                }
            }
            return out
        }

        private fun defaultLabel(path: String, args: List<String>): String {
            val name = path.substringAfterLast('/')
            return if (args.isEmpty()) name else "$name ${redactArgs(args).joinToString(" ")}"
        }

        /** `$PREFIX/bin/<binary> args…` in the background. */
        fun bin(
            binary: String,
            vararg args: String,
            label: String? = null,
            timeoutMs: Long = TermuxTimeouts.DEFAULT_MS,
            workdir: String = TermuxPaths.HOME,
        ): TermuxCommand {
            val path = "${TermuxPaths.BIN}/$binary"
            return TermuxCommand(
                path = path,
                args = args.toList(),
                workdir = workdir,
                label = label ?: defaultLabel(path, args.toList()),
                timeoutMs = timeoutMs,
            )
        }

        /** `$PREFIX/bin/cline args…` in the background. */
        fun cline(
            vararg args: String,
            label: String? = null,
            timeoutMs: Long = TermuxTimeouts.DEFAULT_MS,
            workdir: String = TermuxPaths.HOME,
        ): TermuxCommand = TermuxCommand(
            path = TermuxPaths.CLINE,
            args = args.toList(),
            workdir = workdir,
            label = label ?: defaultLabel(TermuxPaths.CLINE, args.toList()),
            timeoutMs = timeoutMs,
        )
    }
}

/** What Termux reported back for one command. stdout/stderr are ANSI-stripped by the executor. */
data class TermuxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    /** Termux-internal error code; -1 (Activity.RESULT_OK) means the command ran. */
    val err: Int,
    val errmsg: String?,
    val stdoutOriginalLength: Int = stdout.length,
    val stderrOriginalLength: Int = stderr.length,
    val elapsedMillis: Long = 0,
) {
    /** Termux ran the executable (whatever its exit code). */
    val delivered: Boolean get() = err == ERR_NONE
    val internalError: Boolean get() = !delivered

    /** Success of a CLI call is the exit code, never a stdout match (SP §0.4). */
    val ok: Boolean get() = delivered && exitCode == 0

    /** The 100 KB cap may have cut output; callers that list or search check this. */
    val truncated: Boolean
        get() = stdout.length + stderr.length >= TermuxTimeouts.RESULT_CAP_CHARS ||
            stdoutOriginalLength > stdout.length || stderrOriginalLength > stderr.length

    /** Last non-empty stdout line (e.g. `cline hub start` prints the ws URL last). */
    val lastStdoutLine: String? get() = stdout.lineSequence().lastOrNull { it.isNotBlank() }?.trim()

    /** Last non-empty stderr line (the CLI's error text, shown verbatim by the specs). */
    val lastStderrLine: String? get() = stderr.lineSequence().lastOrNull { it.isNotBlank() }?.trim()

    /** stdout split into lines without the trailing empty line. */
    val stdoutLines: List<String> get() = stdout.lines().let { if (it.lastOrNull() == "") it.dropLast(1) else it }

    /** `err != -1` and the message names Termux's allow-external-apps property. */
    val mentionsExternalApps: Boolean get() = internalError && errmsg?.contains(EXTERNAL_APPS_PROPERTY) == true

    /** Termux could not execute the path at all (bootstrap missing, binary absent). */
    val executableFailed: Boolean get() = err == ERR_EXECUTABLE_FAILED

    /** `cat`/`stat`/`tail` on a file that does not exist. */
    val noSuchFile: Boolean get() = delivered && exitCode != 0 && stderr.contains("No such file")

    /** Sizes only; never output (it may hold secrets or user content). */
    override fun toString(): String =
        "TermuxResult(exit=$exitCode, err=$err, stdout=${stdout.length} chars, stderr=${stderr.length} chars, $elapsedMillis ms)"

    companion object {
        const val ERR_NONE = -1

        /** Termux's generic failure code; a missing executable comes back with it in ~20 ms (docs/05 Q4). */
        const val ERR_EXECUTABLE_FAILED = 150
        const val EXTERNAL_APPS_PROPERTY = "allow-external-apps"

        fun success(stdout: String, stderr: String = "", elapsedMillis: Long = 0) =
            TermuxResult(stdout, stderr, exitCode = 0, err = ERR_NONE, errmsg = null, elapsedMillis = elapsedMillis)

        fun failure(exitCode: Int, stderr: String, stdout: String = "", elapsedMillis: Long = 0) =
            TermuxResult(stdout, stderr, exitCode = exitCode, err = ERR_NONE, errmsg = null, elapsedMillis = elapsedMillis)

        fun undelivered(err: Int, errmsg: String, elapsedMillis: Long = 0) =
            TermuxResult("", "", exitCode = -1, err = err, errmsg = errmsg, elapsedMillis = elapsedMillis)
    }
}

/** Whether `com.termux` is resolvable (KS P1). */
sealed interface TermuxPresence {
    data object Missing : TermuxPresence
    data object Disabled : TermuxPresence
    data class Installed(val versionCode: Long, val versionName: String?) : TermuxPresence {
        /** RUN_COMMAND with result intents needs Termux 0.109+ (Termux's versionCode is its minor number). */
        val supported: Boolean
            get() = versionCode >= MIN_VERSION_CODE || (versionName?.let(::parsedMinor) ?: -1) >= MIN_MINOR
    }

    val installed: Boolean get() = this is Installed

    companion object {
        const val MIN_VERSION_CODE = 109L
        const val MIN_MINOR = 109

        /** `0.118.0` → 118; `0.109` → 109; `1.0` → MAX; unparsable → null. */
        fun parsedMinor(versionName: String): Int? {
            val parts = versionName.trim().removePrefix("v").split('.')
            if (parts.size < 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].takeWhile { it.isDigit() }.toIntOrNull() ?: return null
            return if (major > 0) Int.MAX_VALUE else minor
        }
    }
}

/** Why a command could not be handed to Termux, or was not run by it (docs/07 §3.3, §9). */
sealed class TermuxUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class NotInstalled : TermuxUnavailable("Termux is not installed")
    class PermissionMissing : TermuxUnavailable("com.termux.permission.RUN_COMMAND is not granted")
    class StartFailed(cause: Throwable) : TermuxUnavailable("Termux refused the command: ${cause.javaClass.simpleName}: ${cause.message}", cause)
    class TimedOut(val label: String, val afterMillis: Long) :
        TermuxUnavailable("No result from Termux after $afterMillis ms for `$label`")
    class ExternalAppsBlocked(val errmsg: String) : TermuxUnavailable("Termux is blocking external apps: $errmsg")
}

/**
 * A command started with [TermuxExecutor.runDetached]: no completion timeout; the result
 * intent is used only to notice an early exit (dashboard launch, foreground sign-in,
 * `termux-setup-storage`; SP §0.5).
 */
interface DetachedHandle {
    val executionId: Int
    val command: TermuxCommand

    /** Completes when the command exits or Termux reports a delivery error; never on a timer. */
    val earlyExit: Deferred<TermuxResult>

    /** Forgets the handle (a late result is dropped). Killing the process is the caller's `pkill`. */
    fun cancel()
}
