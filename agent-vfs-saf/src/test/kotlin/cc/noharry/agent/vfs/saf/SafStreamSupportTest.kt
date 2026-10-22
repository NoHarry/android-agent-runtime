package cc.noharry.agent.vfs.saf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream

class SafStreamSupportTest {
    @Test
    fun readWindowConsumesOnlyOffsetWindowAndSentinel() {
        val input = TrackingInputStream("0123456789".toByteArray())

        val window = readSafWindow(input, offset = 3L, maxBytes = 4)

        assertEquals("3456", window.bytes.toString(Charsets.UTF_8))
        assertEquals(3L, window.skippedBytes)
        assertTrue(window.hasMore)
        assertFalse(window.binary)
        assertEquals(10L, input.consumedBytes)
        assertEquals(10L, window.observedTotalBytes(requestedOffset = 3L, metadataLength = 10L))
    }

    @Test
    fun readWindowStopsWhenOffsetIsBeyondEnd() {
        val input = TrackingInputStream("short".toByteArray())

        val window = readSafWindow(input, offset = 20L, maxBytes = 4)

        assertTrue(window.bytes.isEmpty())
        assertEquals(5L, window.skippedBytes)
        assertFalse(window.hasMore)
        assertEquals(5L, window.observedTotalBytes(requestedOffset = 20L, metadataLength = 0L))
        assertEquals(5L, input.consumedBytes)
    }

    @Test
    fun readWindowChecksSentinelForBinaryContent() {
        val input = TrackingInputStream(byteArrayOf('t'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 't'.code.toByte(), 0))

        val window = readSafWindow(input, offset = 0L, maxBytes = 4)

        assertFalse(window.hasMore)
        assertTrue(window.binary)
        assertEquals(5L, input.consumedBytes)
    }

    @Test
    fun readWindowRejectsBinarySampleBeforeRequestedOffset() {
        val content = byteArrayOf('t'.code.toByte(), 0) + "safe tail".toByteArray()
        val input = TrackingInputStream(content)

        val window = readSafWindow(input, offset = 5L, maxBytes = 4)

        assertTrue(window.binary)
        assertTrue(window.bytes.isEmpty())
    }

    @Test
    fun readWindowDoesNotConsumeLargeFileTail() {
        val content = ByteArray(10 * 1024 * 1024) { 'x'.code.toByte() }
        val input = TrackingInputStream(content)

        val window = readSafWindow(input, offset = 1_000_000L, maxBytes = 32)

        assertEquals(32, window.bytes.size)
        assertTrue(window.hasMore)
        assertEquals(1_000_033L, input.consumedBytes)
    }

    @Test
    fun readWindowDropsIncompleteTrailingUtf8CodePoint() {
        val input = TrackingInputStream("你好".toByteArray(Charsets.UTF_8))

        val window = readSafWindow(input, offset = 0L, maxBytes = 5)

        assertEquals("你", window.bytes.toString(Charsets.UTF_8))
        assertEquals(3, window.bytesConsumed)
        assertTrue(window.hasMore)
        assertFalse(window.binary)
    }

    @Test
    fun readWindowConsumesLeadingContinuationBytesFromMidCodePointOffset() {
        val input = TrackingInputStream("你好".toByteArray(Charsets.UTF_8))

        val window = readSafWindow(input, offset = 1L, maxBytes = 5)

        assertEquals("好", window.bytes.toString(Charsets.UTF_8))
        assertEquals(5, window.bytesConsumed)
        assertFalse(window.hasMore)
        assertFalse(window.binary)
    }

