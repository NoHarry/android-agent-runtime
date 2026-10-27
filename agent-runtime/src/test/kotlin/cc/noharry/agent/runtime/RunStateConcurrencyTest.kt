package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ConfirmationChoice
import cc.noharry.agent.kernel.api.ConfirmationDecision
import cc.noharry.agent.kernel.api.ConfirmationResumeResult
import cc.noharry.agent.kernel.api.ProtocolRequestCloseLoopResult
import cc.noharry.agent.kernel.api.reliability.RuntimeProtocolRequestStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.api.reliability.RuntimeWorkpad
import cc.noharry.agent.kernel.core.loop.NativeLoopSummary
import cc.noharry.agent.kernel.core.state.InMemoryConfirmationStore
import cc.noharry.agent.kernel.core.state.InMemoryProtocolRequestStore
import cc.noharry.agent.kernel.core.state.ReliabilityStorage
import cc.noharry.agent.kernel.core.state.RunStateController
import cc.noharry.agent.kernel.core.state.RunStateLockRegistry
import cc.noharry.agent.kernel.core.state.RuntimeStateRetentionPolicy
import cc.noharry.agent.runtime.reliability.FileBackedReliabilityStorage
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RunStateConcurrencyTest {
    @Test
    fun concurrentRunsKeepTerminalArtifactsConsistentAndEvidenceIdsUnique() = runBlocking {
        val clock = AtomicLong()
        val controller = RunStateController(nowEpochMillis = clock::incrementAndGet)

        coroutineScope {
            (0 until CONCURRENT_RUNS).map { index ->
                async(Dispatchers.Default) { completeRun(controller, "run-$index") }
            }.awaitAll()
        }

        (0 until CONCURRENT_RUNS).forEach { index ->
            val runId = "run-$index"
            assertTerminalState(controller, runId)
            val evidenceIds = controller.traceEvents(runId).mapNotNull { it.evidenceId }
            assertEquals(evidenceIds.size, evidenceIds.distinct().size)
        }
    }

    @Test
    fun sameRunWorkpadUpdatesDoNotLoseConcurrentSummaries() = runBlocking {
        val clock = AtomicLong()
        val storage = FileBackedReliabilityStorage(
            Files.createTempDirectory("runtime-concurrent-workpad-").toFile(),
        )
        val controller = RunStateController(
            nowEpochMillis = clock::incrementAndGet,
            storage = storage,
        )
        controller.startRun(request("shared-run"), "shared-run")

        coroutineScope {
            (0 until CONCURRENT_SUMMARIES).map { index ->
                async(Dispatchers.Default) {
                    controller.recordNativeLoopSummary(
                        "shared-run",
                        NativeLoopSummary(attemptedTools = listOf("tool-$index")),
                    )
                }
            }.awaitAll()
        }

        val attemptedTools = requireNotNull(controller.workpad("shared-run")).attemptedTools
        val restoredTools = requireNotNull(
            RunStateController(clock::incrementAndGet, storage = storage).workpad("shared-run"),
        ).attemptedTools
        assertEquals(CONCURRENT_SUMMARIES, attemptedTools.size)
        assertEquals(CONCURRENT_SUMMARIES, attemptedTools.toSet().size)
        assertEquals(attemptedTools.toSet(), restoredTools.toSet())
    }

    @Test
    fun cacheRetentionKeepsWaitingRunsAndReloadsEvictedTerminalRuns() {
        val storageRoot = Files.createTempDirectory("runtime-state-retention-").toFile()
        val storage = FileBackedReliabilityStorage(storageRoot)
        val clock = AtomicLong()
        val controller = RunStateController(
            nowEpochMillis = clock::incrementAndGet,
            storage = storage,
            retentionPolicy = RuntimeStateRetentionPolicy(
                maxTerminalRuns = 1,
                maxTraceEntriesPerRun = 8,
                maxEvidenceEntriesPerRun = 4,
            ),
        )

        controller.startRun(request("waiting"), "waiting")
        completeRun(controller, "old-terminal")
        completeRun(controller, "new-terminal")

        assertEquals(1, controller.cachedTerminalRunCount())
        assertEquals(RuntimeRunStatus.RUNNING, controller.snapshot("waiting")?.status)
        assertEquals(RuntimeRunStatus.COMPLETED, controller.snapshot("old-terminal")?.status)
        assertEquals("done-old-terminal", controller.result("old-terminal")?.response)
        assertTrue(controller.cachedTraceCount("new-terminal") <= 8)
        assertTrue(controller.cachedEvidenceCount("new-terminal") <= 4)
    }

    @Test
    fun idleRunLocksAreReleasedAfterEachCriticalSection() {
        val locks = RunStateLockRegistry()

        repeat(CONCURRENT_RUNS) { index ->
            locks.withRunLock("lock-$index") { Unit }
        }

        assertEquals(0, locks.cachedLockCount())
    }

    @Test
    fun lateTerminalResultDoesNotRepopulateAnEvictedRunCache() {
        val controller = RunStateController(
            nowEpochMillis = AtomicLong()::incrementAndGet,
            retentionPolicy = RuntimeStateRetentionPolicy(maxTerminalRuns = 1),
        )
        completeRun(controller, "old-terminal")
        completeRun(controller, "new-terminal")

        controller.applyRuntimeResult(
            RuntimeResult(
                runId = "old-terminal",
                status = RuntimeRunStatus.COMPLETED,
                response = "late-old-terminal",
            ),
        )

        assertEquals(1, controller.cachedTerminalRunCount())
        assertEquals(1, controller.cachedResultCount())
        assertEquals(1, controller.cachedWorkpadCount())
        assertNull(controller.result("old-terminal"))
    }

    @Test
    fun lateTerminalResultDoesNotRewriteEvictedPersistentRun() {
        val storage = FileBackedReliabilityStorage(
            Files.createTempDirectory("runtime-late-terminal-").toFile(),
        )
        val clock = AtomicLong()
        val controller = RunStateController(
            nowEpochMillis = clock::incrementAndGet,
            storage = storage,
            retentionPolicy = RuntimeStateRetentionPolicy(maxTerminalRuns = 1),
        )
        completeRun(controller, "old-terminal")
        completeRun(controller, "new-terminal")
        val original = requireNotNull(controller.result("old-terminal"))
        val originalTraceCount = controller.traceEvents("old-terminal").size

        val recorded = controller.applyRuntimeResult(
            RuntimeResult(
                runId = "old-terminal",
                status = RuntimeRunStatus.FAILED,
                response = "late-old-terminal",
            ),
        )
        controller.markProtocolRequestResolved("old-terminal")

        val restored = RunStateController(clock::incrementAndGet, storage = storage)
        assertEquals(original, recorded)
        assertEquals(RuntimeRunStatus.COMPLETED, restored.snapshot("old-terminal")?.status)
        assertEquals(original, restored.result("old-terminal"))
        assertEquals(originalTraceCount, restored.traceEvents("old-terminal").size)
        assertEquals(1, controller.cachedTerminalRunCount())
        assertEquals(1, controller.cachedResultCount())
        assertEquals(1, controller.cachedWorkpadCount())
        assertEquals(0, controller.cachedTraceCount("old-terminal"))
        assertTrue(controller.cachedTraceCount("new-terminal") > 0)
    }

    @Test
    fun duplicateTerminalResultRepairsPartialStorageFailure() {
        val delegate = FileBackedReliabilityStorage(
            Files.createTempDirectory("runtime-terminal-retry-").toFile(),
        )
        val storage = FailOnceTerminalWorkpadStorage(delegate)
        val clock = AtomicLong()
        val controller = RunStateController(clock::incrementAndGet, storage = storage)
        controller.startRun(request("retry-terminal"), "retry-terminal")
        storage.failNextTerminalWorkpad = true

        assertThrows(IOException::class.java) {
            controller.applyRuntimeResult(
                RuntimeResult("retry-terminal", RuntimeRunStatus.COMPLETED, "first-result"),
            )
        }
        val retried = controller.applyRuntimeResult(
            RuntimeResult("retry-terminal", RuntimeRunStatus.FAILED, "late-result"),
        )

        val restored = RunStateController(clock::incrementAndGet, storage = storage)
        assertEquals(RuntimeRunStatus.COMPLETED, retried.status)
        assertEquals("first-result", retried.response)
        assertEquals(retried, restored.result("retry-terminal"))
        assertTrue(
            restored.workpad("retry-terminal")
                ?.completionBar
                ?.single { it.id == "terminal-result" }
                ?.done == true,
        )
    }

    @Test
    fun confirmationAndProtocolTransitionsAreSingleWinnerUnderConcurrency() = runBlocking {
        val clock = AtomicLong()
        val confirmationStore = InMemoryConfirmationStore(clock::incrementAndGet)
        val pending = confirmationStore.create("run-1", "session-1", Long.MAX_VALUE / 2)
        val confirmationResults = coroutineScope {
            (0 until CONCURRENT_TRANSITIONS).map { index ->
                async(Dispatchers.Default) {
                    confirmationStore.resume(
                        ConfirmationDecision(pending.pendingId, "request-$index", ConfirmationChoice.APPROVE),
                    ).apiResult
                }
            }.awaitAll()
        }
        assertEquals(1, confirmationResults.count { it == ConfirmationResumeResult.Accepted })

        val protocolStore = InMemoryProtocolRequestStore(clock::incrementAndGet)
        val protocol = protocolStore.create("run-2", "session-1", "test", "{}", null)
        val protocolResults = coroutineScope {
            (0 until CONCURRENT_TRANSITIONS).map { index ->
                async(Dispatchers.Default) {
                    protocolStore.transition(
                        protocol.protocolRequestId,
                        "resolution-$index",
                        RuntimeProtocolRequestStatus.RESOLVED,
                    )
                }
            }.awaitAll()
        }
        assertEquals(1, protocolResults.count { it is ProtocolRequestCloseLoopResult.Updated })
    }

    @Test
    fun protocolTerminalCacheIsBoundedWithoutBreakingRecentIdempotency() {
        val clock = AtomicLong()
        val store = InMemoryProtocolRequestStore(
            nowEpochMillis = clock::incrementAndGet,
            retentionPolicy = RuntimeStateRetentionPolicy(maxTerminalRuns = 2),
        )

        repeat(3) { index ->
            val runId = "protocol-run-$index"
            val request = store.create(runId, "session-1", "test", "{}", null)
            store.transition(request.protocolRequestId, "resolution-$index", RuntimeProtocolRequestStatus.RESOLVED)
            store.retainTerminalRun(runId)
        }

        assertEquals(2, store.cachedRequestCount())
        val recent = store.transition(
            "protocol-run-2:protocol-request",
            "resolution-2",
            RuntimeProtocolRequestStatus.RESOLVED,
        )
        val evicted = store.transition(
            "protocol-run-0:protocol-request",
            "resolution-0",
            RuntimeProtocolRequestStatus.RESOLVED,
        )
        assertTrue(recent is ProtocolRequestCloseLoopResult.AlreadyApplied)
        assertEquals(ProtocolRequestCloseLoopResult.NotFound, evicted)
    }

    private fun completeRun(controller: RunStateController, runId: String) {
        controller.startRun(request(runId), runId)
        repeat(SUMMARIES_PER_RUN) { index ->
            controller.recordNativeLoopSummary(
                runId,
                NativeLoopSummary(attemptedTools = listOf("$runId-tool-$index")),
            )
        }
        controller.applyRuntimeResult(
            RuntimeResult(
                runId = runId,
                status = RuntimeRunStatus.COMPLETED,
                response = "done-$runId",
            ),
        )
    }

    private fun assertTerminalState(controller: RunStateController, runId: String) {
        val snapshot = controller.snapshot(runId)
        val envelope = controller.envelope(runId)
        val result = controller.result(runId)
        val workpad = controller.workpad(runId)
        assertEquals(RuntimeRunStatus.COMPLETED, snapshot?.status)
        assertEquals(RuntimeRunStatus.COMPLETED, envelope?.status)
        assertEquals(RuntimeRunPhase.TERMINAL, envelope?.phase)
        assertEquals(RuntimeRunStatus.COMPLETED, result?.status)
        assertTrue(workpad?.completionBar?.single { it.id == "terminal-result" }?.done == true)
        assertNotNull(result?.evidenceIds?.singleOrNull())
    }

    private fun request(runId: String): AgentRequest = AgentRequest(
        requestId = runId,
        sessionId = "session-$runId",
        input = "run",
    )
}

private const val CONCURRENT_RUNS = 32
private const val CONCURRENT_SUMMARIES = 64
private const val CONCURRENT_TRANSITIONS = 32
private const val SUMMARIES_PER_RUN = 4

private class FailOnceTerminalWorkpadStorage(
    private val delegate: ReliabilityStorage,
) : ReliabilityStorage by delegate {
    var failNextTerminalWorkpad: Boolean = false

    override fun saveWorkpad(workpad: RuntimeWorkpad) {
        val terminalCompleted = workpad.completionBar
            .any { it.id == "terminal-result" && it.done }
        if (failNextTerminalWorkpad && terminalCompleted) {
            failNextTerminalWorkpad = false
            throw IOException("Injected terminal workpad write failure.")
        }
        delegate.saveWorkpad(workpad)
    }
}
