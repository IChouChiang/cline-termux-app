package dev.cline.termux.core.kernel.termux

import java.security.SecureRandom
import java.util.Random

/** A worktree [WorktreeCommands.create] made: the repository it was cut from, its path and its branch. */
data class CreatedWorktree(val repoRoot: String, val path: String, val branch: String)

/**
 * G3 "Worktree" (docs/decided.md A10, terminal-only-functions.md §3 item 3): the desktop's task
 * worktrees through Termux. Mirrors `createGitWorktree`, `isTaskWorktreePath` and
 * `removeTaskWorktree` in the desktop sidecar (`apps/examples/desktop-app/sidecar/commands.ts`)
 * and the CLI's `--worktree` layout: `~/.cline/worktrees/<id>/<repo>` on branch `cline/<id>`.
 *
 * Both scripts run as `bash -c <script> _ <args…>`, so paths and ids are positional arguments,
 * never spliced into the script. The `$` signs are the shell's.
 */
object WorktreeCommands {
    const val LABEL_CREATE = "git worktree add"
    const val LABEL_REMOVE = "git worktree remove"

    /** The desktop's `taskWorktreesRoot()` for the default Cline dir in Termux's home. */
    const val WORKTREES_ROOT = "${TermuxPaths.HOME}/.cline/worktrees"
    const val BRANCH_PREFIX = "cline/"

    /** Desktop `randomUUID().replaceAll("-", "").slice(0, 5)`: five lowercase hex characters. */
    const val ID_LENGTH = 5

    /** `rev-parse --show-toplevel` failed: "Not a git repository: <cwd>" on stderr. */
    const val EXIT_NOT_A_REPOSITORY = 2

    /** [remove] was given a path that is not exactly `<root>/<id>/<repo>`; nothing was touched. */
    const val EXIT_NOT_A_TASK_WORKTREE = 2

    /** A checkout of a large repository in Termux can take a while. */
    const val TIMEOUT_MS = TermuxTimeouts.DEFAULT_MS

    private val ID_PATTERN = Regex("[0-9a-f]{$ID_LENGTH}")

    fun newId(random: Random = SecureRandom()): String = buildString(ID_LENGTH) {
        repeat(ID_LENGTH) { append(HEX[random.nextInt(HEX.length)]) }
    }

    fun isValidId(id: String): Boolean = ID_PATTERN.matches(id)

    /** The branch a task worktree [id] checks out. */
    fun branchFor(id: String): String = BRANCH_PREFIX + id

    /**
     * `git worktree add -b cline/<id> <root>/<id>/<repo> HEAD` for the repository containing
     * [cwd]; prints the repository root and the worktree path on two stdout lines (git's own
     * output goes to stderr). A failed `worktree add` removes the empty `<id>` directory again.
     */
    fun create(cwd: String, id: String): TermuxCommand {
        require(isValidId(id)) { "worktree id must be $ID_LENGTH lowercase hex characters" }
        return TermuxCommand(
            path = TermuxPaths.BASH,
            args = listOf("-c", CREATE_SCRIPT, "_", cwd, id),
            label = LABEL_CREATE,
            timeoutMs = TIMEOUT_MS,
        )
    }

    /**
     * Best-effort removal of a task worktree (desktop `removeTaskWorktree`): the exact
     * `<root>/<id>/<repo>` shape is checked again in the script, then `worktree remove --force`,
     * `branch -D cline/<id>` in the repository (failure ignored; any other branch the task
     * switched to is kept) and the `<id>` directory. Every failed step is reported on stderr;
     * exit 0 means all of it went.
     */
    fun remove(path: String): TermuxCommand {
        val normalized = normalize(path)
        require(isTaskWorktreePath(normalized)) { "not a task worktree path" }
        return TermuxCommand(
            path = TermuxPaths.BASH,
            args = listOf("-c", REMOVE_SCRIPT, "_", normalized),
            label = LABEL_REMOVE,
            timeoutMs = TIMEOUT_MS,
        )
    }

    /**
     * True for paths of the exact `~/.cline/worktrees/<id>/<repo>` shape (desktop
     * `isTaskWorktreePath`). `.` and `..` segments never qualify, since removal deletes `<id>`.
     */
    fun isTaskWorktreePath(path: String): Boolean {
        val normalized = normalize(path)
        val prefix = "$WORKTREES_ROOT/"
        if (!normalized.startsWith(prefix)) return false
        val segments = normalized.substring(prefix.length).split('/')
        return segments.size == 2 && segments.all { it.isNotEmpty() && it != "." && it != ".." }
    }

