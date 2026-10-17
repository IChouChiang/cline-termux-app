package dev.cline.termux.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.view.WindowCompat
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.service.ShareFiles
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.chat.rows.GalleryKind
import dev.cline.termux.ui.chat.rows.ImageCacheBudget
import dev.cline.termux.ui.chat.rows.ImageGallery
import dev.cline.termux.ui.chat.rows.ThumbnailCache
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.findActivity
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.ClineDarkScheme
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClinePreviewTheme
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/*
 * Screen D (home-chat.md §5): the image lightbox / carousel. An in-window overlay, not a
 * Dialog (§5.2): the host places [ImageLightboxOverlay] in the app's root Box above the
 * NavHost / pane scaffold, so it covers rail and list pane, draws under the system bars and
 * keeps the app-wide snackbar state. The transcript opens it through [LightboxState]; a
 * caller that passes the thumbnail's FocusRequester to [LightboxState.open] gets focus back
 * on that thumbnail when the lightbox closes.
 */

// ---------------------------------------------------------------- state

/**
 * What the lightbox shows: all images of one message or tool row, starting at [startIndex].
 * [returnFocus] is the opening thumbnail's requester; focus goes back to it on close
 * (§5.4 / §5.6, DS §8.1.7).
 */
@Immutable
data class LightboxTarget(
    val sessionId: String?,
    val gallery: ImageGallery,
    val startIndex: Int,
    val returnFocus: FocusRequester? = null,
)

/** Hoisted by the host above the NavHost; the transcript opens it, the overlay renders it. */
@Stable
class LightboxState {
    var target: LightboxTarget? by mutableStateOf(null)
        private set

    /** The thumbnail to refocus once the overlay has left composition; set by [close]. */
    var pendingFocusReturn: FocusRequester? by mutableStateOf(null)
        private set

    val isOpen: Boolean get() = target != null

    fun open(sessionId: String?, gallery: ImageGallery, index: Int, returnFocus: FocusRequester? = null) {
        if (gallery.images.isEmpty()) return
        pendingFocusReturn = null
        target = LightboxTarget(sessionId, gallery, index.coerceIn(0, gallery.images.lastIndex), returnFocus)
    }

    /** Every dismissal (close button, back commit, scrim tap, swipe-down, Esc) lands here. */
    fun close() {
        pendingFocusReturn = target?.returnFocus
        target = null
    }

    /** Hands the pending requester to the overlay once; null when there is none. */
    fun takeFocusReturn(): FocusRequester? = pendingFocusReturn.also { pendingFocusReturn = null }

    /**
     * §3.11 / §5.4: a lightbox belonging to another session closes when the session is switched.
     * Its thumbnail is no longer on screen, so no focus is handed back.
     */
    fun closeUnlessSession(sessionId: String?) {
        if (target != null && target?.sessionId != sessionId) {
            pendingFocusReturn = null
            target = null
        }
    }
}

@Composable
fun rememberLightboxState(): LightboxState = remember { LightboxState() }

/**
 * §5.6: while the lightbox is up, the content behind it leaves the accessibility tree. The host
 * applies this to the NavHost / pane container.
 */
fun Modifier.hiddenBehindLightbox(state: LightboxState): Modifier =
    if (state.isOpen) this.clearAndSetSemantics { } else this

// ---------------------------------------------------------------- pure rules

/** Lightbox geometry and zoom rules (§5.3, §5.4), pure for tests. */
object LightboxRules {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f
    const val KEY_ZOOM_STEP = 1.25f

    /** Swipe down at 1× by more than this closes (§5.4, Android). */
    const val DISMISS_DRAG_DP = 96

    /**
     * Full-size decode bound: twice the window for zoom (§5.5), capped at 3072 px on the long
     * edge (inside §5.5's 4096 ceiling) so one decoded page (≤ 36 MiB) always fits the cache
     * and swiping back does not decode it again.
     */
    const val MAX_DECODE_EDGE_PX = 3072

