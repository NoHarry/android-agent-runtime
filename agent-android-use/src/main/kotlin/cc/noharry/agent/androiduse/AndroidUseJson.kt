package cc.noharry.agent.androiduse

import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.model.ModelImage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun parseArguments(argumentsJson: String): JsonObject? =
    runCatching { Json.parseToJsonElement(argumentsJson.ifBlank { "{}" }).jsonObject }.getOrNull()

internal fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

internal fun JsonObject.int(name: String): Int? = this[name]?.jsonPrimitive?.intOrNull

internal fun JsonObject.long(name: String): Long? = this[name]?.jsonPrimitive?.longOrNull

internal fun JsonObject.double(name: String): Double? = this[name]?.jsonPrimitive?.doubleOrNull

/** A successful result; the observation's screenshot rides along as an image. */
internal fun successResult(observation: Observation, fields: JsonObjectBuilder.() -> Unit = {}): ToolCallResult =
    ToolCallResult(
        success = true,
        resultJson = buildJsonObject {
            put("ok", true)
            fields()
            put("observation", observation.toJson())
        }.toString(),
        images = observation.screenshotImages(),
    )

/** A failed result; [observation] is attached when one could be taken, so the model can go on. */
internal fun failureResult(code: String, message: String, observation: Observation? = null): ToolCallResult =
    ToolCallResult(
        success = false,
        resultJson = buildJsonObject {
            put("ok", false)
            put("code", code)
            put("message", message)
            observation?.let { put("observation", it.toJson()) }
        }.toString(),
        images = observation?.screenshotImages().orEmpty(),
    )

internal fun Observation.toJson(): JsonObject = buildJsonObject {
    put("observationId", id)
    put("displayId", state.displayId)
    put("packageName", state.packageName)
    put("coordinates", if (capture != null) "screenshot pixels" else "screen pixels")
    capture?.let { image ->
        put("image", buildJsonObject {
            put("width", image.width)
            put("height", image.height)
        })
    }
    put("screenshot", screenshotNote)
    putJsonArray("elements") { elements.forEach { add(it.toJson(this@toJson)) } }
    put("elementsTruncated", elementsTruncated)
    if (state.systemAreas.isNotEmpty()) {
        putJsonArray("blockedAreas") {
            state.systemAreas.forEach { area ->
                val scaled = area.scaled(scale)
                add(buildJsonArray { add(scaled.left); add(scaled.top); add(scaled.right); add(scaled.bottom) })
            }
        }
    }
}

private fun ObservedElement.toJson(observation: Observation): JsonObject = buildJsonObject {
    val scale = observation.scale
    put("id", id)
    node.text.takeIf { it.isNotBlank() }?.let { put("text", it.take(MAX_ELEMENT_TEXT)) }
    readableDescription(node.description).takeIf { it.isNotBlank() }?.let { put("desc", it.take(MAX_ELEMENT_TEXT)) }
    node.viewId.substringAfter(VIEW_ID_SEPARATOR).takeIf { it.isNotBlank() }?.let { put("viewId", it) }
    if (node.clickable || node.inClickableContext) put("clickable", true)
    if (node.editable) put("editable", true)
    if (node.scrollable) put("scrollable", true)
    if (node.isSlider()) {
        put("role", "slider")
        observation.labelFor(node).takeIf { it.isNotBlank() }?.let { put("label", it) }
    }
    node.range?.let { range ->
        put("range", buildJsonObject {
            put("min", range.min)
            put("max", range.max)
            put("value", range.current)
        })
    }
    val bounds = node.bounds.scaled(scale)
    putJsonArray("bounds") {
        add(bounds.left)
        add(bounds.top)
        add(bounds.right)
        add(bounds.bottom)
    }
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: Int) {
    add(kotlinx.serialization.json.JsonPrimitive(value))
}

private fun Observation.screenshotImages(): List<ModelImage> =
    capture?.let { image ->
        val extension = if (image.mediaType == MEDIA_TYPE_PNG) "png" else "jpg"
        listOf(ModelImage(image.bytes, image.mediaType, "android-use/$id.$extension"))
    }.orEmpty()

internal const val MEDIA_TYPE_JPEG = "image/jpeg"
internal const val MEDIA_TYPE_PNG = "image/png"
private const val MAX_ELEMENT_TEXT = 60
