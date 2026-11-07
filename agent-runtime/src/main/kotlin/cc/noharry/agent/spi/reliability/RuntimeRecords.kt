@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.spi.reliability

import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi

internal data class RuntimeRunEnvelope(
    val runId: String,
    val requestId: String,
    val sessionId: String,
    val status: RuntimeRunStatus,
    val phase: RuntimeRunPhase,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

internal enum class RuntimeRunPhase {
    QUEUED,
    MODEL_RUNNING,
    WAITING_CONFIRMATION,
    WAITING_USER_INPUT,
    TERMINAL,
}

internal data class RuntimeWorkpad(
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

internal data class CompletionItem(
    val id: String,
    val label: String,
    val done: Boolean,
)

internal data class RuntimeBlocker(
    val runId: String,
    val code: String,
    val message: String,
    val suggestedNextAction: SuggestedNextAction,
    val handoff: RuntimeHandoff? = null,
    val evidenceId: String? = null,
)

internal data class OpenConfusion(
    val runId: String,
    val sessionId: String,
    val question: String,
    val suggestedNextAction: SuggestedNextAction,
    val createdAtEpochMillis: Long = 0,
)

/** Candidate-only runtime finding. Promotion requires trusted review outside runtime. */
internal data class RuntimeCandidate(
    val candidateId: String,
    val runId: String,
    val type: RuntimeCandidateType,
    val summary: String,
    val evidenceRefs: List<String> = emptyList(),
    val confidence: String,
    val suggestedDestination: String? = null,
    val createdAtEpochMillis: Long = 0,
)

internal enum class RuntimeCandidateType {
    MEMORY,
    EVAL,
    POLICY_HARDENING,
    TOOL_SCHEMA_FIX,
    TRACE_REDACTION_RULE,
}
