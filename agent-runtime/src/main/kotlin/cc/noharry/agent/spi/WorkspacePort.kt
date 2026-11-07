package cc.noharry.agent.spi

import cc.noharry.agent.spi.model.ModelRef

/**
 * Internal workspace capability used by the loop and its VFS adapters.
 */
internal interface WorkspacePort {
    /** Executes a native workspace tool and returns JSON plus optional telemetry. */
    suspend fun execute(
        toolName: String,
        argumentsJson: String,
        context: WorkspaceCallContext = WorkspaceCallContext(),
    ): WorkspaceExecutionResult
}

/** Runtime-owned call context for [WorkspacePort]. Independent of core/VFS types. */
internal data class WorkspaceCallContext(
    val sessionId: String = "",
    val runId: String = "",
    val requestId: String = "",
    val scene: String? = null,
    val modelRef: ModelRef? = null,
)

/** Workspace tool execution result. [resultJson] is the model-visible payload. */
internal data class WorkspaceExecutionResult(
    val resultJson: String,
    val telemetry: WorkspaceExecutionTelemetry = WorkspaceExecutionTelemetry(),
)

/** Timing and cache telemetry for a workspace tool execution. */
internal data class WorkspaceExecutionTelemetry(
    val resolveDurationMs: Long = 0,
    val materializeDurationMs: Long = 0,
    val scanDurationMs: Long = 0,
    val serializeDurationMs: Long = 0,
    val prepareDurationMs: Long = 0,
    val batchWaitDurationMs: Long = 0,
    val executeDurationMs: Long = 0,
    val batchResultWaitDurationMs: Long = 0,
    val summaryDurationMs: Long = 0,
    val publicFinishDurationMs: Long = 0,
    val schemaCacheHit: Boolean? = null,
)
