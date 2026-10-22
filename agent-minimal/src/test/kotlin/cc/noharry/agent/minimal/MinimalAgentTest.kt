@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.minimal

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentEvent
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.WorkspaceCallContext
import cc.noharry.agent.kernel.api.WorkspaceExecutionResult
import cc.noharry.agent.kernel.api.WorkspacePort
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import cc.noharry.agent.kernel.api.reliability.RuntimeErrorCode
import cc.noharry.agent.kernel.api.reliability.RuntimeRunStatus
import cc.noharry.agent.kernel.core.KernelRuntimeBuilder
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class MinimalAgentTest {
    @Test
    fun profileUsesSingleKernelLoopWithNoOptionalToolsAndTerminalResult() = runBlocking {
        val requests = mutableListOf<ModelRequest>()
        val model = finishModel(requests)
        val workspace = InMemoryWorkspace()
        val runtime = MinimalAgent.build(model, workspace)

        try {
            assertTrue(MinimalAgent.PROFILE.id == MinimalAgent.PROFILE_ID)
            assertTrue(MinimalAgent.builder(model, workspace) is KernelRuntimeBuilder)
            val events = runtime.run(AgentRequest("request-1", "session-1", "finish")).toList()
            assertTrue(events.first() is AgentEvent.Started)
            assertTrue(events.any { it is AgentEvent.ResultAvailable })
            assertTrue(events.last() is AgentEvent.Finished)
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-1")?.status)
            val defaultRejected = runtime.run(
                AgentRequest("request-other-ws", "session-1", "finish", workspaceId = "other"),
            ).toList()
            assertEquals(
                RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode,
                defaultRejected.filterIsInstance<AgentEvent.Failed>().single().code,
            )
            assertTrue(requests.single().tools.any { it.name == "finish" })
            assertFalse(requests.single().tools.any { it.name == "web_fetch" })
            assertFalse(requests.single().tools.any { it.name.startsWith("mcp__") })
        } finally {
            runtime.close()
        }
    }
}

class MinimalRuntimeConformanceTest {
    @Test
    fun sharesTheSingleNativeToolLoopDeclaration() {
        assertEquals(
            "cc.noharry.agent.kernel.core.NativeToolLoop",
            cc.noharry.agent.kernel.core.NativeToolLoop::class.java.name,
        )
        org.junit.Assert.assertThrows(ClassNotFoundException::class.java) {
            Class.forName("cc.noharry.agent.runtime.NativeToolLoop")
        }
        org.junit.Assert.assertThrows(ClassNotFoundException::class.java) {
            Class.forName("cc.noharry.agent.minimal.NativeToolLoop")
        }
        assertEquals(listOf("build", "builder"), publicMinimalMethods())
    }

    private fun publicMinimalMethods(): List<String> =
        MinimalAgent::class.java.methods
            .filter { it.declaringClass == MinimalAgent::class.java }
            .map { it.name }
            .filterNot { it.startsWith("get") || "$" in it }
            .distinct()
            .sorted()

    @Test
    fun pluginWorkspaceAndModelKeepIdentityAndCloseIsIdempotent() = runBlocking {
        val model = finishModel()
        val workspace = InMemoryWorkspace()
        val seen = mutableListOf<Any?>()
        val probe = object : AgentPlugin {
            override val id: String = "identity-probe"
            override val descriptor = AgentPluginDescriptor(
                id = "identity-probe",
                pluginVersion = PluginVersion(1, 0, 0),
                kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
                requires = setOf(AgentCapabilities.MODEL_CLIENT, AgentCapabilities.WORKSPACE),
            )

            override fun install(scope: AgentPluginScope) {
                seen += scope.resolve(AgentCapabilities.MODEL_CLIENT)
                seen += scope.resolve(AgentCapabilities.WORKSPACE)
            }
        }
        val runtime = MinimalAgent.build(model, workspace, listOf(probe))
        try {
            assertSame(model, seen[0])
            assertSame(workspace, seen[1])
            runtime.run(AgentRequest("request-close", "session-close", "finish")).toList()
            assertEquals(RuntimeRunStatus.COMPLETED, runtime.getRuntimeResult("request-close")?.status)
        } finally {
            runtime.close()
            runtime.close()
        }
    }

    @Test
    fun optionalWorkspaceIdDefaultsCompatiblyAndRejectsOtherIds() = runBlocking {
        val runtime = MinimalAgent.build(finishModel(), InMemoryWorkspace(), workspaceId = "alpha")
        try {
            val accepted = runtime.run(AgentRequest("request-ok", "session-ok", "finish")).toList()
            assertTrue(accepted.any { it is AgentEvent.Finished })
            val rejected = runtime.run(
                AgentRequest("request-bad", "session-bad", "finish", workspaceId = "beta"),
            ).toList()
            val failed = rejected.filterIsInstance<AgentEvent.Failed>().single()
            assertEquals(RuntimeErrorCode.WORKSPACE_NOT_CONFIGURED.wireCode, failed.code)
            assertTrue(!failed.recoverable)
        } finally {
            runtime.close()
        }
    }
}

private class InMemoryWorkspace : WorkspacePort {
    override suspend fun execute(
        toolName: String,
        argumentsJson: String,
        context: WorkspaceCallContext,
    ): WorkspaceExecutionResult = WorkspaceExecutionResult("""{"ok":true}""")
}

private fun finishModel(seen: MutableList<ModelRequest> = mutableListOf()): ModelClient =
    object : ModelClient {
        override val providerConcurrency: ProviderConcurrencyDeclaration =
            ProviderConcurrencyDeclaration.serialized()

        override fun complete(request: ModelRequest) =
            flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            ).also { seen += request }
    }
