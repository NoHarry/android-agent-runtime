@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.api.SessionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class InMemorySessionEventLogTest {
    @Test
    fun subscribePublishesTheAcceptedIdentityAfterAppend() {
        val log = deterministicLog()
        val seen = mutableListOf<String>()
        val handle = log.subscribe { seen += "${it.eventId}:${it.sequence}" }

        val stored = log.append(event(SessionEventType.RUN_STARTED))

        assertEquals(listOf("${stored.eventId}:${stored.sequence}"), seen)
        handle.close()
        log.append(event(SessionEventType.USER_MESSAGE, payload = SessionEventPayload.UserMessage("hi")))
        assertEquals(1, seen.size)
    }

    @Test
    fun appendAllocatesStablePerRunSequencesAndReplayOrder() {
        val log = deterministicLog()

        val first = log.append(event(SessionEventType.RUN_STARTED))
        val second = log.append(event(SessionEventType.USER_MESSAGE))
        val otherRun = log.append(event(SessionEventType.RUN_STARTED, runId = "run-2"))

        assertEquals(listOf(1L, 2L), log.replay("session-1", "run-1").map { it.sequence })
        assertEquals(listOf(first.eventId, second.eventId), log.replay("session-1", "run-1").map { it.eventId })
        assertEquals(1L, otherRun.sequence)
        assertEquals(2, first.schemaVersion)
        assertEquals(100L, first.recordedAtEpochMillis)
    }

    @Test
    fun queryFiltersWithoutCrossingSessionOrRunBoundaries() {
        val log = deterministicLog()
        log.append(event(SessionEventType.RUN_STARTED, requestId = "request-1"))
        log.append(event(SessionEventType.USAGE, requestId = "request-1"))
        log.append(event(SessionEventType.USAGE, runId = "run-2", requestId = "request-2"))
        log.append(event(SessionEventType.USAGE, sessionId = "session-2", requestId = "request-3"))

        val result = log.query(
            SessionEventQuery(
                sessionId = "session-1",
                runId = "run-1",
                requestId = "request-1",
                types = setOf(SessionEventType.USAGE),
                afterSequenceExclusive = 1,
                limit = 1,
            ),
        )

        assertEquals(1, result.size)
        assertEquals("run-1", result.single().runId)
        assertEquals(2L, result.single().sequence)
        assertEquals(3, log.query(SessionEventQuery("session-1")).size)
    }

    @Test
    fun appendFreezesPayloadAndExposesNoMutationOperation() {
        val messages = linkedMapOf("role" to SessionJson.Text("user"))
        val event = event(
            type = SessionEventType.MODEL_REQUEST,
            payload = SessionEventPayload.ModelRequest(messages = SessionJson.Object(messages)),
        )

        val log = deterministicLog()
        val appended = log.append(event)
        messages["role"] = SessionJson.Text("mutated")

        val stored = appended.payload as SessionEventPayload.ModelRequest
        assertEquals(SessionJson.Text("user"), (stored.messages as SessionJson.Object).values["role"])
        assertEquals(appended, log.query(SessionEventQuery("session-1")).single())
    }

    @Test
    fun causalSourcesMustExistEarlierInTheSameStream() {
        val log = deterministicLog()
        log.append(event(SessionEventType.TOOL_STARTED))
        val finished = log.append(
            event(SessionEventType.TOOL_RESULT, sourceEventSequences = listOf(1)),
        )

        assertEquals(listOf(1L), finished.sourceEventSequences)
        assertThrows(IllegalArgumentException::class.java) {
            log.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(3)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            log.append(event(SessionEventType.RUN_FINISHED, runId = "run-2", sourceEventSequences = listOf(1)))
        }
    }

    @Test
    fun concurrentAppendsRemainUniqueAndContiguous() {
        val log = InMemorySessionEventLog(nowEpochMillis = { 100 })
        val executor = Executors.newFixedThreadPool(WORKER_COUNT)
        try {
            val tasks = (1..EVENT_COUNT).map {
                Callable { log.append(event(SessionEventType.RUN_STATUS)) }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val replay = log.replay("session-1", "run-1")
        assertEquals((1L..EVENT_COUNT.toLong()).toList(), replay.map { it.sequence })
        assertEquals(EVENT_COUNT, replay.map { it.eventId }.toSet().size)
        assertTrue(replay.all { it.requestId == "request-1" })
    }

    @Test
    fun successfulMemoryLogReportsVolatileHealth() {
        val log = deterministicLog()
        log.append(event(SessionEventType.RUN_STARTED))

        assertEquals(
            cc.noharry.agent.kernel.api.SessionEventLogHealthStatus.VOLATILE,
            log.health("session-1", "run-1").status,
        )
        assertEquals(
            cc.noharry.agent.kernel.api.SessionEventLogDurability.VOLATILE,
            log.durability(),
        )
    }

    @Test
    fun appendRejectsSensitiveMetadataAndInvalidPayloadsWithoutRecording() {
        val log = deterministicLog()
        val rejected = listOf(
            event(
                SessionEventType.MODEL_REQUEST,
                payload = SessionEventPayload.ModelRequest(
                    options = SessionJson.Object(
                        mapOf("Authorization" to SessionJson.Text("secret")),
                    ),
                ),
            ),
            event(
                SessionEventType.USER_MESSAGE,
                payload = SessionEventPayload.UserMessage(text = "Bearer sk-secret"),
            ),
            event(SessionEventType.RUN_STARTED, sessionId = " "),
            event(SessionEventType.RUN_STARTED, runId = " "),
            event(SessionEventType.RUN_STARTED, requestId = " "),
            event(SessionEventType.RUN_STARTED, sessionId = "s".repeat(MAX_EVENT_IDENTITY_CHARS + 1)),
            event(SessionEventType.RUN_STARTED, runId = "r".repeat(MAX_EVENT_IDENTITY_CHARS + 1)),
            event(SessionEventType.RUN_STARTED, requestId = "q".repeat(MAX_EVENT_IDENTITY_CHARS + 1)),
        )
        rejected.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { log.append(invalid) }
        }

        assertEquals(0, log.query(SessionEventQuery("session-1")).size)
        assertEquals(0, log.replay("session-1", "run-1").size)
        val health = log.health("session-1", "run-1")
        assertEquals(0, health.appendedCount)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, health.status)
        assertNotEquals(SessionEventLogHealthStatus.VOLATILE, health.status)
    }

    @Test
    fun rejectedAppendDoesNotMutateAHealthyVolatileStream() {
        val log = deterministicLog()
        log.append(event(SessionEventType.RUN_STARTED))
        assertThrows(IllegalArgumentException::class.java) {
            log.append(
                event(
                    SessionEventType.MODEL_CALL_FINISHED,
                    payload = SessionEventPayload.ModelRequest(
                        options = SessionJson.Object(
                            mapOf("api_key" to SessionJson.Text("secret")),
                        ),
                    ),
                ),
            )
        }
        assertEquals(1, log.replay("session-1", "run-1").size)
        val health = log.health("session-1", "run-1")
        assertEquals(SessionEventLogHealthStatus.VOLATILE, health.status)
        assertEquals(1, health.appendedCount)
        assertEquals(0, health.failedCount)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, health.status)
    }
}

private fun deterministicLog(): InMemorySessionEventLog {
    var eventId = 0
    return InMemorySessionEventLog(
        nowEpochMillis = { 100 },
        eventIdFactory = { "event-${++eventId}" },
    )
}

private fun event(
    type: SessionEventType,
    sessionId: String = "session-1",
    runId: String = "run-1",
    requestId: String = "request-1",
    payload: SessionEventPayload = SessionEventPayload.Empty,
    sourceEventSequences: List<Long> = emptyList(),
): SessionEventAppend = SessionEventAppend(
    sessionId = sessionId,
    runId = runId,
    requestId = requestId,
    type = type,
    payload = payload,
    sourceEventSequences = sourceEventSequences,
)

private const val WORKER_COUNT = 4
private const val EVENT_COUNT = 100
