@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core


import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUseResult
import cc.noharry.agent.kernel.api.HookContext

fun unsupportedToolResult(toolName: String): String =
    """{"ok":false,"code":"${RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode}","tool":"${escapeJson(toolName)}"}"""

fun inactiveBackendToolResult(toolName: String, schemaPath: String): String =
    """{"ok":false,"code":"${RuntimeErrorCode.INACTIVE_BACKEND_TOOL.wireCode}","tool":"${escapeJson(toolName)}","schemaPath":"${escapeJson(schemaPath)}","message":"Read schema.json before calling this backend tool."}"""

fun unknownBackendToolResult(toolName: String): String =
    """{"ok":false,"code":"${RuntimeErrorCode.UNKNOWN_BACKEND_TOOL.wireCode}","tool":"${escapeJson(toolName)}","message":"Tool is not in the current MCP catalog."}"""

/** Acknowledge client-shaped web_search so the model does not retry as MCP. */
fun hostedWebSearchAcknowledgedResult(query: String): String =
    """{"ok":true,"hosted":true,"tool":"web_search","query":"${escapeJson(query)}","message":"Server-side web_search was handled by the model provider."}"""

/** Prefer `query` / `q` from tool arguments JSON for hosted search UI. */
fun extractWebSearchQuery(argumentsJson: String): String {
    val root = try {
        Json.parseToJsonElement(argumentsJson) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return ""
    return root["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        .ifBlank { root["q"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty() }
}

fun workspaceMissingResult(): String =
    """{"ok":false,"code":"${RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode}"}"""

fun backendNotConfiguredResult(): String =
    """{"ok":false,"code":"${RuntimeErrorCode.CAPABILITY_BACKEND_NOT_CONFIGURED.wireCode}"}"""

fun mcpProviderNotConfiguredResult(): String =
    """{"ok":false,"code":"${RuntimeErrorCode.CAPABILITY_BACKEND_NOT_CONFIGURED.wireCode}","message":"MCP provider is not configured."}"""

fun backendFailedResult(message: String, retryable: Boolean): String =
    """{"ok":false,"code":"${RuntimeErrorCode.BACKEND_CALL_FAILED.wireCode}","message":"${escapeJson(message)}","retryable":$retryable}"""

fun backendRepeatedFailureResult(toolName: String, tripped: TrippedToolCall): String {
    val lastErrorCode = tripped.lastFailureCode
        ?.let { ""","lastErrorCode":"${escapeJson(it)}"""" }
        .orEmpty()
    val message = when (tripped.scope) {
        TrippedToolCallScope.IDENTICAL_ARGUMENTS ->
            "Tool $toolName already failed ${tripped.failureCount} times with identical arguments " +
                "in this run; the call was suppressed and not executed again. Do not retry this call. " +
                "Report the failure to the user in finish(response)."
        TrippedToolCallScope.TOOL ->
            "Tool $toolName failed ${tripped.failureCount} times in a row in this run (including with " +
                "different arguments); it appears unavailable in this environment, so the call was not " +
                "executed. Do not call this tool again in this run. Use a different capability if one " +
                "fits, otherwise report the failure to the user in finish(response)."
    }
    return """{"ok":false,"code":"${RuntimeErrorCode.BACKEND_CALL_FAILED.wireCode}","tool":"${escapeJson(toolName)}","message":"${escapeJson(message)}"$lastErrorCode,"retryable":false}"""
}

fun execEngineNotConfiguredResult(): String =
    """{"ok":false,"code":"${RuntimeErrorCode.EXEC_ENGINE_NOT_CONFIGURED.wireCode}","message":"Exec engine is not configured.","retryable":false}"""

fun execFailedResult(code: String, message: String, retryable: Boolean = false): String =
    """{"ok":false,"code":"${escapeJson(code)}","message":"${escapeJson(message)}","retryable":$retryable}"""

fun execSucceededResult(output: String): String =
    """{"ok":true,"output":"${escapeJson(output)}"}"""

fun hookDeniedResult(code: String, message: String): String =
    """{"ok":false,"code":"${escapeJson(code)}","message":"${escapeJson(message)}","retryable":false}"""

fun hookBlockedAndContinueResult(reason: String, prompt: String): String =
    """{"ok":false,"code":"${RuntimeErrorCode.HOOK_DENIED.wireCode}","message":"${escapeJson(reason)}","prompt":"${escapeJson(prompt)}","retryable":true}"""

fun toolArgumentsInvalidResult(toolName: String, message: String): String =
    """{"ok":false,"code":"${RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode}","tool":"${escapeJson(toolName)}","message":"${escapeJson(message)}","retryable":false}"""

fun todoUpdatedResult(items: List<TodoItem>): String =
    buildJsonObject {
        put("ok", true)
        put("todoCount", items.size)
        putJsonArray("todos") {
            for (item in items) {
                add(
                    buildJsonObject {
                        put("content", item.content)
                        put("status", item.status.name)
                    },
                )
            }
        }
    }.toString()

fun invalidFinishResponseResult(): String =
    """{"ok":false,"code":"$INVALID_FINISH_RESPONSE_CODE","message":"$INVALID_FINISH_RESPONSE_MESSAGE"}"""

fun isPureNarrationFinishResponse(response: String): Boolean {
    val normalized = response.trim().replace(Regex("""\s+"""), " ")
    if (normalized.isBlank()) return false
    if (normalized == "已根据可用工具完成请求。" || INTERNAL_FINISH_PLANNING.containsMatchIn(normalized)) return true
    if (normalized.hasConcreteResultContent()) return false
    return normalized.hasProspectiveFinishNarration() ||
        (normalized.length < SHORT_NARRATION_RESPONSE_LIMIT && normalized.hasGenericNonAnswerNarration())
}

fun MaterializerContext.toHookContext(approvedConfirmation: Boolean = false): HookContext = HookContext(
    sessionId = sessionId,
    runId = runId,
    requestId = requestId,
    approvedConfirmation = approvedConfirmation,
)

fun DynamicToolSource.toToolSource(): ToolSource = when (this) {
    DynamicToolSource.NATIVE_READ -> ToolSource.NATIVE_READ
    DynamicToolSource.NATIVE_WRITE -> ToolSource.NATIVE_WRITE
    DynamicToolSource.NETWORK -> ToolSource.NETWORK
    DynamicToolSource.EXEC -> ToolSource.EXEC
    DynamicToolSource.NATIVE_TODO -> ToolSource.NATIVE_TODO
    DynamicToolSource.BACKEND -> ToolSource.BACKEND
    DynamicToolSource.TERMINAL -> ToolSource.TERMINAL
    DynamicToolSource.INTERACTION -> ToolSource.INTERACTION
}





fun MutableList<ModelMessage>.appendToolResult(
    event: ModelEvent.ToolCall,
    result: String,
    reasoningContent: String? = null,
    thinkingSignature: String? = null,
    images: List<ModelImage> = emptyList(),
) {
    this += event.toAssistantToolCallMessage(reasoningContent, thinkingSignature)
    this += event.toToolResultMessage(result, images)
}

suspend fun HookChainPort.authorizeToolUse(
    event: ModelEvent.ToolCall,
    source: DynamicToolSource,
    context: MaterializerContext,
    backendToolName: String? = null,
    parsedArguments: JsonObject? = null,
): ToolAuthorization {
    if (parsedArguments == null) {
        when (val parsed = parseToolArguments(event.name, event.argumentsJson)) {
            is ParsedToolArguments.Invalid -> return ToolAuthorization.Denied(parsed.resultJson)
            is ParsedToolArguments.Valid -> Unit
        }
    }
    val toolUse = ToolUse(
        toolName = event.name,
        argumentsJson = event.argumentsJson,
        source = source.toToolSource(),
        toolCallId = event.id,
    )
    return when (val decision = preToolUseOutcome(toolUse, context.toHookContext())) {
        is PreToolUseOutcome.Allowed -> ToolAuthorization.Allowed(decision.toolUse)
        is PreToolUseOutcome.Denied -> ToolAuthorization.Denied(hookDeniedResult(decision.code, decision.reason))
        is PreToolUseOutcome.BlockedAndContinue -> ToolAuthorization.Denied(
            hookBlockedAndContinueResult(decision.reason, decision.prompt),
        )
        is PreToolUseOutcome.RequiresConfirmation -> ToolAuthorization.RequiresConfirmation(
            prompt = decision.prompt,
            pending = PendingNativeToolUse(
                toolUse = decision.toolUse,
                context = context,
                backendToolName = backendToolName,
                toolCallId = event.id,
            ),
        )
    }
}

suspend fun HookChainPort.recordPostToolUse(
    toolUse: ToolUse,
    resultJson: String,
    context: MaterializerContext,
) {
    val result = if (toolUse.source == ToolSource.BACKEND) {
        backendToolCallResult(resultJson)
    } else {
        toolUseResult(resultJson)
    }
    postToolUse(toolUse, result, context.toHookContext())
}



sealed interface RoundOutcome {
    data class Finished(
        val response: String,
        val completionOutcome: cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome =
            cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome.SUCCEEDED,
        val messages: List<ModelMessage> = emptyList(),
        /** False when input accepted during this round was found at finish; the loop continues instead. */
        val sealed: Boolean = true,
        /**
         * True only for a finish that explicitly declared outcome "success". Legacy response-only finishes and
         * implicit text completion keep the checklist as the model last reported it.
         */
        val closesChecklist: Boolean = false,
    ) : RoundOutcome

    data class Failed(
        val failure: ToolLoopFailure,
        val modelFailure: Boolean = false,
        val toolResultsCommitted: Boolean = false,
    ) : RoundOutcome

    data class RequiresConfirmation(
        val prompt: String,
        val pendingToolUse: PendingNativeToolUse? = null,
        val messages: List<ModelMessage> = emptyList(),
    ) : RoundOutcome

    data class NeedsUserInput(
        val question: String,
        val messages: List<ModelMessage> = emptyList(),
    ) : RoundOutcome

    data class ToolResults(
        val messages: List<ModelMessage>,
        val usedFinishResponseRetry: Boolean = false,
        val usedObligationCorrection: Boolean = false,
        /** True when this round injected an empty-completion continue nudge (no tools / no finish). */
        val usedEmptyCompletionContinue: Boolean = false,
    ) : RoundOutcome
}

sealed interface ToolAuthorization {
    data class Allowed(val toolUse: ToolUse) : ToolAuthorization

    data class Denied(val resultJson: String) : ToolAuthorization

    data class RequiresConfirmation(
        val prompt: String,
        val pending: PendingNativeToolUse,
    ) : ToolAuthorization
}

private fun JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun String.unescapeJsonString(): String =
    replace("\\\"", "\"").replace("\\\\", "\\")

private fun escapeJson(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private val RESPONSE_FIELD = Regex(""""response"\s*:\s*"((?:\\.|[^"])*)"""")

private val INTERNAL_FINISH_PLANNING = Regex(
    """\b(?:now|let me|i(?:'ll| will))\s+(?:call\s+)?finish\b|\bfinish with (?:a |the )?.*(?:report|response|summary)""",
    RegexOption.IGNORE_CASE,
)

private fun String.hasProspectiveFinishNarration(): Boolean =
    PROSPECTIVE_FINISH_NARRATION.any { it.containsMatchIn(this) }

private fun String.hasConcreteResultContent(): Boolean =
    CONCRETE_RESULT_MARKERS.any { contains(it, ignoreCase = true) }

private fun String.hasGenericNonAnswerNarration(): Boolean =
    GENERIC_NON_ANSWER_NARRATION.any { it.containsMatchIn(this) }

private const val SHORT_NARRATION_RESPONSE_LIMIT = 120
const val INVALID_FINISH_RESPONSE_MESSAGE =
    "finish requires a JSON object with a nonblank string response containing the complete user-facing final answer. " +
        "Only response and outcome are allowed. Put outcome in its own field: success, partial or blocked. " +
        "Do not put a nested finish JSON object, tool-call XML/parameter tags or internal planning in response. Correct the arguments; " +
        "there is no further turn after an accepted finish."
const val INVALID_FINISH_RESPONSE_CODE = "INVALID_FINISH_RESPONSE"
/** Sent once when a subagent replies with text instead of finish. */
const val SUBAGENT_TEXT_REPLY_MESSAGE =
    "You replied without calling finish. You are a background subagent: only what you pass to finish reaches the main " +
        "agent, and a plan such as \"I will search…\" is not a result. If you still have searching or checking to do, do it " +
        "now; then call finish with the complete result."
/**
 * Injected once when the model ends a turn with no tool calls and no implicit/explicit finish.
 * Mirrors Claude Code / Codex stop-hook "block and continue" and pure-narration finish retry:
 * prefer one budgeted nudge over hard-failing mid-task empty completions.
 */
const val EMPTY_COMPLETION_CONTINUE_MESSAGE =
    "Runtime notice: you ended a model turn with no tool calls and no finish(response). " +
        "Continue the task now: call the next required tool(s), or call finish with the complete user-facing final answer."
const val EMPTY_COMPLETION_CONTINUE_MARKER = "EMPTY_COMPLETION_CONTINUE"

fun emptyCompletionContinueMessages(partialText: String): List<ModelMessage> {
    val messages = mutableListOf<ModelMessage>()
    val trimmed = partialText.trim()
    if (trimmed.isNotEmpty()) {
        messages += ModelMessage(role = "assistant", content = trimmed)
    }
    messages += ModelMessage(
        role = "user",
        content = "$EMPTY_COMPLETION_CONTINUE_MARKER\n$EMPTY_COMPLETION_CONTINUE_MESSAGE",
    )
    return messages
}



private val PROSPECTIVE_FINISH_NARRATION = listOf(
    Regex("""\blet me (?:now )?(?:update|finish|call|provide)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bi will now (?:update|finish|provide|write|compose)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bi'll (?:now )?(?:update|finish|provide|write|compose)\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:call|use)\s+finish\b""", RegexOption.IGNORE_CASE),
    Regex("""\b(?:provide|write|compose) (?:the )?final (?:response|answer|summary)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bupdate (?:the )?todo\b""", RegexOption.IGNORE_CASE),
    Regex("""\bfinish with (?:a |the )?.*(?:response|answer|summary)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bwrap up with (?:the )?.*(?:response|answer|summary)\b""", RegexOption.IGNORE_CASE),
    Regex("""(我来|我会|我将|现在|准备).{0,8}(更新|调用|提供).{0,8}(todo|finish|最终答复|最终回答|总结)"""),
)

private val GENERIC_NON_ANSWER_NARRATION = listOf(
    Regex("""\ball (?:tasks|actions|steps|operations) (?:are )?(?:complete|completed|done)\b""", RegexOption.IGNORE_CASE),
    Regex("""\bcompleted successfully\b""", RegexOption.IGNORE_CASE),
    Regex("""\boperations are complete\b""", RegexOption.IGNORE_CASE),
    Regex("""^\(?same reasoning as previous tool call\)?$""", RegexOption.IGNORE_CASE),
)

private val CONCRETE_RESULT_MARKERS = listOf(
    "已为",
    "已帮",
    "已找到",
    "已搜索",
    "已规划",
    "已计算",
    "已开启",
    "已播放",
    "已完成",
    "kilometer",
    "miles",
    "found",
    "calculated",
    "planned",
    "opened",
    "played",
    "saved",
    "wrote",
    "created",
    "set to",
    "turned",
)

fun parseTodoItems(argumentsJson: String): List<TodoItem> {
    val json = try {
        Json.parseToJsonElement(argumentsJson) as? JsonObject
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } ?: return emptyList()
    val todosArray = json["todos"] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return todosArray.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val content = obj.stringField("content") ?: return@mapNotNull null
        val statusWire = obj.stringField("status") ?: return@mapNotNull null
        val status = runCatching { TodoStatus.fromWire(statusWire) }.getOrNull() ?: return@mapNotNull null
        TodoItem(content, status)
    }
}

internal data class CompactedMessages(
    val messages: List<ModelMessage>,
    val compacted: Boolean,
)

internal data class ModelRetryRound(
    val outcome: RoundOutcome,
    val modelRef: cc.noharry.agent.kernel.api.model.ModelRef?,
)

internal fun RoundOutcome.Failed.isRetryableModelFailure(): Boolean =
    modelFailure && failure.recoverable && !toolResultsCommitted
