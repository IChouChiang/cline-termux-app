package dev.cline.termux.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [showReplacingUntil]: a snackbar whose message is only true for a while (trial row 11). */
@OptIn(ExperimentalCoroutinesApi::class)
class SnackbarTest {
    private val message = "The agent did not stop. Try again."
    private val action = "Restart kernel"

    @Test
    fun `the snackbar goes when its message stops being true`() = runTest {
        val host = SnackbarHostState()
        val ended = CompletableDeferred<Unit>()
        val result = async { host.showReplacingUntil(message, action) { ended.await() } }
        runCurrent()
        assertEquals(message, host.currentSnackbarData?.visuals?.message)
        assertEquals(SnackbarDuration.Indefinite, host.currentSnackbarData?.visuals?.duration)

        ended.complete(Unit)
        runCurrent()
        assertNull(host.currentSnackbarData)
        assertNull(result.await())
    }

    @Test
    fun `an action taken before the end is the result`() = runTest {
        val host = SnackbarHostState()
        val result = async { host.showReplacingUntil(message, action) { awaitCancellation() } }
        runCurrent()
        host.currentSnackbarData!!.performAction()
        runCurrent()
        assertEquals(SnackbarResult.ActionPerformed, result.await())
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun `a message that ended while waiting is never shown and the other snackbar stays`() = runTest {
        val host = SnackbarHostState()
        val other = async { host.showSnackbar("Copied", duration = SnackbarDuration.Indefinite) }
        runCurrent()
        val ended = CompletableDeferred<Unit>()
        val ours = async { host.showReplacingUntil(message, action) { ended.await() } }
        runCurrent()
        assertEquals("Copied", host.currentSnackbarData?.visuals?.message)

        ended.complete(Unit)
        runCurrent()
        assertNull(ours.await())
        assertEquals("only this message is affected", "Copied", host.currentSnackbarData?.visuals?.message)
        host.currentSnackbarData!!.dismiss()
        runCurrent()
        assertEquals(SnackbarResult.Dismissed, other.await())
        assertNull("the ended message never came up", host.currentSnackbarData)
    }

    @Test
    fun `cancelling the caller cancels it and takes the snackbar down`() = runTest {
        val host = SnackbarHostState()
        var returned = false
        val job = launch {
            host.showReplacingUntil(message, action) { awaitCancellation() }
            returned = true
        }
        runCurrent()
        assertEquals(message, host.currentSnackbarData?.visuals?.message)
        job.cancel()
        runCurrent()
        assertTrue(job.isCancelled)
        assertFalse(returned)
        assertNull(host.currentSnackbarData)
    }
}
