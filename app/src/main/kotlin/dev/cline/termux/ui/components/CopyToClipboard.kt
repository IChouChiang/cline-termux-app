package dev.cline.termux.ui.components

import android.content.ClipData
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.showReplacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * A plain (non-secret) clipboard write that reports whether it worked (CT A.7 "Copy failed").
 * The one clipboard path of the app besides `SecureClipboard` (docs/07 §14).
 */
interface CheckedCopier {
    /**
     * Copies [text] as a plain clip whose `ClipDescription` label is [label] (a string
     * resource, never a user-facing message). [confirmation] replaces the copier's legacy
     * snackbar text for this one copy; [onResult] receives whether the write worked.
     */
    fun copy(label: String, text: String, confirmation: String? = null, onResult: (Boolean) -> Unit = {})
}

/**
 * Copies file paths, ids, commands and output as plain clips with a `ClipDescription` label
 * (docs/07 §14). API 33+ relies on the system clipboard confirmation; below that
 * [legacyConfirmation] (or a copy's own `confirmation`) is shown as a snackbar when given. A
 * failure (a `SecurityException` from a locked-down clipboard, an oversized clip) is reported
 * through `onResult(false)` and, when [failureMessage] is given, as that snackbar on every API
 * level. Only the exception's class name is logged, never the text. Secrets go through
 * `SecureClipboard`, never through this.
 */
@Composable
fun rememberCopier(legacyConfirmation: String? = null, failureMessage: String? = null): CheckedCopier {
    val clipboard = LocalClipboard.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    return remember(clipboard, snackbar, scope, legacyConfirmation, failureMessage) {
        object : CheckedCopier {
            override fun copy(label: String, text: String, confirmation: String?, onResult: (Boolean) -> Unit) {
                scope.launch {
                    val ok = writePlainClip { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) }
                    onResult(ok)
                    val legacy = confirmation ?: legacyConfirmation
                    when {
                        !ok -> failureMessage?.let { snackbar.showReplacing(it) }
                        legacy != null && showsLegacyConfirmation(Build.VERSION.SDK_INT) -> snackbar.showReplacing(legacy)
                    }
                }
            }
        }
    }
}

/**
 * [rememberCopier] with the generic "Copied" confirmation on API ≤ 32 and the "Copy failed"
 * snackbar on every API level (an oversized clip, a locked-down clipboard).
 */
@Composable
fun rememberPlainCopier(): CheckedCopier = rememberCopier(
    legacyConfirmation = stringResource(R.string.components_copied),
    failureMessage = stringResource(R.string.components_copy_failed),
)

/** API 33+ shows the system's own clipboard confirmation, so the app's snackbar is for ≤ 32 only. */
internal fun showsLegacyConfirmation(sdkInt: Int): Boolean = sdkInt < Build.VERSION_CODES.TIRAMISU

/**
 * Runs one clipboard [write] and reports whether it worked: cancellation propagates, any other
 * failure is logged by type only (never the text) and returns false.
 */
internal suspend fun writePlainClip(write: suspend () -> Unit): Boolean = try {
    write()
    true
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w(TAG, "clipboard write failed: ${e.javaClass.simpleName}")
    false
}

private const val TAG = "ClineClipboard"
