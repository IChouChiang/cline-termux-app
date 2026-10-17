package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubCapabilities
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * The client contributions the app registers on every `session.create` (HubPayloads.runtimeOptions,
 * batch-1 brief §8) and the answers it gives for them:
 *
 * - `tool_executor.askQuestion`: the user answers in the ask-question sheet (AskQuestionController).
 * - `mistake_limit.decide` (G2): the user answers in the same sheet; [MistakeLimitPrompt] builds
 *   the decision and [ClientContributions.answerMistakeLimit] steers its guidance and answers, on
 *   the app scope. A request the user already continued through is answered here, and so is one
 *   nobody answered within the desktop's five minutes (stop).
 * - `custom_tool.switch_to_act_mode` (A11 row 4, plan sessions only): answered here from the
 *   turn's Plan/Act mode; a successful switch in a run the app can follow (its own send, or a
 *   drained queued prompt) rebuilds the session in act mode and continues the plan ([ActModeSwitch]).
 * - Any other capability targeted at this client is refused at once, so a run never waits on
 *   the phone for something it cannot do.
 *
 * No hook contribution exists (brief F1): the Hub runs the user's hook files itself.
 */

/** What the user chose at the mistake limit ([MistakeLimitPrompt.decide]). */
sealed interface MistakeLimitDecision {
    /** `{action:"stop", reason}`: core aborts the run with [reason]. */
    data class Stop(val reason: String) : MistakeLimitDecision

    /** Continue: [guidance] is steered into the run, then `{action:"continue"}` ([MistakeLimitPrompt.answer]). */
    data class Continue(val guidance: String) : MistakeLimitDecision
}

/**
 * G2: the desktop's mistake-limit prompt (`apps/examples/desktop-app/sidecar/chat-session.ts`,
 * `createDesktopMistakeLimitPrompt`) with its question, options, answer spellings, detail
 * truncation, guidance text and delivery: the guidance is steered into the running loop
 * (`session.send_input {delivery:"steer"}`) and the decision is a bare `{action:"continue"}`.
 * The bench showed why the guidance does not ride in the decision (the CLI's shape): core 0.0.91
 * appends a decision's guidance to its conversation store, which the running loop never reads,
 * so the model kept repeating the call. The desktop's hold hooks are not used (brief F1/F14):
 * core keeps calling the model while the decision is open, so the desktop's five-minute bound
 * on the question ([TIMEOUT_MS], [timeoutResult]) is what stops an unattended run.
 * The question itself is UI copy: the sheet builds it from string resources; the strings here
 * are protocol text (option answers, the model-facing guidance, the stop reasons).
 */
object MistakeLimitPrompt {
    const val CONTINUE_OPTION = "Try a different approach"
    const val STOP_OPTION = "Stop this run"

    /** The two options as the desktop passes them; the answer is the option text. */
    val OPTIONS: List<String> = listOf(CONTINUE_OPTION, STOP_OPTION)

    const val DETAIL_MAX_CHARS = 600
    const val ELLIPSIS = "…"
    const val ACTION_CONTINUE = "continue"
    const val ACTION_STOP = "stop"
    const val STOP_REASON = "stopped after mistake_limit_reached prompt"

    const val GUIDANCE_INTRO = "The run reached the limit for repeated mistakes or tool calls."
    const val GUIDANCE_ADVICE =
        "Do not repeat the same call. Re-check the tool's parameter requirements, fix the call, and try a different approach."

    private val STOP_ANSWERS = setOf("2", "stop this run", "stop", "n", "no")

    /** Desktop: the stop reason when the guidance could not be steered (a Continue without it would loop on). */
    const val STEER_FAILED_PREFIX = "Could not send recovery guidance: "

    /** Desktop `ASK_QUESTION_TIMEOUT_MS` (`sidecar/context.ts`): how long the prompt waits for the user. */
    const val TIMEOUT_MS: Long = 5 * 60_000L

    /** Desktop: the stop reason of an unanswered prompt, then the untruncated detail or the context's reason. */
    const val TIMEOUT_REASON_PREFIX = "mistake_limit_reached: "

