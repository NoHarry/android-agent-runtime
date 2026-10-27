@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.model

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata

internal fun openAiResponsesRequest(
    apiKey: String,
    baseUrl: String,
    model: String,
    request: ModelRequest,
    stream: Boolean = false,
    serverSideSearch: Boolean = false,
    searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.OPENAI_RESPONSES_TOOL,
): HttpRequest = providerRequest(
    url = endpoint(baseUrl, "/responses"),
    headers = mapOf(
        "Authorization" to "Bearer $apiKey",
        "User-Agent" to OPENAI_RESPONSES_USER_AGENT,
    ),
    body = buildJsonObject {
        val options = request.modelRef.providerOptions(OPENAI_PROVIDER_NAME, model)
        put("model", model)
        modelOutputTokenLimit(model)?.let { put("max_output_tokens", it) }
        put("stream", stream)
        put("tool_choice", request.openAiResponsesToolChoice())
        put("parallel_tool_calls", true)
        put("store", false)
        request.promptCacheKey?.trim()?.takeIf { it.isNotBlank() }?.let { key ->
            put("prompt_cache_key", key)
        }
        options.reasoningEffort?.let { effort ->
            put("reasoning", buildJsonObject { put("effort", effort) })
        }
        putOpenAiResponsesInput(request)
        putOpenAiResponsesTools(request.tools, serverSideSearch, searchCapability)
    },
)

internal fun openAiResponsesEvents(response: HttpResponse): List<ModelEvent> {
    requireProviderSuccess(OPENAI_PROVIDER_NAME, response)
    val root = parseProviderObject(OPENAI_PROVIDER_NAME, response.body.toString(Charsets.UTF_8))
    return buildList {
        root.modelResponseMetadata()?.let { add(it) }
        addUsage(root.obj("usage")?.modelUsageDelta() ?: root.modelUsageDelta())
        var hasOutputText = false
        root.array("output").orEmpty().forEach { element ->
            val item = element as? JsonObject ?: return@forEach
            hasOutputText = appendOpenAiResponseItem(item) || hasOutputText
        }
        if (!hasOutputText) addText(root.string("output_text"))
    }
}

internal fun openAiResponsesStreamEvents(events: Flow<HttpEvent>): Flow<ModelEvent> = flow {
    val toolCalls = linkedMapOf<String, OpenAiToolCallAccumulator>()
    val emittedSearchItems = linkedSetOf<String>()
    var lastMetadata: ModelResponseMetadata? = null
    providerSsePayloads(OPENAI_PROVIDER_NAME, events).collect { payload ->
        val root = parseProviderObject(OPENAI_PROVIDER_NAME, payload)
        root.modelResponseMetadata()
            ?.takeIf { it != lastMetadata }
            ?.let {
                emit(it)
                lastMetadata = it
            }
        responseStreamUsage(root)?.let { emit(it) }
        responseStreamThinking(root)?.toThinkingEvent()?.let { emit(it) }
        responseStreamText(root)?.toTextEvent()?.let { emit(it) }
        responseStreamSearchActivity(root, emittedSearchItems)?.let { emit(it) }
        collectOpenAiResponseToolDelta(root, toolCalls)
    }
    for (toolCall in toolCalls.values.mapNotNull { it.toEvent() }) {
        emit(toolCall)
    }
}

private fun responseStreamUsage(root: JsonObject): ModelEvent.UsageDelta? =
    root.obj("usage")?.modelUsageDelta()
        ?: root.obj("response")?.obj("usage")?.modelUsageDelta()

private fun responseStreamText(root: JsonObject): String? =
    when (root.string("type")) {
        "response.output_text.delta", "response.refusal.delta" -> root.string("delta")
        else -> null
    }

private fun responseStreamThinking(root: JsonObject): String? =
    when (root.string("type")) {
        "response.reasoning_summary_text.delta",
        "response.reasoning_text.delta" -> root.string("delta")
        else -> null
    }

