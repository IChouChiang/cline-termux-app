package dev.cline.termux.core.kernel.termux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandOutputsTest {
    @Test
    fun `hub status parses the JSON line the CLI prints`() {
        val status = HubStatusOutput.parse(
            "\u001B[2mconnecting\u001B[0m\n{\"running\":true,\"url\":\"ws://127.0.0.1:25463/hub\",\"pid\":4242,\"startedAt\":\"2026-10-03T21:00:00.000Z\",\"uptime\":\"2m\",\"cliVersion\":\"3.0.68\",\"coreVersion\":\"0.0.90\"}\n",
        )!!
        assertTrue(status.running)
        assertEquals(4242, status.pid)
        assertEquals("3.0.68", status.cliVersion)
        assertEquals("2m", status.uptime)
        assertEquals(false, HubStatusOutput.parse("{\"running\":false}")!!.running)
        assertNull(HubStatusOutput.parse("nothing here"))
    }

    @Test
    fun `VERSION file parses release, tag and cline`() {
        val version = PortVersion.parse("# written by install-cline-termux.sh\nrelease=3.0.68-termux.2\ntag=cli-v3.0.68\ncline=3.0.68\n")
        assertEquals("3.0.68-termux.2", version.release)
        assertEquals("cli-v3.0.68", version.tag)
        assertEquals("3.0.68", version.cline)
        assertEquals(PortRelease(3, 0, 68, 2), version.parsedRelease)
        val old = PortVersion.parse("cline=3.0.68\n")
        assertNull(old.release)
        assertNull(old.parsedRelease)
    }

    @Test
    fun `port releases compare semver first then the revision`() {
        val min = PortRelease.parse("3.0.68-termux.2")!!
        assertTrue(PortRelease.parse("3.0.68-termux.1")!! < min)
        assertTrue(PortRelease.parse("3.0.67-termux.9")!! < min)
        assertTrue(PortRelease.parse("3.0.69-termux.1")!! > min)
        assertTrue(PortRelease.parse("3.1.0-termux.1")!! > min)
        assertTrue(PortRelease.parse("v3.0.68-termux.2")!! == min)
        assertNull(PortRelease.parse("3.0.68"))
        assertEquals("3.0.68-termux.2", min.toString())
        assertEquals("3.0.68-termux.2", PortRelease.fromReadlink("/data/data/com.termux/files/usr/opt/cline-termux/3.0.68-termux.2\n"))
        assertNull(PortRelease.fromReadlink(""))
    }

    @Test
    fun `the daemon's argv names the release tree it runs from`() {
        // Trial row 24. The S25U's Hub on 2026-10-17, one argument per line.
        val prefix = "/data/data/com.termux/files/usr"
        val bun = "$prefix/opt/bun-android-ffi/1.4.0-canary.1-55f6c899f/bun"
        val tail = "--cwd\n/data/data/com.termux/files/home\n--host\n127.0.0.1\n--port\n25463\n--pathname\n/hub\n"
        assertEquals("3.0.72-termux.1", PortRelease.fromHubCmdline("$bun\n$prefix/opt/cline-termux/3.0.72-termux.1/entry.js\n$tail"))
        // The Tab S7+: termux-exec runs Bun through the linker, so its path comes twice.
        assertEquals("3.0.69-termux.1", PortRelease.fromHubCmdline("$bun\n$bun\n$prefix/opt/cline-termux/3.0.69-termux.1/entry.js\n$tail"))
        assertNull("the symlink names no release", PortRelease.fromHubCmdline("$bun\n$prefix/opt/cline-termux/current/entry.js\n$tail"))
        assertNull(PortRelease.fromHubCmdline("$bun\n$prefix/opt/cline-termux/3.0.72-termux.1/index.js\n"))
        assertNull(PortRelease.fromHubCmdline(""))
    }

    @Test
    fun `global settings keep only telemetry and disabled tools`() {
        val view = GlobalSettingsView.parse("""{"telemetryOptOut":true,"autoUpdateEnabled":false,"disabledTools":["tasks","web_search"],"tools":{"webSearch":true}}""")
        assertEquals(true, view.telemetryOptOut)
        assertEquals(setOf("tasks", "web_search"), view.disabledTools)
        assertFalse(view.isToolEnabled("tasks"))
        assertTrue(view.isToolEnabled("editor"))
        assertEquals(GlobalSettingsView.DEFAULT, GlobalSettingsView.parse("{}"))
        assertEquals(GlobalSettingsView.DEFAULT, GlobalSettingsView.parse("garbage"))
        assertEquals(GlobalSettingsView.DEFAULT, GlobalSettingsView.from(TermuxResult.failure(1, "cat: No such file or directory")))
        assertTrue(GlobalSettingsView.from(TermuxResult.success("""{"telemetryOptOut":false}""")).telemetryOptOut == false)
    }

    @Test
    fun `log tails drop the partial first line only when the file was cut`() {
        assertEquals("second\nthird\n", LogTail.clean("rst\nsecond\nthird\n", requestedBytes = 17))
        assertEquals("whole\nfile\n", LogTail.clean("whole\nfile\n", requestedBytes = 49_152))
        assertEquals("", LogTail.clean("no-newline-at-all", requestedBytes = 17))
        assertEquals("", LogTail.clean("", requestedBytes = 10))
    }

    @Test
    fun `directory listings cap at 500 and flag truncation`() {
        val lines = (1..600).joinToString("\n") { "/home/d$it" } + "\n"
        val listing = DirectoryListing.from(TermuxResult.success(lines))
        assertEquals(500, listing.directories.size)
        assertTrue(listing.truncated)
        val small = DirectoryListing.from(TermuxResult.success("/home/a\n/home/b\n"))
        assertEquals(listOf("/home/a", "/home/b"), small.directories)
        assertFalse(small.truncated)
        val capped = DirectoryListing.from(TermuxResult("/home/a\n", "", 0, -1, null, stdoutOriginalLength = 150_000))
        assertTrue(capped.truncated)
    }

    @Test
    fun `git branch probe maps the HC 2-5 outcomes`() {
        assertEquals(GitBranchProbe.Branch("main"), GitBranchProbe.from(TermuxResult.success("main\n")))
        assertEquals(GitBranchProbe.Detached, GitBranchProbe.from(TermuxResult.success("\n")))
        assertEquals(GitBranchProbe.NotARepository, GitBranchProbe.from(TermuxResult.failure(128, "fatal: not a git repository")))
        assertEquals(GitBranchProbe.GitMissing, GitBranchProbe.from(TermuxResult.undelivered(150, "No such file")))
        assertEquals(GitBranchProbe.Failed("fatal: bad"), GitBranchProbe.from(TermuxResult.failure(1, "fatal: bad\n")))
    }

    @Test
    fun `git work-tree probe maps the CT C3-5 outcomes`() {
        assertEquals(GitWorkTreeProbe.InsideWorkTree, GitWorkTreeProbe.from(TermuxResult.success("true\n")))
        assertEquals(GitWorkTreeProbe.NotARepository, GitWorkTreeProbe.from(TermuxResult.failure(128, "fatal: not a git repository")))
        assertEquals(GitWorkTreeProbe.GitMissing, GitWorkTreeProbe.from(TermuxResult.undelivered(150, "No such file")))
        assertEquals(GitWorkTreeProbe.Failed("permission denied"), GitWorkTreeProbe.from(TermuxResult.failure(1, "permission denied\n")))
        assertEquals(GitWorkTreeProbe.Failed(null), GitWorkTreeProbe.from(TermuxResult.success("false\n")))
    }
}
