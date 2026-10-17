package dev.cline.termux.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.cline.termux.R
import dev.cline.termux.data.session.CompactionMode
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.ui.components.ConfirmDialog
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.components.KernelBlockAction
import dev.cline.termux.ui.components.KernelBlockedCard
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.SectionHeader
import dev.cline.termux.ui.components.StatusDot
import dev.cline.termux.ui.components.copyPluralStringResource
import dev.cline.termux.ui.approval.approvalWriteFailureText
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ThemeMode
import dev.cline.termux.ui.theme.Spacing

/** What the S1 list does on a tap; the route maps each to navigation (SP §0.2). */
@Immutable
data class SettingsHomeActions(
    val onSection: (SettingsSection) -> Unit = {},
    val onTelemetry: (Boolean) -> Unit = {},
    /** "Replay" confirmed in the New user experience dialog. */
    val onReplayOnboarding: () -> Unit = {},
    val onDiagnostics: () -> Unit = {},
    val onBlockAction: (KernelBlockAction) -> Unit = {},
    /** Context compaction picked in its dialog (A11 row 2). */
    val onCompaction: (CompactionMode) -> Unit = {},
    /** The Kernel group's "Auto-approve all" switch (decided A1). */
    val onAutoApprove: (Boolean) -> Unit = {},
    /** A11.9: the Message details dialog picked a mode. */
    val onMessageDetails: (MessageDetails) -> Unit = {},
)

/**
 * The S1 list (compact/medium page body and the expanded list pane): optional page
 * KernelBanner, description, the blocked card (item 0 above "App"), then the groups App /
 * Kernel / Models / Customize / Privacy / Support. [highlight] marks the expanded detail
 * pane's section with the `secondaryContainer` indicator; Telemetry, New user experience and
 * Diagnostics act in place and are never highlighted.
 */
