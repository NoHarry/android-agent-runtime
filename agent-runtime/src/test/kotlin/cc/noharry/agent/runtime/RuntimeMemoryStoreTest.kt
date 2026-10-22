package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsMemorySource
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsSource
import cc.noharry.agent.vfs.VfsSourceResolver
import cc.noharry.agent.vfs.VfsWriteMode
import cc.noharry.agent.vfs.VfsWriteResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import cc.noharry.agent.kernel.core.*

class RuntimeMemoryStoreTest {
    @Test
    fun lowConfidenceCandidateRequiresRepeatedEvidenceBeforeActiveLesson() = runBlocking {
        val store = RuntimeMemoryStore(Files.createTempDirectory("runtime-memory-").toFile())
        val candidate = memoryCandidate("Efficient path: read_file->set_temperature")

        store.promote(candidate, scene = "ac_control")
        store.promote(candidate.copy(candidateId = "candidate-2", runId = "run-2"), scene = "ac_control")

        assertTrue(store.lessonsFor("ac_control").isEmpty())

        store.promote(candidate.copy(candidateId = "candidate-3", runId = "run-3"), scene = "ac_control")
        val lesson = store.lessonsFor("ac_control").single()

        assertEquals("Efficient path: read_file->set_temperature", lesson.content)
        assertEquals(3, lesson.hitCount)
        assertEquals(3, lesson.evidenceRunKeys.size)
        assertEquals(RuntimeLessonMode.QUERY_ONLY, lesson.mode)
        assertTrue(lesson.confidence >= CONFIDENCE_GATE)
    }

    @Test
    fun highConfidenceCandidateRequiresRepeatedEvidenceBeforeActiveLesson() = runBlocking {
        val store = RuntimeMemoryStore(Files.createTempDirectory("runtime-memory-high-").toFile())
        val candidate = memoryCandidate("Repeated schema failure", confidence = "high")

        store.promote(candidate, scene = "ac_control")

        assertTrue(store.lessonsFor("ac_control").isEmpty())

        store.promote(candidate.copy(candidateId = "candidate-2", runId = "run-2"), scene = "ac_control")

        assertTrue(store.lessonsFor("ac_control").isEmpty())

        store.promote(candidate.copy(candidateId = "candidate-3", runId = "run-3"), scene = "ac_control")

        assertEquals("Repeated schema failure", store.lessonsFor("ac_control").single().content)
    }

    @Test
    fun vfsBackedStorePersistsLessonsThroughMemoryMount() = runBlocking {
        val memory = VfsMemorySource()
        val store = RuntimeMemoryStore(memoryVfs(memory))

        store.promote(memoryCandidate("Use VFS memory", confidence = "high"), scene = "ac_control")
        store.promote(
            memoryCandidate("Use VFS memory", confidence = "high")
                .copy(candidateId = "candidate-2", runId = "run-2"),
            scene = "ac_control",
        )
        store.promote(
            memoryCandidate("Use VFS memory", confidence = "high")
                .copy(candidateId = "candidate-3", runId = "run-3"),
            scene = "ac_control",
        )

        assertEquals("Use VFS memory", store.lessonsFor("ac_control").single().content)
        assertTrue(memory.snapshot().keys.any { it.startsWith("lessons/") && it.endsWith("lessons.json") })
    }

    @Test
    fun capabilityLessonsMergeBySetSemantics() = runBlocking {
        val store = RuntimeMemoryStore(Files.createTempDirectory("runtime-memory-merge-").toFile())
        val first = "Efficient capability search plan: navi key word search; music play search music"
        val reordered = "Efficient capability search plan: music play search music; navi key word search"

        store.promote(memoryCandidate(first, confidence = "high"), scene = "navigation")
        store.promote(
            memoryCandidate(reordered, confidence = "high").copy(candidateId = "candidate-2", runId = "run-2"),
            scene = "navigation",
        )
        store.promote(
            memoryCandidate(first, confidence = "high").copy(candidateId = "candidate-3", runId = "run-3"),
            scene = "navigation",
        )

        val lesson = store.lessonsFor("navigation").single()
        assertEquals(3, lesson.hitCount)
        assertTrue(lesson.content.contains("navi key word search"))
        assertTrue(lesson.content.contains("music play search music"))
    }

