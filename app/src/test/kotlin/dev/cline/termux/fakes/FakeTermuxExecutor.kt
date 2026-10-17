package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.termux.DetachedHandle
import dev.cline.termux.core.kernel.termux.TermuxCommand
import dev.cline.termux.core.kernel.termux.TermuxExecutor
import dev.cline.termux.core.kernel.termux.TermuxPresence
import dev.cline.termux.core.kernel.termux.TermuxResult
import dev.cline.termux.core.kernel.termux.TermuxUnavailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * docs/07 §10.1: canned [TermuxResult]s keyed by command label, optional latency, and
 * `run` timeouts that fire on the test scheduler's virtual clock. Detached commands hand
 * back a [FakeDetachedHandle] the test completes by hand.
 */
typealias TermuxResponder = suspend (TermuxCommand) -> TermuxResult

class FakeTermuxExecutor : TermuxExecutor {

    var presence: TermuxPresence = TermuxPresence.Installed(118, "0.118.3")
    var permitted: Boolean = true

    /** Virtual latency applied to every `run` before the responder answers. */
    var latencyMs: Long = 0

    /** Every command handed in, `run` and `runDetached` alike, in order. */
    val commands = mutableListOf<TermuxCommand>()
    val labels: List<String> get() = commands.map { it.label }
    val detached = mutableListOf<FakeDetachedHandle>()

    private val responders = HashMap<String, TermuxResponder>()
    var fallback: TermuxResponder = { TermuxResult.success("") }

    fun on(label: String, result: TermuxResult) = on(label) { result }

    fun on(label: String, responder: TermuxResponder) {
        responders[label] = responder
    }

    /** The command never returns; `run` ends in [TermuxUnavailable.TimedOut] after its timeout. */
    fun never(label: String) = on(label) { awaitCancellation() }

    fun throwing(label: String, error: TermuxUnavailable) = on(label) { throw error }

    fun commandsLabelled(label: String): List<TermuxCommand> = commands.filter { it.label == label }

    fun ran(label: String): Boolean = commands.any { it.label == label }

    override fun isTermuxInstalled(): TermuxPresence = presence

    override fun hasPermission(): Boolean = permitted

    override suspend fun run(command: TermuxCommand, timeoutMs: Long): TermuxResult {
        gate()
        commands += command
        val responder = responders[command.label] ?: fallback
        val result = withTimeoutOrNull(timeoutMs) {
            if (latencyMs > 0) delay(latencyMs)
            responder(command)
        } ?: throw TermuxUnavailable.TimedOut(command.label, timeoutMs)
        if (result.mentionsExternalApps) throw TermuxUnavailable.ExternalAppsBlocked(result.errmsg.orEmpty())
        return result
    }

    override fun runDetached(command: TermuxCommand): DetachedHandle {
        gate()
        commands += command
        return FakeDetachedHandle(command, detached.size + 1).also { detached += it }
    }

    private fun gate() {
        if (!presence.installed) throw TermuxUnavailable.NotInstalled()
        if (!permitted) throw TermuxUnavailable.PermissionMissing()
    }

    class FakeDetachedHandle(override val command: TermuxCommand, override val executionId: Int) : DetachedHandle {
        private val exit = CompletableDeferred<TermuxResult>()
        override val earlyExit: Deferred<TermuxResult> = exit
        var cancelled: Boolean = false
            private set

        /** The process ended with [result] (the result PendingIntent fired). */
        fun exitWith(result: TermuxResult) {
            exit.complete(result)
        }

        /** Termux could not run it at all (e.g. allow-external-apps off). */
        fun failWith(error: TermuxUnavailable) {
            exit.completeExceptionally(error)
        }

        override fun cancel() {
            cancelled = true
            exit.cancel()
        }
    }
}
