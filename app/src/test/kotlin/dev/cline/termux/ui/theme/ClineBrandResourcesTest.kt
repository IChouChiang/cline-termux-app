package dev.cline.termux.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The launcher grounds exist twice: as [ClineBrand] tokens for Compose and in `colors.xml` for the
 * adaptive icons and splash theme. They must not drift (DS §1.7, §10.6).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClineBrandResourcesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun resource(id: Int): Int = ContextCompat.getColor(context, id)

    @Test
    fun midnightLauncherGroundMatchesLogoInk() {
        assertEquals(ClineBrand.LogoInk.toArgb(), resource(R.color.ic_launcher_background))
    }

    @Test
    fun classicLauncherGroundMatchesAccent9() {
        assertEquals(ClineBrand.Accent9.toArgb(), resource(R.color.ic_launcher_background_classic))
    }

    @Test
    fun whatsNewHeroGlowReusesBrandViolet() {
        assertEquals(ClineBrand.Violet, ClineBrand.WhatsNewHeroGlowViolet)
    }

    @Test
    fun whatsNewHeroTitleIsOpaqueWhite() {
        assertEquals(Color.White, ClineBrand.WhatsNewHeroOnColor)
    }
}
