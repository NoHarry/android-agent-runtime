@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.ThreadItem
import cc.noharry.agent.spi.HookContext
import cc.noharry.agent.spi.HookDecision
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.RuntimeHook
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionEventType
import cc.noharry.agent.spi.SessionJson
import cc.noharry.agent.spi.ToolCallContext
import cc.noharry.agent.spi.ToolCallResult
import cc.noharry.agent.spi.ToolDescriptor
import cc.noharry.agent.spi.ToolUse
import cc.noharry.agent.spi.mcp.McpClientProvider
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelMessage
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A host's fields on tool calls ([ToolArguments]): the session keeps the call as written, everything else reads it without them. */
class ToolArgumentsTest {
    @Test fun theToolHooksEventsAndLogStartGetTheCallWithoutTheFieldWhileTheModelCallKeepsIt() = runBlocking {
        val model = ThreadScriptModel(scripts(1))
        val provider = provider()
        val hooked = mutableListOf<String>()
        val (events, log) = runtime(model, provider, threadRoot(), hooked).use { runtime ->
            val thread = runtime.startThread()
            val events = thread.runStreamed("cool it").toList()
            events to runtime.diagnostics.replaySessionEvents(thread.id, (events.first() as ThreadEvent.TurnStarted).turnId)
        }

        assertEquals(listOf(BARE), provider.calledArguments)
        assertTrue(hooked.contains(BARE) && hooked.none { "note" in it })
        val started = events.mapNotNull { ((it as? ThreadEvent.ItemStarted)?.item as? ThreadItem.ToolCall) }
        assertEquals(BARE, started.single { it.tool == qualifiedMcpTool("set_temperature") }.arguments)
        assertTrue(log.toolCall(SessionEventType.MODEL_TOOL_CALL).containsKey("note"))
        assertFalse(log.toolCall(SessionEventType.TOOL_STARTED).containsKey("note"))
        // The next request shows the model its call as it wrote it.
        assertEquals(WRITTEN, model.requests[2].messages.writtenCall("c1").toolArgumentsJson)
    }

    @Test fun aNewRuntimeOverTheSameStorageSendsTheCallAsTheEarlierRunDid() = runBlocking {
        val root = threadRoot()
        val first = ThreadScriptModel(scripts(1))
        val threadId = runtime(first, provider(), root).use { runtime ->
            runtime.startThread().also { it.runStreamed("cool it").toList() }.id
        }
        // As after a process restart: another runtime, model and session history over the same storage.
        val next = ThreadScriptModel(listOf(finishEvents("done")))
        runtime(next, provider(), root).use { it.resumeThread(threadId).runStreamed("and now?").toList() }

        val sent = next.requests.single().messages.writtenCall("c1")
        assertEquals(WRITTEN, sent.toolArgumentsJson)
        assertEquals(first.requests[2].messages.writtenCall("c1"), sent)
    }

