package dev.cline.termux.ui.composer

import kotlinx.coroutines.ExperimentalCoroutinesApi
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

/** C7: the desktop's streaming insertion cases (chat-input-bar.test.tsx) and the recogniser rules. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceInputTest {

    // ---- insertion (ported desktop cases) ------------------------------------------------------

    @Test fun insertsCumulativeTranscriptsWithoutDuplicatingText() {
        val insertion = VoiceInsertion()
        insertion.begin("alpha omega", 6, 6)
        val first = insertion.apply("alpha omega", "hello")!!
        assertEquals("alpha hello omega", first.first)
        val second = insertion.apply(first.first, "hello world")!!
        assertEquals("alpha hello world omega", second.first)
    }

    @Test fun keepsTheDraftIdentityAcrossInternalUpdates() {
        val insertion = VoiceInsertion()
        insertion.begin("alpha omega", 6, 6)
        val (text, cursor) = insertion.apply("alpha omega", "hello")!!
        assertEquals("alpha hello omega", text)
        assertEquals(12, cursor)
        assertEquals("alpha hello world omega", insertion.apply(text, "hello world")!!.first)
    }

    @Test fun anotherWriterDropsTheRange() {
        // "protects the draft … external replacement … must not overwrite".
        val insertion = VoiceInsertion()
        insertion.begin("alpha omega", 6, 6)
        insertion.apply("alpha omega", "hello")
        assertNull(insertion.apply("external replacement", "must not overwrite"))
        assertFalse(insertion.active)
        assertNull(insertion.apply("external replacement", "still not"))
    }

    @Test fun discardsUpdatesAfterAnEqualValuedReplacementOfAnotherDraft() {
        val insertion = VoiceInsertion()
        insertion.begin("", 0, 0)
        assertEquals("stale transcript" to 16, insertion.apply("", "stale transcript"))
        insertion.end()
        assertNull(insertion.apply("", "stale transcript"))
    }

    @Test fun spacingRulesAtEdgesAndSelections() {
        val insertion = VoiceInsertion()
        insertion.begin("", 0, 0)
        assertEquals("hi" to 2, insertion.apply("", "  hi  "))
        val replace = VoiceInsertion()
        replace.begin("say WORD now", 4, 8)
        assertEquals("say this now", replace.apply("say WORD now", "this")!!.first)
        val atEnd = VoiceInsertion()
        atEnd.begin("text", 4, 4)
        assertEquals("text more" to 9, atEnd.apply("text", "more"))
        val beforeSpace = VoiceInsertion()
        beforeSpace.begin("a  b", 2, 2)
        assertEquals("a more b", beforeSpace.apply("a  b", "more")!!.first.replace("  ", " "))
    }

    @Test fun blankTranscriptChangesNothing() {
        val insertion = VoiceInsertion()
        insertion.begin("x", 1, 1)
        assertNull(insertion.apply("x", "   "))
        assertTrue(insertion.active)
    }

    // ---- recogniser rules --------------------------------------------------------------------------

    private class FakeEngine : SpeechEngine {
        var listener: SpeechListener? = null
        var starts = 0
        var stops = 0
        var cancels = 0
        var destroys = 0
        var startResult = true
        override fun start(listener: SpeechListener): Boolean {
            this.listener = listener
            starts++
            return startResult
        }
        override fun stop() { stops++ }
        override fun cancel() { cancels++ }
        override fun destroy() { destroys++ }
    }

    private fun TestScope.voice(engine: FakeEngine, available: Boolean = true) =
        VoiceInputController(engine, backgroundScope, available)

    private fun TestScope.events(controller: VoiceInputController): MutableList<VoiceEvent> {
        val list = mutableListOf<VoiceEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { controller.events.collect { list += it } }
        return list
    }

    @Test fun unavailableNeverStarts() = runTest {
        val engine = FakeEngine()
        val v = voice(engine, available = false)
        v.start()
        assertEquals(VoiceState.Unavailable, v.state.value)
        assertEquals(0, engine.starts)
    }

    @Test fun partialsStreamAndResultsRestartTheNextUtterance() = runTest {
        val engine = FakeEngine()
        val v = voice(engine)
        val events = events(v)
        v.start()
        assertEquals(VoiceState.Listening(0f, ""), v.state.value)
        engine.listener!!.onRms(4f)
        assertEquals(0.5f, (v.state.value as VoiceState.Listening).level)
        engine.listener!!.onPartial("fix the")
        engine.listener!!.onResult("fix the parser")
        assertTrue(VoiceEvent.Transcript("fix the parser") in events)
        advanceTimeBy(251)
        runCurrent()
        assertEquals(2, engine.starts)
        assertEquals(
            listOf(VoiceEvent.Active(true), VoiceEvent.UtteranceStarted, VoiceEvent.Transcript("fix the"), VoiceEvent.Transcript("fix the parser"), VoiceEvent.UtteranceEnded, VoiceEvent.UtteranceStarted),
            events,
        )
    }

    @Test fun twoSilentTimeoutsEndTheSession() = runTest {
        val engine = FakeEngine()
        val v = voice(engine)
        v.start()
        engine.listener!!.onFailure(SpeechFailure.SpeechTimeout)
        advanceTimeBy(251)
        runCurrent()
        assertEquals(2, engine.starts)
        engine.listener!!.onFailure(SpeechFailure.NoMatch)
        assertEquals(VoiceState.Idle, v.state.value)
    }

    @Test fun busyRetriesOnceThenFails() = runTest {
        val engine = FakeEngine()
        val v = voice(engine)
        val events = events(v)
        v.start()
        engine.listener!!.onFailure(SpeechFailure.Busy)
        advanceTimeBy(901)
        runCurrent()
        assertEquals(2, engine.starts)
        engine.listener!!.onFailure(SpeechFailure.Busy)
        assertEquals(VoiceState.Idle, v.state.value)
        assertTrue(VoiceEvent.Failed(VoiceError.Busy) in events)
    }

    @Test fun errorMapping() = runTest {
        for ((failure, error) in listOf(
            SpeechFailure.Audio to VoiceError.Audio,
            SpeechFailure.Network to VoiceError.Network,
            SpeechFailure.Permission to VoiceError.Permission,
        )) {
            val engine = FakeEngine()
            val v = voice(engine)
            val events = events(v)
            v.start()
            engine.listener!!.onFailure(failure)
            assertTrue(VoiceEvent.Failed(error) in events)
            assertEquals(VoiceState.Idle, v.state.value)
        }
    }

    @Test fun otherErrorsRecreateOnceThenFailWithTheName() = runTest {
        val engine = FakeEngine()
        val v = voice(engine)
        val events = events(v)
        v.start()
        engine.listener!!.onFailure(SpeechFailure.Other("ERROR_CLIENT"))
        assertEquals(1, engine.destroys)
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(2, engine.starts)
        engine.listener!!.onFailure(SpeechFailure.Other("ERROR_CLIENT"))
        assertTrue(VoiceEvent.Failed(VoiceError.Generic("ERROR_CLIENT")) in events)
    }

    @Test fun stopCommitsTheFinalResultThenIdle() = runTest {
        val engine = FakeEngine()
        val v = voice(engine)
        val events = events(v)
        v.start()
        v.stop()
        assertEquals(VoiceState.Processing, v.state.value)
        assertEquals(1, engine.stops)
        engine.listener!!.onResult("done")
        assertEquals(VoiceState.Idle, v.state.value)
        assertTrue(VoiceEvent.Transcript("done") in events)
        advanceTimeBy(5_000)
        assertEquals(1, engine.starts)
    }

    @Test fun processingGivesUpAfterThreeSeconds() = runTest {
        val engine = FakeEngine()
        val v = voice(engine)
        v.start()
        v.stop()
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(VoiceState.Idle, v.state.value)
        assertEquals(1, engine.cancels)
    }

    @Test fun cancelReleasesTheRecogniser() = runTest {
        val engine = FakeEngine()
        val v = voice(engine)
        v.start()
        v.cancel()
        assertEquals(VoiceState.Idle, v.state.value)
        assertEquals(1, engine.destroys)
        engine.listener!!.onPartial("late")
        assertEquals(VoiceState.Idle, v.state.value)
    }

    @Test fun aRecogniserThatCannotStartFails() = runTest {
        val engine = FakeEngine().apply { startResult = false }
        val v = voice(engine)
        val events = events(v)
        v.start()
        assertEquals(VoiceState.Idle, v.state.value)
        assertTrue(VoiceEvent.Failed(VoiceError.Unavailable) in events)
    }

}
