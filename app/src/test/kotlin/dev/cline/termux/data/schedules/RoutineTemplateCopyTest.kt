package dev.cline.termux.data.schedules

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The template titles and descriptions resolve to the desktop routine-templates.ts copy, verbatim. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoutineTemplateCopyTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `titles and descriptions match the desktop`() {
        val copy = ROUTINE_TEMPLATES.associate { it.id to (res.getString(it.title) to res.getString(it.description)) }
        assertEquals(
            mapOf(
                "find-critical-bugs" to (
                    "Find critical bugs" to
                        "Sweep recent commits for high-severity bugs that slipped past review, and fix the ones with a concrete trigger."
                    ),
                "security-scan" to (
                    "Scan for vulnerabilities" to
                        "Audit the codebase for exploitable security issues with a validated end-to-end attack path."
                    ),
                "daily-digest" to (
                    "Summarize changes daily" to
                        "Get a skimmable digest of everything that landed in the last 24 hours, plus risks worth watching."
                    ),
                "update-docs" to (
                    "Keep docs updated" to
                        "Refresh documentation whenever the code drifts away from it, verified against the source."
                    ),
            ),
            copy,
        )
    }

    @Test
    fun `schedule names stay data`() {
        assertEquals(
            listOf("Find critical bugs", "Security scan", "Daily change digest", "Update docs"),
            ROUTINE_TEMPLATES.map { it.name },
        )
    }
}
