@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.NarrationCheck
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import java.nio.file.Files
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a host sets of the loop's content judgements reaches its runs. */
class LoopJudgementsBuilderTest {
    @Test
    fun theHostsNarrationCheckDecidesWhetherAnAnswerOnlyAnnounces() = runBlocking {
        val announcing = "I'll now look that up and provide the final answer."
        val byDefault = AgentRuntimeBuilder.create().modelClient(finishingWith(announcing)).build()
        assertNotEquals(RuntimeRunStatus.COMPLETED, byDefault.run(request("default")).result().status)
        byDefault.close()

        val accepting = AgentRuntimeBuilder.create().modelClient(finishingWith(announcing)).narrationCheck(NarrationCheck.NONE).build()
        val result = accepting.run(request("none")).result()
        assertEquals(RuntimeRunStatus.COMPLETED, result.status)
        assertEquals(announcing, result.response)
        accepting.close()
    }

    @Test
    fun withoutLessonsNothingIsKeptForThem() {
        val root = Files.createTempDirectory("lessons").toFile()
        AgentRuntimeBuilder.create().modelClient(finishingWith("done")).workspaceRoot(root).lessons(false).build().close()
        assertFalse(root.resolve("memory/lessons").exists())
        val learning = Files.createTempDirectory("lessons").toFile()
        AgentRuntimeBuilder.create().modelClient(finishingWith("done")).workspaceRoot(learning).build().close()
        assertTrue(learning.resolve("memory/lessons").exists())
        root.deleteRecursively()
        learning.deleteRecursively()
    }
}

private fun finishingWith(response: String): ModelClient = object : ModelClient {
    override val providerConcurrency: ProviderConcurrencyDeclaration = ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest) = flowOf(
        ModelEvent.ToolCall("finish-${request.messages.size}", "finish", """{"response":"$response"}"""),
        ModelEvent.Completed,
    )
}

private fun request(name: String) = AgentRequest(requestId = name, sessionId = "session-$name", input = "What is the weather?")

private suspend fun kotlinx.coroutines.flow.Flow<AgentEvent>.result() =
    toList().filterIsInstance<AgentEvent.ResultAvailable>().single().result
