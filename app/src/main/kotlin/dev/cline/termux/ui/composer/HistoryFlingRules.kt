package dev.cline.termux.ui.composer

import kotlin.math.abs

/** One step through the recent prompts (A11.6; the direction is A18's). */
enum class HistoryStep {
    /** Fling from left to right (start to end), TalkBack "Previous prompt": one prompt older. */
    Older,

    /** Fling from right to left (end to start), TalkBack "Next prompt": one prompt newer; from the newest it leaves browsing. */
    Newer,
}

/** How a gesture in the field reads once it passes the touch slop. */
enum class HistoryDragLock {
    /** Still inside the slop. */
    Undecided,

    /** Mostly horizontal: the history gesture takes it (the drawer and the field do not see it). */
    Horizontal,

    /** Anything else: left to the field (scrolling, selection). */
    Other,
}

/**
 * The fling rules of decision A11.6 as pure functions (brief §9.2), in dp so they test without
 * a device: a fling starts inside the field and not within [EDGE_EXCLUSION_DP] of either screen
 * edge (those belong to system back and the drawer), travels at least [MIN_DISTANCE_DP], ends
 * at [MIN_VELOCITY_DP_PER_S] or faster, and is more than [AXIS_RATIO] times as wide as it is
 * tall. A slow drag never steps.
 */
object HistoryFlingRules {
    const val MIN_DISTANCE_DP = 48f
    const val MIN_VELOCITY_DP_PER_S = 1_000f
    const val EDGE_EXCLUSION_DP = 24f
    const val AXIS_RATIO = 2f

    /** A gesture whose first touch is at [downXDp] in a window [windowWidthDp] wide may become a fling. */
    fun startAllowed(downXDp: Float, windowWidthDp: Float): Boolean =
        downXDp >= EDGE_EXCLUSION_DP && downXDp <= windowWidthDp - EDGE_EXCLUSION_DP

    /**
     * The lock once the pointer has moved [dxPx], [dyPx] from the first touch: undecided inside
     * [slopPx], horizontal when the horizontal part dominates, else left to the field.
     */
    fun lock(dxPx: Float, dyPx: Float, slopPx: Float): HistoryDragLock = when {
        maxOf(abs(dxPx), abs(dyPx)) < slopPx -> HistoryDragLock.Undecided
        abs(dxPx) > abs(dyPx) -> HistoryDragLock.Horizontal
        else -> HistoryDragLock.Other
    }

    /**
     * The step a finished gesture makes, or null: [dxDp]/[dyDp] are the total travel from the
     * first touch, [velocityXDpPerS] the horizontal release velocity. Left to right recalls the
     * previous (older) prompt and right to left the next (newer) one, the way a pager turns back
     * and forward (A18); a right-to-left layout mirrors both.
     */
    fun classify(dxDp: Float, dyDp: Float, velocityXDpPerS: Float, rtl: Boolean = false): HistoryStep? {
        if (abs(dxDp) < MIN_DISTANCE_DP) return null
        if (abs(velocityXDpPerS) < MIN_VELOCITY_DP_PER_S) return null
        if (abs(dxDp) <= AXIS_RATIO * abs(dyDp)) return null
        // A flick back against the drag is not a fling in the drag's direction.
        if (dxDp * velocityXDpPerS <= 0f) return null
        val towardEnd = (dxDp > 0f) != rtl
        return if (towardEnd) HistoryStep.Older else HistoryStep.Newer
    }
}
