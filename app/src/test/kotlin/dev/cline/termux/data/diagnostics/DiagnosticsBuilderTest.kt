package dev.cline.termux.data.diagnostics

import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.KernelTransitions
import dev.cline.termux.core.kernel.hub.HubCommands
import dev.cline.termux.core.kernel.hub.HubHealth
import dev.cline.termux.core.kernel.hub.HubVersion
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.customize.CustomizeJson
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.fakes.FakeHubTransport
import dev.cline.termux.fakes.FakeTermuxExecutor
import dev.cline.termux.fakes.connectedHub
import dev.cline.termux.fakes.ok
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsBuilderTest {
    private val hex64 = "ab".repeat(32)
    private val now = Instant.parse("2026-10-03T17:12:05Z").toEpochMilli()

    private val prober = object : HubProber {
        override suspend fun health(port: Int) = HubHealth(ok = true, coreVersion = "0.0.90", buildId = "source-0.0.90")
        override suspend fun version(port: Int) = HubVersion(coreVersion = "0.0.90", pid = 42)
    }

    private fun app() = DiagnosticsBuilder.AppFacts("io.github.ichouchiang.clinetermux", "0.1.0-pre.1", 1, 36, "16", "SM-S938B", "arm64-v8a")

    @Test
    fun `the report has the header, doctor, transitions, both log tails and stripped sessions, redacted`() = runTest {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope) { env ->
            when (env.command) {
                HubCommands.SESSION_GET -> env.ok(
                    CustomizeJson.parseToJsonElement(
                        """{"session":{"sessionId":"s1","status":"completed","cwd":"/data/data/com.termux/files/home/app",
                        "metadata":{"title":"Fix login","prompt":"secret prompt","systemPrompt":"sys","provider":"anthropic"}}}""",
                    ).jsonObject,
                )
                else -> null
            }
        }
        val termux = FakeTermuxExecutor().apply {
            on(ClineCommands.Labels.DOCTOR, TermuxResult.success("""{"ok":true,"authToken":"$hex64"}"""))
            on(ClineCommands.Labels.TAIL_LOG) { command ->
                if (command.args.last() == TermuxPaths.CLI_LOG) TermuxResult.success("partial line\nBearer abcdef\nfull line\n")
                else TermuxResult.failure(1, "tail: cannot open: No such file or directory")
            }
        }
        val builder = DiagnosticsBuilder(
            hub = hub,
            termux = termux,
            prober = prober,
            app = app(),
            kernelFacts = { DiagnosticsBuilder.KernelFacts("3.0.68-termux.2", "3.0.68", DiagnosticsBuilder.gateName(KernelGate.Ready)) },
            transitions = { listOf(KernelTransitions.Transition(100, "Unknown", "Ready")) },
            settings = { buildJsonObject { put("theme", "system") } },
            nowMs = { now },
            zone = { ZoneOffset.UTC },
        )
        val report = builder.build(listOf("s1", "s1", "../etc"))
        assertEquals("cline-diagnostics-0.1.0-pre.1-20261003-171205.txt", report.fileName)
        assertEquals(listOf("s1"), report.sessionIds)
        val text = report.text
        val order = listOf("\"generatedAt\"", "===== doctor =====", "===== kernel transitions =====", "===== tail of ~/.cline/data/logs/cline.log =====", "===== session s1 =====")
        val positions = order.map { text.indexOf(it) }
        assertTrue("sections in order: $positions", positions.all { it >= 0 } && positions == positions.sorted())
        assertFalse("a missing log skips its section", text.contains("hub-daemon.log ====="))
        assertTrue(text.contains("\"gate\": \"Ready\""))
        assertTrue(text.contains("\"coreVersion\": \"0.0.90\""))
        assertTrue(text.contains("+100 ms  Unknown -> Ready"))
        assertFalse(text.contains("secret prompt"))
        assertFalse(text.contains("\"sys\""))
        assertTrue(text.contains("Fix login"))
        assertFalse(text.contains(hex64))
        assertFalse(Regex("[0-9a-f]{64}|sk-|Bearer [^<]").containsMatchIn(text))
        assertTrue(text.contains("\"cwd\": \"~/app\""))
        // The tail was shorter than asked for: the whole file, nothing dropped.
        assertTrue(text.contains("partial line"))
    }

    @Test
    fun `session rows are labelled and sorted newest first, and a down hub is a failure`() = runTest {
        val transport = FakeHubTransport()
        val hub = connectedHub(transport, backgroundScope) { env ->
            if (env.command == HubCommands.SESSION_LIST) {
                assertEquals("true", env.payload!!["rootOnly"]!!.jsonPrimitive.content)
                env.ok(
                    CustomizeJson.parseToJsonElement(
                        """{"sessions":[{"sessionId":"old","updatedAt":1,"metadata":{"prompt":"  first task "},"workspaceRoot":"/w/app"},
                                        {"sessionId":"new","updatedAt":5,"metadata":{"title":"Named"}},{"sessionId":"bare","updatedAt":3}]}""",
                    ).jsonObject,
                )
            } else {
                null
            }
        }
        val builder = DiagnosticsBuilder(hub, FakeTermuxExecutor(), prober, app(), { DiagnosticsBuilder.KernelFacts(null, null, "Ready") }, { emptyList() }, { buildJsonObject { } })
        val rows = (builder.sessions() as Outcome.Ok).value
        assertEquals(listOf("new", "bare", "old"), rows.map { it.sessionId })
        assertEquals(listOf("Named", "bare", "first task"), rows.map { it.label })
        assertEquals("app", rows.last().workspaceName)

        hub.disconnect()
        assertEquals(Outcome.Failed(RepoFailure.HubDisconnected), builder.sessions())
    }

    @Test
    fun `write puts the file in the diagnostics folder and sweep removes day-old files`() = runTest {
        val dir = Files.createTempDirectory("diag").toFile()
        val builder = DiagnosticsBuilder(
            hub = connectedHub(FakeHubTransport(), backgroundScope),
            termux = FakeTermuxExecutor(),
            prober = prober,
            app = app(),
            kernelFacts = { DiagnosticsBuilder.KernelFacts(null, null, "Ready") },
            transitions = { emptyList() },
            settings = { buildJsonObject { } },
            nowMs = { now },
            io = UnconfinedTestDispatcher(testScheduler),
        )
        val file = builder.write(dir, DiagnosticsBuilder.Report("body", "x.txt", emptyList()))
        assertEquals("body", file.readText())
        val old = java.io.File(dir, "old.txt").apply { writeText("o"); setLastModified(now - DiagnosticsBuilder.SWEEP_AGE_MS - 1) }
        file.setLastModified(now)
        builder.sweep(dir)
        assertFalse(old.exists())
        assertTrue(file.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `gate names and prompt stripping`() {
        assertEquals("Down(Asleep)", DiagnosticsBuilder.gateName(KernelGate.Down(KernelGate.DownVariant.Asleep, null)))
        val stripped = DiagnosticsBuilder.stripPrompts(
            CustomizeJson.parseToJsonElement("""{"prompt":"p","metadata":{"prompt":"p","systemPrompt":"s","title":"t"}}""").jsonObject,
        )
        assertEquals(setOf("metadata"), stripped.keys)
        assertEquals(setOf("title"), stripped["metadata"]!!.jsonObject.keys)
    }
}
