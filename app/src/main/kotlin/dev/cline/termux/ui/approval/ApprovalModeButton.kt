package dev.cline.termux.ui.approval

import android.content.res.Resources
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.data.approvals.ApprovalMode
import dev.cline.termux.data.approvals.ApprovalWriteFailure
import dev.cline.termux.data.session.SessionFacts
import dev.cline.termux.data.session.SessionStatus
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LiveRegionState
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.navigation.BackLevel
import dev.cline.termux.ui.navigation.ReportBackLevel
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/**
 * What the control shows (decided A15, after auto-approve-control.md §2A): a plain glyph, no
 * container and no ring. The pending state draws the state it switches to, at low emphasis,
 * with a small dot badge at the glyph's corner.
 */
enum class ApprovalVisual {
    /** "Ask each time": the ticked shield (`gpp_good`, fill 1) in `onSurfaceVariant`. */
    Ask,

    /** "Auto-approve all": the bolt (`bolt`) in `onSurfaceVariant`. */
    Auto,

    /** "Ask from the end of this run": the ticked shield at low emphasis with the dot badge until the run ends. */
    Pending,
    ;

    /** The glyph: a pending switch draws its target state's (Ask's), never the state it leaves. */
    val glyph: ApprovalGlyph get() = if (this == Auto) ApprovalGlyph.Bolt else ApprovalGlyph.TickedShield

    /** Only while the switch waits for the run to end: low emphasis and the dot badge. */
    val waitsForRunEnd: Boolean get() = this == Pending
}

/** The control's two glyphs (decided A15): `ClineIcons.Approval.Ask` and `ClineIcons.Approval.Auto`. */
enum class ApprovalGlyph { TickedShield, Bolt }

/**
 * The auto-approve control's state (decided A1): always the kernel's global mode, plus the
 * transient pending state while the open session's run still auto-approves after a switch to
 * Ask (§1 item 5). Home passes no session.
 */
@Immutable
data class ApprovalButtonState(
    val mode: ApprovalMode = ApprovalMode.Unknown,
    val pending: Boolean = false,
    /** Termux commands can run (not degraded, not blocked): the setting can be read and written. */
    val commandsAvailable: Boolean = true,
    val writing: Boolean = false,
    /** §6: a desktop-started session that still auto-approves; the menu says so. */
    val desktopSession: Boolean = false,
    /** The open session's last run was stopped (the announcement becomes "Run stopped. …"). */
    val runStopped: Boolean = false,
    /** The open session, so a switch to another one never plays a transition. */
    val sessionId: String? = null,
) {
    /** Before the first read, or without Termux commands: the disabled glyph. */
    val enabled: Boolean get() = mode != ApprovalMode.Unknown && commandsAvailable

    val visual: ApprovalVisual
        get() = when {
            mode == ApprovalMode.Auto -> ApprovalVisual.Auto
            pending -> ApprovalVisual.Pending
            else -> ApprovalVisual.Ask
        }

    companion object {
        const val DESKTOP_SOURCE = "desktop"

        /**
         * [facts] of the open session (null on Home) and [sessionAuto], its runtime's policy
         * (`SessionState.autoApproveTools`: the record's key, or the value last seen when a pin
         * dropped it from the record; null when unknown). Pure, unit-tested.
         */
        fun of(mode: ApprovalMode, writing: Boolean, gate: KernelGate, facts: SessionFacts?, sessionAuto: Boolean?): ApprovalButtonState {
            val running = facts != null && (facts.runPhase.isBusy || facts.status.isRunning)
            return ApprovalButtonState(
                mode = mode,
                pending = mode == ApprovalMode.Ask && sessionAuto == true && running,
                commandsAvailable = gate !is KernelGate.Blocked && gate != KernelGate.DegradedNoCommands,
                writing = writing,
                desktopSession = facts?.source == DESKTOP_SOURCE && sessionAuto == true,
                runStopped = facts?.status == SessionStatus.Cancelled,
                sessionId = facts?.sessionId,
            )
        }
    }
}

