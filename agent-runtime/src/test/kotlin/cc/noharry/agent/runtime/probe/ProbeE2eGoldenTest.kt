@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.probe


import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import cc.noharry.agent.runtime.FakeModelClient
import cc.noharry.agent.runtime.LoopMcpProvider
import cc.noharry.agent.runtime.mcpTool
import cc.noharry.agent.runtime.run.ProbeSessionLoopProbeSink
import cc.noharry.agent.runtime.workspace.VirtualFileMaterializer
import cc.noharry.agent.runtime.workspace.VirtualWorkspace
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden cross-language contract: real Kotlin emission (NativeToolLoop → ProbeRunAttachment
 * → file sink/completeness/run-meta) must byte-match the committed artifacts under
 * tools/fixtures/kotlin-e2e. The Python analyzer e2e consumes exactly those files, so the
 * Runtime→Probe→Report chain is proven on real Kotlin output, not handwritten fixtures.
 *
 * Case-bound evidence requires run-meta caseBinding.casePackId + promptSha256 matching the
 * pack under tools/fixtures/bench-cases/kotlin_e2e.v1.json (not analyzer-side rebinding).
 *
 * Regenerate after an intentional emitter change with (env reaches a fresh test JVM):
 *   PROBE_GOLDEN_UPDATE=1 ./gradlew --no-daemon :agent-runtime:testDebugUnitTest --tests '*ProbeE2eGoldenTest'
 */
class ProbeE2eGoldenTest {

