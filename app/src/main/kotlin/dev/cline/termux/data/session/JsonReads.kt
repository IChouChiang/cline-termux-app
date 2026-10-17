package dev.cline.termux.data.session

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Lenient readers for Hub payloads (docs/08: shapes vary by publisher, fields may be absent
 * or null). Internal to the reducer core; nothing here throws on a malformed payload.
 */

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let {
    it.longOrNull ?: it.doubleOrNull?.toLong()
}

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

/** Epoch milliseconds from a number or an ISO-8601 string (snapshots use ISO, records use ms). */
internal fun JsonElement?.epochMillis(): Long? {
    val p = this as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    p.longOrNull?.let { return it }
    p.doubleOrNull?.let { return it.toLong() }
    val text = p.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}

internal val EMPTY_OBJECT = JsonObject(emptyMap())
