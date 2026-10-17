package dev.cline.termux.ui.composer

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.data.chat.AttachmentIo
import dev.cline.termux.data.chat.AttachmentRef
import dev.cline.termux.data.chat.Attachments
import dev.cline.termux.data.chat.EncodedImage
import dev.cline.termux.data.chat.ImageEncodeResult
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.DisabledToolsSource
import dev.cline.termux.data.chat.SendPipeline
import dev.cline.termux.data.chat.TextReadResult
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.prefs.ComposerMode
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogSource
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.Delivery
import dev.cline.termux.data.session.ModeSwitchEvents
import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.QueuedPrompt
import dev.cline.termux.data.session.RunRef
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SendOutcome
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionSignal
import dev.cline.termux.data.session.SessionState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import dev.cline.termux.R
import dev.cline.termux.ui.kernel.UiText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C1.7 send/stop/hold rules, C1.9 attachments, C5.7 slash handling and the draft store. */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerControllerTest {

    private class FakeSessions : ComposerSessions {
        val states = mutableMapOf<String, MutableStateFlow<SessionState>>()
        val signalFlow = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 8)
        val sent = mutableListOf<SendRequest>()
        var outcome: SendOutcome = SendOutcome.Sent("r1")
        var aborts = 0

        /** What `run.abort` answers (the store's `abort`). */
        var abortResult: Result<Unit> = Result.success(Unit)

        fun flow(id: String) = states.getOrPut(id) { MutableStateFlow(SessionState(id)) }

        fun phase(
            id: String,
            phase: RunPhase,
            pending: List<QueuedPrompt> = emptyList(),
            queueUnavailable: Boolean = false,
            activeRun: RunRef? = null,
        ) {
            val f = flow(id)
            f.value = f.value.copy(facts = SessionFacts(id, runPhase = phase, activeRun = activeRun, pendingPrompts = pending, queueUnavailable = queueUnavailable))
        }

        override fun state(sessionId: String): StateFlow<SessionState> = flow(sessionId)
        override val signals = signalFlow
        /** When set, `send` waits for it (the chat's run-boundary check, decided A1). */
        var sendGate: CompletableDeferred<Unit>? = null

        override suspend fun send(request: SendRequest): SendOutcome {
            sent += request
            sendGate?.await()
            return outcome
        }
        override suspend fun abort(sessionId: String): Result<Unit> {
            aborts++
            return abortResult
        }

        /** The store's returned prompts no composer took yet (SessionStore.takeReturnedPrompt). */
        val returned = mutableMapOf<String, SessionSignal.NotSent>()
        override fun takeReturnedPrompt(sessionId: String): SessionSignal.NotSent? = returned.remove(sessionId)
    }

    private class FakePrefs : ComposerPrefs {
        val drafts = mutableMapOf<String, String?>()
        val modes = mutableMapOf<String?, ComposerMode>()
        override suspend fun draft(key: String) = drafts[key]
        override suspend fun setDraft(key: String, text: String?) {
            drafts[key] = text
        }
        override suspend fun mode(sessionId: String?) = modes[sessionId] ?: modes[null] ?: ComposerMode.Act
        override suspend fun hasSessionMode(sessionId: String) = sessionId in modes
        override suspend fun setMode(mode: ComposerMode, sessionId: String?) {
            modes[sessionId] = mode
        }
    }

    private data class Ref(override val displayName: String, override val mimeType: String?) : AttachmentRef

    private class FakeIo : AttachmentIo {
        val images = mutableMapOf<String, ImageEncodeResult>()
        val texts = mutableMapOf<String, TextReadResult>()
        val deleted = mutableListOf<File>()
        override suspend fun encodeImage(ref: AttachmentRef) = images[ref.displayName] ?: ImageEncodeResult.Unreadable
        override suspend fun readText(ref: AttachmentRef) = texts[ref.displayName] ?: TextReadResult.Unreadable
        override suspend fun readBytes(file: File) = byteArrayOf(1, 2, 3)
        override suspend fun delete(file: File) {
            deleted += file
        }
    }

    private val sessions = FakeSessions()
    private val prefs = FakePrefs()
    private val io = FakeIo()
    private val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)
    private val catalog = MutableStateFlow(CatalogState())
    private var writeFails = false
    private var steers = 0

    private fun TestScope.controller(
        sessionId: String? = "s1",
        createSession: (suspend (PromptMode) -> Result<String>)? = null,
        onScreen: Boolean = true,
        runningAgents: (String) -> Flow<Int> = { flowOf(0) },
        modeSwitches: ModeSwitchEvents = ModeSwitchEvents.None,
        disabledTools: DisabledToolsSource = DisabledToolsSource.NONE,
    ): ComposerController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val controller = ComposerController(
            variant = if (sessionId == null) ComposerVariant.Welcome else ComposerVariant.Conversation,
            sessionId = sessionId,
            sessions = sessions,
            gate = gate,
            catalog = catalog,
            selection = MutableStateFlow(ModelSelection()),
            reasoning = MutableStateFlow(emptyMap<String, ReasoningEffort>()),
            homeWorkspaceRoot = { "/home" },
            prefs = prefs,
            attachmentIo = io,
            writeText = { dir, name, _ ->
                if (writeFails) Result.failure(IllegalStateException("exit 1")) else Result.success("$dir/$name")
            },
            pipeline = SendPipeline(disabledTools) { Result.failure(IllegalStateException("unread")) },
            slashSnapshots = { null },
            voice = null,
            scope = scope,
            hooks = ComposerController.Hooks(
                createSession = createSession,
                steerFirstQueued = { steers++ },
            ),
            compute = dispatcher,
            runningAgents = runningAgents,
            modeSwitches = modeSwitches,
        )
        // The composer on screen: uiState is shared WhileSubscribed (docs/07 §8).
        if (onScreen) backgroundScope.launch(dispatcher) { controller.uiState.collect { } }
        return controller
    }

    private fun TestScope.collect(c: ComposerController): Pair<MutableList<ComposerMessage>, MutableList<ComposerEffect>> {
        val messages = mutableListOf<ComposerMessage>()
        val effects = mutableListOf<ComposerEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.messages.collect { messages += it } }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.effects.collect { effects += it } }
        return messages to effects
    }

    private fun ComposerController.type(text: String) {
        draft.setTextAndPlaceCursorAtEnd(text)
        onDraftEdited()
    }

    private val ComposerController.text get() = draft.text.toString()

    // ---- send ---------------------------------------------------------------------------------------

    @Test fun idleSendIsDirectAndKeepsTheKeyboard() = runTest {
        val c = controller()
        val (_, effects) = collect(c)
        c.type("hello")
        c.send()
        runCurrent()
        val request = sessions.sent.single()
        assertEquals("hello", request.prompt)
        assertNull(request.delivery)
        assertEquals(PromptMode.Act, request.mode)
        assertEquals("", c.text)
        assertTrue(effects.none { it == ComposerEffect.HideKeyboard })
    }

    @Test fun busySendQueuesAndHidesTheKeyboard() = runTest {
        sessions.phase("s1", RunPhase.Running)
        val c = controller()
        val (_, effects) = collect(c)
        c.type("next")
        c.send()
        runCurrent()
        assertEquals(Delivery.Queue, sessions.sent.single().delivery)
        assertTrue(ComposerEffect.HideKeyboard in effects)
    }

    @Test fun aFocusRequestWaitsUntilTheBoundSessionHasLoaded() = runTest {
        sessions.flow("s1").value = SessionState("s1", seed = SeedState.Seeded(1))
        val c = controller()
        val (_, effects) = collect(c)
        runCurrent()
        // Edit and restart (C.6): the new session is bound and focused before its history arrives.
        c.bind("s2")
        c.requestFocus()
        runCurrent()
        assertTrue(effects.none { it == ComposerEffect.RequestFocus })
        sessions.flow("s2").value = SessionState("s2", seed = SeedState.Seeding)
        runCurrent()
        assertTrue(effects.none { it == ComposerEffect.RequestFocus })
        sessions.flow("s2").value = SessionState("s2", seed = SeedState.Seeded(3))
        runCurrent()
        assertEquals(listOf<ComposerEffect>(ComposerEffect.RequestFocus), effects)
    }

    @Test fun bindingAnotherSessionDropsAPendingFocusRequest() = runTest {
        val c = controller()
        val (_, effects) = collect(c)
        c.requestFocus()
        c.bind("s2")
        sessions.flow("s1").value = SessionState("s1", seed = SeedState.Seeded(1))
        sessions.flow("s2").value = SessionState("s2", seed = SeedState.Seeded(1))
        runCurrent()
        assertTrue(effects.none { it == ComposerEffect.RequestFocus })
    }

    @Test fun homeFocusRequestsAreImmediate() = runTest {
        val c = controller(sessionId = null)
        val (_, effects) = collect(c)
        c.requestFocus()
        runCurrent()
        assertEquals(listOf<ComposerEffect>(ComposerEffect.RequestFocus), effects)
    }

    @Test fun busyLongPressSteersInsteadOfQueueing() = runTest {
        sessions.phase("s1", RunPhase.Running)
        val c = controller()
        val (messages, effects) = collect(c)
        c.type("use the other API")
        c.steer()
        runCurrent()
        val request = sessions.sent.single()
        assertEquals(Delivery.Steer, request.delivery)
        assertEquals("use the other API", request.prompt)
        assertEquals("", c.text)
        assertEquals(listOf<ComposerMessage>(ComposerMessage.SteerSent), messages)
        assertTrue(ComposerEffect.HideKeyboard in effects)
    }

    @Test fun idleLongPressSendsNothing() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.type("hello")
        c.steer()
        runCurrent()
        assertTrue(sessions.sent.isEmpty())
        assertEquals("hello", c.text)
        assertTrue(messages.isEmpty())
    }

    @Test fun busyTapStillQueuesWithoutTheSteerMessage() = runTest {
        sessions.phase("s1", RunPhase.Running)
        val c = controller()
        val (messages, _) = collect(c)
        c.type("next")
        c.send()
        runCurrent()
        assertEquals(Delivery.Queue, sessions.sent.single().delivery)
        assertTrue(messages.none { it == ComposerMessage.SteerSent })
    }

    @Test fun anUnavailableQueueSendsDirect() = runTest {
        sessions.phase("s1", RunPhase.Running, queueUnavailable = true)
        val c = controller()
        c.type("next")
        c.send()
        runCurrent()
        assertNull(sessions.sent.single().delivery)
    }

    @Test fun noConnectedProviderAsksToConnect() = runTest {
        catalog.value = CatalogState(source = CatalogSource.Catalog, initialized = true)
        val c = controller()
        val (messages, _) = collect(c)
        c.type("hello")
        c.send()
        runCurrent()
        assertEquals(listOf<ComposerMessage>(ComposerMessage.ConnectModel), messages)
        assertTrue(sessions.sent.isEmpty())
        assertEquals("hello", c.text)
    }

    @Test fun blankDraftDoesNothing() = runTest {
        val c = controller()
        c.type("   ")
        c.send()
        runCurrent()
        assertTrue(sessions.sent.isEmpty())
        assertFalse(c.uiState.value.canSend)
    }

    @Test fun notConnectedPutsTheDraftBack() = runTest {
        sessions.outcome = SendOutcome.NotConnected
        val c = controller()
        val (messages, _) = collect(c)
        c.type("hello")
        c.send()
        runCurrent()
        assertEquals("hello", c.text)
        assertEquals(listOf<ComposerMessage>(ComposerMessage.SendFailedDisconnected), messages)
    }

    @Test fun aLateFailureRestoresOnlyIntoAnEmptyField() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.type("first")
        c.send()
        runCurrent()
        c.type("typed since")
        sessions.signalFlow.emit(SessionSignal.SendFailed("s1", "command_failed", "quota"))
        assertEquals("typed since", c.text)
        assertEquals(ComposerMessage.SendFailed("quota"), messages.last())

        c.replaceDraft("")
        sessions.signalFlow.emit(SessionSignal.SendFailed("s1", "invalid_session_input", "bad"))
        assertEquals("first", c.text)
    }

    @Test fun anInterruptedTurnPutsThePromptBack() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.type("hello")
        c.send()
        runCurrent()
        sessions.signalFlow.emit(SessionSignal.Interrupted("s1", "r1"))
        assertEquals("hello", c.text)
        assertEquals(ComposerMessage.RunInterrupted, messages.last())
    }

    @Test fun signalsOfOtherSessionsAreIgnored() = runTest {
        val c = controller()
        sessions.signalFlow.emit(SessionSignal.NotSent("s2", "other", emptyList()))
        assertEquals("", c.text)
    }

    @Test fun aPromptReturnedWhileAnotherSessionWasBoundComesBackOnBind() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.type("hello")
        c.send()
        runCurrent()
        // The user switches away; the held send's reconnect then ends without a kernel (HC §3.12 step 6).
        c.bind("s2")
        runCurrent()
        val notSent = SessionSignal.NotSent("s1", "hello", emptyList(), kernelDown = true)
        sessions.returned["s1"] = notSent
        sessions.signalFlow.emit(notSent)
        assertEquals("", c.text)
        assertTrue(messages.none { it == ComposerMessage.SendFailedKernelDown })

        c.bind("s1")
        runCurrent()
        assertEquals("hello", c.text)
        assertEquals("hello", prefs.drafts["s1"])
        assertEquals(ComposerMessage.SendFailedKernelDown, messages.last())
        assertTrue(sessions.returned.isEmpty())
    }

    @Test fun aNewComposerTakesThePromptReturnedAfterTheChatWasLeft() = runTest {
        sessions.returned["s1"] = SessionSignal.NotSent("s1", "lost", emptyList())
        val c = controller()
        runCurrent()
        assertEquals("lost", c.text)
        assertEquals("lost", prefs.drafts["s1"])
        assertTrue(sessions.returned.isEmpty())
    }

    @Test fun aReturnedPromptKeepsANewerDraftInFront() = runTest {
        prefs.drafts["s1"] = "newer"
        sessions.returned["s1"] = SessionSignal.NotSent("s1", "older", emptyList())
        val c = controller()
        runCurrent()
        assertEquals("newer\n\nolder", c.text)
    }

    @Test fun theBoundComposerTakesTheStoredCopyWithTheSignal() = runTest {
        val c = controller()
        c.type("hello")
        c.send()
        runCurrent()
        val notSent = SessionSignal.NotSent("s1", "hello", emptyList())
        sessions.returned["s1"] = notSent
        sessions.signalFlow.emit(notSent)
        assertEquals("hello", c.text)
        assertTrue(sessions.returned.isEmpty())
        // A later bind back to s1 does not put it in a second time.
        c.bind("s2")
        c.bind("s1")
        runCurrent()
        assertEquals("hello", c.text)
    }

    @Test fun aDirectSendShowsTheHoldWhileTheChatChecksAndRestarts() = runTest {
        val gateOpen = CompletableDeferred<Unit>()
        sessions.sendGate = gateOpen
        val c = controller()
        c.type("hello")
        c.send()
        runCurrent()
        // The field cleared at once; the spinner holds the slot while the setting is re-read.
        assertEquals("", c.text)
        assertEquals(HoldReason.PreparingSend, c.uiState.value.holdReason)
        assertFalse(c.uiState.value.canSend)
        // A restart for the new policies is under way: it reads as the resume it is.
        sessions.flow("s1").value = sessions.flow("s1").value.copy(creating = true)
        runCurrent()
        assertEquals(HoldReason.ResumingSession, c.uiState.value.holdReason)
        sessions.flow("s1").value = sessions.flow("s1").value.copy(creating = false)
        gateOpen.complete(Unit)
        runCurrent()
        assertNull(c.uiState.value.holdReason)
    }

    @Test fun aQueuedSendNeverShowsTheHold() = runTest {
        val gateOpen = CompletableDeferred<Unit>()
        sessions.sendGate = gateOpen
        sessions.phase("s1", RunPhase.Running)
        val c = controller()
        c.type("next")
        c.send()
        runCurrent()
        assertNull(c.uiState.value.holdReason)
        gateOpen.complete(Unit)
        runCurrent()
    }

    @Test fun drainingHoldsTheSendUntilTheHubIsBack() = runTest {
        gate.value = KernelGate.Busy(KernelGate.BusyKind.Draining, null, false)
        val c = controller()
        c.type("hello")
        c.send()
        runCurrent()
        assertEquals(HoldReason.HubDraining, c.uiState.value.holdReason)
        assertTrue(sessions.sent.isEmpty())
        gate.value = KernelGate.Ready
        runCurrent()
        assertEquals("hello", sessions.sent.single().prompt)
        assertNull(c.uiState.value.holdReason)
    }

    @Test fun editingDuringTheHoldCancelsItAndKeepsTheText() = runTest {
        gate.value = KernelGate.Busy(KernelGate.BusyKind.Draining, null, false)
        val c = controller()
        c.type("hello")
        c.send()
        runCurrent()
        c.type("x")
        assertEquals("hellox", c.text)
        assertNull(c.uiState.value.holdReason)
        gate.value = KernelGate.Ready
        runCurrent()
        assertTrue(sessions.sent.isEmpty())
    }

    @Test fun homeCreatesTheSessionOnFirstSend() = runTest {
        sessions.flow("new")
        val modes = mutableListOf<PromptMode>()
        val c = controller(sessionId = null, createSession = { mode ->
            modes += mode
            Result.success("new")
        })
        assertNull(c.uiState.value.usage)
        c.type("start")
        c.send()
        runCurrent()
        assertEquals(listOf(PromptMode.Act), modes)
        assertEquals("new", sessions.sent.single().sessionId)
        assertEquals("new", c.uiState.value.sessionId)
        assertEquals(ComposerVariant.Conversation, c.uiState.value.variant)
    }

    @Test fun aFailedCreateRestoresTheDraft() = runTest {
        val c = controller(sessionId = null, createSession = { Result.failure(IllegalStateException("boom")) })
        val (messages, _) = collect(c)
        c.type("start")
        c.send()
        runCurrent()
        assertEquals("start", c.text)
        assertEquals(ComposerMessage.SendFailed("boom"), messages.last())
        assertNull(c.uiState.value.sessionId)
        // No host showed a bubble: the transcript is empty again, so the welcome variant returns.
        assertEquals(ComposerVariant.Welcome, c.uiState.value.variant)
    }

    @Test fun theFirstSendIsTheConversationVariantWhileItsSessionIsCreated() = runTest {
        val created = CompletableDeferred<Result<String>>()
        sessions.flow("new")
        val c = controller(sessionId = null, createSession = { created.await() })
        assertEquals(ComposerVariant.Welcome, c.uiState.value.variant)
        assertEquals(UiText.Res(R.string.composer_placeholder_welcome), composerPlaceholderText(c.uiState.value, fieldEmpty = true))
        c.type("start")
        c.send()
        runCurrent()
        // desktop `isWelcomeState` is false once the user message shows: "Agent is working..." while starting.
        val starting = c.uiState.value
        assertNull(starting.sessionId)
        assertEquals(ComposerVariant.Conversation, starting.variant)
        assertEquals(RunPhase.Starting, starting.phase)
        assertTrue(starting.isBusy)
        assertFalse(starting.showSetupNotice)
        assertEquals(UiText.Res(R.string.composer_placeholder_busy), composerPlaceholderText(starting, fieldEmpty = true))

        created.complete(Result.success("new"))
        runCurrent()
        assertEquals("new", c.uiState.value.sessionId)
        assertEquals(ComposerVariant.Conversation, c.uiState.value.variant)
    }

    @Test fun aFailedCreateStaysInConversationWhileTheBubbleShows() = runTest {
        lateinit var c: ComposerController
        c = controller(sessionId = null, createSession = {
            // The chat shows the optimistic bubble first and keeps it with the error row.
            c.setFirstSendShown(true)
            Result.failure(IllegalStateException("boom"))
        })
        c.type("start")
        c.send()
        runCurrent()
        assertEquals("start", c.text)
        assertNull(c.uiState.value.sessionId)
        assertEquals(ComposerVariant.Conversation, c.uiState.value.variant)
        assertEquals(RunPhase.NoSession, c.uiState.value.phase)
        assertEquals(UiText.Res(R.string.composer_placeholder_conversation), composerPlaceholderText(c.uiState.value, fieldEmpty = false))

        // "New session" / Back drops the bubble: Home is empty again.
        c.setFirstSendShown(false)
        runCurrent()
        assertEquals(ComposerVariant.Welcome, c.uiState.value.variant)
        assertEquals(UiText.Res(R.string.composer_placeholder_welcome), composerPlaceholderText(c.uiState.value, fieldEmpty = true))
    }

    @Test fun aCreateThatNeverReachedTheHubSaysDisconnected() = runTest {
        val c = controller(sessionId = null, createSession = {
            Result.failure(dev.cline.termux.data.session.SessionCreateDisconnectedException())
        })
        val (messages, _) = collect(c)
        c.type("start")
        c.send()
        runCurrent()
        assertEquals("start", c.text)
        assertEquals(ComposerMessage.SendFailedDisconnected, messages.last())
    }

    // ---- slash ------------------------------------------------------------------------------------

    @Test fun teamWithoutTaskShowsUsageAndKeepsTheDraft() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.type("/team")
        c.send()
        runCurrent()
        assertEquals(listOf<ComposerMessage>(ComposerMessage.TeamUsage), messages)
        assertEquals("/team", c.text)
        assertTrue(sessions.sent.isEmpty())
    }

    @Test fun teamWithTeamsDisabledIsRefusedBeforeAnySessionExists() = runTest {
        // V-TEAM: Home's welcome composer, Teams off in global-settings.json disabledTools.
        var creates = 0
        val c = controller(
            sessionId = null,
            createSession = { creates++; Result.success("new") },
            disabledTools = { setOf("teams", "spawn_agent") },
        )
        val (messages, _) = collect(c)
        c.type("/team list the files in this folder and say done")
        c.send()
        runCurrent()
        assertEquals(listOf<ComposerMessage>(ComposerMessage.TeamDisabled), messages)
        assertEquals("/team list the files in this folder and say done", c.text)
        assertEquals(0, creates)
        assertTrue(sessions.sent.isEmpty())
    }

    @Test fun bareTeamWithTeamsDisabledStillShowsUsage() = runTest {
        val c = controller(disabledTools = { setOf("teams") })
        val (messages, _) = collect(c)
        c.type("/team")
        c.send()
        runCurrent()
        assertEquals(listOf<ComposerMessage>(ComposerMessage.TeamUsage), messages)
        assertEquals("/team", c.text)
        assertTrue(sessions.sent.isEmpty())
    }

    @Test fun slashForkIsNoCommandAndGoesAsTyped() = runTest {
        // A19: the fork is the chat three-dot's only; the composer has no `/fork` of its own.
        val c = controller()
        val (messages, _) = collect(c)
        c.type("/fork")
        c.send()
        runCurrent()
        assertEquals(listOf("/fork"), sessions.sent.map { it.prompt })
        assertTrue(messages.isEmpty())
        assertEquals("", c.text)
    }

    // ---- stop and keys ---------------------------------------------------------------------------------

    @Test fun stopWaitsForTheHubCommandTimeoutThenOffersRestartKernel() = runTest {
        sessions.phase("s1", RunPhase.Running)
        val c = controller()
        val (messages, _) = collect(c)
        c.stop()
        runCurrent()
        assertEquals(1, sessions.aborts)
        sessions.phase("s1", RunPhase.Stopping)
        // A4: the protocol's 30 s (HUB_DEFAULT_COMMAND_TIMEOUT_MS), not the app's former 10 s.
        assertEquals(30_000L, ComposerController.Config().stopTimeoutMs)
        advanceTimeBy(29_000)
        runCurrent()
        assertTrue(messages.none { it == ComposerMessage.StopFailed })
        assertFalse(c.uiState.value.stopTimedOut)
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(ComposerMessage.StopFailed, messages.last())
        assertEquals(ComposerMessageAction.RestartKernel, messages.last().action)
        assertTrue(c.uiState.value.stopTimedOut)
        assertEquals(RunPhase.Running, c.uiState.value.phase)
    }

    @Test fun runningAgentsKeepStopAfterTheParentTurnEnded() = runTest {
        sessions.phase("s1", RunPhase.Idle)
        sessions.phase("s2", RunPhase.Idle)
        val running = MutableStateFlow(1)
        val c = controller(runningAgents = { id -> if (id == "s1") running else flowOf(0) })
        runCurrent()
        // desktop `canAbort = status === "running" || status === "stopping" || hasRunningAgents`.
        assertEquals(RunPhase.Idle, c.uiState.value.phase)
        assertFalse(c.uiState.value.isBusy)
        assertTrue(c.uiState.value.canAbort)
        assertTrue(c.onHardwareEscape())
        runCurrent()
        assertEquals(1, sessions.aborts)

        running.value = 0
        runCurrent()
        assertFalse(c.uiState.value.canAbort)

        // A count belongs to its session: another session never inherits it.
        running.value = 2
        runCurrent()
        assertTrue(c.uiState.value.canAbort)
        c.bind("s2")
        runCurrent()
        assertEquals("s2", c.uiState.value.sessionId)
        assertFalse(c.uiState.value.canAbort)
    }

    @Test fun stopIsIgnoredWhenNothingRuns() = runTest {
        val c = controller()
        c.stop()
        assertFalse(c.onHardwareEscape())
        assertEquals(0, sessions.aborts)
    }

    @Test fun emptyEnterWhileBusySteersTheFirstQueuedPrompt() = runTest {
        sessions.phase("s1", RunPhase.Running, pending = listOf(QueuedPrompt("q1", "later", "queue", 0)))
        val c = controller()
        assertTrue(c.onHardwareEnter())
        assertEquals(1, steers)
        assertEquals(1, c.uiState.value.queueSize)
    }

    // ---- mode and draft -------------------------------------------------------------------------------

    @Test fun modeIsStoredPerSessionAndUsedForTheSend() = runTest {
        val c = controller()
        c.setMode(PromptMode.Plan)
        runCurrent()
        assertEquals(ComposerMode.Plan, prefs.modes["s1"])
        c.type("plan it")
        c.send()
        runCurrent()
        assertEquals(PromptMode.Plan, sessions.sent.single().mode)
    }

    private class FakeModeSwitches : ModeSwitchEvents {
        val flipFlow = MutableSharedFlow<String>(extraBufferCapacity = 4)
        override val flips: Flow<String> = flipFlow
        val toggled = mutableListOf<String>()
        override fun userToggled(sessionId: String) {
            toggled += sessionId
        }
    }

    /** Brief §8.4: an approved `switch_to_act_mode` flips the control; the user's own change is reported (it cancels a pending switch). */
    @Test fun anApprovedSwitchFlipsTheBoundSessionToActAndUserChangesAreReported() = runTest {
        val switches = FakeModeSwitches()
        val c = controller(modeSwitches = switches)
        c.setMode(PromptMode.Plan)
        runCurrent()
        assertEquals(listOf("s1"), switches.toggled)
        assertEquals(PromptMode.Plan, c.uiState.value.mode)
        // Tapping the segment already selected changes nothing, so it cancels no pending switch.
        c.setMode(PromptMode.Plan)
        runCurrent()
        assertEquals(listOf("s1"), switches.toggled)
        // Another session's flip leaves this composer alone.
        switches.flipFlow.tryEmit("s2")
        runCurrent()
        assertEquals(PromptMode.Plan, c.uiState.value.mode)
        switches.flipFlow.tryEmit("s1")
        runCurrent()
        assertEquals(PromptMode.Act, c.uiState.value.mode)
        // The flip is the switch's own: it is not reported back as a user toggle.
        assertEquals(listOf("s1"), switches.toggled)
        // Re-selecting Act after the flip is no toggle either; a real change back to Plan is.
        c.setMode(PromptMode.Act)
        runCurrent()
        assertEquals(listOf("s1"), switches.toggled)
        c.setMode(PromptMode.Plan)
        runCurrent()
        assertEquals(listOf("s1", "s1"), switches.toggled)
    }

    @Test fun draftIsSavedAfterTheDebounceAndRestoredOnBind() = runTest {
        prefs.drafts["s2"] = "saved"
        val c = controller()
        c.type("abc")
        advanceTimeBy(499)
        assertNull(prefs.drafts["s1"])
        advanceTimeBy(2)
        runCurrent()
        assertEquals("abc", prefs.drafts["s1"])
        c.bind("s2")
        runCurrent()
        assertEquals("saved", c.text)
    }

    // ---- attachments -------------------------------------------------------------------------------------

    private fun image(name: String, bytes: Long = 1_000): Ref {
        val file = File.createTempFile("img", ".png").apply { deleteOnExit() }
        io.images[name] = ImageEncodeResult.Ok(EncodedImage(file, "image/png", bytes, null))
        return Ref(name, "image/png")
    }

    @Test fun imagesBeyondFiveAreRefused() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.addAttachments((1..6).map { image("p$it.png") })
        runCurrent()
        assertEquals(Attachments.MAX_IMAGES, c.uiState.value.attachments.size)
        assertEquals(listOf<ComposerMessage>(ComposerMessage.TooManyImages), messages)
    }

    @Test fun anOversizedImageIsDroppedAndDeleted() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.addAttachments(listOf(image("big.png", bytes = 4_000_000)))
        runCurrent()
        assertTrue(c.uiState.value.attachments.isEmpty())
        assertEquals(listOf<ComposerMessage>(ComposerMessage.ImageTooLarge), messages)
        assertEquals(1, io.deleted.size)
    }

    @Test fun imagesAloneNeedText() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        c.addAttachments(listOf(image("a.png")))
        runCurrent()
        c.send()
        runCurrent()
        assertEquals(ComposerMessage.NeedsText, messages.last())
        c.type("look")
        c.send()
        runCurrent()
        val request = sessions.sent.single()
        assertEquals(1, request.images.size)
        assertEquals(listOf("data:image/png;base64,AQID"), request.imageDataUrls)
    }

    @Test fun anImageIsEncodedOnceForBothPayloads() = runTest {
        val c = controller()
        c.addAttachments(listOf(image("a.png")))
        runCurrent()
        c.type("look")
        c.send()
        runCurrent()
        val request = sessions.sent.single()
        assertEquals("AQID", request.images.single().data)
        assertEquals(listOf(Attachments.dataUrl("image/png", byteArrayOf(1, 2, 3))), request.imageDataUrls)
    }

    // ---- P4: sharing ---------------------------------------------------------------------------------

    @Test fun theSessionIsObservedOnlyWhileTheComposerIsShown() = runTest {
        val flow = sessions.flow("s1")
        val c = controller(onScreen = false)
        runCurrent()
        assertEquals(0, flow.subscriptionCount.value)
        val shown = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.uiState.collect { } }
        runCurrent()
        assertTrue(flow.subscriptionCount.value > 0)
        sessions.phase("s1", RunPhase.Running)
        assertTrue(c.uiState.value.isBusy)
        shown.cancel()
        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(0, flow.subscriptionCount.value)
    }

    @Test fun aSendWithoutCollectorsStillReadsTheCurrentState() = runTest {
        sessions.phase("s1", RunPhase.Running)
        val c = controller(onScreen = false)
        c.type("next")
        c.send()
        runCurrent()
        assertEquals(Delivery.Queue, sessions.sent.single().delivery)
    }

    @Test fun aStopTimeoutClearsOnceTheRunLeavesStopping() = runTest {
        sessions.phase("s1", RunPhase.Running)
        val c = controller()
        collect(c)
        c.stop()
        runCurrent()
        sessions.phase("s1", RunPhase.Stopping)
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(c.uiState.value.stopTimedOut)
        sessions.phase("s1", RunPhase.Idle)
        runCurrent()
        assertFalse(c.uiState.value.stopTimedOut)
        assertEquals(RunPhase.Idle, c.uiState.value.phase)
    }

    // ---- trial row 11: "The agent did not stop" ends with its run ------------------------------------

    private val abortFailed: Result<Unit> = Result.failure(HubCommandException(HubCommands.RUN_ABORT, HubErrorCodes.COMMAND_FAILED, "no run"))

    private fun run(requestId: String, startedAt: Long) = RunRef(requestId = requestId, runId = "run_$requestId", startedAt = startedAt, clientId = "android", ours = true)

    @Test fun aFailedStopRequestSaysSoAtOnceWithRestartKernel() = runTest {
        // The composer's Stop no longer returns silently when run.abort fails: the approval sheet's message.
        sessions.phase("s1", RunPhase.Running)
        sessions.abortResult = abortFailed
        val c = controller()
        val (messages, _) = collect(c)
        c.stop()
        runCurrent()
        assertEquals(1, sessions.aborts)
        assertEquals(listOf<ComposerMessage>(ComposerMessage.StopFailed), messages)
        assertEquals(ComposerMessageAction.RestartKernel, messages.single().action)
        assertTrue(c.uiState.value.stopFailed)
        assertTrue("Stop stays offered for another try", c.uiState.value.canAbort)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals("one message per attempt", 1, messages.size)
    }

    @Test fun theStopFailureEndsWhenTheRunIsOver() = runTest {
        val running = run("r1", 1L)
        sessions.phase("s1", RunPhase.Running, activeRun = running)
        val c = controller()
        val (messages, _) = collect(c)
        c.stop()
        runCurrent()
        sessions.phase("s1", RunPhase.Stopping, activeRun = running)
        advanceTimeBy(30_001)
        runCurrent()
        assertEquals(ComposerMessage.StopFailed, messages.last())
        assertTrue(c.uiState.value.stopFailed)
        val ended = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { c.awaitStopFailureEnd() }
        runCurrent()
        assertFalse("the snackbar stays while the run goes on", ended.isCompleted)

        // The run's terminal event arrives long after the timeout.
        sessions.phase("s1", RunPhase.Idle)
        runCurrent()
        assertFalse(c.uiState.value.stopFailed)
        assertTrue("the snackbar goes with the run", ended.isCompleted)
    }

    @Test fun theStopFailureEndsWhenAnotherRunTookItsPlace() = runTest {
        val first = run("r1", 1L)
        sessions.phase("s1", RunPhase.Running, activeRun = first)
        sessions.abortResult = abortFailed
        val c = controller()
        collect(c)
        c.stop()
        runCurrent()
        assertTrue(c.uiState.value.stopFailed)
        // The same run waits for an approval: it still has not stopped.
        sessions.phase("s1", RunPhase.AwaitingUser, activeRun = first)
        runCurrent()
        assertTrue(c.uiState.value.stopFailed)

        // The run ended and a queued prompt started between two readings: the session never read idle.
        sessions.phase("s1", RunPhase.Running, activeRun = RunRef(requestId = "q:p1", runId = null, startedAt = 2L, clientId = null, ours = false))
        runCurrent()
        assertFalse(c.uiState.value.stopFailed)
        // Ended for good: a later run this client never saw start does not bring it back.
        sessions.phase("s1", RunPhase.Running, activeRun = null)
        runCurrent()
        assertFalse(c.uiState.value.stopFailed)
    }

    @Test fun aLaterStopTheHubAcceptsEndsTheFailureAndItsOwnFailureBringsItBack() = runTest {
        sessions.phase("s1", RunPhase.Running)
        sessions.abortResult = abortFailed
        val c = controller()
        val (messages, _) = collect(c)
        c.stop()
        runCurrent()
        assertTrue(c.uiState.value.stopFailed)

        sessions.abortResult = Result.success(Unit)
        c.stop()
        runCurrent()
        assertEquals(2, sessions.aborts)
        assertFalse("a new attempt is under way", c.uiState.value.stopFailed)
        sessions.phase("s1", RunPhase.Stopping)
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(c.uiState.value.stopFailed)
        assertEquals(listOf<ComposerMessage>(ComposerMessage.StopFailed, ComposerMessage.StopFailed), messages)
    }

    @Test fun theStopFailureDoesNotOutliveTheSession() = runTest {
        sessions.phase("s1", RunPhase.Running)
        sessions.phase("s2", RunPhase.Running)
        sessions.abortResult = abortFailed
        val c = controller()
        collect(c)
        c.stop()
        runCurrent()
        assertTrue(c.uiState.value.stopFailed)
        val ended = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { c.awaitStopFailureEnd() }
        runCurrent()
        assertFalse(ended.isCompleted)

        c.bind("s2")
        runCurrent()
        assertTrue(ended.isCompleted)
        assertEquals("s2", c.uiState.value.sessionId)
        assertFalse(c.uiState.value.stopFailed)
    }

    @Test fun aStopOutcomeThatArrivesAfterAMoveIsNotShownForTheNewSession() = runTest {
        sessions.phase("s1", RunPhase.Running)
        sessions.phase("s2", RunPhase.Running)
        val c = controller()
        val (messages, _) = collect(c)
        c.stop()
        runCurrent()
        sessions.phase("s1", RunPhase.Stopping)
        c.bind("s2")
        runCurrent()
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue(messages.none { it == ComposerMessage.StopFailed })
        assertEquals("s2", c.uiState.value.sessionId)
        assertFalse(c.uiState.value.stopFailed)
        assertFalse(c.uiState.value.stopTimedOut)
        assertEquals(RunPhase.Running, c.uiState.value.phase)
    }

    @Test fun aDeletedSessionEndsTheStopFailure() = runTest {
        sessions.phase("s1", RunPhase.Running)
        sessions.abortResult = abortFailed
        val c = controller()
        collect(c)
        c.stop()
        runCurrent()
        assertTrue(c.uiState.value.stopFailed)
        sessions.flow("s1").value = sessions.flow("s1").value.copy(seed = SeedState.Missing)
        runCurrent()
        assertFalse(c.uiState.value.stopFailed)
    }

    @Test fun aStopFailureWhileOnlyAgentsRunEndsWhenTheyFinish() = runTest {
        sessions.phase("s1", RunPhase.Idle)
        val running = MutableStateFlow(1)
        sessions.abortResult = abortFailed
        val c = controller(runningAgents = { running })
        collect(c)
        c.stop()
        runCurrent()
        assertTrue(c.uiState.value.stopFailed)
        running.value = 0
        runCurrent()
        assertFalse(c.uiState.value.stopFailed)
    }

    @Test fun textFilesBecomeReadyOrFail() = runTest {
        val c = controller()
        val (messages, _) = collect(c)
        io.texts["notes.md"] = TextReadResult.Ok("# hi")
        io.texts["huge.log"] = TextReadResult.TooLarge
        io.texts["blob.bin"] = TextReadResult.NotText
        c.addAttachments(listOf(Ref("notes.md", "text/markdown"), Ref("huge.log", "text/plain"), Ref("blob.bin", null)))
        runCurrent()
        val ready = c.uiState.value.attachments.single() as DraftAttachment.TextFile
        assertEquals(FileAttachState.Ready, ready.state)
        assertTrue(ready.termuxPath!!.endsWith("/notes.md"))
        assertEquals(listOf(ComposerMessage.FileTooLarge("huge.log"), ComposerMessage.FileNotText("blob.bin")), messages)

        writeFails = true
        io.texts["bad.txt"] = TextReadResult.Ok("x")
        c.addAttachments(listOf(Ref("bad.txt", "text/plain")))
        runCurrent()
        assertEquals(ComposerMessage.FileFailed("bad.txt"), messages.last())
        assertEquals(1, c.uiState.value.attachments.size)

        c.type("read it")
        c.send()
        runCurrent()
        assertEquals(listOf(ready.termuxPath), sessions.sent.single().userFiles)
    }

    @Test fun removingAnImageDeletesItsCacheFile() = runTest {
        val c = controller()
        c.addAttachments(listOf(image("a.png")))
        runCurrent()
        val chip = c.uiState.value.attachments.single()
        c.removeAttachment(chip.id)
        runCurrent()
        assertTrue(c.uiState.value.attachments.isEmpty())
        assertEquals(1, io.deleted.size)
    }
}
