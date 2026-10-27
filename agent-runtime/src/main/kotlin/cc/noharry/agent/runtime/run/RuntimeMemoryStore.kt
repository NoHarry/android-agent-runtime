package cc.noharry.agent.runtime.run
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidate
import cc.noharry.agent.kernel.api.reliability.RuntimeCandidateType
import cc.noharry.agent.kernel.core.memory.RuntimeLesson
import cc.noharry.agent.kernel.core.memory.RuntimeLessonMode
import cc.noharry.agent.kernel.core.memory.RuntimeMemoryPort
import cc.noharry.agent.kernel.core.memory.mergeRuntimeMemoryContent
import cc.noharry.agent.kernel.core.memory.runtimeMemoryLessonsMatch
import cc.noharry.agent.kernel.core.state.decodeOrNull
import cc.noharry.agent.kernel.core.state.encodeFileName
import cc.noharry.agent.kernel.core.state.writeReliabilityText
import cc.noharry.agent.vfs.VFS_ENTRY_DIR
import cc.noharry.agent.vfs.VfsFileSystem
import cc.noharry.agent.vfs.VfsListResult
import cc.noharry.agent.vfs.VfsReadResult
import cc.noharry.agent.vfs.VfsWriteMode
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Stores cross-run runtime lessons per scene in the runtime workspace.
 *
 * Candidate promotion follows the harness memory governance model (mem-005:
 * Dream candidate is not durable promotion): a single low-confidence candidate
 * does not become an active lesson until its confidence crosses [CONFIDENCE_GATE]
 * via accumulated evidence.
 *
 * Lessons are organized by scene and capped at [MAX_LESSONS_PER_SCENE] per scene;
 * the lowest-confidence lesson is evicted when the cap is exceeded.
 */
private data class PendingRuntimeLesson(
    val id: String,
    val scene: String?,
    val type: RuntimeCandidateType,
    val content: String,
    val confidence: Float,
    val hitCount: Int,
    val createdAtEpochMillis: Long,
    val lastValidatedAtEpochMillis: Long,
    val lastDecayedAtEpochMillis: Long,
    val mode: RuntimeLessonMode,
    val evidenceRunKeys: Set<String>,
)

