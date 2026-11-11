@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.EventLogDurability
import cc.noharry.agent.appserver.protocol.EventLogHealthStatus
import cc.noharry.agent.appserver.protocol.ModelCallPhase
import cc.noharry.agent.appserver.protocol.TrajectoryCorrelation
import cc.noharry.agent.appserver.protocol.TrajectoryHealth
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryLane
import cc.noharry.agent.appserver.protocol.TrajectoryModelCall
import cc.noharry.agent.appserver.protocol.TrajectoryQuestion
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.protocol.TrajectoryRichDetails
import cc.noharry.agent.appserver.protocol.TrajectorySource
import cc.noharry.agent.appserver.protocol.TrajectoryTimeQuality
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventLogHealth
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionEventType
import cc.noharry.agent.spi.SessionJson

/**
 * Pure projection from durable [SessionEvent] into UI records.
 *
 * Live tail and cold replay share this mapping and the same `event:` identity. No disk IO,
 * no clock, no Runtime access: every timestamp comes from the canonical event.
 */

internal data class CanonicalProjection(
    val records: List<TrajectoryRecord>,
    val hasSequenceGap: Boolean,
    val hasSequenceConflict: Boolean,
)

/**
 * Maps one canonical event. Scalar fields and rich details are a projection of the typed v2
 * payload, never a reconstructed second source.
 */
internal fun SessionEvent.toTrajectoryRecord(): TrajectoryRecord {
    val metadata = payload.scalarFields().withCanonicalCallId(runId) + (TRAJECTORY_EXECUTION_ID to runId)
    val kind = type.toTrajectoryKind(payload)
    val callId = metadata["callId"]
    return TrajectoryRecord(
        id = TrajectoryRecordId("$EVENT_ID_PREFIX$eventId"),
        sessionId = sessionId,
        runId = logicalTurnId,
        requestId = requestId,
        eventId = eventId,
        sequence = sequence,
        occurredAtEpochMillis = recordedAtEpochMillis,
        timeQuality = TrajectoryTimeQuality.PERSISTED_RECORDED,
        kind = kind,
        lane = kind.lane(),
        status = metadata["status"],
        title = persistedTitle(kind, type, metadata),
        summary = persistedSummary(kind, metadata),
        correlation = TrajectoryCorrelation(
            toolCallId = callId.takeIf { kind.isToolPhase() },
            modelCallId = callId.takeIf { kind.isModelCallPhase() },
            lifecyclePhase = kind.lifecyclePhase(),
        ),
        safeMetadata = metadata,
        contentHashes = emptyMap(),
        sourceEventSequences = sourceEventSequences,
        sources = setOf(TrajectorySource.EVENT_LOG),
        rich = (payload.toRichDetails() ?: TrajectoryRichDetails()).copy(
            question = payload.questionFact(),
            modelCall = payload.modelCallFact(),
        ),
        richAvailability = payload.richAvailability(),
        isLive = false,
    )
}

internal fun projectCanonicalRecords(
    sessionId: String,
    runId: String,
    events: List<SessionEvent>,
): CanonicalProjection {
    val owned = events.filter { it.sessionId == sessionId && it.logicalTurnId == runId }
    val attempt = latestAttemptEvents(owned)
    val ordered = attempt.sortedBy { it.sequence }
    val sequences = ordered.map { it.sequence }
    val hasConflict = owned.size != events.size || sequences.distinct().size != sequences.size
    val hasGap = sequences.zipWithNext().any { (previous, next) -> next > previous + 1 }
    val verified = if (hasConflict) ordered.takeVerifiedPrefix() else ordered
    return CanonicalProjection(
        records = verified.map { it.toTrajectoryRecord() },
        hasSequenceGap = hasGap,
        hasSequenceConflict = hasConflict,
    )
}

