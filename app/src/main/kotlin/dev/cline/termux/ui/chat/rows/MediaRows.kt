package dev.cline.termux.ui.chat.rows

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import dev.cline.termux.R
import dev.cline.termux.data.chat.ChatImage
import dev.cline.termux.data.chat.GeneratedMedia
import dev.cline.termux.data.chat.MediaModality
import dev.cline.termux.data.chat.MediaSource
import dev.cline.termux.data.chat.sanitizeFileName
import dev.cline.termux.data.tools.ToolOutputMedia
import dev.cline.termux.service.ShareFiles
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.showReplacing
import dev.cline.termux.ui.theme.Spacing
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * Generated media (HC §3.4.8, desktop `generated-media.tsx`): inline images become
 * thumbnails, audio/video/file bytes become a row with "Open" (written under the app's
 * FileProvider and handed to ACTION_VIEW), remote and artifact sources and anything that
 * fails validation become the desktop's fallback sentences. Provider URLs are never loaded.
 */

/** Tool output media other than images, as generated media (desktop builds the same shape). */
fun ToolOutputMedia.asGeneratedMedia(id: String): GeneratedMedia =
    GeneratedMedia(id = id, modality = modality, mediaType = mediaType, source = MediaSource.Base64(data), name = name)

@Composable
fun GeneratedMediaList(
    media: List<GeneratedMedia>,
    cacheKeyPrefix: String,
    onExpandImage: ((ImageGallery, Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (media.isEmpty()) return
    // Validation base64-decodes every item, so it runs on Default, never during composition (DS §6).
    val presentations by produceState<List<MediaPresentation>?>(null, media) {
        value = withContext(Dispatchers.Default) { presentGeneratedMedia(media) }
    }
    presentations?.let { GeneratedMediaPresentations(it, cacheKeyPrefix, onExpandImage, modifier) }
}

/**
 * Already-validated media (the transcript pipeline builds [MessageFacts.media] and
 * [ToolRowFacts.media] off Main), drawn without decoding anything in composition.
 */
@Composable
fun GeneratedMediaPresentations(
    presentations: List<MediaPresentation>,
    cacheKeyPrefix: String,
    onExpandImage: ((ImageGallery, Int) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (presentations.isEmpty()) return
    val images = remember(presentations) {
        presentations.filterIsInstance<MediaPresentation.Image>().map { ChatImage(it.media.id, it.mediaType, it.data) }
    }
    val thumbnailKeys = remember(presentations, cacheKeyPrefix) {
        presentations.filterIsInstance<MediaPresentation.Image>().map { "$cacheKeyPrefix:media:${it.media.id}" }
    }
    val gallery = ImageGallery(GalleryKind.Generated, images, thumbnailKeys)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        var imageIndex = 0
        presentations.forEach { presentation ->
            when (presentation) {
                is MediaPresentation.Image -> {
                    val index = imageIndex++
                    val alt = presentation.media.name ?: stringResource(R.string.msg_generated_image_default)
                    ImageThumbnail(
                        cacheKey = thumbnailKeys[index],
                        image = images[index],
                        alt = alt,
                        expandLabel = stringResource(R.string.msg_generated_expand, index + 1),
                        onClick = onExpandImage?.let { { it(gallery, index) } },
                    )
                }
                is MediaPresentation.Openable -> OpenableMediaRow(presentation)
                is MediaPresentation.Remote -> MediaFallbackRow(stringResource(presentation.media.modality.remoteRes()))
                is MediaPresentation.StoredArtifact -> MediaFallbackRow(
                    stringResource(presentation.media.modality.artifactRes(), presentation.artifactId),
                )
                is MediaPresentation.Unrenderable -> MediaFallbackRow(stringResource(presentation.media.modality.unrenderableRes()))
            }
        }
    }
}

private val MediaModality.icon: ImageVector
    @Composable get() = when (this) {
        MediaModality.Audio -> ClineIcons.AudioFile
        MediaModality.Video -> ClineIcons.Movie
        MediaModality.Image, MediaModality.File -> ClineIcons.Description
    }

/** 56dp row: modality icon, name (or "Generated file (<type>)"), trailing "Open" (HC §3.4.8). */
@Composable
private fun OpenableMediaRow(presentation: MediaPresentation.Openable) {
    val context = LocalContext.current
    val snackbar = LocalSnackbarHost.current
    val scope = rememberCoroutineScope()
    val noApp = stringResource(R.string.media_no_app)
    val failed = stringResource(R.string.media_open_failed)
    ListItem(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Icon(presentation.media.modality.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        headlineContent = {
            Text(
                presentation.media.name ?: stringResource(R.string.msg_generated_file, presentation.media.mediaType),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
            )
        },
        trailingContent = {
            TextButton(onClick = {
                scope.launch {
                    when (MediaOpener.open(context, presentation)) {
                        MediaOpener.Result.Opened -> Unit
                        MediaOpener.Result.NoApp -> snackbar.showReplacing(noApp)
                        MediaOpener.Result.Failed -> snackbar.showReplacing(failed)
                    }
                }
            }) { Text(stringResource(R.string.media_open)) }
        },
    )
}

/** `Surface(surfaceContainerHighest, shapes.small)`, 12dp padding, `bodyMedium` (HC §3.4.8). */
@Composable
internal fun MediaFallbackRow(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(Spacing.m))
    }
}

/**
 * Writes generated bytes to `cacheDir/share/media/<id>` (the FileProvider's `share` root,
 * swept after 24 h by ShareFiles) and fires ACTION_VIEW with the media type and a read grant.
 */
internal object MediaOpener {
    enum class Result { Opened, NoApp, Failed }

    suspend fun open(context: Context, presentation: MediaPresentation.Openable): Result {
        val file = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "share/media").apply { mkdirs() }
                val bytes = Base64.decode(presentation.data, Base64.DEFAULT)
                File(dir, safeFileName(presentation.media)).apply { writeBytes(bytes) }
            }.getOrNull()
        } ?: return Result.Failed
        val uri = runCatching { FileProvider.getUriForFile(context, ShareFiles.AUTHORITY, file) }.getOrNull() ?: return Result.Failed
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, presentation.mediaType)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Result.Opened
        } catch (_: ActivityNotFoundException) {
            Result.NoApp
        } catch (_: SecurityException) {
            Result.Failed
        }
    }

    /**
     * The media name (or id) as a safe file name; never a path. Only filesystem-unsafe
     * characters are replaced, so "架构图.png" stays "架构图.png"; the cut keeps whole graphemes.
     */
    fun safeFileName(media: GeneratedMedia): String =
        sanitizeFileName(media.name?.takeIf { it.isNotBlank() } ?: media.id, fallback = "media", maxUnits = MAX_MEDIA_NAME)

    private const val MAX_MEDIA_NAME = 96
}
