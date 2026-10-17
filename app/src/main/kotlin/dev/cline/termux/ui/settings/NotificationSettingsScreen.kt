package dev.cline.termux.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.SectionHeader
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/** What the Notifications controls do; the route binds them to the ViewModel and the system intents. */
data class NotificationSettingsActions(
    val onAllow: () -> Unit = {},
    /** "Check permission": the app's notification settings page. */
    val onCheckPermission: () -> Unit = {},
    val onNotify: (NotificationEventType, Boolean) -> Unit = { _, _ -> },
    val onSound: (NotificationEventType, Boolean) -> Unit = { _, _ -> },
    /** "Open" on a blocked event: `ACTION_CHANNEL_NOTIFICATION_SETTINGS` for [channelId]. */
    val onOpenChannel: (channelId: String) -> Unit = {},
    /** The "Background connection" switch (docs/07 D11: `notifications.keepConnected`). */
    val onKeepConnected: (Boolean) -> Unit = {},
    /** "System notification settings" → `ACTION_APP_NOTIFICATION_SETTINGS`. */
    val onSystemSettings: () -> Unit = {},
)

/**
 * S3 body: description, the permission card (only while posting is not allowed), the four
 * events with Notify and a nested Sound switch, and the Delivery rows. One row anatomy on every
 * size class (no desktop table).
 */
