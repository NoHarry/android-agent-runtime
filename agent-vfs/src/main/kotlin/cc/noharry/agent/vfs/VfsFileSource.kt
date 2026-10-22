package cc.noharry.agent.vfs

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.io.SequenceInputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

/** File-backed VFS source rooted at one canonical host directory. */
class VfsFileSource(root: File) : VfsSource {
    private val canonicalRoot = root.canonicalFile
    private val rootIsFile = canonicalRoot.isFile

    override suspend fun list(relativePath: String): VfsListResult {
        val target = resolve(relativePath) ?: return VfsListResult.InvalidPath
        if (!target.exists() || !target.isDirectory) return VfsListResult.NotFound
        return VfsListResult.Entries(
            target.listFiles().orEmpty().map { file ->
                VfsEntry(
                    name = file.name,
                    type = if (file.isDirectory) VFS_ENTRY_DIR else VFS_ENTRY_FILE,
                    revision = file.revisionToken(),
                    sizeBytes = file.sizeMetadata(),
                    modifiedAtMillis = file.lastModified().takeIf { it > 0L },
                )
            },
        )
    }

    override suspend fun stat(relativePath: String): VfsStatResult {
        val target = resolve(relativePath) ?: return VfsStatResult.InvalidPath
        if (!target.exists()) return VfsStatResult.NotFound
        return VfsStatResult.Found(target.stat())
    }

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (offset < 0 || maxBytes <= 0) return VfsReadResult.InvalidPath
        val target = resolve(relativePath) ?: return VfsReadResult.InvalidPath
        if (!target.exists() || !target.isFile) return VfsReadResult.NotFound
        val window = readTextWindow(target, offset, maxBytes) ?: return VfsReadResult.BinaryUnsupported
        return VfsReadResult.Found(
            text = window.text,
            bytesRead = window.bytesRead,
            totalBytes = target.length(),
            revision = target.revisionToken(),
            sizeBytes = target.length(),
            modifiedAtMillis = target.lastModified().takeIf { it > 0L },
        )
    }

    override suspend fun write(
        relativePath: String,
        content: String,
        mode: VfsWriteMode,
        expectedRevision: String?,
    ): VfsWriteResult {
        if (isBinaryText(content)) return VfsWriteResult.BinaryUnsupported
        val target = resolveForWrite(relativePath) ?: return VfsWriteResult.InvalidPath
        if (target.exists() && target.isDirectory) return VfsWriteResult.WriteDenied
        if (mode == VfsWriteMode.CREATE && target.exists()) return VfsWriteResult.Conflict
        if (expectedRevision != null && target.revisionToken() != expectedRevision) {
            return VfsWriteResult.Conflict
        }
        if (!prepareParent(target)) return VfsWriteResult.WriteDenied
        when (mode) {
            VfsWriteMode.CREATE,
            VfsWriteMode.OVERWRITE,
            -> target.writeText(content, Charsets.UTF_8)
            VfsWriteMode.APPEND -> target.appendText(content, Charsets.UTF_8)
            VfsWriteMode.ATOMIC_REPLACE -> atomicReplace(target, content)
        }
        return VfsWriteResult.Written(
            bytes = target.length(),
            revision = target.revisionToken(),
            sizeBytes = target.length(),
            modifiedAtMillis = target.lastModified().takeIf { it > 0L },
        )
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        if (pattern.isEmpty() || maxMatches < 0) return VfsSearchResult.InvalidPattern
        val target = resolve(relativePath) ?: return VfsSearchResult.InvalidPath
        if (!target.exists()) return VfsSearchResult.NotFound
        val matcher = VfsSearchMatcher(pattern)
        var scanned = 0L
        val matches = mutableListOf<VfsSearchMatch>()
        for (file in safeSearchFiles(target)) {
            if (matches.size >= maxMatches) break
            val remainingBudget = maxScanBytes - scanned
            if (remainingBudget < 0L) return VfsSearchResult.BudgetExceeded
            val result = scanFile(
                file = file,
                matcher = matcher,
                maxMatches = maxMatches,
                maxScanBytes = remainingBudget,
                matches = matches,
            )
            scanned += result.scannedBytes
            if (result.budgetExceeded || scanned > maxScanBytes) {
                return VfsSearchResult.BudgetExceeded
            }
        }
        return VfsSearchResult.Matches(matches.toList(), scannedBytes = scanned)
    }

    override suspend fun deleteTree(relativePath: String): Boolean {
        val target = resolveForWrite(relativePath) ?: return false
        if (hasRedirectingSegment(target)) return false
        return !target.exists() || target.deleteRecursively()
    }

    private fun resolve(relativePath: String): File? =
        if (rootIsFile) {
            canonicalRoot.takeIf { relativePath.isBlank() }
        } else {
            VfsPathResolver.resolveInside(canonicalRoot, toAbsolutePath(relativePath))
        }

    private fun resolveForWrite(relativePath: String): File? {
        if (rootIsFile) {
            return canonicalRoot.takeIf { relativePath.isBlank() }
        }
        val target = canonicalRoot.resolve(relativePath).absoluteFile
        val canonicalTarget = target.canonicalFile
        return canonicalTarget.takeIf {
            it.toPath().startsWith(canonicalRoot.toPath()) && !hasRedirectingSegment(target)
        }
    }

    private fun prepareParent(target: File): Boolean {
        val parent = target.parentFile ?: return false
        if (hasRedirectingSegment(parent)) return false
        return parent.exists() || parent.mkdirs()
    }

    private fun atomicReplace(target: File, content: String) {
        val parent = target.parentFile ?: error("Target parent was prevalidated.")
        val temp = File.createTempFile(".${target.name}.", ".tmp", parent)
        try {
            temp.writeText(content, Charsets.UTF_8)
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (failure: Exception) {
            if (temp.exists()) temp.delete()
            throw failure
        }
    }

    private fun safeSearchFiles(target: File): Sequence<File> {
        VfsPerformanceCounters.increment(VFS_COUNTER_FILE_SAFE_WALKS)
        return if (target.isFile) {
            sequenceOf(target)
        } else {
            target.walkTopDown()
                .onEnter { directory -> directory == target || !isRedirectingExistingPath(directory) }
                .filter { it.isFile }
                .filterNot { Files.isSymbolicLink(it.toPath()) }
        }
    }

    private fun scanFile(
        file: File,
        matcher: VfsSearchMatcher,
        maxMatches: Int,
        matches: MutableList<VfsSearchMatch>,
        maxScanBytes: Long,
    ): FileScanResult {
        val path = if (rootIsFile) "" else file.relativeTo(canonicalRoot).invariantSeparatorsPath
        VfsPerformanceCounters.increment(VFS_COUNTER_FILE_SEARCH_FILE_OPENS)
        file.inputStream().buffered().use { input ->
            val sample = input.readAtMost(BINARY_SAMPLE_BYTES)
            VfsPerformanceCounters.increment(VFS_COUNTER_FILE_BINARY_SAMPLE_READS)
            if (sample.any(::isBinaryByte)) {
                val scanned = sample.size.toLong()
                return FileScanResult(
                    scannedBytes = scanned.coerceAtMost(maxScanBytes + 1),
                    budgetExceeded = scanned > maxScanBytes,
                )
            }
            SequenceInputStream(ByteArrayInputStream(sample), input).use { textInput ->
                return scanTextStream(
                    input = textInput,
                    path = path,
                    matcher = matcher,
                    maxMatches = maxMatches,
                    maxScanBytes = maxScanBytes,
                    matches = matches,
                )
            }
        }
    }

    private fun hasRedirectingSegment(target: File): Boolean {
        val rootPath = canonicalRoot.toPath()
        val targetPath = target.absoluteFile.toPath().normalize()
        if (rootIsFile) return target.canonicalFile != canonicalRoot
        if (!targetPath.startsWith(rootPath)) return true
        var current = canonicalRoot
        rootPath.relativize(targetPath).forEach { segment ->
            current = current.resolve(segment.toString())
            if (isRedirectingExistingPath(current)) return true
        }
        return false
    }

    private fun isRedirectingExistingPath(path: File): Boolean {
        val nioPath = path.toPath()
        if (!Files.exists(nioPath, LinkOption.NOFOLLOW_LINKS)) return false
        return Files.isSymbolicLink(nioPath) || path.canonicalFile != path.absoluteFile
    }

    private fun File.stat(): VfsStat =
        VfsStat(
            type = if (isDirectory) VFS_ENTRY_DIR else VFS_ENTRY_FILE,
            revision = revisionToken(),
            sizeBytes = sizeMetadata(),
            modifiedAtMillis = lastModified().takeIf { it > 0L },
        )
}

