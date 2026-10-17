package dev.cline.termux.fakes

import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.tools.SessionStateSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A settable `SessionStateSource` for the derived-store tests (docs/07 §10.1). */
class FakeSessionStateSource : SessionStateSource {
    private val flows = HashMap<String, MutableStateFlow<SessionState>>()
    val seeds = mutableListOf<String>()

    /** What [seed] does; the default leaves the state as it is. */
    var onSeed: (String) -> Unit = {}

    fun flow(sessionId: String): MutableStateFlow<SessionState> = flows.getOrPut(sessionId) { MutableStateFlow(SessionState(sessionId)) }

    fun set(state: SessionState) {
        flow(state.sessionId).value = state
    }

    fun update(sessionId: String, transform: (SessionState) -> SessionState) {
        val f = flow(sessionId)
        f.value = transform(f.value)
    }

    override fun state(sessionId: String): StateFlow<SessionState> = flow(sessionId)

    override suspend fun seed(sessionId: String) {
        seeds += sessionId
        onSeed(sessionId)
    }
}
