@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.PostUserInputRequest
import cc.noharry.agent.kernel.api.PostUserInputResult
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSteeringTest {
    @Test
    fun acceptedInputDuringFinalRoundContinuesOnceAndRejectsDuplicateOrWrongSession() = runBlocking {
        val model = SteeringModelClient(firstRoundFinishes = true)
        val runtime = AgentRuntimeBuilder.create().modelClient(model).build()
        try {
            val running = async { runtime.run(agentRequest()).toList() }
            model.firstRoundStarted.await()
            val input = PostUserInputRequest("request-1", "late-input", "change the destination", "session-1")
            assertEquals(PostUserInputResult.Accepted, runtime.postUserInput(input))
            assertEquals(PostUserInputResult.Duplicate, runtime.postUserInput(input))
            assertEquals(PostUserInputResult.Rejected("SESSION_MISMATCH"), runtime.postUserInput(input.copy(sessionId = "other")))
            assertEquals(PostUserInputResult.Rejected("REQUEST_ID_CONFLICT"), runtime.postUserInput(input.copy(text = "different")))
            model.releaseFirstRound.complete(Unit)
            val events = running.await()
            assertEquals(2, model.seenRequests.size)
            assertEquals(1, model.seenRequests.last().messages.count { it.role == "user" && it.content.contains(input.text) })
            assertEquals(1, events.filterIsInstance<AgentEvent.SteeringAccepted>().size)
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        } finally { runtime.close() }
    }

    @Test
    fun postUserInputQueuesSteeringForNextModelRound() = runBlocking {
        val model = SteeringModelClient()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(model)
            .build()

        val eventsDeferred = async { runtime.run(agentRequest()).toList() }
        model.firstRoundStarted.await()

        val result = runtime.postUserInput(
            PostUserInputRequest(
                runId = "request-1",
                requestId = "steering-1",
                text = "also explain why",
            ),
        )

        assertEquals(PostUserInputResult.Accepted, result)
        assertFalse(eventsDeferred.isCompleted)

        model.releaseFirstRound.complete(Unit)
        val events = eventsDeferred.await()

        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        assertEquals(2, model.seenRequests.size)
        assertTrue(model.seenRequests.first().messages.none { it.content.contains("also explain why") })
        assertTrue(
            model.seenRequests[1].messages.any {
                it.role == "user" && it.content.contains("also explain why")
            },
        )
        val steering = events.filterIsInstance<AgentEvent.SteeringAccepted>().single()
        assertEquals("request-1", steering.runId)
        assertEquals("steering-1", steering.requestId)
        assertEquals("also explain why", steering.textPreview)
    }

    @Test
    fun postUserInputRejectsUnknownOrInactiveRuns() = runBlocking {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(FakeModelClient(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}""")))
            .build()

        assertEquals(
            PostUserInputResult.NotFound,
            runtime.postUserInput(PostUserInputRequest("missing", "steering-1", "ignored")),
        )

        runtime.run(agentRequest()).toList()

        assertEquals(
            PostUserInputResult.RunNotActive,
            runtime.postUserInput(PostUserInputRequest("request-1", "steering-2", "too late")),
        )
    }

    private fun agentRequest(): AgentRequest = AgentRequest(
        requestId = "request-1",
        sessionId = "session-1",
        input = "call slow then finish",
    )
}

private class SteeringModelClient(private val firstRoundFinishes: Boolean = false) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    val seenRequests = mutableListOf<ModelRequest>()
    val firstRoundStarted = CompletableDeferred<Unit>()
    val releaseFirstRound = CompletableDeferred<Unit>()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        seenRequests += request.copy(
            messages = request.messages.toList(),
            tools = request.tools.toList(),
        )
        if (seenRequests.size == 1) {
            firstRoundStarted.complete(Unit)
            releaseFirstRound.await()
            if (firstRoundFinishes) {
                emit(ModelEvent.ToolCall("initial-finish", "finish", """{"response":"original answer"}"""))
                emit(ModelEvent.Completed)
                return@flow
            }
            emit(
                ModelEvent.ToolCall(
                    "tool-1",
                    "update_todo",
                    """{"items":[{"text":"first tool","status":"done"}]}""",
                ),
            )
        } else {
            emit(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""))
        }
        emit(ModelEvent.Completed)
    }
}
