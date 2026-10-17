package dev.cline.termux.core.kernel.hub

import kotlinx.serialization.json.JsonElement

/**
 * The error family of the Hub protocol (docs/07 §9): one exception for `ok:false`
 * replies and client-side timers, one for a socket that was not open, plus the reply
 * codes the app reacts to by name.
 */
class HubCommandException(
    val command: String,
    val code: String,
    message: String,
    val details: JsonElement? = null,
) : Exception("$command failed: $code: $message") {
    /** The Hub's `error.message` verbatim (kernel text is data, never translated). */
    val hubMessage: String = message
}

/** The socket was not open when the command was sent; the event stream is the truth. */
class HubConnectionClosedException(message: String) : Exception(message)

object HubErrorCodes {
    /** Set by the client on every pending command when the socket closes. */
    const val HUB_CONNECTION_CLOSED = "hub_connection_closed"

    /** Set by the client when its own timer (always longer than `envelope.timeoutMs`) expires. */
    const val CLIENT_TIMEOUT = "client_timeout"

    /** The Hub stopped waiting but did not cancel the handler (changes-tools-checkpoints.md §1.5.4). */
    const val HUB_COMMAND_TIMEOUT = "hub_command_timeout"
    const val HUB_DRAINING = "hub_draining"
    const val SESSION_NOT_FOUND = "session_not_found"
    const val SESSION_MESSAGES_NOT_FOUND = "session_messages_not_found"
    const val COMMAND_FAILED = "command_failed"
    const val CLIENT_AUTHORITY_MISMATCH = "client_authority_mismatch"
    const val CLIENT_NOT_FOUND = "client_not_found"
    const val CLIENT_ALREADY_REGISTERED = "client_already_registered"
    const val INVALID_CLIENT_REGISTRATION = "invalid_client_registration"
    const val APPROVAL_NOT_FOUND = "approval_not_found"
    const val CAPABILITY_WRONG_CLIENT = "capability_wrong_client"
    const val CAPABILITY_WRONG_SESSION = "capability_wrong_session"
    const val PENDING_PROMPTS_UNAVAILABLE = "pending_prompts_unavailable"
    const val INVALID_SESSION_INPUT = "invalid_session_input"
    const val INVALID_SESSION_ID = "invalid_session_id"
    const val INVALID_RESTORE = "invalid_restore"
    const val RESTORE_FAILED = "restore_failed"
    const val UNSUPPORTED_COMMAND = "unsupported_command"
    const val SETTINGS_LIST_FAILED = "settings_list_failed"
    const val SETTINGS_TOGGLE_FAILED = "settings_toggle_failed"
    const val SCHEDULE_COMMAND_FAILED = "schedule_command_failed"

    /** The connection lost its client record: close and re-register with the same clientId (CR §0.5). */
    val AUTHORITY_LOST = setOf(CLIENT_AUTHORITY_MISMATCH, CLIENT_NOT_FOUND, CLIENT_ALREADY_REGISTERED)
}

/**
 * The outcome of a non-idempotent command is unknown: the socket dropped, the client timer
 * fired, or the Hub stopped waiting while its handler kept running. Never retried blindly;
 * repositories recover by re-reading (CT §1.6 lost-reply recovery).
 */
fun HubCommandException.isLostReply(): Boolean =
    code == HubErrorCodes.HUB_CONNECTION_CLOSED ||
        code == HubErrorCodes.CLIENT_TIMEOUT ||
        code == HubErrorCodes.HUB_COMMAND_TIMEOUT

/** The session is not live on this Hub (daemon restarted, deleted elsewhere); resume via `session.create`. */
fun HubCommandException.isSessionGone(): Boolean =
    code == HubErrorCodes.SESSION_NOT_FOUND ||
        (code == HubErrorCodes.COMMAND_FAILED && hubMessage.startsWith("session not found", ignoreCase = true)) ||
        hubMessage.startsWith("Unknown session:", ignoreCase = true)

fun HubCommandException.isDraining(): Boolean = code == HubErrorCodes.HUB_DRAINING

fun HubCommandException.isAuthorityLost(): Boolean = code in HubErrorCodes.AUTHORITY_LOST

fun HubCommandException.isPendingPromptsUnavailable(): Boolean = code == HubErrorCodes.PENDING_PROMPTS_UNAVAILABLE

/**
 * Drain policy (docs/07 §3.1, CR §0.5): a drain-gated command that gets `hub_draining`
 * waits for `hub.drain_changed {draining:false}` up to [waitMs] and is sent exactly once
 * more; a second refusal, or the wait running out, surfaces the original exception.
 */
suspend fun <T> HubClient.withDrainRetry(waitMs: Long = HubClient.DRAIN_WAIT_MS, block: suspend () -> T): T {
    return try {
        block()
    } catch (e: HubCommandException) {
        if (!e.isDraining()) throw e
        if (!awaitNotDraining(waitMs)) throw e
        block()
    }
}
