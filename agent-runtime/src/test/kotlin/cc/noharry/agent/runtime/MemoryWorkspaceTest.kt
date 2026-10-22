package cc.noharry.agent.runtime

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MemoryWorkspaceTest {
    @Test
    fun memoryWorkspaceInitializesLayoutAndIndex() {
        val root = Files.createTempDirectory("agent-memory-").toFile()
        val memory = MemoryWorkspace(root)

        memory.initialize()

        assertTrue(root.resolve("MEMORY.md").isFile)
        assertTrue(root.resolve("user").isDirectory)
        assertTrue(root.resolve("feedback").isDirectory)
        assertTrue(root.resolve("routines").isDirectory)
        assertTrue(root.resolve("reference").isDirectory)
        assertTrue(root.resolve("session").isDirectory)
    }

    @Test
    fun memoryWorkspaceRebuildsIndexWithoutSemanticSelection() {
        val root = Files.createTempDirectory("agent-memory-").toFile()
        val memory = MemoryWorkspace(root)

        memory.initialize()
        memory.writeMemory(
            category = MemoryCategory.ROUTINES,
            name = "driver-feels-cold",
            content = "Use climate setting history, but do not auto-enable seat heat.",
        )

        val index = root.resolve("MEMORY.md").readText()
        assertTrue(index.contains("## routines"))
        assertTrue(index.contains("[driver-feels-cold](routines/driver-feels-cold.md)"))
        assertTrue(index.contains("Use climate setting history"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun memoryWorkspaceRejectsUnsafeNames() {
        MemoryWorkspace(Files.createTempDirectory("agent-memory-").toFile())
            .writeMemory(MemoryCategory.USER, "../secret", "invalid")
    }

    @Test
    fun virtualWorkspaceReadsInitializedMemoryIndex() = runBlocking {
        val workspaceRoot = Files.createTempDirectory("agent-workspace-").toFile()

        AgentRuntimeBuilder.create().workspaceRoot(workspaceRoot).build()
        val result = VirtualWorkspace(workspaceRoot).executeJson(
            toolName = "read_file",
            argumentsJson = """{"path":"/memory/MEMORY.md"}""",
        )

        assertTrue(result.contains(""""ok":true"""))
        assertTrue(result.contains("Memory Index"))
    }
}
