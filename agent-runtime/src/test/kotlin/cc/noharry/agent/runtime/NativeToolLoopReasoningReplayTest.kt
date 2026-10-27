package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
class NativeToolLoopReasoningReplayTest {
    @Test
    fun runPreservesThinkingOnAssistantToolCallHistory() = runBlocking {
        val workspace = Files.createTempDirectory("agent-runtime-reasoning-").toFile()
        File(workspace, "note.txt").writeText("workspace content")
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ThinkingDelta(REASONING),
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = "read_file",
                                argumentsJson = """{"path":"/note.txt"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """{"response":"read complete"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(agentRequest()).toList()

        val assistantToolCall = seenRequests[1].messages[1]
        assertEquals("assistant", assistantToolCall.role)
        assertEquals("tool-1", assistantToolCall.toolCallId)
        assertEquals(REASONING, assistantToolCall.content)
    }

    @Test
    fun runStoresThinkingTextOnlyOnFirstAssistantToolCallInBackendBatch() = runBlocking {
        val workspace = Files.createTempDirectory("agent-runtime-reasoning-backend-").toFile()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("first_backend"), mcpTool("second_backend")))
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            backendSchemaCall("schema-1", "first_backend"),
                            backendSchemaCall("schema-2", "second_backend"),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ThinkingDelta(REASONING),
                            backendToolCall("backend-1", "first_backend"),
                            backendToolCall("backend-2", "second_backend"),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-3",
                                name = "finish",
                                argumentsJson = """{"response":"backend complete"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(agentRequest()).toList()

        val assistantToolCalls = seenRequests[2].messages
            .filter { it.role == "assistant" && it.toolCallId in setOf("backend-1", "backend-2") }

        assertEquals(listOf("backend-1", "backend-2"), assistantToolCalls.map { it.toolCallId })
        assertEquals(REASONING, assistantToolCalls[0].content)
        assertEquals(DEDUPED_REASONING_MARKER, assistantToolCalls[1].content)
        assertEquals(1, assistantToolCalls.count { it.content == REASONING })
    }

    @Test
    fun runPreservesSignedThinkingOnEveryAssistantToolCallInBackendBatch() = runBlocking {
        val workspace = Files.createTempDirectory("agent-runtime-signed-reasoning-backend-").toFile()
        val backend = LoopMcpProvider(exposedTools = listOf(mcpTool("first_backend"), mcpTool("second_backend")))
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(workspace)
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            backendSchemaCall("schema-1", "first_backend"),
                            backendSchemaCall("schema-2", "second_backend"),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ThinkingDelta(REASONING, THINKING_SIGNATURE),
                            backendToolCall("backend-1", "first_backend"),
                            backendToolCall("backend-2", "second_backend"),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-3",
                                name = "finish",
                                argumentsJson = """{"response":"backend complete"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        runtime.run(agentRequest()).toList()

        val assistantToolCalls = seenRequests[2].messages
            .filter { it.role == "assistant" && it.toolCallId in setOf("backend-1", "backend-2") }

        assertEquals(listOf(REASONING, REASONING), assistantToolCalls.map { it.content })
        assertEquals(listOf(THINKING_SIGNATURE, THINKING_SIGNATURE), assistantToolCalls.map { it.thinkingSignature })
    }
}

private fun backendSchemaCall(id: String, toolName: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall(id, "read_file", """{"path":"/mcp/tools/${qualifiedMcpTool(toolName)}/schema.json"}""")

private fun backendToolCall(id: String, toolName: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall(id, qualifiedMcpTool(toolName), "{}")

private const val REASONING = "I should inspect the requested file."
private const val THINKING_SIGNATURE = "sig-reasoning"
private const val DEDUPED_REASONING_MARKER = "(same reasoning as previous tool call)"
