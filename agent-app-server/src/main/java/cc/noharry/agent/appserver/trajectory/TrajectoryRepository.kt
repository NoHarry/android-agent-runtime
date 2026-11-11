@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.evidence.RunEvidence
import cc.noharry.agent.appserver.protocol.SessionTurn
import cc.noharry.agent.appserver.protocol.TrajectoryHealth
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRichAvailability
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.appserver.runtime.ActiveRunSnapshot
import cc.noharry.agent.appserver.runtime.RuntimeRunScope
import cc.noharry.agent.AgentRuntime
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventLogHealth
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Read-only Trajectory state for the Debug console.
 *
 * The repository owns the run catalog, cold loads through [TrajectoryEventLogGateway], and the
 * accepted-event live tail from the same SessionEventLog v2. It never writes a new history store
 * and never starts a run.
 */
internal interface TrajectoryRepository {
    fun snapshot(sessionId: String): TrajectorySnapshot

    suspend fun refreshSession(sessionId: String)

    suspend fun loadRun(sessionId: String, runId: String)

    fun loadOlderRuns(sessionId: String)

    fun attachWriter(runtime: AgentRuntime, sessionId: String): AutoCloseable

    fun observeAccepted(event: SessionEvent)

    suspend fun reconcile(runtime: AgentRuntime, scope: RuntimeRunScope)

    suspend fun dropSession(sessionId: String)

    suspend fun clear()
}

