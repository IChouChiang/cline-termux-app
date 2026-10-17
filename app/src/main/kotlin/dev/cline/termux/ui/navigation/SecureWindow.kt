package dev.cline.termux.ui.navigation

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Reference-counted window flag (docs/07 §3.7, SP §0.6). `MainActivity` owns two of these:
 * one sets `FLAG_SECURE` while any screen reveals a secret, the other turns off the recents
 * screenshot (API 33+) while a key field is on screen (Connect a model, provider detail).
 * The flag is applied when the first owner holds it and cleared when the last one releases
 * it, so two screens can never clear each other's protection.
 */
class SecureFlagHolder(private val apply: (enabled: Boolean) -> Unit) {
    private val owners = LinkedHashSet<Any>()

    /** True while at least one owner holds the flag. */
    val held: Boolean get() = synchronized(owners) { owners.isNotEmpty() }

    fun hold(owner: Any) {
        val first = synchronized(owners) {
            val wasEmpty = owners.isEmpty()
            owners.add(owner) && wasEmpty
        }
        if (first) apply(true)
    }

    fun release(owner: Any) {
        val last = synchronized(owners) { owners.remove(owner) && owners.isEmpty() }
        if (last) apply(false)
    }

    companion object {
        /** A holder that applies nothing (previews, tests, surfaces outside `MainActivity`). */
        fun noop(): SecureFlagHolder = SecureFlagHolder { }
    }
}

/** The activity's two holders, provided at the root by `MainActivity`. */
class SecureWindow(
    /** `WindowManager.LayoutParams.FLAG_SECURE` on the activity window. */
    val secure: SecureFlagHolder,
    /** `Activity.setRecentsScreenshotEnabled(false)` (API 33+; a no-op below). */
    val recentsScreenshot: SecureFlagHolder,
)

val LocalSecureWindow = staticCompositionLocalOf { SecureWindow(SecureFlagHolder.noop(), SecureFlagHolder.noop()) }

/**
 * Holds `FLAG_SECURE` while [revealed] (a secret value is visible on this screen) and, with
 * [keyFieldShown], disables the recents screenshot while a key field is on screen. Releases
 * both when the caller leaves composition. Sheets and expanded dialogs are separate windows:
 * they also pass `SecureFlagPolicy.SecureOn` while revealed (SP §0.6).
 */
@Composable
fun SecureWindowEffect(revealed: Boolean, keyFieldShown: Boolean = false) {
    val window = LocalSecureWindow.current
    val owner = remember { Any() }
    DisposableEffect(window, revealed) {
        if (revealed) window.secure.hold(owner)
        onDispose { window.secure.release(owner) }
    }
    DisposableEffect(window, keyFieldShown) {
        if (keyFieldShown) window.recentsScreenshot.hold(owner)
        onDispose { window.recentsScreenshot.release(owner) }
    }
}

/**
 * Holds `FLAG_SECURE` on the dialog window this composable is drawn in while [secure] (SP
 * §0.6: the activity's flag does not reach a separate dialog window). Outside a dialog it does
 * nothing; the activity window is covered by [SecureWindowEffect].
 */
@Composable
fun DialogSecureEffect(secure: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, secure) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (secure && window != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (secure && window != null) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
