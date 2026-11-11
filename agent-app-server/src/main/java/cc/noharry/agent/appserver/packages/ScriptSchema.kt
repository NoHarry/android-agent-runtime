package cc.noharry.agent.appserver.packages

import cc.noharry.agent.appserver.protocol.PackageProblem
import java.math.BigDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The part of JSON Schema a script tool's `inputSchema` may use, so that what it declares is what a call is held to:
 * `type`, `properties`, `required`, `additionalProperties`, `items`, `enum`, `const`, the bounds of numbers, strings
 * and arrays, and the annotations (`description`, `title`, `default`, `examples`, `format`, `$schema`), which are not
 * checked. Anything else (`pattern`, `$ref`, `oneOf`, …) is refused when the package is checked; `pattern` especially,
 * as a package's regular expression would run on the host.
 */
internal object ScriptSchema {
    const val MAX_SCHEMA_CHARS = 8 * 1024
    const val MAX_DEPTH = 8

    /** [schema] as a tool's input schema may be: an object schema of the supported keywords; refuses the package if not. */
    fun check(tool: String, schema: JsonObject) {
        if (schema.toString().length > MAX_SCHEMA_CHARS) refuse(PackageProblem.BAD_TOOL, "$tool: inputSchema over $MAX_SCHEMA_CHARS characters")
        if (schema[TYPE] != JsonPrimitive(OBJECT)) refuse(PackageProblem.BAD_TOOL, "$tool: inputSchema is not of type object")
        checkNode(tool, schema, depth = 0)
    }

    /** What is wrong with [value] against [schema] (a checked one), as the model reads it; null when nothing is. */
    fun problem(schema: JsonObject, value: JsonElement, at: String = ""): String? {
        val where = at.ifEmpty { "arguments" }
        (schema[TYPE])?.let { type -> if (!types(type).any { matchesType(it, value) }) return "$where: not ${types(type).joinToString(" or ")}" }
        (schema[ENUM] as? JsonArray)?.let { if (value !in it) return "$where: not one of $it" }
        schema[CONST]?.let { if (value != it) return "$where: not $it" }
        return when (value) {
            is JsonObject -> objectProblem(schema, value, at)
            is JsonArray -> arrayProblem(schema, value, at)
            is JsonPrimitive -> primitiveProblem(schema, value, where)
        }
    }

    private fun objectProblem(schema: JsonObject, value: JsonObject, at: String): String? {
        val properties = schema[PROPERTIES] as? JsonObject ?: JsonObject(emptyMap())
        (schema[REQUIRED] as? JsonArray)?.map { (it as JsonPrimitive).content }?.firstOrNull { it !in value }?.let { return "${path(at, it)}: missing" }
        value.forEach { (name, item) ->
            val sub = properties[name] as? JsonObject
            val extra = schema[ADDITIONAL_PROPERTIES]
            when {
                sub != null -> problem(sub, item, path(at, name))?.let { return it }
                extra is JsonPrimitive && extra.booleanOrNull == false -> return "${path(at, name)}: not a known property"
                extra is JsonObject -> problem(extra, item, path(at, name))?.let { return it }
            }
        }
        return null
    }

    private fun arrayProblem(schema: JsonObject, value: JsonArray, at: String): String? {
        val where = at.ifEmpty { "arguments" }
        schema.number(MIN_ITEMS)?.let { if (BigDecimal(value.size) < it) return "$where: fewer than $it items" }
        schema.number(MAX_ITEMS)?.let { if (BigDecimal(value.size) > it) return "$where: more than $it items" }
        val items = schema[ITEMS] as? JsonObject ?: return null
        value.forEachIndexed { index, item -> problem(items, item, "$where[$index]")?.let { return it } }
        return null
    }

    private fun primitiveProblem(schema: JsonObject, value: JsonPrimitive, where: String): String? {
        if (value.isString) {
            val length = BigDecimal(value.content.codePointCount(0, value.content.length))
            schema.number(MIN_LENGTH)?.let { if (length < it) return "$where: shorter than $it characters" }
            schema.number(MAX_LENGTH)?.let { if (length > it) return "$where: longer than $it characters" }
            return null
        }
        val number = value.number() ?: return null
        schema.number(MINIMUM)?.let { if (number < it) return "$where: below $it" }
        schema.number(MAXIMUM)?.let { if (number > it) return "$where: above $it" }
        schema.number(EXCLUSIVE_MINIMUM)?.let { if (number <= it) return "$where: not above $it" }
        schema.number(EXCLUSIVE_MAXIMUM)?.let { if (number >= it) return "$where: not below $it" }
        return null
    }

