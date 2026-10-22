package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VfsMountTableTest {
    @Test
    fun resolvesHighestPriorityMostSpecificMount() {
        val table = VfsMountTable(
            listOf(
                mount("root", "/", VfsMountMode.READ_WRITE, 10),
                mount("skills", "/.agents/skills", VfsMountMode.READ_ONLY, 50),
            ),
        )

        val resolved = table.resolve("/.agents/skills/demo/SKILL.md")

        assertEquals("skills", resolved?.mount?.id)
        assertEquals("demo/SKILL.md", resolved?.relativePath)
    }

    @Test
    fun writeResolutionSkipsReadOnlyMounts() {
        val table = VfsMountTable(
            listOf(
                mount("root", "/", VfsMountMode.READ_WRITE, 10),
                mount("skills", "/.agents/skills", VfsMountMode.READ_ONLY, 50),
            ),
        )

        val resolved = table.resolve("/.agents/skills/demo/SKILL.md", forWrite = true)

        assertEquals("root", resolved?.mount?.id)
        assertEquals(".agents/skills/demo/SKILL.md", resolved?.relativePath)
    }

    @Test
    fun invalidPathDoesNotResolve() {
        val table = VfsMountTable(listOf(mount("root", "/", VfsMountMode.READ_WRITE, 10)))

        assertNull(table.resolve("/../secret"))
    }

    @Test
    fun snapshotRecordsManifestHashAndMounts() {
        val manifest = VfsManifestLoader().parse(WORKSPACE_JSON_FOR_SNAPSHOT)
        val snapshot = VfsSnapshotFactory.snapshot(
            manifest = manifest,
            manifestJson = WORKSPACE_JSON_FOR_SNAPSHOT,
            capturedAtEpochMillis = 123L,
        )

        assertEquals("1.0.0", snapshot.vfsContractVersion)
        assertEquals(64, snapshot.manifestHash.length)
        assertEquals(123L, snapshot.capturedAtEpochMillis)
        assertEquals(listOf("root"), snapshot.mounts.map { it.id })
    }

    private fun mount(
        id: String,
        target: String,
        mode: VfsMountMode,
        priority: Int,
    ): VfsMount =
        VfsMount(
            id = id,
            source = "memory://$id",
            target = VfsPathResolver.normalize(target) ?: error("invalid test target"),
            mode = mode,
            priority = priority,
        )
}

private val WORKSPACE_JSON_FOR_SNAPSHOT = """
    {
      "schemaVersion": 1,
      "vfsContractVersion": "1.0.0",
      "workspaceRoot": "/",
      "mounts": [
        {
          "id": "root",
          "source": "app-private://workspace",
          "target": "/",
          "mode": "readWrite",
          "priority": 100
        }
      ]
    }
""".trimIndent()
