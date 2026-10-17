package dev.cline.termux.data.approvals

import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.KernelGlobalSettingsCommands
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kernel's global `toolAutoApprove` (decided A1; batch 1 brief §7.1). */
@OptIn(ExperimentalCoroutinesApi::class)
class ApprovalModeRepositoryTest {
    private val commands = mutableListOf<TermuxCommand>()
    private var reply: suspend (TermuxCommand) -> TermuxResult = { ok("{}") }

    private fun ok(stdout: String) = TermuxResult(stdout = stdout, stderr = "", exitCode = 0, err = TermuxResult.ERR_NONE, errmsg = null)

    private fun exit(code: Int, stderr: String = "") = TermuxResult(stdout = "", stderr = stderr, exitCode = code, err = TermuxResult.ERR_NONE, errmsg = null)

    private val missing = exit(1, "cat: /data/data/com.termux/files/home/.cline/data/settings/global-settings.json: No such file or directory")

    /** The repository's monotonic clock, in milliseconds. */
    private var now = 1_000L

    private fun TestScope.repository() = ApprovalModeRepository(
        run = { command ->
            commands += command
            reply(command)
        },
        scope = backgroundScope,
        clock = { now },
    )

    private val reads get() = commands.count { it.label == ClineCommands.Labels.READ_GLOBAL_SETTINGS }
    private val writes get() = commands.filter { it.label == KernelGlobalSettingsCommands.LABEL_WRITE }

    @Test
    fun `a missing key is Auto-approve all, false is Ask each time`() = runTest {
        val repo = repository()
        assertEquals(ApprovalMode.Unknown, repo.mode.value)
        reply = { ok("""{"telemetryOptOut":true}""") }
        assertEquals(ApprovalMode.Auto, repo.refresh(ApprovalRefresh.Foreground))
        reply = { ok("""{"toolAutoApprove":false}""") }
        assertEquals(ApprovalMode.Ask, repo.refresh(ApprovalRefresh.Foreground))
        assertEquals(ApprovalMode.Ask, repo.mode.value)
    }

    @Test
    fun `maxAgeMs reuses a read or write that recent and reads again after it`() = runTest {
        val repo = repository()
        reply = { ok("""{"toolAutoApprove":false}""") }
        // Nothing read yet: a max age never skips the first read.
        assertEquals(ApprovalMode.Ask, repo.refresh(ApprovalRefresh.BeforeSend, maxAgeMs = 5_000))
        assertEquals(1, reads)
        now += 5_000
        reply = { ok("{}") }
        assertEquals(ApprovalMode.Ask, repo.refresh(ApprovalRefresh.BeforeSend, maxAgeMs = 5_000))
        assertEquals(1, reads)
        now += 1
        assertEquals(ApprovalMode.Auto, repo.refresh(ApprovalRefresh.BeforeSend, maxAgeMs = 5_000))
        assertEquals(2, reads)
        // A write counts as current too; a plain refresh always reads.
        reply = { ok("""{"toolAutoApprove":false}""") }
        repo.set(false)
        now += 4_000
        assertEquals(ApprovalMode.Ask, repo.refresh(ApprovalRefresh.BeforeSend, maxAgeMs = 5_000))
        assertEquals(2, reads)
        repo.refresh(ApprovalRefresh.MenuOpen)
        assertEquals(3, reads)
    }

    @Test
    fun `a missing file is Auto-approve all, like the terminal`() = runTest {
        reply = { missing }
        val repo = repository()
        assertEquals(ApprovalMode.Auto, repo.refresh(ApprovalRefresh.Foreground))
    }

    @Test
    fun `an unreadable result keeps the last known mode, and Unknown before any`() = runTest {
        val repo = repository()
        reply = { throw TermuxUnavailable.PermissionMissing() }
        assertEquals(ApprovalMode.Unknown, repo.refresh(ApprovalRefresh.Foreground))
        // A new session asks while the mode is unknown (the safe value).
        assertFalse(repo.autoApproveForNewSession())

        reply = { ok("""{"toolAutoApprove":true}""") }
        assertEquals(ApprovalMode.Auto, repo.refresh(ApprovalRefresh.Foreground))
        reply = { ok("{broken") }
        assertEquals(ApprovalMode.Auto, repo.refresh(ApprovalRefresh.Foreground))
        reply = { exit(1, "Permission denied") }
        assertEquals(ApprovalMode.Auto, repo.refresh(ApprovalRefresh.Foreground))
        reply = { throw TermuxUnavailable.TimedOut("cat global-settings.json", 5_000) }
        assertEquals(ApprovalMode.Auto, repo.refresh(ApprovalRefresh.Foreground))
    }

