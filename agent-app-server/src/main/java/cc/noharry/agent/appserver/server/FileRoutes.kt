package cc.noharry.agent.appserver.server

import android.content.Context
import cc.noharry.agent.appserver.protocol.FileEntryView
import cc.noharry.agent.appserver.protocol.FileListResult
import cc.noharry.agent.appserver.protocol.FileMountsResult
import cc.noharry.agent.appserver.protocol.FileReadResult
import cc.noharry.agent.appserver.protocol.FileRequests
import cc.noharry.agent.appserver.protocol.MAX_FILE_READ_BYTES
import cc.noharry.agent.appserver.runtime.appWorkspaceRoot
import cc.noharry.agent.appserver.runtime.workspaceVfs
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The workspace the agent reads and writes, as the console's file browser lists and opens it. */
internal class FileRoutes(private val app: Context) : RouteModule {
    override fun RouteTable.install() {
        on(FileRequests.MOUNTS) { withContext(Dispatchers.IO) { FileMountsResult(mountTargets()) } }
        on(FileRequests.LIST) { params ->
            withContext(Dispatchers.IO) {
                when (val listed = workspace().list(params.path)) {
                    is VfsListResult.Entries -> FileListResult(
                        listed.entries.map { FileEntryView(it.name, it.type == VFS_DIR_TYPE, it.sizeBytes) },
                    )
                    is VfsListResult.Error -> FileListResult(error = listed.message)
                    VfsListResult.InvalidPath, VfsListResult.NotFound -> FileListResult(missing = true)
                }
            }
        }
        on(FileRequests.READ) { params ->
            withContext(Dispatchers.IO) {
                val read = workspace().read(params.path, 0L, params.maxBytes.coerceIn(0, MAX_FILE_READ_BYTES))
                FileReadResult((read as? VfsReadResult.Found)?.text)
            }
        }
    }

    /** Built for each request, so a newly picked folder or skill shows at once. */
    private fun workspace(): VfsFileSystem = workspaceVfs(app, appWorkspaceRoot(app))

    /** Directory mount targets from the workspace manifest (file mounts excluded). */
    private fun mountTargets(): List<String> = runCatching {
        val text = app.assets.open("$WORKSPACE_ASSET_ROOT_PATH/$WORKSPACE_MANIFEST_FILE")
            .use { it.readBytes().toString(Charsets.UTF_8) }
        val mounts = MountsJson.parseToJsonElement(text).jsonObject["mounts"]?.jsonArray.orEmpty()
        mounts.mapNotNull { element ->
            val target = element.jsonObject["target"]?.jsonPrimitive?.content ?: return@mapNotNull null
            target.takeIf { path -> path.split('/').last().none { it == '.' } || path == "/" }
        }.distinct().sorted()
    }.getOrDefault(listOf("/"))

    private companion object {
        val MountsJson = Json { ignoreUnknownKeys = true }
        const val WORKSPACE_ASSET_ROOT_PATH = "runtime-workspace"
        const val WORKSPACE_MANIFEST_FILE = "workspace.json"
        const val VFS_DIR_TYPE = "dir"
    }
}
