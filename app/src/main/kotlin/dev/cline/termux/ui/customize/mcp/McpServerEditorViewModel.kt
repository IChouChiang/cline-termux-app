package dev.cline.termux.ui.customize.mcp

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperLease
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.data.customize.McpInputError
import dev.cline.termux.data.customize.McpInputException
import dev.cline.termux.data.customize.McpInputs
import dev.cline.termux.data.customize.McpRepository
import dev.cline.termux.data.customize.McpServer
import dev.cline.termux.data.customize.McpServerForm
import dev.cline.termux.data.customize.McpServerInput
import dev.cline.termux.data.customize.McpTransportType
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject

/** Local (stdio) or Remote (Streamable HTTP / SSE), the editor's two radio cards. */
enum class ServerType { Local, Remote }

/** The remote transport select. */
enum class RemoteTransport(val type: McpTransportType) { StreamableHttp(McpTransportType.StreamableHttp), Sse(McpTransportType.Sse) }

/** CR §2.8 `McpRoute`: which channel Save takes. */
enum class McpRoute { Helper, Fallback, Waiting }

/** A field that can carry the validation message (`isError` + supporting text). */
enum class McpField { Name, Command, Url }

/** One env row; [value] stays in this ViewModel only (never `SavedStateHandle`, docs/07 §3.7). */
@Immutable
data class EnvRow(val id: Long, val key: String, val value: String, val revealed: Boolean = false) {
    override fun toString(): String = "EnvRow(id=$id, key=$key, revealed=$revealed)"
}

/** What the error box says (CR §2.4, §2.5, §2.9). */
@Immutable
sealed interface McpFormError {
    data class Validation(val error: McpInputError) : McpFormError
    data class Collision(val name: String) : McpFormError
    data class Failure(val failure: RepoFailure) : McpFormError

    /** The helper dropped mid-save and the re-read does not show the server (CR §2.9). */
    data object Unconfirmed : McpFormError

    /** Edit of a server that is no longer in the file. */
    data object NotFound : McpFormError
}

sealed interface McpEditorMode {
    data object Create : McpEditorMode
    data class Edit(val originalName: String) : McpEditorMode
}

/** CR §2.8 `McpEditorUiState`, plus the dialogs and kernel state the screen renders. */
@Immutable
data class McpEditorUiState(
    val mode: McpEditorMode,
    val loading: Boolean = false,
    val name: String = "",
    val type: ServerType = ServerType.Local,
    val transport: RemoteTransport = RemoteTransport.StreamableHttp,
    val command: String = "",
    val argsText: String = "",
    val cwd: String = "",
    val env: List<EnvRow> = emptyList(),
    val url: String = "",
    val headersText: String = "",
    val enabled: Boolean = true,
    val advancedOpen: Boolean = false,
    val dirty: Boolean = false,
    val fieldError: McpField? = null,
    val formError: McpFormError? = null,
    val saving: Boolean = false,
    val route: McpRoute = McpRoute.Waiting,
    val helper: HelperState = HelperState.Idle,
    val settingsPath: String? = null,
    /** Fallback route + the stored server has env or cwd: Save is hidden (CR §2.8). */
    val blockedInFallback: Boolean = false,
    /** The stored entry's metadata keys (read-only note, Save confirm). */
    val metadataKeys: List<String> = emptyList(),
    val metadataConfirm: Boolean = false,
    /** Restored after process death without the env values (CR §2.8). */
    val reenterEnv: Boolean = false,
    val gate: KernelGate = KernelGate.Ready,
    val deleteDialog: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: RepoFailure? = null,
) {
    val isEdit: Boolean get() = mode is McpEditorMode.Edit

    /** Any env value revealed: `FLAG_SECURE` on the window (SP §0.6). */
    val anyRevealed: Boolean get() = env.any { it.revealed }

    /** The editor requires `Ready` (CR §2.4 "Kernel down"). */
    val kernelReady: Boolean get() = gate is KernelGate.Ready

    val canSave: Boolean get() = kernelReady && !saving && !loading && !blockedInFallback && !deleting

    /** In fallback the env section and Working directory are replaced by a note (CR §2.4). */
    val fallback: Boolean get() = route == McpRoute.Fallback

    override fun toString(): String = "McpEditorUiState(mode=$mode, name=$name, type=$type, env=${env.map { it.key }}, saving=$saving, route=$route)"
}

