package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeToolLoopDirectBackendTest {
    @Test
    fun arrayCapacityRejectsBeforeDispatchAndAllowsCorrectedBoundary() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val tool = "calculate_route"
        val backend = LoopMcpProvider(
            exposedTools = listOf(mcpTool(tool)),
            schemaJson = """{"type":"object","properties":{"midPois":{"type":"array","maxItems":3,
                "items":{"type":"object","required":["lon","lat"],"properties":{
                "lon":{"type":"number"},"lat":{"type":"number"}}}}},"required":["midPois"]}""",
        )
        val point = """{"lon":1,"lat":2}"""
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("array-capacity-").toFile()).toolProvider(backend)
            .modelClient(FakeModelClient(eventsByRound = listOf(
                modelRound(schemaRead(tool, "schema")),
                modelRound(ModelEvent.ToolCall("invalid", qualifiedMcpTool(tool), """{"midPois":[$point,$point,$point,$point]}""")),
                modelRound(ModelEvent.ToolCall("valid", qualifiedMcpTool(tool), """{"midPois":[$point,$point,$point]}""")),
                modelRound(finish("finish", "corrected")),
            ), seenRequests = requests)).build()
        runtime.run(agentRequest()).toList()
        assertEquals(listOf(tool), backend.calledTools)
        assertTrue(requests[2].messages.last().content.contains("maxItems=3"))
        assertTrue(requests[2].messages.last().content.contains("SCHEMA_VALIDATION_FAILED"))
    }

    @Test
    fun runFeedsUnsupportedToolResultBackToModel() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = "shell",
                                argumentsJson = "{}",
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """{"response":"recovered"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertTrue(seenRequests[1].messages.last().content.contains("UNSUPPORTED_NATIVE_TOOL"))
    }

    @Test
    fun runDispatchesDirectBackendToolAfterSchemaActivation() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(
            resultJson = """{"ok":true,"temperature":22}""",
            exposedTools = listOf(mcpTool("set_temperature")),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("direct-backend-").toFile())
            .toolProvider(backend)
            .modelClient(directBackendModel(seenRequests))
            .build()

        val events = runtime.run(agentRequest()).toList()
        val result = events.filterIsInstance<AgentEvent.ResultAvailable>().single().result

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals("backend complete", result.response)
        assertEquals(listOf("set_temperature"), backend.calledTools)
        assertTrue(events.any { it is AgentEvent.NativeToolCallFinished })
        assertFalse(seenRequests.first().tools.any { it.name == qualifiedMcpTool("set_temperature") })
        assertTrue(seenRequests[1].tools.any { it.name == qualifiedMcpTool("set_temperature") })
        assertTrue(seenRequests[2].messages.last().content.contains("temperature"))
    }

    @Test
    fun backendSchemaValidationRejectsMissingRequiredArgumentBeforeProviderCall() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(
            exposedTools = listOf(mcpTool("set_temperature")),
            schemaJson = TEMPERATURE_SCHEMA,
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("direct-backend-").toFile())
            .toolProvider(backend)
            .modelClient(schemaValidationBackendModel(seenRequests, "{}"))
            .build()

        val result = runtime.run(agentRequest()).toList()
            .filterIsInstance<AgentEvent.ResultAvailable>()
            .single()
            .result
        val validationResult = seenRequests[2].messages.last().content

        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(emptyList<String>(), backend.calledTools)
        assertTrue(validationResult.contains(RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode))
        assertTrue(validationResult.contains("degreesCelsius"))
    }

    @Test
    fun backendSchemaValidationRejectsWrongTypedArgumentBeforeProviderCall() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(
            exposedTools = listOf(mcpTool("set_temperature")),
            schemaJson = TEMPERATURE_SCHEMA,
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("direct-backend-").toFile())
            .toolProvider(backend)
            .modelClient(schemaValidationBackendModel(seenRequests, """{"degreesCelsius":"hot"}"""))
            .build()

        runtime.run(agentRequest()).toList()
        val validationResult = seenRequests[2].messages.last().content

        assertEquals(emptyList<String>(), backend.calledTools)
        assertTrue(validationResult.contains(RuntimeErrorCode.SCHEMA_VALIDATION_FAILED.wireCode))
        assertTrue(validationResult.contains("must be number"))
    }

    @Test
    fun qualifiedBackendToolNameAvoidsNativeCollisionAndCallsOriginalTool() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(
            exposedTools = listOf(mcpTool("read_file")),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("direct-backend-").toFile())
            .toolProvider(backend)
            .modelClient(collisionBackendModel(seenRequests))
            .build() as DefaultAgentRuntime

        runtime.run(agentRequest()).toList()

        val toolNames = seenRequests[1].tools.map { it.name }
        assertTrue(toolNames.contains("read_file"))
        assertTrue(toolNames.contains(qualifiedMcpTool("read_file")))
        assertEquals(listOf("read_file"), backend.calledTools)
        assertEquals(qualifiedMcpTool("read_file"), seenRequests[2].messages[3].toolName)
        assertEquals(qualifiedMcpTool("read_file"), seenRequests[2].messages[4].toolName)
    }

    @Test
    fun backendInspectFailureIsReturnedFromSchemaRead() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val backend = LoopMcpProvider(
            exposedTools = listOf(mcpTool("broken_tool")),
            schemaFailures = setOf("broken_tool"),
        )
        val runtime = AgentRuntimeBuilder.create()
            .workspaceRoot(Files.createTempDirectory("direct-backend-").toFile())
            .toolProvider(backend)
            .modelClient(
                FakeModelClient(
                    eventsByRound = listOf(
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-1",
                                name = "read_file",
                                argumentsJson = schemaPathArgs(qualifiedMcpTool("broken_tool")),
                            ),
                            ModelEvent.Completed,
                        ),
                        listOf(
                            ModelEvent.ToolCall(
                                id = "tool-2",
                                name = "finish",
                                argumentsJson = """{"response":"done"}""",
                            ),
                            ModelEvent.Completed,
                        ),
                    ),
                    seenRequests = seenRequests,
                ),
            )
            .build() as DefaultAgentRuntime

        runtime.run(agentRequest()).toList()

        assertFalse(seenRequests.first().tools.any { it.name == qualifiedMcpTool("broken_tool") })
        assertFalse(seenRequests[1].tools.any { it.name == qualifiedMcpTool("broken_tool") })
        assertTrue(seenRequests[1].messages.last().content.contains("NOT_FOUND"))
        assertTrue(backend.calledTools.isEmpty())
    }
}

private fun directBackendModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            modelRound(schemaRead("set_temperature", "tool-1")),
            modelRound(ModelEvent.ToolCall("tool-2", qualifiedMcpTool("set_temperature"), """{"degreesCelsius":22}""")),
            modelRound(finish("tool-3", "backend complete")),
        ),
        seenRequests = seenRequests,
    )

private fun schemaValidationBackendModel(
    seenRequests: MutableList<ModelRequest>,
    argumentsJson: String,
): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            modelRound(schemaRead("set_temperature", "tool-1")),
            modelRound(ModelEvent.ToolCall("tool-2", qualifiedMcpTool("set_temperature"), argumentsJson)),
            modelRound(finish("tool-3", "schema validation complete")),
        ),
        seenRequests = seenRequests,
    )

private fun collisionBackendModel(seenRequests: MutableList<ModelRequest>): FakeModelClient =
    FakeModelClient(
        eventsByRound = listOf(
            modelRound(schemaRead("read_file", "tool-1")),
            modelRound(ModelEvent.ToolCall("tool-2", qualifiedMcpTool("read_file"), """{"path":"/virtual"}""")),
            modelRound(finish("tool-3", "collision complete")),
        ),
        seenRequests = seenRequests,
    )

private fun modelRound(vararg events: ModelEvent): List<ModelEvent> =
    events.toList() + ModelEvent.Completed

private fun schemaRead(toolName: String, id: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall(id, "read_file", schemaPathArgs(qualifiedMcpTool(toolName)))

private fun schemaPathArgs(toolName: String): String =
    """{"path":"/mcp/tools/$toolName/schema.json"}"""

private fun finish(id: String, response: String): ModelEvent.ToolCall =
    ModelEvent.ToolCall(id, "finish", """{"response":"$response"}""")
