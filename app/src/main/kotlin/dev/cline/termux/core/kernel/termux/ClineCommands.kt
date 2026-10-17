package dev.cline.termux.core.kernel.termux

/**
 * The RUN_COMMAND catalog of docs/07 §3.4: every command the app issues, with its exact
 * spelling, label and timeout. Pure builders; [TermuxExecutor] runs them. Labels are
 * stable identifiers (the test fake keys on them) and the only thing ever logged.
 *
 * `$PREFIX` = `/data/data/com.termux/files/usr`, `$HOME` = `/data/data/com.termux/files/home`
 * (RunCommandService expands neither, [TermuxPaths]). Everything runs in the background
 * runner unless marked foreground/detached.
 *
 * Spelling notes against the sources: `cline hub status` has no `--json` flag (it always
 * prints one JSON line, `apps/cli/src/commands/hub.ts`), and the process cleanup is the
 * `doctor fix` subcommand (`apps/cli/src/commands/doctor.ts`, KS §0.5), not `--fix`.
 */
object ClineCommands {
    const val DASHBOARD_PORT = 8787
    const val LOG_TAIL_BYTES = 49_152
    const val DAEMON_LOG_TAIL_BYTES = 4_000
    const val ATTACHMENT_CHUNK_CHARS = 128 * 1024
    const val BRANCH_LIST_CAP = 500

    /** OAuth providers whose sign-in runs in a Termux terminal session (SP S7.3). */
    val OAUTH_PROVIDER_IDS: Set<String> = setOf("cline", "openai-codex", "oca")

    // ---- Kernel probe and lifecycle (KS §0.5) ------------------------------------------