private fun SessionEventType.toTrajectoryKind(payload: SessionEventPayload): TrajectoryKind =
    when (this) {
        SessionEventType.RUN_STARTED -> TrajectoryKind.RUN_START
        SessionEventType.USER_MESSAGE -> TrajectoryKind.INPUT
        SessionEventType.USER_INTERACTION -> TrajectoryKind.STATUS
        SessionEventType.ASSISTANT_CHUNK ->
            if (payload is SessionEventPayload.AssistantChunk && payload.kind == "thinking") {
                TrajectoryKind.THINKING
            } else {
                TrajectoryKind.MODEL_MESSAGE
            }
        SessionEventType.ASSISTANT_MESSAGE -> TrajectoryKind.MODEL_MESSAGE
        SessionEventType.MODEL_REQUEST,
        SessionEventType.MODEL_CALL_FINISHED,
        -> TrajectoryKind.MODEL_CALL
        SessionEventType.MODEL_RESPONSE_METADATA ->
            if (payload.isSearchActivity()) TrajectoryKind.SEARCH else TrajectoryKind.MODEL_CALL
        SessionEventType.MODEL_TOOL_CALL -> TrajectoryKind.MODEL_TOOL
        SessionEventType.TOOL_STARTED -> TrajectoryKind.TOOL_START
        SessionEventType.TOOL_RESULT -> TrajectoryKind.TOOL_FINISH
        SessionEventType.USAGE -> TrajectoryKind.USAGE
        SessionEventType.RUN_STATUS -> TrajectoryKind.STATUS
        SessionEventType.RUN_FINISHED -> TrajectoryKind.RUN_END
    }

private fun persistedTitle(
    kind: TrajectoryKind,
    type: SessionEventType,
    metadata: Map<String, String>,
): String =
    when (kind) {
        TrajectoryKind.RUN_START -> "Run started"
        TrajectoryKind.INPUT -> "User message"
        TrajectoryKind.THINKING -> "Thinking"
        TrajectoryKind.MODEL_MESSAGE -> when (type) {
            SessionEventType.ASSISTANT_CHUNK -> "Assistant chunk"
            else -> "Assistant message"
        }
        TrajectoryKind.MODEL_TOOL -> metadata["toolName"] ?: "Model tool call"
        TrajectoryKind.TOOL_START -> metadata["toolName"] ?: "Tool started"
        TrajectoryKind.TOOL_FINISH -> metadata["toolName"] ?: "Tool finished"
        TrajectoryKind.SEARCH -> "Search activity"
        TrajectoryKind.MODEL_CALL -> when (type) {
            SessionEventType.MODEL_REQUEST -> "Model request"
            SessionEventType.MODEL_RESPONSE_METADATA -> "Model metadata"
            else -> "Model call"
        }
        TrajectoryKind.USAGE -> "Token usage"
        TrajectoryKind.STATUS -> "Status ${metadata["status"].orEmpty()}".trim()
        TrajectoryKind.RUN_END -> "Run finished"
        else -> kind.name
    }

private fun persistedSummary(kind: TrajectoryKind, metadata: Map<String, String>): String =
    when (kind) {
        TrajectoryKind.RUN_START -> metadataLine(metadata, "status", "scene", "modelRef")
        TrajectoryKind.INPUT, TrajectoryKind.THINKING, TrajectoryKind.MODEL_MESSAGE ->
            metadataLine(metadata, "role", "kind", "text", "finishReason")
        TrajectoryKind.MODEL_TOOL, TrajectoryKind.TOOL_START ->
            metadataLine(metadata, "source", "callId")
        TrajectoryKind.TOOL_FINISH -> metadataLine(metadata, "status", "ok", "code")
        TrajectoryKind.SEARCH -> metadataLine(metadata, "provider", "queryPreview", "resultCount")
        TrajectoryKind.MODEL_CALL -> metadataLine(metadata, "status", "modelRef", "durationMs", "toolCount", "errorCode")
        TrajectoryKind.USAGE -> metadataLine(metadata, "inputTokens", "outputTokens", "reasoningTokens")
        TrajectoryKind.STATUS, TrajectoryKind.RUN_END -> metadataLine(metadata, "status", "errorCode")
        else -> ""
    }

private fun metadataLine(metadata: Map<String, String>, vararg keys: String): String =
    keys.mapNotNull { key -> metadata[key]?.takeIf { it.isNotBlank() }?.let { "$key=$it" } }
        .joinToString(" · ")
        .boundedSummary()

private fun List<SessionEvent>.takeVerifiedPrefix(): List<SessionEvent> {
    val verified = mutableListOf<SessionEvent>()
    var last: Long? = null
    forEach { event ->
        if (last != null && event.sequence <= last!!) return verified
        verified += event
        last = event.sequence
    }
    return verified
}