    /** The full-size cache key: image, page and bound; never a hash of the multi-MB payload. */
    fun cacheKey(image: ChatImage, index: Int, maxEdge: Int): String = "${image.id}:$index:$maxEdge"

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Double tap toggles 1× ↔ 2.5×. */
    fun doubleTapScale(current: Float): Float = if (current > MIN_SCALE) MIN_SCALE else DOUBLE_TAP_SCALE

    /** The `ContentScale.Fit` box of an [intrinsic] image inside [container], centred. */
    fun fittedRect(container: Size, intrinsic: Size): Rect {
        if (container.width <= 0f || container.height <= 0f || intrinsic.width <= 0f || intrinsic.height <= 0f) return Rect.Zero
        val ratio = min(container.width / intrinsic.width, container.height / intrinsic.height)
        val width = intrinsic.width * ratio
        val height = intrinsic.height * ratio
        val left = (container.width - width) / 2f
        val top = (container.height - height) / 2f
        return Rect(left, top, left + width, top + height)
    }

    /** Pan limits when zoomed: the scaled image may not leave the container (per axis, centred when smaller). */
    fun clampPan(offset: Offset, scale: Float, content: Size, container: Size): Offset {
        val maxX = max(0f, (content.width * scale - container.width) / 2f)
        val maxY = max(0f, (content.height * scale - container.height) / 2f)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /** The pan that keeps [tap] fixed when zooming to [scale] around the container [center] (graphicsLayer origin). */
    fun panForZoomAt(tap: Offset, center: Offset, scale: Float): Offset = (tap - center) * (1f - scale)

    /** Whether a tap at [position] lands on the displayed image (zoomed by [scale], panned by [pan]). */
    fun hitsImage(position: Offset, fitted: Rect, scale: Float, pan: Offset, container: Size): Boolean {
        if (fitted.isEmpty) return false
        val center = Offset(container.width / 2f, container.height / 2f)
        val topLeft = center + (fitted.topLeft - center) * scale + pan
        val bottomRight = center + (fitted.bottomRight - center) * scale + pan
        return Rect(topLeft, bottomRight).contains(position)
    }

    /** "N / M" values: 1-based page and count; null when there is a single image (no counter). */
    fun counter(page: Int, count: Int): Pair<Int, Int>? = if (count > 1) (page + 1) to count else null

    /** Long-edge pixel bound for the full decode of a window [windowPx]. */
    fun decodeEdge(windowPx: IntSize): Int = min(MAX_DECODE_EDGE_PX, max(windowPx.width, windowPx.height) * 2).coerceAtLeast(1)

    /** The smallest power-of-two `inSampleSize` that keeps the longer side within [maxEdge]. */
    fun sampleToFit(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        val longer = max(width, height)
        while (longer / sample > maxEdge) sample *= 2
        return sample
    }

    /** `cacheDir/share/<id>-<n>.<ext>` (§5.4); the id is sanitised by `ShareFiles`. */
    fun shareFileName(image: ChatImage, index: Int): String = "${image.id}-${index + 1}.${extensionFor(image.mediaType)}"

    fun extensionFor(mediaType: String): String = when (mediaType.trim().lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        else -> "img"
    }
}

// ---------------------------------------------------------------- decoding

/** A decoded lightbox image, or why it is not one. */
private sealed interface LightboxImage {
    data class Loading(val preview: ImageBitmap?) : LightboxImage
    data class Loaded(val bitmap: ImageBitmap) : LightboxImage
    data object Failed : LightboxImage
}

/** Full-size decodes, keyed by [LightboxRules.cacheKey]; sized from the heap ([ImageCacheBudget]). */
private object LightboxCache {
    private val maxBytes = ImageCacheBudget.lightboxBytes()
    private val cache = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    fun get(key: String): ImageBitmap? = cache.get(key)
    fun put(key: String, bitmap: ImageBitmap) {
        // A page larger than the whole budget would only evict everything and itself.
        if (bitmap.width.toLong() * bitmap.height * 4 <= maxBytes) cache.put(key, bitmap)
    }
}

/** One Base64 decode, then bounds and pixels from the same bytes (off Main). */
private fun decodeBounded(data: String, maxEdge: Int): ImageBitmap? {
    val bytes = runCatching { Base64.decode(data, Base64.DEFAULT) }.getOrNull() ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply { inSampleSize = LightboxRules.sampleToFit(bounds.outWidth, bounds.outHeight, maxEdge) }
    return runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) }.getOrNull()?.asImageBitmap()
}

