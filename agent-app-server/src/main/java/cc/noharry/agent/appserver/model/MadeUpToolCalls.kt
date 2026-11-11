@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A small model sometimes calls a backend tool it made up from the prompt's tool domains: asked 「把客厅温度调到25度」,
 * Gemma 4 E4B called home__set_temperature, was told there is no such tool, and searched in the round after. A call
 * whose name has `__` but which [request] neither offers nor names anywhere goes on as search_tools for the user's
 * words and the made-up name's, so the matches come back in the round the made-up call would have spent. One search
 * per response: a second made-up call in it is dropped.
 */
@cc.noharry.agent.appserver.AppServerPluginApi
fun Flow<ModelEvent>.withMadeUpCallsSearched(request: ModelRequest): Flow<ModelEvent> {
    if (request.tools.none { it.name == SEARCH_TOOLS }) return this
    val offered = request.tools.mapTo(HashSet()) { it.name }
    return flow {
        var searched = false
        collect { event ->
            if (event !is ModelEvent.ToolCall || !event.isMadeUp(offered, request.messages)) {
                emit(event)
            } else if (!searched) {
                searched = true
                emit(ModelEvent.ToolCall(event.id, SEARCH_TOOLS, searchFor(request.messages.userWords(), event.name)))
            }
        }
    }
}

private fun ModelEvent.ToolCall.isMadeUp(offered: Set<String>, messages: List<ModelMessage>): Boolean =
    "__" in name && name !in offered && messages.none { message ->
        name in message.content || name == message.toolName || message.toolArgumentsJson?.contains(name) == true
    }

/** What the user said last: the latest user message that is not one of the app's own notes. */
private fun List<ModelMessage>.userWords(): String =
    lastOrNull { it.role == "user" && !it.content.startsWith(APP_NOTE) }?.content.orEmpty().trim().take(MAX_QUERY_WORDS)

private fun searchFor(words: String, madeUpName: String): String = buildJsonObject {
    val nameWords = madeUpName.split('_').filter { it.isNotEmpty() && it != "mcp" }
    put("query", (listOf(words) + nameWords).filter(String::isNotEmpty).joinToString(" "))
}.toString()

private const val SEARCH_TOOLS = "search_tools"

/** How the runtime starts the user-role messages it adds itself (the run's context, the tools ready). */
private const val APP_NOTE = "From the app, not typed by the user"

private const val MAX_QUERY_WORDS = 200
