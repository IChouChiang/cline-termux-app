package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.data.session.ReduceContext
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionFactsReducer
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.ui.approval.PendingInputHost
import kotlinx.coroutines.flow.MutableStateFlow
import dev.cline.termux.data.session.MistakeLimitDecision
import kotlinx.coroutines.flow.StateFlow

/**
 * A scripted [PendingInputHost] for the approval and ask-question controllers: session state
 * is driven through the real `SessionFactsReducer` ([dispatch], fixture replay), commands are
 * recorded and answered by settable lambdas.
 */
class FakePendingInputHost(val clientId: String = "android-test") : PendingInputHost {
    private val flows = HashMap<String, MutableStateFlow<SessionState>>()

    override val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
    override val hubLifetime = MutableStateFlow<String?>("4242:2026-10-03T00:00:00.000Z")
    override val autoApproveAll = MutableStateFlow(false)

    /** Calls of [turnOnAutoApprove]; [onTurnOnAutoApprove] answers (and by default turns the flow on). */
    var autoApproveWrites = 0
    var onTurnOnAutoApprove: suspend () -> ApprovalWriteResult = {
        autoApproveAll.value = true
        ApprovalWriteResult.Written
    }

    override suspend fun turnOnAutoApprove(): ApprovalWriteResult {
        autoApproveWrites++
        return onTurnOnAutoApprove()
    }

    data class Decision(val approvalId: String, val approved: Boolean, val reason: String?)
    data class Answer(val requestId: String, val answer: String)
    data class Failure(val command: String, val id: String, val code: String)

    val decisions = mutableListOf<Decision>()
    data class MistakeAnswer(val sessionId: String, val requestId: String, val decision: MistakeLimitDecision)

    val answers = mutableListOf<Answer>()
    val mistakeAnswers = mutableListOf<MistakeAnswer>()
    val aborts = mutableListOf<String>()
    val failures = mutableListOf<Failure>()

    var onRespond: suspend (Decision) -> Unit = {}
    var onAnswer: suspend (Answer) -> Boolean = { false }
    var onMistakeAnswer: suspend (MistakeAnswer) -> Boolean = { false }
    var onAbort: suspend (String) -> Result<Unit> = { Result.success(Unit) }

    fun flow(sessionId: String): MutableStateFlow<SessionState> = flows.getOrPut(sessionId) { MutableStateFlow(SessionState(sessionId)) }

    override fun state(sessionId: String): StateFlow<SessionState> = flow(sessionId)

    /** Applies one event to its session through the real reducer (what `SessionStore.dispatch` does for facts). */
    fun dispatch(event: HubEventEnvelope, now: Long = event.timestamp ?: 0L) {
        val id = event.sessionId ?: return
        val f = flow(id)
        f.value = f.value.copy(facts = SessionFactsReducer.reduce(f.value.facts, event, ReduceContext(clientId, now = now)))
    }

    fun updateFacts(sessionId: String, transform: (SessionFacts) -> SessionFacts) {
        val f = flow(sessionId)
        f.value = f.value.copy(facts = transform(f.value.facts))
    }

    override suspend fun respondToApproval(approvalId: String, approved: Boolean, reason: String?) {
        val d = Decision(approvalId, approved, reason)
        decisions += d
        onRespond(d)
    }

    override suspend fun answerQuestion(requestId: String, answer: String): Boolean {
        val a = Answer(requestId, answer)
        answers += a
        return onAnswer(a)
    }

    override suspend fun answerMistakeLimit(sessionId: String, requestId: String, decision: MistakeLimitDecision): Boolean {
        val a = MistakeAnswer(sessionId, requestId, decision)
        mistakeAnswers += a
        return onMistakeAnswer(a)
    }

    override suspend fun abort(sessionId: String): Result<Unit> {
        aborts += sessionId
        return onAbort(sessionId)
    }

    override fun recordFailure(command: String, id: String, code: String) {
        failures += Failure(command, id, code)
    }
}