/**
 * §5.5: Loading shows the cached thumbnail (the one the user tapped, [thumbnailKey]) scaled
 * up under the spinner while the full image decodes once on Default.
 */
@Composable
private fun rememberLightboxImage(image: ChatImage, index: Int, thumbnailKey: String?, maxEdge: Int): LightboxImage {
    val cacheKey = LightboxRules.cacheKey(image, index, maxEdge)
    if (LocalInspectionMode.current) {
        // Previews decode synchronously so the loaded state renders.
        return remember(cacheKey) { decodeBounded(image.data, maxEdge)?.let { LightboxImage.Loaded(it) } ?: LightboxImage.Failed }
    }
    val initial = remember(cacheKey, thumbnailKey) {
        LightboxCache.get(cacheKey)?.let { LightboxImage.Loaded(it) }
            ?: LightboxImage.Loading(thumbnailKey?.let { ThumbnailCache.get(it) }?.bitmap)
    }
    val state by produceState(initial, cacheKey) {
        if (value is LightboxImage.Loaded) return@produceState
        val full = withContext(Dispatchers.Default) { decodeBounded(image.data, maxEdge) }
        value = if (full == null) LightboxImage.Failed else LightboxImage.Loaded(full).also { LightboxCache.put(cacheKey, full) }
    }
    return state
}

// ---------------------------------------------------------------- zoom

/** One page's zoom and pan; reset when the page changes (§5.4). */
@Stable
private class PageZoom {
    var scale by mutableFloatStateOf(1f)
    var pan by mutableStateOf(Offset.Zero)

    /** Layout facts written by the page on each composition; read only by gesture handlers. */
    var content: Size = Size.Zero
    var container: Size = Size.Zero

    val zoomed: Boolean get() = scale > LightboxRules.MIN_SCALE

    fun reset() {
        scale = LightboxRules.MIN_SCALE
        pan = Offset.Zero
    }

    fun zoomTo(target: Float, around: Offset? = null) {
        val next = LightboxRules.clampScale(target)
        val center = Offset(container.width / 2f, container.height / 2f)
        val wanted = if (around != null) LightboxRules.panForZoomAt(around, center, next) else pan * (next / scale.coerceAtLeast(0.01f))
        scale = next
        pan = if (next <= LightboxRules.MIN_SCALE) Offset.Zero else LightboxRules.clampPan(wanted, next, content, container)
    }

    fun transform(zoomChange: Float, panChange: Offset) {
        val next = LightboxRules.clampScale(scale * zoomChange)
        scale = next
        pan = if (next <= LightboxRules.MIN_SCALE) Offset.Zero else LightboxRules.clampPan(pan + panChange, next, content, container)
    }
}

// ---------------------------------------------------------------- overlay

/**
 * The shell's one lightbox (HC §5.2): `AppShell` provides it and draws [ImageLightboxOverlay]
 * last in its root `Box`, above the rail, the list pane, the NavHost and the supporting pane,
 * so the scrim covers the whole window and nothing behind it can be reached. Null outside the
 * shell (previews, tests): the chat screen then hosts its own.
 */
val LocalShellLightbox = staticCompositionLocalOf<LightboxState?> { null }

/**
 * The lightbox overlay (§5.2). Renders nothing while [state] is closed and reports
 * [BackLevel.Lightbox] while open (the compact drawer's edge drag stands down). Place it last
 * in the shell's root `Box` (above the NavHost and panes) so it covers everything; it sets
 * `zIndex(10f)` itself.
 */
