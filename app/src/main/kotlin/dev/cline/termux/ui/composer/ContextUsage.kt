package dev.cline.termux.ui.composer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.data.chat.ContextUsageCalculator
import dev.cline.termux.data.chat.ContextUsageData
import dev.cline.termux.data.chat.ContextUsageTone
import dev.cline.termux.data.sessions.formatCostUsd
import dev.cline.termux.ui.components.ClineSheet
import dev.cline.termux.ui.components.ComponentPreview
import dev.cline.termux.ui.components.LightDarkPreviews
import dev.cline.termux.ui.components.LtrText
import dev.cline.termux.ui.components.PlainTooltipBox
import dev.cline.termux.ui.components.focusRing
import dev.cline.termux.ui.theme.ClineMotion
import dev.cline.termux.ui.theme.ClineTheme
import dev.cline.termux.ui.theme.Spacing
import java.util.Locale

/** The ring's colour: `primary` < 50 %, `warning.color` ≥ 50 %, `error` ≥ 75 % (DS §1.7). */
@Composable
private fun ContextUsageTone.ringColor() = when (this) {
    ContextUsageTone.Normal -> MaterialTheme.colorScheme.primary
    ContextUsageTone.Warning -> ClineTheme.colors.warning.color
    ContextUsageTone.Critical -> MaterialTheme.colorScheme.error
}

private fun currentLocale(configuration: android.content.res.Configuration): Locale =
    if (configuration.locales.isEmpty) Locale.getDefault() else configuration.locales[0]

/** "Context window: 12,345 of 200,000 tokens used (6%)". */
@Composable
fun contextRingDescription(usage: ComposerUsage): String {
    val locale = currentLocale(LocalConfiguration.current)
    return stringResource(
        R.string.composer_cd_context_ring,
        ContextUsageCalculator.formatInteger(usage.usage.totalTokens, locale),
        ContextUsageCalculator.formatInteger(usage.usage.contextWindow, locale),
        usage.usage.percent,
    )
}

/**
 * DS #8 usage ring: a 20dp ring (stroke 3.5dp, round cap, from −90°) in a 48dp `IconButton`,
 * 65 % alpha at rest and 100 % while its sheet is open; the sweep animates over 120 ms.
 */
@Composable
fun UsageRing(usage: ComposerUsage, sheetOpen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, focus: FocusRequester? = null) {
    val description = contextRingDescription(usage)
    val sweep by animateFloatAsState(usage.usage.ratio.toFloat(), tween(120), label = "ring")
    val color = usage.usage.tone.ringColor()
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f)
    PlainTooltipBox(text = description, modifier = modifier) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .then(if (focus != null) Modifier.focusRequester(focus) else Modifier)
                .focusRing(CircleShape)
                .semantics { contentDescription = description },
        ) {
            Canvas(Modifier.size(20.dp).alpha(if (sheetOpen) 1f else 0.65f)) {
                val stroke = 3.5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke))
                drawArc(color, -90f, 360f * sweep, useCenter = false, topLeft = Offset(inset, inset), size = arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
    }
}

