@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionAttachmentRef
import cc.noharry.agent.kernel.api.SessionEvent
import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLog
import cc.noharry.agent.kernel.api.SessionEventLogDurability
import cc.noharry.agent.kernel.api.SessionEventLogHealth
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventQuery
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import cc.noharry.agent.kernel.core.encodeFileName

/**
 * Official durable segmented EventLog rooted under a host-supplied app-private directory.
 *
 * Layout:
 * `<root>/session-events/v2/streams/{encodedSession}/{encodedRun}/manifest.json`
 * `<root>/session-events/v2/streams/{encodedSession}/{encodedRun}/segments/000001.jsonl`
 * `<root>/session-events/v2/streams/{encodedSession}/{encodedRun}/blobs/{sha256}`
 * `<root>/session-events/v2/health/{encodedSession}/{encodedRun}.json`
 */
internal class FileBackedSessionEventLog(
    root: File,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val eventIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val persistLine: (File, String) -> Unit = ::appendForcedLine,
    private val persistSidecar: (File, String) -> Unit = ::writeForcedText,
    private val segmentRolloverBytes: Long = SEGMENT_ROLLOVER_BYTES,
    private val blobInlineThresholdBytes: Long = BLOB_INLINE_THRESHOLD_BYTES,
) : SessionEventLog {
    private val canonicalRoot: File = prepareDurableRoot(root)
    private val processLock: Any = processLockFor(canonicalRoot)
    private val eventIdIndex = EventIdIndex()
    private val eventIdList = PersistedEventIdList { containedFile(canonicalRoot, EVENT_ID_LIST_FILE) }
    private val decodedEvents = SessionEventDecodeCache()
    private val liveHealth = linkedMapOf<EventStreamKey, MutableStreamHealth>()
    private val acceptedListeners = SessionEventLogListeners()
    private val asyncCursors = linkedMapOf<EventStreamKey, SessionEventAppendCursor?>()
    private var streamReadCount = 0
    private val attachments = SessionAttachmentStore(canonicalRoot)

    override fun append(event: SessionEventAppend): SessionEvent {
        val stored = withLogLock {
            validateAppendIdentity(event)
            validatePayload(event.payload)
            val key = EventStreamKey(event.sessionId, event.runId)
            val tracker = liveHealth.getOrPut(key) { seedLiveHealth(event.sessionId, event.runId) }
            tracker.noteAttempt()
            try {
                val persisted = persistEvent(event)
                tracker.noteSuccess(persisted.sequence)
                writeSidecarBestEffort(key.sessionId, key.runId, tracker)
                persisted
            } catch (failure: Exception) {
                tracker.noteFailure(safeFailureCode(failure))
                writeSidecarBestEffort(key.sessionId, key.runId, tracker)
                throw failure
            }
        }
        acceptedListeners.notifyAccepted(stored)
        return stored
    }

    override fun subscribe(listener: (SessionEvent) -> Unit): AutoCloseable =
        acceptedListeners.subscribe(listener)

    override fun putAttachment(sessionId: String, ref: SessionAttachmentRef, bytes: ByteArray): Boolean =
        runCatching { attachments.put(sessionId, ref, bytes) }.getOrDefault(false)

    override fun readAttachment(sessionId: String, sha256: String): ByteArray? =
        runCatching { attachments.read(sessionId, sha256) }.getOrNull()

    /** Commits already accepted identities in order, once per stream per batch. */
    internal fun appendAllocatedBatch(events: List<SessionEvent>) = withLogLock {
        events.groupBy { EventStreamKey(it.sessionId, it.runId) }.forEach { (key, batch) ->
            persistAllocatedStream(key, batch)
        }
    }

    internal fun beginAsyncStream(key: EventStreamKey) = withLogLock {
        val marker = File(streamDir(key.sessionId, key.runId), ASYNC_OPEN_FILE)
        // Do not erase evidence of an interrupted writer or take over a live writer's run.
        check(!marker.exists()) { "EventLog stream already has an open async writer" }
        writeForcedText(marker, "open")
        asyncCursors[key] = null
    }

    internal fun endAsyncStream(key: EventStreamKey) = withLogLock {
        val marker = File(streamDir(key.sessionId, key.runId), ASYNC_OPEN_FILE)
        check(!marker.exists() || marker.delete()) { "EventLog open marker could not be cleared" }
        asyncCursors.remove(key)
    }

    private fun persistAllocatedStream(key: EventStreamKey, batch: List<SessionEvent>) {
        val tracker = liveHealth.getOrPut(key) { seedLiveHealth(key.sessionId, key.runId) }
        batch.forEach { tracker.noteAttempt() }
        tracker.noteAccepted(batch.last().sequence)
        try {
            val manifest = manifestFile(key.sessionId, key.runId)
            val cursor = asyncCursors[key]?.takeIf { it.isUnchanged(manifest) }
            val snapshot = cursor?.snapshot ?: readStream(key.sessionId, key.runId)
            check(batch.all { it.sessionId == key.sessionId && it.runId == key.runId }) { "Batch identity mismatch" }
            validatePersistedStream(batch, (cursor?.lastSequence ?: snapshot.events.lastOrNull()?.sequence ?: 0L) + 1)
            rememberEventIds(batch.map { it.eventId })
            if (snapshot.tornTail) truncateTornTail(snapshot.activeSegmentFile)
            val lines = batch.map { encodeDurableLine(it, key.sessionId, key.runId) }
            val prepared = prepareActiveSegment(snapshot)
            val joined = lines.joinToString("\n")
            persistLine(prepared.file, joined)
            val next = writeManifest(key.sessionId, key.runId, prepared.manifest, batch.last(),
                utf8ByteLength(joined) + 1L, batch.size)
            if (asyncCursors.containsKey(key)) {
                asyncCursors[key] = SessionEventAppendCursor(snapshot.copy(
                    manifest = next, events = emptyList(), activeSegmentFile = prepared.file,
                    activeByteLength = prepared.file.length(), tornTail = false,
                ), batch.last().sequence, manifest)
            }
            batch.forEach { tracker.noteSuccess(it.sequence) }
        } catch (failure: Exception) {
            if (asyncCursors.containsKey(key)) asyncCursors[key] = null
            batch.forEach { tracker.noteFailure(safeFailureCode(failure)) }
            throw failure
        } finally {
            writeSidecarBestEffort(key.sessionId, key.runId, tracker)
        }
    }

    override fun query(query: SessionEventQuery): List<SessionEvent> = withLogLock {
        validateQuery(query)
        val requestId = query.requestId
        val afterSequenceExclusive = query.afterSequenceExclusive
        val limit = query.limit
        val candidates = query.runId?.let { readStream(query.sessionId, it).events }
            ?: loadSessionEvents(query.sessionId)
        candidates.asSequence()
            .filter { requestId == null || it.requestId == requestId }
            .filter { query.types.isEmpty() || it.type in query.types }
            .filter { afterSequenceExclusive == null || it.sequence > afterSequenceExclusive }
            .let { matches -> limit?.let(matches::take) ?: matches }
            .toList()
    }

    override fun replay(sessionId: String, runId: String): List<SessionEvent> = withLogLock {
        validateEventIdentity(sessionId, "sessionId")
        validateEventIdentity(runId, "runId")
        readStream(sessionId, runId).events.toList()
    }

    override fun durability(): SessionEventLogDurability = SessionEventLogDurability.DURABLE

    override fun health(sessionId: String, runId: String): SessionEventLogHealth = withLogLock {
        validateEventIdentity(sessionId, "sessionId")
        validateEventIdentity(runId, "runId")
        val live = liveHealth[EventStreamKey(sessionId, runId)]?.snapshot()
        val offline = readOfflineHealth(sessionId, runId)
        mergeLiveAndOfflineHealth(live, offline)
    }

    private fun seedLiveHealth(sessionId: String, runId: String): MutableStreamHealth {
        val tracker = MutableStreamHealth(SessionEventLogDurability.DURABLE)
        val snapshot = runCatching { readStream(sessionId, runId) }.getOrNull()
        val events = snapshot?.events.orEmpty()
        tracker.appendedCount = events.size
        tracker.attemptedCount = events.size
        tracker.lastPersistedSequence = events.lastOrNull()?.sequence
        tracker.lastAcceptedSequence = events.lastOrNull()?.sequence
        if (snapshot?.tornTail == true) {
            tracker.sidecarWriteFailed = true
            if (tracker.firstFailureCode == null) {
                tracker.firstFailureCode = FAILURE_STREAM_TRUNCATED
            }
        }
        val sidecarRead = runCatching {
            val file = healthFile(sessionId, runId)
            if (!file.isFile) return@runCatching null
            decodeHealthSidecar(file.readText(Charsets.UTF_8))
        }
        val sidecar = sidecarRead.getOrNull()
        val health = sidecar?.health
        val identityMatched = sidecar != null &&
            sidecarIdentityMatches(sidecar.sessionId, sidecar.runId, sessionId, runId)
        if (identityMatched && health != null) {
            tracker.attemptedCount = maxOf(tracker.attemptedCount, health.attemptedCount)
            tracker.failedCount = health.failedCount
            tracker.firstFailureCode = boundedSafeFailureCode(health.firstFailureCode)
        }
        val lastReadable = events.lastOrNull()?.sequence
        val trusted = sidecarEvidenceTrusted(
            sidecar = sidecar,
            sessionId = sessionId,
            runId = runId,
            readableCount = events.size,
            lastReadableSequence = lastReadable,
        )
        if (events.isNotEmpty() && !trusted) {
            tracker.sidecarWriteFailed = true
            if (tracker.firstFailureCode == null) {
                tracker.firstFailureCode = FAILURE_SIDECAR_UNTRUSTED
            }
        }
        if (tracker.attemptedCount != tracker.appendedCount + tracker.failedCount) {
            tracker.attemptedCount = tracker.appendedCount + tracker.failedCount
            tracker.sidecarWriteFailed = true
        }
        return tracker
    }

    private fun persistEvent(event: SessionEventAppend): SessionEvent {
        val eventId = eventIdFactory().also { require(it.isNotBlank()) { "eventId factory returned a blank id" } }
        rememberEventId(eventId)
        val snapshot = readStream(event.sessionId, event.runId)
        if (snapshot.tornTail) {
            truncateTornTail(snapshot.activeSegmentFile)
        }
        val sequence = (snapshot.events.lastOrNull()?.sequence ?: 0L) + FIRST_EVENT_SEQUENCE
        validateCausalSources(event.sourceEventSequences, snapshot.events, sequence)
        val stored = event.toStoredEvent(eventId, sequence, nowEpochMillis())
        val encoded = encodeDurableLine(stored, event.sessionId, event.runId)
        val prepared = prepareActiveSegment(snapshot)
        liveHealth[EventStreamKey(event.sessionId, event.runId)]?.noteAccepted(stored.sequence)
        persistLine(prepared.file, encoded)
        writeManifest(
            sessionId = event.sessionId,
            runId = event.runId,
            previous = prepared.manifest,
            stored = stored,
            lineBytes = utf8ByteLength(encoded) + 1L,
        )
        return stored
    }

    private fun rememberEventId(eventId: String) = rememberEventIds(listOf(eventId))

    /**
     * Event ids are unique across the whole log. Each instance keeps them in memory and seeds that set once (again only
     * when another writer moved the generation): from the persisted id list when it covers the current generation, else
     * by decoding every stream, which then rewrites the list. A host that builds a runtime per run therefore no longer
     * decodes the entire history at the start of each run.
     */
    private fun rememberEventIds(eventIds: List<String>) {
        val durableGeneration = readEventIdGeneration()
        if (!eventIdIndex.seeded || eventIdIndex.generation != durableGeneration) {
            eventIdIndex.ids.clear()
            val listed = eventIdList.read(durableGeneration)
            if (listed != null) {
                eventIdIndex.ids += listed
            } else {
                loadAllReadableEvents().forEach { eventIdIndex.ids += it.eventId }
                eventIdIndex.fullScanCount += 1
                eventIdList.rewrite(eventIdIndex.ids, durableGeneration)
            }
            eventIdIndex.seeded = true
            eventIdIndex.generation = durableGeneration
            eventIdIndex.seedCount += 1
        }
        eventIds.forEach { eventId ->
            check(eventIdIndex.ids.add(eventId)) { "eventId factory returned a duplicate id" }
        }
        check(durableGeneration < Long.MAX_VALUE) { "EventLog eventId generation overflow" }
        val nextGeneration = durableGeneration + 1L
        // The list (ending with the generation it covers) before the shared generation: a crash in between leaves the
        // list's marker ahead of the generation, which only makes the next seed rescan; it never misses an id.
        eventIdList.append(eventIds, nextGeneration)
        writeForcedText(eventIdGenerationFile(), nextGeneration.toString())
        eventIdIndex.generation = nextGeneration
    }

    /** Test seam: first persist seeds once; later appends must not rescan every stream. */
    internal fun debugEventIdSeedCount(): Int = eventIdIndex.seedCount

    /** Test seam: how many seeds decoded every stream instead of reading the persisted id list. */
    internal fun debugEventIdFullScanCount(): Int = eventIdIndex.fullScanCount

    internal fun debugStreamReadCount(): Int = streamReadCount

    private fun readEventIdGeneration(): Long {
        val file = eventIdGenerationFile()
        if (!existsNoFollow(file)) return 0L
        check(file.isFile) { "EventLog eventId generation is not a regular file" }
        rejectLink(file.toPath())
        val encoded = file.readText(Charsets.UTF_8)
        val generation = encoded.toLongOrNull()
            ?: throw IllegalStateException("EventLog eventId generation is corrupt")
        check(generation >= 0L) { "EventLog eventId generation is negative" }
        return generation
    }

    private fun eventIdGenerationFile(): File =
        containedFile(canonicalRoot, EVENT_ID_GENERATION_FILE)

    private fun encodeDurableLine(stored: SessionEvent, sessionId: String, runId: String): String {
        val encoded = encodeEvent(stored)
        if (utf8ByteLength(encoded) <= blobInlineThresholdBytes) return encoded
        val bytes = encoded.toByteArray(Charsets.UTF_8)
        val digest = sha256Hex(bytes)
        writeForcedText(blobFile(sessionId, runId, digest), encoded)
        return encodeBlobEnvelope(digest, bytes.size.toLong())
    }

    private fun prepareActiveSegment(snapshot: FileStreamSnapshot): PreparedSegment {
        var manifest = snapshot.manifest
        var active = snapshot.activeSegmentFile
        val length = if (active.exists()) active.length() else 0L
        if (length > 0L && length >= segmentRolloverBytes) {
            manifest = closeActiveSegment(snapshot.sessionId, snapshot.runId, manifest, active)
            active = segmentFile(snapshot.sessionId, snapshot.runId, manifest.activeSegment)
        }
        return PreparedSegment(file = active, manifest = manifest)
    }

    private fun loadAllReadableEvents(): List<SessionEvent> {
        val streamsRoot = containedDirectory(canonicalRoot, SESSION_EVENTS_DIR, SESSION_EVENTS_VERSION_DIR, STREAMS_DIR)
        if (!streamsRoot.isDirectory) return emptyList()
        val events = listContainedDirectories(streamsRoot).flatMap { sessionDir ->
            listContainedDirectories(sessionDir).mapNotNull { runDir ->
                readReadableSiblingSnapshot(sessionDir.name, runDir.name)
            }
        }.flatMap { it.events }
        validateGlobalEventIds(events)
        return events
    }

    private fun readReadableSiblingSnapshot(encodedSession: String, encodedRun: String): FileStreamSnapshot? {
        return try {
            val streamDir = containedDirectory(
                canonicalRoot,
                SESSION_EVENTS_DIR,
                SESSION_EVENTS_VERSION_DIR,
                STREAMS_DIR,
                encodedSession,
                encodedRun,
            )
            readStreamDir(streamDir, encodedSession, encodedRun, identityIsEncoded = true)
        } catch (_: Exception) {
            null
        }
    }

    private fun loadSessionEvents(sessionId: String): List<SessionEvent> {
        val sessionDir = streamSessionDir(sessionId)
        if (!sessionDir.isDirectory) return emptyList()
        val events = listContainedDirectories(sessionDir).flatMap { runDir ->
            readStreamDir(runDir, sessionDir.name, runDir.name, identityIsEncoded = true).events
        }
        check(events.all { it.sessionId == sessionId }) { "Persisted event does not match its session path" }
        validateGlobalEventIds(events)
        return events.sortedWith(compareBy(SessionEvent::recordedAtEpochMillis, SessionEvent::runId, SessionEvent::sequence))
    }

    private fun listContainedDirectories(parent: File): List<File> {
        if (!existsNoFollow(parent)) return emptyList()
        rejectLink(parent.toPath())
        if (!parent.isDirectory) return emptyList()
        rejectAncestorLinks(canonicalRoot, parent)
        val children = parent.listFiles()
            ?: throw IllegalStateException("EventLog directory listing failed")
        return children.mapNotNull { child ->
            if (!existsNoFollow(child)) return@mapNotNull null
            if (isSymlinkOrJunction(child.toPath())) return@mapNotNull null
            if (!isSafePathSegment(child.name) || !child.isDirectory) return@mapNotNull null
            rejectAncestorLinks(canonicalRoot, child)
            val resolved = child.canonicalFile
            check(resolved.toPath().startsWith(canonicalRoot.toPath())) {
                "EventLog path escaped its canonical root"
            }
            resolved
        }
    }

    private fun readStream(sessionId: String, runId: String): FileStreamSnapshot {
        streamReadCount += 1
        val snapshot = readStreamDir(streamDir(sessionId, runId), sessionId, runId, identityIsEncoded = false)
        snapshot.events.forEach { stored ->
            check(stored.sessionId == sessionId && stored.runId == runId) {
                "Persisted event does not match its session/run path"
            }
        }
        return snapshot
    }

    private fun readStreamDir(
        streamDir: File,
        sessionId: String,
        runId: String,
        identityIsEncoded: Boolean,
    ): FileStreamSnapshot {
        val activeIndex = 1
        val defaultActive = segmentFile(sessionId, runId, activeIndex, identityIsEncoded)
        if (!streamDir.exists()) {
            return FileStreamSnapshot(
                sessionId = sessionId,
                runId = runId,
                streamDir = streamDir,
                manifest = emptyManifest(sessionId, runId),
                events = emptyList(),
                activeSegmentFile = defaultActive,
                activeByteLength = 0L,
                tornTail = false,
            )
        }
        check(streamDir.isDirectory) { "Event stream path is not a directory" }
        rejectLink(streamDir.toPath())
        val manifestFile = manifestFile(sessionId, runId, identityIsEncoded)
        val persistedManifest = manifestFile.isFile
        val manifest = if (persistedManifest) {
            rejectLink(manifestFile.toPath())
            decodeManifest(manifestFile.readText(Charsets.UTF_8))
        } else {
            emptyManifest(sessionId, runId)
        }
        val events = mutableListOf<SessionEvent>()
        var tornTail = false
        var activeFile = segmentFile(sessionId, runId, manifest.activeSegment, identityIsEncoded)
        var activeBytes = 0L
        val segmentIndexes = if (persistedManifest && manifest.segments.isNotEmpty()) {
            manifest.segments.map { it.index }.distinct().sorted()
        } else {
            listSegmentIndexes(sessionId, runId, identityIsEncoded)
        }
        for (index in segmentIndexes) {
            val file = segmentFile(sessionId, runId, index, identityIsEncoded)
            val closed = manifest.segments.firstOrNull { it.index == index }?.closed == true
            val checksum = manifest.segments.firstOrNull { it.index == index }?.sha256
            val loaded = readSegmentFile(
                file = file,
                closed = closed,
                expectedSha256 = checksum,
                sessionId = sessionId,
                runId = runId,
                identityIsEncoded = identityIsEncoded,
            )
            events += loaded.events
            if (index == (segmentIndexes.lastOrNull() ?: manifest.activeSegment)) {
                activeFile = file
                activeBytes = loaded.byteLength
                tornTail = loaded.tornTail
            }
            if (loaded.checksumFailed) {
                throw IllegalStateException("Closed event segment checksum mismatch")
            }
        }
        validatePersistedStream(events)
        return FileStreamSnapshot(
            sessionId = sessionId,
            runId = runId,
            streamDir = streamDir,
            manifest = if (!persistedManifest && events.isNotEmpty()) {
                recoveredManifest(sessionId, runId, events, segmentIndexes, activeFile)
            } else {
                manifest
            },
            events = events,
            activeSegmentFile = activeFile,
            activeByteLength = activeBytes,
            tornTail = tornTail,
        )
    }

    private fun recoveredManifest(
        sessionId: String,
        runId: String,
        events: List<SessionEvent>,
        indexes: List<Int>,
        activeFile: File,
    ): StreamManifest {
        val lastIndex = indexes.lastOrNull() ?: 1
        return StreamManifest(
            sessionId = sessionId,
            runId = runId,
            activeSegment = lastIndex,
            lastAcceptedSequence = events.last().sequence,
            lastPersistedSequence = events.last().sequence,
            segments = indexes.map { index ->
                SegmentRecord(
                    index = index,
                    fileName = segmentFileName(index),
                    closed = false,
                    eventCount = events.size,
                    firstSequence = events.first().sequence,
                    lastSequence = events.last().sequence,
                    sha256 = null,
                    byteLength = if (index == lastIndex && activeFile.isFile) activeFile.length() else 0L,
                )
            },
        )
    }

    private fun readSegmentFile(
        file: File,
        closed: Boolean,
        expectedSha256: String?,
        sessionId: String,
        runId: String,
        identityIsEncoded: Boolean,
    ): SegmentRead {
        if (!file.exists()) {
            return SegmentRead(events = emptyList(), byteLength = 0L, tornTail = false, checksumFailed = false)
        }
        check(file.isFile) { "Event segment path is not a regular file" }
        rejectLink(file.toPath())
        val bytes = file.readBytes()
        if (closed && expectedSha256 != null && sha256Hex(bytes) != expectedSha256) {
            return SegmentRead(events = emptyList(), byteLength = bytes.size.toLong(), tornTail = false, checksumFailed = true)
        }
        val text = String(bytes, Charsets.UTF_8)
        val torn = text.isNotEmpty() && !text.endsWith('\n')
        val completeText = if (torn) text.substringBeforeLast('\n', missingDelimiterValue = "").let { prefix ->
            if (prefix.isEmpty()) "" else "$prefix\n"
        } else {
            text
        }
        val events = completeText.lineSequence().filter { it.isNotEmpty() }.map { line ->
            try {
                decodeDurableLine(line, sessionId, runId, identityIsEncoded)
            } catch (failure: RuntimeException) {
                throw IllegalStateException("VFS event log contains a corrupt JSONL line", failure)
            }
        }.toList()
        return SegmentRead(
            events = events,
            byteLength = bytes.size.toLong(),
            tornTail = torn,
            checksumFailed = false,
        )
    }

    private fun decodeDurableLine(
        line: String,
        sessionId: String,
        runId: String,
        identityIsEncoded: Boolean,
    ): SessionEvent {
        decodedEvents.cached(line)?.let { return it }
        val envelope = blobEnvelopeOrNull(line) ?: return decodedEvents.read(line)
        val blob = blobFile(sessionId, runId, envelope.first, identityIsEncoded)
        check(blob.isFile) { "Event blob is missing" }
        rejectLink(blob.toPath())
        val payload = blob.readText(Charsets.UTF_8)
        check(sha256Hex(payload.toByteArray(Charsets.UTF_8)) == envelope.first) { "Event blob checksum mismatch" }
        return decodedEvents.read(payload)
    }

    private fun readOfflineHealth(sessionId: String, runId: String): SessionEventLogHealth {
        val stream = runCatching { streamDir(sessionId, runId) }.getOrNull()
        val sidecar = runCatching { healthFile(sessionId, runId) }.getOrNull()
        if (stream == null || sidecar == null) {
            return SessionEventLogHealth.unknown(SessionEventLogDurability.DURABLE)
        }
        val streamExists = stream.isDirectory && listSegmentIndexes(sessionId, runId).isNotEmpty()
        val sidecarExists = sidecar.isFile
        val streamRead = runCatching { if (streamExists) readStream(sessionId, runId) else null }
        val sidecarRead = runCatching {
            if (sidecarExists) decodeHealthSidecar(sidecar.readText(Charsets.UTF_8)) else null
        }
        val snapshot = streamRead.getOrNull()
        val events = snapshot?.events.orEmpty()
        val sidecarPayload = sidecarRead.getOrNull()
        val health = sidecarPayload?.health
        val identityMatched = sidecarIdentityMatches(
            sidecarSessionId = sidecarPayload?.sessionId,
            sidecarRunId = sidecarPayload?.runId,
            sessionId = sessionId,
            runId = runId,
        )
        val torn = snapshot?.tornTail == true
        val status = classifyReadOfflineHealth(
            streamExists = streamExists,
            sidecarExists = sidecarExists,
            events = events,
            health = health,
            identityMatched = identityMatched,
            streamCorrupt = streamExists && streamRead.isFailure,
            sidecarCorrupt = sidecarExists && sidecarRead.isFailure,
            schemaValid = sidecarRead.isSuccess || !sidecarExists,
            tornTail = torn,
        )
        if (status == SessionEventLogHealthStatus.UNKNOWN) {
            return SessionEventLogHealth.unknown(SessionEventLogDurability.DURABLE)
        }
        val offline = health?.copy(status = status)
            ?: SessionEventLogHealth.unknown(SessionEventLogDurability.DURABLE)
        return if (offline.status == SessionEventLogHealthStatus.COMPLETE && File(stream, ASYNC_OPEN_FILE).exists()) {
            offline.copy(status = SessionEventLogHealthStatus.DEGRADED, firstFailureCode = ASYNC_WRITER_OPEN)
        } else offline
    }

    private fun classifyReadOfflineHealth(
        streamExists: Boolean,
        sidecarExists: Boolean,
        events: List<SessionEvent>,
        health: SessionEventLogHealth?,
        identityMatched: Boolean,
        streamCorrupt: Boolean,
        sidecarCorrupt: Boolean,
        schemaValid: Boolean,
        tornTail: Boolean,
    ): SessionEventLogHealthStatus {
        val lastReadable = events.lastOrNull()?.sequence
        return SessionEventLogHealth.classifyOffline(
            streamExists = streamExists,
            sidecarExists = sidecarExists,
            eventsEmpty = !streamExists || events.isEmpty(),
            health = health?.status?.name,
            streamCorrupt = streamCorrupt,
            sidecarCorrupt = sidecarCorrupt,
            identityMatched = identityMatched,
            lastPersistedMatchesLastSequence = health?.lastPersistedSequence == lastReadable,
            coverageGap = (health?.failedCount ?: 0) > 0 || tornTail,
            countersConsistent = health != null && healthCountersExact(
                attemptedCount = health.attemptedCount,
                appendedCount = health.appendedCount,
                failedCount = health.failedCount,
                readableCount = events.size,
                lastPersistedSequence = health.lastPersistedSequence,
                lastReadableSequence = lastReadable,
            ),
            schemaValid = schemaValid,
        )
    }

    private fun writeSidecarBestEffort(sessionId: String, runId: String, tracker: MutableStreamHealth) {
        try {
            persistSidecar(healthFile(sessionId, runId), encodeHealth(tracker.snapshot(), sessionId, runId))
        } catch (_: Exception) {
            tracker.sidecarWriteFailed = true
        }
    }

    private fun writeManifest(
        sessionId: String,
        runId: String,
        previous: StreamManifest,
        stored: SessionEvent,
        lineBytes: Long,
        eventCount: Int = 1,
    ): StreamManifest {
        val activeIndex = previous.activeSegment
        val existing = previous.segments.firstOrNull { it.index == activeIndex }
        val updatedActive = SegmentRecord(
            index = activeIndex,
            fileName = segmentFileName(activeIndex),
            closed = false,
            eventCount = (existing?.eventCount ?: 0) + eventCount,
            firstSequence = existing?.firstSequence ?: (stored.sequence - eventCount + 1),
            lastSequence = stored.sequence,
            sha256 = null,
            byteLength = (existing?.byteLength ?: 0L) + lineBytes,
        )
        val segments = previous.segments.filterNot { it.index == activeIndex } + updatedActive
        val next = previous.copy(
            sessionId = sessionId,
            runId = runId,
            lastAcceptedSequence = stored.sequence,
            lastPersistedSequence = stored.sequence,
            segments = segments.sortedBy { it.index },
            activeSegment = activeIndex,
        )
        writeForcedText(manifestFile(sessionId, runId), encodeManifest(next))
        return next
    }

    private fun closeActiveSegment(
        sessionId: String,
        runId: String,
        previous: StreamManifest,
        activeFile: File,
    ): StreamManifest {
        val bytes = if (activeFile.isFile) activeFile.readBytes() else ByteArray(0)
        val closedIndex = previous.activeSegment
        val existing = previous.segments.firstOrNull { it.index == closedIndex }
        val closed = SegmentRecord(
            index = closedIndex,
            fileName = segmentFileName(closedIndex),
            closed = true,
            eventCount = existing?.eventCount ?: 0,
            firstSequence = existing?.firstSequence ?: FIRST_EVENT_SEQUENCE,
            lastSequence = existing?.lastSequence ?: previous.lastPersistedSequence,
            sha256 = if (bytes.isEmpty()) null else sha256Hex(bytes),
            byteLength = bytes.size.toLong(),
        )
        val nextIndex = closedIndex + 1
        val next = previous.copy(
            sessionId = sessionId,
            runId = runId,
            activeSegment = nextIndex,
            segments = (previous.segments.filterNot { it.index == closedIndex } + closed).sortedBy { it.index },
        )
        writeForcedText(manifestFile(sessionId, runId), encodeManifest(next))
        return next
    }

    private fun truncateTornTail(file: File) {
        if (!file.isFile) return
        val bytes = file.readBytes()
        val text = String(bytes, Charsets.UTF_8)
        if (text.isEmpty() || text.endsWith('\n')) return
        val prefix = text.substringBeforeLast('\n', missingDelimiterValue = "")
        val rewritten = if (prefix.isEmpty()) ByteArray(0) else "$prefix\n".toByteArray(Charsets.UTF_8)
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(rewritten.size.toLong())
            raf.seek(0)
            raf.write(rewritten)
            raf.fd.sync()
        }
    }

    private fun listSegmentIndexes(sessionId: String, runId: String, identityIsEncoded: Boolean = false): List<Int> {
        val segmentsDir = containedDirectory(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            STREAMS_DIR,
            encodedOrRaw(sessionId, identityIsEncoded),
            encodedOrRaw(runId, identityIsEncoded),
            SEGMENTS_DIR,
        )
        if (!segmentsDir.isDirectory) return emptyList()
        rejectLink(segmentsDir.toPath())
        return segmentsDir.listFiles().orEmpty()
            .filter { it.isFile && !isSymlinkOrJunction(it.toPath()) && it.name.matches(Regex("""\d{6}\.jsonl""")) }
            .map { it.name.removeSuffix(".jsonl").toInt() }
            .sorted()
    }

    private fun streamDir(sessionId: String, runId: String): File =
        containedDirectory(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            STREAMS_DIR,
            encodeFileName(sessionId),
            encodeFileName(runId),
        )

    private fun manifestFile(sessionId: String, runId: String, identityIsEncoded: Boolean = false): File =
        containedFile(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            STREAMS_DIR,
            encodedOrRaw(sessionId, identityIsEncoded),
            encodedOrRaw(runId, identityIsEncoded),
            MANIFEST_FILE,
        )

    private fun segmentFile(
        sessionId: String,
        runId: String,
        index: Int,
        identityIsEncoded: Boolean = false,
    ): File =
        containedFile(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            STREAMS_DIR,
            encodedOrRaw(sessionId, identityIsEncoded),
            encodedOrRaw(runId, identityIsEncoded),
            SEGMENTS_DIR,
            segmentFileName(index),
        )

    private fun blobFile(
        sessionId: String,
        runId: String,
        sha256: String,
        identityIsEncoded: Boolean = false,
    ): File =
        containedFile(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            STREAMS_DIR,
            encodedOrRaw(sessionId, identityIsEncoded),
            encodedOrRaw(runId, identityIsEncoded),
            BLOBS_DIR,
            sha256,
        )

    private fun encodedOrRaw(value: String, identityIsEncoded: Boolean): String =
        if (identityIsEncoded) value else encodeFileName(value)

    private fun streamSessionDir(sessionId: String): File =
        containedDirectory(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            STREAMS_DIR,
            encodeFileName(sessionId),
        )

    private fun healthFile(sessionId: String, runId: String): File =
        containedFile(
            canonicalRoot,
            SESSION_EVENTS_DIR,
            SESSION_EVENTS_VERSION_DIR,
            HEALTH_DIR,
            encodeFileName(sessionId),
            "${encodeFileName(runId)}.json",
        )

    private fun <T> withLogLock(block: () -> T): T = synchronized(processLock) {
        canonicalRoot.mkdirs()
        val lockFile = File(canonicalRoot, EVENT_LOG_LOCK_FILE)
        RandomAccessFile(lockFile, "rw").use { owner ->
            val lock = owner.channel.lock()
            try {
                block()
            } finally {
                lock.release()
            }
        }
    }

    private companion object {
        private val PROCESS_LOCKS = ConcurrentHashMap<String, Any>()

        fun processLockFor(root: File): Any =
            PROCESS_LOCKS.computeIfAbsent(root.canonicalPath) { Any() }
    }
}

