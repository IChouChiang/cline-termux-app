package dev.cline.termux.ui.approval

import android.util.Log
import dev.cline.termux.core.kernel.KernelCoordinator
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.data.approvals.ApprovalMode
import dev.cline.termux.data.approvals.ApprovalModeRepository
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.data.session.ClientContributions
import dev.cline.termux.data.session.MistakeLimitDecision
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * What the approval and ask-question controllers need from the app (composer-approvals.md
 * C3.7, C4.6): the session's state from `SessionStore` (D15: never the socket), the kernel
 * gate, the connected daemon's lifetime, and the commands. Fakes implement it in
 * tests; [of] wires the real objects.
 *
 * Commands throw `HubCommandException` (reply `ok:false`, client timer, socket closed while
 * pending) or `HubConnectionClosedException` (socket not open).
 */
interface PendingInputHost {
    fun state(sessionId: String): StateFlow<SessionState>

    /** The one kernel projection (KS F.6). */
    val gate: StateFlow<KernelGate>

    /** `pid:startedAt` of the daemon (`HubInfo.lifetimePair`), null while unknown. A change means a new daemon (CA §0.5). */
    val hubLifetime: StateFlow<String?>

    /** `approval.respond {approvalId, approved, reason?}`. */
    suspend fun respondToApproval(approvalId: String, approved: Boolean, reason: String?)

    /**
     * `capability.respond {requestId, ok:true, payload:{result:<answer>}}` with
     * `envelope.clientId` = this install's id, which is the `targetClientId` of every question
     * the reducer keeps. Returns true when the reply says `ignored:true` (already resolved).
     */
    suspend fun answerQuestion(requestId: String, answer: String): Boolean

    /**
     * Answers a `mistake_limit.decide` request (G2) the desktop's way
     * ([dev.cline.termux.data.session.MistakeLimitPrompt.answer]): Continue steers the guidance
     * into [sessionId]'s run (`session.send_input {delivery:"steer"}`, a synthetic send whose
     * reply is awaited), then `capability.respond {result:{action:"continue"}}`, or
     * `{action:"stop", reason:"Could not send recovery guidance: …"}` when the Hub did not take
     * the steer; Stop sends `{action:"stop", reason}`. The prompt's five-minute timeout stops
     * first, and the steer and the reply run on the app scope
     * ([ClientContributions.answerMistakeLimit]), so a caller that goes away in between never
     * leaves a steer without its reply. Returns true when the reply says `ignored:true`.
     */
    suspend fun answerMistakeLimit(sessionId: String, requestId: String, decision: MistakeLimitDecision): Boolean

    /** `run.abort` through the session store (it owns the `Stopping` phase). */
    suspend fun abort(sessionId: String): Result<Unit>

    /** Records a failed command by id and error code only (CA §0.8: never input, question or answer text). */
    fun recordFailure(command: String, id: String, code: String)

    /**
     * The kernel's global "Auto-approve all" is on (decided A1, `ApprovalModeRepository.mode`):
     * the approval controller answers new requests of this client's runs itself.
     */
    val autoApproveAll: StateFlow<Boolean>

    /** Turns "Auto-approve all" on: the sheet's "Approve and auto-approve from now on". */
    suspend fun turnOnAutoApprove(): ApprovalWriteResult

    companion object {
        private const val TAG = "PendingInput"

        /**
         * The production wiring; [scope] keeps the lifetime projection (a ViewModel scope).
         * [contributions] is the app-scoped responder that answers the mistake limit.
         */
        fun of(
            store: SessionStore,
            api: HubSessionApi,
            kernel: KernelCoordinator,
            approvals: ApprovalModeRepository,
            contributions: ClientContributions,
            scope: CoroutineScope,
        ): PendingInputHost =
            object : PendingInputHost {
                override fun state(sessionId: String): StateFlow<SessionState> = store.state(sessionId)
                override val gate: StateFlow<KernelGate> get() = kernel.gate
                override val hubLifetime: StateFlow<String?> = kernel.state
                    .map { s -> s.hubInfo?.takeIf { it.pid != null }?.lifetimePair }
                    .stateIn(scope, SharingStarted.Eagerly, kernel.state.value.hubInfo?.takeIf { it.pid != null }?.lifetimePair)
                override val autoApproveAll: StateFlow<Boolean> = approvals.mode
                    .map { it == ApprovalMode.Auto }
                    .stateIn(scope, SharingStarted.Eagerly, approvals.mode.value == ApprovalMode.Auto)

                override suspend fun turnOnAutoApprove(): ApprovalWriteResult = approvals.set(true)

                override suspend fun respondToApproval(approvalId: String, approved: Boolean, reason: String?) {
                    api.respondToApproval(approvalId, approved, reason)
                }

                override suspend fun answerQuestion(requestId: String, answer: String): Boolean {
                    val reply = api.respondToCapability(requestId, ok = true, result = JsonPrimitive(answer))
                    return (reply["ignored"] as? JsonPrimitive)?.booleanOrNull == true
                }

                override suspend fun answerMistakeLimit(sessionId: String, requestId: String, decision: MistakeLimitDecision): Boolean =
                    contributions.answerMistakeLimit(sessionId, requestId, decision)

                override suspend fun abort(sessionId: String): Result<Unit> = store.abort(sessionId)

                override fun recordFailure(command: String, id: String, code: String) {
                    Log.w(TAG, "$command failed for $id: $code")
                }
            }
    }
}
