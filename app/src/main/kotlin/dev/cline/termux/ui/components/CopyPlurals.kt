package dev.cline.termux.ui.components

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import dev.cline.termux.ui.kernel.quantityResources

/**
 * `pluralStringResource` with the plural form chosen by the language the copy is written in,
 * not the device's. The app ships English copy only, and Android picks the quantity by the
 * device locale's rules, so on a Chinese, Japanese or Korean phone (one form, "other") every
 * English plural read "1 tool calls", "1 requests". See [quantityResources].
 */
@Composable
fun copyPluralStringResource(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String {
    val quantity = rememberCopyQuantityResources()
    return if (formatArgs.isEmpty()) quantity.getQuantityString(id, count) else quantity.getQuantityString(id, count, *formatArgs)
}

/** The [quantityResources] for the current configuration, rebuilt when it changes. */
@Composable
fun rememberCopyQuantityResources(): Resources {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { quantityResources(resources) }
}
