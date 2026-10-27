@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.api

import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeEvidence
import cc.noharry.agent.kernel.api.reliability.OpenConfusion
import cc.noharry.agent.kernel.api.reliability.RuntimeHandoff
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeTraceEvent
import kotlinx.coroutines.flow.Flow

interface AgentRuntime {
    /** Starts a runtime run. Public API is scaffold until API freeze closes. */
    fun run(request: AgentRequest): Flow<AgentEvent>

    /** Resumes a pending user confirmation. */
    suspend fun resumeConfirmation(decision: ConfirmationDecision): ConfirmationResumeResult

    /** Returns a pending user confirmation, or null when missing, consumed, or expired. */
    suspend fun getPendingConfirmation(pendingId: String): PendingConfirmation?

    /** Candidate pending confirmation cancellation API shape. */
    suspend fun cancelPendingConfirmation(
        request: CancelPendingConfirmationRequest,
    ): CancelPendingResult

    /** Returns a redacted run snapshot, or null when the run is unknown. */
    suspend fun getRunState(runId: String): RuntimeRunSnapshot?

    /** Returns a terminal runtime result, or null when the run is unknown or not terminal. */
    suspend fun getRuntimeResult(runId: String): RuntimeResult?

    /** Candidate redacted handoff query API shape for waiting runs. */
    suspend fun getRuntimeHandoff(runId: String): RuntimeHandoff?

    /** Candidate open-confusion query API shape for WAITING_USER_INPUT runs. */
    suspend fun getOpenConfusion(runId: String): OpenConfusion?

    /**
     * Candidate waiting-user-input resume API shape.
     *
     * Runtime reliability storage keeps only loop history and safe run metadata.
     * Hosts must re-supply current state and workspace selection when resuming.
     */
    suspend fun resumeUserInput(request: AgentRequest): RuntimeResult?

    /** Candidate in-flight steering API shape for active RUNNING runs. */
    suspend fun postUserInput(request: PostUserInputRequest): PostUserInputResult

    /**
     * Candidate ProtocolRequest close-loop API shape.
     *
     * Method names and DTOs are not frozen until the API review closes.
     */
    suspend fun resolveRuntimeProtocolRequest(
        decision: ProtocolRequestResolution,
    ): ProtocolRequestCloseLoopResult

    /**
     * Candidate ProtocolRequest rejection API shape.
     *
     * This rejects a host/control-plane request only; it does not replace user confirmation.
     */
    suspend fun rejectRuntimeProtocolRequest(
        decision: ProtocolRequestRejection,
    ): ProtocolRequestCloseLoopResult

    /** Candidate ProtocolRequest cancellation API shape. */
    suspend fun cancelRuntimeProtocolRequest(
        decision: ProtocolRequestCancellation,
    ): ProtocolRequestCloseLoopResult

    /** Candidate pending ProtocolRequest query API shape. */
    suspend fun getPendingProtocolRequests(
        sessionId: String? = null,
        runId: String? = null,
    ): List<RuntimeProtocolRequest>

    /** Candidate redacted evidence query API shape. */
    suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence?

    /** Candidate redacted trace query API shape. */
    suspend fun getRuntimeTraceEvents(runId: String): List<RuntimeTraceEvent>

    /** Queries the append-only, privacy-safe event authority for offline analysis. */
    @ExperimentalAgentRuntimeApi
    suspend fun querySessionEvents(query: SessionEventQuery): List<SessionEvent> = emptyList()

    /** Replays one run stream in its canonical append order. */
    @ExperimentalAgentRuntimeApi
    suspend fun replaySessionEvents(sessionId: String, runId: String): List<SessionEvent> = emptyList()

    /**
     * Candidate EventLog health for one `(sessionId, runId)` stream.
     * Missing, custom, or legacy backends that do not report health return UNKNOWN.
     */
    @ExperimentalAgentRuntimeApi
    suspend fun getSessionEventLogHealth(sessionId: String, runId: String): SessionEventLogHealth =
        SessionEventLogHealth.unknown()

