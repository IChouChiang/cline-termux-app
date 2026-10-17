package dev.cline.termux.ui.kernel

import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import dev.cline.termux.R
import java.util.Locale

/**
 * Copy as data, so the kernel projections in this package stay pure (and JVM-testable)
 * while every user-visible word still comes from `strings_kernel.xml`. [Raw] carries kernel
 * text (stderr tails, close reasons) that is shown verbatim as data, never as app copy.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        constructor(@StringRes id: Int, vararg args: Any) : this(id, args.toList())
    }

    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText

    /** Kernel data shown verbatim. */
    data class Raw(val text: String) : UiText

    fun resolve(resources: Resources): String = when (this) {
        is Res -> if (args.isEmpty()) resources.getString(id) else resources.getString(id, *args.map { it.resolveArg(resources) }.toTypedArray())
        is Plural -> quantityResources(resources).getQuantityString(id, count, *args.map { it.resolveArg(resources) }.toTypedArray())
        is Raw -> text
    }

    private fun Any.resolveArg(resources: Resources): Any = if (this is UiText) resolve(resources) else this
}

/**
 * The resources to pick a plural form with. Android chooses the quantity by the device
 * locale's rules even when the strings fell back to another language, so an English
 * `<plurals>` on a Chinese, Japanese or Korean device (one form, "other") read "1 active
 * sessions". The form is chosen by the language the copy is written in instead
 * (`kernel_copy_language`, set by each translation).
 */
fun quantityResources(resources: Resources): Resources {
    val written = Locale.forLanguageTag(resources.getString(R.string.kernel_copy_language))
    val config = resources.configuration
    if (config.locales.isEmpty || config.locales[0].language == written.language) return resources
    val pinned = Configuration(config).apply { setLocale(written) }
    @Suppress("DEPRECATION")
    return Resources(resources.assets, resources.displayMetrics, pinned)
}

/** Resolves [UiText] against the current configuration (recomposes on locale change). */
@Composable
fun UiText.resolve(): String = resolve(LocalResources.current)
