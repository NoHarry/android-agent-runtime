@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionEventDecodeCacheTest {
    @Test
    fun identicalDiskContentIsDecodedOnceAndChangedContentIsRevalidated() {
        var reads = 0
        val cache = SessionEventDecodeCache(decode = { reads++; decodeEvent(it) })
        val line = encodeEvent(event("original"))
        val first = cache.read(line)
        repeat(100) { assertSame(first, cache.read(line)) }
        assertEquals(1, reads)
        assertEquals(SessionEventPayload.UserMessage("modified"), cache.read(encodeEvent(event("modified"))).payload)
        assertEquals(2, reads)
        assertThrows(IllegalStateException::class.java) { cache.read("invalid-json") }
    }

    @Test
    fun cachedEntriesAreEvictedWithinBothCharacterAndEntryBudgets() {
        val first = encodeEvent(event("first"))
        val second = encodeEvent(event("second"))
        val cache = SessionEventDecodeCache(maxChars = first.length + second.length - 1)
        cache.read(first)
        cache.read(second)
        assertNull(cache.cached(first))
        assertEquals(SessionEventPayload.UserMessage("second"), cache.cached(second)?.payload)
        val single = SessionEventDecodeCache(maxEntries = 1)
        single.read(first)
        single.read(second)
        assertNull(single.cached(first))
    }

    private fun event(text: String) = SessionEvent(
        schemaVersion = 2, eventId = "event", sessionId = "session", runId = "run", requestId = "request",
        sequence = 1, recordedAtEpochMillis = 100, type = SessionEventType.USER_MESSAGE,
        payload = SessionEventPayload.UserMessage(text), sourceEventSequences = emptyList(),
    )
}
