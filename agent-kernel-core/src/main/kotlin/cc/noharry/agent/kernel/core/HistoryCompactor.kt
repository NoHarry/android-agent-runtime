package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Candidate compactor: an explicit input-token threshold disables legacy byte pressure.
 * Without it, retains the legacy byte/usage budget behavior (SPEC-CDR-007).
 *
 * Main strategy: LLM summarization (decision 5.b). Messages are classified into
 * Critical (never dropped/compressed), Compressible (LLM-summarized), and Droppable
 * (discarded first). When the model client is unavailable or summarization fails,
 * falls back to rule-based compression (mem-012: cancellation must not become failure).
 */
class HistoryCompactor(
    private val modelClient: ModelClient?,
    private val budgetBytes: Int = MAX_LOOP_HISTORY_BYTES,
    private val minBatchMessages: Int = 4,
    private val maxSummaryBytes: Int = MAX_SUMMARY_BYTES,
    private val usageBudgetTokens: Int = DEFAULT_COMPACTION_USAGE_BUDGET_TOKENS,
    private val usageThresholdRatio: Double = DEFAULT_COMPACTION_USAGE_THRESHOLD_RATIO,
    private val inputTokenThreshold: Long? = null,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val effectiveByteBudget = if (inputTokenThreshold == null) budgetBytes else Int.MAX_VALUE

    init {
        require(inputTokenThreshold == null || inputTokenThreshold > 0) { "inputTokenThreshold must be positive." }
        require(usageBudgetTokens > 0) { "usageBudgetTokens must be positive." }
        require(usageThresholdRatio > 0.0 && usageThresholdRatio <= 1.0) {
            "usageThresholdRatio must be in (0.0, 1.0]."
        }
    }

    suspend fun compact(
        messages: List<ModelMessage>,
        context: MaterializerContext? = null,
        recorder: ModelCallRecorder? = null,
        latestUsageTokens: Int? = null,
    ): CompactionResult {
        val overByteBudget = inputTokenThreshold == null && encodedSize(messages) > budgetBytes
        val overUsageBudget = latestUsageTokens?.let(::exceedsUsageThreshold) == true
        if (!overByteBudget && !overUsageBudget) {
            return CompactionResult.Unchanged(messages)
        }
        val folded = foldOldReadOnlyToolResults(messages)
        if (folded.count > 0 && encodedSize(folded.messages) <= effectiveByteBudget) {
            return CompactionResult.Compacted(
                messages = folded.messages.withoutOrphanToolProtocol(),
                droppedCount = folded.count,
                summaryBytes = 0,
            )
        }
        val (critical, compressible, droppable) = partition(folded.messages)
        val kept = critical + compressible
        if (!overUsageBudget && encodedSize(kept) <= effectiveByteBudget) {
            return CompactionResult.Unchanged(
                folded.messages
                    .filterNot { classifyMessage(it) == MessageRetention.Droppable }
                    .withoutOrphanToolProtocol(),
            )
        }
        if (compressible.size >= minBatchMessages) {
            val summary = summarize(compressible, context, recorder)
            var compacted = critical.withoutToolProtocol() + listOf(summaryMessage(summary))
            while (encodedSize(compacted) > effectiveByteBudget && compacted.size > critical.size + 1) {
                compacted = compacted.drop(1)
            }
            return CompactionResult.Compacted(compacted, droppable.size, summary.toByteArray(Charsets.UTF_8).size)
        }
        return CompactionResult.Unchanged(kept)
    }

    /**
     * Folds each old read-only result once. An already folded result is left byte-identical: rewriting it
     * would change an early message on every round and invalidate the provider prompt cache for the whole
     * history after it, and it would count as progress when nothing was actually reduced.
     */
    private fun foldOldReadOnlyToolResults(messages: List<ModelMessage>): FoldedMessages {
        val readOnlyIndexes = messages.indices.filter { index -> messages[index].isReadOnlyToolResult() }
        val keepIndexes = readOnlyIndexes.takeLast(RECENT_READ_ONLY_TOOL_RESULTS_TO_KEEP).toSet()
        var foldedCount = 0
        val folded = messages.mapIndexed { index, message ->
            if (index in readOnlyIndexes && index !in keepIndexes && message.isLargeEnoughToFold() && !message.isFoldedToolResult()) {
                foldedCount += 1
                message.copy(content = foldedToolResultContent(message))
            } else {
                message
            }
        }
        return FoldedMessages(folded, foldedCount)
    }

    private fun exceedsUsageThreshold(tokens: Int): Boolean {
        val threshold = inputTokenThreshold ?: kotlin.math.ceil(usageBudgetTokens * usageThresholdRatio).toLong().coerceAtLeast(1)
        return tokens.toLong() >= threshold
    }

    private suspend fun summarize(
        messages: List<ModelMessage>,
        context: MaterializerContext?,
        recorder: ModelCallRecorder?,
    ): String {
        val client = modelClient ?: return ruleBasedFallback(messages)
        val startedAt = nowEpochMillis()
        val request = ModelRequest(
            messages = listOf(ModelMessage(role = "user", content = compactionSource(messages))),
            system = COMPACTION_PROMPT,
            tools = emptyList(),
        )
        val canonical = recorder as? LoopSessionRecorder
        if (context != null) canonical?.recordModelRequest(context, request)
        val probe = modelCallProbe(context, recorder, request, startedAt)
        return try {
            val result = StringBuilder()
            client.complete(request).collect { event ->
                if (context != null) {
                    canonical?.recordModelStreamEvent(context, event)
                    if (event is ModelEvent.UsageDelta) canonical?.recordUsage(context, event)
                }
                probe?.recordEvent(event)
                if (event == ModelEvent.Completed) {
                    probe?.finish(SESSION_MODEL_CALL_STATUS_COMPLETED)
                }
                if (event is ModelEvent.TextDelta) result.append(event.text)
            }
            probe?.finish(SESSION_MODEL_CALL_STATUS_COMPLETED)
            result.toString().take(maxSummaryBytes)
        } catch (_: CancellationException) {
            probe?.finish(SESSION_MODEL_CALL_STATUS_CANCELLED)
            throw CancellationException()
        } catch (_: Exception) {
            probe?.finish(SESSION_MODEL_CALL_STATUS_FAILED, RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode)
            ruleBasedFallback(messages)
        }
    }

    private fun modelCallProbe(
        context: MaterializerContext?,
        recorder: ModelCallRecorder?,
        request: ModelRequest?,
        startedAt: Long,
    ): ModelCallProbe? {
        if (context == null || recorder == null) return null
        return ModelCallProbe(
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
                messageCount = request?.messages?.size ?: 0,
                toolCount = request?.tools?.size ?: 0,
            ),
            nowEpochMillis = nowEpochMillis,
            recorder = recorder,
        )
    }

    private fun compactionSource(messages: List<ModelMessage>): String =
        buildString {
            appendLine("Conversation history to summarize:")
            messages.forEachIndexed { index, message ->
                append(index + 1)
                append(". ")
                append(message.compactionLabel())
                append(": ")
                append(message.compactionContent().compactionPreview())
                appendLine()
            }
        }.take(MAX_SUMMARY_SOURCE_CHARS)

    private fun ruleBasedFallback(messages: List<ModelMessage>): String =
        messages.joinToString(separator = "\n") { message ->
            val preview = message.compactionContent().take(RULE_FALLBACK_PREVIEW_CHARS)
            "[${message.compactionLabel()}] $preview"
        }.take(maxSummaryBytes)

    private fun partition(messages: List<ModelMessage>): Triple<List<ModelMessage>, List<ModelMessage>, List<ModelMessage>> {
        val critical = mutableListOf<ModelMessage>()
        val compressible = mutableListOf<ModelMessage>()
        val droppable = mutableListOf<ModelMessage>()
        val firstUserIndex = messages.indexOfFirst { it.role == "user" && it.toolCallId == null }
        messages.forEachIndexed { index, message ->
            when (classifyMessage(message, index, firstUserIndex)) {
                MessageRetention.Critical -> critical.add(message)
                MessageRetention.Compressible -> compressible.add(message)
                MessageRetention.Droppable -> droppable.add(message)
            }
        }
        return Triple(critical, compressible, droppable)
    }

    private fun classifyMessage(message: ModelMessage, index: Int = -1, firstUserIndex: Int = -1): MessageRetention {
        if (index == 0 && firstUserIndex == 0) return MessageRetention.Critical
        if (index >= 0 && index == firstUserIndex) return MessageRetention.Critical
        if (message.isAssistantToolCallMessage()) return MessageRetention.Compressible
        if (message.role == "assistant" && message.content.contains("\"response\"")) return MessageRetention.Critical
        if (message.role == "tool" && message.toolName == "list_dir") return MessageRetention.Droppable
        if (message.role == "tool") return MessageRetention.Compressible
        return MessageRetention.Critical
    }

    private fun encodedSize(messages: List<ModelMessage>): Int =
        messages.joinToString(separator = "\n") { it.content }.toByteArray(Charsets.UTF_8).size
}

