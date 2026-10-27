@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.eventlog

import cc.noharry.agent.kernel.api.SessionEventAppend
import cc.noharry.agent.kernel.api.SessionEventLogDurability
import cc.noharry.agent.kernel.api.SessionEventLogHealthStatus
import cc.noharry.agent.kernel.api.SessionEventPayload
import cc.noharry.agent.kernel.api.SessionEventQuery
import cc.noharry.agent.kernel.api.SessionEventType
import cc.noharry.agent.kernel.core.state.encodeFileName
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

class FileBackedSessionEventLogTest {
    @Test
    fun forcingADirectoryNeverFailsAWrite() {
        val root = Files.createTempDirectory("file-event-log-dir-force-").toFile()
        try {
            val manifest = File(root, "stream/manifest.json")
            writeForcedText(manifest, "{}")
            appendForcedLine(File(root, "stream/segments/000001.jsonl"), "{}")
            forceDirectory(File(root, "missing"))
            forceDirectory(manifest)
            forceDirectory(null)

            assertEquals("{}", manifest.readText())
            assertFalse("no staging file is left", File(root, "stream/manifest.json.tmp").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimesOpeningOneLogAtOnceNeverTripOverEachOthersProbe() {
        // 2026-10-21 on a device: two opens of one session's log raced on the probe's staging file, and one run failed
        // with 「The source file doesn't exist」.
        val root = Files.createTempDirectory("file-event-log-probe-race-").toFile()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val opens = (1..200).map { Callable { prepareDurableRoot(root) } }
            pool.invokeAll(opens).forEach { it.get(10, TimeUnit.SECONDS) }
            assertTrue("no probe is left behind", root.listFiles().orEmpty().none { it.name.startsWith(EVENT_LOG_OPEN_PROBE_FILE) })
        } finally {
            pool.shutdownNow()
            root.deleteRecursively()
        }
    }

    @Test
    fun restartReopensContiguousSequenceAndOfficialEncodedPaths() {
        val root = Files.createTempDirectory("file-event-log-restart-").toFile()
        val first = FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = { "event-a" })
        first.append(event(SessionEventType.RUN_STARTED))

        val second = FileBackedSessionEventLog(root, nowEpochMillis = { 200 }, eventIdFactory = { "event-b" })
        val appended = second.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))

