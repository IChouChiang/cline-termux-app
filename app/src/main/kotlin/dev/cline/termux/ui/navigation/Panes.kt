package dev.cline.termux.ui.navigation

import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.MutableThreePaneScaffoldState
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.material3.adaptive.layout.PaneScaffoldDirective
import androidx.compose.material3.adaptive.layout.SupportingPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableSupportingPaneScaffold
import androidx.compose.material3.adaptive.navigation.ThreePaneScaffoldNavigator
import androidx.compose.material3.adaptive.navigation.rememberSupportingPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable

/**
 * What the chat's supporting pane shows at ≥ 1200 × ≥ 480 (docs/07 §6.2, CT A.3, B.3:
 * `SupportingContent.Changes/Tool`). Below that size the same request is the pushed route
 * [route] instead ([ShellNavigator.openChanges], [ShellNavigator.openTool]).
 */
@Serializable
sealed interface SupportingContent {
    val sessionId: String

    /** The equivalent pushed route on windows without a supporting pane. */
    val route: ShellRoute

    @Serializable
    data class Changes(override val sessionId: String) : SupportingContent {
        override val route: ShellRoute get() = ChatChanges(sessionId)
    }

    @Serializable
    data class Tool(override val sessionId: String, val toolCallId: String) : SupportingContent {
        override val route: ShellRoute get() = ChatTool(sessionId, toolCallId)
    }
}

/** The supporting pane is 400dp (DS §5.2 "a supporting pane of 400dp on the right"). */
val SupportingPaneWidth: Dp = 400.dp

/** The window's pane directive with the given preferred pane width (list 360/400dp, supporting 400dp). */
@Composable
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
fun rememberPaneDirective(preferredWidth: Dp, maxPartitions: Int? = null): PaneScaffoldDirective {
    val base = calculatePaneScaffoldDirective(currentWindowAdaptiveInfoV2())
    return base.copy(
        maxHorizontalPartitions = maxPartitions?.coerceAtMost(base.maxHorizontalPartitions) ?: base.maxHorizontalPartitions,
        defaultPanePreferredWidth = preferredWidth,
    )
}

/**
 * Follows the navigator's [ThreePaneScaffoldNavigator.scaffoldValue] with its scaffold state.
 * adaptive-navigation 1.3 animates the state only inside `navigateTo`/`navigateBack`, so a
 * directive change alone (the list pane collapsed through `maxHorizontalPartitions`, CT DS-A1,
 * or a window resize) recomputed the value but left the panes where they were.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun SyncScaffoldState(navigator: ThreePaneScaffoldNavigator<*>) {
    LaunchedEffect(navigator) {
        snapshotFlow { navigator.scaffoldValue }
            .distinctUntilChanged()
            .collect { value ->
                val state = navigator.scaffoldState as? MutableThreePaneScaffoldState ?: return@collect
                if (state.targetState != value && !state.isPredictiveBackInProgress) state.animateTo(value)
            }
    }
}

/**
 * The chat's `NavigableSupportingPaneScaffold` (DS §5.2 "At ≥ 1200dp, Changes opens as a
 * supporting pane of 400dp on the right"). [supporting] is the shell's request
 * (`LocalShellChat.current?.supporting`); the pane opens while it is non-null. Back, or the
 * scaffold collapsing the pane, calls [onCloseSupporting] (`LocalShellChat.current?.closeSupporting`;
 * DS §5.8 item 7a). Below the
 * supporting-pane size only [main] is laid out, and the shell pushes the route instead.
 */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
fun ChatSupportingPaneHost(
    supporting: SupportingContent?,
    onCloseSupporting: () -> Unit,
    modifier: Modifier = Modifier,
    main: @Composable () -> Unit,
    supportingPane: @Composable (SupportingContent) -> Unit,
) {
    val available = LocalShellLayout.current.supportingPaneAvailable
    val wanted = supporting != null && available
    // Two partitions only while the pane is requested: with two the scaffold always gives the
    // second one to the supporting pane, so an idle chat kept an empty 400dp column and a pop
    // to Main never changed the value (no back handler, so back left the app).
    var onSupporting by remember { mutableStateOf(false) }
    val navigator = rememberSupportingPaneScaffoldNavigator<Nothing>(
        scaffoldDirective = rememberPaneDirective(SupportingPaneWidth, maxPartitions = if (wanted) null else 1),
    )
    val close by rememberUpdatedState(onCloseSupporting)
    val scope = rememberCoroutineScope()

    LaunchedEffect(navigator, wanted) {
        val showing = navigator.currentDestination?.pane == SupportingPaneScaffoldRole.Supporting
        if (wanted && !showing) navigator.navigateTo(SupportingPaneScaffoldRole.Supporting)
        if (!wanted && showing) navigator.navigateBack(BackNavigationBehavior.PopLatest)
    }
    // The scaffold dropping the pane (a window resize) clears the request too.
    LaunchedEffect(navigator) {
        snapshotFlow { navigator.currentDestination?.pane == SupportingPaneScaffoldRole.Supporting }
            .distinctUntilChanged()
            .collect { showing ->
                if (onSupporting && !showing) close()
                onSupporting = showing
            }
    }
    SyncScaffoldState(navigator)
    // DS §5.8 item 7a (CT DS-A9): back closes the pane with a predictive preview that slides
    // it out toward the chat-only value; the built-in navigator back cannot do this because
    // both destinations have the same two-partition value while the pane is open.
    OrderedPredictiveBackHandler(
        level = BackLevel.SupportingPane,
        enabled = wanted,
        onStart = { navigator.scaffoldState as? MutableThreePaneScaffoldState },
        onProgress = { state, event -> state?.seekTo(event.progress, MainOnly, isPredictiveBackInProgress = true) },
        onCommit = { close() },
        onCancel = { state -> scope.launch { state?.animateTo(navigator.scaffoldValue) } },
    )

    NavigableSupportingPaneScaffold(
        navigator = navigator,
        modifier = modifier,
        mainPane = { AnimatedPane { PaneFocusAnchor { main() } } },
        supportingPane = {
            AnimatedPane {
                PaneFocusAnchor { if (supporting != null) supportingPane(supporting) }
            }
        },
        defaultBackBehavior = BackNavigationBehavior.PopUntilScaffoldValueChange,
    )
}

/** The supporting-pane scaffold with only the main pane (the predictive-back target). */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
private val MainOnly = ThreePaneScaffoldValue(
    primary = PaneAdaptedValue.Expanded,
    secondary = PaneAdaptedValue.Hidden,
    tertiary = PaneAdaptedValue.Hidden,
)

/**
 * The adaptive scaffolds (material3-adaptive 1.3 `ThreePaneScaffold`) request focus on the
 * current destination pane whenever the scaffold value changes, and a pane is a focus group, so
 * focus enters its first focusable child. In the chat that child is the composer's text field,
 * which raised the IME over Home on every launch, against composer C1.13 ("entering a chat
 * focuses the field only when the user came from New session") and DS §5.7. This wrapper is a
 * focus target around the pane content, so the pane's own focus request stops here: no field
 * is focused, no IME appears, and Tab from a hardware keyboard still moves into the content.
 */
@Composable
fun PaneFocusAnchor(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().focusTarget()) { content() }
}
