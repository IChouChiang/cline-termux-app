package dev.cline.termux.ui.about

import android.content.Context
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * C13 data (customize-and-rest.md §10): the hand-maintained `assets/licenses/index.json`
 * `[{name, version, license, url, file, artifacts}]` with the texts beside it. [artifacts]
 * lists the Gradle coordinate prefixes an entry covers, so a unit test fails the build when a
 * dependency in `app/build.gradle.kts` has no entry (no licence plugin, CR OQ C-11).
 */
@Immutable
@Serializable
data class LicenseEntry(
    val name: String,
    val version: String,
    val license: String,
    val url: String,
    val file: String,
    val artifacts: List<String> = emptyList(),
) {
    /** True when this entry covers the `group:artifact` coordinate. */
    fun covers(coordinate: String): Boolean = artifacts.any { coordinate.startsWith(it) }
}

object Licenses {
    const val DIR = "licenses"
    const val INDEX = "$DIR/index.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<LicenseEntry> = json.decodeFromString(ListSerializer(LicenseEntry.serializer()), text)

    /** The bundled list (asset read on IO). */
    suspend fun load(context: Context): List<LicenseEntry> = withContext(Dispatchers.IO) {
        context.applicationContext.assets.open(INDEX).bufferedReader().use { parse(it.readText()) }
    }

    /** One licence text; null when the file is not bundled. */
    suspend fun text(context: Context, file: String): String? = withContext(Dispatchers.IO) {
        if (file.contains("..") || file.contains('/')) return@withContext null
        runCatching { context.applicationContext.assets.open("$DIR/$file").bufferedReader().use { it.readText() } }.getOrNull()
    }
}
