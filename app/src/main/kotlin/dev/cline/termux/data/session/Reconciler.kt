package dev.cline.termux.data.session

import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.chat.isReasoningOnlyAssistantMessage
import dev.cline.termux.data.chat.isSystemSteeringMessage

/**
 * Canonical ∪ live (home-chat.md §1.3.3). Live ids (`local:`, `a:`, `r:`, `t:`) and history
 * ids (`h:`, the daemon's message ids, `t:`) share only `t:`, so reconciliation replaces a
 * closed turn's live rows with the history slice the daemon appended and hands each matched
 * canonical row the live row's `renderKey`: lazy-list keys, saveable expansion state and the
 * work-row id survive the swap, and nothing flickers or jumps.
 */
object Reconciler {

    data class Result(val canonical: List<ChatMessage>, val canonicalCount: Int, val live: List<ChatMessage>)

    private val TRANSCRIPT_ROLES = setOf(Role.User, Role.Assistant, Role.Tool)

    /**
     * Rendered messages: canonical rows, then live transcript rows, with live-only rows (error,
     * status, system: never in history) placed by `createdAt` (ties go after). A live row of a
     * still-open turn that history already holds (a `t:` row) is fresher and replaces it in place.
     */
    fun merge(canonical: List<ChatMessage>, live: List<ChatMessage>): List<ChatMessage> {
        if (live.isEmpty()) return canonical
        val liveById = live.associateBy { it.id }
        val out = ArrayList<ChatMessage>(canonical.size + live.size)
        val replaced = HashSet<String>()
        for (row in canonical) {
            val fresher = liveById[row.id]
            if (fresher != null) {
                out += fresher.copy(renderKey = row.renderKey)
                replaced += row.id
            } else {
                out += row
            }
        }
        val liveOnly = ArrayList<ChatMessage>()
        for (row in live) {
            if (row.id in replaced) continue
            if (row.role in TRANSCRIPT_ROLES) out += row else liveOnly += row
        }
        for (row in liveOnly) {
            val at = out.indexOfFirst { it.createdAt > row.createdAt }
            if (at < 0) out += row else out.add(at, row)
        }
        return out
    }