@Composable
fun ImageLightboxOverlay(state: LightboxState, modifier: Modifier = Modifier) {
    val target = state.target
    val focusReturn = state.pendingFocusReturn
    // §5.4 / §5.6: once the overlay has left composition, focus returns to the thumbnail.
    LaunchedEffect(target, focusReturn) {
        if (target == null && focusReturn != null) {
            state.takeFocusReturn()?.let { runCatching { it.requestFocus() } }
        }
    }
    ReportBackLevel(BackLevel.Lightbox, target != null)
    if (target == null) return
    key(target) {
        LightboxContent(target = target, onClose = state::close, modifier = modifier)
    }
}

@Composable
private fun LightboxContent(target: LightboxTarget, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val images = target.gallery.images
    val kind = target.gallery.kind
    val pager = rememberPagerState(initialPage = target.startIndex) { images.size }
    val zooms = remember { mutableStateMapOf<Int, PageZoom>() }
    val failed = remember { mutableStateMapOf<Int, Boolean>() }
    var chromeVisible by remember { mutableStateOf(true) }
    var backProgress by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val reducedMotion = ClineTheme.reducedMotion

    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect { backProgress = it.progress }
            onClose()
        } catch (e: CancellationException) {
            backProgress = 0f
            throw e
        }
    }
    LightIconsOnDarkScrim()
    LaunchedEffect(pager.currentPage) {
        zooms.forEach { (page, zoom) -> if (page != pager.currentPage) zoom.reset() }
    }

    val currentZoom = zooms[pager.currentPage]
    val zoomed = currentZoom?.zoomed == true
    fun goTo(page: Int) {
        if (page !in images.indices) return
        scope.launch { if (reducedMotion) pager.scrollToPage(page) else pager.animateScrollToPage(page) }
    }

    MaterialTheme(colorScheme = ClineDarkScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            val title = stringResource(R.string.lightbox_title)
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .zIndex(10f)
                    .semantics { paneTitle = title }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.Escape -> onClose().let { true }
                            Key.DirectionLeft -> goTo(pager.currentPage - 1).let { true }
                            Key.DirectionRight -> goTo(pager.currentPage + 1).let { true }
                            Key.Plus, Key.Equals, Key.NumPadAdd -> currentZoom?.zoomTo(currentZoom.scale * LightboxRules.KEY_ZOOM_STEP).let { true }
                            Key.Minus, Key.NumPadSubtract -> currentZoom?.zoomTo(currentZoom.scale / LightboxRules.KEY_ZOOM_STEP).let { true }
                            else -> false
                        }
                    },
            ) {
                LightboxScrim(alpha = SCRIM_ALPHA * (1f - backProgress), onClose = onClose)
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = images.size > 1 && !zoomed,
                    beyondViewportPageCount = 0,
                    key = { page -> "${images[page].id}:$page" },
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = 1f - BACK_SCALE_DELTA * backProgress
                            scaleX = scale
                            scaleY = scale
                        },
                ) { page ->
                    val zoom = zooms.getOrPut(page) { PageZoom() }
                    LightboxPage(
                        image = images[page],
                        index = page,
                        thumbnailKey = target.gallery.thumbnailKeys.getOrNull(page),
                        kind = kind,
                        zoom = zoom,
                        chromeVisible = chromeVisible,
                        onToggleChrome = { chromeVisible = !chromeVisible },
                        onClose = onClose,
                        onFailed = { failed[page] = true },
                    )
                }
                LightboxTopBar(
                    visible = chromeVisible,
                    reducedMotion = reducedMotion,
                    counter = LightboxRules.counter(pager.currentPage, images.size),
                    shareEnabled = failed[pager.currentPage] != true,
                    image = images.getOrNull(pager.currentPage),
                    index = pager.currentPage,
                    onClose = onClose,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                if (images.size > 1) {
                    LightboxArrows(
                        visible = chromeVisible && !zoomed,
                        reducedMotion = reducedMotion,
                        kind = kind,
                        pager = pager,
                        onPage = ::goTo,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                // §5.2: snackbars (share failure) render above the scrim while the overlay is up.
                SnackbarHost(
                    hostState = LocalSnackbarHost.current,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(Spacing.l),
                )
            }
        }
    }
}

