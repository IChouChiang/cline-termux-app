package dev.cline.termux.ui.chat

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import dev.cline.termux.core.kernel.termux.GitBranchProbe
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.data.chat.ChatMessage
import dev.cline.termux.data.chat.ChatStatus
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.diff.SessionDiffSummary
import dev.cline.termux.data.prefs.WorkspaceChoice
import dev.cline.termux.data.session.PendingKind
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionError
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.SessionVisual
import dev.cline.termux.data.session.SessionVisualFlags
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.sessions.firstLine
import dev.cline.termux.data.sessions.normalizeTitle
import dev.cline.termux.data.workspace.TERMUX_HOME
import dev.cline.termux.data.workspace.normalizeWorkspacePath
import dev.cline.termux.data.workspace.workspaceName
import dev.cline.termux.ui.agents.AgentsChipModel
import dev.cline.termux.data.checkpoint.RestoreKind
import dev.cline.termux.ui.chat.rows.ActionError
import dev.cline.termux.ui.chat.rows.MessageActionsState
import dev.cline.termux.ui.checkpoint.CheckpointUiState
import dev.cline.termux.ui.checkpoint.RowError
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/*
 * The chat destination's state (home-chat.md §1.2, §2 Home, §6 header; docs/07 §6.5): one
 * sealed [ChatUiState] (Home | Session) plus the pure rules that build it, so the ViewModel
 * stays a wiring class and every rule is a JVM unit test.
 */

// ---------------------------------------------------------------- Home (screen A)

/** HC §2.4 workspace chip label: "Home" with no folder chosen (A22), "~" (Termux home, Android) or the folder's basename. */
@Immutable
sealed interface WorkspaceLabel {
    /** No folder chosen ("Just chat"): the session starts in the Termux home (decided A22), so the chip reads "Home". */
    data object Default : WorkspaceLabel
    data object Home : WorkspaceLabel
    data class Name(val name: String) : WorkspaceLabel
}

/** The workspace chip: its label and the full path for the CD (null = no folder chosen). */
@Immutable
data class WorkspaceChipState(val label: WorkspaceLabel, val path: String?) {
    /**
     * No folder chosen: the first send starts in the Termux home (A22). The branch chip, the git
     * probe and the worktree apply only to a chosen folder (§2.4, §2.6).
     */
    val isDefault: Boolean get() = label == WorkspaceLabel.Default

    companion object {
        val Default = WorkspaceChipState(WorkspaceLabel.Default, null)

        /**
         * No folder → "Home" (A22; the desktop's `!workspaceRoot.trim()` → "Chat",
         * `welcome-workspace-controls.tsx:1034-1035`, a deliberate deviation); the Termux home →
         * "~" (Android); otherwise the basename. The kernel's chat workspace, if ever chosen as a
         * folder, reads as the folder it is: the app no longer sends it for "no folder".
         */
        fun of(choice: WorkspaceChoice): WorkspaceChipState {
            val path = choice.pathOrNull?.trim().orEmpty()
            if (path.isEmpty()) return Default
            if (normalizeWorkspacePath(path) == TERMUX_HOME) return WorkspaceChipState(WorkspaceLabel.Home, path)
            return WorkspaceChipState(WorkspaceLabel.Name(workspaceName(path)), path)
        }
    }
}

/** The branch chip (§2.4): shown only for a git folder workspace. [busy]: an agent runs in this workspace. */
@Immutable
data class BranchChipState(val name: String, val busy: Boolean)

/** Home's kernel notice (§2.4): the [KernelLine.homeNotice] copy, pulsing while (re)connecting. */
@Immutable
data class KernelNoticeState(val notice: HomeKernelNotice, val line: KernelLine) {
    val pulsing: Boolean get() = line == KernelLine.Connecting || line == KernelLine.Reconnecting || line == KernelLine.Draining
}

@Immutable
data class HomeState(
    val workspace: WorkspaceChipState,
    val branch: BranchChipState?,
    val kernelNotice: KernelNoticeState?,
    /** WelcomeSetupNotice (C8): no configured provider, and the kernel notice is not a down state. */
    val setupNotice: Boolean,
    /**
     * G3 "Worktree" chip after the branch chip: whether the first send cuts a task worktree
     * (`home.workInWorktree`). Null hides the chip; it shows only beside a branch chip, i.e. for
     * a git workspace (desktop WorktreeToggle in welcome-workspace-controls.tsx).
     */
    val worktree: Boolean? = null,
) {
    companion object {
        /**
         * §2.4 / C8.2: the setup notice shows only when the catalog says no provider is
         * configured ([providerConfigured] false; null = unknown, never nag) and the kernel is
         * not down (one problem at a time, the kernel comes first).
         */
        fun setupNoticeVisible(providerConfigured: Boolean?, line: KernelLine): Boolean =
            providerConfigured == false && !line.offersStartKernel && line != KernelLine.Blocked
    }
}

