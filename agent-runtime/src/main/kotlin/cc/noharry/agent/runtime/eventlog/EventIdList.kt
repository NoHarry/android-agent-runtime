package cc.noharry.agent.runtime.eventlog

import java.io.File

/** The ids a [FileBackedSessionEventLog] instance has seen, seeded once from [PersistedEventIdList] or every stream. */
internal class EventIdIndex {
    val ids = HashSet<String>()
    var seeded: Boolean = false
    var generation: Long = 0L
    var seedCount: Int = 0
    var fullScanCount: Int = 0
}

/**
 * Every persisted event id, one per line, each batch followed by `#<generation>`: a new log instance reads this instead
 * of decoding every stream. It is trusted only when its last marker equals the log's eventId generation, so a missing,
 * torn or stale list (or one another writer did not keep) sends the seed back to the full decode, which rewrites it.
 * Callers hold the log lock.
 */
internal class PersistedEventIdList(private val location: () -> File) {
    /** The ids when the list ends with [generation]'s marker; null when absent, torn or out of date. */
    fun read(generation: Long): List<String>? {
        val file = location()
        if (!existsNoFollow(file)) return null
        rejectLink(file.toPath())
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        if (!text.endsWith("\n")) return null
        val lines = text.dropLast(1).split('\n')
        if (lines.last() != "$EVENT_ID_LIST_MARKER$generation") return null
        return lines.filterNot { it.startsWith(EVENT_ID_LIST_MARKER) }
    }

    /**
     * One batch and the generation it brings the log to. Only an existing list grows: a batch alone never starts one,
     * since it would read as complete. An id the line format cannot hold drops the list instead.
     */
    fun append(eventIds: List<String>, generation: Long) {
        val file = location()
        if (!existsNoFollow(file)) return
        if (eventIds.any { !it.isListableEventId() }) return drop(file)
        appendForcedLine(file, (eventIds + "$EVENT_ID_LIST_MARKER$generation").joinToString("\n"))
    }

    /** The whole list after a full decode; dropped when an id cannot be listed, so no older list outlives it. */
    fun rewrite(ids: Collection<String>, generation: Long) {
        val file = location()
        if (ids.any { !it.isListableEventId() }) return drop(file)
        writeForcedText(file, (ids + "$EVENT_ID_LIST_MARKER$generation").joinToString("\n") + "\n")
    }

    private fun drop(file: File) {
        if (!existsNoFollow(file)) return
        rejectLink(file.toPath())
        check(file.delete()) { "EventLog eventId list could not be dropped" }
    }
}

private fun String.isListableEventId(): Boolean =
    isNotEmpty() && '\n' !in this && '\r' !in this && !startsWith(EVENT_ID_LIST_MARKER)

/** Every persisted event id, one per line, each batch followed by `#<generation>`; read instead of every stream. */
internal const val EVENT_ID_LIST_FILE = ".event-ids"
private const val EVENT_ID_LIST_MARKER = "#"
