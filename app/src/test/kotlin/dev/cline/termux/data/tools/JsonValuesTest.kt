package dev.cline.termux.data.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JavaScript-compatibility helpers the ports rely on. */
class JsonValuesTest {
    @Test fun `stringify matches JSON stringify with two-space indent`() {
        val value = Json.parseToJsonElement("""{"a":[1,2.0,{"b":"x\"y\n"}],"c":{},"d":[],"e":null,"f":1e3}""")
        assertEquals(
            "{\n  \"a\": [\n    1,\n    2,\n    {\n      \"b\": \"x\\\"y\\n\"\n    }\n  ],\n  \"c\": {},\n  \"d\": [],\n  \"e\": null,\n  \"f\": 1000\n}",
            jsonStringify(value),
        )
        assertEquals("""{"a":1}""", jsonStringify(Json.parseToJsonElement("""{"a":1}"""), ""))
    }

    @Test fun `numbers print as JavaScript prints them`() {
        assertEquals("1.5", jsNumberToString(1.5))
        assertEquals("-3", jsNumberToString(-3.0))
        assertEquals("1e+21", jsNumberToString(1e21))
        assertEquals("1e-7", jsNumberToString(1e-7))
    }

    @Test fun `normalize re-parses JSON-looking strings only`() {
        assertEquals(Json.parseToJsonElement("""{"a":1}"""), normalizeJsonValue(JsonPrimitive(""" {"a":1} """)))
        assertEquals(JsonPrimitive("{broken"), normalizeJsonValue(JsonPrimitive("{broken")))
        assertEquals(JsonPrimitive("plain"), normalizeJsonValue(JsonPrimitive("plain")))
    }

    @Test fun `truthiness and typeof checks follow JavaScript`() {
        assertFalse(JsonPrimitive("").isTruthy)
        assertFalse(JsonPrimitive(0).isTruthy)
        assertTrue(JsonPrimitive(2).isTruthy)
        assertTrue(Json.parseToJsonElement("[]").isTruthy)
        assertNull(JsonPrimitive("5").jsNumber())
        assertEquals(5.0, JsonPrimitive(5).jsNumber()!!, 0.0)
        assertNull(JsonPrimitive(true).jsNumber())
        assertEquals(true, JsonPrimitive(true).jsBoolean())
    }

    @Test fun `collapses JavaScript whitespace`() {
        assertEquals("a b c", collapseWhitespace("  a \n b\t c "))
    }

    @Test fun `parses the timestamp shapes the Hub sends`() {
        assertEquals(1_788_204_700_834L, parseJsDate("2026-08-31T19:31:40.834Z"))
        assertEquals(1_767_571_200_000L, parseJsDate("2026-01-05"))
        assertNull(parseJsDate("not a date"))
    }
}
