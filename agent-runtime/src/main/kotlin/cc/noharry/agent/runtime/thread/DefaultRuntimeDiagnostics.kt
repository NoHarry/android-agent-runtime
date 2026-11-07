@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.RuntimeDiagnostics
import cc.noharry.agent.spi.AgentRuntime
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.spi.SessionEventLogHealth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class DefaultRuntimeDiagnostics(
    private val runtime: AgentRuntime,
    private val attempts: ThreadAttemptStore,
) : RuntimeDiagnostics {
    private suspend fun execution(threadId: String, turnId: String): String? = withContext(Dispatchers.IO) {
        attempts.latest(threadId, turnId)?.executionId
    }
    override suspend fun getRunState(threadId: String, turnId: String) =
        execution(threadId, turnId)?.let { runtime.getRunState(it) }
    override suspend fun getRuntimeResult(threadId: String, turnId: String) =
        execution(threadId, turnId)?.let { runtime.getRuntimeResult(it) }
    override suspend fun getRuntimeHandoff(threadId: String, turnId: String) =
        execution(threadId, turnId)?.let { runtime.getRuntimeHandoff(it) }
    override suspend fun getRuntimeEvidence(evidenceId: String) = runtime.getRuntimeEvidence(evidenceId)
    override suspend fun getRuntimeTraceEvents(threadId: String, turnId: String) =
        execution(threadId, turnId)?.let { runtime.getRuntimeTraceEvents(it) }.orEmpty()
    override suspend fun querySessionEvents(query: SessionEventQuery) = runtime.querySessionEvents(query)
    override suspend fun replaySessionEvents(threadId: String, turnId: String) =
        execution(threadId, turnId)?.let { runtime.replaySessionEvents(threadId, it) }.orEmpty()
    override suspend fun getSessionEventLogHealth(threadId: String, turnId: String) =
        execution(threadId, turnId)?.let { runtime.getSessionEventLogHealth(threadId, it) } ?: SessionEventLogHealth.unknown()
    override fun subscribeSessionEvents(threadId: String, listener: (SessionEvent) -> Unit) = runtime.subscribeSessionEvents(threadId, listener)
}
