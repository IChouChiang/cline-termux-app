package dev.cline.termux.ui.navigation

import android.view.KeyEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure layout, transition, presentation, shortcut and secure-window rules of the shell. */
class ShellRulesTest {
    // ---- ShellLayout (DS §5.1, §5.2, CT A.3) ---------------------------------------------

    @Test
    fun `width classes break at 600 and 840`() {
        assertEquals(WidthClass.Compact, ShellLayout(599, 900).width)
        assertEquals(WidthClass.Medium, ShellLayout(600, 900).width)
        assertEquals(WidthClass.Medium, ShellLayout(839, 900).width)
        assertEquals(WidthClass.Expanded, ShellLayout(840, 900).width)
    }

    @Test
    fun `compact phone portrait uses the compact drawer and no rail`() {
        val l = ShellLayout.PhonePortrait
        assertFalse(l.showsRail)
        assertTrue(l.usesModalDrawer)
        assertEquals(DrawerVariant.CompactDrawer, l.drawerVariant)
        assertFalse(l.editorsAsDialogs)
        assertFalse(l.supportingPaneAvailable)
        assertTrue(l.heroVisible)
    }

    @Test
    fun `medium uses rail plus the medium drawer`() {
        val l = ShellLayout(700, 900)
        assertTrue(l.showsRail)
        assertTrue(l.usesModalDrawer)
        assertFalse(l.showsListPane)
        assertEquals(DrawerVariant.MediumDrawer, l.drawerVariant)
        assertFalse(l.editorsAsDialogs)
    }

    @Test
    fun `expanded shows the list pane and editor dialogs`() {
        val l = ShellLayout(1000, 800)
        assertTrue(l.showsRail)
        assertTrue(l.showsListPane)
        assertFalse(l.usesModalDrawer)
        assertTrue(l.editorsAsDialogs)
        assertEquals(360f, l.listPaneWidth.value)
        assertFalse(l.supportingPaneAvailable)
        val large = ShellLayout(1280, 800)
        assertEquals(400f, large.listPaneWidth.value)
        assertTrue(large.supportingPaneAvailable)
    }

    @Test
    fun `height compact drops the list pane, hero, supporting pane and editor dialogs`() {
        val landscape = ShellLayout(915, 412)
        assertTrue(landscape.heightCompact)
        assertEquals(WidthClass.Expanded, landscape.width)
        assertTrue(landscape.showsRail)
        assertFalse(landscape.showsListPane)
        assertTrue(landscape.usesModalDrawer)
        assertEquals(DrawerVariant.MediumDrawer, landscape.drawerVariant)
        assertFalse(landscape.heroVisible)
        assertFalse(landscape.editorsAsDialogs)
        assertFalse(ShellLayout(1400, 479).supportingPaneAvailable)
        assertFalse(ShellLayout(1400, 480).heightCompact)
    }

    @Test
    fun `list-detail screens use one pane on a phone in landscape`() {
        val landscape = ShellLayout(891, 412)
        assertEquals(WidthClass.Expanded, landscape.width)
        assertTrue(landscape.heightCompact)
        assertFalse(landscape.listDetailPanes)
        assertFalse(landscape.showsListPane)
        assertTrue(ShellLayout(1000, 800).listDetailPanes)
        assertTrue(ShellLayout(840, 480).listDetailPanes)
        assertFalse(ShellLayout(839, 900).listDetailPanes)
        assertFalse(ShellLayout.PhonePortrait.listDetailPanes)
    }

    // ---- Transitions (DS §6.3) -------------------------------------------------------------

    @Test
    fun `top level swaps fade through, pushes slide on x, editors on y`() {
        assertEquals(TransitionKind.FadeThrough, NavTransitions.shellKind(RouteKind.TopLevel, RouteKind.TopLevel))
        assertEquals(TransitionKind.SharedAxisX, NavTransitions.shellKind(RouteKind.TopLevel, RouteKind.Pushed))
        assertEquals(TransitionKind.SharedAxisX, NavTransitions.shellKind(RouteKind.Pushed, RouteKind.TopLevel))
        assertEquals(TransitionKind.SharedAxisY, NavTransitions.shellKind(RouteKind.Pushed, RouteKind.Editor))
        assertEquals(TransitionKind.SharedAxisY, NavTransitions.shellKind(RouteKind.Editor, RouteKind.Pushed))
        assertEquals(TransitionKind.SharedAxisX, NavTransitions.shellKind(null, RouteKind.Pushed))
    }

