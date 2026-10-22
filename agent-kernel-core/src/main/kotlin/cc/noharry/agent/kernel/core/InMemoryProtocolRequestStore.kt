package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import java.util.concurrent.ConcurrentHashMap

class InMemoryProtocolRequestStore(
    private val nowEpochMillis: () -> Long,
    private val storage: ReliabilityStorage? = null,
    private val locks: RunStateLockRegistry = RunStateLockRegistry(),
    private val retentionPolicy: RuntimeStateRetentionPolicy = RuntimeStateRetentionPolicy(),
) {
    private val protocolRequests = ConcurrentHashMap<String, RuntimeProtocolRequest>()
    private val resolutionRequests =
        ConcurrentHashMap<String, ConcurrentHashMap<String, RuntimeProtocolRequest>>()
    private val evidenceRecords = ConcurrentHashMap<String, RuntimeEvidence>()
    private val traceEvents = ConcurrentHashMap<String, ArrayDeque<RuntimeTraceEvent>>()
    private val terminalRunOrder = ArrayDeque<String>()

    fun create(
        runId: String,
        sessionId: String,
        kind: String,
        payloadJson: String,
        expiresInMillis: Long?,
    ): RuntimeProtocolRequest {
        synchronized(terminalRunOrder) { terminalRunOrder.remove(runId) }
        val now = nowEpochMillis()
        val request = RuntimeProtocolRequest(
            protocolRequestId = "$runId:protocol-request",
            runId = runId,
            sessionId = sessionId,
            status = RuntimeProtocolRequestStatus.WAITING,
            kind = kind,
            payloadJson = payloadJson,
            createdAtEpochMillis = now,
            expiresAtEpochMillis = expiresInMillis?.let { now + it },
        )
        protocolRequests[request.protocolRequestId] = request
        storage?.saveProtocolRequest(request)
        return request
    }

    fun getPending(
        sessionId: String?,
        runId: String?,
    ): List<RuntimeProtocolRequest> =
        (protocolRequests.values + storage?.readPendingProtocolRequests(sessionId, runId).orEmpty())
            .distinctBy { it.protocolRequestId }
            .filter { request ->
                request.status.isPending() &&
                    (runId == null || request.runId == runId) &&
                    (sessionId == null || request.sessionId == sessionId)
            }

    fun evidence(evidenceId: String): RuntimeEvidence? =
        evidenceRecords[evidenceId] ?: storage?.readEvidence(evidenceId)

    fun traceEvents(runId: String): List<RuntimeTraceEvent> {
        val current = locks.withRunLock(runId) { traceEvents[runId].orEmpty().toList() }
        val stored = storage?.readTrace(runId)
            ?.filter { it.eventName == PROTOCOL_REQUEST_RESOLVED }
            .orEmpty()
        return stored.ifEmpty { current }
    }

    fun transition(
        protocolRequestId: String,
        requestId: String,
        targetStatus: RuntimeProtocolRequestStatus,
    ): ProtocolRequestCloseLoopResult {
        val loadedCurrent = protocolRequests[protocolRequestId]
            ?: storage?.readProtocolRequest(protocolRequestId)
            ?: return ProtocolRequestCloseLoopResult.NotFound
        val loadedApplied = resolutionRequests[protocolRequestId]?.get(requestId)
            ?: storage?.readProtocolResolution(protocolRequestId, requestId)
        var updated: RuntimeProtocolRequest? = null
        val result = locks.withRunLock(loadedCurrent.runId) {
            val current = protocolRequests[protocolRequestId] ?: loadedCurrent
            val applied = resolutionRequests[protocolRequestId]?.get(requestId) ?: loadedApplied
            if (applied != null) {
                ProtocolRequestCloseLoopResult.AlreadyApplied(applied)
            } else {
                transitionPending(current, requestId, targetStatus).also { transition ->
                    transition.protocolRequestOrNull()
                        ?.takeIf { transition is ProtocolRequestCloseLoopResult.Updated || transition is ProtocolRequestCloseLoopResult.Expired }
                        ?.let { appliedRequest ->
                            updated = appliedRequest
                            protocolRequests[protocolRequestId] = appliedRequest
                            resolutionRequests.computeIfAbsent(protocolRequestId) { ConcurrentHashMap() }[requestId] = appliedRequest
                        }
                }
            }
        }
        updated?.let { persistResolution(protocolRequestId, requestId, it) }
        return result
    }

    fun retainTerminalRun(runId: String) {
        terminalVictims(runId).forEach(::evictCachedRun)
    }

    fun cachedRequestCount(): Int = protocolRequests.size

    private fun terminalVictims(runId: String): List<String> = synchronized(terminalRunOrder) {
        terminalRunOrder.remove(runId)
        terminalRunOrder.addLast(runId)
        buildList {
            while (terminalRunOrder.size > retentionPolicy.maxTerminalRuns) {
                add(terminalRunOrder.removeFirst())
            }
        }
    }

    private fun evictCachedRun(runId: String) {
        protocolRequests.entries.forEach { entry ->
            if (entry.value.runId == runId) {
                protocolRequests.remove(entry.key, entry.value)
                resolutionRequests.remove(entry.key)
            }
        }
        evidenceRecords.entries.forEach { entry ->
            if (entry.value.runId == runId) {
                evidenceRecords.remove(entry.key, entry.value)
            }
        }
        locks.withRunLock(runId) { traceEvents.remove(runId) }
    }

    private fun transitionPending(
        current: RuntimeProtocolRequest,
        requestId: String,
        targetStatus: RuntimeProtocolRequestStatus,
    ): ProtocolRequestCloseLoopResult {
        if (current.status.isTerminal()) {
            return ProtocolRequestCloseLoopResult.AlreadyTerminal(current)
        }
        if (!current.status.isPending()) {
            return ProtocolRequestCloseLoopResult.InvalidState(current.status)
        }
        val now = nowEpochMillis()
        val finalStatus = if (current.isExpiredAt(now)) RuntimeProtocolRequestStatus.EXPIRED else targetStatus
        val updated = current.copy(
            status = finalStatus,
            resolvedAtEpochMillis = now,
            resolutionEvidenceId = evidenceId(current.protocolRequestId, requestId),
        )
        return if (finalStatus == RuntimeProtocolRequestStatus.EXPIRED) {
            ProtocolRequestCloseLoopResult.Expired(updated)
        } else {
            ProtocolRequestCloseLoopResult.Updated(updated)
        }
    }

    private fun persistResolution(
        protocolRequestId: String,
        requestId: String,
        updated: RuntimeProtocolRequest,
    ) {
        storage?.saveProtocolRequest(updated)
        storage?.saveProtocolResolution(protocolRequestId, requestId, updated)
        recordResolution(updated)
    }

    private fun recordResolution(request: RuntimeProtocolRequest) {
        val evidenceId = request.resolutionEvidenceId ?: return
        val now = request.resolvedAtEpochMillis ?: nowEpochMillis()
        val evidence = RuntimeEvidence(
            evidenceId = evidenceId,
            runId = request.runId,
            type = RuntimeEvidenceType.PROTOCOL_REQUEST_RESOLUTION,
            summary = "ProtocolRequest ${request.protocolRequestId} moved to ${request.status}.",
            createdAtEpochMillis = now,
            traceEventName = PROTOCOL_REQUEST_RESOLVED,
        )
        evidenceRecords[evidenceId] = evidence
        storage?.saveEvidence(evidence)
        val trace = RuntimeTraceEvent(
            runId = request.runId,
            eventName = PROTOCOL_REQUEST_RESOLVED,
            createdAtEpochMillis = now,
            evidenceId = evidenceId,
        )
        locks.withRunLock(request.runId) {
            val events = traceEvents.getOrPut(request.runId) { ArrayDeque() }
            events.addLast(trace)
            while (events.size > retentionPolicy.maxTraceEntriesPerRun) {
                events.removeFirst()
            }
        }
        storage?.appendTrace(trace)
    }
}