internal class RuntimeMemoryStore private constructor(
    private val memoryRoot: File?,
    private val workspace: VfsFileSystem?,
    private val nowEpochMillis: () -> Long = { System.currentTimeMillis() },
) : RuntimeMemoryPort {
    constructor(memoryRoot: File, nowEpochMillis: () -> Long = { System.currentTimeMillis() }) :
        this(memoryRoot, null, nowEpochMillis)

    constructor(workspace: VfsFileSystem, nowEpochMillis: () -> Long = { System.currentTimeMillis() }) :
        this(null, workspace, nowEpochMillis)

    private val promoteMutex = Mutex()
    private val lessonsDir = memoryRoot?.let { File(it, LESSONS_DIR_NAME).apply { mkdirs() } }

    suspend fun promote(
        candidate: RuntimeCandidate,
        scene: String?,
        mode: RuntimeLessonMode = RuntimeLessonMode.QUERY_ONLY,
    ) = promoteMutex.withLock {
        val sceneKey = scene ?: GLOBAL_SCENE
        val normalized = normalizeStoredLessons(loadLessons(sceneKey))
        val lessons = normalized.active.toMutableList()
        val pending = mergePendingDuplicates(loadPending(sceneKey) + normalized.demoted)
            .toMutableList()
        val existing = lessons.find { runtimeMemoryLessonsMatch(it.content, candidate.summary) }
        if (existing != null) {
            val refreshed = refreshActiveLesson(lessons, existing, candidate, mode)
            pending.removeAll { runtimeMemoryLessonsMatch(it.content, refreshed.content) }
        } else {
            promotePendingCandidate(candidate, scene, sceneKey, lessons, pending, mode)
        }
        val capped = capActiveLessons(mergeActiveDuplicates(lessons))
        val cappedPending = capPendingLessons(mergePendingDuplicates(pending))
        saveLessons(sceneKey, capped)
        savePending(sceneKey, cappedPending)
    }

    private fun refreshActiveLesson(
        lessons: MutableList<RuntimeLesson>,
        existing: RuntimeLesson,
        candidate: RuntimeCandidate,
        mode: RuntimeLessonMode,
    ): RuntimeLesson {
        lessons.remove(existing)
        val evidenceKey = candidate.evidenceRunKey()
        val isNewEvidence = evidenceKey != null && evidenceKey !in existing.evidenceRunKeys
        if (!isNewEvidence) {
            lessons.add(existing)
            return existing
        }
        val evidenceRunKeys = boundedEvidenceRunKeys(existing.evidenceRunKeys, listOfNotNull(evidenceKey))
        val validatedAt = nowEpochMillis()
        val refreshed = existing.copy(
            content = mergeRuntimeMemoryContent(existing.content, candidate.summary),
            confidence = refreshedConfidence(existing.confidence, candidate.confidence),
            hitCount = existing.hitCount + 1,
            lastValidatedAtEpochMillis = validatedAt,
            lastDecayedAtEpochMillis = validatedAt,
            mode = eligibleLessonMode(existing.mode, mode, evidenceRunKeys),
            evidenceRunKeys = evidenceRunKeys,
        )
        lessons.add(refreshed)
        return refreshed
    }

    private fun promotePendingCandidate(
        candidate: RuntimeCandidate,
        scene: String?,
        sceneKey: String,
        lessons: MutableList<RuntimeLesson>,
        pending: MutableList<PendingRuntimeLesson>,
        mode: RuntimeLessonMode,
    ) {
        val existingPending = pending.find { runtimeMemoryLessonsMatch(it.content, candidate.summary) }
        val updated = updatedPendingLesson(candidate, scene, sceneKey, existingPending, mode)
        pending.remove(existingPending)
        if (updated.isReadyForPromotion()) {
            lessons.add(updated.toRuntimeLesson())
        } else {
            pending.add(updated)
        }
    }

    private fun updatedPendingLesson(
        candidate: RuntimeCandidate,
        scene: String?,
        sceneKey: String,
        existing: PendingRuntimeLesson?,
        mode: RuntimeLessonMode,
    ): PendingRuntimeLesson {
        val evidenceKey = candidate.evidenceRunKey()
        if (existing == null) {
            val evidenceRunKeys = evidenceKey?.let(::setOf).orEmpty()
            val createdAt = nowEpochMillis()
            return PendingRuntimeLesson(
                id = lessonId(sceneKey, candidate.summary),
                scene = scene,
                type = candidate.type,
                content = candidate.summary,
                confidence = initialConfidence(candidate.confidence),
                hitCount = if (evidenceKey == null) 0 else 1,
                createdAtEpochMillis = createdAt,
                lastValidatedAtEpochMillis = createdAt,
                lastDecayedAtEpochMillis = createdAt,
                mode = eligibleLessonMode(RuntimeLessonMode.QUERY_ONLY, mode, evidenceRunKeys),
                evidenceRunKeys = evidenceRunKeys,
            )
        }
        val isNewEvidence = evidenceKey != null && evidenceKey !in existing.evidenceRunKeys
        if (!isNewEvidence) return existing
        val evidenceRunKeys = boundedEvidenceRunKeys(existing.evidenceRunKeys, listOfNotNull(evidenceKey))
        val validatedAt = nowEpochMillis()
        return existing.copy(
            content = mergeRuntimeMemoryContent(existing.content, candidate.summary),
            confidence = refreshedConfidence(existing.confidence, candidate.confidence),
            hitCount = existing.hitCount + 1,
            lastValidatedAtEpochMillis = validatedAt,
            lastDecayedAtEpochMillis = validatedAt,
            mode = eligibleLessonMode(existing.mode, mode, evidenceRunKeys),
            evidenceRunKeys = evidenceRunKeys,
        )
    }

    private fun PendingRuntimeLesson.toRuntimeLesson(): RuntimeLesson = RuntimeLesson(
        id = id,
        scene = scene,
        type = type,
        content = content,
        confidence = confidence,
        hitCount = hitCount,
        createdAtEpochMillis = createdAtEpochMillis,
        lastValidatedAtEpochMillis = lastValidatedAtEpochMillis,
        lastDecayedAtEpochMillis = lastDecayedAtEpochMillis,
        mode = mode,
        evidenceRunKeys = evidenceRunKeys,
    )

    override suspend fun lessonsFor(scene: String?): List<RuntimeLesson> {
        val sceneKey = scene ?: GLOBAL_SCENE
        return normalizeStoredLessons(loadLessons(sceneKey)).active
    }

    suspend fun allLessons(): List<RuntimeLesson> {
        val dir = lessonsDir
        if (dir != null) {
            return dir.listFiles().orEmpty()
                .filter { it.isDirectory }
                .flatMap { loadLessonsFromFileDir(it) }
        }
        val activeWorkspace = workspace ?: return emptyList()
        return when (val result = activeWorkspace.list(LESSONS_VFS_ROOT)) {
            is VfsListResult.Entries -> result.entries
                .filter { it.type == VFS_ENTRY_DIR }
                .flatMap { loadLessonsFromEncodedScene(it.name) }
            else -> emptyList()
        }
    }

    private suspend fun loadLessons(sceneKey: String): List<RuntimeLesson> =
        loadStoredJson(sceneKey, LESSONS_FILE)
            ?.let { decodeLessonsOrEmpty(it) }
            .orEmpty()

    private suspend fun loadPending(sceneKey: String): List<PendingRuntimeLesson> =
        loadStoredJson(sceneKey, PENDING_FILE)
            ?.let { decodePendingOrEmpty(it) }
            .orEmpty()

    private suspend fun loadLessonsFromEncodedScene(encodedSceneKey: String): List<RuntimeLesson> =
        loadStoredJsonByEncodedScene(encodedSceneKey, LESSONS_FILE)
            ?.let { decodeLessonsOrEmpty(it) }
            .orEmpty()
            .let { normalizeStoredLessons(it).active }

    private fun loadLessonsFromFileDir(sceneDir: File): List<RuntimeLesson> =
        File(sceneDir, LESSONS_FILE).decodeOrNull(::decodeLessons)
            .orEmpty()
            .let { normalizeStoredLessons(it).active }

    private fun normalizeStoredLessons(lessons: List<RuntimeLesson>): NormalizedRuntimeLessons {
        val merged = mergeActiveDuplicates(lessons.map { it.decayed().failClosedPrewarmMode() })
        return NormalizedRuntimeLessons(
            active = capActiveLessons(merged.map { it.failClosedPrewarmMode() }),
            demoted = emptyList(),
        )
    }

    private fun mergeActiveDuplicates(lessons: List<RuntimeLesson>): List<RuntimeLesson> {
        val merged = mutableListOf<RuntimeLesson>()
        for (lesson in lessons) {
            val index = merged.indexOfFirst { runtimeMemoryLessonsMatch(it.content, lesson.content) }
            if (index >= 0) {
                merged[index] = mergeRuntimeLessons(merged[index], lesson)
            } else {
                merged += lesson
            }
        }
        return merged
    }

    private fun mergePendingDuplicates(pending: List<PendingRuntimeLesson>): List<PendingRuntimeLesson> {
        val merged = mutableListOf<PendingRuntimeLesson>()
        for (lesson in pending) {
            val index = merged.indexOfFirst { runtimeMemoryLessonsMatch(it.content, lesson.content) }
            if (index >= 0) {
                merged[index] = mergePendingLessons(merged[index], lesson)
            } else {
                merged += lesson
            }
        }
        return merged
    }

    private fun mergeRuntimeLessons(first: RuntimeLesson, second: RuntimeLesson): RuntimeLesson =
        first.copy(
            content = mergeRuntimeMemoryContent(first.content, second.content),
            confidence = maxOf(first.confidence, second.confidence),
            hitCount = mergedHitCount(first, second),
            lastValidatedAtEpochMillis = maxOf(first.lastValidatedAtEpochMillis, second.lastValidatedAtEpochMillis),
            lastDecayedAtEpochMillis = maxOf(
                first.lastDecayedAtEpochMillis,
                second.lastDecayedAtEpochMillis,
            ),
            mode = mergeLessonMode(first.mode, second.mode),
            evidenceRunKeys = boundedEvidenceRunKeys(first.evidenceRunKeys, second.evidenceRunKeys),
        )

    private fun mergePendingLessons(first: PendingRuntimeLesson, second: PendingRuntimeLesson): PendingRuntimeLesson =
        first.copy(
            content = mergeRuntimeMemoryContent(first.content, second.content),
            confidence = maxOf(first.confidence, second.confidence),
            hitCount = mergedHitCount(first, second),
            lastValidatedAtEpochMillis = maxOf(first.lastValidatedAtEpochMillis, second.lastValidatedAtEpochMillis),
            lastDecayedAtEpochMillis = maxOf(
                first.lastDecayedAtEpochMillis,
                second.lastDecayedAtEpochMillis,
            ),
            mode = mergeLessonMode(first.mode, second.mode),
            evidenceRunKeys = boundedEvidenceRunKeys(first.evidenceRunKeys, second.evidenceRunKeys),
        )

    private fun capActiveLessons(lessons: List<RuntimeLesson>): List<RuntimeLesson> =
        lessons.sortedWith(runtimeLessonOrder()).take(MAX_LESSONS_PER_SCENE)

    private fun capPendingLessons(pending: List<PendingRuntimeLesson>): List<PendingRuntimeLesson> =
        pending.sortedWith(pendingLessonOrder()).take(MAX_LESSONS_PER_SCENE)

    private fun RuntimeLesson.decayed(): RuntimeLesson {
        val checkpoint = maxOf(lastValidatedAtEpochMillis, lastDecayedAtEpochMillis)
        val elapsed = (nowEpochMillis() - checkpoint).coerceAtLeast(0L)
        val periods = (elapsed / MEMORY_DECAY_INTERVAL_MILLIS).toInt()
        if (periods <= 0) return this
        return copy(
            confidence = (confidence - CONFIDENCE_INCREMENT * periods).coerceAtLeast(0f),
            lastDecayedAtEpochMillis = checkpoint + periods * MEMORY_DECAY_INTERVAL_MILLIS,
        )
    }

    private fun RuntimeLesson.failClosedPrewarmMode(): RuntimeLesson =
        if (mode == RuntimeLessonMode.PREWARM_ELIGIBLE &&
            (confidence < CONFIDENCE_GATE || evidenceRunKeys.size < MIN_PROMOTION_HIT_COUNT)
        ) {
            copy(mode = RuntimeLessonMode.QUERY_ONLY)
        } else {
            this
        }

    private fun PendingRuntimeLesson.isReadyForPromotion(): Boolean =
        confidence >= CONFIDENCE_GATE && evidenceRunKeys.size >= MIN_PROMOTION_HIT_COUNT

    private suspend fun saveLessons(sceneKey: String, lessons: List<RuntimeLesson>) {
        saveStoredJson(sceneKey, LESSONS_FILE, encodeLessons(lessons))
    }

    private suspend fun savePending(sceneKey: String, pending: List<PendingRuntimeLesson>) {
        saveStoredJson(sceneKey, PENDING_FILE, encodePending(pending))
    }

    private suspend fun loadStoredJson(sceneKey: String, name: String): String? =
        loadStoredJsonByEncodedScene(encodeFileName(sceneKey), name)

    private suspend fun loadStoredJsonByEncodedScene(encodedSceneKey: String, name: String): String? {
        val dir = lessonsDir
        if (dir != null) {
            return File(File(dir, encodedSceneKey), name).readTextOrNull()
        }
        val result = workspace?.read(lessonPath(encodedSceneKey, name), 0, MAX_MEMORY_JSON_BYTES) ?: return null
        return (result as? VfsReadResult.Found)?.text
    }

    private suspend fun saveStoredJson(sceneKey: String, name: String, content: String) {
        val dir = lessonsDir
        if (dir != null) {
            val sceneDir = File(dir, encodeFileName(sceneKey)).apply { mkdirs() }
            File(sceneDir, name).writeReliabilityText(content)
            return
        }
        workspace?.write(lessonPath(encodeFileName(sceneKey), name), content, VfsWriteMode.OVERWRITE)
    }

    private fun encodeLessons(lessons: List<RuntimeLesson>): String =
        lessons.joinToString(prefix = "[", postfix = "]", separator = ",") { lesson ->
            """{"schemaVersion":$RUNTIME_MEMORY_SCHEMA_VERSION,"id":"${escape(lesson.id)}","scene":${jsonStringOrNull(lesson.scene)},"type":"${lesson.type.name}","content":"${escape(lesson.content)}","confidence":${lesson.confidence},"hitCount":${lesson.hitCount},"createdAtEpochMillis":${lesson.createdAtEpochMillis},"lastValidatedAtEpochMillis":${lesson.lastValidatedAtEpochMillis},"lastDecayedAtEpochMillis":${lesson.lastDecayedAtEpochMillis},"mode":"${lesson.mode.name}","evidenceRunKeys":${encodeStringSet(lesson.evidenceRunKeys)}}"""
        }

    private fun encodePending(pending: List<PendingRuntimeLesson>): String =
        pending.joinToString(prefix = "[", postfix = "]", separator = ",") { item ->
            """{"schemaVersion":$RUNTIME_MEMORY_SCHEMA_VERSION,"id":"${escape(item.id)}","scene":${jsonStringOrNull(item.scene)},"type":"${item.type.name}","content":"${escape(item.content)}","confidence":${item.confidence},"hitCount":${item.hitCount},"createdAtEpochMillis":${item.createdAtEpochMillis},"lastValidatedAtEpochMillis":${item.lastValidatedAtEpochMillis},"lastDecayedAtEpochMillis":${item.lastDecayedAtEpochMillis},"mode":"${item.mode.name}","evidenceRunKeys":${encodeStringSet(item.evidenceRunKeys)}}"""
        }

    private fun decodeLessons(value: String): List<RuntimeLesson> {
        val jsonArray = kotlinx.serialization.json.Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonArray
        return jsonArray.map { element ->
            val obj = element as kotlinx.serialization.json.JsonObject
            val lastValidatedAt = obj.stringField("lastValidatedAtEpochMillis").toLongOrNull() ?: 0L
            RuntimeLesson(
                id = obj.stringField("id"),
                scene = obj.stringFieldOrNull("scene"),
                type = runCatching { RuntimeCandidateType.valueOf(obj.stringField("type")) }
                    .getOrDefault(RuntimeCandidateType.MEMORY),
                content = obj.stringField("content"),
                confidence = obj.stringField("confidence").toFloatOrNull() ?: 0f,
                hitCount = obj.stringField("hitCount").toIntOrNull() ?: 0,
                createdAtEpochMillis = obj.stringField("createdAtEpochMillis").toLongOrNull() ?: 0L,
                lastValidatedAtEpochMillis = lastValidatedAt,
                lastDecayedAtEpochMillis = obj.stringField("lastDecayedAtEpochMillis")
                    .toLongOrNull() ?: lastValidatedAt,
                mode = obj.decodedLessonMode(),
                evidenceRunKeys = obj.decodedEvidenceRunKeys(),
            )
        }
    }

    private fun decodeLessonsOrEmpty(value: String): List<RuntimeLesson> =
        runCatching { decodeLessons(value) }.getOrDefault(emptyList())

    private fun decodePending(value: String): List<PendingRuntimeLesson> {
        val jsonArray = kotlinx.serialization.json.Json.parseToJsonElement(value) as kotlinx.serialization.json.JsonArray
        return jsonArray.map { element ->
            val obj = element as kotlinx.serialization.json.JsonObject
            val lastValidatedAt = obj.stringField("lastValidatedAtEpochMillis").toLongOrNull() ?: 0L
            PendingRuntimeLesson(
                id = obj.stringField("id"),
                scene = obj.stringFieldOrNull("scene"),
                type = runCatching { RuntimeCandidateType.valueOf(obj.stringField("type")) }
                    .getOrDefault(RuntimeCandidateType.MEMORY),
                content = obj.stringField("content"),
                confidence = obj.stringField("confidence").toFloatOrNull() ?: 0f,
                hitCount = obj.stringField("hitCount").toIntOrNull() ?: 0,
                createdAtEpochMillis = obj.stringField("createdAtEpochMillis").toLongOrNull() ?: 0L,
                lastValidatedAtEpochMillis = lastValidatedAt,
                lastDecayedAtEpochMillis = obj.stringField("lastDecayedAtEpochMillis")
                    .toLongOrNull() ?: lastValidatedAt,
                mode = obj.decodedLessonMode(),
                evidenceRunKeys = obj.decodedEvidenceRunKeys(),
            )
        }
    }

    private fun decodePendingOrEmpty(value: String): List<PendingRuntimeLesson> =
        runCatching { decodePending(value) }.getOrDefault(emptyList())

    private fun kotlinx.serialization.json.JsonObject.stringField(name: String): String =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

    private fun kotlinx.serialization.json.JsonObject.stringFieldOrNull(name: String): String? =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it != "null" }

    private fun kotlinx.serialization.json.JsonObject.stringSet(name: String): Set<String> =
        (this[name] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            ?.distinct()
            ?.takeLast(MAX_EVIDENCE_RUN_KEYS)
            ?.toCollection(linkedSetOf())
            .orEmpty()

    private fun kotlinx.serialization.json.JsonObject.decodedLessonMode(): RuntimeLessonMode =
        if (runtimeMemorySchemaVersion() == RUNTIME_MEMORY_SCHEMA_VERSION) {
            decodeLessonMode(stringFieldOrNull("mode"))
        } else {
            RuntimeLessonMode.QUERY_ONLY
        }

    private fun kotlinx.serialization.json.JsonObject.decodedEvidenceRunKeys(): Set<String> =
        if (runtimeMemorySchemaVersion() == RUNTIME_MEMORY_SCHEMA_VERSION) {
            stringSet("evidenceRunKeys")
        } else {
            emptySet()
        }

    private fun kotlinx.serialization.json.JsonObject.runtimeMemorySchemaVersion(): Int =
        stringField("schemaVersion").toIntOrNull() ?: LEGACY_RUNTIME_MEMORY_SCHEMA_VERSION

    private fun jsonStringOrNull(value: String?): String =
        value?.let { "\"${escape(it)}\"" } ?: "null"

    private fun encodeStringSet(values: Set<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"${escape(it)}\"" }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun lessonId(sceneKey: String, content: String): String =
        "lesson-${sceneKey}-${content.hashCode().and(0xFFFFFF)}"

    private fun RuntimeCandidate.evidenceRunKey(): String? =
        runId.takeIf { it.isNotBlank() }?.let(::memoryEvidenceKey)

    private fun refreshedConfidence(current: Float, candidate: String): Float {
        val base = maxOf(current, initialConfidence(candidate))
        return (base + CONFIDENCE_INCREMENT).coerceAtMost(MAX_CONFIDENCE)
    }

    private fun initialConfidence(wireConfidence: String): Float = when (wireConfidence) {
        "high" -> 0.9f
        "medium" -> 0.6f
        else -> 0.3f
    }
}

