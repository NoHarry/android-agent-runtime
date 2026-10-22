@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEvent
import java.util.Collections

/**
 * Bounded per-reader decode reuse, keyed by the exact content freshly read from disk.
 * File/path/checksum/stream validation still runs. Never trust mtime as content identity.
 * Owned exclusively by the enclosing EventLog lock; returned payloads are immutable.
 */
internal class SessionEventDecodeCache(
    private val maxChars: Int = DECODE_CACHE_CHARS,
    private val maxEntries: Int = DECODE_CACHE_ENTRIES,
    private val decode: (String) -> SessionEvent = ::decodeEvent,
) {
    private val entries = LinkedHashMap<String, SessionEvent>(INITIAL_CAPACITY, LOAD_FACTOR, true)
    private var cachedChars = 0

    fun cached(line: String): SessionEvent? = entries[line]

    fun read(line: String): SessionEvent {
        entries[line]?.let { return it }
        val decoded = decode(line)
        val frozen = decoded.copy(
            payload = decoded.payload.frozenCopy(),
            sourceEventSequences = Collections.unmodifiableList(decoded.sourceEventSequences.toList()),
        )
        if (line.length <= maxChars && maxEntries > 0) {
            entries[line] = frozen
            cachedChars += line.length
            while (cachedChars > maxChars || entries.size > maxEntries) {
                val oldest = entries.entries.iterator()
                cachedChars -= oldest.next().key.length
                oldest.remove()
            }
        }
        return frozen
    }
}

private const val DECODE_CACHE_CHARS = 2 * 1024 * 1024
private const val DECODE_CACHE_ENTRIES = 2_048
private const val INITIAL_CAPACITY = 16
private const val LOAD_FACTOR = 0.75f
