package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.spi.reliability.OpenConfusion
import cc.noharry.agent.spi.reliability.RuntimeEvidence
import cc.noharry.agent.spi.reliability.RuntimeHandoff
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunEnvelope
import cc.noharry.agent.spi.reliability.RuntimeRunSnapshot
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.spi.reliability.RuntimeTraceEvent
import cc.noharry.agent.spi.reliability.RuntimeWorkpad

internal interface ReliabilityStorage {
    /** Deletes all persisted artifacts for the session, including supplied ids whose run metadata may be damaged. */
    fun deleteSession(sessionId: String, knownRunIds: Set<String> = emptySet()): Set<String>

    fun saveRun(snapshot: RuntimeRunSnapshot, envelope: RuntimeRunEnvelope)

    fun readSnapshot(runId: String): RuntimeRunSnapshot?

    fun readEnvelope(runId: String): RuntimeRunEnvelope?

    fun latestSnapshot(sessionId: String, status: RuntimeRunStatus): RuntimeRunSnapshot?

    /**
     * Returns the most recent run snapshots for a session, newest first.
     *
     * Used by [SessionContextMaterializer] to build `/context/session/current.json`
     * for session continuity.
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

}
