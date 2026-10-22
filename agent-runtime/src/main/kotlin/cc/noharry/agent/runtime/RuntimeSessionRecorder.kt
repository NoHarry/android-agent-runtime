@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.SessionAttachmentRef
import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.runtime.eventlog.toSessionAppendPayload
import cc.noharry.agent.runtime.eventlog.toAttachmentRef
import cc.noharry.agent.runtime.eventlog.toSessionPayload
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import cc.noharry.agent.kernel.api.ToolUseResult
import cc.noharry.agent.kernel.api.WorkspaceExecutionTelemetry
import cc.noharry.agent.kernel.core.DynamicToolSource
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.RuntimeVfsPerformanceCounters
import cc.noharry.agent.kernel.core.SESSION_TOOL_STATUS_COMPLETED
import cc.noharry.agent.kernel.core.SESSION_TOOL_STATUS_FAILED
import cc.noharry.agent.kernel.core.SESSION_TOOL_STATUS_STARTED
import cc.noharry.agent.kernel.core.SESSION_VFS_STATUS_FAILED
import cc.noharry.agent.kernel.core.SESSION_VFS_STATUS_OK
import cc.noharry.agent.kernel.core.SessionStore
import cc.noharry.agent.kernel.core.StoredModelCall
import cc.noharry.agent.kernel.core.StoredRun
import cc.noharry.agent.kernel.core.StoredRunStatusUpdate
import cc.noharry.agent.kernel.core.StoredSession
import cc.noharry.agent.kernel.core.StoredSessionMessage
import cc.noharry.agent.kernel.core.StoredToolEvent
import cc.noharry.agent.kernel.core.StoredToolResult
import cc.noharry.agent.kernel.core.StoredUsageDelta
import cc.noharry.agent.kernel.core.StoredVfsOperation
import cc.noharry.agent.kernel.core.TodoItem
import cc.noharry.agent.kernel.core.TodoStatus
import cc.noharry.agent.kernel.core.VfsOperationTimings
import cc.noharry.agent.kernel.core.backendToolCallResult
import cc.noharry.agent.kernel.core.parseTodoItems
import cc.noharry.agent.kernel.core.toolUseResult
import cc.noharry.agent.runtime.eventlog.parseJsonOrText
import cc.noharry.agent.kernel.api.SessionJson
import cc.noharry.agent.kernel.core.SESSION_MODEL_CALL_SOURCE_ROUND
import cc.noharry.agent.kernel.core.SESSION_MODEL_CALL_SOURCE_COMPACTION

