@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionRunnerMultiSessionRoutingTest {
    @Test
    fun steeringAndConfirmationRouteToSelectedExactRun() =
        steeringAndConfirmationRouteToSelectedExactRunCase()

    @Test
    fun staleEventFromOtherSessionDoesNotMutateSelectedUi() =
        staleEventFromOtherSessionDoesNotMutateSelectedUiCase()

    @Test
    fun backgroundTokenUsageProjectsOnSelect() = backgroundTokenUsageProjectsOnSelectCase()

    @Test
    fun isRunningDescribesSelectedSessionOnly() = isRunningDescribesSelectedSessionOnlyCase()

    @Test
    fun oneThousandRequestIdsAreUnique() = oneThousandRequestIdsAreUniqueCase()
}

internal fun steeringAndConfirmationRouteToSelectedExactRunCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val first = RuntimeMultiSessionFixture("session-a", "run-a")
            val second = RuntimeMultiSessionFixture("session-b", "run-b", waitForConfirmation = true)
            harness.startHeld(first)
            harness.startHeld(second)
            harness.coordinator.adoptActiveSessionId("session-a")
            harness.runner.postSteeringText("keep going A")
            assertTrue(first.steeringPosted.await(2, TimeUnit.SECONDS))
            assertEquals(0, second.postedSteering.size)
            assertEquals("run-a", first.postedSteering.single().runId)

            harness.coordinator.adoptActiveSessionId("session-b")
            harness.runner.resolveConfirmation("pending-confirm", approve = true)
            assertTrue(second.confirmationPosted.await(2, TimeUnit.SECONDS))
            assertEquals(0, first.postedConfirmations.size)
            assertEquals("pending-confirm", second.postedConfirmations.single().pendingId)
            first.release.complete(Unit)
            second.release.complete(Unit)
        }
    }
}

internal fun staleEventFromOtherSessionDoesNotMutateSelectedUiCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val background = RuntimeMultiSessionFixture("session-a", "run-a")
            val selected = RuntimeMultiSessionFixture("session-b", "run-b")
            harness.startHeld(background)
            harness.startHeld(selected)
            harness.statuses.clear()
            val selectedBefore = harness.runner.lastRunSummary
            background.release.complete(Unit)
            assertTrue(awaitUntil { background.runtimeCloses.get() == 1 })
            assertEquals(selectedBefore, harness.runner.lastRunSummary)
            assertFalse(harness.statuses.any { it.startsWith("Worked for") || it.startsWith("Failed after") })
            assertEquals("session-b", harness.coordinator.activeSessionId)
            assertTrue(harness.runner.isRunning)
            selected.release.complete(Unit)
        }
    }
}

internal fun backgroundTokenUsageProjectsOnSelectCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val background = RuntimeMultiSessionFixture("session-a", "run-a", emitToken = true)
            val selected = RuntimeMultiSessionFixture("session-b", "run-b")
            harness.startHeld(background)
            assertTrue(awaitUntil { harness.runner.tokenUsageFor("session-a").totalTokens > 0 })
            harness.coordinator.adoptActiveSessionId("session-b")
            harness.startHeld(selected)
            harness.runner.projectSelectedSessionState()
            assertEquals(0, harness.runner.tokenUsage.totalTokens)
            assertTrue(harness.runner.tokenUsageFor("session-a").totalTokens > 0)
            harness.coordinator.adoptActiveSessionId("session-a")
            harness.runner.projectSelectedSessionState()
            assertEquals(harness.runner.tokenUsageFor("session-a"), harness.runner.tokenUsage)
            // One usage event (11 in, 7 out) is one request, counted once.
            assertEquals(1, harness.runner.tokenUsage.requestCount)
            assertEquals(18, harness.runner.tokenUsage.totalTokens)
            background.release.complete(Unit)
            selected.release.complete(Unit)
        }
    }
}

internal fun isRunningDescribesSelectedSessionOnlyCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val first = RuntimeMultiSessionFixture("session-a", "run-a")
            val second = RuntimeMultiSessionFixture("session-b", "run-b")
            harness.startHeld(first)
            harness.coordinator.adoptActiveSessionId("session-idle")
            assertFalse(harness.runner.isRunning)
            harness.startHeld(second)
            assertTrue(harness.runner.isRunning)
            harness.coordinator.adoptActiveSessionId("session-a")
            assertTrue(harness.runner.isRunning)
            first.release.complete(Unit)
            second.release.complete(Unit)
        }
    }
}

internal fun oneThousandRequestIdsAreUniqueCase() {
    val ids = (1..1000).map { RequestIds.next("debug") }.toSet()
    assertEquals(1000, ids.size)
    assertTrue(ids.all { it.startsWith("debug-") })
    assertTrue(ids.all { it.count { ch -> ch == '-' } >= 3 })
}
