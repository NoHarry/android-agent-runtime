package cc.noharry.agent.runtime.probe

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import cc.noharry.agent.kernel.core.RUNS_DIR
import cc.noharry.agent.kernel.core.encodeFileName

internal const val PROBES_JSONL = "probes.jsonl"
internal const val PROBE_COMPLETENESS_JSON = "probe-completeness.json"
internal const val RUN_META_JSON = "run-meta.json"

/** Resolve the reliability run directory that hosts probe artifacts. */
internal fun probeRunDirectory(reliabilityRoot: File, runId: String): File =
    File(File(reliabilityRoot, RUNS_DIR), encodeFileName(runId))

/**
 * Opens file-backed probe sinks under a reliability run directory.
 * Observe-only: failures are swallowed and reported via completeness.
 */
internal fun openFileProbeSession(
    reliabilityRoot: File,
    runId: String,
    sessionId: String?,
    requestId: String?,
    packs: List<ProbePack>,
    capacity: Int = DEFAULT_PROBE_QUEUE_CAPACITY,
    clock: ProbeClock = SystemProbeClock(),
    casePackId: String = "none",
): ProbeSession {
    val dir = probeRunDirectory(reliabilityRoot, runId)
    dir.mkdirs()
    val eventSink = FileProbeEventSink(File(dir, PROBES_JSONL), capacity = capacity)
    val completenessStore = FileCompletenessStore(File(dir, PROBE_COMPLETENESS_JSON))
    return ProbeSession.create(
        runId = runId,
        sessionId = sessionId,
        requestId = requestId,
        packs = packs,
        eventSink = eventSink,
        completenessStore = completenessStore,
        clock = clock,
        casePackId = casePackId,
    )
}

/** Persist run-meta.json next to probes (SPEC-RPB-003). Never throws to control path. */
internal fun writeRunMetaJson(reliabilityRoot: File, meta: RunMeta): Boolean =
    try {
        val dir = probeRunDirectory(reliabilityRoot, meta.runId)
        dir.mkdirs()
        val target = File(dir, RUN_META_JSON)
        val temp = File(dir, "$RUN_META_JSON.tmp-${System.nanoTime()}")
        temp.writeText(encodeRunMetaJson(meta))
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
        true
    } catch (_: Exception) {
        false
    }

/** Delete probe artifacts for a run (clear-data / retention). */
internal fun clearProbeArtifacts(reliabilityRoot: File, runId: String): Boolean =
    try {
        val dir = probeRunDirectory(reliabilityRoot, runId)
        var ok = true
        for (name in listOf(PROBES_JSONL, PROBE_COMPLETENESS_JSON, RUN_META_JSON)) {
            val f = File(dir, name)
            if (f.exists() && !f.delete()) ok = false
        }
        ok
    } catch (_: Exception) {
        false
    }

/** List probe artifact files that still exist for a run. */
internal fun listProbeArtifactFiles(reliabilityRoot: File, runId: String): List<File> {
    val dir = probeRunDirectory(reliabilityRoot, runId)
    return listOf(PROBES_JSONL, PROBE_COMPLETENESS_JSON, RUN_META_JSON)
        .map { File(dir, it) }
        .filter { it.exists() }
}

internal fun encodeRunMetaJson(meta: RunMeta): String =
    buildJsonObject {
        put("schemaVersion", meta.schemaVersion)
        put("runId", meta.runId)
        put("sessionId", meta.sessionId)
        put("armId", meta.armId)
        put("buildId", meta.buildId)
        put("startedAtEpochMillis", meta.startedAtEpochMillis)
        put("provider", meta.provider)
        put("protocol", meta.protocol)
        put("model", meta.model)
        meta.modelRef?.let { put("modelRef", it) }
        meta.profile?.let { put("profile", it) }
        meta.variant?.let { put("variant", it) }
        put("baseUrlHost", meta.baseUrlHost)
        meta.searchMode?.let { put("searchMode", it) }
        meta.fetchMode?.let { put("fetchMode", it) }
        meta.stream?.let { put("stream", it) }
        meta.backend?.let { put("backend", it) }
        put(
            "isolation",
            buildJsonObject {
                put("session", meta.isolation.session)
                put("memory", meta.isolation.memory)
                put("cache", meta.isolation.cache)
            },
        )
        put(
            "caseBinding",
            buildJsonObject {
                put("casePackId", meta.caseBinding.casePackId)
                meta.caseBinding.promptSha256?.let { put("promptSha256", it) }
            },
        )
        put("enabledProbePacks", jsonStringArray(meta.enabledProbePacks))
        put(
            "toolset",
            buildJsonArray {
                meta.toolset.forEach { t ->
                    add(
                        buildJsonObject {
                            put("qualifiedName", t.qualifiedName)
                            put("source", t.source)
                            put("schemaDigest", t.schemaDigest)
                        },
                    )
                }
            },
        )
        put("declaredVariables", jsonStringArray(meta.declaredVariables))
        put("configFingerprint", meta.configFingerprint)
        put("controlFingerprint", meta.controlFingerprint)
        put("toolsetFingerprint", meta.toolsetFingerprint)
        put("comparable", meta.comparable)
        if (meta.incompleteReasons.isNotEmpty()) {
            put("incompleteReasons", jsonStringArray(meta.incompleteReasons))
        }
    }.toString()

