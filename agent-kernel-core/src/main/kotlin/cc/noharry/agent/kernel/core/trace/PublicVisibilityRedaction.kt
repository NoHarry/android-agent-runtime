@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.trace
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad
import cc.noharry.agent.kernel.core.loop.LoopBudgetWarning
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

class NativePublicEventBuffer(
    private val runId: String,
    private val redactor: TraceRedactor,
    private val emitEvent: suspend (AgentEvent) -> Unit = {},
) {
    private val events = mutableListOf<AgentEvent>()

    suspend fun recordThinking(text: String) {
        add(
            AgentEvent.ThinkingDelta(
                runId = runId,
                text = redactor.redact(text),
            ),
        )
    }

    suspend fun recordText(text: String) {
        add(
            AgentEvent.TextDelta(
                runId = runId,
                text = redactor.redact(text),
            ),
        )
    }

    suspend fun recordUsage(delta: cc.noharry.agent.kernel.api.model.ModelEvent.UsageDelta) {
        add(
            AgentEvent.TokenUsageDelta(
                runId = runId,
                inputTokens = delta.inputTokens,
                outputTokens = delta.outputTokens,
                reasoningTokens = delta.reasoningTokens,
                cacheReadTokens = delta.cacheReadTokens,
                cacheWriteTokens = delta.cacheWriteTokens,
            ),
        )
    }

    suspend fun recordRetryAttempted(
        attempt: Int,
        delayMillis: Long,
        modelRef: ModelRef?,
        fallbackModelRef: ModelRef?,
    ) {
        add(
            AgentEvent.RetryAttempted(
                runId = runId,
                attempt = attempt,
                delayMillis = delayMillis,
                modelRef = modelRef,
                fallbackModelRef = fallbackModelRef,
            ),
        )
    }

    suspend fun recordBudgetWarning(warning: LoopBudgetWarning) {
        add(
            AgentEvent.BudgetWarning(
                runId = runId,
                roundsRemaining = warning.roundsRemaining,
                toolCallsRemaining = warning.toolCallsRemaining,
            ),
        )
    }

    suspend fun recordSearchActivity(activity: cc.noharry.agent.kernel.api.model.ModelEvent.SearchActivity) {
        add(
            AgentEvent.SearchActivity(
                runId = runId,
                provider = activity.provider,
                queryPreview = redactor.redact(activity.queryPreview).steeringPreview(),
                resultCount = activity.resultCount,
            ),
        )
    }

    suspend fun recordCompactionApplied(
        beforeMessageCount: Int,
        afterMessageCount: Int,
        droppedCount: Int,
        summaryBytes: Int,
    ) {
        add(
            AgentEvent.CompactionApplied(
                runId = runId,
                beforeMessageCount = beforeMessageCount,
                afterMessageCount = afterMessageCount,
                droppedCount = droppedCount,
                summaryBytes = summaryBytes,
            ),
        )
    }

    suspend fun recordEmptyCompletionObserved(
        modelRound: Int,
        kind: String,
        continued: Boolean,
        continueCount: Int,
        textChars: Int,
        thinkingChars: Int,
        hadStateAdvancingWork: Boolean,
        successfulBackendToolCount: Int,
    ) {
        add(
            AgentEvent.EmptyCompletionObserved(
                runId = runId,
                modelRound = modelRound,
                kind = kind,
                continued = continued,
                continueCount = continueCount,
                textChars = textChars,
                thinkingChars = thinkingChars,
                hadStateAdvancingWork = hadStateAdvancingWork,
                successfulBackendToolCount = successfulBackendToolCount,
            ),
        )
    }

    suspend fun recordSteeringAccepted(requestId: String, text: String) {
        add(
            AgentEvent.SteeringAccepted(
                runId = runId,
                requestId = requestId,
                textPreview = redactor.redact(text).steeringPreview(),
            ),
        )
    }

    /** Public content is redacted; the original is recorded by LoopSessionRecorder. */
    suspend fun recordInteraction(fact: cc.noharry.agent.kernel.api.SessionEventPayload.UserInteraction) {
        add(AgentEvent.UserInteraction(runId, fact.copy(
            text = redactor.redact(fact.text), options = fact.options.map(redactor::redact),
        )))
    }

    suspend fun recordStarted(toolCallId: String, toolName: String, argumentsJson: String) {
        add(AgentEvent.NativeToolCallStarted(
            runId = runId,
            toolName = toolName,
            arguments = redactedPublicJson(argumentsJson, redactor),
            toolCallId = toolCallId,
        ))
    }

    suspend fun recordFinished(toolCallId: String, toolName: String, resultJson: String) {
        add(AgentEvent.NativeToolCallFinished(
            runId = runId,
            toolName = toolName,
            result = publicResultJson(toolName, resultJson),
            toolCallId = toolCallId,
        ))
        workspaceFileChangedEvent(toolCallId, toolName, resultJson)?.let { add(it) }
    }

    /**
     * Tool results are metadata-redacted for hosts, with one exception: `update_todo` is a
     * progress/display protocol (ACP `plan_update` equivalent), so its normalized todo list
     * passes through [TraceRedactor] — the same treatment as streamed model text — letting
     * hosts render plan boards from the event stream without reading workspace state.
     */
    private fun publicResultJson(toolName: String, resultJson: String): String =
        if (toolName == UPDATE_TODO_TOOL_NAME) {
            redactor.redact(resultJson)
        } else {
            redactedPublicJson(resultJson, redactor)
        }

    suspend fun recordNetworkActivity(
        toolCallId: String,
        toolName: String,
        domain: String,
        decision: String,
        statusCode: Int? = null,
        truncated: Boolean = false,
    ) {
        add(
            AgentEvent.NetworkToolActivity(
                runId = runId,
                toolName = toolName,
                decision = decision,
                toolCallId = toolCallId,
                domain = redactor.redact(domain),
                statusCode = statusCode,
                truncated = truncated,
            ),
        )
    }

    suspend fun recordExecOutput(text: String) {
        val redacted = redactor.redact(text).take(MAX_EXEC_OUTPUT_DELTA_CHARS)
        if (redacted.isNotBlank()) {
            add(AgentEvent.ExecOutputDelta(runId = runId, text = redacted))
        }
    }

    fun snapshot(): List<AgentEvent> = events.toList()

    private suspend fun add(event: AgentEvent) {
        events += event
        emitEvent(event)
    }

    private fun workspaceFileChangedEvent(
        toolCallId: String,
        toolName: String,
        resultJson: String,
    ): AgentEvent.WorkspaceFileChanged? {
        if (toolName != WRITE_FILE_TOOL_NAME && toolName != EDIT_FILE_TOOL_NAME) return null
        val root = parseJsonObject(resultJson) ?: return null
        if (root.string("ok") != "true") return null
        val path = root.string("path") ?: return null
        return AgentEvent.WorkspaceFileChanged(
            runId = runId,
            path = redactor.redact(path),
            toolName = toolName,
            toolCallId = toolCallId,
            bytes = root.string("bytes")?.toLongOrNull() ?: 0L,
            replacements = root.string("replacements")?.toIntOrNull(),
            addedLines = root.string("added_lines")?.toIntOrNull() ?: 0,
            removedLines = root.string("removed_lines")?.toIntOrNull() ?: 0,
            diffTimedOut = root.string("diff_timed_out")?.toBooleanStrictOrNull() ?: false,
        )
    }
}

