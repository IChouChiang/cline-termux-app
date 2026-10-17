package dev.cline.termux.service

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cline.termux.BuildConfig
import dev.cline.termux.data.chat.sanitizeFileName
import java.io.File
import java.io.IOException

/**
 * The one FileProvider (authority [AUTHORITY], `res/xml/file_paths.xml`, D7) for diff and
 * output shares (changes-tools-checkpoints.md A.7), diagnostics bundles (customize-and-rest.md
 * §9.5) and camera captures (composer-approvals.md C1.9). Everything lives under `cacheDir`
 * and is swept after [MAX_AGE_MS] on app start (docs/07 §7.4). Callers run the file helpers
 * on `Dispatchers.IO`.
 */
object ShareFiles {
    /**
     * `${applicationId}.files` (the manifest's provider), so a debug build (`.debug`) and the
     * owner's app never claim the same authority (`INSTALL_FAILED_CONFLICTING_PROVIDER`).
     */
    const val AUTHORITY = BuildConfig.APPLICATION_ID + ".files"
    const val MAX_AGE_MS = 24 * 60 * 60 * 1000L

    /** A FileProvider root (the `cache-path` entries) plus the non-shared attachments scratch area. */
    enum class Area(val dirName: String, val shared: Boolean) {
        Share("share", true),
        Diagnostics("diagnostics", true),
        Camera("camera", true),
        Attachments("attachments", false),
    }

    fun dir(context: Context, area: Area): File = File(context.cacheDir, area.dirName).apply { mkdirs() }

    /**
     * A new file in [area] named after [displayName] (path separators and control characters
     * replaced; an existing name gets a " (n)" suffix before the extension).
     */
    fun newFile(context: Context, area: Area, displayName: String): File = uniqueFile(dir(context, area), sanitize(displayName))

    /** Writes [text] (UTF-8) to a new file in [area] and returns its content URI. */
    @Throws(IOException::class)
    fun writeText(context: Context, area: Area, displayName: String, text: String): Uri {
        val file = newFile(context, area, displayName)
        file.writeText(text, Charsets.UTF_8)
        return uriFor(context, file)
    }

    /** Writes [bytes] to a new file in [area] and returns its content URI. */
    @Throws(IOException::class)
    fun writeBytes(context: Context, area: Area, displayName: String, bytes: ByteArray): Uri {
        val file = newFile(context, area, displayName)
        file.writeBytes(bytes)
        return uriFor(context, file)
    }

    /** The content URI of a file under one of the shared areas. */
    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, AUTHORITY, file)

    /** An `ACTION_SEND` chooser for [uri] with read permission granted to the receiver. */
    fun shareIntent(uri: Uri, mimeType: String, title: CharSequence? = null): Intent {
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        title?.let { send.putExtra(Intent.EXTRA_TITLE, it) }
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** A camera capture target (`ActivityResultContracts.TakePicture` needs a writable content URI). */
    fun newCameraCapture(context: Context, stamp: Long): Pair<File, Uri> {
        val file = newFile(context, Area.Camera, "photo-$stamp.jpg")
        return file to uriFor(context, file)
    }

    /** Deletes files older than [maxAgeMs] under every area of [cacheDir]; returns how many went. */
    fun sweep(cacheDir: File, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Int {
        var removed = 0
        for (area in Area.entries) {
            val root = File(cacheDir, area.dirName)
            if (!root.isDirectory) continue
            root.walkBottomUp().forEach { file ->
                if (file == root) return@forEach
                if (file.isFile && nowMs - file.lastModified() > maxAgeMs) {
                    if (file.delete()) removed++
                } else if (file.isDirectory && file.list().isNullOrEmpty()) {
                    file.delete()
                }
            }
        }
        return removed
    }

    /** Unsafe characters replaced, non-ASCII text kept, cut at a grapheme boundary (DS §8.2.7). */
    internal fun sanitize(displayName: String): String = sanitizeFileName(displayName, fallback = "file", maxUnits = MAX_NAME)

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.').takeIf { it > 0 }
        val stem = dot?.let { name.substring(0, it) } ?: name
        val ext = dot?.let { name.substring(it) }.orEmpty()
        var n = 1
        while (candidate.exists()) candidate = File(dir, "$stem ($n)$ext").also { n++ }
        return candidate
    }

    private const val MAX_NAME = 120
}
