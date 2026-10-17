package dev.cline.termux.ui.components

import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import dev.cline.termux.core.kernel.dashboard.HelperFailure
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.customize.TermuxProblem
import dev.cline.termux.ui.customize.SourceWarningOverrides
import dev.cline.termux.ui.kernel.UiText
import dev.cline.termux.ui.marketplace.MarketplaceFailureOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one RepoFailure → copy mapping (CR §0.5), with the cases the Account, Marketplace,
 * Schedules and Customize mappers tested before they were merged into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FailureCopyTest {

    private val res: Resources = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @Test
    fun `kernel helper and CLI text is shown verbatim`() {
        assertEquals(UiText.Raw("boom"), RepoFailure.Hub("command_failed", "boom").toUiText())
        assertEquals(UiText.Raw("boom"), RepoFailure.Hub("schedule_command_failed", "boom").toUiText())
        assertEquals(
            UiText.Raw("settings_list_failed: boom"),
            RepoFailure.Hub("settings_list_failed", "settings_list_failed: boom").toUiText(),
        )
        assertEquals(UiText.Raw("MCP install failed with exit code 1"), RepoFailure.Command(1, "MCP install failed with exit code 1").toUiText())
        assertEquals(UiText.Raw("nope"), RepoFailure.Helper("nope").toUiText())
        assertEquals(UiText.Raw("Unexpected JSON"), RepoFailure.Malformed("Unexpected JSON").toUiText())
    }

    @Test
    fun `empty kernel text falls back to the code, the exit code or Unknown error`() {
        assertEquals(UiText.Raw("command_failed"), RepoFailure.Hub("command_failed", "").toUiText())
        assertEquals(UiText.Res(R.string.components_failure_unknown), RepoFailure.Hub("", " ").toUiText())
        assertEquals(UiText.Res(R.string.components_failure_command_exit, 3), RepoFailure.Command(3, "").toUiText())
        assertEquals(UiText.Res(R.string.components_failure_unknown), RepoFailure.Helper("").toUiText())
        assertEquals(UiText.Res(R.string.components_failure_unknown), RepoFailure.Malformed("").toUiText())
        assertEquals("The command failed with exit code 3.", RepoFailure.Command(3, "").text(res))
        assertEquals("Unknown error", RepoFailure.Helper("").text(res))
    }

    @Test
    fun `connection cases use the CR 0_5 sentences`() {
        assertEquals(UiText.Res(R.string.components_failure_hub_disconnected), RepoFailure.HubDisconnected.toUiText())
        assertEquals(UiText.Res(R.string.components_failure_hub_timeout), RepoFailure.HubTimeout.toUiText())
        assertEquals(UiText.Res(R.string.components_failure_hub_draining), RepoFailure.HubDraining.toUiText())
        assertEquals("Cline Hub disconnected. Try again when it reconnects.", RepoFailure.HubDisconnected.text(res))
        assertEquals("The Cline Hub did not answer in time.", RepoFailure.HubTimeout.text(res))
        assertEquals("Cline Hub is restarting. Try again in a moment.", RepoFailure.HubDraining.text(res))
    }

    @Test
    fun `every Termux problem and helper case has its own sentence`() {
        assertEquals(UiText.Res(R.string.components_failure_termux_missing), RepoFailure.Termux(TermuxProblem.NotInstalled).toUiText())
        assertEquals(UiText.Res(R.string.components_failure_termux_timeout), RepoFailure.Termux(TermuxProblem.TimedOut).toUiText())
        assertEquals(UiText.Res(R.string.components_failure_termux_start), RepoFailure.Termux(TermuxProblem.StartFailed).toUiText())
        assertEquals("Termux is not installed.", RepoFailure.Termux(TermuxProblem.NotInstalled).text(res))
        assertEquals("Cline is not allowed to run commands in Termux.", RepoFailure.Termux(TermuxProblem.PermissionMissing).text(res))
        assertEquals("Termux is blocking commands from other apps.", RepoFailure.Termux(TermuxProblem.ExternalAppsBlocked).text(res))
        assertEquals("Termux did not answer.", RepoFailure.Termux(TermuxProblem.TimedOut).text(res))
        assertEquals("Termux could not start the command.", RepoFailure.Termux(TermuxProblem.StartFailed).text(res))
        assertEquals("The settings helper in Termux is not running.", RepoFailure.HelperDown(null).text(res))
        assertEquals("The settings helper in Termux is not running.", RepoFailure.HelperDown(HelperFailure.StartTimeout).text(res))
        assertEquals("The settings helper in Termux stopped responding.", RepoFailure.HelperLost.text(res))
        assertEquals("The settings helper in Termux did not answer in time.", RepoFailure.HelperTimeout.text(res))
    }

    @Test
    fun `no failure ever reads as a Kotlin object name`() {
        val all = listOf(
            RepoFailure.Hub("", ""), RepoFailure.HubDisconnected, RepoFailure.HubTimeout, RepoFailure.HubDraining,
            RepoFailure.Command(1, ""), RepoFailure.HelperDown(null), RepoFailure.Helper(""), RepoFailure.HelperLost,
            RepoFailure.HelperTimeout, RepoFailure.Malformed(""),
        ) + TermuxProblem.entries.map { RepoFailure.Termux(it) }
        all.forEach { failure ->
            val text = failure.text(res)
            assertFalse("$failure -> '$text'", text.isBlank())
            assertFalse("$failure -> '$text'", text.contains("HelperLost") || text.contains("HelperDown(") || text.contains("reason="))
        }
    }

    @Test
    fun `overrides replace only the cases they name`() {
        val overrides = FailureOverrides { if (it == RepoFailure.HubTimeout) UiText.Raw("custom") else null }
        assertEquals(UiText.Raw("custom"), RepoFailure.HubTimeout.toUiText(overrides))
        assertEquals(UiText.Res(R.string.components_failure_hub_draining), RepoFailure.HubDraining.toUiText(overrides))
    }

    @Test
    fun `marketplace keeps its install sentences`() {
        assertEquals(UiText.Res(R.string.marketplace_interrupted), RepoFailure.HelperLost.toUiText(MarketplaceFailureOverrides))
        assertEquals(UiText.Res(R.string.marketplace_interrupted), RepoFailure.HelperTimeout.toUiText(MarketplaceFailureOverrides))
        assertEquals(UiText.Res(R.string.marketplace_helper_body), RepoFailure.HelperDown(null).toUiText(MarketplaceFailureOverrides))
        assertEquals(UiText.Res(R.string.components_failure_hub_disconnected), RepoFailure.HubDisconnected.toUiText(MarketplaceFailureOverrides))
        assertEquals(UiText.Res(R.string.components_failure_hub_timeout), RepoFailure.HubTimeout.toUiText(MarketplaceFailureOverrides))
        assertEquals(UiText.Res(R.string.components_failure_hub_draining), RepoFailure.HubDraining.toUiText(MarketplaceFailureOverrides))
        assertEquals(UiText.Res(R.string.components_failure_termux_start), RepoFailure.Termux(TermuxProblem.StartFailed).toUiText(MarketplaceFailureOverrides))
        assertEquals(UiText.Raw("nope"), RepoFailure.Helper("nope").toUiText(MarketplaceFailureOverrides))
    }

    @Test
    fun `customize source warnings use the short Termux forms`() {
        assertEquals("Termux did not answer", RepoFailure.Termux(TermuxProblem.TimedOut).text(res, SourceWarningOverrides))
        assertEquals("Termux is blocking Cline", RepoFailure.Termux(TermuxProblem.ExternalAppsBlocked).text(res, SourceWarningOverrides))
        assertEquals("The Cline Hub did not answer in time.", RepoFailure.HubTimeout.text(res, SourceWarningOverrides))
    }
}
