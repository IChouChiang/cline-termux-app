package dev.cline.termux.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode

/** Settings > Appearance > theme: the desktop's single "Dark mode" switch plus "System default". */
enum class ThemeMode(val storageKey: String) {
    System("system"),
    Light("light"),
    Dark("dark"),
    ;

    companion object {
        fun fromStorage(value: String?): ThemeMode = entries.firstOrNull { it.storageKey == value } ?: System
    }
}

/** Settings > Appearance > App icon (desktop Settings > General > "App icon"), see §10.6. */
enum class AppIcon(val storageKey: String) {
    Midnight("midnight"),
    Classic("classic"),
    ;

    companion object {
        fun fromStorage(value: String?): AppIcon = entries.firstOrNull { it.storageKey == value } ?: Midnight
    }
}

/** Everything the theme needs from Settings > Appearance (DataStore `appearance.*`). */
@Immutable
data class AppearancePrefs(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    val fontSize: Int = FontSizeSetting.DEFAULT,
) {
    /** The light/dark decision for a given system setting. */
    fun isDark(systemDark: Boolean): Boolean = when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** True inside a dark [ClineTheme]; the activity reads it to colour the system bar icons. */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * The app theme (design-system.md §1–3, §6). On Android 12+ with "Dynamic colour" on, the
 * scheme comes from the wallpaper's tonal-palette stops ([paletteColorScheme]); otherwise,
 * and in every preview, the brand scheme ([ClineLightScheme]/[ClineDarkScheme]) is used.
 * The extended colours, the mono styles and the reduced-motion flag ride along as
 * composition locals, reachable through [ClineTheme] the object.
 */
@Composable
fun ClineTheme(
    appearance: AppearancePrefs = AppearancePrefs(),
    content: @Composable () -> Unit,
) {
    val dark = appearance.isDark(isSystemInDarkTheme())
    val context = LocalContext.current
    val preview = LocalInspectionMode.current
    val scheme = when {
        appearance.dynamicColor && !preview && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            remember(context, dark) { paletteColorScheme(context, dark) }
        dark -> ClineDarkScheme
        else -> ClineLightScheme
    }
    val scale = FontSizeSetting.scale(appearance.fontSize)
    val typography = remember(scale) { clineTypography(scale) }
    val mono = remember(scale) { clineMono(scale) }
    val colors = if (dark) ClineDarkColors else ClineLightColors
    val reducedMotion = if (preview) false else rememberReducedMotion()
    CompositionLocalProvider(
        LocalClineColors provides colors,
        LocalClineMono provides mono,
        LocalReducedMotion provides reducedMotion,
        LocalIsDarkTheme provides dark,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = ClineShapes, content = content)
    }
}

/** `ClineTheme.colors.success.dot`, `ClineTheme.mono.body`, `ClineTheme.reducedMotion`. */
object ClineTheme {
    val colors: ClineColors
        @Composable @ReadOnlyComposable get() = LocalClineColors.current
    val mono: ClineMonoStyles
        @Composable @ReadOnlyComposable get() = LocalClineMono.current
    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalIsDarkTheme.current
}

/** For `@Preview`s: the brand scheme, light or dark, default font size. */
@Composable
fun ClinePreviewTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    ClineTheme(
        appearance = AppearancePrefs(
            themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light,
            dynamicColor = false,
        ),
        content = content,
    )
}
