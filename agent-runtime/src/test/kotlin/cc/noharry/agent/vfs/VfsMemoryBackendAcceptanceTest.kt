package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VfsMemoryBackendAcceptanceTest {
    @Test
    fun memoryBackendSupportsListReadWriteSearchAndQuotaEndToEnd() = runSuspend {
        val memory = VfsMemorySource(
            initialFiles = mapOf("initial.txt" to "alpha"),
            quotaBytes = 48,
        )
        val vfs = memoryFileSystem(memory)

        assertTrue(vfs.write("/mem/note.txt", "hello", VfsWriteMode.CREATE) is VfsWriteResult.Written)
        assertEquals(VfsWriteResult.Conflict, vfs.write("/mem/note.txt", "again", VfsWriteMode.CREATE))
        assertTrue(vfs.write("/mem/note.txt", "hello needle", VfsWriteMode.OVERWRITE) is VfsWriteResult.Written)
        assertTrue(vfs.write("/mem/state.txt", "old", VfsWriteMode.CREATE) is VfsWriteResult.Written)
        assertTrue(vfs.write("/mem/state.txt", "atomic needle", VfsWriteMode.ATOMIC_REPLACE) is VfsWriteResult.Written)
        assertTrue(vfs.write("/mem/log.txt", "log", VfsWriteMode.CREATE) is VfsWriteResult.Written)
        assertTrue(vfs.write("/mem/log.txt", " needle", VfsWriteMode.APPEND) is VfsWriteResult.Written)

        val list = (vfs.list("/mem") as VfsListResult.Entries).entries.map { it.name }
        val read = vfs.read("/mem/note.txt", offset = 6, maxBytes = 6)
        val search = vfs.search("/mem", "needle", maxMatches = 10, maxScanBytes = 128)
        val overQuota = vfs.write("/mem/large.txt", "0123456789-0123456789", VfsWriteMode.CREATE)

        assertEquals(listOf("initial.txt", "log.txt", "note.txt", "state.txt"), list)
        assertEquals("needle", (read as VfsReadResult.Found).text)
        assertEquals(
            listOf("/mem/log.txt", "/mem/note.txt", "/mem/state.txt"),
            (search as VfsSearchResult.Matches).matches.map { it.path }.sorted(),
        )
        assertEquals("EINVAL", (overQuota as VfsWriteResult.Error).code)
        assertFalse("large.txt" in memory.snapshot())
    }

    private fun memoryFileSystem(source: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"memory","source":"memory://acceptance","target":"/mem","mode":"readWrite","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { value ->
                if (value == "memory://acceptance") source else null
            },
        )
}

private fun runSuspend(block: suspend () -> Unit) {
    var failure: Throwable? = null
    block.startCoroutine(
        object : Continuation<Unit> {
            override val context = EmptyCoroutineContext

            override fun resumeWith(result: Result<Unit>) {
                failure = result.exceptionOrNull()
            }
        },
    )
    failure?.let { throw it }
}
