package dev.cline.termux.ui.settings

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import dev.cline.termux.data.notifications.NotificationEventType
import dev.cline.termux.data.notifications.NotificationPreference
import dev.cline.termux.data.notifications.NotificationSettings
import dev.cline.termux.service.NotificationChannels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NotificationSettingsViewModelTest {
    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSources : NotificationSettingsSources {
        override val settings = MutableStateFlow(NotificationSettings.DEFAULT)
        override val keepAliveDenied = MutableStateFlow(false)
        override val keepConnected = MutableStateFlow(false)
        override suspend fun setKeepConnected(enabled: Boolean) {
            keepConnected.value = enabled
        }
        override var sdkInt = 34
        var appEnabled = true
        var granted = false
        val blockedChannels = mutableSetOf<String>()
        val writes = mutableListOf<Triple<String, NotificationEventType, Boolean>>()

        override suspend fun setEnabled(type: NotificationEventType, enabled: Boolean) {
            writes += Triple("enabled", type, enabled)
        }
        override suspend fun setSound(type: NotificationEventType, sound: Boolean) {
            writes += Triple("sound", type, sound)
        }
        override fun appNotificationsEnabled() = appEnabled
        override fun postPermissionGranted() = granted
        override fun isChannelBlocked(channelId: String) = channelId in blockedChannels
    }

    private val sources = FakeSources()

    private fun TestScope.viewModel(handle: SavedStateHandle = SavedStateHandle()): NotificationSettingsViewModel {
        val vm = NotificationSettingsViewModel(handle, sources)
        backgroundScope.launch { vm.uiState.collect {} }
        return vm
    }

    @Test
    fun `permission rules cover every API level and answer`() {
        val rules = NotificationPermissionRules
        assertEquals(NotificationPermission.NotRequired, rules.derive(32, appEnabled = true, granted = false, requestedBefore = false, rationale = false))
        assertEquals(NotificationPermission.Denied, rules.derive(32, appEnabled = false, granted = true, requestedBefore = false, rationale = false))
        assertEquals(NotificationPermission.Granted, rules.derive(33, appEnabled = true, granted = true, requestedBefore = true, rationale = false))
        assertEquals(NotificationPermission.Denied, rules.derive(33, appEnabled = false, granted = true, requestedBefore = false, rationale = false))
        assertEquals(NotificationPermission.NotAsked, rules.derive(33, appEnabled = true, granted = false, requestedBefore = false, rationale = false))
        assertEquals(NotificationPermission.NotAsked, rules.derive(33, appEnabled = true, granted = false, requestedBefore = true, rationale = true))
        assertEquals(NotificationPermission.Denied, rules.derive(33, appEnabled = true, granted = false, requestedBefore = true, rationale = false))
    }

    @Test
    fun `ui state flags follow the permission`() {
        assertTrue(NotificationSettingsUiState().showsPermissionCard)
        assertTrue(NotificationSettingsUiState(permission = NotificationPermission.Denied).showsPermissionCard)
        assertFalse(NotificationSettingsUiState(permission = NotificationPermission.Granted).showsPermissionCard)
        assertTrue(NotificationSettingsUiState(permission = NotificationPermission.NotRequired).allowedBySystem)
        assertFalse(NotificationSettingsUiState(permission = NotificationPermission.NotAsked).allowedBySystem)
    }

    @Test
    fun `the background connection switch writes keepConnected`() = runTest(main) {
        val vm = viewModel()
        assertFalse(vm.uiState.value.keepConnected)
        vm.setKeepConnected(true)
        assertTrue(sources.keepConnected.value)
        assertTrue(vm.uiState.value.keepConnected)
    }

    @Test
    fun `checking until the first resume, then the system answer`() = runTest(main) {
        val vm = viewModel()
        assertEquals(NotificationPermission.Checking, vm.uiState.value.permission)
        vm.onResume(rationale = false)
        assertEquals(NotificationPermission.NotAsked, vm.uiState.value.permission)
        sources.granted = true
        vm.onResume(rationale = false)
        assertEquals(NotificationPermission.Granted, vm.uiState.value.permission)
    }

    @Test
    fun `a denied request without rationale is permanent and survives in saved state`() = runTest(main) {
        val handle = SavedStateHandle()
        val vm = viewModel(handle)
        vm.onResume(rationale = false)
        vm.events.test {
            vm.requestPermission()
            assertEquals(NotificationSettingsEvent.RequestPermission, awaitItem())
            assertTrue(vm.uiState.value.requesting)
            vm.requestPermission()
            expectNoEvents()
        }
        vm.onPermissionResult(granted = false, rationale = false)
        assertFalse(vm.uiState.value.requesting)
        assertEquals(NotificationPermission.Denied, vm.uiState.value.permission)

        val restored = viewModel(handle)
        restored.onResume(rationale = false)
        assertEquals(NotificationPermission.Denied, restored.uiState.value.permission)
    }

    @Test
    fun `turning an event on without permission asks once`() = runTest(main) {
        val vm = viewModel()
        vm.onResume(rationale = false)
        vm.events.test {
            vm.setEnabled(NotificationEventType.QuestionAsked, true)
            assertEquals(NotificationSettingsEvent.RequestPermission, awaitItem())
            vm.onPermissionResult(granted = false, rationale = true)
            vm.setEnabled(NotificationEventType.QuestionAsked, true)
            vm.setEnabled(NotificationEventType.SessionError, false)
            expectNoEvents()
        }
        assertEquals(
            listOf(
                Triple("enabled", NotificationEventType.QuestionAsked, true),
                Triple("enabled", NotificationEventType.QuestionAsked, true),
                Triple("enabled", NotificationEventType.SessionError, false),
            ),
            sources.writes,
        )
    }

    @Test
    fun `channel blocking concerns the active channel and is re-read on resume`() = runTest(main) {
        sources.granted = true
        sources.settings.value = NotificationSettings(taskCompletion = NotificationPreference(enabled = true, sound = true))
        val sounding = NotificationChannels.channelFor(NotificationEventType.TaskCompletion, sound = true)
        val vm = viewModel()
        vm.onResume(rationale = false)
        assertFalse(vm.uiState.value.events.first().channelBlocked)

        sources.blockedChannels += sounding
        vm.onResume(rationale = false)
        val event = vm.uiState.value.events.first()
        assertEquals(NotificationEventType.TaskCompletion, event.type)
        assertTrue(event.channelBlocked)
        assertEquals(sounding, event.activeChannel)

        vm.setSound(NotificationEventType.TaskCompletion, false)
        sources.settings.value = NotificationSettings(taskCompletion = NotificationPreference(enabled = true, sound = false))
        assertFalse(vm.uiState.value.events.first().channelBlocked)
        assertEquals(Triple("sound", NotificationEventType.TaskCompletion, false), sources.writes.last())
    }

    @Test
    fun `keep-alive denial and event order reach the state`() = runTest(main) {
        val vm = viewModel()
        sources.keepAliveDenied.value = true
        assertTrue(vm.uiState.value.keepAliveDenied)
        assertEquals(NotificationEventType.entries.toList(), vm.uiState.value.events.map { it.type })
    }
}