private fun summaryMessage(summary: String): ModelMessage =
    ModelMessage(role = "user", content = "Previous context summary:\n$summary")

private fun ModelMessage.isAssistantToolCallMessage(): Boolean =
    role == "assistant" && !toolCallId.isNullOrBlank() && !toolName.isNullOrBlank()

private fun ModelMessage.isToolResultMessage(): Boolean =
    role == "tool" && !toolCallId.isNullOrBlank()

private fun List<ModelMessage>.withoutToolProtocol(): List<ModelMessage> =
    filterNot { it.isAssistantToolCallMessage() || it.isToolResultMessage() }

private fun List<ModelMessage>.withoutOrphanToolProtocol(): List<ModelMessage> {
    val toolResultIds = filter { it.isToolResultMessage() }.mapNotNull { it.toolCallId }.toSet()
    val assistantIds = filter { it.isAssistantToolCallMessage() }.mapNotNull { it.toolCallId }.toSet()
    return filterNot { message ->
        (message.isAssistantToolCallMessage() && message.toolCallId.orEmpty() !in toolResultIds) ||
            (message.isToolResultMessage() && message.toolCallId.orEmpty() !in assistantIds)
    }
}

private fun ModelMessage.compactionLabel(): String =
    buildString {
        append(role)
        toolName?.takeIf { it.isNotBlank() }?.let { append(" tool=").append(it) }
        toolCallId?.takeIf { it.isNotBlank() }?.let { append(" call=").append(it) }
    }

