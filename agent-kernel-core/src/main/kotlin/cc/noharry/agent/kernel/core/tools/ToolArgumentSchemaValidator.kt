package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun validateBackendToolArguments(
    toolName: String,
    argumentsJson: String,
    schemaJson: String?,
): ToolArgumentValidationResult {
    val arguments = when (val parsed = parseToolArguments(toolName, argumentsJson)) {
        is ParsedToolArguments.Invalid -> return ToolArgumentValidationResult.Invalid(parsed.resultJson)
        is ParsedToolArguments.Valid -> parsed.arguments
    }
    return validateBackendToolArguments(toolName, arguments, schemaJson)
}

fun validateBackendToolArguments(
    toolName: String,
    arguments: JsonObject,
    schemaJson: String?,
): ToolArgumentValidationResult {
    val schema = schemaJson?.takeIf { it.isNotBlank() }?.let(::parseJsonObject)
        ?: return ToolArgumentValidationResult.Valid(arguments)
    if (schema.isEmpty()) return ToolArgumentValidationResult.Valid(arguments)
    return validateObjectAgainstSchema(toolName, arguments, schema)
}

sealed interface ToolArgumentValidationResult {
    data class Valid(val arguments: JsonObject) : ToolArgumentValidationResult

    data class Invalid(
        private val code: String,
        private val message: String,
        private val explicitResultJson: String? = null,
    ) : ToolArgumentValidationResult {
        constructor(resultJson: String) : this("", "", resultJson)

        fun resultJson(): String =
            explicitResultJson
                ?: """{"ok":false,"code":"${escapeJson(code)}","message":"${escapeJson(message)}","retryable":false}"""
    }
}

private fun validateObjectAgainstSchema(
    toolName: String,
    arguments: JsonObject,
    schema: JsonObject,
): ToolArgumentValidationResult {
    val rootTypes = schema.schemaTypes()
    if (rootTypes.isNotEmpty() && "object" !in rootTypes) {
        return invalidSchema(toolName, "Input schema root type must allow object.")
    }
    requiredFields(schema).firstOrNull { it !in arguments }?.let { missing ->
        return invalidArguments(toolName, "Missing required property '$missing'.")
    }
    val properties = schema["properties"] as? JsonObject
    return validateProperties(toolName, arguments, properties, schema)
}

private fun validateProperties(
    toolName: String,
    arguments: JsonObject,
    properties: JsonObject?,
    schema: JsonObject,
    pathPrefix: String = "",
): ToolArgumentValidationResult {
    val allowExtra = schema.booleanField("additionalProperties") ?: true
    for ((name, value) in arguments) {
        val propertySchema = properties?.get(name) as? JsonObject
        if (!allowExtra && propertySchema == null) {
            return invalidArguments(toolName, "Unexpected property '$name'.")
        }
        propertySchema?.let { property ->
            val path = if (pathPrefix.isEmpty()) name else "$pathPrefix.$name"
            validateValue(toolName, path, value, property)?.let { return it }
        }
    }
    return ToolArgumentValidationResult.Valid(arguments)
}

private fun validateValue(
    toolName: String,
    path: String,
    value: JsonElement,
    schema: JsonObject,
): ToolArgumentValidationResult.Invalid? {
    validateType(toolName, path, value, schema)?.let { return it }
    validateEnum(toolName, path, value, schema)?.let { return it }
    validateMinimum(toolName, path, value, schema)?.let { return it }
    return when {
        value is JsonObject -> validateNestedObject(toolName, path, value, schema)
        value is JsonArray -> validateArrayItems(toolName, path, value, schema)
        else -> null
    }
}

private fun validateNestedObject(
    toolName: String,
    path: String,
    value: JsonObject,
    schema: JsonObject,
): ToolArgumentValidationResult.Invalid? {
    val required = requiredFields(schema)
    required.firstOrNull { it !in value }?.let { missing ->
        return invalidArguments(toolName, "Missing required property '$path.$missing'.")
    }
    val properties = schema["properties"] as? JsonObject
    val result = validateProperties(toolName, value, properties, schema, path)
    return result as? ToolArgumentValidationResult.Invalid
}