/** Explicit failure row so a broken cold read is never mistaken for an empty or unloaded run. */
internal fun loadErrorRecord(sessionId: String, runId: String, reasonCode: String): TrajectoryRecord =
    TrajectoryRecord(
        id = TrajectoryRecordId("$ERROR_ID_PREFIX$sessionId:$runId"),
        sessionId = sessionId,
        runId = runId,
        timeQuality = TrajectoryTimeQuality.UNKNOWN,
        kind = TrajectoryKind.LOAD_ERROR,
        lane = TrajectoryLane.SYSTEM,
        title = "Could not read the recorded events for this run",
        summary = "reason=$reasonCode",
        safeMetadata = mapOf("reason" to reasonCode),
        sources = setOf(TrajectorySource.SYNTHETIC_BOUNDARY),
    )

internal fun gapBoundaryRecord(sessionId: String, runId: String, afterSequence: Long?): TrajectoryRecord =
    TrajectoryRecord(
        id = TrajectoryRecordId("$GAP_ID_PREFIX$sessionId:$runId:${afterSequence ?: 0}"),
        sessionId = sessionId,
        runId = runId,
        sequence = afterSequence,
        timeQuality = TrajectoryTimeQuality.UNKNOWN,
        kind = TrajectoryKind.GAP,
        lane = TrajectoryLane.SYSTEM,
        title = "Recorded events are not contiguous",
        summary = afterSequence?.let { "afterSequence=$it" }.orEmpty(),
        sources = setOf(TrajectorySource.SYNTHETIC_BOUNDARY),
    )

/**
 * Combines runtime-reported health with repository-observed facts. Runtime status is never
 * upgraded: UNKNOWN stays UNKNOWN even when records were read successfully.
 */
internal fun composeTrajectoryHealth(
    runtimeHealth: SessionEventLogHealth?,
    projection: CanonicalProjection? = null,
    richAvailability: TrajectoryRichAvailability,
    lastAcceptedSequence: Long? = null,
    safeReasonCode: String? = null,
): TrajectoryHealth {
    val base = runtimeHealth ?: SessionEventLogHealth.unknown()
    val degraded = projection?.hasSequenceConflict == true &&
        base.status == SessionEventLogHealthStatus.COMPLETE
    return TrajectoryHealth(
        eventLogStatus = if (degraded) EventLogHealthStatus.DEGRADED else EventLogHealthStatus.valueOf(base.status.name),
        durability = EventLogDurability.valueOf(base.backendDurability.name),
        attemptedCount = base.attemptedCount,
        appendedCount = base.appendedCount,
        failedCount = base.failedCount,
        lastAcceptedSequence = lastAcceptedSequence ?: base.lastAcceptedSequence,
        lastPersistedSequence = base.lastPersistedSequence,
        hasSequenceGap = projection?.hasSequenceGap == true,
        hasLiveOnlyRecords = false,
        richAvailability = richAvailability,
        safeReasonCode = safeReasonCode ?: base.firstFailureCode,
    )
}

internal fun TrajectoryKind.lane(): TrajectoryLane =
    when (this) {
        TrajectoryKind.RUN_START, TrajectoryKind.INPUT, TrajectoryKind.CONFIRMATION -> TrajectoryLane.INPUT
        TrajectoryKind.THINKING,
        TrajectoryKind.MODEL_MESSAGE,
        TrajectoryKind.MODEL_CALL,
        TrajectoryKind.MODEL_TOOL,
        TrajectoryKind.USAGE,
        -> TrajectoryLane.MODEL
        TrajectoryKind.TOOL_START,
        TrajectoryKind.TOOL_FINISH,
        TrajectoryKind.NETWORK,
        TrajectoryKind.SEARCH,
        TrajectoryKind.EXEC_OUTPUT,
        TrajectoryKind.WORKSPACE,
        -> TrajectoryLane.TOOLS
        TrajectoryKind.RETRY,
        TrajectoryKind.COMPACTION,
        TrajectoryKind.STATUS,
        TrajectoryKind.RESULT,
        TrajectoryKind.RUN_END,
        TrajectoryKind.GAP,
        TrajectoryKind.LOAD_ERROR,
        -> TrajectoryLane.SYSTEM
    }

/** Declaration order runs best-to-worst, so the lower ordinal is the richer availability. */
internal fun betterAvailability(
    left: TrajectoryRichAvailability,
    right: TrajectoryRichAvailability,
): TrajectoryRichAvailability = if (left.ordinal <= right.ordinal) left else right

