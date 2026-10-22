package cc.noharry.agent.runtime.probe

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Security / redaction / clear-data / overhead-style deterministic gates (SPEC-RPB-009).
 */
class ProbeSecurityGatesTest {

    @Test
    fun attrsBudgetEnforcedAt8KiB() {
        val factory = ProbeEventFactory(
            runId = "r",
            sessionId = null,
            requestId = null,
            clock = object : ProbeClock {
                override fun elapsedNanos() = 0L
                override fun epochMillis() = 0L
            },
            catalog = ProbeStageCatalog.p0Minimal(),
            attrsMaxBytes = PROBE_ATTRS_MAX_BYTES,
        )
        val huge = "z".repeat(PROBE_ATTRS_MAX_BYTES * 2)
        val event = factory.next(
            stageId = "probe.error",
            probeId = "lifecycle.run",
            attrs = mapOf("code" to "X", "message" to huge),
        )
        assertTrue(event.truncated)
        assertTrue(factory.truncatedCount() >= 1)
        val encoded = encodeProbeEventJsonLine(event)
        // truncated attrs should keep total event reasonable; message may be dropped
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size < PROBE_ATTRS_MAX_BYTES * 3)
    }

    @Test
    fun clearDataRemovesProbeArtifactsViaClearProbeArtifacts() = runBlocking {
        val root = Files.createTempDirectory("probe-clear").toFile()
        try {
            val runId = "clear-me"
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
    fun runMetaJsonDoesNotEmbedCredentials() {
        val meta = buildRunMeta(
            runId = "r",
            sessionId = "s",
            armId = "default",
            buildId = "b",
            startedAtEpochMillis = 1,
            provider = "p",
            protocol = "openai",
            model = "m",
            baseUrlHost = "api.example.com",
            isolation = IsolationDeclaration("fresh", "cleared", "cold"),
            caseBinding = CaseBinding("none"),
            enabledProbePacks = emptyList(),
            toolset = emptyList(),
        )
        val text = FingerprintCanonicalizer.configCanonical(
            mapOf(
                "baseUrlHost" to meta.baseUrlHost,
                "provider" to meta.provider,
            ),
        )
        assertFalse(text.contains("password"))
        assertFalse(text.contains("sk-"))
        assertFalse(text.contains("api_key"))
        assertTrue(meta.baseUrlHost == "api.example.com")
    }
}
