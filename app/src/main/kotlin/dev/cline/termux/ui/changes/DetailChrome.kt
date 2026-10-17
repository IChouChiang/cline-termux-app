package dev.cline.termux.ui.changes

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.union
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.cline.termux.service.ShareFiles
import dev.cline.termux.ui.LocalSnackbarHost
import dev.cline.termux.ui.components.BackNavIcon
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.bottomSafe
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Chrome shared by Changes (CT A) and Tool detail (CT B): the DS-A10 page app bar kinds with a
 * composable title slot, the pane header and the text share rule of A.7 (inline below
 * 100,000 characters, a FileProvider file above). Clipboard writes go through
 * `ui/components/rememberCopier`.
 */

/** DS-A10 app bar kinds, plus the supporting-pane header of DS-A1. */
enum class DetailBar {
    /** `MediumTopAppBar`, collapses with the body (`exitUntilCollapsed`), back arrow. */
    Medium,

    /** Small 64dp `TopAppBar`, pinned (never collapses), back arrow, hairline on scroll. */
    SmallPinned,

    /** The 400dp supporting pane: small `TopAppBar` on `surfaceContainerLow`, no navigation icon, trailing close. */
    Pane,
}

/**
 * A pushed page or supporting pane with a composable [title] (DS-A10). [onBack] draws the
 * back arrow ("Back") on the page kinds; [onClose] with [closeLabel] adds the pane's trailing
 * close button after [actions]. [loading] shows the linear indicator under the bar (CT A.5,
 * B.5 "Loading"). The content gets the inner padding; the bottom never comes closer than
 * `bottomSafe` unless [drawToBottom] is set (the terminal draws to the edge and pads inside).
 * [snackbarsVisible] is false while the image lightbox covers the page: the lightbox then
 * hosts the app-wide snackbar above its scrim, and DS §4.1 #17 allows one host at a time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    bar: DetailBar,
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    closeLabel: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    loading: Boolean = false,
    drawToBottom: Boolean = false,
    bottomBar: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    snackbarsVisible: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = when (bar) {
        DetailBar.Medium -> TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
        DetailBar.SmallPinned, DetailBar.Pane -> TopAppBarDefaults.pinnedScrollBehavior()
    }
    val scrolledUnder by remember(scrollBehavior) { derivedStateOf { scrollBehavior.state.overlappedFraction > 0.01f } }
    val hairline by animateFloatAsState(if (scrolledUnder) 1f else 0f, tween(ClineMotion.Short3), label = "hairline")
    // A.10: opening the page or pane moves focus to the title, once.
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(titleFocus) { titleFocus.requestFocus() }
    val focusableTitle: @Composable () -> Unit = {
        Box(Modifier.focusRequester(titleFocus).focusable()) { title() }
    }
    val container = if (bar == DetailBar.Pane) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surface
    // The pane draws edge to edge beside the chat like the chat itself: same insets as a page.
    val insets = if (drawToBottom) {
        ScaffoldDefaults.contentWindowInsets
    } else {
        ScaffoldDefaults.contentWindowInsets.union(WindowInsets.bottomSafe)
    }
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = insets,
        snackbarHost = { if (snackbarsVisible) SnackbarHost(LocalSnackbarHost.current) },
        topBar = {
            Column {
                val navigation: @Composable () -> Unit = { if (bar != DetailBar.Pane && onBack != null) BackNavIcon(onBack) }
                val allActions: @Composable RowScope.() -> Unit = {
                    actions()
                    if (onClose != null && closeLabel != null) {
                        TooltipIconButton(icon = ClineIcons.Close, contentDescription = closeLabel, onClick = onClose)
                    }
                }
                val colors = TopAppBarDefaults.topAppBarColors(containerColor = container, scrolledContainerColor = container)
                if (bar == DetailBar.Medium) {
                    MediumTopAppBar(
                        title = focusableTitle,
                        navigationIcon = navigation,
                        actions = allActions,
                        colors = colors,
                        scrollBehavior = scrollBehavior,
                    )
                } else {
                    TopAppBar(
                        title = focusableTitle,
                        navigationIcon = navigation,
                        actions = allActions,
                        colors = colors,
                        scrollBehavior = scrollBehavior,
                    )
                }
                HorizontalDivider(
                    Modifier.graphicsLayer { alpha = if (bar == DetailBar.Pane) 1f else hairline },
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        bottomBar = bottomBar,
        floatingActionButton = floatingActionButton,
        content = content,
    )
}

/** What a share needs besides the text (CT A.7, B.7). */
data class ShareRequest(
    val text: String,
    val subject: String?,
    val chooserTitle: String,
    /** Display name of the FileProvider file used above [INLINE_SHARE_LIMIT] characters. */
    val fileName: String,
    /** MIME type of that file (`text/x-diff` for diffs, `text/plain` for logs). */
    val fileMimeType: String,
)

/** Above this many characters a share goes through `cache/share` and the FileProvider (CT A.7). */
const val INLINE_SHARE_LIMIT = 100_000

/**
 * Opens the system share sheet for [request]: `ACTION_SEND text/plain` with `EXTRA_TEXT` up to
 * [INLINE_SHARE_LIMIT] characters, otherwise a file under `cacheDir/share` (swept after 24 h
 * by [ShareFiles]) shared as a `content://` URI with read permission.
 */
suspend fun launchShare(context: Context, request: ShareRequest) {
    val intent = if (request.text.length <= INLINE_SHARE_LIMIT) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, request.text)
        request.subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        Intent.createChooser(send, request.chooserTitle)
    } else {
        val uri = withContext(Dispatchers.IO) { ShareFiles.writeText(context, ShareFiles.Area.Share, request.fileName, request.text) }
        val send = Intent(Intent.ACTION_SEND)
            .setType(request.fileMimeType)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uri)
        request.subject?.let { send.putExtra(Intent.EXTRA_SUBJECT, it) }
        Intent.createChooser(send, request.chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
