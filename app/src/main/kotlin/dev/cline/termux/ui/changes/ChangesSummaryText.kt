package dev.cline.termux.ui.changes

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/*
 * The Changes summary line ("+N -N across N files", A.6). `changes_summary_counts` is formatted
 * with private-use marks in place of the two counts, the sentence plural is formatted with that
 * result, and each mark is then replaced with its styled count (the splice ToolLabelText.kt
 * uses). Translators may reorder the positional args or wrap them in punctuation (DS §8.2.3);
 * the colour always lands on the right count and no index arithmetic can go out of range.
 */

/** U+E000: stands in for the additions count while the copy is formatted. */
internal const val SUMMARY_ADDED_MARK: Char = ''

/** U+E001: stands in for the deletions count while the copy is formatted. */
internal const val SUMMARY_REMOVED_MARK: Char = ''

/**
 * Replaces [SUMMARY_ADDED_MARK] in [formatted] with [added] in [addedStyle] and
 * [SUMMARY_REMOVED_MARK] with [removed] in [removedStyle]; every other character is kept.
 */
internal fun spliceSummaryCounts(
    formatted: String,
    added: String,
    removed: String,
    addedStyle: SpanStyle,
    removedStyle: SpanStyle,
): AnnotatedString = buildAnnotatedString {
    formatted.forEach { char ->
        when (char) {
            SUMMARY_ADDED_MARK -> withStyle(addedStyle) { append(added) }
            SUMMARY_REMOVED_MARK -> withStyle(removedStyle) { append(removed) }
            else -> append(char)
        }
    }
}
