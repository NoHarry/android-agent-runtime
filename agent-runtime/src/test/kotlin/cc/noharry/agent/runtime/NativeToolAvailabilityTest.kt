@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.tools.NATIVE_TOOLS
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeToolAvailabilityTest {
    @Test
    fun exclusionsAreCopiedAndRequiredOrUnknownNamesRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().disabledNativeTools(setOf("finish"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().disabledNativeTools(setOf("not_a_tool"))
        }
    }

    @Test
    fun allDisabledNativeToolsAreHiddenAndGuessedCallsCannotExecute() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        // A web_search the runtime does not offer is the provider's hosted search: answered as such, never run here.
        val disabled = NATIVE_TOOLS.map { it.name }.filter { it != "finish" && it != "web_search" }.toMutableSet()
        val engine = RecordingBridgeEngine()
        val calls = disabled.mapIndexed { i, name -> ModelEvent.ToolCall("blocked-$i", name, "{}") }
        val builder = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("disabled-tools-").toFile())
            .disabledNativeTools(disabled)
            .execEngine(engine)
            .modelClient(FakeModelClient(listOf(calls + ModelEvent.Completed, finishRound()), requests))
        disabled.clear()
        val events = builder.build().run(agentRequest()).toList()
        assertTrue(events.any { it is AgentEvent.ResultAvailable })
        assertEquals(0, engine.calls)
        assertTrue(requests.all { it.tools.map { tool -> tool.name } == listOf("finish") })
        val results = requests.last().messages.filter { it.role == "tool" }
        assertEquals(calls.size, results.size)
        assertTrue(results.all { it.content.contains("UNSUPPORTED_NATIVE_TOOL") && it.content.contains("\"ok\":false") })
    }

    @Test
    fun enabledExecCannotReadWriteOrDiscoverThroughDisabledTools() = runBlocking {
        val engine = RecordingBridgeEngine(listOf("read_file", "write_file", "search_tools"))
        val root = Files.createTempDirectory("disabled-nested-").toFile()
        val requests = mutableListOf<ModelRequest>()
        val events = AgentRuntimeBuilder.create().workspaceRoot(root)
            .disabledNativeTools(setOf("read_file", "write_file", "search_tools"))
            .execEngine(engine)
            .modelClient(FakeModelClient(listOf(
                listOf(ModelEvent.ToolCall("exec", "exec_js", """{"code":"test"}"""), ModelEvent.Completed),
                finishRound(),
            ), requests))
            .build().run(agentRequest()).toList()
        assertEquals(1, engine.calls)
        assertEquals(3, engine.results.size)
        assertTrue(engine.results.all { it.contains("UNSUPPORTED_NATIVE_TOOL") && it.contains("\"ok\":false") })
        assertFalse(root.resolve("blocked.txt").exists())
        assertTrue(requests.first().tools.any { it.name == "exec_js" })
        assertTrue(events.any { it is AgentEvent.ResultAvailable })
    }

    private fun finishRound(): List<ModelEvent> = listOf(
        ModelEvent.ToolCall("finish", "finish", """{"response":"Tool unavailable."}"""), ModelEvent.Completed,
    )
}

private class RecordingBridgeEngine(private val nestedTools: List<String> = emptyList()) : ExecEngine {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    var calls = 0
    val results = mutableListOf<String>()

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        calls += 1
        nestedTools.forEach { name ->
            results += bridge.callTool(name, """{"path":"/blocked.txt","content":"blocked","query":"media"}""")
        }
        return ExecResult(ok = true, output = results.joinToString())
    }
}
