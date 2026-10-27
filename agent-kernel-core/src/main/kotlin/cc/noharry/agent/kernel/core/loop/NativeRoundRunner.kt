@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelClientException
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRef
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelResponseMetadata
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.context.TodoState
import cc.noharry.agent.kernel.core.context.TodoStorePort
import cc.noharry.agent.kernel.core.context.preparationElapsedMs
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_STATUS_CANCELLED
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_STATUS_COMPLETED
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_STATUS_FAILED
import cc.noharry.agent.kernel.core.state.StoredModelCall
import cc.noharry.agent.kernel.core.tools.BackendToolClassification
import cc.noharry.agent.kernel.core.tools.CALL_TOOL_NAME
import cc.noharry.agent.kernel.core.tools.DynamicToolRegistry
import cc.noharry.agent.kernel.core.tools.DynamicToolSet
import cc.noharry.agent.kernel.core.tools.DynamicToolSource
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.ToolActivationStore
import cc.noharry.agent.kernel.core.tools.ToolExecutionLimiter
import cc.noharry.agent.kernel.core.tools.cancelledToolResult
import cc.noharry.agent.kernel.core.tools.invalidCallToolResult
import cc.noharry.agent.kernel.core.tools.requiresObligationLedger
import cc.noharry.agent.kernel.core.tools.unwrapCallTool
import cc.noharry.agent.kernel.core.tools.withWireCalls
import cc.noharry.agent.kernel.core.trace.LoopSessionRecorder
import cc.noharry.agent.kernel.core.trace.ModelCallProbe
import cc.noharry.agent.kernel.core.trace.NativePublicEventBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect

