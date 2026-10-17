package dev.cline.termux.data.sessions

import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SessionVisual
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SS §1.3 mapping rules, replayed against the `session.list` records of fixture 05. */
class SessionSummaryTest {
    private fun j(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun findSessions(element: JsonElement?): JsonArray? = when (element) {
        is JsonObject -> (element["sessions"] as? JsonArray) ?: element.values.firstNotNullOfOrNull(::findSessions)
        is JsonArray -> element.firstNotNullOfOrNull(::findSessions)
        else -> null
    }

    private val fixtureRecords: List<JsonObject> by lazy {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("hub-fixtures/05-resume-after-restart.jsonl"))
        stream.bufferedReader().readLines().filter { it.isNotBlank() }
            .firstNotNullOf { findSessions(Json.parseToJsonElement(it)) }
            .map { it.jsonObject }
    }

    @Test fun `maps the fixture's session list records`() {
        val summaries = fixtureRecords.mapNotNull { SessionSummary.fromRecord(it) }
        val pinned = summaries.first { it.id == "1791064689782_kck3p" }
        assertEquals("Fixture: hello-from-mock", pinned.title)
        assertTrue(pinned.hasCustomTitle)
        assertTrue(pinned.pinned)
        assertEquals("openai-compatible", pinned.provider)
        assertEquals("mock-1", pinned.model)
        assertEquals("android", pinned.source)
        assertEquals(KernelSessionStatus.Completed, pinned.kernelStatus)
        assertEquals(1791064704674L, pinned.activityAt)
        assertFalse(pinned.isLive)
        val aborted = summaries.first { it.id == "1791064691937_rkppp" }
        assertEquals(KernelSessionStatus.Aborted, aborted.kernelStatus)
        assertEquals(RowUsage(inputTokens = 2450, outputTokens = 38, totalCostUsd = 0.0), aborted.usage)
        assertEquals(SessionVisual.Cancelled, aborted.visual())
        assertTrue(fixtureRecords.all { isListableRecord(it) })
    }

    @Test fun `drops subagents and unknown models except kept ids`() {
        val sub = j("""{"sessionId":"s1","metadata":{"provider":"cline","model":"m","isSubagent":true}}""")
        val child = j("""{"sessionId":"s2","metadata":{"provider":"cline","model":"m","parentSessionId":"root"}}""")
        val unknown = j("""{"sessionId":"s3","metadata":{"provider":"unknown","model":"m"}}""")
        assertFalse(isListableRecord(sub))
        assertFalse(isListableRecord(child))
        assertFalse(isListableRecord(unknown))
        assertTrue(isListableRecord(unknown, alwaysKeep = setOf("s3")))
        assertFalse(isListableRecord(sub, alwaysKeep = setOf("s1")))
    }

    @Test fun `schedule info comes from the origin trigger, runner stamp or link`() {
        val origin = SessionSummary.fromRecord(j("""{"sessionId":"a","metadata":{"sessionHistoryOrigin":{"trigger":"hub-schedule"},"scheduleRunNumber":3}}"""))!!
        assertEquals(ScheduleInfo(null, null, 3), origin.schedule)
        val stamped = SessionSummary.fromRecord(j("""{"sessionId":"b","metadata":{"scheduleId":" sched_1 ","scheduleName":"Daily"}}"""))!!
        assertEquals(ScheduleInfo("sched_1", "Daily", null), stamped.schedule)
        val linked = SessionSummary.fromRecord(j("""{"sessionId":"c","metadata":{}}"""), ScheduleLink("sched_2", "Weekly"))!!
        assertEquals(ScheduleInfo("sched_2", "Weekly", null), linked.schedule)
        // Metadata stamped by the runner wins over the link.
        val both = SessionSummary.fromRecord(j("""{"sessionId":"d","metadata":{"scheduleId":"sched_meta"}}"""), ScheduleLink("sched_link", "Name"))!!
        assertEquals(ScheduleInfo("sched_meta", "Name", null), both.schedule)
        assertNull(SessionSummary.fromRecord(j("""{"sessionId":"e","metadata":{}}"""))!!.schedule)
    }

    @Test fun `title, workspace and git branch fallbacks`() {
        val summary = SessionSummary.fromRecord(
            j("""{"sessionId":"1791064689782_abcdef","cwd":"/w","workspaceRoot":"","createdAt":5,"updatedAt":3,"status":"running","participants":[{"clientId":"x"}],"metadata":{"git":{"branch":" main "}}}"""),
        )!!
        assertEquals("Session abcdef", summary.title)
        assertFalse(summary.hasCustomTitle)
        assertEquals("/w", summary.workspaceRoot)
        assertEquals("main", summary.gitBranch)
        assertEquals(5L, summary.activityAt)
        assertTrue(summary.isLive)
        assertNull(summary.usage)
        assertNull(SessionSummary.fromRecord(j("""{"metadata":{}}""")))
    }

    @Test fun `orders by activity then id, both descending`() {
        fun row(id: String, at: Long) = SessionSummary.fromRecord(j("""{"sessionId":"$id","createdAt":$at,"updatedAt":$at}"""))!!
        val sorted = listOf(row("a", 1), row("c", 5), row("b", 5)).sortedWith(SessionActivityDescending)
        assertEquals(listOf("c", "b", "a"), sorted.map { it.id })
    }

    @Test fun `pending actions win in the visual, the cache drops metadata`() {
        val summary = SessionSummary.fromRecord(j("""{"sessionId":"a","status":"failed","metadata":{"title":"x"}}"""))!!
        assertEquals(SessionVisual.Failed, summary.visual())
        assertEquals(SessionVisual.ActionPending(PendingKind.Rename), summary.visual(SessionFlags(pendingAction = PendingKind.Rename)))
        assertTrue(summary.forCache().metadata.isEmpty())
        assertNotNull(summary.metadata["title"])
    }
}
