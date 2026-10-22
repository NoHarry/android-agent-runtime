package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VfsFileSystemTest {
    @Test
    fun readsHighestPriorityMountedFile() = runSuspend {
        val root = tempDir("root")
        val instructions = tempDir("instructions")
        File(root, "AGENTS.md").writeText("root agents")
        val agents = File(instructions, "AGENTS.md").apply { writeText("asset agents") }
        val vfs = fileSystem(
            manifestJson = manifestJson(
                mounts = """
                    ${mount("root", "file://root", "/", "readWrite", 10)},
                    ${mount("instructions", "file://instructions", "/AGENTS.md", "readOnly", 100)}
                """.trimIndent(),
            ),
            sources = mapOf(
                "file://root" to VfsFileSource(root),
                "file://instructions" to VfsFileSource(agents),
            ),
        )

        val result = vfs.read("/AGENTS.md", offset = 0, maxBytes = 1024)

        assertEquals("asset agents", (result as VfsReadResult.Found).text)
    }

    @Test
    fun listsSyntheticChildrenFromMountedTargets() = runSuspend {
        val root = tempDir("root")
        val vfs = fileSystem(
            manifestJson = manifestJson(
                mounts = """
                    ${mount("root", "file://root", "/", "readWrite", 10)},
                    ${mount("instructions", "file://instructions", "/AGENTS.md", "readOnly", 100)}
                """.trimIndent(),
            ),
            sources = mapOf("file://root" to VfsFileSource(root)),
        )

        val result = vfs.list("/")

        val names = (result as VfsListResult.Entries).entries.map { it.name }
        assertTrue("AGENTS.md" in names)
    }

    @Test
    fun fallsBackToLowerPriorityMountOnlyWhenHigherPrioritySourceMisses() = runSuspend {
        val root = tempDir("root")
        val fallback = File(tempDir("fallback"), "AGENTS.md").apply { writeText("fallback agents") }
        val vfs = fileSystem(
            manifestJson = manifestJson(
                mounts = """
                    ${mount("root", "file://root", "/", "readWrite", 10)},
                    ${mount("fallback", "file://fallback", "/AGENTS.md", "readOnly", -100)}
                """.trimIndent(),
            ),
            sources = mapOf(
                "file://root" to VfsFileSource(root),
                "file://fallback" to VfsFileSource(fallback),
            ),
        )

        val fallbackRead = vfs.read("/AGENTS.md", offset = 0, maxBytes = 1024)
        File(root, "AGENTS.md").writeText("root agents")
        val rootRead = vfs.read("/AGENTS.md", offset = 0, maxBytes = 1024)

        assertEquals("fallback agents", (fallbackRead as VfsReadResult.Found).text)
        assertEquals("root agents", (rootRead as VfsReadResult.Found).text)
    }

    @Test
    fun writesThroughWritableMountAndSearchesVirtualPath() = runSuspend {
        val root = tempDir("root")
        val vfs = fileSystem(
            manifestJson = manifestJson(mount("root", "file://root", "/", "readWrite", 10)),
            sources = mapOf("file://root" to VfsFileSource(root)),
        )

        val write = vfs.write("/memory/note.txt", "hello vfs", VfsWriteMode.CREATE)
        val search = vfs.search("/memory", "vfs", maxMatches = 10, maxScanBytes = 1024)

        assertTrue(write is VfsWriteResult.Written)
        val matches = (search as VfsSearchResult.Matches).matches
        assertEquals("/memory/note.txt", matches.single().path)
    }

    @Test
    fun fileSourceStatRevisionAndConditionalWriteDetectStaleRevision() = runSuspend {
        val root = tempDir("root")
        File(root, "note.txt").writeText("first")
        val vfs = fileSystem(
            manifestJson = manifestJson(mount("root", "file://root", "/", "readWrite", 10)),
            sources = mapOf("file://root" to VfsFileSource(root)),
        )

        val stat = (vfs.stat("/note.txt") as VfsStatResult.Found).stat
        val read = vfs.read("/note.txt", offset = 0, maxBytes = 64) as VfsReadResult.Found
        val freshWrite = vfs.write(
            "/note.txt",
            "second",
            VfsWriteMode.OVERWRITE,
            expectedRevision = read.revision,
        )
        File(root, "note.txt").writeText("external change with different length")
        val staleWrite = vfs.write(
            "/note.txt",
            "third",
            VfsWriteMode.OVERWRITE,
            expectedRevision = (freshWrite as VfsWriteResult.Written).revision,
        )

        assertEquals(VFS_ENTRY_FILE, stat.type)
        assertEquals("first", read.text)
        assertEquals(read.revision, stat.revision)
        assertEquals(5L, stat.sizeBytes)
        assertTrue(read.revision?.isNotBlank() == true)
        assertTrue(freshWrite.revision?.isNotBlank() == true)
        assertEquals(VfsWriteResult.Conflict, staleWrite)
    }

    @Test
    fun memorySourceStatRevisionAndConditionalWriteDetectStaleRevision() = runSuspend {
        val memory = VfsMemorySource(mapOf("note.txt" to "first"))
        val vfs = fileSystem(
            manifestJson = manifestJson(mount("memory", "memory://root", "/", "readWrite", 10)),
            sources = mapOf("memory://root" to memory),
        )

        val entry = (vfs.list("/") as VfsListResult.Entries).entries.single { it.name == "note.txt" }
        val stat = (vfs.stat("/note.txt") as VfsStatResult.Found).stat
        val read = vfs.read("/note.txt", offset = 0, maxBytes = 64) as VfsReadResult.Found
        val freshWrite = vfs.write(
            "/note.txt",
            "second",
            VfsWriteMode.OVERWRITE,
            expectedRevision = read.revision,
        ) as VfsWriteResult.Written
        val staleWrite = vfs.write(
            "/note.txt",
            "third",
            VfsWriteMode.OVERWRITE,
            expectedRevision = read.revision,
        )

        assertEquals(VFS_ENTRY_FILE, entry.type)
        assertEquals(entry.revision, stat.revision)
        assertEquals(read.revision, stat.revision)
        assertEquals(5L, stat.sizeBytes)
        assertEquals(6L, freshWrite.sizeBytes)
        assertEquals(VfsWriteResult.Conflict, staleWrite)
    }

    @Test
    fun fromJsonCanMountMemoryBackedDirectory() = runSuspend {
        val memory = VfsMemorySource(
            mapOf(
                "session/current.json" to """{"turns":2}""",
                "todo.json" to """{"items":["read"]}""",
            ),
        )
        val vfs = VfsFileSystem.fromJson(
            jsonText = manifestJson(mount("context", "memory://context", "/context", "readWrite", 20)),
            sourceResolver = VfsSourceResolver { source ->
                if (source == "memory://context") memory else null
            },
        )

        val list = vfs.list("/context")
        val read = vfs.read("/context/session/current.json", offset = 0, maxBytes = 1024)
        val write = vfs.write("/context/new.json", "hello memory", VfsWriteMode.CREATE)

        val names = (list as VfsListResult.Entries).entries.map { it.name }
        assertEquals(listOf("session", "todo.json"), names)
        assertEquals("""{"turns":2}""", (read as VfsReadResult.Found).text)
        assertTrue(write is VfsWriteResult.Written)
        assertEquals("hello memory", memory.snapshot()["new.json"])
    }

    @Test
    fun manifestSourceUrisCanRouteAssetsMemoryAndDatabaseStyleMounts() = runSuspend {
        val root = tempDir("root")
        File(root, "host.txt").writeText("host file")
        val memory = VfsMemorySource(mapOf("runtime-state.json" to """{"state":"hot"}"""))
        val database = VfsRoomSource(SessionIndexAdapter())
        val skills = VfsMemorySource(mapOf("debug/SKILL.md" to "skill body"))
        val vfs = fileSystem(
            manifestJson = manifestJson(
                mounts = """
                    ${mount("root", "app-private://workspace", "/", "readWrite", 10)},
                    ${mount("skills", "asset://runtime-workspace/skills", "/.agents/skills", "readOnly", 90)},
                    ${mount("memory", "memory://runtime", "/memory/host", "readWrite", 80)},
                    ${mount("sessions", "database://session-index", "/sessions", "readOnly", 80)}
                """.trimIndent(),
            ),
            sources = mapOf(
                "app-private://workspace" to VfsFileSource(root),
                "asset://runtime-workspace/skills" to skills,
                "memory://runtime" to memory,
                "database://session-index" to database,
            ),
        )

        val rootList = (vfs.list("/") as VfsListResult.Entries).entries.map { it.name }
        val skill = vfs.read("/.agents/skills/debug/SKILL.md", offset = 0, maxBytes = 1024)
        val sessionIndex = vfs.read("/sessions/resources/sessions.json", offset = 0, maxBytes = 1024)
        val write = vfs.write("/memory/host/latest.json", """{"ok":true}""", VfsWriteMode.CREATE)

        assertTrue("host.txt" in rootList)
        assertTrue(".agents" in rootList)
        assertTrue("memory" in rootList)
        assertTrue("sessions" in rootList)
        assertEquals("skill body", (skill as VfsReadResult.Found).text)
        assertEquals("[]", (sessionIndex as VfsReadResult.Found).text)
        assertTrue(write is VfsWriteResult.Written)
        assertEquals("""{"ok":true}""", memory.snapshot()["latest.json"])
    }

    @Test
    fun searchRootIncludesMountedChildSources() = runSuspend {
        val root = tempDir("root")
        File(root, "host.txt").writeText("host file")
        val skills = VfsMemorySource(mapOf("debug/SKILL.md" to "mounted search needle"))
        val vfs = fileSystem(
            manifestJson = manifestJson(
                mounts = """
                    ${mount("root", "app-private://workspace", "/", "readWrite", 10)},
                    ${mount("skills", "asset://runtime-workspace/skills", "/.agents/skills", "readOnly", 90)}
                """.trimIndent(),
            ),
            sources = mapOf(
                "app-private://workspace" to VfsFileSource(root),
                "asset://runtime-workspace/skills" to skills,
            ),
        )

        val search = vfs.search("/", "needle", maxMatches = 10, maxScanBytes = 1024)

        val matches = (search as VfsSearchResult.Matches).matches
        assertEquals("/.agents/skills/debug/SKILL.md", matches.single().path)
    }

    @Test
    fun searchHandlesMatchLimitBoundariesBeforeSourceMerge() = runSuspend {
        val root = tempDir("root")
        File(root, "host.txt").writeText("host needle")
        val vfs = fileSystem(
            manifestJson = manifestJson(mount("root", "app-private://workspace", "/", "readWrite", 10)),
            sources = mapOf("app-private://workspace" to VfsFileSource(root)),
        )

        val zero = vfs.search("/", "needle", maxMatches = 0, maxScanBytes = 1024)
        val negative = vfs.search("/", "needle", maxMatches = -1, maxScanBytes = 1024)

        assertTrue((zero as VfsSearchResult.Matches).matches.isEmpty())
        assertEquals(VfsSearchResult.InvalidPattern, negative)
    }

    private fun fileSystem(
        manifestJson: String,
        sources: Map<String, VfsSource>,
    ): VfsFileSystem {
        return VfsFileSystem.fromJson(manifestJson, VfsSourceResolver { source -> sources[source] })
    }

    private fun manifestJson(mounts: String): String =
        """
            {
              "schemaVersion": 1,
              "vfsContractVersion": "1.0.0",
              "workspaceRoot": "/",
              "mounts": [
                $mounts
              ]
            }
        """.trimIndent()

    private fun mount(
        id: String,
        source: String,
        target: String,
        mode: String,
        priority: Int,
    ): String =
        """
            {
              "id": "$id",
              "source": "$source",
              "target": "$target",
              "mode": "$mode",
              "priority": $priority
            }
        """.trimIndent()

    private fun tempDir(name: String): File =
        Files.createTempDirectory("vfs-$name-").toFile().canonicalFile
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

private class SessionIndexAdapter : VfsRoomResourceAdapter {
    override fun schemaJson(): String =
        """{"tables":["sessions"]}"""

    override fun resources(): List<VfsRoomResource> =
        listOf(VfsRoomResource(name = "sessions", displayPath = "sessions.json"))

    override fun readResource(resource: String): String? =
        if (resource == "sessions") "[]" else null

    override fun updateRow(payload: VfsRoomUpdateRow): Boolean =
        false
}
