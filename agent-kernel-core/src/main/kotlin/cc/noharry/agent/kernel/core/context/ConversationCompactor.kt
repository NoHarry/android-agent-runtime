package cc.noharry.agent.kernel.core.context
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.model.ModelTool
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.core.loop.CompactedMessages
import cc.noharry.agent.kernel.core.loop.LoopBudgetTracker
import cc.noharry.agent.kernel.core.loop.modelPromptCacheKey
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_SOURCE_COMPACTION
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_STATUS_CANCELLED
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_STATUS_COMPLETED
import cc.noharry.agent.kernel.core.state.SESSION_MODEL_CALL_STATUS_FAILED
import cc.noharry.agent.kernel.core.state.StoredModelCall
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.trace.LoopSessionRecorder
import cc.noharry.agent.kernel.core.trace.ModelCallProbe
import cc.noharry.agent.kernel.core.trace.NativePublicEventBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

/**
 * Session-level compaction, after Codex and dsh (deepseek-harness). When the latest request comes near the context
 * window, the session's oldest runs become one checkpoint message and the newest runs stay as they were.
 *
 * The summary request repeats the system prompt, the tool list and those oldest runs exactly as the last request sent
 * them, then adds one instruction (dsh), so all but the instruction is read from the provider's cache. Runs are kept
 * whole, so a tool call is never separated from its result. An earlier checkpoint in the span is merged, not copied.
 * Nothing changes when the summary fails, is empty or is not smaller than what it replaces. The run's own messages
 * are left to [HistoryCompactor].
 */
class ConversationCompactor(
    private val modelClient: ModelClient?,
    /** Input tokens at which the conversation is compacted: the window times the compaction ratio. */
    private val thresholdTokens: Long,
    /** Recent conversation kept as it was, in tokens; dsh keeps 16% of the window. */
    private val retainTokens: Long,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    init {
        require(thresholdTokens > 0 && retainTokens in 0 until thresholdTokens) { "retainTokens must be below thresholdTokens." }
    }

    suspend fun compactIfNeeded(
        runId: String,
        history: SessionHistoryStore,
        own: List<ModelMessage>,
        systemPrompt: String,
        tools: List<ModelTool>,
        latestInputTokens: Long?,
        context: MaterializerContext,
        recorder: LoopSessionRecorder,
    ): ConversationCompaction? {
        val client = modelClient ?: return null
        if (latestInputTokens == null || latestInputTokens < thresholdTokens || history.compactedInRun(runId)) return null
        val runs = history.earlierRuns(runId)
        // Providers report tokens per request, not per message: estimate from the latest request's bytes.
        val requestBytes = systemPrompt.utf8Size() + tools.sumOf { it.name.utf8Size() + it.schemaJson.utf8Size() } +
            (runs.flatten() + own).messageBytes()
        val tokensPerByte = latestInputTokens.toDouble() / requestBytes.coerceAtLeast(1)
        var keptTokens = own.messageBytes() * tokensPerByte
        var retainedCount = 0
        for (run in runs.asReversed()) {
            val next = keptTokens + run.messageBytes() * tokensPerByte
            if (next > retainTokens) break
            keptTokens = next
            retainedCount += 1
        }
        val span = runs.dropLast(retainedCount)
        if (span.isEmpty() || span.singleOrNull()?.isCheckpoint() == true) return null
        val spanMessages = span.flatten()
        val summary = summarize(client, systemPrompt, tools, spanMessages, context, recorder) ?: return null
        val checkpoint = checkpointMessage(summary)
        if (checkpoint.content.utf8Size() >= spanMessages.messageBytes()) return null
        val retained = runs.takeLast(retainedCount)
        if (!history.replaceEarlierRuns(runId, listOf(listOf(checkpoint)) + retained)) return null
        return ConversationCompaction(
            beforeMessageCount = runs.sumOf { it.size } + own.size,
            afterMessageCount = 1 + retained.sumOf { it.size } + own.size,
            compactedMessageCount = spanMessages.size,
            summaryBytes = summary.utf8Size(),
        )
    }

    private suspend fun summarize(
        client: ModelClient,
        systemPrompt: String,
        tools: List<ModelTool>,
        span: List<ModelMessage>,
        context: MaterializerContext,
        recorder: LoopSessionRecorder,
    ): String? {
        val request = ModelRequest(
            messages = span + ModelMessage(role = "user", content = CONVERSATION_COMPACTION_INSTRUCTION),
            tools = tools,
            system = systemPrompt,
            modelRef = context.modelRef,
            promptCacheKey = modelPromptCacheKey(context.sessionId),
        )
        val startedAt = nowEpochMillis()
        recorder.recordModelRequest(context, request)
        val probe = ModelCallProbe(
            call = StoredModelCall(
                runId = context.runId,
                sessionId = context.sessionId,
                callId = recorder.nextModelCallId(context.runId),
                status = SESSION_MODEL_CALL_STATUS_COMPLETED,
                source = SESSION_MODEL_CALL_SOURCE_COMPACTION,
                modelRef = context.modelRef?.wireValue().orEmpty(),
                startedAtEpochMillis = startedAt,
                finishedAtEpochMillis = startedAt,
                durationMs = 0L,
                messageCount = request.messages.size,
                toolCount = request.tools.size,
            ),
            nowEpochMillis = nowEpochMillis,
            recorder = recorder,
        )
        return try {
            val text = StringBuilder()
            // Only the text is kept; a tool call the model makes anyway is ignored.
            client.complete(request).collect { event ->
                recorder.recordModelStreamEvent(context, event)
                if (event is ModelEvent.UsageDelta) recorder.recordUsage(context, event)
                probe.recordEvent(event)
                if (event is ModelEvent.TextDelta) text.append(event.text)
            }
            probe.finish(SESSION_MODEL_CALL_STATUS_COMPLETED)
            text.toString().trim().take(MAX_CHECKPOINT_CHARS).takeIf { it.isNotEmpty() }
        } catch (cancelled: CancellationException) {
            probe.finish(SESSION_MODEL_CALL_STATUS_CANCELLED)
            throw cancelled
        } catch (_: Exception) {
            probe.finish(SESSION_MODEL_CALL_STATUS_FAILED, RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode)
            null
        }
    }
}

