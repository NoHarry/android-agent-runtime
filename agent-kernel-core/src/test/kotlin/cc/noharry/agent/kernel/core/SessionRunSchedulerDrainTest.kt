package cc.noharry.agent.kernel.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRunSchedulerDrainTest {
    @Test
    fun admitDuringDrainQueuesUntilFinish() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val completion = scheduler.completeAndQueryDrain(granted.lease)
        assertTrue(completion.sessionDrained)
        val drain = checkNotNull(completion.drainLease)
        assertFalse(scheduler.isSessionDrained(SESSION_A))
        assertEquals(0, scheduler.snapshot().activeExecutionCount)
        assertEquals(listOf(SESSION_A), scheduler.snapshot().drainingSessionIds)

        val admitted = async(Dispatchers.Default) {
            scheduler.admitOrdinary(SESSION_A, RUN_A2)
        }
        awaitSnapshot(scheduler) { it.queuedCount == 1 && !admitted.isCompleted }
        assertFalse(admitted.isCompleted)

        scheduler.finishSessionDrain(drain)
        val next = admitted.await() as SessionRunAdmission.Granted
        assertEquals(RUN_A2, next.runId)
        assertTrue(scheduler.isSessionDrained(SESSION_A).not())
        val finished = scheduler.completeAndQueryDrain(next.lease)
        scheduler.finishSessionDrain(checkNotNull(finished.drainLease))
        assertTrue(scheduler.isSessionDrained(SESSION_A))
        assertIdle(scheduler)
    }

    @Test
    fun staleFinishTokenDoesNotReleaseNewerDrain() = runSchedulerTest {
        val scheduler = scheduler()
        val first = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val firstDrain = checkNotNull(scheduler.completeAndQueryDrain(first.lease).drainLease)
        scheduler.finishSessionDrain(firstDrain)

        val second = scheduler.admitOrdinary(SESSION_A, RUN_A2) as SessionRunAdmission.Granted
        val secondDrain = checkNotNull(scheduler.completeAndQueryDrain(second.lease).drainLease)
        assertTrue(secondDrain != firstDrain)
        assertFalse(scheduler.isSessionDrained(SESSION_A))

        scheduler.finishSessionDrain(firstDrain)
        assertFalse(scheduler.isSessionDrained(SESSION_A))
        assertEquals(listOf(SESSION_A), scheduler.snapshot().drainingSessionIds)

        val parked = async(Dispatchers.Default) { scheduler.admitOrdinary(SESSION_A, RUN_A3) }
        awaitSnapshot(scheduler) { it.queuedCount == 1 && !parked.isCompleted }
        scheduler.finishSessionDrain(secondDrain)
        val third = parked.await() as SessionRunAdmission.Granted
        val thirdDrain = scheduler.completeAndQueryDrain(third.lease).drainLease
        scheduler.finishSessionDrain(checkNotNull(thirdDrain))
        assertIdle(scheduler)
    }

    @Test
    fun drainCloseThrowStillFinish() = runSchedulerTest {
        val scheduler = scheduler()
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val drain = checkNotNull(scheduler.completeAndQueryDrain(granted.lease).drainLease)
        val closeFailed = runCatching { error("drain close failed") }
        assertTrue(closeFailed.isFailure)
        scheduler.snapshot()
        scheduler.finishSessionDrain(drain)
        val next = scheduler.admitOrdinary(SESSION_A, RUN_A2) as SessionRunAdmission.Granted
        scheduler.finishSessionDrain(
            checkNotNull(scheduler.completeAndQueryDrain(next.lease).drainLease),
        )
        assertIdle(scheduler)
    }

    @Test
    fun closeAbsorbsDrainInProgress() = runSchedulerTest {
        val scheduler = scheduler()
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val drain = checkNotNull(scheduler.completeAndQueryDrain(granted.lease).drainLease)
        val parked = async(Dispatchers.Default) { scheduler.admitOrdinary(SESSION_A, RUN_A2) }
        awaitSnapshot(scheduler) { it.queuedCount == 1 }
        val snapshot = scheduler.close()
        assertEquals(listOf(SessionRunQueuedIdentity(SESSION_A, RUN_A2)), snapshot.queuedIdentities)
        assertEquals(
            SessionRunAdmissionReason.CLOSED,
            (parked.await() as SessionRunAdmission.Rejected).reason,
        )
        scheduler.finishSessionDrain(drain)
        assertTrue(scheduler.isSessionDrained(SESSION_A))
        val rejected = scheduler.admitOrdinary(SESSION_A, RUN_A3) as SessionRunAdmission.Rejected
        assertEquals(SessionRunAdmissionReason.CLOSED, rejected.reason)
    }

    @Test
    fun drainCloseCanReenterSchedulerWithoutHoldingLock() = runSchedulerTest {
        val scheduler = scheduler()
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val drain = checkNotNull(scheduler.completeAndQueryDrain(granted.lease).drainLease)
        val entered = CompletableDeferred<Unit>()
        val closed = launch(Dispatchers.Default) {
            entered.complete(Unit)
            scheduler.snapshot()
            scheduler.ownerQuery(SESSION_A)
        }
        entered.await()
        closed.join()
        assertNotNull(drain)
        scheduler.finishSessionDrain(drain)
        assertIdle(scheduler)
    }
}
