package cc.noharry.agent.runtime

import cc.noharry.agent.vfs.VfsFileSystem
import java.util.concurrent.ConcurrentHashMap

/** Candidate host-registered workspace reference. Not frozen until API review closes. */
data class RuntimeWorkspaceRef(
    val workspaceId: String,
    val fileSystem: VfsFileSystem,
) {
    init {
        require(workspaceId.isNotBlank()) { "workspaceId must not be blank." }
    }
}

internal class RuntimeWorkspaceRegistry(
    refs: List<RuntimeWorkspaceRef>,
    private val defaultWorkspaceId: String?,
) {
    private val workspaces = refs.associateBy { it.workspaceId }
    private val sessionSelections = ConcurrentHashMap<String, String>()
    private val runSelections = ConcurrentHashMap<String, String>()

    fun select(
        request: cc.noharry.agent.kernel.api.AgentRequest,
        runId: String = request.requestId,
    ): RuntimeWorkspaceSelection {
        val requestedId = request.workspaceId?.trim()?.takeIf { it.isNotEmpty() }
        val selectedId =
            requestedId
                ?: sessionSelections[request.sessionId]
                ?: runSelections[runId]
                ?: defaultWorkspaceId
        if (selectedId == null) {
            runSelections.remove(runId)
            return RuntimeWorkspaceSelection.Selected(workspaceId = null, fileSystem = null, changed = false)
        }
        val workspace = workspaces[selectedId]
            ?: return RuntimeWorkspaceSelection.Unknown(selectedId)
        val previous = sessionSelections.put(request.sessionId, selectedId)
        runSelections[runId] = selectedId
        return RuntimeWorkspaceSelection.Selected(
            workspaceId = selectedId,
            fileSystem = workspace.fileSystem,
            changed = previous != selectedId,
        )
    }

    fun fileSystem(workspaceId: String?): VfsFileSystem? =
        workspaceId?.let { workspaces[it]?.fileSystem }

    fun fileSystemForRun(runId: String): VfsFileSystem? =
        runSelections[runId]?.let(workspaces::get)?.fileSystem

    fun clearRun(runId: String) {
        runSelections.remove(runId)
    }
}

internal sealed interface RuntimeWorkspaceSelection {
    data class Selected(
        val workspaceId: String?,
        val fileSystem: VfsFileSystem?,
        val changed: Boolean,
    ) : RuntimeWorkspaceSelection

    data class Unknown(val workspaceId: String) : RuntimeWorkspaceSelection
}
