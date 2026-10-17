// Adapted from cline-termux-voice (same owner): android/app/src/main/kotlin/dev/cline/voice/ui/theme/DynamicScheme.kt
package dev.cline.termux.ui.theme

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.annotation.ColorRes
import androidx.annotation.RequiresApi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.toArgb
import android.R.color as Stop

/*
 * The wallpaper palette as Android 12+ publishes it: five tonal ranges (accent1-3,
 * neutral1-2), thirteen stops each. A stop's number maps to an M3 tone as
 *
 *   _0 T100 · _10 T99 · _50 T95 · _100 T90 · _200 T80 · _300 T70 · _400 T60
 *   _500 T50 · _600 T40 · _700 T30 · _800 T20 · _900 T10 · _1000 T0
 *
 * Every role below is read from these stops, exactly as material3 1.3.1 does on API 31-33
 * (dynamicTonalPalette + dynamicLight/DarkColorScheme31). Surfaces and their text come from
 * the neutral-variant range, the way material3 builds them.
 */

/**
 * The Material You scheme built straight from the wallpaper palette stops.
 *
 * On API 34+ material3 reads the system's own role tokens instead, and One UI overrides
 * those: primary becomes a fixed #387AFF, onSurfaceVariant equals onSurface and the
 * surface containers fall out of order. The stops underneath are still the wallpaper's, so
 * building the roles from them keeps the colour dynamic while giving back true M3 tones.
 * (design-system.md §1.2: never call dynamicLightColorScheme / dynamicDarkColorScheme.)
 */
@RequiresApi(31)
internal fun paletteColorScheme(context: Context, dark: Boolean): ColorScheme {
    fun stop(@ColorRes id: Int) = Color(context.getColor(id))

    // Tones with no stop of their own: the neutral-variant T40 with its L* replaced, as
    // material3's setLuminance does. Its L*=0/100 special case never applies to these tones.
    val key = stop(Stop.system_neutral2_600).convert(ColorSpaces.CieLab)
    fun neutralVariant(tone: Float) =
        Color(tone, key.green, key.blue, 1f, ColorSpaces.CieLab).convert(ColorSpaces.Srgb)

    val scheme = if (dark) {
        darkColorScheme(
            primary = stop(Stop.system_accent1_200),
            onPrimary = stop(Stop.system_accent1_800),
            primaryContainer = stop(Stop.system_accent1_700),
            onPrimaryContainer = stop(Stop.system_accent1_100),
            inversePrimary = stop(Stop.system_accent1_600),
            secondary = stop(Stop.system_accent2_200),
            onSecondary = stop(Stop.system_accent2_800),
            secondaryContainer = stop(Stop.system_accent2_700),
            onSecondaryContainer = stop(Stop.system_accent2_100),
            tertiary = stop(Stop.system_accent3_200),
            onTertiary = stop(Stop.system_accent3_800),
            tertiaryContainer = stop(Stop.system_accent3_700),
            onTertiaryContainer = stop(Stop.system_accent3_100),
            background = neutralVariant(6f),
            onBackground = stop(Stop.system_neutral2_100),
            surface = neutralVariant(6f),
            onSurface = stop(Stop.system_neutral2_100),
            surfaceVariant = stop(Stop.system_neutral2_700),
            onSurfaceVariant = stop(Stop.system_neutral2_200),
            surfaceTint = stop(Stop.system_accent1_200),
            inverseSurface = stop(Stop.system_neutral2_100),
            inverseOnSurface = stop(Stop.system_neutral2_800),
            outline = stop(Stop.system_neutral2_400),
            outlineVariant = stop(Stop.system_neutral2_700),
            scrim = stop(Stop.system_neutral2_1000),
            surfaceBright = neutralVariant(24f),
            surfaceDim = neutralVariant(6f),
            surfaceContainerLowest = neutralVariant(4f),
            surfaceContainerLow = stop(Stop.system_neutral2_900),
            surfaceContainer = neutralVariant(12f),
            surfaceContainerHigh = neutralVariant(17f),
            surfaceContainerHighest = neutralVariant(22f),
        )
    } else {
        lightColorScheme(
            primary = stop(Stop.system_accent1_600),
            onPrimary = stop(Stop.system_accent1_0),
            primaryContainer = stop(Stop.system_accent1_100),
            onPrimaryContainer = stop(Stop.system_accent1_900),
            inversePrimary = stop(Stop.system_accent1_200),
            secondary = stop(Stop.system_accent2_600),
            onSecondary = stop(Stop.system_accent2_0),
            secondaryContainer = stop(Stop.system_accent2_100),
            onSecondaryContainer = stop(Stop.system_accent2_900),
            tertiary = stop(Stop.system_accent3_600),
            onTertiary = stop(Stop.system_accent3_0),
            tertiaryContainer = stop(Stop.system_accent3_100),
            onTertiaryContainer = stop(Stop.system_accent3_900),
            background = neutralVariant(98f),
            onBackground = stop(Stop.system_neutral2_900),
            surface = neutralVariant(98f),
            onSurface = stop(Stop.system_neutral2_900),
            surfaceVariant = stop(Stop.system_neutral2_100),
            onSurfaceVariant = stop(Stop.system_neutral2_700),
            surfaceTint = stop(Stop.system_accent1_600),
            inverseSurface = stop(Stop.system_neutral2_800),
            inverseOnSurface = stop(Stop.system_neutral2_50),
            outline = stop(Stop.system_neutral2_500),
            outlineVariant = stop(Stop.system_neutral2_200),
            scrim = stop(Stop.system_neutral2_1000),
            surfaceBright = neutralVariant(98f),
            surfaceDim = neutralVariant(87f),
            surfaceContainerLowest = stop(Stop.system_neutral2_0),
            surfaceContainerLow = neutralVariant(96f),
            surfaceContainer = neutralVariant(94f),
            surfaceContainerHigh = neutralVariant(92f),
            surfaceContainerHighest = stop(Stop.system_neutral2_100),
        )
    }
    if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) logScheme(scheme, dark)
    return scheme
}

/**
 * One logcat line per built scheme (debug builds only), so a device check can confirm the
 * roles without screenshots: `adb logcat -s Theme`.
 */
private fun logScheme(scheme: ColorScheme, dark: Boolean) {
    fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
    Log.d(
        "Theme",
        "palette scheme (${if (dark) "dark" else "light"}): " +
            "primary=${hex(scheme.primary)} " +
            "onSurface=${hex(scheme.onSurface)} " +
            "onSurfaceVariant=${hex(scheme.onSurfaceVariant)} " +
            "surface=${hex(scheme.surface)} " +
            "containerLow=${hex(scheme.surfaceContainerLow)} " +
            "container=${hex(scheme.surfaceContainer)} " +
            "containerHigh=${hex(scheme.surfaceContainerHigh)} " +
            "containerHighest=${hex(scheme.surfaceContainerHighest)}",
    )
}
