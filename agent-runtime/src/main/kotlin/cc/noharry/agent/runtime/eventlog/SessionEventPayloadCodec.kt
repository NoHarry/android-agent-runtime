@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionAttachmentRef
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections

internal fun SessionEventPayload.frozenCopy(): SessionEventPayload = when (this) {
    SessionEventPayload.Empty -> this
    is SessionEventPayload.UserInteraction -> copy(options = Collections.unmodifiableList(options.toList()))
    is SessionEventPayload.RunLifecycle -> copy()
    is SessionEventPayload.UserMessage -> copy(attachments = attachments.frozenAttachments())
    is SessionEventPayload.ModelRequest -> copy(
        messages = messages.frozenCopy(),
        tools = tools.frozenCopy(),
        options = options.frozenCopy(),
        attachments = attachments.frozenAttachments(),
    )
    is SessionEventPayload.AssistantChunk -> copy(metadata = metadata.frozenCopy())
    is SessionEventPayload.AssistantMessage -> copy(
        toolCalls = toolCalls.frozenCopy(),
        usage = usage.copy(extras = usage.extras.frozenCopy()),
        sourceChunkSequences = Collections.unmodifiableList(sourceChunkSequences.toList()),
    )
    is SessionEventPayload.ToolCall -> copy(arguments = arguments.frozenCopy())
    is SessionEventPayload.ToolResult -> copy(result = result.frozenCopy(), attachments = attachments.frozenAttachments())
    is SessionEventPayload.Usage -> copy(extras = extras.frozenCopy())
    is SessionEventPayload.ModelResponseMetadata -> copy(fields = fields.frozenCopy())
    is SessionEventPayload.ModelCallFinished -> copy()
}

internal fun validatePayload(payload: SessionEventPayload) {
    rejectCredentials(payload)
}

internal fun encodePayload(payload: SessionEventPayload): JsonObject = buildJsonObject {
    put("kind", payload.kindName())
    encodeKindFields(payload)
}

private fun JsonObjectBuilder.encodeKindFields(payload: SessionEventPayload) {
    when (payload) {
        SessionEventPayload.Empty -> Unit
        is SessionEventPayload.UserInteraction -> encodeUserInteraction(payload)
        is SessionEventPayload.RunLifecycle -> encodeRunLifecycle(payload)
        is SessionEventPayload.UserMessage -> encodeUserMessage(payload)
        is SessionEventPayload.ModelRequest -> encodeModelRequest(payload)
        is SessionEventPayload.AssistantChunk -> encodeAssistantChunk(payload)
        is SessionEventPayload.AssistantMessage -> encodeAssistantMessage(payload)
        is SessionEventPayload.ToolCall -> encodeToolCall(payload)
        is SessionEventPayload.ToolResult -> encodeToolResult(payload)
        is SessionEventPayload.Usage -> encodeUsage(payload)
        is SessionEventPayload.ModelResponseMetadata -> put("fields", encodeSessionJson(payload.fields))
        is SessionEventPayload.ModelCallFinished -> encodeModelCallFinished(payload)
    }
}

private fun JsonObjectBuilder.encodeRunLifecycle(payload: SessionEventPayload.RunLifecycle) {
    put("phase", payload.phase)
    put("status", payload.status)
    put("modelRef", payload.modelRef)
    put("scene", payload.scene)
    put("errorCode", payload.errorCode)
    put("todoCompletion", payload.todoCompletion)
}

private fun JsonObjectBuilder.encodeUserInteraction(payload: SessionEventPayload.UserInteraction) {
    put("interactionKind", payload.kind)
    put("state", payload.state)
    put("inputRequestId", payload.inputRequestId)
    put("questionId", payload.questionId)
    put("text", payload.text)
    put("options", JsonArray(payload.options.map(::JsonPrimitive)))
    put("reason", payload.reason)
}

private fun JsonObjectBuilder.encodeUserMessage(payload: SessionEventPayload.UserMessage) {
    put("text", payload.text)
    put("attachments", encodeAttachments(payload.attachments))
}

private fun JsonObjectBuilder.encodeModelRequest(payload: SessionEventPayload.ModelRequest) {
    put("messages", encodeSessionJson(payload.messages))
    put("systemPrompt", payload.systemPrompt)
    put("tools", encodeSessionJson(payload.tools))
    put("modelRef", payload.modelRef)
    put("toolChoice", payload.toolChoice)
    put("promptCacheKey", payload.promptCacheKey)
    put("options", encodeSessionJson(payload.options))
    put("attachments", encodeAttachments(payload.attachments))
}

