package dev.cline.termux.ui.changes

import android.content.Context
import android.content.res.Resources
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Changes summary line: counts spliced into the copy by mark, never by index (DS §8.2.3). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChangesSummaryTextTest {
    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val green = SpanStyle(color = Color.Green)
    private val red = SpanStyle(color = Color.Red)
    private val marks = arrayOf<Any>(SUMMARY_ADDED_MARK.toString(), SUMMARY_REMOVED_MARK.toString())

    private fun splice(formatted: String, added: String = "+12", removed: String = "-3") =
        spliceSummaryCounts(formatted, added, removed, green, red)

    private fun AnnotatedString.styledText(style: SpanStyle): List<String> =
        spanStyles.filter { it.item == style }.map { text.substring(it.start, it.end) }

    @Test fun `english copy colours each count`() {
        val counts = resources.getString(R.string.changes_summary_counts, *marks)
        val sentence = resources.getQuantityString(R.plurals.changes_summary, 2, counts, 2)
        val added = resources.getString(R.string.diff_additions_count, 12)
        val removed = resources.getString(R.string.diff_deletions_count, 3)
        val text = splice(sentence, added, removed)
        assertEquals("$added $removed across 2 files", text.text)
        assertEquals(listOf(added), text.styledText(green))
        assertEquals(listOf(removed), text.styledText(red))
    }

    @Test fun `singular file count`() {
        val counts = resources.getString(R.string.changes_summary_counts, *marks)
        val sentence = resources.getQuantityString(R.plurals.changes_summary, 1, counts, 1)
        assertEquals("+12 -3 across 1 file", splice(sentence).text)
    }

    @Test fun `a translation may reorder the counts`() {
        val counts = String.format("%2\$s %1\$s", *marks)
        val text = splice(String.format("%1\$s in %2\$d Dateien", counts, 4))
        assertEquals("-3 +12 in 4 Dateien", text.text)
        assertEquals(listOf("+12"), text.styledText(green))
        assertEquals(listOf("-3"), text.styledText(red))
        assertEquals(3, text.spanStyles.single { it.item == green }.start)
        assertEquals(0, text.spanStyles.single { it.item == red }.start)
    }

    @Test fun `a translation may wrap the counts in punctuation`() {
        val counts = String.format("（%1\$s，%2\$s）", *marks)
        val text = splice(String.format("%2\$d 个文件%1\$s", counts, 5))
        assertEquals("5 个文件（+12，-3）", text.text)
        assertEquals(listOf("+12"), text.styledText(green))
        assertEquals(listOf("-3"), text.styledText(red))
    }

    @Test fun `copy shorter than the counts never throws`() {
        // A translation that drops the separator (or a count) used to make substring() throw.
        val text = splice(String.format("%1\$s%2\$s", *marks))
        assertEquals("+12-3", text.text)
        assertEquals(listOf("+12"), text.styledText(green))
        val dropped = splice(String.format("%1\$s", *marks))
        assertEquals("+12", dropped.text)
        assertEquals(emptyList<String>(), dropped.styledText(red))
    }

    @Test fun `text without marks passes through unchanged`() {
        val text = splice("No marks here")
        assertEquals("No marks here", text.text)
        assertEquals(0, text.spanStyles.size)
    }
}