private fun mergedHitCount(first: RuntimeLesson, second: RuntimeLesson): Int =
    mergedHitCount(first.hitCount, second.hitCount, first.evidenceRunKeys, second.evidenceRunKeys)

private fun mergedHitCount(first: PendingRuntimeLesson, second: PendingRuntimeLesson): Int =
    mergedHitCount(first.hitCount, second.hitCount, first.evidenceRunKeys, second.evidenceRunKeys)

private fun mergedHitCount(
    firstCount: Int,
    secondCount: Int,
    firstEvidence: Set<String>,
    secondEvidence: Set<String>,
): Int = firstCount + secondCount - firstEvidence.intersect(secondEvidence).size

private fun boundedEvidenceRunKeys(vararg groups: Collection<String>): Set<String> {
    val ordered = linkedSetOf<String>()
    groups.forEach { group -> group.forEach { key -> ordered += key } }
    return ordered.toList().takeLast(MAX_EVIDENCE_RUN_KEYS).toCollection(linkedSetOf())
}

private fun memoryEvidenceKey(runId: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(runId.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(MEMORY_EVIDENCE_KEY_CHARS)
}

private fun eligibleLessonMode(
    current: RuntimeLessonMode,
    requested: RuntimeLessonMode,
    evidenceRunKeys: Set<String>,
): RuntimeLessonMode = if (
    current == RuntimeLessonMode.PREWARM_ELIGIBLE ||
    (requested == RuntimeLessonMode.PREWARM_ELIGIBLE && evidenceRunKeys.size >= MIN_PROMOTION_HIT_COUNT)
) {
    RuntimeLessonMode.PREWARM_ELIGIBLE
} else {
    RuntimeLessonMode.QUERY_ONLY
}

private fun mergeLessonMode(first: RuntimeLessonMode, second: RuntimeLessonMode): RuntimeLessonMode =
    if (first == RuntimeLessonMode.PREWARM_ELIGIBLE && second == RuntimeLessonMode.PREWARM_ELIGIBLE) {
        RuntimeLessonMode.PREWARM_ELIGIBLE
    } else {
        RuntimeLessonMode.QUERY_ONLY
    }

private fun decodeLessonMode(value: String?): RuntimeLessonMode =
    runCatching { RuntimeLessonMode.valueOf(value.orEmpty()) }
        .getOrDefault(RuntimeLessonMode.QUERY_ONLY)

private data class NormalizedRuntimeLessons(
    val active: List<RuntimeLesson>,
    val demoted: List<PendingRuntimeLesson>,
)

internal const val CONFIDENCE_GATE = 0.6f
internal const val MAX_LESSONS_PER_SCENE = 20
internal const val CONFIDENCE_INCREMENT = 0.15f
internal const val MIN_PROMOTION_HIT_COUNT = 3
internal const val RUNTIME_MEMORY_SCHEMA_VERSION = 2
private const val LEGACY_RUNTIME_MEMORY_SCHEMA_VERSION = 1
private fun lessonPath(encodedSceneKey: String, name: String): String =
    "$LESSONS_VFS_ROOT/$encodedSceneKey/$name"

private fun runtimeLessonOrder(): Comparator<RuntimeLesson> =
    compareByDescending<RuntimeLesson> { it.confidence }
        .thenByDescending { it.hitCount }
        .thenByDescending { it.lastValidatedAtEpochMillis }

private fun pendingLessonOrder(): Comparator<PendingRuntimeLesson> =
    compareByDescending<PendingRuntimeLesson> { it.confidence }
        .thenByDescending { it.hitCount }
        .thenByDescending { it.lastValidatedAtEpochMillis }

private fun File.readTextOrNull(): String? =
    if (isFile) readText() else null

private const val LESSONS_VFS_ROOT = "/memory/lessons"
private const val MAX_MEMORY_JSON_BYTES = 256 * 1024
private const val MAX_CONFIDENCE = 1.0f
private const val MAX_EVIDENCE_RUN_KEYS = 8
private const val MEMORY_EVIDENCE_KEY_CHARS = 16
private const val MEMORY_DECAY_INTERVAL_MILLIS = 7L * 24L * 60L * 60L * 1000L
private const val GLOBAL_SCENE = "global"
private const val LESSONS_DIR_NAME = "lessons"
private const val LESSONS_FILE = "lessons.json"
private const val PENDING_FILE = "pending.json"
