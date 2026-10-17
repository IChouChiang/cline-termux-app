package dev.cline.termux.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * `am broadcast -a dev.cline.termux.debug.SET_HUB_TOKEN -p io.github.ichouchiang.clinetermux.debug --es token <t>
 * [--ei port <1024..65535>]` stores a developer Hub token and, optionally, the Hub port the app
 * dials instead of 25463 (read once at the next cold start); `--ez clear true` removes both.
 * Registered in the debug manifest only, exported behind `android.permission.DUMP`
 * (shell-only), so no other app can set or clear it. The token value is never logged.
 */
class HubTokenOverrideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        // A port given with the wrong type (`--es port …`) reads as MIN_VALUE and is refused.
        val port = if (intent.hasExtra(EXTRA_PORT)) intent.getIntExtra(EXTRA_PORT, Int.MIN_VALUE) else null
        val request = HubOverrideRequest.parse(
            clear = intent.getBooleanExtra(EXTRA_CLEAR, false),
            token = intent.getStringExtra(EXTRA_TOKEN),
            port = port,
        )
        immediateResult(request)?.let {
            resultCode = it
            return
        }
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (request) {
                    HubOverrideRequest.Clear -> KernelOverrides.clearHubToken(app)
                    is HubOverrideRequest.Set -> KernelOverrides.setHubToken(app, request.token, request.port)
                    HubOverrideRequest.Invalid -> Unit
                }
                pending.resultCode = RESULT_OK
            } catch (_: Exception) {
                pending.resultCode = RESULT_FAILED
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "dev.cline.termux.debug.SET_HUB_TOKEN"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_CLEAR = "clear"
        const val EXTRA_PORT = "port"
        const val RESULT_OK = 0
        const val RESULT_BAD_REQUEST = 2
        const val RESULT_FAILED = 3

        /** The result code answered without touching storage: [RESULT_BAD_REQUEST] for an invalid request. */
        fun immediateResult(request: HubOverrideRequest): Int? = if (request == HubOverrideRequest.Invalid) RESULT_BAD_REQUEST else null
    }
}