private data class VfsTextWindow(
    val text: String,
    val bytesRead: Int,
)

private data class FileScanResult(
    val scannedBytes: Long,
    val budgetExceeded: Boolean,
)

private fun toAbsolutePath(relativePath: String): String =
    if (relativePath.isBlank()) "/" else "/${relativePath.trim('/')}"

private fun File.revisionToken(): String? =
    takeIf { exists() }?.let { "${lastModified()}:${length()}" }

private fun File.sizeMetadata(): Long? =
    takeIf { exists() && isFile }?.length()

private fun readTextWindow(file: File, offset: Long, maxBytes: Int): VfsTextWindow? =
    RandomAccessFile(file, "r").use { input ->
        VfsPerformanceCounters.increment(VFS_COUNTER_FILE_READ_OPENS)
        VfsPerformanceCounters.increment(VFS_COUNTER_FILE_BINARY_SAMPLE_READS)
        val sample = input.readAtMost(BINARY_SAMPLE_BYTES)
        if (sample.any(::isBinaryByte)) return null
        if (offset >= input.length()) return VfsTextWindow(text = "", bytesRead = 0)
        input.seek(offset)
        VfsPerformanceCounters.increment(VFS_COUNTER_FILE_READ_SEEKS)
        val bytes = input.readAtMost(maxBytes)
        VfsTextWindow(text = bytes.toString(Charsets.UTF_8), bytesRead = bytes.size)
    }

