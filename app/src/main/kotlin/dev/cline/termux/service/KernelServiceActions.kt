package dev.cline.termux.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cline.termux.appGraph
import dev.cline.termux.core.kernel.KernelGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The foreground notification's actions (customize-and-rest.md §12.3), an `exported="false"`
 * receiver reached only through this app's immutable PendingIntents:
 *
 * - **Stop agent** → `SessionStore.abort(sessionId)` (`run.abort`), the same path as the
 *   composer's Stop (docs/07 rule 8), so the session's chat and composer show `Stopping` and a
 *   failed abort restores the phase. Only while the gate is `Ready` (a tap that arrives after the
 *   gate left `Ready` is dropped). The notification switches to "Stopping..." only after the
 *   reply is ok, and returns to its previous variant otherwise.
 * - **Disconnect** → `KernelCoordinator.disconnect()` (the Hub keeps running), then the
 *   service stops.
 *
 * No `goAsync()`: the work can outlast the 10 s a notification-action broadcast may take
 * (Disconnect's `termux-wake-unlock` alone has taken that long with Termux in the background),
 * and holding the broadcast past it is an ANR that kills the process mid-disconnect. These
 * actions exist only on the foreground service's notification, and that service keeps the
 * process alive until the work is done; Disconnect stops it last.
 */
class KernelServiceActions : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val graph = context.appGraph
        val appContext = context.applicationContext
        graph.appScope.launch {
            when (intent.action) {
                ACTION_STOP_AGENT -> {
                    val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return@launch
                    runStopAgent(
                        sessionId = sessionId,
                        gate = graph.kernel.gate.value,
                        abort = graph.sessionStore::abort,
                        onStopConfirmed = graph.keepAlive::onStopConfirmed,
                    )
                }
                ACTION_DISCONNECT -> {
                    try {
                        graph.kernel.disconnect()
                    } finally {
                        KernelForegroundService.stop(appContext)
                    }
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP_AGENT = "dev.cline.termux.action.STOP_AGENT"
        const val ACTION_DISCONNECT = "dev.cline.termux.action.DISCONNECT"
        const val EXTRA_SESSION_ID = "dev.cline.termux.extra.SESSION_ID"

        /**
         * The Stop agent action: [abort] is `SessionStore.abort`, which owns the `Stopping` phase
         * and its restore on failure; [onStopConfirmed] runs only on an ok reply. Returns whether
         * the abort was confirmed.
         */
        internal suspend fun runStopAgent(
            sessionId: String,
            gate: KernelGate,
            abort: suspend (String) -> Result<Unit>,
            onStopConfirmed: (String) -> Unit,
        ): Boolean {
            if (gate != KernelGate.Ready) return false
            val ok = try {
                abort(sessionId).isSuccess
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // An error the store does not map (protocol): the variant stays; the chat shows the stop failure.
                false
            }
            if (ok) onStopConfirmed(sessionId)
            return ok
        }

        fun stopAgent(context: Context, sessionId: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            "stop/$sessionId".hashCode(),
            Intent(context, KernelServiceActions::class.java).setAction(ACTION_STOP_AGENT).putExtra(EXTRA_SESSION_ID, sessionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun disconnect(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            ACTION_DISCONNECT.hashCode(),
            Intent(context, KernelServiceActions::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
