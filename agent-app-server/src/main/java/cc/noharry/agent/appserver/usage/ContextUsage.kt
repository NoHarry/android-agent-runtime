@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.usage
import cc.noharry.agent.appserver.protocol.ContextUsage
import cc.noharry.agent.appserver.protocol.ModelCallPhase
import cc.noharry.agent.appserver.protocol.TrajectoryModelCall
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.session.UiState
import cc.noharry.agent.spi.model.ModelRef
import cc.noharry.agent.runtime.RuntimeInspection.SESSION_MODEL_CALL_SOURCE_COMPACTION
import cc.noharry.agent.runtime.RuntimeInspection.SESSION_MODEL_CALL_SOURCE_ROUND
import cc.noharry.agent.model.ModelVariantRegistry

internal fun contextUsage(state: UiState, windowTokens: Long?): ContextUsage {
    val profile = state.configEditor
    val modelRef = ModelVariantRegistry.resolve(ModelRef(profile.provider.wireName, profile.model, profile.variant))
        ?.wireValue().orEmpty()
    val activeRunId = state.currentRequest?.requestId.takeIf { state.isRunning }
    return contextUsage(state.trajectory, modelRef, windowTokens, activeRunId)
}

internal fun contextUsage(
    snapshot: TrajectorySnapshot,
    modelRef: String,
    windowTokens: Long?,
    activeRunId: String? = null,
): ContextUsage {
    val empty = ContextUsage(windowTokens = windowTokens)
    // Repository catalog is newest-first, including live arrivals; the running run leads.
    val active = activeRunId?.let { id -> snapshot.runGroups.find { it.runId == id } }
    // A run carries its session's earlier runs, so until its own first call reports, the context in use is the size of
    // the session's latest request: fall back to earlier runs instead of showing an empty ring at every run start.
    for (group in listOfNotNull(active) + snapshot.runGroups.filter { it !== active }) {
        if (group.sessionId != snapshot.sessionId || group.loadFailed || group.health.hasSequenceGap) continue
        val calls = group.records.filter { it.sessionId == snapshot.sessionId && it.runId == group.runId }
            .sortedBy { it.sequence }.mapNotNull { it.rich?.modelCall }
        val usage = recentContextInput(calls, modelRef, empty)
        if (usage.inputTokens != null || usage.awaitingAfterCompaction) return usage
    }
    return empty
}

private fun recentContextInput(calls: List<TrajectoryModelCall>, modelRef: String, empty: ContextUsage): ContextUsage {
    var request: TrajectoryModelCall? = null
    var input = 0L
    var invalid = false
    var last = empty
    for (payload in calls) {
        when (payload.phase) {
            ModelCallPhase.REQUEST -> { request = payload; input = 0; invalid = false }
            ModelCallPhase.USAGE -> if (request != null) {
                // Runtime normalizes cache-exclusive provider usage into inclusive input already.
                if (payload.inputTokens < 0 || payload.inputTokens > Long.MAX_VALUE - input) invalid = true
                else input += payload.inputTokens
            }
            ModelCallPhase.FINISHED -> {
                last = when {
                    payload.source == SESSION_MODEL_CALL_SOURCE_COMPACTION -> empty.copy(awaitingAfterCompaction = true)
                    payload.source != SESSION_MODEL_CALL_SOURCE_ROUND -> last
                    request?.modelRef != modelRef || payload.modelRef != modelRef -> empty
                    invalid || input <= 0 -> empty
                    else -> empty.copy(inputTokens = input)
                }
                request = null
            }
        }
    }
    return last
}

private const val PERCENT = 100
