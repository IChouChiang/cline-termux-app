package dev.cline.termux.ui.queue

import androidx.compose.runtime.Immutable
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubConnectionClosedException
import dev.cline.termux.core.kernel.hub.HubErrorCodes
import dev.cline.termux.core.kernel.hub.HubSessionApi
import dev.cline.termux.core.kernel.hub.isPendingPromptsUnavailable
import dev.cline.termux.data.chat.SlashRefusal
import dev.cline.termux.data.chat.SlashResolution
import dev.cline.termux.data.session.QueuedPrompt
import dev.cline.termux.data.session.SessionState
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.tools.SessionStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** One queued prompt as the strip and sheet show it (composer-approvals.md C2.6 `QueuedPrompt`). */
@Immutable
data class QueuedPromptItem(
    val id: String,
    /** `formatDisplayUserInput(prompt)`: envelopes folded back to `/team X`, `/name …`. */
    val displayPrompt: String,
    val steer: Boolean,
    val attachmentCount: Int,
)

enum class QueueAction { Steer, Save, Remove }

/** Why a queue action failed; the UI picks the sentence (C2.5). */
sealed interface QueueFailure {
    /** Kernel text, verbatim; blank → the action's fallback sentence. */
    data class Kernel(val message: String) : QueueFailure

    /** The socket closed under the command: "Cline Hub is not connected." */
    data object NotConnected : QueueFailure

    /** Saving an edit re-runs C5.7 and it refused (e.g. `/team` without a task). */
    data class Refused(val refusal: SlashRefusal) : QueueFailure
}

@Immutable
data class QueueError(val promptId: String, val action: QueueAction, val failure: QueueFailure)

/** How the kernel stands for the queue (C2.4 rows "Kernel reconnecting" / "Kernel down"). */
enum class QueueLink { Connected, Reconnecting, Down }

fun KernelGate.toQueueLink(): QueueLink = when (this) {
    KernelGate.Ready, KernelGate.DegradedNoCommands -> QueueLink.Connected
    is KernelGate.Busy -> QueueLink.Reconnecting
    is KernelGate.Down, is KernelGate.Blocked -> QueueLink.Down
}

/** C2.6 `PromptQueueState`, plus the queue's availability on this connection. */
@Immutable
data class PromptQueueState(
    val items: List<QueuedPromptItem> = emptyList(),
    /** One action at a time. */
    val pendingId: String? = null,
    val pendingAction: QueueAction? = null,
    val editingId: String? = null,
    val editingText: String = "",
    val error: QueueError? = null,
    val sheetOpen: Boolean = false,
    val link: QueueLink = QueueLink.Connected,
    /** `pending_prompts_unavailable`: no prompt queue on this kernel build; busy sends go out directly. */
    val unavailable: Boolean = false,
    /** The first `session.pending_prompts` reply (or event) arrived; nothing shows before it. */
    val loaded: Boolean = false,
) {
    val busy: Boolean get() = pendingId != null
    val actionsEnabled: Boolean get() = pendingId == null && link == QueueLink.Connected

    /** The strip is composed (C2.4): a loaded, non-empty queue on a kernel that is not down. */
    val stripVisible: Boolean get() = loaded && !unavailable && items.isNotEmpty() && link != QueueLink.Down

    /** The strip's Steer button: the first item is not steering yet. */
    val canSteerFirst: Boolean get() = items.firstOrNull()?.steer == false
}

sealed interface QueueEvent {
    /** Strip Steer failed: snackbar "Could not steer queued message. <error>" (desktop toast). */
    data class StripSteerFailed(val failure: QueueFailure) : QueueEvent
}

/** The four queue commands (CA C2.6); production is [of] over `HubSessionApi`. Replies carry `{prompts}`. */
interface PromptQueueApi {
    suspend fun pendingPrompts(sessionId: String): JsonObject
    suspend fun steerFirst(sessionId: String): JsonObject
    suspend fun update(sessionId: String, promptId: String, prompt: String?, delivery: String?): JsonObject
    suspend fun remove(sessionId: String, promptId: String): JsonObject