private fun scanTextStream(
    input: InputStream,
    path: String,
    matcher: VfsSearchMatcher,
    maxMatches: Int,
    maxScanBytes: Long,
    matches: MutableList<VfsSearchMatch>,
): FileScanResult {
    val lineBuffer = ByteArrayOutputStream()
    val readBuffer = ByteArray(TEXT_SCAN_BUFFER_BYTES)
    var scanned = 0L
    var lineNumber = 1
    while (true) {
        val read = input.read(readBuffer)
        if (read <= 0) break
        for (index in 0 until read) {
            scanned += 1
            if (scanned > maxScanBytes) {
                return FileScanResult(scannedBytes = scanned, budgetExceeded = true)
            }
            val byte = readBuffer[index]
            if ((byte.toInt() and BYTE_MASK) == LF_BYTE) {
                if (emitLine(path, lineNumber, lineBuffer, matcher, maxMatches, matches)) {
                    return FileScanResult(scannedBytes = scanned, budgetExceeded = false)
                }
                lineBuffer.reset()
                lineNumber += 1
            } else {
                lineBuffer.write(byte.toInt())
            }
        }
    }
    if (lineBuffer.size() > 0) {
        emitLine(path, lineNumber, lineBuffer, matcher, maxMatches, matches)
    }
    return FileScanResult(scannedBytes = scanned, budgetExceeded = false)
}

private fun emitLine(
    path: String,
    lineNumber: Int,
    lineBuffer: ByteArrayOutputStream,
    matcher: VfsSearchMatcher,
    maxMatches: Int,
    matches: MutableList<VfsSearchMatch>,
): Boolean {
    val line = lineBuffer.toByteArray().stripTrailingCr().toString(Charsets.UTF_8)
    if (matches.size < maxMatches && matcher.matches(line)) {
        matches += VfsSearchMatch(path = path, line = lineNumber, preview = line)
    }
    return matches.size >= maxMatches
}

private fun isBinaryText(value: String): Boolean =
    value.toByteArray(Charsets.UTF_8).any(::isBinaryByte)

private fun isBinaryByte(byte: Byte): Boolean {
    val value = byte.toInt() and BYTE_MASK
    return value == 0 || (value < ASCII_SPACE && value !in TEXT_CONTROL_BYTES)
}

private fun InputStream.readAtMost(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var bytesRead = 0
    while (bytesRead < maxBytes) {
        val read = read(buffer, bytesRead, maxBytes - bytesRead)
        if (read <= 0) break
        bytesRead += read
    }
    return if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
}

private fun RandomAccessFile.readAtMost(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var bytesRead = 0
    while (bytesRead < maxBytes) {
        val read = read(buffer, bytesRead, maxBytes - bytesRead)
        if (read <= 0) break
        bytesRead += read
    }
    return if (bytesRead == buffer.size) buffer else buffer.copyOf(bytesRead)
}

private fun ByteArray.stripTrailingCr(): ByteArray =
    if (isNotEmpty() && (last().toInt() and BYTE_MASK) == CR_BYTE) copyOf(size - 1) else this

private const val BINARY_SAMPLE_BYTES = 512
private const val TEXT_SCAN_BUFFER_BYTES = 8192
private const val ASCII_SPACE = 32
private const val BYTE_MASK = 0xFF
private const val LF_BYTE = 10
private const val CR_BYTE = 13
private val TEXT_CONTROL_BYTES = setOf(9, 10, 13)
