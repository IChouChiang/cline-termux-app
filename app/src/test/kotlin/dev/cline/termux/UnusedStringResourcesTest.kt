package dev.cline.termux

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every string, plurals and string-array resource is referenced from code or XML. Stands in for
 * lint's UnusedResources check, which the module's lint block does not raise to an error, so dead
 * copy (an entry point that was never rendered, a duplicate of a wire constant) fails the unit suite.
 */
class UnusedStringResourcesTest {
    private val appDir: File = listOf(File("."), File("app")).first { File(it, "src/main/res").isDirectory }

    @Test
    fun `every string resource is referenced`() {
        val declaration = Regex("""<(string|plurals|string-array)\s+name="([^"]+)"""")
        val declared = File(appDir, "src/main/res").walkTopDown()
            .filter { it.isFile && it.extension == "xml" && it.parentFile.name.startsWith("values") }
            .flatMap { file -> declaration.findAll(file.readText()).map { it.groupValues[2] to file.name } }
            .toList()
        val sources = File(appDir, "src").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }
            .joinToString("\n") { it.readText() }
        val references = Regex("""(?:R\.(?:string|plurals|array)\.|@(?:string|plurals|array)/)([A-Za-z0-9_]+)""")
            .findAll(sources).map { it.groupValues[1] }.toSet()
        val unused = declared.filterNot { (name, _) -> name in references }.map { (name, file) -> "$file: $name" }
        assertTrue("declared ${declared.size} resources, found none", declared.isNotEmpty())
        assertTrue("Unreferenced string resources:\n" + unused.joinToString("\n"), unused.isEmpty())
    }
}
