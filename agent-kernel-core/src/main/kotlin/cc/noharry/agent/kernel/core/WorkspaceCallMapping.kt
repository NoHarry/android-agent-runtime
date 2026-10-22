@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.WorkspaceCallContext

fun MaterializerContext.toWorkspaceCallContext(): WorkspaceCallContext =
    WorkspaceCallContext(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef,
    )

fun WorkspaceCallContext.toMaterializerContext(
    timeoutMillis: Long = DEFAULT_MATERIALIZER_TIMEOUT_MILLIS,
): MaterializerContext =
    MaterializerContext(
        sessionId = sessionId,
        runId = runId,
        requestId = requestId,
        scene = scene,
        modelRef = modelRef,
        timeoutMillis = timeoutMillis,
    )