fun ProtocolRequestCloseLoopResult.protocolRequestOrNull(): RuntimeProtocolRequest? =
    when (this) {
        is ProtocolRequestCloseLoopResult.Updated -> protocolRequest
        is ProtocolRequestCloseLoopResult.AlreadyApplied -> protocolRequest
        is ProtocolRequestCloseLoopResult.AlreadyTerminal -> protocolRequest
        is ProtocolRequestCloseLoopResult.Expired -> protocolRequest
        is ProtocolRequestCloseLoopResult.InvalidState -> null
        ProtocolRequestCloseLoopResult.NotFound -> null
    }

private fun RuntimeProtocolRequest.isExpiredAt(now: Long): Boolean =
    expiresAtEpochMillis?.let { it <= now } == true

private fun RuntimeProtocolRequestStatus.isPending(): Boolean =
    this == RuntimeProtocolRequestStatus.CREATED || this == RuntimeProtocolRequestStatus.WAITING

private fun RuntimeProtocolRequestStatus.isTerminal(): Boolean =
    this == RuntimeProtocolRequestStatus.RESOLVED ||
        this == RuntimeProtocolRequestStatus.REJECTED ||
        this == RuntimeProtocolRequestStatus.CANCELLED ||
        this == RuntimeProtocolRequestStatus.EXPIRED

private fun evidenceId(protocolRequestId: String, requestId: String): String =
    "evidence:${protocolRequestId}:${requestId}"

private const val PROTOCOL_REQUEST_RESOLVED = "PROTOCOL_REQUEST_RESOLVED"
