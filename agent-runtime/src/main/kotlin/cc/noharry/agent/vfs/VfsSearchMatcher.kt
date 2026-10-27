package cc.noharry.agent.vfs

internal class VfsSearchMatcher(pattern: String) {
    private val literal = pattern

    fun matches(line: String): Boolean =
        line.indexOf(literal, ignoreCase = true) >= 0
}

internal class VfsTextByteCache(
    private val encodeCounter: String,
) {
    private val entries = linkedMapOf<String, Entry>()

    fun bytes(path: String, version: Long, text: String): ByteArray {
        val cached = entries[path]
        if (cached != null && cached.version == version) return cached.bytes
        return encode(path = path, version = version, text = text, keepText = false)
    }

    fun bytes(path: String, text: String): ByteArray {
        val cached = entries[path]
        if (cached != null && cached.text == text) return cached.bytes
        return encode(path = path, version = null, text = text, keepText = true)
    }

    fun invalidate(path: String) {
        entries.remove(path)
    }

    fun clear() {
        entries.clear()
    }

    private fun encode(path: String, version: Long?, text: String, keepText: Boolean): ByteArray {
        VfsPerformanceCounters.increment(encodeCounter)
        val bytes = text.toByteArray(Charsets.UTF_8)
        entries[path] = Entry(version = version, text = if (keepText) text else null, bytes = bytes)
        return bytes
    }

    private data class Entry(
        val version: Long?,
        val text: String?,
        val bytes: ByteArray,
    )
}

internal fun String.utf8EncodedSize(): Long {
    var size = 0L
    var index = 0
    while (index < length) {
        val code = this[index].code
        when {
            code <= ONE_BYTE_UTF8_MAX -> size += 1
            code <= TWO_BYTE_UTF8_MAX -> size += 2
            Character.isHighSurrogate(this[index]) &&
                index + 1 < length &&
                Character.isLowSurrogate(this[index + 1]) -> {
                size += 4
                index += 1
            }
            Character.isSurrogate(this[index]) -> size += 1
            else -> size += 3
        }
        index += 1
    }
    return size
}

private const val ONE_BYTE_UTF8_MAX = 0x7F
private const val TWO_BYTE_UTF8_MAX = 0x7FF
