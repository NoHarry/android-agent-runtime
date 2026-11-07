package cc.noharry.agent.minimal

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.TurnStatus
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MinimalAgentTest {
    @Test fun smallestHostRunsACompleteTurn() = runBlocking {
        val model = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest) = flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            )
        }
        MinimalAgent.build(model).use { runtime ->
            val events = runtime.startThread().runStreamed("finish").toList()
            assertTrue(events.first() is ThreadEvent.TurnStarted)
            val result = (events.last() as ThreadEvent.TurnCompleted).result
            assertEquals(TurnStatus.COMPLETED, result.status)
            assertEquals("done", result.finalResponse)
            assertTrue(result.items.isNotEmpty())
        }
    }
}
