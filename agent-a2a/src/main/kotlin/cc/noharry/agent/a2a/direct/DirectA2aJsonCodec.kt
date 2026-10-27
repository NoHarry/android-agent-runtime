package cc.noharry.agent.a2a.direct

import cc.noharry.agent.a2a.A2aAgentCard
import cc.noharry.agent.a2a.A2aArtifact
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aSendMessageResult
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.A2aTask
import cc.noharry.agent.a2a.A2aValue
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object DirectA2aJsonCodec {
    val json = Json { ignoreUnknownKeys = true }

    fun rpcRequest(id: String, method: String, params: JsonElement): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    fun messageParams(message: A2aMessage): JsonObject = buildJsonObject {
        put("message", message.toJson())
    }

    fun taskParams(taskId: String): JsonObject = buildJsonObject { put("id", taskId) }

    fun decodeCard(root: JsonObject): A2aAgentCard {
        val name = root.requiredString("name")
        val url = URI(root.requiredString("url"))
        val capabilities = root["capabilities"] as? JsonObject
        return A2aAgentCard(
            name = name,
            description = root.string("description"),
            url = url,
            protocolVersion = root.string("protocolVersion"),
            streaming = capabilities?.get("streaming")?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    fun decodeSendResult(element: JsonElement): A2aSendMessageResult {
        val objectValue = element.jsonObject
        return if (isTask(objectValue)) {
            A2aSendMessageResult.Task(decodeTask(objectValue))
        } else {
            A2aSendMessageResult.Message(decodeMessage(objectValue))
        }
    }

    fun decodeTask(element: JsonElement): A2aTask {
        val root = element.jsonObject
        val statusElement = root["status"]
        val statusObject = statusElement as? JsonObject
        val state = statusObject?.string("state") ?: (statusElement as? JsonPrimitive)?.contentOrNull
        val message = statusObject?.get("message")?.let(::decodeMessage)
        return A2aTask(
            id = root.requiredString("id"),
            contextId = root.string("contextId"),
            status = decodeStatus(state),
            statusMessage = message,
            artifacts = (root["artifacts"] as? JsonArray)?.map(::decodeArtifact).orEmpty(),
        )
    }

    fun decodeStreamResult(element: JsonElement): Pair<List<A2aStreamEvent>, Boolean> {
        val root = element.jsonObject
        val taskPayload = root["task"] ?: root.takeIf(::isTask)
        if (taskPayload != null) {
            val task = decodeTask(taskPayload)
            return listOf(A2aStreamEvent.Task(task)) to task.status.isTerminal()
        }
        val messagePayload = root["message"] ?: root.takeIf { it["parts"] != null }
        if (messagePayload != null) {
            val final = root["final"]?.jsonPrimitive?.booleanOrNull ?: false
            return listOf(A2aStreamEvent.Message(decodeMessage(messagePayload))) to final
        }
        val status = root["status"]?.jsonObject
        if (status != null && root.string("taskId") != null) {
            val synthetic = buildJsonObject {
                put("id", root.requiredString("taskId"))
                root.string("contextId")?.let { put("contextId", it) }
                put("status", status)
            }
            val task = decodeTask(synthetic)
            val final = root["final"]?.jsonPrimitive?.booleanOrNull ?: task.status.isTerminal()
            return listOf(A2aStreamEvent.Task(task, root.objectMetadata())) to final
        }
        val artifact = root["artifact"] as? JsonObject
        if (artifact != null && root.string("taskId") != null) {
            val event = A2aStreamEvent.Artifact(
                taskId = root.requiredString("taskId"),
                contextId = root.string("contextId"),
                artifact = decodeArtifact(artifact),
                append = root["append"]?.jsonPrimitive?.booleanOrNull ?: false,
                lastChunk = root["lastChunk"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
            // An artifact never ends the task; only a final status or terminal task does.
            return listOf(event) to false
        }
        throw IllegalArgumentException("unsupported_stream_payload")
    }

    fun rpcError(root: JsonObject): Pair<Int, String>? {
        val error = root["error"] as? JsonObject ?: return null
        return (error["code"]?.jsonPrimitive?.intOrNull ?: -1) to "rpc_error"
    }

    fun rpcResult(root: JsonObject, expectedId: String): JsonElement {
        if (root.string("jsonrpc") != "2.0" || root.string("id") != expectedId) {
            throw IllegalArgumentException("rpc_envelope_mismatch")
        }
        return root["result"] ?: throw IllegalArgumentException("rpc_result_missing")
    }

    private fun A2aMessage.toJson(): JsonObject = buildJsonObject {
        messageId?.let { put("messageId", it) }
        put("role", role.name.lowercase())
        put("parts", buildJsonArray { parts.forEach { add(it.toJson()) } })
        contextId?.let { put("contextId", it) }
        taskId?.let { put("taskId", it) }
        metadata?.let { put("metadata", it.toJson()) }
    }

    private fun A2aPart.toJson(): JsonObject = when (this) {
        is A2aPart.Text -> buildJsonObject {
            put("kind", "text")
            put("text", text)
        }
        is A2aPart.Data -> buildJsonObject {
            put("kind", "data")
            put("data", value.toJson())
        }
    }

    private fun A2aValue.toJson(): JsonElement = when (this) {
        A2aValue.Null -> JsonNull
        is A2aValue.BooleanValue -> JsonPrimitive(value)
        is A2aValue.NumberValue -> JsonPrimitive(value)
        is A2aValue.StringValue -> JsonPrimitive(value)
        is A2aValue.ListValue -> JsonArray(values.map { it.toJson() })
        is A2aValue.ObjectValue -> JsonObject(values.mapValues { it.value.toJson() })
    }

    private fun decodeMessage(element: JsonElement): A2aMessage {
        val root = element.jsonObject
        val role = when (root.string("role")?.lowercase()) {
            "agent" -> A2aMessage.Role.AGENT
            else -> A2aMessage.Role.USER
        }
        return A2aMessage(
            messageId = root.string("messageId"),
            role = role,
            parts = root["parts"]?.jsonArray?.map(::decodePart).orEmpty(),
            contextId = root.string("contextId"),
            taskId = root.string("taskId"),
            metadata = root.objectMetadata(),
        )
    }

    private fun decodeArtifact(element: JsonElement): A2aArtifact {
        val root = element.jsonObject
        return A2aArtifact(
            artifactId = root.requiredString("artifactId"),
            name = root.string("name"),
            parts = root["parts"]?.jsonArray?.map(::decodePart).orEmpty(),
            metadata = root.objectMetadata(),
        )
    }

    /** Metadata is an object by contract; any other shape is ignored rather than guessed. */
    private fun JsonObject.objectMetadata(): A2aValue.ObjectValue? =
        (this["metadata"] as? JsonObject)?.let { decodeValue(it) as A2aValue.ObjectValue }

    private fun decodePart(element: JsonElement): A2aPart {
        val root = element.jsonObject
        return when (root.string("kind") ?: root.string("type")) {
            "data" -> A2aPart.Data(decodeValue(root["data"] ?: JsonNull))
            else -> A2aPart.Text(root.string("text").orEmpty())
        }
    }

    private fun decodeValue(element: JsonElement): A2aValue = when (element) {
        JsonNull -> A2aValue.Null
        is JsonArray -> A2aValue.ListValue(element.map(::decodeValue))
        is JsonObject -> A2aValue.ObjectValue(element.mapValues { decodeValue(it.value) })
        is JsonPrimitive -> when {
            element.isString -> A2aValue.StringValue(element.content)
            element.booleanOrNull != null -> A2aValue.BooleanValue(element.booleanOrNull!!)
            element.doubleOrNull != null -> A2aValue.NumberValue(element.doubleOrNull!!)
            else -> A2aValue.StringValue(element.content)
        }
    }

    private fun decodeStatus(value: String?): A2aTask.Status = when (value?.lowercase()) {
        "submitted" -> A2aTask.Status.SUBMITTED
        "working" -> A2aTask.Status.WORKING
        "input-required", "input_required" -> A2aTask.Status.INPUT_REQUIRED
        "completed" -> A2aTask.Status.COMPLETED
        "canceled", "cancelled" -> A2aTask.Status.CANCELLED
        "failed", "rejected" -> A2aTask.Status.FAILED
        else -> A2aTask.Status.UNKNOWN
    }

    private fun isTask(root: JsonObject): Boolean =
        root["status"] != null && root.string("id") != null

    private fun A2aTask.Status.isTerminal(): Boolean =
        this == A2aTask.Status.COMPLETED || this == A2aTask.Status.CANCELLED || this == A2aTask.Status.FAILED

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        string(name)?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("missing_$name")
}
