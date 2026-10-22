@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelImage
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.TimeUnit
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.ToolSource
import cc.noharry.agent.kernel.api.ToolUseResult
import cc.noharry.agent.kernel.api.WorkspaceExecutionResult
import cc.noharry.agent.kernel.api.WorkspaceExecutionTelemetry
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecToolBridge

class NativeToolUseController(
    private val mcpRegistry: ToolProviderRegistry,
    private val workspace: WorkspacePort?,
    private val networkToolBackend: NetworkToolPort = NetworkToolPort.disabled(),
    private val execEngine: ExecEngine? = null,
    private val hookChain: HookChainPort,
    private val mcpDebugTraceSink: RuntimeMcpDebugTraceSink = RuntimeMcpDebugTraceSink.None,
    private val sessionRecorder: LoopSessionRecorder = LoopSessionRecorder.noop(),
    private val toolExecutionLimiter: ToolExecutionLimiter = ToolExecutionLimiter(),
    private val failureBreaker: ToolCallFailureBreaker = ToolCallFailureBreaker(),
    private val obligationLedger: RuntimeObligationLedger = RuntimeObligationLedger(),
) {
    private val toolResultImages = ToolResultImageStash()

    suspend fun handleWorkspaceTool(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        source: DynamicToolSource,
        reasoningContent: String?,
        thinkingSignature: String? = null,
    ): RoundOutcome? =
        run {
            val prepareStart = System.nanoTime()
            when (val prepared = prepareWorkspaceTool(event, context, source, reasoningContent)) {
            is PreparedToolUse.Denied -> finishDeniedToolUse(
                event = event,
                toolResults = toolResults,
                context = context,
                publicEvents = publicEvents,
                source = source,
                result = prepared.resultJson,
                reasoningContent = reasoningContent,
            )
            is PreparedToolUse.RequiresConfirmation -> prepared.outcome
            is PreparedToolUse.Allowed -> {
                val prepareMs = elapsedMs(prepareStart)
                val executeStart = System.nanoTime()
                val execution = executeWorkspaceToolWithTelemetry(prepared.toolUse, context)
                val executionWithTiming = execution.copy(
                    telemetry = execution.telemetry.copy(
                        prepareDurationMs = prepareMs,
                        executeDurationMs = elapsedMs(executeStart),
                    ),
                )
                val summaryStart = System.nanoTime()
                summaryBuilder.recordToolResult(event.name, executionWithTiming.resultJson)
                val telemetry = executionWithTiming.telemetry.copy(summaryDurationMs = elapsedMs(summaryStart))
                finishAllowedToolUse(
                    event,
                    toolResults,
                    context,
                    publicEvents,
                    prepared.toolUse,
                    source,
                    executionWithTiming.resultJson,
                    reasoningContent,
                    telemetry,
                    thinkingSignature = thinkingSignature,
                )
            }
        }
    }

    suspend fun handleBackendTool(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        toolSet: DynamicToolSet,
        reasoningContent: String?,
        thinkingSignature: String? = null,
    ): RoundOutcome? {
        declareRequiredBackendObligation(context.runId, toolSet, event)
        return when (val authorization = prepareBackendTool(event, context, toolSet)) {
            is ToolAuthorization.Denied -> {
                recordDeniedBackendObligation(context.runId, toolSet, event, authorization.resultJson)
                finishDeniedToolUse(
                event,
                toolResults,
                context,
                publicEvents,
                DynamicToolSource.BACKEND,
                authorization.resultJson,
                reasoningContent,
                summaryBuilder,
                )
            }
            is ToolAuthorization.RequiresConfirmation -> requiresConfirmation(authorization, reasoningContent)
            is ToolAuthorization.Allowed -> {
                val backendToolName = toolSet.backendToolName(event.name) ?: event.name
                val result = try {
                    executeBackendToolUse(authorization.toolUse, context, backendToolName)
                } catch (cancelled: CancellationException) {
                    recordDeniedBackendObligation(
                        context.runId,
                        toolSet,
                        event,
                        cancelledToolResult(event.name),
                        status = cc.noharry.agent.kernel.api.RuntimeActionStatus.CANCELLED,
                    )
                    throw cancelled
                } catch (failure: RuntimeException) {
                    val failed = backendFailedResult(failure.message ?: "MCP provider call failed.", retryable = true)
                    recordBackendObligation(context.runId, toolSet, event, failed, success = false)
                    throw failure
                }
                recordBackendObligation(context.runId, toolSet, event, result)
                finishAllowedToolUse(
                    event,
                    toolResults,
                    context,
                    publicEvents,
                    authorization.toolUse,
                    DynamicToolSource.BACKEND,
                    result,
                    reasoningContent,
                    thinkingSignature = thinkingSignature,
                    summaryBuilder = summaryBuilder,
                )
            }
        }
    }

    suspend fun handleNetworkTool(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        reasoningContent: String?,
        thinkingSignature: String? = null,
    ): RoundOutcome? {
        return when (val authorization = hookChain.authorizeToolUse(event, DynamicToolSource.NETWORK, context)) {
            is ToolAuthorization.Denied -> {
                recordNetworkActivity(event, publicEvents, event.argumentsJson, NETWORK_ACTIVITY_HOOK_DENIED)
                finishDeniedToolUse(
                    event = event,
                    toolResults = toolResults,
                    context = context,
                    publicEvents = publicEvents,
                    source = DynamicToolSource.NETWORK,
                    result = authorization.resultJson,
                    reasoningContent = reasoningContent,
                )
            }
            is ToolAuthorization.RequiresConfirmation -> {
                recordNetworkActivity(event, publicEvents, event.argumentsJson, NETWORK_ACTIVITY_HOOK_CONFIRMATION)
                requiresConfirmation(authorization, reasoningContent)
            }
            is ToolAuthorization.Allowed -> finishAuthorizedNetworkTool(
                event,
                toolResults,
                context,
                summaryBuilder,
                publicEvents,
                authorization.toolUse,
                reasoningContent,
                thinkingSignature,
            )
        }
    }

    suspend fun handleExecTool(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        toolSet: DynamicToolSet,
        budgetTracker: LoopBudgetTracker,
        reasoningContent: String?,
        thinkingSignature: String? = null,
    ): RoundOutcome? {
        val authorization = hookChain.authorizeToolUse(event, DynamicToolSource.EXEC, context)
        return when (authorization) {
            is ToolAuthorization.Denied -> finishDeniedToolUse(
                event,
                toolResults,
                context,
                publicEvents,
                DynamicToolSource.EXEC,
                authorization.resultJson,
                reasoningContent,
                summaryBuilder,
            )
            is ToolAuthorization.RequiresConfirmation -> requiresConfirmation(authorization, reasoningContent)
            is ToolAuthorization.Allowed -> finishAllowedExecTool(
                event,
                toolResults,
                context,
                summaryBuilder,
                publicEvents,
                toolSet,
                budgetTracker,
                authorization.toolUse,
                reasoningContent,
                thinkingSignature,
            )
        }
    }

    private suspend fun finishAllowedExecTool(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        toolSet: DynamicToolSet,
        budgetTracker: LoopBudgetTracker,
        toolUse: ToolUse,
        reasoningContent: String?,
        thinkingSignature: String?,
    ): RoundOutcome? {
        val engine = execEngine ?: return finishDeniedToolUse(
            event,
            toolResults,
            context,
            publicEvents,
            DynamicToolSource.EXEC,
            execEngineNotConfiguredResult(),
            reasoningContent,
            summaryBuilder,
        )
        val request = execRequest(context.runId, toolUse.argumentsJson)
        val bridge = RuntimeExecToolBridge(context, summaryBuilder, publicEvents, toolSet, budgetTracker)
        val resultJson = try {
            val result = toolExecutionLimiter.withPermit { engine.run(request, bridge) }
            if (result.ok) {
                execSucceededResult(result.output)
            } else {
                execFailedResult(result.errorCode ?: RuntimeErrorCode.EXEC_FAILED.wireCode, result.message)
            }
        } catch (pause: ExecBridgePausedException) {
            return pause.outcome
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            execFailedResult(RuntimeErrorCode.EXEC_FAILED.wireCode, error.message ?: "Exec failed.", retryable = true)
        }
        summaryBuilder.recordToolResult(event.name, resultJson)
        if (!toolUseResult(resultJson).ok) {
            summaryBuilder.recordBackendToolResult(event.name, resultJson)
        }
        return finishAllowedToolUse(
            event = event,
            toolResults = toolResults,
            context = context,
            publicEvents = publicEvents,
            toolUse = toolUse,
            source = DynamicToolSource.EXEC,
            result = resultJson,
            reasoningContent = reasoningContent,
            thinkingSignature = thinkingSignature,
        )
    }

    private suspend fun finishAuthorizedNetworkTool(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        toolUse: ToolUse,
        reasoningContent: String?,
        thinkingSignature: String?,
    ): RoundOutcome? =
        when (val networkDecision = networkToolBackend.authorize(context.runId, toolUse.toolName, toolUse.argumentsJson)) {
            NetworkToolPolicyDecision.Allowed -> finishAllowedNetworkTool(
                event,
                toolResults,
                context,
                summaryBuilder,
                publicEvents,
                toolUse,
                reasoningContent,
                thinkingSignature,
            )
            is NetworkToolPolicyDecision.Denied -> {
                recordNetworkActivity(event, publicEvents, toolUse.argumentsJson, NETWORK_ACTIVITY_POLICY_DENIED)
                finishDeniedToolUse(
                    event,
                    toolResults,
                    context,
                    publicEvents,
                    DynamicToolSource.NETWORK,
                    networkDecision.resultJson,
                    reasoningContent,
                )
            }
            is NetworkToolPolicyDecision.RequiresConfirmation -> {
                recordNetworkActivity(event, publicEvents, toolUse.argumentsJson, NETWORK_ACTIVITY_CONFIRMATION_REQUIRED)
                RoundOutcome.RequiresConfirmation(
                    prompt = networkDecision.prompt,
                    pendingToolUse = PendingNativeToolUse(
                        toolUse = toolUse,
                        context = context,
                        backendToolName = null,
                        toolCallId = event.id,
                        reasoningContent = reasoningContent,
                    ),
                )
            }
        }

    private suspend fun finishAllowedNetworkTool(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        toolUse: ToolUse,
        reasoningContent: String?,
        thinkingSignature: String?,
    ): RoundOutcome? {
        val execution = executeNetworkToolWithTelemetry(toolUse)
        val activity = networkToolBackend.activityResult(execution.resultJson)
        recordNetworkActivity(
            event = event,
            publicEvents = publicEvents,
            argumentsJson = toolUse.argumentsJson,
            decision = NETWORK_ACTIVITY_ALLOWED,
            statusCode = activity.statusCode,
            truncated = activity.truncated,
        )
        summaryBuilder.recordToolResult(event.name, execution.resultJson)
        return finishAllowedToolUse(
            event = event,
            toolResults = toolResults,
            context = context,
            publicEvents = publicEvents,
            toolUse = toolUse,
            source = DynamicToolSource.NETWORK,
            result = execution.resultJson,
            reasoningContent = reasoningContent,
            telemetry = execution.telemetry,
            thinkingSignature = thinkingSignature,
        )
    }

    suspend fun prepareBackendTool(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
        toolSet: DynamicToolSet,
    ): ToolAuthorization {
        val arguments = when (val parsed = parseToolArguments(event.name, event.argumentsJson)) {
            is ParsedToolArguments.Invalid -> return ToolAuthorization.Denied(parsed.resultJson)
            is ParsedToolArguments.Valid -> parsed.arguments
        }
        val schemaJson = toolSet.schemaJson(event.name)
        when (val validation = validateBackendToolArguments(event.name, arguments, schemaJson)) {
            is ToolArgumentValidationResult.Invalid -> return ToolAuthorization.Denied(validation.resultJson())
            is ToolArgumentValidationResult.Valid -> Unit
        }
        val backendToolName = toolSet.backendToolName(event.name) ?: event.name
        return hookChain.authorizeToolUse(event, DynamicToolSource.BACKEND, context, backendToolName, arguments)
            .validatedBackendRewrite(event.name, schemaJson)
    }

    suspend fun prepareTerminalTool(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
    ): ToolAuthorization =
        hookChain.authorizeToolUse(event, DynamicToolSource.TERMINAL, context)

    suspend fun prepareInteractionTool(event: ModelEvent.ToolCall, context: MaterializerContext): ToolAuthorization =
        hookChain.authorizeToolUse(event, DynamicToolSource.INTERACTION, context)

    suspend fun recordTerminalToolUse(
        toolUse: ToolUse,
        resultJson: String,
        context: MaterializerContext,
    ) {
        hookChain.recordPostToolUse(toolUse, resultJson, context)
    }

    suspend fun executeBackendToolUse(
        toolUse: ToolUse,
        context: MaterializerContext,
        backendToolName: String?,
        permitAlreadyHeld: Boolean = false,
    ): String =
        executeBackendTool(toolUse, context, backendToolName, permitAlreadyHeld)

    suspend fun executeConfirmedToolUse(
        pending: PendingNativeToolUse,
        publicEvents: NativePublicEventBuffer,
    ): ConfirmedToolUseResult {
        val context = pending.context
        val confirmedToolUse = when (
            val decision = hookChain.preToolUseOutcome(
                pending.toolUse,
                context.toHookContext(approvedConfirmation = true),
            )
        ) {
            is PreToolUseOutcome.Allowed -> decision.toolUse
            is PreToolUseOutcome.Denied -> return confirmedResult(
                hookDeniedResult(decision.code, decision.reason),
                pending.toolUse.source,
            )
            is PreToolUseOutcome.RequiresConfirmation -> return confirmedResult(
                hookDeniedResult(RuntimeErrorCode.HOOK_DENIED.wireCode, "Confirmation still required."),
                pending.toolUse.source,
            )
            is PreToolUseOutcome.BlockedAndContinue -> return confirmedResult(
                hookBlockedAndContinueResult(decision.reason, decision.prompt),
                pending.toolUse.source,
            )
        }
        val confirmedEvent = pending.toToolCallEvent(confirmedToolUse)
        publicEvents.recordStarted(confirmedEvent.id, confirmedEvent.name, confirmedEvent.argumentsJson)
        val execution = when (confirmedToolUse.source) {
            ToolSource.BACKEND -> executeBackendToolUse(confirmedToolUse, context, pending.backendToolName)
                .let(::WorkspaceExecutionResult)
            ToolSource.NATIVE_READ,
            ToolSource.NATIVE_WRITE,
            -> executeWorkspaceToolWithTelemetry(confirmedToolUse, context)
            ToolSource.NETWORK -> {
                networkToolBackend.rememberConfirmed(context.runId, confirmedToolUse.argumentsJson)
                executeNetworkToolWithTelemetry(confirmedToolUse)
            }
            ToolSource.EXEC -> hookDeniedResult(
                RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode,
                "Confirmed exec tool source is not executable from a nested confirmation.",
            ).let(::WorkspaceExecutionResult)
            else -> hookDeniedResult(
                RuntimeErrorCode.UNSUPPORTED_NATIVE_TOOL.wireCode,
                "Confirmed tool source is not executable.",
            ).let(::WorkspaceExecutionResult)
        }
        if (confirmedToolUse.source == ToolSource.NETWORK) {
            val activity = networkToolBackend.activityResult(execution.resultJson)
            recordNetworkActivity(
                event = confirmedEvent,
                publicEvents = publicEvents,
                argumentsJson = confirmedToolUse.argumentsJson,
                decision = NETWORK_ACTIVITY_ALLOWED,
                statusCode = activity.statusCode,
                truncated = activity.truncated,
            )
        }
        publicEvents.recordFinished(confirmedEvent.id, confirmedEvent.name, execution.resultJson)
        hookChain.recordPostToolUse(confirmedToolUse, execution.resultJson, context)
        val images = toolResultImages.take(confirmedEvent.id)
        sessionRecorder.recordToolFinishedWithImages(
            context = context,
            event = confirmedEvent,
            source = confirmedToolUse.source.toDynamicToolSource(),
            resultJson = execution.resultJson,
            images = images,
            telemetry = execution.telemetry,
        )
        return confirmedResult(execution.resultJson, confirmedToolUse.source, images)
    }

    suspend fun prepareWorkspaceTool(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
        source: DynamicToolSource,
        reasoningContent: String?,
    ): PreparedToolUse =
        when (val authorization = hookChain.authorizeToolUse(event, source, context)) {
            is ToolAuthorization.Denied -> PreparedToolUse.Denied(authorization.resultJson)
            is ToolAuthorization.RequiresConfirmation -> PreparedToolUse.RequiresConfirmation(
                requiresConfirmation(authorization, reasoningContent),
            )
            is ToolAuthorization.Allowed -> PreparedToolUse.Allowed(authorization.toolUse)
        }

    suspend fun executeWorkspaceTool(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
    ): String =
        executeWorkspaceToolWithTelemetry(event, context).resultJson

    suspend fun executeWorkspaceToolWithTelemetry(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
    ): WorkspaceExecutionResult =
        executeWorkspaceToolWithTelemetry(
            ToolUse(
                toolName = event.name,
                argumentsJson = event.argumentsJson,
                source = DynamicToolSource.NATIVE_READ.toToolSource(),
                toolCallId = event.id,
            ),
            context,
        )

    suspend fun executeWorkspaceToolWithTelemetry(
        toolUse: ToolUse,
        context: MaterializerContext,
        permitAlreadyHeld: Boolean = false,
    ): WorkspaceExecutionResult {
        val configuredWorkspace = workspace ?: return WorkspaceExecutionResult(workspaceMissingResult())
        val execute = suspend {
            configuredWorkspace.execute(toolUse.toolName, toolUse.argumentsJson, context.toWorkspaceCallContext())
        }
        return if (permitAlreadyHeld) execute() else toolExecutionLimiter.withPermit(execute)
    }

    suspend fun executeNetworkToolWithTelemetry(
        toolUse: ToolUse,
        permitAlreadyHeld: Boolean = false,
    ): WorkspaceExecutionResult {
        val execute = suspend {
            WorkspaceExecutionResult(networkToolBackend.execute(toolUse.toolName, toolUse.argumentsJson))
        }
        return if (permitAlreadyHeld) execute() else toolExecutionLimiter.withPermit(execute)
    }

    suspend fun finishAllowedToolUse(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        publicEvents: NativePublicEventBuffer,
        toolUse: ToolUse,
        source: DynamicToolSource,
        result: String,
        reasoningContent: String?,
        telemetry: WorkspaceExecutionTelemetry = WorkspaceExecutionTelemetry(),
        thinkingSignature: String? = null,
        summaryBuilder: NativeLoopSummaryBuilder? = null,
    ): RoundOutcome? {
        val publicFinishStart = System.nanoTime()
        val images = toolResultImages.take(event.id)
        publicEvents.recordFinished(event.id, event.name, result)
        sessionRecorder.recordToolFinishedWithImages(
            context = context,
            event = event,
            source = source,
            resultJson = result,
            images = images,
            telemetry = telemetry.copy(publicFinishDurationMs = elapsedMs(publicFinishStart)),
        )
        if (source == DynamicToolSource.BACKEND || source == DynamicToolSource.EXEC) {
            summaryBuilder?.recordBackendToolResult(event.name, result)
        }
        hookChain.recordPostToolUse(toolUse, result, context)
        appendToolResult(toolResults, event, result, reasoningContent, thinkingSignature, images)
        return null
    }

    private suspend fun recordNetworkActivity(
        event: ModelEvent.ToolCall,
        publicEvents: NativePublicEventBuffer,
        argumentsJson: String,
        decision: String,
        statusCode: Int? = null,
        truncated: Boolean = false,
    ) {
        publicEvents.recordNetworkActivity(
            toolCallId = event.id,
            toolName = event.name,
            domain = networkToolBackend.activityDomain(event.name, argumentsJson),
            decision = decision,
            statusCode = statusCode,
            truncated = truncated,
        )
    }

    private suspend fun executeBackendTool(
        toolUse: ToolUse,
        context: MaterializerContext,
        backendToolName: String?,
        permitAlreadyHeld: Boolean = false,
    ): String {
        if (!mcpRegistry.isConfigured) return mcpProviderNotConfiguredResult()
        val qualifiedName = backendToolName ?: toolUse.toolName
        failureBreaker.trippedFailure(context.runId, qualifiedName, toolUse.argumentsJson)?.let { tripped ->
            return backendRepeatedFailureResult(qualifiedName, tripped)
        }
        val execute = suspend { executeRoutedBackendTool(toolUse, context, qualifiedName) }
        val result = try {
            if (permitAlreadyHeld) execute() else toolExecutionLimiter.withPermit(execute)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            backendFailedResult(error.message ?: "MCP provider call failed.", retryable = true)
        }
        failureBreaker.record(context.runId, qualifiedName, toolUse.argumentsJson, backendToolCallResult(result))
        return result
    }

    private suspend fun executeRoutedBackendTool(
        toolUse: ToolUse,
        context: MaterializerContext,
        qualifiedName: String,
    ): String {
        val routed = mcpRegistry.callTool(qualifiedName, toolUse.argumentsJson, context)
            ?: return unknownBackendToolResult(qualifiedName)
        routed.result.trace?.let { trace ->
            mcpDebugTraceSink.record(
                McpDebugCallRecord(
                    runId = context.runId,
                    toolName = routed.tool.qualifiedName,
                    callJson = mcpToolsCallJson(
                        id = context.requestId.ifBlank { context.runId },
                        toolName = routed.tool.originalToolName,
                        argumentsJson = toolUse.argumentsJson,
                    ),
                    validationOk = trace.validationOk,
                    validationCode = trace.validationCode,
                    validationMessage = trace.validationMessage,
                    validationSchemaPath = trace.validationSchemaPath,
                ),
            )
        }
        toolResultImages.put(toolUse.toolCallId, routed.result.images)
        return routed.result.toBackendToolResult()
    }

    suspend fun finishDeniedToolUse(
        event: ModelEvent.ToolCall,
        toolResults: MutableList<ModelMessage>,
        context: MaterializerContext,
        publicEvents: NativePublicEventBuffer,
        source: DynamicToolSource,
        result: String,
        reasoningContent: String?,
        summaryBuilder: NativeLoopSummaryBuilder? = null,
    ): RoundOutcome? {
        publicEvents.recordFinished(event.id, event.name, result)
        sessionRecorder.recordToolFinished(context, event, source, result)
        if (source == DynamicToolSource.BACKEND) {
            summaryBuilder?.recordBackendToolResult(event.name, result)
        }
        appendToolResult(toolResults, event, result, reasoningContent)
        return null
    }

    fun declareRequiredBackendObligation(
        runId: String,
        toolSet: DynamicToolSet,
        event: ModelEvent.ToolCall,
    ) {
        val descriptor = toolSet.actionDescriptor(event.name) ?: return
        obligationLedger.declareRequiredMutating(runId, descriptor, event.argumentsJson)
    }

    fun recordBackendObligation(
        runId: String,
        toolSet: DynamicToolSet,
        event: ModelEvent.ToolCall,
        resultJson: String,
        success: Boolean = true,
    ) {
        val descriptor = toolSet.actionDescriptor(event.name) ?: return
        if (descriptor.effectKind != cc.noharry.agent.kernel.api.RuntimeEffectKind.MUTATING) return
        if (descriptor.impact != cc.noharry.agent.kernel.api.CompletionImpact.REQUIRED_ON_CALL) return
        val obligationId = obligationLedger.obligationId(runId, descriptor, event.argumentsJson)
        obligationLedger.recordOutcome(
            normalizeTransportResult(
                obligationId = obligationId,
                actionName = descriptor.actionName,
                success = success,
                resultJson = resultJson,
            ),
        )
    }

    fun recordDeniedBackendObligation(
        runId: String,
        toolSet: DynamicToolSet,
        event: ModelEvent.ToolCall,
        resultJson: String,
        status: cc.noharry.agent.kernel.api.RuntimeActionStatus = cc.noharry.agent.kernel.api.RuntimeActionStatus.DENIED,
    ) {
        val descriptor = toolSet.actionDescriptor(event.name) ?: return
        if (descriptor.effectKind != cc.noharry.agent.kernel.api.RuntimeEffectKind.MUTATING) return
        if (descriptor.impact != cc.noharry.agent.kernel.api.CompletionImpact.REQUIRED_ON_CALL) return
        val obligationId = obligationLedger.obligationId(runId, descriptor, event.argumentsJson)
        obligationLedger.recordOutcome(
            cc.noharry.agent.kernel.api.RuntimeActionOutcome(
                obligationId = obligationId,
                actionName = descriptor.actionName,
                status = status,
                commitStatus = cc.noharry.agent.kernel.api.RuntimeCommitStatus.NOT_COMMITTED,
                safeCode = normalizeTransportResult(obligationId, descriptor.actionName, false, resultJson).safeCode,
            ),
        )
    }

    private fun requiresConfirmation(
        authorization: ToolAuthorization.RequiresConfirmation,
        reasoningContent: String?,
    ): RoundOutcome =
        RoundOutcome.RequiresConfirmation(
            prompt = authorization.prompt,
            pendingToolUse = authorization.pending.copy(reasoningContent = reasoningContent),
        )

    private fun appendToolResult(
        toolResults: MutableList<ModelMessage>,
        event: ModelEvent.ToolCall,
        result: String,
        reasoningContent: String?,
        thinkingSignature: String? = null,
        images: List<ModelImage> = emptyList(),
    ) {
        toolResults += event.toAssistantToolCallMessage(reasoningContent, thinkingSignature)
        toolResults += event.toToolResultMessage(result, images)
    }

    private inner class RuntimeExecToolBridge(
        private val context: MaterializerContext,
        private val summaryBuilder: NativeLoopSummaryBuilder,
        private val publicEvents: NativePublicEventBuffer,
        private val toolSet: DynamicToolSet,
        private val budgetTracker: LoopBudgetTracker,
    ) : ExecToolBridge {
        override suspend fun callTool(name: String, argumentsJson: String): String {
            budgetTracker.recordToolCall()?.let { failure ->
                summaryBuilder.recordToolCall(name, argumentsJson)
                return execFailedResult(failure.code, failure.message).also { result ->
                    summaryBuilder.recordBackendToolResult(name, result)
                }
            }
            val event = ModelEvent.ToolCall(
                id = "exec-${context.runId}-${nextExecToolId()}",
                name = name,
                argumentsJson = argumentsJson,
            )
            val source = toolSet.sourceFor(name)
            summaryBuilder.recordToolCall(name, argumentsJson)
            sessionRecorder.recordToolStarted(context, event, source)
            publicEvents.recordStarted(event.id, name, argumentsJson)
            return executeExecNestedTool(event, source, context, summaryBuilder, publicEvents, toolSet)
        }

        override suspend fun emitOutput(text: String) {
            publicEvents.recordExecOutput(text)
        }
    }

    private suspend fun executeExecNestedTool(
        event: ModelEvent.ToolCall,
        source: DynamicToolSource?,
        context: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        toolSet: DynamicToolSet,
    ): String =
        when (source) {
            DynamicToolSource.NATIVE_READ,
            DynamicToolSource.NATIVE_WRITE,
            -> finishExecNestedAuthorizedTool(event, context, publicEvents, source) {
                executeWorkspaceToolWithTelemetry(it, context, permitAlreadyHeld = true)
            }
            DynamicToolSource.NETWORK -> finishExecNestedAuthorizedTool(event, context, publicEvents, source) {
                executeNetworkToolWithTelemetry(it, permitAlreadyHeld = true)
            }
            DynamicToolSource.BACKEND -> finishExecNestedBackendTool(event, context, publicEvents, toolSet)
            DynamicToolSource.NATIVE_TODO,
            DynamicToolSource.INTERACTION,
            DynamicToolSource.TERMINAL,
            DynamicToolSource.EXEC,
            null,
            -> unsupportedToolResult(event.name).also {
                publicEvents.recordFinished(event.id, event.name, it)
                sessionRecorder.recordToolFinished(context, event, source, it)
            }
        }.also { result ->
            summaryBuilder.recordToolResult(event.name, result)
            if (source == DynamicToolSource.BACKEND || source == null) {
                summaryBuilder.recordBackendToolResult(event.name, result)
            }
        }

    private suspend fun finishExecNestedBackendTool(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
        publicEvents: NativePublicEventBuffer,
        toolSet: DynamicToolSet,
    ): String {
        declareRequiredBackendObligation(context.runId, toolSet, event)
        return when (val authorization = prepareBackendTool(event, context, toolSet)) {
            is ToolAuthorization.Denied -> {
                recordDeniedBackendObligation(context.runId, toolSet, event, authorization.resultJson)
                finishExecNestedDenied(event, context, publicEvents, DynamicToolSource.BACKEND, authorization.resultJson)
            }
            is ToolAuthorization.RequiresConfirmation -> throw ExecBridgePausedException(requiresConfirmation(authorization, null))
            is ToolAuthorization.Allowed -> {
                val backendToolName = toolSet.backendToolName(event.name) ?: event.name
                val result = try {
                    executeBackendToolUse(
                        authorization.toolUse,
                        context,
                        backendToolName,
                        permitAlreadyHeld = true,
                    )
                } catch (cancelled: CancellationException) {
                    recordDeniedBackendObligation(
                        context.runId,
                        toolSet,
                        event,
                        cancelledToolResult(event.name),
                        status = cc.noharry.agent.kernel.api.RuntimeActionStatus.CANCELLED,
                    )
                    throw cancelled
                } catch (failure: RuntimeException) {
                    val failed = backendFailedResult(failure.message ?: "MCP provider call failed.", retryable = true)
                    recordBackendObligation(context.runId, toolSet, event, failed, success = false)
                    throw failure
                }
                recordBackendObligation(context.runId, toolSet, event, result)
                hookChain.recordPostToolUse(authorization.toolUse, result, context)
                publicEvents.recordFinished(event.id, event.name, result)
                // Exec scripts get text only; the EventLog still keeps the images and the stash drains.
                val images = toolResultImages.take(event.id)
                sessionRecorder.recordToolFinishedWithImages(context, event, DynamicToolSource.BACKEND, result, images)
                result
            }
        }
    }

    private suspend fun finishExecNestedAuthorizedTool(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
        publicEvents: NativePublicEventBuffer,
        source: DynamicToolSource,
        execute: suspend (ToolUse) -> WorkspaceExecutionResult,
    ): String =
        when (val authorization = hookChain.authorizeToolUse(event, source, context)) {
            is ToolAuthorization.Denied -> finishExecNestedDenied(event, context, publicEvents, source, authorization.resultJson)
            is ToolAuthorization.RequiresConfirmation -> throw ExecBridgePausedException(requiresConfirmation(authorization, null))
            is ToolAuthorization.Allowed -> {
                val execution = execute(authorization.toolUse)
                hookChain.recordPostToolUse(authorization.toolUse, execution.resultJson, context)
                publicEvents.recordFinished(event.id, event.name, execution.resultJson)
                sessionRecorder.recordToolFinished(context, event, source, execution.resultJson, execution.telemetry)
                execution.resultJson
            }
        }

    private suspend fun finishExecNestedDenied(
        event: ModelEvent.ToolCall,
        context: MaterializerContext,
        publicEvents: NativePublicEventBuffer,
        source: DynamicToolSource?,
        resultJson: String,
    ): String {
        publicEvents.recordFinished(event.id, event.name, resultJson)
        sessionRecorder.recordToolFinished(context, event, source, resultJson)
        return resultJson
    }
}

