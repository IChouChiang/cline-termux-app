package dev.cline.termux.ui.components

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** KS 0.7 CommandBlock: what lands on the clipboard and the copy feedback it shows. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommandBlockTest {
    private val resources = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `copies exactly the command without trailing newlines`() {
        assertEquals("pkg install git", commandClipText("pkg install git"))
        assertEquals("pkg install git", commandClipText("pkg install git\n"))
        assertEquals("pkg install git", commandClipText("pkg install git\n\n"))
        assertEquals("echo a\necho b", commandClipText("echo a\necho b\n"))
        assertEquals("  indented", commandClipText("  indented"))
        assertEquals("", commandClipText(""))
    }

    @Test
    fun `clip label and feedback duration follow KS 0_7`() {
        assertEquals("command", COMMAND_CLIP_LABEL)
        assertEquals(2_000L, COMMAND_COPIED_FEEDBACK_MS)
    }

    @Test
    fun `copy button and snackbar copy`() {
        assertEquals("Copy command", resources.getString(R.string.setup_copy_command))
        assertEquals("Copied command", resources.getString(R.string.setup_copied_command))
        assertEquals("Command copied", resources.getString(R.string.setup_command_copied))
    }
}
