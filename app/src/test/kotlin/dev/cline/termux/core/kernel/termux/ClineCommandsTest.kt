package dev.cline.termux.core.kernel.termux

import dev.cline.termux.core.kernel.termux.ClineCommands.Labels
import dev.cline.termux.fakes.FakeTermuxExecutor
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every catalog row builds the documented argv (docs/07 §3.4, §10.1 "Bridge"). */
class ClineCommandsTest {
    private val prefix = "/data/data/com.termux/files/usr"
    private val home = "/data/data/com.termux/files/home"

    private fun TermuxCommand.argv() = listOf(path) + args

    private fun check(command: TermuxCommand, argv: List<String>, label: String, timeoutMs: Long, background: Boolean = true, workdir: String = home) {
        assertEquals(argv, command.argv())
        assertEquals(label, command.label)
        assertEquals(timeoutMs, command.timeoutMs)
        assertEquals(background, command.background)
        assertEquals(workdir, command.workdir)
    }

    @Test
    fun `kernel probe and lifecycle rows`() {
        check(ClineCommands.probeVersion(), listOf("$prefix/bin/cat", "$prefix/opt/cline-termux/current/VERSION"), Labels.PROBE_VERSION, 10_000)
        check(ClineCommands.readPortRelease(), listOf("$prefix/bin/readlink", "$prefix/opt/cline-termux/current"), Labels.READ_PORT_RELEASE, 10_000)
        check(ClineCommands.cliVersion(), listOf("$prefix/bin/cline", "--version"), Labels.CLI_VERSION, 10_000)
        check(ClineCommands.hubStart(), listOf("$prefix/bin/cline", "hub", "start"), Labels.HUB_START, 60_000)
        check(ClineCommands.hubStatus(), listOf("$prefix/bin/cline", "hub", "status"), Labels.HUB_STATUS, 15_000)
        check(ClineCommands.hubStop(), listOf("$prefix/bin/cline", "hub", "stop"), Labels.HUB_STOP, 30_000)
        check(ClineCommands.hubUpgrade(), listOf("$prefix/bin/cline", "hub", "upgrade", "--wait", "30"), Labels.HUB_UPGRADE, 75_000)
        check(ClineCommands.wakeLock(), listOf("$prefix/bin/termux-wake-lock"), Labels.WAKE_LOCK, 10_000)
        check(ClineCommands.wakeUnlock(), listOf("$prefix/bin/termux-wake-unlock"), Labels.WAKE_UNLOCK, 10_000)
        check(ClineCommands.readHubDiscovery(), listOf("$prefix/bin/cat", "$home/.cline/data/locks/hub/production.json"), Labels.READ_HUB_DISCOVERY, 20_000)
        check(ClineCommands.tailDaemonLog(), listOf("$prefix/bin/tail", "-c", "4000", "$home/.cline/data/logs/hub-daemon.log"), Labels.TAIL_DAEMON_LOG, 10_000)
        check(ClineCommands.tailLog("$home/.cline/data/logs/cline.log"), listOf("$prefix/bin/tail", "-c", "49152", "$home/.cline/data/logs/cline.log"), Labels.TAIL_LOG, 15_000)
        check(ClineCommands.doctorJson(), listOf("$prefix/bin/cline", "doctor", "--json"), Labels.DOCTOR, 30_000)
        check(ClineCommands.doctorFix(), listOf("$prefix/bin/cline", "doctor", "fix", "--json"), Labels.DOCTOR_FIX, 60_000)
    }

