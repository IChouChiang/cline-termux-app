package dev.cline.termux.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/** Cline brand fallback: seed #6E56CF (desktop accent-9), Radix-slate neutrals. See design-system.md §1.3. */
val ClineLightScheme = lightColorScheme(
    primary = Color(0xFF6249C2), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6DEFF), onPrimaryContainer = Color(0xFF4A2EA9),
    inversePrimary = Color(0xFFCBBEFF),
    secondary = Color(0xFF605B71), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6DFF9), onSecondaryContainer = Color(0xFF484458),
    tertiary = Color(0xFF7F4D7B), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD7F7), onTertiaryContainer = Color(0xFF653662),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF9F9FC), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFF9F9FC), onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFDEE3EA), onSurfaceVariant = Color(0xFF42474D),
    surfaceTint = Color(0xFF6249C2),
    inverseSurface = Color(0xFF2F3133), inverseOnSurface = Color(0xFFF0F0F3),
    outline = Color(0xFF72787E), outlineVariant = Color(0xFFC1C7CE), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFF9F9FC), surfaceDim = Color(0xFFD9DADD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF3F3F6),
    surfaceContainer = Color(0xFFEDEEF1), surfaceContainerHigh = Color(0xFFE8E8EB),
    surfaceContainerHighest = Color(0xFFE2E2E5),
)

val ClineDarkScheme = darkColorScheme(
    primary = Color(0xFFCBBEFF), onPrimary = Color(0xFF320A93),
    primaryContainer = Color(0xFF4A2EA9), onPrimaryContainer = Color(0xFFE6DEFF),
    inversePrimary = Color(0xFF6249C2),
    secondary = Color(0xFFCAC3DC), onSecondary = Color(0xFF322E41),
    secondaryContainer = Color(0xFF484458), onSecondaryContainer = Color(0xFFE6DFF9),
    tertiary = Color(0xFFF0B3E7), onTertiary = Color(0xFF4B1F4A),
    tertiaryContainer = Color(0xFF653662), onTertiaryContainer = Color(0xFFFFD7F7),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111416), onBackground = Color(0xFFE2E2E5),
    surface = Color(0xFF111416), onSurface = Color(0xFFE2E2E5),
    surfaceVariant = Color(0xFF42474D), onSurfaceVariant = Color(0xFFC1C7CE),
    surfaceTint = Color(0xFFCBBEFF),
    inverseSurface = Color(0xFFE2E2E5), inverseOnSurface = Color(0xFF2F3133),
    outline = Color(0xFF8C9198), outlineVariant = Color(0xFF42474D), scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF37393C), surfaceDim = Color(0xFF111416),
    surfaceContainerLowest = Color(0xFF0C0E11), surfaceContainerLow = Color(0xFF1A1C1E),
    surfaceContainer = Color(0xFF1E2022), surfaceContainerHigh = Color(0xFF282A2D),
    surfaceContainerHighest = Color(0xFF333537),
)

/**
 * Brand colours that exist outside the M3 roles (design-system.md §1.1, §1.7), the single
 * source for every brand literal outside the M3 schemes. The hero heading gradient and the
 * What's new hero use them with the brand scheme (under dynamic colour they fall back to
 * `primary` / `tertiary` / `primaryContainer`); the Appearance icon tiles use the launcher
 * grounds. `res/values/colors.xml` repeats [Accent9] and [LogoInk] only because the adaptive
 * launcher icons and the splash theme need colour resources; a unit test keeps them equal.
 */
object ClineBrand {
    /** Desktop `--brand-periwinkle`. */
    val Periwinkle = Color(0xFF8392F1)
    /** Desktop `--brand-violet` (light). */
    val Violet = Color(0xFF7C49E3)
    /** Desktop `--brand-violet` lightened for dark mode. */
    val VioletDark = Color(0xFFA486FD)
    /** Desktop `--brand-magenta`. */
    val Magenta = Color(0xFFE289DB)
    /** Desktop accent-9, the Classic launcher ground and the notification accent fallback. */
    val Accent9 = Color(0xFF6E56CF)
    /** `public/icon.svg` fill, the Midnight launcher ground. */
    val LogoInk = Color(0xFF1C1C24)

    /** What's new hero base (customize-and-rest.md §8.2), from the desktop `HERO_BACKGROUND` oklch literals. */
    val WhatsNewHeroBase = Color(0xFF2A1852)
    /** What's new hero first radial glow: the light brand violet. */
    val WhatsNewHeroGlowViolet = Violet
    /** What's new hero second radial glow. */
    val WhatsNewHeroGlowIndigo = Color(0xFF3F46AC)
    /** What's new hero overline text. */
    val WhatsNewHeroOverline = Color(0xFFEEC6FF)
    /** What's new hero title text. */
    val WhatsNewHeroOnColor = Color(0xFFFFFFFF)
}
