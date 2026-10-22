package cc.noharry.agent.vfs.saf

import cc.noharry.agent.vfs.VfsSearchMatch
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal data class SafReadWindow(
    val bytes: ByteArray,
    val skippedBytes: Long,
    val hasMore: Boolean,
    val binary: Boolean,
    val bytesConsumed: Int = bytes.size,
) {
    fun observedTotalBytes(requestedOffset: Long, metadataLength: Long): Long {
        val observedEnd = if (skippedBytes < requestedOffset) skippedBytes else requestedOffset + bytesConsumed
        val streamLength = observedEnd + if (hasMore) 1L else 0L
        return maxOf(metadataLength.coerceAtLeast(0L), streamLength)
    }
}

internal data class SafSearchScan(
    val matches: List<VfsSearchMatch>,
    val scannedBytes: Long,
    val budgetExceeded: Boolean,
    val binary: Boolean,
)

internal fun readSafWindow(
    input: InputStream,
    offset: Long,
    maxBytes: Int,
): SafReadWindow {
    require(offset >= 0L) { "offset must not be negative." }
    require(maxBytes > 0) { "maxBytes must be positive." }
    val sample = input.readAtMost(SAF_BINARY_SAMPLE_BYTES)
    if (sample.any(::isBinaryByte)) {
        return SafReadWindow(ByteArray(0), minOf(offset, sample.size.toLong()), hasMore = false, binary = true)
    }
    val skipped = skipToOffset(input, sample, offset)
    if (skipped < offset) return SafReadWindow(ByteArray(0), skipped, hasMore = false, binary = false)
    val window = readWindowFromSampleAndStream(input, sample, offset, maxBytes)
    val aligned = window.bytes.alignUtf8Window(allowLeadingContinuation = offset > 0L)
    return SafReadWindow(
        bytes = aligned.bytes,
        skippedBytes = skipped,
        hasMore = window.sentinel != END_OF_STREAM || aligned.trailingBytesDropped > 0,
        binary = !aligned.valid || window.bytes.any(::isBinaryByte) || window.sentinel.isBinarySentinel(),
        bytesConsumed = aligned.leadingBytesDropped + aligned.bytes.size,
    )
}

private data class WindowBytes(
    val bytes: ByteArray,
    val sentinel: Int,
)

private fun skipToOffset(input: InputStream, sample: ByteArray, offset: Long): Long {
    if (offset <= sample.size.toLong()) return offset
    return sample.size + input.skipAtMost(offset - sample.size)
}

private fun readWindowFromSampleAndStream(
    input: InputStream,
    sample: ByteArray,
    offset: Long,
    maxBytes: Int,
): WindowBytes {
    val output = ByteArrayOutputStream(minOf(maxBytes, SAF_SEARCH_BUFFER_BYTES))
    var sampleIndex = offset.coerceAtMost(sample.size.toLong()).toInt()
    if (sampleIndex < sample.size) {
        val count = minOf(maxBytes, sample.size - sampleIndex)
        output.write(sample, sampleIndex, count)
        sampleIndex += count
    }
    if (output.size() < maxBytes) {
        val tail = input.readAtMost(maxBytes - output.size())
        output.write(tail)
    }
    val sentinel = when {
        output.size() < maxBytes -> END_OF_STREAM
        sampleIndex < sample.size -> sample[sampleIndex].toInt() and BYTE_MASK
        else -> input.read()
    }
    return WindowBytes(output.toByteArray(), sentinel)
}

