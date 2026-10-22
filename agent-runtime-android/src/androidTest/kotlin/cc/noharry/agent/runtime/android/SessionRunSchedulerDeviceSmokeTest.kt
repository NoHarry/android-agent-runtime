package cc.noharry.agent.runtime.android

import androidx.test.runner.AndroidJUnit4
import cc.noharry.agent.kernel.core.SessionRunAdmission
import cc.noharry.agent.kernel.core.SessionRunAdmissionReason
import cc.noharry.agent.kernel.core.SessionRunQueuedIdentity
import cc.noharry.agent.kernel.core.SessionRunScheduler
import cc.noharry.agent.kernel.core.SessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.SessionRunSchedulerSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.coroutineContext

@RunWith(AndroidJUnit4::class)
class SessionRunSchedulerDeviceSmokeTest {
    @Test
    fun deviceSmokeProvesOverlapQueueCancelReleaseReadmitAndClose() {
        runBlocking {
            withTimeout(CASE_TIMEOUT_MS) {
                val scheduler = SessionRunScheduler(DEVICE_CONFIG)
                val jobs = mutableListOf<Job>()
                try {
                    val overlap = proveOverlapQueueAndCancel(scheduler, jobs)
                    proveReleaseAndReadmit(scheduler, jobs, overlap.first, overlap.second)
                    proveCloseReturnsAttachedAndQueued(scheduler, jobs)
                } finally {
                    cancelAndJoinAll(jobs)
                }
            }
        }
    }
}

private suspend fun CoroutineScope.proveOverlapQueueAndCancel(
    scheduler: SessionRunScheduler,
    jobs: MutableList<Job>,
): Pair<HeldOrdinaryRun, HeldOrdinaryRun> {
    val startedOrder = mutableListOf<String>()
    val sessionA = holdOrdinary(scheduler, SESSION_A, RUN_A1, startedOrder)
    val sessionB = holdOrdinary(scheduler, SESSION_B, RUN_B1, startedOrder)
    jobs += sessionA.job
    jobs += sessionB.job
    sessionA.started.await()
    sessionB.started.await()
    val overlapping = scheduler.snapshot()
    assertEquals(EXPECTED_ACTIVE_OVERLAP, overlapping.activeExecutionCount)
    assertEquals(setOf(SESSION_A, SESSION_B), overlapping.owners.keys)

    val queued = launch { scheduler.admitOrdinary(SESSION_A, RUN_A2) }
    jobs += queued
    awaitSnapshot(scheduler) { it.queuedCount == 1 && !queued.isCompleted }
    queued.cancel()
    withTimeout(POLL_TIMEOUT_MS) { queued.join() }
    awaitSnapshot(scheduler) { it.queuedCount == 0 }
    assertEquals(EXPECTED_ACTIVE_OVERLAP, scheduler.snapshot().activeExecutionCount)
    return sessionA to sessionB
}

private suspend fun CoroutineScope.proveReleaseAndReadmit(
    scheduler: SessionRunScheduler,
    jobs: MutableList<Job>,
    sessionA: HeldOrdinaryRun,
    sessionB: HeldOrdinaryRun,
) {
    sessionA.releaseAndJoin()
    sessionB.releaseAndJoin()
    val idle = scheduler.snapshot()
    assertEquals(0, idle.activeExecutionCount)
    assertTrue(idle.owners.isEmpty())

    val startedOrder = mutableListOf<String>()
    val readmit = holdOrdinary(scheduler, SESSION_C, RUN_C1, startedOrder)
    jobs += readmit.job
    readmit.started.await()
    assertEquals(1, scheduler.snapshot().activeExecutionCount)
    assertEquals(RUN_C1, scheduler.snapshot().owners[SESSION_C])
    readmit.releaseAndJoin()
    assertIdle(scheduler)
}

