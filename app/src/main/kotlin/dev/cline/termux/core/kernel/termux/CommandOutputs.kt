package dev.cline.termux.core.kernel.termux

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Lenient JSON for CLI output and the small settings files the catalog reads. */
internal val CommandJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
}

/** `cline hub status` JSON (last stdout line; the command always prints JSON). */
@Serializable
data class HubStatusOutput(
    val running: Boolean = false,
    val url: String? = null,
    val pid: Int? = null,
    val startedAt: String? = null,
    val uptime: String? = null,
    val cliVersion: String? = null,
    val coreVersion: String? = null,
    val buildId: String? = null,
) {
    companion object {
        fun parse(stdout: String): HubStatusOutput? =
            stdout.lineSequence().lastOrNull { it.trim().startsWith("{") }?.let {
                runCatching { CommandJson.decodeFromString(serializer(), it.trim()) }.getOrNull()
            }
    }
}

/**
 * `$PREFIX/opt/cline-termux/current/VERSION`: `key=value` lines written by the port's
 * installer (`release=3.0.68-termux.2`, `tag=cli-v3.0.68`, `cline=3.0.68`).
 */
data class PortVersion(val release: String?, val cline: String?, val tag: String?) {
    val parsedRelease: PortRelease? get() = release?.let(PortRelease::parse)

    companion object {
        fun parse(stdout: String): PortVersion {
            val values = HashMap<String, String>()
            for (line in stdout.lineSequence()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val eq = trimmed.indexOf('=')
                if (eq <= 0) continue
                values[trimmed.substring(0, eq).trim()] = trimmed.substring(eq + 1).trim().trim('"')
            }
            // An installer that wrote only `tag=v3.0.68-termux.2` still names a release.
            val release = values["release"] ?: values["tag"]?.removePrefix("v")?.takeIf { PortRelease.parse(it) != null }
            return PortVersion(release = release, cline = values["cline"], tag = values["tag"])
        }
    }
}

/** `X.Y.Z-termux.N`: `X.Y.Z` compares as semver, then `N` numerically (KS §0.4). */
data class PortRelease(val major: Int, val minor: Int, val patch: Int, val revision: Int) : Comparable<PortRelease> {
    override fun compareTo(other: PortRelease): Int =
        compareValuesBy(this, other, PortRelease::major, PortRelease::minor, PortRelease::patch, PortRelease::revision)

    override fun toString(): String = "$major.$minor.$patch-termux.$revision"

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)-termux\.(\d+)$""")

        /** null when the text is not a port release (a `cline=` only VERSION counts as too old). */
        fun parse(text: String?): PortRelease? {
            val match = PATTERN.matchEntire(text?.trim().orEmpty()) ?: return null
            val (major, minor, patch, revision) = match.destructured
            return PortRelease(major.toInt(), minor.toInt(), patch.toInt(), revision.toInt())
        }

        /** `release` satisfies `minimum`; an unparsable release never does (KS §0.4 P3). */
        fun satisfies(release: String?, minimum: String?): Boolean {
            val have = parse(release) ?: return false
            val need = parse(minimum) ?: return false
            return have >= need
        }

        /** The basename of `readlink $PREFIX/opt/cline-termux/current` (CR §7.7). */
        fun fromReadlink(stdout: String): String? =
            stdout.trim().trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() }

        /**
         * The release a Hub daemon runs from, out of its argv one argument per line
         * (`ClineCommands.readHubCmdline`): the tree of the argument that ends in `/entry.js`. On the
         * Tab S7+ termux-exec puts the Bun path in twice, so its position varies. null when that
         * tree is not a release (`current/entry.js`) or no argument names one: unknown, never
         * "the installed one" (trial row 24).
         */
        fun fromHubCmdline(stdout: String): String? = stdout.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.endsWith("/entry.js") }
            ?.removeSuffix("/entry.js")
            ?.substringAfterLast('/')
            ?.takeIf { parse(it) != null }
    }
}

/**
 * The two fields the app keeps from `global-settings.json` (docs/07 §3.4); the rest of the
 * parsed object is discarded. A missing file or key means "default".
 */
data class GlobalSettingsView(val telemetryOptOut: Boolean?, val disabledTools: Set<String>) {
    fun isToolEnabled(name: String): Boolean = name !in disabledTools

    companion object {
        val DEFAULT = GlobalSettingsView(telemetryOptOut = null, disabledTools = emptySet())

        fun parse(json: String): GlobalSettingsView {
            val root = runCatching { CommandJson.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return DEFAULT
            val optOut = (root["telemetryOptOut"] as? JsonPrimitive)?.booleanOrNull
            val disabled = (root["disabledTools"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
            return GlobalSettingsView(telemetryOptOut = optOut, disabledTools = disabled)
        }

        /** Missing file = defaults; any other failure = defaults too (nothing here blocks a screen). */
        fun from(result: TermuxResult): GlobalSettingsView = if (result.ok) parse(result.stdout) else DEFAULT
    }
}

/** `tail -c <bytes> <path>` post-processing (CR §9.4, desktop `readTail`). */
object LogTail {
    /**
     * Drops the partial first line when the tail cut the file (the output is as long as
     * was asked for); a shorter output is the whole file and is kept intact.
     */
    fun clean(stdout: String, requestedBytes: Int): String {
        if (stdout.isEmpty()) return stdout
        val cut = stdout.toByteArray(Charsets.UTF_8).size >= requestedBytes
        if (!cut) return stdout
        val newline = stdout.indexOf('\n')
        return if (newline < 0) "" else stdout.substring(newline + 1)
    }
}

/** `find <dir> -maxdepth 1 -mindepth 1 -type d -not -name ".*"` parsed for the folder browser (SP S9.3). */
data class DirectoryListing(val directories: List<String>, val truncated: Boolean) {
    companion object {
        const val MAX_ENTRIES = 500

        fun from(result: TermuxResult): DirectoryListing {
            val lines = result.stdoutLines.filter { it.isNotBlank() }
            return DirectoryListing(
                directories = lines.take(MAX_ENTRIES),
                truncated = result.truncated || lines.size > MAX_ENTRIES,
            )
        }
    }
}

/** `git -C <ws> branch --show-current` interpretation (HC §2.5). */
sealed interface GitBranchProbe {
    data class Branch(val name: String) : GitBranchProbe
    data object Detached : GitBranchProbe
    data object NotARepository : GitBranchProbe
    data object GitMissing : GitBranchProbe
    data class Failed(val detail: String?) : GitBranchProbe

    companion object {
        fun from(result: TermuxResult): GitBranchProbe = when {
            result.executableFailed -> GitMissing
            result.internalError -> Failed(result.errmsg)
            result.exitCode == 128 -> NotARepository
            result.exitCode != 0 -> Failed(result.lastStderrLine)
            result.stdout.isBlank() -> Detached
            else -> Branch(result.stdout.trim())
        }
    }
}

/** `git -C <cwd> rev-parse --is-inside-work-tree` interpretation (CT C3.5). */
sealed interface GitWorkTreeProbe {
    data object InsideWorkTree : GitWorkTreeProbe
    data object NotARepository : GitWorkTreeProbe
    data object GitMissing : GitWorkTreeProbe
    data class Failed(val detail: String?) : GitWorkTreeProbe

    companion object {
        fun from(result: TermuxResult): GitWorkTreeProbe = when {
            result.executableFailed -> GitMissing
            result.internalError -> Failed(result.errmsg)
            result.exitCode == 128 -> NotARepository
            result.ok && result.stdout.trim() == "true" -> InsideWorkTree
            else -> Failed(result.lastStderrLine)
        }
    }
}
