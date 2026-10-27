@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.probe


import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.core.context.*
import cc.noharry.agent.kernel.core.context.SimpleBootstrapPromptResolver
import cc.noharry.agent.kernel.core.loop.*
import cc.noharry.agent.kernel.core.memory.*
import cc.noharry.agent.kernel.core.plugin.*
import cc.noharry.agent.kernel.core.state.*
import cc.noharry.agent.kernel.core.tools.*
import cc.noharry.agent.kernel.core.trace.*
import cc.noharry.agent.runtime.FakeModelClient
import cc.noharry.agent.runtime.LoopMcpProvider
import cc.noharry.agent.runtime.agentRequest
import cc.noharry.agent.runtime.mcpTool
import cc.noharry.agent.runtime.run.NativeLoopFactory
import cc.noharry.agent.runtime.run.RuntimeHookChain
import cc.noharry.agent.vfs.VfsFileSystem
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Q-RPB-009 / SPEC-RPB-009 deterministic overhead main gate.
 *
 * Same fake model/tool workload through production [NativeLoopFactory] /
 * [NativeToolLoop], comparing probes disabled vs `bench_core`. Median of 5
 * measured runs; wall time via [System.nanoTime] (never a script clock).
 *
 * Pass when:
 *   enabledMedianMs - disabledMedianMs
 *     <= max(disabledMedianMs * 0.02, 200ms)
 *
 * Pair order is deterministically alternating so JVM warm-up is not
 * attributed to a fixed disabled→enabled bias:
 *   pair even: disabled then enabled; pair odd: enabled then disabled.
 *
 * VFS roots live under each sample root so outer deleteRecursively cleans both.
 */
class ProbeOverheadGateTest {

    @Test
    fun benchCoreInstrumentationOverheadWithinDeterministicBudget() = runBlocking {
        val runSeq = AtomicInteger(0)
        val trackedRoots = ArrayList<File>()
        // Discarded warm-up pair (fixed order; not counted).
        measureSample(probesEnabled = false, runSeq = runSeq, trackedRoots = trackedRoots)
        measureSample(probesEnabled = true, runSeq = runSeq, trackedRoots = trackedRoots)

        val disabledSamples = ArrayList<Double>(MEASURED_RUNS)
        val enabledSamples = ArrayList<Double>(MEASURED_RUNS)
        collectAlternatingPairs(runSeq, trackedRoots, disabledSamples, enabledSamples)
        assertAllTrackedRootsDeleted(trackedRoots)
        assertBudget(disabledSamples, enabledSamples)
    }

    private suspend fun collectAlternatingPairs(
        runSeq: AtomicInteger,
        trackedRoots: MutableList<File>,
        disabledSamples: MutableList<Double>,
        enabledSamples: MutableList<Double>,
    ) {
        // Deterministic alternation: even pair disabled→enabled, odd enabled→disabled.
        for (pairIndex in 0 until MEASURED_RUNS) {
            val disabledFirst = pairIndex % 2 == 0
            if (disabledFirst) {
                disabledSamples += measureSample(false, runSeq, trackedRoots)
                enabledSamples += measureSample(true, runSeq, trackedRoots)
            } else {
                enabledSamples += measureSample(true, runSeq, trackedRoots)
                disabledSamples += measureSample(false, runSeq, trackedRoots)
            }
        }
    }

    private fun assertBudget(disabledSamples: List<Double>, enabledSamples: List<Double>) {
        val disabledMedian = medianMs(disabledSamples)
        val enabledMedian = medianMs(enabledSamples)
        val deltaMs = enabledMedian - disabledMedian
        val relativeDelta =
            if (disabledMedian > 0.0) deltaMs / disabledMedian else Double.POSITIVE_INFINITY
        val thresholdMs = maxOf(disabledMedian * RELATIVE_BUDGET, ABSOLUTE_BUDGET_MS)
        val report = buildString {
            appendLine("deterministic overhead gate (Q-RPB-009)")
            appendLine("pairOrder=even:disabled->enabled odd:enabled->disabled")
            appendLine("disabled samplesMs=$disabledSamples medianMs=$disabledMedian")
            appendLine("enabled  samplesMs=$enabledSamples medianMs=$enabledMedian")
            appendLine("deltaMs=$deltaMs relativeDelta=$relativeDelta")
            appendLine("allowedThresholdMs=$thresholdMs (max(disabled*0.02, 200ms))")
        }
        if (deltaMs > thresholdMs) {
            fail(report)
        }
        println(report)
    }

    private fun assertAllTrackedRootsDeleted(trackedRoots: List<File>) {
        val lingering = trackedRoots.filter { it.exists() }
        assertTrue(
            "sample/VFS roots must be deleted after each inner run; lingering=$lingering",
            lingering.isEmpty(),
        )
    }

