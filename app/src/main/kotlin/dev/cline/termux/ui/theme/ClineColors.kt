package dev.cline.termux.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * One status hue in its five roles (design-system.md §1.5): [color] for text and icons
 * (4.5:1 or better on every surface container), [onColor], a [container] with its
 * [onContainer], and [dot] for 8dp status dots (non-text, 3:1).
 */
@Immutable
data class StatusColors(
    val color: Color,
    val onColor: Color,
    val container: Color,
    val onContainer: Color,
    val dot: Color,
)

/**
 * Extended semantic colours. These are fixed per light/dark and never follow the wallpaper
 * (§1.2 rule 4): running is always green and approval always amber, as in the desktop.
 */
@Immutable
data class ClineColors(
    val success: StatusColors,
    val warning: StatusColors,
    val info: StatusColors,
    /** Background of an added diff line (successContainer blended over surfaceContainerLow). */
    val diffAddedLine: Color,
    /** Background of a removed diff line (errorContainer blended over surfaceContainerLow). */
    val diffRemovedLine: Color,
    /** Command output ground, the same in both themes (desktop `bg-black/90 text-zinc-100`). */
    val terminalBackground: Color,
    val terminalForeground: Color,
    val terminalCursor: Color,
    /** ANSI colours 0–15; bold maps 0–7 to 8–15. */
    val ansi: List<Color>,
)

/** The 16 ANSI colours for [ClineColors.terminalBackground]; identical in light and dark. */
private val Ansi16 = listOf(
    Color(0xFF52525B), Color(0xFFF87171), Color(0xFF4ADE80), Color(0xFFFACC15),
    Color(0xFF60A5FA), Color(0xFFC084FC), Color(0xFF22D3EE), Color(0xFFE4E4E7),
    Color(0xFF71717A), Color(0xFFFCA5A5), Color(0xFF86EFAC), Color(0xFFFDE047),
    Color(0xFF93C5FD), Color(0xFFD8B4FE), Color(0xFF67E8F9), Color(0xFFFAFAFA),
)

private val TerminalBackground = Color(0xFF0E0E12)
private val TerminalForeground = Color(0xFFE4E4E7)

val ClineLightColors = ClineColors(
    success = StatusColors(
        color = Color(0xFF006D42), onColor = Color(0xFFFFFFFF),
        container = Color(0xFFC4ECD0), onContainer = Color(0xFF002111),
        dot = Color(0xFF198856),
    ),
    warning = StatusColors(
        color = Color(0xFF855300), onColor = Color(0xFFFFFFFF),
        container = Color(0xFFFFDDB8), onContainer = Color(0xFF2A1700),
        dot = Color(0xFFA76A00),
    ),
    info = StatusColors(
        color = Color(0xFF1C5BB9), onColor = Color(0xFFFFFFFF),
        container = Color(0xFFD8E2FF), onContainer = Color(0xFF051A3C),
        dot = Color(0xFF3E75D4),
    ),
    diffAddedLine = Color(0xFFD7EFDF),
    diffRemovedLine = Color(0xFFFAE4E3),
    terminalBackground = TerminalBackground,
    terminalForeground = TerminalForeground,
    terminalCursor = TerminalForeground,
    ansi = Ansi16,
)

val ClineDarkColors = ClineColors(
    success = StatusColors(
        color = Color(0xFF76DAA1), onColor = Color(0xFF003920),
        container = Color(0xFF2B4E39), onContainer = Color(0xFFC4ECD0),
        dot = Color(0xFF5ABE87),
    ),
    warning = StatusColors(
        color = Color(0xFFFFB95F), onColor = Color(0xFF472A00),
        container = Color(0xFF643F07), onContainer = Color(0xFFFFDDB8),
        dot = Color(0xFFEC9914),
    ),
    info = StatusColors(
        color = Color(0xFFADC6FF), onColor = Color(0xFF002E6A),
        container = Color(0xFF35466A), onContainer = Color(0xFFD8E2FF),
        dot = Color(0xFF81AAFF),
    ),
    diffAddedLine = Color(0xFF23352C),
    diffRemovedLine = Color(0xFF570E14),
    terminalBackground = TerminalBackground,
    terminalForeground = TerminalForeground,
    terminalCursor = TerminalForeground,
    ansi = Ansi16,
)

/** Provided by [ClineTheme] with the same light/dark decision as the `ColorScheme`. */
val LocalClineColors = staticCompositionLocalOf { ClineLightColors }
