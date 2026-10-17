package dev.cline.termux.ui.agents

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import dev.cline.termux.R
import dev.cline.termux.data.agents.SessionAgentActivity
import dev.cline.termux.data.agents.SessionAgentRunState
import dev.cline.termux.ui.kernel.quantityResources

/**
 * `describeAgentActivity` (desktop `lib/session-agents.ts`) from resources: "3 agents: 1 running,
 * 2 completed", only the non-zero parts in the desktop order, "agent" when the total is 1.
 * `AgentsCopyTest` pins the English wording to the desktop's.
 */
fun describeAgentActivity(resources: Resources, activity: SessionAgentActivity): String {
    val count = quantityResources(resources).getQuantityString(R.plurals.agents_count, activity.total, activity.total)
    val parts = buildList {
        if (activity.running > 0) add(resources.getString(R.string.agents_part_running, activity.running))
        if (activity.completed > 0) add(resources.getString(R.string.agents_part_completed, activity.completed))
        if (activity.failed > 0) add(resources.getString(R.string.agents_part_failed, activity.failed))
        if (activity.cancelled > 0) add(resources.getString(R.string.agents_part_cancelled, activity.cancelled))
        if (activity.unresolved > 0) add(resources.getString(R.string.agents_part_unresolved, activity.unresolved))
    }
    if (parts.isEmpty()) return count
    val joined = parts.reduce { acc, part -> resources.getString(R.string.agents_join, acc, part) }
    return resources.getString(R.string.agents_summary, count, joined)
}

@Composable
fun describeAgentActivity(activity: SessionAgentActivity): String = describeAgentActivity(LocalResources.current, activity)

/**
 * The roster's one-line error from the kernel banner's two sentences, joined through
 * `agents_banner_join` so CJK translations can drop the space (DS §8.2.3).
 */
fun joinBannerLines(resources: Resources, line1: String, line2: String?): String =
    if (line2 == null) line1 else resources.getString(R.string.agents_banner_join, line1, line2)

/** The lowercase state word of the desktop ("No activity recorded (unresolved)", TalkBack row label). */
@StringRes
fun SessionAgentRunState.word(): Int = when (this) {
    SessionAgentRunState.Running -> R.string.agents_state_running
    SessionAgentRunState.Completed -> R.string.agents_state_completed
    SessionAgentRunState.Failed -> R.string.agents_state_failed
    SessionAgentRunState.Cancelled -> R.string.agents_state_cancelled
    SessionAgentRunState.Unresolved -> R.string.agents_state_unresolved
}
