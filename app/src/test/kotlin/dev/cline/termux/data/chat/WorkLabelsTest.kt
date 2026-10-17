package dev.cline.termux.data.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ported from sdk/packages/ui/tests/agent-chat.test.tsx (the label cases). */
class WorkLabelsTest {
    @Test fun `labels the thinking row by streaming state and duration`() {
        assertEquals("Thinking", formatThoughtLabel(null))
        assertEquals("Thought for 0s", formatThoughtLabel(0))
        assertEquals("Thought for 1s", formatThoughtLabel(120))
        assertEquals("Thought for 3s", formatThoughtLabel(2_600))
    }

    @Test fun `formats work summary labels`() {
        assertEquals("Worked for 4m 12s and made 14 tool calls", formatWorkActivityLabel(durationMilliseconds = 252_000, toolCallCount = 14))
        assertEquals("Worked for 1s and made 1 tool call", formatWorkActivityLabel(durationMilliseconds = 800, toolCallCount = 1))
        assertEquals("Worked for 1h 2m", formatWorkActivityLabel(durationMilliseconds = 3_720_000))
        assertEquals("Made 3 tool calls", formatWorkActivityLabel(toolCallCount = 3))
        assertEquals("Worked", formatWorkActivityLabel())
    }

    @Test fun `formats compact work durations`() {
        assertEquals("1s", formatWorkDuration(0))
        assertEquals("3s", formatWorkDuration(2_500))
        assertEquals("1m", formatWorkDuration(60_000))
        assertEquals("1h", formatWorkDuration(3_600_000))
        assertEquals("Worked", formatWorkActivityLabel(durationMilliseconds = -1, toolCallCount = 0))
    }
}
