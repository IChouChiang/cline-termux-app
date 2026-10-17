package dev.cline.termux.ui.components

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.cline.termux.R
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineDarkScheme
import dev.cline.termux.ui.theme.ClineLightScheme
import dev.cline.termux.ui.theme.ClineTheme
import kotlinx.coroutines.launch

/*
 * The only place that opens URLs (docs/07 D8: KS §0.7 `openUrl` and CR §0.8 `openExternal`
 * merged). Web URLs open in Custom Tabs with the system colour scheme and per-scheme toolbar
 * colours, or a plain ACTION_VIEW when no Custom Tabs provider answers; any other scheme
 * (mailto:, tel: from Markdown links) goes straight to ACTION_VIEW. When nothing resolves,
 * the URL is copied to the clipboard and the caller shows "No browser found to open %1$s."
 */

/** What happened to an [openExternal] call. */
enum class OpenExternalResult {
    /** A Custom Tab or the browser took the URL. */
    Opened,

    /** No activity could handle it; the URL was copied to the clipboard instead. */
    NoBrowser,
}

/**
 * Opens an http(s) [url] in a Custom Tab (`setShowTitle(true)`, `COLOR_SCHEME_SYSTEM`,
 * toolbar = [lightSurface] / [darkSurface]), falling back to `Intent.ACTION_VIEW`; any other
 * scheme (mailto:, tel:) skips the Custom Tab and goes straight to `ACTION_VIEW` with
 * `FLAG_ACTIVITY_NEW_TASK`. When nothing takes it, copies the URL to the clipboard and returns
 * [OpenExternalResult.NoBrowser] so the caller can show the snackbar ([copyOnFailure] false:
 * nothing is copied, the caller says why). Never throws for a malformed or unsupported URL.
 */
fun openExternal(
    context: Context,
    url: String,
    lightSurface: Color = ClineLightScheme.surface,
    darkSurface: Color = ClineDarkScheme.surface,
    copyOnFailure: Boolean = true,
): OpenExternalResult {
    val fallback = { if (copyOnFailure) copyAsFallback(context, url) else OpenExternalResult.NoBrowser }
    val uri = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return fallback()
    val web = uri.scheme?.lowercase() in WebSchemes
    if (!web) return if (view(context, uri, Intent.FLAG_ACTIVITY_NEW_TASK)) OpenExternalResult.Opened else fallback()
    val newTask = if (context is Activity) 0 else Intent.FLAG_ACTIVITY_NEW_TASK
    val customTab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
        .setColorSchemeParams(
            CustomTabsIntent.COLOR_SCHEME_LIGHT,
            CustomTabColorSchemeParams.Builder().setToolbarColor(lightSurface.toArgb()).build(),
        )
        .setColorSchemeParams(
            CustomTabsIntent.COLOR_SCHEME_DARK,
            CustomTabColorSchemeParams.Builder().setToolbarColor(darkSurface.toArgb()).build(),
        )
        .build()
    customTab.intent.addFlags(newTask)
    val launched = runCatching { customTab.launchUrl(context, uri); true }
        .recoverCatching { e -> if (e is ActivityNotFoundException) false else throw e }
        .getOrDefault(false)
    if (launched) return OpenExternalResult.Opened
    return if (view(context, uri, newTask)) OpenExternalResult.Opened else fallback()
}

private val WebSchemes = setOf("http", "https")

/** A plain `ACTION_VIEW` of [uri]; false when no activity takes it or it may not be started. */
private fun view(context: Context, uri: Uri, flags: Int): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(flags)
    return runCatching { context.startActivity(intent); true }
        .recoverCatching { e -> if (e is ActivityNotFoundException || e is SecurityException) false else throw e }
        .getOrDefault(false)
}

/** Alias kept for the onboarding spec's name (KS §0.7). */
fun openUrl(
    context: Context,
    url: String,
    lightSurface: Color = ClineLightScheme.surface,
    darkSurface: Color = ClineDarkScheme.surface,
): OpenExternalResult = openExternal(context, url, lightSurface, darkSurface)

private fun copyAsFallback(context: Context, url: String): OpenExternalResult {
    runCatching {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.components_clip_url), url))
    }
    return OpenExternalResult.NoBrowser
}

/**
 * The composable entry point every link button uses: `val open = rememberOpenExternal();
 * open(url)`. The toolbar colour for the active mode is the current scheme's `surface`
 * (dynamic colour included); the other mode uses the brand scheme's. On [OpenExternalResult.NoBrowser]
 * it shows "No browser found to open %1$s." on the screen's snackbar host.
 */
@Composable
fun rememberOpenExternal(): (String) -> Unit {
    val context = LocalContext.current
    val surface = MaterialTheme.colorScheme.surface
    val dark = ClineTheme.isDark
    val noBrowser = stringResource(R.string.components_no_browser)
    val urlLabel = stringResource(R.string.components_clip_url)
    val copier = rememberCopier()
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    return remember(context, copier, snackbar, scope, surface, dark, noBrowser, urlLabel) {
        { url: String ->
            val light = if (dark) ClineLightScheme.surface else surface
            val night = if (dark) surface else ClineDarkScheme.surface
            val result = openExternal(context, url, lightSurface = light, darkSurface = night, copyOnFailure = false)
            if (result == OpenExternalResult.NoBrowser) {
                // The URL goes to the clipboard and the snackbar says so on every API level.
                copier.copy(urlLabel, url)
                scope.launch { snackbar.showReplacing(noBrowser.format(url)) }
            }
        }
    }
}