/** C1.12 Context usage sheet: value, segmented bar, the four rows (cost only when known). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContextUsageSheet(usage: ComposerUsage, onDismiss: () -> Unit, returnFocus: FocusRequester? = null) {
    val locale = currentLocale(LocalConfiguration.current)
    val title = stringResource(R.string.composer_usage_title)
    ClineSheet(
        onDismissRequest = onDismiss,
        // No half-height anchor, so one predictive back closes it (DS §5.8).
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        paneTitle = title,
        returnFocus = returnFocus,
        titleContent = {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            LtrText(usage.usage.valueLabel, style = ClineTheme.mono.small, color = MaterialTheme.colorScheme.onSurface)
        },
    ) {
        ContextUsageDetails(usage, locale)
    }
}

@Composable
internal fun ContextUsageDetails(usage: ComposerUsage, locale: Locale, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = Spacing.sheetGutter).padding(bottom = Spacing.l)) {
        UsageBar(usage)
        Spacer(Modifier.height(Spacing.m))
        UsageRow(stringResource(R.string.composer_usage_input), ContextUsageCalculator.formatInteger(usage.usage.tokensIn, locale))
        UsageRow(stringResource(R.string.composer_usage_output), ContextUsageCalculator.formatInteger(usage.usage.tokensOut, locale))
        UsageRow(stringResource(R.string.composer_usage_cached), ContextUsageCalculator.formatInteger(usage.usage.cacheReadTokens, locale))
        formatCostUsd(usage.totalCostUsd)?.let { UsageRow(stringResource(R.string.composer_usage_cost), it) }
    }
}

/** 4dp segmented bar: uncached input `primary`, cached `primary @ 60 %`, output `info.color`. */
@Composable
private fun UsageBar(usage: ComposerUsage) {
    val uncached by animateFloatAsState(usage.usage.uncachedInputFraction, tween(120, easing = ClineMotion.Standard), label = "uncached")
    val cached by animateFloatAsState(usage.usage.cachedInputFraction, tween(120, easing = ClineMotion.Standard), label = "cached")
    val output by animateFloatAsState(usage.usage.outputFraction, tween(120, easing = ClineMotion.Standard), label = "output")
    val primary = MaterialTheme.colorScheme.primary
    val info = ClineTheme.colors.info.color
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    // Fractions are of the full track (agent-context-usage.tsx `segmentWidth`), drawn end to end
    // from the start edge: the bar mirrors under RTL like the rest of the sheet (DS §8.2.6).
    Canvas(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)) {
        drawRect(track)
        val rtl = layoutDirection == LayoutDirection.Rtl
        usageBarSegments(listOf(uncached, cached, output), size.width, rtl).zip(listOf(primary, primary.copy(alpha = 0.6f), info)) { (left, w), color ->
            if (w > 0f) drawRect(color, topLeft = Offset(left, 0f), size = Size(w, size.height))
        }
    }
}

/**
 * The bar's segments as (left edge, width) pairs: each [fractions] entry is a share of the full
 * [width], laid end to end from the start edge, which is the right edge when [rtl].
 */
internal fun usageBarSegments(fractions: List<Float>, width: Float, rtl: Boolean): List<Pair<Float, Float>> {
    var x = 0f
    return fractions.map { fraction ->
        val w = width * fraction.coerceIn(0f, 1f)
        val left = if (rtl) width - x - w else x
        x += w
        left to w
    }
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        LtrText(value, style = ClineTheme.mono.small, color = MaterialTheme.colorScheme.onSurface)
    }
}

internal fun previewUsage(tokensIn: Long, tokensOut: Long, cached: Long, window: Long, cost: Double?): ComposerUsage =
    ComposerUsage(ContextUsageCalculator.compute(ContextUsageData(tokensIn, tokensOut, cached, window), Locale.US)!!, cost)

@LightDarkPreviews
@Composable
private fun UsageRingPreview() = ComponentPreview {
    Row {
        UsageRing(previewUsage(12_034, 311, 8_192, 200_000, 0.0421), sheetOpen = false, onClick = {})
        UsageRing(previewUsage(110_000, 2_000, 0, 200_000, null), sheetOpen = false, onClick = {})
        UsageRing(previewUsage(160_000, 2_000, 0, 200_000, null), sheetOpen = true, onClick = {})
    }
}

@LightDarkPreviews
@Composable
private fun ContextUsageDetailsPreview() = ComponentPreview(padding = false) {
    Column(Modifier.width(412.dp)) {
        ContextUsageDetails(previewUsage(12_034, 311, 8_192, 200_000, 0.0421), Locale.US)
    }
}

@Preview(name = "Cached above input", widthDp = 412)
@Composable
private fun ContextUsageClampPreview() = ComponentPreview(padding = false) {
    ContextUsageDetails(previewUsage(1_000, 500, 50_000, 1_200, null), Locale.US)
}

/** DS §8.2.6: the segmented bar fills from the start edge, the right one under RTL. */
@Preview(name = "Usage details, RTL", widthDp = 412, locale = "ar")
@Composable
private fun ContextUsageRtlPreview() = ComponentPreview(padding = false) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ContextUsageDetails(previewUsage(60_000, 20_000, 30_000, 200_000, 0.0421), Locale.US)
    }
}
