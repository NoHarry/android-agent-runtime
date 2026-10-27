package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.core.trace.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.trace.TraceRedactor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RuntimeEvidenceController(
    private val nowEpochMillis: () -> Long,
    private val redactor: TraceRedactor = DefaultTraceRedactor,
    private val storage: ReliabilityStorage? = null,
    private val locks: RunStateLockRegistry = RunStateLockRegistry(),
    private val retentionPolicy: RuntimeStateRetentionPolicy = RuntimeStateRetentionPolicy(),
) {
    private val evidence = ConcurrentHashMap<String, RuntimeEvidence>()
    private val evidenceIdsByRun = ConcurrentHashMap<String, ArrayDeque<String>>()
    private val evictedRuns = BoundedStringSet(retentionPolicy.maxTerminalRuns)

    fun record(
        runId: String,
        type: RuntimeEvidenceType,
        summary: String,
        traceEventName: String? = null,
    ): String {
        val evidenceId = evidenceId(runId, type)
        val record = RuntimeEvidence(
            evidenceId = evidenceId,
            runId = runId,
            type = type,
            summary = redactor.redact(summary),
            createdAtEpochMillis = nowEpochMillis(),
            traceEventName = traceEventName,
        )
        cache(record)
        storage?.saveEvidence(record)
        return evidenceId
    }

    fun evidence(evidenceId: String): RuntimeEvidence? =
        evidence[evidenceId] ?: storage?.readEvidence(evidenceId)

    fun clearRun(runId: String) {
        locks.withRunLock(runId) {
            evictedRuns.add(runId)
            evidenceIdsByRun.remove(runId).orEmpty().forEach(evidence::remove)
        }
    }

    fun activateRun(runId: String) {
        evictedRuns.remove(runId)
    }

    fun cachedEvidenceCount(runId: String): Int =
        locks.withRunLock(runId) { evidenceIdsByRun[runId]?.size ?: 0 }

    private fun cache(record: RuntimeEvidence) {
        locks.withRunLock(record.runId) {
            if (record.runId in evictedRuns) {
                return@withRunLock
            }
            evidence[record.evidenceId] = record
            val ids = evidenceIdsByRun.getOrPut(record.runId) { ArrayDeque() }
            ids.remove(record.evidenceId)
            ids.addLast(record.evidenceId)
            while (ids.size > retentionPolicy.maxEvidenceEntriesPerRun) {
                evidence.remove(ids.removeFirst())
            }
        }
    }

    private fun evidenceId(runId: String, type: RuntimeEvidenceType): String =
        "evidence-$runId-${type.name.lowercase()}-${UUID.randomUUID()}"
}
