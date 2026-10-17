package dev.cline.termux.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cline.termux.R
import dev.cline.termux.ui.theme.Spacing

/**
 * The one small indeterminate spinner of the app: 16dp, 2dp stroke, drawn in
 * [LocalContentColor]. Inside a button that is the button's content colour, so a disabled
 * button (the usual state while its action runs, DS §4.1 #16 "confirming") supplies its own
 * 38 % disabled content colour and the spinner stays readable on the 12 % disabled container;
 * a destructive button supplies `error`; a list row supplies its trailing/leading colour.
 *
 * A caller that needs another size passes it first in [modifier] (the outer `size` wins).
 * Status spinners that carry a DS §1.6 colour (running sessions, runs) keep using
 * [SessionStatusIcon] instead.
 */
@Composable
fun InlineSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(
        modifier = modifier.size(InlineSpinnerSize),
        color = LocalContentColor.current,
        strokeWidth = InlineSpinnerStroke,
    )
}

private val InlineSpinnerSize = 16.dp
private val InlineSpinnerStroke = 2.dp

// ---- previews --------------------------------------------------------------------------------

@Composable
private fun SpinnerButtonsPreviewContent(enabled: Boolean) {
    val busy = stringResource(R.string.components_status_deleting)
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Button(onClick = {}, enabled = enabled) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
            Text(busy)
        }
        FilledTonalButton(onClick = {}, enabled = enabled) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
            Text(busy)
        }
        OutlinedButton(
            onClick = {},
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
            Text(busy)
        }
        TextButton(onClick = {}, enabled = enabled) {
            InlineSpinner()
            Spacer(Modifier.width(Spacing.s))
            Text(busy)
        }
    }
}

/** Enabled buttons: the spinner takes each button's content colour (onPrimary, error, primary). */
@LightDarkPreviews
@Composable
private fun InlineSpinnerEnabledButtonsPreview() {
    ComponentPreview { SpinnerButtonsPreviewContent(enabled = true) }
}

/** Disabled buttons (the confirming state): the spinner is the 38 % disabled content colour. */
@LightDarkPreviews
@Composable
private fun InlineSpinnerDisabledButtonsPreview() {
    ComponentPreview { SpinnerButtonsPreviewContent(enabled = false) }
}

/** On its own: `onSurface` from the preview surface. */
@LightDarkPreviews
@Composable
private fun InlineSpinnerStandalonePreview() {
    ComponentPreview { InlineSpinner() }
}
