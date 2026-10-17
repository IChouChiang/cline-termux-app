package dev.cline.termux.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.cline.termux.AppGraph
import dev.cline.termux.core.kernel.HubInfo
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.dashboard.HelperState
import dev.cline.termux.core.kernel.termux.ClineCommands
import dev.cline.termux.core.kernel.termux.GlobalSettingsView
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.data.approvals.ApprovalMode
import dev.cline.termux.data.approvals.ApprovalRefresh
import dev.cline.termux.data.approvals.ApprovalWriteResult
import dev.cline.termux.data.notifications.KeepAlive
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.data.notifications.NotificationSettings
import dev.cline.termux.data.prefs.MessageDetails
import dev.cline.termux.data.prefs.SignInPending
import dev.cline.termux.data.session.CompactionMode
import dev.cline.termux.data.providers.CatalogState
import dev.cline.termux.service.NotificationChannels
import dev.cline.termux.ui.components.KernelBlock
import dev.cline.termux.ui.theme.AppIcon
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * The app's adapters from [AppGraph] to the three settings ViewModels. Every kernel-side
 * member is touched lazily inside a flow or a suspend call on a background dispatcher, so a
 * screen never builds `graph.kernel` on Main (docs/07 §8, merge-0 note).
 */

/** Settings home over the app graph (S1.2 data sources). */
class GraphSettingsHomeSources(private val context: Context, private val graph: AppGraph) : SettingsHomeSources {
    private fun <T> lazyFlow(block: () -> Flow<T>): Flow<T> = flow { emitAll(block()) }.flowOn(Dispatchers.Default)

    override val appearance: Flow<AppearancePrefs> = lazyFlow { graph.prefs.appearance }
    override val notificationSettings: Flow<NotificationSettings> = lazyFlow { graph.notificationPrefs.settings }
    override val kernelGate: Flow<KernelGate> = lazyFlow { graph.kernel.gate }
    override val hubInfo: Flow<HubInfo?> = lazyFlow { graph.kernel.state.map { it.hubInfo } }
    override val catalog: Flow<CatalogState> = lazyFlow {
        graph.providerCatalog.start()
        graph.providerCatalog.state
    }
    override val account: Flow<Pair<String?, String?>> = lazyFlow { graph.prefs.account }
    override val signInPending: Flow<SignInPending?> = lazyFlow { graph.prefs.signInPending }
    override val helperState: Flow<HelperState> = lazyFlow { graph.settingsHelper.state }
    override val telemetryLocked: Flow<Boolean> = lazyFlow {
        combine(graph.prefs.helperUnsupported, graph.kernel.userStopped) { unsupported, stopped -> unsupported || stopped }
    }
    override val scheduleCounts: Flow<ScheduleCounts?> = lazyFlow {
        graph.schedules.state.map { state -> state.overview?.let(ScheduleCounts::of) }
    }
    override val installedInvalidations: Flow<Unit> = lazyFlow { graph.customize.invalidations }

    override fun notificationPermission(): SummaryPermission = NotificationAccess.summary(context)

    override fun termuxBlock(): KernelBlock? = when {
        !graph.termux.isTermuxInstalled().installed -> KernelBlock.TermuxMissing
        !graph.termux.hasPermission() -> KernelBlock.PermissionMissing
        else -> null
    }

    override suspend fun readTelemetryOptOut(): Boolean = withContext(Dispatchers.IO) {
        GlobalSettingsView.from(graph.termux.run(ClineCommands.readGlobalSettings())).telemetryOptOut ?: false
    }

    override suspend fun refreshPresence() {
        withContext(Dispatchers.IO) { graph.providerCatalog.refreshPresence() }
    }

    override suspend fun loadInstalled(force: Boolean): InstalledCounts? = withContext(Dispatchers.Default) {
        val workspace = graph.prefs.customizeWorkspaceRoot() ?: TermuxPaths.HOME
        graph.customize.load(workspace, force).snapshot?.let(InstalledCounts::of)
    }

    override suspend fun refreshSchedules() {
        withContext(Dispatchers.Default) { graph.schedules.refresh(silent = true) }
    }

    override suspend fun acquireHelper(owner: String) = withContext(Dispatchers.Default) { graph.settingsHelper.acquire(owner) }

    override suspend fun retryHelper() = withContext(Dispatchers.Default) { graph.settingsHelper.retry() }

    override fun releaseHelper(owner: String) {
        graph.appScope.launch { graph.settingsHelper.release(owner) }
    }

    override suspend fun awaitHelper(): HelperState = withContext(Dispatchers.Default) { graph.settingsHelper.awaitSettled() }

