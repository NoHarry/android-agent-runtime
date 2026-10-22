package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.core.ContextCatalog
import cc.noharry.agent.kernel.core.ContextPathEntry
import cc.noharry.agent.kernel.core.MaterializedEntry
import cc.noharry.agent.kernel.core.MaterializerCacheMetadata
import cc.noharry.agent.kernel.core.MaterializerContext
import cc.noharry.agent.kernel.core.MaterializerListResult
import cc.noharry.agent.kernel.core.MaterializerReadResult
import cc.noharry.agent.kernel.core.ReliabilityStorage
import cc.noharry.agent.kernel.core.SkillsCatalog
import cc.noharry.agent.kernel.core.RuntimeLesson
import cc.noharry.agent.kernel.core.TodoState
/**
 * Routes virtual filesystem reads/lists for context-driven paths (AGENTS.md,
 * context, skills) to the appropriate read-only aggregation source.
 *
 * All operations are read-only: the model cannot write context, memory, session,
 * runs, or skill files directly. The only model-writable path is `context/todo.json`
 * via the `update_todo` tool (handled by [TodoStore], not this router).
 *
 * Routing priority in [VirtualFileMaterializer] is: MCP first (unchanged),
 * then context/skills paths handled here.
 */
internal class ContextMaterializerRouter(
    private val storage: ReliabilityStorage?,
    private val memoryStore: RuntimeMemoryStore?,
    private val todoStore: TodoStore?,
    private val skillRegistry: SkillRegistry?,
    private val agentsMdContent: String?,
) {
    fun hasAgentsIndex(): Boolean = agentsMdContent != null

    fun readAgentsIndex(): MaterializerReadResult =
        agentsMdContent?.let {
            MaterializerReadResult.Found(
                content = it,
                cacheHit = false,
                cacheMetadata = MaterializerCacheMetadata.shared(contentVersion("agents", it)),
            )
        }
            ?: MaterializerReadResult.NotFound

    suspend fun cacheMetadata(path: String, context: MaterializerContext): MaterializerCacheMetadata = when {
        path == AGENTS_INDEX -> agentsMdContent
            ?.let { MaterializerCacheMetadata.shared(contentVersion("agents", it)) }
            ?: MaterializerCacheMetadata.NotCacheable
        path.matches(MEMORY_INDEX_PATH) -> memoryIndexCacheMetadata()
        path.matches(MEMORY_SCENE_PATH) -> memorySceneCacheMetadata(path)
        path.matches(SESSION_CURRENT_PATH) -> sessionCurrentCacheMetadata(context)
        path.matches(RUNS_INDEX_PATH) -> runsIndexCacheMetadata(context)
        path.matches(RUN_SUMMARY_PATH) -> runSummaryCacheMetadata(path, context)
        path == CONTEXT_TODO -> todoCacheMetadata(context)
        path.matches(SKILL_INDEX_PATH) -> skillIndexCacheMetadata(context)
        path.matches(SKILL_MD_PATH) -> skillMdCacheMetadata(path, context)
        else -> MaterializerCacheMetadata.NotCacheable
    }

    suspend fun read(path: String, context: MaterializerContext): MaterializerReadResult = when {
        path.matches(MEMORY_INDEX_PATH) -> readMemoryIndex()
        path.matches(MEMORY_SCENE_PATH) -> readMemoryScene(path)
        path.matches(SESSION_CURRENT_PATH) -> readSessionCurrent(context)
        path.matches(RUNS_INDEX_PATH) -> readRunsIndex(context)
        path.matches(RUN_SUMMARY_PATH) -> readRunSummary(path, context)
        path == CONTEXT_TODO -> readTodo(context)
        path.matches(SKILL_INDEX_PATH) -> readSkillIndex(context)
        path.matches(SKILL_MD_PATH) -> readSkillMd(path, context)
        else -> MaterializerReadResult.NotFound
    }

    suspend fun list(path: String, context: MaterializerContext): MaterializerListResult = when {
        path == AGENTS_ROOT -> listAgentsRoot()
        path == CONTEXT_ROOT -> listContextRoot()
        path == CONTEXT_MEMORY -> listMemoryScenes()
        path.matches(Regex("""^/context/memory/[^/]+$""")) -> listMemorySceneDir()
        path == CONTEXT_SESSION -> listSessionDir()
        path == CONTEXT_RUNS -> listRunsDir(context)
        path == AGENTS_SKILLS_ROOT -> listSkillsRoot(context)
        path.matches(SKILL_DIR_PATH) -> listSkillDir()
        else -> MaterializerListResult.NotFound
    }

    private suspend fun readMemoryIndex(): MaterializerReadResult {
        val lessons = memoryStore?.allLessons().orEmpty()
        val scenes = lessons.map { it.contextSceneName() }.distinct()
        return found(
            content = contextMemoryIndexJson(scenes, lessons.size),
            cacheMetadata = MaterializerCacheMetadata.session(memoryIndexVersion(lessons)),
        )
    }

    private suspend fun readMemoryScene(path: String): MaterializerReadResult {
        val scene = pathSegments(path)[2]
        val lessons = memoryStore?.lessonsFor(scene.runtimeMemoryScene()).orEmpty()
        return found(
            content = contextMemorySceneJson(scene, lessons),
            cacheMetadata = MaterializerCacheMetadata.session(memorySceneVersion(scene, lessons)),
        )
    }

    private suspend fun readSessionCurrent(context: MaterializerContext): MaterializerReadResult {
        val runs = storage?.recentRunsForSession(context.sessionId, limit = 3).orEmpty()
        return found(
            content = sessionCurrentJson(context.sessionId, runs, storage),
            cacheMetadata = MaterializerCacheMetadata.session(sessionRunsVersion("session-current", runs, storage)),
        )
    }

    private fun readRunsIndex(context: MaterializerContext): MaterializerReadResult {
        val runs = storage?.recentRunsForSession(context.sessionId, limit = 10).orEmpty()
        return found(
            content = runsIndexJson(runs),
            cacheMetadata = MaterializerCacheMetadata.session(sessionRunsVersion("runs-index", runs, storage)),
        )
    }

    private fun readRunSummary(path: String, context: MaterializerContext): MaterializerReadResult {
        val runId = pathSegments(path)[2]
        val snapshot = storage?.readSnapshot(runId)
            ?: return MaterializerReadResult.NotFound
        if (snapshot.sessionId != context.sessionId) {
            return MaterializerReadResult.NotFound
        }
        val result = storage.readResult(runId)
            ?: return MaterializerReadResult.NotFound
        return found(
            content = runSummaryJson(runId, result),
            cacheMetadata = MaterializerCacheMetadata.session(runSummaryVersion(runId, result)),
        )
    }

    private suspend fun readTodo(context: MaterializerContext): MaterializerReadResult {
        val state = todoStore?.load(context.sessionId)
            ?: return MaterializerReadResult.Found(
                content = """{"items":[]}""",
                cacheHit = false,
                cacheMetadata = MaterializerCacheMetadata.session(todoVersion(null)),
            )
        return found(
            content = todoJson(state),
            cacheMetadata = MaterializerCacheMetadata.session(todoVersion(state)),
        )
    }

    private suspend fun readSkillIndex(context: MaterializerContext): MaterializerReadResult {
        val catalog = skillRegistry?.catalog(context.scene) ?: SkillsCatalog(emptyList())
        return found(
            content = skillIndexJson(catalog),
            cacheMetadata = MaterializerCacheMetadata.shared(skillCatalogVersion(context.scene, catalog)),
        )
    }

    private suspend fun readSkillMd(path: String, context: MaterializerContext): MaterializerReadResult {
        val skillName = skillNameFromPath(path)
        val content = skillRegistry?.skillContent(skillName, context.scene)
            ?: return MaterializerReadResult.NotFound
        return MaterializerReadResult.Found(
            content = content,
            cacheHit = false,
            cacheMetadata = MaterializerCacheMetadata.shared(skillContentVersion(skillName, context.scene, content)),
        )
    }

    private fun listContextRoot(): MaterializerListResult.Entries = MaterializerListResult.Entries(
        listOf(
            MaterializedEntry("memory", "dir"),
            MaterializedEntry("session", "dir"),
            MaterializedEntry("runs", "dir"),
            MaterializedEntry("todo.json", "file"),
        ).sortedBy { it.name },
    )

    private fun listAgentsRoot(): MaterializerListResult.Entries =
        MaterializerListResult.Entries(listOf(MaterializedEntry("skills", "dir")))

    private suspend fun listMemoryScenes(): MaterializerListResult.Entries {
        val scenes = memoryStore?.allLessons().orEmpty().map { it.contextSceneName() }.distinct()
        return MaterializerListResult.Entries(scenes.map { MaterializedEntry(it, "dir") }.sortedBy { it.name })
    }

    private fun listMemorySceneDir(): MaterializerListResult.Entries =
        MaterializerListResult.Entries(listOf(MaterializedEntry("index.json", "file")))

    private fun listSessionDir(): MaterializerListResult.Entries =
        MaterializerListResult.Entries(listOf(MaterializedEntry("current.json", "file")))

    private fun listRunsDir(context: MaterializerContext): MaterializerListResult.Entries {
        val runs = storage?.recentRunsForSession(context.sessionId, limit = 10).orEmpty()
        return MaterializerListResult.Entries(
            listOf(MaterializedEntry("index.json", "file")) +
                runs.map { MaterializedEntry(it.runId, "dir") },
        )
    }

    private suspend fun listSkillsRoot(context: MaterializerContext): MaterializerListResult.Entries {
        val catalog = skillRegistry?.catalog(context.scene) ?: SkillsCatalog(emptyList())
        return MaterializerListResult.Entries(
            listOf(MaterializedEntry("index.json", "file")) +
                catalog.skills.map { entry -> MaterializedEntry(entry.name, "dir") }.sortedBy { it.name },
        )
    }

    private fun listSkillDir(): MaterializerListResult.Entries =
        MaterializerListResult.Entries(listOf(MaterializedEntry("SKILL.md", "file")))

    private suspend fun memoryIndexCacheMetadata(): MaterializerCacheMetadata =
        MaterializerCacheMetadata.session(memoryIndexVersion(memoryStore?.allLessons().orEmpty()))

    private suspend fun memorySceneCacheMetadata(path: String): MaterializerCacheMetadata {
        val scene = pathSegments(path)[2]
        val lessons = memoryStore?.lessonsFor(scene.runtimeMemoryScene()).orEmpty()
        return MaterializerCacheMetadata.session(memorySceneVersion(scene, lessons))
    }

    private fun sessionCurrentCacheMetadata(context: MaterializerContext): MaterializerCacheMetadata {
        val runs = storage?.recentRunsForSession(context.sessionId, limit = 3).orEmpty()
        return MaterializerCacheMetadata.session(sessionRunsVersion("session-current", runs, storage))
    }

    private fun runsIndexCacheMetadata(context: MaterializerContext): MaterializerCacheMetadata {
        val runs = storage?.recentRunsForSession(context.sessionId, limit = 10).orEmpty()
        return MaterializerCacheMetadata.session(sessionRunsVersion("runs-index", runs, storage))
    }

    private fun runSummaryCacheMetadata(path: String, context: MaterializerContext): MaterializerCacheMetadata {
        val runId = pathSegments(path)[2]
        val snapshot = storage?.readSnapshot(runId) ?: return MaterializerCacheMetadata.NotCacheable
        if (snapshot.sessionId != context.sessionId) return MaterializerCacheMetadata.NotCacheable
        val result = storage.readResult(runId) ?: return MaterializerCacheMetadata.NotCacheable
        return MaterializerCacheMetadata.session(runSummaryVersion(runId, result))
    }

    private suspend fun todoCacheMetadata(context: MaterializerContext): MaterializerCacheMetadata =
        MaterializerCacheMetadata.session(todoVersion(todoStore?.load(context.sessionId)))

    private suspend fun skillIndexCacheMetadata(context: MaterializerContext): MaterializerCacheMetadata {
        val catalog = skillRegistry?.catalog(context.scene) ?: SkillsCatalog(emptyList())
        return MaterializerCacheMetadata.shared(skillCatalogVersion(context.scene, catalog))
    }

    private suspend fun skillMdCacheMetadata(path: String, context: MaterializerContext): MaterializerCacheMetadata {
        val skillName = skillNameFromPath(path)
        val content = skillRegistry?.skillContent(skillName, context.scene)
            ?: return MaterializerCacheMetadata.NotCacheable
        return MaterializerCacheMetadata.shared(skillContentVersion(skillName, context.scene, content))
    }

    private fun found(
        content: String,
        cacheMetadata: MaterializerCacheMetadata,
    ): MaterializerReadResult.Found =
        MaterializerReadResult.Found(content = content, cacheHit = false, cacheMetadata = cacheMetadata)

    private fun pathSegments(path: String): List<String> =
        path.trim('/').split('/').filter { it.isNotBlank() }

    private fun skillNameFromPath(path: String): String {
        val segments = pathSegments(path)
        return segments[2]
    }
}

