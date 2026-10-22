package cc.noharry.agent.runtime.probe

/**
 * Observe-only probe session for one run (SPEC-RPB-001/002).
 * Emit helpers cover the seven mandatory P0 stage families.
 * Never throws into the control path; failures bump error counters only.
 */
internal class ProbeSession(
    val runId: String,
    val sessionId: String?,
    val requestId: String?,
    private val bus: ProbeBus,
    private val factory: ProbeEventFactory,
    val packs: List<ProbePack>,
    /** Case pack id bound for this run ("none" when unbound). Used by production case.bound emit. */
    val casePackId: String = "none",
) {
    fun emit(stageId: String, probeId: String, attrs: Map<String, Any?> = emptyMap()): Boolean =
        try {
            val redacted = ProbeValueRedactor.redactAttrs(attrs)
            val known = factory.isKnownStage(stageId)
            val accepted = bus.accept(factory.next(stageId = stageId, probeId = probeId, attrs = redacted))
            // Unknown stage: still fail-open, but leave probe.error evidence (SPEC-RPB-001).
            if (!known && stageId != "probe.error" && packs.isNotEmpty()) {
                emitProbeError(
                    code = "UNKNOWN_STAGE",
                    message = "unknown stageId",
                    stageId = stageId,
                )
            }
            accepted
        } catch (_: Exception) {
            factory.recordError()
            false
        }

    // --- Family: run ---
    fun emitRunStart(attrs: Map<String, Any?> = emptyMap()): Boolean =
        emit("run.start", ProbeProducerIds.LIFECYCLE_RUN, attrs)

    fun emitRunFinish(attrs: Map<String, Any?> = emptyMap()): Boolean =
        emit("run.finish", ProbeProducerIds.LIFECYCLE_RUN, attrs)

    // --- Family: request/session config ---
    fun emitRequestConfig(
        controlFingerprint: String? = null,
        configFingerprint: String? = null,
    ): Boolean =
        emit(
            "request.config",
            ProbeProducerIds.REQUEST_CONFIG,
            buildMap {
                controlFingerprint?.let { put("controlFingerprint", it) }
                configFingerprint?.let { put("configFingerprint", it) }
            },
        )

    fun emitSessionIsolate(session: String, memory: String, cache: String): Boolean =
        emit(
            "session.isolate",
            ProbeProducerIds.REQUEST_CONFIG,
            mapOf("session" to session, "memory" to memory, "cache" to cache),
        )

    // --- Family: model ---
    fun emitModelRoundStart(roundIndex: Int): Boolean =
        emit("model.round.start", ProbeProducerIds.MODEL_ROUND, mapOf("roundIndex" to roundIndex))

    fun emitModelRoundEnd(roundIndex: Int, status: String): Boolean =
        emit(
            "model.round.end",
            ProbeProducerIds.MODEL_ROUND,
            mapOf("roundIndex" to roundIndex, "status" to status),
        )

    fun emitModelUsage(inputTokens: Int, outputTokens: Int): Boolean =
        emit(
            "model.usage",
            ProbeProducerIds.MODEL_ROUND,
            mapOf("inputTokens" to inputTokens, "outputTokens" to outputTokens),
        )

    // --- Family: tool/batch ---
    fun emitToolPrepare(
        toolName: String,
        callId: String,
        source: String? = null,
        roundIndex: Int? = null,
    ): Boolean =
        emit(
            "tool.prepare",
            ProbeProducerIds.TOOL_EXEC,
            buildMap {
                put("toolName", toolName)
                put("callId", callId)
                source?.let { put("source", it) }
                roundIndex?.let { put("roundIndex", it) }
            },
        )

    fun emitToolAuthorize(
        toolName: String,
        callId: String,
        decision: String,
        source: String? = null,
        roundIndex: Int? = null,
    ): Boolean =
        emit(
            "tool.authorize",
            ProbeProducerIds.TOOL_EXEC,
            buildMap {
                put("toolName", toolName)
                put("callId", callId)
                put("decision", decision)
                source?.let { put("source", it) }
                roundIndex?.let { put("roundIndex", it) }
            },
        )

    fun emitToolExecuteStart(
        toolName: String,
        callId: String,
        source: String? = null,
        roundIndex: Int? = null,
        args: Map<String, Any?>? = null,
        argsDigest: String? = null,
    ): Boolean =
        emit(
            "tool.execute.start",
            ProbeProducerIds.TOOL_EXEC,
            buildMap {
                put("toolName", toolName)
                put("callId", callId)
                source?.let { put("source", it) }
                roundIndex?.let { put("roundIndex", it) }
                args?.let { put("args", it) }
                argsDigest?.let { put("argsDigest", it) }
            },
        )

    fun emitToolExecuteEnd(
        toolName: String,
        callId: String,
        ok: Boolean,
        code: String? = null,
        source: String? = null,
        roundIndex: Int? = null,
    ): Boolean =
        emit(
            "tool.execute.end",
            ProbeProducerIds.TOOL_EXEC,
            buildMap {
                put("toolName", toolName)
                put("callId", callId)
                put("ok", ok)
                code?.let { put("code", it) }
                source?.let { put("source", it) }
                roundIndex?.let { put("roundIndex", it) }
            },
        )

    fun emitToolBatchPlan(width: Int, roundIndex: Int? = null): Boolean =
        emit(
            "tool.batch.plan",
            ProbeProducerIds.TOOL_EXEC,
            buildMap {
                put("width", width)
                roundIndex?.let { put("roundIndex", it) }
            },
        )

    // --- Family: search/network ---
    fun emitSearchHosted(path: String): Boolean =
        emit("search.hosted", ProbeProducerIds.SEARCH_NETWORK, mapOf("path" to path))

    fun emitNetworkFetch(host: String): Boolean =
        emit("network.fetch", ProbeProducerIds.SEARCH_NETWORK, mapOf("host" to host))

    // --- Family: terminal/empty ---
    fun emitEmptyCompletion(reason: String): Boolean =
        emit("empty_completion", ProbeProducerIds.TERMINAL, mapOf("reason" to reason))

    fun emitRunTerminal(status: String, incompleteReason: String? = null): Boolean =
        emit(
            "run.terminal",
            ProbeProducerIds.TERMINAL,
            if (incompleteReason != null) {
                mapOf("status" to status, "incompleteReason" to incompleteReason)
            } else {
                mapOf("status" to status)
            },
        )

    // --- Family: case/probe completeness ---
    fun emitCaseBound(casePackId: String): Boolean =
        emit("case.bound", ProbeProducerIds.CASE_COMPLETENESS, mapOf("casePackId" to casePackId))

    fun emitProbeError(code: String, message: String, stageId: String? = null): Boolean =
        emit(
            "probe.error",
            ProbeProducerIds.CASE_COMPLETENESS,
            buildMap {
                put("code", code)
                put("message", message)
                stageId?.let { put("stageId", it) }
            },
        )

    fun emitProbeCompleteness(complete: Boolean, enqueued: Long, written: Long): Boolean =
        emit(
            "probe.completeness",
            ProbeProducerIds.CASE_COMPLETENESS,
            mapOf(
                "complete" to complete,
                "enqueued" to enqueued,
                "written" to written,
            ),
        )

    /**
     * Synthetic harness: emit one event from each mandatory family (for stage counter tests).
     * Does not replace real lifecycle wiring.
     */
    fun emitAllMandatoryFamiliesSynthetic(): Map<ProbeStageFamily, Boolean> {
        val results = linkedMapOf<ProbeStageFamily, Boolean>()
        results[ProbeStageFamily.RUN] = emitRunStart()
        results[ProbeStageFamily.REQUEST_SESSION_CONFIG] =
            emitRequestConfig(controlFingerprint = "c0", configFingerprint = "f0") &&
                emitSessionIsolate("fresh", "cleared", "cold")
        results[ProbeStageFamily.MODEL] =
            emitModelRoundStart(0) && emitModelRoundEnd(0, "ok") && emitModelUsage(1, 2)
        results[ProbeStageFamily.TOOL_BATCH] =
            emitToolPrepare("mcp__x__t", "c1") &&
                emitToolAuthorize("mcp__x__t", "c1", "allow") &&
                emitToolExecuteStart("mcp__x__t", "c1") &&
                emitToolExecuteEnd("mcp__x__t", "c1", ok = true) &&
                emitToolBatchPlan(1)
        results[ProbeStageFamily.SEARCH_NETWORK] =
            emitSearchHosted("hosted") && emitNetworkFetch("example.com")
        results[ProbeStageFamily.TERMINAL_EMPTY] =
            emitEmptyCompletion("none") && emitRunTerminal("COMPLETED")
        results[ProbeStageFamily.CASE_PROBE_COMPLETENESS] =
            emitCaseBound("none") &&
                emitProbeError("TEST", "synthetic") &&
                emitProbeCompleteness(complete = false, enqueued = 0, written = 0)
        return results
    }

    /**
     * Terminal path: run.finish → primary drain → probe.error? + probe.completeness
     * (real enqueued/written from primary drain) → secondary drain → side-car writeFinal.
     * Completeness side-car remains the analyzer truth source (detail-design B2).
     */
    suspend fun finish(drainTimeoutMillis: Long = DEFAULT_PROBE_DRAIN_TIMEOUT_MILLIS): ProbeCompleteness {
        emitRunFinish()
        return bus.onRunFinished(drainTimeoutMillis) { primary ->
            emitCaseFamilyAfterPrimaryDrain(primary)
        }
    }

    /**
     * Production emit for case/probe-completeness family after primary drain.
     * Fields come from real completeness counters — not terminal summary backfill.
     */
    private fun emitCaseFamilyAfterPrimaryDrain(primary: ProbeCompleteness) {
        if (packs.isEmpty()) return
        // Redundant event stream only when sink is still healthy (design §361-362).
        // Side-car remains the analyzer truth even if these events never land.
        if (primary.sinkIoFailed) return
        if (primary.drainTimedOut || primary.error > 0L || primary.dropped > 0L) {
            val code = when {
                primary.drainTimedOut -> "DRAIN_TIMEOUT"
                primary.dropped > 0L -> "DROPPED"
                else -> "PROBE_ERROR"
            }
            emitProbeError(
                code = code,
                message = "probe path incomplete",
                stageId = null,
            )
        }
        emitProbeCompleteness(
            complete = primary.complete,
            enqueued = primary.enqueued,
            written = primary.written,
        )
    }

    companion object {
        fun create(
            runId: String,
            sessionId: String? = null,
            requestId: String? = null,
            packs: List<ProbePack> = emptyList(),
            eventSink: ProbeEventSink = InMemoryProbeEventSink(),
            completenessStore: CompletenessStore = InMemoryCompletenessStore(),
            clock: ProbeClock = SystemProbeClock(),
            catalog: ProbeStageCatalog = ProbeStageCatalog.p0Minimal(),
            casePackId: String = "none",
        ): ProbeSession {
            val factory = ProbeEventFactory(
                runId = runId,
                sessionId = sessionId,
                requestId = requestId,
                clock = clock,
                catalog = catalog,
            )
            val bus = DefaultProbeBus.fromPacks(
                runId = runId,
                packs = packs,
                eventSink = eventSink,
                completenessStore = completenessStore,
                factoryCounters = factory,
            )
            return ProbeSession(
                runId = runId,
                sessionId = sessionId,
                requestId = requestId,
                bus = bus,
                factory = factory,
                packs = packs,
                casePackId = casePackId,
            )
        }
    }
}
