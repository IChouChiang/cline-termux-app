package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import dev.cline.termux.R
import dev.cline.termux.ui.icons.ClineIcons
import dev.cline.termux.ui.theme.Spacing

/**
 * The app's one search input (sessions, marketplace, Customize tabs, providers, provider
 * models, model picker, workspace pickers, branch sheet): a pill `OutlinedTextField`
 * (`shapes.extraLarge`) with the leading search icon, a trailing clear button while
 * non-empty, autocorrect off and a single line.
 *
 * The IME key is [imeAction] (Search by default). Pressing it runs [onImeAction] when given
 * (open a typed path, pick the only match); otherwise it hides the keyboard and clears focus,
 * keeping the query. [contentDescription] names the field for TalkBack where the screen has its
 * own label (the desktop `aria-label`); without it the placeholder is read.
 */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val runImeAction = {
        if (onImeAction != null) {
            onImeAction()
        } else {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(ClineIcons.Search, contentDescription = null) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }, enabled = enabled, modifier = Modifier.focusRing(CircleShape)) {
                    Icon(ClineIcons.Close, contentDescription = clearContentDescription)
                }
            }
        } else {
            null
        },
        shape = MaterialTheme.shapes.extraLarge,
        keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(
            onSearch = { runImeAction() },
            onGo = { runImeAction() },
            onDone = { runImeAction() },
        ),
        modifier = modifier
            .fillMaxWidth()
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    )
}

@LightDarkPreviews
@Composable
private fun SearchFieldEmptyPreview() {
    ComponentPreview {
        SearchField("", {}, placeholder = "Search sessions", clearContentDescription = stringResource(R.string.components_clear_search))
    }
}

@LightDarkPreviews
@Composable
private fun SearchFieldFilledPreview() {
    ComponentPreview {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            SearchField("refactor", {}, placeholder = "Search sessions", clearContentDescription = stringResource(R.string.components_clear_search))
            SearchField(
                "openrouter",
                {},
                placeholder = "Search providers...",
                clearContentDescription = stringResource(R.string.components_clear_search),
                enabled = false,
            )
        }
    }
}
