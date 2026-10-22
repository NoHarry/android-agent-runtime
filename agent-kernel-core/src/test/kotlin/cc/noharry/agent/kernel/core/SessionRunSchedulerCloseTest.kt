package cc.noharry.agent.kernel.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRunSchedulerCloseTest {
    @Test
    fun closeDrainsWaitersAndRejectsAdmission() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))

        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        b1.started.await()
        val cQueued = CompletableDeferred<SessionRunAdmission>()
        val cJob = launch { cQueued.complete(scheduler.admitOrdinary(SESSION_C, RUN_C1)) }
        val resumeAdmission = CompletableDeferred<SessionRunAdmission>()
        val resumeJob = launch { resumeAdmission.complete(scheduler.admitResume(SESSION_A, RUN_A1)) }
        awaitSnapshot(scheduler) { it.queuedCount == 1 && it.readySessionIds.contains(SESSION_A) }

        val closed = scheduler.close()
        val closedAgain = scheduler.close()
        assertEquals(listOf(a1.lease.await()), closed.waitingOwnerLeases)
        assertEquals(closed.waitingOwnerLeases, closedAgain.waitingOwnerLeases)
        assertEquals(setOf(RUN_A1, RUN_B1), closed.attachedJobs.map { it.runId }.toSet())
        assertEquals(closed.attachedJobs.map { it.runId }.toSet(), closedAgain.attachedJobs.map { it.runId }.toSet())
        assertEquals(listOf(SessionRunQueuedIdentity(SESSION_C, RUN_C1)), closed.queuedIdentities)
        assertTrue(closedAgain.queuedIdentities.isEmpty())
        assertTrue(closed.attachedJobs.all { it.job.isActive })
        assertTrue(closedAgain.attachedJobs.all { it.job.isActive })

        assertEquals(SessionRunAdmissionReason.CLOSED, (cQueued.await() as SessionRunAdmission.Rejected).reason)
        assertEquals(SessionRunAdmissionReason.CLOSED, (resumeAdmission.await() as SessionRunAdmission.Rejected).reason)
        cJob.join()
        resumeJob.join()

        val rejected = scheduler.admitOrdinary(SESSION_C, "after-close")
        assertEquals(SessionRunAdmissionReason.CLOSED, (rejected as SessionRunAdmission.Rejected).reason)

        val afterClose = scheduler.snapshot()
        assertEquals(1, afterClose.activeExecutionCount)
        assertEquals(0, afterClose.queuedCount)
        assertTrue(afterClose.readySessionIds.isEmpty())
        assertEquals(RUN_A1, afterClose.owners[SESSION_A])
        assertEquals(RUN_B1, afterClose.owners[SESSION_B])

        assertEquals(SessionRunTransition.Applied, scheduler.complete(b1.lease.await()))
        assertEquals(SessionRunTransition.Applied, scheduler.complete(b1.lease.await()))
        assertEquals(SessionRunTransition.Applied, scheduler.complete(a1.lease.await()))
        b1.hold.complete(Unit)
        a1.hold.complete(Unit)
        b1.job.join()
        a1.job.join()
        assertEquals(0, scheduler.snapshot().activeExecutionCount)
    }

    @Test
    fun closeDoesNotReturnEndedCollectorJob() = runSchedulerTest {
        val scheduler = scheduler()
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val ended = Job()
        ended.complete()
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(granted.lease, ended))

        val closed = scheduler.close()
        assertTrue(closed.attachedJobs.none { it.job === ended })
        assertTrue(closed.attachedJobs.none { it.runId == RUN_A1 })

        assertEquals(SessionRunTransition.Applied, scheduler.complete(granted.lease))
        assertEquals(0, scheduler.snapshot().activeExecutionCount)
    }

    @Test
    fun attachJobDoesNotClobberLiveJobWithInactiveJob() = runSchedulerTest {
        val scheduler = scheduler()
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val live = Job()
        val ended = Job()
        ended.complete()
        val lease = a1.lease.await()
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(lease, live))
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(lease, ended))

        val stale = SessionRunLease(SESSION_A, RUN_A1, id = lease.id + 99L)
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.attachJob(stale, Job()) as SessionRunTransition.Rejected).reason,
        )

        val closed = scheduler.close()
        assertEquals(listOf(RUN_A1), closed.attachedJobs.map { it.runId })
        assertSame(live, closed.attachedJobs.single().job)
        assertTrue(closed.attachedJobs.single().job.isActive)
        assertTrue(closed.attachedJobs.none { it.job === ended })
        assertTrue(scheduler.close().queuedIdentities.isEmpty())
        assertSame(live, scheduler.close().attachedJobs.single().job)

        live.complete()
        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        a1.hold.complete(Unit)
        a1.job.join()
    }

    @Test
    fun closeDrainsQueuedIdentitiesOnlyOnFirstClose() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))

        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        b1.started.await()
        val queuedAdmission = CompletableDeferred<SessionRunAdmission>()
        val queuedJob = launch { queuedAdmission.complete(scheduler.admitOrdinary(SESSION_A, RUN_A2)) }
        val extraAdmission = CompletableDeferred<SessionRunAdmission>()
        val extraJob = launch { extraAdmission.complete(scheduler.admitOrdinary(SESSION_C, RUN_C1)) }
        awaitSnapshot(scheduler) { it.queuedCount == 2 }

        val closed = scheduler.close()
        val closedAgain = scheduler.close()
        assertEquals(
            listOf(
                SessionRunQueuedIdentity(SESSION_A, RUN_A2),
                SessionRunQueuedIdentity(SESSION_C, RUN_C1),
            ),
            closed.queuedIdentities,
        )
        assertTrue(closedAgain.queuedIdentities.isEmpty())
        assertEquals(listOf(a1.lease.await()), closed.waitingOwnerLeases)
        assertEquals(closed.waitingOwnerLeases, closedAgain.waitingOwnerLeases)
        assertEquals(setOf(RUN_A1, RUN_B1), closed.attachedJobs.map { it.runId }.toSet())
        assertEquals(closed.attachedJobs.map { it.runId }.toSet(), closedAgain.attachedJobs.map { it.runId }.toSet())
        assertTrue(closed.attachedJobs.all { it.job.isActive })
        assertEquals(SessionRunAdmissionReason.CLOSED, (queuedAdmission.await() as SessionRunAdmission.Rejected).reason)
        assertEquals(SessionRunAdmissionReason.CLOSED, (extraAdmission.await() as SessionRunAdmission.Rejected).reason)
        queuedJob.join()
        extraJob.join()

        assertEquals(SessionRunTransition.Applied, scheduler.complete(b1.lease.await()))
        assertEquals(SessionRunTransition.Applied, scheduler.complete(a1.lease.await()))
        b1.hold.complete(Unit)
        a1.hold.complete(Unit)
        b1.job.join()
        a1.job.join()
        assertEquals(0, scheduler.snapshot().activeExecutionCount)
    }

    @Test
    fun attachJobReplacesEndedCollectorAndCloseKeepsOnlyLiveJob() = runSchedulerTest {
        val scheduler = scheduler()
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val ended = Job()
        ended.complete()
        val live = Job()
        val lease = a1.lease.await()
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(lease, ended))
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(lease, live))

        val closed = scheduler.close()
        assertEquals(listOf(RUN_A1), closed.attachedJobs.map { it.runId })
        assertSame(live, closed.attachedJobs.single().job)
        assertEquals(lease, closed.attachedJobs.single().lease)
        assertTrue(closed.attachedJobs.single().job.isActive)

        live.complete()
        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        a1.hold.complete(Unit)
        a1.job.join()
        val closedAfterEnd = scheduler.close()
        assertTrue(closedAfterEnd.attachedJobs.none { it.job === live })
        assertTrue(closedAfterEnd.attachedJobs.none { it.job === ended })
    }

    @Test
    fun waitingControlDropsEndedCollectorSoCloseDoesNotCancelIt() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val endedCollector = Job()
        endedCollector.complete()
        val lease = granted.lease
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(lease, endedCollector))
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(lease))

        val started = mutableListOf<String>()
        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        b1.started.await()

        val resumeJob = launch { scheduler.admitResume(SESSION_A, RUN_A1) }
        awaitSnapshot(scheduler) { it.readySessionIds == listOf(SESSION_A) }
        resumeJob.cancelAndJoin()

        val closed = scheduler.close()
        assertEquals(listOf(lease), closed.waitingOwnerLeases)
        assertTrue(closed.attachedJobs.none { it.job === endedCollector })
        assertTrue(closed.attachedJobs.none { it.runId == RUN_A1 })
        assertEquals(listOf(RUN_B1), closed.attachedJobs.map { it.runId })

        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        b1.releaseAndJoin()
        assertEquals(0, scheduler.snapshot().activeExecutionCount)
    }

    @Test
    fun grantDecidedBeforeCloseIsDeliveredAsClosed() = runSchedulerTest {
        val scheduler = scheduler()
        val closed = CompletableDeferred<SessionRunCloseSnapshot>()
        scheduler.beforeClaimGrantedForTest = {
            closed.complete(scheduler.close())
        }
        val admission = scheduler.admitOrdinary(SESSION_A, RUN_A1)
        assertEquals(
            SessionRunAdmissionReason.CLOSED,
            (admission as SessionRunAdmission.Rejected).reason,
        )
        val snapshot = closed.await()
        assertTrue(snapshot.attachedJobs.isEmpty())
        assertTrue(snapshot.waitingOwnerLeases.isEmpty())
        assertEquals(0, scheduler.snapshot().activeExecutionCount)
        assertTrue(scheduler.snapshot().owners.isEmpty())
        val job = Job()
        val staleLease = SessionRunLease(SESSION_A, RUN_A1, id = 1L)
        assertEquals(
            SessionRunTransitionReason.CLOSED,
            (scheduler.attachJob(staleLease, job) as SessionRunTransition.Rejected).reason,
        )
        job.complete()
    }

    @Test
    fun postCloseAttachWaitAndFinalizeRejectWhileCompleteDrains() = runSchedulerTest {
        val scheduler = scheduler()
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val closed = scheduler.close()
        assertTrue(closed.attachedJobs.isEmpty())
        assertTrue(closed.waitingOwnerLeases.isEmpty())
        val stray = Job()
        assertEquals(
            SessionRunTransitionReason.CLOSED,
            (scheduler.attachJob(granted.lease, stray) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(
            SessionRunTransitionReason.CLOSED,
            (scheduler.markWaitingControl(granted.lease) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(
            SessionRunTransitionReason.CLOSED,
            (scheduler.markFinalizing(granted.lease) as SessionRunTransition.Rejected).reason,
        )
        val closedAgain = scheduler.close()
        assertTrue(closedAgain.attachedJobs.none { it.job === stray })
        assertEquals(SessionRunTransition.Applied, scheduler.complete(granted.lease))
        assertEquals(SessionRunTransition.Applied, scheduler.complete(granted.lease))
        assertEquals(0, scheduler.snapshot().activeExecutionCount)
        stray.complete()
    }
}