/** The button's callbacks: opening the menu re-reads the setting (§6); a pick writes it. */
@Immutable
class ApprovalModeActions(
    val onMenuOpened: () -> Unit = {},
    val onSelect: (auto: Boolean) -> Unit = {},
)

/** "Couldn't turn on/off Auto-approve all: <reason>" for a failed write. */
fun approvalWriteFailureText(resources: Resources, turningOn: Boolean, failure: ApprovalWriteFailure): String {
    val reason = when (failure) {
        ApprovalWriteFailure.Unreadable -> resources.getString(R.string.approval_mode_error_unreadable)
        is ApprovalWriteFailure.Unavailable -> failure.detail.ifBlank { resources.getString(R.string.approval_mode_error_unavailable) }
        is ApprovalWriteFailure.Failed -> failure.detail?.takeIf { it.isNotBlank() } ?: resources.getString(R.string.approval_mode_error_exit, failure.exitCode)
    }
    return resources.getString(if (turningOn) R.string.approval_mode_turn_on_failed else R.string.approval_mode_turn_off_failed, reason)
}

/**
 * The tool approvals control (auto-approve-control.md §2A, decided A1, A′ and A15): a 48 dp
 * standard icon button (no container) before the title, with a long-press tooltip; a tap opens
 * a two-item menu where the pick applies (two deliberate taps, never a toggle). [announcer] is
 * the screen's polite live region.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalModeButton(
    state: ApprovalButtonState,
    actions: ApprovalModeActions,
    modifier: Modifier = Modifier,
    announcer: LiveRegionState? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Termux commands went away while the menu was open: it closes.
    LaunchedEffect(state.enabled) { if (!state.enabled) open = false }
    ReportBackLevel(BackLevel.Menu, open && state.enabled)
    val stateText = stringResource(
        when (state.visual) {
            ApprovalVisual.Auto -> R.string.approval_mode_state_auto
            ApprovalVisual.Ask -> R.string.approval_mode_state_ask
            ApprovalVisual.Pending -> R.string.approval_mode_state_pending
        },
    )
    val tooltip = if (state.enabled) stringResource(R.string.approval_mode_tooltip, stateText) else stringResource(R.string.approval_mode_disabled)
    val cd = stringResource(R.string.approval_mode_cd)
    val changeLabel = stringResource(R.string.approval_mode_click_label)
    val openMenu = {
        if (state.enabled) {
            open = true
            actions.onMenuOpened()
        }
    }
    ApprovalTransitions(state, announcer)
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
            tooltip = { PlainTooltip { Text(tooltip) } },
            state = rememberTooltipState(),
        ) {
            ModeIconButton(
                state = state,
                onClick = openMenu,
                modifier = Modifier.semantics { stateDescription = if (state.enabled) stateText else tooltip },
                contentDescription = cd,
                clickLabel = changeLabel,
            )
        }
        ApprovalModeMenu(
            expanded = open && state.enabled,
            state = state,
            onDismiss = { open = false },
            onSelect = { auto ->
                open = false
                val current = state.mode == ApprovalMode.Auto
                // The current item only closes the menu; a write in flight ignores a second pick.
                if (auto != current && !state.writing) actions.onSelect(auto)
            },
        )
    }
}

/**
 * The 48 dp target (40 dp container) with the plain glyph (decided A15). The glyphs cross-fade
 * (fade + `scaleIn(0.8f)`, `Short3`, the Send ⇄ Stop recipe), the tint settles over `Short4`
 * (`Standard`), and the pending dot scales in with the enter curve and out with the exit curve
 * (`Short4`) when the switch applies at the run's end or on Stop. Reduced motion, a session
 * switch and the first read are instant.
 */
