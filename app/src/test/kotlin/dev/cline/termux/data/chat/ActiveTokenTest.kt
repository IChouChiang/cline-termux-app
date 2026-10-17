package dev.cline.termux.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `getActiveSlash` / `getActiveMention` / the two insertions of chat-input-bar.tsx (C5.8, C6.7). */
class ActiveTokenTest {

    @Test fun slashAtStartOpensWithEmptyQuery() {
        assertEquals(ActiveSlash(0, ""), ActiveToken.slash("/", 1))
    }

    @Test fun slashQueryUpToCursor() {
        assertEquals(ActiveSlash(0, "te"), ActiveToken.slash("/team", 3))
        assertEquals(ActiveSlash(0, "team"), ActiveToken.slash("/team", 5))
    }

    @Test fun slashAfterWhitespaceOpens() {
        assertEquals(ActiveSlash(6, "rev"), ActiveToken.slash("hello /rev", 10))
    }

    @Test fun slashInsideAWordDoesNotOpen() {
        assertNull(ActiveToken.slash("src/main", 8))
        assertNull(ActiveToken.slash("a/b", 3))
    }

    @Test fun whitespaceAfterTheSlashCommitsIt() {
        assertNull(ActiveToken.slash("/team ", 6))
        assertNull(ActiveToken.slash("/team do it", 11))
    }

    @Test fun noSecondCommandAfterACompletedOne() {
        assertNull(ActiveToken.slash("/review then /", 14))
        assertEquals(ActiveSlash(5, ""), ActiveToken.slash("text /", 6))
    }

    @Test fun cursorOutOfRangeIsNull() {
        assertNull(ActiveToken.slash("/x", -1))
        assertNull(ActiveToken.slash("/x", 3))
        assertNull(ActiveToken.mention("@x", 5))
    }

    @Test fun noSlashIsNull() {
        assertNull(ActiveToken.slash("hello", 5))
    }

    @Test fun mentionAtStartAndAfterSpace() {
        assertEquals(ActiveMention(0, 4, "src"), ActiveToken.mention("@src", 4))
        assertEquals(ActiveMention(5, 10, "app."), ActiveToken.mention("look @app.ts", 10))
    }

    @Test fun mentionInsideEmailDoesNotOpen() {
        assertNull(ActiveToken.mention("me@example.com", 14))
    }

    @Test fun mentionEndsAtWhitespace() {
        assertNull(ActiveToken.mention("@src done", 9))
    }

    @Test fun emptyMentionQueryOpens() {
        assertEquals(ActiveMention(3, 4, ""), ActiveToken.mention("hi @", 4))
    }

    @Test fun insertSlashAddsSeparatorWhenSuffixIsNotWhitespace() {
        val slash = ActiveToken.slash("/wor", 4)!!
        assertEquals(TokenEdit("/workflow ", 10), ActiveToken.insertSlashCommand("/wor", 4, slash, "workflow"))
    }

    @Test fun insertSlashKeepsTheSuffixAndItsSpace() {
        // "/" typed before " world": tapping a command gives "/name world" (no double space).
        val input = "/ world"
        val slash = ActiveToken.slash(input, 1)!!
        val edit = ActiveToken.insertSlashCommand(input, 1, slash, "name")
        assertEquals("/name world", edit.text)
        assertEquals(6, edit.cursor)
    }

    @Test fun insertSlashKeepsTextBeforeTheToken() {
        val input = "please /rev"
        val slash = ActiveToken.slash(input, input.length)!!
        assertEquals(TokenEdit("please /review ", 15), ActiveToken.insertSlashCommand(input, input.length, slash, "review"))
    }

    @Test fun insertMentionReplacesTheQuery() {
        val input = "read @ap and fix"
        val mention = ActiveToken.mention(input, 8)!!
        val edit = ActiveToken.insertMention(input, mention, "src/app.ts")
        assertEquals("read @src/app.ts  and fix", edit.text)
        assertEquals(17, edit.cursor)
    }
}
