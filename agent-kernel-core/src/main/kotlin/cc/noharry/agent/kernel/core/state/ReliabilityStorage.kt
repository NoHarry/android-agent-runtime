package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad

interface ReliabilityStorage {
    fun saveRun(snapshot: RuntimeRunSnapshot, envelope: RuntimeRunEnvelope)

    fun readSnapshot(runId: String): RuntimeRunSnapshot?

    fun readEnvelope(runId: String): RuntimeRunEnvelope?

    fun latestSnapshot(sessionId: String, status: RuntimeRunStatus): RuntimeRunSnapshot?

    /**
     * Returns the most recent run snapshots for a session, newest first.
     *
     * Used by [SessionContextMaterializer] to build `/context/session/current.json`
     * for session continuity (SPEC-CDR-003).
     */
    fun recentRunsForSession(sessionId: String, limit: Int = 3): List<RuntimeRunSnapshot>

    fun saveWorkpad(workpad: RuntimeWorkpad)

    fun readWorkpad(runId: String): RuntimeWorkpad?

    fun saveResult(result: RuntimeResult)

    fun readResult(runId: String): RuntimeResult?

    fun saveHandoff(handoff: RuntimeHandoff)

    fun readHandoff(runId: String): RuntimeHandoff?

    fun saveOpenConfusion(confusion: OpenConfusion)

    fun readOpenConfusion(runId: String): OpenConfusion?

    fun saveEvidence(evidence: RuntimeEvidence)

    fun readEvidence(evidenceId: String): RuntimeEvidence?

    fun appendTrace(event: RuntimeTraceEvent)

    fun readTrace(runId: String): List<RuntimeTraceEvent>

    fun saveProtocolRequest(request: RuntimeProtocolRequest)

    fun readProtocolRequest(protocolRequestId: String): RuntimeProtocolRequest?

    fun readPendingProtocolRequests(sessionId: String?, runId: String?): List<RuntimeProtocolRequest>

    fun saveProtocolResolution(
        protocolRequestId: String,
        requestId: String,
        request: RuntimeProtocolRequest,
    )

    fun readProtocolResolution(
        protocolRequestId: String,
        requestId: String,
    ): RuntimeProtocolRequest?
}
