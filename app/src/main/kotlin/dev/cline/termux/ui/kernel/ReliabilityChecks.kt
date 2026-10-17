package dev.cline.termux.ui.kernel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.app.NotificationManagerCompat
import dev.cline.termux.core.kernel.termux.TermuxPaths
import dev.cline.termux.data.prefs.AppPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * The "Keep the kernel running" checklist (onboarding-kernel.md D.6) as pure rules over one
 * snapshot of system readings, so every status, count and visibility rule is JVM-tested.
 * The Android reads ([AndroidReliabilityProbe]) are all local calls; nothing here touches
 * the kernel (D.7).
 */
enum class ReliabilityItemId { TermuxBattery, ClineBattery, NeverSleeping, ChildProcess, Notifications, TermuxOverlay }

enum class ReliabilityGroup { Required, Recommended, Optional }

/** D.7: verified [Done] / [NotSet], or a self-check the user confirms ([SelfConfirmed]) or not yet ([SelfCheck]). */
enum class ItemStatus { Done, NotSet, SelfCheck, SelfConfirmed }

@Immutable
data class ReliabilityItemUi(
    val id: ReliabilityItemId,
    val group: ReliabilityGroup,
    val status: ItemStatus,
    /** Item 4 on API 34+: Developer options are off, so the toggle cannot be reached yet. */
    val developerOptionsOff: Boolean = false,
    /** Item 4 on API 31–33: no developer toggle exists; only the adb command works. */
    val legacyAdbOnly: Boolean = false,
    /** Item 2: the system "Let app always run in the background?" dialog can be requested directly. */
    val directRequest: Boolean = true,
) {
    /** Counts toward "N of M done" (self-checked items count once confirmed, D.6). */
    val done: Boolean get() = status == ItemStatus.Done || status == ItemStatus.SelfConfirmed

    /** The item shows the "I've done this" checkbox. */
    val selfCheck: Boolean get() = status == ItemStatus.SelfCheck || status == ItemStatus.SelfConfirmed
}

/** The phantom-process setting as read from `Settings.Global` (item 4). */
sealed interface PhantomSetting {
    /** The raw value: `"false"` = restrictions off; null (unset) or `"true"` = on. */
    data class Value(val raw: String?) : PhantomSetting

    /** Reading threw (non-readable key on this target SDK, open question 6): self-check. */
    data object Unreadable : PhantomSetting
}

/** One snapshot of everything D.6 verifies (re-read on every `ON_RESUME`). */
data class SystemReadings(
    val sdkInt: Int,
    val samsung: Boolean,
    val termuxUnrestricted: Boolean,
    val clineUnrestricted: Boolean,
    val phantomProcs: PhantomSetting,
    val developerOptionsOn: Boolean,
    val notificationsOn: Boolean,
    /** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is declared, so item 2's "Allow" opens the system dialog. */
    val canRequestIgnoreBattery: Boolean,
)

/** What E's "Background reliability" row shows: "%1$d of %2$d done" and the warning dot. */
@Immutable
data class ReliabilitySummary(val doneCount: Int, val countedTotal: Int, val requiredMissing: Boolean)

/** The D.6 table, D.4 and the counting rule. */
object ReliabilityRules {
    const val PHANTOM_PROCS_KEY = "settings_enable_monitor_phantom_procs"

    fun items(r: SystemReadings, confirmed: Set<ReliabilityItemId>): List<ReliabilityItemUi> = buildList {
        add(verified(ReliabilityItemId.TermuxBattery, ReliabilityGroup.Required, r.termuxUnrestricted))
        add(verified(ReliabilityItemId.ClineBattery, ReliabilityGroup.Required, r.clineUnrestricted).copy(directRequest = r.canRequestIgnoreBattery))
        if (r.samsung) add(selfCheck(ReliabilityItemId.NeverSleeping, ReliabilityGroup.Required, confirmed))
        if (r.sdkInt >= 31) {
            val legacy = r.sdkInt <= 33
            val base = when (val phantom = r.phantomProcs) {
                is PhantomSetting.Value -> verified(ReliabilityItemId.ChildProcess, ReliabilityGroup.Recommended, phantom.raw?.trim() == "false")
                PhantomSetting.Unreadable -> selfCheck(ReliabilityItemId.ChildProcess, ReliabilityGroup.Recommended, confirmed)
            }
            add(base.copy(legacyAdbOnly = legacy, developerOptionsOff = !legacy && !r.developerOptionsOn))
        }
        if (r.sdkInt >= 33) add(verified(ReliabilityItemId.Notifications, ReliabilityGroup.Recommended, r.notificationsOn))
        add(selfCheck(ReliabilityItemId.TermuxOverlay, ReliabilityGroup.Optional, confirmed))
    }