@Composable
fun SettingsHomeList(
    state: SettingsHomeUiState,
    actions: SettingsHomeActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    highlight: SettingsSection? = null,
    permissionPermanentlyDenied: Boolean = false,
    banner: (@Composable () -> Unit)? = null,
) {
    var confirmReplay by rememberSaveable { mutableStateOf(false) }
    var chooseDetails by rememberSaveable { mutableStateOf(false) }
    SettingsList(contentPadding = contentPadding, modifier = modifier) {
        if (banner != null) settingsItem(key = "banner") { banner() }
        settingsItem(key = "description") { SettingsDescription(stringResource(R.string.settings_description)) }
        state.blocked?.let { block ->
            settingsItem(key = "blocked") {
                KernelBlockedCard(
                    block = block,
                    onAction = actions.onBlockAction,
                    permissionPermanentlyDenied = permissionPermanentlyDenied,
                    modifier = Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s),
                )
            }
        }

        settingsItem(key = "app") { SectionHeader(stringResource(R.string.settings_group_app)) }
        settingsItem(key = SettingsSection.Appearance.key) {
            PrefNavRow(
                headline = stringResource(R.string.settings_appearance),
                icon = ClineIcons.Palette,
                supporting = { Summary(appearanceText(state.appearanceSummary)) },
                selected = highlight == SettingsSection.Appearance,
                onClick = { actions.onSection(SettingsSection.Appearance) },
            )
        }
        settingsItem(key = SettingsSection.Notifications.key) {
            PrefNavRow(
                headline = stringResource(R.string.settings_notifications),
                icon = ClineIcons.Notifications,
                supporting = {
                    if (state.notifications.blocked) {
                        Summary(stringResource(R.string.notif_settings_blocked), error = true)
                    } else {
                        Summary(notificationsText(state.notifications))
                    }
                },
                selected = highlight == SettingsSection.Notifications,
                onClick = { actions.onSection(SettingsSection.Notifications) },
            )
        }
        settingsItem(key = "message-details") {
            PrefNavRow(
                headline = stringResource(R.string.message_details_title),
                icon = ClineIcons.Chat,
                supporting = { Summary(messageDetailsLabel(state.messageDetails)) },
                trailingIcon = null,
                onClick = { chooseDetails = true },
            )
        }

        settingsItem(key = "kernel-header") { SectionHeader(stringResource(R.string.settings_group_kernel)) }
        settingsItem(key = SettingsSection.Kernel.key) {
            val line1 = state.kernel.line1.resolve()
            val line2 = state.kernel.line2?.resolve()
            val spoken = if (state.kernelConnected && state.kernelPort != null) {
                stringResource(R.string.settings_kernel_connected_a11y, state.kernelPort.toString())
            } else if (line2 != null) {
                stringResource(R.string.settings_cd_pair, line1, line2)
            } else {
                line1
            }
            PrefNavRow(
                headline = stringResource(R.string.settings_kernel),
                icon = ClineIcons.AppLogo,
                supporting = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(tone = state.kernel.tone, pulsing = state.kernel.pulsing, boxSize = 16.dp)
                        Spacer(Modifier.width(Spacing.xs))
                        Summary(line1)
                    }
                },
                spokenSupporting = spoken,
                selected = highlight == SettingsSection.Kernel,
                onClick = { actions.onSection(SettingsSection.Kernel) },
            )
        }
        settingsItem(key = "auto-approve") { AutoApproveRow(state.autoApprove, actions.onAutoApprove) }

        settingsItem(key = "models-header") { SectionHeader(stringResource(R.string.settings_group_models)) }
        settingsItem(key = SettingsSection.Providers.key) {
            val text = providersText(state.providers)
            PrefNavRow(
                headline = stringResource(R.string.settings_providers),
                icon = ClineIcons.Power,
                supporting = text?.let { { Summary(it) } },
                onClick = { actions.onSection(SettingsSection.Providers) },
            )
        }
        settingsItem(key = SettingsSection.Account.key) {
            val text = accountText(state.account)
            PrefNavRow(
                headline = stringResource(R.string.settings_account),
                icon = ClineIcons.AccountCircle,
                supporting = text?.let { { Summary(it) } },
                selected = highlight == SettingsSection.Account,
                onClick = { actions.onSection(SettingsSection.Account) },
            )
        }
        settingsItem(key = "compaction") { CompactionRow(state.compaction, actions.onCompaction) }

        settingsItem(key = "customize-header") { SectionHeader(stringResource(R.string.settings_group_customize)) }
        settingsItem(key = SettingsSection.Installed.key) {
            val text = installedText(state.installed)
            PrefNavRow(
                headline = stringResource(R.string.settings_installed),
                icon = ClineIcons.Extension,
                supporting = text?.let { { Summary(it) } },
                onClick = { actions.onSection(SettingsSection.Installed) },
            )
        }
        settingsItem(key = SettingsSection.Marketplace.key) {
            PrefNavRow(
                headline = stringResource(R.string.settings_marketplace),
                icon = ClineIcons.Storefront,
                onClick = { actions.onSection(SettingsSection.Marketplace) },
            )
        }
        settingsItem(key = SettingsSection.Schedules.key) {
            val text = schedulesText(state.schedules)
            PrefNavRow(
                headline = stringResource(R.string.settings_schedules),
                icon = ClineIcons.Schedule,
                supporting = text?.let { { Summary(it) } },
                onClick = { actions.onSection(SettingsSection.Schedules) },
            )
        }

        settingsItem(key = "privacy-header") { SectionHeader(stringResource(R.string.settings_group_privacy)) }
        settingsItem(key = "telemetry") { TelemetryRow(state.telemetry, actions.onTelemetry) }

        settingsItem(key = "support-header") { SectionHeader(stringResource(R.string.settings_group_support)) }
        settingsItem(key = "nux") {
            PrefNavRow(
                headline = stringResource(R.string.settings_nux),
                icon = ClineIcons.RestartAlt,
                supporting = { Summary(stringResource(R.string.settings_nux_supporting), maxLines = Int.MAX_VALUE) },
                trailingIcon = null,
                onClick = { confirmReplay = true },
            )
        }
        settingsItem(key = "diagnostics") {
            PrefNavRow(
                headline = stringResource(R.string.settings_diagnostics),
                icon = ClineIcons.Share,
                supporting = { Summary(stringResource(R.string.settings_diagnostics_supporting), maxLines = Int.MAX_VALUE) },
                trailingIcon = null,
                onClickLabel = stringResource(R.string.settings_diagnostics_action),
                onClick = actions.onDiagnostics,
            )
        }
        settingsItem(key = SettingsSection.About.key) {
            val version = state.kernelVersion?.let { stringResource(R.string.settings_about_versions, state.appVersion, it) }
                ?: stringResource(R.string.settings_about_version, state.appVersion)
            PrefNavRow(
                headline = stringResource(R.string.settings_about),
                icon = ClineIcons.Info,
                supporting = { Summary(version) },
                selected = highlight == SettingsSection.About,
                onClick = { actions.onSection(SettingsSection.About) },
            )
        }
    }

    if (confirmReplay) {
        ConfirmDialog(
            title = stringResource(R.string.settings_nux_dialog_title),
            text = stringResource(R.string.settings_nux_dialog_text),
            confirmLabel = stringResource(R.string.settings_nux_replay),
            onConfirm = {
                confirmReplay = false
                actions.onReplayOnboarding()
            },
            onDismiss = { confirmReplay = false },
        )
    }
    if (chooseDetails) {
        MessageDetailsDialog(
            current = state.messageDetails,
            onSelect = { mode ->
                chooseDetails = false
                if (mode != state.messageDetails) actions.onMessageDetails(mode)
            },
            onDismiss = { chooseDetails = false },
        )
    }
}

