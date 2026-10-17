package dev.cline.termux.data.prefs

import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * `filesDir/catalog-cache.json` (SP §0.7, docs/07 §7.4): the non-secret provider/model
 * projection (`ProviderListItem` minus `apiKey`, `configValues`, `baseUrl`, plus each
 * provider's `ProviderModel` list) so the model picker opens instantly without the helper.
 * The payload type belongs to `data/providers`; this cache stores and returns it. Excluded
 * from backup and device transfer.
 */
class CatalogCache<T>(
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
        const val RELATIVE_PATH = "catalog-cache.json"
        const val SCHEMA_VERSION = 1
    }
}
