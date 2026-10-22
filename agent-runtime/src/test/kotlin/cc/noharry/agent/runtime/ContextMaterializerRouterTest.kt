package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunEnvelope
import cc.noharry.agent.kernel.api.reliability.RuntimeRunPhase
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READS
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS
import cc.noharry.agent.kernel.core.RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES
import cc.noharry.agent.kernel.core.RuntimeVfsPerformanceCounters
import cc.noharry.agent.kernel.core.TodoItem
import cc.noharry.agent.kernel.core.TodoState
import cc.noharry.agent.kernel.core.TodoStatus

class ContextMaterializerRouterTest {
    @Test
    fun virtualWorkspaceReadsContextSurfaces() = runBlocking {
        val fixture = contextFixture()
        val context = MaterializerContext(
            sessionId = "session-1",
            runId = "run-2",
            requestId = "request-2",
            scene = "ac_control",
        )

        val root = fixture.workspace.executeJson("list_dir", """{"path":"/"}""", context)
        val agents = fixture.workspace.readMaterialized("/AGENTS.md", context)
        val session = fixture.workspace.readMaterialized("/context/session/current.json", context)
        val todo = fixture.workspace.readMaterialized("/context/todo.json", context)
        val memory = fixture.workspace.readMaterialized("/context/memory/ac_control/index.json", context)
        val runs = fixture.workspace.readMaterialized("/context/runs/run-1/summary.json", context)
        val skillsRoot = fixture.workspace.executeJson("list_dir", """{"path":"/.agents/skills"}""", context)
        val skills = fixture.workspace.readMaterialized("/.agents/skills/ac_multi_step/SKILL.md", context)

        assertTrue(root.contains("AGENTS.md") && root.contains("context") && root.contains(".agents"))
        assertTrue(!root.contains(""""name":"skills""""))
        assertTrue(agents.contains("Root runtime rules"))
        assertTrue(session.contains("set to 22"))
        assertTrue(todo.contains("Tune AC") && todo.contains("in_progress"))
        assertTrue(memory.contains("Prefer precise temperature adjustments"))
        assertTrue(memory.contains("QUERY_ONLY"))
        assertTrue(memory.contains("schemaVersion"))
        assertTrue(runs.contains("set to 22"))
        assertTrue(skillsRoot.contains("ac_multi_step"))
        assertTrue(skills.contains("# AC Multi Step"))
    }

    @Test
    fun metadataDrivenCacheCoversContextSessionAndSkills() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val fixture = contextFixture()
        val context = MaterializerContext(
            sessionId = "session-1",
            runId = "run-2",
            requestId = "request-2",
            scene = "ac_control",
        )
        val paths = listOf(
            "/context/memory/ac_control/index.json" to "Prefer precise temperature adjustments",
            "/context/session/current.json" to "set to 22",
            "/.agents/skills/ac_multi_step/SKILL.md" to "# AC Multi Step",
        )

        paths.forEach { (path, expected) ->
            val first = fixture.workspace.readMaterialized(path, context)
            val second = fixture.workspace.readMaterialized(path, context)

            assertTrue(first, first.contains(expected))
            assertTrue(second, second.contains(expected))
            assertTrue(first, first.contains(""""cache_hit":false"""))
            assertTrue(second, second.contains(""""cache_hit":false"""))
        }

        assertEquals(3L, counter(RUNTIME_COUNTER_MATERIALIZER_READS))
        assertEquals(3L, counter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES))
        assertEquals(3L, counter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS))
        assertEquals(3L, counter(RUNTIME_COUNTER_MATERIALIZER_BYTE_ENCODES))
    }

    @Test
    fun globalLessonsAreIndexedListedAndReadable() = runBlocking {
        val fixture = contextFixture()
        val context = MaterializerContext(
            sessionId = "session-1",
            runId = "run-2",
            requestId = "request-2",
            scene = "ac_control",
        )

        val index = fixture.workspace.readMaterialized("/context/memory/index.json", context)
        val listing = fixture.workspace.executeJson("list_dir", """{"path":"/context/memory"}""", context)
        val global = fixture.workspace.readMaterialized("/context/memory/global/index.json", context)

        assertTrue(index, index.contains("global"))
        assertTrue(listing, listing.contains(""""name":"global"""))
        assertTrue(global, global.contains("global"))
        assertTrue(global, global.contains("Verify assumptions before reusing tools"))
    }

    @Test
    fun sessionMetadataCacheInvalidatesAfterStorageWriteAndStaysSessionScoped() = runBlocking {
        RuntimeVfsPerformanceCounters.reset()
        val fixture = contextFixture()
        val context = MaterializerContext(
            sessionId = "session-1",
            runId = "run-2",
            requestId = "request-2",
            scene = "ac_control",
        )

        val first = fixture.workspace.readMaterialized("/context/session/current.json", context)
        fixture.storage.saveRun(storageSnapshot("run-3", "session-1", 5L))
        fixture.storage.saveResult(RuntimeResult("run-3", RuntimeRunStatus.COMPLETED, "set to 23"))
        val refreshed = fixture.workspace.readMaterialized("/context/session/current.json", context)
        val cached = fixture.workspace.readMaterialized("/context/session/current.json", context)
        val otherSession = fixture.workspace.readMaterialized(
            "/context/session/current.json",
            context.copy(sessionId = "session-other", runId = "run-other-current", requestId = "request-other"),
        )

        assertTrue(first, first.contains("set to 22"))
        assertTrue(refreshed, refreshed.contains("set to 23"))
        assertTrue(cached, cached.contains("set to 23"))
        assertTrue(otherSession, otherSession.contains("private other session"))
        assertTrue(otherSession, !otherSession.contains("set to 23"))
        assertEquals(3L, counter(RUNTIME_COUNTER_MATERIALIZER_READS))
        assertEquals(3L, counter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_MISSES))
        assertEquals(1L, counter(RUNTIME_COUNTER_MATERIALIZER_READ_CACHE_HITS))
    }

    @Test
    fun legacySkillsAndScenePathsAreNotMaterialized() = runBlocking {
        val fixture = contextFixture()
        val context = MaterializerContext(
            sessionId = "session-1",
            runId = "run-2",
            requestId = "request-2",
            scene = "ac_control",
        )

        val legacySkill = fixture.workspace.readMaterialized("/skills/ac_multi_step/SKILL.md", context)
        val legacySkillList = fixture.workspace.executeJson("list_dir", """{"path":"/skills"}""", context)
        val sceneAgents = fixture.workspace.readMaterialized("/context/scenes/ac_control/AGENTS.md", context)
        val sceneList = fixture.workspace.executeJson("list_dir", """{"path":"/context/scenes"}""", context)

        assertTrue(legacySkill.contains(RuntimeErrorCode.NOT_FOUND.wireCode))
        assertTrue(legacySkillList.contains(RuntimeErrorCode.NOT_FOUND.wireCode))
        assertTrue(sceneAgents.contains(RuntimeErrorCode.NOT_FOUND.wireCode))
        assertTrue(sceneList.contains(RuntimeErrorCode.NOT_FOUND.wireCode))
    }

    @Test
    fun skillCatalogFiltersByScene() = runBlocking {
        val registry = SkillRegistry(
            hostSkills = listOf(
                HostSkill("ac_multi_step", "AC skill", scenes = listOf("ac_control"), skillMd = "# AC"),
                HostSkill("nav_route", "Navigation skill", scenes = listOf("navigation"), skillMd = "# Nav"),
                HostSkill("general", "General skill", skillMd = "# General"),
            ),
        )

        val acCatalog = registry.catalog("ac_control").skills.map { it.name }

        assertTrue("ac_multi_step" in acCatalog)
        assertTrue("general" in acCatalog)
        assertTrue("nav_route" !in acCatalog)
    }

    @Test
    fun skillContentReadUsesSceneFilter() = runBlocking {
        val fixture = contextFixture()
        val context = MaterializerContext(
            sessionId = "session-1",
            runId = "run-2",
            requestId = "request-2",
            scene = "ac_control",
        )

        val blocked = fixture.workspace.readMaterialized("/.agents/skills/nav_route/SKILL.md", context)

        assertTrue(blocked.contains(RuntimeErrorCode.NOT_FOUND.wireCode))
    }

    @Test
    fun runSummaryReadIsScopedToCurrentSession() = runBlocking {
        val fixture = contextFixture()
        val context = MaterializerContext(
            sessionId = "session-1",
            runId = "run-2",
            requestId = "request-2",
            scene = "ac_control",
        )

        val blocked = fixture.workspace.readMaterialized("/context/runs/run-other/summary.json", context)

        assertTrue(blocked.contains(RuntimeErrorCode.NOT_FOUND.wireCode))
    }

    private suspend fun contextFixture(): ContextFixture {
        val workspaceRoot = Files.createTempDirectory("context-router-workspace-").toFile()
        val storage = FileBackedReliabilityStorage(Files.createTempDirectory("context-router-storage-").toFile())
        storage.saveRun(storageSnapshot("run-1", "session-1", 1L))
        storage.saveResult(RuntimeResult("run-1", RuntimeRunStatus.COMPLETED, "set to 22"))
        storage.saveRun(storageSnapshot("run-other", "session-other", 2L))
        storage.saveResult(RuntimeResult("run-other", RuntimeRunStatus.COMPLETED, "private other session"))
        val memoryRoot = workspaceRoot.resolve("memory")
        val memoryStore = RuntimeMemoryStore(memoryRoot, nowEpochMillis = { 2L })
        val memoryCandidate = memoryCandidate()
        memoryStore.promote(memoryCandidate, scene = "ac_control")
        memoryStore.promote(
            memoryCandidate.copy(candidateId = "candidate-2", runId = "run-2"),
            scene = "ac_control",
        )
        memoryStore.promote(
            memoryCandidate.copy(candidateId = "candidate-3", runId = "run-3"),
            scene = "ac_control",
        )
        val globalCandidate = memoryCandidate.copy(
            candidateId = "global-candidate-1",
            runId = "global-run-1",
            summary = "Verify assumptions before reusing tools",
        )
        memoryStore.promote(globalCandidate, scene = null)
        memoryStore.promote(
            globalCandidate.copy(candidateId = "global-candidate-2", runId = "global-run-2"),
            scene = null,
        )
        memoryStore.promote(
            globalCandidate.copy(candidateId = "global-candidate-3", runId = "global-run-3"),
            scene = null,
        )
        val todoStore = TodoStore(memoryRoot)
        todoStore.save("session-1", TodoState("session-1", listOf(TodoItem("Tune AC", TodoStatus.in_progress)), 3L))
        val router = ContextMaterializerRouter(
            storage = storage,
            memoryStore = memoryStore,
            todoStore = todoStore,
            skillRegistry = skillRegistry(),
            agentsMdContent = "# Root runtime rules",
        )
        return ContextFixture(
            workspace = VirtualWorkspace(
                root = workspaceRoot,
                materializer = VirtualFileMaterializer(contextRouter = router),
            ),
            storage = storage,
        )
    }

    private fun skillRegistry(): SkillRegistry = SkillRegistry(
        hostSkills = listOf(
            HostSkill(
                name = "ac_multi_step",
                description = "Operate AC in multiple steps.",
                scenes = listOf("ac_control"),
                skillMd = "# AC Multi Step\nUse exact target temperatures.",
            ),
            HostSkill(
                name = "nav_route",
                description = "Navigation route skill.",
                scenes = listOf("navigation"),
                skillMd = "# Navigation\nUse navigation context.",
            ),
        ),
    )

    private fun memoryCandidate(): RuntimeCandidate = RuntimeCandidate(
        candidateId = "candidate-1",
        runId = "run-1",
        type = RuntimeCandidateType.MEMORY,
        summary = "Prefer precise temperature adjustments",
        confidence = "high",
    )

    private fun storageSnapshot(
        runId: String,
        sessionId: String,
        updatedAtEpochMillis: Long,
    ): Pair<RuntimeRunSnapshot, RuntimeRunEnvelope> =
        RuntimeRunSnapshot(
            runId = runId,
            sessionId = sessionId,
            status = RuntimeRunStatus.COMPLETED,
            updatedAtEpochMillis = updatedAtEpochMillis,
        ) to RuntimeRunEnvelope(
            runId = runId,
            requestId = "$runId-request",
            sessionId = sessionId,
            status = RuntimeRunStatus.COMPLETED,
            phase = RuntimeRunPhase.TERMINAL,
            createdAtEpochMillis = updatedAtEpochMillis,
            updatedAtEpochMillis = updatedAtEpochMillis,
        )

    private fun FileBackedReliabilityStorage.saveRun(
        runState: Pair<RuntimeRunSnapshot, RuntimeRunEnvelope>,
    ) {
        saveRun(runState.first, runState.second)
    }

    private suspend fun VirtualWorkspace.readMaterialized(
        path: String,
        context: MaterializerContext,
    ): String = executeJson("read_file", """{"path":"$path"}""", context)

    private data class ContextFixture(
        val workspace: VirtualWorkspace,
        val storage: FileBackedReliabilityStorage,
    )

    private fun counter(name: String): Long =
        RuntimeVfsPerformanceCounters.snapshot().getOrDefault(name, 0L)
}
