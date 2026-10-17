package dev.cline.termux.data.checkpoint

import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubPayloads
import dev.cline.termux.fakes.HubFixtures
import dev.cline.termux.fakes.HubFixtures.command
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture 07 (docs/08): two mock-edit runs, then the app's split restore at run 1, recorded
 * against core 0.0.91. Checks that the app's own payload builders and checkpoint readers agree
 * with what the real Hub received and answered: the daemon-side checkpoint history, the
 * workspace-only `session.restore`, the transcript cut and the restored create's metadata.
 */
class CheckpointRestoreFixtureTest {
    private val lines = HubFixtures.load("07-checkpoint-restore.jsonl")

    private fun commands(name: String): List<HubCommandEnvelope> =
        lines.mapNotNull { it.command }.filter { it.command == name }

    private fun replyPayload(command: HubCommandEnvelope): JsonObject {
        val reply = HubFixtures.replyTo(lines, checkNotNull(command.requestId))
        assertTrue("${command.command} replied ok", reply.ok)
        return reply.payload ?: JsonObject(emptyMap())
    }

    private val sourceId: String
        get() = commands("session.restore").single().payload!!["sessionId"]!!.jsonPrimitive.content

    private fun sourceMetadata(): JsonObject =
        replyPayload(commands("session.get").last())["session"]!!.jsonObject["metadata"]!!.jsonObject

    @Test
    fun `the daemon records a checkpoint for each run without a client contribution`() {
        val history = readSessionCheckpointHistory(sourceMetadata())
        assertEquals(listOf(1, 2), history.map { it.runCount })
        assertEquals(listOf("commit", "stash"), history.map { it.kind })
        assertTrue(history.all { it.ref.length == 40 && it.createdAt > 0 })
    }

    @Test
    fun `the workspace-only restore matches the app's payload and answers with run 1's checkpoint`() {
        val restore = commands("session.restore").single()
        assertEquals(HubPayloads.sessionRestoreWorkspaceOnly(sourceId, 1), restore.payload)
        val checkpoint = replyPayload(restore)["checkpoint"]!!.jsonObject
        assertEquals(1, checkpoint["runCount"]!!.jsonPrimitive.content.toInt())
        val run1 = readSessionCheckpointHistory(sourceMetadata()).first { it.runCount == 1 }
        assertEquals(run1.ref, checkpoint["ref"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the transcript cut at run 1 keeps exactly what the restored session holds`() {
        val sourceMessages = HubFixtures.messagesReply(lines, sourceId)["messages"]!!.jsonArray.map { it.jsonObject }
        val kept = trimMessagesToCheckpoint(sourceMessages.map(RunMessage::fromJson), 1)
        val restoredCreate = commands("session.create").last()
        val restoredId = replyPayload(restoredCreate)["session"]!!.jsonObject["sessionId"]!!.jsonPrimitive.content
        val restoredMessages = HubFixtures.messagesReply(lines, restoredId)["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(kept.map { it.id }, restoredMessages.map { it["id"]!!.jsonPrimitive.content })
        assertEquals(1, countUserRunMessages(restoredMessages.map(RunMessage::fromJson)))
    }

    @Test
    fun `the restored create carries the history filtered to run 1`() {
        val metadata = commands("session.create").last().payload!!["metadata"]!!.jsonObject
        assertEquals(sourceId, metadata["restoredFromSessionId"]!!.jsonPrimitive.content)
        assertEquals(1, metadata["restoredCheckpointRunCount"]!!.jsonPrimitive.content.toInt())
        val run1 = readSessionCheckpointHistory(sourceMetadata()).filter { it.runCount == 1 }
        // Recorded before the fix: createdAt went out as a string. Core reads it with Number(),
        // and so must the app, or a restored session loses its inherited checkpoints in C3.
        assertTrue(metadata["checkpoint"]!!.jsonObject["history"]!!.jsonArray[0].jsonObject["createdAt"]!!.jsonPrimitive.isString)
        assertEquals(run1, readSessionCheckpointHistory(metadata))
    }

    @Test
    fun `the app now writes the filtered history in core's shape, createdAt a number`() {
        val built = HubPayloads.filteredCheckpoints(sourceMetadata()["checkpoint"]!!.jsonObject, 1)!!
        val entry = built["history"]!!.jsonArray.single().jsonObject
        assertFalse(entry["createdAt"]!!.jsonPrimitive.isString)
        assertFalse(built["latest"]!!.jsonObject["createdAt"]!!.jsonPrimitive.isString)
        val run1 = readSessionCheckpointHistory(sourceMetadata()).filter { it.runCount == 1 }
        assertEquals(run1, readCheckpointHistory(built))
    }
}
