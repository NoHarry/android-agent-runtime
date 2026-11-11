@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.spi.SessionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryLiveCostTest {
    @Test
    fun acceptingBurstDoesNotWalkExistingHistoryUntilSnapshotAndPreservesAllEvents() {
        val state = TrajectorySessionState("session")
        val history = CountingRecords(listOf(event(1).toTrajectoryRecord()))
        state.putGroup(TrajectoryRunGroup(sessionId = "session", runId = "run", records = history))
        val setupReads = history.reads
        for (index in 2..BURST_SIZE) {
            val event = event(index)
            val record = event.toTrajectoryRecord()
            state.ensureAcceptedGroup(event, record)
        }
        assertEquals("Accepted callback reread old records", 0, history.reads - setupReads)
        val first = state.toSnapshot(1).runGroups.single().records
        assertEquals((1..BURST_SIZE).map(Int::toLong), first.map { it.sequence })
        val extra = event(BURST_SIZE + 1)
        state.ensureAcceptedGroup(extra, extra.toTrajectoryRecord())
        assertEquals(BURST_SIZE + 1, state.toSnapshot(2).runGroups.single().records.size)
        assertEquals("Published snapshot must stay immutable", BURST_SIZE, first.size)
        assertTrue(state.acceptedRecords("run").size <= TRAJECTORY_MAX_LIVE_RECORDS_PER_RUN)
    }

    private fun event(index: Int) = sessionEvent(
        eventId = "event-$index", sessionId = "session", runId = "run", sequence = index.toLong(),
        type = SessionEventType.ASSISTANT_CHUNK, safeMetadata = mapOf("kind" to "text", "text" to "chunk"),
    )

    private class CountingRecords(private val records: List<TrajectoryRecord>) : AbstractList<TrajectoryRecord>() {
        var reads = 0
        override val size: Int get() = records.size
        override fun get(index: Int): TrajectoryRecord {
            reads++
            return records[index]
        }
    }
}

private const val BURST_SIZE = 1_100
