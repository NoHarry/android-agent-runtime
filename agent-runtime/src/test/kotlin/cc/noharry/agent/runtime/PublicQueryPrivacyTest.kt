@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.trace.StandardTraceRedactor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicQueryPrivacyTest {
    @Test
    fun theAnswerGoesOutAsWrittenWhateverItHolds() = runBlocking {
        val answer = "order 13800138000 token=sk-querysecret123 lat=31.2304, 2^40 is 1099511627776"
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                PrivacyModel(
                    ModelEvent.ToolCall(id = "tool-1", name = "finish", argumentsJson = """{"response":"$answer"}"""),
                    ModelEvent.Completed,
                ),
            )
            .build() as DefaultAgentRuntime

        val events = runtime.run(agentRequest()).toList()

        assertEquals(answer, events.filterIsInstance<AgentEvent.ResultAvailable>().single().result.response)
        assertEquals(answer, requireNotNull(runtime.getRuntimeResult("request-1")).response)
        assertEquals(answer, events.filterIsInstance<AgentEvent.TextDelta>().joinToString("") { it.text })
    }

    @Test
    fun summariesOfTheRunStillHideSecretPhoneAndLocation() = runBlocking {
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

        runtime.run(agentRequest()).toList()
        val result = requireNotNull(runtime.getRuntimeResult("request-1"))
        val evidence = requireNotNull(runtime.getRuntimeEvidence(result.evidenceIds.single()))
        val workpad = requireNotNull(runtime.getRuntimeWorkpad("request-1"))
        val summaries = listOf(
            evidence.summary,
            workpad.planSummaries.joinToString(),
            workpad.writeArtifacts.joinToString(),
        ).joinToString()

        assertNoPii(summaries)
        assertTrue(summaries, summaries.contains("[REDACTED_SECRET]"))
        assertTrue(summaries, summaries.contains("[REDACTED_PHONE]"))
        assertTrue(summaries, summaries.contains("[REDACTED_LOCATION]"))
    }

    @Test
    fun aFailureReportNeverCarriesASecretWhateverTheHostsRedactor() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                object : ModelClient {
                    override val providerConcurrency: ProviderConcurrencyDeclaration =
                        ProviderConcurrencyDeclaration.serialized()

                    override fun complete(request: ModelRequest): Flow<ModelEvent> {
                        throw IllegalStateException("provider exploded token=sk-failsecret123 order 13800138000")
                    }
                },
            )
            .traceRedactor { it }
            .build()

        val events = runtime.run(agentRequest()).toList()
        val report = listOf(
            events.filterIsInstance<AgentEvent.Failed>().single().message,
            requireNotNull(runtime.getRuntimeResult("request-1")).response,
        ).joinToString()

        assertFalse(report, report.contains("sk-failsecret123"))
        // The host's redactor keeps phone numbers in reports; only secrets are always hidden.
        assertTrue(report, report.contains("13800138000"))
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
