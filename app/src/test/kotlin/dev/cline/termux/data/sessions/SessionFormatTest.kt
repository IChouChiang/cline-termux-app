package dev.cline.termux.data.sessions

import dev.cline.termux.data.session.PromptMode
import dev.cline.termux.data.session.UserInputFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SS §1.4 formatting: `formatCompactTokens` from sessions-view.test.tsx, the use-session-history
 * helpers, `formatDisplayUserInput`, and the Android `compactAge` / run label rules.
 */
class SessionFormatTest {
    private val utc: ZoneId = ZoneOffset.UTC
    private fun at(text: String): Long = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test fun `abbreviates millions and thousands and leaves small counts alone`() {
        assertEquals("13.8m", formatCompactTokens(13_837_938))
        assertEquals("132.6k", formatCompactTokens(132_579))
        assertEquals("17.2k", formatCompactTokens(17_218))
        assertEquals("11", formatCompactTokens(11))
        assertEquals("0", formatCompactTokens(0))
        assertEquals("0", formatCompactTokens(-5.0))
        assertEquals("0", formatCompactTokens(Double.NaN))
    }

    @Test fun `tokens label pairs input and output, dash when unknown`() {
        assertEquals("12.5k/3.2k", tokensLabel(12_500, 3_200))
        assertEquals("1.2m/40.0k", tokensLabel(1_200_000, 40_000))
        assertEquals("0/38", tokensLabel(null, 38))
        assertEquals("-", tokensLabel(null, null))
    }

    @Test fun `token count for details`() {
        assertNull(formatTokenCount(null, null))
        assertNull(formatTokenCount(0, 0))
        assertEquals("999", formatTokenCount(900, 99))
        assertEquals("2.6k", formatTokenCount(2_450, 150))
        assertEquals("14k", formatTokenCount(12_000, 2_000))
    }

    @Test fun `cost uses four, three or two decimals`() {
        assertNull(formatCostUsd(null))
        assertNull(formatCostUsd(0.0))
        assertNull(formatCostUsd(-1.0))
        assertNull(formatCostUsd(Double.POSITIVE_INFINITY))
        assertEquals("$0.0042", formatCostUsd(0.0042))
        assertEquals("$0.123", formatCostUsd(0.123))
        assertEquals("$1.23", formatCostUsd(1.234))
    }

    @Test fun `compact age adds Yesterday and dates`() {
        val now = at("2026-09-13T12:00:00")
        assertEquals(CompactAge.Now, compactAge(now - 10_000, now, utc))
        assertEquals(CompactAge.Minutes(5), compactAge(now - 5 * 60_000, now, utc))
        assertEquals(CompactAge.Hours(3), compactAge(now - 3 * 3_600_000, now, utc))
        assertEquals(CompactAge.Yesterday, compactAge(at("2026-09-12T08:00:00"), now, utc))
        assertEquals(CompactAge.Days(4), compactAge(at("2026-09-09T12:00:00"), now, utc))
        assertEquals(CompactAge.Date(at("2026-08-01T12:00:00"), sameYear = true), compactAge(at("2026-08-01T12:00:00"), now, utc))
        assertEquals(CompactAge.Date(at("2025-09-13T12:00:00"), sameYear = false), compactAge(at("2025-09-13T12:00:00"), now, utc))
    }

    @Test fun `parses epoch seconds, millis and dates`() {
        assertEquals(1_700_000_000_000L, parseTimestamp("1700000000"))
        assertEquals(1_700_000_000_123L, parseTimestamp("1700000000123"))
        assertEquals(at("2026-08-31T19:31:40"), parseTimestamp("2026-08-31T19:31:40Z"))
        assertNull(parseTimestamp(""))
        assertNull(parseTimestamp("garbage"))
    }

    @Test fun `normalizes titles like formatDisplayUserInput`() {
        assertEquals("Run echo hello-from-mock and summarize", normalizeTitle("<user_input mode=\"act\">Run echo hello-from-mock and summarize</user_input>"))
        assertEquals("Hi", normalizeTitle("<mode_notice>The user switched from plan mode to act mode before sending this message.</mode_notice><user_input mode=\"act\">Hi</user_input>"))
        assertEquals("/team fix the bug", normalizeTitle("<user_command slash=\"team\">Spawn a team of agents for the following task: fix the bug</user_command>"))
        assertEquals("/team", normalizeTitle("<user_command slash=\"team\"></user_command>"))
        assertEquals("/review src", normalizeTitle("<user_command slash=\"review\">src</user_command>"))
        assertEquals("", normalizeTitle("   "))
    }

    @Test fun `session title falls back to the prompt then the id`() {
        assertEquals("Custom", sessionTitle("abc", " Custom ", "prompt"))
        assertEquals("First line", sessionTitle("abc", null, "<user_input mode=\"act\">First line\nsecond</user_input>"))
        assertEquals("Session k3p9x2", sessionTitle("1791064689782_k3p9x2", "", null))
        assertEquals("Hello", titleFromMessages(listOf("assistant" to "Hi there", "user" to "Hello\nmore")))
        assertNull(titleFromMessages(listOf("tool" to "x")))
    }

