package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.reliability.RuntimeBlocker
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.SuggestedNextAction

class ResumeFirstController(
    private val runStateController: RunStateController,
) {
    fun resumeFirstBlocker(sessionId: String): RuntimeBlocker? =
        pendingConfirmationBlocker(sessionId)
            ?: waitingUserInputBlocker(sessionId)
            ?: waitingProtocolBlocker(sessionId)

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

    private fun waitingProtocolBlocker(sessionId: String): RuntimeBlocker? {
        val waitingSnapshot = runStateController.latestSnapshot(
            sessionId = sessionId,
            status = RuntimeRunStatus.WAITING_PROTOCOL_REQUEST,
        ) ?: return null
        return RuntimeBlocker(
            runId = waitingSnapshot.runId,
            code = WAITING_PROTOCOL_BLOCKER_CODE,
            message = "A pending protocol request must be resolved before starting a new run.",
            suggestedNextAction = SuggestedNextAction.RESOLVE_PROTOCOL_REQUEST,
            handoff = runStateController.handoff(waitingSnapshot.runId),
        )
    }
}

const val WAITING_PROTOCOL_BLOCKER_CODE = "WAITING_PROTOCOL_REQUEST"
