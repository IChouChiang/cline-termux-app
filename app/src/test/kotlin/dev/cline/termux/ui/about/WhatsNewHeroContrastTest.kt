package dev.cline.termux.ui.about

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.cline.termux.ui.theme.ClineDarkScheme
import dev.cline.termux.ui.theme.ClineLightScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C11 dynamic-colour hero (customize-and-rest.md §8.2): the full-opacity `onPrimaryContainer`
 * overline keeps 4.5:1 even at the primary glow's peak, and the title keeps 3:1 (large text) at
 * the tertiary glow's peak. The brand and baseline schemes stand in for wallpaper schemes with the
 * same tone pairs (container 90/30, on-container 10-30/90, accent 40/80).
 */
class WhatsNewHeroContrastTest {

    private val schemes = listOf(
        "brand light" to ClineLightScheme,
        "brand dark" to ClineDarkScheme,
        "baseline light" to lightColorScheme(),
        "baseline dark" to darkColorScheme(),
    )

    private fun contrast(a: Color, b: Color): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    private fun ColorScheme.under(glow: Color, alpha: Float): Color =
        glow.copy(alpha = alpha).compositeOver(primaryContainer)

    @Test
    fun overlineMeetsNormalTextContrastAtPrimaryGlowPeak() {
        schemes.forEach { (name, scheme) ->
            val ratio = contrast(scheme.onPrimaryContainer, scheme.under(scheme.primary, WhatsNewHeroDynamic.PRIMARY_GLOW_ALPHA))
            assertTrue("$name overline contrast $ratio < 4.5", ratio >= 4.5f)
        }
    }

    @Test
    fun overlineMeetsNormalTextContrastOnBareContainer() {
        schemes.forEach { (name, scheme) ->
            val ratio = contrast(scheme.onPrimaryContainer, scheme.primaryContainer)
            assertTrue("$name overline contrast $ratio < 4.5", ratio >= 4.5f)
        }
    }

    @Test
    fun titleMeetsLargeTextContrastAtTertiaryGlowPeak() {
        schemes.forEach { (name, scheme) ->
            val ratio = contrast(scheme.onPrimaryContainer, scheme.under(scheme.tertiary, WhatsNewHeroDynamic.TERTIARY_GLOW_ALPHA))
            assertTrue("$name title contrast $ratio < 3", ratio >= 3f)
        }
    }

    @Test
    fun theOldUncappedGlowFailedInDarkSchemes() {
        // Regression guard for A11Y-11: the former 0.55 glow with an 80 % overline fell below 4.5:1.
        val scheme = ClineDarkScheme
        val ratio = contrast(scheme.onPrimaryContainer.copy(alpha = 0.8f).compositeOver(scheme.under(scheme.primary, 0.55f)), scheme.under(scheme.primary, 0.55f))
        assertTrue(ratio < 4.5f)
    }
}
