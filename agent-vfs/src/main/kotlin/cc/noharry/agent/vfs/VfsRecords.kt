package cc.noharry.agent.vfs

/** Directory entry visible inside the virtual workspace. */
data class VfsEntry(
    val name: String,
    val type: String,
    val revision: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null,
)

/** Extra source-provided metadata serialized by the runtime facade. */
data class VfsReadAttributes(
    val booleans: Map<String, Boolean> = emptyMap(),
    val strings: Map<String, String> = emptyMap(),
    val longs: Map<String, Long> = emptyMap(),
)

/** Text read result from a VFS source. */
sealed interface VfsReadResult {
    data class Found(
        val text: String,
        val bytesRead: Int,
        val totalBytes: Long,
        val revision: String? = null,
        val sizeBytes: Long? = totalBytes,
        val modifiedAtMillis: Long? = null,
        val attributes: VfsReadAttributes = VfsReadAttributes(),
    ) : VfsReadResult

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : VfsReadResult

    data object NotFound : VfsReadResult
    data object InvalidPath : VfsReadResult
    data object BinaryUnsupported : VfsReadResult
    data object ReadDenied : VfsReadResult
}

/** Semantic-free source metadata for freshness checks. */
data class VfsStat(
    val type: String,
    val revision: String? = null,
    val sizeBytes: Long? = null,
    val modifiedAtMillis: Long? = null,
)

/** Stat result from a VFS source. */
sealed interface VfsStatResult {
    data class Found(val stat: VfsStat) : VfsStatResult
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : VfsStatResult

    data object NotFound : VfsStatResult
    data object InvalidPath : VfsStatResult
    data object ReadDenied : VfsStatResult
}

/** Directory list result from a VFS source. */
sealed interface VfsListResult {
    data class Entries(val entries: List<VfsEntry>) : VfsListResult
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : VfsListResult

    data object NotFound : VfsListResult
    data object InvalidPath : VfsListResult
}

/** Text write mode for write_file. */
enum class VfsWriteMode {
    CREATE,
    OVERWRITE,
    APPEND,
    ATOMIC_REPLACE,
}

/** Write result from a VFS source. */
sealed interface VfsWriteResult {
    data class Written(
        val bytes: Long,
        val revision: String? = null,
        val sizeBytes: Long? = bytes,
        val modifiedAtMillis: Long? = null,
    ) : VfsWriteResult

    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : VfsWriteResult

    data object NotFound : VfsWriteResult
    data object InvalidPath : VfsWriteResult
    data object WriteDenied : VfsWriteResult
    data object Conflict : VfsWriteResult
    data object BinaryUnsupported : VfsWriteResult
}

/** Grep-style text match in VFS-relative coordinates. */
data class VfsSearchMatch(
    val path: String,
    val line: Int,
    val preview: String,
)

/** Search result from a VFS source. */
sealed interface VfsSearchResult {
    data class Matches(
        val matches: List<VfsSearchMatch>,
        val scannedBytes: Long = 0,
    ) : VfsSearchResult
    data class Error(
        val code: String,
        val message: String,
        val retryable: Boolean = false,
    ) : VfsSearchResult

    data object NotFound : VfsSearchResult
    data object InvalidPath : VfsSearchResult
    data object InvalidPattern : VfsSearchResult
    data object BudgetExceeded : VfsSearchResult
}

/** Host-provided file source behind one VFS mount. */
interface VfsSource {
    suspend fun list(relativePath: String): VfsListResult

    suspend fun stat(relativePath: String): VfsStatResult =
        VfsStatResult.NotFound

    suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult

    suspend fun write(
        relativePath: String,
        content: String,
        mode: VfsWriteMode,
        expectedRevision: String? = null,
    ): VfsWriteResult =
        VfsWriteResult.WriteDenied

    suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult

    suspend fun deleteTree(relativePath: String): Boolean = false
}

const val VFS_ENTRY_FILE = "file"
const val VFS_ENTRY_DIR = "dir"
