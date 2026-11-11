@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.session

import cc.noharry.agent.appserver.evidence.isInternalToolName
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.SessionToolCall
import cc.noharry.agent.appserver.protocol.SessionTurn
internal data class SessionContext(
    val summary: SessionSummary,
    val recentTurns: List<SessionTurn>,
) {
    internal val lastAction: SessionToolCall? =
        recentTurns.asReversed().flatMap { it.tools.asReversed() }.firstOrNull { it.isMeaningfulAction() }
}

internal fun SessionTurn.meaningfulAction(): SessionToolCall? =
    tools.asReversed().firstOrNull { it.isMeaningfulAction() }

internal fun SessionToolCall.isMeaningfulAction(): Boolean =
    toolName.isNotBlank() && !toolName.isInternalToolName()

internal const val DEBUG_REPLAY_STATUS_NOT_RUN = "NOT_RUN"
internal const val DEBUG_REPLAY_STATUS_PASSED = "PASSED"
internal const val DEBUG_REPLAY_STATUS_FAILED = "FAILED"
