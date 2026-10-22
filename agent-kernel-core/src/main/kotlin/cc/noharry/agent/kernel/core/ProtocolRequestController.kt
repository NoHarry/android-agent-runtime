package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent

data class ProtocolRequestTemplate(
    val kind: String,
    val payloadJson: String,
    val expiresInMillis: Long?,
)

class ProtocolRequestController(
    private val nowEpochMillis: () -> Long,
    private val runStateController: RunStateController,
    storage: ReliabilityStorage? = null,
    locks: RunStateLockRegistry = RunStateLockRegistry(),
    retentionPolicy: RuntimeStateRetentionPolicy = RuntimeStateRetentionPolicy(),
) {
    private val store = InMemoryProtocolRequestStore(nowEpochMillis, storage, locks, retentionPolicy)

    fun create(
        template: ProtocolRequestTemplate,
        started: RuntimeRunSnapshot,
        request: AgentRequest,
    ): RuntimeProtocolRequest {
        val protocolRequest = store.create(
            runId = started.runId,
            sessionId = request.sessionId,
            kind = template.kind,
            payloadJson = template.payloadJson,
            expiresInMillis = template.expiresInMillis,
        )
        runStateController.markWaitingProtocolRequest(started)
        return protocolRequest
    }

    fun getPending(
        sessionId: String?,
        runId: String?,
    ): List<RuntimeProtocolRequest> = store.getPending(sessionId, runId)

    fun evidence(evidenceId: String): RuntimeEvidence? = store.evidence(evidenceId)

    fun traceEvents(runId: String): List<RuntimeTraceEvent> = store.traceEvents(runId)

    fun retainTerminalRun(runId: String) {
        store.retainTerminalRun(runId)
    }

    fun transition(
        protocolRequestId: String,
        requestId: String,
        targetStatus: RuntimeProtocolRequestStatus,
    ): ProtocolRequestCloseLoopResult {
        val result = store.transition(
            protocolRequestId = protocolRequestId,
            requestId = requestId,
            targetStatus = targetStatus,
        )
        result.protocolRequestOrNull()?.let { updateSnapshotAfterProtocolTransition(it) }
        return result
    }

    private fun updateSnapshotAfterProtocolTransition(request: RuntimeProtocolRequest) {
        when (request.status) {
            RuntimeProtocolRequestStatus.RESOLVED -> runStateController.markProtocolRequestResolved(request.runId)
            RuntimeProtocolRequestStatus.REJECTED,
            RuntimeProtocolRequestStatus.CANCELLED,
            RuntimeProtocolRequestStatus.EXPIRED,
            -> runStateController.markProtocolRequestTerminal(request.runId, RuntimeRunStatus.CANCELLED)
            RuntimeProtocolRequestStatus.CREATED,
            RuntimeProtocolRequestStatus.WAITING,
            -> Unit
        }
    }
}
