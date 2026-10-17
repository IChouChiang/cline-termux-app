package dev.cline.termux.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** The M3 scale tuning of `clineTypography` (DS §2, §8.2.6, §8.2.8). */
class ClineTypographyTest {

    private fun Typography.roles(): Map<String, TextStyle> = mapOf(
        "displayLarge" to displayLarge,
        "displayMedium" to displayMedium,
        "displaySmall" to displaySmall,
        "headlineLarge" to headlineLarge,
        "headlineMedium" to headlineMedium,
        "headlineSmall" to headlineSmall,
        "titleLarge" to titleLarge,
        "titleMedium" to titleMedium,
        "titleSmall" to titleSmall,
        "bodyLarge" to bodyLarge,
        "bodyMedium" to bodyMedium,
        "bodySmall" to bodySmall,
        "labelLarge" to labelLarge,
        "labelMedium" to labelMedium,
        "labelSmall" to labelSmall,
    )

    @Test
    fun everyRoleTakesItsDirectionFromTheFirstStrongCharacter() {
        for (scale in listOf(1f, FontSizeSetting.scale(FontSizeSetting.MAX))) {
            clineTypography(scale).roles().forEach { (name, style) ->
                assertEquals("$name at scale $scale", TextDirection.Content, style.textDirection)
            }
        }
    }

    @Test
    fun everyRoleHasZeroLetterSpacing() {
        clineTypography().roles().forEach { (name, style) ->
            assertEquals(name, 0.sp, style.letterSpacing)
        }
    }

    @Test
    fun titleAndLabelRolesUseNormalWeight() {
        val type = clineTypography()
        listOf(type.titleMedium, type.titleSmall, type.labelLarge, type.labelMedium, type.labelSmall).forEach {
            assertEquals(FontWeight.Normal, it.fontWeight)
        }
        assertEquals(FontWeight.SemiBold, type.displaySmall.fontWeight)
        assertEquals(FontWeight.SemiBold, type.headlineMedium.fontWeight)
    }

    @Test
    fun scaleMultipliesSizeAndLineHeight() {
        val base = Typography()
        val scale = FontSizeSetting.scale(18)
        val scaled = clineTypography(scale)
        assertEquals(base.bodyMedium.fontSize.value * scale, scaled.bodyMedium.fontSize.value, 0.001f)
        assertEquals(base.bodyMedium.lineHeight.value * scale, scaled.bodyMedium.lineHeight.value, 0.001f)
    }

    @Test
    fun monoStylesAreNotContentDirected() {
        // Code, commands and paths are forced LTR by LtrText / LtrBlock, never resolved from content.
        val mono = clineMono()
        listOf(mono.body, mono.label, mono.small, mono.tiny).forEach {
            assertNotEquals(TextDirection.Content, it.textDirection)
        }
    }
}
