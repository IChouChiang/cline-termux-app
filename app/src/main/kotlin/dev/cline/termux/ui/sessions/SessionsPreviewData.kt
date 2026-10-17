package dev.cline.termux.ui.sessions

import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.RowUsage
import dev.cline.termux.data.sessions.ScheduleInfo
import dev.cline.termux.data.sessions.ScheduleRunLabel
import dev.cline.termux.data.sessions.SessionFlags
import dev.cline.termux.data.sessions.SessionRow
import dev.cline.termux.data.sessions.SessionSummary

/** Sample rows for the sessions previews (data, not copy: titles stand in for kernel records). */
internal object SessionsPreviewData {
    const val NOW = 1_759_500_000_000L
    private const val MIN = 60_000L

    fun summary(
        id: String,
        title: String,
        minutesAgo: Long,
        status: KernelSessionStatus = KernelSessionStatus.Completed,
        pinned: Boolean = false,
        workspace: String = "/data/data/com.termux/files/home/projects/app",
        schedule: ScheduleInfo? = null,
        usage: RowUsage? = RowUsage(12_500, 3_200, 0.123),
    ) = SessionSummary(
        id = id,
        title = title,
        hasCustomTitle = true,
        workspaceRoot = workspace,
        cwd = workspace,
        kernelStatus = status,
        provider = "anthropic",
        model = "claude-sonnet-4-5",
        source = "android",
        pinned = pinned,
        schedule = schedule,
        gitBranch = "main",
        createdAt = NOW - minutesAgo * MIN - MIN,
        activityAt = NOW - minutesAgo * MIN,
        usage = usage,
        participantCount = if (status == KernelSessionStatus.Running) 1 else 0,
    )

    val rows: List<SessionRow> = listOf(
        SessionRow(summary("s1", "Fix login redirect", 5, KernelSessionStatus.Running, pinned = true), SessionFlags()),
        SessionRow(summary("s2", "Port OpenTUI 0.4.4", 3 * 24 * 60, KernelSessionStatus.Failed), SessionFlags()),
        SessionRow(summary("s3", "Refactor parser", 3 * 60), SessionFlags(openApprovalIds = setOf("a1"))),
        SessionRow(summary("s4", "Update README", 4 * 24 * 60, usage = null), SessionFlags(unread = true)),
        SessionRow(summary("s5", "Review queue", 45, KernelSessionStatus.Idle), SessionFlags(openQuestionIds = setOf("q1"))),
    )

    val pendingRow: SessionRow = SessionRow(summary("s6", "Rename me", 20), SessionFlags(pendingAction = PendingKind.Rename))

    fun group(expanded: Boolean): DrawerRow.ScheduleGroup {
        val runs = (1..4).map { n ->
            val row = SessionRow(
                summary("r$n", "Daily standup", n * 24 * 60L, schedule = ScheduleInfo("sch1", "Daily standup", n), status = if (n == 1) KernelSessionStatus.Running else KernelSessionStatus.Completed),
                SessionFlags(),
            )
            DrawerRow.Session(row, row.visual, ScheduleRunLabel.Numbered(n), "Daily standup")
        }
        return DrawerRow.ScheduleGroup("schedule:sch1", "Daily standup", running = true, unread = false, expanded = expanded, containsActive = false, runs = runs)
    }
}
