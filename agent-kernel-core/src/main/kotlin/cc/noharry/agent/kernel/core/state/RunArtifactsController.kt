package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.reliability.CompletionItem
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidenceType
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad
import cc.noharry.agent.kernel.core.loop.NativeLoopSummary
import cc.noharry.agent.kernel.core.tools.MCP_DEBUG_CALL_EVENT
import cc.noharry.agent.kernel.core.tools.McpDebugCallRecord
import cc.noharry.agent.kernel.core.trace.DefaultTraceRedactor
import cc.noharry.agent.kernel.core.trace.TraceRedactor
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class RunArtifactsController(
    private val nowEpochMillis: () -> Long,
    traceRedactor: TraceRedactor = DefaultTraceRedactor,
    private val storage: ReliabilityStorage? = null,
    private val locks: RunStateLockRegistry = RunStateLockRegistry(),
    private val retentionPolicy: RuntimeStateRetentionPolicy = RuntimeStateRetentionPolicy(),
) {
    private val evidenceController = RuntimeEvidenceController(
        nowEpochMillis = nowEpochMillis,
        redactor = traceRedactor,
        storage = storage,
        locks = locks,
        retentionPolicy = retentionPolicy,
    )
    private val results = ConcurrentHashMap<String, RuntimeResult>()
    private val handoffs = ConcurrentHashMap<String, RuntimeHandoff>()
    private val workpads = ConcurrentHashMap<String, RuntimeWorkpad>()
    private val traceEventsByRun = ConcurrentHashMap<String, ArrayDeque<RuntimeTraceEvent>>()
    private val evictedRuns = BoundedStringSet(retentionPolicy.maxTerminalRuns)

    fun createWorkpad(runId: String, now: Long) {
        evictedRuns.remove(runId)
        evidenceController.activateRun(runId)
        val workpad = RuntimeWorkpad(
            runId = runId,
            completionBar = listOf(
                CompletionItem(
                    id = "terminal-result",
                    label = "Terminal result written before final event",
                    done = false,
                ),
            ),
            updatedAtEpochMillis = now,
        )
        locks.withRunLock(runId) { workpads[runId] = workpad }
        persistLatestWorkpad(runId, workpad)
    }

    fun workpad(runId: String): RuntimeWorkpad? =
        workpads[runId] ?: storage?.readWorkpad(runId)

    fun result(runId: String): RuntimeResult? =
        results[runId] ?: storage?.readResult(runId)

    fun evidence(evidenceId: String): RuntimeEvidence? = evidenceController.evidence(evidenceId)

    fun handoff(runId: String): RuntimeHandoff? =
        handoffs[runId] ?: storage?.readHandoff(runId)

    fun traceEvents(runId: String): List<RuntimeTraceEvent> {
        val current = locks.withRunLock(runId) { traceEventsByRun[runId].orEmpty().toList() }
        return storage?.readTrace(runId)?.ifEmpty { current } ?: current
    }

    fun recordRunCreated(runId: String) {
        recordEvidence(
            runId = runId,
            type = RuntimeEvidenceType.RUN_CREATED,
            summary = "Run $runId was created.",
            traceEventName = "RUN_CREATED",
        )
    }

    fun recordStatusChanged(runId: String) {
        recordTrace(runId, "RUN_STATUS_CHANGED")
    }

    fun recordNativeLoopSummary(runId: String, summary: NativeLoopSummary) {
        if (summary.isEmpty()) return
        updateWorkpad(runId) { workpad ->
            workpad.copy(
                readPaths = (workpad.readPaths + summary.readPaths).distinct(),
                writeArtifacts = workpad.writeArtifacts + summary.writeArtifacts,
                attemptedTools = workpad.attemptedTools + summary.attemptedTools,
                toolRegistryWarnings = (workpad.toolRegistryWarnings + summary.toolRegistryWarnings).distinct(),
                toolRegistryFailures = (workpad.toolRegistryFailures + summary.toolRegistryFailures).distinct(),
                planSummaries = workpad.planSummaries + summary.planSummaries,
            )
        }
        recordLoopEvidence(runId, summary)
        recordMemoryEvidence(runId, summary)
        recordBackendFailureEvidence(runId, summary)
    }

    private fun recordLoopEvidence(runId: String, summary: NativeLoopSummary) {
        summary.writeArtifacts.forEach { artifact ->
            recordEvidence(runId, RuntimeEvidenceType.WORKPAD_UPDATED, artifact, "WORKSPACE_WRITE_RECORDED")
        }
        summary.toolRegistryWarnings.forEach { warning ->
            recordEvidence(runId, RuntimeEvidenceType.WORKPAD_UPDATED, warning, "TOOL_REGISTRY_WARNING")
        }
        summary.toolRegistryFailures.forEach { failure ->
            recordEvidence(runId, RuntimeEvidenceType.WORKPAD_UPDATED, failure, "TOOL_REGISTRY_FAILURE")
        }
        if (summary.modelRoundCount > 0) {
            recordEvidence(
                runId,
                RuntimeEvidenceType.WORKPAD_UPDATED,
                "Model rounds: ${summary.modelRoundCount}.",
                "MODEL_ROUND_SUMMARY",
            )
        }
    }

    private fun recordMemoryEvidence(runId: String, summary: NativeLoopSummary) {
        if (summary.memoryRetrievalEvaluated && summary.retrievedMemoryMatches.isEmpty()) {
            recordEvidence(
                runId,
                RuntimeEvidenceType.WORKPAD_UPDATED,
                "Runtime memory retrieval found no relevant prewarm-eligible lesson.",
                "MEMORY_RETRIEVAL_EMPTY",
            )
        }
        summary.retrievedMemoryMatches.forEach { match ->
            val domains = match.matchedDomains.joinToString(",").ifBlank { "none" }
            recordEvidence(
                runId,
                RuntimeEvidenceType.WORKPAD_UPDATED,
                "Runtime memory lesson ${match.lessonId} matched with score ${match.relevanceScore}; domains=$domains.",
                "MEMORY_LESSON_RETRIEVED",
            )
        }
        summary.memoryHintTools.forEach { toolName ->
            recordEvidence(
                runId,
                RuntimeEvidenceType.WORKPAD_UPDATED,
                "Runtime memory preactivated current-catalog tool $toolName.",
                "MEMORY_TOOL_PREACTIVATED",
            )
        }
    }

    private fun recordBackendFailureEvidence(runId: String, summary: NativeLoopSummary) {
        summary.backendToolFailures.forEach { failure ->
            val code = failure.code ?: "UNKNOWN"
            recordEvidence(
                runId,
                RuntimeEvidenceType.WORKPAD_UPDATED,
                "Backend tool ${failure.toolName} failed with code $code.",
                "BACKEND_TOOL_FAILURE",
            )
        }
    }

    fun recordHandoff(
        runId: String,
        sessionId: String,
        summary: String,
        done: List<String>,
        nextActions: List<String>,
        task: String? = null,
        decisions: List<String> = emptyList(),
        triedAndAbandoned: List<String> = emptyList(),
        doNotRepeat: List<String> = emptyList(),
    ): RuntimeHandoff {
        val evidenceId = recordEvidence(
            runId = runId,
            type = RuntimeEvidenceType.HANDOFF_WRITTEN,
            summary = (listOf(summary) + nextActions).joinToString(" "),
            traceEventName = "HANDOFF_WRITTEN",
        )
        val handoff = RuntimeHandoff(
            runId = runId,
            sessionId = sessionId,
            summary = summary,
            done = done,
            nextActions = nextActions,
            evidenceRefs = listOf(evidenceId),
            updatedAtEpochMillis = nowEpochMillis(),
            task = task,
            decisions = decisions,
            triedAndAbandoned = triedAndAbandoned,
            doNotRepeat = doNotRepeat,
        )
        locks.withRunLock(runId) {
            if (runId !in evictedRuns) {
                handoffs[runId] = handoff
            }
        }
        persistLatestHandoff(runId, handoff)
        updateWorkpad(runId) { it }
        return handoff
    }

    fun recordBlocker(blocker: RuntimeBlocker): RuntimeBlocker {
        val evidenceId = recordEvidence(
            runId = blocker.runId,
            type = RuntimeEvidenceType.BLOCKER_RECORDED,
            summary = "${blocker.code}: ${blocker.message}",
            traceEventName = "BLOCKER_RECORDED",
        )
        val recorded = blocker.copy(evidenceId = evidenceId)
        updateWorkpad(blocker.runId) { workpad ->
            workpad.copy(blockers = workpad.blockers + recorded)
        }
        return recorded
    }

    fun recordTerminalResult(result: RuntimeResult): RuntimeResult =
        locks.withRunLock(result.runId) {
            val existing = results[result.runId] ?: storage?.readResult(result.runId)
            if (result.runId in evictedRuns && existing == null) {
                return@withRunLock result
            }
            val recorded = existing ?: result.withResultEvidence()
            val currentWorkpad = workpads[result.runId] ?: storage?.readWorkpad(result.runId)
            val completionPending = currentWorkpad?.terminalCompletionPending() == true
            val completedWorkpad = currentWorkpad?.let { workpad ->
                if (completionPending) workpad.terminalCompleted(nowEpochMillis()) else workpad
            }
            if (result.runId !in evictedRuns) {
                results[result.runId] = recorded
                completedWorkpad?.let { workpads[result.runId] = it }
            }
            persistLatestResult(result.runId, recorded)
            completedWorkpad?.let { persistLatestWorkpad(result.runId, it) }
            if (completionPending) {
                recordEvidence(
                    runId = result.runId,
                    type = RuntimeEvidenceType.WORKPAD_UPDATED,
                    summary = "RuntimeWorkpad was updated.",
                    traceEventName = "WORKPAD_UPDATED",
                )
            }
            recorded
        }

    fun recordMcpDebugCall(record: McpDebugCallRecord) {
        recordEvidence(
            runId = record.runId,
            type = RuntimeEvidenceType.MCP_DEBUG_CALL,
            summary = record.toEvidenceSummary(),
            traceEventName = MCP_DEBUG_CALL_EVENT,
        )
    }

    fun evictRun(runId: String) {
        locks.withRunLock(runId) {
            evictedRuns.add(runId)
            results.remove(runId)
            handoffs.remove(runId)
            workpads.remove(runId)
            traceEventsByRun.remove(runId)
        }
        evidenceController.clearRun(runId)
    }

    fun cachedTraceCount(runId: String): Int =
        locks.withRunLock(runId) { traceEventsByRun[runId]?.size ?: 0 }

    fun cachedEvidenceCount(runId: String): Int =
        evidenceController.cachedEvidenceCount(runId)

    fun cachedResultCount(): Int = results.size

    fun cachedWorkpadCount(): Int = workpads.size

    private fun updateWorkpad(
        runId: String,
        update: (RuntimeWorkpad) -> RuntimeWorkpad,
    ) {
        if (runId in evictedRuns) {
            return
        }
        val loaded = workpads[runId] ?: storage?.readWorkpad(runId)
        val updated = locks.withRunLock(runId) {
            if (runId in evictedRuns) {
                return@withRunLock null
            }
            val current = workpads[runId] ?: loaded ?: return@withRunLock null
            update(current).trimmed().copy(updatedAtEpochMillis = nowEpochMillis())
                .also { workpads[runId] = it }
        } ?: return
        persistLatestWorkpad(runId, updated)
        recordEvidence(
            runId = runId,
            type = RuntimeEvidenceType.WORKPAD_UPDATED,
            summary = "RuntimeWorkpad was updated.",
            traceEventName = "WORKPAD_UPDATED",
        )
    }

    private fun recordEvidence(
        runId: String,
        type: RuntimeEvidenceType,
        summary: String,
        traceEventName: String,
    ): String {
        val evidenceId = evidenceController.record(runId, type, summary, traceEventName)
        recordTrace(runId, traceEventName, evidenceId)
        return evidenceId
    }

    private fun RuntimeResult.withResultEvidence(): RuntimeResult {
        val evidenceId = recordEvidence(
            runId = runId,
            type = RuntimeEvidenceType.RESULT_WRITTEN,
            summary = "RuntimeResult $status: $response",
            traceEventName = "RESULT_WRITTEN",
        )
        return copy(evidenceIds = (evidenceIds + evidenceId).distinct())
    }

    private fun recordTrace(runId: String, name: String, evidenceId: String? = null) {
        val event = RuntimeTraceEvent(
            runId = runId,
            eventName = name,
            createdAtEpochMillis = nowEpochMillis(),
            evidenceId = evidenceId,
        )
        locks.withRunLock(runId) {
            if (runId in evictedRuns) {
                return@withRunLock
            }
            val events = traceEventsByRun.getOrPut(runId) { ArrayDeque() }
            events.addLast(event)
            while (events.size > retentionPolicy.maxTraceEntriesPerRun) {
                events.removeFirst()
            }
        }
        storage?.appendTrace(event)
    }

    private fun persistLatestWorkpad(runId: String, initial: RuntimeWorkpad) {
        val targetStorage = storage ?: return
        var candidate = initial
        while (true) {
            targetStorage.saveWorkpad(candidate)
            val latest = workpads[runId] ?: return
            if (latest === candidate) return
            candidate = latest
        }
    }

    private fun persistLatestResult(runId: String, initial: RuntimeResult) {
        val targetStorage = storage ?: return
        var candidate = initial
        while (true) {
            targetStorage.saveResult(candidate)
            val latest = results[runId] ?: return
            if (latest === candidate) return
            candidate = latest
        }
    }

    private fun persistLatestHandoff(runId: String, initial: RuntimeHandoff) {
        val targetStorage = storage ?: return
        var candidate = initial
        while (true) {
            targetStorage.saveHandoff(candidate)
            val latest = handoffs[runId] ?: return
            if (latest === candidate) return
            candidate = latest
        }
    }
}

