package cc.noharry.agent.runtime.workspace
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionSummary
import cc.noharry.agent.kernel.api.reliability.RuntimeResult
import cc.noharry.agent.kernel.api.reliability.RuntimeRunSnapshot
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.context.SkillsCatalog
import cc.noharry.agent.kernel.core.context.TodoState
import cc.noharry.agent.kernel.core.loop.completed
import cc.noharry.agent.kernel.core.memory.RuntimeLesson
import cc.noharry.agent.kernel.core.state.ReliabilityStorage
import cc.noharry.agent.runtime.run.RUNTIME_MEMORY_SCHEMA_VERSION
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Shared JSON builders for context materializer output (SPEC-CDR-002).
 * All output is provider-neutral JSON consumed by the model via read_file.
 */

internal fun contextMemoryIndexJson(scenes: List<String>, totalLessons: Int): String = buildJsonObject {
    putJsonArray("scenes") { scenes.forEach { add(it) } }
    put("totalLessons", totalLessons)
}.toString()

internal fun contextMemorySceneJson(scene: String, lessons: List<RuntimeLesson>): String = buildJsonObject {
    put("schemaVersion", RUNTIME_MEMORY_SCHEMA_VERSION)
    put("scene", scene)
    putJsonArray("lessons") {
        lessons.forEach { lesson ->
            add(buildJsonObject {
                put("id", lesson.id)
                put("content", lesson.content)
                put("confidence", lesson.confidence)
                put("hitCount", lesson.hitCount)
                put("mode", lesson.mode.name)
                put("evidenceCount", lesson.evidenceRunKeys.size)
                put("lastValidatedAtEpochMillis", lesson.lastValidatedAtEpochMillis)
            })
        }
    }
}.toString()

internal fun sessionCurrentJson(
    sessionId: String,
    runs: List<RuntimeRunSnapshot>,
    storage: ReliabilityStorage?,
): String = buildJsonObject {
    put("sessionId", sessionId)
    putJsonArray("previousTurns") {
        runs.mapIndexed { index, run ->
            add(buildJsonObject {
                put("runId", run.runId)
                put("status", run.status.name)
                put("turnIndex", index + 1)
                storage?.readResult(run.runId)?.let { runResult ->
                    put("response", runResult.response.take(MAX_SESSION_RESPONSE_PREVIEW_CHARS))
                    put(
                        "completed",
                        RuntimeCompletionSummary.firstPartyPass(runResult.status, runResult.completion),
                    )
                }
            })
        }
    }
    put("currentTurnIndex", runs.size + 1)
}.toString()

internal fun runsIndexJson(runs: List<RuntimeRunSnapshot>): String = buildJsonObject {
    putJsonArray("runs") {
        runs.forEach { run ->
            add(buildJsonObject {
                put("runId", run.runId)
                put("status", run.status.name)
                put("updatedAtEpochMillis", run.updatedAtEpochMillis)
            })
        }
    }
}.toString()

internal fun runSummaryJson(runId: String, result: RuntimeResult): String = buildJsonObject {
    put("runId", runId)
    put("status", result.status.name)
    put("response", result.response)
    result.errorCode?.let { put("errorCode", it) }
}.toString()

internal fun todoJson(state: TodoState): String = buildJsonObject {
    putJsonArray("items") {
        state.items.forEach { item ->
            add(buildJsonObject {
                put("content", item.content)
                put("status", item.status.name)
            })
        }
    }
}.toString()

internal fun skillIndexJson(catalog: SkillsCatalog): String = buildJsonObject {
    putJsonArray("skills") {
        catalog.skills.forEach { entry ->
            add(buildJsonObject {
                put("name", entry.name)
                put("description", entry.description)
            })
        }
    }
}.toString()

private const val MAX_SESSION_RESPONSE_PREVIEW_CHARS = 200
