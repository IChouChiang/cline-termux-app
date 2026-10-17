package dev.cline.termux.ui.chat

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.core.kernel.termux.GitBranchProbe
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.checkpoint.RestoreKind
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.ui.checkpoint.CheckpointUiState
import dev.cline.termux.ui.checkpoint.RowError
import dev.cline.termux.ui.checkpoint.RowPending
import dev.cline.termux.ui.chat.rows.ActionError
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of the chat screen: chips, branch sheet, header width and title, checkpoint rows. */
class ChatUiStateTest {
    private fun result(stdout: String = "", stderr: String = "", exit: Int = 0) =
        TermuxResult(stdout = stdout, stderr = stderr, exitCode = exit, err = -1, errmsg = null)

    @Test
    fun `workspace chip labels no folder, the Termux home and folder names`() {
        assertEquals(WorkspaceChipState.Default, WorkspaceChipState.of(WorkspaceChoice.Chat))
        assertEquals(WorkspaceChipState.Default, WorkspaceChipState.of(WorkspaceChoice.Folder("  ")))
        assertTrue(WorkspaceChipState.Default.isDefault)
        assertEquals(WorkspaceLabel.Home, WorkspaceChipState.of(WorkspaceChoice.Folder("/data/data/com.termux/files/home")).label)
        val folder = WorkspaceChipState.of(WorkspaceChoice.Folder("/data/data/com.termux/files/home/src/app/"))
        assertEquals(WorkspaceLabel.Name("app"), folder.label)
        assertFalse(folder.isDefault)
        // A22: "Chat" is no longer the no-folder state; the kernel's chat workspace chosen as a folder is that folder.
        val chat = WorkspaceChipState.of(WorkspaceChoice.Folder("/data/data/com.termux/files/home/.cline/data/workspaces/chat"))
        assertEquals(WorkspaceLabel.Name("chat"), chat.label)
        assertFalse(chat.isDefault)
    }

    @Test
    fun `branch rules parse, filter, probe and report the first stderr line`() {
        assertEquals(listOf("main", "dev"), BranchRules.parse(result("main\n\n dev \n")))
        assertEquals(listOf("feature/Chat"), BranchRules.filter(listOf("main", "feature/Chat"), " chat"))
        assertEquals(listOf("a", "b"), BranchRules.filter(listOf("a", "b"), ""))
        assertEquals(BranchRules.Probe.Named("main"), BranchRules.probe(GitBranchProbe.Branch("main")))
        assertEquals(BranchRules.Probe.Detached, BranchRules.probe(GitBranchProbe.Detached))
        assertEquals(BranchRules.Probe.Hidden, BranchRules.probe(GitBranchProbe.NotARepository))
        assertEquals("error: pathspec 'x' did not match", BranchRules.failureLine(result(stderr = "\nerror: pathspec 'x' did not match\nmore", exit = 1)))

        val sheet = BranchSheetState(branches = listOf("main", "dev"), current = "main", busy = true)
        assertFalse(sheet.rowsEnabled)
        assertTrue(BranchSheetState(loading = false, branches = listOf("main"), query = "zzz").empty)
    }

    @Test
    fun `width rule moves the agents chip first, then the diff chip`() {
        assertEquals(HeaderWidthRule.Placement.All, HeaderWidthRule.place(400, 100, 100, 80, 8))
        assertEquals(HeaderWidthRule.Placement(agentsInBar = false, diffInBar = true), HeaderWidthRule.place(250, 100, 100, 80, 8))
        assertEquals(HeaderWidthRule.Placement(agentsInBar = false, diffInBar = false), HeaderWidthRule.place(150, 100, 100, 80, 8))
        assertEquals(HeaderWidthRule.Placement.All, HeaderWidthRule.place(120, 100, null, null, 8))
    }

