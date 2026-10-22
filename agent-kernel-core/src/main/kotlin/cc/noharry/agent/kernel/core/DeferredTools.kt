package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * Deferred backend tools, as Codex defers tools behind tool_search. Activated backend tools stay out of the request's
 * tool list; the model reads their schemas in search results (or the run's "tools ready" message) and calls them
 * through call_tool. The tool list then stays the same for the whole session, so a tool found mid-run no longer makes
 * everything after the tool list (the session history included) miss the provider's prompt cache.
 *
 * Execution does not change: a call_tool call is unwrapped into the named tool's call as it arrives, so hooks,
 * policy, confirmation, argument checks, obligations and events all see the real tool. Only the assistant message
 * kept for later requests is put back to the call_tool form the model actually sent.
 */

const val CALL_TOOL_NAME = "call_tool"

internal val CALL_TOOL = ModelTool(
    name = CALL_TOOL_NAME,
    schemaJson = """
        {
          "type": "object",
          "description": "Call a backend tool that is not in your tool list: one returned by search_tools with its schema, or listed in this request's tools-ready message. Pass its exact name and arguments that match its schema.",
          "additionalProperties": false,
          "required": ["name", "arguments"],
          "properties": {
            "name": {"type": "string", "description": "Exact tool name, e.g. mcp__server__tool."},
            "arguments": {"type": "object", "description": "Arguments matching the tool's schema."}
          }
        }
    """.trimIndent(),
)

/** [call] as a call of the tool it names, or null when it is not a well-formed call_tool call. */
internal fun DynamicToolSet.unwrapCallTool(call: ModelEvent.ToolCall): ModelEvent.ToolCall? {
    if (!defersBackendTools || call.name != CALL_TOOL_NAME) return null
    val args = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull() ?: return null
    val name = (args["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val arguments = when (val value = args["arguments"]) {
        null -> "{}"
        is JsonObject -> value.toString()
        is JsonPrimitive -> value.content.takeIf { value.isString && it.trimStart().startsWith("{") } ?: return null
        else -> return null
    }
    return call.copy(name = resolveToolName(name), argumentsJson = arguments)
}

/** Puts the calls in [wireCalls] (by call id) back to the call_tool form the model sent; results keep the real tool. */
internal fun List<ModelMessage>.withWireCalls(wireCalls: Map<String, ModelEvent.ToolCall>): List<ModelMessage> =
    if (wireCalls.isEmpty()) this else map { message ->
        val wire = message.toolCallId?.let(wireCalls::get)
        if (wire != null && message.role == "assistant") {
            message.copy(toolName = wire.name, toolArgumentsJson = wire.argumentsJson)
        } else {
            message
        }
    }

internal fun RoundOutcome.withWireCalls(wireCalls: Map<String, ModelEvent.ToolCall>): RoundOutcome = when (this) {
    is RoundOutcome.Finished -> copy(messages = messages.withWireCalls(wireCalls))
    is RoundOutcome.NeedsUserInput -> copy(messages = messages.withWireCalls(wireCalls))
    is RoundOutcome.ToolResults -> copy(messages = messages.withWireCalls(wireCalls))
    else -> this
}

internal fun invalidCallToolResult(): String = buildJsonObject {
    put("ok", false)
    put("error", buildJsonObject {
        put("code", "INVALID_CALL_TOOL")
        put("message", "call_tool needs \"name\" (a tool from search_tools or the tools-ready list) and \"arguments\" (an object matching its schema).")
    })
}.toString()

/**
 * The activated backend tools whose schema the model cannot see in [conversation] (the session's earlier runs and this
 * run's messages), as one message: at run start for prefetched and memory-hinted tools, and again after compaction
 * has folded away the messages that showed them.
 */
internal fun deferredToolsMessage(activated: List<ToolActivationRecord>, conversation: List<ModelMessage>): ModelMessage? =
    deferredToolsMessage(activated, conversation.toolsWithSchemaShown())

/** Tool names whose schema a message shows: a tools-ready line, or a search_tools match offered through call_tool. */
internal fun List<ModelMessage>.toolsWithSchemaShown(): Set<String> = buildSet {
    for (message in this@toolsWithSchemaShown) {
        if (message.content.startsWith(DEFERRED_TOOLS_HEADER)) {
            message.content.lineSequence().filter { it.startsWith("- ") }.forEach { add(it.removePrefix("- ").substringBefore(": ")) }
        } else if (message.role == "tool" && message.content.contains(""""via":"call_tool"""")) {
            SHOWN_MATCH_NAME.findAll(message.content).forEach { add(it.groupValues[1]) }
        }
    }
}

private val SHOWN_MATCH_NAME = Regex(""""name":"([^"]+)","originalName"""")

/**
 * For [BackendToolExposure.NATIVE_DEFERRED]: every tool a tool_addition in [conversation] loaded must stay declared, or
 * the provider rejects the whole request ("tool_addition references unknown tool"). Tools a run no longer carries
 * (memory hints are not inherited, the session cap drops old ones) are declared again from the catalog.
 */
@OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
internal suspend fun ToolActivationStore.keepLoadedToolsDeclared(
    runId: String,
    conversation: List<ModelMessage>,
    registry: ToolProviderRegistry,
    context: MaterializerContext,
) {
    val missing = conversation.flatMapTo(hashSetOf()) { it.toolAdditions } - activated(runId).mapTo(hashSetOf()) { it.toolName }
    if (missing.isEmpty()) return
    for (tool in registry.allTools(context).filter { it.qualifiedName in missing }) {
        val schema = tool.descriptor.inputSchemaJson.takeIf { it.isNotBlank() } ?: continue
        activate(runId, tool, schema, ToolActivationMetadata(source = SESSION_INHERITED_ACTIVATION_SOURCE))
    }
}

/** For [BackendToolExposure.NATIVE_DEFERRED]: one system update loading the tools [conversation] has not loaded yet. */
@OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)
internal fun toolAdditionMessage(activated: List<ToolActivationRecord>, conversation: List<ModelMessage>): ModelMessage? {
    val loaded = conversation.flatMapTo(hashSetOf()) { it.toolAdditions }
    val names = activated.map { it.toolName }.filter { it !in loaded }.distinct()
    return names.takeIf { it.isNotEmpty() }?.let { ModelMessage(role = "system", content = "", toolAdditions = it) }
}

internal fun deferredToolsMessage(activated: List<ToolActivationRecord>, alreadyShown: Set<String>): ModelMessage? {
    val shown = activated.filter { it.toolName !in alreadyShown }
    if (shown.isEmpty()) return null
    val lines = shown.joinToString("\n") { tool ->
        val description = tool.description.ifBlank { tool.originalToolName }
        "- ${tool.toolName}: $description\n  schema: ${compactJson(tool.schemaJson)}"
    }
    return ModelMessage(role = "user", content = "$DEFERRED_TOOLS_HEADER\n$lines")
}

const val DEFERRED_TOOLS_HEADER =
    "From the app, not typed by the user: tools ready for this request. They are not in your tool list; call them with call_tool(name, arguments)."

private fun compactJson(text: String): String =
    runCatching { Json.parseToJsonElement(text).toString() }.getOrDefault(text)
