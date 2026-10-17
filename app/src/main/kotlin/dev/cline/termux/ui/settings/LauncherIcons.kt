package dev.cline.termux.ui.settings

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.cline.termux.ClineApp
import dev.cline.termux.ui.theme.AppIcon
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The launcher-alias rules of settings-providers.md S2.2 / DS §10.6, pure. A switch enables
 * the new alias at once; the old one is retired only where that cannot drop the live task:
 * on the next cold start (`ClineApp`) or at process `ON_STOP` when the task was not launched
 * through an alias being disabled.
 */
object LauncherIconPlan {
    /** Every launcher alias component class name. */
    val aliases: List<String> get() = AppIcon.entries.map(ClineApp::launcherAlias)

    /** The aliases to disable so that only [chosen] stays enabled (the later request wins). */
    fun toDisable(chosen: AppIcon): List<String> = AppIcon.entries.filter { it != chosen }.map(ClineApp::launcherAlias)

    /**
     * The `ON_STOP` retire: the aliases to disable now, or null when the task's base intent
     * ([baseComponent], a class name) is one of them, so the disable must wait for a cold start.
     */
    fun retireOnStop(chosen: AppIcon, baseComponent: String?): List<String>? {
        val disable = toDisable(chosen)
        return if (baseComponent != null && baseComponent in disable) null else disable
    }
}

/** What the Appearance ViewModel needs to switch the icon; [AndroidLauncherIcons] in the app. */
interface LauncherIcons {
    /** Enables [icon]'s alias (`COMPONENT_ENABLED_STATE_ENABLED`, `DONT_KILL_APP`). Throws on failure. */
    fun enable(icon: AppIcon)

    /** Arms the process `ON_STOP` retire (S2.2 (b)); [onRetired] clears the pending marker. */
    fun scheduleRetire(chosen: () -> AppIcon, onRetired: () -> Unit)
}

/** `PackageManager.setComponentEnabledSetting` on the two `activity-alias` entries. */
class AndroidLauncherIcons(context: Context) : LauncherIcons {
    private val app = context.applicationContext
    private val armed = AtomicBoolean(false)

    @Volatile
    private var chosen: () -> AppIcon = { AppIcon.Midnight }

    @Volatile
    private var onRetired: () -> Unit = {}

    override fun enable(icon: AppIcon) {
        set(ClineApp.launcherAlias(icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
    }

    override fun scheduleRetire(chosen: () -> AppIcon, onRetired: () -> Unit) {
        this.chosen = chosen
        this.onRetired = onRetired
        if (!armed.compareAndSet(false, true)) return
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    retireIfSafe()
                }
            },
        )
    }

    private fun retireIfSafe() {
        val icon = chosen()
        val disable = LauncherIconPlan.retireOnStop(icon, baseComponent()) ?: return
        try {
            disable.forEach { set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
            set(ClineApp.launcherAlias(icon), PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            onRetired()
        } catch (_: SecurityException) {
            // Stays pending; the next cold start retries (ClineApp).
        } catch (_: IllegalArgumentException) {
            // Stays pending; the next cold start clears it.
        }
    }

    /** The class name the current task was launched through (`baseIntent.component`). */
    private fun baseComponent(): String? = runCatching {
        val manager = app.getSystemService(ActivityManager::class.java)
        manager?.appTasks?.firstOrNull()?.taskInfo?.baseIntent?.component?.className
    }.getOrNull()

    private fun set(className: String, state: Int) {
        val component = ComponentName(app, className)
        if (app.packageManager.getComponentEnabledSetting(component) != state) {
            app.packageManager.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }
    }

    companion object {
        @Volatile
        private var instance: AndroidLauncherIcons? = null

        /** One per process, so the `ON_STOP` observer is registered once however often Appearance opens. */
        fun of(context: Context): AndroidLauncherIcons =
            instance ?: synchronized(this) { instance ?: AndroidLauncherIcons(context).also { instance = it } }
    }
}
