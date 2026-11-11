@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.EventLogHealthStatus
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectorySource
import cc.noharry.agent.appserver.runtime.ActiveRunSnapshot
import cc.noharry.agent.appserver.runtime.RuntimeMultiSessionRuntimeStub
import cc.noharry.agent.appserver.runtime.RuntimeRunScope
import cc.noharry.agent.appserver.runtime.awaitUntil
import cc.noharry.agent.appserver.runtime.RuntimeTurnRequest
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventLogDurability
import cc.noharry.agent.spi.SessionEventLogHealth
import cc.noharry.agent.spi.SessionEventLogHealthStatus
import cc.noharry.agent.spi.SessionEventQuery
import cc.noharry.agent.spi.SessionEventType
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrajectoryRepositoryTest {

    @Test
    fun catalogMergesPersistedTurnsWithInProcessRuns() = runTest {
        val fixture = fixture(
            turns = listOf(turn("run-old", startedAt = 100L)),
            activeRuns = listOf(activeSnapshot("run-live", startedAt = 500L)),
        )

        fixture.repository.refreshSession(SESSION)

        val snapshot = fixture.repository.snapshot(SESSION)
        assertEquals(listOf("run-live", "run-old"), snapshot.runGroups.map { it.runId })
        assertTrue(snapshot.runGroups.first().isActive)
        assertEquals(2, snapshot.totalKnownRunCount)
    }

    @Test
    fun runGroupsUseRunIdAsADeterministicTieBreakForEqualStartTimes() = runTest {
        val fixture = fixture(
            turns = listOf(turn("run-b", startedAt = 100L), turn("run-a", startedAt = 100L)),
        )

        fixture.repository.refreshSession(SESSION)

        assertEquals(listOf("run-a", "run-b"), fixture.repository.snapshot(SESSION).runGroups.map { it.runId })
    }

    @Test
    fun initialPageShowsSixRunsAndLoadOlderAddsSixMore() = runTest {
        val fixture = fixture(turns = (1..15).map { turn("run-%02d".format(it), startedAt = it * 100L) })

        fixture.repository.refreshSession(SESSION)

        assertEquals(TRAJECTORY_INITIAL_VISIBLE_RUNS, fixture.repository.snapshot(SESSION).runGroups.size)
        assertTrue(fixture.repository.snapshot(SESSION).hasOlderRuns)

        fixture.repository.loadOlderRuns(SESSION)

        assertEquals(
            TRAJECTORY_INITIAL_VISIBLE_RUNS + TRAJECTORY_OLDER_RUN_PAGE,
            fixture.repository.snapshot(SESSION).runGroups.size,
        )
        assertEquals(15, fixture.repository.snapshot(SESSION).totalKnownRunCount)
    }

    @Test
    fun runWithoutDurableEventsStaysEmptyInsteadOfSynthesisingASummary() = runTest {
        val fixture = fixture(turns = listOf(turn("run-old", startedAt = 100L)))

        fixture.repository.refreshSession(SESSION)

        val group = fixture.repository.snapshot(SESSION).runGroups.single { it.runId == "run-old" }
        assertTrue(group.recordsLoaded)
        assertFalse(group.loadFailed)
        assertTrue(group.records.isEmpty())
    }

    @Test
    fun durableRunProjectsCanonicalRecordsAndDurableHealth() = runTest {
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            events = mapOf("run-1" to listOf(runEvent("a", 1), runEvent("b", 2))),
        )

        fixture.repository.refreshSession(SESSION)

        val group = fixture.repository.snapshot(SESSION).runGroups.single()
        assertEquals(listOf(1L, 2L), group.records.mapNotNull { it.sequence })
        assertEquals(EventLogHealthStatus.COMPLETE, group.health.eventLogStatus)
        assertEquals(cc.noharry.agent.appserver.protocol.EventLogDurability.DURABLE, group.health.durability)
    }

    @Test
    fun readFailureIsReportedAsAFailedLoadNotAsAnUnexpandedRun() = runTest {
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            events = mapOf("run-1" to listOf(runEvent("a", 1))),
        )
        fixture.repository.refreshSession(SESSION)
        assertEquals(1, fixture.repository.snapshot(SESSION).runGroups.single().records.size)

        fixture.failReads = true
        fixture.repository.loadRun(SESSION, "run-1")

        val group = fixture.repository.snapshot(SESSION).runGroups.single()
        assertTrue(group.loadFailed)
        assertTrue(group.recordsLoaded)
        assertEquals(listOf(TrajectoryKind.LOAD_ERROR), group.records.map { it.kind })
        assertEquals(TrajectoryEventLogReason.READER_BUILD_FAILED, group.health.safeReasonCode)
    }

    @Test
    fun readFailureIsRetryableAndRecoversOnceTheReaderWorksAgain() = runTest {
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            events = mapOf("run-1" to listOf(runEvent("a", 1), runEvent("b", 2))),
        )
        fixture.failReads = true
        fixture.repository.refreshSession(SESSION)
        assertTrue(fixture.repository.snapshot(SESSION).runGroups.single().loadFailed)

        fixture.failReads = false
        fixture.repository.loadRun(SESSION, "run-1")

        val group = fixture.repository.snapshot(SESSION).runGroups.single()
        assertFalse(group.loadFailed)
        assertEquals(listOf(1L, 2L), group.records.mapNotNull { it.sequence })
    }

    @Test
    fun backgroundSessionEventsBuildItsOwnTrajectoryWithoutTouchingTheSelectedOne() = runTest {
        val fixture = fixture()

        fixture.repository.observeAccepted(
            sessionEvent(
                eventId = "bg-think",
                sessionId = "session-bg",
                runId = "bg-run",
                type = SessionEventType.ASSISTANT_CHUNK,
                safeMetadata = mapOf("kind" to "thinking", "text" to "planning"),
            ),
        )

        assertTrue(fixture.repository.snapshot(SESSION).runGroups.isEmpty())
        val backgroundGroups = fixture.repository.snapshot("session-bg").runGroups
        assertEquals(1, backgroundGroups.size)
        assertTrue(backgroundGroups.single().records.any { it.kind == TrajectoryKind.THINKING })
    }

    @Test
    fun changeNotificationsCarryTheSessionSoTheHostCanFilterBackgroundRuns() = runTest {
        val fixture = fixture()

        fixture.repository.observeAccepted(sessionEvent(eventId = "s1", sessionId = SESSION, runId = "run-1"))
        fixture.repository.observeAccepted(sessionEvent(eventId = "bg", sessionId = "session-bg", runId = "bg"))

        assertEquals(listOf(SESSION, "session-bg"), fixture.changedSessions)
    }

    @Test
    fun anEventInFullIsGivenWhenAskedForAndNotWithItsRecord() = runTest {
        val fixture = fixture()

        fixture.repository.observeAccepted(sessionEvent(eventId = "s1", sessionId = SESSION, runId = "run-1"))

        val raw = fixture.repository.rawText(SESSION, "s1").orEmpty()
        assertTrue(raw, raw.contains("eventId=s1"))
        assertEquals(null, fixture.repository.rawText(SESSION, "unknown"))
        assertEquals(null, fixture.repository.rawText("session-other", "s1"))
    }

    @Test
    fun acceptedRecordsAreBoundedPerRunWithoutTouchingTheEventLog() = runTest {
        val fixture = fixture()

        repeat(TRAJECTORY_MAX_LIVE_RECORDS_PER_RUN + 25) { index ->
            fixture.repository.observeAccepted(
                sessionEvent(
                    eventId = "evt-$index",
                    sessionId = SESSION,
                    runId = "run-1",
                    sequence = index.toLong() + 1,
                    type = SessionEventType.RUN_STATUS,
                ),
            )
        }

        assertEquals(
            TRAJECTORY_MAX_LIVE_RECORDS_PER_RUN,
            fixture.repository.liveRecordsForTest(SESSION, "run-1").size,
        )
        assertTrue(fixture.sessionRoot(SESSION).isDirectory)
    }

    @Test
    fun dropSessionClearsStateAndDeletesOnlyThatSessionRoot() = runTest {
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            events = mapOf("run-1" to listOf(runEvent("a", 1))),
        )
        fixture.repository.refreshSession(SESSION)
        assertEquals(1, fixture.repository.snapshot(SESSION).runGroups.size)

        fixture.repository.dropSession(SESSION)

        assertTrue(fixture.repository.snapshot(SESSION).runGroups.isEmpty())
        assertFalse(fixture.sessionRoot(SESSION).exists())
        assertTrue(fixture.sessionRoot("session-other").isDirectory)
    }

    @Test
    fun clearDropsEveryStateAndDeletesAllRoots() = runTest {
        val fixture = fixture(turns = listOf(turn("run-1", startedAt = 100L)))
        fixture.repository.refreshSession(SESSION)

        fixture.repository.clear()

        assertTrue(fixture.repository.snapshot(SESSION).runGroups.isEmpty())
        assertFalse(fixture.baseRoot.exists())
    }

    @Test
    fun reconcileReplaysTheSameCanonicalIdentityWithoutASecondSource() = runTest {
        val fixture = fixture(turns = listOf(turn("run-1", startedAt = 100L)))
        val scope = RuntimeRunScope("run-1", SESSION)
        val event = runEvent(
            eventId = "tool-start",
            sequence = 1,
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to "run-1:call-a", "toolName" to "web_search"),
        )
        fixture.repository.observeAccepted(event)

        fixture.repository.reconcile(
            runtime = FakeReaderRuntime(mapOf("run-1" to listOf(event))),
            scope = scope,
        )

        val records = fixture.repository.snapshot(SESSION).runGroups.single { it.runId == "run-1" }.records
        assertEquals(1, records.size)
        assertEquals("event:tool-start", records.single().id.value)
        assertEquals(setOf(TrajectorySource.EVENT_LOG), records.single().sources)
        assertTrue(fixture.repository.snapshot(SESSION).recordAliases.isEmpty())
    }

    /**
     * A failed replay used to default to an empty list, so whenever the health call still
     * succeeded the run rendered as a healthy zero-event trajectory instead of a read failure.
     */
    @Test
    fun reconcileReplayFailureIsReportedAsAFailedLoadNotAnEmptyRun() = runTest {
        val fixture = fixture(turns = listOf(turn("run-1", startedAt = 100L)))
        val scope = RuntimeRunScope("run-1", SESSION)

        fixture.repository.reconcile(FakeReaderRuntime(emptyMap(), failReplay = true), scope)

        val group = fixture.repository.snapshot(SESSION).runGroups.single()
        assertTrue(group.loadFailed)
        assertEquals(listOf(TrajectoryKind.LOAD_ERROR), group.records.map { it.kind })
        assertEquals(TrajectoryEventLogReason.REPLAY_FAILED, group.health.safeReasonCode)
    }

    @Test
    fun acceptedAndReplayedSelectionUsesTheSameRecordId() = runTest {
        val fixture = fixture(turns = listOf(turn("run-1", startedAt = 100L)))
        val scope = RuntimeRunScope("run-1", SESSION)
        val event = runEvent(
            eventId = "tool-start",
            sequence = 1,
            type = SessionEventType.TOOL_STARTED,
            safeMetadata = mapOf("callId" to "run-1:call-a", "toolName" to "read_file"),
        )
        fixture.repository.observeAccepted(event)
        val acceptedId = fixture.repository.snapshot(SESSION).runGroups.single().records.single().id

        fixture.repository.reconcile(FakeReaderRuntime(mapOf("run-1" to listOf(event))), scope)

        val snapshot = fixture.repository.snapshot(SESSION)
        assertEquals(acceptedId, snapshot.runGroups.single().records.single().id)
        assertEquals(acceptedId, snapshot.resolveRecordAlias(acceptedId))
    }

    @Test
    fun attachWriterPublishesAcceptedEventsWithTheSameIdentityAsReplay() = runTest {
        val fixture = fixture()
        val event = runEvent(eventId = "live-1", sequence = 1, type = SessionEventType.USER_MESSAGE)
        val runtime = FakeReaderRuntime(mapOf("run-1" to listOf(event)))

        val handle = fixture.repository.attachWriter(runtime, SESSION)
        runtime.emit(event)

        val record = fixture.repository.snapshot(SESSION).runGroups.single().records.single()
        assertEquals("event:live-1", record.id.value)
        assertEquals(setOf(TrajectorySource.EVENT_LOG), record.sources)
        handle.close()
    }

    /**
     * Deterministic stale race with distinct results per reader.
     *
     * The old load is held inside its reader while a refresh bumps the generation and starts a
     * newer load of the same run, which parks on the session mutex behind the held reader. The
     * old load finishing must neither publish its stale events nor clear the loading marker the
     * newer load now owns — with the previous shared-set marker this fails on the marker
     * assertion, and with `getOrDefault`-style publishing it fails on the old-events assertion.
     */
    @Test
    fun staleLoadNeitherClearsTheNewerLoadsMarkerNorPublishesItsResult() = runBlocking {
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val oldEvents = listOf(runEvent("old-a", 1))
        val newEvents = listOf(runEvent("new-a", 1), runEvent("new-b", 2))
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            scope = this,
            readerForCall = { call ->
                staleRaceReader(call, oldEntered, releaseOld, releaseNew, oldEvents, newEvents)
            },
        )

        val oldLoad = launch(Dispatchers.IO) { fixture.repository.loadRun(SESSION, "run-1") }
        assertTrue(oldEntered.await(STALE_TIMEOUT_SEC, TimeUnit.SECONDS))
        assertTrue(fixture.repository.snapshot(SESSION).loadingRunIds.contains("run-1"))

        // Bumps the generation and starts the newer load of the same run.
        val refresh = launch(Dispatchers.IO) { fixture.repository.refreshSession(SESSION) }
        // The newer load owns the marker once its begin has published. Four publishes are due by
        // then: old load begin, refresh start, catalog applied, new load begin.
        assertTrue(
            "newer load never began",
            awaitUntil(STALE_TIMEOUT_SEC) { fixture.changedSessions.count { it == SESSION } >= 4 },
        )

        releaseOld.countDown()
        oldLoad.join()
        // The newer load is parked in its health call while the old one is fully finished.
        assertTrue("newer load never reached its health read", awaitUntil { fixture.readerCallCount >= 4 })

        val midway = fixture.repository.snapshot(SESSION)
        // The stale load must not clear the marker owned by the newer in-flight load...
        assertTrue("stale load cleared the newer load's marker", midway.loadingRunIds.contains("run-1"))
        // ...and must not publish its own stale events.
        assertTrue(midway.runGroups.flatMap { it.records }.none { it.eventId == "old-a" })

        releaseNew.countDown()
        refresh.join()

        val finalSnapshot = fixture.repository.snapshot(SESSION)
        assertTrue(finalSnapshot.loadingRunIds.isEmpty())
        assertEquals(
            listOf("new-a", "new-b"),
            finalSnapshot.runGroups.single().records.mapNotNull { it.eventId },
        )
    }

    @Test
    fun loadedRunLifecycleComesFromCanonicalEventsNotTheTurn() = runTest {
        val events = listOf(
            runEvent("start", 1, type = SessionEventType.RUN_STARTED, safeMetadata = mapOf("status" to "RUNNING")),
            runEvent("status", 2, type = SessionEventType.RUN_STATUS, safeMetadata = mapOf("status" to "FAILED")),
            runEvent("end", 3, type = SessionEventType.RUN_FINISHED, safeMetadata = mapOf("status" to "FAILED")),
        )
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            events = mapOf("run-1" to events),
        )

        fixture.repository.refreshSession(SESSION)

        val group = fixture.repository.snapshot(SESSION).runGroups.single()
        assertEquals("FAILED", group.lifecycleStatus)
        assertEquals("FAILED", group.completionOutcome)
        assertEquals(1_000L, group.startedAtEpochMillis)
        assertEquals(1_000L, group.completedAtEpochMillis)
        assertFalse(fixture.repository.snapshot(SESSION).refreshing)
    }

    @Test
    fun sameGenerationOlderLoadDoesNotOverwriteNewerResult() = runBlocking {
        val oldEntered = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val releaseNew = CountDownLatch(1)
        val oldEvents = listOf(runEvent("old-a", 1))
        val newEvents = listOf(runEvent("new-a", 1), runEvent("new-b", 2))
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            scope = this,
            readerForCall = { call ->
                staleRaceReader(call, oldEntered, releaseOld, releaseNew, oldEvents, newEvents)
            },
        )

        val oldLoad = launch(Dispatchers.IO) { fixture.repository.loadRun(SESSION, "run-1") }
        assertTrue(oldEntered.await(STALE_TIMEOUT_SEC, TimeUnit.SECONDS))
        val newLoad = launch(Dispatchers.IO) { fixture.repository.loadRun(SESSION, "run-1") }
        assertTrue(
            "newer load never began",
            awaitUntil(STALE_TIMEOUT_SEC) { fixture.changedSessions.count { it == SESSION } >= 2 },
        )
        releaseOld.countDown()
        oldLoad.join()
        assertTrue(fixture.repository.snapshot(SESSION).runGroups.flatMap { it.records }.none { it.eventId == "old-a" })
        releaseNew.countDown()
        newLoad.join()
        assertEquals(
            listOf("new-a", "new-b"),
            fixture.repository.snapshot(SESSION).runGroups.single().records.mapNotNull { it.eventId },
        )
    }

    @Test
    fun staleRefreshDoesNotLeaveRefreshingTrue() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val fixture = fixture(
            turns = listOf(turn("run-1", startedAt = 100L)),
            scope = this,
            readerForCall = { call ->
                if (call == 1) {
                    entered.countDown()
                    check(release.await(STALE_TIMEOUT_SEC, TimeUnit.SECONDS)) { "refresh reader stuck" }
                }
                FakeReaderRuntime(mapOf("run-1" to listOf(runEvent("a", 1))))
            },
        )

        val refresh = launch(Dispatchers.IO) { fixture.repository.refreshSession(SESSION) }
        assertTrue(entered.await(STALE_TIMEOUT_SEC, TimeUnit.SECONDS))
        release.countDown()
        refresh.join()
        fixture.repository.dropSession(SESSION)
        assertFalse(fixture.repository.snapshot(SESSION).refreshing)
        assertTrue(fixture.repository.snapshot(SESSION).runGroups.isEmpty())
    }

    @Test
    fun reconcileFailureLeavesTheGroupUnknownRatherThanThrowing() = runTest {
        val fixture = fixture()
        val scope = RuntimeRunScope("run-1", SESSION)

        fixture.repository.reconcile(FakeReaderRuntime(emptyMap(), failHealth = true), scope)

        val group = fixture.repository.snapshot(SESSION).runGroups.single()
        assertEquals(EventLogHealthStatus.UNKNOWN, group.health.eventLogStatus)
        assertEquals(TrajectoryEventLogReason.HEALTH_FAILED, group.health.safeReasonCode)
    }

    private fun TestScope.fixture(
        turns: List<SessionTurn> = emptyList(),
        events: Map<String, List<SessionEvent>> = emptyMap(),
        activeRuns: List<ActiveRunSnapshot> = emptyList(),
    ) = Fixture(turns, events, activeRuns, this)

    private fun fixture(
        turns: List<SessionTurn>,
        scope: CoroutineScope,
        readerForCall: (Int) -> RuntimeMultiSessionRuntimeStub,
    ) = Fixture(turns, emptyMap(), emptyList(), scope, readerForCall)

    /**
     * Reader state machine for the stale race. Each load reads twice (replay then health) and the
     * session mutex serialises them, so call 1 is the old replay and call 4 the new health. Calls
     * 2 and 3 are the old health and the new replay in whichever order they win the mutex, so they
     * must not block; the old load's health result is discarded as stale anyway.
     */
    private fun staleRaceReader(
        call: Int,
        oldEntered: CountDownLatch,
        releaseOld: CountDownLatch,
        releaseNew: CountDownLatch,
        oldEvents: List<SessionEvent>,
        newEvents: List<SessionEvent>,
    ): RuntimeMultiSessionRuntimeStub = when (call) {
        1 -> {
            oldEntered.countDown()
            check(releaseOld.await(STALE_TIMEOUT_SEC, TimeUnit.SECONDS)) { "old reader stuck" }
            FakeReaderRuntime(mapOf("run-1" to oldEvents))
        }
        2, 3 -> FakeReaderRuntime(mapOf("run-1" to newEvents))
        else -> {
            check(releaseNew.await(STALE_TIMEOUT_SEC, TimeUnit.SECONDS)) { "new reader stuck" }
            FakeReaderRuntime(mapOf("run-1" to newEvents))
        }
    }

    private class Fixture(
        turns: List<SessionTurn>,
        events: Map<String, List<SessionEvent>>,
        activeRuns: List<ActiveRunSnapshot>,
        scope: CoroutineScope,
        private val readerForCall: ((Int) -> RuntimeMultiSessionRuntimeStub)? = null,
    ) {
        @Volatile var failReads: Boolean = false
        val changedSessions = Collections.synchronizedList(mutableListOf<String>())
        val baseRoot: File = Files.createTempDirectory("trajectory-repo-").toFile()
        private val readerCalls = AtomicInteger(0)
        val readerCallCount: Int get() = readerCalls.get()

        private val gateway = TrajectoryEventLogGateway(
            baseRoot = { baseRoot },
            rootForSession = { sessionRoot(it) },
            readerFactory = {
                val call = readerCalls.incrementAndGet()
                readerForCall?.invoke(call)
                    ?: if (failReads) error("reader down") else FakeReaderRuntime(events)
            },
            ioDispatcher = Dispatchers.Unconfined,
        )

        val repository = DefaultTrajectoryRepository(
            gateway = gateway,
            readTurns = { sessionId -> turns.filter { it.sessionId == sessionId } },
            activeRunSnapshots = { activeRuns },
            ioScope = scope,
            onChanged = { changedSessions += it },
        )

        init {
            sessionRoot(SESSION).mkdirs()
            sessionRoot("session-other").mkdirs()
        }

        fun sessionRoot(sessionId: String): File = File(baseRoot, sessionId)
    }

    private class FakeReaderRuntime(
        private val events: Map<String, List<SessionEvent>>,
        private val failHealth: Boolean = false,
        private val failReplay: Boolean = false,
    ) : RuntimeMultiSessionRuntimeStub() {
        private val listeners = mutableListOf<(SessionEvent) -> Unit>()

        fun emit(event: SessionEvent) {
            listeners.forEach { it(event) }
        }

        override fun subscribeSessionEvents(
            sessionId: String,
            listener: (SessionEvent) -> Unit,
        ): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners.remove(listener) }
        }

        override fun run(request: RuntimeTurnRequest): Flow<cc.noharry.agent.ThreadEvent> = emptyFlow()



        override suspend fun getRunState(runId: String) = null

        override suspend fun getRuntimeResult(runId: String) = null


        override suspend fun replaySessionEvents(sessionId: String, runId: String): List<SessionEvent> {
            if (failReplay) error("replay unavailable")
            return events[runId].orEmpty()
        }

        override suspend fun querySessionEvents(query: SessionEventQuery): List<SessionEvent> =
            events[query.runId].orEmpty()

        override suspend fun getSessionEventLogHealth(sessionId: String, runId: String): SessionEventLogHealth {
            if (failHealth) error("health unavailable")
            val count = events[runId].orEmpty().size
            return SessionEventLogHealth(
                status = SessionEventLogHealthStatus.COMPLETE,
                backendDurability = SessionEventLogDurability.DURABLE,
                attemptedCount = count,
                appendedCount = count,
            )
        }
    }

    private fun runEvent(
        eventId: String,
        sequence: Long,
        runId: String = "run-1",
        type: SessionEventType = SessionEventType.RUN_STARTED,
        safeMetadata: Map<String, String> = emptyMap(),
    ) = sessionEvent(
        eventId = eventId,
        sessionId = SESSION,
        runId = runId,
        sequence = sequence,
        type = type,
        safeMetadata = safeMetadata,
    )

    private fun turn(runId: String, startedAt: Long) = SessionTurn(
        turnId = "turn-$runId",
        requestId = runId,
        runId = runId,
        sessionId = SESSION,
        scene = "",
        userText = "hello",
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = startedAt + 10L,
        finalStatus = "COMPLETED",
        finalResponse = "done",
        tools = emptyList(),
    )

    private fun activeSnapshot(runId: String, startedAt: Long) = ActiveRunSnapshot(
        sessionId = SESSION,
        runId = runId,
        requestId = runId,
        isActive = true,
        startedAtEpochMillis = startedAt,
        lastRunSummary = null,
        lifecycleStatus = "RUNNING",
        completionOutcome = "UNKNOWN",
        logHealth = "UNKNOWN",
    )
}

private const val SESSION = "session-1"
private const val STALE_TIMEOUT_SEC = 20L
