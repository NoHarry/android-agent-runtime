@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.model

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata

internal fun openAiRequest(
    apiKey: String,
    baseUrl: String,
    model: String,
    request: ModelRequest,
    stream: Boolean = false,
    serverSideSearch: Boolean = false,
    searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_CHAT_OPTIONS,
): HttpRequest = providerRequest(
    url = endpoint(baseUrl, "/chat/completions"),
    headers = mapOf("Authorization" to "Bearer $apiKey"),
    body = buildJsonObject {
        val options = request.modelRef.providerOptions(OPENAI_PROVIDER_NAME, model)
        put("model", model)
        modelOutputTokenLimit(model)?.let { put("max_tokens", it) }
        put("stream", stream)
        if (stream) {
            put("stream_options", buildJsonObject { put("include_usage", true) })
        }
        // DeepSeek: thinking.type enabled/disabled (extra_body in SDK; top-level in raw HTTP).
        options.thinkingType?.let { type ->
            put("thinking", buildJsonObject { put("type", type) })
        }
        options.reasoningEffort?.let { put("reasoning_effort", it) }
        putOpenAiServerSideSearch(serverSideSearch, searchCapability)
        putOpenAiMessages(request, model)
        putOpenAiTools(request.tools)
    },
)

internal fun openAiEvents(response: HttpResponse): List<ModelEvent> {
    requireProviderSuccess(OPENAI_PROVIDER_NAME, response)
    val root = parseProviderObject(OPENAI_PROVIDER_NAME, response.body.toString(Charsets.UTF_8))
    val message = root
        .array("choices")
        ?.firstObject()
        ?.obj("message")
    val usage = root.openAiUsageDelta()
    val metadata = root.modelResponseMetadata()
    if (message == null && usage == null && metadata == null) return emptyList()
    return buildList {
        metadata?.let { add(it) }
        addUsage(usage)
        if (message != null) {
            addThinking(message.string("reasoning_content"))
            addThinking(message.string("thinking"))
            addText(message.string("content"))
            addAll(openAiUrlCitationEvents(message))
            addAll(openAiToolCalls(message.array("tool_calls")))
        }
    }
}

internal fun openAiStreamEvents(events: Flow<HttpEvent>): Flow<ModelEvent> = flow {
    val toolCalls = linkedMapOf<Int, OpenAiToolCallAccumulator>()
    var lastMetadata: ModelResponseMetadata? = null
    providerSsePayloads(OPENAI_PROVIDER_NAME, events).collect { payload ->
        val root = parseProviderObject(OPENAI_PROVIDER_NAME, payload)
        root.modelResponseMetadata()
            ?.takeIf { it != lastMetadata }
            ?.let {
                emit(it)
                lastMetadata = it
            }
        root.openAiUsageDelta()?.let { emit(it) }
        val delta = root
            .array("choices")
            ?.firstObject()
            ?.obj("delta")
            ?: return@collect
        delta.string("reasoning_content")?.toThinkingEvent()?.let { emit(it) }
        delta.string("thinking")?.toThinkingEvent()?.let { emit(it) }
        delta.string("content")?.toTextEvent()?.let { emit(it) }
        collectOpenAiToolCallDeltas(delta.array("tool_calls"), toolCalls)
    }
    for (toolCall in toolCalls.values.mapNotNull { it.toEvent() }) {
        emit(toolCall)
    }
}

private fun JsonObjectBuilder.putOpenAiTools(tools: List<ModelTool>) {
    if (tools.isEmpty()) return
    putJsonArray("tools") {
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", tool.name)
                    tool.description.takeIf { it.isNotBlank() }?.let { put("description", it) }
                    put("parameters", parseObjectOrEmpty(tool.schemaJson))
                })
            })
        }
    }
}

private fun JsonObjectBuilder.putOpenAiServerSideSearch(
    enabled: Boolean,
    searchCapability: ServerSideSearchCapability,
) {
    if (enabled && searchCapability == ServerSideSearchCapability.OPENAI_CHAT_OPTIONS) {
        put("web_search_options", buildJsonObject { })
    }
}

