package cc.noharry.agent.vfs

/** In-memory text-backed VFS source useful for host DB/cache adapters and tests. */
class VfsMemorySource(
    initialFiles: Map<String, String> = emptyMap(),
    private val quotaBytes: Long = Long.MAX_VALUE,
) : VfsSource {
    private val files = linkedMapOf<String, String>()
    private val versions = linkedMapOf<String, Long>()
    private val byteCache = VfsTextByteCache(VFS_COUNTER_MEMORY_READ_FULL_ENCODES)
    private var nextVersion = 1L

    init {
        require(quotaBytes >= 0) { "quotaBytes must be non-negative." }
        initialFiles.forEach { (path, content) ->
            normalizeRelative(path)?.let {
                files[it] = content
                versions[it] = allocateVersion()
            }
        }
        require(totalBytes() <= quotaBytes) { "initialFiles exceed quotaBytes." }
    }

    override suspend fun list(relativePath: String): VfsListResult {
        val dir = normalizeRelative(relativePath) ?: return VfsListResult.InvalidPath
        val prefix = dir.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty()
        val entries = files.keys
            .filter { key -> dir.isBlank() || key == dir || key.startsWith(prefix) }
            .mapNotNull { key -> childEntry(dir, key) }
            .distinctBy { it.name }
            .sortedBy { it.name }
        return if (entries.isEmpty() && !hasDirectory(dir)) {
            VfsListResult.NotFound
        } else {
            VfsListResult.Entries(entries)
        }
    }

    override suspend fun stat(relativePath: String): VfsStatResult {
        val path = normalizeRelative(relativePath) ?: return VfsStatResult.InvalidPath
        val text = files[path]
        if (text != null) {
            return VfsStatResult.Found(
                VfsStat(
                    type = VFS_ENTRY_FILE,
                    revision = revision(path),
                    sizeBytes = text.utf8EncodedSize().toLong(),
                ),
            )
        }
        return if (hasDirectory(path)) {
            VfsStatResult.Found(VfsStat(type = VFS_ENTRY_DIR))
        } else {
            VfsStatResult.NotFound
        }
    }

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (offset < 0 || maxBytes <= 0) return VfsReadResult.InvalidPath
        val path = normalizeRelative(relativePath) ?: return VfsReadResult.InvalidPath
        val text = files[path] ?: return VfsReadResult.NotFound
        val bytes = byteCache.bytes(path, versions.getValue(path), text)
        val start = offset.coerceAtMost(bytes.size.toLong()).toInt()
        val end = (start + maxBytes).coerceAtMost(bytes.size)
        return VfsReadResult.Found(
            text = bytes.copyOfRange(start, end).toString(Charsets.UTF_8),
            bytesRead = end - start,
            totalBytes = bytes.size.toLong(),
            revision = revision(path),
            sizeBytes = bytes.size.toLong(),
        )
    }

    override suspend fun write(
        relativePath: String,
        content: String,
        mode: VfsWriteMode,
        expectedRevision: String?,
    ): VfsWriteResult {
        if (isBinaryText(content)) return VfsWriteResult.BinaryUnsupported
        val path = normalizeRelative(relativePath) ?: return VfsWriteResult.InvalidPath
        if (path.isBlank()) return VfsWriteResult.WriteDenied
        if (mode == VfsWriteMode.CREATE && path in files) return VfsWriteResult.Conflict
        if (expectedRevision != null && revision(path) != expectedRevision) {
            return VfsWriteResult.Conflict
        }
        val updated = when (mode) {
            VfsWriteMode.APPEND -> files[path].orEmpty() + content
            VfsWriteMode.CREATE,
            VfsWriteMode.OVERWRITE,
            VfsWriteMode.ATOMIC_REPLACE,
            -> content
        }
        if (!fitsQuota(path, updated)) {
            return VfsWriteResult.Error(ERROR_INVALID_ARGUMENT, "Memory quota exceeded.")
        }
        files[path] = updated
        versions[path] = allocateVersion()
        byteCache.invalidate(path)
        val bytes = updated.toByteArray(Charsets.UTF_8).size.toLong()
        return VfsWriteResult.Written(
            bytes = bytes,
            revision = revision(path),
            sizeBytes = bytes,
        )
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        if (pattern.isEmpty() || maxMatches < 0) return VfsSearchResult.InvalidPattern
        val root = normalizeRelative(relativePath) ?: return VfsSearchResult.InvalidPath
        val selected = files.filterKeys { key -> key == root || root.isBlank() || key.startsWith("$root/") }
        if (selected.isEmpty()) return VfsSearchResult.NotFound
        val matcher = VfsSearchMatcher(pattern)
        var scanned = 0L
        val matches = mutableListOf<VfsSearchMatch>()
        selected.forEach { (path, text) ->
            scanned += text.utf8EncodedSize()
            if (scanned > maxScanBytes) return VfsSearchResult.BudgetExceeded
            text.lineSequence().forEachIndexed { index, line ->
                if (matches.size < maxMatches && matcher.matches(line)) {
                    matches += VfsSearchMatch(path, index + 1, line)
                }
            }
        }
        return VfsSearchResult.Matches(matches, scannedBytes = scanned)
    }

    override suspend fun deleteTree(relativePath: String): Boolean {
        val root = normalizeRelative(relativePath) ?: return false
        val prefix = root.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty()
        files.keys.filter { key -> key == root || key.startsWith(prefix) }.toList().forEach { key ->
            files.remove(key)
            versions.remove(key)
            byteCache.invalidate(key)
        }
        return true
    }

    fun snapshot(): Map<String, String> = files.toMap()

    private fun fitsQuota(path: String, updated: String): Boolean {
        val currentBytes = files[path]?.byteSize() ?: 0L
        val nextBytes = updated.byteSize()
        return totalBytes() - currentBytes + nextBytes <= quotaBytes
    }

    private fun totalBytes(): Long =
        files.values.sumOf { it.byteSize() }

    private fun hasDirectory(dir: String): Boolean {
        val prefix = dir.takeIf { it.isNotBlank() }?.let { "$it/" }.orEmpty()
        return files.keys.any { key -> dir.isBlank() || key.startsWith(prefix) }
    }

    private fun childEntry(dir: String, path: String): VfsEntry? {
        val relative = if (dir.isBlank()) path else path.removePrefix("$dir/")
        val child = relative.substringBefore('/').takeIf { it.isNotBlank() } ?: return null
        val type = if ('/' in relative) VFS_ENTRY_DIR else VFS_ENTRY_FILE
        val childPath = if (dir.isBlank()) child else "$dir/$child"
        return VfsEntry(
            name = child,
            type = type,
            revision = childPath.takeIf { type == VFS_ENTRY_FILE }?.let(::revision),
            sizeBytes = files[childPath]?.utf8EncodedSize()?.toLong(),
        )
    }

    private fun allocateVersion(): Long =
        nextVersion++

    private fun revision(path: String): String? =
        versions[path]?.let { "memory:$it" }
}

private fun normalizeRelative(path: String): String? =
    VfsPathResolver.normalize(if (path.startsWith("/")) path else "/$path")
        ?.value
        ?.trim('/')

private fun isBinaryText(value: String): Boolean =
    value.toByteArray(Charsets.UTF_8).any { byte ->
        val code = byte.toInt() and BYTE_MASK
        code == 0 || (code < ASCII_SPACE && code !in TEXT_CONTROL_BYTES)
    }

private fun String.byteSize(): Long =
    utf8EncodedSize()

private const val ERROR_INVALID_ARGUMENT = "EINVAL"
private const val ASCII_SPACE = 32
private const val BYTE_MASK = 0xFF
private val TEXT_CONTROL_BYTES = setOf(9, 10, 13)
