package dev.cline.termux.core.kernel.termux

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Brief §9.4: the append script itself, run with the workstation's `bun -e` against a copy of
 * the script whose path points into a temp directory (the device runs the same text with
 * `bun-ffi -e`). Skipped, not failed, on a machine without Bun.
 */
class InputHistoryAppendScriptTest {

    private lateinit var dir: File
    private lateinit var file: File
    private lateinit var bun: String

    @Before
    fun setUp() {
        val found = findBun()
        assumeTrue("bun is not installed on this machine", found != null)
        bun = found!!
        dir = Files.createTempDirectory("p6-history").toFile()
        file = File(dir, "cache/user_input_history.jsonl")
    }

    @After
    fun tearDown() {
        if (::dir.isInitialized) {
            file.setReadable(true)
            dir.deleteRecursively()
        }
    }

    private fun script(): String {
        val original = InputHistoryCommands.append("unused").args[1]
        assertTrue(original.contains("\"${InputHistoryCommandsTest.HISTORY_FILE}\""))
        return original.replace(InputHistoryCommandsTest.HISTORY_FILE, file.absolutePath)
    }

    private fun append(prompt: String): Int {
        val process = ProcessBuilder(bun, "-e", script()).redirectErrorStream(true).start()
        process.outputStream.use { it.write(prompt.toByteArray(Charsets.UTF_8)) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue("bun did not finish: $output", process.waitFor(30, TimeUnit.SECONDS))
        return process.exitValue()
    }

    private fun lines(): List<String> = file.readText().split('\n').filter { it.isNotEmpty() }

    private fun prompts(): List<String> = lines().map { Json.parseToJsonElement(it).jsonObject["prompt"]!!.jsonPrimitive.content }

    private fun write(vararg prompts: String) {
        file.parentFile.mkdirs()
        file.writeText(prompts.joinToString("\n", postfix = "\n") { """{"prompt":"$it","ts":1}""" })
    }

    @Test fun aMissingFileAndFolderAreCreated() {
        assertEquals(0, append("  hello world \n"))
        assertEquals(listOf("hello world"), prompts())
        val ts = Json.parseToJsonElement(lines().single()).jsonObject["ts"]!!.jsonPrimitive.longOrNull
        assertNotNull(ts)
        assertTrue(file.readText().endsWith("\n"))
    }

    @Test fun theNewPromptIsLastInTheFileAndEarlierCopiesGo() {
        write("a", "b", "c")
        assertEquals(0, append("b"))
        assertEquals(listOf("a", "c", "b"), prompts())
        // Every line gets a fresh timestamp, like the terminal's rewrite.
        assertTrue(lines().none { it.contains("\"ts\":1}") })
        // The app's reader sees it newest first.
        assertEquals(listOf("b", "c", "a"), InputHistoryCommands.parse(file.readText()))
    }

    @Test fun theFileKeepsTwenty() {
        write(*(1..20).map { "p$it" }.toTypedArray())
        assertEquals(0, append("new"))
        val prompts = prompts()
        assertEquals(20, prompts.size)
        assertEquals("p2", prompts.first())
        assertEquals("new", prompts.last())
    }

    @Test fun malformedLinesAreDroppedOnRewrite() {
        file.parentFile.mkdirs()
        file.writeText("{\"prompt\":\"a\",\"ts\":1}\nnot json\n{\"prompt\":5}\n")
        assertEquals(0, append("b"))
        assertEquals(listOf("a", "b"), prompts())
    }

    @Test fun anEmptyPromptChangesNothing() {
        assertEquals(0, append("   \n"))
        assertFalse(file.exists())
    }

    @Test fun quotesNewlinesAndUnicodeRoundTrip() {
        val prompt = "say \"hi\"\n\tthen 你好 🙂 \\ done"
        assertEquals(0, append(prompt))
        assertEquals(listOf(prompt), InputHistoryCommands.parse(file.readText()))
    }

    @Test fun anUnreadableFileExitsThreeAndIsLeftAlone() {
        write("keep")
        val before = file.readText()
        assertTrue(file.setReadable(false))
        assumeTrue("running as a user who can read anything", !file.canRead())
        assertEquals(InputHistoryCommands.EXIT_UNREADABLE, append("new"))
        assertTrue(file.setReadable(true))
        assertEquals(before, file.readText())
        assertEquals(listOf("user_input_history.jsonl"), file.parentFile.list()!!.toList())
    }

    @Test fun noTempFileIsLeftBehind() {
        write("a")
        assertEquals(0, append("b"))
        assertEquals(listOf("user_input_history.jsonl"), file.parentFile.list()!!.toList())
    }

    private fun findBun(): String? {
        val path = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "bun") }
        val mise = File(System.getProperty("user.home"), ".local/share/mise/installs/bun")
            .listFiles()?.map { File(it, "bin/bun") }.orEmpty()
        val home = File(System.getProperty("user.home"), ".bun/bin/bun")
        return (path + mise + home).firstOrNull { it.isFile && it.canExecute() }?.absolutePath
    }
}
