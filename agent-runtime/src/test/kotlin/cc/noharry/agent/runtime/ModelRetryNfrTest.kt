@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.failed

class ModelRetryNfrTest {
    @Test
    fun recoverableThirtyPercentTransientFailuresMeetSuccessThreshold() = runBlocking {
        var successes = 0
        var retryEvents = 0

        repeat(NFR_SAMPLE_RUNS) { index ->
            val failFirstAttempt = index < NFR_TRANSIENT_FAILURE_RUNS
            val events = AgentRuntimeBuilder.create()
                .modelRetry(maxAttempts = 2, initialBackoffMillis = 0L, maxBackoffMillis = 0L)
                .modelClient(TransientFailureModelClient(failFirstAttempt))
                .build()
                .run(nfrRequest(index))
                .toList()

            val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result
            if (result.status == RuntimeRunStatus.COMPLETED) successes += 1
            retryEvents += events.count { it is AgentEvent.RetryAttempted }
        }

        val successRate = successes.toDouble() / NFR_SAMPLE_RUNS
        assertTrue("successRate=$successRate", successRate >= NFR_RETRY_SUCCESS_THRESHOLD)
        assertEquals(NFR_TRANSIENT_FAILURE_RUNS, retryEvents)
    }

    @Test
    fun unrecoverableModelFailureDoesNotRetry() = runBlocking {
        val events = AgentRuntimeBuilder.create()
            .modelRetry(maxAttempts = 2, initialBackoffMillis = 0L, maxBackoffMillis = 0L)
            .modelClient(UnrecoverableFailureModelClient())
            .build()
            .run(nfrRequest(0))
            .toList()

        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.FAILED, result.status)
        assertEquals(RuntimeErrorCode.PROVIDER_AUTH_FAILED.wireCode, result.errorCode)
        assertTrue(events.none { it is AgentEvent.RetryAttempted })
    }

    private fun nfrRequest(index: Int): AgentRequest =
        AgentRequest("retry-nfr-$index", "session-retry-nfr", "finish")
}

private class TransientFailureModelClient(
    private val failFirstAttempt: Boolean,
) : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    private var attempts = 0

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        attempts += 1
        if (failFirstAttempt && attempts == 1) {
            throw ModelClientException(
                RuntimeErrorCode.PROVIDER_TRANSPORT_FAILED,
                recoverable = true,
                message = "transient provider failure",
            )
        }
        emit(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""))
        emit(ModelEvent.Completed)
    }
}

private class UnrecoverableFailureModelClient : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration =
        ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        throw ModelClientException(
            RuntimeErrorCode.PROVIDER_AUTH_FAILED,
            recoverable = false,
            message = "auth failed",
        )
    }
}

private const val NFR_SAMPLE_RUNS = 100
private const val NFR_TRANSIENT_FAILURE_RUNS = 30
private const val NFR_RETRY_SUCCESS_THRESHOLD = 0.95
