package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.KernelGlobalSettings
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsCommands
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsPatch
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompactionModeSourceTest {
    private fun json(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    private var now = 0L
    private var reads = 0
    private var next: () -> KernelGlobalSettings? = { KernelGlobalSettings.DEFAULT }
    private val writes = mutableListOf<KernelGlobalSettingsPatch>()

    private fun TestScope.source(config: CompactionModeSource.Config = CompactionModeSource.Config()) = CompactionModeSource(
        read = {
            reads++
            next()
        },
        write = { patch ->
            writes += patch
            KernelGlobalSettings(toolAutoApprove = null, compactionStrategy = patch.compactionStrategy ?: "basic", compactionEnabled = patch.compactionEnabled)
        },
        nowMs = { now },
        scope = backgroundScope,
        config = config,
    )

    @Test fun `unknown until a read succeeds, then every create carries the stored mode`() = runTest {
        val source = source()
        assertNull(source.settings.value)
        assertEquals(json("""{"enabled":true}"""), source.current())
        next = { KernelGlobalSettings.parse("""{"compactionStrategy":"basic"}""") }
        source.refresh()
        assertEquals(json("""{"enabled":true,"strategy":"basic"}"""), source.current())
    }

    @Test fun `a failed read keeps the last known value`() = runTest {
        val source = source()
        next = { KernelGlobalSettings.parse("""{"compactionEnabled":false}""") }
        source.refresh()
        next = { null }
        source.refresh()
        assertEquals(json("""{"enabled":false}"""), source.current())
        next = { error("termux gone") }
        source.refresh()
        assertEquals(json("""{"enabled":false}"""), source.current())
    }

    @Test fun `a create refreshes only a value older than a minute`() = runTest {
        val source = source()
        source.forCreate()
        assertEquals(1, reads)
        now = 59_000
        source.forCreate()
        assertEquals(1, reads)
        now = 61_000
        next = { KernelGlobalSettings.parse("""{"compactionStrategy":"basic"}""") }
        assertEquals(json("""{"enabled":true,"strategy":"basic"}"""), source.forCreate())
        assertEquals(2, reads)
    }

    @Test fun `a slow refresh before a create is bounded and the create keeps the last value`() = runTest {
        val slow = CompletableDeferred<KernelGlobalSettings?>()
        val source = CompactionModeSource(
            read = { slow.await() },
            write = { error("unused") },
            nowMs = { now },
            scope = backgroundScope,
            config = CompactionModeSource.Config(createWaitMs = 1_500),
        )
        val create = async { source.forCreate() }
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals(json("""{"enabled":true}"""), create.await())
    }

    @Test fun `a read slower than the create's wait still lands, and the next create uses it without reading again`() = runTest {
        val slow = CompletableDeferred<KernelGlobalSettings?>()
        val source = CompactionModeSource(
            read = {
                reads++
                slow.await()
            },
            write = { error("unused") },
            nowMs = { now },
            scope = backgroundScope,
            config = CompactionModeSource.Config(createWaitMs = 1_500),
        )
        val first = async { source.forCreate() }
        advanceTimeBy(1_501)
        runCurrent()
        assertEquals("the first create stopped waiting", json("""{"enabled":true}"""), first.await())
        now = 4_000
        slow.complete(KernelGlobalSettings.parse("""{"compactionStrategy":"basic"}"""))
        runCurrent()
        assertEquals(KernelGlobalSettings.COMPACTION_BASIC, source.settings.value?.compactionStrategy)
        assertEquals(json("""{"enabled":true,"strategy":"basic"}"""), source.forCreate())
        assertEquals("the late answer counts as a fresh read", 1, reads)
    }

    @Test fun `concurrent callers share one read`() = runTest {
        val slow = CompletableDeferred<KernelGlobalSettings?>()
        val source = CompactionModeSource(
            read = {
                reads++
                slow.await()
            },
            write = { error("unused") },
            nowMs = { now },
            scope = backgroundScope,
        )
        source.refreshInBackground()
        val create = async { source.forCreate() }
        val settings = async { source.refresh() }
        runCurrent()
        slow.complete(KernelGlobalSettings.parse("""{"compactionEnabled":false}"""))
        assertEquals(json("""{"enabled":false}"""), create.await())
        assertEquals(false, settings.await()?.compactionEnabled)
        assertEquals(1, reads)
    }

    @Test fun `a read that started before a write does not overwrite it`() = runTest {
        val slow = CompletableDeferred<KernelGlobalSettings?>()
        val source = CompactionModeSource(
            read = { slow.await() },
            write = { patch -> KernelGlobalSettings(toolAutoApprove = null, compactionStrategy = null, compactionEnabled = patch.compactionEnabled) },
            nowMs = { now },
            scope = backgroundScope,
        )
        source.refreshInBackground()
        runCurrent()
        source.set(CompactionMode.Off)
        slow.complete(KernelGlobalSettings.parse("""{"compactionEnabled":true}"""))
        runCurrent()
        assertEquals(json("""{"enabled":false}"""), source.current())
    }

    @Test fun `set writes the choice's patch and adopts the merged file`() = runTest {
        val source = source()
        val merged = source.set(CompactionMode.Off)
        assertEquals(listOf(KernelGlobalSettingsPatch(compactionEnabled = false)), writes)
        assertEquals(merged, source.settings.value)
        assertEquals(json("""{"enabled":false}"""), source.current())
        source.set(CompactionMode.Truncation)
        assertEquals(KernelGlobalSettingsPatch(compactionStrategy = "basic", compactionEnabled = true), writes.last())
        assertEquals(json("""{"enabled":true,"strategy":"basic"}"""), source.current())
    }

    // ---- production reader / writer over RUN_COMMAND ---------------------------------------------

    @Test fun `the production reader parses the file and treats a missing one as defaults`() = runTest {
        val termux = FakeTermuxExecutor()
        termux.on(ClineCommands.Labels.READ_GLOBAL_SETTINGS, TermuxResult.success("""{"compactionStrategy":"basic"}"""))
        val source = CompactionModeSource.of(termux, backgroundScope) { now }
        assertEquals(KernelGlobalSettings.COMPACTION_BASIC, source.refresh()?.compactionStrategy)

        val missing = FakeTermuxExecutor()
        missing.on(ClineCommands.Labels.READ_GLOBAL_SETTINGS, TermuxResult.failure(1, "cat: global-settings.json: No such file or directory"))
        assertEquals(KernelGlobalSettings.DEFAULT, CompactionModeSource.of(missing, backgroundScope) { now }.refresh())

        val blocked = FakeTermuxExecutor().apply { permitted = false }
        assertNull(CompactionModeSource.of(blocked, backgroundScope) { now }.refresh())
    }

    @Test fun `the production writer sends the patch on stdin and maps failures`() = runTest {
        val termux = FakeTermuxExecutor()
        termux.on(KernelGlobalSettingsCommands.LABEL_WRITE, TermuxResult.success("""{"telemetryOptOut":true,"compactionEnabled":false}"""))
        val source = CompactionModeSource.of(termux, backgroundScope) { now }
        val merged = source.set(CompactionMode.Off)
        assertFalse(merged.compactionEnabled ?: true)
        assertEquals("""{"compactionEnabled":false}""", termux.commandsLabelled(KernelGlobalSettingsCommands.LABEL_WRITE).single().stdin)

        termux.on(KernelGlobalSettingsCommands.LABEL_WRITE, TermuxResult.failure(KernelGlobalSettingsCommands.EXIT_UNREADABLE, ""))
        assertSaveError(CompactionSaveError.FileUnreadable) { source.set(CompactionMode.Llm) }

        termux.on(KernelGlobalSettingsCommands.LABEL_WRITE, TermuxResult.failure(1, "error: EACCES\n"))
        assertSaveError(CompactionSaveError.Failed("error: EACCES")) { source.set(CompactionMode.Llm) }

        termux.on(KernelGlobalSettingsCommands.LABEL_WRITE, TermuxResult.undelivered(TermuxResult.ERR_EXECUTABLE_FAILED, "no bun"))
        assertSaveError(CompactionSaveError.TermuxUnavailable) { source.set(CompactionMode.Llm) }

        termux.permitted = false
        assertSaveError(CompactionSaveError.TermuxUnavailable) { source.set(CompactionMode.Llm) }
        // The failed writes left the adopted value alone.
        assertEquals(json("""{"enabled":false}"""), source.current())
    }

    private suspend fun assertSaveError(expected: CompactionSaveError, block: suspend () -> Unit) {
        try {
            block()
            fail("expected $expected")
        } catch (e: CompactionSaveException) {
            assertEquals(expected, e.error)
        }
    }
}