    @Test
    fun kotlinEmissionMatchesCommittedGoldenArtifacts() = runBlocking {
        val root = Files.createTempDirectory("probe-golden").toFile()
        try {
            val activationStore = ToolActivationStore()
            val (loop, probeAttachment) = goldenLoop(root, activationStore)
            val outcome = loop.run(goldenRequest(), GOLDEN_RUN_ID) {}
            assertTrue(outcome is NativeLoopOutcome.Terminal)
            assertEquals(
                "COMPLETED",
                (outcome as NativeLoopOutcome.Terminal).result.status.name,
            )
            // Probe terminal drain/persist is owned by DefaultAgentRuntime's coordinator in
            // production; this test exercises NativeToolLoop directly, so finalize explicitly.
            probeAttachment.finishAndPersist("COMPLETED")
            val runDir = probeRunDirectory(root, GOLDEN_RUN_ID)
            val metaText = File(runDir, RUN_META_JSON).readText()
            assertTrue(
                "run-meta must bind kotlin_e2e.v1 for case-bound e2e",
                metaText.contains("\"casePackId\":\"kotlin_e2e.v1\""),
            )
            assertTrue(
                "run-meta must include promptSha256 for non-none case",
                metaText.contains("\"promptSha256\""),
            )
            assertFalse(
                "toolset must not be empty (A6 fingerprint)",
                metaText.contains("\"toolset\":[]"),
            )
            assertTrue(metaText.contains("read_file"))
            assertTrue(metaText.contains("mcp__default__search_docs"))
            assertMatchesGolden(runDir)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun goldenLoop(
        root: File,
        activationStore: ToolActivationStore,
    ): Pair<NativeToolLoop, ProbeRunAttachment> {
        val registry = ToolProviderRegistry(
            listOf(LoopMcpProvider(exposedTools = listOf(mcpTool("search_docs")))),
        )
        val probeAttachment = ProbeRunAttachment.open(
            reliabilityRoot = root,
            runId = GOLDEN_RUN_ID,
            sessionId = GOLDEN_SESSION_ID,
            requestId = GOLDEN_RUN_ID,
            packs = listOf(benchCorePack()),
            // Finish-time snapshot: progressive MCP activation is included.
            runMetaBuilder = { goldenRunMeta(activationStore) },
            clock = ScriptProbeClock(),
            casePackId = GOLDEN_CASE_PACK_ID,
        )
        val loop = NativeToolLoop(
            modelClient = goldenModelClient(),
            mcpRegistry = registry,
            workspace = VirtualWorkspace(
                fileSystem = VfsFileSystem.singleFileRoot(
                    Files.createTempDirectory("probe-golden-vfs-").toFile(),
                ),
                materializer = VirtualFileMaterializer(
                    mcpRegistry = registry,
                    activationStore = activationStore,
                ),
                mcpRegistry = registry,
                activationStore = activationStore,
                runId = GOLDEN_RUN_ID,
            ),
            activationStore = activationStore,
            nowEpochMillis = { GOLDEN_EPOCH_MILLIS },
            probeSink = ProbeSessionLoopProbeSink(probeAttachment.session),
        )
        return loop to probeAttachment
    }

    private fun assertMatchesGolden(runDir: File) {
        val produced = listOf(PROBES_JSONL, PROBE_COMPLETENESS_JSON, RUN_META_JSON)
        if (System.getenv(GOLDEN_UPDATE_ENV) == "1") {
            goldenDir().mkdirs()
            produced.forEach { name ->
                File(runDir, name).copyTo(File(goldenDir(), name), overwrite = true)
            }
            return
        }
        produced.forEach { name ->
            val golden = File(goldenDir(), name)
            assertTrue(
                "golden missing: ${golden.absolutePath}; regenerate with $GOLDEN_UPDATE_ENV=1",
                golden.isFile,
            )
            assertEquals("golden drift in $name", golden.readText(), File(runDir, name).readText())
        }
    }

    private fun goldenModelClient(): FakeModelClient = FakeModelClient(
        eventsByRound = listOf(
            // Progressive MCP flow: read the schema (activates the tool), then call it.
            listOf(
                ModelEvent.ToolCall(
                    "schema-1",
                    "read_file",
                    """{"path":"/mcp/tools/mcp__default__search_docs/schema.json"}""",
                ),
                ModelEvent.Completed,
            ),
            listOf(
                ModelEvent.ToolCall("call-1", "mcp__default__search_docs", """{"q":"weather"}"""),
                ModelEvent.Completed,
            ),
            listOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ),
        ),
    )

    private fun goldenRequest(): AgentRequest = AgentRequest(
        requestId = GOLDEN_RUN_ID,
        sessionId = GOLDEN_SESSION_ID,
        input = GOLDEN_PROMPT,
    )

    private fun goldenRunMeta(activationStore: ToolActivationStore) = buildRunMeta(
        runId = GOLDEN_RUN_ID,
        sessionId = GOLDEN_SESSION_ID,
        armId = "default",
        buildId = "test-build",
        startedAtEpochMillis = GOLDEN_EPOCH_MILLIS,
        provider = "fixture",
        protocol = "native",
        model = "m1",
        baseUrlHost = "local",
        isolation = IsolationDeclaration("fresh", "cleared", "cold"),
        caseBinding = CaseBinding(
            casePackId = GOLDEN_CASE_PACK_ID,
            promptSha256 = promptSha256Hex(GOLDEN_PROMPT),
        ),
        enabledProbePacks = listOf("bench_core"),
        toolset = snapshotAdvertisedToolset(
            runId = GOLDEN_RUN_ID,
            activationStore = activationStore,
            nativeWebFetchEnabled = true,
        ),
    )

    /**
     * Deterministic clock: strictly increasing elapsed nanos and progressive epoch millis
     * so wall-clock span is non-zero (and nano fallback remains correct if epoch collapses).
     */
    private class ScriptProbeClock : ProbeClock {
        private val tick = AtomicLong(0L)

        override fun elapsedNanos(): Long = tick.incrementAndGet() * 1_000_000L

        override fun epochMillis(): Long = GOLDEN_EPOCH_MILLIS + tick.get()
    }

    private fun goldenDir(): File =
        File(System.getProperty("user.dir"), "../tools/fixtures/kotlin-e2e").canonicalFile

    private companion object {
        const val GOLDEN_RUN_ID = "run-kt-e2e"
        const val GOLDEN_SESSION_ID = "session-1"
        const val GOLDEN_EPOCH_MILLIS = 1_700_000_000_000L
        const val GOLDEN_PROMPT = "search docs then finish"
        const val GOLDEN_CASE_PACK_ID = "kotlin_e2e.v1"
        const val GOLDEN_UPDATE_ENV = "PROBE_GOLDEN_UPDATE"
    }
}
