package dev.cline.termux.core.kernel.hub

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request

/** `GET /health` (no auth). */
@Serializable
data class HubHealth(
    val ok: Boolean = false,
    val protocolVersion: String? = null,
    val minClientProtocolVersion: String? = null,
    val maxClientProtocolVersion: String? = null,
    val coreVersion: String? = null,
    val buildId: String? = null,
    val buildEpochMs: Long? = null,
    val draining: Boolean = false,
    val host: String? = null,
    val port: Int? = null,
    val url: String? = null,
)

/** `GET /version` (no auth); `pid` + `startedAt` identify one daemon lifetime. */
@Serializable
data class HubVersion(
    val protocolVersion: String? = null,
    val minClientProtocolVersion: String? = null,
    val maxClientProtocolVersion: String? = null,
    val capabilities: List<String> = emptyList(),
    val coreVersion: String? = null,
    val buildId: String? = null,
    val buildEpochMs: Long? = null,
    val pid: Int? = null,
    val startedAt: String? = null,
)

/**
 * `~/.cline/data/locks/hub/production.json` (`HubServerDiscoveryRecord`), read over
 * RUN_COMMAND because the file is Termux-private. The token rotates on every
 * daemon start, so (hubId, pid, startedAt) tell whether a cached token is stale.
 */
@Serializable
data class HubDiscoveryRecord(
    val hubId: String,
    val protocolVersion: String,
    val authToken: String,
    val host: String,
    val port: Int,
    val url: String,
    val pid: Int? = null,
    val startedAt: String,
    val updatedAt: String? = null,
    val coreVersion: String? = null,
    val buildId: String? = null,
    val capabilities: List<String> = emptyList(),
) {
    val lifetimeKey: String get() = "$hubId:$pid:$startedAt"

    companion object {
        fun parse(json: String): HubDiscoveryRecord = HubJson.decodeFromString(serializer(), json)
    }
}

/** Default Hub endpoint on the phone (upstream's fixed loopback port). */
object HubEndpoints {
    const val DEFAULT_PORT = 25463
    fun healthUrl(port: Int = DEFAULT_PORT) = "http://127.0.0.1:$port/health"
    fun versionUrl(port: Int = DEFAULT_PORT) = "http://127.0.0.1:$port/version"
    fun socketUrl(port: Int = DEFAULT_PORT) = "ws://127.0.0.1:$port/hub"
}

/** Unauthenticated liveness probes against the loopback Hub. */
class HubProbe(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build(),
) {
    /** null when nothing answers on the port (daemon down or Termux asleep). */
    suspend fun health(port: Int = HubEndpoints.DEFAULT_PORT): HubHealth? =
        get(HubEndpoints.healthUrl(port))?.let { HubJson.decodeFromString(HubHealth.serializer(), it) }

    suspend fun version(port: Int = HubEndpoints.DEFAULT_PORT): HubVersion? =
        get(HubEndpoints.versionUrl(port))?.let { HubJson.decodeFromString(HubVersion.serializer(), it) }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (_: IOException) {
            null
        }
    }
}
