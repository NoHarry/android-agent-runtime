@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.RuntimeDiagnostics
import cc.noharry.agent.spi.AgentRuntime
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventQuery

internal class DefaultRuntimeDiagnostics(private val runtime: AgentRuntime) : RuntimeDiagnostics {
    override suspend fun getRunState(turnId: String) = runtime.getRunState(turnId)
    override suspend fun getRuntimeResult(turnId: String) = runtime.getRuntimeResult(turnId)
    override suspend fun getRuntimeHandoff(turnId: String) = runtime.getRuntimeHandoff(turnId)
    override suspend fun getRuntimeEvidence(evidenceId: String) = runtime.getRuntimeEvidence(evidenceId)
    override suspend fun getRuntimeTraceEvents(turnId: String) = runtime.getRuntimeTraceEvents(turnId)
    override suspend fun querySessionEvents(query: SessionEventQuery) = runtime.querySessionEvents(query)
    override suspend fun replaySessionEvents(threadId: String, turnId: String) = runtime.replaySessionEvents(threadId, turnId)
    override suspend fun getSessionEventLogHealth(threadId: String, turnId: String) = runtime.getSessionEventLogHealth(threadId, turnId)
    override fun subscribeSessionEvents(threadId: String, listener: (SessionEvent) -> Unit) = runtime.subscribeSessionEvents(threadId, listener)
}
