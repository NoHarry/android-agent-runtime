@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.state
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.core.loop.LoopBudgetSnapshot
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class LoopHistory(
    val runId: String,
    val messages: List<ModelMessage>,
    val budgetSnapshot: LoopBudgetSnapshot = LoopBudgetSnapshot(),
    val droppedMessages: Int = 0,
    val obligationLedger: String = "",
    val contextSystemSuffix: String = "",
)

interface LoopHistoryStore {
    fun save(
        runId: String,
        messages: List<ModelMessage>,
        budgetSnapshot: LoopBudgetSnapshot = LoopBudgetSnapshot(),
        obligationLedger: String = "",
        contextSystemSuffix: String = "",
    ): LoopHistory

    fun read(runId: String): LoopHistory?

    fun clearCached(runId: String)
}

/** Candidate storage budget; hosts using token-window compaction can disable legacy byte trimming. */
class InMemoryLoopHistoryStore(private val budgetBytes: Int = MAX_LOOP_HISTORY_BYTES) : LoopHistoryStore {
    init { require(budgetBytes > 0) { "budgetBytes must be positive." } }
    private val histories = ConcurrentHashMap<String, LoopHistory>()

    override fun save(
        runId: String,
        messages: List<ModelMessage>,
        budgetSnapshot: LoopBudgetSnapshot,
        obligationLedger: String,
        contextSystemSuffix: String,
    ): LoopHistory =
        LoopHistoryTrimmer.trim(runId, messages, budgetSnapshot, budgetBytes).copy(obligationLedger = obligationLedger, contextSystemSuffix = contextSystemSuffix)
            .also { histories[runId] = it }

    override fun read(runId: String): LoopHistory? = histories[runId]

    override fun clearCached(runId: String) {
        histories.remove(runId)
    }
}

class FileBackedLoopHistoryStore(private val root: File, budgetBytes: Int = MAX_LOOP_HISTORY_BYTES) : LoopHistoryStore {
    private val memory = InMemoryLoopHistoryStore(budgetBytes)

    override fun save(
        runId: String,
        messages: List<ModelMessage>,
        budgetSnapshot: LoopBudgetSnapshot,
        obligationLedger: String,
        contextSystemSuffix: String,
    ): LoopHistory =
        memory.save(runId, messages, budgetSnapshot, obligationLedger, contextSystemSuffix).also {
            historyFile(runId).writeReliabilityText(LoopHistoryJson.encode(it))
        }

    override fun read(runId: String): LoopHistory? =
        memory.read(runId) ?: historyFile(runId).decodeOrNull(LoopHistoryJson::decode)

    override fun clearCached(runId: String) {
        memory.clearCached(runId)
    }

    private fun historyFile(runId: String): File =
        File(File(root, RUNS_DIR), encodeFileName(runId)).resolve(LOOP_HISTORY_FILE)
}

object LoopHistoryTrimmer {
    fun trim(
        runId: String,
        messages: List<ModelMessage>,
        budgetSnapshot: LoopBudgetSnapshot = LoopBudgetSnapshot(),
        budgetBytes: Int = MAX_LOOP_HISTORY_BYTES,
    ): LoopHistory {
        require(budgetBytes > 0) { "budgetBytes must be positive." }
        var kept = messages
        var dropped = 0
        while (kept.size > 1 && encodedSize(runId, kept, budgetSnapshot, dropped) > budgetBytes) {
            val dropIndex = kept.indexOfFirstDroppableMessage()
            if (dropIndex == -1) break
            kept = kept.filterIndexed { index, _ -> index != dropIndex }
            dropped += 1
        }
        return LoopHistory(runId, kept, budgetSnapshot, dropped)
    }

    private fun encodedSize(
        runId: String,
        messages: List<ModelMessage>,
        budgetSnapshot: LoopBudgetSnapshot,
        dropped: Int,
    ): Int =
        LoopHistoryJson.encode(LoopHistory(runId, messages, budgetSnapshot, dropped)).toByteArray(Charsets.UTF_8).size
}

