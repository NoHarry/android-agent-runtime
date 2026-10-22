@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventLogDurability
import cc.noharry.agent.kernel.api.SessionEventLogHealth
import cc.noharry.agent.kernel.api.SessionEventQuery
import java.util.UUID

internal class InMemorySessionEventLog(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
) : SessionEventLog {
    private val lock = Any()
    private val events = mutableListOf<SessionEvent>()
    private val streams = linkedMapOf<EventStreamKey, MutableList<SessionEvent>>()
    private val eventIds = mutableSetOf<String>()
    private val liveHealth = linkedMapOf<EventStreamKey, MutableStreamHealth>()
    private val acceptedListeners = SessionEventLogListeners()

    override fun append(event: SessionEventAppend): SessionEvent {
        val stored = synchronized(lock) {
            validateAppendIdentity(event)
            validatePayload(event.payload)
            val key = EventStreamKey(event.sessionId, event.runId)
            val tracker = liveHealth.getOrPut(key) { MutableStreamHealth(SessionEventLogDurability.VOLATILE) }
            tracker.noteAttempt()
            try {
                val stream = streams.getOrPut(key) { mutableListOf() }
                val sequence = stream.size.toLong() + FIRST_EVENT_SEQUENCE
                validateCausalSources(event.sourceEventSequences, stream, sequence)
                val eventId = eventIdFactory().also(::validateEventId)
                val written = event.toStoredEvent(eventId, sequence, nowEpochMillis())
                eventIds += eventId
                stream += written
                events += written
                tracker.noteSuccess(written.sequence)
                written
            } catch (failure: RuntimeException) {
                tracker.noteFailure(safeFailureCode(failure))
                throw failure
            }
        }
        acceptedListeners.notifyAccepted(stored)
        return stored
    }

    override fun subscribe(listener: (SessionEvent) -> Unit): AutoCloseable =
        acceptedListeners.subscribe(listener)

    override fun query(query: SessionEventQuery): List<SessionEvent> = synchronized(lock) {
        validateQuery(query)
        val runId = query.runId
        val requestId = query.requestId
        val afterSequenceExclusive = query.afterSequenceExclusive
        val limit = query.limit
        events.asSequence()
            .filter { it.sessionId == query.sessionId }
            .filter { runId == null || it.runId == runId }
            .filter { requestId == null || it.requestId == requestId }
            .filter { query.types.isEmpty() || it.type in query.types }
            .filter { afterSequenceExclusive == null || it.sequence > afterSequenceExclusive }
            .let { matches -> limit?.let(matches::take) ?: matches }
            .toList()
    }

    override fun replay(sessionId: String, runId: String): List<SessionEvent> = synchronized(lock) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
        streams[EventStreamKey(sessionId, runId)].orEmpty().toList()
    }

    override fun durability(): SessionEventLogDurability = SessionEventLogDurability.VOLATILE

    override fun health(sessionId: String, runId: String): SessionEventLogHealth = synchronized(lock) {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(runId.isNotBlank()) { "runId must not be blank" }
        liveHealth[EventStreamKey(sessionId, runId)]?.snapshot()
            ?: SessionEventLogHealth.unknown(SessionEventLogDurability.VOLATILE)
    }

    private fun validateEventId(eventId: String) {
        require(eventId.isNotBlank()) { "eventId factory returned a blank id" }
        check(eventId !in eventIds) { "eventId factory returned a duplicate id" }
    }
}
