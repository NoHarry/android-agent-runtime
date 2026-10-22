@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventLogDurability
import cc.noharry.agent.kernel.api.SessionEventLogHealth
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.agent.kernel.api.SessionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventLogHealthTest {
    @Test
    fun inMemorySuccessfulStreamIsVolatileNeverComplete() {
        val log = InMemorySessionEventLog(nowEpochMillis = { 100 })
        log.append(event())

        val health = log.health("session-1", "run-1")
        assertEquals(SessionEventLogDurability.VOLATILE, log.durability())
        assertEquals(SessionEventLogHealthStatus.VOLATILE, health.status)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, health.status)
        assertEquals(1, health.attemptedCount)
        assertEquals(1, health.appendedCount)
    }

    @Test
    fun missingEmptyAndCustomEvidenceClassifyUnknown() {
        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            SessionEventLogHealth.classifyOffline(
                streamExists = false,
                sidecarExists = false,
                eventsEmpty = true,
                health = null,
            ),
        )
        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = true,
                health = "COMPLETE",
            ),
        )
        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = null,
                eventsEmpty = false,
                health = "COMPLETE",
            ),
        )
        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = false,
                health = "CUSTOM_OK",
            ),
        )
        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = false,
                health = "COMPLETE",
                streamCorrupt = true,
            ),
        )
    }

    @Test
    fun customLegacyBackendDefaultsToUnknown() {
        val log = object : SessionEventLog {
            override fun append(event: SessionEventAppend) = error("unused")
            override fun query(query: SessionEventQuery) = emptyList<cc.noharry.agent.kernel.api.SessionEvent>()
            override fun replay(sessionId: String, runId: String) = emptyList<cc.noharry.agent.kernel.api.SessionEvent>()
        }
        assertEquals(SessionEventLogDurability.UNKNOWN, log.durability())
        assertEquals(SessionEventLogHealthStatus.UNKNOWN, log.health("session-1", "run-1").status)
    }

    @Test
    fun identityStaleCounterAndCoverageGapsNeverClassifyComplete() {
        val failClosed = listOf(
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = false,
                health = "COMPLETE",
                identityMatched = false,
            ),
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = false,
                health = "COMPLETE",
                lastPersistedMatchesLastSequence = false,
            ),
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = false,
                health = "COMPLETE",
                countersConsistent = false,
            ),
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = false,
                health = "COMPLETE",
                coverageGap = true,
            ),
            SessionEventLogHealth.classifyOffline(
                streamExists = true,
                sidecarExists = true,
                eventsEmpty = false,
                health = "COMPLETE",
                sidecarCorrupt = true,
            ),
        )
        failClosed.forEach { status ->
            assertNotEquals(SessionEventLogHealthStatus.COMPLETE, status)
        }
    }

    @Test
    fun liveCompleteCannotOverrideOfflineFailClosedEvidence() {
        val liveComplete = SessionEventLogHealth(
            status = SessionEventLogHealthStatus.COMPLETE,
            attemptedCount = 1,
            appendedCount = 1,
            backendDurability = SessionEventLogDurability.DURABLE,
        )
        val offlineUnknown = SessionEventLogHealth.unknown(SessionEventLogDurability.DURABLE)
        val offlineDegraded = liveComplete.copy(status = SessionEventLogHealthStatus.DEGRADED)
        assertEquals(
            SessionEventLogHealthStatus.UNKNOWN,
            mergeLiveAndOfflineHealth(liveComplete, offlineUnknown).status,
        )
        assertEquals(
            SessionEventLogHealthStatus.DEGRADED,
            mergeLiveAndOfflineHealth(liveComplete, offlineDegraded).status,
        )
        assertEquals(
            SessionEventLogHealthStatus.DEGRADED,
            mergeLiveAndOfflineHealth(offlineDegraded, liveComplete).status,
        )
    }

    @Test
    fun sidecarIdentityRequiresExactSessionAndRun() {
        assertTrue(sidecarIdentityMatches("session-1", "run-1", "session-1", "run-1"))
        assertFalse(sidecarIdentityMatches(null, "run-1", "session-1", "run-1"))
        assertFalse(sidecarIdentityMatches("session-1", null, "session-1", "run-1"))
        assertFalse(sidecarIdentityMatches(null, null, "session-1", "run-1"))
        assertFalse(sidecarIdentityMatches("other", "run-1", "session-1", "run-1"))
        assertFalse(sidecarIdentityMatches("session-1", "other", "session-1", "run-1"))
    }

    @Test
    fun healthCountersMustBeNonnegativeAndExact() {
        assertTrue(healthCountersExact(2, 1, 1, 1, 1L, 1L))
        assertFalse(healthCountersExact(2, 1, 0, 1, 1L, 1L))
        assertFalse(healthCountersExact(1, 1, 0, 2, 1L, 1L))
        assertFalse(healthCountersExact(-1, 0, 0, 0, null, null))
        assertFalse(healthCountersExact(1, 1, 0, 1, 9L, 1L))
        assertFalse(sidecarEvidenceTrusted(null, "session-1", "run-1", 1, 1L))
    }

    @Test
    fun quotedNumericAndNumericIdentitySidecarsCannotDecode() {
        val valid = sidecarJson()
        assertEquals(SessionEventLogHealthStatus.COMPLETE, decodeHealthSidecar(valid).health.status)
        assertEquals("session-1", decodeHealthSidecar(valid).sessionId)
        quotedNumericSidecars().forEach { raw ->
            assertTrue(raw, runCatching { decodeHealthSidecar(raw) }.isFailure)
        }
        val numericIdentity = sidecarJson(sessionId = "1", runId = "2")
            .replace("\"sessionId\":\"1\"", "\"sessionId\":1")
            .replace("\"runId\":\"2\"", "\"runId\":2")
        assertTrue(runCatching { decodeHealthSidecar(numericIdentity) }.isFailure)
    }

    @Test
    fun quotedNumericAndNumericIdentityEventsCannotDecode() {
        val valid = encodeEvent(sampleStoredEvent())
        assertEquals("1", decodeEvent(valid).sessionId)
        assertEquals(2L, decodeEvent(valid).sequence)
        assertEquals("1", decodeEvent(valid).payload.scalarFields()["phase"])
        assertEquals(listOf(1L), decodeEvent(valid).sourceEventSequences)
        quotedNumericEvents(valid).forEach { raw ->
            assertTrue(raw, runCatching { decodeEvent(raw) }.isFailure)
        }
    }

    @Test
    fun unsafeFirstFailureCodeIsRejected() {
        assertEquals("APPEND_FAILED", boundedSafeFailureCode("APPEND_FAILED"))
        assertEquals(null, boundedSafeFailureCode("../etc/passwd"))
        assertEquals(null, boundedSafeFailureCode("java.io.IOException: disk full at C:\\\\data"))
        assertEquals(null, boundedSafeFailureCode("X".repeat(65)))
        assertEquals(null, boundedSafeFailureCode(" "))
    }
}

