package cc.noharry.agent.vfs

internal object VfsPerformanceBaselineFixture {
    const val ONE_MIB_PAGINATION_PAGES = 32
    const val ONE_MIB_PAGE_BYTES = 32_000
    const val ONE_MIB_PAGINATION_FULL_ENCODES = 32L

    const val SMALL_GREP_FILE_COUNT = 256
    const val SMALL_GREP_SAFE_WALKS = 2L
    const val SMALL_GREP_BINARY_SAMPLE_READS = 256L
    const val SMALL_GREP_READ_LINES = 256L

    const val LARGE_GREP_BYTES = 1_024_000
    const val LARGE_GREP_SAFE_WALKS = 2L
    const val LARGE_GREP_BINARY_SAMPLE_READS = 1L
    const val LARGE_GREP_READ_LINES = 1L
}
