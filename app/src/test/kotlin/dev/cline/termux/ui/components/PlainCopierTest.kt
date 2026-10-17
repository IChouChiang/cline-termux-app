package dev.cline.termux.ui.components

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlainCopierTest {

    @Test
    fun `a clipboard write that works reports success`() = runTest {
        var writes = 0
        assertTrue(writePlainClip { writes++ })
        assertEquals(1, writes)
    }

    @Test
    fun `an oversized clip is reported, not thrown`() = runTest {
        // ClipboardManager.setPrimaryClip past the Binder limit: TransactionTooLargeException
        // wrapped in a RuntimeException.
        assertFalse(writePlainClip { throw RuntimeException("android.os.TransactionTooLargeException") })
    }

    @Test
    fun `a locked-down clipboard is reported, not thrown`() = runTest {
        assertFalse(writePlainClip { throw SecurityException("clipboard denied") })
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates`() = runTest {
        writePlainClip { throw CancellationException("left the screen") }
    }
}
