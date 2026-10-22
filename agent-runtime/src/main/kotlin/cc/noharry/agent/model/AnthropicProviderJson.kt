@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.model

import cc.noharry.agent.transport.HttpEvent
import cc.noharry.agent.transport.HttpRequest
import cc.noharry.agent.transport.HttpResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.api.model.ServerSideSearchCapability
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata

internal fun anthropicRequest(
    apiKey: String,
    baseUrl: String,
    model: String,
    request: ModelRequest,
    stream: Boolean = false,
    serverSideSearch: Boolean = false,
    searchCapability: ServerSideSearchCapability = ServerSideSearchCapability.ANTHROPIC_SERVER_TOOL,
): HttpRequest = providerRequest(
    url = endpoint(baseUrl, "/messages"),
    headers = mapOf(
        "x-api-key" to apiKey,
        "anthropic-version" to ANTHROPIC_VERSION,
    ) + toolChangesBeta(request),
    body = buildJsonObject {
        val options = request.modelRef.providerOptions(ANTHROPIC_PROVIDER_NAME, model)
        put("model", model)
        put("max_tokens", modelOutputTokenLimit(model) ?: maxTokensFor(options))
        put("stream", stream)
        putAnthropicThinking(options)
        request.system?.takeIf { it.isNotBlank() }?.let { put("system", it) }
        putAnthropicMessages(request)
        putAnthropicTools(request.tools, serverSideSearch, searchCapability)
    },
)

/**
 * Claude: `thinking: { type: enabled, budget_tokens }`.
 * DeepSeek Anthropic-compatible: `thinking: { type: enabled|disabled }` +
 * `output_config: { effort }` when enabled (not budget_tokens).
 */
private fun JsonObjectBuilder.putAnthropicThinking(options: ProviderRequestOptions) {
    val thinkingType = options.thinkingType
    if (thinkingType != null) {
        put("thinking", buildJsonObject { put("type", thinkingType) })
        if (thinkingType == THINKING_TYPE_ENABLED) {
            options.reasoningEffort?.let { effort ->
                put("output_config", buildJsonObject { put("effort", effort) })
            }
        }
        return
    }
    options.thinkingBudgetTokens?.let { budget ->
        put("thinking", buildJsonObject {
            put("type", "enabled")
            put("budget_tokens", budget)
        })
    }
}

internal fun anthropicEvents(response: HttpResponse): List<ModelEvent> {
    requireProviderSuccess(ANTHROPIC_PROVIDER_NAME, response)
    val root = parseProviderObject(ANTHROPIC_PROVIDER_NAME, response.body.toString(Charsets.UTF_8))
    return buildList {
        root.modelResponseMetadata()?.let { add(it) }
        addUsage(root.obj("usage")?.modelUsageDelta())
        for (block in root.array("content").orEmpty()) {
            val item = block as? JsonObject ?: continue
            when (item.string("type")) {
                "thinking" -> {
                    val sig = item.string("signature")?.takeIf { it.isNotBlank() }
                    addThinking(item.string("thinking"), sig)
                }
                "text" -> addAll(anthropicTextEvents(item.string("text")))
                "tool_use" -> add(anthropicToolCall(item))
                "server_tool_use" -> add(anthropicServerToolUse(item))
                "web_search_tool_result" -> add(anthropicWebSearchResult(item))
            }
        }
    }
}

internal fun anthropicStreamEvents(events: Flow<HttpEvent>): Flow<ModelEvent> = flow {
    val blocks = mutableMapOf<Int, AnthropicBlockAccumulator>()
    val textBlocks = mutableMapOf<Int, StringBuilder>()
    val usage = AnthropicStreamUsage()
    // Tracks the latest thinking signature seen so far for this stream.
    // Anthropic sends it via content_block_start (empty) then signature_delta.
    var thinkingSignature: String? = null
    var lastMetadata: ModelResponseMetadata? = null
    providerSsePayloads(ANTHROPIC_PROVIDER_NAME, events).collect { payload ->
        val item = parseProviderObject(ANTHROPIC_PROVIDER_NAME, payload)
        item.modelResponseMetadata()
            ?.takeIf { it != lastMetadata }
            ?.let {
                emit(it)
                lastMetadata = it
            }
        // message_start nests usage under message; later deltas put usage at root.
        (item.obj("usage") ?: item.obj("message")?.obj("usage"))
            ?.let(usage::update)
            ?.let { emit(it) }
        when (item.string("type")) {
            "content_block_start" -> {
                captureThinkingSignature(item)?.let { thinkingSignature = it }
                startAnthropicTextBlock(item, textBlocks)
                startAnthropicBlock(item, blocks)?.let { emit(it) }
            }
            "content_block_delta" -> {
                val delta = item.obj("delta")
                if (delta?.string("type") == "signature_delta") {
                    delta.string("signature")?.let { sig ->
                        thinkingSignature = if (sig.isNotBlank()) sig else thinkingSignature
                    }
                }
                appendAnthropicTextDelta(item, textBlocks)
                appendAnthropicDelta(item, blocks, textBlocks, thinkingSignature)?.let { emit(it) }
            }
            "content_block_stop" -> {
                stopAnthropicTextBlock(item, textBlocks).forEach { emit(it) }
                stopAnthropicBlock(item, blocks)?.let { emit(it) }
            }
        }
    }
}