    /** Items that count: shown items in Required + Recommended (Optional is not counted, D.6). */
    fun counted(items: List<ReliabilityItemUi>): List<ReliabilityItemUi> = items.filter { it.group != ReliabilityGroup.Optional }

    fun summary(items: List<ReliabilityItemUi>): ReliabilitySummary {
        val counted = counted(items)
        return ReliabilitySummary(
            doneCount = counted.count { it.done },
            countedTotal = counted.size,
            requiredMissing = items.any { it.group == ReliabilityGroup.Required && !it.done },
        )
    }

    /** "All done": every counted item is done (D.6 screen state). */
    fun allDone(items: List<ReliabilityItemUi>): Boolean = counted(items).let { c -> c.isNotEmpty() && c.all { it.done } }

    /** D.4: the onboarding step is shown only when a verifiable item (1, 2, 5) is not done. */
    fun verifiableMissing(items: List<ReliabilityItemUi>): Boolean = items.any {
        it.id in VERIFIABLE && it.status == ItemStatus.NotSet
    }

    /** D.6 "populated": the first not-done item is expanded. */
    fun firstOpen(items: List<ReliabilityItemUi>): ReliabilityItemId? =
        items.firstOrNull { it.status == ItemStatus.NotSet || it.status == ItemStatus.SelfCheck }?.id

    /**
     * The items [before] showed as not done that [after] shows done (trial row 14): they collapse
     * like the items that were done already, even when the user had opened them. An item that
     * was not shown before is not among them.
     */
    fun newlyDone(before: List<ReliabilityItemUi>, after: List<ReliabilityItemUi>): Set<ReliabilityItemId> {
        val notDoneBefore = before.filterNot { it.done }.mapTo(mutableSetOf()) { it.id }
        return after.filter { it.done && it.id in notDoneBefore }.mapTo(mutableSetOf()) { it.id }
    }

    private val VERIFIABLE = setOf(ReliabilityItemId.TermuxBattery, ReliabilityItemId.ClineBattery, ReliabilityItemId.Notifications)

    private fun verified(id: ReliabilityItemId, group: ReliabilityGroup, done: Boolean) =
        ReliabilityItemUi(id, group, if (done) ItemStatus.Done else ItemStatus.NotSet)

    private fun selfCheck(id: ReliabilityItemId, group: ReliabilityGroup, confirmed: Set<ReliabilityItemId>) =
        ReliabilityItemUi(id, group, if (id in confirmed) ItemStatus.SelfConfirmed else ItemStatus.SelfCheck)
}

/** Reads [SystemReadings] (fakeable). */
fun interface SystemReliabilityProbe {
    fun read(): SystemReadings
}

/** The self-check confirmations and the onboarding "shown once" mark (DataStore `app`, docs/07 §7.2). */
interface ReliabilityStore {
    val confirmed: Flow<Set<ReliabilityItemId>>
    suspend fun setConfirmed(id: ReliabilityItemId, confirmed: Boolean)
    suspend fun onboardingShownAt(): Long?
    suspend fun markOnboardingShown()
}

/**
 * [ReliabilityStore] over `AppPreferences`. docs/07 §7.2 names three self-check keys and D.6
 * has exactly three self-check items: `reliability.sleepingConfirmed` = item 3 (Never sleeping
 * apps), `reliability.phantomConfirmed` = item 4 (child process restrictions, when unreadable),
 * `reliability.batteryConfirmed` = item 6 (Termux overlay), the remaining key.
 */
class AppReliabilityStore(private val prefs: AppPreferences) : ReliabilityStore {
    override val confirmed: Flow<Set<ReliabilityItemId>>
        get() = prefs.reliabilityConfirmed.map { checks -> checks.mapTo(mutableSetOf(), ::itemOf) }

    override suspend fun setConfirmed(id: ReliabilityItemId, confirmed: Boolean) {
        val check = checkOf(id) ?: return
        prefs.setReliabilityConfirmed(check, confirmed)
    }