private fun ModelRequest.openAiResponsesToolChoice(): String =
    toolChoice?.trim()?.takeIf { it.isNotBlank() } ?: OPENAI_RESPONSES_DEFAULT_TOOL_CHOICE

private fun responseStreamSearchActivity(
    root: JsonObject,
    emittedItems: MutableSet<String>,
): ModelEvent.SearchActivity? {
    if (root.string("type") != "response.output_item.done") return null
    val item = root.obj("item")?.takeIf { it.string("type") == "web_search_call" } ?: return null
    val key = item.string("id") ?: item.string("call_id") ?: item.toString()
    if (!emittedItems.add(key)) return null
    return ModelEvent.SearchActivity(
        provider = OPENAI_PROVIDER_NAME,
        queryPreview = item.openAiWebSearchQueryPreview(),
    )
}

private fun collectOpenAiResponseToolDelta(
    root: JsonObject,
    accumulators: MutableMap<String, OpenAiToolCallAccumulator>,
) {
    val item = root.obj("item")
    when (root.string("type")) {
        "response.output_item.added" -> collectOpenAiResponseToolItem(item, accumulators)
        "response.output_item.done" -> collectOpenAiResponseToolItem(item, accumulators)
        "response.function_call_arguments.delta" -> {
            val accumulator = root.responseToolAccumulator(accumulators)
            root.string("delta")?.let { accumulator.arguments.append(it) }
        }
        "response.function_call_arguments.done" -> {
            val accumulator = root.responseToolAccumulator(accumulators)
            root.string("arguments")?.let { arguments ->
                accumulator.arguments.setLength(0)
                accumulator.arguments.append(arguments)
            }
        }
    }
}

private fun collectOpenAiResponseToolItem(
    item: JsonObject?,
    accumulators: MutableMap<String, OpenAiToolCallAccumulator>,
) {
    if (item?.string("type") != "function_call") return
    val key = item.string("id") ?: item.string("call_id") ?: "openai-response-tool-call"
    val accumulator = accumulators.getOrPut(key) { OpenAiToolCallAccumulator(id = key) }
    item.string("call_id")?.let { accumulator.id = it }
    item.string("name")?.let { accumulator.name = it }
    item.string("arguments")?.takeIf { it.isNotBlank() }?.let { arguments ->
        accumulator.arguments.setLength(0)
        accumulator.arguments.append(arguments)
    }
}

private fun JsonObject.responseToolAccumulator(
    accumulators: MutableMap<String, OpenAiToolCallAccumulator>,
): OpenAiToolCallAccumulator {
    val key = responseToolKey()
    return accumulators.getOrPut(key) { OpenAiToolCallAccumulator(id = key) }
}

private fun JsonObject.responseToolKey(): String =
    string("item_id") ?: string("call_id") ?: string("id") ?: "openai-response-tool-call"

private fun MutableList<ModelEvent>.appendOpenAiResponseItem(item: JsonObject): Boolean =
    when (item.string("type")) {
        "message" -> appendOpenAiResponseMessage(item)
        "web_search_call" -> {
            add(
                ModelEvent.SearchActivity(
                    provider = OPENAI_PROVIDER_NAME,
                    queryPreview = item.openAiWebSearchQueryPreview(),
                ),
            )
            false
        }
        "function_call" -> {
            openAiResponsesToolCall(item)?.let { add(it) }
            false
        }
        "reasoning" -> {
            addThinking(openAiResponseReasoning(item))
            false
        }
        else -> false
    }

private fun MutableList<ModelEvent>.appendOpenAiResponseMessage(message: JsonObject): Boolean {
    var hasOutputText = false
    message.array("content").orEmpty().forEach { content ->
        val text = openAiResponseContentText(content)
        addText(text)
        hasOutputText = hasOutputText || !text.isNullOrBlank()
    }
    return hasOutputText
}

private fun openAiResponseContentText(content: JsonElement): String? {
    val item = content as? JsonObject ?: return null
    return when (item.string("type")) {
        "output_text", "text" -> item.string("text")
        "refusal" -> item.string("refusal")
        else -> null
    }
}

