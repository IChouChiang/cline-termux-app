package dev.cline.termux.ui.checkpoint

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.data.chat.CheckpointRef
import dev.cline.termux.data.chat.Role
import dev.cline.termux.data.checkpoint.CheckpointActions
import dev.cline.termux.data.checkpoint.CheckpointHub
import dev.cline.termux.data.checkpoint.CheckpointIndex
import dev.cline.termux.data.checkpoint.GitDiagnosis
import dev.cline.termux.data.checkpoint.HubCheckpointHub
import dev.cline.termux.data.checkpoint.PendingRestore
import dev.cline.termux.data.checkpoint.RestoreFailure
import dev.cline.termux.data.checkpoint.RestoreKind
import dev.cline.termux.data.checkpoint.RestoreOutcome
import dev.cline.termux.data.checkpoint.RestoreProgress
import dev.cline.termux.data.checkpoint.RestoreRequest
import dev.cline.termux.data.checkpoint.RunMessage
import dev.cline.termux.data.checkpoint.displayText
import dev.cline.termux.data.checkpoint.findCheckpointForRun
import dev.cline.termux.data.checkpoint.readCheckpointHistory
import dev.cline.termux.data.checkpoint.truncatePreview
import dev.cline.termux.data.session.CheckpointEntry
import dev.cline.termux.data.session.SeedState
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.session.obj
import dev.cline.termux.data.session.str
import dev.cline.termux.data.tools.SessionStateSource
import dev.cline.termux.data.tools.collapseWhitespace
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Checkpoint dialogs C1/C2, the C3 picker and the message-row states
 * (design/screens/changes-tools-checkpoints.md C, docs/07 §6.5 and §13). The ViewModel is
 * scoped to the chat route; the chat screen binds the selected session and its own busy
 * flags, and hosts [CheckpointHost]. Restores run through [CheckpointActions]; with
 * `MIN_PORT_RELEASE_RESTORE == null` that is always the split path (CT §1.5.1). The
 * pending request and the dialog live in [SavedStateHandle] so they survive rotation and
 * process death; message text never does (docs/07 §6.5: transcripts are not saved).
 */

/** Everything the ViewModel reads; production wiring in [CheckpointDeps.of]. */
class CheckpointDeps(
    val actions: CheckpointActions,
    /** `session.get` / `session.messages` for the picker and the edit prefill. */
    val hub: CheckpointHub,
    val sessions: SessionStateSource,
    val gate: StateFlow<KernelGate>,
    /** The kernel's global auto-approve for the restored session (`ApprovalModeRepository`, decided A1); [sessionId] is the source. */
    val autoApprove: suspend (sessionId: String) -> Boolean,
    val clock: Clock,
    /** Logs a failure reason (`restore_invalid`, `restore_run_unmapped`, `restore_no_cwd`), never payloads. */
    val log: (reason: String) -> Unit = { Log.w(LOG_TAG, it) },
    /** `sessionConfig.compaction` of the restored session (G1, `CompactionModeSource.forCreate`). */
    val compaction: suspend () -> JsonObject? = { null },
) {
    companion object {
        const val LOG_TAG = "ClineCheckpoint"

        fun of(graph: AppGraph): CheckpointDeps {
            val hub = HubCheckpointHub(graph.hubClient, graph.sessionApi)
            return CheckpointDeps(
                actions = CheckpointActions(hub, graph.termux, graph.clock, kernelRelease = { graph.kernel.portVersion.value?.release }),
                hub = hub,
                sessions = SessionStateSource.of(graph.sessionStore),
                gate = graph.kernel.gate,
                autoApprove = { graph.approvalMode.autoApproveForNewSession() },
                clock = graph.clock,
                compaction = { graph.compactionMode.forCreate() },
            )
        }
    }
}

/** How the dialog's confirm stands with the kernel (C.8). */
enum class CheckpointKernelLine { Connected, Reconnecting, NotConnected }

