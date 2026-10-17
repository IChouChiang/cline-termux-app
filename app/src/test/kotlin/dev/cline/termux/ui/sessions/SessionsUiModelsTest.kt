package dev.cline.termux.ui.sessions

import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.data.sessions.ActionFailure
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.RowUsage
import dev.cline.termux.data.sessions.ScheduleInfo
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of S2 (§3.4, sessions-view.tsx), S3 (§4), F5 and F6. */
class SessionsUiModelsTest {
    // ---- S2 facets, search, sort -----------------------------------------------------------------

    @Test fun `facet status uses the desktop vocabulary`() {
        val expected = mapOf(
            KernelSessionStatus.Aborted to "cancelled",
            KernelSessionStatus.Cancelled to "cancelled",
            KernelSessionStatus.Pending to "idle",
            KernelSessionStatus.Unknown to "idle",
            KernelSessionStatus.Running to "running",
            KernelSessionStatus.Completed to "completed",
            KernelSessionStatus.Failed to "failed",
            KernelSessionStatus.Idle to "idle",
        )
        expected.forEach { (status, word) -> assertEquals(status.name, word, SessionsScreenModel.facetStatus(status)) }
    }

    @Test fun `facets follow sessionFilterDetails`() {
        val s = Rows.summary("a", pinned = true, status = KernelSessionStatus.Aborted, workspace = "/w/app", provider = "openai", model = "gpt-5")
        assertEquals(listOf("pinned:yes", "workspace:app", "status:cancelled", "provider:openai", "model:gpt-5"), SessionsScreenModel.facets(s))
        val bare = Rows.summary("b", workspace = "", provider = "", model = "")
        assertEquals(listOf("status:completed"), SessionsScreenModel.facets(bare))
    }

    @Test fun `facet options are distinct and sorted ignoring case`() {
        val rows = listOf(Rows.row("a") { it.copy(model = "Zeta") }, Rows.row("b") { it.copy(model = "alpha", pinned = true) })
        assertEquals(
            listOf("model:alpha", "model:Zeta", "pinned:yes", "provider:anthropic", "status:completed", "workspace:app"),
            SessionsScreenModel.facetOptions(rows),
        )
    }

    @Test fun `query matches the searchable join, trimmed and lowercased`() {
        val s = Rows.summary("a", title = "Fix Login", workspace = "/data/proj/client-app", provider = "anthropic", model = "claude-sonnet")
        assertTrue(SessionsScreenModel.matchesQuery(s, "  login "))
        assertTrue(SessionsScreenModel.matchesQuery(s, "CLIENT-APP"))
        assertTrue(SessionsScreenModel.matchesQuery(s, "sonnet"))
        assertTrue(SessionsScreenModel.matchesQuery(s, "/data/proj"))
        assertTrue(SessionsScreenModel.matchesQuery(s, ""))
        assertFalse(SessionsScreenModel.matchesQuery(s, "openai"))
    }

    @Test fun `facets are ORed and sort reverses by activity`() {
        val rows = listOf(
            Rows.row("a", 3) { it.copy(kernelStatus = KernelSessionStatus.Running) },
            Rows.row("b", 2) { it.copy(workspaceRoot = "/w/other") },
            Rows.row("c", 1) { it.copy(workspaceRoot = "/w/third") },
        )
        val selected = setOf("status:running", "workspace:other")
        assertEquals(listOf("a", "b"), SessionsScreenModel.apply(rows, "", selected, SortDirection.Newest).map { it.id })
        assertEquals(listOf("b", "a"), SessionsScreenModel.apply(rows, "", selected, SortDirection.Oldest).map { it.id })
        assertEquals(listOf("c", "b", "a"), SessionsScreenModel.apply(rows, "", emptySet(), SortDirection.Oldest).map { it.id })
    }

    @Test fun `complete history and paging triggers`() {
        assertFalse(SessionsScreenModel.needsCompleteHistory("", emptySet(), SortDirection.Newest, filterSheetOpen = false))
        assertTrue(SessionsScreenModel.needsCompleteHistory(" x", emptySet(), SortDirection.Newest, false))
        assertTrue(SessionsScreenModel.needsCompleteHistory("", setOf("pinned:yes"), SortDirection.Newest, false))
        assertTrue(SessionsScreenModel.needsCompleteHistory("", emptySet(), SortDirection.Oldest, false))
        assertTrue(SessionsScreenModel.needsCompleteHistory("", emptySet(), SortDirection.Newest, true))

        assertTrue(SessionsScreenModel.shouldLoadOlder(44, 50, mayHaveMore = true, needsComplete = false, loading = false, halted = false))
        assertFalse(SessionsScreenModel.shouldLoadOlder(43, 50, true, false, false, false))
        assertFalse(SessionsScreenModel.shouldLoadOlder(49, 50, false, false, false, false))
        assertFalse(SessionsScreenModel.shouldLoadOlder(49, 50, true, true, false, false))
        assertFalse(SessionsScreenModel.shouldLoadOlder(49, 50, true, false, true, false))
        assertFalse(SessionsScreenModel.shouldLoadOlder(49, 50, true, false, false, true))
    }

    // ---- S3 search ----------------------------------------------------------------------------

    @Test fun `snippet brackets become bold ranges`() {
        val spans = SessionSearchModel.snippetSpans("…the [redirect] loop [x] and [unclosed")
        assertEquals("…the redirect loop x and [unclosed", spans.text)
        assertEquals(listOf(5..12, 19..19), spans.bold)
        assertEquals("redirect", spans.text.substring(5, 13))
        assertEquals(SnippetSpans("[]", emptyList()), SessionSearchModel.snippetSpans("[]"))
    }

