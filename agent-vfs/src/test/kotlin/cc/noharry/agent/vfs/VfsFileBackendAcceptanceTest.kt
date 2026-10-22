package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VfsFileBackendAcceptanceTest {
    @Test
    fun fileBackendSupportsListReadWriteModesAndSearchEndToEnd() = runSuspend {
        val root = tempDir("file-e2e")
        val vfs = fileSystem(root, mode = "readWrite")

        assertTrue(vfs.write("/docs/note.txt", "hello", VfsWriteMode.CREATE) is VfsWriteResult.Written)
        assertEquals(VfsWriteResult.Conflict, vfs.write("/docs/note.txt", "again", VfsWriteMode.CREATE))
        assertTrue(vfs.write("/docs/note.txt", "hello vfs", VfsWriteMode.OVERWRITE) is VfsWriteResult.Written)
        assertTrue(vfs.write("/docs/note.txt", "\nneedle", VfsWriteMode.APPEND) is VfsWriteResult.Written)
        assertTrue(vfs.write("/docs/atomic.txt", "stale", VfsWriteMode.CREATE) is VfsWriteResult.Written)
        assertTrue(vfs.write("/docs/atomic.txt", "atomic needle", VfsWriteMode.ATOMIC_REPLACE) is VfsWriteResult.Written)

        val names = (vfs.list("/docs") as VfsListResult.Entries).entries.map { it.name }
        val read = vfs.read("/docs/note.txt", offset = 6, maxBytes = 3)
        val search = vfs.search("/docs", "needle", maxMatches = 10, maxScanBytes = 1024)

        assertEquals(listOf("atomic.txt", "note.txt"), names)
        assertEquals("vfs", (read as VfsReadResult.Found).text)
        assertEquals(3, read.bytesRead)
        assertEquals(
            listOf("/docs/atomic.txt", "/docs/note.txt"),
            (search as VfsSearchResult.Matches).matches.map { it.path }.sorted(),
        )
    }

    @Test
    fun fileBackendRejectsReadOnlyWritesInvalidPathsAndEscapes() = runSuspend {
        val root = tempDir("file-safety")
        val outside = tempDir("file-outside")
        File(root, "inside.txt").writeText("inside needle")
        File(outside, "secret.txt").writeText("outside needle")
        val symlinkCreated = createSymlink(root.resolve("outside-link"), outside)
        val vfs = fileSystem(root, mode = "readOnly")

        val write = vfs.write("/blocked.txt", "blocked", VfsWriteMode.OVERWRITE)
        val invalid = vfs.read("/../secret.txt", offset = 0, maxBytes = 16)
        val escapedRead = if (symlinkCreated) {
            vfs.read("/outside-link/secret.txt", offset = 0, maxBytes = 64)
        } else {
            VfsReadResult.InvalidPath
        }
        val search = vfs.search("/", "outside needle", maxMatches = 10, maxScanBytes = 1024)

        assertEquals(VfsWriteResult.WriteDenied, write)
        assertEquals(VfsReadResult.InvalidPath, invalid)
        assertEquals(VfsReadResult.InvalidPath, escapedRead)
        assertFalse((search as VfsSearchResult.Matches).matches.any { it.path.contains("outside-link") })
    }

    private fun fileSystem(root: File, mode: String): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"files","source":"file://files","target":"/","mode":"$mode","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                if (source == "file://files") VfsFileSource(root) else null
            },
        )

    private fun tempDir(name: String): File =
        Files.createTempDirectory("vfs-$name-").toFile().canonicalFile

    private fun createSymlink(link: File, target: File): Boolean =
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: Exception) {
            false
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