private fun validateArrayItems(
    toolName: String,
    path: String,
    value: JsonArray,
    schema: JsonObject,
): ToolArgumentValidationResult.Invalid? {
    validateArraySize(toolName, path, value.size, schema)?.let { return it }
    val itemSchema = schema["items"] as? JsonObject ?: return null
    value.forEachIndexed { index, item ->
        validateValue(toolName, "$path[$index]", item, itemSchema)?.let { return it }
    }
    return null
}

private fun validateArraySize(
    toolName: String,
    path: String,
    size: Int,
    schema: JsonObject,
): ToolArgumentValidationResult.Invalid? {
    for (keyword in listOf("minItems", "maxItems")) {
        if (keyword !in schema) continue
        val limit = (schema[keyword] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        if (limit == null || limit < 0) return invalidSchema(toolName, "$path.$keyword must be a nonnegative integer.")
        if ((keyword == "minItems" && size < limit) || (keyword == "maxItems" && size > limit)) {
            return invalidArguments(toolName, "Property '$path' violates $keyword=$limit (actual items=$size).")
        }
    }
    return null
}

private fun validateType(
    toolName: String,
    path: String,
    value: JsonElement,
    schema: JsonObject,
): ToolArgumentValidationResult.Invalid? {
    val types = schema.schemaTypes()
    if (types.isEmpty() || types.any { value.matchesJsonType(it) }) return null
    return invalidArguments(toolName, "Property '$path' must be ${types.joinToString(" or ")}.")
}

private fun validateEnum(
    toolName: String,
    path: String,
    value: JsonElement,
    schema: JsonObject,
): ToolArgumentValidationResult.Invalid? {
    val allowed = schema["enum"] as? JsonArray ?: return null
    if (allowed.any { it == value }) return null
    return invalidArguments(toolName, "Property '$path' must match one of the declared enum values.")
}

private fun validateMinimum(
    toolName: String,
    path: String,
    value: JsonElement,
    schema: JsonObject,
): ToolArgumentValidationResult.Invalid? {
    val minimum = (schema["minimum"] as? JsonPrimitive)?.doubleOrNull ?: return null
    val actual = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
    return if (actual < minimum) {
        invalidArguments(toolName, "Property '$path' must be >= $minimum.")
    } else {
        null
    }
}

private fun JsonElement.matchesJsonType(type: String): Boolean = when (type) {
    "object" -> this is JsonObject
    "array" -> this is JsonArray
    "string" -> (this as? JsonPrimitive)?.isString == true
    "boolean" -> (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null
    "integer" -> (this as? JsonPrimitive)?.isIntegerNumber() == true
    "number" -> (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull != null
    "null" -> this is JsonNull
    else -> true
}

private fun JsonPrimitive.isIntegerNumber(): Boolean =
    !isString && contentOrNull?.let { INTEGER_NUMBER.matches(it) } == true

private fun JsonObject.schemaTypes(): Set<String> = when (val type = this["type"]) {
    is JsonPrimitive -> type.contentOrNull?.let(::setOf).orEmpty()
    is JsonArray -> type.mapNotNullTo(linkedSetOf()) { (it as? JsonPrimitive)?.contentOrNull }
    else -> emptySet()
}

private fun requiredFields(schema: JsonObject): List<String> =
    (schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        .orEmpty()

private fun JsonObject.booleanField(name: String): Boolean? =
    (this[name] as? JsonPrimitive)?.booleanOrNull

private fun parseJsonObject(value: String): JsonObject? =
    (parseToolArguments("input_schema", value) as? ParsedToolArguments.Valid)?.arguments

private fun invalidArguments(toolName: String, message: String): ToolArgumentValidationResult.Invalid =
    ToolArgumentValidationResult.Invalid(
        RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode,
        "Tool $toolName argument validation failed. $message",
    )

private fun invalidSchema(toolName: String, message: String): ToolArgumentValidationResult.Invalid =
    ToolArgumentValidationResult.Invalid(
        RuntimeErrorCode.INVALID_TOOL_SCHEMA.wireCode,
        "Tool $toolName has an invalid input schema. $message",
    )

private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private val INTEGER_NUMBER = Regex("""-?(0|[1-9][0-9]*)""")