    /** P3: `cat $PREFIX/opt/cline-termux/current/VERSION` → [PortVersion]. */
    fun probeVersion() = TermuxCommand.bin("cat", TermuxPaths.PORT_VERSION_FILE, label = Labels.PROBE_VERSION, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** `readlink $PREFIX/opt/cline-termux/current` → basename `X.Y.Z-termux.N` (CR §7.7). */
    fun readPortRelease() = TermuxCommand.bin("readlink", TermuxPaths.PORT_CURRENT, label = Labels.READ_PORT_RELEASE, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** `cline --version` → first line (CR §7.7). ~2.5 s on the S25U. */
    fun cliVersion() = TermuxCommand.cline("--version", label = Labels.CLI_VERSION, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** Starts the daemon if needed; exits when it is up (8-15 s cold); last stdout line is the ws URL. */
    fun hubStart() = TermuxCommand.cline("hub", "start", label = Labels.HUB_START, timeoutMs = TermuxTimeouts.HUB_START_MS)

    /** Always prints one JSON line (`running`, `url`, `pid`, `startedAt`, `uptime`, `cliVersion`, `coreVersion`). */
    fun hubStatus() = TermuxCommand.cline("hub", "status", label = Labels.HUB_STATUS, timeoutMs = TermuxTimeouts.HUB_STATUS_MS)

    /** Asks the daemon to stop; prints `{"stopped":true|false}`. */
    fun hubStop() = TermuxCommand.cline("hub", "stop", label = Labels.HUB_STOP, timeoutMs = TermuxTimeouts.HUB_STOP_MS)

    /** Drains, stops and starts a fresh daemon; prints `{"upgraded":true,"url":…}` (KS E.9). */
    fun hubUpgrade() = TermuxCommand.cline(
        "hub", "upgrade", "--wait", TermuxTimeouts.HUB_UPGRADE_WAIT_S.toString(),
        label = Labels.HUB_UPGRADE, timeoutMs = TermuxTimeouts.HUB_UPGRADE_MS,
    )

    /** Keeps TermuxService (and so the Hub, its child) alive: PARTIAL_WAKE_LOCK + WifiLock. */
    fun wakeLock() = TermuxCommand.bin("termux-wake-lock", label = Labels.WAKE_LOCK, timeoutMs = TermuxTimeouts.SHORT_MS)

    fun wakeUnlock() = TermuxCommand.bin("termux-wake-unlock", label = Labels.WAKE_UNLOCK, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** Termux-private discovery record with the auth token; parsed in the bridge, raw string discarded. */
    fun readHubDiscovery() = TermuxCommand.bin("cat", TermuxPaths.HUB_DISCOVERY, label = Labels.READ_HUB_DISCOVERY, timeoutMs = TermuxTimeouts.DISCOVERY_MS)

    /** `/proc/<pid>/cmdline` with its NUL separators made newlines: one argument per line. */
    const val HUB_CMDLINE_SCRIPT = "tr '\\0' '\\n' < \"/proc/\$1/cmdline\""

    /**
     * The daemon's argv, `<bun> $PREFIX/opt/cline-termux/<release>/entry.js --cwd …`: the release
     * tree it runs from, which `/version` does not name (trial row 24, [PortRelease.fromHubCmdline]).
     */
    fun readHubCmdline(pid: Int) = TermuxCommand(
        path = TermuxPaths.BASH,
        args = listOf("-c", HUB_CMDLINE_SCRIPT, "_", pid.toString()),
        label = Labels.READ_HUB_CMDLINE,
        timeoutMs = TermuxTimeouts.SHORT_MS,
    )

    /** Matched for `entry.js` on the way to StartFailed/StartTimedOut, then discarded (KS §0.4). */
    fun tailDaemonLog() = TermuxCommand.bin(
        "tail", "-c", DAEMON_LOG_TAIL_BYTES.toString(), TermuxPaths.HUB_DAEMON_LOG,
        label = Labels.TAIL_DAEMON_LOG, timeoutMs = TermuxTimeouts.SHORT_MS,
    )

    /** `tail -c <bytes> <path>`; post-process with [LogTail.clean] (CR §9.4). */
    fun tailLog(path: String, bytes: Int = LOG_TAIL_BYTES) = TermuxCommand.bin(
        "tail", "-c", bytes.toString(), path,
        label = Labels.TAIL_LOG, timeoutMs = TermuxTimeouts.MEDIUM_MS,
    )

    fun doctorJson() = TermuxCommand.cline("doctor", "--json", label = Labels.DOCTOR, timeoutMs = TermuxTimeouts.DOCTOR_MS)

    /** `cline doctor fix --json`: kills every stale Cline process in Termux (KS E.6). */
    fun doctorFix() = TermuxCommand.cline("doctor", "fix", "--json", label = Labels.DOCTOR_FIX, timeoutMs = TermuxTimeouts.DOCTOR_FIX_MS)

    // ---- Providers and sign-in (KS §0.5, SP §0.4, S7.3) --------------------------------

    /** Secret-free sign-in completion poll: the file's mtime in epoch seconds (exit 1 = no file yet). */
    fun statProviders() = TermuxCommand.bin("stat", "-c", "%Y", TermuxPaths.PROVIDERS_JSON, label = Labels.STAT_PROVIDERS, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** `cat providers.json`; reduce with [ProviderPresence.from] at once. Never on a timer (KS §0.5). */
    fun readProviderPresence() = TermuxCommand.bin("cat", TermuxPaths.PROVIDERS_JSON, label = Labels.READ_PROVIDER_PRESENCE, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** `cat global-settings.json`; reduce with [GlobalSettingsView.from]. */
    fun readGlobalSettings() = TermuxCommand.bin("cat", TermuxPaths.GLOBAL_SETTINGS_JSON, label = Labels.READ_GLOBAL_SETTINGS, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** Non-interactive provider setup; success = exit 0, never a stdout match (SP §0.4). */
    fun authProvider(providerId: String, apiKey: String, modelId: String, baseUrl: String? = null): TermuxCommand {
        val args = mutableListOf("auth", "--provider", providerId, "--apikey", apiKey, "--modelid", modelId)
        if (!baseUrl.isNullOrBlank()) args += listOf("--baseurl", baseUrl)
        return TermuxCommand(path = TermuxPaths.CLINE, args = args, label = authLabel(providerId), timeoutMs = TermuxTimeouts.AUTH_MS)
    }

    /** "cline auth <providerId>" without the key (SP §0.6). */
    fun authLabel(providerId: String) = "cline auth $providerId"

    /**
     * Browser sign-in (`cline auth <cline|openai-codex|oca>`) in a visible Termux session:
     * the device code/URL print there and only a foreground Termux may open the browser.
     * Run with [TermuxExecutor.runDetached], then bring Termux to the front (SP S7.3).
     */
    fun authInteractive(providerId: String = "cline") = TermuxCommand(
        path = TermuxPaths.CLINE,
        args = listOf("auth", providerId),
        background = false,
        sessionAction = 0,
        label = if (providerId == "cline") Labels.SIGN_IN_CLINE else "Sign in with $providerId",
    )

    /** Kills the Bun child of a running sign-in; exit 0 and 1 both mean "done" (SP S7.3 step 7). */
    fun cancelAuth() = TermuxCommand.bin("pkill", "-f", CANCEL_AUTH_PATTERN, label = Labels.CANCEL_AUTH, timeoutMs = TermuxTimeouts.SHORT_MS)

    const val CANCEL_AUTH_PATTERN = "index.js auth (cline|openai-codex|oca)$"

    // ---- Settings helper (SP §0.3) ------------------------------------------------------

    /** The dashboard launch line; the secret travels only in this argv and the loopback URL. Detached. */
    fun dashboardStart(roomSecret: String) = TermuxCommand(
        path = TermuxPaths.CLINE,
        args = listOf("dashboard", "--no-open", "--host", "127.0.0.1", "--port", DASHBOARD_PORT.toString(), "--room-secret", roomSecret),
        label = Labels.DASHBOARD_START,
    )

    /**
     * `pkill -f` on the app's own launch line only: the exact [roomSecret] of this process,
     * or any 32-hex secret (an earlier process of the app) when null. A dashboard the user
     * started by hand never matches.
     */
    fun dashboardStop(roomSecret: String? = null) = TermuxCommand.bin(
        "pkill", "-f", dashboardStopPattern(roomSecret),
        label = Labels.DASHBOARD_STOP, timeoutMs = TermuxTimeouts.SHORT_MS,
    )

    fun dashboardStopPattern(roomSecret: String?): String =
        "index.js dashboard --no-open --host 127.0.0.1 --port $DASHBOARD_PORT --room-secret ${roomSecret ?: "[0-9a-f]{32}"}( |$)"

    // ---- Customize, MCP, marketplace (CR §1.8, §2.8, §3.8) ------------------------------

    /** Built-in tool catalog (CLI-scoped), workdir = the workspace. */
    fun configToolsJson(workspaceRoot: String) = TermuxCommand.cline(
        "config", "tools", "--json",
        label = Labels.CONFIG_TOOLS, timeoutMs = TermuxTimeouts.CONFIG_MS, workdir = workspaceRoot,
    )

    fun configHooksJson(workspaceRoot: String) = TermuxCommand.cline(
        "config", "hooks", "--json",
        label = Labels.CONFIG_HOOKS, timeoutMs = TermuxTimeouts.CONFIG_MS, workdir = workspaceRoot,
    )

    /** Fallback MCP details; env values are redacted by the repository in memory. */
    fun readMcpSettings() = TermuxCommand.bin("cat", TermuxPaths.MCP_SETTINGS_JSON, label = Labels.READ_MCP_SETTINGS, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** `cline mcp install <args…>` as given (the caller shapes the arguments). */
    fun mcpInstall(args: List<String>, timeoutMs: Long = TermuxTimeouts.INSTALL_MS) =
        TermuxCommand.cline("mcp", "install", *args.toTypedArray(), label = Labels.MCP_INSTALL, timeoutMs = timeoutMs)

    /** stdio server: `cline mcp install <name> --yes --json -- <command> <args…>` (CR §2.8). */
    fun mcpInstallStdio(name: String, command: String, args: List<String>) =
        mcpInstall(listOf(name, "--yes", "--json", "--", command) + args)

    /**
     * Remote server: `cline mcp install <name> <url> --transport streamable-http|sse
     * [--header "Name: value"]… --yes --json` (one `--header` per pair; the CLI splits at the first ":").
     */
    fun mcpInstallRemote(name: String, url: String, transport: McpTransport, headers: Map<String, String> = emptyMap()): TermuxCommand {
        val args = mutableListOf(name, url, "--transport", transport.cliValue)
        headers.forEach { (key, value) -> args += listOf("--header", "$key: $value") }
        args += listOf("--yes", "--json")
        return mcpInstall(args)
    }

    /** Marketplace entry: `cline mcp install --yes --json <install.args…>` (CR §3.8, 300 s). */
    fun mcpInstallMarketplace(installArgs: List<String>) =
        mcpInstall(listOf("--yes", "--json") + installArgs, timeoutMs = TermuxTimeouts.MARKETPLACE_INSTALL_MS)

    fun mcpUninstall(name: String) =
        TermuxCommand.cline("mcp", "uninstall", name, "--json", label = Labels.MCP_UNINSTALL, timeoutMs = TermuxTimeouts.INSTALL_MS)

    /** `cline plugin install <source> --json` (CR §3.8). */
    fun pluginInstall(source: String) =
        TermuxCommand.cline("plugin", "install", source, "--json", label = Labels.PLUGIN_INSTALL, timeoutMs = TermuxTimeouts.MARKETPLACE_INSTALL_MS)

    /** `cline plugin uninstall <name or path> --json` (CR §1.8). */
    fun pluginUninstall(nameOrPath: String) =
        TermuxCommand.cline("plugin", "uninstall", nameOrPath, "--json", label = Labels.PLUGIN_UNINSTALL, timeoutMs = TermuxTimeouts.INSTALL_MS)

    /** The dashboard's own form: `npx -y skills@latest add <install.args…> -g -a cline -y` (CR §3.8). */
    fun skillAdd(installArgs: List<String>) = TermuxCommand(
        path = TermuxPaths.NPX,
        args = listOf("-y", "skills@latest", "add") + installArgs + listOf("-g", "-a", "cline", "-y"),
        label = Labels.SKILL_ADD,
        timeoutMs = TermuxTimeouts.MARKETPLACE_INSTALL_MS,
    )

    /** `cline skill remove <installed dir> -g -y` (CR §1.8); [installedSkillDirectory] derives the name. */
    fun skillRemove(installedDirectory: String) =
        TermuxCommand.cline("skill", "remove", installedDirectory, "-g", "-y", label = Labels.SKILL_REMOVE, timeoutMs = TermuxTimeouts.INSTALL_MS)

    /** `basename(dirname(path))` for a `…/SKILL.md` path, else `basename(path)` (CR §1.8). */
    fun installedSkillDirectory(itemPath: String): String {
        val trimmed = itemPath.trimEnd('/')
        return if (trimmed.endsWith("/SKILL.md")) {
            trimmed.removeSuffix("/SKILL.md").substringAfterLast('/')
        } else {
            trimmed.substringAfterLast('/')
        }
    }

    // ---- Git (HC §2.5, CT C3.5) ----------------------------------------------------------

    /** Current branch; reduce with [GitBranchProbe.from]. */
    fun gitBranch(workspaceRoot: String) = git(workspaceRoot, "branch", "--show-current", label = Labels.GIT_BRANCH)

    /** Detached HEAD label. */
    fun gitShortHead(workspaceRoot: String) = git(workspaceRoot, "rev-parse", "--short", "HEAD", label = Labels.GIT_SHORT_HEAD)

    /** Local branches, most recently committed first; the cap keeps the result under 100 KB. */
    fun gitBranches(workspaceRoot: String) = git(
        workspaceRoot, "for-each-ref", "--count=$BRANCH_LIST_CAP", "--sort=-committerdate", "--format=%(refname:short)", "refs/heads",
        label = Labels.GIT_BRANCHES,
    )

    fun gitCheckout(workspaceRoot: String, branch: String) =
        git(workspaceRoot, "checkout", branch, label = Labels.GIT_CHECKOUT, timeoutMs = TermuxTimeouts.GIT_CHECKOUT_MS)

    /** `err 150` → no git; exit 128 → not a repository; stdout `true` → ok (CT C3.5). */
    fun gitIsWorkTree(cwd: String) = git(cwd, "rev-parse", "--is-inside-work-tree", label = Labels.GIT_IS_WORK_TREE)

    private fun git(workspaceRoot: String, vararg args: String, label: String, timeoutMs: Long = TermuxTimeouts.SHORT_MS) =
        TermuxCommand.bin("git", "-C", workspaceRoot, *args, label = label, timeoutMs = timeoutMs)

    // ---- Workspace picker, folder browser, schedule editor (SP S9.3, CR §4.9) --------------

    /** `realpath -e <path>`: exit 0 and stdout = the canonical path. */
    fun realpath(path: String) = TermuxCommand.bin("realpath", "-e", TermuxPaths.expandHome(path), label = Labels.REALPATH, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** `find <path> -maxdepth 0 -type d`: non-empty stdout = a directory. */
    fun isDirectory(path: String) = TermuxCommand.bin("find", TermuxPaths.expandHome(path), "-maxdepth", "0", "-type", "d", label = Labels.IS_DIRECTORY, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** `test -d <path>`: exit 0 = the folder exists (schedule editor validation, CR §4.9). */
    fun testDirectory(path: String) = TermuxCommand.bin("test", "-d", TermuxPaths.expandHome(path), label = Labels.TEST_DIRECTORY, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** Direct children of [dir], one per line; reduce with [DirectoryListing.from]. */
    fun listDirectories(dir: String) = TermuxCommand.bin(
        "find", TermuxPaths.expandHome(dir), "-maxdepth", "1", "-mindepth", "1", "-type", "d", "-not", "-name", ".*",
        label = Labels.LIST_DIRECTORIES, timeoutMs = TermuxTimeouts.MEDIUM_MS,
    )

    /** `find $HOME/storage -maxdepth 0`: exit 0 = `termux-setup-storage` has run. */
    fun hasSharedStorage() = TermuxCommand.bin("find", TermuxPaths.STORAGE, "-maxdepth", "0", label = Labels.HAS_SHARED_STORAGE, timeoutMs = TermuxTimeouts.SHORT_MS)

    /** Foreground session (Termux shows its storage permission dialog); detached. */
    fun setupStorage() = TermuxCommand(
        path = "${TermuxPaths.BIN}/termux-setup-storage",
        background = false,
        sessionAction = 0,
        label = Labels.SETUP_STORAGE,
    )

    // ---- Composer: mentions, slash commands, attachments (CA C6.6, C5.6, C1.9) -------------

    /** The CA C6.6 pipeline, filtered per query so the 100 KB cap is never hit. */
    const val SEARCH_WORKSPACE_FILES_SCRIPT =
        "cd \"\$1\" && { if command -v rg >/dev/null 2>&1; then rg --files --hidden -g '!.git' 2>/dev/null; " +
            "else find . -type f -not -path './.git/*' -not -path '*/node_modules/*' -printf '%P\\n' 2>/dev/null; fi; } " +
            "| grep -i -F -- \"\$2\" | head -n 200"

    fun searchWorkspaceFiles(workspaceRoot: String, query: String) = TermuxCommand(
        path = TermuxPaths.BASH,
        args = listOf("-c", SEARCH_WORKSPACE_FILES_SCRIPT, "_", workspaceRoot, query),
        label = Labels.SEARCH_WORKSPACE_FILES,
        timeoutMs = TermuxTimeouts.SHORT_MS,
    )

    /** `cat <path>` (≤ 100 KB): skill/workflow bodies for the slash menu (CA C5.6). */
    fun readTextFile(path: String) = TermuxCommand.bin("cat", TermuxPaths.expandHome(path), label = Labels.READ_TEXT_FILE, timeoutMs = TermuxTimeouts.READ_FILE_MS)

    /** `cline history export <sessionId> -o <path>`; needs shared storage for a Downloads path. */
    fun historyExport(sessionId: String, outPath: String) =
        TermuxCommand.cline("history", "export", sessionId, "-o", outPath, label = Labels.HISTORY_EXPORT, timeoutMs = TermuxTimeouts.DEFAULT_MS)

    private const val ATTACHMENT_APPEND_SCRIPT = "umask 077 && mkdir -p \"\$1\" && cat >> \"\$1/.\$2.part\""
    private const val ATTACHMENT_FINALIZE_SCRIPT = "mv -f \"\$1/.\$2.part\" \"\$1/\$2\""

    /** The display name with `/` and NUL removed and a leading `.` replaced by `_` (CA C1.9). */
    fun sanitizeAttachmentName(name: String): String {
        val cleaned = name.replace("/", "").replace("\u0000", "").trim()
        val safe = if (cleaned.startsWith(".")) "_" + cleaned.substring(1) else cleaned
        return safe.ifEmpty { "attachment" }
    }

    /**
     * Splits [content] into stdin chunks of at most [ATTACHMENT_CHUNK_CHARS] UTF-16 units
     * without cutting a surrogate pair (a split pair would be re-encoded as U+FFFD).
     */
    fun attachmentChunks(content: String, chunkChars: Int = ATTACHMENT_CHUNK_CHARS): List<String> {
        if (content.isEmpty()) return listOf("")
        val chunks = ArrayList<String>((content.length + chunkChars - 1) / chunkChars)
        var start = 0
        while (start < content.length) {
            var end = minOf(start + chunkChars, content.length)
            if (end < content.length && Character.isHighSurrogate(content[end - 1])) end--
            chunks += content.substring(start, end)
            start = end
        }
        return chunks
    }

    /**
     * The command sequence that materialises a text attachment inside Termux (CA C1.9):
     * one append per chunk through stdin, never through arguments, then an atomic rename.
     * Returns the commands in the order they must run; each must exit 0 before the next.
     */
    fun attachmentWriteCommands(dir: String, name: String, content: String): List<TermuxCommand> {
        val safeName = sanitizeAttachmentName(name)
        val appends = attachmentChunks(content).map { chunk ->
            TermuxCommand(
                path = TermuxPaths.BASH,
                args = listOf("-c", ATTACHMENT_APPEND_SCRIPT, "_", dir, safeName),
                stdin = chunk,
                label = Labels.WRITE_ATTACHMENT_CHUNK,
                timeoutMs = TermuxTimeouts.ATTACHMENT_CHUNK_MS,
            )
        }
        val finalize = TermuxCommand(
            path = TermuxPaths.BASH,
            args = listOf("-c", ATTACHMENT_FINALIZE_SCRIPT, "_", dir, safeName),
            label = Labels.FINALIZE_ATTACHMENT,
            timeoutMs = TermuxTimeouts.ATTACHMENT_CHUNK_MS,
        )
        return appends + finalize
    }

    /** `<dir>/<sanitised name>`: the Termux path the attachment ends up at. */
    fun attachmentPath(dir: String, name: String): String = "$dir/${sanitizeAttachmentName(name)}"

    /** The stable labels; `FakeTermuxExecutor` keys its canned results on them. */
    object Labels {
        const val PROBE_VERSION = "cat VERSION"
        const val READ_PORT_RELEASE = "readlink current"
        const val CLI_VERSION = "cline --version"
        const val HUB_START = "cline hub start"
        const val HUB_STATUS = "cline hub status"
        const val HUB_STOP = "cline hub stop"
        const val HUB_UPGRADE = "cline hub upgrade"
        const val WAKE_LOCK = "termux-wake-lock"
        const val WAKE_UNLOCK = "termux-wake-unlock"
        const val READ_HUB_DISCOVERY = "cat production.json"
        const val READ_HUB_CMDLINE = "read hub argv"
        const val TAIL_DAEMON_LOG = "tail hub-daemon.log"
        const val TAIL_LOG = "tail log"
        const val DOCTOR = "cline doctor --json"
        const val DOCTOR_FIX = "cline doctor fix"
        const val STAT_PROVIDERS = "stat providers.json"
        const val READ_PROVIDER_PRESENCE = "read provider presence"
        const val READ_GLOBAL_SETTINGS = "cat global-settings.json"
        const val SIGN_IN_CLINE = "Sign in to Cline"
        const val CANCEL_AUTH = "cancel auth"
        const val DASHBOARD_START = "start settings helper"
        const val DASHBOARD_STOP = "stop settings helper"
        const val CONFIG_TOOLS = "cline config tools"
        const val CONFIG_HOOKS = "cline config hooks"
        const val READ_MCP_SETTINGS = "cat cline_mcp_settings.json"
        const val MCP_INSTALL = "cline mcp install"
        const val MCP_UNINSTALL = "cline mcp uninstall"
        const val PLUGIN_INSTALL = "cline plugin install"
        const val PLUGIN_UNINSTALL = "cline plugin uninstall"
        const val SKILL_ADD = "skill add"
        const val SKILL_REMOVE = "cline skill remove"
        const val GIT_BRANCH = "git branch"
        const val GIT_SHORT_HEAD = "git rev-parse --short HEAD"
        const val GIT_BRANCHES = "git for-each-ref"
        const val GIT_CHECKOUT = "git checkout"
        const val GIT_IS_WORK_TREE = "git rev-parse --is-inside-work-tree"
        const val REALPATH = "realpath"
        const val IS_DIRECTORY = "is directory"
        const val TEST_DIRECTORY = "test -d"
        const val LIST_DIRECTORIES = "list directories"
        const val HAS_SHARED_STORAGE = "has shared storage"
        const val SETUP_STORAGE = "termux-setup-storage"
        const val SEARCH_WORKSPACE_FILES = "search workspace files"
        const val READ_TEXT_FILE = "cat file"
        const val HISTORY_EXPORT = "cline history export"
        const val WRITE_ATTACHMENT_CHUNK = "write attachment chunk"
        const val FINALIZE_ATTACHMENT = "finalize attachment"
    }
}

/** Remote MCP transports the CLI accepts (`--transport`). */
enum class McpTransport(val cliValue: String) {
    StreamableHttp("streamable-http"),
    Sse("sse"),
}

/**
 * C1.9 steps 2–3, the composer's one attachment writer: runs the
 * [ClineCommands.attachmentWriteCommands] sequence (one stdin append per ≤ 128 Ki-char chunk,
 * then the atomic rename) strictly in order, each command with its own [timeoutMs]. Stops at
 * the first command that does not exit 0 and reports it as [AttachmentWriteFailed] (a
 * RUN_COMMAND that could not run at all is the [TermuxUnavailable] it threw). Returns the
 * Termux path of the written file.
 */
suspend fun TermuxExecutor.writeAttachment(
    dir: String,
    name: String,
    content: String,
    timeoutMs: Long = TermuxTimeouts.ATTACHMENT_CHUNK_MS,
): Result<String> {
    for (command in ClineCommands.attachmentWriteCommands(dir, name, content)) {
        val result = try {
            run(command, timeoutMs)
        } catch (e: TermuxUnavailable) {
            return Result.failure(e)
        }
        if (!result.ok) {
            return Result.failure(AttachmentWriteFailed(command.label, result.exitCode, result.err, result.lastStderrLine ?: result.errmsg))
        }
    }
    return Result.success(ClineCommands.attachmentPath(dir, name))
}

/** One step of an attachment write did not exit 0; [detail] is the stripped last stderr line. */
class AttachmentWriteFailed(val label: String, val exitCode: Int, val err: Int, val detail: String?) :
    Exception("$label failed: exit=$exitCode err=$err${detail?.let { ": $it" } ?: ""}")