// ---------------------------------------------------------------- branch sheet (§2.5)

/** The Branch sheet's content (§2.5); the open flag lives in the screen. */
@Immutable
data class BranchSheetState(
    val loading: Boolean = false,
    /** Most recently committed first (`for-each-ref --sort=-committerdate`). */
    val branches: List<String> = emptyList(),
    val current: String? = null,
    val query: String = "",
    /** The row whose `git checkout` is running (all rows disabled meanwhile). */
    val switchingTo: String? = null,
    /** An agent runs in this workspace: rows disabled with the busy line (Android rule). */
    val busy: Boolean = false,
    /** Exactly [BranchRules.CAP] branches came back: the cap footer shows. */
    val capped: Boolean = false,
) {
    val visible: List<String> get() = BranchRules.filter(branches, query)
    val rowsEnabled: Boolean get() = !busy && switchingTo == null && !loading
    val empty: Boolean get() = !loading && visible.isEmpty()
}

object BranchRules {
    /** `for-each-ref --count=500` (keeps the RUN_COMMAND result under 100 KB). */
    const val CAP = 500

    /** One branch per line; blank lines dropped. */
    fun parse(result: TermuxResult): List<String> = result.stdout.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

    /** Case-insensitive substring filter on the typed query (desktop combobox). */
    fun filter(branches: List<String>, query: String): List<String> {
        val needle = query.trim()
        if (needle.isEmpty()) return branches
        return branches.filter { it.contains(needle, ignoreCase = true) }
    }

    /** The chip's label from `branch --show-current`: the name, null for detached (then `rev-parse --short HEAD`), hidden otherwise. */
    sealed interface Probe {
        data class Named(val name: String) : Probe
        data object Detached : Probe
        data object Hidden : Probe
    }

    fun probe(result: GitBranchProbe): Probe = when (result) {
        is GitBranchProbe.Branch -> Probe.Named(result.name)
        GitBranchProbe.Detached -> Probe.Detached
        GitBranchProbe.NotARepository, GitBranchProbe.GitMissing, is GitBranchProbe.Failed -> Probe.Hidden
    }

    /** The first stderr line (or Termux's errmsg) for "Couldn't switch to …: <line>". */
    fun failureLine(result: TermuxResult): String =
        result.stderr.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: result.errmsg?.trim()?.takeIf { it.isNotEmpty() }
            ?: result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            ?: ""
}

// ---------------------------------------------------------------- header (screen E)

/** A header command in flight (its pending label, every item disabled meanwhile). */
enum class HeaderAction {
    Rename, Pin, Fork, Delete, Export;

    /** The status dot's "action pending" state (DS §1.6), for rename / fork / delete. */
    val pendingKind: PendingKind?
        get() = when (this) {
            Rename -> PendingKind.Rename
            Fork -> PendingKind.Fork
            Delete -> PendingKind.Delete
            Pin, Export -> null
        }
}

/**
 * The subagent back chip's target (§6.4). [title] is the parent's own title (first line), or
 * null when unknown or when the data layer only has its English "Session <last6>" id fallback;
 * [untitled] marks that fallback, which the header renders from resources (DS §8.2.1).
 */
