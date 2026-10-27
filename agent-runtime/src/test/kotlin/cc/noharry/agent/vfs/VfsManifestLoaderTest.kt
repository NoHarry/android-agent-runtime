package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VfsManifestLoaderTest {
    @Test
    fun parsesWorkspaceJsonManifest() {
        val manifest = VfsManifestLoader().parse(WORKSPACE_JSON)

        assertEquals(1, manifest.schemaVersion)
        assertEquals("1.0.0", manifest.vfsContractVersion)
        assertEquals("/", manifest.workspaceRoot.value)
        assertEquals(listOf("workspace-root", "agent-skills"), manifest.mounts.map { it.id })
        assertEquals("/.agents/skills", manifest.mounts[1].target.value)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val result = runCatching {
            VfsManifestLoader().parse(WORKSPACE_JSON.replace(""""schemaVersion": 1""", """"schemaVersion": 2"""))
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("schemaVersion"))
    }

    @Test
    fun rejectsInvalidTargetPath() {
        val result = runCatching {
            VfsManifestLoader().parse(WORKSPACE_JSON.replace(""""/.agents/skills"""", """"/../escape""""))
        }

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("target"))
    }
}

private val WORKSPACE_JSON = """
    {
      "schemaVersion": 1,
      "vfsContractVersion": "1.0.0",
      "bundleVersion": "2026.06.28",
      "workspaceRoot": "/",
      "mounts": [
        {
          "id": "workspace-root",
          "source": "app-private://runtime-debug-workspace",
          "target": "/",
          "mode": "readWrite",
          "priority": 100
        },
        {
          "id": "agent-skills",
          "source": "asset://debug/skills",
          "target": "/.agents/skills",
          "mode": "readOnly",
          "priority": 200
        }
      ]
    }
""".trimIndent()