private suspend fun CoroutineScope.proveCloseReturnsAttachedAndQueued(
    scheduler: SessionRunScheduler,
    jobs: MutableList<Job>,
) {
    val owner = holdOrdinary(scheduler, SESSION_CLOSE, RUN_OWNER, mutableListOf())
    jobs += owner.job
    owner.started.await()
    val queuedAdmission = CompletableDeferred<SessionRunAdmission>()
    val queued = launch { queuedAdmission.complete(scheduler.admitOrdinary(SESSION_CLOSE, RUN_QUEUED)) }
    jobs += queued
    awaitSnapshot(scheduler) { it.queuedCount == 1 }

    val closed = scheduler.close()
    assertEquals(listOf(RUN_OWNER), closed.attachedJobs.map { it.runId })
    assertEquals(listOf(SessionRunQueuedIdentity(SESSION_CLOSE, RUN_QUEUED)), closed.queuedIdentities)
    assertTrue(closed.attachedJobs.all { it.job.isActive })
    closed.attachedJobs.forEach { it.job.cancel() }
    withTimeout(POLL_TIMEOUT_MS) {
        closed.attachedJobs.forEach { it.job.join() }
        owner.job.join()
        queued.join()
    }
    val rejected = queuedAdmission.await() as SessionRunAdmission.Rejected
    assertEquals(SessionRunAdmissionReason.CLOSED, rejected.reason)
    assertIdle(scheduler)
}

private suspend fun awaitSnapshot(
    scheduler: SessionRunScheduler,
    predicate: (SessionRunSchedulerSnapshot) -> Boolean,
): SessionRunSchedulerSnapshot {
    return withTimeout(POLL_TIMEOUT_MS) {
        var snap = scheduler.snapshot()
        while (!predicate(snap)) {
            yield()
            snap = scheduler.snapshot()
        }
        snap
    }
}

private suspend fun assertIdle(scheduler: SessionRunScheduler) {
    val snap = scheduler.snapshot()
    assertEquals(0, snap.activeExecutionCount)
    assertEquals(0, snap.queuedCount)
    assertTrue(snap.owners.isEmpty())
    assertTrue(snap.readySessionIds.isEmpty())
    assertTrue(snap.waitingOwnerRunIds.isEmpty())
    assertTrue(snap.drainingSessionIds.isEmpty())
}

private suspend fun cancelAndJoinAll(jobs: List<Job>) {
    jobs.forEach { it.cancel() }
    withTimeout(POLL_TIMEOUT_MS) { jobs.forEach { it.join() } }
}

private class HeldOrdinaryRun(
    val job: Job,
    val started: CompletableDeferred<Unit>,
    val hold: CompletableDeferred<Unit>,
) {
    suspend fun releaseAndJoin() {
        hold.complete(Unit)
        withTimeout(POLL_TIMEOUT_MS) { job.join() }
    }
}

private fun CoroutineScope.holdOrdinary(
    scheduler: SessionRunScheduler,
    sessionId: String,
    runId: String,
    startedOrder: MutableList<String>,
): HeldOrdinaryRun {
    val started = CompletableDeferred<Unit>()
    val hold = CompletableDeferred<Unit>()
    val job = launch {
        val admission = scheduler.admitOrdinary(sessionId, runId)
        if (admission !is SessionRunAdmission.Granted) {
            return@launch
        }
        try {
            scheduler.attachJob(admission.lease, coroutineContext[Job]!!)
            startedOrder += runId
            started.complete(Unit)
            hold.await()
        } finally {
            val completion = scheduler.completeAndQueryDrain(admission.lease)
            completion.drainLease?.let { scheduler.finishSessionDrain(it) }
        }
    }
    return HeldOrdinaryRun(job, started, hold)
}

private const val EXPECTED_ACTIVE_OVERLAP = 2
private const val MAX_QUEUED_PER_SESSION = 8
private const val MAX_QUEUED_GLOBAL = 16

private val DEVICE_CONFIG = SessionRunSchedulerConfig(
    maxActiveSessions = EXPECTED_ACTIVE_OVERLAP,
    maxQueuedRunsPerSession = MAX_QUEUED_PER_SESSION,
    maxQueuedRunsGlobal = MAX_QUEUED_GLOBAL,
)
private const val CASE_TIMEOUT_MS = 15_000L
private const val POLL_TIMEOUT_MS = 5_000L
private const val SESSION_A = "session-a"
private const val SESSION_B = "session-b"
private const val SESSION_C = "session-c"
private const val SESSION_CLOSE = "session-close"
private const val RUN_A1 = "run-a1"
private const val RUN_A2 = "run-a2"
private const val RUN_B1 = "run-b1"
private const val RUN_C1 = "run-c1"
private const val RUN_OWNER = "run-owner"
private const val RUN_QUEUED = "run-queued"
