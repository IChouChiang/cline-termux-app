package dev.cline.termux.core.kernel.termux

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the result Termux posts through the PendingIntent [TermuxRunner] attached
 * to each command. Not exported: Termux sends it with our own PendingIntent identity.
 * Never logs the bundle (KS §0.6 rule 5): only the execution id and exit/err codes.
 */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESULT) return
        val executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra(TermuxRunner.RESULT_BUNDLE)
        if (executionId < 0 || bundle == null) {
            Log.w(TAG, "result without execution id or bundle (id=$executionId)")
            return
        }
        val raw = TermuxRunner.RawResult(
            stdout = bundle.getString(TermuxRunner.RESULT_STDOUT) ?: "",
            stderr = bundle.getString(TermuxRunner.RESULT_STDERR) ?: "",
            exitCode = bundle.getInt(TermuxRunner.RESULT_EXIT_CODE, -1),
            err = bundle.getInt(TermuxRunner.RESULT_ERR, TermuxResult.ERR_NONE),
            errmsg = bundle.getString(TermuxRunner.RESULT_ERRMSG)?.takeIf { it.isNotBlank() },
            stdoutOriginalLength = bundle.getString(TermuxRunner.RESULT_STDOUT_ORIGINAL_LENGTH)?.toIntOrNull()
                ?: bundle.getInt(TermuxRunner.RESULT_STDOUT_ORIGINAL_LENGTH, -1),
            stderrOriginalLength = bundle.getString(TermuxRunner.RESULT_STDERR_ORIGINAL_LENGTH)?.toIntOrNull()
                ?: bundle.getInt(TermuxRunner.RESULT_STDERR_ORIGINAL_LENGTH, -1),
        )
        if (!TermuxRunner.complete(executionId, raw)) {
            Log.w(TAG, "late or unknown Termux result for execution $executionId (exit=${raw.exitCode}, err=${raw.err})")
        }
    }

    companion object {
        const val ACTION_RESULT = "dev.cline.termux.action.TERMUX_RESULT"
        const val EXTRA_EXECUTION_ID = "dev.cline.termux.extra.EXECUTION_ID"
        private const val TAG = "TermuxResultReceiver"
    }
}
