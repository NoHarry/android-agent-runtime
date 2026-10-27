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

class VfsPerformanceOptimizationTest {
    @Test
    fun tcPerf101To103MemoryReadPaginationUsesSingleByteCacheAndPreservesWindows() = runSuspend {
        VfsPerformanceCounters.reset()
        val pageBytes = VfsPerformanceBaselineFixture.ONE_MIB_PAGE_BYTES
        val pages = VfsPerformanceBaselineFixture.ONE_MIB_PAGINATION_PAGES
        val content = "a".repeat(pageBytes * pages)
        val vfs = memoryFileSystem(VfsMemorySource(mapOf("large.txt" to content)))
        val joined = StringBuilder()

        repeat(pages) { page ->
            val result = vfs.read("/mem/large.txt", offset = page.toLong() * pageBytes, maxBytes = pageBytes)
                as VfsReadResult.Found
            assertEquals(pageBytes, result.bytesRead)
            joined.append(result.text)
        }
        val tail = vfs.read("/mem/large.txt", offset = content.length - 4L, maxBytes = 64) as VfsReadResult.Found
        val beyondEnd = vfs.read("/mem/large.txt", offset = content.length + 10L, maxBytes = 64) as VfsReadResult.Found

        assertEquals(content, joined.toString())
        assertEquals("aaaa", tail.text)
        assertEquals(4, tail.bytesRead)
        assertEquals("", beyondEnd.text)
        assertEquals(0, beyondEnd.bytesRead)
        assertEquals(1L, counter(VFS_COUNTER_MEMORY_READ_FULL_ENCODES))
        assertTrue(
            "optimized cache should beat baseline full encodes",
            counter(VFS_COUNTER_MEMORY_READ_FULL_ENCODES) < VfsPerformanceBaselineFixture.ONE_MIB_PAGINATION_FULL_ENCODES,
        )
        assertEquals(VfsReadResult.InvalidPath, vfs.read("/mem/large.txt", offset = -1, maxBytes = 64))
        assertEquals(VfsReadResult.InvalidPath, vfs.read("/mem/large.txt", offset = 0, maxBytes = 0))
    }

    @Test
    fun tcPerf101To103RoomReadPaginationUsesSingleByteCacheAndInvalidatesOnWrite() = runSuspend {
        VfsPerformanceCounters.reset()
        val content = "b".repeat(96_000)
        val adapter = PerformanceRoomAdapter(content)
        val vfs = roomFileSystem(VfsRoomSource(adapter, writableResources = setOf("sessions")))

        repeat(3) { page ->
            val result = vfs.read("/db/resources/sessions.json", offset = page * 32_000L, maxBytes = 32_000)
                as VfsReadResult.Found
            assertEquals(32_000, result.bytesRead)
        }
        assertEquals(1L, counter(VFS_COUNTER_ROOM_READ_FULL_ENCODES))

        val update = vfs.write(
            "/db/resources/sessions.json",
            """{"operation":"update_row","resource":"sessions","key":"s1","fields":{"content":"updated"}}""",
            VfsWriteMode.OVERWRITE,
        )
        val updated = vfs.read("/db/resources/sessions.json", offset = 0, maxBytes = 64) as VfsReadResult.Found

        assertTrue(update is VfsWriteResult.Written)
        assertTrue(updated.text.contains("updated"))
        assertEquals(2L, counter(VFS_COUNTER_ROOM_READ_FULL_ENCODES))
    }

    @Test
    fun tcPerf306FileReadPaginationUsesSeekBasedWindows() = runSuspend {
        VfsPerformanceCounters.reset()
        val root = tempDir("file-read-seek")
        val pageBytes = 32_000
        val pages = 100
        val content = buildString(pageBytes * pages) {
            repeat(pages) { page ->
                val marker = "page-$page|"
                append(marker)
                append(('a'.code + (page % 26)).toChar().toString().repeat(pageBytes - marker.length))
            }
        }
        File(root, "large.txt").writeText(content, Charsets.UTF_8)
        val vfs = fileSystem(root)

        repeat(pages) { page ->
            val result = vfs.read("/large.txt", offset = page.toLong() * pageBytes, maxBytes = pageBytes)
                as VfsReadResult.Found
            assertEquals(pageBytes, result.bytesRead)
            assertTrue(result.text.startsWith("page-$page|"))
        }
        val tail = vfs.read("/large.txt", offset = content.length - 4L, maxBytes = 64) as VfsReadResult.Found
        val beyondEnd = vfs.read("/large.txt", offset = content.length + 10L, maxBytes = 64) as VfsReadResult.Found

        assertEquals(4, tail.bytesRead)
        assertEquals("", beyondEnd.text)
        assertEquals(0, beyondEnd.bytesRead)
        assertEquals((pages + 2).toLong(), counter(VFS_COUNTER_FILE_READ_OPENS))
        assertEquals((pages + 1).toLong(), counter(VFS_COUNTER_FILE_READ_SEEKS))
    }