private fun RuntimeLesson.contextSceneName(): String = scene ?: GLOBAL_MEMORY_SCENE_NAME

private fun String.runtimeMemoryScene(): String? = takeUnless { it == GLOBAL_MEMORY_SCENE_NAME }

private fun memoryIndexVersion(lessons: List<RuntimeLesson>): String =
    contentVersion(
        "memory-index",
        lessons.joinToString("|") {
            "${it.id}:${it.scene}:${it.mode}:${it.hitCount}:${it.evidenceRunKeys.size}:${it.lastValidatedAtEpochMillis}"
        },
    )

private fun memorySceneVersion(scene: String, lessons: List<RuntimeLesson>): String =
    contentVersion(
        "memory-scene:$scene",
        lessons.joinToString("|") {
            "${it.id}:${it.mode}:${it.confidence}:${it.hitCount}:${it.evidenceRunKeys.size}:" +
                "${it.lastValidatedAtEpochMillis}:${it.content.hashCode()}"
        },
    )

private fun sessionRunsVersion(
    prefix: String,
    runs: List<cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot>,
    storage: ReliabilityStorage?,
): String = contentVersion(
    prefix,
    runs.joinToString("|") { run ->
        val result = storage?.readResult(run.runId)
        "${run.runId}:${run.status}:${run.updatedAtEpochMillis}:${result?.status}:${result?.response.hashCode()}:${result?.errorCode}"
    },
)

private fun runSummaryVersion(runId: String, result: cc.noharry.agent.kernel.api.reliability.RuntimeResult): String =
    contentVersion("run-summary:$runId", "${result.status}:${result.response.hashCode()}:${result.errorCode}")

private fun todoVersion(state: TodoState?): String =
    contentVersion(
        "todo",
        state?.let { todo ->
            "${todo.updatedAtEpochMillis}:" +
                todo.items.joinToString("|") { "${it.status}:${it.content.hashCode()}" }
        } ?: "none",
    )

private fun skillCatalogVersion(scene: String?, catalog: SkillsCatalog): String =
    contentVersion(
        "skill-index:${scene.orEmpty()}",
        catalog.skills.joinToString("|") { "${it.name}:${it.description.hashCode()}" },
    )

private fun skillContentVersion(skillName: String, scene: String?, content: String): String =
    contentVersion("skill:$skillName:${scene.orEmpty()}", content)

private fun contentVersion(prefix: String, content: String): String =
    "$prefix:${content.hashCode()}"

private const val GLOBAL_MEMORY_SCENE_NAME = "global"
