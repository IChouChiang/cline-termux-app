package dev.cline.termux.ui.sessions

import androidx.compose.runtime.Immutable
import dev.cline.termux.data.sessions.ActionFailure
import dev.cline.termux.data.sessions.ActionOutcome
import dev.cline.termux.data.sessions.SessionSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which flow a failure snackbar belongs to (its title, sessions.md §9). */
enum class FailedAction { Rename, Pin, Unpin, Fork, Delete }

/** What the sessions screens tell the user; the composable picks the sentence. */
sealed interface SessionsMessage {
    /** "<Rename|Pin|Unpin|Fork|Delete> failed. <reason or the flow's default>". */
    data class ActionFailed(val action: FailedAction, val failure: ActionFailure) : SessionsMessage

    /** "Could not load more sessions. Session history is unavailable right now." */
    data object LoadMoreFailed : SessionsMessage
}

/** One-shot effects the sessions screens perform (navigation and snackbars). */
sealed interface SessionsEvent {
    data class Message(val message: SessionsMessage) : SessionsEvent

    /** F1 (and F4 step 6): open this session in the chat. */
    data class OpenSession(val sessionId: String) : SessionsEvent

    /** F5 step 4: the deleted session was the open chat → Home. */
    data class Deleted(val sessionId: String) : SessionsEvent
}

/** The inline editor (F2 step 1): the row and the title it opened with. */
@Immutable
data class RenameDraft(val sessionId: String, val initialTitle: String)

/** The row-action state the drawer and the Sessions screen share. */
@Immutable
data class RowActionsState(
    val editing: RenameDraft? = null,
    val deleteCandidate: SessionSummary? = null,
    val detailsFor: String? = null,
)

/**
 * F1-F6 for a row, owned by a ViewModel (docs/07 §6.5 transient controller): opening (marks
 * seen), the inline rename editor, optimistic pin, fork, the delete confirm and the details
 * sheet. Pending state lives in the repository (`SessionsSnapshot.pending`, §1.10); this class
 * keeps only which editor, dialog or sheet is open and turns outcomes into [events].
 */
class SessionRowActions(private val port: SessionsPort, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(RowActionsState())
    val state: StateFlow<RowActionsState> = _state.asStateFlow()

    private val _events = Channel<SessionsEvent>(Channel.BUFFERED)
    val events: Flow<SessionsEvent> = _events.receiveAsFlow()

    /** F1 steps 1-2. */
    fun open(sessionId: String) {
        scope.launch {
            runCatching { port.markSeen(sessionId) }
            _events.send(SessionsEvent.OpenSession(sessionId))
        }
    }

    // ---- F2 rename -------------------------------------------------------------------------------

    fun startRename(summary: SessionSummary) {
        _state.update { it.copy(editing = RenameDraft(summary.id, summary.title.lineSequence().firstOrNull().orEmpty())) }
    }

    /** Back, Escape, the S2 `close`, a scrim tap: closes the editor without a call. */
    fun cancelRename() {
        _state.update { it.copy(editing = null) }
    }

    /**
     * F2 steps 2-6: commits [text]. Success or "unchanged" closes the editor; a failure keeps
     * it open with the typed text and reports the reason.
     */
    fun commitRename(text: String) {
        val draft = _state.value.editing ?: return
        scope.launch {
            when (val outcome = port.rename(draft.sessionId, text)) {
                ActionOutcome.Done, ActionOutcome.Unchanged, is ActionOutcome.Forked -> closeEditorFor(draft.sessionId)
                is ActionOutcome.Failed -> {
                    if (outcome.failure == ActionFailure.Gone) closeEditorFor(draft.sessionId)
                    message(FailedAction.Rename, outcome.failure)
                }
            }
        }
    }

    private fun closeEditorFor(sessionId: String) {
        _state.update { if (it.editing?.sessionId == sessionId) it.copy(editing = null) else it }
    }

    // ---- F3 pin ----------------------------------------------------------------------------------

    fun togglePin(summary: SessionSummary) {
        val pin = !summary.pinned
        scope.launch {
            val outcome = port.setPinned(summary.id, pin)
            if (outcome is ActionOutcome.Failed) message(if (pin) FailedAction.Pin else FailedAction.Unpin, outcome.failure)
        }
    }

    // ---- F4 fork ---------------------------------------------------------------------------------

    fun fork(summary: SessionSummary) {
        scope.launch {
            when (val outcome = port.fork(summary.id)) {
                is ActionOutcome.Forked -> open(outcome.sessionId)
                is ActionOutcome.Failed -> message(FailedAction.Fork, outcome.failure)
                ActionOutcome.Done, ActionOutcome.Unchanged -> Unit
            }
        }
    }

    // ---- F5 delete -------------------------------------------------------------------------------

    fun requestDelete(summary: SessionSummary) {
        _state.update { it.copy(deleteCandidate = summary) }
    }

    /** Cancel / outside tap; the dialog itself ignores this while deleting. */
    fun dismissDelete() {
        _state.update { it.copy(deleteCandidate = null) }
    }

    fun confirmDelete() {
        val candidate = _state.value.deleteCandidate ?: return
        scope.launch {
            val outcome = port.delete(candidate.id)
            _state.update { if (it.deleteCandidate?.id == candidate.id) it.copy(deleteCandidate = null) else it }
            when (outcome) {
                is ActionOutcome.Failed -> message(FailedAction.Delete, outcome.failure)
                else -> {
                    _state.update { if (it.detailsFor == candidate.id) it.copy(detailsFor = null) else it }
                    _events.send(SessionsEvent.Deleted(candidate.id))
                }
            }
        }
    }

    // ---- F6 details ------------------------------------------------------------------------------

    fun showDetails(sessionId: String) {
        _state.update { it.copy(detailsFor = sessionId) }
    }

    fun dismissDetails() {
        _state.update { it.copy(detailsFor = null) }
    }

    fun message(message: SessionsMessage) {
        scope.launch { _events.send(SessionsEvent.Message(message)) }
    }

    private suspend fun message(action: FailedAction, failure: ActionFailure) {
        _events.send(SessionsEvent.Message(SessionsMessage.ActionFailed(action, failure)))
    }
}
