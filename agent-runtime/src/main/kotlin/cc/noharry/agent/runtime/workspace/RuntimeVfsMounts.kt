package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.core.tools.MaterializerContext
import cc.noharry.agent.kernel.core.tools.MaterializerListResult
import cc.noharry.agent.kernel.core.tools.MaterializerReadResult
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_MATERIALIZER_READS
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_MATERIALIZER_SEARCH_FILES
import cc.noharry.agent.kernel.core.trace.RUNTIME_COUNTER_OVERLAY_ATTACHES
import cc.noharry.agent.kernel.core.trace.RuntimeVfsPerformanceCounters
import cc.noharry.agent.vfs.VfsEntry
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsMount
import cc.noharry.agent.vfs.VfsMountMode
import cc.noharry.agent.vfs.VfsPathResolver
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsSearchMatch
import cc.noharry.agent.vfs.VfsSearchResult
import cc.noharry.agent.vfs.VfsSource

internal class RuntimeVfsMounts(
    private val materializer: VirtualFileMaterializer,
) {
    private val materializedCache = RuntimeMaterializedCache()

    fun attach(base: VfsFileSystem, context: MaterializerContext): VfsFileSystem {
        RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_OVERLAY_ATTACHES)
        return base.withOverlay(runtimeMounts(), runtimeSources(context))
    }

    private fun runtimeSources(context: MaterializerContext): Map<String, VfsSource> {
        return runtimeMounts().associate { mount ->
            mount.source to MaterializerVfsSource(mount.target.value, materializer, context, materializedCache)
        }
    }

    private fun runtimeMounts(): List<VfsMount> =
        materializer.runtimeMountTargets().map { target ->
            runtimeMount(
                id = "runtime-${target.trim('/').ifBlank { "root" }}",
                source = "runtime://$target",
                target = target,
            )
    }
}