sealed interface McpEditorEvent {
    /** Saved or deleted: pop the editor. */
    data object Close : McpEditorEvent
}

/**
 * The MCP server editor (CR C2). Leases the settings helper while open; Save validates with
 * the desktop's `buildServerInput`, guards name collisions, asks before dropping metadata,
 * waits up to [helperWaitMs] for a starting helper, then saves through [McpRepository]
 * (helper or CLI fallback). Secrets (env values, headers) live only in this ViewModel.
 */
class McpServerEditorViewModel(
    private val mcp: McpRepository,
    private val helper: HelperLease,
    private val gate: StateFlow<KernelGate>,
    private val savedState: SavedStateHandle,
    originalName: String?,
    private val leaseScope: CoroutineScope,
    private val helperWaitMs: Long = HELPER_WAIT_MS,
) : ViewModel() {
    private val owner = "mcp-editor@" + Integer.toHexString(System.identityHashCode(this))
    private val mode: McpEditorMode = originalName?.takeIf { it.isNotBlank() }?.let { McpEditorMode.Edit(it) } ?: McpEditorMode.Create
    private val restored: Boolean = savedState.get<Boolean>(KEY_INIT) == true
    private var nextEnvId = 1L
    private var previous: McpServer? = null
    private var existingNames: Set<String> = emptySet()
    private var initial: FormFields = FormFields.blank()

    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<McpEditorUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<McpEditorEvent>(extraBufferCapacity = 2, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<McpEditorEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch { helper.acquire(owner) }
        viewModelScope.launch { gate.collect { g -> _state.update { it.copy(gate = g) } } }
        viewModelScope.launch {
            helper.state.collect { h ->
                _state.update { s -> s.copy(helper = h, route = routeOf(h), blockedInFallback = routeOf(h) == McpRoute.Fallback && previous?.hasEnvOrCwd == true) }
            }
        }
        viewModelScope.launch { loadServers() }
    }

    private fun initialState(): McpEditorUiState {
        val base = McpEditorUiState(mode = mode, loading = mode is McpEditorMode.Edit, gate = gate.value, helper = helper.state.value, route = routeOf(helper.state.value))
        if (!restored) return base.copy(env = listOf(newRow()))
        val keys = savedState.get<ArrayList<String>>(KEY_ENV_KEYS).orEmpty()
        return base.copy(
            name = savedState.get<String>(KEY_NAME).orEmpty(),
            type = savedState.get<String>(KEY_TYPE)?.let { runCatching { ServerType.valueOf(it) }.getOrNull() } ?: ServerType.Local,
            transport = savedState.get<String>(KEY_TRANSPORT)?.let { runCatching { RemoteTransport.valueOf(it) }.getOrNull() } ?: RemoteTransport.StreamableHttp,
            command = savedState.get<String>(KEY_COMMAND).orEmpty(),
            argsText = savedState.get<String>(KEY_ARGS).orEmpty(),
            cwd = savedState.get<String>(KEY_CWD).orEmpty(),
            url = savedState.get<String>(KEY_URL).orEmpty(),
            enabled = savedState.get<Boolean>(KEY_ENABLED) ?: true,
            advancedOpen = savedState.get<Boolean>(KEY_ADVANCED) ?: false,
            env = keys.map { newRow(key = it) }.ifEmpty { listOf(newRow()) },
        )
    }

    private fun newRow(key: String = "", value: String = ""): EnvRow = EnvRow(nextEnvId++, key, value)

    private fun routeOf(h: HelperState): McpRoute = when (h) {
        HelperState.Ready -> McpRoute.Helper
        is HelperState.Unavailable -> McpRoute.Fallback
        HelperState.Idle, is HelperState.Starting -> McpRoute.Waiting
    }

    /** Reads the list for the settings path, the collision names and (edit) the server itself. */
    private suspend fun loadServers() {
        val listed = mcp.list()
        val response = listed.valueOrNull()
        existingNames = response?.servers?.map { it.name }?.toSet().orEmpty()
        _state.update { it.copy(settingsPath = response?.settingsPath) }
        val edit = mode as? McpEditorMode.Edit
        if (edit == null) {
            initial = FormFields.blank()
            if (!restored) persist(_state.value)
            recomputeDirty()
            return
        }
        val server = response?.find(edit.originalName)
        if (server == null) {
            val error = if (listed is Outcome.Failed) McpFormError.Failure(listed.failure) else McpFormError.NotFound
            _state.update { it.copy(loading = false, formError = error) }
            return
        }
        previous = server
        initial = FormFields.of(server)
        val keys = metadataKeys(server.metadata)
        _state.update { s ->
            val filled = if (restored) {
                s.copy(reenterEnv = !server.env.isNullOrEmpty())
            } else {
                s.copy(
                    name = initial.name,
                    type = initial.type,
                    transport = initial.transport,
                    command = initial.command,
                    argsText = initial.argsText,
                    cwd = initial.cwd,
                    env = initial.env.map { (k, v) -> newRow(k, v) }.ifEmpty { listOf(newRow()) },
                    url = initial.url,
                    headersText = initial.headersText,
                    enabled = initial.enabled,
                    // Desktop opens Advanced when cwd or metadata exist.
                    advancedOpen = initial.cwd.isNotEmpty() || keys.isNotEmpty(),
                )
            }
            filled.copy(
                loading = false,
                metadataKeys = keys,
                blockedInFallback = s.route == McpRoute.Fallback && server.hasEnvOrCwd,
            )
        }
        if (!restored) persist(_state.value)
        recomputeDirty()
    }

    // ---- field edits -------------------------------------------------------------------------------

    private fun edit(transform: (McpEditorUiState) -> McpEditorUiState) {
        if (_state.value.saving) return
        _state.update { s ->
            val next = transform(s)
            next.copy(dirty = FormFields.of(next) != initial, fieldError = null, formError = null)
        }
        persist(_state.value)
    }

    fun setName(value: String) = edit { it.copy(name = value) }

    /** Local → Remote keeps the previous remote choice, Streamable HTTP by default (desktop). */
    fun setType(value: ServerType) = edit { it.copy(type = value) }

    fun setTransport(value: RemoteTransport) = edit { it.copy(transport = value) }
    fun setCommand(value: String) = edit { it.copy(command = value) }
    fun setArgs(value: String) = edit { it.copy(argsText = value) }
    fun setCwd(value: String) = edit { it.copy(cwd = value) }
    fun setUrl(value: String) = edit { it.copy(url = value) }
    fun setHeaders(value: String) = edit { it.copy(headersText = value) }
    fun setEnabled(value: Boolean) = edit { it.copy(enabled = value) }

    fun setAdvancedOpen(open: Boolean) {
        _state.update { it.copy(advancedOpen = open) }
        savedState[KEY_ADVANCED] = open
    }

    fun setEnvKey(id: Long, key: String) = edit { s -> s.copy(env = s.env.map { if (it.id == id) it.copy(key = key) else it }) }

    fun setEnvValue(id: Long, value: String) = edit { s -> s.copy(env = s.env.map { if (it.id == id) it.copy(value = value) else it }) }

    /** The eye toggle: reveals one value (and turns on `FLAG_SECURE` while any is revealed). */
    fun toggleReveal(id: Long) = _state.update { s -> s.copy(env = s.env.map { if (it.id == id) it.copy(revealed = !it.revealed) else it }) }

    fun addEnv() = edit { s -> s.copy(env = s.env + newRow()) }

    /** "−" removes the row, or clears it when it is the last one (desktop `removeEnvEntry`). */
    fun removeEnv(id: Long) = edit { s ->
        if (s.env.size <= 1) s.copy(env = listOf(newRow())) else s.copy(env = s.env.filterNot { it.id == id })
    }

    private fun recomputeDirty() = _state.update { it.copy(dirty = FormFields.of(it) != initial) }

    private fun persist(s: McpEditorUiState) {
        savedState[KEY_INIT] = true
        savedState[KEY_NAME] = s.name
        savedState[KEY_TYPE] = s.type.name
        savedState[KEY_TRANSPORT] = s.transport.name
        savedState[KEY_COMMAND] = s.command
        savedState[KEY_ARGS] = s.argsText
        savedState[KEY_CWD] = s.cwd
        savedState[KEY_URL] = s.url
        savedState[KEY_ENABLED] = s.enabled
        savedState[KEY_ADVANCED] = s.advancedOpen
        savedState[KEY_ENV_KEYS] = ArrayList(s.env.map { it.key })
    }

    // ---- save ----------------------------------------------------------------------------------------

    /** The primary action, IME Done on the last field and Ctrl+Enter. */
    fun save() {
        val s = _state.value
        if (!s.canSave) return
        val input = validate(s) ?: return
        if (s.metadataKeys.isNotEmpty()) {
            _state.update { it.copy(metadataConfirm = true) }
            return
        }
        doSave(input)
    }

    fun confirmMetadataSave() {
        val s = _state.value
        _state.update { it.copy(metadataConfirm = false) }
        if (!s.canSave) return
        validate(s)?.let(::doSave)
    }

    fun dismissMetadataConfirm() = _state.update { it.copy(metadataConfirm = false) }

    /** `buildServerInput` + the collision guard; sets the field and box errors on failure. */
    private fun validate(s: McpEditorUiState): McpServerForm? {
        val form = toForm(s, includeEnvAndCwd = s.route != McpRoute.Fallback)
        val built = McpInputs.buildServerInput(form)
        val error = (built.exceptionOrNull() as? McpInputException)?.error
        if (error != null) {
            val field = when (error) {
                McpInputError.NameRequired -> McpField.Name
                McpInputError.CommandRequired -> McpField.Command
                McpInputError.UrlRequired -> McpField.Url
            }
            _state.update { it.copy(fieldError = field, formError = McpFormError.Validation(error)) }
            return null
        }
        val name = s.name.trim()
        val original = (mode as? McpEditorMode.Edit)?.originalName
        if (name != original && name in existingNames) {
            _state.update { it.copy(fieldError = McpField.Name, formError = McpFormError.Collision(name)) }
            return null
        }
        return form
    }

    private fun doSave(form: McpServerForm) {
        _state.update { it.copy(saving = true, formError = null, fieldError = null) }
        viewModelScope.launch {
            // CR §2.4 "Helper starting": wait for Ready or Unavailable before choosing the route.
            if (routeOf(helper.state.value) == McpRoute.Waiting) {
                withTimeoutOrNull(helperWaitMs) { helper.state.first { it is HelperState.Ready || it is HelperState.Unavailable } }
            }
            val fallback = helper.state.value !is HelperState.Ready
            if (fallback && previous?.hasEnvOrCwd == true) {
                _state.update { it.copy(saving = false, route = McpRoute.Fallback, blockedInFallback = true) }
                return@launch
            }
            val finalForm = if (fallback) form.copy(cwd = "", env = emptyList()) else form
            val input: McpServerInput = McpInputs.buildServerInput(finalForm).getOrNull() ?: run {
                _state.update { it.copy(saving = false) }
                return@launch
            }
            when (val result = mcp.save(input, previous)) {
                is McpRepository.WriteResult.Done -> {
                    initial = FormFields.of(_state.value)
                    _state.update { it.copy(saving = false, dirty = false) }
                    _events.tryEmit(McpEditorEvent.Close)
                }
                McpRepository.WriteResult.Unconfirmed -> _state.update { it.copy(saving = false, formError = McpFormError.Unconfirmed) }
                is McpRepository.WriteResult.Failed -> _state.update { it.copy(saving = false, formError = McpFormError.Failure(result.failure)) }
                McpRepository.WriteResult.BlockedInFallback -> _state.update { it.copy(saving = false, blockedInFallback = true) }
            }
        }
    }

    // ---- delete ------------------------------------------------------------------------------------

    fun requestDelete() {
        if (mode !is McpEditorMode.Edit || _state.value.saving) return
        _state.update { it.copy(deleteDialog = true, deleteError = null) }
    }

    fun dismissDelete() = _state.update { s -> if (s.deleting) s else s.copy(deleteDialog = false, deleteError = null) }

    fun confirmDelete() {
        val edit = mode as? McpEditorMode.Edit ?: return
        if (_state.value.deleting || !_state.value.kernelReady) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            when (val result = mcp.delete(edit.originalName)) {
                is McpRepository.WriteResult.Done -> {
                    _state.update { it.copy(deleting = false, deleteDialog = false, dirty = false) }
                    _events.tryEmit(McpEditorEvent.Close)
                }
                is McpRepository.WriteResult.Failed -> _state.update { it.copy(deleting = false, deleteError = result.failure) }
                McpRepository.WriteResult.Unconfirmed, McpRepository.WriteResult.BlockedInFallback ->
                    _state.update { it.copy(deleting = false, deleteError = RepoFailure.HelperLost) }
            }
        }
    }

    fun retryHelper() {
        viewModelScope.launch {
            helper.retry()
            // A server the fallback refused may now be editable through the helper.
            if (helper.state.value is HelperState.Ready) _state.update { it.copy(blockedInFallback = false) }
        }
    }

    override fun onCleared() {
        leaseScope.launch { withContext(NonCancellable) { helper.release(owner) } }
    }

    /** The comparable form (dirty check): everything the user can edit, env as ordered pairs. */
    internal data class FormFields(
        val name: String,
        val type: ServerType,
        val transport: RemoteTransport,
        val command: String,
        val argsText: String,
        val cwd: String,
        val env: List<Pair<String, String>>,
        val url: String,
        val headersText: String,
        val enabled: Boolean,
    ) {
        override fun toString(): String = "FormFields(name=$name, type=$type, env=${env.map { it.first }})"

        companion object {
            fun blank() = FormFields("", ServerType.Local, RemoteTransport.StreamableHttp, "", "", "", emptyList(), "", "", true)

            /** Desktop `createServerFormState(existing)`. */
            fun of(server: McpServer) = FormFields(
                name = server.name,
                type = if (server.transportType == McpTransportType.Stdio) ServerType.Local else ServerType.Remote,
                transport = if (server.transportType == McpTransportType.Sse) RemoteTransport.Sse else RemoteTransport.StreamableHttp,
                command = server.command.orEmpty(),
                argsText = server.args.orEmpty().joinToString(", "),
                cwd = server.cwd.orEmpty(),
                env = server.env.orEmpty().toList(),
                url = server.url.orEmpty(),
                headersText = McpInputs.stringifyKeyValuePairs(server.headers),
                enabled = !server.disabled,
            )

            fun of(s: McpEditorUiState) = FormFields(
                name = s.name,
                type = s.type,
                transport = s.transport,
                command = s.command,
                argsText = s.argsText,
                cwd = s.cwd,
                env = s.env.map { it.key to it.value }.filterNot { (k, v) -> k.isEmpty() && v.isEmpty() },
                url = s.url,
                headersText = s.headersText,
                enabled = s.enabled,
            )
        }
    }

    private fun toForm(s: McpEditorUiState, includeEnvAndCwd: Boolean) = McpServerForm(
        name = s.name,
        previousName = (mode as? McpEditorMode.Edit)?.originalName.orEmpty(),
        transportType = if (s.type == ServerType.Local) McpTransportType.Stdio else s.transport.type,
        command = s.command,
        argsText = s.argsText,
        cwd = if (includeEnvAndCwd) s.cwd else "",
        env = if (includeEnvAndCwd) s.env.map { it.key to it.value } else emptyList(),
        url = s.url,
        headersText = s.headersText,
        enabled = s.enabled,
        // The desktop never sends metadata; the kernel's upsert replaces the record (CR §2.2).
        metadata = null,
    )

    companion object {
        /** CR §2.4: Save waits this long for a starting helper. */
        const val HELPER_WAIT_MS = 20_000L

        private const val KEY_INIT = "mcp.init"
        private const val KEY_NAME = "mcp.name"
        private const val KEY_TYPE = "mcp.type"
        private const val KEY_TRANSPORT = "mcp.transport"
        private const val KEY_COMMAND = "mcp.command"
        private const val KEY_ARGS = "mcp.args"
        private const val KEY_CWD = "mcp.cwd"
        private const val KEY_URL = "mcp.url"
        private const val KEY_ENABLED = "mcp.enabled"
        private const val KEY_ADVANCED = "mcp.advanced"
        private const val KEY_ENV_KEYS = "mcp.envKeys"

        /** The keys of the entry's `metadata` (values are never shown, CR §2.4). */
        fun metadataKeys(metadata: JsonElement?): List<String> = when (metadata) {
            null, JsonNull -> emptyList()
            is JsonObject -> metadata.keys.toList().ifEmpty { listOf("metadata") }
            else -> listOf("metadata")
        }
    }
}
