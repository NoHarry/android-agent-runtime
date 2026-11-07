package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.spi.reliability.RuntimeBlocker
import cc.noharry.agent.spi.reliability.RuntimeErrorCode
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.spi.reliability.SuggestedNextAction

internal class ResumeFirstController(
    private val runStateController: RunStateController,
) {
    fun resumeFirstBlocker(sessionId: String): RuntimeBlocker? =
        pendingConfirmationBlocker(sessionId)
            ?: waitingUserInputBlocker(sessionId)

    private fun pendingConfirmationBlocker(sessionId: String): RuntimeBlocker? {
        val pendingSnapshot = runStateController.latestSnapshot(
            sessionId = sessionId,
            status = RuntimeRunStatus.WAITING_CONFIRMATION,
        ) ?: return null
        val handoff = runStateController.handoff(pendingSnapshot.runId)
        return RuntimeBlocker(
            runId = pendingSnapshot.runId,
            code = RuntimeErrorCode.PENDING_CONFIRMATION_EXISTS.wireCode,
            message = "A pending confirmation must be resolved before starting a new run.",
            suggestedNextAction = SuggestedNextAction.RESUME_CONFIRMATION,
            handoff = handoff,
        )
    }

    private fun waitingUserInputBlocker(sessionId: String): RuntimeBlocker? {
        val waitingSnapshot = runStateController.latestSnapshot(
            sessionId = sessionId,
            status = RuntimeRunStatus.WAITING_USER_INPUT,
        ) ?: return null
        val confusion = runStateController.openConfusion(waitingSnapshot.runId)
        return RuntimeBlocker(
            runId = waitingSnapshot.runId,
            code = RuntimeErrorCode.WAITING_USER_INPUT.wireCode,
            message = confusion?.question ?: "The run is waiting for user input.",
            suggestedNextAction = SuggestedNextAction.START_NEW_AGENT_REQUEST,
            handoff = runStateController.handoff(waitingSnapshot.runId),
        )
    }

}
