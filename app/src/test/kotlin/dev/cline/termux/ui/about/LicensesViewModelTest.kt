package dev.cline.termux.ui.about

import dev.cline.termux.fakes.FakeLicenseSource
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LicensesViewModelTest {
    private val main = UnconfinedTestDispatcher()

    private val geist = LicenseEntry("Geist Mono", "1.4.01", "OFL-1.1", "https://github.com/vercel/geist-font", "geist-mono-OFL.txt", listOf("font:geist-mono"))
    private val symbols = LicenseEntry("Material Symbols", "4.0.0", "Apache-2.0", "https://github.com/google/material-design-icons", "material-symbols-APACHE.txt")

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Every value [flow] emits from now on, collected in the background. */
    private fun <T> TestScope.record(flow: StateFlow<T>): List<T> {
        val seen = mutableListOf<T>()
        backgroundScope.launch(main) { flow.collect { seen += it } }
        return seen
    }

    @Test
    fun `index goes from loading to loaded`() = runTest(main) {
        val source = FakeLicenseSource(indexAnswer = { listOf(geist, symbols) })
        val vm = LicensesViewModel(source, file = null)
        val entries = record(vm.entries)
        assertEquals(listOf<List<LicenseEntry>?>(null), entries)

        source.release.complete(Unit)

        assertEquals(listOf(null, listOf(geist, symbols)), entries)
        assertEquals(1, source.indexReads)
        assertTrue(source.textReads.isEmpty())
        assertEquals("", vm.text.value)
    }

    @Test
    fun `an unreadable index goes from loading to an empty list`() = runTest(main) {
        val source = FakeLicenseSource(indexAnswer = { throw SerializationException("Unexpected JSON token at offset 0") })
        val vm = LicensesViewModel(source, file = null)
        val entries = record(vm.entries)
        assertEquals(listOf<List<LicenseEntry>?>(null), entries)

        source.release.complete(Unit)

        assertEquals(listOf(null, emptyList<LicenseEntry>()), entries)
    }

    @Test
    fun `a licence text goes from loading to loaded`() = runTest(main) {
        val source = FakeLicenseSource(texts = mapOf("geist-mono-OFL.txt" to "SIL OPEN FONT LICENSE Version 1.1"))
        val vm = LicensesViewModel(source, file = "geist-mono-OFL.txt")
        val text = record(vm.text)
        assertEquals(listOf<String?>(null), text)

        source.release.complete(Unit)

        assertEquals(listOf(null, "SIL OPEN FONT LICENSE Version 1.1"), text)
        assertEquals(listOf("geist-mono-OFL.txt"), source.textReads)
        assertEquals(0, source.indexReads)
        assertEquals(emptyList<LicenseEntry>(), vm.entries.value)
    }

    @Test
    fun `a missing licence text goes from loading to empty`() = runTest(main) {
        val source = FakeLicenseSource()
        val vm = LicensesViewModel(source, file = "absent.txt")
        val text = record(vm.text)
        assertEquals(listOf<String?>(null), text)

        source.release.complete(Unit)

        assertEquals(listOf(null, ""), text)
    }

    @Test
    fun `an unreadable licence text goes from loading to empty`() = runTest(main) {
        val source = FakeLicenseSource(texts = mapOf("x.txt" to "text"), textFailure = IOException("EIO"))
        val vm = LicensesViewModel(source, file = "x.txt")
        val text = record(vm.text)
        assertEquals(listOf<String?>(null), text)

        source.release.complete(Unit)

        assertEquals(listOf(null, ""), text)
    }
}
