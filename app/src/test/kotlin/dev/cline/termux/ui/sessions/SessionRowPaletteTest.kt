package dev.cline.termux.ui.sessions

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.cline.termux.ui.theme.ClineDarkScheme
import dev.cline.termux.ui.theme.ClineLightScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sessions-screen row colours (DS §1.8, §8.1.9): every text colour is the listed pair of
 * its container, so the active session's lines read in `onSecondaryContainer`, never in
 * `onSurface` / `onSurfaceVariant` on `secondaryContainer`.
 */
class SessionRowPaletteTest {

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

    @Test fun `selected row pairs secondaryContainer with onSecondaryContainer for every line`() {
        schemes.forEach { (name, scheme) ->
            val palette = sessionRowPalette(scheme, selected = true, menuOpen = false)
            assertEquals(name, scheme.secondaryContainer, palette.container)
            assertEquals(name, scheme.onSecondaryContainer, palette.content)
            assertEquals(name, scheme.onSecondaryContainer, palette.muted)
        }
    }

    @Test fun `unselected row sits on surface with onSurface and onSurfaceVariant`() {
        schemes.forEach { (name, scheme) ->
            val palette = sessionRowPalette(scheme, selected = false, menuOpen = false)
            assertEquals(name, scheme.surface, palette.container)
            assertEquals(name, scheme.onSurface, palette.content)
            assertEquals(name, scheme.onSurfaceVariant, palette.muted)
        }
    }

    @Test fun `open menu lifts the row to surfaceContainerHighest even when selected`() {
        schemes.forEach { (name, scheme) ->
            listOf(true, false).forEach { selected ->
                val palette = sessionRowPalette(scheme, selected = selected, menuOpen = true)
                assertEquals(name, scheme.surfaceContainerHighest, palette.container)
                assertEquals(name, scheme.onSurface, palette.content)
                assertEquals(name, scheme.onSurfaceVariant, palette.muted)
            }
        }
    }

    @Test fun `every palette keeps body text at 4_5 to 1`() {
        schemes.forEach { (name, scheme) ->
            listOf(false to false, true to false, false to true, true to true).forEach { (selected, menuOpen) ->
                val palette = sessionRowPalette(scheme, selected, menuOpen)
                val label = "$name selected=$selected menuOpen=$menuOpen"
                assertTrue("$label content", contrast(palette.content, palette.container) >= 4.5f)
                assertTrue("$label muted", contrast(palette.muted, palette.container) >= 4.5f)
            }
        }
    }
}
