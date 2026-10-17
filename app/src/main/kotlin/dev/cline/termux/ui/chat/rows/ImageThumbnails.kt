package dev.cline.termux.ui.chat.rows

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Message images (HC §3.4.1 user attachments, §3.4.8 generated images; DS §4.2 carousel):
 * base64 decoded off the main thread with `inSampleSize` to the displayed size and cached in
 * one 32 MiB LruCache keyed `<messageId>:<index>`; a `surfaceContainerHighest` placeholder
 * holds the thumbnail's final box while it decodes. Tap opens the lightbox (screen D).
 */

/** Largest thumbnail box (desktop `max-h-56.25 max-w-56.25` = 225px). */
internal val ThumbnailMaxSize = 225.dp

/** Carousel thumbnail height for two or more generated images (DS §4.2). */
internal val CarouselThumbnailHeight = 160.dp

/**
 * The decoded-image cache budgets, sized from this process's heap limit (what
 * `ActivityManager.memoryClass` grants, or `largeMemoryClass` under `largeHeap`), which
 * `Runtime.maxMemory()` reports without a Context. Pure for tests.
 */
internal object ImageCacheBudget {
    private const val MIB = 1024L * 1024L

    /** Thumbnails: 1/16 of the heap, 16–48 MiB. */
    fun thumbnailBytes(heapBytes: Long = Runtime.getRuntime().maxMemory()): Int = (heapBytes / 16).coerceIn(16 * MIB, 48 * MIB).toInt()

    /**
     * Full-size lightbox pages: 1/6 of the heap, 40–96 MiB, so at least one page at the
     * largest decode edge (3072 px square, 36 MiB) always fits.
     */
    fun lightboxBytes(heapBytes: Long = Runtime.getRuntime().maxMemory()): Int = (heapBytes / 6).coerceIn(40 * MIB, 96 * MIB).toInt()
}

/** Process-wide decoded-thumbnail cache (HC §3.4.8). */
internal object ThumbnailCache {
    private val cache = object : LruCache<String, DecodedImage>(ImageCacheBudget.thumbnailBytes()) {
        override fun sizeOf(key: String, value: DecodedImage): Int = value.byteCount
    }

    fun get(key: String): DecodedImage? = cache.get(key)

    fun put(key: String, image: DecodedImage) {
        cache.put(key, image)
    }
}

/** A decoded thumbnail; [width]/[height] are the source's, for the aspect ratio. */
@Immutable
internal class DecodedImage(val bitmap: ImageBitmap?, val width: Int, val height: Int, val byteCount: Int) {
    val aspectRatio: Float get() = if (width > 0 && height > 0) width.toFloat() / height else 1f
}

/** The decode state: [Loading] keeps the placeholder box; [Failed] shows a broken-image glyph. */
internal sealed interface ThumbnailState {
    data object Loading : ThumbnailState
    data class Ready(val image: DecodedImage) : ThumbnailState
    data object Failed : ThumbnailState
}

/** `inSampleSize` so the decoded bitmap is at least [targetPx] on its longer side (power of two). */
internal fun sampleSizeFor(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    var sample = 1
    val longer = maxOf(width, height)
    while (longer / (sample * 2) >= targetPx) sample *= 2
    return sample
}

private fun decodeThumbnail(data: String, targetPx: Int): DecodedImage? {
    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetPx) }
    val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull() ?: return null
    return DecodedImage(bitmap.asImageBitmap(), bounds.outWidth, bounds.outHeight, bitmap.byteCount)
}

@Composable
internal fun rememberThumbnail(cacheKey: String, data: String, maxSize: Dp): ThumbnailState {
    val targetPx = with(LocalDensity.current) { maxSize.roundToPx() }
    val cached = ThumbnailCache.get(cacheKey)
    val state by produceState<ThumbnailState>(cached?.let { ThumbnailState.Ready(it) } ?: ThumbnailState.Loading, cacheKey, data, targetPx) {
        if (value is ThumbnailState.Ready) return@produceState
        val decoded = withContext(Dispatchers.Default) { decodeThumbnail(data, targetPx) }
        value = if (decoded == null) {
            ThumbnailState.Failed
        } else {
            ThumbnailCache.put(cacheKey, decoded)
            ThumbnailState.Ready(decoded)
        }
    }
    return state
}

