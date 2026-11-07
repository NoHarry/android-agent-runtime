@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.spi

import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.spi.reliability.RuntimeBlocker
import cc.noharry.agent.spi.reliability.RuntimeEvidence
import cc.noharry.agent.spi.reliability.OpenConfusion
import cc.noharry.agent.spi.reliability.RuntimeHandoff
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunSnapshot
import cc.noharry.agent.spi.reliability.RuntimeTraceEvent
import kotlinx.coroutines.flow.Flow

internal interface AgentRuntime {
    /** Starts a runtime run. */
    fun run(request: AgentRequest): Flow<AgentEvent>

    /** Resumes a pending user confirmation. */
    suspend fun resumeConfirmation(decision: ConfirmationDecision): ConfirmationResumeResult

    /** Returns a pending user confirmation, or null when missing, consumed, or expired. */
    suspend fun getPendingConfirmation(pendingId: String): PendingConfirmation?

    /** Cancels a pending user confirmation. */
    suspend fun cancelPendingConfirmation(
        request: CancelPendingConfirmationRequest,
    ): CancelPendingResult

    /** Returns a redacted run snapshot, or null when the run is unknown. */
    suspend fun getRunState(runId: String): RuntimeRunSnapshot?

    /** Returns a terminal runtime result, or null when the run is unknown or not terminal. */
    suspend fun getRuntimeResult(runId: String): RuntimeResult?

    /** Queries the redacted handoff of a waiting run. */
    suspend fun getRuntimeHandoff(runId: String): RuntimeHandoff?

    /** Queries the open confusion of a WAITING_USER_INPUT run. */
    suspend fun getOpenConfusion(runId: String): OpenConfusion?

    /**
     * Resumes a run waiting for user input.
     *
     * Runtime reliability storage keeps only loop history and safe run metadata.
     * Hosts must re-supply current state and workspace selection when resuming.
     */
    suspend fun resumeUserInput(request: AgentRequest): RuntimeResult?

    /** Steers an active RUNNING run with in-flight user input. */
    suspend fun postUserInput(request: PostUserInputRequest): PostUserInputResult

    /** Queries redacted evidence by id. */
    suspend fun getRuntimeEvidence(evidenceId: String): RuntimeEvidence?

    /** Queries a run's redacted trace events. */
    suspend fun getRuntimeTraceEvents(runId: String): List<RuntimeTraceEvent>

    /** Queries the append-only, privacy-safe event authority for offline analysis. */
    @ExperimentalAgentRuntimeApi
    suspend fun querySessionEvents(query: SessionEventQuery): List<SessionEvent> = emptyList()

    /** Replays one run stream in its canonical append order. */
    @ExperimentalAgentRuntimeApi
    suspend fun replaySessionEvents(sessionId: String, runId: String): List<SessionEvent> = emptyList()

    /**
     * EventLog health for one `(sessionId, runId)` stream.
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
     * [cc.noharry.agent.spi.reliability.RuntimeErrorCode.RUNTIME_CLOSED]
     * for runs cancelled by close.
     *
     * Lifecycle close also performs reverse-registration close of context
     * providers and turn observers. Idempotent; never throws to hosts.
     */
    @ExperimentalAgentRuntimeApi
    fun close() {}
}

/**
 * Run request; [currentStateKeys] is trusted typed host state, never prompt-derived.
 *
 * Resume rebuilds copy trusted [currentStateKeys] and [workspaceId] from the host request.
 * Those fields are not written to the Runtime reliability store.
 *
 * [sessionRunConflictPolicy] is consulted only when a same-session owner is WAITING_CONTROL.
 * Null requires an explicit host choice then. The field is appended last so existing
 * positional constructors remain coherent.
 */
