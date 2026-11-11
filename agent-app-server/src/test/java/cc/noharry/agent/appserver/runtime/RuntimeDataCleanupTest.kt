package cc.noharry.agent.appserver.runtime
import cc.noharry.agent.appserver.protocol.view.GENERATED_IMAGES_DIR
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDataCleanupTest {
    @Test
    fun wipeTargetsCoverWorkspaceSessionsAndEvidence() {
        val root = java.io.File("files")
        val names = runtimeDataDirs(root).map { it.name }.toSet()
        assertTrue(names.contains(DEBUG_WORKSPACE_DIR))
        assertTrue(names.contains("debug-run-evidence"))
        assertTrue(names.contains("mcp-progressive-evidence"))
        assertTrue(names.contains("runtime-debug-logs"))
        assertTrue(names.contains("web-search-evidence"))
        assertTrue(names.contains(GENERATED_IMAGES_DIR))
    }

    @Test
    fun clearDeletesExistingDirsAndSkipsMissing() {
        val basePath = createTempDirectory(prefix = "debug-runtime-cleanup-")
        val base = basePath.toFile()
        try {
            val workspace = java.io.File(base, DEBUG_WORKSPACE_DIR).also { it.mkdirs() }
            java.io.File(workspace, "sessions/s1/meta.json").also {
                it.parentFile?.mkdirs()
                it.writeText("{}")
            }
            java.io.File(workspace, "memory/todo/x.json").also {
                it.parentFile?.mkdirs()
                it.writeText("[]")
            }
            val evidence = java.io.File(base, "debug-run-evidence").also { it.mkdirs() }
            java.io.File(evidence, "run-1.json").writeText("{}")
            val targets = runtimeDataDirs(base)
            val result = clearRuntimeDataDirs(targets)

            assertTrue(result.ok)
            assertEquals(2, result.deletedCount)
            assertFalse(workspace.exists())
            assertFalse(evidence.exists())
            assertTrue(result.summaryLine().contains("Cleared 2"))
        } finally {
            Files.walk(basePath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun emptyTargetsReportNothingToClear() {
        val basePath = createTempDirectory(prefix = "debug-runtime-empty-")
        try {
            val result = clearRuntimeDataDirs(runtimeDataDirs(basePath.toFile()))
            assertTrue(result.ok)
            assertEquals(0, result.deletedCount)
            assertEquals("Nothing to clear", result.summaryLine())
        } finally {
            Files.walk(basePath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun clearDataCoversProbeRunMetaAndCompletenessArtifacts() {
        // Q-RPB-008: clearRuntimeData must cover probes/graph/run-meta under the
        // reliability root that DebugRuntimeBuilder wires as agent-reliability.
        val basePath = createTempDirectory(prefix = "debug-runtime-probes-")
        val base = basePath.toFile()
        try {
            assertTrue(runtimeDataDirs(base).any { it.name == AGENT_RELIABILITY_DIR })
            val runDir = java.io.File(base, "$AGENT_RELIABILITY_DIR/runs/run-1")
            runDir.mkdirs()
            java.io.File(runDir, "probes.jsonl").writeText("{}\n")
            java.io.File(runDir, "probe-completeness.json").writeText("{}")
            java.io.File(runDir, "run-meta.json").writeText("{}")

            val result = clearRuntimeDataDirs(runtimeDataDirs(base))

            assertTrue(result.ok)
            assertFalse(java.io.File(base, AGENT_RELIABILITY_DIR).exists())
            assertFalse(runDir.exists())
        } finally {
            Files.walk(basePath)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}
