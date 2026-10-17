package dev.cline.termux.ui.customize

import dev.cline.termux.core.kernel.dashboard.HelperLease
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.workspace.WorkspaceValidation
import dev.cline.termux.fakes.FakeHelperChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A [HelperLease] over the same state the repositories' [FakeHelperChannel] reads. */
class FakeLease(private val channel: FakeHelperChannel) : HelperLease {
    val owners = mutableListOf<String>()
    val released = mutableListOf<String>()
    var retries = 0

    override val state: StateFlow<HelperState> get() = channel.mutableState

    override suspend fun acquire(owner: String) {
        owners += owner
    }

    override suspend fun release(owner: String) {
        released += owner
    }

    override suspend fun retry() {
        retries++
        channel.mutableState.value = HelperState.Ready
    }
}

/** A [CustomizeWorkspace] with a fixed root and a settable validation answer. */
class FakeWorkspace(var root: String) : CustomizeWorkspace {
    val stored = mutableListOf<String>()
    var validation: (String) -> WorkspaceValidation = { WorkspaceValidation.Valid(it) }
    val candidateFlow = MutableStateFlow(listOf(root))

    override suspend fun initialRoot(): String = root

    override suspend fun store(path: String) {
        stored += path
        root = path
    }

    override val candidates = candidateFlow

    override suspend fun validate(path: String): WorkspaceValidation = validation(path)
}
