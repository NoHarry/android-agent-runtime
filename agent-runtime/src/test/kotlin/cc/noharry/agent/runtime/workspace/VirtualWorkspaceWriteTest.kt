package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.core.state.encodeFileName
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsSourceResolver
import cc.noharry.agent.vfs.VfsWriteMode
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class VirtualWorkspaceWriteTest {
    @Test
    fun writeFileCreatesRunScopedScratchTextFile() = runBlocking {
        val root = createWorkspace()

        val result = VirtualWorkspace(root, runId = "run-a").executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/out.txt","content":"hello"}""",
        )

        assertTrue(result.contains(""""ok":true"""))
        assertEquals("hello", scratchFile(root, "run-a", "out.txt").readText())
        assertFalse(File(root, "scratch/out.txt").exists())
    }

    @Test
    fun aScratchFileIsReadListedAndFoundAtThePathItWasWritten() = runBlocking {
        val root = createWorkspace()
        VirtualWorkspace(root, runId = "run-b").executeJson("write_file", """{"path":"/scratch/other.txt","content":"note b"}""")
        val workspace = VirtualWorkspace(root, runId = "run-a")
        workspace.executeJson("write_file", """{"path":"/scratch/out.txt","content":"note a"}""")

        assertTrue(workspace.executeJson("read_file", """{"path":"/scratch/out.txt"}""").contains(""""content":"note a""""))
        val listed = workspace.executeJson("list_dir", """{"path":"/scratch"}""")
        assertTrue(listed, listed.contains(""""name":"out.txt"""") && !listed.contains("other.txt"))
        val found = workspace.executeJson("grep", """{"pattern":"note","path":"/"}""")
        assertTrue(found, found.contains(""""path":"/scratch/out.txt""""))
        assertFalse(found, found.contains("other.txt") || found.contains("run-a"))
    }

    @Test
    fun scratchWritesAreIsolatedByRunIdAndCleanedOnDemand() = runBlocking {
        val root = createWorkspace()
        VirtualWorkspace(root, runId = "run-a").executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/out.txt","content":"a"}""",
        )
        VirtualWorkspace(root, runId = "run-b").executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/out.txt","content":"b"}""",
        )

        VirtualWorkspace.cleanupScratch(root, "run-a")

        assertFalse(scratchFile(root, "run-a", "out.txt").exists())
        assertEquals("b", scratchFile(root, "run-b", "out.txt").readText())
    }

    @Test
    fun duplicateSeparatorScratchPathsRemainRunScoped() = runBlocking {
        val root = createWorkspace()

        val first = VirtualWorkspace(root, runId = "run-a").executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"//scratch/out.txt","content":"a"}""",
        )
        val second = VirtualWorkspace(root, runId = "run-b").executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"///scratch/out.txt","content":"b"}""",
        )

        assertTrue(first, first.contains(""""ok":true"""))
        assertTrue(second, second.contains(""""ok":true"""))
        assertEquals("a", scratchFile(root, "run-a", "out.txt").readText())
        assertEquals("b", scratchFile(root, "run-b", "out.txt").readText())
        assertFalse(File(root, "scratch/out.txt").exists())
    }

    @Test
    fun scratchSymlinkIsNotFollowedForWritesOrCleanup() = runBlocking {
        val root = createWorkspace()
        val memory = File(root, "memory").apply { mkdirs() }
        val protectedFile = File(memory, "keep.txt").apply { writeText("safe") }
        val scratchParent = File(root, "scratch").apply { mkdirs() }
        createDirectorySymlinkOrSkip(File(scratchParent, encodeFileName("run-a")), memory)

        val result = VirtualWorkspace(root, runId = "run-a").executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/out.txt","content":"unsafe"}""",
        )
        VirtualWorkspace.cleanupScratch(root, "run-a")

        assertTrue(result.contains("WORKSPACE_WRITE_DENIED"))
        assertEquals("safe", protectedFile.readText())
        assertFalse(File(memory, "out.txt").exists())
    }

    @Test
    fun writeFileRejectsReadOnlyMaterializedPath() = runBlocking {
        val result = VirtualWorkspace(createWorkspace()).executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/mcp/out.txt","content":"nope"}""",
        )

        assertTrue(result.contains("WORKSPACE_WRITE_DENIED"))
    }

    @Test
    fun writeFileAtomicReplaceAllowsNewMemoryFileWithoutRead() = runBlocking {
        val root = createWorkspace()

        val result = VirtualWorkspace(root).executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/memory/new.txt","content":"new","mode":"atomic_replace"}""",
        )

        assertTrue(result.contains(""""ok":true"""))
        assertEquals("new", File(root, "memory/new.txt").readText())
    }

    @Test
    fun writeToolsRejectRuntimeManagedLessonNamespace() = runBlocking {
        val root = createWorkspace()
        val lessonFile = File(root, "memory/lessons/navigation/lessons.json")
        requireNotNull(lessonFile.parentFile).mkdirs()
        lessonFile.writeText("trusted")
        val workspace = VirtualWorkspace(root)
        workspace.executeJson("read_file", """{"path":"/memory/lessons/navigation/lessons.json"}""")

        val directWrite = workspace.executeJson(
            "write_file",
            """{"path":"/memory/lessons/navigation/forged.json","content":"forged"}""",
        )
        val normalizedBypass = workspace.executeJson(
            "write_file",
            """{"path":"/memory//lessons/navigation/forged.json","content":"forged"}""",
        )
        val caseBypass = workspace.executeJson(
            "write_file",
            """{"path":"/memory/LESSONS/navigation/forged.json","content":"forged"}""",
        )
        val edit = workspace.executeJson(
            "edit_file",
            """{"path":"/memory/lessons/navigation/lessons.json","old_string":"trusted","new_string":"forged"}""",
        )

        assertTrue(directWrite.contains("WORKSPACE_WRITE_DENIED"))
        assertTrue(normalizedBypass.contains("WORKSPACE_WRITE_DENIED"))
        assertTrue(caseBypass.contains("WORKSPACE_WRITE_DENIED"))
        assertTrue(edit.contains("WORKSPACE_WRITE_DENIED"))
        assertEquals("trusted", lessonFile.readText())
    }

    @Test
    fun writeFileCreateModeRejectsExistingFile() = runBlocking {
        val root = createWorkspace()
        val existing = scratchFile(root, "default", "out.txt")
        requireNotNull(existing.parentFile).mkdirs()
        existing.writeText("old")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/out.txt","content":"new"}""",
        )

        assertTrue(result.contains("WORKSPACE_WRITE_CONFLICT"))
        assertEquals("old", existing.readText())
    }

    @Test
    fun writeFileRejectsOverBudgetContent() = runBlocking {
        val result = VirtualWorkspace(createWorkspace()).executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/large.txt","content":"${"a".repeat((MAX_WORKSPACE_WRITE_FILE_BYTES + 1).toInt())}"}""",
        )

        assertTrue(result.contains("WORKSPACE_WRITE_BUDGET_EXCEEDED"))
    }

    @Test
    fun writeFileRejectsWhenRunByteBudgetIsExceeded() = runBlocking {
        val workspace = VirtualWorkspace(createWorkspace())
        val content = "a".repeat(MAX_WORKSPACE_WRITE_FILE_BYTES.toInt())
        repeat(4) { index ->
            val result = workspace.executeJson(
                toolName = "write_file",
                argumentsJson = """{"path":"/scratch/$index.txt","content":"$content"}""",
            )
            assertTrue(result, result.contains(""""ok":true"""))
        }

        val result = workspace.executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/overflow.txt","content":"x"}""",
        )

        assertTrue(result.contains("WORKSPACE_WRITE_BUDGET_EXCEEDED"))
    }

    @Test
    fun writeFileRejectsWhenRunCallBudgetIsExceeded() = runBlocking {
        val workspace = VirtualWorkspace(createWorkspace())
        repeat(MAX_WORKSPACE_WRITE_CALLS) { index ->
            val result = workspace.executeJson(
                toolName = "write_file",
                argumentsJson = """{"path":"/scratch/$index.txt","content":"x"}""",
            )
            assertTrue(result, result.contains(""""ok":true"""))
        }

        val result = workspace.executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/overflow.txt","content":"x"}""",
        )

        assertTrue(result.contains("WORKSPACE_WRITE_BUDGET_EXCEEDED"))
    }

    @Test
    fun writeFileRejectsBinaryTextContent() = runBlocking {
        val result = VirtualWorkspace(createWorkspace()).executeJson(
            toolName = "write_file",
            argumentsJson = """{"path":"/scratch/bin.txt","content":"\u0000"}""",
        )

        assertTrue(result.contains("BINARY_FILE_UNSUPPORTED"))
    }

    @Test
    fun editFileReplacesSingleMatch() = runBlocking {
        val root = createWorkspace()
        File(root, "memory").mkdirs()
        File(root, "memory/note.txt").writeText("turn on lamp")
        val workspace = VirtualWorkspace(root)
        workspace.executeJson("read_file", """{"path":"/memory/note.txt"}""")

        val result = workspace.executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"lamp","new_string":"heater"}""",
        )

        assertTrue(result.contains(""""ok":true"""))
        assertEquals("turn on heater", File(root, "memory/note.txt").readText())
    }

    @Test
    fun editFileUsesCanonicalPathForReadGuard() = runBlocking {
        val root = createWorkspace()
        File(root, "memory").mkdirs()
        File(root, "memory/note.txt").writeText("turn on lamp")
        val workspace = VirtualWorkspace(root)
        workspace.executeJson("read_file", """{"path":"//memory/note.txt"}""")

        val result = workspace.executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"lamp","new_string":"heater"}""",
        )

        assertTrue(result, result.contains(""""ok":true"""))
        assertEquals("turn on heater", File(root, "memory/note.txt").readText())
    }

    @Test
    fun editFileRejectsNoMatchOrAmbiguousMatchByDefault() = runBlocking {
        val root = createWorkspace()
        File(root, "memory").mkdirs()
        File(root, "memory/note.txt").writeText("same same")
        val workspace = VirtualWorkspace(root)
        workspace.executeJson("read_file", """{"path":"/memory/note.txt"}""")

        val noMatch = workspace.executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"missing","new_string":"x"}""",
        )
        val ambiguous = workspace.executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"same","new_string":"x"}""",
        )

        assertTrue(noMatch.contains("WORKSPACE_EDIT_NO_MATCH"))
        assertTrue(ambiguous.contains("WORKSPACE_EDIT_NO_MATCH"))
    }

    @Test
    fun editFileReplaceAllReplacesEveryMatch() = runBlocking {
        val root = createWorkspace()
        File(root, "memory").mkdirs()
        File(root, "memory/note.txt").writeText("same same")
        val workspace = VirtualWorkspace(root)
        workspace.executeJson("read_file", """{"path":"/memory/note.txt"}""")

        val result = workspace.executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"same","new_string":"done","replace_all":true}""",
        )

        assertTrue(result.contains(""""ok":true"""))
        assertEquals("done done", File(root, "memory/note.txt").readText())
    }

    @Test
    fun editFileRejectsWhenFileWasNotReadInRun() = runBlocking {
        val root = createWorkspace()
        File(root, "memory").mkdirs()
        File(root, "memory/note.txt").writeText("turn on lamp")

        val result = VirtualWorkspace(root).executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"lamp","new_string":"heater"}""",
        )

        assertTrue(result.contains("WORKSPACE_READ_REQUIRED"))
        assertEquals("turn on lamp", File(root, "memory/note.txt").readText())
    }

    @Test
    fun editFileRejectsAfterPartialRead() = runBlocking {
        val root = createWorkspace()
        File(root, "memory").mkdirs()
        File(root, "memory/note.txt").writeText("turn on lamp")
        val workspace = VirtualWorkspace(root)
        workspace.executeJson("read_file", """{"path":"/memory/note.txt","max_chars":4}""")

        val result = workspace.executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"lamp","new_string":"heater"}""",
        )

        assertTrue(result.contains("WORKSPACE_FULL_READ_REQUIRED"))
    }

    @Test
    fun editFileRejectsWhenRevisionChangedAfterRead() = runBlocking {
        val source = VfsMemorySource(mapOf("memory/note.txt" to "turn on lamp"))
        val workspace = VirtualWorkspace(memoryWorkspace(source))
        workspace.executeJson("read_file", """{"path":"/memory/note.txt"}""")
        source.write("memory/note.txt", "turn on fan", VfsWriteMode.OVERWRITE)

        val result = workspace.executeJson(
            toolName = "edit_file",
            argumentsJson = """{"path":"/memory/note.txt","old_string":"fan","new_string":"heater"}""",
        )

        assertTrue(result.contains("WORKSPACE_WRITE_CONFLICT"))
        assertEquals("turn on fan", source.snapshot().getValue("memory/note.txt"))
    }

    private fun createWorkspace(): File =
        Files.createTempDirectory("agent-runtime-workspace-").toFile().canonicalFile

    private fun memoryWorkspace(source: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"root","source":"memory://root","target":"/","mode":"readWrite","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { if (it == "memory://root") source else null },
        )

    private fun createDirectorySymlinkOrSkip(link: File, target: File) {
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
        } catch (error: Exception) {
            assumeTrue("Directory symlink unavailable: ${error.message}", false)
        }
    }

    private fun scratchFile(root: File, runId: String, relativePath: String): File =
        File(root, "scratch/${encodeFileName(runId)}/$relativePath")
}