    companion object {
        fun of(api: HubSessionApi): PromptQueueApi = object : PromptQueueApi {
            override suspend fun pendingPrompts(sessionId: String) = api.pendingPrompts(sessionId)
            override suspend fun steerFirst(sessionId: String) = api.steerFirstPendingPrompt(sessionId)
            override suspend fun update(sessionId: String, promptId: String, prompt: String?, delivery: String?) =
                api.updatePendingPrompt(sessionId, promptId, prompt, delivery)
            override suspend fun remove(sessionId: String, promptId: String) = api.removePendingPrompt(sessionId, promptId)
        }
    }
}

/**
 * The prompt queue of one open session (composer-approvals.md C2), owned by `ChatViewModel`.
 * Live list: the store's `facts.pendingPrompts`, which `session.pending_prompts` events
 * replace. Load: `session.pending_prompts` on start, on reconnect and on [reload] (after
 * `session.attach`). A mutation reply replaces the list only when no event changed it
 * meanwhile (desktop `queueRevisionRef`). Ports `agent-prompt-queue.tsx`'s one-action-at-a-
 * time rule, per-row errors and the auto-cancel of an edit whose item left the queue.
 * Prompt text is user content and is never logged (§0.8).
 */
class PromptQueueController(
    private val sessionId: String,
    private val api: PromptQueueApi,
    private val session: StateFlow<SessionState>,
    private val kernel: StateFlow<KernelGate>,
    private val scope: CoroutineScope,
    /** C5.7 on an edited prompt (`SendPipeline.resolve` with the session's workspace snapshot and mode). */
    private val resolveEdit: suspend (String) -> SlashResolution,
) {
    private val _state = MutableStateFlow(PromptQueueState(link = kernel.value.toQueueLink()))
    val state: StateFlow<PromptQueueState> = _state.asStateFlow()

    private val _events = Channel<QueueEvent>(Channel.BUFFERED)
    val events: Flow<QueueEvent> = _events.receiveAsFlow()

    /** Bumped whenever an event replaced the store's list; replies older than it are dropped. */
    private var revision = 0
    private var storeList: List<QueuedPrompt>? = null
    private var storeUnavailable = false
    private var localUnavailable = false

    /** [openSheet] arrived before the first load (the `?sheet=queue` deep link); honoured once the list loads. */
    private var openWhenLoaded = false

    init {
        scope.launch {
            session.collect { onSession(it) }
        }
        scope.launch {
            kernel.collect { onLink(it.toQueueLink()) }
        }
        reload()
    }

    /** `session.pending_prompts` now (open, reconnect, `session.attach`). */
    fun reload() {
        if (_state.value.link == QueueLink.Down) return
        val rev = revision
        scope.launch {
            try {
                val reply = api.pendingPrompts(sessionId)
                applyReply(reply, rev, markLoaded = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is HubCommandException && e.isPendingPromptsUnavailable()) markUnavailable()
            }
        }
    }

    /**
     * Strip Steer (and hardware Enter on an empty composer, C2.7): `steer_first_pending_prompt`.
     * [awaitQueuedSends] lets queued sends still in flight be acknowledged first, for at most
     * 1 s (desktop `QUEUE_ACKNOWLEDGEMENT_WAIT_MS`).
     */
    fun steerFirst(awaitQueuedSends: (suspend () -> Unit)? = null) {
        val current = _state.value
        if (current.busy || current.link != QueueLink.Connected || current.unavailable) return
        scope.launch {
            if (awaitQueuedSends != null) withTimeoutOrNull(QUEUE_ACKNOWLEDGEMENT_WAIT_MS) { awaitQueuedSends() }
            val first = _state.value.items.firstOrNull() ?: return@launch
            if (_state.value.busy) return@launch
            runAction(first.id, QueueAction.Steer, fromStrip = true) { api.steerFirst(sessionId) }
        }
    }

    /** Sheet row Steer: `update_pending_prompt {delivery:"steer"}` (the kernel moves it to the front). */
    fun steer(promptId: String) {
        if (!_state.value.actionsEnabled) return
        scope.launch { runAction(promptId, QueueAction.Steer, fromStrip = false) { api.update(sessionId, promptId, null, DELIVERY_STEER) } }
    }

    fun remove(promptId: String) {
        if (!_state.value.actionsEnabled) return
        scope.launch { runAction(promptId, QueueAction.Remove, fromStrip = false) { api.remove(sessionId, promptId) } }
    }

    fun startEdit(promptId: String) {
        val current = _state.value
        if (current.busy) return
        val item = current.items.firstOrNull { it.id == promptId } ?: return
        _state.value = current.copy(editingId = promptId, editingText = item.displayPrompt, error = null)
    }

    fun onEditTextChange(text: String) {
        if (_state.value.editingId == null) return
        _state.value = _state.value.copy(editingText = text)
    }

    fun cancelEdit() {
        _state.value = _state.value.copy(editingId = null, editingText = "")
    }

    /** Save: trimmed, re-resolved through C5.7 (`/team` rewrite, workflow expansion), then `update_pending_prompt {prompt}`. */
    fun saveEdit() {
        val current = _state.value
        val id = current.editingId ?: return
        val text = current.editingText.trim()
        if (text.isEmpty() || !current.actionsEnabled) return
        scope.launch {
            runAction(id, QueueAction.Save, fromStrip = false, onSuccess = { cancelEdit() }) {
                val prompt = when (val resolution = resolveEdit(text)) {
                    is SlashResolution.Send -> resolution.prompt
                    is SlashResolution.Refused -> throw RefusedException(resolution.reason)
                }
                api.update(sessionId, id, prompt, null)
            }
        }
    }

    /** Opens the sheet; before the first load the request waits for the list and opens only if it is non-empty. */
    fun openSheet() {
        val current = _state.value
        if (!current.loaded) {
            openWhenLoaded = true
            return
        }
        if (!current.stripVisible) return
        _state.value = current.copy(sheetOpen = true)
    }

    fun closeSheet() {
        _state.value = _state.value.copy(sheetOpen = false, editingId = null, editingText = "")
    }

    private suspend fun runAction(
        promptId: String,
        action: QueueAction,
        fromStrip: Boolean,
        onSuccess: () -> Unit = {},
        block: suspend () -> JsonObject,
    ) {
        if (_state.value.busy) return
        val rev = revision
        _state.value = _state.value.copy(pendingId = promptId, pendingAction = action, error = null)
        try {
            val reply = block()
            onSuccess()
            applyReply(reply, rev, markLoaded = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is HubCommandException && e.isPendingPromptsUnavailable()) {
                markUnavailable()
            } else {
                val failure = failureOf(e)
                if (fromStrip) {
                    _events.trySend(QueueEvent.StripSteerFailed(failure))
                } else {
                    _state.value = _state.value.copy(error = QueueError(promptId, action, failure))
                }
            }
        } finally {
            _state.value = _state.value.copy(pendingId = null, pendingAction = null)
        }
    }

    private fun applyReply(reply: JsonObject, rev: Int, markLoaded: Boolean) {
        val prompts = reply["prompts"] as? JsonArray
        if (prompts == null || rev != revision) {
            if (markLoaded && !_state.value.loaded) publish(storeList.orEmpty(), loaded = true)
            return
        }
        publish(parsePrompts(prompts), loaded = true)
    }

    private fun onSession(state: SessionState) {
        val facts = state.facts
        storeUnavailable = facts.queueUnavailable
        val list = facts.pendingPrompts
        val previous = storeList
        storeList = list
        if (previous != null && list !== previous) {
            revision++
            publish(list, loaded = true)
        } else {
            refreshUnavailable()
        }
    }

    private fun onLink(link: QueueLink) {
        val before = _state.value.link
        if (before == link) return
        var next = _state.value.copy(link = link)
        if (link == QueueLink.Down) next = next.copy(sheetOpen = false, editingId = null, editingText = "")
        _state.value = next
        if (link == QueueLink.Connected) {
            // A new connection: the queue may exist again (C2.6) and the list is reloaded (C2.10 step 7).
            localUnavailable = false
            refreshUnavailable()
            reload()
        }
    }

    private fun markUnavailable() {
        localUnavailable = true
        refreshUnavailable()
    }

    private fun refreshUnavailable() {
        val unavailable = localUnavailable || storeUnavailable
        val current = _state.value
        if (current.unavailable == unavailable) return
        _state.value = if (unavailable) {
            openWhenLoaded = false
            current.copy(unavailable = true, sheetOpen = false, editingId = null, editingText = "", loaded = true)
        } else {
            current.copy(unavailable = false)
        }
    }

    private fun publish(prompts: List<QueuedPrompt>, loaded: Boolean) {
        val items = prompts.map { it.toItem() }
        val current = _state.value
        val ids = items.mapTo(HashSet()) { it.id }
        var next = current.copy(items = items, loaded = current.loaded || loaded)
        // Desktop effects: an edit or error whose item left the queue is dropped; an empty queue collapses the sheet.
        if (next.editingId != null && next.editingId !in ids) next = next.copy(editingId = null, editingText = "")
        if (next.error != null && next.error!!.promptId !in ids) next = next.copy(error = null)
        if (items.isEmpty()) next = next.copy(sheetOpen = false)
        _state.value = next
        refreshUnavailable()
        if (openWhenLoaded && _state.value.loaded) {
            openWhenLoaded = false
            if (_state.value.stripVisible) _state.value = _state.value.copy(sheetOpen = true)
        }
    }

    private class RefusedException(val refusal: SlashRefusal) : Exception("refused")

    /** Builds one controller per open session; `ChatViewModel` passes its scope and resolver. */
    class Factory(
        private val api: PromptQueueApi,
        private val sessions: SessionStateSource,
        private val kernel: StateFlow<KernelGate>,
    ) {
        fun create(sessionId: String, scope: CoroutineScope, resolveEdit: suspend (String) -> SlashResolution): PromptQueueController =
            PromptQueueController(sessionId, api, sessions.state(sessionId), kernel, scope, resolveEdit)
    }

    companion object {
        const val QUEUE_ACKNOWLEDGEMENT_WAIT_MS = 1_000L
        const val DELIVERY_STEER = "steer"

        fun QueuedPrompt.toItem(): QueuedPromptItem =
            QueuedPromptItem(id, UserInputFormat.formatDisplayUserInput(prompt), steer, attachmentCount)

        /** A reply's or event's `prompts` array (`{id, prompt, delivery, attachmentCount}`). */
        fun parsePrompts(prompts: JsonArray): List<QueuedPrompt> = prompts.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = (o["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            QueuedPrompt(
                id = id,
                prompt = (o["prompt"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                delivery = (o["delivery"] as? JsonPrimitive)?.contentOrNull ?: "queue",
                attachmentCount = (o["attachmentCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
        }

        fun failureOf(error: Throwable): QueueFailure = when {
            error is RefusedException -> QueueFailure.Refused(error.refusal)
            error is HubConnectionClosedException -> QueueFailure.NotConnected
            error is HubCommandException && error.code == HubErrorCodes.HUB_CONNECTION_CLOSED -> QueueFailure.NotConnected
            error is HubCommandException -> QueueFailure.Kernel(error.hubMessage)
            else -> QueueFailure.Kernel("")
        }
    }
}
