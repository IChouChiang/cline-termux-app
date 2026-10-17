package dev.cline.termux.data.prefs

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.cash.turbine.test
import dev.cline.termux.fakes.FakeClock
import dev.cline.termux.ui.theme.AppIcon
import dev.cline.termux.ui.theme.ThemeMode
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** DataStore on a temp dir (docs/07 §10.1 "Persistence"): migrations, typed accessors, caps. */
class AppPreferencesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val jobs = mutableListOf<Job>()

    @After
    fun tearDown() = runTest { jobs.forEach { it.cancelAndJoin() } }

    private fun store(name: String, migrations: List<DataMigration<Preferences>> = emptyList()): DataStore<Preferences> {
        val job = SupervisorJob().also(jobs::add)
        return PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            migrations = migrations,
            produceFile = { File(tmp.root, "$name.preferences_pb") },
        )
    }

    /** Writes legacy keys with a short-lived store, then closes it so the real one can open the file. */
    private suspend fun seed(name: String, block: suspend (DataStore<Preferences>) -> Unit) {
        val job = SupervisorJob()
        val legacy = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + job),
            produceFile = { File(tmp.root, "$name.preferences_pb") },
        )
        block(legacy)
        job.cancelAndJoin()
    }

    private fun prefs(
        app: DataStore<Preferences> = store("app", listOf(LegacyAppMigration(clock))),
        sessions: DataStore<Preferences>? = null,
    ): AppPreferences {
        val sessionsStore = sessions ?: store("sessions", listOf(LegacyCursorMigration(app)))
        val job = SupervisorJob().also(jobs::add)
        return AppPreferences(app, sessionsStore, CoroutineScope(Dispatchers.IO + job), clock)
    }

    @Test
    fun `client id is minted once and stays`() = runTest {
        val p = prefs()
        val first = p.clientId()
        assertTrue(first.startsWith("android-"))
        assertEquals(first, p.clientId())
        assertEquals(first, p.clientIdBlocking())
    }

    @Test
    fun `legacy app keys migrate into their successors and disappear`() = runTest {
        seed("app") { legacy ->
            legacy.edit {
                it[booleanPreferencesKey("onboarding.done")] = true
                it[booleanPreferencesKey("kernel.keepRunning")] = false
                it[stringPreferencesKey("model.provider")] = "anthropic"
                it[stringPreferencesKey("model.id")] = "claude-sonnet-5-5"
                it[stringPreferencesKey("workspace.last")] = "/data/data/com.termux/files/home/proj"
            }
        }
        val p = prefs()
        assertEquals(clock.millis(), p.onboardingCompletedAt())
        assertFalse(p.keepAwake())
        assertEquals(ModelSelection("anthropic", mapOf("anthropic" to "claude-sonnet-5-5")), p.modelSelection())
        assertEquals(WorkspaceChoice.Folder("/data/data/com.termux/files/home/proj"), p.workspace())
        assertEquals(listOf("/data/data/com.termux/files/home/proj"), p.workspaceRecents())
        assertEquals("/data/data/com.termux/files/home/proj", p.lastWorkspace())
        val raw = p.data.first().asMap().keys.map { it.name }
        for (gone in listOf("onboarding.done", "kernel.keepRunning", "model.provider", "model.id", "workspace.last")) {
            assertFalse(gone, gone in raw)
        }
    }

    @Test
    fun `legacy per-session cursors seed the one global cursor and are deleted`() = runTest {
        seed("app") { legacy ->
            legacy.edit {
                it[longPreferencesKey("hub.cursor.global")] = 120L
                it[longPreferencesKey("hub.cursor.sess_a")] = 345L
                it[longPreferencesKey("hub.cursor.sess_b")] = 200L
                it[stringPreferencesKey("client.id")] = "android-keep"
            }
        }
        val p = prefs()
        assertEquals(345L, p.globalCursor())
        val appKeys = p.data.first().asMap().keys.map { it.name }
        assertTrue(appKeys.none { it.startsWith("hub.cursor.") })
        assertEquals("android-keep", p.clientId())
    }

    @Test
    fun `global cursor is monotonic and the adapter flushes through it`() = runTest {
        val p = prefs()
        p.setGlobalCursor(50)
        p.setGlobalCursor(20)
        assertEquals(50L, p.globalCursor())
        val adapter = p.cursorStore()
        assertEquals(50L, adapter.get())
        adapter.put(80)
        adapter.put(70)
        assertEquals(80L, adapter.get())
        assertEquals(80L, p.globalCursor())
    }

    @Test
    fun `defaults match docs 07 section 7`() = runTest {
        val p = prefs()
        assertNull(p.onboardingCompletedAt())
        assertTrue(p.keepAwake())
        assertFalse(p.userStopped())
        assertFalse(p.wakeLockRequested())
        assertEquals(ThemeMode.System, p.appearance.first().themeMode)
        assertTrue(p.appearance.first().dynamicColor)
        assertEquals(15, p.appearance.first().fontSize)
        assertEquals(AppIcon.Midnight, p.appIcon())
        assertEquals(ModelSelection(), p.modelSelection())
        assertEquals(ComposerMode.Act, p.composerModeNow())
        assertEquals("[]", p.chatTurnsJson())
        assertEquals(WorkspaceChoice.Chat, p.workspace())
        assertNull(p.lastWorkspace())
        assertEquals(emptyMap<String, List<String>>(), p.favoriteModels())
        assertFalse(p.helperUnsupported())
        assertNull(p.signInPending())
        assertNull(p.notificationsJson())
        assertFalse(p.keepConnected())
        assertTrue(p.toolDetailWrapLines.first())
        assertNull(p.whatsNewSeenId())
        assertNull(p.globalCursor())
        assertEquals(SidebarSortMode.Time, p.sidebarSort.first())
    }

    @Test
    fun `per-session composer mode falls back to the global value`() = runTest {
        val p = prefs()
        p.setComposerMode(ComposerMode.Plan)
        assertEquals(ComposerMode.Plan, p.composerModeNow("s1"))
        p.setComposerMode(ComposerMode.Act, sessionId = "s1")
        assertEquals(ComposerMode.Act, p.composerModeNow("s1"))
        assertEquals(ComposerMode.Plan, p.composerModeNow("s2"))

        p.setComposerDraft("home", "hello")
        assertEquals("hello", p.composerDraft("home"))
        p.setComposerDraft("home", "")
        assertNull(p.composerDraft("home"))
    }

    @Test
    fun `model picks and reasoning levels`() = runTest {
        val p = prefs()
        p.recordModelPick("anthropic", "claude-sonnet-5-5")
        p.recordModelPick("openai", "gpt-5")
        p.modelSelection.test {
            val s = awaitItem()
            assertEquals("openai", s.lastProvider)
            assertEquals("claude-sonnet-5-5", s.modelFor("anthropic"))
            assertEquals("gpt-5", s.modelFor("openai"))
            cancelAndIgnoreRemainingEvents()
        }
        p.setReasoning("anthropic", "high")
        assertEquals(mapOf("anthropic" to "high"), p.reasoningByProvider())
        p.setReasoning("anthropic", null)
        assertEquals(emptyMap<String, String>(), p.reasoningByProvider())
    }

    @Test
    fun `workspace recents are an MRU of at most 20`() = runTest {
        val p = prefs()
        repeat(25) { p.setWorkspace(WorkspaceChoice.Folder("/w/$it")) }
        p.setWorkspace(WorkspaceChoice.Folder("/w/3"))
        val recents = p.workspaceRecents()
        assertEquals(20, recents.size)
        assertEquals("/w/3", recents.first())
        assertEquals(1, recents.count { it == "/w/3" })
        p.setWorkspace(WorkspaceChoice.Chat)
        assertEquals(WorkspaceChoice.Chat, p.workspace())
        assertEquals(20, p.workspaceRecents().size)
        p.removeWorkspaceRecent("/w/3")
        assertFalse("/w/3" in p.workspaceRecents())
    }

    @Test
    fun `favorites toggle per provider and drop empty providers`() = runTest {
        val p = prefs()
        p.setFavoriteModel("anthropic", "a", true)
        p.setFavoriteModel("anthropic", "b", true)
        p.setFavoriteModel("anthropic", "a", true)
        assertEquals(mapOf("anthropic" to listOf("b", "a")), p.favoriteModels())
        p.setFavoriteModel("anthropic", "a", false)
        p.setFavoriteModel("anthropic", "b", false)
        assertEquals(emptyMap<String, List<String>>(), p.favoriteModels())
    }

    @Test
    fun `notification memories are capped LRUs`() = runTest {
        val p = prefs()
        repeat(AppPreferences.MAX_SEEN_REQUEST_IDS + 5) { p.addSeenRequestId("req-$it") }
        val seen = p.seenRequestIds()
        assertEquals(AppPreferences.MAX_SEEN_REQUEST_IDS, seen.size)
        assertEquals("req-5", seen.first())
        assertEquals("req-${AppPreferences.MAX_SEEN_REQUEST_IDS + 4}", seen.last())

        p.addKnownSession("s1")
        p.addKnownSession("s2")
        p.addKnownSession("s1")
        assertEquals(listOf("s2", "s1"), p.knownSessionIds())
        p.removeKnownSession("s2")
        assertEquals(listOf("s1"), p.knownSessionIds())
        assertEquals(listOf("b", "c", "a"), AppPreferences.lruAdd(listOf("a", "b", "c"), "a", 3))
        assertEquals(listOf("c", "d"), AppPreferences.lruAdd(listOf("b", "c"), "d", 2))
    }

    @Test
    fun `sessions file holds unread, last seen and suspects, and forget clears a session`() = runTest {
        val p = prefs()
        p.setUnreadCounts(mapOf("a" to 2, "b" to 0))
        assertEquals(mapOf("a" to 2), p.unreadCounts())
        p.setLastSeen("a", 10)
        p.setLastSeen("a", 5)
        assertEquals(mapOf("a" to 10L), p.lastSeenSequences())
        p.setSuspectInterrupted(setOf("a", "c"))
        p.forgetSession("a")
        assertEquals(emptyMap<String, Int>(), p.unreadCounts())
        assertEquals(emptyMap<String, Long>(), p.lastSeenSequences())
        assertEquals(setOf("c"), p.suspectInterrupted())
        p.setSidebarSort(SidebarSortMode.Project)
        assertEquals(SidebarSortMode.Project, p.sidebarSort.first())
        p.setLastDisconnectAt()
        assertEquals(clock.millis(), p.lastDisconnectAt())
    }

    @Test
    fun `appearance, icon, reliability, account and misc keys round trip`() = runTest {
        val p = prefs()
        p.setThemeMode(ThemeMode.Dark)
        p.setDynamicColor(false)
        p.setFontSize(99)
        val a = p.appearance.first()
        assertEquals(ThemeMode.Dark, a.themeMode)
        assertFalse(a.dynamicColor)
        assertEquals(20, a.fontSize)
        p.setAppIcon(AppIcon.Classic)
        assertEquals(AppIcon.Classic, p.appIcon())
        p.setPendingIconDisable("dev.cline.termux.LauncherMidnight")
        assertEquals("dev.cline.termux.LauncherMidnight", p.pendingIconDisable())
        p.setPendingIconDisable(null)
        assertNull(p.pendingIconDisable())

        p.setReliabilityConfirmed(AppPreferences.ReliabilityCheck.Battery, true)
        assertEquals(setOf(AppPreferences.ReliabilityCheck.Battery), p.reliabilityConfirmed.first())
        assertFalse(p.isReliabilityConfirmed(AppPreferences.ReliabilityCheck.Phantom))

        p.setOnboardingCompleted()
        assertEquals(clock.millis(), p.onboardingCompletedAt.first())
        p.setOnboardingSignInStartedAt(123L)
        assertEquals(123L, p.onboardingSignInStartedAt())
        p.setOnboardingReliabilityShownAt()
        assertNotNull(p.onboardingReliabilityShownAt())

        p.setUserStopped(true)
        p.setKeepAwake(false)
        p.setWakeLockRequested(true)
        p.setHubLifetimeRelease("3.0.68-termux.2")
        assertTrue(p.userStopped.first())
        assertFalse(p.keepAwake.first())
        assertTrue(p.wakeLockRequested())
        assertEquals("3.0.68-termux.2", p.hubLifetimeRelease())

        p.setAccount("Ada", "org")
        assertEquals("Ada" to "org", p.account.first())
        p.setAccount(null, null)
        assertEquals(null to null, p.account.first())

        p.setSignInPending(SignInPending("cline", 42L))
        assertEquals(SignInPending("cline", 42L), p.signInPending.first())
        p.setHelperUnsupported(true)
        assertTrue(p.helperUnsupported())
        p.setNotificationsJson("""{"sound":false}""")
        assertEquals("""{"sound":false}""", p.notificationsJson())
        p.setKeepConnected(true)
        assertTrue(p.keepConnected())
        p.setToolDetailWrapLines(false)
        assertFalse(p.toolDetailWrapLines.first())
        p.setWhatsNewSeenId("2026-10")
        assertEquals("2026-10", p.whatsNewSeenId())
        p.setSchedulesKeepAliveNoticeDismissedFor("0.1.0")
        assertEquals("0.1.0", p.schedulesKeepAliveNoticeDismissedFor())
        p.setCustomizeWorkspaceRoot("/w")
        assertEquals("/w", p.customizeWorkspaceRoot())
        p.setChatTurnsJson("""[{"requestId":"r1"}]""")
        assertEquals("""[{"requestId":"r1"}]""", p.chatTurnsJson())
    }
}