        assertEquals(2L, appended.sequence)
        assertEquals(listOf("event-a", "event-b"), second.replay(SESSION_ID, RUN_ID).map { it.eventId })
        assertEquals(listOf(1L, 2L), second.query(SessionEventQuery(SESSION_ID, RUN_ID)).map { it.sequence })
        assertTrue(eventFile(root).isFile)
        assertTrue(manifestFile(root).isFile)
        assertTrue(healthFile(root).isFile)
        assertFalse(File(root, "session-events/v1/streams/$SESSION_ID").exists())
        assertFalse(File(root, "sessions").exists())
        assertEquals(SessionEventLogHealthStatus.COMPLETE, second.health(SESSION_ID, RUN_ID).status)
        assertEquals(SessionEventLogDurability.DURABLE, second.durability())
    }

    @Test
    fun leftoverV1TreeIsDeletedOnFirstV2Open() {
        val root = Files.createTempDirectory("file-event-log-v1-clear-").toFile()
        val leftover = File(root, "session-events/v1/streams/old")
        leftover.mkdirs()
        File(leftover, "events.jsonl").writeText("{}\n")

        FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = { "event-a" })
            .append(event(SessionEventType.RUN_STARTED))

        assertFalse(File(root, "session-events/v1").exists())
        assertTrue(File(root, "session-events/v2").exists())
    }

    @Test
    fun concurrentAndCrossInstanceAppendsStayContiguous() {
        val root = Files.createTempDirectory("file-event-log-concurrent-").toFile()
        val first = FileBackedSessionEventLog(root, nowEpochMillis = { 100 })
        val second = FileBackedSessionEventLog(root, nowEpochMillis = { 100 })
        val executor = Executors.newFixedThreadPool(WORKER_COUNT)
        try {
            val tasks = (1..EVENT_COUNT).map { index ->
                Callable {
                    val log = if (index % 2 == 0) first else second
                    log.append(event(SessionEventType.RUN_STATUS))
                }
            }
            executor.invokeAll(tasks).forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }

        val replay = FileBackedSessionEventLog(root).replay(SESSION_ID, RUN_ID)
        assertEquals((1L..EVENT_COUNT.toLong()).toList(), replay.map { it.sequence })
        assertEquals(EVENT_COUNT, replay.map { it.eventId }.toSet().size)
        assertEquals(EVENT_COUNT, eventFile(root).readLines().size)
    }

    @Test
    fun tornActiveSegmentRecoversPrefixAndTruncatesOnlyOnSameStreamAppend() {
        val root = Files.createTempDirectory("file-event-log-torn-").toFile()
        val log = FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = incrementalIds())
        log.append(event(SessionEventType.RUN_STARTED))
        eventFile(root).appendText(TRUNCATED_EVENT_LINE)
        val torn = eventFile(root).readBytes()

        assertEquals(listOf(1L), log.replay(SESSION_ID, RUN_ID).map { it.sequence })
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_ID, RUN_ID).status)
        assertTrue(torn.contentEquals(eventFile(root).readBytes()))

        log.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_B, runId = SHARED_RUN))
        assertTrue(torn.contentEquals(eventFile(root).readBytes()))
        assertEquals(1, log.replay(SESSION_B, SHARED_RUN).size)

        val recovered = log.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))
        assertEquals(2L, recovered.sequence)
        assertEquals(listOf(1L, 2L), log.replay(SESSION_ID, RUN_ID).map { it.sequence })
        assertFalse(eventFile(root).readText().contains(TRUNCATED_EVENT_LINE))
    }

    @Test
    fun encodedPathsRejectRawIdsAndTraversalSegments() {
        val root = Files.createTempDirectory("file-event-log-path-").toFile()
        val log = FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = { "event-a" })
        log.append(event(SessionEventType.RUN_STARTED, sessionId = TRAVERSAL_SESSION, runId = TRAVERSAL_RUN))

        val expected = eventFile(root, TRAVERSAL_SESSION, TRAVERSAL_RUN)
        assertTrue(expected.isFile)
        val streamChildren = File(root, STREAMS_RELATIVE).list().orEmpty().toList()
        assertFalse(streamChildren.contains(".."))
        assertFalse(streamChildren.contains(TRAVERSAL_SESSION))
        assertFalse(File(root, TRAVERSAL_SESSION).exists())
        assertTrue(root.walkTopDown().filter { it.name.endsWith(".jsonl") }.all { it.canonicalPath.startsWith(root.canonicalPath) })
    }

    @Test
    fun symlinkRootIsRejected() {
        val parent = Files.createTempDirectory("file-event-log-symlink-").toFile()
        val real = File(parent, "real").apply { mkdirs() }
        val link = File(parent, "link")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), real.toPath()) }.isSuccess
        Assume.assumeTrue("OS or policy does not allow symbolic links", created)
        assertThrows(IllegalStateException::class.java) { FileBackedSessionEventLog(link) }
    }

    @Test
    fun appendFailureUpdatesHealthWithoutRepairingHistory() {
        val root = Files.createTempDirectory("file-event-log-partial-").toFile()
        var remainingSuccesses = 1
        val log = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = { "event-${System.nanoTime()}" },
            persistLine = { file, line ->
                if (remainingSuccesses <= 0) error("injected append failure")
                remainingSuccesses -= 1
                appendForcedLine(file, line)
            },
        )
        log.append(event(SessionEventType.RUN_STARTED))
        assertThrows(IllegalStateException::class.java) {
            log.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))
        }

        val health = log.health(SESSION_ID, RUN_ID)
        assertEquals(SessionEventLogHealthStatus.DEGRADED, health.status)
        assertEquals(2, health.attemptedCount)
        assertEquals(1, health.appendedCount)
        assertEquals(1, health.failedCount)
        assertEquals(2L, health.lastAcceptedSequence)
        assertEquals(1L, health.lastPersistedSequence)
        assertEquals(1, FileBackedSessionEventLog(root).replay(SESSION_ID, RUN_ID).size)
    }

    @Test
    fun missingOrCorruptSidecarIsUnknownNeverComplete() {
        val root = Files.createTempDirectory("file-event-log-sidecar-").toFile()
        var nextId = 0
        val writer = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = { "event-${++nextId}" },
        )
        writer.append(event(SessionEventType.RUN_STARTED))
        healthFile(root).delete()

        val missing = FileBackedSessionEventLog(root)
        assertEquals(SessionEventLogHealthStatus.UNKNOWN, missing.health(SESSION_ID, RUN_ID).status)
        assertEquals(1, missing.replay(SESSION_ID, RUN_ID).size)

        writer.append(event(SessionEventType.RUN_STATUS))
        healthFile(root).writeText(INCOMPLETE_HEALTH_SIDECAR)
        val corrupt = FileBackedSessionEventLog(root)
        assertEquals(SessionEventLogHealthStatus.UNKNOWN, corrupt.health(SESSION_ID, RUN_ID).status)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, writer.health(SESSION_ID, RUN_ID).status)
    }

    @Test
    fun sameRunIdIsIsolatedAcrossSessionsAndSurvivesRestart() {
        val root = Files.createTempDirectory("file-event-log-isolate-").toFile()
        val first = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
        )
        first.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_A, runId = SHARED_RUN))
        first.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_B, runId = SHARED_RUN))

        assertTrue(eventFile(root, SESSION_A, SHARED_RUN).isFile)
        assertTrue(eventFile(root, SESSION_B, SHARED_RUN).isFile)
        assertTrue(healthFile(root, SESSION_A, SHARED_RUN).isFile)
        assertTrue(healthFile(root, SESSION_B, SHARED_RUN).isFile)
        assertNotEquals(eventFile(root, SESSION_A, SHARED_RUN), eventFile(root, SESSION_B, SHARED_RUN))
        assertEquals(SessionEventLogHealthStatus.COMPLETE, first.health(SESSION_A, SHARED_RUN).status)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, first.health(SESSION_B, SHARED_RUN).status)

        val restarted = FileBackedSessionEventLog(root)
        assertEquals(1, restarted.replay(SESSION_A, SHARED_RUN).size)
        assertEquals(1, restarted.replay(SESSION_B, SHARED_RUN).size)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, restarted.health(SESSION_A, SHARED_RUN).status)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, restarted.health(SESSION_B, SHARED_RUN).status)

        healthFile(root, SESSION_A, SHARED_RUN).delete()
        assertEquals(SessionEventLogHealthStatus.UNKNOWN, restarted.health(SESSION_A, SHARED_RUN).status)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, restarted.health(SESSION_B, SHARED_RUN).status)
    }

    @Test
    fun corruptSiblingMainJsonlDoesNotBlockHealthyStreamOrRepairEvidence() {
        val root = Files.createTempDirectory("file-event-log-sibling-corrupt-").toFile()
        val nextId = incrementalIds()
        FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = nextId,
        ).apply {
            append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_A, runId = SHARED_RUN))
            append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_B, runId = SHARED_RUN))
        }

        val corruptFile = eventFile(root, SESSION_A, SHARED_RUN)
        corruptFile.appendText(TRUNCATED_EVENT_LINE)
        val corrupted = corruptFile.readBytes()

        val reopened = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 200 },
            eventIdFactory = nextId,
        )
        assertEquals(listOf(1L), reopened.replay(SESSION_A, SHARED_RUN).map { it.sequence })
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, reopened.health(SESSION_A, SHARED_RUN).status)

        val appended = reopened.append(event(SessionEventType.RUN_STATUS, sessionId = SESSION_B, runId = SHARED_RUN))
        assertEquals(2L, appended.sequence)
        assertEquals(listOf(1L, 2L), reopened.replay(SESSION_B, SHARED_RUN).map { it.sequence })
        assertEquals(SessionEventLogHealthStatus.COMPLETE, reopened.health(SESSION_B, SHARED_RUN).status)
        assertTrue(corrupted.contentEquals(corruptFile.readBytes()))
    }

    @Test
    fun healthyCrossStreamDuplicateEventIdIsRejected() {
        val root = Files.createTempDirectory("file-event-log-dup-id-").toFile()
        val log = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = { DUPLICATE_EVENT_ID },
        )
        log.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_A, runId = SHARED_RUN))
        assertThrows(IllegalStateException::class.java) {
            log.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_B, runId = SHARED_RUN))
        }
        assertEquals(1, log.replay(SESSION_A, SHARED_RUN).size)
        assertEquals(0, log.replay(SESSION_B, SHARED_RUN).size)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_A, SHARED_RUN).status)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_B, SHARED_RUN).status)
    }

    @Test
    fun liveAndOfflineHealthFailClosedForTamperedEvidence() {
        val cases = listOf(
            TamperCase("truncated") { eventFile(it).writeText(TRUNCATED_EVENT_LINE) },
            TamperCase("identity-mismatch") { writeCompleteSidecar(it, sessionId = "other-session") },
            TamperCase("stale-ahead") { writeCompleteSidecar(it, lastPersistedSequence = 9L) },
            TamperCase("stale-behind") { writeCompleteSidecar(it, lastPersistedSequence = 0L) },
            TamperCase("counter-inconsistency") { writeCompleteSidecar(it, appendedCount = 9) },
            TamperCase("empty-stream") { eventFile(it).writeText("") },
            TamperCase("quoted-attempted-count") {
                healthFile(it).writeText(quotedCompleteSidecar().replace("\"attemptedCount\":1", "\"attemptedCount\":\"1\""))
            },
            TamperCase("quoted-appended-count") {
                healthFile(it).writeText(quotedCompleteSidecar().replace("\"appendedCount\":1", "\"appendedCount\":\"1\""))
            },
            TamperCase("quoted-last-sequence") {
                healthFile(it).writeText(
                    quotedCompleteSidecar().replace("\"lastPersistedSequence\":1", "\"lastPersistedSequence\":\"1\""),
                )
            },
            TamperCase("numeric-session-identity") {
                healthFile(it).writeText(quotedCompleteSidecar().replace("\"sessionId\":\"$SESSION_ID\"", "\"sessionId\":1"))
            },
            TamperCase("numeric-run-identity") {
                healthFile(it).writeText(quotedCompleteSidecar().replace("\"runId\":\"$RUN_ID\"", "\"runId\":1"))
            },
        )
        cases.forEach { case ->
            val root = Files.createTempDirectory("file-event-log-${case.name}-").toFile()
            val live = FileBackedSessionEventLog(
                root,
                nowEpochMillis = { 100 },
                eventIdFactory = { "event-${case.name}" },
            )
            live.append(event(SessionEventType.RUN_STARTED))
            case.mutate(root)
            assertNotEquals(case.name, SessionEventLogHealthStatus.COMPLETE, live.health(SESSION_ID, RUN_ID).status)
            val offline = FileBackedSessionEventLog(root)
            assertNotEquals(case.name, SessionEventLogHealthStatus.COMPLETE, offline.health(SESSION_ID, RUN_ID).status)
        }
    }

    @Test
    fun typedJsonTamperedStreamsRejectReplayQueryAndCannotComplete() {
        typedStreamTamperCases().forEach(::assertTypedStreamTamperRejected)
    }

    private fun typedStreamTamperCases(): List<TypedStreamTamper> = listOf(
            TypedStreamTamper(
                name = "numeric-session-id",
                sessionId = NUMERIC_SESSION_ID,
                runId = NUMERIC_RUN_ID,
                seed = { log ->
                    log.append(
                        event(
                            SessionEventType.RUN_STARTED,
                            sessionId = NUMERIC_SESSION_ID,
                            runId = NUMERIC_RUN_ID,
                        ),
                    )
                },
                mutate = { root ->
                    rewriteEventFile(root, NUMERIC_SESSION_ID, NUMERIC_RUN_ID) { encoded ->
                        encoded.replace("\"sessionId\":\"$NUMERIC_SESSION_ID\"", "\"sessionId\":$NUMERIC_SESSION_ID")
                    }
                },
            ),
            TypedStreamTamper(
                name = "quoted-schema",
                seed = { it.append(event(SessionEventType.RUN_STARTED)) },
                mutate = { root ->
                    rewriteEventFile(root) { it.replace("\"schemaVersion\":2", "\"schemaVersion\":\"2\"") }
                },
            ),
            TypedStreamTamper(
                name = "quoted-sequence",
                seed = { it.append(event(SessionEventType.RUN_STARTED)) },
                mutate = { root ->
                    rewriteEventFile(root) { it.replace("\"sequence\":1", "\"sequence\":\"1\"") }
                },
            ),
            TypedStreamTamper(
                name = "quoted-recorded-at",
                seed = { it.append(event(SessionEventType.RUN_STARTED)) },
                mutate = { root ->
                    rewriteEventFile(root) {
                        it.replace("\"recordedAtEpochMillis\":100", "\"recordedAtEpochMillis\":\"100\"")
                    }
                },
            ),
            TypedStreamTamper(
                name = "numeric-payload-metadata",
                seed = {
                    it.append(
                        event(
                            SessionEventType.RUN_STARTED,
                            payload = SessionEventPayload.RunLifecycle(phase = "1"),
                        ),
                    )
                },
                mutate = { root ->
                    rewriteEventFile(root) { it.replace("\"phase\":\"1\"", "\"phase\":1") }
                },
            ),
            TypedStreamTamper(
                name = "quoted-source-sequence",
                seed = {
                    it.append(event(SessionEventType.RUN_STARTED))
                    it.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))
                },
                mutate = { root ->
                    rewriteEventFile(root) {
                        it.replace("\"sourceEventSequences\":[1]", "\"sourceEventSequences\":[\"1\"]")
                    }
                },
            ),
        )

    private fun assertTypedStreamTamperRejected(case: TypedStreamTamper) {
        val root = Files.createTempDirectory("file-event-log-typed-${case.name}-").toFile()
        val live = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
        )
        case.seed(live)
        assertEquals(case.name, SessionEventLogHealthStatus.COMPLETE, live.health(case.sessionId, case.runId).status)
        case.mutate(root)
        assertThrows(case.name, IllegalStateException::class.java) {
            live.replay(case.sessionId, case.runId)
        }
        assertThrows(case.name, IllegalStateException::class.java) {
            live.query(SessionEventQuery(case.sessionId, case.runId))
        }
        assertNotEquals(case.name, SessionEventLogHealthStatus.COMPLETE, live.health(case.sessionId, case.runId).status)
        val offline = FileBackedSessionEventLog(root)
        assertThrows(case.name, IllegalStateException::class.java) {
            offline.replay(case.sessionId, case.runId)
        }
        assertThrows(case.name, IllegalStateException::class.java) {
            offline.query(SessionEventQuery(case.sessionId, case.runId))
        }
        assertNotEquals(
            case.name,
            SessionEventLogHealthStatus.COMPLETE,
            offline.health(case.sessionId, case.runId).status,
        )
    }

    @Test
    fun failedAppendThenSuccessDoesNotSelfHealComplete() {
        val root = Files.createTempDirectory("file-event-log-no-heal-").toFile()
        var remainingSuccesses = 1
        val live = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
            persistLine = { file, line ->
                if (remainingSuccesses <= 0) {
                    remainingSuccesses = 1
                    error("injected append gap")
                }
                remainingSuccesses -= 1
                appendForcedLine(file, line)
            },
        )
        live.append(event(SessionEventType.RUN_STARTED))
        assertThrows(IllegalStateException::class.java) {
            live.append(event(SessionEventType.RUN_STATUS))
        }
        val recovered = live.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))
        assertEquals(2L, recovered.sequence)
        assertEquals(SessionEventLogHealthStatus.DEGRADED, live.health(SESSION_ID, RUN_ID).status)
        assertEquals(1, live.health(SESSION_ID, RUN_ID).failedCount)

        val offline = FileBackedSessionEventLog(root)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, offline.health(SESSION_ID, RUN_ID).status)
        assertEquals(2, offline.replay(SESSION_ID, RUN_ID).size)
    }

    @Test
    fun sidecarWriteFailureIsDegradedLiveAndUnknownOffline() {
        val root = Files.createTempDirectory("file-event-log-sidecar-write-").toFile()
        val live = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = { "event-sidecar-write" },
            persistSidecar = { _, _ -> error("injected sidecar write failure") },
        )
        live.append(event(SessionEventType.RUN_STARTED))
        assertEquals(SessionEventLogHealthStatus.DEGRADED, live.health(SESSION_ID, RUN_ID).status)
        assertFalse(healthFile(root).isFile)

        val offline = FileBackedSessionEventLog(root)
        assertEquals(SessionEventLogHealthStatus.UNKNOWN, offline.health(SESSION_ID, RUN_ID).status)
        assertEquals(1, offline.replay(SESSION_ID, RUN_ID).size)
    }

    @Test
    fun blankAndOverlongIdentitiesAreRejected() {
        val root = Files.createTempDirectory("file-event-log-ids-").toFile()
        val log = FileBackedSessionEventLog(root, nowEpochMillis = { 100 }, eventIdFactory = { "event-id" })
        assertThrows(IllegalArgumentException::class.java) {
            log.append(event(SessionEventType.RUN_STARTED, sessionId = " "))
        }
        assertThrows(IllegalArgumentException::class.java) {
            log.append(event(SessionEventType.RUN_STARTED, sessionId = "s".repeat(257)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            log.append(event(SessionEventType.RUN_STARTED, runId = "r".repeat(257)))
        }
        assertThrows(IllegalArgumentException::class.java) { log.health(" ", RUN_ID) }
        assertThrows(IllegalArgumentException::class.java) { log.health(SESSION_ID, "r".repeat(257)) }
        assertThrows(IllegalArgumentException::class.java) { log.replay("", RUN_ID) }
    }

    @Test
    fun ancestorAndSidecarSymlinksAreRejectedOrFailClosed() {
        val parent = Files.createTempDirectory("file-event-log-ancestor-").toFile()
        val root = File(parent, "root").apply { mkdirs() }
        val outside = File(parent, "outside").apply { mkdirs() }
        val created = runCatching {
            Files.createSymbolicLink(File(root, SESSION_EVENTS_DIR).toPath(), outside.toPath())
        }.isSuccess
        Assume.assumeTrue("OS or policy does not allow symbolic links", created)
        assertThrows(IllegalStateException::class.java) {
            FileBackedSessionEventLog(root).append(event(SessionEventType.RUN_STARTED))
        }

        val sidecarRoot = Files.createTempDirectory("file-event-log-sidecar-link-").toFile()
        val live = FileBackedSessionEventLog(
            sidecarRoot,
            nowEpochMillis = { 100 },
            eventIdFactory = { "event-sidecar-link" },
        )
        live.append(event(SessionEventType.RUN_STARTED))
        val sidecar = healthFile(sidecarRoot)
        val decoy = File(parent, "decoy-health.json").apply { writeText(INCOMPLETE_HEALTH_SIDECAR) }
        sidecar.delete()
        val sidecarLinked = runCatching { Files.createSymbolicLink(sidecar.toPath(), decoy.toPath()) }.isSuccess
        Assume.assumeTrue("OS or policy does not allow sidecar symbolic links", sidecarLinked)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, live.health(SESSION_ID, RUN_ID).status)
        assertNotEquals(
            SessionEventLogHealthStatus.COMPLETE,
            FileBackedSessionEventLog(sidecarRoot).health(SESSION_ID, RUN_ID).status,
        )
    }

    @Test
    fun stagingSymlinkFailsClosedWithoutClaimingComplete() {
        val root = Files.createTempDirectory("file-event-log-staging-").toFile()
        val live = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
        )
        live.append(event(SessionEventType.RUN_STARTED))
        val sidecar = healthFile(root)
        val staging = File(sidecar.parentFile, sidecar.name + ".tmp")
        val decoy = File(root, "staging-decoy.json").apply { writeText("{}") }
        val created = runCatching { Files.createSymbolicLink(staging.toPath(), decoy.toPath()) }.isSuccess
        Assume.assumeTrue("OS or policy does not allow staging symbolic links", created)
        live.append(event(SessionEventType.RUN_STATUS))
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, live.health(SESSION_ID, RUN_ID).status)
    }

    @Test
    fun windowsJunctionRootOrAncestorIsRejected() {
        val parent = Files.createTempDirectory("file-event-log-junction-").toFile()
        val real = File(parent, "real").apply { mkdirs() }
        val link = File(parent, "junction")
        val created = tryCreateWindowsJunction(link, real)
        Assume.assumeTrue(
            "Windows junction could not be created safely (needs cmd mklink /J and a non-elevated capable volume)",
            created,
        )
        assertThrows(IllegalStateException::class.java) { FileBackedSessionEventLog(link) }

        val root = File(parent, "root").apply { mkdirs() }
        val outside = File(parent, "junction-outside").apply { mkdirs() }
        val ancestor = File(root, SESSION_EVENTS_DIR)
        val ancestorCreated = tryCreateWindowsJunction(ancestor, outside)
        Assume.assumeTrue("Windows ancestor junction could not be created safely", ancestorCreated)
        assertThrows(IllegalStateException::class.java) {
            FileBackedSessionEventLog(root).append(event(SessionEventType.RUN_STARTED))
        }
    }

    @Test
    fun missingSidecarIdentityIsNotAMatchAndNeverComplete() {
        val root = Files.createTempDirectory("file-event-log-identity-").toFile()
        FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = { "event-identity" },
        ).append(event(SessionEventType.RUN_STARTED))
        writeCompleteSidecar(root, sessionId = "", runId = "")
        healthFile(root).writeText(
            """{"schemaVersion":1,"status":"COMPLETE","attemptedCount":1,"appendedCount":1,"failedCount":0,"lastPersistedSequence":1,"backendDurability":"DURABLE","recorderCoverageVersion":1}""",
        )

        val offline = FileBackedSessionEventLog(root)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, offline.health(SESSION_ID, RUN_ID).status)
        val afterAppend = offline.append(event(SessionEventType.RUN_STATUS))
        assertEquals(2L, afterAppend.sequence)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, offline.health(SESSION_ID, RUN_ID).status)
    }

    @Test
    fun inexactOrUnsafeSidecarEvidenceNeverCompletes() {
        val inexact = Files.createTempDirectory("file-event-log-inexact-").toFile()
        FileBackedSessionEventLog(
            inexact,
            nowEpochMillis = { 100 },
            eventIdFactory = { "event-inexact" },
        ).append(event(SessionEventType.RUN_STARTED))
        writeCompleteSidecar(inexact, attemptedCount = 2, appendedCount = 1, failedCount = 0)
        assertNotEquals(
            SessionEventLogHealthStatus.COMPLETE,
            FileBackedSessionEventLog(inexact).health(SESSION_ID, RUN_ID).status,
        )

        val unsafe = Files.createTempDirectory("file-event-log-unsafe-code-").toFile()
        FileBackedSessionEventLog(
            unsafe,
            nowEpochMillis = { 100 },
            eventIdFactory = { "event-unsafe" },
        ).append(event(SessionEventType.RUN_STARTED))
        writeCompleteSidecar(unsafe, firstFailureCode = "../etc/passwd")
        assertNotEquals(
            SessionEventLogHealthStatus.COMPLETE,
            FileBackedSessionEventLog(unsafe).health(SESSION_ID, RUN_ID).status,
        )
        writeCompleteSidecar(unsafe, firstFailureCode = "X".repeat(65))
        assertNotEquals(
            SessionEventLogHealthStatus.COMPLETE,
            FileBackedSessionEventLog(unsafe).health(SESSION_ID, RUN_ID).status,
        )
    }

    @Test
    fun checkedSidecarExceptionDoesNotEscapeOrHealComplete() {
        val root = Files.createTempDirectory("file-event-log-checked-sidecar-").toFile()
        val live = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
            persistSidecar = { _, _ -> throw InjectedCheckedSidecarException("injected checked sidecar failure") },
        )
        val stored = live.append(event(SessionEventType.RUN_STARTED))
        assertEquals(1L, stored.sequence)
        assertEquals(SessionEventLogHealthStatus.DEGRADED, live.health(SESSION_ID, RUN_ID).status)
        assertFalse(healthFile(root).isFile)
        live.append(event(SessionEventType.RUN_STATUS))
        assertEquals(SessionEventLogHealthStatus.DEGRADED, live.health(SESSION_ID, RUN_ID).status)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, FileBackedSessionEventLog(root).health(SESSION_ID, RUN_ID).status)
    }

    @Test
    fun oversizedLosslessEventPersistsAcrossBlobAndReplay() {
        val root = Files.createTempDirectory("file-event-log-utf8-").toFile()
        val oversized = "中".repeat(1_024) + "a".repeat(8 * 1024 * 1024 + 4_096)
        val live = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
            segmentRolloverBytes = 4_096,
            blobInlineThresholdBytes = 1_024,
        )
        live.append(
            event(
                SessionEventType.RUN_STARTED,
                payload = SessionEventPayload.RunLifecycle(scene = oversized),
            ),
        )
        live.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))

        val replayed = live.replay(SESSION_ID, RUN_ID)
        assertEquals(2, replayed.size)
        assertEquals(oversized, (replayed[0].payload as SessionEventPayload.RunLifecycle).scene)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, live.health(SESSION_ID, RUN_ID).status)
        assertTrue(blobsDir(root).listFiles().orEmpty().isNotEmpty())
        assertTrue(segmentsDir(root).listFiles().orEmpty().any { it.name.endsWith(".jsonl") })
        assertFalse(File(root, "session-events/v1").exists())
    }

    @Test
    fun persistLineIoExceptionIsRecordedAndDoesNotHeal() {
        val root = Files.createTempDirectory("file-event-log-io-").toFile()
        var remainingSuccesses = 1
        val live = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
            persistLine = { file, line ->
                if (remainingSuccesses <= 0) throw IOException("injected persist IOException")
                remainingSuccesses -= 1
                appendForcedLine(file, line)
            },
        )
        live.append(event(SessionEventType.RUN_STARTED))
        assertThrows(IOException::class.java) {
            live.append(event(SessionEventType.RUN_STATUS))
        }
        val health = live.health(SESSION_ID, RUN_ID)
        assertEquals(SessionEventLogHealthStatus.DEGRADED, health.status)
        assertEquals(2, health.attemptedCount)
        assertEquals(1, health.appendedCount)
        assertEquals(1, health.failedCount)
        assertEquals(2L, health.lastAcceptedSequence)
        assertEquals(1L, health.lastPersistedSequence)
        assertEquals(health.appendedCount + health.failedCount, health.attemptedCount)
    }

    @Test
    fun restartThenAppendStaysNonCompleteForUntrustedSidecars() {
        val cases = listOf(
            RestartAppendCase("missing") { healthFile(it).delete() },
            RestartAppendCase("corrupt") { healthFile(it).writeText(INCOMPLETE_HEALTH_SIDECAR) },
            RestartAppendCase("mismatched") { writeCompleteSidecar(it, sessionId = "other-session") },
            RestartAppendCase("stale") { writeCompleteSidecar(it, lastPersistedSequence = 9L) },
            RestartAppendCase("degraded") {
                writeCompleteSidecar(it, status = SessionEventLogHealthStatus.DEGRADED, failedCount = 1, attemptedCount = 2)
            },
            RestartAppendCase("sidecar-write-gap") { healthFile(it).delete() },
        )
        cases.forEach { case ->
            val root = Files.createTempDirectory("file-event-log-restart-${case.name}-").toFile()
            val nextId = incrementalIds()
            FileBackedSessionEventLog(
                root,
                nowEpochMillis = { 100 },
                eventIdFactory = nextId,
            ).append(event(SessionEventType.RUN_STARTED))
            case.mutate(root)
            val restarted = FileBackedSessionEventLog(
                root,
                nowEpochMillis = { 200 },
                eventIdFactory = nextId,
            )
            assertNotEquals(case.name, SessionEventLogHealthStatus.COMPLETE, restarted.health(SESSION_ID, RUN_ID).status)
            val appended = restarted.append(event(SessionEventType.RUN_STATUS))
            assertEquals(case.name, 2L, appended.sequence)
            assertNotEquals(case.name, SessionEventLogHealthStatus.COMPLETE, restarted.health(SESSION_ID, RUN_ID).status)
            assertNotEquals(
                case.name,
                SessionEventLogHealthStatus.COMPLETE,
                FileBackedSessionEventLog(root).health(SESSION_ID, RUN_ID).status,
            )
        }
    }

    @Test
    fun enumeratedSessionAndRunLinksAreNotFollowedOutsideRoot() {
        val parent = Files.createTempDirectory("file-event-log-enum-link-").toFile()
        val root = File(parent, "root").apply { mkdirs() }
        val outside = File(parent, "outside").apply { mkdirs() }
        val decoySession = File(outside, "stolen-session").apply { mkdirs() }
        val decoyRun = File(decoySession, "stolen-run").apply { mkdirs() }
        val decoyStream = File(decoyRun, "000001.jsonl").apply { writeText("{stolen}\n") }
        val live = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
        )
        live.append(event(SessionEventType.RUN_STARTED))

        val streamsRoot = File(root, STREAMS_RELATIVE)
        val plantedSession = File(streamsRoot, "planted-session")
        val sessionLinked = runCatching {
            Files.createSymbolicLink(plantedSession.toPath(), decoySession.toPath())
        }.isSuccess
        Assume.assumeTrue("OS or policy does not allow enumerated session symbolic links", sessionLinked)

        live.append(event(SessionEventType.RUN_STATUS))
        assertEquals("{stolen}\n", decoyStream.readText())
        // The planted link intentionally remains; the test's own walk must not follow it.
        assertTrue(root.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile }.all { it.canonicalPath.startsWith(root.canonicalPath) })
        assertEquals(2, live.replay(SESSION_ID, RUN_ID).size)

        val plantedRun = File(runDir(root).parentFile, "planted-run")
        val runLinked = runCatching {
            Files.createSymbolicLink(plantedRun.toPath(), decoyRun.toPath())
        }.isSuccess
        Assume.assumeTrue("OS or policy does not allow enumerated run symbolic links", runLinked)
        live.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))
        assertEquals("{stolen}\n", decoyStream.readText())
        assertEquals(3, FileBackedSessionEventLog(root).query(SessionEventQuery(SESSION_ID)).size)
    }

    @Test
    fun windowsEnumeratedJunctionsAreNotFollowedOutsideRoot() {
        val parent = Files.createTempDirectory("file-event-log-enum-junction-").toFile()
        val root = File(parent, "root").apply { mkdirs() }
        val outside = File(parent, "outside").apply { mkdirs() }
        val decoySession = File(outside, "stolen-session").apply { mkdirs() }
        val decoyRun = File(decoySession, "stolen-run").apply { mkdirs() }
        val decoyStream = File(decoyRun, "000001.jsonl").apply { writeText("{stolen-junction}\n") }
        val live = FileBackedSessionEventLog(
            root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
        )
        live.append(event(SessionEventType.RUN_STARTED))

        val plantedSession = File(root, "$STREAMS_RELATIVE/planted-session")
        Assume.assumeTrue(
            "Windows enumerated session junction could not be created safely",
            tryCreateWindowsJunction(plantedSession, decoySession),
        )
        live.append(event(SessionEventType.RUN_STATUS))
        assertEquals("{stolen-junction}\n", decoyStream.readText())
        assertEquals(2, live.replay(SESSION_ID, RUN_ID).size)

        val plantedRun = File(runDir(root).parentFile, "planted-run")
        Assume.assumeTrue(
            "Windows enumerated run junction could not be created safely",
            tryCreateWindowsJunction(plantedRun, decoyRun),
        )
        live.append(event(SessionEventType.RUN_FINISHED, sourceEventSequences = listOf(1)))
        assertEquals("{stolen-junction}\n", decoyStream.readText())
        assertTrue(root.walkTopDown().filter { it.isFile }.all { it.canonicalPath.startsWith(root.canonicalPath) })
    }

    @Test
    fun tinyRolloverCreatesMultipleSegmentsWithoutDroppingEvents() {
        val root = Files.createTempDirectory("file-event-log-rollover-").toFile()
        val log = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
            segmentRolloverBytes = 1,
        )
        repeat(4) { log.append(event(SessionEventType.RUN_STATUS)) }
        assertEquals((1L..4L).toList(), log.replay(SESSION_ID, RUN_ID).map { it.sequence })
        assertTrue(segmentsDir(root).listFiles().orEmpty().count { it.name.endsWith(".jsonl") } >= 2)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_ID, RUN_ID).status)
        assertEquals((1L..4L).toList(), FileBackedSessionEventLog(root).replay(SESSION_ID, RUN_ID).map { it.sequence })
    }

    @Test
    fun closedSegmentChecksumMismatchIsolatesSiblingStream() {
        val root = Files.createTempDirectory("file-event-log-checksum-").toFile()
        val log = FileBackedSessionEventLog(
            root = root,
            nowEpochMillis = { 100 },
            eventIdFactory = incrementalIds(),
            segmentRolloverBytes = 1,
        )
        log.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_A, runId = SHARED_RUN))
        log.append(event(SessionEventType.RUN_STATUS, sessionId = SESSION_A, runId = SHARED_RUN))
        log.append(event(SessionEventType.RUN_STARTED, sessionId = SESSION_B, runId = SHARED_RUN))

        val closed = eventFile(root, SESSION_A, SHARED_RUN)
        closed.appendText(" ")
        val tampered = closed.readBytes()

        assertThrows(IllegalStateException::class.java) { log.replay(SESSION_A, SHARED_RUN) }
        assertEquals(listOf(1L), log.replay(SESSION_B, SHARED_RUN).map { it.sequence })
        val appended = log.append(event(SessionEventType.RUN_STATUS, sessionId = SESSION_B, runId = SHARED_RUN))
        assertEquals(2L, appended.sequence)
        assertEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_B, SHARED_RUN).status)
        assertNotEquals(SessionEventLogHealthStatus.COMPLETE, log.health(SESSION_A, SHARED_RUN).status)
        assertTrue(tampered.contentEquals(closed.readBytes()))
    }

}

