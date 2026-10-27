@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.kernel.core.tools
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.ToolCallContext
import cc.noharry.agent.kernel.api.ToolCallResult
import cc.noharry.agent.kernel.api.mcp.McpClientProvider
import cc.noharry.agent.kernel.api.ToolDescriptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ProviderConcurrencyLimiterTest {
    @Test
    fun objectIdentityIsolatesSameServerId() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val first = ProbeMcpProvider(serverId = "shared", providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val second = ProbeMcpProvider(serverId = "shared", providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val firstHold = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val firstJob = launch {
            limiter.withPermit(first) {
                first.enter()
                firstHold.await()
                first.leave()
            }
        }
        first.entered.await()
        val secondJob = launch {
            limiter.withPermit(second) {
                second.enter()
                secondEntered.complete(Unit)
                second.leave()
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { secondEntered.await() }
        firstHold.complete(Unit)
        firstJob.join()
        secondJob.join()

        assertEquals(1, first.maxActive.get())
        assertEquals(1, second.maxActive.get())
        assertEquals(0, first.active.get())
        assertEquals(0, second.active.get())
    }

    @Test
    fun serializedCapsAtOne() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val firstJob = launch {
            limiter.withPermit(provider) {
                provider.enter()
                firstEntered.complete(Unit)
                release.await()
                provider.leave()
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { firstEntered.await() }
        val secondJob = launch {
            limiter.withPermit(provider) {
                provider.enter()
                secondEntered.complete(Unit)
                release.await()
                provider.leave()
            }
        }
        assertTrue(!secondEntered.isCompleted)
        assertEquals(1, provider.maxActive.get())
        release.complete(Unit)
        firstJob.join()
        withTimeout(TEST_TIMEOUT_MS) { secondEntered.await() }
        secondJob.join()
        assertEquals(1, provider.maxActive.get())
    }

    @Test
    fun boundedThreadSafeCapsAtN() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(providerConcurrency = ProviderConcurrencyDeclaration.threadSafe(2))
        val enteredTwo = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val jobs = List(3) {
            launch {
                limiter.withPermit(provider) {
                    provider.enter()
                    if (provider.active.get() == 2 && !enteredTwo.isCompleted) {
                        enteredTwo.complete(Unit)
                    }
                    release.await()
                    provider.leave()
                }
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { enteredTwo.await() }
        assertEquals(2, provider.maxActive.get())
        release.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(2, provider.maxActive.get())
    }

    @Test
    fun differentProvidersOverlap() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val first = ProbeMcpProvider(serverId = "a", providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val second = ProbeMcpProvider(serverId = "b", providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val bothActive = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val overlap = AtomicInteger(0)

        coroutineScope {
            val jobs = listOf(first, second).map { provider ->
                async {
                    limiter.withPermit(provider) {
                        provider.enter()
                        if (first.active.get() == 1 && second.active.get() == 1) {
                            overlap.set(1)
                            bothActive.complete(Unit)
                        }
                        release.await()
                        provider.leave()
                    }
                }
            }
            withTimeout(TEST_TIMEOUT_MS) { bothActive.await() }
            release.complete(Unit)
            jobs.awaitAll()
        }
        assertEquals(1, overlap.get())
    }

    @Test
    fun cancellationWhileBlockedAcquiringSerializedPermit() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val release = CompletableDeferred<Unit>()
        val holder = launch {
            limiter.withPermit(provider) {
                provider.enter()
                try {
                    release.await()
                } finally {
                    provider.leave()
                }
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { provider.entered.await() }

        val waiterStarted = CompletableDeferred<Unit>()
        val waiterEntered = CompletableDeferred<Unit>()
        val waiter = launch {
            waiterStarted.complete(Unit)
            limiter.withPermit(provider) {
                waiterEntered.complete(Unit)
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { waiterStarted.await() }
        repeat(16) { yield() }
        delay(20)
        assertTrue(!waiterEntered.isCompleted)
        waiter.cancelAndJoin()
        assertTrue(!waiterEntered.isCompleted)

        release.complete(Unit)
        holder.join()
        val nextEntered = CompletableDeferred<Unit>()
        withTimeout(TEST_TIMEOUT_MS) {
            limiter.withPermit(provider) {
                nextEntered.complete(Unit)
            }
        }
        assertTrue(nextEntered.isCompleted)
        assertEquals(1, provider.maxActive.get())
    }

    @Test
    fun closeClearsCachedLimiterEntries() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        limiter.withPermit(provider) { }
        assertEquals(1, limiter.cachedEntryCountForTests())
        limiter.close()
        assertEquals(0, limiter.cachedEntryCountForTests())
        limiter.close()
        assertEquals(0, limiter.cachedEntryCountForTests())
    }

    @Test
    fun closeAndPermitRaceRejectsNewPermitsAndCannotResurrectMap() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val inPermit = CompletableDeferred<Unit>()
        val releasePermit = CompletableDeferred<Unit>()
        val holder = launch {
            limiter.withPermit(provider) {
                inPermit.complete(Unit)
                releasePermit.await()
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { inPermit.await() }
        limiter.close()
        assertEquals(0, limiter.cachedEntryCountForTests())
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { limiter.withPermit(provider) { } }
        }
        assertTrue(error.message.orEmpty().contains("closed"))
        assertEquals(0, limiter.cachedEntryCountForTests())
        releasePermit.complete(Unit)
        holder.join()
        assertEquals(0, limiter.cachedEntryCountForTests())
    }

    @Test
    fun evictAndPermitRaceDoesNotResurrectEntry() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val inPermit = CompletableDeferred<Unit>()
        val releasePermit = CompletableDeferred<Unit>()
        val holder = launch {
            limiter.withPermit(provider) {
                inPermit.complete(Unit)
                releasePermit.await()
            }
        }
        withTimeout(TEST_TIMEOUT_MS) { inPermit.await() }
        limiter.evict(provider)
        assertEquals(0, limiter.cachedEntryCountForTests())
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { limiter.withPermit(provider) { } }
        }
        assertTrue(error.message.orEmpty().contains("evict"))
        assertEquals(0, limiter.cachedEntryCountForTests())
        releasePermit.complete(Unit)
        holder.join()
        assertEquals(0, limiter.cachedEntryCountForTests())
    }

    @Test
    fun cancellationReleasesPermit() = runBlocking {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(providerConcurrency = ProviderConcurrencyDeclaration.serialized())
        val release = CompletableDeferred<Unit>()
        val blocked = launch {
            limiter.withPermit(provider) {
                provider.enter()
                try {
                    release.await()
                } finally {
                    provider.leave()
                }
            }
        }
        provider.entered.await()
        blocked.cancelAndJoin()

        val nextEntered = CompletableDeferred<Unit>()
        withTimeout(TEST_TIMEOUT_MS) {
            limiter.withPermit(provider) {
                nextEntered.complete(Unit)
            }
        }
        assertTrue(nextEntered.isCompleted)
    }

    @Test
    fun directSessionFactoryFailsClosed() {
        val limiter = ProviderConcurrencyLimiter()
        val provider = ProbeMcpProvider(
            providerConcurrency = ProviderConcurrencyDeclaration.sessionFactory(1),
        )
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { limiter.withPermit(provider) { } }
        }
        assertTrue(error.message.orEmpty().contains("SESSION_FACTORY"))
    }
}

private class ProbeMcpProvider(
    override val serverId: String = "probe",
    override val providerConcurrency: ProviderConcurrencyDeclaration,
) : McpClientProvider {
    val active = AtomicInteger(0)
    val maxActive = AtomicInteger(0)
    val entered = CompletableDeferred<Unit>()

    override suspend fun listTools(): List<ToolDescriptor> = emptyList()

    override suspend fun callTool(
        toolName: String,
        argumentsJson: String,
        context: ToolCallContext,
    ): ToolCallResult = ToolCallResult(success = true, resultJson = "{}")

    fun enter() {
        val current = active.incrementAndGet()
        maxActive.updateAndGet { maxOf(it, current) }
        if (!entered.isCompleted) entered.complete(Unit)
    }

    fun leave() {
        active.decrementAndGet()
    }
}

private const val TEST_TIMEOUT_MS = 5_000L
