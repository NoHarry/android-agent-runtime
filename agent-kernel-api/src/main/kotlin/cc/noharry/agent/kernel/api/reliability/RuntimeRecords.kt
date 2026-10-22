package cc.noharry.agent.kernel.api.reliability

enum class RuntimeRunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    WAITING_CONFIRMATION,
    WAITING_USER_INPUT,
    WAITING_PROTOCOL_REQUEST,
    BLOCKED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class RuntimeRunSnapshot(
    val runId: String,
    val sessionId: String,
    val status: RuntimeRunStatus,
    val updatedAtEpochMillis: Long,
    val suggestedNextAction: SuggestedNextAction? = null,
    val pendingConfirmationId: String? = null,
)

data class RuntimeRunEnvelope(
    val runId: String,
    val requestId: String,
    val sessionId: String,
    val status: RuntimeRunStatus,
    val phase: RuntimeRunPhase,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

enum class RuntimeRunPhase {
    QUEUED,
    MODEL_RUNNING,
    WAITING_CONFIRMATION,
    WAITING_USER_INPUT,
    WAITING_PROTOCOL_REQUEST,
    TERMINAL,
}

data class RuntimeWorkpad(
    val runId: String,
    val readPaths: List<String> = emptyList(),
    val writeArtifacts: List<String> = emptyList(),
    val attemptedTools: List<String> = emptyList(),
    val toolRegistryWarnings: List<String> = emptyList(),
    val toolRegistryFailures: List<String> = emptyList(),
    val planSummaries: List<String> = emptyList(),
    val blockers: List<RuntimeBlocker> = emptyList(),
    val completionBar: List<CompletionItem> = emptyList(),
    val updatedAtEpochMillis: Long = 0,
)

data class CompletionItem(
    val id: String,
    val label: String,
    val done: Boolean,
)

/** Redacted waiting-state handoff. Public API shape remains candidate until API freeze. */
data class RuntimeHandoff(
    val runId: String,
    val sessionId: String,
    val summary: String,
    val done: List<String> = emptyList(),
    val nextActions: List<String> = emptyList(),
    val evidenceRefs: List<String> = emptyList(),
    val updatedAtEpochMillis: Long = 0,
    val task: String? = null,
    val decisions: List<String> = emptyList(),
    val triedAndAbandoned: List<String> = emptyList(),
    val doNotRepeat: List<String> = emptyList(),
)

enum class RuntimeCompletionOutcome {
    SUCCEEDED,
    PARTIAL,
    BLOCKED,
    FAILED,
    UNKNOWN,
}

/** Bounded, metadata-only completion projection. Missing persisted fields decode as UNKNOWN. */
data class RuntimeCompletionSummary(
    val outcome: RuntimeCompletionOutcome = RuntimeCompletionOutcome.UNKNOWN,
    val requiredTotal: Int = 0,
    val requiredSucceeded: Int = 0,
    val requiredUnresolved: Int = 0,
    val unresolvedCodes: List<String> = emptyList(),
    val projectionDigest: String = "",
) {
    companion object {
        fun unknown(): RuntimeCompletionSummary = RuntimeCompletionSummary()

        fun firstPartyPass(status: RuntimeRunStatus, completion: RuntimeCompletionSummary): Boolean =
            status == RuntimeRunStatus.COMPLETED && completion.outcome == RuntimeCompletionOutcome.SUCCEEDED
    }
}

data class RuntimeResult(
    val runId: String,
    val status: RuntimeRunStatus,
    val response: String,
    val evidenceIds: List<String> = emptyList(),
    val errorCode: String? = null,
    val recoverable: Boolean = true,
    val completion: RuntimeCompletionSummary = RuntimeCompletionSummary.unknown(),
)

data class RuntimeBlocker(
    val runId: String,
    val code: String,
    val message: String,
    val suggestedNextAction: SuggestedNextAction,
    val handoff: RuntimeHandoff? = null,
    val evidenceId: String? = null,
)

data class OpenConfusion(
    val runId: String,
    val sessionId: String,
    val question: String,
    val suggestedNextAction: SuggestedNextAction,
    val createdAtEpochMillis: Long = 0,
)

data class RuntimeProtocolRequest(
    val protocolRequestId: String,
    val runId: String,
    val sessionId: String? = null,
    val status: RuntimeProtocolRequestStatus,
    val kind: String,
    val payloadJson: String,
    val createdAtEpochMillis: Long = 0,
    val expiresAtEpochMillis: Long? = null,
    val resolvedAtEpochMillis: Long? = null,
    val resolutionEvidenceId: String? = null,
)

enum class RuntimeProtocolRequestStatus {
    CREATED,
    WAITING,
    RESOLVED,
    REJECTED,
    CANCELLED,
    EXPIRED,
}

data class RuntimeEvidence(
    val evidenceId: String,
    val runId: String,
    val type: RuntimeEvidenceType,
    val summary: String,
    val createdAtEpochMillis: Long,
    val traceEventName: String? = null,
)

enum class RuntimeEvidenceType {
    PROTOCOL_REQUEST_RESOLUTION,
    RUN_CREATED,
    WORKPAD_UPDATED,
    HANDOFF_WRITTEN,
    BLOCKER_RECORDED,
    RESULT_WRITTEN,
    MCP_DEBUG_CALL,
}

data class RuntimeTraceEvent(
    val runId: String,
    val eventName: String,
    val createdAtEpochMillis: Long,
    val evidenceId: String? = null,
)

enum class SuggestedNextAction {
    ASK_USER,
    RESUME_CONFIRMATION,
    START_NEW_AGENT_REQUEST,
    RESOLVE_PROTOCOL_REQUEST,
    INSPECT_BLOCKER,
}

/** Candidate-only runtime finding. Promotion requires trusted review outside runtime. */
data class RuntimeCandidate(
    val candidateId: String,
    val runId: String,
    val type: RuntimeCandidateType,
    val summary: String,
    val evidenceRefs: List<String> = emptyList(),
    val confidence: String,
    val suggestedDestination: String? = null,
    val createdAtEpochMillis: Long = 0,
)

enum class RuntimeCandidateType {
    MEMORY,
    EVAL,
    POLICY_HARDENING,
    TOOL_SCHEMA_FIX,
    TRACE_REDACTION_RULE,
}
