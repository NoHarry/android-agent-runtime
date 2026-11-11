@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.model

import cc.noharry.agent.appserver.runtime.AppHost
import cc.noharry.agent.runtime.RuntimeInspection
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A model that plays a script, for a host's tests and demos with no network: each turn the person starts is matched
 * against the script's turns in order ([Script.on], then [Script.otherwise]); the matching turn calls the host's tools
 * ([Turn.call]) one after another and ends with its answer ([Turn.answer]), which may be made from the calls' results.
 * It speaks the model's side of the server for the script: it finds each tool with `search_tools` (a tool the
 * conversation was shown before, in a search's matches or the app's list of tools ready, is called straight away; one
 * the run refuses as not loaded is searched for and called again), calls it through `call_tool` under its full name
 * (`mcp__<provider>__<tool>`), ends the turn with `finish`, and reads past the app's notes that follow the person's
 * words. A tool its search does not find ends the turn saying so. It keeps nothing between requests, so one instance
 * serves every run.
 *
 * A host gives it as [cc.noharry.agent.appserver.server.AppServerConfig.simulatedModel]; the simulated backend plays it
 * ([cc.noharry.agent.appserver.server.AppServerConfig.simulatedBackend] starts the server on that backend).
 */
class ScriptedModel(script: Script.() -> Unit) : ModelClient {
    private val turns = Script().apply(script)

