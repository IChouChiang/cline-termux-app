package dev.cline.termux.data.chat

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import dev.cline.termux.data.session.InterruptionDetector
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.data.session.TurnOutcome
import dev.cline.termux.data.session.TurnRecord
import dev.cline.termux.data.session.TurnRecords
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProviderAuthResolver, SessionCreateBuilder/ForkTitle, ChatStatus, InterruptionDetector and TurnRecords. */
class SessionCoreHelpersTest {

    // ---- ProviderAuthResolver (HC §1.3.5) ----

    private class Catalog(
        val live: Map<String, CatalogAuthFacts> = emptyMap(),
        val cached: Map<String, CatalogAuthFacts> = emptyMap(),
        val fallback: Map<String, String> = emptyMap(),
        val configured: Set<String> = emptySet(),
    ) : ProviderAuthCatalog {
        override fun liveProvider(providerId: String) = live[providerId]
        override fun cachedProvider(providerId: String) = cached[providerId]
        override fun fallbackAuthKind(providerId: String) = fallback[providerId]
        override fun isConfigured(providerId: String) = providerId in configured
    }

    @Test
    fun `resolver walks cline, live, cache, fallback, configured, unknown`() {
        val resolver = ProviderAuthResolver(
            Catalog(
                live = mapOf("openai-codex" to CatalogAuthFacts(listOf("oauth", "local-auth"), "codex", "https://docs")),
                cached = mapOf("anthropic" to CatalogAuthFacts(listOf("api-key"))),
                fallback = mapOf("claude-code" to "local", "github-copilot" to "oauth", "groq" to "api-key"),
                configured = setOf("my-custom"),
            ),
        )
        assertEquals(ProviderAuthInfo("cline"), resolver.resolve("cline"))
        assertEquals(LocalCli("codex", "https://docs"), resolver.resolve("openai-codex")!!.localCli)
        assertEquals(listOf("api-key"), resolver.resolve("anthropic")!!.capabilities)
        assertNull(resolver.resolve("claude-code"))
        assertEquals(listOf("oauth"), resolver.resolve("github-copilot")!!.capabilities)
        assertEquals(listOf("api-key"), resolver.resolve("groq")!!.capabilities)
        assertEquals(ProviderAuthInfo("my-custom"), resolver.resolve("my-custom"))
        assertNull(resolver.resolve("nobody"))
        assertNull(resolver.resolve(" "))
        assertNull(ProviderAuthResolver(ProviderAuthCatalog.None).resolve("anthropic"))
    }

    // ---- SessionCreateBuilder (HC §1.3.6) ----

    private val config = SessionConfigInput(workspaceRoot = "/w", providerId = "anthropic", modelId = "m", reasoningEffort = "high")
    private val fork = ForkTitleStrings("Untitled", " (fork)")

    private fun JsonObject.hasAskQuestion(): Boolean =
        this["runtimeOptions"]?.jsonObject?.get("clientContributions")?.jsonArray
            ?.any { it.jsonObject["executor"]?.jsonPrimitive?.content == "askQuestion" } == true

    private fun messages(vararg pairs: Pair<String, String>) = buildJsonArray {
        pairs.forEach { (role, text) -> add(buildJsonObject { put("role", role); put("content", text) }) }
    }

