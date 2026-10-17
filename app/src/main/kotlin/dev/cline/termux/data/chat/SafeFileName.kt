package dev.cline.termux.data.chat

/*
 * File names for shared and opened files (generated media, diff and output shares, diagnostics).
 * Only characters a filesystem or a receiving app cannot take are replaced, so a CJK or emoji
 * name such as "架构图.png" keeps its text (DS §2.3 mixed scripts), and the length cut never
 * splits a grapheme cluster (DS §8.2.7).
 */

/** Path separators, the characters Windows-style receivers reject, and C0 controls. */
val UNSAFE_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]")

/**
 * ext4 and f2fs allow 255 bytes per name; the cap leaves room for a " (n)" de-duplication
 * suffix. A CJK character is 3 UTF-8 bytes, so a UTF-16 limit alone can overflow it.
 */
const val MAX_FILE_NAME_BYTES = 240

/**
 * [raw] with every unsafe character replaced by `_`, surrounding spaces and dots removed (no
 * `..`, no hidden files), [fallback] when nothing is left, cut to whole graphemes within
 * [maxUnits] UTF-16 units and [MAX_FILE_NAME_BYTES] UTF-8 bytes.
 */
fun sanitizeFileName(raw: String, fallback: String, maxUnits: Int): String {
    val cleaned = raw.trim().replace(UNSAFE_FILE_NAME_CHARS, "_").trim('.', ' ')
    return fitFileName(cleaned.ifEmpty { fallback }, maxUnits)
}

/** The longest whole-grapheme prefix of [name] within [maxUnits] UTF-16 units and [MAX_FILE_NAME_BYTES] bytes. */
fun fitFileName(name: String, maxUnits: Int): String {
    var cut = takeGraphemePrefix(name, maxUnits)
    while (cut.toByteArray(Charsets.UTF_8).size > MAX_FILE_NAME_BYTES) {
        cut = takeGraphemePrefix(cut, cut.length - 1)
    }
    return cut
}
