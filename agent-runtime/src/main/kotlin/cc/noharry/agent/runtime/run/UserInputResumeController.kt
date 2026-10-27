package cc.noharry.agent.runtime.run
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.loop.NativeLoopOutcome
import cc.noharry.agent.kernel.core.state.InMemoryConfirmationStore
import cc.noharry.agent.kernel.core.state.PendingToolUseController
import cc.noharry.agent.kernel.core.state.RunStateController
import cc.noharry.agent.runtime.DEFAULT_CONFIRMATION_TIMEOUT_MILLIS
import cc.noharry.agent.runtime.RuntimeWorkspaceRegistry
import cc.noharry.agent.runtime.RuntimeWorkspaceSelection
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class UserInputResumeController(
    private val runStateController: RunStateController,
    private val nativeLoopFactory: NativeLoopFactory,
    private val confirmationStore: InMemoryConfirmationStore,
    private val pendingToolUseController: PendingToolUseController,
    private val workspaceRegistry: RuntimeWorkspaceRegistry,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun resume(request: AgentRequest): RuntimeResult? {
        val runId = request.resumeRunId ?: return null
        val snapshot = withContext(ioDispatcher) { runStateController.snapshot(runId) } ?: return null
        if (snapshot.sessionId != request.sessionId) {
            return null
        }
        if (
            snapshot.status != RuntimeRunStatus.WAITING_USER_INPUT &&
            snapshot.status != RuntimeRunStatus.RUNNING
        ) {
            return null
        }
        // Select before any resume state transition so unknown workspaces fail closed.
        val selection = workspaceRegistry.select(request, runId)
        if (selection is RuntimeWorkspaceSelection.Unknown) {
            return withContext(ioDispatcher) {
                runStateController.applyRuntimeResult(
                    RuntimeResult(
                        runId = runId,
                        status = RuntimeRunStatus.FAILED,
                        response = "Unknown runtime workspace: ${selection.workspaceId}",
                        errorCode = RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode,
                        recoverable = false,
                    ),
                )
            }
        }
        val workspaceFileSystem = (selection as RuntimeWorkspaceSelection.Selected).fileSystem
        withContext(ioDispatcher) { runStateController.markUserInputResumed(runId) }
        return when (val outcome = nativeLoopFactory.resumeUserInput(
            runId = runId,
            sessionId = request.sessionId,
            requestId = request.requestId,
            answer = request.input,
            scene = request.scene,
            modelRef = request.modelRef,
            emitPublicEvent = {},
            currentStateKeys = request.currentStateKeys,
            workspaceId = request.workspaceId,
            workspaceFileSystem = workspaceFileSystem,
        )) {
            is NativeLoopOutcome.Terminal -> recordTerminal(runId, outcome)
            is NativeLoopOutcome.RequiresConfirmation -> {
                recordResumeConfirmation(snapshot, outcome)
                null
            }
            is NativeLoopOutcome.NeedsUserInput -> {
                withContext(ioDispatcher) {
                    runStateController.recordNativeLoopSummary(runId, outcome.summary)
                    runStateController.markWaitingUserInput(snapshot, outcome.question, outcome.summary)
                }
                null
            }
        }
    }

    private suspend fun recordTerminal(
        runId: String,
        outcome: NativeLoopOutcome.Terminal,
    ): RuntimeResult = withContext(ioDispatcher) {
        runStateController.recordNativeLoopSummary(runId, outcome.summary)
        runStateController.applyRuntimeResult(outcome.result)
    }

    private suspend fun recordResumeConfirmation(
        snapshot: RuntimeRunSnapshot,
        outcome: NativeLoopOutcome.RequiresConfirmation,
    ) {
        val pending = confirmationStore.create(
            runId = snapshot.runId,
            sessionId = snapshot.sessionId,
            expiresInMillis = DEFAULT_CONFIRMATION_TIMEOUT_MILLIS,
        )
        withContext(ioDispatcher) {
            runStateController.recordNativeLoopSummary(snapshot.runId, outcome.summary)
            runStateController.markWaitingConfirmation(
                started = snapshot,
                prompt = outcome.prompt,
                pendingConfirmationId = pending.pendingId,
                summary = outcome.summary,
            )
        }
        outcome.pendingToolUse?.let { pendingToolUseController.record(pending, it) }
    }
}
