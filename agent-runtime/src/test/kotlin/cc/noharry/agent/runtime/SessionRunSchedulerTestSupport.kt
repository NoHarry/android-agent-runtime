package cc.noharry.agent.runtime

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import cc.noharry.agent.kernel.core.SessionRunAdmission
import cc.noharry.agent.kernel.core.SessionRunLease
import cc.noharry.agent.kernel.core.SessionRunScheduler
import cc.noharry.agent.kernel.core.SessionRunSchedulerConfig
import cc.noharry.agent.kernel.core.SessionRunSchedulerSnapshot

internal const val SCHEDULER_TEST_TIMEOUT_MS = 5_000L
internal const val SCHEDULER_TEST_CASE_TIMEOUT_MS = 15_000L

internal fun runSchedulerTest(block: suspend CoroutineScope.() -> Unit) {
    runBlocking {
        withTimeout(SCHEDULER_TEST_CASE_TIMEOUT_MS) {
            block()
        }
    }
}

internal fun scheduler(
    maxActive: Int = 2,
    maxQueuedPerSession: Int = 8,
    maxQueuedGlobal: Int = 16,
): SessionRunScheduler =
    SessionRunScheduler(
        SessionRunSchedulerConfig(
            maxActiveSessions = maxActive,
            maxQueuedRunsPerSession = maxQueuedPerSession,
            maxQueuedRunsGlobal = maxQueuedGlobal,
        ),
    )

internal suspend fun awaitSnapshot(
    scheduler: SessionRunScheduler,
    predicate: (SessionRunSchedulerSnapshot) -> Boolean,
): SessionRunSchedulerSnapshot {
    return withTimeout(SCHEDULER_TEST_TIMEOUT_MS) {
        var snap = scheduler.snapshot()
        while (!predicate(snap)) {
            yield()
            snap = scheduler.snapshot()
        }
        assertBounded(snap)
        snap
    }
}

internal suspend fun assertIdle(scheduler: SessionRunScheduler) {
    val snap = scheduler.snapshot()
    assertBounded(snap)
    assertEquals(0, snap.activeExecutionCount)
    assertEquals(0, snap.queuedCount)
    assertTrue(snap.owners.isEmpty())
    assertTrue(snap.readySessionIds.isEmpty())
    assertTrue(snap.waitingOwnerRunIds.isEmpty())
    assertTrue(snap.drainingSessionIds.isEmpty())
}

internal fun assertBounded(snap: SessionRunSchedulerSnapshot) {
    assertTrue(snap.activeExecutionCount >= 0)
    assertTrue(snap.queuedCount >= 0)
    assertTrue(snap.owners.size >= snap.waitingOwnerRunIds.size)
    assertTrue(snap.readySessionIds.size <= snap.owners.size + snap.queuedCount)
    assertEquals(snap.readySessionIds.size, snap.readySessionIds.distinct().size)
}

internal class HeldOrdinaryRun(
    val job: Job,
    val started: CompletableDeferred<Unit>,
    val hold: CompletableDeferred<Unit>,
    val lease: CompletableDeferred<SessionRunLease>,
) {
    suspend fun releaseAndJoin() {
        hold.complete(Unit)
        withTimeout(SCHEDULER_TEST_TIMEOUT_MS) { job.join() }
    }
}

internal fun CoroutineScope.holdOrdinary(
    scheduler: SessionRunScheduler,
    sessionId: String,
    runId: String,
    startedOrder: MutableList<String>,
    maxObservedActive: AtomicInteger? = null,
): HeldOrdinaryRun {
    val started = CompletableDeferred<Unit>()
    val hold = CompletableDeferred<Unit>()
    val lease = CompletableDeferred<SessionRunLease>()
    val job = launch {
        val admission = scheduler.admitOrdinary(sessionId, runId)
        if (admission !is SessionRunAdmission.Granted) {
            return@launch
        }
        lease.complete(admission.lease)
        try {
            scheduler.attachJob(admission.lease, coroutineContext[Job]!!)
            startedOrder += runId
            maxObservedActive?.let { counter ->
                val active = scheduler.snapshot().owners.size
                counter.updateAndGet { current -> maxOf(current, active) }
            }
            started.complete(Unit)
            hold.await()
        } finally {
            val completion = scheduler.completeAndQueryDrain(admission.lease)
            completion.drainLease?.let { scheduler.finishSessionDrain(it) }
        }
    }
    return HeldOrdinaryRun(job, started, hold, lease)
}
