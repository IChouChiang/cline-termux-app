package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.ClineMono

/**
 * Code, commands, paths, ids and diff lines are forced LTR under any locale (DS §8.2.6:
 * `LocalLayoutDirection = Ltr` + `TextDirection.Ltr`) and read by TalkBack as written
 * (DS §8.1.12: `contentDescription = raw`, no ligature glyphs). Defaults to `ClineMono.body`.
 * A single-line label (`maxLines = 1`) ellipsizes by default (DS §2.3); pass
 * `TextOverflow.StartEllipsis` for a path whose leaf must stay visible.
 */
@Composable
fun LtrText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = ClineMono.body,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = defaultLtrOverflow(maxLines),
    softWrap: Boolean = true,
    readAsWritten: Boolean = true,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            modifier = if (readAsWritten) modifier.semantics { contentDescription = text } else modifier,
            style = style.copy(textDirection = TextDirection.Ltr),
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
        )
    }
}

/** [AnnotatedString] variant (ANSI-coloured output, highlighted diff lines). */
@Composable
fun LtrText(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    style: TextStyle = ClineMono.body,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = defaultLtrOverflow(maxLines),
    softWrap: Boolean = true,
    readAsWritten: Boolean = true,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Text(
            text = text,
            modifier = if (readAsWritten) modifier.semantics { contentDescription = text.text } else modifier,
            style = style.copy(textDirection = TextDirection.Ltr),
            color = color,
            maxLines = maxLines,
            overflow = overflow,
            softWrap = softWrap,
        )
    }
}

/**
 * [LtrText]'s default overflow: an ellipsis for a single-line label (DS §2.3, so a long or
 * double-width CJK name never cuts mid-glyph), a clip for wrapping text.
 */
internal fun defaultLtrOverflow(maxLines: Int): TextOverflow =
    if (maxLines == 1) TextOverflow.Ellipsis else TextOverflow.Clip

/** Forces LTR layout for a block of code-like content (diff hunks, terminal output). */
@Composable
fun LtrBlock(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@LightDarkPreviews
@Composable
private fun LtrTextPreview() {
    ComponentPreview {
        Column {
            LtrText("$ bun test --filter chat-schema")
            LtrText("/data/data/com.termux/files/home/projects/cline-app", style = ClineMono.small, color = MaterialTheme.colorScheme.onSurfaceVariant)
            CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium) {
                LtrText("sess_01HZX…9Q", style = ClineMono.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.width(160.dp)) {
                LtrText("界面组件设计规范文档最终版本.kt", maxLines = 1)
            }
            Box(Modifier.width(160.dp)) {
                LtrText(
                    "app/src/main/kotlin/dev/cline/termux/ui/组件",
                    style = ClineMono.small,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.StartEllipsis,
                )
            }
        }
    }
}
