package dev.cline.termux.data.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import dev.cline.termux.core.kernel.termux.TermuxPaths
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.BreakIterator
import java.util.Base64
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * The composer's attachment pipeline (design/screens/composer-approvals.md C1.9; desktop
 * `handleAttachFiles`, `lib/image-attachments.ts` `cloudImageAttachmentError`,
 * `hooks/chat-session/attachments.ts`). [Attachments] holds the pure rules (counts, limits,
 * encodings, text decoding, names); [AndroidAttachmentIo] is the Android edge (content
 * resolver, decoder, EXIF, cache files); `TermuxExecutor.writeAttachment` (core/kernel)
 * materialises a text file in Termux through stdin chunks. File contents and names are never
 * logged (CA §0.8).
 */

/** One picked or pasted item before it is processed. */
interface AttachmentRef {
    /** The display name (`OpenableColumns.DISPLAY_NAME`, else the last path segment). */
    val displayName: String

    /** The resolver's MIME type, when it reports one. */
    val mimeType: String?
}

/** A re-encoded image stored under `cacheDir/attachments/` (never DataStore). */
data class EncodedImage(
    val file: File,
    /** `image/png` or `image/jpeg`. */
    val mime: String,
    /** Re-encoded byte count. */
    val byteSize: Long,
    /** 96px thumbnail held in memory for the chip. */
    val thumbnail: Bitmap?,
) {
    /** The size of its data URL payload: `4 * ceil(bytes / 3)`. */
    val base64Size: Long get() = Attachments.base64Length(byteSize)
}

sealed interface ImageEncodeResult {
    data class Ok(val image: EncodedImage) : ImageEncodeResult

    /** The decoder failed on a format it normally opens ("Could not read the image."). */
    data object Unreadable : ImageEncodeResult

    /** A format the decoder cannot open ("Choose PNG, JPEG, GIF, or WebP images."). */
    data object UnsupportedType : ImageEncodeResult
}

sealed interface TextReadResult {
    data class Ok(val text: String) : TextReadResult

    /** Over [Attachments.MAX_TEXT_FILE_BYTES]. */
    data object TooLarge : TextReadResult

    /** The decoded text contains U+0000 (the daemon's binary check). */
    data object NotText : TextReadResult

    /** The resolver could not open it. */
    data object Unreadable : TextReadResult
}

/** The image limit a new image broke (C1.9 step 5), or the count cap (step 2). */
enum class ImageLimit { TooMany, TooLarge, TotalTooLarge }

/** The Android edge of the pipeline; tests substitute a fake. */
interface AttachmentIo {
    suspend fun encodeImage(ref: AttachmentRef): ImageEncodeResult
    suspend fun readText(ref: AttachmentRef): TextReadResult
    suspend fun readBytes(file: File): ByteArray
    suspend fun delete(file: File)
}

object Attachments {
    /** Images per message (desktop cloud rule, applied locally). */
    const val MAX_IMAGES = 5

    /** Long edge after downscaling. */
    const val MAX_LONG_EDGE_PX = 2048

    /** A PNG re-encode larger than this falls back to JPEG. */
    const val MAX_PNG_BYTES = 1_572_864L

    const val JPEG_QUALITY = 85

    /** `DEFAULT_MAX_IMAGE_BASE64_BYTES` (5 MiB of base64 ≈ "3.75 MB"). */
    const val MAX_IMAGE_BASE64_BYTES = 5L * 1024 * 1024

    /** `DEFAULT_MAX_TOTAL_MEDIA_BYTES` (8 MiB of base64 ≈ "6 MB"). */
    const val MAX_TOTAL_MEDIA_BYTES = 8L * 1024 * 1024

    /** Android ceiling for text files (the daemon accepts up to 20,480,000 bytes). */
    const val MAX_TEXT_FILE_BYTES = 1_048_576L

    /** Thumbnail edge for the chip. */
    const val THUMBNAIL_PX = 96

    /** Graphemes before a chip label is middle-ellipsized. */
    const val CHIP_LABEL_GRAPHEMES = 24

    private val SUPPORTED_DECODE = setOf(
        "image/png", "image/jpeg", "image/jpg", "image/gif", "image/webp", "image/heic", "image/heif",
        "image/avif", "image/tiff", "image/bmp", "image/x-ms-bmp",
    )

    private val IMAGE_EXTENSIONS = mapOf(
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "jfif" to "image/jpeg",
        "gif" to "image/gif", "webp" to "image/webp", "heic" to "image/heic", "heif" to "image/heif",
        "avif" to "image/avif", "tif" to "image/tiff", "tiff" to "image/tiff", "bmp" to "image/bmp",
    )

