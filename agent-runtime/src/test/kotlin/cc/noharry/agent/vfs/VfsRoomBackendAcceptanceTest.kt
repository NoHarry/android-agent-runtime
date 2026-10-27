package cc.noharry.agent.vfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class VfsRoomBackendAcceptanceTest {
    @Test
    fun roomBackendSupportsControlledReadQueryUpdateAndSearchEndToEnd() = runSuspend {
        val adapter = FakeRoomAdapter()
        val vfs = roomFileSystem(VfsRoomSource(adapter, writableResources = setOf("sessions")))

        val root = (vfs.list("/db") as VfsListResult.Entries).entries.map { it.name }
        val resources = (vfs.list("/db/resources") as VfsListResult.Entries).entries.map { it.name }
        val schema = vfs.read("/db/schema.json", offset = 0, maxBytes = 512)
        val query = vfs.read("/db/queries/recent_sessions.json", offset = 0, maxBytes = 512)
        val search = vfs.search("/db", "Alice", maxMatches = 10, maxScanBytes = 2048)
        val update = vfs.write("/db/resources/sessions.json", UPDATE_SESSION, VfsWriteMode.OVERWRITE)
        val updated = vfs.read("/db/resources/sessions.json", offset = 0, maxBytes = 512)

        assertEquals(listOf("schema.json", "resources", "queries").sorted(), root.sorted())
        assertEquals(listOf("messages.json", "sessions.json"), resources)
        assertTrue((schema as VfsReadResult.Found).text.contains("sessions"))
        assertTrue((query as VfsReadResult.Found).text.contains("recent_sessions"))
        assertTrue((search as VfsSearchResult.Matches).matches.any { it.path == "/db/resources/sessions.json" })
        assertTrue(update is VfsWriteResult.Written)
        assertTrue((updated as VfsReadResult.Found).text.contains("updated"))
    }

    @Test
    fun roomBackendRejectsUnsafeSqlPhysicalDbAndUnsupportedWrites() = runSuspend {
        val adapter = FakeRoomAdapter()
        val vfs = roomFileSystem(VfsRoomSource(adapter, writableResources = setOf("sessions")))

        val append = vfs.write("/db/resources/sessions.json", UPDATE_SESSION, VfsWriteMode.APPEND)
        val unwritable = vfs.write("/db/resources/messages.json", UPDATE_MESSAGE, VfsWriteMode.OVERWRITE)
        val pathMismatch = vfs.write("/db/resources/messages.json", UPDATE_SESSION, VfsWriteMode.OVERWRITE)
        val schemaWrite = vfs.write("/db/schema.json", UPDATE_SESSION, VfsWriteMode.OVERWRITE)
        val dangerous = vfs.write("/db/resources/sessions.json", "DROP TABLE sessions", VfsWriteMode.OVERWRITE)
        val physicalDb = vfs.read("/db/runtime.db", offset = 0, maxBytes = 128)

        assertEquals("ENOTSUP", (append as VfsWriteResult.Error).code)
        assertEquals("EPERM", (unwritable as VfsWriteResult.Error).code)
        assertEquals("EPERM", (pathMismatch as VfsWriteResult.Error).code)
        assertEquals("EPERM", (schemaWrite as VfsWriteResult.Error).code)
        assertEquals("EPERM", (dangerous as VfsWriteResult.Error).code)
        assertEquals("EACCES", (physicalDb as VfsReadResult.Error).code)
    }

    private fun roomFileSystem(source: VfsRoomSource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"room","source":"database://room","target":"/db","mode":"readWrite","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { value ->
                if (value == "database://room") source else null
            },
        )
}

private class FakeRoomAdapter : VfsRoomResourceAdapter {
    private val rows = linkedMapOf(
        "sessions" to """[{"id":"s1","title":"Alice drive","version":"1"}]""",
        "messages" to """[{"id":"m1","session_id":"s1","text":"hello Alice"}]""",
    )

    override fun schemaJson(): String =
        """{"tables":["sessions","messages"],"queries":["recent_sessions"]}"""

    override fun resources(): List<VfsRoomResource> =
        rows.keys.map { name -> VfsRoomResource(name = name, displayPath = "$name.json") }

    override fun readResource(resource: String): String? =
        rows[resource]

    override fun queryNames(): List<String> =
        listOf("recent_sessions")

    override fun readQuery(name: String): String? =
        if (name == "recent_sessions") """{"query":"recent_sessions","rows":${rows.getValue("sessions")}}""" else null

    override fun updateRow(payload: VfsRoomUpdateRow): Boolean {
        if (payload.resource !in rows || payload.key != "s1") return false
        val title = payload.fields["title"] ?: return false
        rows[payload.resource] = """[{"id":"s1","title":"$title","version":"2"}]"""
        return true
    }
}

private const val UPDATE_SESSION = """
    {"operation":"update_row","resource":"sessions","key":"s1","fields":{"title":"updated"},"expectedVersion":"1"}
"""

private const val UPDATE_MESSAGE = """
    {"operation":"update_row","resource":"messages","key":"m1","fields":{"text":"blocked"}}
"""

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