private fun openAiResponsesToolCall(item: JsonObject): ModelEvent.ToolCall? {
    val name = item.string("name") ?: return null
    return ModelEvent.ToolCall(
        id = item.string("call_id") ?: item.string("id") ?: "openai-response-tool-call",
        name = name,
        argumentsJson = item.rawOrString("arguments") ?: "{}",
    )
}

private fun openAiResponseReasoning(item: JsonObject): String? =
    item.string("text")
        ?: item.array("summary")
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("text") }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

private fun JsonObject.openAiWebSearchQueryPreview(): String =
    obj("action")?.string("query")
        ?: obj("action")
            ?.array("queries")
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .firstOrNull()
        ?: ""

private fun JsonObjectBuilder.putOpenAiResponsesInput(request: ModelRequest) {
    putJsonArray("input") {
        request.system?.takeIf { it.isNotBlank() }?.let { add(responseMessage("system", it)) }
        for (message in request.messages.withoutToolLoads()) {
            // What the model told the user alongside a call goes before the call, as the assistant's message.
            message.assistantText.takeIf { it.isNotBlank() && message.isAssistantToolCall() }
                ?.let { add(responseMessage("assistant", it)) }
            add(responseInputItem(message))
            message.toolResultImagesFollowUp()?.let { add(responseMessage(it)) }
        }
    }
}

private fun responseInputItem(message: ModelMessage): JsonObject =
    when {
        message.isAssistantToolCall() -> responseFunctionCall(message)
        message.isToolResult() -> responseFunctionCallOutput(message)
        else -> responseMessage(message)
    }

private fun responseMessage(role: String, content: String): JsonObject =
    buildJsonObject {
        put("role", role)
        put("content", content)
    }

private fun responseMessage(message: ModelMessage): JsonObject =
    if (message.images.isEmpty()) {
        responseMessage(message.role, message.content)
    } else {
        buildJsonObject {
            put("role", message.role)
            putJsonArray("content") {
                message.content.takeIf { it.isNotBlank() }?.let { text ->
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", text)
                    })
                }
                for (image in message.images) {
                    add(buildJsonObject {
                        put("type", "input_image")
                        put("image_url", image.dataUrl())
                        put("detail", image.detail)
                    })
                }
            }
        }
    }

private fun responseFunctionCall(message: ModelMessage): JsonObject =
    buildJsonObject {
        put("type", "function_call")
        put("call_id", message.toolCallId)
        put("name", message.toolName)
        put("arguments", message.toolArgumentsJson ?: "{}")
    }

private fun responseFunctionCallOutput(message: ModelMessage): JsonObject =
    buildJsonObject {
        put("type", "function_call_output")
        put("call_id", message.toolCallId)
        put("output", message.content)
    }

private fun JsonObjectBuilder.putOpenAiResponsesTools(
    tools: List<ModelTool>,
    serverSideSearch: Boolean,
    searchCapability: ServerSideSearchCapability,
) {
    if (tools.isEmpty() && !serverSideSearch) return
    putJsonArray("tools") {
        if (serverSideSearch && searchCapability == ServerSideSearchCapability.OPENAI_RESPONSES_TOOL) {
            add(buildJsonObject { put("type", OPENAI_RESPONSES_WEB_SEARCH_TOOL_TYPE) })
        }
        for (tool in tools) {
            add(buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                tool.description.takeIf { it.isNotBlank() }?.let { put("description", it) }
                put("parameters", parseObjectOrEmpty(tool.schemaJson))
            })
        }
    }
}

private const val OPENAI_RESPONSES_WEB_SEARCH_TOOL_TYPE = "web_search"
private const val OPENAI_RESPONSES_DEFAULT_TOOL_CHOICE = "auto"
private const val OPENAI_RESPONSES_USER_AGENT =
    "codex-tui/0.136.0 (Ubuntu 24.4.0; x86_64) xterm-256color (codex-tui; 0.136.0)"