/** The 95 % scrim: consumes every pointer change behind the controls; tap closes (desktop backdrop). */
@Composable
private fun LightboxScrim(alpha: Float, onClose: () -> Unit) {
    val backdrop = stringResource(R.string.lightbox_backdrop)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = alpha.coerceIn(0f, 1f)))
            .focusProperties { canFocus = false }
            // Nothing behind the overlay may react: every change is consumed, a tap closes.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClose() }) }
            .semantics {
                contentDescription = backdrop
                onClick { onClose(); true }
            },
    )
}

/** One page: decode, zoom, pan, double tap, swipe-down-to-close, tap chrome / tap outside closes. */
@Composable
private fun LightboxPage(
    image: ChatImage,
    index: Int,
    thumbnailKey: String?,
    kind: GalleryKind,
    zoom: PageZoom,
    chromeVisible: Boolean,
    onToggleChrome: () -> Unit,
    onClose: () -> Unit,
    onFailed: () -> Unit,
) {
    val density = LocalDensity.current
    val dismissPx = with(density) { LightboxRules.DISMISS_DRAG_DP.dp.toPx() }
    val margin = with(density) { Spacing.l.toPx() }
    val alt = stringResource(if (kind == GalleryKind.Attachment) R.string.msg_attachment_alt else R.string.msg_generated_alt, index + 1)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val windowPx = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) }
        val decoded = rememberLightboxImage(image, index, thumbnailKey, LightboxRules.decodeEdge(windowPx))
        LaunchedEffect(decoded) { if (decoded is LightboxImage.Failed) onFailed() }
        val bitmap = when (decoded) {
            is LightboxImage.Loaded -> decoded.bitmap
            is LightboxImage.Loading -> decoded.preview
            LightboxImage.Failed -> null
        }
        val container = Size(windowPx.width - 2 * margin, windowPx.height - 2 * margin)
        val fitted = if (bitmap != null) LightboxRules.fittedRect(container, Size(bitmap.width.toFloat(), bitmap.height.toFloat())) else Rect.Zero
        zoom.container = container
        zoom.content = fitted.size
        var dragY by remember { mutableFloatStateOf(0f) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ -> zoom.transform(zoomChange, panChange) }

        if (decoded is LightboxImage.Failed) {
            LightboxDecodeFailure(chromeVisible = chromeVisible, onTap = onToggleChrome)
            return@BoxWithConstraints
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.l)
                .chromeToggleSemantics(chromeVisible, onToggleChrome)
                .pointerInput(fitted) {
                    detectTapGestures(
                        onTap = { position ->
                            if (LightboxRules.hitsImage(position, fitted, zoom.scale, zoom.pan, container)) onToggleChrome() else onClose()
                        },
                        onDoubleTap = { position -> zoom.zoomTo(LightboxRules.doubleTapScale(zoom.scale), around = position) },
                    )
                }
                .draggable(
                    state = rememberDraggableState { delta -> dragY = (dragY + delta).coerceAtLeast(0f) },
                    orientation = Orientation.Vertical,
                    enabled = !zoom.zoomed,
                    onDragStopped = { if (dragY > dismissPx) onClose() else dragY = 0f },
                )
                .transformable(state = transformState, canPan = { zoom.zoomed }),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = alt,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom.scale
                            scaleY = zoom.scale
                            translationX = zoom.pan.x
                            translationY = zoom.pan.y + dragY
                            alpha = 1f - (dragY / (dismissPx * 3f)).coerceIn(0f, 0.6f)
                        }
                        .clip(MaterialTheme.shapes.large),
                )
            }
            if (decoded is LightboxImage.Loading) {
                CircularProgressIndicator(Modifier.size(LoadingSpinnerSize))
            }
        }
    }
}

