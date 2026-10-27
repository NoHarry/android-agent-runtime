package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VfsCoreAcceptanceTest {
    @Test
    fun mountRoutingAndPathSecurityAreEnforcedBeforeBackendAccess() = runSuspend {
        val root = VfsMemorySource(mapOf("root.txt" to "root"))
        val child = VfsMemorySource(mapOf("note.txt" to "child needle"))
        val vfs = VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"root","source":"memory://root","target":"/","mode":"readWrite","priority":10},
                    {"id":"child","source":"memory://child","target":"/data","mode":"readOnly","priority":100}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                when (source) {
                    "memory://root" -> root
                    "memory://child" -> child
                    else -> null
                }
            },
        )

        val rootList = (vfs.list("/") as VfsListResult.Entries).entries.map { it.name }
        val childRead = vfs.read("/data/note.txt", offset = 0, maxBytes = 64)
        val deniedWrite = vfs.write("/data/note.txt", "blocked", VfsWriteMode.OVERWRITE)
        val invalidRead = vfs.read("/../secret.txt", offset = 0, maxBytes = 64)
        val missing = vfs.read("/missing.txt", offset = 0, maxBytes = 64)

        assertTrue("data" in rootList)
        assertEquals("child needle", (childRead as VfsReadResult.Found).text)
        assertEquals(VfsWriteResult.WriteDenied, deniedWrite)
        assertEquals("child needle", child.snapshot().getValue("note.txt"))
        assertTrue("data/note.txt" !in root.snapshot())
        assertEquals(VfsReadResult.InvalidPath, invalidRead)
        assertEquals(VfsReadResult.NotFound, missing)
    }

    @Test
    fun resourceLimitsReturnStableErrors() = runSuspend {
        val source = VfsMemorySource(mapOf("a.txt" to "needle", "b.txt" to "needle"))
        val vfs = VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"mem","source":"memory://mem","target":"/mem","mode":"readWrite","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { if (it == "memory://mem") source else null },
        )

        val invalidSearch = vfs.search("/mem", "", maxMatches = 10, maxScanBytes = 64)
        val limited = vfs.search("/mem", "needle", maxMatches = 1, maxScanBytes = 64)
        val overBudget = vfs.search("/mem", "needle", maxMatches = 10, maxScanBytes = 4)

        assertEquals(VfsSearchResult.InvalidPattern, invalidSearch)
        assertEquals(1, (limited as VfsSearchResult.Matches).matches.size)
        assertEquals(VfsSearchResult.BudgetExceeded, overBudget)
    }
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
