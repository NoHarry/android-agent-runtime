@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ExecEngine
import cc.noharry.agent.kernel.api.ExecRequest
import cc.noharry.agent.kernel.api.ExecResult
import cc.noharry.agent.kernel.api.ExecToolBridge
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.core.tools.ConcurrencyLimitedExecEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyLimitedExecEngineTest {
    @Test
    fun serializedDeclarationCapsAtOne() = runBlocking {
        val delegate = ProbeExecEngine(ProviderConcurrencyDeclaration.serialized())
        val engine = ConcurrencyLimitedExecEngine(delegate)
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val first = launch { engine.run(request("a"), NoopBridge) }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        val secondStarted = CompletableDeferred<Unit>()
        val second = launch {
            engine.run(request("b"), NoopBridge)
            secondStarted.complete(Unit)
        }
        repeat(16) { yield() }
        delay(20)
        assertTrue(!secondStarted.isCompleted)
        assertEquals(1, delegate.maxActive.get())
        release.complete(Unit)
        first.join()
        withTimeout(TEST_TIMEOUT_MS) { second.join() }
        assertEquals(1, delegate.maxActive.get())
    }

    @Test
    fun cancellationReleasesProviderPermit() = runBlocking {
        val delegate = ProbeExecEngine(ProviderConcurrencyDeclaration.serialized())
        val engine = ConcurrencyLimitedExecEngine(delegate)
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val blocked = launch { engine.run(request("blocked"), NoopBridge) }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        blocked.cancelAndJoin()

        delegate.hold = null
        val result = withTimeout(TEST_TIMEOUT_MS) {
            engine.run(request("next"), NoopBridge)
        }
        assertTrue(result.ok)
    }

    @Test
    fun cancellationWhileBlockedAcquiringSerializedPermit() = runBlocking {
        val delegate = ProbeExecEngine(ProviderConcurrencyDeclaration.serialized())
        val engine = ConcurrencyLimitedExecEngine(delegate)
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val holder = launch { engine.run(request("holder"), NoopBridge) }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }

        val waiterStarted = CompletableDeferred<Unit>()
        val waiter = launch {
            waiterStarted.complete(Unit)
            engine.run(request("blocked"), NoopBridge)
        }
        withTimeout(TEST_TIMEOUT_MS) { waiterStarted.await() }
        repeat(16) { yield() }
        delay(20)
        assertEquals(1, delegate.active.get())
        waiter.cancelAndJoin()

        delegate.hold = null
        release.complete(Unit)
        holder.join()
        val result = withTimeout(TEST_TIMEOUT_MS) {
            engine.run(request("next"), NoopBridge)
        }
        assertTrue(result.ok)
        assertEquals(1, delegate.maxActive.get())
    }

    @Test
    fun directSessionFactoryFailsClosed() {
        val delegate = ProbeExecEngine(ProviderConcurrencyDeclaration.sessionFactory(1))
        val error = assertThrows(IllegalStateException::class.java) {
            ConcurrencyLimitedExecEngine(delegate)
        }
        assertTrue(error.message.orEmpty().contains("SESSION_FACTORY"))
    }
}

private class ProbeExecEngine(
    override val providerConcurrency: ProviderConcurrencyDeclaration,
) : ExecEngine {
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val entered = CompletableDeferred<Unit>()
    var hold: CompletableDeferred<Unit>? = null

    override suspend fun run(request: ExecRequest, bridge: ExecToolBridge): ExecResult {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
        if (!entered.isCompleted) entered.complete(Unit)
        return try {
            hold?.await()
            ExecResult(ok = true, output = request.runId)
        } finally {
            active.decrementAndGet()
        }
    }
}

private object NoopBridge : ExecToolBridge {
    override suspend fun callTool(name: String, argumentsJson: String): String = "{}"

    override suspend fun emitOutput(text: String) = Unit
}

private fun request(name: String): ExecRequest =
    ExecRequest(runId = name, code = "return 1", timeoutMillis = 1_000L, outputTokenBudget = 64)

private const val TEST_TIMEOUT_MS = 5_000L
