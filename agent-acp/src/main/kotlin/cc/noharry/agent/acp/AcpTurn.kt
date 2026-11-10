@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.acp

import cc.noharry.agent.Approval
import cc.noharry.agent.ApprovalScope
import cc.noharry.agent.ItemStatus
import cc.noharry.agent.Question
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.spi.ToolSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One session's items as `session/update` notifications, for a running turn or a replayed history. Text streams as
 * chunks under the item's id; a tool call is announced once and then updated; an approval becomes a permission request.
 */
internal class AcpTurn(
    private val connection: AcpConnection,
    private val sessionId: String,
    private val messages: AcpAgentMessages,
    private val scope: CoroutineScope,
) {
    private val streamed = mutableMapOf<String, Int>()
    private val announced = mutableSetOf<String>()
    private val said = mutableSetOf<String>()
    private val asked = mutableSetOf<String>()
    private val toolStates = mutableMapOf<String, Pair<cc.noharry.agent.ItemStatus, Boolean>>()
    private var lastTool: ThreadItem.ToolCall? = null

    suspend fun on(event: ThreadEvent) {
        when (event) {
            is ThreadEvent.ItemStarted -> started(event.item)
            is ThreadEvent.ItemDelta -> delta(event.itemId, event.text)
            // Output so far comes with the completed call, not again with every update.
            is ThreadEvent.ItemUpdated -> (event.item as? ThreadItem.ToolCall)?.let { tool(it.copy(output = null)) }
            is ThreadEvent.ItemCompleted -> completed(event.item, replay = false)
            else -> Unit
        }
    }

    /** A stored item, as `session/load` replays it. */
    suspend fun replay(item: ThreadItem) = completed(item, replay = true)

    private suspend fun started(item: ThreadItem) {
        when (item) {
            is ThreadItem.AgentMessage -> text(MESSAGE, item.id, item.text)
            is ThreadItem.Reasoning -> text(THOUGHT, item.id, item.text)
            is ThreadItem.ToolCall -> tool(item)
            is Approval -> askPermission(item)
            // A question asked while the turn goes on shows when it is asked, not when it has lapsed.
            is Question -> if (item.async && asked.add(item.id)) chunk(MESSAGE, item.id, question(item))
            else -> Unit
        }
    }

    private suspend fun delta(itemId: String, text: String) {
        val kind = kinds[itemId] ?: return
        chunk(kind, itemId, text)
        streamed[itemId] = (streamed[itemId] ?: 0) + text.length
    }

    private suspend fun completed(item: ThreadItem, replay: Boolean) {
        when (item) {
            is ThreadItem.AgentMessage -> { text(MESSAGE, item.id, item.text); said += item.text.trim() }
            is ThreadItem.Reasoning -> text(THOUGHT, item.id, item.text)
            is ThreadItem.UserMessage -> if (replay || item.steer) chunk(USER, item.id, item.text())
            is ThreadItem.ToolCall -> tool(item)
            // A provider's own search reports some steps without a query; those say nothing to show.
            is ThreadItem.WebSearch -> if (item.query.isNotBlank()) finishedTool(item.id, "${messages.search}: ${item.query}", "search")
            is ThreadItem.FileChange -> finishedTool(item.id, "${item.path} (+${item.added} -${item.removed})", "edit")
            // The model usually says a blocking question as its message too; say it only when it did not.
            is Question -> if (asked.add(item.id) && (item.async || item.text.trim() !in said)) chunk(MESSAGE, item.id, question(item))
            else -> Unit
        }
    }

    private val kinds = mutableMapOf<String, String>()

    /** What of [full] has not been sent yet, as one chunk. */
    private suspend fun text(kind: String, itemId: String, full: String) {
        kinds[itemId] = kind
        val sent = streamed[itemId] ?: 0
        if (full.length <= sent) return
        chunk(kind, itemId, full.substring(sent))
        streamed[itemId] = full.length
    }

    private suspend fun chunk(kind: String, itemId: String, text: String) {
        if (text.isEmpty()) return
        update(buildJsonObject { put("sessionUpdate", kind); put("messageId", itemId); put("content", textBlock(text)) })
    }

    private suspend fun tool(call: ThreadItem.ToolCall) {
        if (call.hidden()) return
        if (call.source == ToolSource.NATIVE_TODO) {
            if (call.status == ItemStatus.COMPLETED) planEntries(call)?.let { entries ->
                update(buildJsonObject { put("sessionUpdate", "plan"); put("entries", entries) })
            }
            return
        }
        lastTool = call
        val first = announced.add(call.id)
        // An update that changes nothing the client shows (a network note, output so far) is not worth a message.
        val shown = call.status to (call.output != null)
        if (!first && toolStates[call.id] == shown) return
        toolStates[call.id] = shown
        update(JsonObject(mapOf("sessionUpdate" to kotlinx.serialization.json.JsonPrimitive(if (first) "tool_call" else "tool_call_update")) +
            toolCallFields(call, announce = first)))
    }

    private suspend fun finishedTool(id: String, title: String, kind: String) = update(buildJsonObject {
        put("sessionUpdate", "tool_call"); put("toolCallId", id); put("title", title); put("kind", kind); put("status", "completed")
    })

    private fun question(item: Question): String =
        (listOf(item.text) + item.choices.mapIndexed { index, choice -> "${index + 1}. $choice" }).joinToString("\n")

    /**
     * Asks the client; the turn waits inside the runtime until the answer, its expiry or a cancel. A call that always
     * asks comes here in every mode, approve-all included, and offers no "always": its yes cannot be remembered.
     */
    private fun askPermission(approval: Approval) {
        val call = lastTool?.takeIf { it.tool == approval.tool && it.status == ItemStatus.IN_PROGRESS }
        val params = buildJsonObject {
            put("sessionId", sessionId)
            put("toolCall", buildJsonObject {
                put("toolCallId", call?.id ?: approval.id)
                put("title", approval.reason.ifBlank { approval.tool })
            })
            put("options", buildJsonArray {
                add(option(ALLOW_ONCE, messages.allowOnce, "allow_once"))
                if (!approval.alwaysAsk) add(option(ALLOW_ALWAYS, messages.allowAlways, "allow_always"))
                add(option(REJECT_ONCE, messages.reject, "reject_once"))
            })
        }
        scope.launch { decide(approval, params) }
    }

    /** A cancelled outcome comes with session/cancel, which interrupts the turn; a failure leaves it to expire. */
    @Suppress("TooGenericExceptionCaught") // Nothing here may end the connection's other sessions.
    private suspend fun decide(approval: Approval, params: JsonObject) {
        try {
            val outcome = connection.request("session/request_permission", params)["outcome"] as? JsonObject
            when (outcome?.takeIf { it.text("outcome") == "selected" }?.text("optionId")) {
                ALLOW_ONCE -> approval.approve(ApprovalScope.ONCE)
                ALLOW_ALWAYS -> approval.approve(ApprovalScope.THREAD)
                REJECT_ONCE -> approval.deny()
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    private fun option(id: String, name: String, kind: String) = buildJsonObject {
        put("optionId", id); put("name", name); put("kind", kind)
    }

    private suspend fun update(update: JsonObject) = connection.notify("session/update", buildJsonObject {
        put("sessionId", sessionId); put("update", update)
    })

    private companion object {
        const val MESSAGE = "agent_message_chunk"
        const val THOUGHT = "agent_thought_chunk"
        const val USER = "user_message_chunk"
        const val ALLOW_ONCE = "allow_once"
        const val ALLOW_ALWAYS = "allow_always"
        const val REJECT_ONCE = "reject_once"
    }
}
