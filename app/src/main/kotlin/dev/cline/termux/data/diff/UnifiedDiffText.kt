package dev.cline.termux.data.diff

/**
 * Shareable unified-diff text (CT A.7 "Share diff" / "Share all changes"). Per file:
 *
 * ```
 * --- a/<display path>
 * +++ b/<display path>
 * @@ -<oldStart>,<oldCount> +<newStart>,<newCount> @@
 * -<old line>
 * +<new line>
 * ```
 *
 * Counts come from the [LineDiff] result of each hunk, context lines are prefixed with a
 * space, and fragments keep their stored starts. A file without hunks emits its two header
 * lines and `# <noHunksNote>` (the caller passes the localised "No hunk details available.").
 * Identical hunks are skipped, as the screen renders nothing for them. Lines are never
 * clipped here: the share text keeps very long lines whole (CT A.9).
 */
object UnifiedDiffText {
    fun forSession(files: List<SessionFileDiff>, noHunksNote: String): String =
        buildString { files.forEach { appendFile(it, noHunksNote) } }

    fun forFile(file: SessionFileDiff, noHunksNote: String): String = buildString { appendFile(file, noHunksNote) }

    /** One `@@` block, or null for an identical hunk. */
    fun forHunk(hunk: SessionDiffHunk): String? {
        val diff = LineDiff.forHunk(hunk) ?: return null
        return buildString { appendHunk(diff) }
    }

    private fun StringBuilder.appendFile(file: SessionFileDiff, noHunksNote: String) {
        append("--- a/").append(file.path).append('\n')
        append("+++ b/").append(file.path).append('\n')
        if (file.hunks.isEmpty()) {
            append("# ").append(noHunksNote).append('\n')
            return
        }
        for (hunk in file.hunks) {
            val diff = LineDiff.forHunk(hunk) ?: continue
            appendHunk(diff)
        }
    }

    private fun StringBuilder.appendHunk(diff: HunkDiff) {
        append("@@ -").append(diff.oldStart).append(',').append(diff.oldCount)
            .append(" +").append(diff.newStart).append(',').append(diff.newCount).append(" @@\n")
        for (line in diff.lines) {
            append(
                when (line.kind) {
                    DiffLineKind.Context -> ' '
                    DiffLineKind.Removed -> '-'
                    DiffLineKind.Added -> '+'
                },
            )
            append(line.text).append('\n')
        }
    }
}
