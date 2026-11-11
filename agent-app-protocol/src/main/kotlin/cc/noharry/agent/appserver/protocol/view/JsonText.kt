package cc.noharry.agent.appserver.protocol.view

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** [content] as a JSON object, or null when it is not one. */
fun parseJsonObject(content: String): JsonObject? =
    try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
