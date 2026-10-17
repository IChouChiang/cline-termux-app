package dev.cline.termux.data.tools

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.cline.termux.ui.chat.rows.resolveGroupedToolLabel
import dev.cline.termux.ui.chat.rows.resolveToolLabel

/**
 * Resolves typed tool labels against the app's English resources, so the ported desktop
 * tests keep asserting the desktop's strings. Only for Robolectric tests.
 */
internal object EnglishToolLabels {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    fun of(label: ToolLabel): String = resolveToolLabel(label, resources, code = null).text

    fun group(labels: List<ToolLabel>): String = resolveGroupedToolLabel(labels, resources, code = null).text
}

/** The summary's label as the desktop's English string. */
internal val ToolSummary.english: String get() = EnglishToolLabels.of(label)