fun RuntimeResult.redactedForPublic(redactor: TraceRedactor): RuntimeResult =
    copy(response = redactor.redact(response))

fun RuntimeHandoff.redactedForPublic(redactor: TraceRedactor): RuntimeHandoff =
    copy(
        summary = redactor.redact(summary),
        done = done.map(redactor::redact),
        nextActions = nextActions.map(redactor::redact),
        task = task?.let(redactor::redact),
        decisions = decisions.map(redactor::redact),
        triedAndAbandoned = triedAndAbandoned.map(redactor::redact),
        doNotRepeat = doNotRepeat.map(redactor::redact),
    )

fun OpenConfusion.redactedForPublic(redactor: TraceRedactor): OpenConfusion =
    copy(question = redactor.redact(question))

fun RuntimeBlocker.redactedForPublic(redactor: TraceRedactor): RuntimeBlocker =
    copy(
        message = redactor.redact(message),
        handoff = handoff?.redactedForPublic(redactor),
    )

fun RuntimeWorkpad.redactedForPublic(redactor: TraceRedactor): RuntimeWorkpad =
    copy(
        readPaths = readPaths.map(redactor::redact),
        writeArtifacts = writeArtifacts.map(redactor::redact),
        toolRegistryWarnings = toolRegistryWarnings.map(redactor::redact),
        toolRegistryFailures = toolRegistryFailures.map(redactor::redact),
        planSummaries = planSummaries.map(redactor::redact),
        blockers = blockers.map { it.redactedForPublic(redactor) },
        completionBar = completionBar.map { item -> item.copy(label = redactor.redact(item.label)) },
    )

fun RuntimeEvidence.redactedForPublic(redactor: TraceRedactor): RuntimeEvidence =
    copy(summary = redactor.redact(summary))

fun RuntimeProtocolRequest.redactedForPublic(
    redactor: TraceRedactor,
): RuntimeProtocolRequest = copy(payloadJson = redactedPublicJson(payloadJson, redactor))

fun ProtocolRequestCloseLoopResult.redactedForPublic(
    redactor: TraceRedactor,
): ProtocolRequestCloseLoopResult = when (this) {
    is ProtocolRequestCloseLoopResult.Updated -> copy(protocolRequest = protocolRequest.redactedForPublic(redactor))
    is ProtocolRequestCloseLoopResult.AlreadyApplied -> copy(protocolRequest = protocolRequest.redactedForPublic(redactor))
    is ProtocolRequestCloseLoopResult.AlreadyTerminal -> copy(protocolRequest = protocolRequest.redactedForPublic(redactor))
    is ProtocolRequestCloseLoopResult.Expired -> copy(protocolRequest = protocolRequest.redactedForPublic(redactor))
    is ProtocolRequestCloseLoopResult.InvalidState,
    ProtocolRequestCloseLoopResult.NotFound,
    -> this
}

private fun redactedPublicJson(value: String, redactor: TraceRedactor): String =
    if (redactor.redact(value) == value) REDACTED_PUBLIC_JSON else REDACTED_SENSITIVE_PUBLIC_JSON

private fun parseJsonObject(value: String): JsonObject? =
    try {
        Json.parseToJsonElement(value) as? JsonObject
    } catch (_: RuntimeException) {
        null
    }

private fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun String.steeringPreview(): String =
    replace(Regex("\\s+"), " ").trim().take(MAX_STEERING_PREVIEW_CHARS)

private const val MAX_STEERING_PREVIEW_CHARS = 120
private const val MAX_EXEC_OUTPUT_DELTA_CHARS = 2_000
private const val WRITE_FILE_TOOL_NAME = "write_file"
private const val EDIT_FILE_TOOL_NAME = "edit_file"
private const val UPDATE_TODO_TOOL_NAME = "update_todo"
private const val REDACTED_PUBLIC_JSON = """{"redacted":true}"""
private const val REDACTED_SENSITIVE_PUBLIC_JSON = """{"redacted":true,"sensitive":true}"""
