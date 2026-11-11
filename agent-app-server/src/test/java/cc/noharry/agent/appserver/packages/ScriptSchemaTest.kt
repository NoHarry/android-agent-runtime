package cc.noharry.agent.appserver.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A call's arguments are held to what the tool's input schema declares, and the model reads what is wrong. */
class ScriptSchemaTest {
    private val schema = Json.parseToJsonElement(
        """
        {"type": "object", "required": ["from", "to"], "additionalProperties": false, "properties": {
          "from": {"type": "string", "minLength": 2, "maxLength": 20},
          "to": {"type": "string"},
          "day": {"type": "string", "enum": ["today", "tomorrow"]},
          "seats": {"type": "integer", "minimum": 1, "maximum": 6},
          "price": {"type": ["number", "null"], "exclusiveMinimum": 0},
          "tags": {"type": "array", "maxItems": 2, "items": {"type": "string"}},
          "class": {"const": "second"},
          "extra": {"type": "object", "additionalProperties": {"type": "boolean"}}
        }}
        """.trimIndent(),
    ) as JsonObject

    private fun problem(arguments: String): String? = ScriptSchema.problem(schema, Json.parseToJsonElement(arguments))

    @Test
    fun argumentsThatFitPass() {
        assertNull(problem("""{"from": "Bern", "to": "Basel"}"""))
        assertNull(problem("""{"from": "Bern", "to": "Basel", "day": "today", "seats": 2, "price": null, "tags": ["a"], "class": "second"}"""))
        assertNull(problem("""{"from": "Bern", "to": "Basel", "seats": 2.0, "price": 0.5, "extra": {"x": true}}"""))
    }

    @Test
    fun whatDoesNotFitIsNamed() {
        assertEquals("arguments: not object", problem("""["Bern"]"""))
        assertEquals("to: missing", problem("""{"from": "Bern"}"""))
        assertEquals("from: not string", problem("""{"from": 3, "to": "Basel"}"""))
        assertEquals("from: shorter than 2 characters", problem("""{"from": "B", "to": "Basel"}"""))
        assertEquals("via: not a known property", problem("""{"from": "Bern", "to": "Basel", "via": "Olten"}"""))
        assertEquals("day: not one of [\"today\",\"tomorrow\"]", problem("""{"from": "Bern", "to": "Basel", "day": "monday"}"""))
        assertEquals("seats: not integer", problem("""{"from": "Bern", "to": "Basel", "seats": 1.5}"""))
        assertEquals("seats: above 6", problem("""{"from": "Bern", "to": "Basel", "seats": 7}"""))
        assertEquals("seats: not integer", problem("""{"from": "Bern", "to": "Basel", "seats": "2"}"""))
        assertEquals("price: not above 0", problem("""{"from": "Bern", "to": "Basel", "price": 0}"""))
        assertEquals("tags: more than 2 items", problem("""{"from": "Bern", "to": "Basel", "tags": ["a", "b", "c"]}"""))
        assertEquals("tags[1]: not string", problem("""{"from": "Bern", "to": "Basel", "tags": ["a", 1]}"""))
        assertEquals("class: not \"second\"", problem("""{"from": "Bern", "to": "Basel", "class": "first"}"""))
        assertEquals("extra.x: not boolean", problem("""{"from": "Bern", "to": "Basel", "extra": {"x": "yes"}}"""))
    }
}
