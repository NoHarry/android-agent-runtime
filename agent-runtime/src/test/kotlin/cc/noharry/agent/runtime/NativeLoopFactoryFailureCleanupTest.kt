@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration

import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeCompletionOutcome
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.runtime.probe.PROBES_JSONL
import cc.noharry.agent.runtime.probe.ProbeRunAttachment
import cc.noharry.agent.runtime.probe.benchCorePack
import cc.noharry.agent.runtime.probe.probeRunDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import cc.noharry.agent.kernel.core.*

class NativeLoopFactoryFailureCleanupTest {
    @Test
    fun unexpectedExceptionCleansUpExactlyOnceAndFailsClosed() = runBlocking {
        val root = Files.createTempDirectory("loop-factory-fail-").toFile()
        val runId = "request-fail"
        lateinit var attachment: ProbeRunAttachment
        val factory = cleanupFactory(
            root = root,
            runId = runId,
            modelClient = FakeModelClient(
                eventsByRound = listOf(
                    listOf(ModelEvent.ToolCall("f1", "finish", """{"response":"done"}"""), ModelEvent.Completed),
                ),
            ),
            promptResolver = BootstrapPromptResolver { _, _ -> error("prompt exploded") },
            attachmentSink = { attachment = it },
        )
        try {
            val outcome = factory.run(AgentRequest(runId, "session-1", "hello"), runId, workspaceFileSystem = null) {}
            assertTrue(outcome is NativeLoopOutcome.Terminal)
            val terminal = outcome as NativeLoopOutcome.Terminal
            assertEquals(RuntimeRunStatus.FAILED, terminal.result.status)
            assertEquals(RuntimeCompletionOutcome.FAILED, terminal.result.completion.outcome)
            assertTrue(attachment.isFinished())
            assertFalse(factory.hasProbeAttachment(runId))
            assertEquals(1, terminalCount(root, runId))
            factory.finishProbeRun(runId, "FAILED")
            assertEquals(1, terminalCount(root, runId))
            assertTrue(attachment.isFinished())
        } finally {
            factory.closeAll()
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationCleansUpExactlyOnceAndPropagates() = runBlocking {
        val root = Files.createTempDirectory("loop-factory-cancel-").toFile()
        val runId = "request-cancel"
        lateinit var attachment: ProbeRunAttachment
        val factory = cleanupFactory(
            root = root,
            runId = runId,
            modelClient = object : ModelClient {
                override val providerConcurrency: ProviderConcurrencyDeclaration =
                    ProviderConcurrencyDeclaration.serialized()
                override fun complete(request: ModelRequest): Flow<ModelEvent> = flow {
                    throw CancellationException("model cancelled")
                }
            },
            attachmentSink = { attachment = it },
        )
        try {
            var thrown: CancellationException? = null
            try {
                factory.run(AgentRequest(runId, "session-1", "hello"), runId, workspaceFileSystem = null) {}
            } catch (cancelled: CancellationException) {
                thrown = cancelled
            }
            assertEquals("model cancelled", thrown?.message)
            assertTrue(attachment.isFinished())
            assertFalse(factory.hasProbeAttachment(runId))
            assertEquals(1, terminalCount(root, runId))
            factory.finishProbeRun(runId, "CANCELLED")
            assertEquals(1, terminalCount(root, runId))
        } finally {
            factory.closeAll()
            root.deleteRecursively()
        }
    }

    private fun cleanupFactory(
        root: File,
        runId: String,
        modelClient: ModelClient,
        promptResolver: BootstrapPromptResolver = SimpleBootstrapPromptResolver(BootstrapPromptProvider.build()),
        attachmentSink: (ProbeRunAttachment) -> Unit,
    ): NativeLoopFactory {
        val prompt = BootstrapPromptProvider.build()
        return NativeLoopFactory(
            modelClient = modelClient,
            mcpRegistry = ToolProviderRegistry(),
            materializerSchemaCache = null,
            traceRedactor = DefaultTraceRedactor,
            hookChain = RuntimeHookChain.empty(),
            bootstrapPrompt = prompt,
            promptResolver = promptResolver,
            contextRouter = null,
            loopBudget = LoopBudgetConfig(),
            loopHistoryStore = InMemoryLoopHistoryStore(),
            activationStore = ToolActivationStore(),
            nowEpochMillis = { 1_700_000_000_000L },
            probeAttachmentProvider = { _, id ->
                ProbeRunAttachment.open(
                    reliabilityRoot = root,
                    runId = id,
                    sessionId = "session-1",
                    requestId = id,
                    packs = listOf(benchCorePack()),
                    runMetaBuilder = { null },
                ).also { if (id == runId) attachmentSink(it) }
            },
        )
    }

    private fun terminalCount(root: File, runId: String): Int {
        val probes = File(probeRunDirectory(root, runId), PROBES_JSONL)
        if (!probes.isFile) return 0
        return probes.readLines().count { it.contains("\"stageId\":\"run.terminal\"") }
    }
}