internal fun scanSafTextStream(
    input: InputStream,
    path: String,
    pattern: String,
    maxMatches: Int,
    maxScanBytes: Long,
): SafSearchScan {
    require(pattern.isNotEmpty()) { "pattern must not be empty." }
    require(maxMatches >= 0) { "maxMatches must not be negative." }
    if (maxMatches == 0) return SafSearchScan(emptyList(), 0L, budgetExceeded = false, binary = false)
    if (maxScanBytes < 0L) return SafSearchScan(emptyList(), 0L, budgetExceeded = true, binary = false)

    val scanner = SafLineScanner(path, pattern, maxMatches)
    var scannedBytes = 0L
    val sampleLimit = minOf(SAF_BINARY_SAMPLE_BYTES.toLong(), maxScanBytes)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
    val sample = input.readAtMost(sampleLimit)
    scannedBytes += sample.size
    if (sample.any(::isBinaryByte)) {
        return SafSearchScan(emptyList(), scannedBytes, budgetExceeded = false, binary = true)
    }
    scanner.accept(sample, sample.size)
    if (sample.size < sampleLimit) {
        scanner.finish()
        return scanner.result(scannedBytes, budgetExceeded = false)
    }

    val buffer = ByteArray(SAF_SEARCH_BUFFER_BYTES)
    while (scannedBytes < maxScanBytes) {
        val remainingBudget = maxScanBytes - scannedBytes
        val requestedBytes = minOf(buffer.size.toLong(), remainingBudget).toInt()
        val read = input.readChunk(buffer, requestedBytes)
        if (read <= 0) {
            scanner.finish()
            return scanner.result(scannedBytes, budgetExceeded = false)
        }
        scannedBytes += read
        if (buffer.hasBinaryByte(read)) {
            return SafSearchScan(emptyList(), scannedBytes, budgetExceeded = false, binary = true)
        }
        scanner.accept(buffer, read)
    }
    if (scanner.hasEnoughMatches) {
        return scanner.result(scannedBytes, budgetExceeded = false)
    }
    val sentinel = input.read()
    if (sentinel == END_OF_STREAM) {
        scanner.finish()
        return scanner.result(scannedBytes, budgetExceeded = false)
    }
    scannedBytes += 1
    if (isBinaryByte(sentinel.toByte())) {
        return SafSearchScan(emptyList(), scannedBytes, budgetExceeded = true, binary = true)
    }
    return scanner.result(scannedBytes, budgetExceeded = true)
}

private class SafLineScanner(
    private val path: String,
    private val pattern: String,
    private val maxMatches: Int,
) {
    private val matches = mutableListOf<VfsSearchMatch>()
    private val lineBuffer = ByteArrayOutputStream()
    private var lineNumber = 1

    val hasEnoughMatches: Boolean
        get() = matches.size >= maxMatches

    fun accept(bytes: ByteArray, count: Int) {
        for (index in 0 until count) {
            val byte = bytes[index]
            if ((byte.toInt() and BYTE_MASK) == LF_BYTE) {
                emitLine()
                lineNumber += 1
            } else if (matches.size < maxMatches) {
                lineBuffer.write(byte.toInt())
            }
        }
    }

    fun finish() {
        if (lineBuffer.size() > 0) emitLine()
    }

    fun result(scannedBytes: Long, budgetExceeded: Boolean): SafSearchScan =
        SafSearchScan(matches.toList(), scannedBytes, budgetExceeded, binary = false)

    private fun emitLine() {
        val line = lineBuffer.toByteArray().stripTrailingCr().toString(Charsets.UTF_8)
        lineBuffer.reset()
        if (matches.size < maxMatches && line.contains(pattern, ignoreCase = true)) {
            matches += VfsSearchMatch(path = path, line = lineNumber, preview = line)
        }
    }
}

private fun ByteArray.hasBinaryByte(count: Int): Boolean {
    for (index in 0 until count) {
        if (isBinaryByte(this[index])) return true
    }
    return false
}

private fun InputStream.skipAtMost(maxBytes: Long): Long {
    var skippedBytes = 0L
    val discard = ByteArray(SAF_SEARCH_BUFFER_BYTES)
    while (skippedBytes < maxBytes) {
        val skipped = skip(maxBytes - skippedBytes)
        if (skipped > 0L) {
            skippedBytes += skipped
            continue
        }
        val read = readChunk(discard, minOf(discard.size.toLong(), maxBytes - skippedBytes).toInt())
        if (read <= 0) break
        skippedBytes += read
    }
    return skippedBytes
}

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    if (maxBytes <= 0) return ByteArray(0)
    val output = ByteArrayOutputStream(minOf(maxBytes, SAF_SEARCH_BUFFER_BYTES))
    val buffer = ByteArray(minOf(maxBytes, SAF_SEARCH_BUFFER_BYTES))
    var remaining = maxBytes
    while (remaining > 0) {
        val read = readChunk(buffer, minOf(buffer.size, remaining))
        if (read <= 0) break
        output.write(buffer, 0, read)
        remaining -= read
    }
    return output.toByteArray()
}