class NativeRoundRunner(
    private val toolRegistry: DynamicToolRegistry,
    private val toolUseController: NativeToolUseController,
    private val activationStore: ToolActivationStore,
    private val todoStore: TodoStorePort? = null,
    private val toolExecutionLimiter: ToolExecutionLimiter = ToolExecutionLimiter(),
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val sessionRecorder: LoopSessionRecorder = LoopSessionRecorder.noop(),
    private val steeringQueue: RuntimeSteeringQueue = RuntimeSteeringQueue(),
    private val imageView: ModelImageRequestView = ModelImageRequestView(),
    /** A subagent's run: its result is what it passes to finish, so a text-only reply is nudged once first. */
    private val textNeedsFinish: Boolean = false,
) {
    private val userInteraction = NativeUserInteraction(steeringQueue)
    private val readOnlyBatchExecutor = NativeReadOnlyToolBatchExecutor(toolUseController, sessionRecorder)

    suspend fun collect(
        client: ModelClient,
        messages: List<ModelMessage>,
        toolContext: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        systemPrompt: String,
        budgetTracker: LoopBudgetTracker,
        modelRef: ModelRef?,
        allowFinishResponseRetry: Boolean = false,
        allowEmptyCompletionContinue: Boolean = false,
        allowObligationCorrection: Boolean = false,
        obligationLedger: RuntimeObligationLedger = RuntimeObligationLedger(),
    ): RoundOutcome {
        summaryBuilder.recordModelRound()
        val thinking = StringBuilder()
        val text = StringBuilder()
        var thinkingSignature: String? = null
        val toolResults = mutableListOf<ModelMessage>()
        val pendingToolCalls = mutableListOf<ModelEvent.ToolCall>()
        val cancellationHistory = CancellationLegalHistoryTracker()
        var immediateOutcome: RoundOutcome? = null
        val toolSchemaStarted = System.nanoTime()
        val toolSet = toolRegistry.tools(toolContext)
        sessionRecorder.recordPreparationTimings(toolContext, mapOf("toolSchemaAssemblyMs" to preparationElapsedMs(toolSchemaStarted)))
        val modelRequest = ModelRequest(
            messages = imageView.apply(messages),
            tools = toolSet.modelTools,
            system = systemPrompt,
            modelRef = modelRef,
            promptCacheKey = modelPromptCacheKey(toolContext.sessionId),
        )
        val modelProbe = ModelCallProbe(
            call = StoredModelCall(
                runId = toolContext.runId,
                sessionId = toolContext.sessionId,
                callId = sessionRecorder.nextModelCallId(toolContext.runId),
                status = SESSION_MODEL_CALL_STATUS_COMPLETED,
                modelRef = modelRef?.wireValue().orEmpty(),
                startedAtEpochMillis = nowEpochMillis(),
                finishedAtEpochMillis = 0L,
                durationMs = 0L,
                messageCount = modelRequest.messages.size,
                toolCount = modelRequest.tools.size,
            ),
            nowEpochMillis = nowEpochMillis,
            recorder = sessionRecorder,
        )
        summaryBuilder.recordToolRegistrySignals(toolSet)
        val callUsage = ModelCallUsage()
        val wireCalls = mutableMapOf<String, ModelEvent.ToolCall>()
        try {
            budgetTracker.beginModelCall()
            sessionRecorder.recordModelRequest(toolContext, modelRequest)
            steeringQueue.projectPublic(toolContext.runId, publicEvents)
            client.complete(modelRequest)
                .collect roundEvents@{ event ->
                    steeringQueue.projectPublic(toolContext.runId, publicEvents)
                    sessionRecorder.recordModelStreamEvent(toolContext, event)
                    modelProbe.recordEvent(event)
                    if (event == ModelEvent.Completed) {
                        modelProbe.finish(SESSION_MODEL_CALL_STATUS_COMPLETED)
                    }
                    if (event is ModelEvent.ThinkingDelta) {
                        thinking.append(event.text)
                        event.signature?.takeIf { it.isNotBlank() }?.let { thinkingSignature = it }
                        publicEvents.recordThinking(event.text)
                        return@roundEvents
                    }
                    if (event is ModelEvent.ToolCall) {
                        cancellationHistory.observe(event, thinking.toString(), thinkingSignature)
                        if (immediateOutcome == null) {
                            pendingToolCalls += toolSet.unwrapCallTool(event)?.also { wireCalls[it.id] = event } ?: event
                        }
                        return@roundEvents
                    }
                    immediateOutcome = immediateOutcome ?: flushToolCalls(
                        pendingToolCalls,
                        RoundCollectContext(
                            toolResults,
                            toolContext,
                            summaryBuilder,
                            publicEvents,
                            toolSet,
                            budgetTracker,
                            thinking,
                            visibleText = text.toString(),
                            thinkingSignature = thinkingSignature,
                            allowFinishResponseRetry = allowFinishResponseRetry,
                            allowObligationCorrection = allowObligationCorrection,
                            obligationLedger = obligationLedger,
                            wireCalls = wireCalls,
                        ),
                    )
                    if (immediateOutcome != null) {
                        // The frame that closes the stream (output tokens, cache reads) usually follows a terminal
                        // call such as finish; it is still this call's cost, so it is recorded like any other.
                        if (event is ModelEvent.UsageDelta) recordUsage(event, toolContext, budgetTracker, callUsage)
                        return@roundEvents
                    }
                    immediateOutcome = collectEvent(
                        event,
                        thinking,
                        text,
                        immediateOutcome,
                        callUsage,
                        RoundCollectContext(
                            toolResults,
                            toolContext,
                            summaryBuilder,
                            publicEvents,
                            toolSet,
                            budgetTracker,
                            thinking,
                            visibleText = text.toString(),
                            thinkingSignature = thinkingSignature,
                            allowFinishResponseRetry = allowFinishResponseRetry,
                            allowObligationCorrection = allowObligationCorrection,
                            obligationLedger = obligationLedger,
                            wireCalls = wireCalls,
                        ),
                    )
                }
        } catch (error: CancellationException) {
            modelProbe.finish(SESSION_MODEL_CALL_STATUS_CANCELLED)
            val legalRoundMessages = toolResults + cancellationHistory.roundMessagesForCancellation(toolResults)
            if (legalRoundMessages.isNotEmpty()) {
                throw LegalHistoryCancellationException(legalRoundMessages, error)
            }
            throw error
        } catch (error: ModelClientException) {
            modelProbe.finish(SESSION_MODEL_CALL_STATUS_FAILED, error.code.wireCode)
            callUsage.publish(publicEvents)
            return RoundOutcome.Failed(
                failure = ToolLoopFailure(error.code.wireCode, error.message, error.recoverable),
                modelFailure = true,
                toolResultsCommitted = toolResults.isNotEmpty(),
            )
        } catch (error: RuntimeException) {
            modelProbe.finish(SESSION_MODEL_CALL_STATUS_FAILED, RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode)
            callUsage.publish(publicEvents)
            return RoundOutcome.Failed(
                ToolLoopFailure(
                    RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode,
                    error.message ?: "Model loop failed.",
                ),
            )
        }
        modelProbe.finish(SESSION_MODEL_CALL_STATUS_COMPLETED)
        callUsage.publish(publicEvents)
        // CONSUMED means the model completed a response to a request that contained the input.
        for (input in steeringQueue.inFlight(toolContext.runId)) {
            steeringQueue.consumed(input)
            publicEvents.recordSteeringAccepted(input.requestId, input.text)
        }
        steeringQueue.projectPublic(toolContext.runId, publicEvents)
        immediateOutcome = immediateOutcome ?: flushToolCalls(
            pendingToolCalls,
            RoundCollectContext(
                toolResults,
                toolContext,
                summaryBuilder,
                publicEvents,
                toolSet,
                budgetTracker,
                thinking,
                visibleText = text.toString(),
                thinkingSignature = thinkingSignature,
                allowFinishResponseRetry = allowFinishResponseRetry,
                allowObligationCorrection = allowObligationCorrection,
                obligationLedger = obligationLedger,
                wireCalls = wireCalls,
            ),
        )
        immediateOutcome?.let { return it }
        budgetTracker.afterRoundFailure()?.let { return RoundOutcome.Failed(it) }
        return when {
            toolResults.isNotEmpty() -> RoundOutcome.ToolResults(toolResults)
            // A text answer with no tool call ends the run, as in dsh and Codex; finish(response) stays the explicit form.
            text.isNotBlank() -> implicitFinishOutcome(text.toString(), allowFinishResponseRetry, toolContext.runId)
            // CC/Codex end-turn-no-tools is natural stop; this protocol needs finish/tools.
            // Prefer one budgeted continue nudge over hard-failing empty mid-task completions.
            // Probe always records kind/continued so hosts can root-cause without UI coupling.
            else -> resolveEmptyCompletionOutcome(
                text = text.toString(),
                thinking = thinking.toString(),
                allowEmptyCompletionContinue = allowEmptyCompletionContinue,
                summaryBuilder = summaryBuilder,
                publicEvents = publicEvents,
            )
        }
    }

    private fun recordUsage(
        event: ModelEvent.UsageDelta,
        toolContext: MaterializerContext,
        budgetTracker: LoopBudgetTracker,
        callUsage: ModelCallUsage,
    ) {
        sessionRecorder.recordUsage(toolContext, event)
        budgetTracker.recordUsage(event)
        callUsage.add(event)
    }

    private fun implicitFinishOutcome(response: String, allowRetry: Boolean, runId: String): RoundOutcome {
        // A subagent that only said what it is about to do ("我将检索…") would hand that back as its result.
        val nudge = textNeedsFinish && allowRetry
        if (!nudge && !invalidFinishResponseText(response) && !response.isEchoedData()) {
            return RoundOutcome.Finished(response, sealed = steeringQueue.sealIfIdle(runId))
        }
        if (!allowRetry) return RoundOutcome.Failed(
            ToolLoopFailure(INVALID_FINISH_RESPONSE_CODE, INVALID_FINISH_RESPONSE_MESSAGE, recoverable = false),
        )
        val notice = if (nudge) SUBAGENT_TEXT_REPLY_MESSAGE else INVALID_FINISH_RESPONSE_MESSAGE
        return RoundOutcome.ToolResults(
            messages = listOf(ModelMessage("user", "$INVALID_FINISH_RESPONSE_CODE\n$notice")),
            usedFinishResponseRetry = true,
        )
    }

    private suspend fun resolveEmptyCompletionOutcome(
        text: String,
        thinking: String,
        allowEmptyCompletionContinue: Boolean,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
    ): RoundOutcome {
        val kind = classifyEmptyCompletion(text, thinking)
        val continued = allowEmptyCompletionContinue
        summaryBuilder.recordEmptyCompletion(kind, continued = continued)
        publicEvents.recordEmptyCompletionObserved(
            modelRound = summaryBuilder.currentModelRoundCount(),
            kind = kind.name,
            continued = continued,
            continueCount = summaryBuilder.emptyCompletionContinueCount(),
            textChars = text.trim().length,
            thinkingChars = thinking.trim().length,
            hadStateAdvancingWork = summaryBuilder.hasStateAdvancingWork(),
            successfulBackendToolCount = summaryBuilder.successfulBackendToolCount(),
        )
        return if (continued) {
            RoundOutcome.ToolResults(
                messages = emptyCompletionContinueMessages(text),
                usedEmptyCompletionContinue = true,
            )
        } else if (text.isNotBlank()) {
            missingFinish(text)
        } else {
            missingFinish("Model completed without finish(response).")
        }
    }

    private suspend fun collectEvent(
        event: ModelEvent,
        thinking: StringBuilder,
        text: StringBuilder,
        immediateOutcome: RoundOutcome?,
        callUsage: ModelCallUsage,
        context: RoundCollectContext,
    ): RoundOutcome? {
        return when (event) {
            is ModelEvent.ThinkingDelta -> {
                thinking.append(event.text)
                context.publicEvents.recordThinking(event.text)
                immediateOutcome
            }
            is ModelEvent.TextDelta -> {
                text.append(event.text)
                context.publicEvents.recordText(event.text)
                immediateOutcome
            }
            is ModelEvent.UsageDelta -> {
                recordUsage(event, context.toolContext, context.budgetTracker, callUsage)
                immediateOutcome
            }
            is ModelEvent.SearchActivity -> {
                context.publicEvents.recordSearchActivity(event)
                immediateOutcome
            }
            is ModelEvent.ToolCall -> immediateOutcome
            is ModelResponseMetadata -> immediateOutcome
            ModelEvent.Completed -> immediateOutcome
        }
    }

    private suspend fun flushToolCalls(
        pendingToolCalls: MutableList<ModelEvent.ToolCall>,
        context: RoundCollectContext,
    ): RoundOutcome? {
        if (pendingToolCalls.isEmpty()) return null
        val calls = pendingToolCalls.toList()
        pendingToolCalls.clear()
        val outcome = handleToolCalls(calls, context)
        if (context.wireCalls.isEmpty()) return outcome
        val restored = context.toolResults.withWireCalls(context.wireCalls)
        context.toolResults.clear()
        context.toolResults += restored
        return outcome?.withWireCalls(context.wireCalls)
    }

    private suspend fun handleToolCalls(
        calls: List<ModelEvent.ToolCall>,
        context: RoundCollectContext,
    ): RoundOutcome? {
        var index = 0
        while (index < calls.size) {
            val call = calls[index]
            if (call.isBatchableReadTool(context.toolSet)) {
                val batch = calls.drop(index).takeWhile { it.isBatchableReadTool(context.toolSet) }
                readOnlyBatchExecutor.handle(batch, context)?.let { return it }
                index += batch.size
            } else if (call.isBatchableBackendTool(context.toolSet)) {
                val batch = calls.drop(index).takeWhile { it.isBatchableBackendTool(context.toolSet) }
                handleBackendToolBatch(batch, context)?.let { return it }
                index += batch.size
            } else {
                handleBudgetedToolCall(call, context)?.let { return it }
                index += 1
            }
        }
        return null
    }

    private suspend fun handleBudgetedToolCall(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? {
        // TODO tool is a management tool and does not consume loop budget (Q-CDR-006)
        val isTodo = context.toolSet.sourceFor(event.name) == DynamicToolSource.NATIVE_TODO
        if (!isTodo) {
            context.budgetTracker.recordToolCall()?.let { return RoundOutcome.Failed(it) }
        }
        return handleToolCall(event, context)
    }

    private suspend fun handleBackendToolBatch(
        calls: List<ModelEvent.ToolCall>,
        context: RoundCollectContext,
    ): RoundOutcome? = coroutineScope {
        val prepared = mutableListOf<PreparedBackendBatchItem>()
        for (event in calls) {
            context.budgetTracker.recordToolCall()?.let { failure ->
                finishPreparedBackendItems(prepared, context)
                return@coroutineScope RoundOutcome.Failed(failure)
            }
            toolUseController.declareRequiredBackendObligation(
                context.toolContext.runId,
                context.toolSet,
                event,
            )
            context.summaryBuilder.recordToolCall(event.name, event.argumentsJson)
            sessionRecorder.recordToolStarted(context.toolContext, event, DynamicToolSource.BACKEND)
            context.publicEvents.recordStarted(event.id, event.name, event.argumentsJson)
            when (
                val authorization = toolUseController.prepareBackendTool(
                    event = event,
                    context = context.toolContext,
                    toolSet = context.toolSet,
                )
            ) {
                is ToolAuthorization.Allowed -> {
                    prepared += PreparedBackendBatchItem.Allowed(
                        event = event,
                        toolUse = authorization.toolUse,
                        backendToolName = context.toolSet.backendToolName(event.name) ?: event.name,
                    )
                }
                is ToolAuthorization.Denied -> {
                    prepared += PreparedBackendBatchItem.Denied(event, authorization.resultJson)
                }
                is ToolAuthorization.RequiresConfirmation -> {
                    finishPreparedBackendItems(prepared, context)
                    return@coroutineScope RoundOutcome.RequiresConfirmation(
                        prompt = authorization.prompt,
                        pendingToolUse = authorization.pending.copy(
                            reasoningContent = context.nextReasoningContentForHistory(),
                        ),
                    )
                }
            }
        }
        finishPreparedBackendItems(prepared, context)
        null
    }

    private suspend fun finishPreparedBackendItems(
        prepared: List<PreparedBackendBatchItem>,
        context: RoundCollectContext,
    ) = coroutineScope {
        val executions = linkedMapOf<ModelEvent.ToolCall, Deferred<String>>()
        for (item in prepared) {
            if (item is PreparedBackendBatchItem.Allowed) {
                executions[item.event] = async(Dispatchers.IO) {
                    toolUseController.executeBackendToolUse(
                        toolUse = item.toolUse,
                        context = context.toolContext,
                        backendToolName = item.backendToolName,
                    )
                }
            }
        }
        for (item in prepared) {
            when (item) {
                is PreparedBackendBatchItem.Allowed -> {
                    val result = try {
                        requireNotNull(executions[item.event]).await()
                    } catch (cancelled: CancellationException) {
                        toolUseController.recordDeniedBackendObligation(
                            context.toolContext.runId,
                            context.toolSet,
                            item.event,
                            cancelledToolResult(item.event.name),
                            status = cc.noharry.agent.kernel.api.RuntimeActionStatus.CANCELLED,
                        )
                        throw cancelled
                    }
                    toolUseController.recordBackendObligation(
                        context.toolContext.runId,
                        context.toolSet,
                        item.event,
                        result,
                    )
                    toolUseController.finishAllowedToolUse(
                        event = item.event,
                        toolResults = context.toolResults,
                        context = context.toolContext,
                        publicEvents = context.publicEvents,
                        toolUse = item.toolUse,
                        source = DynamicToolSource.BACKEND,
                        result = result,
                        reasoningContent = context.nextReasoningContentForHistory(),
                        thinkingSignature = context.thinkingSignature,
                        summaryBuilder = context.summaryBuilder,
                    )
                }
                is PreparedBackendBatchItem.Denied -> {
                    toolUseController.recordDeniedBackendObligation(
                        context.toolContext.runId,
                        context.toolSet,
                        item.event,
                        item.resultJson,
                    )
                    toolUseController.finishDeniedToolUse(
                        event = item.event,
                        toolResults = context.toolResults,
                        context = context.toolContext,
                        publicEvents = context.publicEvents,
                        source = DynamicToolSource.BACKEND,
                        result = item.resultJson,
                        reasoningContent = context.nextReasoningContentForHistory(),
                        summaryBuilder = context.summaryBuilder,
                    )
                }
            }
        }
    }

    private suspend fun handleToolCall(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? {
        // Hosted web_search is a provider server tool, not MCP/backend. Emitting
        // NativeToolCallStarted would redact args and fail with UNKNOWN_BACKEND_TOOL. A host's own web_search
        // (NATIVE_SEARCH_TOOLS) is in the tool set and runs like web_fetch.
        if (event.isHostedWebSearchTool() && context.toolSet.sourceFor(event.name) == null) {
            return handleHostedWebSearchTool(event, context)
        }
        // A call_tool call left as itself could not be unwrapped: no tool name, or arguments that are not an object.
        if (event.name == CALL_TOOL_NAME && context.toolSet.defersBackendTools) {
            context.summaryBuilder.recordToolCall(event.name, event.argumentsJson)
            sessionRecorder.recordToolStarted(context.toolContext, event, source = null)
            val result = invalidCallToolResult()
            sessionRecorder.recordToolFinished(context.toolContext, event, source = null, resultJson = result)
            context.toolResults.appendToolResult(event, result, context.nextReasoningContentForHistory(), context.thinkingSignature)
            return null
        }
        val source = context.toolSet.sourceFor(event.name)
        context.summaryBuilder.recordToolCall(event.name, event.argumentsJson)
        sessionRecorder.recordToolStarted(context.toolContext, event, source)
        context.publicEvents.recordStarted(event.id, event.name, event.argumentsJson)
        return when (source) {
            DynamicToolSource.INTERACTION -> interactionToolCall(event, context)
            DynamicToolSource.TERMINAL -> if (event.name == "ask") askToolCall(event, context) else finishToolCall(event, context)
            DynamicToolSource.NATIVE_TODO -> handleTodoTool(event, context)
            DynamicToolSource.NATIVE_READ,
            DynamicToolSource.NATIVE_WRITE,
            -> handleWorkspaceToolCall(event, context)
            DynamicToolSource.NETWORK -> handleNetworkToolCall(event, context)
            DynamicToolSource.EXEC -> handleExecToolCall(event, context)
            DynamicToolSource.BACKEND -> handleBackendToolCall(event, context)
            null -> handleUnsupportedTool(
                event = event,
                toolResults = context.toolResults,
                publicEvents = context.publicEvents,
                reasoningContent = context.nextReasoningContentForHistory(),
                toolContext = context.toolContext,
                sessionRecorder = sessionRecorder,
                summaryBuilder = context.summaryBuilder,
                classification = activationStore.classify(
                    runId = context.toolContext.runId,
                    requestedModelToolName = event.name,
                ),
            )
        }
    }

    /**
     * Client-shaped web_search tool calls (DSML / mis-tagged server tool) are not executable
     * locally. Surface query via [ModelEvent.SearchActivity] and acknowledge to the model so
     * the loop continues without UNKNOWN_BACKEND_TOOL.
     */
    private suspend fun handleHostedWebSearchTool(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? {
        val query = extractWebSearchQuery(event.argumentsJson)
        context.summaryBuilder.recordToolCall(event.name, event.argumentsJson)
        sessionRecorder.recordToolStarted(context.toolContext, event, source = null)
        context.publicEvents.recordSearchActivity(
            ModelEvent.SearchActivity(
                provider = HOSTED_WEB_SEARCH_PROVIDER,
                queryPreview = query,
            ),
        )
        val result = hostedWebSearchAcknowledgedResult(query)
        sessionRecorder.recordToolFinished(context.toolContext, event, source = null, resultJson = result)
        context.toolResults.appendToolResult(
            event,
            result,
            context.nextReasoningContentForHistory(),
            context.thinkingSignature,
        )
        return null
    }

    private suspend fun handleWorkspaceToolCall(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? =
        toolUseController.handleWorkspaceTool(
            event,
            context.toolResults,
            context.toolContext,
            context.summaryBuilder,
            context.publicEvents,
            context.toolSet.sourceFor(event.name) ?: DynamicToolSource.NATIVE_READ,
            context.nextReasoningContentForHistory(),
            thinkingSignature = context.thinkingSignature,
        )

    private suspend fun handleNetworkToolCall(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? =
        toolUseController.handleNetworkTool(
            event,
            context.toolResults,
            context.toolContext,
            context.summaryBuilder,
            context.publicEvents,
            context.nextReasoningContentForHistory(),
            thinkingSignature = context.thinkingSignature,
        )

    private suspend fun handleExecToolCall(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? =
        toolUseController.handleExecTool(
            event = event,
            toolResults = context.toolResults,
            context = context.toolContext,
            summaryBuilder = context.summaryBuilder,
            publicEvents = context.publicEvents,
            toolSet = context.toolSet,
            budgetTracker = context.budgetTracker,
            reasoningContent = context.nextReasoningContentForHistory(),
            thinkingSignature = context.thinkingSignature,
        )

    private suspend fun handleBackendToolCall(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? =
        toolUseController.handleBackendTool(
            event,
            context.toolResults,
            context.toolContext,
            context.summaryBuilder,
            context.publicEvents,
            context.toolSet,
            context.nextReasoningContentForHistory(),
            thinkingSignature = context.thinkingSignature,
        )

    private suspend fun handleTodoTool(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome? {
        val sessionId = context.toolContext.sessionId
        val store = todoStore
        val items = parseTodoItems(event.argumentsJson)
        val result = toolExecutionLimiter.withPermit {
            // Todo is a progress/display protocol: the normalized echo (model history) and the
            // public finished event are the source of truth. A configured store only adds
            // persistence; without one (or without a session key) the tool still succeeds.
            // Store I/O failures (returned or thrown) map to the failure result so the
            // started/finished event pair and the model tool result stay intact.
            val persisted = try {
                store == null || sessionId.isBlank() ||
                    store.save(sessionId, TodoState(sessionId, items, nowEpochMillis()))
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (persisted) {
                todoUpdatedResult(items)
            } else {
                """{"ok":false,"code":"TODO_STORE_WRITE_FAILED","message":"TodoStore write failed."}"""
            }
        }
        context.publicEvents.recordFinished(event.id, event.name, result)
        sessionRecorder.recordToolFinished(context.toolContext, event, DynamicToolSource.NATIVE_TODO, result)
        context.toolResults.appendToolResult(
            event,
            result,
            context.nextReasoningContentForHistory(),
            context.thinkingSignature,
        )
        return null
    }

    private suspend fun askToolCall(event: ModelEvent.ToolCall, context: RoundCollectContext): RoundOutcome {
        val authorization = toolUseController.prepareTerminalTool(event, context.toolContext)
        val toolUse = when (authorization) {
            is ToolAuthorization.Allowed -> authorization.toolUse
            is ToolAuthorization.Denied -> return blockFinishToolCall(event, authorization.resultJson, context)
            is ToolAuthorization.RequiresConfirmation -> return blockFinishToolCall(
                event, hookBlockedAndContinueResult("Ask confirmation is unsupported", authorization.prompt), context,
            )
        }
        val args = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(toolUse.argumentsJson) as? kotlinx.serialization.json.JsonObject
        }.getOrNull()
        val question = (args?.get("question") as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }?.content
        if (question.isNullOrBlank() || question.length > 4096 || args.keys != setOf("question")) {
            return blockFinishToolCall(event, """{"ok":false,"code":"INVALID_ARGUMENT","message":"ask requires only a nonblank question"}""", context)
        }
        val asked = event.copy(argumentsJson = toolUse.argumentsJson)
        val result = """{"ok":true,"waitingForUser":true}"""
        context.publicEvents.recordFinished(asked.id, asked.name, result)
        sessionRecorder.recordToolFinished(context.toolContext, asked, DynamicToolSource.TERMINAL, result)
        toolUseController.recordTerminalToolUse(toolUse, result, context.toolContext)
        context.toolResults.appendToolResult(asked, result, context.nextReasoningContentForHistory(), context.thinkingSignature)
        context.publicEvents.recordText(question)
        return RoundOutcome.NeedsUserInput(question, context.toolResults.toList())
    }

    private suspend fun interactionToolCall(event: ModelEvent.ToolCall, context: RoundCollectContext): RoundOutcome? {
        val authorization = toolUseController.prepareInteractionTool(event, context.toolContext)
        val toolUse = when (authorization) {
            is ToolAuthorization.Allowed -> authorization.toolUse
            is ToolAuthorization.Denied -> return interactionResult(event, authorization.resultJson, context)
            is ToolAuthorization.RequiresConfirmation -> return interactionResult(event, """{"ok":false,"code":"INTERACTION_REQUIRES_HOST_APPROVAL"}""", context)
        }
        val effective = event.copy(argumentsJson = toolUse.argumentsJson)
        val result = try {
            userInteraction.ask(effective, context.toolContext, context.publicEvents)
        } catch (error: IllegalArgumentException) {
            return interactionResult(effective, interactionFailure("INVALID_INTERACTION_ARGUMENTS", error.message), context)
        } catch (error: IllegalStateException) {
            // Admission-side refusals (run not accepting questions, question limit, reused call id) are not argument errors.
            return interactionResult(effective, interactionFailure("INTERACTION_UNAVAILABLE", error.message), context)
        }
        toolUseController.recordTerminalToolUse(toolUse, result, context.toolContext)
        return interactionResult(effective, result, context)
    }

    private suspend fun interactionResult(event: ModelEvent.ToolCall, result: String, context: RoundCollectContext): RoundOutcome? {
        context.publicEvents.recordFinished(event.id, event.name, result)
        sessionRecorder.recordToolFinished(context.toolContext, event, DynamicToolSource.INTERACTION, result)
        context.toolResults.appendToolResult(event, result, context.nextReasoningContentForHistory(), context.thinkingSignature)
        return null
    }

    private suspend fun finishToolCall(
        event: ModelEvent.ToolCall,
        context: RoundCollectContext,
    ): RoundOutcome {
        if (invalidFinishArguments(event.argumentsJson)) return rejectInvalidFinish(event, context)
        val authorization = toolUseController.prepareTerminalTool(event, context.toolContext)
        val toolUse = when (authorization) {
            is ToolAuthorization.Allowed -> authorization.toolUse
            is ToolAuthorization.Denied -> return blockFinishToolCall(event, authorization.resultJson, context)
            is ToolAuthorization.RequiresConfirmation -> return blockFinishToolCall(
                event = event,
                result = hookBlockedAndContinueResult(
                    reason = "Finish confirmation is not supported.",
                    prompt = authorization.prompt,
                ),
                context = context,
            )
        }
        val finishEvent = event.copy(argumentsJson = toolUse.argumentsJson)
        if (invalidFinishArguments(finishEvent.argumentsJson)) return rejectInvalidFinish(finishEvent, context)
        val response = extractFinishResponse(finishEvent.argumentsJson)
        val finishDecision = decideFinish(
            argumentsJson = finishEvent.argumentsJson,
            ledger = context.obligationLedger,
            correctionAlreadyUsed = !context.allowObligationCorrection,
        )
        if (!finishDecision.accept) {
            if (finishDecision.retryable) {
                return blockFinishToolCall(
                    event = finishEvent,
                    result = finishDecision.resultJson ?: unresolvedRequiredActionResult(emptyList()),
                    context = context,
                    usedObligationCorrection = true,
                )
            }
            blockFinishToolCall(finishEvent, finishDecision.resultJson ?: unresolvedRequiredActionResult(emptyList()), context)
            return RoundOutcome.Failed(
                ToolLoopFailure(
                    finishDecision.safeCode ?: REQUIRED_ACTION_UNRESOLVED,
                    "Required mutating actions remain unresolved.",
                    recoverable = false,
                ),
            )
        }
        context.publicEvents.recordFinished(finishEvent.id, finishEvent.name, finishEvent.argumentsJson)
        sessionRecorder.recordToolFinished(
            context.toolContext,
            finishEvent,
            DynamicToolSource.TERMINAL,
            finishEvent.argumentsJson,
        )
        toolUseController.recordTerminalToolUse(toolUse, finishEvent.argumentsJson, context.toolContext)
        // Seal before publishing the final text: a finish withheld for late user input must not be spoken.
        val sealed = steeringQueue.sealIfIdle(context.toolContext.runId)
        if (sealed && response.isNotBlank() && context.visibleText != response) {
            context.publicEvents.recordText(response)
        }
        return RoundOutcome.Finished(
            response, finishDecision.outcome, context.toolResults.toList(), sealed,
            closesChecklist = extractFinishOutcomeToken(finishEvent.argumentsJson) == FINISH_OUTCOME_SUCCESS,
        )
    }

    private suspend fun rejectInvalidFinish(event: ModelEvent.ToolCall, context: RoundCollectContext): RoundOutcome {
        val rejected = blockFinishToolCall(
            event, invalidFinishResponseResult(), context, usedFinishResponseRetry = true,
        )
        return if (context.allowFinishResponseRetry) rejected else RoundOutcome.Failed(
            ToolLoopFailure(INVALID_FINISH_RESPONSE_CODE, INVALID_FINISH_RESPONSE_MESSAGE, recoverable = false),
        )
    }

    private suspend fun blockFinishToolCall(
        event: ModelEvent.ToolCall,
        result: String,
        context: RoundCollectContext,
        usedFinishResponseRetry: Boolean = false,
        usedObligationCorrection: Boolean = false,
    ): RoundOutcome {
        context.publicEvents.recordFinished(event.id, event.name, result)
        sessionRecorder.recordToolFinished(context.toolContext, event, DynamicToolSource.TERMINAL, result)
        context.toolResults.appendToolResult(
            event,
            result,
            context.nextReasoningContentForHistory(),
            context.thinkingSignature,
        )
        return RoundOutcome.ToolResults(
            context.toolResults.toList(),
            usedFinishResponseRetry = usedFinishResponseRetry,
            usedObligationCorrection = usedObligationCorrection,
        )
    }
}

internal fun modelPromptCacheKey(sessionId: String): String? =
    sessionId.trim()
        .takeIf { it.isNotBlank() }
        ?.let { id ->
            "$MODEL_PROMPT_CACHE_KEY_PREFIX$id"
                .takeIf { it.length <= MAX_MODEL_PROMPT_CACHE_KEY_LENGTH }
                ?: "$MODEL_PROMPT_CACHE_KEY_PREFIX${id.sha256Hex().take(MODEL_PROMPT_CACHE_HASH_CHARS)}"
        }

private fun String.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }

private const val MODEL_PROMPT_CACHE_KEY_PREFIX = "agent-runtime:"
private const val MODEL_PROMPT_CACHE_HASH_CHARS = 48
private const val MAX_MODEL_PROMPT_CACHE_KEY_LENGTH = 64

private suspend fun handleUnsupportedTool(
    event: ModelEvent.ToolCall,
    toolResults: MutableList<ModelMessage>,
    publicEvents: NativePublicEventBuffer,
    reasoningContent: String?,
    toolContext: MaterializerContext,
    sessionRecorder: LoopSessionRecorder,
    summaryBuilder: NativeLoopSummaryBuilder,
    classification: BackendToolClassification,
): RoundOutcome? {
    val result = when (classification) {
        is BackendToolClassification.KnownInactive -> inactiveBackendToolResult(
            toolName = classification.toolName,
            schemaPath = classification.schemaPath,
        )
        is BackendToolClassification.Unknown -> unknownBackendToolResult(classification.requestedName)
        BackendToolClassification.UnsupportedNativeFallback -> unsupportedToolResult(event.name)
    }
    publicEvents.recordFinished(event.id, event.name, result)
    sessionRecorder.recordToolFinished(toolContext, event, null, result)
    if (classification != BackendToolClassification.UnsupportedNativeFallback) {
        summaryBuilder.recordBackendToolResult(event.name, result)
    }
    toolResults.appendToolResult(event, result, reasoningContent)
    return null
}

private fun ModelEvent.ToolCall.isBackendTool(toolSet: DynamicToolSet): Boolean =
    toolSet.sourceFor(name) == DynamicToolSource.BACKEND

private fun ModelEvent.ToolCall.isBatchableBackendTool(toolSet: DynamicToolSet): Boolean =
    isBackendTool(toolSet) &&
        toolSet.isReadOnlyBackendTool(name) &&
        !toolSet.requiresObligationLedger(name)

/** Provider-hosted search tool name; not present in [DynamicToolSet] as an executable entry. */
private fun ModelEvent.ToolCall.isHostedWebSearchTool(): Boolean =
    name.equals(HOSTED_WEB_SEARCH_TOOL_NAME, ignoreCase = true)

private const val HOSTED_WEB_SEARCH_TOOL_NAME = "web_search"
private const val HOSTED_WEB_SEARCH_PROVIDER = "hosted"

private sealed interface PreparedBackendBatchItem {
    val event: ModelEvent.ToolCall

    data class Allowed(
        override val event: ModelEvent.ToolCall,
        val toolUse: ToolUse,
        val backendToolName: String,
    ) : PreparedBackendBatchItem

    data class Denied(
        override val event: ModelEvent.ToolCall,
        val resultJson: String,
    ) : PreparedBackendBatchItem
}

/** Text that is only JSON (a tool result the model repeated) is not an answer to show the user. */
private fun String.isEchoedData(): Boolean {
    val trimmed = trim()
    if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return false
    return runCatching { kotlinx.serialization.json.Json.parseToJsonElement(trimmed) }.isSuccess
}

private fun missingFinish(message: String): RoundOutcome.Failed = RoundOutcome.Failed(
    ToolLoopFailure(RuntimeErrorCode.MODEL_FINISHED_WITHOUT_RUNTIME_FINISH.wireCode, message),
)

/**
 * One model call's usage. A provider reports it in several frames (the request's input when the stream opens, output
 * and cache reads when it closes), each a delta of the same call. The session log keeps the frames; hosts get their sum
 * as one event when the call ends, so one usage event is one model request and its cache rate reads correctly.
 */
internal class ModelCallUsage {
    private var total = ModelEvent.UsageDelta()

    fun add(delta: ModelEvent.UsageDelta) {
        total = ModelEvent.UsageDelta(
            inputTokens = total.inputTokens + delta.inputTokens,
            outputTokens = total.outputTokens + delta.outputTokens,
            reasoningTokens = total.reasoningTokens + delta.reasoningTokens,
            cacheReadTokens = total.cacheReadTokens + delta.cacheReadTokens,
            cacheWriteTokens = total.cacheWriteTokens + delta.cacheWriteTokens,
        )
    }

    suspend fun publish(publicEvents: NativePublicEventBuffer) {
        val usage = total
        total = ModelEvent.UsageDelta()
        if (usage != ModelEvent.UsageDelta()) publicEvents.recordUsage(usage)
    }
}
