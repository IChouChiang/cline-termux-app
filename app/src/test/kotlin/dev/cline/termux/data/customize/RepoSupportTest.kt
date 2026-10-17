package dev.cline.termux.data.customize

import dev.cline.termux.core.kernel.dashboard.DesktopCommandException
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.core.kernel.dashboard.HelperUnavailable
import dev.cline.termux.core.kernel.hub.HubClient
import dev.cline.termux.core.kernel.hub.HubClientIdentity
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.InMemoryCursorStore
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.ok
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoSupportTest {

    // ---- Throwable.toRepoFailure ------------------------------------------------------

    @Test
    fun `hub error codes map onto the typed hub failures`() {
        assertEquals(RepoFailure.HubDisconnected, hubError(HubErrorCodes.HUB_CONNECTION_CLOSED).toRepoFailure())
        assertEquals(RepoFailure.HubTimeout, hubError(HubErrorCodes.CLIENT_TIMEOUT).toRepoFailure())
        assertEquals(RepoFailure.HubTimeout, hubError(HubErrorCodes.HUB_COMMAND_TIMEOUT).toRepoFailure())
        assertEquals(RepoFailure.HubDraining, hubError(HubErrorCodes.HUB_DRAINING).toRepoFailure())
        assertEquals(
            "any other code keeps the code and the Hub's message verbatim",
            RepoFailure.Hub(HubErrorCodes.SETTINGS_LIST_FAILED, "Failed to read settings: EACCES"),
            HubCommandException(HubCommands.SETTINGS_LIST, HubErrorCodes.SETTINGS_LIST_FAILED, "Failed to read settings: EACCES").toRepoFailure(),
        )
        assertEquals(RepoFailure.HubDisconnected, HubConnectionClosedException("socket not open").toRepoFailure())
    }

    @Test
    fun `termux unavailability maps onto the kernel blocked problems`() {
        val cases = mapOf(
            TermuxUnavailable.NotInstalled() to TermuxProblem.NotInstalled,
            TermuxUnavailable.PermissionMissing() to TermuxProblem.PermissionMissing,
            TermuxUnavailable.ExternalAppsBlocked("allow-external-apps is false") to TermuxProblem.ExternalAppsBlocked,
            TermuxUnavailable.TimedOut("cline config list", 30_000) to TermuxProblem.TimedOut,
            TermuxUnavailable.StartFailed(SecurityException("denied")) to TermuxProblem.StartFailed,
        )
        cases.forEach { (error, problem) ->
            assertEquals(problem, error.toProblem())
            assertEquals(RepoFailure.Termux(problem), error.toRepoFailure())
        }
    }

    @Test
    fun `helper errors map onto the helper failures`() {
        assertEquals(RepoFailure.HelperDown(HelperFailure.StartTimeout), HelperUnavailable.NotRunning(HelperFailure.StartTimeout).toRepoFailure())
        assertEquals("idle (no lease) carries no reason", RepoFailure.HelperDown(null), HelperUnavailable.NotRunning(null).toRepoFailure())
        assertEquals(RepoFailure.HelperLost, HelperUnavailable.TransportClosed().toRepoFailure())
        assertEquals(RepoFailure.HelperTimeout, HelperUnavailable.Timeout("getMcpServers", 15_000).toRepoFailure())
        assertEquals(
            RepoFailure.Helper("Server not found: github"),
            DesktopCommandException("toggleMcpServer", "Server not found: github").toRepoFailure(),
        )
    }

    @Test
    fun `anything else is malformed with its message, or its class name when it has none`() {
        assertEquals(RepoFailure.Malformed("Unexpected JSON token at offset 3"), IllegalArgumentException("Unexpected JSON token at offset 3").toRepoFailure())
        assertEquals(RepoFailure.Malformed("IllegalStateException"), IllegalStateException().toRepoFailure())
    }

    @Test
    fun `cancellation is rethrown, never mapped`() {
        val cancelled = CancellationException("scope cancelled")
        val thrown = assertThrows(CancellationException::class.java) { cancelled.toRepoFailure() }
        assertSame(cancelled, thrown)
    }

    @Test
    fun `detail is the carried text only for failures that carry text`() {
        assertEquals("m", RepoFailure.Hub("c", "m").detail)
        assertEquals("out", RepoFailure.Command(2, "out").detail)
        assertEquals("h", RepoFailure.Helper("h").detail)
        assertEquals("bad", RepoFailure.Malformed("bad").detail)
        listOf(
            RepoFailure.HubDisconnected,
            RepoFailure.HubTimeout,
            RepoFailure.HubDraining,
            RepoFailure.Termux(TermuxProblem.NotInstalled),
            RepoFailure.HelperDown(null),
            RepoFailure.HelperLost,
            RepoFailure.HelperTimeout,
        ).forEach { assertNull("$it supplies no detail", it.detail) }
    }

    // ---- Outcome / outcomeOf ----------------------------------------------------------

    @Test
    fun `outcomeOf wraps values and failures and lets cancellation through`() = runTest {
        val ok = outcomeOf { 42 }
        assertEquals(Outcome.Ok(42), ok)
        assertEquals(42, ok.valueOrNull())
        assertNull(ok.failureOrNull())

        val failed = outcomeOf<Int> { throw hubError(HubErrorCodes.HUB_DRAINING) }
        assertEquals(Outcome.Failed(RepoFailure.HubDraining), failed)
        assertNull(failed.valueOrNull())
        assertEquals(RepoFailure.HubDraining, failed.failureOrNull())

        val cancelled = runCatching { outcomeOf<Int> { throw CancellationException("stop") } }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    // ---- isUnknownWriteOutcome --------------------------------------------------------

    @Test
    fun `only a lost reply leaves a write's outcome unknown`() {
        assertTrue(isUnknownWriteOutcome(RepoFailure.HubDisconnected))
        assertTrue(isUnknownWriteOutcome(RepoFailure.HubTimeout))
        assertTrue(isUnknownWriteOutcome(RepoFailure.HelperLost))
        listOf(
            RepoFailure.HubDraining,
            RepoFailure.Hub(HubErrorCodes.COMMAND_FAILED, "no"),
            RepoFailure.Termux(TermuxProblem.TimedOut),
            RepoFailure.Command(1, "boom"),
            RepoFailure.HelperDown(HelperFailure.SocketFailed),
            RepoFailure.Helper("no"),
            RepoFailure.HelperTimeout,
            RepoFailure.Malformed("x"),
        ).forEach { assertFalse("$it is a known outcome", isUnknownWriteOutcome(it)) }
    }

    // ---- HubClient.idempotentRead / write ----------------------------------------------

    @Test
    fun `a read whose reply is lost to a closed socket waits for Connected and is sent once more`() = runTest {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope)
        var listCalls = 0
        transport.onCommand = { env ->
            if (env.command == HubCommands.SESSION_LIST) {
                listCalls++
                if (listCalls == 1) null else env.ok(buildJsonObject { put("attempt", listCalls) })
            } else {
                transport.defaultReply(env)
            }
        }
        val read = async { hub.idempotentRead { hub.command(HubCommands.SESSION_LIST) } }
        runCurrent()
        assertEquals(1, listCalls)
        assertFalse(read.isCompleted)

        transport.current.drop()
        settle()

        assertEquals("the retry went out on the replacement socket", 2, transport.sockets.size)
        assertEquals(2, listCalls)
        assertEquals("2", read.await()["attempt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a read on a socket that is not open retries once the client connects`() = runTest {
        val hub = idleHub(backgroundScope)
        var calls = 0
        val read = async {
            hub.idempotentRead {
                calls++
                if (calls == 1) throw HubConnectionClosedException("socket not open") else "listed"
            }
        }
        runCurrent()
        assertEquals(1, calls)
        assertFalse("waits for Connected before the retry", read.isCompleted)

        hub.connect()
        settle()

        assertEquals("listed", read.await())
        assertEquals(2, calls)
    }

    @Test
    fun `a read retries only once`() = runTest {
        val hub = connectedHub(FakeHubTransport(), backgroundScope)
        var calls = 0
        val error = runCatching {
            hub.idempotentRead<Unit> {
                calls++
                throw hubError(HubErrorCodes.HUB_CONNECTION_CLOSED)
            }
        }.exceptionOrNull() as HubCommandException
        assertEquals(2, calls)
        assertEquals(HubErrorCodes.HUB_CONNECTION_CLOSED, error.code)
    }

    @Test
    fun `a timed out read is rethrown without a retry`() = runTest {
        val hub = connectedHub(FakeHubTransport(), backgroundScope)
        listOf(HubErrorCodes.CLIENT_TIMEOUT, HubErrorCodes.HUB_COMMAND_TIMEOUT).forEach { code ->
            var calls = 0
            val timeout = hubError(code)
            val thrown = runCatching {
                hub.idempotentRead<Unit> {
                    calls++
                    throw timeout
                }
            }.exceptionOrNull()
            assertSame(timeout, thrown)
            assertEquals("$code is not retried", 1, calls)
        }
    }

    @Test
    fun `a read whose socket does not come back within the wait rethrows the original error`() = runTest {
        val hub = idleHub(backgroundScope)
        var calls = 0
        val closed = hubError(HubErrorCodes.HUB_CONNECTION_CLOSED)
        val read = async {
            runCatching {
                hub.idempotentRead<Unit>(waitMs = 1_000) {
                    calls++
                    throw closed
                }
            }.exceptionOrNull()
        }
        advanceTimeBy(999)
        runCurrent()
        assertFalse(read.isCompleted)
        advanceTimeBy(2)
        runCurrent()
        assertSame(closed, read.await())
        assertEquals(1, calls)
    }

    @Test
    fun `a read refused while draining is sent once more, other errors are never retried`() = runTest {
        val hub = connectedHub(FakeHubTransport(), backgroundScope)
        var calls = 0
        val value = hub.idempotentRead {
            calls++
            if (calls == 1) throw hubError(HubErrorCodes.HUB_DRAINING) else "settings"
        }
        assertEquals("settings", value)
        assertEquals(2, calls)

        calls = 0
        val failed = hubError(HubErrorCodes.SETTINGS_LIST_FAILED)
        val thrown = runCatching {
            hub.idempotentRead<Unit> {
                calls++
                throw failed
            }
        }.exceptionOrNull()
        assertSame(failed, thrown)
        assertEquals(1, calls)

        calls = 0
        val malformed = IllegalArgumentException("not JSON")
        assertSame(malformed, runCatching { hub.idempotentRead<Unit> { calls++; throw malformed } }.exceptionOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun `a write is resent only after a drain refusal`() = runTest {
        val hub = connectedHub(FakeHubTransport(), backgroundScope)
        var calls = 0
        val written = hub.write {
            calls++
            if (calls == 1) throw hubError(HubErrorCodes.HUB_DRAINING) else "saved"
        }
        assertEquals("saved", written)
        assertEquals(2, calls)

        listOf(HubErrorCodes.HUB_CONNECTION_CLOSED, HubErrorCodes.CLIENT_TIMEOUT).forEach { code ->
            calls = 0
            val lost = hubError(code)
            assertSame(lost, runCatching { hub.write<Unit> { calls++; throw lost } }.exceptionOrNull())
            assertEquals("a lost write reply ($code) is never resent", 1, calls)
        }
    }

    // ---- HubClient.reconnects ---------------------------------------------------------

    @Test
    fun `reconnects emits once per return to Connected, not for the first observation`() = runTest {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope)
        var seen = 0
        backgroundScope.launch { hub.reconnects().collect { seen++ } }
        runCurrent()
        assertEquals("already Connected at the first observation", 0, seen)

        transport.current.drop()
        settle()
        assertEquals(1, seen)

        transport.current.drop()
        settle()
        assertEquals(2, seen)
    }

    @Test
    fun `reconnects counts the first connect when collection started before it`() = runTest {
        val hub = idleHub(backgroundScope)
        var seen = 0
        backgroundScope.launch { hub.reconnects().collect { seen++ } }
        runCurrent()
        assertEquals(0, seen)
        hub.connect()
        settle()
        assertEquals(1, seen)
    }

    // ---- CliOutput --------------------------------------------------------------------

    @Test
    fun `output joins trimmed stdout and stderr and is empty when both are blank`() {
        assertEquals("installed\nwarning: deprecated", CliOutput.of(TermuxResult.failure(1, stderr = "  warning: deprecated\n", stdout = "\ninstalled\n")))
        assertEquals("only stderr", CliOutput.of(TermuxResult.failure(1, stderr = "only stderr\n")))
        assertEquals("only stdout", CliOutput.of(TermuxResult.success("only stdout\n", stderr = " \n")))
        assertEquals("", CliOutput.of(TermuxResult.failure(1, stderr = "\n  ", stdout = "")))
    }

    @Test
    fun `output is redacted`() {
        val result = TermuxResult.failure(
            exitCode = 1,
            stdout = "GET with Authorization Bearer sk-live-123",
            stderr = "error: cannot open ${TermuxPaths.HOME}/.cline/data/settings.json apiKey=sk-ant-999",
        )
        val shaped = CliOutput.of(result)
        assertFalse(shaped.contains("sk-live-123"))
        assertFalse(shaped.contains("sk-ant-999"))
        assertFalse(shaped.contains(TermuxPaths.HOME))
        assertTrue(shaped.contains("Bearer <redacted>"))
        assertTrue(shaped.contains("~/.cline/data/settings.json"))
    }

    @Test
    fun `truncated output keeps the last 12 lines`() {
        val lines = (1..20).map { "line $it" }
        val stdout = lines.joinToString("\n")
        val result = TermuxResult(stdout, "", exitCode = 1, err = TermuxResult.ERR_NONE, errmsg = null, stdoutOriginalLength = stdout.length + 5_000)
        assertTrue(result.truncated)
        assertEquals(lines.takeLast(CliOutput.TRUNCATED_TAIL_LINES).joinToString("\n"), CliOutput.of(result))
    }

    @Test
    fun `untruncated output keeps every line`() {
        val stdout = (1..20).joinToString("\n") { "line $it" }
        val result = TermuxResult.failure(1, stderr = "", stdout = stdout)
        assertFalse(result.truncated)
        assertEquals(stdout, CliOutput.of(result))
    }

    @Test
    fun `output over 12k characters keeps the tail`() {
        val result = TermuxResult.failure(1, stderr = "", stdout = "HEAD" + "z".repeat(12_500))
        val shaped = CliOutput.of(result)
        assertEquals(CliOutput.MAX_OUTPUT_CHARS, shaped.length)
        assertEquals("z".repeat(CliOutput.MAX_OUTPUT_CHARS), shaped)
    }

    @Test
    fun `truncated output whose last 12 lines exceed 12k characters keeps the tail of those lines`() {
        val lines = (1..20).map { n -> "$n:" + "y".repeat(1_500) }
        val stdout = lines.joinToString("\n")
        val result = TermuxResult(stdout, "", exitCode = 1, err = TermuxResult.ERR_NONE, errmsg = null, stdoutOriginalLength = stdout.length + 1)
        val shaped = CliOutput.of(result)
        assertEquals(CliOutput.MAX_OUTPUT_CHARS, shaped.length)
        assertTrue(lines.takeLast(CliOutput.TRUNCATED_TAIL_LINES).joinToString("\n").endsWith(shaped))
        assertTrue(shaped.endsWith(lines.last()))
    }

    @Test
    fun `error line prefers stderr, then stdout, then errmsg, then the exit code`() {
        assertEquals("error: bad flag", CliOutput.errorLine(TermuxResult.failure(1, stderr = "warning\nerror: bad flag\n\n", stdout = "progress")))
        assertEquals("last stdout", CliOutput.errorLine(TermuxResult.failure(1, stderr = " \n", stdout = "first\nlast stdout\n")))
        assertEquals("Permission denied", CliOutput.errorLine(TermuxResult.undelivered(TermuxResult.ERR_EXECUTABLE_FAILED, "Permission denied")))
        assertEquals("exit code 3", CliOutput.errorLine(TermuxResult.failure(3, stderr = "")))
        assertEquals("error: token=<redacted>", CliOutput.errorLine(TermuxResult.failure(1, stderr = "error: token=abc123")))
    }

    // ---- TermuxExecutor.runChecked ----------------------------------------------------

    @Test
    fun `runChecked passes success through and turns a non-zero exit into a command failure`() = runTest {
        val termux = FakeTermuxExecutor()
        val ok = TermuxResult.success("done\n")
        termux.on("ok", ok)
        termux.on("loud", TermuxResult.failure(2, stderr = "error: Bearer secret-token"))
        termux.on("quiet", TermuxResult.failure(5, stderr = ""))
        termux.throwing("blocked", TermuxUnavailable.ExternalAppsBlocked("allow-external-apps"))

        assertEquals(Outcome.Ok(ok), termux.runChecked(command("ok")))
        assertEquals(Outcome.Failed(RepoFailure.Command(2, "error: Bearer <redacted>")), termux.runChecked(command("loud")))
        assertEquals(
            "no output falls back to the CLI error line",
            Outcome.Failed(RepoFailure.Command(5, "exit code 5")),
            termux.runChecked(command("quiet")),
        )
        assertEquals(Outcome.Failed(RepoFailure.Termux(TermuxProblem.ExternalAppsBlocked)), termux.runChecked(command("blocked")))
    }

    // ---- helpers ----------------------------------------------------------------------

    /**
     * Lets the client's reconnect loop run: it lives on `backgroundScope`, which
     * `advanceUntilIdle` ignores, so virtual time is advanced past the longest backoff
     * (5 s base + 50 % jitter) instead.
     */
    private fun TestScope.settle() {
        advanceTimeBy(RECONNECT_SETTLE_MS)
        runCurrent()
    }

    private fun hubError(code: String) = HubCommandException(HubCommands.SETTINGS_LIST, code, code)

    private fun command(label: String) = TermuxCommand(path = TermuxPaths.CLINE, args = listOf(label), label = label)

    /** A client that has not connected yet (state `Disconnected`); [HubClient.connect] opens it. */
    private fun idleHub(scope: CoroutineScope): HubClient {
        val transport = FakeHubTransport()
        transport.onCommand = { env -> transport.defaultReply(env) }
        return HubClient(
            transport = transport,
            identity = HubClientIdentity(clientId = "android-test", appVersion = "0.1.0", workspaceRoot = "/w"),
            scope = scope,
            cursors = InMemoryCursorStore(),
            credentials = { HubClient.HubCredentials("ws://127.0.0.1:25463/hub", "tok") },
            random = Random(1),
        )
    }

    private companion object {
        const val RECONNECT_SETTLE_MS = 10_000L
    }
}
