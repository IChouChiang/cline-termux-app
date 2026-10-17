package dev.cline.termux.ui.checkpoint

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import dev.cline.termux.data.checkpoint.GitDiagnosis
import dev.cline.termux.data.checkpoint.RestoreKind
import dev.cline.termux.ui.chat.rows.ActionError
import dev.cline.termux.ui.chat.CheckpointRowActions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The C.7 sentences and the row-state mapping, against the real string resources. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckpointCopyTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `row errors - kernel text, fallbacks, split partial and picker variants`() {
        assertEquals("No checkpoint found at or before run 2 in session s", rowErrorText(res, RowError(RestoreKind.Revert, "No checkpoint found at or before run 2 in session s")))
        assertEquals("Could not restore checkpoint.", rowErrorText(res, RowError(RestoreKind.Revert, null)))
        assertEquals("Could not restart from this message.", rowErrorText(res, RowError(RestoreKind.Edit, null)))
        assertEquals("Cline Hub is not connected.", rowErrorText(res, RowError(RestoreKind.Revert, null, notConnected = true)))
        assertEquals(
            "The workspace was restored, but the new session could not start: boom",
            rowErrorText(res, RowError(RestoreKind.Edit, "boom", workspaceRestored = true)),
        )
        assertEquals(
            "The workspace was restored, but the new session could not start: Could not restore checkpoint.",
            rowErrorText(res, RowError(RestoreKind.Revert, null, workspaceRestored = true)),
        )
        assertEquals("Checkpoint restore failed: boom", rowErrorText(res, RowError(RestoreKind.Edit, "boom", pickerMode = true)))
        assertEquals(
            "Checkpoint restore failed: no result returned.",
            rowErrorText(res, RowError(RestoreKind.Edit, null, pickerMode = true, noResult = true)),
        )
    }

    @Test
    fun `snackbars - CLI strings verbatim`() {
        assertEquals("Restored to checkpoint", snackText(res, CheckpointSnack.Restored))
        assertEquals("Checkpoint restore already in progress", snackText(res, CheckpointSnack.InProgress))
        assertEquals("Wait for the current turn to finish before undoing", snackText(res, CheckpointSnack.Busy))
        assertEquals("Restoring to checkpoint (chat + workspace)...", snackText(res, CheckpointSnack.Status(workspace = true)))
        assertEquals("Restoring to checkpoint (chat only)...", snackText(res, CheckpointSnack.Status(workspace = false)))
        assertEquals("Could not restore checkpoint.", snackText(res, CheckpointSnack.RestoreError))
    }

    @Test
    fun `dialog copy - preview line and diagnosis bodies`() {
        assertEquals("Restore to: \"Add a toggle\"", res.getString(R.string.checkpoint_restore_to, "Add a toggle"))
        assertTrue(res.getString(diagnosisText(GitDiagnosis.NoGit)).contains("pkg install git"))
        assertTrue(res.getString(diagnosisText(GitDiagnosis.NotARepository)).contains("git init"))
        assertEquals("Restore checkpoint…", res.getString(R.string.checkpoint_header_menu))
    }

    @Test
    fun `row actions resolve errors to their C_7 sentences`() {
        val state = CheckpointUiState(
            sessionId = "s",
            pending = RowPending("m1", RestoreKind.Edit, pickerMode = false),
            errors = mapOf("m2" to RowError(RestoreKind.Revert, null), "m3" to RowError(RestoreKind.Edit, "boom")),
            versioningPending = true,
            sourceGone = true,
        )
        val actions = CheckpointRowActions.of(state) { rowErrorText(res, it) }
        assertEquals("m1", actions.editPendingId)
        assertEquals(null, actions.restorePendingId)
        assertEquals(mapOf("m2" to ActionError("Could not restore checkpoint.")), actions.restoreErrors)
        assertEquals(mapOf("m3" to ActionError("boom")), actions.editErrors)
        assertTrue(actions.versioningPending)
        assertTrue("a gone source disables the actions", actions.versioningDisabled)
    }

    @Test
    fun `the drain line copy`() {
        assertEquals("Cline Hub is restarting. Waiting...", res.getString(R.string.checkpoint_draining))
    }
}