private fun List<ModelMessage>.indexOfFirstDroppableMessage(): Int {
    val firstUserIndex = indexOfFirst { it.role == USER_ROLE }
    return indices.firstOrNull { it != firstUserIndex } ?: -1
}

private object LoopHistoryJson {
    fun encode(history: LoopHistory): String = buildJsonObject {
        put("runId", history.runId)
        put("droppedMessages", history.droppedMessages)
        put("roundsStarted", history.budgetSnapshot.roundsStarted)
        put("toolCallsUsed", history.budgetSnapshot.toolCallsUsed)
        put("latestUsageTokens", history.budgetSnapshot.latestUsageTokens)
        history.budgetSnapshot.startedAtEpochMillis?.let { put("startedAtEpochMillis", it) }
        putJsonArray("messages") { history.messages.forEach { add(encodeMessage(it)) } }
        put("contextSystemSuffix", history.contextSystemSuffix)
        if (history.obligationLedger.isNotBlank()) put("obligationLedger", history.obligationLedger)
    }.toString()

    fun decode(value: String): LoopHistory {
        val json = Json.parseToJsonElement(value) as JsonObject
        return LoopHistory(
            runId = json.string("runId"),
            messages = (json["messages"] as? JsonArray).orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.let(::decodeMessage)
            },
            budgetSnapshot = LoopBudgetSnapshot(
                roundsStarted = json.int("roundsStarted"),
                toolCallsUsed = json.int("toolCallsUsed"),
                startedAtEpochMillis = json.longOrNull("startedAtEpochMillis"),
                latestUsageTokens = json.int("latestUsageTokens"),
            ),
            droppedMessages = json.int("droppedMessages"),
            obligationLedger = json.optionalString("obligationLedger").orEmpty(),
            contextSystemSuffix = json.optionalString("contextSystemSuffix").orEmpty(),
        )
    }

    private fun encodeMessage(message: ModelMessage): JsonObject = buildJsonObject {
        put("role", message.role)
        put("content", message.content)
        message.toolCallId?.let { put("toolCallId", it) }
        message.toolName?.let { put("toolName", it) }
        message.toolArgumentsJson?.let { put("toolArgumentsJson", it) }
        if (message.toolAdditions.isNotEmpty()) put("toolAdditions", JsonArray(message.toolAdditions.map(::JsonPrimitive)))
        // A resumed run sends these back as they were: Anthropic rejects a thinking block whose signature went missing.
        message.thinkingSignature?.takeIf { it.isNotBlank() }?.let { put("thinkingSignature", it) }
        message.assistantText.takeIf { it.isNotBlank() }?.let { put("assistantText", it) }
    }

    private fun decodeMessage(json: JsonObject): ModelMessage = ModelMessage(
        role = json.string("role"),
        content = json.string("content"),
        toolCallId = json.optionalString("toolCallId"),
        toolName = json.optionalString("toolName"),
        toolArgumentsJson = json.optionalString("toolArgumentsJson"),
        toolAdditions = (json["toolAdditions"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
        thinkingSignature = json.optionalString("thinkingSignature"),
        assistantText = json.optionalString("assistantText").orEmpty(),
    )
}

private fun JsonObject.string(name: String): String =
    this[name]?.jsonPrimitive?.contentOrNull ?: ""

private fun JsonObject.optionalString(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.int(name: String): Int =
    this[name]?.jsonPrimitive?.intOrNull ?: 0

private fun JsonObject.longOrNull(name: String): Long? =
    this[name]?.jsonPrimitive?.contentOrNull?.toLongOrNull()

const val MAX_LOOP_HISTORY_BYTES = 512 * 1024
private const val USER_ROLE = "user"
private const val LOOP_HISTORY_FILE = "loop-history.json"
