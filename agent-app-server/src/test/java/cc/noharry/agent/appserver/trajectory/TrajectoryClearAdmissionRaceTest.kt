@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.runtime.RuntimeMultiSessionFixture
import cc.noharry.agent.appserver.runtime.RuntimeMultiSessionHarness
import cc.noharry.agent.appserver.runtime.StartRunAdmission
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Clear runtime data deletes EventLog roots while run admission is still closed, so a run
 * admitted mid-clear cannot start writing into a tree that is about to be removed. The wipe also
 * only runs once every writer has confirmed cancellation.
 */
class TrajectoryClearAdmissionRaceTest {

    @Test
    fun noRunIsAdmittedWhileClearIsDeletingEventLogRootsAndWritersAreConfirmedStopped() = runBlocking {
        RuntimeMultiSessionHarness().use { harness ->
            val held = (1..3).map { RuntimeMultiSessionFixture("session-$it", "run-$it") }
            held.forEach { harness.startHeld(it) }
            val admittedDuringDelete = AtomicBoolean(false)
            val writersStopped = AtomicReference<Boolean?>(null)
            val extras = (1..3).map { RuntimeMultiSessionFixture("extra-$it", "extra-$it") }
            val admissions = Collections.synchronizedList(mutableListOf<StartRunAdmission>())

            coroutineScope {
                val clear = async {
                    harness.runner.stopAllRunsThen { stopped ->
                        writersStopped.set(stopped)
                        // Stand-in for gateway.deleteAllRoots(): any run admitted in this window
                        // would be writing into roots that are being deleted.
                        repeat(20) { yield() }
                        if (harness.runner.activeRunCount > 0) admittedDuringDelete.set(true)
                    }.join()
                }
                extras.map { extra -> async { admissions += harness.startPrepared(extra) } }.awaitAll()
                clear.await()
            }

            assertFalse("a run was active while EventLog roots were being deleted", admittedDuringDelete.get())
            // Cooperative writers must report as stopped, otherwise the wipe would be refused.
            assertEquals(true, writersStopped.get())
            held.forEach { assertEquals(1, it.runtimeCloses.get()) }
            extras.forEach { extra ->
                val active = harness.runner.activeRunSnapshots().any { it.runId == extra.runId && it.isActive }
                // Each extra either lost admission and was closed, or started after the window.
                assertTrue(extra.runtimeCloses.get() == 1 || active)
                if (active) extra.release.complete(Unit)
            }
        }
    }
}
