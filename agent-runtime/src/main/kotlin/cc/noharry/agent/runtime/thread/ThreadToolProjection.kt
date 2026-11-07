@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.thread

import cc.noharry.agent.*
import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.ToolSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal class ThreadToolProjection(
    private val turnId: String,
    private val nextId: () -> String,
    private val start: suspend (ThreadItem) -> Unit,
    private val update: suspend (ThreadItem) -> Unit,
    private val complete: suspend (ThreadItem) -> Unit,
    private val emit: suspend (ThreadEvent) -> Unit,
) {
    private val calls = linkedMapOf<String, ThreadItem.ToolCall>()
    private val decisions = mutableMapOf<String, ApprovalPolicy>()

    suspend fun decision(callId: String, policy: ApprovalPolicy) {
        val current = calls[callId]
        if (current == null) decisions[callId] = policy
        else {
            val decided = current.copy(approvedBy = policy)
            calls[callId] = decided
            update(decided)
        }
    }

    suspend fun started(event: AgentEvent.NativeToolCallStarted) {
        val id = event.toolCallId.ifEmpty { nextId() }
        if (id in calls) return
        val item = ThreadItem.ToolCall(
            id = nextId(), turnId = turnId, tool = event.toolName,
            source = source(event.toolName), server = server(event.toolName), arguments = event.arguments,
            approvedBy = decisions.remove(event.toolCallId),
        )
        calls[id] = item
        start(item)
    }

    suspend fun finished(event: AgentEvent.NativeToolCallFinished) {
        var key = key(event.toolCallId, event.toolName)
        if (key == null) {
            started(AgentEvent.NativeToolCallStarted(event.runId, event.toolName, "{}", event.toolCallId))
            key = key(event.toolCallId, event.toolName) ?: return
        }
        val item = calls.remove(key) ?: return
        complete(item.copy(output = event.result, status = resultStatus(event.result)))
    }

    suspend fun network(event: AgentEvent.NetworkToolActivity) {
        val key = key(event.toolCallId, event.toolName) ?: return
        val item = calls.getValue(key).copy(
            network = NetworkActivity(event.domain, event.decision, event.statusCode, event.truncated),
        )
        calls[key] = item
        update(item)
    }

    suspend fun output(text: String) {
        val entry = calls.entries.lastOrNull { it.value.source == ToolSource.EXEC } ?: return
        val item = entry.value.copy(output = entry.value.output.orEmpty() + text)
        calls[entry.key] = item
        update(item)
        emit(ThreadEvent.ItemDelta(item.id, text))
    }

    private fun key(id: String, tool: String): String? =
        if (id.isNotEmpty()) id.takeIf { it in calls }
        else calls.entries.firstOrNull { it.value.tool == tool }?.key
}

private fun resultStatus(output: String): ItemStatus {
    val root = try { Json.parseToJsonElement(output) as? JsonObject } catch (_: IllegalArgumentException) { null }
    val code = (root?.get("code") as? JsonPrimitive)?.content
    if (code in setOf("CONFIRMATION_DENIED", "POLICY_DENIED", "HOOK_DENIED")) return ItemStatus.DECLINED
    val ok = (root?.get("ok") as? JsonPrimitive)?.content
    return if (ok == "false") ItemStatus.FAILED else ItemStatus.COMPLETED
}

private fun server(tool: String): String? = tool.takeIf { it.startsWith("mcp__") }?.removePrefix("mcp__")?.substringBefore("__")

private fun source(tool: String): ToolSource = when (tool) {
    "exec_js" -> ToolSource.EXEC
    "read_file", "list_files", "grep" -> ToolSource.NATIVE_READ
    "write_file", "edit_file" -> ToolSource.NATIVE_WRITE
    "web_fetch", "web_search" -> ToolSource.NETWORK
    "update_todo" -> ToolSource.NATIVE_TODO
    "ask_async" -> ToolSource.INTERACTION
    "ask", "finish" -> ToolSource.TERMINAL
    else -> ToolSource.BACKEND
}
