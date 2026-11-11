@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.ThreadEvent
import cc.noharry.agent.appserver.runtime.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

class ExecutionThreadingTest {
    @Test fun blockedConstructionAndLateCancellationKeepUiFreeAndCloseOffUi() = runBlocking {
        Executors.newSingleThreadExecutor { Thread(it, "construction-ui") }.asCoroutineDispatcher().use { ui ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val buildThread = AtomicReference<String>()
            val closeThread = AtomicReference<String>()
            val closes = AtomicInteger()
            val delegate = RuntimeMultiSessionFixture("construction", "construction-run").runtime
            val runtime = object : cc.noharry.agent.AgentRuntime by delegate {
                override fun close() { closeThread.set(Thread.currentThread().name); closes.incrementAndGet() }
            }
            val job = launch(ui) {
                buildRuntimeOffMain {
                    buildThread.set(Thread.currentThread().name)
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    runtime
                }
            }
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS))
                withTimeout(2_000) { withContext(ui) { assertTrue(Thread.currentThread().name.startsWith("construction-ui")) } }
                job.cancel()
                release.countDown()
                job.join()
                assertFalse(buildThread.get().startsWith("construction-ui"))
                assertFalse(closeThread.get().startsWith("construction-ui"))
                assertEquals(1, closes.get())
            } finally { release.countDown(); job.cancelAndJoin() }
        }
    }
}