    @Test
    fun `provider and sign-in rows`() {
        check(ClineCommands.statProviders(), listOf("$prefix/bin/stat", "-c", "%Y", "$home/.cline/data/settings/providers.json"), Labels.STAT_PROVIDERS, 10_000)
        check(ClineCommands.readProviderPresence(), listOf("$prefix/bin/cat", "$home/.cline/data/settings/providers.json"), Labels.READ_PROVIDER_PRESENCE, 10_000)
        check(ClineCommands.readGlobalSettings(), listOf("$prefix/bin/cat", "$home/.cline/data/settings/global-settings.json"), Labels.READ_GLOBAL_SETTINGS, 10_000)
        check(
            ClineCommands.authProvider("anthropic", "sk-1", "claude-sonnet-4-6"),
            listOf("$prefix/bin/cline", "auth", "--provider", "anthropic", "--apikey", "sk-1", "--modelid", "claude-sonnet-4-6"),
            "cline auth anthropic", 30_000,
        )
        check(
            ClineCommands.authProvider("openai-compatible", "k", "mock-1", "http://127.0.0.1:25499/v1"),
            listOf("$prefix/bin/cline", "auth", "--provider", "openai-compatible", "--apikey", "k", "--modelid", "mock-1", "--baseurl", "http://127.0.0.1:25499/v1"),
            "cline auth openai-compatible", 30_000,
        )
        assertFalse(ClineCommands.authProvider("x", "k", "m", "   ").args.contains("--baseurl"))

        val signIn = ClineCommands.authInteractive()
        check(signIn, listOf("$prefix/bin/cline", "auth", "cline"), Labels.SIGN_IN_CLINE, 60_000, background = false)
        assertEquals(0, signIn.sessionAction)
        assertEquals(listOf("$prefix/bin/cline", "auth", "openai-codex"), ClineCommands.authInteractive("openai-codex").argv())
        assertFalse(ClineCommands.authInteractive("oca").background)
        assertTrue(ClineCommands.OAUTH_PROVIDER_IDS.containsAll(listOf("cline", "openai-codex", "oca")))

        check(ClineCommands.cancelAuth(), listOf("$prefix/bin/pkill", "-f", "index.js auth (cline|openai-codex|oca)$"), Labels.CANCEL_AUTH, 10_000)
        val cancel = Regex(ClineCommands.CANCEL_AUTH_PATTERN)
        assertTrue(cancel.containsMatchIn("/bun /opt/cline-termux/current/index.js auth cline"))
        assertFalse(cancel.containsMatchIn("/bun /opt/cline-termux/current/index.js auth --provider anthropic --apikey k"))
    }

    @Test
    fun `settings helper rows`() {
        val secret = "0123456789abcdef0123456789abcdef"
        check(
            ClineCommands.dashboardStart(secret),
            listOf("$prefix/bin/cline", "dashboard", "--no-open", "--host", "127.0.0.1", "--port", "8787", "--room-secret", secret),
            Labels.DASHBOARD_START, 60_000,
        )
        check(
            ClineCommands.dashboardStop(secret),
            listOf("$prefix/bin/pkill", "-f", "index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret $secret( |$)"),
            Labels.DASHBOARD_STOP, 10_000,
        )
        assertEquals(
            "index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret [0-9a-f]{32}( |$)",
            ClineCommands.dashboardStop().args.last(),
        )
        // The generic pattern matches the app's launch line and nothing a user typed by hand.
        val pattern = Regex(ClineCommands.dashboardStopPattern(null))
        assertTrue(pattern.containsMatchIn("/bun /opt/cline-termux/current/index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret $secret"))
        assertFalse(pattern.containsMatchIn("/bun /opt/cline-termux/current/index.js dashboard --no-open --host 127.0.0.1 --port 8787"))
        assertFalse(pattern.containsMatchIn("/bun index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret mine"))
        val exact = Regex(ClineCommands.dashboardStopPattern(secret))
        assertFalse(exact.containsMatchIn("/bun index.js dashboard --no-open --host 127.0.0.1 --port 8787 --room-secret ffffffffffffffffffffffffffffffff"))
    }

    @Test
    fun `customize, MCP and marketplace rows`() {
        val ws = "$home/projects/app"
        check(ClineCommands.configToolsJson(ws), listOf("$prefix/bin/cline", "config", "tools", "--json"), Labels.CONFIG_TOOLS, 60_000, workdir = ws)
        check(ClineCommands.configHooksJson(ws), listOf("$prefix/bin/cline", "config", "hooks", "--json"), Labels.CONFIG_HOOKS, 60_000, workdir = ws)
        check(ClineCommands.readMcpSettings(), listOf("$prefix/bin/cat", "$home/.cline/data/settings/cline_mcp_settings.json"), Labels.READ_MCP_SETTINGS, 10_000)
        check(
            ClineCommands.mcpInstallStdio("github", "npx", listOf("-y", "@modelcontextprotocol/server-github")),
            listOf("$prefix/bin/cline", "mcp", "install", "github", "--yes", "--json", "--", "npx", "-y", "@modelcontextprotocol/server-github"),
            Labels.MCP_INSTALL, 120_000,
        )
        check(
            ClineCommands.mcpInstallRemote("docs", "https://mcp.example.com/sse", McpTransport.Sse, linkedMapOf("Authorization" to "Bearer t", "X-Team" to "a")),
            listOf("$prefix/bin/cline", "mcp", "install", "docs", "https://mcp.example.com/sse", "--transport", "sse", "--header", "Authorization: Bearer t", "--header", "X-Team: a", "--yes", "--json"),
            Labels.MCP_INSTALL, 120_000,
        )
        assertEquals("streamable-http", McpTransport.StreamableHttp.cliValue)
        check(
            ClineCommands.mcpInstallMarketplace(listOf("fetch", "--", "uvx", "mcp-server-fetch")),
            listOf("$prefix/bin/cline", "mcp", "install", "--yes", "--json", "fetch", "--", "uvx", "mcp-server-fetch"),
            Labels.MCP_INSTALL, 300_000,
        )
        check(ClineCommands.mcpUninstall("github"), listOf("$prefix/bin/cline", "mcp", "uninstall", "github", "--json"), Labels.MCP_UNINSTALL, 120_000)
        check(ClineCommands.pluginInstall("cline-plugin-x"), listOf("$prefix/bin/cline", "plugin", "install", "cline-plugin-x", "--json"), Labels.PLUGIN_INSTALL, 300_000)
        check(ClineCommands.pluginUninstall("$home/.cline/plugins/x"), listOf("$prefix/bin/cline", "plugin", "uninstall", "$home/.cline/plugins/x", "--json"), Labels.PLUGIN_UNINSTALL, 120_000)
        check(
            ClineCommands.skillAdd(listOf("owner/repo", "--skill", "review")),
            listOf("$prefix/bin/npx", "-y", "skills@latest", "add", "owner/repo", "--skill", "review", "-g", "-a", "cline", "-y"),
            Labels.SKILL_ADD, 300_000,
        )
        check(ClineCommands.skillRemove("review"), listOf("$prefix/bin/cline", "skill", "remove", "review", "-g", "-y"), Labels.SKILL_REMOVE, 120_000)
        assertEquals("review", ClineCommands.installedSkillDirectory("$home/.cline/skills/review/SKILL.md"))
        assertEquals("review", ClineCommands.installedSkillDirectory("$home/.cline/skills/review"))
        assertEquals("review", ClineCommands.installedSkillDirectory("$home/.cline/skills/review/"))
    }