private fun event(
    type: SessionEventType,
    sessionId: String = SESSION_ID,
    runId: String = RUN_ID,
    payload: SessionEventPayload = SessionEventPayload.Empty,
    sourceEventSequences: List<Long> = emptyList(),
): SessionEventAppend = SessionEventAppend(
    sessionId = sessionId,
    runId = runId,
    requestId = REQUEST_ID,
    type = type,
    payload = payload,
    sourceEventSequences = sourceEventSequences,
)

private fun runDir(root: File, sessionId: String = SESSION_ID, runId: String = RUN_ID): File =
    File(root, "$STREAMS_RELATIVE/${encodeFileName(sessionId)}/${encodeFileName(runId)}")

private fun eventFile(root: File, sessionId: String = SESSION_ID, runId: String = RUN_ID): File =
    File(runDir(root, sessionId, runId), "segments/000001.jsonl")

private fun manifestFile(root: File, sessionId: String = SESSION_ID, runId: String = RUN_ID): File =
    File(runDir(root, sessionId, runId), "manifest.json")

private fun segmentsDir(root: File, sessionId: String = SESSION_ID, runId: String = RUN_ID): File =
    File(runDir(root, sessionId, runId), "segments")

private fun blobsDir(root: File, sessionId: String = SESSION_ID, runId: String = RUN_ID): File =
    File(runDir(root, sessionId, runId), "blobs")