private fun captureThinkingSignature(item: JsonObject): String? {
    val block = item.obj("content_block") ?: return null
    if (block.string("type") != "thinking") return null
    // Return the signature even if empty — some gateways (DeepSeek) send
    // an empty signature but still require the thinking block to be echoed.
    return block.string("signature") ?: ""
}

private fun JsonObjectBuilder.putAnthropicTools(
    tools: List<ModelTool>,
    serverSideSearch: Boolean,
    searchCapability: ServerSideSearchCapability,
) {
    if (tools.isEmpty() && !serverSideSearch) return
    putJsonArray("tools") {
        if (serverSideSearch && searchCapability == ServerSideSearchCapability.ANTHROPIC_SERVER_TOOL) {
            add(buildJsonObject {
                put("type", ANTHROPIC_WEB_SEARCH_TOOL_TYPE)
                put("name", ANTHROPIC_WEB_SEARCH_TOOL_NAME)
            })
        }
        for (tool in tools) {
            add(buildJsonObject {
                put("name", tool.name)
                put("input_schema", parseObjectOrEmpty(tool.schemaJson))
                if (tool.deferred) put("defer_loading", true)
            })
        }
    }
}

private const val ANTHROPIC_WEB_SEARCH_TOOL_TYPE = "web_search_20250305"
private const val ANTHROPIC_WEB_SEARCH_TOOL_NAME = "web_search"

private fun JsonObjectBuilder.putAnthropicMessages(request: ModelRequest) {
    putJsonArray("messages") {
        // A load naming a tool the request does not declare would fail the whole request; such a load is left out.
        val declared = request.tools.mapTo(hashSetOf()) { it.name }
        for (merged in request.messages.withMergedSystemUpdates()) {
            val message = merged.copy(toolAdditions = merged.toolAdditions.filter { it in declared })
            if (message.role == "system" && message.content.isBlank() && message.toolAdditions.isEmpty()) continue
            add(buildJsonObject {
                when {
                    message.role == "system" -> {
                        put("role", "system")
                        putSystemUpdateContent(message)
                    }
                    message.isAssistantToolCall() -> {
                        put("role", "assistant")
                        putJsonArray("content") {
                            // Anthropic thinking-mode contract: thinking blocks
                            // must be passed back in subsequent turns. Output
                            // the block whenever thinking text is present, even
                            // if the signature is empty (DeepSeek's gateway
                            // sends an empty signature but still requires the
                            // thinking block to be echoed back).
                            if (message.content.isNotBlank()) {
                                add(buildJsonObject {
                                    put("type", "thinking")
                                    put("thinking", message.content)
                                    put("signature", message.thinkingSignature ?: "")
                                })
                            }
                            add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", message.toolCallId)
                                put("name", message.toolName)
                                put("input", parseObjectOrEmpty(message.toolArgumentsJson ?: "{}"))
                            })
                        }
                    }
                    message.isToolResult() -> {
                        put("role", "user")
                        putJsonArray("content") {
                            add(buildJsonObject {
                                put("type", "tool_result")
                                put("tool_use_id", message.toolCallId)
                                putAnthropicContent(message)
                            })
                        }
                    }
                    else -> {
                        put("role", message.role)
                        putAnthropicContent(message)
                    }
                }
            })
        }
    }
}

private fun JsonObjectBuilder.putAnthropicContent(message: ModelMessage) {
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
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", image.mediaType)
                    put("data", image.base64Data())
                })
            })
        }
    }
}

private fun anthropicToolCall(item: JsonObject): ModelEvent.ToolCall =
    ModelEvent.ToolCall(
        id = item.string("id") ?: "anthropic-tool-call",
        name = item.string("name") ?: "unknown_tool",
        argumentsJson = item["input"]?.toString() ?: "{}",
    )

private fun anthropicServerToolUse(item: JsonObject): ModelEvent.SearchActivity =
    ModelEvent.SearchActivity(
        provider = ANTHROPIC_PROVIDER_NAME,
        queryPreview = item.obj("input")?.string("query").orEmpty(),
    )

