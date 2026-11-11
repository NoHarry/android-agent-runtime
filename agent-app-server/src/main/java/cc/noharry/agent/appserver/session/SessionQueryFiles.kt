package cc.noharry.agent.appserver.session
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.appserver.protocol.SessionToolCall
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.runtime.encodeSessionId
import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

internal fun publishSessionQueryFiles(
    sessionRoot: File,
    sessions: List<SessionSummary>,
    turnsForSession: (String) -> List<SessionTurn>,
) {
    sessionRoot.mkdirs()
    val records = sessions.map { summary ->
        val turns = turnsForSession(summary.sessionId)
        val recentTurns = turns.takeLast(SESSION_QUERY_RECENT_TURNS)
        val dir = File(sessionRoot, encodeSessionId(summary.sessionId)).apply { mkdirs() }
        File(dir, SESSION_QUERY_SUMMARY_FILE).writeText(
            sessionQuerySummaryJson(summary, recentTurns),
            Charsets.UTF_8,
        )
        SessionQueryRecord(summary, recentTurns)
    }
    File(sessionRoot, SESSION_QUERY_INDEX_FILE).writeText(sessionQueryIndexJson(records), Charsets.UTF_8)
}

private fun sessionQueryIndexJson(records: List<SessionQueryRecord>): String = buildJsonObject {
    put("schemaVersion", SESSION_QUERY_SCHEMA_VERSION)
    put("description", SESSION_QUERY_INDEX_DESCRIPTION)
    putJsonArray("sessions") {
        records.forEach { record ->
            val summary = record.summary
            add(buildJsonObject {
                put("sessionId", summary.sessionId)
                put("title", summary.title)
                put("updatedAtEpochMillis", summary.updatedAtEpochMillis)
                put("turnCount", summary.turnCount)
                put("summaryPath", summary.querySummaryPath())
                record.lastAction?.let { action ->
                    put("lastAction", action.toQueryJson())
                }
                putJsonArray("actionDomains") {
                    record.actionDomains.forEach { add(it) }
                }
            })
        }
    }
}.toString()

private fun sessionQuerySummaryJson(
    summary: SessionSummary,
    recentTurns: List<SessionTurn>,
): String = buildJsonObject {
    put("schemaVersion", SESSION_QUERY_SCHEMA_VERSION)
    put("sessionId", summary.sessionId)
    put("title", summary.title)
    put("updatedAtEpochMillis", summary.updatedAtEpochMillis)
    put("turnCount", summary.turnCount)
    put("compactedSummary", summary.compactedSummary)
    recentTurns.asReversed().firstNotNullOfOrNull { it.meaningfulAction() }?.let { action ->
        put("lastAction", action.toQueryJson())
    }
    putJsonArray("recentTurns") {
        recentTurns.forEach { turn ->
            add(turn.toQueryJson())
        }
    }
}.toString()

private fun SessionSummary.querySummaryPath(): String =
    "$SESSION_QUERY_ROOT/${encodeSessionId(sessionId)}/$SESSION_QUERY_SUMMARY_FILE"

private fun SessionTurn.toQueryJson(): JsonObject = buildJsonObject {
    put("turnId", turnId)
    put("requestId", requestId)
    put("runId", runId)
    put("userText", userText.compactQueryText())
    put("finalStatus", finalStatus)
    put("finalResponse", finalResponse.compactQueryText())
    meaningfulAction()?.let { put("meaningfulAction", it.toQueryJson()) }
}

private fun SessionToolCall.toQueryJson(): JsonObject = buildJsonObject {
    put("toolName", toolName)
    put("domainId", actionDomainForTool(toolName))
    put("status", status)
    put("argumentsPreview", argumentsPreview.compactQueryText())
    put("resultPreview", resultPreview.compactQueryText())
}

private data class SessionQueryRecord(
    val summary: SessionSummary,
    val recentTurns: List<SessionTurn>,
) {
    val lastAction: SessionToolCall? =
        recentTurns.asReversed().firstNotNullOfOrNull { it.meaningfulAction() }

    val actionDomains: List<String> =
        recentTurns.asSequence()
            .mapNotNull { it.meaningfulAction() }
            .map { actionDomainForTool(it.toolName) }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
}

private fun actionDomainForTool(toolName: String): String =
    rawCommandName(toolName)
        .takeIf { it.startsWith(COMMAND_PREFIX) }
        ?.removePrefix(COMMAND_PREFIX)
        ?.substringBefore("_", UNKNOWN_ACTION_DOMAIN)
        ?.let { raw -> ACTION_DOMAIN_ALIASES[raw] ?: raw }
        ?: UNKNOWN_ACTION_DOMAIN

private fun rawCommandName(toolName: String): String =
    toolName.substringAfterLast(MCP_QUALIFIER_SEPARATOR, toolName)

private fun String.compactQueryText(): String =
    replace("\n", " ").replace(Regex("""\s+"""), " ").trim().take(SESSION_QUERY_TEXT_CHARS)

internal const val SESSION_QUERY_ROOT = "/$SESSIONS_DIR"
private const val SESSION_QUERY_INDEX_FILE = "index.json"
private const val SESSION_QUERY_SUMMARY_FILE = "summary.json"
/** Where the model reads the index of the past sessions it may look into. */
@cc.noharry.agent.appserver.AppServerPluginApi
const val SESSION_QUERY_INDEX_PATH = "$SESSION_QUERY_ROOT/$SESSION_QUERY_INDEX_FILE"
private const val SESSION_QUERY_INDEX_DESCRIPTION =
    "Debug session index. Use actionDomains and lastAction to choose summaryPath when cross-session continuity is needed."
private const val SESSION_QUERY_SCHEMA_VERSION = 1
private const val SESSION_QUERY_RECENT_TURNS = 3
private const val SESSION_QUERY_TEXT_CHARS = 300
private const val MCP_QUALIFIER_SEPARATOR = "__"
private const val COMMAND_PREFIX = "command_"
private const val UNKNOWN_ACTION_DOMAIN = "misc"

private val ACTION_DOMAIN_ALIASES = mapOf(
    "ac" to "air_control",
    "navi" to "navigation",
)
