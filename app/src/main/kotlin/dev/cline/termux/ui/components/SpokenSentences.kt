package dev.cline.termux.ui.components

/**
 * Joins the lines of one merged TalkBack node into a single spoken text (DS §8.2.3: no
 * translatable fragments are concatenated in code). The separator lives in the caller's
 * positional resources, so a locale chooses its own (CJK "%1$s。%2$s", no space):
 *
 * - [sentence] formats "<first>. <second>" and is used when the text so far ends without
 *   terminal punctuation.
 * - [continued] formats "<first> <second>" and is used when the text so far already ends a
 *   sentence ("Starting…", "Done!", "完了。"), so TalkBack never reads doubled punctuation.
 *
 * A trailing colon ("Run the installer in Termux:") introduces a command shown beside the
 * line, not spoken text, so it is dropped before the [sentence] separator. Blank lines are
 * skipped; an empty list yields "".
 */
fun joinSpokenLines(
    lines: List<String>,
    sentence: (first: String, second: String) -> String,
    continued: (first: String, second: String) -> String,
): String = lines
    .map(String::trim)
    .filter(String::isNotEmpty)
    .reduceOrNull { spoken, next ->
        val head = spoken.trimEnd().removeIntroducingColon()
        if (head.endsWithSentencePunctuation()) continued(head, next) else sentence(head, next)
    }
    .orEmpty()

private const val SENTENCE_END = ".!?…。！？．"
private const val COLONS = ":："

private fun String.removeIntroducingColon(): String =
    if (isNotEmpty() && last() in COLONS) dropLast(1).trimEnd() else this

private fun String.endsWithSentencePunctuation(): Boolean = isNotEmpty() && last() in SENTENCE_END
