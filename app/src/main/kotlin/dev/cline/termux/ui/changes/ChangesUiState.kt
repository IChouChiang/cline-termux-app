package dev.cline.termux.ui.changes

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.diff.SessionDiffSummary
import dev.cline.termux.data.diff.SessionFileDiff

/** Desktop hunk key: path + index (hunks never reorder within a file). */
@Immutable
data class HunkKey(val path: String, val index: Int)

/**
 * CT A.8 `ChangesUiState`. File toggles are kept as the **collapsed** set, because the
 * desktop default is "every file expanded" and a file that appears later must open expanded
 * too; [expanded] is the spec's view of the same state.
 */
@Immutable
data class ChangesUiState(
    val sessionId: String,
    /** The session title for the share subject; null = the desktop fallback "New Session". */
    val sessionTitle: String? = null,
    val kernel: KernelConnectionVisual = KernelConnectionVisual.Connected,
    val content: Content = Content.Loading,
    /** `snapshot.status` running or pending (A.5 "Running / streaming"). */
    val isSessionBusy: Boolean = false,
    val collapsed: Set<String> = emptySet(),
    /** The path whose copy button shows the check; clears after 1600 ms (desktop). */
    val copiedPath: String? = null,
    /** Two-pane layout only; null = the first file. */
    val selectedPath: String? = null,
    /** Rows shown per hunk beyond the default 400 ("Show N more lines"). */
    val shownLinesPerHunk: Map<HunkKey, Int> = emptyMap(),
) {
    @Immutable
    sealed interface Content {
        data object Loading : Content

        data class Ready(
            val files: List<SessionFileDiff>,
            val summary: SessionDiffSummary,
            /** Paths whose counts changed in the last update (the header highlight). */
            val changedPaths: Set<String>,
            val anyTruncated: Boolean,
            /** Store update time; keys the header highlight so each update flashes once. */
            val updatedAt: Long,
        ) : Content

        /** `session.messages` failed; [message] is kernel text. */
        data class Failed(val message: String) : Content

        /** Kernel down and nothing cached (A.5 "Offline / kernel down, nothing cached"). */
        data object Unavailable : Content
    }

    val files: List<SessionFileDiff> get() = (content as? Content.Ready)?.files.orEmpty()

    /** Display paths of the expanded files (default: all). */
    val expanded: Set<String> get() = files.mapTo(LinkedHashSet()) { it.path }.apply { removeAll(collapsed) }

    fun isExpanded(path: String): Boolean = path !in collapsed

    /** "Collapse all" while any file is expanded, else "Expand all" (A.4). */
    val anyExpanded: Boolean get() = files.any { it.path !in collapsed }

    /** The file shown in the two-pane detail. */
    val selectedFile: SessionFileDiff? get() = files.firstOrNull { it.path == selectedPath } ?: files.firstOrNull()

    /** Badge and actions only once the content is populated (A.4: hidden while loading and empty). */
    val hasFiles: Boolean get() = files.isNotEmpty()

    fun shownRows(key: HunkKey): Int = shownLinesPerHunk[key] ?: PAGE_ROWS

    companion object {
        /** Long hunks show 400 rows at first, then 400 more per tap (A.4). */
        const val PAGE_ROWS = 400
    }
}

/** One-off effects the Changes composable performs. */
sealed interface ChangesEffect {
    /** Pop the route / close the pane: the summary dropped to zero, or the session was deleted (A.3). */
    data object Close : ChangesEffect

    /** Write [text] (the absolute Termux path) to the clipboard, then report back with `onCopied`/`onCopyFailed`. */
    data class Copy(val path: String, val text: String) : ChangesEffect

    /** Open the share sheet with unified-diff [text]; [sessionTitle] null = "New Session". */
    data class Share(val text: String, val fileName: String, val sessionTitle: String?) : ChangesEffect

    /** TalkBack polite "Changes updated: N files" (at most once per 5 s). */
    data class Announce(val fileCount: Int) : ChangesEffect
}