private fun anthropicWebSearchResult(item: JsonObject): ModelEvent.SearchActivity =
    ModelEvent.SearchActivity(
        provider = ANTHROPIC_PROVIDER_NAME,
        resultCount = item.array("content")?.size,
    )

private fun startAnthropicBlock(
    item: JsonObject,
    blocks: MutableMap<Int, AnthropicBlockAccumulator>,
): ModelEvent? {
    val index = item.int("index") ?: return null
    val block = item.obj("content_block") ?: return null
    return when (block.string("type")) {
        "thinking" -> block.string("thinking")?.toThinkingEvent()
        "text" -> block.string("text")?.toTextEvent()
        "tool_use" -> {
            // Stream starts with empty input {}; accumulate input_json_delta until stop.
            blocks[index] = AnthropicBlockAccumulator(
                id = block.string("id") ?: "anthropic-tool-call",
                name = block.string("name") ?: "unknown_tool",
                input = StringBuilder(block["input"]?.toString()?.takeIf { it != "{}" } ?: ""),
                kind = AnthropicBlockKind.CLIENT_TOOL,
            )
            null
        }
        "server_tool_use" -> {
            // Same streaming shape as tool_use: input arrives via input_json_delta.
            // Emitting SearchActivity here would lose the query (often still {}).
            blocks[index] = AnthropicBlockAccumulator(
                id = block.string("id") ?: "anthropic-server-tool",
                name = block.string("name") ?: "web_search",
                input = StringBuilder(block["input"]?.toString()?.takeIf { it != "{}" } ?: ""),
                kind = AnthropicBlockKind.SERVER_TOOL,
            )
            null
        }
        "web_search_tool_result" -> anthropicWebSearchResult(block)
        else -> null
    }
}

private fun appendAnthropicDelta(
    item: JsonObject,
    blocks: MutableMap<Int, AnthropicBlockAccumulator>,
    textBlocks: MutableMap<Int, StringBuilder>,
    thinkingSignature: String?,
): ModelEvent? {
    val index = item.int("index") ?: return null
    val delta = item.obj("delta") ?: return null
    return when (delta.string("type")) {
        "thinking_delta" -> delta.string("thinking")?.let { text ->
            ModelEvent.ThinkingDelta(text = text, signature = thinkingSignature)
        }
        "text_delta" -> if (index in textBlocks) null else delta.string("text")?.toTextEvent()
        "input_json_delta" -> {
            blocks[index]?.input?.append(delta.string("partial_json") ?: "")
            null
        }
        else -> null
    }
}

private fun stopAnthropicBlock(
    item: JsonObject,
    blocks: MutableMap<Int, AnthropicBlockAccumulator>,
): ModelEvent? {
    val index = item.int("index") ?: return null
    return blocks.remove(index)?.toEvent()
}

private enum class AnthropicBlockKind {
    CLIENT_TOOL,
    SERVER_TOOL,
}

private data class AnthropicBlockAccumulator(
    val id: String,
    val name: String,
    val input: StringBuilder,
    val kind: AnthropicBlockKind = AnthropicBlockKind.CLIENT_TOOL,
) {
    fun toEvent(): ModelEvent =
        when (kind) {
            AnthropicBlockKind.CLIENT_TOOL ->
                ModelEvent.ToolCall(id, name, input.toString().ifBlank { "{}" })
            AnthropicBlockKind.SERVER_TOOL ->
                ModelEvent.SearchActivity(
                    provider = ANTHROPIC_PROVIDER_NAME,
                    queryPreview = serverToolQueryPreview(input.toString()),
                )
        }
}

/** Prefer `query` field from accumulated server tool JSON; fall back to raw input. */
private fun serverToolQueryPreview(inputJson: String): String {
    val raw = inputJson.trim().ifBlank { return "" }
    val parsed = runCatching {
        Json.parseToJsonElement(raw) as? JsonObject
    }.getOrNull()
    val query = parsed?.string("query")?.trim().orEmpty()
    return query.ifBlank { raw.takeIf { it != "{}" }.orEmpty() }
}

private fun startAnthropicTextBlock(
    item: JsonObject,
    textBlocks: MutableMap<Int, StringBuilder>,
) {
    val index = item.int("index") ?: return
    val block = item.obj("content_block") ?: return
    if (block.string("type") != "text") return
    textBlocks[index] = StringBuilder(block.string("text").orEmpty())
}

private fun appendAnthropicTextDelta(
    item: JsonObject,
    textBlocks: MutableMap<Int, StringBuilder>,
) {
    val index = item.int("index") ?: return
    val delta = item.obj("delta") ?: return
    if (delta.string("type") != "text_delta") return
    val target = textBlocks[index] ?: return
    target.append(delta.string("text").orEmpty())
}

