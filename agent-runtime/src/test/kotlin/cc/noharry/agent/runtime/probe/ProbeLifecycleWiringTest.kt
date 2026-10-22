package cc.noharry.agent.runtime.probe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import cc.noharry.agent.kernel.core.failed

/**
 * Real file-backed lifecycle path: ProbeSession → probes.jsonl + completeness + run-meta.
 * Cross-language analyzer can consume these artifacts (Python e2e separately).
 */
class ProbeLifecycleWiringTest {

    @Test
    fun fileBackedSessionWritesProbesCompletenessAndRunMeta() = runBlocking {
        val root = Files.createTempDirectory("probe-lifecycle").toFile()
        try {
            val runId = "run-lifecycle-1"
            val attachment = openLifecycleAttachment(root, runId)
            val session = attachment.session
            assertTrue(session.emitRunStart())
            assertTrue(session.emitRequestConfig(controlFingerprint = "c", configFingerprint = "f"))
            assertTrue(session.emitSessionIsolate("fresh", "cleared", "cold"))
            assertTrue(session.emitCaseBound(session.casePackId))
            assertTrue(session.emitModelRoundStart(0))
            assertTrue(session.emitModelRoundEnd(0, "COMPLETED"))
            assertTrue(session.emitToolPrepare("mcp__x__search", "c1"))
            assertTrue(session.emitToolExecuteStart("mcp__x__search", "c1"))
            assertTrue(session.emitToolExecuteEnd("mcp__x__search", "c1", ok = true))
            assertTrue(session.emitSearchHosted("hosted"))
            assertTrue(session.emitEmptyCompletion("none"))
            val completeness = attachment.finishAndPersist("COMPLETED")
            assertTrue(completeness.enqueued > 0)
            assertEquals(completeness.enqueued, completeness.written)
            assertTrue(completeness.complete)

            val dir = probeRunDirectory(root, runId)
            val probes = File(dir, PROBES_JSONL)
            assertTrue(probes.isFile)
            assertTrue(File(dir, PROBE_COMPLETENESS_JSON).isFile)
            val metaFile = File(dir, RUN_META_JSON)
            assertTrue(metaFile.isFile)
            val lines = probes.readLines().filter { it.isNotBlank() }
            assertTrue(lines.size >= 8)
            assertTrue(lines.any { it.contains("run.start") })
            assertTrue(lines.any { it.contains("\"stageId\":\"case.bound\"") })
            assertTrue(lines.any { it.contains("\"stageId\":\"probe.completeness\"") })
            assertTrue(lines.any { it.contains("model.round.start") })
            assertTrue(lines.any { it.contains("tool.execute.end") })
            assertTrue(metaFile.readText().contains("\"runId\":\"$runId\""))
            assertTrue(metaFile.readText().contains("configFingerprint"))
            assertTrue(File(dir, PROBE_COMPLETENESS_JSON).readText().contains("\"complete\":true"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun productionCaseFamilyEmitsFromRealBindingAndPrimaryDrainCounters() = runBlocking {
        val root = Files.createTempDirectory("probe-case-family").toFile()
        try {
            val runId = "run-case-family"
            val attachment = openCaseBoundAttachment(root, runId, casePackId = "kotlin_e2e.v1")
            assertEquals("kotlin_e2e.v1", attachment.session.casePackId)
            // Start family: case.bound from real binding (not synthetic finish backfill).
            assertTrue(attachment.session.emitRunStart())
            assertTrue(attachment.session.emitCaseBound(attachment.session.casePackId))
            assertTrue(attachment.session.emitRunTerminal("COMPLETED"))
            val summary = attachment.finishAndPersist("COMPLETED")
            assertCaseFamilyArtifacts(root, runId, summary, expectedCasePackId = "kotlin_e2e.v1")
        } finally {
            root.deleteRecursively()
        }
    }

    private fun openCaseBoundAttachment(
        root: File,
        runId: String,
        casePackId: String,
    ): ProbeRunAttachment =
        ProbeRunAttachment.open(
            reliabilityRoot = root,
            runId = runId,
            sessionId = "sess-case",
            requestId = runId,
            packs = listOf(benchCorePack()),
            casePackId = casePackId,
            runMetaBuilder = {
                buildRunMeta(
                    runId = runId,
                    sessionId = "sess-case",
                    armId = "default",
                    buildId = "test-build",
                    startedAtEpochMillis = 1_700_000_000_000L,
                    provider = "fixture",
                    protocol = "native",
                    model = "m1",
                    baseUrlHost = "local",
                    isolation = IsolationDeclaration("fresh", "cleared", "cold"),
                    caseBinding = CaseBinding(
                        casePackId = casePackId,
                        promptSha256 = "a".repeat(64),
                    ),
                    enabledProbePacks = listOf("bench_core"),
                    toolset = emptyList(),
                )
            },
        )

    private fun assertCaseFamilyArtifacts(
        root: File,
        runId: String,
        summary: ProbeCompleteness,
        expectedCasePackId: String,
    ) {
        assertTrue(summary.complete)
        val lines = File(probeRunDirectory(root, runId), PROBES_JSONL).readLines()
        val caseBound = lines.first { it.contains("\"stageId\":\"case.bound\"") }
        assertTrue(caseBound.contains("\"casePackId\":\"$expectedCasePackId\""))
        val completenessEvt = lines.first { it.contains("\"stageId\":\"probe.completeness\"") }
        // enqueued/written in the event are primary-drain totals (before the event itself).
        assertTrue(completenessEvt.contains("\"complete\":true"))
        assertTrue(completenessEvt.contains("\"enqueued\""))
        assertTrue(completenessEvt.contains("\"written\""))
        // Side-car is independent truth and includes the post-drain event(s).
        val side = File(probeRunDirectory(root, runId), PROBE_COMPLETENESS_JSON).readText()
        assertTrue(side.contains("\"complete\":true"))
        assertTrue(summary.enqueued >= summary.written)
    }

    @Test
    fun unknownStageEmitsProbeErrorWithoutFailingControl() = runBlocking {
        val sink = InMemoryProbeEventSink(capacity = 64)
        val session = ProbeSession.create(
            runId = "run-unknown-stage",
            packs = listOf(benchCorePack()),
            eventSink = sink,
        )
        // Unknown stage is accepted as evidence but marks incomplete and emits probe.error.
        session.emit("totally.unknown.stage", ProbeProducerIds.LIFECYCLE_RUN)
        val summary = session.finish(drainTimeoutMillis = 1_000L)
        assertFalse(summary.complete)
        assertTrue(summary.error >= 1L)
        val stages = sink.lines.map { it.stageId }
        assertTrue(stages.contains("probe.error"))
        assertTrue(stages.contains("probe.completeness"))
        val err = sink.lines.first { it.stageId == "probe.error" }
        assertEquals("UNKNOWN_STAGE", (err.attrs["code"] as? ProbeAttrValue.Str)?.value)
    }

    @Test
    fun sinkIoFailureStillWritesIndependentCompletenessSideCar() = runBlocking {
        val root = Files.createTempDirectory("probe-sink-fail").toFile()
        try {
            val runId = "run-sink-fail"
            val dir = probeRunDirectory(root, runId)
            dir.mkdirs()
            val forceIo = java.util.concurrent.atomic.AtomicBoolean(true)
            val eventSink = FileProbeEventSink(
                eventFile = File(dir, PROBES_JSONL),
                capacity = 64,
                forceIoFailure = forceIo,
            )
            val completenessStore = FileCompletenessStore(File(dir, PROBE_COMPLETENESS_JSON))
            val session = ProbeSession.create(
                runId = runId,
                packs = listOf(benchCorePack()),
                eventSink = eventSink,
                completenessStore = completenessStore,
                casePackId = "none",
            )
            assertTrue(session.emitRunStart())
            assertTrue(session.emitCaseBound("none"))
            val summary = session.finish(drainTimeoutMillis = 1_000L)
            assertFalse(summary.complete)
            assertTrue(summary.sinkIoFailed)
            // Completeness side-car must still land independently of event sink IO.
            val sideFile = File(dir, PROBE_COMPLETENESS_JSON)
            assertTrue(sideFile.isFile)
            assertTrue(sideFile.readText().contains("\"sinkIoFailed\":true"))
            assertTrue(sideFile.readText().contains("\"complete\":false"))
            // No probe.completeness event when sink already failed (redundant path skipped).
            val probes = File(dir, PROBES_JSONL)
            if (probes.isFile) {
                assertFalse(probes.readText().contains("probe.completeness"))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun openLifecycleAttachment(root: File, runId: String): ProbeRunAttachment =
        ProbeRunAttachment.open(
            reliabilityRoot = root,
            runId = runId,
            sessionId = "sess-1",
            requestId = runId,
            packs = listOf(benchCorePack()),
            casePackId = "none",
            runMetaBuilder = {
                buildRunMeta(
                    runId = runId,
                    sessionId = "sess-1",
                    armId = "default",
                    buildId = "test-build",
                    startedAtEpochMillis = 1_700_000_000_000L,
                    provider = "fixture",
                    protocol = "native",
                    model = "m1",
                    baseUrlHost = "local",
                    isolation = IsolationDeclaration("fresh", "cleared", "cold"),
                    caseBinding = CaseBinding("none"),
                    enabledProbePacks = listOf("bench_core"),
                    toolset = emptyList(),
                )
            },
        )

    @Test
    fun clearProbeArtifactsRemovesEvidence() = runBlocking {
        val root = Files.createTempDirectory("probe-clear-real").toFile()
        try {
            val runId = "run-clear"
            val attachment = ProbeRunAttachment.open(
                reliabilityRoot = root,
                runId = runId,
                sessionId = "s",
                requestId = runId,
                packs = listOf(benchCorePack()),
                runMetaBuilder = {
                    buildRunMeta(
                        runId = runId,
                        sessionId = "s",
                        armId = "default",
                        buildId = "b",
                        startedAtEpochMillis = 1L,
                        provider = "p",
                        protocol = "native",
                        model = "m",
                        baseUrlHost = "h",
                        isolation = IsolationDeclaration("fresh", "cleared", "cold"),
                        caseBinding = CaseBinding("none"),
                        enabledProbePacks = listOf("bench_core"),
                        toolset = emptyList(),
                    )
                },
            )
            attachment.session.emitRunStart()
            attachment.finishAndPersist("COMPLETED")
            assertTrue(listProbeArtifactFiles(root, runId).isNotEmpty())
            assertTrue(clearProbeArtifacts(root, runId))
            assertTrue(listProbeArtifactFiles(root, runId).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun valueRedactionStripsSecretsFromProbeErrorAndPath() = runBlocking {
        val sink = InMemoryProbeEventSink(capacity = 32)
        val session = ProbeSession.create(
            runId = "redact",
            packs = listOf(benchCorePack()),
            eventSink = sink,
        )
        session.emitProbeError("X", "token=${FAKE_SECRET_KEY} leak")
        session.emitSearchHosted("https://example.com/search?q=secret&token=abc")
        session.finish(drainTimeoutMillis = 1_000L)
        val joined = sink.lines.joinToString("\n") { encodeProbeEventJsonLine(it) }
        assertFalse(joined.contains(FAKE_SECRET_KEY))
        assertTrue(joined.contains(ProbeValueRedactor.REDACTED))
        assertFalse(joined.contains("token=abc"))
    }
}

/** A made-up key the redaction must catch; built at run time so the source never holds a key-shaped literal. */
private val FAKE_SECRET_KEY = "sk-" + "abcdefghijklmnopqrstuvwxyz0123"
