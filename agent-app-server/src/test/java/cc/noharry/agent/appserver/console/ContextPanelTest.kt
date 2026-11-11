package cc.noharry.agent.appserver.console
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsSourceResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.appserver.runtime.decodeTodoToolResult
import cc.noharry.agent.appserver.runtime.readLessons
import cc.noharry.agent.appserver.runtime.readPendingLessons
import cc.noharry.agent.appserver.runtime.readSkills
import cc.noharry.agent.appserver.runtime.encodeSessionId

class ContextPanelTest {
    @Test
    fun decodesTodoToolResultPayloadFromEventStream() {
        val entries = decodeTodoToolResult(
            """{"ok":true,"todoCount":2,"todos":[{"content":"turn on AC","status":"in_progress"},{"content":"set temp","status":"pending"}]}""",
        )

        assertEquals(
            listOf("turn on AC" to "in_progress", "set temp" to "pending"),
            entries?.map { it.content to it.status },
        )
    }

    @Test
    fun rejectsFailureRedactedAndMalformedTodoToolResults() {
        assertNull(decodeTodoToolResult("""{"ok":false,"code":"TODO_STORE_WRITE_FAILED","message":"x"}"""))
        assertNull(decodeTodoToolResult("""{"redacted":true}"""))
        assertNull(decodeTodoToolResult("""{"ok":true}"""))
        assertNull(decodeTodoToolResult("not json"))
    }

    @Test
    fun readsLessonsFromVfsMemoryMount() = runBlocking {
        val encodedScene = encodeSessionId("air_control")
        val memory = VfsMemorySource(
            mapOf(
                "lessons/$encodedScene/lessons.json" to
                    """[{"id":"l1","scene":"air_control","type":"MEMORY","content":"avoid duplicate AC retries","confidence":0.75,"hitCount":2,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":2}]""",
                "lessons/$encodedScene/pending.json" to
                    """[{"id":"p1","scene":"air_control","type":"MEMORY","content":"candidate AC retry lesson","confidence":0.45,"hitCount":1,"createdAtEpochMillis":3,"lastValidatedAtEpochMillis":4}]""",
            ),
        )
        val workspace = memoryOnlyWorkspace(memory)

        val lessons = readLessons(workspace)
        val pending = readPendingLessons(workspace)

        assertEquals(1, lessons.size)
        assertEquals("air_control", lessons[0].scene)
        assertEquals("avoid duplicate AC retries", lessons[0].content)
        assertEquals(1, pending.size)
        assertEquals("candidate AC retry lesson", pending[0].content)
    }

    @Test
    fun readsSkillsFromMountedSkillMdAssets() = runBlocking {
        val skillsSource = VfsMemorySource(
            mapOf(
                "debug_voice_progress/SKILL.md" to """
                    ---
                    name: debug_voice_progress
                    description: Emit short marked progress text for debug-console TTS during complex multi-intent tasks.
                    scenes: ["debug-console"]
                    ---

                    # Debug Voice Progress
                """.trimIndent(),
            ),
        )
        val workspace = skillsOnlyWorkspace(skillsSource)

        val skills = readSkills(workspace)

        assertEquals(1, skills.size)
        assertEquals("debug_voice_progress", skills[0].name)
        assertTrue(skills[0].description.contains("TTS"))
    }

    @Test
    fun readsLessonsAcrossSceneDirectories() {
        val tempDir = createTempDir()
        try {
            val memoryDir = File(tempDir, "memory").apply { mkdirs() }
            val lessonsDir = File(memoryDir, "lessons").apply { mkdirs() }
            // Scene "air_control" encoded as base64url.
            val sceneDir = File(lessonsDir, encodeSessionId("air_control")).apply { mkdirs() }
            val lessonsJson = """[{"id":"l1","scene":"air_control","type":"MEMORY","content":"prefer set over step","confidence":0.8,"hitCount":3,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":2}]"""
            File(sceneDir, "lessons.json").writeText(lessonsJson)
            val pendingJson = """[{"id":"p1","scene":"air_control","type":"MEMORY","content":"pending set over step","confidence":0.45,"hitCount":1,"createdAtEpochMillis":3,"lastValidatedAtEpochMillis":4}]"""
            File(sceneDir, "pending.json").writeText(pendingJson)

            val entries = readLessons(memoryDir)
            val pending = readPendingLessons(memoryDir)

            assertEquals(1, entries.size)
            assertEquals("air_control", entries[0].scene)
            assertEquals("prefer set over step", entries[0].content)
            assertEquals(0.8f, entries[0].confidence, 0.001f)
            assertEquals(3, entries[0].hitCount)
            assertEquals(1, pending.size)
            assertEquals("pending set over step", pending[0].content)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun encodeSessionIdMatchesBase64UrlSafeNoPadding() {
        val sessionId = "debug-console-session"
        val encoded = encodeSessionId(sessionId)

        // Base64url no padding: only [A-Za-z0-9_-] chars, no '=' suffix.
        assertTrue(encoded.matches(Regex("^[A-Za-z0-9_-]+$")))
        assertTrue(!encoded.endsWith("="))
    }

    private fun createTempDir(): File = File(System.getProperty("java.io.tmpdir"), "debug-ctx-test-${System.nanoTime()}").apply { mkdirs() }

    private fun memoryOnlyWorkspace(memory: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"memory","source":"memory://debug-runtime-memory","target":"/memory","mode":"readWrite","priority":80}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                if (source == "memory://debug-runtime-memory") memory else null
            },
        )

    private fun skillsOnlyWorkspace(skills: VfsMemorySource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"skills","source":"memory://debug-skills","target":"/.agents/skills","mode":"readOnly","priority":80}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                if (source == "memory://debug-skills") skills else null
            },
        )
}
