package dev.cline.termux.data.prefs

import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * `filesDir/sessions/list-cache.json` (SS §1.8, docs/07 §7.4): the last successful
 * `session.list` projection (≤ 200 summaries without `metadata`, the annotation overrides
 * and the schedule links), so the drawer is never blank while the kernel is down or still
 * connecting. The payload type belongs to `data/sessions`; this cache only stores and
 * returns it. Excluded from backup and device transfer (`data_extraction_rules.xml`).
 */
class SessionListCache<T>(
    filesDir: File,
    serializer: KSerializer<T>,
    json: Json = JsonFileCache.DEFAULT_JSON,
    clock: Clock = Clock.systemUTC(),
    io: CoroutineDispatcher = Dispatchers.IO,
) : JsonFileCache<T>(
    file = File(filesDir, RELATIVE_PATH),
    serializer = serializer,
    version = SCHEMA_VERSION,
    json = json,
    clock = clock,
    io = io,
) {
    companion object {
        const val RELATIVE_PATH = "sessions/list-cache.json"
        const val SCHEMA_VERSION = 1
    }
}
