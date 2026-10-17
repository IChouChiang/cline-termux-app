package dev.cline.termux.core.kernel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Who else is using the Hub (KS E.7 CheckingSessions; upstream `summarizeHubSessionActivity`). */
data class HubSessionActivity(val activeSessionCount: Int, val participantClientCount: Int) {
    val empty: Boolean get() = activeSessionCount == 0 && participantClientCount == 0

    companion object {
        val NONE = HubSessionActivity(0, 0)

        /** The ported fallback text for an unknown activity (`session.list` failed, E.10). */
        val UNKNOWN = HubSessionActivity(-1, -1)

        /**
         * 1:1 port of `summarizeHubSessionActivity` (`sdk/packages/core/src/hub/client/index.ts`
         * lines 1188–1219) over a `session.list` reply, after removing participants whose
         * `clientId` is [ownClientId]: a session counts when at least one other participant
         * remains (status is ignored, as upstream: participants are live socket subscriptions
         * and cannot go stale); `participantClientCount` is the number of distinct remaining ids.
         */
        fun summarizeOtherClients(sessionListReply: JsonObject, ownClientId: String): HubSessionActivity {
            val sessions = (sessionListReply["sessions"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            var active = 0
            val clients = HashSet<String>()
            for (session in sessions) {
                val others = (session["participants"] as? JsonArray)?.mapNotNull { p ->
                    (p as? JsonObject)?.get("clientId")?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                }.orEmpty().filter { it != ownClientId }
                if (others.isEmpty()) continue
                active += 1
                clients += others
            }
            return HubSessionActivity(activeSessionCount = active, participantClientCount = clients.size)
        }

        /**
         * Port of `describeOutdatedHubSessions` (`sdk/packages/shared/src/hub.ts`):
         * "1 active session from 2 connected Cline clients"; the fallback is the
         * ported sentence fragment for an unknown or empty count.
         */
        fun describe(activity: HubSessionActivity): String {
            val sessions = activity.activeSessionCount
            val clients = activity.participantClientCount
            if (sessions <= 0) return "active sessions from other Cline clients"
            val sessionText = if (sessions == 1) "1 active session" else "$sessions active sessions"
            if (clients <= 0) return sessionText
            val clientText = if (clients == 1) "1 connected Cline client" else "$clients connected Cline clients"
            return "$sessionText from $clientText"
        }
    }
}

/**
 * 1:1 port of `formatUptime` (`sdk/packages/shared/src/parse/time.ts`): "1d 2h", "2h 14m",
 * "4m 12s", "9s". The same formatter `cline hub status` and `doctor --json` use, so the
 * sheet's "Uptime" row and Diagnostics' "Hub uptime" always agree (KS E.5).
 */
fun formatUptime(ms: Long): String {
    val totalSeconds = maxOf(0L, ms / 1000)
    val days = totalSeconds / 86_400
    val hours = (totalSeconds % 86_400) / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
