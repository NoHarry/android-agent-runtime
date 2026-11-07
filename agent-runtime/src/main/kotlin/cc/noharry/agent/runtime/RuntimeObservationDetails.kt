package cc.noharry.agent.runtime

import cc.noharry.agent.spi.AgentEvent

internal fun AgentEvent.EmptyCompletionObserved.traceDetails(): Map<String, String> = mapOf(
    "modelRound" to modelRound.toString(), "kind" to kind, "continued" to continued.toString(),
    "continueCount" to continueCount.toString(), "textChars" to textChars.toString(),
    "thinkingChars" to thinkingChars.toString(), "hadStateAdvancingWork" to hadStateAdvancingWork.toString(),
    "successfulBackendToolCount" to successfulBackendToolCount.toString(),
)