private data class PreparedSegment(
    val file: File,
    val manifest: StreamManifest,
)

private data class SegmentRead(
    val events: List<SessionEvent>,
    val byteLength: Long,
    val tornTail: Boolean,
    val checksumFailed: Boolean,
)

internal fun prepareDurableRoot(root: File): File {
    rejectSymlink(root.toPath())
    if (root.exists() && !root.isDirectory) {
        throw IllegalArgumentException("EventLog root must be a directory")
    }
    if (!root.mkdirs() && !root.isDirectory) {
        throw IllegalStateException("EventLog root cannot be created")
    }
    val canonical = root.canonicalFile
    rejectSymlink(canonical.toPath())
    probeWritable(canonical)
    deleteLegacySessionEventLogV1(canonical)
    return canonical
}

/** First v2 open deletes leftover metadata-only v1 trees. Content is not migrated. */
internal fun deleteLegacySessionEventLogV1(root: File) {
    val legacy = runCatching {
        containedDirectory(root, SESSION_EVENTS_DIR, SESSION_EVENTS_LEGACY_VERSION_DIR)
    }.getOrNull() ?: return
    if (legacy.exists()) {
        legacy.deleteRecursively()
    }
}

internal fun probeWritable(root: File) {
    // Each open probes with its own file: two runtimes opening one session's log at once (a voice turn arriving while
    // the last one finished, 2026-10-21) shared one name, and the loser's rename found the staging file gone and failed
    // the run with 「The source file doesn't exist」.
    val probe = containedFile(root, "$EVENT_LOG_OPEN_PROBE_FILE-${java.util.UUID.randomUUID()}")
    try {
        writeForcedText(probe, "ok")
        check(probe.isFile) { "EventLog root is not writable" }
    } finally {
        probe.delete()
    }
}

