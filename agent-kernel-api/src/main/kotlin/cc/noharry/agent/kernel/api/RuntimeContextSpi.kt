package cc.noharry.agent.kernel.api

/**
 * Candidate pre-model context SPI for host-owned context providers.
 *
 * Experimental — not API freeze. Runtime owns typed contribution AST; providers must not inject
 * free-form system instructions or fake user messages.
 */
@ExperimentalAgentRuntimeApi
interface RuntimeContextProvider : AutoCloseable {
    val id: String

    suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution

    override fun close() {}
}

/**
 * Candidate turn observer SPI for pre-model explicit mutations and post-terminal enqueue.
 */
@ExperimentalAgentRuntimeApi
interface RuntimeTurnObserver : AutoCloseable {
    val id: String

    /**
     * Candidate preflight for a required mutating observer action. Runtime declares the
     * obligation before [onTurnInput]. Return null when this turn creates no obligation.
     */
    fun preflightTurnInput(input: RuntimeTurnInput): RuntimeObserverActionPreflight? = null

    suspend fun onTurnInput(input: RuntimeTurnInput): TurnInputObservation

    suspend fun onTurnFinished(turn: RuntimeCompletedTurn): TurnObservationReceipt

    override fun close() {}
}

/** Business-neutral observer action declaration. Kernel does not interpret memory names. */
@ExperimentalAgentRuntimeApi
data class RuntimeObserverActionPreflight(
    val observerId: String,
    val actionName: String,
    val effectKind: RuntimeEffectKind,
    val completionImpact: CompletionImpact,
    val effectKeyDigest: String? = null,
    val argumentDigest: String? = null,
)

@ExperimentalAgentRuntimeApi
data class RuntimeContextRequest(
    val runId: String,
    val sessionId: String,
    val requestId: String,
    val scene: String?,
    val input: String,
    /** Host/runtime-injected reference clock; providers must not use wall clock. */
    val referenceTimeEpochMillis: Long,
    val deadlineEpochMillis: Long?,
    /** Trusted typed host/runtime state keys that override historical provider defaults. */
    val currentStateKeys: Set<String> = emptySet(),
    /**
     * Owning runtime instance id for exact Probe hub routing (runtimeId+runId).
     * Null only for host-local/test calls without Probe.
     */
    val runtimeInstanceId: String? = null,
)

@ExperimentalAgentRuntimeApi
data class RuntimeTurnInput(
    val runId: String,
    val sessionId: String,
    val requestId: String,
    val scene: String?,
    val input: String,
    val referenceTimeEpochMillis: Long,
    /** Runtime-owned observer deadline. Null only for host-local/test calls. */
    val deadlineEpochMillis: Long? = null,
    /** See [RuntimeContextRequest.runtimeInstanceId]. */
    val runtimeInstanceId: String? = null,
)

@ExperimentalAgentRuntimeApi
data class RuntimeCompletedTurn(
    val runId: String,
    val sessionId: String,
    val requestId: String,
    val scene: String?,
    val inputDigest: String,
    val terminalStatus: String,
    val referenceTimeEpochMillis: Long,
    /** See [RuntimeContextRequest.runtimeInstanceId]. */
    val runtimeInstanceId: String? = null,
    val completionOutcome: String? = null,
)

@ExperimentalAgentRuntimeApi
enum class ContextStability {
    STABLE,
    LIVE,
}

@ExperimentalAgentRuntimeApi
enum class RuntimeContextDatumKind {
    FACT,
    EVENT,
    SESSION_STATE,
    UNCERTAINTY,
    WRITE_RECEIPT,
}

@ExperimentalAgentRuntimeApi
sealed interface RuntimeContextScalar {
    @ExperimentalAgentRuntimeApi
    data class Text(val value: String) : RuntimeContextScalar

    @ExperimentalAgentRuntimeApi
    data class Number(val value: Double) : RuntimeContextScalar

    @ExperimentalAgentRuntimeApi
    data class Flag(val value: Boolean) : RuntimeContextScalar
}

@ExperimentalAgentRuntimeApi
data class RuntimeContextDatum(
    val id: String,
    val kind: RuntimeContextDatumKind,
    val subject: String?,
    val predicate: String,
    val value: RuntimeContextScalar,
    val validFromEpochMillis: Long? = null,
    val validToEpochMillis: Long? = null,
)

@ExperimentalAgentRuntimeApi
data class RuntimeContextBlock(
    val id: String,
    val stability: ContextStability,
    val data: List<RuntimeContextDatum>,
    val payloadDigest: String,
)

@ExperimentalAgentRuntimeApi
data class RuntimeContextContribution(
    val providerId: String,
    val blocks: List<RuntimeContextBlock>,
    val fingerprint: String,
) {
    companion object {
        fun empty(providerId: String): RuntimeContextContribution =
            RuntimeContextContribution(providerId, emptyList(), fingerprint = "empty")
    }
}

@ExperimentalAgentRuntimeApi
data class TurnInputObservation(
    val observerId: String,
    val handled: Boolean,
    val writeCommitted: Boolean = false,
    val safeCode: String? = null,
    val digest: String? = null,
    val effectKind: RuntimeEffectKind = RuntimeEffectKind.UNKNOWN,
    val completionImpact: CompletionImpact = CompletionImpact.ADVISORY,
    val commitStatus: RuntimeCommitStatus? = null,
    val effectKeyDigest: String? = null,
) {
    companion object {
        fun noop(observerId: String): TurnInputObservation =
            TurnInputObservation(observerId = observerId, handled = false)
    }
}

@ExperimentalAgentRuntimeApi
data class TurnObservationReceipt(
    val observerId: String,
    val enqueued: Boolean,
    val safeCode: String? = null,
)
