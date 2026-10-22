package cc.noharry.agent.runtime

import java.io.File
import java.io.InputStream

internal data class TextWindow(
    val text: String,
    val bytesRead: Int,
)

internal fun readTextWindow(file: File, offset: Long, maxBytes: Int): TextWindow =
    file.inputStream().use { input ->
        val skipped = input.skip(offset)
        if (skipped < offset) {
            return TextWindow(text = "", bytesRead = 0)
        }
        val bytes = input.readAtMost(maxBytes)
        TextWindow(text = bytes.toString(Charsets.UTF_8), bytesRead = bytes.size)
    }

internal fun isBinaryFile(file: File): Boolean {
    if (!file.isFile) {
        return false
    }
    val bytes = file.inputStream().use { it.readAtMost(BINARY_SAMPLE_BYTES) }
    return bytes.any { byte ->
        val value = byte.toInt() and BYTE_MASK
        value == 0 || (value < ASCII_SPACE && value !in TEXT_CONTROL_BYTES)
    }
}

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    if (maxBytes <= 0) {
        return ByteArray(0)
    }
    val buffer = ByteArray(maxBytes)
    var bytesRead = 0
    while (bytesRead < maxBytes) {
        val read = read(buffer, bytesRead, maxBytes - bytesRead)
        if (read <= 0) {
            break
        }
        bytesRead += read
    }
    return if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
}

internal const val MAX_READ_CHARS = 32_000
internal const val MAX_GREP_SCAN_BYTES = 256_000L
internal const val MAX_GREP_PATTERN_CHARS = 256
internal const val MAX_WORKSPACE_WRITE_FILE_BYTES = 256 * 1024L
internal const val MAX_WORKSPACE_WRITE_RUN_BYTES = 1024 * 1024L
internal const val MAX_WORKSPACE_WRITE_CALLS = 64
private const val BINARY_SAMPLE_BYTES = 512
private const val ASCII_SPACE = 32
private const val BYTE_MASK = 0xFF
private val TEXT_CONTROL_BYTES = setOf(9, 10, 13)

internal class WorkspaceWriteBudget {
    private var calls = 0
    private var writtenBytes = 0L

    fun tryConsume(fileBytes: Long, operationBytes: Long): Boolean {
        if (fileBytes > MAX_WORKSPACE_WRITE_FILE_BYTES) {
            return false
        }
        if (calls + 1 > MAX_WORKSPACE_WRITE_CALLS) {
            return false
        }
        if (writtenBytes + operationBytes > MAX_WORKSPACE_WRITE_RUN_BYTES) {
            return false
        }
        calls += 1
        writtenBytes += operationBytes
        return true
    }
}
