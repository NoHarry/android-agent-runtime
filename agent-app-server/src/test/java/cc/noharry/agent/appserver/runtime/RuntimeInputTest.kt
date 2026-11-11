@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.SteerResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RuntimeInputTest {
    @Test fun answerTargetsTheOriginalRunRegardlessOfUiSelectionAndRejectsReplacedRuns() = runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val original = RuntimeMultiSessionFixture("remote", "original")
            val selected = RuntimeMultiSessionFixture("local", "selected")
            harness.startHeld(original)
            harness.startHeld(selected)
            assertEquals("local", harness.coordinator.activeSessionId)
            assertEquals(SteerResult.ACCEPTED, harness.runner.postInput("remote", "original", "answer", "Suzhou", null))
            assertEquals(listOf(PostedSteering("original", "Suzhou")), original.postedSteering)
            assertTrue(selected.postedSteering.isEmpty())
            assertEquals(2, harness.runner.activeRunCount)
            assertEquals(SteerResult.NOT_RUNNING, harness.runner.postInput("local", "original", "wrong", "text", null))
            val successor = RuntimeMultiSessionFixture("remote", "successor")
            harness.startHeld(successor)
            assertEquals(SteerResult.NOT_RUNNING, harness.runner.postInput("remote", "original", "late", "text", null))
            assertTrue(successor.postedSteering.isEmpty())
            selected.release.complete(Unit)
            successor.release.complete(Unit)
            Unit
        }
    }
}
