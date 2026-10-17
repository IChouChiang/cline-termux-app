package dev.cline.termux.data.chat

import androidx.collection.LruCache
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** Why the mention panel shows a notice instead of files (C6.4). */
enum class MentionProblem {
    /** Home with "Just chat": "Choose a workspace to mention files." + "Choose". */
    NoWorkspace,

    /** "Start kernel to search files." */
    KernelDown,

    /** RUN_COMMAND not granted or allow-external-apps off: "Cline can't run commands in Termux yet." + "Allow". */
    PermissionMissing,

    /** Exit ≠ 0 or the 10 s timeout: "Could not search files." */
    Error,
}

sealed interface MentionResult {
    data class Files(val paths: List<String>) : MentionResult
    data class Failed(val problem: MentionProblem) : MentionResult
}

/** Ports of the desktop mention token helpers (`chat-input-bar.tsx`). */
object MentionTokens {
    /** `getActiveMention`: `@` at the start or after whitespace, no whitespace or second `@` in the token. */
    fun getActiveMention(input: String, cursor: Int): ActiveMention? {
        if (cursor < 0 || cursor > input.length) return null
        val left = input.substring(0, cursor)
        val atIndex = left.lastIndexOf('@')
        if (atIndex == -1) return null
        if (atIndex > 0 && !left[atIndex - 1].isJsWhitespace()) return null
        val body = left.substring(atIndex + 1)
        if (body.any { it.isJsWhitespace() || it == '@' }) return null
        return ActiveMention(atIndex, cursor, body)
    }

    /** `insertMentionFile`: `@<path> ` replaces the token; the cursor lands after the space. */
    fun insertMentionFile(input: String, active: ActiveMention, path: String): TextEdit {
        val end = active.end.coerceIn(active.start, input.length)
        val next = input.substring(0, active.start) + "@" + path + " " + input.substring(end)
        return TextEdit(next, active.start + path.length + 2)
    }

    private fun Char.isJsWhitespace(): Boolean = isWhitespace() || this == '\u00A0' || this == '\uFEFF'
}

/** C6.6 ranking, applied after the command returns. */
object MentionRanking {
    const val LIMIT = 10

    /**
     * Exact basename > basename starts with > basename contains > path contains; ties by the
     * shorter path, then alphabetically. An empty query takes the first [limit] by depth, then name.
     */
    fun rank(paths: List<String>, query: String, limit: Int = LIMIT): List<String> {
        val unique = paths.map { it.trim().removePrefix("./") }.filter { it.isNotEmpty() }.distinct()
        val q = query.lowercase(Locale.ROOT)
        if (q.isEmpty()) {
            return unique.sortedWith(compareBy<String> { depth(it) }.thenBy { it }).take(limit)
        }
        return unique
            .mapNotNull { path -> tier(path, q)?.let { path to it } }
            .sortedWith(compareBy<Pair<String, Int>> { it.second }.thenBy { it.first.length }.thenBy { it.first })
            .map { it.first }
            .take(limit)
    }

    fun basename(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun depth(path: String): Int = path.count { it == '/' }

    private fun tier(path: String, q: String): Int? {
        val base = basename(path).lowercase(Locale.ROOT)
        return when {
            base == q -> 0
            base.startsWith(q) -> 1
            base.contains(q) -> 2
            path.lowercase(Locale.ROOT).contains(q) -> 3
            else -> null
        }
    }
}

/**
 * Workspace file search for `@` mentions (C6.6): RUN_COMMAND `rg --files` (or `find`)
 * filtered per query inside Termux, ranked here, cached in an `LruCache(64)` keyed by
 * `(workspaceRoot, query)`. A search outlives the composer that asked for it, so a newer
 * query only stops the wait and the older result still lands in the cache. The cache is
 * cleared when the workspace changes and by [clear] after a turn ran `editor`,
 * `apply_patch` or `run_commands` (docs/07 §4.2).
 */
class MentionSearch(
    private val termux: TermuxExecutor,
    /** Where searches run (appScope); a waiter's cancellation does not cancel the command. */
    private val scope: CoroutineScope,
) {
    private data class Key(val workspaceRoot: String, val query: String)

    private val lock = Any()
    private val cache = LruCache<Key, List<String>>(CACHE_SIZE)
    private val inFlight = HashMap<Key, Deferred<MentionResult>>()
    private var lastRoot: String? = null

    /** The cached ranked paths for this query, or null. */
    fun cached(workspaceRoot: String, query: String): List<String>? = synchronized(lock) {
        noteRoot(workspaceRoot)
        cache[Key(workspaceRoot, query)]
    }

    /** Ranked paths (≤ 10) or a problem; successes are cached, failures are not. */
    suspend fun search(workspaceRoot: String, query: String): MentionResult {
        val key = Key(workspaceRoot, query)
        val deferred = synchronized(lock) {
            noteRoot(workspaceRoot)
            cache[key]?.let { return MentionResult.Files(it) }
            inFlight.getOrPut(key) { scope.async { runSearch(key) } }
        }
        return deferred.await()
    }

    fun clear() = synchronized(lock) { cache.evictAll() }

    private fun noteRoot(workspaceRoot: String) {
        if (lastRoot != null && lastRoot != workspaceRoot) cache.evictAll()
        lastRoot = workspaceRoot
    }

    private suspend fun runSearch(key: Key): MentionResult {
        val outcome = try {
            val result = termux.run(ClineCommands.searchWorkspaceFiles(key.workspaceRoot, key.query))
            if (result.ok) {
                MentionResult.Files(MentionRanking.rank(result.stdout.lines(), key.query))
            } else {
                MentionResult.Failed(MentionProblem.Error)
            }
        } catch (e: CancellationException) {
            synchronized(lock) { inFlight.remove(key) }
            throw e
        } catch (e: TermuxUnavailable) {
            MentionResult.Failed(problemOf(e))
        } catch (e: Exception) {
            MentionResult.Failed(MentionProblem.Error)
        }
        synchronized(lock) {
            inFlight.remove(key)
            if (outcome is MentionResult.Files && lastRoot == key.workspaceRoot) cache.put(key, outcome.paths)
        }
        return outcome
    }

    companion object {
        const val CACHE_SIZE = 64

        /** Desktop debounce before a search starts. */
        const val DEBOUNCE_MS = 120L

        fun problemOf(error: TermuxUnavailable): MentionProblem = when (error) {
            is TermuxUnavailable.PermissionMissing, is TermuxUnavailable.ExternalAppsBlocked -> MentionProblem.PermissionMissing
            is TermuxUnavailable.TimedOut -> MentionProblem.Error
            else -> MentionProblem.KernelDown
        }
    }
}
