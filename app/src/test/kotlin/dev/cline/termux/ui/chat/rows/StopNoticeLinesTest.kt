package dev.cline.termux.ui.chat.rows

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.Meta
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.session.StopNotice
import dev.cline.termux.data.session.StopNoticeKind
import dev.cline.termux.data.session.StopNotices
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A17: the words of the app's stop notice rows ([noticeLines]). The owner's phone runs zh-CN,
 * whose plural rules have one form, so the timeout must still read "5 minutes" (the form follows
 * the copy's language, `quantityResources`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "zh-rCN")
class StopNoticeLinesTest {
    private val resources: Resources = ApplicationProvider.getApplicationContext<Context>().resources

    private fun stopped(detail: String = "") =
        StopNotices.row(StopNotice("s1", "m1", StopNoticeKind.Stopped, detail = detail, at = 0L))

    @Test
    fun `a stop names no count, with the kernel's latest detail under it`() {
        // Trial row 21: a loop stop's count is the kernel's limit, not the 5 identical calls its detail names.
        assertEquals(
            NoticeLines("Stopped after repeated mistakes", "Latest: Detected 5 consecutive identical calls to read_files"),
            noticeLines(stopped("Detected 5 consecutive identical calls to read_files"), resources),
        )
        assertEquals(NoticeLines("Stopped after repeated mistakes"), noticeLines(stopped(), resources))
    }

    @Test
    fun `a timeout says the question was not answered within five minutes`() {
        val row = StopNotices.row(StopNotice("s1", "m1", StopNoticeKind.TimedOut, at = 0L))
        assertEquals(NoticeLines("Stopped: the question was not answered within 5 minutes"), noticeLines(row, resources))
    }

    @Test
    fun `the kernel's own notices keep its words`() {
        val kernel = ChatMessage(
            id = "n1",
            sessionId = "s1",
            role = Role.Status,
            content = "Stopped by the user",
            createdAt = 0L,
            meta = Meta(noticeType = "stop"),
        )
        assertEquals(NoticeLines("Stopped by the user"), noticeLines(kernel, resources))
    }
}
