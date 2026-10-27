@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.AgentRequest
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KernelPluginIntegrationTest {
    @Test
    fun pluginModelRunsThroughTheSingleNativeLoopAndOwnedResourceClosesOnce() = runBlocking {
        var closeCount = 0
        val seenRequests = mutableListOf<ModelRequest>()
        val runtime = AgentRuntimeBuilder.create()
            .plugin(
                testPlugin(
                    "model",
                    testDescriptor("model", provides = setOf(AgentCapabilities.MODEL_CLIENT)),
                ) {
                    provide(
                        AgentCapabilities.MODEL_CLIENT,
                        terminalModel(seenRequests),
                        testSerializedConcurrency,
                    )
                    own(AutoCloseable { closeCount++ })
                },
            )
            .build()

        runtime.run(AgentRequest("request-1", "session-1", "finish")).toList()
        runtime.close()
        runtime.close()

        assertEquals(1, seenRequests.size)
        assertEquals(1, closeCount)
        assertTrue(seenRequests.single().tools.any { it.name == "finish" })
    }

    @Test
    fun describedPluginModelRunsThroughTheSingleNativeLoop() = runBlocking {
        val seenRequests = mutableListOf<ModelRequest>()
        val model = terminalModel(seenRequests)
        val modelKey = AgentCapabilities.MODEL_CLIENT
        val runtime = AgentRuntimeBuilder.create()
            .plugin(
                object : AgentPlugin {
                    override val id: String = "described-model"
                    override val descriptor = AgentPluginDescriptor(
                        id = "described-model",
                        pluginVersion = PluginVersion(1, 0, 0),
                        kernelApiRange = KernelApiRange(
                            KernelApiVersions.CURRENT,
                            KernelApiVersions.CURRENT,
                        ),
                        provides = setOf(modelKey),
                    )

                    override fun install(scope: AgentPluginScope) {
                        scope.provide(modelKey, model, testSerializedConcurrency)
                    }
                },
            )
            .build()

        runtime.run(AgentRequest("request-2", "session-2", "finish")).toList()
        runtime.close()
        assertEquals(1, seenRequests.size)
    }

    @Test
    fun directAndPluginSingleCapabilityConflictFailsBuildBeforeInstallSideEffects() {
        var installed = false
        val model = terminalModel(mutableListOf())
        val builder = AgentRuntimeBuilder.create()
            .modelClient(model)
            .plugin(
                object : AgentPlugin {
                    override val id: String = "duplicate-model"
                    override val descriptor = AgentPluginDescriptor(
                        id = "duplicate-model",
                        pluginVersion = PluginVersion(1, 0, 0),
                        kernelApiRange = KernelApiRange(
                            KernelApiVersions.CURRENT,
                            KernelApiVersions.CURRENT,
                        ),
                        provides = setOf(AgentCapabilities.MODEL_CLIENT),
                    )

                    override fun install(scope: AgentPluginScope) {
                        installed = true
                        scope.own(AutoCloseable { })
                        scope.provide(
                            AgentCapabilities.MODEL_CLIENT,
                            model,
                            testSerializedConcurrency,
                        )
                    }
                },
            )

        val error = assertThrows(IllegalArgumentException::class.java) { builder.build() }
        assertTrue(error.message.orEmpty().contains("duplicate SINGLE"))
        assertTrue(!installed)
    }

    private fun terminalModel(seen: MutableList<ModelRequest>): ModelClient =
        object : ModelClient {
            override val providerConcurrency = testSerializedConcurrency

            override fun complete(request: ModelRequest) =
                flowOf(
                    ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                    ModelEvent.Completed,
                ).also { seen += request }
        }
}
