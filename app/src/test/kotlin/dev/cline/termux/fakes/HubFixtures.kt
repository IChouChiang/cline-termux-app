package dev.cline.termux.fakes

import dev.cline.termux.core.kernel.hub.HubCommandEnvelope
import dev.cline.termux.core.kernel.hub.HubEventEnvelope
import dev.cline.termux.core.kernel.hub.HubFrame
import dev.cline.termux.core.kernel.hub.HubFrameSerializer
import dev.cline.termux.core.kernel.hub.HubJson
import dev.cline.termux.core.kernel.hub.HubReplyEnvelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Reader for the recorded Hub traffic in `src/test/resources/hub-fixtures` (docs/08). */
object HubFixtures {
    data class Line(val dir: String, val conn: String?, val frame: HubFrame?, val raw: JsonObject)

    fun load(name: String): List<Line> {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("hub-fixtures/$name")) { "missing fixture $name" }
        return stream.bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val obj = HubJson.parseToJsonElement(line).jsonObject
            val frame = obj["frame"]?.let { runCatching { HubFrameSerializer.decodeFrame(it.jsonObject) }.getOrNull() }
            Line(obj["dir"]!!.jsonPrimitive.content, obj["conn"]?.jsonPrimitive?.content, frame, obj)
        }
    }

    val Line.event: HubEventEnvelope? get() = (frame as? HubFrame.Event)?.envelope?.takeIf { dir == "recv" }
    val Line.command: HubCommandEnvelope? get() = (frame as? HubFrame.Command)?.envelope?.takeIf { dir == "send" }
    val Line.reply: HubReplyEnvelope? get() = (frame as? HubFrame.Reply)?.envelope?.takeIf { dir == "recv" }

    /** Distinct events in recorded order (each session event is recorded twice, docs/08). */
    fun events(lines: List<Line>): List<HubEventEnvelope> {
        val seen = HashSet<Long>()
        return lines.mapNotNull { it.event }.filter { e -> e.sequence?.let(seen::add) ?: true }
    }

    fun replyTo(lines: List<Line>, requestId: String): HubReplyEnvelope = lines.mapNotNull { it.reply }.first { it.requestId == requestId }

    /** The payload of the first reply that carries `messages[]` for [sessionId]. */
    fun messagesReply(lines: List<Line>, sessionId: String, nth: Int = 0): JsonObject =
        lines.mapNotNull { it.reply?.payload }
            .filter { it["messages"] is JsonArray && it["sessionId"]?.jsonPrimitive?.content == sessionId }[nth]
}
