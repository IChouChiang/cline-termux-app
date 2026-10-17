package dev.cline.termux.ui.sessions

import dev.cline.termux.data.sessions.ActionOutcome
import dev.cline.termux.data.sessions.KernelSessionStatus
import dev.cline.termux.data.sessions.ListLoad
import dev.cline.termux.data.sessions.ListSource
import dev.cline.termux.data.sessions.RowUsage
import dev.cline.termux.data.sessions.ScheduleInfo
import dev.cline.termux.data.sessions.SessionFlags
import dev.cline.termux.data.sessions.SessionRow
import dev.cline.termux.data.sessions.SessionSummary
import dev.cline.termux.data.sessions.SessionsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A scripted [SessionsPort]: the snapshot is settable and every call is recorded in [calls]. */
class FakeSessionsPort : SessionsPort {
    val state = MutableStateFlow(SessionsSnapshot())
    override val snapshot get() = state

    val calls = mutableListOf<String>()
    val usageRequests = mutableListOf<List<String>>()
    val searches = mutableListOf<String>()

    var onLoadOlder: suspend () -> Boolean = { true }
    var onLoadAll: suspend () -> Boolean = { true }
    var onRefresh: suspend () -> Boolean = { true }
    var renameOutcome: ActionOutcome = ActionOutcome.Done
    var pinOutcome: ActionOutcome = ActionOutcome.Done
    var forkOutcome: ActionOutcome = ActionOutcome.Forked("fork-1")
    var deleteOutcome: ActionOutcome = ActionOutcome.Done
    var usage: RowUsage? = null
    var onSearch: suspend (String, Int) -> JsonObject = { _, _ -> buildJsonObject { put("hits", JsonArray(emptyList())) } }

    override suspend fun onDrawerOpened() {
        calls += "drawerOpened"
    }

    override suspend fun refresh(): Boolean {
        calls += "refresh"
        return onRefresh()
    }

    override suspend fun loadOlder(): Boolean {
        calls += "loadOlder"
        return onLoadOlder()
    }

    override suspend fun loadAll(): Boolean {
        calls += "loadAll"
        return onLoadAll()
    }

    override suspend fun requestUsage(visibleIds: List<String>) {
        usageRequests += visibleIds
    }

    override suspend fun markSeen(sessionId: String) {
        calls += "markSeen:$sessionId"
    }

    override suspend fun rename(sessionId: String, input: String): ActionOutcome {
        calls += "rename:$sessionId:$input"
        return renameOutcome
    }

    override suspend fun setPinned(sessionId: String, pinned: Boolean): ActionOutcome {
        calls += "pin:$sessionId:$pinned"
        return pinOutcome
    }

    override suspend fun fork(sessionId: String): ActionOutcome {
        calls += "fork:$sessionId"
        return forkOutcome
    }

    override suspend fun delete(sessionId: String): ActionOutcome {
        calls += "delete:$sessionId"
        return deleteOutcome
    }

    override suspend fun liveUsage(sessionId: String): RowUsage? {
        calls += "liveUsage:$sessionId"
        return usage
    }

    override suspend fun search(query: String, limit: Int): JsonObject {
        searches += query
        return onSearch(query, limit)
    }

    fun loaded(rows: List<SessionRow>, mayHaveMore: Boolean = false, connected: Boolean = true) {
        state.value = SessionsSnapshot(rows = rows, source = ListSource.Live, load = ListLoad.Loaded, connected = connected, mayHaveMore = mayHaveMore)
    }
}

/** Row builders for the sessions UI tests. */
object Rows {
    fun summary(
        id: String,
        activityAt: Long = 1_000,
        pinned: Boolean = false,
        status: KernelSessionStatus = KernelSessionStatus.Completed,
        source: String = "android",
        workspace: String = "/w/app",
        schedule: ScheduleInfo? = null,
        title: String = "Title $id",
        provider: String = "anthropic",
        model: String = "claude",
        usage: RowUsage? = null,
        participants: Int = 0,
    ) = SessionSummary(
        id = id,
        title = title,
        hasCustomTitle = true,
        workspaceRoot = workspace,
        cwd = workspace,
        kernelStatus = status,
        provider = provider,
        model = model,
        source = source,
        pinned = pinned,
        schedule = schedule,
        createdAt = activityAt - 10,
        activityAt = activityAt,
        usage = usage,
        participantCount = participants,
    )

    fun row(id: String, activityAt: Long = 1_000, flags: SessionFlags = SessionFlags(), build: (SessionSummary) -> SessionSummary = { it }) =
        SessionRow(build(summary(id, activityAt)), flags)

    /** [count] task rows, newest first. */
    fun tasks(count: Int, prefix: String = "t"): List<SessionRow> = (0 until count).map { row("$prefix$it", activityAt = 100_000L - it) }
}
