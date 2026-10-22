package cc.noharry.agent.vfs

internal object VfsPerformanceCounters {
    private val values = linkedMapOf<String, Long>()

    fun reset() {
        synchronized(values) {
            values.clear()
        }
    }

    fun increment(name: String, amount: Long = 1L) {
        synchronized(values) {
            values[name] = values.getOrDefault(name, 0L) + amount
        }
    }

    fun snapshot(): Map<String, Long> =
        synchronized(values) {
            values.toMap()
        }
}

internal const val VFS_COUNTER_MEMORY_READ_FULL_ENCODES = "memory.read.full_encodes"
internal const val VFS_COUNTER_MEMORY_SEARCH_FULL_ENCODES = "memory.search.full_encodes"
internal const val VFS_COUNTER_ROOM_READ_FULL_ENCODES = "room.read.full_encodes"
internal const val VFS_COUNTER_ROOM_SEARCH_FULL_ENCODES = "room.search.full_encodes"
internal const val VFS_COUNTER_FILE_READ_OPENS = "file.read.opens"
internal const val VFS_COUNTER_FILE_READ_SEEKS = "file.read.seeks"
internal const val VFS_COUNTER_FILE_SAFE_WALKS = "file.search.safe_walks"
internal const val VFS_COUNTER_FILE_CANONICAL_CHECKS = "file.search.canonical_checks"
internal const val VFS_COUNTER_FILE_BINARY_SAMPLE_READS = "file.search.binary_sample_reads"
internal const val VFS_COUNTER_FILE_READ_LINES = "file.search.read_lines_calls"
internal const val VFS_COUNTER_FILE_SEARCH_FILE_OPENS = "file.search.file_opens"
