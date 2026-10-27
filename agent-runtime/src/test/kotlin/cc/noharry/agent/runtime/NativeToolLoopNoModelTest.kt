@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class NativeToolLoopNoModelTest {
    @Test
    fun ordinaryRunFailsClosedWhenModelMissing() = runBlocking {
        val outcome = loopWithoutModel().run(agentRequest(), "request-1") as NativeLoopOutcome.Terminal
        assertNoModelFailure(outcome)
    }

    @Test
    fun userInputResumeFailsClosedWhenModelMissing() = runBlocking {
        val outcome = loopWithoutModel().resumeUserInput(
            runId = "request-1",
            sessionId = "session-1",
            requestId = "request-1",
            answer = "more detail",
            scene = null,
            modelRef = null,
        ) as NativeLoopOutcome.Terminal
        assertNoModelFailure(outcome)
    }

    @Test
    fun ordinaryRuntimeRunFailsClosedWhenModelMissing() = runBlocking {
        val runtime = AgentRuntimeBuilder.create().build()
        try {
            runtime.run(agentRequest()).toList()
            val result = requireNotNull(runtime.getRuntimeResult("request-1"))
            assertEquals(RuntimeRunStatus.FAILED, result.status)
            assertEquals(RuntimeCompletionOutcome.UNKNOWN, result.completion.outcome)
            assertEquals(RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode, result.errorCode)
            assertEquals(MODEL_CLIENT_NOT_CONFIGURED_MESSAGE, result.response)
            assertFalse(RuntimeCompletionSummary.firstPartyPass(result.status, result.completion))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun fixtureModelClientCompletesInsteadOfNullScaffold() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall("finish-1", "finish", """{"response":"fixture-done"}"""),
                            ModelEvent.Completed,
                        ),
                    ),
                ),
            )
            .build()
        try {
            runtime.run(agentRequest()).toList()
            val result = requireNotNull(runtime.getRuntimeResult("request-1"))
            assertEquals(RuntimeRunStatus.COMPLETED, result.status)
            assertEquals(RuntimeCompletionOutcome.SUCCEEDED, result.completion.outcome)
            assertTrue(result.response.contains("fixture-done"))
        } finally {
            runtime.close()
        }
    }

    private fun loopWithoutModel(): NativeToolLoop = NativeToolLoop(
        modelClient = null,
        mcpRegistry = ToolProviderRegistry(),
        workspace = null,
    )

    private fun assertNoModelFailure(outcome: NativeLoopOutcome.Terminal) {
        assertEquals(RuntimeRunStatus.FAILED, outcome.result.status)
        assertEquals(RuntimeCompletionOutcome.UNKNOWN, outcome.result.completion.outcome)
        assertEquals(RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode, outcome.result.errorCode)
        assertEquals(MODEL_CLIENT_NOT_CONFIGURED_MESSAGE, outcome.result.response)
        assertFalse(RuntimeCompletionSummary.firstPartyPass(outcome.result.status, outcome.result.completion))
    }
}