/** C1 / C2 as shown. */
@Immutable
data class CheckpointDialogUi(
    val kind: RestoreKind,
    val messageId: String,
    val runCount: Int,
    /** The "Restore to:" text, 60 characters (CT C.4 previews). */
    val preview: String,
    /** C2 opened from the C3 picker (CLI `/undo` semantic). */
    val pickerMode: Boolean,
    /** C1: exact checkpoint; C2: a checkpoint at or before the run. False → C2 no-checkpoint text, no options. */
    val hasCheckpoint: Boolean,
    val restoreWorkspace: Boolean,
    /** False after "is not a git repository": only "Restore chat only" for this dialog's life. */
    val workspaceOptionAvailable: Boolean,
    /** Kernel text of a workspace refusal (C.6 step 6). */
    val refusal: String?,
    val kernel: CheckpointKernelLine,
    /** The session became busy while the dialog was open (C.8). */
    val busy: Boolean,
    /** C1 only: the message lost its exact checkpoint after a history refresh; the host calls [CheckpointViewModel.onCheckpointLost]. */
    val checkpointLost: Boolean = false,
) {
    val showOptions: Boolean get() = hasCheckpoint
    val workspaceSelected: Boolean get() = showOptions && workspaceOptionAvailable && restoreWorkspace
    val confirmEnabled: Boolean get() = kernel == CheckpointKernelLine.Connected && !busy && !checkpointLost
}

/** A failure under the originating message row (C.5 "Error"). */
@Immutable
data class RowError(
    val kind: RestoreKind,
    /** Kernel text, or null for the flow's fallback (C.7). */
    val message: String?,
    /** Split path: the workspace was reset before the new session failed. */
    val workspaceRestored: Boolean = false,
    val pickerMode: Boolean = false,
    /** The reply carried no session ("no result returned."). */
    val noResult: Boolean = false,
    /** The Hub connection dropped before the restore was sent: "Cline Hub is not connected." */
    val notConnected: Boolean = false,
)

/** The originating row's pending state (C.5 "Pending"). */
@Immutable
data class RowPending(val messageId: String, val kind: RestoreKind, val pickerMode: Boolean, val draining: Boolean = false)

/** C3 rows. */
@Immutable
data class CheckpointPickerRow(
    val key: String,
    val messageId: String?,
    val runCount: Int,
    val preview: String,
    /** The matched checkpoint's `createdAt` (epoch ms). */
    val time: Long,
    val planMode: Boolean,
)

/** C3 body (C.8). */
sealed interface PickerContent {
    data object Loading : PickerContent

    /** "No checkpoint data available" + Retry; [message] is kernel text. */
    data class LoadError(val message: String?) : PickerContent

    /** Empty history; [diagnosis] is null while the git probe runs (C3.5). */
    data class NoHistory(val diagnosis: GitDiagnosis?) : PickerContent

    /** History present, but no restorable message. */
    data object NoRows : PickerContent

    data class Rows(val rows: List<CheckpointPickerRow>) : PickerContent
}

@Immutable
data class CheckpointPickerUi(
    val content: PickerContent,
    /** A refresh is running (cached rows stay visible). */
    val refreshing: Boolean,
    val busy: Boolean,
    val kernel: CheckpointKernelLine,
) {
    val rowsEnabled: Boolean get() = !busy && kernel == CheckpointKernelLine.Connected
}

/** Why the entries are unavailable (C.3), for the row icons' state description and tap snackbar. */
enum class CheckpointUnavailable { Busy, InProgress }

@Immutable
data class CheckpointUiState(
    val sessionId: String? = null,
    val dialog: CheckpointDialogUi? = null,
    val picker: CheckpointPickerUi? = null,
    val lostReply: Boolean = false,
    val pending: RowPending? = null,
    val errors: Map<String, RowError> = emptyMap(),
    /** Busy rule of C.3 without the versioning part. */
    val busy: Boolean = false,
    val versioningPending: Boolean = false,
    /** `session_not_found` on restore: the actions stay disabled until the session is rebound. */
    val sourceGone: Boolean = false,
    /** Messages folded into a compacted run: their restore/edit actions are hidden from then on. */
    val hiddenMessageIds: Set<String> = emptySet(),
) {
    val unavailable: CheckpointUnavailable?
        get() = when {
            busy || sourceGone -> CheckpointUnavailable.Busy
            versioningPending -> CheckpointUnavailable.InProgress
            else -> null
        }

    fun actionsHidden(messageId: String): Boolean = messageId in hiddenMessageIds
}

/** Snackbar copy the host resolves (C.7). */
sealed interface CheckpointSnack {
    data object Restored : CheckpointSnack
    data object InProgress : CheckpointSnack
    data object Busy : CheckpointSnack
    data object RestoreError : CheckpointSnack

    /** Indefinite picker-mode status line. */
    data class Status(val workspace: Boolean) : CheckpointSnack

