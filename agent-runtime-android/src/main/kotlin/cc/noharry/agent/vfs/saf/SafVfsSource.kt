package cc.noharry.agent.vfs.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import cc.noharry.agent.vfs.VFS_ENTRY_DIR
import cc.noharry.agent.vfs.VFS_ENTRY_FILE
import cc.noharry.agent.vfs.VfsEntry
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsSearchMatch
import cc.noharry.agent.vfs.VfsSearchResult
import cc.noharry.agent.vfs.VfsSource
import cc.noharry.agent.vfs.VfsStat
import cc.noharry.agent.vfs.VfsStatResult
import cc.noharry.agent.vfs.VfsWriteMode
import cc.noharry.agent.vfs.VfsWriteResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Storage Access Framework backed VFS source for host-selected tree URIs. */
class SafVfsSource private constructor(
    private val contentResolver: ContentResolver,
    private val root: DocumentFile,
) : VfsSource {
    override suspend fun list(relativePath: String): VfsListResult = withContext(Dispatchers.IO) {
        try {
            val directory = resolve(relativePath) ?: return@withContext VfsListResult.InvalidPath
            if (!directory.exists() || !directory.isDirectory) return@withContext VfsListResult.NotFound
            VfsListResult.Entries(directory.listFiles().mapNotNull { it.entry() }.sortedBy { it.name })
        } catch (error: SecurityException) {
            VfsListResult.Error(SAF_PERMISSION_REVOKED, error.message ?: "SAF permission revoked.", retryable = true)
        }
    }

    override suspend fun stat(relativePath: String): VfsStatResult = withContext(Dispatchers.IO) {
        try {
            val document = resolve(relativePath) ?: return@withContext VfsStatResult.InvalidPath
            if (!document.exists()) return@withContext VfsStatResult.NotFound
            VfsStatResult.Found(document.stat())
        } catch (error: SecurityException) {
            VfsStatResult.Error(SAF_PERMISSION_REVOKED, error.message ?: "SAF permission revoked.", retryable = true)
        }
    }

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult =
        withContext(Dispatchers.IO) {
            try {
                if (offset < 0 || maxBytes <= 0) return@withContext VfsReadResult.InvalidPath
                val document = resolve(relativePath) ?: return@withContext VfsReadResult.InvalidPath
                if (!document.exists() || !document.isFile) return@withContext VfsReadResult.NotFound
                readWindow(document, offset, maxBytes)
            } catch (error: SecurityException) {
                VfsReadResult.Error(SAF_PERMISSION_REVOKED, error.message ?: "SAF permission revoked.", retryable = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: RuntimeException) {
                VfsReadResult.Error(SAF_IO_FAILED, error.message ?: "SAF read failed.", retryable = true)
            }
        }

    override suspend fun write(
        relativePath: String,
        content: String,
        mode: VfsWriteMode,
        expectedRevision: String?,
    ): VfsWriteResult = withContext(Dispatchers.IO) {
        try {
            if (content.toByteArray(Charsets.UTF_8).any(::isBinaryByte)) {
                return@withContext VfsWriteResult.BinaryUnsupported
            }
            val target = writableDocument(relativePath, mode) ?: return@withContext VfsWriteResult.InvalidPath
            if (expectedRevision != null && target.revisionToken() != expectedRevision) {
                return@withContext VfsWriteResult.Conflict
            }
            val outputMode = if (mode == VfsWriteMode.APPEND) SAF_MODE_APPEND else SAF_MODE_TRUNCATE
            contentResolver.openOutputStream(target.uri, outputMode)?.use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
            } ?: return@withContext VfsWriteResult.WriteDenied
            VfsWriteResult.Written(
                bytes = target.length(),
                revision = target.revisionToken(),
                sizeBytes = target.length(),
                modifiedAtMillis = target.lastModified().takeIf { it > 0L },
            )
        } catch (error: SecurityException) {
            VfsWriteResult.Error(SAF_PERMISSION_REVOKED, error.message ?: "SAF permission revoked.", retryable = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            VfsWriteResult.Error(SAF_IO_FAILED, error.message ?: "SAF write failed.", retryable = true)
        }
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult = withContext(Dispatchers.IO) {
        try {
            if (pattern.isEmpty() || maxMatches < 0) return@withContext VfsSearchResult.InvalidPattern
            val start = resolve(relativePath) ?: return@withContext VfsSearchResult.InvalidPath
            if (!start.exists()) return@withContext VfsSearchResult.NotFound
            scanDocuments(start, relativePath, pattern, maxMatches, maxScanBytes)
        } catch (error: SecurityException) {
            VfsSearchResult.Error(SAF_PERMISSION_REVOKED, error.message ?: "SAF permission revoked.", retryable = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeException) {
            VfsSearchResult.Error(SAF_IO_FAILED, error.message ?: "SAF search failed.", retryable = true)
        }
    }

    override suspend fun deleteTree(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        resolve(relativePath)?.takeIf { it != root }?.delete() ?: false
    }

    private fun resolve(relativePath: String): DocumentFile? {
        val segments = SafVfsPath.segments(relativePath) ?: return null
        return segments.fold(root as DocumentFile?) { current, segment ->
            current?.findFile(segment)
        }
    }

    private fun writableDocument(relativePath: String, mode: VfsWriteMode): DocumentFile? {
        val segments = SafVfsPath.segments(relativePath) ?: return null
        val fileName = segments.lastOrNull() ?: return null
        val parent = resolveOrCreateDirectory(segments.dropLast(1)) ?: return null
        val existing = parent.findFile(fileName)
        if (existing != null) {
            if (existing.isDirectory || mode == VfsWriteMode.CREATE) return null
            return existing
        }
        return parent.createFile(TEXT_MIME_TYPE, fileName)
    }

    private fun resolveOrCreateDirectory(segments: List<String>): DocumentFile? =
        segments.fold(root as DocumentFile?) { current, segment ->
            current?.findFile(segment) ?: current?.createDirectory(segment)
        }

    private fun readWindow(document: DocumentFile, offset: Long, maxBytes: Int): VfsReadResult {
        val metadataLength = document.length()
        val window = contentResolver.openInputStream(document.uri)?.use { input ->
            readSafWindow(input, offset, maxBytes)
        } ?: SafReadWindow(ByteArray(0), 0L, hasMore = false, binary = false)
        if (window.binary) return VfsReadResult.BinaryUnsupported
        val totalBytes = window.observedTotalBytes(offset, metadataLength)
        return VfsReadResult.Found(
            text = window.bytes.toString(Charsets.UTF_8),
            bytesRead = window.bytesConsumed,
            totalBytes = totalBytes,
            revision = document.revisionToken(),
            sizeBytes = metadataLength.takeIf { it > 0L } ?: totalBytes,
            modifiedAtMillis = document.lastModified().takeIf { it > 0L },
        )
    }

    private fun scanDocuments(
        start: DocumentFile,
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        val matches = mutableListOf<VfsSearchMatch>()
        var scannedBytes = 0L
        for ((path, document) in start.searchFiles(relativePath.trim('/'))) {
            if (matches.size >= maxMatches) break
            val remainingBudget = maxScanBytes - scannedBytes
            if (remainingBudget < 0L) return VfsSearchResult.BudgetExceeded
            val scan = contentResolver.openInputStream(document.uri)?.use { input ->
                scanSafTextStream(
                    input = input,
                    path = path,
                    pattern = pattern,
                    maxMatches = maxMatches - matches.size,
                    maxScanBytes = remainingBudget,
                )
            } ?: SafSearchScan(emptyList(), 0L, budgetExceeded = false, binary = false)
            scannedBytes += scan.scannedBytes
            if (scan.budgetExceeded || scannedBytes > maxScanBytes) return VfsSearchResult.BudgetExceeded
            if (!scan.binary) matches += scan.matches
        }
        return VfsSearchResult.Matches(matches.toList(), scannedBytes = scannedBytes)
    }

    private fun DocumentFile.searchFiles(path: String): Sequence<Pair<String, DocumentFile>> =
        if (isFile) {
            sequenceOf(path to this)
        } else {
            listFiles().asSequence().flatMap { child ->
                val childPath = listOf(path, child.name.orEmpty()).filter { it.isNotBlank() }.joinToString("/")
                child.searchFiles(childPath)
            }
        }

    companion object {
        /** Returns null when the tree URI is no longer resolvable by DocumentFile. */
        fun fromTreeUri(context: Context, treeUri: Uri): SafVfsSource? {
            val appContext = context.applicationContext
            val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
            return SafVfsSource(appContext.contentResolver, root)
        }
    }
}

internal object SafVfsPath {
    fun segments(relativePath: String): List<String>? {
        val normalized = relativePath.trim('/')
        if (normalized.isBlank()) return emptyList()
        if (normalized.contains('\\')) return null
        return normalized.split('/').map { segment ->
            if (segment.isBlank() || segment == "." || segment == "..") return null
            segment
        }
    }
}

private fun DocumentFile.entry(): VfsEntry? {
    val name = name ?: return null
    return VfsEntry(
        name = name,
        type = if (isDirectory) VFS_ENTRY_DIR else VFS_ENTRY_FILE,
        revision = revisionToken(),
        sizeBytes = length().takeIf { isFile },
        modifiedAtMillis = lastModified().takeIf { it > 0L },
    )
}

private fun DocumentFile.stat(): VfsStat =
    VfsStat(
        type = if (isDirectory) VFS_ENTRY_DIR else VFS_ENTRY_FILE,
        revision = revisionToken(),
        sizeBytes = length().takeIf { isFile },
        modifiedAtMillis = lastModified().takeIf { it > 0L },
    )

private fun DocumentFile.revisionToken(): String? =
    takeIf { exists() }?.let { "${lastModified()}:${length()}" }

private const val SAF_PERMISSION_REVOKED = "SAF_PERMISSION_REVOKED"
private const val SAF_IO_FAILED = "SAF_IO_FAILED"
private const val SAF_MODE_APPEND = "wa"
private const val SAF_MODE_TRUNCATE = "wt"
private const val TEXT_MIME_TYPE = "text/plain"
