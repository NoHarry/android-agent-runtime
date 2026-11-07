@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.spi.NarrationCheck
import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IndependentLimitsTest {
    @Test fun changingNarrationDoesNotResetAnExistingRoundBudget() = runBlocking {
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("todo", "update_todo", """{"items":[]}"""), ModelEvent.Completed),
            finishEvents("would need another round"),
        ))
        threadBuilder(model).limits(LoopLimits(maxModelRounds = 1))
            .settings(LoopSettings(narration = NarrationCheck { false })).build().use { runtime ->
                val events = runtime.startThread().runStreamed("continue").toList()
                assertEquals("MODEL_LOOP_BUDGET_EXCEEDED", (events.last() as ThreadEvent.TurnFailed).error.code)
                assertEquals(1, model.requests.size)
            }
    }

    @Test fun anExplicitSettingsBudgetStillReplacesThePreviousOne() = runBlocking {
        val model = ThreadScriptModel(listOf(
            listOf(ModelEvent.ToolCall("todo", "update_todo", """{"items":[]}"""), ModelEvent.Completed),
            finishEvents("done"),
        ))
        threadBuilder(model).limits(LoopLimits(maxModelRounds = 1))
            .settings(LoopSettings(limits = LoopLimits(maxModelRounds = 2))).build().use { runtime ->
                assertEquals("done", runtime.startThread().run("continue").finalResponse)
                assertEquals(2, model.requests.size)
            }
    }
}
