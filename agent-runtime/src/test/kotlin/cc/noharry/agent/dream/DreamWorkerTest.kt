package cc.noharry.agent.dream

import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsSourceResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DreamWorkerTest {
    @Test
    fun noOpDreamWorkerDoesNotWriteOrEmitCandidates() = runBlocking {
        val request = dreamRequest()
        val result = NoOpDreamWorker().run(request)

        assertTrue(result.success)
        assertFalse(result.updatedMemoryIndex)
        assertTrue(result.candidates.isEmpty())
        assertFalse(requireNotNull(request.memoryRoot).resolve("MEMORY.md").exists())
    }

    @Test
    fun memoryIndexRebuilderOnlyUpdatesMemoryIndex() = runBlocking {
        val request = dreamRequest(mode = DreamMode.REBUILD_MEMORY_INDEX)
        val memoryRoot = requireNotNull(request.memoryRoot)
        val userDir = memoryRoot.resolve("user")
        userDir.mkdirs()
        userDir.resolve("driver-temperature.md").writeText("Driver prefers 23C in winter.")

        val result = MemoryIndexRebuilder().run(request)
        val index = memoryRoot.resolve("MEMORY.md").readText()

        assertTrue(result.success)
        assertTrue(result.updatedMemoryIndex)
        assertTrue(result.candidates.isEmpty())
        assertTrue(index.contains("[driver-temperature](user/driver-temperature.md)"))
    }

    @Test
    fun memoryIndexRebuilderCanUseVfsMemoryRoot() = runBlocking {
        val memory = VfsMemorySource(
            mapOf("user/driver-temperature.md" to "Driver prefers 23C in winter."),
        )
        val request = DreamRequest(vfsMemoryWorkspace(memory), DreamMode.REBUILD_MEMORY_INDEX)

        val result = MemoryIndexRebuilder().run(request)
        val index = memory.snapshot()["MEMORY.md"].orEmpty()

        assertTrue(result.success)
        assertTrue(result.updatedMemoryIndex)
        assertTrue(index.contains("[driver-temperature](user/driver-temperature.md)"))
        assertTrue(index.contains("Driver prefers 23C"))
    }

    @Test
    fun dreamCandidatesAreResultDataOnly() = runBlocking {
        val request = dreamRequest(mode = DreamMode.EXTRACT_MEMORY_CANDIDATES)
        val result = CandidateDreamWorker().run(request)

        assertTrue(result.success)
        assertTrue(result.candidates.single().summary.contains("candidate only"))
        val memoryRoot = requireNotNull(request.memoryRoot)
        assertFalse(memoryRoot.resolve("MEMORY.md").exists())
        assertFalse(memoryRoot.resolve("user").exists())
    }

    private fun dreamRequest(
        mode: DreamMode = DreamMode.CONSOLIDATE_MEMORY,
    ): DreamRequest {
        val root = Files.createTempDirectory("agent-dream-").toFile()
        return DreamRequest(
            workspaceRoot = root.resolve("workspace"),
            traceRoot = root.resolve("trace"),
            memoryRoot = root.resolve("memory"),
            mode = mode,
        )
    }
}

private fun vfsMemoryWorkspace(memory: VfsMemorySource): VfsFileSystem =
    VfsFileSystem.fromJson(
        jsonText = """
            {
              "schemaVersion": 1,
              "vfsContractVersion": "1.0.0",
              "workspaceRoot": "/",
              "mounts": [
                {"id":"memory","source":"memory://dream","target":"/memory","mode":"readWrite","priority":10}
              ]
            }
        """.trimIndent(),
        sourceResolver = VfsSourceResolver { source ->
            if (source == "memory://dream") memory else null
        },
    )

private class CandidateDreamWorker : DreamWorker {
    override suspend fun run(request: DreamRequest): DreamResult =
        DreamResult(
            success = true,
            candidates = listOf(
                RuntimeCandidate(
                    candidateId = "candidate-1",
                    runId = "run-1",
                    type = RuntimeCandidateType.MEMORY,
                    summary = "candidate only; requires review before promotion",
                    confidence = "medium",
                ),
            ),
        )
}
