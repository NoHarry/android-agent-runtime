@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent

import cc.noharry.agent.spi.*
import cc.noharry.agent.spi.reliability.*

/**
 * Advanced diagnostic records, separate from the stable conversation API.
 * Conversation queries select the latest execution attempt for `(threadId, turnId)`. Returned raw records use
 * `runId` for that execution's unique id, `sessionId` for the thread id and `requestId` for the public turn id.
 * [querySessionEvents] accepts these raw identifiers and can query earlier attempts using their execution ids.
 */
@ExperimentalAgentRuntimeApi
interface RuntimeDiagnostics {
    suspend fun getRunState(threadId: String, turnId: String): RuntimeRunSnapshot?
    suspend fun getRuntimeResult(threadId: String, turnId: String): RuntimeResult?
    suspend fun getRuntimeHandoff(threadId: String, turnId: String): RuntimeHandoff?
    suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence?
    suspend fun getRuntimeTraceEvents(threadId: String, turnId: String): List<RuntimeTraceEvent>
    suspend fun querySessionEvents(query: SessionEventQuery): List<SessionEvent>
    suspend fun replaySessionEvents(threadId: String, turnId: String): List<SessionEvent>
    suspend fun getSessionEventLogHealth(threadId: String, turnId: String): SessionEventLogHealth
    fun subscribeSessionEvents(threadId: String, listener: (SessionEvent) -> Unit): AutoCloseable
}
