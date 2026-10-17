package dev.cline.termux.ui.schedules

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.customize.Outcome
import dev.cline.termux.data.customize.RepoFailure
import dev.cline.termux.data.prefs.ModelSelection
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.data.schedules.CLINE_DEFAULT_MODEL_ID
import dev.cline.termux.data.schedules.ONE_TIME_SCHEDULE_CRON_PATTERN
import dev.cline.termux.data.schedules.ScheduleDraft
import dev.cline.termux.data.schedules.ScheduleFormException
import dev.cline.termux.data.schedules.ScheduleRecord
import dev.cline.termux.data.schedules.ScheduleType
import dev.cline.termux.data.schedules.SchedulesRepository
import dev.cline.termux.data.schedules.validateScheduleForm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the editor reads besides the schedules repository (CR §0.3, §4.8): the provider catalog
 * (`ProviderCatalogRepository`, cached or fallback; the editor never acquires the helper), the
 * remembered model selection (`ModelSelectionStore`) and the known workspaces (`WorkspaceStore`).
 */
interface ScheduleEditorSources {
    val catalog: Flow<CatalogState>

    /** Loads the catalog (fallback mode when the helper is not Ready); errors stay in [catalog]. */
    suspend fun loadCatalog()

    suspend fun rememberedSelection(): ModelSelection

    /** The workspace menu: the active folder, recents and the sessions' workspaces. */
    val workspaces: Flow<List<String>>

    /** The most recently used workspace, or null. */
    suspend fun recentWorkspace(): String?
}

/** The error box of C6 and the field it marks. */
sealed interface EditorError {
    val field: FormField?

    data class Validation(@param:StringRes val message: Int, override val field: FormField) : EditorError

    data class Failure(val failure: RepoFailure, override val field: FormField?) : EditorError
}

@Immutable
data class ScheduleEditorUiState(
    /** Null while the edited schedule is being read. */
    val form: ScheduleFormState? = null,
    val editing: Boolean = false,
    val loadError: RepoFailure? = null,
    /** The edited recurring schedule's cron (the "Current cron expression" note). */
    val currentCron: String? = null,
    val providers: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val workspaces: List<String> = emptyList(),
    val catalogLoading: Boolean = false,
    val saving: Boolean = false,
    val error: EditorError? = null,
    val dirty: Boolean = false,
    val kernel: KernelGate = KernelGate.Ready,
    /** Origin-only connection: the workspace is locked to this folder (§4.4 "Reduced authority"). */
    val homeOnly: String? = null,
    /** The earliest selectable once-date (today, or tomorrow just before midnight). */
    val minDate: LocalDate = LocalDate.MIN,
) {
    val canSave: Boolean get() = form != null && !saving && (kernel is KernelGate.Ready || kernel is KernelGate.DegradedNoCommands)
}

sealed interface EditorEvent {
    /** Saved: close C6 (the repository already refreshed C5 / C7). */
    data object Saved : EditorEvent
}

/**
 * C6 (customize-and-rest.md §4.5–4.9): create from scratch or a template, or edit. The form
 * lives in [savedState] (`@Parcelize` [ScheduleFormState]); validation is the desktop's
 * `submitCreateForm` order, then the Android checks (timeout format, home-only scope,
 * `test -d` of the workspace).
 */
