@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.loop
import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.model.*
import cc.noharry.agent.kernel.api.reliability.*
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
class NativeToolLoop(
    private val modelClient: ModelClient?,
    private val mcpRegistry: ToolProviderRegistry,
    private val workspace: WorkspacePort?,
    private val traceRedactor: TraceRedactor = DefaultTraceRedactor,
    private val hookChain: HookChainPort = HookChainPort.empty(),
    private val bootstrapPrompt: BootstrapPrompt = BootstrapPromptProvider.build(),
    private val promptResolver: BootstrapPromptResolver = SimpleBootstrapPromptResolver(bootstrapPrompt),
    private val loopBudget: LoopBudgetConfig = LoopBudgetConfig(),
    private val toolExecutionLimiter: ToolExecutionLimiter = ToolExecutionLimiter(),
    private val loopHistoryStore: LoopHistoryStore = InMemoryLoopHistoryStore(),
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    private val activationStore: ToolActivationStore = ToolActivationStore(nowEpochMillis = nowEpochMillis),
    private val mcpDebugTraceSink: RuntimeMcpDebugTraceSink = RuntimeMcpDebugTraceSink.None,
    private val todoStore: TodoStorePort? = null,
    private val memoryStore: RuntimeMemoryPort? = null,
    private val historyCompactor: HistoryCompactor? = null,
    private val sessionRecorder: LoopSessionRecorder = LoopSessionRecorder.noop(),
    private val handoffProvider: suspend (String) -> RuntimeHandoff? = { null },
    private val modelRetryConfig: ModelRetryConfig = ModelRetryConfig(),
    private val execEngine: ExecEngine? = null,
    private val networkToolBackend: NetworkToolPort = NetworkToolPort.disabled(),
    private val nativeWebFetchEnabled: Boolean = true,
    private val steeringQueue: RuntimeSteeringQueue = RuntimeSteeringQueue(nowEpochMillis),
    private val probeSink: LoopProbeSink = LoopProbeSink.noop(),
    private val contextProviders: List<RuntimeContextProvider> = emptyList(),
    private val turnObservers: List<RuntimeTurnObserver> = emptyList(),
    /** Exact Probe hub routing key; never fall back to runId-only. */
    private val runtimeInstanceId: String? = null,
    disabledNativeTools: Set<String> = emptySet(),
    /** Per-run host context, sent after the input so the system prompt (and its prompt cache) stays the same. */
    private val runContext: String = "",
    /** A session's earlier runs, sent before this run's own messages in every request (see [SessionHistoryStore]). */
    private val sessionHistory: SessionHistoryStore? = null,
    /** How backend tools reach the model: in the tool list, through call_tool, or deferred and loaded in history. */
    private val backendToolExposure: BackendToolExposure = BackendToolExposure.DIRECT,
    /** Send [runContext] as a system message in history (providers that accept system updates there). */
    private val runContextAsSystem: Boolean = false,
    /** Compacts the session's oldest runs when the conversation nears the context window. */
    private val conversationCompactor: ConversationCompactor? = null,
    /** Offers spawn_agent / wait_agent / cancel_agent, which the workspace port executes. */
    private val subagentTools: Boolean = false,
    /** This run is a subagent's: a text-only reply is nudged once to finish instead of ending the run. */
    private val textNeedsFinish: Boolean = false,
    /** False in a subagent's run: ask and ask_async return an error there, since no user answers them. */
    private val userReachable: Boolean = true,
    /** Offers web_search; the network port runs it with the host's search service. */
    private val nativeWebSearchEnabled: Boolean = false,
    /** The host's kinds of subagent, offered as spawn_agent's `agent`. */
    private val subagentTypes: List<NativeSubagentType> = emptyList(),
) {
    private val obligationLedger = RuntimeObligationLedger()

    /**
     * Todo state must survive without a host-configured store: `update_todo` is a
     * display/progress protocol (event-stream observability), not workspace storage.
     * Hosts that configure a persistent store additionally get cross-run continuity.
     */
    private val effectiveTodoStore: TodoStorePort = todoStore ?: InMemoryTodoStore()
    private val toolRegistry =
        DynamicToolRegistry(
            activationStore, nativeWebFetchEnabled, execEngine != null, disabledNativeTools, backendToolExposure, subagentTools,
            nativeWebSearchEnabled, subagentTypes,
        )
    private val toolUseController = NativeToolUseController(
        mcpRegistry,
        workspace,
        networkToolBackend,
        execEngine,
        hookChain,
        mcpDebugTraceSink,
        sessionRecorder,
        toolExecutionLimiter = toolExecutionLimiter,
        obligationLedger = obligationLedger,
    )
    private val roundRunner = NativeRoundRunner(
        toolRegistry,
        toolUseController,
        activationStore,
        effectiveTodoStore,
        toolExecutionLimiter,
        nowEpochMillis,
        sessionRecorder,
        steeringQueue,
        ModelImageRequestView(imageInputEnabled = loopBudget.imageInputEnabled),
        textNeedsFinish = textNeedsFinish,
        userReachable = userReachable,
    )
    private val memoryRetriever = RuntimeMemoryRetriever()
    private val finishResponseRetryRuns = mutableSetOf<String>()
    private val emptyCompletionContinueRuns = mutableSetOf<String>()
    private val obligationCorrectionRuns = mutableSetOf<String>()

    private var persistedContextSuffix: String = ""

    suspend fun run(
        request: AgentRequest,
        runId: String,
        emitPublicEvent: suspend (AgentEvent) -> Unit = {},
    ): NativeLoopOutcome {
        // Observe-only: never throw into control path from probe emissions.
        // case.bound is emitted at run start from the real session casePackId (not finish backfill).
        try {
            probeSink.emitRunStart()
            probeSink.emitRequestConfig()
            probeSink.emitSessionIsolate("runtime", "default", "default")
            probeSink.emitCaseBound(probeSink.casePackId)
        } catch (_: Exception) {
            // ignore
        }
        val client = modelClient ?: return emitTerminalProbeEvents(
            NativeLoopOutcome.Terminal(modelClientNotConfiguredResult(runId), NativeLoopSummary()),
        )
        // First-user invariant: single initial user message = exact AgentRequest.input bytes.
        val messages = mutableListOf(ModelMessage(role = "user", content = request.input))
        val toolContext = MaterializerContext(
            sessionId = request.sessionId,
            runId = runId,
            requestId = request.requestId,
            scene = request.scene,
            modelRef = request.modelRef,
        )
        sessionRecorder.appendMessage(toolContext, messages.first())
        runContextMessage(runContext, runContextAsSystem)?.let { messages += it; sessionRecorder.appendMessage(toolContext, it) }
        sessionHistory?.start(runId, request.sessionId)
        steeringQueue.open(toolContext, sessionRecorder)
        val summaryBuilder = NativeLoopSummaryBuilder()
        summaryBuilder.recordSessionInheritedTools(
            activationStore.activated(runId)
                .filter { it.metadata.source == SESSION_INHERITED_ACTIVATION_SOURCE }
                .map { it.toolName },
        )
        retrieveMemorySelection(request.input, toolContext)?.let { selection ->
            summaryBuilder.recordMemorySelection(selection.loopMatchSummaries(), selection.capabilityQueries)
            activateMemoryHintTools(toolContext, summaryBuilder, selection)
        }
        prepareRuntimeTools(request.input, toolContext, mcpRegistry, activationStore, sessionRecorder)
        if (backendToolExposure == BackendToolExposure.NATIVE_DEFERRED) activationStore.keepLoadedToolsDeclared(
            runId, sessionHistory?.earlierRuns(runId).orEmpty().flatten(), mcpRegistry, toolContext)
        announceDeferredTools(runId, messages, toolContext)
        val hookContext = toolContext.toHookContext()
        hookChain.onRunStarted(hookContext)
        // Pre-model: turn observers (explicit mutations) then context providers (data-only system suffix).
        RuntimeContextHooks.notifyTurnInput(
            turnObservers,
            request,
            runId,
            nowEpochMillis(),
            runtimeInstanceId = runtimeInstanceId,
            obligationLedger = obligationLedger,
        )
        val contextSuffix =
            RuntimeContextHooks.collectContextSystemSuffix(
                contextProviders,
                request,
                runId,
                nowEpochMillis(),
                runtimeInstanceId = runtimeInstanceId,
            )
        persistedContextSuffix = contextSuffix
        val activePromptResolver = promptResolver.withContextSuffix(contextSuffix)
        val publicEvents = NativePublicEventBuffer(runId, traceRedactor, emitPublicEvent)
        if (conversationCompactor.compactBeforeRun(runId, sessionHistory, messages, { activePromptResolver.resolve(request.scene, request.sessionId).text },
                { toolRegistry.tools(toolContext).modelTools }, toolContext, sessionRecorder, publicEvents)) announceDeferredTools(runId, messages, toolContext)
        val outcome = continueLoop(
            client = client,
            runId = runId,
            toolContext = toolContext,
            hookContext = hookContext,
            messages = messages,
            summaryBuilder = summaryBuilder,
            publicEvents = publicEvents,
            budgetTracker = LoopBudgetTracker(loopBudget, nowEpochMillis),
            modelRef = request.modelRef,
            promptResolverOverride = activePromptResolver,
        )
        return when (outcome) {
            is NativeLoopOutcome.Terminal -> emitTerminalProbeEvents(outcome)
            else -> outcome // confirmation / user-input: probe finalized on eventual terminal
        }
    }

    /**
     * Terminal probe process events only (empty-completion). The Probe terminal drain/persist
     * is owned solely by DefaultAgentRuntime's unified post-persist coordinator — never here.
     * This method emits at most the empty-completion evidence, then returns the Terminal as-is.
     */
    private suspend fun emitTerminalProbeEvents(
        outcome: NativeLoopOutcome.Terminal,
    ): NativeLoopOutcome.Terminal {
        try {
            if (outcome.summary.emptyCompletionObservedCount > 0) {
                probeSink.emitEmptyCompletion(
                    outcome.summary.lastEmptyCompletionKind ?: "empty",
                )
            }
        } catch (_: Exception) {
            // observe-only
        }
        return outcome
    }

    // --- Observe-only mid-loop probe emission (SPEC-RPB-001/004) ---
    private fun emitProbeModelRoundStart(roundIndex: Int) {
        try {
            probeSink.emitModelRoundStart(roundIndex)
        } catch (_: Exception) {
            // observe-only
        }
    }

    private fun emitProbeRoundOutcome(roundIndex: Int, outcome: RoundOutcome) {
        try {
            if (outcome is RoundOutcome.ToolResults) {
                emitProbeToolRound(roundIndex, outcome.messages)
            }
            probeSink.emitModelRoundEnd(roundIndex, probeRoundStatus(outcome))
        } catch (_: Exception) {
            // observe-only
        }
    }

    private fun emitProbeToolRound(roundIndex: Int, roundMessages: List<ModelMessage>) {
        val session = probeSink
        val calls = roundMessages.filter {
            it.role == "assistant" && it.toolName != null && it.toolCallId != null
        }
        if (calls.isEmpty()) return
        session.emitToolBatchPlan(calls.size, roundIndex)
        val results = roundMessages.filter { it.role == "tool" && it.toolCallId != null }
            .associateBy { it.toolCallId }
        for (call in calls) {
            emitProbeToolCall(roundIndex, call, results[call.toolCallId]?.content)
        }
    }

    private fun emitProbeToolCall(roundIndex: Int, call: ModelMessage, resultJson: String?) {
        val session = probeSink
        val toolName = call.toolName ?: return
        val callId = call.toolCallId ?: return
        val source = probeToolSource(toolName)
        session.emitToolPrepare(toolName, callId, source = source, roundIndex = roundIndex)
        session.emitToolExecuteStart(
            toolName = toolName,
            callId = callId,
            source = source,
            roundIndex = roundIndex,
            argsDigest = probeArgsDigest(call.toolArgumentsJson),
        )
        val parsed = probeToolExecutionResult(toolName, resultJson)
        session.emitToolExecuteEnd(
            toolName = toolName,
            callId = callId,
            ok = parsed.ok,
            code = parsed.code,
            source = source,
            roundIndex = roundIndex,
        )
        emitProbeNetworkFetch(toolName, call.toolArgumentsJson)
    }

    private fun emitProbeConfirmedToolExecution(
        toolName: String,
        callId: String,
        argumentsJson: String,
        ok: Boolean,
        code: String?,
    ) {
        try {
            val session = probeSink
            val source = probeToolSource(toolName)
            session.emitToolPrepare(toolName, callId, source = source)
            session.emitToolExecuteStart(
                toolName = toolName,
                callId = callId,
                source = source,
                argsDigest = probeArgsDigest(argumentsJson),
            )
            session.emitToolExecuteEnd(toolName, callId, ok = ok, code = code, source = source)
            emitProbeNetworkFetch(toolName, argumentsJson)
        } catch (_: Exception) {
            // observe-only
        }
    }

    private fun emitProbeNetworkFetch(toolName: String, argumentsJson: String?) {
        if (toolName != "web_fetch") return
        val host = probeUrlHost(argumentsJson) ?: return
        try {
            probeSink.emitNetworkFetch(host)
        } catch (_: Exception) {
            // observe-only
        }
    }

    private suspend fun continueLoop(
        client: ModelClient,
        runId: String,
        toolContext: MaterializerContext,
        hookContext: HookContext,
        messages: MutableList<ModelMessage>,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        budgetTracker: LoopBudgetTracker,
        modelRef: ModelRef?,
        promptResolverOverride: BootstrapPromptResolver = promptResolver,
    ): NativeLoopOutcome {
        var todoStateMayBeStale = hasStateAdvancingToolAfterLastTodoUpdate(messages)
        steeringQueue.open(toolContext, sessionRecorder)
        var activeModelRef = modelRef
        var probeRoundIndex = 0
        val repeatedCalls = RepeatToolCallReminder()
        // A finish accepted while user input was pending; it is the fallback if the continuation cannot run.
        var withheldFinish: RoundOutcome.Finished? = null
        try {
        while (true) {
            budgetTracker.beforeRoundFailure()?.let { failure ->
                withheldFinish?.let { finish ->
                    return withheldFinishOutcome(hookContext, runId, toolContext, finish, failure, summaryBuilder, publicEvents, messages)
                }
                return terminalOutcome(hookContext, failed(runId, failure), summaryBuilder.build(), publicEvents)
            }
            appendSteeringInputs(messages, toolContext, publicEvents, afterWithheldFinish = withheldFinish != null)
            val promptStarted = System.nanoTime()
            val resolvedPrompt = promptResolverOverride.resolve(toolContext.scene, toolContext.sessionId)
            sessionRecorder.recordPreparationTimings(toolContext, mapOf("promptAssemblyMs" to preparationElapsedMs(promptStarted)))
            val systemPrompt = resolvedPrompt.text
            appendBudgetWarningIfNeeded(messages, toolContext, publicEvents, budgetTracker)
            budgetTracker.recordRoundStarted()
            val allowFinishResponseRetry = synchronized(finishResponseRetryRuns) {
                runId !in finishResponseRetryRuns && !messages.hasInvalidFinishResponseRetry()
            }
            val allowObligationCorrection = synchronized(obligationCorrectionRuns) {
                runId !in obligationCorrectionRuns && !messages.hasUnresolvedObligationRetry()
            }
            val allowEmptyCompletionContinue = synchronized(emptyCompletionContinueRuns) {
                runId !in emptyCompletionContinueRuns && !messages.hasEmptyCompletionContinue()
            }
            emitProbeModelRoundStart(probeRoundIndex)
            val retryRound = try {
                collectRoundWithRetry(
                    client = client,
                    messages = sessionHistory.prefixed(runId, messages),
                    toolContext = toolContext,
                    summaryBuilder = summaryBuilder,
                    publicEvents = publicEvents,
                    systemPrompt = systemPrompt,
                    budgetTracker = budgetTracker,
                    modelRef = activeModelRef,
                    allowFinishResponseRetry = allowFinishResponseRetry,
                    allowEmptyCompletionContinue = allowEmptyCompletionContinue,
                    allowObligationCorrection = allowObligationCorrection,
                )
            } catch (error: LegalHistoryCancellationException) {
                persistLegalCancellationHistory(runId, toolContext, messages, error.roundMessages, budgetTracker)
                // The calls this round started stay in the run, so the session's history below shows them.
                messages += error.roundMessages
                throw error.original
            }
            emitProbeRoundOutcome(probeRoundIndex, retryRound.outcome)
            probeRoundIndex += 1
            activeModelRef = retryRound.modelRef
            budgetTracker.latestUsageTokensForCompaction()?.let { sessionHistory?.noteInputTokens(runId, it.toLong()) }
            when (val round = retryRound.outcome) {
                is RoundOutcome.Finished -> {
                    if (!round.sealed) {
                        // Input arrived during the finishing round: keep this round's tool results, remember the
                        // finish as fallback, and let the model see the input before it finishes again.
                        withheldFinish = round
                        val continuation = round.messages + ModelMessage(role = "assistant", content = round.response)
                        messages += continuation
                        sessionRecorder.appendMessages(toolContext, continuation)
                        continue
                    }
                    clearFinishResponseRetry(runId)
                    clearEmptyCompletionContinue(runId)
                    sessionRecorder.appendMessage(
                        toolContext,
                        ModelMessage(role = "assistant", content = round.response),
                    )
                    val result = completed(runId, round.response, obligationLedger.summarize(round.completionOutcome))
                    if (round.closesChecklist) todoStore.closeChecklistOnSuccess(toolContext.sessionId, result, messages + round.messages, nowEpochMillis)
                    sessionHistory?.record(runId, messages + round.messages + ModelMessage(role = "assistant", content = round.response))
                    return terminalOutcome(
                        hookContext,
                        result,
                        summaryBuilder.build(),
                        publicEvents,
                    )
                }
                is RoundOutcome.Failed -> {
                    withheldFinish?.takeIf { !round.toolResultsCommitted }?.let { finish ->
                        return withheldFinishOutcome(hookContext, runId, toolContext, finish, round.failure, summaryBuilder, publicEvents, messages)
                    }
                    clearFinishResponseRetry(runId)
                    clearEmptyCompletionContinue(runId)
                    sessionRecorder.appendMessage(
                        toolContext,
                        ModelMessage(role = "assistant", content = round.failure.message),
                    )
                    // The calls the failed round ran did happen; a notice, not the error text as the model's own words, ends it.
                    sessionHistory?.record(runId, messages + round.messages + failedRunNotice(round.failure.code))
                    return terminalOutcome(
                        hookContext,
                        failed(runId, round.failure).copy(
                            completion = obligationLedger.summarize(RuntimeCompletionOutcome.FAILED),
                        ),
                        summaryBuilder.build(),
                        publicEvents,
                    )
                }
                is RoundOutcome.NeedsUserInput -> {
                    withheldFinish = null
                    steeringQueue.pause(runId)
                    messages += round.messages
                    sessionRecorder.appendMessages(toolContext, round.messages)
                    sessionHistory?.record(runId, messages)
                    persistHistory(runId, messages, budgetTracker.snapshot())
                    return NativeLoopOutcome.NeedsUserInput(
                        round.question,
                        summaryBuilder.build(),
                        publicEvents.snapshot(),
                    )
                }
                is RoundOutcome.RequiresConfirmation -> {
                    withheldFinish = null
                    steeringQueue.pause(runId)
                    messages += round.messages
                    sessionRecorder.appendMessages(toolContext, round.messages)
                    val pending = round.pendingToolUse?.copy(
                        historyMessages = messages.toList(),
                        budgetSnapshot = budgetTracker.snapshot(),
                    )
                    persistHistory(runId, pending?.historyMessages ?: messages, budgetTracker.snapshot())
                    pending?.let { sessionHistory?.record(runId, messages + it.awaitingConfirmationMessages()) }
                    return NativeLoopOutcome.RequiresConfirmation(
                        prompt = round.prompt,
                        summary = summaryBuilder.build(),
                        events = publicEvents.snapshot(),
                        pendingToolUse = pending,
                    )
                }
                is RoundOutcome.ToolResults -> {
                    // The model did new work after the withheld finish; that response is no longer a valid fallback.
                    withheldFinish = null
                    if (round.usedFinishResponseRetry) markFinishResponseRetryUsed(runId)
                    if (round.usedObligationCorrection) markObligationCorrectionUsed(runId)
                    if (round.usedEmptyCompletionContinue) markEmptyCompletionContinueUsed(runId)
                    val hadTodoUpdate = round.messages.hasTodoUpdate()
                    val hadStateAdvancingTool = round.messages.hasStateAdvancingTool()
                    val nextTodoStateMayBeStale = if (hadTodoUpdate) {
                        hadStateAdvancingTool
                    } else {
                        todoStateMayBeStale || hadStateAdvancingTool
                    }
                    messages += round.messages
                    sessionRecorder.appendMessages(toolContext, round.messages)
                    repeatedCalls.after(round.messages)?.let { appendRuntimeNotice(messages, toolContext, it) }
                    val compacted = compactAfterRound(runId, messages, systemPrompt, { toolRegistry.tools(toolContext).modelTools },
                        conversationCompactor, sessionHistory, historyCompactor, budgetTracker, toolContext, sessionRecorder, publicEvents)
                    if (compacted.messages !== messages) messages.clear().also { messages += compacted.messages }
                    // Tools this round found are loaded (deferred mode) or, if compaction folded their schemas away, re-shown.
                    announceDeferredTools(runId, messages, toolContext)
                    if (compacted.compacted && !nextTodoStateMayBeStale) {
                        appendTodoReminder(messages, toolContext, "after-compaction")
                    }
                    todoStateMayBeStale = nextTodoStateMayBeStale
                    persistHistory(runId, messages, budgetTracker.snapshot())
                }
            }
        }
        } catch (cancelled: CancellationException) {
            // The next run of the session sees this one and is told it was cut short, as Codex and dsh tell their model.
            sessionHistory?.record(runId, messages + cancelledRunNotice())
            // Record why queued input and open questions ended; the host's later clear() is then a no-op.
            steeringQueue.close(runId, "CANCELLED")
            throw cancelled
        }
    }

    private suspend fun collectRoundWithRetry(
        client: ModelClient,
        messages: List<ModelMessage>,
        toolContext: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        systemPrompt: String,
        budgetTracker: LoopBudgetTracker,
        modelRef: ModelRef?,
        allowFinishResponseRetry: Boolean,
        allowEmptyCompletionContinue: Boolean,
        allowObligationCorrection: Boolean,
    ): ModelRetryRound {
        val candidates = modelRetryConfig.candidates(modelRef)
        var lastFailure: RoundOutcome.Failed? = null
        for ((candidateIndex, candidate) in candidates.withIndex()) {
            for (attempt in 1..modelRetryConfig.maxAttempts) {
                val round = roundRunner.collect(
                    client,
                    messages,
                    toolContext.copy(modelRef = candidate),
                    summaryBuilder,
                    publicEvents,
                    systemPrompt,
                    budgetTracker,
                    candidate,
                    allowFinishResponseRetry = allowFinishResponseRetry,
                    allowEmptyCompletionContinue = allowEmptyCompletionContinue,
                    allowObligationCorrection = allowObligationCorrection,
                    obligationLedger = obligationLedger,
                )
                if (round !is RoundOutcome.Failed || !round.isRetryableModelFailure()) {
                    return ModelRetryRound(round, candidate)
                }
                lastFailure = round
                if (attempt < modelRetryConfig.maxAttempts) {
                    val delayMillis = modelRetryConfig.delayMillis(attempt)
                    publicEvents.recordRetryAttempted(
                        attempt = attempt + 1,
                        delayMillis = delayMillis,
                        modelRef = candidate,
                        fallbackModelRef = null,
                    )
                    delayIfNeeded(delayMillis)
                    continue
                }
                break
            }
            val fallback = candidates.getOrNull(candidateIndex + 1)
            if (fallback != null && lastFailure != null) {
                val delayMillis = modelRetryConfig.delayMillis(modelRetryConfig.maxAttempts)
                publicEvents.recordRetryAttempted(
                    attempt = 1,
                    delayMillis = delayMillis,
                    modelRef = candidate,
                    fallbackModelRef = fallback,
                )
                delayIfNeeded(delayMillis)
                continue
            }
            lastFailure?.let { return ModelRetryRound(it, candidate) }
        }
        return ModelRetryRound(
            lastFailure ?: RoundOutcome.Failed(
                ToolLoopFailure(RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode, "Model loop failed."),
            ),
            candidates.lastOrNull(),
        )
    }

    private fun persistLegalCancellationHistory(
        runId: String,
        toolContext: MaterializerContext,
        messages: MutableList<ModelMessage>,
        roundMessages: List<ModelMessage>,
        budgetTracker: LoopBudgetTracker,
    ) {
        if (roundMessages.isEmpty()) return
        val legalHistory = messages + roundMessages
        sessionRecorder.appendMessages(toolContext, roundMessages)
        persistHistory(runId, legalHistory, budgetTracker.snapshot())
    }

    private suspend fun delayIfNeeded(delayMillis: Long) {
        if (delayMillis > 0) delay(delayMillis)
    }

    private fun markFinishResponseRetryUsed(runId: String) {
        synchronized(finishResponseRetryRuns) { finishResponseRetryRuns += runId }
    }

    private fun markObligationCorrectionUsed(runId: String) {
        synchronized(obligationCorrectionRuns) { obligationCorrectionRuns += runId }
    }

    private fun persistHistory(
        runId: String,
        messages: List<ModelMessage>,
        budgetSnapshot: LoopBudgetSnapshot,
    ) {
        loopHistoryStore.save(runId, messages, budgetSnapshot, obligationLedger.encode(), persistedContextSuffix)
    }

    private fun clearFinishResponseRetry(runId: String) {
        synchronized(finishResponseRetryRuns) { finishResponseRetryRuns -= runId }
    }

    private fun markEmptyCompletionContinueUsed(runId: String) {
        synchronized(emptyCompletionContinueRuns) { emptyCompletionContinueRuns += runId }
    }

    private fun clearEmptyCompletionContinue(runId: String) {
        synchronized(emptyCompletionContinueRuns) { emptyCompletionContinueRuns -= runId }
    }

    private suspend fun appendSteeringInputs(
        messages: MutableList<ModelMessage>,
        toolContext: MaterializerContext,
        publicEvents: NativePublicEventBuffer,
        afterWithheldFinish: Boolean,
    ) {
        val inputs = steeringQueue.drain(toolContext.runId)
        steeringQueue.projectPublic(toolContext.runId, publicEvents)
        if (inputs.isEmpty()) return
        val framed = if (afterWithheldFinish) listOf(ModelMessage(role = "user", content = WITHHELD_FINISH_NOTE)) else emptyList()
        for (message in framed + inputs.map { ModelMessage(role = "user", content = it.toModelMessageContent()) }) {
            messages += message
            sessionRecorder.appendMessage(toolContext, message)
        }
    }

    /** The continuation could not run; the finish accepted before the input arrived is the honest result. */
    private suspend fun withheldFinishOutcome(
        hookContext: HookContext,
        runId: String,
        toolContext: MaterializerContext,
        finish: RoundOutcome.Finished,
        failure: ToolLoopFailure,
        summaryBuilder: NativeLoopSummaryBuilder,
        publicEvents: NativePublicEventBuffer,
        history: List<ModelMessage>,
    ): NativeLoopOutcome.Terminal {
        clearFinishResponseRetry(runId)
        clearEmptyCompletionContinue(runId)
        publicEvents.recordText(finish.response)
        sessionRecorder.appendMessage(
            toolContext,
            ModelMessage(role = "assistant", content = "$WITHHELD_FINISH_FALLBACK ${failure.code}"),
        )
        val result = completed(runId, finish.response, obligationLedger.summarize(finish.completionOutcome))
        if (finish.closesChecklist) todoStore.closeChecklistOnSuccess(toolContext.sessionId, result, history, nowEpochMillis)
        return terminalOutcome(
            hookContext,
            result,
            summaryBuilder.build(),
            publicEvents,
            inputReason = failure.code,
        )
    }

    private suspend fun appendBudgetWarningIfNeeded(
        messages: MutableList<ModelMessage>,
        toolContext: MaterializerContext,
        publicEvents: NativePublicEventBuffer,
        budgetTracker: LoopBudgetTracker,
    ) {
        val warning = budgetTracker.budgetWarningForRound() ?: return
        publicEvents.recordBudgetWarning(warning)
        val sections = mutableListOf(warning.toPromptJson())
        todoPromptSection(toolContext)?.let { sections += it }
        appendRuntimeNotice(messages, toolContext, sections.joinToString(prefix = "Runtime context:\n", separator = "\n\n"))
    }

    private suspend fun appendTodoReminder(
        messages: MutableList<ModelMessage>,
        toolContext: MaterializerContext,
        reason: String,
    ): Boolean {
        val section = todoPromptSection(toolContext) ?: return false
        appendRuntimeNotice(messages, toolContext, "Runtime todo reminder ($reason):\n$section")
        return true
    }

    private suspend fun todoPromptSection(toolContext: MaterializerContext): String? =
        toolContext.sessionId
            .takeIf { it.isNotBlank() }
            ?.let { effectiveTodoStore.load(it) }
            ?.toPromptSection()

    private fun appendRuntimeNotice(
        messages: MutableList<ModelMessage>,
        toolContext: MaterializerContext,
        content: String,
    ) {
        if (messages.lastOrNull()?.content == content) return
        val message = ModelMessage(role = "user", content = content)
        messages += message
        sessionRecorder.appendMessage(toolContext, message)
    }

    private suspend fun memoryLessonsFor(scene: String?): List<RuntimeLesson> {
        val store = memoryStore ?: return emptyList()
        val sceneLessons = store.lessonsFor(scene)
        if (scene == null) return sceneLessons
        val globalLessons = store.lessonsFor(null)
        return (sceneLessons + globalLessons).distinctBy { runtimeMemoryLessonKey(it.content) }
    }

    private suspend fun retrieveMemorySelection(
        requestText: String,
        toolContext: MaterializerContext,
    ): RuntimeMemorySelection? {
        if (memoryStore == null) return null
        return memoryRetriever.retrieve(requestText, memoryLessonsFor(toolContext.scene))
    }

    private suspend fun activateMemoryHintTools(
        toolContext: MaterializerContext,
        summaryBuilder: NativeLoopSummaryBuilder,
        selection: RuntimeMemorySelection,
    ) {
        if (!mcpRegistry.isConfigured) return
        if (selection.capabilityQueries.isEmpty() || selection.maxPreactivatedTools <= 0) return
        val tools = mcpRegistry.allTools(toolContext)
        activationStore.recordCatalog(toolContext.runId, tools)
        val activated = linkedSetOf<String>()
        val available = activationStore.activated(toolContext.runId)
            .mapTo(linkedSetOf()) { it.toolName }
        for (query in selection.capabilityQueries) {
            val hits = rankMcpTools(tools, query, maxResults = MEMORY_HINT_ACTIVATION_TOP_K)
                .filter { queryCoverage(it.tool, query) >= MEMORY_HINT_MIN_COVERAGE }
            for (hit in hits) {
                if (activated.size >= selection.maxPreactivatedTools) return
                if (hit.tool.qualifiedName in available) break
                val schemaJson = hit.tool.descriptor.inputSchemaJson
                if (schemaJson.isBlank()) continue
                if (!activated.add(hit.tool.qualifiedName)) continue
                available += hit.tool.qualifiedName
                activationStore.activate(
                    runId = toolContext.runId,
                    tool = hit.tool,
                    schemaJson = schemaJson,
                    metadata = ToolActivationMetadata(source = MEMORY_HINT_ACTIVATION_SOURCE),
                )
                summaryBuilder.recordMemoryHintTool(hit.tool.qualifiedName)
                break
            }
        }
    }

    /**
     * This run's backend tools whose schema the model cannot see, as a tools-ready message after [messages]. Tools a
     * session carries over are not repeated once compaction has folded away where they were shown: the model finds
     * them again with search_tools, as Codex does, instead of every compaction re-sending dozens of schemas.
     */
    private fun announceDeferredTools(runId: String, messages: MutableList<ModelMessage>, toolContext: MaterializerContext) {
        if (backendToolExposure == BackendToolExposure.DIRECT) return
        val history = sessionHistory?.earlierRuns(runId)
        val tools = activationStore.activated(runId).filter { history == null || it.metadata.source != SESSION_INHERITED_ACTIVATION_SOURCE }
        val conversation = history.orEmpty().flatten() + messages
        val message = if (backendToolExposure == BackendToolExposure.NATIVE_DEFERRED) toolAdditionMessage(tools, conversation)
            else deferredToolsMessage(tools, conversation)
        message?.let { messages += it; sessionRecorder.appendMessage(toolContext, it) }
    }

    private suspend fun terminalOutcome(
        hookContext: HookContext,
        result: RuntimeResult,
        summary: NativeLoopSummary,
        publicEvents: NativePublicEventBuffer,
        inputReason: String = result.status.name,
    ): NativeLoopOutcome.Terminal {
        steeringQueue.close(result.runId, inputReason)
        steeringQueue.projectPublic(result.runId, publicEvents)
        hookChain.onRunFinished(result, hookContext)
        return NativeLoopOutcome.Terminal(result, summary, publicEvents.snapshot())
    }

    suspend fun resumeConfirmedToolUse(
        pending: PendingNativeToolUse,
        emitPublicEvent: suspend (AgentEvent) -> Unit = {},
    ): NativeLoopOutcome {
        val client = modelClient
        if (client == null) {
            val outcome = failed(
                pending.context.runId,
                ToolLoopFailure(RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode, MODEL_CLIENT_NOT_CONFIGURED_MESSAGE),
            ).toTerminalOutcome()
            return emitTerminalProbeEvents(outcome)
        }
        val publicEvents = NativePublicEventBuffer(pending.context.runId, traceRedactor, emitPublicEvent)
        loopHistoryStore.read(pending.context.runId)?.obligationLedger?.takeIf { it.isNotBlank() }
            ?.let(obligationLedger::restore)
        persistedContextSuffix = loopHistoryStore.read(pending.context.runId)?.contextSystemSuffix.orEmpty()
        val resumedPromptResolver = promptResolver.withContextSuffix(persistedContextSuffix)
        val confirmedEvent = pending.toToolCallEvent()
        val confirmedToolSet = toolRegistry.tools(pending.context)
        if (pending.toolUse.source == ToolSource.BACKEND) {
            toolUseController.declareRequiredBackendObligation(
                pending.context.runId,
                confirmedToolSet,
                confirmedEvent,
            )
        }
        val executed = toolUseController.executeConfirmedToolUse(pending, publicEvents)
        if (pending.toolUse.source == ToolSource.BACKEND) {
            toolUseController.recordBackendObligation(
                pending.context.runId,
                confirmedToolSet,
                confirmedEvent,
                executed.resultJson,
            )
        }
        // Observe-only: the confirmed tool executes outside the round runner; record it here.
        emitProbeConfirmedToolExecution(
            toolName = pending.toolUse.toolName,
            callId = pending.toolCallId,
            argumentsJson = pending.toolUse.argumentsJson,
            ok = executed.parsed.ok,
            code = executed.parsed.code,
        )
        val messages = restoredHistory(pending).toMutableList()
        messages += pending.toCallMessage()
        messages += pending.toToolCallEvent().toToolResultMessage(executed.resultJson, executed.images)
        if (!executed.parsed.ok) {
            // The user approved this call, not further attempts: the run stops, and the session sees the call and its result.
            persistHistory(pending.context.runId, restoredHistory(pending), pending.budgetSnapshot)
            sessionHistory?.record(pending.context.runId, messages + failedRunNotice(executed.parsed.code ?: "TOOL_FAILED"))
            val outcome = failed(pending.context.runId, executed.parsed)
                .copy(completion = obligationLedger.summarize(RuntimeCompletionOutcome.FAILED))
                .toTerminalOutcome(publicEvents.snapshot())
            return emitTerminalProbeEvents(outcome)
        }
        persistHistory(pending.context.runId, messages, pending.budgetSnapshot)
        return when (
            val outcome = continueLoop(
                client = client,
                runId = pending.context.runId,
                toolContext = pending.context,
                hookContext = pending.context.toHookContext(approvedConfirmation = true),
                messages = messages,
                summaryBuilder = NativeLoopSummaryBuilder(),
                publicEvents = publicEvents,
                budgetTracker = LoopBudgetTracker(loopBudget, nowEpochMillis, pending.budgetSnapshot),
                modelRef = pending.context.modelRef,
                promptResolverOverride = resumedPromptResolver,
            )
        ) {
            // Pause keeps the attachment open; DefaultAgentRuntime finalizes on eventual terminal.
            is NativeLoopOutcome.Terminal -> emitTerminalProbeEvents(outcome)
            else -> outcome
        }
    }

    suspend fun resumeUserInput(
        runId: String,
        sessionId: String,
        requestId: String,
        answer: String,
        scene: String?,
        modelRef: cc.noharry.agent.kernel.api.model.ModelRef?,
        currentStateKeys: Set<String> = emptySet(),
        workspaceId: String? = null,
        emitPublicEvent: suspend (AgentEvent) -> Unit = {},
    ): NativeLoopOutcome {
        val client = modelClient
        if (client == null) {
            val outcome = NativeLoopOutcome.Terminal(
                modelClientNotConfiguredResult(runId),
                NativeLoopSummary(),
            )
            return emitTerminalProbeEvents(outcome)
        }
        val history = loopHistoryStore.read(runId)
        history?.obligationLedger?.takeIf { it.isNotBlank() }?.let(obligationLedger::restore)
        if (history == null || history.messages.isEmpty()) {
            val outcome = NativeLoopOutcome.Terminal(
                failed(runId, userInputHistoryMissingFailure()),
                NativeLoopSummary(),
            )
            return emitTerminalProbeEvents(outcome)
        }
        // Append answer only; do not rewrite first historical user message.
        val messages = (history.messages + ModelMessage(role = "user", content = answer)).toMutableList()
        val context = MaterializerContext(
            sessionId = sessionId,
            runId = runId,
            requestId = requestId,
            scene = scene,
            modelRef = modelRef,
        )
        val resumeRequest =
            AgentRequest(
                requestId = requestId,
                sessionId = sessionId,
                input = answer,
                scene = scene,
                modelRef = modelRef,
                workspaceId = workspaceId,
                currentStateKeys = currentStateKeys,
            )
        val referenceTime = nowEpochMillis()
        RuntimeContextHooks.notifyTurnInput(
            turnObservers,
            resumeRequest,
            runId,
            referenceTime,
            inputOverride = answer,
            runtimeInstanceId = runtimeInstanceId,
            obligationLedger = obligationLedger,
        )
        val contextSuffix =
            RuntimeContextHooks.collectContextSystemSuffix(
                contextProviders,
                resumeRequest,
                runId,
                referenceTime,
                inputOverride = answer,
                runtimeInstanceId = runtimeInstanceId,
            )
        persistedContextSuffix = contextSuffix
        val activePromptResolver = promptResolver.withContextSuffix(contextSuffix)
        return when (
            val outcome = continueLoop(
                client = client,
                runId = runId,
                toolContext = context,
                hookContext = context.toHookContext(),
                messages = messages,
                summaryBuilder = NativeLoopSummaryBuilder(),
                publicEvents = NativePublicEventBuffer(runId, traceRedactor, emitPublicEvent),
                budgetTracker = LoopBudgetTracker(loopBudget, nowEpochMillis, history.budgetSnapshot),
                modelRef = modelRef,
                promptResolverOverride = activePromptResolver,
            )
        ) {
            // Pause keeps the attachment open; DefaultAgentRuntime finalizes on eventual terminal.
            is NativeLoopOutcome.Terminal -> emitTerminalProbeEvents(outcome)
            else -> outcome
        }
    }

    /** The run as it paused. Only when that is lost does the handoff note stand in for it: it would repeat the history. */
    private suspend fun restoredHistory(pending: PendingNativeToolUse): List<ModelMessage> =
        loopHistoryStore.read(pending.context.runId)?.messages?.takeIf { it.isNotEmpty() }
            ?: pending.historyMessages.takeIf { it.isNotEmpty() }
            ?: (resumeContextMessage(pending.context.runId) + ModelMessage(role = "user", content = ""))

    private suspend fun resumeContextMessage(runId: String): List<ModelMessage> =
        handoffProvider(runId)?.toResumeContextMessage()?.let { listOf(it) }.orEmpty()

    private fun RuntimeHandoff.toResumeContextMessage(): ModelMessage = ModelMessage(
        role = "system",
        content = buildList {
            task?.let { add("Task: $it") }
            if (done.isNotEmpty()) add("Done: ${done.joinToString("; ")}")
            if (nextActions.isNotEmpty()) add("Next: ${nextActions.joinToString("; ")}")
            if (decisions.isNotEmpty()) add("Decisions: ${decisions.joinToString("; ")}")
            if (doNotRepeat.isNotEmpty()) add("Do not repeat: ${doNotRepeat.joinToString("; ")}")
        }.joinToString("\n"),
    )

    private fun PendingNativeToolUse.toToolCallEvent(): ModelEvent.ToolCall =
        ModelEvent.ToolCall(
            id = toolCallId,
            name = toolUse.toolName,
            argumentsJson = toolUse.argumentsJson,
        )
}

/** A remembered capability preactivates a tool only when the tool has every term of it (search scores vary with the catalog). */
private const val MEMORY_HINT_MIN_COVERAGE = 1.0
private const val MEMORY_HINT_ACTIVATION_TOP_K = 3
private fun RuntimeResult.toTerminalOutcome(events: List<AgentEvent> = emptyList()): NativeLoopOutcome.Terminal =
    NativeLoopOutcome.Terminal(this, NativeLoopSummary(), events)