internal fun containedDirectory(root: File, vararg segments: String): File =
    containedFile(root, *segments)

internal fun containedFile(root: File, vararg segments: String): File {
    val canonicalRoot = root.canonicalFile
    rejectLink(canonicalRoot.toPath())
    var current = canonicalRoot
    segments.forEach { segment ->
        require(isSafePathSegment(segment)) { "EventLog path segment is invalid" }
        current = File(current, segment)
        if (existsNoFollow(current)) {
            rejectLink(current.toPath())
        }
    }
    rejectAncestorLinks(canonicalRoot, current)
    val resolved = if (existsNoFollow(current) && current.exists()) current.canonicalFile else current.normalize()
    val rootPath = canonicalRoot.toPath()
    val childPath = resolved.toPath()
    check(childPath.startsWith(rootPath)) { "EventLog path escaped its canonical root" }
    return if (existsNoFollow(current) && current.exists()) resolved else current
}

internal fun isSafePathSegment(segment: String): Boolean =
    segment.isNotBlank() &&
        segment != "." &&
        segment != ".." &&
        '/' !in segment &&
        '\\' !in segment &&
        segment.none { it.code < 32 }

internal fun rejectSymlink(path: java.nio.file.Path) = rejectLink(path)

internal fun rejectLink(path: java.nio.file.Path) {
    if (isSymlinkOrJunction(path)) {
        throw IllegalStateException("EventLog path must not be a symbolic link or junction")
    }
}

