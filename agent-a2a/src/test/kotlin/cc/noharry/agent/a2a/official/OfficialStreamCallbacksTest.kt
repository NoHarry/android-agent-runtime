package cc.noharry.agent.a2a.official

import cc.noharry.agent.a2a.A2aClientError
import cc.noharry.agent.a2a.A2aMessage
import cc.noharry.agent.a2a.A2aPart
import cc.noharry.agent.a2a.A2aStreamEvent
import cc.noharry.agent.a2a.ExperimentalA2aClientApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalA2aClientApi::class)
class OfficialStreamCallbacksTest {
    @Test
    fun terminalPayloadCompletesOnceAndIgnoresLateCallbacks() {
        val output = mutableListOf<A2aStreamEvent>()
        val callbacks = callbacks(output)

        callbacks.onEvent(message("done"), terminal = true)
        callbacks.onComplete()
        callbacks.onError(IllegalStateException("late"))

        assertEquals(listOf(message("done"), A2aStreamEvent.Completed), output)
    }

    @Test
    fun normalCompletionWithoutTerminalPayloadIsIncomplete() {
        val output = mutableListOf<A2aStreamEvent>()
        val callbacks = callbacks(output)

        callbacks.onEvent(message("working"), terminal = false)
        callbacks.onComplete()

        assertEquals(
            listOf(message("working"), A2aStreamEvent.Incomplete(A2aClientError.Incomplete())),
            output,
        )
    }

    @Test
    fun duplicatePayloadFailsAndSuppressesLaterTerminalPayload() {
        val output = mutableListOf<A2aStreamEvent>()
        val callbacks = callbacks(output)
        val payload = message("same")

        callbacks.onEvent(payload, terminal = false)
        callbacks.onEvent(payload, terminal = false)
        callbacks.onEvent(message("late"), terminal = true)

        assertEquals(
            listOf(
                payload,
                A2aStreamEvent.Failed(A2aClientError.Protocol("duplicate_stream_event")),
            ),
            output,
        )
    }

    @Test
    fun cancellationSuppressesAllLateCallbacks() {
        val output = mutableListOf<A2aStreamEvent>()
        val callbacks = callbacks(output)

        callbacks.cancel()
        callbacks.onEvent(message("late"), terminal = true)
        callbacks.onComplete()
        callbacks.onError(IllegalStateException("late"))

        assertTrue(output.isEmpty())
    }

    @Test
    fun concurrentTerminalCallbacksProduceOneTerminalSequence() {
        val output = mutableListOf<A2aStreamEvent>()
        val callbacks = callbacks(output)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val terminal = executor.submit {
                ready.countDown()
                start.await()
                callbacks.onEvent(message("done"), terminal = true)
            }
            val completion = executor.submit {
                ready.countDown()
                start.await()
                callbacks.onComplete()
            }
            ready.await()
            start.countDown()
            terminal.get()
            completion.get()
        } finally {
            executor.shutdownNow()
        }

        val validOutputs = setOf(
            listOf(message("done"), A2aStreamEvent.Completed),
            listOf(A2aStreamEvent.Incomplete(A2aClientError.Incomplete())),
        )
        assertTrue(output in validOutputs)
    }

    @Test
    fun resourceCreatedAfterCancellationIsReleasedExactlyOnce() {
        val released = mutableListOf<String>()
        val lease = OfficialStreamResourceLease<String>(released::add)

        lease.cancel()

        assertFalse(lease.attach("client"))
        lease.cancel()
        assertEquals(listOf("client"), released)
    }

    @Test
    fun attachedResourceIsReleasedExactlyOnceAndCannotRunAfterCancellation() {
        val released = mutableListOf<String>()
        val lease = OfficialStreamResourceLease<String>(released::add)
        var starts = 0

        assertTrue(lease.attach("client"))
        assertTrue(lease.runIfActive { starts += 1 })
        lease.cancel()
        lease.cancel()

        assertFalse(lease.runIfActive { starts += 1 })
        assertEquals(1, starts)
        assertEquals(listOf("client"), released)
    }

    @Test
    fun cancellationDefersReleaseUntilBlockedStartReturnsWithoutWaiting() {
        val startEntered = CountDownLatch(1)
        val allowStartReturn = CountDownLatch(1)
        val releaseCount = AtomicInteger()
        val lease = OfficialStreamResourceLease<String> { releaseCount.incrementAndGet() }
        val executor = Executors.newFixedThreadPool(2)
        try {
            assertTrue(lease.attach("client"))
            val start = executor.submit<Boolean> {
                lease.runIfActive {
                    startEntered.countDown()
                    allowStartReturn.await()
                }
            }
            assertTrue(startEntered.await(1, TimeUnit.SECONDS))

            val cancellation = executor.submit { lease.cancel() }

            cancellation.get(1, TimeUnit.SECONDS)
            assertEquals(0, releaseCount.get())
            allowStartReturn.countDown()
            assertTrue(start.get(1, TimeUnit.SECONDS))
            lease.cancel()
            assertEquals(1, releaseCount.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun bridgeCloseAfterRegisterBeforeLeaseStartPreventsStartAndReleasesOnce() {
        val releaseCount = AtomicInteger()
        val registry = OfficialResourceRegistry<String> { releaseCount.incrementAndGet() }
        val lease = OfficialStreamResourceLease<String>(registry::release)
        var starts = 0

        assertTrue(registry.register("client"))
        assertTrue(lease.attach("client"))
        registry.close()

        assertTrue(lease.runIfActive { client ->
            assertFalse(registry.runIfActive(client) { starts += 1 })
        })
        lease.cancel()
        registry.close()

        assertEquals(0, starts)
        assertEquals(1, releaseCount.get())
    }

    @Test
    fun bridgeCloseDefersReleaseUntilBlockedStartReturnsWithoutWaiting() {
        val startEntered = CountDownLatch(1)
        val allowStartReturn = CountDownLatch(1)
        val releaseCount = AtomicInteger()
        val registry = OfficialResourceRegistry<String> { releaseCount.incrementAndGet() }
        val lease = OfficialStreamResourceLease<String>(registry::release)
        val executor = Executors.newFixedThreadPool(2)
        try {
            assertTrue(registry.register("client"))
            assertTrue(lease.attach("client"))
            val start = executor.submit<Boolean> {
                lease.runIfActive { client ->
                    assertTrue(registry.runIfActive(client) {
                        startEntered.countDown()
                        allowStartReturn.await()
                    })
                }
            }
            assertTrue(startEntered.await(1, TimeUnit.SECONDS))

            val close = executor.submit { registry.close() }

            close.get(1, TimeUnit.SECONDS)
            assertEquals(0, releaseCount.get())
            allowStartReturn.countDown()
            assertTrue(start.get(1, TimeUnit.SECONDS))
            lease.cancel()
            assertEquals(1, releaseCount.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun callbacks(output: MutableList<A2aStreamEvent>) = OfficialStreamCallbacks(
        emitEvent = { output += it; true },
        emitTerminal = { output += it },
        mapFailure = { A2aClientError.Transport("mapped_failure") },
    )

    private fun message(text: String): A2aStreamEvent.Message = A2aStreamEvent.Message(
        A2aMessage("message-$text", A2aMessage.Role.AGENT, listOf(A2aPart.Text(text))),
    )
}
