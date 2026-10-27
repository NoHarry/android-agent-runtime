@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicQueryPrivacyTest {
    @Test
    fun publicResultQueryAndEventsRedactSecretPhoneAndLocation() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                PrivacyModel(
                    ModelEvent.ToolCall(
                        id = "tool-1",
                        name = "finish",
                        argumentsJson = """
                            {"response":"done token=sk-querysecret123 phone=13800138000 lat=31.2304 longitude=121.4737"}
                        """.trimIndent(),
                    ),
                    ModelEvent.Completed,
                ),
            )
            .build() as DefaultAgentRuntime

        val events = runtime.run(agentRequest()).toList()
        val resultEvent = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
        val result = requireNotNull(runtime.getRuntimeResult("request-1"))
        val evidence = requireNotNull(runtime.getRuntimeEvidence(result.evidenceIds.single()))
        val workpad = requireNotNull(runtime.getRuntimeWorkpad("request-1"))
        val publicText = listOf(
            resultEvent.response,
            result.response,
            evidence.summary,
            workpad.planSummaries.joinToString(),
            workpad.writeArtifacts.joinToString(),
        ).joinToString()

        assertNoPii(publicText)
        assertTrue(publicText.contains("[REDACTED_SECRET]"))
        assertTrue(publicText.contains("[REDACTED_PHONE]"))
        assertTrue(publicText.contains("[REDACTED_LOCATION]"))
    }

    @Test
    fun publicHandoffBlockerAndFailedPathsRedactSecretPhoneAndLocation() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("privacy-ws-").toFile())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmWithPrompt("Need token=sk-waitquery123 phone=13800138000 lat=31.2304"))
            .modelClient(FakeModelClient(schemaThenBackendRounds()))
            .build() as DefaultAgentRuntime

        val events = runtime.run(agentRequest()).toList()
        assertTrue(events.any { it is AgentEvent.ConfirmationRequired })
        val handoff = requireNotNull(runtime.getRuntimeHandoff("request-1"))
        val workpad = requireNotNull(runtime.getRuntimeWorkpad("request-1"))
        val publicText = listOf(
            handoff.summary,
            handoff.nextActions.joinToString(),
            workpad.planSummaries.joinToString(),
        ).joinToString()

        assertNoPii(publicText)
        assertTrue(publicText.contains("[REDACTED_SECRET]"))
        assertTrue(publicText.contains("[REDACTED_PHONE]"))
        assertTrue(publicText.contains("[REDACTED_LOCATION]"))
    }

    @Test
    fun publicFailedEventRedactsLoopFailurePayload() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                object : ModelClient {
                    override val providerConcurrency: ProviderConcurrencyDeclaration =
                        ProviderConcurrencyDeclaration.serialized()

                    override fun complete(request: ModelRequest): Flow<ModelEvent> {
                        throw IllegalStateException(
                            "provider exploded token=sk-failsecret123 phone=13800138000 lat=31.2304",
                        )
                    }
                },
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        val result = requireNotNull(runtime.getRuntimeResult("request-1"))
        val publicText = listOf(failed.message, result.response).joinToString()

        assertNoPii(publicText)
        assertTrue(publicText.contains("[REDACTED_SECRET]"))
        assertTrue(publicText.contains("[REDACTED_PHONE]"))
        assertTrue(publicText.contains("[REDACTED_LOCATION]"))
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "run",
    )

    private fun assertNoPii(value: String) {
        assertFalse(value.contains("sk-querysecret123"))
        assertFalse(value.contains("sk-waitquery123"))
        assertFalse(value.contains("sk-failsecret123"))
        assertFalse(value.contains("13800138000"))
        assertFalse(value.contains("31.2304"))
        assertFalse(value.contains("121.4737"))
    }
}

private class PrivacyModel(
    vararg private val events: ModelEvent,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()

    override fun complete(request: ModelRequest): Flow<ModelEvent> =
        flowOf(*events)
}
