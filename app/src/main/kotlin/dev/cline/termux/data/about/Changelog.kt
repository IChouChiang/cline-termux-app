package dev.cline.termux.data.about

import androidx.compose.runtime.Immutable

/*
 * Pure parts of About and What's new (design/screens/customize-and-rest.md §7, §8): port of
 * apps/examples/desktop-app/webview/lib/changelog.ts (`parseChangelog`, `releaseUrl`, the
 * link constants) and lib/app-channel.ts (`isBetaVersion`). The release notes source is the
 * app's own CHANGELOG.md bundled as an asset. Links point at the app's own repository
 * (CR OQ C-8), not at the cline-termux port.
 */

/** `ChangelogRelease`: one `## X.Y.Z` heading with its bullets (Markdown). */
@Immutable
data class ChangelogRelease(val version: String, val notes: List<String>)

/** About shows the newest releases only (`RECENT_RELEASE_COUNT`). */
const val RECENT_RELEASE_COUNT = 5

/** The bundled changelog asset (`assets/CHANGELOG.md`). */
const val CHANGELOG_ASSET = "CHANGELOG.md"

/** The app's repository (CR OQ C-8) for issues, releases and the changelog. */
object AppLinks {
    const val REPO_URL = "https://github.com/IChouChiang/cline-termux-app"
    const val ISSUES_URL = "$REPO_URL/issues/new"
    const val CHANGELOG_URL_ON_GITHUB = "$REPO_URL/releases"

    /**
     * App releases are tagged `vX.Y.Z` (prereleases `vX.Y.Z-pre.N`) in the app's own repository,
     * matching docs/release-notes/v<version>.md; the desktop needs `desktop-vX.Y.Z` only because
     * it shares the monorepo's tags.
     */
    fun releaseUrl(version: String): String = "$REPO_URL/releases/tag/v$version"
}

private val LINE_BREAK = Regex("\\r?\\n")
private val HEADING = Regex("^##\\s+(\\S+)\\s*$")
private val BULLET = Regex("^-\\s+")

/**
 * `parseChangelog`: `## X.Y.Z` headings with `- ` bullets below; wrapped or indented
 * continuation lines fold into the preceding bullet; versions without notes are dropped.
 */
fun parseChangelog(markdown: String): List<ChangelogRelease> {
    val releases = mutableListOf<Pair<String, MutableList<String>>>()
    var current: MutableList<String>? = null
    for (line in markdown.split(LINE_BREAK)) {
        val heading = HEADING.find(line)
        if (heading != null) {
            current = mutableListOf()
            releases += heading.groupValues[1] to current
            continue
        }
        val notes = current ?: continue
        if (BULLET.containsMatchIn(line)) {
            notes += line.replaceFirst(BULLET, "").trim()
        } else if (line.isNotBlank() && notes.isNotEmpty()) {
            notes[notes.lastIndex] = "${notes.last()}\n${line.trim()}"
        }
    }
    return releases.filter { it.second.isNotEmpty() }.map { ChangelogRelease(it.first, it.second.toList()) }
}

/** The About list: the newest [RECENT_RELEASE_COUNT] releases. */
fun recentReleases(releases: List<ChangelogRelease>): List<ChangelogRelease> = releases.take(RECENT_RELEASE_COUNT)

/** "Installed" badge rule: the release is the running app version. */
fun isInstalledRelease(release: ChangelogRelease, versionName: String): Boolean = release.version == versionName

/** `isBetaVersion`: prerelease builds carry `-beta` (the desktop's) or `-pre` (this app's). */
fun isBetaVersion(version: String?): Boolean = version != null && ("-beta" in version || "-pre" in version)
