package cc.noharry.agent.appserver.runtime
import android.content.Context
import android.content.res.AssetManager
import cc.noharry.agent.appserver.skills.RuntimeSkill
import cc.noharry.agent.appserver.skills.appSkillSource
import cc.noharry.agent.appserver.skills.loadRuntimeSkills
import cc.noharry.agent.vfs.VfsEntry
import cc.noharry.agent.vfs.VfsFileSource
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsRoomResource
import cc.noharry.agent.vfs.VfsRoomResourceAdapter
import cc.noharry.agent.vfs.VfsRoomSource
import cc.noharry.agent.vfs.VfsRoomUpdateRow
import cc.noharry.agent.vfs.VfsSearchMatch
import cc.noharry.agent.vfs.VfsSearchResult
import cc.noharry.agent.vfs.VfsSource
import cc.noharry.agent.vfs.VfsSourceResolver
import cc.noharry.agent.vfs.saf.SafVfsSource
import java.io.File

internal fun workspaceVfs(
    context: Context,
    workspaceRoot: File,
    skills: List<RuntimeSkill> = loadRuntimeSkills(context),
): VfsFileSystem {
    val manifestJson = context.assets.readText(WORKSPACE_MANIFEST_ASSET)
    return VfsFileSystem.fromJson(
        jsonText = manifestJson,
        sourceResolver = WorkspaceSourceResolver(context.applicationContext, context.assets, workspaceRoot, skills),
    )
}

private class WorkspaceSourceResolver(
    context: Context,
    private val assets: AssetManager,
    workspaceRoot: File,
    skills: List<RuntimeSkill>,
) : VfsSourceResolver {
    private val skillSource = appSkillSource(context, skills)
    private val appPrivateSource = VfsFileSource(workspaceRoot)
    private val memorySource = VfsMemorySource(memoryFiles())
    private val databaseSource = VfsRoomSource(SessionRoomAdapter())
    private val safSource = selectedSafWorkspace(context)
        ?.let { SafVfsSource.fromTreeUri(context, it.uri) }

    override fun resolve(source: String): VfsSource? = when {
        source == "asset://runtime-workspace/skills" -> skillSource
        source == APP_PRIVATE_WORKSPACE_SOURCE -> appPrivateSource
        source == MEMORY_WORKSPACE_SOURCE -> memorySource
        source == DATABASE_WORKSPACE_SOURCE -> databaseSource
        source == SAF_WORKSPACE_SOURCE -> safSource
        source.startsWith(ASSET_SOURCE_PREFIX) ->
            AndroidAssetVfsSource(assets, source.removePrefix(ASSET_SOURCE_PREFIX))
        else -> null
    }
}