private fun JsonObjectBuilder.encodeAssistantChunk(payload: SessionEventPayload.AssistantChunk) {
    put("chunkKind", payload.kind)
    put("text", payload.text)
    put("signature", payload.signature)
    put("metadata", encodeSessionJson(payload.metadata))
}

private fun JsonObjectBuilder.encodeAssistantMessage(payload: SessionEventPayload.AssistantMessage) {
    put("text", payload.text)
    put("toolCalls", encodeSessionJson(payload.toolCalls))
    put("finishReason", payload.finishReason)
    put("usage", encodePayload(payload.usage))
    put("sourceChunkSequences", encodeLongs(payload.sourceChunkSequences))
}

private fun JsonObjectBuilder.encodeToolCall(payload: SessionEventPayload.ToolCall) {
    put("toolCallId", payload.toolCallId)
    put("name", payload.name)
    put("arguments", encodeSessionJson(payload.arguments))
    put("schemaRef", payload.schemaRef)
    put("source", payload.source)
    put("todoCompletion", payload.todoCompletion)
}

private fun JsonObjectBuilder.encodeToolResult(payload: SessionEventPayload.ToolResult) {
    put("toolCallId", payload.toolCallId)
    put("name", payload.name)
    put("result", encodeSessionJson(payload.result))
    put("ok", payload.ok)
    put("code", payload.code)
    put("retryable", payload.retryable)
    put("source", payload.source)
    put("status", payload.status)
    put("todoCompletion", payload.todoCompletion)
    if (payload.attachments.isNotEmpty()) put("attachments", encodeAttachments(payload.attachments))
}

private fun JsonObjectBuilder.encodeUsage(payload: SessionEventPayload.Usage) {
    put("inputTokens", payload.inputTokens)
    put("outputTokens", payload.outputTokens)
    put("reasoningTokens", payload.reasoningTokens)
    put("cacheReadTokens", payload.cacheReadTokens)
    put("cacheWriteTokens", payload.cacheWriteTokens)
    put("extras", encodeSessionJson(payload.extras))
}

private fun JsonObjectBuilder.encodeModelCallFinished(payload: SessionEventPayload.ModelCallFinished) {
    put("callId", payload.callId)
    put("status", payload.status)
    put("source", payload.source)
    put("modelRef", payload.modelRef)
    put("durationMs", payload.durationMs)
    put("messageCount", payload.messageCount)
    put("toolCount", payload.toolCount)
    put("eventCount", payload.eventCount)
    put("errorCode", payload.errorCode)
}

internal fun decodePayload(payload: JsonObject): SessionEventPayload {
    val kind = payload.requiredTypedString("kind")
    return decodePayloadKind(kind, payload)
}

private fun decodePayloadKind(kind: String, payload: JsonObject): SessionEventPayload {
    return decodeKnownPayloadKind(kind, payload)
        ?: error("Unsupported session event payload kind")
}

private fun decodeKnownPayloadKind(kind: String, payload: JsonObject): SessionEventPayload? = when (kind) {
    "Empty" -> SessionEventPayload.Empty
    "UserInteraction" -> decodeUserInteraction(payload)
    "RunLifecycle" -> decodeRunLifecycle(payload)
    "UserMessage" -> SessionEventPayload.UserMessage(
        text = payload.requiredTypedString("text"),
        attachments = decodeAttachments(payload),
    )
    "ModelRequest" -> decodeModelRequest(payload)
    "AssistantChunk" -> decodeAssistantChunk(payload)
    "AssistantMessage" -> decodeAssistantMessage(payload)
    "ToolCall" -> decodeToolCall(payload)
    "ToolResult" -> decodeToolResult(payload)
    "Usage" -> decodeUsageObject(payload)
    "ModelResponseMetadata" -> SessionEventPayload.ModelResponseMetadata(
        fields = payload.optionalSessionJson("fields"),
    )
    "ModelCallFinished" -> decodeModelCallFinished(payload)
    else -> null
}

private fun decodeRunLifecycle(payload: JsonObject) = SessionEventPayload.RunLifecycle(
    phase = payload.optionalTypedString("phase").orEmpty(),
    status = payload.optionalTypedString("status").orEmpty(),
    modelRef = payload.optionalTypedString("modelRef").orEmpty(),
    scene = payload.optionalTypedString("scene").orEmpty(),
    errorCode = payload.optionalTypedString("errorCode").orEmpty(),
    todoCompletion = payload.optionalTypedString("todoCompletion").orEmpty(),
)