    @Test fun aCallToolCallRunsWithoutTheFieldOnItOrInsideItsArgumentsAndIsSentBackAsWritten() = runBlocking {
        val wire = """{"name":"${qualifiedMcpTool("set_temperature")}","arguments":{"value":22,"note":"inside"},"note":"outside"}"""
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("s1", "search_tools", """{"query":"set_temperature"}"""), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("c1", "call_tool", wire), ModelEvent.Completed),
            finishEvents("done"),
        ))
        val provider = provider()
        val notes = NoteField()
        threadBuilder(model).tools(provider).deferredBackendTools().toolArguments(notes).build().use { runtime ->
            runtime.startThread().runStreamed("cool it").toList()
        }

        assertEquals(listOf(BARE), provider.calledArguments)
        val (call, written, schema) = notes.seen.single { it.second.id == "c1" }
        assertEquals(qualifiedMcpTool("set_temperature") to "call_tool", call.name to written.name)
        assertEquals(wire, written.argumentsJson)
        // The host reads the schema of the tool call_tool names, which the model's tool list does not carry.
        assertEquals(STRICT_SCHEMA, schema)
        val sent = model.requests[2].messages.writtenCall("c1")
        assertEquals("call_tool" to wire, sent.toolName to sent.toolArgumentsJson)
    }

    @Test fun aCallThatWaitsForApprovalIsApprovedWithoutTheFieldAndKeptAsWritten() = runBlocking {
        val model = ThreadScriptModel(scripts(1))
        val provider = provider()
        threadBuilder(model).tools(provider).hook(confirmUntilApproved()).toolArguments(NoteField()).build().use { runtime ->
            runtime.startThread().collectApproving("cool it") { approval -> assertEquals(BARE, approval.arguments) }
        }

        assertEquals(listOf(BARE), provider.calledArguments)
        assertEquals(WRITTEN, model.requests[2].messages.writtenCall("c1").toolArgumentsJson)
    }

    @Test fun aToolWhoseOwnSchemaHasThePropertyIsGivenItsSchemaAndKeepsIt() = runBlocking {
        val owns = """{"type":"object","properties":{"note":{"type":"string"}},"required":["note"],"additionalProperties":false}"""
        val model = ThreadScriptModel(listOf(
            listOf(schemaRead("announce"), ModelEvent.Completed),
            listOf(ModelEvent.ToolCall("c1", qualifiedMcpTool("announce"), """{"note":"Hi"}"""), ModelEvent.Completed),
            finishEvents("done"),
        ))
        val provider = LoopMcpProvider(exposedTools = listOf(mcpTool("announce")), schemaJson = owns)
        val notes = NoteField()
        threadBuilder(model).tools(provider).toolArguments(notes).build().use { runtime -> runtime.startThread().runStreamed("say hi").toList() }

        assertEquals(owns, notes.seen.single { it.first.id == "c1" }.third)
        assertEquals(listOf("""{"note":"Hi"}"""), provider.calledArguments)
    }

    @Test fun aTurnCutShortMidRoundKeepsTheCallItRanAndTheOneItCutAsWritten() = runBlocking {
        val fan = """{"speed":3,"note":"Fan up."}"""
        val model = ThreadScriptModel(listOf(
            listOf(schemaRead("set_fan"), schemaRead("set_temperature"), ModelEvent.Completed),
            listOf(
                ModelEvent.ToolCall("c0", qualifiedMcpTool("set_fan"), fan),
                ModelEvent.ToolCall("c1", qualifiedMcpTool("set_temperature"), WRITTEN),
                ModelEvent.Completed,
            ),
            finishEvents("done"),
        ))
        val provider = HangingProvider()
        threadBuilder(model).tools(provider).toolArguments(NoteField()).build().use { runtime ->
            val thread = runtime.startThread()
            val cut = async { thread.runStreamed("cool it").toList() }
            provider.hanging.await()
            thread.interrupt()
            cut.await()
            thread.runStreamed("and now?").toList()
        }

        assertEquals(listOf("""{"speed":3}"""), provider.ran)
        val next = model.requests.last().messages
        assertEquals(fan, next.writtenCall("c0").toolArgumentsJson)
        assertEquals(WRITTEN, next.writtenCall("c1").toolArgumentsJson)
    }

    @Test fun callsThatDifferOnlyInTheFieldAreRepeats() = runBlocking {
        val model = ThreadScriptModel(scripts(3))
        threadBuilder(model).tools(provider()).toolArguments(NoteField()).build().use { runtime ->
            runtime.startThread().runStreamed("cool it").toList()
        }

        assertTrue(model.requests.last().messages.any { it.content.contains("repeating the exact same tool call") })
    }

    @Test fun withoutAHostsFieldsToolsGetTheCallsAsWritten() = runBlocking {
        val model = ThreadScriptModel(scripts(1))
        val provider = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        threadBuilder(model).tools(provider).build().use { runtime -> runtime.startThread().runStreamed("cool it").toList() }

        assertEquals(listOf(WRITTEN), provider.calledArguments)
        assertEquals(WRITTEN, model.requests[2].messages.writtenCall("c1").toolArgumentsJson)
    }

    private fun runtime(model: ThreadScriptModel, provider: LoopMcpProvider, root: File, hooked: MutableList<String> = mutableListOf()) =
        threadBuilder(model, root).sessionEventLogRoot(File(root, "events")).tools(provider).toolArguments(NoteField())
            .hook(object : RuntimeHook {
                override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
                    hooked += call.argumentsJson
                    return HookDecision.Allow()
                }
            }).build()

    /** The tool's schema refuses arguments it does not declare, as many MCP servers' do. */
    private fun provider() = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")), schemaJson = STRICT_SCHEMA)

    /** Reads the schema, calls set_temperature [calls] times with a note that changes each time, then finishes. */
    private fun scripts(calls: Int): List<List<ModelEvent>> =
        listOf(listOf(schemaRead("set_temperature"), ModelEvent.Completed)) +
            (1..calls).map { listOf(ModelEvent.ToolCall("c$it", qualifiedMcpTool("set_temperature"), written(it)), ModelEvent.Completed) } +
            listOf(finishEvents("done"))

    private fun written(call: Int) = if (call == 1) WRITTEN else """{"value":22,"note":"Still on it ($call)."}"""

    private fun List<ModelMessage>.writtenCall(id: String): ModelMessage = single { it.role == "assistant" && it.toolCallId == id }

    private fun List<SessionEvent>.toolCall(type: SessionEventType, id: String = "c1"): Map<String, SessionJson> =
        filter { it.type == type }.map { it.payload as SessionEventPayload.ToolCall }.single { it.toolCallId == id }
            .let { (it.arguments as SessionJson.Object).values }

    private companion object {
        const val WRITTEN = """{"value":22,"note":"Cooling it down."}"""
        const val BARE = """{"value":22}"""
        const val STRICT_SCHEMA = """{"type":"object","properties":{"value":{"type":"integer"}},"additionalProperties":false}"""
    }
}

