package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.core.kernel.KernelGate
import dev.cline.termux.core.kernel.RestartPhase
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.kernel.KernelChipModel
import dev.cline.termux.ui.kernel.KernelCopy
import dev.cline.termux.ui.kernel.resolve
import dev.cline.termux.ui.theme.Spacing

/** Where the chip sits (DS §5.2): the compact drawer header, or the rail's bottom item. */
enum class KernelChipPlacement { DrawerHeader, Rail }

/**
 * DS §4.2 KernelChip (desktop `agent-sidebar.tsx` logo HoverCard trigger): the F.2 dot and
 * line 1 for [gate]; tapping it opens the Kernel status sheet through [onOpenStatus] (the
 * shell owns the sheet). TalkBack reads "Kernel status: <line 1>". The drawer variant is an
 * `AssistChip` with the dot as its leading icon (the header already has the logo button);
 * the rail variant is the bottom rail item: logo with the dot on it and the label "Hub".
 */
@Composable
fun KernelChip(
    gate: KernelGate,
    port: Int?,
    onOpenStatus: () -> Unit,
    modifier: Modifier = Modifier,
    placement: KernelChipPlacement = KernelChipPlacement.DrawerHeader,
) {
    KernelChip(KernelCopy.chip(gate, port), onOpenStatus, modifier, placement)
}

@Composable
fun KernelChip(
    model: KernelChipModel,
    onOpenStatus: () -> Unit,
    modifier: Modifier = Modifier,
    placement: KernelChipPlacement = KernelChipPlacement.DrawerHeader,
) {
    val line1 = model.line1.resolve()
    val line2 = model.line2?.resolve()
    val spoken = if (line2 != null) stringResource(R.string.kernel_lines_cd, line1, line2) else line1
    val description = stringResource(R.string.components_kernel_status_a11y, spoken)
    // DS §8.1.7: the Kernel status sheet hands focus back to this chip when it closes.
    val focusReturn = LocalKernelSheetFocusReturn.current
    val requester = remember { FocusRequester() }
    val open = {
        focusReturn?.offer(requester)
        onOpenStatus()
    }
    when (placement) {
        KernelChipPlacement.DrawerHeader -> AssistChip(
            onClick = open,
            modifier = modifier
                .focusRequester(requester)
                .focusRing(MaterialTheme.shapes.small)
                .widthIn(max = 200.dp)
                .semantics(mergeDescendants = true) { contentDescription = description },
            label = { Text(line1, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { StatusDot(tone = model.tone, pulsing = model.pulsing, boxSize = 18.dp) },
        )
        KernelChipPlacement.Rail -> NavigationRailItem(
            selected = false,
            onClick = open,
            modifier = modifier
                .focusRequester(requester)
                .focusRing(MaterialTheme.shapes.large)
                .clearAndSetSemantics {
                    contentDescription = description
                    role = Role.Button
                    onClick { open(); true }
                },
            icon = {
                Box {
                    Icon(ClineIcons.AppLogo, contentDescription = null, modifier = Modifier.size(18.dp))
                    StatusDot(
                        tone = model.tone,
                        pulsing = model.pulsing,
                        boxSize = 10.dp,
                        modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-4).dp),
                    )
                }
            },
            label = { Text(stringResource(R.string.kernel_rail_label)) },
        )
    }
}

@LightDarkPreviews
@Composable
private fun KernelChipDrawerPreview() {
    ComponentPreview {
        Column {
            KernelChip(KernelGate.Ready, 25463, onOpenStatus = {})
            Spacer(Modifier.size(Spacing.s))
            KernelChip(KernelGate.Busy(KernelGate.BusyKind.Connecting, null, false), 25463, onOpenStatus = {})
            Spacer(Modifier.size(Spacing.s))
            KernelChip(KernelGate.Busy(KernelGate.BusyKind.Restarting, KernelGate.BusyLabel.Phase(RestartPhase.Replacing), false), 25463, onOpenStatus = {})
            Spacer(Modifier.size(Spacing.s))
            KernelChip(KernelGate.Busy(KernelGate.BusyKind.Draining, null, false), 25463, onOpenStatus = {})
            Spacer(Modifier.size(Spacing.s))
            KernelChip(KernelGate.Down(KernelGate.DownVariant.Asleep, null), null, onOpenStatus = {})
            Spacer(Modifier.size(Spacing.s))
            KernelChip(KernelGate.Down(KernelGate.DownVariant.StartFailed, null), null, onOpenStatus = {})
            Spacer(Modifier.size(Spacing.s))
            KernelChip(KernelGate.DegradedNoCommands, 25463, onOpenStatus = {})
        }
    }
}

@LightDarkPreviews
@Composable
private fun KernelChipRailPreview() {
    ComponentPreview {
        Column {
            KernelChip(KernelGate.Ready, 25463, onOpenStatus = {}, placement = KernelChipPlacement.Rail)
            KernelChip(KernelGate.Down(KernelGate.DownVariant.Asleep, null), null, onOpenStatus = {}, placement = KernelChipPlacement.Rail)
            KernelChip(KernelGate.Busy(KernelGate.BusyKind.Reconnecting, null, false), 25463, onOpenStatus = {}, placement = KernelChipPlacement.Rail)
        }
    }
}