private fun TrajectoryKind.isToolPhase(): Boolean =
    this == TrajectoryKind.MODEL_TOOL ||
        this == TrajectoryKind.TOOL_START ||
        this == TrajectoryKind.TOOL_FINISH

private fun TrajectoryKind.isModelCallPhase(): Boolean =
    this == TrajectoryKind.MODEL_CALL || this == TrajectoryKind.MODEL_TOOL

private fun Map<String, String>.withCanonicalCallId(runId: String): Map<String, String> {
    val raw = this["callId"] ?: return this
    val canonical = canonicalToolCallId(runId, raw)
    return if (canonical == raw) this else this + ("callId" to canonical)
}

private fun TrajectoryKind.lifecyclePhase(): String? =
    when (this) {
        TrajectoryKind.RUN_START -> LIFECYCLE_STARTED
        TrajectoryKind.RUN_END -> LIFECYCLE_FINISHED
        else -> null
    }

private fun SessionEventPayload.toRichDetails(): TrajectoryRichDetails? = when (this) {
    SessionEventPayload.Empty -> null
    is SessionEventPayload.UserInteraction -> TrajectoryRichDetails(inputPreview = text.boundedRich(), outputText = text.boundedRich())
    is SessionEventPayload.UserMessage -> TrajectoryRichDetails(
        inputPreview = text.boundedRich(),
        outputText = text.boundedRich(),
    )
    is SessionEventPayload.ModelRequest -> TrajectoryRichDetails(
        requestText = messages.render().boundedRich(),
        payloadPreview = systemPrompt.takeIf { it.isNotBlank() }?.boundedRich(),
        schemaPreview = tools.takeUnless { it == SessionJson.Null }?.render()?.boundedRich(),
    )
    is SessionEventPayload.AssistantChunk -> TrajectoryRichDetails(
        thinkingText = text.takeIf { kind == "thinking" }?.boundedRich(),
        outputText = text.takeIf { kind != "thinking" }?.boundedRich(),
    )
    is SessionEventPayload.AssistantMessage -> TrajectoryRichDetails(
        outputText = text.boundedRich(),
        usageText = usage.summaryLine(),
    )
    is SessionEventPayload.ToolCall -> TrajectoryRichDetails(
        payloadPreview = arguments.render().boundedRich(),
        schemaPreview = schemaRef.takeIf { it.isNotBlank() },
    )
    is SessionEventPayload.ToolResult -> TrajectoryRichDetails(
        resultPreview = result.render().boundedRich(),
    )
    is SessionEventPayload.Usage -> TrajectoryRichDetails(usageText = summaryLine())
    is SessionEventPayload.ModelResponseMetadata -> TrajectoryRichDetails()
    is SessionEventPayload.ModelCallFinished -> TrajectoryRichDetails(
        timing = mapOf("durationMs" to durationMs),
    )
    is SessionEventPayload.RunLifecycle -> TrajectoryRichDetails()
}

private fun SessionEventPayload.Usage.summaryLine(): String =
    "in=$inputTokens out=$outputTokens reasoning=$reasoningTokens"

private fun String.boundedSummary(): String =
    if (length <= TRAJECTORY_SUMMARY_PREVIEW_CHARS) this else take(TRAJECTORY_SUMMARY_PREVIEW_CHARS)

private fun String.boundedRich(): String =
    if (length <= TRAJECTORY_RICH_PREVIEW_CHARS) this else take(TRAJECTORY_RICH_PREVIEW_CHARS)

