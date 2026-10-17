package dev.cline.termux.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/** [joinSpokenLines]: one separator from resources, never doubled punctuation (DS §8.2.3). */
class SpokenSentencesTest {
    private val sentence = { first: String, second: String -> "$first. $second" }
    private val continued = { first: String, second: String -> "$first $second" }
    private val cjkSentence = { first: String, second: String -> "$first。$second" }
    private val cjkContinued = { first: String, second: String -> "$first$second" }

    private fun join(vararg lines: String) = joinSpokenLines(lines.toList(), sentence, continued)

    @Test
    fun `a single line is spoken as is`() {
        assertEquals("Cline Hub at port 25463, connected", join("Cline Hub at port 25463, connected"))
    }

    @Test
    fun `an empty or blank list is spoken as nothing`() {
        assertEquals("", join())
        assertEquals("", join("  ", ""))
    }

    @Test
    fun `lines without terminal punctuation use the sentence separator`() {
        assertEquals(
            "Cline Hub at port 25463, failed. Timed out after 15 s",
            join("Cline Hub at port 25463, failed", "Timed out after 15 s"),
        )
    }

    @Test
    fun `a line that already ends a sentence is not given a second period`() {
        assertEquals(
            "Couldn't start the Cline Hub. Timed out waiting for the discovery record.",
            join("Couldn't start the Cline Hub.", "Timed out waiting for the discovery record."),
        )
        assertEquals("Starting… Next", join("Starting…", "Next"))
        assertEquals("Done! Next", join("Done!", "Next"))
        assertEquals("Ready? Next", join("Ready?", "Next"))
    }

    @Test
    fun `an introducing colon is dropped before the separator`() {
        assertEquals(
            "Run the installer in Termux. It takes about a minute",
            join("Run the installer in Termux:", "It takes about a minute"),
        )
        assertEquals("Run this. Next", join("Run this :", "Next"))
    }

    @Test
    fun `several lines are joined left to right and blank ones skipped`() {
        assertEquals(
            "Termux is installed. Version 0.118.3. Signed by F-Droid",
            join("Termux is installed.", "", "Version 0.118.3", "Signed by F-Droid"),
        )
    }

    @Test
    fun `surrounding whitespace never reaches TalkBack`() {
        assertEquals("One. Two", join("  One  ", " Two "))
    }

    @Test
    fun `a CJK locale supplies its own separators and punctuation`() {
        assertEquals("无法启动。超时", joinSpokenLines(listOf("无法启动", "超时"), cjkSentence, cjkContinued))
        assertEquals("无法启动。超时", joinSpokenLines(listOf("无法启动。", "超时"), cjkSentence, cjkContinued))
        assertEquals("在 Termux 中运行。下一步", joinSpokenLines(listOf("在 Termux 中运行：", "下一步"), cjkSentence, cjkContinued))
    }
}
