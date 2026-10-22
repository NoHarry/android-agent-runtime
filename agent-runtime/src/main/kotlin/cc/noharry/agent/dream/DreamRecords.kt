package cc.noharry.agent.dream

import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.runtime.MemoryWorkspace
import cc.noharry.agent.vfs.VFS_ENTRY_FILE
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsWriteMode
import cc.noharry.agent.vfs.VfsWriteResult
import java.io.File
import cc.noharry.agent.kernel.core.completed
import cc.noharry.agent.kernel.core.failed

/**
 * Candidate Dream worker API scaffold. Exact API names remain subject to API freeze.
 */
interface DreamWorker {
    suspend fun run(request: DreamRequest): DreamResult
}

/**
 * Candidate Dream execution request. Prefer [virtualWorkspace]; File roots are
 * compatibility-only for older hosts and tests.
 */
data class DreamRequest(
    val workspaceRoot: File?,
    val traceRoot: File?,
    val memoryRoot: File?,
    val mode: DreamMode,
    val virtualWorkspace: VfsFileSystem? = null,
) {
    /** Creates a VFS-only request. Candidate API. */
    constructor(virtualWorkspace: VfsFileSystem, mode: DreamMode) : this(
        workspaceRoot = null,
        traceRoot = null,
        memoryRoot = null,
        mode = mode,
        virtualWorkspace = virtualWorkspace,
    )
}

/** Candidate Dream modes for v0.1 scaffold validation. */
enum class DreamMode {
    REBUILD_MEMORY_INDEX,
    EXTRACT_MEMORY_CANDIDATES,
    CONSOLIDATE_MEMORY,
}

/**
 * Candidate Dream result. Candidates are pending review and are not durable promotion.
 */
data class DreamResult(
    val success: Boolean,
    val updatedMemoryIndex: Boolean = false,
    val candidates: List<RuntimeCandidate> = emptyList(),
    val message: String = "",
)

/** Default v0.1 worker that performs no writes and emits no candidates. */
class NoOpDreamWorker : DreamWorker {
    override suspend fun run(request: DreamRequest): DreamResult =
        DreamResult(success = true, message = "NoOpDreamWorker completed without changes.")
}

/** Rebuilds MEMORY.md from existing memory files without selecting relevant memory. */
class MemoryIndexRebuilder : DreamWorker {
    override suspend fun run(request: DreamRequest): DreamResult {
        if (request.mode != DreamMode.REBUILD_MEMORY_INDEX) {
            return DreamResult(success = true, message = "Mode does not rebuild memory index.")
        }
        val workspace = request.virtualWorkspace
        if (workspace != null) {
            return rebuildVfsIndex(workspace)
        }
        val memoryRoot = request.memoryRoot ?: return DreamResult(
            success = false,
            message = "MemoryIndexRebuilder requires virtualWorkspace or memoryRoot.",
        )
        MemoryWorkspace(memoryRoot).rebuildIndex()
        return DreamResult(success = true, updatedMemoryIndex = true)
    }

    private suspend fun rebuildVfsIndex(workspace: VfsFileSystem): DreamResult =
        when (workspace.write(MEMORY_INDEX_PATH, vfsIndexContent(workspace), VfsWriteMode.OVERWRITE)) {
            is VfsWriteResult.Written -> DreamResult(success = true, updatedMemoryIndex = true)
            VfsWriteResult.BinaryUnsupported,
            VfsWriteResult.Conflict,
            VfsWriteResult.InvalidPath,
            VfsWriteResult.NotFound,
            VfsWriteResult.WriteDenied,
            -> DreamResult(success = false, message = "VFS /memory is not writable.")
            is VfsWriteResult.Error -> DreamResult(success = false, message = "VFS memory index write failed.")
        }

    private suspend fun vfsIndexContent(workspace: VfsFileSystem): String {
        val sections = mutableListOf<String>()
        for (category in MEMORY_CATEGORIES) {
            val entries = vfsCategoryEntries(workspace, category)
                .joinToString(separator = "\n") { entry ->
                    "- [${entry.nameWithoutExtension}]($category/${entry.name})\n  ${entry.preview}"
                }
                .ifBlank { "- _No entries._" }
            sections += "## $category\n\n$entries"
        }
        return "# Memory Index\n\n${sections.joinToString(separator = "\n\n")}\n"
    }

    private suspend fun vfsCategoryEntries(workspace: VfsFileSystem, category: String): List<VfsMemoryEntry> {
        val result = workspace.list("$MEMORY_ROOT_PATH/$category")
        val files = (result as? VfsListResult.Entries)?.entries.orEmpty()
        val entries = mutableListOf<VfsMemoryEntry>()
        for (entry in files
            .filter { it.type == VFS_ENTRY_FILE && it.name.endsWith(MEMORY_FILE_EXTENSION) }
            .sortedBy { it.name }) {
            entries += VfsMemoryEntry(
                name = entry.name,
                preview = workspace.memoryPreview("$MEMORY_ROOT_PATH/$category/${entry.name}"),
            )
        }
        return entries
    }

    private suspend fun VfsFileSystem.memoryPreview(path: String): String =
        when (val result = read(path, 0, MAX_MEMORY_PREVIEW_READ_BYTES)) {
            is VfsReadResult.Found -> result.text
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.take(MAX_MEMORY_PREVIEW_CHARS)
                .orEmpty()
            else -> ""
        }
}

private data class VfsMemoryEntry(
    val name: String,
    val preview: String,
) {
    val nameWithoutExtension: String = name.removeSuffix(MEMORY_FILE_EXTENSION)
}

private val MEMORY_CATEGORIES = listOf("user", "feedback", "routines", "reference", "session")
private const val MEMORY_ROOT_PATH = "/memory"
private const val MEMORY_INDEX_PATH = "$MEMORY_ROOT_PATH/MEMORY.md"
private const val MEMORY_FILE_EXTENSION = ".md"
private const val MAX_MEMORY_PREVIEW_READ_BYTES = 4096
private const val MAX_MEMORY_PREVIEW_CHARS = 120
