package cc.noharry.agent.kernel.core

import cc.noharry.agent.kernel.api.CapabilityCardinality
import cc.noharry.agent.kernel.api.ExperimentalAgentRuntimeApi
import cc.noharry.agent.kernel.api.KernelApiRange
import cc.noharry.agent.kernel.api.KernelApiVersion
import cc.noharry.agent.kernel.api.PluginContractMessages
import cc.noharry.agent.kernel.api.PluginVersion
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalAgentRuntimeApi::class)
class PluginPreflightTest {
    @Test
    fun missingRequiredCapability_failsBeforeInstall() {
        val required = testKey("needed")
        var installed = false
        val error = assertThrows(IllegalArgumentException::class.java) {
            compile(
                testPlugin(
                    id = "consumer",
                    descriptor = testDescriptor("consumer", requires = setOf(required)),
                ) { installed = true },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.MISSING_REQUIRED_CAPABILITY))
        assertTrue(!installed)
    }

    @Test
    fun dependencyCycle_failsBeforeInstall() {
        val left = testKey("left")
        val right = testKey("right")
        var installed = false
        val error = assertThrows(IllegalStateException::class.java) {
            compile(
                testPlugin(
                    id = "a",
                    descriptor = testDescriptor("a", provides = setOf(left), requires = setOf(right)),
                ) { installed = true },
                testPlugin(
                    id = "b",
                    descriptor = testDescriptor("b", provides = setOf(right), requires = setOf(left)),
                ) { installed = true },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.DEPENDENCY_CYCLE))
        assertTrue(!installed)
    }

    @Test
    fun invalidPluginVersion_isRejected() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            PluginVersion(-1, 0, 0)
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.INVALID_PLUGIN_VERSION))
    }

    @Test
    fun incompatibleKernelRange_failsBeforeInstall() {
        var installed = false
        val error = assertThrows(IllegalArgumentException::class.java) {
            compile(
                testPlugin(
                    id = "future",
                    descriptor = testDescriptor(
                        id = "future",
                        range = KernelApiRange(KernelApiVersion(2, 0), KernelApiVersion(2, 0)),
                    ),
                ) { installed = true },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.INCOMPATIBLE_KERNEL_API))
        assertTrue(!installed)
    }

    @Test
    fun duplicateSingleInDescriptors_failsBeforeInstall() {
        val key = testKey("once")
        var installed = false
        val error = assertThrows(IllegalArgumentException::class.java) {
            compile(
                testPlugin(
                    id = "first",
                    descriptor = testDescriptor("first", provides = setOf(key)),
                ) {
                    installed = true
                    provide(key, "a", testSerializedConcurrency)
                },
                testPlugin(
                    id = "second",
                    descriptor = testDescriptor("second", provides = setOf(key)),
                ) {
                    installed = true
                    provide(key, "b", testSerializedConcurrency)
                },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.DUPLICATE_SINGLE))
        assertTrue(!installed)
    }

    @Test
    fun duplicatePluginIds_failBeforeInstall() {
        var installed = false
        val error = assertThrows(IllegalArgumentException::class.java) {
            compile(
                testPlugin("dup") { installed = true },
                testPlugin("dup") { installed = true },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.DUPLICATE_PLUGIN_ID))
        assertTrue(!installed)
    }

    @Test
    fun optionalRequirement_doesNotBlockWhenMissing() {
        val optional = testKey("optional")
        val blueprint = compile(
            testPlugin(
                id = "optional-consumer",
                descriptor = testDescriptor("optional-consumer", optionalRequires = setOf(optional)),
            ),
        )
        assertTrue(blueprint.installedPluginIds.contains("optional-consumer"))
        blueprint.close()
    }

    @Test
    fun ambiguousDiagnosticIds_failBeforeInstall() {
        val first = testKey("same")
        val second = testKey("same")
        var installed = false
        val error = assertThrows(IllegalArgumentException::class.java) {
            compile(
                testPlugin(
                    id = "first",
                    descriptor = testDescriptor("first", provides = setOf(first)),
                ) {
                    installed = true
                    provide(first, "a", testSerializedConcurrency)
                },
                testPlugin(
                    id = "second",
                    descriptor = testDescriptor("second", provides = setOf(second)),
                ) {
                    installed = true
                    provide(second, "b", testSerializedConcurrency)
                },
            )
        }
        assertTrue(error.message.orEmpty().contains(RuntimeCompositionMessages.AMBIGUOUS_DIAGNOSTIC_ID))
        assertTrue(!installed)
    }

    @Test
    fun majorMismatch_failsBeforeInstall() {
        val required = testKey("cap", major = 1)
        val provided = testKey("cap", major = 2)
        var installed = false
        val error = assertThrows(IllegalArgumentException::class.java) {
            compile(
                testPlugin(
                    id = "provider",
                    descriptor = testDescriptor("provider", provides = setOf(provided)),
                ) {
                    installed = true
                    provide(provided, "v2", testSerializedConcurrency)
                },
                testPlugin(
                    id = "consumer",
                    descriptor = testDescriptor("consumer", requires = setOf(required)),
                ) { installed = true },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.CAPABILITY_MAJOR_MISMATCH))
        assertTrue(!installed)
    }

    @Test
    fun declaredProvidesMustExactlyMatchInstalledKeys() {
        val declared = testKey("declared")
        var installed = false
        val error = assertThrows(IllegalStateException::class.java) {
            compile(
                testPlugin(
                    id = "missing-provide",
                    descriptor = testDescriptor("missing-provide", provides = setOf(declared)),
                ) { installed = true },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.PROVIDES_MISMATCH))
        assertTrue(installed)
    }

    @Test
    fun undeclaredExtraProvideFailsEvenWhenDeclaredSetIsEmpty() {
        val extra = testKey("sneak")
        var installed = false
        val error = assertThrows(IllegalStateException::class.java) {
            compile(
                testPlugin("empty-declared") {
                    installed = true
                    provide(extra, "extra", testSerializedConcurrency)
                },
            )
        }
        assertTrue(error.message.orEmpty().contains(PluginContractMessages.PROVIDES_MISMATCH))
        assertTrue(installed)
    }

    private fun compile(vararg plugins: cc.noharry.agent.kernel.api.AgentPlugin): RuntimeBlueprint =
        ProfileCompiler().compile(testProfile(), plugins.toList())
}
