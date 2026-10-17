package dev.cline.termux.ui.theme

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import dev.cline.termux.R

/*
 * Typography (design-system.md §2). Text uses the system face with the M3 scale and the
 * voice app's two CJK changes: letter spacing 0 everywhere (M3 tracking spreads Hanzi
 * apart) and Normal instead of Medium on the title/label styles (Samsung's CJK fallback
 * has no Medium face, so Latin digits would render Medium beside Regular Hanzi). Emphasis
 * uses SemiBold, which both scripts have. Code and numbers use Geist Mono.
 */

/** Desktop `lib/app-font-size.ts`: sizes 12–20, default 15, applied as a multiplier. */
object FontSizeSetting {
    const val MIN = 12
    const val MAX = 20
    const val DEFAULT = 15

    fun clamp(size: Int): Int = size.coerceIn(MIN, MAX)

    /** The multiplier applied to every type size and line height (0.8–1.333). */
    fun scale(size: Int): Float = clamp(size) / DEFAULT.toFloat()
}

/**
 * Geist Mono (SIL OFL 1.1) 400 and 500, bundled as static TTFs. If the resources were
 * ever stripped from a build the font loader would throw at first use, so the family is
 * resolved once here; CJK inside code falls back to the system CJK face automatically.
 */
val ClineMonoFamily: FontFamily = runCatching {
    FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
    )
}.getOrDefault(FontFamily.Monospace)

/** The four monospace styles (§2.2). */
@Immutable
class ClineMonoStyles(
    /** Code blocks, command output, approval detail, tool details, diff lines. 13/20. */
    val body: TextStyle,
    /** `code: true` parts inside a tool label; the row itself stays `bodyMedium`. 13/20. */
    val label: TextStyle,
    /** `+N -N`, token and cost values, model ids, paths in Changes headers. 12/16. */
    val small: TextStyle,
    /** Diff gutter line numbers, letter keys in ask-question. 11/16. */
    val tiny: TextStyle,
)

fun clineMono(scale: Float = 1f): ClineMonoStyles {
    fun style(size: Int, line: Int) = TextStyle(
        fontFamily = ClineMonoFamily,
        fontWeight = FontWeight.Normal,
        fontSize = (size * scale).sp,
        lineHeight = (line * scale).sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "calt 0",
        platformStyle = PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    return ClineMonoStyles(
        body = style(13, 20),
        label = style(13, 20),
        small = style(12, 16),
        tiny = style(11, 16),
    )
}

val LocalClineMono = staticCompositionLocalOf { clineMono() }

/** Access point for the mono styles, scaled by the font size setting: `ClineMono.body`. */
object ClineMono {
    val body: TextStyle
        @Composable @ReadOnlyComposable get() = LocalClineMono.current.body
    val label: TextStyle
        @Composable @ReadOnlyComposable get() = LocalClineMono.current.label
    val small: TextStyle
        @Composable @ReadOnlyComposable get() = LocalClineMono.current.small
    val tiny: TextStyle
        @Composable @ReadOnlyComposable get() = LocalClineMono.current.tiny
}

/**
 * The M3 scale with the CJK changes, every size and line height multiplied by [scale].
 *
 * Every role uses [TextDirection.Content]: the paragraph direction comes from the first
 * strong character (ContentOrLtr / ContentOrRtl), not from `LocalLayoutDirection`. Under an
 * RTL locale the layout mirrors but Latin and CJK text (model output, user text, kernel
 * text, untranslated copy) keeps its own order (DS §8.2.6, §8.2.8). Code, commands, paths
 * and diff lines are not content-directed: they go through `LtrText` / `LtrBlock`, which
 * force [TextDirection.Ltr].
 */
fun clineTypography(scale: Float = 1f): Typography = Typography().run {
    copy(
        displayLarge = displayLarge.tuned(scale),
        displayMedium = displayMedium.tuned(scale),
        displaySmall = displaySmall.tuned(scale, FontWeight.SemiBold),
        headlineLarge = headlineLarge.tuned(scale),
        headlineMedium = headlineMedium.tuned(scale, FontWeight.SemiBold),
        headlineSmall = headlineSmall.tuned(scale),
        titleLarge = titleLarge.tuned(scale),
        titleMedium = titleMedium.tuned(scale, FontWeight.Normal),
        titleSmall = titleSmall.tuned(scale, FontWeight.Normal),
        bodyLarge = bodyLarge.tuned(scale),
        bodyMedium = bodyMedium.tuned(scale),
        bodySmall = bodySmall.tuned(scale),
        labelLarge = labelLarge.tuned(scale, FontWeight.Normal),
        labelMedium = labelMedium.tuned(scale, FontWeight.Normal),
        labelSmall = labelSmall.tuned(scale, FontWeight.Normal),
    )
}

private fun TextStyle.tuned(scale: Float, weight: FontWeight? = null): TextStyle = copy(
    fontSize = fontSize.scaled(scale),
    lineHeight = lineHeight.scaled(scale),
    letterSpacing = 0.sp,
    fontWeight = weight ?: fontWeight,
    textDirection = TextDirection.Content,
)

private fun TextUnit.scaled(scale: Float): TextUnit =
    if (isSpecified && scale != 1f) this * scale else this

/**
 * The scale under a mirrored (RTL) layout: Latin and CJK lines keep their own order and sit at
 * the left edge, the Arabic line flows right to left. Sample text, not app copy.
 */
@Preview(name = "RTL light", showBackground = false)
@Preview(name = "RTL dark", showBackground = false, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun TypographyRtlPreview() {
    ClinePreviewTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface {
                Column(Modifier.fillMaxWidth().padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    Text("Here is the summary.", style = MaterialTheme.typography.bodyLarge)
                    Text("+1 -1 across 2 files", style = MaterialTheme.typography.bodyMedium)
                    Text("这是摘要。", style = MaterialTheme.typography.bodyMedium)
                    Text("مرحبا بالعالم.", style = MaterialTheme.typography.bodyMedium)
                    Text("New Session", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}