    /** `4 * ceil(bytes / 3)`: the data URL payload size of [bytes] raw bytes. */
    fun base64Length(bytes: Long): Long = 4 * ceil(bytes / 3.0).toLong()

    /** The item is an image (MIME first, then the extension; desktop `file.type.startsWith("image/")`). */
    fun isImage(ref: AttachmentRef): Boolean = imageMime(ref) != null

    /** The image MIME of [ref], or null when it is not an image. */
    fun imageMime(ref: AttachmentRef): String? {
        val mime = ref.mimeType?.lowercase()
        if (mime != null && mime.startsWith("image/")) return mime
        if (mime != null && mime != "application/octet-stream") return null
        return IMAGE_EXTENSIONS[ref.displayName.substringAfterLast('.', "").lowercase()]
    }

    /** True when the platform decoders can open [mime] (else "Choose PNG, JPEG, GIF, or WebP images."). */
    fun decodable(mime: String?): Boolean = mime != null && mime.lowercase() in SUPPORTED_DECODE

    /** C1.9 step 2: how many of [incoming] images fit next to [current], and whether the cap was hit. */
    fun acceptCount(current: Int, incoming: Int): Pair<Int, Boolean> {
        val remaining = (MAX_IMAGES - current).coerceAtLeast(0)
        return minOf(remaining, incoming) to (incoming > remaining)
    }

    /** C1.9 step 5 on the re-encoded size of a new image next to the message's other images. */
    fun sizeError(newBase64: Long, existingBase64: Long): ImageLimit? = when {
        newBase64 > MAX_IMAGE_BASE64_BYTES -> ImageLimit.TooLarge
        existingBase64 + newBase64 > MAX_TOTAL_MEDIA_BYTES -> ImageLimit.TotalTooLarge
        else -> null
    }

    /** C1.9 step 3: the size that keeps the long edge at or under [maxEdge]. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE_PX): Pair<Int, Int> {
        val longEdge = max(width, height)
        if (longEdge <= maxEdge || longEdge <= 0) return width to height
        val scale = maxEdge.toDouble() / longEdge
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** C1.9 step 4: PNG only for alpha sources that are not GIF or WebP (and only while ≤ 1.5 MB). */
    fun prefersPng(sourceMime: String?, hasAlpha: Boolean): Boolean {
        val mime = sourceMime?.lowercase()
        return hasAlpha && mime != "image/gif" && mime != "image/webp"
    }

    /**
     * C1.9 text step 1: UTF-8 with malformed sequences replaced (desktop `file.text()`); null
     * when the text contains U+0000 (the daemon's "Cannot read binary file into context.").
     */
    fun decodeText(bytes: ByteArray): String? {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        return if (text.indexOf('\u0000') >= 0) null else text
    }

    /** `data:<mime>;base64,<…>` for `attachments.userImages`. */
    fun dataUrl(mime: String, bytes: ByteArray): String = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

    /** The Termux folder of one text attachment: a fresh uuid per file, so the `.part` never pre-exists. */
    fun termuxDir(sessionId: String?, uuid: String = UUID.randomUUID().toString()): String =
        "${TermuxPaths.ATTACHMENTS_DIR}/${sessionId ?: "draft"}/$uuid"

    /** Chip label: middle-ellipsized at [max] graphemes ("…"), never cutting a grapheme. */
    fun chipLabel(name: String, max: Int = CHIP_LABEL_GRAPHEMES): String {
        val bounds = graphemeBounds(name)
        val count = bounds.size - 1
        if (count <= max) return name
        val head = max / 2
        val tail = max - 1 - head
        return name.substring(0, bounds[head]) + "…" + name.substring(bounds[count - tail])
    }

    private fun graphemeBounds(text: String): List<Int> {
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        val out = ArrayList<Int>()
        var b = it.first()
        while (b != BreakIterator.DONE) {
            out += b
            b = it.next()
        }
        return out
    }
}

/** An item the content resolver handed over (photo picker, documents, camera, paste, drop). */
class UriAttachmentRef(val uri: Uri, override val displayName: String, override val mimeType: String?) : AttachmentRef

/** Display name and MIME of a picked, pasted or dropped [uri] (the resolver's columns). */
suspend fun describeAttachment(context: Context, uri: Uri, fallbackName: String? = null): UriAttachmentRef = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    var name: String? = null
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0)
        }
    }
    val mime = runCatching { resolver.getType(uri) }.getOrNull()
    UriAttachmentRef(uri, name ?: fallbackName ?: uri.lastPathSegment ?: "attachment", mime)
}

/** The Android edge: decode with EXIF orientation, downscale, re-encode, cache; read text. */
class AndroidAttachmentIo(private val context: Context) : AttachmentIo {
    private val resolver: ContentResolver get() = context.contentResolver