    @Test fun `search title and preview compact whitespace`() {
        assertEquals("a b", formatSessionSearchTitle("<user_input mode=\"act\">a\n\n b</user_input>"))
        val long = "x".repeat(300)
        assertEquals(240, formatSessionSearchTitle(long).length)
        assertEquals('…', formatSessionSearchTitle(long).last())
        assertEquals(480, formatSessionSearchPreview("user", "y".repeat(600)).length)
    }

    @Test fun `bounds and unwraps session search display text`() {
        val oversized = "generate ".repeat(10_000)
        val wrapped = UserInputFormat.formatUserInputBlock(oversized, PromptMode.Act)
        val title = formatSessionSearchTitle(wrapped)
        val preview = formatSessionSearchPreview("session", wrapped)
        assertFalse(title.contains("user_input"))
        assertFalse(preview.contains("user_input"))
        assertEquals(SESSION_SEARCH_TITLE_MAX_LENGTH, title.length)
        assertEquals(SESSION_SEARCH_PREVIEW_MAX_LENGTH, preview.length)
        assertTrue(title.endsWith("…"))
        assertTrue(preview.endsWith("…"))
    }

    @Test fun `preserves assistant examples while bounding their search previews`() {
        val content = "<user_input mode=\"act\">this tag is part of the answer</user_input>"
        assertEquals(content, formatSessionSearchPreview("assistant", content))
    }

    @Test fun `search preview unwraps only user and session snippets`() {
        val wrapped = "  <user_input mode=\"plan\">look\n here</user_input>  "
        assertEquals("look here", formatSessionSearchPreview("user", wrapped))
        assertEquals("look here", formatSessionSearchPreview("User", wrapped))
        assertEquals("look here", formatSessionSearchPreview("SESSION", wrapped))
        val raw = "<user_input mode=\"plan\">look here</user_input>"
        assertEquals(raw, formatSessionSearchPreview("tool", "  $raw  "))
        assertEquals(raw, formatSessionSearchPreview("", raw))
        val command = "<user_command slash=\"team\">x</user_command>"
        assertEquals(command, formatSessionSearchPreview("assistant", command))
        val notice = "<mode_notice>switched</mode_notice> ok"
        assertEquals(notice, formatSessionSearchPreview("tool", notice))
        assertEquals("", formatSessionSearchPreview("user", null))
    }

    @Test fun `search text never ends on half a surrogate pair`() {
        val grin = "😀"
        // The title cut is at 239 units; the emoji occupies 238-239, so it is dropped whole.
        assertEquals("y".repeat(238) + "…", compactSessionSearchText("y".repeat(238) + grin + "zzz", SESSION_SEARCH_TITLE_MAX_LENGTH))
        // Ending exactly at the cut keeps it.
        assertEquals("y".repeat(237) + grin + "…", compactSessionSearchText("y".repeat(237) + grin + "zzz", SESSION_SEARCH_TITLE_MAX_LENGTH))
        val preview = formatSessionSearchPreview("assistant", grin.repeat(300))
        assertEquals(grin.repeat(239) + "…", preview)
        assertEquals(SESSION_SEARCH_PREVIEW_MAX_LENGTH - 1, preview.length)
    }

    @Test fun `basename handles chat, Termux home and trailing separators`() {
        assertEquals("workspace", basenamePath(null))
        assertEquals("Chat", basenamePath("/data/data/com.termux/files/home/.cline/data/workspaces/chat"))
        assertEquals("~", basenamePath("/data/data/com.termux/files/home/"))
        assertEquals("cline-termux-app", basenamePath("/data/data/com.termux/files/home/cline-termux-app/"))
        assertEquals("cline", basenamePath("C:\\code\\cline\\"))
    }

    @Test fun `source labels`() {
        assertEquals("CLI", getSessionSourceLabel("cli"))
        assertEquals("Desktop", getSessionSourceLabel("Desktop"))
        assertEquals("VS Code", getSessionSourceLabel("vscode-webview"))
        assertEquals("Android", getSessionSourceLabel("android"))
        assertEquals("Hub Schedule", getSessionSourceLabel("hub-schedule"))
    }

    @Test fun `unique workspace labels use the shortest unique suffix`() {
        val paths = listOf("/work/acme/app", "/work/client/app", "/work/tools")
        assertEquals("acme/app", uniqueWorkspaceLabel("/work/acme/app", paths))
        assertEquals("tools", uniqueWorkspaceLabel("/work/tools", paths))
        assertEquals("Other", uniqueWorkspaceLabel("", paths))
    }

    @Test fun `schedule run label numbers runs or falls back to the start`() {
        assertEquals(ScheduleRunLabel.Numbered(7), scheduleRunLabel(7, 1L))
        assertEquals(ScheduleRunLabel.StartedAt(1_000L), scheduleRunLabel(null, 1_000L))
        assertEquals(ScheduleRunLabel.Unknown, scheduleRunLabel(0, null))
    }

    @Test fun `normalizes discovered statuses`() {
        assertEquals("completed", normalizeDiscoveredStatus("ended", null))
        assertEquals("cancelled", normalizeDiscoveredStatus("aborted", null))
        assertEquals("provisioning", normalizeDiscoveredStatus("provisioning", null))
        assertEquals("failed", normalizeDiscoveredStatus("error", null))
        assertEquals("running", normalizeDiscoveredStatus("running", "do it"))
        assertEquals("idle", normalizeDiscoveredStatus("running", " "))
        assertEquals("idle", normalizeDiscoveredStatus(null, null))
    }
}