private class MaterializerVfsSource(
    private val targetPath: String,
    private val materializer: VirtualFileMaterializer,
    private val context: MaterializerContext,
    private val cache: RuntimeMaterializedCache,
) : VfsSource {
    override suspend fun list(relativePath: String): VfsListResult =
        when (val result = materializer.list(fullPath(relativePath), context)) {
            is MaterializerListResult.Entries -> VfsListResult.Entries(
                result.entries.map { VfsEntry(it.name, it.type) },
            )
            is MaterializerListResult.Error -> VfsListResult.Error(result.code, result.message, result.retryable)
            MaterializerListResult.NotFound,
            MaterializerListResult.NotMaterialized,
            -> VfsListResult.NotFound
        }

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (offset < 0 || maxBytes <= 0) return VfsReadResult.InvalidPath
        val path = fullPath(relativePath)
        return when (val result = cachedRead(path)) {
            is MaterializerReadResult.Found -> found(path, result, offset, maxBytes)
            is MaterializerReadResult.Error -> VfsReadResult.Error(result.code, result.message, result.retryable)
            MaterializerReadResult.NotFound,
            MaterializerReadResult.NotMaterialized,
            -> VfsReadResult.NotFound
        }
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        if (pattern.isEmpty() || maxMatches < 0) return VfsSearchResult.InvalidPattern
        if (maxMatches == 0) return VfsSearchResult.Matches(emptyList())
        val files = mutableListOf<MaterializedSearchFile>()
        return when (val collected = collectFiles(relativePath, files)) {
            is MaterializedSearchCollectResult.Error ->
                VfsSearchResult.Error(collected.code, collected.message, collected.retryable)
            MaterializedSearchCollectResult.InvalidPath -> VfsSearchResult.InvalidPath
            MaterializedSearchCollectResult.NotFound -> VfsSearchResult.NotFound
            MaterializedSearchCollectResult.Found -> searchFiles(files, pattern, maxMatches, maxScanBytes)
        }
    }

    private suspend fun cachedRead(path: String): MaterializerReadResult =
        cache.read(path, context, materializer.cacheMetadata(path, context)) {
            RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_MATERIALIZER_READS)
            materializer.read(path, context)
        }

    private suspend fun collectFiles(
        relativePath: String,
        files: MutableList<MaterializedSearchFile>,
    ): MaterializedSearchCollectResult {
        val path = fullPath(relativePath)
        when (val read = cachedRead(path)) {
            is MaterializerReadResult.Found -> {
                RuntimeVfsPerformanceCounters.increment(RUNTIME_COUNTER_MATERIALIZER_SEARCH_FILES)
                files += MaterializedSearchFile(
                    fullPath = path,
                    relativePath = normalizeRelativeSearchPath(relativePath),
                    content = read.content,
                    cacheMetadata = read.cacheMetadata,
                )
                return MaterializedSearchCollectResult.Found
            }
            is MaterializerReadResult.Error -> return MaterializedSearchCollectResult.Error(
                read.code,
                read.message,
                read.retryable,
            )
            MaterializerReadResult.NotFound,
            MaterializerReadResult.NotMaterialized,
            -> Unit
        }
        return when (val listed = materializer.list(path, context)) {
            is MaterializerListResult.Entries -> {
                listed.entries.forEach { entry ->
                    val child = joinRelativeSearchPath(relativePath, entry.name)
                    val result = collectFiles(child, files)
                    if (result is MaterializedSearchCollectResult.Error ||
                        result == MaterializedSearchCollectResult.InvalidPath
                    ) {
                        return result
                    }
                }
                if (files.isEmpty()) MaterializedSearchCollectResult.NotFound else MaterializedSearchCollectResult.Found
            }
            is MaterializerListResult.Error -> MaterializedSearchCollectResult.Error(
                listed.code,
                listed.message,
                listed.retryable,
            )
            MaterializerListResult.NotFound,
            MaterializerListResult.NotMaterialized,
            -> MaterializedSearchCollectResult.NotFound
        }
    }

    private fun searchFiles(
        files: List<MaterializedSearchFile>,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        val matcher = MaterializedSearchPattern(pattern)
        var scanned = 0L
        val matches = mutableListOf<VfsSearchMatch>()
        files.forEach { file ->
            scanned += cache.bytes(file.fullPath, context, file.cacheMetadata, file.content).size
            if (scanned > maxScanBytes) return VfsSearchResult.BudgetExceeded
            file.content.lineSequence().forEachIndexed { index, line ->
                if (matches.size < maxMatches && matcher.matches(line)) {
                    matches += VfsSearchMatch(file.relativePath, index + 1, line)
                }
            }
            if (matches.size >= maxMatches) return VfsSearchResult.Matches(matches, scannedBytes = scanned)
        }
        return VfsSearchResult.Matches(matches, scannedBytes = scanned)
    }

    private fun found(
        fullPath: String,
        result: MaterializerReadResult.Found,
        offset: Long,
        maxBytes: Int,
    ): VfsReadResult.Found {
        val bytes = cache.bytes(fullPath, context, result)
        val start = offset.coerceAtMost(bytes.size.toLong()).toInt()
        val end = (start + maxBytes).coerceAtMost(bytes.size)
        return VfsReadResult.Found(
            text = bytes.copyOfRange(start, end).toString(Charsets.UTF_8),
            bytesRead = end - start,
            totalBytes = bytes.size.toLong(),
            attributes = result.attributes(),
        )
    }

    private fun fullPath(relativePath: String): String {
        val relative = relativePath.trim('/')
        return if (relative.isBlank()) targetPath else "${targetPath.trimEnd('/')}/$relative"
    }
}

private fun runtimeMount(id: String, source: String, target: String): VfsMount =
    VfsMount(
        id = id,
        source = source,
        target = VfsPathResolver.normalize(target) ?: error("Invalid runtime VFS target: $target"),
        mode = VfsMountMode.READ_ONLY,
        priority = RUNTIME_MOUNT_FALLBACK_PRIORITY,
    )

private const val RUNTIME_MOUNT_FALLBACK_PRIORITY = -100
