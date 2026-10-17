package dev.cline.termux

import dev.cline.termux.service.ShareFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch-1 build identity: the debug build installs beside the owner's app as
 * `io.github.ichouchiang.clinetermux.debug`, and every id-derived name (the FileProvider authority) follows the
 * application id instead of a hard-coded `io.github.ichouchiang.clinetermux`.
 */
class BuildIdentityTest {
    @Test
    fun `debug builds carry the debug suffix and release builds do not`() {
        if (BuildConfig.BUILD_TYPE == "debug") {
            assertEquals("io.github.ichouchiang.clinetermux.debug", BuildConfig.APPLICATION_ID)
            assertTrue(BuildConfig.VERSION_NAME.endsWith("-debug"))
        } else {
            assertFalse(BuildConfig.APPLICATION_ID.endsWith(".debug"))
            assertFalse(BuildConfig.VERSION_NAME.endsWith("-debug"))
        }
        assertTrue(BuildConfig.APPLICATION_ID.startsWith("io.github.ichouchiang.clinetermux"))
    }

    @Test
    fun `the file provider authority follows the application id`() {
        assertEquals(BuildConfig.APPLICATION_ID + ".files", ShareFiles.AUTHORITY)
    }
}
