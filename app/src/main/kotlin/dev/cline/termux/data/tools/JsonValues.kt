package dev.cline.termux.data.tools

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * JavaScript value semantics over kotlinx JsonElement, shared by the desktop ports in
 * the data packages (tool summaries, session agents, schedules). The TypeScript sources take
 * `unknown` and test with `typeof`; these helpers answer the same questions so the
 * ports read line for line. `null` (Kotlin) stands for `undefined`, [JsonNull] for `null`.
 */

private val lenientJson = Json { isLenient = false }

/** `JSON.parse`, or null when the text is not JSON. */
fun parseJsonOrNull(text: String): JsonElement? =
    try {
        lenientJson.parseToJsonElement(text)
    } catch (_: Exception) {
        null
    }

/** `typeof value === "string" ? value : undefined`. */
fun JsonElement?.jsString(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** `typeof value === "string" && value.length > 0 ? value : undefined`. */
fun JsonElement?.nonEmptyString(): String? = jsString()?.takeIf { it.isNotEmpty() }

/** `typeof value === "number" ? value : undefined` (finite JSON numbers only). */
fun JsonElement?.jsNumber(): Double? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString || primitive is JsonNull) return null
    if (primitive.content == "true" || primitive.content == "false") return null
    return primitive.content.toDoubleOrNull()
}

/** `typeof value === "boolean" ? value : undefined`. */
fun JsonElement?.jsBoolean(): Boolean? {
    val primitive = this as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

/** `isRecord(value)`: a plain object, not an array, not null. */
fun JsonElement?.asRecord(): JsonObject? = this as? JsonObject

/** `value == null` (undefined or null). */
val JsonElement?.isNullish: Boolean get() = this == null || this is JsonNull

/** JavaScript truthiness of a JSON value (`Boolean(value)`). */
val JsonElement?.isTruthy: Boolean
    get() = when (this) {
        null, is JsonNull -> false
        is JsonObject, is JsonArray -> true
        is JsonPrimitive -> when {
            isString -> content.isNotEmpty()
            content == "true" -> true
            content == "false" -> false
            else -> content.toDoubleOrNull().let { it != null && it != 0.0 && !it.isNaN() }
        }
    }

/**
 * `normalizeValue` (tool-summary/parsers.ts) and `normalizeDisplayValue`
 * (tool-summaries.ts): a string that looks like serialized JSON is re-parsed; every
 * other value passes through.
 */
fun normalizeJsonValue(value: JsonElement?): JsonElement? {
    val text = value.jsString() ?: return value
    val trimmed = text.trim()
    val looksLikeJson = (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
        (trimmed.startsWith("[") && trimmed.endsWith("]"))
    if (!looksLikeJson) return value
    return parseJsonOrNull(trimmed) ?: value
}

/** JavaScript's `\s` (ECMAScript WhiteSpace + LineTerminator). */
internal const val JS_WHITESPACE_CLASS =
    "[\\t\\n\\u000B\\f\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"

internal val JS_WHITESPACE_RUN = Regex("$JS_WHITESPACE_CLASS+")

/** `text.replace(/\s+/g, " ").trim()`. */
fun collapseWhitespace(text: String): String = text.replace(JS_WHITESPACE_RUN, " ").trim()

/** `String(value)` of a number, as JavaScript prints it. */
fun jsNumberToString(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    if (value == Math.rint(value) && kotlin.math.abs(value) < 1e21) {
        return BigDecimal(value).toBigInteger().toString()
    }
    val text = value.toString()
    val exponent = text.indexOf('E')
    if (exponent < 0) return text
    val mantissa = text.substring(0, exponent).removeSuffix(".0")
    val power = text.substring(exponent + 1)
    return if (power.startsWith("-")) "${mantissa}e$power" else "${mantissa}e+$power"
}

/** `JSON.stringify(value, null, indent)` with JavaScript's spacing and escaping. */
fun jsonStringify(value: JsonElement, indent: String = "  "): String =
    StringBuilder().also { appendJson(it, value, indent, "") }.toString()

private fun appendJson(out: StringBuilder, value: JsonElement, indent: String, current: String) {
    when (value) {
        is JsonNull -> out.append("null")
        is JsonPrimitive -> when {
            value.isString -> appendQuoted(out, value.content)
            value.content == "true" || value.content == "false" -> out.append(value.content)
            else -> out.append(value.content.toDoubleOrNull()?.let(::jsNumberToString) ?: value.content)
        }
        is JsonArray -> {
            if (value.isEmpty()) {
                out.append("[]")
                return
            }
            val inner = current + indent
            out.append('[')
            value.forEachIndexed { index, element ->
                if (index > 0) out.append(',')
                if (indent.isNotEmpty()) out.append('\n').append(inner)
                appendJson(out, element, indent, inner)
            }
            if (indent.isNotEmpty()) out.append('\n').append(current)
            out.append(']')
        }
        is JsonObject -> {
            if (value.isEmpty()) {
                out.append("{}")
                return
            }
            val inner = current + indent
            out.append('{')
            var first = true
            for ((key, element) in value) {
                if (!first) out.append(',')
                first = false
                if (indent.isNotEmpty()) out.append('\n').append(inner)
                appendQuoted(out, key)
                out.append(if (indent.isNotEmpty()) ": " else ":")
                appendJson(out, element, indent, inner)
            }
            if (indent.isNotEmpty()) out.append('\n').append(current)
            out.append('}')
        }
    }
}

private fun appendQuoted(out: StringBuilder, text: String) {
    out.append('"')
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\b' -> out.append("\\b")
            c == '\u000C' -> out.append("\\f")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c < ' ' -> out.append("\\u").append(String.format("%04x", c.code))
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                out.append(c).append(text[i + 1])
                i++
            }
            c.isSurrogate() -> out.append("\\u").append(String.format("%04x", c.code))
            else -> out.append(c)
        }
        i++
    }
    out.append('"')
}

/**
 * `Date.parse(text)` for the timestamp shapes the Hub and the desktop exchange: ISO-8601
 * instants with `Z` or an offset, date-time without an offset (local time, as JavaScript
 * reads it) and bare dates (UTC). Null where JavaScript returns NaN.
 */
fun parseJsDate(text: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    runCatching { return java.time.Instant.parse(trimmed).toEpochMilli() }
    runCatching { return java.time.OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }
    runCatching { return java.time.LocalDateTime.parse(trimmed).atZone(zone).toInstant().toEpochMilli() }
    runCatching { return java.time.LocalDate.parse(trimmed).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli() }
    runCatching { return java.time.ZonedDateTime.parse(trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
    return null
}

private val ROOT_COLLATOR: java.text.Collator =
    java.text.Collator.getInstance(java.util.Locale.ROOT).apply { strength = java.text.Collator.TERTIARY }

/** `a.localeCompare(b)` (root collation: case and accents break ties only). */
fun jsLocaleCompare(a: String, b: String): Int {
    val byCollation = synchronized(ROOT_COLLATOR) { ROOT_COLLATOR.compare(a, b) }
    return if (byCollation != 0) byCollation else a.compareTo(b)
}
