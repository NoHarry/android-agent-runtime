@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelMessage
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.tools.ConcurrencyLimitedModelClient
import cc.noharry.agent.kernel.core.tools.ProviderConcurrencyLimiter
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConcurrencyLimitedModelClientTest {
    @Test
    fun globalSemaphoreCapsThreadSafeDelegate() = runBlocking {
        val delegate = ProbeModelClient(ProviderConcurrencyDeclaration.threadSafe(4))
        val client = ConcurrencyLimitedModelClient(
            delegate = delegate,
            maxConcurrentModelCalls = 1,
        )
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val first = launch { client.complete(request("a")).collect() }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        val secondEntered = CompletableDeferred<Unit>()
        val second = launch {
            client.complete(request("b")).collect()
            secondEntered.complete(Unit)
        }
        assertTrue(!secondEntered.isCompleted)
        assertEquals(1, delegate.maxActive.get())
        release.complete(Unit)
        first.join()
        withTimeout(TEST_TIMEOUT_MS) { second.join() }
        assertEquals(1, delegate.maxActive.get())
    }

    @Test
    fun providerDeclarationCapsBelowGlobalBudget() = runBlocking {
        val delegate = ProbeModelClient(ProviderConcurrencyDeclaration.serialized())
        val client = ConcurrencyLimitedModelClient(
            delegate = delegate,
            maxConcurrentModelCalls = 4,
        )
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val jobs = List(2) { index ->
            launch { client.complete(request("call-$index")).collect() }
        }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        assertEquals(1, delegate.maxActive.get())
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(1, delegate.maxActive.get())
    }

    @Test
    fun fullFlowCollectionHoldsBothPermitsUntilCompletion() = runBlocking {
        val delegate = ProbeModelClient(ProviderConcurrencyDeclaration.threadSafe(2))
        val limiter = ProviderConcurrencyLimiter()
        val client = ConcurrencyLimitedModelClient(
            delegate = delegate,
            limiter = limiter,
            maxConcurrentModelCalls = 2,
        )
        val mid = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        delegate.emitMid = mid
        delegate.hold = release

        val collected = async { client.complete(request("flow")).toList() }
        withTimeout(TEST_TIMEOUT_MS) { mid.await() }
        assertEquals(1, delegate.active.get())
        release.complete(Unit)
        val events = withTimeout(TEST_TIMEOUT_MS) { collected.await() }
        assertEquals(listOf(ModelEvent.TextDelta("ok"), ModelEvent.Completed), events)
        assertEquals(0, delegate.active.get())
    }

    @Test
    fun cancellationWhileBlockedAcquiringSerializedPermit() = runBlocking {
        val delegate = ProbeModelClient(ProviderConcurrencyDeclaration.serialized())
        val client = ConcurrencyLimitedModelClient(
            delegate = delegate,
            maxConcurrentModelCalls = 1,
        )
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val holder = launch { client.complete(request("holder")).collect() }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }

        val waiterStarted = CompletableDeferred<Unit>()
        val waiter = launch {
            waiterStarted.complete(Unit)
            client.complete(request("blocked")).collect()
        }
        withTimeout(TEST_TIMEOUT_MS) { waiterStarted.await() }
        repeat(16) { yield() }
        delay(20)
        assertEquals(1, delegate.active.get())
        waiter.cancelAndJoin()

        delegate.hold = null
        release.complete(Unit)
        holder.join()
        val events = withTimeout(TEST_TIMEOUT_MS) {
            client.complete(request("next")).toList()
        }
        assertTrue(events.contains(ModelEvent.Completed))
        assertEquals(1, delegate.maxActive.get())
    }

    @Test
    fun cancellationReleasesGlobalAndProviderPermits() = runBlocking {
        val delegate = ProbeModelClient(ProviderConcurrencyDeclaration.serialized())
        val client = ConcurrencyLimitedModelClient(
            delegate = delegate,
            maxConcurrentModelCalls = 1,
        )
        val release = CompletableDeferred<Unit>()
        delegate.hold = release

        val blocked = launch { client.complete(request("blocked")).collect() }
        withTimeout(TEST_TIMEOUT_MS) { delegate.entered.await() }
        blocked.cancelAndJoin()

        delegate.hold = null
        val events = withTimeout(TEST_TIMEOUT_MS) {
            client.complete(request("next")).toList()
        }
        assertTrue(events.contains(ModelEvent.Completed))
    }

    @Test
    fun overlappingClientsUseIndependentProviderCaps() = runBlocking {
        val first = ProbeModelClient(ProviderConcurrencyDeclaration.serialized())
        val second = ProbeModelClient(ProviderConcurrencyDeclaration.serialized())
        val limiter = ProviderConcurrencyLimiter()
        val left = ConcurrencyLimitedModelClient(first, limiter, maxConcurrentModelCalls = 2)
        val right = ConcurrencyLimitedModelClient(second, limiter, maxConcurrentModelCalls = 2)
        val release = CompletableDeferred<Unit>()
        first.hold = release
        second.hold = release

        coroutineScope {
            val jobs = listOf(left, right).map { client ->
                async { client.complete(request("overlap")).collect() }
            }
            withTimeout(TEST_TIMEOUT_MS) {
                first.entered.await()
                second.entered.await()
            }
            assertEquals(1, first.active.get())
            assertEquals(1, second.active.get())
            release.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(0, first.active.get())
        assertEquals(0, second.active.get())
    }

    @Test
    fun directSessionFactoryFailsClosed() {
        val delegate = ProbeModelClient(ProviderConcurrencyDeclaration.sessionFactory(1))
        val error = assertThrows(IllegalStateException::class.java) {
            ConcurrencyLimitedModelClient(delegate)
        }
        assertTrue(error.message.orEmpty().contains("SESSION_FACTORY"))
    }
}

private class ProbeModelClient(
    override val providerConcurrency: ProviderConcurrencyDeclaration,
) : ModelClient {
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val entered = CompletableDeferred<Unit>()
    var hold: CompletableDeferred<Unit>? = null
    var emitMid: CompletableDeferred<Unit>? = null

    override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
        if (!entered.isCompleted) entered.complete(Unit)
        try {
            emitMid?.complete(Unit)
            hold?.await()
            emit(ModelEvent.TextDelta("ok"))
            emit(ModelEvent.Completed)
        } finally {
            active.decrementAndGet()
        }
    }
}

private fun request(name: String): ModelRequest =
    ModelRequest(messages = listOf(ModelMessage(role = "user", content = name)))

private const val TEST_TIMEOUT_MS = 5_000L
