@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.reliability.*

/** Advanced diagnostic records, separate from the stable conversation API. */
@ExperimentalAgentRuntimeApi
interface RuntimeDiagnostics {
    suspend fun getRunState(turnId: String): RuntimeRunSnapshot?
    suspend fun getRuntimeResult(turnId: String): RuntimeResult?
    suspend fun getRuntimeHandoff(turnId: String): RuntimeHandoff?
    suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence?
    suspend fun getRuntimeTraceEvents(turnId: String): List<RuntimeTraceEvent>
    suspend fun querySessionEvents(query: SessionEventQuery): List<SessionEvent>
    suspend fun replaySessionEvents(threadId: String, turnId: String): List<SessionEvent>
    suspend fun getSessionEventLogHealth(threadId: String, turnId: String): SessionEventLogHealth
    fun subscribeSessionEvents(threadId: String, listener: (SessionEvent) -> Unit): AutoCloseable
}