internal fun isSymlinkOrJunction(path: java.nio.file.Path): Boolean {
    if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
    if (Files.isSymbolicLink(path)) return true
    return try {
        val noFollow = path.toRealPath(java.nio.file.LinkOption.NOFOLLOW_LINKS)
        val followed = path.toRealPath()
        noFollow != followed
    } catch (_: Exception) {
        Files.isSymbolicLink(path)
    }
}

internal fun existsNoFollow(file: File): Boolean =
    Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)

internal fun rejectAncestorLinks(root: File, leaf: File) {
    val rootPath = root.canonicalFile.toPath()
    var cursor: File? = if (existsNoFollow(leaf) && leaf.exists()) leaf.canonicalFile else leaf.absoluteFile
    while (cursor != null) {
        if (existsNoFollow(cursor)) {
            rejectLink(cursor.toPath())
        }
        val cursorPath = (if (cursor.exists()) cursor.canonicalFile else cursor.absoluteFile).toPath()
        if (cursorPath == rootPath) break
        cursor = cursor.parentFile
    }
}

internal fun utf8ByteLength(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

internal fun appendForcedLine(file: File, line: String) {
    file.parentFile?.mkdirs()
    rejectSymlink(file.toPath())
    RandomAccessFile(file, "rw").use { raf ->
        raf.seek(raf.length())
        raf.write((line + "\n").toByteArray(Charsets.UTF_8))
        raf.fd.sync()
        forceChannel(raf.channel)
    }
}

internal fun writeForcedText(file: File, text: String) = writeForcedBytes(file, text.toByteArray(Charsets.UTF_8))

internal fun writeForcedBytes(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    file.parentFile?.let { if (existsNoFollow(it)) rejectLink(it.toPath()) }
    if (existsNoFollow(file)) rejectLink(file.toPath())
    val staging = File(file.parentFile, file.name + ".tmp")
    if (existsNoFollow(staging)) rejectLink(staging.toPath())
    RandomAccessFile(staging, "rw").use { raf ->
        raf.setLength(0)
        raf.write(bytes)
        raf.fd.sync()
        forceChannel(raf.channel)
    }
    if (existsNoFollow(file)) rejectLink(file.toPath())
    if (!staging.renameTo(file)) {
        staging.copyTo(file, overwrite = true)
        staging.delete()
    }
}

private fun forceChannel(channel: FileChannel) {
    channel.force(true)
}

internal const val EVENT_LOG_LOCK_FILE = ".session-event-log.lock"
internal const val EVENT_LOG_OPEN_PROBE_FILE = ".eventlog-open-probe"
internal const val EVENT_ID_GENERATION_FILE = ".event-id-generation"
internal const val ASYNC_WRITER_OPEN = "ASYNC_WRITER_OPEN"
private const val ASYNC_OPEN_FILE = ".async-writer-open"
