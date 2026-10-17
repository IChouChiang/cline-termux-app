package dev.cline.termux.data.prefs

import dev.cline.termux.fakes.FakeClock
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JsonFileCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Serializable
    private data class Row(val id: String, val title: String)

    private val clock = FakeClock()

    private fun cache(dir: File = tmp.root, version: Int = 1) =
        JsonFileCache(File(dir, "nested/rows.json"), ListSerializer(Row.serializer()), version = version, clock = clock)

    @Test
    fun `round trips the payload with its save time`() = runTest {
        val cache = cache()
        assertNull(cache.read())
        assertFalse(cache.exists())
        cache.write(listOf(Row("a", "Alpha"), Row("b", "Beta")))
        assertTrue(cache.exists())
        val read = cache.read()!!
        assertEquals(listOf(Row("a", "Alpha"), Row("b", "Beta")), read.value)
        assertEquals(clock.millis(), read.savedAtMs)
    }

    @Test
    fun `a corrupt file reads as empty and is removed`() = runTest {
        val cache = cache()
        cache.path.parentFile!!.mkdirs()
        cache.path.writeText("{not json")
        assertNull(cache.read())
        assertFalse(cache.path.exists())
    }

    @Test
    fun `a different schema version reads as empty and is removed`() = runTest {
        cache(version = 1).write(listOf(Row("a", "Alpha")))
        val newer = cache(version = 2)
        assertNull(newer.read())
        assertFalse(newer.path.exists())
    }

    @Test
    fun `writes replace the file and leave no temp file behind`() = runTest {
        val cache = cache()
        cache.write(listOf(Row("a", "Alpha")))
        cache.write(listOf(Row("b", "Beta")))
        assertEquals(listOf(Row("b", "Beta")), cache.read()!!.value)
        assertEquals(listOf("rows.json"), cache.path.parentFile!!.list()!!.toList())
    }

    @Test
    fun `clear removes the file`() = runTest {
        val cache = cache()
        cache.write(emptyList())
        cache.clear()
        assertNull(cache.read())
        assertFalse(cache.exists())
    }

    @Test
    fun `the two named caches live at the documented paths`() = runTest {
        val sessions = SessionListCache(tmp.root, ListSerializer(String.serializer()), clock = clock)
        val catalog = CatalogCache(tmp.root, String.serializer(), clock = clock)
        assertEquals(File(tmp.root, "sessions/list-cache.json"), sessions.path)
        assertEquals(File(tmp.root, "catalog-cache.json"), catalog.path)
        sessions.write(listOf("s1"))
        catalog.write("projection")
        assertEquals(listOf("s1"), sessions.read()!!.value)
        assertEquals("projection", catalog.read()!!.value)
    }
}