@Composable
internal fun messageDetailsLabel(mode: MessageDetails): String = stringResource(
    when (mode) {
        MessageDetails.Brief -> R.string.message_details_brief
        MessageDetails.Verbose -> R.string.message_details_verbose
    },
)

/**
 * A11.9 "Message details" (the terminal's Brief / Verbose setting): a single-choice dialog;
 * picking an option applies it and closes, Cancel or a tap outside keeps the current one.
 */
@Composable
private fun MessageDetailsDialog(current: MessageDetails, onSelect: (MessageDetails) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // As ConfirmDialog: One UI's dialog window has no side inset, so keep the M3 margin here.
        modifier = Modifier.padding(horizontal = DialogSideMargin),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text(stringResource(R.string.message_details_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                MessageDetails.entries.forEach { mode ->
                    MessageDetailsOption(
                        label = messageDetailsLabel(mode),
                        supporting = stringResource(messageDetailsSupporting(mode)),
                        selected = mode == current,
                        onClick = { onSelect(mode) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.components_cancel)) } },
    )
}

/** Each option's one-line explanation under its label. */
@StringRes
private fun messageDetailsSupporting(mode: MessageDetails): Int = when (mode) {
    MessageDetails.Brief -> R.string.message_details_brief_supporting
    MessageDetails.Verbose -> R.string.message_details_verbose_supporting
}

/** One radio option; the whole row (at least 56dp) is the target, `Role.RadioButton`. */
@Composable
private fun MessageDetailsOption(label: String, supporting: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.m))
        Column(Modifier.padding(vertical = Spacing.xs)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val DialogSideMargin = 24.dp

/** S1.2 Telemetry row: inline switch, saving spinner, "Starting settings helper...", rollback error. */
@Composable
private fun TelemetryRow(state: TelemetryRowState, onToggle: (Boolean) -> Unit) {
    val supporting = when {
        state.startingHelper -> stringResource(R.string.settings_telemetry_starting)
        !state.writable -> stringResource(R.string.settings_telemetry_read_only)
        else -> stringResource(R.string.settings_telemetry_supporting)
    }
    val error = state.error?.let { error ->
        val detail = when (error) {
            is TelemetryError.Message -> error.text
            TelemetryError.HelperUnavailable -> stringResource(R.string.components_helper_unavailable_title)
        }
        stringResource(R.string.settings_telemetry_error, detail)
    }
    PrefSwitchRow(
        headline = stringResource(R.string.settings_telemetry),
        icon = ClineIcons.Analytics,
        checked = state.enabled ?: false,
        onCheckedChange = onToggle,
        enabled = state.switchEnabled || state.saving,
        saving = state.saving,
        supporting = {
            Summary(supporting, maxLines = Int.MAX_VALUE)
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        },
    )
}

/**
 * "Auto-approve all" (decided A1; auto-approve-control.md §4): the kernel's global setting,
 * shared with the terminal. Disabled with "Start kernel to change this." until it has been read
 * or while Termux commands cannot run; the pending sentence while an open session's run still
 * auto-approves; a failed write stays under the switch.
 */
@Composable
private fun AutoApproveRow(state: AutoApproveRowState, onToggle: (Boolean) -> Unit) {
    val resources = LocalResources.current
    val supporting = if (state.enabled || state.saving) {
        stringResource(R.string.settings_auto_approve_supporting)
    } else {
        stringResource(R.string.approval_mode_disabled)
    }
    val error = state.error?.let { approvalWriteFailureText(resources, it.turningOn, it.failure) }
    PrefSwitchRow(
        headline = stringResource(R.string.settings_auto_approve),
        icon = ClineIcons.Approval.Auto,
        checked = state.checked,
        onCheckedChange = { if (!state.saving) onToggle(it) },
        enabled = state.enabled || state.saving,
        saving = state.saving,
        supporting = {
            Summary(supporting, maxLines = Int.MAX_VALUE)
            if (state.pending) Summary(stringResource(R.string.settings_auto_approve_pending), maxLines = Int.MAX_VALUE)
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                )
            }
        },
    )
}

