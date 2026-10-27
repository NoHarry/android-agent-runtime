@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.ProtocolRequestResolution
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.api.SessionRunConflictPolicy
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.loop.failed
import cc.noharry.agent.runtime.eventlog.InMemorySessionEventLog
import cc.noharry.agent.runtime.eventlog.scalarFields
import cc.noharry.agent.vfs.VfsFileSystem
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSchedulingIntegrationTest {
    @Test
    fun sameSessionFifoAndObservableQueuedMaxOneOwner() = runSchedulingTest {
        val gates = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val runtime = runtime(GatedFinishModel(gates, entered), maxActive = 2)
        try {
            val first = launch { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            entered[0].await()
            assertEquals(RuntimeRunStatus.RUNNING, runtime.getRunState(RUN_A1)?.status)

            val secondStarted = CompletableDeferred<Boolean>()
            val second = launch {
                val events = runtime.run(request(RUN_A2, SESSION_A)).toList()
                secondStarted.complete(events.any { it is AgentEvent.Started })
            }
            awaitQueued(runtime, RUN_A2)
            assertFalse(entered[1].isCompleted)
            val snap = runtime.sessionSchedulerSnapshotForTest()
            assertEquals(1, snap.owners.size)
            assertEquals(RUN_A1, snap.owners[SESSION_A])
            assertEquals(1, snap.queuedCount)

            gates[0].complete(Unit)
            first.join()
            entered[1].await()
            assertEquals(RuntimeRunStatus.RUNNING, runtime.getRunState(RUN_A2)?.status)
            assertEquals(RUN_A2, runtime.sessionSchedulerSnapshotForTest().owners[SESSION_A])
            gates[1].complete(Unit)
            second.join()
            assertTrue(secondStarted.await())
            assertIdle(runtime)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun differentSessionsOverlapUpToCap() = runSchedulingTest {
        val gates = mapOf(
            RUN_A1 to CompletableDeferred<Unit>(),
            RUN_B1 to CompletableDeferred<Unit>(),
            RUN_C1 to CompletableDeferred<Unit>(),
        )
        val entered = mapOf(
            RUN_A1 to CompletableDeferred<Unit>(),
            RUN_B1 to CompletableDeferred<Unit>(),
            RUN_C1 to CompletableDeferred<Unit>(),
        )
        val runtime = runtime(KeyedGateFinishModel(gates, entered), maxActive = 2)
        try {
            val a = launch(Dispatchers.Default) { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            entered.getValue(RUN_A1).await()
            awaitState(runtime, RUN_A1, RuntimeRunStatus.RUNNING)
            val b = launch(Dispatchers.Default) { runtime.run(request(RUN_B1, SESSION_B)).toList() }
            entered.getValue(RUN_B1).await()
            awaitState(runtime, RUN_B1, RuntimeRunStatus.RUNNING)
            val overlapping = runtime.sessionSchedulerSnapshotForTest()
            assertEquals(2, overlapping.activeExecutionCount)
            assertEquals(setOf(SESSION_A, SESSION_B), overlapping.owners.keys)

            val c = launch(Dispatchers.Default) { runtime.run(request(RUN_C1, SESSION_C)).toList() }
            awaitQueued(runtime, RUN_C1, minQueued = 1)
            assertFalse(entered.getValue(RUN_C1).isCompleted)
            assertEquals(2, runtime.sessionSchedulerSnapshotForTest().activeExecutionCount)

            gates.getValue(RUN_A1).complete(Unit)
            a.join()
            entered.getValue(RUN_C1).await()
            assertEquals(setOf(SESSION_B, SESSION_C), runtime.sessionSchedulerSnapshotForTest().owners.keys)
            gates.getValue(RUN_B1).complete(Unit)
            gates.getValue(RUN_C1).complete(Unit)
            b.join()
            c.join()
            assertIdle(runtime)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun fairnessDoesNotStarveOtherSessions() = runSchedulingTest {
        val startOrder = CopyOnWriteArrayList<String>()
        val firstHold = CompletableDeferred<Unit>()
        val firstEntered = CompletableDeferred<Unit>()
        val runtime = runtime(FirstHoldFinishModel(firstHold, firstEntered, startOrder), maxActive = 1)
        try {
            val a1 = launch(Dispatchers.Default) { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            firstEntered.await()
            val a2 = launch(Dispatchers.Default) { runtime.run(request(RUN_A2, SESSION_A)).toList() }
            awaitQueued(runtime, RUN_A2, minQueued = 1)
            val b1 = launch(Dispatchers.Default) { runtime.run(request(RUN_B1, SESSION_B)).toList() }
            awaitQueued(runtime, RUN_B1, minQueued = 2)
            val c1 = launch(Dispatchers.Default) { runtime.run(request(RUN_C1, SESSION_C)).toList() }
            awaitQueued(runtime, RUN_C1, minQueued = 3)
            assertEquals(
                listOf(SESSION_B, SESSION_C),
                runtime.sessionSchedulerSnapshotForTest().readySessionIds,
            )

            firstHold.complete(Unit)
            a1.join()
            b1.join()
            c1.join()
            a2.join()
            assertEquals(listOf(RUN_A1, RUN_B1, RUN_C1, RUN_A2), startOrder.toList())
            assertIdle(runtime)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun missingResumeQueueAndCancelReplacePolicies() = runSchedulingTest {
        val runtime = confirmationRuntime()
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()

            val missing = runtime.run(request(RUN_A2, SESSION_A)).toList()
            val missingBlocker = missing.filterIsInstance<AgentEvent.RuntimeBlocked>().single().blocker
            assertEquals(RuntimeErrorCode.ADMISSION_POLICY_REQUIRED.wireCode, missingBlocker.code)
            assertEquals(RUN_A1, missingBlocker.runId)
            assertNull(runtime.getRunState(RUN_A2))
            assertTrue(missing.none { it is AgentEvent.Started })

            val resume = runtime.run(
                request(RUN_A3, SESSION_A, SessionRunConflictPolicy.RESUME_CURRENT),
            ).toList()
            val resumeBlocker = resume.filterIsInstance<AgentEvent.RuntimeBlocked>().single().blocker
            assertEquals(RuntimeErrorCode.PENDING_CONFIRMATION_EXISTS.wireCode, resumeBlocker.code)
            assertNull(runtime.getRunState(RUN_A3))

            val queued = launch {
                runtime.run(request(RUN_A4, SESSION_A, SessionRunConflictPolicy.QUEUE_NEW)).toList()
            }
            awaitQueued(runtime, RUN_A4)
            queued.cancelAndJoin()
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A4)?.status)

            val replaced = runtime.run(
                request(RUN_A5, SESSION_A, SessionRunConflictPolicy.CANCEL_AND_REPLACE),
            ).toList()
            assertTrue(replaced.any { it is AgentEvent.Started })
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(RuntimeErrorCode.RUN_REPLACED.wireCode, runtime.getRuntimeResult(RUN_A1)?.errorCode)
            assertEquals(RuntimeRunStatus.WAITING_CONFIRMATION, runtime.getRunState(RUN_A5)?.status)
            assertIdleOwnersExcept(runtime, SESSION_A to RUN_A5)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun queueAndGlobalBackpressureMapToFailedUnknown() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime(
            model = SingleGateFinishModel(gate, entered),
            maxActive = 1,
            maxQueuedPerSession = 1,
            maxQueuedGlobal = 1,
        )
        try {
            val a1 = launch { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            entered.await()
            val a2 = launch { runtime.run(request(RUN_A2, SESSION_A)).toList() }
            awaitQueued(runtime, RUN_A2)

            val sessionFull = runtime.run(request(RUN_A3, SESSION_A)).toList()
            val sessionResult = sessionFull.filterIsInstance<AgentEvent.ResultAvailable>().single().result
            assertEquals(RuntimeRunStatus.FAILED, sessionResult.status)
            assertEquals(RuntimeErrorCode.SESSION_QUEUE_FULL.wireCode, sessionResult.errorCode)
            assertEquals(RuntimeCompletionOutcome.UNKNOWN, sessionResult.completion.outcome)
            assertTrue(sessionFull.none { it is AgentEvent.Started })

            val busy = runtime.run(request(RUN_B1, SESSION_B)).toList()
            val busyResult = busy.filterIsInstance<AgentEvent.ResultAvailable>().single().result
            assertEquals(RuntimeRunStatus.FAILED, busyResult.status)
            assertEquals(RuntimeErrorCode.RUNTIME_BUSY.wireCode, busyResult.errorCode)
            assertEquals(RuntimeCompletionOutcome.UNKNOWN, busyResult.completion.outcome)

            gate.complete(Unit)
            a1.join()
            a2.join()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun queuedCancellationDoesNotStartOrLeakOwner() = runSchedulingTest {
        val gate = CompletableDeferred<Unit>()
        val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val runtime = runtime(
            GatedFinishModel(listOf(gate, CompletableDeferred()), entered),
            maxActive = 1,
        )
        try {
            val first = launch { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            entered[0].await()
            val queued = launch { runtime.run(request(RUN_A2, SESSION_A)).toList() }
            awaitQueued(runtime, RUN_A2)
            queued.cancelAndJoin()
            assertEquals(RuntimeRunStatus.CANCELLED, runtime.getRunState(RUN_A2)?.status)
            assertFalse(entered[1].isCompleted)
            assertEquals(RUN_A1, runtime.sessionSchedulerSnapshotForTest().owners[SESSION_A])
            gate.complete(Unit)
            first.join()
            assertFalse(entered[1].isCompleted)
            assertIdle(runtime)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun protocolResolvedDoesNotLeaveOrphanRunning() = runSchedulingTest {
        val runtime = AgentRuntimeBuilder.create()
            .protocolRequestOnRun(kind = "host_control")
            .sessionConcurrency(maxActiveSessions = 1)
            .build() as DefaultAgentRuntime
        try {
            runtime.run(request(RUN_A1, SESSION_A)).toList()
            val pending = runtime.getPendingProtocolRequests().single()
            runtime.resolveRuntimeProtocolRequest(
                ProtocolRequestResolution(pending.protocolRequestId, "resolution-1"),
            )
            val result = runtime.getRuntimeResult(RUN_A1)
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(RuntimeRunStatus.COMPLETED, result?.status)
            assertEquals(RuntimeCompletionOutcome.UNKNOWN, result?.completion?.outcome)
            assertFalse(RuntimeCompletionSummary.firstPartyPass(result!!.status, result.completion))
            assertIdle(runtime)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun queuedRecorderEmitsQueuedThenRunningThenTerminal() = runSchedulingTest {
        val log = InMemorySessionEventLog()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime(SingleGateFinishModel(gate, entered), maxActive = 1, eventLog = log)
        try {
            val first = launch { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            entered.await()
            val second = launch { runtime.run(request(RUN_A2, SESSION_A)).toList() }
            awaitQueued(runtime, RUN_A2)
            val parked = log.replay(SESSION_A, RUN_A2)
            assertTrue(parked.any { it.type == SessionEventType.RUN_STARTED })
            assertEquals(
                listOf(RuntimeRunStatus.QUEUED.name),
                parked.filter { it.type == SessionEventType.RUN_STATUS }
                    .map { it.payload.scalarFields()["status"] },
            )
            assertFalse(runtime.workspaceBoundForTest(RUN_A2))

            gate.complete(Unit)
            first.join()
            second.join()
            val finished = log.replay(SESSION_A, RUN_A2)
            assertEquals(
                listOf(RuntimeRunStatus.QUEUED.name, RuntimeRunStatus.RUNNING.name, RuntimeRunStatus.COMPLETED.name),
                finished.filter { it.type == SessionEventType.RUN_STATUS }
                    .map { it.payload.scalarFields()["status"] },
            )
            assertTrue(finished.any { it.type == SessionEventType.RUN_FINISHED })
        } finally {
            runtime.close()
        }
    }

    @Test
    fun duplicateRunIdDoesNotMutateExistingActiveRun() = runSchedulingTest {
        val observer = CountingTerminalObserver()
        val log = InMemorySessionEventLog()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runtime = runtime(
            SingleGateFinishModel(gate, entered),
            maxActive = 1,
            observer = observer,
            eventLog = log,
        )
        try {
            val first = launch { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            entered.await()
            val duplicate = runtime.run(request(RUN_A1, SESSION_A)).toList()
            val failed = duplicate.filterIsInstance<AgentEvent.Failed>().single()
            assertEquals(RuntimeErrorCode.DUPLICATE_RUN_ID.wireCode, failed.code)
            assertEquals(RuntimeRunStatus.RUNNING, runtime.getRunState(RUN_A1)?.status)
            assertNull(observer.counts[RUN_A1])
            assertTrue(log.replay(SESSION_A, RUN_A1).none { it.type == SessionEventType.RUN_FINISHED })

            gate.complete(Unit)
            first.join()
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A1)?.status)
            assertEquals(1, observer.counts[RUN_A1])
        } finally {
            runtime.close()
        }
    }

    @Test
    fun queuedRunDoesNotSelectWorkspaceUntilGrant() = runSchedulingTest {
        val workspace = Files.createTempDirectory("runtime-queued-workspace-").toFile()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val runtime = AgentRuntimeBuilder.create()
            .workspace(RuntimeWorkspaceRef("alpha", VfsFileSystem.singleFileRoot(workspace)))
            .defaultWorkspace("alpha")
            .modelClient(SingleGateFinishModel(gate, entered))
            .sessionConcurrency(maxActiveSessions = 1)
            .build() as DefaultAgentRuntime
        try {
            val first = launch { runtime.run(request(RUN_A1, SESSION_A)).toList() }
            entered.await()
            val second = launch { runtime.run(request(RUN_A2, SESSION_A)).toList() }
            awaitQueued(runtime, RUN_A2)
            assertTrue(runtime.workspaceBoundForTest(RUN_A1))
            assertFalse(runtime.workspaceBoundForTest(RUN_A2))
            gate.complete(Unit)
            first.join()
            second.join()
        } finally {
            runtime.close()
        }
    }

    @Test
    fun hostCollectorThrowOnStartedPersistsFailedUnknownOnceAndAllowsNextRun() = runSchedulingTest {
        val observer = CountingTerminalObserver()
        val log = InMemorySessionEventLog()
        val probeRoot = Files.createTempDirectory("runtime-collector-throw-").toFile()
        val boom = IllegalStateException("host collector boom")
        val runtime = collectorThrowRuntime(observer, log, probeRoot)
        try {
            val hostEvents = CopyOnWriteArrayList<AgentEvent>()
            val failure = collectUntilStartedThrow(runtime, boom, hostEvents)
            assertHostCollectorThrowPersistedOnce(failure, boom, hostEvents, runtime, observer, log, probeRoot)
            assertNextRunAllowedAfterCollectorThrow(runtime, observer, log, probeRoot)
        } finally {
            runtime.close()
        }
    }

    @Test
    fun unexpectedThrowBeforeRunCreationDoesNotCreateState() = runSchedulingTest {
        val observer = CountingTerminalObserver()
        val log = InMemorySessionEventLog()
        val runtime = runtime(finishModel(), observer = observer, eventLog = log)
        runtime.beforePersistQueuedForTest = { error("pre-create boom") }
        try {
            val failure = runCatching { runtime.run(request(RUN_A1, SESSION_A)).toList() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals("pre-create boom", failure?.message)
            assertNull(runtime.getRunState(RUN_A1))
            assertNull(runtime.getRuntimeResult(RUN_A1))
            assertNull(observer.counts[RUN_A1])
            assertTrue(log.replay(SESSION_A, RUN_A1).isEmpty())
            assertIdle(runtime)
        } finally {
            runtime.beforePersistQueuedForTest = null
            runtime.close()
        }
    }

    @Test
    fun cleanupThrowsStillReleasesExactLease() = runSchedulingTest {
        val runtime = AgentRuntimeBuilder.create()
            .modelClient(finishModel())
            .sessionConcurrency(maxActiveSessions = 1)
            .build() as DefaultAgentRuntime
        runtime.beforeCleanupForTest = { error("cleanup boom") }
        try {
            val failure = runCatching { runtime.run(request(RUN_A1, SESSION_A)).toList() }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertIdle(runtime)
        } finally {
            runtime.beforeCleanupForTest = null
            runtime.close()
        }
    }
}

private fun collectorThrowRuntime(
    observer: CountingTerminalObserver,
    log: InMemorySessionEventLog,
    probeRoot: java.io.File,
): DefaultAgentRuntime =
    AgentRuntimeBuilder.create()
        .modelClient(finishModel())
        .sessionConcurrency(maxActiveSessions = 1)
        .turnObserver(observer)
        .sessionEventLog(log)
        .runtimeEvidence(
            RuntimeEvidenceConfig(
                storageRoot = probeRoot,
                enabledProbePackIds = setOf("bench_core"),
                casePackId = "none",
                promptSha256 = null,
            ),
        )
        .build() as DefaultAgentRuntime

private suspend fun collectUntilStartedThrow(
    runtime: DefaultAgentRuntime,
    boom: IllegalStateException,
    hostEvents: MutableList<AgentEvent>,
): Throwable? =
    runCatching {
        runtime.run(request(RUN_A1, SESSION_A)).collect { event ->
            hostEvents += event
            if (event is AgentEvent.Started) throw boom
        }
    }.exceptionOrNull()

private suspend fun assertHostCollectorThrowPersistedOnce(
    failure: Throwable?,
    boom: IllegalStateException,
    hostEvents: List<AgentEvent>,
    runtime: DefaultAgentRuntime,
    observer: CountingTerminalObserver,
    log: InMemorySessionEventLog,
    probeRoot: java.io.File,
) {
    val original = generateSequence(failure) { it.cause }.firstOrNull { it === boom }
    assertTrue(
        "original collector exception must propagate, got ${failure?.javaClass?.name}: ${failure?.message}",
        failure === boom || original === boom,
    )
    assertFalse(failure is CancellationException)
    assertTrue(
        hostEvents.none {
            it is AgentEvent.ResultAvailable || it is AgentEvent.Failed || it is AgentEvent.Finished
        },
    )
    assertEquals(RuntimeRunStatus.FAILED, runtime.getRunState(RUN_A1)?.status)
    val first = runtime.getRuntimeResult(RUN_A1)
    assertEquals(RuntimeErrorCode.MODEL_LOOP_FAILED.wireCode, first?.errorCode)
    assertEquals(RuntimeCompletionOutcome.UNKNOWN, first?.completion?.outcome)
    assertEquals("Run failed unexpectedly.", first?.response)
    assertIdle(runtime)
    assertEquals(1, observer.counts[RUN_A1])
    assertEquals(1, log.replay(SESSION_A, RUN_A1).count { it.type == SessionEventType.RUN_FINISHED })
    // Started throw is before loop/probe attach; finalize must not invent probe artifacts.
    assertEquals(0, countProbeTerminals(probeRoot, RUN_A1))
    assertEquals(0, RuntimeProbeEmitterHubTestAccess.sizeForTest())
}

private suspend fun assertNextRunAllowedAfterCollectorThrow(
    runtime: DefaultAgentRuntime,
    observer: CountingTerminalObserver,
    log: InMemorySessionEventLog,
    probeRoot: java.io.File,
) {
    val second = runtime.run(request(RUN_A2, SESSION_A)).toList()
    assertTrue(second.any { it is AgentEvent.Started })
    assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRunState(RUN_A2)?.status)
    assertIdle(runtime)
    assertEquals(1, observer.counts[RUN_A1])
    assertEquals(1, observer.counts[RUN_A2])
    assertEquals(1, log.replay(SESSION_A, RUN_A1).count { it.type == SessionEventType.RUN_FINISHED })
    assertEquals(1, log.replay(SESSION_A, RUN_A2).count { it.type == SessionEventType.RUN_FINISHED })
    assertEquals(0, countProbeTerminals(probeRoot, RUN_A1))
    assertEquals(1, countProbeTerminals(probeRoot, RUN_A2))
    assertEquals(0, RuntimeProbeEmitterHubTestAccess.sizeForTest())
}
