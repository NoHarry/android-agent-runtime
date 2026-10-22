package cc.noharry.agent.kernel.api

/** Generic effect of a trusted action. Kernel does not interpret business names. */
@ExperimentalAgentRuntimeApi
enum class RuntimeEffectKind {
    READ_ONLY,
    MUTATING,
    CONTROL,
    UNKNOWN,
}

/** Whether a required mutating call must be satisfied before success completion. */
@ExperimentalAgentRuntimeApi
enum class CompletionImpact {
    REQUIRED_ON_CALL,
    ADVISORY,
}

/** Lifecycle of one run-scoped obligation. */
@ExperimentalAgentRuntimeApi
enum class RuntimeActionStatus {
    DECLARED,
    STARTED,
    SUCCEEDED,
    FAILED,
    DENIED,
    BLOCKED,
    CANCELLED,
    UNKNOWN,
}

/** Commit truth after a provider/observer receipt is normalized. */
@ExperimentalAgentRuntimeApi
enum class RuntimeCommitStatus {
    COMMITTED,
    NO_CHANGE_REQUIRED,
    NOT_COMMITTED,
    UNKNOWN,
}

/**
 * Metadata-only outcome for one obligation. Identity is a run-local digest; raw arguments
 * are never stored.
 */
@ExperimentalAgentRuntimeApi
data class RuntimeActionOutcome(
    val obligationId: String,
    val actionName: String,
    val status: RuntimeActionStatus,
    val commitStatus: RuntimeCommitStatus,
    val safeCode: String? = null,
    val retryable: Boolean = false,
)
