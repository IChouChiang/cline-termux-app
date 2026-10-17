package dev.cline.termux.data.sessions

import dev.cline.termux.core.kernel.hub.HubCommandException
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.core.kernel.hub.isSessionGone
import dev.cline.termux.data.session.PROMPT_METADATA_KEY
import dev.cline.termux.data.session.RunEnded
import dev.cline.termux.data.session.UserInputFormat
import dev.cline.termux.data.session.str
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Trial row 16: keeps the title a session was created with through its first prompt.
 *
 * The cause is in the kernel, not the app. Core 0.0.91 keeps a brand-new session in memory until
 * its first prompt and only then writes its record (`LocalRuntimeHost.ensureSessionPersisted` →
 * `createRootSessionWithArtifacts`), titling it with `resolveMetadataWithTitle({metadata, prompt})`:
 * a title derived from the prompt replaces the create's `metadata.title`. Sessions seeded with
 * messages (forks) are written at their create and keep their title, and `updateSession` never
 * replaces a stored title. The app never sends a create-time title itself (its first send carries
 * none, a fork is seeded), so this only meets sessions another client created with a title and
 * this app sent the first prompt to.
 *
 * Right after such a run ends ([RunEnded.createTitle], set by `SessionStore` for this client's
 * first prompt into a session that already had a title), the keeper reads the record. When its
 * title is the one the kernel derives from the stored first prompt ([KernelTitle.fromPrompt]), and
 * not the create's, it renames the session back through the F2 rename (`session.update`). One
 * retry when the read or the rename fails, then it gives up; nothing triggers it again for that
 * session. A title that is neither (someone renamed it meanwhile) is left alone.
 */
class CreateTitleKeeper(
    private val runEnds: Flow<RunEnded>,
    /** `session.get {sessionId}` (the record whose `session.metadata` carries the title and the stored prompt). */
    private val record: suspend (sessionId: String) -> JsonObject,
    /** The F2 rename ([SessionActions.rename]). */
    private val rename: suspend (sessionId: String, title: String) -> ActionOutcome,
    private val scope: CoroutineScope,
    private val config: Config = Config(),
) {
    data class Config(
        /** After the run end: the run-boundary restart (decided A1) and the run's records settle first. */
        val settleMs: Long = 1_000L,
        /** Before the one retry. */
        val retryMs: Long = 3_000L,
    )

    enum class Result {
        /** The kernel kept the create's title. */
        Kept,

        /** The kernel had replaced it with the first prompt; the rename put it back. */
        Restored,

        /** The title is neither the create's nor the prompt's (renamed meanwhile), or the session is gone. */
        LeftAlone,

        /** Both attempts failed (no connection, a refused rename). */
        Failed,

        /** Not this client's first prompt into a titled session. */
        Skipped,
    }

    private var job: Job? = null

    /** Starts listening for run ends; idempotent. */
    fun start() {
        synchronized(this) {
            if (job != null) return
            job = scope.launch {
                runEnds.collect { end -> if (end.ours && end.createTitle != null) launch { keep(end) } }
            }
        }
    }

    /** One run end: read, compare, rename back at most twice. */
    suspend fun keep(end: RunEnded): Result {
        val title = KernelTitle.normalize(end.createTitle)
        if (!end.ours || title == null) return Result.Skipped
        repeat(ATTEMPTS) { attempt ->
            delay(if (attempt == 0) config.settleMs else config.retryMs)
            val metadata = try {
                HubPayloads.metadataOf(record(end.sessionId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: HubCommandException) {
                if (e.isSessionGone()) return Result.LeftAlone
                return@repeat
            } catch (_: Exception) {
                return@repeat
            }
            val current = KernelTitle.normalize(metadata?.str("title"))
            if (current == title) return Result.Kept
            if (current == null || current != KernelTitle.fromPrompt(metadata?.str(PROMPT_METADATA_KEY))) return Result.LeftAlone
            val outcome = rename(end.sessionId, title)
            if (outcome !is ActionOutcome.Failed) return Result.Restored
            if (outcome.failure == ActionFailure.Gone) return Result.LeftAlone
        }
        return Result.Failed
    }

    private companion object {
        /** The rename and its one retry. */
        const val ATTEMPTS = 2
    }
}

/** The kernel's session titles (core `services/session-data.ts`), ported for [CreateTitleKeeper]. */
internal object KernelTitle {
    /** `MAX_TITLE_LENGTH`. */
    const val MAX_TITLE_LENGTH = 120

    /** `normalizeTitle`: trimmed and cut to [MAX_TITLE_LENGTH], null when blank. */
    fun normalize(title: String?): String? = title?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE_LENGTH)

    /** `deriveTitleFromPrompt`: the first line of the unwrapped prompt, without mode notices. */
    fun fromPrompt(prompt: String?): String? {
        val normalized = UserInputFormat.stripModeNotices(UserInputFormat.normalizeUserInput(prompt.orEmpty())).trim()
        if (normalized.isEmpty()) return null
        return normalize(normalized.split("\n").firstOrNull()?.trim())
    }
}