@Composable
private fun ModeIconButton(state: ApprovalButtonState, onClick: () -> Unit, modifier: Modifier, contentDescription: String, clickLabel: String) {
    val visual = state.visual
    val scheme = MaterialTheme.colorScheme
    val tint = when {
        !state.enabled -> scheme.onSurface.copy(alpha = DISABLED_ALPHA)
        visual.waitsForRunEnd -> scheme.onSurfaceVariant.copy(alpha = PENDING_ALPHA)
        else -> scheme.onSurfaceVariant
    }
    val dot = if (state.enabled) scheme.tertiary else scheme.onSurface.copy(alpha = DISABLED_ALPHA)
    // The M3 icon button's geometry (48 dp target, 40 dp container) with its own click, so the
    // TalkBack action is named ("Double-tap to change").
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(ContainerSize)
            .focusRing(CircleShape)
            .clip(CircleShape)
            .clickable(enabled = state.enabled, role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        // Keyed by session and by the first read: switching sessions or reading the mode for the
        // first time snaps; only a change within one session animates.
        key(state.sessionId, state.mode == ApprovalMode.Unknown) {
            ModeGlyph(visual, tint, dot, ClineTheme.reducedMotion)
        }
    }
}

@Composable
private fun ModeGlyph(visual: ApprovalVisual, tintTarget: Color, dotColor: Color, reduced: Boolean) {
    val tint by animateColorAsState(
        targetValue = tintTarget,
        animationSpec = if (reduced) snap() else tween(ClineMotion.Short4, easing = ClineMotion.Standard),
        label = "approvalTint",
    )
    BadgedBox(
        badge = {
            AnimatedVisibility(
                visible = visual.waitsForRunEnd,
                enter = if (reduced) {
                    EnterTransition.None
                } else {
                    fadeIn(tween(ClineMotion.Short4, easing = ClineMotion.EmphasizedDecelerate)) +
                        scaleIn(tween(ClineMotion.Short4, easing = ClineMotion.EmphasizedDecelerate), initialScale = DOT_START_SCALE)
                },
                exit = if (reduced) {
                    ExitTransition.None
                } else {
                    fadeOut(tween(ClineMotion.Short4, easing = ClineMotion.EmphasizedAccelerate)) +
                        scaleOut(tween(ClineMotion.Short4, easing = ClineMotion.EmphasizedAccelerate), targetScale = DOT_START_SCALE)
                },
            ) {
                Badge(containerColor = dotColor)
            }
        },
    ) {
        AnimatedContent(
            targetState = visual.glyph,
            transitionSpec = {
                if (reduced) {
                    fadeIn(snap()) togetherWith fadeOut(snap())
                } else {
                    (fadeIn(tween(ClineMotion.Short3)) + scaleIn(tween(ClineMotion.Short3), initialScale = GLYPH_START_SCALE)) togetherWith
                        fadeOut(tween(ClineMotion.Short3))
                }
            },
            label = "approvalIcon",
        ) { glyph ->
            Icon(approvalGlyphIcon(glyph), contentDescription = null, tint = tint)
        }
    }
}

/** The vector behind each [ApprovalGlyph]; the button and its menu both draw through it. */
@Composable
private fun approvalGlyphIcon(glyph: ApprovalGlyph): ImageVector = when (glyph) {
    ApprovalGlyph.TickedShield -> ClineIcons.Approval.Ask
    ApprovalGlyph.Bolt -> ClineIcons.Approval.Auto
}

