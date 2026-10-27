package cc.noharry.agent.kernel.core.state
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRunSchedulerRaceTest {
    @Test
    fun closeRacingParkedGrantDeliveryFailsClosedAndBlocksAttach() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        scheduler.beforeClaimGrantedForTest = { scheduler.close() }
        val admission = CompletableDeferred<SessionRunAdmission>()
        val waiter = launch(Dispatchers.Default) {
            admission.complete(scheduler.admitOrdinary(SESSION_B, RUN_B1))
        }
        awaitSnapshot(scheduler) { it.queuedCount == 1 }
        a1.releaseAndJoin()
        assertEquals(
            SessionRunAdmissionReason.CLOSED,
            (admission.await() as SessionRunAdmission.Rejected).reason,
        )
        waiter.join()
        val closed = scheduler.close()
        assertTrue(closed.attachedJobs.none { it.runId == RUN_B1 })
        assertTrue(closed.waitingOwnerLeases.none { it.runId == RUN_B1 })
    }

    @Test
    fun closeRevertingAnUnclaimedGrantWithAnotherLaneQueuedBehindItRejectsBoth() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        scheduler.beforeClaimGrantedForTest = { scheduler.close() }
        val first = CompletableDeferred<SessionRunAdmission>()
        val second = CompletableDeferred<SessionRunAdmission>()
        val waiters = listOf(
            launch(Dispatchers.Default) { first.complete(scheduler.admitOrdinary(SESSION_B, RUN_B1)) },
            launch(Dispatchers.Default) {
                awaitSnapshot(scheduler) { it.queuedCount == 1 }
                second.complete(scheduler.admitOrdinary(SESSION_C, RUN_C1))
            },
        )
        awaitSnapshot(scheduler) { it.queuedCount == 2 }
        // B is granted when A ends; closing before B claims it reverts the grant and drops B's lane while C's follows.
        a1.releaseAndJoin()

        assertEquals(SessionRunAdmissionReason.CLOSED, (first.await() as SessionRunAdmission.Rejected).reason)
        assertEquals(SessionRunAdmissionReason.CLOSED, (second.await() as SessionRunAdmission.Rejected).reason)
        waiters.forEach { it.join() }
    }

    @Test
    fun closeRacingAttachKeepsJobInsideSnapshotOrRejects() = runSchedulerTest {
        val scheduler = scheduler()
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val job = Job()
        val attached = CompletableDeferred<SessionRunTransition>()
        val closed = CompletableDeferred<SessionRunCloseSnapshot>()
        val closer = launch(Dispatchers.Default) {
            closed.complete(scheduler.close())
        }
        val attacher = launch(Dispatchers.Default) {
            attached.complete(scheduler.attachJob(granted.lease, job))
        }
        val closeSnap = withTimeout(SCHEDULER_TEST_TIMEOUT_MS) { closed.await() }
        val attachResult = withTimeout(SCHEDULER_TEST_TIMEOUT_MS) { attached.await() }
        closer.join()
        attacher.join()
        when (attachResult) {
            SessionRunTransition.Applied -> {
                assertTrue(closeSnap.attachedJobs.any { it.job === job && it.lease == granted.lease })
            }
            is SessionRunTransition.Rejected -> {
                assertEquals(SessionRunTransitionReason.CLOSED, attachResult.reason)
                assertTrue(closeSnap.attachedJobs.none { it.job === job })
            }
        }
        assertEquals(SessionRunTransition.Applied, scheduler.complete(granted.lease))
        job.complete()
    }

    @Test
    fun cancellationWhileSchedulerMutexContendedDoesNotLeak() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val queued = launch(Dispatchers.Default) { scheduler.admitOrdinary(SESSION_B, RUN_B1) }
        awaitSnapshot(scheduler) { it.queuedCount == 1 && it.readySessionIds == listOf(SESSION_B) }

        val holding = CompletableDeferred<Unit>()
        val releaseHold = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            scheduler.holdMutexForTest {
                holding.complete(Unit)
                releaseHold.await()
            }
        }
        holding.await()

        val cleanupEntering = CompletableDeferred<Unit>()
        scheduler.beforeNonCancellableMutexForTest = { cleanupEntering.complete(Unit) }
        val cancelled = launch(Dispatchers.Default) { queued.cancelAndJoin() }
        withTimeout(SCHEDULER_TEST_TIMEOUT_MS) { cleanupEntering.await() }
        assertFalse(queued.isCompleted)
        releaseHold.complete(Unit)
        withTimeout(SCHEDULER_TEST_TIMEOUT_MS) { cancelled.join() }
        holder.join()
        scheduler.beforeNonCancellableMutexForTest = null

        val after = scheduler.snapshot()
        assertEquals(0, after.queuedCount)
        assertTrue(after.readySessionIds.isEmpty())
        assertEquals(1, after.activeExecutionCount)
        assertEquals(mapOf(SESSION_A to RUN_A1), after.owners)

        a1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun queueWaiterCancellationUnderContentionRemovesRequest() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val queuedAdmission = CompletableDeferred<Result<SessionRunAdmission>>()
        val queued = launch(Dispatchers.Default) {
            queuedAdmission.complete(
                runCatching { scheduler.admitOrdinary(SESSION_B, RUN_B1) },
            )
        }
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        val holding = CompletableDeferred<Unit>()
        val releaseHold = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            scheduler.holdMutexForTest {
                holding.complete(Unit)
                releaseHold.await()
            }
        }
        holding.await()

        val cleanupEntering = CompletableDeferred<Unit>()
        scheduler.beforeNonCancellableMutexForTest = { cleanupEntering.complete(Unit) }
        queued.cancel()
        withTimeout(SCHEDULER_TEST_TIMEOUT_MS) { cleanupEntering.await() }
        assertFalse(queuedAdmission.isCompleted)
        releaseHold.complete(Unit)
        withTimeout(SCHEDULER_TEST_TIMEOUT_MS) { queued.join() }
        holder.join()
        scheduler.beforeNonCancellableMutexForTest = null

        assertTrue(queuedAdmission.await().isFailure)
        val after = scheduler.snapshot()
        assertEquals(0, after.queuedCount)
        assertTrue(after.readySessionIds.isEmpty())
        assertEquals(mapOf(SESSION_A to RUN_A1), after.owners)

        val c1 = holdOrdinary(scheduler, SESSION_C, RUN_C1, started)
        a1.releaseAndJoin()
        c1.started.await()
        c1.releaseAndJoin()
        assertEquals(listOf(RUN_A1, RUN_C1), started.toList())
        assertIdle(scheduler)
    }

    @Test
    fun resumeGrantCancellationRestoresPreviousLease() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val oldLease = a1.lease.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(oldLease))

        val enteredClaim = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        scheduler.beforeClaimGrantedForTest = {
            enteredClaim.complete(Unit)
            releaseClaim.await()
        }
        val resumeJob = launch(Dispatchers.Default) {
            scheduler.admitResume(SESSION_A, RUN_A1)
        }
        enteredClaim.await()
        resumeJob.cancel()
        releaseClaim.complete(Unit)
        resumeJob.join()
        scheduler.beforeClaimGrantedForTest = null

        val snap = scheduler.snapshot()
        assertEquals(RUN_A1, snap.owners[SESSION_A])
        assertEquals(SessionLaneOwnerPhase.WAITING_CONTROL, snap.ownerPhases[SESSION_A])
        assertEquals(0, snap.activeExecutionCount)
        assertEquals(SessionRunTransition.Applied, scheduler.complete(oldLease))
        a1.hold.complete(Unit)
        a1.job.join()
        assertIdle(scheduler)
    }

    @Test
    fun staleLiveJobCannotOverwriteCurrentLeaseJob() = runSchedulerTest {
        val scheduler = scheduler()
        val first = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val staleJob = Job()
        val liveJob = Job()
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(first.lease, staleJob))
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(first.lease))

        val resume = scheduler.admitResume(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        assertTrue(resume.lease.id > first.lease.id)
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.attachJob(first.lease, staleJob) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(resume.lease, liveJob))

        val closed = scheduler.close()
        assertEquals(listOf(resume.lease), closed.attachedJobs.map { it.lease })
        assertSame(liveJob, closed.attachedJobs.single().job)
        assertTrue(closed.attachedJobs.none { it.job === staleJob })

        liveJob.complete()
        staleJob.complete()
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.complete(first.lease) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(SessionRunTransition.Applied, scheduler.complete(resume.lease))
        assertEquals(SessionRunTransition.Applied, scheduler.complete(first.lease))
    }

    @Test
    fun ordinaryGrantCancellationBeforeAttachReleasesOwner() = runSchedulerTest {
        val scheduler = scheduler()
        val enteredClaim = CompletableDeferred<Unit>()
        val releaseClaim = CompletableDeferred<Unit>()
        scheduler.beforeClaimGrantedForTest = {
            enteredClaim.complete(Unit)
            releaseClaim.await()
        }
        val grantJob = launch(Dispatchers.Default) {
            scheduler.admitOrdinary(SESSION_A, RUN_A1)
        }
        enteredClaim.await()
        grantJob.cancel()
        releaseClaim.complete(Unit)
        grantJob.join()
        scheduler.beforeClaimGrantedForTest = null
        assertIdle(scheduler)
    }
}
