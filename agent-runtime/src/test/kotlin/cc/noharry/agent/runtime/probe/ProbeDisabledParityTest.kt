@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime.probe


import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.runtime.FakeModelClient
import cc.noharry.agent.runtime.LoopMcpProvider
import cc.noharry.agent.runtime.NativeLoopFactory
import cc.noharry.agent.runtime.RuntimeHookChain
import cc.noharry.agent.kernel.core.SimpleBootstrapPromptResolver
import cc.noharry.agent.runtime.agentRequest
import cc.noharry.agent.vfs.VfsFileSystem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import cc.noharry.agent.kernel.core.*
import cc.noharry.agent.runtime.mcpTool

/**
 * Real observe-only parity (A2 / SPEC-RPB-002): the same scripted workload runs through
 * the production [NativeLoopFactory] wiring twice — probes disabled vs `bench_core` —
 * and must yield identical result, summary, ordering, and public control events.
 */
class ProbeDisabledParityTest {

    @Test
    fun disabledVsEnabledSameLoopWorkloadProducesIdenticalOutcome() = runBlocking {
        val offRoot = Files.createTempDirectory("probe-parity-off").toFile()
        val onRoot = Files.createTempDirectory("probe-parity-on").toFile()
        try {
            val off = runWorkload(offRoot, probesEnabled = false) as NativeLoopOutcome.Terminal
            val on = runWorkload(onRoot, probesEnabled = true) as NativeLoopOutcome.Terminal

            // Enabling probes must not change runtime result, summary, or control events.
            assertEquals(off.result, on.result)
            assertEquals(off.summary, on.summary)
            assertEquals(off.events, on.events)

            // The enabled run produced real probe evidence (comparison is not vacuous).
            val onDir = probeRunDirectory(onRoot, PARITY_RUN_ID)
            val probes = File(onDir, PROBES_JSONL)
            assertTrue(probes.isFile)
            val text = probes.readText()
            assertTrue(text.contains("\"stageId\":\"model.round.start\""))
            assertTrue(text.contains("\"stageId\":\"tool.execute.start\""))
            assertTrue(text.contains("\"stageId\":\"tool.execute.end\""))
            assertTrue(text.contains("mcp__default__search_docs"))
            assertTrue(text.contains("\"stageId\":\"run.terminal\""))
            assertTrue(text.contains("\"stageId\":\"case.bound\""))
            assertTrue(text.contains("\"stageId\":\"probe.completeness\""))
            assertTrue(File(onDir, PROBE_COMPLETENESS_JSON).readText().contains("\"complete\":true"))
            assertTrue(File(onDir, RUN_META_JSON).isFile)

            // The disabled run wrote no probe artifacts.
            assertFalse(File(probeRunDirectory(offRoot, PARITY_RUN_ID), PROBES_JSONL).exists())
        } finally {
            offRoot.deleteRecursively()
            onRoot.deleteRecursively()
        }
    }

    private suspend fun runWorkload(root: File, probesEnabled: Boolean): NativeLoopOutcome {
        val vfs = VfsFileSystem.singleFileRoot(
            Files.createTempDirectory("probe-parity-vfs-").toFile(),
        )
        val factory = NativeLoopFactory(
            modelClient = parityModelClient(),
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
            nowEpochMillis = { PARITY_EPOCH_MILLIS },
            probeAttachmentProvider = { _, runId ->
                if (probesEnabled) {
                    ProbeRunAttachment.open(
                        reliabilityRoot = root,
                        runId = runId,
                        sessionId = "session-1",
                        requestId = runId,
                        packs = listOf(benchCorePack()),
                        runMetaBuilder = { parityRunMeta(runId) },
                    )
                } else {
                    ProbeRunAttachment.disabled(runId)
                }
            },
        )
        val outcome = factory.run(agentRequest(), PARITY_RUN_ID, workspaceFileSystem = vfs) {}
        // Probe terminal drain is owned by the runtime coordinator; finalize explicitly here.
        if (outcome is NativeLoopOutcome.Terminal) {
            factory.finishProbeRun(PARITY_RUN_ID, outcome.result.status.name)
        }
        return outcome
    }

    private fun parityModelClient(): FakeModelClient = FakeModelClient(
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

    private fun parityRunMeta(runId: String) = buildRunMeta(
        runId = runId,
        sessionId = "session-1",
        armId = "default",
        buildId = "test-build",
        startedAtEpochMillis = PARITY_EPOCH_MILLIS,
        provider = "fixture",
        protocol = "native",
        model = "m1",
        baseUrlHost = "local",
        isolation = IsolationDeclaration("fresh", "cleared", "cold"),
        caseBinding = CaseBinding("none"),
        enabledProbePacks = listOf("bench_core"),
        toolset = emptyList(),
    )

    private companion object {
        const val PARITY_RUN_ID = "request-1"
        const val PARITY_EPOCH_MILLIS = 1_700_000_000_000L
    }
}