    private fun checkNode(tool: String, node: JsonObject, depth: Int) {
        val bad = { why: String -> refuse(PackageProblem.BAD_TOOL, "$tool: inputSchema: $why") }
        if (depth > MAX_DEPTH) bad("nested over $MAX_DEPTH levels")
        (node.keys - KEYWORDS).firstOrNull()?.let { bad("$it is not supported") }
        node[TYPE]?.let { type -> if (types(type).isEmpty() || !TYPES.containsAll(types(type))) bad("type $type") }
        node[PROPERTIES]?.let { properties ->
            (properties as? JsonObject ?: bad(PROPERTIES)).values.forEach { checkNode(tool, it as? JsonObject ?: bad(PROPERTIES), depth + 1) }
        }
        node[REQUIRED]?.let { required -> if ((required as? JsonArray)?.all { it is JsonPrimitive && it.isString } != true) bad(REQUIRED) }
        node[ITEMS]?.let { checkNode(tool, it as? JsonObject ?: bad(ITEMS), depth + 1) }
        node[ADDITIONAL_PROPERTIES]?.let { extra ->
            if (extra is JsonObject) checkNode(tool, extra, depth + 1) else if ((extra as? JsonPrimitive)?.booleanOrNull == null) bad(ADDITIONAL_PROPERTIES)
        }
        node[ENUM]?.let { if ((it as? JsonArray)?.isNotEmpty() != true) bad(ENUM) }
        (BOUNDS + COUNTS).forEach { key -> node[key]?.let { if ((it as? JsonPrimitive)?.number() == null) bad(key) } }
        COUNTS.forEach { key -> node.number(key)?.let { if (it.signum() < 0 || it.stripTrailingZeros().scale() > 0) bad(key) } }
    }

    private fun types(type: JsonElement): List<String> = when (type) {
        is JsonPrimitive -> if (type.isString) listOf(type.content) else emptyList()
        is JsonArray -> type.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: return emptyList() }
        else -> emptyList()
    }

    private fun matchesType(type: String, value: JsonElement): Boolean = when (type) {
        OBJECT -> value is JsonObject
        "array" -> value is JsonArray
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "number" -> value is JsonPrimitive && value.number() != null
        "integer" -> value is JsonPrimitive && value.number()?.let { it.stripTrailingZeros().scale() <= 0 } == true
        else -> false
    }

    private fun JsonPrimitive.number(): BigDecimal? = if (isString || this is JsonNull) null else content.toBigDecimalOrNull()

    private fun JsonObject.number(key: String): BigDecimal? = (this[key] as? JsonPrimitive)?.number()

    private fun path(at: String, name: String): String = if (at.isEmpty()) name else "$at.$name"

    private const val TYPE = "type"
    private const val OBJECT = "object"
    private const val PROPERTIES = "properties"
    private const val REQUIRED = "required"
    private const val ADDITIONAL_PROPERTIES = "additionalProperties"
    private const val ITEMS = "items"
    private const val ENUM = "enum"
    private const val CONST = "const"
    private const val MINIMUM = "minimum"
    private const val MAXIMUM = "maximum"
    private const val EXCLUSIVE_MINIMUM = "exclusiveMinimum"
    private const val EXCLUSIVE_MAXIMUM = "exclusiveMaximum"
    private const val MIN_LENGTH = "minLength"
    private const val MAX_LENGTH = "maxLength"
    private const val MIN_ITEMS = "minItems"
    private const val MAX_ITEMS = "maxItems"
    private val BOUNDS = setOf(MINIMUM, MAXIMUM, EXCLUSIVE_MINIMUM, EXCLUSIVE_MAXIMUM)
    private val COUNTS = setOf(MIN_LENGTH, MAX_LENGTH, MIN_ITEMS, MAX_ITEMS)
    private val TYPES = setOf(OBJECT, "array", "string", "number", "integer", "boolean", "null")
    private val ANNOTATIONS = setOf("description", "title", "default", "examples", "format", "\$schema")
    private val KEYWORDS = setOf(TYPE, PROPERTIES, REQUIRED, ADDITIONAL_PROPERTIES, ITEMS, ENUM, CONST) + BOUNDS + COUNTS + ANNOTATIONS
}