    @Test
    fun `first send carries the askQuestion contribution, checkpoints and thinking`() {
        val payload = SessionCreateBuilder.firstSend(config).payload()
        assertTrue(payload.hasAskQuestion())
        val sessionConfig = payload["sessionConfig"]!!.jsonObject
        assertEquals("true", sessionConfig["checkpoint"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        assertFalse(sessionConfig.containsKey("apiKey"))
        assertEquals("android", payload["metadata"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        assertEquals(true, SessionCreateBuilder.run { config.toSpec() }.thinking)
    }

    @Test
    fun `resume keeps the persisted source and seeds the history`() {
        val history = messages("user" to "hi")
        val request = SessionCreateBuilder.resume(config, "s-1", history, buildJsonObject { put("source", "cli") })
        val payload = request.payload()
        assertTrue(payload.hasAskQuestion())
        assertEquals("s-1", request.spec.resumeSessionId)
        assertEquals("cli", request.spec.source)
        assertEquals(history, payload["initialMessages"])
        assertEquals("android", SessionCreateBuilder.resume(config, "s-1", history, null).spec.source)
    }

    @Test
    fun `fork strips non-inherited metadata, stamps fork and the title`() {
        val source = buildJsonObject {
            put("title", "Fix the build")
            put("pid", 42)
            put("totalCost", 1.5)
            put("custom", "kept")
        }
        val request = SessionCreateBuilder.fork(config, "s-1", source, messages("user" to "hi"), "2026-10-03T00:00:00Z", fork)
        val metadata = request.metadata
        assertTrue(request.payload().hasAskQuestion())
        assertEquals("Fix the build (fork)", metadata["title"]!!.jsonPrimitive.content)
        assertFalse(metadata.containsKey("pid"))
        assertFalse(metadata.containsKey("totalCost"))
        assertEquals("kept", metadata["custom"]!!.jsonPrimitive.content)
        assertEquals("s-1", metadata["fork"]!!.jsonObject["forkedFromSessionId"]!!.jsonPrimitive.content)
        assertFalse("the whole session, no cut (A16)", metadata["fork"]!!.jsonObject.containsKey("beforeRunCount"))
    }

    @Test(expected = EmptySessionForkException::class)
    fun `forking an empty session throws`() {
        SessionCreateBuilder.fork(config, "s-1", null, JsonArray(emptyList()), "2026-10-03T00:00:00Z", fork)
    }

    @Test
    fun `fork title falls back to prompt, first user text, assistant text, Untitled and truncates`() {
        assertEquals("Do it (fork)", ForkTitle.derive("  ", "Do   it", JsonArray(emptyList()), fork))
        assertEquals("first (fork)", ForkTitle.derive(null, null, messages("assistant" to "reply", "user" to "first"), fork))
        assertEquals("reply (fork)", ForkTitle.derive(null, null, messages("assistant" to "reply"), fork))
        assertEquals("Untitled (fork)", ForkTitle.derive(null, null, JsonArray(emptyList()), fork))
        val long = ForkTitle.derive("x".repeat(200), null, JsonArray(emptyList()), fork)
        assertEquals(ForkTitle.MAX_FORK_TITLE_LENGTH, long.length)
        assertTrue(long.endsWith(" (fork)"))
    }

    @Test
    fun `fork title never cuts inside a surrogate pair before the suffix`() {
        val grin = "😀"
        val maxBase = ForkTitle.MAX_FORK_TITLE_LENGTH - " (fork)".length // 113
        // The emoji occupies UTF-16 indices 112-113, so a plain substring(0, 113) would end on its high surrogate.
        val straddling = ForkTitle.derive("x".repeat(maxBase - 1) + grin + "tail", null, JsonArray(emptyList()), fork)
        assertEquals("x".repeat(maxBase - 1) + " (fork)", straddling)
        // An emoji that ends exactly at the limit is kept whole.
        val fitting = ForkTitle.derive("x".repeat(maxBase - 2) + grin + "tail", null, JsonArray(emptyList()), fork)
        assertEquals("x".repeat(maxBase - 2) + grin + " (fork)", fitting)
        assertEquals(ForkTitle.MAX_FORK_TITLE_LENGTH, fitting.length)
        // An all-emoji title stays well formed at every length.
        for (count in 50..70) {
            val title = ForkTitle.derive(grin.repeat(count), null, JsonArray(emptyList()), fork)
            val base = title.removeSuffix(" (fork)")
            assertTrue(title.length <= ForkTitle.MAX_FORK_TITLE_LENGTH)
            assertTrue(base.isEmpty() || !Character.isHighSurrogate(base.last()))
            assertEquals(0, base.length % 2)
        }
    }

    // ---- ChatStatus / RunPhase ----

    @Test
    fun `chat status follows run phase first, then the session status`() {
        val facts = SessionFacts("s")
        assertEquals(ChatStatus.Starting, facts.chatStatus(creating = true))
        assertEquals(ChatStatus.Running, facts.copy(runPhase = RunPhase.AwaitingUser).chatStatus())
        assertEquals(ChatStatus.Stopping, facts.copy(runPhase = RunPhase.Stopping).chatStatus())
        assertEquals(ChatStatus.Cancelled, facts.copy(status = SessionStatus.Cancelled).chatStatus())
        assertEquals(ChatStatus.Failed, facts.copy(status = SessionStatus.Error).chatStatus())
        assertEquals(ChatStatus.Idle, ChatStatusDerivation.of(null))
        assertEquals(SessionStatus.Cancelled, SessionStatus.parse("aborted"))
        assertEquals(SessionStatus.Starting, SessionStatus.parse("provisioning"))
        assertTrue(RunPhase.AwaitingUser.isBusy)
        assertFalse(RunPhase.Idle.isBusy)
    }

    // ---- InterruptionDetector ----

    private fun turn(id: String, key: String?, started: Boolean = true, open: Boolean = true, ours: Boolean = true) =
        TurnRecord(requestId = id, sessionId = "s", hubLifetimeKey = key, started = started, open = open, ours = ours, sentAt = 0)

    @Test
    fun `restart and not-delivered verdicts`() {
        val turns = listOf(turn("a", "1:100"), turn("b", "2:200", started = false), turn("c", null), turn("d", "1:100", open = false))
        assertEquals(listOf("a"), InterruptionDetector.interruptedByRestart(turns, "2:200").map { it.requestId })
        assertEquals(emptyList<TurnRecord>(), InterruptionDetector.interruptedByRestart(turns, null))
        assertEquals(listOf("b"), InterruptionDetector.notDelivered(turns, "2:200").map { it.requestId })
    }

    @Test
    fun `shutdown projections`() {
        fun event(name: String, payload: JsonObject) = HubEventEnvelope(event = name, sessionId = "s", payload = payload)
        assertTrue(InterruptionDetector.isShutdownProjection(event(HubEvents.RUN_COMPLETED, buildJsonObject { put("reason", "disposed") })))
        assertFalse(InterruptionDetector.isShutdownProjection(event(HubEvents.RUN_COMPLETED, buildJsonObject { put("reason", "completed") })))
        val ended = buildJsonObject {
            put("snapshot", buildJsonObject { put("status", "cancelled"); put("endedAt", 1_700_000_000_000L) })
        }
        assertTrue(InterruptionDetector.isShutdownProjection(event(HubEvents.SESSION_UPDATED, ended)))
        val running = buildJsonObject { put("snapshot", buildJsonObject { put("status", "running") }) }
        assertFalse(InterruptionDetector.isShutdownProjection(event(HubEvents.SESSION_UPDATED, running)))
        // A daemon disposal of an interactive session is a shutdown projection...
        val disposedInteractive = buildJsonObject {
            put("snapshot", buildJsonObject { put("status", "completed"); put("interactive", true); put("endedAt", "2026-10-06T14:41:58.079Z") })
        }
        assertTrue(InterruptionDetector.isShutdownProjection(event(HubEvents.SESSION_UPDATED, disposedInteractive)))
        // ...but a one-shot session's own end (published before its run.completed) is not.
        val oneShotEnded = buildJsonObject {
            put("snapshot", buildJsonObject { put("status", "completed"); put("interactive", false); put("endedAt", "2026-10-06T14:41:58.079Z") })
        }
        assertFalse(InterruptionDetector.isShutdownProjection(event(HubEvents.SESSION_UPDATED, oneShotEnded)))
    }

    // ---- TurnRecords ----

    @Test
    fun `turn records round trip, prune and tolerate garbage`() {
        val day = TurnRecords.CLOSED_RETENTION_MS
        val now = 10 * day
        val records = listOf(
            TurnRecord("open-old", "s", sentAt = 0, providerAuth = ProviderAuthInfo("openai-codex", localCli = LocalCli("codex"))),
            TurnRecord("closed-old", "s", sentAt = 1, open = false, closedAt = now - day - 1, outcome = TurnOutcome.Completed),
            TurnRecord("closed-new", "s", sentAt = 2, open = false, closedAt = now - 1, outcome = TurnOutcome.Interrupted),
            TurnRecord("open-new", "s", sentAt = now - TurnRecords.OPEN_RETENTION_MS),
        )
        assertEquals(records, TurnRecords.decode(TurnRecords.encode(records)))
        // An open record past 24 h never saw its terminal event: it expires instead of pinning the session.
        assertEquals(listOf("closed-new", "open-new"), TurnRecords.prune(records, now).map { it.requestId })
        assertTrue(TurnRecords.isExpired(records[0], now))
        assertFalse(TurnRecords.isExpired(records[3], now))
        assertFalse(TurnRecords.isExpired(records[1], now))
        val many = (1..150).map { TurnRecord("r$it", "s", sentAt = it.toLong()) }
        assertEquals("r51", TurnRecords.prune(many, 0).first().requestId)
        assertEquals(emptyList<TurnRecord>(), TurnRecords.decode("{not json"))
        assertEquals(emptyList<TurnRecord>(), TurnRecords.decode(null))
        assertEquals(JsonPrimitive("x"), JsonPrimitive("x"))
    }
}