    /**
     * `{action:"continue"}` without guidance: the answer after the guidance was steered, and for
     * a request the user already continued through.
     */
    val AUTO_CONTINUE: JsonObject = buildJsonObject { put("action", ACTION_CONTINUE) }

    fun stopResult(reason: String): JsonObject = buildJsonObject {
        put("action", ACTION_STOP)
        put("reason", reason)
    }

    /**
     * The desktop's answer when the prompt timed out (`createDesktopMistakeLimitPrompt` catch):
     * stop, with `mistake_limit_reached: <trimmed details || reason>`.
     */
    fun timeoutResult(context: MistakeLimitContext): JsonObject =
        stopResult(TIMEOUT_REASON_PREFIX + context.details?.trim().orEmpty().ifEmpty { context.reason.orEmpty() })

    /** The request's `payload.context` (`ConsecutiveMistakeLimitContext`). */
    fun context(payload: JsonObject?): MistakeLimitContext {
        val c = payload?.obj("context")
        return MistakeLimitContext(
            iteration = c?.int("iteration"),
            consecutiveMistakes = c?.int("consecutiveMistakes"),
            maxConsecutiveMistakes = c?.int("maxConsecutiveMistakes"),
            reason = c?.str("reason"),
            details = c?.str("details"),
        )
    }

    /** The trimmed detail, cut at [DETAIL_MAX_CHARS] UTF-16 units with "…" (desktop `truncatedDetail`); "" when none. */
    fun truncatedDetail(details: String?): String {
        val detail = details?.trim().orEmpty()
        return if (detail.length > DETAIL_MAX_CHARS) detail.substring(0, DETAIL_MAX_CHARS) + ELLIPSIS else detail
    }

    /**
     * The desktop's question (`createDesktopMistakeLimitPrompt`): [intro], the "Latest: <detail>"
     * line when the request carried a detail, and [ask], the non-empty lines joined with a
     * newline. The lines are UI copy (string resources); the sheet and the notification share this.
     */
    fun question(intro: String, latest: String?, ask: String): String = listOfNotNull(intro, latest, ask).joinToString("\n")

    /** The desktop's stop spellings: `2`, `stop this run`, `stop`, `n`, `no` (trimmed, any case). */
    fun isStop(answer: String): Boolean = answer.trim().lowercase() in STOP_ANSWERS

    /**
     * The decision for [answer]: a stop spelling stops the run with the desktop's reason;
     * anything else continues with the desktop's guidance, plus "User guidance: <answer>" when
     * the answer is the user's own text (not `1` and not the first option).
     */
    fun decide(context: MistakeLimitContext?, answer: String): MistakeLimitDecision {
        if (isStop(answer)) return MistakeLimitDecision.Stop(STOP_REASON)
        val normalized = answer.trim().lowercase()
        val custom = if (normalized.isNotEmpty() && normalized != "1" && normalized != CONTINUE_OPTION.lowercase()) answer.trim() else ""
        val detail = truncatedDetail(context?.details)
        val guidance = listOf(
            GUIDANCE_INTRO,
            if (detail.isNotEmpty()) "Latest: $detail" else "",
            GUIDANCE_ADVICE,
            if (custom.isNotEmpty()) "User guidance: $custom" else "",
        ).filter { it.isNotEmpty() }.joinToString(" ")
        return MistakeLimitDecision.Continue(guidance)
    }

    /**
     * Sends [decision] the desktop's way. Stop: `{action:"stop", reason}`. Continue: [steer] the
     * guidance first (it returns null once the Hub accepted the steer, else why not), then
     * `{action:"continue"}`; a steer the Hub did not take stops the run instead, since a
     * Continue without the guidance would repeat the same failing loop. [respond] sends the
     * `capability.respond` result and returns the reply's `ignored` flag, which this returns.
     */
    suspend fun answer(
        decision: MistakeLimitDecision,
        steer: suspend (guidance: String) -> String?,
        respond: suspend (result: JsonObject) -> Boolean,
    ): Boolean = when (decision) {
        is MistakeLimitDecision.Stop -> respond(stopResult(decision.reason))
        is MistakeLimitDecision.Continue -> {
            val failure = steer(decision.guidance)
            respond(if (failure == null) AUTO_CONTINUE else stopResult(STEER_FAILED_PREFIX + failure))
        }
    }