    @Test
    fun stalePrewarmLessonDecaysToQueryableOnly() = runBlocking {
        var now = 0L
        val store = RuntimeMemoryStore(
            Files.createTempDirectory("runtime-memory-decay-").toFile(),
            nowEpochMillis = { now },
        )
        val candidate = memoryCandidate("Capability search hints: destination POI", confidence = "high")
        store.promote(candidate, "navigation", RuntimeLessonMode.PREWARM_ELIGIBLE)
        store.promote(
            candidate.copy(candidateId = "candidate-2", runId = "run-2"),
            "navigation",
            RuntimeLessonMode.PREWARM_ELIGIBLE,
        )
        store.promote(
            candidate.copy(candidateId = "candidate-3", runId = "run-3"),
            "navigation",
            RuntimeLessonMode.PREWARM_ELIGIBLE,
        )

        assertEquals(RuntimeLessonMode.PREWARM_ELIGIBLE, store.lessonsFor("navigation").single().mode)

        now = TWENTY_TWO_DAYS_MILLIS

        val stale = store.lessonsFor("navigation").single()
        assertEquals(RuntimeLessonMode.QUERY_ONLY, stale.mode)
        assertTrue(RuntimeMemoryRetriever().retrieve("navigate home", listOf(stale)).matches.isEmpty())
    }

    @Test
    fun legacyLessonWithoutModeLoadsAsQueryOnly() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-legacy-").toFile()
        val sceneDir = File(File(root, "lessons"), encodeFileName("navigation")).apply { mkdirs() }
        File(sceneDir, "lessons.json").writeText(
            """[{"id":"legacy","scene":"navigation","type":"MEMORY","content":"Capability search hints: destination poi","confidence":0.9,"hitCount":2,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":1}]""",
        )

        val lesson = RuntimeMemoryStore(root, nowEpochMillis = { 1L }).lessonsFor("navigation").single()

