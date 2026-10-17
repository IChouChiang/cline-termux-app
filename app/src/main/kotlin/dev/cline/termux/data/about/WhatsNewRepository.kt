package dev.cline.termux.data.about

import kotlinx.coroutines.CancellationException

/**
 * What's new seen-tracking (customize-and-rest.md §8.4; desktop `lib/whats-new.ts`):
 * DataStore `whatsNew.seenId` (desktop `cline.code.whats-new.v1`). An unreadable store counts
 * as seen, so the dialog cannot reappear on every launch; a failed write only means it shows
 * again next launch.
 *
 * Shell rule: show [pending] once Home has drawn, only when onboarding is complete; closing
 * (back, scrim, "Get Started", "See all changes") calls [markSeen]. Finishing onboarding calls
 * [markCurrentSeen]. About replays [latest] without writing anything.
 */
class WhatsNewRepository(
    private val store: Store,
    private val releases: List<WhatsNewRelease> = WHATS_NEW_RELEASES,
) {
    /** The two DataStore reads and one write this needs (`AppPreferences` in the app). */
    interface Store {
        suspend fun seenId(): String?
        suspend fun setSeenId(id: String)
        suspend fun onboardingCompletedAt(): Long?
    }

    /** `latestWhatsNew`. */
    fun latest(): WhatsNewRelease? = releases.firstOrNull()

    /** `readSeenWhatsNewId`: an unreadable store reads as the latest id (seen). */
    suspend fun seenId(): String? = try {
        store.seenId()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        latest()?.id
    }

    /** `pendingWhatsNew(seenId)`. */
    fun pending(seenId: String?): WhatsNewRelease? = latest()?.takeIf { it.id != seenId }

    /** The shell's launch check: onboarded users only, and only an unseen latest entry. */
    suspend fun pendingForShell(): WhatsNewRelease? {
        val onboarded = try {
            store.onboardingCompletedAt() != null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
        return if (onboarded) pending(seenId()) else null
    }

    /** `markWhatsNewSeen`; storage failures are swallowed (the dialog shows again next launch). */
    suspend fun markSeen(id: String) {
        try {
            store.setSeenId(id)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Storage unavailable: nothing breaks.
        }
    }

    /** `markCurrentWhatsNewSeen`: so a fresh install never sees the catch-up after onboarding. */
    suspend fun markCurrentSeen() {
        latest()?.let { markSeen(it.id) }
    }
}
