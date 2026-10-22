@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.DebugRawTraceAccessDecision
import cc.noharry.agent.kernel.core.RuntimeBuildType
import cc.noharry.agent.kernel.core.TraceVisibilityConfig
import cc.noharry.agent.kernel.core.DEBUG_RAW_TRACE_NOT_ENABLED
import cc.noharry.agent.kernel.core.RELEASE_DEBUG_RAW_TRACE_ERROR
import cc.noharry.agent.kernel.core.RELEASE_RAW_TRACE_ERROR

class TraceSecurityTest {
    @Test
    fun releaseBuildRejectsRawTraceWorkspaceExposure() {
        val error = try {
            AgentRuntimeBuilder.create()
                .traceVisibility(
                    TraceVisibilityConfig(
                        buildType = RuntimeBuildType.RELEASE,
                        exposeRawTraceWorkspace = true,
                    ),
                )
                .build()
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertEquals(RELEASE_RAW_TRACE_ERROR, error?.message)
    }

    @Test
    fun releaseBuildRejectsDebugRawTraceAccess() {
        val error = try {
            AgentRuntimeBuilder.create()
                .traceVisibility(
                    TraceVisibilityConfig(
                        buildType = RuntimeBuildType.RELEASE,
                        allowDebugRawTraceAccess = true,
                    ),
                )
                .build()
            null
        } catch (error: IllegalArgumentException) {
            error
        }

        assertEquals(RELEASE_DEBUG_RAW_TRACE_ERROR, error?.message)
    }

    @Test
    fun modelTraceSummaryRequiresExplicitOptIn() {
        val defaultRuntime = AgentRuntimeBuilder.create().build() as DefaultAgentRuntime
        val optInRuntime = AgentRuntimeBuilder.create()
            .traceVisibility(TraceVisibilityConfig(exposeModelTraceSummary = true))
            .build() as DefaultAgentRuntime

        assertFalse(defaultRuntime.allowsModelTraceSummary())
        assertTrue(optInRuntime.allowsModelTraceSummary())
    }

    @Test
    fun debugRawTraceAccessRequiresExplicitOptIn() {
        val defaultRuntime = AgentRuntimeBuilder.create().build() as DefaultAgentRuntime
        val optInRuntime = AgentRuntimeBuilder.create()
            .traceVisibility(TraceVisibilityConfig(allowDebugRawTraceAccess = true))
            .build() as DefaultAgentRuntime

        val denied = defaultRuntime.debugRawTraceAccessDecision()
        val allowed = optInRuntime.debugRawTraceAccessDecision()

        assertEquals(
            DEBUG_RAW_TRACE_NOT_ENABLED,
            (denied as DebugRawTraceAccessDecision.Denied).reason,
        )
        assertEquals(DebugRawTraceAccessDecision.Allowed, allowed)
    }

    @Test
    fun redactedTraceSummaryRemovesSensitiveTerminalAndPlanData() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .modelClient(
                TraceSecurityModel(
                    eventsByRound = listOf(
                        listOf(schemaRead("set_temperature"), ModelEvent.Completed),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = qualifiedMcpTool("set_temperature"),
                                argumentsJson = """{"degreesCelsius":22,"token":"${FAKE_SECRET_KEY}"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """
                                    {"response":"done token=${FAKE_SECRET_KEY} phone=13800138000 lat=31.2304 longitude=121.4737"}
                                """.trimIndent(),
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build() as DefaultAgentRuntime

        runtime.run(agentRequest()).toList()
        val summaryText = runtime.getRedactedTraceSummary("request-1").toString()

        assertFalse(summaryText.contains(FAKE_SECRET_KEY))
        assertFalse(summaryText.contains("13800138000"))
        assertFalse(summaryText.contains("31.2304"))
        assertFalse(summaryText.contains("121.4737"))
        assertFalse(summaryText.contains("degreesCelsius"))
        assertTrue(summaryText.contains("[REDACTED_SECRET]"))
        assertTrue(summaryText.contains("[REDACTED_PHONE]"))
        assertTrue(summaryText.contains("[REDACTED_LOCATION]"))
        assertTrue(summaryText.contains("set_temperature") || summaryText.contains("[REDACTED"))
    }

    @Test
    fun redactedTraceSummaryRemovesSensitiveWaitingStateData() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(java.nio.file.Files.createTempDirectory("trace-ws-").toFile())
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmWithPrompt("Need token sk-waitsecret123 phone 13800138000 lat=31.2304"))
            .modelClient(FakeModelClient(schemaThenBackendRounds()))
            .build() as DefaultAgentRuntime

        runtime.run(agentRequest()).toList()
        val summaryText = runtime.getRedactedTraceSummary("request-1").toString()

        assertFalse(summaryText.contains("sk-waitsecret123"))
        assertFalse(summaryText.contains("13800138000"))
        assertFalse(summaryText.contains("31.2304"))
        assertTrue(summaryText.contains("[REDACTED_SECRET]"))
        assertTrue(summaryText.contains("[REDACTED_PHONE]"))
        assertTrue(summaryText.contains("[REDACTED_LOCATION]"))
        assertTrue(summaryText.contains("WAITING_CONFIRMATION"))
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "run",
    )
}

private class TraceSecurityModel(
    private val eventsByRound: List<List<ModelEvent>>,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    constructor(vararg events: ModelEvent) : this(listOf(events.toList()))

    private var round = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        val events = eventsByRound.getOrElse(round) { listOf(ModelEvent.Completed) }
        round += 1
        return flowOf(*events.toTypedArray())
    }
}

/** A made-up key the redaction must catch; built at run time so the source never holds a key-shaped literal. */
private val FAKE_SECRET_KEY = "sk-" + "terminalsecret123"