sealed interface PreparedToolUse {
    data class Allowed(val toolUse: ToolUse) : PreparedToolUse

    data class Denied(val resultJson: String) : PreparedToolUse

    data class RequiresConfirmation(val outcome: RoundOutcome) : PreparedToolUse
}

data class ConfirmedToolUseResult(
    val resultJson: String,
    val parsed: ToolUseResult,
    /** Images the provider returned with the confirmed call, for the tool-result message. */
    val images: List<ModelImage> = emptyList(),
)

private fun confirmedResult(
    resultJson: String,
    source: ToolSource,
    images: List<ModelImage> = emptyList(),
): ConfirmedToolUseResult =
    ConfirmedToolUseResult(
        resultJson = resultJson,
        parsed = if (source == ToolSource.BACKEND) {
            backendToolCallResult(resultJson)
        } else {
            toolUseResult(resultJson)
        },
        images = images,
    )

private fun ToolAuthorization.validatedBackendRewrite(
    toolName: String,
    schemaJson: String?,
): ToolAuthorization =
    when (this) {
        is ToolAuthorization.Allowed -> validateBackendToolUse(toolUse, toolName, schemaJson) ?: this
        is ToolAuthorization.RequiresConfirmation -> validateBackendToolUse(pending.toolUse, toolName, schemaJson) ?: this
        is ToolAuthorization.Denied -> this
    }

