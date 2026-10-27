package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.vfs.VfsFileSource
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsManifestLoader
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsRoomResource
import cc.noharry.agent.vfs.VfsRoomResourceAdapter
import cc.noharry.agent.vfs.VfsRoomSource
import cc.noharry.agent.vfs.VfsRoomUpdateRow
import cc.noharry.agent.vfs.VfsSourceResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VirtualWorkspaceManifestVfsTest {
    @Test
    fun readAgentsMdUsesVfsFileBeforeRuntimeFallback() = runBlocking {
        val root = createWorkspace()
        val agents = File(root, "host-AGENTS.md").apply { writeText("real vfs agents") }
        val router = ContextMaterializerRouter(
            storage = null,
            memoryStore = null,
            todoStore = null,
            skillRegistry = null,
            agentsMdContent = "legacy fallback agents",
        )

        val result = VirtualWorkspace(
            fileSystem = fileSystemForAgents(root, agents),
            materializer = VirtualFileMaterializer(contextRouter = router),
        ).executeJson("read_file", """{"path":"/AGENTS.md"}""")

        assertTrue(result.contains("real vfs agents"))
        assertFalse(result.contains("legacy fallback agents"))
    }

    @Test
    fun runtimeFileToolsUseManifestVfsRootForEveryMountedSource() = runBlocking {
        val root = createWorkspace()
        File(root, "host.txt").writeText("host file")
        val memory = VfsMemorySource(mapOf("runtime-state.json" to """{"state":"ready"}"""))
        val database = VfsRoomSource(SessionIndexAdapter())
        val skills = VfsMemorySource(mapOf("debug/SKILL.md" to "debug skill"))
        val workspace = VirtualWorkspace(multiSourceVfs(root, memory, database, skills))

        val rootList = workspace.executeJson("list_dir", """{"path":"/"}""")
        val skill = workspace.executeJson("read_file", """{"path":"/.agents/skills/debug/SKILL.md"}""")
        val sessionIndex = workspace.executeJson("read_file", """{"path":"/sessions/resources/sessions.json"}""")
        val write = workspace.executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/memory/host/latest.json","content":"{\"ok\":true}"}""",
        )

        assertTrue(rootList.contains("host.txt"))
        assertTrue(rootList.contains(".agents"))
        assertTrue(rootList.contains("memory"))
        assertTrue(rootList.contains("sessions"))
        assertTrue(skill.contains("debug skill"))
        assertTrue(sessionIndex.contains("[]"))
        assertTrue(write.contains(""""ok":true"""))
        assertEquals("""{"ok":true}""", memory.snapshot()["latest.json"])
    }

    private fun createWorkspace(): File =
        Files.createTempDirectory("agent-runtime-workspace-").toFile().canonicalFile

    private fun fileSystemForAgents(root: File, agents: File): VfsFileSystem {
        val manifest = VfsManifestLoader().parse(
            """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"root","source":"file://root","target":"/","mode":"readWrite","priority":10},
                    {"id":"agents","source":"file://agents","target":"/AGENTS.md","mode":"readOnly","priority":100}
                  ]
                }
            """.trimIndent(),
        )
        return VfsFileSystem(
            manifest = manifest,
            sourceResolver = VfsSourceResolver { source ->
                when (source) {
                    "file://root" -> VfsFileSource(root)
                    "file://agents" -> VfsFileSource(agents)
                    else -> null
                }
            },
        )
    }

    private fun multiSourceVfs(
        root: File,
        memory: VfsMemorySource,
        database: VfsRoomSource,
        skills: VfsMemorySource,
    ): VfsFileSystem = VfsFileSystem.fromJson(
        jsonText = multiSourceManifestJson(),
        sourceResolver = VfsSourceResolver { source ->
            when (source) {
                "app-private://workspace" -> VfsFileSource(root)
                "asset://runtime-workspace/skills" -> skills
                "memory://runtime" -> memory
                "database://session-index" -> database
                else -> null
            }
        },
    )
}

private fun multiSourceManifestJson(): String = """
    {
      "schemaVersion": 1,
      "vfsContractVersion": "1.0.0",
      "workspaceRoot": "/",
      "mounts": [
        {"id":"root","source":"app-private://workspace","target":"/","mode":"readWrite","priority":10},
        {"id":"skills","source":"asset://runtime-workspace/skills","target":"/.agents/skills","mode":"readOnly","priority":90},
        {"id":"memory","source":"memory://runtime","target":"/memory/host","mode":"readWrite","priority":80},
        {"id":"sessions","source":"database://session-index","target":"/sessions","mode":"readOnly","priority":80}
      ]
    }
""".trimIndent()

private class SessionIndexAdapter : VfsRoomResourceAdapter {
    override fun schemaJson(): String =
        """{"tables":["sessions"]}"""

    override fun resources(): List<VfsRoomResource> =
        listOf(VfsRoomResource(name = "sessions", displayPath = "sessions.json"))

    override fun readResource(resource: String): String? =
        if (resource == "sessions") "[]" else null

    override fun updateRow(payload: VfsRoomUpdateRow): Boolean =
        false
}
