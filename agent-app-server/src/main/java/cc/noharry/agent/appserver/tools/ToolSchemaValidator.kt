package cc.noharry.agent.appserver.tools
import cc.noharry.agent.appserver.protocol.view.parseJsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@cc.noharry.agent.appserver.AppServerPluginApi
fun validateInputSchema(inputSchemaJson: String, argumentsJson: String): SchemaValidation {
    val arguments = parseJsonObject(argumentsJson) ?: return SchemaValidation.Invalid(
        code = "SCHEMA_VALIDATION_FAILED",
        message = "Tool arguments must be a JSON object.",
        schemaPath = "/arguments",
    )
    val schema = parseJsonObject(inputSchemaJson) ?: return SchemaValidation.Invalid(
        code = "INVALID_TOOL_SCHEMA",
        message = "Tool schema must be a JSON object.",
    )
    return validateObjectSchema(schema, arguments)
}

private fun validateObjectSchema(schema: JsonObject, arguments: JsonObject): SchemaValidation {
    requiredFields(schema).firstOrNull { !arguments.containsKey(it) }?.let { missing ->
        return SchemaValidation.Invalid(
            code = "SCHEMA_VALIDATION_FAILED",
            message = "Missing required field: $missing",
            schemaPath = "/required",
        )
    }
    val properties = schema["properties"] as? JsonObject ?: return SchemaValidation.Valid
    for ((name, propertySchema) in properties) {
        val value = arguments[name] ?: continue
        val property = propertySchema as? JsonObject ?: continue
        property.stringField("type")?.let { expectedType ->
            if (!matchesType(value, expectedType)) {
                return SchemaValidation.Invalid(
                    code = "SCHEMA_VALIDATION_FAILED",
                    message = "Field $name must be $expectedType.",
                    schemaPath = "/properties/$name/type",
                )
            }
        }
        val allowedValues = property["enum"] as? JsonArray ?: continue
        if (allowedValues.none { it == value }) {
            return SchemaValidation.Invalid(
                code = "SCHEMA_VALIDATION_FAILED",
                message = "Field $name must be one of the declared enum values.",
                schemaPath = "/properties/$name/enum",
            )
        }
    }
    return SchemaValidation.Valid
}

private fun requiredFields(schema: JsonObject): List<String> =
    (schema["required"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }

private fun matchesType(value: JsonElement, expectedType: String): Boolean =
    when (expectedType) {
        "string" -> value is JsonPrimitive && value.isString
        "number" -> value is JsonPrimitive && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && value.intOrNull != null
        "boolean" -> value is JsonPrimitive && value.booleanOrNull != null
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        else -> true
    }

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

@cc.noharry.agent.appserver.AppServerPluginApi
sealed interface SchemaValidation {
    data object Valid : SchemaValidation

    data class Invalid(
        val code: String,
        val message: String,
        val schemaPath: String? = null,
    ) : SchemaValidation
}
