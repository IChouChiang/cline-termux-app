package dev.cline.termux.ui.providers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.components.InlineErrorCard
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.SearchField
import dev.cline.termux.ui.components.TagBadge
import dev.cline.termux.ui.components.TooltipIconButton
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.ClineMono
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing

/*
 * S5.4 models card (desktop `ProviderModelsSection`): header with the refresh button, the
 * search field, then one row per model with its badges, capability icons, favourite star and
 * copy-id button. Drawn as lazy items so a long catalog scrolls with the rest of the detail.
 */

class ModelsCallbacks(
    val onRefresh: () -> Unit,
    val onQuery: (String) -> Unit,
    val onToggleFavorite: (modelId: String) -> Unit,
    val onCopy: (modelId: String) -> Unit,
)

private val CardTop = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val CardBottom = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)

fun LazyListScope.modelsCard(models: ModelsCardUi, callbacks: ModelsCallbacks, gutter: Dp) {
    item(key = "models-header") {
        Column(
            Modifier
                .padding(horizontal = gutter)
                .padding(top = Spacing.l)
                .clip(CardTop)
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.provider_models_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (models.loading) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                } else if (models.refreshAvailable) {
                    TooltipIconButton(
                        icon = ClineIcons.Refresh,
                        contentDescription = stringResource(R.string.provider_models_refresh),
                        onClick = callbacks.onRefresh,
                    )
                }
            }
            if (models.searchVisible) {
                SearchField(
                    value = models.query,
                    onValueChange = callbacks.onQuery,
                    placeholder = stringResource(R.string.provider_models_search_placeholder),
                    clearContentDescription = stringResource(R.string.components_clear_search),
                    contentDescription = stringResource(R.string.provider_models_search_cd),
                    modifier = Modifier.padding(bottom = Spacing.s),
                )
            }
        }
    }
    when {
        models.error != null -> item(key = "models-error") {
            ModelsFooter(gutter) {
                InlineErrorCard(
                    message = models.error,
                    actionLabel = stringResource(R.string.components_retry),
                    onAction = callbacks.onRefresh,
                )
            }
        }
        models.rows.isEmpty() -> item(key = "models-empty") {
            ModelsFooter(gutter) {
                Text(
                    text = when {
                        models.loading -> stringResource(R.string.provider_models_loading)
                        models.totalCount > 0 && models.query.isNotBlank() -> stringResource(R.string.provider_models_no_match, models.query.trim())
                        else -> stringResource(R.string.provider_models_empty)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.m).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        else -> {
            val last = models.rows.last().id
            items(models.rows, key = { "model-${it.id}" }) { row ->
                Column(
                    Modifier
                        .padding(horizontal = gutter)
                        .then(if (row.id == last) Modifier.clip(CardBottom) else Modifier)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ModelRow(row, copied = row.id == models.copiedId, callbacks = callbacks)
                }
            }
        }
    }
}

@Composable
private fun ModelsFooter(gutter: Dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .padding(horizontal = gutter)
            .fillMaxWidth()
            .clip(CardBottom)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
    ) { content() }
}

/**
 * S5.4 row (desktop `provider-list-view.tsx` model row): headline = name, featured badges,
 * audio badge and capability icons in one merged node; then the description (one line);
 * then the model id with its copy glyph as one 48dp button ("Copied" after a copy); the
 * favourite star trails the row.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelRow(row: ModelRowUi, copied: Boolean, callbacks: ModelsCallbacks, modifier: Modifier = Modifier) {
    val favoriteLabel = stringResource(if (row.favorite) R.string.provider_unfavorite else R.string.provider_favorite)
    val copyLabel = stringResource(R.string.provider_copy_model_id)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(copyLabel) { callbacks.onCopy(row.id); true },
                    CustomAccessibilityAction(favoriteLabel) { callbacks.onToggleFavorite(row.id); true },
                )
            }
            .padding(start = Spacing.l, end = Spacing.xs, top = Spacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            FlowRow(
                modifier = Modifier.semantics(mergeDescendants = true) {},
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xxs),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(row.name, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                for (badge in row.badges) ModelBadgePill(badge)
                if (row.fileSupport) CapabilityIcon(ClineIcons.Draft, stringResource(R.string.provider_cap_file))
                if (row.imageSupport) CapabilityIcon(ClineIcons.Image, stringResource(R.string.provider_cap_image))
                row.audioPill?.let { AudioPillBadge(it) }
                if (row.streamingUnsupported) {
                    CapabilityIcon(ClineIcons.WifiOff, stringResource(R.string.provider_streaming_unsupported), size = 14.dp)
                }
                if (row.audioSupport) CapabilityIcon(ClineIcons.Mic, stringResource(R.string.provider_cap_audio))
                if (row.reasoningSupport) CapabilityIcon(ClineIcons.Neurology, stringResource(R.string.provider_cap_reasoning))
            }
            row.description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Spacing.xxs),
                )
            }
            val copyCd = stringResource(R.string.provider_copy_model_id_cd, row.id)
            PlainTooltipBox(text = copyLabel) {
                Row(
                    Modifier
                        .heightIn(min = 48.dp)
                        .focusRing(MaterialTheme.shapes.small, inset = true)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(role = Role.Button, onClickLabel = copyLabel) { callbacks.onCopy(row.id) }
                        .semantics(mergeDescendants = true) { contentDescription = copyCd },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        row.id,
                        style = ClineMono.small,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        ClineIcons.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (copied) {
                        Text(
                            stringResource(R.string.provider_copied),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        )
                    }
                }
            }
        }
        IconButton(onClick = { callbacks.onToggleFavorite(row.id) }) {
            Icon(
                if (row.favorite) ClineIcons.StarFilled else ClineIcons.Star,
                contentDescription = stringResource(if (row.favorite) R.string.provider_unfavorite_cd else R.string.provider_favorite_cd, row.name),
                tint = if (row.favorite) ClineTheme.colors.warning.color else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelBadgePill(badge: ModelBadge) {
    when (badge) {
        ModelBadge.Recommended -> TagBadge(text = stringResource(R.string.provider_badge_recommended))
        ModelBadge.Free -> TagBadge(text = stringResource(R.string.provider_badge_free))
        is ModelBadge.Tag -> TagBadge(text = badge.text)
    }
}

@Composable
private fun AudioPillBadge(pill: AudioPill) {
    val (label, tooltip) = when (pill) {
        AudioPill.Realtime -> stringResource(R.string.provider_audio_realtime) to stringResource(R.string.provider_audio_realtime_tooltip)
        AudioPill.Transcription -> stringResource(R.string.provider_audio_transcription) to stringResource(R.string.provider_audio_transcription_tooltip)
    }
    val description = stringResource(R.string.provider_audio_pill_cd, label, tooltip)
    PlainTooltipBox(text = tooltip) {
        TagBadge(
            text = label,
            icon = if (pill == AudioPill.Realtime) ClineIcons.GraphicEq else ClineIcons.Mic,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

@Composable
private fun CapabilityIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Dp = 16.dp) {
    PlainTooltipBox(text = label) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
