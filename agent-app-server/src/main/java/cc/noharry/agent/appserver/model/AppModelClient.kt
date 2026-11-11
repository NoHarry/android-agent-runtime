@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model
import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.appserver.runtime.ToolNote
import cc.noharry.agent.appserver.runtime.ToolNotes
import cc.noharry.agent.appserver.runtime.ModelWords
import cc.noharry.agent.appserver.runtime.PromptPrefix
import cc.noharry.agent.appserver.runtime.append
import cc.noharry.agent.appserver.session.SESSION_QUERY_INDEX_PATH
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelClientException
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

internal class AppModelClient(
    private val delegate: ModelClient,
    private val onModelRequestTools: ((List<String>) -> Unit)? = null,
    private val onPromptPrefix: ((String) -> Unit)? = null,
    /** [withFirstEventTimeout] for a streamed call; null for none. */
    private val firstEventMillis: Long? = null,
) : ModelClient {
    @OptIn(ExperimentalAgentRuntimeApi::class)
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    private var recordedFirstRound = false

    /** What the model wrote in the host's note field, by call id: its later requests show it its own calls whole. */
    private val notes = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** The call as the runtime gets it, without the note, which goes to [ToolNotes] for the host. */
    private fun takeNote(note: ToolNote, call: ModelEvent.ToolCall): ModelEvent.ToolCall {
        val text = note.of(call.argumentsJson) ?: return call
        val bare = call.copy(argumentsJson = note.without(call.argumentsJson))
        notes[call.id] = text
        ToolNotes.put(bare.name, bare.argumentsJson, text)
        return bare
    }

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        if (!recordedFirstRound) {
            recordedFirstRound = true
            onModelRequestTools?.invoke(request.tools.map { it.name })
        }
        onPromptPrefix?.invoke(PromptPrefix.compare(request))
        val round = ModelRoundRecovery(request)
        val note = AppHost.runs.toolNote()
        val events = delegate.complete(if (note == null) request else request.withToolNote(note, notes))
        (firstEventMillis?.let(events::withFirstEventTimeout) ?: events).collect { event ->
            when (event) {
                is ModelEvent.ToolCall -> {
                    val call = note?.let { takeNote(it, event) } ?: event
                    round.recordToolCall(call.name, call.argumentsJson)
                    emit(call)
                }
                is ModelEvent.ThinkingDelta -> {
                    emit(event)
                }
                is ModelEvent.TextDelta -> {
                    round.recordText(event.text)
                    emit(event)
                }
                ModelEvent.Completed -> {
                    round.recoveredFinishCall()?.let { emit(it) }
                    emit(event)
                }
                is ModelEvent.UsageDelta -> emit(event)
                else -> emit(event)
            }
        }
    }
}

private class ModelRoundRecovery(private val request: ModelRequest) {
    private val text = StringBuilder()
    private val toolCalls = mutableListOf<String>()
    private var finalTodoUpdate = false

    fun recordText(delta: String) {
        text.append(delta)
    }

    fun recordToolCall(name: String, argumentsJson: String) {
        toolCalls += name
        if (name == UPDATE_TODO_TOOL_NAME && argumentsJson.hasAllTodosCompletedPayload()) {
            finalTodoUpdate = true
        }
    }

    fun recoveredFinishCall(): ModelEvent.ToolCall? {
        if (!request.canRecoverFinalText()) return null
        val candidate = text.toString()
        if (toolCalls.isNotEmpty() && !canRecoverAfterOnlyTodo(candidate)) return null
        if (!request.shouldRecoverFinalText(candidate) && !canRecoverFromCompletedTodoPayload()) return null
        val response = candidate.speakableFinalOrNull() ?: return null
        return ModelEvent.ToolCall(
            id = "debug-recovered-finish-${System.currentTimeMillis()}",
            name = FINISH_TOOL_NAME,
            argumentsJson = finishArguments(response),
        )
    }

    private fun canRecoverAfterOnlyTodo(candidate: String): Boolean =
        toolCalls.all { it == UPDATE_TODO_TOOL_NAME } && (candidate.hasFinalIntent() || finalTodoUpdate)

    private fun canRecoverFromCompletedTodoPayload(): Boolean =
        finalTodoUpdate && toolCalls.all { it == UPDATE_TODO_TOOL_NAME }
}

/** [nativeTools]: backend tools load into the tool list (a service's native updates, such as a `tool_addition` in history) instead of going through call_tool. */
internal fun appSystemPrompt(sessionContext: String, nativeTools: Boolean = false, words: ModelWords = AppHost.words): String {
    val rules = coreRules(words).let { if (nativeTools) it.replace(CALL_TOOL_LINE, NATIVE_TOOL_LINE) else it }
    val prompt = AppHost.persona.identity + "\n\n" + rules
    return if (sessionContext.isBlank()) prompt else "$prompt\n\n$SESSION_CONTEXT_HEADER\n$sessionContext"
}

