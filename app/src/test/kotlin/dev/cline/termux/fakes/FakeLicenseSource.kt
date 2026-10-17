package dev.cline.termux.fakes

import dev.cline.termux.ui.about.LicenseEntry
import dev.cline.termux.ui.about.LicenseSource
import kotlinx.coroutines.CompletableDeferred

/**
 * Scripted [LicenseSource]. Reads suspend on [release] until the test completes it, so the
 * loading state is observable; [indexAnswer] and [texts] answer once released, a throw means "failed".
 */
class FakeLicenseSource(
    var indexAnswer: () -> List<LicenseEntry> = { emptyList() },
    var texts: Map<String, String> = emptyMap(),
    var textFailure: Exception? = null,
) : LicenseSource {
    val release = CompletableDeferred<Unit>()
    var indexReads = 0
    val textReads = mutableListOf<String>()

    override suspend fun index(): List<LicenseEntry> {
        indexReads++
        release.await()
        return indexAnswer()
    }

    override suspend fun text(file: String): String? {
        textReads += file
        release.await()
        textFailure?.let { throw it }
        return texts[file]
    }
}
