package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsPerformanceBaselineTest {
    @Test
    fun benchOneMibPaginationBaselineFixtureRecordsPreOptimizationCost() {
        assertEquals(32, VfsPerformanceBaselineFixture.ONE_MIB_PAGINATION_PAGES)
        assertEquals(32_000, VfsPerformanceBaselineFixture.ONE_MIB_PAGE_BYTES)
        assertEquals(
            VfsPerformanceBaselineFixture.ONE_MIB_PAGINATION_PAGES.toLong(),
            VfsPerformanceBaselineFixture.ONE_MIB_PAGINATION_FULL_ENCODES,
        )
    }

    @Test
    fun benchSmallFileGrepBaselineFixtureRecordsPreOptimizationCost() {
        assertEquals(256, VfsPerformanceBaselineFixture.SMALL_GREP_FILE_COUNT)
        assertEquals(2L, VfsPerformanceBaselineFixture.SMALL_GREP_SAFE_WALKS)
        assertEquals(
            VfsPerformanceBaselineFixture.SMALL_GREP_FILE_COUNT.toLong(),
            VfsPerformanceBaselineFixture.SMALL_GREP_BINARY_SAMPLE_READS,
        )
        assertEquals(
            VfsPerformanceBaselineFixture.SMALL_GREP_FILE_COUNT.toLong(),
            VfsPerformanceBaselineFixture.SMALL_GREP_READ_LINES,
        )
    }

    @Test
    fun benchLargeFileGrepBaselineFixtureRecordsPreOptimizationCost() {
        assertTrue(VfsPerformanceBaselineFixture.LARGE_GREP_BYTES >= 1_024_000)
        assertEquals(2L, VfsPerformanceBaselineFixture.LARGE_GREP_SAFE_WALKS)
        assertEquals(1L, VfsPerformanceBaselineFixture.LARGE_GREP_BINARY_SAMPLE_READS)
        assertEquals(1L, VfsPerformanceBaselineFixture.LARGE_GREP_READ_LINES)
    }
}
