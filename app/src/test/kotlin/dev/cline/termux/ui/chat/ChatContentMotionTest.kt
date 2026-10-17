package dev.cline.termux.ui.chat

import dev.cline.termux.data.session.KernelVisual
import dev.cline.termux.ui.chat.ChatContentMotion.HOME
import dev.cline.termux.ui.chat.ChatContentMotion.PENDING
import dev.cline.termux.ui.home.kernelVisualOf
import org.junit.Assert.assertEquals
import org.junit.Test

/** HC §2.7 / §2.9 content swaps and the Home kernel notice dot (HC §2.4). */
class ChatContentMotionTest {
    @Test
    fun `home is keyed home`() {
        assertEquals(HOME, ChatContentMotion.keyOf(ChatUiState.Initial))
    }

    @Test
    fun `first send hand-off is the welcome swap both ways`() {
        assertEquals(ChatSwap.Welcome, ChatContentMotion.swap(HOME, PENDING))
        assertEquals(ChatSwap.Welcome, ChatContentMotion.swap(PENDING, HOME))
    }

    @Test
    fun `created session replacing the pending conversation does not animate`() {
        assertEquals(ChatSwap.None, ChatContentMotion.swap(PENDING, "s1"))
        assertEquals(ChatSwap.None, ChatContentMotion.swap("s1", "s1"))
    }

    @Test
    fun `selecting a session is shared axis forward and back to home is the pop`() {
        assertEquals(ChatSwap.Forward, ChatContentMotion.swap(HOME, "s1"))
        assertEquals(ChatSwap.Forward, ChatContentMotion.swap("s1", "s2"))
        assertEquals(ChatSwap.Back, ChatContentMotion.swap("s1", HOME))
    }

    @Test
    fun `kernel notice dot follows the DS kernel table`() {
        assertEquals(KernelVisual.Connecting, kernelVisualOf(KernelLine.Connecting))
        assertEquals(KernelVisual.Reconnecting, kernelVisualOf(KernelLine.Reconnecting))
        assertEquals(KernelVisual.Reconnecting, kernelVisualOf(KernelLine.Draining))
        assertEquals(KernelVisual.TermuxAsleep, kernelVisualOf(KernelLine.TermuxAsleep))
        assertEquals(KernelVisual.Unavailable, kernelVisualOf(KernelLine.Unavailable))
    }
}
