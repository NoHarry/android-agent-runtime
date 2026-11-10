package cc.noharry.agent.acp

import cc.noharry.agent.InputPart
import cc.noharry.agent.ItemStatus
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.TurnInput
import cc.noharry.agent.spi.ToolSource
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A prompt's content blocks as a turn's input: text, images, embedded text resources and links to resources. */
@OptIn(ExperimentalEncodingApi::class)
internal fun promptInput(blocks: JsonArray): TurnInput {
    val parts = blocks.map { element ->
        val block = element as? JsonObject ?: throw AcpError(INVALID_PARAMS, "A prompt block must be an object")
        when (val type = block.text("type")) {
            "text" -> InputPart.Text(block.requireText("text"))
            "image" -> try {
                InputPart.Image(Base64.decode(block.requireText("data")), block.requireText("mimeType"))
            } catch (_: IllegalArgumentException) {
                throw AcpError(INVALID_PARAMS, "The image data is not valid base64")
            }
            "resource" -> InputPart.Text(embeddedResource(block))
            "resource_link" -> InputPart.Text(listOfNotNull(block.text("title") ?: block.text("name"), block.requireText("uri")).joinToString(": "))
            else -> throw AcpError(INVALID_PARAMS, "Unsupported prompt content: $type")
        }
    }
    if (parts.isEmpty()) throw AcpError(INVALID_PARAMS, "The prompt is empty")
    return TurnInput(parts)
}

private fun embeddedResource(block: JsonObject): String {
    val resource = block["resource"] as? JsonObject ?: throw AcpError(INVALID_PARAMS, "resource is required")
    val text = resource.text("text") ?: throw AcpError(INVALID_PARAMS, "Only text resources can be embedded")
    return "${resource.requireText("uri")}\n$text"
}

internal fun textBlock(text: String): JsonObject = buildJsonObject { put("type", "text"); put("text", text) }

internal fun ThreadItem.UserMessage.text(): String =
    input.filterIsInstance<InputPart.Text>().joinToString("\n") { it.text }

/** `finish` and the question tools: their effect shows as the answer or the question, not as a tool call. */
internal fun ThreadItem.ToolCall.hidden(): Boolean = source == ToolSource.TERMINAL || source == ToolSource.INTERACTION

internal fun ThreadItem.ToolCall.kind(): String = when (source) {
    ToolSource.NATIVE_READ -> if (tool == "read_file") "read" else "search"
    ToolSource.NATIVE_WRITE -> "edit"
    ToolSource.NETWORK -> if (tool == "web_search") "search" else "fetch"
    ToolSource.EXEC -> "execute"
    else -> "other"
}

internal fun ItemStatus.toolStatus(): String = when (this) {
    ItemStatus.IN_PROGRESS -> "in_progress"
    ItemStatus.COMPLETED -> "completed"
    ItemStatus.FAILED, ItemStatus.DECLINED -> "failed"
}

/** The fields of a `tool_call` or `tool_call_update` that describe [call] as it is now. */
internal fun toolCallFields(call: ThreadItem.ToolCall, announce: Boolean): JsonObject = buildJsonObject {
    put("toolCallId", call.id)
    if (announce) {
        put("title", call.server?.let { "$it: ${call.tool.substringAfterLast("__")}" } ?: call.tool)
        put("kind", call.kind())
        put("rawInput", jsonOrText(call.arguments))
    }
    put("status", call.status.toolStatus())
    call.output?.let { output ->
        put("rawOutput", jsonOrText(output))
        put("content", buildJsonArray { add(buildJsonObject { put("type", "content"); put("content", textBlock(output)) }) })
    }
}

/** The plan `update_todo` wrote: every entry, as ACP wants a plan sent; null when its arguments hold no list. */
internal fun planEntries(call: ThreadItem.ToolCall): JsonArray? {
    val todos = (jsonOrText(call.arguments) as? JsonObject)?.get("todos") as? JsonArray ?: return null
    return buildJsonArray {
        todos.filterIsInstance<JsonObject>().forEach { todo ->
            add(buildJsonObject {
                put("content", todo.text("content").orEmpty())
                put("priority", "medium")
                put("status", todo.text("status")?.takeIf { it in PLAN_STATUSES } ?: "pending")
            })
        }
    }
}

private val PLAN_STATUSES = setOf("pending", "in_progress", "completed")

private fun jsonOrText(value: String): JsonElement =
    try { Json.parseToJsonElement(value) } catch (_: IllegalArgumentException) { JsonPrimitive(value) }