    /**
     * The guidance steer for [state]'s run: `delivery:"steer"`, in the mode of the turn the model
     * is in ([runMode]), synthetic so nothing of it reaches the composer
     * (`SessionStore.sendSynthetic`, whose result [steerFailure] reads).
     */
    fun steerRequest(state: SessionState, guidance: String): SendRequest =
        SendRequest(state.sessionId, guidance, mode = runMode(state), delivery = Delivery.Steer, synthetic = true)

    /**
     * Null when the Hub accepted the steer (desktop: `await manager.send()` resolved); otherwise
     * the reason, for [STEER_FAILED_PREFIX]: the Hub's own text when it refused, else the store's.
     */
    fun steerFailure(result: SyntheticSendResult): String? = when (result) {
        is SyntheticSendResult.Accepted -> null
        SyntheticSendResult.NotConnected -> "not connected to the Hub"
        is SyntheticSendResult.ResumeFailed -> result.message.ifBlank { "the session could not be resumed" }
        is SyntheticSendResult.Refused -> result.message.ifBlank { result.code }
    }

    /**
     * The desktop's dedupe: the user already chose to continue through [iteration] in this run
     * (the tracker serialises decisions, so older failures can arrive after the answer).
     */
    fun autoContinues(facts: SessionFacts, iteration: Int?): Boolean {
        val through = facts.mistakeContinuedThroughIteration ?: return false
        return iteration != null && iteration <= through
    }
}

/**
 * A11 row 4: the CLI's `switch_to_act_mode` (`apps/cli/src/runtime/interactive/mode.ts`): the
 * tool result texts and the continuation prompt, verbatim. Protocol text the model reads.
 */
object ActModeSwitch {
    const val SUCCESS =
        "You successfully switched to act mode, proceed with the plan. You now have access to editing files and running " +
            "commands. (The switch_to_act_mode tool is only available in plan mode.)"
    const val ALREADY_ACT = "Already in act mode."

    /**
     * The refusal for a switch in a run this app cannot follow to its end (one another client
     * started): claiming success there would leave the model waiting for a plan that never runs.
     */
    const val UNAVAILABLE = "Cannot switch to act mode from this run. Ask the user to switch to act mode."

    /** `ACT_MODE_CONTINUATION_PROMPT`: a synthetic user message, never shown in the transcript. */
    const val CONTINUATION_PROMPT = "The user approved switching to act mode. Continue with the approved plan now."

    /** `meta.messageKind` of the history row that holds [CONTINUATION_PROMPT] (hidden like a steering note). */
    const val MESSAGE_KIND = "act_mode_continuation"

    /** The exact display text of the synthetic prompt (`isSyntheticUserText` in the CLI's hydration). */
    fun isContinuationPrompt(displayText: String): Boolean = displayText == CONTINUATION_PROMPT
}

/** The open turn this app sent that the session's active run belongs to; null for runs started elsewhere. */
internal fun ownRunTurn(state: SessionState): TurnRecord? {
    val run = state.facts.activeRun?.requestId ?: return null
    return state.turns.lastOrNull { it.open && it.ours && it.requestId == run }
}

/**
 * The open turn the model is in that this app can follow to its end: its own direct send
 * ([ownRunTurn]), or a drained queued prompt (`q:<id>`). A drained prompt never gets a `run.*`
 * event and its run may carry no `activeRun` at all (the previous run's late `run.completed`
 * cleared it), so it is the session's open turn; its mode is the one the app queued it in, else
 * the session's ([SessionStore] records it when the prompt drains).
 */
internal fun runTurn(state: SessionState): TurnRecord? =
    ownRunTurn(state) ?: state.openTurn?.takeIf { SessionFactsReducer.isQueuedRunKey(it.requestId) }