private fun NativeLoopSummary.isEmpty(): Boolean =
    readPaths.isEmpty() &&
        writeArtifacts.isEmpty() &&
        attemptedTools.isEmpty() &&
        modelRoundCount == 0 &&
        successfulBackendTools.isEmpty() &&
        backendToolFailures.isEmpty() &&
        toolRegistryWarnings.isEmpty() &&
        toolRegistryFailures.isEmpty() &&
        planSummaries.isEmpty() &&
        !memoryRetrievalEvaluated &&
        memoryHintTools.isEmpty()

private fun RuntimeWorkpad.terminalCompleted(nowEpochMillis: Long): RuntimeWorkpad =
    copy(
        completionBar = completionBar.map {
            if (it.id == "terminal-result") it.copy(done = true) else it
        },
        updatedAtEpochMillis = nowEpochMillis,
    )

private fun RuntimeWorkpad.terminalCompletionPending(): Boolean =
    completionBar.any { it.id == "terminal-result" && !it.done }

private fun McpDebugCallRecord.toEvidenceSummary(): String =
    buildJsonObject {
        put("toolName", toolName)
        put("callJson", callJson)
        putJsonObject("validation") {
            put("ok", validationOk)
            validationCode?.let { put("code", it) }
            validationMessage?.let { put("message", it) }
            validationSchemaPath?.let { put("schemaPath", it) }
        }
    }.toString()