    @Test
    fun `height-compact dock drops the queue strip for the IME or a docked banner and is bounded unless typing without a banner`() {
        assertTrue(DockRules.queueStripShown(stripVisible = true, heightCompact = false, imeOpen = true, bannerVisible = true))
        assertFalse(DockRules.queueStripShown(stripVisible = true, heightCompact = true, imeOpen = true, bannerVisible = false))
        assertFalse(DockRules.queueStripShown(stripVisible = true, heightCompact = true, imeOpen = false, bannerVisible = true))
        assertTrue(DockRules.queueStripShown(stripVisible = true, heightCompact = true, imeOpen = false, bannerVisible = false))
        assertFalse(DockRules.queueStripShown(stripVisible = false, heightCompact = false, imeOpen = false, bannerVisible = false))
        assertTrue(DockRules.bounded(heightCompact = true, imeOpen = false, bannerVisible = false))
        assertTrue(DockRules.bounded(heightCompact = true, imeOpen = false, bannerVisible = true))
        assertTrue(DockRules.bounded(heightCompact = true, imeOpen = true, bannerVisible = true))
        assertFalse(DockRules.bounded(heightCompact = true, imeOpen = true, bannerVisible = false))
        assertFalse(DockRules.bounded(heightCompact = false, imeOpen = false, bannerVisible = true))
        assertFalse(DockRules.bounded(heightCompact = false, imeOpen = true, bannerVisible = true))
    }

    @Test
    fun `height-compact dock with the IME closed leaves room for a docked banner`() {
        // S25U landscape: the chat pane under the app bar is ≈ 289dp.
        val pane = 289.dp
        assertEquals(Dp.Unspecified, DockRules.maxHeight(pane, heightCompact = false, imeOpen = false, bannerVisible = true))
        assertEquals(pane * DockRules.MAX_PANE_FRACTION, DockRules.maxHeight(pane, heightCompact = true, imeOpen = false, bannerVisible = false))
        // The banner outranks transcript rows (D18): the stack may take the whole pane, so it
        // still never draws under the app bar.
        val withBanner = DockRules.maxHeight(pane, heightCompact = true, imeOpen = false, bannerVisible = true)
        assertEquals(pane, withBanner)
        // Measured on the device: banner + composer ≈ 197dp, ≈ 221dp once the field has focus
        // (focused minLines = 2), ≈ 256dp with a 3-line draft. The 60 % bound (≈ 173dp) clipped
        // the banner in each of these, and fully hid it once the field had focus.
        assertTrue(pane * DockRules.MAX_PANE_FRACTION < 197.dp)
        assertTrue(withBanner >= 256.dp)
    }

    @Test
    fun `while typing in a height-compact window a docked banner keeps the bound and nothing else does`() {
        // Trial row 13: unbounded, the stack that outgrew the room above the keyboard drew the stall
        // notice under the status bar (the app bar steps aside while typing). With a banner the
        // stack keeps the whole pane as its bound; without one the C1.4 step 6 overflow is unchanged.
        val pane = 383.dp
        assertEquals(pane, DockRules.maxHeight(pane, heightCompact = true, imeOpen = true, bannerVisible = true))
        assertEquals(Dp.Unspecified, DockRules.maxHeight(pane, heightCompact = true, imeOpen = true, bannerVisible = false))
        assertEquals(Dp.Unspecified, DockRules.maxHeight(pane, heightCompact = false, imeOpen = true, bannerVisible = true))
    }

    @Test
    fun `width rule drops the back chip's label before moving any chip`() {
        // The labelled back chip (≈ 230dp) and its icon-only form (48dp), no other chips.
        assertEquals(HeaderWidthRule.Placement.All, HeaderWidthRule.place(400, 96, null, null, 8, parentPx = 230, parentCompactPx = 48))
        assertEquals(
            HeaderWidthRule.Placement(agentsInBar = true, diffInBar = true, parentLabel = false),
            HeaderWidthRule.place(292, 96, null, null, 8, parentPx = 230, parentCompactPx = 48),
        )
        // Then the agents chip moves, then the diff chip, and the back chip stays icon-only.
        assertEquals(
            HeaderWidthRule.Placement(agentsInBar = false, diffInBar = true, parentLabel = false),
            HeaderWidthRule.place(292, 96, 90, 80, 8, parentPx = 230, parentCompactPx = 48),
        )
        assertEquals(
            HeaderWidthRule.Placement(agentsInBar = false, diffInBar = false, parentLabel = false),
            HeaderWidthRule.place(200, 96, 90, 80, 8, parentPx = 230, parentCompactPx = 48),
        )
    }

    private fun summary(id: String, title: String, custom: Boolean) = dev.cline.termux.data.sessions.SessionSummary(
        id = id,
        title = title,
        hasCustomTitle = custom,
        workspaceRoot = "/w",
        cwd = "/w",
        kernelStatus = dev.cline.termux.data.sessions.KernelSessionStatus.Idle,
        provider = "p",
        model = "m",
        source = "android",
        pinned = false,
        createdAt = 0L,
        activityAt = 0L,
    )

