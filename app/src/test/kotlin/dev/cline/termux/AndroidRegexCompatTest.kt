package dev.cline.termux

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Android compiles `Regex` with ICU, not the JVM engine these unit tests run on, so a
 * pattern the JVM accepts can still throw `PatternSyntaxException` on the phone (a
 * `(?U)` in `SlashCommands` crashed the app at Home). This guards the JVM-only syntax
 * ICU rejects; use explicit classes such as `JS_WHITESPACE_CLASS` instead.
 */
class AndroidRegexCompatTest {
    private val jvmOnly = listOf(
        "(?U)",
        "UNICODE_CHARACTER_CLASS",
        "CANON_EQ",
        "\\\\p{java",
        "\\\\p{Is",
        "\\\\p{In",
    )

    @Test
    fun mainSourcesAvoidJvmOnlyRegexSyntax() {
        val root = listOf(File("src/main/kotlin"), File("app/src/main/kotlin")).first { it.isDirectory }
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val hit = jvmOnly.firstOrNull { line.contains(it) && !line.trimStart().startsWith("*") && !line.trimStart().startsWith("//") }
                    hit?.let { "${file.path}:${index + 1}: $it" }
                }
            }
            .toList()
        assertTrue("JVM-only regex syntax that Android's ICU rejects:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