    @Test
    fun searchStreamsInBoundedChunksAndCountsActualBytes() {
        val content = buildString {
            append("a".repeat(SAF_SEARCH_BUFFER_BYTES - 2))
            append("\nneedle line\n")
            append("tail".repeat(SAF_SEARCH_BUFFER_BYTES))
        }.toByteArray()
        val input = TrackingInputStream(content)

        val scan = scanSafTextStream(
            input = input,
            path = "large.txt",
            pattern = "NEEDLE",
            maxMatches = 2,
            maxScanBytes = content.size.toLong(),
        )

        assertFalse(scan.budgetExceeded)
        assertFalse(scan.binary)
        assertEquals(1, scan.matches.size)
        assertEquals("large.txt", scan.matches.single().path)
        assertEquals(2, scan.matches.single().line)
        assertEquals(content.size.toLong(), scan.scannedBytes)
        assertEquals(content.size.toLong(), input.consumedBytes)
        assertTrue(input.maxRequestedRead <= SAF_SEARCH_BUFFER_BYTES)
    }

    @Test
    fun searchReadsOneSentinelBeyondByteBudgetThenStops() {
        val input = TrackingInputStream("x".repeat(1_000).toByteArray())

        val scan = scanSafTextStream(
            input = input,
            path = "budget.txt",
            pattern = "missing",
            maxMatches = 1,
            maxScanBytes = 100L,
        )

        assertTrue(scan.budgetExceeded)
        assertEquals(101L, scan.scannedBytes)
        assertEquals(101L, input.consumedBytes)
    }

    @Test
    fun searchKeepsSatisfiedMatchesWhenRemainingFileExceedsBudget() {
        val budget = 1_024L
        val input = TrackingInputStream(
            ("needle first\n" + "x".repeat((budget * 2).toInt())).toByteArray(),
        )

        val scan = scanSafTextStream(
            input = input,
            path = "large-match.txt",
            pattern = "needle",
            maxMatches = 1,
            maxScanBytes = budget,
        )

        assertFalse(scan.budgetExceeded)
        assertFalse(scan.binary)
        assertEquals(1, scan.matches.size)
        assertEquals(budget, scan.scannedBytes)
        assertEquals(budget, input.consumedBytes)
    }

    @Test
    fun searchRejectsBinarySampleWithoutMaterializingWholeFile() {
        val content = byteArrayOf('n'.code.toByte(), 'e'.code.toByte(), 0) + ByteArray(10_000) { 'x'.code.toByte() }
        val input = TrackingInputStream(content)

        val scan = scanSafTextStream(
            input = input,
            path = "binary.dat",
            pattern = "ne",
            maxMatches = 1,
            maxScanBytes = content.size.toLong(),
        )

        assertTrue(scan.binary)
        assertFalse(scan.budgetExceeded)
        assertTrue(scan.matches.isEmpty())
        assertEquals(512L, scan.scannedBytes)
        assertEquals(512L, input.consumedBytes)
    }

    @Test
    fun searchDiscardsEarlierMatchesWhenBinaryByteAppearsLate() {
        val content = "needle first\n".toByteArray() +
            ByteArray(SAF_SEARCH_BUFFER_BYTES) { 'x'.code.toByte() } +
            byteArrayOf(0)
        val input = TrackingInputStream(content)

        val scan = scanSafTextStream(
            input = input,
            path = "late-binary.dat",
            pattern = "needle",
            maxMatches = 1,
            maxScanBytes = content.size.toLong(),
        )

        assertTrue(scan.binary)
        assertTrue(scan.matches.isEmpty())
        assertEquals(content.size.toLong(), scan.scannedBytes)
    }
}

private class TrackingInputStream(
    private val bytes: ByteArray,
) : InputStream() {
    private var position = 0
    var consumedBytes: Long = 0L
        private set
    var maxRequestedRead: Int = 0
        private set

    override fun read(): Int {
        if (position >= bytes.size) return -1
        consumedBytes += 1
        return bytes[position++].toInt() and 0xFF
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        maxRequestedRead = maxOf(maxRequestedRead, length)
        if (position >= bytes.size) return -1
        val count = minOf(length, bytes.size - position)
        bytes.copyInto(buffer, offset, position, position + count)
        position += count
        consumedBytes += count
        return count
    }

    override fun skip(byteCount: Long): Long {
        val count = minOf(byteCount, (bytes.size - position).toLong()).toInt()
        position += count
        consumedBytes += count
        return count.toLong()
    }
}