    @Test fun `hits parse in the Hub's order with formatted title and preview`() {
        val reply = HubJson.parseToJsonElement(
            """{"hits":[
              {"sessionId":"s2","documentId":"d2","ordinal":0,"role":"assistant","title":"<user_input mode=\"act\">Port   OpenTUI</user_input>","snippet":"rebuilt the [redirect]\n handler","workspaceRoot":"/w","score":2},
              {"sessionId":"s1","documentId":"d1","ordinal":3,"role":"user","title":"Fix","snippet":"x","workspaceRoot":"/a","score":1},
              {"documentId":"bad"}
            ]}""",
        ).jsonObject
        val hits = SessionSearchModel.parseHits(reply)
        assertEquals(listOf("s2", "s1"), hits.map { it.sessionId })
        assertEquals("Assistant", hits[0].role)
        assertEquals("Port OpenTUI", hits[0].title)
        assertEquals("rebuilt the [redirect] handler", hits[0].snippet)
        assertEquals("User", hits[1].role)
    }

    @Test fun `reveal adds fifteen up to the total`() {
        assertEquals(30, SessionSearchModel.reveal(15, 50))
        assertEquals(20, SessionSearchModel.reveal(15, 20))
    }

    // ---- labels, F5, F6, footer -------------------------------------------------------------------

    @Test fun `workspace kinds and model text`() {
        assertEquals(WorkspaceKind.None, SessionsLabels.workspaceKind(" "))
        assertEquals(WorkspaceKind.Home, SessionsLabels.workspaceKind("/data/data/com.termux/files/home"))
        assertEquals(WorkspaceKind.Chat, SessionsLabels.workspaceKind("/data/data/com.termux/files/home/.cline/data/workspaces/chat"))
        assertEquals(WorkspaceKind.Name("app"), SessionsLabels.workspaceKind("/w/app/"))
        assertEquals("anthropic:claude", SessionsLabels.modelText("anthropic", "claude"))
        assertEquals("claude", SessionsLabels.modelText("", "claude"))
        assertEquals("anthropic", SessionsLabels.modelText("anthropic", ""))
        assertEquals(null, SessionsLabels.modelText("", ""))
    }

    @Test fun `id fallback title is recognised`() {
        val s = Rows.summary("abc123456789", title = "Session 456789").copy(hasCustomTitle = false)
        assertTrue(SessionsLabels.isIdFallback(s))
        assertFalse(SessionsLabels.isIdFallback(s.copy(title = "Session notes")))
    }

    @Test fun `delete dialog quotes the first line, capped at 120 graphemes`() {
        assertEquals("Fix login", DeleteDialogText.quotedTitle("Fix login\nmore"))
        val long = "a".repeat(130)
        assertEquals("a".repeat(120) + "…", DeleteDialogText.quotedTitle(long))
        assertEquals("a".repeat(120), DeleteDialogText.quotedTitle("a".repeat(120)))
        val flags = "🇯🇵".repeat(121)
        assertEquals("🇯🇵".repeat(120) + "…", DeleteDialogText.quotedTitle(flags))
        assertTrue(DeleteDialogText.stopsRunningTask(KernelSessionStatus.Running))
        assertTrue(DeleteDialogText.stopsRunningTask(KernelSessionStatus.Pending))
        assertFalse(DeleteDialogText.stopsRunningTask(KernelSessionStatus.Idle))
    }

    @Test fun `details rows follow getSessionOverviewItems and omit empty values`() {
        val s = Rows.summary("a", workspace = "/w/app", provider = "anthropic", model = "claude", usage = RowUsage(2_000, 600, 0.0042), source = "hub-schedule")
            .copy(schedule = ScheduleInfo("sch", "Daily standup", 12), gitBranch = "main")
        val items = SessionDetails.items(s, workspace = "app")
        assertEquals(
            listOf(
                DetailField.Schedule, DetailField.Run, DetailField.Workspace, DetailField.Branch, DetailField.Provider,
                DetailField.Model, DetailField.Tokens, DetailField.Cost, DetailField.Source, DetailField.SessionId,
            ),
            items.map { it.field },
        )
        assertEquals("a", items.last().value)
        assertEquals("/w/app", items.first { it.field == DetailField.Workspace }.copyValue)
        assertEquals("2.6k", items.first { it.field == DetailField.Tokens }.value)
        assertEquals("$0.0042", items.first { it.field == DetailField.Cost }.value)
        assertEquals("Hub Schedule", items.first { it.field == DetailField.Source }.value)
        val bare = Rows.summary("b", workspace = "", provider = "", model = "", source = "")
        assertEquals(listOf(DetailField.SessionId), SessionDetails.items(bare, "").map { it.field })
        assertEquals("12", items.first { it.field == DetailField.Run }.value)
        val runZero = SessionDetails.items(s.copy(schedule = ScheduleInfo("sch", "Daily standup", 0)), workspace = "app")
        assertFalse(runZero.any { it.field == DetailField.Run })
        assertTrue(runZero.any { it.field == DetailField.Schedule })
        assertTrue(SessionDetails.needsLiveUsage(Rows.summary("c", status = KernelSessionStatus.Running, participants = 1)))
        assertFalse(SessionDetails.needsLiveUsage(Rows.summary("d", status = KernelSessionStatus.Completed, participants = 0)))
    }

    @Test fun `account footer name - first word, or the email's local part`() {
        assertEquals("Ada", AccountFooter("Ada Lovelace", null).name)
        assertEquals("ada", AccountFooter("ada@example.com", "Org").name)
        assertEquals('A', AccountFooter("ada", null).initial)
    }

    @Test fun `failures keep their kind for the snackbar`() {
        val m = SessionsMessage.ActionFailed(FailedAction.Fork, ActionFailure.EmptySession)
        assertEquals(FailedAction.Fork, m.action)
    }
}