    /**
     * One measured sample: [INNER_LOOPS] identical workloads on independent sample roots.
     * VFS is created under each sample root so cleanup is symmetric and complete.
     */
    private suspend fun measureSample(
        probesEnabled: Boolean,
        runSeq: AtomicInteger,
        trackedRoots: MutableList<File>,
    ): Double {
        var totalNanos = 0L
        repeat(INNER_LOOPS) {
            val runId = "oh-${if (probesEnabled) "on" else "off"}-${runSeq.incrementAndGet()}"
            val root = Files.createTempDirectory("probe-overhead-$runId").toFile()
            trackedRoots += root
            try {
                val started = System.nanoTime()
                val outcome = runWorkload(root, runId, probesEnabled)
                totalNanos += System.nanoTime() - started
                assertTrue(outcome is NativeLoopOutcome.Terminal)
                assertArtifacts(root, runId, probesEnabled)
            } finally {
                root.deleteRecursively()
            }
            assertFalse("sample root must not linger: ${root.absolutePath}", root.exists())
        }
        return totalNanos / 1_000_000.0
    }

    private suspend fun runWorkload(
        root: File,
        runId: String,
        probesEnabled: Boolean,
    ): NativeLoopOutcome {
        // VFS under sample root: outer root.deleteRecursively() cleans both sides equally.
        val vfsRoot = File(root, "vfs").also { it.mkdirs() }
        val vfs = VfsFileSystem.singleFileRoot(vfsRoot)
        val factory = overheadFactory(root, probesEnabled)
        val outcome = factory.run(agentRequest().copy(requestId = runId), runId, workspaceFileSystem = vfs) {}
        // Probe terminal drain is owned by the runtime coordinator; finalize explicitly here.
        if (outcome is NativeLoopOutcome.Terminal) {
            factory.finishProbeRun(runId, outcome.result.status.name)
        }
        return outcome
    }

    private fun overheadFactory(root: File, probesEnabled: Boolean): NativeLoopFactory =
        NativeLoopFactory(
            modelClient = overheadModelClient(),
            mcpRegistry = ToolProviderRegistry(
                listOf(LoopMcpProvider(exposedTools = listOf(mcpTool("search_docs")))),
            ),
            materializerSchemaCache = null,
            traceRedactor = DefaultTraceRedactor,
            hookChain = RuntimeHookChain.empty(),
            bootstrapPrompt = BootstrapPromptProvider.build(),
            promptResolver = SimpleBootstrapPromptResolver(BootstrapPromptProvider.build()),
            contextRouter = null,
            loopBudget = LoopBudgetConfig(),
            loopHistoryStore = InMemoryLoopHistoryStore(),
            activationStore = ToolActivationStore(),
            nowEpochMillis = { System.currentTimeMillis() },
            probeAttachmentProvider = { _, runId ->
                if (probesEnabled) {
                    ProbeRunAttachment.open(
                        reliabilityRoot = root,
                        runId = runId,
                        sessionId = "session-1",
                        requestId = runId,
                        packs = listOf(benchCorePack()),
                        runMetaBuilder = { overheadRunMeta(runId) },
                    )
                } else {
                    ProbeRunAttachment.disabled(runId)
                }
            },
        )

    private fun assertArtifacts(root: File, runId: String, probesEnabled: Boolean) {
        val dir = probeRunDirectory(root, runId)
        if (probesEnabled) {
            assertTrue(File(dir, PROBES_JSONL).isFile)
            assertTrue(File(dir, PROBE_COMPLETENESS_JSON).isFile)
            assertTrue(File(dir, RUN_META_JSON).isFile)
            val text = File(dir, PROBES_JSONL).readText()
            assertTrue(text.contains("\"stageId\":\"run.terminal\""))
            assertTrue(text.contains("\"stageId\":\"probe.completeness\""))
        } else {
            assertFalse(File(dir, PROBES_JSONL).exists())
            assertFalse(File(dir, PROBE_COMPLETENESS_JSON).exists())
            assertFalse(File(dir, RUN_META_JSON).exists())
            assertTrue(listProbeArtifactFiles(root, runId).isEmpty())
        }
    }

    private fun overheadModelClient(): FakeModelClient = FakeModelClient(
        eventsByRound = listOf(
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

    private fun overheadRunMeta(runId: String) = buildRunMeta(
        runId = runId,
        sessionId = "session-1",
        armId = "default",
        buildId = "test-build",
        startedAtEpochMillis = System.currentTimeMillis(),
        provider = "fixture",
        protocol = "native",
        model = "m1",
        baseUrlHost = "local",
        isolation = IsolationDeclaration("fresh", "cleared", "cold"),
        caseBinding = CaseBinding("none"),
        enabledProbePacks = listOf("bench_core"),
        toolset = emptyList(),
    )

    private fun medianMs(samples: List<Double>): Double {
        require(samples.size == MEASURED_RUNS) {
            "need $MEASURED_RUNS measured runs, got ${samples.size}"
        }
        val sorted = samples.sorted()
        return sorted[MEASURED_RUNS / 2]
    }

    private companion object {
        const val MEASURED_RUNS = 5
        const val INNER_LOOPS = 3
        const val RELATIVE_BUDGET = 0.02
        const val ABSOLUTE_BUDGET_MS = 200.0
    }
}