internal class RuntimeSessionRecorder(
    private val store: SessionStore?,
    private val eventLog: SessionEventLog? = null,
    private val nowEpochMillis: () -> Long,
) : cc.noharry.agent.kernel.core.LoopSessionRecorder {
    private val pendingLock = Any()
    private val pendingToolRecordings = mutableListOf<PendingToolRecording>()
    private val counterBaselines = mutableMapOf<String, Map<String, Long>>()
    private val modelCallSequences = mutableMapOf<String, Int>()
    private val eventLock = Any()
    private val runIdentities = mutableMapOf<String, RunIdentity>()
    private val runStartSequences = mutableMapOf<String, Long>()
    private val toolStartSequences = mutableMapOf<String, Long>()
    private val chunkSequences = mutableMapOf<String, MutableList<Long>>()
    private val finishedEventRuns = mutableSetOf<String>()
    private val preparationTimings = mutableMapOf<String, MutableMap<String, Long>>()
    private val modelRequestStarts = mutableMapOf<String, Long>()
    private val firstContentTimings = mutableMapOf<String, Long>()

    fun startRun(request: AgentRequest, runId: String) {
        startRun(request, runId, RuntimeRunStatus.RUNNING)
    }

    fun startQueuedRun(request: AgentRequest, runId: String) {
        startRun(request, runId, RuntimeRunStatus.QUEUED)
    }

    private fun startRun(request: AgentRequest, runId: String, initialStatus: RuntimeRunStatus) {
        initializeRunState(request, runId)
        persistStoreSafely { persistRunStart(request, runId, initialStatus) }
        recordRunStartedEvent(request, runId, initialStatus)
        if (initialStatus != RuntimeRunStatus.RUNNING) {
            recordRunStatusEvents(runId, initialStatus, response = "", errorCode = null)
        }
    }

    private fun initializeRunState(request: AgentRequest, runId: String) {
        synchronized(eventLock) {
            runIdentities[runId] = RunIdentity(request.sessionId, request.requestId)
            runStartSequences.remove(runId)
            finishedEventRuns.remove(runId)
        }
        synchronized(counterBaselines) {
            counterBaselines[runId] = RuntimeVfsPerformanceCounters.snapshot()
        }
    }

    private fun persistRunStart(
        request: AgentRequest,
        runId: String,
        initialStatus: RuntimeRunStatus = RuntimeRunStatus.RUNNING,
    ) {
        val now = nowEpochMillis()
        val modelRef = request.modelRef?.normalized()
        store?.upsertSession(
            StoredSession(
                sessionId = request.sessionId,
                title = request.input.sessionTitle(),
                modelId = modelRef?.modelId.orEmpty(),
                createdAtEpochMillis = now,
                updatedAtEpochMillis = now,
            ),
        )
        store?.upsertRun(
            StoredRun(
                runId = runId,
                requestId = request.requestId,
                sessionId = request.sessionId,
                status = initialStatus.name,
                scene = request.scene.orEmpty(),
                providerId = modelRef?.providerId.orEmpty(),
                modelId = modelRef?.modelId.orEmpty(),
                startedAtEpochMillis = now,
                updatedAtEpochMillis = now,
                modelRef = modelRef.wireValueOrEmpty(),
                variant = modelRef?.variant.orEmpty(),
            ),
        )
    }

    private fun recordRunStartedEvent(
        request: AgentRequest,
        runId: String,
        initialStatus: RuntimeRunStatus = RuntimeRunStatus.RUNNING,
    ) {
        synchronized(eventLock) {
            appendEventSafely(
                SessionEventAppend(
                    sessionId = request.sessionId,
                    runId = runId,
                    requestId = request.requestId,
                    type = SessionEventType.RUN_STARTED,
                    payload = SessionEventPayload.RunLifecycle(
                        status = initialStatus.name,
                        modelRef = request.modelRef.wireValueOrEmpty(),
                        scene = request.scene.orEmpty(),
                    ),
                ),
            )?.let { runStartSequences[runId] = it.sequence }
            if (request.input.isNotBlank()) {
                appendEventSafely(
                    SessionEventAppend(
                        sessionId = request.sessionId,
                        runId = runId,
                        requestId = request.requestId,
                        type = SessionEventType.USER_MESSAGE,
                        payload = SessionEventPayload.UserMessage(text = request.input),
                        sourceEventSequences = runStartSource(runId),
                    ),
                )
            }
        }
    }

    override fun appendMessage(context: MaterializerContext, message: ModelMessage) {
        flushPendingToolRecordings()
        persistStoreSafely {
            store?.appendMessage(
                StoredSessionMessage(
                    sessionId = context.sessionId,
                    runId = context.runId,
                    role = message.role,
                    content = message.content,
                    toolCallId = message.toolCallId.orEmpty(),
                    toolName = message.toolName.orEmpty(),
                    toolArgumentsJson = message.toolArgumentsJson.orEmpty(),
                    modelRef = context.modelRef.wireValueOrEmpty(),
                    createdAtEpochMillis = nowEpochMillis(),
                ),
            )
        }
        recordMessageEvent(context, message)
    }

    override fun recordInteraction(context: MaterializerContext, interaction: SessionEventPayload.UserInteraction) {
        synchronized(eventLock) {
            eventLog?.append(SessionEventAppend(
                sessionId = context.sessionId, runId = context.runId, requestId = context.requestId,
                type = SessionEventType.USER_INTERACTION, payload = interaction,
                sourceEventSequences = runStartSource(context.runId),
            ))
        }
    }

    override fun appendMessages(context: MaterializerContext, messages: List<ModelMessage>) {
        if (messages.isEmpty()) return
        flushPendingToolRecordings()
        messages.forEach { message ->
            persistStoreSafely {
                store?.appendMessage(
                    StoredSessionMessage(
                        sessionId = context.sessionId,
                        runId = context.runId,
                        role = message.role,
                        content = message.content,
                        toolCallId = message.toolCallId.orEmpty(),
                        toolName = message.toolName.orEmpty(),
                        toolArgumentsJson = message.toolArgumentsJson.orEmpty(),
                        modelRef = context.modelRef.wireValueOrEmpty(),
                        createdAtEpochMillis = nowEpochMillis(),
                    ),
                )
            }
            recordMessageEvent(context, message)
        }
    }

    override fun recordToolStarted(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
    ) {
        // Prefetch stages consume the preceding result; ordinary tools retain batched writes.
        if (source == DynamicToolSource.NATIVE_READ && event.name == PREFETCH_DISCOVERY_TOOL) {
            flushPendingToolRecordings()
        }
        val storeCallId = stableCallId(context.runId, event)
        val visibleCallId = modelVisibleToolCallId(context.runId, event)
        persistStoreSafely {
            store?.recordToolStarted(
                StoredToolEvent(
                    callId = storeCallId,
                    runId = context.runId,
                    sessionId = context.sessionId,
                    toolName = event.name,
                    source = source?.name.orEmpty().ifBlank { UNKNOWN_TOOL_SOURCE },
                    argumentsJson = event.argumentsJson,
                    inputHash = sha256(event.argumentsJson),
                    status = SESSION_TOOL_STATUS_STARTED,
                    startedAtEpochMillis = nowEpochMillis(),
                ),
            )
        }
        val todoMetadata = todoReportedMetadata(event)
        synchronized(eventLock) {
            appendEventSafely(
                SessionEventAppend(
                    sessionId = context.sessionId,
                    runId = context.runId,
                    requestId = context.requestId,
                    type = SessionEventType.TOOL_STARTED,
                    payload = SessionEventPayload.ToolCall(
                        toolCallId = visibleCallId,
                        name = event.name,
                        arguments = parseJsonOrText(event.argumentsJson),
                        source = source?.name.orEmpty().ifBlank { UNKNOWN_TOOL_SOURCE },
                        todoCompletion = todoMetadata["todoCompletion"].orEmpty(),
                    ),
                    sourceEventSequences = runStartSource(context.runId),
                ),
            )?.let { toolStartSequences[storeCallId] = it.sequence }
        }
    }

    override fun recordToolFinished(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
        resultJson: String,
        telemetry: WorkspaceExecutionTelemetry,
    ) = recordToolFinished(context, event, source, resultJson, telemetry, attachments = emptyList())

    /**
     * Only images the attachment store kept become references on the ToolResult, so it never names bytes that are
     * nowhere; an image the log could not keep stays inline in the MODEL_REQUEST that carries it to the model.
     */
    override fun recordToolFinishedWithImages(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
        resultJson: String,
        images: List<ModelImage>,
        telemetry: WorkspaceExecutionTelemetry,
    ) {
        val attachments = images.mapNotNull { image ->
            image.toAttachmentRef().takeIf { ref -> storeAttachment(context.sessionId, image, ref) }
        }
        recordToolFinished(context, event, source, resultJson, telemetry, attachments)
    }

    private fun recordToolFinished(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
        resultJson: String,
        telemetry: WorkspaceExecutionTelemetry,
        attachments: List<SessionAttachmentRef>,
    ) {
        val parsed = if (source == DynamicToolSource.BACKEND) {
            backendToolCallResult(resultJson)
        } else {
            toolUseResult(resultJson)
        }
        val status = if (parsed.ok) SESSION_TOOL_STATUS_COMPLETED else SESSION_TOOL_STATUS_FAILED
        val toolResult = StoredToolResult(
            callId = stableCallId(context.runId, event),
            runId = context.runId,
            status = status,
            finishedAtEpochMillis = nowEpochMillis(),
            resultJson = resultJson,
            resultHash = boundedSha256(resultJson),
            ok = parsed.ok,
            code = parsed.code,
        )
        val vfsOperation = if (source == DynamicToolSource.NATIVE_READ || source == DynamicToolSource.NATIVE_WRITE) {
            vfsOperation(context, event, status, resultJson, telemetry)
        } else {
            null
        }
        synchronized(pendingLock) {
            pendingToolRecordings += PendingToolRecording(
                result = toolResult,
                vfsOperation = vfsOperation,
                context = context,
                event = event,
                source = source,
                parsed = parsed,
                attachments = attachments,
            )
        }
    }

    /** True once [image] is in the session attachment store under [ref]; otherwise it stays inline in the request. */
    private fun storeAttachment(sessionId: String, image: ModelImage, ref: SessionAttachmentRef): Boolean {
        val log = eventLog ?: return false
        return runCatching { log.putAttachment(sessionId, ref, image.bytes) }.getOrDefault(false)
    }

    fun flushPendingToolRecordings() {
        if (store == null && eventLog == null) return
        val pending = synchronized(pendingLock) {
            if (pendingToolRecordings.isEmpty()) return
            pendingToolRecordings.toList().also { pendingToolRecordings.clear() }
        }
        pending.forEach { recording ->
            persistStoreSafely {
                store?.recordToolFinished(recording.result)
                recording.vfsOperation?.let { store?.appendVfsOperation(it) }
            }
            recordToolFinishedEvent(recording)
        }
    }

    override fun recordModelRequest(context: MaterializerContext, request: ModelRequest) {
        // Pre-model discovery also records tool results; commit them before the request
        // that consumes their activated schemas so canonical chronology stays truthful.
        flushPendingToolRecordings()
        synchronized(eventLock) {
            modelRequestStarts[context.runId] = System.nanoTime()
            firstContentTimings.remove(context.runId)
            appendEventSafely(
                SessionEventAppend(
                    sessionId = context.sessionId,
                    runId = context.runId,
                    requestId = context.requestId,
                    type = SessionEventType.MODEL_REQUEST,
                    payload = request.toSessionPayload { image, ref -> storeAttachment(context.sessionId, image, ref) },
                    sourceEventSequences = runStartSource(context.runId),
                ),
            )
        }
    }

    override fun recordModelStreamEvent(context: MaterializerContext, event: ModelEvent) {
        if (event is ModelEvent.ToolCall || event is ModelEvent.TextDelta && event.text.isNotEmpty() ||
            event is ModelEvent.ThinkingDelta && event.text.isNotEmpty()) synchronized(eventLock) {
            modelRequestStarts[context.runId]?.let { started ->
                firstContentTimings.putIfAbsent(context.runId, ((System.nanoTime() - started) / TIMING_NANOS_PER_MILLI).coerceAtLeast(0L))
            }
        }
        val payload = event.toSessionAppendPayload() ?: return
        val type = when (event) {
            is ModelEvent.ThinkingDelta, is ModelEvent.TextDelta -> SessionEventType.ASSISTANT_CHUNK
            is ModelEvent.ToolCall -> SessionEventType.MODEL_TOOL_CALL
            else -> SessionEventType.MODEL_RESPONSE_METADATA
        }
        synchronized(eventLock) {
            val stored = appendEventSafely(
                SessionEventAppend(
                    sessionId = context.sessionId,
                    runId = context.runId,
                    requestId = context.requestId,
                    type = type,
                    payload = payload,
                    sourceEventSequences = runStartSource(context.runId),
                ),
            ) ?: return
            if (type == SessionEventType.ASSISTANT_CHUNK) {
                chunkSequences.getOrPut(context.runId) { mutableListOf() } += stored.sequence
            }
        }
    }

    override fun recordUsage(context: MaterializerContext, delta: ModelEvent.UsageDelta) {
        flushPendingToolRecordings()
        persistStoreSafely {
            store?.appendUsage(
                StoredUsageDelta(
                    runId = context.runId,
                    sessionId = context.sessionId,
                    inputTokens = delta.inputTokens,
                    outputTokens = delta.outputTokens,
                    reasoningTokens = delta.reasoningTokens,
                    cacheReadTokens = delta.cacheReadTokens,
                    cacheWriteTokens = delta.cacheWriteTokens,
                    createdAtEpochMillis = nowEpochMillis(),
                ),
            )
        }
        synchronized(eventLock) {
            appendEventSafely(
                SessionEventAppend(
                    sessionId = context.sessionId,
                    runId = context.runId,
                    requestId = context.requestId,
                    type = SessionEventType.USAGE,
                    payload = SessionEventPayload.Usage(
                        inputTokens = delta.inputTokens.toLong(),
                        outputTokens = delta.outputTokens.toLong(),
                        reasoningTokens = delta.reasoningTokens.toLong(),
                        cacheReadTokens = delta.cacheReadTokens.toLong(),
                        cacheWriteTokens = delta.cacheWriteTokens.toLong(),
                    ),
                    sourceEventSequences = runStartSource(context.runId),
                ),
            )
        }
    }

    override fun nextModelCallId(runId: String): String =
        synchronized(modelCallSequences) {
            val next = (modelCallSequences[runId] ?: 0) + 1
            modelCallSequences[runId] = next
            "$runId:model-$next"
        }

    override fun recordPreparationTimings(context: MaterializerContext, timingsMs: Map<String, Long>) {
        synchronized(eventLock) {
            preparationTimings.getOrPut(context.runId) { linkedMapOf() }.putAll(timingsMs)
        }
    }

    private fun takeModelTimings(runId: String): SessionJson = synchronized(eventLock) {
        val timings = preparationTimings.remove(runId).orEmpty().toMutableMap()
        firstContentTimings.remove(runId)?.let { timings["firstContentMs"] = it }
        modelRequestStarts.remove(runId)?.let {
            timings["modelObservedMs"] = ((System.nanoTime() - it) / TIMING_NANOS_PER_MILLI).coerceAtLeast(0L)
        }
        SessionJson.Object(timings.mapValues { SessionJson.Number(it.value.toString()) })
    }

    override fun recordModelCall(call: StoredModelCall) {
        persistStoreSafely {
            store?.appendModelCall(call)
        }
        val identity = synchronized(eventLock) { runIdentities[call.runId] }
        if (identity != null) {
            synchronized(eventLock) {
                val finished = appendEventSafely(
                    SessionEventAppend(
                        sessionId = call.sessionId,
                        runId = call.runId,
                        requestId = identity.requestId,
                        type = SessionEventType.MODEL_CALL_FINISHED,
                        payload = SessionEventPayload.ModelCallFinished(
                            callId = call.callId,
                            status = call.status,
                            source = call.source,
                            modelRef = call.modelRef,
                            durationMs = call.durationMs,
                            messageCount = call.messageCount,
                            toolCount = call.toolCount,
                            eventCount = call.eventCount,
                            errorCode = call.errorCode.orEmpty(),
                        ),
                        sourceEventSequences = runStartSource(call.runId),
                    ),
                )
                if (finished != null && call.source in setOf(SESSION_MODEL_CALL_SOURCE_ROUND, SESSION_MODEL_CALL_SOURCE_COMPACTION)) {
                    recordRuntimeTimingEvent(call, identity, finished.sequence)
                }
                // Summary text belongs to its own model request, not the next business assistant message.
                if (call.source == SESSION_MODEL_CALL_SOURCE_COMPACTION) chunkSequences.remove(call.runId)
            }
        }
    }

    private fun recordRuntimeTimingEvent(call: StoredModelCall, identity: RunIdentity, sourceSequence: Long) {
        val timings = takeModelTimings(call.runId)
        if ((timings as SessionJson.Object).values.isEmpty()) return
        appendEventSafely(SessionEventAppend(
            sessionId = call.sessionId, runId = call.runId, requestId = identity.requestId,
            type = SessionEventType.MODEL_RESPONSE_METADATA,
            payload = SessionEventPayload.ModelResponseMetadata(SessionJson.Object(mapOf(
                "kind" to SessionJson.Text("runtime_timing"),
                "callId" to SessionJson.Text(call.callId), "timings" to timings,
            ))), sourceEventSequences = listOf(sourceSequence),
        ))
    }

    fun updateRunStatus(runId: String, status: RuntimeRunStatus, response: String = "", errorCode: String? = null) {
        flushPendingToolRecordings()
        persistStoreSafely {
            val now = nowEpochMillis()
            store?.updateRunStatus(
                StoredRunStatusUpdate(
                    runId = runId,
                    status = status.name,
                    updatedAtEpochMillis = now,
                    finishedAtEpochMillis = if (status.isTerminal()) now else null,
                    response = response,
                    errorCode = errorCode,
                ),
            )
        }
        recordRunStatusEvents(runId, status, response, errorCode)
    }

    fun finishRun(result: RuntimeResult) {
        updateRunStatus(result.runId, result.status, result.response, result.errorCode)
        synchronized(counterBaselines) {
            counterBaselines.remove(result.runId)
        }
        synchronized(modelCallSequences) {
            modelCallSequences.remove(result.runId)
        }
        synchronized(eventLock) {
            toolStartSequences.keys.removeAll { it.startsWith("${result.runId}:") }
            chunkSequences.remove(result.runId)
            preparationTimings.remove(result.runId)
            modelRequestStarts.remove(result.runId)
            firstContentTimings.remove(result.runId)
        }
    }

    private fun recordMessageEvent(context: MaterializerContext, message: ModelMessage) {
        synchronized(eventLock) {
            appendEventSafely(
                SessionEventAppend(
                    sessionId = context.sessionId,
                    runId = context.runId,
                    requestId = context.requestId,
                    type = if (message.role.equals("user", ignoreCase = true)) {
                        SessionEventType.USER_MESSAGE
                    } else {
                        SessionEventType.ASSISTANT_MESSAGE
                    },
                    payload = if (message.role.equals("user", ignoreCase = true)) {
                        SessionEventPayload.UserMessage(text = message.content)
                    } else {
                        SessionEventPayload.AssistantMessage(
                            text = message.content,
                            toolCalls = message.toolArgumentsJson
                                ?.takeIf { it.isNotBlank() }
                                ?.let(::parseJsonOrText)
                                ?: cc.noharry.agent.kernel.api.SessionJson.Null,
                            finishReason = message.toolName.orEmpty(),
                            sourceChunkSequences = chunkSequences.remove(context.runId).orEmpty(),
                        )
                    },
                    sourceEventSequences = runStartSource(context.runId),
                ),
            )
        }
    }

    private fun recordToolFinishedEvent(recording: PendingToolRecording) {
        val storeCallId = recording.result.callId
        val visibleCallId = modelVisibleToolCallId(recording.context.runId, recording.event)
        synchronized(eventLock) {
            val sourceSequences = toolStartSequences.remove(storeCallId)?.let(::listOf)
                ?: runStartSource(recording.context.runId)
            appendEventSafely(
                SessionEventAppend(
                    sessionId = recording.context.sessionId,
                    runId = recording.context.runId,
                    requestId = recording.context.requestId,
                    type = SessionEventType.TOOL_RESULT,
                    payload = SessionEventPayload.ToolResult(
                        toolCallId = visibleCallId,
                        name = recording.event.name,
                        result = parseJsonOrText(recording.result.resultJson),
                        ok = recording.parsed.ok,
                        code = recording.parsed.code.orEmpty(),
                        source = recording.source?.name.orEmpty().ifBlank { UNKNOWN_TOOL_SOURCE },
                        status = recording.result.status,
                        todoCompletion = todoVerifiedMetadata(recording)["todoCompletion"].orEmpty(),
                        attachments = recording.attachments,
                    ),
                    sourceEventSequences = sourceSequences,
                ),
            )
        }
    }

    private fun recordRunStatusEvents(
        runId: String,
        status: RuntimeRunStatus,
        response: String,
        errorCode: String?,
    ) {
        synchronized(eventLock) {
            if (runId in finishedEventRuns) return
            val identity = runIdentities[runId] ?: return
            val statusEvent = appendEventSafely(
                SessionEventAppend(
                    sessionId = identity.sessionId,
                    runId = runId,
                    requestId = identity.requestId,
                    type = SessionEventType.RUN_STATUS,
                    payload = SessionEventPayload.RunLifecycle(
                        status = status.name,
                        errorCode = errorCode.orEmpty(),
                    ),
                    sourceEventSequences = runStartSource(runId),
                ),
            )
            if (!status.isTerminal()) return
            val finished = appendEventSafely(
                SessionEventAppend(
                    sessionId = identity.sessionId,
                    runId = runId,
                    requestId = identity.requestId,
                    type = SessionEventType.RUN_FINISHED,
                    payload = SessionEventPayload.RunLifecycle(
                        status = status.name,
                        errorCode = errorCode.orEmpty(),
                    ),
                    sourceEventSequences = statusEvent?.let { listOf(it.sequence) }
                        ?: runStartSource(runId),
                ),
            )
            if (finished != null) {
                finishedEventRuns += runId
                runIdentities.remove(runId)
                runStartSequences.remove(runId)
            }
        }
    }

    private fun todoReportedMetadata(event: ModelEvent.ToolCall): Map<String, String> {
        if (event.name != UPDATE_TODO_TOOL) return emptyMap()
        val items = parseTodoItems(event.argumentsJson)
        return todoCounts(items) +
            ("todoCompletion" to if (items.all { it.status == TodoStatus.completed }) {
                TODO_REPORTED_COMPLETE
            } else {
                TODO_PLANNED
            })
    }

    private fun todoVerifiedMetadata(recording: PendingToolRecording): Map<String, String> {
        if (recording.event.name != UPDATE_TODO_TOOL) return emptyMap()
        val items = parseTodoItems(recording.event.argumentsJson)
        val state = when {
            !recording.parsed.ok -> TODO_FAILED
            items.all { it.status == TodoStatus.completed } -> TODO_VERIFIED_COMPLETE
            else -> TODO_PLANNED
        }
        return todoCounts(items) + ("todoCompletion" to state)
    }

    private fun todoCounts(items: List<TodoItem>): Map<String, String> =
        mapOf(
            "todoCount" to items.size.toString(),
            "todoPendingCount" to items.count { it.status == TodoStatus.pending }.toString(),
            "todoInProgressCount" to items.count { it.status == TodoStatus.in_progress }.toString(),
            "todoCompletedCount" to items.count { it.status == TodoStatus.completed }.toString(),
        )

    private fun runStartSource(runId: String): List<Long> =
        runStartSequences[runId]?.let(::listOf).orEmpty()

    private fun appendEventSafely(event: SessionEventAppend): SessionEvent? {
        val log = eventLog ?: return null
        return try {
            log.append(event)
        } catch (_: Exception) {
            null
        }
    }

    private fun vfsOperation(
        context: MaterializerContext,
        event: ModelEvent.ToolCall,
        status: String,
        resultJson: String,
        telemetry: WorkspaceExecutionTelemetry,
    ): StoredVfsOperation? {
        val path = event.argumentsJson.vfsProbePath(event.name) ?: return null
        return StoredVfsOperation(
            runId = context.runId,
            sessionId = context.sessionId,
            op = event.name,
            path = path,
            status = if (status == SESSION_TOOL_STATUS_COMPLETED) SESSION_VFS_STATUS_OK else SESSION_VFS_STATUS_FAILED,
            contentHash = boundedSha256(resultJson),
            createdAtEpochMillis = nowEpochMillis(),
            vfsCounters = runVfsCounterDelta(context.runId),
            timings = VfsOperationTimings(
                resolveMs = telemetry.resolveDurationMs,
                materializeMs = telemetry.materializeDurationMs,
                scanMs = telemetry.scanDurationMs,
                serializeMs = telemetry.serializeDurationMs,
                prepareMs = telemetry.prepareDurationMs,
                batchWaitMs = telemetry.batchWaitDurationMs,
                executeMs = telemetry.executeDurationMs,
                batchResultWaitMs = telemetry.batchResultWaitDurationMs,
                summaryMs = telemetry.summaryDurationMs,
                publicFinishMs = telemetry.publicFinishDurationMs,
            ),
            schemaCacheHit = telemetry.schemaCacheHit.takeIf { path.isMcpSchemaPath() },
        )
    }

    private fun runVfsCounterDelta(runId: String): Map<String, Long> {
        val baseline = synchronized(counterBaselines) { counterBaselines[runId].orEmpty() }
        return RuntimeVfsPerformanceCounters.deltaSince(baseline)
    }

    private fun persistStoreSafely(block: () -> Unit) {
        try {
            if (store != null) block()
        } catch (_: Exception) {
        }
    }
}