private fun decodeUserInteraction(payload: JsonObject) = SessionEventPayload.UserInteraction(
    kind = payload.requiredTypedString("interactionKind"),
    state = payload.requiredTypedString("state"),
    inputRequestId = payload.requiredTypedString("inputRequestId"),
    questionId = payload.requiredTypedString("questionId"),
    text = payload.requiredTypedString("text"),
    options = (payload["options"] as? JsonArray ?: error("Missing interaction options")).map {
        val option = it as? JsonPrimitive ?: error("Invalid interaction option")
        require(option.isString) { "Invalid interaction option" }
        option.content
    },
    reason = payload.requiredTypedString("reason"),
)

private fun decodeModelRequest(payload: JsonObject) = SessionEventPayload.ModelRequest(
    messages = payload.optionalSessionJson("messages"),
    systemPrompt = payload.optionalTypedString("systemPrompt").orEmpty(),
    tools = payload.optionalSessionJson("tools"),
    modelRef = payload.optionalTypedString("modelRef").orEmpty(),
    toolChoice = payload.optionalTypedString("toolChoice").orEmpty(),
    promptCacheKey = payload.optionalTypedString("promptCacheKey").orEmpty(),
    options = payload.optionalSessionJson("options"),
    attachments = decodeAttachments(payload),
)

private fun decodeAssistantChunk(payload: JsonObject) = SessionEventPayload.AssistantChunk(
    kind = payload.requiredTypedString("chunkKind"),
    text = payload.requiredTypedString("text"),
    signature = payload.optionalTypedString("signature").orEmpty(),
    metadata = payload.optionalSessionJson("metadata"),
)

private fun decodeAssistantMessage(payload: JsonObject) = SessionEventPayload.AssistantMessage(
    text = payload.optionalTypedString("text").orEmpty(),
    toolCalls = payload.optionalSessionJson("toolCalls"),
    finishReason = payload.optionalTypedString("finishReason").orEmpty(),
    usage = decodeUsage(payload["usage"]),
    sourceChunkSequences = payload.optionalTypedLongList("sourceChunkSequences"),
)

private fun decodeToolCall(payload: JsonObject) = SessionEventPayload.ToolCall(
    toolCallId = payload.requiredTypedString("toolCallId"),
    name = payload.requiredTypedString("name"),
    arguments = payload.optionalSessionJson("arguments"),
    schemaRef = payload.optionalTypedString("schemaRef").orEmpty(),
    source = payload.optionalTypedString("source").orEmpty(),
    todoCompletion = payload.optionalTypedString("todoCompletion").orEmpty(),
)

private fun decodeToolResult(payload: JsonObject) = SessionEventPayload.ToolResult(
    toolCallId = payload.requiredTypedString("toolCallId"),
    name = payload.requiredTypedString("name"),
    result = payload.optionalSessionJson("result"),
    ok = payload.optionalTypedBoolean("ok") ?: true,
    code = payload.optionalTypedString("code").orEmpty(),
    retryable = payload.optionalTypedBoolean("retryable") ?: false,
    source = payload.optionalTypedString("source").orEmpty(),
    status = payload.optionalTypedString("status").orEmpty(),
    todoCompletion = payload.optionalTypedString("todoCompletion").orEmpty(),
    attachments = decodeAttachments(payload),
)

private fun decodeModelCallFinished(payload: JsonObject) = SessionEventPayload.ModelCallFinished(
    callId = payload.optionalTypedString("callId").orEmpty(),
    status = payload.optionalTypedString("status").orEmpty(),
    source = payload.optionalTypedString("source").orEmpty(),
    modelRef = payload.optionalTypedString("modelRef").orEmpty(),
    durationMs = payload.optionalTypedLong("durationMs") ?: 0L,
    messageCount = payload.optionalTypedInt("messageCount") ?: 0,
    toolCount = payload.optionalTypedInt("toolCount") ?: 0,
    eventCount = payload.optionalTypedInt("eventCount") ?: 0,
    errorCode = payload.optionalTypedString("errorCode").orEmpty(),
)

internal fun textToSessionJson(text: String): SessionJson =
    if (text.isBlank()) SessionJson.Null else SessionJson.Text(text)

internal fun parseJsonOrText(raw: String): SessionJson {
    if (raw.isBlank()) return SessionJson.Null
    return try {
        jsonElementToSessionJson(kotlinx.serialization.json.Json.parseToJsonElement(raw))
    } catch (_: Exception) {
        SessionJson.Text(raw)
    }
}

internal fun jsonElementToSessionJson(element: JsonElement): SessionJson = when (element) {
    JsonNull -> SessionJson.Null
    is JsonPrimitive -> when {
        element.isString -> SessionJson.Text(element.content)
        element.booleanOrNull != null -> SessionJson.Bool(element.booleanOrNull!!)
        else -> SessionJson.Number(element.content)
    }
    is JsonArray -> SessionJson.Array(element.map(::jsonElementToSessionJson))
    is JsonObject -> SessionJson.Object(
        element.entries.associate { (key, value) -> key to jsonElementToSessionJson(value) },
    )
}