private fun healthFile(root: File, sessionId: String = SESSION_ID, runId: String = RUN_ID): File =
    File(root, "session-events/v2/health/${encodeFileName(sessionId)}/${encodeFileName(runId)}.json")

private fun incrementalIds(): () -> String {
    val next = AtomicInteger(0)
    return { "event-${next.incrementAndGet()}" }
}

private fun writeCompleteSidecar(
    root: File,
    sessionId: String = SESSION_ID,
    runId: String = RUN_ID,
    lastPersistedSequence: Long = 1L,
    appendedCount: Int = 1,
    attemptedCount: Int = appendedCount,
    failedCount: Int = 0,
    status: SessionEventLogHealthStatus = SessionEventLogHealthStatus.COMPLETE,
    firstFailureCode: String? = null,
) {
    val health = cc.noharry.agent.kernel.api.SessionEventLogHealth(
        status = status,
        attemptedCount = attemptedCount,
        appendedCount = appendedCount,
        failedCount = failedCount,
        firstFailureCode = firstFailureCode,
        lastAcceptedSequence = lastPersistedSequence,
        lastPersistedSequence = lastPersistedSequence,
        backendDurability = SessionEventLogDurability.DURABLE,
    )
    healthFile(root, SESSION_ID, RUN_ID).writeText(encodeHealth(health, sessionId, runId))
}