private fun validateBackendToolUse(
    toolUse: ToolUse,
    toolName: String,
    schemaJson: String?,
): ToolAuthorization.Denied? =
    when (val validation = validateBackendToolArguments(toolName, toolUse.argumentsJson, schemaJson)) {
        is ToolArgumentValidationResult.Invalid -> ToolAuthorization.Denied(validation.resultJson())
        is ToolArgumentValidationResult.Valid -> null
    }

private fun PendingNativeToolUse.toToolCallEvent(toolUse: ToolUse): ModelEvent.ToolCall =
    ModelEvent.ToolCall(
        id = toolCallId,
        name = toolUse.toolName,
        argumentsJson = toolUse.argumentsJson,
    )

private fun ToolSource.toDynamicToolSource(): DynamicToolSource? = when (this) {
    ToolSource.NATIVE_READ -> DynamicToolSource.NATIVE_READ
    ToolSource.NATIVE_WRITE -> DynamicToolSource.NATIVE_WRITE
    ToolSource.NETWORK -> DynamicToolSource.NETWORK
    ToolSource.EXEC -> DynamicToolSource.EXEC
    ToolSource.NATIVE_TODO -> DynamicToolSource.NATIVE_TODO
    ToolSource.BACKEND -> DynamicToolSource.BACKEND
    ToolSource.TERMINAL -> DynamicToolSource.TERMINAL
    ToolSource.INTERACTION -> DynamicToolSource.INTERACTION
}