private fun RuntimeWorkpad.trimmed(): RuntimeWorkpad =
    copy(
        readPaths = readPaths.takeLast(MAX_WORKPAD_READ_PATHS).map(::trimWorkpadText),
        writeArtifacts = writeArtifacts.takeLast(MAX_WORKPAD_WRITE_ARTIFACTS).map(::trimWorkpadText),
        attemptedTools = attemptedTools.takeLast(MAX_WORKPAD_ATTEMPTED_TOOLS).map(::trimWorkpadText),
        toolRegistryWarnings = toolRegistryWarnings.takeLast(MAX_WORKPAD_TOOL_REGISTRY_ITEMS).map(::trimWorkpadText),
        toolRegistryFailures = toolRegistryFailures.takeLast(MAX_WORKPAD_TOOL_REGISTRY_ITEMS).map(::trimWorkpadText),
        planSummaries = planSummaries.takeLast(MAX_WORKPAD_PLAN_SUMMARIES).map(::trimWorkpadText),
        blockers = blockers.takeLast(MAX_WORKPAD_BLOCKERS).map(::trimWorkpadBlocker),
    )

private fun trimWorkpadBlocker(blocker: RuntimeBlocker): RuntimeBlocker =
    blocker.copy(
        message = trimWorkpadText(blocker.message),
        handoff = blocker.handoff?.trimmedForWorkpad(),
    )

