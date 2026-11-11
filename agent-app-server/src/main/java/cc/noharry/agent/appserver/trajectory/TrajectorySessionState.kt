@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.appserver.trajectory
import cc.noharry.agent.appserver.protocol.TrajectoryKind
import cc.noharry.agent.appserver.protocol.TrajectoryRecord
import cc.noharry.agent.appserver.protocol.TrajectoryRecordId
import cc.noharry.agent.appserver.protocol.TrajectoryRunGroup
import cc.noharry.agent.appserver.protocol.TrajectorySnapshot
import cc.noharry.agent.spi.SessionEvent
import cc.noharry.agent.spi.SessionEventType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job

/**
 * Mutable Trajectory state for one session: run groups, the in-process accepted
 * EventLog tail, load generation and the tracked load jobs.
 *
 * Owned by [DefaultTrajectoryRepository]; snapshots handed to Compose are immutable.
 */
internal class TrajectorySessionState(
    private val sessionId: String,
) {
    private val groups = LinkedHashMap<String, TrajectoryRunGroup>()
    private val pendingRecords = LinkedHashMap<String, LinkedHashMap<TrajectoryRecordId, TrajectoryRecord>>()
    private val accepted = ConcurrentHashMap<String, LinkedHashMap<TrajectoryRecordId, TrajectoryRecord>>()
    private val jobs = mutableListOf<Job>()
    private val attempts = mutableMapOf<String, TrajectoryAttempt>()
    private val retiredAttempts = mutableMapOf<String, MutableSet<String>>()

    /** Each record's event in full, rendered only when an inspector asks ([rawText]), by event id. */
    private val raw = ConcurrentHashMap<String, Lazy<String>>()
    private val generationCounter = AtomicLong(0)

    @Volatile var refreshing: Boolean = false
    @Volatile var visibleRunCount: Int = TRAJECTORY_INITIAL_VISIBLE_RUNS
    private val loadTokens = ConcurrentHashMap<String, Long>()
    private val latestLoadTokens = ConcurrentHashMap<String, Long>()
    private val loadTokenCounter = AtomicLong(0)
    private val refreshToken = AtomicLong(0)

    val generation: Long get() = generationCounter.get()

    /** Keeps [events] for [rawText]. */
    fun keepRaw(events: Iterable<SessionEvent>) = events.forEach { event ->
        raw[event.eventId] = lazy(LazyThreadSafetyMode.PUBLICATION) { event.renderRawText() }
    }

    fun rawText(eventId: String): String? = raw[eventId]?.value

    /** Marks [runId] as loading and returns the token that owns the marker. */
    fun beginLoad(runId: String): Long {
        val token = loadTokenCounter.incrementAndGet()
        loadTokens[runId] = token
        latestLoadTokens[runId] = token
        return token
    }

    /** Survives [endLoad] so a finished older load cannot overwrite a newer result. */
    fun isLatestLoad(runId: String, token: Long): Boolean = latestLoadTokens[runId] == token

    fun beginRefresh(): Long {
        refreshing = true
        return refreshToken.incrementAndGet()
    }

    fun endRefresh(token: Long) {
        if (refreshToken.get() == token) refreshing = false
    }

    /**
     * Releases the marker only while [token] still owns it. Concurrent loads of the same run
     * used to share one set entry, so a stale load finishing late removed the marker the newer
     * load depended on and the UI stopped showing loading while a read was still in flight.
     */
    fun endLoad(runId: String, token: Long) {
        loadTokens.remove(runId, token)
    }

    fun loadingRunIds(): Set<String> = loadTokens.keys.toSet()

    fun nextGeneration(): Long = generationCounter.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = generationCounter.get() == generation

    /**
     * Immutable copy taken under the bucket lock. Returning the internal map let reconcile and
     * load iterate it while a writer thread mutated it inside `synchronized(bucket)`.
     */
    fun acceptedRecords(runId: String): Map<TrajectoryRecordId, TrajectoryRecord> {
        val bucket = accepted[runId] ?: return emptyMap()
        return synchronized(bucket) { LinkedHashMap(bucket) }
    }

    private fun putAccepted(runId: String, record: TrajectoryRecord) {
        val bucket = accepted.getOrPut(runId) { LinkedHashMap() }
        synchronized(bucket) {
            bucket[record.id] = record
            while (bucket.size > TRAJECTORY_MAX_LIVE_RECORDS_PER_RUN) {
                bucket.remove(bucket.keys.first())
            }
        }
    }

    fun ensureAcceptedGroup(event: SessionEvent, record: TrajectoryRecord): Boolean = synchronized(groups) {
        val turnId = event.logicalTurnId
        val attempt = TrajectoryAttempt(event.runId, event.recordedAtEpochMillis)
        if (!adoptAttempt(turnId, attempt, event.type == SessionEventType.RUN_STARTED)) return@synchronized false
        putAccepted(turnId, record)
        if (turnId !in groups) {
            // A newly accepted run belongs before the cold catalog, even when its
            // first page is full. Preserve the catalog's existing order/tie breaks.
            val previous = LinkedHashMap(groups)
            groups.clear()
            groups[turnId] = TrajectoryRunGroup(
                sessionId = event.sessionId, runId = turnId, requestId = turnId,
                startedAtEpochMillis = event.recordedAtEpochMillis,
            )
            groups.putAll(previous)
        }
        pendingRecords.getOrPut(turnId) { LinkedHashMap() }[record.id] = record
        true
    }

    /** Under groups' monitor: a new physical attempt replaces one logical turn's old projection. */
    private fun adoptAttempt(turnId: String, attempt: TrajectoryAttempt, mayStart: Boolean): Boolean {
        val current = attempts[turnId]
        if (current?.id == attempt.id) return true
        if (attempt.id in retiredAttempts[turnId].orEmpty()) return false
        if (current != null) {
            if (!mayStart || attempt.startedAt < current.startedAt) return false
            retiredAttempts.getOrPut(turnId) { mutableSetOf() } += current.id
            accepted.remove(turnId)
            pendingRecords.remove(turnId)
            groups.remove(turnId)
            // An old asynchronous read may finish after this start; it must not restore the previous attempt.
            latestLoadTokens[turnId] = loadTokenCounter.incrementAndGet()
        }
        attempts[turnId] = attempt
        return true
    }

    /** Called under groups' monitor at snapshot/load boundaries, never for each chunk. */
    private fun materializePending(runId: String) {
        val pending = pendingRecords.remove(runId) ?: return
        val base = groups[runId] ?: return
        val records = mergeAcceptedIntoRecords(base.records, pending.values)
        val lifecycle = canonicalRunLifecycle(records)
        groups[runId] = base.copy(
            isActive = records.none { it.kind == TrajectoryKind.RUN_END },
            records = records,
            recordsLoaded = true,
            lifecycleStatus = lifecycle.lifecycleStatus,
            startedAtEpochMillis = lifecycle.startedAtEpochMillis ?: base.startedAtEpochMillis,
            completedAtEpochMillis = lifecycle.completedAtEpochMillis,
            completionOutcome = lifecycle.completionOutcome ?: base.completionOutcome,
        )
    }

    fun putGroup(group: TrajectoryRunGroup, expectedLoadToken: Long? = null) {
        synchronized(groups) {
            if (expectedLoadToken != null && !isLatestLoad(group.runId, expectedLoadToken)) return
            val attempt = group.records.attempt()
            if (attempt != null && !adoptAttempt(group.runId, attempt, mayStart = true)) return
            groups[group.runId] = group
            materializePending(group.runId)
        }
    }

    fun group(runId: String): TrajectoryRunGroup? = synchronized(groups) {
        materializePending(runId)
        groups[runId]
    }

    fun isActive(runId: String): Boolean = group(runId)?.isActive == true

    fun requestIdFor(runId: String): String? = group(runId)?.requestId

    /** Rebuilds the catalog while keeping records already loaded for the same run. */
    fun applyCatalog(catalog: List<TrajectoryRunGroup>) {
        synchronized(groups) {
            pendingRecords.keys.toList().forEach(::materializePending)
            val previous = LinkedHashMap(groups)
            groups.clear()
            catalog.forEach { group ->
                val existing = previous[group.runId]
                groups[group.runId] = if (existing?.recordsLoaded == true) {
                    val lifecycle = canonicalRunLifecycle(existing.records)
                    group.copy(
                        records = existing.records,
                        recordsLoaded = true,
                        health = existing.health,
                        lifecycleStatus = lifecycle.lifecycleStatus,
                        startedAtEpochMillis = lifecycle.startedAtEpochMillis ?: existing.startedAtEpochMillis,
                        completedAtEpochMillis = lifecycle.completedAtEpochMillis,
                        completionOutcome = lifecycle.completionOutcome ?: existing.completionOutcome,
                    )
                } else {
                    group
                }
            }
        }
    }

    fun track(job: Job) {
        synchronized(jobs) { jobs += job }
    }

    /**
     * Bumps the generation so late IO is discarded, then cancels and joins the load jobs.
     * A generation check alone cannot guarantee a reader stopped touching the files.
     */
    suspend fun cancelAndJoinLoads() {
        nextGeneration()
        val pending = synchronized(jobs) { jobs.toList().also { jobs.clear() } }
        pending.forEach { it.cancel() }
        pending.forEach { it.join() }
    }

    fun toSnapshot(revision: Long): TrajectorySnapshot {
        val all = synchronized(groups) {
            pendingRecords.keys.toList().forEach(::materializePending)
            groups.values.toList()
        }
        val visible = all.take(visibleRunCount)
        return TrajectorySnapshot(
            sessionId = sessionId,
            runGroups = visible,
            visibleRunCount = visible.size,
            totalKnownRunCount = all.size,
            hasOlderRuns = all.size > visible.size,
            loadingRunIds = loadingRunIds(),
            refreshing = refreshing,
            recordAliases = emptyMap(),
            revision = revision,
        )
    }

    private fun mergeAcceptedIntoRecords(
        existing: List<TrajectoryRecord>,
        acceptedRecords: Collection<TrajectoryRecord>,
    ): List<TrajectoryRecord> {
        val byId = LinkedHashMap<TrajectoryRecordId, TrajectoryRecord>()
        existing.forEach { byId[it.id] = it }
        acceptedRecords.forEach { byId[it.id] = it }
        return byId.values.sortedWith(
            compareBy<TrajectoryRecord> { it.sequence ?: Long.MAX_VALUE }.thenBy { it.id.value },
        )
    }
}
