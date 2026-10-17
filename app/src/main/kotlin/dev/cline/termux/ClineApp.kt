package dev.cline.termux

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.cline.termux.data.approvals.ApprovalRefresh
import dev.cline.termux.service.NotificationChannels
import dev.cline.termux.service.ShareFiles
import dev.cline.termux.ui.theme.AppIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ClineApp : Application() {
    /** Built lazily so a cold start renders before the kernel graph is touched. */
    val graph: AppGraph by lazy { AppGraph(this) }

    @Volatile
    private var keepAliveStarted = false

    /**
     * The only eager work (docs/07 §1.3): notification channels, the pending launcher-alias
     * disable (settings-providers.md S2.2) and the 24 h FileProvider sweep, the last two on IO.
     * The notifier and the keep-alive launcher start at the first process `ON_START`, off Main,
     * so the graph's kernel members are never first built on the main thread.
     */
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensure(this)
        graph.appScope.launch(Dispatchers.IO) {
            disablePendingLauncherAlias()
            ShareFiles.sweep(cacheDir, System.currentTimeMillis())
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = onProcess { graph ->
                    if (!keepAliveStarted) {
                        graph.notifier.start()
                        graph.keepAliveLauncher.start()
                        // Decided A1: run-boundary restarts and the re-read after a Hub reconnect.
                        graph.startApprovalMode()
                        // Trial row 16: a create-time title the kernel replaced with the first prompt goes back.
                        graph.createTitleKeeper.start()
                        // SP S7 step 5: a sign-in that finished while the app was away posts "Signed in".
                        graph.appScope.launch {
                            graph.signIn.completions.collect { done -> if (!done.appInForeground) graph.notifier.postSignedIn() }
                        }
                        // Onboarding finished in this process: the coordinator joins the foreground policy now.
                        graph.appScope.launch(lifecycleDispatcher) {
                            if (graph.prefs.onboardingCompletedAt() != null) return@launch
                            graph.prefs.onboardingCompletedAt.first { it != null }
                            if (graph.processForeground.value) graph.kernel.onForeground()
                        }
                        // G1: the global compaction mode every session.create carries (read once at app start).
                        if (graph.prefs.onboardingCompletedAt() != null) graph.compactionMode.refreshInBackground()
                        keepAliveStarted = true
                    }
                    graph.notifier.cancelSignedIn()
                    graph.signIn.onAppStarted()
                    // KS §0.4: auto-start after more than 5 s away, passive probe while foreground.
                    // Not during first-run onboarding: Kernel setup row 5 waits for "Start kernel" (KS A).
                    if (graph.prefs.onboardingCompletedAt() != null) {
                        graph.kernel.onForeground()
                        // auto-approve-control.md §6: the terminal may have flipped the setting while the app was away.
                        graph.appScope.launch { graph.approvalMode.refresh(ApprovalRefresh.Foreground) }
                    }
                }

                override fun onResume(owner: LifecycleOwner) = onProcess { graph ->
                    graph.signIn.onAppResumed()
                    graph.sessionsRepository.onResume()
                }

                override fun onStop(owner: LifecycleOwner) = onProcess { graph ->
                    graph.signIn.onAppStopped()
                    graph.sessionsRepository.onStop()
                    // Cursor flush; RUN_COMMAND is never issued from the background.
                    graph.kernel.onBackground()
                }
            },
        )
        registerActivityLifecycleCallbacks(
            object : ActivityLifecycleCallbacks {
                override fun onActivityPaused(activity: Activity) {
                    // The last moment a foreground-service start is allowed (customize-and-rest.md §12.2).
                    if (keepAliveStarted) graph.keepAliveLauncher.onActivityPaused()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    /**
     * Process lifecycle work runs in order on one serial dispatcher off Main: the first event
     * builds the kernel graph, which must never happen on the main thread (docs/07 §8).
     */
    private val lifecycleDispatcher by lazy { kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1) }

    private fun onProcess(block: suspend (AppGraph) -> Unit) {
        graph.appScope.launch(lifecycleDispatcher) { block(graph) }
    }

    /**
     * Settings-providers.md S2.2: the alias the user switched away from is disabled on the next
     * cold start (never under the live task). Every alias except the chosen one is disabled, so
     * after two quick switches only the later choice stays enabled; a failure keeps the pending
     * marker for the next start.
     */
    private suspend fun disablePendingLauncherAlias() {
        val prefs = graph.prefs
        if (prefs.pendingIconDisable() == null) return
        val keep = prefs.appIcon()
        try {
            for (icon in AppIcon.entries) {
                val component = ComponentName(this, launcherAlias(icon))
                val wanted = if (icon == keep) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (packageManager.getComponentEnabledSetting(component) != wanted) {
                    packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
                }
            }
            prefs.setPendingIconDisable(null)
        } catch (_: SecurityException) {
            // Kept pending; the Appearance screen's ON_STOP path or the next start retries.
        } catch (_: IllegalArgumentException) {
            // Unknown component (a build without the alias): nothing to disable.
            prefs.setPendingIconDisable(null)
        }
    }

    companion object {
        /** The launcher `activity-alias` of each [AppIcon] (AndroidManifest.xml, design-system.md §10.6). */
        fun launcherAlias(icon: AppIcon): String = when (icon) {
            AppIcon.Midnight -> "dev.cline.termux.LauncherMidnight"
            AppIcon.Classic -> "dev.cline.termux.LauncherClassic"
        }
    }
}