internal class DefaultTrajectoryRepository(
    private val gateway: TrajectoryEventLogGateway,
    private val readTurns: (String) -> List<SessionTurn>,
    @Suppress("UnusedPrivateProperty")
    private val readRunEvidence: (String) -> RunEvidence? = { null },
    private val activeRunSnapshots: () -> List<ActiveRunSnapshot> = { emptyList() },
    private val ioScope: CoroutineScope,
    private val onChanged: (String) -> Unit = {},
) : TrajectoryRepository {

    private val sessions = ConcurrentHashMap<String, TrajectorySessionState>()
    private val subscriptions = ConcurrentHashMap<String, AutoCloseable>()
    private val revision = AtomicLong(0)

    override fun snapshot(sessionId: String): TrajectorySnapshot =
        sessions[sessionId]?.toSnapshot(revision.get()) ?: TrajectorySnapshot(sessionId = sessionId)

    override suspend fun refreshSession(sessionId: String) {
        val state = state(sessionId)
        val generation = state.nextGeneration()
        val refreshToken = state.beginRefresh()
        publish(sessionId)
        try {
            val catalog = buildCatalog(sessionId)
            if (!state.isCurrent(generation)) return
            state.applyCatalog(catalog)
            publish(sessionId)
            catalog.firstOrNull { it.isActive }?.runId
                ?.let { loadRun(sessionId, it) }
                ?: catalog.firstOrNull()?.let { loadRun(sessionId, it.runId) }
        } finally {
            state.endRefresh(refreshToken)
            publish(sessionId)
        }
    }

    override suspend fun loadRun(sessionId: String, runId: String) {
        val state = state(sessionId)
        val generation = state.generation
        val loadToken = state.beginLoad(runId)
        publish(sessionId)
        val loaded = try {
            loadRunGroup(sessionId, runId, state)
        } finally {
            // Always released — a stale or throwing load must not leave "loading…" behind — but
            // only while this load still owns the marker: a newer load of the same run holds a
            // fresher token, and clearing it here would hide that its read is still in flight.
            state.endLoad(runId, loadToken)
        }
        // Session switch, delete and clear all bump the generation, so a late IO result
        // observed after any of them is dropped instead of resurrecting stale rows.
        if (!state.isCurrent(generation) || !state.isLatestLoad(runId, loadToken)) {
            publish(sessionId)
            return
        }
        if (loaded != null) state.putGroup(loaded, loadToken)
        publish(sessionId)
    }

    /** The event behind a record of [sessionId], in full: an inspector asks for it when it shows the record. */
    fun rawText(sessionId: String, eventId: String): String? = sessions[sessionId]?.rawText(eventId)

    override fun loadOlderRuns(sessionId: String) {
        val state = state(sessionId)
        state.visibleRunCount += TRAJECTORY_OLDER_RUN_PAGE
        publish(sessionId)
    }

    override fun attachWriter(runtime: AgentRuntime, sessionId: String): AutoCloseable {
        subscriptions.remove(sessionId)?.close()
        val handle = runtime.diagnostics.subscribeSessionEvents(sessionId, ::observeAccepted)
        subscriptions[sessionId] = handle
        return AutoCloseable {
            if (subscriptions.remove(sessionId, handle)) {
                handle.close()
            }
        }
    }

    override fun observeAccepted(event: SessionEvent) {
        val state = state(event.sessionId)
        state.keepRaw(listOf(event))
        val record = event.toTrajectoryRecord()
        if (state.ensureAcceptedGroup(event, record)) publish(event.sessionId)
    }

    override suspend fun reconcile(runtime: AgentRuntime, scope: RuntimeRunScope) {
        val sessionId = scope.sessionId
        val runId = scope.requestId
        val state = state(sessionId)
        val loadToken = state.beginLoad(runId)
        val generation = state.generation
        try {
            // Read from the still-open writer; failure degrades only this attempt's trajectory.
            val health = runCatching { runtime.diagnostics.getSessionEventLogHealth(sessionId, runId) }.getOrNull()
            val replay = runCatching { runtime.diagnostics.replaySessionEvents(sessionId, runId) }
            if (!state.isCurrent(generation) || !state.isLatestLoad(runId, loadToken)) return
            val turn = readTurns(sessionId).lastOrNull { it.requestId == runId }
            if (replay.isFailure) {
                // A failed replay must not render as a healthy empty trajectory.
                state.putGroup(loadFailureGroup(sessionId, runId, turn, state, TrajectoryEventLogReason.REPLAY_FAILED), loadToken)
                return
            }
            val events = replay.getOrDefault(emptyList())
            state.keepRaw(events)
            val projection = projectCanonicalRecords(sessionId, runId, events)
            state.putGroup(
                buildGroup(
                    sessionId = sessionId,
                    runId = runId,
                    requestId = scope.requestId,
                    projection = projection,
                    health = health,
                    active = false,
                    safeReasonCode = if (health == null) TrajectoryEventLogReason.HEALTH_FAILED else null,
                ),
                loadToken,
            )
        } finally {
            state.endLoad(runId, loadToken)
            publish(sessionId)
        }
    }

    override suspend fun dropSession(sessionId: String) {
        subscriptions.remove(sessionId)?.close()
        val state = sessions.remove(sessionId)
        state?.cancelAndJoinLoads()
        gateway.deleteSessionRoot(sessionId)
        publish(sessionId)
    }

    override suspend fun clear() {
        subscriptions.values.forEach { runCatching { it.close() } }
        subscriptions.clear()
        val states = sessions.values.toList()
        sessions.clear()
        states.forEach { it.cancelAndJoinLoads() }
        gateway.deleteAllRoots()
    }

    /** Test seam: the accepted tail keyed by run, without exposing mutable repository state. */
    fun liveRecordsForTest(sessionId: String, runId: String): List<TrajectoryRecord> =
        sessions[sessionId]?.acceptedRecords(runId)?.values?.toList().orEmpty()

    private suspend fun loadRunGroup(
        sessionId: String,
        runId: String,
        state: TrajectorySessionState,
    ): TrajectoryRunGroup? {
        val turns = readTurns(sessionId).filter { it.requestId == runId }
        val turn = turns.lastOrNull()
        val events = runCatching { gateway.replay(sessionId, runId) }
        val failure = events.exceptionOrNull() as? TrajectoryEventLogFailure
        if (failure != null) {
            // A failed cold read must read as failed. Silently keeping the catalog row made a
            // broken reader look like a run that had simply not been expanded yet.
            return loadFailureGroup(sessionId, runId, turn, state, failure.reasonCode)
        }
        val persisted = events.getOrDefault(emptyList())
        state.keepRaw(persisted)
        val health = runCatching { gateway.health(sessionId, runId) }.getOrNull()
        val projection = projectCanonicalRecords(sessionId, runId, persisted)
        return buildGroup(
            sessionId = sessionId,
            runId = runId,
            requestId = turn?.requestId ?: state.requestIdFor(runId),
            projection = projection,
            health = health,
            active = state.isActive(runId),
            safeReasonCode = if (health == null) TrajectoryEventLogReason.HEALTH_FAILED else null,
        )
    }

    private fun buildGroup(
        sessionId: String,
        runId: String,
        requestId: String?,
        projection: CanonicalProjection,
        health: SessionEventLogHealth?,
        active: Boolean,
        safeReasonCode: String?,
    ): TrajectoryRunGroup {
        val records = buildList {
            addAll(projection.records)
            if (projection.hasSequenceGap) {
                add(gapBoundaryRecord(sessionId, runId, projection.records.mapNotNull { it.sequence }.maxOrNull()))
            }
        }
        val richAvailability = records
            .map { it.richAvailability }
            .reduceOrNull(::betterAvailability)
            ?: TrajectoryRichAvailability.UNAVAILABLE
        val lifecycle = canonicalRunLifecycle(records)
        return TrajectoryRunGroup(
            sessionId = sessionId,
            runId = runId,
            requestId = requestId,
            startedAtEpochMillis = lifecycle.startedAtEpochMillis,
            completedAtEpochMillis = lifecycle.completedAtEpochMillis,
            lifecycleStatus = lifecycle.lifecycleStatus,
            completionOutcome = lifecycle.completionOutcome,
            isActive = active,
            recordsLoaded = true,
            records = records,
            health = composeTrajectoryHealth(
                runtimeHealth = health,
                projection = projection,
                richAvailability = richAvailability,
                lastAcceptedSequence = records.mapNotNull { it.sequence }.maxOrNull(),
                safeReasonCode = safeReasonCode,
            ),
        )
    }

    /** Surfaces the failure as its own row and marks the group retryable rather than unloaded. */
    private fun loadFailureGroup(
        sessionId: String,
        runId: String,
        turn: SessionTurn?,
        state: TrajectorySessionState,
        reasonCode: String,
    ): TrajectoryRunGroup {
        val existing = state.group(runId)
        return (existing ?: TrajectoryRunGroup(sessionId = sessionId, runId = runId)).copy(
            requestId = existing?.requestId ?: turn?.requestId,
            startedAtEpochMillis = existing?.startedAtEpochMillis ?: turn?.startedAtEpochMillis,
            completedAtEpochMillis = existing?.completedAtEpochMillis ?: turn?.completedAtEpochMillis,
            lifecycleStatus = existing?.lifecycleStatus ?: turn?.finalStatus ?: TRAJECTORY_STATUS_UNKNOWN,
            recordsLoaded = true,
            loadFailed = true,
            records = listOf(loadErrorRecord(sessionId, runId, reasonCode)),
            health = (existing?.health ?: TrajectoryHealth()).copy(safeReasonCode = reasonCode),
        )
    }

    /**
     * Run catalog for a session: persisted turns plus in-process runs. EventLog has no
     * cross-run cursor, so orphan runs that crashed before writing a turn may be missing.
     */
    private fun buildCatalog(sessionId: String): List<TrajectoryRunGroup> {
        val fromTurns = readTurns(sessionId)
            .groupBy { it.requestId }
            .map { (runId, turns) ->
                val turn = turns.last()
                TrajectoryRunGroup(
                    sessionId = sessionId,
                    runId = runId,
                    requestId = turn.requestId,
                    startedAtEpochMillis = turn.startedAtEpochMillis,
                    completedAtEpochMillis = turn.completedAtEpochMillis,
                    lifecycleStatus = turn.finalStatus,
                    completionOutcome = turn.completionOutcome,
                )
            }
        val fromRuns = activeRunSnapshots()
            .filter { it.sessionId == sessionId }
            .map { snapshot ->
                TrajectoryRunGroup(
                    sessionId = sessionId,
                    runId = snapshot.requestId,
                    requestId = snapshot.requestId,
                    startedAtEpochMillis = snapshot.startedAtEpochMillis,
                    lifecycleStatus = snapshot.lifecycleStatus,
                    completionOutcome = snapshot.completionOutcome,
                    isActive = snapshot.isActive,
                )
            }
        val byRunId = LinkedHashMap<String, TrajectoryRunGroup>()
        fromTurns.forEach { byRunId[it.runId] = it }
        fromRuns.forEach { group ->
            byRunId[group.runId] = byRunId[group.runId]?.let { group.copy(records = it.records) } ?: group
        }
        return byRunId.values.sortedWith(
            compareByDescending<TrajectoryRunGroup> { it.startedAtEpochMillis ?: 0L }.thenBy { it.runId },
        )
    }

    private fun state(sessionId: String): TrajectorySessionState =
        sessions.getOrPut(sessionId) { TrajectorySessionState(sessionId) }

    private fun publish(sessionId: String) {
        revision.incrementAndGet()
        onChanged(sessionId)
    }

    /** Fire-and-forget refresh used by attach/select/new session so the UI is never blocked. */
    fun refreshSessionAsync(sessionId: String) {
        val state = state(sessionId)
        val job = ioScope.launch { refreshSession(sessionId) }
        state.track(job)
    }

    fun loadRunAsync(sessionId: String, runId: String) {
        val state = state(sessionId)
        val job = ioScope.launch { loadRun(sessionId, runId) }
        state.track(job)
    }
}
