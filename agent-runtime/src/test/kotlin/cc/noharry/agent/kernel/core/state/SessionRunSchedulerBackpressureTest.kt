package cc.noharry.agent.kernel.core.state
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SessionRunSchedulerBackpressureTest {
    @Test
    fun perSessionAndGlobalBackpressure() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1, maxQueuedPerSession = 1, maxQueuedGlobal = 2)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val a2 = holdOrdinary(scheduler, SESSION_A, RUN_A2, started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        val sessionFull = scheduler.admitOrdinary(SESSION_A, RUN_A3)
        assertEquals(
            SessionRunAdmissionReason.SESSION_QUEUE_FULL,
            (sessionFull as SessionRunAdmission.Rejected).reason,
        )

        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        awaitSnapshot(scheduler) { it.queuedCount == 2 }

        val runtimeBusy = scheduler.admitOrdinary(SESSION_C, RUN_C1)
        assertEquals(
            SessionRunAdmissionReason.RUNTIME_BUSY,
            (runtimeBusy as SessionRunAdmission.Rejected).reason,
        )

        a1.releaseAndJoin()
        b1.started.await()
        assertFalse(a2.started.isCompleted)
        b1.releaseAndJoin()
        a2.started.await()
        a2.releaseAndJoin()
        assertIdle(scheduler)
    }
}
