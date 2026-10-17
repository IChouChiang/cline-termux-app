package dev.cline.termux.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.ThemeMode

/** Preview host for the settings pages: the brand theme on `surface`. */
@Composable
internal fun SettingsPreviewSurface(content: @Composable () -> Unit) {
    ClinePreviewTheme {
        Surface(color = MaterialTheme.colorScheme.surface) { content() }
    }
}

/** Sample states for the `@Preview`s of S1–S3 (DS §11: every listed state, light and dark). */
internal object SettingsPreviewData {
    private const val PORT = 25463

    val populated = SettingsHomeUiState(
        appearanceSummary = AppearanceSummary(ThemeMode.System, dynamicColor = true, fontSize = null),
        notifications = NotificationsSummary(enabledCount = 4, permission = SummaryPermission.Granted),
        kernel = KernelCopy.chip(KernelGate.Ready, PORT),
        kernelPort = PORT,
        kernelConnected = true,
        providers = ProvidersSummary(configured = 2, available = 41, fallback = false),
        account = AccountSummary.SignedIn("Ada Lovelace", null),
        installed = InstalledCounts(skills = 12, mcpServers = 3, plugins = 2, rules = 0),
        schedules = ScheduleCounts(schedules = 3, active = 2),
        telemetry = TelemetryRowState(enabled = true, loading = false),
        appVersion = "1.0.0",
        kernelVersion = "3.0.68",
        blocked = null,
        selected = null,
    )

    val firstRun = populated.copy(
        kernel = KernelCopy.chip(KernelGate.Down(KernelGate.DownVariant.Asleep, null), null),
        kernelPort = null,
        kernelConnected = false,
        providers = ProvidersSummary(configured = 0, available = 41, fallback = false),
        account = AccountSummary.SignedOut,
        installed = null,
        schedules = null,
        kernelVersion = null,
    )

    val loading = populated.copy(
        kernel = KernelCopy.chip(KernelGate.Busy(KernelGate.BusyKind.Connecting, null, slow = false), null),
        kernelPort = null,
        kernelConnected = false,
        providers = null,
        account = AccountSummary.Unknown,
        installed = null,
        schedules = null,
        telemetry = TelemetryRowState(),
        kernelVersion = null,
    )

    val offline = populated.copy(
        kernel = KernelCopy.chip(KernelGate.Down(KernelGate.DownVariant.Stopped, null), null),
        kernelPort = null,
        kernelConnected = false,
        installed = null,
        schedules = null,
        providers = ProvidersSummary(configured = 1, available = 18, fallback = true),
    )

    fun blocked(block: KernelBlock) = populated.copy(
        blocked = block,
        providers = null,
        account = AccountSummary.Unknown,
        telemetry = TelemetryRowState(enabled = null, loading = false),
        notifications = NotificationsSummary(enabledCount = 4, permission = SummaryPermission.Denied),
    )

    val telemetrySaving = populated.copy(telemetry = TelemetryRowState(enabled = false, loading = false, saving = true, startingHelper = true))

    val telemetryError = populated.copy(
        telemetry = TelemetryRowState(enabled = true, loading = false, error = TelemetryError.Message("Timed out waiting for the settings helper")),
    )

    val telemetryReadOnly = populated.copy(telemetry = TelemetryRowState(enabled = true, loading = false, writable = false))

    // ---------------------------------------------------------------------- notifications

    private val defaultEvents = NotificationEventType.entries.map { EventPrefUi(it, enabled = true, sound = false, channelBlocked = false) }

    val notificationsGranted = NotificationSettingsUiState(permission = NotificationPermission.Granted, events = defaultEvents)
    val notificationsChecking = NotificationSettingsUiState(permission = NotificationPermission.Checking, events = defaultEvents)
    val notificationsNotAsked = NotificationSettingsUiState(permission = NotificationPermission.NotAsked, events = defaultEvents)
    val notificationsDenied = NotificationSettingsUiState(permission = NotificationPermission.Denied, events = defaultEvents)
    val notificationsNotRequired = NotificationSettingsUiState(permission = NotificationPermission.NotRequired, events = defaultEvents)

    val notificationsChannelBlocked = notificationsGranted.copy(
        events = defaultEvents.map {
            when (it.type) {
                NotificationEventType.QuestionAsked -> it.copy(channelBlocked = true)
                NotificationEventType.TaskCompletion -> it.copy(sound = true)
                NotificationEventType.SessionError -> it.copy(enabled = false)
                else -> it
            }
        },
    )

    val notificationsKeepAliveDenied = notificationsGranted.copy(keepAliveDenied = true, keepConnected = true)
}