    override suspend fun onboardingShownAt(): Long? = prefs.onboardingReliabilityShownAt()

    override suspend fun markOnboardingShown() {
        prefs.setOnboardingReliabilityShownAt()
    }

    companion object {
        fun checkOf(id: ReliabilityItemId): AppPreferences.ReliabilityCheck? = when (id) {
            ReliabilityItemId.NeverSleeping -> AppPreferences.ReliabilityCheck.Sleeping
            ReliabilityItemId.ChildProcess -> AppPreferences.ReliabilityCheck.Phantom
            ReliabilityItemId.TermuxOverlay -> AppPreferences.ReliabilityCheck.Battery
            ReliabilityItemId.TermuxBattery, ReliabilityItemId.ClineBattery, ReliabilityItemId.Notifications -> null
        }

        fun itemOf(check: AppPreferences.ReliabilityCheck): ReliabilityItemId = when (check) {
            AppPreferences.ReliabilityCheck.Sleeping -> ReliabilityItemId.NeverSleeping
            AppPreferences.ReliabilityCheck.Phantom -> ReliabilityItemId.ChildProcess
            AppPreferences.ReliabilityCheck.Battery -> ReliabilityItemId.TermuxOverlay
        }
    }
}

/**
 * The checklist as other screens use it: E's "Background reliability" row ([summary]) and
 * the onboarding decision of D.4 ([onboardingStepNeeded]).
 */
class ReliabilityChecks(private val probe: SystemReliabilityProbe, val store: ReliabilityStore) {
    suspend fun items(confirmed: Set<ReliabilityItemId>): List<ReliabilityItemUi> =
        ReliabilityRules.items(runCatching { probe.read() }.getOrElse { FALLBACK }, confirmed)

    suspend fun summary(confirmed: Set<ReliabilityItemId>): ReliabilitySummary = ReliabilityRules.summary(items(confirmed))

    /** D.4: not shown before, and at least one verifiable item is not done. */
    suspend fun onboardingStepNeeded(confirmed: Set<ReliabilityItemId>): Boolean =
        store.onboardingShownAt() == null && ReliabilityRules.verifiableMissing(items(confirmed))

    /** E's row: the summary with the stored confirmations. */
    suspend fun summary(): ReliabilitySummary = summary(store.confirmed.first())

    companion object {
        /** A probe that threw: every verifiable item reads as Not set (D.8 "verification is advisory"). */
        val FALLBACK = SystemReadings(
            sdkInt = Build.VERSION.SDK_INT, samsung = false, termuxUnrestricted = false, clineUnrestricted = false,
            phantomProcs = PhantomSetting.Unreadable, developerOptionsOn = false, notificationsOn = false,
            canRequestIgnoreBattery = false,
        )

        fun of(context: Context, prefs: AppPreferences): ReliabilityChecks =
            ReliabilityChecks(AndroidReliabilityProbe(context.applicationContext), AppReliabilityStore(prefs))
    }
}

/** The Android reads behind D.6 (all synchronous, local). */
class AndroidReliabilityProbe(private val context: Context) : SystemReliabilityProbe {
    override fun read(): SystemReadings {
        val power = context.getSystemService(PowerManager::class.java)
        fun unrestricted(pkg: String): Boolean = runCatching { power?.isIgnoringBatteryOptimizations(pkg) == true }.getOrDefault(false)
        val resolver = context.contentResolver
        val phantom = try {
            PhantomSetting.Value(Settings.Global.getString(resolver, ReliabilityRules.PHANTOM_PROCS_KEY))
        } catch (_: SecurityException) {
            PhantomSetting.Unreadable
        } catch (_: RuntimeException) {
            PhantomSetting.Unreadable
        }
        val developer = runCatching { Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0 }.getOrDefault(false)
        return SystemReadings(
            sdkInt = Build.VERSION.SDK_INT,
            samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true),
            termuxUnrestricted = unrestricted(TermuxPaths.PACKAGE),
            clineUnrestricted = unrestricted(context.packageName),
            phantomProcs = phantom,
            developerOptionsOn = developer,
            notificationsOn = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false),
            canRequestIgnoreBattery = declares(Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
        )
    }

    private fun declares(permission: String): Boolean = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        info.requestedPermissions?.contains(permission) == true
    }.getOrDefault(false)
}