/**
 * DS §8.1.1 / §8.1.3: a tap on the page shows or hides the controls, so TalkBack gets the same
 * labelled action ("Hide controls" / "Show controls") and hears whether they are on screen.
 * The page's image description merges in, so the one node reads both.
 */
@Composable
private fun Modifier.chromeToggleSemantics(chromeVisible: Boolean, onToggleChrome: () -> Unit): Modifier {
    val actionLabel = stringResource(if (chromeVisible) R.string.lightbox_hide_controls else R.string.lightbox_show_controls)
    val state = stringResource(if (chromeVisible) R.string.lightbox_controls_shown else R.string.lightbox_controls_hidden)
    return semantics(mergeDescendants = true) {
        onClick(label = actionLabel) { onToggleChrome(); true }
        stateDescription = state
    }
}

/** §5.5 decode failure: `broken_image` 48dp + "Generated image could not be rendered.". */
@Composable
private fun LightboxDecodeFailure(chromeVisible: Boolean, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .chromeToggleSemantics(chromeVisible, onTap)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(ClineIcons.BrokenImage, contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.size(Spacing.m))
        Text(
            text = stringResource(R.string.media_unrenderable_image),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Close · counter · share, transparent, under the status bar and cutout (§5.3). */
@Composable
private fun LightboxTopBar(
    visible: Boolean,
    reducedMotion: Boolean,
    counter: Pair<Int, Int>?,
    shareEnabled: Boolean,
    image: ChatImage?,
    index: Int,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { closeFocus.requestFocus() } }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(if (reducedMotion) 0 else ClineMotion.Short3)),
        exit = fadeOut(tween(if (reducedMotion) 0 else ClineMotion.Short3)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
                .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val closeLabel = stringResource(R.string.lightbox_close)
            PlainTooltipBox(text = stringResource(R.string.lightbox_close_tooltip)) {
                IconButton(onClick = onClose, modifier = Modifier.focusRequester(closeFocus)) {
                    Icon(ClineIcons.Chrome.Close, contentDescription = closeLabel)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                if (counter != null) {
                    Text(
                        text = stringResource(R.string.lightbox_counter, counter.first, counter.second),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
            if (image != null) {
                ShareImageButton(image = image, index = index, enabled = shareEnabled)
            }
        }
    }
}

/** Share (Android, §5.4): bytes → `cacheDir/share/…` → FileProvider → `ACTION_SEND` chooser "Share image". */
@Composable
private fun ShareImageButton(image: ChatImage, index: Int, enabled: Boolean) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.lightbox_share)
    val failure = stringResource(R.string.lightbox_share_error)
    PlainTooltipBox(text = label) {
        IconButton(
            enabled = enabled,
            onClick = {
                scope.launch {
                    val shared = runCatching {
                        val uri = withContext(Dispatchers.IO) {
                            val bytes = Base64.decode(image.data, Base64.DEFAULT)
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                            check(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
                            ShareFiles.writeBytes(context, ShareFiles.Area.Share, LightboxRules.shareFileName(image, index), bytes)
                        }
                        context.startActivity(ShareFiles.shareIntent(uri, image.mediaType, label))
                    }.isSuccess
                    if (!shared) snackbar.showReplacing(failure)
                }
            },
        ) {
            Icon(ClineIcons.Share, contentDescription = label)
        }
    }
}

/** Previous / next (only with more than one image; hidden while zoomed; disabled at the ends). */
@Composable
private fun LightboxArrows(
    visible: Boolean,
    reducedMotion: Boolean,
    kind: GalleryKind,
    pager: PagerState,
    onPage: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val previous = stringResource(if (kind == GalleryKind.Generated) R.string.lightbox_prev_generated else R.string.lightbox_prev_attachment)
    val next = stringResource(if (kind == GalleryKind.Generated) R.string.lightbox_next_generated else R.string.lightbox_next_attachment)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(if (reducedMotion) 0 else ClineMotion.Short3)),
        exit = fadeOut(tween(if (reducedMotion) 0 else ClineMotion.Short3)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(Spacing.xs),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlainTooltipBox(text = previous) {
                IconButton(onClick = { onPage(pager.currentPage - 1) }, enabled = pager.currentPage > 0) {
                    // chevron_right is auto-mirrored; rotated it points back in both directions.
                    Icon(ClineIcons.ChevronRight, contentDescription = previous, modifier = Modifier.rotate(180f))
                }
            }
            PlainTooltipBox(text = next) {
                IconButton(onClick = { onPage(pager.currentPage + 1) }, enabled = pager.currentPage < pager.pageCount - 1) {
                    Icon(ClineIcons.ChevronRight, contentDescription = next)
                }
            }
        }
    }
}

