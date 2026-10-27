@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.workspace
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VirtualWorkspaceTest {
    @Test
    fun readFileReturnsContentWithinWorkspace() = runBlocking {
        val root = createWorkspace()
        File(root, "notes.txt").writeText("hello runtime")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/notes.txt"}""",
        )

        assertTrue(result.contains("hello runtime"))
    }

    @Test
    fun readFileSupportsOffsetAndTruncation() = runBlocking {
        val root = createWorkspace()
        File(root, "notes.txt").writeText("hello runtime")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/notes.txt","offset":6,"max_chars":4}""",
        )

        assertTrue(result.contains("runt"))
        assertTrue(result.contains(""""truncated":true"""))
    }

    @Test
    fun readFileRejectsBinaryFiles() = runBlocking {
        val root = createWorkspace()
        File(root, "image.bin").writeBytes(byteArrayOf(0, 1, 2, 3))

        val result = VirtualWorkspace(root).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/image.bin"}""",
        )

        assertTrue(result.contains("BINARY_FILE_UNSUPPORTED"))
    }

    @Test
    fun readFileRejectsOverBudgetRequest() = runBlocking {
        val root = createWorkspace()
        File(root, "notes.txt").writeText("hello runtime")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/notes.txt","max_chars":${MAX_READ_CHARS + 1}}""",
        )

        assertTrue(result.contains("READ_BUDGET_EXCEEDED"))
    }

    @Test
    fun listDirReturnsEntries() = runBlocking {
        val root = createWorkspace()
        File(root, "a.txt").writeText("a")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "list_dir",
            argumentsJson = """{"path":"/"}""",
        )

        assertTrue(result.contains("a.txt"))
    }

    @Test
    fun grepFindsLiteralMatchesWithoutShell() = runBlocking {
        val root = createWorkspace()
        File(root, "a.txt").writeText("alpha\nneedle")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "grep",
            argumentsJson = """{"path":"/","pattern":"needle"}""",
        )

        assertTrue(result.contains("needle"))
        assertTrue(result.contains("a.txt"))
    }

    @Test
    fun grepBoundsLongPreviewBeforeSerializingResultJson() = runBlocking {
        val root = createWorkspace()
        val longTail = "x".repeat(1_000)
        File(root, "large-line.txt").writeText("needle-$longTail")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "grep",
            argumentsJson = """{"path":"/","pattern":"needle","max_matches":1}""",
        )

        assertTrue(result.contains("needle-"))
        assertTrue(result.contains("... [truncated]"))
        assertFalse(result.contains("x".repeat(400)))
        assertTrue("result length=${result.length}", result.length < 500)
    }

    @Test
    fun readFileEscapesJsonWithoutChangingWireShape() = runBlocking {
        val root = createWorkspace()
        File(root, "quoted.txt").writeText("slash\\ quote\" line\nnext")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/quoted.txt"}""",
        )

        assertEquals(
            """{"ok":true,"content":"slash\\ quote\" line\nnext","truncated":false}""",
            result,
        )
    }

    @Test
    fun executeWithTelemetrySplitsGrepScanAndSerializationEvidence() = runBlocking {
        val root = createWorkspace()
        File(root, "a.txt").writeText("needle")

        val execution = VirtualWorkspace(root).executeWithTelemetry(
            toolName = "grep",
            argumentsJson = """{"path":"/","pattern":"needle"}""",
        )

        assertTrue(execution.resultJson.contains("needle"))
        assertTrue(execution.telemetry.resolveDurationMs >= 0)
        assertTrue(execution.telemetry.scanDurationMs >= 0)
        assertTrue(execution.telemetry.serializeDurationMs >= 0)
        assertEquals(0, execution.telemetry.materializeDurationMs)
    }

    @Test
    fun grepRejectsOverBudgetScan() = runBlocking {
        val root = createWorkspace()
        File(root, "large.txt").writeText("a".repeat((MAX_GREP_SCAN_BYTES + 1L).toInt()))

        val result = VirtualWorkspace(root).executeJson(
            toolName = "grep",
            argumentsJson = """{"path":"/","pattern":"needle"}""",
        )

        assertTrue(result.contains("GREP_BUDGET_EXCEEDED"))
    }

    @Test
    fun readFileRejectsPathTraversal() = runBlocking {
        val result = VirtualWorkspace(createWorkspace()).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/../secret.txt"}""",
        )

        assertTrue(result.contains("INVALID_WORKSPACE_PATH"))
    }

    @Test
    fun readFileRejectsHostSchemePath() = runBlocking {
        val result = VirtualWorkspace(createWorkspace()).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"file:///tmp/secret.txt"}""",
        )

        assertTrue(result.contains("INVALID_WORKSPACE_PATH"))
    }

    @Test
    fun readFileRejectsTracePath() = runBlocking {
        val result = VirtualWorkspace(createWorkspace()).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/traces/current.jsonl"}""",
        )

        assertTrue(result.contains("INVALID_WORKSPACE_PATH"))
    }

    @Test
    fun readFileRejectsCanonicalSymlinkEscape() = runBlocking {
        val root = createWorkspace()
        val outside = File(root.parentFile, "${root.name}-outside").canonicalFile
        outside.mkdirs()
        File(outside, "secret.txt").writeText("outside secret")
        val link = File(root, "outside-link").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (_: Exception) {
            assumeTrue("Filesystem does not allow symlink creation in this test environment.", false)
        }

        val result = VirtualWorkspace(root).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/outside-link/secret.txt"}""",
        )

        assertTrue(result.contains("INVALID_WORKSPACE_PATH"))
    }

    @Test
    fun grepSkipsCanonicalSymlinkEscape() = runBlocking {
        val root = createWorkspace()
        val outside = File(root.parentFile, "${root.name}-outside").canonicalFile
        outside.mkdirs()
        File(outside, "secret.txt").writeText("outside secret")
        val link = File(root, "outside-link").toPath()
        try {
            Files.createSymbolicLink(link, outside.toPath())
        } catch (_: Exception) {
            assumeTrue("Filesystem does not allow symlink creation in this test environment.", false)
        }

        val result = VirtualWorkspace(root).executeJson(
            toolName = "grep",
            argumentsJson = """{"path":"/","pattern":"outside secret"}""",
        )

        assertFalse(result.contains("outside secret"))
        assertTrue(result.contains(""""matches":[]"""))
    }

    private fun createWorkspace(): File =
        Files.createTempDirectory("agent-runtime-workspace-").toFile().canonicalFile
}
