@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionAttachmentRef
import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.api.SessionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventPayloadRoundTripTest {
    @Test
    fun runtimeTimingMetadataIsFrozenAndRetainsCredentialChecks() {
        val values = mutableMapOf<String, SessionJson>("firstContentMs" to SessionJson.Number("7"))
        val original = SessionEventPayload.ModelResponseMetadata(fields = SessionJson.Object(values))
        val frozen = original.frozenCopy() as SessionEventPayload.ModelResponseMetadata
        values["firstContentMs"] = SessionJson.Number("99")
        assertEquals(SessionJson.Number("7"), (frozen.fields as SessionJson.Object).values["firstContentMs"])
        val unsafe = original.copy(fields = SessionJson.Object(mapOf("api_key" to SessionJson.Text("secret"))))
        assertTrue(runCatching { validatePayload(unsafe) }.isFailure)
        assertEquals(frozen, decodeEvent(encodeEvent(stored(frozen))).payload)
    }

    @Test
    fun everyTypedPayloadRoundTripsThroughEncodeDecode() {
        payloads().forEach { payload ->
            val encoded = encodeEvent(stored(payload))
            val decoded = decodeEvent(encoded)
            assertEquals(2, decoded.schemaVersion)
            assertEquals(payload, decoded.payload)
        }
    }

    @Test
    fun unknownSchemaVersionFailsClosed() {
        val encoded = encodeEvent(stored(SessionEventPayload.Empty))
            .replace("\"schemaVersion\":2", "\"schemaVersion\":1")
        val result = runCatching { decodeEvent(encoded) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Unsupported session event schema"))
    }

    @Test
    fun unknownPayloadKindFailsClosed() {
        val encoded = encodeEvent(stored(SessionEventPayload.Empty))
            .replace("\"kind\":\"Empty\"", "\"kind\":\"LegacyHash\"")
        val result = runCatching { decodeEvent(encoded) }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("Unsupported session event payload kind"))
    }

    @Test
    fun credentialKeysAndBearerValuesAreRejected() {
        val rejected = listOf(
            SessionEventPayload.ModelRequest(
                options = SessionJson.Object(mapOf("Authorization" to SessionJson.Text("secret"))),
            ),
            SessionEventPayload.UserMessage(text = "Bearer sk-secret"),
            SessionEventPayload.ToolCall(
                toolCallId = "call-1",
                name = "http",
                arguments = SessionJson.Object(mapOf("api_key" to SessionJson.Text("secret"))),
            ),
        )
        rejected.forEach { payload ->
            val result = runCatching { validatePayload(payload) }
            assertTrue(payload.toString(), result.isFailure)
        }
        validatePayload(SessionEventPayload.UserMessage(text = "please summarize this prompt"))
        validatePayload(
            SessionEventPayload.ToolCall(
                toolCallId = "call-1",
                name = "read_file",
                arguments = SessionJson.Object(mapOf("path" to SessionJson.Text("/tmp/a"))),
            ),
        )
    }

    private fun payloads(): List<SessionEventPayload> = listOf(
        SessionEventPayload.Empty,
        SessionEventPayload.UserInteraction("QUESTION", "PENDING", questionId = "run/call/0", text = "去哪？", options = listOf("上海", "苏州")),
        SessionEventPayload.UserInteraction("INPUT", "UNCONSUMED", "input-1", "run/call/0", "上海", reason = "CANCELLED"),
        SessionEventPayload.RunLifecycle(status = "RUNNING", modelRef = "deepseek", scene = "debug"),
        SessionEventPayload.UserMessage(
            text = "hello",
            attachments = listOf(SessionAttachmentRef(sha256 = "abc", mediaType = "text/plain", byteSize = 5)),
        ),
        SessionEventPayload.ModelRequest(
            messages = SessionJson.Array(listOf(SessionJson.Object(mapOf("role" to SessionJson.Text("user"))))),
            systemPrompt = "be concise",
            tools = SessionJson.Array(emptyList()),
            modelRef = "deepseek",
            toolChoice = "auto",
            options = SessionJson.Object(mapOf("temperature" to SessionJson.Number("0.2"))),
        ),
        SessionEventPayload.AssistantChunk(kind = "thinking", text = "hmm", signature = "sig"),
        SessionEventPayload.AssistantMessage(
            text = "done",
            finishReason = "stop",
            usage = SessionEventPayload.Usage(inputTokens = 3, outputTokens = 5),
            sourceChunkSequences = listOf(2, 3),
        ),
        SessionEventPayload.ToolCall(
            toolCallId = "run-1:call-a",
            name = "read_file",
            arguments = SessionJson.Object(mapOf("path" to SessionJson.Text("/context/a"))),
        ),
        SessionEventPayload.ToolResult(
            toolCallId = "run-1:call-a",
            name = "read_file",
            result = SessionJson.Text("file body"),
            ok = true,
            status = "COMPLETED",
        ),
        SessionEventPayload.Usage(inputTokens = 11, outputTokens = 7, cacheReadTokens = 2),
        SessionEventPayload.ModelResponseMetadata(
            fields = SessionJson.Object(mapOf("id" to SessionJson.Text("resp-1"))),
        ),
        SessionEventPayload.ModelCallFinished(
            callId = "run-1:model-1",
            status = "COMPLETED",
            durationMs = 42,
            messageCount = 2,
        ),
    )

    private fun stored(payload: SessionEventPayload): SessionEvent = SessionEvent(
        schemaVersion = EVENT_SCHEMA_VERSION,
        eventId = "event-1",
        sessionId = "session-1",
        runId = "run-1",
        requestId = "request-1",
        sequence = 1L,
        recordedAtEpochMillis = 100L,
        type = SessionEventType.RUN_STARTED,
        payload = payload,
        sourceEventSequences = emptyList(),
    )
}