private fun sidecarJson(
    sessionId: String = "session-1",
    runId: String = "run-1",
): String =
    """{"schemaVersion":1,"status":"COMPLETE","attemptedCount":1,"appendedCount":1,"failedCount":0,"lastPersistedSequence":1,"backendDurability":"DURABLE","recorderCoverageVersion":1,"sessionId":"$sessionId","runId":"$runId"}"""

private fun quotedNumericSidecars(): List<String> {
    val valid = sidecarJson()
    return listOf(
        valid.replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\""),
        valid.replace("\"attemptedCount\":1", "\"attemptedCount\":\"1\""),
        valid.replace("\"appendedCount\":1", "\"appendedCount\":\"1\""),
        valid.replace("\"failedCount\":0", "\"failedCount\":\"0\""),
        valid.replace("\"lastPersistedSequence\":1", "\"lastPersistedSequence\":\"1\""),
        valid.replace("\"recorderCoverageVersion\":1", "\"recorderCoverageVersion\":\"1\""),
        valid.replace("\"status\":\"COMPLETE\"", "\"status\":true"),
        valid.replace("\"backendDurability\":\"DURABLE\"", "\"backendDurability\":1"),
        valid.replace("\"failedCount\":0", "\"firstFailureCode\":1,\"failedCount\":0"),
    )
}

private fun sampleStoredEvent(): SessionEvent = SessionEvent(
    schemaVersion = EVENT_SCHEMA_VERSION,
    eventId = "event-1",
    sessionId = "1",
    runId = "2",
    requestId = "3",
    sequence = 2L,
    recordedAtEpochMillis = 100L,
    type = SessionEventType.RUN_FINISHED,
    payload = SessionEventPayload.RunLifecycle(phase = "1"),
    sourceEventSequences = listOf(1L),
)

private fun quotedNumericEvents(valid: String): List<String> = listOf(
    valid.replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\""),
    valid.replace("\"sequence\":2", "\"sequence\":\"2\""),
    valid.replace("\"recordedAtEpochMillis\":100", "\"recordedAtEpochMillis\":\"100\""),
    valid.replace("\"sessionId\":\"1\"", "\"sessionId\":1"),
    valid.replace("\"runId\":\"2\"", "\"runId\":2"),
    valid.replace("\"requestId\":\"3\"", "\"requestId\":3"),
    valid.replace("\"eventId\":\"event-1\"", "\"eventId\":1"),
    valid.replace("\"type\":\"RUN_FINISHED\"", "\"type\":1"),
    valid.replace("\"phase\":\"1\"", "\"phase\":1"),
    valid.replace("\"sourceEventSequences\":[1]", "\"sourceEventSequences\":[\"1\"]"),
    valid.replace("\"payload\":", "\"payload\":[],\"unused\":"),
    valid.replace("\"sourceEventSequences\":[1]", "\"sourceEventSequences\":{\"1\":1}"),
    valid.replace("\"kind\":\"RunLifecycle\"", "\"kind\":1"),
)

private fun event(): SessionEventAppend = SessionEventAppend(
    sessionId = "session-1",
    runId = "run-1",
    requestId = "request-1",
    type = SessionEventType.RUN_STARTED,
)
