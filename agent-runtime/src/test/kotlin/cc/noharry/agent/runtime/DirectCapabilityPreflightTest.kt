@file:OptIn(cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime

import cc.noharry.agent.kernel.api.AgentCapabilities
import cc.noharry.agent.kernel.api.AgentPlugin
import cc.noharry.agent.kernel.api.AgentPluginDescriptor
import cc.noharry.agent.kernel.api.AgentPluginScope
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersions
import cc.noharry.agent.kernel.api.PluginContractMessages
import cc.noharry.agent.kernel.api.PluginVersion
import cc.noharry.agent.kernel.api.ProviderConcurrencyDeclaration
import cc.noharry.agent.kernel.api.SessionCapabilityFactory
import cc.noharry.agent.kernel.api.model.ModelClient
import cc.noharry.agent.kernel.api.model.ModelEvent
import cc.noharry.agent.kernel.api.model.ModelRequest
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import cc.noharry.agent.kernel.core.RuntimeCapabilityDeclarations
import cc.noharry.agent.kernel.core.RuntimeCompositionMessages

class DirectCapabilityPreflightTest {
    @Test
    fun declaredDirectAndPluginSingleCollisionIsSideEffectFree() {
        var installed = false
        val model = preflightFinishModel()
        val error = assertThrows(IllegalArgumentException::class.java) {
            AgentRuntimeBuilder.create()
                .modelClient(model)
                .plugin(describedPlugin("dup-model", AgentCapabilities.MODEL_CLIENT) {
                    installed = true
                    provide(AgentCapabilities.MODEL_CLIENT, model, testSerializedConcurrency)
                })
                .build()
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.DUPLICATE_SINGLE))
        assertTrue(!installed)
    }

    @Test
    fun nonToolProviderSessionFactoryFailsAtBuildWithUnsupportedComposition() {
        val error = assertThrows(IllegalStateException::class.java) {
            AgentRuntimeBuilder.create()
                .plugin(
                    describedPlugin("factory-model", AgentCapabilities.MODEL_CLIENT) {
                        val factory = SessionCapabilityFactory<cc.noharry.agent.kernel.api.model.ModelClient> {
                            preflightFinishModel()
                        }
                        provide(AgentCapabilities.MODEL_CLIENT, factory)
                    },
                )
                .build()
        }
        assertTrue(
            error.message.orEmpty().contains(RuntimeCompositionMessages.UNSUPPORTED_FACTORY_COMPOSITION),
        )
    }

    @Test
    fun noncanonicalBuiltinDiagnostic_failsAtRuntimeAdapter() {
        val alias = cc.noharry.agent.kernel.api.CapabilityKey<cc.noharry.agent.kernel.api.ToolProvider>(
            "tool_provider",
            cc.noharry.agent.kernel.api.CapabilityCardinality.MULTIPLE,
        )
        val error = assertThrows(IllegalStateException::class.java) {
            RuntimeProfileAdapter.compile(
                testProfile("noncanonical"),
                RuntimeCapabilityDeclarations.Collector().snapshot(),
                listOf(
                    describedPlugin("alias-tools", alias) {
                        provide(
                            alias,
                            object : cc.noharry.agent.kernel.api.ToolProvider {
                                override val providerId: String = "alias"
                                override val providerConcurrency = testSerializedConcurrency
                                override suspend fun listTools() = emptyList<cc.noharry.agent.kernel.api.ToolDescriptor>()
                                override suspend fun callTool(
                                    toolName: String,
                                    argumentsJson: String,
                                    context: cc.noharry.agent.kernel.api.ToolCallContext,
                                ) = cc.noharry.agent.kernel.api.ToolCallResult(success = true, resultJson = "{}")
                            },
                            testSerializedConcurrency,
                        )
                    },
                ),
            )
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.NONCANONICAL_BUILTIN_KEY))
    }

    @Test
    fun directSessionFactoryModelStillFailsClosed() {
        val error = assertThrows(IllegalStateException::class.java) {
            AgentRuntimeBuilder.create()
                .modelClient(object : ModelClient {
                    override val providerConcurrency =
                        ProviderConcurrencyDeclaration.sessionFactory(1)
                    override fun complete(request: ModelRequest) = flowOf(ModelEvent.Completed)
                })
                .build()
        }
        assertTrue(error.message.orEmpty().contains("SESSION_FACTORY"))
    }
}

private fun preflightFinishModel(): ModelClient =
    object : ModelClient {
        override val providerConcurrency = testSerializedConcurrency

        override fun complete(request: ModelRequest) =
            flowOf(
                ModelEvent.ToolCall("finish-1", "finish", """{"response":"done"}"""),
                ModelEvent.Completed,
            )
    }

private fun describedPlugin(
    id: String,
    key: cc.noharry.agent.kernel.api.CapabilityKey<*>,
    install: AgentPluginScope.() -> Unit,
): AgentPlugin =
    object : AgentPlugin {
        override val id: String = id
        override val descriptor = AgentPluginDescriptor(
            id = id,
            pluginVersion = PluginVersion(1, 0, 0),
            kernelApiRange = KernelApiRange(KernelApiVersions.CURRENT, KernelApiVersions.CURRENT),
            provides = setOf(key),
        )

        override fun install(scope: AgentPluginScope) {
            scope.install()
        }
    }