internal fun cc.noharry.agent.kernel.api.ToolCallResult.toBackendToolResult(): String =
    if (success || isMcpBusinessResult(resultJson) && !backendToolCallResult(resultJson).ok) resultJson
    else backendFailedResult(resultJson, retryable)

private fun mcpToolsCallJson(id: String, toolName: String, argumentsJson: String): String =
    """{"jsonrpc":"2.0","id":"${id.escapeJson()}","method":"tools/call","params":{"name":"${toolName.escapeJson()}","arguments":${argumentsJson.asJsonObjectLiteral()}}}"""

private fun String.asJsonObjectLiteral(): String {
    val trimmed = trim()
    return if (trimmed.startsWith("{") && trimmed.endsWith("}")) trimmed else "{}"
}

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"")

private fun elapsedMs(startNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis((System.nanoTime() - startNanos).coerceAtLeast(0L))

private const val NETWORK_ACTIVITY_ALLOWED = "allowed"
private const val NETWORK_ACTIVITY_CONFIRMATION_REQUIRED = "confirmation_required"
private const val NETWORK_ACTIVITY_HOOK_CONFIRMATION = "hook_confirmation_required"
private const val NETWORK_ACTIVITY_HOOK_DENIED = "hook_denied"
private const val NETWORK_ACTIVITY_POLICY_DENIED = "policy_denied"

private class ExecBridgePausedException(val outcome: RoundOutcome) : RuntimeException("Exec bridge paused.")

private fun execRequest(runId: String, argumentsJson: String): ExecRequest {
    val json = when (val parsed = parseToolArguments("exec_js", argumentsJson)) {
        is ParsedToolArguments.Valid -> parsed.arguments
        is ParsedToolArguments.Invalid -> return ExecRequest(runId, "", DEFAULT_EXEC_TIMEOUT_MS, DEFAULT_EXEC_OUTPUT_TOKEN_BUDGET)
    }
    return ExecRequest(
        runId = runId,
        code = json.stringField("code").orEmpty(),
        timeoutMillis = json.stringField("timeout_ms")?.toLongOrNull() ?: DEFAULT_EXEC_TIMEOUT_MS,
        outputTokenBudget = json.stringField("output_token_budget")?.toIntOrNull() ?: DEFAULT_EXEC_OUTPUT_TOKEN_BUDGET,
    )
}

private fun kotlinx.serialization.json.JsonObject.stringField(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private var execToolId = 0

private fun nextExecToolId(): Int = synchronized(NativeToolUseController::class.java) {
    execToolId += 1
    execToolId
}

private const val DEFAULT_EXEC_TIMEOUT_MS = 5_000L
private const val DEFAULT_EXEC_OUTPUT_TOKEN_BUDGET = 512
