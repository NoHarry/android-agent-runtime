@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import java.nio.file.Files
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A runtime without a workspace still reaches the providers' tools, and offers no file tools it cannot run. */
class NoWorkspaceToolsTest {
    private val fileTools = setOf("list_dir", "read_file", "grep", "write_file", "edit_file")

    @Test
    fun withoutAWorkspaceSearchFindsTheToolAndTheModelCallsIt() = runBlocking<Unit> {
        val saved = mutableListOf<String>()
        val seen = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(searchThenCall(seen))
            .toolProvider(functionTools("notes") { tool("save", "Saves a note.") { saved += "milk"; "saved" } })
            .build()

        runtime.run(AgentRequest("r1", "s1", "Save a note.")).toList()
        runtime.close()

        assertEquals(listOf("milk"), saved)
        val offered = seen.first().tools.map { it.name }.toSet()
        assertTrue("search_tools" in offered)
        assertTrue(offered.intersect(fileTools).isEmpty())
        assertFalse("write_file" in seen.first().system.orEmpty())
        assertFalse("Read skill or context files" in seen.first().system.orEmpty())
    }

    @Test
    fun withoutAWorkspaceThePromptPointsAtNoFiles() = runBlocking<Unit> {
        val seen = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(searchThenCall(seen))
            .agentsMd("Be brief.")
            .skill(HostSkill(name = "pack", description = "Packs a bag.", skillMd = "Put things in the bag."))
            .build()

        runtime.run(AgentRequest("r1", "s1", "Hello.")).toList()
        runtime.close()

        val system = seen.first().system.orEmpty()
        assertFalse(system, "read_file" in system)
        assertFalse(system, "SKILL.md" in system)
    }

    @Test
    fun aWorkspaceKeepsTheFileTools() = runBlocking<Unit> {
        val root = Files.createTempDirectory("with-workspace").toFile()
        val seen = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create().modelClient(searchThenCall(seen)).workspaceRoot(root).build()

        runtime.run(AgentRequest("r1", "s1", "Hello.")).toList()
        runtime.close()
        root.deleteRecursively()

        assertTrue(seen.first().tools.map { it.name }.containsAll(fileTools))
        assertTrue("write_file" in seen.first().system.orEmpty())
    }

    /** Searches, calls the first tool the search added to the request, then finishes. */
    private fun searchThenCall(seen: MutableList<ModelRequest>): ModelClient = object : ModelClient {
        override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()

        override fun complete(request: ModelRequest) = flow {
            seen += request
            val last = request.messages.lastOrNull { it.role == "tool" }
            val backend = request.tools.firstOrNull { it.name.startsWith("mcp__") }
            emit(
                when {
                    last == null -> ModelEvent.ToolCall("c1", "search_tools", """{"query":"note"}""")
                    last.toolName == "search_tools" && backend != null -> ModelEvent.ToolCall("c2", backend.name, "{}")
                    else -> ModelEvent.ToolCall("c3", "finish", """{"response":"done"}""")
                },
            )
            emit(ModelEvent.Completed)
        }
    }
}
