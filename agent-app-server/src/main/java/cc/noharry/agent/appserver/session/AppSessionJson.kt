@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.session
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.SessionToolCall
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.appserver.protocol.UNTITLED_SESSION_TITLE
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal object AppSessionJson {
    fun encodeSummary(summary: SessionSummary): String = buildJsonObject {
        put("schemaVersion", DEBUG_SESSION_SCHEMA_VERSION)
        put("sessionId", summary.sessionId)
        put("title", summary.title)
        put("updatedAtEpochMillis", summary.updatedAtEpochMillis)
        put("turnCount", summary.turnCount)
        put("compactedSummary", summary.compactedSummary)
    }.toString()

    fun decodeSummary(text: String): SessionSummary? =
        runCatching {
            val obj = Json.parseToJsonElement(text).jsonObject
            SessionSummary(
                sessionId = obj.string("sessionId"),
                title = obj.string("title").ifBlank { UNTITLED_SESSION_TITLE },
                updatedAtEpochMillis = obj.long("updatedAtEpochMillis"),
                turnCount = obj.int("turnCount"),
                compactedSummary = obj.string("compactedSummary"),
            )
        }.getOrNull()

    fun encodeTurn(turn: SessionTurn): String = buildJsonObject {
        put("schemaVersion", DEBUG_SESSION_SCHEMA_VERSION)
        put("turnId", turn.turnId)
        put("requestId", turn.requestId)
        put("runId", turn.runId)
        put("sessionId", turn.sessionId)
        put("scene", turn.scene)
        put("userText", turn.userText)
        put("startedAtEpochMillis", turn.startedAtEpochMillis)
        put("completedAtEpochMillis", turn.completedAtEpochMillis)
        put("finalStatus", turn.finalStatus)
        put("finalResponse", turn.finalResponse)
        put("completionOutcome", turn.completionOutcome)
        put("replayStatus", turn.replayStatus)
        put("replaySummary", turn.replaySummary)
        if (turn.usage.requestCount > 0) put("usage", turn.usage.toJson())
        putJsonArray("tools") {
            turn.tools.forEach { tool -> add(tool.toJson()) }
        }
    }.toString()

    fun decodeTurn(text: String): SessionTurn? =
        runCatching {
            val obj = Json.parseToJsonElement(text).jsonObject
            SessionTurn(
                turnId = obj.string("turnId"),
                requestId = obj.string("requestId"),
                runId = obj.string("runId"),
                sessionId = obj.string("sessionId"),
                scene = obj.string("scene"),
                userText = obj.string("userText"),
                startedAtEpochMillis = obj.long("startedAtEpochMillis"),
                completedAtEpochMillis = obj.long("completedAtEpochMillis"),
                finalStatus = obj.string("finalStatus"),
                finalResponse = obj.string("finalResponse"),
                tools = obj["tools"]?.jsonArray?.mapNotNull { tool ->
                    (tool as? JsonObject)?.toTool()
                }.orEmpty(),
                completionOutcome = obj.decodedCompletionOutcome(),
                replayStatus = obj.decodedReplayStatus(),
                replaySummary = obj.string("replaySummary"),
                usage = (obj["usage"] as? JsonObject)?.toTokenUsage() ?: TokenUsageState(),
            )
        }.getOrNull()
}

private fun SessionToolCall.toJson(): JsonObject = buildJsonObject {
    put("sequence", sequence)
    put("toolCallId", toolCallId)
    put("toolName", toolName)
    put("argumentsPreview", argumentsPreview)
    put("resultPreview", resultPreview)
    put("status", status)
}

private fun JsonObject.toTool(): SessionToolCall =
    SessionToolCall(
        sequence = int("sequence"),
        toolName = string("toolName"),
        argumentsPreview = string("argumentsPreview"),
        resultPreview = string("resultPreview"),
        status = string("status"),
        toolCallId = string("toolCallId"),
    )

private fun JsonObject.string(name: String): String =
    (this[name] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun JsonObject.long(name: String): Long =
    (this[name] as? JsonPrimitive)?.longOrNull ?: 0L

private fun JsonObject.int(name: String): Int =
    (this[name] as? JsonPrimitive)?.intOrNull ?: 0

private fun JsonObject.decodedCompletionOutcome(): String {
    val raw = string("completionOutcome")
    return cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.entries
        .firstOrNull { it.name == raw }
        ?.name
        ?: cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome.UNKNOWN.name
}

private fun JsonObject.decodedReplayStatus(): String =
    string("replayStatus").takeIf {
        it == DEBUG_REPLAY_STATUS_PASSED || it == DEBUG_REPLAY_STATUS_FAILED
    } ?: DEBUG_REPLAY_STATUS_NOT_RUN

private const val DEBUG_SESSION_SCHEMA_VERSION = 2

private fun TokenUsageState.toJson(): JsonObject = buildJsonObject {
    put("requests", requestCount)
    put("input", inputTokens)
    put("output", outputTokens)
    put("reasoning", reasoningTokens)
    put("total", totalTokens)
    put("cachedInput", cachedInputTokens)
    put("cacheWrite", cacheWriteTokens)
    put("cacheHit", cacheHitTokens)
    put("cacheMiss", cacheMissTokens)
}

private fun JsonObject.toTokenUsage(): TokenUsageState {
    fun count(name: String): Int = (this[name] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
    return TokenUsageState(
        requestCount = count("requests"),
        inputTokens = count("input"),
        outputTokens = count("output"),
        reasoningTokens = count("reasoning"),
        totalTokens = count("total"),
        cachedInputTokens = count("cachedInput"),
        cacheWriteTokens = count("cacheWrite"),
        cacheHitTokens = count("cacheHit"),
        cacheMissTokens = count("cacheMiss"),
    )
}
