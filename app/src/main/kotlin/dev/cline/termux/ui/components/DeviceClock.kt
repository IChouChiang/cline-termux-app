package dev.cline.termux.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.cline.termux.data.schedules.is24HourClock

/**
 * The user's "Use 24-hour format" setting for the time-of-day formatters (`ClockFormat`): read
 * here once and passed down as a Boolean. Changing the setting is not a configuration change, so
 * it is read again on every resume (the user comes back from Settings), and whenever the
 * configuration changes (a new locale moves the default clock).
 */
@Composable
fun rememberIs24HourClock(): Boolean {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var is24Hour by remember(context, configuration) { mutableStateOf(is24HourClock(context)) }
    LifecycleResumeEffect(context, configuration) {
        is24Hour = is24HourClock(context)
        onPauseOrDispose { }
    }
    return is24Hour
}
