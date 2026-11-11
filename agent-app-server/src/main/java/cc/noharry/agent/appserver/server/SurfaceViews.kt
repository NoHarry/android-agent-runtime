@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.server

import cc.noharry.agent.appserver.protocol.SubagentLane
import cc.noharry.agent.appserver.protocol.SubagentRunState
import cc.noharry.agent.runtime.SubagentStatus

/** The subagent board as the card's lanes read it. */
internal fun Map<String, List<SubagentStatus>>.lanes(): Map<String, List<SubagentLane>> =
    mapValues { (_, children) -> children.map { it.lane() } }

private fun SubagentStatus.lane() = SubagentLane(
    id = id,
    label = label,
    state = SubagentRunState.valueOf(state.name),
    startedAtEpochMillis = startedAtEpochMillis,
    lastActivityEpochMillis = lastActivityEpochMillis,
    finishedAtEpochMillis = finishedAtEpochMillis,
    toolCalls = toolCalls,
    latest = latest,
    result = result,
    error = error,
    parentSessionId = parentSessionId,
    sessionId = sessionId,
    task = task,
    modelCalls = modelCalls,
    inputTokens = inputTokens,
    cachedTokens = cachedTokens,
    outputTokens = outputTokens,
    agentType = agentType,
)
