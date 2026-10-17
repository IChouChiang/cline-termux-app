package dev.cline.termux.data.session

import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubEvents
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The append-only `run_commands` output buffer (CT §1.4 head cap, docs/07 §8 single reducer thread). */
class CommandBufferTest {

    private fun buffer() = CommandBuffer(executionId = "x1", commandIndex = 0, query = "make")

    private fun out(text: String) = OutputChunk(OutputStream.Stdout, text)
    private fun err(text: String) = OutputChunk(OutputStream.Stderr, text)

    @Test
    fun `small chunks of one stream share a piece and the length runs with them`() {
        var b = buffer()
        b = SessionFactsReducer.appendChunk(b, out("a\n"))
        b = SessionFactsReducer.appendChunk(b, out("b\n"))
        b = SessionFactsReducer.appendChunk(b, err("oops\n"))
        b = SessionFactsReducer.appendChunk(b, out("c\n"))
        b = SessionFactsReducer.appendChunk(b, out(""))
        assertEquals(listOf(out("a\nb\n"), err("oops\n"), out("c\n")), b.chunks)
        assertEquals(11, b.length)
        assertEquals("a\nb\noops\nc\n", b.text())
        assertEquals("oops\n", b.text(OutputStream.Stderr))
        assertEquals(0L, b.headDroppedChars)
    }

    @Test
    fun `a chunk that does not fit the last piece starts a new one and earlier pieces are never copied`() {
        var b = SessionFactsReducer.appendChunk(buffer(), out("x".repeat(CommandBuffer.PIECE_CHARS - 1)))
        val first = b.chunks.single()
        b = SessionFactsReducer.appendChunk(b, out("yy"))
        assertEquals(2, b.chunks.size)
        assertSame(first, b.chunks[0])
        assertEquals(CommandBuffer.PIECE_CHARS + 1, b.length)
    }

    @Test
    fun `a large chunk is split into pieces of at most PIECE_CHARS`() {
        val text = (0 until 10_000).joinToString("") { (it % 10).toString() }
        val b = SessionFactsReducer.appendChunk(buffer(), out(text))
        assertTrue(b.chunks.all { it.text.length <= CommandBuffer.PIECE_CHARS })
        assertEquals(3, b.chunks.size)
        assertEquals(text, b.text())
        assertEquals(10_000, b.length)
    }

    @Test
    fun `the head cap drops whole leading pieces and slices only the boundary piece`() {
        var b = buffer()
        val piece = "p".repeat(CommandBuffer.PIECE_CHARS)
        repeat(CommandBuffer.MAX_CHARS / CommandBuffer.PIECE_CHARS) { b = SessionFactsReducer.appendChunk(b, out(piece)) }
        assertEquals(CommandBuffer.MAX_CHARS, b.length)
        assertEquals(0L, b.headDroppedChars)
        val third = b.chunks[2]

        // 4 097 characters over: the first piece goes whole, the second loses one character.
        b = SessionFactsReducer.appendChunk(b, err("e".repeat(CommandBuffer.PIECE_CHARS + 1)))
        assertEquals(CommandBuffer.MAX_CHARS, b.length)
        assertEquals((CommandBuffer.PIECE_CHARS + 1).toLong(), b.headDroppedChars)
        assertEquals(CommandBuffer.PIECE_CHARS - 1, b.chunks[0].text.length)
        assertSame(third, b.chunks[1])
        assertEquals(b.length, b.chunks.sumOf { it.text.length })
        assertTrue(b.text().endsWith("p" + "e".repeat(CommandBuffer.PIECE_CHARS + 1)))
    }

    @Test
    fun `a buffer built from a plain list keeps its length and caps like any other`() {
        val b = CommandBuffer("x1", 0, "q", chunks = listOf(out("abc"), err("de")))
        assertEquals(5, b.length)
        val next = SessionFactsReducer.appendChunk(b, out("f"))
        assertEquals(6, next.length)
        assertEquals("abcdef", next.text())
    }

    @Test
    fun `five thousand 4 KB chunks through the reducer finish well under a second`() {
        val chunk = "0123456789abcdef".repeat(256) // 4 096 characters
        val started = HubEventEnvelope(
            event = HubEvents.TOOL_STARTED, sessionId = "s1", sequence = 1, timestamp = 1L,
            payload = buildJsonObject { put("toolCallId", "call_1"); put("toolName", "run_commands") },
        )
        val updates = (0 until 5_000).map { i ->
            HubEventEnvelope(
                event = HubEvents.TOOL_UPDATED, sessionId = "s1", sequence = 2L + i, timestamp = 2L + i,
                payload = buildJsonObject {
                    put("toolCallId", "call_1")
                    put(
                        "update",
                        buildJsonObject {
                            put("executionId", "x1")
                            put("commandIndex", 0)
                            put("query", "make")
                            put("stream", if (i % 50 == 49) "stderr" else "stdout")
                            put("chunk", chunk)
                        },
                    )
                },
            )
        }
        val ctx = ReduceContext(clientId = "android-test", now = 0L)
        var facts = SessionFactsReducer.reduce(SessionFacts("s1"), started, ctx)
        val start = System.nanoTime()
        for (event in updates) facts = SessionFactsReducer.reduce(facts, event, ctx)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        val exec = facts.tools.getValue("call_1").executions.single()
        val total = 5_000L * chunk.length
        assertEquals(CommandBuffer.MAX_CHARS, exec.length)
        assertEquals(total - CommandBuffer.MAX_CHARS, exec.headDroppedChars)
        assertEquals(exec.length, exec.chunks.sumOf { it.text.length })
        assertTrue(exec.text().endsWith(chunk))
        assertTrue("5 000 appends took $elapsedMs ms", elapsedMs < 1_000)
    }
}
