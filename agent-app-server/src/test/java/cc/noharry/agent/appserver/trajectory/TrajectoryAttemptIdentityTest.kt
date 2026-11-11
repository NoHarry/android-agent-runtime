@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory

import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.view.questionCards
import cc.noharry.agent.appserver.runtime.RuntimeMultiSessionRuntimeStub
import cc.noharry.agent.appserver.runtime.RuntimeRunScope
import cc.noharry.agent.appserver.usage.contextUsage
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventPayload
import cc.noharry.agent.spi.SessionEventType
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TrajectoryAttemptIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun latestAttemptKeepsOnePublicTurnAndItsQuestionAndContextUsage() = runBlocking {
        val old = attempt("execution-old", 100, "Old destination?", 10)
        val current = attempt("execution-new", 200, "New destination?", 25)
        val reader = reader(current)
        val repository = repository(this, reader)
        old.forEach(repository::observeAccepted)
        current.forEach(repository::observeAccepted)
        // Even a delayed duplicate start cannot revive an already retired execution.
        repository.observeAccepted(old.first().copy(recordedAtEpochMillis = 999))
        val live = repository.snapshot(SESSION)
        val group = live.runGroups.single()
        assertEquals(TURN, group.runId)
        assertEquals(current.map { it.eventId }, group.records.map { it.eventId })
        assertEquals("New destination?", live.questionCards(TURN).single().first.title)
        assertEquals(TURN, live.questionCards(TURN).single().first.runId)
        assertEquals(25L, contextUsage(live, MODEL, 1_000, TURN).inputTokens)
        assertTrue(requireNotNull(repository.rawText(SESSION, current.first().eventId)).contains("runId=execution-new"))
        assertTrue(requireNotNull(repository.rawText(SESSION, old.first().eventId)).contains("runId=execution-old"))
        repository.reconcile(reader, RuntimeRunScope(TURN, SESSION))
        assertEquals(group.records.map { it.id }, repository.snapshot(SESSION).runGroups.single().records.map { it.id })
        assertFalse(repository.snapshot(SESSION).runGroups.single().health.hasSequenceGap)
        assertTrue(current.all { it.runId == "execution-new" && it.requestId == TURN })
    }

    @Test fun coldCatalogUsesThePublicRequestEvenWhenSavedRunIdIsPhysical() = runBlocking {
        val current = attempt("execution-new", 200, "New destination?", 25)
        val repository = repository(this, reader(current))
        repository.refreshSession(SESSION)
        val group = repository.snapshot(SESSION).runGroups.single()
        assertEquals(TURN, group.runId)
        assertEquals(current.map { it.eventId }, group.records.map { it.eventId })
    }

    @Test fun oldLoadsAndRecordsCannotOverwriteANewerAttempt() {
        val old = attempt("execution-old", 100, "Old destination?", 10)
        val current = attempt("execution-new", 200, "New destination?", 25)
        val state = TrajectorySessionState(SESSION)
        old.forEach { state.ensureAcceptedGroup(it, it.toTrajectoryRecord()) }
        val load = state.beginLoad(TURN)
        current.forEach { state.ensureAcceptedGroup(it, it.toTrajectoryRecord()) }
        assertFalse(state.isLatestLoad(TURN, load))
        state.putGroup(TrajectoryRunGroup(SESSION, TURN, records = old.map { it.toTrajectoryRecord() }))
        assertEquals(current.map { it.eventId }, state.toSnapshot(1).runGroups.single().records.map { it.eventId })
    }

    @Test fun mixedAttemptsChooseLatestWhileForeignSessionsStillFailTheIntegrityCheck() {
        val old = attempt("execution-old", 100, "Old destination?", 10)
        val current = attempt("execution-new", 200, "New destination?", 25)
        val projection = projectCanonicalRecords(SESSION, TURN, old + current)
        assertEquals(current.map { it.eventId }, projection.records.map { it.eventId })
        assertFalse(projection.hasSequenceConflict)
        val foreign = projectCanonicalRecords(SESSION, TURN, old + current + current.first().copy(sessionId = "other"))
        assertTrue(foreign.hasSequenceConflict)
        assertEquals(current.map { it.eventId }, foreign.records.map { it.eventId })
    }

    @Test fun lateReconcileFailureDoesNotMarkTheNewAttemptAsFailed() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldReader = object : RuntimeMultiSessionRuntimeStub() {
            override suspend fun replaySessionEvents(sessionId: String, runId: String): List<SessionEvent> {
                entered.complete(Unit)
                release.await()
                error("old attempt read failed")
            }
        }
        val current = attempt("execution-new", 200, "New destination?", 25)
        val repository = repository(this, reader(current))
        attempt("execution-old", 100, "Old destination?", 10).forEach(repository::observeAccepted)
        val oldLoad = async { repository.reconcile(oldReader, RuntimeRunScope(TURN, SESSION)) }
        withTimeout(5_000) { entered.await() }
        current.forEach(repository::observeAccepted)
        release.complete(Unit)
        oldLoad.await()
        val group = repository.snapshot(SESSION).runGroups.single()
        assertFalse(group.loadFailed)
        assertEquals(current.map { it.eventId }, group.records.map { it.eventId })
        assertTrue(repository.snapshot(SESSION).loadingRunIds.isEmpty())
    }

    private fun repository(scope: CoroutineScope, reader: RuntimeMultiSessionRuntimeStub): DefaultTrajectoryRepository {
        val root = temporary.newFolder()
        return DefaultTrajectoryRepository(
            TrajectoryEventLogGateway({ root }, { File(root, it) }, { reader }, Dispatchers.Unconfined),
            readTurns = { listOf(SessionTurn(TURN, TURN, "saved-execution", SESSION, "app", "ask", 100, 200, "COMPLETED", "done", emptyList())) },
            ioScope = scope,
        )
    }

    private fun reader(events: List<SessionEvent>) = object : RuntimeMultiSessionRuntimeStub() {
        override suspend fun replaySessionEvents(sessionId: String, runId: String): List<SessionEvent> {
            assertEquals(SESSION, sessionId)
            assertEquals(TURN, runId)
            return events
        }
    }

    private fun attempt(execution: String, startedAt: Long, question: String, input: Long): List<SessionEvent> {
        val payloads = listOf(
            SessionEventType.RUN_STARTED to SessionEventPayload.RunLifecycle(status = "RUNNING"),
            SessionEventType.MODEL_REQUEST to SessionEventPayload.ModelRequest(modelRef = MODEL),
            SessionEventType.USAGE to SessionEventPayload.Usage(inputTokens = input),
            SessionEventType.MODEL_CALL_FINISHED to SessionEventPayload.ModelCallFinished(source = "round", modelRef = MODEL),
            SessionEventType.USER_INTERACTION to SessionEventPayload.UserInteraction("QUESTION", "PENDING", questionId = "$execution-question", text = question),
        )
        return payloads.mapIndexed { index, (type, payload) ->
            SessionEvent(2, "$execution-$index", SESSION, execution, TURN, index + 1L, startedAt + index, type, payload, emptyList())
        }
    }
}

private const val SESSION = "session"
private const val TURN = "public-turn"
private const val MODEL = "openai:test-model"