    @Test
    fun tcPerf201And205FileGrepUsesOneSafeWalkOneOpenPerSearchedFileAndNoReadLines() = runSuspend {
        VfsPerformanceCounters.reset()
        val root = tempDir("small-grep")
        repeat(VfsPerformanceBaselineFixture.SMALL_GREP_FILE_COUNT) { index ->
            File(root, "file-$index.txt").writeText("line $index needle\n", Charsets.UTF_8)
        }
        val vfs = fileSystem(root)

        val result = vfs.search("/", "needle", maxMatches = 512, maxScanBytes = 128_000)

        assertEquals(
            VfsPerformanceBaselineFixture.SMALL_GREP_FILE_COUNT,
            (result as VfsSearchResult.Matches).matches.size,
        )
        assertEquals(1L, counter(VFS_COUNTER_FILE_SAFE_WALKS))
        assertEquals(0L, counter(VFS_COUNTER_FILE_READ_LINES))
        assertEquals(VfsPerformanceBaselineFixture.SMALL_GREP_FILE_COUNT.toLong(), counter(VFS_COUNTER_FILE_SEARCH_FILE_OPENS))
        assertEquals(
            VfsPerformanceBaselineFixture.SMALL_GREP_FILE_COUNT.toLong(),
            counter(VFS_COUNTER_FILE_BINARY_SAMPLE_READS),
        )
    }

    @Test
    fun tcPerf202And203SearchMatcherKeepsLiteralIgnoreCaseAndBudgetSemantics() = runSuspend {
        VfsPerformanceCounters.reset()
        val source = VfsMemorySource(
            mapOf(
                "case.txt" to "Needle\nn.eedle\n",
                "other.txt" to "plain\n",
            ),
        )
        val vfs = memoryFileSystem(source)

        val ignoreCase = vfs.search("/mem", "needle", maxMatches = 10, maxScanBytes = 128) as VfsSearchResult.Matches
        val regexLikeLiteral = vfs.search("/mem", "n.eedle", maxMatches = 10, maxScanBytes = 128)
            as VfsSearchResult.Matches
        val overBudget = vfs.search("/mem", "needle", maxMatches = 10, maxScanBytes = 4)

        assertEquals(listOf("/mem/case.txt:1:Needle"), ignoreCase.matches.map { "${it.path}:${it.line}:${it.preview}" })
        assertEquals(listOf("/mem/case.txt:2:n.eedle"), regexLikeLiteral.matches.map { "${it.path}:${it.line}:${it.preview}" })
        assertEquals(VfsSearchResult.BudgetExceeded, overBudget)
        assertEquals(0L, counter(VFS_COUNTER_MEMORY_SEARCH_FULL_ENCODES))
    }

    @Test
    fun tcPerf307FileGrepBudgetStreamsAndKeepsEarlyMatch() = runSuspend {
        VfsPerformanceCounters.reset()
        val root = tempDir("streaming-grep-budget")
        File(root, "large.txt").writeText(
            "$NEEDLE\n" + "a".repeat(VfsPerformanceBaselineFixture.LARGE_GREP_BYTES),
            Charsets.UTF_8,
        )
        val vfs = fileSystem(root)

        val earlyMatch = vfs.search("/", NEEDLE, maxMatches = 1, maxScanBytes = 64)
        val overBudgetMiss = vfs.search("/", "missing", maxMatches = 1, maxScanBytes = 64)

        val matches = (earlyMatch as VfsSearchResult.Matches)
        assertEquals(listOf("/large.txt:1:$NEEDLE"), matches.matches.map { "${it.path}:${it.line}:${it.preview}" })
        assertTrue("scannedBytes=${matches.scannedBytes}", matches.scannedBytes <= 64)
        assertEquals(VfsSearchResult.BudgetExceeded, overBudgetMiss)
        assertEquals(2L, counter(VFS_COUNTER_FILE_SAFE_WALKS))
        assertEquals(2L, counter(VFS_COUNTER_FILE_SEARCH_FILE_OPENS))
        assertEquals(2L, counter(VFS_COUNTER_FILE_BINARY_SAMPLE_READS))
        assertEquals(0L, counter(VFS_COUNTER_FILE_READ_LINES))
    }