/**
 * One thumbnail: fits within [maxSize] (or is [fixedHeight] tall in a carousel), `shapes.small`,
 * 1dp `outlineVariant` border on `surfaceContainerHighest`. [expandLabel] is the button's
 * TalkBack label ("Expand attachment N"); [alt] labels it when it is not tappable.
 */
@Composable
internal fun ImageThumbnail(
    cacheKey: String,
    image: ChatImage,
    alt: String,
    expandLabel: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxSize: Dp = ThumbnailMaxSize,
    fixedHeight: Dp? = null,
) {
    val state = rememberThumbnail(cacheKey, image.data, fixedHeight ?: maxSize)
    val shape = MaterialTheme.shapes.small
    val ratio = (state as? ThumbnailState.Ready)?.image?.aspectRatio ?: 1f
    val box = if (fixedHeight != null) {
        Modifier.height(fixedHeight).aspectRatio(ratio.coerceIn(0.25f, 4f))
    } else {
        Modifier.sizeIn(maxWidth = maxSize, maxHeight = maxSize).aspectRatio(ratio, matchHeightConstraintsFirst = ratio < 1f)
    }
    val semantics = if (onClick != null) {
        Modifier.clearAndSetSemantics {
            contentDescription = expandLabel
            role = Role.Button
            onClick { onClick(); true }
        }
    } else {
        Modifier.clearAndSetSemantics { contentDescription = alt }
    }
    Box(
        modifier = modifier
            .then(box)
            .then(if (onClick != null) Modifier.focusRing(shape) else Modifier)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .then(semantics)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is ThumbnailState.Ready -> state.image.bitmap?.let { bitmap ->
                Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Fit)
            }
            ThumbnailState.Failed -> Icon(
                ClineIcons.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            ThumbnailState.Loading -> Unit
        }
    }
}

/**
 * User attachments (desktop `MessageImages` for users): one thumbnail per line, 8dp apart,
 * each fitted within 225dp.
 */
@Composable
fun AttachmentImages(
    messageId: String,
    images: List<ChatImage>,
    onExpand: ((ImageGallery, Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val gallery = ImageGallery(GalleryKind.Attachment, images, images.indices.map { "$messageId:$it" })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        images.forEachIndexed { index, image ->
            ImageThumbnail(
                cacheKey = "$messageId:$index",
                image = image,
                alt = stringResource(R.string.msg_attachment_alt, index + 1),
                expandLabel = stringResource(R.string.msg_attachment_expand, index + 1),
                onClick = onExpand?.let { { it(gallery, index) } },
            )
        }
    }
}

/**
 * Generated images (assistant `message.images`, tool output images; HC §3.4.8): one image is
 * a single thumbnail within 225dp, two or more a `LazyRow` of 160dp-tall thumbnails 8dp apart.
 */
@Composable
fun GeneratedImages(
    cacheKeyPrefix: String,
    images: List<ChatImage>,
    onExpand: ((ImageGallery, Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (images.isEmpty()) return
    val gallery = ImageGallery(GalleryKind.Generated, images, images.indices.map { "$cacheKeyPrefix:$it" })
    if (images.size == 1) {
        ImageThumbnail(
            cacheKey = "$cacheKeyPrefix:0",
            image = images[0],
            alt = stringResource(R.string.msg_generated_alt, 1),
            expandLabel = stringResource(R.string.msg_generated_expand, 1),
            onClick = onExpand?.let { { it(gallery, 0) } },
            modifier = modifier,
        )
        return
    }
    LazyRow(
        modifier = modifier.height(CarouselThumbnailHeight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        itemsIndexed(images, key = { index, image -> "${image.id}:$index" }) { index, image ->
            ImageThumbnail(
                cacheKey = "$cacheKeyPrefix:$index",
                image = image,
                alt = stringResource(R.string.msg_generated_alt, index + 1),
                expandLabel = stringResource(R.string.msg_generated_expand, index + 1),
                onClick = onExpand?.let { { it(gallery, index) } },
                fixedHeight = CarouselThumbnailHeight,
            )
        }
    }
}
