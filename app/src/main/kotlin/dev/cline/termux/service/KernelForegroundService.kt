package dev.cline.termux.service

import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.cline.termux.appGraph
import dev.cline.termux.data.notifications.HoldState
import dev.cline.termux.data.notifications.KeepAlive
import dev.cline.termux.data.notifications.KeepAlivePolicy
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process unfrozen so the Hub socket survives while the agent works or waits for the
 * user (customize-and-rest.md §12). It never runs RUN_COMMAND, never holds the Hub token and
 * holds no wake lock: the connection lives in the app graph. What it shows and when it stops
 * come from [KeepAlivePolicy] (already debounced 10 s); it updates the notification in place at
 * most once per second.
 */
class KernelForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderJob: Job? = null
    private lateinit var policy: KeepAlivePolicy

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        policy = appGraph.keepAlive
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopNow()
            return START_NOT_STICKY
        }
        val notification = KernelNotification.build(this, policy.state.value)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            if (isStartNotAllowed(e)) {
                policy.reportDenied()
                stopSelf()
                return START_NOT_STICKY
            }
            throw e
        }
        policy.reportRunning()
        if (renderJob == null) renderJob = scope.launch { render(policy.state) }
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private suspend fun render(state: StateFlow<HoldState>) {
        var everHeld = false
        state.collectLatest { hold ->
            if (!hold.hold) {
                // Started at ON_PAUSE for a hold that begins once backgrounded: give it the debounce window.
                if (!everHeld) delay(KeepAlivePolicy.RELEASE_DEBOUNCE_MS)
                stopNow()
                return@collectLatest
            }
            everHeld = true
            if (NotificationChannels.canPost(this)) {
                try {
                    NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, KernelNotification.build(this, hold))
                } catch (_: SecurityException) {
                    // Permission revoked between the check and the post: the service still holds.
                }
            }
            delay(MIN_UPDATE_INTERVAL_MS)
        }
    }

    private fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        renderJob = null
        if (::policy.isInitialized) policy.reportStopped()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.cline.termux.action.STOP_KERNEL_SERVICE"
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L

        /**
         * Starts the service; call only while the app is visible (Android 12+ background-start
         * restriction). Returns false when Android refused (`KeepAlive.Denied` is recorded).
         */
        fun start(context: Context, policy: KeepAlivePolicy): Boolean = try {
            ContextCompat.startForegroundService(context, Intent(context, KernelForegroundService::class.java))
            true
        } catch (e: IllegalStateException) {
            if (!isStartNotAllowed(e)) throw e
            policy.reportDenied()
            false
        }

        fun stop(context: Context) {
            try {
                context.startService(Intent(context, KernelForegroundService::class.java).setAction(ACTION_STOP))
            } catch (_: IllegalStateException) {
                // Background start refused: the service is not running (it already released), so nothing to stop.
            }
        }

        internal fun isStartNotAllowed(e: IllegalStateException): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is ForegroundServiceStartNotAllowedException
    }
}

/**
 * Starts [KernelForegroundService] when [KeepAlivePolicy] says hold, only from the foreground
 * (customize-and-rest.md §12.2): when the hold turns on while the app is visible (the user just
 * sent a prompt), or at `ON_PAUSE` of the activity when the hold would be true once
 * backgrounded (`keepConnected`, or a run already going). The service stops itself.
 */
class KeepAliveLauncher(
    context: Context,
    private val policy: KeepAlivePolicy,
    private val foreground: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val starter: (Context, KeepAlivePolicy) -> Boolean = KernelForegroundService::start,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) {
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(dispatcher) {
            policy.state.collect { hold ->
                if (hold.hold && foreground.value && policy.status.value != KeepAlive.Running) {
                    starter(context, policy)
                }
            }
        }
    }

    /** `ON_PAUSE` of the visible activity: the last moment a start is allowed before the app leaves. */
    fun onActivityPaused() {
        if (!started.get()) return
        if (policy.status.value == KeepAlive.Running) return
        if (policy.wouldHoldInBackground()) starter(context, policy)
    }
}