    @Test
    fun tcPerf203SearchBudgetIsSharedAcrossVisibleMounts() = runSuspend {
        val first = VfsMemorySource(mapOf("first.txt" to "abcdef"))
        val second = VfsMemorySource(mapOf("second.txt" to "needle"))
        val vfs = twoMemoryMountFileSystem(first, second)

        val underBudget = vfs.search("/", "needle", maxMatches = 10, maxScanBytes = 16)
        val overBudget = vfs.search("/", "needle", maxMatches = 10, maxScanBytes = 8)

        assertEquals(listOf("/b/second.txt"), (underBudget as VfsSearchResult.Matches).matches.map { it.path })
        assertEquals(12L, underBudget.scannedBytes)
        assertEquals(VfsSearchResult.BudgetExceeded, overBudget)
    }

    @Test
    fun tcPerf204FileGrepStillRejectsSymlinkEscapes() = runSuspend {
        VfsPerformanceCounters.reset()
        val root = tempDir("symlink-root")
        val outside = tempDir("symlink-outside")
        File(root, "inside.txt").writeText("inside needle", Charsets.UTF_8)
        File(outside, "secret.txt").writeText("outside needle", Charsets.UTF_8)
        if (!createSymlink(File(root, "outside-link"), outside)) return@runSuspend
        val vfs = fileSystem(root)

        val result = vfs.search("/", "outside needle", maxMatches = 10, maxScanBytes = 1024)

        assertFalse((result as VfsSearchResult.Matches).matches.any { it.path.contains("outside-link") })
        assertEquals(1L, counter(VFS_COUNTER_FILE_SAFE_WALKS))
    }

    @Test
    fun tcPerf205LargeFileGrepStreamsWithoutReadLines() = runSuspend {
        VfsPerformanceCounters.reset()
        val root = tempDir("large-grep")
        File(root, "large.txt").writeText(
            "a".repeat(VfsPerformanceBaselineFixture.LARGE_GREP_BYTES - NEEDLE.length) + NEEDLE,
            Charsets.UTF_8,
        )
        val vfs = fileSystem(root)

        val result = vfs.search("/", NEEDLE, maxMatches = 10, maxScanBytes = 2_000_000)

        assertTrue((result as VfsSearchResult.Matches).matches.single().preview.endsWith(NEEDLE))
        assertEquals(1L, counter(VFS_COUNTER_FILE_SAFE_WALKS))
        assertEquals(1L, counter(VFS_COUNTER_FILE_SEARCH_FILE_OPENS))
        assertEquals(1L, counter(VFS_COUNTER_FILE_BINARY_SAMPLE_READS))
        assertEquals(0L, counter(VFS_COUNTER_FILE_READ_LINES))
    }

    private fun memoryFileSystem(source: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = manifest("memory://performance", "/mem", "readWrite"),
            sourceResolver = VfsSourceResolver { if (it == "memory://performance") source else null },
        )

    private fun roomFileSystem(source: VfsRoomSource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = manifest("database://performance", "/db", "readWrite"),
            sourceResolver = VfsSourceResolver { if (it == "database://performance") source else null },
        )

    private fun fileSystem(root: File): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = manifest("file://performance", "/", "readOnly"),
            sourceResolver = VfsSourceResolver { if (it == "file://performance") VfsFileSource(root) else null },
        )

    private fun manifest(source: String, target: String, mode: String): String =
        """
            {
              "schemaVersion": 1,
              "vfsContractVersion": "1.0.0",
              "workspaceRoot": "/",
              "mounts": [
                {"id":"performance","source":"$source","target":"$target","mode":"$mode","priority":10}
              ]
            }
        """.trimIndent()

    private fun twoMemoryMountFileSystem(first: VfsMemorySource, second: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"a","source":"memory://a","target":"/a","mode":"readOnly","priority":10},
                    {"id":"b","source":"memory://b","target":"/b","mode":"readOnly","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                when (source) {
                    "memory://a" -> first
                    "memory://b" -> second
                    else -> null
                }
            },
        )

    private fun counter(name: String): Long =
        VfsPerformanceCounters.snapshot().getOrDefault(name, 0L)

    private fun tempDir(name: String): File =
        Files.createTempDirectory("vfs-performance-$name-").toFile().canonicalFile

    private fun createSymlink(link: File, target: File): Boolean =
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath())
            true
        } catch (_: Exception) {
            false
        }
}

private class PerformanceRoomAdapter(
    private var sessions: String,
) : VfsRoomResourceAdapter {
    override fun schemaJson(): String =
        """{"tables":["sessions"]}"""

    override fun resources(): List<VfsRoomResource> =
        listOf(VfsRoomResource(name = "sessions", displayPath = "sessions.json"))

    override fun readResource(resource: String): String? =
        sessions.takeIf { resource == "sessions" }

    override fun updateRow(payload: VfsRoomUpdateRow): Boolean {
        if (payload.resource != "sessions" || payload.key != "s1") return false
        sessions = payload.fields["content"] ?: return false
        return true
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

private const val NEEDLE = "needle"
