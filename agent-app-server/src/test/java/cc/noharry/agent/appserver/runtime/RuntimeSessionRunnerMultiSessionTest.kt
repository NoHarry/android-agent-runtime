@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.SessionSummary
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.spi.reliability.RuntimeRunStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeSessionRunnerMultiSessionTest {
    @Test
    fun fourSessionsOverlapAndKeepIndependentStatusAfterCompletion() =
        fourSessionsOverlapAndKeepIndependentStatusAfterCompletionCase()

    @Test
    fun sameSessionReplacementCancelsOnlyPredecessor() =
        sameSessionReplacementCancelsOnlyPredecessorCase()

    @Test
    fun maxBoundReturnsExplicitBusyAndKeepsExistingRuns() =
        maxBoundReturnsExplicitBusyAndKeepsExistingRunsCase()
}

internal fun fourSessionsOverlapAndKeepIndependentStatusAfterCompletionCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val sessions = (1..4).map { index -> RuntimeMultiSessionFixture("session-$index", "run-$index") }
            sessions.forEach { fixture -> harness.startHeld(fixture) }
            assertEquals(4, harness.runner.activeRunCount)
            assertTrue(harness.runner.activeRunSnapshots().all { it.isActive })
            assertEquals("session-4", harness.coordinator.activeSessionId)
            assertTrue(harness.runner.isRunning)

            harness.coordinator.adoptActiveSessionId("session-1")
            assertTrue(harness.runner.isRunning)

            sessions.forEach { it.release.complete(Unit) }
            assertTrue(awaitUntil { harness.runner.activeRunSnapshots().none { it.isActive } })

            val snapshots = harness.runner.activeRunSnapshots()
            assertEquals(4, snapshots.size)
            assertTrue(snapshots.none { it.isActive })
            snapshots.forEach { snapshot ->
                assertEquals(RuntimeRunStatus.COMPLETED.name, snapshot.lifecycleStatus)
                assertEquals(RuntimeCompletionOutcome.SUCCEEDED.name, snapshot.completionOutcome)
                assertEquals(SessionEventLogHealthStatus.COMPLETE.name, snapshot.logHealth)
            }
            assertFourSessionDecorations(harness, sessions)
        }
    }
}

internal fun assertFourSessionDecorations(
    harness: RuntimeMultiSessionHarness,
    sessions: List<RuntimeMultiSessionFixture>,
) {
    val decorated = harness.runner.decorateSessions(
        sessions.map { fixture ->
            SessionSummary(fixture.sessionId, fixture.sessionId, 1L, 1, "")
        },
    )
    assertEquals(4, decorated.size)
    decorated.forEach { summary ->
        assertEquals(
            listOf("COMPLETED", "SUCCEEDED", "COMPLETE").joinToString(" · "),
            summary.runStatusLine(),
        )
    }
    sessions.forEach { fixture ->
        assertEquals(1, fixture.runtimeCloses.get())
        assertEquals(1, fixture.memoryCloses.get())
    }
}

internal fun sameSessionReplacementCancelsOnlyPredecessorCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val first = RuntimeMultiSessionFixture("session-a", "run-a1")
            val second = RuntimeMultiSessionFixture("session-a", "run-a2")
            val other = RuntimeMultiSessionFixture("session-b", "run-b1")
            harness.startHeld(first)
            harness.startHeld(other)
            val admission = harness.startHeld(second)
            assertEquals(StartRunAdmission.REPLACED_SAME_SESSION, admission)
            assertTrue(awaitUntil { first.runtimeCloses.get() == 1 })
            assertEquals(1, first.runtimeCloses.get())
            assertEquals(1, first.memoryCloses.get())
            assertEquals(0, other.runtimeCloses.get())
            assertEquals(0, other.memoryCloses.get())
            assertEquals(0, second.runtimeCloses.get())
            assertEquals(2, harness.runner.activeRunCount)
            assertTrue(harness.runner.activeRunSnapshots().any { it.runId == "run-a2" && it.isActive })
            assertTrue(harness.runner.activeRunSnapshots().any { it.runId == "run-b1" && it.isActive })
            second.release.complete(Unit)
            other.release.complete(Unit)
        }
    }
}

internal fun maxBoundReturnsExplicitBusyAndKeepsExistingRunsCase() {
    runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val held = (1..DEBUG_MAX_ACTIVE_SESSION_RUNS).map { index ->
                RuntimeMultiSessionFixture("session-$index", "run-$index")
            }
            held.forEach { harness.startHeld(it) }
            assertEquals(DEBUG_MAX_ACTIVE_SESSION_RUNS, harness.runner.activeRunCount)
            val overflow = RuntimeMultiSessionFixture("session-overflow", "run-overflow")
            val admission = harness.startHeld(overflow)
            assertEquals(StartRunAdmission.BUSY_MAX_ACTIVE_SESSIONS, admission)
            assertEquals(1, overflow.runtimeCloses.get())
            assertEquals(1, overflow.memoryCloses.get())
            assertEquals(DEBUG_MAX_ACTIVE_SESSION_RUNS, harness.runner.activeRunCount)
            assertTrue(harness.statuses.any { it.startsWith("Busy:") })
            assertTrue(
                harness.transcript.snapshot().any { item ->
                    item.toString().contains("active session limit reached")
                },
            )
            held.forEach { assertEquals(0, it.runtimeCloses.get()) }
            held.forEach { it.release.complete(Unit) }
        }
    }
}
