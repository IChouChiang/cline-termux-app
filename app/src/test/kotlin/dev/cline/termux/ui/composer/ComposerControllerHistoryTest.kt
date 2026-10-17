package dev.cline.termux.ui.composer

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.chat.AttachmentIo
import dev.cline.termux.data.chat.AttachmentRef
import dev.cline.termux.data.chat.ImageEncodeResult
import dev.cline.termux.data.chat.RunPhase
import dev.cline.termux.data.chat.DisabledToolsSource
import dev.cline.termux.data.chat.SendPipeline
import dev.cline.termux.data.chat.TextReadResult
import dev.cline.termux.data.composer.InputHistory
import dev.cline.termux.data.composer.InputHistorySource
import dev.cline.termux.data.composer.InputHistoryStatus
import dev.cline.termux.core.kernel.termux.InputHistoryCommands
import dev.cline.termux.data.model.ReasoningEffort
import dev.cline.termux.data.prefs.ComposerMode
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.session.Delivery
import dev.cline.termux.data.session.SendOutcome
import dev.cline.termux.data.session.SendRequest
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionSignal
import dev.cline.termux.data.session.SessionState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

/**
 * A11.6 in the composer: the fling steps, the browsing chip, typing ending it, the attach
 * sheet's list, and recording a send the Hub accepted. B9: the full-screen editor shares the
 * draft, so closing keeps the text and Send closes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComposerControllerHistoryTest {

    private class FakeSessions : ComposerSessions {
        val states = mutableMapOf<String, MutableStateFlow<SessionState>>()
        override val signals = MutableSharedFlow<SessionSignal>(extraBufferCapacity = 8)
        val sent = mutableListOf<SendRequest>()
        var outcome: SendOutcome = SendOutcome.Sent("r1")

        fun flow(id: String) = states.getOrPut(id) { MutableStateFlow(SessionState(id)) }

        fun phase(id: String, phase: RunPhase) {
            val f = flow(id)
            f.value = f.value.copy(facts = SessionFacts(id, runPhase = phase))
        }

        override fun state(sessionId: String): StateFlow<SessionState> = flow(sessionId)
        override suspend fun send(request: SendRequest): SendOutcome {
            sent += request
            return outcome
        }
        override suspend fun abort(sessionId: String): Result<Unit> = Result.success(Unit)
        override fun takeReturnedPrompt(sessionId: String): SessionSignal.NotSent? = null
    }

    private class FakePrefs : ComposerPrefs {
        val drafts = mutableMapOf<String, String?>()
        override suspend fun draft(key: String) = drafts[key]
        override suspend fun setDraft(key: String, text: String?) {
            drafts[key] = text
        }
        override suspend fun mode(sessionId: String?) = ComposerMode.Act
        override suspend fun hasSessionMode(sessionId: String) = false
        override suspend fun setMode(mode: ComposerMode, sessionId: String?) = Unit
    }

    private class FakeIo : AttachmentIo {
        override suspend fun encodeImage(ref: AttachmentRef) = ImageEncodeResult.Unreadable
        override suspend fun readText(ref: AttachmentRef) = TextReadResult.Unreadable
        override suspend fun readBytes(file: File) = byteArrayOf()
        override suspend fun delete(file: File) = Unit
    }

    /** The repository's contract without Termux: a list, a load the test can hold, and the records. */
    private class FakeHistory(entries: List<String>, loaded: Boolean = true) : InputHistorySource {
        override val state = MutableStateFlow(InputHistory(entries, if (loaded) InputHistoryStatus.Loaded else InputHistoryStatus.NotLoaded))
        val recorded = mutableListOf<String>()
        var refreshes = 0
        var onDisk: List<String> = entries
        val loadGate = CompletableDeferred<Unit>().apply { if (loaded) complete(Unit) }

        override fun refresh() {
            refreshes++
        }

        override suspend fun ensureLoaded() {
            loadGate.await()
            state.value = InputHistory(onDisk, InputHistoryStatus.Loaded)
        }

        override fun record(prompt: String) {
            recorded += prompt
            state.update { it.copy(entries = InputHistoryCommands.prepend(it.entries, prompt)) }
        }
    }

    private val sessions = FakeSessions()
    private val prefs = FakePrefs()
    private val gate = MutableStateFlow<KernelGate>(KernelGate.Ready)

    /** Newest first: "three" was sent last. */
    private val history = FakeHistory(listOf("three", "two", "one"))

    private fun TestScope.controller(
        source: InputHistorySource? = history,
        sessionId: String? = "s1",
        hooks: ComposerController.Hooks = ComposerController.Hooks(),
    ): ComposerController {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(backgroundScope.coroutineContext + dispatcher)
        val controller = ComposerController(
            variant = if (sessionId == null) ComposerVariant.Welcome else ComposerVariant.Conversation,
            sessionId = sessionId,
            sessions = sessions,
            gate = gate,
            catalog = MutableStateFlow(CatalogState()),
            selection = MutableStateFlow(ModelSelection()),
            reasoning = MutableStateFlow(emptyMap<String, ReasoningEffort>()),
            homeWorkspaceRoot = { "/home" },
            prefs = prefs,
            attachmentIo = FakeIo(),
            writeText = { dir, name, _ -> Result.success("$dir/$name") },
            pipeline = SendPipeline(DisabledToolsSource.NONE) { Result.failure(IllegalStateException("unread")) },
            slashSnapshots = { null },
            voice = null,
            scope = scope,
            hooks = hooks,
            compute = dispatcher,
            history = source,
        )
        backgroundScope.launch(dispatcher) { controller.uiState.collect { } }
        return controller
    }

    private fun TestScope.effects(c: ComposerController): MutableList<ComposerEffect> {
        val effects = mutableListOf<ComposerEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { c.effects.collect { effects += it } }
        return effects
    }

    private fun ComposerController.type(text: String) {
        draft.setTextAndPlaceCursorAtEnd(text)
        onDraftEdited()
    }

    private val ComposerController.text get() = draft.text.toString()
    private val ComposerController.chip get() = uiState.value.history

    // ---- fling ------------------------------------------------------------------------------------

    @Test fun aFlingOnTheEmptyFieldShowsTheNewestThenOlder() = runTest {
        val c = controller()
        val effects = effects(c)
        assertTrue(c.uiState.value.historyGestures)
        c.historyStep(HistoryStep.Older)
        assertEquals("three", c.text)
        assertEquals(HistoryBrowse(1, 3), c.chip)
        c.historyStep(HistoryStep.Older)
        assertEquals("two", c.text)
        assertEquals(HistoryBrowse(2, 3), c.chip)
        assertEquals(
            listOf<ComposerEffect>(ComposerEffect.HistoryStepped(HistoryBrowse(1, 3)), ComposerEffect.HistoryStepped(HistoryBrowse(2, 3))),
            effects,
        )
    }

    @Test fun theOldestIsTheEnd() = runTest {
        val c = controller()
        repeat(5) { c.historyStep(HistoryStep.Older) }
        assertEquals("one", c.text)
        assertEquals(HistoryBrowse(3, 3), c.chip)
        assertFalse(c.uiState.value.historyOlderAvailable)
    }

    @Test fun nextGoesNewerAndFromTheNewestClearsTheField() = runTest {
        val c = controller()
        val effects = effects(c)
        c.historyStep(HistoryStep.Older)
        c.historyStep(HistoryStep.Older)
        c.historyStep(HistoryStep.Newer)
        assertEquals("three", c.text)
        assertEquals(HistoryBrowse(1, 3), c.chip)
        c.historyStep(HistoryStep.Newer)
        assertEquals("", c.text)
        assertNull(c.chip)
        assertEquals(ComposerEffect.HistoryStepped(null), effects.last())
    }

    @Test fun nextOnAnEmptyFieldDoesNothing() = runTest {
        val c = controller()
        val effects = effects(c)
        c.historyStep(HistoryStep.Newer)
        assertEquals("", c.text)
        assertNull(c.chip)
        assertTrue(effects.isEmpty())
    }

    /** A18 below the pointer handler: the field hands [HistoryFlingRules.classify]'s step to historyStep. */
    @Test fun aLeftToRightFlingRecallsTheNewestThenOlderAndRightToLeftGoesBack() = runTest {
        val c = controller()
        fun fling(dxDp: Float) = HistoryFlingRules.classify(dxDp, dyDp = 0f, velocityXDpPerS = dxDp * 20f)?.let(c::historyStep)
        fling(120f)
        assertEquals("three", c.text)
        assertEquals(HistoryBrowse(1, 3), c.chip)
        fling(120f)
        assertEquals("two", c.text)
        fling(-120f)
        assertEquals("three", c.text)
        fling(-120f)
        assertEquals("", c.text)
        assertNull(c.chip)
    }

    @Test fun typingEndsBrowsingAndKeepsTheText() = runTest {
        val c = controller()
        c.historyStep(HistoryStep.Older)
        c.type("three and more")
        assertNull(c.chip)
        assertEquals("three and more", c.text)
        // Edited text is the user's: a fling no longer replaces it.
        c.historyStep(HistoryStep.Older)
        assertEquals("three and more", c.text)
        assertFalse(c.uiState.value.historyGestures)
    }

    @Test fun clearingTheFieldEndsBrowsing() = runTest {
        val c = controller()
        c.historyStep(HistoryStep.Older)
        c.type("")
        assertNull(c.chip)
        assertTrue(c.uiState.value.historyGestures)
    }

    @Test fun aFlingIsIgnoredWhileTheFieldHasText() = runTest {
        val c = controller()
        c.type("draft")
        c.historyStep(HistoryStep.Older)
        assertEquals("draft", c.text)
        assertNull(c.chip)
        assertFalse(c.uiState.value.historyGestures)
        assertFalse(c.uiState.value.historyOlderAvailable)
    }

    @Test fun theChipsCrossClearsTheField() = runTest {
        val c = controller()
        c.historyStep(HistoryStep.Older)
        c.historyStep(HistoryStep.Older)
        c.stopHistoryBrowsing()
        assertEquals("", c.text)
        assertNull(c.chip)
    }

    @Test fun anEmptyHistoryHasNothingToShow() = runTest {
        val c = controller(source = FakeHistory(emptyList()))
        c.historyStep(HistoryStep.Older)
        assertEquals("", c.text)
        assertNull(c.chip)
        assertFalse(c.uiState.value.historyOlderAvailable)
    }

    @Test fun theFirstFlingWaitsForTheFirstRead() = runTest {
        val source = FakeHistory(emptyList(), loaded = false).apply { onDisk = listOf("from disk", "older") }
        val c = controller(source = source)
        // Not read yet: "Previous prompt" is offered so the read can happen.
        assertTrue(c.uiState.value.historyOlderAvailable)
        c.historyStep(HistoryStep.Older)
        assertEquals("", c.text)
        source.loadGate.complete(Unit)
        runCurrent()
        assertEquals("from disk", c.text)
        assertEquals(HistoryBrowse(1, 2), c.chip)
    }

    @Test fun noFlingWhileTheKernelIsDown() = runTest {
        gate.value = KernelGate.Down(KernelGate.DownVariant.Stopped, null)
        val c = controller()
        c.historyStep(HistoryStep.Older)
        assertEquals("", c.text)
        assertFalse(c.uiState.value.historyGestures)
    }

    @Test fun withoutAHistoryTheComposerShowsNoneOfIt() = runTest {
        val c = controller(source = null)
        c.historyStep(HistoryStep.Older)
        assertEquals("", c.text)
        assertFalse(c.uiState.value.historyGestures)
        assertFalse(c.hasHistory)
    }

    // ---- drafts -----------------------------------------------------------------------------------

    @Test fun aRecalledPromptIsNotSavedAsTheDraft() = runTest {
        val c = controller()
        c.historyStep(HistoryStep.Older)
        advanceTimeBy(1_000)
        assertEquals("", prefs.drafts["s1"])
        c.bind("s2")
        runCurrent()
        assertEquals("", prefs.drafts["s1"])
    }

    // ---- the attach sheet's list -----------------------------------------------------------------

    @Test fun pickingFromTheListShowsThatPromptInTheBrowsingState() = runTest {
        val c = controller()
        c.pickRecentPrompt(listOf("three", "two", "one"), index = 1)
        assertEquals("two", c.text)
        assertEquals(HistoryBrowse(2, 3), c.chip)
        c.historyStep(HistoryStep.Older)
        assertEquals("one", c.text)
    }

    @Test fun pickingOverTypedTextGivesItBackWhenBrowsingEnds() = runTest {
        val c = controller()
        c.type("half a thought")
        c.pickRecentPrompt(listOf("three", "two", "one"), index = 0)
        assertEquals("three", c.text)
        advanceTimeBy(1_000)
        assertEquals("half a thought", prefs.drafts["s1"])
        c.stopHistoryBrowsing()
        assertEquals("half a thought", c.text)
    }

    @Test fun focusAndSheetsRefreshTheList() = runTest {
        val c = controller()
        c.refreshHistory()
        assertEquals(1, history.refreshes)
        assertEquals(history.state.value, c.recentPrompts.value)
    }

    // ---- recording ----------------------------------------------------------------------------------

    @Test fun sendingARecalledPromptRecordsItOnce() = runTest {
        val c = controller()
        c.historyStep(HistoryStep.Older)
        c.historyStep(HistoryStep.Older)
        c.send()
        runCurrent()
        assertEquals("two", sessions.sent.single().prompt)
        assertEquals(listOf("two"), history.recorded)
        assertEquals(listOf("two", "three", "one"), history.state.value.entries)
        assertEquals("", c.text)
        assertNull(c.chip)
    }

    @Test fun aSendIsRecordedAsTyped() = runTest {
        val c = controller()
        c.type("  hello there  ")
        c.send()
        runCurrent()
        assertEquals(listOf("  hello there  "), history.recorded)
        assertEquals("hello there", history.state.value.entries.first())
    }

    @Test fun onlyAnAcceptedSendIsRecorded() = runTest {
        sessions.outcome = SendOutcome.NotConnected
        val c = controller()
        c.type("lost")
        c.send()
        runCurrent()
        assertTrue(history.recorded.isEmpty())
        assertEquals("lost", c.text)
    }

    @Test fun aQueuedSendIsNotRecordedLikeTheTerminal() = runTest {
        sessions.phase("s1", RunPhase.Running)
        sessions.outcome = SendOutcome.Queued("q1")
        val c = controller()
        c.type("later")
        c.send()
        runCurrent()
        assertEquals(Delivery.Queue, sessions.sent.single().delivery)
        assertTrue(history.recorded.isEmpty())
    }

    @Test fun homesFirstSendIsRecorded() = runTest {
        val c = controller(sessionId = null, hooks = ComposerController.Hooks(createSession = { Result.success("s9") }))
        c.type("start a session")
        c.send()
        runCurrent()
        assertEquals("s9", sessions.sent.single().sessionId)
        assertEquals(listOf("start a session"), history.recorded)
    }

    @Test fun aFailedCreateIsNotRecorded() = runTest {
        val c = controller(sessionId = null, hooks = ComposerController.Hooks(createSession = { Result.failure(IllegalStateException("no")) }))
        c.type("start")
        c.send()
        runCurrent()
        assertTrue(history.recorded.isEmpty())
        assertEquals("start", c.text)
    }

    // ---- B9 full-screen editor ---------------------------------------------------------------------

    @Test fun closingTheEditorKeepsTheText() = runTest {
        val c = controller()
        val editor = FullScreenEditorState()
        c.type("a long message")
        editor.open()
        // The editor edits the composer's own state.
        c.draft.edit { append(" that grew in the editor") }
        c.onDraftEdited()
        editor.close()
        assertFalse(editor.isOpen)
        assertEquals("a long message that grew in the editor", c.text)
        assertTrue(c.uiState.value.canSend)
    }

    @Test fun sendInTheEditorSendsAndCloses() = runTest {
        val c = controller()
        val editor = FullScreenEditorState()
        c.type("ship it")
        editor.open()
        assertTrue(editor.send(c.uiState.value.canSend, c::send))
        runCurrent()
        assertFalse(editor.isOpen)
        assertEquals("ship it", sessions.sent.single().prompt)
        assertEquals("", c.text)
    }

    @Test fun sendWithNothingToSendKeepsTheEditorOpen() = runTest {
        val c = controller()
        val editor = FullScreenEditorState(initiallyOpen = true)
        assertFalse(editor.send(c.uiState.value.canSend, c::send))
        assertTrue(editor.isOpen)
        assertTrue(sessions.sent.isEmpty())
    }

    @Test fun enterInTheEditorDoesWhatTheComposerDoes() = runTest {
        val c = controller()
        val editor = FullScreenEditorState(initiallyOpen = true)
        c.type("by keyboard")
        assertTrue(editor.enter(c.uiState.value.canSend, c::onHardwareEnter))
        runCurrent()
        assertFalse(editor.isOpen)
        assertEquals("by keyboard", sessions.sent.single().prompt)

        val empty = FullScreenEditorState(initiallyOpen = true)
        assertTrue(empty.enter(c.uiState.value.canSend, c::onHardwareEnter))
        assertTrue("nothing sent: the editor stays", empty.isOpen)
    }
}