/**
 * Takes the field `note` off every call whose tool's own schema does not declare it, as a host's note on tool calls
 * does, and keeps what it was given.
 */
private class NoteField : ToolArguments {
    val seen = mutableListOf<Triple<ModelEvent.ToolCall, ModelEvent.ToolCall, String?>>()

    override fun forTool(call: ModelEvent.ToolCall, written: ModelEvent.ToolCall, schemaJson: String?): String {
        synchronized(seen) { seen += Triple(call, written, schemaJson) }
        if (schemaJson?.contains("\"note\"") == true) return call.argumentsJson
        val arguments = runCatching { Json.parseToJsonElement(call.argumentsJson) as? JsonObject }.getOrNull() ?: return call.argumentsJson
        return if ("note" in arguments) JsonObject(arguments - "note").toString() else call.argumentsJson
    }
}

/** set_fan answers at once; set_temperature never does, until its run is cancelled. */
private class HangingProvider : McpClientProvider {
    override val serverId: String = TEST_MCP_SERVER_ID
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()
    val hanging = CompletableDeferred<Unit>()
    val ran = mutableListOf<String>()

    override suspend fun listTools(): List<ToolDescriptor> =
        listOf(mcpTool("set_fan"), mcpTool("set_temperature")).map { it.copy(inputSchemaJson = """{"type":"object"}""") }

    override suspend fun callTool(toolName: String, argumentsJson: String, context: ToolCallContext): ToolCallResult {
        if (toolName == "set_temperature") {
            hanging.complete(Unit)
            awaitCancellation()
        }
        synchronized(ran) { ran += argumentsJson }
        return ToolCallResult(success = true, resultJson = """{"ok":true}""")
    }
}
