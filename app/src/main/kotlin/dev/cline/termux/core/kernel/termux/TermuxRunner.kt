package dev.cline.termux.core.kernel.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Runs commands inside Termux through its `RUN_COMMAND` intent and returns the result
 * Termux posts back to [TermuxResultReceiver] via a one-shot PendingIntent.
 *
 * Needs `allow-external-apps=true` in `~/.termux/termux.properties` and the runtime
 * permission `com.termux.permission.RUN_COMMAND` (declared by Termux, requested by us).
 * stdout+stderr come back truncated to 100 KB combined (`RESULT_CAP_CHARS`), so this is
 * for control commands and small reads, never for logs or transcripts (§0 rule 5).
 */
class TermuxRunner(
    context: Context,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) : TermuxExecutor {
    private val appContext = context.applicationContext

    override fun isTermuxInstalled(): TermuxPresence = try {
        val info = appContext.packageManager.getPackageInfo(TermuxPaths.PACKAGE, 0)
        val application = info.applicationInfo
        if (application != null && !application.enabled) {
            TermuxPresence.Disabled
        } else {
            TermuxPresence.Installed(PackageInfoCompat.getLongVersionCode(info), info.versionName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        TermuxPresence.Missing
    }

    override fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, PERMISSION) == PackageManager.PERMISSION_GRANTED

    override suspend fun run(command: TermuxCommand, timeoutMs: Long): TermuxResult {
        val deferred = CompletableDeferred<RawResult>()
        val startedAt = elapsed()
        val executionId = dispatch(command) { raw -> deferred.complete(raw) }
        val raw = try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (_: TimeoutCancellationException) {
            Log.w(TAG, "${command.label}: no result after ${elapsed() - startedAt} ms (#$executionId)")
            throw TermuxUnavailable.TimedOut(command.label, elapsed() - startedAt)
        } finally {
            pending.remove(executionId)
        }
        return finish(command, raw, startedAt)
    }

    override fun runDetached(command: TermuxCommand): DetachedHandle {
        val exit = CompletableDeferred<TermuxResult>()
        val startedAt = elapsed()
        val executionId = dispatch(command) { raw ->
            try {
                exit.complete(finish(command, raw, startedAt))
            } catch (e: TermuxUnavailable) {
                exit.completeExceptionally(e)
            }
        }
        return object : DetachedHandle {
            override val executionId: Int = executionId
            override val command: TermuxCommand = command
            override val earlyExit: Deferred<TermuxResult> = exit
            override fun cancel() {
                pending.remove(executionId)
                exit.cancel()
            }
        }
    }

    /** Builds the intent, registers the completion callback and starts RunCommandService. */
    private fun dispatch(command: TermuxCommand, onResult: (RawResult) -> Unit): Int {
        if (!isTermuxInstalled().installed) throw TermuxUnavailable.NotInstalled()
        if (!hasPermission()) throw TermuxUnavailable.PermissionMissing()

        val executionId = nextExecutionId.incrementAndGet()
        pending[executionId] = onResult
        val intent = buildIntent(command, executionId)
        try {
            startRunCommandService(intent)
        } catch (_: SecurityException) {
            pending.remove(executionId)
            throw TermuxUnavailable.PermissionMissing()
        } catch (e: Exception) {
            pending.remove(executionId)
            throw TermuxUnavailable.StartFailed(e)
        }
        Log.d(TAG, "dispatched ${command.label} (#$executionId)")
        return executionId
    }

    /**
     * `startForegroundService` first (Termux's RunCommandService calls `startForeground`
     * itself), then plain `startService` when the foreground start is not allowed (API 31+
     * `ForegroundServiceStartNotAllowedException`, an `IllegalStateException`). When the
     * plain start is refused too, the caller gets [TermuxUnavailable.StartFailed] (docs/07 §3.3).
     */
    private fun startRunCommandService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                appContext.startForegroundService(intent)
                return
            } catch (_: IllegalStateException) {
                // fall through to the plain start
            }
        }
        appContext.startService(intent)
    }

    internal fun buildIntent(command: TermuxCommand, executionId: Int): Intent {
        val resultIntent = Intent(appContext, TermuxResultReceiver::class.java)
            .setAction(TermuxResultReceiver.ACTION_RESULT)
            .setPackage(appContext.packageName)
            .putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, executionId)
        // Mutable: Termux fills the result bundle into this intent when it sends it.
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            executionId,
            resultIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return Intent(ACTION_RUN_COMMAND)
            .setClassName(TermuxPaths.PACKAGE, RUN_COMMAND_SERVICE)
            .putExtra(EXTRA_COMMAND_PATH, command.path)
            .putExtra(EXTRA_ARGUMENTS, command.args.toTypedArray())
            .putExtra(EXTRA_WORKDIR, command.workdir)
            .putExtra(EXTRA_BACKGROUND, command.background)
            .putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            .apply {
                command.stdin?.let { putExtra(EXTRA_STDIN, it) }
                command.sessionAction?.let { putExtra(EXTRA_SESSION_ACTION, it.toString()) }
                putExtra(EXTRA_COMMAND_LABEL, command.label)
                command.description?.let { putExtra(EXTRA_COMMAND_DESCRIPTION, it) }
            }
    }

    /** ANSI strip, timing, label-only log; the external-apps refusal becomes an exception. */
    private fun finish(command: TermuxCommand, raw: RawResult, startedAt: Long): TermuxResult {
        val result = TermuxResult(
            stdout = AnsiStrip.strip(raw.stdout),
            stderr = AnsiStrip.strip(raw.stderr),
            exitCode = raw.exitCode,
            err = raw.err,
            errmsg = raw.errmsg?.let(AnsiStrip::strip),
            stdoutOriginalLength = if (raw.stdoutOriginalLength >= 0) raw.stdoutOriginalLength else raw.stdout.length,
            stderrOriginalLength = if (raw.stderrOriginalLength >= 0) raw.stderrOriginalLength else raw.stderr.length,
            elapsedMillis = elapsed() - startedAt,
        )
        Log.d(TAG, "${command.label}: exit=${result.exitCode} err=${result.err} in ${result.elapsedMillis} ms")
        if (result.mentionsExternalApps) throw TermuxUnavailable.ExternalAppsBlocked(result.errmsg.orEmpty())
        return result
    }

    /** The bundle Termux posts, before stripping. Never logged (KS §0.6 rule 5). */
    internal class RawResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val err: Int,
        val errmsg: String?,
        val stdoutOriginalLength: Int,
        val stderrOriginalLength: Int,
    )

    companion object {
        const val PERMISSION = "com.termux.permission.RUN_COMMAND"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
        const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
        const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
        const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

        // Result bundle (TermuxConstants.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE*).
        const val RESULT_BUNDLE = "result"
        const val RESULT_STDOUT = "stdout"
        const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
        const val RESULT_STDERR = "stderr"
        const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
        const val RESULT_EXIT_CODE = "exitCode"
        const val RESULT_ERR = "err"
        const val RESULT_ERRMSG = "errmsg"

        private const val TAG = "TermuxRunner"

        // Execution ids double as PendingIntent request codes, so they must be unique per
        // process lifetime; seed from the clock so a restarted process does not collide
        // with still-pending intents of the previous one.
        private val nextExecutionId = AtomicInteger((System.currentTimeMillis() / 1000L % 1_000_000L).toInt() * 100)
        private val pending = ConcurrentHashMap<Int, (RawResult) -> Unit>()

        /** Routes a result bundle to whichever `run`/`runDetached` is waiting for it. */
        internal fun complete(executionId: Int, result: RawResult): Boolean {
            val callback = pending.remove(executionId) ?: return false
            callback(result)
            return true
        }

        /**
         * Brings Termux to the front after a foreground-session command (OQ 5): Termux
         * cannot start its own activity from the background, the app can while it is
         * in the foreground. Start it from an activity.
         */
        fun termuxActivityIntent(): Intent = Intent()
            .setClassName(TermuxPaths.PACKAGE, TermuxPaths.ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
