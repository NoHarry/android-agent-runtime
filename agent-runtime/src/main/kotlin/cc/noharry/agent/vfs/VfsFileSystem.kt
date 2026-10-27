package cc.noharry.agent.vfs

import java.io.File

/** Resolves manifest source URIs to host-provided VFS sources. */
fun interface VfsSourceResolver {
    fun resolve(source: String): VfsSource?
}

/** Semantic-free virtual filesystem backed by a workspace manifest. */
class VfsFileSystem(
    private val manifest: VfsWorkspaceManifest,
    private val sourceResolver: VfsSourceResolver,
) {
    private val mountTable = VfsMountTable(manifest.mounts)

    suspend fun list(path: String): VfsListResult {
        val normalized = VfsPathResolver.normalize(path) ?: return VfsListResult.InvalidPath
        val entries = linkedMapOf<String, VfsEntry>()
        var foundDirectory = false
        for (resolved in mountTable.resolveAll(normalized.value)) {
            when (val result = resolved.source()?.list(resolved.relativePath)) {
                is VfsListResult.Entries -> {
                    foundDirectory = true
                    result.entries.forEach { entries.putIfAbsent(it.name, it) }
                }
                is VfsListResult.Error -> return result
                VfsListResult.InvalidPath -> return result
                VfsListResult.NotFound,
                null,
                -> Unit
            }
        }
        childMountEntries(normalized).forEach { entries.putIfAbsent(it.name, it) }
        return if (foundDirectory || entries.isNotEmpty()) {
            VfsListResult.Entries(entries.values.sortedBy { it.name })
        } else {
            VfsListResult.NotFound
        }
    }

    suspend fun read(path: String, offset: Long, maxBytes: Int): VfsReadResult {
        val normalized = VfsPathResolver.normalize(path) ?: return VfsReadResult.InvalidPath
        var unresolvedSource = false
        for (resolved in mountTable.resolveAll(normalized.value)) {
            val source = resolved.source()
            if (source == null) {
                unresolvedSource = true
                continue
            }
            when (val result = source.read(resolved.relativePath, offset, maxBytes)) {
                VfsReadResult.NotFound -> Unit
                else -> return result
            }
        }
        return if (unresolvedSource) VfsReadResult.ReadDenied else VfsReadResult.NotFound
    }

    suspend fun stat(path: String): VfsStatResult {
        val normalized = VfsPathResolver.normalize(path) ?: return VfsStatResult.InvalidPath
        var unresolvedSource = false
        for (resolved in mountTable.resolveAll(normalized.value)) {
            val source = resolved.source()
            if (source == null) {
                unresolvedSource = true
                continue
            }
            when (val result = source.stat(resolved.relativePath)) {
                VfsStatResult.NotFound -> Unit
                else -> return result
            }
        }
        return if (unresolvedSource) VfsStatResult.ReadDenied else VfsStatResult.NotFound
    }

    suspend fun write(
        path: String,
        content: String,
        mode: VfsWriteMode,
        expectedRevision: String? = null,
    ): VfsWriteResult {
        val normalized = VfsPathResolver.normalize(path) ?: return VfsWriteResult.InvalidPath
        for (resolved in writableResolutions(normalized) ?: return VfsWriteResult.WriteDenied) {
            val source = resolved.source() ?: continue
            when (val result = source.write(resolved.relativePath, content, mode, expectedRevision)) {
                VfsWriteResult.NotFound -> Unit
                else -> return result
            }
        }
        return VfsWriteResult.WriteDenied
    }

    suspend fun search(
        path: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        val normalized = VfsPathResolver.normalize(path) ?: return VfsSearchResult.InvalidPath
        if (pattern.isEmpty() || maxMatches < 0) return VfsSearchResult.InvalidPattern
        if (maxMatches == 0) return VfsSearchResult.Matches(emptyList())
        val matches = linkedMapOf<String, VfsSearchMatch>()
        var foundPath = false
        var scannedBytes = 0L
        for (resolved in mountTable.resolveVisible(normalized.value)) {
            val source = resolved.source() ?: continue
            val remaining = maxMatches - matches.size
            if (remaining <= 0) break
            val remainingScanBytes = maxScanBytes - scannedBytes
            if (remainingScanBytes < 0) return VfsSearchResult.BudgetExceeded
            val result = source.search(resolved.relativePath, pattern, remaining, remainingScanBytes)
            when (result) {
                is VfsSearchResult.Matches -> {
                    foundPath = true
                    scannedBytes += result.scannedBytes
                    if (scannedBytes > maxScanBytes) return VfsSearchResult.BudgetExceeded
                    result.matches.map { it.withMountTarget(resolved.mount.target) }.forEach { match ->
                        matches.putIfAbsent("${match.path}:${match.line}", match)
                    }
                }
                VfsSearchResult.NotFound -> Unit
                else -> return result
            }
        }
        return if (foundPath) {
            VfsSearchResult.Matches(matches.values.toList(), scannedBytes = scannedBytes)
        } else {
            VfsSearchResult.NotFound
        }
    }

    suspend fun deleteTree(path: String): Boolean {
        val normalized = VfsPathResolver.normalize(path) ?: return false
        val resolved = writableResolutions(normalized)?.firstOrNull() ?: return false
        val source = resolved.source() ?: return false
        return source.deleteTree(resolved.relativePath)
    }

    fun mounts(): List<VfsMount> = mountTable.mounts()

    fun withOverlay(mounts: List<VfsMount>, sources: Map<String, VfsSource>): VfsFileSystem =
        VfsFileSystem(
            manifest = manifest.copy(mounts = manifest.mounts + mounts),
            sourceResolver = VfsSourceResolver { source ->
                sources[source] ?: sourceResolver.resolve(source)
            },
        )

    private fun VfsResolvedMount.source(): VfsSource? =
        sourceResolver.resolve(mount.source)

    private fun writableResolutions(path: VfsPath): List<VfsResolvedMount>? {
        val matches = mountTable.resolveAll(path.value)
        if (matches.isEmpty()) return emptyList()
        if (matches.first().mount.mode != VfsMountMode.READ_WRITE) return null
        return matches.filter { it.mount.mode == VfsMountMode.READ_WRITE }
    }

    private suspend fun childMountEntries(path: VfsPath): List<VfsEntry> =
        manifest.mounts.mapNotNull { mount -> childEntryFor(path, mount) }

    /** A mount just below [parent] is a directory when its source lists entries (/mcp), else a file (/AGENTS.md). */
    private suspend fun childEntryFor(parent: VfsPath, mount: VfsMount): VfsEntry? {
        val target = mount.target
        if (target.value == parent.value) return null
        val parentPrefix = if (parent.value == ROOT_PATH) ROOT_PATH else parent.value + ROOT_PATH
        if (!target.value.startsWith(parentPrefix)) return null
        val child = target.value.removePrefix(parentPrefix).trim('/').substringBefore('/')
        if (child.isBlank()) return null
        val deeper = target.value.removePrefix(parentPrefix).trim('/').contains('/')
        val type = if (deeper || sourceResolver.resolve(mount.source)?.list("") is VfsListResult.Entries) {
            VFS_ENTRY_DIR
        } else {
            VFS_ENTRY_FILE
        }
        return VfsEntry(child, type)
    }

    private fun VfsSearchMatch.withMountTarget(target: VfsPath): VfsSearchMatch {
        val prefix = if (target.value == ROOT_PATH) "" else target.value.trimEnd('/')
        val suffix = path.trim('/')
        val fullPath = when {
            prefix.isBlank() && suffix.isBlank() -> ROOT_PATH
            prefix.isBlank() -> "/$suffix"
            suffix.isBlank() -> prefix
            else -> "$prefix/$suffix"
        }
        return copy(path = fullPath)
    }

    companion object {
        fun fromJson(jsonText: String, sourceResolver: VfsSourceResolver): VfsFileSystem =
            VfsFileSystem(
                manifest = VfsManifestLoader().parse(jsonText),
                sourceResolver = sourceResolver,
            )

        fun singleFileRoot(root: File): VfsFileSystem {
            val source = VfsFileSource(root)
            val mount = VfsMount(
                id = "workspace-root",
                source = SINGLE_FILE_ROOT_SOURCE,
                target = VfsPathResolver.normalize(ROOT_PATH) ?: error("Invalid root path."),
                mode = VfsMountMode.READ_WRITE,
                priority = 0,
            )
            val manifest = VfsWorkspaceManifest(
                schemaVersion = 1,
                vfsContractVersion = "1.0.0",
                bundleVersion = null,
                workspaceRoot = mount.target,
                mounts = listOf(mount),
            )
            return VfsFileSystem(manifest, VfsSourceResolver { source })
        }
    }
}

private const val ROOT_PATH = "/"
private const val SINGLE_FILE_ROOT_SOURCE = "file://workspace-root"
