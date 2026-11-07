@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.spi.AgentEvent
import cc.noharry.agent.spi.AgentRequest
import cc.noharry.agent.spi.NarrationCheck
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import cc.noharry.agent.runtime.LoopSettings
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a host sets of the loop's settings reaches its runs. */
class LoopSettingsBuilderTest {
    @Test
    fun theHostsNarrationCheckDecidesWhetherAnAnswerOnlyAnnounces() = runBlocking {
        val announcing = "I'll now look that up and provide the final answer."
        val byDefault = AgentRuntimeBuilder.create().modelClient(finishingWith(announcing)).buildEngine()
        assertNotEquals(RuntimeRunStatus.COMPLETED, byDefault.run(request("default")).result().status)
        byDefault.close()

        val accepting = AgentRuntimeBuilder.create().modelClient(finishingWith(announcing)).loopSettings(LoopSettings(narration = NarrationCheck.NONE)).buildEngine()
        val result = accepting.run(request("none")).result()
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(announcing, result.response)
        accepting.close()
    }

    @Test
    fun theHostRewordsTheProtocolRules() = runBlocking<Unit> {
        val seen = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(finishingWith("done", seen))
            .protocolRules { rules -> rules.filterNot { "bing.com" in it } + "- Answer in one sentence." }
            .buildEngine()
        runtime.run(request("rules")).toList()
        runtime.close()
        val system = seen.first().system.orEmpty()
        assertFalse("bing.com" in system)
        assertTrue("- Answer in one sentence." in system)
        // With a workspace the prompt is assembled each round, and the host's rules hold there too.
        val root = Files.createTempDirectory("rules").toFile()
        val withWorkspace = mutableListOf<ModelRequest>()
        val workspaceRuntime = AgentRuntimeBuilder.create()
            .modelClient(finishingWith("done", withWorkspace))
            .workspaceRoot(root)
            .protocolRules { rules -> rules.filterNot { "bing.com" in it } + "- Answer in one sentence." }
            .buildEngine()
        workspaceRuntime.run(request("rules-workspace")).toList()
        workspaceRuntime.close()
        val assembled = withWorkspace.first().system.orEmpty()
        assertFalse("bing.com" in assembled)
        assertTrue("- Answer in one sentence." in assembled)
        root.deleteRecursively()
    }

    @Test
    fun aScenesRulesReachTheModelOnlyInThatScene() = runBlocking<Unit> {
        val root = Files.createTempDirectory("scenes").toFile()
        for (workspace in listOf(null, root)) {
            val seen = mutableListOf<ModelRequest>()
            val runtime = AgentRuntimeBuilder.create()
                .modelClient(finishingWith("done", seen))
                .apply { workspace?.let(::workspaceRoot) }
                .sceneAgentsMd("debug-console", "Answer with the raw log line.")
                .sceneAgentsMd("cabin", "Rules that a blank call takes back.")
                .sceneAgentsMd("cabin", " ")
                .buildEngine()
            runtime.run(request("in-scene").copy(scene = "debug-console")).toList()
            runtime.run(request("elsewhere").copy(scene = "cabin")).toList()
            runtime.run(request("no-scene")).toList()
            runtime.close()
            val (inScene, elsewhere, noScene) = seen.map { it.system.orEmpty() }
            assertTrue("Current scene: debug-console\nScene rules:\nAnswer with the raw log line." in inScene)
            assertFalse("raw log line" in elsewhere || "takes back" in elsewhere)
            assertFalse("raw log line" in noScene)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create().sceneAgentsMd("big", "x".repeat(17 * 1024))
        }
        assertThrows(IllegalArgumentException::class.java) { AgentRuntimeBuilder.create().sceneAgentsMd(" ", "Rules.") }
        root.deleteRecursively()
    }

    @Test
    fun limitsMustBePositive() {
        SessionHistory(maxSessions = 200, maxRunsInFlight = 500)
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { SessionHistory(maxSessions = 0) }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { LoopSettings(contextTimeoutMillis = 0) }
    }

    @Test
    fun withoutLessonsNothingIsKeptForThem() {
        val root = Files.createTempDirectory("lessons").toFile()
        AgentRuntimeBuilder.create().modelClient(finishingWith("done")).workspaceRoot(root).lessons(false).buildEngine().close()
        assertFalse(root.resolve("memory/lessons").exists())
        val learning = Files.createTempDirectory("lessons").toFile()
        AgentRuntimeBuilder.create().modelClient(finishingWith("done")).workspaceRoot(learning).buildEngine().close()
        assertTrue(learning.resolve("memory/lessons").exists())
        root.deleteRecursively()
        learning.deleteRecursively()
    }
}

private fun finishingWith(response: String, seen: MutableList<ModelRequest> = mutableListOf()): ModelClient = object : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest) = flowOf(
        ModelEvent.ToolCall("finish-${request.messages.size}", "finish", """{"response":"$response"}"""),
        ModelEvent.Completed,
    ).also { seen += request }
}

private fun request(name: String) = AgentRequest(requestId = name, sessionId = "session-$name", input = "What is the weather?")

private suspend fun kotlinx.coroutines.flow.Flow<AgentEvent>.result() =
    toList().filterIsInstance<AgentEvent.ResultAvailable>().single().result