/** Plan/Act of the turn the model is in ([runTurn]; per turn in the app), else the session's mode. */
internal fun runMode(state: SessionState): PromptMode = PromptMode.parse(runTurn(state)?.mode ?: state.facts.mode)

/** What the composer needs from a tool-initiated Plan → Act switch (the Plan/Act control's own state). */
interface ModeSwitchEvents {
    /** Sessions whose Plan/Act flips to Act: the model's switch was approved and the plan continues. */
    val flips: Flow<String>

    /** The user changed Plan/Act in [sessionId]'s composer: a pending switch does not continue. */
    fun userToggled(sessionId: String)

    companion object {
        val None: ModeSwitchEvents = object : ModeSwitchEvents {
            override val flips: Flow<String> = emptyFlow()
            override fun userToggled(sessionId: String) = Unit
        }
    }
}

/**
 * The one responder for `capability.requested` events targeted at this client (brief §8.1).
 * Fed by the `SessionStore` pump through `SessionEventTap` ([observe], before the store applies
 * the event; D15 keeps one collector). Answers are `capability.respond` through [hub].
 *
 * - Dedupes by `requestId`: a replay or a second subscription never answers twice.
 * - A mistake-limit prompt the user has not answered after [Config.mistakeLimitTimeoutMs] (the
 *   desktop's five minutes) is answered "stop" here ([MistakeLimitPrompt.timeoutResult]). The
 *   user's answer ([answerMistakeLimit]), its `capability.resolved` or the end of the session's
 *   run cancels the timer. Lives in app scope, so the chat screen need not be open.
 * - A17: once the Hub took a stop (the user's "Stop this run", or that timeout), the session
 *   gets a stop notice saying why ([StopNotices], [Sessions.addStopNotice]); `run.aborted`
 *   carries no reason. A Continue whose guidance steer failed also stops, without a notice.
 * - A replayed request that the Hub already resolved is answered too; the reply then says
 *   `ignored:true`, and a switch to Act is only kept when the Hub took the answer.
 * - Contributions are bound to the session's runtime and this install's stable clientId, so a
 *   reconnect needs no new registration: `session.attach` carries none, and every create path
 *   (new, resume, restore, fork, the rebuild in act mode) sends
 *   [dev.cline.termux.core.kernel.hub.HubPayloads.runtimeOptions] for the mode it creates.
 */
