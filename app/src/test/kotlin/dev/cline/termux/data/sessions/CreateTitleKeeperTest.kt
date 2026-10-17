package dev.cline.termux.data.sessions

import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.data.session.RunEnded
import dev.cline.termux.data.session.TurnOutcome
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trial row 16: the create-time title goes back once when the kernel replaced it with the first prompt. */
@OptIn(ExperimentalCoroutinesApi::class)
class CreateTitleKeeperTest {
    private val sid = "s1"
    private val wrapped = "<user_input mode=\"act\">hello there\nsecond line</user_input>"

    private fun record(title: String?, prompt: String? = wrapped): JsonObject = buildJsonObject {
        putJsonObject("session") {
            put("sessionId", sid)
            putJsonObject("metadata") {
                title?.let { put("title", it) }
                prompt?.let { put("prompt", it) }
            }
        }
    }

    private fun end(createTitle: String? = "[test] a13", ours: Boolean = true) =
        RunEnded(sid, "r1", ours, TurnOutcome.Completed, createTitle)

    private class Rig(
        val keeper: CreateTitleKeeper,
        val renames: MutableList<Pair<String, String>>,
        val reads: MutableList<String>,
        val runEnds: MutableSharedFlow<RunEnded>,
    )

    private fun TestScope.rig(
        records: ArrayDeque<() -> JsonObject>,
        outcomes: ArrayDeque<ActionOutcome> = ArrayDeque(listOf(ActionOutcome.Done)),
    ): Rig {
        val renames = mutableListOf<Pair<String, String>>()
        val reads = mutableListOf<String>()
        val runEnds = MutableSharedFlow<RunEnded>(extraBufferCapacity = 8)
        val keeper = CreateTitleKeeper(
            runEnds = runEnds,
            record = { id ->
                reads += id
                (records.removeFirstOrNull() ?: error("unexpected read"))()
            },
            rename = { id, title ->
                renames += id to title
                outcomes.removeFirstOrNull() ?: ActionOutcome.Done
            },
            scope = backgroundScope,
            config = CreateTitleKeeper.Config(settleMs = 1_000, retryMs = 3_000),
        )
        return Rig(keeper, renames, reads, runEnds)
    }

    @Test
    fun `a title the kernel replaced with the first prompt goes back once`() = runTest {
        val r = rig(ArrayDeque(listOf({ record("hello there") })))
        assertEquals(CreateTitleKeeper.Result.Restored, r.keeper.keep(end()))
        assertEquals(listOf(sid to "[test] a13"), r.renames)
        assertEquals(1_000L, testScheduler.currentTime)
    }

    @Test
    fun `a kept title, a title renamed meanwhile and a gone session are left alone`() = runTest {
        val kept = rig(ArrayDeque(listOf({ record("[test] a13") })))
        assertEquals(CreateTitleKeeper.Result.Kept, kept.keeper.keep(end()))
        val renamed = rig(ArrayDeque(listOf({ record("Owner's own name") })))
        assertEquals(CreateTitleKeeper.Result.LeftAlone, renamed.keeper.keep(end()))
        val cleared = rig(ArrayDeque(listOf({ record(title = null) })))
        assertEquals(CreateTitleKeeper.Result.LeftAlone, cleared.keeper.keep(end()))
        val gone = rig(ArrayDeque(listOf({ throw HubCommandException("session.get", "session_not_found", "Unknown session: s1") })))
        assertEquals(CreateTitleKeeper.Result.LeftAlone, gone.keeper.keep(end()))
        listOf(kept, renamed, cleared, gone).forEach { assertTrue(it.renames.isEmpty()) }
    }

    @Test
    fun `a failed rename is retried once and never again`() = runTest {
        val failed = ActionOutcome.Failed(ActionFailure.NotConnected)
        val once = rig(
            ArrayDeque(listOf({ record("hello there") }, { record("hello there") })),
            ArrayDeque(listOf(failed, ActionOutcome.Done)),
        )
        assertEquals(CreateTitleKeeper.Result.Restored, once.keeper.keep(end()))
        assertEquals(2, once.renames.size)
        assertEquals(4_000L, testScheduler.currentTime)

        val twice = rig(
            ArrayDeque(listOf({ record("hello there") }, { record("hello there") })),
            ArrayDeque(listOf(failed, failed)),
        )
        assertEquals(CreateTitleKeeper.Result.Failed, twice.keeper.keep(end()))
        assertEquals(2, twice.renames.size)
        assertEquals(2, twice.reads.size)
    }

    @Test
    fun `a failed read is retried once, and the retry re-checks the title`() = runTest {
        val r = rig(ArrayDeque(listOf({ throw HubConnectionClosedException("closed") }, { record("Renamed by the owner") })))
        assertEquals(CreateTitleKeeper.Result.LeftAlone, r.keeper.keep(end()))
        assertEquals(2, r.reads.size)
        assertTrue(r.renames.isEmpty())
    }

    @Test
    fun `only this client's first prompt into a titled session is checked`() = runTest {
        val r = rig(ArrayDeque(listOf({ record("hello there") })))
        r.keeper.start()
        runCurrent()
        r.runEnds.emit(end(ours = false))
        r.runEnds.emit(end(createTitle = null))
        // The keeper runs in the background scope: virtual time moves past its settle delay.
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(r.reads.isEmpty())
        r.runEnds.emit(end())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(sid to "[test] a13"), r.renames)
        assertEquals(CreateTitleKeeper.Result.Skipped, r.keeper.keep(end(ours = false)))
    }

    @Test
    fun `the kernel title port derives what core derives`() {
        assertEquals("hello there", KernelTitle.fromPrompt(wrapped))
        val notice = "<mode_notice>The user switched from plan mode to act mode before sending this message.</mode_notice>"
        assertEquals("Ship it", KernelTitle.fromPrompt("<user_input mode=\"act\">$notice\n  Ship it  \nnow</user_input>"))
        assertEquals("x".repeat(120), KernelTitle.fromPrompt("x".repeat(130)))
        assertNull(KernelTitle.fromPrompt("<user_input mode=\"act\">   </user_input>"))
        assertNull(KernelTitle.fromPrompt(null))
        assertEquals("[test] a13", KernelTitle.normalize("  [test] a13 "))
        assertNull(KernelTitle.normalize(" "))
    }
}
