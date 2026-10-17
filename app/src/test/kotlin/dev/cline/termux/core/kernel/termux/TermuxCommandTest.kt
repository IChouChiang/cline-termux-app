package dev.cline.termux.core.kernel.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/07 §10.1 "Bridge": `toString` redaction and result semantics. */
class TermuxCommandTest {
    private val key = "sk-ant-verysecret-0123456789"
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun `toString prints the label and the argument count only`() {
        val command = ClineCommands.authProvider("anthropic", key, "claude-sonnet-4-6")
        assertEquals("cline auth anthropic (7 args)", command.toString())
        assertFalse(command.toString().contains(key))
        assertEquals("termux-wake-lock (0 args)", ClineCommands.wakeLock().toString())
        assertEquals("cline --version (1 arg)", ClineCommands.cliVersion().toString())
    }

    @Test
    fun `toString never renders the room secret, headers or stdin`() {
        val start = ClineCommands.dashboardStart(secret)
        assertEquals("start settings helper (8 args)", start.toString())
        assertFalse(start.toString().contains(secret))

        val remote = ClineCommands.mcpInstallRemote("docs", "https://x", McpTransport.Sse, mapOf("Authorization" to "Bearer t0k"))
        assertFalse(remote.toString().contains("t0k"))

        val chunk = ClineCommands.attachmentWriteCommands("/tmp/x", "notes.txt", "top secret body").first()
        assertEquals("write attachment chunk (5 args, stdin)", chunk.toString())
        assertFalse(chunk.toString().contains("top secret"))
    }

    @Test
    fun `TimedOut carries the label, never the arguments`() {
        val error = TermuxUnavailable.TimedOut(ClineCommands.authProvider("openai-native", key, "gpt-5.5").label, 30_000)
        assertEquals("No result from Termux after 30000 ms for `cline auth openai-native`", error.message)
        assertFalse(error.message!!.contains(key))
    }

    @Test
    fun `default labels redact secret flag values`() {
        val command = TermuxCommand.cline("auth", "--provider", "x", "--apikey", key, "-k", key, "--room-secret", secret, "--header", "Authorization: Bearer t")
        assertEquals("cline auth --provider x --apikey ••• -k ••• --room-secret ••• --header •••", command.label)
        assertEquals(listOf("--apikey=•••"), TermuxCommand.redactArgs(listOf("--apikey=$key")))
        assertFalse(command.toString().contains(key))
    }

    @Test
    fun `bin and cline builders target the Termux prefix and keep the home workdir`() {
        val cat = TermuxCommand.bin("cat", "/x")
        assertEquals("/data/data/com.termux/files/usr/bin/cat", cat.path)
        assertEquals(TermuxPaths.HOME, cat.workdir)
        assertTrue(cat.background)
        assertEquals(TermuxTimeouts.DEFAULT_MS, cat.timeoutMs)
        val cline = TermuxCommand.cline("hub", "status")
        assertEquals(TermuxPaths.CLINE, cline.path)
        assertEquals("cline hub status", cline.label)
    }

    @Test
    fun `result flags follow the Termux bundle semantics`() {
        val ok = TermuxResult.success("out")
        assertTrue(ok.ok && ok.delivered && !ok.truncated)
        val failed = TermuxResult.failure(1, "cat: /x: No such file or directory\n")
        assertTrue(failed.delivered && !failed.ok && failed.noSuchFile)
        assertEquals("cat: /x: No such file or directory", failed.lastStderrLine)
        val blocked = TermuxResult.undelivered(1, "RUN_COMMAND Intent ... allow-external-apps property to true")
        assertTrue(blocked.internalError && blocked.mentionsExternalApps)
        val missing = TermuxResult.undelivered(TermuxResult.ERR_EXECUTABLE_FAILED, "No such file or directory")
        assertTrue(missing.executableFailed)
        assertFalse(TermuxResult.success("zq-secret-value").toString().contains("zq-secret-value"))
        assertEquals(listOf("a", "b"), TermuxResult.success("a\nb\n").stdoutLines)
        assertEquals("b", TermuxResult.success("a\nb\n\n").lastStdoutLine)
    }

    @Test
    fun `truncated reflects the 100 KB cap and the original lengths`() {
        val big = "a".repeat(60_000)
        assertTrue(TermuxResult(big, big, 0, -1, null).truncated)
        assertTrue(TermuxResult("short", "", 0, -1, null, stdoutOriginalLength = 500_000).truncated)
        assertFalse(TermuxResult("short", "", 0, -1, null).truncated)
    }

    @Test
    fun `expandHome replaces the tilde forms only`() {
        assertEquals(TermuxPaths.HOME, TermuxPaths.expandHome("~"))
        assertEquals("${TermuxPaths.HOME}/storage/shared", TermuxPaths.expandHome("~/storage/shared"))
        assertEquals("/sdcard/x~y", TermuxPaths.expandHome("/sdcard/x~y"))
        assertEquals("/a/~b", TermuxPaths.expandHome("/a/~b"))
    }

    @Test
    fun `presence and unavailable reasons`() {
        assertTrue(TermuxPresence.Installed(118, "0.118.3").installed)
        assertFalse(TermuxPresence.Disabled.installed)
        assertFalse(TermuxPresence.Missing.installed)
        val blocked = TermuxUnavailable.ExternalAppsBlocked("allow-external-apps is off")
        assertEquals("allow-external-apps is off", blocked.errmsg)
        assertTrue(TermuxUnavailable.StartFailed(IllegalStateException("bg")).message!!.contains("IllegalStateException"))
    }
}
