@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.skills

import cc.noharry.agent.*
import cc.noharry.agent.appserver.protocol.TokenUsageState
import cc.noharry.agent.runtime.SubagentSpec
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelRequest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSubagentUsageTest {
    @After fun clear() = AppSubagents.forget(null)

    @Test fun successfulAndFailedChildrenKeepTheLatestSnapshotOnce() = runBlocking {
        for (failed in listOf(false, true)) {
            val parent = UUID.randomUUID().toString()
            val last = Usage(inputTokens = 25, outputTokens = 4, reasoningTokens = 2, cacheReadTokens = 5)
            val terminal = if (failed) ThreadEvent.TurnFailed("run", TurnError("TEST_FAILED", "failed")) else
                ThreadEvent.TurnCompleted(TurnResult("run", TurnStatus.COMPLETED, "done", emptyList(), last))
            AppSubagents.rememberChildRuntime(parent) {
                runtime(flowOf(ThreadEvent.UsageUpdated("run", Usage(inputTokens = 10)), ThreadEvent.UsageUpdated("run", last), terminal))
            }
            AppSubagents.launch(spec(parent, "child")).collect()
            val result = AppSubagents.usage(parent)
            assertEquals(25, result.inputTokens)
            assertEquals(31, result.totalTokens)
            assertEquals(5, result.cachedInputTokens)
            assertEquals(2, result.requestCount)
        }
    }

    @Test fun cancellationKeepsUsageAlreadyObservedAndClosesTheRuntime() = runBlocking {
        val parent = UUID.randomUUID().toString()
        val observed = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        AppSubagents.rememberChildRuntime(parent) {
            runtime(flow {
                emit(ThreadEvent.UsageUpdated("run", Usage(inputTokens = 10)))
                emit(ThreadEvent.UsageUpdated("run", Usage(inputTokens = 25)))
                observed.complete(Unit)
                awaitCancellation()
            }, closed)
        }
        val child = launch(Dispatchers.Default) { AppSubagents.launch(spec(parent, "child")).collect() }
        withTimeout(TIMEOUT_MS) { observed.await() }
        child.cancelAndJoin()
        withTimeout(TIMEOUT_MS) { closed.await() }
        assertEquals(25, AppSubagents.usage(parent).inputTokens)
        assertEquals(2, AppSubagents.usage(parent).requestCount)
    }

    @Test fun concurrentChildrenAggregateOnlyIntoTheirOwnParent() = runBlocking {
        val first = UUID.randomUUID().toString()
        val second = UUID.randomUUID().toString()
        val ready = Channel<Unit>(3)
        val release = CompletableDeferred<Unit>()
        for (parent in listOf(first, second)) AppSubagents.rememberChildRuntime(parent) { child ->
            runtime(flow {
                emit(ThreadEvent.UsageUpdated("run", Usage(inputTokens = child.task.toInt())))
                ready.send(Unit)
                release.await()
            })
        }
        val children = listOf(spec(first, "a", "25"), spec(first, "b", "8"), spec(second, "c", "9")).map { child ->
            async(Dispatchers.Default) { AppSubagents.launch(child).collect() }
        }
        withTimeout(TIMEOUT_MS) { repeat(3) { ready.receive() } }
        release.complete(Unit)
        children.awaitAll()
        assertEquals(33, AppSubagents.usage(first).inputTokens)
        assertEquals(2, AppSubagents.usage(first).requestCount)
        assertEquals(9, AppSubagents.usage(second).inputTokens)
    }

    @Test fun lateFinalizationDoesNotRecreateOrChangeAForgottenParent() = runBlocking {
        val parent = UUID.randomUUID().toString()
        val observed = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        AppSubagents.rememberChildRuntime(parent) {
            runtime(flow {
                emit(ThreadEvent.UsageUpdated("run", Usage(inputTokens = 25)))
                observed.complete(Unit)
                release.await()
            })
        }
        val old = async(Dispatchers.Default) { AppSubagents.launch(spec(parent, "old")).collect() }
        withTimeout(TIMEOUT_MS) { observed.await() }
        AppSubagents.forget(parent)
        assertEquals(TokenUsageState(), AppSubagents.usage(parent))
        AppSubagents.rememberChildRuntime(parent) { runtime(flowOf(ThreadEvent.UsageUpdated("run", Usage(inputTokens = 9)))) }
        AppSubagents.launch(spec(parent, "new")).collect()
        release.complete(Unit)
        old.await()
        assertEquals(9, AppSubagents.usage(parent).inputTokens)
    }

    @Test fun terminalUsageFillsUnreportedTokensWithoutInventingAnotherUpdate() = runBlocking {
        for (initial in listOf(null, Usage(inputTokens = 8))) {
            val parent = UUID.randomUUID().toString()
            AppSubagents.rememberChildRuntime(parent) {
                runtime(flow {
                    initial?.let { emit(ThreadEvent.UsageUpdated("run", it)) }
                    emit(ThreadEvent.TurnCompleted(TurnResult("run", TurnStatus.COMPLETED, "done", emptyList(), Usage(inputTokens = 12))))
                })
            }
            AppSubagents.launch(spec(parent, "child")).collect()
            assertEquals(12, AppSubagents.usage(parent).inputTokens)
            assertEquals(1, AppSubagents.usage(parent).requestCount)
        }
    }

    @Test fun forgettingWhileAChildBuildsDoesNotAttachItToTheNewSession() = runBlocking {
        val parent = UUID.randomUUID().toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        AppSubagents.rememberChildRuntime(parent) {
            entered.countDown()
            check(release.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            runtime(flowOf(ThreadEvent.UsageUpdated("run", Usage(inputTokens = 25))))
        }
        val old = async(Dispatchers.Default) { AppSubagents.launch(spec(parent, "old")).collect() }
        try {
            assertTrue(entered.await(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            AppSubagents.forget(parent)
            AppSubagents.rememberChildRuntime(parent) { runtime(flowOf(ThreadEvent.UsageUpdated("run", Usage(inputTokens = 9)))) }
            AppSubagents.launch(spec(parent, "new")).collect()
        } finally { release.countDown() }
        old.await()
        assertEquals(9, AppSubagents.usage(parent).inputTokens)
    }

    private fun spec(parent: String, id: String, task: String = "task") =
        SubagentSpec(id, "$parent-$id", parent, "parent-run", id, task, 1, null, false)

    private fun runtime(events: Flow<ThreadEvent>, closed: CompletableDeferred<Unit>? = null): AgentRuntime {
        val delegate = AgentRuntime.builder().model(UnusedModel()).build()
        return object : AgentRuntime by delegate {
            override fun resumeThread(threadId: String, options: ThreadOptions): AgentThread {
                val thread = delegate.resumeThread(threadId, options)
                return object : AgentThread by thread {
                    override fun runStreamed(input: TurnInput, options: TurnOptions): Flow<ThreadEvent> = events
                    override fun runStreamed(text: String, options: TurnOptions): Flow<ThreadEvent> = events
                }
            }
            override fun close() { delegate.close(); closed?.complete(Unit) }
        }
    }
}

private class UnusedModel : ModelClient {
    override val providerConcurrency = ProviderConcurrencyDeclaration.serialized()
    override fun complete(request: ModelRequest): Nothing = error("The child test supplies its event stream.")
}

private const val TIMEOUT_MS = 5_000L