private fun RuntimeHandoff.trimmedForWorkpad(): RuntimeHandoff =
    copy(
        summary = trimWorkpadText(summary),
        done = done.takeLast(MAX_WORKPAD_HANDOFF_ITEMS).map(::trimWorkpadText),
        nextActions = nextActions.takeLast(MAX_WORKPAD_HANDOFF_ITEMS).map(::trimWorkpadText),
        evidenceRefs = evidenceRefs.takeLast(MAX_WORKPAD_EVIDENCE_REFS).map(::trimWorkpadText),
    )

private fun trimWorkpadText(value: String): String {
    if (value.length <= MAX_WORKPAD_TEXT_CHARS) {
        return value
    }
    return value.take(MAX_WORKPAD_TEXT_CHARS - WORKPAD_TRUNCATION_SUFFIX.length) +
        WORKPAD_TRUNCATION_SUFFIX
}

const val MAX_WORKPAD_READ_PATHS = 64
const val MAX_WORKPAD_WRITE_ARTIFACTS = 64
const val MAX_WORKPAD_ATTEMPTED_TOOLS = 128
const val MAX_WORKPAD_TOOL_REGISTRY_ITEMS = 64
const val MAX_WORKPAD_PLAN_SUMMARIES = 64
const val MAX_WORKPAD_BLOCKERS = 32
const val MAX_WORKPAD_HANDOFF_ITEMS = 16
const val MAX_WORKPAD_EVIDENCE_REFS = 32
const val MAX_WORKPAD_TEXT_CHARS = 256
const val WORKPAD_TRUNCATION_SUFFIX = "...[truncated]"
