package dev.cline.termux.ui.schedules

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.schedules.ScheduleType
import dev.cline.termux.data.schedules.formatScheduleDays
import dev.cline.termux.data.schedules.formatScheduleTime
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.text
import dev.cline.termux.ui.components.KernelBannerHost
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelAction
import dev.cline.termux.ui.navigation.EditorDialogWindow
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.Spacing
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** The editor's intents (bound to [ScheduleEditorViewModel] by the graph). */
@Immutable
class ScheduleEditorCallbacks(
    val onName: (String) -> Unit = {},
    val onType: (ScheduleType) -> Unit = {},
    val onDate: (LocalDate) -> Unit = {},
    val onTime: (Int, Int) -> Unit = { _, _ -> },
    val onToggleDay: (DayOfWeek) -> Unit = {},
    val onPrompt: (String) -> Unit = {},
    val onProvider: (String) -> Unit = {},
    val onModel: (String) -> Unit = {},
    val onWorkspace: (String) -> Unit = {},
    val onSystemPrompt: (String) -> Unit = {},
    val onTimeout: (String) -> Unit = {},
    val onTags: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onRetryLoad: () -> Unit = {},
    val onKernelAction: (KernelAction) -> Unit = {},
)

/**
 * C6 (customize-and-rest.md §4.3, §0.2 "Editors"): a full-screen editor on compact/medium
 * (close icon, title "Schedule", confirm `TextButton` in the app bar; 720dp column with the
 * two-column grid on medium) or the expanded `BasicAlertDialog` anatomy ([asDialog]: 560dp
 * surface, footer "Cancel" + confirm). [onRequestClose] is the close/cancel/back path; the
 * caller shows the discard confirm when the form is dirty.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleEditor(
    state: ScheduleEditorUiState,
    callbacks: ScheduleEditorCallbacks,
    asDialog: Boolean,
    is24Hour: Boolean,
) {
    val confirmLabel = when {
        state.saving && state.editing -> stringResource(R.string.schedule_editor_saving)
        state.saving -> stringResource(R.string.schedule_editor_creating)
        state.editing -> stringResource(R.string.schedule_editor_save)
        else -> stringResource(R.string.schedule_editor_create)
    }
    if (asDialog) {
        BoxWithConstraints {
            EditorDialogWindow(onTapOutside = callbacks.onClose, surfaceModifier = Modifier.heightIn(max = maxHeight * 0.9f)) {
                Column(Modifier.padding(top = Spacing.xxl)) {
                    Text(
                        stringResource(R.string.schedule_editor_title),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(horizontal = Spacing.xxl),
                    )
                    if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = Spacing.s))
                    Box(Modifier.weight(1f, fill = false)) {
                        EditorForm(state, callbacks, twoColumns = true, is24Hour = is24Hour, horizontalPadding = Spacing.xxl)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = Spacing.xxl, end = Spacing.xxl, top = Spacing.s, bottom = Spacing.xxl),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End),
                    ) {
                        TextButton(onClick = callbacks.onClose, enabled = !state.saving) { Text(stringResource(R.string.schedule_editor_cancel)) }
                        Button(onClick = callbacks.onSave, enabled = state.canSave) {
                            if (state.saving) {
                                InlineSpinner()
                                Spacer(Modifier.width(Spacing.s))
                            }
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    } else {
        Scaffold(
            snackbarHost = { SnackbarHost(LocalSnackbarHost.current) },
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(R.string.schedule_editor_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = {
                            IconButton(onClick = callbacks.onClose, enabled = !state.saving) {
                                Icon(ClineIcons.Close, contentDescription = stringResource(R.string.schedule_editor_close))
                            }
                        },
                        actions = {
                            TextButton(onClick = callbacks.onSave, enabled = state.canSave) {
                                if (state.saving) {
                                    InlineSpinner()
                                    Spacer(Modifier.width(Spacing.s))
                                }
                                Text(confirmLabel)
                            }
                        },
                    )
                    if (state.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
        ) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                val wide = maxWidth >= Spacing.gutterBreakpoint
                Box(Modifier.widthIn(max = Spacing.pageMaxWidth).fillMaxWidth()) {
                    EditorForm(state, callbacks, twoColumns = wide, is24Hour = is24Hour, horizontalPadding = if (wide) Spacing.gutterWide else Spacing.gutterCompact)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorForm(
    state: ScheduleEditorUiState,
    callbacks: ScheduleEditorCallbacks,
    twoColumns: Boolean,
    is24Hour: Boolean,
    horizontalPadding: androidx.compose.ui.unit.Dp,
) {
    val form = state.form
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = horizontalPadding, vertical = Spacing.l),
        verticalArrangement = Arrangement.spacedBy(Spacing.l),
    ) {
        KernelBannerHost(state.kernel, hasComposer = false, onAction = callbacks.onKernelAction)
        Text(
            stringResource(if (state.editing) R.string.schedule_editor_update_description else R.string.schedule_editor_create_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (form == null) {
            val error = state.loadError
            if (error != null) {
                InlineErrorCard(error.text(), actionLabel = stringResource(R.string.schedule_retry), onAction = callbacks.onRetryLoad)
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            return@Column
        }
        val enabled = !state.saving
        val errorField = state.error?.field
        OutlinedTextField(
            value = form.name,
            onValueChange = callbacks.onName,
            label = { Text(stringResource(R.string.schedule_editor_name)) },
            placeholder = { Text(stringResource(R.string.schedule_editor_name_placeholder)) },
            singleLine = true,
            isError = errorField == FormField.Name,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        ScheduleGroup(state, form, callbacks, enabled, errorField, is24Hour)
        OutlinedTextField(
            value = form.prompt,
            onValueChange = callbacks.onPrompt,
            label = { Text(stringResource(R.string.schedule_editor_prompt)) },
            minLines = 4,
            maxLines = 10,
            isError = errorField == FormField.Prompt,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        TwoColumns(twoColumns, first = { m ->
            ProviderField(state.providers, form.provider, callbacks.onProvider, enabled, errorField == FormField.Provider, m)
        }, second = { m ->
            ModelField(state.models, form.model, callbacks.onModel, enabled, errorField == FormField.Model, m)
        })
        WorkspaceField(state, form.workspaceRoot, callbacks.onWorkspace, enabled, errorField == FormField.Workspace)
        OutlinedTextField(
            value = form.systemPrompt,
            onValueChange = callbacks.onSystemPrompt,
            label = { Text(stringResource(R.string.schedule_editor_system_prompt)) },
            minLines = 2,
            maxLines = 8,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        TwoColumns(twoColumns, first = { m ->
            OutlinedTextField(
                value = form.timeoutSeconds,
                onValueChange = callbacks.onTimeout,
                label = { Text(stringResource(R.string.schedule_editor_timeout)) },
                placeholder = { Text(stringResource(R.string.schedule_editor_timeout_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = errorField == FormField.Timeout,
                enabled = enabled,
                modifier = m,
            )
        }, second = { m ->
            OutlinedTextField(
                value = form.tags,
                onValueChange = callbacks.onTags,
                label = { Text(stringResource(R.string.schedule_editor_tags)) },
                placeholder = { Text(stringResource(R.string.schedule_editor_tags_placeholder)) },
                singleLine = true,
                enabled = enabled,
                modifier = m,
            )
        })
        state.error?.let { error ->
            FormErrorBox(
                when (error) {
                    is EditorError.Validation -> stringResource(error.message)
                    is EditorError.Failure -> error.failure.text()
                },
            )
        }
    }
}

@Composable
private fun TwoColumns(
    twoColumns: Boolean,
    first: @Composable (Modifier) -> Unit,
    second: @Composable (Modifier) -> Unit,
) {
    if (twoColumns) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            first(Modifier.weight(1f))
            second(Modifier.weight(1f))
        }
    } else {
        first(Modifier.fillMaxWidth())
        second(Modifier.fillMaxWidth())
    }
}

/** "Schedule" group: Frequency, Date (Once), Time, Days (Weekly), the current-cron note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleGroup(
    state: ScheduleEditorUiState,
    form: ScheduleFormState,
    callbacks: ScheduleEditorCallbacks,
    enabled: Boolean,
    errorField: FormField?,
    is24Hour: Boolean,
) {
    var datePicker by rememberSaveable { mutableStateOf(false) }
    var timePicker by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(stringResource(R.string.schedule_editor_schedule), style = MaterialTheme.typography.titleSmall)
        OutlinedCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                state.currentCron?.let { cron ->
                    Text(
                        stringResource(R.string.schedule_editor_current_cron, cron),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FrequencyField(form.type, callbacks.onType, enabled)
                val timeText = formatScheduleTime(form.hour.toString(), form.minute.toString(), locale, is24Hour)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
                    if (form.type == ScheduleType.Once) {
                        val dateText = form.date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                        PickerField(
                            label = stringResource(R.string.schedule_editor_date),
                            value = dateText,
                            a11y = stringResource(R.string.schedule_editor_date_a11y, dateText),
                            icon = ClineIcons.CalendarMonth,
                            isError = errorField == FormField.Date || errorField == FormField.Time,
                            enabled = enabled,
                            onOpen = { datePicker = true },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    PickerField(
                        label = stringResource(R.string.schedule_editor_time),
                        value = timeText,
                        a11y = stringResource(R.string.schedule_editor_time_a11y, timeText),
                        icon = ClineIcons.Schedule,
                        isError = errorField == FormField.Time,
                        enabled = enabled,
                        onOpen = { timePicker = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (form.type == ScheduleType.Weekly) {
            val resources = LocalResources.current
            val words = remember(resources) { scheduleWords(resources) }
            Text(stringResource(R.string.schedule_editor_days), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                val dayFull = stringArrayResource(R.array.schedule_day_full)
                DAY_CHIPS.forEachIndexed { index, day ->
                    val short = words.dayShort[index]
                    val selected = day in form.days
                    val full = dayFull[index]
                    // The chip's name is the full day; FilterChip itself reports checked / not checked.
                    FilterChip(
                        selected = selected,
                        onClick = { callbacks.onToggleDay(day) },
                        label = { Text(short) },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = full },
                    )
                }
            }
            // DS §8.1.3: the Days error is not colour-only; an error icon leads the helper text
            // and TalkBack announces the validation message as the text's error.
            val daysError = errorField == FormField.Days
            val daysErrorMessage = when (val error = state.error) {
                is EditorError.Validation -> stringResource(error.message)
                is EditorError.Failure -> error.failure.text()
                null -> null
            }?.takeIf { daysError }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                if (daysError) {
                    Icon(ClineIcons.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
                Text(
                    if (form.days.isEmpty()) stringResource(R.string.schedule_editor_choose_days) else formatScheduleDays(form.days.toCronDays(), words),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (daysError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (daysErrorMessage != null) Modifier.semantics { error(daysErrorMessage) } else Modifier,
                )
            }
        }
    }
    if (datePicker) {
        val minDate = state.minDate
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = form.date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = remember(minDate) {
                object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(minDate)

                    override fun isSelectableYear(year: Int): Boolean = year >= minDate.year
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { callbacks.onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    datePicker = false
                }) { Text(stringResource(R.string.schedule_editor_ok)) }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text(stringResource(R.string.schedule_editor_cancel)) } },
        ) { DatePicker(pickerState) }
    }
    if (timePicker) {
        val pickerState = rememberTimePickerState(initialHour = form.hour, initialMinute = form.minute, is24Hour = is24Hour)
        AlertDialog(
            onDismissRequest = { timePicker = false },
            title = { Text(stringResource(R.string.schedule_editor_time)) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(pickerState) } },
            confirmButton = {
                TextButton(onClick = {
                    callbacks.onTime(pickerState.hour, pickerState.minute)
                    timePicker = false
                }) { Text(stringResource(R.string.schedule_editor_ok)) }
            },
            dismissButton = { TextButton(onClick = { timePicker = false }) { Text(stringResource(R.string.schedule_editor_cancel)) } },
        )
    }
}

/** A read-only field that opens a picker (`Role.Button`, CD "Date, <value>, double-tap to change"). */
@Composable
private fun PickerField(
    label: String,
    value: String,
    a11y: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isError: Boolean,
    enabled: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(icon, contentDescription = null) },
            isError = isError,
            enabled = enabled,
            singleLine = true,
            interactionSource = interaction,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        // The click target over the whole field: touch, TalkBack double-tap, keyboard and
        // switch access all reach [onOpen] through one ACTION_CLICK.
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = a11y },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrequencyField(type: ScheduleType, onType: (ScheduleType) -> Unit, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        ScheduleType.Daily to stringResource(R.string.schedule_editor_daily),
        ScheduleType.Weekly to stringResource(R.string.schedule_editor_weekly),
        ScheduleType.Once to stringResource(R.string.schedule_editor_once),
    )
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = labels.getValue(type),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.schedule_editor_frequency)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            enabled = enabled,
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(ScheduleType.Daily, ScheduleType.Weekly, ScheduleType.Once).forEach { option ->
                DropdownMenuItem(text = { Text(labels.getValue(option)) }, onClick = {
                    onType(option)
                    expanded = false
                })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderField(options: List<String>, value: String, onPick: (String) -> Unit, enabled: Boolean, isError: Boolean, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.schedule_editor_provider)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            enabled = enabled,
            isError = isError,
            singleLine = true,
            textStyle = ClineMono.body,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (options.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.schedule_editor_no_providers)) }, onClick = {}, enabled = false)
            }
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option, style = ClineMono.body) }, onClick = {
                    onPick(option)
                    expanded = false
                })
            }
        }
    }
}

