package cc.noharry.agent.appserver.mcp

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem

internal object McpProgressiveEvidenceCollector {
    private val schemaPathRegex = Regex("""/mcp/tools/([^/"]+)/schema\.json""")

    fun record(event: ThreadEvent, recorder: McpProgressiveEvidenceRecorder?) {
        if (recorder == null) return
        when (event) {
            is ThreadEvent.TurnStarted -> recorder.recordRunId(event.turnId)
            is ThreadEvent.ItemStarted -> (event.item as? ThreadItem.ToolCall)?.let { item ->
                schemaPathRegex.find(item.arguments)?.let { recorder.recordSchemaBrowse(it.groupValues[1]) }
            }
            is ThreadEvent.ItemCompleted -> (event.item as? ThreadItem.ToolCall)?.let { item ->
                if (item.tool.startsWith("mcp__") || item.tool.startsWith("command_")) recorder.recordBackendToolCalled(item.tool)
            }
            is ThreadEvent.TurnCompleted, is ThreadEvent.TurnFailed -> recorder.recordFinished()
            else -> Unit
        }
    }
}
