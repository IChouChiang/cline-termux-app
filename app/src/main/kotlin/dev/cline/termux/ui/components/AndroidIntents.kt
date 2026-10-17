package dev.cline.termux.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxRunner

/** The system "App info" page for [pkg] (`ACTION_APPLICATION_DETAILS_SETTINGS`). */
fun appDetailsIntent(pkg: String): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))

/**
 * Opens [pkg]'s App info page (this app's by default: the microphone, notification and Termux
 * `RUN_COMMAND` permissions live there after a permanent denial). False when the ROM has no
 * such page or does not export it to apps.
 */
fun Context.openAppDetails(pkg: String = packageName): Boolean =
    startActivitySafely(appDetailsIntent(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/**
 * "Open Termux" (A.6, E.7): Termux's launcher intent in a new task, else
 * `com.termux/.app.TermuxActivity` ([TermuxRunner.termuxActivityIntent]). False when Termux is
 * missing or its activity cannot be started, so the caller can say so instead of failing silently.
 */
fun openTermux(context: Context): Boolean {
    val launch = context.packageManager.getLaunchIntentForPackage(TermuxPaths.PACKAGE)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ?: TermuxRunner.termuxActivityIntent()
    return context.startActivitySafely(launch)
}

/**
 * Starts a system page; false instead of a crash when nothing resolves it
 * (`ActivityNotFoundException`) or it is not exported to apps on this ROM (`SecurityException`).
 */
fun Context.startActivitySafely(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}

/** The hosting activity behind any number of `ContextWrapper`s, or null outside one. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
