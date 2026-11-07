@file:OptIn(cc.noharry.agent.spi.ExperimentalAgentRuntimeApi::class)

package cc.noharry.agent.runtime
import cc.noharry.agent.spi.AgentCapabilities
import cc.noharry.agent.spi.AgentPlugin
import cc.noharry.agent.spi.AgentPluginDescriptor
import cc.noharry.agent.spi.AgentPluginScope
import cc.noharry.agent.spi.ExperimentalAgentRuntimeApi
import cc.noharry.agent.spi.KernelApiRange
import cc.noharry.agent.spi.KernelApiVersions
import cc.noharry.agent.spi.PluginContractMessages
import cc.noharry.agent.spi.PluginVersion
import cc.noharry.agent.spi.ProviderConcurrencyDeclaration
import cc.noharry.agent.spi.SessionCapabilityFactory
import cc.noharry.agent.spi.model.ModelClient
import cc.noharry.agent.spi.model.ModelEvent
import cc.noharry.agent.spi.model.ModelRequest
import cc.noharry.agent.kernel.core.plugin.RuntimeCapabilityDeclarations
import cc.noharry.agent.kernel.core.plugin.RuntimeCompositionMessages
import cc.noharry.agent.runtime.run.RuntimeProfileAdapter
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

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
                .buildEngine()
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
                        val factory = SessionCapabilityFactory<cc.noharry.agent.spi.model.ModelClient> {
                            preflightFinishModel()
                        }
                        provide(AgentCapabilities.MODEL_CLIENT, factory)
                    },
                )
                .buildEngine()
        }
        assertTrue(
            error.message.orEmpty().contains(RuntimeCompositionMessages.UNSUPPORTED_FACTORY_COMPOSITION),
        )
    }

    @Test
    fun noncanonicalBuiltinDiagnostic_failsAtRuntimeAdapter() {
        val alias = cc.noharry.agent.spi.CapabilityKey<cc.noharry.agent.spi.ToolProvider>(
            "tool_provider",
            cc.noharry.agent.spi.CapabilityCardinality.MULTIPLE,
        )
        val error = assertThrows(IllegalStateException::class.java) {
            RuntimeProfileAdapter.compile(
                testProfile("noncanonical"),
                RuntimeCapabilityDeclarations.Collector().snapshot(),
                listOf(
                    describedPlugin("alias-tools", alias) {
                        provide(
                            alias,
                            object : cc.noharry.agent.spi.ToolProvider {
                                override val providerId: String = "alias"
                                override val providerConcurrency = testSerializedConcurrency
                                override suspend fun listTools() = emptyList<cc.noharry.agent.spi.ToolDescriptor>()
                                override suspend fun callTool(
                                    toolName: String,
                                    argumentsJson: String,
                                    context: cc.noharry.agent.spi.ToolCallContext,
                                ) = cc.noharry.agent.spi.ToolCallResult(success = true, resultJson = "{}")
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
                .buildEngine()
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
    key: cc.noharry.agent.spi.CapabilityKey<*>,
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
