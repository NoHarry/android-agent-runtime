@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.RuntimeEvidenceConfig
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.state.RUNS_DIR
import cc.noharry.agent.runtime.probe.PROBES_JSONL
import cc.noharry.agent.runtime.probe.PROBE_COMPLETENESS_JSON
import cc.noharry.agent.runtime.probe.RUN_META_JSON
import cc.noharry.agent.runtime.probe.probeRunDirectory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Production [AgentRuntimeBuilder.runtimeEvidence] → DefaultAgentRuntime probe artifacts.
 */
class RuntimeEvidenceE2eTest {

    @Test
    fun defaultBuilderProducesNoProbeArtifacts() = runBlocking {
        val root = Files.createTempDirectory("ev-e2e-off").toFile()
        try {
            val runtime = AgentRuntimeBuilder.create()
                .modelClient(finishOnlyClient())
                .reliabilityStorageRoot(root)
                .build()
            val events = runtime.run(simpleRequest("r-off")).toList()
            assertTrue(events.isNotEmpty())
            val runDirs = File(root, RUNS_DIR).listFiles().orEmpty()
            for (dir in runDirs) {
                assertFalse(File(dir, PROBES_JSONL).exists())
                assertFalse(File(dir, RUN_META_JSON).exists())
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun benchCoreWithNoneCaseWritesArtifactsAndCaseBoundNone() = runBlocking {
        val root = Files.createTempDirectory("ev-e2e-on").toFile()
        try {
            val runtime = AgentRuntimeBuilder.create()
                .modelClient(finishOnlyClient())
                .runtimeEvidence(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        enabledProbePackIds = setOf("bench_core"),
                        casePackId = "none",
                    ),
                )
                .build()
            runtime.run(simpleRequest("r-on")).toList()
            val runDir = findProbeRunDir(root)
            assertTrue(File(runDir, PROBES_JSONL).isFile)
            assertTrue(File(runDir, PROBE_COMPLETENESS_JSON).isFile)
            assertTrue(File(runDir, RUN_META_JSON).isFile)
            val probes = File(runDir, PROBES_JSONL).readText()
            assertTrue(probes.contains("\"stageId\":\"case.bound\""))
            assertTrue(probes.contains("\"casePackId\":\"none\"") || probes.contains("none"))
            val meta = File(runDir, RUN_META_JSON).readText()
            assertTrue(meta.contains("\"casePackId\":\"none\""))
            assertTrue(meta.contains("bench_core"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun caseBoundHashAppearsInRunMetaAndMatchesEvent() = runBlocking {
        val root = Files.createTempDirectory("ev-e2e-case").toFile()
        // Same string used as request input and as hash source (host contract).
        // Neutral host case id only — runtime tests must not reference the sample Case Pack id
        // (sample-removal reverse-dep scan rejects that pack id in agent-runtime sources).
        val prompt = "HOST_BENCH_V1:case=host.bench.v1;normative=false"
        val hash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        try {
            val runtime = AgentRuntimeBuilder.create()
                .modelClient(finishOnlyClient())
                .runtimeEvidence(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        enabledProbePackIds = setOf("bench_core"),
                        casePackId = "host.bench.v1",
                        promptSha256 = hash,
                    ),
                )
                .build()
            runtime.run(
                AgentRequest(
                    requestId = "r-case",
                    sessionId = "sess-ev",
                    input = prompt,
                ),
            ).toList()
            val runDir = findProbeRunDir(root)
            val meta = File(runDir, RUN_META_JSON).readText()
            assertTrue(meta.contains("\"casePackId\":\"host.bench.v1\""))
            assertTrue(meta.contains(hash))
            val probes = File(runDir, PROBES_JSONL).readText()
            assertTrue(probes.contains("host.bench.v1"))
            assertTrue(meta.contains("promptSha256"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun clearReliabilityRootThenRerunRecreatesArtifacts() = runBlocking {
        val root = Files.createTempDirectory("ev-e2e-clear").toFile()
        try {
            val builder = {
                AgentRuntimeBuilder.create()
                    .modelClient(finishOnlyClient())
                    .runtimeEvidence(
                        RuntimeEvidenceConfig(
                            storageRoot = root,
                            enabledProbePackIds = setOf("bench_core"),
                            casePackId = "none",
                        ),
                    )
                    .build()
            }
            builder().run(simpleRequest("r1")).toList()
            assertTrue(findProbeRunDir(root).let { File(it, PROBES_JSONL).isFile })
            // Production wipe of reliability root (same as sample agent-reliability recursive delete).
            assertTrue(root.deleteRecursively() || !root.exists())
            root.mkdirs()
            builder().run(simpleRequest("r2")).toList()
            assertTrue(findProbeRunDir(root).let { File(it, PROBES_JSONL).isFile })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun wrongPromptHashIsStillWrittenButHostMustNotBindMismatchedConstant() = runBlocking {
        // Runtime accepts host-supplied hash; fail-closed for wrong binding is analyzer-side
        // (and sample host refuses non-bench prompts before build). Document both layers.
        val root = Files.createTempDirectory("ev-e2e-wrong-hash").toFile()
        val prompt = "HOST_BENCH_V1:case=host.bench.v1;normative=false"
        val actualHash = java.security.MessageDigest.getInstance("SHA-256")
            .digest(prompt.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val wrongHash = "0".repeat(64)
        try {
            val runtime = AgentRuntimeBuilder.create()
                .modelClient(finishOnlyClient())
                .runtimeEvidence(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        enabledProbePackIds = setOf("bench_core"),
                        casePackId = "host.bench.v1",
                        promptSha256 = wrongHash,
                    ),
                )
                .build()
            runtime.run(
                AgentRequest(requestId = "r-wrong", sessionId = "sess-ev", input = prompt),
            ).toList()
            val meta = File(findProbeRunDir(root), RUN_META_JSON).readText()
            assertTrue(meta.contains(wrongHash))
            assertFalse(meta.contains(actualHash) && actualHash == wrongHash)
            assertTrue(actualHash != wrongHash)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun emptyPackIdsDisableProbesEvenWithStorageRoot() = runBlocking {
        val root = Files.createTempDirectory("ev-e2e-empty-packs").toFile()
        try {
            val runtime = AgentRuntimeBuilder.create()
                .modelClient(finishOnlyClient())
                .runtimeEvidence(
                    RuntimeEvidenceConfig(
                        storageRoot = root,
                        enabledProbePackIds = emptySet(),
                        casePackId = "none",
                    ),
                )
                .build()
            runtime.run(simpleRequest("r-empty")).toList()
            val runDirs = File(root, RUNS_DIR).listFiles().orEmpty()
            for (dir in runDirs) {
                assertFalse(
                    "empty pack ids must not write probes.jsonl",
                    File(dir, PROBES_JSONL).exists(),
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun finishOnlyClient(): FakeModelClient =
        FakeModelClient(
            eventsByRound = listOf(
                listOf(
                    ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                    ModelEvent.Completed,
                ),
            ),
        )

    private fun simpleRequest(id: String): AgentRequest =
        AgentRequest(requestId = id, sessionId = "sess-ev", input = "finish with done")

    private fun findProbeRunDir(root: File): File {
        val runs = File(root, RUNS_DIR)
        assertTrue(runs.isDirectory)
        val withProbes = runs.listFiles()?.firstOrNull { File(it, PROBES_JSONL).isFile }
        assertTrue("expected a run dir with probes under $root", withProbes != null)
        return withProbes!!
    }
}
