@file:OptIn(ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.samples.extensions

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.ToolMatcher
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.runtime.AgentRuntimeBuilder
import cc.noharry.agent.runtime.functionTools
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive

/**
 * The runtime's extension points, with no network and no key: a model of your own, tools from your code, and a hook
 * that refuses a call whatever the model wants.
 *
 *     ./gradlew :samples:extensions:run
 */
fun main() = runBlocking { runSample().forEach(::println) }

/** Runs one request and returns what happened, a line per step, ending with the notes kept. */
suspend fun runSample(): List<String> {
    val notes = mutableListOf("call the dentist")
    val noteTools = functionTools("notes") {
        tool(
            "save", "Saves a note.",
            inputSchema = """{"type":"object","properties":{"text":{"type":"string"}},"required":["text"]}""",
        ) { args -> notes += args.getValue("text").jsonPrimitive.content; "saved" }
        tool("clear", "Deletes every note.") { notes.clear(); "cleared" }
    }
    val transcript = mutableListOf<String>()
    // No workspace: the agent has no files of its own here, and finds the providers' tools with search_tools.
    val runtime = AgentRuntimeBuilder.create()
        .model(ScriptedModel())
        .tools(noteTools)
        // The model sees a provider's tools as mcp__<provider id>__<tool>.
        .hook(
            RefuseDeleting { tool, reason -> transcript += "refused $tool: $reason" },
            ToolMatcher.toolName("mcp__notes__clear"),
        )
        .build()
    try {
        runtime.startThread().runStreamed("Remember to buy milk, then tidy up my notes.").collect { event ->
            when (event) {
                is ThreadEvent.ItemStarted -> (event.item as? ThreadItem.ToolCall)?.let {
                    transcript += "model called ${it.tool}"
                }
                is ThreadEvent.TurnCompleted -> transcript += "answer: ${event.result.finalResponse}"
                is ThreadEvent.TurnFailed -> transcript += "failed: ${event.error.code}"
                else -> Unit
            }
        }
    } finally {
        runtime.close()
    }
    return transcript + "notes: $notes"
}

/**
 * A model of your own: anything that turns a [ModelRequest] into [ModelEvent]s, such as an on-device model or a
 * provider the runtime has no client for. This one follows a script, by the last tool result it has seen. Tools from
 * providers are found with `search_tools` first, as a real model finds them, and then join the request's tools. It
 * saves a note, tries to clear the notes, and answers through `finish` with what the results said.
 */
class ScriptedModel : ModelClient {
    // One request at a time, which is also the default; a client that takes overlapping requests says so here.
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val last = request.messages.lastOrNull { it.role == "tool" }
        val call = when {
            last == null -> ModelEvent.ToolCall("call-1", "search_tools", """{"query":"note"}""")
            last.toolName == "search_tools" ->
                ModelEvent.ToolCall("call-2", toolNamed(request, "save"), """{"text":"buy milk"}""")
            last.toolName == toolNamed(request, "save") ->
                ModelEvent.ToolCall("call-3", toolNamed(request, "clear"), "{}")
            else -> {
                val refused = "HOOK_DENIED" in last.content
                val cleared = if (refused) "clearing the notes was refused" else "the notes are cleared"
                ModelEvent.ToolCall("call-4", "finish", """{"response":"Saved \"buy milk\"; $cleared."}""")
            }
        }
        emit(call)
        emit(ModelEvent.Completed)
    }

    private fun toolNamed(request: ModelRequest, tool: String): String =
        request.tools.first { it.name.endsWith("__$tool") }.name
}

/** A host's rule, kept outside the model: the tool it is registered for never runs, whatever the model asks for. */
class RefuseDeleting(private val refused: (tool: String, reason: String) -> Unit) : RuntimeHook {
    override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
        val reason = "Deleting notes is not allowed here."
        refused(call.toolName, reason)
        return HookDecision.Deny(reason = reason)
    }
}