/** Light status / navigation bar icons on the dark scrim while shown, restored on close (§5.2). */
@Composable
private fun LightIconsOnDarkScrim() {
    val view = LocalView.current
    if (view.isInEditMode) return
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, view)
            val status = controller.isAppearanceLightStatusBars
            val navigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = status
                controller.isAppearanceLightNavigationBars = navigation
            }
        }
    }
}

/** DS §4.2 / §5.3: the scrim at 95 %. */
private const val SCRIM_ALPHA = 0.95f

/** §5.4 predictive back: content scales 1 → 0.9 with progress. */
private const val BACK_SCALE_DELTA = 0.1f

private val LoadingSpinnerSize = 24.dp

// ---------------------------------------------------------------- previews

/** Small fixture-sized images for previews (48×32 checker, 32×48 two-tone). */
internal object PreviewImages {
    const val WIDE_PNG = "iVBORw0KGgoAAAANSUhEUgAAADAAAAAgCAIAAADbtmxLAAAAR0lEQVR42mOoWPUMK7LJO4EV0Vo9w6iDRh006qAR56CBshiX+lEHjTpo1EEjz0GjJfWog0YdNOqg0fbQaNUx6qBRB41wBwEAjglul3LpUS8AAAAASUVORK5CYII="
    const val TALL_PNG = "iVBORw0KGgoAAAANSUhEUgAAACAAAAAwCAIAAAD/zu84AAAAOElEQVR42u3NMQ0AMAgAMIRN2G6EoWQiJgEVHCRN+jdenlEhEAgEAoFAsCT4dUcJBAKBQCAQLAkaOV9ApsSosBAAAAAASUVORK5CYII="

    val wide = ChatImage("img-wide", "image/png", WIDE_PNG)
    val tall = ChatImage("img-tall", "image/png", TALL_PNG)
    val broken = ChatImage("img-broken", "image/png", "bm90IGFuIGltYWdl")
}

@Composable
private fun LightboxPreviewHost(gallery: ImageGallery, index: Int) {
    ClinePreviewTheme {
        val state = remember { LightboxState().apply { open("preview", gallery, index) } }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            ImageLightboxOverlay(state)
        }
    }
}

@LightDarkPreviews
@Composable
private fun LightboxSingleAttachmentPreview() {
    LightboxPreviewHost(ImageGallery(GalleryKind.Attachment, listOf(PreviewImages.wide)), 0)
}

@LightDarkPreviews
@Composable
private fun LightboxGeneratedCarouselPreview() {
    LightboxPreviewHost(ImageGallery(GalleryKind.Generated, listOf(PreviewImages.wide, PreviewImages.tall, PreviewImages.wide)), 1)
}

@LightDarkPreviews
@Composable
private fun LightboxDecodeFailurePreview() {
    LightboxPreviewHost(ImageGallery(GalleryKind.Generated, listOf(PreviewImages.broken)), 0)
}

@LightDarkPreviews
@Composable
private fun LightboxLoadingPreview() {
    ClinePreviewTheme {
        MaterialTheme(colorScheme = ClineDarkScheme) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(LoadingSpinnerSize))
            }
        }
    }
}