data class ConversationCompaction(
    val beforeMessageCount: Int,
    val afterMessageCount: Int,
    val compactedMessageCount: Int,
    val summaryBytes: Int,
)

/** Before a run's first request: the previous request's size tells whether the session must be compacted first. */
internal suspend fun ConversationCompactor?.compactBeforeRun(
    runId: String,
    history: SessionHistoryStore?,
    own: List<ModelMessage>,
    systemPrompt: suspend () -> String,
    tools: () -> List<ModelTool>,
    context: MaterializerContext,
    recorder: LoopSessionRecorder,
    publicEvents: NativePublicEventBuffer,
): Boolean {
    val compactor = this ?: return false
    val store = history ?: return false
    val latest = store.lastInputTokens(runId) ?: return false
    val compaction = compactor.compactIfNeeded(runId, store, own, systemPrompt(), tools(), latest, context, recorder) ?: return false
    publicEvents.record(compaction)
    return true
}

/**
 * After a tool round: the session's oldest runs first, which keeps this run's own work intact; only when that frees
 * nothing does [HistoryCompactor] compact the run's own messages, as before session history existed.
 */
internal suspend fun compactAfterRound(
    runId: String,
    own: List<ModelMessage>,
    systemPrompt: String,
    tools: () -> List<ModelTool>,
    conversation: ConversationCompactor?,
    history: SessionHistoryStore?,
    runCompactor: HistoryCompactor?,
    budgetTracker: LoopBudgetTracker,
    context: MaterializerContext,
    recorder: LoopSessionRecorder,
    publicEvents: NativePublicEventBuffer,
): CompactedMessages {
    val latest = budgetTracker.latestUsageTokensForCompaction()
    if (conversation != null && history != null && latest != null) {
        history.noteInputTokens(runId, latest.toLong())
        conversation.compactIfNeeded(runId, history, own, systemPrompt, tools(), latest.toLong(), context, recorder)?.let {
            publicEvents.record(it)
            return CompactedMessages(own, compacted = true)
        }
    }
    val compactor = runCompactor ?: return CompactedMessages(own, compacted = false)
    // With the session's earlier runs the larger part, summarizing this run's few messages frees nothing worth a call.
    val earlier = history?.earlierRuns(runId).orEmpty().flatten()
    if (conversation != null && own.messageBytes() < earlier.messageBytes()) return CompactedMessages(own, compacted = false)
    return when (val result = compactor.compact(own, context, recorder, latest)) {
        is CompactionResult.Unchanged -> CompactedMessages(result.messages, compacted = false)
        is CompactionResult.Compacted -> {
            publicEvents.recordCompactionApplied(
                beforeMessageCount = own.size,
                afterMessageCount = result.messages.size,
                droppedCount = result.droppedCount,
                summaryBytes = result.summaryBytes,
            )
            CompactedMessages(result.messages, compacted = true)
        }
    }
}

