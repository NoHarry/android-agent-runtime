@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop

import cc.noharry.agent.kernel.core.tools.DynamicToolSet
import cc.noharry.agent.kernel.core.tools.unwrapCallTool
import cc.noharry.agent.kernel.core.tools.withWireCalls
import cc.noharry.agent.runtime.ToolArguments
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * A call as the model wrote it and as the runtime runs it. The session's event log records the call as the model wrote
 * it, and the messages later requests send keep that form, so the model reads its own calls back unchanged. The call as
 * it runs, which the tool, hooks, argument checks and events get, is a call_tool call unwrapped to the tool it names,
 * with the arguments the host's [ToolArguments] gives that tool.
 */

/**
 * [written] as the runtime runs it. A call that runs in another form goes into [wireCalls] by its id, so its messages
 * keep the written form; one whose tool does not receive all the model wrote goes into [compared], in the written form
 * without what the tool does not receive, for the checks that compare calls. The host is given the tool's own schema,
 * a tool reached through call_tool included.
 */
internal fun ToolArguments.takeUp(
    written: ModelEvent.ToolCall,
    toolSet: DynamicToolSet,
    wireCalls: MutableMap<String, ModelEvent.ToolCall>,
    compared: MutableMap<String, ModelEvent.ToolCall>,
): ModelEvent.ToolCall {
    val unwrapped = toolSet.unwrapCallTool(written)
    val asCalled = unwrapped ?: written
    val arguments = forTool(asCalled, written, toolSet.schemaJson(asCalled.name))
    val call = if (arguments == asCalled.argumentsJson) asCalled else asCalled.copy(argumentsJson = arguments)
    if (call != written) wireCalls[call.id] = written
    comparedForm(written, unwrapped, arguments)?.let { compared[call.id] = it }
    return call
}

/**
 * [written] without what its tool does not receive, or null when that is all of it. A call_tool call keeps its form:
 * the tool's name as written and the arguments the tool receives, without other fields, which no tool receives.
 */
private fun comparedForm(written: ModelEvent.ToolCall, unwrapped: ModelEvent.ToolCall?, arguments: String): ModelEvent.ToolCall? {
    if (unwrapped == null) return if (arguments == written.argumentsJson) null else written.copy(argumentsJson = arguments)
    val wrapper = runCatching { Json.parseToJsonElement(written.argumentsJson) as? JsonObject }.getOrNull() ?: return null
    val kept = wrapper.filterKeys { it == CALL_TOOL_NAME_FIELD || it == CALL_TOOL_ARGUMENTS_FIELD }
    if (kept.size == wrapper.size && arguments == unwrapped.argumentsJson) return null
    val inner = runCatching { Json.parseToJsonElement(arguments) }.getOrElse { JsonPrimitive(arguments) }
    return written.copy(argumentsJson = JsonObject(kept + (CALL_TOOL_ARGUMENTS_FIELD to inner)).toString())
}

/** A round's tool results with the calls in [compared] (by call id) on them; other outcomes as they are. */
internal fun RoundOutcome.withComparedCalls(compared: Map<String, ModelEvent.ToolCall>): RoundOutcome =
    if (this is RoundOutcome.ToolResults && compared.isNotEmpty()) copy(comparedCalls = compared) else this

/** The round's messages with each call as the checks that compare calls read it ([takeUp]). */
internal fun RoundOutcome.ToolResults.comparedMessages(): List<ModelMessage> = messages.withWireCalls(comparedCalls)

private const val CALL_TOOL_NAME_FIELD = "name"
private const val CALL_TOOL_ARGUMENTS_FIELD = "arguments"
