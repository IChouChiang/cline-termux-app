package dev.cline.termux.data.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from desktop changelog.test.ts plus the version helpers. */
class ChangelogTest {
    @Test fun `parses versions with bullets and folds wrapped lines`() {
        val markdown = "# Changelog\r\n\r\n## 1.2.0\n\n- Added A\n  continued here\n- Fixed B\n\n## 1.1.0\n\n## 1.0.0\n- First\n"
        assertEquals(
            listOf(
                ChangelogRelease("1.2.0", listOf("Added A\ncontinued here", "Fixed B")),
                ChangelogRelease("1.0.0", listOf("First")),
            ),
            parseChangelog(markdown),
        )
        assertEquals(emptyList<ChangelogRelease>(), parseChangelog("no headings\n- stray"))
    }

    @Test fun `recent releases and the installed marker`() {
        val releases = (1..7).map { ChangelogRelease("1.0.$it", listOf("n")) }
        assertEquals(RECENT_RELEASE_COUNT, recentReleases(releases).size)
        assertTrue(isInstalledRelease(releases[0], "1.0.1"))
        assertFalse(isInstalledRelease(releases[0], "1.0.2"))
    }

    @Test fun `beta versions and links`() {
        assertTrue(isBetaVersion("0.3.0-beta.2"))
        assertTrue(isBetaVersion("0.1.0-pre.1"))
        assertFalse(isBetaVersion("0.3.0"))
        assertFalse(isBetaVersion(null))
        assertEquals("https://github.com/IChouChiang/cline-termux-app/releases/tag/v0.1.0-pre.1", AppLinks.releaseUrl("0.1.0-pre.1"))
        assertEquals("https://github.com/IChouChiang/cline-termux-app/issues/new", AppLinks.ISSUES_URL)
    }
}