    /**
     * Subscribes to accepted canonical events for one session after the writer allocated
     * `eventId`/`sequence`. Default backends that do not publish a live tail return a no-op.
     */
    @ExperimentalAgentRuntimeApi
    fun subscribeSessionEvents(
        sessionId: String,
        listener: (SessionEvent) -> Unit,
    ): AutoCloseable = AutoCloseable { }

    /**
     * Closes this runtime.
     *
     * Active [run] flows are cancelled. That cancellation is not required to emit a
     * terminal [AgentEvent] (`ResultAvailable`, `Finished`, or `Failed`); a collector
     * may complete without a terminal event. The authoritative terminal outcome is
     * [getRuntimeResult], which records
     * [cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode.RUNTIME_CLOSED]
     * for runs cancelled by close.
     *
     * Candidate lifecycle close also performs reverse-registration close of context
     * providers and turn observers. Idempotent; never throws to hosts.
     */
    @ExperimentalAgentRuntimeApi
    fun close() {}
}

/**
 * Candidate same-session run conflict policy.
 *
 * Consulted only when a same-session owner is WAITING_CONTROL. A null
 * [AgentRequest.sessionRunConflictPolicy] requires an explicit host choice then.
 */
enum class SessionRunConflictPolicy {
    RESUME_CURRENT,
    QUEUE_NEW,
    CANCEL_AND_REPLACE,
}

/**
 * Candidate run request; [currentStateKeys] is trusted typed host state, never prompt-derived.
 *
 * Resume rebuilds copy trusted [currentStateKeys] and [workspaceId] from the host request.
 * Those fields are not written to the Runtime reliability store.
 *
 * [sessionRunConflictPolicy] is consulted only when a same-session owner is WAITING_CONTROL.
 * Null requires an explicit host choice then. The field is appended last so existing
 * positional constructors remain coherent.
 */
data class AgentRequest(
    val requestId: String,
    val sessionId: String,
    val input: String,
    val scene: String? = null,
    val resumeRunId: String? = null,
    val modelRef: ModelRef? = null,
    val workspaceId: String? = null,
    val currentStateKeys: Set<String> = emptySet(),
    val sessionRunConflictPolicy: SessionRunConflictPolicy? = null,
) {
    /** Safe Java constructor used by host smoke and typical three-field callers. */
    constructor(requestId: String, sessionId: String, input: String) : this(
        requestId = requestId,
        sessionId = sessionId,
        input = input,
        scene = null,
    )

    /** Safe Java constructor used by [scene]-aware host callers. */
    constructor(requestId: String, sessionId: String, input: String, scene: String?) : this(
        requestId = requestId,
        sessionId = sessionId,
        input = input,
        scene = scene,
        resumeRunId = null,
    )
}

sealed interface AgentEvent {
    data class Started(val runId: String) : AgentEvent

    /** Candidate live projection of the canonical question/input fact, never a second content ledger. */
    data class UserInteraction(val runId: String, val interaction: SessionEventPayload.UserInteraction) : AgentEvent

    /** Candidate model thinking/status delta. Not frozen until API review closes. */
    data class ThinkingDelta(
        val runId: String,
        val text: String,
    ) : AgentEvent

    /** Candidate model text delta. Not frozen until API review closes. */
    data class TextDelta(
        val runId: String,
        val text: String,
    ) : AgentEvent

    /** Candidate provider token usage delta. Not frozen until API review closes. */
    data class TokenUsageDelta(
        val runId: String,
        val inputTokens: Int = 0,
        val outputTokens: Int = 0,
        val reasoningTokens: Int = 0,
        val cacheReadTokens: Int = 0,
        val cacheWriteTokens: Int = 0,
    ) : AgentEvent

    data class ConfirmationRequired(val pending: PendingConfirmation) : AgentEvent

    data class RuntimeBlocked(val blocker: RuntimeBlocker) : AgentEvent

