@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.*
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.core.FileBackedLoopHistoryStore
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConfirmationContextResumeTest {
    @Test fun confirmationPreservesExactContextWithoutRepeatingProvider() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        var providerCalls = 0
        val provider = object : RuntimeContextProvider {
            override val id = "resume-context"
            override suspend fun provide(request: RuntimeContextRequest): RuntimeContextContribution {
                providerCalls++
                assertEquals(setOf("media.volume"), request.currentStateKeys)
                return RuntimeContextContribution(id, listOf(RuntimeContextBlock(
                    id = "fact", stability = ContextStability.LIVE,
                    data = listOf(RuntimeContextDatum("v", RuntimeContextDatumKind.FACT, "self", "context_fact", RuntimeContextScalar.Text("RESUME_SENTINEL"))),
                    payloadDigest = "fact",
                )), "fingerprint")
            }
        }
        val root = Files.createTempDirectory("confirmation-context-").toFile()
        val runtime = AgentRuntimeBuilder.create().workspaceRoot(root)
            .toolProvider(LoopMcpProvider(exposedTools = listOf(mcpTool("set_temperature"))))
            .hook(confirmUntilApproved()).contextProvider(provider)
            .modelClient(backendThenFinishModel(requests)).build()
        try {
            val events = runtime.run(AgentRequest("context-run", "context-session", "set temperature", currentStateKeys = setOf("media.volume"))).toList()
            val pending = events.filterIsInstance<AgentEvent.ConfirmationRequired>().single().pending.pendingId
            runtime.resumeConfirmation(approve(pending))
            assertTrue(requests.size >= 3)
            assertTrue(requests.all { it.system.orEmpty().contains("RESUME_SENTINEL") })
            assertEquals(1, providerCalls)
        } finally { runtime.close() }
    }

    @Test fun suffixPersistsAcrossHistoryStoreInstances() {
        val root = Files.createTempDirectory("context-history-").toFile()
        FileBackedLoopHistoryStore(root).save("run", emptyList(), contextSystemSuffix = "context\n事实")
        assertEquals("context\n事实", FileBackedLoopHistoryStore(root).read("run")?.contextSystemSuffix)
    }
}
