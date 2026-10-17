package dev.cline.termux.data.chat

/*
 * Ports of the label helpers in sdk/packages/ui/components/agent-chat/index.tsx
 * (`formatThoughtLabel`, `formatWorkDuration`, `formatWorkActivityLabel`), used by the
 * reasoning row (HC §3.4.3) and the folded work row (HC §3.4.5). JavaScript's
 * `Math.round` rounds halves up, which `floor(x + 0.5)` reproduces.
 */

internal fun jsRound(value: Double): Long = Math.floor(value + 0.5).toLong()

/** "Thinking" while the duration is unknown, "Thought for Ns" once it is. */
fun formatThoughtLabel(durationMilliseconds: Long?): String {
    if (durationMilliseconds == null) return "Thinking"
    val seconds = if (durationMilliseconds == 0L) 0L else maxOf(1L, jsRound(durationMilliseconds / 1000.0))
    return "Thought for ${seconds}s"
}

/** Compact "3s" / "4m 12s" / "1h 3m" duration for work summary rows. */
fun formatWorkDuration(durationMilliseconds: Long): String {
    val totalSeconds = maxOf(1L, jsRound(durationMilliseconds / 1000.0))
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    if (hours > 0) return if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
    if (minutes > 0) return if (seconds > 0) "${minutes}m ${seconds}s" else "${minutes}m"
    return "${seconds}s"
}

/** "Worked for 4m 12s and made 14 tool calls", with fallbacks when either number is unknown. */
fun formatWorkActivityLabel(durationMilliseconds: Long? = null, toolCallCount: Int? = null): String {
    val worked = durationMilliseconds?.takeIf { it >= 0 }?.let { "Worked for ${formatWorkDuration(it)}" }
    val calls = toolCallCount?.takeIf { it != 0 }?.let { "$it ${if (it == 1) "tool call" else "tool calls"}" }
    return when {
        worked != null && calls != null -> "$worked and made $calls"
        worked != null -> worked
        calls != null -> "Made $calls"
        else -> "Worked"
    }
}
