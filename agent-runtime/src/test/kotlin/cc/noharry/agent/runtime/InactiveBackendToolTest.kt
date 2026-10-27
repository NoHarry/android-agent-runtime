@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** A real backend tool called before it was loaded: the model is told how to load it, not that it does not exist. */
class InactiveBackendToolTest {
    @Test fun aCatalogToolCalledBeforeLoadingGetsItsSchemaPath() = runBlocking {
        val seen = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature")))
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("inactive-backend-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(ModelEvent.ToolCall("call-1", qualifiedMcpTool("set_temperature"), "{}"), ModelEvent.Completed),
                        listOf(ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
                    ),
                    seenRequests = seen,
                ),
            )
            .build()

        runtime.run(AgentRequest("r1", "s1", "调空调")).toList()

        val result = seen[1].messages.last { it.role == "tool" }.content
        assertTrue(result, result.contains("INACTIVE_BACKEND_TOOL") && result.contains("schema.json"))
        assertTrue(backend.calledTools.isEmpty())
    }
}