    data class RuntimeProtocolRequestCreated(
        val protocolRequest: RuntimeProtocolRequest,
    ) : AgentEvent

    /** Candidate model retry event. Not frozen until API review closes. */
    data class RetryAttempted(
        val runId: String,
        val attempt: Int,
        val delayMillis: Long,
        val modelRef: ModelRef? = null,
        val fallbackModelRef: ModelRef? = null,
    ) : AgentEvent

    /** Candidate loop budget warning event. Not frozen until API review closes. */
    data class BudgetWarning(
        val runId: String,
        val roundsRemaining: Int,
        val toolCallsRemaining: Int,
    ) : AgentEvent

    /** Candidate loop history compaction event. Not frozen until API review closes. */
    data class CompactionApplied(
        val runId: String,
        val beforeMessageCount: Int,
        val afterMessageCount: Int,
        val droppedCount: Int,
        val summaryBytes: Int,
    ) : AgentEvent

    /**
     * Candidate empty-completion probe. Not frozen until API review closes.
     *
     * Emitted by the native tool loop when a model round ends with no tool calls and no
     * accepted finish/implicit-finish. Hosts use this to diagnose continue-vs-fail root
     * causes; UI must not invent loop policy from presentation mode.
     *
     * [kind]: EMPTY_NO_OUTPUT | THINKING_ONLY | TEXT_WITHOUT_FINISH
     * [continued]: true when a budgeted continue nudge was injected into model history
     */
    data class EmptyCompletionObserved(
        val runId: String,
        val modelRound: Int,
        val kind: String,
        val continued: Boolean,
        val continueCount: Int,
        val textChars: Int,
        val thinkingChars: Int,
        val hadStateAdvancingWork: Boolean,
        val successfulBackendToolCount: Int,
    ) : AgentEvent

    /** Candidate in-flight steering event. Not frozen until API review closes. */
    data class SteeringAccepted(
        val runId: String,
        val requestId: String,
        val textPreview: String,
    ) : AgentEvent

    /** Candidate redacted native tool call event. Not frozen until API review closes. */
    data class NativeToolCallStarted(
        val runId: String,
        val toolName: String,
        val arguments: String,
        val toolCallId: String = "",
    ) : AgentEvent

    /** Candidate redacted native tool result event. Not frozen until API review closes. */
    data class NativeToolCallFinished(
        val runId: String,
        val toolName: String,
        val result: String,
        val toolCallId: String = "",
    ) : AgentEvent

    /** Candidate metadata-only network tool activity event. Not frozen until API review closes. */
    data class NetworkToolActivity(
        val runId: String,
        val toolName: String,
        val decision: String,
        val toolCallId: String = "",
        val domain: String = "",
        val statusCode: Int? = null,
        val truncated: Boolean = false,
    ) : AgentEvent

    /** Candidate metadata-only web_search activity event. Not frozen until API review closes. */
    data class SearchActivity(
        val runId: String,
        val provider: String,
        val queryPreview: String = "",
        val resultCount: Int? = null,
    ) : AgentEvent

    /** Candidate redacted exec output stream event. Not frozen until API review closes. */
    data class ExecOutputDelta(
        val runId: String,
        val text: String,
    ) : AgentEvent

    /** Candidate workspace write visibility event. Not frozen until API review closes. */
    data class WorkspaceFileChanged(
        val runId: String,
        val path: String,
        val toolName: String,
        val toolCallId: String = "",
        val bytes: Long = 0L,
        val replacements: Int? = null,
        val addedLines: Int = 0,
        val removedLines: Int = 0,
        val diffTimedOut: Boolean = false,
    ) : AgentEvent

    /** Candidate workspace binding visibility event. Not frozen until API review closes. */
    data class WorkspaceChanged(
        val runId: String,
        val workspaceId: String,
    ) : AgentEvent

    data class ResultAvailable(val result: RuntimeResult) : AgentEvent

    data class Finished(val runId: String) : AgentEvent

