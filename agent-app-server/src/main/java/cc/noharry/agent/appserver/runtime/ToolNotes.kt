package cc.noharry.agent.appserver.runtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** [RunAddOns.toolNote]: the argument's name, what the model is told about it, and the tools that take it. */
class ToolNote(val field: String, val description: String, val applies: (toolName: String) -> Boolean = { true }) {
    /** What the model wrote in this field of a call's arguments, or null. */
    fun of(argumentsJson: String): String? =
        (argumentsJson.jsonObject()?.get(field) as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

    /** These arguments without the field, as the tool gets them. */
    internal fun without(argumentsJson: String): String {
        val root = argumentsJson.jsonObject() ?: return argumentsJson
        return if (field in root) JsonObject(root - field).toString() else argumentsJson
    }

    /** These arguments with [text] in the field, as the model wrote them. */
    internal fun with(argumentsJson: String, text: String): String {
        val root = argumentsJson.jsonObject() ?: return argumentsJson
        return JsonObject(root + (field to JsonPrimitive(text))).toString()
    }

    /** A tool's parameter schema with the field added, or as it was when it is not an object schema. */
    internal fun inSchema(schemaJson: String): String {
        val root = schemaJson.jsonObject() ?: return schemaJson
        val properties = root["properties"] as? JsonObject ?: JsonObject(emptyMap())
        if (field in properties) return schemaJson
        val added = JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive(description)))
        return JsonObject(root + ("properties" to JsonObject(properties + (field to added)))).toString()
    }
}

/**
 * The notes the model wrote on its tool calls ([RunAddOns.toolNote]), taken off the calls before the runtime sees them
 * and kept here for the host, which asks by the call as it then starts: its tool and arguments.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
object ToolNotes {
    private val notes = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > MAX_NOTES
    }

    @Synchronized
    internal fun put(toolName: String, argumentsJson: String, text: String) { notes[key(toolName, argumentsJson)] = text }

    /** The note written on this call, if any. */
    @Synchronized
    fun of(toolName: String, argumentsJson: String): String? = notes[key(toolName, argumentsJson)]

    private fun key(toolName: String, argumentsJson: String): String =
        "$toolName ${runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull()?.sorted() ?: argumentsJson}"

    private const val MAX_NOTES = 64
}

private fun String.jsonObject(): JsonObject? = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull()

/** Key order is not part of a call's identity. */
private fun JsonElement.sorted(): JsonElement =
    if (this is JsonObject) JsonObject(entries.sortedBy { it.key }.associate { it.key to it.value.sorted() }) else this
