package dev.cline.termux.data.prefs

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Clock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What [JsonFileCache.read] hands back: the payload and when it was written. */
data class CachedValue<T>(val value: T, val savedAtMs: Long)

/**
 * A rebuildable JSON file under `filesDir` (docs/07 §7.4): one serializable payload wrapped
 * in a small envelope (`version`, `savedAtMs`). Writes go to a sibling temp file and are
 * moved into place atomically, so a process death mid-write leaves the previous file
 * intact. A file that cannot be parsed, or whose schema version differs, reads as empty and
 * is deleted. Every I/O call runs on [io]; concurrent writers are serialized.
 *
 * Secrets never belong here: callers write projections (SP §0.7 strips `apiKey`,
 * `configValues`, `baseUrl`; SS §1.8 stores titles and workspace paths only).
 */
open class JsonFileCache<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val version: Int,
    private val json: Json = DEFAULT_JSON,
    private val clock: Clock = Clock.systemUTC(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    @Serializable
    private class Envelope<T>(val version: Int, val savedAtMs: Long, val payload: T)

    private val envelopeSerializer = Envelope.serializer(serializer)
    private val lock = Mutex()

    val path: File get() = file

    suspend fun exists(): Boolean = withContext(io) { file.isFile }

    /** The cached payload, or null when there is none, it is corrupt or it is from another schema version. */
    suspend fun read(): CachedValue<T>? = withContext(io) {
        lock.withLock {
            if (!file.isFile) return@withLock null
            val envelope = runCatching { json.decodeFromString(envelopeSerializer, file.readText()) }.getOrNull()
            if (envelope == null || envelope.version != version) {
                file.delete()
                null
            } else {
                CachedValue(envelope.payload, envelope.savedAtMs)
            }
        }
    }

    /** Replaces the file atomically; a failure leaves the previous content in place. */
    suspend fun write(value: T): Unit = withContext(io) {
        lock.withLock {
            val text = json.encodeToString(envelopeSerializer, Envelope(version, clock.millis(), value))
            file.parentFile?.let { dir -> if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("cannot create ${dir.path}") }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text)
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                // Some filesystems refuse ATOMIC_MOVE with REPLACE_EXISTING; a plain replace is the fallback.
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    suspend fun clear(): Unit = withContext(io) {
        lock.withLock {
            file.delete()
            File(file.parentFile, "${file.name}.tmp").delete()
        }
    }

    companion object {
        val DEFAULT_JSON: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
