package dev.cline.termux.ui.navigation

import android.content.Intent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a notification (or an in-app shortcut) asks the shell to open (CA §0.6, docs/07 §6.4,
 * D10: intent extras, no `cline://` scheme).
 */
@Serializable
sealed interface DeepLink {
    /** `chat/{sessionId}?sheet=…`: select [sessionId] in the chat root and open [sheet] over it. */
    @Serializable
    data class Session(val sessionId: String, val sheet: ChatSheet? = null) : DeepLink {
        val route: Chat get() = Chat(sessionId, sheet?.wire)
    }

    /**
     * The Kernel status sheet (KS E.3 "a `kernel://status` deep link from the foreground-service
     * notification"), carried as `OPEN_SHEET = kernel_status` without a session id, because the
     * app declares no URI scheme (D10).
     */
    @Serializable
    data object KernelStatus : DeepLink
}

/** The two extras of CA §0.6, both read by `MainActivity.onCreate` (cold) and `onNewIntent` (warm). */
object DeepLinks {
    const val EXTRA_SESSION_ID = "dev.cline.termux.extra.SESSION_ID"
    const val EXTRA_OPEN_SHEET = "dev.cline.termux.extra.OPEN_SHEET"

    /** `OPEN_SHEET` value for [DeepLink.KernelStatus] (no `SESSION_ID`). */
    const val SHEET_KERNEL_STATUS = "kernel_status"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Maps the raw extras to a link: a non-blank session id opens that session (with the
     * sheet when [openSheet] is `approval` | `question` | `queue`; any other value opens the
     * session without a sheet); no session id with `kernel_status` opens the status sheet;
     * anything else is not a deep link.
     */
    fun parse(sessionId: String?, openSheet: String?): DeepLink? {
        val id = sessionId?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            id != null -> DeepLink.Session(id, ChatSheet.fromWire(openSheet?.trim()))
            openSheet?.trim() == SHEET_KERNEL_STATUS -> DeepLink.KernelStatus
            else -> null
        }
    }

    /** The extras that produce [link] (for `NotificationRouter`'s PendingIntents). */
    fun extrasOf(link: DeepLink): Map<String, String> = when (link) {
        is DeepLink.Session -> buildMap {
            put(EXTRA_SESSION_ID, link.sessionId)
            link.sheet?.let { put(EXTRA_OPEN_SHEET, it.wire) }
        }
        DeepLink.KernelStatus -> mapOf(EXTRA_OPEN_SHEET to SHEET_KERNEL_STATUS)
    }

    /** Puts [link]'s extras on [intent]. */
    fun fillIn(intent: Intent, link: DeepLink): Intent = intent.apply {
        extrasOf(link).forEach { (key, value) -> putExtra(key, value) }
    }

    /** Access to an intent's extras, so the read-and-remove rule is testable without Android. */
    interface Extras {
        fun string(key: String): String?
        fun remove(key: String)
    }

    /**
     * Reads the link and removes both extras whether or not they formed one (CA §0.6: "so a
     * rotation or process recreation does not reopen the sheet").
     */
    fun consume(extras: Extras): DeepLink? {
        val link = parse(extras.string(EXTRA_SESSION_ID), extras.string(EXTRA_OPEN_SHEET))
        extras.remove(EXTRA_SESSION_ID)
        extras.remove(EXTRA_OPEN_SHEET)
        return link
    }

    /** [consume] on a real intent; the caller then `setIntent(intent)`. */
    fun consume(intent: Intent?): DeepLink? {
        if (intent == null) return null
        return consume(
            object : Extras {
                override fun string(key: String): String? = intent.getStringExtra(key)
                override fun remove(key: String) = intent.removeExtra(key)
            },
        )
    }

    /** `SavedStateHandle` / bundle form (a pending link must survive process death). */
    fun encode(link: DeepLink): String = json.encodeToString(DeepLink.serializer(), link)

    fun decode(value: String?): DeepLink? = value?.let { runCatching { json.decodeFromString(DeepLink.serializer(), it) }.getOrNull() }
}