    /** Long picker-mode failure. */
    data class Failed(val error: RowError) : CheckpointSnack
}

sealed interface CheckpointEvent {
    data class Snackbar(val snack: CheckpointSnack) : CheckpointEvent

    /** Dismiss the picker-mode status snackbar. */
    data object DismissStatus : CheckpointEvent

    /**
     * C.6 step 7: move the chat to [newSessionId] with `popUpTo(chat) { inclusive = true }`,
     * close the source's panes, clear its approvals/questions/queue in the UI, and (edit)
     * put [composerText] into the new session's composer with focus and the IME.
     */
    data class SessionReplaced(
        val sourceSessionId: String,
        val newSessionId: String,
        val composerText: String?,
        val hasMessages: Boolean,
    ) : CheckpointEvent

    /** "Open sessions" of the lost-reply dialog. */
    data object OpenSessions : CheckpointEvent
}

/** What survives process death for an open dialog (no message text). */
@Serializable
internal data class SavedDialog(
    val sessionId: String,
    val kind: RestoreKind,
    val messageId: String,
    val runCount: Int,
    val pickerMode: Boolean,
    val restoreWorkspace: Boolean,
    val workspaceOptionAvailable: Boolean,
    val refusal: String? = null,
)

/** The originating row of the saved [PendingRestore]. */
@Serializable
internal data class SavedRow(val messageId: String, val kind: RestoreKind, val pickerMode: Boolean)

