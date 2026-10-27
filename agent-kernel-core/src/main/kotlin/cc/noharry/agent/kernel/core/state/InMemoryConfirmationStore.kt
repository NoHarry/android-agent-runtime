package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.CancelPendingConfirmationRequest
import cc.noharry.agent.kernel.api.CancelPendingResult
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.PendingConfirmation
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class InMemoryConfirmationStore(
    private val nowEpochMillis: () -> Long,
) {
    private val records = ConcurrentHashMap<String, ConfirmationRecord>()
    private val nextSequenceByRunId = ConcurrentHashMap<String, AtomicInteger>()
    private val consumedPendingIds = BoundedStringSet(MAX_CONSUMED_CONFIRMATION_TOMBSTONES)

    fun create(
        runId: String,
        sessionId: String,
        expiresInMillis: Long,
    ): PendingConfirmation {
        val sequence = nextSequenceByRunId.computeIfAbsent(runId) { AtomicInteger() }
            .incrementAndGet()
        val basePendingId = "$runId:confirmation"
        val pendingId = if (sequence == 1) basePendingId else "$basePendingId:$sequence"
        val pending = PendingConfirmation(
            pendingId = pendingId,
            sessionId = sessionId,
            runId = runId,
            expiresAtEpochMillis = nowEpochMillis() + expiresInMillis,
            idempotencyKey = "$pendingId:resume",
        )
        records[pendingId] = ConfirmationRecord(pending)
        return pending
    }

    fun getPending(pendingId: String): PendingConfirmation? {
        val record = records[pendingId] ?: return null
        return synchronized(record) {
            if (record.isExpired() || record.status != ConfirmationStatus.PENDING) {
                null
            } else {
                record.pending
            }
        }
    }

    fun resume(decision: ConfirmationDecision): ConfirmationResume {
        val record = records[decision.pendingId]
            ?: return if (decision.pendingId in consumedPendingIds) {
                ConfirmationResume(ConfirmationResumeResult.AlreadyConsumed)
            } else {
                ConfirmationResume(ConfirmationResumeResult.NotFound)
            }
        return synchronized(record) {
            resumeLocked(record, decision)
        }
    }

    private fun resumeLocked(
        record: ConfirmationRecord,
        decision: ConfirmationDecision,
    ): ConfirmationResume {
        if (record.hasHandled(decision.requestId) || record.status.isTerminal()) {
            return ConfirmationResume(ConfirmationResumeResult.AlreadyConsumed)
        }
        record.handledRequestIds += decision.requestId
        if (record.isExpired()) {
            record.status = ConfirmationStatus.EXPIRED
            return record.expiredResume()
        }
        return when (decision.choice) {
            ConfirmationChoice.APPROVE -> {
                record.status = ConfirmationStatus.APPROVED
                ConfirmationResume(
                    apiResult = ConfirmationResumeResult.Accepted,
                    runId = record.pending.runId,
                    terminalStatus = RuntimeRunStatus.COMPLETED,
                    response = "Confirmation approved.",
                )
            }
            ConfirmationChoice.DENY -> {
                record.status = ConfirmationStatus.DENIED
                ConfirmationResume(
                    apiResult = ConfirmationResumeResult.Rejected("Confirmation denied."),
                    runId = record.pending.runId,
                    terminalStatus = RuntimeRunStatus.CANCELLED,
                    response = "Confirmation denied.",
                )
            }
        }
    }

    fun cancel(request: CancelPendingConfirmationRequest): ConfirmationCancel {
        val record = records[request.pendingId]
            ?: return if (request.pendingId in consumedPendingIds) {
                ConfirmationCancel(CancelPendingResult.AlreadyConsumed)
            } else {
                ConfirmationCancel(CancelPendingResult.NotFound)
            }
        return synchronized(record) {
            cancelLocked(record, request)
        }
    }

    fun clearRun(runId: String) {
        records.entries.forEach { entry ->
            if (entry.value.pending.runId == runId) {
                consumedPendingIds.add(entry.key)
                records.remove(entry.key, entry.value)
            }
        }
        nextSequenceByRunId.remove(runId)
    }

    fun cachedRecordCount(): Int = records.size

    private fun cancelLocked(
        record: ConfirmationRecord,
        request: CancelPendingConfirmationRequest,
    ): ConfirmationCancel {
        if (record.hasHandled(request.requestId) || record.status.isTerminal()) {
            return ConfirmationCancel(CancelPendingResult.AlreadyConsumed)
        }
        record.handledRequestIds += request.requestId
        if (record.isExpired()) {
            record.status = ConfirmationStatus.EXPIRED
            return ConfirmationCancel(
                apiResult = CancelPendingResult.Expired,
                runId = record.pending.runId,
                terminalStatus = RuntimeRunStatus.CANCELLED,
                response = "Confirmation expired.",
            )
        }
        record.status = ConfirmationStatus.CANCELLED
        return ConfirmationCancel(
            apiResult = CancelPendingResult.Cancelled(record.pending),
            runId = record.pending.runId,
            terminalStatus = RuntimeRunStatus.CANCELLED,
            response = request.reason ?: "Confirmation cancelled.",
        )
    }

    private fun ConfirmationRecord.isExpired(): Boolean =
        nowEpochMillis() >= pending.expiresAtEpochMillis
}

private fun ConfirmationRecord.expiredResume(): ConfirmationResume =
    ConfirmationResume(
        apiResult = ConfirmationResumeResult.Expired,
        runId = pending.runId,
        terminalStatus = RuntimeRunStatus.CANCELLED,
        response = "Confirmation expired.",
    )

data class ConfirmationResume(
    val apiResult: ConfirmationResumeResult,
    val runId: String? = null,
    val terminalStatus: RuntimeRunStatus? = null,
    val response: String? = null,
)

data class ConfirmationCancel(
    val apiResult: CancelPendingResult,
    val runId: String? = null,
    val terminalStatus: RuntimeRunStatus? = null,
    val response: String? = null,
)

private data class ConfirmationRecord(
    val pending: PendingConfirmation,
    var status: ConfirmationStatus = ConfirmationStatus.PENDING,
    val handledRequestIds: MutableSet<String> = linkedSetOf(),
) {
    fun hasHandled(requestId: String): Boolean = handledRequestIds.contains(requestId)
}

private enum class ConfirmationStatus {
    PENDING,
    APPROVED,
    DENIED,
    CANCELLED,
    EXPIRED,
}

private fun ConfirmationStatus.isTerminal(): Boolean = this != ConfirmationStatus.PENDING

private const val MAX_CONSUMED_CONFIRMATION_TOMBSTONES = 256