/** §2A accessibility: each applied state is announced politely (not the first read, not a session switch). */
@Composable
private fun ApprovalTransitions(state: ApprovalButtonState, announcer: LiveRegionState?) {
    if (announcer == null) return
    val resources = LocalResources.current
    val latest by rememberUpdatedState(state)
    val tracker = remember { Array<ApprovalButtonState?>(1) { null } }
    LaunchedEffect(state.visual, state.mode, state.sessionId) {
        val previous = tracker[0]
        val now = latest
        tracker[0] = now
        if (previous == null || previous.mode == ApprovalMode.Unknown || now.mode == ApprovalMode.Unknown) return@LaunchedEffect
        if (previous.sessionId != now.sessionId || previous.visual == now.visual) return@LaunchedEffect
        val text = when (now.visual) {
            ApprovalVisual.Auto -> resources.getString(R.string.approval_mode_announce_auto)
            ApprovalVisual.Pending -> resources.getString(R.string.approval_mode_state_pending)
            ApprovalVisual.Ask -> if (previous.visual == ApprovalVisual.Pending && now.runStopped) {
                resources.getString(R.string.approval_mode_announce_stopped)
            } else {
                resources.getString(R.string.approval_mode_announce_ask)
            }
        }
        announcer.announce(text)
    }
}

/** §2A menu: header, the two modes with a check on the current one, the footer and the notes. */
@Composable
private fun ApprovalModeMenu(expanded: Boolean, state: ApprovalButtonState, onDismiss: () -> Unit, onSelect: (Boolean) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = MenuMinWidth, max = MenuMaxWidth)) {
        Text(
            stringResource(R.string.approval_mode_menu_header),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.s),
        )
        ModeItem(
            title = stringResource(R.string.approval_mode_auto_title),
            supporting = stringResource(R.string.approval_mode_auto_supporting),
            icon = { Icon(approvalGlyphIcon(ApprovalGlyph.Bolt), contentDescription = null) },
            selected = state.mode == ApprovalMode.Auto,
            onClick = { onSelect(true) },
        )
        ModeItem(
            title = stringResource(R.string.approval_mode_ask_title),
            supporting = stringResource(R.string.approval_mode_ask_supporting),
            icon = { Icon(approvalGlyphIcon(ApprovalGlyph.TickedShield), contentDescription = null) },
            selected = state.mode == ApprovalMode.Ask,
            onClick = { onSelect(false) },
        )
        HorizontalDivider()
        MenuNote(stringResource(R.string.approval_mode_footer))
        if (state.pending) MenuNote(stringResource(R.string.approval_mode_pending_note))
        if (state.desktopSession) MenuNote(stringResource(R.string.approval_mode_desktop_note))
    }
}

@Composable
private fun ModeItem(title: String, supporting: String, icon: @Composable () -> Unit, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        leadingIcon = icon,
        trailingIcon = if (selected) {
            { Icon(ClineIcons.Check, contentDescription = null) }
        } else {
            null
        },
        onClick = onClick,
        modifier = Modifier.semantics { if (selected) stateDescription = title },
    )
}

@Composable
private fun MenuNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.m, vertical = Spacing.xs),
    )
}

private val ContainerSize = 40.dp
private val MenuMinWidth = 200.dp
private val MenuMaxWidth = 300.dp
private const val DISABLED_ALPHA = 0.38f

/** The pending glyph's low emphasis: clearly dimmer than the plain states, well above the disabled 38 %. */
private const val PENDING_ALPHA = 0.6f

/** The pending dot grows from (and shrinks to) this scale. */
private const val DOT_START_SCALE = 0.6f

/** A new glyph scales in from this size (the Send ⇄ Stop recipe, DS §6). */
private const val GLYPH_START_SCALE = 0.8f

@LightDarkPreviews
@Composable
private fun ApprovalModeButtonPreview() {
    ComponentPreview {
        androidx.compose.foundation.layout.Row {
            ApprovalModeButton(ApprovalButtonState(mode = ApprovalMode.Ask), ApprovalModeActions())
            ApprovalModeButton(ApprovalButtonState(mode = ApprovalMode.Auto), ApprovalModeActions())
            ApprovalModeButton(ApprovalButtonState(mode = ApprovalMode.Ask, pending = true), ApprovalModeActions())
            ApprovalModeButton(ApprovalButtonState(mode = ApprovalMode.Unknown), ApprovalModeActions())
        }
    }
}
