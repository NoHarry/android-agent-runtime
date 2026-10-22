package cc.noharry.agent.kernel.api

import cc.noharry.agent.kernel.api.model.ModelRef

/**
 * Plugin-facing workspace capability. Concrete VFS adapters live in runtime;
 * Kernel core and plugins depend only on this contract and its string/API DTOs.
 */
@ExperimentalAgentRuntimeApi
interface WorkspacePort {
    /** Executes a native workspace tool and returns JSON plus optional telemetry. */
    suspend fun execute(
        toolName: String,
        argumentsJson: String,
        context: WorkspaceCallContext = WorkspaceCallContext(),
    ): WorkspaceExecutionResult
}

/** API-owned call context for [WorkspacePort]. Independent of core/VFS types. */
@ExperimentalAgentRuntimeApi
data class WorkspaceCallContext(
    val sessionId: String = "",
    val runId: String = "",
    val requestId: String = "",
    val scene: String? = null,
    val modelRef: ModelRef? = null,
)

/** Workspace tool execution result. [resultJson] is the model-visible payload. */
@ExperimentalAgentRuntimeApi
data class WorkspaceExecutionResult(
    val resultJson: String,
    val telemetry: WorkspaceExecutionTelemetry = WorkspaceExecutionTelemetry(),
)

/** Timing and cache telemetry for a workspace tool execution. */
@ExperimentalAgentRuntimeApi
data class WorkspaceExecutionTelemetry(
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