private class AndroidAssetVfsSource(
    private val assets: AssetManager,
    private val root: String,
) : VfsSource {
    override suspend fun list(relativePath: String): VfsListResult {
        val entries = assets.list(assetPath(relativePath)).orEmpty()
        if (entries.isEmpty()) return VfsListResult.NotFound
        return VfsListResult.Entries(
            entries.map { name ->
                val childRelative = joinRelative(relativePath, name)
                VfsEntry(name, if (assets.list(assetPath(childRelative)).orEmpty().isEmpty()) "file" else "dir")
            },
        )
    }

    override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
        if (offset < 0 || maxBytes <= 0) return VfsReadResult.InvalidPath
        val bytes = readBytes(relativePath) ?: return VfsReadResult.NotFound
        if (bytes.any(::isBinaryByte)) return VfsReadResult.BinaryUnsupported
        val start = offset.coerceAtMost(bytes.size.toLong()).toInt()
        val end = (start + maxBytes).coerceAtMost(bytes.size)
        return VfsReadResult.Found(
            text = bytes.copyOfRange(start, end).toString(Charsets.UTF_8),
            bytesRead = end - start,
            totalBytes = bytes.size.toLong(),
        )
    }

    override suspend fun search(
        relativePath: String,
        pattern: String,
        maxMatches: Int,
        maxScanBytes: Long,
    ): VfsSearchResult {
        if (pattern.isEmpty()) return VfsSearchResult.InvalidPattern
        var scanned = 0L
        val matches = mutableListOf<VfsSearchMatch>()
        val files = collectFiles(relativePath)
        if (files.isEmpty()) return VfsSearchResult.NotFound
        for ((path, bytes) in files) {
            scanned += bytes.size
            if (scanned > maxScanBytes) return VfsSearchResult.BudgetExceeded
            if (bytes.any(::isBinaryByte)) continue
            bytes.toString(Charsets.UTF_8).lineSequence().forEachIndexed { index, line ->
                if (matches.size < maxMatches && line.contains(pattern, ignoreCase = true)) {
                    matches += VfsSearchMatch(path, index + 1, line)
                }
            }
        }
        return VfsSearchResult.Matches(matches)
    }

    private fun collectFiles(relativePath: String): List<Pair<String, ByteArray>> {
        val children = assets.list(assetPath(relativePath)).orEmpty()
        if (children.isEmpty()) {
            return readBytes(relativePath)?.let { listOf(relativePath.trim('/') to it) }.orEmpty()
        }
        return children.flatMap { child -> collectFiles(joinRelative(relativePath, child)) }
    }

    private fun readBytes(relativePath: String): ByteArray? =
        try {
            assets.open(assetPath(relativePath)).use { it.readBytes() }
        } catch (_: Exception) {
            null
        }

    private fun assetPath(relativePath: String): String =
        listOf(root.trim('/'), relativePath.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")

    private fun joinRelative(parent: String, child: String): String =
        listOf(parent.trim('/'), child.trim('/'))
            .filter { it.isNotBlank() }
            .joinToString("/")
}

private fun AssetManager.readText(path: String): String =
    open(path).use { it.readBytes().toString(Charsets.UTF_8) }

private fun memoryFiles(): Map<String, String> = mapOf(
    "runtime-state.json" to
        """{"source":"memory://debug-runtime-memory","description":"host-managed in-memory workspace file"}""",
)

private class SessionRoomAdapter : VfsRoomResourceAdapter {
    override fun schemaJson(): String =
        """{"source":"database://debug-session-index","tables":["sessions"],"queries":["recent_sessions"]}"""

    override fun resources(): List<VfsRoomResource> =
        listOf(VfsRoomResource(name = "sessions", displayPath = "sessions.json"))

    override fun readResource(resource: String): String? =
        if (resource == "sessions") SESSION_ROWS else null

    override fun queryNames(): List<String> =
        listOf("recent_sessions")

    override fun readQuery(name: String): String? =
        if (name == "recent_sessions") """{"query":"recent_sessions","rows":$SESSION_ROWS}""" else null

    override fun updateRow(payload: VfsRoomUpdateRow): Boolean =
        false
}

private fun isBinaryByte(byte: Byte): Boolean {
    val value = byte.toInt() and BYTE_MASK
    return value == 0 || (value < ASCII_SPACE && value !in TEXT_CONTROL_BYTES)
}

private const val WORKSPACE_ASSET_ROOT = "runtime-workspace"
private const val WORKSPACE_MANIFEST_ASSET = "$WORKSPACE_ASSET_ROOT/workspace.json"
private const val APP_PRIVATE_WORKSPACE_SOURCE = "app-private://runtime-debug-workspace"
private const val MEMORY_WORKSPACE_SOURCE = "memory://debug-runtime-memory"
private const val DATABASE_WORKSPACE_SOURCE = "database://debug-session-index"
private const val SESSION_ROWS = """[]"""
private const val ASSET_SOURCE_PREFIX = "asset://"
private const val ASCII_SPACE = 32
private const val BYTE_MASK = 0xFF
private val TEXT_CONTROL_BYTES = setOf(9, 10, 13)