private fun decodeUsage(element: JsonElement?): SessionEventPayload.Usage {
    val obj = element as? JsonObject ?: return SessionEventPayload.Usage()
    return decodeUsageObject(obj)
}

private fun decodeUsageObject(payload: JsonObject): SessionEventPayload.Usage =
    SessionEventPayload.Usage(
        inputTokens = payload.optionalTypedLong("inputTokens") ?: 0L,
        outputTokens = payload.optionalTypedLong("outputTokens") ?: 0L,
        reasoningTokens = payload.optionalTypedLong("reasoningTokens") ?: 0L,
        cacheReadTokens = payload.optionalTypedLong("cacheReadTokens") ?: 0L,
        cacheWriteTokens = payload.optionalTypedLong("cacheWriteTokens") ?: 0L,
        extras = payload.optionalSessionJson("extras"),
    )

private fun rejectCredentials(payload: SessionEventPayload) {
    when (payload) {
        SessionEventPayload.Empty -> Unit
        is SessionEventPayload.UserInteraction -> {
            (listOf(payload.kind, payload.state, payload.inputRequestId, payload.questionId, payload.text, payload.reason) +
                payload.options).forEach(::rejectCredentialText)
        }
        is SessionEventPayload.RunLifecycle -> {
            rejectCredentialText(payload.phase)
            rejectCredentialText(payload.status)
            rejectCredentialText(payload.modelRef)
            rejectCredentialText(payload.scene)
            rejectCredentialText(payload.errorCode)
        }
        is SessionEventPayload.UserMessage -> rejectCredentialText(payload.text)
        is SessionEventPayload.ModelRequest -> {
            rejectCredentialJson(payload.messages)
            rejectCredentialJson(payload.tools)
            rejectCredentialJson(payload.options)
            rejectCredentialText(payload.systemPrompt)
            rejectCredentialText(payload.promptCacheKey)
        }
        is SessionEventPayload.AssistantChunk -> {
            rejectCredentialText(payload.text)
            rejectCredentialJson(payload.metadata)
        }
        is SessionEventPayload.AssistantMessage -> {
            rejectCredentialText(payload.text)
            rejectCredentialJson(payload.toolCalls)
        }
        is SessionEventPayload.ToolCall -> {
            rejectCredentialText(payload.toolCallId)
            rejectCredentialJson(payload.arguments)
        }
        is SessionEventPayload.ToolResult -> {
            rejectCredentialText(payload.toolCallId)
            rejectCredentialJson(payload.result)
        }
        is SessionEventPayload.Usage -> rejectCredentialJson(payload.extras)
        is SessionEventPayload.ModelResponseMetadata -> rejectCredentialJson(payload.fields)
        is SessionEventPayload.ModelCallFinished -> {
            rejectCredentialText(payload.callId)
            rejectCredentialText(payload.errorCode)
        }
    }
}

private fun rejectCredentialJson(value: SessionJson) {
    when (value) {
        SessionJson.Null, is SessionJson.Bool, is SessionJson.Number -> Unit
        is SessionJson.Text -> rejectCredentialText(value.value)
        is SessionJson.Array -> value.values.forEach(::rejectCredentialJson)
        is SessionJson.Object -> value.values.forEach { (key, child) ->
            require(CREDENTIAL_KEY_TOKENS.none { token -> key.contains(token, ignoreCase = true) }) {
                "payload must not persist API keys, Authorization headers, or transport credentials"
            }
            rejectCredentialJson(child)
        }
    }
}

private fun rejectCredentialText(value: String) {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return
    require(CREDENTIAL_VALUE_MARKERS.none { marker -> trimmed.startsWith(marker, ignoreCase = true) }) {
        "payload must not persist API keys, Authorization headers, or transport credentials"
    }
}

private fun SessionEventPayload.kindName(): String = when (this) {
    SessionEventPayload.Empty -> "Empty"
    is SessionEventPayload.UserInteraction -> "UserInteraction"
    is SessionEventPayload.RunLifecycle -> "RunLifecycle"
    is SessionEventPayload.UserMessage -> "UserMessage"
    is SessionEventPayload.ModelRequest -> "ModelRequest"
    is SessionEventPayload.AssistantChunk -> "AssistantChunk"
    is SessionEventPayload.AssistantMessage -> "AssistantMessage"
    is SessionEventPayload.ToolCall -> "ToolCall"
    is SessionEventPayload.ToolResult -> "ToolResult"
    is SessionEventPayload.Usage -> "Usage"
    is SessionEventPayload.ModelResponseMetadata -> "ModelResponseMetadata"
    is SessionEventPayload.ModelCallFinished -> "ModelCallFinished"
}

