package cc.noharry.agent.kernel.core

data class McpDebugCallRecord(
    val runId: String,
    val toolName: String,
    val callJson: String,
    val validationOk: Boolean,
    val validationCode: String? = null,
    val validationMessage: String? = null,
    val validationSchemaPath: String? = null,
)

interface McpDebugTraceRecordSource {
    fun takeMcpDebugTraceRecords(runId: String): List<McpDebugCallRecord>
}

fun interface RuntimeMcpDebugTraceSink {
    suspend fun record(record: McpDebugCallRecord)

    companion object {
        val None = RuntimeMcpDebugTraceSink { }
    }
}

internal const val MCP_DEBUG_CALL_EVENT = "MCP_DEBUG_CALL"