@Composable
fun NotificationSettingsContent(
    state: NotificationSettingsUiState,
    actions: NotificationSettingsActions,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    SettingsList(contentPadding = contentPadding, modifier = modifier) {
        settingsItem(key = "description") { SettingsDescription(stringResource(R.string.notif_settings_description)) }
        if (state.showsPermissionCard) {
            settingsItem(key = "permission") {
                PermissionCard(state, actions, Modifier.padding(horizontal = Spacing.l, vertical = Spacing.s))
            }
        }

        settingsItem(key = "events-header") { SectionHeader(stringResource(R.string.notif_settings_events_header)) }
        state.events.forEach { event ->
            settingsItem(key = event.type.key) { EventRows(event, actions) }
        }

        settingsItem(key = "delivery-header") { SectionHeader(stringResource(R.string.notif_settings_delivery_header)) }
        settingsItem(key = "background") {
            PrefSwitchRow(
                headline = stringResource(R.string.notif_settings_background_connection),
                checked = state.keepConnected,
                onCheckedChange = actions.onKeepConnected,
                supporting = {
                    Text(stringResource(R.string.notif_settings_background_connection_supporting))
                    if (state.keepAliveDenied) {
                        Text(stringResource(R.string.notif_settings_keep_alive_denied), color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
        settingsItem(key = "system") {
            PrefNavRow(
                headline = stringResource(R.string.notif_settings_system),
                supporting = {
                    Text(
                        stringResource(
                            if (state.allowedBySystem) R.string.notif_settings_system_supporting_granted else R.string.notif_settings_system_supporting,
                        ),
                    )
                },
                trailingIcon = ClineIcons.OpenInNew,
                onClick = actions.onSystemSettings,
            )
        }
    }
}

/**
 * The permission card (S3.2): "Checking…" before the first read, "Cline needs permission to
 * show notifications." + "Allow notifications" before asking, "Notifications are blocked in
 * system settings." + "Check permission" when denied. A polite live region (S3.6).
 */
@Composable
private fun PermissionCard(state: NotificationSettingsUiState, actions: NotificationSettingsActions, modifier: Modifier = Modifier) {
    val denied = state.permission == NotificationPermission.Denied
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(Modifier.padding(Spacing.l), verticalAlignment = Alignment.Top) {
            Icon(
                ClineIcons.Warning,
                contentDescription = null,
                tint = if (denied) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                when (state.permission) {
                    NotificationPermission.Checking -> Text(
                        stringResource(R.string.notif_settings_checking),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    NotificationPermission.NotAsked -> {
                        Text(stringResource(R.string.notif_settings_permission_needed), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = actions.onAllow, enabled = !state.requesting) {
                            Text(stringResource(R.string.notif_settings_allow))
                        }
                    }
                    NotificationPermission.Denied -> {
                        Text(
                            stringResource(R.string.notif_settings_blocked),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        OutlinedButton(onClick = actions.onCheckPermission) {
                            Text(stringResource(R.string.notif_settings_check_permission))
                        }
                    }
                    NotificationPermission.Granted, NotificationPermission.NotRequired -> Unit
                }
            }
        }
    }
}

/** One event: the Notify row, the "Open" action when its active channel is blocked, the nested Sound row. */
@Composable
private fun EventRows(event: EventPrefUi, actions: NotificationSettingsActions) {
    val label = stringResource(event.type.labelRes())
    val description = stringResource(event.type.descriptionRes())
    val supporting = if (event.channelBlocked) stringResource(R.string.notif_settings_channel_blocked) else description
    Column {
        PrefSwitchRow(
            headline = label,
            checked = event.enabled,
            onCheckedChange = { actions.onNotify(event.type, it) },
            supporting = {
                Text(supporting, color = if (event.channelBlocked) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
            },
            contentDescription = stringResource(
                R.string.settings_cd_pair,
                stringResource(R.string.notif_settings_notify_cd, label),
                supporting,
            ),
        )
        if (event.channelBlocked) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { actions.onOpenChannel(event.activeChannel) }) {
                    Text(stringResource(R.string.notif_settings_channel_open))
                }
            }
        }
        PrefSwitchRow(
            headline = stringResource(R.string.notif_settings_sound),
            checked = event.sound,
            onCheckedChange = { actions.onSound(event.type, it) },
            enabled = event.enabled,
            contentDescription = stringResource(R.string.notif_settings_sound_cd, label),
            startInset = 56.dp,
        )
    }
}

private fun NotificationEventType.labelRes(): Int = when (this) {
    NotificationEventType.TaskCompletion -> R.string.notif_settings_task_completion_label
    NotificationEventType.ApprovalNeeded -> R.string.notif_settings_approval_needed_label
    NotificationEventType.QuestionAsked -> R.string.notif_settings_question_asked_label
    NotificationEventType.SessionError -> R.string.notif_settings_session_error_label
}

private fun NotificationEventType.descriptionRes(): Int = when (this) {
    NotificationEventType.TaskCompletion -> R.string.notif_settings_task_completion_description
    NotificationEventType.ApprovalNeeded -> R.string.notif_settings_approval_needed_description
    NotificationEventType.QuestionAsked -> R.string.notif_settings_question_asked_description
    NotificationEventType.SessionError -> R.string.notif_settings_session_error_description
}

// ------------------------------------------------------------------------------ previews

@LightDarkPreviews
@Composable
private fun NotificationSettingsGrantedPreview() {
    SettingsPreviewSurface { NotificationSettingsContent(SettingsPreviewData.notificationsGranted, NotificationSettingsActions(), PaddingValues(0.dp)) }
}

@LightDarkPreviews
@Composable
private fun NotificationSettingsCheckingPreview() {
    SettingsPreviewSurface { NotificationSettingsContent(SettingsPreviewData.notificationsChecking, NotificationSettingsActions(), PaddingValues(0.dp)) }
}

@LightDarkPreviews
@Composable
private fun NotificationSettingsNotAskedPreview() {
    SettingsPreviewSurface { NotificationSettingsContent(SettingsPreviewData.notificationsNotAsked, NotificationSettingsActions(), PaddingValues(0.dp)) }
}

@LightDarkPreviews
@Composable
private fun NotificationSettingsDeniedPreview() {
    SettingsPreviewSurface { NotificationSettingsContent(SettingsPreviewData.notificationsDenied, NotificationSettingsActions(), PaddingValues(0.dp)) }
}

@LightDarkPreviews
@Composable
private fun NotificationSettingsNotRequiredPreview() {
    SettingsPreviewSurface { NotificationSettingsContent(SettingsPreviewData.notificationsNotRequired, NotificationSettingsActions(), PaddingValues(0.dp)) }
}

@LightDarkPreviews
@Composable
private fun NotificationSettingsChannelBlockedPreview() {
    SettingsPreviewSurface { NotificationSettingsContent(SettingsPreviewData.notificationsChannelBlocked, NotificationSettingsActions(), PaddingValues(0.dp)) }
}

@LightDarkPreviews
@Composable
private fun NotificationSettingsKeepAliveDeniedPreview() {
    SettingsPreviewSurface { NotificationSettingsContent(SettingsPreviewData.notificationsKeepAliveDenied, NotificationSettingsActions(), PaddingValues(0.dp)) }
}