/** A summary line that fades in when its source answers (S1.4 "Loading"). */
@Composable
private fun Summary(text: String, error: Boolean = false, maxLines: Int = 2) {
    AnimatedContent(
        targetState = text,
        transitionSpec = { fadeIn(tween(ClineMotion.Short3)) togetherWith fadeOut(tween(ClineMotion.Short2)) },
        label = "summary",
    ) { value ->
        Text(
            value,
            maxLines = maxLines,
            color = if (error) MaterialTheme.colorScheme.error else Color.Unspecified,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------------- summary copy

@Composable
private fun join(parts: List<String>): String {
    val resources = LocalResources.current
    return parts.reduce { acc, part -> resources.getString(R.string.settings_summary_join, acc, part) }
}

@Composable
internal fun themeLabel(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.System -> R.string.appearance_theme_system
        ThemeMode.Light -> R.string.appearance_theme_light
        ThemeMode.Dark -> R.string.appearance_theme_dark
    },
)

@Composable
private fun appearanceText(summary: AppearanceSummary): String = join(
    buildList {
        add(themeLabel(summary.theme))
        if (summary.dynamicColor) add(stringResource(R.string.settings_appearance_dynamic))
        summary.fontSize?.let { add(stringResource(R.string.settings_appearance_font_size, it)) }
    },
)

@Composable
private fun notificationsText(summary: NotificationsSummary): String =
    if (summary.enabledCount > 0) {
        copyPluralStringResource(R.plurals.settings_notifications_on, summary.enabledCount, summary.enabledCount)
    } else {
        stringResource(R.string.settings_notifications_off)
    }

@Composable
private fun providersText(summary: ProvidersSummary?): String? = when {
    summary == null -> null
    summary.none -> stringResource(R.string.settings_providers_none)
    summary.fallback -> stringResource(R.string.settings_providers_fallback, summary.configured)
    else -> stringResource(R.string.settings_providers_counts, summary.configured, summary.available)
}

@Composable
private fun accountText(summary: AccountSummary): String? = when (summary) {
    AccountSummary.Unknown -> null
    is AccountSummary.SignedIn -> join(listOf(summary.name, summary.scope ?: stringResource(R.string.settings_account_personal)))
    AccountSummary.SignedInUnknown -> stringResource(R.string.settings_account_signed_in)
    AccountSummary.SignedOut -> stringResource(R.string.settings_account_signed_out)
    AccountSummary.Waiting -> stringResource(R.string.settings_account_waiting)
}

@Composable
private fun installedText(counts: InstalledCounts?): String? = when {
    counts == null -> null
    counts.isEmpty -> stringResource(R.string.settings_installed_none)
    else -> join(
        buildList {
            if (counts.skills > 0) add(copyPluralStringResource(R.plurals.settings_installed_skills, counts.skills, counts.skills))
            if (counts.mcpServers > 0) add(copyPluralStringResource(R.plurals.settings_installed_mcp, counts.mcpServers, counts.mcpServers))
            if (counts.plugins > 0) add(copyPluralStringResource(R.plurals.settings_installed_plugins, counts.plugins, counts.plugins))
            if (counts.rules > 0) add(copyPluralStringResource(R.plurals.settings_installed_rules, counts.rules, counts.rules))
        },
    )
}

@Composable
private fun schedulesText(counts: ScheduleCounts?): String? = when {
    counts == null -> null
    counts.schedules == 0 -> stringResource(R.string.settings_schedules_none)
    else -> join(
        listOf(
            copyPluralStringResource(R.plurals.settings_schedules_count, counts.schedules, counts.schedules),
            copyPluralStringResource(R.plurals.settings_schedules_active, counts.active, counts.active),
        ),
    )
}

// ------------------------------------------------------------------------------ previews

@LightDarkPreviews
@Composable
private fun SettingsHomePopulatedPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.populated, SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeFirstRunPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.firstRun, SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeLoadingPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.loading, SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeOfflinePreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.offline, SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeBlockedPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.blocked(KernelBlock.PermissionMissing), SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeTelemetrySavingPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.telemetrySaving, SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeTelemetryErrorPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.telemetryError, SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeTelemetryReadOnlyPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.telemetryReadOnly, SettingsHomeActions(), PaddingValues(0.dp))
    }
}

@LightDarkPreviews
@Composable
private fun SettingsHomeSelectedPreview() {
    SettingsPreviewSurface {
        SettingsHomeList(SettingsPreviewData.populated, SettingsHomeActions(), PaddingValues(0.dp), highlight = SettingsSection.Notifications)
    }
}