        assertEquals(RuntimeLessonMode.QUERY_ONLY, lesson.mode)
        assertTrue(lesson.evidenceRunKeys.isEmpty())
    }

    @Test
    fun v1VfsBackedLessonWithoutModeLoadsAsQueryOnly() = runBlocking {
        val sceneKey = encodeFileName("navigation")
        val memory = VfsMemorySource(
            mapOf(
                "lessons/$sceneKey/lessons.json" to
                    """[{"schemaVersion":1,"id":"legacy-vfs","scene":"navigation","type":"MEMORY","content":"Capability search hints: destination poi","confidence":0.9,"hitCount":2,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":1}]""",
            ),
        )

        val lesson = RuntimeMemoryStore(memoryVfs(memory), nowEpochMillis = { 1L })
            .lessonsFor("navigation")
            .single()

        assertEquals(RuntimeLessonMode.QUERY_ONLY, lesson.mode)
        assertTrue(lesson.evidenceRunKeys.isEmpty())
    }

    @Test
    fun unknownStoredLessonModeFailsClosedToQueryOnly() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-unknown-mode-").toFile()
        val sceneDir = File(File(root, "lessons"), encodeFileName("navigation")).apply { mkdirs() }
        File(sceneDir, "lessons.json").writeText(
            """[{"schemaVersion":2,"id":"future-mode","scene":"navigation","type":"MEMORY","content":"Capability search hints: destination poi","confidence":0.9,"hitCount":3,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":1,"mode":"FUTURE_MODE","evidenceRunKeys":["one","two","three"]}]""",
        )

        val lesson = RuntimeMemoryStore(root, nowEpochMillis = { 1L }).lessonsFor("navigation").single()

        assertEquals(RuntimeLessonMode.QUERY_ONLY, lesson.mode)
    }

    @Test
    fun v1LessonCannotClaimPrewarmPrivilegeWithV2Fields() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-v1-forged-privilege-").toFile()
        val sceneDir = File(File(root, "lessons"), encodeFileName("navigation")).apply { mkdirs() }
        File(sceneDir, "lessons.json").writeText(
            """[{"schemaVersion":1,"id":"legacy-forged","scene":"navigation","type":"MEMORY","content":"Observed capability set: navi cal route","confidence":0.9,"hitCount":3,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":1,"mode":"PREWARM_ELIGIBLE","evidenceRunKeys":["one","two","three"]}]""",
        )

        val lesson = RuntimeMemoryStore(root, nowEpochMillis = { 1L }).lessonsFor("navigation").single()

        assertEquals(RuntimeLessonMode.QUERY_ONLY, lesson.mode)
        assertTrue(lesson.evidenceRunKeys.isEmpty())
        assertTrue(RuntimeMemoryRetriever().retrieve("navigate home", listOf(lesson)).matches.isEmpty())
    }

    @Test
    fun futureSchemaVersionCannotClaimCurrentPrewarmPrivilege() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-future-schema-").toFile()
        val sceneDir = File(File(root, "lessons"), encodeFileName("navigation")).apply { mkdirs() }
        File(sceneDir, "lessons.json").writeText(
            """[{"schemaVersion":3,"id":"future","scene":"navigation","type":"MEMORY","content":"Observed capability set: navi cal route","confidence":0.9,"hitCount":3,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":1,"mode":"PREWARM_ELIGIBLE","evidenceRunKeys":["one","two","three"]}]""",
        )

        val lesson = RuntimeMemoryStore(root, nowEpochMillis = { 1L }).lessonsFor("navigation").single()

        assertEquals(RuntimeLessonMode.QUERY_ONLY, lesson.mode)
        assertTrue(lesson.evidenceRunKeys.isEmpty())
    }

    @Test
    fun freshLegacyDuplicateCannotRescueStaleV2PrewarmPrivilege() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-duplicate-privilege-").toFile()
        val sceneDir = File(File(root, "lessons"), encodeFileName("navigation")).apply { mkdirs() }
        File(sceneDir, "lessons.json").writeText(
            """
            [
              {"schemaVersion":2,"id":"stale-v2","scene":"navigation","type":"MEMORY","content":"Observed capability set: navi cal route","confidence":0.75,"hitCount":3,"createdAtEpochMillis":0,"lastValidatedAtEpochMillis":0,"lastDecayedAtEpochMillis":0,"mode":"PREWARM_ELIGIBLE","evidenceRunKeys":["one","two","three"]},
              {"id":"fresh-v1","scene":"navigation","type":"MEMORY","content":"Observed capability set: navi cal route","confidence":0.95,"hitCount":9,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":$TWENTY_TWO_DAYS_MILLIS}
            ]
            """.trimIndent(),
        )

        val lesson = RuntimeMemoryStore(root, nowEpochMillis = { TWENTY_TWO_DAYS_MILLIS })
            .lessonsFor("navigation")
            .single()

        assertEquals(RuntimeLessonMode.QUERY_ONLY, lesson.mode)
        assertTrue(RuntimeMemoryRetriever().retrieve("navigate home", listOf(lesson)).matches.isEmpty())
    }

    @Test
    fun legacyLessonRemainsQueryableWhileAccumulatingV2Evidence() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-legacy-evidence-").toFile()
        val sceneDir = File(File(root, "lessons"), encodeFileName("navigation")).apply { mkdirs() }
        File(sceneDir, "lessons.json").writeText(
            """[{"id":"legacy","scene":"navigation","type":"MEMORY","content":"Observed capability set: navi cal route","confidence":0.9,"hitCount":2,"createdAtEpochMillis":1,"lastValidatedAtEpochMillis":1}]""",
        )
        val store = RuntimeMemoryStore(root, nowEpochMillis = { 2L })
        val candidate = memoryCandidate("Observed capability set: navi cal route", confidence = "high")

        store.promote(candidate.copy(runId = "new-run-1"), "navigation", RuntimeLessonMode.PREWARM_ELIGIBLE)
        assertEquals(RuntimeLessonMode.QUERY_ONLY, store.lessonsFor("navigation").single().mode)
        store.promote(candidate.copy(runId = "new-run-2"), "navigation", RuntimeLessonMode.PREWARM_ELIGIBLE)
        assertEquals(RuntimeLessonMode.QUERY_ONLY, store.lessonsFor("navigation").single().mode)
        store.promote(candidate.copy(runId = "new-run-3"), "navigation", RuntimeLessonMode.PREWARM_ELIGIBLE)

        val promoted = store.lessonsFor("navigation").single()
        assertEquals(RuntimeLessonMode.PREWARM_ELIGIBLE, promoted.mode)
        assertEquals(3, promoted.evidenceRunKeys.size)
    }

    @Test
    fun duplicateRunEvidenceDoesNotPromoteLesson() = runBlocking {
        val store = RuntimeMemoryStore(Files.createTempDirectory("runtime-memory-distinct-").toFile())
        val candidate = memoryCandidate("Observed capability set: ac set temperature", confidence = "high")

        repeat(3) { store.promote(candidate.copy(candidateId = "candidate-$it"), scene = "cabin") }

        assertTrue(store.lessonsFor("cabin").isEmpty())
    }

    @Test
    fun concurrentDistinctRunEvidenceIsRetainedAndPromoted() = runBlocking {
        val memory = VfsMemorySource()
        val store = RuntimeMemoryStore(memoryVfs(yieldingMemorySource(memory)))
        val candidate = memoryCandidate("Observed capability set: ac set temperature", confidence = "high")
        val start = CompletableDeferred<Unit>()
        val promotions = (1..3).map { index ->
            async {
                start.await()
                store.promote(
                    candidate.copy(candidateId = "candidate-$index", runId = "run-$index"),
                    scene = "cabin",
                    mode = RuntimeLessonMode.PREWARM_ELIGIBLE,
                )
            }
        }

        start.complete(Unit)
        promotions.awaitAll()

        val lesson = store.lessonsFor("cabin").single()
        assertEquals(3, lesson.hitCount)
        assertEquals((1..3).mapTo(linkedSetOf()) { testEvidenceKey("run-$it") }, lesson.evidenceRunKeys)
        assertEquals(RuntimeLessonMode.PREWARM_ELIGIBLE, lesson.mode)
    }

    @Test
    fun duplicateRunEvidenceDoesNotRefreshActiveLessonValidationTime() = runBlocking {
        var now = 100L
        val store = RuntimeMemoryStore(
            Files.createTempDirectory("runtime-memory-active-replay-").toFile(),
            nowEpochMillis = { now },
        )
        val candidate = memoryCandidate("Observed capability set: ac set temperature", confidence = "high")
        repeat(3) { index ->
            store.promote(
                candidate.copy(candidateId = "candidate-$index", runId = "run-$index"),
                scene = "cabin",
            )
        }
        val initiallyValidatedAt = store.lessonsFor("cabin").single().lastValidatedAtEpochMillis

        now = 200L
        store.promote(candidate.copy(candidateId = "candidate-replay", runId = "run-2"), scene = "cabin")

        val replayed = store.lessonsFor("cabin").single()
        assertEquals(initiallyValidatedAt, replayed.lastValidatedAtEpochMillis)
        assertEquals(3, replayed.hitCount)
        assertEquals(3, replayed.evidenceRunKeys.size)
    }

    @Test
    fun sameRunReplayCannotRaiseActiveLessonConfidence() = runBlocking {
        val store = RuntimeMemoryStore(Files.createTempDirectory("runtime-memory-replay-confidence-").toFile())
        val candidate = memoryCandidate("Observed capability set: ac set temperature")
        repeat(3) { index ->
            store.promote(candidate.copy(runId = "run-$index"), scene = "cabin")
        }
        val beforeReplay = store.lessonsFor("cabin").single().confidence

        store.promote(
            candidate.copy(runId = "run-2", confidence = "high"),
            scene = "cabin",
            mode = RuntimeLessonMode.PREWARM_ELIGIBLE,
        )

        val replayed = store.lessonsFor("cabin").single()
        assertEquals(beforeReplay, replayed.confidence, 0f)
        assertEquals(RuntimeLessonMode.QUERY_ONLY, replayed.mode)
    }

    @Test
    fun repeatedPromoteDoesNotApplySameDecayWindowTwice() = runBlocking {
        var now = 0L
        val store = RuntimeMemoryStore(
            Files.createTempDirectory("runtime-memory-decay-checkpoint-").toFile(),
            nowEpochMillis = { now },
        )
        val candidate = memoryCandidate("Observed capability set: ac set temperature", confidence = "high")
        repeat(3) { index -> store.promote(candidate.copy(runId = "run-$index"), "cabin") }
        now = EIGHT_DAYS_MILLIS

        store.promote(candidate.copy(runId = "run-2"), "cabin")
        val afterFirstReplay = store.lessonsFor("cabin").single()
        store.promote(candidate.copy(runId = "run-2"), "cabin")
        val afterSecondReplay = store.lessonsFor("cabin").single()

        assertEquals(afterFirstReplay.confidence, afterSecondReplay.confidence, 0f)
        assertEquals(afterFirstReplay.lastValidatedAtEpochMillis, afterSecondReplay.lastValidatedAtEpochMillis)
    }

    @Test
    fun evidenceRetentionPreservesMostRecentRunsAcrossReload() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-evidence-order-").toFile()
        var store = RuntimeMemoryStore(root)
        val candidate = memoryCandidate("Observed capability set: ac set temperature", confidence = "high")
        repeat(9) { index -> store.promote(candidate.copy(runId = "run-$index"), "cabin") }
        assertFalse(testEvidenceKey("run-0") in store.lessonsFor("cabin").single().evidenceRunKeys)

        store = RuntimeMemoryStore(root)
        store.promote(candidate.copy(runId = "run-9"), "cabin")
        val retained = store.lessonsFor("cabin").single().evidenceRunKeys

        assertFalse(testEvidenceKey("run-1") in retained)
        assertTrue((2..9).all { testEvidenceKey("run-$it") in retained })
    }

    @Test
    fun explicitPrewarmModePersistsAfterPromotion() = runBlocking {
        val root = Files.createTempDirectory("runtime-memory-prewarm-").toFile()
        val store = RuntimeMemoryStore(root)
        val candidate = memoryCandidate("Observed capability set: ac set temperature", confidence = "high")
        repeat(3) { index ->
            store.promote(
                candidate.copy(candidateId = "candidate-$index", runId = "run-$index"),
                scene = "cabin",
                mode = RuntimeLessonMode.PREWARM_ELIGIBLE,
            )
        }

        val lesson = store.lessonsFor("cabin").single()
        assertEquals(RuntimeLessonMode.PREWARM_ELIGIBLE, lesson.mode)
        assertEquals(3, lesson.evidenceRunKeys.size)
        val stored = File(File(File(root, "lessons"), encodeFileName("cabin")), "lessons.json").readText()
        assertTrue(stored.contains("\"schemaVersion\":$RUNTIME_MEMORY_SCHEMA_VERSION"))
        assertTrue(stored.contains("\"mode\":\"PREWARM_ELIGIBLE\""))
        assertTrue(stored.contains("\"evidenceRunKeys\":"))
    }

    private fun memoryCandidate(
        summary: String,
        confidence: String = "low",
    ): RuntimeCandidate = RuntimeCandidate(
        candidateId = "candidate-1",
        runId = "run-1",
        type = RuntimeCandidateType.MEMORY,
        summary = summary,
        confidence = confidence,
    )

    private fun yieldingMemorySource(memory: VfsMemorySource): VfsSource = object : VfsSource by memory {
        override suspend fun read(relativePath: String, offset: Long, maxBytes: Int): VfsReadResult {
            delay(CONCURRENT_STORE_IO_DELAY_MILLIS)
            return memory.read(relativePath, offset, maxBytes)
        }

        override suspend fun write(
            relativePath: String,
            content: String,
            mode: VfsWriteMode,
            expectedRevision: String?,
        ): VfsWriteResult {
            delay(CONCURRENT_STORE_IO_DELAY_MILLIS)
            return memory.write(relativePath, content, mode, expectedRevision)
        }
    }

    private fun memoryVfs(memory: VfsSource): VfsFileSystem =
        VfsFileSystem.fromJson(
            jsonText = """
                {
                  "schemaVersion": 1,
                  "vfsContractVersion": "1.0.0",
                  "workspaceRoot": "/",
                  "mounts": [
                    {"id":"memory","source":"memory://runtime","target":"/memory","mode":"readWrite","priority":10}
                  ]
                }
            """.trimIndent(),
            sourceResolver = VfsSourceResolver { source ->
                if (source == "memory://runtime") memory else null
            },
        )

    private companion object {
        const val CONCURRENT_STORE_IO_DELAY_MILLIS = 5L
        const val EIGHT_DAYS_MILLIS = 8L * 24L * 60L * 60L * 1000L
        const val TWENTY_TWO_DAYS_MILLIS = 22L * 24L * 60L * 60L * 1000L
    }
}

private fun testEvidenceKey(runId: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(runId.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)
