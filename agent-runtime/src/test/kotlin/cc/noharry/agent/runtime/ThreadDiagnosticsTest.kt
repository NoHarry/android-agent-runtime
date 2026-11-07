@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.TurnOptions
import cc.noharry.agent.spi.model.ModelEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ThreadDiagnosticsTest {
    @Test fun emptyCompletionObservationsRemainAvailableAfterRestart() = runBlocking {
        val root = threadRoot()
        val model = ThreadScriptModel(listOf(listOf(ModelEvent.Completed), finishEvents("done")))
        threadBuilder(model, root).build().use { runtime ->
            runtime.startThread().run("answer", TurnOptions(turnId = "empty-observation"))
            val observation = runtime.diagnostics.getRuntimeTraceEvents("empty-observation")
                .first { it.eventName == "EMPTY_COMPLETION_OBSERVED" }
            assertEquals("1", observation.details["modelRound"])
            assertEquals("0", observation.details["textChars"])
            assertTrue(observation.details.containsKey("hadStateAdvancingWork"))
        }
        threadBuilder(ThreadScriptModel(listOf(finishEvents("done"))), root).build().use { runtime ->
            val observation = runtime.diagnostics.getRuntimeTraceEvents("empty-observation")
                .first { it.eventName == "EMPTY_COMPLETION_OBSERVED" }
            assertEquals("0", observation.details["thinkingChars"])
            assertTrue(observation.details.containsKey("successfulBackendToolCount"))
        }
    }
}
