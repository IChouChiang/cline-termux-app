package dev.cline.termux.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.cline.termux.ui.theme.ClinePreviewTheme

/** Every component preview renders twice: light and dark, brand scheme (DS §1.2 rule 2). */
@Preview(name = "Light", showBackground = false)
@Preview(name = "Dark", showBackground = false, uiMode = Configuration.UI_MODE_NIGHT_YES or Configuration.UI_MODE_TYPE_NORMAL)
annotation class LightDarkPreviews

/** Preview host: the brand theme (light or dark from the preview's uiMode) on a `surface`. */
@Composable
fun ComponentPreview(padding: Boolean = true, content: @Composable () -> Unit) {
    ClinePreviewTheme {
        Surface {
            androidx.compose.foundation.layout.Box(Modifier.padding(if (padding) 16.dp else 0.dp)) { content() }
        }
    }
}