private const val CALL_TOOL_LINE =
    "2. Call backend tools via call_tool(name, arguments) with the schema shown by search_tools or a tools-ready message, " +
        "also earlier in the chat; read /mcp/tools/<qualified-tool>/schema.json only if none was shown."
private const val NATIVE_TOOL_LINE =
    "2. search_tools matches marked callable are loaded into your tool list right after the result; call them directly. " +
        "Read /mcp/tools/<qualified-tool>/schema.json only for a match not marked callable."

private fun ModelRequest.canRecoverFinalText(): Boolean =
    tools.any { it.name == FINISH_TOOL_NAME } && messages.any { it.isSuccessfulBusinessToolResult() }

private fun ModelRequest.shouldRecoverFinalText(candidate: String): Boolean =
    candidate.isNotBlank() &&
        (system.hasBudgetWarning() || system.hasAllTodosCompleted() || candidate.hasFinalIntent())

private fun ModelMessage.isSuccessfulBusinessToolResult(): Boolean =
    role == "tool" &&
        toolName.orEmpty().isBusinessToolName() &&
        content.isSuccessfulToolResult()

private fun String.isBusinessToolName(): Boolean =
    startsWith("mcp__") || startsWith("command_")

private fun String.isSuccessfulToolResult(): Boolean =
    runCatching {
        val root = Json.parseToJsonElement(this) as? JsonObject ?: return false
        val payload = root["result"] as? JsonObject ?: root
        root["ok"]?.jsonPrimitive?.content != "false" &&
            root["isError"]?.jsonPrimitive?.content != "true" &&
            payload["isError"]?.jsonPrimitive?.content != "true" &&
            (root["error"] == null || root["error"] is kotlinx.serialization.json.JsonNull)
    }.getOrDefault(false)

private fun String?.hasBudgetWarning(): Boolean =
    this?.contains("budgetWarning") == true

private fun String?.hasAllTodosCompleted(): Boolean {
    val value = this ?: return false
    if (!value.contains("Todo:")) return false
    return !value.contains("- [ ]") && !value.contains("- [~]")
}

private fun String.hasFinalIntent(): Boolean {
    val lower = lowercase()
    return lower.contains("all tasks are completed") ||
        lower.contains("everything has been completed") ||
        lower.contains("final response") ||
        lower.contains("call finish") ||
        contains("全部完成") ||
        contains("任务完成") ||
        contains("执行完成") ||
        contains("已完成")
}

private fun String.hasAllTodosCompletedPayload(): Boolean =
    runCatching {
        val root = Json.parseToJsonElement(this) as? JsonObject ?: return false
        val todos = root["todos"] as? JsonArray ?: return false
        todos.isNotEmpty() && todos.all { item ->
            (item as? JsonObject)?.get("status")?.jsonPrimitive?.contentOrNull == "completed"
        }
    }.getOrDefault(false)

private fun String.speakableFinalOrNull(): String? {
    val normalized = trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return null
    if (normalized.isInternalFinishPlan() || normalized == "已根据可用工具完成请求。") return null
    val chineseStart = normalized.indexOfFirst { it in '\u4e00'..'\u9fff' }
    if (chineseStart > 0 && normalized.take(chineseStart).hasFinalIntent()) {
        return normalized.drop(chineseStart).trimEnd()
    }
    val lower = normalized.lowercase()
    if (lower.startsWith("all tasks are completed") || lower.contains("call finish")) return null
    return normalized.takeIf { it.length >= MIN_RECOVERED_FINISH_CHARS }
}

private fun String.isInternalFinishPlan(): Boolean =
    Regex("(?i)(?:now|let me|i(?:'ll| will))\\s+(?:call\\s+)?finish\\b|finish with (?:a |the )?.*(?:report|response|summary)").containsMatchIn(this)

private fun finishArguments(response: String): String =
    """{"response":${response.jsonString()}}"""