    /** One request at a time, the runtime's default. */
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        emit(next(request))
        emit(ModelEvent.Completed)
    }

    /** The script: turns for what the person says, tried in the order they are written. */
    class Script internal constructor() {
        internal val matchers = mutableListOf<(String) -> Turn?>()
        internal var fallback: (String) -> Turn = { said -> Turn().apply { answer("No turn of the script covers \"$said\".") } }

        /** A turn for words that contain [words], ignoring case. */
        fun on(words: String, turn: Turn.() -> Unit) {
            matchers += { said -> if (said.contains(words, ignoreCase = true)) Turn().apply(turn) else null }
        }

        /** A turn for words in which [pattern] finds a match; [turn] gets the match, its groups included. */
        fun on(pattern: Regex, turn: Turn.(MatchResult) -> Unit) {
            matchers += { said -> pattern.find(said)?.let { found -> Turn().apply { turn(found) } } }
        }

        /** The turn for words no other turn matches; without it the model says the script has no turn for them. */
        fun otherwise(turn: Turn.(said: String) -> Unit) {
            fallback = { said -> Turn().apply { turn(said) } }
        }

        internal fun turnFor(said: String): Turn = matchers.firstNotNullOfOrNull { it(said) } ?: fallback(said)
    }

    /** One turn: tool calls in the order written, then the answer. */
    class Turn internal constructor() {
        internal val calls = mutableListOf<Pair<String, JsonObject>>()
        internal var answer: (List<String>) -> String = { "" }

        /** Calls [tool] of the host's tool provider whose `providerId` is [provider], with [arguments] (a JSON object's text). */
        fun call(provider: String, tool: String, arguments: String) {
            val parsed = runCatching { Json.parseToJsonElement(arguments) }.getOrNull()
            require(parsed is JsonObject) { "The arguments for $tool are not a JSON object: $arguments" }
            calls += RuntimeInspection.qualifiedToolName(provider, tool) to parsed
        }

        /** Calls [tool] of [provider] with [arguments]: strings, numbers, booleans, null, and lists and maps of them. */
        fun call(provider: String, tool: String, arguments: Map<String, Any?> = emptyMap()) =
            call(provider, tool, toJson(arguments).toString())

        /** Ends the turn with [text]. */
        fun answer(text: String) {
            answer = { text }
        }

        /**
         * Ends the turn with what [text] makes of the calls' results, in the order of the calls: each the text the
         * model was given for it, which is the tool's `resultJson` (or the server's JSON error when the call did not run).
         */
        fun answer(text: (results: List<String>) -> String) {
            answer = text
        }
    }

    private fun next(request: ModelRequest): ModelEvent.ToolCall {
        val messages = request.messages
        val asked = messages.indexOfLast { it.role == USER && !it.fromTheApp() }
        val turn = turns.turnFor(messages.getOrNull(asked)?.content.orEmpty().trim())
        val progress = Progress(messages.drop(asked + 1))
        val step = turn.calls.getOrNull(progress.results.size)
            ?: return finish(turn.answer(progress.results), SUCCESS)
        val (name, arguments) = step
        return when {
            // A tool the request declares (a service that loads tools natively) is called by its name.
            request.tools.any { it.name == name } -> call(name, arguments.toString())
            // Right after a search: the tool if it found it.
            progress.searched -> if (offers(progress.lastSearch, name)) {
                callTool(name, arguments)
            } else {
                // Said, not asked: the person has nothing to answer (a question mark would read as a question).
                finish("$name is not among the tools search_tools found; its provider may not be mounted.", BLOCKED)
            }
            // Refused as not loaded in this run (the app restarted since the conversation found it, say): searched for, as the refusal says.
            progress.unloaded -> call(SEARCH_TOOLS, searchFor(name))
            // Shown earlier in the conversation, in a search's matches or the app's list of tools ready: callable.
            messages.any { it.shows(name) } -> callTool(name, arguments)
            else -> call(SEARCH_TOOLS, searchFor(name))
        }
    }

    private fun call(name: String, arguments: String) = ModelEvent.ToolCall("scripted-${UUID.randomUUID()}", name, arguments)

    private fun callTool(name: String, arguments: JsonObject) =
        call(CALL_TOOL, JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to arguments)).toString())

    private fun finish(response: String, outcome: String): ModelEvent.ToolCall =
        call(FINISH, buildJsonObject { put("response", response); put("outcome", outcome) }.toString())

    /** Keywords that find [name] (`mcp__timer__start_timer`): its tool's words, then the provider's. */
    private fun searchFor(name: String): String {
        val parts = name.removePrefix("mcp__").split("__")
        val words = (parts.drop(1) + parts.take(1)).joinToString(" ") { it.replace('_', ' ') }
        return buildJsonObject { put("query", words); put("max_results", SEARCH_RESULTS) }.toString()
    }

    /**
     * What the turn did so far: the results of its calls (not its searches, nor a first refusal as not loaded, after
     * which the call is searched for and made again), whether its last call was a search and what that search found,
     * and whether the call in hand was refused as not loaded and not searched for since.
     */
    private class Progress(turn: List<ModelMessage>) {
        val results = mutableListOf<String>()
        var searched = false
        var lastSearch = ""
        var unloaded = false

        init {
            val names = turn.filter { it.role == ASSISTANT && it.toolCallId != null }.associate { it.toolCallId to it.toolName }
            var refused = false
            turn.filter { it.role == TOOL }.forEach { result ->
                searched = names[result.toolCallId] == SEARCH_TOOLS
                when {
                    searched -> lastSearch = result.content
                    !refused && NOT_LOADED in result.content -> refused = true
                    else -> {
                        results += result.content
                        refused = false
                    }
                }
            }
            unloaded = refused && !searched
        }
    }

    private companion object {
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"
        const val SEARCH_TOOLS = "search_tools"
        const val CALL_TOOL = "call_tool"
        const val FINISH = "finish"
        const val SUCCESS = "success"
        const val BLOCKED = "blocked"
        const val SEARCH_RESULTS = 20

        /** Every note the app adds to a request starts so: the run's context, the tools ready, a steer's frame. */
        const val APP_NOTE = "From the app, not typed by the user"

        /** How the runtime starts its list of tools ready for a request. */
        const val TOOLS_READY = "$APP_NOTE: tools ready for this request."

        /** The runtime's refusal of a tool the run has not loaded: find it with search_tools, then call it. */
        const val NOT_LOADED = "\"code\":\"INACTIVE_BACKEND_TOOL\""

        fun ModelMessage.fromTheApp(): Boolean =
            content.startsWith(APP_NOTE) || AppHost.persona.identity.let { it.isNotEmpty() && content.startsWith(it) }

        /** Whether this message shows [name] with its schema: a search's match, or a line of the tools-ready list. */
        fun ModelMessage.shows(name: String): Boolean = when {
            role == TOOL -> offers(content, name)
            role == USER && content.startsWith(TOOLS_READY) -> content.lineSequence().any { it.startsWith("- $name: ") }
            else -> false
        }

        /** Whether a search's result [text] has [name] among its matches. */
        fun offers(text: String, name: String): Boolean = "\"name\":\"$name\"" in text

        fun toJson(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJson(item) })
            is Iterable<*> -> JsonArray(value.map(::toJson))
            else -> JsonPrimitive(value.toString())
        }
    }
}