    @Test
    fun `git rows`() {
        val ws = "$home/projects/app"
        check(ClineCommands.gitBranch(ws), listOf("$prefix/bin/git", "-C", ws, "branch", "--show-current"), Labels.GIT_BRANCH, 10_000)
        check(ClineCommands.gitShortHead(ws), listOf("$prefix/bin/git", "-C", ws, "rev-parse", "--short", "HEAD"), Labels.GIT_SHORT_HEAD, 10_000)
        check(
            ClineCommands.gitBranches(ws),
            listOf("$prefix/bin/git", "-C", ws, "for-each-ref", "--count=500", "--sort=-committerdate", "--format=%(refname:short)", "refs/heads"),
            Labels.GIT_BRANCHES, 10_000,
        )
        check(ClineCommands.gitCheckout(ws, "feature/x"), listOf("$prefix/bin/git", "-C", ws, "checkout", "feature/x"), Labels.GIT_CHECKOUT, 30_000)
        check(ClineCommands.gitIsWorkTree(ws), listOf("$prefix/bin/git", "-C", ws, "rev-parse", "--is-inside-work-tree"), Labels.GIT_IS_WORK_TREE, 10_000)
    }

    @Test
    fun `workspace rows expand the tilde and keep the find shapes`() {
        check(ClineCommands.realpath("~/projects"), listOf("$prefix/bin/realpath", "-e", "$home/projects"), Labels.REALPATH, 10_000)
        check(ClineCommands.isDirectory("/sdcard/x"), listOf("$prefix/bin/find", "/sdcard/x", "-maxdepth", "0", "-type", "d"), Labels.IS_DIRECTORY, 10_000)
        check(ClineCommands.testDirectory("~/ws"), listOf("$prefix/bin/test", "-d", "$home/ws"), Labels.TEST_DIRECTORY, 10_000)
        check(
            ClineCommands.listDirectories("~"),
            listOf("$prefix/bin/find", home, "-maxdepth", "1", "-mindepth", "1", "-type", "d", "-not", "-name", ".*"),
            Labels.LIST_DIRECTORIES, 15_000,
        )
        check(ClineCommands.hasSharedStorage(), listOf("$prefix/bin/find", "$home/storage", "-maxdepth", "0"), Labels.HAS_SHARED_STORAGE, 10_000)
        val setup = ClineCommands.setupStorage()
        check(setup, listOf("$prefix/bin/termux-setup-storage"), Labels.SETUP_STORAGE, 60_000, background = false)
        assertEquals(0, setup.sessionAction)
    }

