package dev.cline.termux.ui.settings

import app.cash.turbine.test
import dev.cline.termux.ClineApp
import dev.cline.termux.ui.theme.AppIcon
import dev.cline.termux.ui.theme.AppearancePrefs
import dev.cline.termux.ui.theme.FontSizeSetting
import dev.cline.termux.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppearanceViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStore : AppearanceStore {
        override val appearance = MutableStateFlow(AppearancePrefs())
        override val appIcon = MutableStateFlow(AppIcon.Midnight)
        val fontWrites = mutableListOf<Int>()
        val pendingDisable = mutableListOf<String?>()

        override suspend fun setThemeMode(mode: ThemeMode) = appearance.update { it.copy(themeMode = mode) }
        override suspend fun setDynamicColor(enabled: Boolean) = appearance.update { it.copy(dynamicColor = enabled) }
        override suspend fun setFontSize(size: Int) {
            fontWrites += size
            appearance.update { it.copy(fontSize = size) }
        }
        override suspend fun setAppIcon(icon: AppIcon) {
            appIcon.value = icon
        }
        override suspend fun setPendingIconDisable(component: String?) {
            pendingDisable += component
        }
    }

    private class FakeIcons : LauncherIcons {
        var failure: Exception? = null
        val enabled = mutableListOf<AppIcon>()
        var retire: (() -> Unit)? = null
        var chosen: (() -> AppIcon)? = null

        override fun enable(icon: AppIcon) {
            failure?.let { throw it }
            enabled += icon
        }

        override fun scheduleRetire(chosen: () -> AppIcon, onRetired: () -> Unit) {
            this.chosen = chosen
            retire = onRetired
        }
    }

    private val store = FakeStore()
    private val icons = FakeIcons()

    private fun TestScope.viewModel(dynamicSupported: Boolean = true): AppearanceViewModel {
        val vm = AppearanceViewModel(store, icons, dynamicSupported, appScope = backgroundScope)
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `theme and dynamic colour write through`() = runTest(main) {
        val vm = viewModel()
        vm.setThemeMode(ThemeMode.Dark)
        vm.setDynamicColor(false)
        assertEquals(ThemeMode.Dark, vm.uiState.value.themeMode)
        assertFalse(vm.uiState.value.dynamicColor)
    }

    @Test
    fun `dynamic colour is ignored below API 31`() = runTest(main) {
        val vm = viewModel(dynamicSupported = false)
        vm.setDynamicColor(false)
        assertTrue(store.appearance.value.dynamicColor)
        assertFalse(vm.uiState.value.dynamicSupported)
    }

    @Test
    fun `dragging previews without persisting until release`() = runTest(main) {
        val vm = viewModel()
        vm.onFontSizeDrag(18)
        assertEquals(18, vm.uiState.value.previewFontSize)
        assertEquals(FontSizeSetting.DEFAULT, vm.uiState.value.fontSize)
        assertTrue(store.fontWrites.isEmpty())

        vm.onFontSizeDrag(99)
        assertEquals(FontSizeSetting.MAX, vm.uiState.value.previewFontSize)
        vm.onFontSizeDragFinished()
        assertEquals(listOf(FontSizeSetting.MAX), store.fontWrites)
        assertEquals(FontSizeSetting.MAX, vm.uiState.value.fontSize)
        assertEquals(FontSizeSetting.MAX, vm.uiState.value.previewFontSize)

        vm.onFontSizeDragFinished()
        assertEquals(1, store.fontWrites.size)
    }

    @Test
    fun `stepper persists each tap and clamps at the bounds`() = runTest(main) {
        val vm = viewModel()
        vm.stepFontSize(-1)
        assertEquals(listOf(14), store.fontWrites)
        assertEquals(14, vm.uiState.value.fontSize)

        store.appearance.update { it.copy(fontSize = FontSizeSetting.MAX) }
        assertFalse(vm.uiState.value.canIncrease)
        assertTrue(vm.uiState.value.canDecrease)
        vm.stepFontSize(1)
        assertEquals(FontSizeSetting.MAX, store.fontWrites.last())

        store.appearance.update { it.copy(fontSize = FontSizeSetting.MIN) }
        vm.stepFontSize(-1)
        assertEquals(FontSizeSetting.MIN, vm.uiState.value.fontSize)
        assertFalse(vm.uiState.value.canDecrease)
    }

    @Test
    fun `switching the icon enables the new alias and retires the old one later`() = runTest(main) {
        val vm = viewModel()
        vm.events.test {
            vm.setAppIcon(AppIcon.Classic)
            assertEquals(AppearanceEvent.IconSwitched, awaitItem())
        }
        assertEquals(listOf(AppIcon.Classic), icons.enabled)
        assertEquals(AppIcon.Classic, vm.uiState.value.appIcon)
        assertEquals(listOf<String?>(ClineApp.launcherAlias(AppIcon.Midnight)), store.pendingDisable)
        assertEquals(AppIcon.Classic, icons.chosen!!())
        assertNull(vm.uiState.value.iconError)

        icons.retire!!()
        assertEquals(listOf(ClineApp.launcherAlias(AppIcon.Midnight), null), store.pendingDisable)
    }

    @Test
    fun `choosing the current icon does nothing`() = runTest(main) {
        val vm = viewModel()
        vm.setAppIcon(AppIcon.Midnight)
        assertTrue(icons.enabled.isEmpty())
        assertTrue(store.pendingDisable.isEmpty())
    }

    @Test
    fun `a failed switch rolls back with the error`() = runTest(main) {
        icons.failure = SecurityException("component not found")
        val vm = viewModel()
        vm.setAppIcon(AppIcon.Classic)
        assertEquals(AppIcon.Midnight, vm.uiState.value.appIcon)
        assertEquals("component not found", vm.uiState.value.iconError)
        assertEquals(AppIcon.Midnight, store.appIcon.value)
        assertTrue(store.pendingDisable.isEmpty())

        icons.failure = null
        vm.setAppIcon(AppIcon.Classic)
        assertNull(vm.uiState.value.iconError)
        assertEquals(AppIcon.Classic, vm.uiState.value.appIcon)
    }
}