    /**
     * Replaces the canonical rows with [mapping] (a fresh `session.messages` reply) and drops from
     * [live] every user/assistant/tool row that does not belong to a still-open turn
     * ([openRequestIds]). Error and status rows stay. Opening a session is the same operation
     * with an empty live list (§1.3.3 step 6).
     *
     * A stopped or interrupted turn ([partialRequestIds]) is the exception: the daemon does not
     * persist the text it was streaming when the run was cancelled, so the turn's live rows that
     * found no history counterpart move into the canonical list at the end of their turn instead
     * of disappearing (§3.16 "the partial rows stay"). They keep their `meta.requestId`, which
     * history rows never carry, and later reconciliations keep them after the same predecessor.
     */
    fun reconcile(
        previousCanonical: List<ChatMessage>,
        previousCanonicalCount: Int,
        live: List<ChatMessage>,
        mapping: HistoryMapping,
        openRequestIds: Set<String>,
        partialRequestIds: Set<String> = emptySet(),
    ): Result {
        val closed = live.filter { it.role in TRANSCRIPT_ROLES && it.meta.requestId !in openRequestIds }
        val closedIds = closed.mapTo(HashSet()) { it.id }

        // Turn slice: everything the daemon appended since the last reconciliation (also TUI turns).
        val slice = mapping.messages.indices.filter { mapping.rawIndex[it] >= previousCanonicalCount }.map { mapping.messages[it] }
        val alias = HashMap<String, String>() // canonical id → renderKey to inherit

        // Tools map by identical `t:` id.
        val closedTools = closed.filter { it.role == Role.Tool }.associateBy { it.id }
        for (row in slice) if (row.role == Role.Tool) closedTools[row.id]?.let { alias[row.id] = it.renderKey }
        // The k-th closed local user row ↔ the k-th genuine user text row of the slice, and the k-th
        // live assistant/reasoning row ↔ the k-th assistant text or reasoning-only row. Counted from
        // the end: a turn started elsewhere earlier in the slice has no live rows to pair with.
        val liveUsers = closed.filter { it.role == Role.User }
        val sliceUsers = slice.filter { it.role == Role.User && !isSystemSteeringMessage(it) }
        liveUsers.asReversed().zip(sliceUsers.asReversed()).forEach { (liveRow, canonicalRow) -> alias[canonicalRow.id] = liveRow.renderKey }
        val liveAssistant = closed.filter { it.role == Role.Assistant }
        val sliceAssistant = slice.filter {
            it.role == Role.Assistant && (it.content.isNotBlank() || isReasoningOnlyAssistantMessage(it) || it.media.isNotEmpty() || it.images.isNotEmpty())
        }
        liveAssistant.asReversed().zip(sliceAssistant.asReversed()).forEach { (liveRow, canonicalRow) -> alias[canonicalRow.id] = liveRow.renderKey }

        // A run failure is persisted too (`displayRole: "error"`, HistoryMapper): the live `e:` row of a
        // closed turn hands its key to the k-th history error row from the end and leaves live
        // (desktop `canReplaceFailedTurn`). Interrupted rows are never in history and stay.
        val liveErrors = live.filter { it.role == Role.Error && it.id.startsWith("e:") && it.meta.requestId !in openRequestIds }
        val sliceErrors = slice.filter { it.role == Role.Error }
        val replacedErrors = HashSet<String>()
        liveErrors.asReversed().zip(sliceErrors.asReversed()).forEach { (liveRow, canonicalRow) ->
            alias[canonicalRow.id] = liveRow.renderKey
            replacedErrors += liveRow.id
        }

        // Rows that were already canonical keep the key they had (an earlier alias must not reset).
        val previousKeys = previousCanonical.associate { it.id to it.renderKey }
        val canonical = mapping.messages.mapTo(ArrayList(mapping.messages.size)) { row ->
            val key = alias[row.id] ?: previousKeys[row.id]
            if (key != null && key != row.renderKey) row.copy(renderKey = key) else row
        }
        val historyIds = mapping.messages.mapTo(HashSet()) { it.id }
        keepCarriedPartials(previousCanonical, historyIds, canonical)
        if (partialRequestIds.isNotEmpty()) {
            val inherited = alias.values.toHashSet()
            val partial = closed.filter { it.meta.requestId in partialRequestIds && it.renderKey !in inherited && it.id !in historyIds }
            insertPartials(partial, closed, canonical)
        }
        val remainingLive = live.filter { it.id !in closedIds && it.id !in replacedErrors }
        return Result(canonical, mapping.rawCount, remainingLive)
    }

    /** Partial rows kept by an earlier reconciliation stay right after the row they followed. */
    private fun keepCarriedPartials(previousCanonical: List<ChatMessage>, historyIds: Set<String>, canonical: MutableList<ChatMessage>) {
        previousCanonical.forEachIndexed { index, row ->
            if (row.meta.requestId == null || row.id in historyIds) return@forEachIndexed
            var at = 0
            for (j in index - 1 downTo 0) {
                val found = canonical.indexOfFirst { it.id == previousCanonical[j].id }
                if (found >= 0) {
                    at = found + 1
                    break
                }
            }
            canonical.add(at, row)
        }
    }

    /** Unmatched rows of a stopped turn go after the last history row of that turn (or at the end). */
    private fun insertPartials(partial: List<ChatMessage>, closed: List<ChatMessage>, canonical: MutableList<ChatMessage>) {
        for ((requestId, rows) in partial.groupBy { it.meta.requestId }) {
            val userKey = closed.firstOrNull { it.role == Role.User && it.meta.requestId == requestId }?.renderKey
            val anchor = if (userKey == null || rows.any { it.renderKey == userKey }) -1 else canonical.indexOfFirst { it.renderKey == userKey }
            var at = canonical.size
            if (anchor >= 0) {
                at = anchor + 1
                while (at < canonical.size && !(canonical[at].role == Role.User && !isSystemSteeringMessage(canonical[at]))) at++
            }
            canonical.addAll(at, rows)
        }
    }
}