    @Test
    fun `mention search runs the CA C6 pipeline verbatim through bash`() {
        val command = ClineCommands.searchWorkspaceFiles("$home/projects/app", "pars")
        assertEquals("$prefix/bin/bash", command.path)
        assertEquals(5, command.args.size)
        assertEquals("-c", command.args[0])
        assertEquals(
            "cd \"\$1\" && { if command -v rg >/dev/null 2>&1; then rg --files --hidden -g '!.git' 2>/dev/null; " +
                "else find . -type f -not -path './.git/*' -not -path '*/node_modules/*' -printf '%P\\n' 2>/dev/null; fi; } " +
                "| grep -i -F -- \"\$2\" | head -n 200",
            command.args[1],
        )
        assertEquals(listOf("_", "$home/projects/app", "pars"), command.args.drop(2))
        assertEquals(Labels.SEARCH_WORKSPACE_FILES, command.label)
        assertEquals(10_000, command.timeoutMs)
        check(ClineCommands.readTextFile("~/.cline/skills/x/SKILL.md"), listOf("$prefix/bin/cat", "$home/.cline/skills/x/SKILL.md"), Labels.READ_TEXT_FILE, 60_000)
        check(ClineCommands.historyExport("s1", "/sdcard/Download/s1.md"), listOf("$prefix/bin/cline", "history", "export", "s1", "-o", "/sdcard/Download/s1.md"), Labels.HISTORY_EXPORT, 60_000)
    }

    @Test
    fun `attachment writes go through stdin in 128 Ki chunks and end with an atomic rename`() {
        val dir = "$home/.cline/data/attachments/android/draft/uuid-1"
        val content = "x".repeat(ClineCommands.ATTACHMENT_CHUNK_CHARS * 2 + 10)
        val commands = ClineCommands.attachmentWriteCommands(dir, "notes.txt", content)
        assertEquals(4, commands.size)
        commands.dropLast(1).forEachIndexed { index, command ->
            assertEquals("$prefix/bin/bash", command.path)
            assertEquals(listOf("-c", "umask 077 && mkdir -p \"\$1\" && cat >> \"\$1/.\$2.part\"", "_", dir, "notes.txt"), command.args)
            assertEquals(Labels.WRITE_ATTACHMENT_CHUNK, command.label)
            assertEquals(60_000, command.timeoutMs)
            val expected = if (index < 2) ClineCommands.ATTACHMENT_CHUNK_CHARS else 10
            assertEquals(expected, command.stdin!!.length)
        }
        val finalize = commands.last()
        assertEquals(listOf("-c", "mv -f \"\$1/.\$2.part\" \"\$1/\$2\"", "_", dir, "notes.txt"), finalize.args)
        assertNull(finalize.stdin)
        assertEquals(Labels.FINALIZE_ATTACHMENT, finalize.label)
        assertEquals("$dir/notes.txt", ClineCommands.attachmentPath(dir, "notes.txt"))
        // A 1 MB file is at most 9 commands (8 chunks + the rename).
        assertEquals(9, ClineCommands.attachmentWriteCommands(dir, "big.txt", "y".repeat(1_048_576)).size)
    }

    @Test
    fun `attachment chunking never splits a surrogate pair and names are sanitised`() {
        val emoji = "😀"
        val content = "ab$emoji"
        val chunks = ClineCommands.attachmentChunks(content, chunkChars = 3)
        assertEquals(listOf("ab", emoji), chunks)
        assertEquals(listOf(""), ClineCommands.attachmentChunks(""))
        assertEquals("_env", ClineCommands.sanitizeAttachmentName(".env"))
        assertEquals("ab.txt", ClineCommands.sanitizeAttachmentName("a/b\u0000.txt"))
        assertEquals("attachment", ClineCommands.sanitizeAttachmentName("///"))
    }

    @Test
    fun `writeAttachment runs the sequence in order and stops at the first failure`() = runTest {
        val termux = FakeTermuxExecutor()
        val path = termux.writeAttachment("/d", "a.txt", "hello").getOrThrow()
        assertEquals("/d/a.txt", path)
        assertEquals(listOf(Labels.WRITE_ATTACHMENT_CHUNK, Labels.FINALIZE_ATTACHMENT), termux.labels)
        assertEquals("hello", termux.commands.first().stdin)

        val failing = FakeTermuxExecutor().apply { on(Labels.FINALIZE_ATTACHMENT, TermuxResult.failure(1, "mv: cannot move")) }
        val failure = failing.writeAttachment("/d", "a.txt", "hello").exceptionOrNull() as AttachmentWriteFailed
        assertEquals(Labels.FINALIZE_ATTACHMENT, failure.label)
        assertEquals("mv: cannot move", failure.detail)

        val blocked = FakeTermuxExecutor().apply { throwing(Labels.WRITE_ATTACHMENT_CHUNK, TermuxUnavailable.PermissionMissing()) }
        assertTrue(blocked.writeAttachment("/d", "a.txt", "x").exceptionOrNull() is TermuxUnavailable.PermissionMissing)
        assertEquals(1, blocked.commands.size)
    }
}
