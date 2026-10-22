package cc.noharry.agent.kernel.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class SessionRunSchedulerTest {
    @Test
    fun configRequiresPositiveLimits() {
        assertThrows(IllegalArgumentException::class.java) {
            SessionRunScheduler(SessionRunSchedulerConfig(0, 1, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionRunScheduler(SessionRunSchedulerConfig(1, 0, 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionRunScheduler(SessionRunSchedulerConfig(1, 1, 0))
        }
    }

    @Test
    fun processLocalSchedulerStartsEmptyAndDoesNotRestoreAdmission() = runSchedulerTest {
        val scheduler = scheduler()
        val snap = scheduler.snapshot()
        assertEquals(0, snap.activeExecutionCount)
        assertEquals(0, snap.queuedCount)
        assertTrue(snap.owners.isEmpty())
        assertTrue(snap.readySessionIds.isEmpty())
        assertTrue(snap.waitingOwnerRunIds.isEmpty())
    }

    @Test
    fun sameSessionFifoAndMaxOneOwner() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 4)
        val started = mutableListOf<String>()
        val maxOwners = AtomicInteger(0)
        val first = holdOrdinary(scheduler, SESSION_A, RUN_A1, started, maxOwners)
        first.started.await()

        val second = holdOrdinary(scheduler, SESSION_A, RUN_A2, started, maxOwners)
        val third = holdOrdinary(scheduler, SESSION_A, RUN_A3, started, maxOwners)
        awaitSnapshot(scheduler) { it.queuedCount == 2 && it.owners[SESSION_A] == RUN_A1 }

        val mid = scheduler.snapshot()
        assertEquals(1, mid.activeExecutionCount)
        assertEquals(1, mid.owners.size)
        assertEquals(SessionLaneOwnerPhase.EXECUTING, mid.ownerPhases[SESSION_A])
        assertFalse(second.started.isCompleted)
        assertFalse(third.started.isCompleted)
        assertEquals(1, maxOwners.get())

        first.releaseAndJoin()
        second.started.await()
        assertEquals(listOf(RUN_A1, RUN_A2), started.toList())
        assertEquals(RUN_A2, scheduler.snapshot().owners[SESSION_A])
        assertFalse(third.started.isCompleted)

        second.releaseAndJoin()
        third.started.await()
        third.releaseAndJoin()
        assertEquals(listOf(RUN_A1, RUN_A2, RUN_A3), started.toList())
        assertEquals(1, maxOwners.get())
        assertIdle(scheduler)
    }

    @Test
    fun differentSessionsOverlapUpToCap() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 2)
        val started = mutableListOf<String>()
        val maxActive = AtomicInteger(0)
        val sessionA = holdOrdinary(scheduler, SESSION_A, RUN_A1, started, maxActive)
        sessionA.started.await()
        val sessionB = holdOrdinary(scheduler, SESSION_B, RUN_B1, started, maxActive)
        sessionB.started.await()

        val overlapping = scheduler.snapshot()
        assertEquals(2, overlapping.activeExecutionCount)
        assertEquals(setOf(SESSION_A, SESSION_B), overlapping.owners.keys)
        assertEquals(2, maxActive.get())

        val sessionC = holdOrdinary(scheduler, SESSION_C, RUN_C1, started, maxActive)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }
        assertFalse(sessionC.started.isCompleted)
        assertEquals(2, scheduler.snapshot().activeExecutionCount)
        assertEquals(2, maxActive.get())

        sessionA.releaseAndJoin()
        sessionC.started.await()
        assertEquals(setOf(SESSION_B, SESSION_C), scheduler.snapshot().owners.keys)
        assertEquals(2, maxActive.get())

        sessionB.releaseAndJoin()
        sessionC.releaseAndJoin()
        assertEquals(listOf(RUN_A1, RUN_B1, RUN_C1), started.toList())
        assertIdle(scheduler)
    }

    @Test
    fun heavySessionDoesNotStarveOthers() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val a2 = holdOrdinary(scheduler, SESSION_A, RUN_A2, started)
        val a3 = holdOrdinary(scheduler, SESSION_A, RUN_A3, started)
        val a4 = holdOrdinary(scheduler, SESSION_A, RUN_A4, started)
        awaitSnapshot(scheduler) { it.queuedCount == 3 }

        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        val c1 = holdOrdinary(scheduler, SESSION_C, RUN_C1, started)
        awaitSnapshot(scheduler) { it.queuedCount == 5 && it.readySessionIds == listOf(SESSION_B, SESSION_C) }

        a1.releaseAndJoin()
        b1.started.await()
        assertEquals(listOf(RUN_A1, RUN_B1), started.toList())
        assertFalse(a2.started.isCompleted)
        assertFalse(c1.started.isCompleted)

        b1.releaseAndJoin()
        c1.started.await()
        assertEquals(listOf(RUN_A1, RUN_B1, RUN_C1), started.toList())
        assertFalse(a2.started.isCompleted)

        c1.releaseAndJoin()
        a2.started.await()
        a2.releaseAndJoin()
        a3.started.await()
        a3.releaseAndJoin()
        a4.started.await()
        a4.releaseAndJoin()
        assertEquals(listOf(RUN_A1, RUN_B1, RUN_C1, RUN_A2, RUN_A3, RUN_A4), started.toList())
        assertIdle(scheduler)
    }

    @Test
    fun ownerQueryAndWaitingOwnerLeaseAreAtomic() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val lease = a1.lease.await()

        val executing = scheduler.ownerQuery(SESSION_A)
        assertEquals(lease, executing?.lease)
        assertEquals(SessionLaneOwnerPhase.EXECUTING, executing?.phase)
        assertEquals(null, scheduler.waitingOwnerLease(SESSION_A))
        assertEquals(null, scheduler.ownerQuery(SESSION_B))

        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(lease))
        val waiting = scheduler.ownerQuery(SESSION_A)
        assertEquals(lease, waiting?.lease)
        assertEquals(SessionLaneOwnerPhase.WAITING_CONTROL, waiting?.phase)
        assertEquals(lease, scheduler.waitingOwnerLease(SESSION_A))

        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        assertEquals(null, scheduler.ownerQuery(SESSION_A))
        assertEquals(null, scheduler.waitingOwnerLease(SESSION_A))
        a1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun waitingControlReleasesExecutionAndRetainsOwner() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))
        val waiting = scheduler.snapshot()
        assertEquals(0, waiting.activeExecutionCount)
        assertEquals(RUN_A1, waiting.owners[SESSION_A])
        assertEquals(listOf(RUN_A1), waiting.waitingOwnerRunIds)
        assertEquals(SessionLaneOwnerPhase.WAITING_CONTROL, waiting.ownerPhases[SESSION_A])

        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        b1.started.await()
        assertEquals(1, scheduler.snapshot().activeExecutionCount)
        assertEquals(setOf(SESSION_A, SESSION_B), scheduler.snapshot().owners.keys)
        assertEquals(RUN_A1, scheduler.snapshot().owners[SESSION_A])

        b1.releaseAndJoin()
        a1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun waitingControlKeepsQueuedOrdinaryBlocked() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 2)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val a2 = holdOrdinary(scheduler, SESSION_A, RUN_A2, started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))
        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        b1.started.await()

        val snap = scheduler.snapshot()
        assertEquals(1, snap.activeExecutionCount)
        assertEquals(1, snap.queuedCount)
        assertEquals(RUN_A1, snap.owners[SESSION_A])
        assertFalse(a2.started.isCompleted)

        a1.releaseAndJoin()
        a2.started.await()
        assertEquals(listOf(RUN_A1, RUN_B1, RUN_A2), started.toList())
        a2.releaseAndJoin()
        b1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun resumeReacquiresThroughFairReadyRing() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))
        b1.started.await()

        val resumeStarted = CompletableDeferred<SessionRunAdmission>()
        val resumeHold = CompletableDeferred<Unit>()
        val resumeJob = launch {
            val admission = scheduler.admitResume(SESSION_A, RUN_A1)
            resumeStarted.complete(admission)
            resumeHold.await()
        }
        awaitSnapshot(scheduler) { it.readySessionIds == listOf(SESSION_A) }

        val c1 = holdOrdinary(scheduler, SESSION_C, RUN_C1, started)
        awaitSnapshot(scheduler) { it.readySessionIds == listOf(SESSION_A, SESSION_C) }

        b1.releaseAndJoin()
        val resumeAdmission = resumeStarted.await()
        assertTrue(resumeAdmission is SessionRunAdmission.Granted)
        val resumeLease = (resumeAdmission as SessionRunAdmission.Granted).lease
        assertEquals(SessionRunGrantKind.RESUME, resumeAdmission.kind)
        assertTrue(resumeLease.id > a1.lease.await().id)
        assertEquals(RUN_A1, scheduler.snapshot().owners[SESSION_A])
        assertEquals(SessionLaneOwnerPhase.EXECUTING, scheduler.snapshot().ownerPhases[SESSION_A])
        assertFalse(c1.started.isCompleted)

        resumeHold.complete(Unit)
        resumeJob.join()
        assertEquals(SessionRunTransition.Applied, scheduler.complete(resumeLease))
        a1.hold.complete(Unit)
        a1.job.join()
        c1.started.await()
        c1.releaseAndJoin()
        assertEquals(listOf(RUN_A1, RUN_B1, RUN_C1), started.toList())
        assertIdle(scheduler)
    }

    @Test
    fun queuedCancellationRemovesRequestWithoutLeak() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val queued = launch { scheduler.admitOrdinary(SESSION_B, RUN_B1) }
        awaitSnapshot(scheduler) { it.queuedCount == 1 && it.readySessionIds == listOf(SESSION_B) }
        queued.cancelAndJoin()

        val afterCancel = scheduler.snapshot()
        assertEquals(0, afterCancel.queuedCount)
        assertTrue(afterCancel.readySessionIds.isEmpty())
        assertEquals(1, afterCancel.activeExecutionCount)
        assertEquals(mapOf(SESSION_A to RUN_A1), afterCancel.owners)

        val c1 = holdOrdinary(scheduler, SESSION_C, RUN_C1, started)
        a1.releaseAndJoin()
        c1.started.await()
        c1.releaseAndJoin()
        assertEquals(listOf(RUN_A1, RUN_C1), started.toList())
        assertIdle(scheduler)
    }

    @Test
    fun resumeWaitingCancellationRemovesRequestWithoutLeak() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))

        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        b1.started.await()

        val resumeJob = launch { scheduler.admitResume(SESSION_A, RUN_A1) }
        awaitSnapshot(scheduler) { it.readySessionIds == listOf(SESSION_A) }
        resumeJob.cancelAndJoin()

        val afterCancel = scheduler.snapshot()
        assertTrue(afterCancel.readySessionIds.isEmpty())
        assertEquals(0, afterCancel.queuedCount)
        assertEquals(RUN_A1, afterCancel.owners[SESSION_A])
        assertEquals(SessionLaneOwnerPhase.WAITING_CONTROL, afterCancel.ownerPhases[SESSION_A])
        assertEquals(1, afterCancel.activeExecutionCount)

        val c1 = holdOrdinary(scheduler, SESSION_C, RUN_C1, started)
        b1.releaseAndJoin()
        c1.started.await()
        assertEquals(RUN_C1, scheduler.snapshot().owners[SESSION_C])
        assertEquals(RUN_A1, scheduler.snapshot().owners[SESSION_A])

        c1.releaseAndJoin()
        a1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun completionReleasesPermitExactlyOnce() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        val lease = a1.lease.await()
        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        a1.hold.complete(Unit)
        a1.job.join()
        b1.started.await()
        assertEquals(1, scheduler.snapshot().activeExecutionCount)
        b1.releaseAndJoin()
        assertEquals(listOf(RUN_A1, RUN_B1), started.toList())
        assertIdle(scheduler)
    }

    @Test
    fun failureCompletionReleasesPermitExactlyOnce() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        val lease = a1.lease.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markFinalizing(lease))
        assertEquals(SessionLaneOwnerPhase.FINALIZING, scheduler.snapshot().ownerPhases[SESSION_A])
        assertEquals(1, scheduler.snapshot().activeExecutionCount)
        assertFalse(b1.started.isCompleted)

        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        assertEquals(SessionRunTransition.Applied, scheduler.complete(lease))
        a1.hold.complete(Unit)
        a1.job.join()
        b1.started.await()
        b1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun duplicateRunIdFailClosed() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 2)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val duplicateOwner = scheduler.admitOrdinary(SESSION_B, RUN_A1)
        assertEquals(
            SessionRunAdmissionReason.DUPLICATE_RUN_ID,
            (duplicateOwner as SessionRunAdmission.Rejected).reason,
        )

        val queued = holdOrdinary(scheduler, SESSION_A, RUN_A2, started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }
        val duplicateQueued = scheduler.admitOrdinary(SESSION_A, RUN_A2)
        assertEquals(
            SessionRunAdmissionReason.DUPLICATE_RUN_ID,
            (duplicateQueued as SessionRunAdmission.Rejected).reason,
        )

        a1.releaseAndJoin()
        queued.started.await()
        queued.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun mismatchedAndNonWaitingResumeFailClosed() = runSchedulerTest {
        val scheduler = scheduler()
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()

        val executing = scheduler.admitResume(SESSION_A, RUN_A1)
        assertEquals(
            SessionRunAdmissionReason.NOT_WAITING,
            (executing as SessionRunAdmission.Rejected).reason,
        )

        val unknown = scheduler.admitResume(SESSION_B, RUN_B1)
        assertEquals(
            SessionRunAdmissionReason.UNKNOWN_RUN,
            (unknown as SessionRunAdmission.Rejected).reason,
        )

        val mismatched = scheduler.admitResume(SESSION_A, RUN_A2)
        assertEquals(
            SessionRunAdmissionReason.MISMATCHED_RUN,
            (mismatched as SessionRunAdmission.Rejected).reason,
        )

        a1.releaseAndJoin()
        val afterComplete = scheduler.admitResume(SESSION_A, RUN_A1)
        assertEquals(
            SessionRunAdmissionReason.UNKNOWN_RUN,
            (afterComplete as SessionRunAdmission.Rejected).reason,
        )
        assertIdle(scheduler)
    }

    @Test
    fun duplicateResumeFailClosed() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))
        b1.started.await()

        val firstResume = launch { scheduler.admitResume(SESSION_A, RUN_A1) }
        awaitSnapshot(scheduler) { it.readySessionIds == listOf(SESSION_A) }
        val duplicate = scheduler.admitResume(SESSION_A, RUN_A1)
        assertEquals(
            SessionRunAdmissionReason.DUPLICATE_RESUME,
            (duplicate as SessionRunAdmission.Rejected).reason,
        )

        firstResume.cancelAndJoin()
        b1.releaseAndJoin()
        a1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun markFinalizingPreservesOwnerUntilTerminal() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        awaitSnapshot(scheduler) { it.queuedCount == 1 }

        val lease = a1.lease.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markFinalizing(lease))
        assertEquals(SessionRunTransition.Applied, scheduler.markFinalizing(lease))
        val snap = scheduler.snapshot()
        assertEquals(1, snap.activeExecutionCount)
        assertEquals(RUN_A1, snap.owners[SESSION_A])
        assertEquals(SessionLaneOwnerPhase.FINALIZING, snap.ownerPhases[SESSION_A])
        assertFalse(b1.started.isCompleted)

        a1.releaseAndJoin()
        b1.started.await()
        b1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun invalidIdentityAndWaitingCannotFinalize() = runSchedulerTest {
        val scheduler = scheduler()
        val started = mutableListOf<String>()
        assertEquals(
            SessionRunAdmissionReason.INVALID_IDENTITY,
            (scheduler.admitOrdinary("", RUN_A1) as SessionRunAdmission.Rejected).reason,
        )
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(a1.lease.await()))
        assertEquals(
            SessionRunTransitionReason.INVALID_PHASE,
            (scheduler.markFinalizing(a1.lease.await()) as SessionRunTransition.Rejected).reason,
        )
        a1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun grantedAdmissionCancellationReleasesPermit() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        awaitSnapshot(scheduler) { it.owners[SESSION_A] == RUN_A1 && it.activeExecutionCount == 1 }

        a1.job.cancelAndJoin()
        assertIdle(scheduler)

        val b1 = holdOrdinary(scheduler, SESSION_B, RUN_B1, started)
        b1.started.await()
        assertEquals(listOf(RUN_A1, RUN_B1), started.toList())
        b1.releaseAndJoin()
        assertIdle(scheduler)
    }

    @Test
    fun staleOrdinaryCompleteAfterResumeGrantDoesNotReleaseOwner() = runSchedulerTest {
        val scheduler = scheduler(maxActive = 1)
        val started = mutableListOf<String>()
        val a1 = holdOrdinary(scheduler, SESSION_A, RUN_A1, started)
        a1.started.await()
        val oldLease = a1.lease.await()
        assertEquals(SessionRunTransition.Applied, scheduler.markWaitingControl(oldLease))

        val resume = scheduler.admitResume(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        assertEquals(SessionRunGrantKind.RESUME, resume.kind)
        assertTrue(resume.lease.id > oldLease.id)
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.complete(oldLease) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.attachJob(oldLease, Job()) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.markWaitingControl(oldLease) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.markFinalizing(oldLease) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(RUN_A1, scheduler.snapshot().owners[SESSION_A])
        assertEquals(SessionLaneOwnerPhase.EXECUTING, scheduler.snapshot().ownerPhases[SESSION_A])
        assertEquals(1, scheduler.snapshot().activeExecutionCount)
        assertEquals(SessionRunTransition.Applied, scheduler.complete(resume.lease))
        a1.hold.complete(Unit)
        a1.job.join()
        assertIdle(scheduler)
    }

    @Test
    fun runIdReuseRejectsStaleCompletionWithoutTombstones() = runSchedulerTest {
        val scheduler = scheduler()
        val first = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        assertEquals(SessionRunTransition.Applied, scheduler.complete(first.lease))
        assertTrue(scheduler.snapshot().owners.isEmpty())

        val second = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        assertTrue(second.lease.id > first.lease.id)
        assertEquals(first.lease.runId, second.lease.runId)
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.complete(first.lease) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(
            SessionRunTransitionReason.STALE_LEASE,
            (scheduler.attachJob(first.lease, Job()) as SessionRunTransition.Rejected).reason,
        )
        assertEquals(RUN_A1, scheduler.snapshot().owners[SESSION_A])
        assertEquals(1, scheduler.snapshot().activeExecutionCount)
        assertEquals(SessionRunTransition.Applied, scheduler.complete(second.lease))
        assertIdle(scheduler)
    }

    @Test
    fun afterAttachRevalidateRejectsClosedAndMismatchedJob() = runSchedulerTest {
        val scheduler = scheduler()
        val job = Job()
        val other = Job()
        val granted = scheduler.admitOrdinary(SESSION_A, RUN_A1) as SessionRunAdmission.Granted
        val attached = CompletableDeferred<Unit>()
        scheduler.afterAttachJobForTest = { attached.complete(Unit) }
        assertEquals(SessionRunTransition.Applied, scheduler.attachJob(granted.lease, job))
        attached.await()
        scheduler.afterAttachJobForTest = null
        assertTrue(scheduler.isCurrentExecutingOwner(granted.lease, job))
        assertFalse(scheduler.isCurrentExecutingOwner(granted.lease, other))
        scheduler.close()
        assertFalse(scheduler.isCurrentExecutingOwner(granted.lease, job))
        job.complete()
        other.complete()
        scheduler.complete(granted.lease)
    }
}