    data class Failed(
        val runId: String,
        val code: String,
        val message: String,
        val recoverable: Boolean,
    ) : AgentEvent
}

data class ConfirmationDecision(
    val pendingId: String,
    val requestId: String,
    val choice: ConfirmationChoice,
)

enum class ConfirmationChoice {
    APPROVE,
    DENY,
}

data class PendingConfirmation(
    val pendingId: String,
    val sessionId: String,
    val runId: String,
    val expiresAtEpochMillis: Long,
    val idempotencyKey: String,
    /** What to ask the user, from the policy rule or hook that wanted the confirmation. */
    val prompt: String = "",
    /** The model-visible name of the tool call waiting for this answer; null when no tool call waits. */
    val toolName: String? = null,
    /** The arguments that run on approval, after hook rewrites; null when no tool call waits. */
    val argumentsJson: String? = null,
)

/** Candidate in-flight steering request. Not frozen until API review closes. */
data class PostUserInputRequest(
    val runId: String,
    val requestId: String,
    val text: String,
    val sessionId: String? = null,
    val questionId: String? = null,
)

/** Candidate in-flight steering result. Not frozen until API review closes. */
sealed interface PostUserInputResult {
    data object Accepted : PostUserInputResult

    /** The identical request was already accepted; it will not be applied twice. */
    data object Duplicate : PostUserInputResult

    /** A bounded, validated input was not admitted. No model work was started. */
    data class Rejected(val code: String) : PostUserInputResult

    data object RunNotActive : PostUserInputResult

    data object NotFound : PostUserInputResult
}

/** Candidate confirmation cancellation request. Not frozen until API review closes. */
data class CancelPendingConfirmationRequest(
    val pendingId: String,
    val requestId: String,
    val reason: String? = null,
)

sealed interface ConfirmationResumeResult {
    data object Accepted : ConfirmationResumeResult

    data object AlreadyConsumed : ConfirmationResumeResult

    data object Expired : ConfirmationResumeResult

    data object NotFound : ConfirmationResumeResult

    data class Rejected(val reason: String) : ConfirmationResumeResult
}

/** Candidate cancellation result for pending user confirmation lifecycle. */
sealed interface CancelPendingResult {
    data class Cancelled(
        val pending: PendingConfirmation,
    ) : CancelPendingResult

    data object AlreadyConsumed : CancelPendingResult

    data object Expired : CancelPendingResult

    data object NotFound : CancelPendingResult
}

/** Candidate ProtocolRequest resolve request. Not frozen until API review closes. */
data class ProtocolRequestResolution(
    val protocolRequestId: String,
    val requestId: String,
    val responsePayloadJson: String? = null,
)

/** Candidate ProtocolRequest reject request. Not frozen until API review closes. */
data class ProtocolRequestRejection(
    val protocolRequestId: String,
    val requestId: String,
    val reason: String,
)

/** Candidate ProtocolRequest cancel request. Not frozen until API review closes. */
data class ProtocolRequestCancellation(
    val protocolRequestId: String,
    val requestId: String,
    val reason: String? = null,
)

/** Candidate close-loop result for ProtocolRequest lifecycle transitions. */
sealed interface ProtocolRequestCloseLoopResult {
    data class Updated(
        val protocolRequest: RuntimeProtocolRequest,
    ) : ProtocolRequestCloseLoopResult

    data class AlreadyApplied(
        val protocolRequest: RuntimeProtocolRequest,
    ) : ProtocolRequestCloseLoopResult

    data class AlreadyTerminal(
        val protocolRequest: RuntimeProtocolRequest,
    ) : ProtocolRequestCloseLoopResult

    data class Expired(
        val protocolRequest: RuntimeProtocolRequest,
    ) : ProtocolRequestCloseLoopResult

    data object NotFound : ProtocolRequestCloseLoopResult

    data class InvalidState(
        val status: RuntimeProtocolRequestStatus,
    ) : ProtocolRequestCloseLoopResult
}
