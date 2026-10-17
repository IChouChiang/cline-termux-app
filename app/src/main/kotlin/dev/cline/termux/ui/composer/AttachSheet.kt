package dev.cline.termux.ui.composer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.Attachments
import dev.cline.termux.data.chat.describeAttachment
import dev.cline.termux.data.composer.InputHistory
import dev.cline.termux.data.composer.InputHistoryStatus
import dev.cline.termux.service.ShareFiles
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.InlineSpinner
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing
import kotlinx.coroutines.launch

/** What the attach sheet's rows open (C1.9); registered unconditionally by the composer. */
@Stable
class AttachLaunchers internal constructor(
    val photos: (remaining: Int) -> Unit,
    val camera: () -> Unit,
    val files: () -> Unit,
)

/**
 * Registers the three system pickers once (they must outlive the sheet): photo picker
 * (multi when ≥ 2 slots remain, single when 1; `PickMultipleVisualMedia(maxItems ≤ 1)`
 * throws), camera capture into a FileProvider URI under `cacheDir/camera/`, and
 * `OpenMultipleDocuments` for any MIME type (desktop file input accepting every type). Every result is described and handed to [onPicked].
 */
@Composable
fun rememberAttachLaunchers(remainingImages: Int, onPicked: (List<dev.cline.termux.data.chat.AttachmentRef>) -> Unit): AttachLaunchers {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun deliver(uris: List<Uri>, fallback: String? = null) {
        if (uris.isEmpty()) return
        scope.launch { onPicked(uris.map { describeAttachment(context, it, fallback) }) }
    }
    val maxItems = maxOf(remainingImages, 2)
    val multi = rememberLauncherForActivityResult(remember(maxItems) { ActivityResultContracts.PickMultipleVisualMedia(maxItems) }) { deliver(it) }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> deliver(listOfNotNull(uri)) }
    var pendingCamera by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val target = pendingCamera
        pendingCamera = null
        if (saved && target != null) {
            val uri = Uri.parse(target)
            deliver(listOf(uri), uri.lastPathSegment)
        }
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { deliver(it) }
    val imageOnly = PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
    return AttachLaunchers(
        photos = { remaining -> if (remaining >= 2) multi.launch(imageOnly) else single.launch(imageOnly) },
        camera = {
            val (_, uri) = ShareFiles.newCameraCapture(context, System.currentTimeMillis())
            pendingCamera = uri.toString()
            runCatching { camera.launch(uri) }
        },
        files = { runCatching { documents.launch(arrayOf("*/*")) } },
    )
}

/** Which row the user chose; the composer runs it once the sheet's hide animation ended. */
enum class AttachChoice {
    Photos,
    Camera,
    Files,

    /** A11.6: the "Recent prompts" list (the fling's discoverable and accessible path). */
    RecentPrompts,
}

/**
 * C1.9 attach source sheet: "Photos", "Camera", "Files", and (A11.6, when the composer has the
 * prompt history) "Recent prompts". With a text-only model the image rows stay visible and
 * explain why tapping them only shows a snackbar (no disabled rows). Every row first hides the
 * sheet, then [onChoice] runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachSheet(
    imagesSupported: Boolean,
    onChoice: (AttachChoice) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester? = null,
    recentPrompts: Boolean = false,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun choose(choice: AttachChoice) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            onChoice(choice)
        }
    }
    ClineSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        title = stringResource(R.string.attach_sheet_title),
        returnFocus = returnFocus,
    ) {
        AttachSheetRows(imagesSupported, recentPrompts, ::choose)
    }
}

@Composable
private fun AttachSheetRows(imagesSupported: Boolean, recentPrompts: Boolean, onChoice: (AttachChoice) -> Unit) {
    val noImages = stringResource(R.string.attach_images_unsupported_support)
    Column {
        AttachRow(ClineIcons.PhotoLibrary, stringResource(R.string.attach_photos), if (imagesSupported) stringResource(R.string.attach_photos_support) else noImages) {
            onChoice(AttachChoice.Photos)
        }
        AttachRow(ClineIcons.PhotoCamera, stringResource(R.string.attach_camera), if (imagesSupported) null else noImages) {
            onChoice(AttachChoice.Camera)
        }
        AttachRow(ClineIcons.Description, stringResource(R.string.attach_files), stringResource(R.string.attach_files_support)) {
            onChoice(AttachChoice.Files)
        }
        if (recentPrompts) {
            AttachRow(ClineIcons.History, stringResource(R.string.composer_history_attach_row), stringResource(R.string.composer_history_attach_row_support)) {
                onChoice(AttachChoice.RecentPrompts)
            }
        }
    }
}

/**
 * A11.6 "Recent prompts": the last 20 prompts, newest first, two lines each (whitespace runs
 * shown as one space). Tapping one hides the sheet, then [onPick] gets the list as shown and
 * the row's index; the composer shows that prompt in its browsing state.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentPromptsSheet(
    history: InputHistory,
    onPick: (entries: List<String>, index: Int) -> Unit,
    onDismiss: () -> Unit,
    returnFocus: FocusRequester? = null,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    fun pick(entries: List<String>, index: Int) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            onPick(entries, index)
        }
    }
    ClineSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        title = stringResource(R.string.composer_history_sheet_title),
        returnFocus = returnFocus,
    ) {
        RecentPromptsContent(history, ::pick)
    }
}

@Composable
private fun RecentPromptsContent(history: InputHistory, onPick: (List<String>, Int) -> Unit) {
    val entries = history.entries
    if (entries.isNotEmpty()) {
        LazyColumn {
            itemsIndexed(entries) { index, prompt ->
                ListItem(
                    headlineContent = { Text(oneLinePreview(prompt), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Icon(ClineIcons.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier
                        .heightIn(min = 56.dp)
                        .focusRing(RectangleShape, inset = true)
                        .clickable(role = Role.Button) { onPick(entries, index) },
                )
            }
        }
        return
    }
    val status = history.status
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = Spacing.sheetGutter, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        val loading = status == InputHistoryStatus.NotLoaded || status == InputHistoryStatus.Loading
        if (loading) InlineSpinner()
        Text(
            stringResource(
                when {
                    loading -> R.string.composer_history_loading
                    status == InputHistoryStatus.Failed -> R.string.composer_history_failed
                    else -> R.string.composer_history_empty
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/** A prompt for a two-line list row: runs of whitespace (line breaks included) become one space. */
internal fun oneLinePreview(prompt: String): String = prompt.trim().replace(WHITESPACE_RUN, " ")

private val WHITESPACE_RUN = Regex("\\s+")

@Composable
private fun AttachRow(icon: ImageVector, title: String, supporting: String?, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier
            .heightIn(min = 56.dp)
            .focusRing(RectangleShape, inset = true)
            .clickable(role = Role.Button, onClick = onClick),
    )
}

/** Remaining image slots of a draft (`5 − images`). */
fun remainingImageSlots(state: ComposerUiState): Int =
    (Attachments.MAX_IMAGES - state.attachments.count { it is DraftAttachment.Image }).coerceAtLeast(0)

@LightDarkPreviews
@Composable
private fun AttachSheetRowsPreview() = ComponentPreview(padding = false) { AttachSheetRows(imagesSupported = true, recentPrompts = true) {} }

@LightDarkPreviews
@Composable
private fun AttachSheetRowsNoImagesPreview() = ComponentPreview(padding = false) { AttachSheetRows(imagesSupported = false, recentPrompts = false) {} }