    private val cacheDir: File get() = File(context.cacheDir, "attachments").apply { mkdirs() }

    /** Display name and MIME of [uri] (a camera capture passes its own name). */
    suspend fun describe(uri: Uri, fallbackName: String? = null): UriAttachmentRef = describeAttachment(context, uri, fallbackName)

    override suspend fun encodeImage(ref: AttachmentRef): ImageEncodeResult = withContext(Dispatchers.IO) {
        val uri = (ref as? UriAttachmentRef)?.uri ?: return@withContext ImageEncodeResult.Unreadable
        val mime = Attachments.imageMime(ref)
        val bitmap = runCatching { decode(uri) }.getOrNull()
            ?: return@withContext if (Attachments.decodable(mime)) ImageEncodeResult.Unreadable else ImageEncodeResult.UnsupportedType
        try {
            val (bytes, outMime) = encode(bitmap, mime)
            val ext = if (outMime == "image/png") "png" else "jpg"
            val file = File(cacheDir, "${UUID.randomUUID()}.$ext")
            file.writeBytes(bytes)
            val thumb = thumbnail(bitmap)
            ImageEncodeResult.Ok(EncodedImage(file, outMime, bytes.size.toLong(), thumb))
        } catch (_: Exception) {
            ImageEncodeResult.Unreadable
        } finally {
            bitmap.recycle()
        }
    }

    override suspend fun readText(ref: AttachmentRef): TextReadResult = withContext(Dispatchers.IO) {
        val uri = (ref as? UriAttachmentRef)?.uri ?: return@withContext TextReadResult.Unreadable
        val bytes = try {
            resolver.openInputStream(uri)?.use { it.readCapped(Attachments.MAX_TEXT_FILE_BYTES + 1) }
        } catch (_: Exception) {
            null
        } ?: return@withContext TextReadResult.Unreadable
        if (bytes.size > Attachments.MAX_TEXT_FILE_BYTES) return@withContext TextReadResult.TooLarge
        Attachments.decodeText(bytes)?.let { TextReadResult.Ok(it) } ?: TextReadResult.NotText
    }

    override suspend fun readBytes(file: File): ByteArray = withContext(Dispatchers.IO) { file.readBytes() }

    override suspend fun delete(file: File) {
        withContext(Dispatchers.IO) { runCatching { file.delete() } }
    }

    /** Decodes honouring EXIF orientation, already downscaled to the 2048px long edge. */
    private fun decode(uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(resolver, uri)
            // ImageDecoder applies the EXIF orientation itself; GIF decodes its first frame.
            return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (w, h) = Attachments.targetSize(info.size.width, info.size.height)
                if (w != info.size.width || h != info.size.height) decoder.setTargetSize(w, h)
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= Attachments.MAX_LONG_EDGE_PX) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = resolver.openInputStream(uri)?.use { stream ->
            runCatching { ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrNull()
        } ?: ExifInterface.ORIENTATION_NORMAL
        val oriented = applyOrientation(decoded, orientation)
        val (w, h) = Attachments.targetSize(oriented.width, oriented.height)
        if (w == oriented.width && h == oriented.height) return oriented
        return Bitmap.createScaledBitmap(oriented, w, h, true).also { if (it !== oriented) oriented.recycle() }
    }

    private fun applyOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { if (it !== bitmap) bitmap.recycle() }
    }

    /** PNG for alpha sources while ≤ 1.5 MB, else JPEG 85 composited on white. */
    private fun encode(bitmap: Bitmap, sourceMime: String?): Pair<ByteArray, String> {
        if (Attachments.prefersPng(sourceMime, bitmap.hasAlpha())) {
            val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            if (png.size <= Attachments.MAX_PNG_BYTES) return png to "image/png"
        }
        val opaque = if (bitmap.hasAlpha()) {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { out ->
                Canvas(out).apply { drawColor(Color.WHITE); drawBitmap(bitmap, 0f, 0f, null) }
            }
        } else {
            bitmap
        }
        val jpeg = ByteArrayOutputStream().also { opaque.compress(Bitmap.CompressFormat.JPEG, Attachments.JPEG_QUALITY, it) }.toByteArray()
        if (opaque !== bitmap) opaque.recycle()
        return jpeg to "image/jpeg"
    }

    private fun thumbnail(bitmap: Bitmap): Bitmap? = runCatching {
        val (w, h) = Attachments.targetSize(bitmap.width, bitmap.height, Attachments.THUMBNAIL_PX)
        Bitmap.createScaledBitmap(bitmap, w, h, true).let { if (it === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else it }
    }.getOrNull()

    private fun InputStream.readCapped(limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < limit) {
            val n = read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }
}