@OptIn(ExperimentalCoroutinesApi::class)
class CheckpointViewModel(
    private val savedState: SavedStateHandle,
    private val deps: CheckpointDeps,
) : ViewModel() {

    private data class HostFlags(val abortPending: Boolean = false, val switching: Boolean = false)

    private data class Internal(
        val sessionId: String? = null,
        val dialog: SavedDialog? = null,
        /** The text the row handed in (edit), kept in memory only. */
        val dialogText: String? = null,
        /** C2 picker mode returns to the sheet on Cancel. */
        val returnToPicker: Boolean = false,
        val pickerOpen: Boolean = false,
        val picker: PickerContent = PickerContent.Loading,
        val pickerRefreshing: Boolean = false,
        val lostReply: Boolean = false,
        val pendingSession: String? = null,
        val pending: RowPending? = null,
        val errors: Map<String, RowError> = emptyMap(),
        val sourceGone: Boolean = false,
        val hidden: Set<String> = emptySet(),
        val jobActive: Boolean = false,
    )

    /** A loaded picker data set, cached for 10 s (C.6 "Picker"). */
    private class PickerData(
        val sessionId: String,
        val loadedAt: Long,
        val index: CheckpointIndex,
        val texts: Map<Int, String>,
        val modes: Map<Int, String>,
        val cwd: String?,
    )

    private val internal = MutableStateFlow(Internal())
    private val host = MutableStateFlow(HostFlags())
    private val events = Channel<CheckpointEvent>(Channel.BUFFERED)
    private var restoreJob: Job? = null
    private var pickerJob: Job? = null
    private var pickerData: PickerData? = null

    /** The kernel projection the picker's KernelStrip renders. */
    val gate: StateFlow<KernelGate> = deps.gate

    /** One-shot events; the host collects them ([CheckpointHost] does). */
    val eventFlow: Flow<CheckpointEvent> = events.receiveAsFlow()

    private val session: Flow<SessionState?> = internal.map { it.sessionId }.distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(null) else deps.sessions.state(id) }

    val uiState: StateFlow<CheckpointUiState> = combine(internal, host, session, deps.gate) { s, h, state, gate ->
        project(s, h, state, gate)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CheckpointUiState())

    init {
        val savedSession = savedState.get<String>(KEY_SESSION)
        val dialog = savedState.get<String>(KEY_DIALOG)?.let { raw ->
            runCatching { HubJson.decodeFromString(SavedDialog.serializer(), raw) }.getOrNull()
        }?.takeIf { it.sessionId == savedSession }
        val pickerOpen = savedSession != null && savedState.get<String>(KEY_PICKER) == savedSession
        internal.value = Internal(sessionId = savedSession, dialog = dialog, pickerOpen = pickerOpen, returnToPicker = dialog?.pickerMode == true && pickerOpen)
        if (pickerOpen && dialog == null) loadPicker(force = true)
        PendingRestore.decode(savedState.get<String>(KEY_PENDING))?.let { pending ->
            val row = savedState.get<String>(KEY_ROW)?.let { raw ->
                runCatching { HubJson.decodeFromString(SavedRow.serializer(), raw) }.getOrNull()
            }
            resumeRecovery(pending, row)
        }
    }

    // ---- host inputs --------------------------------------------------------------------------

    /** The chat's selected session. A change closes any dialog and the sheet (desktop resets on `sessionId`). */
    fun bindSession(sessionId: String?) {
        if (internal.value.sessionId == sessionId) return
        savedState[KEY_SESSION] = sessionId
        savedState[KEY_DIALOG] = null
        savedState[KEY_PICKER] = null
        pickerJob?.cancel()
        pickerData = null
        internal.update {
            it.copy(
                sessionId = sessionId,
                dialog = null,
                dialogText = null,
                returnToPicker = false,
                pickerOpen = false,
                picker = PickerContent.Loading,
                pickerRefreshing = false,
                errors = emptyMap(),
                sourceGone = false,
                hidden = emptySet(),
            )
        }
    }

    /** The chat's own busy inputs: a `run.abort` pending (Stop) and a session switch in progress. */
    fun setHostFlags(abortPending: Boolean, switching: Boolean) {
        host.value = HostFlags(abortPending, switching)
    }

    // ---- entries (C.3) ------------------------------------------------------------------------

    /** C1 from the row's undo icon or the long-press menu. */
    fun openRevert(messageId: String, runCount: Int) = openDialog(RestoreKind.Revert, messageId, runCount, text = null, pickerMode = false)

    /** C2 from the row's edit icon or the long-press menu; [content] is the message's display text. */
    fun openEdit(messageId: String, content: String, runCount: Int) =
        openDialog(RestoreKind.Edit, messageId, runCount, text = content, pickerMode = false)

    /** C3 from the header overflow ("Restore checkpoint…"). */
    fun openPicker() {
        val id = internal.value.sessionId ?: return
        savedState[KEY_PICKER] = id
        internal.update { it.copy(pickerOpen = true) }
        loadPicker(force = false)
    }

    fun dismissPicker() {
        savedState[KEY_PICKER] = null
        pickerJob?.cancel()
        internal.update { it.copy(pickerOpen = false, pickerRefreshing = false) }
    }

    fun retryPicker() = loadPicker(force = true)

    /** C3 row tap: C2 in picker mode on top of the sheet. */
    fun pickRow(row: CheckpointPickerRow) {
        val s = internal.value
        val id = s.sessionId ?: return
        if (unavailableNow() != null) return
        val saved = SavedDialog(
            sessionId = id,
            kind = RestoreKind.Edit,
            messageId = row.messageId ?: row.key,
            runCount = row.runCount,
            pickerMode = true,
            restoreWorkspace = true,
            workspaceOptionAvailable = true,
        )
        persistDialog(saved)
        internal.update { it.copy(dialog = saved, dialogText = pickerData?.texts?.get(row.runCount), returnToPicker = true, pickerOpen = false) }
    }

    fun selectWorkspace(workspace: Boolean) {
        val d = internal.value.dialog ?: return
        if (workspace && !d.workspaceOptionAvailable) return
        val next = d.copy(restoreWorkspace = workspace)
        persistDialog(next)
        internal.update { it.copy(dialog = next) }
    }

    /** Back, outside tap or Cancel. In picker mode Cancel returns to the sheet (C.3). */
    fun dismissDialog() {
        val s = internal.value
        persistDialog(null)
        if (s.returnToPicker && s.sessionId != null) {
            savedState[KEY_PICKER] = s.sessionId
            internal.update { it.copy(dialog = null, dialogText = null, returnToPicker = false, pickerOpen = true) }
            loadPicker(force = false)
        } else {
            internal.update { it.copy(dialog = null, dialogText = null, returnToPicker = false) }
        }
    }

    /** C.8: a C1 whose message lost its exact checkpoint closes with "Could not restore checkpoint.". */
    fun onCheckpointLost() {
        if (internal.value.dialog?.kind != RestoreKind.Revert) return
        persistDialog(null)
        internal.update { it.copy(dialog = null, dialogText = null, returnToPicker = false) }
        events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.RestoreError))
    }

    /** Confirm (C.6). The dialog closes first; the work continues here. */
    fun confirm() {
        val s = internal.value
        val ui = project(s, host.value, s.sessionId?.let { deps.sessions.state(it).value }, deps.gate.value).dialog ?: return
        val saved = s.dialog ?: return
        val sessionId = s.sessionId ?: return
        if (!ui.confirmEnabled) return
        if (versioningPending()) {
            closeDialogAndSheet()
            events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.InProgress))
            return
        }
        val text = s.dialogText
        closeDialogAndSheet()
        val workspace = ui.workspaceSelected
        val pending = RowPending(saved.messageId, saved.kind, saved.pickerMode)
        internal.update { it.copy(pending = pending, pendingSession = sessionId, errors = it.errors - saved.messageId, jobActive = true) }
        if (saved.pickerMode) events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.Status(workspace)))
        restoreJob = viewModelScope.launch {
            val request = RestoreRequest(
                kind = saved.kind,
                sourceSessionId = sessionId,
                runCount = saved.runCount,
                restoreWorkspace = workspace,
                hasCheckpoint = ui.hasCheckpoint,
                autoApprove = runCatching { deps.autoApprove(sessionId) }.getOrDefault(false),
                compaction = runCatching { deps.compaction() }.getOrNull(),
            )
            val outcome = try {
                deps.actions.restore(request) { progress -> onProgress(progress, pending) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestoreOutcome.Failed(RestoreFailure.Kernel, (e as? HubCommandException)?.hubMessage)
            }
            handle(outcome, sessionId, pending, saved, text)
        }
    }

    fun dismissLostReply() {
        internal.update { it.copy(lostReply = false) }
    }

    fun openSessionsFromLostReply() {
        internal.update { it.copy(lostReply = false) }
        events.trySend(CheckpointEvent.OpenSessions)
    }

    // ---- internals ----------------------------------------------------------------------------

    private fun openDialog(kind: RestoreKind, messageId: String, runCount: Int, text: String?, pickerMode: Boolean) {
        val id = internal.value.sessionId ?: return
        when (unavailableNow()) {
            CheckpointUnavailable.Busy -> {
                events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.Busy))
                return
            }
            CheckpointUnavailable.InProgress -> {
                events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.InProgress))
                return
            }
            null -> Unit
        }
        val saved = SavedDialog(id, kind, messageId, runCount, pickerMode, restoreWorkspace = true, workspaceOptionAvailable = true)
        persistDialog(saved)
        internal.update { it.copy(dialog = saved, dialogText = text, returnToPicker = false) }
    }

    private fun closeDialogAndSheet() {
        persistDialog(null)
        savedState[KEY_PICKER] = null
        internal.update { it.copy(dialog = null, returnToPicker = false, pickerOpen = false) }
    }

    private fun persistDialog(dialog: SavedDialog?) {
        savedState[KEY_DIALOG] = dialog?.let { HubJson.encodeToString(SavedDialog.serializer(), it) }
    }

    private fun versioningPending(): Boolean = restoreJob?.isActive == true || deps.actions.pending

    private fun unavailableNow(): CheckpointUnavailable? {
        val s = internal.value
        val state = s.sessionId?.let { deps.sessions.state(it).value }
        val h = host.value
        val busy = s.sourceGone || (state != null && CheckpointActions.isBusy(state.facts, h.abortPending, h.switching))
        return when {
            busy -> CheckpointUnavailable.Busy
            versioningPending() -> CheckpointUnavailable.InProgress
            else -> null
        }
    }

    private fun onProgress(progress: RestoreProgress, row: RowPending) {
        when (progress) {
            is RestoreProgress.Started -> savePending(progress.pending, row)
            is RestoreProgress.WorkspaceRestored -> savePending(progress.pending, row)
            RestoreProgress.Draining -> internal.update { s -> s.copy(pending = s.pending?.copy(draining = true)) }
        }
    }

    private fun savePending(pending: PendingRestore, row: RowPending) {
        savedState[KEY_PENDING] = pending.encode()
        savedState[KEY_ROW] = HubJson.encodeToString(SavedRow.serializer(), SavedRow(row.messageId, row.kind, row.pickerMode))
    }

    private fun clearPending() {
        savedState[KEY_PENDING] = null
        savedState[KEY_ROW] = null
        internal.update { it.copy(pending = null, pendingSession = null, jobActive = false) }
    }

    /** Process death while pending (CT §1.6): run the lost-reply recovery on the next connect. */
    private fun resumeRecovery(pending: PendingRestore, row: SavedRow?) {
        val rowPending = row?.let { RowPending(it.messageId, it.kind, it.pickerMode) }
        internal.update { it.copy(pending = rowPending, pendingSession = pending.sourceSessionId, jobActive = true) }
        val dialog = SavedDialog(
            sessionId = pending.sourceSessionId,
            kind = pending.kind,
            messageId = row?.messageId.orEmpty(),
            runCount = pending.runCount,
            pickerMode = row?.pickerMode == true,
            restoreWorkspace = pending.restoreWorkspace,
            workspaceOptionAvailable = true,
        )
        restoreJob = viewModelScope.launch {
            val outcome = try {
                deps.actions.recover(pending)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                RestoreOutcome.StatusUnknown(pending)
            }
            handle(outcome, pending.sourceSessionId, rowPending, dialog, text = null)
        }
    }

    private suspend fun handle(outcome: RestoreOutcome, sourceId: String, row: RowPending?, dialog: SavedDialog, text: String?) {
        val pickerMode = dialog.pickerMode
        if (pickerMode) events.trySend(CheckpointEvent.DismissStatus)
        when (outcome) {
            is RestoreOutcome.Success -> {
                clearPending()
                val composerText = if (dialog.kind == RestoreKind.Edit) text ?: editTextFor(sourceId, dialog.runCount) else null
                events.trySend(
                    CheckpointEvent.SessionReplaced(
                        sourceSessionId = sourceId,
                        newSessionId = outcome.newSessionId,
                        composerText = composerText,
                        hasMessages = outcome.hasMessages,
                    ),
                )
                if (dialog.kind == RestoreKind.Revert || pickerMode) events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.Restored))
            }
            is RestoreOutcome.WorkspaceRefused -> {
                clearPending()
                if (internal.value.sessionId == sourceId && dialog.messageId.isNotEmpty()) {
                    val reopened = dialog.copy(
                        restoreWorkspace = false,
                        workspaceOptionAvailable = dialog.workspaceOptionAvailable && outcome.workspaceOptionAvailable,
                        refusal = outcome.message,
                    )
                    persistDialog(reopened)
                    internal.update { it.copy(dialog = reopened, dialogText = text, returnToPicker = false) }
                }
            }
            is RestoreOutcome.Failed -> {
                clearPending()
                val error = RowError(
                    kind = dialog.kind,
                    message = outcome.message?.takeUnless { it == CheckpointActions.NO_SESSION_MESSAGE && pickerMode },
                    workspaceRestored = outcome.workspaceRestored,
                    pickerMode = pickerMode,
                    noResult = outcome.message == CheckpointActions.NO_SESSION_MESSAGE,
                    notConnected = outcome.reason == RestoreFailure.NotConnected,
                )
                onFailure(outcome, sourceId, row?.messageId ?: dialog.messageId.takeIf { it.isNotEmpty() })
                val key = row?.messageId ?: dialog.messageId
                if (internal.value.sessionId == sourceId && key.isNotEmpty()) {
                    internal.update { it.copy(errors = it.errors + (key to error)) }
                }
                if (pickerMode) events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.Failed(error)))
            }
            is RestoreOutcome.StatusUnknown -> {
                clearPending()
                internal.update { it.copy(lostReply = true) }
            }
            RestoreOutcome.AlreadyInProgress -> {
                internal.update { it.copy(pending = null, pendingSession = null, jobActive = false) }
                events.trySend(CheckpointEvent.Snackbar(CheckpointSnack.InProgress))
            }
        }
    }

    /** CT §1.5.4 side effects; logs carry the reason only. */
    private suspend fun onFailure(outcome: RestoreOutcome.Failed, sourceId: String, messageId: String?) {
        when (outcome.reason) {
            RestoreFailure.SourceGone -> if (internal.value.sessionId == sourceId) internal.update { it.copy(sourceGone = true) }
            RestoreFailure.HistoryChanged -> {
                if (outcome.message?.startsWith("Could not find user message") == true) deps.log("restore_run_unmapped")
                pickerData = null
                runCatching { deps.sessions.seed(sourceId) }
            }
            RestoreFailure.FoldedRun -> if (messageId != null && internal.value.sessionId == sourceId) {
                internal.update { it.copy(hidden = it.hidden + messageId) }
            }
            RestoreFailure.InvalidRequest -> deps.log("restore_invalid")
            RestoreFailure.NoCwd -> deps.log("restore_no_cwd")
            else -> Unit
        }
    }

    /** The edit's composer text when only the run count survived (process death, picker without cache). */
    private suspend fun editTextFor(sourceId: String, runCount: Int): String? = try {
        val messages = HubPayloads.messagesOf(deps.hub.messages(sourceId)).mapNotNull { it as? JsonObject }.map(RunMessage::fromJson)
        val entry = CheckpointIndex.build(emptyList(), messages).runs.firstOrNull { it.runCount == runCount && it.span == 1 }
        entry?.let { displayText(messages[it.index]) }?.takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private fun loadPicker(force: Boolean) {
        val id = internal.value.sessionId ?: return
        val cached = pickerData?.takeIf { it.sessionId == id }
        if (cached != null) internal.update { it.copy(picker = contentFor(cached, it.picker)) }
        if (!force && cached != null && deps.clock.millis() - cached.loadedAt < PICKER_CACHE_MS) return
        // C.8 / §13 C3 KernelDown: the cached rows stay visible (disabled) instead of a refresh that cannot succeed.
        if (cached != null && kernelLine(deps.gate.value) != CheckpointKernelLine.Connected) return
        pickerJob?.cancel()
        internal.update { it.copy(pickerRefreshing = true, picker = if (cached == null) PickerContent.Loading else it.picker) }
        pickerJob = viewModelScope.launch {
            val data = try {
                val record = deps.hub.get(id)
                val raw = HubPayloads.messagesOf(deps.hub.messages(id)).mapNotNull { it as? JsonObject }
                buildPickerData(id, record, raw)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (internal.value.sessionId == id) {
                    // A failed refresh keeps the cached rows; the load error is for an empty cache only.
                    val kept = pickerData?.takeIf { it.sessionId == id }
                    internal.update {
                        it.copy(
                            pickerRefreshing = false,
                            picker = kept?.let { k -> contentFor(k, it.picker) }
                                ?: PickerContent.LoadError((e as? HubCommandException)?.hubMessage ?: e.message),
                        )
                    }
                }
                return@launch
            }
            if (internal.value.sessionId != id) return@launch
            pickerData = data
            internal.update { it.copy(pickerRefreshing = false, picker = contentFor(data, null)) }
            if (data.index.history.isEmpty()) {
                val diagnosis = data.cwd?.let {
                    try {
                        deps.actions.diagnoseGit(it)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        GitDiagnosis.Unknown
                    }
                } ?: GitDiagnosis.Unknown
                if (internal.value.sessionId == id && pickerData === data) {
                    internal.update { it.copy(picker = PickerContent.NoHistory(diagnosis)) }
                }
            }
        }
    }

    private fun contentFor(data: PickerData, previous: PickerContent?): PickerContent = when {
        data.index.history.isEmpty() -> (previous as? PickerContent.NoHistory) ?: PickerContent.NoHistory(null)
        else -> {
            val rows = data.index.restorableMessages().map { r ->
                CheckpointPickerRow(
                    key = r.entry.messageId ?: "run-${r.entry.runCount}",
                    messageId = r.entry.messageId,
                    runCount = r.entry.runCount,
                    preview = r.previewLine,
                    time = r.time,
                    planMode = data.modes[r.entry.runCount] == PLAN,
                )
            }
            if (rows.isEmpty()) PickerContent.NoRows else PickerContent.Rows(rows)
        }
    }

    private fun buildPickerData(sessionId: String, record: JsonObject, raw: List<JsonObject>): PickerData {
        val session = record.obj("session")
        val snapshot = record.obj("snapshot")
        val checkpoint = snapshot?.obj("checkpoint") ?: session?.obj("metadata")?.obj("checkpoint")
        val messages = raw.map(RunMessage::fromJson)
        val index = CheckpointIndex.build(readCheckpointHistory(checkpoint), messages)
        val texts = HashMap<Int, String>()
        val modes = HashMap<Int, String>()
        index.runs.forEach { entry ->
            if (entry.span != 1) return@forEach
            texts[entry.runCount] = displayText(messages[entry.index])
            val mode = (messages[entry.index].metadata?.get("mode") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (mode != null) modes[entry.runCount] = mode
        }
        return PickerData(
            sessionId = sessionId,
            loadedAt = deps.clock.millis(),
            index = index,
            texts = texts,
            modes = modes,
            cwd = session?.str("cwd") ?: session?.str("workspaceRoot"),
        )
    }

    private fun project(s: Internal, h: HostFlags, state: SessionState?, gate: KernelGate): CheckpointUiState {
        val kernel = kernelLine(gate)
        val facts = state?.facts
        val busy = facts != null && CheckpointActions.isBusy(facts, h.abortPending, h.switching)
        val versioning = s.jobActive || deps.actions.pending
        val history = facts?.checkpointHistory.orEmpty().map(CheckpointEntry::toRef)
        val seeded = state?.seed is SeedState.Seeded
        val dialog = s.dialog?.takeIf { it.sessionId == s.sessionId }?.let { d ->
            val inPicker = d.pickerMode
            val hasCheckpoint = when {
                inPicker -> true
                d.kind == RestoreKind.Revert -> true
                else -> findCheckpointForRun(history, d.runCount) != null
            }
            val lost = d.kind == RestoreKind.Revert && !inPicker && seeded && history.none { it.runCount == d.runCount }
            CheckpointDialogUi(
                kind = d.kind,
                messageId = d.messageId,
                runCount = d.runCount,
                preview = previewFor(s, state, d),
                pickerMode = inPicker,
                hasCheckpoint = hasCheckpoint,
                restoreWorkspace = d.restoreWorkspace,
                workspaceOptionAvailable = d.workspaceOptionAvailable,
                refusal = d.refusal,
                kernel = kernel,
                busy = busy || s.sourceGone,
                checkpointLost = lost,
            )
        }
        val picker = if (s.pickerOpen && s.dialog == null) {
            CheckpointPickerUi(content = s.picker, refreshing = s.pickerRefreshing, busy = busy || versioning || s.sourceGone, kernel = kernel)
        } else null
        return CheckpointUiState(
            sessionId = s.sessionId,
            dialog = dialog,
            picker = picker,
            lostReply = s.lostReply,
            pending = s.pending?.takeIf { s.pendingSession == s.sessionId },
            errors = s.errors,
            busy = busy,
            versioningPending = versioning,
            sourceGone = s.sourceGone,
            hiddenMessageIds = s.hidden,
        )
    }

    private fun previewFor(s: Internal, state: SessionState?, d: SavedDialog): String {
        val text = s.dialogText
            ?: state?.messages?.firstOrNull { it.id == d.messageId && it.role == Role.User }?.let { UserInputFormat.formatDisplayUserInput(it.content) }
            ?: pickerData?.takeIf { it.sessionId == d.sessionId }?.texts?.get(d.runCount)
            ?: return ""
        return truncatePreview(collapseWhitespace(text))
    }

    override fun onCleared() {
        pickerJob?.cancel()
        events.close()
    }

    companion object {
        internal const val KEY_SESSION = "checkpoint.session"
        internal const val KEY_DIALOG = "checkpoint.dialog"
        internal const val KEY_PICKER = "checkpoint.picker"
        internal const val KEY_PENDING = "checkpoint.pending"
        internal const val KEY_ROW = "checkpoint.pendingRow"

        /** C.6 "Picker": refresh when the cached copies are older than 10 s. */
        const val PICKER_CACHE_MS = 10_000L

        private const val PLAN = "plan"

        fun kernelLine(gate: KernelGate): CheckpointKernelLine = when (gate) {
            KernelGate.Ready, KernelGate.DegradedNoCommands -> CheckpointKernelLine.Connected
            is KernelGate.Busy -> if (gate.kind == KernelGate.BusyKind.Draining) CheckpointKernelLine.Connected else CheckpointKernelLine.Reconnecting
            is KernelGate.Down, is KernelGate.Blocked -> CheckpointKernelLine.NotConnected
        }

        /** Production factory: the chat route creates it with `viewModel(factory = CheckpointViewModel.factory(graph))`. */
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { CheckpointViewModel(createSavedStateHandle(), CheckpointDeps.of(graph)) }
        }

        /** Tests and debug builds. */
        fun factory(deps: () -> CheckpointDeps): ViewModelProvider.Factory = viewModelFactory {
            initializer { CheckpointViewModel(createSavedStateHandle(), deps()) }
        }
    }
}

internal fun CheckpointEntry.toRef(): CheckpointRef = CheckpointRef(ref = ref, createdAt = createdAt, runCount = runCount, kind = kind)
