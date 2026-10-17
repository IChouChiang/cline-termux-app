package dev.cline.termux.debug

import android.content.Context
import android.content.Intent
import dev.cline.termux.core.kernel.HubProber
import dev.cline.termux.core.kernel.KernelCommands
import dev.cline.termux.core.kernel.hub.HubTransport
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.data.account.TermuxForeground

/**
 * Release twin of the debug `KernelOverrides`: identical signatures, no storage, no
 * receiver, no fake kernel. The kernel coordinator always gets `null` here, so a release
 * build can only ever talk to the Hub whose `production.json` it read through Termux, on the
 * default port, and every seam wrapper returns the real implementation unchanged.
 */
object KernelOverrides {
    const val EXTRA_FAKE_KERNEL = "dev.cline.termux.FAKE_KERNEL"
    const val EXTRA_RESET_ONBOARDING = "dev.cline.termux.RESET_ONBOARDING"

    suspend fun hubToken(context: Context): String? = null

    /** Never overridden in a release build: the Hub is always on `HubEndpoints.DEFAULT_PORT`. */
    fun hubPort(context: Context): Int? = null

    suspend fun setHubToken(context: Context, token: String, port: Int? = null) = Unit

    suspend fun clearHubToken(context: Context) = Unit

    fun applyLaunchIntent(context: Context, intent: Intent?) = Unit

    fun helperRoomSecret(): String? = null

    fun fakeKernelScenario(context: Context): String? = null

    fun fakeKernelTrace(): List<String> = emptyList()

    fun termuxExecutor(context: Context, real: TermuxExecutor): TermuxExecutor = real

    fun kernelCommands(context: Context, real: KernelCommands): KernelCommands = real

    fun termuxForeground(context: Context, real: TermuxForeground): TermuxForeground = real

    fun hubProber(context: Context, real: HubProber): HubProber = real

    fun hubTransport(context: Context, real: HubTransport): HubTransport = real
}
