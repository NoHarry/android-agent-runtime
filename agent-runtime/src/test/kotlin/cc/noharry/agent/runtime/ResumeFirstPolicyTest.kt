@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeFirstPolicyTest {
    @Test
    fun sameSessionRunIsBlockedWhenConfirmationIsPending() = runBlocking {
        val model = CountingModelClient()
        val runtime = AgentRuntimeBuilder.create()
            .confirmationOnRun()
            .modelClient(model)
            .build()

        val first = runtime.run(agentRequest("request-1")).toList()
        val second = runtime.run(agentRequest("request-2", SessionRunConflictPolicy.RESUME_CURRENT)).toList()
        val blocker = second.filterIsInstance<AgentEvent.RuntimeBlocked>().single().blocker

        assertEquals(1, first.filterIsInstance<AgentEvent.ConfirmationRequired>().size)
        assertEquals("request-1", blocker.runId)
        assertEquals("PENDING_CONFIRMATION_EXISTS", blocker.code)
        assertEquals(SuggestedNextAction.RESUME_CONFIRMATION, blocker.suggestedNextAction)
        assertTrue(second.filterIsInstance<AgentEvent.Started>().isEmpty())
        assertTrue(second.filterIsInstance<AgentEvent.ResultAvailable>().isEmpty())
        assertEquals(0, model.callCount)
    }

    private fun agentRequest(
        requestId: String,
        sessionRunConflictPolicy: SessionRunConflictPolicy? = null,
    ): AgentRequest = AgentRequest(
        requestId = requestId,
        sessionId = "session-1",
        input = "run",
        sessionRunConflictPolicy = sessionRunConflictPolicy,
    )
}

private class CountingModelClient : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    var callCount = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> {
        callCount += 1
        return flowOf(ModelEvent.Completed)
    }
}