private fun SessionEventPayload.scalarFields(): Map<String, String> = when (this) {
    SessionEventPayload.Empty -> emptyMap()
    is SessionEventPayload.UserInteraction -> mapOf("interactionKind" to kind, "state" to state, "questionId" to questionId, "inputRequestId" to inputRequestId, "text" to text.take(SCALAR_TEXT_CHARS))
    is SessionEventPayload.RunLifecycle -> buildMap {
        if (phase.isNotBlank()) put("phase", phase)
        if (status.isNotBlank()) put("status", status)
        if (modelRef.isNotBlank()) put("modelRef", modelRef)
        if (scene.isNotBlank()) put("scene", scene)
        if (errorCode.isNotBlank()) put("errorCode", errorCode)
        if (todoCompletion.isNotBlank()) put("todoCompletion", todoCompletion)
    }
    is SessionEventPayload.UserMessage -> buildMap {
        put("role", "user")
        if (text.isNotBlank()) put("text", text.take(SCALAR_TEXT_CHARS))
    }
    is SessionEventPayload.ModelRequest -> buildMap {
        if (modelRef.isNotBlank()) put("modelRef", modelRef)
        if (toolChoice.isNotBlank()) put("toolChoice", toolChoice)
        if (promptCacheKey.isNotBlank()) put("promptCacheKey", promptCacheKey)
    }
    is SessionEventPayload.AssistantChunk -> buildMap {
        put("kind", kind)
        if (text.isNotBlank()) put("text", text.take(SCALAR_TEXT_CHARS))
    }
    is SessionEventPayload.AssistantMessage -> buildMap {
        if (finishReason.isNotBlank()) put("finishReason", finishReason)
        put("role", "assistant")
        if (text.isNotBlank()) put("text", text.take(SCALAR_TEXT_CHARS))
    }
    is SessionEventPayload.ToolCall -> buildMap {
        if (toolCallId.isNotBlank()) put("callId", toolCallId)
        if (name.isNotBlank()) put("toolName", name)
        if (source.isNotBlank()) put("source", source)
        if (schemaRef.isNotBlank()) put("schemaRef", schemaRef)
        if (todoCompletion.isNotBlank()) put("todoCompletion", todoCompletion)
    }
    is SessionEventPayload.ToolResult -> buildMap {
        if (toolCallId.isNotBlank()) put("callId", toolCallId)
        if (name.isNotBlank()) put("toolName", name)
        if (source.isNotBlank()) put("source", source)
        put("ok", ok.toString())
        if (code.isNotBlank()) put("code", code)
        put("retryable", retryable.toString())
        if (status.isNotBlank()) put("status", status)
        if (todoCompletion.isNotBlank()) put("todoCompletion", todoCompletion)
    }
    is SessionEventPayload.Usage -> mapOf(
        "inputTokens" to inputTokens.toString(),
        "outputTokens" to outputTokens.toString(),
        "reasoningTokens" to reasoningTokens.toString(),
        "cacheReadTokens" to cacheReadTokens.toString(),
        "cacheWriteTokens" to cacheWriteTokens.toString(),
    )
    is SessionEventPayload.ModelResponseMetadata -> searchOrEmptyFields()
    is SessionEventPayload.ModelCallFinished -> buildMap {
        if (callId.isNotBlank()) put("callId", callId)
        if (status.isNotBlank()) put("status", status)
        if (source.isNotBlank()) put("source", source)
        if (modelRef.isNotBlank()) put("modelRef", modelRef)
        put("durationMs", durationMs.toString())
        put("messageCount", messageCount.toString())
        put("toolCount", toolCount.toString())
        put("eventCount", eventCount.toString())
        if (errorCode.isNotBlank()) put("errorCode", errorCode)
    }
}

private fun SessionEventPayload.ModelResponseMetadata.searchOrEmptyFields(): Map<String, String> {
    if (!isSearchActivity()) return emptyMap()
    return buildMap {
        textField(fields, "kind")?.let { put("kind", it) }
        textField(fields, "provider")?.let { put("provider", it) }
        textField(fields, "queryPreview")?.let { put("queryPreview", it) }
        numberField(fields, "resultCount")?.let { put("resultCount", it) }
    }
}

private const val EVENT_ID_PREFIX = "event:"
private const val ERROR_ID_PREFIX = "error:"
private const val GAP_ID_PREFIX = "gap:"
private const val LIFECYCLE_STARTED = "started"
private const val LIFECYCLE_FINISHED = "finished"
private const val SCALAR_TEXT_CHARS = 200

/** A question the run asked, kept on its record so the question cards need no raw event. */
private fun SessionEventPayload.questionFact(): TrajectoryQuestion? =
    (this as? SessionEventPayload.UserInteraction)?.let { TrajectoryQuestion(it.questionId, it.kind, it.text, it.options, it.state) }

/** The parts of a model call that a front end works out context use from. */
private fun SessionEventPayload.modelCallFact(): TrajectoryModelCall? = when (this) {
    is SessionEventPayload.ModelRequest -> TrajectoryModelCall(ModelCallPhase.REQUEST, modelRef = modelRef)
    is SessionEventPayload.Usage -> TrajectoryModelCall(ModelCallPhase.USAGE, inputTokens = inputTokens)
    is SessionEventPayload.ModelCallFinished -> TrajectoryModelCall(ModelCallPhase.FINISHED, modelRef = modelRef, source = source)
    else -> null
}