    override suspend fun setTelemetryOptOut(optOut: Boolean): Boolean = withContext(Dispatchers.Default) {
        val reply = graph.settingsHelper.desktopCommand(
            SET_TELEMETRY_OPT_OUT,
            buildJsonObject { put("telemetry_opt_out", JsonPrimitive(optOut)) },
        )
        TelemetryReply.optOut(reply, fallback = optOut)
    }

    override val compactionMode: Flow<CompactionMode?> = lazyFlow { graph.compactionMode.mode }

    override suspend fun refreshCompaction() {
        withContext(Dispatchers.IO) { graph.compactionMode.refresh() }
    }

    override suspend fun setCompactionMode(mode: CompactionMode): CompactionMode = withContext(Dispatchers.IO) {
        CompactionMode.of(graph.compactionMode.set(mode))
    }

    override val approvalMode: Flow<ApprovalMode> = lazyFlow { graph.approvalMode.mode }
    override val approvalWriting: Flow<Boolean> = lazyFlow { graph.approvalMode.writing }
    override val approvalPending: Flow<Boolean> = lazyFlow { graph.runBoundary.pendingSwitch }

    override suspend fun refreshApprovalMode() {
        withContext(Dispatchers.Default) { graph.approvalMode.refresh(ApprovalRefresh.SettingsOpen) }
    }

    override suspend fun setAutoApprove(auto: Boolean): ApprovalWriteResult = withContext(Dispatchers.Default) { graph.approvalMode.set(auto) }

    override val messageDetails: Flow<MessageDetails> = lazyFlow { graph.prefs.messageDetails }

    override suspend fun setMessageDetails(mode: MessageDetails) {
        withContext(Dispatchers.IO) { graph.prefs.setMessageDetails(mode) }
    }

    private companion object {
        const val SET_TELEMETRY_OPT_OUT = "set_telemetry_opt_out"
    }
}

/** Appearance over `AppPreferences` (DataStore `appearance.*`). */
class GraphAppearanceStore(private val graph: AppGraph) : AppearanceStore {
    override val appearance: Flow<AppearancePrefs> get() = graph.prefs.appearance
    override val appIcon: Flow<AppIcon> get() = graph.prefs.appIcon
    override suspend fun setThemeMode(mode: ThemeMode) {
        withContext(Dispatchers.IO) { graph.prefs.setThemeMode(mode) }
    }
    override suspend fun setDynamicColor(enabled: Boolean) {
        withContext(Dispatchers.IO) { graph.prefs.setDynamicColor(enabled) }
    }
    override suspend fun setFontSize(size: Int) {
        withContext(Dispatchers.IO) { graph.prefs.setFontSize(size) }
    }
    override suspend fun setAppIcon(icon: AppIcon) {
        withContext(Dispatchers.IO) { graph.prefs.setAppIcon(icon) }
    }
    override suspend fun setPendingIconDisable(component: String?) {
        withContext(Dispatchers.IO) { graph.prefs.setPendingIconDisable(component) }
    }
}

/** Notifications settings over `NotificationPrefs`, the keep-alive status and the system. */
class GraphNotificationSettingsSources(private val context: Context, private val graph: AppGraph) : NotificationSettingsSources {
    override val settings: Flow<NotificationSettings> get() = graph.notificationPrefs.settings
    override val keepAliveDenied: Flow<Boolean>
        get() = flow { emitAll(graph.keepAlive.status.map { it == KeepAlive.Denied }) }
            .onStart { emit(false) }
            .flowOn(Dispatchers.Default)

    override val keepConnected: Flow<Boolean> get() = graph.prefs.keepConnected

    override suspend fun setKeepConnected(enabled: Boolean) {
        withContext(Dispatchers.IO) { graph.prefs.setKeepConnected(enabled) }
    }

    override suspend fun setEnabled(type: NotificationEventType, enabled: Boolean) {
        withContext(Dispatchers.IO) { graph.notificationPrefs.setEnabled(type, enabled) }
    }

    override suspend fun setSound(type: NotificationEventType, sound: Boolean) {
        withContext(Dispatchers.IO) { graph.notificationPrefs.setSound(type, sound) }
    }

    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override fun appNotificationsEnabled(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()
    override fun postPermissionGranted(): Boolean = NotificationAccess.postPermissionGranted(context)
    override fun isChannelBlocked(channelId: String): Boolean = NotificationChannels.isBlocked(context, channelId)
}

/** The system notification switches (S3.2 permission flow). */
object NotificationAccess {
    fun postPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** S1 summary: API < 33 needs no runtime permission; otherwise the app can post or it cannot. */
    fun summary(context: Context): SummaryPermission {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        return when {
            !enabled -> SummaryPermission.Denied
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> SummaryPermission.NotRequired
            postPermissionGranted(context) -> SummaryPermission.Granted
            else -> SummaryPermission.Denied
        }
    }
}