private fun JsonObjectBuilder.putOpenAiMessages(request: ModelRequest, model: String) {
    putJsonArray("messages") {
        request.system?.takeIf { it.isNotBlank() }?.let {
            add(buildJsonObject {
                put("role", "system")
                put("content", it)
            })
        }
        for (message in request.messages.withoutToolLoads()) {
            add(buildJsonObject {
                when {
                    message.isAssistantToolCall() -> {
                        val options = request.modelRef.providerOptions(OPENAI_PROVIDER_NAME, model)
                        val reasoningContent = assistantReasoningContent(message, model, options)
                        put("role", "assistant")
                        // What the model told the user alongside the call; null when it said nothing.
                        val said = message.assistantText.takeIf { it.isNotBlank() }
                        when {
                            reasoningContent != null -> {
                                put("content", said.orEmpty())
                                put("reasoning_content", reasoningContent)
                            }
                            said != null -> put("content", said)
                            else -> put("content", JsonNull)
                        }
                        putJsonArray("tool_calls") {
                            add(buildJsonObject {
                                put("id", message.toolCallId)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", message.toolName)
                                    put("arguments", message.toolArgumentsJson ?: "{}")
                                })
                            })
                        }
                    }
                    message.isToolResult() -> {
                        put("role", "tool")
                        put("tool_call_id", message.toolCallId)
                        put("content", message.content)
                    }
                    else -> {
                        put("role", message.role)
                        putOpenAiContent(message)
                    }
                }
            })
            message.toolResultImagesFollowUp()?.let { followUp ->
                add(buildJsonObject {
                    put("role", followUp.role)
                    putOpenAiContent(followUp)
                })
            }
        }
    }
}

private fun JsonObjectBuilder.putOpenAiContent(message: ModelMessage) {
    if (message.images.isEmpty()) {
        put("content", message.content)
        return
    }
    putJsonArray("content") {
        message.content.takeIf { it.isNotBlank() }?.let { text ->
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        }
        for (image in message.images) {
            add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", image.dataUrl())
                    put("detail", image.detail)
                })
            })
        }
    }
}

private fun assistantReasoningContent(
    message: ModelMessage,
    model: String,
    options: ProviderRequestOptions,
): String? {
    // Thinking disabled: do not invent/replay reasoning_content (DeepSeek thinking mode off).
    if (options.thinkingType == THINKING_TYPE_DISABLED) return null
    if (!shouldReplayReasoningContent(model)) return null
    return message.content.takeIf { it.isNotBlank() } ?: REASONING_REPLAY_PLACEHOLDER
}

private fun shouldReplayReasoningContent(model: String): Boolean {
    val lower = model.lowercase()
    return lower.contains("deepseek-v4") ||
        lower.startsWith("deepseek-chat") ||
        lower.startsWith("deepseek-reasoner") ||
        lower.contains("deepseek-r")
}

private fun openAiToolCalls(calls: JsonArray?): List<ModelEvent.ToolCall> =
    calls.orEmpty().mapNotNull { element ->
        val call = element as? JsonObject ?: return@mapNotNull null
        val function = call.obj("function") ?: return@mapNotNull null
        ModelEvent.ToolCall(
            id = call.string("id") ?: "openai-tool-call",
            name = function.string("name") ?: return@mapNotNull null,
            argumentsJson = function.rawOrString("arguments") ?: "{}",
        )
    }

private fun openAiUrlCitationEvents(message: JsonObject): List<ModelEvent.SearchActivity> {
    val citations = message.array("annotations").urlCitationCount() +
        message.array("content")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .sumOf { it.array("annotations").urlCitationCount() }
    return if (citations > 0) {
        listOf(ModelEvent.SearchActivity(provider = OPENAI_PROVIDER_NAME, resultCount = citations))
    } else {
        emptyList()
    }
}

private fun JsonArray?.urlCitationCount(): Int =
    orEmpty().count { element ->
        val item = element as? JsonObject ?: return@count false
        item.string("type") == "url_citation" || item.obj("url_citation") != null
    }

private fun collectOpenAiToolCallDeltas(
    calls: JsonArray?,
    accumulators: MutableMap<Int, OpenAiToolCallAccumulator>,
) {
    calls.orEmpty().forEachIndexed { fallbackIndex, element ->
        val call = element as? JsonObject ?: return@forEachIndexed
        val index = call.int("index") ?: fallbackIndex
        val accumulator = accumulators.getOrPut(index) { OpenAiToolCallAccumulator() }
        call.string("id")?.let { accumulator.id = it }
        val function = call.obj("function") ?: return@forEachIndexed
        function.string("name")?.let { accumulator.name = it }
        function.string("arguments")?.let { accumulator.arguments.append(it) }
    }
}

internal data class OpenAiToolCallAccumulator(
    var id: String = "openai-tool-call",
    var name: String? = null,
    val arguments: StringBuilder = StringBuilder(),
) {
    fun toEvent(): ModelEvent.ToolCall? {
        val activeName = name ?: return null
        return ModelEvent.ToolCall(id, activeName, arguments.toString().ifBlank { "{}" })
    }
}

internal const val OPENAI_PROVIDER_NAME = "OpenAI"
private const val REASONING_REPLAY_PLACEHOLDER = "."

private fun JsonObject.openAiUsageDelta(): ModelEvent.UsageDelta? =
    obj("usage")?.modelUsageDelta() ?: modelUsageDelta()
