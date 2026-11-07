@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PublicBuilderTest {
    @Test fun missingModelFailsAtBuildTime() {
        assertThrows(IllegalArgumentException::class.java) { AgentRuntime.builder().build() }
    }

    @Test fun groupedTurnBudgetStopsAnUnfinishedModel() = runBlocking {
        val model = object : ModelClient {
            override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
            override fun complete(request: ModelRequest) = flowOf(
                ModelEvent.ToolCall("clock", "update_todo", """{"items":[{"id":"one","text":"Keep working","status":"in_progress"}]}"""),
                ModelEvent.Completed,
            )
        }
        AgentRuntime.builder().model(model).limits(LoopLimits(maxModelRounds = 1)).build().use { runtime ->
            val events = runtime.startThread().runStreamed("Keep going").toList()
            val failed = events.last() as ThreadEvent.TurnFailed
            assertEquals("MODEL_LOOP_BUDGET_EXCEEDED", failed.error.code)
        }
    }
}