    @Test
    fun `root replacements fade through, screens pushed over the shell slide`() {
        assertEquals(TransitionKind.FadeThrough, NavTransitions.rootKind(Shell, KernelInstallTermux))
        assertEquals(TransitionKind.FadeThrough, NavTransitions.rootKind(KernelRepair(), Shell))
        assertEquals(TransitionKind.SharedAxisX, NavTransitions.rootKind(Shell, KernelRepair(pushed = true)))
        assertEquals(TransitionKind.SharedAxisX, NavTransitions.rootKind(KernelReliability, Shell))
        assertEquals(TransitionKind.SharedAxisX, NavTransitions.rootKind(OnboardingWelcome, OnboardingKernel))
    }

    @Test
    fun `slide offsets keep the direction of the full offset`() {
        assertEquals(-30, NavTransitions.signed(-1080, 30))
        assertEquals(30, NavTransitions.signed(1080, 30))
    }

    // ---- Editor presentation latch (CR §0.2) ----------------------------------------------

    @Test
    fun `editor presentation only changes while no editor is open`() {
        assertTrue(EditorPresentation.effective(wanted = true, latched = false, editorOpen = false))
        assertFalse(EditorPresentation.effective(wanted = true, latched = false, editorOpen = true))
        assertTrue(EditorPresentation.effective(wanted = false, latched = true, editorOpen = true))
        assertFalse(EditorPresentation.effective(wanted = false, latched = true, editorOpen = false))
    }

    // ---- Hardware keyboard (DS §5.7) -------------------------------------------------------

    private fun key(code: Int, ctrl: Boolean = true, alt: Boolean = false, shift: Boolean = false, action: Int = KeyEvent.ACTION_DOWN, repeat: Int = 0) =
        ShellShortcuts.match(action, code, ctrl, alt, shift, meta = false, repeatCount = repeat)

    @Test
    fun `ctrl k and ctrl p search, ctrl n starts a session`() {
        assertEquals(ShellShortcut.Search, key(KeyEvent.KEYCODE_K))
        assertEquals(ShellShortcut.Search, key(KeyEvent.KEYCODE_P))
        assertEquals(ShellShortcut.NewSession, key(KeyEvent.KEYCODE_N))
    }

    @Test
    fun `other chords, key ups and repeats are left alone`() {
        assertNull(key(KeyEvent.KEYCODE_K, ctrl = false))
        assertNull(key(KeyEvent.KEYCODE_K, shift = true))
        assertNull(key(KeyEvent.KEYCODE_K, alt = true))
        assertNull(key(KeyEvent.KEYCODE_K, action = KeyEvent.ACTION_UP))
        assertNull(key(KeyEvent.KEYCODE_K, repeat = 1))
        assertNull(key(KeyEvent.KEYCODE_ENTER))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the shortcut bus only consumes keys while a shell listens`() = runTest {
        val bus = ShortcutBus()
        assertFalse(bus.offer(ShellShortcut.Search))
        val seen = mutableListOf<ShellShortcut>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { bus.shortcuts.collect { seen += it } }
        assertTrue(bus.offer(ShellShortcut.NewSession))
        assertEquals(listOf(ShellShortcut.NewSession), seen)
        job.cancel()
        assertFalse(bus.offer(ShellShortcut.Search))
    }

    // ---- FLAG_SECURE (docs/07 §3.7) --------------------------------------------------------

    @Test
    fun `the secure flag is reference counted by owner`() {
        val applied = mutableListOf<Boolean>()
        val holder = SecureFlagHolder { applied += it }
        val a = Any()
        val b = Any()
        holder.hold(a)
        holder.hold(a)
        holder.hold(b)
        assertTrue(holder.held)
        holder.release(a)
        assertTrue(holder.held)
        holder.release(b)
        holder.release(b)
        assertFalse(holder.held)
        assertEquals(listOf(true, false), applied)
    }
}