    @Test
    fun `a new session reads once while unknown, then uses the known mode`() = runTest {
        reply = { ok("""{"toolAutoApprove":false}""") }
        val repo = repository()
        assertFalse(repo.autoApproveForNewSession())
        assertFalse(repo.autoApproveForNewSession())
        assertEquals(1, reads)
        reply = { ok("""{"toolAutoApprove":true}""") }
        assertTrue(repo.autoApproveAfterRefresh(ApprovalRefresh.Resume))
        assertEquals(2, reads)
    }

    @Test
    fun `concurrent refreshes share one read`() = runTest {
        val gate = CompletableDeferred<TermuxResult>()
        reply = { gate.await() }
        val repo = repository()
        val a = async { repo.refresh(ApprovalRefresh.MenuOpen) }
        val b = async { repo.refresh(ApprovalRefresh.SessionOpen) }
        runCurrent()
        gate.complete(ok("""{"toolAutoApprove":false}"""))
        assertEquals(ApprovalMode.Ask, a.await())
        assertEquals(ApprovalMode.Ask, b.await())
        assertEquals(1, reads)
    }

    @Test
    fun `a write sends only the changed key and adopts the merged file`() = runTest {
        reply = { command ->
            if (command.label == KernelGlobalSettingsCommands.LABEL_WRITE) ok("""{"telemetryOptOut":true,"toolAutoApprove":true}""") else ok("""{"toolAutoApprove":false}""")
        }
        val repo = repository()
        repo.refresh(ApprovalRefresh.Foreground)
        assertEquals(ApprovalWriteResult.Written, repo.set(true))
        assertEquals(ApprovalMode.Auto, repo.mode.value)
        assertEquals("""{"toolAutoApprove":true}""", writes.single().stdin)
        assertFalse(repo.writing.value)
    }

    @Test
    fun `the switch is optimistic while the write runs`() = runTest {
        val gate = CompletableDeferred<TermuxResult>()
        reply = { command -> if (command.label == KernelGlobalSettingsCommands.LABEL_WRITE) gate.await() else ok("""{"toolAutoApprove":false}""") }
        val repo = repository()
        repo.refresh(ApprovalRefresh.Foreground)
        val write = async { repo.set(true) }
        runCurrent()
        assertEquals(ApprovalMode.Auto, repo.mode.value)
        assertTrue(repo.writing.value)
        gate.complete(ok("""{"toolAutoApprove":true}"""))
        assertEquals(ApprovalWriteResult.Written, write.await())
        assertFalse(repo.writing.value)
    }

    @Test
    fun `a failed write rolls back and says why`() = runTest {
        var writeReply: TermuxResult = exit(KernelGlobalSettingsCommands.EXIT_UNREADABLE)
        reply = { command -> if (command.label == KernelGlobalSettingsCommands.LABEL_WRITE) writeReply else ok("""{"toolAutoApprove":false}""") }
        val repo = repository()
        repo.refresh(ApprovalRefresh.Foreground)

        assertEquals(ApprovalWriteResult.Failed(ApprovalWriteFailure.Unreadable), repo.set(true))
        assertEquals(ApprovalMode.Ask, repo.mode.value)

        writeReply = exit(1, "EACCES: permission denied")
        assertEquals(ApprovalWriteResult.Failed(ApprovalWriteFailure.Failed(1, "EACCES: permission denied")), repo.set(true))
        assertEquals(ApprovalMode.Ask, repo.mode.value)

        reply = { command -> if (command.label == KernelGlobalSettingsCommands.LABEL_WRITE) throw TermuxUnavailable.PermissionMissing() else ok("{}") }
        val unavailable = repo.set(true) as ApprovalWriteResult.Failed
        assertTrue(unavailable.failure is ApprovalWriteFailure.Unavailable)
        assertEquals(ApprovalMode.Ask, repo.mode.value)
        assertFalse(repo.writing.value)
    }

    @Test
    fun `a change made outside the app is reported once, never for the app's own write`() = runTest {
        var stored = """{"toolAutoApprove":false}"""
        reply = { command ->
            if (command.label == KernelGlobalSettingsCommands.LABEL_WRITE) {
                stored = """{"toolAutoApprove":true}"""
                ok(stored)
            } else {
                ok(stored)
            }
        }
        val repo = repository()
        val changes = mutableListOf<Boolean>()
        backgroundScope.launch { repo.externalChanges.collect { changes += it } }
        runCurrent()

        repo.refresh(ApprovalRefresh.Foreground)
        // The first read is not a change.
        assertTrue(changes.isEmpty())
        repo.set(true)
        repo.refresh(ApprovalRefresh.MenuOpen)
        runCurrent()
        assertTrue("the app's own write is not news", changes.isEmpty())

        stored = """{"toolAutoApprove":false}"""
        repo.refresh(ApprovalRefresh.Foreground)
        repo.refresh(ApprovalRefresh.SessionOpen)
        runCurrent()
        assertEquals(listOf(false), changes)
    }
}
