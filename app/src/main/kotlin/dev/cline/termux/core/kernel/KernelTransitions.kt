package dev.cline.termux.core.kernel

/**
 * The last [capacity] state transitions, for the diagnostics bundle (docs/07 §1.1, §3.7):
 * state names and discriminators only, never `lastError` text, URLs or payloads.
 */
class KernelTransitions(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Transition(val atElapsedMs: Long, val from: String, val to: String)

    private val ring = ArrayDeque<Transition>(capacity)

    @Synchronized
    fun record(atElapsedMs: Long, from: KernelState, to: KernelState) {
        val fromLabel = label(from)
        val toLabel = label(to)
        // A refreshed payload (new HubInfo, new hub.status) is not a transition.
        if (fromLabel == toLabel) return
        if (ring.size == capacity) ring.removeFirst()
        ring.addLast(Transition(atElapsedMs, fromLabel, toLabel))
    }

    /** Oldest first. */
    @Synchronized
    fun snapshot(): List<Transition> = ring.toList()

    @Synchronized
    fun clear() = ring.clear()

    companion object {
        const val DEFAULT_CAPACITY = 50

        /** `HubDown(Asleep)`, `Starting(StartingHub)`, `Ready`, … */
        fun label(state: KernelState): String {
            val name = state::class.simpleName ?: "?"
            val discriminator = when (state) {
                is KernelState.HubDown -> state.reason.name
                is KernelState.Starting -> state.step.name
                is KernelState.Restarting -> state.phase.name
                is KernelState.Reconnecting -> "attempt=${state.attempt}"
                is KernelState.TermuxMissing -> if (state.disabled) "disabled" else "absent"
                is KernelState.PortTooOld -> state.release ?: "unknown"
                is KernelState.TermuxTooOld -> state.versionName
                else -> null
            }
            return if (discriminator == null) name else "$name($discriminator)"
        }
    }
}
