package dev.cline.termux.data.chat

import java.text.BreakIterator

/*
 * Grapheme-safe cuts of user text (DS §8.2.7: never truncate user text mid-grapheme).
 *
 * The desktop limits are JS `String.prototype.slice` lengths, i.e. UTF-16 code units, so the
 * limits stay in UTF-16 units here too; the cut only moves back to the previous grapheme-cluster
 * boundary so a surrogate pair, a combining sequence or a ZWJ emoji is never split.
 */

/**
 * The longest prefix of [text] made of whole grapheme clusters whose length is at most
 * [maxUnits] UTF-16 code units. Returns [text] itself when it already fits, "" for
 * `maxUnits <= 0`.
 */
fun takeGraphemePrefix(text: String, maxUnits: Int): String {
    if (text.length <= maxUnits) return text
    if (maxUnits <= 0) return ""
    return text.substring(0, graphemeCutIndex(text, maxUnits))
}

/**
 * [text] cut to at most [maxGraphemes] grapheme clusters (`BreakIterator`), plus "…" when
 * anything was cut; returned unchanged when it already fits. Unlike [takeGraphemePrefix] the
 * limit counts user-perceived characters, not UTF-16 units: a ZWJ emoji or a CJK ideograph
 * counts as one. The composer's compact ModelChip label (DS #7, C1.4 rule 3: 16 graphemes).
 */
fun ellipsizeGraphemes(text: String, maxGraphemes: Int = 16): String {
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    var count = 0
    var end = iterator.first()
    while (true) {
        val next = iterator.next()
        if (next == BreakIterator.DONE) return text
        if (++count > maxGraphemes) return text.substring(0, end) + "…"
        end = next
    }
}

/**
 * The first grapheme cluster of [text] (`BreakIterator`), "" for an empty string: a whole
 * surrogate pair, combining sequence or ZWJ emoji, never a lone UTF-16 unit. Avatar initials.
 */
fun firstGrapheme(text: String): String {
    if (text.isEmpty()) return ""
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    val end = iterator.following(0)
    return if (end == BreakIterator.DONE) text else text.substring(0, end)
}

/** The largest grapheme-cluster boundary of [text] that is `<= maxUnits` (UTF-16 index). */
internal fun graphemeCutIndex(text: String, maxUnits: Int): Int {
    if (maxUnits >= text.length) return text.length
    if (maxUnits <= 0) return 0
    val iterator = BreakIterator.getCharacterInstance()
    iterator.setText(text)
    // `preceding(n)` is the last boundary strictly before n; n itself counts when it is one.
    val cut = if (iterator.isBoundary(maxUnits)) maxUnits else iterator.preceding(maxUnits)
    var safe = if (cut == BreakIterator.DONE) 0 else cut
    // Belt and braces: never leave a dangling high surrogate whatever the break rules say.
    if (safe > 0 && Character.isHighSurrogate(text[safe - 1]) && safe < text.length && Character.isLowSurrogate(text[safe])) {
        safe -= 1
    }
    return safe
}