    @Test
    fun `parent link never carries the data layer's English id fallback`() {
        val id = "1791064689782_kck3p"
        assertEquals(ParentLink(id, null, untitled = true), ParentLink.of(id, summary(id, "Session " + id.takeLast(6), custom = false)))
        // A user who named the session "Session kck3p" keeps that title.
        assertEquals(ParentLink(id, "Session " + id.takeLast(6)), ParentLink.of(id, summary(id, "Session " + id.takeLast(6), custom = true)))
        assertEquals(ParentLink(id, "Refactor the hub"), ParentLink.of(id, summary(id, "Refactor the hub\nsecond line", custom = false)))
        assertEquals(ParentLink(id, null), ParentLink.of(id, null))
    }

    @Test
    fun `a child session's title keeps at least 96dp in a 412dp window`() {
        // S25U portrait at 1dp = 1px: 412 minus the nav icon slot (52), the title inset (16)
        // and the overflow slot (52) is the title slot; chip widths as measured on the device.
        val slot = 412 - 52 - 16 - 52
        val titleMin = 96
        val agents = 92
        val diff = 84
        val parentFull = 230
        val parentCompact = 48
        val gap = 8
        val placement = HeaderWidthRule.place(slot, titleMin, agents, diff, gap, parentFull, parentCompact)
        val title = slot - HeaderWidthRule.reservedPx(placement, agents, diff, gap, parentFull, parentCompact)
        assertTrue("title got $title", title >= titleMin)
        assertFalse(placement.parentLabel)
        // Without the other chips the back chip still loses its label (230dp would leave 46dp).
        val alone = HeaderWidthRule.place(slot, titleMin, null, null, gap, parentFull, parentCompact)
        assertTrue(slot - HeaderWidthRule.reservedPx(alone, null, null, gap, parentFull, parentCompact) >= titleMin)
    }

    @Test
    fun `title prefers the custom title, then the record title, then the first user line`() {
        val user = ChatMessage(id = "u1", sessionId = "s", role = Role.User, content = "Fix the build\nplease", createdAt = 0L)
        val bare = SessionState("s").copy(canonical = listOf(user))
        assertEquals("Fix the build", HeaderState.titleOf(bare))
        val titled = bare.copy(facts = SessionFacts("s", title = "Record title"))
        assertEquals("Record title", HeaderState.titleOf(titled))
        val custom = titled.copy(facts = titled.facts.copy(metadata = buildJsonObject { put("title", JsonPrimitive("Mine")) }))
        assertEquals("Mine", HeaderState.titleOf(custom))
        assertNull(HeaderState.titleOfPrompt("   "))
    }

    @Test
    fun `checkpoint rows map pending and errors per kind, picker mode stays out`() {
        val state = CheckpointUiState(
            pending = RowPending("m1", RestoreKind.Edit, pickerMode = false),
            errors = mapOf(
                "m2" to RowError(RestoreKind.Revert, "disk full"),
                "m3" to RowError(RestoreKind.Edit, null, pickerMode = true),
            ),
            versioningPending = true,
        )
        val actions = CheckpointRowActions.of(state)
        assertEquals("m1", actions.editPendingId)
        assertNull(actions.restorePendingId)
        assertEquals(mapOf("m2" to ActionError("disk full")), actions.restoreErrors)
        assertTrue(actions.editErrors.isEmpty())
        assertTrue(actions.versioningPending)
        // The chat resolves each row error to its C.7 sentence.
        val resolved = CheckpointRowActions.of(state) { "sentence for ${it.message}" }
        assertEquals(mapOf("m2" to ActionError("sentence for disk full")), resolved.restoreErrors)
    }

    @Test
    fun `a draining restore keeps its pending icon and marks the row for the wait line`() {
        val draining = CheckpointUiState(pending = RowPending("m1", RestoreKind.Revert, pickerMode = false, draining = true))
        val actions = CheckpointRowActions.of(draining)
        assertEquals("m1", actions.restorePendingId)
        assertEquals("m1", actions.drainingMessageId)
        assertNull(CheckpointRowActions.of(draining.copy(pending = draining.pending!!.copy(draining = false))).drainingMessageId)
        // Picker-mode restores report through the picker, not a row: no line under any message.
        assertNull(CheckpointRowActions.of(draining.copy(pending = draining.pending!!.copy(pickerMode = true))).drainingMessageId)
        assertNull(CheckpointRowActions.of(CheckpointUiState()).drainingMessageId)
    }
}
