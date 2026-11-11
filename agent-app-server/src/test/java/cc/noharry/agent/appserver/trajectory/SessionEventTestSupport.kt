@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory

import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryLane
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySource
import cc.noharry.agent.appserver.protocol.TrajectoryTimeQuality
import cc.noharry.agent.appserver.protocol.view.TrajectoryInspectorTab
import cc.noharry.agent.appserver.protocol.view.inspectorTabsFor
import cc.noharry.agent.appserver.protocol.view.paginateRawText
import cc.noharry.agent.spi.SessionAttachmentRef
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionEventType
import cc.noharry.agent.spi.SessionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun sessionEvent(
    eventId: String = "evt-1",
    sessionId: String = "s1",
    runId: String = "run-1",
    requestId: String = runId,
    sequence: Long = 1L,
    recordedAtEpochMillis: Long = 1_000L,
    type: SessionEventType = SessionEventType.RUN_STARTED,
    safeMetadata: Map<String, String> = emptyMap(),
    @Suppress("UNUSED_PARAMETER") contentHashes: Map<String, String> = emptyMap(),
    sourceEventSequences: List<Long> = emptyList(),
): SessionEvent = SessionEvent(
    schemaVersion = 2,
    eventId = eventId,
    sessionId = sessionId,
    runId = runId,
    requestId = requestId,
    sequence = sequence,
    recordedAtEpochMillis = recordedAtEpochMillis,
    type = type,
    payload = payloadFor(type, safeMetadata),
    sourceEventSequences = sourceEventSequences,
)

internal fun payloadFor(
    type: SessionEventType,
    metadata: Map<String, String>,
): SessionEventPayload = when (type) {
    SessionEventType.USER_MESSAGE -> SessionEventPayload.UserMessage(text = metadata["text"].orEmpty())
    SessionEventType.USER_INTERACTION -> SessionEventPayload.UserInteraction("INPUT", "RECEIVED", text = metadata["text"].orEmpty())
    SessionEventType.ASSISTANT_CHUNK -> SessionEventPayload.AssistantChunk(
        kind = metadata["kind"] ?: "text",
        text = metadata["text"].orEmpty(),
    )
    SessionEventType.ASSISTANT_MESSAGE -> SessionEventPayload.AssistantMessage(
        text = metadata["text"].orEmpty(),
        finishReason = metadata["finishReason"].orEmpty(),
    )
    SessionEventType.MODEL_TOOL_CALL,
    SessionEventType.TOOL_STARTED,
    -> SessionEventPayload.ToolCall(
        toolCallId = metadata["callId"].orEmpty(),
        name = metadata["toolName"].orEmpty(),
        source = metadata["source"].orEmpty(),
    )
    SessionEventType.TOOL_RESULT -> SessionEventPayload.ToolResult(
        toolCallId = metadata["callId"].orEmpty(),
        name = metadata["toolName"].orEmpty(),
        source = metadata["source"].orEmpty(),
        status = metadata["status"].orEmpty(),
    )
    SessionEventType.USAGE -> SessionEventPayload.Usage()
    SessionEventType.MODEL_CALL_FINISHED -> SessionEventPayload.ModelCallFinished(
        callId = metadata["callId"].orEmpty(),
        status = metadata["status"].orEmpty(),
        modelRef = metadata["modelRef"].orEmpty(),
    )
    SessionEventType.MODEL_REQUEST -> SessionEventPayload.ModelRequest(
        modelRef = metadata["modelRef"].orEmpty(),
    )
    SessionEventType.MODEL_RESPONSE_METADATA -> SessionEventPayload.ModelResponseMetadata()
    else -> SessionEventPayload.RunLifecycle(
        phase = metadata["phase"].orEmpty(),
        status = metadata["status"].orEmpty(),
        modelRef = metadata["modelRef"].orEmpty(),
        scene = metadata["scene"].orEmpty(),
        errorCode = metadata["errorCode"].orEmpty(),
        todoCompletion = metadata["todoCompletion"].orEmpty(),
    )
}
