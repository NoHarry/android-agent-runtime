@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import android.content.Context
import cc.noharry.agent.appserver.evidence.appRunEvidenceRecorder
import cc.noharry.agent.appserver.mcp.McpProgressiveEvidenceRecorder
import cc.noharry.agent.appserver.model.ProviderProfile
import cc.noharry.agent.appserver.protocol.RunOutcome
import cc.noharry.agent.appserver.session.SessionCoordinator
import cc.noharry.agent.appserver.session.SessionTurnRecorder
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal data class RuntimeStartupFailure(val snapshot: ActiveRunSnapshot, val message: String)

internal fun createRunRecorders(
    context: Context,
    request: RuntimeTurnRequest,
    text: String,
    profile: ProviderProfile,
    simulated: Boolean,
    streamLabel: String,
    backendLabel: String,
): RuntimeRunRecorders = RuntimeRunRecorders(
    // The simulated backend's tool search, kept only for a host that keeps diagnostics.
    evidenceRecorder = if (simulated && AppHost.diagnostics) McpProgressiveEvidenceRecorder(context, request.requestId) else null,
    runEvidenceRecorder = appRunEvidenceRecorder(context, request, profile.displayLabel, streamLabel, backendLabel, text),
    turnRecorder = SessionTurnRecorder(request, text),
)

/** Construction happens before registry ownership; a failed constructor must not kill the UI scope. */
internal suspend fun constructRuntime(
    runScope: RuntimeRunScope,
    recorders: RuntimeRunRecorders,
    coordinator: SessionCoordinator,
    startedAt: Long?,
    onFailure: (RuntimeStartupFailure) -> Unit,
    onCancelled: () -> Unit,
    build: () -> AgentRuntime,
): AgentRuntime? = try {
    buildRuntimeOffMain(build)
} catch (error: CancellationException) {
    onCancelled()
    throw error
} catch (error: Exception) {
    val failure = withContext(Dispatchers.IO) { recordStartupFailure(runScope, recorders, coordinator, startedAt, error) }
    onFailure(failure)
    null
}

/** Own the constructor result across the cancellable dispatcher hand-off, including late cancellation. */
internal suspend fun buildRuntimeOffMain(build: () -> AgentRuntime): AgentRuntime {
    var constructed: AgentRuntime? = null
    try {
        return withContext(Dispatchers.IO) { build().also { constructed = it } }
    } catch (error: CancellationException) {
        withContext(NonCancellable + Dispatchers.IO) { constructed?.close() }
        throw error
    }
}

private fun recordStartupFailure(
    runScope: RuntimeRunScope,
    recorders: RuntimeRunRecorders,
    coordinator: SessionCoordinator,
    startedAt: Long?,
    error: Exception,
): RuntimeStartupFailure {
    val message = "Runtime initialization failed: ${redactToolPayload(error.message ?: error.javaClass.simpleName)}"
    val now = System.currentTimeMillis()
    val summary = formatRunSummary(now - (startedAt ?: now), RunOutcome.FAILED)
    recorders.runEvidenceRecorder.recordRuntimeFailure()
    recorders.turnRecorder.recordRuntimeFailure(message)
    // No Kernel run or EventLog writer exists yet: never synthesize RUN_FINISHED or COMPLETE.
    runCatching { recorders.runEvidenceRecorder.write() }
    runCatching { coordinator.appendTurn(recorders.turnRecorder.snapshot()) }
    return RuntimeStartupFailure(
        snapshot = ActiveRunSnapshot(
            sessionId = runScope.sessionId, runId = runScope.requestId, requestId = runScope.requestId,
            isActive = false, startedAtEpochMillis = null, lastRunSummary = summary,
            lifecycleStatus = RuntimeRunStatus.FAILED.name, completionOutcome = RuntimeCompletionOutcome.UNKNOWN.name,
            logHealth = SessionEventLogHealthStatus.UNKNOWN.name,
        ),
        message = message,
    )
}
