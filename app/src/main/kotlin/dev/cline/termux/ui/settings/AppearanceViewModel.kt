package dev.cline.termux.ui.settings

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cline.termux.ClineApp
import dev.cline.termux.ui.theme.AppIcon
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.FontSizeSetting
import dev.cline.termux.ui.theme.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** DataStore `appearance.*` as the Appearance page sees it ([GraphAppearanceStore] in the app). */
interface AppearanceStore {
    val appearance: Flow<AppearancePrefs>
    val appIcon: Flow<AppIcon>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setDynamicColor(enabled: Boolean)
    suspend fun setFontSize(size: Int)
    suspend fun setAppIcon(icon: AppIcon)
    suspend fun setPendingIconDisable(component: String?)
}

/** S2 page state. [previewFontSize] follows the slider live; [fontSize] is what is stored. */
@Immutable
data class AppearanceUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val dynamicColor: Boolean = true,
    /** API 31+; below it the Dynamic colour row is hidden (DS §1.2.2). */
    val dynamicSupported: Boolean = true,
    val fontSize: Int = FontSizeSetting.DEFAULT,
    val previewFontSize: Int = FontSizeSetting.DEFAULT,
    val appIcon: AppIcon = AppIcon.Midnight,
    /** "Failed to change app icon: <message>" under the tiles (S2.2). */
    val iconError: String? = null,
) {
    val canDecrease: Boolean get() = previewFontSize > FontSizeSetting.MIN
    val canIncrease: Boolean get() = previewFontSize < FontSizeSetting.MAX
}

/** One-shot effects the screen turns into snackbars. */
sealed interface AppearanceEvent {
    /** "The launcher may take a moment to update the icon." */
    data object IconSwitched : AppearanceEvent
}

/**
 * Appearance (S2): theme mode, dynamic colour, font size with a live preview, and the
 * launcher-icon alias. Everything is app-local DataStore and applies without a restart
 * (`ClineTheme` observes the same keys).
 */
class AppearanceViewModel(
    private val store: AppearanceStore,
    private val icons: LauncherIcons,
    private val dynamicSupported: Boolean,
    /** Outlives the ViewModel: the `ON_STOP` retire may fire after the page is gone. */
    private val appScope: CoroutineScope,
) : ViewModel() {

    /** Slider value while dragging (null when not dragging): the preview follows it, DataStore does not. */
    private val dragging = MutableStateFlow<Int?>(null)

    /** The tile chosen before DataStore confirms it (rolled back on failure). */
    private val pendingIcon = MutableStateFlow<AppIcon?>(null)
    private val iconError = MutableStateFlow<String?>(null)

    private val _events = Channel<AppearanceEvent>(Channel.BUFFERED)
    val events: Flow<AppearanceEvent> = _events.receiveAsFlow()

    private val appearance: StateFlow<AppearancePrefs> = store.appearance.stateIn(viewModelScope, SharingStarted.Eagerly, AppearancePrefs())
    private val storedIcon: StateFlow<AppIcon> = store.appIcon.stateIn(viewModelScope, SharingStarted.Eagerly, AppIcon.Midnight)

    val uiState: StateFlow<AppearanceUiState> = combine(
        appearance,
        storedIcon,
        dragging,
        pendingIcon,
        iconError,
    ) { appearance, icon, drag, pending, error ->
        AppearanceUiState(
            themeMode = appearance.themeMode,
            dynamicColor = appearance.dynamicColor,
            dynamicSupported = dynamicSupported,
            fontSize = appearance.fontSize,
            previewFontSize = drag ?: appearance.fontSize,
            appIcon = pending ?: icon,
            iconError = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppearanceUiState(dynamicSupported = dynamicSupported))

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { store.setThemeMode(mode) }
    }

    fun setDynamicColor(enabled: Boolean) {
        if (!dynamicSupported) return
        viewModelScope.launch { store.setDynamicColor(enabled) }
    }

    /** Slider `onValueChange`: the preview updates on every tick; nothing is written yet. */
    fun onFontSizeDrag(size: Int) {
        dragging.value = FontSizeSetting.clamp(size)
    }

    /** Slider `onValueChangeFinished`: the value is persisted on release (S2.2). */
    fun onFontSizeDragFinished() {
        val size = dragging.value ?: return
        viewModelScope.launch {
            store.setFontSize(size)
            dragging.update { if (it == size) null else it }
        }
    }

    /** Stepper buttons: persist on each tap. */
    fun stepFontSize(delta: Int) {
        val next = FontSizeSetting.clamp((dragging.value ?: appearance.value.fontSize) + delta)
        dragging.value = next
        viewModelScope.launch {
            store.setFontSize(next)
            dragging.update { if (it == next) null else it }
        }
    }

    /**
     * App icon (S2.2): enable the new alias at once, store the choice and the alias to
     * retire, and arm the `ON_STOP` retire; the old alias is never disabled under the live
     * task. A failure rolls the selection back with the error under the tiles.
     */
    fun setAppIcon(icon: AppIcon) {
        if (pendingIcon.value != null || storedIcon.value == icon) return
        val previous = storedIcon.value
        pendingIcon.value = icon
        iconError.value = null
        viewModelScope.launch {
            try {
                icons.enable(icon)
                store.setAppIcon(icon)
                store.setPendingIconDisable(ClineApp.launcherAlias(previous))
                icons.scheduleRetire(chosen = { icon }, onRetired = { appScope.launch { store.setPendingIconDisable(null) } })
                _events.trySend(AppearanceEvent.IconSwitched)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                iconError.value = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            } finally {
                pendingIcon.value = null
            }
        }
    }
}