private fun encodeSessionJson(value: SessionJson): JsonElement = when (value) {
    SessionJson.Null -> JsonNull
    is SessionJson.Bool -> JsonPrimitive(value.value)
    is SessionJson.Number -> kotlinx.serialization.json.Json.parseToJsonElement(value.literal)
    is SessionJson.Text -> JsonPrimitive(value.value)
    is SessionJson.Array -> buildJsonArray { value.values.forEach { add(encodeSessionJson(it)) } }
    is SessionJson.Object -> buildJsonObject {
        value.values.forEach { (key, child) -> put(key, encodeSessionJson(child)) }
    }
}

private fun encodeAttachments(values: List<SessionAttachmentRef>): JsonArray = buildJsonArray {
    values.forEach { ref ->
        add(
            buildJsonObject {
                put("sha256", ref.sha256)
                put("mediaType", ref.mediaType)
                put("byteSize", ref.byteSize)
                put("logicalName", ref.logicalName)
            },
        )
    }
}

private fun encodeLongs(values: List<Long>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun decodeAttachments(payload: JsonObject): List<SessionAttachmentRef> {
    val values = payload["attachments"] as? JsonArray ?: return emptyList()
    return values.map { element ->
        val obj = element as? JsonObject ?: error("attachment must be an object")
        SessionAttachmentRef(
            sha256 = obj.requiredTypedString("sha256"),
            mediaType = obj.optionalTypedString("mediaType").orEmpty(),
            byteSize = obj.optionalTypedLong("byteSize") ?: 0L,
            logicalName = obj.optionalTypedString("logicalName").orEmpty(),
        )
    }
}

private fun SessionJson.frozenCopy(): SessionJson = when (this) {
    SessionJson.Null, is SessionJson.Bool, is SessionJson.Number, is SessionJson.Text -> this
    is SessionJson.Array -> SessionJson.Array(Collections.unmodifiableList(values.map { it.frozenCopy() }))
    is SessionJson.Object -> SessionJson.Object(
        Collections.unmodifiableMap(LinkedHashMap(values.mapValues { it.value.frozenCopy() })),
    )
}

private fun List<SessionAttachmentRef>.frozenAttachments(): List<SessionAttachmentRef> =
    Collections.unmodifiableList(map { it.copy() })

private fun JsonObject.optionalSessionJson(key: String): SessionJson {
    val element = this[key] ?: return SessionJson.Null
    return jsonElementToSessionJson(element)
}

internal fun JsonObject.requiredTypedString(key: String): String {
    val primitive = this[key] as? JsonPrimitive ?: error("Missing string $key")
    if (!primitive.isString) error("$key must be a json string")
    return primitive.content
}

internal fun JsonObject.optionalTypedString(key: String): String? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: error("$key must be a json string")
    if (!primitive.isString) error("$key must be a json string")
    return primitive.content
}

internal fun JsonObject.optionalTypedLong(key: String): Long? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: error("$key must be a numeric primitive")
    if (primitive.isString) error("$key must be a numeric primitive")
    return primitive.content.toLongOrNull() ?: error("$key must be a numeric primitive")
}

internal fun JsonObject.optionalTypedInt(key: String): Int? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: error("$key must be a numeric primitive")
    if (primitive.isString) error("$key must be a numeric primitive")
    return primitive.content.toIntOrNull() ?: error("$key must be a numeric primitive")
}

internal fun JsonObject.optionalTypedBoolean(key: String): Boolean? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: error("$key must be a boolean primitive")
    return primitive.booleanOrNull ?: error("$key must be a boolean primitive")
}

internal fun JsonObject.optionalTypedLongList(key: String): List<Long> {
    val values = this[key] as? JsonArray ?: return emptyList()
    return values.map { element ->
        val primitive = element as? JsonPrimitive ?: error("$key values must be numeric primitives")
        if (primitive.isString) error("$key values must be numeric primitives")
        primitive.content.toLongOrNull() ?: error("$key values must be longs")
    }
}

private val CREDENTIAL_KEY_TOKENS = listOf(
    "authorization",
    "api_key",
    "apikey",
    "x-api-key",
    "x_api_key",
    "bearer",
    "cookie",
    "set-cookie",
    "access_token",
    "secret",
    "password",
)

private val CREDENTIAL_VALUE_MARKERS = listOf(
    "Bearer ",
    "sk-",
)