private fun String.jsonString(): String =
    buildString {
        append('"')
        for (char in this@jsonString) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

private const val FINISH_TOOL_NAME = "finish"
private const val UPDATE_TODO_TOOL_NAME = "update_todo"
private const val MIN_RECOVERED_FINISH_CHARS = 8
/** The core rules, with the reply language and the short replies in [words]' language; a Chinese persona's read as they always did. */
private fun coreRules(words: ModelWords) = """
    Current turn and continuity:
    - Execute only intents present in the current user text, including an unambiguous affirmative or negative answer to the immediately preceding assistant question. For acceptance, treat the offered action itself as the current-turn intent: discover and call the matching current backend tool. Do not repeat the question or merely acknowledge the answer. Then give only ${words.briefReport}; do not quote the prior question or narrate internal reasoning. For rejection, acknowledge and take no offered action.
    - Do not continue previous-turn side intents unless explicitly requested or accepted now. History and leftover TODOs are continuity, not proof that the new user request is already done. Repeated requests require fresh actions; prior PARTIAL/FAILED results do not prove success.
    - Bind an initial short reply (${words.shortReplies}) to the Immediately preceding exchange, not the last line of historical summaries or lastSuccessfulAction. For later user messages, use the latest in-run question instead of that snapshot. Clarify unresolved choices; do not treat bare approval as consent to older or other-session offers. For context-dependent requests not resolvable from this exchange, only explicit references to another session may use $SESSION_QUERY_INDEX_PATH; use actionDomains and lastAction to read only relevant listed session summary files.

    Tools and execution:
    Discover host capabilities progressively before calling a backend tool; do not assume fixed server names, tool names, or call orders.
    1. Call relevant advertised tools directly. Otherwise use search_tools(query). Prefer the device's backend tools over web search for actions on the device.
    2. Call backend tools via call_tool(name, arguments) with the schema shown by search_tools or a tools-ready message, also earlier in the chat; read /mcp/tools/<qualified-tool>/schema.json only if none was shown. Use /mcp/servers.json or /mcp/tools.index.json only if discovery is insufficient; /mcp/tools.json is for full catalog cards.
    3. Call the exact qualified backend tool from the catalog with schema-shaped arguments, including requested target values. If no tool fits, clarify or report the unsupported action.
    Batch independent calls with known inputs, including several reads after one action; respect dependencies. When one catalog tool completes a step that would otherwise take several calls, prefer it. Avoid standalone narration or TODO rounds. For complex requests, replace update_todo with this turn's intents; statuses: pending, in_progress, completed. Send updates with useful calls. A finish with outcome success closes the remaining checklist items, so do not spend a round only to mark todos completed.
    Inspect MCP isError, structuredContent.status, details.reason and upstreamCode. Correct the cause or report the limit, rather than repeat rejected commands. Check state/capacity as needed. Transport failure leaves mutating outcomes unknown: read state before retrying.

    Completion:
    Every successful run should use finish with {"response":"...","outcome":"success"} exactly once. For actions, first reconcile every requested target, order, setting and readout against current backend business tool results. Accepted calls and completed TODOs alone do not prove full completion. Changed state invalidates old readings. Report actual values and distinguish completed, failed and unknown parts.
    Use outcome="partial" or "blocked" when anything remains unresolved; success requires the whole request. response is ${words.conciseResponse}, with no internal plans, TODOs or XML/parameter tags; outcome is a separate JSON field. Discovery and progress alone are not completion.
""".trimIndent()

private const val SESSION_CONTEXT_HEADER = "Current debug session context for continuity:"

/**
 * Fails a model call whose stream has sent nothing at all after [millis], as a recoverable transport failure, so the
 * runtime's model retry sends it again. A service's first event typically comes within a few seconds, but a call can
 * wait minutes with no answer, and the conversation would stand still that long.
 */
internal fun Flow<ModelEvent>.withFirstEventTimeout(millis: Long): Flow<ModelEvent> = channelFlow {
    val started = CompletableDeferred<Unit>()
    launch {
        try {
            collect { event ->
                started.complete(Unit)
                send(event)
            }
        } finally {
            started.complete(Unit)
        }
    }
    if (withTimeoutOrNull(millis) { started.await() } == null) {
        throw ModelClientException(
            code = RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED,
            recoverable = true,
            message = "The model sent nothing in ${millis / 1000} s; asking again.",
        )
    }
}

internal const val FIRST_EVENT_TIMEOUT_MS = 20_000L

/** Loading the on-device model (and falling back from the NPU to the CPU) can take minutes on a cold start. */
@cc.noharry.agent.appserver.AppServerPluginApi
const val ON_DEVICE_FIRST_EVENT_TIMEOUT_MS = 240_000L

/** The request as the model reads it: the note field on the tools that take it, and on the calls it wrote one for. */
@OptIn(ExperimentalAgentRuntimeApi::class)
private fun ModelRequest.withToolNote(note: ToolNote, written: Map<String, String>): ModelRequest = copy(
    tools = tools.map { if (note.applies(it.name)) it.copy(schemaJson = note.inSchema(it.schemaJson)) else it },
    messages = messages.map { message ->
        val text = message.toolCallId?.let(written::get)
        val arguments = message.toolArgumentsJson
        if (text == null || arguments == null || message.role == "tool") message
        else message.copy(toolArgumentsJson = note.with(arguments, text))
    },
)
