package dev.cline.termux.ui.terminal

import dev.cline.termux.data.tools.CommandOutputCap
import dev.cline.termux.data.tools.TerminalBuffer
import dev.cline.termux.data.tools.TerminalSnapshot

/**
 * Turns successive versions of one command's raw output text (with ANSI escapes) into
 * [TerminalSnapshot]s by appending only what each version adds to one [TerminalBuffer]
 * (docs/07 §4.3, §8: `TerminalBuffer` appends, never reparses), instead of re-running the
 * interpreter over the whole text on every streamed tick.
 *
 * The chat row only sees the projector's capped string ([CommandOutputCap.appendCapped]), not
 * the kernel's `headDroppedChars + length` counter that [dev.cline.termux.data.tools.ToolCallStore]
 * uses, so the delta is recovered from the text itself ([appendedSuffix]): a version that
 * extends the previous one appends its suffix, a head-capped window that slid forward appends
 * what entered at its end, and anything else (a different output, the final result replacing a
 * gapped stream) resets the buffer and parses the text once.
 *
 * Because escapes are interpreted as they arrive, the buffer keeps the visible text (up to
 * [capChars] characters) of everything it was fed while the row stayed composed; its own head
 * cap adds the "[Earlier command output truncated]" row when it drops text.
 *
 * Not thread-safe: one caller feeds it, one version at a time.
 */
internal class IncrementalTerminalParser(val capChars: Int = CommandOutputCap.MAX_LIVE_COMMAND_OUTPUT_CHARS) {
    private val buffer = TerminalBuffer(capChars)
    private var previous: String = ""
    private var snapshot: TerminalSnapshot = TerminalSnapshot.EMPTY

    /** Times the buffer was cleared and the whole text parsed again (for tests). */
    var resets: Int = 0
        private set

    fun update(text: String): TerminalSnapshot {
        if (text === previous || text == previous) return snapshot
        val suffix = appendedSuffix(previous, text)
        if (suffix == null) {
            buffer.clear()
            buffer.append(text)
            resets++
        } else {
            buffer.append(suffix)
        }
        previous = text
        snapshot = buffer.snapshot()
        return snapshot
    }
}

/**
 * What [text] adds to [previous] when both are versions of one growing output, capped the way
 * [CommandOutputCap.appendCapped] caps it, or null when [text] is not a continuation.
 *
 * - [text] starts with [previous]: the rest of [text].
 * - [text] is head-capped (starts with [marker]): its body (after the marker) must be a later
 *   window of the same stream, `oldBody.drop(k) + delta`, where `oldBody` is [previous]
 *   without its marker. The overlap is found from where the last [OVERLAP_PROBE_CHARS]
 *   characters of `oldBody` sit in the new body, the latest position first, and each
 *   candidate is verified over the whole overlap; after [OVERLAP_MAX_CANDIDATES] failed
 *   candidates, or with less overlap than the probe, the text counts as new.
 *
 * For output that repeats with a period shorter than the window, several shifts verify; the
 * smallest delta is returned, which renders the same window.
 */
internal fun appendedSuffix(
    previous: String,
    text: String,
    marker: String = CommandOutputCap.COMMAND_OUTPUT_TRUNCATION_MARKER,
): String? {
    if (text.startsWith(previous)) return text.substring(previous.length)
    if (!text.startsWith(marker)) return null

    val oldStart = if (previous.startsWith(marker)) marker.length else 0
    val oldLength = previous.length - oldStart
    val newStart = marker.length
    val newLength = text.length - newStart
    val probeLength = minOf(OVERLAP_PROBE_CHARS, oldLength)
    if (probeLength == 0 || newLength < probeLength) return null

    val probe = previous.substring(previous.length - probeLength)
    // The probe ends where the overlap ends: overlap = probeStart + probeLength - newStart,
    // at most the whole old body and the whole new body.
    val maxOverlap = minOf(oldLength, newLength)
    var probeStart = text.lastIndexOf(probe, newStart + maxOverlap - probeLength)
    var candidates = 0
    while (probeStart >= newStart && candidates < OVERLAP_MAX_CANDIDATES) {
        val overlap = probeStart + probeLength - newStart
        val shift = oldLength - overlap
        if (text.regionMatches(newStart, previous, oldStart + shift, overlap)) {
            return text.substring(newStart + overlap)
        }
        candidates++
        probeStart = text.lastIndexOf(probe, probeStart - 1)
    }
    return null
}

/** Characters from the end of the previous window searched for in the new one. */
internal const val OVERLAP_PROBE_CHARS = 64

/** Candidate overlaps verified before the text is treated as a new output. */
internal const val OVERLAP_MAX_CANDIDATES = 8
