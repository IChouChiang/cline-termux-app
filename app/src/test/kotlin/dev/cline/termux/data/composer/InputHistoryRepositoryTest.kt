package dev.cline.termux.data.composer

import dev.cline.termux.core.kernel.termux.InputHistoryCommands
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A11.6: the shared cache of recent prompts and its Termux reads and appends. */
@OptIn(ExperimentalCoroutinesApi::class)
class InputHistoryRepositoryTest {

    private val termux = FakeTermuxExecutor()
    private var now = 1_000_000L
    private val logs = mutableListOf<String>()

    private fun file(vararg oldestFirst: String) =
        TermuxResult.success(oldestFirst.joinToString("\n", postfix = "\n") { """{"prompt":"$it","ts":1}""" })

    private fun TestScope.repository(): InputHistoryRepository {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        return InputHistoryRepository(termux, scope, clockMs = { now }, log = { logs += it })
    }

    @Test fun refreshReadsTheFileNewestFirst() = runTest {
        termux.on(InputHistoryCommands.LABEL_READ, file("old", "mid", "new"))
        val repo = repository()
        repo.refresh()
        runCurrent()
        assertEquals(InputHistory(listOf("new", "mid", "old"), InputHistoryStatus.Loaded), repo.state.value)
    }

    @Test fun refreshReadsAtMostOncePerThirtySeconds() = runTest {
        termux.on(InputHistoryCommands.LABEL_READ, file("a"))
        val repo = repository()
        repo.refresh()
        now += 29_999
        repo.refresh()
        runCurrent()
        assertEquals(1, termux.commandsLabelled(InputHistoryCommands.LABEL_READ).size)
        now += 1
        repo.refresh()
        runCurrent()
        assertEquals(2, termux.commandsLabelled(InputHistoryCommands.LABEL_READ).size)
    }

    @Test fun aMissingFileLoadsEmpty() = runTest {
        termux.on(InputHistoryCommands.LABEL_READ, TermuxResult.failure(1, "cat: user_input_history.jsonl: No such file or directory"))
        val repo = repository()
        repo.ensureLoaded()
        assertEquals(InputHistory(emptyList(), InputHistoryStatus.Loaded), repo.state.value)
    }

    @Test fun aFailedReadIsLoggedWithoutContentAndMarkedFailed() = runTest {
        termux.throwing(InputHistoryCommands.LABEL_READ, TermuxUnavailable.PermissionMissing())
        val repo = repository()
        repo.ensureLoaded()
        assertEquals(InputHistoryStatus.Failed, repo.state.value.status)
        assertEquals(1, logs.size)
        assertTrue(logs.single().startsWith(InputHistoryCommands.LABEL_READ))
    }

    @Test fun ensureLoadedReadsOnlyOnce() = runTest {
        termux.on(InputHistoryCommands.LABEL_READ, file("a"))
        val repo = repository()
        repo.ensureLoaded()
        repo.ensureLoaded()
        assertEquals(1, termux.commandsLabelled(InputHistoryCommands.LABEL_READ).size)
        assertEquals(listOf("a"), repo.state.value.entries)
    }

    @Test fun recordShowsThePromptAtOnceAndAppendsThroughTermux() = runTest {
        termux.on(InputHistoryCommands.LABEL_READ, file("a", "b"))
        val repo = repository()
        repo.ensureLoaded()
        repo.record("  a  ")
        assertEquals(listOf("a", "b"), repo.state.value.entries)
        runCurrent()
        val append = termux.commandsLabelled(InputHistoryCommands.LABEL_APPEND).single()
        assertEquals("a", append.stdin)
    }

    @Test fun aBlankPromptIsNotRecorded() = runTest {
        val repo = repository()
        repo.record("   ")
        runCurrent()
        assertFalse(termux.ran(InputHistoryCommands.LABEL_APPEND))
        assertTrue(repo.state.value.entries.isEmpty())
    }

    @Test fun aFailedAppendIsOnlyLogged() = runTest {
        termux.on(InputHistoryCommands.LABEL_APPEND, TermuxResult.failure(InputHistoryCommands.EXIT_UNREADABLE, ""))
        val repo = repository()
        repo.record("private words")
        runCurrent()
        assertEquals(listOf("private words"), repo.state.value.entries)
        assertEquals(1, logs.size)
        assertTrue(logs.single().contains("exit=3"))
        assertTrue("a log line never carries the prompt", logs.none { it.contains("private") })
    }

    @Test fun aPromptRecordedDuringARead() = runTest {
        // The read was answered with the file as it was before the append.
        val gate = CompletableDeferred<Unit>()
        termux.on(InputHistoryCommands.LABEL_READ) {
            gate.await()
            file("old")
        }
        val repo = repository()
        repo.refresh()
        runCurrent()
        repo.record("fresh")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("fresh", "old"), repo.state.value.entries)
        assertEquals(InputHistoryStatus.Loaded, repo.state.value.status)
        // The append waited for the read (one at a time).
        assertEquals(listOf(InputHistoryCommands.LABEL_READ, InputHistoryCommands.LABEL_APPEND), termux.labels)
    }

    @Test fun aReadAfterAnAppendSeesIt() = runTest {
        val written = mutableListOf("old")
        termux.on(InputHistoryCommands.LABEL_READ) { file(*written.toTypedArray()) }
        termux.on(InputHistoryCommands.LABEL_APPEND) { cmd ->
            written += cmd.stdin!!
            TermuxResult.success("")
        }
        val repo = repository()
        repo.record("new")
        runCurrent()
        repo.ensureLoaded()
        assertEquals(listOf("new", "old"), repo.state.value.entries)
    }

    @Test fun whileLoadingTheStatusSaysSo() = runTest {
        val gate = CompletableDeferred<Unit>()
        termux.on(InputHistoryCommands.LABEL_READ) {
            gate.await()
            file("a")
        }
        val repo = repository()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.ensureLoaded() }
        assertEquals(InputHistoryStatus.Loading, repo.state.value.status)
        gate.complete(Unit)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(InputHistoryStatus.Loaded, repo.state.value.status)
    }
}
