@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.WorkspaceExecutionResult
import cc.noharry.agent.kernel.api.model.ModelEvent
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit
import cc.noharry.agent.kernel.api.ToolUse

class NativeReadOnlyToolBatchExecutor(
    private val toolUseController: NativeToolUseController,
    private val sessionRecorder: LoopSessionRecorder,
) {
    suspend fun handle(
        batch: List<ModelEvent.ToolCall>,
        context: RoundCollectContext,
    ): RoundOutcome? = coroutineScope {
        for (event in batch) {
            context.budgetTracker.recordToolCall()?.let { return@coroutineScope RoundOutcome.Failed(it) }
        }

        val allowed = mutableListOf<Deferred<ReadOnlyToolExecution>>()
        for (event in batch) {
            val source = context.toolSet.sourceFor(event.name) ?: DynamicToolSource.NATIVE_READ
            context.summaryBuilder.recordToolCall(event.name, event.argumentsJson)
            sessionRecorder.recordToolStarted(context.toolContext, event, source)
            context.publicEvents.recordStarted(event.id, event.name, event.argumentsJson)
            val prepareStart = System.nanoTime()
            val reasoningContent = context.nextReasoningContentForHistory()
            when (val prepared = prepare(event, source, context, reasoningContent)) {
                is PreparedToolUse.Allowed -> {
                    val call = AllowedReadOnlyToolCall(
                        event = event,
                        toolUse = prepared.toolUse,
                        source = source,
                        reasoningContent = reasoningContent,
                        prepareDurationMs = elapsedMs(prepareStart),
                        readyAtNanos = System.nanoTime(),
                    )
                    allowed += async(Dispatchers.IO) { execute(call, context) }
                }
                is PreparedToolUse.Denied -> {
                    finishAllowed(allowed, context)
                    return@coroutineScope toolUseController.finishDeniedToolUse(
                        event = event,
                        toolResults = context.toolResults,
                        context = context.toolContext,
                        publicEvents = context.publicEvents,
                        source = source,
                        result = prepared.resultJson,
                        reasoningContent = reasoningContent,
                    )
                }
                is PreparedToolUse.RequiresConfirmation -> {
                    finishAllowed(allowed, context)
                    return@coroutineScope prepared.outcome
                }
            }
        }
        finishAllowed(allowed, context)
        null
    }

    private suspend fun prepare(
        event: ModelEvent.ToolCall,
        source: DynamicToolSource,
        context: RoundCollectContext,
        reasoningContent: String?,
    ): PreparedToolUse =
        toolUseController.prepareWorkspaceTool(
            event = event,
            context = context.toolContext,
            source = source,
            reasoningContent = reasoningContent,
        )

    private suspend fun finishAllowed(
        calls: List<Deferred<ReadOnlyToolExecution>>,
        context: RoundCollectContext,
    ) {
        if (calls.isEmpty()) return
        calls.forEach { result ->
            finishExecution(result.await(), context)
        }
    }

    private suspend fun execute(
        call: AllowedReadOnlyToolCall,
        context: RoundCollectContext,
    ): ReadOnlyToolExecution {
        val executeStart = System.nanoTime()
        val execution = toolUseController.executeWorkspaceToolWithTelemetry(call.toolUse, context.toolContext)
        val executeEnd = System.nanoTime()
        return ReadOnlyToolExecution(
            call = call,
            execution = execution.copy(
                telemetry = execution.telemetry.copy(
                    prepareDurationMs = call.prepareDurationMs,
                    batchWaitDurationMs = elapsedMs(call.readyAtNanos, executeStart),
                    executeDurationMs = elapsedMs(executeStart, executeEnd),
                ),
            ),
            executeEndNanos = executeEnd,
        )
    }

    private suspend fun finishExecution(
        result: ReadOnlyToolExecution,
        context: RoundCollectContext,
    ) {
        val call = result.call
        val finishStart = System.nanoTime()
        val execution = result.execution.copy(
            telemetry = result.execution.telemetry.copy(
                batchResultWaitDurationMs = elapsedMs(result.executeEndNanos, finishStart),
            ),
        )
        val summaryStart = System.nanoTime()
        context.summaryBuilder.recordToolResult(call.event.name, execution.resultJson)
        val telemetry = execution.telemetry.copy(summaryDurationMs = elapsedMs(summaryStart))
        toolUseController.finishAllowedToolUse(
            event = call.event,
            toolResults = context.toolResults,
            context = context.toolContext,
            publicEvents = context.publicEvents,
            toolUse = call.toolUse,
            source = call.source,
            result = execution.resultJson,
            reasoningContent = call.reasoningContent,
            telemetry = telemetry,
        )
    }
}

fun ModelEvent.ToolCall.isBatchableReadTool(toolSet: DynamicToolSet): Boolean =
    toolSet.sourceFor(name) == DynamicToolSource.NATIVE_READ &&
        name in BATCHABLE_READ_TOOL_NAMES

private data class AllowedReadOnlyToolCall(
    val event: ModelEvent.ToolCall,
    val toolUse: ToolUse,
    val source: DynamicToolSource,
    val reasoningContent: String?,
    val prepareDurationMs: Long,
    val readyAtNanos: Long,
)

private data class ReadOnlyToolExecution(
    val call: AllowedReadOnlyToolCall,
    val execution: WorkspaceExecutionResult,
    val executeEndNanos: Long,
)

private val BATCHABLE_READ_TOOL_NAMES = setOf("list_dir", "read_file", "grep", "search_tools")

private fun elapsedMs(startNanos: Long, endNanos: Long = System.nanoTime()): Long =
    TimeUnit.NANOSECONDS.toMillis((endNanos - startNanos).coerceAtLeast(0L))