internal data class AgentRequest(
    val requestId: String,
    val sessionId: String,
    val input: String,
    val scene: String? = null,
    val resumeRunId: String? = null,
    val modelRef: ModelRef? = null,
    val workspaceId: String? = null,
    val currentStateKeys: Set<String> = emptySet(),
    val sessionRunConflictPolicy: SessionRunConflictPolicy? = null,
    val inputImages: List<cc.noharry.agent.spi.model.ModelImage> = emptyList(),
    val instructions: String? = null,
    private val executionIdOverride: String? = null,
    val approvalPolicy: cc.noharry.agent.ApprovalPolicy = cc.noharry.agent.ApprovalPolicy.ASK,
) {
    val executionId: String get() = executionIdOverride ?: requestId

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

internal sealed interface AgentEvent {
    data class Started(val runId: String) : AgentEvent

    /** Live projection of the canonical question/input fact, never a second content ledger. */
    data class UserInteraction(val runId: String, val interaction: SessionEventPayload.UserInteraction) : AgentEvent

    /** Model thinking/status delta. */
    data class ThinkingDelta(
        val runId: String,
        val text: String,
    ) : AgentEvent

    /** Model text delta. */
    data class TextDelta(
        val runId: String,
        val text: String,
    ) : AgentEvent

    /** Provider token usage delta. */
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

    /** Model retry event. */
    data class RetryAttempted(
        val runId: String,
        val attempt: Int,
        val delayMillis: Long,
        val modelRef: ModelRef? = null,
        val fallbackModelRef: ModelRef? = null,
    ) : AgentEvent

    /** Loop budget warning event. */
    data class BudgetWarning(
        val runId: String,
        val roundsRemaining: Int,
        val toolCallsRemaining: Int,
    ) : AgentEvent

    /** Loop history compaction event. */
    data class CompactionApplied(
        val runId: String,
        val beforeMessageCount: Int,
        val afterMessageCount: Int,
        val droppedCount: Int,
        val summaryBytes: Int,
    ) : AgentEvent

    /**
     * Empty-completion probe.
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

    /** In-flight steering event. */
    data class SteeringAccepted(
        val runId: String,
        val requestId: String,
        val textPreview: String,
    ) : AgentEvent

    /** Live native tool call; the host decides how to display its arguments. Stored traces remain redacted. */
    data class NativeToolCallStarted(
        val runId: String,
        val toolName: String,
        val arguments: String,
        val toolCallId: String = "",
    ) : AgentEvent

    /** Live native tool result; the host decides how to display its output. Stored traces remain redacted. */
    data class NativeToolCallFinished(
        val runId: String,
        val toolName: String,
        val result: String,
        val toolCallId: String = "",
    ) : AgentEvent

    /** Metadata-only network tool activity event. */
    data class NetworkToolActivity(
        val runId: String,
        val toolName: String,
        val decision: String,
        val toolCallId: String = "",
        val domain: String = "",
        val statusCode: Int? = null,
        val truncated: Boolean = false,
    ) : AgentEvent

    /** Metadata-only web_search activity event. */
    data class SearchActivity(
        val runId: String,
        val provider: String,
        val queryPreview: String = "",
        val resultCount: Int? = null,
    ) : AgentEvent

    /** Redacted exec output stream event. */
    data class ExecOutputDelta(
        val runId: String,
        val text: String,
    ) : AgentEvent

    /** Workspace write visibility event. */
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

    /** Workspace binding visibility event. */
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

internal data class ConfirmationDecision(
    val pendingId: String,
    val requestId: String,
    val choice: ConfirmationChoice,
)

internal enum class ConfirmationChoice {
    APPROVE,
    DENY,
}

internal data class PendingConfirmation(
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

/** In-flight steering request. */
internal data class PostUserInputRequest(
    val runId: String,
    val requestId: String,
    val text: String,
    val sessionId: String? = null,
    val questionId: String? = null,
)

/** In-flight steering result. */
internal sealed interface PostUserInputResult {
    data object Accepted : PostUserInputResult

    /** The identical request was already accepted; it will not be applied twice. */
    data object Duplicate : PostUserInputResult

    /** A bounded, validated input was not admitted. No model work was started. */
    data class Rejected(val code: String) : PostUserInputResult

    data object RunNotActive : PostUserInputResult

    data object NotFound : PostUserInputResult
}

/** Confirmation cancellation request. */
internal data class CancelPendingConfirmationRequest(
    val pendingId: String,
    val requestId: String,
    val reason: String? = null,
)

internal sealed interface ConfirmationResumeResult {
    data object Accepted : ConfirmationResumeResult

    data object AlreadyConsumed : ConfirmationResumeResult

    data object Expired : ConfirmationResumeResult

    data object NotFound : ConfirmationResumeResult

    data class Rejected(val reason: String) : ConfirmationResumeResult
}

/** Cancellation result for pending user confirmation lifecycle. */
internal sealed interface CancelPendingResult {
    data class Cancelled(
        val pending: PendingConfirmation,
    ) : CancelPendingResult

    data object AlreadyConsumed : CancelPendingResult

    data object Expired : CancelPendingResult

    data object NotFound : CancelPendingResult
}
