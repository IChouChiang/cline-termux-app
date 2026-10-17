package dev.cline.termux.core.kernel.termux

import dev.cline.termux.data.workspace.WorkspacePathRules
import java.io.File
import java.util.Random
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** G3: the desktop sidecar's task worktree commands (commands.ts:905-985) through Termux. */
class WorktreeCommandsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = WorktreeCommands.WORKTREES_ROOT

    @Test
    fun `create runs bash with the cwd and id as positional arguments`() {
        val command = WorktreeCommands.create("/data/data/com.termux/files/home/my repo", "5e0b3")
        assertEquals(TermuxPaths.BASH, command.path)
        assertEquals(WorktreeCommands.LABEL_CREATE, command.label)
        assertEquals("git worktree add", command.label)
        assertEquals(WorktreeCommands.TIMEOUT_MS, command.timeoutMs)
        assertEquals("-c", command.args[0])
        assertEquals(listOf("_", "/data/data/com.termux/files/home/my repo", "5e0b3"), command.args.drop(2))
        // Paths never enter the script text.
        assertFalse(command.args[1].contains("my repo"))
        assertTrue(command.args[1].contains("worktree add -b \"cline/\$2\""))
        assertTrue(command.args[1].contains(root))
    }

    @Test
    fun `remove runs bash with the normalized path`() {
        val command = WorktreeCommands.remove("$root/5e0b3/repo/")
        assertEquals(TermuxPaths.BASH, command.path)
        assertEquals(WorktreeCommands.LABEL_REMOVE, command.label)
        assertEquals("git worktree remove", command.label)
        assertEquals("-c", command.args[0])
        assertEquals(listOf("_", "$root/5e0b3/repo"), command.args.drop(2))
        assertTrue(command.args[1].contains("worktree remove --force"))
        assertTrue(command.args[1].contains("branch -D \"cline/\$id\""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `remove refuses anything but a task worktree`() {
        WorktreeCommands.remove("$root/5e0b3")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `create refuses a malformed id`() {
        WorktreeCommands.create("/w", "5E0B3")
    }

    @Test
    fun `task worktree paths are exactly root, id and repo`() {
        assertEquals("/data/data/com.termux/files/home/.cline/worktrees", root)
        assertTrue(WorktreeCommands.isTaskWorktreePath("$root/5e0b3/repo"))
        assertTrue(WorktreeCommands.isTaskWorktreePath("$root/5e0b3/repo/"))
        assertFalse(WorktreeCommands.isTaskWorktreePath("$root/5e0b3"))
        assertFalse(WorktreeCommands.isTaskWorktreePath("$root/5e0b3/repo/sub"))
        assertFalse(WorktreeCommands.isTaskWorktreePath(root))
        assertFalse(WorktreeCommands.isTaskWorktreePath("$root/../x/repo"))
        assertFalse(WorktreeCommands.isTaskWorktreePath("$root/5e0b3/.."))
        assertFalse(WorktreeCommands.isTaskWorktreePath("$root//repo"))
        assertFalse(WorktreeCommands.isTaskWorktreePath("/data/data/com.termux/files/home/repo"))
        assertFalse(WorktreeCommands.isTaskWorktreePath("/home/u/.cline/worktrees/5e0b3/repo"))
        assertFalse(WorktreeCommands.isTaskWorktreePath("${root}x/5e0b3/repo"))
        // Agrees with the workspace catalog's own rule for the same root.
        val rules = WorkspacePathRules(taskWorktreeRoot = root)
        for (p in listOf("$root/5e0b3/repo", "$root/5e0b3", "$root/5e0b3/repo/sub", "/w")) {
            assertEquals(p, rules.isTaskWorktreePath(p), WorktreeCommands.isTaskWorktreePath(p))
        }
        // A worktree never becomes a remembered Home workspace (anything inside `.cline` is excluded).
        assertTrue(WorkspacePathRules.Termux.isExcludedWorkspacePath("$root/5e0b3/repo"))
    }

    @Test
    fun `ids are five lowercase hex characters`() {
        val random = Random(7)
        repeat(200) {
            val id = WorktreeCommands.newId(random)
            assertEquals(WorktreeCommands.ID_LENGTH, id.length)
            assertTrue(id, Regex("[0-9a-f]{5}").matches(id))
            assertTrue(WorktreeCommands.isValidId(id))
        }
        assertTrue(WorktreeCommands.isValidId(WorktreeCommands.newId()))
        assertFalse(WorktreeCommands.isValidId("5e0b"))
        assertFalse(WorktreeCommands.isValidId("5e0b3a"))
        assertFalse(WorktreeCommands.isValidId("5e0g3"))
        assertEquals("cline/5e0b3", WorktreeCommands.branchFor("5e0b3"))
    }

    @Test
    fun `the create result is parsed from its last two stdout lines`() {
        val ok = TermuxResult("/data/data/com.termux/files/home/repo\n$root/5e0b3/repo\n", "Preparing worktree\n", 0, -1, null)
        assertEquals(
            CreatedWorktree("/data/data/com.termux/files/home/repo", "$root/5e0b3/repo", "cline/5e0b3"),
            WorktreeCommands.parseCreated(ok, "5e0b3"),
        )
        assertNull(WorktreeCommands.parseCreated(ok.copy(exitCode = 128), "5e0b3"))
        assertNull(WorktreeCommands.parseCreated(TermuxResult("/x\n/y\n", "", 0, -1, null), "5e0b3"))
        val failed = TermuxResult("", "Not a git repository: /w\n", 2, -1, null)
        assertEquals("Not a git repository: /w", WorktreeCommands.failureLine(failed))
        assertEquals("not allowed", WorktreeCommands.failureLine(TermuxResult("", "", 0, 2, "not allowed")))
        assertEquals("exit 1", WorktreeCommands.failureLine(TermuxResult("", "", 1, -1, null)))
    }

    // ---- the scripts themselves, run with the workstation's bash and git ------------------------

    private fun hasTools(): Boolean = runCatching {
        listOf("bash", "git").all { tool ->
            val process = ProcessBuilder(tool, "--version").redirectErrorStream(true).start()
            process.inputStream.readBytes()
            process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0
        }
    }.getOrDefault(false)

    private data class Run(val exit: Int, val stdout: String, val stderr: String)

    /** One process with a scratch HOME and no user or system git config. */
    private fun run(vararg args: String): Run {
        val builder = ProcessBuilder(*args)
        val env = builder.environment()
        env["HOME"] = tmp.root.absolutePath
        // Never discover a repository above the scratch folder (the test JVM's tmp dir may sit
        // inside this checkout): "outside a repository" must really be outside one.
        env["GIT_CEILING_DIRECTORIES"] = "${tmp.root.absolutePath}:${tmp.root.canonicalPath}"
        env["GIT_CONFIG_NOSYSTEM"] = "1"
        env["GIT_CONFIG_GLOBAL"] = "/dev/null"
        env["GIT_AUTHOR_NAME"] = "t"
        env["GIT_AUTHOR_EMAIL"] = "t@example.invalid"
        env["GIT_COMMITTER_NAME"] = "t"
        env["GIT_COMMITTER_EMAIL"] = "t@example.invalid"
        val errFile = File.createTempFile("stderr", ".txt", tmp.root)
        builder.redirectError(errFile)
        val process = builder.start()
        val out = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS))
        return Run(process.exitValue(), out, errFile.readText().also { errFile.delete() })
    }

    private fun createIn(worktrees: String, cwd: String, id: String) = run("bash", "-c", WorktreeCommands.createScript(worktrees), "_", cwd, id)

    private fun removeIn(worktrees: String, path: String) = run("bash", "-c", WorktreeCommands.removeScript(worktrees), "_", path)

    private fun repo(name: String): File {
        val dir = tmp.newFolder(name)
        assertEquals(0, run("git", "init", "-q", "-b", "main", dir.absolutePath).exit)
        File(dir, "hello.js").writeText("console.log('hi')\n")
        assertEquals(0, run("git", "-C", dir.absolutePath, "add", ".").exit)
        assertEquals(0, run("git", "-C", dir.absolutePath, "commit", "-q", "-m", "init").exit)
        return dir
    }

    @Test
    fun `the create and remove scripts cut and drop a worktree and its branch`() {
        assumeTrue(hasTools())
        val worktrees = File(tmp.root, "worktrees").absolutePath
        val repo = repo("my repo")
        val sub = File(repo, "src").apply { mkdirs() }

        val created = createIn(worktrees, sub.absolutePath, "5e0b3")
        assertEquals(created.stderr, 0, created.exit)
        val dest = "$worktrees/5e0b3/my repo"
        assertEquals(listOf(repo.canonicalPath, dest), created.stdout.lines().filter { it.isNotEmpty() })
        assertTrue(File(dest, "hello.js").isFile)
        assertEquals("cline/5e0b3", run("git", "-C", dest, "branch", "--show-current").stdout.trim())

        // A second create with the same id fails (the branch exists) and leaves the first alone.
        val again = createIn(worktrees, repo.absolutePath, "5e0b3")
        assertTrue(again.exit != 0)
        assertTrue(File(dest, "hello.js").isFile)

        File(dest, "scratch.txt").writeText("uncommitted\n")
        val removed = removeIn(worktrees, dest)
        assertEquals(removed.stderr, 0, removed.exit)
        assertFalse(File("$worktrees/5e0b3").exists())
        assertEquals("", run("git", "-C", repo.absolutePath, "branch", "--list", "cline/5e0b3").stdout.trim())
        assertFalse(run("git", "-C", repo.absolutePath, "worktree", "list").stdout.contains("5e0b3"))
    }

    @Test
    fun `create outside a repository fails with the desktop's message and leaves nothing`() {
        assumeTrue(hasTools())
        val worktrees = File(tmp.root, "worktrees").absolutePath
        val plain = tmp.newFolder("plain")
        val result = createIn(worktrees, plain.absolutePath, "abcde")
        assertEquals(WorktreeCommands.EXIT_NOT_A_REPOSITORY, result.exit)
        assertEquals("Not a git repository: ${plain.absolutePath}", result.stderr.trim())
        assertFalse(File("$worktrees/abcde").exists())
        assertEquals(2, createIn(worktrees, plain.absolutePath, "../..").exit)
    }

    @Test
    fun `remove checks the shape again and touches nothing else`() {
        assumeTrue(hasTools())
        val worktrees = File(tmp.root, "worktrees").absolutePath
        val keep = File("$worktrees/5e0b3/repo/sub").apply { mkdirs() }
        for (path in listOf("$worktrees/5e0b3", "$worktrees/5e0b3/repo/sub", "$worktrees/../x/repo", "$worktrees/./repo", "/elsewhere/5e0b3/repo")) {
            assertEquals(path, WorktreeCommands.EXIT_NOT_A_TASK_WORKTREE, removeIn(worktrees, path).exit)
        }
        assertTrue(keep.isDirectory)
        // Not a worktree (no git there): reported, and the `<id>` directory still goes, like the desktop.
        val result = removeIn(worktrees, "$worktrees/5e0b3/repo")
        assertEquals(1, result.exit)
        assertTrue(result.stderr, result.stderr.contains("Failed to remove task worktree"))
        assertFalse(File("$worktrees/5e0b3").exists())
    }
}
