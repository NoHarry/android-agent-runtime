@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.HookContext
import cc.noharry.agent.kernel.api.HookDecision
import cc.noharry.agent.kernel.api.RuntimeHook
import cc.noharry.agent.kernel.api.ToolUse
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DeferredBackendToolsTest {
    private val schemas = mapOf("set_temperature" to """{"type":"object","properties":{"value":{"type":"integer"}}}""")

    @Test
    fun backendToolsAreCalledThroughCallToolAndTheToolListNeverChanges() = runBlocking {
        val model = GoldenScriptedModel(
            listOf(
                round(tool("search_tools", """{"query":"set_temperature"}""")),
                round(tool("call_tool", """{"name":"${qualifiedMcpTool("set_temperature")}","arguments":{"value":22}}""")),
                round(finish("done")),
            ),
        )
        val backend = GoldenMcpProvider(schemas)
        val hookedTools = mutableListOf<String>()
        val runtime = runtime(model, backend).hook(recorder(hookedTools)).build()

        runtime.run(goldenRequest()).toList()

        assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
        // Hooks and the backend see the real tool and its own arguments.
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertEquals(listOf("""{"value":22}"""), backend.calledArguments)
        assertTrue(qualifiedMcpTool("set_temperature") in hookedTools)
        // The tool list is the same in every request: native tools and call_tool, no backend tool.
        val toolLists = model.seenRequests.map { request -> request.tools.map { it.name } }
        assertTrue(toolLists.all { it == toolLists.first() })
        assertTrue("call_tool" in toolLists.first())
        assertTrue(toolLists.first().none { it.startsWith("mcp__") })
        val found = model.seenRequests[1].messages.last { it.role == "tool" }.content
        assertTrue(found, found.contains("\"via\":\"call_tool\"") && found.contains("\"schema\":") && found.contains("\"value\":"))
        // The next request repeats the call as the model sent it, paired with the real tool's result.
        val call = model.seenRequests[2].messages.single { it.role == "assistant" && it.toolCallId == "call_tool-call" }
        assertEquals("call_tool", call.toolName)
        assertTrue(call.toolArgumentsJson.orEmpty().contains("\"arguments\":{\"value\":22}"))
        val result = model.seenRequests[2].messages.last { it.role == "tool" }
        assertEquals("call_tool-call", result.toolCallId)
        assertTrue(result.content, result.content.contains("\"tool\":\"set_temperature\""))
    }

    @Test
    fun aToolNamedWithoutItsServerPrefixStillRuns() = runBlocking {
        val model = GoldenScriptedModel(
            listOf(
                round(tool("search_tools", """{"query":"set_temperature"}""")),
                round(tool("call_tool", """{"name":"set_temperature","arguments":{"value":23}}""")),
                round(finish("done")),
            ),
        )
        val backend = GoldenMcpProvider(schemas)

        runtime(model, backend).build().run(goldenRequest()).toList()

        assertEquals(listOf("set_temperature"), backend.calledTools)
    }

    @Test
    fun aCallToolWithoutANameGetsAnErrorTheModelCanFix() = runBlocking {
        val model = GoldenScriptedModel(
            listOf(
                round(tool("call_tool", """{"arguments":{"value":22}}""")),
                round(finish("done")),
            ),
        )
        val backend = GoldenMcpProvider(schemas)

        runtime(model, backend).build().run(goldenRequest()).toList()

        assertTrue(backend.calledTools.isEmpty())
        val error = model.seenRequests[1].messages.last { it.role == "tool" }.content
        assertTrue(error, error.contains("INVALID_CALL_TOOL"))
    }

    @Test
    fun nativeModeDeclaresFoundToolsDeferredAndLoadsThemRightAfterTheSearch() = runBlocking {
        val model = GoldenScriptedModel(
            listOf(
                round(tool("search_tools", """{"query":"set_temperature"}""")),
                round(tool(qualifiedMcpTool("set_temperature"), """{"value":22}""")),
                round(finish("done")),
            ),
        )
        val backend = GoldenMcpProvider(schemas)
        AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("native-deferred-").toFile())
            .toolProvider(backend)
            .modelClient(model)
            .nativeDeferredTools()
            .runContextAsSystem()
            .runContext("Current debug session context.")
            .build()
            .run(goldenRequest())
            .toList()

        // The model calls the tool natively once it is loaded; no call_tool in the list.
        assertEquals(listOf("set_temperature"), backend.calledTools)
        val first = model.seenRequests[0]
        assertEquals(listOf("user", "system"), first.messages.map { it.role })
        assertTrue(first.tools.none { it.name == "call_tool" })
        val second = model.seenRequests[1]
        assertTrue(second.tools.single { it.name == qualifiedMcpTool("set_temperature") }.deferred)
        val load = second.messages.last()
        assertEquals("system", load.role)
        assertTrue(qualifiedMcpTool("set_temperature") in load.toolAdditions)
        val found = second.messages.last { it.role == "tool" }.content
        assertTrue(found, found.contains("\"callable\":true") && !found.contains("call_tool"))
    }

    @Test
    fun aToolLoadedInAnEarlierRunStaysDeclaredWhenThisRunDidNotActivateIt() = runBlocking {
        val model = GoldenScriptedModel(
            listOf(
                round(tool("search_tools", """{"query":"set_temperature"}""")),
                round(finish("done")),
                round(finish("again")),
            ),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("native-deferred-").toFile())
            .toolProvider(GoldenMcpProvider(schemas))
            .modelClient(model)
            .nativeDeferredTools()
            .sessionHistory(SessionHistory())
            .build()
        runtime.run(goldenRequest("request-1")).toList()
        runtime.run(goldenRequest("request-2")).toList()

        // No session tool carry-over: run 2 activated nothing, yet run 1's load is in the history it sends.
        val second = model.seenRequests.last()
        assertTrue(second.messages.any { qualifiedMcpTool("set_temperature") in it.toolAdditions })
        assertTrue(second.tools.single { it.name == qualifiedMcpTool("set_temperature") }.deferred)
    }

    private fun runtime(model: GoldenScriptedModel, backend: GoldenMcpProvider): AgentRuntimeBuilder =
        AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("deferred-tools-").toFile())
            .toolProvider(backend)
            .modelClient(model)
            .deferredBackendTools()

    private fun recorder(names: MutableList<String>): RuntimeHook = object : RuntimeHook {
        override suspend fun onPreToolUse(call: ToolUse, context: HookContext): HookDecision {
            names += call.toolName
            return HookDecision.Allow()
        }
    }
}
