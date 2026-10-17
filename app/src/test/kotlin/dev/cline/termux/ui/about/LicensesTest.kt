package dev.cline.termux.ui.about

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hand-maintained licence list (CR OQ C-11, no licence plugin): every runtime dependency in
 * `app/build.gradle.kts` must be covered by an entry, and every entry's text must be bundled.
 */
class LicensesTest {
    private val moduleDir: File = listOf(File("."), File("app")).first { File(it, "build.gradle.kts").isFile && File(it, "src/main/assets").isDirectory }
    private val assets = File(moduleDir, "src/main/assets/${Licenses.DIR}")
    private val entries = Licenses.parse(File(assets, "index.json").readText())

    @Test
    fun `index parses and names the bundled fonts and icons`() {
        assertTrue(entries.size >= 5)
        val names = entries.map { it.name }
        assertTrue(names.any { it.startsWith("Geist Mono") })
        assertTrue(names.any { it.startsWith("Material Symbols") })
        assertEquals("OFL-1.1", entries.first { it.name.startsWith("Geist Mono") }.license)
        assertEquals("Apache-2.0", entries.first { it.name.startsWith("Material Symbols") }.license)
        assertEquals(entries.size, names.toSet().size)
    }

    @Test
    fun `every licence text is bundled and non-empty`() {
        entries.forEach { entry ->
            assertFalse("${entry.name}: ${entry.file}", entry.file.contains('/') || entry.file.contains(".."))
            val file = File(assets, entry.file)
            assertTrue("${entry.name}: missing ${entry.file}", file.isFile && file.length() > 0)
        }
    }

    @Test
    fun `every runtime dependency is covered`() {
        val gradle = File(moduleDir, "build.gradle.kts").readText()
        val coordinates = Regex("""^\s*(?:implementation|api|runtimeOnly)\("([^":]+:[^":]+)(?::[^"]*)?"\)""", RegexOption.MULTILINE)
            .findAll(gradle)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(coordinates.isNotEmpty())
        val uncovered = coordinates.filter { coordinate -> entries.none { it.covers(coordinate) } }
        assertEquals("dependencies without a licence entry", emptyList<String>(), uncovered)
    }

    @Test
    fun `transitive runtime libraries with their own licence are covered`() {
        // commonmark-ext-autolink pulls in autolink-java (MIT) at runtime.
        val autolink = entries.single { it.covers("org.nibor.autolink:autolink") }
        assertEquals("MIT", autolink.license)
        assertEquals("autolink-java-MIT.txt", autolink.file)
    }

    @Test
    fun `covers matches coordinate prefixes`() {
        val entry = LicenseEntry("x", "1", "MIT", "https://example.com", "x.txt", listOf("org.example:lib"))
        assertTrue(entry.covers("org.example:lib-core"))
        assertFalse(entry.covers("org.other:lib"))
    }
}