private suspend fun NativePublicEventBuffer.record(compaction: ConversationCompaction) {
    recordCompactionApplied(
        beforeMessageCount = compaction.beforeMessageCount,
        afterMessageCount = compaction.afterMessageCount,
        droppedCount = compaction.compactedMessageCount,
        summaryBytes = compaction.summaryBytes,
    )
}

private fun List<ModelMessage>.isCheckpoint(): Boolean =
    singleOrNull()?.content?.startsWith(CHECKPOINT_PREAMBLE) == true

private fun checkpointMessage(summary: String): ModelMessage =
    ModelMessage(role = "user", content = "$CHECKPOINT_PREAMBLE\n\n$CHECKPOINT_OPEN\n$summary\n$CHECKPOINT_CLOSE")

private fun List<ModelMessage>.messageBytes(): Int =
    sumOf { it.content.utf8Size() + it.toolArgumentsJson.orEmpty().utf8Size() }

private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

private const val MAX_CHECKPOINT_CHARS = 8_000
private const val CHECKPOINT_OPEN = "<compacted-summary>"
private const val CHECKPOINT_CLOSE = "</compacted-summary>"

/** dsh's framing: the checkpoint is established background, and the conversation goes on from what follows. */
const val CHECKPOINT_PREAMBLE =
    "This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. " +
        "Treat the captured context as established background and build on it without restating it. Continue directly " +
        "from the messages that follow, without acknowledging this checkpoint."

/** Codex's handoff aim with dsh's fixed sections, worded for an in-car assistant. */
internal val CONVERSATION_COMPACTION_INSTRUCTION = """
You are now compacting this conversation. Condense the conversation ABOVE into a checkpoint that lets you continue it with nothing essential lost.

Output exactly these sections, in order, as terse bullets; write "(none)" for an empty section:

## Requests and intent
- [what the user asked for, in their words where the wording matters, including corrections]

## Actions and results
- [each action taken: the tool, the exact values set, and whether it succeeded, failed or is unknown]

## Current state
- [latest known settings and states: temperatures, levels, modes, destination, media, and so on]

## Open items
- [questions or offers still waiting for the user's answer, and requested work not done yet]

## Preferences and constraints
- [what the user prefers or ruled out]

Rules:
- Keep exact names, numbers, tool names and values. Keep the language the user used.
- Do not call any tool. Do not mention this compaction. Output only the checkpoint.
- If the conversation already contains a $CHECKPOINT_OPEN block, it is an earlier checkpoint: keep what is still true, drop what is stale, and merge it with the newer conversation into one checkpoint.
""".trimIndent()
