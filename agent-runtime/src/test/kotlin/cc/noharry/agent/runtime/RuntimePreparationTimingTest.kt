@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.model.*
import cc.noharry.agent.kernel.core.*
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimePreparationTimingTest {
    @Test fun compactionTimingsAndChunksDoNotLeakIntoBusinessResponse() {
        val log = InMemorySessionEventLog()
        val recorder = RuntimeSessionRecorder(null, log) { 100L }
        val context = MaterializerContext("s", "r", "q")
        recorder.startRun(AgentRequest("q", "s", "input"), "r")
        recorder.recordModelRequest(context, ModelRequest(listOf(ModelMessage("user", "compact"))))
        recorder.recordModelStreamEvent(context, ModelEvent.TextDelta("summary only"))
        recorder.recordModelCall(call("r", "compact").copy(source = SESSION_MODEL_CALL_SOURCE_COMPACTION))
        recorder.recordModelRequest(context, ModelRequest(listOf(ModelMessage("user", "continue"))))
        recorder.recordModelCall(call("r", "business"))
        recorder.appendMessage(context, ModelMessage("assistant", "actual answer"))
        val records = log.replay("s", "r")
        val message = records.mapNotNull { it.payload as? SessionEventPayload.AssistantMessage }.single()
        assertTrue(message.sourceChunkSequences.isEmpty())
        val timings = records.mapNotNull { it.payload as? SessionEventPayload.ModelResponseMetadata }
        assertEquals(2, timings.size)
        val business = (((timings.last().fields as SessionJson.Object).values["timings"]) as SessionJson.Object).values
        assertFalse(business.containsKey("firstContentMs"))
    }

    @Test fun preparationAndFirstContentAreIsolatedByRunAndConsumedOnce() {
        val log = InMemorySessionEventLog()
        val recorder = RuntimeSessionRecorder(null, log) { 100L }
        val context = MaterializerContext("s", "r", "q")
        recorder.startRun(AgentRequest("q", "s", "input"), "r")
        recorder.recordPreparationTimings(context, mapOf("provider.0.initializeMs" to 17L, "promptAssemblyMs" to 4L))
        recorder.recordModelRequest(context, ModelRequest(listOf(ModelMessage("user", "input"))))
        recorder.recordModelStreamEvent(context, ModelEvent.TextDelta("answer"))
        recorder.recordModelCall(call("r", "c1"))
        recorder.recordModelRequest(context, ModelRequest(listOf(ModelMessage("user", "input"))))
        recorder.recordModelCall(call("r", "c2"))
        val records = log.replay("s", "r").mapNotNull { it.payload as? SessionEventPayload.ModelResponseMetadata }
        val first = (((records[0].fields as SessionJson.Object).values["timings"]) as SessionJson.Object).values
        val second = (((records[1].fields as SessionJson.Object).values["timings"]) as SessionJson.Object).values
        assertEquals(SessionJson.Number("17"), first["provider.0.initializeMs"])
        assertTrue(first.containsKey("firstContentMs"))
        assertFalse(second.containsKey("provider.0.initializeMs"))
        assertFalse(second.containsKey("firstContentMs"))
    }

    @Test fun catalogTimingSeparatesInitializationFromListAndDoesNotRefetchSnapshot() = runBlocking {
        var initialized = 0
        var listed = 0
        val provider = object : ToolProvider {
            override val providerId = "timing"
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override suspend fun initialize(context: ToolInitializeContext): ToolInitializeResult {
                initialized++
                return ToolInitializeResult.connected()
            }
            override suspend fun listTools(): List<ToolDescriptor> { listed++; return emptyList() }
            override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext) = error("unused")
        }
        val registry = ToolProviderRegistry(listOf(provider))
        val context = MaterializerContext("s", "r", "q")
        registry.allTools(context)
        val timings = registry.preparationTimings(context)
        assertEquals(1, initialized)
        assertEquals(1, listed)
        assertTrue(timings.keys.containsAll(listOf("provider.0.initializeMs", "provider.0.listToolsMs", "catalogSnapshotMs")))
        assertTrue(timings.values.all { it >= 0 })
    }

    private fun call(run: String, id: String) = StoredModelCall(
        runId = run, sessionId = "s", callId = id, status = "COMPLETED", modelRef = "fixture",
        startedAtEpochMillis = 100, finishedAtEpochMillis = 110, durationMs = 10, messageCount = 1, toolCount = 0,
    )
}