@Immutable
data class ParentLink(val sessionId: String, val title: String?, val untitled: Boolean = false) {
    companion object {
        /** Builds the link from the drawer's summary of [parentId] (null when the drawer does not list it). */
        fun of(parentId: String, summary: dev.cline.termux.data.sessions.SessionSummary?): ParentLink {
            if (summary == null) return ParentLink(parentId, null)
            if (!summary.hasCustomTitle && dev.cline.termux.ui.sessions.SessionsLabels.isIdFallback(summary)) {
                return ParentLink(parentId, null, untitled = true)
            }
            return ParentLink(parentId, summary.title.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

@Immutable
data class HeaderState(
    /** Null while the first `session.create` is in flight (Rename/Pin/Fork/Delete disabled). */
    val sessionId: String?,
    /** Null → "New Session". */
    val title: String?,
    val visual: SessionVisual,
    val agents: AgentsChipModel,
    /** Null or zero additions → no diff chip (desktop rule). */
    val diff: SessionDiffSummary?,
    val parent: ParentLink?,
    val pinned: Boolean,
    val busy: Boolean,
    val pending: HeaderAction?,
    /** Kernel connected: command actions enabled (§6.8 "Kernel down / offline"). */
    val kernelReady: Boolean,
    /** `session_not_found`: no actions except New session (§6.8). */
    val missing: Boolean,
) {
    val diffVisible: Boolean get() = diff != null && diff.additions != 0

    /** Rename / Pin / Delete / Export / Restore checkpoint. */
    val commandsEnabled: Boolean get() = sessionId != null && kernelReady && pending == null && !missing

    /** Fork is additionally disabled while busy (desktop `forkDisabled`). */
    val forkEnabled: Boolean get() = commandsEnabled && !busy

    companion object {
        /** §6.4 title: the custom title, else the record's title, else the first user message's first line. */
        fun titleOf(state: SessionState): String? {
            val facts = state.facts
            val custom = (facts.metadata["title"] as? JsonPrimitive)?.contentOrNull
            listOfNotNull(custom, facts.title).forEach { raw -> firstLine(raw).takeIf { it.isNotEmpty() }?.let { return it } }
            val prompt = (facts.metadata["prompt"] as? JsonPrimitive)?.contentOrNull
            firstLine(prompt).takeIf { it.isNotEmpty() }?.let { return it }
            val firstUser = state.messages.firstOrNull { it.role == Role.User } ?: return null
            return firstLine(UserInputFormat.formatDisplayUserInput(firstUser.content)).takeIf { it.isNotEmpty() }
        }

        /** The first line of the typed prompt (§2.7 step 2), for the brand-new session's header. */
        fun titleOfPrompt(prompt: String): String? = firstLine(normalizeTitle(prompt)).takeIf { it.isNotEmpty() }

        /** DS §1.6 through `SessionVisual.of` only. */
        fun visualOf(state: SessionState, pending: HeaderAction?): SessionVisual = visualOf(state.chatStatus, state.facts, state.creating, pending)

        fun visualOf(status: ChatStatus, facts: SessionFacts?, creating: Boolean, pending: HeaderAction?): SessionVisual =
            SessionVisual.of(
                status,
                SessionVisualFlags(
                    pendingAction = pending?.pendingKind,
                    approvalRequired = facts?.approvals?.isNotEmpty() == true,
                    questionAsked = facts?.questions?.isNotEmpty() == true,
                    starting = creating,
                ),
            )
    }
}

/**
 * §6.3 width rule (compact): the title keeps at least [titleMinPx]. The subagent back chip
 * is measured in the same slot (it replaces New session, §6.4): if the title would get less,
 * the back chip first drops its label and becomes the icon-only button (same CD and tooltip);
 * if still less, the agents chip moves into the overflow; if still less, the diff chip moves
 * too. New session and the overflow never move (they are outside the measured slot).
 */
object HeaderWidthRule {
    @Immutable
    data class Placement(val agentsInBar: Boolean, val diffInBar: Boolean, val parentLabel: Boolean = true) {
        companion object {
            val All = Placement(agentsInBar = true, diffInBar = true)
        }
    }

    /**
     * [agentsPx] / [diffPx] are null when that chip is not shown at all; [gapPx] separates the
     * slot's children. [parentPx] is the labelled back chip and [parentCompactPx] its icon-only
     * form, both null on a top-level session.
     */
    fun place(
        availablePx: Int,
        titleMinPx: Int,
        agentsPx: Int?,
        diffPx: Int?,
        gapPx: Int,
        parentPx: Int? = null,
        parentCompactPx: Int? = null,
    ): Placement {
        val hasParent = parentPx != null
        val candidates = buildList {
            add(Placement.All)
            if (hasParent) add(Placement(agentsInBar = true, diffInBar = true, parentLabel = false))
            add(Placement(agentsInBar = false, diffInBar = true, parentLabel = !hasParent))
        }
        candidates.forEach { placement ->
            if (availablePx - reservedPx(placement, agentsPx, diffPx, gapPx, parentPx, parentCompactPx) >= titleMinPx) return placement
        }
        return Placement(agentsInBar = false, diffInBar = false, parentLabel = !hasParent)
    }

    /** The slot width [placement] leaves to the chips and the back chip (each with its gap); the title gets the rest. */
    fun reservedPx(placement: Placement, agentsPx: Int?, diffPx: Int?, gapPx: Int, parentPx: Int? = null, parentCompactPx: Int? = null): Int {
        val agents = if (placement.agentsInBar && agentsPx != null) agentsPx + gapPx else 0
        val diff = if (placement.diffInBar && diffPx != null) diffPx + gapPx else 0
        val parent = when {
            parentPx == null -> 0
            placement.parentLabel -> parentPx + gapPx
            else -> (parentCompactPx ?: parentPx) + gapPx
        }
        return agents + diff + parent
    }
}

// ---------------------------------------------------------------- docked stack (DS §5.1, CA C1.4)

/**
 * The docked stack's height-compact rules (DS §5.1 "the transcript remains the primary
 * content"): the queue strip is dropped while the IME is open (C1.4 step 6) or while an
 * approval / question banner already docks (it leads to the same action), and the stack is
 * bounded ([maxHeight]) and scrolls inside the bound, starting at the composer, with the IME
 * closed and, while a banner docks, with the IME open too.
 */
object DockRules {
    /** The share of the chat pane the docked stack may take in a height-compact window with the IME closed. */
    const val MAX_PANE_FRACTION = 0.6f

    /**
     * The docked stack's height bound in the chat pane of [paneHeight], or [Dp.Unspecified] when
     * it is not bounded (every window that is not height-compact, and the IME-open overflow of
     * C1.4 step 6 while no banner docks). Without a banner it is [MAX_PANE_FRACTION] of the pane,
     * which keeps transcript rows visible. With an approval, question or stall banner docked it
     * is the whole pane: the banner is what the user is answering, so it outranks transcript rows
     * (lead decision 2026-10-06, docs/07 D18), and the bound keeps it from drawing under the app
     * bar. Under the 60 % bound (≈ 173dp on the S25U) the banner + composer stack (≈ 197dp,
     * ≈ 221dp once the field has focus and its second line) overflowed, and the reverse scroll
     * that keeps the composer in view pushed the banner off the top.
     *
     * The same holds with the IME open (trial row 13): the app bar steps aside while typing, so an
     * unbounded stack that outgrew the room above the keyboard drew its banner under the status
     * bar, as the stall notice did. Bounded, whatever still does not fit scrolls inside the stack
     * and stays inside the safe area; the composer stays in view.
     */
    fun maxHeight(paneHeight: Dp, heightCompact: Boolean, imeOpen: Boolean, bannerVisible: Boolean): Dp = when {
        !bounded(heightCompact, imeOpen, bannerVisible) -> Dp.Unspecified
        bannerVisible -> paneHeight
        else -> paneHeight * MAX_PANE_FRACTION
    }

    fun queueStripShown(stripVisible: Boolean, heightCompact: Boolean, imeOpen: Boolean, bannerVisible: Boolean): Boolean =
        stripVisible && !(heightCompact && (imeOpen || bannerVisible))

    /** Whether the stack is bounded: height-compact, except the IME-open overflow of C1.4 step 6 while no banner docks. */
    fun bounded(heightCompact: Boolean, imeOpen: Boolean, bannerVisible: Boolean): Boolean = heightCompact && (!imeOpen || bannerVisible)
}

// ---------------------------------------------------------------- first send (§2.7)

/**
 * The Home → thread hand-off while `session.create` runs: the optimistic `local:<uuid>` bubble
 * with the typed text, Starting, "Thinking...". [sessionId] is set once the reply lands; the
 * store's own `local:<requestId>` bubble replaces this one when the send goes out. [error] is
 * the failed create's kernel text ("Couldn't start the session: <message>" + Retry).
 */
@Immutable
data class FirstSend(
    val localId: String,
    val text: String,
    val createdAt: Long,
    val sessionId: String? = null,
    val error: String? = null,
) {
    /** The synthetic state the transcript renders until the real session has rows. */
    fun syntheticState(key: String): SessionState = SessionState(
        sessionId = key,
        canonical = if (text.isBlank()) emptyList() else listOf(ChatMessage(id = localId, sessionId = key, role = Role.User, content = text, createdAt = createdAt)),
        seed = SeedState.Seeded(0),
        creating = error == null,
    )

    val hostError: SessionError? get() = error?.let { SessionError(SessionError.Kind.Start, it) }

    companion object {
        /** The saveable-state key of the transcript before the session id exists. */
        const val PENDING_KEY = "pending"

        /** Show the synthetic state until the created session's store state has rows. */
        fun showsSynthetic(firstSend: FirstSend?, created: SessionState?): Boolean =
            firstSend != null && (firstSend.sessionId == null || created == null || created.messages.isEmpty())
    }
}

// ---------------------------------------------------------------- the destination's state

/** docs/07 §6.5 `ChatUiState`: Home (screen A) or a session (screens B + E). */
@Immutable
sealed interface ChatUiState {
    val kernel: KernelLine

    @Immutable
    data class Home(override val kernel: KernelLine, val home: HomeState) : ChatUiState

    @Immutable
    data class Session(
        override val kernel: KernelLine,
        /** Null while the first send's `session.create` is in flight. */
        val sessionId: String?,
        val header: HeaderState,
        val transcript: TranscriptUiState,
        /** A pushed subagent entry (`ChildChat`): back pops instead of selecting Home. */
        val isChild: Boolean,
    ) : ChatUiState

    companion object {
        val Initial: ChatUiState = Home(
            kernel = KernelLine.Connecting,
            home = HomeState(WorkspaceChipState.Default, branch = null, kernelNotice = null, setupNotice = false),
        )
    }
}

/** One-shot effects for the screen (navigation, snackbars, dialogs). */
sealed interface ChatEvent {
    /** A fork (the three-dot overflow, A16) or checkpoint result: select it (pop to the chat root). */
    data class OpenSession(val sessionId: String) : ChatEvent

    /** After delete: Home (HC §6.9). */
    data object OpenHome : ChatEvent

    /** The parent of a subagent session (§6.4 back chip). */
    data class OpenParent(val parentId: String) : ChatEvent

    data class RenameFailed(val message: String) : ChatEvent
    data class PinFailed(val pinned: Boolean) : ChatEvent
    data class ForkFailed(val message: String) : ChatEvent
    data class DeleteFailed(val message: String) : ChatEvent
    data class ExportSaved(val fileName: String) : ChatEvent
    data class ExportFailed(val message: String) : ChatEvent
    data object ExportNeedsStorage : ChatEvent
    data class BranchSwitchFailed(val branch: String, val detail: String) : ChatEvent
    data object BranchSwitched : ChatEvent

    /** Credential action of an error row or banner (§3.4.7): Settings > Account or Providers. */
    data class Credential(val action: dev.cline.termux.data.chat.CredentialAction) : ChatEvent

    /** The header menu's pick did not reach `global-settings.json` (decided A1): "Couldn't turn on/off Auto-approve all: <reason>". */
    data class ApprovalModeFailed(val turningOn: Boolean, val failure: dev.cline.termux.data.approvals.ApprovalWriteFailure) : ChatEvent

    /** A re-read found the terminal's change (auto-approve-control.md §6): "Auto-approve all turned on/off outside the app". */
    data class ApprovalModeChangedOutside(val on: Boolean) : ChatEvent
}

/** Where `cline history export` writes (HC §6.6, FM "Session export"). */
object ExportPaths {
    fun fileName(sessionId: String): String = "cline-$sessionId.html"

    fun outPath(sessionId: String): String = "${TermuxPaths.STORAGE}/downloads/${fileName(sessionId)}"
}

/**
 * The checkpoint ViewModel's row state as the transcript's action state (composer-approvals.md
 * C.3): which row's restore / edit is pending, the per-row errors, the versioning lock and the
 * row that waits for a Hub drain (CT §1.6). The only mapping from [CheckpointUiState] to rows.
 */
object CheckpointRowActions {
    /** [errorText] resolves a row error to its C.7 sentence (`rowErrorText`); the default keeps the kernel text. */
    fun of(state: CheckpointUiState, errorText: (RowError) -> String? = { it.message }): MessageActionsState {
        val pending = state.pending?.takeIf { !it.pickerMode }
        val errors = state.errors.filterValues { !it.pickerMode }
        return MessageActionsState(
            versioningPending = state.versioningPending || state.busy || state.sourceGone,
            editPendingId = pending?.takeIf { it.kind == RestoreKind.Edit }?.messageId,
            editErrors = errors.filterValues { it.kind == RestoreKind.Edit }.mapValues { ActionError(errorText(it.value)) },
            restorePendingId = pending?.takeIf { it.kind == RestoreKind.Revert }?.messageId,
            restoreErrors = errors.filterValues { it.kind == RestoreKind.Revert }.mapValues { ActionError(errorText(it.value)) },
            drainingMessageId = pending?.takeIf { it.draining }?.messageId,
        )
    }
}
