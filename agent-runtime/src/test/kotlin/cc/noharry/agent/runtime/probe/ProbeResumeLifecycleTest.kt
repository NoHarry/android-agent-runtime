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
import cc.noharry.agent.runtime.NetworkToolBackend
import cc.noharry.agent.runtime.agentRequest
import cc.noharry.agent.runtime.run.NativeLoopFactory
import cc.noharry.agent.runtime.run.RuntimeHookChain
import cc.noharry.agent.transport.WebFetchRequest
import cc.noharry.agent.transport.WebFetchResponse
import cc.noharry.agent.transport.WebFetchTransport
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * Pause/resume lifecycle (SPEC-RPB-002): the probe attachment opened by the initial run
 * must survive RequiresConfirmation and NeedsUserInput pauses inside [NativeLoopFactory]
 * and the resumed terminal must complete the original drain, completeness side-car, and
 * run-meta on the same run directory.
 */
class ProbeResumeLifecycleTest {

    @Test
    fun pauseResumeTerminalDrainsOriginalAttachmentAndPersistsRunMeta() = runBlocking {
        val root = Files.createTempDirectory("probe-resume").toFile()
        try {
            val transport = RecordingWebFetchTransport()
            val factory = confirmationResumeFactory(transport, root)

            val waiting = factory.run(agentRequest(), RESUME_RUN_ID, workspaceFileSystem = null) {}
            assertTrue(waiting is NativeLoopOutcome.RequiresConfirmation)
            // Paused: attachment stays open; terminal artifacts are not persisted yet.
            val dir = probeRunDirectory(root, RESUME_RUN_ID)
            assertFalse(File(dir, RUN_META_JSON).isFile)

            val pending = (waiting as NativeLoopOutcome.RequiresConfirmation).pendingToolUse
            val terminal = factory.resumeConfirmedToolUse(requireNotNull(pending)) {}
            assertTrue(terminal is NativeLoopOutcome.Terminal)
            // Probe terminal drain is owned by the runtime coordinator; finalize explicitly here.
            factory.finishProbeRun(
                RESUME_RUN_ID,
                (terminal as NativeLoopOutcome.Terminal).result.status.name,
            )

            assertOriginalAttachmentDrained(dir)
            val lines = File(dir, PROBES_JSONL).readLines().filter { it.isNotBlank() }
            assertTrue(
                lines.any {
                    it.contains("\"stageId\":\"tool.execute.end\"") && it.contains("web_fetch")
                },
            )
            assertTrue(lines.any { it.contains("\"stageId\":\"network.fetch\"") })
            assertEquals(listOf("https://example.com/page"), transport.urls)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertOriginalAttachmentDrained(dir: File) {
        val probes = File(dir, PROBES_JSONL)
        assertTrue(probes.isFile)
        val lines = probes.readLines().filter { it.isNotBlank() }
        assertTrue(lines.any { it.contains("\"stageId\":\"run.start\"") })
        assertEquals(1, lines.count { it.contains("\"stageId\":\"run.terminal\"") })
        assertEquals(1, lines.count { it.contains("\"stageId\":\"run.finish\"") })
        assertTrue(File(dir, PROBE_COMPLETENESS_JSON).readText().contains("\"complete\":true"))
        assertTrue(File(dir, RUN_META_JSON).isFile)
        // A6: finish-time toolset must be non-empty for a real model catalog.
        assertFalse(File(dir, RUN_META_JSON).readText().contains("\"toolset\":[]"))
    }

    private fun confirmationResumeFactory(
        transport: RecordingWebFetchTransport,
        root: File,
    ): NativeLoopFactory = baseFactory(
        root = root,
        modelClient = FakeModelClient(
            eventsByRound = listOf(
                listOf(
                    ModelEvent.ToolCall(
                        "network-1",
                        "web_fetch",
                        """{"url":"https://example.com/page","max_bytes":64}""",
                    ),
                    ModelEvent.Completed,
                ),
                listOf(
                    ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                    ModelEvent.Completed,
                ),
            ),
        ),
        networkToolBackend = NetworkToolBackend(transport),
    )

    private fun baseFactory(
        root: File,
        modelClient: FakeModelClient,
        networkToolBackend: NetworkToolBackend = NetworkToolBackend(),
    ): NativeLoopFactory {
        val activationStore = ToolActivationStore()
        return NativeLoopFactory(
            modelClient = modelClient,
            mcpRegistry = ToolProviderRegistry(),
            materializerSchemaCache = null,
            traceRedactor = DefaultTraceRedactor,
            hookChain = RuntimeHookChain.empty(),
            bootstrapPrompt = BootstrapPromptProvider.build(),
            promptResolver = SimpleBootstrapPromptResolver(BootstrapPromptProvider.build()),
            contextRouter = null,
            loopBudget = LoopBudgetConfig(),
            loopHistoryStore = InMemoryLoopHistoryStore(),
            activationStore = activationStore,
            nowEpochMillis = { RESUME_EPOCH_MILLIS },
            networkToolBackend = networkToolBackend,
            probeAttachmentProvider = { _, runId ->
                ProbeRunAttachment.open(
                    reliabilityRoot = root,
                    runId = runId,
                    sessionId = "session-1",
                    requestId = runId,
                    packs = listOf(benchCorePack()),
                    runMetaBuilder = {
                        buildRunMeta(
                            runId = runId,
                            sessionId = "session-1",
                            armId = "default",
                            buildId = "test-build",
                            startedAtEpochMillis = RESUME_EPOCH_MILLIS,
                            provider = "fixture",
                            protocol = "native",
                            model = "m1",
                            baseUrlHost = "local",
                            isolation = IsolationDeclaration("fresh", "cleared", "cold"),
                            caseBinding = CaseBinding("none"),
                            enabledProbePacks = listOf("bench_core"),
                            toolset = snapshotAdvertisedToolset(
                                runId = runId,
                                activationStore = activationStore,
                                nativeWebFetchEnabled = true,
                            ),
                        )
                    },
                )
            },
        )
    }

    private class RecordingWebFetchTransport : WebFetchTransport {
        val urls = mutableListOf<String>()

        override suspend fun execute(request: WebFetchRequest): WebFetchResponse {
            urls += request.url
            return WebFetchResponse(
                statusCode = 200,
                headers = mapOf("content-type" to listOf("text/plain")),
                body = "hello".toByteArray(),
            )
        }
    }

    private companion object {
        const val RESUME_RUN_ID = "request-1"
        const val RESUME_EPOCH_MILLIS = 1_700_000_000_000L
    }
}