private fun tryCreateWindowsJunction(link: File, target: File): Boolean {
    if (!System.getProperty("os.name").orEmpty().contains("Windows", ignoreCase = true)) {
        return false
    }
    return runCatching {
        val process = ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.absolutePath, target.absolutePath)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(15, TimeUnit.SECONDS)
        finished && process.exitValue() == 0 && link.exists()
    }.getOrDefault(false)
}

private fun rewriteEventFile(
    root: File,
    sessionId: String = SESSION_ID,
    runId: String = RUN_ID,
    transform: (String) -> String,
) {
    val file = eventFile(root, sessionId, runId)
    val before = file.readText()
    val after = transform(before)
    check(after != before) { "typed stream tamper did not change ${file.path}" }
    file.writeText(after)
}

private fun quotedCompleteSidecar(
    sessionId: String = SESSION_ID,
    runId: String = RUN_ID,
): String =
    """{"schemaVersion":1,"status":"COMPLETE","attemptedCount":1,"appendedCount":1,"failedCount":0,"lastPersistedSequence":1,"backendDurability":"DURABLE","recorderCoverageVersion":1,"sessionId":"$sessionId","runId":"$runId"}"""

private class InjectedCheckedSidecarException(message: String) : Exception(message)

private data class TamperCase(
    val name: String,
    val mutate: (File) -> Unit,
)

private data class RestartAppendCase(
    val name: String,
    val mutate: (File) -> Unit,
)

private data class TypedStreamTamper(
    val name: String,
    val sessionId: String = SESSION_ID,
    val runId: String = RUN_ID,
    val seed: (FileBackedSessionEventLog) -> Unit,
    val mutate: (File) -> Unit,
)

private const val NUMERIC_SESSION_ID = "1"
private const val NUMERIC_RUN_ID = "2"
private const val SESSION_ID = "session-1"
private const val SESSION_A = "session-a"
private const val SESSION_B = "session-b"
private const val RUN_ID = "run-1"
private const val SHARED_RUN = "shared-run"
private const val REQUEST_ID = "request-1"
private const val DUPLICATE_EVENT_ID = "event-shared"
private const val TRAVERSAL_SESSION = "../../../etc"
private const val TRAVERSAL_RUN = "..\\windows"
private const val STREAMS_RELATIVE = "session-events/v2/streams"
private const val WORKER_COUNT = 4
private const val EVENT_COUNT = 24
private const val TRUNCATED_EVENT_LINE = """{"schemaVersion":1"""
private const val INCOMPLETE_HEALTH_SIDECAR = """{"schemaVersion":1,"status":"COMPLETE"}"""
