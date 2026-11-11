@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.usage
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.protocol.formatContextTokens
import cc.noharry.agent.appserver.trajectory.TRAJECTORY_INITIAL_VISIBLE_RUNS
import cc.noharry.agent.appserver.trajectory.TrajectorySessionState
import cc.noharry.agent.appserver.trajectory.toTrajectoryRecord
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionEventType
import org.junit.Assert.*
import org.junit.Test

class ContextUsageTest {
    @Test fun usesLatestInputInsteadOfCumulativeBillingOutputOrCacheTwice() {
        val usage = project(
            request(), input(200_000), finished(),
            request(), input(123_456, cached = 100_000), input(0, output = 3000), finished(),
        )
        assertEquals(123_456L, usage.inputTokens)
        assertEquals(876_544L, usage.remainingTokens)
        assertEquals("12.3%", usage.percentText)
        assertEquals(0.123456f, usage.fraction!!, 0.00001f)
    }

    @Test fun compactionClearsStaleInputUntilNextBusinessRequestCompletes() {
        val before = arrayOf(request(), input(200_000), finished(), request(), input(20_000), finished("compaction"))
        val compacted = project(*before)
        assertNull(compacted.inputTokens)
        assertTrue(compacted.awaitingAfterCompaction)
        val after = project(*before, request(), input(12_000), finished())
        assertEquals(12_000L, after.inputTokens)
        assertFalse(after.awaitingAfterCompaction)
    }

    @Test fun unknownUsageModelOrWindowIsNotRenderedAsZeroPercent() {
        assertNull(project(request(), finished()).percentText)
        assertNull(project(request(), input(0, output = 500), finished()).inputTokens)
        assertNull(contextUsage(snapshot(request(), input(500), finished()), "openai:other", WINDOW).inputTokens)
        val unknownWindow = contextUsage(snapshot(request(), input(500), finished()), MODEL, null)
        assertEquals(500L, unknownWindow.inputTokens)
        assertNull(unknownWindow.percentText)
        assertNull(unknownWindow.remainingTokens)
    }

    @Test fun aNewRunShowsItsSessionsLatestRequestUntilItsOwnCallReportsButNotAnotherSessions() {
        // A run carries its session's earlier runs, so the context in use is the latest request's, not empty.
        val snapshot = snapshot(request(), input(2000), finished())
        assertEquals(2000L, contextUsage(snapshot, MODEL, WINDOW, activeRunId = "new").inputTokens)
        val newest = snapshot.runGroups.single().copy(runId = "new", startedAtEpochMillis = 2L, records = emptyList())
        assertEquals(2000L, contextUsage(snapshot.copy(runGroups = listOf(newest) + snapshot.runGroups), MODEL, WINDOW).inputTokens)
        assertNull(contextUsage(snapshot.copy(sessionId = "other"), MODEL, WINDOW).inputTokens)
    }

    @Test fun newestRunRemainsVisibleWhenTheInitialTrajectoryPageIsFull() {
        val state = TrajectorySessionState("session")
        repeat(TRAJECTORY_INITIAL_VISIBLE_RUNS) { index ->
            state.putGroup(TrajectoryRunGroup("session", "old-$index", startedAtEpochMillis = index.toLong()))
        }
        events(request(), input(5215), finished()).forEach { event ->
            val record = event.toTrajectoryRecord()
            state.ensureAcceptedGroup(event, record)
        }
        val visible = state.toSnapshot(1L)
        assertEquals(TRAJECTORY_INITIAL_VISIBLE_RUNS, visible.runGroups.size)
        assertEquals("run", visible.runGroups.first().runId)
        assertEquals(5215L, contextUsage(visible, MODEL, WINDOW).inputTokens)
    }

    @Test fun incompleteLogAndMalformedUsageRemainUnknown() {
        val original = snapshot(request(), input(2000), finished())
        val group = original.runGroups.single()
        val gap = original.copy(runGroups = listOf(group.copy(health = group.health.copy(hasSequenceGap = true))))
        assertNull(contextUsage(gap, MODEL, WINDOW).inputTokens)
        assertNull(project(request(), input(-1), finished()).inputTokens)
        assertNull(project(request(), input(Long.MAX_VALUE), input(1), finished()).inputTokens)
    }

    @Test fun newCallWithoutUsageDoesNotRetainPreviousCountAfterCompletion() {
        assertNull(project(request(), input(2000), finished(), request(), finished()).inputTokens)
    }

    @Test fun overCapacityKeepsRealCountButClampsTheDrawingAndRemaining() {
        val usage = project(request(), input(1_200_000), finished())
        assertEquals("120.0%", usage.percentText)
        assertEquals(1f, usage.fraction)
        assertEquals(0L, usage.remainingTokens)
        assertEquals("1,200,000", formatContextTokens(usage.inputTokens!!))
    }

    private fun project(vararg payloads: SessionEventPayload) = contextUsage(snapshot(*payloads), MODEL, WINDOW)
    private fun request() = SessionEventPayload.ModelRequest(modelRef = MODEL)
    private fun input(value: Long, cached: Long = 0, output: Long = 0) =
        SessionEventPayload.Usage(inputTokens = value, cacheReadTokens = cached, outputTokens = output)
    private fun finished(source: String = "round") = SessionEventPayload.ModelCallFinished(source = source, modelRef = MODEL)
    private fun events(vararg payloads: SessionEventPayload): List<SessionEvent> = payloads.mapIndexed { index, payload ->
        val type = when (payload) {
            is SessionEventPayload.ModelRequest -> SessionEventType.MODEL_REQUEST
            is SessionEventPayload.Usage -> SessionEventType.USAGE
            else -> SessionEventType.MODEL_CALL_FINISHED
        }
        SessionEvent(2, "e$index", "session", "run", "run", index.toLong() + 1, index.toLong(), type, payload, emptyList())
    }

    private fun snapshot(vararg payloads: SessionEventPayload): TrajectorySnapshot {
        val records = events(*payloads).map { it.toTrajectoryRecord() }
        return TrajectorySnapshot("session", listOf(TrajectoryRunGroup("session", "run", startedAtEpochMillis = 1, records = records)))
    }
}

private const val MODEL = "openai:deepseek-flash"
private const val WINDOW = 1_000_000L
