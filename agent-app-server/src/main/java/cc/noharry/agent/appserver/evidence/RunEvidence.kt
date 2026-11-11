@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.evidence
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.appserver.runtime.hostCompletion
import cc.noharry.agent.spi.reliability.RuntimeResult
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import java.io.File

/**
 * A run's events as its replay check reads them; [write] keeps them in [evidenceDir] as a diagnostic file, or nowhere
 * when it is null (a host that keeps no diagnostics).
 */
internal class RunEvidenceRecorder(
    private val evidenceDir: File?,
    request: RuntimeTurnRequest,
    private val providerLabel: String,
    private val streamLabel: String,
    private val backendLabel: String,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
    /** What the user typed; a host frame around a short reply must not make every reply look actionable. */
    userText: String = request.input,
) {
    private val tools = mutableListOf<MutableRunToolEvidence>()
    private val toolsByCallId = mutableMapOf<String, Int>()
    private val publicPending = mutableMapOf<String, ArrayDeque<Int>>()
    private val requestId = request.requestId
    private val sessionId = request.sessionId
    private val scene = request.scene.orEmpty()
    private val inputLength = request.input.length
    private val inputPreview = request.input.inputPreviewText()
    private val requiresBusinessAction = userText.requiresBusinessAction()
    private var runId: String = request.requestId
    private var finalStatus: String? = null
    private var finalCompletionOutcome: String? = null
    private var finalErrorCode: String? = null
    private var failureEventCode: String? = null
    private var finalResponse: String = ""
    private var eventDispatch: RuntimeEventDispatchEvidence = RuntimeEventDispatchEvidence()
    private var memory: RuntimeMemoryEvidence = RuntimeMemoryEvidence()
    private val emptyCompletionObservations = mutableListOf<EmptyCompletionObservation>()

    internal fun recordEvent(event: ThreadEvent) {
        when (event) {
            is ThreadEvent.TurnStarted -> runId = event.turnId
            is ThreadEvent.ItemStarted -> (event.item as? cc.noharry.agent.ThreadItem.ToolCall)?.let {
                recordPublicStarted(it.turnId, it.tool, it.id, nowEpochMillis())
            }
            is ThreadEvent.ItemCompleted -> when (val item = event.item) {
                is cc.noharry.agent.ThreadItem.ToolCall -> {
                    recordPublicFinished(item.turnId, item.tool, item.id, item.status.name, nowEpochMillis())
                }
                is cc.noharry.agent.ThreadItem.WebSearch -> recordSearchActivity(item)
                else -> Unit
            }
            is ThreadEvent.TurnCompleted -> recordResult(event.result.turnId, event.result.status.name,
                event.result.hostCompletion().name, event.result.error?.code, event.result.finalResponse)
            is ThreadEvent.TurnFailed -> recordFailed(event.turnId, event.error.code)
            else -> Unit
        }
    }

    internal fun recordCancelled() {
        if (finalStatus == null) finalStatus = RuntimeRunStatus.CANCELLED.name
    }

    internal fun recordRuntimeFailure() {
        if (finalStatus == null) finalStatus = RuntimeRunStatus.FAILED.name
    }

    /**
     * The runtime's own result, read once the run ended. A turn the server reported as stopping to ask (a partial or
     * blocked reply that asks the person) keeps saying so: the runtime calls that run COMPLETED, and the replay check
     * would read it as one that claimed to have done everything.
     */
    internal fun recordTerminalResult(result: RuntimeResult) {
        recordResult(
            eventRunId = result.runId,
            status = if (finalStatus in ASKED) RuntimeRunStatus.WAITING_USER_INPUT.name else result.status.name,
            completionOutcome = result.completion.outcome.name,
            errorCode = result.errorCode,
            response = result.response,
        )
    }

    fun snapshot(): RunEvidence {
        val immutableTools = tools.map { it.toEvidence() }
        return RunEvidence(
            requestId = requestId,
            runId = runId,
            sessionId = sessionId,
            scene = scene,
            inputLength = inputLength,
            inputPreview = inputPreview,
            provider = providerLabel,
            stream = streamLabel,
            backend = backendLabel,
            finalStatus = finalStatus,
            finalCompletionOutcome = finalCompletionOutcome,
            finalErrorCode = finalErrorCode,
            failureEventCode = failureEventCode,
            finalResponse = finalResponse,
            tools = immutableTools,
            requiresBusinessAction = requiresBusinessAction,
            counts = RunEvidenceCounts.from(immutableTools),
            eventDispatch = eventDispatch,
            memory = memory,
            loopProbes = loopProbeEvidence(),
        )
    }

    internal fun recordLoopProbes(events: List<cc.noharry.agent.spi.reliability.RuntimeTraceEvent>) {
        emptyCompletionObservations.clear()
        events.filter { it.eventName == "EMPTY_COMPLETION_OBSERVED" }.forEach { event ->
            val data = event.details
            val round = data["modelRound"]?.toIntOrNull() ?: return@forEach
            emptyCompletionObservations += EmptyCompletionObservation(
                modelRound = round, kind = data["kind"].orEmpty(), continued = data["continued"].toBoolean(),
                continueCount = data["continueCount"]?.toIntOrNull() ?: 0, textChars = data["textChars"]?.toIntOrNull() ?: 0,
                thinkingChars = data["thinkingChars"]?.toIntOrNull() ?: 0, hadStateAdvancingWork = data["hadStateAdvancingWork"].toBoolean(),
                successfulBackendToolCount = data["successfulBackendToolCount"]?.toIntOrNull() ?: 0,
            )
        }
    }

    private fun loopProbeEvidence(): LoopProbeEvidence {
        val last = emptyCompletionObservations.lastOrNull()
        return LoopProbeEvidence(
            emptyCompletionObservedCount = emptyCompletionObservations.size,
            emptyCompletionContinueCount = emptyCompletionObservations.count { it.continued },
            lastEmptyCompletionKind = last?.kind,
            lastEmptyCompletionContinued = last?.continued,
            lastEmptyCompletionModelRound = last?.modelRound,
            lastEmptyCompletionTextChars = last?.textChars,
            lastEmptyCompletionThinkingChars = last?.thinkingChars,
            lastHadStateAdvancingWork = last?.hadStateAdvancingWork,
            lastSuccessfulBackendToolCount = last?.successfulBackendToolCount,
            observations = emptyCompletionObservations.toList(),
        )
    }

    /** The file the evidence was written to, or null when no file is kept. */
    fun write(): File? {
        val evidenceDir = evidenceDir ?: return null
        evidenceDir.mkdirs()
        val file = File(evidenceDir, runEvidenceFileName(requestId))
        file.writeText(RunEvidenceJson.write(snapshot()), Charsets.UTF_8)
        return file
    }

    internal fun recordEventDispatch(evidence: RuntimeEventDispatchEvidence) {
        eventDispatch = evidence
    }

    internal fun recordMemoryEvidence(evidence: RuntimeMemoryEvidence) {
        memory = evidence
    }

    private fun recordPublicStarted(eventRunId: String, toolName: String, toolCallId: String, timestampMs: Long) {
        runId = eventRunId
        val tool = toolByCallId(toolCallId)
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(tool, toolCallId)
        tool.publicStarted = true
        tool.publicStartedAtEpochMillis = timestampMs
        publicPending.getOrPut(toolName) { ArrayDeque() }.addLast(tool.sequence)
    }

    private fun recordPublicFinished(eventRunId: String, toolName: String, toolCallId: String, status: String, timestampMs: Long) {
        runId = eventRunId
        val sequence = toolByCallId(toolCallId)?.sequence ?: publicPending[toolName]?.removeLastOrNull()
        val tool = toolBySequenceOrNull(sequence)
            ?: findLatest(toolName) { !it.publicFinished }
            ?: newTool(toolName, toolCallId)
        rememberToolCallId(tool, toolCallId)
        removePending(publicPending, toolName, tool.sequence)
        tool.publicFinished = true
        tool.status = status
        tool.publicFinishedAtEpochMillis = timestampMs
    }

    private fun recordResult(
        eventRunId: String,
        status: String,
        completionOutcome: String?,
        errorCode: String?,
        response: String,
    ) {
        runId = eventRunId
        finalStatus = status
        finalCompletionOutcome = completionOutcome
        finalErrorCode = errorCode.orEmpty().ifBlank { null }
        finalResponse = response
    }

    private fun recordFailed(eventRunId: String, code: String) {
        runId = eventRunId
        failureEventCode = code
        if (finalStatus == null) finalStatus = RuntimeRunStatus.FAILED.name
    }

    /**
     * Provider-hosted web_search (Anthropic server tool / OpenAI hosted search / DSML-shaped
     * web_search remapped to SearchActivity). Record its public lifecycle as meaningful work.
     */
    private fun recordSearchActivity(event: cc.noharry.agent.ThreadItem.WebSearch) {
        runId = event.turnId.ifBlank { runId }
        val now = nowEpochMillis()
        val tool = findLatest(WEB_SEARCH_TOOL_NAME) { it.publicStarted && !it.publicFinished }
            ?: findLatest(WEB_SEARCH_TOOL_NAME) {
                // Merge resultCount-only follow-ups into the last completed search card.
                event.query.isBlank() && it.publicFinished
            }
            ?: newTool(
                toolName = WEB_SEARCH_TOOL_NAME,
                toolCallId = "search-activity-${tools.size + 1}",
            )
        if (!tool.publicStarted) {
            tool.publicStarted = true
            tool.publicStartedAtEpochMillis = now
        }
        // This completed hosted-search item has no separate tool-call lifecycle.
        tool.publicFinished = true
        tool.status = cc.noharry.agent.ItemStatus.COMPLETED.name
        tool.publicFinishedAtEpochMillis = now
    }

    private fun newTool(toolName: String, toolCallId: String = ""): MutableRunToolEvidence {
        val tool = MutableRunToolEvidence(tools.size + 1, toolCallId, toolName)
        tools += tool
        rememberToolCallId(tool, toolCallId)
        return tool
    }

    private fun findLatest(
        toolName: String,
        predicate: (MutableRunToolEvidence) -> Boolean,
    ): MutableRunToolEvidence? = tools.asReversed().firstOrNull { it.toolName == toolName && predicate(it) }

    private fun toolBySequenceOrNull(sequence: Int?): MutableRunToolEvidence? =
        sequence?.let { seq -> tools.firstOrNull { it.sequence == seq } }

    private fun toolByCallId(toolCallId: String): MutableRunToolEvidence? =
        toolCallId.takeIf { it.isNotBlank() }?.let(toolsByCallId::get)?.let(::toolBySequence)

    private fun rememberToolCallId(tool: MutableRunToolEvidence, toolCallId: String) {
        if (toolCallId.isBlank()) return
        tool.toolCallId = tool.toolCallId.ifBlank { toolCallId }
        toolsByCallId[toolCallId] = tool.sequence
    }

    private fun toolBySequence(sequence: Int): MutableRunToolEvidence? =
        tools.firstOrNull { it.sequence == sequence }

    private fun removePending(pending: MutableMap<String, ArrayDeque<Int>>, toolName: String, sequence: Int) {
        val stack = pending[toolName] ?: return
        val remaining = stack.filterNot { it == sequence }
        stack.clear()
        stack.addAll(remaining)
        if (stack.isEmpty()) pending.remove(toolName)
    }

}

/** Shared by the writer and the read-only Trajectory enrichment so both resolve one path. */
internal fun runEvidenceFileName(requestId: String): String =
    requestId.replace(Regex("""[^A-Za-z0-9_.-]"""), "_").ifBlank { "debug-run" } + ".json"

private const val WEB_SEARCH_TOOL_NAME = "web_search"

/** A run's end that stopped to ask the person: the runtime's word for it, and the turn's. */
private val ASKED = setOf(RuntimeRunStatus.WAITING_USER_INPUT.name, cc.noharry.agent.TurnStatus.WAITING_FOR_ANSWER.name)