    /** The [create] result: its two stdout lines, or null when it failed or printed something else. */
    fun parseCreated(result: TermuxResult, id: String): CreatedWorktree? {
        if (!result.ok) return null
        val lines = result.stdoutLines.map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return null
        val root = lines[lines.size - 2]
        val path = lines[lines.size - 1]
        if (!isTaskWorktreePath(path)) return null
        return CreatedWorktree(repoRoot = root, path = path, branch = branchFor(id))
    }

    /** What a failed [create] says: git's (or the script's) last stderr line, else Termux's message. */
    fun failureLine(result: TermuxResult): String =
        result.lastStderrLine ?: result.errmsg?.trim()?.takeIf { it.isNotEmpty() } ?: result.lastStdoutLine ?: "exit ${result.exitCode}"

    private fun normalize(path: String): String = path.trim().trimEnd('/')

    private const val HEX = "0123456789abcdef"

    /**
     * The [create] script for worktrees under [root] ($1 = cwd, $2 = id). `basename` of the
     * repository root names the worktree (desktop `basename(repoRoot) || "workspace"`).
     * [root] is a parameter only so the unit test can run the script against a temp directory.
     */
    internal fun createScript(root: String): String =
        "case \"\$2\" in [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;; *) echo \"Invalid worktree id: \$2\" >&2; exit 2 ;; esac\n" +
            "root=\$(git -C \"\$1\" rev-parse --show-toplevel 2>/dev/null) || { echo \"Not a git repository: \$1\" >&2; exit $EXIT_NOT_A_REPOSITORY; }\n" +
            "name=\$(basename \"\$root\")\n" +
            "case \"\$name\" in ''|/) name=workspace ;; esac\n" +
            "dir=\"$root/\$2\"\n" +
            "dest=\"\$dir/\$name\"\n" +
            "mkdir -p \"\$dir\" || exit 1\n" +
            "git -C \"\$root\" worktree add -b \"$BRANCH_PREFIX\$2\" \"\$dest\" HEAD >&2 || { rc=\$?; rmdir \"\$dir\" 2>/dev/null; exit \$rc; }\n" +
            "printf '%s\\n%s\\n' \"\$root\" \"\$dest\"\n"

    /** The [remove] script for worktrees under [root] ($1 = the path, checked again here). */
    internal fun removeScript(root: String): String =
        "p=\"\$1\"\n" +
            "rest=\"\${p#$root/}\"\n" +
            "id=\"\${rest%%/*}\"\n" +
            "name=\"\${rest#*/}\"\n" +
            "if [ \"\$rest\" = \"\$p\" ] || [ \"\$name\" = \"\$rest\" ] || [ \"\$name\" != \"\${name%%/*}\" ]; then echo \"Not a task worktree: \$p\" >&2; exit $EXIT_NOT_A_TASK_WORKTREE; fi\n" +
            "case \"\$id\" in ''|.|..) echo \"Not a task worktree: \$p\" >&2; exit $EXIT_NOT_A_TASK_WORKTREE ;; esac\n" +
            "case \"\$name\" in ''|.|..) echo \"Not a task worktree: \$p\" >&2; exit $EXIT_NOT_A_TASK_WORKTREE ;; esac\n" +
            "rc=0\n" +
            "if common=\$(git -C \"\$p\" rev-parse --path-format=absolute --git-common-dir 2>&1); then\n" +
            "  if git -C \"\$p\" worktree remove --force \"\$p\" >&2; then\n" +
            "    git -C \"\$(dirname \"\$common\")\" branch -D \"$BRANCH_PREFIX\$id\" >&2 || echo \"Branch $BRANCH_PREFIX\$id was not deleted\" >&2\n" +
            "  else\n" +
            "    echo \"Failed to remove task worktree: \$p\" >&2; rc=1\n" +
            "  fi\n" +
            "else\n" +
            "  echo \"Failed to remove task worktree: \$p: \$common\" >&2; rc=1\n" +
            "fi\n" +
            "rm -rf \"$root/\$id\" || rc=1\n" +
            "exit \$rc\n"

    private val CREATE_SCRIPT = createScript(WORKTREES_ROOT)
    private val REMOVE_SCRIPT = removeScript(WORKTREES_ROOT)
}