private data class PendingToolRecording(
    val result: StoredToolResult,
    val vfsOperation: StoredVfsOperation?,
    val context: MaterializerContext,
    val event: ModelEvent.ToolCall,
    val source: DynamicToolSource?,
    val parsed: ToolUseResult,
    val attachments: List<SessionAttachmentRef> = emptyList(),
)

private data class RunIdentity(
    val sessionId: String,
    val requestId: String,
)

private fun RuntimeRunStatus.isTerminal(): Boolean =
    this == RuntimeRunStatus.COMPLETED || this == RuntimeRunStatus.FAILED || this == RuntimeRunStatus.CANCELLED

private fun stableCallId(runId: String, event: ModelEvent.ToolCall): String =
    "$runId:${modelVisibleToolCallId(runId, event)}"

internal fun modelVisibleToolCallId(runId: String, event: ModelEvent.ToolCall): String =
    event.id.ifBlank { "generated-${sha256("$runId:${event.name}:${event.argumentsJson}")}" }

private fun ModelRef?.wireValueOrEmpty(): String =
    this?.wireValue().orEmpty()

private fun String.pathField(): String? {
    val json = runCatching { Json.parseToJsonElement(this) as? JsonObject }.getOrNull() ?: return null
    return json["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.startsWith("/") }
}

private fun String.vfsProbePath(toolName: String): String? =
    pathField() ?: SYNTHETIC_VFS_PROBE_PATHS[toolName]

private fun String.isMcpSchemaPath(): Boolean =
    startsWith("/mcp/tools/") && endsWith("/schema.json")

private val SYNTHETIC_VFS_PROBE_PATHS = mapOf(
    "search_tools" to "/mcp/search_tools",
)

private fun String.sessionTitle(): String =
    replace("\n", " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .take(SESSION_TITLE_LIMIT)
        .ifBlank { "Session" }

private fun boundedSha256(value: String): String {
    if (value.length <= HASH_FULL_RESULT_CHAR_LIMIT) return sha256(value)
    return "sha256-prefix:$HASH_FULL_RESULT_CHAR_LIMIT:${value.length}:${sha256(value.take(HASH_FULL_RESULT_CHAR_LIMIT))}"
}

private fun sha256(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { byte -> "%02x".format(byte) }
}

private const val UNKNOWN_TOOL_SOURCE = "UNKNOWN"
private const val UPDATE_TODO_TOOL = "update_todo"
private const val PREFETCH_DISCOVERY_TOOL = "prefetch_tools"
private const val TODO_PLANNED = "PLANNED"
private const val TODO_REPORTED_COMPLETE = "PLAN_REPORTED_COMPLETE"
private const val TODO_VERIFIED_COMPLETE = "PLAN_STORE_ACCEPTED_COMPLETE"
private const val TODO_FAILED = "PLAN_WRITE_FAILED"
private const val SESSION_TITLE_LIMIT = 48
private const val HASH_FULL_RESULT_CHAR_LIMIT = 8192

private const val TIMING_NANOS_PER_MILLI = 1_000_000L