private fun InputStream.readChunk(buffer: ByteArray, requestedBytes: Int): Int {
    if (requestedBytes <= 0) return 0
    val read = read(buffer, 0, requestedBytes)
    if (read != 0) return read
    val single = read()
    if (single == END_OF_STREAM) return END_OF_STREAM
    buffer[0] = single.toByte()
    return 1
}

private fun ByteArray.stripTrailingCr(): ByteArray =
    if (isNotEmpty() && (last().toInt() and BYTE_MASK) == CR_BYTE) copyOf(size - 1) else this

private fun Int.isBinarySentinel(): Boolean =
    this != END_OF_STREAM && isBinaryByte(toByte())

internal fun isBinaryByte(byte: Byte): Boolean {
    val value = byte.toInt() and BYTE_MASK
    return value == 0 || (value < ASCII_SPACE && value !in TEXT_CONTROL_BYTES)
}

private data class Utf8AlignedWindow(
    val bytes: ByteArray,
    val leadingBytesDropped: Int,
    val trailingBytesDropped: Int,
    val valid: Boolean,
)

private fun ByteArray.alignUtf8Window(allowLeadingContinuation: Boolean): Utf8AlignedWindow {
    var start = 0
    while (start < size && this[start].isUtf8Continuation()) start += 1
    if (start > 0 && (!allowLeadingContinuation || start > MAX_UTF8_CONTINUATION_BYTES)) {
        return Utf8AlignedWindow(ByteArray(0), start, size - start, valid = false)
    }
    var index = start
    var completeEnd = start
    while (index < size) {
        val sequenceBytes = utf8SequenceBytes(this[index])
        if (sequenceBytes == 0) {
            return Utf8AlignedWindow(ByteArray(0), start, size - start, valid = false)
        }
        if (index + sequenceBytes > size) {
            break
        }
        if (!isValidUtf8Sequence(index, sequenceBytes)) {
            return Utf8AlignedWindow(ByteArray(0), start, size - start, valid = false)
        }
        index += sequenceBytes
        completeEnd = index
    }
    return Utf8AlignedWindow(
        bytes = copyOfRange(start, completeEnd),
        leadingBytesDropped = start,
        trailingBytesDropped = size - completeEnd,
        valid = true,
    )
}

private fun ByteArray.isValidUtf8Sequence(index: Int, sequenceBytes: Int): Boolean {
    if ((1 until sequenceBytes).any { offset -> !this[index + offset].isUtf8Continuation() }) return false
    if (sequenceBytes < 3) return true
    val lead = this[index].toInt() and BYTE_MASK
    val second = this[index + 1].toInt() and BYTE_MASK
    return when (lead) {
        0xE0 -> second >= 0xA0
        0xED -> second <= 0x9F
        0xF0 -> second >= 0x90
        0xF4 -> second <= 0x8F
        else -> true
    }
}

private fun utf8SequenceBytes(byte: Byte): Int = when (byte.toInt() and BYTE_MASK) {
    in 0x00..0x7F -> 1
    in 0xC2..0xDF -> 2
    in 0xE0..0xEF -> 3
    in 0xF0..0xF4 -> 4
    else -> 0
}

private fun Byte.isUtf8Continuation(): Boolean =
    (toInt() and UTF8_CONTINUATION_MASK) == UTF8_CONTINUATION_PREFIX

private const val SAF_BINARY_SAMPLE_BYTES = 512
internal const val SAF_SEARCH_BUFFER_BYTES = 8 * 1024
private const val ASCII_SPACE = 32
private const val BYTE_MASK = 0xFF
private const val LF_BYTE = 10
private const val CR_BYTE = 13
private const val END_OF_STREAM = -1
private const val UTF8_CONTINUATION_MASK = 0xC0
private const val UTF8_CONTINUATION_PREFIX = 0x80
private const val MAX_UTF8_CONTINUATION_BYTES = 3
private val TEXT_CONTROL_BYTES = setOf(9, 10, 13)
