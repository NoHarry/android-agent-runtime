package cc.noharry.agent.runtime.run
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.loop.NativeLoopOutcome
import cc.noharry.agent.kernel.core.state.ConfirmationResume
import cc.noharry.agent.kernel.core.state.InMemoryConfirmationStore
import cc.noharry.agent.kernel.core.state.PendingToolUseController
import cc.noharry.agent.kernel.core.state.ProtocolRequestController
import cc.noharry.agent.kernel.core.state.RunStateController
import cc.noharry.agent.kernel.core.state.SessionRunLease
import cc.noharry.agent.kernel.core.state.protocolRequestOrNull
import cc.noharry.agent.kernel.core.trace.TraceRedactor
import cc.noharry.agent.kernel.core.trace.redactedForPublic
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Confirmation, user-input, and protocol resume/close-loop helpers.
 * Ports keep process-restart-unsupported, closed, and stale-lease semantics.
 */
internal class RuntimeControlResumeCoordinator(
    private val confirmationStore: InMemoryConfirmationStore,
    private val pendingToolUseController: PendingToolUseController,
    private val nativeLoopFactory: NativeLoopFactory,
    private val userInputResumeController: UserInputResumeController,
    private val sessionAdmission: RuntimeSessionAdmissionCoordinator,
    private val terminal: RuntimeTerminalCoordinator,
    private val runStateController: RunStateController,
    private val sessionRecorder: RuntimeSessionRecorder,
    private val protocolController: ProtocolRequestController,
    private val traceRedactor: TraceRedactor,
    private val closed: AtomicBoolean,
    private val runtimeIoDispatcher: CoroutineDispatcher,
    private val cleanupTerminalRun: suspend (String, String?) -> Unit,
    private val cancelledObligationCompletion: (String) -> RuntimeCompletionSummary,
    private val recordResumeConfirmation: suspend (String?, NativeLoopOutcome.RequiresConfirmation) -> Unit,
    private val recordResumeUserInput: suspend (String?, NativeLoopOutcome.NeedsUserInput) -> Unit,
) {
    suspend fun rejectOrCancelConfirmation(
        decision: ConfirmationDecision,
    ): ConfirmationResumeResult {
        val result = confirmationStore.resume(decision)
        val runId = result.runId
        val lease = runId?.let { sessionAdmission.leaseOf(it) }
        try {
            pendingToolUseController.remove(decision.pendingId)
            applyConfirmationTerminalState(result.runId, result.terminalStatus, result.response)
            return result.apiResult
        } catch (cancelled: CancellationException) {
            if (runId != null) {
                sessionAdmission.terminalizeCancelledRun(runId, lease)
            }
            throw cancelled
        }
    }

    suspend fun resumeAdmittedUserInput(
        request: AgentRequest,
        runId: String,
        onLease: (SessionRunLease) -> Unit,
    ): RuntimeResult? {
        return when (val outcome = sessionAdmission.admitResume(runId)) {
            is ResumeAdmission.Granted -> {
                onLease(outcome.lease)
                resumeGrantedUserInput(request, runId, outcome.lease)
            }
            is ResumeAdmission.ProcessRestartUnsupported,
            is ResumeAdmission.Closed,
            -> runtimeIo { runStateController.result(runId) }
            is ResumeAdmission.Rejected -> runtimeIo { runStateController.result(runId) }
        }
    }

    suspend fun resumeApprovedConfirmation(
        decision: ConfirmationDecision,
        runId: String,
    ): ConfirmationResumeResult {
        var lease = sessionAdmission.leaseOf(runId)
        try {
            return when (val outcome = sessionAdmission.admitResume(runId)) {
                is ResumeAdmission.Granted -> {
                    lease = outcome.lease
                    resumeGrantedConfirmation(decision, runId, outcome.lease)
                }
                is ResumeAdmission.ProcessRestartUnsupported ->
                    ConfirmationResumeResult.Rejected("Process-restart admission is unsupported.")
                is ResumeAdmission.Closed ->
                    ConfirmationResumeResult.Rejected("Runtime is closed.")
                is ResumeAdmission.Rejected ->
                    ConfirmationResumeResult.Rejected("Resume admission was rejected.")
            }
        } catch (cancelled: CancellationException) {
            sessionAdmission.terminalizeCancelledRun(runId, lease)
            throw cancelled
        }
    }

    suspend fun transitionProtocolRequest(
        protocolRequestId: String,
        requestId: String,
        targetStatus: RuntimeProtocolRequestStatus,
    ): ProtocolRequestCloseLoopResult {
        val result = runtimeIo {
            protocolController.transition(
                protocolRequestId = protocolRequestId,
                requestId = requestId,
                targetStatus = targetStatus,
            )
        }
        applyProtocolRequestOutcome(result.protocolRequestOrNull())
        return result.redactedForPublic(traceRedactor)
    }

    suspend fun applyConfirmationTerminalState(
        runId: String?,
        status: RuntimeRunStatus?,
        response: String?,
    ) {
        if (runId != null && status != null && status.isTerminalStatus()) {
            sessionAdmission.markFinalizingIfExecuting(runId)
        }
        persistConfirmationTerminal(runId, status, response)
        if (runId != null && status != null && status.isTerminalStatus()) {
            finalizeControlRun(runId, status.name)
        } else {
            runId?.let { cleanupTerminalRun(it, null) }
        }
    }

    private suspend fun resumeGrantedUserInput(
        request: AgentRequest,
        runId: String,
        lease: SessionRunLease,
    ): RuntimeResult? {
        if (!sessionAdmission.persistRunningAfterResumeGrant(runId, lease)) {
            return runtimeIo { runStateController.result(runId) }
        }
        terminal.indexRunRequest(request, request.requestId)
        terminal.indexRunRequest(request, runId)
        val result = userInputResumeController.resume(request)
        if (result == null) {
            return keepWaitingUserInput(runId)
        }
        sessionAdmission.markFinalizingIfExecuting(runId, lease)
        runtimeIo { sessionRecorder.finishRun(result) }
        finalizeControlRun(result.runId, result.status.name, lease)
        return result
    }

    private suspend fun keepWaitingUserInput(runId: String): RuntimeResult? {
        val waiting = runtimeIo { runStateController.snapshot(runId) }
        if (waiting != null && !waiting.status.isTerminalStatus()) {
            runtimeIo { sessionRecorder.updateRunStatus(runId, waiting.status) }
            sessionAdmission.markWaitingAfterPersist(runId)
        }
        return null
    }

    private suspend fun applyProtocolRequestOutcome(protocolRequest: RuntimeProtocolRequest?) {
        // REJECTED/CANCELLED/EXPIRED end the run. RESOLVED must not leave orphan RUNNING:
        // fairly resume, then finish COMPLETED + UNKNOWN (never first-party PASS).
        when (protocolRequest?.status) {
            RuntimeProtocolRequestStatus.REJECTED,
            RuntimeProtocolRequestStatus.CANCELLED,
            RuntimeProtocolRequestStatus.EXPIRED,
            -> persistProtocolTerminal(protocolRequest.runId, protocolRequest.status)
            RuntimeProtocolRequestStatus.RESOLVED -> finishResolvedProtocolRequest(protocolRequest.runId)
            RuntimeProtocolRequestStatus.CREATED,
            RuntimeProtocolRequestStatus.WAITING,
            null,
            -> Unit
        }
    }

    private suspend fun persistConfirmationTerminal(
        runId: String?,
        status: RuntimeRunStatus?,
        response: String?,
    ) {
        runtimeIo {
            val completion = if (runId != null && status == RuntimeRunStatus.CANCELLED) {
                cancelledObligationCompletion(runId)
            } else {
                RuntimeCompletionSummary.unknown()
            }
            runStateController.applyTerminalState(runId, status, response, completion)
            if (runId != null && status != null) {
                sessionRecorder.updateRunStatus(runId, status, response.orEmpty())
            }
        }
    }

    private suspend fun resumeGrantedConfirmation(
        decision: ConfirmationDecision,
        runId: String,
        lease: SessionRunLease,
    ): ConfirmationResumeResult {
        if (!sessionAdmission.persistRunningAfterResumeGrant(runId, lease)) {
            return failClosedApprovedResume(decision, runId, lease)
        }
        val result = confirmationStore.resume(decision)
        if (result.apiResult != ConfirmationResumeResult.Accepted) {
            return applyRejectedConfirmationResume(decision, result)
        }
        applyResumedConfirmationOutcome(decision, result, lease)
        return result.apiResult
    }

    private suspend fun applyRejectedConfirmationResume(
        decision: ConfirmationDecision,
        result: ConfirmationResume,
    ): ConfirmationResumeResult {
        pendingToolUseController.remove(decision.pendingId)
        applyConfirmationTerminalState(result.runId, result.terminalStatus, result.response)
        return result.apiResult
    }

    private suspend fun applyResumedConfirmationOutcome(
        decision: ConfirmationDecision,
        result: ConfirmationResume,
        lease: SessionRunLease,
    ) {
        val resumed = pendingToolUseController.resume(decision) {
            nativeLoopFactory.resumeConfirmedToolUse(it)
        }
        when (resumed) {
            is NativeLoopOutcome.Terminal -> sessionAdmission.applyRuntimeResult(resumed.result, lease)
            is NativeLoopOutcome.RequiresConfirmation -> recordResumeConfirmation(result.runId, resumed)
            is NativeLoopOutcome.NeedsUserInput -> recordResumeUserInput(result.runId, resumed)
            null -> applyConfirmationTerminalState(result.runId, result.terminalStatus, result.response)
        }
    }

    private suspend fun failClosedApprovedResume(
        decision: ConfirmationDecision,
        runId: String,
        lease: SessionRunLease,
    ): ConfirmationResumeResult {
        val current = runtimeIo { runStateController.snapshot(runId) }
        if (current != null && current.status.isTerminalStatus()) {
            return rejectAlreadyTerminalResume(decision, runId, lease)
        }
        if (closed.get()) {
            sessionAdmission.persistClosedRun(runId)
            pendingToolUseController.remove(decision.pendingId)
            return ConfirmationResumeResult.Rejected("Runtime is closed.")
        }
        return rejectFailedResumeAdmission(decision, runId)
    }

    private suspend fun rejectAlreadyTerminalResume(
        decision: ConfirmationDecision,
        runId: String,
        lease: SessionRunLease,
    ): ConfirmationResumeResult {
        val storeResult = confirmationStore.resume(decision)
        pendingToolUseController.remove(decision.pendingId)
        sessionAdmission.releaseExactLease(runId, lease)
        return if (storeResult.apiResult == ConfirmationResumeResult.Accepted) {
            ConfirmationResumeResult.Rejected("Resume admission was rejected.")
        } else {
            storeResult.apiResult
        }
    }

    private suspend fun rejectFailedResumeAdmission(
        decision: ConfirmationDecision,
        runId: String,
    ): ConfirmationResumeResult {
        pendingToolUseController.remove(decision.pendingId)
        applyConfirmationTerminalState(
            runId,
            RuntimeRunStatus.CANCELLED,
            "Resume admission was rejected.",
        )
        return ConfirmationResumeResult.Rejected("Resume admission was rejected.")
    }

    private suspend fun finishResolvedProtocolRequest(runId: String) {
        when (val outcome = sessionAdmission.admitResume(runId)) {
            is ResumeAdmission.Granted -> finishGrantedProtocolRequest(runId, outcome.lease)
            is ResumeAdmission.ProcessRestartUnsupported,
            is ResumeAdmission.Closed,
            is ResumeAdmission.Rejected,
            -> Unit
        }
    }

    private suspend fun finishGrantedProtocolRequest(runId: String, lease: SessionRunLease) {
        if (!sessionAdmission.persistRunningAfterResumeGrant(runId, lease)) {
            return
        }
        sessionAdmission.markFinalizingIfExecuting(runId, lease)
        sessionAdmission.applyRuntimeResult(
            RuntimeResult(
                runId = runId,
                status = RuntimeRunStatus.COMPLETED,
                response = "Protocol request resolved.",
                completion = RuntimeCompletionSummary.unknown(),
            ),
            lease = lease,
        )
    }

    private suspend fun persistProtocolTerminal(
        runId: String,
        protocolStatus: RuntimeProtocolRequestStatus,
    ) {
        val result = RuntimeResult(
            runId = runId,
            status = RuntimeRunStatus.CANCELLED,
            response = protocolTerminalResponse(protocolStatus),
            recoverable = false,
            completion = RuntimeCompletionSummary.unknown(),
        )
        sessionAdmission.markFinalizingIfExecuting(runId)
        runtimeIo {
            runStateController.applyRuntimeResult(result).also(sessionRecorder::finishRun)
        }
        finalizeControlRun(runId, result.status.name)
    }

    private fun protocolTerminalResponse(protocolStatus: RuntimeProtocolRequestStatus): String {
        return when (protocolStatus) {
            RuntimeProtocolRequestStatus.REJECTED -> "Protocol request rejected."
            RuntimeProtocolRequestStatus.EXPIRED -> "Protocol request expired."
            else -> "Protocol request cancelled."
        }
    }

    private suspend fun finalizeControlRun(
        runId: String,
        status: String,
        lease: SessionRunLease? = sessionAdmission.leaseOf(runId),
    ) {
        terminal.coordinateTerminalFinalization(runId, status, lease = lease)
    }

    private suspend fun <T> runtimeIo(block: suspend () -> T): T =
        withContext(runtimeIoDispatcher) { block() }

    private fun RuntimeRunStatus.isTerminalStatus(): Boolean =
        this == RuntimeRunStatus.COMPLETED ||
            this == RuntimeRunStatus.FAILED ||
            this == RuntimeRunStatus.CANCELLED
}