private fun stopAnthropicTextBlock(
    item: JsonObject,
    textBlocks: MutableMap<Int, StringBuilder>,
): List<ModelEvent> {
    val index = item.int("index") ?: return emptyList()
    val text = textBlocks.remove(index)?.toString() ?: return emptyList()
    return anthropicTextEvents(text)
}

private fun anthropicTextEvents(text: String?): List<ModelEvent> {
    val value = text?.takeIf { it.isNotBlank() } ?: return emptyList()
    return parseDsmlToolCalls(value).ifEmpty { listOf(ModelEvent.TextDelta(value)) }
}

private fun parseDsmlToolCalls(text: String): List<ModelEvent.ToolCall> {
    if (!text.contains(DSML_TOKEN) || !text.contains(DSML_TOOL_CALLS)) return emptyList()
    return DSML_INVOKE_REGEX.findAll(text).mapNotNull { match ->
        val name = match.groupValues[1].takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val body = match.groupValues[2]
        val arguments = buildJsonObject {
            DSML_PARAMETER_REGEX.findAll(body).forEach { parameter ->
                val parameterName = parameter.groupValues[1].takeIf { it.isNotBlank() } ?: return@forEach
                val stringFlag = parameter.groupValues[2].lowercase()
                val rawValue = parameter.groupValues[3]
                put(parameterName, dsmlParameterValue(rawValue, stringFlag == "true"))
            }
        }
        ModelEvent.ToolCall(
            id = "dsml-$name-${match.range.first}",
            name = name,
            argumentsJson = arguments.toString(),
        )
    }.toList()
}

private fun dsmlParameterValue(value: String, forceString: Boolean): JsonElement {
    val trimmed = value.trim()
    if (forceString) return JsonPrimitive(trimmed)
    return runCatching { DSML_JSON.parseToJsonElement(trimmed) }
        .getOrElse { JsonPrimitive(trimmed) }
}

private const val ANTHROPIC_VERSION = "2023-06-01"
private const val DEFAULT_MAX_TOKENS = 1024
private const val ANTHROPIC_PROVIDER_NAME = "Anthropic"
private const val DSML_TOKEN = "DSML"
private const val DSML_TOOL_CALLS = "tool_calls"
private val DSML_INVOKE_REGEX = Regex(
    """<[^>]*invoke\s+name="([^"]+)"[^>]*>(.*?)</[^>]*invoke>""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)
private val DSML_PARAMETER_REGEX = Regex(
    """<[^>]*parameter\s+name="([^"]+)"(?:\s+string="([^"]+)")?[^>]*>(.*?)</[^>]*parameter>""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)
private val DSML_JSON = Json { ignoreUnknownKeys = true }

private fun maxTokensFor(options: ProviderRequestOptions): Int {
    val thinkingBudget = options.thinkingBudgetTokens ?: return DEFAULT_MAX_TOKENS
    return maxOf(DEFAULT_MAX_TOKENS, thinkingBudget + DEFAULT_MAX_TOKENS)
}

/**
 * DeepSeek's mid-conversation updates: a system message in history (a context or rule update) and `tool_addition`
 * blocks loading deferred tools. Consecutive updates go out as one system message, as the endpoint expects them
 * between a user or tool-result turn and the next assistant turn.
 */
private fun List<ModelMessage>.withMergedSystemUpdates(): List<ModelMessage> {
    val merged = mutableListOf<ModelMessage>()
    for (message in this) {
        val previous = merged.lastOrNull()
        if (message.role == "system" && previous?.role == "system" && message.images.isEmpty() && previous.images.isEmpty()) {
            merged[merged.lastIndex] = previous.copy(
                content = listOf(previous.content, message.content).filter { it.isNotBlank() }.joinToString("\n\n"),
                toolAdditions = previous.toolAdditions + message.toolAdditions,
            )
        } else {
            merged += message
        }
    }
    return merged
}

private fun JsonObjectBuilder.putSystemUpdateContent(message: ModelMessage) {
    if (message.toolAdditions.isEmpty()) {
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
        for (name in message.toolAdditions) {
            add(buildJsonObject {
                put("type", "tool_addition")
                put("tool", buildJsonObject {
                    put("type", "tool_reference")
                    put("name", name)
                })
            })
        }
    }
}

private fun toolChangesBeta(request: ModelRequest): Map<String, String> =
    if (request.messages.any { it.toolAdditions.isNotEmpty() } || request.tools.any { it.deferred }) {
        mapOf("anthropic-beta" to ANTHROPIC_TOOL_CHANGES_BETA)
    } else {
        emptyMap()
    }

private const val ANTHROPIC_TOOL_CHANGES_BETA = "mid-conversation-tool-changes-2026-07-01"
