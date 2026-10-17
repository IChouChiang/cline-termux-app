package dev.cline.termux.ui.workspace

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A22: a session with no folder chosen starts in the Termux home, so Home's chip reads "Home"
 * for that state and the drawer's project group of the home reads "Home"; "Chat" stays for the
 * kernel's chat workspace (sessions it imported).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DefaultFolderLabelsTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `no folder reads Home on the chip and in the drawer, Chat only for the chat workspace`() {
        val chip = resources.getString(R.string.home_workspace_default)
        assertEquals("Home", chip)
        assertEquals("Workspace: Home", resources.getString(R.string.home_workspace_cd, chip))
        assertEquals("Home", resources.getString(R.string.sessions_project_home))
        assertEquals("Chat", resources.getString(R.string.sessions_workspace_chat))
    }
}
