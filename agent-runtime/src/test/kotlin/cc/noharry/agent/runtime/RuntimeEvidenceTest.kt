@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEvidenceTest {
    @Test
    fun terminalResultWritesQueryableRedactedEvidence() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                EvidenceModel(
                    ModelEvent.ToolCall(
                        id = "tool-1",
                        name = "finish",
                        argumentsJson = """
                            {"response":"done token=sk-resultsecret123 phone=13800138000 lat=31.2304"}
                        """.trimIndent(),
                    ),
                    ModelEvent.Completed,
                ),
            )
            .build() as DefaultAgentRuntime

        val events = runtime.run(agentRequest()).toList()
        val resultEvent = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val result = requireNotNull(runtime.getRuntimeResult("request-1"))
        val evidenceId = resultEvent.evidenceIds.single()
        val evidence = requireNotNull(runtime.getRuntimeEvidence(evidenceId))
        val resultTrace = runtime.getRuntimeTraceEvents("request-1")
            .single { it.eventName == "RESULT_WRITTEN" }
        val runCreatedTrace = runtime.getRuntimeTraceEvents("request-1")
            .single { it.eventName == "RUN_CREATED" }
        val workpadTrace = runtime.getRuntimeTraceEvents("request-1")
            .first { it.eventName == "WORKPAD_UPDATED" }

        assertEquals(result.evidenceIds, resultEvent.evidenceIds)
        assertEquals(RuntimeEvidenceType.RESULT_WRITTEN, evidence.type)
        assertEquals("RESULT_WRITTEN", evidence.traceEventName)
        assertEquals(evidenceId, resultTrace.evidenceId)
        assertEquals(RuntimeEvidenceType.RUN_CREATED, runtime.getRuntimeEvidence(runCreatedTrace.evidenceId!!)?.type)
        assertEquals(RuntimeEvidenceType.WORKPAD_UPDATED, runtime.getRuntimeEvidence(workpadTrace.evidenceId!!)?.type)
        assertRedacted(evidence.summary)
    }

    @Test
    fun waitingConfirmationWritesHandoffEvidence() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("evidence-ws-").toFile())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmWithPrompt("Need token=sk-waitsecret123 phone=13800138000"))
            .modelClient(FakeModelClient(schemaThenBackendRounds()))
            .build() as DefaultAgentRuntime

        assertTrue(runtime.run(agentRequest()).toList().any { it is AgentEvent.ConfirmationRequired })
        val handoff = requireNotNull(runtime.getRuntimeHandoff("request-1"))
        val handoffEvidence = requireNotNull(runtime.getRuntimeEvidence(handoff.evidenceRefs.single()))

        assertEquals(RuntimeEvidenceType.HANDOFF_WRITTEN, handoffEvidence.type)
        assertTrue(runtime.getRuntimeTraceEvents("request-1").any { it.evidenceId == handoffEvidence.evidenceId })
        assertRedacted(handoffEvidence.summary)
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "run",
    )

    private fun assertRedacted(summary: String) {
        assertFalse(summary.contains("sk-resultsecret123"))
        assertFalse(summary.contains("sk-waitsecret123"))
        assertFalse(summary.contains("13800138000"))
        assertFalse(summary.contains("31.2304"))
        assertTrue(summary.contains("[REDACTED_SECRET]") || summary.contains("[REDACTED_PHONE]"))
    }
}

private class EvidenceModel(
    vararg private val events: ModelEvent,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> =
        flowOf(*events)
}