/** Model: an editable dropdown; typing filters and a typed id not in the list is accepted (§4.6). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelField(options: List<String>, value: String, onChange: (String) -> Unit, enabled: Boolean, isError: Boolean, modifier: Modifier) {
    var expanded by remember { mutableStateOf(false) }
    var filtering by remember { mutableStateOf(false) }
    val shown = if (filtering && value.isNotBlank()) options.filter { it.contains(value.trim(), ignoreCase = true) } else options
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                filtering = true
                expanded = true
                onChange(it)
            },
            label = { Text(stringResource(R.string.schedule_editor_model)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            enabled = enabled,
            isError = isError,
            singleLine = true,
            textStyle = ClineMono.body,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable, enabled),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = {
            expanded = false
            filtering = false
        }) {
            if (shown.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(R.string.schedule_editor_no_models)) }, onClick = {}, enabled = false)
            }
            shown.forEach { option ->
                DropdownMenuItem(text = { Text(option, style = ClineMono.body, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = {
                    onChange(option)
                    expanded = false
                    filtering = false
                })
            }
        }
    }
}

/** Workspace: free text plus "Choose workspace" (known workspaces); locked on an Origin-only connection. */
@Composable
private fun WorkspaceField(state: ScheduleEditorUiState, value: String, onChange: (String) -> Unit, enabled: Boolean, isError: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val locked = state.homeOnly != null
    val choose = stringResource(R.string.schedule_editor_choose_workspace)
    // Only the path itself is LTR data (DS §8.2.6); the label, folder button and menu mirror.
    val pathStyle = ClineMono.body.copy(textDirection = TextDirection.Ltr)
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.schedule_editor_workspace)) },
        singleLine = true,
        readOnly = locked,
        enabled = enabled,
        isError = isError,
        textStyle = pathStyle,
        trailingIcon = if (locked) null else {
            {
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled && state.workspaces.isNotEmpty()) {
                        Icon(ClineIcons.FolderOpen, contentDescription = choose)
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        state.workspaces.forEach { path ->
                            DropdownMenuItem(
                                text = { Text(path, style = pathStyle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingIcon = { Icon(ClineIcons.Folder, contentDescription = null) },
                                onClick = {
                                    onChange(path)
                                    menu = false
                                },
                            )
                        }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** "Discard changes?" (Android): Keep editing / Discard. */
@Composable
fun DiscardScheduleDialog(onKeepEditing: () -> Unit, onDiscard: () -> Unit) {
    dev.cline.termux.ui.components.ConfirmDialog(
        title = stringResource(R.string.schedule_discard_title),
        text = stringResource(R.string.schedule_discard_text),
        confirmLabel = stringResource(R.string.schedule_discard_confirm),
        dismissLabel = stringResource(R.string.schedule_discard_keep),
        onConfirm = onDiscard,
        onDismiss = onKeepEditing,
        destructive = true,
    )
}

// ------------------------------------------------------------------ previews

private fun previewForm(type: ScheduleType = ScheduleType.Once) = ScheduleFormState(
    name = "Daily code review",
    type = type,
    date = LocalDate.of(2026, 10, 5),
    provider = "cline",
    model = "anthropic/claude-sonnet-5",
    workspaceRoot = "/data/data/com.termux/files/home/app",
    timeoutSeconds = "3600",
    tags = "automation,review",
)

private val previewState = ScheduleEditorUiState(
    form = previewForm(),
    providers = listOf("cline", "anthropic", "openrouter"),
    models = listOf("anthropic/claude-sonnet-5", "anthropic/claude-haiku-5"),
    workspaces = listOf("/data/data/com.termux/files/home/app", "/data/data/com.termux/files/home"),
    minDate = LocalDate.of(2026, 10, 4),
)

@LightDarkPreviews
@Composable
private fun ScheduleEditorCreatePreview() {
    ClinePreviewTheme { ScheduleEditor(previewState, ScheduleEditorCallbacks(), asDialog = false, is24Hour = false) }
}

@LightDarkPreviews
@Composable
private fun ScheduleEditorWeeklyValidationPreview() {
    ClinePreviewTheme {
        ScheduleEditor(
            previewState.copy(form = previewForm(ScheduleType.Weekly).copy(days = emptySet()), error = EditorError.Validation(R.string.schedule_error_no_weekday, FormField.Days), dirty = true),
            ScheduleEditorCallbacks(),
            asDialog = false,
            is24Hour = false,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleEditorEditSavingPreview() {
    ClinePreviewTheme {
        ScheduleEditor(
            previewState.copy(form = previewForm(ScheduleType.Daily), editing = true, currentCron = "*/15 * * * *", saving = true),
            ScheduleEditorCallbacks(),
            asDialog = false,
            is24Hour = true,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleEditorKernelDownPreview() {
    ClinePreviewTheme {
        ScheduleEditor(
            previewState.copy(kernel = KernelGate.Down(KernelGate.DownVariant.Stopped, null), homeOnly = "/data/data/com.termux/files/home"),
            ScheduleEditorCallbacks(),
            asDialog = false,
            is24Hour = false,
        )
    }
}

@LightDarkPreviews
@Composable
private fun ScheduleEditorLoadErrorPreview() {
    ClinePreviewTheme {
        ScheduleEditor(
            ScheduleEditorUiState(editing = true, loadError = dev.cline.termux.data.customize.RepoFailure.HubTimeout),
            ScheduleEditorCallbacks(),
            asDialog = false,
            is24Hour = false,
        )
    }
}

@androidx.compose.ui.tooling.preview.Preview(name = "Expanded dialog", widthDp = 1244, heightDp = 800)
@androidx.compose.ui.tooling.preview.Preview(name = "Expanded dialog (dark)", widthDp = 1244, heightDp = 800, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ScheduleEditorDialogPreview() {
    ClinePreviewTheme {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ScheduleEditor(
                previewState.copy(form = previewForm(ScheduleType.Weekly), error = EditorError.Failure(dev.cline.termux.data.customize.RepoFailure.Hub("schedule_command_failed", "schedule cwd is outside the client workspace scope"), FormField.Workspace)),
                ScheduleEditorCallbacks(),
                asDialog = true,
                is24Hour = false,
            )
        }
    }
}

@LightDarkPreviews
@Composable
private fun DiscardScheduleDialogPreview() {
    ClinePreviewTheme { DiscardScheduleDialog(onKeepEditing = {}, onDiscard = {}) }
}