class ScheduleEditorViewModel(
    private val repo: SchedulesRepository,
    private val sources: ScheduleEditorSources,
    kernelGate: StateFlow<KernelGate>,
    private val savedState: SavedStateHandle,
    private val scheduleId: String?,
    private val templateId: String?,
    private val homeOnly: String? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : ViewModel() {

    private data class Local(
        val loadError: RepoFailure? = null,
        val saving: Boolean = false,
        val error: EditorError? = null,
        val catalogLoading: Boolean = false,
    )

    private val local = MutableStateFlow(Local())
    private val form = MutableStateFlow(savedState.get<ScheduleFormState>(KEY_FORM))
    private var initial: ScheduleFormState? = savedState.get<ScheduleFormState>(KEY_INITIAL)
    private val editing = MutableStateFlow<ScheduleRecord?>(null)
    private var selection = ModelSelection()
    private var latestCatalog: CatalogState? = null
    private val events = Channel<EditorEvent>(Channel.BUFFERED)
    val effects: Flow<EditorEvent> = events.receiveAsFlow()

    val uiState: StateFlow<ScheduleEditorUiState> = combine(
        combine(form, editing, local) { f, e, l -> Triple(f, e, l) },
        sources.catalog,
        sources.workspaces,
        kernelGate,
    ) { (f, record, l), catalog, workspaces, gate ->
        val providers = providerOptions(catalog, f?.provider)
        ScheduleEditorUiState(
            form = f,
            editing = scheduleId != null,
            loadError = l.loadError,
            currentCron = record?.cronPattern?.takeIf { it != ONE_TIME_SCHEDULE_CRON_PATTERN && it.isNotBlank() },
            providers = providers,
            models = modelOptions(catalog, f?.provider.orEmpty(), f?.model),
            workspaces = (listOfNotNull(f?.workspaceRoot?.takeIf { it.isNotBlank() }) + workspaces).distinct(),
            catalogLoading = l.catalogLoading || catalog.loading,
            saving = l.saving,
            error = l.error,
            dirty = f != null && initial != null && f != initial,
            kernel = gate,
            homeOnly = homeOnly,
            minDate = OnceClamp.minimum(nowMs(), zone()).toLocalDate(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleEditorUiState(editing = scheduleId != null, kernel = kernelGate.value, homeOnly = homeOnly))

    init {
        viewModelScope.launch { sources.catalog.collect { latestCatalog = it } }
        viewModelScope.launch {
            selection = runCatching { sources.rememberedSelection() }.getOrDefault(ModelSelection())
            if (scheduleId == null) {
                if (form.value == null) startCreate()
            } else {
                loadEdited()
            }
        }
        viewModelScope.launch {
            local.update { it.copy(catalogLoading = true) }
            try {
                runCatching { sources.loadCatalog() }
            } finally {
                local.update { it.copy(catalogLoading = false) }
            }
        }
    }

    private suspend fun startCreate() {
        val provider = selection.lastProvider?.trim()?.ifEmpty { null } ?: DEFAULT_PROVIDER
        val model = selection.modelFor(provider)?.trim()?.ifEmpty { null } ?: CLINE_DEFAULT_MODEL_ID
        val workspace = homeOnly ?: runCatching { sources.recentWorkspace() }.getOrNull()?.ifBlank { null } ?: TERMUX_HOME
        val created = ScheduleFormState.create(nowMs(), zone(), provider, model, workspace, templateById(templateId))
        setInitial(created)
        setForm(created)
    }

    private suspend fun loadEdited() {
        val id = scheduleId ?: return
        local.update { it.copy(loadError = null) }
        val record = repo.state.value.overview?.schedules?.firstOrNull { it.scheduleId == id }
            ?: when (val out = repo.details(id)) {
                is Outcome.Ok -> out.value.schedule
                is Outcome.Failed -> {
                    local.update { it.copy(loadError = out.failure) }
                    return
                }
            }
        editing.value = record
        if (form.value == null) {
            val edited = ScheduleFormState.edit(record, nowMs(), zone())
            setInitial(edited)
            setForm(edited)
        }
    }

    /** "Retry" after the edited schedule could not be read. */
    fun retryLoad() {
        viewModelScope.launch { loadEdited() }
    }

    private fun setInitial(value: ScheduleFormState) {
        initial = value
        savedState[KEY_INITIAL] = value
    }

    private fun setForm(value: ScheduleFormState) {
        form.value = value
        savedState[KEY_FORM] = value
    }

    private inline fun edit(block: (ScheduleFormState) -> ScheduleFormState) {
        if (local.value.saving) return
        val current = form.value ?: return
        setForm(block(current))
    }

    // ------------------------------------------------------------------ field edits

    fun setName(value: String) = edit { it.copy(name = value) }

    /** Frequency switch keeps time and days (§4.6). */
    fun setType(type: ScheduleType) = edit { it.copy(type = type) }

    fun setDate(date: LocalDate) = edit { OnceClamp.withDate(it, date, nowMs(), zone()) }

    fun setTime(hour: Int, minute: Int) = edit { OnceClamp.withTime(it, hour, minute, nowMs(), zone()) }

    fun toggleDay(day: DayOfWeek) = edit { if (day in it.days) it.copy(days = it.days - day) else it.copy(days = it.days + day) }

    fun setPrompt(value: String) = edit { it.copy(prompt = value) }

    /** Desktop provider change: the remembered model of that provider if offered, else its first model. */
    fun setProvider(providerId: String) {
        val catalog = latestCatalog
        val offered = catalog?.let { modelOptions(it, providerId, null) }.orEmpty()
        val remembered = selection.modelFor(providerId)
        val model = remembered?.takeIf { it in offered } ?: offered.firstOrNull() ?: ""
        edit { it.copy(provider = providerId, model = model) }
    }

    fun setModel(value: String) = edit { it.copy(model = value) }

    fun setWorkspace(value: String) = edit { it.copy(workspaceRoot = value) }

    fun setSystemPrompt(value: String) = edit { it.copy(systemPrompt = value) }

    fun setTimeout(value: String) = edit { it.copy(timeoutSeconds = value) }

    fun setTags(value: String) = edit { it.copy(tags = value) }

    fun clearError() {
        local.update { it.copy(error = null) }
    }

    // ------------------------------------------------------------------ save

    /** "Create Schedule" / "Save Changes". */
    fun save() {
        val current = form.value ?: return
        if (local.value.saving) return
        val error = validate(current)
        if (error != null) {
            local.update { it.copy(error = error) }
            return
        }
        local.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                submit(current)
            } finally {
                local.update { it.copy(saving = false) }
            }
        }
    }

    private fun validate(current: ScheduleFormState): EditorError? {
        val existing = editing.value
        val result = validateScheduleForm(current.toForm(), existing?.cronPattern, nowMs(), zone())
        result.exceptionOrNull()?.let { e ->
            val formError = (e as? ScheduleFormException)?.error ?: return EditorError.Failure(RepoFailure.Malformed(e.message.orEmpty()), null)
            return EditorError.Validation(formError.message(), formError.field())
        }
        if (!isValidTimeout(current.timeoutSeconds)) return EditorError.Validation(R.string.schedule_error_timeout_invalid, FormField.Timeout)
        if (homeOnly != null && normalizePath(current.workspaceRoot) != normalizePath(homeOnly)) {
            return EditorError.Validation(R.string.schedule_error_home_only, FormField.Workspace)
        }
        return null
    }

    private suspend fun submit(current: ScheduleFormState) {
        val existing = editing.value
        if (scheduleId != null && existing == null) return
        val valid = validateScheduleForm(current.toForm(), existing?.cronPattern, nowMs(), zone()).getOrNull() ?: return
        if (repo.workspaceExists(valid.workspaceRoot) == false) {
            local.update { it.copy(error = EditorError.Validation(R.string.schedule_error_workspace_missing, FormField.Workspace)) }
            return
        }
        val draft = ScheduleDraft.of(current.toForm(), valid, existing, zone())
        val out = if (existing == null) repo.create(draft) else repo.update(existing, draft)
        when (out) {
            is Outcome.Ok -> {
                initial = form.value
                events.trySend(EditorEvent.Saved)
            }
            is Outcome.Failed -> {
                val field = if (out.failure.detail?.contains(WORKSPACE_SCOPE_ERROR, ignoreCase = true) == true) FormField.Workspace else null
                local.update { it.copy(error = EditorError.Failure(out.failure, field)) }
            }
        }
    }

    companion object {
        const val KEY_FORM = "scheduleForm"
        const val KEY_INITIAL = "scheduleFormInitial"
        const val DEFAULT_PROVIDER = "cline"

        /** The desktop's `get_process_context` workspace on the phone. */
        const val TERMUX_HOME = "/data/data/com.termux/files/home"

        /** `schedule_command_failed` text that points at the workspace field (§4.9). */
        const val WORKSPACE_SCOPE_ERROR = "outside the client workspace scope"

        private val DIGITS = Regex("^\\d+$")

        /** Empty, or a whole number of seconds above zero (Android rule over `parseOptionalPositiveInt`). */
        fun isValidTimeout(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return true
            return DIGITS.matches(trimmed) && (trimmed.toIntOrNull() ?: 0) > 0
        }

        fun normalizePath(path: String): String = path.trim().trimEnd('/').ifEmpty { "/" }

        /**
         * Provider choices (desktop `availableProviders`): enabled providers with chat models,
         * else the configured ones; the current value is kept in the list when missing.
         */
        fun providerOptions(catalog: CatalogState, current: String?): List<String> {
            val enabled = catalog.enabledProviderIds
            val base = enabled.ifEmpty { catalog.providers.filter { catalog.configured(it.id) }.map { it.id } }
            val keep = current?.trim()?.takeIf { it.isNotEmpty() && it !in base }
            return if (keep != null) base + keep else base
        }

        /** Model choices of [providerId]; the current model is kept in the list when missing. */
        fun modelOptions(catalog: CatalogState, providerId: String, current: String?): List<String> {
            val base = catalog.chatModels(providerId).map { it.id }.distinct()
            val keep = current?.trim()?.takeIf { it.isNotEmpty() && it !in base }
            return if (keep != null) base + keep else base
        }
    }
}
