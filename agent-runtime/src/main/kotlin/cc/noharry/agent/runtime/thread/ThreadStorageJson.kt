package cc.noharry.agent.runtime.thread

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

internal fun JsonObject.text(key: String): String = optionalText(key).orEmpty()
internal fun JsonObject.optionalText(key: String): String? = (get(key) as? JsonPrimitive)
    ?.takeUnless { it is JsonNull }?.content
internal fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.intOrNull ?: 0
internal fun JsonObject.long(key: String): Long = optionalLong(key) ?: 0L
internal fun JsonObject.optionalLong(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull
internal fun JsonObject.flag(key: String): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull == true
internal fun JsonObject.array(key: String): List<JsonElement> = (get(key) as? JsonArray).orEmpty()
internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal inline fun <reified T : Enum<T>> JsonObject.enum(key: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == text(key) } ?: default
