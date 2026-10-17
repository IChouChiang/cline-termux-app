package dev.cline.termux.ui.approval

import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.CountBadge
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.components.rememberCopyQuantityResources
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/**
 * The collapsed state of an approval or question sheet (composer-approvals.md C3.3, C4.3):
 * docked 8dp above `ComposerSurface`, `warning.container` with `onContainer` content, min
 * 56dp, title up to two lines in a weighted slot, an optional count `Badge`, and an
 * end-aligned action label styled as a `TextButton`; it grows with font scale instead of
 * clipping. Tapping anywhere reopens the sheet. The banner is one control: one keyboard focus
 * stop with the focus ring and one TalkBack node, "[title], [action]" ([description]). Put a
 * `focusRequester` in [modifier] to hand focus back to it when its sheet closes (DS §8.1.7).
 */
@Composable
fun PendingInputBanner(
    icon: ImageVector,
    title: String,
    actionLabel: String,
    description: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int = 1,
) {
    val colors = ClineTheme.colors.warning
    Surface(
        onClick = onAction,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .focusRing(MaterialTheme.shapes.medium)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = actionLabel) { onAction(); true }
            },
        shape = MaterialTheme.shapes.medium,
        color = colors.container,
        contentColor = colors.onContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = colors.onContainer)
            Spacer(Modifier.width(Spacing.m))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (count >= 2) {
                Spacer(Modifier.width(Spacing.s))
                CountBadge(count)
            }
            Spacer(Modifier.width(Spacing.xs))
            // Drawn like a TextButton but not a control of its own: the whole banner is the one
            // focus stop and the one TalkBack node (DS §3.4, §8.1.7).
            Box(
                modifier = Modifier.heightIn(min = Spacing.touchTarget).padding(horizontal = Spacing.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge, color = colors.onContainer)
            }
        }
    }
}

/** "Tool approval required" + count + "Review" (C3.3); shown while [ApprovalUiState.bannerVisible]. */
@Composable
fun ApprovalBanner(count: Int, onReview: () -> Unit, modifier: Modifier = Modifier) {
    PendingInputBanner(
        icon = ClineIcons.GppMaybeFilled,
        title = stringResource(R.string.approval_banner_title),
        actionLabel = stringResource(R.string.approval_banner_action),
        description = approvalBannerDescription(rememberCopyQuantityResources(), count),
        onAction = onReview,
        modifier = modifier,
        count = count,
    )
}

/**
 * The banner's content description. A single request reads without a count ("Tool approval
 * required, Review"); that choice is made here rather than by the plural's `one` form, which
 * also matches 21, 31, ... in some locales and 0 in others, so every plural item carries the
 * count. Pass [rememberCopyQuantityResources] so plural forms follow the copy's language.
 */
internal fun approvalBannerDescription(resources: Resources, count: Int): String =
    if (count == 1) {
        resources.getString(R.string.approval_banner_cd_single)
    } else {
        resources.getQuantityString(R.plurals.approval_banner_cd, count, count)
    }

@LightDarkPreviews
@Composable
private fun ApprovalBannerPreview() {
    ComponentPreview {
        Column {
            ApprovalBanner(count = 1, onReview = {})
            Spacer(Modifier.size(Spacing.s))
            ApprovalBanner(count = 3, onReview = {})
        }
    }
}

@Preview(name = "Font scale 2, light", fontScale = 2f)
@Preview(name = "Font scale 2, dark", fontScale = 2f, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
@Composable
private fun ApprovalBannerLargeFontPreview() {
    ComponentPreview { ApprovalBanner(count = 2, onReview = {}) }
}