class ClientContributions(
    private val clientId: String,
    private val hub: Hub,
    private val sessions: Sessions,
    /** Persists `composer.mode.<sessionId>` (the Plan/Act control's state). */
    private val persistMode: suspend (sessionId: String, mode: PromptMode) -> Unit,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val config: Config = Config(),
    /** Epoch milliseconds, for the time a stop notice sits at (A17). */
    private val now: () -> Long = System::currentTimeMillis,
) : ModeSwitchEvents {

    /** `capability.respond`; returns the reply payload (`{requestId, ok}` or `{ignored:true}`). */
    fun interface Hub {
        suspend fun respond(requestId: String, ok: Boolean, result: JsonElement?, error: String?): JsonObject
    }

    /** The parts of `SessionStore` the responder reads and the continuation sends through. */
    interface Sessions {
        fun state(sessionId: String): StateFlow<SessionState>

        /** `SessionStore.sendSynthetic`: the continuation and the guidance steer are the app's prompts, never the user's draft. */
        suspend fun sendSynthetic(request: SendRequest): SyntheticSendResult

        /**
         * `SessionStore.restart`: rebuilds the idle session's runtime in [mode] with its messages
         * (brief F2: `session.detach`, then `session.create` with the same id). True once the
         * Hub's create replied; false when the session was busy or the rebuild failed.
         */
        suspend fun restart(sessionId: String, mode: PromptMode): Boolean

        /** A17: `SessionStore.addStopNotice`, the transcript's reason for a run the app stopped at the mistake limit. */
        suspend fun addStopNotice(notice: StopNotice) = Unit
    }

    data class Config(
        /** How long the continuation waits for the store to apply the run's end before it goes on anyway. */
        val settleTimeoutMs: Long = 5_000,
        /** Request ids remembered for the dedupe. */
        val rememberedRequests: Int = 512,
        /** How long a mistake-limit prompt waits for the user before it stops the run (desktop: five minutes). */
        val mistakeLimitTimeoutMs: Long = MistakeLimitPrompt.TIMEOUT_MS,
    )

    /**
     * A successful switch in a run this app can follow ([runTurn]): continues in Act when that run
     * completes. A class, not a data class: the continuation checks it is still the same switch.
     */
    private class PendingSwitch(val requestId: String, val runRequestId: String)

    private val lock = Any()
    private val seen = LinkedHashSet<String>()
    private val pending = HashMap<String, PendingSwitch>()
    private val inFlight = HashMap<String, CompletableDeferred<Unit>>()

    /** An open mistake-limit prompt's timeout, by `requestId`. */
    private class MistakeTimer(val sessionId: String, val context: MistakeLimitContext, val job: Job)
    private val mistakeTimers = HashMap<String, MistakeTimer>()

    private val _flips = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val flips: SharedFlow<String> = _flips.asSharedFlow()

    /** One Hub event, in pump order. Never suspends; answers go out on [scope]. */
    fun observe(event: HubEventEnvelope) {
        val sessionId = event.sessionId ?: return
        val p = event.payload ?: return
        when (event.event) {
            HubEvents.CAPABILITY_REQUESTED -> onRequested(sessionId, p)
            // Answered (by the user, by another path, or cancelled by the Hub): no timeout any more.
            HubEvents.CAPABILITY_RESOLVED -> p.str("requestId")?.let(::cancelMistakeTimer)
            HubEvents.RUN_COMPLETED, HubEvents.RUN_ABORTED, HubEvents.RUN_FAILED, HubEvents.RUN_INTERRUPTED -> {
                // The run the prompt belongs to is over: nothing left to stop.
                cancelMistakeTimers(sessionId)
                onRunEnded(
                    sessionId,
                    // Observed before the store applies the event: the active run is the one that ends.
                    endedRun = sessions.state(sessionId).value.facts.activeRun?.requestId,
                    completed = event.event == HubEvents.RUN_COMPLETED && p.str("reason") == "completed",
                )
            }
        }
    }

    override fun userToggled(sessionId: String) {
        synchronized(lock) { pending.remove(sessionId) }
    }

    /** True while a switch to Act waits for its run to end (tests, diagnostics). */
    fun hasPendingSwitch(sessionId: String): Boolean = synchronized(lock) { sessionId in pending }

    /** True while the mistake-limit prompt [requestId] has its timeout running (tests, diagnostics). */
    fun hasMistakeTimer(requestId: String): Boolean = synchronized(lock) { requestId in mistakeTimers }

    // ---- the user's mistake-limit answer ------------------------------------------------------

    /**
     * Answers the mistake-limit prompt [requestId] of [sessionId] with [decision] the desktop's way
     * ([MistakeLimitPrompt.answer]: stop; or steer the guidance, then continue).
     *
     * - The user committed an answer, so the prompt's timeout stops now, not when the Hub resolves
     *   the request (the desktop's `requestSidecarAskQuestion` clears its timer the moment the
     *   answer arrives). Otherwise the timer could stop the run between an accepted guidance steer
     *   and the continue reply, and the steer, which the kernel keeps across an abort
     *   (`pending-prompt-service.ts`), would then start a run of its own.
     * - The steer and its reply run on the app scope, so leaving the chat screen (its controller's
     *   scope) can never send the steer without the reply.
     * - An answer that fails before the Hub took it re-arms the timeout while the question is
     *   still open.
     *
     * Returns the reply's `ignored` flag; throws what the Hub commands throw.
     */
    suspend fun answerMistakeLimit(sessionId: String, requestId: String, decision: MistakeLimitDecision): Boolean {
        val timer = stopMistakeTimer(requestId)
        // Read now: the question leaves the facts once the Hub resolves it.
        val context = timer?.context ?: sessions.state(sessionId).value.facts.questions[requestId]?.mistakeLimit
        val outcome = CompletableDeferred<Boolean>()
        scope.launch(dispatcher) {
            try {
                outcome.complete(
                    MistakeLimitPrompt.answer(
                        decision,
                        steer = { guidance ->
                            // Through the store: a steer's run.started must not open a turn (CA §0.2). Synthetic,
                            // and awaited: only a steer the Hub accepted lets the run continue (desktop `await manager.send()`).
                            val request = MistakeLimitPrompt.steerRequest(sessions.state(sessionId).value, guidance)
                            MistakeLimitPrompt.steerFailure(sessions.sendSynthetic(request))
                        },
                        respond = { result ->
                            val ignored = hub.respond(requestId, true, result, null).bool("ignored") == true
                            // A17: the Hub took the user's stop, so the transcript says why the run ends.
                            if (!ignored && decision is MistakeLimitDecision.Stop) noteStop(sessionId, requestId, StopNoticeKind.Stopped, context)
                            ignored
                        },
                    ),
                )
            } catch (e: CancellationException) {
                outcome.cancel(e)
                throw e
            } catch (e: Exception) {
                if (timer != null && requestId in sessions.state(sessionId).value.facts.questions) {
                    startMistakeTimer(timer.sessionId, requestId, timer.context)
                }
                outcome.completeExceptionally(e)
            }
        }
        return outcome.await()
    }

    // ---- requests -------------------------------------------------------------------------------

    private fun onRequested(sessionId: String, p: JsonObject) {
        val requestId = p.str("requestId") ?: return
        if (p.str("targetClientId") != clientId) return
        val capability = p.str("capabilityName").orEmpty()
        if (!remember(requestId)) return
        when (capability) {
            // The user answers in the ask-question sheet.
            HubCapabilities.ASK_QUESTION -> Unit
            HubCapabilities.MISTAKE_LIMIT -> {
                // The reducer opens no question for this one (same facts, same rule): answer it here.
                val context = MistakeLimitPrompt.context(p.obj("payload"))
                if (MistakeLimitPrompt.autoContinues(sessions.state(sessionId).value.facts, context.iteration)) {
                    respondLater(requestId, ok = true, result = MistakeLimitPrompt.AUTO_CONTINUE)
                } else {
                    startMistakeTimer(sessionId, requestId, context)
                }
            }
            HubCapabilities.SWITCH_TO_ACT_MODE -> switchToActMode(sessionId, requestId)
            else -> respondLater(requestId, ok = false, error = "$UNSUPPORTED_PREFIX$capability")
        }
    }

    /** First sight of [requestId]: true. Bounded to [Config.rememberedRequests], oldest first out. */
    private fun remember(requestId: String): Boolean = synchronized(lock) {
        if (!seen.add(requestId)) return false
        while (seen.size > config.rememberedRequests) seen.remove(seen.first())
        true
    }

    private fun respondLater(requestId: String, ok: Boolean, result: JsonElement? = null, error: String? = null) {
        scope.launch(dispatcher) { runCatchingHub { hub.respond(requestId, ok, result, error) } }
    }

    // ---- mistake-limit timeout ------------------------------------------------------------------

    /**
     * The desktop's bound on the prompt (`requestSidecarAskQuestion` times out, then
     * `createDesktopMistakeLimitPrompt` stops): core keeps the loop running while the decision is
     * open, so without it a looping run would call the model until someone answers on the phone.
     */
    private fun startMistakeTimer(sessionId: String, requestId: String, context: MistakeLimitContext) {
        val job = scope.launch(dispatcher, start = CoroutineStart.LAZY) {
            delay(config.mistakeLimitTimeoutMs)
            val self = coroutineContext[Job]
            val due = synchronized(lock) { mistakeTimers[requestId]?.takeIf { it.job === self }?.let { mistakeTimers.remove(requestId) } }
            if (due == null) return@launch
            val reply = runCatchingHub { hub.respond(requestId, true, MistakeLimitPrompt.timeoutResult(context), null) }
            // A17: the Hub took the app's stop (an `ignored` reply means the request was already over).
            if (reply != null && reply.bool("ignored") != true) noteStop(sessionId, requestId, StopNoticeKind.TimedOut, context)
        }
        synchronized(lock) { mistakeTimers[requestId] = MistakeTimer(sessionId, context, job) }
        job.start()
    }

    private fun cancelMistakeTimer(requestId: String) {
        stopMistakeTimer(requestId)
    }

    /** A17: hands the stop's notice to the store; a failure there never fails the answer. */
    private suspend fun noteStop(sessionId: String, requestId: String, kind: StopNoticeKind, context: MistakeLimitContext?) {
        try {
            sessions.addStopNotice(StopNotices.of(sessionId, requestId, kind, context, now()))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The run still stopped; only its reason is missing from the transcript.
        }
    }

    /** Stops [requestId]'s timeout; returns it (for a re-arm), or null when none ran. */
    private fun stopMistakeTimer(requestId: String): MistakeTimer? =
        synchronized(lock) { mistakeTimers.remove(requestId) }?.also { it.job.cancel() }

    private fun cancelMistakeTimers(sessionId: String) {
        val ended = synchronized(lock) {
            val ids = mistakeTimers.filterValues { it.sessionId == sessionId }.keys.toList()
            ids.mapNotNull { mistakeTimers.remove(it) }
        }
        ended.forEach { it.job.cancel() }
    }

    /**
     * The handler checks the mode of the turn the model is in (Plan/Act is per turn in the app,
     * per session in the CLI): Act refuses like the CLI's throw. In a run the app can follow
     * ([runTurn]: its own send or a drained queued prompt) the CLI's success text, and the plan
     * continues in Act once that run completes ([continueInAct]); in any other run the switch is
     * refused ([ActModeSwitch.UNAVAILABLE]) rather than claimed and never continued.
     */
    private fun switchToActMode(sessionId: String, requestId: String) {
        val state = sessions.state(sessionId).value
        if (runMode(state) == PromptMode.Act) {
            respondLater(requestId, ok = false, error = ActModeSwitch.ALREADY_ACT)
            return
        }
        val turn = runTurn(state)
        if (turn == null) {
            respondLater(requestId, ok = false, error = ActModeSwitch.UNAVAILABLE)
            return
        }
        val switch = PendingSwitch(requestId, turn.requestId)
        val done = CompletableDeferred<Unit>()
        synchronized(lock) {
            pending[sessionId] = switch
            inFlight[sessionId] = done
        }
        scope.launch(dispatcher) {
            val reply = runCatchingHub { hub.respond(requestId, true, JsonPrimitive(ActModeSwitch.SUCCESS), null) }
            val accepted = reply != null && reply.bool("ignored") != true
            synchronized(lock) {
                if (!accepted && pending[sessionId] === switch) pending.remove(sessionId)
                if (inFlight[sessionId] === done) inFlight.remove(sessionId)
            }
            done.complete(Unit)
            // A drained queued prompt gets no run.* event: its run ends when the store closes its turn.
            if (accepted && SessionFactsReducer.isQueuedRunKey(switch.runRequestId)) awaitQueuedRunEnd(sessionId, switch)
        }
    }

    // ---- run end ----------------------------------------------------------------------------------

    /**
     * A `run.*` terminal event: a pending switch of a direct run is consumed. Only the switch's
     * own run, ended as completed, continues (CLI `sendTurnWithActModeContinuation`,
     * [continueInAct]). An abort, a failure, an interruption or the end of another run (the
     * switch's run was lost to a Hub restart) drops it. A queued run's switch is left to
     * [awaitQueuedRunEnd]: the late `run.completed` of the run before it is not its end.
     */
    private fun onRunEnded(sessionId: String, endedRun: String?, completed: Boolean) {
        val waitFor = synchronized(lock) { inFlight[sessionId] }
        scope.launch(dispatcher) {
            waitFor?.await()
            val switch = synchronized(lock) { pending[sessionId] } ?: return@launch
            if (SessionFactsReducer.isQueuedRunKey(switch.runRequestId)) return@launch
            if (!completed || endedRun != switch.runRequestId) {
                drop(sessionId, switch)
                return@launch
            }
            // The store applies this run's end after its observers: go on only once the run is closed there.
            withTimeoutOrNull(config.settleTimeoutMs) {
                sessions.state(sessionId).first { it.facts.activeRun?.requestId != switch.runRequestId }
            }
            continueInAct(sessionId, switch)
        }
    }

    /**
     * The end of a drained queued prompt's run (`q:<id>`): the store closes its turn when the loop
     * settles (`session.updated`), or on a terminal `run.*`, with the outcome. Completed continues.
     */
    private suspend fun awaitQueuedRunEnd(sessionId: String, switch: PendingSwitch) {
        val closed = sessions.state(sessionId).first { s -> s.turns.none { it.open && it.requestId == switch.runRequestId } }
        val outcome = closed.turns.lastOrNull { it.requestId == switch.runRequestId }?.outcome
        if (outcome == TurnOutcome.Completed) continueInAct(sessionId, switch) else drop(sessionId, switch)
    }

    /**
     * The approved plan continues (CLI `applyPendingModeChange` + continuation): the composer
     * flips to Act, a session whose runtime is not in act mode is rebuilt in act mode first (the
     * act-mode tools, the editor among them, exist only in a runtime created with that mode:
     * `runtime-builder.ts` picks the tool preset from `config.mode`; the CLI's `applyMode`
     * restarts for the same reason), and only after the rebuild's create replied does the
     * synthetic continuation prompt go out, with mode act. Nothing continues when the session is
     * no longer idle (the user sent something, or a queued prompt waits to run on this runtime),
     * when the rebuild fails, or when the user changed Plan/Act in the meantime.
     */
    private suspend fun continueInAct(sessionId: String, switch: PendingSwitch) {
        if (!isPending(sessionId, switch)) return
        val state = sessions.state(sessionId).value
        if (!idle(state)) {
            drop(sessionId, switch)
            return
        }
        persistMode(sessionId, PromptMode.Act)
        _flips.tryEmit(sessionId)
        if (PromptMode.parse(state.facts.mode) != PromptMode.Act) {
            val rebuilt = runCatchingHub { sessions.restart(sessionId, PromptMode.Act) } == true
            if (!rebuilt) {
                drop(sessionId, switch)
                return
            }
        }
        // A Plan/Act change by the user during the rebuild cancels the continuation.
        if (!drop(sessionId, switch)) return
        // Synthetic: a continuation that cannot go out is dropped, never put in the user's draft.
        runCatchingHub {
            sessions.sendSynthetic(SendRequest(sessionId, ActModeSwitch.CONTINUATION_PROMPT, mode = PromptMode.Act, synthetic = true))
        }
    }

    /** Nothing runs or waits in the session: no open turn, no busy phase, no prompt in the Hub's queue. */
    private fun idle(state: SessionState): Boolean =
        state.openTurn == null && !state.facts.runPhase.isBusy && state.facts.activeRun == null &&
            state.facts.pendingPrompts.isEmpty() && !state.creating

    private fun isPending(sessionId: String, switch: PendingSwitch): Boolean = synchronized(lock) { pending[sessionId] === switch }

    /** Removes [switch] if it is still the session's pending one; true when it was. */
    private fun drop(sessionId: String, switch: PendingSwitch): Boolean = synchronized(lock) {
        if (pending[sessionId] === switch) {
            pending.remove(sessionId)
            true
        } else {
            false
        }
    }

    private suspend fun <T> runCatchingHub(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // A closed socket or a refused reply: the Hub cancels the request when this client goes away.
        null
    }

    companion object {
        /** `capability.respond {ok:false}` text for a capability this app does not provide. */
        const val UNSUPPORTED_PREFIX = "Unsupported capability: "
    }
}