/** Prefer protocol facts over verbose raw MCP content only in the derived summary input. */
private fun ModelMessage.compactionContent(): String {
    if (isAssistantToolCallMessage()) return "arguments=${toolArgumentsJson.orEmpty()}\n$content"
    if (role != "tool") return content
    val root = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull() ?: return content
    if (root["error"] != null && root["error"] != JsonNull) return content
    val result = root["result"] as? JsonObject ?: root
    val structured = result["structuredContent"] as? JsonObject ?: return content
    return buildJsonObject {
        root["ok"]?.let { put("ok", it) }
        root["isError"]?.let { put("providerIsError", it) }
        result["isError"]?.let { put("isError", it) }
        put("structuredContent", buildJsonObject {
            structured["status"]?.let { put("status", it) }
            structured.forEach { (key, value) -> if (key != "status") put(key, value) }
        })
    }.toString()
}

private fun String.compactionPreview(): String =
    lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(SUMMARY_MESSAGE_PREVIEW_CHARS)

private fun ModelMessage.isReadOnlyToolResult(): Boolean =
    role == "tool" && toolName in FOLDABLE_READ_ONLY_TOOL_NAMES

private fun ModelMessage.isLargeEnoughToFold(): Boolean =
    content.toByteArray(Charsets.UTF_8).size > FOLDED_TOOL_RESULT_PREVIEW_CHARS

private fun ModelMessage.isFoldedToolResult(): Boolean =
    content.startsWith(FOLDED_TOOL_RESULT_PREFIX)

private fun foldedToolResultContent(message: ModelMessage): String =
    """$FOLDED_TOOL_RESULT_PREFIX{"tool":"${message.toolName.orEmpty().escapeJson()}","originalBytes":${message.content.toByteArray(Charsets.UTF_8).size},"preview":"${message.content.compactionPreview().take(FOLDED_TOOL_RESULT_PREVIEW_CHARS).escapeJson()}"}}"""

private const val FOLDED_TOOL_RESULT_PREFIX = """{"foldedToolResult":"""

private fun String.escapeJson(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

sealed interface MessageRetention {
    data object Critical : MessageRetention
    data object Compressible : MessageRetention
    data object Droppable : MessageRetention
}

sealed interface CompactionResult {
    data class Unchanged(val messages: List<ModelMessage>) : CompactionResult
    data class Compacted(val messages: List<ModelMessage>, val droppedCount: Int, val summaryBytes: Int) : CompactionResult
}

private data class FoldedMessages(val messages: List<ModelMessage>, val count: Int)

private const val MAX_SUMMARY_BYTES = 2048
private const val MAX_SUMMARY_SOURCE_CHARS = 24000
private const val SUMMARY_MESSAGE_PREVIEW_CHARS = 1500
private const val RULE_FALLBACK_PREVIEW_CHARS = 200
private const val DEFAULT_COMPACTION_USAGE_BUDGET_TOKENS = 120_000
private const val DEFAULT_COMPACTION_USAGE_THRESHOLD_RATIO = 0.9
private const val RECENT_READ_ONLY_TOOL_RESULTS_TO_KEEP = 1
private const val FOLDED_TOOL_RESULT_PREVIEW_CHARS = 200

private val FOLDABLE_READ_ONLY_TOOL_NAMES = setOf(
    "list_dir",
    "read_file",
    "grep",
    "search_tools",
)

private val COMPACTION_PROMPT = """
You are a context compactor. Summarize the following conversation history into a concise retention block. Preserve:
- The user's original intent and any concrete values (temperatures, destinations, names).
- Tool calls that succeeded and their key results (not full JSON, just outcomes).
- Tool calls that failed and the error pattern.
- Any decisions or constraints established.
Discard:
- Verbose tool output (full catalog listings, full file contents, full schemas).
- Redundant exploration steps.
Output a single paragraph or short bullet list. Do not add commentary.
""".trimIndent()
