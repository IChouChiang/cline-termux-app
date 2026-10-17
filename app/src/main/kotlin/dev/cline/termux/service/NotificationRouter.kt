package dev.cline.termux.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.cline.termux.MainActivity

/**
 * Builds the tap intents of every notification (composer-approvals.md §0.6, customize-and-rest.md
 * §11.2 "Tap", D10): `MainActivity` with the session extras, never a `cline://` URI.
 * `MainActivity` (cold start and `onNewIntent`) maps the extras to `chat/{sessionId}?sheet=…`,
 * adds the session to `KnownSessions` and removes the extras afterwards.
 */
object NotificationRouter {
    const val EXTRA_SESSION_ID = "dev.cline.termux.extra.SESSION_ID"
    const val EXTRA_OPEN_SHEET = "dev.cline.termux.extra.OPEN_SHEET"

    /** The `OPEN_SHEET` values (the chat route's `sheet` argument). */
    enum class Sheet(val wire: String) {
        Approval("approval"),
        Question("question"),
        ;

        companion object {
            fun parse(raw: String?): Sheet? = entries.firstOrNull { it.wire == raw }
        }
    }

    /** What a routed intent asks for; read by `MainActivity` (deep-link routing). */
    data class Target(val sessionId: String, val sheet: Sheet?)

    fun sessionIntent(context: Context, sessionId: String, sheet: Sheet? = null): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .apply { if (sheet != null) putExtra(EXTRA_OPEN_SHEET, sheet.wire) }

    fun homeIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** The extras of [intent], or null when it carries no session. */
    fun targetOf(intent: Intent?): Target? {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.takeIf { it.isNotBlank() } ?: return null
        return Target(sessionId, Sheet.parse(intent.getStringExtra(EXTRA_OPEN_SHEET)))
    }

    /** Removes the handled extras so a rotation or recreation does not reopen the sheet (CA §0.6). */
    fun consume(intent: Intent) {
        intent.removeExtra(EXTRA_SESSION_ID)
        intent.removeExtra(EXTRA_OPEN_SHEET)
    }

    /** Opens [sessionId] (with [sheet]); the request code is [tag]'s hash so intents stay distinct. */
    fun openSession(context: Context, tag: String, sessionId: String, sheet: Sheet? = null): PendingIntent =
        PendingIntent.getActivity(context, tag.hashCode(), sessionIntent(context, sessionId, sheet), FLAGS)

    /** Opens Home (the session list). */
    fun openHome(context: Context, tag: String = HOME_TAG): PendingIntent =
        PendingIntent.getActivity(context, tag.hashCode(), homeIntent(context), FLAGS)

    /** Wraps an arbitrary in-app [intent] (e.g. the sign-in origin screen) under [tag]'s request code. */
    fun open(context: Context, tag: String, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, tag.hashCode(), intent, FLAGS)

    private const val HOME_TAG = "home"
    private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}