private fun jsonStringArray(values: List<String>): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }

/**
 * Observe-only lifecycle attachment for one run.
 * Safe to call when probes are disabled (noop session).
 */
internal class ProbeRunAttachment(
    val session: ProbeSession,
    private val reliabilityRoot: File?,
    private val runMetaBuilder: () -> RunMeta?,
) {
    private val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastCompleteness: ProbeCompleteness? = null

    /** True after first successful [finishAndPersist] (terminal or abort cleanup). */
    fun isFinished(): Boolean = finished.get()

    /**
     * Exactly-once terminal drain. NativeLoopFactory/coordinator and closeAll may both call
     * this; subsequent calls are no-ops so run.terminal/run.finish are not duplicated.
     *
     * [incompleteReason] records an explicit incomplete cause (e.g. observer delivery could not
     * complete under cancellation) in the run.terminal event and run-meta incompleteReasons,
     * without faking complete=true or rewriting the terminal status.
     */
    suspend fun finishAndPersist(
        status: String,
        incompleteReason: String? = null,
    ): ProbeCompleteness {
        if (!finished.compareAndSet(false, true)) {
            return lastCompleteness
                ?: ProbeCompleteness(
                    runId = session.runId,
                    sequenceMax = 0,
                    enqueued = 0,
                    written = 0,
                    dropped = 0,
                    truncated = 0,
                    error = 0,
                    drainTimedOut = false,
                    sinkIoFailed = false,
                    completenessIoFailed = false,
                    complete = true,
                    probesDisabled = session.packs.isEmpty(),
                )
        }
        try {
            session.emitRunTerminal(status, incompleteReason)
        } catch (_: Exception) {
            // observe-only
        }
        val completeness = session.finish()
        lastCompleteness = completeness
        val root = reliabilityRoot
        if (root != null) {
            try {
                runMetaBuilder()?.let { meta ->
                    val finalized =
                        if (incompleteReason != null) {
                            meta.copy(
                                incompleteReasons = meta.incompleteReasons + incompleteReason,
                                comparable = false,
                            )
                        } else {
                            meta
                        }
                    writeRunMetaJson(root, finalized)
                }
            } catch (_: Exception) {
                // observe-only: meta failure must not affect runtime
            }
        }
        return completeness
    }

    companion object {
        fun disabled(runId: String): ProbeRunAttachment =
            ProbeRunAttachment(
                session = ProbeSession.create(runId = runId, packs = emptyList()),
                reliabilityRoot = null,
                runMetaBuilder = { null },
            )

        fun open(
            reliabilityRoot: File?,
            runId: String,
            sessionId: String?,
            requestId: String?,
            packs: List<ProbePack>,
            runMetaBuilder: () -> RunMeta?,
            clock: ProbeClock = SystemProbeClock(),
            /** Bound case pack id for production case.bound emit; defaults to none. */
            casePackId: String = "none",
        ): ProbeRunAttachment {
            if (reliabilityRoot == null || packs.isEmpty()) {
                return disabled(runId)
            }
            return try {
                val session = openFileProbeSession(
                    reliabilityRoot = reliabilityRoot,
                    runId = runId,
                    sessionId = sessionId,
                    requestId = requestId,
                    packs = packs,
                    clock = clock,
                    casePackId = casePackId,
                )
                ProbeRunAttachment(session, reliabilityRoot, runMetaBuilder)
            } catch (_: Exception) {
                disabled(runId)
            }
        }
    }
}
