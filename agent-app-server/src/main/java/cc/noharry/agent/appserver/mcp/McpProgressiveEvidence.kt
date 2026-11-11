@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.mcp
import android.content.Context
import cc.noharry.agent.AgentRuntime
import java.io.File

/**
 * Records a sanitized progressive-activation evidence file while the debug console drives the
 * 391-tool MCP fixture backend with a live model.
 *
 * The recorder is only active when the fixture backend is selected. It captures the structural
 * signals that prove the progressive path end to end:
 * - first-round model tool names (must be native-only, no backend schemas),
 * - backend tool schemas browsed by the model via `/mcp/tools/{qualified-name}/schema.json`,
 * - backend tools called directly by the model after activation,
 * - `MCP_DEBUG_CALL` trace events produced by the fixture backend,
 * - whether the run finished.
 *
 * Raw tool arguments, model text, API keys, and headers are intentionally excluded; only tool
 * names, status categories, and the simulator `statusCode` token are kept. The file is written
 * to app-private storage and is meant to be pulled off the device for assertion by the bench
 * script.
 */
internal class McpProgressiveEvidenceRecorder(
    private val context: Context,
    private val requestId: String,
) {
    private val firstRoundToolNames = linkedSetOf<String>()
    private val browsedSchemaTools = linkedSetOf<String>()
    private val backendToolsCalled = linkedSetOf<String>()
    private val mcpCallSummaries = mutableListOf<String>()
    private var completed = false
    private var runId: String? = null
    private var recordedFirstRound = false

    /** Records the tool names offered to the model on the very first round only. */
    internal fun recordFirstRoundTools(toolNames: Collection<String>) {
        if (recordedFirstRound) return
        firstRoundToolNames += toolNames
        recordedFirstRound = true
    }

    internal fun recordSchemaBrowse(toolName: String) {
        browsedSchemaTools += toolName
    }

    internal fun recordBackendToolCalled(toolName: String) {
        backendToolsCalled += toolName
    }

    internal fun recordRawToolEvent(title: String, body: String) {
        mcpSchemaToolFromRawToolEvent(title, body)?.let(::recordSchemaBrowse)
    }

    internal fun recordFinished() {
        completed = true
    }

    internal fun recordRunId(id: String) {
        runId = id
    }

    /** Drains `MCP_DEBUG_CALL` trace events from the runtime once the run is terminal. */
    internal suspend fun drainMcpDebugTrace(runtime: AgentRuntime, sessionId: String) {
        val id = runId ?: return
        val mcpEvents = try {
            runtime.diagnostics.getRuntimeTraceEvents(sessionId, id).filter { it.eventName == MCP_DEBUG_CALL_EVENT_NAME }
        } catch (_: RuntimeException) {
            emptyList()
        }
        for (event in mcpEvents) {
            val evidenceId = event.evidenceId ?: continue
            val evidence = try {
                runtime.diagnostics.getRuntimeEvidence(evidenceId)
            } catch (_: RuntimeException) {
                null
            }
            val text = evidence?.summary?.take(MAX_SUMMARY_CHARS) ?: MCP_DEBUG_CALL_EVENT_NAME
            mcpCallSummaries += text
        }
    }

    /** Writes the sanitized JSON evidence file and returns it. */
    fun write(): File {
        val dir = File(context.filesDir, EVIDENCE_DIR)
        dir.mkdirs()
        val file = File(dir, "$requestId.json")
        file.writeText(json(), Charsets.UTF_8)
        return file
    }

    fun summary(): String =
        "firstRound=${firstRoundToolNames.joinToString("|")} " +
            "browsed=${browsedSchemaTools.joinToString("|")} " +
            "called=${backendToolsCalled.joinToString("|")} " +
            "mcpDebugCalls=${mcpCallSummaries.size} completed=$completed"

    private fun json(): String {
        val firstRound = firstRoundToolNames.joinToString(separator = ", ") { "\"$it\"" }
        val browsed = browsedSchemaTools.joinToString(separator = ", ") { "\"$it\"" }
        val called = backendToolsCalled.joinToString(separator = ", ") { "\"$it\"" }
        val callsJson = mcpCallSummaries.joinToString(separator = ", ") { "\"${it.escapeJson()}\"" }
        return """
            {
              "requestId": "${requestId.escapeJson()}",
              "runId": "${(runId ?: "").escapeJson()}",
              "firstRoundToolNames": [$firstRound],
              "browsedSchemaTools": [$browsed],
              "backendToolsCalled": [$called],
              "mcpDebugCallEvents": [$callsJson],
              "completed": $completed
            }
        """.trimIndent()
    }

    private fun String.escapeJson(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        internal const val EVIDENCE_DIR = "mcp-progressive-evidence"
        internal const val MCP_DEBUG_CALL_EVENT_NAME = "MCP_DEBUG_CALL"
        internal const val MAX_SUMMARY_CHARS = 240
    }
}

internal fun mcpSchemaToolFromRawToolEvent(title: String, body: String): String? {
    if (!title.startsWith("Raw tool arguments")) return null
    if (body.rawLineValue("tool") != "read_file") return null
    return RAW_SCHEMA_PATH_REGEX.find(body.rawLineValue("arguments").orEmpty())?.groupValues?.get(1)
}

private fun String.rawLineValue(name: String): String? =
    lineSequence().firstOrNull { it.startsWith("$name=") }?.substringAfter("=")?.trim()

private val RAW_SCHEMA_PATH_REGEX = Regex("""/mcp/tools/([^/"]+)/schema\.json""")
