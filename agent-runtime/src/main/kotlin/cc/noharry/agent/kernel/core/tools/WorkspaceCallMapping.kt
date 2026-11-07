@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.spi.WorkspaceCallContext

internal fun MaterializerContext.toWorkspaceCallContext(): WorkspaceCallContext =
    WorkspaceCallContext(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef,
        approvalPolicy = approvalPolicy,
        approvalState = approvalState,
    )

internal fun WorkspaceCallContext.toMaterializerContext(
    timeoutMillis: Long = DEFAULT_MATERIALIZER_TIMEOUT_MILLIS,
): MaterializerContext =
    MaterializerContext(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef,
        timeoutMillis = timeoutMillis,
        approvalPolicy = approvalPolicy,
        approvalState = approvalState,
    )
